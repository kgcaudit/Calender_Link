package app.olo.calendar

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import app.olo.calendar.model.AllDay
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.MovePlan
import app.olo.calendar.model.MoveResult
import app.olo.calendar.model.mergeExdates
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.Recurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncKind
import app.olo.calendar.model.SyncStatus
import app.olo.calendar.model.androidDuration
import app.olo.calendar.model.at
import app.olo.calendar.model.classifyAndroidAccount
import app.olo.calendar.model.isGoogleHolidayCalendarId
import app.olo.calendar.model.looksLikeHolidayCalendar
import app.olo.calendar.model.parseExdates
import app.olo.calendar.model.BridgeException
import app.olo.calendar.model.BridgeFields
import app.olo.calendar.model.BridgeItem
import app.olo.calendar.model.timedDays
import app.olo.calendar.store.CalendarStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The Android Calendar Provider: the same database Samsung Calendar reads, kept
 * in sync with Google by the system's Google account sync adapter.
 *
 * Rules from CalendarContract.Events that this class keeps:
 * - all-day events are stored at UTC midnight with eventTimezone "UTC";
 * - a repeating event carries DURATION (not DTEND) next to RRULE;
 * - display reads the Instances table, which expands repeats;
 * - an update writes only the columns that changed, so values other apps keep on
 *   the row (Samsung's own columns, Google-only fields) are not overwritten.
 */
class AndroidCalendarStore(context: Context) : CalendarStore {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val zone get() = TimeZone.currentSystemDefault()
    private val accounts = android.accounts.AccountManager.get(context.applicationContext)

    override suspend fun calendars(): List<CalendarInfo> = io {
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE, Calendars.IS_PRIMARY,
            Calendars.OWNER_ACCOUNT,
        )
        resolver.query(Calendars.CONTENT_URI, projection, null, null, null).list { c ->
            val owner = c.str(Calendars.OWNER_ACCOUNT)
            val accountType = c.str(Calendars.ACCOUNT_TYPE)
            val accountName = c.str(Calendars.ACCOUNT_NAME)
            val name = c.str(Calendars.CALENDAR_DISPLAY_NAME).ifBlank { accountName }
            val writable = c.int(Calendars.CALENDAR_ACCESS_LEVEL) >= Calendars.CAL_ACCESS_CONTRIBUTOR
            CalendarInfo(
                id = c.long(Calendars._ID).toString(),
                name = name,
                accountName = accountName,
                accountType = accountType,
                kind = classifyAndroidAccount(accountType),
                colorArgb = c.int(Calendars.CALENDAR_COLOR),
                writable = writable,
                visible = c.int(Calendars.VISIBLE) == 1,
                isPrimary = c.int(Calendars.IS_PRIMARY) == 1,
                isHoliday = looksLikeHolidayCalendar(owner, accountName, name, writable),
            )
        }.sortedWith(compareBy<CalendarInfo>({ it.kind != SyncKind.GOOGLE }, { it.accountName }, { !it.isPrimary }, { it.name }))
    }

    override suspend fun occurrences(from: Instant, to: Instant): List<Occurrence> = io { instances(from, to, null, null) }

    override suspend fun search(query: String, from: Instant, to: Instant): List<Occurrence> = io {
        val q = query.trim()
        if (q.isEmpty()) return@io emptyList()
        // LIKE with the user's text escaped, so % and _ are matched literally.
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val args = arrayOf(like, like, like)
        // The Instances table only answers for a window it has expanded, so first ask the
        // Events table where the matches actually lie (first start, last end; a series that
        // never ends runs to [to]) and expand just that span, however far back it reaches.
        val span = resolver.query(
            Events.CONTENT_URI, arrayOf(Events.DTSTART, Events.DTEND, Events.LAST_DATE, Events.RRULE),
            "${Events.DELETED} = 0 AND (${Events.TITLE} LIKE ? ESCAPE '\\' OR ${Events.EVENT_LOCATION} LIKE ? ESCAPE '\\' OR ${Events.DESCRIPTION} LIKE ? ESCAPE '\\')",
            args, null,
        ).list { c ->
            val start = c.long(Events.DTSTART)
            val repeats = c.str(Events.RRULE).isNotBlank()
            val last = when {
                !c.isNull(c.getColumnIndexOrThrow(Events.LAST_DATE)) -> c.long(Events.LAST_DATE)
                repeats -> Long.MAX_VALUE
                !c.isNull(c.getColumnIndexOrThrow(Events.DTEND)) -> c.long(Events.DTEND)
                else -> start + 86_400_000L
            }
            start to last
        }
        if (span.isEmpty()) return@io emptyList()
        // A day of slack on both sides for all-day rows stored at UTC midnight.
        val first = maxOf(from.toEpochMilliseconds(), span.minOf { it.first } - 86_400_000L)
        val last = minOf(to.toEpochMilliseconds(), span.maxOf { it.second }.let { if (it == Long.MAX_VALUE) it else it + 86_400_000L })
        if (first >= last) return@io emptyList()
        instances(
            Instant.fromEpochMilliseconds(first), Instant.fromEpochMilliseconds(last),
            "(${Instances.TITLE} LIKE ? ESCAPE '\\' OR ${Instances.EVENT_LOCATION} LIKE ? ESCAPE '\\' OR ${Instances.DESCRIPTION} LIKE ? ESCAPE '\\')",
            args,
        )
    }

    private fun instances(from: Instant, to: Instant, extra: String?, extraArgs: Array<String>?): List<Occurrence> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilliseconds())
            ContentUris.appendId(it, to.toEpochMilliseconds())
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.TITLE,
            Instances.EVENT_LOCATION, Instances.DISPLAY_COLOR, Instances.CALENDAR_ID, Instances.RRULE,
            Instances.ORIGINAL_ID,
        )
        val z = zone
        // Every calendar: OLO filters by its own visibility (docs/05), not the shared VISIBLE column.
        val selection = extra
        return resolver.query(uri, projection, selection, extraArgs, "${Instances.BEGIN} ASC").list { c ->
            val begin = c.long(Instances.BEGIN)
            val end = c.long(Instances.END)
            val allDay = c.int(Instances.ALL_DAY) == 1
            val (first, last) = if (allDay) {
                AllDay.firstDay(begin) to AllDay.lastDay(begin, end)
            } else {
                timedDays(Instant.fromEpochMilliseconds(begin), Instant.fromEpochMilliseconds(end), z)
            }
            Occurrence(
                eventId = c.long(Instances.EVENT_ID).toString(),
                calendarId = c.long(Instances.CALENDAR_ID).toString(),
                title = c.str(Instances.TITLE),
                allDay = allDay,
                begin = Instant.fromEpochMilliseconds(begin),
                end = Instant.fromEpochMilliseconds(end),
                firstDay = first,
                lastDay = last,
                location = c.str(Instances.EVENT_LOCATION),
                colorArgb = c.int(Instances.DISPLAY_COLOR),
                recurring = c.str(Instances.RRULE).isNotBlank() || !c.isNull(c.getColumnIndexOrThrow(Instances.ORIGINAL_ID)),
                seriesId = c.getColumnIndexOrThrow(Instances.ORIGINAL_ID).let { i -> if (c.isNull(i)) c.long(Instances.EVENT_ID).toString() else c.getLong(i).toString() },
            )
        }
    }

    override suspend fun event(id: String): StoredEvent? = io { readEvent(id.toLong()) }

    private fun readEvent(id: Long): StoredEvent? {
        val row = readRow(id) ?: return null
        val allDay = row[Events.ALL_DAY] == 1L
        val dtStart = row[Events.DTSTART] as? Long ?: return null
        val tzId = (row[Events.EVENT_TIMEZONE] as String?).orEmpty()
        val eventZone = if (allDay) TimeZone.UTC else runCatching { TimeZone.of(tzId) }.getOrDefault(zone)
        val rule = (row[Events.RRULE] as String?)?.takeIf { it.isNotBlank() }
        val endMillis = (row[Events.DTEND] as Long?) ?: (dtStart + durationMillis(row[Events.DURATION] as String?))

        val draft = if (allDay) {
            val first = AllDay.firstDay(dtStart)
            EventDraft(
                id = id.toString(), calendarId = row[Events.CALENDAR_ID].toString(), title = row.s(Events.TITLE),
                allDay = true, startDate = first, endDate = AllDay.lastDay(dtStart, endMillis),
                timeZoneId = zone.id,
            )
        } else {
            // Edited in the event's own zone, so saving other fields never rewrites
            // EVENT_TIMEZONE (an event made abroad keeps its zone).
            val s = Instant.fromEpochMilliseconds(dtStart).toLocalDateTime(eventZone)
            val e = Instant.fromEpochMilliseconds(endMillis).toLocalDateTime(eventZone)
            EventDraft(
                id = id.toString(), calendarId = row[Events.CALENDAR_ID].toString(), title = row.s(Events.TITLE),
                allDay = false, startDate = s.date, startTime = s.time, endDate = e.date, endTime = e.time,
                timeZoneId = eventZone.id,
            )
        }.copy(
            location = row.s(Events.EVENT_LOCATION),
            description = row.s(Events.DESCRIPTION),
            reminderMinutes = reminders(id),
        )
        val parsed = Recurrence.parse(rule, draft.startDate, eventZone)
        val writable = (row[Events.CALENDAR_ID] as? Long)?.let(::calendarWritable) ?: false
        return StoredEvent(
            draft = draft.copy(recurrence = parsed),
            rawRule = rule,
            recurrenceEditable = rule == null || parsed != null,
            calendarWritable = writable,
            isException = row[Events.ORIGINAL_ID] != null,
        )
    }

    override suspend fun save(draft: EventDraft): String = io {
        val wanted = eventColumns(draft)
        val current = draft.id?.let { readRow(it.toLong()) ?: error("event ${draft.id} no longer exists") }
        val movesCalendar = current != null && !sameValue(current[Events.CALENDAR_ID], wanted[Events.CALENDAR_ID])
        if (current == null || movesCalendar) {
            // A new event, or one moving to another calendar. Moving is done as
            // create-then-delete: rewriting CALENDAR_ID would carry the old
            // account's sync id into the new one.
            if (movesCalendar && draft.keepExistingRule) {
                wanted[Events.RRULE] = current!![Events.RRULE]
                wanted[Events.DTEND] = null
            }
            val values = ContentValues().apply { wanted.forEach { (k, v) -> putAny(k, v) } }
            val uri = resolver.insert(Events.CONTENT_URI, values) ?: error("insert returned null")
            val id = ContentUris.parseId(uri)
            writeReminders(id, draft.reminderMinutes)
            if (movesCalendar) resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, draft.id!!.toLong()), null, null)
            id.toString()
        } else {
            val id = draft.id!!.toLong()
            val changed = ContentValues()
            wanted.forEach { (k, v) -> if (!sameValue(current[k], v)) changed.putAny(k, v) }
            if (changed.size() > 0) {
                val n = resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), changed, null, null)
                if (n == 0) error("휴대폰 캘린더가 수정을 받지 않았습니다")
                // Read back: a provider can accept the call yet keep its own values (0.10.3).
                val after = readRow(id)
                val kept = listOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.EVENT_LOCATION)
                    .filter { k -> changed.containsKey(k) && !sameValue(after?.get(k), wanted[k]) }
                if (kept.isNotEmpty()) error("휴대폰 캘린더가 일부 항목을 바꾸지 않았습니다: " + kept.joinToString(", ") { it.lowercase() })
            }
            if (reminders(id).sorted() != draft.reminderMinutes.sorted()) writeReminders(id, draft.reminderMinutes)
            id.toString()
        }
    }

    /** The columns OLO manages, as they should be after saving [d]. */
    private fun eventColumns(d: EventDraft): MutableMap<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            Events.CALENDAR_ID to d.calendarId.toLong(),
            Events.TITLE to d.title,
            Events.EVENT_LOCATION to d.location,
            Events.DESCRIPTION to d.description,
            Events.ALL_DAY to if (d.allDay) 1L else 0L,
            Events.HAS_ALARM to if (d.reminderMinutes.isNotEmpty()) 1L else 0L,
        )
        val tz = runCatching { TimeZone.of(d.timeZoneId) }.getOrDefault(zone)
        val (start, end) = if (d.allDay) {
            AllDay.startMillis(d.startDate) to AllDay.endMillisExclusive(d.endDate)
        } else {
            d.startDate.at(d.startTime, tz).toEpochMilliseconds() to d.endDate.at(d.endTime, tz).toEpochMilliseconds()
        }
        m[Events.DTSTART] = start
        m[Events.EVENT_TIMEZONE] = if (d.allDay) "UTC" else tz.id
        when {
            d.keepExistingRule -> {
                // Rule untouched; only the length may change, and it lives in DURATION.
                m[Events.DURATION] = androidDuration(d.allDay, d.startDate, d.endDate, (end - start) / 1000)
            }
            d.recurrence != null -> {
                m[Events.RRULE] = d.recurrence.toRRule(d.allDay, tz)
                m[Events.DURATION] = androidDuration(d.allDay, d.startDate, d.endDate, (end - start) / 1000)
                m[Events.DTEND] = null
            }
            else -> {
                m[Events.RRULE] = null
                m[Events.DURATION] = null
                m[Events.DTEND] = end
            }
        }
        return m
    }

    override suspend fun delete(id: String) {
        io { resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id.toLong()), null, null) }
    }

    override suspend fun setVisible(calendarId: String, visible: Boolean) {
        io {
            val v = ContentValues().apply { put(Calendars.VISIBLE, if (visible) 1 else 0) }
            resolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId.toLong()), v, null, null)
        }
    }

    override suspend fun syncStatus(): SyncStatus = io {
        val pending = resolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.DIRTY} = 1 AND ${Events.ACCOUNT_TYPE} = ?", arrayOf("com.google"), null,
        )?.use { it.count } ?: 0
        // Only accounts OLO can see answer truthfully; the rest read as "off" whatever they are.
        val visible = runCatching { accounts.getAccountsByType("com.google").map { it.name }.toSet() }.getOrDefault(emptySet())
        val all = googleAccounts()
        val off = all.filter { it in visible }.filter { name ->
            runCatching { !ContentResolver.getSyncAutomatically(Account(name, "com.google"), CalendarContract.AUTHORITY) }.getOrDefault(false)
        }
        val master = runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrDefault(true)
        SyncStatus(pendingUploads = pending, accountsWithSyncOff = off, masterSyncOn = master, accountsNeedingAccess = all.filterNot { it in visible })
    }

    // WRITE_SYNC_SETTINGS is a normal permission: granted at install, no prompt.
    override suspend fun setAccountSync(account: String, on: Boolean): Boolean = io {
        runCatching {
            val a = Account(account, "com.google")
            if (on && ContentResolver.getIsSyncable(a, CalendarContract.AUTHORITY) == 0) {
                ContentResolver.setIsSyncable(a, CalendarContract.AUTHORITY, 1)
            }
            ContentResolver.setSyncAutomatically(a, CalendarContract.AUTHORITY, on)
            ContentResolver.getSyncAutomatically(a, CalendarContract.AUTHORITY) == on
        }.getOrDefault(false).also { ok -> if (ok && on) requestSync() }
    }

    override suspend fun setMasterSync(on: Boolean): Boolean = io {
        runCatching {
            ContentResolver.setMasterSyncAutomatically(on)
            ContentResolver.getMasterSyncAutomatically() == on
        }.getOrDefault(false).also { ok -> if (ok && on) requestSync() }
    }

    override fun requestSync(exclude: Set<String>) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        Thread {
            syncTargets(exclude).forEach { a ->
                runCatching { ContentResolver.requestSync(a, CalendarContract.AUTHORITY, extras) }
            }
        }.start()
    }

    override suspend fun syncActive(exclude: Set<String>): Boolean = io {
        syncTargets(exclude).any { a ->
            runCatching { ContentResolver.isSyncActive(a, CalendarContract.AUTHORITY) || ContentResolver.isSyncPending(a, CalendarContract.AUTHORITY) }
                .getOrDefault(false)
        }
    }

    /**
     * Who "지금 동기화" asks: synced accounts minus the person's exclusions and
     * minus any whose calendar sync is off on the phone. SYNC_EXTRAS_MANUAL
     * ignores that switch, so without this check a manual request would sync
     * an account the person turned off.
     */
    private fun syncTargets(exclude: Set<String>): List<Account> =
        syncedAccounts().filter { a ->
            "${a.type}/${a.name}" !in exclude &&
                runCatching { ContentResolver.getSyncAutomatically(a, CalendarContract.AUTHORITY) }.getOrDefault(true)
        }

    /**
     * Every account whose calendars a sync adapter carries: Google, Exchange,
     * the Samsung account, … — not phone-only calendars (LOCAL) or Samsung's
     * built-in holiday and contact-date accounts.
     */
    private fun syncedAccounts(): List<Account> =
        resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE), null, null, null)
            .list { it.str(Calendars.ACCOUNT_NAME) to it.str(Calendars.ACCOUNT_TYPE) }
            .distinct()
            .filter { (name, type) -> name.isNotBlank() && type.isNotBlank() && type != CalendarContract.ACCOUNT_TYPE_LOCAL && !type.startsWith("local") && !name.startsWith("local.") }
            .map { (name, type) -> Account(name, type) }

    override fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    override suspend fun eventCounts(): Map<String, Int> = io {
        resolver.query(Events.CONTENT_URI, arrayOf(Events.CALENDAR_ID), "${Events.DELETED} = 0", null, null)
            .list { it.long(Events.CALENDAR_ID).toString() }
            .groupingBy { it }.eachCount()
    }

    // --- moving a calendar's events into Google --------------------------------

    private val moveColumns = arrayOf(
        Events._ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.DTSTART, Events.DTEND,
        Events.DURATION, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.ALL_DAY, Events.RRULE,
        Events.RDATE, Events.EXRULE, Events.EXDATE, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME,
        Events.STATUS, Events.AVAILABILITY, Events.ACCESS_LEVEL, Events._SYNC_ID, Events.EVENT_COLOR,
    )

    private fun sourceRows(calendarId: String, deleted: Boolean = false): List<Map<String, Any?>> =
        resolver.query(
            Events.CONTENT_URI, moveColumns, "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = ${if (deleted) 1 else 0}",
            arrayOf(calendarId), "${Events.DTSTART} ASC",
        ).list { c ->
            moveColumns.associateWith { col ->
                val i = c.getColumnIndexOrThrow(col)
                when {
                    c.isNull(i) -> null
                    c.getType(i) == Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    else -> c.getString(i)
                }
            }
        }

    private fun Map<String, Any?>.id(): Long = this[Events._ID] as Long
    private fun Map<String, Any?>.isException(): Boolean = this[Events.ORIGINAL_ID] != null
    private fun Map<String, Any?>.isCancelled(): Boolean = (this[Events.STATUS] as Long?) == Events.STATUS_CANCELED.toLong()
    private fun Map<String, Any?>.repeats(): Boolean =
        !(this[Events.RRULE] as String?).isNullOrBlank() || !(this[Events.RDATE] as String?).isNullOrBlank()

    /** Event ids (among [ids]) that have at least one guest besides the organizer. */
    private fun eventsWithGuests(ids: List<Long>): Set<Long> = buildSet {
        ids.chunked(400).forEach { chunk ->
            resolver.query(
                CalendarContract.Attendees.CONTENT_URI,
                arrayOf(CalendarContract.Attendees.EVENT_ID, CalendarContract.Attendees.ATTENDEE_RELATIONSHIP),
                "${CalendarContract.Attendees.EVENT_ID} IN (${chunk.joinToString(",")})", null, null,
            ).list { c ->
                if (c.int(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP) != CalendarContract.Attendees.RELATIONSHIP_ORGANIZER) {
                    c.long(CalendarContract.Attendees.EVENT_ID)
                } else null
            }.filterNotNull().forEach { add(it) }
        }
    }

    override suspend fun planMove(fromCalendarId: String): MovePlan = io {
        val rows = sourceRows(fromCalendarId)
        val masters = rows.filterNot { it.isException() }
        val changed = rows.count { it.isException() && !it.isCancelled() }
        MovePlan(
            fromCalendarId = fromCalendarId,
            events = masters.size + changed,
            recurring = masters.count { it.repeats() },
            changedOccurrences = changed,
            withGuests = eventsWithGuests(rows.map { it.id() }).size,
        )
    }

    override suspend fun move(
        fromCalendarId: String,
        toCalendarId: String,
        deleteOriginals: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): MoveResult = io {
        require(fromCalendarId != toCalendarId) { "same calendar" }
        val rows = sourceRows(fromCalendarId)
        val exceptions = rows.filter { it.isException() }
        val masters = rows.filterNot { it.isException() }
        val byMaster = exceptions.groupBy { it[Events.ORIGINAL_ID].toString() }
        val standalone = exceptions.filterNot { it.isCancelled() }
        val total = masters.size + standalone.size
        var done = 0
        var failed = 0
        onProgress(0, total)

        for (m in masters) {
            val excluded = byMaster[m.id().toString()].orEmpty()
                .mapNotNull { it[Events.ORIGINAL_INSTANCE_TIME] as Long? }
                .map { Instant.fromEpochMilliseconds(it) }
            if (copyEvent(m, toCalendarId, keepRule = true, excluded = excluded)) done++ else failed++
            onProgress(done + failed, total)
        }
        // A changed occurrence becomes its own event on the new calendar; its slot in
        // the rule was excluded above. Cancelled occurrences are simply excluded.
        for (e in standalone) {
            if (copyEvent(e, toCalendarId, keepRule = false, excluded = emptyList())) done++ else failed++
            onProgress(done + failed, total)
        }

        var deleted = 0
        if (deleteOriginals && failed == 0) {
            (exceptions + masters).forEach { r ->
                deleted += runCatching {
                    resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, r.id()), null, null)
                }.getOrDefault(0).coerceAtMost(1)
            }
        }
        MoveResult(
            moved = done,
            deleted = deleted,
            failed = failed,
            message = if (failed > 0 && deleteOriginals) "옮기지 못한 일정이 있어 원본을 지우지 않았습니다." else null,
        )
    }

    /** Inserts a copy of [r] into [toCalendarId]; returns false if the provider refused it. */
    private fun copyEvent(r: Map<String, Any?>, toCalendarId: String, keepRule: Boolean, excluded: List<Instant>): Boolean =
        copyEventId(r, toCalendarId, keepRule, excluded) != null

    private fun copyEventId(r: Map<String, Any?>, toCalendarId: String, keepRule: Boolean, excluded: List<Instant>): Long? {
        val allDay = r[Events.ALL_DAY] == 1L
        val start = r[Events.DTSTART] as Long? ?: return null
        val explicitEnd = r[Events.DTEND] as Long?
        val durMs = durationMillis(r[Events.DURATION] as String?)
        val end = explicitEnd ?: if (durMs > 0) start + durMs else start + if (allDay) 86_400_000L else 3_600_000L
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, toCalendarId.toLong())
            put(Events.TITLE, r[Events.TITLE] as String?)
            put(Events.EVENT_LOCATION, r[Events.EVENT_LOCATION] as String?)
            put(Events.DESCRIPTION, r[Events.DESCRIPTION] as String?)
            put(Events.DTSTART, start)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else (r[Events.EVENT_TIMEZONE] as String?) ?: zone.id)
            (r[Events.EVENT_END_TIMEZONE] as String?)?.takeIf { !allDay }?.let { put(Events.EVENT_END_TIMEZONE, it) }
            (r[Events.AVAILABILITY] as Long?)?.let { put(Events.AVAILABILITY, it) }
            // The event's own colour (Samsung's pink birthday, say), so a copy looks like the original.
            (r[Events.EVENT_COLOR] as Long?)?.takeIf { it != 0L }?.let { put(Events.EVENT_COLOR, it.toInt()) }
            (r[Events.ACCESS_LEVEL] as Long?)?.let { put(Events.ACCESS_LEVEL, it) }
            if (keepRule && r.repeats()) {
                putAny(Events.RRULE, r[Events.RRULE])
                putAny(Events.RDATE, r[Events.RDATE])
                putAny(Events.EXRULE, r[Events.EXRULE])
                putAny(Events.EXDATE, mergeExdates(r[Events.EXDATE] as String?, excluded))
                put(Events.DURATION, (r[Events.DURATION] as String?)?.takeIf { durMs > 0 } ?: if (allDay) {
                    "P${maxOf(1L, (end - start) / 86_400_000L)}D"
                } else {
                    "P${maxOf(0L, (end - start) / 1000)}S"
                })
                putNull(Events.DTEND)
            } else {
                put(Events.DTEND, end)
            }
        }
        val newId = runCatching { resolver.insert(Events.CONTENT_URI, v)?.let(ContentUris::parseId) }.getOrNull() ?: return null
        copyReminders(r.id(), newId)
        return newId
    }

    // --- the Galaxy bridge: Samsung Calendar ⇄ Google main (docs/07) ------------

    override suspend fun bridgeItems(calendarId: String): List<BridgeItem> = io {
        val rows = sourceRows(calendarId)
        val exceptions = rows.filter { it.isException() }.groupBy { it[Events.ORIGINAL_ID].toString() }
        val alarms = remindersOf(rows.map { it.id() })
        rows.filterNot { it.isException() }.map { m ->
            BridgeItem(
                m.id().toString(), fieldsOf(m, exceptions[m.id().toString()].orEmpty(), alarms[m.id()].orEmpty()),
                synced = !(m[Events._SYNC_ID] as String?).isNullOrBlank(),
            )
        }
    }

    override suspend fun bridgeDeleted(calendarId: String): List<BridgeItem> = io {
        val rows = sourceRows(calendarId, deleted = true)
        val exceptions = rows.filter { it.isException() }.groupBy { it[Events.ORIGINAL_ID].toString() }
        rows.filterNot { it.isException() }.map { m ->
            BridgeItem(m.id().toString(), fieldsOf(m, exceptions[m.id().toString()].orEmpty(), emptyList()))
        }
    }

    override suspend fun bridgeRestore(deletedId: String, toCalendarId: String): String? = io {
        val master = resolver.query(
            Events.CONTENT_URI, moveColumns, "${Events._ID} = ?", arrayOf(deletedId), null,
        ).list { c -> rowOf(c, moveColumns) }.firstOrNull() ?: return@io null
        copyEventId(master, toCalendarId, keepRule = true, excluded = emptyList())?.toString()
    }

    private fun endOf(r: Map<String, Any?>): Long {
        val start = r[Events.DTSTART] as Long? ?: 0L
        val allDay = r[Events.ALL_DAY] == 1L
        val dur = durationMillis(r[Events.DURATION] as String?)
        return (r[Events.DTEND] as Long?) ?: if (dur > 0) start + dur else start + if (allDay) 86_400_000L else 3_600_000L
    }

    private fun fieldsOf(m: Map<String, Any?>, exceptions: List<Map<String, Any?>>, minutes: List<Int>) = BridgeFields(
        title = m.s(Events.TITLE),
        location = m.s(Events.EVENT_LOCATION),
        description = m.s(Events.DESCRIPTION),
        allDay = m[Events.ALL_DAY] == 1L,
        startMillis = m[Events.DTSTART] as Long? ?: 0L,
        endMillis = endOf(m),
        timeZone = m.s(Events.EVENT_TIMEZONE),
        rrule = m.s(Events.RRULE),
        exdates = parseExdates(m[Events.EXDATE] as String?),
        reminderMinutes = minutes,
        exceptions = exceptions.mapNotNull { e ->
            val orig = e[Events.ORIGINAL_INSTANCE_TIME] as Long? ?: return@mapNotNull null
            if (e.isCancelled()) BridgeException(orig, cancelled = true)
            else BridgeException(orig, false, e.s(Events.TITLE), e[Events.DTSTART] as Long? ?: 0L, endOf(e))
        },
    )

    private fun remindersOf(ids: List<Long>): Map<Long, List<Int>> = buildMap<Long, MutableList<Int>> {
        ids.chunked(400).forEach { chunk ->
            resolver.query(
                Reminders.CONTENT_URI, arrayOf(Reminders.EVENT_ID, Reminders.MINUTES),
                "${Reminders.EVENT_ID} IN (${chunk.joinToString(",")})", null, null,
            ).list { c -> c.long(Reminders.EVENT_ID) to c.int(Reminders.MINUTES) }
                .forEach { (id, min) -> getOrPut(id) { mutableListOf() }.add(min) }
        }
    }

    /** A series: its master row and the rows of its changed / cancelled occurrences. */
    private fun seriesRows(id: Long): Pair<Map<String, Any?>, List<Map<String, Any?>>>? {
        val master = resolver.query(
            Events.CONTENT_URI, moveColumns + Events.CALENDAR_ID, "${Events._ID} = ? AND ${Events.DELETED} = 0", arrayOf(id.toString()), null,
        ).list { c -> rowOf(c, moveColumns + Events.CALENDAR_ID) }.firstOrNull() ?: return null
        val exceptions = resolver.query(
            Events.CONTENT_URI, moveColumns, "${Events.ORIGINAL_ID} = ? AND ${Events.DELETED} = 0", arrayOf(id.toString()), null,
        ).list { c -> rowOf(c, moveColumns) }
        return master to exceptions
    }

    private fun rowOf(c: Cursor, cols: Array<String>): Map<String, Any?> = cols.associateWith { col ->
        val i = c.getColumnIndexOrThrow(col)
        when {
            c.isNull(i) -> null
            c.getType(i) == Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
            else -> c.getString(i)
        }
    }

    override suspend fun bridgeCreate(fields: BridgeFields, toCalendarId: String): String? = io {
        val f = fields
        val repeats = f.rrule.isNotBlank()
        // Cancelled occurrences come back as exclusions; changed ones keep the series' own times.
        val excluded = (f.exdates + f.exceptions.filter { it.cancelled }.map { it.originalStart }).distinct().map { Instant.fromEpochMilliseconds(it) }
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, toCalendarId.toLong())
            put(Events.TITLE, f.title)
            put(Events.EVENT_LOCATION, f.location)
            put(Events.DESCRIPTION, f.description)
            put(Events.DTSTART, f.startMillis)
            put(Events.ALL_DAY, if (f.allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (f.allDay) "UTC" else f.timeZone.ifBlank { zone.id })
            if (repeats) {
                put(Events.RRULE, f.rrule.removePrefix("RRULE:"))
                mergeExdates(null, excluded)?.let { put(Events.EXDATE, it) }
                put(Events.DURATION, if (f.allDay) "P${maxOf(1L, (f.endMillis - f.startMillis) / 86_400_000L)}D" else "P${maxOf(0L, (f.endMillis - f.startMillis) / 1000)}S")
            } else {
                put(Events.DTEND, f.endMillis)
            }
        }
        val id = runCatching { resolver.insert(Events.CONTENT_URI, v)?.let(ContentUris::parseId) }.getOrNull() ?: return@io null
        f.reminderMinutes.distinct().forEach { m ->
            runCatching {
                resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                    put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, m); put(Reminders.METHOD, Reminders.METHOD_ALERT)
                })
            }
        }
        id.toString()
    }

    override suspend fun bridgeCopy(sourceId: String, toCalendarId: String): String? = io { copySeries(sourceId.toLong(), toCalendarId)?.toString() }

    private fun copySeries(sourceId: Long, toCalendarId: String): Long? {
        val (master, exceptions) = seriesRows(sourceId) ?: return null
        val newId = copyEventId(master, toCalendarId, keepRule = true, excluded = emptyList()) ?: return null
        // Changed and cancelled occurrences go in as real exceptions of the copy, so the
        // other side sees one series (and never copies them back as loose events).
        exceptions.forEach { e ->
            val orig = e[Events.ORIGINAL_INSTANCE_TIME] as Long? ?: return@forEach
            val v = ContentValues().apply {
                put(Events.ORIGINAL_INSTANCE_TIME, orig)
                if (e.isCancelled()) {
                    put(Events.STATUS, Events.STATUS_CANCELED)
                } else {
                    put(Events.TITLE, e[Events.TITLE] as String?)
                    put(Events.EVENT_LOCATION, e[Events.EVENT_LOCATION] as String?)
                    put(Events.DESCRIPTION, e[Events.DESCRIPTION] as String?)
                    put(Events.DTSTART, e[Events.DTSTART] as Long?)
                    put(Events.DTEND, endOf(e))
                    put(Events.ALL_DAY, if (e[Events.ALL_DAY] == 1L) 1 else 0)
                }
            }
            runCatching { resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, newId), v) }
        }
        return newId
    }

    override suspend fun bridgeReplace(sourceId: String, targetId: String): String? = io {
        val (src, srcEx) = seriesRows(sourceId.toLong()) ?: return@io null
        val (tgt, tgtEx) = seriesRows(targetId.toLong()) ?: return@io null
        val plain = !src.repeats() && srcEx.isEmpty() && !tgt.repeats() && tgtEx.isEmpty()
        if (plain) {
            // A plain event: rewrite only what is shown, so the target keeps its own id
            // (and Google keeps the iPhone's copy as the same event).
            val allDay = src[Events.ALL_DAY] == 1L
            val v = ContentValues().apply {
                put(Events.TITLE, src[Events.TITLE] as String?)
                put(Events.EVENT_LOCATION, src[Events.EVENT_LOCATION] as String?)
                put(Events.DESCRIPTION, src[Events.DESCRIPTION] as String?)
                put(Events.DTSTART, src[Events.DTSTART] as Long?)
                put(Events.DTEND, endOf(src))
                // A plain event's length is DTEND alone: a leftover DURATION (Samsung writes
                // one) would make a provider keep the old length (0.10.3).
                putNull(Events.DURATION)
                put(Events.ALL_DAY, if (allDay) 1 else 0)
                put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else (src[Events.EVENT_TIMEZONE] as String?) ?: zone.id)
            }
            // A refused update is a failed write (the pass retries it), not a silent success.
            if (runCatching { resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, targetId.toLong()), v, null, null) }.getOrDefault(0) == 0) return@io null
            resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(targetId))
            copyReminders(sourceId.toLong(), targetId.toLong())
            targetId
        } else {
            val cal = tgt[Events.CALENDAR_ID].toString()
            val newId = copySeries(sourceId.toLong(), cal) ?: return@io null
            resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, targetId.toLong()), null, null)
            newId.toString()
        }
    }

    override suspend fun bridgeDelete(id: String): Boolean = io {
        runCatching { resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id.toLong()), null, null) > 0 }.getOrDefault(false)
    }

    private fun copyReminders(fromEvent: Long, toEvent: Long) {
        val rs = resolver.query(
            Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES, Reminders.METHOD),
            "${Reminders.EVENT_ID} = ?", arrayOf(fromEvent.toString()), null,
        ).list { c -> c.int(Reminders.MINUTES) to c.int(Reminders.METHOD) }
        rs.distinct().take(5).forEach { (min, method) ->
            runCatching {
                resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                    put(Reminders.EVENT_ID, toEvent)
                    put(Reminders.MINUTES, min)
                    // Google keeps popup and email; anything else becomes a popup.
                    put(Reminders.METHOD, if (method == Reminders.METHOD_EMAIL) Reminders.METHOD_EMAIL else Reminders.METHOD_ALERT)
                })
            }
        }
        if (rs.isNotEmpty()) {
            resolver.update(
                ContentUris.withAppendedId(Events.CONTENT_URI, toEvent),
                ContentValues().apply { put(Events.HAS_ALARM, 1) }, null, null,
            )
        }
    }

    // --- helpers -------------------------------------------------------------

    private val rowColumns = arrayOf(
        Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.ALL_DAY,
        Events.DTSTART, Events.DTEND, Events.DURATION, Events.EVENT_TIMEZONE, Events.RRULE, Events.HAS_ALARM,
        Events.ORIGINAL_ID,
    )

    private fun readRow(id: Long): Map<String, Any?>? =
        resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), rowColumns, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            rowColumns.associateWith { col ->
                val i = c.getColumnIndexOrThrow(col)
                when {
                    c.isNull(i) -> null
                    c.getType(i) == Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    else -> c.getString(i)
                }
            }
        }

    private fun calendarWritable(calendarId: Long): Boolean =
        resolver.query(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId),
            arrayOf(Calendars.CALENDAR_ACCESS_LEVEL), null, null, null,
        )?.use { c -> c.moveToFirst() && c.getInt(0) >= Calendars.CAL_ACCESS_CONTRIBUTOR } ?: false

    private fun reminders(eventId: Long): List<Int> =
        resolver.query(
            Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES, Reminders.METHOD),
            "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), null,
        ).list { c -> if (c.int(Reminders.METHOD) == Reminders.METHOD_ALERT || c.int(Reminders.METHOD) == Reminders.METHOD_DEFAULT) c.int(Reminders.MINUTES) else null }
            .filterNotNull()

    /** Replaces popup reminders only; email/SMS reminders set elsewhere are left alone. */
    private fun writeReminders(eventId: Long, minutes: List<Int>) {
        resolver.delete(
            Reminders.CONTENT_URI,
            "${Reminders.EVENT_ID} = ? AND ${Reminders.METHOD} IN (?, ?)",
            arrayOf(eventId.toString(), Reminders.METHOD_ALERT.toString(), Reminders.METHOD_DEFAULT.toString()),
        )
        minutes.distinct().take(5).forEach { m ->
            resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, m)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
    }

    private fun googleAccounts(): List<String> =
        resolver.query(
            Calendars.CONTENT_URI, arrayOf(Calendars.ACCOUNT_NAME),
            "${Calendars.ACCOUNT_TYPE} = ?", arrayOf("com.google"), null,
        ).list { it.str(Calendars.ACCOUNT_NAME) }.distinct()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}

