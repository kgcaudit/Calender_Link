package app.olo.calendar.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** What an edit or deletion of one occurrence of a repeating event reaches (docs/10). */
enum class EditScope(val label: String) {
    THIS("이 일정만"),
    FOLLOWING("이 일정과 이후 일정"),
    ALL("전체 반복 일정"),
}

/** One occurrence as the screen knows it: enough to address it in the store. */
data class OccurrenceRef(
    /** The series' master event. */
    val seriesId: String,
    /** The row drawn: the master, or a changed occurrence (an exception row). */
    val eventId: String,
    val begin: Instant,
    val end: Instant,
    val allDay: Boolean,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
) {
    /** A changed occurrence: it is its own row, outside the master's rule. */
    val isException: Boolean get() = eventId != seriesId

    /** Whether [o] is one of the occurrences [scope] reaches from this one. */
    fun covers(o: Occurrence, scope: EditScope): Boolean = when (scope) {
        EditScope.THIS -> o.eventId == eventId && o.begin == begin
        EditScope.FOLLOWING -> o.seriesId == seriesId && o.begin >= begin
        EditScope.ALL -> o.seriesId == seriesId
    }
}

fun Occurrence.ref() = OccurrenceRef(seriesId, eventId, begin, end, allDay, firstDay, lastDay)

/**
 * The series' draft moved onto one occurrence: its own dates (and times, read in
 * the event's zone), so "this one" and "from this one on" open where it was tapped.
 */
fun occurrenceDraft(master: EventDraft, o: OccurrenceRef): EventDraft {
    if (o.allDay) return master.copy(startDate = o.firstDay, endDate = o.lastDay)
    val zone = runCatching { TimeZone.of(master.timeZoneId) }.getOrDefault(TimeZone.currentSystemDefault())
    val s = o.begin.toLocalDateTime(zone)
    val e = o.end.toLocalDateTime(zone)
    return master.copy(startDate = s.date, startTime = s.time, endDate = e.date, endTime = e.time)
}

/**
 * The RRULE value that stops a series just before [beforeMillis] (the first
 * occurrence it no longer has): COUNT and UNTIL replaced by an UNTIL, UTC for
 * timed events, a date for all-day ones (RFC 5545 §3.3.10).
 */
fun ruleEndingBefore(rule: String, beforeMillis: Long, allDay: Boolean): String {
    val until = if (allDay) {
        val d = Instant.fromEpochMilliseconds(beforeMillis).toLocalDateTime(TimeZone.UTC).date.minus(1, DateTimeUnit.DAY)
        "${d.year}${pad(d.monthNumber)}${pad(d.dayOfMonth)}"
    } else {
        val t = Instant.fromEpochMilliseconds(beforeMillis - 1000).toLocalDateTime(TimeZone.UTC)
        "${t.year}${pad(t.monthNumber)}${pad(t.dayOfMonth)}T${pad(t.hour)}${pad(t.minute)}${pad(t.second)}Z"
    }
    return withParts(rule) { parts -> parts.filterNot { it.key == "COUNT" || it.key == "UNTIL" } + RulePart("UNTIL", until) }
}

/** The rule's COUNT, if it ends after a number of times. */
fun ruleCount(rule: String): Int? = parts(rule).firstOrNull { it.key == "COUNT" }?.value?.toIntOrNull()

/** The same rule with [count] occurrences (the second half of a split "N times" series). */
fun ruleWithCount(rule: String, count: Int): String =
    withParts(rule) { parts -> parts.map { if (it.key == "COUNT") RulePart("COUNT", count.coerceAtLeast(1).toString()) else it } }

private data class RulePart(val key: String, val value: String)

private fun parts(rule: String): List<RulePart> =
    rule.removePrefix("RRULE:").split(';').filter { it.contains('=') }.map { RulePart(it.substringBefore('=').uppercase(), it.substringAfter('=')) }

private fun withParts(rule: String, change: (List<RulePart>) -> List<RulePart>): String {
    val prefix = if (rule.startsWith("RRULE:")) "RRULE:" else ""
    return prefix + change(parts(rule)).joinToString(";") { "${it.key}=${it.value}" }
}

private fun pad(n: Int) = n.toString().padStart(2, '0')
