package app.olo.calendar

import app.olo.calendar.model.BridgeException
import app.olo.calendar.model.BridgeFields
import app.olo.calendar.model.BridgeItem
import app.olo.calendar.model.BridgeLink
import app.olo.calendar.model.BridgeOp
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.MemoryPreferences
import app.olo.calendar.model.MovePlan
import app.olo.calendar.model.MoveResult
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncStatus
import app.olo.calendar.model.decodeLinks
import app.olo.calendar.model.encodeLinks
import app.olo.calendar.model.parseExdates
import app.olo.calendar.model.planBridge
import app.olo.calendar.store.BridgeConfig
import app.olo.calendar.store.BridgeEngine
import app.olo.calendar.store.CalendarStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun f(title: String, start: Long = 1_000L, rrule: String = "", ex: List<Long> = emptyList(), rem: List<Int> = listOf(10)) =
    BridgeFields(title, "", "", false, start, start + 3_600_000L, "Asia/Seoul", rrule, ex, rem, emptyList())

private fun item(id: String, title: String, start: Long = 1_000L) = BridgeItem(id, f(title, start))

class BridgeTest {
    @Test
    fun firstRunPairsCopiesAndNeverDuplicates() {
        val s = listOf(item("s1", "치과"), item("s2", "회의", 9_000))
        val g = listOf(item("g1", "치과"), item("g2", "아이폰에서 만든 일정", 5_000))
        val p = planBridge(s, g, emptyList())
        assertEquals(setOf<BridgeOp>(BridgeOp.Agree("s1", "g1"), BridgeOp.CreateInGoogle("s2"), BridgeOp.CreateInSamsung("g2")), p.ops.toSet())
        assertEquals(1, p.toGoogle); assertEquals(1, p.toSamsung); assertEquals(1, p.paired)
    }

    @Test
    fun steadyStateDoesNothing() {
        val s = item("s1", "치과"); val g = item("g1", "치과")
        val p = planBridge(listOf(s), listOf(g), listOf(BridgeLink("s1", "g1", s.fingerprint, g.fingerprint)))
        assertTrue(p.ops.isEmpty())
    }

    @Test
    fun changesFlowToTheOtherSideAndSamsungWinsConflicts() {
        val old = item("x", "치과")
        val link = BridgeLink("s1", "g1", old.fingerprint, old.fingerprint)
        // Edited on the Galaxy side.
        assertEquals(listOf<BridgeOp>(BridgeOp.UpdateGoogle("s1", "g1")), planBridge(listOf(item("s1", "치과 3시")), listOf(item("g1", "치과")), listOf(link)).ops)
        // Edited on the iPhone (Google) side.
        assertEquals(listOf<BridgeOp>(BridgeOp.UpdateSamsung("g1", "s1")), planBridge(listOf(item("s1", "치과")), listOf(item("g1", "치과 4시")), listOf(link)).ops)
        // Both: Samsung's version is kept.
        val both = planBridge(listOf(item("s1", "치과 3시")), listOf(item("g1", "치과 4시")), listOf(link))
        assertEquals(listOf<BridgeOp>(BridgeOp.UpdateGoogle("s1", "g1")), both.ops)
        assertEquals(1, both.conflicts)
    }

    @Test
    fun deletionsFollowButAMassDeleteWaitsForAYes() {
        val one = item("s1", "치과")
        val link = BridgeLink("s1", "g1", one.fingerprint, one.fingerprint)
        assertEquals(listOf<BridgeOp>(BridgeOp.DeleteGoogle("g1", "s1")), planBridge(emptyList(), listOf(item("g1", "치과")), listOf(link)).ops)

        val links = (1..20).map { BridgeLink("s$it", "g$it", one.fingerprint, one.fingerprint) }
        val google = (1..20).map { item("g$it", "치과") }
        val held = planBridge(emptyList(), google, links)
        assertEquals(20, held.heldDeletes)
        assertTrue(held.ops.none { it is BridgeOp.DeleteGoogle })
        assertEquals(20, planBridge(emptyList(), google, links, allowMassDelete = true).deletes)
    }

    @Test
    fun fingerprintIgnoresProviderBookkeeping() {
        val samsung = f("반복", rrule = "FREQ=WEEKLY;BYDAY=MO", ex = listOf(3L, 1L), rem = listOf(30, 10))
        val google = f("반복 ", rrule = "RRULE:BYDAY=MO;FREQ=WEEKLY;WKST=MO", ex = listOf(1L, 3L, 3L), rem = listOf(10, 30))
        assertEquals(BridgeItem("a", samsung).fingerprint, BridgeItem("b", google).fingerprint)
        val changed = samsung.copy(exceptions = listOf(BridgeException(7L, cancelled = true)))
        assertTrue(BridgeItem("a", changed).fingerprint != BridgeItem("a", samsung).fingerprint)
    }

