package app.olo.calendar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import app.olo.calendar.model.SharedEvent
import app.olo.calendar.model.at
import app.olo.calendar.model.fold
import app.olo.calendar.model.icsFileName
import app.olo.calendar.model.icsOf
import app.olo.calendar.model.icsText
import app.olo.calendar.model.shareText
import app.olo.calendar.store.BridgeReport
import app.olo.calendar.store.bridgeAlert
import app.olo.calendar.ui.linkified
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/12: sharing, tappable places and links, and the bridge's notification. */
class ShareTest {
    private val zone = TimeZone.of("Asia/Seoul")
    private val day = LocalDate(2026, 10, 19)
    private val meeting = SharedEvent(
        "e1-1", "기전팀 보고", false, day.at(LocalTime(13, 30), zone), day.at(LocalTime(14, 30), zone), day, day,
        "본사 3층; 대회의실", "자료: https://meet.google.com/abc-defg-hij\n문의 010-1234-5678",
    )

    @Test
    fun messageReadsLikeAPerson() {
        assertEquals(
            "기전팀 보고\n10월 19일 (월) 오후 1:30 – 오후 2:30\n장소 · 본사 3층; 대회의실\n메모 · 자료: https://meet.google.com/abc-defg-hij\n문의 010-1234-5678",
            shareText(meeting, zone, use24 = false),
        )
        val birthday = meeting.copy(allDay = true, location = "", description = "")
        assertEquals("기전팀 보고\n10월 19일 (월) 종일", shareText(birthday, zone, use24 = true))
    }

    @Test
    fun icsIsAValidSingleEvent() {
        val ics = icsOf(meeting, 0L)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n") && ics.endsWith("END:VCALENDAR\r\n"))
        assertTrue("DTSTART:20261019T043000Z\r\n" in ics)
        assertTrue("DTEND:20261019T053000Z\r\n" in ics)
        assertTrue("LOCATION:본사 3층\\; 대회의실\r\n" in ics)
        // All day: dates, the end the day after.
        val allDay = icsOf(meeting.copy(allDay = true), 0L)
        assertTrue("DTSTART;VALUE=DATE:20261019\r\nDTEND;VALUE=DATE:20261020\r\n" in allDay)
        assertEquals("a\\,b\\nc\\\\", icsText("a,b\nc\\"))
        // Folded at 75 octets without splitting a Korean character (3 octets each).
        val long = fold("SUMMARY:" + "가".repeat(40))
        assertTrue(long.split("\r\n").all { it.encodeToByteArray().size <= 75 })
        assertEquals("SUMMARY:" + "가".repeat(40), long.replace("\r\n ", ""))
        assertEquals("a b.ics", icsFileName("a/b"))
    }

    @Test
    fun linksAndPhoneNumbersInTheMemoCanBeTapped() {
        val t = linkified(meeting.description, Color.Red)
        val urls = t.getLinkAnnotations(0, t.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://meet.google.com/abc-defg-hij", "tel:01012345678"), urls)
        assertEquals(meeting.description, t.text)
    }

    @Test
    fun theBridgeNotifiesOncePerProblem() {
        val ok = BridgeReport(0, 0, 0, 0, 0, 0, 0, 10)
        assertNull(bridgeAlert(ok))
        val held = bridgeAlert(ok.copy(heldDeletes = 5))!!
        assertEquals("연동 확인 필요", held.first)
        assertTrue("한쪽에서만 사라진 일정 5개" in held.second)
        assertEquals(held.third, bridgeAlert(ok.copy(heldDeletes = 5, atMillis = 99))!!.third)
        assertTrue(held.third != bridgeAlert(ok.copy(heldDeletes = 5, failed = 1))!!.third)
    }
}
