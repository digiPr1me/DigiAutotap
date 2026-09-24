package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import kotlin.math.abs

/**
 * bond.py's readers, carried over line for line: `grid_button`,
 * `raise_button`, `cells`, `raised_cell` and `partner_subtab`. The tour
 * itself is not here; it comes with its own session, under the director.
 *
 * Every constant keeps its Python name and value, and the sentence that says
 * where it came from. A number here is changed in bond.py first, with a
 * measurement, then the oracle is written again, then this file follows --
 * never the other way round (NOTES.md, "Two implementations, one
 * direction"). The frame is BGR, uint8, as `cv2.imread` gives it.
 */
object Bond {

    // ------------------------------------------------------------------------
    // The +/- grid disc
    // ------------------------------------------------------------------------
    // The anchor for everything else on the Partner page. Found by its hue
    // and its size, both fractions of game_rect so an ADB frame and a window
    // frame answer the same way. Measured on six frames of both sources, to
    // four decimals in fx/fy:
    //
    //            fx      fy      fw      fill   vert (open) / vert (shut)
    //   window  0.7830  0.8236  0.0555  0.75-0.80   0.30 / 0.00
    //   ADB     0.7803  0.8220  0.0541  0.77-0.83   0.45 / 0.00
    //
    // 34 x 34 device pixels, and 0.785 is what the disc fills of its own box.
    val DISC_BAND = doubleArrayOf(0.70, 0.90, 0.75, 0.92)
    val DISC_HUE = Dungeon.Hsv(intArrayOf(103, 170, 120), intArrayOf(113, 255, 210))
    val DISC_W = doubleArrayOf(0.045, 0.065)
    // Not the frame's own w/h -- the disc is square in device pixels, and
    // w/gw against h/gh only agree because gw and gh are not the same shape.
    // Checked both sources: 34x34 device pixels, so the pixel aspect is the
    // one to use.
    val DISC_ASPECT = doubleArrayOf(0.75, 1.35)
    // fill is 0.75 to 0.83 across both sources -- more pixels on ADB, less of
    // the anti-aliased edge lost, the same effect NOTES.md records for the
    // Summons tab. 0.60 passes every real disc and refuses the dimmed one
    // under a dialog (V 100 against a clear page's 160), which is the second
    // job this answers: nothing is drawn over the Partner page.
    const val DISC_FILL_MIN = 0.60
    val DISC_WHITE = Dungeon.Hsv(intArrayOf(0, 0, 200), intArrayOf(179, 60, 255))
    // The vertical arm, over the disc's own middle fifth: rows 0.10 to 0.35,
    // columns 0.40 to 0.60. 0.30 (window) / 0.45 (ADB) open, 0.00 either way
    // shut -- not a threshold with a coin flip in it.
    val DISC_ARM_ROWS = doubleArrayOf(0.10, 0.35)
    val DISC_ARM_COLS = doubleArrayOf(0.40, 0.60)
    // Above this, the vertical arm is there and the glyph is a "+". A "+" is
    // the button that *would* expand the grid, so a "+" means the grid is
    // **shut**. The field is called `expanded` and not `open` for exactly
    // that reason: the first version returned "open" meaning "the plus is
    // drawn", the caller read it as "the grid is open", and the tour
    // therefore tapped the disc on an already expanded grid -- collapsing it
    // -- and then took the "+" that appeared as proof it had succeeded. It
    // went on to tap a cell at an expanded row's coordinates on a grid that
    // was shut and scrolled. A field name that can be read two ways is a bug
    // waiting for a second author.
    const val DISC_PLUS_MIN = 0.10

    /** The +/- disc: where it is, and whether the grid behind it is expanded. */
    data class Disc(val fx: Double, val fy: Double, val fw: Double, val fh: Double,
                    val expanded: Boolean) {
        fun toOracle(): Map<String, Any?> =
            mapOf("fx" to fx, "fy" to fy, "fw" to fw, "fh" to fh, "expanded" to expanded)
    }

