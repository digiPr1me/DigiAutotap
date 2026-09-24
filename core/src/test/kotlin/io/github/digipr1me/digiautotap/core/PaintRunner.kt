package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Painted frames for the runner's flow test, beside Paint.kt and on the
 * same window shape (805 x 1390, where game_rect is the whole image). Every
 * frame here goes through the **real** Runner.kt readers, so the flow test
 * proves the loop and the readers together. Every colour and place is
 * runner.py's own measurement, quoted where it is used; nothing here is a
 * game image, these are rectangles in the hues the readers measure.
 *
 * The score is not painted. Its digits are the game's own font, read by
 * Dungeon.readDigit against the shapes it was measured on; a Hershey glyph
 * is not one of those (PaintFarm says the same). The cap is therefore
 * exercised through its time fallback, RUN_TIME_CAP, and the score's own
 * reading is the oracle test's business on the corpus frames.
 */
object PaintRunner {

    const val W = Paint.W
    const val H = Paint.H

    // ------------------------------------------------------------------------
    // The colours
    // ------------------------------------------------------------------------
    // The event's blue box: Runner.EVENT_BLUE, H 98-110, S >= 180, V 100-190;
    // measured H 101-106 S 233 V 139.
    private val EVENT_BOX = Paint.hsv(103, 233, 139)
    // The pink "Play Game": Runner.PINK_TEXT, H 140-170, S >= 120, V >= 150;
    // measured H 142-154.
    private val PLAY_PINK = Paint.hsv(148, 200, 230)
    // The Quit button: Runner.QUIT_PINK, H 143-157, S 120-200, V >= 180;
    // measured H 148-152 S 158 V 223.
    private val QUIT = Paint.hsv(150, 158, 223)
    // Continue: Runner.CONTINUE_CYAN, H 95-108, S >= 200, V >= 150; measured
    // H 100 S 255 V 209.
    private val CONTINUE = Paint.hsv(100, 255, 209)
    // The dark plate the run dialogs carry: V 77 S 212 on both, against V 139
    // on the game's prompts (Runner.PLATE_V_MAX 105).
    private val PLATE = Paint.hsv(103, 212, 77)
    // The Missions window: Runner.MISSION_BLUE, H 100-112, S >= 150, V >= 120.
    // The header block is painted brighter than the event's box so that
    // Runner.EVENT_BLUE (V <= 190) cannot take it for the Events dialog.
    private val MISSION = Paint.hsv(106, 233, 200)
    private val TAB = Paint.hsv(102, 255, 206)
    // Claim: Runner.CLAIM_YELLOW, H 20-35, S >= 150, V >= 200; measured H 26-30
    // S 213 V 255.
    private val CLAIM = Paint.hsv(28, 213, 255)
    // The Reward sheet: Runner.REWARD_BLUE with V inside Runner.REWARD_V
    // (130-165); measured V 148-149.
    private val REWARD = Paint.hsv(108, 233, 148)
    // The dimmed X under the Missions window: V 108 S 24 (Runner.DIMMED_X_V,
    // DIMMED_X_S_MAX).
    private val DIMMED_X = Paint.hsv(0, 24, 108)
    private val WHITE = Scalar(255.0, 255.0, 255.0)
    // The X's blue mark: Summon.EXIT_MARK, H 98-118, S 110-220, V >= 140.
    private val X_MARK = Paint.hsv(108, 165, 200)
    private const val X_HALF = Summon.EXIT_BUTTON_FW / 2
    private const val X_MARK_HALF = 0.0125

