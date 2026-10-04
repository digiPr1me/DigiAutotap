package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Chef's Special, the skewer minigame of the Events window (PLAN_SKEWER.md):
 * its menu, its stage popup, the grill with its twelve ingredients, the
 * order in the speech bubble, the lives, and the dialogs that end a round.
 * The loop that uses them is SkewerSkill (SK2).
 *
 * The laboratory's `skewer.py` was the model and not the source ("One
 * project"): every place below was measured on 2026-09-30 on LDPlayer
 * instance 0 at 1080 x 1920 and at 1080 x 2340 hole105 (1080 x 2235), from
 * frames in `staging/skewer` and the rounds played to take them. What the
 * laboratory measured at 765 x 1390 did not survive: its grid rows, its
 * order box, its fixed 37 px pitch, its "Play Game" lantern (the menu is
 * new) and its red flash (a mistake shows as a lost life).
 *
 * Every place is a fraction of `Dungeon.gameRect(img, anchor)`, at the
 * anchor its window stands at over the ceiling ([ROUND], [MENU], [STAGE];
 * notes/formats.md rule 4, and notes/skewer.md, "Over the ceiling the round
 * hangs from the top, and its menu and popup stand in the middle").
 *
 * The oracle family `skewer` holds every reader here.
 */
object Skewer {

    // Where the three windows of Chef's Special stand over the ceiling,
    // measured on 2026-10-02 on LDPlayer instance 1 at 1080 x 2520 (180 rows
    // of headroom) against 1080 x 1920 twins of the same session, every
    // reader asked at all three anchors (staging/n4): the round -- the
    // grill, its twelve, the bubble, the lives, Complete and the pause
    // button -- answered as its twin at the top only (grill 12 of 12, the
    // twelve names, the order, "x3"; at the bottom and the middle 7 or 8 red
    // points, no lives, no order: the page hangs from the top, the empty
    // rows under the grill); the menu's "Play Game" at the middle only
    // (0.4489/0.8266 against 0.4490/0.8265), the stage popup's Start at the
    // middle only (0.4757/0.6774 against 0.4760/0.6778). The X of the menu
    // is Summon.exitButton's, at the bottom, and the round's dialogs are
    // Runner.DIALOG's, in the middle -- both read there on these frames.
    val ROUND = Dungeon.Anchor.TOP
    val MENU = Dungeon.Anchor.MIDDLE
    val STAGE = Dungeon.Anchor.MIDDLE

    // ------------------------------------------------------------------------
    // The grill
    // ------------------------------------------------------------------------
    // Twelve dark cells in a red grill, 4 x 3, the same order in every round
    // played (but read every time, never assumed). Measured on
    // skewer_order3_1080x1920_none0_191214 by the dark inner edges of the
    // cells: columns 118-319, 333-534, 547-748, 762-963 px, rows from 1189,
    // 1404, 1619 px, 201 px square; in game_rect (-6, -57, 1147, 1980):
    val CELL_FX = doubleArrayOf(0.19573, 0.38318, 0.57062, 0.75806)
    val CELL_FY = doubleArrayOf(0.68005, 0.78864, 0.89722)
    /** Half the ingredient's square as the templates were cut, 80 px of 1147. */
    const val ICON_HALF_FW = 0.06975
    // The grill between the cells, half a pitch right of each cell's middle
    // (0.0937), and the four corners of each cell, 0.07 across and 0.04 down
    // from its middle, clear of the ingredient. Measured on every staged
    // frame (HSV, the median of a 7 px patch):
    //
    //                          grill: red points     corner V, median of 48
    //   a round, live          12 of 12, H 5-8,      49 (the worst corner 70)
    //                          S 147-199, V 160-226
    //   the countdown          12 of 12, V 118-169   34
    //   a dialog over it       12 of 12, V 88-127    23 (the dialog's own 139)
    //   everything else        0 of 12 (H 22-125)    83-209 (the Missions
    //   (main, Events, menu,                         window 83, the Events
    //   stage, Missions)                             window 86)
    //
    // So a point is grill where its hue is red (H <= 15 or >= 170) and S >=
    // 100. Swept over the 1791 frames of the corpus the most red points any
    // of them has is 8, on two (passive/unclear_173435 and
    // dungeon/daily_vs_053118, their corners at 134 and 178); over the 502
    // frames of the seven rounds played for the measurement and the staged
    // ones, a round in play has 10 to 12, its countdown 7 to 9. So a frame is
    // the grill with at least GRILL_POINTS, 9, of the twelve -- the middle of
    // the corpus's 8 and the round's 10 -- and with its cells dark, their
    // corners' median under CORNER_V_MAX, the middle of 49 and 83. The
    // "PERFECT" burst at a round's end (skewer_perfect_1080x1920_none0_192440,
    // 7 points, corners 69) is no grill for the second it lasts.
    const val GAP_FX = 0.0937
    const val CORNER_DX = 0.07
    const val CORNER_DY = 0.04
    const val RED_H_LOW = 15
    const val RED_H_HIGH = 170
    const val RED_S_MIN = 100
    const val GRILL_POINTS = 9
    const val CORNER_V_MAX = 66.0

    /** What the grill reader measured: red grill points of 12, and the cells' corner V median. */
    data class Grill(val red: Int, val cornerV: Double) {
        val ok get() = red >= GRILL_POINTS && cornerV <= CORNER_V_MAX
        fun toOracle(): Map<String, Any?> = mapOf("red" to red, "corner_v" to cornerV)
    }

    /** The median H, S and V of the 7 x 7 px patch around [x], [y], or null off the picture. */
    private fun patch(img: Mat, x: Int, y: Int, r: Int = 3): IntArray? {
        if (x - r < 0 || y - r < 0 || x + r + 1 > img.cols() || y + r + 1 > img.rows()) return null
        val sub = img.submat(y - r, y + r + 1, x - r, x + r + 1)
        val hsv = Cv.hsv(sub)
        val bytes = ByteArray(hsv.rows() * hsv.cols() * 3)
        hsv.get(0, 0, bytes)
        hsv.release()
        val out = IntArray(3)
        for (c in 0 until 3) {
            val v = IntArray(bytes.size / 3) { bytes[it * 3 + c].toInt() and 0xff }
            v.sort()
            out[c] = v[v.size / 2]
        }
        return out
    }

    /** The grill's two measurements on this frame. */
    fun grill(img: Mat, anchor: Dungeon.Anchor = ROUND): Grill {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        var red = 0
        val corners = ArrayList<Int>()
        for (cy in CELL_FY) for (cx in CELL_FX) {
            val g = patch(img, Py.int(x0 + (cx + GAP_FX) * gw), Py.int(y0 + cy * gh))
            if (g != null && (g[0] <= RED_H_LOW || g[0] >= RED_H_HIGH) && g[1] >= RED_S_MIN) red += 1
            for (dx in doubleArrayOf(-CORNER_DX, CORNER_DX)) for (dy in doubleArrayOf(-CORNER_DY, CORNER_DY)) {
                val c = patch(img, Py.int(x0 + (cx + dx) * gw), Py.int(y0 + (cy + dy) * gh))
                corners.add(c?.get(2) ?: 255)
            }
        }
        corners.sort()
        val n = corners.size
        val median = if (n % 2 == 1) corners[n / 2].toDouble() else (corners[n / 2 - 1] + corners[n / 2]) / 2.0
        return Grill(red, median)
    }

    /** Where to tap the ingredient in [row], [col]. */
    fun cell(row: Int, col: Int): Explore.Target = Explore.Target(CELL_FX[col], CELL_FY[row], ROUND)

    // The two buttons of the round, measured where the taps that played it
    // landed: Complete, the silver dome right of the plate, at 870,1040 of
    // 1080 x 1920 and its game_rect place at 1080 x 2235 (eight orders served
    // there on 2026-09-30, one of them a deliberate mistake); the pause
    // button, the blue square top right, at 890,145 -- the same on both.
    val COMPLETE = Explore.Target(0.7637, 0.5540, ROUND)
    val PAUSE = Explore.Target(0.7812, 0.1020, ROUND)

    // ------------------------------------------------------------------------
    // The screens around the round
    // ------------------------------------------------------------------------
    // The menu: the event's own page, three pennants and the white X plate
    // bottom right (Summon.exitButton, the same reused artwork -- 0.7934 /
    // 0.9567 on the menu). "Play Game" is the orange-red pennant, one blob
    // of H 0-14 S >= 160 V >= 180: fx 0.245-0.653 fy 0.771-0.929, share
    // 0.0377-0.0378, fill 0.58-0.59 on both formats; not one blob of the kind
    // of 0.01 share on the stage popup (dimmed), the round, or the main
    // screen. The tap is its text, 0.35 of the pennant down.
    val PENNANT = Dungeon.Hsv(intArrayOf(0, 160, 180), intArrayOf(14, 255, 255))
    val PENNANT_BOX = doubleArrayOf(0.245, 0.771, 0.653, 0.929)
    const val BOX_TOL = 0.03
    const val PENNANT_TEXT_DOWN = 0.35

    private fun near(b: Runner.Blob, box: DoubleArray, tol: Double = BOX_TOL) =
        kotlin.math.abs(b.fx0 - box[0]) <= tol && kotlin.math.abs(b.fy0 - box[1]) <= tol &&
            kotlin.math.abs(b.fx1 - box[2]) <= tol && kotlin.math.abs(b.fy1 - box[3]) <= tol

    /** The Chef's Special menu, or null; where "Play Game" is tapped. */
    fun menu(img: Mat, anchor: Dungeon.Anchor = MENU): Explore.Target? {
        if (Summon.exitButton(img) == null) return null
        for (b in Runner.blobs(img, PENNANT, minShare = 0.01, anchor = anchor)) {
            if (near(b, PENNANT_BOX)) return Explore.Target(b.cx, b.fy0 + PENNANT_TEXT_DOWN * b.height, anchor)
        }
        return null
    }

    // The stage popup: "Stage N", the lanterns, and a blue Start button, all
    // over the dimmed menu. Start is one blob of H 97-104 S >= 220 V >= 180
    // at fx 0.363-0.589 fy 0.656-0.699, fill 0.93, on all six staged popups
    // of both formats; the "Stage" tab over the popup is the same blue at fx
    // 0.254-0.698 fy 0.310-0.332. The pause menu's Continue is that blue too,
    // and stands elsewhere (fx 0.505-0.698 fy 0.592-0.630). The popup's
    // opening animation (skewer_stage_opening_1080x1920_none0_192724) is
    // smaller and is no popup yet.
    val STAGE_BLUE = Dungeon.Hsv(intArrayOf(97, 220, 180), intArrayOf(104, 255, 255))
    val START_BOX = doubleArrayOf(0.363, 0.656, 0.589, 0.699)
    val STAGE_TAB_BOX = doubleArrayOf(0.254, 0.310, 0.698, 0.332)

    /** The stage popup, or null; where Start is tapped. */
    fun stage(img: Mat, anchor: Dungeon.Anchor = STAGE): Explore.Target? {
        var start: Runner.Blob? = null
        var tab = false
        for (b in Runner.blobs(img, STAGE_BLUE, minShare = 0.003, anchor = anchor)) {
            if (near(b, START_BOX) && b.fill >= 0.85) start = b
            if (near(b, STAGE_TAB_BOX)) tab = true
        }
        val s = start ?: return null
        return if (tab) Explore.Target(s.cx, s.cy, anchor) else null
    }

    // The round's two dialogs are the event's own blue box with a pink
    // button, the artwork Gekkomon Run ends its runs with, and Runner's
    // readers answer on them: resultDialog on "Success!" and "Failed..."
    // (its Close stands where the run's Quit does, Runner.QUIT_RESULT, and
    // the tap there closed it on both formats), pauseDialog on "Pause" (Quit
    // and Continue, Runner.QUIT_PAUSE). What tells them from the run's is
    // the grill under them, dimmed but red (the table above). Over the
    // ceiling the dialog stands in the middle and the grill at the top, so
    // the dialog's lower edge lies over the grill's first row: at 1080 x
    // 2520 the result window left 9 red points of 12 and the pause menu 8,
    // under GRILL_POINTS. So under a dialog the grill is asked for
    // DIALOG_POINTS only. Over the corpus and the staged frames (1867), on
    // every frame on which Runner's dialog readers answer: the skewer's 11
    // dialogs 8 to 12 red points, Gekkomon Run's 30, on every format of the
    // tour, 0 or 1. DIALOG_POINTS is 5, the middle of 1 and 8; the corners
    // are not asked there (the run's result over the ceiling read 64 at the
    // top, under CORNER_V_MAX).
    const val DIALOG_POINTS = 5

    /** "Success!" or "Failed..." over the grill, or null; where Close is tapped. */
    fun over(img: Mat): Explore.Target? {
        if (grill(img).red < DIALOG_POINTS) return null
        return Runner.resultDialog(img)
    }

    /** The pause menu over the grill, or null; where Quit is tapped. */
    fun paused(img: Mat): Explore.Target? {
        if (grill(img).red < DIALOG_POINTS) return null
        return Runner.pauseDialog(img)
    }

    /** A round in play: the grill, and no dialog over it. */
    fun playing(img: Mat): Boolean =
        grill(img).ok && Runner.resultDialog(img) == null && Runner.pauseDialog(img) == null

    // ------------------------------------------------------------------------
    // The lives
    // ------------------------------------------------------------------------
    // "x3" on a dark plate right of the heart, top left. A wrong skewer costs
    // a life and nothing else -- the score stays, the guest leaves angry --
    // and at none the round ends "Failed..." (skewer_failed_lives0). The
    // plate at fx 0.1709-0.2337 fy 0.1258-0.1480 (190-262 x 192-236 px at
    // 1080 x 1920); the glyphs are its bright pixels, at 0.6 of its brightest
    // (the "x0" under the dialog is 143, the live ones 254); the digit is the
    // rightmost glyph at least 0.45 of the plate tall ("x" is shorter), and it
    // is named by the four cut from those frames (templates/skewer_lives_*),
    // both resized to LIVES_GLYPH, by the smallest mean difference (0 to 1).
    // Over the 429 frames of the grill of the six rounds and the staged ones,
    // both formats -- 310 of three, 88 of two, 29 of one, 2 of none -- the
    // digit it was named by differed by 0.0546 at most, the runner-up by
    // 0.2882 at least; LIVES_DIFF_MAX is 0.17, the middle, and a glyph over
    // it is no digit.
    val LIVES_BOX = doubleArrayOf(0.1709, 0.1258, 0.2337, 0.1480)
    const val LIVES_BRIGHT = 0.6
    const val LIVES_GLYPH_H = 0.45
    val LIVES_GLYPH = Size(12.0, 18.0)
    const val LIVES_DIFF_MAX = 0.17

    /** The lives digit and its mean difference to the template it was named by. */
    data class Lives(val n: Int, val diff: Double, val second: Double) {
        fun toOracle(): Map<String, Any?> = mapOf("n" to n, "diff" to diff, "second" to second)
    }

    // ------------------------------------------------------------------------
    // The combo
    // ------------------------------------------------------------------------
    // After a guest served right -- from the second in a row on -- the game
    // writes "N Combo" over the guests for about a second: it pops, shrinks,
    // rises and fades (notes/skewer.md). Its pink is one flat colour, H
    // 161-162 S 100 V up to 250 (r2 191927_after10, "2 Combo"), which no
    // guest wears: Palmon's pink and the others' fall outside H 158-166 S
    // 85-118. A glyph is a blob of that colour 0.012 to 0.035 of game_rect
    // tall, no wider than 0.06, of 30 px or more, in the band fy 0.36-0.49
    // over the guests; the text is the most glyphs standing on one row
    // (their middles within 0.012). Over the 482 frames of the grill in the
    // seven rounds, both formats: 33 had one or more such glyphs, and every one of
    // them, looked at, is the text -- six glyphs fresh ("2 Combo" spans 0.21
    // to 0.25 of the width), fewer as it fades -- and no other frame had one.
    // So one glyph is the text; its absence says nothing, because the first
    // guest of a round gets none and a faded text is gone in a second. The
    // number is not read: the task counts for itself.
    val COMBO_BAND = doubleArrayOf(0.36, 0.49)
    val COMBO_PINK = Dungeon.Hsv(intArrayOf(158, 85, 170), intArrayOf(166, 118, 255))
    val COMBO_GLYPH_H = doubleArrayOf(0.012, 0.035)
    const val COMBO_GLYPH_W_MAX = 0.06
    const val COMBO_GLYPH_PX_MIN = 30
    const val COMBO_ROW = 0.012

    /** How many glyphs of the "N Combo" text stand on one row over the guests; 0 where there is none. */
    fun comboGlyphs(img: Mat, anchor: Dungeon.Anchor = ROUND): Int {
        val (_, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val sub = Py.crop(img, maxOf(0, Py.int(y0 + COMBO_BAND[0] * gh)),
                          minOf(img.rows(), Py.int(y0 + COMBO_BAND[1] * gh)), 0, img.cols()) ?: return 0
        val m = Cv.hsvMask(sub, COMBO_PINK)
        val (n, stats) = Cv.components(m)
        m.release()
        val glyphs = (1 until n).map { stats[it] }.filter {
            it[4] >= COMBO_GLYPH_PX_MIN && it[3] >= COMBO_GLYPH_H[0] * gh && it[3] <= COMBO_GLYPH_H[1] * gh &&
                it[2] <= COMBO_GLYPH_W_MAX * gw
        }
        var best = 0
        for (g in glyphs) {
            val cy = g[1] + g[3] / 2.0
            val row = glyphs.count { kotlin.math.abs(it[1] + it[3] / 2.0 - cy) <= COMBO_ROW * gh }
            if (row > best) best = row
        }
        return best
    }

    // ------------------------------------------------------------------------
    // The plate
    // ------------------------------------------------------------------------
    // The plate is not read. The game lays the ingredients on it at uneven
    // places -- a gap before the last of five on
    // skewer_plate5_short_1080x1920_none0_192541 -- so there is no lattice to
    // read it by (the laboratory's own note: "less reliable there"), and the
    // share of saturated pixels over its stick, tried, runs into the guests
    // standing behind it and the served skewer on its way out: 0.00 to 0.52
    // on the 266 frames taken between two orders, 0.14 to 0.45 on the 52
    // with a whole skewer on it. What
    // says a skewer went out right is the order changing with the lives
    // unchanged, and what says it went out wrong is a life lost.
}

/**
 * The templates Chef's Special reads by: the twelve ingredients, cut from
 * the grill of skewer_order3_1080x1920_none0_191214 at 160 x 160 px
 * (templates/skewer_<name>.png), and the four lives digits
 * (templates/skewer_lives_<n>.png). Loaded once from [assets], as Vision's.
 */
class SkewerIcons(private val assets: AssetSource) {

    companion object {
        /**
         * The laboratory's names where the art is the same, the art's own
         * where the frames say what it is: the laboratory's `pink_item` is an
         * octopus and `brown_item` a potato, its `fish_cake` a mushroom (its
         * own comments call it one), and three cells carry new art -- a
         * dumpling where the egg was, corn, asparagus where the radish was.
         */
        val NAMES = listOf("raw_meat", "dark_meat", "shrimp", "dumpling",
                           "leaf_wrap", "mushroom", "corn", "tomato",
                           "asparagus", "octopus", "potato", "onion")
        /** The width of a template as cut, 160 px, of game_rect's 1147 at 1080 x 1920. */
        const val TEMPLATE_W = 160.0
        const val TEMPLATE_GW = 1147.0

        // The ingredient's own pixels: the cell behind it is dark and grey,
        // the ingredient brighter or coloured (the laboratory's
        // `_grid_icon_mask`, both tests: dark_meat fails the brightness one),
        // less one pixel of edge, where a resize blends the two.
        const val MASK_GRAY = 60
        const val MASK_SAT = 70
    }

    private fun decode(path: String, flags: Int): Mat? {
        val bytes = assets.bytes(path) ?: return null
        val mat = Imgcodecs.imdecode(MatOfByte(*bytes), flags)
        return if (mat.empty()) null else mat
    }

    val icons: Map<String, Mat> by lazy {
        NAMES.associateWith { decode("templates/skewer_$it.png", Imgcodecs.IMREAD_COLOR)
            ?: throw IllegalStateException("templates/skewer_$it.png is missing") }
    }
    val digits: Map<Int, Mat> by lazy {
        (0..3).associateWith { decode("templates/skewer_lives_$it.png", Imgcodecs.IMREAD_GRAYSCALE)
            ?: throw IllegalStateException("templates/skewer_lives_$it.png is missing") }
    }

    /** The mask of an ingredient's own pixels (see [MASK_GRAY]). */
    internal fun mask(t: Mat): Mat {
        val gray = Cv.gray(t)
        val hsv = Cv.hsv(t)
        val sat = Mat()
        Core.extractChannel(hsv, sat, 1)
        val a = Mat(); val b = Mat(); val m = Mat()
        // 0 and 1, not 0 and 255: TM_SQDIFF weighs every difference by the
        // mask's value squared.
        Imgproc.threshold(gray, a, MASK_GRAY.toDouble(), 1.0, Imgproc.THRESH_BINARY)
        Imgproc.threshold(sat, b, MASK_SAT.toDouble(), 1.0, Imgproc.THRESH_BINARY)
        Core.bitwise_or(a, b, m)
        val eroded = Mat()
        Imgproc.erode(m, eroded, Mat.ones(3, 3, CvType.CV_8U))
        gray.release(); hsv.release(); sat.release(); a.release(); b.release(); m.release()
        return eroded
    }

    /** Mean squared difference over [mask]'s pixels, per channel value, of [templ] laid on [img] at every place. */
    internal fun sqdiff(img: Mat, templ: Mat, mask: Mat): Mat {
        val m3 = Mat()
        Core.merge(listOf(mask, mask, mask), m3)
        val weight = Core.countNonZero(mask) * 3.0
        val r = Mat()
        Imgproc.matchTemplate(img, templ, r, Imgproc.TM_SQDIFF, m3)
        m3.release()
        Core.multiply(r, Scalar(1.0 / maxOf(1.0, weight)), r)
        return r
    }

    // ------------------------------------------------------------------------
    // The grill's twelve
    // ------------------------------------------------------------------------

    /** One cell of the grill: what it holds, how well, and by how much over the runner-up. */
    data class Cell(val name: String, val score: Double, val margin: Double) {
        fun toOracle(): Map<String, Any?> = mapOf("name" to name, "score" to score, "margin" to margin)
    }

    // Over the 392 frames of a round in play (Skewer.playing) of the six
    // rounds, both formats, the twelve came out in the same order on 391, the
    // least margin to the runner-up 323 and the worst score 7638 (a cell the
    // tap had just lit with its blue ring); the one other is the first frame
    // of the Success dialog growing over the grill (191700_look), ten cells
    // dark_meat under the dim. So a round's grid is believed only as twelve
    // different names, which is what the task has to ask (PLAN_SKEWER.md SK2).

    /**
     * The twelve cells, row by row, each named by the template of the least
     * masked squared difference on the cell cut to the template's size.
     */
    fun grid(img: Mat, anchor: Dungeon.Anchor = Skewer.ROUND): List<Cell> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val out = ArrayList<Cell>()
        val half = Skewer.ICON_HALF_FW * gw
        for (cy in Skewer.CELL_FY) for (cx in Skewer.CELL_FX) {
            val px = x0 + cx * gw; val py = y0 + cy * gh
            val sub = Py.crop(img, Py.int(py - half), Py.int(py + half), Py.int(px - half), Py.int(px + half))
            if (sub == null || sub.rows() < 8 || sub.cols() < 8) { out.add(Cell("", Double.MAX_VALUE, 0.0)); continue }
            val cellImg = Mat()
            Imgproc.resize(sub, cellImg, Size(TEMPLATE_W, TEMPLATE_W), 0.0, 0.0, Imgproc.INTER_AREA)
            val scores = icons.map { (name, t) ->
                val m = masks.getValue(name)
                val r = sqdiff(cellImg, t, m)
                val v = r.get(0, 0)[0]
                r.release()
                name to v
            }.sortedBy { it.second }
            cellImg.release()
            out.add(Cell(scores[0].first, scores[0].second, scores[1].second - scores[0].second))
        }
        return out
    }

    private val masks: Map<String, Mat> by lazy { icons.mapValues { mask(it.value) } }

    // ------------------------------------------------------------------------
    // The order
    // ------------------------------------------------------------------------
    // The guest's skewer in the speech bubble. How many ingredients, and
    // where, the prototype of 2026-09-30 measured over 40 orders it served
    // (every one of them right, the lives unchanged), 1080 x 1920: the game
    // lays N ingredients centred on the bubble at a pitch of its own for
    // each N, the left edge of a template (80 px) at
    //
    //      N = 2   139 250                 pitch 111 px
    //      N = 3   120 195 269             74.5
    //      N = 4   108 164 220 275         55.7
    //      N = 5   106 150 192 236 281     43.8
    //
    // px into the crop that starts at x 290 -- the centre slot's edge at 485
    // px, 0.42807 of game_rect. Stage 1 ordered two or three, stage 2 three,
    // stage 3 three or four, stage 4 four or five, stage 5 five (all nine); six
    // was not seen, a lattice of six is not guessed, and such an order reads
    // null until it is measured. Packed at four and five, each ingredient
    // covers the right side of the one before it, so a template is laid with
    // the left 0.55 of its own pixels only (ORDER_LEFT). The laboratory's
    // 37 px for every order was measured on fives only and is none of these.
    val ORDER_BOX = doubleArrayOf(0.2581, 0.2283, 0.6940, 0.2939)
    val SLOT0_FX = 0.42807
    val PITCH_FX = mapOf(2 to 0.09678, 3 to 0.06495, 4 to 0.04856, 5 to 0.03819)
    val SLOT_SEARCH_FX = 0.007
    val ORDER_LEFT = 0.55
    val ORDER_SCALE = 0.5
    // Every slot of the right N is an ingredient's place, and a lattice of
    // more slots than there are ingredients puts one between two of them.
    // The worst slot's difference (the mean squared difference per channel
    // value, see [sqdiff]), this reader over every frame of the six rounds
    // played for the measurement whose order is known -- the orders the
    // prototype served with the lives unchanged, and one round's seven read
    // by eye -- 100 frames, 85 at 1080 x 1920 and 15 at 1080 x 2235, N 2 to
    // 5, all 100 read right: the right N at most 2022, a larger N at least
    // 4675. So the order is the largest N whose worst slot is under
    // SLOT_MAX, 3350, the middle of that gap; a smaller N fits too -- its
    // slots are a subset -- and is not what the bubble holds. The three
    // frames of a known order it answered null on are the round's end, the
    // dialog or the "PERFECT" burst over the bubble.
    val SLOT_MAX = 3350.0
    // And only on a bubble: under a dialog, and in the countdown before the
    // first guest, the bubble is gone or dimmed and dark_meat -- dark, like
    // the dim -- fits every slot under SLOT_MAX. The bubble is white: the
    // share of the order box at S <= 30 and V >= 230 measured 0.444-0.595 on
    // every staged frame with an order, both formats, and 0.000-0.038 on
    // every one without (the dialogs, the countdown, a guest between two
    // orders, the menu and the stage popup behind). BUBBLE_WHITE is 0.24,
    // the middle of that gap.
    val BUBBLE = Dungeon.Hsv(intArrayOf(0, 0, 230), intArrayOf(179, 30, 255))
    val BUBBLE_WHITE = 0.24

    /** The order, left to right, and every N's worst slot it was chosen by. */
    data class Order(val names: List<String>, val worst: Map<Int, Double>) {
        fun toOracle(): Map<String, Any?> = mapOf("names" to names,
                                                  "worst" to worst.mapKeys { it.key.toString() })
    }

    private var scaledFor = -1
    private var scaled: Map<String, Pair<Mat, Mat>> = emptyMap()

    /** The templates at the order's scale for a rectangle [gw] wide, masked to their left [ORDER_LEFT]. */
    private fun orderTemplates(gw: Int): Map<String, Pair<Mat, Mat>> {
        if (scaledFor == gw) return scaled
        val side = maxOf(8.0, TEMPLATE_W * ORDER_SCALE * gw / TEMPLATE_GW)
        scaled = icons.mapValues { (_, t) ->
            val s = Mat()
            Imgproc.resize(t, s, Size(side, side), 0.0, 0.0, Imgproc.INTER_AREA)
            val m = mask(s)
            // the visible part of a packed ingredient is its left side
            val cols = IntArray(m.cols()) { c -> Core.countNonZero(m.col(c)) }
            val lit = cols.indices.filter { cols[it] > 0 }
            if (lit.isNotEmpty()) {
                val cut = Py.int(lit.first() + ORDER_LEFT * (lit.last() - lit.first()))
                if (cut < m.cols()) m.colRange(cut, m.cols()).setTo(Scalar(0.0))
            }
            s to m
        }
        scaledFor = gw
        return scaled
    }

    /** The order in the speech bubble, or null where no N fits (no bubble, a half-drawn one, an N never measured). */
    fun order(img: Mat, anchor: Dungeon.Anchor = Skewer.ROUND): Order? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val left = Py.int(x0 + ORDER_BOX[0] * gw)
        val strip = Py.crop(img, Py.int(y0 + ORDER_BOX[1] * gh), Py.int(y0 + ORDER_BOX[3] * gh),
                            left, Py.int(x0 + ORDER_BOX[2] * gw)) ?: return null
        val white = Cv.hsvMask(strip, BUBBLE)
        val bubble = Cv.share(white)
        white.release()
        if (bubble < BUBBLE_WHITE) return null
        val templates = orderTemplates(gw)
        val names = ArrayList<String>()
        val curves = ArrayList<DoubleArray>()
        for ((name, tm) in templates) {
            val (t, m) = tm
            if (strip.rows() < t.rows() || strip.cols() < t.cols()) return null
            val r = sqdiff(strip, t, m)
            val cols = DoubleArray(r.cols()) { Double.MAX_VALUE }
            for (y in 0 until r.rows()) for (x in 0 until r.cols()) {
                val v = r.get(y, x)[0]
                if (v < cols[x]) cols[x] = v
            }
            r.release()
            names.add(name); curves.add(cols)
        }
        val width = curves[0].size
        val worst = LinkedHashMap<Int, Double>()
        val read = HashMap<Int, List<String>>()
        val search = maxOf(1, Py.int(SLOT_SEARCH_FX * gw))
        for ((n, pitch) in PITCH_FX) {
            var w = 0.0
            val got = ArrayList<String>()
            for (i in 0 until n) {
                val x = Py.roundInt((SLOT0_FX + (i - (n - 1) / 2.0) * pitch) * gw + x0) - left
                val lo = maxOf(0, x - search); val hi = minOf(width - 1, x + search)
                if (lo > hi) { w = Double.MAX_VALUE; break }
                var best = Double.MAX_VALUE; var bestName = ""
                for (c in lo..hi) for (k in names.indices) {
                    if (curves[k][c] < best) { best = curves[k][c]; bestName = names[k] }
                }
                w = maxOf(w, best)
                got.add(bestName)
            }
            worst[n] = w
            read[n] = got
        }
        val n = worst.filter { it.value < SLOT_MAX }.keys.maxOrNull() ?: return null
        return Order(read.getValue(n), worst)
    }

    // ------------------------------------------------------------------------
    // Right or wrong
    // ------------------------------------------------------------------------
    // What one frame after Complete says about the skewer that went out: a
    // life less than before is WRONG, the "N Combo" text is RIGHT, anything
    // else is UNCLEAR. Played over every serve of the six rounds with frames
    // after it (54: 49 right, 5 wrong -- r2's seventh on purpose, three of
    // r4's read short, h1's fourth on purpose), frame by frame until the next
    // skewer, on a clock of whole seconds from the last frame before Complete:
    // all five wrong ones said WRONG, 4 at 1 s and 1 at 2 s; 24 right ones
    // said RIGHT at 0 to 2 s; 25 right ones stayed
    // UNCLEAR -- the first guest of a round gets no text, and the text lives
    // about a second against the prototype's 0.7 s between frames. No right
    // skewer ever said WRONG and no wrong one RIGHT. So UNCLEAR is not a
    // mistake: a skewer that has not said WRONG three seconds after Complete,
    // with the lives read on two frames, went out right.
    enum class Served { RIGHT, WRONG, UNCLEAR }

    /** What [img], a frame after Complete, says of the skewer that went out; [livesBefore] read before Complete. */
    fun served(img: Mat, livesBefore: Int): Served {
        val now = lives(img)
        if (now != null && now.n < livesBefore) return Served.WRONG
        if (Skewer.comboGlyphs(img) >= 1) return Served.RIGHT
        return Served.UNCLEAR
    }

    // ------------------------------------------------------------------------
    // The lives
    // ------------------------------------------------------------------------

    /** The lives on the plate by the heart, or null where no digit stands there. */
    fun lives(img: Mat, anchor: Dungeon.Anchor = Skewer.ROUND): Skewer.Lives? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val b = Skewer.LIVES_BOX
        val sub = Py.crop(img, Py.int(y0 + b[1] * gh), Py.int(y0 + b[3] * gh),
                          Py.int(x0 + b[0] * gw), Py.int(x0 + b[2] * gw)) ?: return null
        val gray = Cv.gray(sub)
        val mm = Core.minMaxLoc(gray)
        val bin = Mat()
        Imgproc.threshold(gray, bin, Skewer.LIVES_BRIGHT * mm.maxVal - 0.5, 255.0, Imgproc.THRESH_BINARY)
        gray.release()
        try {
            val (n, stats) = Cv.components(bin)
            val glyph = (1 until n).map { stats[it] }
                .filter { it[3] >= Skewer.LIVES_GLYPH_H * bin.rows() }
                .maxByOrNull { it[0] } ?: return null
            val crop = bin.submat(glyph[1], glyph[1] + glyph[3], glyph[0], glyph[0] + glyph[2])
            val small = Mat()
            Imgproc.resize(crop, small, Skewer.LIVES_GLYPH, 0.0, 0.0, Imgproc.INTER_AREA)
            var bestN = -1; var bestD = Double.MAX_VALUE; var secondD = Double.MAX_VALUE
            for ((d, t) in digits) {
                val ts = Mat()
                Imgproc.resize(t, ts, Skewer.LIVES_GLYPH, 0.0, 0.0, Imgproc.INTER_AREA)
                val diff = Mat()
                Core.absdiff(small, ts, diff)
                val v = Core.mean(diff).`val`[0] / 255.0
                ts.release(); diff.release()
                if (v < bestD) { secondD = bestD; bestD = v; bestN = d } else if (v < secondD) secondD = v
            }
            small.release()
            if (bestD > Skewer.LIVES_DIFF_MAX) return null
            return Skewer.Lives(bestN, bestD, secondD)
        } finally {
            bin.release()
        }
    }
}