package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import kotlin.math.min

/**
 * Tower & Ruins under the director: taps one point the player saved, every
 * few seconds, until the limit, until the main switch goes off, or until
 * something is wrong. tower.py's `TowerBot`, line for line where a line has
 * a counterpart on the phone (PLAN_ANDROID_3_DIRECTOR.md 3.2 and 5.3).
 *
 * The smallest skill there is, on purpose: it reads nothing, recognises
 * nothing and decides nothing. Tower and Ruins runs are screens where one
 * button does the whole thing, and the point is the player's. The one thing
 * that has to be right is where the tap lands, so the point is kept as a
 * fraction of the reference window -- what `Dungeon.gameRect` and the
 * director's own taps already speak -- and turned into a pixel on the frame
 * in front of it, whatever shape that frame has.
 *
 * **No screen of its own.** [worksOn] answers nothing, so the semi-automatic
 * mode never hands it a screen and [work] is never called; if it is called
 * anyway it parks with the reason instead of tapping a screen it was not
 * given. Tower is only ever a step of the fully automatic chain, and it is
 * in that chain while a point is saved ([hasBudget], chain.py:108).
 *
 * **What is not the PC's.** There is no pause here: the phone has one main
 * switch and off is a stop, checked between two taps, never in the middle of
 * one. And one guard the PC has no need of: a tap at a fixed spot is only
 * safe while the game is what is under it. On the emulator the window is
 * the game; on a phone the player can leave the game in the middle of the
 * run, and the next tap would land in whatever app they went to. So the
 * front window is asked before every tap ([game]), and the run parks the
 * moment the game is not it.
 *
 * `tower_has_point` and `tower_limit` are [Chain]'s, where every budget
 * question lives; [hasPoint] and [limit] below are the two names this skill
 * and its test call them by.
 *
 * [point] is `tower_fx` and `tower_fy` as saved, or null while none is;
 * [interval] is `tower_interval`; [minutes] is the minutes of the chain's
 * current Tower step (`chain.current().minutes`), 0 for "no bound". All three
 * are asked afresh every run: the limit is converted at the moment the step
 * starts, against whatever interval is saved then (chain.py, tower_limit).
 */
