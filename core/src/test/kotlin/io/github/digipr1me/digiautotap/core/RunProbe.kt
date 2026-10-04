package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.jsonObject
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.system.exitProcess

/**
 * The numbers behind what a dungeon run shows between two screens the
 * readers know (PLAN_RELEASE_1_3.md B6 and B11), printed before a threshold
 * was chosen. Reads, writes nothing.
 *
 *   gradlew :core:runProbe --no-daemon --args="sheet corpus staging/daily_won_sheet_paused_225504.png"
 *
 * The first argument is what to measure: `hsv` (the Reward sheet's band,
 * hue, saturation and value at five percentiles), `sheet` (the band's share
 * in the sheet's blue and in the other windows asked, and the "Tap to close"
 * words), `dark` (how black the canvas is), `vs` (the VS screen's two
 * halves). Every other argument is a picture, a folder, or `corpus`: every
 * frame of the corpus, as the oracle reads it. `mask=0.06:0.155:0.005` asks
 * again with the dot and its plate masked in at every fy of that range, as
 * readerProbe does.
 */
fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    if (args.size < 2) {
        println("runProbe wants a mode and pictures: `sheet corpus staging/x.png`")
        exitProcess(2)
    }
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val mode = args[0]
    if (mode == "preview") {
        RunProbe.preview(args[1].split(','), repo)
        return
    }
    val places: List<Double?> = args.drop(1).firstOrNull { it.startsWith("mask=") }?.removePrefix("mask=")
        ?.split(':')?.map { it.toDouble() }
        ?.let { (a, b, step) -> generateSequence(a) { it + step }.takeWhile { it <= b + 1e-9 }.toList() }
        ?: listOf(null)
    args.drop(1).firstOrNull { it.startsWith("floors=") }?.removePrefix("floors=")?.split(':')
        ?.map { it.toInt() }?.let { (a, b, step) -> RunProbe.floors = (a..b step step).toList() }
    val files = ArrayList<File>()
    for (arg in args.drop(1).filter { !it.startsWith("mask=") && !it.startsWith("floors=") }) {
        if (arg == "corpus") {
            files += OracleFamilies.corpus(repo).frames.map { File(repo, it) }
            continue
        }
        val f = File(arg).let { if (it.isAbsolute) it else File(repo, arg) }
        if (f.isDirectory) files += f.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name }
        else files.add(f)
    }
    println("$mode over ${files.size} pictures")
    for (file in files) {
        val raw = OracleFamilies.framed(Imgcodecs.imread(file.path), file.name)
        for (fy in places) {
            val img = if (fy == null) raw else OracleFamilies.copy(raw).also {
                Dot.mask(it, listOf(Dot.square(it.cols(), it.rows(), false, fy), Dot.plate(it.cols(), it.rows(), false, fy)))
            }
            val at = if (fy == null) "" else "[fy %.3f] ".format(fy)
            val name = file.path.replace('\\', '/').substringAfter("corpus/").substringAfter("staging/")
            when (mode) {
                "hsv" -> RunProbe.hsv(img, "$at$name")
                "sheet" -> RunProbe.sheet(img, "$at$name")
                "sheetS" -> RunProbe.sheetS(img, "$at$name")
                "dark" -> RunProbe.dark(img, "$at$name")
                "vs" -> RunProbe.vs(img, "$at$name")
                else -> { println("no mode '$mode'"); exitProcess(2) }
            }
            if (img !== raw) img.release()
        }
        raw.release()
    }
}

object RunProbe {

    private fun f(x: Double) = "%.4f".format(x)