    // The road. The red cube: Runner.OBSTACLE_RED, H 0-8, S >= 180; measured
    // H 0/179 S 240. The spike: H 170-177 S 214, which is OBSTACLE_MAGENTA's
    // upper end. The glitch block: H 145-165, S >= 180 -- painted at S 220,
    // above QUIT_PINK's S 200, so that a block at the button row's height
    // could never read as a Quit.
    private val CUBE = Paint.hsv(3, 240, 200)
    private val SPIKE = Paint.hsv(173, 214, 200)
    private val GLITCH = Paint.hsv(155, 220, 200)
    // The orbs: Runner.ORB, H 138-158, S 60-140, V >= 225.
    private val ORB = Paint.hsv(148, 100, 240)
    // The egg bar: Runner.BAR_GREEN H 35-90 S >= 120 V >= 120, BAR_PINK
    // H 135-170 S >= 150 V >= 150.
    private val BAR_GREEN = Paint.hsv(60, 200, 200)
    private val BAR_PINK = Paint.hsv(150, 200, 200)

    private fun rect(img: Mat, fx0: Double, fy0: Double, fx1: Double, fy1: Double, colour: Scalar) {
        val x0 = (maxOf(0.0, fx0) * W).toInt()
        val x1 = (minOf(1.0, fx1) * W).toInt()
        if (x1 <= x0) return
        Imgproc.rectangle(img, Point(x0.toDouble(), fy0 * H), Point(x1.toDouble(), fy1 * H), colour, -1)
    }

    /** The white X with its blue mark, in the corner every screen of this event has it. */
    private fun exitX(img: Mat) {
        val hx = X_HALF; val hy = X_HALF * W / H.toDouble()
        rect(img, Summon.EXIT_BUTTON_FX - hx, Summon.EXIT_BUTTON_FY - hy,
             Summon.EXIT_BUTTON_FX + hx, Summon.EXIT_BUTTON_FY + hy, WHITE)
        val mx = X_MARK_HALF; val my = X_MARK_HALF * W / H.toDouble()
        rect(img, Summon.EXIT_BUTTON_FX - mx, Summon.EXIT_BUTTON_FY - my,
             Summon.EXIT_BUTTON_FX + mx, Summon.EXIT_BUTTON_FY + my, X_MARK)
    }

    /** The same plate, dimmed by the Missions window over it. */
    private fun dimmedX(img: Mat) {
        val hx = X_HALF; val hy = X_HALF * W / H.toDouble()
        rect(img, Summon.EXIT_BUTTON_FX - hx, Summon.EXIT_BUTTON_FY - hy,
             Summon.EXIT_BUTTON_FX + hx, Summon.EXIT_BUTTON_FY + hy, DIMMED_X)
    }

    /** The plain main screen with the nav bar: what go_to_event starts from. */
    fun mainScreen(): Mat = PaintFarm.mainScreen()

    /** The Events window: one solid box of the event's blue, fx 0.160-0.793 fy 0.245-0.782. */
    fun eventsDialog(): Mat {
        val img = Paint.blank()
        rect(img, 0.160, 0.245, 0.793, 0.782, EVENT_BOX)
        return img
    }

    /** The event page: "Play" at fx 0.188-0.297 fy 0.664-0.699, and the X. */
    fun eventPage(): Mat {
        val img = Paint.blank()
        rect(img, 0.188, 0.664, 0.297, 0.699, PLAY_PINK)
        exitX(img)
        return img
    }

    /**
     * The blue box and the dark plate both run dialogs stand on.
     *
     * The plate is painted as the whole plate, not as the strip the reader
     * samples: `darkPlate` measures it as a distance above the Quit button
     * now, and a painted frame that is only the old strip would pass or fail
     * on where that strip happens to land. Measured on the three stored
     * dialogs, the plate is dark from fy 0.38 down to just above the button
     * at 0.592.
     */
    private fun runDialog(): Mat {
        val img = Paint.blank(20.0)
        rect(img, 0.13, 0.30, 0.87, 0.70, EVENT_BOX)
        rect(img, Runner.PLATE_BAND[0], 0.38, Runner.PLATE_BAND[1], 0.56, PLATE)
        return img
    }

