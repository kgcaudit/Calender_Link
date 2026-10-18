package app.olo.calendar

import app.olo.calendar.model.BridgeException
import app.olo.calendar.model.BridgeFields
import app.olo.calendar.model.BridgeItem
import app.olo.calendar.model.BridgeLink
import app.olo.calendar.model.BridgeOp
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.MemoryPreferences
import app.olo.calendar.model.encodeFields
import app.olo.calendar.model.decodeFields
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
    fun aFewDeletionsGoThroughManyWaitForAYes() {
        val one = item("s1", "치과")
        val link = BridgeLink("s1", "g1", one.fingerprint, one.fingerprint, samsungConfirmed = true, googleConfirmed = true)
        // One event deleted in Samsung Calendar: the Google copy goes too.
        assertEquals(listOf<BridgeOp>(BridgeOp.DeleteGoogle("g1", "s1")), planBridge(emptyList(), listOf(item("g1", "치과")), listOf(link)).ops)
        // With no allowance it waits, as before 0.10.2.
        val held = planBridge(emptyList(), listOf(item("g1", "치과")), listOf(link), autoDeleteLimit = 0)
        assertTrue(held.ops.isEmpty())
        assertEquals(listOf<BridgeOp>(BridgeOp.DeleteGoogle("g1", "s1")), held.pendingDeletes)
        // Four gone at once (more than AUTO_DELETE_LIMIT) looks like a loss: all wait.
        val items = (1..4).map { item("s$it", "일정 $it", it * 1000L) }
        val links = items.map { BridgeLink(it.id, "g" + it.id.drop(1), it.fingerprint, it.fingerprint, true, true) }
        val many = planBridge(emptyList(), items.map { item("g" + it.id.drop(1), it.fields.title, it.fields.startMillis) }, links)
        assertTrue(many.ops.none { it is BridgeOp.DeleteGoogle })
        assertEquals(4, many.pendingDeletes.size)
    }

    @Test
    fun fieldsSurviveTheUndoRecord() {
        val f = item("s1", "가족 저녁\n메모", 5_000).fields.copy(exdates = listOf(1L, 2L), reminderMinutes = listOf(10, 60))
        assertEquals(f, decodeFields(encodeFields(f)))
    }

    @Test
    fun aCopyThatVanishedBeforeSyncingNeverDeletesTheOriginal() {
        // 0.8.2 lost 103 events this way: Google dropped copies it never accepted,
        // and the bridge took that for a deletion and removed the Samsung originals.
        val s1 = item("s1", "음력 생일")
        val link = BridgeLink("s1", "g1", s1.fingerprint, s1.fingerprint, samsungConfirmed = true, googleConfirmed = false)
        val p = planBridge(listOf(s1), emptyList(), listOf(link))
        assertTrue(p.pendingDeletes.isEmpty())
        assertTrue(p.ops.none { it is BridgeOp.DeleteSamsung })
        assertEquals(listOf(link), p.orphans)
        // Offered to Google once more; if refused before with the same content, not again.
        assertEquals(listOf<BridgeOp>(BridgeOp.CreateInGoogle("s1")), p.ops)
        val again = planBridge(listOf(s1), emptyList(), emptyList(), refused = mapOf("s1" to s1.fingerprint))
        assertTrue(again.ops.isEmpty()); assertEquals(1, again.unsupported)
    }

    @Test
    fun lunarAndVendorRulesStayOnSamsung() {
        val lunar = BridgeItem("s1", f("음력", rrule = "FREQ=YEARLY;X-SAMSUNG-LUNAR=1"))
        val p = planBridge(listOf(lunar), emptyList(), emptyList())
        assertTrue(p.ops.isEmpty()); assertEquals(1, p.unsupported)
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
    fun twinsAreNeverCopiedAcrossAndCanBeCleanedUp() = runTest {
        // Two passes overlapped (0.8.0): every event exists twice on both sides.
        val store = FakeBridgeStore()
        store.put("S", "1", f("치과")); store.put("S", "2", f("치과"))
        store.put("G", "11", f("치과")); store.put("G", "12", f("치과"))
        val prefs = MemoryPreferences()
        val engine = BridgeEngine(store, prefs)
        val cfg = BridgeConfig("S", "G")
        engine.run(cfg)
        assertEquals(2 to 2, store.count("S") to store.count("G"))
        assertEquals(1 to 1, engine.findDuplicates(cfg))
        assertEquals(2, engine.removeDuplicates(cfg))
        assertEquals(1 to 1, store.count("S") to store.count("G"))
        // The original (lowest id) is the one kept, and the pair is whole again.
        assertTrue(store.has("1"))
        assertEquals(1, engine.links.size)
        assertEquals(0 to 0, engine.findDuplicates(cfg))
    }

    @Test
    fun aRestoredCopyWithoutRemindersIsStillATwinAndTheRicherOneStays() {
        // 현정탄신일: the Samsung-trash original (with reminders) and OLO's restored copy (none).
        val original = BridgeItem("50", f("현정탄신일", rem = listOf(1440)))
        val copy = BridgeItem("900", f("현정탄신일", rem = emptyList()))
        assertEquals(listOf("900"), app.olo.calendar.model.planDedupe(listOf(copy, original), setOf("900")))
    }

    @Test
    fun anUnpairedTwinOfAPairedEventIsNotCopied() {
        val a = item("s1", "치과"); val g = item("g1", "치과")
        val p = planBridge(listOf(a, item("s9", "치과")), listOf(g, item("g9", "치과")), listOf(BridgeLink("s1", "g1", a.fingerprint, g.fingerprint)))
        // s9 and g9 pair with each other (same look) rather than being copied twice more.
        assertEquals(listOf<BridgeOp>(BridgeOp.Agree("s9", "g9")), p.ops)
        val q = planBridge(listOf(a, item("s9", "치과")), listOf(g), listOf(BridgeLink("s1", "g1", a.fingerprint, g.fingerprint)))
        assertTrue(q.ops.isEmpty()); assertEquals(1, q.duplicates)
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

        // Edit on the iPhone side reaches Samsung.
        val gOfS1 = engine.links.first { it.samsungId == "s1" }.googleId
        store.put("G", gOfS1, f("치과 (변경)"))
        engine.run(cfg)
        assertEquals("치과 (변경)", store.fields("s1").title)
        // One event deleted on Samsung carries over by itself, and can be undone for a day.
        store.remove("s2")
        engine.run(cfg)
        assertEquals(2, store.count("G"))
        assertEquals(listOf("회의"), engine.recentAutoDeleted.map { it.second.title })
        assertEquals(1, engine.undoAutoDeleted(cfg))
        assertEquals(3, store.count("S")); assertEquals(3, store.count("G"))
        assertTrue(engine.recentAutoDeleted.isEmpty())
    }

    @Test
    fun manyDeletionsWaitAndCanBeKeptOrConfirmed() = runTest {
        val store = FakeBridgeStore()
        (1..5).forEach { store.put("S", "s$it", f("일정 $it", it * 10_000L)) }
        val engine = BridgeEngine(store, MemoryPreferences())
        val cfg = BridgeConfig("S", "G")
        engine.run(cfg)
        // Four vanish from Samsung at once: nothing is deleted on Google.
        (1..4).forEach { store.remove("s$it") }
        engine.run(cfg)
        assertEquals(5, store.count("G"))
        assertEquals(4, engine.pendingDeletes(cfg).size)
        // Keeping them copies the survivors back.
        engine.keepPending(cfg)
        assertEquals(5, store.count("S"))
        // Deleting for real takes the person's yes.
        val doomed = engine.links.filter { store.fields(it.samsungId).title in setOf("일정 1", "일정 2", "일정 3", "일정 4") }.map { it.samsungId }
        doomed.forEach { store.remove(it) }
        engine.run(cfg, allowMassDelete = true)
        assertEquals(1, store.count("G"))
        // And what the phone still holds of a deleted event can be brought back.
        assertTrue(engine.recoverable(cfg).any { it.fields.title == "일정 1" })
        assertEquals(4, engine.restore(cfg, connected = true))
        assertEquals(5, store.count("S")); assertEquals(5, store.count("G"))
    }

    @Test
    fun deletionsThePersonMadeAreNotOfferedBack() = runTest {
        val store = FakeBridgeStore()
        store.put("S", "s1", f("영화 스트리트 파이터"))
        store.put("S", "s2", f("회의", 9_000))
        val engine = BridgeEngine(store, MemoryPreferences())
        val cfg = BridgeConfig("S", "G")
        engine.run(cfg)
        // Deleted in Samsung Calendar: the phone keeps a DELETED row until it syncs.
        val sid = engine.links.first { store.fields(it.samsungId).title == "영화 스트리트 파이터" }.samsungId
        store.bridgeDelete(sid)
        // The person agrees to delete the Google copy too; OLO deletes it.
        engine.run(cfg, allowMassDelete = true)
        assertEquals(1, store.count("G"))
        assertTrue(engine.recoverable(cfg).isEmpty())
    }

    @Test
    fun syncNowLeavesExcludedAccountsAlone() = runTest {
        val store = FakeBridgeStore()
        val prefs = MemoryPreferences()
        prefs.put("syncExcluded", "com.google/hazakuqa@gmail.com")
        val controller = app.olo.calendar.ui.CalendarController(store, backgroundScope, prefs = prefs)
        controller.syncNow()
        // Background work is not waited for by advanceUntilIdle: step virtual time past the 1.5 s wait.
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(setOf("com.google/hazakuqa@gmail.com"), store.lastExclude)
        assertEquals(false, controller.state.value.syncRun?.running)
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
    fun has(id: String) = id in rows

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
    override suspend fun bridgeCreate(fields: BridgeFields, toCalendarId: String): String {
        val id = "n${next++}"; rows[id] = toCalendarId to fields; return id
    }
    override suspend fun bridgeDelete(id: String) = rows.remove(id)?.also { gone[id] = it } != null
    val gone = LinkedHashMap<String, Pair<String, BridgeFields>>()
    override suspend fun bridgeDeleted(calendarId: String) = gone.filter { it.value.first == calendarId }.map { BridgeItem(it.key, it.value.second) }
    override suspend fun bridgeRestore(deletedId: String, toCalendarId: String): String? {
        val src = gone[deletedId] ?: return null
        val id = "n${next++}"
        rows[id] = toCalendarId to src.second
        return id
    }

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
    var lastExclude: Set<String>? = null
    override fun requestSync(exclude: Set<String>) { lastExclude = exclude }
    override fun changes(): Flow<Unit> = emptyFlow()
    override suspend fun eventCounts(): Map<String, Int> = emptyMap()
    override suspend fun planMove(fromCalendarId: String) = MovePlan(fromCalendarId, 0, 0, 0, 0)
    override suspend fun move(fromCalendarId: String, toCalendarId: String, deleteOriginals: Boolean, onProgress: (Int, Int) -> Unit) = MoveResult(0, 0, 0)
}
