package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The Special Summon screens, painted, the way test_summon_flow.py paints
 * them: the yellow button live and dimmed, the mode dots, the white plate
 * with its blue X, the out-of-tickets dialog, the price in white or in red,
 * the General/Buddy tab pair and the View Ads row with its counter.
 *
 * Every colour is an HSV triple converted to BGR ([Paint.hsv]), the exact
 * number the reader's own comment was measured against, rather than a guess
 * at what "yellow" or "red" looks like in BGR. The frame is [Paint.W] x
 * [Paint.H], where `game_rect` is the whole image, and what every painter has
 * to pass is the real reader in [Summon] -- SummonSkillTest's first case
 * holds all of them to it.
 *
 * The one difference from the Python painter: there the bot-level cases hand
 * the patched readers plain dicts, so nothing under those cases is drawn at
 * all. Here the frames go through [Summon] itself, which is the stronger
 * proof and the only one a Kotlin port can make -- a state machine that
 * agrees with a stub it was written beside says nothing.
 */
object PaintSummon {

    // The yellow button live and behind a dialog: H 20 S 220, V 245 against
    // V 80, either side of YELLOW's own floor at 180.
    val YELLOW_LIVE = Paint.hsv(20, 220, 245)
    val YELLOW_DIM = Paint.hsv(20, 220, 80)
    val DOT_ORANGE = Paint.hsv(10, 200, 250)
    val DOT_INACTIVE = Paint.hsv(112, 90, 180)
    val RED = Paint.hsv(3, 220, 200)
    val WHITE = Scalar(235.0, 235.0, 235.0)
    val BLUE = Paint.hsv(100, 200, 220)
    val GREEN = Paint.hsv(78, 200, 220)
    // The out-of-tickets dialog, at the fractions and colours measured on the
    // eight stored frames of it (summon.py, "Insufficient ... Tickets").
    val CLOSE_PINK = Paint.hsv(148, 158, 223)
    val MOVE_BLUE = Paint.hsv(100, 255, 209)
    val EXIT_PLATE = Scalar(250.0, 250.0, 250.0)
    val EXIT_MARK = Paint.hsv(108, 165, 213)

    /** Where the painted buttons stand, as test_summon_flow.py has them. */
    val BUTTON = Dungeon.Button(0.75, 0.885, 0.25, 0.048)
    val ADS_BUTTON = Dungeon.Button(0.21, 0.885, 0.17, 0.046)
    /** The 15x Summon between them: the second blue of the row View Ads leads. */
    val MIDDLE_BUTTON = Dungeon.Button(0.48, 0.885, 0.19, 0.046)
    const val CLOSE_FX = 0.373
    const val CLOSE_FY = 0.588
    const val CLOSE_FW = 0.166
    const val CLOSE_FH = 0.029
    const val CLOSE_GAP = 1.23
    /** The dimmed plate behind the dialog, where the real one stands. */
    val DIMMED_BUTTON = Dungeon.Button(0.607, 0.960, 0.238, 0.048)

    private val FONT = Imgproc.FONT_HERSHEY_DUPLEX
    private val ADS_FONT = Imgproc.FONT_HERSHEY_SIMPLEX
    // The ad counter, "n /2", built to the proportions dungeon.counter_digits
    // and dungeon.read_digit were measured on: a slash a third again as tall as
    // the digits beside it, and a zero drawn as a solid ring.
    const val ADS_TEXT_SCALE = 0.85
    const val ADS_SLASH_W = 0.42
    const val ADS_SLASH_H = 1.35
    const val ADS_SLASH_STROKE = 0.14
    const val ADS_RING_W = 0.30
    const val ADS_RING_H = 0.92
    const val ADS_RING_STROKE = 0.18

    /**
     * One frame of a Special Summon screen, as test_summon_flow.py's `F`
     * names its state: what is on it, and nothing else.
     *
     * [phase] moves a block of artwork across the frame, so that two frames of
     * the same screen measure as having moved (`dungeon._same_screen`); the
     * same [phase] twice is a picture that has not moved at all, which is what
     * the frozen-screen case needs.
     */
    fun frame(
        button: Dungeon.Button? = null,
        red: Boolean = false,
        dots: Int = 0,
        exitX: Boolean = false,
        dialog: Boolean = false,
        general: Boolean = false,
        ads: Int = -1,
        phase: Int = 0,
    ): Mat {
        val img = Paint.blank()
        if (phase > 0) {
            val fx = 0.05 + 0.20 * (phase % 3)
            rect(img, fx, 0.42, fx + 0.20, 0.52, Scalar(60.0, 180.0, 90.0))
        }
        if (dots in 1..3) paintDots(img, dots)
        if (general) paintTabs(img)
        if (ads >= 0) paintAds(img, ads)
        if (button != null) {
            paintButton(img, button, YELLOW_LIVE)
            paintPrice(img, button, if (red) RED else WHITE)
        }
        if (exitX) paintExitX(img)
        if (dialog) paintNotEnough(img)
        return img
    }

