package app.olo.calendar

import app.olo.calendar.model.EditScope
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.LunarGroup
import app.olo.calendar.model.LunarRule
import app.olo.calendar.model.MemoryPreferences
import app.olo.calendar.model.MovePlan
import app.olo.calendar.model.MoveResult
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncStatus
import app.olo.calendar.model.decodeLunarGroups
import app.olo.calendar.model.encodeLunarGroups
import app.olo.calendar.model.lunarDateIn
import app.olo.calendar.model.lunarRuleOf
import app.olo.calendar.model.lunarYearlyDates
import app.olo.calendar.store.CalendarStore
import app.olo.calendar.store.LunarRepeats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/11: every lunar year as its own event, kept as one repeat. */
class LunarRepeatTest {
    @Test
    fun lunarDatesFallOnTheRightSolarDays() {
        // 추석 (8/15) in 2026, 2027, 2028.
        assertEquals(
            listOf(LocalDate(2026, 9, 25), LocalDate(2027, 9, 15), LocalDate(2028, 10, 3)),
            lunarYearlyDates(LunarRule(8, 15), LocalDate(2026, 1, 1), 3),
        )
        assertEquals(LunarRule(8, 15), lunarRuleOf(LocalDate(2026, 9, 25)))
        // A 30th falls on the 29th in a 29-day month.
        val y = (2026..2040).first { app.olo.calendar.model.KoreanLunar.monthLength(it, 1) == 29 }
        assertEquals(app.olo.calendar.model.KoreanLunar.toSolar(y, 1, 29), lunarDateIn(y, LunarRule(1, 30)))
        // Past the table's end: nothing invented.
        assertTrue(lunarYearlyDates(LunarRule(1, 1), LocalDate(2059, 6, 1), 10).size <= 2)
    }

    @Test
    fun groupsSurviveStorageAndReachTheRightYears() {
        val g = LunarGroup("L1", LunarRule(3, 4), mapOf("a" to LocalDate(2026, 4, 20), "b" to LocalDate(2027, 4, 10), "c" to LocalDate(2028, 3, 29)))
        assertEquals(listOf(g), decodeLunarGroups(encodeLunarGroups(listOf(g))))
        assertEquals(setOf("b"), g.reach("b", EditScope.THIS))
        assertEquals(setOf("b", "c"), g.reach("b", EditScope.FOLLOWING))
        assertEquals(setOf("a", "b", "c"), g.reach("b", EditScope.ALL))
    }

    @Test
    fun createEditDeleteAndTopUp() = runTest {
        val store = MemStore()
        val deleting = mutableListOf<String>()
        val lunar = LunarRepeats(store, MemoryPreferences()) { deleting += it }
        val start = LocalDate(2026, 9, 25) // 음력 8/15
        val ids = lunar.create(EventDraft(calendarId = "c", title = "할머니 생신", allDay = true, startDate = start, timeZoneId = "Asia/Seoul", lunarYearly = true))
        assertEquals(10, ids.size)
        assertEquals(LocalDate(2027, 9, 15), store.rows[ids[1]]!!.startDate)
        assertTrue(store.rows.values.none { it.lunarYearly || it.recurrence != null })

        // A new title for this year and the ones after: each keeps its own date.
        val third = ids[2]
        lunar.save(store.rows[third]!!.copy(title = "할머니 생신 (본가)"), third, EditScope.FOLLOWING)
        assertEquals("할머니 생신", store.rows[ids[1]]!!.title)
        assertEquals("할머니 생신 (본가)", store.rows[ids[5]]!!.title)
        assertEquals(LocalDate(2028, 10, 3), store.rows[third]!!.startDate)

        // Deleting from the 8th year on removes three, and says so first.
        lunar.delete(ids[7], EditScope.FOLLOWING)
        assertEquals(7, lunar.groupOf(ids[0])!!.members.size)
        assertEquals(ids.drop(7).toSet(), deleting.toSet())

        // Fewer than five years left (seen from 2031): topped up to ten ahead.
        val added = lunar.topUp(LocalDate(2031, 1, 1))
        assertTrue(added > 0)
        val last = lunar.groupOf(ids[0])!!.last!!
        assertTrue(last >= LocalDate(2040, 1, 1) && last <= LocalDate(2041, 1, 1))
    }
}

private class MemStore : CalendarStore {
    val rows = linkedMapOf<String, EventDraft>()
    private var next = 1
    override suspend fun save(draft: EventDraft): String {
        val id = draft.id ?: "e${next++}"
        rows[id] = draft.copy(id = id)
        return id
    }
    override suspend fun delete(id: String) { rows.remove(id) }
    override suspend fun event(id: String): StoredEvent? = rows[id]?.let { StoredEvent(it, null, true, true, false) }
    override suspend fun calendars() = emptyList<app.olo.calendar.model.CalendarInfo>()
    override suspend fun occurrences(from: Instant, to: Instant): List<Occurrence> = emptyList()
    override suspend fun search(query: String, from: Instant, to: Instant): List<Occurrence> = emptyList()
    override suspend fun setVisible(calendarId: String, visible: Boolean) = Unit
    override suspend fun syncStatus() = SyncStatus()
    override suspend fun setAccountSync(account: String, on: Boolean) = true
    override suspend fun setMasterSync(on: Boolean) = true
    override fun requestSync(exclude: Set<String>) = Unit
    override fun changes(): Flow<Unit> = emptyFlow()
    override suspend fun eventCounts(): Map<String, Int> = emptyMap()
    override suspend fun planMove(fromCalendarId: String) = MovePlan(fromCalendarId, 0, 0, 0, 0)
    override suspend fun move(fromCalendarId: String, toCalendarId: String, deleteOriginals: Boolean, onProgress: (Int, Int) -> Unit) = MoveResult(0, 0, 0)
    override suspend fun bridgeItems(calendarId: String): List<app.olo.calendar.model.BridgeItem> = emptyList()
    override suspend fun bridgeCopy(sourceId: String, toCalendarId: String): String? = null
    override suspend fun bridgeReplace(sourceId: String, targetId: String): String? = null
    override suspend fun bridgeDelete(id: String) = false
    override suspend fun bridgeDeleted(calendarId: String): List<app.olo.calendar.model.BridgeItem> = emptyList()
    override suspend fun bridgeRestore(deletedId: String, toCalendarId: String): String? = null
}
