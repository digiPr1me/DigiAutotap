package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two glyphs of the metre label that touch across their gap
 * (PLAN_WORLD_SEARCH_FORMATE.md F25; notes/world-search.md, "Two digits that
 * touch are one glyph too wide"): `Vision.segmentDigits` cuts them apart at
 * their neck ([Vision.SPLIT_NECK_MAX]) where it used to drop the pair.
 *
 * The live run's frames whose digit pairs touched are in the corpus, and
 * the oracle holds what they read; these cases hold the two edges of the
 * rule no live frame shows, on a corpus frame with the live run's cause
 * painted in -- the plate lit from behind. Each case first asks
 * [MeterGlyphs] whether the light joined what it says (a component as tall
 * as a digit and too wide for one, which the ratio rule alone drops), so
 * that it can only pass on the picture it claims.
 */
class CounterJoinTest {

    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val vision = Vision(ClassPathAssets)

    private fun frame(rel: String): Mat {
        // No corpus is the public copy of the source; a corpus without the frame is a fault.
        assumeTrue(File(repo, "corpus").isDirectory, "no corpus in this checkout")
        val file = File(repo, rel)
        assertTrue(file.exists(), "no corpus frame at $file")
        return Imgcodecs.imread(file.path)
    }

    private fun label(img: Mat, calib: Calib) = MeterGlyphs.measure(img, calib.roi("roi_meters"), "bright")!!

    /** Components as tall as a digit that the ratio rule alone would drop: the joins. */
    private fun joins(r: MeterGlyphs.Result) =
        r.all.filter { (c, v) -> v == "wide" && r.refH != null && c.h >= 0.80 * r.refH && c.h <= 1.25 * r.refH }

    /** [img] with the frame's pixels in [x0, x1) x [y0, y1) lit by [k]. */
    private fun lit(img: Mat, x0: Int, y0: Int, x1: Int, y1: Int, k: Int): Mat {
        val out = img.clone()
        val box = out.submat(y0, y1, x0, x1)
        Core.add(box, Scalar(k.toDouble(), k.toDouble(), k.toDouble()), box)
        return out
    }

    /**
     * The landscape board's label, "46,606m" on a 608 px canvas, has the
     * brightest gap the corpus's labels hold: its last digit and the 'm'
     * cross at 160 against the bar's 175. Ten grey levels more on the whole
     * plate -- what the live run's plates had over a pyramid -- and they join,
     * and before F25 the label read 4660. Cut at the neck, the '6' reads and
     * the 'm' is dropped for its height as it always is.
     */
    @Test
    fun `a digit that touches the m reads, and the m stays out`() {
        val img = frame("corpus/formats/board_1920x1080_landscape0_175233.png")
        val calib = vision.calibrate(img)
        assertEquals(46606L, vision.readNumber(img, "roi_meters", calib), "before the light")
        val (x, y, w, h) = calib.roi("roi_meters")
        val lit = lit(img, x, y, x + w, y + h, 10)
        val r = label(lit, calib)
        // The join stands after the four digits still kept, as wide as a digit and the 'm' together.
        val join = joins(r).singleOrNull()?.first
        assertTrue(r.kept.size == 4 && join != null && join.x > r.kept.last().right && join.w > 2 * r.digitW!!,
                   "the light has to join the last digit and the m: ${MeterGlyphs.line(r)}")
        assertEquals(46606L, vision.readNumber(lit, "roi_meters", calib), MeterGlyphs.line(r))
        lit.release()
        img.release()
    }

    /**
     * Light enough to fill a whole gap -- the plate from the middle of one
     * digit to the middle of the next lit by 200 -- makes no neck: the pair
     * is one block and stays dropped, as every join was before F25. Where the
     * thinnest column near the gap is then a glyph's own thin part (the base
     * of a '1', the tip of a '4''s bar) a cut lands there and the pieces do
     * not read. What the reader may answer is the whole number, the number
     * with the pair dropped, or nothing -- never a number the tracker would
     * take for a step.
     */
    @Test
    fun `a gap lit solid never reads as another number`() {
        for ((rel, value) in listOf("corpus/formats/board_720x1280_none0_230200.png" to 45515L,
                                    "corpus/formats/board_1920x1080_landscape0_175233.png" to 46606L)) {
            val img = frame(rel)
            val calib = vision.calibrate(img)
            val (x, y) = calib.roi("roi_meters")
            val kept = label(img, calib).kept
            assertEquals(5, kept.size, "$rel: the label's five digits")
            // Every gap between two digits but the comma's.
            for (i in listOf(0, 2, 3)) {
                val a = kept[i]; val b = kept[i + 1]
                val lit = lit(img, x + (a.x + a.w / 2) / 3, y + minOf(a.y, b.y) / 3,
                              x + (b.x + b.w / 2) / 3, y + (maxOf(a.bottom, b.bottom) + 2) / 3, 200)
                val r = label(lit, calib)
                assertTrue(joins(r).isNotEmpty(), "$rel gap $i: the light has to join the pair: ${MeterGlyphs.line(r)}")
                val read = vision.readNumber(lit, "roi_meters", calib)
                val whole = value.toString()
                val shortened = read?.toString()?.let { s -> s.length < whole.length && (whole.startsWith(s) || whole.endsWith(s)) }
                assertTrue(read == null || read == value || shortened == true, "$rel gap $i read $read: ${MeterGlyphs.line(r)}")
                lit.release()
            }
            img.release()
        }
    }
}
