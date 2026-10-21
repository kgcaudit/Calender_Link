package app.olo.calendar.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** What a shared event carries (docs/12 §1): one occurrence, as the person sees it. */
data class SharedEvent(
    val uid: String,
    val title: String,
    val allDay: Boolean,
    val begin: Instant,
    val end: Instant,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val location: String,
    val description: String,
)

/**
 * The message for KakaoTalk or SMS:
 * "할머니 생신\n10월 19일 (월) 오후 1:30 – 2:30\n장소 · 본가\n메모 · …".
 */
fun shareText(e: SharedEvent, zone: TimeZone, use24: Boolean): String = buildList {
    add(e.title.ifBlank { "(제목 없음)" })
    add(whenText(e, zone, use24))
    if (e.location.isNotBlank()) add("장소 · ${e.location.trim()}")
    if (e.description.isNotBlank()) add("메모 · ${e.description.trim()}")
}.joinToString("\n")

private fun whenText(e: SharedEvent, zone: TimeZone, use24: Boolean): String {
    fun day(d: LocalDate) = "${d.monthNumber}월 ${d.dayOfMonth}일 (${d.dayOfWeek.koShort()})"
    if (e.allDay) return if (e.firstDay == e.lastDay) "${day(e.firstDay)} 종일" else "${day(e.firstDay)} – ${day(e.lastDay)} 종일"
    val s = e.begin.toLocalDateTime(zone)
    val t = e.end.toLocalDateTime(zone)
    fun clock(h: Int, m: Int): String {
        val mm = m.toString().padStart(2, '0')
        return if (use24) "${h.toString().padStart(2, '0')}:$mm" else (if (h < 12) "오전 " else "오후 ") + "${if (h % 12 == 0) 12 else h % 12}:$mm"
    }
    val endPart = (if (t.date != s.date) day(t.date) + " " else "") + clock(t.hour, t.minute)
    return "${day(s.date)} ${clock(s.hour, s.minute)} – $endPart"
}

/**
 * An .ics file (RFC 5545) with one event, which Samsung Calendar, Google Calendar
 * and the iPhone open as "add to calendar". Times in UTC; all-day as dates,
 * the end exclusive; text escaped and lines folded at 75 octets.
 */
fun icsOf(e: SharedEvent, nowMillis: Long): String {
    fun utc(i: Instant): String {
        val t = i.toLocalDateTime(TimeZone.UTC)
        return "${t.year}${p(t.monthNumber)}${p(t.dayOfMonth)}T${p(t.hour)}${p(t.minute)}${p(t.second)}Z"
    }
    fun date(d: LocalDate) = "${d.year}${p(d.monthNumber)}${p(d.dayOfMonth)}"
    val lines = buildList {
        add("BEGIN:VCALENDAR")
        add("VERSION:2.0")
        add("PRODID:-//OLO Calendar//KO")
        add("METHOD:PUBLISH")
        add("BEGIN:VEVENT")
        add("UID:${e.uid}@olo.calendar")
        add("DTSTAMP:${utc(Instant.fromEpochMilliseconds(nowMillis))}")
        if (e.allDay) {
            add("DTSTART;VALUE=DATE:${date(e.firstDay)}")
            add("DTEND;VALUE=DATE:${date(e.lastDay.plus(1, DateTimeUnit.DAY))}")
        } else {
            add("DTSTART:${utc(e.begin)}")
            add("DTEND:${utc(e.end)}")
        }
        add("SUMMARY:${icsText(e.title.ifBlank { "(제목 없음)" })}")
        if (e.location.isNotBlank()) add("LOCATION:${icsText(e.location.trim())}")
        if (e.description.isNotBlank()) add("DESCRIPTION:${icsText(e.description.trim())}")
        add("END:VEVENT")
        add("END:VCALENDAR")
    }
    return lines.joinToString("") { fold(it) + "\r\n" }
}

/** RFC 5545 §3.3.11: backslash, semicolon, comma and line breaks escaped. */
fun icsText(s: String): String =
    s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")

/** RFC 5545 §3.1: lines longer than 75 octets continue on the next, after a space; a character is never split. */
fun fold(line: String): String {
    val out = StringBuilder()
    var octets = 0
    for (ch in line) {
        val n = ch.toString().encodeToByteArray().size
        if (octets + n > 75) {
            out.append("\r\n ")
            octets = 1
        }
        out.append(ch)
        octets += n
    }
    return out.toString()
}

private fun p(n: Int) = n.toString().padStart(2, '0')

/** A file name for the shared .ics: the title, without characters file systems refuse. */
fun icsFileName(title: String): String =
    (title.ifBlank { "일정" }.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(40).ifEmpty { "일정" }) + ".ics"
