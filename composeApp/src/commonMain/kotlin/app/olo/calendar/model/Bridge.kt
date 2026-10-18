package app.olo.calendar.model

import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant

/*
 * The Galaxy bridge (docs/07): Samsung Calendar ⇄ the Google main calendar,
 * both ways, originals kept. Samsung Calendar stays the home of the Galaxy
 * ecosystem (phone, tab, book); the Google main calendar carries the same
 * events to the iPhone.
 *
 * Unit of work: a "series" — a standalone event, or a repeating event with its
 * changed and cancelled occurrences folded in. Each side's series is reduced to
 * a fingerprint of what a person sees (title, times, rule, exclusions,
 * reminders, place, notes). Equal fingerprints mean the pair agrees, whatever
 * bookkeeping the sync adapters add, so the bridge's own writes never echo.
 */

/** A series' content, normalised so both providers yield the same value for the same event. */
data class BridgeFields(
    val title: String,
    val location: String,
    val description: String,
    val allDay: Boolean,
    val startMillis: Long,
    val endMillis: Long,
    /** Wall-clock zone of a timed event; ignored for all-day events. */
    val timeZone: String,
    val rrule: String,
    /** Excluded occurrence starts, epoch millis. */
    val exdates: List<Long>,
    val reminderMinutes: List<Int>,
    /** Changed occurrences: original start → their own content (cancelled ones as [BridgeException.cancelled]). */
    val exceptions: List<BridgeException>,
)

data class BridgeException(
    val originalStart: Long,
    val cancelled: Boolean,
    val title: String = "",
    val startMillis: Long = 0,
    val endMillis: Long = 0,
)

/**
 * One series as the bridge sees it.
 * [synced]: the sync adapter has given the row its server id (_SYNC_ID), i.e. the
 * event really reached Google / Samsung Cloud. A row that vanishes before that was
 * refused or replaced by the adapter, not deleted by a person (docs/07 §8).
 */
data class BridgeItem(val id: String, val fields: BridgeFields, val synced: Boolean = true) {
    val fingerprint: String = bridgeFingerprint(fields)

    /** What a person would call "the same event" when two calendars already hold copies. */
    val matchKey: String = "${fields.title.trim().lowercase()}|${fields.allDay}|${fields.startMillis}"

    /**
     * Two series in one calendar that are the same event to a person: same title,
     * start, end, repeat rule and place, whatever their reminders, notes or colour (a
     * copy restored from a deletion record has lost those).
     */
    val twinKey: String = "$matchKey|${fields.endMillis}|${normaliseRrule(fields.rrule)}|${fields.location.trim().lowercase()}"

    /** How much a copy carries besides the essentials: the richer twin is the one kept. */
    val richness: Int get() = fields.reminderMinutes.size * 100 + fields.description.trim().length + fields.location.trim().length + fields.exceptions.size * 10
}

fun normaliseRrule(rule: String): String =
    rule.trim().removePrefix("RRULE:").uppercase().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        // WKST=MO is the default; Google adds it, Samsung does not.
        .filterNot { it == "WKST=MO" }
        .sorted().joinToString(";")

fun bridgeFingerprint(f: BridgeFields): String {
    val text = buildString {
        append(f.title.trim()).append('\u0001')
        append(f.location.trim()).append('\u0001')
        append(f.description.trim().replace("\r\n", "\n")).append('\u0001')
        append(f.allDay).append('\u0001')
        append(f.startMillis).append('\u0001')
        append(f.endMillis).append('\u0001')
        append(if (f.allDay) "" else f.timeZone).append('\u0001')
        append(normaliseRrule(f.rrule)).append('\u0001')
        append(f.exdates.distinct().sorted().joinToString(",")).append('\u0001')
        append(f.reminderMinutes.distinct().sorted().joinToString(",")).append('\u0001')
        f.exceptions.sortedBy { it.originalStart }.forEach { e ->
            append(e.originalStart).append(if (e.cancelled) "x" else "~${e.title.trim()}@${e.startMillis}-${e.endMillis}").append(';')
        }
    }
    return fnv64(text)
}

/** FNV-1a 64-bit over UTF-16 code units, as hex: stable on every platform. */
fun fnv64(s: String): String {
    var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (c in s) {
        h = h xor c.code.toLong()
        h *= 0x100000001b3L
    }
    return h.toULong().toString(16)
}