    /**
     * The +/- disc: fx, fy, fw, fh, expanded -- or null if not plainly there.
     *
     * `expanded` is the state of the **grid**, not of the glyph: a "-" is
     * drawn when the grid is open and a "+" when it is shut.
     *
     * Null is also "is anything drawn over this page at all": a dialog dims
     * the disc out of the fill floor, measured on the confirmation prompt and
     * on nothing else that has a disc on it.
     */
    fun gridButton(img: Mat): Disc? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val (fx0, fx1, fy0, fy1) = DISC_BAND.toList()
        val left = maxOf(0, Py.int(x0 + fx0 * gw))
        val top = maxOf(0, Py.int(y0 + fy0 * gh))
        val sub = Py.crop(img, top, Py.int(y0 + fy1 * gh), left, Py.int(x0 + fx1 * gw))
            ?: return null
        val mask = Cv.hsvMask(sub, DISC_HUE)
        val (count, stats) = Cv.components(mask)
        mask.release()
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (w == 0 || h == 0) continue
            val fw = w / gw.toDouble()
            if (!(DISC_W[0] <= fw && fw <= DISC_W[1])) continue
            val aspect = w / h.toDouble()
            if (!(DISC_ASPECT[0] <= aspect && aspect <= DISC_ASPECT[1])) continue
            val fill = area / (w * h).toDouble()
            if (fill < DISC_FILL_MIN) continue
            val bx = left + x
            val by = top + y
            val disc = Py.crop(img, by, by + h, bx, bx + w)!!
            val dmask = Cv.hsvMask(disc, DISC_WHITE)
            val r0 = Py.int(DISC_ARM_ROWS[0] * h)
            val r1 = Py.int(DISC_ARM_ROWS[1] * h)
            val c0 = Py.int(DISC_ARM_COLS[0] * w)
            val c1 = Py.int(DISC_ARM_COLS[1] * w)
            val arm = Py.crop(dmask, r0, r1, c0, c1)
            val vert = if (arm != null) Cv.share(arm) else 0.0
            dmask.release()
            return Disc((bx + w / 2.0 - x0) / gw, (by + h / 2.0 - y0) / gh,
                        fw, h / gh.toDouble(), vert < DISC_PLUS_MIN)
        }
        return null
    }

    // ------------------------------------------------------------------------
    // The Raise button
    // ------------------------------------------------------------------------
    // One box, three labels: "Raise" (live, tappable), "Raising..." and
    // "Growth Complete" (both a dead grey plate). Position separates nothing
    // at all -- every one of them sits in the same box to a thousandth -- so
    // what decides is saturation: 255 live against a median 94 for both grey
    // states, and a width/height floor that keeps out a stray sliver of the
    // same hue elsewhere on the page, measured on every frame that has no
    // live button.
    //
    //                    fx      fy      fw      fh     median S
    //   Raise, window  0.4758  0.4185  0.2246  0.0412     255
    //   Raise, ADB     0.4760  0.4182  0.2232  0.0414     255
    val RAISE_BAND = doubleArrayOf(0.30, 0.75, 0.38, 0.46)
    val RAISE_HUE = Dungeon.Hsv(intArrayOf(85, 180, 140), intArrayOf(97, 255, 255))
    val RAISE_W = doubleArrayOf(0.18, 0.27)
    const val RAISE_H_MIN = 0.03

    /** The live Raise button: where it is, and how much of its box it fills. */
    data class Raise(val fx: Double, val fy: Double, val fw: Double, val fh: Double,
                     val fill: Double) {
        fun toOracle(): Map<String, Any?> =
            mapOf("fx" to fx, "fy" to fy, "fw" to fw, "fh" to fh, "fill" to fill)
    }

    /** The live Raise button, or null -- including on the grey dead states. */
    fun raiseButton(img: Mat): Raise? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val (fx0, fx1, fy0, fy1) = RAISE_BAND.toList()
        val left = maxOf(0, Py.int(x0 + fx0 * gw))
        val top = maxOf(0, Py.int(y0 + fy0 * gh))
        val sub = Py.crop(img, top, Py.int(y0 + fy1 * gh), left, Py.int(x0 + fx1 * gw))
            ?: return null
        val mask = Cv.hsvMask(sub, RAISE_HUE)
        val (count, stats) = Cv.components(mask)
        mask.release()
        var best: IntArray? = null
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (w == 0 || h == 0) continue
            val fw = w / gw.toDouble()
            if (!(RAISE_W[0] <= fw && fw <= RAISE_W[1])) continue
            if (h / gh.toDouble() < RAISE_H_MIN) continue
            if (best == null || area > best[4]) best = intArrayOf(x, y, w, h, area)
        }
        if (best == null) return null
        val (x, y, w, h, area) = best.toList()
        return Raise((left + x + w / 2.0 - x0) / gw, (top + y + h / 2.0 - y0) / gh,
                     w / gw.toDouble(), h / gh.toDouble(), area / (w * h).toDouble())
    }

    // ------------------------------------------------------------------------
    // The grid: gold selection, panel and the 5-column lattice
    // ------------------------------------------------------------------------
    // The panel that holds the cells. Found reliably by its border on both
    // sources -- but this module never searches for it by colour at all,
    // which is what a passing panel search would need to avoid the PASSIVE
    // SKILL panel sharing its width while the grid is collapsed. The tour
    // only ever reads the grid *expanded* (see BondTour._enter), where that
    // confusion cannot arise, so the panel is a measured constant instead,
    // the way PARTY_SLOTS and HOME_BAND already are elsewhere in this
    // program:
    //
    //            fx0     fx1     fy0     fy1
    //   window  0.1207  0.8287  0.6051  0.8462
    //   ADB     0.1255  0.8265  0.6035  0.8444
    //
    // three to five thousandths apart, which the column snap below absorbs.
    val PANEL_BAND = doubleArrayOf(0.1207, 0.8287, 0.6051, 0.8462)
    const val N_COLS = 5
    // The cells do not fill the panel edge to edge: dividing the width by
    // five overestimates the pitch by four per cent, measured on the gap
    // between the true columns and a naive division. What holds on both
    // sources is a margin of 2.04% of the panel's width on each side before
    // the five columns start.
    const val COL_MARGIN_FRAC = 0.0204

    // Rows are not inset the way columns are -- solving the same margin+pitch
    // formula backwards from the three measured row centres (0.6452, 0.7256,
    // 0.8060 on the window, 0.6437, 0.7240, 0.8043 on ADB) against the panel
    // band above gives a margin under a thousandth, so rows simply divide the
    // panel's own height. That is also why a wider grid needs no new numbers:
    // the row count is the panel's height over the column pitch, not a
    // constant three -- a player with twenty Digimon gets four rows for free.
    const val ROW_MARGIN_FRAC = 0.0

    // The gold corner brackets that mark the SELECTED cell (whose card is
    // shown above) -- not the small green tick, which marks the RAISED one
    // and is asked only as a second opinion. Found the same shape of way as
    // the bond bubble's cyan brackets and the dungeon's button pairs: a thing
    // is what its neighbour says it is, not its own position. Measured on
    // both sources:
    //
    //            fx (left) fx (right)  fw      fill    area
    //   window     0.5726    0.6509   0.0522   0.22-0.24   227
    //   ADB        0.5719    0.6491   0.0523   0.24-0.28   854
    //
    // Area is never used to select them -- it differs by a factor of 3.7
    // between the two frame rects, which is exactly the ratio NOTES.md warns
    // an area floor cannot survive.
    val GOLD_HUE = Dungeon.Hsv(intArrayOf(15, 120, 120), intArrayOf(35, 255, 255))
    val GOLD_W = doubleArrayOf(0.035, 0.075)
    val GOLD_FILL = doubleArrayOf(0.15, 0.35)
    // Two arcs at the same row, a fraction of the window apart -- not the
    // leftmost-to-rightmost spread over every arc found, which on one ADB
    // frame returned 0.4106: not a cell at all, because the device frame
    // draws extra gold slivers the window frame never showed.
    const val GOLD_DY_TOL = 0.012
    val GOLD_DX = doubleArrayOf(0.055, 0.105)

    // The tap point is not the cell's centre. The disc overlaps the last cell
    // -- its box runs to 0.753 of the window across, eight thousandths from
    // cell 15's own centre at 0.745 -- so every cell is tapped a quarter of
    // its own width left of centre and a tenth of its height above, which
    // clears the disc everywhere and costs nothing on the other fourteen,
    // where the sprite fills the cell and any point inside works.
    const val CELL_TAP_LEFT = 0.25
    const val CELL_TAP_UP = 0.10

    // Is there a Digimon in this cell at all, or an empty slot -- the same
    // question dungeon.party_slots_filled asks of the party screen, answered
    // the same way: standard deviation over a patch well inside the cell, not
    // a colour, because the sprite is a different Digimon in every cell.
    const val CELL_PATCH_FRAC = 0.32

    // The gold bracket, measured on the four sides of a cell -- see
    // raisedCell for the frames that made this necessary and for the
    // numbers.
    //
    // Where the bracket is drawn, in half-pitches from the cell's own centre,
    // measured on four frames of both sources (156 x 159 device pixels a cell
    // on an ADB frame, 83 x 86 on a window one):
    //
    //   across   -0.94 to +0.95      down   -0.74 to +0.86
    //
    // It is wider than it is tall, and it sits a little low: the lattice's
    // row centre is above the bracket's own by 0.06 of a half-pitch. Both
    // matter, so the bands are per axis and not one ring.
    val RAISED_SIDE_X = doubleArrayOf(0.82, 1.02)
    val RAISED_SIDE_Y = doubleArrayOf(0.62, 0.94)
    // What each side has to carry. The winner measures 0.086 to 0.110 across
    // every frame there is; every other cell on those frames, and every cell
    // on a page that is not this one, measures **0.0000**. So this is a floor
    // with a factor of four under the smallest thing it must catch and
    // nothing at all above the largest thing it must refuse.
    const val RAISED_SIDE_MIN = 0.02

    private fun panelRect(x0: Int, y0: Int, gw: Int, gh: Int): DoubleArray {
        val (fx0, fx1, fy0, fy1) = PANEL_BAND.toList()
        return doubleArrayOf(x0 + fx0 * gw, x0 + fx1 * gw, y0 + fy0 * gh, y0 + fy1 * gh)
    }

    private fun rawColumns(px0: Double, px1: Double): Pair<List<Double>, Double> {
        val width = px1 - px0
        val margin = COL_MARGIN_FRAC * width
        val pitch = (width - 2 * margin) / N_COLS
        return List(N_COLS) { i -> px0 + margin + pitch * (i + 0.5) } to pitch
    }

    /**
     * As many rows as the panel's height holds, at the column pitch.
     *
     * Not a fixed three: a player with more Digimon gets a taller panel and
     * more rows, counted here rather than assumed.
     */
    private fun rawRows(py0: Double, py1: Double, pitchX: Double, gw: Int, gh: Int): Pair<List<Double>, Double> {
        val height = py1 - py0
        // The column pitch is a fraction of gw; a row is as tall as a column
        // is wide in device pixels, so convert through the frame's own
        // aspect.
        val pitchY = pitchX * gw / gh.toDouble()
        val nRows = maxOf(1, Py.roundInt(height / pitchY))
        val margin = ROW_MARGIN_FRAC * height
        val pitch = (height - 2 * margin) / nRows
        return List(nRows) { i -> py0 + margin + pitch * (i + 0.5) } to pitch
    }

    /** The selected cell's gold brackets, as a window-space (fx, fy) midpoint. */
    private fun goldPair(img: Mat, x0: Int, y0: Int, gw: Int, gh: Int): Pair<Double, Double>? {
        val (px0, px1, py0, py1) = panelRect(x0, y0, gw, gh).toList()
        val left = maxOf(0, Py.int(px0))
        val top = maxOf(0, Py.int(py0))
        val sub = Py.crop(img, top, Py.int(py1), left, Py.int(px1)) ?: return null
        val mask = Cv.hsvMask(sub, GOLD_HUE)
        val (count, stats) = Cv.components(mask)
        mask.release()
        val arcs = ArrayList<Pair<Double, Double>>()
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (w == 0 || h == 0) continue
            val fw = w / gw.toDouble()
            if (!(GOLD_W[0] <= fw && fw <= GOLD_W[1])) continue
            val fill = area / (w * h).toDouble()
            if (!(GOLD_FILL[0] <= fill && fill <= GOLD_FILL[1])) continue
            arcs.add((left + x + w / 2.0 - x0) / gw to (top + y + h / 2.0 - y0) / gh)
        }
        var best: Pair<Double, Double>? = null
        for (i in arcs.indices) {
            val one = arcs[i]
            for (other in arcs.subList(i + 1, arcs.size)) {
                if (abs(one.second - other.second) > GOLD_DY_TOL) continue
                val dx = abs(one.first - other.first)
                if (!(GOLD_DX[0] <= dx && dx <= GOLD_DX[1])) continue
                best = (one.first + other.first) / 2.0 to (one.second + other.second) / 2.0
            }
        }
        return best
    }

    /** What `_lattice` returns: centres, the two pitches and the game rect. */
    private class Lattice(val centres: List<Pair<Double, Double>>, val pitchX: Double,
                          val pitchY: Double, val rect: Dungeon.GameRect)

    /**
     * The expanded grid's cell centres, in reading order, as (fx, fy).
     *
     * Columns are snapped onto the gold pair's own midpoint when one is
     * found; without one the raw margin+pitch positions are still within a
     * thousandth of it, measured, so a page with nothing selected is not a
     * page this refuses to read.
     */
    private fun lattice(img: Mat): Lattice {
        val rect = Dungeon.gameRect(img)
        val (x0, y0, gw, gh) = rect
        val (px0, px1, py0, py1) = panelRect(x0, y0, gw, gh).toList()
        val fpx0 = (px0 - x0) / gw
        val fpx1 = (px1 - x0) / gw
        var (cols, pitchX) = rawColumns(fpx0, fpx1)
        val fpy0 = (py0 - y0) / gh
        val fpy1 = (py1 - y0) / gh
        val (rows, pitchY) = rawRows(fpy0, fpy1, pitchX, gw, gh)

        val gold = goldPair(img, x0, y0, gw, gh)
        if (gold != null) {
            // `min(range(len(cols)), key=...)`: the first of equal distances.
            val nearestCol = cols.indices.minBy { abs(cols[it] - gold.first) }
            val dx = gold.first - cols[nearestCol]
            cols = cols.map { it + dx }
        }

        val centres = rows.flatMap { cy -> cols.map { cx -> cx to cy } }
        return Lattice(centres, pitchX, pitchY, rect)
    }

    private fun cellFilled(img: Mat, cx: Double, cy: Double, pitchX: Double, pitchY: Double,
                           x0: Int, y0: Int, gw: Int, gh: Int): Boolean {
        val hw = CELL_PATCH_FRAC * pitchX * gw
        val hh = CELL_PATCH_FRAC * pitchY * gh
        val px = x0 + cx * gw
        val py = y0 + cy * gh
        val x = Py.int(px - hw)
        val y = Py.int(py - hh)
        val w = Py.int(2 * hw)
        val h = Py.int(2 * hh)
        val patch = Py.crop(img, maxOf(0, y), y + h, maxOf(0, x), x + w) ?: return false
        val gray = Cv.gray(patch)
        val std = Cv.std(gray)
        gray.release()
        return std >= Dungeon.PARTY_SLOT_MIN_STD
    }

    /**
     * Tap points for every filled cell of the expanded grid, in reading
     * order.
     *
     * Not found -- laid out, and checked against the picture only to trim
     * empty slots off the end. cells(img).size is how many Digimon the
     * player has; a tour of fourteen or of twenty is the same code.
     *
     * Null where there is no expanded grid to read. That guard is the price
     * of laying a lattice out rather than finding one: asked about the Buddy
     * sub-tab, which has no grid on it at all, the first version cheerfully
     * returned fourteen tap points -- the lattice does not know what page it
     * is on, so it has to be told. The disc is what tells it, and the disc
     * is only drawn on the Partner tab.
     */
    fun cells(img: Mat): List<Pair<Double, Double>>? {
        val disc = gridButton(img)
        if (disc == null || !disc.expanded) return null
        val l = lattice(img)
        val (x0, y0, gw, gh) = l.rect
        val out = ArrayList<Pair<Double, Double>>()
        for ((cx, cy) in l.centres) {
            if (!cellFilled(img, cx, cy, l.pitchX, l.pitchY, x0, y0, gw, gh)) continue
            out.add((cx - CELL_TAP_LEFT * l.pitchX) to (cy - CELL_TAP_UP * l.pitchY))
        }
        return out
    }

    /**
     * The weakest of the four sides of this cell, as a share of gold.
     *
     * All four, and the weakest of them, because that is what a bracket is
     * and a Digimon standing in its cell is not. Several of them are painted
     * in the bracket's own colours -- Gallantmon's shield, a red and orange
     * dancer -- and a sprite can fill a good part of one side. None of them
     * outlines the cell.
     *
     * The corners are counted on two sides each, which is what lets a badge
     * eat one and leave every side still carrying the rest of itself.
     */
    private fun bracketSides(cx: Double, cy: Double, pitchX: Double, pitchY: Double,
                             x0: Int, y0: Int, gw: Int, gh: Int, gold: Mat): Double {
        val hx = pitchX * gw / 2.0
        val hy = pitchY * gh / 2.0
        val px = x0 + cx * gw
        val py = y0 + cy * gh
        val (xi, xo) = RAISED_SIDE_X.toList()
        val (yi, yo) = RAISED_SIDE_Y.toList()

        fun share(a: Double, b: Double, c: Double, d: Double): Double {
            val band = Py.crop(gold, maxOf(0, Py.int(py + c * hy)), Py.int(py + d * hy),
                               maxOf(0, Py.int(px + a * hx)), Py.int(px + b * hx))
            return if (band != null) Cv.share(band) else 0.0
        }

        return minOf(share(-xo, xo, -yo, -yi), share(-xo, xo, yi, yo),
                     share(-xo, -xi, -yo, yo), share(xi, xo, -yo, yo))
    }

    /**
     * Index into cells(img) of the currently raised Digimon, or null.
     *
     * The gold bracket says which, and it means "raised" only on a freshly
     * opened page, where the selected cell and the raised one are the same
     * (see BondTour._enter).
     *
     * **The bracket is not two arcs, and a badge can eat one of them.** It
     * was read as a pair of gold corner arcs at the same row, the way the
     * bond bubble's cyan ring is read, and that worked on every stored
     * frame. A live run then stopped dead on "no Digimon reads as raised on
     * a freshly opened page", with the bracket plainly on the screen: the
     * game had put a pink "!" badge over the cell's top right corner, which
     * is where the second arc is drawn. Only the top left one survived
     * whole, and one arc is not a pair.
     *
     * That badge is not an accident either. It is drawn on a Digimon that
     * has something waiting -- which is exactly the Digimon this tour exists
     * to visit. The state that breaks the reader is the state the reader is
     * for.
     *
     * Nor is more gold the answer. Taken as one ring around the cell, a red
     * and orange Digimon two rows up scored 0.10 against the real bracket's
     * 0.15 -- its hue is the bracket's hue to the unit, and its saturation
     * and value too, so no colour and no threshold on how much separates
     * them. What separates them is **shape**: the bracket is drawn on all
     * four sides of its cell and a Digimon standing in one is not. Measured
     * over every frame there is, as the weakest of the four sides:
     *
     *     frame                        selected   every other cell
     *     bond-partner-adb  1080x1920    0.0901        0.0000
     *     164059             624x1076    0.0983        0.0000
     *     the badge frame   1080x1920    0.0863        0.0000
     *     the cell-0 frame  1080x1920    0.1081        0.0000
     *     two pages that are not this one at all       0.0000
     *
     * Not "small", not "smaller" -- no other cell on any of them carries
     * gold on all four of its sides at once.
     */
    fun raisedCell(img: Mat): Int? {
        val points = cells(img)
        if (points.isNullOrEmpty()) return null
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val l = lattice(img)
        // The same centres cells() filtered to, in the same order, so the
        // index this returns is an index into what the caller has.
        var candidates = l.centres.filter { (cx, cy) ->
            cellFilled(img, cx, cy, l.pitchX, l.pitchY, x0, y0, gw, gh)
        }
        if (candidates.size != points.size) candidates = l.centres
        val gold = Cv.hsvMask(img, GOLD_HUE)
        val scores = candidates.map { (cx, cy) ->
            bracketSides(cx, cy, l.pitchX, l.pitchY, x0, y0, gw, gh, gold)
        }
        gold.release()
        if (scores.isEmpty()) return null
        // `max(range(len(scores)), key=...)`: the first of equal scores.
        val best = scores.indices.maxBy { scores[it] }
        if (scores[best] < RAISED_SIDE_MIN) return null
        return best
    }

    // ------------------------------------------------------------------------
    // The way into the Partner screen
    // ------------------------------------------------------------------------
    // The Digimon tab in the bottom nav, `explore.digimon_tab`, which finds
    // the globe and then the label clump at dx -0.235 from it. A target that
    // is found rather than assumed, and its None doubles as "the nav bar is
    // dimmed or gone", which is exactly the state a stray dialog leaves
    // behind.
    //
    // It is not the lead figure in the middle of the field. That was the
    // first answer, it was checked to land on the right figure on two stored
    // frames, and the first live run showed within five seconds what it
    // actually opens: the small Encyclopedia / Move dialog, with no grid on
    // it. The point had been verified for position and for nothing else.
    // passive.py had carried the sentence "tapping the lead only opens its
    // info window" since the bond token was built. See PLAN_BOND_TOUR.md 1.2.
    //
    // And the tab alone is not the whole way in. The Digimon page has four
    // sub-tabs -- Partner, Support Digimon, Buddy, SP Support -- and it opens
    // on whichever one was used last, not always on Partner
    // (player-confirmed). Only Partner carries the grid this tour reads, so
    // the sub-tab is a step of its own, below.
    //
    // The row is anchored on the globe rather than on the frame, the way
    // explore.py anchors its own labels, and measured over six frames of both
    // sources:
    //
    //   the row            dy -0.090 to -0.040 from the globe
    //   the four labels    fx 0.181  0.375  0.569  0.761       within 0.008
    //
    // Those two lines were measured with the crop arithmetic subtabClumps had
    // at the time, which read every ADB frame 0.0052 too far left -- see there.
    // Counted again over every corpus frame the row reads on, 63 of them: the
    // leftmost clump sits at fx 0.1696 to 0.2249, and the next one to its right
    // never below 0.3797. The spread is not the tab wandering but the gap-merge
    // taking in a neighbouring badge on some frames, fw 0.052 to 0.192.
    //
    // The active tab is drawn white on top of its own cyan, 0.108 wide
    // against 0.005 to 0.007 for the notification badges that share the band
    // -- a factor of fifteen. That test only decides what the log says,
    // though: the tour taps Partner either way, because tapping the tab you
    // are already on is inert and a threshold measured on one state only is
    // not one to hang a decision on. Every frame there is has Partner active;
    // nobody has photographed the others.
    val SUBTAB_BAND = doubleArrayOf(-0.090, -0.040)
    val SUBTAB_CYAN = Dungeon.Hsv(intArrayOf(90, 80, 150), intArrayOf(115, 255, 255))
    val SUBTAB_WHITE = Dungeon.Hsv(intArrayOf(0, 0, 215), intArrayOf(179, 60, 255))
    const val SUBTAB_GAP = 0.012
    const val SUBTAB_MIN_AREA = 3
    // A label, not a badge, and not three labels either: over the 63 corpus
    // frames the row reads on, a clump that is one label measures 0.0506 to
    // 0.1918 across, and the one frame where the gap-merge chains Partner,
    // Support Digimon and Buddy into a single clump measures 0.3609 -- which
    // came in as a "Partner tab" at fx 0.2807, between two of them, the moment
    // SUBTAB_PARTNER_FX was given the air it needed. The ceiling sits between
    // the two with 30 % above what it must accept and 31 % below what it must
    // refuse.
    const val SUBTAB_W_MIN = 0.05
    const val SUBTAB_W_MAX = 0.25
    // Partner is the leftmost of the four, and it is where it is. The ceiling
    // used to be 0.23, which the corpus's widest reading cleared by 0.0103 --
    // and once subtabClumps stopped reading ADB frames 0.0052 too far left,
    // by 0.0051, two per cent of air. Nothing is to the left of Partner, so the
    // floor carries nothing; the ceiling has to sit between the leftmost clump
    // (0.2249 at most) and the next label along (0.3797 at least), and 0.29 is
    // 29 % above what it must accept and 24 % below what it must refuse.
    val SUBTAB_PARTNER_FX = doubleArrayOf(0.14, 0.29)

    /** One merged clump of the sub-tab row. */
    private class Clump(val fx: Double, val fw: Double, val area: Int, val fy: Double)

    /**
     * Bright clumps in the sub-tab row, merged left to right by gap.
     *
     * Returns null where the globe is not there -- which is also "the bar is
     * dimmed", the same second job explore.nav_tab's own None does.
     */
    private fun subtabClumps(img: Mat, rng: Dungeon.Hsv): List<Clump>? {
        val home = Dungeon.homeButton(img) ?: return null
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val top = maxOf(0, Py.int(y0 + (home.fy + SUBTAB_BAND[0]) * gh))
        val bottom = Py.int(y0 + (home.fy + SUBTAB_BAND[1]) * gh)
        // Clamped, because on an ADB frame the reference rect starts left of
        // the picture and a negative index would wrap to the far edge -- and
        // then put back, because a clump's x is measured from the crop while
        // gw measures the game. Explore.labelClumps, which this one is
        // modelled on, does exactly that (`left + x - x0`), and findButtons
        // takes x0 off fx as it takes y0 off fy; this did neither, so every
        // fx came out -x0/gw too small. Measured over every corpus frame with
        // a sub-tab row on it (`_subtab_probe.py`): 0.0000 to 0.0056 on a
        // window frame, 0.0052 at 1080 x 1920, and 0.0894 at 1080 x 2340 --
        // which is the whole width of SUBTAB_PARTNER_FX, so on a long display
        // the leftmost tab would have fallen out of its own band.
        val leftPx = maxOf(0, x0)
        val sub = Py.crop(img, top, bottom, leftPx, x0 + gw) ?: return null
        val mask = Cv.hsvMask(sub, rng)
        val (count, stats) = Cv.components(mask)
        mask.release()
        // `sorted((left, right, area) ...)`: tuples, so ties on the left
        // edge are broken by the right edge and then by the area.
        val parts = (1 until count).map { stats[it] }
            .filter { it[4] >= SUBTAB_MIN_AREA }
            .map { intArrayOf(leftPx + it[0] - x0, leftPx + it[0] + it[2] - x0, it[4]) }
            .sortedWith(compareBy<IntArray>({ it[0] }, { it[1] }, { it[2] }))
        val groups = ArrayList<IntArray>()
        for ((left, right, area) in parts.map { it.toList() }) {
            if (groups.isNotEmpty() && left - groups.last()[1] <= SUBTAB_GAP * gw) {
                val g = groups.last()
                groups[groups.size - 1] = intArrayOf(g[0], maxOf(g[1], right), g[2] + area)
            } else {
                groups.add(intArrayOf(left, right, area))
            }
        }
        val out = ArrayList<Clump>()
        for ((left, right, area) in groups.map { it.toList() }) {
            val fw = (right - left) / gw.toDouble()
            if (fw < SUBTAB_W_MIN || fw > SUBTAB_W_MAX) continue
            out.add(Clump((left + right) / 2.0 / gw, fw, area,
                          home.fy + SUBTAB_BAND.sum() / 2.0))
        }
        return out
    }

    /** The Partner sub-tab, and whether it is the one already showing. */
    data class Subtab(val fx: Double, val fy: Double, val fw: Double, val active: Boolean) {
        fun toOracle(): Map<String, Any?> =
            mapOf("fx" to fx, "fy" to fy, "fw" to fw, "active" to active)
    }

    /**
     * The Partner sub-tab, with whether it is the one already showing.
     *
     * Null means the row is not there to read -- no globe, so the bar is
     * dimmed or this is not the Digimon page at all.
     */
    fun partnerSubtab(img: Mat): Subtab? {
        val labels = subtabClumps(img, SUBTAB_CYAN)
        if (labels.isNullOrEmpty()) return null
        val tab = labels.firstOrNull { c ->
            SUBTAB_PARTNER_FX[0] <= c.fx && c.fx <= SUBTAB_PARTNER_FX[1]
        } ?: return null
        val lit = subtabClumps(img, SUBTAB_WHITE) ?: emptyList()
        val active = lit.any { w -> abs(w.fx - tab.fx) < 0.02 }
        return Subtab(tab.fx, tab.fy, tab.fw, active)
    }

    // The confirmation dialog: nothing new to read. Dungeon.recognise already
    // answers EXIT / exit_kind "party" for "Raise <name>?", identically to
    // the dungeon's own leaving prompt.
}
