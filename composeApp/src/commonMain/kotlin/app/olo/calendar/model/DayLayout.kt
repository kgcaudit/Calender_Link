package app.olo.calendar.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit

/** A timed event placed in a day column of the week/day time grid. */
data class TimeBlock(
    val occurrence: Occurrence,
    /** Minutes from the day's midnight, clipped to 0..1440. */
    val startMin: Int,
    val endMin: Int,
    /** Side-by-side position among overlapping events: column [col] of [cols]. */
    val col: Int,
    val cols: Int,
)

/**
 * Places a day's timed events in the time grid. Overlapping events share the
 * width in columns; each overlap cluster gets as many columns as it needs at
 * most, so an event that overlaps nothing takes the full width.
 * Very short events still get [minMinutes] of height so their title shows.
 */
fun layoutDay(day: LocalDate, items: List<Occurrence>, zone: TimeZone, minMinutes: Int = 20): List<TimeBlock> {
    val dayStart = day.atStartOfDayIn(zone)
    val dayEnd = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
    fun mins(i: Instant) = ((i - dayStart).inWholeMinutes).toInt().coerceIn(0, 1440)

    data class Item(val o: Occurrence, val s: Int, val e: Int)
    val timed = items.filter { !it.allDay && it.end > dayStart && it.begin < dayEnd }
        .map { o -> val s = mins(o.begin); Item(o, s, maxOf(mins(o.end), minOf(1440, s + minMinutes))) }
        .sortedWith(compareBy<Item> { it.s }.thenByDescending { it.e - it.s })

    val out = mutableListOf<TimeBlock>()
    var cluster = mutableListOf<Pair<Item, Int>>()
    var clusterEnd = -1
    val colEnds = mutableListOf<Int>()
    fun flush() {
        val n = colEnds.size
        cluster.forEach { (it, c) -> out += TimeBlock(it.o, it.s, it.e, c, n) }
        cluster = mutableListOf()
        colEnds.clear()
    }
    for (it in timed) {
        if (it.s >= clusterEnd && cluster.isNotEmpty()) flush()
        val c = colEnds.indexOfFirst { end -> end <= it.s }
        val col = if (c >= 0) { colEnds[c] = it.e; c } else { colEnds += it.e; colEnds.size - 1 }
        cluster += it to col
        clusterEnd = maxOf(clusterEnd, it.e)
    }
    if (cluster.isNotEmpty()) flush()
    return out
}