/** A pair the bridge keeps in step, with each side's fingerprint when they last agreed. */
data class BridgeLink(
    val samsungId: String,
    val googleId: String,
    val samsungPrint: String,
    val googlePrint: String,
    /** Each side was once seen with its server id: only then is its disappearance a real deletion. */
    val samsungConfirmed: Boolean = false,
    val googleConfirmed: Boolean = false,
)

fun encodeLinks(links: List<BridgeLink>): String =
    links.joinToString(";") {
        "${it.samsungId},${it.googleId},${it.samsungPrint},${it.googlePrint},${if (it.samsungConfirmed) 1 else 0},${if (it.googleConfirmed) 1 else 0}"
    }

fun decodeLinks(s: String?): List<BridgeLink> =
    s.orEmpty().split(';').mapNotNull { part ->
        val p = part.split(',')
        when {
            p.size < 4 || p[0].isEmpty() || p[1].isEmpty() -> null
            p.size >= 6 -> BridgeLink(p[0], p[1], p[2], p[3], p[4] == "1", p[5] == "1")
            else -> BridgeLink(p[0], p[1], p[2], p[3])
        }
    }

/** Repeat rules Google cannot hold (Samsung's lunar and vendor extensions): such events stay on Samsung. */
fun googleCanHold(f: BridgeFields): Boolean {
    val r = f.rrule.uppercase()
    return !r.contains("X-") && !r.contains("RSCALE") && !r.contains("LUNAR")
}

sealed interface BridgeOp {
    data class CreateInGoogle(val samsungId: String) : BridgeOp
    data class CreateInSamsung(val googleId: String) : BridgeOp
    /** Copy the source series over its partner. */
    data class UpdateGoogle(val samsungId: String, val googleId: String) : BridgeOp
    data class UpdateSamsung(val googleId: String, val samsungId: String) : BridgeOp
    data class DeleteGoogle(val googleId: String, val samsungId: String) : BridgeOp
    data class DeleteSamsung(val samsungId: String, val googleId: String) : BridgeOp
    /** Already equal: only the link's fingerprints move (or a new pair is recorded). */
    data class Agree(val samsungId: String, val googleId: String) : BridgeOp
}

data class BridgePlan(
    val ops: List<BridgeOp>,
    /** Both sides changed since they last agreed; Samsung's version wins (docs/07 §2). */
    val conflicts: Int,
    /** Deletions held back by the safety stop, waiting for the person to confirm. */
    val heldDeletes: Int,
    /** Links whose both sides are gone: dropped. */
    val dropped: List<BridgeLink>,
    /** Unpaired exact twins of an already paired event on the same side: not copied again. */
    val duplicates: Int = 0,
    /**
     * Deletions waiting for a person's yes. The bridge never deletes on its own
     * (0.8.3, after 103 events were lost): it lists what vanished on one side.
     */
    val pendingDeletes: List<BridgeOp> = emptyList(),
    /** A copy vanished before it ever reached its server: refused or replaced by the sync adapter. */
    val orphans: List<BridgeLink> = emptyList(),
    /** Events with repeat rules Google cannot hold: kept on Samsung only. */
    val unsupported: Int = 0,
) {
    fun count(kind: (BridgeOp) -> Boolean) = ops.count(kind)
    val toGoogle get() = count { it is BridgeOp.CreateInGoogle || it is BridgeOp.UpdateGoogle }
    val toSamsung get() = count { it is BridgeOp.CreateInSamsung || it is BridgeOp.UpdateSamsung }
    val paired get() = count { it is BridgeOp.Agree }
    val writes get() = ops.size - paired
}

/**
 * A pass carries deletions over by itself when there are at most this many
 * (docs/07 §18): a person deleting an event or two. More in one pass looks like
 * the 0.8.3 loss (a calendar cleared, an account gone) and waits for a yes.
 * Only pairs both sides had confirmed reach this point; the rest are orphans.
 */
const val AUTO_DELETE_LIMIT = 3

/**
 * What one pass of the bridge does. Pure: the engine reads both calendars,
 * calls this, then carries the ops out.
 */
