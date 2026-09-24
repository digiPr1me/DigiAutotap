package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The passive helper's painted frames, test_passive_flow.py's painters
 * carried over: the hologram counter where the game draws it, the bond
 * bubble with its cyan ring, the Daily Bonus wheel that was once taken for
 * one, and the Partner window's button pair.
 *
 * [Paint] paints the screens the director needs; this paints what the
 * passive helper reads on them, and everything here goes through the real
 * readers in [Passive]. A painter that draws something prettier than the
 * game's is worth nothing -- the last painted bubble was, and the reader
 * written against it could not find the real thing -- so each of these
 * keeps the proportions the Python painter measured off the game's own
 * frames.
 *
 * One honest limit on the painted digits: the glyphs come from OpenCV's own
 * font, not the game's, and this program ships no picture of the game's.
 * The shape reader in [Dungeon] was measured against the game, and of the
 * ten OpenCV digits it reads eight -- its 4 and 9 are drawn differently
 * there. So every number in the test is built from the eight that carry
 * over. What the cases test is this module's part: the right crop, the
 * right row, the comma thrown away and the slash found.
 */
object PaintPassive {
    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    /**
     * Two window sizes for everything, because a threshold that is really a
     * threshold on how thickly something is drawn passes at one size and
     * fails at the other. That is not a hypothetical: it is how a 3 came to
     * be read as a 5 in the dungeon skill.
     *
     * 765 x 1390 and 657 x 1198 are two of the sizes the real screenshots
     * were taken at. Smaller than that, OpenCV's own font stops drawing
     * legible digits -- the strokes thin out and fall through the mask -- so
     * the counter cases stop there while the shapes, which do not depend on
     * a font, go on down to 497 x 914.
     */
    val SIZES = listOf(765 to 1390, 657 to 1198)
    val SHAPE_SIZES = listOf(765 to 1390, 657 to 1198, 497 to 914)

    private val FONT = Imgproc.FONT_HERSHEY_DUPLEX

    /** BGR, hue about 93 -- the bubble's cyan as the Python painter mixes it. */
    val CYAN = Scalar(230.0, 200.0, 60.0)

    fun blank(w: Int, h: Int): Mat = Mat(h, w, CvType.CV_8UC3, Scalar(30.0, 30.0, 30.0))

    /** The blue disc that says the main screen is in front and clear. */
    fun autoButton(img: Mat): Mat {
        val r = Dungeon.gameRect(img)
        val radius = (0.053 * r.gw / 2).toInt()
        Imgproc.circle(img, Point((r.x0 + 0.361 * r.gw).toInt().toDouble(),
                                  (r.y0 + 0.765 * r.gh).toInt().toDouble()),
                       radius, Scalar(230.0, 140.0, 50.0), -1)
        return img
    }

    /**
     * The hologram counter where the game draws it.
     *
     * The comma is drawn by hand rather than typed. OpenCV's own comma is a
     * speck at this size, and the comma is not decoration here -- how tall
     * it is and how far it sits below the row is what tells it from a digit
     * and from the specks off the device's rim. Painted at the proportions
     * measured on the real frames: a third of the slash's height, a third of
     * that height below the middle of the row.
     *
     * [clip] cuts the top off one digit, the way the XP numbers rising out
     * of the device do. [junk] paints those numbers as a second row above
     * the counter.
     */
    fun counter(img: Mat, text: String, clip: Boolean = false, junk: Boolean = false): Mat {
        val r = Dungeon.gameRect(img)
        val height = 0.0083 * r.gh
        // Sized so the painted digits come out the height the game's are, 12
        // pixels at a 1390 tall window: OpenCV's cap height is about 0.71 of
        // what getTextSize reports, and everything the reader measures -- the
        // comma against the slash, the row against the crop -- is a
        // proportion of that.
        val scale = 0.60 * (r.gh / 1390.0)
        val left = (r.x0 + 0.411 * r.gw).toInt()
        val base = (r.y0 + 0.8626 * r.gh + height).toInt()
        if (junk) {
            Imgproc.putText(img, "888", Point((left + (0.02 * r.gw).toInt()).toDouble(),
                                              (base - (1.8 * height).toInt()).toDouble()),
                            FONT, scale, Scalar(255.0, 255.0, 255.0), 1, Imgproc.LINE_AA, false)
        }
        Imgproc.putText(img, text, Point(left.toDouble(), base.toDouble()),
                        FONT, scale, Scalar(255.0, 255.0, 255.0), 1, Imgproc.LINE_AA, false)
        if (text.contains(",")) {
            val before = Imgproc.getTextSize(text.substringBefore(","), FONT, scale, 1, null).width
            // The rendered glyph is about 0.71 of what getTextSize calls the
            // height, and the comma is 0.41 of a digit, sitting on the
            // baseline. Counted in rows rather than worked out from
            // fractions: at these sizes it is three pixels tall, and rounding
            // a fraction twice put it at four, which is a comma no longer.
            val digit = 0.71 * Imgproc.getTextSize("0", FONT, scale, 1, null).height
            val rows = max(2, (0.41 * digit).roundToInt())
            val width = max(1, (0.09 * height).roundToInt())
            Imgproc.rectangle(img, Point(left + before, (base - rows + 1).toDouble()),
                              Point(left + before + width - 1, base.toDouble()),
                              Scalar(255.0, 255.0, 255.0), -1)
        }
        if (clip) {
            // The top half of the digit after the comma, cut away.
            val cut = left + Imgproc.getTextSize(text.substringBefore(",") + ",",
                                                 FONT, scale, 1, null).width
            Imgproc.rectangle(img, Point(cut, (base - height.toInt()).toDouble()),
                              Point(cut + (0.6 * height).toInt(),
                                    (base - (0.45 * height).toInt()).toDouble()),
                              Scalar(30.0, 30.0, 30.0), -1)
        }
        return img
    }

