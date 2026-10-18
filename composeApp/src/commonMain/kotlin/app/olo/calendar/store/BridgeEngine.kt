package app.olo.calendar.store

import app.olo.calendar.model.BridgeFields
import app.olo.calendar.model.LogDir
import app.olo.calendar.model.LogEntry
import app.olo.calendar.model.LogItem
import app.olo.calendar.model.decodeLog
import app.olo.calendar.model.describeChange
import app.olo.calendar.model.encodeLog
import app.olo.calendar.model.BridgeItem
import app.olo.calendar.model.decodeFields
import app.olo.calendar.model.encodeFields
import app.olo.calendar.model.BridgeLink
import app.olo.calendar.model.BridgeOp
import app.olo.calendar.model.BridgePlan
import app.olo.calendar.model.Preferences
import app.olo.calendar.model.decodeLinks
import app.olo.calendar.model.encodeLinks
import app.olo.calendar.model.planBridge
import app.olo.calendar.model.planDedupe
import app.olo.calendar.model.dedupeTitles
import app.olo.calendar.model.decodeMap
import app.olo.calendar.model.encodeMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/** Which two calendars the bridge joins. */
data class BridgeConfig(val samsungCalendarId: String, val googleCalendarId: String)

/** The outcome of one pass, kept for the drawer ("마지막 맞춤 2분 전"). */
data class BridgeReport(
    val atMillis: Long,
    val toGoogle: Int,
    val toSamsung: Int,
    val deleted: Int,
    val conflicts: Int,
    val heldDeletes: Int,
    val failed: Int,
    val linked: Int,
) {
    fun encode() = listOf(atMillis, toGoogle, toSamsung, deleted, conflicts, heldDeletes, failed, linked).joinToString(",")

    companion object {
        fun decode(s: String?): BridgeReport? = s?.split(',')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 8 }?.let {
            BridgeReport(it[0], it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt(), it[5].toInt(), it[6].toInt(), it[7].toInt())
        }
    }
}

/**
 * An event a pass deleted by itself: when, on which side ([googleSide]: the
 * Google main copy, because the Samsung one vanished; else the reverse), and
 * what it was, so the sheet can say exactly what happened.
 */
data class AutoDeleted(val atMillis: Long, val googleSide: Boolean, val fields: BridgeFields)

/**
 * Runs the bridge: reads both calendars, plans (model/Bridge.kt), writes, and
 * records each pair with the fingerprints both sides now have. One pass at a
 * time per process; the platform (WorkManager on Android) decides when passes
 * run while the app is closed.
 */
class BridgeEngine(private val store: CalendarStore, private val prefs: Preferences) {

    var links: List<BridgeLink>
        get() = decodeLinks(prefs.get(KEY_LINKS))
        private set(v) = prefs.put(KEY_LINKS, encodeLinks(v))

    val lastReport: BridgeReport? get() = BridgeReport.decode(prefs.get(KEY_REPORT))

    /** Google-side ids of linked pairs: OLO shows those events once, from Samsung. */
    fun linkedGoogleIds(): Set<String> = links.map { it.googleId }.toSet()

    private var refused: Map<String, String>
        get() = decodeMap(prefs.get(KEY_REFUSED))
        set(v) = prefs.put(KEY_REFUSED, encodeMap(v))

    suspend fun preview(config: BridgeConfig): BridgePlan =
        planBridge(store.bridgeItems(config.samsungCalendarId), store.bridgeItems(config.googleCalendarId), links, refused = refused)

    /** What vanished on one side and waits for a yes, as (title, the side it is still on). */
    suspend fun pendingDeletes(config: BridgeConfig): List<Pair<String, String>> {
        val s = store.bridgeItems(config.samsungCalendarId).associateBy { it.id }
        val g = store.bridgeItems(config.googleCalendarId).associateBy { it.id }
        return preview(config).pendingDeletes.mapNotNull { op ->
            when (op) {
                is BridgeOp.DeleteSamsung -> s[op.samsungId]?.let { it.fields.title to "samsung" }
                is BridgeOp.DeleteGoogle -> g[op.googleId]?.let { it.fields.title to "google" }
                else -> null
            }
        }
    }

