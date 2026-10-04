package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * The game's presets: the bar that shows which one is in use, and the list
 * it drops. Six places carry one (PLAN_PRESET_SWITCH.md 2.1; the sixth,
 * Overdrive, PLAN_DAILY_LOST_SECTOR_PRESETS.md 4.1), in two kinds:
 *
 *   compact   Digivice, Tactical Memory, Food Effects, Overdrive: `[pencil] 1 Bosses [v]`,
 *             a list of ten bars that all fit
 *   wide      Skill Cards, Support Digimon: `[ Bosses v ]`, no number, a list
 *             of card rows that scrolls, a tick on the one in use
 *
 * Nothing here reads a name. A slot is asked for by its number; where the
 * game shows the number (the wide list's row heads) it is read by shape
 * with [Dungeon.readDigit], and where a switch has to be proved it is proved
 * by comparing two pictures of the same text ([sameText]): the row that was
 * tapped against the bar afterwards.
 *
 * Every number below was measured on LDPlayer at 1080 x 1920 on 2026-09-25
 * (the 35 frames of corpus/preset/), and the whole corpus was swept for the
 * bar; it is all window space, as everywhere (Dungeon.gameRect).
 */
object Preset {

    enum class Kind { COMPACT, WIDE }

    /**
     * Where each place's bar stands, its top left corner. The six are far
     * enough apart that [PLACE_TOL] cannot take one for another: the nearest
     * two, Skill Cards and Support Digimon, share a row and differ by 0.129
     * across -- Skill Cards has its "Auto" switch left of the bar.
     *
     * [arrowFx] and [arrowFy] are the middle of the bar's arrow as measured.
     * A reader finds the arrow on every frame the bar is read on; these are
     * for the one frame it is not -- a wide list open dims the bar under it,
     * and its arrow is still what closes it.
     */
    enum class Place(val key: String, val label: String, val kind: Kind,
                     val barFx0: Double, val barFy0: Double, val arrowFx: Double, val arrowFy: Double,
                     val anchor: Dungeon.Anchor) {
        DIGIVICE("digivice", "Digivice", Kind.COMPACT, 0.4385, 0.0985, 0.8026, 0.1152, Dungeon.Anchor.TOP),
        TACTICAL("tactical", "Tactical Memory", Kind.COMPACT, 0.1299, 0.4348, 0.4381, 0.4520, Dungeon.Anchor.BOTTOM),
        FOOD("food", "Food Effects", Kind.COMPACT, 0.2772, 0.4944, 0.6465, 0.5111, Dungeon.Anchor.BOTTOM),
        SKILL_CARDS("skill_cards", "Skill Cards", Kind.WIDE, 0.2947, 0.1742, 0.7537, 0.1887, Dungeon.Anchor.BOTTOM),
        SUPPORT("support", "Support Digimon", Kind.WIDE, 0.1656, 0.1742, 0.7603, 0.1887, Dungeon.Anchor.BOTTOM),
        // The sixth place (PLAN_DAILY_LOST_SECTOR_PRESETS.md 4.1 point 2),
        // measured on LDPlayer instance 0 on 2026-09-28/29: the Overdrive
        // page's "Drive Ability" tab ([overdriveTabs]) carries a compact bar,
        // `[pencil] 2 FullStack [v]` and ten rows, the list closing itself
        // on the tap like the other three. Its bar stands at the bottom: the
        // same window fractions at 1080 x 1920, 2235, 2340, 1200 x 1920, 720 x
        // 1600 (40 rows of headroom), landscape, and on the whole 1080 x 2520
        // frame with 180 rows of headroom, where the top and the middle read
        // it 0.075 and 0.037 lower. The nearest place, Skill Cards, is 0.033
        // and 0.035 away, more than PLACE_TOL, and has no pencil.
        // The list: ten rows 0.0268 tall at a pitch of 0.0323, the first
        // 0.0091 under the bar, the last ending at 0.4985 -- inside
        // PresetSkill.COMPACT_SPAN. The bar against the tapped row, six
        // times: 0.915 to 0.926, over TEXT_SAME but thinner than the other
        // compact places' 0.962 to 0.993; every other row is refused by the
        // width of its ink, and row against row over two takes of the list
        // is 1.000 on the same row and 0.687 at most beside it.
        OVERDRIVE("overdrive", "Overdrive", Kind.COMPACT, 0.2616, 0.1394, 0.6622, 0.1558, Dungeon.Anchor.BOTTOM);

        val arrow get() = Explore.Target(arrowFx, arrowFy, anchor)
    }

    /**
     * Where the preset pages stand on a display taller than the canvas
     * ceiling (Dungeon.Anchor, PLAN_FORMATE.md V4). Measured 2026-09-27 over
     * the V3 rows of the corpus against the 1920 twin: the Digivice bar at
     * fy 0.0985 there stands at the top (0.0982 to 0.1045; the bar is cut by
     * 11 rows at 180 of headroom and lost at 200, which only the rows over
     * the canvas mend), and its list's first row at 0.1414 reads 0.1411 to
     * 0.1417 there; the Skill Cards bar at 0.1742 stands at the bottom (0.1740
     * to 0.1742), and the heads of its list in the middle (0.3705 to 0.3708
     * against 0.3707) -- [WIDE_LIST]. Tactical Memory, Food Effects and
     * Support Digimon were not in the tour: the first two are pages of their
     * own, the last shares the Skill Cards row. So a place's own [Place.anchor]
     * is asked first and the other two after it ([place]): where the anchors
     * lie further apart than [PLACE_TOL] only the right one matches, and
     * where they do not they are one place to the tap.
     */
    val WIDE_LIST = Dungeon.Anchor.MIDDLE

    // ------------------------------------------------------------------------
    // The bar
    // ------------------------------------------------------------------------
    // One navy, H 107 S 255 V 50, on all six, and one blue for the arrow's
    // box, H 107 S 200 V 163 -- the same blue the compact list's bars are
    // drawn in. Tactical Memory's bar is darker at its left end (V 28), which
    // the floor of 15 keeps.
    val BAR_NAVY = Dungeon.Hsv(intArrayOf(100, 230, 15), intArrayOf(115, 255, 75))
    val ARROW_BLUE = Dungeon.Hsv(intArrayOf(100, 170, 130), intArrayOf(113, 230, 195))
    // The bars measure 0.34 to 0.62 of the width, 0.029 (wide) to 0.034
    // (compact) of the height, fill 0.84 to 0.93. The arrow's box is a square
    // 0.75 of the bar's height at its right end, searched in the last two
    // heights of the bar.
    //
    // Swept over the 843 whole frames of the corpus on 2026-09-25: a navy
    // bar of that size stands on 42 of them, the arrow on 10. Six of those
    // are the preset bar (Food Effects twice, Skill Cards four times); the
    // other four are the Friends list's "LastLogin" sort, the same dropdown
    // artwork at fx 0.431 fy 0.091 -- 0.008 from where the Digivice's bar
    // stands. What tells them apart is the pencil ([PENCIL_MIN]) and the
    // place ([Place]); the bar reader answers them all.
    const val BAR_W_MIN = 0.25
    val BAR_H = doubleArrayOf(0.02, 0.045)
    const val BAR_FILL_MIN = 0.7
    const val ARROW_H_MIN = 0.6
    val ARROW_ASPECT = doubleArrayOf(0.75, 1.33)
    // The pencil: the white share of the bar's left end, 1.3 of its height
    // wide. 0.050 to 0.064 on the three compact bars, 0.000 on the two wide
    // ones and on the Friends sort.
    val INK_WHITE = Dungeon.Hsv(intArrayOf(0, 0, 200), intArrayOf(179, 80, 255))
    const val PENCIL_W = 1.3
    const val PENCIL_MIN = 0.02
    // A bar is its place's within this, both ways. Every bar measured stood
    // within 0.0001 of its place over two visits.
    const val PLACE_TOL = 0.02

    /** A preset bar: its box, the arrow's box at its right end, and whether it carries a pencil. */
    data class Bar(val fx0: Double, val fy0: Double, val fx1: Double, val fy1: Double,
                   val arrowFx0: Double, val arrowFy0: Double, val arrowFx1: Double, val arrowFy1: Double,
                   val pencil: Boolean,
                   /** The rectangle the bar was read in (Dungeon.Anchor); not in the oracle's dict. */
                   val anchor: Dungeon.Anchor = Dungeon.Anchor.BOTTOM) {
        /** Where the arrow is tapped: the middle of its box. */
        val arrow get() = Explore.Target((arrowFx0 + arrowFx1) / 2, (arrowFy0 + arrowFy1) / 2, anchor)
        val height get() = fy1 - fy0
        fun toOracle(): Map<String, Any?> = mapOf(
            "fx0" to fx0, "fy0" to fy0, "fx1" to fx1, "fy1" to fy1,
            "arrow" to listOf(arrowFx0, arrowFy0, arrowFx1, arrowFy1), "pencil" to pencil)
    }

    /** A box in this frame's pixels, from window fractions, clamped to the picture. */
    private class Px(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    private fun px(img: Mat, fx0: Double, fy0: Double, fx1: Double, fy1: Double,
                   anchor: Dungeon.Anchor): Px {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        return Px(maxOf(0, Py.roundInt(x0 + fx0 * gw)), maxOf(0, Py.roundInt(y0 + fy0 * gh)),
                  minOf(img.cols(), Py.roundInt(x0 + fx1 * gw)), minOf(img.rows(), Py.roundInt(y0 + fy1 * gh)))
    }

    private fun share(img: Mat, box: Px, colour: Dungeon.Hsv): Double {
        val sub = Py.crop(img, box.y0, box.y1, box.x0, box.x1) ?: return 0.0
        val m = Cv.hsvMask(sub, colour)
        val s = Cv.share(m)
        m.release()
        return s
    }

    /** Every preset bar on the frame, top to bottom: a navy bar with the arrow's box at its right end. */
    fun bars(img: Mat, anchor: Dungeon.Anchor = Dungeon.Anchor.BOTTOM): List<Bar> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val mask = Cv.hsvMask(img, BAR_NAVY)
        val (n, stats) = Cv.components(mask)
        mask.release()
        val out = ArrayList<Bar>()
        for (i in 1 until n) {
            val (x, y, w, h, a) = stats[i].toList()
            if (w / gw.toDouble() < BAR_W_MIN) continue
            if (h / gh.toDouble() !in BAR_H[0]..BAR_H[1]) continue
            if (a / (w.toDouble() * h) < BAR_FILL_MIN) continue
            val arrow = arrowBox(img, x, y, w, h) ?: continue
            val pencil = share(img, Px(x, y, minOf(img.cols(), x + Py.int(PENCIL_W * h)), y + h), INK_WHITE) >= PENCIL_MIN
            out.add(Bar((x - x0) / gw.toDouble(), (y - y0) / gh.toDouble(),
                        (x + w - x0) / gw.toDouble(), (y + h - y0) / gh.toDouble(),
                        (arrow[0] - x0) / gw.toDouble(), (arrow[1] - y0) / gh.toDouble(),
                        (arrow[0] + arrow[2] - x0) / gw.toDouble(), (arrow[1] + arrow[3] - y0) / gh.toDouble(),
                        pencil, anchor))
        }
        return out.sortedBy { it.fy0 }
    }

    /** The arrow's box in the last two heights of a bar at x, y, w, h: x, y, w, h in pixels, or null. */
    private fun arrowBox(img: Mat, x: Int, y: Int, w: Int, h: Int): IntArray? {
        val left = maxOf(x, x + w - 2 * h)
        val sub = Py.crop(img, y, y + h, left, x + w) ?: return null
        val m = Cv.hsvMask(sub, ARROW_BLUE)
        val (n, stats) = Cv.components(m)
        m.release()
        var best: IntArray? = null
        for (i in 1 until n) {
            val (ax, ay, aw, ah, area) = stats[i].toList()
            if (ah < ARROW_H_MIN * h) continue
            if (aw / ah.toDouble() !in ARROW_ASPECT[0]..ARROW_ASPECT[1]) continue
            if (best == null || area > best[4]) best = intArrayOf(left + ax, y + ay, aw, ah, area)
        }
        return best
    }

    /**
     * Which of the six places is open, and its bar; null on any other
     * screen. Each place is looked for at its own anchor first, then at the
     * other two (WIDE_LIST); on a frame without headroom they are one.
     */
    fun place(img: Mat): Pair<Place, Bar>? {
        val found = HashMap<Dungeon.Anchor, List<Bar>>()
        val order = if (Dungeon.headroom(img) == 0) listOf(Dungeon.Anchor.BOTTOM) else null
        for (p in Place.values()) {
            val anchors = order ?: listOf(p.anchor) + Dungeon.Anchor.values().filter { it != p.anchor }
            for (a in anchors) {
                for (b in found.getOrPut(a) { bars(img, a) }) {
                    if (abs(b.fx0 - p.barFx0) > PLACE_TOL || abs(b.fy0 - p.barFy0) > PLACE_TOL) continue
                    if (b.pencil != (p.kind == Kind.COMPACT)) continue
                    return p to b
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------------
    // The Overdrive page's tabs
    // ------------------------------------------------------------------------
    // The Overdrive icon opens its page on "Base" every time -- twice on
    // 2026-09-28, the second after Drive Ability had been left open -- and
    // the preset bar is on the third tab, "Drive Ability", only. So the page
    // is known and its third tab found by the row of three along the bottom
    // (PLAN_DAILY_LOST_SECTOR_PRESETS.md G6). Measured on the eighteen
    // Overdrive frames of 2026-09-28/29, 1080 x 1920 and six format rows:
    //
    //   a closed tab   H 107 S 199 V 164, the arrow's own blue (ARROW_BLUE)
    //   the open tab   H 92-100 S 255 V 209-246
    //   each tab       fw 0.1931-0.1953, fh 0.0491-0.0512, row fy 0.9562-0.9588
    //   the middles    fx 0.2049-0.2060, 0.4195-0.4211, 0.6339-0.6356
    //
    // the same fractions on every row, 2520 with its 180 rows of headroom
    // among them, so the row stands at the bottom like the bar. The Base
    // tab's page has one more button of the open tab's cyan, at fy 0.8667
    // and fw 0.2232, which the row band and the three-in-a-row rule keep out.
    val TAB_ON = Dungeon.Hsv(intArrayOf(88, 240, 190), intArrayOf(102, 255, 255))
    val TAB_W = doubleArrayOf(0.17, 0.22)
    val TAB_H = doubleArrayOf(0.04, 0.065)
    val TAB_ROW_FY = doubleArrayOf(0.93, 0.985)
    // One row: the middles within this of each other.
    const val TAB_ROW_TOL = 0.01
    // Three tabs a pitch of 0.2145 apart; a gap may differ from the other by this much.
    const val TAB_PITCH_TOL = 0.02
    // The row is one piece of the game's artwork: the Friends list has it
    // too -- "List", "Add", "Block" and the X, at the same places to the
    // fourth decimal -- and the corpus sweep of 2026-09-29 found it on four
    // Friends frames (corpus/passive/unclear_112907, _134538, _221723,
    // _223137). What the list has and the Overdrive page has not is its white
    // rows: the share of pale pixels (S 60 and under, V 200 and over) in the
    // window's middle, fx 0.12 to 0.88, fy 0.12 to 0.80, is 0.614 to 0.624
    // on the four lists and 0.010 to 0.052 on the eleven Overdrive frames of
    // 1080 x 1920, all three tabs, list open or closed. The ceiling is the
    // middle.
    val TAB_PAGE_BAND = doubleArrayOf(0.12, 0.12, 0.88, 0.80)
    val PALE = Dungeon.Hsv(intArrayOf(0, 0, 200), intArrayOf(179, 60, 255))
    const val TAB_PAGE_PALE_MAX = 0.33

    /** One tab of the Overdrive page's row; [on] is the tab that is open. */
    data class Tab(val fx: Double, val fy: Double, val fw: Double, val fh: Double, val on: Boolean) {
        val target get() = Explore.Target(fx, fy, Dungeon.Anchor.BOTTOM)
        fun toOracle(): Map<String, Any?> = mapOf("fx" to fx, "fy" to fy, "fw" to fw, "fh" to fh, "on" to on)
    }

    /**
     * The Overdrive page's three tabs, left to right -- Base, Overdrive,
     * Drive Ability -- exactly one of them open; null on any other screen,
     * the Friends list with the same row included ([TAB_PAGE_PALE_MAX]).
     * The preset bar is on the third ([Place.OVERDRIVE]).
     */
    fun overdriveTabs(img: Mat): List<Tab>? {
        // The raw components, not findButtons: its closing (15 px across)
        // joins two closed tabs on the landscape display, where the gap
        // between them is 12 px of a 646 px canvas; the tabs' own text makes
        // holes in them and leaves each one piece.
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        fun blobs(colour: Dungeon.Hsv, on: Boolean): List<Pair<Dungeon.Button, Boolean>> {
            val mask = Cv.hsvMask(img, colour)
            val (n, stats) = Cv.components(mask)
            mask.release()
            return (1 until n).map { i ->
                val (x, y, w, h, _) = stats[i].toList()
                Dungeon.Button((x + w / 2.0 - x0) / gw, (y + h / 2.0 - y0) / gh, w / gw.toDouble(), h / gh.toDouble()) to on
            }
        }
        val tabs = (blobs(ARROW_BLUE, false) + blobs(TAB_ON, true))
            .filter { (b, _) -> b.fw in TAB_W[0]..TAB_W[1] && b.fh in TAB_H[0]..TAB_H[1] && b.fy in TAB_ROW_FY[0]..TAB_ROW_FY[1] }
            .sortedBy { it.first.fx }
        if (tabs.size != 3 || tabs.count { it.second } != 1) return null
        if (tabs.maxOf { it.first.fy } - tabs.minOf { it.first.fy } > TAB_ROW_TOL) return null
        val gap1 = tabs[1].first.fx - tabs[0].first.fx
        val gap2 = tabs[2].first.fx - tabs[1].first.fx
        if (abs(gap1 - gap2) > TAB_PITCH_TOL || gap1 < TAB_W[0]) return null
        val (b0, b1, b2, b3) = TAB_PAGE_BAND.toList()
        if (share(img, px(img, b0, b1, b2, b3, Dungeon.Anchor.BOTTOM), PALE) > TAB_PAGE_PALE_MAX) return null
        return tabs.map { (b, isOn) -> Tab(b.fx, b.fy, b.fw, b.fh, isOn) }
    }

    // ------------------------------------------------------------------------
    // The compact list: ten bars under the bar
    // ------------------------------------------------------------------------
    // Read down one band a quarter of the way into the bar, where no row's
    // text reaches (the widest name, "7 BossDmgNEW", starts 0.1 of the bar
    // further right): a row is a run of lines whose band is the arrow's blue.
    // Measured: exactly ten runs on all three places, 51 tall at a pitch of
    // 64 on Digivice and Tactical Memory, 57 at 70 on Food Effects, the first
    // 18 to 19 under the bar. The list closed, the band finds nothing like it.
    val ROW_BAND = doubleArrayOf(0.22, 0.28)
    const val ROW_LINE_MIN = 0.8
    const val ROW_H_MIN = 0.6         // of the bar's height
    const val ROW_FIRST_MAX = 0.6     // of the bar's height, from its bottom to the first row
    const val ROWS = 10
    const val ROW_PITCH_TOL = 0.05    // of the pitch, row to row

    data class Row(val fy0: Double, val fy1: Double) {
        val cy get() = (fy0 + fy1) / 2
        fun toOracle(): List<Any?> = listOf(fy0, fy1)
    }

    /** The compact list's ten rows under [bar], top to bottom, or null if the list is not open. */
    fun compactRows(img: Mat, bar: Bar): List<Row>? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, bar.anchor)
        val w = bar.fx1 - bar.fx0
        val box = px(img, bar.fx0 + ROW_BAND[0] * w, bar.fy1, bar.fx0 + ROW_BAND[1] * w, 1.0, bar.anchor)
        val sub = Py.crop(img, box.y0, box.y1, box.x0, box.x1) ?: return null
        val m = Cv.hsvMask(sub, ARROW_BLUE)
        val rowSums = Mat()
        Core.reduce(m, rowSums, 1, Core.REDUCE_AVG, CvType.CV_32F)
        m.release()
        val lines = FloatArray(rowSums.rows())
        rowSums.get(0, 0, lines)
        rowSums.release()
        val barH = bar.height * gh
        val runs = ArrayList<IntArray>()
        var start = -1
        for (i in 0..lines.size) {
            val on = i < lines.size && lines[i] / 255.0 >= ROW_LINE_MIN
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                if (i - start >= ROW_H_MIN * barH) runs.add(intArrayOf(start, i))
                start = -1
            }
        }
        if (runs.size < ROWS) return null
        if (runs[0][0] > ROW_FIRST_MAX * barH) return null
        val list = runs.take(ROWS)
        val pitches = (1 until ROWS).map { list[it][0] - list[it - 1][0] }
        val pitch = pitches.sorted()[pitches.size / 2].toDouble()
        if (pitches.any { abs(it - pitch) > ROW_PITCH_TOL * pitch }) return null
        return list.map { Row((box.y0 + it[0] - y0) / gh.toDouble(), (box.y0 + it[1] - y0) / gh.toDouble()) }
    }

    // ------------------------------------------------------------------------
    // The wide list: row heads with a number and a tick
    // ------------------------------------------------------------------------
    // Every row of Skill Cards and Support Digimon has a head: its number at
    // the left, its name, a pencil, and a tick in a box at the right. The box
    // is one blue, H 102 S 211 V 198, fx 0.736 to 0.810 and 0.025 tall on
    // both places; a head the list's edge cuts is shorter and is not a head.
    val TICK_BOX = Dungeon.Hsv(intArrayOf(98, 190, 175), intArrayOf(106, 230, 215))
    val TICK_W = doubleArrayOf(0.06, 0.09)
    val TICK_H = doubleArrayOf(0.02, 0.03)
    // And it stands in one column. The blue alone is common: over the
    // corpus a box of that size and blue stood on 45 other frames -- the
    // Partner grid, the skins filter, the phone's HUD -- some with a digit
    // left of it that read. All 38 real heads have their box at fx 0.7358
    // to 0.8099, to the fourth place; the nearest other box is 0.7262 to
    // 0.7908. So the column, 0.006 either side.
    val TICK_FX0 = doubleArrayOf(0.730, 0.742)
    val TICK_FX1 = doubleArrayOf(0.804, 0.816)
    // The tick of the row in use is white, the others dark blue: the grey
    // share over 220 in the box is 0.101 to 0.110 on it and 0.000 on every
    // other head, eleven lists, both places.
    const val TICK_GREY = 220.0
    const val TICK_ON_MIN = 0.05
    // The number, white on the head's navy, fx 0.13 to 0.24, 0.013 either
    // side of the box's middle. Read with the dungeon counter's digit reader
    // (Dungeon.readDigit), which measures a shape and stores no picture.
    //
    // White is V 225 and over, not the 200 the ink elsewhere is. At 200 one
    // "6" of eleven frames carried a single anti-aliased pixel under its
    // foot, the 16-row grid squashed the foot to a third, and readDigit's
    // "a 4 has an empty foot" (0.35) took it for a 4. 215 and 230 both read
    // every head of every frame right; 225 is between them.
    val DIGIT_BAND = doubleArrayOf(0.13, 0.24)
    const val DIGIT_HALF_H = 0.013
    val DIGIT_WHITE = Dungeon.Hsv(intArrayOf(0, 0, 225), intArrayOf(179, 80, 255))
    // A digit is 22 to 23 px tall at 1980: 0.011. Specks and the head's edge are far under.
    const val DIGIT_H_MIN = 0.008
    // The name, between the number and the pencil.
    val NAME_BAND = doubleArrayOf(0.197, 0.493)
    const val NAME_HALF_H = 0.012

    /** One row head of the wide list: where its tick box is, its number if it reads, and whether it is in use. */
    data class Header(val fx0: Double, val fy0: Double, val fx1: Double, val fy1: Double,
                      val slot: Int?, val on: Boolean,
                      /** The rectangle the head was read in (Dungeon.Anchor); not in the oracle's dict. */
                      val anchor: Dungeon.Anchor = Dungeon.Anchor.BOTTOM) {
        val tick get() = Explore.Target((fx0 + fx1) / 2, (fy0 + fy1) / 2, anchor)
        val cy get() = (fy0 + fy1) / 2
        fun toOracle(): Map<String, Any?> = mapOf(
            "tick" to listOf(fx0, fy0, fx1, fy1), "slot" to slot, "on" to on)
    }

    /** The wide list's whole row heads on the frame, top to bottom; empty when no list is open. */
    fun headers(img: Mat, anchor: Dungeon.Anchor = WIDE_LIST): List<Header> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val mask = Cv.hsvMask(img, TICK_BOX)
        val (n, stats) = Cv.components(mask)
        mask.release()
        val out = ArrayList<Header>()
        for (i in 1 until n) {
            val (x, y, w, h, _) = stats[i].toList()
            if (w / gw.toDouble() !in TICK_W[0]..TICK_W[1]) continue
            if (h / gh.toDouble() !in TICK_H[0]..TICK_H[1]) continue
            val fx0 = (x - x0) / gw.toDouble(); val fy0 = (y - y0) / gh.toDouble()
            val fx1 = (x + w - x0) / gw.toDouble(); val fy1 = (y + h - y0) / gh.toDouble()
            if (fx0 !in TICK_FX0[0]..TICK_FX0[1] || fx1 !in TICK_FX1[0]..TICK_FX1[1]) continue
            val box = Py.crop(img, y, y + h, x, x + w) ?: continue
            val grey = Cv.gray(box)
            val white = Mat()
            Imgproc.threshold(grey, white, TICK_GREY, 255.0, Imgproc.THRESH_BINARY)
            val on = Cv.share(white) >= TICK_ON_MIN
            grey.release(); white.release()
            out.add(Header(fx0, fy0, fx1, fy1, slotNumber(img, (fy0 + fy1) / 2, anchor), on, anchor))
        }
        return out.sortedBy { it.fy0 }
    }

    /** The number at the left of the head whose middle is at [cy], or null if a digit does not read. */
    internal fun slotNumber(img: Mat, cy: Double, anchor: Dungeon.Anchor = Dungeon.Anchor.BOTTOM): Int? {
        val (_, _, _, gh) = Dungeon.gameRect(img, anchor)
        val box = px(img, DIGIT_BAND[0], cy - DIGIT_HALF_H, DIGIT_BAND[1], cy + DIGIT_HALF_H, anchor)
        val sub = Py.crop(img, box.y0, box.y1, box.x0, box.x1) ?: return null
        val mask = Cv.hsvMask(sub, DIGIT_WHITE)
        val (n, stats) = Cv.components(mask)
        val glyphs = (1 until n).map { stats[it] }.filter { it[3] >= DIGIT_H_MIN * gh }.sortedBy { it[0] }
        var value: Int? = if (glyphs.isEmpty()) null else 0
        for (g in glyphs) {
            val d = Dungeon.readDigit(mask, g)
            value = if (d == null || value == null) null else value * 10 + d
        }
        mask.release()
        return value
    }

    /**
     * Do the heads' numbers count up one by one? The list shows three or
     * four heads at a time and their numbers follow each other, so a head
     * that reads out of turn is a misreading, and the frame is not used.
     */
    fun inTurn(heads: List<Header>): Boolean {
        if (heads.isEmpty()) return false
        val slots = heads.map { it.slot ?: return false }
        return (1 until slots.size).all { slots[it] == slots[it - 1] + 1 }
    }

    // ------------------------------------------------------------------------
    // The same text twice
    // ------------------------------------------------------------------------
    // After a tap the bar shows the text of the row that was tapped, in the
    // same font at the same size. So a switch is proved by comparing the two
    // pictures: grey, cut to the ink (grey over [TEXT_INK]), the bar's text
    // looked for in the row's with a few pixels of play, TM_CCOEFF_NORMED.
    //
    //   compact, bar against the tapped row   0.962 - 0.993   (six switches)
    //   compact, bar against any other row    0.328 - 0.564,
    //                                         0.937 once: "1 BossDmg" is in "7 BossDmgNEW"
    //   compact, row against row, two frames  1.00 on the same row, 0.73 at most
    //   wide, bar name against the row head   0.959 - 0.987 against 0.585 at most
    //
    // Because one name can be inside another, the ink has to be as wide as
    // well: 210 px against 143 there, and within 2 px on every true pair.
    const val TEXT_INK = 200.0
    const val TEXT_PAD = 4
    const val TEXT_W_TOL = 0.04       // of the wider one, and never under TEXT_PAD pixels
    const val TEXT_SAME = 0.9

    /** The ink inside [box] cut to its own extent, grey, as a new Mat; null where there is none. */
    private fun ink(img: Mat, box: Px): Mat? {
        val sub = Py.crop(img, box.y0, box.y1, box.x0, box.x1) ?: return null
        val grey = Cv.gray(sub)
        val bright = Mat()
        Imgproc.threshold(grey, bright, TEXT_INK, 255.0, Imgproc.THRESH_BINARY)
        val pts = Mat()
        Core.findNonZero(bright, pts)
        bright.release()
        if (pts.empty()) {
            pts.release(); grey.release()
            return null
        }
        // The ink's own extent. OpenCV 5's Java has no boundingRect, so the
        // points findNonZero gives (x, y pairs, CV_32SC2 -- one row of them
        // or one column, whichever the build hands back) are walked by hand.
        val xy = IntArray((pts.total() * pts.channels()).toInt())
        pts.get(0, 0, xy)
        pts.release()
        var xMin = Int.MAX_VALUE; var yMin = Int.MAX_VALUE; var xMax = -1; var yMax = -1
        for (i in xy.indices step 2) {
            xMin = minOf(xMin, xy[i]); xMax = maxOf(xMax, xy[i])
            yMin = minOf(yMin, xy[i + 1]); yMax = maxOf(yMax, xy[i + 1])
        }
        val cut = Py.crop(grey, yMin, yMax + 1, xMin, xMax + 1)!!
        val out = Mat()
        cut.copyTo(out)
        grey.release()
        return out
    }

    /** The text on [bar], between the pencil (if any) and the arrow. The caller owns the Mat. */
    fun barText(img: Mat, bar: Bar): Mat? {
        val h = bar.height
        val (_, _, gw, gh) = Dungeon.gameRect(img, bar.anchor)
        val hx = h * gh / gw                 // the bar's height as a fraction of the width
        val left = bar.fx0 + (if (bar.pencil) PENCIL_W else 0.3) * hx
        return ink(img, px(img, left, bar.fy0 + 0.1 * h, bar.arrowFx0 - 0.1 * hx, bar.fy1 - 0.1 * h, bar.anchor))
    }

    /** The text of one row of the compact list under [bar]. The caller owns the Mat. */
    fun rowText(img: Mat, bar: Bar, row: Row): Mat? {
        val (_, _, gw, gh) = Dungeon.gameRect(img, bar.anchor)
        val hx = bar.height * gh / gw
        val h = row.fy1 - row.fy0
        return ink(img, px(img, bar.fx0 + PENCIL_W * hx, row.fy0 + 0.1 * h, bar.fx1 - 0.2 * hx, row.fy1 - 0.1 * h,
                           bar.anchor))
    }

    /** The name on one head of the wide list. The caller owns the Mat. */
    fun headerName(img: Mat, head: Header): Mat? =
        ink(img, px(img, NAME_BAND[0], head.cy - NAME_HALF_H, NAME_BAND[1], head.cy + NAME_HALF_H, head.anchor))

    /**
     * How well [a] matches [b], or null when the two inks are not the same
     * width -- then they are not the same text whatever the score.
     */
    fun sameText(a: Mat?, b: Mat?): Double? {
        if (a == null || b == null) return null
        val tol = maxOf(TEXT_PAD.toDouble(), TEXT_W_TOL * maxOf(a.cols(), b.cols()))
        if (abs(a.cols() - b.cols()) > tol || abs(a.rows() - b.rows()) > tol) return null
        val (t, s) = if (a.cols() <= b.cols()) a to b else b to a
        val padded = Mat()
        Core.copyMakeBorder(s, padded, TEXT_PAD, TEXT_PAD, TEXT_PAD, TEXT_PAD, Core.BORDER_REPLICATE)
        if (padded.rows() < t.rows() || padded.cols() < t.cols()) {
            padded.release()
            return null
        }
        val tf = Mat(); val sf = Mat(); val r = Mat()
        t.convertTo(tf, CvType.CV_32F)
        padded.convertTo(sf, CvType.CV_32F)
        Imgproc.matchTemplate(sf, tf, r, Imgproc.TM_CCOEFF_NORMED)
        val best = Core.minMaxLoc(r).maxVal
        tf.release(); sf.release(); r.release(); padded.release()
        return best
    }

    /** [sameText] as the yes or no a skill asks. */
    fun isSameText(a: Mat?, b: Mat?): Boolean = (sameText(a, b) ?: return false) >= TEXT_SAME
}
