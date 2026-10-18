package app.olo.calendar.model

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/*
 * The bridge's record (docs/07 §19): per pass, when and why it ran, and each
 * event it carried — which way, what kind of change, the event's time, and for
 * an edit what changed. Passes that changed nothing are not kept one by one;
 * the sheet shows the last check instead.
 */

enum class LogDir { TO_GOOGLE, TO_SAMSUNG, NONE }

data class LogItem(
    val dir: LogDir,
    /** "새 일정", "수정", "삭제", "실패", "되살림". */
    val op: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    /** For an edit, what changed ("시간 오후 3:00–3:30 → 오후 1:30–2:30"); for a delete, why. */
    val detail: String = "",
) {
    constructor(dir: LogDir, op: String, f: BridgeFields, detail: String = "") :
        this(dir, op, f.title, f.startMillis, f.endMillis, f.allDay, detail)
}

data class LogEntry(
    val atMillis: Long,
    /** What started the pass: "앱", "백그라운드", "지금 맞추기", "연결", "되살리기", … */
    val trigger: String,
    val items: List<LogItem>,
    /** Counts worth knowing that are not items: held deletes, refused copies, … */
    val note: String = "",
)

private const val ES = '\u001C'
private const val FS = '\u001F'
private const val IS = '\u001E'
private const val VS = '\u001D'
private fun c(t: String) = t.filterNot { it == ES || it == FS || it == IS || it == VS }

fun encodeLog(entries: List<LogEntry>): String = entries.joinToString(ES.toString()) { e ->
    listOf(
        e.atMillis.toString(), c(e.trigger), c(e.note),
        e.items.joinToString(IS.toString()) { i ->
            listOf(i.dir.name, c(i.op), c(i.title), i.startMillis, i.endMillis, if (i.allDay) 1 else 0, c(i.detail)).joinToString(VS.toString())
        },
    ).joinToString(FS.toString())
}

fun decodeLog(s: String?): List<LogEntry> = s.orEmpty().split(ES).mapNotNull { part ->
    val p = part.split(FS)
    if (p.size < 4) return@mapNotNull null
    val at = p[0].toLongOrNull() ?: return@mapNotNull null
    LogEntry(
        at, p[1],
        items = p[3].split(IS).filter { it.isNotEmpty() }.mapNotNull { x ->
            val q = x.split(VS)
            if (q.size < 7) null else runCatching {
                LogItem(LogDir.valueOf(q[0]), q[1], q[2], q[3].toLong(), q[4].toLong(), q[5] == "1", q[6])
            }.getOrNull()
        },
        note = p[2],
    )
}

/** "오후 1:30" in [zone]. */
fun clockKo(millis: Long, zone: TimeZone): String {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).time
    return (if (t.hour < 12) "오전 " else "오후 ") + "${(t.hour + 11) % 12 + 1}:${t.minute.toString().padStart(2, '0')}"
}

/** "10월 19일 (월)". */
fun dayKo(millis: Long, zone: TimeZone): String {
    val d = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date
    return "${d.monthNumber}월 ${d.dayOfMonth}일 (${d.dayOfWeek.koShort()})"
}

private fun span(f: BridgeFields, zone: TimeZone): String =
    if (f.allDay) "종일" else "${clockKo(f.startMillis, zone)}–${clockKo(f.endMillis, zone)}"

/** What an edit changed, from [old] to [new], in a few words a person reads at once. */
fun describeChange(old: BridgeFields?, new: BridgeFields, zone: TimeZone): String {
    if (old == null) return ""
    val parts = buildList {
        if (old.title.trim() != new.title.trim()) add("제목 ${old.title.trim()} → ${new.title.trim()}")
        if (old.startMillis != new.startMillis || old.endMillis != new.endMillis || old.allDay != new.allDay) {
            val moved = dayKo(old.startMillis, zone) != dayKo(new.startMillis, zone)
            add(
                if (moved) "일시 ${dayKo(old.startMillis, zone)} ${span(old, zone)} → ${dayKo(new.startMillis, zone)} ${span(new, zone)}"
                else "시간 ${span(old, zone)} → ${span(new, zone)}",
            )
        }
        if (old.location.trim() != new.location.trim()) add("장소 ${old.location.trim().ifEmpty { "없음" }} → ${new.location.trim().ifEmpty { "없음" }}")
        if (normaliseRrule(old.rrule) != normaliseRrule(new.rrule)) add("반복 바뀜")
        if (old.reminderMinutes.sorted() != new.reminderMinutes.sorted()) add("알림 바뀜")
        if (old.description.trim() != new.description.trim()) add("메모 바뀜")
        if (old.exdates.toSet() != new.exdates.toSet() || old.exceptions != new.exceptions) add("반복 회차 바뀜")
    }
    return parts.joinToString(" · ")
}