class TowerSkill(
    private val cap: Capture,
    private val point: () -> Pair<Double, Double>?,
    private val interval: () -> Double,
    private val minutes: () -> Int = { 0 },
    /** The game's package, or null to skip the front-window check. */
    private val game: () -> String? = { null },
    private val dryRun: Boolean = false,
    /** The main switch, asked between two taps. */
    private val on: () -> Boolean = { MainSwitch.on },
    private val log: (String) -> Unit = { HelperLog.line(it) },
    private val now: () -> Double = { System.nanoTime() / 1e9 },
    private val sleep: (Double) -> Unit = { Thread.sleep(Math.round(it * 1000.0)) },
    /** How many taps lie between two proofs that the screen is still there. */
    private val heartbeatTaps: Int = HEARTBEAT_TAPS,
) : Skill {

    override val key = "tower"
    override val name = "Tower & Ruins"

    override fun worksOn(screen: String): Boolean = false

    override fun hasBudget(): Boolean = point().let { hasPoint(it?.first, it?.second) }

    override fun work(img: Mat): Outcome {
        // Nothing done, so nothing counted: [lastCounts] is read after every
        // pass, and the last run's taps left standing here would go on the
        // card a second time.
        lastRun = null
        return Outcome.parked("$name has no screen of its own; it was handed the " +
            "${img.cols()} x ${img.rows()} frame of one, and taps nothing on it")
    }

    /** What the last run counted; null before the first. The TODAY card reads it. */
    var lastRun: Stats? = null
        private set

    /** What this run counted, for the TODAY card ([SkillStats], [Counted]). */
    val lastCounts: Map<String, Int>
        get() = lastRun?.let { mapOf("clicks" to it.clicks) } ?: emptyMap()

    /** The whole: taps the saved point where the game stands, until the limit or a stop. */
    override fun run(): Outcome {
        val stats = tap(limit(minutes(), interval()))
        lastRun = stats
        return when (stats.end) {
            Result.DONE -> Outcome.DONE
            Result.STOPPED -> Outcome.STOPPED
            else -> Outcome.parked(stats.reason)
        }
    }

    /**
     * What a run says about itself, `TowerBot.run`'s stats. [end] is how the
     * director will hear it: [Result.DONE] at the limit, [Result.STOPPED] for
     * the main switch, [Result.PARKED] for anything that could not go on.
     */
    class Stats(var clicks: Int = 0, var seconds: Double = 0.0, var reason: String = "",
                var end: Result = Result.DONE)

    /**
     * Taps the saved point up to [limit] times, 0 for no bound. Returns
     * when the limit is reached, when the switch is off, when the screen is
     * gone or is not the game any more, or when the point is not on the game.
     */
    fun tap(limit: Int): Stats {
        val start = now()
        val stats = Stats()
        val gap = interval()
        try {
            val saved = point()
            if (saved == null || !hasPoint(saved.first, saved.second)) {
                return park(stats, "no point is saved")
            }
            val (fx, fy) = saved

            val first = try {
                cap.grab()
            } catch (e: CaptureError) {
                log(e.message ?: "no frame")
                return park(stats, "could not reach the screen: ${e.message}")
            }
            // Refused rather than clamped: a settings file edited by hand, or a
            // version that stored something else, would otherwise tap a corner
            // of the screen for however long the run was meant to last.
            val (fx0, fy0, fw, fh) = Dungeon.GAME_IN_WINDOW.toList()
            if (!(fx in fx0..fx0 + fw && fy in fy0..fy0 + fh)) {
                first.release()
                log("the saved point is not on the game, not tapping")
                return park(stats, "the saved point is not on the game")
            }
            var (x, y) = aim(first, fx, fy)
            log("point %.3f / %.3f -> %d, %d on a %dx%d frame"
                .format(fx, fy, x, y, first.cols(), first.rows()))
            first.release()

            var n = 0
            while (true) {
                if (!on()) {
                    stats.reason = "stopped by the main switch"
                    stats.end = Result.STOPPED
                    break
                }
                val pkg = game()
                if (pkg != null) {
                    val front = cap.inFront()
                    if (front != pkg) {
                        stats.reason = "the game is not in front any more" + (front?.let { " ($it)" } ?: "")
                        stats.end = Result.PARKED
                        break
                    }
                }

                if (dryRun) log("  would tap $x, $y (dry run)") else cap.tap(x, y)
                n += 1
                stats.clicks = n

                if (limit > 0 && n >= limit) {
                    stats.reason = "reached the limit"
                    break
                }

                if (n % heartbeatTaps == 0) {
                    // A tap is never evidence of anything -- a gesture that
                    // went nowhere says nothing -- so this grab is the
                    // cheapest thing that is, and a CaptureError here ends
                    // the run with a reason instead of tapping into a dead
                    // service for however long is left.
                    val img = try {
                        cap.grab()
                    } catch (e: CaptureError) {
                        log(e.message ?: "no frame")
                        return park(stats, "lost the screen: ${e.message}")
                    }
                    try {
                        if (img.cols() > img.rows()) {
                            stats.reason = "the screen turned to landscape"
                            stats.end = Result.PARKED
                            break
                        }
                        val p = aim(img, fx, fy)
                        x = p.first
                        y = p.second
                    } finally {
                        img.release()
                    }
                    log("clicked $n times, going on")
                }

                sleepInterval(gap)
            }
        } catch (e: InterruptedException) {
            // The service is going away. The flag stays set for whoever
            // owns the thread, and the run says why it ended.
            Thread.currentThread().interrupt()
            stats.reason = "interrupted"
            stats.end = Result.STOPPED
        }
        stats.seconds = now() - start
        log(formatSummary(stats))
        return stats
    }

    private fun park(stats: Stats, why: String): Stats {
        stats.reason = why
        stats.end = Result.PARKED
        return stats
    }

    /** `to_pixel`: a fraction of the reference window, in this frame's pixels. */
    private fun aim(img: Mat, fx: Double, fy: Double): Pair<Int, Int> {
        val r = Dungeon.gameRect(img)
        return Py.roundInt(r.x0 + fx * r.gw) to Py.roundInt(r.y0 + fy * r.gh)
    }

    /**
     * The gap between two taps, in [TICK] slices.
     *
     * One plain sleep(interval) would make the switch feel dead for a whole
     * interval, and at 60 s that is a hang rather than a stop.
     */
    private fun sleepInterval(gap: Double) {
        var remaining = gap
        while (remaining > 0) {
            if (!on()) return
            val step = min(TICK, remaining)
            sleep(step)
            remaining -= step
        }
    }

    companion object {
        /** How finely the gap between taps is sliced, so a stop is felt within a tenth of a second. */
        const val TICK = 0.1

        /** How often the run proves the screen is still there. */
        const val HEARTBEAT_TAPS = 20

        /**
         * `chain.tower_has_point` and `chain.tower_limit`, which are
         * [Chain.towerHasPoint] and [Chain.towerLimit] now: the budget
         * questions live beside the chain that asks them, not in the skills
         * (PLAN_ANDROID_APP.md 4). Kept here as the two names this skill's
         * own test and its callers already use, and as nothing else.
         */
        fun hasPoint(fx: Double?, fy: Double?): Boolean = Chain.towerHasPoint(fx, fy)

        fun limit(minutes: Int, interval: Double): Int = Chain.towerLimit(minutes, interval)

        /** "44 clicks in 2 min 14 s, stopped by the main switch". */
        fun formatSummary(stats: Stats): String {
            val total = stats.seconds.toInt()
            val span = if (total >= 60) "${total / 60} min ${total % 60} s" else "$total s"
            return "${stats.clicks} clicks in $span, " + stats.reason.ifEmpty { "finished" }
        }
    }
}
