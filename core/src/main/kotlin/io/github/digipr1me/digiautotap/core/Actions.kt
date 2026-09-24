package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * `actions.py`, carried over line for line: clicking with verification.
 *
 * Principle: after every action, check whether it actually took effect, and
 * check it against the counters rather than the picture. The counters are
 * unambiguous.
 *
 *   step                    paws minus 1
 *   step right in column 1  additionally metres plus 1
 *   pyramid                 claws minus 1, no paw
 *   skill                   fireballs minus 1, metres plus 2 or 3
 *
 * Do not check for an exact delta. A step costs one paw but may collect a
 * power-up at the same moment, which makes the delta +4 instead of -1.
 * Instead: if any counter moved plausibly the action happened, and only if
 * none moved is a banner checked. Without a confirmed change the bot
 * re-reads instead of clicking again, otherwise the bookkeeping drifts.
 */
object Actions {
    // Outcomes of an action
    const val OK = "ok"
    const val NO_EFFECT = "no_effect"
    const val BANNER_MOVE = "banner_move"
    const val BANNER_UNKNOWN = "banner_unknown"

    /** Counter unreadable, outcome unclear. Named in Python, never returned. */
    const val BLIND = "blind"

    /** `vision.classify_banner`'s name for this case. */
    const val BANNER_UNKNOWN_TEXT = "unknown"

    /** The pyramid was already gone, the click became a step. */
    const val MOVED_INSTEAD = "moved_instead"

    /** 'Insufficient ...' banner, a resource is used up. */
    const val INSUFFICIENT = "insufficient"

    /** What one look after an action found. */
    class Reading(val counters: Map<String, Long>, val banner: String?, val img: Mat?)

    /**
     * Merge counters across several frames.
     *
     * A single frame may fall inside an animation, making a counter
     * unreadable. Across three to four frames, practically all values show
     * up. The first readable value per counter is taken.
     *
     * [need] names the counters that have to be in hand, under the bare
     * names [Vision.readCounters] answers with ("paws"), and the fallback
     * is all seven. It used to be [Vision.COUNTER_KEYS], which are the ROI
     * names ("roi_paws"): the two sets share nothing, so a caller that
     * named no counters could never exit early. Measured over the 24
     * corpus frames that calibrate to a board (`_merge_probe.py`), on a
     * frame where all seven read at once: one grab and 0.09 s with the
     * bare names against four grabs and 1.13 s without.
     */
    fun mergeCounters(cap: Capture, vision: Vision, calib: Calib, tries: Int = 4,
                      pause: Double = 0.25, need: List<String>? = null,
                      sleep: (Double) -> Unit = { Thread.sleep((it * 1000).toLong()) }): Reading {
        val merged = LinkedHashMap<String, Long>()
        var banner: String? = null
        var bannerAsked = false
        var img: Mat? = null
        for (i in 0 until tries) {
            img = cap.grab()
            if (!bannerAsked) {
                banner = vision.classifyBanner(img, calib)
                // Python asks again while the answer is None, because None
                // is what `banner is None` tests; this flag says the same
                // thing in a language where null is not a sentinel by habit.
                bannerAsked = banner != null
            }
            for ((key, value) in vision.readCounters(img, calib)) {
                if (value != null && key !in merged) merged[key] = value
            }
            val want = need ?: Vision.COUNTER_NAMES
            if (want.all { it in merged }) break
            if (i < tries - 1) sleep(pause)
        }
        return Reading(merged, banner, img)
    }
}

/** What one [Actor.perform] came back with. */
class Performed(
    val state: String,
    val counters: Map<String, Long>,
    val img: Mat?,
    val deltas: Map<String, Long>,
)

/**
 * Performs actions and regulates the pace itself.
 *
 * The pace is a factor on all wait times. After every action confirmed on
 * the first try, it shrinks a little, so the bot gets faster. On any failure
 * it jumps straight back to cautious. That way the bot runs as fast as is
 * currently reliable, without you having to hand-tune values.
 */