    /** The mode screen the banners stand on: the dots, the button, and the X. */
    fun modeScreen(mode: Int, phase: Int = 0): Mat =
        frame(button = BUTTON, dots = mode, exitX = true, phase = phase)

    /** A result screen: the button and the X, no banners under them. */
    fun resultScreen(phase: Int = 0): Mat =
        frame(button = BUTTON, exitX = true, phase = phase)

    /** The out-of-tickets dialog over the dimmed screen. */
    fun notEnough(phase: Int = 0): Mat = frame(dialog = true, phase = phase)

    /** A draw animation: no button to aim at, and nothing else either. */
    fun animation(phase: Int = 0): Mat = frame(phase = phase)

    // ------------------------------------------------------------------------

    private fun rect(img: Mat, fx0: Double, fy0: Double, fx1: Double, fy1: Double, colour: Scalar) {
        Imgproc.rectangle(img, Point(fx0 * Paint.W, fy0 * Paint.H),
                          Point(fx1 * Paint.W, fy1 * Paint.H), colour, -1)
    }

    private fun box(img: Mat, at: Dungeon.Button, colour: Scalar) {
        val w = (at.fw * Paint.W).toInt()
        val h = (at.fh * Paint.H).toInt()
        val x = (at.fx * Paint.W - w / 2.0).toInt()
        val y = (at.fy * Paint.H - h / 2.0).toInt()
        Imgproc.rectangle(img, Point(x.toDouble(), y.toDouble()),
                          Point((x + w).toDouble(), (y + h).toDouble()), colour, -1)
    }

    fun paintButton(img: Mat, at: Dungeon.Button, colour: Scalar) = box(img, at, colour)

    /**
     * Three small discs in a row, the active one orange -- the radius and the
     * spacing test_summon_flow.py measures them at.
     */
    fun paintDots(img: Mat, active: Int, fy: Double = 0.28, spacing: Double = 0.03) {
        val radius = maxOf(2, (0.006 * Paint.W).toInt())
        for (i in 0 until 3) {
            val fx = 0.49 + (i - 1) * spacing
            val colour = if (i == active - 1) DOT_ORANGE else DOT_INACTIVE
            Imgproc.circle(img, Point((fx * Paint.W).toInt().toDouble(),
                                      (fy * Paint.H).toInt().toDouble()),
                           radius, colour, -1)
        }
    }

    /**
     * The General / Buddy tab pair, at the size it really measures: both tabs
     * 0.243 wide and 0.031 tall, General at 0.162, Buddy at 0.464.
     *
     * [fill] is the part that matters and the part a first version of the
     * Python helper got wrong. A tab is not a solid block of colour -- its
     * label is not in the colour mask -- and the real ones cover 0.785 of their
     * own box once find_buttons has closed the mask. Painted solid they clear
     * the area floor the live run actually fell through, and a test that paints
     * them solid passes either way and proves nothing. The number is the ADB
     * measurement, the smaller of the two the live run made.
     */
    fun paintTabs(img: Mat, fy: Double = 0.135, fw: Double = 0.243, fh: Double = 0.031,
                  fill: Double = 0.766) {
        val w = (fw * Paint.W).toInt()
        val h = maxOf(1, (fh * fill * Paint.H).toInt())
        for ((fx, colour) in listOf(0.162 to BLUE, 0.464 to GREEN)) {
            val x = (fx * Paint.W - w / 2.0).toInt()
            val y = (fy * Paint.H - h / 2.0).toInt()
            // A pixel short in each direction, because rectangle() includes
            // both corners.
            Imgproc.rectangle(img, Point(x.toDouble(), y.toDouble()),
                              Point((x + w - 1).toDouble(), (y + h - 1).toDouble()), colour, -1)
        }
    }

