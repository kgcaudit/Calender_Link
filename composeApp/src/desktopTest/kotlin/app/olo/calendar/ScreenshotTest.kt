package app.olo.calendar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
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
import kotlinx.datetime.plus
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
        // The whole drawer at once, down to the sync cards.
        render("month-drawer-tall", dark = false, drawer = true, height = 5200)
        render("month-drawer-dark", dark = true, drawer = true)
        // 공휴일 ticked: 개천절 and 한글날 as rose bars in their cells.
        render("month-holidays", dark = false, shownExtra = "12=1")
        render("bridge-sheet", dark = false, bridge = "16")
        render("month-drawer-bridged", dark = false, drawer = true, bridged = true, height = 3600)
        // 일반 캘린더 open, Google folded by its heading, Samsung's holiday account (in 내 기기) by its tile.
        render("month-drawer-folded", dark = false, drawer = true, folded = "GENERAL=0,DEVICE/LOCAL/local.samsungholiday=1,SRC/GOOGLE=1", height = 3600)
        render("view-agenda-dark", dark = true, view = ViewMode.AGENDA)
        // Halfway through a swipe to November: the page follows the finger.
        render("month-swipe", dark = false, swipe = 0.45f)
        render("search", dark = false, search = "기전팀")
        // Settings: 기본 캘린더 grouped, 자동 naming its pick, the phone-only and not-allowed calendars marked.
        render("settings", dark = false, settingsOpen = true)
        // A long result list with the scroller held a third of the way down.
        render("search-scroller", dark = false, search = "영화", scrubAt = 0.4f)
        // Galaxy Z Fold unfolded, as in the user's screenshot (12-hour clock).
        render("month-wide", dark = false, width = 2000, height = 1500, density = 2.2f)
        for (v in listOf(ViewMode.YEAR, ViewMode.WEEK, ViewMode.DAY, ViewMode.AGENDA)) {
            render("view-${v.name.lowercase()}", dark = false, view = v)
            render("view-${v.name.lowercase()}-wide", dark = false, width = 2000, height = 1500, density = 2.2f, view = v)
        }
    }

    private fun render(
        name: String, dark: Boolean, stage: MonthStage = MonthStage.FULL, drawer: Boolean = false, search: String? = null, swipe: Float? = null, bridge: String? = null, bridged: Boolean = false, folded: String? = null, scrubAt: Float? = null, settingsOpen: Boolean = false, shownExtra: String? = null, width: Int = 1080, height: Int = 2340, density: Float = 2.625f,
        view: ViewMode = ViewMode.MONTH,
    ) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
          androidx.compose.runtime.CompositionLocalProvider(app.olo.calendar.ui.LocalUse24Hour provides false) {
            OloTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val scope = rememberCoroutineScope()
                    val c = remember {
                        val prefs = app.olo.calendar.model.MemoryPreferences()
                        if (bridged) { prefs.put("bridgeSamsungId", "16"); prefs.put("bridgeGoogleId", "6"); prefs.put("folded", "GENERAL=0") }
                        if (folded != null) prefs.put("folded", folded)
                        if (shownExtra != null) prefs.put("visibility", shownExtra)
                        CalendarController(SampleStore, scope, zone, prefs).also { it.setView(view) }
                    }
                    CalendarScreen(c, startStage = stage, startDrawerOpen = drawer, startSearch = search, startBridgeFor = bridge, startSettings = settingsOpen)
                }
            }
          }
        }
        var img = scene.render(0)
        for (i in 1..8) img = scene.render(i * 150_000_000L)
        if (swipe != null) {
            // Drag from the right edge of the grid towards the left and hold.
            val y = height * 0.35f
            val x0 = width * 0.9f
            var t = 9 * 150_000_000L
            scene.sendPointerEvent(PointerEventType.Press, Offset(x0, y), type = PointerType.Touch)
            for (k in 1..12) {
                t += 16_000_000L
                scene.sendPointerEvent(PointerEventType.Move, Offset(x0 - width * swipe * k / 12f, y), type = PointerType.Touch)
                img = scene.render(t)
            }
            img = scene.render(t + 16_000_000L)
        }
        if (scrubAt != null) {
            // Hold the right edge partway down: the scroller jumps and shows its bubble.
            var t = 9 * 150_000_000L
            val x = width - 20f
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, height * 0.2f), type = PointerType.Touch)
            for (k in 1..10) {
                t += 16_000_000L
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, height * (0.2f + (scrubAt - 0.2f) * k / 10f)), type = PointerType.Touch)
                img = scene.render(t)
            }
            for (k in 1..6) img = scene.render(t + k * 50_000_000L)
        }
        val out = File("build/screens/$name.png")
        out.parentFile.mkdirs()
        out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        assertTrue(out.length() > 0)
    }
}

