package app.olo.calendar.store

import app.olo.calendar.model.EditScope
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.LUNAR_TOP_UP_BELOW_YEARS
import app.olo.calendar.model.LUNAR_YEARS
import app.olo.calendar.model.LunarGroup
import app.olo.calendar.model.Preferences
import app.olo.calendar.model.decodeLunarGroups
import app.olo.calendar.model.encodeLunarGroups
import app.olo.calendar.model.lunarRuleOf
import app.olo.calendar.model.lunarYearlyDates
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/**
 * Lunar yearly repeats (docs/11): each year written as an ordinary event, the
 * set kept here so it can be edited, deleted and extended as one.
 */
class LunarRepeats(
    private val store: CalendarStore,
    private val prefs: Preferences,
    /** Told the ids about to be deleted on purpose (the bridge then carries them over without its safety stop). */
    private val beforeDelete: (Set<String>) -> Unit = {},
) {
    val groups: List<LunarGroup> get() = decodeLunarGroups(prefs.get(KEY))

    private fun keep(groups: List<LunarGroup>) =
        prefs.put(KEY, groups.filter { it.members.isNotEmpty() }.let { if (it.isEmpty()) null else encodeLunarGroups(it) })

    fun groupOf(eventId: String): LunarGroup? = groups.firstOrNull { eventId in it.members }

    /** Writes [draft] for [LUNAR_YEARS] years from its start date's lunar date. Returns the new events' ids. */
    suspend fun create(draft: EventDraft): List<String> {
        val rule = lunarRuleOf(draft.startDate) ?: error("음력으로 바꿀 수 없는 날짜입니다")
        val ids = write(draft, lunarYearlyDates(rule, draft.startDate, LUNAR_YEARS))
        keep(groups + LunarGroup(newId(), rule, ids))
        return ids.keys.toList()
    }

    /**
     * Saves [draft] (opened on [eventId]) for [scope] of its group. A changed
     * lunar date rewrites the reached years on the new date; otherwise each one
     * takes the edit on its own date.
     */
    suspend fun save(draft: EventDraft, eventId: String, scope: EditScope) {
        val g = groupOf(eventId) ?: run { store.save(draft.copy(id = eventId, lunarYearly = false)); return }
        val reached = g.reach(eventId, scope)
        val plain = draft.copy(lunarYearly = false, recurrence = null, keepExistingRule = false)
        val newRule = lunarRuleOf(draft.startDate)
        val members = g.members.toMutableMap()
        if (scope == EditScope.THIS || newRule == g.rule) {
            for (id in reached) {
                val date = members[id] ?: continue
                val saved = runCatching { store.save(shift(plain, date).copy(id = id)) }.getOrNull() ?: continue
                if (saved != id) { members.remove(id); members[saved] = date }
            }
            replace(g.copy(members = members))
        } else {
            // A new lunar date: the reached years are written again on it.
            beforeDelete(reached)
            reached.forEach { id -> runCatching { store.delete(id) }; members.remove(id) }
            val from = minOf(draft.startDate, g.members[eventId] ?: draft.startDate)
            val rule = newRule ?: g.rule
            val written = write(plain, lunarYearlyDates(rule, from, reached.size.coerceAtLeast(1)))
            replace(LunarGroup(g.id, rule, members + written))
        }
    }

    /** The events a deletion of [scope] from [eventId] takes (for the screen to hide while it can be undone). */
    fun reach(eventId: String, scope: EditScope): Set<String> = groupOf(eventId)?.reach(eventId, scope) ?: setOf(eventId)

    suspend fun delete(eventId: String, scope: EditScope) {
        val g = groupOf(eventId) ?: run { store.delete(eventId); return }
        val gone = g.reach(eventId, scope)
        beforeDelete(gone)
        gone.forEach { runCatching { store.delete(it) } }
        replace(g.copy(members = g.members - gone))
    }

    /**
     * Keeps every repeat [LUNAR_YEARS] years ahead once fewer than
     * [LUNAR_TOP_UP_BELOW_YEARS] remain, copying its latest year. Returns how many events it wrote.
     */
    suspend fun topUp(today: LocalDate): Int {
        var written = 0
        for (g in groups) {
            val last = g.last ?: continue
            if (last >= today.plus(DatePeriod(years = LUNAR_TOP_UP_BELOW_YEARS))) continue
            val template = g.members.entries.firstOrNull { it.value == last }?.key?.let { store.event(it) }?.draft ?: continue
            val horizon = today.plus(DatePeriod(years = LUNAR_YEARS))
            val dates = lunarYearlyDates(g.rule, last.plus(DatePeriod(days = 1)), LUNAR_YEARS).filter { it <= horizon }
            if (dates.isEmpty()) continue
            val added = write(template.copy(id = null, recurrence = null, keepExistingRule = false), dates)
            replace(g.copy(members = g.members + added))
            written += added.size
        }
        return written
    }

    /** Forgets events that no longer exist (deleted in another app). */
    suspend fun prune(exists: suspend (String) -> Boolean) {
        keep(groups.map { g -> g.copy(members = g.members.filterKeys { exists(it) }) })
    }

    private suspend fun write(draft: EventDraft, dates: List<LocalDate>): Map<String, LocalDate> =
        dates.mapNotNull { d -> runCatching { store.save(shift(draft, d).copy(id = null, lunarYearly = false)) }.getOrNull()?.let { it to d } }.toMap()

    private fun replace(g: LunarGroup) = keep(groups.map { if (it.id == g.id) g else it })

    private fun newId() = "L" + kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val KEY = "lunar.groups"

        /** [d] moved to start on [date], its length and times kept. */
        fun shift(d: EventDraft, date: LocalDate): EventDraft {
            val span = d.startDate.daysUntil(d.endDate)
            return d.copy(startDate = date, endDate = date.plus(DatePeriod(days = span)))
        }
    }
}
