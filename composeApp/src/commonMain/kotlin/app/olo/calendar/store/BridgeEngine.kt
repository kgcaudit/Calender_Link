package app.olo.calendar.store

import app.olo.calendar.model.BridgeItem
import app.olo.calendar.model.BridgeLink
import app.olo.calendar.model.BridgeOp
import app.olo.calendar.model.BridgePlan
import app.olo.calendar.model.Preferences
import app.olo.calendar.model.decodeLinks
import app.olo.calendar.model.encodeLinks
import app.olo.calendar.model.planBridge
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
    private val lock = Mutex()

    var links: List<BridgeLink>
        get() = decodeLinks(prefs.get(KEY_LINKS))
        private set(v) = prefs.put(KEY_LINKS, encodeLinks(v))

    val lastReport: BridgeReport? get() = BridgeReport.decode(prefs.get(KEY_REPORT))

    /** Google-side ids of linked pairs: OLO shows those events once, from Samsung. */
    fun linkedGoogleIds(): Set<String> = links.map { it.googleId }.toSet()

    suspend fun preview(config: BridgeConfig): BridgePlan =
        planBridge(store.bridgeItems(config.samsungCalendarId), store.bridgeItems(config.googleCalendarId), links)

    suspend fun run(
        config: BridgeConfig,
        allowMassDelete: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BridgeReport = lock.withLock {
        val samsung = store.bridgeItems(config.samsungCalendarId)
        val google = store.bridgeItems(config.googleCalendarId)
        val plan = planBridge(samsung, google, links, allowMassDelete)
        val pairs = links.associateBy { it.samsungId }.toMutableMap()
        pairs.values.removeAll(plan.dropped.toSet())
        var failed = 0
        var deleted = 0
        val total = plan.writes
        var done = 0
        onProgress(0, total)

        // Freshly written ids: the source's print stands in for the partner's until it is read back.
        val touched = HashSet<String>()
        fun pair(sid: String, gid: String) { pairs[sid] = BridgeLink(sid, gid, "", ""); touched += sid }

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
                    is BridgeOp.DeleteGoogle -> store.bridgeDelete(op.googleId).also { if (it) { pairs.remove(op.samsungId); deleted++ } }
                    is BridgeOp.DeleteSamsung -> store.bridgeDelete(op.samsungId).also { if (it) { pairs.remove(op.samsungId); deleted++ } }
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
                l.samsungId in touched && si != null && gi != null -> BridgeLink(l.samsungId, l.googleId, si.fingerprint, gi.fingerprint)
                l.samsungPrint.isEmpty() || l.googlePrint.isEmpty() -> null
                else -> l
            }
        }
        val report = BridgeReport(
            atMillis = Clock.System.now().toEpochMilliseconds(),
            toGoogle = plan.toGoogle, toSamsung = plan.toSamsung, deleted = deleted,
            conflicts = plan.conflicts, heldDeletes = plan.heldDeletes, failed = failed, linked = links.size,
        )
        prefs.put(KEY_REPORT, report.encode())
        report
    }

    /** Stops keeping pairs; both calendars keep every event they hold. */
    fun forget() {
        prefs.put(KEY_LINKS, null)
        prefs.put(KEY_REPORT, null)
    }

    private companion object {
        const val KEY_LINKS = "bridge.links"
        const val KEY_REPORT = "bridge.report"
    }
}
