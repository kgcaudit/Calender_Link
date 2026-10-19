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
    /** "새 일정", "수정", "삭제", "실패", "복원". */
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
    /** What started the pass: "앱", "백그라운드", "다시 연동", "연결", "복원", … */
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

/**
 * A record written before docs/08 in today's words ("맞춤" → "연동", "되살림" → "복원"),
 * so old and new entries read alike. New entries are written in these words already.
 */
fun currentWords(s: String): String = LEGACY_WORDS.fold(s) { acc, (old, new) -> acc.replace(old, new) }

private val LEGACY_WORDS = listOf(
    "앱 · 변경 감지" to "변경 감지",
    "지금 맞추기" to "다시 ${Term.BRIDGE}",
    "다시 맞추기" to "다시 ${Term.BRIDGE}",
    "되살린 뒤 맞춤" to "${Term.RESTORE} 뒤 ${Term.BRIDGE}",
    "되돌린 뒤 맞춤" to "되돌린 뒤 ${Term.BRIDGE}",
    "되살리기" to Term.RESTORE,
    "되살림" to Term.RESTORE,
    "양쪽에서 지우기" to "양쪽에서 삭제",
    "Samsung에서 사라짐" to "${Term.SAMSUNG}에서 삭제됨",
    "구글에서 사라짐" to "Google에서 삭제됨",
    "쓰기를 휴대폰이 받지 않음 · 다음 맞춤에서 다시 시도" to "저장되지 않음 · 다음 ${Term.BRIDGE} 때 다시 시도",
    "양쪽 수정" to "동시 수정",
    " · Samsung 기준" to "(삼성 쪽 유지)",
    "동기화 전 사라짐" to "동기화 전에 삭제됨",
    "구글이 받지 않는 일정" to "Google이 지원하지 않는 일정",
    "쓰기 실패" to "저장 실패",
    "지워진 기록에서 Samsung으로" to "삭제 기록에서 ${Term.SAMSUNG}로",
    "함께 지운 일정을 Samsung으로" to "함께 지운 일정을 ${Term.SAMSUNG}로",
    "구글로 " to "Google로 ",
)

/** "방금", "3분 전", "2시간 전", "3일 전": how long before [nowMillis]. */
fun agoText(atMillis: Long, nowMillis: Long): String {
    val m = (nowMillis - atMillis) / 60_000
    return when {
        m < 1 -> "방금"
        m < 60 -> "${m}분 전"
        m < 24 * 60 -> "${m / 60}시간 전"
        else -> "${m / (24 * 60)}일 전"
    }
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

/** The day an event falls on: all-day events are kept at UTC midnight, so read them in UTC. */
fun eventDayKo(startMillis: Long, allDay: Boolean, zone: TimeZone): String =
    dayKo(startMillis, if (allDay) TimeZone.UTC else zone)

/** "종일" or "오후 1:30–오후 2:30". */
fun spanKo(startMillis: Long, endMillis: Long, allDay: Boolean, zone: TimeZone): String =
    if (allDay) "종일" else "${clockKo(startMillis, zone)}–${clockKo(endMillis, zone)}"

private fun span(f: BridgeFields, zone: TimeZone): String = spanKo(f.startMillis, f.endMillis, f.allDay, zone)
private fun day(f: BridgeFields, zone: TimeZone): String = eventDayKo(f.startMillis, f.allDay, zone)

/** What an edit changed, from [old] to [new], in a few words a person reads at once. */
fun describeChange(old: BridgeFields?, new: BridgeFields, zone: TimeZone): String {
    if (old == null) return ""
    val parts = buildList {
        if (old.title.trim() != new.title.trim()) add("제목 ${old.title.trim()} → ${new.title.trim()}")
        if (old.startMillis != new.startMillis || old.endMillis != new.endMillis || old.allDay != new.allDay) {
            val moved = day(old, zone) != day(new, zone)
            add(
                if (moved) "일시 ${day(old, zone)} ${span(old, zone)} → ${day(new, zone)} ${span(new, zone)}"
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
