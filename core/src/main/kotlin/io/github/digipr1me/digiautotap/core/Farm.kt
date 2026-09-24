package io.github.digipr1me.digiautotap.core

import io.github.digipr1me.digiautotap.core.Explore.Blob
import io.github.digipr1me.digiautotap.core.Explore.Target
import org.opencv.core.Core
import org.opencv.core.Mat
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
 * Every constant keeps its Python name and value, and the sentence that says
 * where it came from. A number here is changed in farm.py first, with a
 * measurement, then the oracle is written again, then this file follows --
 * never the other way round (NOTES.md, "Two implementations, one
 * direction"). The frame is BGR, uint8, as `cv2.imread` gives it.
 */
object Farm {

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
     * cells without hiding the rest (NOTES.md, "a dialog dims what is behind
     * it"), and callers that need "definitely open, nothing covering it" ask
     * for 6, while a caller that only needs "this is still the field, not some
     * other screen" can accept fewer. Measured over the whole debug* archive
     * (677 frames, none of them meant to be this screen): the six-cell count
     * fires 6/6 on exactly four frames, two captured here and two the passive
     * helper's own "unclear" dumps of this same screen from an earlier run,
     * and nothing else in the archive reaches even 4.
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
                if (gap > DIGIT_GAP_MAX * minOf(a[2], b[2])) {
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
    fun freeSeeds(img: Mat): Int? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val top = Py.int(y0 + FREE_SEEDS_BAND[0] * gh)
        val bottom = Py.int(y0 + FREE_SEEDS_BAND[1] * gh)
        val left = Py.int(x0 + FREE_SEEDS_BAND[2] * gw)
        val right = Py.int(x0 + FREE_SEEDS_BAND[3] * gw)
        if (bottom <= top || right <= left) return null
        val crop = Py.crop(img, top, bottom, left, right) ?: return null
        val gray = Cv.gray(crop)
        val mask = brightMask(gray, 200.0)
        gray.release()
        try {
            val comps = rawComponents(mask)
            for (row in rowClusters(comps)) {
                val tallest = row.maxOf { it[3] }
                val digits = row.filter { it[3] >= GLYPH_MIN_SHARE * tallest }
                if (digits.size != 1 && digits.size != 2) continue
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
    fun seedRefill(img: Mat): Int? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val top = Py.int(y0 + REFILL_BAND[0] * gh)
        val bottom = Py.int(y0 + REFILL_BAND[1] * gh)
        val left = Py.int(x0 + REFILL_BAND[2] * gw)
        val right = Py.int(x0 + REFILL_BAND[3] * gw)
        val mask = refillMask(img, top, bottom, left, right) ?: return null
        val value = readTimerMask(mask)
        mask.release()
        return value
    }

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
                         fyBand: DoubleArray? = null): List<Blob> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
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
    fun seedSlots(img: Mat): List<Blob> =
        hueBlobs(img, SLOT_HUE, SLOT_MIN_SHARE, SLOT_FY_BAND).sortedBy { it.fx }

    /**
     * Is this slot (one of seedSlots(img)'s entries) the marked one?
     *
     * At least two of the bracket's four corners found in the annulus around
     * this slot's centre -- see BRACKET_DX/DY for why a plain radius would
     * also catch the slot's own icon.
     */
    fun seedSelected(img: Mat, slot: Blob): Boolean {
        var corners = 0
        for (b in hueBlobs(img, BRACKET_HUE, BRACKET_MIN_SHARE)) {
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
    fun selectButton(img: Mat): Blob? =
        hueBlobs(img, SELECT_HUE, SELECT_MIN_SHARE).maxByOrNull { it.share }

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
    fun seedMenu(img: Mat): Pair<List<Blob>?, Blob?> {
        if (meatFieldScreen(img) >= 6) return null to null
        val slots = seedSlots(img)
        val button = selectButton(img)
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
    const val WATER_BUTTON_MIN_ASPECT = 4.0

    /**
     * The green Water button's tap target, or null.
     *
     * Never tapped by anything in this version of the skill (PLAN_MEAT_FIELD,
     * section 9) -- waterPopup only ever closes this dialog by tapping
     * outside it. Exposed here for section 10 and for the tests that must
     * prove it is never reached.
     */
    fun waterButton(img: Mat): Blob? {
        val candidates = ArrayList<Blob>()
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
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
     */
    fun waterPopup(img: Mat): Target? {
        waterButton(img) ?: return null
        if (seedSlots(img).isNotEmpty()) return null
        return Target(0.5, 0.10)
    }
}
