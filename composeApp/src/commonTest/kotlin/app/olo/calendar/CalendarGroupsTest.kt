package app.olo.calendar

import app.olo.calendar.model.AccountStatus
import app.olo.calendar.model.AppSettings
import app.olo.calendar.model.CalendarCategory
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.MemoryPreferences
import app.olo.calendar.model.SyncKind
import app.olo.calendar.model.TriState
import app.olo.calendar.model.buildCalendarTree
import app.olo.calendar.model.classifyAndroidAccount
import app.olo.calendar.model.decodeVisibility
import app.olo.calendar.model.encodeVisibility
import app.olo.calendar.model.isShown
import app.olo.calendar.model.loadSettings
import app.olo.calendar.model.looksLikeHolidayCalendar
import app.olo.calendar.model.saveSettings
import app.olo.calendar.model.triState
import app.olo.calendar.design.OloHue
import app.olo.calendar.design.oloHueFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val GH = "#holiday@group.v.calendar.google.com"

private fun cal(id: String, name: String, account: String, type: String, writable: Boolean, primary: Boolean = false, owner: String = account, visible: Boolean = false) =
    CalendarInfo(
        id, name, account, type, classifyAndroidAccount(type), 0xFF039BE5.toInt(), writable, visible, primary,
        looksLikeHolidayCalendar(owner, account, name, writable),
    )

/** The calendars on the user's phone (drawer screenshot, 2026-10-16), most of them VISIBLE = 0. */
fun phoneCalendars(): List<CalendarInfo> = listOf(
    cal("1", "hazakuqa@gmail.com", "hazakuqa@gmail.com", "com.google", true, primary = true),
    cal("2", "Holidays in South Korea", "hazakuqa@gmail.com", "com.google", false, owner = "en.south_korea$GH"),
    cal("3", "대한민국의 휴일", "hazakuqa@gmail.com", "com.google", false, owner = "ko.south_korea$GH"),
    cal("4", "kgcaudit02@gmail.com", "kgcaudit02@gmail.com", "com.google", true, primary = true),
    cal("5", "대한민국의 휴일", "kgcaudit02@gmail.com", "com.google", false, owner = "ko.south_korea$GH"),
    cal("6", "sutest@gmail.com", "sutest@gmail.com", "com.google", true, primary = true, visible = true),
    cal("7", "가족", "sutest@gmail.com", "com.google", true, owner = "family01@group.calendar.google.com"),
    cal("8", "대한민국의 휴일", "sutest@gmail.com", "com.google", false, owner = "ko.south_korea$GH"),
    cal("9", "한국 정식발매 게임 목록", "sutest@gmail.com", "com.google", false, owner = "games@group.calendar.google.com"),
    cal("10", "My calendar", "My calendar", "LOCAL", true),
    cal("11", "연락처에 저장된 중요한 날", "local.samsungbirthday", "LOCAL", false),
    cal("12", "공휴일", "local.samsungholiday", "LOCAL", false),
    cal("13", "법정기념일", "local.samsungholiday", "LOCAL", false),
    cal("14", "절기와 세시풍속", "local.samsungholiday", "LOCAL", false),
    cal("15", "일정", "sangsu@kolon.com", "com.google.android.gm.exchange", true, primary = true),
    cal("16", "Samsung Calendar", "sutest@gmail.com", "com.osp.app.signin", true),
)

class CalendarGroupsTest {
    private val cals = phoneCalendars()
    private val counts = mapOf("16" to 412, "6" to 12, "1" to 3, "15" to 40)
    private val tree = buildCalendarTree(cals, counts, listOf("kgcaudit02@gmail.com", "sutest@gmail.com"))

