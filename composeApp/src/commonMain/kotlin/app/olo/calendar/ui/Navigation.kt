package app.olo.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.olo.calendar.design.OloIcons
import app.olo.calendar.design.OloLayout
import app.olo.calendar.design.calendarColor
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.ViewMode
import app.olo.calendar.model.koLabel

class NavActions(
    val current: ViewMode,
    val attention: Boolean,
    val calendars: List<CalendarInfo>,
    val onView: (ViewMode) -> Unit,
    val onToggleCalendar: (String, Boolean) -> Unit,
    val onCalendars: () -> Unit,
    val onSync: () -> Unit,
    val onSyncNow: () -> Unit,
    val onSettings: () -> Unit,
)

internal fun ViewMode.icon(): ImageVector = when (this) {
    ViewMode.YEAR -> OloIcons.Year
    ViewMode.MONTH -> OloIcons.Month
    ViewMode.WEEK -> OloIcons.Week
    ViewMode.DAY -> OloIcons.Day
    ViewMode.AGENDA -> OloIcons.Agenda
}

/**
 * Phone menu drawer, 280dp (docs/04): views with icons, the calendars grouped by
 * account with their visibility, then sync and settings. Narrow enough that the
 * month stays visible beside it.
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
                modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 10.dp),
            )
            ViewMode.entries.forEach { v -> DrawerRow(v.icon(), v.label, selected = v == nav.current) { nav.onView(v) } }
            Divider()
            if (nav.attention) DrawerRow(OloIcons.Warning, "동기화 확인 필요", tint = MaterialTheme.colorScheme.error, onClick = nav.onSync)
            nav.calendars.groupBy { it.accountName to it.kind }.forEach { (key, cals) ->
                Text(
                    "${key.second.koLabel()} · ${key.first}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                )
                cals.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).clickable { nav.onToggleCalendar(c.id, !c.visible) }.padding(start = 12.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = c.visible, onCheckedChange = { nav.onToggleCalendar(c.id, it) },
                            colors = CheckboxDefaults.colors(checkedColor = if (c.isHoliday) MaterialTheme.colorScheme.error else calendarColor(c.colorArgb)),
                        )
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Divider()
            DrawerRow(OloIcons.Sync, "지금 동기화", onClick = nav.onSyncNow)
            DrawerRow(OloIcons.Settings, "설정", onClick = nav.onSettings)
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun DrawerRow(icon: ImageVector, text: String, selected: Boolean = false, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(OloLayout.TouchTarget)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.Tab }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(OloLayout.IconSize))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
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
    Column(
        Modifier.width(80.dp).clickable(onClick = onClick).padding(vertical = 4.dp).semantics { this.selected = selected; contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 32.dp)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(OloLayout.IconSize))
            if (badge) Box(Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = 4.dp).size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 2.dp))
    }
}
