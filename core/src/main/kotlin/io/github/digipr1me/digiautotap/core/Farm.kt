package io.github.digipr1me.digiautotap.core

import io.github.digipr1me.digiautotap.core.Explore.Blob
import io.github.digipr1me.digiautotap.core.Explore.Target
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * farm.py's readers, carried over line for line: the Meat Field's plot grid
 * (`meat_field_screen`, `plots` with `plot_state`, `plot_badge_kind`,
 * `plot_harvest`, `plot_timer`, `bubble_over`), the header counters
 * (`free_seeds`, `seed_refill`) and the seed-choice menu and water popup
 * (`seed_slots`, `seed_selected`, `select_button`, `seed_menu`,
 * `water_button`, `water_popup`). The schedule (`next_visit`) and the skill's
 * loop are not here; they come with the Meat Field skill's own session,
 * under the director.
 *
 * Every constant came over with its Python name and value, and the sentence
 * that says where it came from; a number is changed in this file, with a
 * measurement and its sentence, and then `writeOracle` writes the oracle
 * (NOTES.md, "One project"). The frame is BGR, uint8, as `cv2.imread`
 * gives it.
 */
object Farm {

    // ------------------------------------------------------------------------
    // Where the field's windows stand (PLAN_FORMATE.md V4)
    // ------------------------------------------------------------------------
    // Measured 2026-09-28 on LDPlayer instance 0 with whole frames of 1080 x
    // 2520 (180 rows of headroom), 1080 x 2340 and their 1080 x 1920 twins
    // of the same minutes (corpus/formats/meat_field_*, water_popup_*,
    // boost_*): the header -- the sack, the two cans, the meat, their pink
    // timers -- stands at the top of the display, the sack's digit at fy
    // 0.0879 to 0.0986 at the top's rectangle against 0.0884 to 0.0990 at
    // 1920 (0.0506 to 0.0613 at the middle's, 0.0435 at the bottom's, where
    // it was cut off). The popups stand in the middle: the water popup's
    // Water button at fy 0.6757 there against 0.6758, 0.6384 at the bottom
    // and 0.7130 at the top; the watering-can dialog's Water button 0.6616
    // against 0.6616. The plots and the X stay at the bottom, as the whole
    // HUD does. On any display under the canvas ceiling the three are one.
    val HEADER = Dungeon.Anchor.TOP
    val DIALOG = Dungeon.Anchor.MIDDLE

    // ------------------------------------------------------------------------
    // The plot grid
    // ------------------------------------------------------------------------
    // Fixed 2 columns x 3 rows. Centres measured on field_growing_adb.png and
    // field_one_empty_adb.png (ADB, 1080x1920, two different plant states) by the
    // wood-pallet/dirt colour common to every plot regardless of what stands on
    // it -- hue 0-20, sat >= 90, so the sprite standing on the plot (which is a
    // different, more saturated green/pink) does not merge into it. Column
    // centres agreed to the thousandth between the two frames; row centres to
    // 0.003. A third colour clump turned up in the same hue at fy 0.336 on both
    // frames -- the brown Togemon pot above the grid -- which is why the grid
    // search below only ever looks at fy >= 0.45.
    val PLOT_COLS = doubleArrayOf(0.313, 0.640)
    val PLOT_ROWS = doubleArrayOf(0.507, 0.653, 0.810)
    val PLOT_HUE = Dungeon.Hsv(intArrayOf(0, 90, 40), intArrayOf(20, 255, 255))
    const val PLOT_MIN_SHARE = 0.01
    // Half the distance from a cell centre to its neighbour (0.327 across,
    // 0.146-0.157 down), so a found blob is claimed by the nearest cell and no
    // other.
    const val PLOT_CELL_TOL = 0.06