fun planBridge(
    samsung: List<BridgeItem>,
    google: List<BridgeItem>,
    links: List<BridgeLink>,
    allowMassDelete: Boolean = false,
    /** Deletions a pass carries over without asking, if no more than this many ([AUTO_DELETE_LIMIT]). */
    autoDeleteLimit: Int = AUTO_DELETE_LIMIT,
    /** Samsung ids Google refused before (with the print they had): not sent again unless edited. */
    refused: Map<String, String> = emptyMap(),
): BridgePlan {
    val s = samsung.associateBy { it.id }
    val g = google.associateBy { it.id }
    val ops = mutableListOf<BridgeOp>()
    val deletes = mutableListOf<BridgeOp>()
    val dropped = mutableListOf<BridgeLink>()
    val orphans = mutableListOf<BridgeLink>()
    var conflicts = 0
    var unsupportedLinked = 0
    val linkedS = HashSet<String>()
    val linkedG = HashSet<String>()

    for (l in links) {
        val si = s[l.samsungId]
        val gi = g[l.googleId]
        if (si != null) linkedS += si.id
        if (gi != null) linkedG += gi.id
        when {
            si == null && gi == null -> dropped += l
            // Gone before it ever reached its server: the adapter refused or replaced it.
            // Never a reason to delete the partner; the pair is dropped and re-formed.
            si == null && !l.samsungConfirmed -> { orphans += l; linkedG -= l.googleId }
            gi == null && !l.googleConfirmed -> { orphans += l; linkedS -= l.samsungId }
            si == null -> deletes += BridgeOp.DeleteGoogle(l.googleId, l.samsungId)
            gi == null -> deletes += BridgeOp.DeleteSamsung(l.samsungId, l.googleId)
            si.fingerprint == gi.fingerprint -> {
                if (si.fingerprint != l.samsungPrint || gi.fingerprint != l.googlePrint) ops += BridgeOp.Agree(si.id, gi.id)
            }
            else -> {
                val sChanged = si.fingerprint != l.samsungPrint
                val gChanged = gi.fingerprint != l.googlePrint
                if (sChanged && gChanged) conflicts++
                when {
                    gChanged && !sChanged -> ops += BridgeOp.UpdateSamsung(gi.id, si.id)
                    // A rule Google cannot hold (lunar, X-) stays on Samsung; the old copy stays as it is.
                    !googleCanHold(si.fields) -> unsupportedLinked++
                    else -> ops += BridgeOp.UpdateGoogle(si.id, gi.id)
                }
            }
        }
    }

    // Unpaired series: pair equal-looking copies first (no duplicates on the first run), then copy the rest.
    // An unpaired exact twin of an event that is already paired on its own side is a
    // duplicate (two passes that overlapped, a double import): it is never copied across.
    val pairedSPrints = linkedS.mapNotNull { s[it]?.twinKey }.toSet()
    val pairedGPrints = linkedG.mapNotNull { g[it]?.twinKey }.toSet()
    var duplicates = 0
    var unsupported = 0
    val freeG = google.filter { it.id !in linkedG }.groupBy { it.matchKey }.mapValues { it.value.toMutableList() }
    val usedG = HashSet<String>()
    for (si in samsung) {
        if (si.id in linkedS) continue
        // Among same title-and-start candidates, the exact copy first, then the same event in
        // all but reminders and notes, and only then the first one (e.g. a first connect).
        val gi = freeG[si.matchKey]?.let { list ->
            (list.firstOrNull { it.fingerprint == si.fingerprint } ?: list.firstOrNull { it.twinKey == si.twinKey } ?: list.firstOrNull())
                ?.also { list.remove(it) }
        }
        when {
            gi != null -> {
                usedG += gi.id
                ops += when {
                    gi.fingerprint == si.fingerprint -> BridgeOp.Agree(si.id, gi.id)
                    googleCanHold(si.fields) -> BridgeOp.UpdateGoogle(si.id, gi.id)
                    // Paired as they are: Google keeps its copy, Samsung keeps the rule Google cannot hold.
                    else -> BridgeOp.Agree(si.id, gi.id)
                }
            }
            si.twinKey in pairedSPrints -> duplicates++
            !googleCanHold(si.fields) -> unsupported++
            refused[si.id] == si.fingerprint -> unsupported++
            else -> ops += BridgeOp.CreateInGoogle(si.id)
        }
    }
    for (gi in google) {
        if (gi.id in linkedG || gi.id in usedG) continue
        if (gi.twinKey in pairedGPrints) { duplicates++; continue }
        ops += BridgeOp.CreateInSamsung(gi.id)
    }

    // A few deletions go through (and are kept for undo by the engine); many wait for a person.
    val carry = allowMassDelete || deletes.size <= autoDeleteLimit
    if (carry) ops += deletes
    val held = if (carry) 0 else deletes.size
    return BridgePlan(ops, conflicts, held, dropped + orphans, duplicates, if (carry) emptyList() else deletes, orphans, unsupported + unsupportedLinked)
}

