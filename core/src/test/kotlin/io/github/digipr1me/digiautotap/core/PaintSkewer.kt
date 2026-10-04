package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Painted frames for Chef's Special (SkewerSkillTest): the menu, the stage
 * popup, a round with its grill, order and lives, and the round's two
 * dialogs -- each rebuilt from SK1's numbers in Skewer.kt and held to the
 * **real** readers by the test's first case. The ingredients are the
 * templates the readers name them by (templates/skewer_*.png), laid where
 * the game lays them: on the grill at the size they were cut, and in the
 * bubble at the order's scale and lattice (SkewerIcons.order). Everything
 * else is rectangles in the hues the readers measure.
 *
 * Every painter paints onto a frame it is handed, through
 * `Dungeon.gameRect(img, anchor)` -- the round's page at the top
 * (Skewer.ROUND), the menu's pennant and the stage popup in the middle
 * (Skewer.MENU, Skewer.STAGE), the X at the bottom (Summon.exitButton),
 * the round's dialogs in the middle (Runner.DIALOG) -- so
 * that the same painter makes an 805 x 1390 window frame, where the anchors
 * are one rectangle, and a whole frame with headroom (PaintLostSector.frame),
 * where they are not.
 */
object PaintSkewer {
    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    val icons = SkewerIcons(ClassPathAssets)

    // The grill: red between the cells, Skewer.RED_H_LOW / RED_S_MIN (H <= 15,
    // S >= 100; measured H 5-8 S 147-199 V 160-226), and dark cells, their
    // corners under Skewer.CORNER_V_MAX (66; measured 49). Under a dialog
    // both dimmed as measured: the red V 88-127, the corners 23.
    private val RED = Paint.hsv(6, 180, 200)
    private val RED_DIM = Paint.hsv(6, 180, 110)
    private val CELL = Paint.hsv(0, 0, 38)
    private val CELL_DIM = Paint.hsv(0, 0, 20)
    // The bubble: Skewer's BUBBLE, S <= 30 and V >= 230.
    private val WHITE = Scalar(255.0, 255.0, 255.0)
    // The lives' plate, dark, and the digit bright on it.
    private val LIVES_PLATE = Paint.hsv(110, 60, 45)
    // The menu: "Play Game" in Skewer.PENNANT, H 0-14 S >= 160 V >= 180, on a
    // green wood; dimmed under the stage popup to V 100, which the pennant's
    // own floor refuses, and the X with it (V 108 S 24, as Runner measured
    // the dimmed plate).
    private val PENNANT = Paint.hsv(7, 210, 235)
    private val PENNANT_DIM = Paint.hsv(7, 210, 100)
    private val WOOD = Paint.hsv(60, 120, 90)
    private val X_MARK = Paint.hsv(108, 165, 200)
    private val X_DIM = Paint.hsv(0, 24, 108)
    // The stage popup: its body dark blue (V 90, under Runner.EVENT_BLUE's
    // floor of 100), and Start and the "Stage" tab in Skewer.STAGE_BLUE, H
    // 97-104 S >= 220 V >= 180.
    private val POPUP = Paint.hsv(112, 200, 90)
    private val STAGE_BLUE = Paint.hsv(100, 240, 220)
    // The round's dialogs, as PaintRunner paints Gekkomon Run's: the event's
    // blue box (Runner.EVENT_BLUE), the dark plate (Runner.PLATE_V_MAX), the
    // pink Quit or Close (Runner.QUIT_PINK) and the cyan Continue.
    private val EVENT_BOX = Paint.hsv(103, 233, 139)
    private val DIALOG_PLATE = Paint.hsv(103, 212, 77)
    val PINK = Paint.hsv(150, 158, 223)
    private val CONTINUE = Paint.hsv(100, 255, 209)

