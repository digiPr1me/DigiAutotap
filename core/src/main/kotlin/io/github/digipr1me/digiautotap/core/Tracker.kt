package io.github.digipr1me.digiautotap.core

/**
 * `tracker.py`, carried over line for line: plausibility checks on counter
 * values.
 *
 * Two cases have to be covered.
 *
 * First, a single misread, for example 11 instead of 11,925 when the figure
 * stands in the bottom row and covers digits of the metre label. That value
 * is rejected. (It is not always single: on 1920 x 1080 the same cut 477
 * came three times in four actions, one short of being taken as the truth.
 * A reading with digits missing is thrown away for good now -- [cutShort].)
 *
 * Second, a real jump the bot missed. If the same implausible value is read
 * several times in a row it is the truth and the stored value is stale, so
 * the tracker re-syncs. Without that the counter got stuck and reported a
 * wrong delta on every action; in one test run it claimed "metres +9" for
 * thirteen actions straight.
 */
object TrackerConst {
    val ONLY_UP = setOf("top_orange", "top_green", "top_pink")
    const val MAX_METER_STEP = 3
}

/**
 * Checks the counters against the game's rules and pulls itself straight
 * again.
 *
 * @param maxActionsPerFrame in reading mode a human plays and makes several
 *   steps per frame. The bot makes exactly one action, so this is 1 there.
 */
class CounterTracker(
    private val maxActions: Int = 1,
    private val acceptAfter: Int = 3,
) {
    /** The values believed, by short name ("paws", "meters", ...). */
    val values = LinkedHashMap<String, Long>()
    /** (key, old, new) this update rejected. */
    var suspicious: List<Triple<String, Long, Long>> = emptyList()
        private set
    /** (key, old, new) this update re-synced to. */
    var resynced: List<Triple<String, Long, Long>> = emptyList()
        private set
    /** (key, old, new) this update rejected as a reading cut short ([cutShort]); also in [suspicious]. */
    var cut: List<Triple<String, Long, Long>> = emptyList()
        private set

    private val pending = HashMap<String, Pair<Long, Int>>()

    /**
     * The counters whose value has been read twice alike, or reached by a
     * plausible step, or re-synced to -- the ones a shorter reading may be
     * called a cut of. A first reading is not: were it a misread with a digit
     * too many, the truth would look like a cut of it and never come in.
     */
    private val trusted = HashSet<String>()

    /**
     * A reading with digits missing: shorter than every value [key] can
     * plausibly have now, and its digits the beginning or the end of one of
     * them. Not in tracker.py, whose head calls this case "a single misread"
     * and lets it in after [acceptAfter] like any real jump. Measured
     * (PLAN_WORLD_SEARCH_FORMATE.md F19): every meter reading the format tour
     * and the skin tour threw away, twelve over 70 passes, was one --
     * 46760 as 760, 46836 as 836, 47064 as 47, 47103 as 7103, 47733 as 477
     * three times and as 47 once, 47614 as 14, 47165 as 166 (47166, one step
     * on), 47227 as 227, 47313 as 47 -- and live, on 2026-09-29, eight more
     * of the same kind, seven of them off a label that stood whole to the
     * eye (the reader's own question, PLAN F25). Three alike in a row are what
     * [acceptAfter] takes as the truth, and on 1920 x 1080 the fourth 477
     * was one reading away. A real change never looks like this: the metres
     * only rise, paws, claws and fireballs move by a few, the top counters
     * only rise, so no true new value has fewer digits than the old one --
     * short of the counter being reset, and a reset to a value that happens
     * to be the head or the tail of the old one is the one change this keeps
     * out. So a cut is thrown away and counted towards nothing, and whatever
     * [pending] was waiting for waits on (notes/world-search.md, "A number
     * with digits missing is not the number").
     */
    internal fun cutShort(key: String, old: Long, new: Long): Boolean {
        if (new < 0 || key !in trusted) return false
        val digits = new.toString()
        val window = when (key) {
            "meters" -> 0L..(TrackerConst.MAX_METER_STEP.toLong() * maxActions)
            "paws", "claws", "fireballs" -> (-6L * maxActions)..60L
            else -> 0L..0L
        }
        for (d in window) {
            val v = old + d
            if (v < 0) continue
            val s = v.toString()
            if (s.length > digits.length && (s.startsWith(digits) || s.endsWith(digits))) return true
        }
        return false
    }

    private fun plausible(key: String, old: Long, new: Long): Boolean {
        val delta = new - old
        if (key == "meters") return delta in 0..(TrackerConst.MAX_METER_STEP.toLong() * maxActions)
        if (key in TrackerConst.ONLY_UP) return delta >= 0
        if (key == "paws" || key == "claws" || key == "fireballs") {
            // down by at most as many actions as are possible, up only
            // through power-ups, which are small
            return delta in (-6L * maxActions)..60L
        }
        return true
    }

    /** Takes the plausible values over and gives back the deltas. */
    fun update(counters: Map<String, Long?>): Map<String, Long> {
        val deltas = LinkedHashMap<String, Long>()
        val bad = ArrayList<Triple<String, Long, Long>>()
        val fixed = ArrayList<Triple<String, Long, Long>>()
        val short = ArrayList<Triple<String, Long, Long>>()
        for ((key, newOrNull) in counters) {
            val new = newOrNull ?: continue
            val old = values[key]
            if (old == null) {
                values[key] = new
                continue
            }
            if (new == old) {
                trusted.add(key)
                continue
            }
            if (plausible(key, old, new)) {
                deltas[key] = new - old
                values[key] = new
                pending.remove(key)
                trusted.add(key)
                continue
            }
            if (cutShort(key, old, new)) {
                // Thrown away and counted towards nothing (F19, [cutShort]).
                bad.add(Triple(key, old, new))
                short.add(Triple(key, old, new))
                continue
            }

            // Implausible. Count how often the same value has come now.
            val (seen, before) = pending[key] ?: (null to 0)
            val count = if (seen == new) before + 1 else 1
            pending[key] = new to count
            if (count >= acceptAfter) {
                // The same value several times over, so the stored one is stale.
                values[key] = new
                pending.remove(key)
                trusted.add(key)
                fixed.add(Triple(key, old, new))
            } else {
                bad.add(Triple(key, old, new))
            }
        }
        suspicious = bad
        resynced = fixed
        cut = short
        return deltas
    }

    fun get(key: String): Long? = values[key]
}
