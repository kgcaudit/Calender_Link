package app.olo.calendar

import app.olo.calendar.model.Occurrence
import app.olo.calendar.ui.searchHits
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTest {
    private fun occ(id: String, cal: String, title: String, at: Long, color: Int) = Occurrence(
        eventId = id, calendarId = cal, title = title, allDay = false,
        begin = Instant.fromEpochSeconds(at), end = Instant.fromEpochSeconds(at + 3600),
        firstDay = LocalDate(2025, 10, 18), lastDay = LocalDate(2025, 10, 18),
        location = "", colorArgb = color, recurring = false,
    )

    @Test
    fun sameEventInTwoCalendarsIsOneRow() {
        val hits = searchHits(
            listOf(
                occ("1", "16", "영화 어쩔수가없다", 100, 1),
                occ("2", "6", "영화 어쩔수가없다 ", 100, 2),
                occ("3", "6", "영화 주토피아 2", 50, 2),
            ),
        )
        assertEquals(listOf("영화 주토피아 2", "영화 어쩔수가없다"), hits.map { it.first.title })
        assertEquals(listOf(1, 2), hits[1].second)
    }
}