    /**
     * Keeps the events that vanished on one side: forgets those pairs, so the
     * next pass copies the survivors back instead of deleting them.
     */
    suspend fun keepPending(config: BridgeConfig): BridgeReport = lock.withLock {
        val pending = preview(config).pendingDeletes
        val keepS = pending.filterIsInstance<BridgeOp.DeleteSamsung>().map { it.samsungId }.toSet()
        val keepG = pending.filterIsInstance<BridgeOp.DeleteGoogle>().map { it.googleId }.toSet()
        links = links.filterNot { it.samsungId in keepS || it.googleId in keepG }
        runLocked(config, allowMassDelete = false, { _, _ -> }, "되살리기(한쪽만 사라짐)")
    }

    /**
     * Deleted series still held by the phone (not yet purged by the sync
     * adapter) that a bridge pass deleted on its own (docs/07 §16): what a wrong
     * deletion took and can still be brought back.
     *
     * A deletion the person made — in Samsung Calendar, Google Calendar, or by
     * confirming "양쪽에서 지우기" for something they deleted — is theirs, not a
     * loss: neither the row nor the twin OLO deleted after it is offered back.
     * Twins removed by "중복 정리" are not offered either.
     */
    suspend fun recoverable(config: BridgeConfig): List<BridgeItem> {
        // Anything that already looks like a live event (same title, start, end, rule) is
        // not brought back: it was restored another way (the Samsung trash, say).
        val live = (store.bridgeItems(config.samsungCalendarId) + store.bridgeItems(config.googleCalendarId)).map { it.twinKey }.toSet()
        val deleted = store.bridgeDeleted(config.samsungCalendarId) + store.bridgeDeleted(config.googleCalendarId)
        val ours = oloDeleted
        // Events the person deleted themselves: any deleted copy OLO did not delete.
        val byPerson = deleted.filter { it.id !in ours }.map { it.twinKey }.toSet()
        return deleted
            .filter { it.id in ours && it.twinKey !in byPerson && it.twinKey !in live }
            .distinctBy { it.twinKey }
    }

    /** Ids a bridge pass deleted (newest last, capped), so [recoverable] can tell OLO's deletions from the person's. */
    private var oloDeleted: Set<String>
        get() = prefs.get(KEY_OLO_DELETED).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        set(v) = prefs.put(KEY_OLO_DELETED, v.toList().takeLast(3000).joinToString(","))

    /** Brings them back into Samsung Calendar (the home calendar); a pass then copies them to Google. */
    suspend fun restore(config: BridgeConfig, connected: Boolean): Int = lock.withLock {
        val items = recoverable(config)
        val back = items.filter { store.bridgeRestore(it.id, config.samsungCalendarId) != null }
        val n = back.size
        if (back.isNotEmpty()) record(LogEntry(Clock.System.now().toEpochMilliseconds(), "되살리기", back.map { LogItem(LogDir.TO_SAMSUNG, "되살림", it.fields, "지워진 기록에서 Samsung으로") }))
        if (connected) runLocked(config, allowMassDelete = false, { _, _ -> }, "되살린 뒤 맞춤")
        n
    }

    /**
     * Events a pass deleted by itself (docs/07 §18: a person deleted them on the
     * other side, a few at a time) in the last [UNDO_HOURS] hours, newest first,
     * with when. Kept as fields, so undo works after the phone purges the row.
     */
    val recentAutoDeleted: List<Pair<Long, BridgeFields>> get() = recentAutoDeletedDetail.map { it.atMillis to it.fields }

    /** The same, with which side lost its copy (docs/07 §18: for the sheet's detail list). */
    val recentAutoDeletedDetail: List<AutoDeleted>
        get() {
            val since = Clock.System.now().toEpochMilliseconds() - UNDO_HOURS * 3_600_000L
            return prefs.get(KEY_AUTO_DELETED).orEmpty().split(RS).mapNotNull { line ->
                val at = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
                if (at < since) return@mapNotNull null
                var rest = line.substringAfter('|')
                // 0.10.7 records the side ("G|" / "S|"); earlier lines have none.
                val side = rest.substringBefore('|').takeIf { it == "G" || it == "S" }
                if (side != null) rest = rest.substringAfter('|')
                decodeFields(rest)?.let { AutoDeleted(at, googleSide = side != "S", fields = it) }
            }
        }

