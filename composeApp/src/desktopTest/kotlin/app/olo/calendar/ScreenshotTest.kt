package app.olo.calendar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import app.olo.calendar.design.OloTheme
import app.olo.calendar.model.AllDay
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncKind
import app.olo.calendar.model.SyncStatus
import app.olo.calendar.model.at
import app.olo.calendar.model.timedDays
import app.olo.calendar.store.CalendarStore
import app.olo.calendar.ui.CalendarController
import app.olo.calendar.ui.CalendarScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the main screen with sample data to PNGs under build/screens, light
 * and dark, at a phone size. A look at layout without an emulator.
 */
class ScreenshotTest {
    @Test
    fun monthScreen() {
        for (dark in listOf(false, true)) {
            val scene = ImageComposeScene(width = 1080, height = 2340, density = Density(2.625f)) {
                OloTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        val scope = rememberCoroutineScope()
                        val c = remember { CalendarController(SampleStore, scope, zone) }
                        CalendarScreen(c)
                    }
                }
            }
            var img = scene.render(0)
            for (i in 1..6) img = scene.render(i * 100_000_000L)
            val out = File("build/screens/month-${if (dark) "dark" else "light"}.png")
            out.parentFile.mkdirs()
            out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
            assertTrue(out.length() > 0)
        }
    }
}

private val zone = TimeZone.of("Asia/Seoul")

private object SampleStore : CalendarStore {
    val cals = listOf(
        CalendarInfo("1", "개인", "me@gmail.com", "com.google", SyncKind.GOOGLE, 0xFFB95B3B.toInt(), true, true, true, false),
        CalendarInfo("2", "업무", "me@gmail.com", "com.google", SyncKind.GOOGLE, 0xFF4285F4.toInt(), true, true, false, false),
        CalendarInfo("3", "건강", "me@gmail.com", "com.google", SyncKind.GOOGLE, 0xFF0B8043.toInt(), true, true, false, false),
        CalendarInfo("4", "대한민국의 휴일", "me@gmail.com", "com.google", SyncKind.GOOGLE, 0xFF0B8043.toInt(), false, true, false, true),
        CalendarInfo("5", "내 캘린더", "My calendar", "LOCAL", SyncKind.DEVICE, 0xFF8E24AA.toInt(), true, true, false, false),
    )

    private fun timed(id: String, cal: String, title: String, day: LocalDate, h: Int, m: Int, h2: Int, m2: Int, loc: String = ""): Occurrence {
        val b = day.at(LocalTime(h, m), zone)
        val e = day.at(LocalTime(h2, m2), zone)
        val (f, l) = timedDays(b, e, zone)
        return Occurrence(id, cal, title, false, b, e, f, l, loc, cals.first { it.id == cal }.colorArgb, false)
    }

    private fun allDay(id: String, cal: String, title: String, first: LocalDate, last: LocalDate = first): Occurrence {
        val b = Instant.fromEpochMilliseconds(AllDay.startMillis(first))
        val e = Instant.fromEpochMilliseconds(AllDay.endMillisExclusive(last))
        return Occurrence(id, cal, title, true, b, e, first, last, "", cals.first { it.id == cal }.colorArgb, false)
    }

    private val d = { day: Int -> LocalDate(2026, 10, day) }
    val occ = listOf(
        allDay("h1", "4", "개천절", d(3)),
        allDay("h2", "4", "한글날", d(9)),
        timed("a", "2", "주간 회의", d(5), 9, 30, 10, 30, "본사 7층"),
        timed("b", "3", "건강검진", d(12), 8, 0, 10, 0, "강남 세브란스"),
        allDay("c", "2", "현장 출장", d(15), d(17)),
        timed("d", "2", "감사 보고서 마감", d(16), 14, 0, 15, 0),
        timed("e", "1", "저녁 약속", d(16), 19, 0, 21, 0, "을지로"),
        timed("f", "3", "러닝", d(16), 7, 0, 7, 40),
        timed("g", "1", "가족 모임", d(18), 12, 0, 14, 0),
        timed("h", "5", "기기 전용 메모", d(21), 10, 0, 11, 0),
        timed("i", "2", "감사 회의", d(28), 15, 0, 16, 0),
        allDay("j", "1", "생일", d(31)),
    )

    override suspend fun calendars() = cals
    override suspend fun occurrences(from: Instant, to: Instant) = occ.filter { it.end > from && it.begin < to }
    override suspend fun event(id: String): StoredEvent? = null
    override suspend fun save(draft: EventDraft) = "x"
    override suspend fun delete(id: String) = Unit
    override suspend fun setVisible(calendarId: String, visible: Boolean) = Unit
    override suspend fun syncStatus() = SyncStatus(pendingUploads = 1)
    override fun requestSync() = Unit
    override fun changes(): Flow<Unit> = emptyFlow()
}