class Actor(
    private val cap: Capture,
    private val vision: Vision,
    private val calib: Calib,
    clickDelay: Double = 1.5,
    settle: Double = 0.55,
    verifyPause: Double = 0.2,
    private val verifyTries: Int = 3,
    val dryRun: Boolean = true,
    minPace: Double = 0.60,
    adaptive: Boolean = true,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    private val sleep: (Double) -> Unit = { Thread.sleep((it * 1000).toLong()) },
) {
    // The three base values, and the three properties below that are those
    // values times the pace factor -- `base_click_delay` against
    // `click_delay` in Python, where the second is a @property.
    internal var baseClickDelay: Double = clickDelay
    internal var baseSettle: Double = settle
    internal var baseVerifyPause: Double = verifyPause

    var lastFrame: Mat? = null
        private set

    /** Pace factor, 1.0 is cautious, smaller is faster. */
    var pace: Double = 1.0
        internal set
    var minPace: Double = minPace
        internal set
    var adaptive: Boolean = adaptive
        internal set
    var cleanStreak: Int = 0
        internal set

    // ------------------------------------------------------------------------
    val clickDelay: Double get() = baseClickDelay * pace
    val settle: Double get() = baseSettle * pace
    val verifyPause: Double get() = baseVerifyPause * pace

    /** Action confirmed on the first try, so it is allowed to speed up. */
    fun onCleanSuccess() {
        cleanStreak += 1
        if (!adaptive) return
        // only start speeding up after three clean actions
        if (cleanStreak >= 3) pace = maxOf(minPace, pace * 0.90)
    }

    /**
     * Confirmed, but only on the second try. Slow down a little instead of
     * resetting the pace factor completely.
     */
    fun onSlowConfirm() {
        cleanStreak = 0
        if (adaptive) pace = minOf(1.0, pace * 1.08)
    }

    /** Something was not right straight away, become cautious at once. */
    fun onTrouble() {
        cleanStreak = 0
        if (adaptive) pace = 1.0
    }

    fun tempo(): String = "pace %.2f, tick %.2f s".format(pace, clickDelay)

    // ------------------------------------------------------------------------
    fun grab(): Mat {
        lastFrame = cap.grab()
        return lastFrame!!
    }

    fun readCounters(img: Mat? = null): Map<String, Long?> =
        vision.readCounters(img ?: grab(), calib)

    fun tapCell(row: Int, col: Int) {
        val (x, y) = vision.cellCenter(calib, row, col)
        if (dryRun) {
            log("    [dry run] click on r%dc%d, pixel %d,%d".format(row + 1, col + 1, x, y))
            return
        }
        cap.tap(x, y)
    }

    fun tapSkill() {
        val x = calib.skillButton[0]
        val y = calib.skillButton[1]
        if (dryRun) {
            log("    [dry run] click on the skill button, pixel %d,%d".format(x, y))
            return
        }
        cap.tap(x, y)
    }

    // ------------------------------------------------------------------------
    companion object {
        /**
         * Which counters can prove an action happened. Only these are waited
         * on, not all seven. Waiting on all of them would cost every
         * animation three screenshots, and that costs seconds.
         */
        val RELEVANT = mapOf(
            "step" to listOf("paws", "meters"),
            "destroy" to listOf("claws"),
            "skill" to listOf("fireballs", "meters"))
    }

    fun readCountersMerged(tries: Int = 3, pause: Double? = null,
                           need: List<String>? = null): Actions.Reading {
        val reading = Actions.mergeCounters(cap, vision, calib, tries = tries,
                                            pause = pause ?: verifyPause, need = need,
                                            sleep = sleep)
        lastFrame = reading.img
        return reading
    }

    // ------------------------------------------------------------------------
    /**
     * Perform an action and check whether it took effect.
     *
     * Does not check for an exact delta. A step costs one paw, but may
     * collect a power-up at the same moment. With a paw power-up the delta
     * is then +4 instead of -1. That used to be wrongly read as not
     * executed.
     *
     * Evidence instead
     *   any counter moved plausibly              ->  executed
     *   no counter moved and a banner is there   ->  input error
     *   no counter moved, no banner              ->  no effect
     */
    fun perform(action: Action, before: Map<String, Long>, worldColAtStart: Int): Performed {
        if (action.kind == "skill") tapSkill() else tapCell(action.cell!!.first, action.cell.second)

        if (dryRun) return Performed(Actions.OK, before, null, emptyMap())

        sleep(settle)
        var last = Performed(Actions.NO_EFFECT, before, null, emptyMap())
        // Python's loop variable outlives the loop and the tail below reads
        // it; here it is a variable of its own so that the reading is the same.
        var banner: String? = null
        for (attempt in 0 until verifyTries) {
            val reading = readCountersMerged(need = Actor.RELEVANT[action.kind])
            val after = reading.counters
            banner = reading.banner
            val deltas = LinkedHashMap<String, Long>()
            for ((k, v) in after) {
                val old = before[k] ?: continue
                if (v != old) deltas[k] = v - old
            }
            val state = judge(action, worldColAtStart, deltas, banner)
            last = Performed(state, after, reading.img, deltas)
            if (state == Actions.OK || state == Actions.MOVED_INSTEAD || state == Actions.INSUFFICIENT) {
                if (state == Actions.OK && attempt == 0) {
                    onCleanSuccess()
                } else if (state == Actions.OK) {
                    // a second try was needed. Not a failure, just a sign
                    // that it should not go any faster right now
                    onSlowConfirm()
                } else {
                    onTrouble()
                }
                return last
            }
            if (banner == Actions.BANNER_UNKNOWN_TEXT) {
                return Performed(Actions.BANNER_UNKNOWN, after, reading.img, deltas)
            }
            if (attempt < verifyTries - 1) sleep(baseClickDelay / verifyTries)
        }
        onTrouble()
        if (last.state != Actions.OK && last.deltas.isEmpty() && banner == "move") {
            return Performed(Actions.BANNER_MOVE, last.counters, last.img, last.deltas)
        }
        return last
    }

    // ------------------------------------------------------------------------
    /**
     * `Actor._judge`: what the counters say happened. Pure, and the one
     * thing `test_verify.py` calls directly.
     */
    fun judge(action: Action, @Suppress("unused") colAtStart: Int,
              deltas: Map<String, Long>, banner: String?): String {
        if (banner == "insufficient" && deltas.isEmpty()) {
            // a resource is empty. Which one follows from the action
            return Actions.INSUFFICIENT
        }
        if (deltas.isEmpty()) {
            if (banner == "move") return Actions.BANNER_MOVE
            if (banner == Actions.BANNER_UNKNOWN_TEXT) return Actions.BANNER_UNKNOWN
            return Actions.NO_EFFECT
        }

        val paw = deltas["paws"]
        val met = deltas["meters"]
        val claw = deltas["claws"]
        val fire = deltas["fireballs"]
        val pickup = listOf("top_orange", "top_green", "top_pink")
            .any { (deltas[it] ?: 0L) > 0 }
        val gain = listOf("paws", "claws", "fireballs").any { (deltas[it] ?: 0L) > 0 }

        if (action.kind == "destroy") {
            // A pyramid only costs one claw. There may be a power-up
            // underneath it, which immediately raises the counter again
            if (claw != null && claw < 0) return Actions.OK
            // If the pyramid was already gone, the click became a normal
            // step. This MUST be reported, otherwise the position
            // bookkeeping drifts out of sync
            if ((paw != null && paw < 0) || (met != null && met > 0)) return Actions.MOVED_INSTEAD
            if (gain || pickup) return Actions.OK
            return Actions.NO_EFFECT
        }

        if (action.kind == "skill") {
            if (fire != null && fire < 0) return Actions.OK
            if (met != null && (met == 2L || met == 3L)) return Actions.OK
            return Actions.NO_EFFECT
        }

        // A step. Every movement costs one paw, but may collect something at
        // the same time. So any plausible movement of a counter is enough
        if (paw != null && paw != 0L) return Actions.OK
        if (met != null && met > 0) return Actions.OK
        if (pickup || gain) return Actions.OK
        return Actions.NO_EFFECT
    }

    fun waitTick() = sleep(clickDelay)
}