    private fun keepForUndo(f: BridgeFields, googleSide: Boolean) {
        val line = "${Clock.System.now().toEpochMilliseconds()}|${if (googleSide) "G" else "S"}|${encodeFields(f)}"
        val kept = listOf(line) + prefs.get(KEY_AUTO_DELETED).orEmpty().split(RS).filter { it.isNotBlank() }
        prefs.put(KEY_AUTO_DELETED, kept.take(50).joinToString(RS))
    }

    /**
     * Undoes the recent automatic deletions: each comes back into Samsung Calendar
     * (unless an event like it is already there) and a pass copies it to Google.
     */
    suspend fun undoAutoDeleted(config: BridgeConfig): Int = lock.withLock {
        val items = recentAutoDeleted
        val live = (store.bridgeItems(config.samsungCalendarId) + store.bridgeItems(config.googleCalendarId))
            .map { it.twinKey }.toMutableSet()
        val back = mutableListOf<BridgeFields>()
        val failedLines = mutableListOf<BridgeFields>()
        items.map { it.second }.forEach { f ->
            val key = BridgeItem("", f).twinKey
            when {
                key in live -> Unit // already back another way
                store.bridgeCreate(f, config.samsungCalendarId) != null -> { back += f; live += key }
                else -> failedLines += f
            }
        }
        val n = back.size
        // Only what came back (or was already there) leaves the record; a failed one stays for another try.
        val keep = recentAutoDeletedDetail.filter { a -> failedLines.any { it == a.fields } }
        prefs.put(KEY_AUTO_DELETED, null)
        keep.reversed().forEach { keepForUndo(it.fields, it.googleSide) }
        if (back.isNotEmpty()) record(LogEntry(Clock.System.now().toEpochMilliseconds(), "되돌리기", back.map { LogItem(LogDir.TO_SAMSUNG, "되살림", it, "함께 지운 일정을 Samsung으로") }))
        runLocked(config, allowMassDelete = false, { _, _ -> }, "되돌린 뒤 맞춤")
        n
    }

    /** Lets the recent automatic deletions stand: the undo line goes away. */
    fun keepAutoDeleted() = prefs.put(KEY_AUTO_DELETED, null)

    /**
     * Lines from before 0.10.8 (counts only), newest first. Nothing writes them any
     * more, so they age out by time: after [LEGACY_DAYS] days they are no longer shown.
     */
    val log: List<String>
        get() {
            val since = Clock.System.now().toEpochMilliseconds() - LEGACY_DAYS * 86_400_000L
            return prefs.get(KEY_LOG).orEmpty().split('\n').filter { it.isNotBlank() && (it.substringBefore('|').toLongOrNull() ?: 0L) >= since }
        }

    /**
     * The record (docs/07 §19), newest first: only passes that carried something,
     * held something back or failed; each with its events.
     */
    val entries: List<LogEntry> get() = decodeLog(prefs.get(KEY_ENTRIES))

    /** When a pass last looked and found nothing to do. */
    val lastQuietCheck: Long? get() = prefs.get(KEY_LAST_CHECK)?.toLongOrNull()

    private fun record(e: LogEntry) {
        prefs.put(KEY_ENTRIES, encodeLog((listOf(e) + entries).take(60)))
    }

