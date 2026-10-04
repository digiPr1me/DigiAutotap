package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Painted frames for the Lost Sector Tower (LostSectorSkillTest,
 * DirectorTest): the Crests page, the tower's panel with and without the
 * highest floor's toast, a panel DL1a never saw, and a window with a narrow
 * Close -- each rebuilt from DL1a's numbers (LostSector.kt, 4.1 point 4 of
 * PLAN_DAILY_LOST_SECTOR_PRESETS.md) rather than captured, and each held to
 * the **real** readers by the test's first case. Nothing here is a game
 * image: rectangles in the hues the readers measure, at the places they
 * measured them.
 *
 * Every painter paints onto a frame it is handed, through
 * `Dungeon.gameRect(img, anchor)` -- the page, the panel and the toast in
 * the middle of the headroom (LostSector.PAGE), the page's X and the main
 * screen's disc at the bottom -- so that the same painter makes an 805 x
 * 1390 window frame, where the three anchors are one rectangle, and a whole
 * 1080 x 2520 frame with 180 rows of headroom ([frame]), where they are not.
 */
object PaintLostSector {
    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    // The Enter pill, lit on the page (LostSector.ENTER, measured H 111 S 93
    // V 88) and dimmed under the panel (ENTER_DIMMED, H 113 S 122 V 25).
    private val ENTER = Paint.hsv(111, 93, 88)
    private val ENTER_DIM = Paint.hsv(113, 122, 25)
    /** The pill at the middle's rectangle: fx 0.3305-0.3321, fy 0.8604-0.8609, 0.286 x 0.0315. */
    val PILL = Dungeon.Button(0.331, 0.861, 0.286, 0.0315)

    // The panel's Subjugate: fx 0.4752-0.4768, fy 0.7915-0.7919, 0.2597-0.2601 x
    // 0.0535-0.0541 at the middle's rectangle, in the game's button blue.
    private val BLUE = Scalar(230.0, 150.0, 40.0)
    val SUBJUGATE = Dungeon.Button(0.476, 0.7917, 0.26, 0.054)
    // The toast: a navy box (LostSector.TOAST, measured H 107 S 241 V 94), fx
    // 0.476, fy 0.507, 0.656 x 0.063, with white words in it (0.137 of the box
    // on the one real frame).
    private val NAVY = Paint.hsv(107, 241, 94)
    val TOAST = Dungeon.Button(0.476, 0.507, 0.656, 0.063)
    private val WHITE = Scalar(255.0, 255.0, 255.0)
    private val VIOLET = Paint.hsv(130, 200, 200)
    // The white X with its blue mark, Summon.exitButton's, at the bottom.
    private val X_MARK = Paint.hsv(108, 165, 200)
    private const val X_HALF = Summon.EXIT_BUTTON_FW / 2
    private const val X_MARK_HALF = 0.0125

    /**
     * A blank frame: the 805 x 1390 window, or with [headroom] a whole frame
     * of `DigiAutotapService.grab`'s kind -- a [Dungeon.CanvasFrame] holding
     * that many rows over the canvas, as the whole 1080 x 2520 frames of V4
     * do (headroom 180).
     */
    fun frame(w: Int = Paint.W, h: Int = Paint.H, headroom: Int = 0, grey: Double = 30.0): Mat {
        if (headroom <= 0) return Mat(h, w, CvType.CV_8UC3, Scalar(grey, grey, grey))
        val img = Dungeon.CanvasFrame(headroom, headroom)
        img.create(h, w, CvType.CV_8UC3)
        img.setTo(Scalar(grey, grey, grey))
        return img
    }

    /** A box by its centre and size, as fractions of the rectangle at [anchor]. */
    fun box(img: Mat, b: Dungeon.Button, colour: Scalar, anchor: Dungeon.Anchor) {
        val r = Dungeon.gameRect(img, anchor)
        Imgproc.rectangle(img, Point(r.x0 + (b.fx - b.fw / 2) * r.gw, r.y0 + (b.fy - b.fh / 2) * r.gh),
                          Point(r.x0 + (b.fx + b.fw / 2) * r.gw, r.y0 + (b.fy + b.fh / 2) * r.gh), colour, -1)
    }

    /** The page's white X with its blue mark, at the bottom's rectangle (PaintRunner's, on any frame). */
    private fun exitX(img: Mat) {
        val r = Dungeon.gameRect(img)
        val hx = X_HALF * r.gw
        val mx = X_MARK_HALF * r.gw
        val cx = r.x0 + Summon.EXIT_BUTTON_FX * r.gw
        val cy = r.y0 + Summon.EXIT_BUTTON_FY * r.gh
        Imgproc.rectangle(img, Point(cx - hx, cy - hx), Point(cx + hx, cy + hx), WHITE, -1)
        Imgproc.rectangle(img, Point(cx - mx, cy - mx), Point(cx + mx, cy + mx), X_MARK, -1)
    }

    /** The Crests page: the lit Enter pill on the tower's card, and the white X. */
    fun page(img: Mat = frame()): Mat {
        box(img, PILL, ENTER, LostSector.PAGE)
        exitX(img)
        return img
    }

    /**
     * The tower's panel over the page: the page's pill dimmed below it, and
     * Subjugate, alone and centred. With [toast] the highest floor's toast
     * over it. [extra] paints a panel DL1a never saw: "price" is the button
     * widened past Subjugate's measured width -- what a cost drawn beside the
     * word would do to it -- and "button" a violet button above it, a second
     * one of the kind the dungeon panels carry; either way
     * `LostSector.subjugate` must answer null there.
     */
    fun panel(img: Mat = frame(grey = 18.0), toast: Boolean = false, extra: String? = null): Mat {
        box(img, Dungeon.Button(0.5, 0.525, 0.80, 0.61), Paint.BODY, LostSector.PAGE)
        box(img, PILL, ENTER_DIM, LostSector.PAGE)
        val button = if (extra == "price") SUBJUGATE.copy(fw = 0.33) else SUBJUGATE
        box(img, button, BLUE, LostSector.PAGE)
        if (extra == "button") box(img, Dungeon.Button(0.5, 0.70, 0.20, 0.04), VIOLET, LostSector.PAGE)
        if (toast) {
            box(img, TOAST, NAVY, LostSector.PAGE)
            box(img, Dungeon.Button(0.475, 0.507, 0.35, 0.014), WHITE, LostSector.PAGE)
        }
        return img
    }

    /**
     * A window after a run with a narrow Close (Apocalymon's Results window
     * is the one the tickets know, DungeonSkill.CLOSE_W_MAX): a dialog to
     * `recognise`, and no Subjugate.
     */
    fun results(img: Mat = frame(grey = 18.0)): Mat {
        box(img, Dungeon.Button(0.5, 0.525, 0.80, 0.61), Paint.BODY, LostSector.PAGE)
        box(img, Dungeon.Button(0.503, 0.792, 0.216, 0.05), BLUE, LostSector.PAGE)
        return img
    }

    /** The plain main screen on any frame: the auto button's disc at the bottom's rectangle (PaintQuest's). */
    fun mainScreen(img: Mat = frame()): Mat = PaintQuest.autoButton(img)
}
