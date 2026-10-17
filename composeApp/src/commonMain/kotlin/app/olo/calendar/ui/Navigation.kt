package app.olo.calendar.ui

import androidx.compose.foundation.layout.fillMaxSize
import app.olo.calendar.model.SyncKind
import app.olo.calendar.design.color
import app.olo.calendar.design.OloTone
import app.olo.calendar.design.luminanceIsDarkPublic
import app.olo.calendar.design.OloHue
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.olo.calendar.design.OloIcons
import app.olo.calendar.design.OloLayout
import app.olo.calendar.design.OloMotion
import app.olo.calendar.design.calendarColor
import app.olo.calendar.model.AccountGroup
import app.olo.calendar.model.AccountStatus
import app.olo.calendar.model.CalendarCategory
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.CategoryGroup
import app.olo.calendar.model.TriState
import app.olo.calendar.model.ViewMode
import app.olo.calendar.model.buildCalendarTree
import app.olo.calendar.model.triState

class NavActions(
    val state: CalendarUiState,
    val onView: (ViewMode) -> Unit,
    val onShown: (Collection<String>, Boolean) -> Unit,
    val onShowOnly: (String) -> Unit,
    val onShowDefault: () -> Unit,
    val onFold: (String, Boolean) -> Unit,
    /** Turn a Google account's calendar sync on, here and now (docs/06 §3). */
    val onEnableSync: (String) -> Unit,
    val onEnableMaster: () -> Unit,
    val onAccountSettings: (String) -> Unit,
    /** Open "Google로 옮기기" for a phone-only calendar. */
    val onMove: (String) -> Unit,
    val onHue: (String, OloHue?) -> Unit,
    val onCalendars: () -> Unit,
    val onSyncNow: () -> Unit,
    val onSettings: () -> Unit,
    /** A short confirmation shown at the drawer's foot ("…동기화를 켰습니다"), with its undo. */
    val note: String? = null,
    val onUndo: (() -> Unit)? = null,
) {
    val current: ViewMode get() = state.view
    val attention: Boolean get() = state.needsSyncAttention
}

internal fun ViewMode.icon(): ImageVector = when (this) {
    ViewMode.YEAR -> OloIcons.Year
    ViewMode.MONTH -> OloIcons.Month
    ViewMode.WEEK -> OloIcons.Week
    ViewMode.DAY -> OloIcons.Day
    ViewMode.AGENDA -> OloIcons.Agenda
}

/**
 * Phone menu drawer, 280dp (docs/06): title and the five views stay put as a
 * fixed head; the calendar tree scrolls under it.
 */
