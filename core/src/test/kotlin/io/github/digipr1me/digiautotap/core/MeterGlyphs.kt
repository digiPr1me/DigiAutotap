package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A counter's digits as `Vision.segmentDigits` builds them, measured for
 * where they touch (PLAN_WORLD_SEARCH_FORMATE.md F25): `boardProbe
 * --args=meters`.
 *
 * Per counter: the digits it keeps, the gap between each two neighbours
 * with its *valley* -- the lowest of the columns' brightest pixel across the
 * gap, which is what the bar has to stay above for the two to stay apart --
 * and the darkest pixel there (the plate's ground between them); and every
 * component `segmentDigits` drops for being too wide while it stands as tall
 * as a digit ("wide"), with its column profile's lowest points where it
 * would split into digits of the kept digits' width, the brightest pixel of
 * that column (the bridge), and the pieces' widths. And where the digits
 * stand in the crop: their top and bottom against its rows.
 *
 * Writes nothing; the caller prints.
 */
object MeterGlyphs {

    class Comp(val label: Int, val x: Int, val y: Int, val w: Int, val h: Int, val area: Int, val sat: Double?) {
        val ratio get() = w / h.toDouble()
        val right get() = x + w
        val bottom get() = y + h
    }

    class Gap(val left: Comp, val right: Comp, val cols: Int, val valley: Int, val ground: Int)

    class Split(val at: Int, val count: Int, val bridge: Int)

    class Wide(val c: Comp, val relH: Double, val n: Int, val splits: List<Split>, val pieces: List<Int>)

    class Result(
        val rows: Int, val cols: Int, val bar: Int,
        val kept: List<Comp>, val refH: Double?, val digitW: Double?,
        val gaps: List<Gap>, val wides: List<Wide>,
        /** Every component, with the first filter that drops it, as BoardProbe.glyphs says. */
        val all: List<Pair<Comp, String>>,
    ) {
        val topMargin get() = kept.minOfOrNull { it.y }
        val bottomMargin get() = kept.maxOfOrNull { it.bottom }?.let { rows - it }
    }

    fun measure(img: Mat, roi: IntArray, polarity: String, scale: Int = 3): Result? {
        val (x, y, w, h) = roi
        val crop = Py.crop(img, maxOf(0, y), y + h, maxOf(0, x), x + w) ?: return null
        val small = Cv.gray(crop)
        val gray = Mat()
        Imgproc.resize(small, gray, Size(), scale.toDouble(), scale.toDouble(), Imgproc.INTER_CUBIC)
        val bright = polarity == "bright"
        val bar = if (bright) 175 else 255 - 140  // in ink: grey for bright digits, 255 - grey for dark ones
        val binary = Mat()
        if (bright) Core.inRange(gray, Scalar(175.0), Scalar(255.0), binary)
        else Core.inRange(gray, Scalar(0.0), Scalar(140.0), binary)
        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids, 8)
        val hsv = Cv.hsv(crop)
        val colour = Mat()
        Imgproc.resize(hsv, colour, Size(gray.cols().toDouble(), gray.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        val width = binary.cols(); val height = binary.rows()
        val labelData = IntArray(height * width); labels.get(0, 0, labelData)
        val colourData = ByteArray(colour.rows() * colour.cols() * 3); colour.get(0, 0, colourData)
        val greyData = ByteArray(height * width); gray.get(0, 0, greyData)
        fun grey(r: Int, c: Int) = greyData[r * width + c].toInt() and 0xFF
        // For the dark polarity the "bright" pixel of a valley is the dark one:
        // everything below is measured as ink, 255 - grey there.
        fun ink(r: Int, c: Int) = if (bright) grey(r, c) else 255 - grey(r, c)

        val all = ArrayList<Pair<Comp, String>>()
        for (i in 1 until n) {
            val s = IntArray(5).also { stats.get(i, 0, it) }
            var sat: Double? = null
            if (bright) {
                var sum = 0L; var count = 0
                for (yy in s[1] until s[1] + s[3]) for (xx in s[0] until s[0] + s[2]) {
                    if (labelData[yy * width + xx] > 0) { sum += colourData[(yy * width + xx) * 3 + 1].toInt() and 0xFF; count += 1 }
                }
                if (count > 0) sat = sum.toDouble() / count
            }
            val c = Comp(i, s[0], s[1], s[2], s[3], s[4], sat)
            val verdict = when {
                c.area < 50 -> "area"
                c.x <= 1 -> "left edge"
                c.h > 0.95 * height -> "tall"
                c.ratio < 0.28 -> "ratio"
                c.ratio > 0.98 -> "wide"
                sat != null && sat > 90 -> "colour"
                else -> "height?"
            }
            all += c to verdict
        }
        val boxes = all.filter { it.second == "height?" }.map { it.first }
        val refH = if (boxes.isEmpty()) null else boxes.map { it.h }.sorted().let { hs ->
            if (hs.size % 2 == 1) hs[hs.size / 2].toDouble() else (hs[hs.size / 2 - 1] + hs[hs.size / 2]) / 2.0
        }
        val kept = if (refH == null) emptyList() else boxes.filter { it.h >= 0.80 * refH && it.h <= 1.25 * refH }.sortedBy { it.x }
        val digitW = kept.map { it.w.toDouble() }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

        // Column profile of one component: its pixels per column.
        fun profile(c: Comp): IntArray = IntArray(c.w) { dx ->
            var k = 0
            for (yy in c.y until c.bottom) if (labelData[yy * width + c.x + dx] == c.label) k += 1
            k
        }

        val wides = ArrayList<Wide>()
        if (refH != null && digitW != null) {
            for ((c, v) in all) {
                if (v != "wide") continue
                // A wide component whose saturation would drop it anyway is not a digit pair.
                if (c.sat != null && c.sat > 90) continue
                val relH = c.h / refH
                val nn = (c.w / digitW).roundToInt()
                val splits = ArrayList<Split>()
                val pieces = ArrayList<Int>()
                if (nn >= 2) {
                    val prof = profile(c)
                    var from = 0
                    for (k in 1 until nn) {
                        val centre = (k * c.w / nn.toDouble()).roundToInt()
                        val lo = maxOf(from + 1, centre - (digitW / 4).roundToInt())
                        val hi = minOf(c.w - 2, centre + (digitW / 4).roundToInt())
                        if (lo > hi) break
                        var best = lo
                        for (dx in lo..hi) if (prof[dx] < prof[best]) best = dx
                        var bridge = 0
                        for (yy in c.y until c.bottom) if (labelData[yy * width + c.x + best] == c.label) bridge = maxOf(bridge, ink(yy, c.x + best))
                        splits += Split(c.x + best, prof[best], bridge)
                        pieces += best - from
                        from = best + 1
                    }
                    pieces += c.w - from
                }
                wides += Wide(c, relH, nn, splits, pieces)
            }
        }

        val gaps = ArrayList<Gap>()
        for (i in 0 until kept.size - 1) {
            val a = kept[i]; val b = kept[i + 1]
            val top = minOf(a.y, b.y); val bot = maxOf(a.bottom, b.bottom)
            if (b.x <= a.right) { gaps += Gap(a, b, 0, 255, 255); continue }
            var valley = 255; var ground = 255
            for (cc in a.right until b.x) {
                var colMax = 0
                for (r in top until bot) { colMax = maxOf(colMax, ink(r, cc)); ground = minOf(ground, ink(r, cc)) }
                valley = minOf(valley, colMax)
            }
            gaps += Gap(a, b, b.x - a.right, valley, ground)
        }
        listOf(small, gray, binary, labels, stats, centroids, hsv, colour).forEach { it.release() }
        return Result(height, width, bar, kept, refH, digitW, gaps, wides, all.sortedBy { it.first.x })
    }

    /**
     * Where [a] and [b] of the bright mask come closest: the row (in the
     * crop at [scale]) and the columns of a's rightmost and b's leftmost ink
     * there. For a painted join that is where two glyphs would touch.
     */
    fun closest(img: Mat, roi: IntArray, a: Comp, b: Comp, scale: Int = 3): Triple<Int, Int, Int> {
        val (x, y, w, h) = roi
        val crop = Py.crop(img, maxOf(0, y), y + h, maxOf(0, x), x + w)!!
        val small = Cv.gray(crop)
        val gray = Mat()
        Imgproc.resize(small, gray, Size(), scale.toDouble(), scale.toDouble(), Imgproc.INTER_CUBIC)
        val width = gray.cols()
        val data = ByteArray(gray.rows() * width).also { gray.get(0, 0, it) }
        fun ink(r: Int, c: Int) = (data[r * width + c].toInt() and 0xFF) >= 175
        var best = Triple(-1, 0, Int.MAX_VALUE)
        for (r in maxOf(a.y, b.y) until minOf(a.bottom, b.bottom)) {
            val right = (a.right - 1 downTo a.x).firstOrNull { ink(r, it) } ?: continue
            val left = (b.x until b.right).firstOrNull { ink(r, it) } ?: continue
            if (left - right < best.third - best.second) best = Triple(r, right, left)
        }
        small.release(); gray.release()
        return best
    }

    fun f(x: Double, d: Int = 2) = String.format(Locale.ROOT, "%.${d}f", x)

    fun line(r: Result): String {
        val kept = r.kept.joinToString(" ") { "${it.x}+${it.w}" }
        val gaps = r.gaps.joinToString(" ") { "${it.cols}c v${it.valley} g${it.ground}" }
        val wides = r.wides.joinToString("  ") { wd ->
            "WIDE@${wd.c.x} ${wd.c.w}x${wd.c.h} h${f(wd.relH)} n${wd.n}" +
                (if (wd.splits.isNotEmpty()) " split " + wd.splits.joinToString(",") { "@${it.at} px${it.count} bridge${it.bridge}" } +
                    " pieces ${wd.pieces.joinToString("/")}" else "")
        }
        return "rows ${r.rows} digits top ${r.topMargin ?: "-"} bottom margin ${r.bottomMargin ?: "-"}  refH ${r.refH?.let { f(it, 1) } ?: "-"} " +
            "digitW ${r.digitW?.let { f(it, 1) } ?: "-"}  kept [$kept]  gaps [$gaps]" + (if (wides.isNotEmpty()) "  $wides" else "")
    }
}