// --- cursor & value utilities ----------------------------------------------------

private inline fun <T> Cursor?.list(map: (Cursor) -> T): List<T> {
    if (this == null) return emptyList()
    return use { c -> buildList { while (c.moveToNext()) add(map(c)) } }
}

private fun Cursor.str(col: String): String = getColumnIndexOrThrow(col).let { if (isNull(it)) "" else getString(it) }
private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))
private fun Cursor.int(col: String): Int = getColumnIndexOrThrow(col).let { if (isNull(it)) 0 else getInt(it) }
private fun Map<String, Any?>.s(col: String): String = (this[col] as String?).orEmpty()

private fun ContentValues.putAny(key: String, v: Any?) {
    when (v) {
        null -> putNull(key)
        is Long -> put(key, v)
        is Int -> put(key, v)
        is String -> put(key, v)
        is Boolean -> put(key, if (v) 1 else 0)
        else -> put(key, v.toString())
    }
}

/** Compares a stored provider value with a wanted one, treating "" and null alike for text. */
internal fun sameValue(stored: Any?, wanted: Any?): Boolean {
    if (stored == null && wanted == null) return true
    if (stored is String? && wanted is String?) return stored.orEmpty() == wanted.orEmpty()
    if (stored is Number && wanted is Number) return stored.toLong() == wanted.toLong()
    return stored == wanted
}

/** Parses RFC 2445 durations as Android stores them ("P3600S", "P1D", "PT1H", "P1W"). */
internal fun durationMillis(d: String?): Long {
    if (d.isNullOrBlank()) return 0
    val m = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?(?:(\d+)S)?$""").matchEntire(d.trim()) ?: return 0
    fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
    val secs = g(2) * 7 * 86400 + g(3) * 86400 + g(4) * 3600 + g(5) * 60 + g(6) + g(7)
    return (if (m.groupValues[1] == "-") -secs else secs) * 1000
}
