package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.system.exitProcess

/**
 * The numbers behind the Meat Field's small counts (PLAN_ABSCHLUSS_1_3.md
 * K10): every glyph under the seed menu's three slots and in the header's
 * sack and seed timer, with what [Dungeon.readDigit] measures on it -- its
 * size against the rectangle, its holes and where they sit, the ink of its
 * foot and of its right side -- once on the crop as the frame has it and
 * once on the crop brought to the scale of a 1080 x 1920 frame first
 * ([Farm.POPUP_REF_GH], as the water popup's timer is read). Reads, writes
 * nothing.
 *
 *   gradlew :core:seedDigitProbe --no-daemon --args="corpus/farm corpus/formats staging/farm900"
 *   gradlew :core:seedDigitProbe --no-daemon --args="bits staging/farm900/seed_menu_seeds0_900x1600_none0_205900.png"
 *
 * `bits` prints every glyph's 12 x 16 grid as well.
 */
fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    if (args.isEmpty()) {
        println("seedDigitProbe wants pictures or folders")
        exitProcess(2)
    }
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val bits = "bits" in args
    val files = ArrayList<File>()
    for (arg in args.filter { it != "bits" }) {
        val f = File(arg).let { if (it.isAbsolute) it else File(repo, arg) }
        if (f.isDirectory) files += f.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name }
        else files.add(f)
    }
    println("seed digits over ${files.size} pictures")
    for (file in files) {
        val img = OracleFamilies.framed(Imgcodecs.imread(file.path), file.name)
        val name = file.path.replace('\\', '/').substringAfter("corpus/").substringAfter("staging/")
        SeedDigitProbe.picture(img, name, bits)
        img.release()
    }
}

object SeedDigitProbe {

    private fun f(x: Double) = "%.3f".format(x)

    fun picture(img: Mat, name: String, bits: Boolean) {
        val slots = Farm.seedSlots(img)
        val field = Farm.meatFieldScreen(img)
        val gh = Dungeon.gameRect(img, Farm.DIALOG).gh
        val head = "== $name  ${img.cols()} x ${img.rows()}  gh $gh  field $field  slots ${slots.size}"
        if (slots.size == 3) {
            println(head)
            val counts = slots.map { Farm.seedSlotCount(img, it) }
            val chosen = slots.map { Farm.seedSelected(img, it) }
            val ad = Farm.adDialog(img)
            println("  seed_slot_count $counts  selected $chosen  ad_dialog ${ad?.toOracle()}  free_seeds ${Farm.freeSeeds(img)}")
            for ((k, slot) in slots.withIndex()) {
                val band = doubleArrayOf(slot.fy + Farm.SLOT_COUNT_DY[0], slot.fy + Farm.SLOT_COUNT_DY[1],
                                         slot.fx - Farm.SLOT_COUNT_DX, slot.fx + Farm.SLOT_COUNT_DX)
                glyphs(img, band, Farm.DIALOG, "slot$k", bits, white = true)
            }
        } else if (field >= 4) {
            println(head)
            println("  free_seeds ${Farm.freeSeeds(img)}  seed_refill ${Farm.seedRefill(img)}  water_cans ${Farm.waterCans(img)}")
            glyphs(img, Farm.FREE_SEEDS_BAND, Farm.HEADER, "sack", bits, white = true)
            glyphs(img, Farm.REFILL_BAND, Farm.HEADER, "refill", bits, white = false)
        }
    }

    /** A band of the rectangle at [anchor] in this frame's pixels, as Farm.bandPx has it. */
    private fun band(img: Mat, band: DoubleArray, anchor: Dungeon.Anchor): IntArray? {
        val r = Dungeon.gameRect(img, anchor)
        val top = maxOf(0, Py.int(r.y0 + band[0] * r.gh))
        val bottom = Py.int(r.y0 + band[1] * r.gh)
        val left = maxOf(0, Py.int(r.x0 + band[2] * r.gw))
        val right = Py.int(r.x0 + band[3] * r.gw)
        if (bottom <= top || right <= left) return null
        return intArrayOf(top, bottom, left, right)
    }

    private fun mask(crop: Mat, white: Boolean): Mat {
        if (white) {
            val gray = Cv.gray(crop)
            val m = Mat()
            Imgproc.threshold(gray, m, 200.0, 255.0, Imgproc.THRESH_BINARY)
            gray.release()
            return m
        }
        val hsv = Cv.hsv(crop)
        var m: Mat? = null
        for (range in Farm.REFILL_HUE) {
            val one = Cv.inRange(hsv, range)
            if (m == null) m = one else { Core.bitwise_or(m, one, m); one.release() }
        }
        hsv.release()
        return m!!
    }

