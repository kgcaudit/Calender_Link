package app.olo.calendar.ui

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
    val onAction: (SyncAction) -> Unit,
    val onCalendars: () -> Unit,
    val onSyncNow: () -> Unit,
    val onSettings: () -> Unit,
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
 * Phone menu drawer, 280dp (docs/05 §3.4): the five views on one icon row, the
 * calendar tree, then what needs doing about sync, then sync and settings.
 */
@Composable
fun DrawerContent(nav: NavActions) {
    ModalDrawerSheet(
        modifier = Modifier.width(OloLayout.DrawerWidth),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerShape = MaterialTheme.shapes.extraLarge.copy(topStart = CornerSize(0.dp), bottomStart = CornerSize(0.dp)),
    ) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text(
                "OLO Calendar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp),
            )
            ViewRow(nav.current, nav.onView)
            Divider()
            CalendarTree(nav)
            Divider()
            nav.state.syncActions.forEach { ActionCard(it, nav.state, nav.onAction) }
            DrawerRow(OloIcons.Sync, "지금 동기화", onClick = nav.onSyncNow)
            DrawerRow(OloIcons.Settings, "설정", onClick = nav.onSettings)
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

/** Categories that start folded: things people rarely switch (docs/05 §3.3). */
private fun CalendarCategory.foldedByDefault() = this == CalendarCategory.HOLIDAYS || this == CalendarCategory.SUBSCRIBED

/**
 * Category → account → calendar with tri-state boxes at every level. Shared by
 * the phone drawer and the wide screen's calendar sheet.
 */
@Composable
fun CalendarTree(nav: NavActions, horizontal: androidx.compose.ui.unit.Dp = 8.dp) {
    val s = nav.state
    val tree = remember(s.calendars, s.eventCounts, s.sync) { buildCalendarTree(s.calendars, s.eventCounts, s.sync.accountsWithSyncOff) }
    val shown = { id: String -> s.calendarsById[id]?.let(s::isShown) ?: false }
    val allIds = s.calendars.map { it.id }
    Column(Modifier.padding(horizontal = horizontal)) {
        val all = triState(allIds, shown)
        HeaderRow(
            title = "전체 캘린더",
            tri = all,
            trailing = "${allIds.count(shown)}/${allIds.size}",
            onToggle = { nav.onShown(allIds, all != TriState.ALL) },
            bold = true,
        )
        tree.forEach { group -> CategorySection(group, nav, shown) }
    }
}

@Composable
private fun CategorySection(group: CategoryGroup, nav: NavActions, shown: (String) -> Boolean) {
    val folded = nav.state.settings.folded[group.key] ?: group.category.foldedByDefault()
    val ids = group.calendars.map { it.id }
    val tri = triState(ids, shown)
    val turn by animateFloatAsState(if (folded) -90f else 0f, tween(OloMotion.Short))
    Column {
        Row(
            Modifier.fillMaxWidth().height(OloLayout.TouchTarget).clickable { nav.onFold(group.key, !folded) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(OloIcons.ChevronDown, contentDescription = if (folded) "펼치기" else "접기", modifier = Modifier.size(20.dp).rotate(turn))
            TriStateCheckbox(state = tri.toggleable(), onClick = { nav.onShown(ids, tri != TriState.ALL) })
            Text(group.category.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (folded) {
                // Folded: how many are on, and their colours.
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    group.calendars.take(4).forEach { c ->
                        Box(Modifier.size(8.dp).background(dotColor(c).copy(alpha = if (shown(c.id)) 1f else 0.25f), CircleShape))
                    }
                }
                Spacer(Modifier.width(6.dp))
            }
            Text("${ids.count(shown)}/${ids.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
        }
        AnimatedVisibility(
            visible = !folded,
            enter = expandVertically(tween(OloMotion.Short)) + fadeIn(tween(OloMotion.Short)),
            exit = shrinkVertically(tween(OloMotion.Short)) + fadeOut(tween(OloMotion.Quick)),
        ) {
            Column(Modifier.padding(start = 20.dp)) {
                group.accounts.forEach { AccountSection(it, nav, shown) }
            }
        }
    }
}

@Composable
private fun AccountSection(account: AccountGroup, nav: NavActions, shown: (String) -> Boolean) {
    val ids = account.calendars.map { it.id }
    val tri = triState(ids, shown)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(account.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                StatusChip(account.status)
            }
            Text(account.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (account.calendars.size > 1) {
            TriStateCheckbox(state = tri.toggleable(), onClick = { nav.onShown(ids, tri != TriState.ALL) })
        }
    }
    account.calendars.forEach { CalendarRow(it, nav, shown(it.id)) }
}

@Composable
private fun StatusChip(status: AccountStatus) {
    val cs = MaterialTheme.colorScheme
    val (fg, bg) = when (status) {
        AccountStatus.SYNCING -> cs.primary to Color.Transparent
        AccountStatus.SYNC_OFF -> cs.error to cs.errorContainer.copy(alpha = 0.5f)
        AccountStatus.DEVICE_ONLY -> cs.onSurfaceVariant to cs.surfaceContainerHighest
        AccountStatus.READ_ONLY -> cs.onSurfaceVariant to Color.Transparent
    }
    Row(
        Modifier.background(bg, MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == AccountStatus.SYNCING) {
            Box(Modifier.size(6.dp).background(fg, CircleShape))
            Spacer(Modifier.width(4.dp))
        }
        Text(status.label, fontSize = 10.sp, color = fg)
    }
}

@Composable
private fun dotColor(c: CalendarInfo): Color = if (c.isHoliday) MaterialTheme.colorScheme.error else calendarColor(c.colorArgb)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarRow(c: CalendarInfo, nav: NavActions, on: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val n = nav.state.eventCounts[c.id] ?: 0
    val dim = !c.writable && n == 0
    Box {
        Row(
            Modifier.fillMaxWidth().height(44.dp)
                .combinedClickable(onClick = { nav.onShown(listOf(c.id), !on) }, onLongClick = { menu = true })
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Checkbox(
                checked = on, onCheckedChange = { nav.onShown(listOf(c.id), it) },
                colors = CheckboxDefaults.colors(checkedColor = dotColor(c)),
            )
            Text(
                c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dim) 0.5f else 1f),
                modifier = Modifier.weight(1f),
            )
            if (n > 0) Text("$n", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("이것만 보기") }, onClick = { menu = false; nav.onShowOnly(c.id) })
            DropdownMenuItem(text = { Text("처음 설정으로 모두 보기") }, onClick = { menu = false; nav.onShowDefault() })
        }
    }
}

@Composable
private fun HeaderRow(title: String, tri: TriState, trailing: String, onToggle: () -> Unit, bold: Boolean) {
    Row(Modifier.fillMaxWidth().height(OloLayout.TouchTarget).clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(20.dp))
        TriStateCheckbox(state = tri.toggleable(), onClick = onToggle)
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
    }
}

/** A specific problem and its fix, instead of a vague "check sync" line (docs/05 §1). */
@Composable
internal fun ActionCard(action: SyncAction, state: CalendarUiState, onAction: (SyncAction) -> Unit) {
    val (text, button) = when (action) {
        SyncAction.MasterSyncOff -> "휴대폰 자동 동기화가 꺼져 있어 어떤 계정도 동기화되지 않습니다." to "켜기"
        is SyncAction.AccountSyncOff -> "${action.account} 계정의 캘린더 동기화가 꺼져 있어 다른 기기에 반영되지 않습니다." to "켜기"
        is SyncAction.Stranded -> "${action.calendar.name}의 일정 ${action.count}개가 Google에 없어 아이폰에서 보이지 않습니다." to "Google로 옮기기"
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(OloIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                OloTextButton(button, { onAction(action) })
            }
        }
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
