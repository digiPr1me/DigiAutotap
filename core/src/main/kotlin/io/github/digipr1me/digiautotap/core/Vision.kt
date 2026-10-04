package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.IdentityHashMap
import java.util.Locale

/** What `vision.CalibrationError` is: the board could not be found or fitted. */
class CalibrationError(message: String) : RuntimeException(message)

/**
 * The geometry `calibrate` derives from one frame: the card, the grid and the
 * counter boxes. Python holds it as a dict; the keys of [toOracle] are that
 * dict's keys. The boxes (`roi_*`) are `[x, y, w, h]` in pixels of the frame.
 */
class Calib(
    val card: IntArray,
    val gridX0: Double,
    val gridY0: Double,
    val cellW: Double,
    val cellH: Double,
    /** "roi_top_orange" ... "roi_fireballs" and "roi_meters", by their Python key. */
    val rois: Map<String, IntArray>,
    val skillButton: IntArray,
    val skillRadius: Int,
) {
    fun roi(key: String): IntArray = rois.getValue(key)

    fun toOracle(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["card"] = card.toList()
        out["grid_x0"] = gridX0
        out["grid_y0"] = gridY0
        out["cell_w"] = cellW
        out["cell_h"] = cellH
        for ((key, box) in rois) out[key] = box.toList()
        out["skill_button"] = skillButton.toList()
        out["skill_radius"] = skillRadius
        return out
    }
}

/**
 * Where the figure stands. `how` says which way it was found ("body", "eyes",
 * "eye_single", "template", "color"); `x`, `y` are the pixel position where
 * that way has one (the body way has none).
 */
class Figure(
    val row: Int,
    val col: Int,
    val how: String,
    val score: Double?,
    val x: Double? = null,
    val y: Double? = null,
) {
    fun toOracle(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["row"] = row
        out["col"] = col
        out["how"] = how
        out["score"] = score
        if (x != null) {
            out["x"] = x
            out["y"] = y
        }
        return out
    }
}

/**
 * vision.py's readers, carried over line for line: the Digital World Search
 * board -- `find_card`, `calibrate`, `skill_button_ok`, `read_grid` with
 * `search_patch` and `has_object_colour`, `find_figure`, the error banner
 * (`banner_visible`, `classify_banner`) and the counters (`segment_digits`,
 * `read_number`, `read_counters`). The skill's loop is not here; it comes
 * with its own session, under the director.
 *
 * Every constant came over with its Python name and value, and the sentence
 * that says where it came from; a number is changed in this file, with a
 * measurement and its sentence, and then `writeOracle` writes the oracle
 * (NOTES.md, "One project"). The frame is BGR, uint8, as `cv2.imread`
 * gives it.
 *
 * Unlike the other families this one reads images of its own: the object
 * templates from `templates/` and the digit references from `digits/shared/`.
 * They come in through [assets] and nowhere else, so that the tests (class
 * path) and the app (its APK) load the same bytes the same way, and the
 * caches the module keeps in Python live in the instance: build one per
 * run and keep it.
 *
 * Where the Python line is numpy, the port does the numpy arithmetic and not
 * merely the same maths: `mean` of a float32 array sums in float32, in
 * numpy's pairwise order, `np.arange` fills its floats from the first step,
 * `np.convolve` adds its three products left to right. `_fit_periodic` picks
 * the best of thousands of candidates by `>`, so a last-bit difference in the
 * profile is a different grid on the next frame -- the oracle proves the
 * numbers on 25 boards, this proves them everywhere else.
 */
class Vision(private val assets: AssetSource) {