    /**
     * Wood/dirt-coloured clumps anywhere at or below the pots row, each as
     * (fx0, fy0, fx1, fy1, share).
     *
     * Kept as boxes, not just centroids: two neighbouring plots occasionally
     * merge into one connected component (no grass gap survives the mask
     * between them on some sprite poses -- measured on planted_now_adb.png,
     * where column 1's rows 0 and 1 merged into a single 308x575 blob whose
     * centroid falls between both row centres and matches neither). A cell is
     * claimed by any blob whose box contains that cell's centre, which the
     * merged blob still does for both rows.
     */
    private fun plotBlobs(img: Mat): List<DoubleArray> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val mask = Cv.hsvMask(img, PLOT_HUE)
        val (n, stats) = Cv.components(mask)
        mask.release()
        val blobs = ArrayList<DoubleArray>()
        for (i in 1 until n) {
            val (x, y, w, h, area) = stats[i].toList()
            val share = area / (gw * gh).toDouble()
            if (share < PLOT_MIN_SHARE) continue
            val fx0 = (x - x0) / gw.toDouble()
            val fx1 = (x + w - x0) / gw.toDouble()
            val fy0 = (y - y0) / gh.toDouble()
            val fy1 = (y + h - y0) / gh.toDouble()
            if (fy1 < 0.45) continue
            blobs.add(doubleArrayOf(fx0, fy0, fx1, fy1, share))
        }
        return blobs
    }

    /**
     * Is the Meat Field open -- how many of the six grid cells are found.
     *
     * Not a plain True/False: a popup or the seed-choice menu covers one or two
     * cells without hiding the rest (the laboratory's notes, "a dialog dims
     * what is behind it"), and callers that need "definitely open, nothing
     * covering it" ask for 6, while a caller that only needs "this is still the
     * field, not some other screen" can accept fewer. Measured over the whole
     * debug* archive (677 frames, none of them meant to be this screen): the
     * six-cell count fires 6/6 on exactly four frames, two captured here and
     * two the passive helper's own "unclear" dumps of this same screen from an
     * earlier run, and nothing else in the archive reaches even 4.
     *
     * That last sentence stopped being true as the archive grew. Over the
     * 747 frames of 2026-09-19 the count reaches 4 on 36 frames that are the
     * main screen over an orange or brown stage, and 6 on eight of them: the
     * plot hue is the hue of rock and sand. So the count says how much of
     * the field is visible, not whether this is the field -- director.py
     * asks for the white X in the corner beside it, which no main screen
     * has (PLAN_ANDROID_3_DIRECTOR.md 5.1). Inside this skill the count is
     * only ever asked after the field was opened, where the question is the
     * one it answers.
     */
    fun meatFieldScreen(img: Mat): Int {
        val blobs = plotBlobs(img)
        var found = 0
        for (cx in PLOT_COLS) {
            for (cy in PLOT_ROWS) {
                if (blobs.any { (fx0, fy0, fx1, fy1) ->
                        fx0 - PLOT_CELL_TOL <= cx && cx <= fx1 + PLOT_CELL_TOL &&
                            fy0 - PLOT_CELL_TOL <= cy && cy <= fy1 + PLOT_CELL_TOL
                    }) {
                    found += 1
                }
            }
        }
        return found
    }

    // ------------------------------------------------------------------------
    // Bubble and tap point
    // ------------------------------------------------------------------------
    // The water-drop bubble sits at the plot's own OUTER top corner, not always
    // at the left the way a first look suggests: measured on field_growing_adb.png,
    // column 1 (left)'s bubble centres at fx 0.216 -- left of that column's own
    // centre 0.313 -- while column 2 (right)'s bubble centres at fx 0.724, RIGHT
    // of its own centre 0.640. The two mirror around the screen's midline. A tap
    // point that is always "right of centre" (as a first reading of the plan
    // suggests) would walk straight into column 2's bubble; it has to be pulled
    // toward the INNER side of each column instead.
    //
    // Vertical: the bubble's own box measured 0.427-0.465 fy (both columns
    // agree to a thousandth); the timer plate's box measured 0.545-0.565 (col 1)
    // and 0.545-0.563 (col 2). The gap between them, fy 0.465-0.545, is where a
    // tap lands on neither -- centred at 0.505, which is also where the grid's
    // own row centres (PLOT_ROWS) already sit, so no separate vertical offset is
    // needed at all.
    const val TAP_INNER_DX = 0.07

    /**
     * Where to tap plot (col, row) -- 0/1 columns, 0/1/2 rows.
     *
     * In the dirt, pulled toward the inner column and sitting on the row's own
     * centre, which already clears the bubble above and the timer plate below
     * on both columns (0.136-0.172 clearance to the bubble, 0.043-0.045 to the
     * plate -- the plate is the tighter of the two, still comfortably over the
     * half-bubble-width NOTES.md asks for, 0.037).
     */
    fun plotTap(col: Int, row: Int): Target {
        val cx = PLOT_COLS[col]
        val cy = PLOT_ROWS[row]
        val dx = if (col == 0) TAP_INNER_DX else -TAP_INNER_DX
        return Target(cx + dx, cy)
    }

    // The bubble as a veto: found near a plot's own outer-top corner, checked
    // fresh right before every tap on that plot regardless of what state the
    // plot was read as, because the state read a moment ago is a frame, not a
    // fact still true now (NOTES.md, "the state is a frame, not a truth").
    //
    // Not plain white: a light-grey decorative stone sits in the dirt of some
    // plots close enough to the chosen tap point to trip a bare brightness mask
    // -- measured on row 1 and row 2 of field_growing_adb.png, a stone 0.008 and
    // 0.053 from the tap point respectively, both comfortably inside the veto
    // radius a brightness-only mask would use. The bubble itself is blue, not
    // grey: its drop and outline measured H 103, S 130-227, V 200-255 on the
    // same frame, clearly saturated where a stone is not.
    val BUBBLE_WHITE = Dungeon.Hsv(intArrayOf(95, 110, 180), intArrayOf(112, 255, 255))
    const val BUBBLE_MIN_AREA = 200
    // Half the measured bubble box, both axes, both columns (0.216-0.253 across
    // on column 1, 0.699-0.750 on column 2; 0.427-0.465 down on both) -- a tap
    // point inside this radius of a found bubble blob is refused.
    const val BUBBLE_VETO_RADIUS = 0.06

    /**
     * Is a water bubble sitting where plotTap(col, row) would land?
     *
     * True also when nothing at all can be read there (a capture glitch, a
     * transitional frame) -- refusing the tap is the safe answer either way.
     */
    fun bubbleOver(img: Mat, col: Int, row: Int): Boolean {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val tap = plotTap(col, row)
        val cx = PLOT_COLS[col]
        val cy = PLOT_ROWS[row]
        val top = maxOf(0, Py.int(y0 + (cy - 0.12) * gh))
        val bottom = Py.int(y0 + (cy + 0.02) * gh)
        val left = maxOf(0, Py.int(x0 + (cx - 0.20) * gw))
        val right = Py.int(x0 + (cx + 0.20) * gw)
        if (bottom <= top || right <= left) return true
        // Python would hand cvtColor an empty picture here and raise; the
        // oracle has no frame that does.
        val sub = Py.crop(img, top, bottom, left, right)
            ?: throw IllegalStateException("empty bubble crop")
        val mask = Cv.hsvMask(sub, BUBBLE_WHITE)
        val (n, stats) = Cv.components(mask)
        mask.release()
        for (i in 1 until n) {
            val (x, y, w, h, area) = stats[i].toList()
            if (area < BUBBLE_MIN_AREA) continue
            val fx = (left + x + w / 2.0 - x0) / gw
            val fy = (top + y + h / 2.0 - y0) / gh
            if (Math.pow((fx - tap.fx) * (fx - tap.fx) + (fy - tap.fy) * (fy - tap.fy), 0.5) <
                BUBBLE_VETO_RADIUS) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------------
    // read_timer -- field timers (H:M:S) and the header refill timer (M:S)
    // ------------------------------------------------------------------------
    // One glyph-finder for both: bright characters in a crop, kept only if their
    // height is a healthy share of the tallest character found (NOTES.md, the
    // quest counter's comma trap -- never a fixed pixel height, always relative
    // to what stands beside it in the same crop). A colon is two small squarish
    // dots stacked with a gap the height of one dot between them; every other
    // kept glyph is a digit.
    //
    // 0.55 would have been the wrong floor for the ripe badge's harvest count:
    // its lowercase "x" prefix measured 16 px against 21-22 for the digits
    // beside it, a ratio of 0.73, and read as a stray "3" or "5" in front of
    // the real number -- "x276" came back 3276. Every digit in this game's
    // fonts is the same height as its neighbours (measured 13/13/13/13/13/13 on
    // a field timer, 21/21 on the header counter, 21/22/22/21 on a harvest
    // count); nothing that is genuinely a digit here has ever measured under
    // 0.85 of the tallest one beside it.
    const val GLYPH_MIN_SHARE = 0.85
    // A colon dot is small and roughly square (measured 3x3 against 8x13 digits,
    // both timer fonts) -- w/h between these two rules out both thin noise
    // specks and the digits themselves.
    val COLON_DOT_WH = doubleArrayOf(0.5, 2.0)
    const val COLON_DOT_MAX_H = 6
    // How close two dots' rows have to line up before this crop's own digit
    // height gives a scale to compare against (colon-to-digit-row alignment
    // happens after digits are known).
    const val ROW_TOL_FRAC = 0.4

    /** `(gray > floor).astype(np.uint8) * 255`. */
    private fun brightMask(gray: Mat, floor: Double): Mat {
        val mask = Mat()
        Imgproc.threshold(gray, mask, floor, 255.0, Imgproc.THRESH_BINARY)
        return mask
    }

    /** Components of at least three pixels, as (x, y, w, h). */
    private fun rawComponents(mask: Mat): List<IntArray> {
        val (n, stats) = Cv.components(mask)
        val out = ArrayList<IntArray>()
        for (i in 1 until n) {
            if (stats[i][4] >= 3) out.add(intArrayOf(stats[i][0], stats[i][1], stats[i][2], stats[i][3]))
        }
        return out
    }

    /**
     * Colon dots paired up from every small square component, regardless
     * of height -- a colon is roughly a third the height of a digit and would
     * be thrown out by any digit-height filter applied before this runs.
     * Returns (colon stats, remaining unpaired components).
     */
    private fun pairColons(compsIn: List<IntArray>): Pair<List<IntArray>, List<IntArray>> {
        val comps = compsIn.sortedBy { it[0] }
        val used = BooleanArray(comps.size)
        val colons = ArrayList<IntArray>()
        for (i in comps.indices) {
            val (x, y, w, h) = comps[i].toList()
            if (used[i] || h > COLON_DOT_MAX_H ||
                !(COLON_DOT_WH[0] <= w / maxOf(h, 1).toDouble() &&
                    w / maxOf(h, 1).toDouble() <= COLON_DOT_WH[1])) {
                continue
            }
            for (j in i + 1 until comps.size) {
                if (used[j]) continue
                val (x2, y2, w2, h2) = comps[j].toList()
                if (h2 > COLON_DOT_MAX_H ||
                    !(COLON_DOT_WH[0] <= w2 / maxOf(h2, 1).toDouble() &&
                        w2 / maxOf(h2, 1).toDouble() <= COLON_DOT_WH[1])) {
                    continue
                }
                if (abs(x2 - x) <= maxOf(w, w2) && abs(h - h2) <= maxOf(h, h2) * 0.6) {
                    val gap = if (y2 > y) y2 - (y + h) else y - (y2 + h2)
                    if (0 <= gap && gap <= (h + h2) * 1.5) {
                        val top = minOf(y, y2)
                        val bottom = maxOf(y + h, y2 + h2)
                        colons.add(intArrayOf(minOf(x, x2), top,
                                              maxOf(x + w, x2 + w2) - minOf(x, x2), bottom - top))
                        used[i] = true
                        used[j] = true
                        break
                    }
                }
            }
        }
        val rest = comps.filterIndexed { i, _ -> !used[i] }
        return colons to rest
    }

    /**
     * comps grouped into candidate digit rows, biggest groups first.
     *
     * The plate's offset from a cell's own centre wobbles frame to frame --
     * measured 0.031-0.048 below centre on one frame's row 0, 0.010-0.036 on
     * its rows 1 and 2, and not even reproducible for the same cell between
     * two different frames. A crop wide enough to always contain the plate is
     * also wide enough to contain a bone, a rock or the next plot's sprite.
     *
     * Grouping by y-proximity alone is not enough: a decoration's bounding box
     * can start at nearly the same y as the real digits while standing three
     * or six times as tall (measured on plot_timer's own crop, a 76 px-tall
     * piece merged with the 13 px digits under a tolerance scaled off its own
     * height and then out-massed them, so the "tallest in the group" that
     * read_timer measures every other member against was the decoration, not
     * a digit). Real timer digits share both a height and a row, so a group
     * only forms between components whose heights agree to within a third as
     * well as their tops -- a bone or a leg practically never matches a
     * digit's height by chance, so it never contaminates the group meant to
     * be tried first, and it also gets to form a (usually unparsable) group
     * of its own further down the list rather than being silently dropped.
     */
    private fun rowClusters(compsIn: List<IntArray>): List<List<IntArray>> {
        if (compsIn.isEmpty()) return emptyList()
        // Python's sort is stable, and so are sortedBy and sortedByDescending.
        val comps = compsIn.sortedByDescending { it[3] }
        val groups = ArrayList<ArrayList<IntArray>>()
        for (c in comps) {
            var placed = false
            for (g in groups) {
                val ref = g[0]
                val tol = maxOf(2.0, ROW_TOL_FRAC * ref[3])
                if (abs(c[1] - ref[1]) <= tol && abs(c[3] - ref[3]) <= ref[3] / 3.0) {
                    g.add(c)
                    placed = true
                    break
                }
            }
            if (!placed) groups.add(arrayListOf(c))
        }
        return groups.sortedByDescending { it.size }
    }

    private fun parseGlyphRow(mask: Mat, colons: Int, digitStats: List<IntArray>): Int? {
        if (colons != 1 && colons != 2) return null
        if (digitStats.size != 2 * (colons + 1)) return null
        val values = ArrayList<Int>()
        for (stat in digitStats.sortedBy { it[0] }) {
            val d = Dungeon.readDigit(mask, stat) ?: return null
            values.add(d)
        }
        val parts = (0 until values.size step 2).map { values[it] * 10 + values[it + 1] }
        if (colons == 2) {
            val (h, m, s) = parts
            if (!(0 <= m && m < 60 && 0 <= s && s < 60 && 0 <= h && h <= 24)) return null
            return h * 3600 + m * 60 + s
        }
        val (m, s) = parts
        if (!(0 <= m && m < 60 && 0 <= s && s < 60)) return null
        return m * 60 + s
    }

    /**
     * A timer parsed out of an already-binary mask -- seconds, or null.
     *
     * Shared by readTimer's own brightness mask and seedRefill's colour
     * one: once the characters are a binary mask, a timer is a timer whatever
     * put the ink there.
     */
    private fun readTimerMask(mask: Mat): Int? {
        val comps = rawComponents(mask)
        if (comps.isEmpty()) return null
        val (colons, rest) = pairColons(comps)
        for (row in rowClusters(rest)) {
            val tallest = row.maxOf { it[3] }
            val digitStats = row.filter { it[3] >= GLYPH_MIN_SHARE * tallest }
            val rowColons = colons.filter { colon -> digitStats.any { abs(colon[1] - it[1]) <= tallest } }
            val value = parseGlyphRow(mask, rowColons.size, digitStats)
            if (value != null) return value
        }
        return null
    }

    /**
     * A timer in this crop -- seconds, or null if it does not parse.
     *
     * Format decided by how many colons were found: two gives H:M:S, one gives
     * M:S. Every row of digit-height-ish components in the crop is tried in
     * turn (see rowClusters); the first that parses to a plausible time
     * (M, S < 60, H <= 24, PLAN_MEAT_FIELD 2.4) is returned.
     */
    fun readTimer(img: Mat, top: Int, bottom: Int, left: Int, right: Int): Int? {
        if (bottom <= top || right <= left) return null
        val crop = Py.crop(img, top, bottom, left, right) ?: return null
        val gray = Cv.gray(crop)
        val mask = brightMask(gray, 180.0)
        gray.release()
        val value = readTimerMask(mask)
        mask.release()
        return value
    }

    /**
     * The H:M:S timer on a growing plot, or null.
     *
     * The plate's offset from the cell centre is not a single constant --
     * PLOT_ROWS is measured off the visible-dirt centroid, which shifts with
     * how much of each plot the standing sprite happens to cover on a given
     * frame and pose, and is not even reproducible for the same cell between
     * two different frames: measured span cy+0.018 to cy+0.058 across the
     * three rows and several frames. So the crop is generous (cy-0.02 to
     * cy+0.10, wide enough to reach into the next row down) and readTimer
     * tries every row of digit-height content it finds rather than trusting
     * one of them to be the plate ahead of time -- see readTimer/
     * rowClusters for why that is safe.
     */
    fun plotTimer(img: Mat, col: Int, row: Int): Int? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val cx = PLOT_COLS[col]
        val cy = PLOT_ROWS[row]
        val top = Py.int(y0 + (cy - 0.02) * gh)
        val bottom = Py.int(y0 + (cy + 0.10) * gh)
        val left = Py.int(x0 + (cx - 0.15) * gw)
        val right = Py.int(x0 + (cx + 0.15) * gw)
        return readTimer(img, top, bottom, left, right)
    }

    // ------------------------------------------------------------------------
    // ripe and empty -- the cream, green-ringed badge
    // ------------------------------------------------------------------------
    // A ripe plot and an empty one draw the same badge -- a cream speech bubble
    // with a dark green ring, at the plot's own top -- and differ only in what
    // sits inside it: a harvest count for ripe, a plain trowel icon for empty.
    // Measured on field_ripe_adb.png (five ripe plots) and field_one_empty_adb.png
    // (one empty plot): the ring's own hue is H 36-39, S 219-227, V 34-111 on
    // both, share 0.00182-0.00184, fw 0.182-0.183, fh 0.102-0.103 every time --
    // tighter agreement than either the water bubble or the timer plate managed,
    // because this badge does not compete with a standing sprite for space.
    //
    // Not confusable with the water bubble: that one is blue (H 103), this one
    // green: measured together on field_growing_adb.png (no badges at all, blue
    // bubbles only) and field_ripe_adb.png (badges on five plots, a blue bubble
    // still on the sixth, still growing one) without either mask ever firing on
    // the other's colour.
    val BADGE_HUE = Dungeon.Hsv(intArrayOf(30, 180, 20), intArrayOf(45, 255, 140))
    const val BADGE_MIN_SHARE = 0.0012

    /**
     * The cream/green badge's box near this cell as (left, top, right,
     * bottom), or null.
     *
     * Searched around the cell centre and a bit above it -- measured offset
     * from PLOT_ROWS is -0.049 to -0.025 across the three rows on
     * field_ripe_adb.png, similar wobble to the timer plate's own and for the
     * same reason (how much of the plot a badge this size can clear depends
     * on what else is drawn there), so the window is generous rather than
     * pinned to one offset.
     */
    private fun plotBadge(img: Mat, col: Int, row: Int): IntArray? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val cx = PLOT_COLS[col]
        val cy = PLOT_ROWS[row]
        val mask = Cv.hsvMask(img, BADGE_HUE)
        val (n, stats) = Cv.components(mask)
        mask.release()
        var best: IntArray? = null
        var bestShare = 0.0
        for (i in 1 until n) {
            val (x, y, w, h, area) = stats[i].toList()
            val share = area / (gw * gh).toDouble()
            if (share < BADGE_MIN_SHARE) continue
            val fx = (x + w / 2.0 - x0) / gw
            val fy = (y + h / 2.0 - y0) / gh
            if (abs(fx - cx) > 0.12 || !(cy - 0.16 <= fy && fy <= cy + 0.02)) continue
            if (best == null || share > bestShare) {
                best = intArrayOf(x, y, w, h)
                bestShare = share
            }
        }
        val (x, y, w, h) = (best ?: return null).toList()
        return intArrayOf(x, y, x + w, y + h)
    }

    // A number is a row of digits that actually look like digits and actually
    // sit next to each other -- neither is optional. D.read_digit never
    // answers None; it is a shape classifier, not a "is this a digit at all"
    // test, so on its own two of anything bright and similarly tall pass it.
    // Measured on the trowel badge of field_one_empty_adb.png, whose bright
    // mask happens to carry two corner-highlight flecks 21 px and 20 px tall
    // in the same "row" as far as height goes: read as two digits and the
    // whole trowel badge came back "ripe". What real digits have and those
    // flecks do not: an aspect close to a digit's own (harvest digits measured
    // 0.64-0.71 wide/tall, field-timer digits 0.62-0.69, the header counter's
    // 0.62 -- 0.40 to 0.85 covers all of them with room either side, the
    // flecks measured 1.33 and 0.25), and a gap to the next character no wider
    // than the character itself (measured 3-4 px between real digits 14-15 px
    // wide; the flecks sat 180 px apart on a badge 213 px wide).
    val DIGIT_ASPECT = doubleArrayOf(0.40, 0.85)
    const val DIGIT_GAP_MAX = 1.0

    // A harvest of a thousand or more is written "x1,200", and the comma sits
    // in the gap the rule above measures. Measured on the phone tour's Meat
    // Field (corpus/formats/meat_field_*, the bottom right plot): the gap from
    // the "1" to the "2" across the comma is 11 px against the "1"'s 11 px at
    // 1080 x 1920 -- inside the rule by nothing at all -- and 9 against 8 at
    // 720 x 1600, 8 against 7 at 720 x 1280, so a ripe plot read "empty" on
    // every 720-wide phone. The comma itself is 3 to 5 px wide and 0.33 to
    // 0.44 of a digit tall, its top in the lower half of the digits and its
    // bottom 2 to 4 px under theirs; digit to comma and comma to digit are 3
    // to 4 px each, like any two digits. So a comma of that shape standing in
    // a gap splits it, and each half is held to the rule -- the trowel's two
    // flecks, 180 px apart, have no comma between them.
    const val COMMA_MAX_H = 0.5
    const val COMMA_BELOW_MAX = 0.3

    /** The two halves of the gap between a and b, split by a comma lying in it -- or null. */
    private fun commaSplit(a: IntArray, b: IntArray, comps: List<IntArray>): Pair<Int, Int>? {
        val h = minOf(a[3], b[3])
        val bottom = maxOf(a[1] + a[3], b[1] + b[3])
        val mid = minOf(a[1], b[1]) + h / 2.0
        for (c in comps) {
            if (c[3] > COMMA_MAX_H * h) continue
            if (c[0] < a[0] + a[2] || c[0] + c[2] > b[0]) continue
            if (c[1] < mid || c[1] + c[3] > bottom + COMMA_BELOW_MAX * h) continue
            return (c[0] - (a[0] + a[2])) to (b[0] - (c[0] + c[2]))
        }
        return null
    }

    /**
     * The best run of digit-shaped, closely-spaced glyphs in these
     * components, sorted left to right -- or null.
     */
    private fun digitSequence(comps: List<IntArray>): List<IntArray>? {
        for (group in rowClusters(comps)) {
            val tallest = group.maxOf { it[3] }
            var candidates = group.filter { it[3] >= GLYPH_MIN_SHARE * tallest }
            candidates = candidates.filter {
                DIGIT_ASPECT[0] <= it[2] / it[3].toDouble() && it[2] / it[3].toDouble() <= DIGIT_ASPECT[1]
            }
            if (candidates.size < 2) continue
            candidates = candidates.sortedBy { it[0] }
            var ok = true
            for (k in 0 until candidates.size - 1) {
                val a = candidates[k]
                val b = candidates[k + 1]
                val gap = b[0] - (a[0] + a[2])
                val limit = DIGIT_GAP_MAX * minOf(a[2], b[2])
                if (gap > limit) {
                    val halves = commaSplit(a, b, comps)
                    if (halves != null && halves.first <= limit && halves.second <= limit) continue
                    ok = false
                    break
                }
            }
            if (ok) return candidates
        }
        return null
    }

    /** `_read_badge_number`'s answer: the badge's mask and its digit stats. */
    private class BadgeNumber(val mask: Mat?, val digits: List<IntArray>?)

    /**
     * The digit sequence on this cell's badge, as (mask, stats) -- or
     * (null, null) if the badge has no number on it at all.
     */
    private fun readBadgeNumber(img: Mat, col: Int, row: Int): BadgeNumber {
        val box = plotBadge(img, col, row) ?: return BadgeNumber(null, null)
        val (left, top, right, bottom) = box.toList()
        val crop = Py.crop(img, top, bottom, left, right) ?: return BadgeNumber(null, null)
        val gray = Cv.gray(crop)
        val mask = brightMask(gray, 200.0)
        gray.release()
        val digits = digitSequence(rawComponents(mask))
        return BadgeNumber(mask, digits)
    }

    /**
     * "ripe", "empty" or null for this cell's badge.
     *
     * Distinguished by content, not colour -- both badges share one ring.
     * A harvest count is bright white digits (measured on field_ripe_adb.png,
     * "x276"/"x115" in bold white with a dark outline); the trowel icon has no
     * such text at all.
     */
    fun plotBadgeKind(img: Mat, col: Int, row: Int): String? {
        plotBadge(img, col, row) ?: return null
        val number = readBadgeNumber(img, col, row)
        number.mask?.release()
        return if (number.digits != null) "ripe" else "empty"
    }

    /** The harvest count shown on a ripe plot's badge, or null. */
    fun plotHarvest(img: Mat, col: Int, row: Int): Int? {
        val number = readBadgeNumber(img, col, row)
        try {
            val digits = number.digits
            if (digits.isNullOrEmpty()) return null
            var value = 0
            for (stat in digits) {
                val d = Dungeon.readDigit(number.mask!!, stat) ?: return null
                value = value * 10 + d
            }
            return value
        } finally {
            number.mask?.release()
        }
    }

    /**
     * "growing", "ripe", "empty" or null for this cell.
     *
     * Null whenever it cannot be told apart confidently -- a locked plot (no
     * live example seen yet to measure a recogniser against, NOTES.md: no
     * threshold without a measurement) falls out here rather than being
     * guessed at, and a cell with neither a badge nor a readable timer is left
     * alone rather than tapped on a guess (PLAN_MEAT_FIELD 2.3).
     */
    fun plotState(img: Mat, col: Int, row: Int): String? {
        val kind = plotBadgeKind(img, col, row)
        if (kind != null) return kind
        if (plotTimer(img, col, row) != null) return "growing"
        return null
    }

    /** State of all six cells, indexed [row][col] like the grid itself. */
    fun plots(img: Mat): List<List<String?>> =
        (0 until 3).map { row -> (0 until 2).map { col -> plotState(img, col, row) } }

    // ------------------------------------------------------------------------
    // free_seeds -- the header counter
    // ------------------------------------------------------------------------
    // The sack icon's own counter, top left of the field header. Measured on
    // field_growing_adb.png: two digits at fx 0.301-0.365 (crop-relative
    // x 27-57 of a 92 px wide crop starting at fx 0.37... folded back through
    // game_rect), fy 0.130-0.157, height a good deal taller than it looks at
    // first crop (21 px, not 13 -- a first attempt cut the serif foot off and
    // read "11" as 3 and 7; NOTES.md already has this exact trap under the
    // quest counter's "1" and it cost the same lesson twice).
    val FREE_SEEDS_BAND = doubleArrayOf(0.060, 0.110, 0.375, 0.455)

    /**
     * The free-seed count shown on the field's own header, or null.
     *
     * Read like a timer's digits minus the colon: every row of digit-height
     * components in the crop is tried, first one that is 1-2 digits wins.
     * Counts to zero, so unlike a price this never needs the "one character is
     * not a number" floor NOTES.md asks for elsewhere -- a single confident
     * digit is a complete answer between 0 and 9.
     */
    fun freeSeeds(img: Mat, anchor: Dungeon.Anchor = HEADER): Int? =
        headerCount(img, FREE_SEEDS_BAND, 2, anchor)

    /**
     * A band of a rectangle in this frame's pixels, (top, bottom, left,
     * right), its top kept inside the picture: a window at the top of a
     * display with headroom stands above a frame that was cut to the canvas
     * (the V3 frames of the tour), and a negative row is numpy's row from
     * the end. Null when nothing of the band is left.
     */
    private fun bandPx(img: Mat, band: DoubleArray, anchor: Dungeon.Anchor): IntArray? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val top = maxOf(0, Py.int(y0 + band[0] * gh))
        val bottom = Py.int(y0 + band[1] * gh)
        val left = maxOf(0, Py.int(x0 + band[2] * gw))
        val right = Py.int(x0 + band[3] * gw)
        if (bottom <= top || right <= left) return null
        return intArrayOf(top, bottom, left, right)
    }

    // A row of the header band is a count only if its glyphs are a digit's
    // height. Measured live on 2026-09-28 (M3, instance 0 at 1080 x 2340,
    // corpus/farm/field_seeds0_1080x2340_none0_063823.png): the sack read
    // "2" while it showed 0. The band's right end reaches the Good bag's
    // icon there, whose bright rim fell into specks of 1 to 3 px wide and 3
    // and 4 px tall, and two of them made a row of two -- which rowClusters
    // tries before the lone "0" (25 px) because it tries the fuller rows
    // first. A count of one digit loses to any pair of specks, so the same
    // thing waits for a 0 to 9 on any format. The digits are 0.0104 to
    // 0.0106 of the rectangle's height on every format of M1 (21 px of 1980
    // at 1920, 25 of 2413 at 2340, sack and can alike); the specks 0.0017.
    // Half a digit, 0.005, is the floor.
    const val HEADER_DIGIT_H_MIN = 0.005

    /**
     * A count of one to [maxDigits] digits in a band of the header, white
     * on the dark bar (freeSeeds' reading, for every counter of the row).
     * A row shorter than [HEADER_DIGIT_H_MIN] of the rectangle is not one.
     */
    private fun headerCount(img: Mat, band: DoubleArray, maxDigits: Int, anchor: Dungeon.Anchor): Int? {
        val (top, bottom, left, right) = (bandPx(img, band, anchor) ?: return null).toList()
        val crop = Py.crop(img, top, bottom, left, right) ?: return null
        val gray = Cv.gray(crop)
        val mask = brightMask(gray, 200.0)
        gray.release()
        val floor = HEADER_DIGIT_H_MIN * Dungeon.gameRect(img, anchor).gh
        try {
            val comps = rawComponents(mask)
            for (row in rowClusters(comps)) {
                val tallest = row.maxOf { it[3] }
                if (tallest < floor) continue
                val digits = row.filter { it[3] >= GLYPH_MIN_SHARE * tallest }
                if (digits.isEmpty() || digits.size > maxDigits) continue
                val values = ArrayList<Int>()
                var ok = true
                for (stat in digits.sortedBy { it[0] }) {
                    val d = Dungeon.readDigit(mask, stat)
                    if (d == null) {
                        ok = false
                        break
                    }
                    values.add(d)
                }
                if (!ok) continue
                var value = 0
                for (v in values) value = value * 10 + v
                return value
            }
            return null
        } finally {
            mask.release()
        }
    }

    // ------------------------------------------------------------------------
    // water_cans -- the green can's counter, the header's second row
    // ------------------------------------------------------------------------
    // The Tiny Watering Can, 30 minutes off a growing plot each (the game's
    // own item text), one more every hour while under the cap, the pink
    // timer under it counting to the next (59:50 right after one arrived,
    // 2026-09-28). Measured on corpus/farm/field_cans_013050.png and
    // field_ripe_cans_012636.png (instance 0, 1080 x 1920, 4 cans): the
    // digit at fx 0.5466 to 0.5606, fy 0.1182 to 0.1288, 21 px -- the sack's
    // digits stand a row higher at 0.0884 to 0.0990 in the same font. The
    // bar behind it runs fy 0.1141 to 0.1333 and fx 0.49 to 0.563, and the
    // count is right-aligned in it. The can's own icon overlaps the bar's
    // left end up to fx 0.490 and carries white highlights (fx 0.4603 to
    // 0.4804, 8 and 9 px tall), which read as a digit if the band takes
    // them in; the blue can's icon begins at fx 0.582. So the band starts
    // at 0.500 and ends at 0.575, which leaves room for three digits right
    // of the icon (a digit and its gap are 0.0146, and three end at 0.5606).
    // Three and not two as for the sack: the two ads of a day give 20 cans
    // each (the player; M3 checks it on the counter), so a count past 99 is
    // unlikely and not impossible.
    //
    // The blue can beside it is the Huge Watering Can, an hour a can, and
    // is never used (PLAN_MEAT_FIELD_GIESSEN.md 4.1); it reads 0 here at fx
    // 0.6879 to 0.7001, and nothing asks it.
    val WATER_CANS_BAND = doubleArrayOf(0.108, 0.136, 0.500, 0.575)

    /** The green can's count on the field's header, or null. */
    fun waterCans(img: Mat, anchor: Dungeon.Anchor = HEADER): Int? =
        headerCount(img, WATER_CANS_BAND, 3, anchor)

    // ------------------------------------------------------------------------
    // seed_refill -- the pink countdown under the free-seed counter
    // ------------------------------------------------------------------------
    // Not white like every other counter here -- NOTES.md already paid for
    // this shape of trap twice under stage_failed and a dungeon counter's own
    // ist side: red/pink converts to a dark, unremarkable grey in plain
    // brightness, and OpenCV's hue wraps at 179/0 so a single range can cut a
    // glyph's own reddest core out of itself. Measured on field_growing_adb.png,
    // the digits' dominant pixel is H 162, S 217, V 255 -- close enough to the
    // wrap that both ends are covered.
    val REFILL_HUE = listOf(Dungeon.Hsv(intArrayOf(150, 120, 180), intArrayOf(179, 255, 255)),
                            Dungeon.Hsv(intArrayOf(0, 120, 180), intArrayOf(8, 255, 255)))
    val REFILL_BAND = doubleArrayOf(0.10, 0.14, 0.34, 0.48)

    private fun refillMask(img: Mat, top: Int, bottom: Int, left: Int, right: Int): Mat? {
        if (bottom <= top || right <= left) return null
        val crop = Py.crop(img, top, bottom, left, right) ?: return null
        val hsv = Cv.hsv(crop)
        var mask: Mat? = null
        for (range in REFILL_HUE) {
            val m = Cv.inRange(hsv, range)
            if (mask == null) {
                mask = m
            } else {
                Core.bitwise_or(mask, m, mask)
                m.release()
            }
        }
        hsv.release()
        return mask
    }

    /**
     * Seconds until the next free seed arrives, or null.
     *
     * Only drawn while the count sits under its cap (PLAN_MEAT_FIELD: "der
     * Timer läuft nur, solange weniger als 20 im Inventar sind") -- null here
     * is also the answer once it is full, which is exactly when nothing needs
     * waiting for.
     */
    fun seedRefill(img: Mat, anchor: Dungeon.Anchor = HEADER): Int? = refillIn(img, REFILL_BAND, anchor)

    /** A pink M:S timer of the header in [band], seconds, or null. */
    private fun refillIn(img: Mat, band: DoubleArray, anchor: Dungeon.Anchor): Int? {
        val (top, bottom, left, right) = (bandPx(img, band, anchor) ?: return null).toList()
        val mask = refillMask(img, top, bottom, left, right) ?: return null
        val value = readTimerMask(mask)
        mask.release()
        return value
    }

    // The can's timer, pink like the sack's and read by the same mask: on
    // field_cans_013050.png at fx 0.4996 to 0.5519, fy 0.1364 to 0.1444
    // (16 px, "58:30"), under the can's bar as the sack's stands under the
    // sack's. The band is the sack's in shape, 0.03 tall around the digits,
    // and holds nothing else pink: the Great seed's red gift, the nearest,
    // is at fx 0.589 and fy 0.084 to 0.104. Drawn only while the cans are
    // under their cap, like the sack's; with cans at 0 it still runs (0 to
    // 1 in 32:22, field_cans0_nobubble_013707.png).
    val CAN_REFILL_BAND = doubleArrayOf(0.130, 0.160, 0.470, 0.580)

    /** Seconds until the next free can arrives, or null. */
    fun canRefill(img: Mat, anchor: Dungeon.Anchor = HEADER): Int? = refillIn(img, CAN_REFILL_BAND, anchor)

    // ------------------------------------------------------------------------
    // The seed-choice menu
    // ------------------------------------------------------------------------
    // Three slot backgrounds, dark brown, in a row -- measured on
    // seed_menu_left_adb.png at fy 0.475 (all three, agreeing to the thousandth),
    // fx 0.291 / 0.476 / 0.661. The same hue also matches the field plots behind
    // the dialog (the dialog does not cover the whole screen), which is why the
    // fy band below is narrow -- the plots sit at fy >= 0.68 on this frame,
    // nothing survives the band by accident.
    val SLOT_HUE = Dungeon.Hsv(intArrayOf(0, 140, 60), intArrayOf(15, 220, 160))
    const val SLOT_MIN_SHARE = 0.003
    val SLOT_FY_BAND = doubleArrayOf(0.44, 0.52)

    // The gold bracket marking the selected slot: four small corner arcs.
    // Measured, same frame: orange H 15, S 241, V 242, corners at fx 0.231/0.350
    // (left slot's own left/right edge) x fy 0.441/0.509 (top/bottom) -- a box
    // 0.119 wide, 0.068 tall, centred 0.0605/0.475 off from the slot centre
    // 0.291/0.475: dead-on in fy, 0 in fx (the box straddles the slot exactly).
    val BRACKET_HUE = Dungeon.Hsv(intArrayOf(8, 180, 180), intArrayOf(22, 255, 255))
    const val BRACKET_MIN_SHARE = 0.0002
    // Each seed's own icon carries orange/meat-coloured art sitting AT that
    // slot's own centre, in the same hue as the bracket -- measured on the same
    // frame, stray blobs at fx 0.466-0.479 (slot 1's centre 0.476) and fx 0.661
    // (slot 2's centre exactly). A bracket corner is never at the centre: it
    // sits offset both ways, fx 0.06 either side and fy 0.034 above or below.
    // So a corner is only counted within an annulus around the slot, not a
    // plain radius -- an icon blob at zero offset falls inside the hole in the
    // middle and is refused; two of the four real corners are always enough
    // since a slightly occluded frame may only show one full side of the box.
    val BRACKET_DX = doubleArrayOf(0.03, 0.10)
    val BRACKET_DY = doubleArrayOf(0.015, 0.06)
    const val BRACKET_MIN_CORNERS = 2

    // The green Select button below the slots. Measured, same frame: fx 0.476,
    // fy 0.599, share 0.0106, one blob, nothing else in this hue anywhere on the
    // frame -- no ratio-to-runner-up needed the way the menu cards need one.
    val SELECT_HUE = Dungeon.Hsv(intArrayOf(45, 100, 150), intArrayOf(65, 255, 255))
    const val SELECT_MIN_SHARE = 0.003

    private fun hueBlobs(img: Mat, hue: Dungeon.Hsv, minShare: Double,
                         fyBand: DoubleArray? = null, anchor: Dungeon.Anchor = DIALOG): List<Blob> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val mask = Cv.hsvMask(img, hue)
        val (n, stats) = Cv.components(mask)
        mask.release()
        val blobs = ArrayList<Blob>()
        for (i in 1 until n) {
            val (x, y, w, h, area) = stats[i].toList()
            val share = area / (gw * gh).toDouble()
            if (share < minShare) continue
            val fx = (x + w / 2.0 - x0) / gw
            val fy = (y + h / 2.0 - y0) / gh
            if (fyBand != null && !(fyBand[0] <= fy && fy <= fyBand[1])) continue
            blobs.add(Blob(fx, fy, share))
        }
        return blobs
    }

    /**
     * The three seed slots' tap targets, leftmost first, or an empty list if
     * the seed-choice menu is not open.
     */
    fun seedSlots(img: Mat, anchor: Dungeon.Anchor = DIALOG): List<Blob> =
        hueBlobs(img, SLOT_HUE, SLOT_MIN_SHARE, SLOT_FY_BAND, anchor).sortedBy { it.fx }

    /**
     * Is this slot (one of seedSlots(img)'s entries) the marked one?
     *
     * At least two of the bracket's four corners found in the annulus around
     * this slot's centre -- see BRACKET_DX/DY for why a plain radius would
     * also catch the slot's own icon.
     *
     * The menu keeps the last choice: after a Great seed the next menu
     * opened with Great in the bracket, after a Good one with Good
     * (corpus/farm/seed_menu_kept_great_012940.png, seed_menu_kept_good_013011.png,
     * 2026-09-28). So the free slot is asked for every time, never assumed.
     */
    fun seedSelected(img: Mat, slot: Blob, anchor: Dungeon.Anchor = DIALOG): Boolean {
        var corners = 0
        for (b in hueBlobs(img, BRACKET_HUE, BRACKET_MIN_SHARE, anchor = anchor)) {
            val dx = abs(b.fx - slot.fx)
            val dy = abs(b.fy - slot.fy)
            if (BRACKET_DX[0] <= dx && dx <= BRACKET_DX[1] &&
                BRACKET_DY[0] <= dy && dy <= BRACKET_DY[1]) {
                corners += 1
            }
        }
        return corners >= BRACKET_MIN_CORNERS
    }

    /** The green Select/OK button's tap target, or null. */
    fun selectButton(img: Mat, anchor: Dungeon.Anchor = DIALOG): Blob? =
        hueBlobs(img, SELECT_HUE, SELECT_MIN_SHARE, anchor = anchor).maxByOrNull { it.share }

    /**
     * The seed-choice menu's slots (leftmost first) and Select button, or
     * (null, null) if it is not open. Both a row of three slots and the
     * button are required -- either alone can appear while transitioning.
     *
     * Also gated on the field itself being covered (meatFieldScreen < 6):
     * a ripe plot's own harvest bubble is a similar dark-brown-on-cream badge,
     * and a field with several of them can put three within the slot fy band
     * by pure coincidence -- measured on a genuine field_ripe frame (five
     * ripe plots), where three harvest bubbles lined up at fy 0.511-0.514
     * against the real slots' 0.475, and a stray blob the size of a whole
     * ripe bubble briefly passed for the Select button too. The seed dialog
     * always dims the field behind it (meatFieldScreen reads 4 on the real
     * dialog, PLAN_MEAT_FIELD 2.2); the ripe frame reads 6, undimmed, because
     * nothing is actually open there.
     */
    fun seedMenu(img: Mat, anchor: Dungeon.Anchor = DIALOG): Pair<List<Blob>?, Blob?> {
        if (meatFieldScreen(img) >= 6) return null to null
        val slots = seedSlots(img, anchor)
        val button = selectButton(img, anchor)
        if (slots.size != 3 || button == null) return null to null
        return slots to button
    }

    // ------------------------------------------------------------------------
    // The water popup -- a growing plot was tapped by mistake
    // ------------------------------------------------------------------------
    // Reached only when a plot read as empty or ripe turns out still to be
    // growing (PLAN_MEAT_FIELD 2.5): the game says so itself rather than this
    // code trying to tell the difference from the plot art alone. Recognised by
    // a green button of the popup's own shape and the ABSENCE of the
    // seed-choice menu's three slots -- both dialogs put a similar green button
    // in a similar place (Water measured fw 0.321/fh 0.046 on
    // water_popup_adb.png, Select fw 0.244/fh 0.046 on seed_menu_left_adb.png,
    // close enough in aspect that shape alone would not tell them apart), but
    // only one of them ever has three slots above it.
    //
    // 4.0 was the Water button's own aspect at 1920, 368 x 92 px, with
    // nothing to spare, and at 1080 x 2340 the same button is 450 x 113,
    // 3.98: the popup went unread on a long display and the director called
    // it unknown (corpus/formats/water_popup_1080x2340_none0_014000.png,
    // 2026-09-28). The other green buttons of the field are far below it:
    // Select 3.07 and the watering-can dialog's Water 3.10 (seed_menu_left_012848,
    // boost_012720), so the floor is 3.5, between them.
    const val WATER_BUTTON_MIN_ASPECT = 3.5

    /**
     * The green Water button's tap target, or null.
     *
     * Never tapped by anything in this version of the skill (PLAN_MEAT_FIELD,
     * section 9) -- waterPopup only ever closes this dialog by tapping
     * outside it. Exposed here for section 10 and for the tests that must
     * prove it is never reached.
     */
    fun waterButton(img: Mat, anchor: Dungeon.Anchor = DIALOG): Blob? {
        val candidates = ArrayList<Blob>()
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val mask = Cv.hsvMask(img, SELECT_HUE)
        val (n, stats) = Cv.components(mask)
        mask.release()
        for (i in 1 until n) {
            val (x, y, w, h, area) = stats[i].toList()
            val share = area / (gw * gh).toDouble()
            if (share < SELECT_MIN_SHARE || h == 0 || w / h.toDouble() < WATER_BUTTON_MIN_ASPECT) continue
            candidates.add(Blob((x + w / 2.0 - x0) / gw, (y + h / 2.0 - y0) / gh, share))
        }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.share }
    }

    /**
     * The water popup's close target (anywhere well clear of the dialog),
     * or null if it is not open.
     *
     * True positive needs the wide green button and no seed-choice slots in
     * the same frame; the close target is fixed, well above the dialog, on
     * the dimmed field that is never interactive while any popup is up.
     * At 1920 that is the header's Good bag (x 567, y 141), and the header
     * is live on a bare field: a tap on its sack or its can opens that
     * item's card (item_info_seed_013145, item_info_can_013217). Under the
     * popup it is not: a tap at x 540, y 192, on the green can, closed the
     * popup and opened nothing, three times on 2026-09-28.
     */
    fun waterPopup(img: Mat, anchor: Dungeon.Anchor = DIALOG): Target? {
        waterButton(img, anchor) ?: return null
        if (seedSlots(img, anchor).isNotEmpty()) return null
        return Target(0.5, 0.10, anchor)
    }

    // ------------------------------------------------------------------------
    // popup_timer -- "Time Remaining" in the water popup
    // ------------------------------------------------------------------------
    // The plot's own plate (plotTimer) is small and often unreadable; the
    // popup a tap on a growing plot opens writes the same time large and
    // green. Measured on the eleven popups of 2026-09-28 and the two of the
    // laboratory (corpus/farm/water_popup_*.png, after_tap_growing.png,
    // corpus/formats/water_popup_*): the digits stand at fy 0.6202 to 0.6313
    // of the middle's rectangle on every one, 1920, 2340 and 2520 alike,
    // right-aligned to fx 0.6827, "55:24" from fx 0.6112 and "03:52:53" from
    // 0.5676; H 38 to 55, S 90 to 236, V 177 to 196. What else is green
    // there: the popup's own edge at fx 0.73 (V 100 to 121, under the V
    // floor) and the Water button, whose top is at fy 0.6525 (under the
    // band). "Estimated Harvest" above is orange. The watering-can dialog
    // writes its "Estimated Time Reduction" in the same green at fy 0.615
    // and fx 0.49 to 0.61, inside this band, so the timer is read only
    // where the popup's own Water button is ([waterPopup]).
    //
    // readTimerMask's colon is a dot of at most 6 px (COLON_DOT_MAX_H, the
    // plot plate's), and this font's dots are 5 px at 1920 and 6 at 2340; on
    // a display a third larger again they are 8. So the crop is brought to
    // the scale of the 1920 frame first, the rectangle's height against
    // POPUP_REF_GH, which on every 1080 x 1920 frame is no change at all.
    val POPUP_TIMER_BAND = doubleArrayOf(0.600, 0.645, 0.500, 0.720)
    val POPUP_TIMER_HUE = Dungeon.Hsv(intArrayOf(35, 80, 160), intArrayOf(60, 255, 255))
    const val POPUP_REF_GH = 1980

    /** "Time Remaining" of the water popup, seconds, or null when no popup is open or it does not parse. */
    fun popupTimer(img: Mat, anchor: Dungeon.Anchor = DIALOG): Int? {
        waterPopup(img, anchor) ?: return null
        val (top, bottom, left, right) = (bandPx(img, POPUP_TIMER_BAND, anchor) ?: return null).toList()
        val crop = Py.crop(img, top, bottom, left, right) ?: return null
        val scaled = toReference(crop, Dungeon.gameRect(img, anchor).gh)
        val mask = Cv.hsvMask(scaled, POPUP_TIMER_HUE)
        if (scaled !== crop) scaled.release()
        val value = readTimerMask(mask)
        mask.release()
        return value
    }

    /** [crop] at the scale of a 1080 x 1920 frame, whose rectangle is [POPUP_REF_GH] tall; itself when it is there already. */
    private fun toReference(crop: Mat, gh: Int): Mat {
        val f = POPUP_REF_GH / gh.toDouble()
        if (abs(f - 1.0) < 0.01) return crop
        val out = Mat()
        Imgproc.resize(crop, out, Size(), f, f, if (f < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC)
        return out
    }

    // ------------------------------------------------------------------------
    // The number under a slot -- the seed menu's three, the can dialog's two
    // ------------------------------------------------------------------------
    // Every slot writes its count white under its icon, in the header's
    // font: on seed_menu_left_012848.png "4", "92" and "54" at fy 0.5005 to
    // 0.5116, 22 px, centred on their slot (fx 0.288, 0.473, 0.660 against
    // the slots' 0.291, 0.476, 0.661 from seedSlots, fy 0.475); on the can
    // dialog (boost_012720.png) "4" and "0" at fy 0.4727 to 0.4833 under
    // slots at fy 0.4475 -- the same 0.025 to 0.036 below the slot's centre
    // in both dialogs. Above the digits the icon's own white ends 0.0235
    // below the centre (the Good bag's rim, 42 x 26 px, aspect 1.6), under
    // them the slot's name starts 0.054 below. So the band is 0.022 to
    // 0.048 under the centre and 0.065 either side, which holds three digits
    // (0.045 wide), and a glyph counts as a digit only in DIGIT_ASPECT --
    // the rim that reaches into the band is a sliver of it and wide.
    val SLOT_COUNT_DY = doubleArrayOf(0.022, 0.048)
    const val SLOT_COUNT_DX = 0.065

    /** The white glyphs of a box: its mask, and each glyph as (x, y, w, h). */
    private class Glyphs(val mask: Mat, val comps: List<IntArray>)

    private fun glyphsIn(img: Mat, band: DoubleArray, anchor: Dungeon.Anchor): Glyphs? {
        val (top, bottom, left, right) = (bandPx(img, band, anchor) ?: return null).toList()
        val crop = Py.crop(img, top, bottom, left, right) ?: return null
        val gray = Cv.gray(crop)
        val mask = brightMask(gray, 200.0)
        gray.release()
        return Glyphs(mask, rawComponents(mask))
    }

    private fun slotGlyphs(img: Mat, slot: Blob, anchor: Dungeon.Anchor): Glyphs? =
        glyphsIn(img, doubleArrayOf(slot.fy + SLOT_COUNT_DY[0], slot.fy + SLOT_COUNT_DY[1],
                                    slot.fx - SLOT_COUNT_DX, slot.fx + SLOT_COUNT_DX), anchor)

    /**
     * One to [maxDigits] digits in a row, read or null: a row of glyphs of
     * one height (rowClusters), each shaped like a digit, none further
     * from the next than a digit is wide. Null as soon as one digit does
     * not read -- half a count is worse than none (Dungeon.readCounter).
     */
    private fun plainCount(g: Glyphs, maxDigits: Int): Int? {
        for (row in rowClusters(g.comps)) {
            val tallest = row.maxOf { it[3] }
            val digits = row.filter {
                it[3] >= GLYPH_MIN_SHARE * tallest &&
                    DIGIT_ASPECT[0] <= it[2] / it[3].toDouble() && it[2] / it[3].toDouble() <= DIGIT_ASPECT[1]
            }.sortedBy { it[0] }
            if (digits.isEmpty() || digits.size > maxDigits) continue
            if ((0 until digits.size - 1).any { k ->
                    digits[k + 1][0] - (digits[k][0] + digits[k][2]) > DIGIT_GAP_MAX * minOf(digits[k][2], digits[k + 1][2])
                }) continue
            var value = 0
            var ok = true
            for (stat in digits) {
                val d = Dungeon.readDigit(g.mask, stat)
                if (d == null) { ok = false; break }
                value = value * 10 + d
            }
            if (ok) return value
        }
        return null
    }

    // An ad's count is written "n/2" -- on the can slot at 0 cans, "0/2"
    // (ad_dialog_cans_013607.png) -- or "Ad (n/2)" on the seed menu's
    // violet button (ad_dialog_seeds_013423.png), n the ads left today: the
    // slot said 0/2 and the game answered "Ad viewing limit reached."
    // (ad_limit_cans_013641.png), the button said 2/2, gave three seeds and
    // said nothing (ad_reward_seeds_013446.png). The slash is the proof of a
    // count, as Dungeon.counterDigits has it, and here it is not the only
    // tall glyph: both brackets of "(2/2)" are as tall (30 px against the
    // slash's 29 and the digits' 25), and "d" before the bracket reads as a
    // digit's shape. What only the slash has is its lean: its ink at the
    // top stands right of its ink at the bottom. So the count is the digits
    // straight left of the one tall glyph that leans, back to a gap or a
    // tall glyph -- "2" of "(2/2)", "0" of "0/2" -- and one digit must stand
    // right of it, the day's two. Tall is SLASH_TALL of the glyphs' median
    // height (27 against 22 on the slot, 29 against 25 on the button); the
    // lean is SLASH_LEAN of the glyph's width, a fixed grid's columns
    // (Dungeon.digitBitmap, 12): the slash leans 6.7 and 7.3 columns, the
    // brackets 0.6 and -0.6, the digits 0.0 to 2.0 -- and "d", 4.3, with its
    // stem at the top right, which is why tall comes first.
    const val SLASH_TALL = 1.12
    const val SLASH_LEAN = 0.25

    /** Does this glyph lean like a slash: its top quarter's ink right of its bottom quarter's? */
    private fun leans(mask: Mat, stat: IntArray): Boolean {
        val bits = Dungeon.digitBitmap(mask, stat) ?: return false
        fun centre(rows: IntRange): Double? {
            var sum = 0.0
            var n = 0
            for (r in rows) for (c in bits[r].indices) if (bits[r][c]) { sum += c; n += 1 }
            return if (n == 0) null else sum / n
        }
        val q = bits.size / 4
        val top = centre(0 until q) ?: return false
        val bottom = centre(bits.size - q until bits.size) ?: return false
        return top - bottom >= SLASH_LEAN * bits[0].size
    }

    /** n of an "n/2" in these glyphs, or null. */
    private fun adCount(g: Glyphs): Int? {
        // The film clapper beside "Ad" is white stripes 6 to 8 px tall; half
        // the tallest glyph leaves them out of the median and out of the walk.
        val maxH = g.comps.maxOfOrNull { it[3] } ?: return null
        val glyphs = g.comps.filter { it[3] >= 0.5 * maxH }.sortedBy { it[0] }
        if (glyphs.size < 3) return null
        val median = glyphs.map { it[3] }.sorted()[glyphs.size / 2]
        fun tall(s: IntArray) = s[3] >= SLASH_TALL * median
        val slash = glyphs.indices.singleOrNull { tall(glyphs[it]) && leans(g.mask, glyphs[it]) } ?: return null
        fun digit(s: IntArray) = !tall(s) &&
            DIGIT_ASPECT[0] <= s[2] / s[3].toDouble() && s[2] / s[3].toDouble() <= DIGIT_ASPECT[1]
        fun near(a: IntArray, b: IntArray) = b[0] - (a[0] + a[2]) <= DIGIT_GAP_MAX * minOf(a[2], b[2])
        val right = glyphs.getOrNull(slash + 1) ?: return null
        if (!digit(right) || !near(glyphs[slash], right)) return null
        val digits = ArrayList<IntArray>()
        var k = slash - 1
        var next = glyphs[slash]
        while (k >= 0 && digit(glyphs[k]) && near(glyphs[k], next)) {
            digits.add(0, glyphs[k])
            next = glyphs[k]
            k -= 1
        }
        if (digits.isEmpty()) return null
        return Dungeon.readCounter(g.mask, digits)
    }

    /**
     * The count under a seed slot (one of seedSlots' entries), or null --
     * also when the slot shows an ad's "n/2" instead of a count.
     */
    fun seedSlotCount(img: Mat, slot: Blob, anchor: Dungeon.Anchor = DIALOG): Int? {
        val g = slotGlyphs(img, slot, anchor) ?: return null
        try {
            if (adCount(g) != null) return null
            return plainCount(g, 3)
        } finally {
            g.mask.release()
        }
    }

    // ------------------------------------------------------------------------
    // boost_popup -- "Select Watering Can", the dialog after Water
    // ------------------------------------------------------------------------
    // Seen for the first time 2026-09-28 (corpus/farm/boost_*.png): the
    // title, two slots -- the Tiny Watering Can (the green one of the
    // header, "01:59" over it, its count under it) and the Huge one (blue,
    // 0) -- a row of four yellow keys MIN, -, +, MAX around a white box with
    // the number of cans, "Estimated Time Reduction" in green, and the green
    // Water button. Not the one-can-a-tap dialog the laboratory plan
    // guessed: the box opens at what the plot needs, the fewest cans that
    // ripen it, capped by the cans there are -- 2 for 55:24 with 4 cans, 1
    // for 19:39, 2 for 57:48, 1 for 53:35 with 1 can -- MAX says the same and
    // "+" goes no further (boost_min_012804.png, 1 after MIN, "30:00").
    // Water spends what the box says and closes everything: the field is
    // back at once with the can flying (boost_after_012806.png), the counter
    // 4 -> 3 with 1 in the box, the plot 54:23 -> 24:21 two seconds later,
    // 30:02 for 30:00 and 2 s; 3 -> 1 with 2 in the box and a 55-minute
    // Tanemon ripe at once (field_watered_ripe2_013418.png).
    //
    // What says it is this dialog: the four yellow keys in one row, fy
    // 0.554 to 0.589 of the middle's rectangle, H 17 to 30, S 174 to 255, V
    // 200 to 255, 0.0019 to 0.0023 of the rectangle each (MIN and MAX
    // wider, - and + narrower), and nothing of that yellow anywhere on the
    // field, the seed menu or the water popup at a tenth of that share; two
    // slots of the seed menu's brown in seedSlots' band (the can dialog's
    // at fy 0.4475); and a green button under the row, at fy 0.6616.
    // meat_field_screen is 2 under it at 1920 and 2340 and 4 at 2520 --
    // under Director.FIELD_DIALOG_CELLS, so the director asks for it apart.
    //
    // At 0 cans the same dialog shows a film clapper and "0/2" on the Tiny
    // slot, 0 in the box, "00:00", and the green button says "View Ads"
    // (ad_dialog_cans_013607.png): the same shape, another button. So a
    // Boost does not say which; [adDialog] and [boostCans] do.
    val BOOST_KEY_HUE = Dungeon.Hsv(intArrayOf(15, 150, 190), intArrayOf(32, 255, 255))
    const val BOOST_KEY_MIN_SHARE = 0.001
    val BOOST_KEY_FY = doubleArrayOf(0.54, 0.60)
    const val BOOST_KEYS = 4
    const val BOOST_KEY_ROW_TOL = 0.01
    val BOOST_BUTTON_FY = doubleArrayOf(0.63, 0.70)
    // Closing it: a tap outside, as the water popup. Not at the water
    // popup's fy 0.10, which is the header's Good bag at 1920; at 0.18 it
    // is the forest over the pots' labels (Lv. at fy 0.22 and down), and a
    // tap there on the bare field opened nothing (2026-09-28). The pots are
    // never tapped (PLAN_MEAT_FIELD_GIESSEN.md 2).
    val BOOST_CLOSE = doubleArrayOf(0.5, 0.18)

    /**
     * The watering-can dialog: [tiny] is the green can's slot, [button] the
     * green button under the keys (Water, or View Ads at 0 cans), [close] a
     * tap outside. [huge] is the blue can's slot and is never tapped
     * (PLAN_MEAT_FIELD_GIESSEN.md 4.1) -- it is here so that a test can
     * hold every tap against it.
     */
    data class Boost(val tiny: Target, val huge: Target, val button: Target, val close: Target) {
        fun toOracle(): Map<String, Any?> = mapOf(
            "tiny" to tiny.toOracle(), "huge" to huge.toOracle(),
            "button" to button.toOracle(), "close" to close.toOracle())
    }

    /** The watering-can dialog, or null if it is not open. */
    fun boostPopup(img: Mat, anchor: Dungeon.Anchor = DIALOG): Boost? {
        val keys = hueBlobs(img, BOOST_KEY_HUE, BOOST_KEY_MIN_SHARE, BOOST_KEY_FY, anchor)
        if (keys.size != BOOST_KEYS) return null
        if (keys.maxOf { it.fy } - keys.minOf { it.fy } > BOOST_KEY_ROW_TOL) return null
        val slots = seedSlots(img, anchor)
        if (slots.size != 2) return null
        val button = hueBlobs(img, SELECT_HUE, SELECT_MIN_SHARE, BOOST_BUTTON_FY, anchor)
            .maxByOrNull { it.share } ?: return null
        return Boost(Target(slots[0].fx, slots[0].fy, anchor), Target(slots[1].fx, slots[1].fy, anchor),
                     Target(button.fx, button.fy, anchor), Target(BOOST_CLOSE[0], BOOST_CLOSE[1], anchor))
    }

    /**
     * The green can's count on the dialog's Tiny slot, or null -- also at 0
     * cans, where the slot shows the ads left ("0/2") and not a count. The
     * second witness beside [waterCans]: the header is dimmed to 148 under
     * every dialog of the field and does not read (5.2 of the plan).
     */
    fun boostCans(img: Mat, anchor: Dungeon.Anchor = DIALOG): Int? {
        val boost = boostPopup(img, anchor) ?: return null
        return seedSlotCount(img, Explore.Blob(boost.tiny.fx, boost.tiny.fy, 0.0), anchor)
    }

    // The number in the white box: black, 33 px, at fx 0.462 to 0.481 and
    // fy 0.5621 to 0.5788 on every can dialog of 2026-09-28 (0.4607 to
    // 0.4814 and 0.5620 to 0.5789 at 2340 and 2520). The box runs fx 0.36
    // to 0.58 between "-" (to 0.337) and "+" (from 0.614), whose black
    // signs stay outside; the dialog's cream is grey 230 and more.
    val BOOST_AMOUNT_BAND = doubleArrayOf(0.550, 0.590, 0.360, 0.580)
    const val BOOST_AMOUNT_INK = 80.0

    /** How many cans the dialog's box says Water will spend, or null. */
    fun boostAmount(img: Mat, anchor: Dungeon.Anchor = DIALOG): Int? {
        boostPopup(img, anchor) ?: return null
        val (top, bottom, left, right) = (bandPx(img, BOOST_AMOUNT_BAND, anchor) ?: return null).toList()
        val crop = Py.crop(img, top, bottom, left, right) ?: return null
        val gray = Cv.gray(crop)
        val mask = Mat()
        Imgproc.threshold(gray, mask, BOOST_AMOUNT_INK, 255.0, Imgproc.THRESH_BINARY_INV)
        gray.release()
        try {
            return plainCount(Glyphs(mask, rawComponents(mask)), 3)
        } finally {
            mask.release()
        }
    }

    // ------------------------------------------------------------------------
    // ad_dialog -- where the field offers an ad
    // ------------------------------------------------------------------------
    // There is no ad button anywhere until a count is at 0 (the header has
    // no "+", and a tap on the sack or the can opens only the item's card):
    // the seed menu with its free slot at 0 turns Select into a violet "Ad
    // (n/2)" in Select's place (fx 0.476, fy 0.5995, H 122 to 130; the
    // dungeon panel's ad button is the same violet, and Dungeon.recognise
    // calls the menu a dialog_werbung), and the can dialog at 0 cans shows
    // "n/2" on the Tiny slot and "View Ads" on its green button. With the
    // Ad Skip Pass the reward comes at once: the Reward sheet with three
    // seeds (Dungeon.rewardSheet reads it), the sack 0 -> 3, and the menu
    // back with Select (seed_menu_after_ad_013504.png). Not seen: the cans'
    // reward -- that day's two were gone before the measurement -- and the
    // seeds at 1/2 and at 0/2.
    val SEED_AD_FY = doubleArrayOf(0.57, 0.63)
    const val SEED_AD_MIN_AREA = 0.004

    /** An ad offer: [kind] "seeds" or "cans", [left] the ads left today or null if unread, [button] what watches it. */
    data class AdOffer(val kind: String, val left: Int?, val button: Target) {
        fun toOracle(): Map<String, Any?> = mapOf("kind" to kind, "left" to left, "button" to button.toOracle())
    }

    /** The field's ad offer on the seed menu or the can dialog, or null. */
    fun adDialog(img: Mat, anchor: Dungeon.Anchor = DIALOG): AdOffer? {
        val boost = boostPopup(img, anchor)
        if (boost != null) {
            val g = slotGlyphs(img, Explore.Blob(boost.tiny.fx, boost.tiny.fy, 0.0), anchor) ?: return null
            val left = try { adCount(g) } finally { g.mask.release() }
            // A count and not "n/2": the can dialog with cans in it, no offer.
            return if (left == null) null else AdOffer("cans", left, boost.button)
        }
        if (meatFieldScreen(img) >= 6) return null
        if (seedSlots(img, anchor).size != 3) return null
        val button = Dungeon.findButtons(img, Dungeon.VIOLET, minArea = SEED_AD_MIN_AREA, minY = 0.0, anchor = anchor)
            .firstOrNull { it.fy in SEED_AD_FY[0]..SEED_AD_FY[1] } ?: return null
        val g = glyphsIn(img, doubleArrayOf(button.fy - button.fh / 2, button.fy + button.fh / 2,
                                            button.fx - button.fw / 2, button.fx + button.fw / 2), anchor) ?: return null
        val left = try { adCount(g) } finally { g.mask.release() }
        return AdOffer("seeds", left, Target(button.fx, button.fy, anchor))
    }

    // After an ad button with none left the game answers with a message box
    // of its own: dark blue, "Ad viewing limit reached.", one blue OK
    // (ad_limit_cans_013641.png; the can dialog stays under it and is
    // closed after the OK, boost_ad_cans0_013705.png). Dungeon.recognise
    // takes the OK for a panel's Attempt and calls it a dialog. Found as
    // what it is: Dungeon.findButtons' blue, a panel at least half the
    // rectangle wide and 0.15 tall (0.6626 x 0.2384 here), and in its lower
    // half a button the size of an OK (0.1953 x 0.0419, fy 0.5997 against
    // the panel's 0.5136). The item cards of the header are the same blue
    // panel with no button in it, and are not this.
    val LIMIT_PANEL_MIN = doubleArrayOf(0.50, 0.15)
    val LIMIT_OK_W = doubleArrayOf(0.12, 0.30)
    val LIMIT_OK_H = doubleArrayOf(0.03, 0.06)

    /** The OK of the game's one-button message box over the field -- the ad limit -- or null. */
    fun adLimit(img: Mat, anchor: Dungeon.Anchor = DIALOG): Target? {
        val blue = Dungeon.findButtons(img, Dungeon.BLUE, minY = 0.0, anchor = anchor)
        for (panel in blue) {
            if (panel.fw < LIMIT_PANEL_MIN[0] || panel.fh < LIMIT_PANEL_MIN[1]) continue
            val ok = blue.firstOrNull {
                it !== panel && it.fw in LIMIT_OK_W[0]..LIMIT_OK_W[1] && it.fh in LIMIT_OK_H[0]..LIMIT_OK_H[1] &&
                    abs(it.fx - panel.fx) <= panel.fw / 2 && it.fy > panel.fy && it.fy < panel.fy + panel.fh / 2
            } ?: continue
            return Target(ok.fx, ok.fy, anchor)
        }
        return null
    }
}
