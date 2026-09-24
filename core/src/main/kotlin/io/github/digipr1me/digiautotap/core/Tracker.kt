package io.github.digipr1me.digiautotap.core

/**
 * `tracker.py`, carried over line for line: plausibility checks on counter
 * values.
 *
 * Two cases have to be covered.
 *
 * First, a single misread, for example 11 instead of 11,925 when the figure
 * stands in the bottom row and covers digits of the metre label. That value
 * is rejected.
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

    private val pending = HashMap<String, Pair<Long, Int>>()

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
        for ((key, newOrNull) in counters) {
            val new = newOrNull ?: continue
            val old = values[key]
            if (old == null) {
                values[key] = new
                continue
            }
            if (new == old) continue
            if (plausible(key, old, new)) {
                deltas[key] = new - old
                values[key] = new
                pending.remove(key)
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
                fixed.add(Triple(key, old, new))
            } else {
                bad.add(Triple(key, old, new))
            }
        }
        suspicious = bad
        resynced = fixed
        return deltas
    }

    fun get(key: String): Long? = values[key]
}