    /** The crop [b] (x0, x1, y0, y1 as fractions) of the rectangle at [anchor]. */
    private fun box(img: Mat, b: DoubleArray, anchor: Dungeon.Anchor): Mat? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        return Py.crop(img, maxOf(0, Py.int(y0 + b[2] * gh)), minOf(img.rows(), Py.int(y0 + b[3] * gh)),
                       maxOf(0, Py.int(x0 + b[0] * gw)), minOf(img.cols(), Py.int(x0 + b[1] * gw)))
    }

    /** Percentiles of one 8-bit channel of [m]. */
    private fun percentiles(m: Mat, channel: Int, ps: List<Double>): List<Int> {
        val hist = IntArray(256)
        val row = ByteArray(m.cols() * m.channels())
        for (y in 0 until m.rows()) {
            m.get(y, 0, row)
            for (x in 0 until m.cols()) hist[row[x * m.channels() + channel].toInt() and 0xff] += 1
        }
        val n = hist.sum()
        return ps.map { p ->
            val target = p * (n - 1)
            var seen = 0
            var v = 0
            for (i in 0 until 256) {
                seen += hist[i]
                if (seen > target) { v = i; break }
            }
            v
        }
    }

    /** The band [Dungeon.rewardSheet] asks, as hue, saturation and value at 5/25/50/75/95 %. */
    fun hsv(img: Mat, name: String) {
        val band = box(img, Dungeon.SHEET_BAND, Dungeon.POPUP) ?: return println("$name  no band")
        val hsv = Dungeon.hsv(band)
        val ps = listOf(0.05, 0.25, 0.5, 0.75, 0.95)
        println("$name  H ${percentiles(hsv, 0, ps)}  S ${percentiles(hsv, 1, ps)}  V ${percentiles(hsv, 2, ps)}")
        hsv.release()
    }

    /** Windows the band's share is asked in, beside SHEET_BLUE: (name, window). */
    val WINDOWS = listOf(
        "sheet" to Dungeon.SHEET_BLUE,
        "s140" to Dungeon.Hsv(intArrayOf(100, 140, 130), intArrayOf(118, 255, 165)),
        "s130" to Dungeon.Hsv(intArrayOf(100, 130, 130), intArrayOf(118, 255, 165)),
        "s120" to Dungeon.Hsv(intArrayOf(100, 120, 130), intArrayOf(118, 255, 165)),
    )

    /** The band's share in each of [WINDOWS], and the "Tap to close" words at grey 200. */
    fun sheet(img: Mat, name: String) {
        val band = box(img, Dungeon.SHEET_BAND, Dungeon.POPUP) ?: return println("$name  no band")
        val shares = WINDOWS.map { (_, w) ->
            val m = Cv.hsvMask(band, w)
            Cv.share(m).also { m.release() }
        }
        val line = box(img, Dungeon.SHEET_CLOSE_LINE, Dungeon.POPUP)
        val words = if (line == null) -1.0 else {
            val g = Cv.gray(line)
            val white = Mat()
            Core.compare(g, Scalar(Dungeon.SHEET_CLOSE_GREY), white, Core.CMP_GE)
            Cv.share(white).also { g.release(); white.release() }
        }
        val state = Dungeon.recognise(img).state
        println("%-70s %-14s %s  words %s".format(name, state,
            WINDOWS.indices.joinToString("  ") { "${WINDOWS[it].first} ${f(shares[it])}" }, f(words)))
    }

    /** The saturation floors [sheetS] asks; `floors=lo:hi:step` on the command line. */
    var floors: List<Int> = (40..180 step 10).toList()

    /**
     * What `writeOracle` would change, without writing: every frame of the
     * named families' files read again ([OracleFamilies.check]) and every
     * answer that differs from the file printed. The diff before the oracle
     * is this session's to write.
     */
    fun preview(names: List<String>, repo: File) {
        for (name in names) {
            val family = OracleFamilies.BY_NAME[name] ?: run { println("no family '$name'"); return }
            val file = OracleFamilies.pathOf(family, repo)
            val oracle = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonObject
            val report = OracleFamilies.check(family, oracle, repo)
            val byFrame = report.mismatches.groupBy { it.path }
            println("== $name: ${report.frames} frames, ${report.mismatches.size} answers differ on ${byFrame.size} frames")
            for ((path, list) in byFrame) {
                println(path)
                for (m in list) println("    ${m.key}\n      oracle: ${m.python}\n      now:    ${m.kotlin}")
            }
        }
    }

    /**
     * The band's share in the sheet's hue and value (SHEET_BLUE) with the
     * saturation's floor at 40, 50, ... 180, and the words: what the floor
     * of 180 leaves out, and what a lower one lets in.
     */
    fun sheetS(img: Mat, name: String) {
        val band = box(img, Dungeon.SHEET_BAND, Dungeon.POPUP) ?: return println("$name  no band")
        val lo = Dungeon.SHEET_BLUE.lo.`val`
        val hi = Dungeon.SHEET_BLUE.hi.`val`
        val hsv = Dungeon.hsv(band)
        val shares = floors.map { s ->
            val m = Dungeon.inRange(hsv, Dungeon.Hsv(Scalar(lo[0], s.toDouble(), lo[2]), Scalar(hi[0], hi[1], hi[2])))
            Cv.share(m).also { m.release() }
        }
        hsv.release()
        val line = box(img, Dungeon.SHEET_CLOSE_LINE, Dungeon.POPUP)
        val words = if (line == null) -1.0 else {
            val g = Cv.gray(line)
            val white = Mat()
            Core.compare(g, Scalar(Dungeon.SHEET_CLOSE_GREY), white, Core.CMP_GE)
            Cv.share(white).also { g.release(); white.release() }
        }
        println("%-70s %-14s words %s  s%d..%d %s".format(name, Dungeon.recognise(img).state, f(words),
            floors.first(), floors.last(),
            shares.joinToString(" ") { f(it) }))
    }

    /**
     * How black the canvas is: its mean grey, and the share of it at grey
     * 16 and over. Over the canvas only (GAME_IN_WINDOW in the bottom's
     * rectangle, cut to the picture), not the headroom or the columns beside
     * a canvas that does not fill the display.
     */
    fun dark(img: Mat, name: String) {
        val g = Dungeon.GAME_IN_WINDOW
        val canvas = box(img, doubleArrayOf(g[0], g[0] + g[2], g[1], g[1] + g[3]), Dungeon.Anchor.BOTTOM)
            ?: return println("$name  no canvas")
        val grey = Cv.gray(canvas)
        val mean = Core.mean(grey).`val`[0]
        val lit = Mat()
        Core.compare(grey, Scalar(16.0), lit, Core.CMP_GE)
        val share = Cv.share(lit)
        lit.release(); grey.release()
        // And the reader's own number (Dungeon.canvasLit), which is this one.
        println("%-70s mean %8.3f  lit %s  reader %s %s".format(name, mean, f(share), f(Dungeon.canvasLit(img)),
            if (Dungeon.blackFrame(img)) "BLACK" else ""))
    }

    /** Saturated red and saturated blue, the VS screen's two halves. */
    val VS_RED = arrayOf(Dungeon.Hsv(intArrayOf(0, 150, 60), intArrayOf(8, 255, 255)),
                         Dungeon.Hsv(intArrayOf(172, 150, 60), intArrayOf(179, 255, 255)))
    val VS_BLUE = Dungeon.Hsv(intArrayOf(100, 150, 60), intArrayOf(125, 255, 255))

    /**
     * The VS screen: blue on the left, red on the right, split by a diagonal
     * from about fx 0.70 at the top to 0.30 at the bottom. The share of red
     * in a strip down the right edge of the canvas and of blue in one down
     * the left, between the rows the HUD's edges do not reach.
     */
    fun vs(img: Mat, name: String) {
        val g = Dungeon.GAME_IN_WINDOW
        fun canvasStrip(x0: Double, x1: Double) = box(img,
            doubleArrayOf(g[0] + x0 * g[2], g[0] + x1 * g[2], g[1] + 0.10 * g[3], g[1] + 0.90 * g[3]),
            Dungeon.Anchor.BOTTOM)
        val left = canvasStrip(0.02, 0.22) ?: return println("$name  no canvas")
        val right = canvasStrip(0.78, 0.98) ?: return println("$name  no canvas")
        fun share(m: Mat, ws: List<Dungeon.Hsv>): Double {
            val hsv = Dungeon.hsv(m)
            val all = Mat.zeros(m.size(), org.opencv.core.CvType.CV_8U)
            for (w in ws) {
                val one = Dungeon.inRange(hsv, w)
                Core.bitwise_or(all, one, all)
                one.release()
            }
            hsv.release()
            return Cv.share(all).also { all.release() }
        }
        val leftBlue = share(left, listOf(VS_BLUE))
        val rightRed = share(right, VS_RED.toList())
        val leftRed = share(left, VS_RED.toList())
        val rightBlue = share(right, listOf(VS_BLUE))
        println("%-70s left blue %s  right red %s  min %s   (left red %s, right blue %s)  reader %s %s".format(name,
            f(leftBlue), f(rightRed), f(minOf(leftBlue, rightRed)), f(leftRed), f(rightBlue),
            f(Dungeon.vsShare(img)), if (Dungeon.vsScreen(img)) "VS" else ""))
    }
}