    // Where the painter puts what the readers do not place themselves.
    /** The stage popup's body, as SK2's probe measured it on the six staged popups. */
    val POPUP_BOX = doubleArrayOf(SkewerSkill.STAGE_POPUP_FX0, 0.313, 0.803, 0.714)
    /** The round's dialog box, over the grill's first row as the real one is (fy 0.377-0.647 at 1920). */
    private val DIALOG_BOX = doubleArrayOf(0.13, 0.36, 0.87, 0.645)
    /** Close on the result dialog, Quit and Continue on the pause menu (Runner's rows). */
    val CLOSE = doubleArrayOf(0.375, 0.592, 0.570, 0.633)
    val QUIT = doubleArrayOf(0.245, 0.592, 0.441, 0.633)
    val CONTINUE_BOX = doubleArrayOf(0.505, 0.592, 0.698, 0.633)
    /** Complete, the silver dome, and the pause button: where the game takes their taps. */
    val COMPLETE_BOX = doubleArrayOf(Skewer.COMPLETE.fx - 0.08, Skewer.COMPLETE.fy - 0.04,
                                     Skewer.COMPLETE.fx + 0.08, Skewer.COMPLETE.fy + 0.04)
    val PAUSE_BOX = doubleArrayOf(Skewer.PAUSE.fx - 0.045, Skewer.PAUSE.fy - 0.025,
                                  Skewer.PAUSE.fx + 0.045, Skewer.PAUSE.fy + 0.025)
    /** A cell's own square, its centre +- this across and down: the red between two cells is the rest. */
    const val CELL_HALF_FX = 0.085
    const val CELL_HALF_FY = 0.05

    /** A blank frame: the 805 x 1390 window, or a whole frame with [headroom] (PaintLostSector.frame). */
    fun frame(w: Int = Paint.W, h: Int = Paint.H, headroom: Int = 0, grey: Double = 30.0): Mat =
        PaintLostSector.frame(w, h, headroom, grey)

    /** A box by its corners (fx0, fy0, fx1, fy1), as fractions of the rectangle at [anchor]. */
    fun box(img: Mat, b: DoubleArray, colour: Scalar, anchor: Dungeon.Anchor = Dungeon.Anchor.BOTTOM) {
        val r = Dungeon.gameRect(img, anchor)
        Imgproc.rectangle(img, Point(r.x0 + b[0] * r.gw, r.y0 + b[1] * r.gh),
                          Point(r.x0 + b[2] * r.gw, r.y0 + b[3] * r.gh), colour, -1)
    }

    /** [src] laid onto [img] with its top left at (x, y), where [mask] is set (or everywhere), clipped to the picture. */
    private fun paste(img: Mat, src: Mat, x: Int, y: Int, mask: Mat? = null) {
        val x0 = maxOf(0, x); val y0 = maxOf(0, y)
        val x1 = minOf(img.cols(), x + src.cols()); val y1 = minOf(img.rows(), y + src.rows())
        if (x1 <= x0 || y1 <= y0) return
        val dst = img.submat(y0, y1, x0, x1)
        val part = src.submat(y0 - y, y1 - y, x0 - x, x1 - x)
        if (mask == null) part.copyTo(dst) else part.copyTo(dst, mask.submat(y0 - y, y1 - y, x0 - x, x1 - x))
    }

