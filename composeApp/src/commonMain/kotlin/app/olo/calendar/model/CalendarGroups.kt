package app.olo.calendar.model

/*
 * The drawer's calendar tree (docs/05 §3): category → account → calendar, with
 * OLO's own show/hide choices. OLO never writes the shared VISIBLE column, so
 * Samsung's and Google's apps keep their own settings.
 */

/**
 * Where a general calendar comes from (docs/07 §11): the drawer's "일반 캘린더"
 * is split by source, then by account; holiday and subscribed calendars stay
 * inside the account they belong to.
 */
enum class CalendarCategory(val label: String) {
    DEVICE("내 기기"),
    SAMSUNG("Samsung"),
    GOOGLE("Google"),
    OTHER("기타"),
}

/** How an account's events travel; shown as a chip beside the account. */
enum class AccountStatus(val label: String) {
    SYNCING("동기화 중"),
    SYNC_OFF("동기화 꺼짐"),
    /** OLO has not been allowed to see this account yet, so its sync state is unknown. */
    NEEDS_ACCESS("확인 필요"),
    DEVICE_ONLY("이 기기만"),
    /** Left out of "지금 동기화" by the person; the phone's automatic sync goes on. */
    EXCLUDED("제외됨"),
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
// Days off only: 법정기념일 (국군의 날, …) and 절기 (입춘, …) are not red days.
private val holidayName = Regex("(휴일|holiday)", RegexOption.IGNORE_CASE)

/**
 * Public-holiday calendars: Google's #holiday calendars, Samsung's "공휴일"
 * and other read-only "…휴일" calendars. Their days turn red and their names
 * show as rose bars (docs/06 §7). Samsung's 법정기념일 and 절기와 세시풍속 sit in
 * the same local account but are ordinary read-only calendars (0.9.5).
 */
fun looksLikeHolidayCalendar(ownerOrId: String?, accountName: String, name: String, writable: Boolean): Boolean =
    isGoogleHolidayCalendarId(ownerOrId) ||
        (!writable && holidayName.containsMatchIn(name))

/** Calendars Samsung puts on every phone (holidays, 기념일, 절기, contacts' days): 내 기기, off until chosen. */
val CalendarInfo.isSamsungBuiltIn: Boolean
    get() = accountName == SAMSUNG_HOLIDAY_ACCOUNT || accountName == SAMSUNG_BIRTHDAY_ACCOUNT

fun CalendarInfo.category(): CalendarCategory = when {
    // Samsung's built-in calendars live on the phone, as Samsung Calendar's "내 폰" shows them.
    isSamsungBuiltIn -> CalendarCategory.DEVICE
    kind == SyncKind.SAMSUNG -> CalendarCategory.SAMSUNG
    kind == SyncKind.DEVICE -> CalendarCategory.DEVICE
    kind == SyncKind.GOOGLE -> CalendarCategory.GOOGLE
    else -> CalendarCategory.OTHER
}

/**
 * OLO's starting choice before the user touches a box: everything on except
 * holiday copies (OLO draws its own Korean holidays) and Samsung's built-in
 * calendars, which Samsung Calendar also leaves off.
 */
fun CalendarInfo.shownByDefault(): Boolean = !isHoliday && !isSamsungBuiltIn

fun accountTitle(c: CalendarInfo): String = when (c.accountName) {
    SAMSUNG_HOLIDAY_ACCOUNT -> "공휴일·기념일"
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

/** How an account is named in [AppSettings.syncExcluded] and in sync requests: "type/name". */
fun syncAccountKey(accountType: String, accountName: String): String = "$accountType/$accountName"

val CalendarInfo.syncKey: String get() = syncAccountKey(accountType, accountName)

/** Accounts a sync adapter carries (what "지금 동기화" asks): not phone-only or Samsung's built-ins. */
val CalendarInfo.isSyncedAccount: Boolean
    get() = !isSamsungBuiltIn && kind in setOf(SyncKind.GOOGLE, SyncKind.EXCHANGE, SyncKind.ICLOUD, SyncKind.SAMSUNG)

/** The synced accounts, one calendar each (primary first), for the "지금 동기화 대상" list. */
fun syncAccounts(calendars: List<CalendarInfo>): List<CalendarInfo> =
    calendars.filter { it.isSyncedAccount }.sortedBy { !it.isPrimary }.distinctBy { it.syncKey }

fun accountStatus(
    cals: List<CalendarInfo>,
    accountsWithSyncOff: Collection<String>,
    needsAccess: Collection<String> = emptyList(),
    excluded: Collection<String> = emptyList(),
): AccountStatus {
    val c = cals.first()
    return when {
        c.kind == SyncKind.GOOGLE || c.kind == SyncKind.EXCHANGE || c.kind == SyncKind.ICLOUD ->
            when {
                // Off on the phone wins: then nothing syncs it, excluded or not.
                c.accountName in accountsWithSyncOff && c.accountName !in needsAccess -> AccountStatus.SYNC_OFF
                c.syncKey in excluded -> AccountStatus.EXCLUDED
                c.accountName in needsAccess -> AccountStatus.NEEDS_ACCESS
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
    /**
     * With the bridge on: the main section (the joined pair and the rest of the
     * Google main account) is drawn on its own above the tree, so those
     * calendars leave it, and other personal calendars become "다른 캘린더".
     */
    mainSection: Set<String> = emptySet(),
    /** [AppSettings.syncExcluded]: those accounts read "제외됨". */
    excluded: Collection<String> = emptySet(),
): List<CategoryGroup> {
    val n = { c: CalendarInfo -> counts[c.id] ?: 0 }
    val calendarOrder = compareBy<CalendarInfo>(
        { !it.writable && n(it) == 0 }, { !it.isPrimary }, { -n(it) }, { it.name.lowercase() },
    )
    return calendars.filter { it.id !in mainSection }
        .groupBy { it.category() }
        .toSortedMap(compareBy { it.ordinal }).map { (cat, inCat) ->
        val accounts = inCat.groupBy { it.accountType to it.accountName }.map { (k, cals) ->
            val sorted = cals.sortedWith(calendarOrder)
            AccountGroup(
                key = "${cat.name}/${k.first}/${k.second}",
                title = accountTitle(sorted.first()),
                subtitle = kindTitle(sorted.first()),
                status = accountStatus(sorted, accountsWithSyncOff, needsAccess, excluded),
                calendars = sorted,
            )
        }.sortedWith(
            // The person's own accounts before Samsung's built-in ones (내 캘린더 first, as in Samsung Calendar).
            compareBy<AccountGroup>({ g -> g.calendars.first().isSamsungBuiltIn }, { g -> g.calendars.none { it.isPrimary } }, { g -> -g.calendars.sumOf(n) }, { it.title.lowercase() }),
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