    @Test
    fun exdatesAndLinksRoundTrip() {
        assertEquals(listOf(1_792_454_400_000L), parseExdates("20261020T000000Z"))
        assertEquals(listOf(1_792_454_400_000L), parseExdates("20261020"))
        assertEquals(listOf(1_792_454_400_000L), parseExdates("TZID=Asia/Seoul;20261020T090000"))
        val links = listOf(BridgeLink("1", "2", "a", "b"), BridgeLink("3", "4", "c", "d"))
        assertEquals(links, decodeLinks(encodeLinks(links)))
    }

    @Test
    fun engineSettlesAndFollowsEdits() = runTest {
        val store = FakeBridgeStore()
        store.put("S", "s1", f("치과"))
        store.put("S", "s2", f("회의", 9_000))
        store.put("G", "g1", f("아이폰 일정", 5_000))
        val engine = BridgeEngine(store, MemoryPreferences())
        val cfg = BridgeConfig("S", "G")

        val first = engine.run(cfg)
        assertEquals(2, first.toGoogle); assertEquals(1, first.toSamsung); assertEquals(3, first.linked)
        assertEquals(3, store.count("S")); assertEquals(3, store.count("G"))

        // The bridge's own writes come back as changes: the next pass writes nothing.
        val second = engine.run(cfg)
        assertEquals(0, second.toGoogle + second.toSamsung + second.deleted)

        // Edit on the iPhone side reaches Samsung; delete on Samsung reaches Google.
        val gOfS1 = engine.links.first { it.samsungId == "s1" }.googleId
        store.put("G", gOfS1, f("치과 (변경)"))
        engine.run(cfg)
        assertEquals("치과 (변경)", store.fields("s1").title)
        store.remove("s2")
        engine.run(cfg)
        assertEquals(2, store.count("G"))
    }
}

/** Two calendars in memory, enough of CalendarStore for the bridge. */
private class FakeBridgeStore : CalendarStore {
    private val rows = LinkedHashMap<String, Pair<String, BridgeFields>>()
    private var next = 100
    fun put(cal: String, id: String, fields: BridgeFields) { rows[id] = cal to fields }
    fun remove(id: String) { rows.remove(id) }
    fun count(cal: String) = rows.values.count { it.first == cal }
    fun fields(id: String) = rows.getValue(id).second

    override suspend fun bridgeItems(calendarId: String) = rows.filter { it.value.first == calendarId }.map { BridgeItem(it.key, it.value.second) }
    override suspend fun bridgeCopy(sourceId: String, toCalendarId: String): String? {
        val src = rows[sourceId] ?: return null
        val id = "n${next++}"
        rows[id] = toCalendarId to src.second
        return id
    }
    override suspend fun bridgeReplace(sourceId: String, targetId: String): String? {
        val src = rows[sourceId] ?: return null
        val cal = rows[targetId]?.first ?: return null
        rows[targetId] = cal to src.second
        return targetId
    }
    override suspend fun bridgeDelete(id: String) = rows.remove(id) != null

    override suspend fun calendars(): List<CalendarInfo> = emptyList()
    override suspend fun occurrences(from: Instant, to: Instant): List<Occurrence> = emptyList()
    override suspend fun search(query: String, from: Instant, to: Instant): List<Occurrence> = emptyList()
    override suspend fun event(id: String): StoredEvent? = null
    override suspend fun save(draft: EventDraft) = ""
    override suspend fun delete(id: String) = Unit
    override suspend fun setVisible(calendarId: String, visible: Boolean) = Unit
    override suspend fun syncStatus() = SyncStatus()
    override suspend fun setAccountSync(account: String, on: Boolean) = true
    override suspend fun setMasterSync(on: Boolean) = true
    override fun requestSync() = Unit
    override fun changes(): Flow<Unit> = emptyFlow()
    override suspend fun eventCounts(): Map<String, Int> = emptyMap()
    override suspend fun planMove(fromCalendarId: String) = MovePlan(fromCalendarId, 0, 0, 0, 0)
    override suspend fun move(fromCalendarId: String, toCalendarId: String, deleteOriginals: Boolean, onProgress: (Int, Int) -> Unit) = MoveResult(0, 0, 0)
}
