package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The frames test_quest_flow.py paints that [Paint] does not: the quest
 * card's counter row and the report badge beside it. Its own file so that
 * two skill sessions painting at the same time do not both edit Paint.kt
 * (PLAN_ANDROID_APP.md 4, the three rules), the same way [PaintDungeon],
 * [PaintPassive] and [PaintSummon] are.
 *
 * Text avoids the digits 0, 4 and 9 for the reason the Python file gives:
 * OpenCV's own Hershey font draws those differently from the game's, and
 * `Dungeon.readDigit` was measured against the game's shapes, not OpenCV's.
 * The real "0/2" shape is not untested for it -- it is exactly what the
 * corpus frames read, off the game's own font, in [QuestOracleTest].
 */
object PaintQuest {
    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    /** The window shape test_quest_flow.py paints on. */
    const val W = 732
    const val H = 1341

    private val FONT = Imgproc.FONT_HERSHEY_DUPLEX

    /** HSV (0, 255, 235): inside Quest.QUEST_RED_LOW. */
    val RED = Scalar(0.0, 0.0, 235.0)

    /**
     * The finished card's own green, converted straight from what was
     * measured on it: HSV (66, 250, 207), the ist digit of "Bakemon 2/2".
     */
    val GREEN = Scalar(45.0, 207.0, 4.0)
    val WHITE = Scalar(250.0, 250.0, 250.0)

    fun blank(w: Int = W, h: Int = H): Mat =
        Mat(h, w, CvType.CV_8UC3, Scalar(200.0, 190.0, 175.0))

    /** The blue disc that says the main screen is in front and clear. */
    fun autoButton(img: Mat): Mat {
        val r = Dungeon.gameRect(img)
        val radius = (0.053 * r.gw / 2).toInt()
        Imgproc.circle(img, Point((r.x0 + 0.361 * r.gw).toInt().toDouble(),
                                  (r.y0 + 0.765 * r.gh).toInt().toDouble()),
                       radius, Scalar(230.0, 140.0, 50.0), -1)
        return img
    }

    private fun charWidth(ch: String, scale: Double): Int =
        Imgproc.getTextSize(ch, FONT, scale, 1, IntArray(1)).width.toInt()

    /**
     * One character at a time, with a guaranteed gap between them.
     *
     * `putText`'s own kerning lets some digit pairs touch at this scale --
     * "5" against "8" merged into one connected component on the frame this
     * was found on, while "1" against "2" did not, so it is not a spacing
     * this function can just add once and trust for every pair. A fixed gap
     * per character is the same fix Quest.questRow's own reader is built to
     * need the opposite of: real digits are never drawn this close together,
     * but a painted test has no such guarantee unless it is put there on
     * purpose.
     */
    private fun drawSpaced(img: Mat, text: String, x0: Int, y: Int, scale: Double,
                           colour: Scalar, gap: Int): Int {
        var x = x0
        for (ch in text) {
            Imgproc.putText(img, ch.toString(), Point(x.toDouble(), y.toDouble()),
                            FONT, scale, colour, 1)
            x += charWidth(ch.toString(), scale) + gap
        }
        return x
    }

    /**
     * "<name> <ist>/<ziel> <after>", ist in [istColour] and the rest white --
     * the shape `Quest.questRow` looks for. Centred on ([rowFx], [rowFy]),
     * the same way the game centres the line inside the card and lets a
     * longer name push the counter itself sideways.
     */
    fun counter(img: Mat, ist: Int, ziel: Int, name: String = "Bakemon",
                rowFx: Double = 0.78, rowFy: Double = 0.60, scaleMul: Double = 1.0,
                istColour: Scalar = RED, after: String = ""): Mat {
        val r = Dungeon.gameRect(img)
        val scale = scaleMul * 0.55 * (r.gh / 1342.0)
        val prefix = if (name.isNotEmpty()) "$name " else ""
        val istS = ist.toString()
        val rest = "/$ziel"
        val tail = if (after.isNotEmpty()) " $after" else ""
        val full = prefix + istS + rest + tail
        val tallest = Imgproc.getTextSize(full, FONT, scale, 1, IntArray(1)).height.toInt()
        // A couple of pixels: enough that two glyphs never touch at
        // 8-connectivity, small enough to stay well inside Quest.QUEST_GAP_MAX,
        // which is what tells a counter's own digits apart from the quest name
        // in front of them.
        val gap = max(1, (0.08 * tallest).roundToInt())
        val totalW = full.sumOf { charWidth(it.toString(), scale) + gap } - gap
        var x = (r.x0 + rowFx * r.gw - totalW / 2.0).toInt()
        val y = (r.y0 + rowFy * r.gh + tallest / 2.0).toInt()
        x = drawSpaced(img, prefix, x, y, scale, WHITE, gap)
        x = drawSpaced(img, istS, x, y, scale, istColour, gap)
        x = drawSpaced(img, rest, x, y, scale, WHITE, gap)
        // A word behind the number, with a real space in front of it: on the
        // game's own cards "0/50 times" and "Draw 0/30 Skill" both put one
        // there, and it is the space that keeps its letters out of the number.
        if (after.isNotEmpty()) drawSpaced(img, " $after", x, y, scale, WHITE, gap)
        return img
    }

    /**
     * A pink "!" report badge: a small solid red-ish blob, no slash anywhere
     * near it. What tells it apart from the counter is never its colour -- it
     * is thrown out here for having no slash beside it at all.
     */
    fun reportBadge(img: Mat, fx: Double, fy: Double): Mat {
        val r = Dungeon.gameRect(img)
        val radius = (0.012 * r.gw).toInt()
        Imgproc.circle(img, Point((r.x0 + fx * r.gw).toInt().toDouble(),
                                  (r.y0 + fy * r.gh).toInt().toDouble()),
                       radius, RED, -1)
        return img
    }

    /** A blank frame with the auto button and one counter row on it. */
    fun card(ist: Int, ziel: Int, name: String = "Bakemon", after: String = "",
             istColour: Scalar = RED, scaleMul: Double = 1.0): Mat =
        counter(autoButton(blank()), ist, ziel, name = name, after = after,
                istColour = istColour, scaleMul = scaleMul)
}
