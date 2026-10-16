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
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.Recurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncKind
import app.olo.calendar.model.SyncStatus
import app.olo.calendar.model.androidDuration
import app.olo.calendar.model.at
import app.olo.calendar.model.classifyAndroidAccount
import app.olo.calendar.model.isGoogleHolidayCalendarId
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

    override suspend fun calendars(): List<CalendarInfo> = io {
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE, Calendars.IS_PRIMARY,
            Calendars.OWNER_ACCOUNT,
        )
        resolver.query(Calendars.CONTENT_URI, projection, null, null, null).list { c ->
            val owner = c.str(Calendars.OWNER_ACCOUNT)
            val accountType = c.str(Calendars.ACCOUNT_TYPE)
            CalendarInfo(
                id = c.long(Calendars._ID).toString(),
                name = c.str(Calendars.CALENDAR_DISPLAY_NAME).ifBlank { c.str(Calendars.ACCOUNT_NAME) },
                accountName = c.str(Calendars.ACCOUNT_NAME),
                accountType = accountType,
                kind = classifyAndroidAccount(accountType),
                colorArgb = c.int(Calendars.CALENDAR_COLOR),
                writable = c.int(Calendars.CALENDAR_ACCESS_LEVEL) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                visible = c.int(Calendars.VISIBLE) == 1,
                isPrimary = c.int(Calendars.IS_PRIMARY) == 1,
                isHoliday = isGoogleHolidayCalendarId(owner),
            )
        }.sortedWith(compareBy<CalendarInfo>({ it.kind != SyncKind.GOOGLE }, { it.accountName }, { !it.isPrimary }, { it.name }))
    }

    override suspend fun occurrences(from: Instant, to: Instant): List<Occurrence> = io {
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
        resolver.query(uri, projection, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC").list { c ->
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
                resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), changed, null, null)
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
        val off = googleAccounts().filter { name ->
            runCatching { !ContentResolver.getSyncAutomatically(Account(name, "com.google"), CalendarContract.AUTHORITY) }.getOrDefault(false)
        }
        val master = runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrDefault(true)
        SyncStatus(pendingUploads = pending, accountsWithSyncOff = off, masterSyncOn = master)
    }

    override fun requestSync() {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        Thread {
            googleAccounts().forEach { name ->
                runCatching { ContentResolver.requestSync(Account(name, "com.google"), CalendarContract.AUTHORITY, extras) }
            }
        }.start()
    }

    override fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
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
