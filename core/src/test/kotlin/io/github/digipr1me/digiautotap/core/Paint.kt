package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Painted frames for the flow tests, the way test_passive_flow.py,
 * test_dungeon_flow.py and test_wake_flow.py paint theirs: a window frame
 * of 805 x 1390 (game_rect is the whole image there), the game's colours
 * where a reader measures them, and nothing else. What each painter has to
 * pass is the real reader, and DirectorTest's first case holds every one
 * of them to `classify`'s answer.
 */
object Paint {
    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    const val W = 805
    const val H = 1390

    fun blank(grey: Double = 30.0): Mat = Mat(H, W, CvType.CV_8UC3, Scalar(grey, grey, grey))

    private fun rect(img: Mat, fx0: Double, fy0: Double, fx1: Double, fy1: Double, colour: Scalar) {
        Imgproc.rectangle(img, Point(fx0 * W, fy0 * H), Point(fx1 * W, fy1 * H), colour, -1)
    }

    /** One BGR colour from the HSV numbers measured on the real frames (test_dungeon_flow.hsv). */
    fun hsv(h: Int, s: Int, v: Int): Scalar {
        val one = Mat(1, 1, CvType.CV_8UC3, Scalar(h.toDouble(), s.toDouble(), v.toDouble()))
        val bgr = Mat()
        Imgproc.cvtColor(one, bgr, Imgproc.COLOR_HSV2BGR)
        val px = bgr.get(0, 0)
        one.release(); bgr.release()
        return Scalar(px[0], px[1], px[2])
    }

    /** The blue disc that says the main screen is in front and clear (test_passive_flow). */
    fun mainScreen(): Mat {
        val img = blank()
        val radius = (0.053 * W / 2).toInt()
        Imgproc.circle(img, Point((0.361 * W).toInt().toDouble(), (0.765 * H).toInt().toDouble()),
                       radius, Scalar(230.0, 140.0, 50.0), -1)
        return img
    }

    /**
     * The main screen with its battle going: the auto button crisp, and a
     * block of artwork that stands somewhere else in every frame, so that
     * two frames in a row measure well over IDLE_SHARE.
     */
    fun battle(phase: Int): Mat {
        val img = mainScreen()
        val fx = 0.05 + 0.30 * (phase % 3)
        rect(img, fx, 0.30, fx + 0.30, 0.55, Scalar(60.0, 180.0, 90.0))
        return img
    }

    /**
     * A stage inside a dungeon: no auto button, the Give Up bar centred at
     * the very bottom (recognise's BATTLE), and moving artwork. classify has
     * no name for it -- UNKNOWN, stand still.
     */
    fun dungeonBattle(phase: Int): Mat {
        val img = blank()
        val fx = 0.05 + 0.30 * (phase % 3)
        rect(img, fx, 0.30, fx + 0.30, 0.55, Scalar(60.0, 180.0, 90.0))
        rect(img, 0.35, 0.915, 0.65, 0.945, Scalar(230.0, 150.0, 40.0))
        return img
    }

    /**
     * The dungeon list: three blue cards of the real ones' width and place
     * (CARD_X 0.49, CARD_W 0.72 to 0.85, under CARD_H_MAX). [shift] moves
     * them down by that many card heights, which is the player scrolling.
     */
    fun dungeonList(shift: Double = 0.0): Mat {
        val img = blank()
        for (i in 0 until 3) {
            val fy = 0.30 + 0.16 * i + 0.12 * shift
            rect(img, 0.10, fy - 0.06, 0.88, fy + 0.06, Scalar(230.0, 150.0, 40.0))
        }
        return img
    }

    // Measured over the Cancel button of real frames of all three prompts:
    // the in-game pink at hue 147-149 saturation 147, the exit prompt's
    // grey-blue at hue 100-110 saturation 151, and the dialog body behind
    // both at hue 103 saturation 233 (test_dungeon_flow).
    val PINK = hsv(148, 147, 225)
    val GREY = hsv(103, 151, 159)
    val BODY = hsv(103, 233, 139)
    val PINK_OK = Dungeon.Button(0.602, 0.599, 0.195, 0.04)
    val GREY_OK = Dungeon.Button(0.561, 0.652, 0.153, 0.04)

    /**
     * A confirmation prompt, built to the real one's proportions
     * (test_dungeon_flow.prompt): pink is one of the three in-game prompts,
     * grey is "Exit the game?". Cancel deliberately does not sit where the
     * mirror of OK would put it -- measured 0.34 against a mirror of 0.398.
     */
    fun prompt(pink: Boolean): Mat {
        val (button, ok) = if (pink) PINK to PINK_OK else GREY to GREY_OK
        val img = blank(20.0)
        rect(img, 0.13, ok.fy - 0.22, 0.87, ok.fy + 0.06, BODY)
        rect(img, 0.25, ok.fy - 0.021, 0.435, ok.fy + 0.020, button)
        rect(img, ok.fx - ok.fw / 2, ok.fy - 0.021, ok.fx + ok.fw / 2, ok.fy + 0.020,
             Scalar(230.0, 150.0, 40.0))
        return img
    }

    /**
     * The Stage Failed banner over the main screen (test_wake_flow): white
     * first and thicker, red over it -- the game outlines its headlines, and
     * that outline is what tells the banner from a red stage. The auto
     * button stays where it is, in the blue range, which is why classify
     * asks for the banner first.
     */
    fun stageFailed(): Mat {
        val img = mainScreen()
        for ((text, fy) in listOf("Stage" to 0.13, "Failed..." to 0.19)) {
            val x = (W * (if (text == "Stage") 0.30 else 0.22)).toInt()
            val y = (H * fy).toInt()
            for ((dx, dy) in listOf(-3 to 0, 3 to 0, 0 to -3, 0 to 3)) {
                Imgproc.putText(img, text, Point((x + dx).toDouble(), (y + dy).toDouble()),
                                Imgproc.FONT_HERSHEY_SIMPLEX, 2.0, Scalar(255.0, 255.0, 255.0), 7)
            }
            Imgproc.putText(img, text, Point(x.toDouble(), y.toDouble()),
                            Imgproc.FONT_HERSHEY_SIMPLEX, 2.0, Scalar(40.0, 40.0, 220.0), 6)
        }
        return img
    }

    /** A copy the caller owns, as `grab` hands one out. */
    fun copy(img: Mat): Mat = Mat().also { img.copyTo(it) }

    fun release(vararg mats: Mat) = mats.forEach { it.release() }

}