    // ------------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------------
    companion object {
        const val ROWS = 5
        const val COLS = 5

        // Cell aspect, width to height, measured on 8 emulator screenshots.
        const val ASPECT = 1.220
        const val ASPECT_TOL = 0.03

        // The cell width the column fit may find, as a share of the card's
        // width. vision.py searched 0.17 to 0.20; on every board of the corpus
        // the cell is 0.1847 to 0.1857 of the card (61 frames, every format and
        // window; PLAN_WORLD_SEARCH_FORMATE.md F3). The top of 0.20 was a trap:
        // five cells of 0.20 are the card's whole width, and where the card
        // fills the picture its edge against the dark beside it stands at both
        // ends of the band. On 1644 x 3840 that edge is two pixels wide, 376 and
        // 379 in the column profile against 38 to 188 on the grid's own lines,
        // and the fit laid its first and last line on it: cell 328.0 (0.1995 of
        // the card) where the grid's is 303.7, so every template was scaled 8 %
        // too large, every cell read 8 % too wide, and the meter label, which
        // hangs off the grid's bottom, was looked for 113 px under it. The
        // grid's own fit loses that race only there: on five other boards
        // whose card fills the picture, 720 to 1440 wide, the edge-to-edge fit
        // scores 67 to 121 against the grid's 124 to 183, on 1644 x 3840 128.6
        // against 122.5, because at a scale of 1.86 the grid's lines spread
        // and the edge does not. The top is the middle between 0.1857 and
        // 0.1995 now -- a deliberate departure from vision.py, and on every
        // other frame of the corpus the fit's answer is the same, because its
        // best cell was always under it.
        const val CELL_W_MIN = 0.17
        const val CELL_W_MAX = 0.1926

        // Order matters, the specific templates come first.
        val OBJECT_TYPES = listOf("ticket_orange", "ticket_green", "ticket_pink", "claw",
                                  "paw", "fireball", "pyramid", "figure")

        // Templates that are not searched cell by cell on the board. The
        // figure is not, because find_figure does that separately and more
        // reliably; the arrow is not, because it is no object; the banner
        // parts are not, because they lie in an area of their own.
        val NON_BOARD_TEMPLATES = listOf("arrow", "figure", "banner_badge", "banner_text_move",
                                         "banner_text_insufficient")
        val POWERUPS = listOf("ticket_orange", "ticket_green", "ticket_pink", "claw", "paw", "fireball")

        // The pyramid is half transparent and looks like the empty tile, so it
        // needs a higher bar than the high-contrast power-ups.
        val THRESHOLDS = mapOf("pyramid" to 0.74, "figure" to 0.60, "arrow" to 0.55)
        const val DEFAULT_THRESHOLD = 0.62

        // In the bottom row the pyramid is matched with the upper two thirds of
        // its template only (23 of its 34 rows), because the wall ledge covers
        // the pyramid's lower part there -- what read_grid's docstring always
        // said and the search patch never did. Against the whole template the
        // score of what is left rode on the pyramids' glow, which pulses: the
        // 59 bottom pyramids of the corpus's 61 boards scored 0.472 to 0.919
        // against the row's bar of 0.68, so 33 of them read as empty, and one
        // board read three pyramids on one take at 1080 x 2340 and none on the
        // next (PLAN_WORLD_SEARCH_FORMATE.md F2). With the upper two thirds the
        // same 59 score 0.791 to 0.972 and every other bottom cell (246,
        // power-ups, the arrow and the figure among them) 0.427 at most: the
        // widest gap of any cut from 17 rows to 34 (0.364; 21 to 26 rows are
        // all 0.32 or more), and the bar, unchanged, 0.11 under the lowest
        // pyramid and 0.25 over the highest other cell. The score this row
        // reports for a pyramid is the upper part's. Only the pyramid: cut the
        // same way the bottom row's orange tickets read pink on three boards of
        // six; the power-ups have a cut of their own, next. A deliberate
        // departure from vision.py.
        const val PYRAMID_BOTTOM_SHARE = 2.0 / 3.0

        // In the bottom row a power-up is matched with the upper half of its
        // template, the orange SP Training Chip's without the 17 columns left
        // of the chip, and against a bar of its own. The ledge covers the lower
        // part of whatever stands there (its top at 0.58 of the cell on the
        // median plain bottom cell), and with the whole template the corpus's
        // six bottom chips -- all of them orange, on three window sizes --
        // scored 0.578 to 0.716 against the row's bar of 0.56. The upper half,
        // not the pyramid's two thirds: at 2/3 the cut still reaches rows the
        // ledge covers, and three of the six read pink (B2); at 1/2 every name
        // holds. The crop: the orange template carries a corner of the chip
        // next to it and floor in its columns 0 to 16, and on a highlighted
        // cell -- the one the figure steps to next, whose floor is light --
        // the whole template read the corpus's three such chips, laid under
        // the ledge, from 0.528 up, three of their 13 composites under 0.56:
        // a chip the figure walks up to leaves the reading at the last step,
        // and any upper part without the crop reads them lower still. Measured
        // over the corpus's 61 boards (`boardProbe --args=objects`,
        // PLAN_WORLD_SEARCH_FORMATE.md F10), with every power-up of the upper
        // rows laid under the ledge of each plain bottom cell of its frame as
        // well (440 composites): the six real chips 0.858 and up, the orange
        // composites 0.791 and up, the paw's 0.767, the claw's 0.852, the
        // fireball's 0.927, no name another power-up's (margin 0.166);
        // everything coloured that is none -- the figure's wings, feet and
        // shield, the arrow, the meter label, two real bottom cells and 393
        // composites -- 0.593 at most (a shield read as a paw). The bar is the
        // middle between the lowest power-up and that, 0.68. No board has a
        // green or a pink ticket, in any row: theirs is inferred from the
        // other four. A deliberate departure from vision.py.
        const val POWERUP_BOTTOM_SHARE = 0.5
        const val ORANGE_BOTTOM_LEFT = 17
        const val POWERUP_BOTTOM_MIN = 0.68

        // In rows 0 to 3 whatever stands in front of an object covers its
        // lower part as the ledge does in the bottom row: the tip of the
        // pyramid in the cell under it -- the board stacks pyramids in a
        // column -- the figure's head and wings, a claw falling on the cell
        // beside it. Against its whole template such a pyramid scored 0.53 to
        // 0.70 against 0.74 and read as empty, and the planner walked the
        // figure into it: the game smashes it with a claw and the figure
        // stays where it was (notes/world-search.md, "What stands in front of
        // an object covers it in every row"; PLAN_WORLD_SEARCH_FORMATE.md
        // F12, F14, F16, F17). So where
        // no template reads a cell of rows 0-3 it is looked at a second time,
        // with the bottom row's parts: the pyramid's upper two thirds
        // ([upperPart]) against a bar of its own, and in a coloured cell every
        // power-up's upper half ([bottomPart]) against [POWERUP_BOTTOM_MIN].
        // A second look, not a second race: every cell the whole templates
        // read keeps its name and its score, and every cell that reads nothing
        // keeps the score it had, so what moves is exactly the cells that were
        // missed. Measured over 158 frames, the corpus's 61 boards and the 97
        // of the B4 and B7 tours, every cell looked at (`coverProbe`): of the
        // 2866 cells of rows 0-3 not over the figure, every pyramid scores
        // 0.684 and up with its upper two thirds -- 0.789 and up but on three
        // frames kept after a claw had fallen, whose debris covers them -- and
        // every other cell 0.521 at most (the top-left cell, where the wall
        // meets the ledge); the figure's own cell 0.425. The bar is the middle,
        // 0.60. The power-ups' parts: a chip beside the figure over the tip of
        // a pyramid, with the arrow or the highlighted floor around it, read
        // 0.57 to 0.61 whole against 0.62 on four frames and 0.71 to 0.89 with
        // its upper half, a paw under a falling claw 0.45 and 0.93; every
        // power-up the whole templates read scores 0.750 and up with its part,
        // and every part names what the whole named; every coloured cell that
        // is none 0.610 at most, a coloured pyramid 0.577, the figure's own
        // cell 0.648 (a claw falling on it; not asked, below) -- so the bottom
        // row's bar holds here too. The two looks moved 92 cells of those
        // frames, 75 pyramids and 17 power-ups, and not one of them was
        // anything else. Over the figure's head (F16) they read what shows
        // above the wings -- 9 of 28 pyramids (0.656 to 0.958 with the part)
        // and 12 of the 25 chips no whole template read there (0.696 to
        // 0.894) -- and cannot read the rest: those pyramids score 0.33 to
        // 0.59 and an empty cell there 0.378, those chips 0.45 to 0.63 and an
        // empty cell 0.579. A deliberate departure from vision.py.
        const val PYRAMID_COVERED_MIN = 0.60

        // What the second look costs: one match more on every cell it asks,
        // six more on a coloured one, and asked everywhere it nearly doubled
        // readGrid's time (the 1080 boards' median 80 -> 145 ms on the JVM).
        // So the pyramid's part is asked only where the whole template scored
        // 0.30 or more -- every pyramid the second look read scored 0.378 and
        // up there (behind the figure's wings), one with a pyramid under it
        // 0.631 and up, and 1406 of the 2510 cells it would otherwise ask
        // score under it -- and neither part on the figure's own cell, which
        // the World skips anyway: 109 ms, and the same 92 cells.
        const val PYRAMID_COVERED_FLOOR = 0.30

        // Share of strongly coloured pixels, in per cent, from which a cell
        // counts as a possible power-up. Measured over the example pictures:
        // power-ups sit at 4 to 9 per cent, empty tiles and pyramids at 0. The
        // figure itself is coloured too, its cell is therefore skipped when
        // entering it into the map.
        const val OBJECT_COLOUR_MIN = 1.5

        // The figure is a dark blob with two bright yellow eyes. That feature
        // is unique on the board and works when the sprite is cut off at the
        // left edge of the board too. Template matching failed there.
        val HSV_EYE_LO = intArrayOf(18, 120, 170)
        val HSV_EYE_HI = intArrayOf(36, 255, 255)
        val HSV_BODY_LO = intArrayOf(0, 0, 0)
        val HSV_BODY_HI = intArrayOf(180, 255, 85)

        // Optional colour mode. If the game figure has a colour that occurs
        // nowhere else on the board, a colour mask is the most robust way to
        // find it: it survives the cut-off in column 1 and the run animation.
        // The board is blue, power-ups orange, green, pink and yellow,
        // pyramids violet-white, so a saturated red is unambiguous. None in
        // vision.py; not exercised by the oracle.
        val FIGURE_COLOR: List<Pair<IntArray, IntArray>>? = null
        const val FIGURE_COLOR_MIN_AREA = 0.02  // share of a cell's area

        // Signature of the figure, the share of dark, desaturated pixels in
        // the cell. Measured over four screenshots: the figure sits at 15 to
        // 19 per cent, pyramids and power-ups below 1.3 per cent. The sharpest
        // difference found, and it works when the sprite is cut off at the
        // left edge of the board as well.
        const val FIGURE_BODY_MIN = 5.0

        // Reference cell width of the banner templates. They were cut from
        // 1080 wide recordings, where a cell is 164 pixels wide.
        const val BANNER_REF_CELL_W = 164.0
        const val BANNER_BADGE_MIN = 0.60
        const val BANNER_TEXT_MIN = 0.48  // the score drops clearly at a small window size
        const val BANNER_TEXT_MARGIN = 0.08  // distance to the second best text, else unknown

        private const val AREA = 4  // cv2.CC_STAT_AREA

        const val GLYPH_W = 18
        const val GLYPH_H = 26
        const val DIGIT_SET = "shared"

        // The top bar has bright digits on a dark ground, the bottom one dark
        // digits on the light card. Hence the polarity per area.
        val POLARITY = mapOf(
            "roi_top_orange" to "bright",
            "roi_top_green" to "bright",
            "roi_top_pink" to "bright",
            "roi_paws" to "dark",
            "roi_claws" to "dark",
            "roi_fireballs" to "dark",
            "roi_meters" to "bright",
        )

        // Two digits of a bright counter that touch are cut apart again at
        // their neck. The metre label is white on a plate the board shows
        // through, and where the plate stands over something bright -- a
        // pyramid's lit face, the ledge's slope -- the gaps between its digits
        // are brighter too: over the 585 gaps that stayed open on the metre
        // labels of the corpus and of the live run of F25, a gap's brightest
        // crossing (the lowest of its columns' brightest pixel, at scale 3) is
        // 60 on the median and 163 at most, and the bar is 175; on the six
        // frames that read short the gaps left open cross at 101 to 133. Past
        // the bar two digits join into one component, too wide for a digit,
        // which the ratio rule drops, and the number reads short: 47,764m as
        // 477, 47,799m as 477, 47,800m and 47,834m as 478 on six frames of the
        // live run of 2026-09-29, at 720 x 1280 and on the landscape's 608 px
        // canvas, with the label whole to the eye (notes/world-search.md, "Two
        // digits that touch are one glyph too wide";
        // PLAN_WORLD_SEARCH_FORMATE.md F25). A join is a neck: the thinnest
        // column of the pair, within a quarter of a digit of where an even cut
        // would fall, holds 1 to 4 pixels, 0.031 to 0.143 of the component's
        // height, its brightest pixel 176 to 223 -- the halos of two glyphs,
        // not a stroke. Of everything else as tall as a digit and too wide for
        // one, over the corpus's 151 frames that calibrate and the live run's
        // ten, the thinnest such column is 0.227 (an icon in the pink box of a
        // Special Summon page, where nothing reads it) and then 0.38 to 1.0
        // (the top counters' cut digits on the two frames stored without their
        // headroom). The bar is the middle, 0.185. The metre's 'm' has necks
        // from 0.17 but stands at 0.69 to 0.77 of a digit's height on 149
        // labels, and a component and each of its pieces are held to the
        // digits' 0.80 as every digit is. Only the bright counters: the dark
        // ones stand on the opaque card, where a gap's darkest crossing is
        // grey 193 at the least against their bar of 140, and no component
        // there is as tall as a digit and too wide. On the corpus no reading
        // moves, and six of the live run's seven whole labels read whole; the
        // seventh is the box's (F26). A deliberate departure from vision.py.
        const val SPLIT_NECK_MAX = 0.185

        // Comparison by bitmap coverage instead of correlation. With two
        // coloured glyphs that is far more stable, one noisy reference image
        // can no longer make the whole digit unreadable.
        const val DIGIT_MIN_SCORE = 0.82
        const val DIGIT_MIN_MARGIN = 0.02

        val COUNTER_KEYS = listOf("roi_top_orange", "roi_top_green", "roi_top_pink", "roi_paws",
                                  "roi_claws", "roi_fireballs", "roi_meters")

        /**
         * The same seven under the names [readCounters] answers with, derived
         * rather than written out a second time. Every counter here has had two
         * spellings since readCounters was written: the ROI key a rectangle is
         * looked up under, and the bare name the value comes back as. Three
         * places then held their own copy of one of them, and
         * [Actions.mergeCounters]'s early exit compared a map of bare names
         * against [COUNTER_KEYS] -- which share nothing, so the test was false
         * on every frame and the loop sat out all four tries.
         */
        val COUNTER_NAMES = COUNTER_KEYS.map { it.replace("roi_", "") }

        /** How many `NN.png` a digit folder is asked for; the folders hold 7 to 16. */
        private const val DIGIT_SAMPLES_MAX = 100

        /** Python's `round(x, digits)`: the exact value, half to even. */
        private fun roundTo(x: Double, digits: Int): Double =
            BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    }

    // ------------------------------------------------------------------------
    // Calibration
    // ------------------------------------------------------------------------