    /**
     * A white square with the bubble's cyan ring inside it.
     *
     * The ring is what the reader looks for, and it is not one shape but
     * two: a corner bracket at the upper left and another at the lower
     * right, facing each other across the food symbol. Painted at the
     * proportions measured on the game's own frames -- each bracket 0.62 of
     * the ring across, its stroke 0.14 of that again, which comes out at a
     * fill of 0.23 to 0.27 and a pair 0.61 of a bracket apart in both
     * directions.
     *
     * Painted as one L instead, or as a filled square with a cyan edge, the
     * cases would pass a reader that cannot find the real thing.
     *
     * With the partner's HP bar under it, unless [bar] says otherwise: a
     * flat green strip whose left end is 0.096 of the window left of the
     * bubble's centre and 0.023 below it, the full 0.076 wide (Passive,
     * BAR_GREEN). A bubble with no bar is what an attack effect or a reward
     * window looks like, and the reader refuses it.
     */
    fun bubble(img: Mat, fx: Double = 0.51, fy: Double = 0.34, bar: Boolean = true): Mat {
        val r = Dungeon.gameRect(img)
        val side = (Passive.BUBBLE_SIDE * r.gw).toInt()
        val x = (r.x0 + fx * r.gw - side / 2.0).toInt()
        val y = (r.y0 + fy * r.gh - side / 2.0).toInt()
        if (bar) {
            val bx = r.x0 + (fx - 0.096) * r.gw
            val by = r.y0 + (fy + 0.023) * r.gh
            Imgproc.rectangle(img, Point(bx, by - 0.0035 * r.gh), Point(bx + 0.076 * r.gw, by + 0.0035 * r.gh),
                              Scalar(23.0, 255.0, 100.0), -1)
        }
        Imgproc.rectangle(img, Point(x.toDouble(), y.toDouble()),
                          Point((x + side).toDouble(), (y + side).toDouble()),
                          Scalar(255.0, 255.0, 255.0), -1)
        // The ring, inside the white border.
        val edge = max(1, (0.06 * side).toInt())
        val ring = side - 2 * edge
        val arm = max(4, (0.62 * ring).toInt())
        val thick = max(2, (0.14 * arm).toInt())
        for (corner in 0..1) {
            // 0: the upper left bracket, 1: the lower right one.
            val left = if (corner == 0) x + edge else x + side - edge - arm
            val top = if (corner == 0) y + edge else y + side - edge - arm
            Imgproc.rectangle(img,
                Point(left.toDouble(), (if (corner == 0) top else top + arm - thick).toDouble()),
                Point((left + arm).toDouble(), (if (corner == 0) top + thick else top + arm).toDouble()),
                CYAN, -1)
            Imgproc.rectangle(img,
                Point((if (corner == 0) left else left + arm - thick).toDouble(), top.toDouble()),
                Point((if (corner == 0) left + thick else left + arm).toDouble(), (top + arm).toDouble()),
                CYAN, -1)
        }
        // The food symbol, in the middle where the brackets leave room.
        Imgproc.circle(img, Point((x + side / 2).toDouble(), (y + side / 2).toDouble()),
                       (side * 0.18).toInt(), Scalar(60.0, 60.0, 200.0), -1)
        return img
    }