private val zone = TimeZone.of("Asia/Seoul")

private object SampleStore : CalendarStore {
    // The user's phone: events live in Samsung Calendar (id 16), which has VISIBLE = 0 there.
    val cals = phoneCalendars().map { c ->
        when (c.id) {
            "16" -> c.copy(colorArgb = 0xFF7FB3E8.toInt())
            "7" -> c.copy(colorArgb = 0xFF33B679.toInt())
            "15" -> c.copy(colorArgb = 0xFF8E24AA.toInt())
            "10" -> c.copy(colorArgb = 0xFFF6BF26.toInt())
            else -> c
        }
    }
    private const val PINK = 0xFFE57399.toInt()

    private fun timed(id: String, title: String, day: LocalDate, h: Int, m: Int, h2: Int, m2: Int, loc: String = ""): Occurrence {
        val b = day.at(LocalTime(h, m), zone)
        val e = day.at(LocalTime(h2, m2), zone)
        val (f, l) = timedDays(b, e, zone)
        return Occurrence(id, "16", title, false, b, e, f, l, loc, 0xFF7FB3E8.toInt(), false)
    }

    private fun allDay(id: String, title: String, first: LocalDate, last: LocalDate = first, color: Int = 0xFF7FB3E8.toInt()): Occurrence {
        val b = Instant.fromEpochMilliseconds(AllDay.startMillis(first))
        val e = Instant.fromEpochMilliseconds(AllDay.endMillisExclusive(last))
        return Occurrence(id, "16", title, true, b, e, first, last, "", color, false)
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
        // Samsung's 공휴일 calendar (off unless ticked).
        allDay("hol1", "개천절", d(3)).copy(calendarId = "12"), allDay("hol2", "한글날", d(9)).copy(calendarId = "12"),
    )

    override suspend fun calendars() = cals
    override suspend fun occurrences(from: Instant, to: Instant) = occ.filter { it.end > from && it.begin < to }
    override suspend fun event(id: String): StoredEvent? = null
    override suspend fun search(query: String, from: Instant, to: Instant): List<Occurrence> =
        if (query == "영화") movies else occ.filter { it.title.contains(query) || it.location.contains(query) }

    // Four years of Friday films, as in the user's search: long enough for the scroller.
    private val movies = (0 until 140).map { k ->
        val day = LocalDate(2023, 1, 6).plus(k * 10, kotlinx.datetime.DateTimeUnit.DAY)
        timed("mv$k", "영화 ${k + 1}편", day, 20, 0, 22, 0)
    }
    override suspend fun save(draft: EventDraft) = "x"
    override suspend fun delete(id: String) = Unit
    override suspend fun setVisible(calendarId: String, visible: Boolean) = Unit
    override suspend fun syncStatus() = SyncStatus(accountsWithSyncOff = listOf("kgcaudit02@gmail.com", "sutest@gmail.com"), accountsNeedingAccess = listOf("hazakuqa@gmail.com"))
    override suspend fun setAccountSync(account: String, on: Boolean) = true
    override suspend fun bridgeItems(calendarId: String): List<app.olo.calendar.model.BridgeItem> =
        if (calendarId != "16") emptyList() else occ.map { o ->
            app.olo.calendar.model.BridgeItem(o.eventId, app.olo.calendar.model.BridgeFields(o.title, "", "", o.allDay, o.begin.toEpochMilliseconds(), o.end.toEpochMilliseconds(), "Asia/Seoul", "", emptyList(), emptyList(), emptyList()))
        }
    override suspend fun bridgeCopy(sourceId: String, toCalendarId: String): String? = null
    override suspend fun bridgeReplace(sourceId: String, targetId: String): String? = null
    override suspend fun bridgeDelete(id: String) = false
    override suspend fun bridgeDeleted(calendarId: String): List<app.olo.calendar.model.BridgeItem> = emptyList()
    override suspend fun bridgeRestore(deletedId: String, toCalendarId: String): String? = null
    override suspend fun setMasterSync(on: Boolean) = true
    override fun requestSync() = Unit
    override fun changes(): Flow<Unit> = emptyFlow()
    override suspend fun eventCounts(): Map<String, Int> = mapOf("16" to 412, "6" to 12, "1" to 3, "15" to 40)
    override suspend fun planMove(fromCalendarId: String): MovePlan = MovePlan(fromCalendarId, 0, 0, 0, 0)
    override suspend fun move(fromCalendarId: String, toCalendarId: String, deleteOriginals: Boolean, onProgress: (Int, Int) -> Unit): MoveResult =
        MoveResult(0, 0, 0)
}
