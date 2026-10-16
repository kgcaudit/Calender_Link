package app.olo.calendar.model

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What moving one calendar's events into a Google calendar will do. */
data class MovePlan(
    val fromCalendarId: String,
    val events: Int,
    val recurring: Int,
    /** Changed single occurrences of repeating events; kept as standalone events. */
    val changedOccurrences: Int,
    /** Events with guests: guests are not copied, so Google sends no invitations. */
    val withGuests: Int,
)

data class MoveResult(val moved: Int, val deleted: Int, val failed: Int, val message: String? = null)

/**
 * Adds instants to an RFC 5545 EXDATE value as Android stores it (comma-
 * separated UTC date-times, optionally behind a "TZID;" prefix). Used when a
 * repeating event moves: each changed or cancelled occurrence is excluded from
 * the rule, and a changed one is recreated as its own event.
 */
fun mergeExdates(existing: String?, add: List<Instant>): String? {
    if (add.isEmpty()) return existing?.takeIf { it.isNotBlank() }
    val values = mutableListOf<String>()
    var prefix = ""
    existing?.trim()?.takeIf { it.isNotEmpty() }?.let { e ->
        val semi = e.indexOf(';')
        val body = if (semi >= 0 && !e.substring(0, semi).contains(',')) {
            prefix = e.substring(0, semi + 1)
            e.substring(semi + 1)
        } else e
        values += body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    // A TZID prefix makes the values local times; ours are UTC, so drop it only when
    // there were no existing values to keep consistent with.
    if (values.isEmpty()) prefix = ""
    for (i in add) {
        val v = if (prefix.isEmpty()) utcBasic(i) else localBasic(i, prefix.removePrefix("TZID=").removeSuffix(";"))
        if (v !in values) values += v
    }
    return prefix + values.joinToString(",")
}

private fun pad(n: Int) = n.toString().padStart(2, '0')

private fun utcBasic(i: Instant): String {
    val t = i.toLocalDateTime(TimeZone.UTC)
    return "${t.year}${pad(t.monthNumber)}${pad(t.dayOfMonth)}T${pad(t.hour)}${pad(t.minute)}${pad(t.second)}Z"
}

private fun localBasic(i: Instant, tz: String): String {
    val zone = runCatching { TimeZone.of(tz) }.getOrDefault(TimeZone.UTC)
    val t = i.toLocalDateTime(zone)
    return "${t.year}${pad(t.monthNumber)}${pad(t.dayOfMonth)}T${pad(t.hour)}${pad(t.minute)}${pad(t.second)}"
}
