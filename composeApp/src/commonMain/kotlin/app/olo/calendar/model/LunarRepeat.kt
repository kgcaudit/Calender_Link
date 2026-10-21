package app.olo.calendar.model

import kotlinx.datetime.LocalDate

/**
 * A yearly lunar date (음력 생일, 제사), docs/11. A rule of RFC 5545 cannot say
 * "every lunar 9/9", and Google and the iPhone drop Samsung's own lunar repeat,
 * so OLO writes each year's solar date as an ordinary event and remembers the set.
 * A leap month repeats on the ordinary month of that number, and a 30th falls on
 * the 29th in a short month, as is the custom.
 */
data class LunarRule(val month: Int, val day: Int) {
    fun label(): String = "음력 ${month}월 ${day}일"
}

/** The lunar date [date] falls on, as a yearly rule; null outside the table. */
fun lunarRuleOf(date: LocalDate): LunarRule? = KoreanLunar.fromSolar(date)?.let { LunarRule(it.month, it.day) }

/** The solar date of [rule] in lunar [year]; null outside the table. */
fun lunarDateIn(year: Int, rule: LunarRule): LocalDate? {
    val length = KoreanLunar.monthLength(year, rule.month) ?: return null
    return KoreanLunar.toSolar(year, rule.month, minOf(rule.day, length))
}

/** [count] yearly dates of [rule] on or after [from], as far as the table reaches. */
fun lunarYearlyDates(rule: LunarRule, from: LocalDate, count: Int): List<LocalDate> {
    val startYear = KoreanLunar.fromSolar(from)?.year ?: return emptyList()
    return generateSequence(startYear - 1) { it + 1 }.take(count + 2 + 1)
        .mapNotNull { lunarDateIn(it, rule) }.filter { it >= from }.take(count).toList()
}

/** One lunar repeat: its rule and the events written for it, each with its date. */
data class LunarGroup(val id: String, val rule: LunarRule, val members: Map<String, LocalDate>) {
    val last: LocalDate? get() = members.values.maxOrNull()

    /** The events [scope] reaches from the one dated [from]. */
    fun reach(eventId: String, scope: EditScope): Set<String> {
        val from = members[eventId] ?: return setOf(eventId)
        return when (scope) {
            EditScope.THIS -> setOf(eventId)
            EditScope.FOLLOWING -> members.filterValues { it >= from }.keys
            EditScope.ALL -> members.keys
        }
    }
}

/** How many years a lunar repeat is written ahead, and when it is topped up (docs/11 §2). */
const val LUNAR_YEARS = 10
const val LUNAR_TOP_UP_BELOW_YEARS = 5

private const val GS = '\u001E'
private const val FS = '\u001F'

fun encodeLunarGroups(groups: List<LunarGroup>): String = groups.joinToString(GS.toString()) { g ->
    listOf(g.id, g.rule.month.toString(), g.rule.day.toString(), g.members.entries.joinToString(",") { "${it.key}@${it.value.toEpochDays()}" })
        .joinToString(FS.toString())
}

fun decodeLunarGroups(s: String?): List<LunarGroup> = s.orEmpty().split(GS).mapNotNull { part ->
    val p = part.split(FS)
    if (p.size < 4) return@mapNotNull null
    val rule = LunarRule(p[1].toIntOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: return@mapNotNull null)
    val members = p[3].split(',').filter { '@' in it }.mapNotNull { m ->
        m.substringAfter('@').toIntOrNull()?.let { m.substringBefore('@') to LocalDate.fromEpochDays(it) }
    }.toMap()
    LunarGroup(p[0], rule, members)
}
