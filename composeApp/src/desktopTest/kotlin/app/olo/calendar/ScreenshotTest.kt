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
import app.olo.calendar.model.MovePlan
import app.olo.calendar.model.MoveResult
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncKind
import app.olo.calendar.model.ViewMode
import app.olo.calendar.model.SyncStatus
import app.olo.calendar.model.at
import app.olo.calendar.model.timedDays
import app.olo.calendar.store.CalendarStore
import app.olo.calendar.ui.CalendarController
import app.olo.calendar.ui.CalendarScreen
import app.olo.calendar.ui.MonthStage
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
        render("month-light", dark = false)
        render("month-dark", dark = true)
        render("month-stage2", dark = false, stage = MonthStage.LINES)
        render("month-stage3", dark = false, stage = MonthStage.WEEK)
        render("month-drawer", dark = false, drawer = true)
        render("search", dark = false, search = "기전팀")
        // Galaxy Z Fold unfolded, as in the user's screenshot (12-hour clock).
        render("month-wide", dark = false, width = 2000, height = 1500, density = 2.2f)
        for (v in listOf(ViewMode.YEAR, ViewMode.WEEK, ViewMode.DAY, ViewMode.AGENDA)) {
            render("view-${v.name.lowercase()}", dark = false, view = v)
            render("view-${v.name.lowercase()}-wide", dark = false, width = 2000, height = 1500, density = 2.2f, view = v)
        }
    }

    private fun render(
        name: String, dark: Boolean, stage: MonthStage = MonthStage.FULL, drawer: Boolean = false, search: String? = null, width: Int = 1080, height: Int = 2340, density: Float = 2.625f,
        view: ViewMode = ViewMode.MONTH,
    ) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
          androidx.compose.runtime.CompositionLocalProvider(app.olo.calendar.ui.LocalUse24Hour provides false) {
            OloTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val scope = rememberCoroutineScope()
                    val c = remember { CalendarController(SampleStore, scope, zone).also { it.setView(view) } }
                    CalendarScreen(c, startStage = stage, startDrawerOpen = drawer, startSearch = search)
                }
            }
          }
        }
        var img = scene.render(0)
        for (i in 1..8) img = scene.render(i * 150_000_000L)
        val out = File("build/screens/$name.png")
        out.parentFile.mkdirs()
        out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        assertTrue(out.length() > 0)
    }
}

private val zone = TimeZone.of("Asia/Seoul")

private object SampleStore : CalendarStore {
    val cals = listOf(
        CalendarInfo("1", "kgcaudit02@gmail.com", "kgcaudit02@gmail.com", "com.google", SyncKind.GOOGLE, 0xFF039BE5.toInt(), true, true, true, false),
        CalendarInfo("2", "Samsung Calendar", "Samsung account", "com.osp.app.signin", SyncKind.SAMSUNG, 0xFF7FB3E8.toInt(), true, true, false, false),
    )
    private const val PINK = 0xFFE57399.toInt()

    private fun timed(id: String, title: String, day: LocalDate, h: Int, m: Int, h2: Int, m2: Int, loc: String = ""): Occurrence {
        val b = day.at(LocalTime(h, m), zone)
        val e = day.at(LocalTime(h2, m2), zone)
        val (f, l) = timedDays(b, e, zone)
        return Occurrence(id, "2", title, false, b, e, f, l, loc, cals[1].colorArgb, false)
    }

    private fun allDay(id: String, title: String, first: LocalDate, last: LocalDate = first, color: Int = cals[1].colorArgb): Occurrence {
        val b = Instant.fromEpochMilliseconds(AllDay.startMillis(first))
        val e = Instant.fromEpochMilliseconds(AllDay.endMillisExclusive(last))
        return Occurrence(id, "2", title, true, b, e, first, last, "", color, false)
    }

