package app.olo.calendar.model

import kotlinx.datetime.LocalDate

/** One drawn piece of an occurrence inside a week row. Columns are 0..6. */
data class WeekSegment(
    val occurrence: Occurrence,
    val startCol: Int,
    val endCol: Int,
    val lane: Int,
    /** How many lanes tall: a one-day item may take two so its title wraps. */
    val height: Int,
    /** Started in an earlier week / runs on into a later one (squared-off ends). */
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
) {
    val spansDays: Boolean get() = endCol > startCol
}

data class WeekLayout(val segments: List<WeekSegment>, val hiddenPerDay: List<Int>)

/**
 * Lays a week's occurrences into lanes the way a paper diary does:
 * - an event keeps one lane across the whole week, so a multi-day bar is one
 *   continuous strip instead of a chip repeated per day;
 * - longer spans go on top, then other all-day events, then timed ones by start;
 * - a one-day item grows into the lane below when it is free, so its title can
 *   take two lines;
 * - what does not fit in [maxLanes] is counted per day for a "+n".
 */
fun layoutWeek(days: List<LocalDate>, items: List<Occurrence>, maxLanes: Int): WeekLayout {
    require(days.size == 7)
    val first = days.first()
    val last = days.last()
    val lanes = maxOf(0, maxLanes)
    data class Item(val o: Occurrence, val s: Int, val e: Int)
    val inWeek = items.filter { it.lastDay >= first && it.firstDay <= last }.map { o ->
        val s = if (o.firstDay < first) 0 else first.daysUntilSafe(o.firstDay)
        val e = if (o.lastDay > last) 6 else first.daysUntilSafe(o.lastDay)
        Item(o, s, e)
    }
    val ordered = inWeek.sortedWith(
        compareByDescending<Item> { it.o.lastDay.toEpochDays() - it.o.firstDay.toEpochDays() > 0 }
            .thenByDescending { it.e - it.s }
            .thenByDescending { it.o.allDay }
            .thenBy { it.s }
            .thenBy { it.o.begin }
            .thenBy { it.o.title },
    )

    // Roomy first: one-day items take two lanes from the start, so a long title
    // gets its second line even when other items follow. If that would hide
    // anything, fall back to packing one lane each and growing where free.
    val roomy = place(ordered.map { Triple(it.o, it.s, it.e) }, first, last, lanes, twoLineSingles = true)
    val result = if (roomy.second.all { it == 0 }) roomy else place(ordered.map { Triple(it.o, it.s, it.e) }, first, last, lanes, twoLineSingles = false)
    return WeekLayout(result.first, result.second.toList())
}

private fun place(
    ordered: List<Triple<Occurrence, Int, Int>>,
    first: LocalDate,
    last: LocalDate,
    lanes: Int,
    twoLineSingles: Boolean,
): Pair<List<WeekSegment>, IntArray> {
    val grid = Array(lanes) { arrayOfNulls<WeekSegment>(7) }
    val hidden = IntArray(7)
    val placed = mutableListOf<WeekSegment>()
    fun free(l: Int, s: Int, e: Int) = l < lanes && (s..e).all { c -> grid[l][c] == null }
    for ((o, s, e) in ordered) {
        val single = s == e && o.firstDay == o.lastDay
        val tall = if (twoLineSingles && single) (0 until lanes).firstOrNull { l -> free(l, s, e) && free(l + 1, s, e) } else null
        val lane = tall ?: (0 until lanes).firstOrNull { l -> free(l, s, e) }
        if (lane == null) {
            for (c in s..e) hidden[c]++
            continue
        }
        val height = if (tall != null) 2 else 1
        val before = o.firstDay < first
        val after = o.lastDay > last
        val seg = WeekSegment(o, s, e, lane, height, before, after)
        for (l in lane until lane + height) for (c in s..e) grid[l][c] = seg
        placed += seg
    }
    if (twoLineSingles) return placed to hidden
    // Packed: give one-day items a second line where the lane below is empty.
    val grown = placed.map { seg ->
        val below = seg.lane + 1
        if (!seg.spansDays && below < lanes && grid[below][seg.startCol] == null) {
            val g = seg.copy(height = 2)
            grid[below][seg.startCol] = g
            g
        } else seg
    }
    return grown to hidden

}

private fun LocalDate.daysUntilSafe(other: LocalDate): Int = other.toEpochDays() - toEpochDays()
