package app.olo.calendar.model

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/** What the quick-add line understood: a title and when. No [start] means all-day. */
data class QuickEntry(
    val title: String,
    val date: LocalDate,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
)

/**
 * Reads a one-line Korean entry such as "내일 오후 3시 회의", "10월 20일 2시 반 병원",
 * "다음주 화요일 10:30~12시 워크숍" or "금요일 저녁 7시 동창회", as Samsung's quick
 * entry does. Date and time words are taken out of the title ("제목에서 시간 삭제").
 *
 * Rules:
 * - no date word → [baseDate] (the day selected on screen);
 * - a weekday alone → its next occurrence from [today] (today counts);
 *   "이번주" → that day of this week, "다음주" → of next week;
 * - "M월 D일" / "M/D" that already passed this year → next year; "D일" that passed → next month;
 * - an hour without 오전/오후: 1–6 시 → afternoon, 7–11 → morning, 12 → noon;
 * - an end time not after the start is moved to the afternoon when it can be, else
 *   (past midnight) the event ends at 23:59;
 * - no end → one hour.
 */
object QuickAdd {
    private const val QUAL = "(오전|오후|아침|점심|저녁|밤|새벽)"
    private val timeRe = Regex(
        "(?:$QUAL\\s*)?(?:(\\d{1,2})\\s*시\\s*(?:(\\d{1,2})\\s*분|(반))?|(\\d{1,2}):(\\d{2}))",
    )
    private val rangeSep = Regex("^\\s*(?:~|-|–|부터)\\s*")
    private val trailing = Regex("^\\s*(?:까지|에)")
    private val relDayRe = Regex("(오늘|내일|모레|글피)")
    private val weekdayRe = Regex("(?:(이번\\s*주|다음\\s*주|담주|다다음\\s*주)\\s*)?([월화수목금토일])요일")
    private val monthDayRe = Regex("(?:(\\d{4})\\s*년\\s*)?(?:(\\d{1,2})\\s*월\\s*)(\\d{1,2})\\s*일")
    private val dayOnlyRe = Regex("(?<![\\d월])(\\d{1,2})\\s*일(?!\\s*(?:간|동안))")
    private val slashRe = Regex("(?<![\\d:])(\\d{1,2})[/.](\\d{1,2})(?![\\d:])")

    fun parse(text: String, baseDate: LocalDate, today: LocalDate = baseDate): QuickEntry {
        var rest = text.trim()
        var date: LocalDate? = null

        fun cut(r: MatchResult) {
            rest = rest.removeRange(r.range).let { s ->
                // swallow a particle left behind ("3시에" → "", "내일까지" → "")
                val after = s.substring(minOf(r.range.first, s.length))
                val m = trailing.find(after)
                if (m != null && m.range.first == 0) s.removeRange(r.range.first, r.range.first + m.value.length) else s
            }
        }

        relDayRe.find(rest)?.let { m ->
            val n = when (m.groupValues[1]) { "오늘" -> 0; "내일" -> 1; "모레" -> 2; else -> 3 }
            date = today.plus(n, DateTimeUnit.DAY)
            cut(m)
        }
        if (date == null) weekdayRe.find(rest)?.let { m ->
            val dow = "월화수목금토일".indexOf(m.groupValues[2]).let { DayOfWeek.entries[it] }
            val which = m.groupValues[1].replace(" ", "")
            val mondayThisWeek = today.plus(-(today.dayOfWeek.isoDayNumber - 1), DateTimeUnit.DAY)
            date = when (which) {
                "이번주" -> mondayThisWeek.plus(dow.isoDayNumber - 1, DateTimeUnit.DAY)
                "다음주", "담주" -> mondayThisWeek.plus(7 + dow.isoDayNumber - 1, DateTimeUnit.DAY)
                "다다음주" -> mondayThisWeek.plus(14 + dow.isoDayNumber - 1, DateTimeUnit.DAY)
                else -> today.plus((dow.isoDayNumber - today.dayOfWeek.isoDayNumber + 7) % 7, DateTimeUnit.DAY)
            }
            cut(m)
        }
        if (date == null) monthDayRe.find(rest)?.let { m ->
            val y = m.groupValues[1].toIntOrNull()
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            date = safeDate(y ?: today.year, mo, d)?.let { if (y == null && it < today) safeDate(today.year + 1, mo, d) else it }
            if (date != null) cut(m)
        }
        if (date == null) slashRe.find(rest)?.let { m ->
            val mo = m.groupValues[1].toInt()
            val d = m.groupValues[2].toInt()
            date = safeDate(today.year, mo, d)?.let { if (it < today) safeDate(today.year + 1, mo, d) else it }
            if (date != null) cut(m)
        }
        if (date == null) dayOnlyRe.find(rest)?.let { m ->
            val d = m.groupValues[1].toInt()
            date = safeDate(today.year, today.monthNumber, d)?.let { if (it < today) today.plus(DatePeriod(months = 1)).let { n -> safeDate(n.year, n.monthNumber, d) } else it }
            if (date != null) cut(m)
        }

        var start: LocalTime? = null
        var end: LocalTime? = null
        timeRe.find(rest)?.let { m ->
            val s = toTime(m, null)
            if (s != null) {
                start = s
                var removeEnd = m.range.last + 1
                val after = rest.substring(removeEnd)
                val sep = rangeSep.find(after)
                if (sep != null) {
                    val m2 = timeRe.find(after, sep.value.length)
                    if (m2 != null && m2.range.first == sep.value.length) {
                        end = toTime(m2, s)
                        removeEnd += m2.range.last + 1
                    }
                }
                rest = rest.removeRange(m.range.first, removeEnd).let { str ->
                    val tail = str.substring(minOf(m.range.first, str.length))
                    val t = trailing.find(tail)
                    if (t != null && t.range.first == 0) str.removeRange(m.range.first, m.range.first + t.value.length) else str
                }
            }
        }
        val st = start
        if (st != null && end == null) end = if (st.hour >= 23) LocalTime(23, 59) else LocalTime(st.hour + 1, st.minute)
        // An end past midnight ("오후 11시~1시") cannot be said on one day: end the day instead.
        if (st != null && end != null && end!! <= st) end = LocalTime(23, 59)

        val title = rest.replace(Regex("\\s+"), " ").trim().trim(',', '.', '-', '~').trim()
        return QuickEntry(title.ifEmpty { "새 일정" }, date ?: baseDate, start, end)
    }

    private fun toTime(m: MatchResult, after: LocalTime?): LocalTime? {
        val q = m.groupValues[1]
        val colon = m.groupValues[5].isNotEmpty()
        var h = (if (colon) m.groupValues[5] else m.groupValues[2]).toIntOrNull() ?: return null
        val min = when {
            colon -> m.groupValues[6].toIntOrNull() ?: 0
            m.groupValues[4].isNotEmpty() -> 30
            else -> m.groupValues[3].toIntOrNull() ?: 0
        }
        if (h > 24 || min > 59) return null
        h = when (q) {
            // "밤 12시" is midnight, not noon.
            "밤" -> if (h == 12) 0 else if (h < 12) h + 12 else h
            "오후", "저녁" -> if (h < 12) h + 12 else h
            "오전", "아침", "새벽" -> if (h == 12) 0 else h
            "점심" -> if (h in 1..4) h + 12 else h
            else -> when {
                colon && (h == 0 || h >= 7) -> h
                after != null -> if (h <= after.hour && h + 12 < 24) h + 12 else h
                h in 1..6 -> h + 12
                else -> h
            }
        }
        if (h == 24) h = 0
        return LocalTime(h, min)
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate(y, m, d) }.getOrNull()
}
