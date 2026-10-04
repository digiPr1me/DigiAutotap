package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import kotlin.math.ceil

/**
 * The plain main screen, asked over a few seconds: what every way in from
 * the main screen asks before its first tap -- World Search to its board,
 * the Meat Field, Chef's Special and Gekkomon Run through the Events window,
 * and EX Missions through the Missions tile (PLAN_ABSCHLUSS_1_3.md A5c, K2).
 *
 * Until 2026-10-03 each of them asked [Dungeon.autoButton] on one frame, and
 * a frame without the button ended the step -- "not the plain main screen,
 * cannot open ..." -- and with it the task for the rest of the chain's run.
 * The button is the hologram device's own Auto, and the game draws over it
 * by itself for a moment: the device's light when Auto Spend pulls with the
 * Super Hologram Device, the Tamer's level-up over the whole screen. On the
 * Poco on 2026-10-02 the passive round read the button at 19:14:17, World
 * Search's one look at 19:14:19 fell into the device's light, and the next
 * round read it again at 19:14:20; World Search was gone for the run, and at
 * 18:59 the same way under the gear window the device raises
 * (notes/world-search.md, "A way in asks for the main screen over a few
 * seconds, because the game draws over its auto button by itself").
 *
 * The reader stays as it is, and so does the question: a frame without the
 * button is still no main screen, and one with it is one. What changed is
 * that one frame without it is not the answer. Nothing is tapped while it
 * waits -- not the gear window's Equip, which `popup_ok` reads as OK, and
 * not a Stage Failed banner, which stays the caller's, as it always was.
 */
object MainScreen {
    /**
     * How long a way in waits for the main screen, in seconds. Measured on
     * LDPlayer instance 1 on 2026-10-03 at 1080 x 1920, the app stopped,
     * frames 0.3 to 0.6 s apart (staging/k2/measure.txt): of 8 pulls of the
     * Super Hologram Device under Auto Spend, 2 took the button on one frame
     * each, for at most 1.0 and 0.75 s between the frames that read it; the
     * Tamer's level-up on two frames, at most 1.3 and 1.7 s; 20 plain pulls
     * not at all. On the Poco the light stood between two rounds that read
     * the button 3 s apart. After a flash the main screen is read on two
     * looks running ([BEAT] apart, and a look costs up to a second on the
     * slowest phone read -- notes/formats.md, rule 15), so a flash is over
     * in 5 s. The gear window went in under 7 s on the Poco under the
     * player's hand (18:59:06 main, 18:59:08 the window, 18:59:13 main
     * again); on instance 1, with nobody's hand, it stood 90 s. 8 s waits
     * out the first and gives up on the second a few seconds later than the
     * one look did.
     */
    const val WAIT = 8.0

    /** Between two looks, as every two-look wait here (ExMissionsSkill.BEAT). */
    const val BEAT = 0.5

    /**
     * The looks [WAIT] holds at most. The clock ends the wait on the phone;
     * this ends it under a clock that does not move, a flow test's.
     */
    val LOOKS = ceil(WAIT / BEAT).toInt() + 1

    /** The question itself, the one every way in asked on its one frame. */
    fun reads(img: Mat): Boolean = Dungeon.autoButton(img) != null

    /**
     * [first], where it is the main screen ([reads]); else the first frame
     * of two running that are, within [WAIT] -- a frame the caller owns from
     * then on, and [grab]'s last, so that whatever [grab] measures on the way
     * (a skill's rectangle) is this frame's. Null where the switch went off
     * ([on]; nothing said, nothing kept) or the main screen did not come:
     * then [gaveUp] has been handed the last frame looked at and the seconds
     * waited, to say it in the caller's words and keep it. Taps nothing.
     * [isMain] is [reads], unless the caller asks the same reader through a
     * seam of its own (the bond tour's `autoButton`, which its flow tests
     * answer for).
     */
    fun settle(first: Mat, grab: () -> Mat, sleep: (Double) -> Unit, now: () -> Double,
               on: () -> Boolean, log: (String) -> Unit,
               isMain: (Mat) -> Boolean = MainScreen::reads,
               gaveUp: (Mat, Double) -> Unit): Mat? {
        if (isMain(first)) return first
        val begin = now()
        var last: Mat? = null
        var seen = false
        try {
            for (look in 0 until LOOKS) {
                if (!on()) return null
                sleep(BEAT)
                if (!on()) return null
                val img = grab()
                last?.release()
                last = img
                if (isMain(img)) {
                    if (seen) {
                        log("  the plain main screen after %.1f s -- going on".format(now() - begin))
                        last = null
                        return img
                    }
                    seen = true
                } else {
                    seen = false
                }
                if (now() - begin >= WAIT) break
            }
            gaveUp(last ?: first, now() - begin)
            return null
        } finally {
            last?.release()
        }
    }
}
