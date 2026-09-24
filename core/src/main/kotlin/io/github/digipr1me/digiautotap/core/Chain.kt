package io.github.digipr1me.digiautotap.core

/**
 * Skill Chain bookkeeping, chain.py line for line: where a chain is, and
 * what comes next. Nothing here starts anything or reads the game; the
 * director does the starting, and this only remembers the order and
 * decides what comes next -- [advance] is the one thing that ever changes
 * [index].
 */
class Chain(steps: List<Step>, val repeat: Boolean = false,
            private val log: (String) -> Unit = { HelperLog.line(it) }) {

    /**
     * One entry in a chain. `minutes` is only read for a "tower" step --
     * every other key ignores it.
     */
    data class Step(val key: String, val minutes: Int = 0)

    val steps: List<Step> = steps.toList()
    var index = 0
        private set
    var round = 1
        private set
    var finished = false
        private set
    /** This round's skips, for the summary line. */
    val skipped = ArrayList<Pair<String, String>>()
    /**
     * Steps that have said they have nothing left for the rest of this
     * chain, and why -- see [retire]. Not cleared at a round boundary, which
     * is the whole point of it: the skips are this round's, this is the
     * run's.
     */
    val retired = LinkedHashMap<String, String>()
    /**
     * How many steps this round actually started something. A repeating
     * chain in which nothing can run -- every budget at 0, no summon mode
     * on, no tower point -- would otherwise skip its way round for ever. A
     * round that started nothing is the floor under that.
     */
    var ran = 0
        private set

    /** The Step about to run, or null once the chain is over. */
    fun current(): Step? = if (finished || steps.isEmpty()) null else steps[index]

    /** A step really started. Only this makes a round count as one in which something happened. */
    fun recordStart(key: String) { ran += 1 }

    /**
     * This step has nothing left to give for the rest of this chain.
     * Different from a skip, which is asked afresh every round: a skip says
     * "not now, on the settings as they stand", and settings can be changed
     * while a chain runs. This says "not before the game hands something
     * out again", which no round of this chain can reach.
     */
    fun retire(key: String, why: String) { retired.putIfAbsent(key, why) }

    /** Why this step is not being started any more, or null. */
    fun retiredReason(key: String): String? = retired[key]

    fun recordSkip(key: String, why: String) {
        skipped.add(key to why)
        log("  chain: skipping $key -- $why")
    }

    /**
     * The step at [index] is done. Move on, wrap for another round, or end
     * the chain -- in that order of preference.
     */
    fun advance() {
        index += 1
        if (index < steps.size) return
        index = 0
        if (!repeat) {
            finished = true
            return
        }
        if (ran == 0) {
            // Nothing in the whole round had anything to do, and the next
            // round would ask exactly the same questions of exactly the
            // same settings.
            finished = true
            log("  chain: nothing in the chain could run, stopping instead of going round again")
            return
        }
        round += 1
        skipped.clear()
        ran = 0
        log("  chain: round $round starting")
    }

    /** "2 rounds, skipped tower (no point set)" -- the closing log line. */
    fun summary(): String {
        val rounds = "$round round" + (if (round == 1) "" else "s")
        if (skipped.isEmpty()) return "$rounds, nothing skipped"
        return "$rounds, skipped " + skipped.joinToString(", ") { (k, why) -> "$k ($why)" }
    }

    /**
     * Why a step has nothing to do, chain.py's own free functions beside the
     * chain (chain.py:88 and down). Each of these is, in effect, the
     * `if not ...:` line at the top of the matching `_start_*` -- pulled out
     * so the same question can be asked without a dialog attached to the
     * answer. A warning box is right when a person is sitting there to click
     * it away and wrong at three in the morning, so a chain asks these and
     * skips.
     *
     * They live here because six sessions wrote the same six rules into six
     * skills: `FarmSkill.farmIsDue`, `TowerSkill.hasPoint`/`limit`, an
     * expression in `SummonSkill.hasBudget`, another in `DungeonSkill`, and
     * the lambdas in `CoreService`. Every one of them was right; what was
     * wrong was having six places where the next one could be changed alone
     * (PLAN_ANDROID_APP.md 4, the merge list). The skills call these now, and
     * none of them keeps a copy.
     */
    companion object {

        /** `chain.dungeon_has_budget` (chain.py:96): a dungeon with n > 0. */
        fun dungeonHasBudget(budgets: Map<Int, Int>): Boolean = budgets.values.any { it > 0 }

        /**
         * `chain.summon_has_mode` (chain.py:100): any mode at all. Python
         * asks `any(enabled.values())` of a dict whose keys are the modes;
         * here the caller hands the values, because on this side the three
         * modes are three fields of a Settings and not a dict.
         */
        fun summonHasMode(enabled: Collection<Boolean>): Boolean = enabled.any { it }

        /** `chain.mini_has_target` (chain.py:104): anything ticked at all. */
        fun miniHasTarget(wanted: Collection<String>): Boolean = wanted.isNotEmpty()

        /** `chain.tower_has_point` (chain.py:108): a Tower step is in only while a point is saved. */
        fun towerHasPoint(fx: Double?, fy: Double?): Boolean = fx != null && fy != null

        /**
         * `chain.farm_is_due` (chain.py:112): is a Meat Field visit worth
         * starting right now.
         *
         * Skip rather than retire (PLAN_MEAT_FIELD 6.3): the free seeds
         * refill every hour, so there is no "nothing left until the daily
         * reset" the way dungeon.py's budgets have, and retiring the step
         * would lock a long, repeating chain out of the field for hours
         * after the shortest timer that happened to be running when the
         * chain last reached it. Asked fresh on every round instead, the
         * same as the other has_* checks -- a chain step's "nothing to do"
         * is a question, not a state. None (nothing ever recorded, e.g.
         * right after this skill was added) counts as due: activating the
         * switch means visiting at once, and a chain step reaching it for
         * the first time is the same situation.
         */
        fun farmIsDue(dueAt: Double?, now: Double): Boolean = dueAt == null || dueAt <= now

        /**
         * `chain.tower_limit` (chain.py:69): taps for [minutes] at
         * [interval] seconds apart, at least 1 if the player asked for any
         * time at all.
         *
         * Tower has no notion of minutes, only of taps and of its own saved
         * interval, and it is not taught a second unit for this: the
         * conversion happens once, at the moment the step starts, against
         * whatever interval is saved then. So a Tower step that says "12
         * minutes" keeps meaning 12 minutes even if the interval is
         * tightened between two rounds of a repeating chain.
         *
         * 0 is TowerBot's own word for "no bound", which is what an interval
         * of 0 has to answer as well rather than dividing by it. Python's
         * `round` is half-to-even, so is [Py.roundInt].
         */
        fun towerLimit(minutes: Int, interval: Double): Int {
            if (minutes <= 0 || interval <= 0) return 0
            return maxOf(1, Py.roundInt(minutes * 60.0 / interval))
        }
    }
}
