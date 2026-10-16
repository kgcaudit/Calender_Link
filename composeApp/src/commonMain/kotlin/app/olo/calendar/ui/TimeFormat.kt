package app.olo.calendar.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.olo.calendar.model.Occurrence
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Follows the phone's 12/24-hour setting, as Samsung Calendar does ("오후 8:05" vs "20:05"). */
val LocalUse24Hour = staticCompositionLocalOf { true }

fun clock(t: LocalTime, use24: Boolean): String {
    val mm = t.minute.toString().padStart(2, '0')
    if (use24) return "${t.hour.toString().padStart(2, '0')}:$mm"
    val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
    return (if (t.hour < 12) "오전 " else "오후 ") + "$h12:$mm"
}

/** "종일", or "오후 8:05 – 오후 10:15" / "20:05 – 22:15". */
fun timeLabel(o: Occurrence, zone: TimeZone, use24: Boolean): String {
    if (o.allDay) return "종일"
    return clock(o.begin.toLocalDateTime(zone).time, use24) + " – " + clock(o.end.toLocalDateTime(zone).time, use24)
}
