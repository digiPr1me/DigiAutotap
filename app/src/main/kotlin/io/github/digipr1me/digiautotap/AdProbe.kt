package io.github.digipr1me.digiautotap

import android.os.SystemClock
import io.github.digipr1me.digiautotap.core.HelperLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The measurement PLAN_WERBUNG.md 4 asked for: while another activity of
 * the game's own package is in front -- an ad, by the manifest's table in
 * the plan's section 1, or something nobody has named yet -- which class it
 * is and how long it stands in front, every [EVERY_MS]. It taps nothing and
 * reads nothing for a skill, and since 1.3's fourth candidate it writes
 * nothing but lines in the log: until then it kept a frame and the node
 * tree beside it in `debug_ad`, with `ad_log.txt`, for the report to carry
 * (notes/reports.md, "Nothing is sent and no picture of the game is kept:
 * the player shares the log"). The lines are what make a shared log
 * readable around an ad.
 */
class AdProbe(private val svc: DigiAutotapService) {

    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    /** When the current stretch of a foreign activity began, elapsedRealtime; 0 while there is none. */
    private var since = 0L
    private var lastClass: String? = null

    fun start() {
        timer.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { line("probe failed: $it") } },
                                     EVERY_MS, EVERY_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() = timer.shutdownNow().let {}

    /** From the service's window event: the class in front changed. */
    fun noted(pkg: String, cls: String?) {
        line("in front: $pkg / ${cls ?: "?"}")
    }

    private fun tick() {
        val game = svc.gamePackage ?: return
        val pkg = svc.inFront()
        val cls = svc.frontClass
        val now = SystemClock.elapsedRealtime()
        val foreign = pkg == game && cls != null && cls != GAME_ACTIVITY
        if (!foreign) {
            if (since != 0L) {
                line("foreign stretch over after ${(now - since) / 1000.0} s; " +
                    "in front now: $pkg / ${cls ?: "?"}")
                since = 0L
            }
            return
        }
        if (since == 0L) {
            since = now
            line("foreign stretch begins: $cls")
        }
        if (cls != lastClass) {
            lastClass = cls
            line("foreign class ${(now - since) / 1000.0} s in: $cls")
        }
    }

    fun line(text: String) {
        HelperLog.line("ad probe: $text")
    }

    companion object {
        /** The game's own activity (PLAN_WERBUNG.md 1): everything else of its package is foreign. */
        const val GAME_ACTIVITY = io.github.digipr1me.digiautotap.core.Ads.GAME_ACTIVITY
        /** The plan's pace, and far above the screenshot floor of 350 ms. */
        const val EVERY_MS = 2000L
    }
}