    private fun resized(t: Mat, side: Int): Mat {
        val out = Mat()
        Imgproc.resize(t, out, Size(side.toDouble(), side.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        return out
    }

    /** Each template at [side] px and its own pixels' mask, made once per size: a flow test paints hundreds. */
    private val scaled = HashMap<Pair<String, Int>, Pair<Mat, Mat>>()

    private fun scaledIcon(name: String, side: Int): Pair<Mat, Mat> = scaled.getOrPut(name to side) {
        val t = resized(icons.icons.getValue(name), side)
        t to icons.mask(t)
    }

    /**
     * The grill with its twelve ingredients, [SkewerIcons.NAMES] row by row
     * -- the order SK1 found in every round it played -- each the template at
     * the size it was cut (Skewer.ICON_HALF_FW), on a dark cell in the red.
     */
    fun grill(img: Mat, dim: Boolean = false): Mat {
        val r = Dungeon.gameRect(img, Skewer.ROUND)
        box(img, doubleArrayOf(0.08, 0.62, 0.87, 0.955), if (dim) RED_DIM else RED, Skewer.ROUND)
        val side = Py.roundInt(2 * Skewer.ICON_HALF_FW * r.gw)
        var i = 0
        for (cy in Skewer.CELL_FY) for (cx in Skewer.CELL_FX) {
            box(img, doubleArrayOf(cx - CELL_HALF_FX, cy - CELL_HALF_FY, cx + CELL_HALF_FX, cy + CELL_HALF_FY),
                if (dim) CELL_DIM else CELL, Skewer.ROUND)
            if (!dim) {
                val (t, _) = scaledIcon(SkewerIcons.NAMES[i], side)
                paste(img, t, Py.roundInt(r.x0 + cx * r.gw) - side / 2, Py.roundInt(r.y0 + cy * r.gh) - side / 2)
            }
            i += 1
        }
        return img
    }

    /**
     * The order in the white bubble: [names] on the lattice of their own
     * length (SkewerIcons.PITCH_FX), each the template at the order's scale,
     * its own pixels only, the later covering the right side of the one
     * before, as the game packs four and five.
     */
    fun order(img: Mat, names: List<String>): Mat {
        val r = Dungeon.gameRect(img, Skewer.ROUND)
        box(img, doubleArrayOf(0.25, 0.22, 0.70, 0.30), WHITE, Skewer.ROUND)
        val pitch = icons.PITCH_FX.getValue(names.size)
        val side = maxOf(8, Py.roundInt(SkewerIcons.TEMPLATE_W * icons.ORDER_SCALE * r.gw / SkewerIcons.TEMPLATE_GW))
        val y = Py.roundInt(r.y0 + (icons.ORDER_BOX[1] + icons.ORDER_BOX[3]) / 2 * r.gh) - side / 2
        for ((i, name) in names.withIndex()) {
            val (t, m) = scaledIcon(name, side)
            val x = Py.roundInt((icons.SLOT0_FX + (i - (names.size - 1) / 2.0) * pitch) * r.gw + r.x0)
            paste(img, t, x, y, m)
        }
        return img
    }

    /** "x[n]": the digit as its template draws it, on the dark plate by the heart. */
    fun lives(img: Mat, n: Int): Mat {
        val r = Dungeon.gameRect(img, Skewer.ROUND)
        val b = Skewer.LIVES_BOX
        box(img, doubleArrayOf(b[0] - 0.01, b[1] - 0.004, b[2] + 0.004, b[3] + 0.004), LIVES_PLATE, Skewer.ROUND)
        val digit = icons.digits.getValue(n)
        val h = Py.roundInt(0.72 * (b[3] - b[1]) * r.gh)
        val w = maxOf(3, Py.roundInt(digit.cols() * h / digit.rows().toDouble()))
        val small = Mat()
        Imgproc.resize(digit, small, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val bin = Mat()
        Imgproc.threshold(small, bin, 127.0, 255.0, Imgproc.THRESH_BINARY)
        val x = Py.roundInt(r.x0 + b[2] * r.gw) - w - Py.roundInt(0.006 * r.gw)
        val y = Py.roundInt(r.y0 + (b[1] + b[3]) / 2 * r.gh) - h / 2
        val bright = Mat(h, w, img.type(), WHITE)
        paste(img, bright, x, y, bin)
        small.release(); bin.release(); bright.release()
        return img
    }

    /** A round in play: the grill, the lives, and the order in its bubble where a guest has one. */
    fun play(img: Mat = frame(), order: List<String>?, lives: Int = 3): Mat {
        grill(img)
        lives(img, lives)
        if (order != null) order(img, order)
        return img
    }

    /** The round's dialog over the dimmed grill: "Success!" / "Failed..." with Close, or the pause menu. */
    private fun dialog(img: Mat, lives: Int, pause: Boolean): Mat {
        grill(img, dim = true)
        lives(img, lives)
        val mid = Dungeon.Anchor.MIDDLE
        box(img, DIALOG_BOX, EVENT_BOX, mid)
        box(img, doubleArrayOf(0.20, 0.38, 0.76, 0.56), DIALOG_PLATE, mid)
        if (pause) {
            box(img, QUIT, PINK, mid)
            box(img, CONTINUE_BOX, CONTINUE, mid)
        } else {
            box(img, CLOSE, PINK, mid)
        }
        return img
    }

    fun over(img: Mat = frame(), lives: Int = 3): Mat = dialog(img, lives, pause = false)
    fun paused(img: Mat = frame(), lives: Int = 3): Mat = dialog(img, lives, pause = true)

    /** The white X with its blue mark at the bottom right (Summon.exitButton), or dimmed. */
    private fun exitX(img: Mat, dim: Boolean) {
        val r = Dungeon.gameRect(img)
        val hx = Summon.EXIT_BUTTON_FW / 2 * r.gw
        val mx = 0.0125 * r.gw
        val cx = r.x0 + Summon.EXIT_BUTTON_FX * r.gw
        val cy = r.y0 + Summon.EXIT_BUTTON_FY * r.gh
        Imgproc.rectangle(img, Point(cx - hx, cy - hx), Point(cx + hx, cy + hx), if (dim) X_DIM else WHITE, -1)
        if (!dim) Imgproc.rectangle(img, Point(cx - mx, cy - mx), Point(cx + mx, cy + mx), X_MARK, -1)
    }

    /** The Chef's Special menu: "Play Game" on its pennant, and the white X. */
    fun menu(img: Mat = frame(), dim: Boolean = false): Mat {
        img.setTo(WOOD)
        box(img, Skewer.PENNANT_BOX, if (dim) PENNANT_DIM else PENNANT, Skewer.MENU)
        exitX(img, dim)
        return img
    }

    /** The stage popup over the dimmed menu: the "Stage" tab and Start. */
    fun stage(img: Mat = frame()): Mat {
        menu(img, dim = true)
        box(img, POPUP_BOX, POPUP, Skewer.STAGE)
        box(img, Skewer.STAGE_TAB_BOX, STAGE_BLUE, Skewer.STAGE)
        box(img, Skewer.START_BOX, STAGE_BLUE, Skewer.STAGE)
        return img
    }

    /** Where cell [i] ([SkewerIcons.NAMES] order) stands on the grill, as a painted box. */
    fun cellBox(i: Int): DoubleArray {
        val cx = Skewer.CELL_FX[i % Skewer.CELL_FX.size]
        val cy = Skewer.CELL_FY[i / Skewer.CELL_FX.size]
        return doubleArrayOf(cx - CELL_HALF_FX, cy - CELL_HALF_FY, cx + CELL_HALF_FX, cy + CELL_HALF_FY)
    }

    /** Is (fx, fy) inside [b] (fx0, fy0, fx1, fy1)? */
    fun inside(fx: Double, fy: Double, b: DoubleArray) = fx in b[0]..b[2] && fy in b[1]..b[3]

    /** The rectangle a painted frame of this size and headroom has at [anchor]. */
    fun rectOf(img: Mat, anchor: Dungeon.Anchor): Dungeon.GameRect = Dungeon.gameRect(img, anchor)

    /** A copy that keeps the headroom (OracleFamilies.copy). */
    fun copy(img: Mat): Mat = OracleFamilies.copy(img)

    /** The pixel at (x, y) in HSV, or null off the picture. */
    fun hsvAt(img: Mat, x: Int, y: Int): IntArray? {
        if (x < 0 || y < 0 || x >= img.cols() || y >= img.rows()) return null
        val one = img.submat(Rect(x, y, 1, 1))
        val hsv = Cv.hsv(one)
        val px = hsv.get(0, 0)
        hsv.release()
        return intArrayOf(px[0].toInt(), px[1].toInt(), px[2].toInt())
    }
}