    /**
     * The View Ads row: the leftmost blue of the bottom row, a second blue
     * beside it (the game's 15x Summon), the yellow button that anchors the
     * row, and the "n /2" counter above View Ads.
     *
     * Both numbers count down -- "2 /2" is two still to watch, not two watched
     * (NOTES.md, the dungeon cards' counters).
     */
    fun paintAds(img: Mat, left: Int) {
        box(img, ADS_BUTTON, BLUE)
        box(img, MIDDLE_BUTTON, BLUE)
        val scale = ADS_TEXT_SCALE * (Paint.H / 1390.0)
        val digit = Imgproc.getTextSize("2", ADS_FONT, scale, 1, IntArray(1))
        val baseline = (ADS_BUTTON.fy - ADS_BUTTON.fh / 2.0) * Paint.H - 0.016 * Paint.H
        val gap = 0.4 * digit.width
        val slashW = ADS_SLASH_W * digit.height
        val slashH = ADS_SLASH_H * digit.height
        var x = ADS_BUTTON.fx * Paint.W - (2 * digit.width + slashW + 2 * gap) / 2.0
        if (left == 0) {
            // OpenCV's own zero is not the game's: at this size every one of
            // its fonts draws a ring whose squashed bitmap has two holes in
            // it, and dungeon.read_digit reads two holes as an 8. The game's
            // digits are a solid stroke, so the zero is drawn as one --
            // measured back out of read_digit, which answers 0 for it at
            // every size tried. test_passive_flow.py notes the same limit
            // from the other side: its painted badges avoid 4 and 9.
            val stroke = maxOf(1, (ADS_RING_STROKE * digit.height).toInt())
            Imgproc.ellipse(img, Point(x + digit.width / 2.0, baseline - digit.height / 2.0),
                            Size(ADS_RING_W * digit.height - stroke / 2.0,
                                 (ADS_RING_H * digit.height - stroke) / 2.0),
                            0.0, 0.0, 360.0, WHITE, stroke)
        } else {
            Imgproc.putText(img, "$left", Point(x, baseline), ADS_FONT, scale, WHITE, 1,
                            Imgproc.LINE_AA)
        }
        x += digit.width + gap
        // The slash, drawn rather than typed so that it is the tallest
        // character of the group whatever the digit beside it is: that is how
        // dungeon.counter_digits tells the counter's two halves apart.
        Imgproc.line(img, Point(x + slashW, baseline - slashH), Point(x, baseline), WHITE,
                     maxOf(2, (ADS_SLASH_STROKE * digit.height).toInt()), Imgproc.LINE_AA)
        x += slashW + gap
        Imgproc.putText(img, "2", Point(x, baseline), ADS_FONT, scale, WHITE, 1, Imgproc.LINE_AA)
    }

    /**
     * The price above the button, two characters, white or red. The crop it has
     * to land in reaches 0.045 to 0.008 of the game height above the button's
     * own top edge.
     */
    fun paintPrice(img: Mat, at: Dungeon.Button, colour: Scalar, text: String = "30") {
        val cx = at.fx * Paint.W
        val topOfButton = (at.fy - at.fh / 2.0) * Paint.H
        val scale = 0.55 * (Paint.H / 1390.0)
        val size = Imgproc.getTextSize(text, FONT, scale, 1, IntArray(1))
        val org = Point(cx - size.width / 2.0, topOfButton - 0.015 * Paint.H)
        Imgproc.putText(img, text, org, FONT, scale, colour, 1, Imgproc.LINE_AA)
    }

    /**
     * The white plate with its blue X, in the corner where the real one is.
     *
     * Painted as a plate with a stroked X across it rather than a solid patch,
     * because the thresholds it has to clear are shares of a crop: the real
     * button fills 0.75 of that crop with plate and 0.21 with mark, and a solid
     * block of either colour would sail through a floor it was meant to fall
     * below.
     */
    fun paintExitX(img: Mat, fx: Double = Summon.EXIT_BUTTON_FX, fy: Double = Summon.EXIT_BUTTON_FY,
                   plate: Boolean = true, mark: Boolean = true) {
        val half = (0.5 * Summon.EXIT_BUTTON_FW * Paint.W).toInt()
        val cx = (fx * Paint.W).toInt()
        val cy = (fy * Paint.H).toInt()
        if (plate) {
            Imgproc.rectangle(img, Point((cx - half).toDouble(), (cy - half).toDouble()),
                              Point((cx + half).toDouble(), (cy + half).toDouble()), EXIT_PLATE, -1)
        }
        if (mark) {
            // 0.42 of the plate either side of centre, stroked at 0.18 of it:
            // measured back out of exit_button over four window shapes.
            val arm = (0.42 * half).toInt()
            val thick = maxOf(1, (0.18 * half).toInt())
            Imgproc.line(img, Point((cx - arm).toDouble(), (cy - arm).toDouble()),
                         Point((cx + arm).toDouble(), (cy + arm).toDouble()), EXIT_MARK, thick)
            Imgproc.line(img, Point((cx - arm).toDouble(), (cy + arm).toDouble()),
                         Point((cx + arm).toDouble(), (cy - arm).toDouble()), EXIT_MARK, thick)
        }
    }

    /**
     * The dialog: a pink Close, a blue Move beside it, and the summon button
     * dimmed behind it.
     *
     * The third one is the whole recogniser. Three other dialogs in this game
     * wear a pink button beside a blue one within two per cent of this row, and
     * [dimmed] `= false` paints one of those -- it must not be recognised.
     */
    fun paintNotEnough(img: Mat, dimmed: Boolean = true, gap: Double = CLOSE_GAP,
                       row: Double = CLOSE_FY, moveFw: Double = CLOSE_FW) {
        box(img, Dungeon.Button(CLOSE_FX, row, CLOSE_FW, CLOSE_FH), CLOSE_PINK)
        box(img, Dungeon.Button(CLOSE_FX + gap * CLOSE_FW, row, moveFw, CLOSE_FH), MOVE_BLUE)
        if (dimmed) box(img, DIMMED_BUTTON, YELLOW_DIM)
    }
}