    private val d = { day: Int -> LocalDate(2026, 10, day) }
    private val sep = { day: Int -> LocalDate(2026, 9, day) }
    // October 2026 as it appears in Samsung Calendar on the user's phone.
    val occ = listOf(
        allDay("s1", "공동연차", sep(28)), timed("s2", "텐퍼센트 커피 쿠폰 지급", sep(29), 9, 0, 10, 0), timed("s3", "기전팀 보고 실장 리뷰", sep(30), 15, 0, 16, 0),
        allDay("a", "컬러링 신규 등록일", d(1)), timed("b", "지역화폐 충전 대기", d(1), 9, 0, 9, 30),
        allDay("c", "서초3동 복합행정타운", d(2)), allDay("c2", "추경 의회", d(2)), allDay("e", "장인 생신", d(3)),
        allDay("f", "엔화(1천 × 3회 환전)", d(4), d(6)), allDay("g", "용인자연휴양림 신청", d(5), d(9)),
        timed("h", "아이디헤어", d(5), 11, 0, 12, 0), timed("h2", "점심 약속", d(5), 12, 0, 13, 0), timed("h3", "은행 방문", d(5), 14, 0, 14, 30),
        allDay("i", "현정탄신일", d(8), color = PINK), timed("j", "삼진일렉스", d(8), 10, 0, 11, 0),
        allDay("k", "네이버 멤버쉽 갱신일", d(10)),
        timed("l", "시립대 동문회", d(12), 19, 0, 21, 0), timed("m", "ZARA 의류 찾기", d(13), 18, 0, 18, 30), allDay("n", "창립기념일", d(14)),
        allDay("o", "Mini 쿠퍼 5 Door", d(16)), timed("p", "영화 스트리트 파이터", d(16), 20, 5, 22, 15, "CGV 용산"),
        timed("q", "리트와 메트 팝업스토어", d(17), 13, 0, 15, 0), timed("r", "현정 사촌 남동생 결혼", d(18), 12, 0, 14, 0),
        timed("t", "기전팀 보고", d(19), 15, 0, 16, 0), allDay("u", "AX Boot CAMP", d(20), d(21)), timed("v", "지주 책걸이", d(20), 18, 0, 20, 0),
        timed("w", "심폐소생술 교육", d(22), 10, 0, 12, 0), timed("x", "현정 저녁 모임", d(22), 19, 0, 21, 0),
        allDay("y", "세금계산서 발행", d(23)), allDay("z", "연차휴무", d(23)),
        allDay("aa", "대한항공 엔진정비공장 정기감사", d(26), d(30)), allDay("ab", "지도 감사", d(26), d(30)),
        allDay("ac", "hj 일본 여행", d(31), LocalDate(2026, 11, 1)),
    )

    override suspend fun calendars() = cals
    override suspend fun occurrences(from: Instant, to: Instant) = occ.filter { it.end > from && it.begin < to }
    override suspend fun event(id: String): StoredEvent? = null
    override suspend fun search(query: String, from: Instant, to: Instant): List<Occurrence> = occ.filter { it.title.contains(query) || it.location.contains(query) }
    override suspend fun save(draft: EventDraft) = "x"
    override suspend fun delete(id: String) = Unit
    override suspend fun setVisible(calendarId: String, visible: Boolean) = Unit
    override suspend fun syncStatus() = SyncStatus(accountsWithSyncOff = listOf("kgcaudit02@gmail.com"))
    override fun requestSync() = Unit
    override fun changes(): Flow<Unit> = emptyFlow()
    override suspend fun eventCounts(): Map<String, Int> = mapOf("2" to 412)
    override suspend fun planMove(fromCalendarId: String): MovePlan = MovePlan(fromCalendarId, 0, 0, 0, 0)
    override suspend fun move(fromCalendarId: String, toCalendarId: String, deleteOriginals: Boolean, onProgress: (Int, Int) -> Unit): MoveResult =
        MoveResult(0, 0, 0)
}
