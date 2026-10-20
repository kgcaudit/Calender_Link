package app.olo.calendar

import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EditScope
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.MemoryPreferences
import app.olo.calendar.model.MovePlan
import app.olo.calendar.model.MoveResult
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.OccurrenceRef
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncStatus
import app.olo.calendar.model.at
import app.olo.calendar.model.occurrenceDraft
import app.olo.calendar.model.ref
import app.olo.calendar.model.ruleCount
import app.olo.calendar.model.ruleEndingBefore
import app.olo.calendar.model.ruleWithCount
import app.olo.calendar.store.CalendarStore
import app.olo.calendar.ui.CalendarController
import app.olo.calendar.ui.UNDO_MILLIS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/10: one occurrence, from one on, or the whole series; and a deletion that can be taken back. */
class SeriesEditTest {
    private val zone = TimeZone.of("Asia/Seoul")
    private val day = LocalDate(2026, 10, 21)

    private fun occ(event: String, series: String, d: LocalDate, title: String = "주간 회의") =
        Occurrence(event, "c", title, false, d.at(LocalTime(10, 0), zone), d.at(LocalTime(11, 0), zone), d, d, "", 0, true, series)

    @Test
    fun aSeriesEndsJustBeforeTheSplit() {
        val at = day.at(LocalTime(10, 0), zone).toEpochMilliseconds() // 01:00 UTC
        assertEquals("FREQ=WEEKLY;BYDAY=WE;UNTIL=20261021T005959Z", ruleEndingBefore("FREQ=WEEKLY;BYDAY=WE;COUNT=10", at, allDay = false))
        assertEquals("RRULE:FREQ=DAILY;UNTIL=20261020", ruleEndingBefore("RRULE:FREQ=DAILY;UNTIL=20261231", LocalDate(2026, 10, 21).at(LocalTime(0, 0), TimeZone.UTC).toEpochMilliseconds(), allDay = true))
        assertEquals(10, ruleCount("FREQ=WEEKLY;COUNT=10"))
        assertEquals("FREQ=WEEKLY;COUNT=7", ruleWithCount("FREQ=WEEKLY;COUNT=10", 7))
    }

    @Test
    fun scopesReachTheRightOccurrences() {
        val week1 = occ("m", "m", LocalDate(2026, 10, 14))
        val week2 = occ("m", "m", day)
        val week3 = occ("x9", "m", LocalDate(2026, 10, 28)) // a changed occurrence
        val other = occ("o", "o", day, "다른 일정")
        val r = week2.ref()
        assertEquals(listOf(week2), listOf(week1, week2, week3, other).filter { r.covers(it, EditScope.THIS) })
        assertEquals(listOf(week2, week3), listOf(week1, week2, week3, other).filter { r.covers(it, EditScope.FOLLOWING) })
        assertEquals(listOf(week1, week2, week3), listOf(week1, week2, week3, other).filter { r.covers(it, EditScope.ALL) })
        assertTrue(week3.ref().isException)
    }

    @Test
    fun anOccurrenceOpensOnItsOwnDate() {
        val master = EventDraft(id = "m", calendarId = "c", title = "주간 회의", startDate = LocalDate(2026, 9, 2), startTime = LocalTime(10, 0),
            endDate = LocalDate(2026, 9, 2), endTime = LocalTime(11, 0), timeZoneId = zone.id)
        val d = occurrenceDraft(master, occ("m", "m", day).ref())
        assertEquals(day, d.startDate)
        assertEquals(LocalTime(10, 0), d.startTime)
        assertEquals(LocalTime(11, 0), d.endTime)
    }

    @Test
    fun aDeletionWaitsAndCanBeUndone() = runTest {
        val store = RecordingStore(listOf(occ("m", "m", day), occ("o", "o", day, "점심")))
        val c = CalendarController(store, backgroundScope, zone, MemoryPreferences())
        runCurrent()
        c.select(day)
        runCurrent()
        val target = c.state.value.occurrences.first { it.eventId == "m" }
        c.delete(target, EditScope.THIS)
        // Off the screen at once, but nothing sent yet.
        assertTrue(c.state.value.occurrences.none { it.eventId == "m" })
        advanceTimeBy(UNDO_MILLIS - 1000)
        assertTrue(store.deleted.isEmpty())
        c.undoDelete()
        assertTrue(c.state.value.occurrences.any { it.eventId == "m" })
        advanceTimeBy(UNDO_MILLIS * 2); runCurrent()
        assertTrue(store.deleted.isEmpty())

        // Left alone, it goes through once the moment has passed.
        c.delete(target, EditScope.FOLLOWING)
        advanceTimeBy(UNDO_MILLIS + 1); runCurrent()
        assertEquals(listOf(target.ref() to EditScope.FOLLOWING), store.deleted)
        assertFalse(c.state.value.pendingDelete != null)
    }
}

private class RecordingStore(private val items: List<Occurrence>) : CalendarStore {
    val deleted = mutableListOf<Pair<OccurrenceRef, EditScope>>()
    override suspend fun calendars(): List<CalendarInfo> =
        listOf(CalendarInfo("c", "Samsung Calendar", "me", "com.osp.app.signin", app.olo.calendar.model.SyncKind.SAMSUNG, 0, true, true, true, false))
    override suspend fun occurrences(from: Instant, to: Instant) = items.filter { it.begin >= from && it.begin < to }
    override suspend fun search(query: String, from: Instant, to: Instant): List<Occurrence> = emptyList()
    override suspend fun event(id: String): StoredEvent? = null
    override suspend fun save(draft: EventDraft) = ""
    override suspend fun delete(id: String) = Unit
    override suspend fun deleteOccurrence(at: OccurrenceRef, scope: EditScope) { deleted += at to scope }
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