    @Test
    fun groupedBySourceThenAccount() {
        assertEquals(listOf(CalendarCategory.DEVICE, CalendarCategory.SAMSUNG, CalendarCategory.GOOGLE, CalendarCategory.OTHER), tree.map { it.category })
        val ids = { c: CalendarCategory -> tree.first { it.category == c }.calendars.map { it.id }.toSet() }
        // The phone's own calendar plus Samsung's built-in ones, as Samsung Calendar's "내 폰" shows them.
        assertEquals(setOf("10", "11", "12", "13", "14"), ids(CalendarCategory.DEVICE))
        assertEquals(setOf("16"), ids(CalendarCategory.SAMSUNG))
        // Google holidays and subscriptions stay inside their accounts.
        assertEquals(setOf("1", "2", "3", "4", "5", "6", "7", "8", "9"), ids(CalendarCategory.GOOGLE))
        assertEquals(setOf("15"), ids(CalendarCategory.OTHER))
    }

    @Test
    fun accountsSortedAndLabelled() {
        val google = tree.first { it.category == CalendarCategory.GOOGLE }.accounts
        // Primary accounts first, then by events held: sutest (12) before hazakuqa (3) before kgcaudit02 (0).
        assertEquals(listOf("sutest@gmail.com", "hazakuqa@gmail.com", "kgcaudit02@gmail.com"), google.map { it.title })
        assertEquals(AccountStatus.SYNC_OFF, google.first().status)
        assertEquals(AccountStatus.SYNCING, google.first { it.title == "hazakuqa@gmail.com" }.status)
        val samsung = tree.first { it.category == CalendarCategory.SAMSUNG }.accounts
        assertEquals(AccountStatus.DEVICE_ONLY, samsung.first { it.calendars.any { c -> c.id == "16" } }.status)
        assertEquals(listOf("이 기기", "공휴일·기념일", "연락처"), tree.first { it.category == CalendarCategory.DEVICE }.accounts.map { it.title }.sortedBy { listOf("이 기기", "공휴일·기념일", "연락처").indexOf(it) })
    }

    @Test
    fun oloVisibilityIgnoresTheSharedColumn() {
        val st = AppSettings()
        // VISIBLE = 0 on the phone, yet OLO shows them; holiday copies stay off (OLO draws its own).
        assertTrue(st.isShown(cals.first { it.id == "16" }))
        assertTrue(st.isShown(cals.first { it.id == "4" }))
        assertFalse(st.isShown(cals.first { it.id == "3" }))
        val chosen = st.copy(visibility = mapOf("16" to false, "3" to true))
        assertFalse(chosen.isShown(cals.first { it.id == "16" }))
        assertTrue(chosen.isShown(cals.first { it.id == "3" }))
    }

    @Test
    fun bluesAreSkyNotGrey() {
        // Samsung's default and Google's default blue used to fall to slate grey.
        assertEquals(OloHue.SKY, oloHueFor(0xFF7FB3E8.toInt()))
        assertEquals(OloHue.SKY, oloHueFor(0xFF039BE5.toInt()))
        assertEquals(OloHue.DOCUMENT, oloHueFor(0xFF6E7F94.toInt()))   // a greyed blue stays slate
        assertEquals(OloHue.OTHER, oloHueFor(0xFF888888.toInt()))
        assertEquals(OloHue.FOLDER, oloHueFor(0xFFB95B3B.toInt()))
    }

    @Test
    fun anAccountOloCannotSeeIsUnknownNotOff() {
        val t = buildCalendarTree(cals, counts, listOf("kgcaudit02@gmail.com"), needsAccess = listOf("hazakuqa@gmail.com"))
        val google = t.first { it.category == CalendarCategory.GOOGLE }.accounts
        assertEquals(AccountStatus.NEEDS_ACCESS, google.first { it.title == "hazakuqa@gmail.com" }.status)
        assertEquals(AccountStatus.SYNC_OFF, google.first { it.title == "kgcaudit02@gmail.com" }.status)
        assertEquals(AccountStatus.SYNCING, google.first { it.title == "sutest@gmail.com" }.status)
    }