/**
 * Exact twins inside one calendar (same fingerprint): all but one to delete.
 * The one kept is a paired event when there is one, else the oldest (lowest id),
 * which is the person's original rather than a copy.
 */
fun planDedupe(items: List<BridgeItem>, paired: Set<String>): List<String> =
    items.groupBy { it.twinKey }.values.filter { it.size > 1 }.flatMap { group ->
        // Keep the richest copy (reminders, notes, place), then a paired one, then the oldest.
        val order = compareByDescending<BridgeItem> { it.richness }
            .thenBy { if (it.id in paired) 0 else 1 }
            .thenBy { it.id.toLongOrNull() ?: Long.MAX_VALUE }
        val keep = group.minWith(order)
        group.filter { it !== keep }.map { it.id }
    }

/** Twins in one calendar as titles, for the confirmation card. */
fun dedupeTitles(items: List<BridgeItem>, paired: Set<String>): List<String> {
    val gone = planDedupe(items, paired).toSet()
    return items.filter { it.id in gone }.map { it.fields.title }
}

/**
 * EXDATE as Android stores it ("20261020T010000Z,…", "TZID=Asia/Seoul;20261020T100000",
 * or all-day "20261020") → epoch millis.
 */
fun parseExdates(raw: String?): List<Long> {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return emptyList()
    var zone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.UTC
    val body = s.substringAfter(';', s).let { b ->
        val head = s.substringBefore(';', "")
        if (head.startsWith("TZID=")) runCatching { zone = kotlinx.datetime.TimeZone.of(head.removePrefix("TZID=")) }
        if (head.isEmpty() || head.contains(',')) s else b
    }
    return body.split(',').mapNotNull { v -> parseIcalTime(v.trim(), zone) }
}

private fun parseIcalTime(v: String, zone: kotlinx.datetime.TimeZone): Long? = runCatching {
    val y = v.substring(0, 4).toInt()
    val mo = v.substring(4, 6).toInt()
    val d = v.substring(6, 8).toInt()
    if (v.length < 15) {
        kotlinx.datetime.LocalDate(y, mo, d).atStartOfDayIn(kotlinx.datetime.TimeZone.UTC).toEpochMilliseconds()
    } else {
        val ldt = kotlinx.datetime.LocalDateTime(y, mo, d, v.substring(9, 11).toInt(), v.substring(11, 13).toInt(), v.substring(13, 15).toInt())
        val z = if (v.endsWith("Z")) kotlinx.datetime.TimeZone.UTC else zone
        ldt.toInstant(z).toEpochMilliseconds()
    }
}.getOrNull()


// --- undo for automatic deletions (docs/07 §18) ----------------------------------

private const val F = '\u001F'
private const val L = '\u001E'
private const val E = '\u001D'
private fun clean(t: String) = t.filterNot { it == F || it == L || it == E || it == '\u001C' }

/** One line of text for a series' fields, so a deleted event can be made again after its row is purged. */
fun encodeFields(f: BridgeFields): String = listOf(
    clean(f.title), clean(f.location), clean(f.description), f.allDay.toString(), f.startMillis.toString(), f.endMillis.toString(),
    clean(f.timeZone), clean(f.rrule), f.exdates.joinToString(L.toString()), f.reminderMinutes.joinToString(L.toString()),
    f.exceptions.joinToString(L.toString()) { listOf(it.originalStart, if (it.cancelled) 1 else 0, clean(it.title), it.startMillis, it.endMillis).joinToString(E.toString()) },
).joinToString(F.toString())

fun decodeFields(s: String): BridgeFields? {
    val p = s.split(F)
    if (p.size < 11) return null
    fun longs(t: String) = t.split(L).mapNotNull { it.toLongOrNull() }
    return runCatching {
        BridgeFields(
            title = p[0], location = p[1], description = p[2], allDay = p[3] == "true",
            startMillis = p[4].toLong(), endMillis = p[5].toLong(), timeZone = p[6], rrule = p[7],
            exdates = longs(p[8]), reminderMinutes = p[9].split(L).mapNotNull { it.toIntOrNull() },
            exceptions = p[10].split(L).filter { it.isNotEmpty() }.mapNotNull { e ->
                val q = e.split(E)
                if (q.size < 5) null else BridgeException(q[0].toLong(), q[1] == "1", q[2], q[3].toLong(), q[4].toLong())
            },
        )
    }.getOrNull()
}