    /** "Current Record": Quit centred, fx 0.375-0.570, alone on its row. */
    fun resultDialog(): Mat {
        val img = runDialog()
        rect(img, 0.375, 0.592, 0.570, 0.633, QUIT)
        return img
    }

    /** The pause menu: Quit at fx 0.245-0.441, Continue at 0.505-0.698. */
    fun pauseDialog(): Mat {
        val img = runDialog()
        rect(img, 0.245, 0.592, 0.441, 0.633, QUIT)
        rect(img, 0.505, 0.592, 0.698, 0.633, CONTINUE)
        return img
    }

    /** Where the n-th Claim of a list stands: the first at fy 0.471, a card apart each. */
    fun claimAt(i: Int): Explore.Target = Explore.Target(0.683, 0.471 + 0.10 * i)

    /**
     * The Missions window: the header block fx 0.161-0.791 fy 0.280-0.414,
     * the Daily Missions tab fx 0.229-0.475 fy 0.809-0.835, [claims] yellow
     * Claim buttons down the right column (0.126 wide, 0.028 tall) and the
     * dimmed X under it all.
     */
    fun missions(claims: Int): Mat {
        val img = Paint.blank()
        rect(img, 0.161, 0.280, 0.791, 0.414, MISSION)
        rect(img, 0.229, 0.809, 0.475, 0.835, TAB)
        for (i in 0 until claims) {
            val c = claimAt(i)
            rect(img, c.fx - 0.063, c.fy - 0.014, c.fx + 0.063, c.fy + 0.014, CLAIM)
        }
        dimmedX(img)
        return img
    }

    /** The Reward sheet: a full-width box of the darker blue, and "Tap to close" in white along fy 0.82. */
    fun rewardSheet(): Mat {
        val img = Paint.blank()
        rect(img, 0.005, 0.226, 0.947, 0.685, REWARD)
        rect(img, 0.42, 0.812, 0.53, 0.828, WHITE)
        return img
    }

    /** One thing on the road, as the world wants it painted. */
    class Thing(
        /** "cube", "spike" or "glitch": what runner.py met on 2026-09-19 and what it answers each with. */
        val kind: String,
        /** Left edge, window fraction; may run past the right edge, the rectangle is clipped. */
        val fx: Double,
    ) {
        val w: Double get() = when (kind) { "cube" -> 0.12; "spike" -> 0.075; else -> 0.10 }
        val bottom: Double get() = when (kind) { "cube" -> 0.70; "spike" -> 0.66; else -> 0.50 }
        val h: Double get() = when (kind) { "cube" -> 0.08; "spike" -> 0.33; else -> 0.07 }
        /** What beats it: the plan's table, 4.1. */
        val answer: String get() = if (kind == "cube") Runner.JUMP_KIND else Runner.SLIDE_KIND
        val colour: Scalar get() = when (kind) { "cube" -> CUBE; "spike" -> SPIKE; else -> GLITCH }
    }

    /**
     * A run frame: the egg bar with [green] and [pink] shares of its strip,
     * the things on the road, and a row of orbs at jump height with its left
     * edge at [orbsAt] where one is wanted.
     */
    fun run(things: List<Thing> = emptyList(), green: Double = 0.0, pink: Double = 0.0,
            orbsAt: Double? = null): Mat {
        val img = Paint.blank()
        val (bx0, bx1, by0, by1) = Runner.BAR.toList()
        if (green > 0) rect(img, bx0, by0, bx0 + green * (bx1 - bx0), by1, BAR_GREEN)
        if (pink > 0) rect(img, bx1 - pink * (bx1 - bx0), by0, bx1, by1, BAR_PINK)
        for (t in things) rect(img, t.fx, t.bottom - t.h, t.fx + t.w, t.bottom, t.colour)
        if (orbsAt != null) {
            for (i in 0 until 3) rect(img, orbsAt + 0.07 * i, 0.52, orbsAt + 0.07 * i + 0.05, 0.56, ORB)
        }
        return img
    }
}