    @Test
    fun withTheBridgeTheLinkedCalendarsLeaveTheGeneralTree() {
        // Samsung Calendar (16) ⇄ sutest (6), plus sutest's 가족 (7), drawn as the main section.
        val t = buildCalendarTree(cals, counts, emptyList(), mainSection = setOf("16", "6", "7"))
        // sutest's holiday and game list stay in the Google group; the pair and 가족 are gone from it.
        assertEquals(setOf("1", "2", "3", "4", "5", "8", "9"), t.first { it.category == CalendarCategory.GOOGLE }.calendars.map { it.id }.toSet())
        assertTrue(t.none { it.category == CalendarCategory.SAMSUNG })
        assertEquals(setOf("10", "11", "12", "13", "14"), t.first { it.category == CalendarCategory.DEVICE }.calendars.map { it.id }.toSet())
        assertTrue(t.flatMap { it.calendars }.none { it.id in setOf("16", "6", "7") })
    }

    @Test
    fun onlyDaysOffAreHolidayCalendars() {
        val byId = cals.associateBy { it.id }
        // 공휴일 reddens days; 법정기념일 and 절기 are ordinary read-only calendars (국군의 날 is not a day off).
        assertTrue(byId.getValue("12").isHoliday)
        assertFalse(byId.getValue("13").isHoliday)
        assertFalse(byId.getValue("14").isHoliday)
        // Samsung's built-in calendars start off, as in Samsung Calendar; ticking one shows it.
        val st = AppSettings()
        listOf("11", "12", "13", "14").forEach { assertFalse(st.isShown(byId.getValue(it))) }
        assertTrue(st.copy(visibility = mapOf("13" to true)).isShown(byId.getValue("13")))
    }

    @Test
    fun triStates() {
        assertEquals(TriState.ALL, triState(listOf("a", "b")) { true })
        assertEquals(TriState.SOME, triState(listOf("a", "b")) { it == "a" })
        assertEquals(TriState.NONE, triState(listOf("a", "b")) { false })
        assertEquals(TriState.NONE, triState(emptyList()) { true })
    }

    @Test
    fun settingsRoundTrip() {
        val p = MemoryPreferences()
        val s = AppSettings(monthStage = 2, visibility = mapOf("16" to false, "3" to true), folded = mapOf("HOLIDAYS" to false), verticalPagingWide = true, hues = mapOf("16" to "SKY", "7" to "IMAGE"))
        p.saveSettings(s)
        assertEquals(s.copy(lastView = s.lastView), p.loadSettings())
        assertEquals(mapOf("a" to true, "b" to false), decodeVisibility(encodeVisibility(mapOf("a" to true, "b" to false))))
        assertEquals(emptyMap(), decodeVisibility(null))
    }

    private fun ui(settings: app.olo.calendar.model.AppSettings = app.olo.calendar.model.AppSettings(), needsAccess: List<String> = listOf("hazakuqa@gmail.com", "kgcaudit02@gmail.com")) =
        app.olo.calendar.ui.CalendarUiState(
            month = app.olo.calendar.model.YearMonth(2026, 10), selected = kotlinx.datetime.LocalDate(2026, 10, 17), today = kotlinx.datetime.LocalDate(2026, 10, 17),
            calendars = cals, settings = settings, eventCounts = counts,
            sync = app.olo.calendar.model.SyncStatus(accountsNeedingAccess = needsAccess),
        )

    @Test
    fun autoPicksGoogleMainBeforeLinking() {
        // Not hazakuqa (first by name, and OLO cannot see it): the account Samsung Calendar signs in with.
        assertEquals("6", ui().autoCalendar?.id)
        assertEquals("6", ui().googleMainGuess?.id)
    }

    @Test
    fun autoPicksSamsungCalendarWhenLinked() {
        val st = app.olo.calendar.model.AppSettings(bridgeSamsungId = "16", bridgeGoogleId = "6")
        assertEquals("16", ui(st).autoCalendar?.id)
        assertEquals("16", ui(st).defaultCalendar?.id)
    }

    @Test
    fun chosenDefaultWins() {
        val st = app.olo.calendar.model.AppSettings(bridgeSamsungId = "16", bridgeGoogleId = "6", defaultCalendarId = "7")
        assertEquals("7", ui(st).defaultCalendar?.id)
        // A calendar that can no longer be written falls back to 자동.
        assertEquals("16", ui(st.copy(defaultCalendarId = "8")).defaultCalendar?.id)
    }
}