    /** The grey game card in the window. Returns (x, y, w, h). */
    fun findCard(img: Mat): IntArray {
        val hsv = Cv.hsv(img)
        val mask = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 150.0), Scalar(180.0, 60.0, 255.0), mask)
        val closed = Mat()
        Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_CLOSE, Mat.ones(9, 9, CvType.CV_8U))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(closed, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hsv.release(); mask.release(); closed.release()
        if (contours.isEmpty()) throw CalibrationError("no bright area found")
        // Not blindly the largest contour. There can be bright areas beside the
        // card, so the largest one with a plausible portrait shape is chosen.
        val tried = ArrayList<String>()
        // sorted(..., reverse=True) is stable, and so is sortedByDescending.
        val ranked = contours.map { it to Geometry.contourArea(it) }.sortedByDescending { it.second }
        for ((cnt, _) in ranked.take(12)) {
            val r = Geometry.boundingRect(cnt)
            val x = r.x; val y = r.y; val w = r.width; val h = r.height
            if (w < 0.20 * img.cols() || h < 0.20 * img.rows()) continue
            val ratio = h / maxOf(w, 1).toDouble()
            tried.add(String.format(Locale.ROOT, "%dx%d h/w=%.2f", w, h, ratio))
            if (1.8 <= ratio && ratio <= 2.5) return intArrayOf(x, y, w, h)
        }
        throw CalibrationError("no portrait-format card found, candidates: " +
                               (if (tried.isEmpty()) "none" else tried.joinToString(", ")))
    }

    /**
     * Cell size and offset that maximise the edge energy on the n+1 grid
     * lines. More robust than picking peaks. Returns (score, offset, cell).
     */
    private fun fitPeriodic(profile: DoubleArray, cellLo: Double, cellHi: Double, n: Int,
                            offLo: Double, offHi: Double, cellStep: Double = 0.25):
        Triple<Double, Double, Double> {
        var bestScore = -1.0
        var bestOff = Double.NaN
        var bestCell = Double.NaN
        var found = false
        val length = profile.size
        for (cell in arange(cellLo, cellHi, cellStep)) {
            val limit = length - n * cell - 2
            val hi = if (limit < offHi) limit else offHi
            for (off in arange(offLo, hi, 0.5)) {
                var sum = 0.0
                for (k in 0..n) sum += profile[Py.roundInt(off + k * cell)]
                val score = sum / (n + 1)
                if (score > bestScore) {
                    bestScore = score; bestOff = off; bestCell = cell; found = true
                }
            }
        }
        if (!found) throw CalibrationError("periodic fit failed")
        return Triple(bestScore, bestOff, bestCell)
    }

    /**
     * `np.arange(start, stop, step)` on floats: the length is the ceiling of
     * (stop - start) / step, the first two values are start and start + step,
     * every further one is `start + i * delta` with delta the difference of
     * those two -- which is not always `step`.
     */
    private fun arange(start: Double, stop: Double, step: Double): DoubleArray {
        val length = Math.ceil((stop - start) / step)
        if (!(length > 0)) return DoubleArray(0)
        val n = length.toInt()
        val out = DoubleArray(n)
        out[0] = start
        if (n > 1) out[1] = start + step
        val delta = (start + step) - start
        for (i in 2 until n) out[i] = start + i * delta
        return out
    }

    /**
     * `np.convolve(a, np.ones(k) / k, mode="same")` for k = 3: the float32
     * profile in double, each output the sum of its neighbours' products with
     * 1/3 added left to right, the two ends with one neighbour short.
     */
    private fun smooth(a: FloatArray): DoubleArray {
        val third = 1.0 / 3
        val n = a.size
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            if (i - 1 >= 0) s += a[i - 1].toDouble() * third
            s += a[i].toDouble() * third
            if (i + 1 < n) s += a[i + 1].toDouble() * third
            out[i] = s
        }
        return out
    }

    /**
     * numpy's pairwise summation over float32, `pairwise_sum` in
     * loops_utils.h: under 8 elements one by one, up to 128 with eight
     * running sums, above that split in two at a multiple of 8.
     */
    private fun pairwise(a: FloatArray, lo: Int, n: Int): Float {
        if (n < 8) {
            var res = -0.0f
            for (i in 0 until n) res += a[lo + i]
            return res
        } else if (n <= 128) {
            val r = FloatArray(8) { a[lo + it] }
            var i = 8
            while (i < n - (n % 8)) {
                for (j in 0 until 8) r[j] += a[lo + i + j]
                i += 8
            }
            var res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]))
            while (i < n) {
                res += a[lo + i]
                i += 1
            }
            return res
        }
        var n2 = n / 2
        n2 -= n2 % 8
        return pairwise(a, lo, n2) + pairwise(a, lo + n2, n - n2)
    }

    /** `np.abs(cv2.Sobel(band, cv2.CV_32F, dx, dy, ksize=3))` as a flat array. */
    private fun absSobel(band: Mat, dx: Int, dy: Int): FloatArray {
        // Cloned first: a sub-matrix of the card would let Sobel look at the
        // pixels around it, and numpy hands cv2 a bare array.
        val own = band.clone()
        val dst = Mat()
        Imgproc.Sobel(own, dst, CvType.CV_32F, dx, dy, 3)
        val out = FloatArray(dst.rows() * dst.cols())
        dst.get(0, 0, out)
        for (i in out.indices) out[i] = Math.abs(out[i])
        own.release(); dst.release()
        return out
    }

    /** `.mean(axis=0)` of a float32 array, as numpy adds it: row after row in float32. */
    private fun meanAxis0(a: FloatArray, rows: Int, cols: Int): FloatArray {
        val out = FloatArray(cols)
        for (r in 0 until rows) {
            val base = r * cols
            for (c in 0 until cols) out[c] += a[base + c]
        }
        val count = rows.toFloat()
        for (c in 0 until cols) out[c] = out[c] / count
        return out
    }

    /** `.mean(axis=1)` of a float32 array, as numpy adds it: pairwise per row, from 0. */
    private fun meanAxis1(a: FloatArray, rows: Int, cols: Int): FloatArray {
        val out = FloatArray(rows)
        val count = cols.toFloat()
        val row = FloatArray(cols)
        for (r in 0 until rows) {
            System.arraycopy(a, r * cols, row, 0, cols)
            out[r] = (0.0f + pairwise(row, 0, cols)) / count
        }
        return out
    }

    /**
     * The full geometry from one frame.
     *
     * Important: do not call it on a frame with an error banner. The banner
     * distorts the edge profiles. Check banner_visible() first.
     */
    fun calibrate(img: Mat): Calib {
        val (cx, cy, cw, chh) = findCard(img).toList()
        val card = img.submat(cy, cy + chh, cx, cx + cw)
        val gray = Mat()
        Cv.gray(card).convertTo(gray, CvType.CV_32F)

        // Columns, a band only over the play field
        val band = Py.crop(gray, Py.int(0.40 * chh), Py.int(0.68 * chh), 0, gray.cols())
            ?: throw IllegalStateException("empty column band")
        val profCol = smooth(meanAxis0(absSobel(band, 1, 0), band.rows(), band.cols()))
        val (_, x0, cellW) = fitPeriodic(profCol, CELL_W_MIN * cw, CELL_W_MAX * cw, COLS, 0.0, 0.10 * cw)

        // Rows, the cell height tied to the measured aspect
        val band2 = Py.crop(gray, 0, gray.rows(), Py.int(0.06 * cw), Py.int(0.85 * cw))
            ?: throw IllegalStateException("empty row band")
        val profRow = smooth(meanAxis1(absSobel(band2, 0, 1), band2.rows(), band2.cols()))
        val target = cellW / ASPECT
        val (_, y0, cellH) = fitPeriodic(
            profRow,
            target * (1 - ASPECT_TOL),
            target * (1 + ASPECT_TOL),
            ROWS,
            0.30 * chh,
            0.36 * chh,
        )
        gray.release()

        val calib = build(intArrayOf(cx, cy, cw, chh), (cx + x0), (cy + y0), cellW, cellH)
        checkGeometry(calib, img.rows(), img.cols())
        return calib
    }

    /**
     * The card, grid and every box derived from them: `_counter_rois` plus
     * the meter label, which is tied to the grid and not to the card.
     */
    private fun build(card: IntArray, gridX0: Double, gridY0: Double, cellW: Double, cellH: Double): Calib {
        val cx = card[0]; val cy = card[1]; val cw = card[2]; val ch = card[3]

        // The counter areas as shares of the card box. The card is always
        // built the same way, so relative shares are allowed here.
        fun box(fx: Double, fy: Double, fw: Double, fh: Double): IntArray =
            intArrayOf(Py.int(cx + fx * cw), Py.int(cy + fy * ch), Py.int(fw * cw), Py.int(fh * ch))

        val rois = LinkedHashMap<String, IntArray>()
        // top bar, three currencies outside the minigame
        rois["roi_top_orange"] = box(0.208, 0.014, 0.232, 0.036)
        rois["roi_top_green"] = box(0.428, 0.014, 0.232, 0.036)
        rois["roi_top_pink"] = box(0.648, 0.014, 0.232, 0.036)
        // bottom bar, resources in the minigame
        rois["roi_paws"] = box(0.235, 0.852, 0.245, 0.038)
        rois["roi_claws"] = box(0.235, 0.892, 0.245, 0.038)
        rois["roi_fireballs"] = box(0.235, 0.932, 0.245, 0.038)
        // The meter label sticks to the bottom edge of the board in column 3,
        // so it is tied to the grid and not to the card. Its box hangs from
        // five rows of the height the aspect gives the cell width, not from
        // five fitted rows, and reaches down to that bottom: the fitted row
        // height scatters by up to 3 % from frame to frame of one window
        // (cellH 76.5 to 77.8 on the laboratory's 625 x 1150, 75.3 to 75.8 on
        // the landscape's 608 px canvas) while the label stands still to the
        // pixel, and five rows multiply it. Measured with `boardProbe
        // --args="meters hang calibrated"` over the 140 frames whose label the
        // probe finds clean in a wide band: the digits' bottom stands -0.0205
        // to 0.0590 of a cell width over the fitted bottom (-6.5 to +2.9 px)
        // and 0.0296 to 0.0519 over this one (-2.2 to +2.7 px), their top at
        // most 0.1665 over it. The old box ended 0.025 of a row over the
        // fitted bottom and cut the digits' lowest row where the fit ran
        // short: "32,329m" and "32,330m" read nothing on two laboratory frames
        // of the corpus, "47,844m" nothing on a frame of the live run of
        // 2026-09-29 (notes/world-search.md, "The metre box hangs from the
        // grid's rows, and the label does not"; PLAN_WORLD_SEARCH_FORMATE.md
        // F26). Over 161 frames (the corpus's 151 that calibrate and ten of
        // the live run): the old box 0.025 of a row taller reads the live
        // frame and not the two laboratory ones; hung from the aspect's rows
        // alone it reads all three with the digits' lowest row on the box's
        // last (0 rows of margin at scale 3); hung from them and reaching down
        // to their bottom, as here, it reads all three with 6 rows of margin
        // at the least and 16 on the median, and on no frame reads anything
        // else than before. The label's top keeps its share, 0.325 of a row
        // over the bottom. A deliberate departure from vision.py.
        val rowH = cellW / ASPECT
        val bottom = gridY0 + ROWS * rowH
        val midX = gridX0 + 2.5 * cellW
        rois["roi_meters"] = intArrayOf(
            Py.int(midX - 0.85 * cellW),
            Py.int(bottom - 0.325 * rowH),
            Py.int(1.70 * cellW),
            Py.int(0.325 * rowH),
        )
        // The skill button, the big round one at the bottom right
        val skillButton = intArrayOf(Py.int(cx + 0.655 * cw), Py.int(cy + 0.905 * ch))
        val skillRadius = Py.int(0.075 * cw)
        return Calib(card, gridX0, gridY0, cellW, cellH, rois, skillButton, skillRadius)
    }

    /**
     * Whether the button really sits at the computed point. The button is a
     * big bright, strongly coloured disc; the card around it is bright and
     * desaturated.
     */
    fun skillButtonOk(img: Mat, calib: Calib): Boolean {
        val x = calib.skillButton[0]
        val y = calib.skillButton[1]
        val r = Py.int(calib.skillRadius * 0.55)
        val patch = Py.crop(img, maxOf(0, y - r), y + r, maxOf(0, x - r), x + r) ?: return false
        val hsv = Cv.hsv(patch)
        val coloured = Mat()
        Core.inRange(hsv, Scalar(35.0, 70.0, 90.0), Scalar(110.0, 255.0, 255.0), coloured)
        val total = coloured.rows().toLong() * coloured.cols()
        // uint8 .mean(): the sum is exact (255 per set pixel), divided once.
        val mean = (255L * Core.countNonZero(coloured)).toDouble() / total
        hsv.release(); coloured.release()
        return mean > 60
    }

    private fun checkGeometry(calib: Calib, rows: Int, cols: Int) {
        val right = calib.gridX0 + COLS * calib.cellW
        val bottom = calib.gridY0 + ROWS * calib.cellH
        if (right > cols + 2 || bottom > rows + 2) throw CalibrationError("grid lies outside the picture")
        val asp = calib.cellW / calib.cellH
        if (Math.abs(asp - ASPECT) > ASPECT * ASPECT_TOL * 1.5) {
            throw CalibrationError(String.format(Locale.ROOT, "cell aspect %.3f implausible", asp))
        }
    }

    /** `[x, y, w, h]` of one cell. `row` and `col` are 0 based, row 0 on top, col 0 on the left. */
    fun cellRect(calib: Calib, row: Int, col: Int): IntArray = intArrayOf(
        Py.roundInt(calib.gridX0 + col * calib.cellW),
        Py.roundInt(calib.gridY0 + row * calib.cellH),
        Py.roundInt(calib.cellW),
        Py.roundInt(calib.cellH),
    )

    fun cellCenter(calib: Calib, row: Int, col: Int): Pair<Int, Int> {
        val x = calib.gridX0 + (col + 0.5) * calib.cellW
        val y = calib.gridY0 + (row + 0.5) * calib.cellH
        return Py.roundInt(x) to Py.roundInt(y)
    }

    /**
     * Several calibrations averaged by their median. The geometry does not
     * tip over one unlucky frame, like the observed 130.9 instead of 134.7
     * pixels of cell height. Every box is derived again from the median grid.
     */
    fun medianCalib(samples: List<Calib>): Calib {
        fun median(values: List<Double>): Double {
            val s = values.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
        val card = IntArray(4) { i -> Py.int(median(samples.map { it.card[i].toDouble() })) }
        return build(card,
                     median(samples.map { it.gridX0 }), median(samples.map { it.gridY0 }),
                     median(samples.map { it.cellW }), median(samples.map { it.cellH }))
    }

    // ------------------------------------------------------------------------
    // Object detection
    // ------------------------------------------------------------------------
    private var templateCache: Map<String, Mat>? = null

    private fun decode(path: String, flags: Int): Mat? {
        val bytes = assets.bytes(path) ?: return null
        val mat = Imgcodecs.imdecode(MatOfByte(*bytes), flags)
        return if (mat.empty()) null else mat
    }

    /**
     * All templates, objects and banner parts alike, out of `templates/` by
     * way of the asset source. Cached, since a frame needs it several times
     * over; a template the build does not carry is left out, as in Python.
     */
    fun loadTemplates(): Map<String, Mat> {
        templateCache?.let { return it }
        val templates = LinkedHashMap<String, Mat>()
        for (name in OBJECT_TYPES + NON_BOARD_TEMPLATES) {
            val img = decode("templates/$name.png", Imgcodecs.IMREAD_COLOR) ?: continue
            templates[name] = img
        }
        templateCache = templates
        return templates
    }

    /**
     * Only the templates that are searched on the board.
     *
     * The arrow is here on purpose, although it is no object. read_grid
     * checks it as well and throws the hit away, so that it is not reported
     * as an unknown object.
     */
    fun boardTemplates(templates: Map<String, Mat> = loadTemplates()): Map<String, Mat> {
        val src = if (templates.isEmpty()) loadTemplates() else templates
        val skip = NON_BOARD_TEMPLATES.filter { it != "arrow" }
        return src.filterKeys { it !in skip }
    }

    // The templates scaled to the current cell width, cached: without it
    // every template was rescaled for each of the 25 cells, 225 scalings per
    // picture instead of nine. Keyed on the template itself and the factor to
    // four places, as in Python -- two factors that round alike share the
    // first one's picture.
    private val scaleCache = IdentityHashMap<Mat, HashMap<Double, Mat>>()
    private var scaleEntries = 0

    private fun scaled(template: Mat, cellW: Double, refCellW: Double): Mat {
        val factor = cellW / refCellW
        if (Math.abs(factor - 1.0) < 0.02) return template
        val key = roundTo(factor, 4)
        var hit = scaleCache[template]?.get(key)
        if (hit == null) {
            val w = template.cols()
            val h = template.rows()
            hit = Mat()
            Imgproc.resize(template, hit, Size(maxOf(4, Py.int(w * factor)).toDouble(),
                                               maxOf(4, Py.int(h * factor)).toDouble()))
            if (scaleEntries > 200) {
                scaleCache.clear()
                scaleEntries = 0
            }
            scaleCache.getOrPut(template) { HashMap() }[key] = hit
            scaleEntries += 1
        }
        return hit
    }

    /**
     * `cv2.matchTemplate(image, tpl, cv2.TM_CCOEFF_NORMED).max()`. numpy's
     * `max` gives nan as soon as one entry is nan; a plain scan does the same.
     */
    private fun matchMax(image: Mat, tpl: Mat): Double {
        val res = Mat()
        Imgproc.matchTemplate(image, tpl, res, Imgproc.TM_CCOEFF_NORMED)
        val buf = FloatArray(res.rows() * res.cols())
        res.get(0, 0, buf)
        res.release()
        var best = buf[0]
        for (v in buf) {
            if (v.isNaN()) return Double.NaN
            if (v > best) best = v
        }
        return best.toDouble()
    }

    /**
     * The 5 x 5 matrix of object names. An empty cell is null, an unknown
     * object is '?'. An object in the bottom row is partly hidden by the wall
     * ledge and the meter label, so a pyramid there is matched with the upper
     * two thirds of its template ([PYRAMID_BOTTOM_SHARE]) against a bar 0.06
     * lower, and a power-up with its template's upper half ([bottomPart])
     * against [POWERUP_BOTTOM_MIN]. In rows 0-3 an object's lower part can be
     * covered too, by what stands in front of it, so a cell no whole template
     * reads there is looked at again with the same parts
     * ([PYRAMID_COVERED_MIN]); and a pyramid that reads is never dropped as
     * the arrow. Returns the names and, beside them, the scores -- in the
     * bottom row, and where the second look named a cell, the scores of
     * those parts.
     */
    fun readGrid(img: Mat, calib: Calib, templates: Map<String, Mat>, refCellW: Double = 87.5,
                 threshold: Double? = null, figure: Figure? = null):
        Pair<List<List<String?>>, List<List<Double>>> {
        val grid = List(ROWS) { arrayOfNulls<String>(COLS) }
        val scores = List(ROWS) { DoubleArray(COLS) }
        val powerups = templates.keys.filter { it in POWERUPS }
        // The yellow direction arrow is coloured and gets through the colour
        // prefilter. It is checked as well and dropped afterwards, otherwise
        // it would be an unknown object and fill the assistant's queue.
        val arrow = loadTemplates()["arrow"]
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val patch = searchPatch(img, calib, r, c) ?: continue
                // Prefilter over the colour. The board is blue, every power-up
                // is strongly orange, green, pink or yellow. The check costs
                // about 0.2 ms per cell, a template comparison a multiple of
                // that. Without coloured pixels only the pyramid has to be
                // checked.
                val colourful = hasObjectColour(img, calib, r, c)
                var names = if (colourful) powerups + "pyramid" else listOf("pyramid")
                if (colourful && arrow != null) names = names + "arrow"
                var bestName: String? = null
                var bestVal = 0.0
                var bestMargin = -9.0
                var pyramidVal: Double? = null
                val bottom = r == ROWS - 1
                for (name in names) {
                    val whole = templates[name] ?: continue
                    val tpl = when {
                        bottom && name == "pyramid" -> upperPart(whole)
                        bottom && name in POWERUPS -> bottomPart(name, whole)
                        else -> whole
                    }
                    val tplS = scaled(tpl, calib.cellW, refCellW)
                    if (tplS.rows() > patch.rows() || tplS.cols() > patch.cols()) continue
                    val value = matchMax(patch, tplS)
                    if (name == "pyramid") pyramidVal = value
                    var need = THRESHOLDS[name]
                        ?: (if (threshold != null && threshold != 0.0) threshold else DEFAULT_THRESHOLD)
                    if (bottom) {
                        // the bottom row is covered by ledge and meter label; a
                        // power-up there is its template's upper half, with a bar of its own
                        need = if (name in POWERUPS) POWERUP_BOTTOM_MIN else need - 0.06
                    }
                    val margin = value - need  // makes types with another bar comparable
                    if (margin > bestMargin) {
                        bestName = name; bestVal = value; bestMargin = margin
                    }
                }
                if (bestName == "arrow" && bestMargin >= 0) {
                    // The arrow template is a patch of pyramid, not the arrow
                    // (look at templates/arrow.png): on the 705 coloured cells
                    // of rows 0-3 of those 158 frames it reached its bar (0.55)
                    // on 25 pyramids, 0.64 to 0.96, and on no other cell -- not
                    // on one cell of the yellow arrow itself; in the bottom row
                    // it reached 0.40 at most. With a bar 0.19 under the
                    // pyramid's it won the race on every coloured pyramid that
                    // read -- 21 of 21 at 0.74 and up, a pyramid beside the
                    // figure whose wings, lance or arrow colour the cell -- and
                    // the pyramid was dropped as "direction arrow" (F17). So a
                    // pyramid that reads is a pyramid, whatever the arrow says,
                    // and only a cell where it does not is dropped. A
                    // deliberate departure from vision.py.
                    val pyr = pyramidRead(patch, calib, templates, refCellW, bottom, pyramidVal) ?: continue
                    grid[r][c] = "pyramid"
                    scores[r][c] = pyr
                    continue
                }
                if (bestName != null && bestMargin >= 0) {
                    grid[r][c] = bestName
                    scores[r][c] = bestVal
                    continue
                }
                // Rows 0-3 only: the second look at a covered object
                // ([PYRAMID_COVERED_MIN]); the bottom row's race already asked
                // the parts. Not on the figure's own cell, which the World
                // skips and on which a falling claw scores 0.648 as a chip.
                val onFigure = figure != null && figure.row == r && figure.col == c
                val covered = if (bottom || onFigure) null
                    else coveredLook(patch, calib, templates, refCellW, colourful, pyramidVal)
                if (covered != null) {
                    grid[r][c] = covered.first
                    scores[r][c] = covered.second
                } else if (colourful && figureBodyFraction(img, calib, r, c) < FIGURE_BODY_MIN) {
                    // Coloured, but no template fits. Two exceptions. First
                    // the figure itself, which has coloured eyes. Second the
                    // yellow direction arrow. It always stands right beside
                    // the figure and shows the direction last walked. Colour
                    // and dark glow do not tell it from power-ups, measured
                    // both features overlap completely. Its place beside the
                    // figure tells them apart unambiguously.
                    if (figure != null && isCrossNeighbour(figure, r, c)) continue
                    grid[r][c] = "?"
                    scores[r][c] = bestVal
                }
            }
        }
        return grid.map { it.toList() } to scores.map { it.toList() }
    }

    // The upper part of a template, kept beside the template it was cut from:
    // one Mat per source, so that [scaled]'s cache, which is keyed on the Mat
    // itself, finds it again on the next cell and the next frame.
    private val upperCache = IdentityHashMap<Mat, Mat>()
    private val bottomCache = IdentityHashMap<Mat, Mat>()

    /** The upper [PYRAMID_BOTTOM_SHARE] of [tpl], its rows from the top, cut before it is scaled. */
    fun upperPart(tpl: Mat): Mat = upperCache.getOrPut(tpl) {
        val rows = Py.roundInt(tpl.rows() * PYRAMID_BOTTOM_SHARE)
        tpl.submat(0, rows, 0, tpl.cols()).clone()
    }

    /**
     * What a power-up [name] is matched with in the bottom row: the upper
     * [POWERUP_BOTTOM_SHARE] of its template's rows, and for the orange chip
     * its columns from [ORANGE_BOTTOM_LEFT] on; cut before it is scaled.
     */
    fun bottomPart(name: String, tpl: Mat): Mat = bottomCache.getOrPut(tpl) {
        val rows = Py.roundInt(tpl.rows() * POWERUP_BOTTOM_SHARE)
        val x0 = if (name == "ticket_orange") ORANGE_BOTTOM_LEFT else 0
        tpl.submat(0, rows, x0, tpl.cols()).clone()
    }

    /** `matchMax` of [tpl] scaled to the cell, or null where it does not fit the patch. */
    private fun partScore(patch: Mat, tpl: Mat, calib: Calib, refCellW: Double): Double? {
        val tplS = scaled(tpl, calib.cellW, refCellW)
        if (tplS.rows() > patch.rows() || tplS.cols() > patch.cols()) return null
        return matchMax(patch, tplS)
    }

    /**
     * The pyramid's score where it reads, else null: in the bottom row its
     * upper two thirds ([pyramidVal], what the race asked) against the row's
     * bar, in rows 0-3 the whole template ([pyramidVal]) against its bar and,
     * where that misses, the upper two thirds against [PYRAMID_COVERED_MIN].
     */
    private fun pyramidRead(patch: Mat, calib: Calib, templates: Map<String, Mat>, refCellW: Double,
                            bottom: Boolean, pyramidVal: Double?): Double? {
        val bar = THRESHOLDS.getValue("pyramid")
        if (bottom) return pyramidVal?.takeIf { it - (bar - 0.06) >= 0 }
        if (pyramidVal != null && pyramidVal - bar >= 0) return pyramidVal
        return coveredPyramid(patch, calib, templates, refCellW, pyramidVal)
    }

    /**
     * The pyramid's upper two thirds where it reaches [PYRAMID_COVERED_MIN],
     * else null; asked only where the whole template ([pyramidVal]) scored
     * [PYRAMID_COVERED_FLOOR] or more.
     */
    private fun coveredPyramid(patch: Mat, calib: Calib, templates: Map<String, Mat>, refCellW: Double,
                               pyramidVal: Double?): Double? {
        if (pyramidVal == null || pyramidVal < PYRAMID_COVERED_FLOOR) return null
        val whole = templates["pyramid"] ?: return null
        return partScore(patch, upperPart(whole), calib, refCellW)?.takeIf { it - PYRAMID_COVERED_MIN >= 0 }
    }

    /**
     * The second look at a cell of rows 0-3 no whole template read
     * ([PYRAMID_COVERED_MIN]): the pyramid's upper two thirds, and in a
     * coloured cell each power-up's upper half, each against its bar; the
     * best margin names the cell, in the race's order on a tie. Null where
     * none reaches its bar.
     */
    private fun coveredLook(patch: Mat, calib: Calib, templates: Map<String, Mat>, refCellW: Double,
                            colourful: Boolean, pyramidVal: Double?): Pair<String, Double>? {
        var bestName: String? = null
        var bestVal = 0.0
        var bestMargin = 0.0
        if (colourful) {
            for (name in templates.keys.filter { it in POWERUPS }) {
                val value = partScore(patch, bottomPart(name, templates.getValue(name)), calib, refCellW) ?: continue
                val margin = value - POWERUP_BOTTOM_MIN
                if (margin >= 0 && (bestName == null || margin > bestMargin)) {
                    bestName = name; bestVal = value; bestMargin = margin
                }
            }
        }
        coveredPyramid(patch, calib, templates, refCellW, pyramidVal)?.let { value ->
            val margin = value - PYRAMID_COVERED_MIN
            if (bestName == null || margin > bestMargin) {
                bestName = "pyramid"; bestVal = value
            }
        }
        return bestName?.let { it to bestVal }
    }

    /** Is the cell directly above, below, left or right of the figure? */
    private fun isCrossNeighbour(figure: Figure, row: Int, col: Int): Boolean =
        Math.abs(figure.row - row) + Math.abs(figure.col - col) == 1

    /**
     * The search area of a cell. A bit larger than the cell, because icons do
     * not sit exactly centred. Limited downwards, because the bottom row is
     * covered by the wall ledge and the meter label. Null where numpy's slice
     * would be empty.
     */
    fun searchPatch(img: Mat, calib: Calib, row: Int, col: Int): Mat? {
        val (x, y, w, h) = cellRect(calib, row, col)
        val padX = Py.int(0.12 * w)
        val padY = Py.int(0.14 * h)
        val y1 = maxOf(0, y - padY)
        val y2 = minOf(img.rows(), y + h - Py.int(0.05 * h))
        val x1 = maxOf(0, x - padX)
        val x2 = minOf(img.cols(), x + w + padX)
        return Py.crop(img, y1, y2, x1, x2)
    }

    /**
     * A cheap precheck whether there is anything coloured in the cell at all.
     *
     * Replaces the former feature 'dark glow'. That did not separate: dark
     * pixels also occur on empty tiles, measured 0 to 39 per cent for
     * power-ups and empty fields alike. The colour separates cleanly, because
     * the board is blue throughout.
     */
    fun hasObjectColour(img: Mat, calib: Calib, row: Int, col: Int): Boolean {
        val patch = searchPatch(img, calib, row, col) ?: return false
        val hsv = Cv.hsv(patch)
        // (s > 110) & (v > 110) & ((h < 80) | (h > 140))
        val low = Mat()
        val high = Mat()
        Core.inRange(hsv, Scalar(0.0, 111.0, 111.0), Scalar(79.0, 255.0, 255.0), low)
        Core.inRange(hsv, Scalar(141.0, 111.0, 111.0), Scalar(255.0, 255.0, 255.0), high)
        Core.bitwise_or(low, high, low)
        val share = Core.countNonZero(low).toDouble() / (low.rows().toLong() * low.cols())
        hsv.release(); low.release(); high.release()
        return 100.0 * share >= OBJECT_COLOUR_MIN
    }

    // ------------------------------------------------------------------------
    // The figure
    // ------------------------------------------------------------------------

    /**
     * Search window, extended beyond the grid to the left and the top, because
     * the sprite sticks out there. Returns the crop and its origin.
     */
    private fun boardWindow(img: Mat, calib: Calib): Triple<Mat?, Int, Int> {
        val gx = Py.int(calib.gridX0)
        val gy = Py.int(calib.gridY0)
        val gw = Py.int(COLS * calib.cellW)
        val gh = Py.int(ROWS * calib.cellH)
        val x1 = maxOf(0, gx - Py.int(0.60 * calib.cellW))
        val y1 = maxOf(0, gy - Py.int(0.40 * calib.cellH))
        return Triple(Py.crop(img, y1, gy + gh, x1, gx + gw), x1, y1)
    }

    /** One connected-components run: the stats table and the centroids, background row included. */
    private class Blobs(val n: Int, val stats: Array<IntArray>, val centroids: Array<DoubleArray>)

    private fun blobs(mask: Mat): Blobs {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
        val table = Array(n) { i -> IntArray(5).also { stats.get(i, 0, it) } }
        val cent = Array(n) { i -> DoubleArray(2).also { centroids.get(i, 0, it) } }
        labels.release(); stats.release(); centroids.release()
        return Blobs(n, table, cent)
    }

    private fun findByColor(img: Mat, calib: Calib): Figure? {
        val ranges = FIGURE_COLOR
        if (ranges == null || ranges.isEmpty()) return null
        val (board, x1, y1) = boardWindow(img, calib)
        if (board == null) return null
        val hsv = Cv.hsv(board)
        var mask: Mat? = null
        for ((lo, hi) in ranges) {
            val part = Mat()
            Core.inRange(hsv, Scalar(lo[0].toDouble(), lo[1].toDouble(), lo[2].toDouble()),
                         Scalar(hi[0].toDouble(), hi[1].toDouble(), hi[2].toDouble()), part)
            if (mask == null) mask = part else Core.bitwise_or(mask, part, mask)
        }
        val closed = Mat()
        Imgproc.morphologyEx(mask!!, closed, Imgproc.MORPH_CLOSE, Mat.ones(5, 5, CvType.CV_8U))
        val b = blobs(closed)
        val need = FIGURE_COLOR_MIN_AREA * calib.cellW * calib.cellH
        var bestArea = -1
        var best: DoubleArray? = null
        for (i in 1 until b.n) {
            if (b.stats[i][AREA] < need) continue
            if (best == null || b.stats[i][AREA] > bestArea) {
                bestArea = b.stats[i][AREA]
                best = b.centroids[i]
            }
        }
        if (best == null) return null
        return toCell(calib, x1 + best[0], y1 + best[1], "color", null)
    }

    /** Small bright yellow spots lying inside a dark body. */
    private fun eyeBlobs(board: Mat, calib: Calib): List<Pair<IntArray, DoubleArray>> {
        val hsv = Cv.hsv(board)
        val eyes = Mat()
        val body = Mat()
        Core.inRange(hsv, Scalar(HSV_EYE_LO[0].toDouble(), HSV_EYE_LO[1].toDouble(), HSV_EYE_LO[2].toDouble()),
                     Scalar(HSV_EYE_HI[0].toDouble(), HSV_EYE_HI[1].toDouble(), HSV_EYE_HI[2].toDouble()), eyes)
        Core.inRange(hsv, Scalar(HSV_BODY_LO[0].toDouble(), HSV_BODY_LO[1].toDouble(), HSV_BODY_LO[2].toDouble()),
                     Scalar(HSV_BODY_HI[0].toDouble(), HSV_BODY_HI[1].toDouble(), HSV_BODY_HI[2].toDouble()), body)
        val grown = Mat()
        Imgproc.dilate(body, grown, Mat.ones(9, 9, CvType.CV_8U))
        Core.bitwise_and(eyes, grown, eyes)
        val b = blobs(eyes)
        hsv.release(); eyes.release(); body.release(); grown.release()
        val minArea = maxOf(6, Py.int(0.0008 * calib.cellW * calib.cellH))
        val maxArea = Py.int(0.05 * calib.cellW * calib.cellH)
        return (1 until b.n).filter { b.stats[it][AREA] in minArea..maxArea }
            .map { b.stats[it] to b.centroids[it] }
    }

    /** A candidate: (score, x, y, how). */
    private class Candidate(val score: Double, val x: Double, val y: Double, val how: String)

    /**
     * First eye pairs, then single eyes. The single eye is the column 1 case,
     * where the edge of the board cuts one eye off.
     */
    private fun eyeCandidates(board: Mat, calib: Calib, x1: Int, y1: Int): List<Candidate> {
        val found = eyeBlobs(board, calib)
        val pairs = ArrayList<Candidate>()
        val used = HashSet<Int>()
        for (i in found.indices) {
            for (j in i + 1 until found.size) {
                val (sa, ca) = found[i]
                val (sb, cb) = found[j]
                val dx = Math.abs(ca[0] - cb[0])
                val dy = Math.abs(ca[1] - cb[1])
                if (dy > 0.12 * calib.cellH) continue
                if (!(0.08 * calib.cellW < dx && dx < 0.45 * calib.cellW)) continue
                used.add(i)
                used.add(j)
                pairs.add(Candidate(minOf(sa[AREA], sb[AREA]) - dy,
                                    x1 + (ca[0] + cb[0]) / 2, y1 + (ca[1] + cb[1]) / 2, "eyes"))
            }
        }
        val singles = found.withIndex().filter { it.index !in used }
            .map { (_, sc) -> Candidate(sc.first[AREA].toDouble(), x1 + sc.second[0], y1 + sc.second[1], "eye_single") }
        // list.sort(reverse=True) on (score, x, y, how) tuples: descending on
        // all four, ties in their original order.
        val order = compareByDescending<Candidate> { it.score }.thenByDescending { it.x }
            .thenByDescending { it.y }.thenByDescending { it.how }
        return pairs.sortedWith(order) + singles.sortedWith(order)
    }

    /**
     * The yellow claw consists of two strokes and could pass as a pair of
     * eyes. Hence it is excluded explicitly.
     */
    private fun looksLikeClaw(img: Mat, calib: Calib, px: Double, py: Double, templates: Map<String, Mat>?): Boolean {
        if (templates == null || templates.isEmpty() || "claw" !in templates) return false
        val halfW = Py.int(0.32 * calib.cellW)
        val halfH = Py.int(0.30 * calib.cellH)
        val patch = Py.crop(img, maxOf(0, Py.int(py) - halfH), Py.int(py) + halfH,
                            maxOf(0, Py.int(px) - halfW), Py.int(px) + halfW) ?: return false
        val tpl = scaled(templates.getValue("claw"), calib.cellW, 87.5)
        if (patch.rows() < tpl.rows() || patch.cols() < tpl.cols()) return false
        return matchMax(patch, tpl) > 0.60
    }

    /**
     * Share, in per cent, of dark and desaturated pixels in the search area
     * of a cell: the figure's signature.
     */
    fun figureBodyFraction(img: Mat, calib: Calib, row: Int, col: Int): Double {
        val patch = searchPatch(img, calib, row, col) ?: return 0.0
        val hsv = Cv.hsv(patch)
        // (v < 90) & (s < 120)
        val mask = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 0.0), Scalar(255.0, 119.0, 89.0), mask)
        val share = Core.countNonZero(mask).toDouble() / (mask.rows().toLong() * mask.cols())
        hsv.release(); mask.release()
        return 100.0 * share
    }

    /**
     * The cell with the strongest figure signature.
     *
     * Replaces template matching as the main way. The template failed when
     * neighbouring fields looked different from when it was cut out,
     * measured in the field at 0.505 instead of the required 0.62. After
     * that the eye search took over and mistook the flash of an orange ticket
     * for a pair of eyes.
     */
    private fun findByBody(img: Mat, calib: Calib): Figure? {
        var bestFrac = 0.0
        var bestRow = -1
        var bestCol = -1
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val frac = figureBodyFraction(img, calib, row, col)
                if (frac > bestFrac) {
                    bestFrac = frac; bestRow = row; bestCol = col
                }
            }
        }
        if (bestFrac < FIGURE_BODY_MIN) return null
        return Figure(bestRow, bestCol, "body", roundTo(bestFrac, 1))
    }

    /**
     * Row and column of the figure, in three steps.
     *
     * 1. Colour mask, if FIGURE_COLOR is set. The most robust variant
     * 2. Template matching, precise, but it does not work when cut off at the
     *    left edge and not in the middle of the run animation
     * 3. Eyes. A pair anywhere, a single eye only in column 1, because the
     *    edge of the board cuts one off there
     *
     * Returns null when nothing is certain. The bot keeps track of its
     * position itself and needs the detection only at the start and on a
     * resync, so it may wait for a quiet frame.
     */
    fun findFigure(img: Mat, calib: Calib, templates: Map<String, Mat>? = null): Figure? {
        findByColor(img, calib)?.let { return it }

        findByBody(img, calib)?.let { return it }

        val (board, x1, y1) = boardWindow(img, calib)
        if (board == null) return null

        if (templates != null && "figure" in templates) {
            val tpl = scaled(templates.getValue("figure"), calib.cellW, 87.5)
            if (board.rows() >= tpl.rows() && board.cols() >= tpl.cols()) {
                val res = Mat()
                Imgproc.matchTemplate(board, tpl, res, Imgproc.TM_CCOEFF_NORMED)
                val m = Core.minMaxLoc(res)
                res.release()
                if (m.maxVal >= 0.62) {
                    return toCell(calib, x1 + m.maxLoc.x + tpl.cols() / 2.0,
                                  y1 + m.maxLoc.y + tpl.rows() / 2.0, "template", m.maxVal)
                }
            }
        }

        for (cand in eyeCandidates(board, calib, x1, y1)) {
            val px = cand.x
            val py = cand.y
            val col = Math.floor((px - calib.gridX0) / calib.cellW).toInt()
            val row = Math.floor((py - calib.gridY0) / calib.cellH).toInt()
            if (cand.how == "eye_single" && col > 0) continue  // a single eye only at the left edge
            // The flash of an orange ticket looks like a pair of eyes. Only
            // accept what lies in a dark cell.
            if (row in 0 until ROWS && col in 0 until COLS) {
                if (figureBodyFraction(img, calib, row, col) < FIGURE_BODY_MIN * 0.4) continue
            }
            if (looksLikeClaw(img, calib, px, py, templates)) continue
            return toCell(calib, px, py, cand.how, null)
        }
        return null
    }

    private fun toCell(calib: Calib, px: Double, py: Double, how: String, score: Double?): Figure? {
        val col = Math.floor((px - calib.gridX0) / calib.cellW).toInt()
        val row = Math.floor((py - calib.gridY0) / calib.cellH).toInt()
        if (!(row >= -1 && row < ROWS && col >= -1 && col < COLS)) return null
        return Figure(minOf(maxOf(row, 0), ROWS - 1), minOf(maxOf(col, 0), COLS - 1), how, score, px, py)
    }

    // ------------------------------------------------------------------------
    // Counters and banner
    // ------------------------------------------------------------------------

    /** The area in which the error banner appears; null where numpy's slice is empty. */
    fun bannerRoi(img: Mat, calib: Calib): Mat? {
        val gx = Py.int(calib.gridX0)
        val gy = Py.int(calib.gridY0 + 1.2 * calib.cellH)
        val gw = Py.int(COLS * calib.cellW)
        val gh = Py.int(2.2 * calib.cellH)
        return Py.crop(img, maxOf(0, gy), gy + gh, maxOf(0, gx), gx + gw)
    }

    private fun bannerScore(img: Mat, calib: Calib, name: String): Double {
        val tpl = loadTemplates()[name] ?: return 0.0
        val roi = bannerRoi(img, calib)
        val tplS = scaled(tpl, calib.cellW, BANNER_REF_CELL_W)
        if (roi == null || roi.rows() < tplS.rows() || roi.cols() < tplS.cols()) return 0.0
        return matchMax(roi, tplS)
    }

    /**
     * Recognises the error banner by the exclamation mark at the left of the
     * box.
     *
     * Colour statistics alone were not enough, they also responded to the
     * skill effect, to a ticket rain and to a pyramid destruction. The badge
     * separates cleanly: real banners sit at 0.85 to 1.00, the three false
     * triggers at 0.25 to 0.30.
     */
    fun bannerVisible(img: Mat, calib: Calib): Boolean =
        bannerScore(img, calib, "banner_badge") >= BANNER_BADGE_MIN

    /**
     * null, "move", "insufficient" or "unknown".
     *
     * "move"          operating error, a click outside the cross or on the
     *                 figure itself. Read again and go on
     * "insufficient"  a resource is empty. The text reads 'Insufficient
     *                 Attack(s).' for claws, the word after it changes with
     *                 the resource, so only 'Insufficient' is checked. Which
     *                 resource is missing follows from the attempted action
     * "unknown"       everything else. Then stop instead of guessing
     */
    fun classifyBanner(img: Mat, calib: Calib): String? {
        if (!bannerVisible(img, calib)) return null
        // Both text templates covered the same area, so their scores compare
        // directly. The better one wins, but only with a margin.
        val names = listOf("move", "insufficient")
        val scores = listOf(bannerScore(img, calib, "banner_text_move"),
                            bannerScore(img, calib, "banner_text_insufficient"))
        // max() and min() over the dict give the first of equals.
        var best = 0
        var other = 0
        for (i in 1 until scores.size) {
            if (scores[i] > scores[best]) best = i
            if (scores[i] < scores[other]) other = i
        }
        if (scores[best] < BANNER_TEXT_MIN) return "unknown"
        if (scores[best] - scores[other] < BANNER_TEXT_MARGIN) return "unknown"
        return names[best]
    }

    // The digit references, digits/shared/<digit>/<nn>.png, each grey and
    // scaled to a glyph. Kept as the set/unset bitmap the comparison uses.
    private var digitCache: Map<Char, List<BooleanArray>>? = null

    /**
     * Several reference images per digit, out of `digits/shared/<digit>/`. The
     * asset source has no listing, so `00.png`, `01.png` ... are asked for one
     * by one up to the first that is not there (a miss in the APK is an
     * exception, and 990 of them at the first start would be a cost for
     * nothing); the folders hold 7 to 16 of them, and the test says when a
     * folder stops being numbered without a gap, which is the day this and
     * Python's directory listing would disagree.
     */
    private fun digitTemplates(): Map<Char, List<BooleanArray>> {
        digitCache?.let { return it }
        val table = LinkedHashMap<Char, List<BooleanArray>>()
        val base = "digits/$DIGIT_SET"
        for (d in "0123456789") {
            val samples = ArrayList<Mat>()
            for (n in 0 until DIGIT_SAMPLES_MAX) {
                val img = decode("$base/$d/${String.format(Locale.ROOT, "%02d", n)}.png",
                                 Imgcodecs.IMREAD_GRAYSCALE) ?: break
                samples.add(img)
            }
            val legacy = decode("$base/$d.png", Imgcodecs.IMREAD_GRAYSCALE)
            if (legacy != null) samples.add(legacy)
            if (samples.isNotEmpty()) {
                table[d] = samples.map { s ->
                    val r = Mat()
                    Imgproc.resize(s, r, Size(GLYPH_W.toDouble(), GLYPH_H.toDouble()))
                    bitmap(r)
                }
            }
        }
        digitCache = table
        return table
    }

    /** `(m > 127).ravel()` of a grey glyph. */
    private fun bitmap(m: Mat): BooleanArray {
        val bytes = ByteArray(m.rows() * m.cols())
        m.get(0, 0, bytes)
        return BooleanArray(bytes.size) { (bytes[it].toInt() and 0xFF) > 127 }
    }

    /** How many reference images each digit has, for diagnosis. */
    fun digitStats(): Map<Char, Int> = digitTemplates().mapValues { it.value.size }.toSortedMap()

    /**
     * Splits a counter area into single digit images.
     *
     * Commas, the 'm' of the meter counter and frame parts are thrown out by
     * size, aspect ratio and height; two digits of a bright counter that
     * touch across their gap are cut apart again at their neck
     * ([SPLIT_NECK_MAX], [splitJoined]).
     */
    fun segmentDigits(img: Mat, roi: IntArray, polarity: String, scale: Int = 3): List<Mat> {
        val (x, y, w, h) = roi
        val crop = Py.crop(img, maxOf(0, y), y + h, maxOf(0, x), x + w) ?: return emptyList()
        val small = Cv.gray(crop)
        val gray = Mat()
        Imgproc.resize(small, gray, Size(), scale.toDouble(), scale.toDouble(), Imgproc.INTER_CUBIC)
        small.release()
        val binary = Mat()
        if (polarity == "bright") {
            Core.inRange(gray, Scalar(175.0), Scalar(255.0), binary)
        } else {
            // 140 instead of 100. At 100 only the darkest core of the digit
            // was captured and the shape came out thinner than at the top.
            // With 140 both masks are equally thick and one shared digit set
            // is enough.
            Core.inRange(gray, Scalar(0.0), Scalar(140.0), binary)
        }
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids, 8)
        val hsv = Cv.hsv(crop)
        val colour = Mat()
        Imgproc.resize(hsv, colour, Size(gray.cols().toDouble(), gray.rows().toDouble()), 0.0, 0.0,
                       Imgproc.INTER_NEAREST)

        val width = binary.cols()
        val labelData = IntArray(binary.rows() * width)
        labels.get(0, 0, labelData)
        val colourData = ByteArray(colour.rows() * colour.cols() * 3)
        colour.get(0, 0, colourData)

        val boxes = ArrayList<IntArray>()
        // Too wide for one digit, on a bright counter: kept aside for [splitJoined].
        val wide = ArrayList<IntArray>()
        for (i in 1 until n) {
            val s = IntArray(5).also { stats.get(i, 0, it) }
            val gx = s[0]; val gy = s[1]; val gw = s[2]; val gh = s[3]; val area = s[4]
            if (area < 50) continue
            if (gx <= 1) continue  // symbol at the left edge of the area
            if (gh > 0.95 * binary.rows()) continue
            val ratio = gw / gh.toDouble()
            val tooWide = ratio > 0.98
            // comma, 'm', frame, tile noise; too wide on a bright counter is
            // kept aside below, for two digits joined
            if (ratio < 0.28 || (tooWide && polarity != "bright")) continue
            // Top bar only. There the digits are white and the currency
            // symbols strongly coloured, so a symbol reaching in is thrown
            // out. Below, the digits themselves are navy and thus highly
            // saturated; the check would throw real digits away there.
            if (polarity == "bright") {
                // sat[sel].mean(): the labels of the whole box count, not only
                // this component's, as in Python.
                var sum = 0L
                var count = 0
                for (yy in gy until gy + gh) {
                    for (xx in gx until gx + gw) {
                        if (labelData[yy * width + xx] > 0) {
                            sum += colourData[(yy * width + xx) * 3 + 1].toInt() and 0xFF
                            count += 1
                        }
                    }
                }
                if (count > 0 && sum.toDouble() / count > 90) continue
            }
            if (tooWide) {
                wide.add(intArrayOf(gx, gy, gw, gh, i))
                continue
            }
            boxes.add(intArrayOf(gx, gy, gw, gh))
        }
        labels.release(); stats.release(); centroids.release()
        hsv.release(); colour.release(); gray.release()
        if (boxes.isEmpty()) {
            binary.release()
            return emptyList()
        }
        val heights = boxes.map { it[3] }.sorted()
        val refH = if (heights.size % 2 == 1) heights[heights.size / 2].toDouble()
                   else (heights[heights.size / 2 - 1] + heights[heights.size / 2]) / 2.0
        val glyphs = ArrayList<Pair<Int, Mat>>()
        val widths = ArrayList<Int>()
        for ((gx, gy, gw, gh) in boxes) {
            if (gh < 0.80 * refH || gh > 1.25 * refH) continue
            glyphs.add(gx to binary.submat(gy, gy + gh, gx, gx + gw).clone())
            widths.add(gw)
        }
        if (wide.isNotEmpty() && widths.isNotEmpty()) {
            widths.sort()
            val digitW = if (widths.size % 2 == 1) widths[widths.size / 2].toDouble()
                         else (widths[widths.size / 2 - 1] + widths[widths.size / 2]) / 2.0
            for (c in wide) glyphs.addAll(splitJoined(binary, labelData, c, refH, digitW))
        }
        binary.release()
        // list.sort is stable, and so is sortedBy.
        return glyphs.sortedBy { it.first }.map { it.second }
    }

    /**
     * A digit of a bright counter that touches its neighbour across the gap,
     * cut apart again: one [component] `(x, y, w, h, label)` of [binary], as
     * tall as a digit and too wide for one, cut at every neck
     * ([SPLIT_NECK_MAX]) and each piece judged as any component is -- the
     * digits among them, each with its x. A component without a neck is one
     * piece, too wide, and stays dropped.
     */
    private fun splitJoined(binary: Mat, labelData: IntArray, component: IntArray, refH: Double,
                            digitW: Double): List<Pair<Int, Mat>> {
        val (gx, gy, gw, gh) = component
        val label = component[4]
        if (gh < 0.80 * refH || gh > 1.25 * refH) return emptyList()
        val count = Math.round(gw / digitW).toInt()
        if (count < 2) return emptyList()
        val width = binary.cols()
        val profile = IntArray(gw) { dx ->
            var k = 0
            for (yy in gy until gy + gh) if (labelData[yy * width + gx + dx] == label) k += 1
            k
        }
        // A neck is looked for within a quarter of a digit of where each even
        // cut into digits of the counter's own width would fall -- the
        // counter's digits are all of one width, 0.875 to 1.19 of their
        // median over the 736 digits of the metre labels, so a pair's gap
        // stands at most 0.16 of a digit off the even cut; the live joins'
        // necks stood 0.02 to 0.13 off it. A third reached the thin base of
        // a '1' beside the gap. Where the thinnest column there is thicker
        // than a neck, no cut.
        val cuts = ArrayList<Int>()
        var from = 0
        for (k in 1 until count) {
            val centre = k * gw / count.toDouble()
            val lo = maxOf(from + 1, Math.ceil(centre - digitW / 4).toInt())
            val hi = minOf(gw - 2, Math.floor(centre + digitW / 4).toInt())
            if (lo > hi) continue
            var best = lo
            for (dx in lo..hi) if (profile[dx] < profile[best]) best = dx
            if (profile[best] > SPLIT_NECK_MAX * gh) continue
            cuts.add(best)
            from = best + 1
        }
        if (cuts.isEmpty()) return emptyList()
        val ink = ByteArray(binary.rows() * width).also { binary.get(0, 0, it) }
        val pieces = ArrayList<Pair<Int, Mat>>()
        var start = 0
        for (cut in cuts + gw) {
            // The neck's own column goes to neither side.
            val x0 = gx + start
            val x1 = gx + cut
            start = cut + 1
            var top = -1
            var bottom = -1
            for (yy in gy until gy + gh) {
                for (xx in x0 until x1) {
                    if (ink[yy * width + xx].toInt() != 0) {
                        if (top < 0) top = yy
                        bottom = yy + 1
                        break
                    }
                }
            }
            if (top < 0) continue
            // Each piece as any component: the ratio rule and the digits'
            // height, so that an 'm' cut off a digit is dropped as it always is.
            val ph = bottom - top
            val ratio = (x1 - x0) / ph.toDouble()
            if (ratio < 0.28 || ratio > 0.98 || ph < 0.80 * refH || ph > 1.25 * refH) continue
            pieces.add(x0 to binary.submat(top, bottom, x0, x1).clone())
        }
        return pieces
    }

    /** The best digit and its score; the score is the bitmap coverage, 1.0 is equal. */
    private fun classifyGlyph(glyph: Mat, table: Map<Char, List<BooleanArray>>): Pair<Char?, Double> {
        val g = Mat()
        Imgproc.resize(glyph, g, Size(GLYPH_W.toDouble(), GLYPH_H.toDouble()))
        val gb = bitmap(g)
        g.release()
        val ranked = ArrayList<Pair<Double, Char>>()
        for ((d, samples) in table) {
            var best = -1.0
            for (s in samples) {
                var same = 0
                for (i in gb.indices) if (gb[i] == s[i]) same += 1
                val coverage = same / gb.size.toDouble()
                if (coverage > best) best = coverage
            }
            ranked.add(best to d)
        }
        // ranked.sort(reverse=True) on (score, digit) tuples
        ranked.sortWith(compareByDescending<Pair<Double, Char>> { it.first }.thenByDescending { it.second })
        if (ranked.isEmpty()) return null to 0.0
        val (bestScore, bestDigit) = ranked[0]
        val margin = bestScore - (if (ranked.size > 1) ranked[1].first else 0.0)
        if (bestScore < DIGIT_MIN_SCORE || margin < DIGIT_MIN_MARGIN) return null to bestScore
        return bestDigit to bestScore
    }

    /**
     * A number out of one counter area.
     *
     * null when unreadable. The caller treats null as 'no information' and
     * never as 0, otherwise a reading error would look like a consumption.
     * The value is a Long, which holds 18 digits; a counter that long is not
     * a counter.
     */
    fun readNumber(img: Mat, roiKey: String, calib: Calib): Long? = readNumberDebug(img, roiKey, calib).first

    /** `read_number(..., debug=True)`: the value and what was found. */
    fun readNumberDebug(img: Mat, roiKey: String, calib: Calib): Pair<Long?, String> {
        val polarity = POLARITY.getValue(roiKey)
        val table = digitTemplates()
        if (table.isEmpty()) return null to "no digit references"
        val glyphs = segmentDigits(img, calib.roi(roiKey), polarity)
        if (glyphs.isEmpty()) return null to "no digits found"
        val out = StringBuilder()
        for ((i, glyph) in glyphs.withIndex()) {
            val (digit, score) = classifyGlyph(glyph, table)
            glyph.release()
            if (digit == null) {
                return null to String.format(Locale.ROOT, "character %d uncertain, score %.3f", i + 1, score)
            }
            out.append(digit)
        }
        check(out.length <= 18) { "a counter of ${out.length} digits" }
        return out.toString().toLong() to "ok"
    }

    /** For diagnosis: every glyph of one counter area, its digit (null where uncertain) and its score. */
    fun digitScores(img: Mat, roiKey: String, calib: Calib): List<Pair<Char?, Double>> {
        val table = digitTemplates()
        return segmentDigits(img, calib.roi(roiKey), POLARITY.getValue(roiKey)).map { glyph ->
            classifyGlyph(glyph, table).also { glyph.release() }
        }
    }

    /** Every counter by its short name ("top_orange" ... "meters"), null where unreadable. */
    fun readCounters(img: Mat, calib: Calib): Map<String, Long?> {
        val out = LinkedHashMap<String, Long?>()
        for ((key, name) in COUNTER_KEYS.zip(COUNTER_NAMES)) {
            out[name] = readNumber(img, key, calib)
        }
        return out
    }
}