    suspend fun run(
        config: BridgeConfig,
        allowMassDelete: Boolean = false,
        /** A person asked ("지금 맞추기"): offer again what Google refused before. */
        retryRefused: Boolean = false,
        /** For the record: what started this pass ("앱", "백그라운드", "지금 맞추기", …). */
        trigger: String = "앱",
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BridgeReport = lock.withLock {
        if (retryRefused) refused = emptyMap()
        runLocked(config, allowMassDelete, onProgress, trigger)
    }

    /**
     * OLO made a Samsung event again under a new id (an edit the provider would not
     * take, 0.10.5): it is the same event, so its pair follows it. The next pass then
     * sees an edit and updates the Google copy in place — no delete and re-create, and
     * the iPhone keeps the same event (0.10.6).
     */
    suspend fun relink(oldSamsungId: String, newSamsungId: String) = lock.withLock { relinkLocked(oldSamsungId, newSamsungId) }

    private fun relinkLocked(oldSamsungId: String, newSamsungId: String) {
        links = links.map { if (it.samsungId == oldSamsungId) it.copy(samsungId = newSamsungId, samsungConfirmed = false) else it }
    }

    /**
     * Saves an edit to a Samsung event with no pass in between: if the store made the
     * event again under a new id, the pair moves with it before any pass can read the
     * old id as deleted (review 0.10.9).
     */
    suspend fun saveBridged(save: suspend () -> String, oldSamsungId: String): String = lock.withLock {
        val newId = save()
        if (newId != oldSamsungId) relinkLocked(oldSamsungId, newId)
        newId
    }

    private suspend fun runLocked(config: BridgeConfig, allowMassDelete: Boolean, onProgress: (Int, Int) -> Unit, trigger: String = "앱"): BridgeReport {
        val samsung = store.bridgeItems(config.samsungCalendarId)
        val google = store.bridgeItems(config.googleCalendarId)
        val plan = planBridge(samsung, google, links, allowMassDelete, refused = refused)
        // A copy Google dropped before it ever synced: remember the Samsung event so it
        // is offered once more at most, not resent on every pass.
        val gIds = google.map { it.id }.toSet()
        val sById = samsung.associateBy { it.id }
        val gById = google.associateBy { it.id }
        plan.orphans.filter { it.googleId !in gIds }.mapNotNull { o -> sById[o.samsungId]?.let { o.samsungId to it.fingerprint } }
            .takeIf { it.isNotEmpty() }?.let { refused = refused + it }
        val pairs = links.associateBy { it.samsungId }.toMutableMap()
        pairs.values.removeAll(plan.dropped.toSet())
        var failed = 0
        var deleted = 0
        val total = plan.writes
        var done = 0
        onProgress(0, total)

        // A fresh pair takes the source's print for both sides until it is read back, and
        // pairs are saved as the pass goes: a pass cut short (process killed) leaves them
        // recorded instead of copying everything again next time.
        val sPrint = samsung.associate { it.id to it.fingerprint }
        val gPrint = google.associate { it.id to it.fingerprint }
        val touched = HashSet<String>()
        // For the record: each event carried, which way, and what changed.
        val items = mutableListOf<LogItem>()
        val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
        var sinceSave = 0
        fun pair(sid: String, gid: String) {
            val p = sPrint[sid] ?: gPrint[gid] ?: ""
            pairs[sid] = BridgeLink(sid, gid, p, p)
            touched += sid
            if (++sinceSave >= 25) { links = pairs.values.toList(); sinceSave = 0 }
        }

        for (op in plan.ops) {
            val ok = runCatching {
                when (op) {
                    is BridgeOp.Agree -> { pair(op.samsungId, op.googleId); true }
                    is BridgeOp.CreateInGoogle -> (store.bridgeCopy(op.samsungId, config.googleCalendarId)?.also { pair(op.samsungId, it) } != null)
                        .also { if (it) sById[op.samsungId]?.let { s -> items += LogItem(LogDir.TO_GOOGLE, "새 일정", s.fields) } }
                    is BridgeOp.CreateInSamsung -> (store.bridgeCopy(op.googleId, config.samsungCalendarId)?.also { pair(it, op.googleId) } != null)
                        .also { if (it) gById[op.googleId]?.let { g -> items += LogItem(LogDir.TO_SAMSUNG, "새 일정", g.fields) } }
                    is BridgeOp.UpdateGoogle -> (store.bridgeReplace(op.samsungId, op.googleId)?.also { pair(op.samsungId, it) } != null)
                        .also { if (it) sById[op.samsungId]?.let { s -> items += LogItem(LogDir.TO_GOOGLE, "수정", s.fields, describeChange(gById[op.googleId]?.fields, s.fields, zone)) } }
                    is BridgeOp.UpdateSamsung -> (store.bridgeReplace(op.googleId, op.samsungId)?.also { newS ->
                        if (newS != op.samsungId) pairs.remove(op.samsungId)
                        pair(newS, op.googleId)
                    } != null).also { if (it) gById[op.googleId]?.let { g -> items += LogItem(LogDir.TO_SAMSUNG, "수정", g.fields, describeChange(sById[op.samsungId]?.fields, g.fields, zone)) } }
                    is BridgeOp.DeleteGoogle -> {
                        store.bridgeDelete(op.googleId).also { if (it) {
                            // Kept once it is really gone, so a deletion carried over by itself can be undone.
                            if (!allowMassDelete) gById[op.googleId]?.let { g -> keepForUndo(g.fields, googleSide = true) }
                            pairs.remove(op.samsungId); deleted++; oloDeleted = oloDeleted + op.googleId
                            gById[op.googleId]?.let { g -> items += LogItem(LogDir.TO_GOOGLE, "삭제", g.fields, "Samsung에서 사라짐") }
                        } }
                    }
                    is BridgeOp.DeleteSamsung -> {
                        store.bridgeDelete(op.samsungId).also { if (it) {
                            if (!allowMassDelete) sById[op.samsungId]?.let { x -> keepForUndo(x.fields, googleSide = false) }
                            pairs.remove(op.samsungId); deleted++; oloDeleted = oloDeleted + op.samsungId
                            sById[op.samsungId]?.let { s -> items += LogItem(LogDir.TO_SAMSUNG, "삭제", s.fields, "구글에서 사라짐") }
                        } }
                    }
                }
            }.getOrDefault(false)
            if (!ok) {
                failed++
                // Which event, which way: the record shows what could not be written.
                val (dir, f) = when (op) {
                    is BridgeOp.CreateInGoogle -> LogDir.TO_GOOGLE to sById[op.samsungId]?.fields
                    is BridgeOp.UpdateGoogle -> LogDir.TO_GOOGLE to sById[op.samsungId]?.fields
                    is BridgeOp.DeleteGoogle -> LogDir.TO_GOOGLE to gById[op.googleId]?.fields
                    is BridgeOp.CreateInSamsung -> LogDir.TO_SAMSUNG to gById[op.googleId]?.fields
                    is BridgeOp.UpdateSamsung -> LogDir.TO_SAMSUNG to gById[op.googleId]?.fields
                    is BridgeOp.DeleteSamsung -> LogDir.TO_SAMSUNG to sById[op.samsungId]?.fields
                    is BridgeOp.Agree -> LogDir.NONE to null
                }
                if (f != null) items += LogItem(dir, "실패", f, "쓰기를 휴대폰이 받지 않음 · 다음 맞춤에서 다시 시도")
            }
            if (op !is BridgeOp.Agree) onProgress(++done, total)
        }

        // Read both sides back so each pair holds the prints the providers really keep.
        val sNow = store.bridgeItems(config.samsungCalendarId).associateBy(BridgeItem::id)
        val gNow = store.bridgeItems(config.googleCalendarId).associateBy(BridgeItem::id)
        // Only pairs settled this pass take the new prints: a failed write keeps the old
        // ones, so the change is still seen as pending next time.
        links = pairs.values.mapNotNull { l ->
            val si = sNow[l.samsungId]
            val gi = gNow[l.googleId]
            when {
                si == null && gi == null -> null
                l.samsungId in touched && si != null && gi != null ->
                    BridgeLink(l.samsungId, l.googleId, si.fingerprint, gi.fingerprint, l.samsungConfirmed || si.synced, l.googleConfirmed || gi.synced)
                // Untouched pairs only learn that a side has reached its server.
                si != null && gi != null -> l.copy(samsungConfirmed = l.samsungConfirmed || si.synced, googleConfirmed = l.googleConfirmed || gi.synced)
                else -> l
            }
        }
        val report = BridgeReport(
            atMillis = Clock.System.now().toEpochMilliseconds(),
            // What really went across, not what was planned (a failed write is not "구글로 1").
            toGoogle = items.count { it.dir == LogDir.TO_GOOGLE && it.op != "실패" && it.op != "삭제" },
            toSamsung = items.count { it.dir == LogDir.TO_SAMSUNG && it.op != "실패" && it.op != "삭제" },
            deleted = deleted,
            conflicts = plan.conflicts, heldDeletes = plan.heldDeletes, failed = failed, linked = links.size,
        )
        prefs.put(KEY_REPORT, report.encode())
        val note = listOfNotNull(
            plan.pendingDeletes.size.takeIf { it > 0 }?.let { "삭제 대기 ${it}개 (확인 필요)" },
            plan.conflicts.takeIf { it > 0 }?.let { "양쪽 수정 ${it}건 · Samsung 기준" },
            plan.orphans.size.takeIf { it > 0 }?.let { "동기화 전 사라짐 ${it}개" },
            plan.unsupported.takeIf { it > 0 }?.let { "구글이 받지 않는 일정 ${it}개" },
            (failed - items.count { it.op == "실패" }).takeIf { it > 0 }?.let { "쓰기 실패 ${it}건" },
        ).joinToString(" · ")
        // Held deletes and events Google cannot hold stay the same pass after pass: the same
        // note again with nothing carried is a quiet pass, not a new entry.
        // Refusals only matter while the Samsung event is still the same one Google refused.
        val sNowPrints = sNow.mapValues { it.value.fingerprint }
        refused.filter { (id, print) -> sNowPrints[id] == print }.takeIf { it.size != refused.size }?.let { refused = it }
        val quiet = items.isEmpty() && failed == 0 && (note.isEmpty() || note == entries.firstOrNull()?.note)
        if (quiet) prefs.put(KEY_LAST_CHECK, report.atMillis.toString())
        // A first connect can carry thousands: keep the first 100 events, count the rest.
        else record(LogEntry(report.atMillis, trigger, items.take(100), listOfNotNull(note.ifEmpty { null }, (items.size - 100).takeIf { it > 0 }?.let { "외 ${it}건" }).joinToString(" · ")))
        return report
    }

    /** Exact twins inside each of the two calendars (left by overlapping passes). */
    /** Titles of the twins that a clean-up would remove, per side. */
    suspend fun duplicateTitles(config: BridgeConfig): Pair<List<String>, List<String>> {
        val paired = links.flatMap { listOf(it.samsungId, it.googleId) }.toSet()
        return dedupeTitles(store.bridgeItems(config.samsungCalendarId), paired) to
            dedupeTitles(store.bridgeItems(config.googleCalendarId), paired)
    }

    suspend fun findDuplicates(config: BridgeConfig): Pair<Int, Int> {
        val paired = links.flatMap { listOf(it.samsungId, it.googleId) }.toSet()
        return planDedupe(store.bridgeItems(config.samsungCalendarId), paired).size to
            planDedupe(store.bridgeItems(config.googleCalendarId), paired).size
    }

    /**
     * Deletes the twins (keeping each pair's own event), forgets pairs that
     * pointed at a deleted twin so its partner is not deleted after it, then
     * runs a pass that re-pairs what is left. Returns how many were deleted.
     */
    suspend fun removeDuplicates(config: BridgeConfig): Int = lock.withLock {
        val paired = links.flatMap { listOf(it.samsungId, it.googleId) }.toSet()
        val gone = (planDedupe(store.bridgeItems(config.samsungCalendarId), paired) +
            planDedupe(store.bridgeItems(config.googleCalendarId), paired)).filter { store.bridgeDelete(it) }.toSet()
        links = links.filterNot { it.samsungId in gone || it.googleId in gone }
        runLocked(config, allowMassDelete = false, { _, _ -> }, "중복 정리")
        gone.size
    }

    /**
     * Stops keeping pairs; both calendars keep every event they hold. Under the lock:
     * a pass still running would otherwise write the old pairs back after this.
     */
    suspend fun forget() = lock.withLock {
        prefs.put(KEY_LINKS, null)
        prefs.put(KEY_REPORT, null)
        prefs.put(KEY_REFUSED, null)
        prefs.put(KEY_OLO_DELETED, null)
        prefs.put(KEY_AUTO_DELETED, null)
    }

    private companion object {
        /**
         * One pass at a time in the whole process: the screen's engine and the
         * background worker's are separate objects, and two passes that overlap
         * both copy the same events (the duplicates of 0.8.0/0.8.1).
         */
        val lock = Mutex()
        const val KEY_LINKS = "bridge.links"
        const val KEY_REPORT = "bridge.report"
        const val KEY_REFUSED = "bridge.refused"
        const val KEY_LOG = "bridge.log"
        const val KEY_ENTRIES = "bridge.entries"
        const val KEY_LAST_CHECK = "bridge.lastCheck"
        const val KEY_OLO_DELETED = "bridge.oloDeleted"
        const val KEY_AUTO_DELETED = "bridge.autoDeleted"
        const val RS = "\u001C"
        /** How long an automatic deletion can be undone. */
        const val UNDO_HOURS = 24L
        /** How long the counts-only lines from before 0.10.8 stay on the sheet. */
        const val LEGACY_DAYS = 7L
    }
}
