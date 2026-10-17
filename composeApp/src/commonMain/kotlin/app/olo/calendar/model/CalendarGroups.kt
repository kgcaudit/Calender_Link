package app.olo.calendar.model

/*
 * The drawer's calendar tree (docs/05 §3): category → account → calendar, with
 * OLO's own show/hide choices. OLO never writes the shared VISIBLE column, so
 * Samsung's and Google's apps keep their own settings.
 */

/** Top level: what a calendar is for, not where it comes from. */
enum class CalendarCategory(val label: String) {
    MINE("내 캘린더"),
    WORK("업무"),
    HOLIDAYS("공휴일·기념일"),
    SUBSCRIBED("구독"),
}

/** How an account's events travel; shown as a chip beside the account. */
enum class AccountStatus(val label: String) {
    SYNCING("동기화 중"),
    SYNC_OFF("동기화 꺼짐"),
    /** OLO has not been allowed to see this account yet, so its sync state is unknown. */
    NEEDS_ACCESS("확인 필요"),
    DEVICE_ONLY("이 기기만"),
    READ_ONLY("읽기 전용"),
}

data class AccountGroup(
    /** Stable key for collapse state: "category/accountType/accountName". */
    val key: String,
    val title: String,
    val subtitle: String,
    val status: AccountStatus,
    val calendars: List<CalendarInfo>,
)

data class CategoryGroup(val category: CalendarCategory, val accounts: List<AccountGroup>) {
    val key: String get() = category.name
    val calendars: List<CalendarInfo> get() = accounts.flatMap { it.calendars }
}

enum class TriState { ALL, SOME, NONE }

fun triState(ids: Collection<String>, shown: (String) -> Boolean): TriState {
    if (ids.isEmpty()) return TriState.NONE
    val n = ids.count(shown)
    return when (n) { ids.size -> TriState.ALL; 0 -> TriState.NONE; else -> TriState.SOME }
}

private const val SAMSUNG_HOLIDAY_ACCOUNT = "local.samsungholiday"
private const val SAMSUNG_BIRTHDAY_ACCOUNT = "local.samsungbirthday"
private val holidayName = Regex("(휴일|공휴일|법정기념일|절기|세시풍속|holiday)", RegexOption.IGNORE_CASE)

/**
 * Public-holiday calendars: Google's #holiday calendars and Samsung's local
 * holiday account. Their events become red day names rather than event bars.
 */
fun looksLikeHolidayCalendar(ownerOrId: String?, accountName: String, name: String, writable: Boolean): Boolean =
    isGoogleHolidayCalendarId(ownerOrId) ||
        accountName == SAMSUNG_HOLIDAY_ACCOUNT ||
        (!writable && holidayName.containsMatchIn(name))

fun CalendarInfo.category(): CalendarCategory = when {
    isHoliday || accountName == SAMSUNG_BIRTHDAY_ACCOUNT -> CalendarCategory.HOLIDAYS
    kind == SyncKind.EXCHANGE -> CalendarCategory.WORK
    !writable || kind == SyncKind.SUBSCRIBED -> CalendarCategory.SUBSCRIBED
    else -> CalendarCategory.MINE
}

/** OLO's starting choice before the user touches a box: everything on except holiday copies (OLO draws its own Korean holidays). */
fun CalendarInfo.shownByDefault(): Boolean = !isHoliday

fun accountTitle(c: CalendarInfo): String = when (c.accountName) {
    SAMSUNG_HOLIDAY_ACCOUNT -> "삼성 공휴일"
    SAMSUNG_BIRTHDAY_ACCOUNT -> "연락처"
    else -> when (c.kind) {
        SyncKind.DEVICE -> "이 기기"
        else -> c.accountName
    }
}

private fun kindTitle(c: CalendarInfo): String = when {
    c.accountName == SAMSUNG_HOLIDAY_ACCOUNT || c.accountName == SAMSUNG_BIRTHDAY_ACCOUNT -> "Samsung"
    else -> when (c.kind) {
        SyncKind.GOOGLE -> "Google"
        SyncKind.DEVICE -> "휴대폰"
        SyncKind.SAMSUNG -> "삼성 계정"
        SyncKind.ICLOUD -> "iCloud"
        SyncKind.EXCHANGE -> "Exchange"
        SyncKind.SUBSCRIBED -> "구독"
        SyncKind.OTHER -> "기타"
    }
}

fun accountStatus(cals: List<CalendarInfo>, accountsWithSyncOff: Collection<String>, needsAccess: Collection<String> = emptyList()): AccountStatus {
    val c = cals.first()
    return when {
        c.kind == SyncKind.GOOGLE || c.kind == SyncKind.EXCHANGE || c.kind == SyncKind.ICLOUD ->
            when (c.accountName) {
                in needsAccess -> AccountStatus.NEEDS_ACCESS
                in accountsWithSyncOff -> AccountStatus.SYNC_OFF
                else -> AccountStatus.SYNCING
            }
        cals.none { it.writable } -> AccountStatus.READ_ONLY
        else -> AccountStatus.DEVICE_ONLY
    }
}

/**
 * The tree, sorted (docs/05 §3.2): categories in enum order; accounts primary
 * first, then by events held, then by name; calendars primary first, then by
 * events, then by name. Empty read-only calendars sink to the bottom.
 */
fun buildCalendarTree(
    calendars: List<CalendarInfo>,
    counts: Map<String, Int>,
    accountsWithSyncOff: Collection<String>,
    needsAccess: Collection<String> = emptyList(),
): List<CategoryGroup> {
    val n = { c: CalendarInfo -> counts[c.id] ?: 0 }
    val calendarOrder = compareBy<CalendarInfo>(
        { !it.writable && n(it) == 0 }, { !it.isPrimary }, { -n(it) }, { it.name.lowercase() },
    )
    return calendars.groupBy { it.category() }.toSortedMap(compareBy { it.ordinal }).map { (cat, inCat) ->
        val accounts = inCat.groupBy { it.accountType to it.accountName }.map { (k, cals) ->
            val sorted = cals.sortedWith(calendarOrder)
            AccountGroup(
                key = "${cat.name}/${k.first}/${k.second}",
                title = accountTitle(sorted.first()),
                subtitle = kindTitle(sorted.first()),
                status = accountStatus(sorted, accountsWithSyncOff, needsAccess),
                calendars = sorted,
            )
        }.sortedWith(
            compareBy<AccountGroup>({ g -> g.calendars.none { it.isPrimary } }, { g -> -g.calendars.sumOf(n) }, { it.title.lowercase() }),
        )
        CategoryGroup(cat, accounts)
    }
}

fun encodeMap(map: Map<String, String>): String = map.entries.joinToString(",") { "${it.key}=${it.value}" }

fun decodeMap(s: String?): Map<String, String> =
    s.orEmpty().split(',').mapNotNull { part ->
        val i = part.lastIndexOf('=')
        if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
    }.toMap()

/** Stored as "id=1,id=0" (calendar ids are numbers on Android, UUIDs on iOS: no '=' or ','). */
fun encodeVisibility(map: Map<String, Boolean>): String = map.entries.joinToString(",") { "${it.key}=${if (it.value) 1 else 0}" }

fun decodeVisibility(s: String?): Map<String, Boolean> =
    s.orEmpty().split(',').mapNotNull { part ->
        val i = part.lastIndexOf('=')
        if (i <= 0) null else part.substring(0, i) to (part.substring(i + 1) == "1")
    }.toMap()