    /**
     * The Daily Bonus wheel, the icon that was taken for a bubble.
     *
     * White and cyan quarters around a cyan hub, inside a blue rim, drawn at
     * the size and the place the game draws it: the inner of the two columns
     * of side icons, at fx 0.694, with the coloured disc 0.045 of the window
     * across.
     *
     * [solid] paints it with every quarter white. That is not what the game
     * draws, and it is exactly the shape a spin or a dimmed frame could
     * leave behind. Neither version has anything drawn as thinly as the
     * bubble's brackets: a quarter of a disc fills 0.78 of its own box and
     * the hub fills it altogether, where a bracket fills 0.23 to 0.29.
     */
    fun wheel(img: Mat, fx: Double = 0.694, fy: Double = 0.279, solid: Boolean = false): Mat {
        val r = Dungeon.gameRect(img)
        val cx = (r.x0 + fx * r.gw).toInt()
        val cy = (r.y0 + fy * r.gh).toInt()
        val disc = max(6, (0.0226 * r.gw).toInt())           // radius, 17 px at 751 wide
        val c = Point(cx.toDouble(), cy.toDouble())
        Imgproc.circle(img, c, (disc * 1.18).toInt(), Scalar(200.0, 90.0, 20.0), -1)  // the rim
        Imgproc.circle(img, c, disc, Scalar(255.0, 255.0, 255.0), -1)
        if (!solid) {
            // Two quarters facing each other, the way the wheel is drawn.
            for (a in listOf(0.0, 180.0)) {
                Imgproc.ellipse(img, c, org.opencv.core.Size(disc.toDouble(), disc.toDouble()),
                                a, 0.0, 90.0, CYAN, -1)
            }
        }
        Imgproc.circle(img, c, (disc * 0.38).toInt(), CYAN, -1)                       // the hub
        return img
    }

    /** The Partner window's pair: violet Encyclopedia, blue Move. */
    fun partner(img: Mat): Mat {
        val r = Dungeon.gameRect(img)
        for ((fx, colour) in listOf(Passive.PARTNER_VIOLET_X to Scalar(220.0, 47.0, 105.0),
                                    Passive.PARTNER_BLUE_X to Scalar(220.0, 125.0, 30.0))) {
            val w = (0.242 * r.gw).toInt()
            val h = (0.045 * r.gh).toInt()
            val x = (fx * r.gw - w / 2.0).toInt()
            val y = (r.y0 + Passive.PARTNER_Y * r.gh - h / 2.0).toInt()
            Imgproc.rectangle(img, Point(x.toDouble(), y.toDouble()),
                              Point((x + w).toDouble(), (y + h).toDouble()), colour, -1)
        }
        return img
    }

    /**
     * The main screen as the passive helper sees it: the auto button, the
     * counter, and a bubble where one is asked for. [counterText] empty
     * paints no counter at all.
     */
    fun mainScreen(w: Int = Paint.W, h: Int = Paint.H, counterText: String = "77,605 / 10",
                   bubble: Boolean = false): Mat {
        val img = autoButton(blank(w, h))
        if (counterText.isNotEmpty()) counter(img, counterText)
        if (bubble) bubble(img)
        return img
    }

    /** The Stage Failed banner over a frame this module painted (Paint.stageFailed's letters). */
    fun stageFailed(img: Mat): Mat {
        val r = Dungeon.gameRect(img)
        for ((text, fy) in listOf("Stage" to 0.13, "Failed..." to 0.19)) {
            val x = (r.x0 + r.gw * (if (text == "Stage") 0.30 else 0.22)).toInt()
            val y = (r.y0 + r.gh * fy).toInt()
            for ((dx, dy) in listOf(-3 to 0, 3 to 0, 0 to -3, 0 to 3)) {
                Imgproc.putText(img, text, Point((x + dx).toDouble(), (y + dy).toDouble()),
                                Imgproc.FONT_HERSHEY_SIMPLEX, 2.0, Scalar(255.0, 255.0, 255.0), 7)
            }
            Imgproc.putText(img, text, Point(x.toDouble(), y.toDouble()),
                            Imgproc.FONT_HERSHEY_SIMPLEX, 2.0, Scalar(40.0, 40.0, 220.0), 6)
        }
        return img
    }

    /** `abs(a - b) < tol`, for the places a case says "about here". */
    fun near(a: Double, b: Double, tol: Double): Boolean = abs(a - b) < tol
}