    private fun glyphs(img: Mat, b: DoubleArray, anchor: Dungeon.Anchor, what: String, bits: Boolean, white: Boolean) {
        val (top, bottom, left, right) = (band(img, b, anchor) ?: return).toList()
        val crop = Py.crop(img, top, bottom, left, right) ?: return
        val gh = Dungeon.gameRect(img, anchor).gh
        val scale = Farm.POPUP_REF_GH / gh.toDouble()
        val scaled = Mat()
        Imgproc.resize(crop, scaled, Size(), scale, scale, if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC)
        for ((label, c, ref) in listOf(Triple("as is", crop, gh), Triple("x" + "%.3f".format(scale), scaled, Farm.POPUP_REF_GH))) {
            val m = mask(c, white)
            val (n, stats) = Cv.components(m)
            val comps = (1 until n).map { stats[it] }.filter { it[4] >= 3 }
            val tallest = comps.maxOfOrNull { it[3] } ?: 0
            val shown = comps.filter { it[3] >= 0.5 * tallest }.sortedBy { it[0] }
            println("  $what [$label] ${shown.size} glyphs of ${comps.size}, tallest $tallest px")
            for (s in shown) {
                val stat = intArrayOf(s[0], s[1], s[2], s[3])
                val grid = Dungeon.digitBitmap(m, stat)
                val d = Dungeon.readDigit(m, stat)
                if (grid == null) { println("    ${s.toList()} no grid"); continue }
                val rows = grid.size
                val cols = grid[0].size
                val holes = holes(grid)
                val foot = mean(grid, rows - 2, rows, 0, cols)
                val rightSide = mean(grid, 0, rows, cols - 4, cols)
                val upperRight = mean(grid, Dungeon.D1_ROWS[0], Dungeon.D1_ROWS[1], cols - Dungeon.D1_RIGHT_COLS, cols)
                val lowLeft = mean(grid, rows * 3 / 4, rows, 0, cols / 3)
                println("    x ${s[0]} y ${s[1]} ${s[2]}x${s[3]} (h ${f(s[3] / ref.toDouble())} of gh, aspect ${f(s[2] / s[3].toDouble())})" +
                    "  holes ${holes.size}${holes.joinToString("") { "@" + f(it) }}  bottom ${f(foot)}  right ${f(rightSide)}" +
                    "  upper-right ${f(upperRight)}  low-left ${f(lowLeft)}  -> $d")
                if (bits) for (r in grid) println("      " + r.joinToString("") { if (it) "#" else "." })
            }
            m.release()
        }
        scaled.release()
    }

    private fun mean(bits: Array<BooleanArray>, r0: Int, r1: Int, c0: Int, c1: Int): Double {
        var ink = 0
        for (r in r0 until r1) for (c in c0 until c1) if (bits[r][c]) ink += 1
        return ink.toDouble() / ((r1 - r0) * (c1 - c0))
    }

    /** Dungeon.holes, again: enclosed background, 8-connected, and how far down each sits. */
    private fun holes(bits: Array<BooleanArray>): List<Double> {
        val rows = bits.size + 2
        val cols = bits[0].size + 2
        val flat = ByteArray(rows * cols) { 1 }
        for (r in bits.indices) for (c in bits[r].indices) flat[(r + 1) * cols + c + 1] = if (bits[r][c]) 0 else 1
        val padded = Mat(rows, cols, CvType.CV_8U)
        padded.put(0, 0, flat)
        val labels = Mat()
        val count = Imgproc.connectedComponents(padded, labels, 8)
        val lab = IntArray(rows * cols)
        labels.get(0, 0, lab)
        padded.release(); labels.release()
        val found = ArrayList<Double>()
        for (k in 1 until count) {
            var sumY = 0L
            var n = 0
            var outside = false
            for (r in 0 until rows) for (c in 0 until cols) {
                if (lab[r * cols + c] != k) continue
                sumY += r; n += 1
                if (r == 0 || c == 0 || r == rows - 1 || c == cols - 1) outside = true
            }
            if (!outside) found.add(sumY.toDouble() / n / rows)
        }
        return found
    }
}
