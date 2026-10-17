package app.olo.calendar.store

import app.olo.calendar.model.BridgeItem
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
        runLocked(config, allowMassDelete = false) { _, _ -> }
    }

    /**
     * Deleted series still held by the phone (not yet purged by the sync
     * adapter) on either side, that no live Samsung event matches: what a wrong
     * deletion took and can still be brought back.
     */
    suspend fun recoverable(config: BridgeConfig): List<BridgeItem> {
        // Anything that already looks like a live event (same title, start, end, rule) is
        // not brought back: it was restored another way (the Samsung trash, say).
        val live = (store.bridgeItems(config.samsungCalendarId) + store.bridgeItems(config.googleCalendarId)).map { it.twinKey }.toSet()
        return (store.bridgeDeleted(config.samsungCalendarId) + store.bridgeDeleted(config.googleCalendarId))
            .filter { it.twinKey !in live }
            .distinctBy { it.twinKey }
    }

    /** Brings them back into Samsung Calendar (the home calendar); a pass then copies them to Google. */
    suspend fun restore(config: BridgeConfig, connected: Boolean): Int = lock.withLock {
        val items = recoverable(config)
        var n = 0
        items.forEach { if (store.bridgeRestore(it.id, config.samsungCalendarId) != null) n++ }
        if (connected) runLocked(config, allowMassDelete = false) { _, _ -> }
        appendLog("${Clock.System.now().toEpochMilliseconds()}|되살림 $n · " + items.take(12).joinToString(", ") { it.fields.title })
        n
    }

    /** The last passes, newest first, for the bridge sheet's record. */
    val log: List<String> get() = prefs.get(KEY_LOG).orEmpty().split('\n').filter { it.isNotBlank() }

    private fun appendLog(line: String) {
        prefs.put(KEY_LOG, (listOf(line) + log).take(40).joinToString("\n"))
    }

    suspend fun run(
        config: BridgeConfig,
        allowMassDelete: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BridgeReport = lock.withLock { runLocked(config, allowMassDelete, onProgress) }

    private suspend fun runLocked(config: BridgeConfig, allowMassDelete: Boolean, onProgress: (Int, Int) -> Unit): BridgeReport {
        val samsung = store.bridgeItems(config.samsungCalendarId)
        val google = store.bridgeItems(config.googleCalendarId)
        val plan = planBridge(samsung, google, links, allowMassDelete, refused)
        // A copy Google dropped before it ever synced: remember the Samsung event so it
        // is offered once more at most, not resent on every pass.
        val gIds = google.map { it.id }.toSet()
        val sById = samsung.associateBy { it.id }
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
        val deletedTitles = mutableListOf<String>()
        val sTitle = samsung.associate { it.id to it.fields.title }
        val gTitle = google.associate { it.id to it.fields.title }
        fun sPrintTitle(id: String) = sTitle[id].orEmpty()
        fun gPrintTitle(id: String) = gTitle[id].orEmpty()
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
                    is BridgeOp.CreateInGoogle -> store.bridgeCopy(op.samsungId, config.googleCalendarId)?.also { pair(op.samsungId, it) } != null
                    is BridgeOp.CreateInSamsung -> store.bridgeCopy(op.googleId, config.samsungCalendarId)?.also { pair(it, op.googleId) } != null
                    is BridgeOp.UpdateGoogle -> store.bridgeReplace(op.samsungId, op.googleId)?.also { pair(op.samsungId, it) } != null
                    is BridgeOp.UpdateSamsung -> store.bridgeReplace(op.googleId, op.samsungId)?.also { newS ->
                        if (newS != op.samsungId) pairs.remove(op.samsungId)
                        pair(newS, op.googleId)
                    } != null
                    is BridgeOp.DeleteGoogle -> store.bridgeDelete(op.googleId).also { if (it) { pairs.remove(op.samsungId); deleted++; deletedTitles += gPrintTitle(op.googleId) } }
                    is BridgeOp.DeleteSamsung -> store.bridgeDelete(op.samsungId).also { if (it) { pairs.remove(op.samsungId); deleted++; deletedTitles += sPrintTitle(op.samsungId) } }
                }
            }.getOrDefault(false)
            if (!ok) failed++
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
            toGoogle = plan.toGoogle, toSamsung = plan.toSamsung, deleted = deleted,
            conflicts = plan.conflicts, heldDeletes = plan.heldDeletes, failed = failed, linked = links.size,
        )
        prefs.put(KEY_REPORT, report.encode())
        appendLog(
            "${report.atMillis}|구글로 ${plan.toGoogle} · 삼성으로 ${plan.toSamsung} · 삭제 $deleted · 삭제 대기 ${plan.pendingDeletes.size}" +
                " · 동기화 전 사라짐 ${plan.orphans.size} · 구글 불가 ${plan.unsupported} · 실패 $failed" +
                (if (deletedTitles.isNotEmpty()) " | 지움: " + deletedTitles.take(12).joinToString(", ") else ""),
        )
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
        runLocked(config, allowMassDelete = false) { _, _ -> }
        gone.size
    }

    /** Stops keeping pairs; both calendars keep every event they hold. */
    fun forget() {
        prefs.put(KEY_LINKS, null)
        prefs.put(KEY_REPORT, null)
        prefs.put(KEY_REFUSED, null)
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
    }
}