@Composable
fun DrawerContent(nav: NavActions) {
    ModalDrawerSheet(
        modifier = Modifier.width(OloLayout.DrawerWidth),
        // Ivory ground so the white account cards stand off it.
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerShape = MaterialTheme.shapes.extraLarge.copy(topStart = CornerSize(0.dp), bottomStart = CornerSize(0.dp)),
    ) {
        val scroll = rememberScrollState()
        val lift by androidx.compose.animation.core.animateDpAsState(if (scroll.value > 0) 3.dp else 0.dp, tween(OloMotion.Quick))
        Column(Modifier.fillMaxHeight()) {
            Surface(color = MaterialTheme.colorScheme.background, shadowElevation = lift, modifier = Modifier.zIndex(1f)) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
                    Text(
                        "OLO Calendar",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp),
                    )
                    ViewRow(nav.current, nav.onView)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 6.dp, bottom = 12.dp)) {
                    CalendarTree(nav)
                    Divider()
                    SyncNowRow(nav.state.syncRun, nav.onSyncNow)
                    DrawerRow(OloIcons.Settings, "설정", onClick = nav.onSettings)
                }
                NoteBar(nav, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** "hazakuqa 캘린더 동기화를 켰습니다 · 되돌리기", sliding up at the foot of the list. */
@Composable
internal fun NoteBar(nav: NavActions, modifier: Modifier) {
    var last by remember { mutableStateOf(nav.note) }
    if (nav.note != null) last = nav.note
    AnimatedVisibility(
        visible = nav.note != null,
        enter = androidx.compose.animation.slideInVertically(tween(OloMotion.Medium, easing = OloMotion.EmphasizedDecelerate)) { it } + fadeIn(tween(OloMotion.Medium)),
        exit = androidx.compose.animation.slideOutVertically(tween(OloMotion.Quick)) { it } + fadeOut(tween(OloMotion.Quick)),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = MaterialTheme.shapes.small,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(last.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2)
                nav.onUndo?.let { undo ->
                    Text(
                        "되돌리기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inversePrimary,
                        modifier = Modifier.clickable(onClick = undo).padding(horizontal = 10.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** 년 · 월 · 주 · 일 · 일정 as one row of icon buttons: one line instead of five. */
@Composable
private fun ViewRow(current: ViewMode, onView: (ViewMode) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ViewMode.entries.forEach { v ->
            val selected = v == current
            val bg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, tween(OloMotion.Short),
            )
            Column(
                Modifier.weight(1f).height(56.dp).background(bg, MaterialTheme.shapes.small)
                    .clickable { onView(v) }
                    .semantics { this.selected = selected; role = Role.Tab; contentDescription = "${v.label} 보기" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(v.icon(), contentDescription = null, modifier = Modifier.size(22.dp))
                Text(v.label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

private fun TriState.toggleable(): ToggleableState = when (this) {
    TriState.ALL -> ToggleableState.On
    TriState.SOME -> ToggleableState.Indeterminate
    TriState.NONE -> ToggleableState.Off
}

/**
 * Two top sections (docs/07 §11): "연동 캘린더" — the Samsung ⇄ Google main pair and
 * the main account's other calendars, open — and "일반 캘린더", folded at first,
 * split into 내 기기 · Samsung · Google · 기타, each by account. Shared by the
 * phone drawer and the wide screen's calendar sheet.
 */
@Composable
fun CalendarTree(nav: NavActions, horizontal: androidx.compose.ui.unit.Dp = 10.dp) {
    val s = nav.state
    val samsung = s.bridgeSamsung
    val google = s.bridgeGoogle
    val bridged = samsung != null && google != null
    // The linked section: the joined pair and the rest of the Google main account (e.g. 가족).
    val mainRest = if (bridged) {
        s.calendars.filter { it.accountName == google!!.accountName && it.accountType == google.accountType && it.id != google.id && !it.isHoliday && it.writable }
    } else emptyList()
    // Not linked yet: Samsung Calendar still belongs here, waiting for its Google main partner.
    val samsungHome = if (bridged) null else s.calendars.firstOrNull { it.kind == SyncKind.SAMSUNG && it.writable }
    val mainIds = when {
        bridged -> setOf(samsung!!.id, google!!.id) + mainRest.map { it.id }
        samsungHome != null -> setOf(samsungHome.id)
        else -> emptySet()
    }
    val tree = remember(s.calendars, s.eventCounts, s.sync, mainIds) {
        buildCalendarTree(s.calendars, s.eventCounts, s.sync.accountsWithSyncOff, s.sync.accountsNeedingAccess, mainIds)
    }
    val shown = { id: String -> s.calendarsById[id]?.let(s::isShown) ?: false }
    Column(Modifier.padding(horizontal = horizontal)) {
        if (!s.sync.masterSyncOn) MasterSyncLine(nav.onEnableMaster)
        // No Samsung Calendar on this phone: nothing to link, so no section.
        if (mainIds.isNotEmpty()) {
            TopSection("LINKED", "연동 캘린더", mainIds.toList(), foldedByDefault = false, preview = emptyList(), nav = nav, shown = shown) {
                if (bridged) MainSection(samsung!!, google!!, mainRest, nav, shown) else SamsungHome(samsungHome!!, nav, shown)
            }
        }
        val general = tree.flatMap { it.calendars }
        TopSection("GENERAL", "일반 캘린더", general.map { it.id }, foldedByDefault = true, preview = general, nav = nav, shown = shown) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tree.forEach { group -> SourceGroup(group, nav, shown) }
            }
        }
    }
}

/** A top section: a tinted band (fold, box for all, name, n/m) over its content. */
@Composable
private fun TopSection(
    key: String,
    label: String,
    ids: List<String>,
    foldedByDefault: Boolean,
    preview: List<CalendarInfo>,
    nav: NavActions,
    shown: (String) -> Boolean,
    content: @Composable () -> Unit,
) {
    val folded = nav.state.settings.folded[key] ?: foldedByDefault
    val tri = triState(ids, shown)
    val turn by animateFloatAsState(if (folded) -90f else 0f, tween(OloMotion.Short))
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(OloLayout.TouchTarget)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                .clickable { nav.onFold(key, !folded) }
                .padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(OloIcons.ChevronDown, contentDescription = if (folded) "펼치기" else "접기", modifier = Modifier.size(20.dp).rotate(turn))
            if (ids.isNotEmpty()) TriStateCheckbox(state = tri.toggleable(), onClick = { nav.onShown(ids, tri != TriState.ALL) })
            else Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (folded && preview.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    preview.filter { shown(it.id) }.take(4).forEach { c -> Box(Modifier.size(8.dp).background(dotColor(c), CircleShape)) }
                }
                Spacer(Modifier.width(8.dp))
            }
            if (ids.isNotEmpty()) Count(ids.count(shown), ids.size)
        }
        AnimatedVisibility(
            visible = !folded,
            enter = expandVertically(tween(OloMotion.Short)) + fadeIn(tween(OloMotion.Short)),
            exit = shrinkVertically(tween(OloMotion.Short)) + fadeOut(tween(OloMotion.Quick)),
        ) {
            Column(Modifier.padding(top = 6.dp)) { content() }
        }
    }
}

/** 내 기기 · Samsung · Google · 기타: a small heading (box for all, n/m) over its account cards. */
@Composable
private fun SourceGroup(group: CategoryGroup, nav: NavActions, shown: (String) -> Boolean) {
    val ids = group.calendars.map { it.id }
    val tri = triState(ids, shown)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        TriStateCheckbox(state = tri.toggleable(), onClick = { nav.onShown(ids, tri != TriState.ALL) })
        Text(group.category.label, style = MaterialTheme.typography.labelLarge, color = OloTone.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Count(ids.count(shown), ids.size)
        Spacer(Modifier.width(10.dp))
    }
    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        group.accounts.forEach { AccountCard(it, nav, shown) }
    }
}

/**
 * Samsung Calendar before it is linked: it already sits in "연동 캘린더" as an
 * ordinary calendar line, and its own chip opens the connection — no separate
 * invite box.
 */
@Composable
private fun SamsungHome(samsung: CalendarInfo, nav: NavActions, shown: (String) -> Boolean) {
    val cs = MaterialTheme.colorScheme
    val n = nav.state.eventCounts[samsung.id] ?: 0
    Surface(
        color = cs.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { nav.onMove(samsung.id) }.padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Checkbox(
                checked = shown(samsung.id), onCheckedChange = { nav.onShown(listOf(samsung.id), it) },
                colors = CheckboxDefaults.colors(checkedColor = calendarColor(samsung.colorArgb)),
            )
            Column(Modifier.weight(1f)) {
                Text(samsung.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull("삼성 계정", "일정 $n".takeIf { n > 0 }).joinToString(" · "),
                    fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Box(Modifier.size(6.dp).background(OloTone.accent, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text("구글 메인과 연결 안 됨", fontSize = 11.sp, color = OloTone.accent, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            Text("연결", style = MaterialTheme.typography.labelLarge, color = OloTone.accent, fontWeight = FontWeight.SemiBold)
            Icon(OloIcons.ChevronRight, contentDescription = "구글 메인과 연결", tint = OloTone.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The joined pair as one calendar (docs/07 §9): Samsung Calendar ⇄ the Google main
 * calendar hold the same events, so one box shows or hides both, and the line
 * opens the bridge sheet. The main account's other calendars hang below it.
 */
@Composable
private fun MainSection(samsung: CalendarInfo, google: CalendarInfo, rest: List<CalendarInfo>, nav: NavActions, shown: (String) -> Boolean) {
    val cs = MaterialTheme.colorScheme
    val s = nav.state
    val ids = listOf(samsung.id, google.id)
    val on = shown(samsung.id) || shown(google.id)
    val n = s.eventCounts[samsung.id] ?: 0
    val issue = google.accountName in s.sync.accountsWithSyncOff || google.accountName in s.sync.accountsNeedingAccess ||
        (s.bridgeReport?.heldDeletes ?: 0) > 0 || s.bridgeDuplicates > 0
    val green = if (cs.background.luminanceIsDarkPublic()) Color(0xFF8FD1AE) else Color(0xFF2F6B4F)
    Surface(
        color = cs.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, OloTone.bandLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { nav.onMove(samsung.id) }.padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(
                    checked = on, onCheckedChange = { nav.onShown(ids, it) },
                    colors = CheckboxDefaults.colors(checkedColor = calendarColor(samsung.colorArgb)),
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(samsung.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text(" ⇄ ", style = MaterialTheme.typography.labelLarge, color = OloTone.accent)
                    }
                    Text(google.accountName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        val (label, color) = when {
                            s.bridgeProgress != null -> "맞추는 중" to green
                            s.bridgeDuplicates > 0 -> "중복 ${s.bridgeDuplicates}개" to cs.error
                            issue -> "확인 필요" to cs.error
                            else -> "연결됨" to green
                        }
                        Box(Modifier.size(6.dp).background(color, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            listOfNotNull(label, "일정 $n".takeIf { n > 0 }, s.bridgeReport?.let { "맞춤 ${agoKo(it.atMillis)}" }).joinToString(" · "),
                            fontSize = 11.sp, color = color, maxLines = 1,
                        )
                    }
                }
                Icon(OloIcons.ChevronRight, contentDescription = "연결 관리", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            if (rest.isNotEmpty()) {
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
                rest.forEach { c ->
                    CalendarLine(c, nav, shown(c.id), title = c.name, subtitle = null, status = null, trailing = {
                        val k = s.eventCounts[c.id] ?: 0
                        if (k > 0) Text("$k", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                    }, lead = 6.dp)
                }
            }
        }
    }
}

@Composable
private fun Count(on: Int, of: Int) =
    Text("$on/$of", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun MasterSyncLine(onEnable: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f), MaterialTheme.shapes.small)
            .clickable(onClick = onEnable).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("휴대폰 자동 동기화가 꺼져 있습니다", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("켜기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The account's mark: a small tile in its service's hue, as Explorer marks folders. */
@Composable
private fun AccountTile(c: CalendarInfo) {
    val hue = when {
        c.isHoliday -> MaterialTheme.colorScheme.error
        else -> when (c.kind) {
            SyncKind.GOOGLE -> OloHue.SKY.color()
            SyncKind.SAMSUNG -> OloHue.DOCUMENT.color()
            SyncKind.EXCHANGE -> OloHue.VIDEO.color()
            SyncKind.ICLOUD -> OloHue.CODE.color()
            else -> OloHue.OTHER.color()
        }
    }
    val letter = when {
        c.isHoliday -> "휴"
        else -> when (c.kind) {
            SyncKind.GOOGLE -> "G"
            SyncKind.SAMSUNG -> "S"
            SyncKind.EXCHANGE -> "E"
            SyncKind.ICLOUD -> "i"
            SyncKind.DEVICE -> "폰"
            else -> "·"
        }
    }
    Box(Modifier.size(28.dp).background(hue, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(letter, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OloTone.onSolid(hue))
    }
}

@Composable
private fun AccountCard(account: AccountGroup, nav: NavActions, shown: (String) -> Boolean) {
    val cs = MaterialTheme.colorScheme
    val counts = nav.state.eventCounts
    Surface(
        color = cs.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (account.calendars.size == 1) {
            // One calendar: the card is the calendar's own line.
            val c = account.calendars.single()
            val n = counts[c.id] ?: 0
            val bridgedNow = c.id == nav.state.settings.bridgeSamsungId
            val sub = listOfNotNull(
                account.subtitle.takeIf { !bridgedNow },
                account.title.takeIf { it != c.name && !bridgedNow },
                n.takeIf { it > 0 }?.let { "일정 $it" },
                nav.state.bridgeReport?.takeIf { bridgedNow }?.let { "맞춤 ${agoKo(it.atMillis)}" },
                nav.state.bridgeProgress?.takeIf { bridgedNow }?.let { (d, t) -> "맞추는 중 $d/$t" },
            ).joinToString(" · ")
            CalendarLine(c, nav, shown(c.id), title = c.name, subtitle = sub, status = { SyncChip(account, nav) }, trailing = {}, lead = 6.dp)
        } else {
            Column {
                val ids = account.calendars.map { it.id }
                val tri = triState(ids, shown)
                // The tile (or the account line) folds the account's calendars away.
                val folded = nav.state.settings.folded[account.key] ?: false
                val turn by animateFloatAsState(if (folded) -90f else 0f, tween(OloMotion.Short))
                Row(
                    Modifier.fillMaxWidth().clickable { nav.onFold(account.key, !folded) }
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        AccountTile(account.calendars.firstOrNull { !it.isHoliday } ?: account.calendars.first())
                        Box(
                            Modifier.align(Alignment.BottomEnd).offset(x = 5.dp, y = 5.dp).size(16.dp)
                                .background(cs.surfaceContainerLowest, CircleShape)
                                .border(1.dp, cs.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(OloIcons.ChevronDown, contentDescription = if (folded) "펼치기" else "접기", tint = cs.onSurfaceVariant, modifier = Modifier.size(12.dp).rotate(turn))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SyncChip(account, nav)
                            Text("${account.subtitle} · ${account.calendars.size}개", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    TriStateCheckbox(state = tri.toggleable(), onClick = { nav.onShown(ids, tri != TriState.ALL) })
                }
                AnimatedVisibility(
                    visible = !folded,
                    enter = expandVertically(tween(OloMotion.Short)) + fadeIn(tween(OloMotion.Short)),
                    exit = shrinkVertically(tween(OloMotion.Short)) + fadeOut(tween(OloMotion.Quick)),
                ) {
                Column {
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
                // Calendars hang from one guide line, a short branch to each.
                val guide = cs.outlineVariant
                Column(
                    Modifier.fillMaxWidth().padding(start = 22.dp, bottom = 4.dp).drawBehind {
                        val x = 4.dp.toPx()
                        drawLine(guide, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height - 26.dp.toPx()), strokeWidth = 2.dp.toPx())
                    },
                ) {
                    account.calendars.forEach { c ->
                        Box {
                            Box(
                                Modifier.padding(top = 21.dp).offset(x = 4.dp).size(width = 10.dp, height = 2.dp)
                                    .background(guide),
                            )
                            CalendarLine(c, nav, shown(c.id), title = c.name, subtitle = null, status = null, trailing = {
                                val n = counts[c.id] ?: 0
                                if (n > 0) Text("$n", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                            }, lead = 14.dp)
                        }
                    }
                }
                }
                }
            }
        }
    }
}

/** On the account line: where its events go, and the one tap that fixes it (docs/06 §3). */
@Composable
private fun SyncChip(account: AccountGroup, nav: NavActions) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminanceIsDarkPublic()
    when (account.status) {
        AccountStatus.SYNCING -> Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val g = if (dark) Color(0xFF8FD1AE) else Color(0xFF2F6B4F)
            Box(Modifier.size(6.dp).background(g, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text("동기화", fontSize = 10.sp, color = g)
        }
        AccountStatus.NEEDS_ACCESS -> {
            var menu by remember { mutableStateOf(false) }
            Box {
                Chip("확인 필요", cs.onSecondaryContainer, cs.secondaryContainer, OloIcons.ChevronDown) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Text(
                        "OLO가 이 계정의 동기화를 보고 켜려면\n계정 선택 화면에서 한 번 허용해야 합니다", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                    DropdownMenuItem(
                        text = { Text("허용하고 동기화 켜기", color = cs.primary, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = { Icon(OloIcons.Sync, contentDescription = null, tint = cs.primary) },
                        onClick = { menu = false; nav.onEnableSync(account.calendars.first().accountName) },
                    )
                    DropdownMenuItem(
                        text = { Text("계정 설정 열기") },
                        leadingIcon = { Icon(OloIcons.Settings, contentDescription = null) },
                        onClick = { menu = false; nav.onAccountSettings(account.calendars.first().accountName) },
                    )
                }
            }
        }
        AccountStatus.SYNC_OFF -> {
            var menu by remember { mutableStateOf(false) }
            Box {
                Chip("꺼짐", cs.onErrorContainer, cs.errorContainer, OloIcons.ChevronDown) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Text(
                        "이 계정의 캘린더가 동기화되지 않습니다", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                    DropdownMenuItem(
                        text = { Text("캘린더 동기화 켜기", color = cs.primary, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = { Icon(OloIcons.Sync, contentDescription = null, tint = cs.primary) },
                        onClick = { menu = false; nav.onEnableSync(account.calendars.first().accountName) },
                    )
                    DropdownMenuItem(
                        text = { Text("계정 설정 열기") },
                        leadingIcon = { Icon(OloIcons.Settings, contentDescription = null) },
                        onClick = { menu = false; nav.onAccountSettings(account.calendars.first().accountName) },
                    )
                }
            }
        }
        AccountStatus.DEVICE_ONLY -> {
            val bridged = account.calendars.firstOrNull { it.id == nav.state.settings.bridgeSamsungId }
            val samsung = account.calendars.firstOrNull { it.kind == SyncKind.SAMSUNG && it.writable }
            when {
                // Joined to the Google main calendar (docs/07): green, like a syncing account.
                bridged != null -> Chip("구글 메인 연결", if (dark) Color(0xFF8FD1AE) else Color(0xFF2F6B4F), Color.Transparent, OloIcons.ChevronRight) { nav.onMove(bridged.id) }
                samsung != null -> Chip("구글 메인과 연결", cs.onPrimaryContainer, OloTone.container, OloIcons.ChevronRight) { nav.onMove(samsung.id) }
                else -> {
                    val strandedId = account.calendars.firstOrNull { c -> nav.state.stranded.any { it.first.id == c.id } }?.id
                    Chip("이 기기만", cs.onSurfaceVariant, cs.surfaceContainerHighest, if (strandedId != null) OloIcons.ChevronRight else null,
                        onClick = strandedId?.let { id -> { nav.onMove(id) } })
                }
            }
        }
        AccountStatus.READ_ONLY -> Text("읽기 전용 · ", fontSize = 10.sp, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun Chip(text: String, fg: Color, bg: Color, icon: ImageVector?, onClick: (() -> Unit)?) {
    Row(
        Modifier.padding(end = 6.dp).background(bg, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 7.dp, end = if (icon != null) 3.dp else 7.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 10.sp, color = fg)
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(11.dp))
    }
}

@Composable
private fun dotColor(c: CalendarInfo): Color = if (c.isHoliday) MaterialTheme.colorScheme.error else calendarColor(c.colorArgb)

/** A calendar's line: colour box, name, the rest; long press for "이것만 보기" and its colour. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarLine(
    c: CalendarInfo,
    nav: NavActions,
    on: Boolean,
    title: String,
    subtitle: String?,
    status: (@Composable () -> Unit)?,
    trailing: @Composable () -> Unit,
    lead: androidx.compose.ui.unit.Dp,
) {
    var menu by remember { mutableStateOf(false) }
    val n = nav.state.eventCounts[c.id] ?: 0
    val dim = !c.writable && n == 0
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (subtitle != null) 52.dp else 42.dp)
                .combinedClickable(onClick = { nav.onShown(listOf(c.id), !on) }, onLongClick = { menu = true })
                .padding(start = lead - 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Checkbox(
                checked = on, onCheckedChange = { nav.onShown(listOf(c.id), it) },
                colors = CheckboxDefaults.colors(checkedColor = dotColor(c)),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = if (subtitle != null) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dim) 0.5f else 1f),
                )
                if (subtitle != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        status?.invoke()
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            trailing()
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("이것만 보기") }, onClick = { menu = false; nav.onShowOnly(c.id) })
            DropdownMenuItem(text = { Text("처음 설정으로 모두 보기") }, onClick = { menu = false; nav.onShowDefault() })
            Text("OLO에서 쓸 색", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp))
            HueSwatches(selected = nav.state.settings.hues[c.id]) { h -> menu = false; nav.onHue(c.id, h) }
        }
    }
}

/** The OLO hues as swatches, plus "원래 색" (the calendar's own). */
@Composable
private fun HueSwatches(selected: String?, onPick: (OloHue?) -> Unit) {
    val hues = OloHue.entries.filter { it != OloHue.OTHER }
    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        hues.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { h ->
                    val on = selected == h.name
                    Box(
                        Modifier.size(32.dp).background(h.color(), CircleShape)
                            .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { onPick(h) }
                            .semantics { contentDescription = "${h.label}색" },
                    )
                }
            }
        }
        Text(
            "원래 색", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onPick(null) }.padding(vertical = 8.dp, horizontal = 4.dp),
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun DrawerRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(OloLayout.TouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(OloLayout.IconSize))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * "지금 동기화" with its outcome underneath (docs/04): the icon turns while the
 * phone syncs, then the line says what came in, how many events the bridge
 * matched, and whether some accounts may have been skipped.
 */
@Composable
private fun SyncNowRow(run: SyncRun?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val running = run?.running == true
    val spin = androidx.compose.animation.core.rememberInfiniteTransition()
    val angle by spin.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(tween<Float>(900, easing = androidx.compose.animation.core.LinearEasing)),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = OloLayout.TouchTarget)
            .clickable(enabled = !running, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            OloIcons.Sync, contentDescription = null,
            tint = if (running) OloTone.accent else LocalContentColor.current,
            modifier = Modifier.size(OloLayout.IconSize).rotate(if (running) angle else 0f),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(if (running) "동기화 중…" else "지금 동기화", style = MaterialTheme.typography.bodyLarge)
            val line = when {
                run == null -> null
                running -> "계정마다 휴대폰에 동기화를 요청했습니다"
                else -> listOfNotNull(
                    run.atMillis?.let { "${agoKo(it)} 동기화" },
                    when {
                        run.added > 0 -> "새 일정 ${run.added}개"
                        run.removed > 0 -> "일정 ${run.removed}개 줄어듦"
                        else -> "바뀐 일정 없음"
                    },
                    run.matched?.takeIf { it > 0 }?.let { "맞춤 ${it}개" },
                ).joinToString(" · ")
            }
            if (line != null) Text(line, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            if (run != null && !running && run.unreachable > 0) {
                Text(
                    "${run.unreachable}개 계정은 확인 필요 · 계정의 ‘확인 필요’를 눌러 허용하세요",
                    style = MaterialTheme.typography.labelSmall, color = cs.error,
                )
            }
        }
    }
}

/** Wide screens: a 96dp rail, icon over label, labels always shown (five destinations). */
@Composable
fun NavRail(nav: NavActions) {
    Column(
        Modifier.width(OloLayout.RailWidth).fillMaxHeight().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ViewMode.entries.forEach { v -> RailItem(v.icon(), v.label, v == nav.current) { nav.onView(v) } }
        Spacer(Modifier.weight(1f))
        RailItem(OloIcons.Calendars, "캘린더", false, badge = nav.attention, onClick = nav.onCalendars)
        RailItem(OloIcons.Settings, "설정", false, onClick = nav.onSettings)
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, badge: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, tween(OloMotion.Short))
    Column(
        Modifier.width(80.dp).clickable(onClick = onClick).padding(vertical = 4.dp).semantics { this.selected = selected; contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 32.dp).background(bg, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(OloLayout.IconSize))
            if (badge) Box(Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = 4.dp).size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 2.dp))
    }
}
