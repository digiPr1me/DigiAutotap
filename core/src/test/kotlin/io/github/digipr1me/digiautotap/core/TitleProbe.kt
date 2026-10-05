package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.system.exitProcess

/**
 * The numbers behind the title screen's two readers (PLAN_RELEASE_1_3.md
 * B10), printed before a threshold was chosen. Reads, writes nothing.
 *
 *   gradlew :core:titleProbe --no-daemon --args="auto corpus staging/r2 staging/b10"
 *   gradlew :core:titleProbe --no-daemon --args="bar corpus staging/r2 staging/b10"
 *
 * `auto`: every blob in [Dungeon.autoButton]'s band that passes its width,
 * roundness and fill, with the sides of the band it touches -- a blob that
 * touches a side was cut by the band (notes/formats.md, rule 7) --, the
 * share of its box it encloses (holes of any colour) and the share it
 * encloses in white ([Dungeon.glyphWhite], the A), and what the reader as
 * it is answers; at the end the answers ordered by both shares and by
 * their distance from POS_AUTO, and how far the uncut blobs stand from each
 * side, in pixels. "answer" is the first blob that passes width, roundness
 * and fill -- the reader's answer before the A was asked.
 *
 * `bar`: the Touch To Start bar ([Startup.titleBar]) under the mask as it is
 * and under each mask of [BAR_MASKS], every component whose place passes
 * [Startup.BAR_FY] and [Startup.BAR_FX_OFF] with its shape numbers; and, on
 * every frame with one, the value (HSV V) of the bar-coloured pixels inside
 * the bar's rows and of those in the rows just above and below it, and the
 * hue of its bright pixels.
 *
 * `menu` (PLAN_RELEASE_1_3.md B43): every blob of Dungeon.BLUE in the
 * title's bottom right corner (fx 0.60 to 1.00, fy 0.88 to 1.02 of the
 * bar's rectangle) of a button's size and shape, with its fill and the
 * share of its box it encloses in white (the word "Menu"), and the sides of
 * the band it touches. At the end every such blob ordered by its distance
 * from the place the titles' own Menu stands.
 *
 * `popup` (PLAN_RELEASE_1_3.md B44): in the lower half of the middle
 * rectangle (fy 0.70 to 1.00), every small, square blob of Dungeon.BLUE
 * (the rim of "Don't show again today"'s box) with its fill and the share of
 * its box it encloses (any colour), and every wide one near the middle (an
 * OK); at the end the frames with a box, and with a box beside an OK.
 *
 * `news` (PLAN_RELEASE_1_3.md B50): what [Startup.announcement] costs a
 * round, in milliseconds on every picture beside what the whole of
 * [Director.classify] costs there, and classify's answer; and the motion
 * ([Director.motion]) from each picture to the one named before it, in the
 * order of the arguments -- the stacked windows of one morning, one after
 * the other.
 *
 * `sale` (PLAN_RELEASE_1_3.md B72): every run of rows in which a yellow
 * (H 10 to 40, S 100 up, V 150 up) covers half of the canvas's columns or
 * more, in the middle's rectangle, with its rows, its share at its widest
 * row and the median hue, saturation and value of its yellow; and what
 * [Startup.saleWindow] answers. At the end every run by its share, and the
 * pairs of runs on one picture with the distance between them.
 *
 * `help` (B73): every blob of Dungeon.BLUE low in the middle's rectangle of
 * a button's size (a Next), beside every blob of the darker blue of
 * [Startup.HELP_DARK] of a button's size left of it on its row (a Back),
 * with the two buttons' offset and their sizes against each other, and the
 * dark box between them above (the page counter); and what
 * [Startup.helpWindow] answers. At the end every pair ordered by how far it
 * is from the Help window's own.
 *
 * `corpus` is every frame of corpus/ ([OracleFamilies.corpus]); any other
 * argument is a picture or a folder of them.
 */
fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    if (args.size < 2) {
        println("titleProbe wants a mode and pictures: `auto corpus staging/r2`")
        exitProcess(2)
    }
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val files = ArrayList<File>()
    for (arg in args.drop(1)) {
        if (arg == "corpus") {
            files += OracleFamilies.corpus(repo).frames.map { File(repo, it) }
            continue
        }
        val f = File(arg).let { if (it.isAbsolute) it else File(repo, arg) }
        if (f.isDirectory) files += f.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name }
        else files.add(f)
    }
    println("${args[0]} over ${files.size} pictures")
    val probe = TitleProbe()
    for (file in files) {
        val img = OracleFamilies.read(file)
        val name = file.path.replace('\\', '/').substringAfter("corpus/").substringAfter("staging/")
        when (args[0]) {
            "auto" -> probe.auto(img, name)
            "bar" -> probe.bar(img, name)
            "menu" -> probe.menu(img, name)
            "popup" -> probe.popup(img, name)
            "news" -> { probe.news(img, name); continue }
            "sale" -> probe.sale(img, name)
            "help" -> probe.help(img, name)
            "gear" -> probe.gear(img, name)
            else -> { println("no mode '${args[0]}'"); exitProcess(2) }
        }
        img.release()
    }
    if (args[0] == "auto") probe.autoSummary()
    if (args[0] == "bar") probe.barSummary()
    if (args[0] == "menu") probe.menuSummary()
    if (args[0] == "popup") probe.popupSummary()
    if (args[0] == "news") probe.newsSummary()
    if (args[0] == "sale") probe.saleSummary()
    if (args[0] == "help") probe.helpSummary()
    if (args[0] == "gear") probe.gearSummary()
}

class TitleProbe {

    private fun f(x: Double) = "%.4f".format(x)

    // ------------------------------------------------------------------------
    // The auto button's band
    // ------------------------------------------------------------------------

    private var withBlob = 0
    private var firstCut = 0
    private val margins = Array(4) { ArrayList<Int>() }   // left, top, right, bottom, of the uncut blobs
    private val cutFrames = ArrayList<String>()

    fun auto(img: Mat, name: String) {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val (fx0, fx1, fy0, fy1) = Dungeon.AUTO_BAND.toList()
        val left = maxOf(0, Py.int(x0 + fx0 * gw))
        val top = maxOf(0, Py.int(y0 + fy0 * gh))
        val sub = Py.crop(img, top, Py.int(y0 + fy1 * gh), left, Py.int(x0 + fx1 * gw)) ?: return
        val hsv = Dungeon.hsv(sub)
        val mask = Dungeon.inRange(hsv, Dungeon.AUTO_BLUE)
        val labels = Mat()
        val statsMat = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(mask, labels, statsMat, centroids, 8)
        val stats = Array(count) { i -> IntArray(5).also { statsMat.get(i, 0, it) } }
        val white = Dungeon.inRange(hsv, Dungeon.HOME_WHITE)
        hsv.release(); mask.release(); statsMat.release(); centroids.release()
        val lines = ArrayList<String>()
        var first = true
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (h == 0) continue
            val fw = w / gw.toDouble()
            if (!(Dungeon.AUTO_W[0] <= fw && fw <= Dungeon.AUTO_W[1])) continue
            val aspect = w / h.toDouble()
            if (!(Dungeon.AUTO_ASPECT[0] <= aspect && aspect <= Dungeon.AUTO_ASPECT[1])) continue
            val fill = area / (w * h).toDouble()
            if (fill < Dungeon.AUTO_FILL_MIN) continue
            val sides = buildList {
                if (x == 0) add("left")
                if (y == 0) add("top")
                if (x + w == sub.cols()) add("right")
                if (y + h == sub.rows()) add("bottom")
            }
            val holes = holeShare(labels, i, x, y, w, h)
            val whiteHoles = Dungeon.glyphWhite(labels, white, i, x, y, w, h)
            if (kotlin.math.abs(whiteHoles - whiteHoleShare(labels, white, i, x, y, w, h)) > 1e-12) mismatches += 1
            val dfx = (left + x + w / 2.0 - x0) / gw - Dungeon.POS_AUTO[0]
            val dfy = (top + y + h / 2.0 - y0) / gh - Dungeon.POS_AUTO[1]
            if (first) {
                withBlob += 1
                if (sides.isNotEmpty()) { firstCut += 1; cutFrames += name }
                answers += Answer(name, holes, fill, dfx, dfy, sides.isEmpty(), whiteHoles)
            }
            if (sides.isEmpty()) {
                margins[0] += x; margins[1] += y
                margins[2] += sub.cols() - (x + w); margins[3] += sub.rows() - (y + h)
            }
            lines += "   %s fx %s fy %s fw %s fh %s fill %.3f holes %.3f white %.3f  %dx%d at %d,%d in %dx%d  %s".format(
                if (first) "answer" else "later ",
                f((left + x + w / 2.0 - x0) / gw), f((top + y + h / 2.0 - y0) / gh), f(fw), f(h / gh.toDouble()),
                fill, holes, whiteHoles, w, h, x, y, sub.cols(), sub.rows(),
                if (sides.isEmpty()) "uncut" else "CUT " + sides.joinToString("+"))
            first = false
        }
        labels.release(); white.release()
        if (lines.isNotEmpty()) {
            val now = Dungeon.autoButton(img)
            val was = answers.lastOrNull()?.takeIf { it.name == name }
            val moved = (now == null) != (was == null) ||
                (now != null && was != null && kotlin.math.abs(now.fx - Dungeon.POS_AUTO[0] - was.dfx) > 1e-9)
            if (moved) changed += name
            println("== $name  ${img.cols()} x ${img.rows()}  reader now: " +
                (now?.let { "fx %s fy %s".format(f(it.fx), f(it.fy)) } ?: "null"))
            lines.forEach { println(it) }
        }
    }

    class Answer(val name: String, val holes: Double, val fill: Double, val dfx: Double, val dfy: Double,
                 val uncut: Boolean, val white: Double)

    /**
     * The enclosed pixels that are white (HOME_WHITE), as a share of the box,
     * counted island by island -- the same quantity [Dungeon.glyphWhite]
     * takes with a flood, computed a second way to hold it to.
     */
    private fun whiteHoleShare(labels: Mat, white: Mat, label: Int, x: Int, y: Int, w: Int, h: Int): Double {
        val roi = labels.submat(y, y + h, x, x + w)
        val other = Mat()
        Core.compare(roi, org.opencv.core.Scalar(label.toDouble()), other, Core.CMP_NE)
        val lab = Mat(); val st = Mat(); val cen = Mat()
        val n = Imgproc.connectedComponentsWithStats(other, lab, st, cen, 4)
        val row = IntArray(5)
        val wroi = white.submat(y, y + h, x, x + w)
        var count = 0
        for (k in 1 until n) {
            st.get(k, 0, row)
            if (!(row[0] > 0 && row[1] > 0 && row[0] + row[2] < w && row[1] + row[3] < h)) continue
            val island = Mat()
            Core.compare(lab, org.opencv.core.Scalar(k.toDouble()), island, Core.CMP_EQ)
            val both = Mat()
            Core.bitwise_and(island, wroi, both)
            count += Core.countNonZero(both)
            island.release(); both.release()
        }
        roi.release(); other.release(); lab.release(); st.release(); cen.release(); wroi.release()
        return count / (w * h).toDouble()
    }

    private val answers = ArrayList<Answer>()
    private var mismatches = 0
    private val changed = ArrayList<String>()

    /**
     * The share of the blob's box that is enclosed by the blob and not part
     * of it: the pixels a flood from outside the box cannot reach. The auto
     * button's white A and its two arrows stand inside the blue disc.
     */
    private fun holeShare(labels: Mat, label: Int, x: Int, y: Int, w: Int, h: Int): Double {
        val box = labels.submat(y, y + h, x, x + w)
        val blob = Mat()
        Core.compare(box, org.opencv.core.Scalar(label.toDouble()), blob, Core.CMP_EQ)
        val padded = Mat.zeros(h + 2, w + 2, org.opencv.core.CvType.CV_8U)
        blob.copyTo(padded.submat(1, h + 1, 1, w + 1))
        val outside = Mat()
        Core.bitwise_not(padded, outside)
        val ffMask = Mat.zeros(h + 4, w + 4, org.opencv.core.CvType.CV_8U)
        Imgproc.floodFill(outside, ffMask, org.opencv.core.Point(0.0, 0.0), org.opencv.core.Scalar(0.0))
        val holes = Core.countNonZero(outside)
        box.release(); blob.release(); padded.release(); outside.release(); ffMask.release()
        return holes / (w * h).toDouble()
    }

    fun autoSummary() {
        println()
        println("Dungeon.glyphWhite (a flood) against the islands counted here: $mismatches blobs differ")
        println("frames whose answer the reader as it is now changes: ${changed.size}")
        changed.forEach { println("   $it") }
        println("frames with a passing blob: $withBlob; the answer a cut blob on $firstCut:")
        cutFrames.forEach { println("   $it") }
        fun line(it: Answer) = "   holes %.3f white %.3f fill %.3f dfx %+.4f dfy %+.4f %s %s".format(
            it.holes, it.white, it.fill, it.dfx, it.dfy, if (it.uncut) "uncut" else "CUT  ", it.name)
        val sorted = answers.sortedBy { it.holes }
        println("the answers by their holes, lowest 12:")
        sorted.take(12).forEach { println(line(it)) }
        println("highest 5:")
        sorted.takeLast(5).forEach { println(line(it)) }
        val byWhite = answers.sortedBy { it.white }
        println("the answers by the white in their holes, lowest 12:")
        byWhite.take(12).forEach { println(line(it)) }
        println("highest 5:")
        byWhite.takeLast(5).forEach { println(line(it)) }
        val byPlace = answers.sortedBy { kotlin.math.max(kotlin.math.abs(it.dfx), kotlin.math.abs(it.dfy)) }
        println("the answers by their distance from POS_AUTO (the larger of dfx, dfy), farthest 8:")
        byPlace.takeLast(8).forEach { println("   dfx %+.4f dfy %+.4f holes %.3f %s".format(it.dfx, it.dfy, it.holes, it.name)) }
        val names = listOf("left", "top", "right", "bottom")
        for (k in 0 until 4) {
            val m = margins[k].sorted()
            if (m.isEmpty()) continue
            println("uncut blobs, px from the band's %-6s  min %d  p1 %d  median %d".format(
                names[k], m.first(), m[m.size / 100], m[m.size / 2]))
        }
    }

    // ------------------------------------------------------------------------
    // The Touch To Start bar
    // ------------------------------------------------------------------------

    /**
     * The masks asked beside [Startup.BAR_BLUE]: the one it was until
     * 2026-09-30, and its hue and value bounds moved one at a time.
     */
    val BAR_MASKS: List<Pair<String, Dungeon.Hsv>> = listOf(
        "until 09-30" to Dungeon.Hsv(intArrayOf(95, 60, 60), intArrayOf(120, 255, 255)),
        "V>=90" to Dungeon.Hsv(intArrayOf(95, 60, 90), intArrayOf(120, 255, 255)),
        "V>=100" to Dungeon.Hsv(intArrayOf(95, 60, 100), intArrayOf(120, 255, 255)),
        "V>=110" to Dungeon.Hsv(intArrayOf(95, 60, 110), intArrayOf(120, 255, 255)),
        "H<=125" to Dungeon.Hsv(intArrayOf(95, 60, 60), intArrayOf(125, 255, 255)),
        "H<=122,V>=100" to Dungeon.Hsv(intArrayOf(95, 60, 100), intArrayOf(122, 255, 255)),
        "H<=125,V>=100" to Dungeon.Hsv(intArrayOf(95, 60, 100), intArrayOf(125, 255, 255)),
        "H<=128,V>=100" to Dungeon.Hsv(intArrayOf(95, 60, 100), intArrayOf(128, 255, 255)),
        "H<=130,V>=100" to Dungeon.Hsv(intArrayOf(95, 60, 100), intArrayOf(130, 255, 255)))

    private val answered = LinkedHashMap<String, ArrayList<String>>()

    private fun candidates(img: Mat, colour: Dungeon.Hsv): List<String> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val hsv = Dungeon.hsv(img)
        val mask = Dungeon.inRange(hsv, colour)
        val (count, stats) = Dungeon.components(mask)
        hsv.release(); mask.release()
        val out = ArrayList<String>()
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (h < 3 || w < 10) continue
            val fx = (x + w / 2.0 - x0) / gw
            val fy = (y + h / 2.0 - y0) / gh
            if (!(Startup.BAR_FY[0] <= fy && fy <= Startup.BAR_FY[1])) continue
            if (kotlin.math.abs(fx - 0.5) > Startup.BAR_FX_OFF) continue
            val fw = w / gw.toDouble()
            val aspect = w / h.toDouble()
            val fill = area / (w * h).toDouble()
            val fails = buildList {
                if (!(Startup.BAR_ASPECT[0] <= aspect && aspect <= Startup.BAR_ASPECT[1])) add("aspect")
                if (fill < Startup.BAR_FILL_MIN) add("fill")
                if (!(Startup.BAR_FW[0] <= fw && fw <= Startup.BAR_FW[1])) add("fw")
            }
            out += "fx %s fy %s fw %s fh %s aspect %.2f fill %.3f  %dx%d  %s".format(
                f(fx), f(fy), f(fw), f(h / gh.toDouble()), aspect, fill, w, h,
                if (fails.isEmpty()) "PASSES" else "fails " + fails.joinToString("+"))
        }
        return out
    }

    fun bar(img: Mat, name: String) {
        val lines = ArrayList<String>()
        for ((label, colour) in listOf("as is" to Startup.BAR_BLUE) + BAR_MASKS) {
            val c = candidates(img, colour)
            if (c.any { it.endsWith("PASSES") }) answered.getOrPut(label) { ArrayList() } += name
            c.forEach { lines += "   %-14s %s".format(label, it) }
        }
        if (lines.isEmpty()) return
        println("== $name  ${img.cols()} x ${img.rows()}")
        lines.forEach { println(it) }
        values(img)
    }

    /**
     * V of the pixels with the bar's hue and saturation (H 95 to 130, S 60
     * up, any V), inside the bar's rows (fy 0.8290 to 0.8640, a row inside
     * its edges at 0.8278 and 0.8652) and in the rows just outside them (fy
     * 0.8050 to 0.8250 and 0.8680 to 0.8880), fx 0.28 to 0.68 both.
     */
    private fun values(img: Mat) {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val hsv = Dungeon.hsv(img)
        fun vs(fyA: Double, fyB: Double): IntArray {
            val out = ArrayList<Int>()
            val px = ByteArray(3)
            val ya = maxOf(0, Py.int(y0 + fyA * gh)); val yb = minOf(img.rows(), Py.int(y0 + fyB * gh))
            val xa = maxOf(0, Py.int(x0 + 0.28 * gw)); val xb = minOf(img.cols(), Py.int(x0 + 0.68 * gw))
            for (y in ya until yb) for (x in xa until xb) {
                hsv.get(y, x, px)
                val h = px[0].toInt() and 0xff; val s = px[1].toInt() and 0xff; val v = px[2].toInt() and 0xff
                if (h in 95..130 && s >= 60) out += v
            }
            return out.sorted().toIntArray()
        }
        fun pct(all: IntArray) = if (all.isEmpty()) "none" else all.sortedArray().let { a ->
            "n %d  p1 %d  p5 %d  median %d  p95 %d  p99 %d".format(a.size, a[a.size / 100], a[a.size / 20],
                a[a.size / 2], a[a.size - 1 - a.size / 20], a[a.size - 1 - a.size / 100]) }
        println("   V inside the bar's rows:  ${pct(vs(0.8290, 0.8640))}")
        println("   V in the rows around it:  ${pct(vs(0.8050, 0.8250) + vs(0.8680, 0.8880))}")
        // And the hue of the bar's rows where it is bright enough to be the
        // bar (S 60 up, V 100 up), from blue to magenta (H 90 to 150).
        val hues = ArrayList<Int>()
        val px = ByteArray(3)
        val ya = maxOf(0, Py.int(y0 + 0.8290 * gh)); val yb = minOf(img.rows(), Py.int(y0 + 0.8640 * gh))
        val xa = maxOf(0, Py.int(x0 + 0.28 * gw)); val xb = minOf(img.cols(), Py.int(x0 + 0.68 * gw))
        for (y in ya until yb) for (x in xa until xb) {
            hsv.get(y, x, px)
            val h = px[0].toInt() and 0xff; val s = px[1].toInt() and 0xff; val v = px[2].toInt() and 0xff
            if (h in 90..150 && s >= 60 && v >= 100) hues += h
        }
        println("   H inside the bar's rows (S 60+, V 100+):  ${pct(hues.toIntArray())}")
        hsv.release()
    }

    fun barSummary() {
        println()
        for ((label, frames) in answered) {
            println("$label passes on ${frames.size}: ${frames.joinToString(", ")}")
        }
    }


    // ------------------------------------------------------------------------
    // The title's Menu button (B43)
    // ------------------------------------------------------------------------

    private class Seen(val name: String, val b: Startup.Blob, val fails: List<String>)

    private fun line(b: Startup.Blob) =
        "fx %s fy %s fw %s fh %s aspect %.2f fill %.3f enclosed %.3f white %.3f%s".format(
            f(b.fx), f(b.fy), f(b.fw), f(b.fh), b.aspect, b.fill, b.enclosed, b.white, if (b.cut) " CUT" else "")

    private val menus = ArrayList<Seen>()
    private val menuAnswers = ArrayList<String>()

    private fun menuFails(b: Startup.Blob): List<String> = buildList {
        if (b.cut) add("cut")
        if (kotlin.math.abs(b.fx - Startup.MENU_PLACE[0]) > Startup.MENU_PLACE_TOL ||
            kotlin.math.abs(b.fy - Startup.MENU_PLACE[1]) > Startup.MENU_PLACE_TOL) add("place")
        if (!(Startup.MENU_FW[0] <= b.fw && b.fw <= Startup.MENU_FW[1])) add("fw")
        if (b.fill < Startup.MENU_FILL_MIN) add("fill")
        if (b.white < Startup.MENU_GLYPH_MIN) add("white")
    }

    fun menu(img: Mat, name: String) {
        val found = Startup.menuBlobs(img)
        if (Startup.menuButton(img) != null) menuAnswers += name
        if (found.isEmpty()) return
        println("== $name  ${img.cols()} x ${img.rows()}  menuButton ${Startup.menuButton(img)?.let { "fx ${f(it.fx)} fy ${f(it.fy)}" } ?: "null"}  titleBar ${if (Startup.titleBar(img) != null) "yes" else "null"}")
        for (b in found) {
            val fails = menuFails(b)
            println("   ${line(b)}  ${if (fails.isEmpty()) "MENU" else "fails " + fails.joinToString("+")}")
            menus += Seen(name, b, fails)
        }
    }

    fun menuSummary() {
        println()
        println("menuButton answers on ${menuAnswers.size}:")
        menuAnswers.forEach { println("   $it") }
        val menu = menus.filter { it.fails.isEmpty() }
        val rest = menus.filter { it.fails.isNotEmpty() }
        println("the Menu's numbers, ${menu.size} blobs: " +
            "fx %s..%s fy %s..%s fw %s..%s aspect %.2f..%.2f fill %.3f..%.3f white %.3f..%.3f".format(
                f(menu.minOf { it.b.fx }), f(menu.maxOf { it.b.fx }), f(menu.minOf { it.b.fy }), f(menu.maxOf { it.b.fy }),
                f(menu.minOf { it.b.fw }), f(menu.maxOf { it.b.fw }), menu.minOf { it.b.aspect }, menu.maxOf { it.b.aspect },
                menu.minOf { it.b.fill }, menu.maxOf { it.b.fill }, menu.minOf { it.b.white }, menu.maxOf { it.b.white }))
        fun dist(s: Seen) = kotlin.math.max(kotlin.math.abs(s.b.fx - Startup.MENU_PLACE[0]),
                                            kotlin.math.abs(s.b.fy - Startup.MENU_PLACE[1]))
        println("every other blob (${rest.size}), the nearest 12 to the place (the larger of dfx, dfy):")
        rest.sortedBy { dist(it) }.take(12).forEach { println("   d %.4f  %s  %s  %s".format(dist(it), line(it.b), it.fails, it.name)) }
        println("the other blobs within 0.03 of the place: fw, fill and white of each, the highest 25 by fill:")
        rest.filter { dist(it) <= 0.03 }.sortedByDescending { it.b.fill }.take(25)
            .forEach { println("   ${line(it.b)}  ${it.fails}  ${it.name}") }
        println("the other blobs failing one test alone:")
        rest.filter { it.fails.size == 1 }.forEach { println("   ${it.fails}  ${line(it.b)}  ${it.name}") }
        println("the most white any other blob encloses (5):")
        rest.sortedByDescending { it.b.white }.take(5).forEach { println("   ${line(it.b)}  ${it.name}") }
    }

    // ------------------------------------------------------------------------
    // The day's announcements: OK and "Don't show again today" (B44)
    // ------------------------------------------------------------------------

    private val boxes = ArrayList<Seen>()
    private val pairs = ArrayList<String>()
    private val popupAnswers = ArrayList<String>()

    private fun boxFails(b: Startup.Blob): List<String> = buildList {
        if (b.cut) add("cut")
        if (!(Startup.ANNOUNCE_BOX_SIZE[0] <= b.fw && b.fw <= Startup.ANNOUNCE_BOX_SIZE[1])) add("fw")
        if (b.fill < Startup.ANNOUNCE_BOX_FILL_MIN) add("fill")
        if (b.enclosed < Startup.ANNOUNCE_BOX_ENCLOSED_MIN) add("enclosed")
        if (b.white > Startup.ANNOUNCE_BOX_WHITE_MAX) add("white")
    }

    fun popup(img: Mat, name: String) {
        val (found, oks) = Startup.announcementBlobs(img)
        val answer = Startup.announcement(img)
        if (answer != null) popupAnswers += name
        if (found.isEmpty()) return
        println("== $name  ${img.cols()} x ${img.rows()}  announcement ${answer?.let { "fx ${f(it.fx)} fy ${f(it.fy)} fw ${f(it.fw)}" } ?: "null"}")
        for (b in found) {
            val fails = boxFails(b)
            println("   box  ${line(b)}  ${if (fails.isEmpty()) "BOX" else "fails " + fails.joinToString("+")}")
            boxes += Seen(name, b, fails)
            if (fails.size <= 1) {
                for (ok in oks) {
                    pairs += "%-9s dx %s dy %s  ok fw %s fh %s%s  %s".format(if (fails.isEmpty()) "box" else fails.toString(),
                        f(ok.fx - b.fx), f(ok.fy - b.fy), f(ok.fw), f(ok.fh), if (ok.cut) " CUT" else "", name)
                }
            }
        }
        oks.forEach { println("   ok   ${line(it)}") }
    }

    fun popupSummary() {
        println()
        println("announcement answers on ${popupAnswers.size}:")
        popupAnswers.forEach { println("   $it") }
        val box = boxes.filter { it.fails.isEmpty() }
        val rest = boxes.filter { it.fails.isNotEmpty() }
        println("the boxes, ${box.size}:")
        box.forEach { println("   ${line(it.b)}  ${it.name}") }
        println("blobs failing one test alone (${rest.count { it.fails.size == 1 }}):")
        rest.filter { it.fails.size == 1 }.sortedBy { it.fails.first() }.forEach { println("   ${it.fails}  ${line(it.b)}  ${it.name}") }
        for (test in listOf("fw", "fill", "enclosed", "white")) {
            // The other blobs that pass every test but this one and at most
            // one more, nearest the boxes' value on this test first.
            val value: (Startup.Blob) -> Double = when (test) {
                "fw" -> { b -> b.fw }; "fill" -> { b -> b.fill }
                "enclosed" -> { b -> b.enclosed }; else -> { b -> b.white }
            }
            val mid = box.map { value(it.b) }.average()
            println("on $test (the boxes ${"%.4f".format(box.minOf { value(it.b) })}..${"%.4f".format(box.maxOf { value(it.b) })}), " +
                "the nearest of the blobs that fail it and at most one other test:")
            rest.filter { test in it.fails && it.fails.size <= 2 }
                .sortedBy { kotlin.math.abs(value(it.b) - mid) }.take(10)
                .forEach { println("   ${"%.4f".format(value(it.b))}  ${it.fails}  ${line(it.b)}  ${it.name}") }
        }
        println("the OKs beside the boxes, and beside the blobs failing one test (dx, dy from the box):")
        pairs.forEach { println("   $it") }
    }

    // ------------------------------------------------------------------------
    // The news under the director: its price per round, and one window to the next (B50)
    // ------------------------------------------------------------------------

    private class Cost(val name: String, val screen: String, val announceMs: Double, val classifyMs: Double)
    private val costs = ArrayList<Cost>()
    private var previous: Pair<String, Mat>? = null

    private fun millis(f: () -> Unit): Double {
        // The least of three: the price of the reading, not of a collector
        // that happened to run during one of them.
        var best = Double.MAX_VALUE
        repeat(3) {
            val t0 = System.nanoTime()
            f()
            best = minOf(best, (System.nanoTime() - t0) / 1e6)
        }
        return best
    }

    fun news(img: Mat, name: String) {
        val screen = Director.classify(img).screen
        val announce = millis { Startup.announcement(img) }
        val classify = millis { Director.classify(img) }
        costs += Cost(name, screen, announce, classify)
        val answer = Startup.announcement(img)
        previous?.let { (before, mat) ->
            val moved = Director.motion(mat, img)
            val inside = Director.windowMotion(mat, img)
            println("   motion %s -> %s: whole %s, inside the windows (Director.RESET_WINDOW_CROP) %s".format(
                before, name, moved?.let { "%.4f".format(it) } ?: "not comparable",
                inside?.let { "%.4f".format(it) } ?: "not comparable"))
            mat.release()
        }
        println("== %s  %d x %d  classify %s  announcement %s  %.1f ms (classify %.1f ms)".format(
            name, img.cols(), img.rows(), screen,
            answer?.let { "fx ${f(it.fx)} fy ${f(it.fy)}" } ?: "null", announce, classify))
        previous = name to img
    }

    // ------------------------------------------------------------------------
    // The sale window: two yellow rims across the canvas (B72)
    // ------------------------------------------------------------------------

    /** The yellow measured with: wider than any rim, so that the rims' own colour is what the numbers say. */
    val MEASURE_YELLOW = Dungeon.Hsv(intArrayOf(10, 100, 150), intArrayOf(40, 255, 255))

    private class Run(val name: String, val line: Startup.Line, val hsv: String)
    private val runs = ArrayList<Run>()
    private val runPairs = ArrayList<String>()
    private val saleAnswers = ArrayList<String>()

    /** Median H, S and V of the [colour] pixels in the rows of [line], over the canvas's columns. */
    private fun medianHsv(img: Mat, colour: Dungeon.Hsv, line: Startup.Line): String {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, Startup.SALE_ANCHOR)
        val hsv = Dungeon.hsv(img)
        val left = maxOf(0, Py.int(x0 + Dungeon.GAME_IN_WINDOW[0] * gw))
        val right = minOf(img.cols(), Py.int(x0 + (Dungeon.GAME_IN_WINDOW[0] + Dungeon.GAME_IN_WINDOW[2]) * gw))
        val ya = maxOf(0, Py.roundInt(y0 + line.fy0 * gh)); val yb = minOf(img.rows(), Py.roundInt(y0 + line.fy1 * gh))
        val hs = ArrayList<Int>(); val ss = ArrayList<Int>(); val vs = ArrayList<Int>()
        val px = ByteArray(3)
        val lo = DoubleArray(3).also { colour.lo.`val`.copyInto(it, 0, 0, 3) }
        val hi = DoubleArray(3).also { colour.hi.`val`.copyInto(it, 0, 0, 3) }
        for (y in ya until yb) for (x in left until right) {
            hsv.get(y, x, px)
            val h = px[0].toInt() and 0xff; val s = px[1].toInt() and 0xff; val v = px[2].toInt() and 0xff
            if (h >= lo[0] && h <= hi[0] && s >= lo[1] && s <= hi[1] && v >= lo[2] && v <= hi[2]) { hs += h; ss += s; vs += v }
        }
        hsv.release()
        fun med(a: List<Int>) = if (a.isEmpty()) -1 else a.sorted()[a.size / 2]
        fun lo5(a: List<Int>) = if (a.isEmpty()) -1 else a.sorted()[a.size / 20]
        return "H %d S %d (p5 %d) V %d (p5 %d)".format(med(hs), med(ss), lo5(ss), med(vs), lo5(vs))
    }

    fun sale(img: Mat, name: String) {
        val colour = if (System.getProperty("sale.wide") != null) MEASURE_YELLOW else Startup.SALE_YELLOW
        val found = Startup.lines(img, colour, 0.5)
        val answer = Startup.saleWindow(img)
        saleMs += millis { Startup.saleWindow(img) }
        if (answer != null) saleAnswers += name
        if (found.isEmpty()) return
        println("== $name  ${img.cols()} x ${img.rows()}  saleWindow ${answer?.let { "fy0 ${f(it.fy0)} fy1 ${f(it.fy1)}" } ?: "null"}")
        for (l in found) {
            val c = medianHsv(img, colour, l)
            println("   fy %s..%s rows %d fh %s share %.3f  %s".format(f(l.fy0), f(l.fy1), l.rows, f(l.fh), l.share, c))
            runs += Run(name, l, c)
        }
        val wide = found.filter { it.share >= 0.80 }
        for ((i, a) in wide.withIndex()) for (b in wide.drop(i + 1)) {
            runPairs += "gap %s  shares %.3f %.3f  rows %d %d  fy %s %s  %s".format(
                f(b.fy0 - a.fy0), a.share, b.share, a.rows, b.rows, f(a.fy0), f(b.fy0), name)
        }
    }

    private val saleMs = ArrayList<Double>()
    private val helpMs = ArrayList<Double>()

    private fun priceLine(what: String, ms: List<Double>) {
        val s = ms.sorted()
        if (s.isEmpty()) return
        println("%s costs, over %d pictures: median %.1f ms, p95 %.1f, max %.1f".format(
            what, s.size, s[s.size / 2], s[((s.size - 1) * 0.95).toInt()], s.last()))
    }

    fun saleSummary() {
        println()
        priceLine("saleWindow", saleMs)
        println("saleWindow answers on ${saleAnswers.size}:")
        saleAnswers.forEach { println("   $it") }
        println("every run of the yellow covering half the canvas or more, by share, the widest 40:")
        runs.sortedByDescending { it.line.share }.take(40).forEach {
            println("   share %.3f rows %3d fh %s fy %s  %s  %s".format(it.line.share, it.line.rows, f(it.line.fh),
                f(it.line.fy0), it.hsv, it.name))
        }
        println("the runs by their thickness (fh) among those of share 0.90 or more:")
        runs.filter { it.line.share >= 0.90 }.sortedBy { it.line.fh }.forEach {
            println("   fh %s rows %3d share %.3f fy %s  %s".format(f(it.line.fh), it.line.rows, it.line.share, f(it.line.fy0), it.name))
        }
        println("pairs of runs of share 0.80 or more on one picture:")
        runPairs.forEach { println("   $it") }
    }

    // ------------------------------------------------------------------------
    // The Help tutorial: a Next beside a Back of its size, the page counter above (B73)
    // ------------------------------------------------------------------------

    private val helpPairs = ArrayList<String>()
    private val helpAnswers = ArrayList<String>()

    fun help(img: Mat, name: String) {
        val (nexts, backs, counters) = Startup.helpBlobs(img)
        val answer = Startup.helpWindow(img)
        helpMs += millis { Startup.helpWindow(img) }
        if (answer != null) helpAnswers += name
        if (nexts.isEmpty() || backs.isEmpty()) return
        println("== $name  ${img.cols()} x ${img.rows()}  helpWindow ${answer?.let { "fx ${f(it.fx)} fy ${f(it.fy)}" } ?: "null"}")
        nexts.forEach { println("   next    ${line(it)}") }
        backs.forEach { println("   back    ${line(it)}") }
        counters.forEach { println("   counter ${line(it)}") }
        for (next in nexts) for (back in backs) {
            val dx = next.fx - back.fx
            if (dx <= 0) continue
            val dy = next.fy - back.fy
            val counter = counters.minByOrNull { kotlin.math.abs(it.fx - (next.fx + back.fx) / 2) + kotlin.math.abs(next.fy - it.fy - 0.07) }
            helpPairs += "dx %s dy %+.4f  w %.3f h %.3f  fills %.3f %.3f  next fw %s fy %s%s  counter %s  %s  %s".format(
                f(dx), dy, back.fw / next.fw, back.fh / next.fh, next.fill, back.fill, f(next.fw), f(next.fy),
                if (next.cut || back.cut) " CUT" else "",
                counter?.let { "dx %+.4f dy %s fill %.3f".format(it.fx - (next.fx + back.fx) / 2, f(next.fy - it.fy), it.fill) } ?: "none",
                if (Startup.isHelpPair(next, back)) "PAIR" else "-", name)
        }
    }

    fun helpSummary() {
        println()
        priceLine("helpWindow", helpMs)
        println("helpWindow answers on ${helpAnswers.size}:")
        helpAnswers.forEach { println("   $it") }
        println("every Next with a Back left of it (${helpPairs.size}):")
        helpPairs.sortedBy { kotlin.math.abs(it.substringAfter("dy ").substringBefore(" ").toDouble()) }
            .forEach { println("   $it") }
    }

    // ------------------------------------------------------------------------
    // The gear window: Sell (pink) beside Equip (blue), the cards above (K2)
    // ------------------------------------------------------------------------

    /** Every pink of a button: the hue band of the pink prompt's Cancel (Dungeon.PARTY_PINK_HUE), wide on purpose. */
    private val measurePink = Dungeon.Hsv(intArrayOf(135, 100, 150), intArrayOf(175, 255, 255))
    private val gearBand = doubleArrayOf(0.0, 1.0, 0.40, 1.05)
    private val gearFw = doubleArrayOf(0.08, 0.48)
    private val gearAspect = doubleArrayOf(1.3, 6.0)
    private val gearPairs = ArrayList<String>()
    private val gearAnswers = ArrayList<String>()
    private val gearMs = ArrayList<Double>()

    fun gear(img: Mat, name: String) {
        val answer = Startup.gearWindow(img)
        gearMs += millis { Startup.gearWindow(img) }
        if (answer != null) gearAnswers += name
        for (anchor in listOf(Dungeon.Anchor.MIDDLE, Dungeon.Anchor.BOTTOM, Dungeon.Anchor.TOP)) {
            val rect = Dungeon.gameRect(img, anchor)
            val pinks = Startup.blobs(img, rect, measurePink, gearBand, gearFw, gearAspect)
            if (pinks.isEmpty()) { if (anchor == Dungeon.Anchor.MIDDLE) return else continue }
            val blues = Startup.blobs(img, rect, Dungeon.BLUE, gearBand, gearFw, gearAspect)
            if (anchor == Dungeon.Anchor.MIDDLE) {
                println("== $name  ${img.cols()} x ${img.rows()}  gearWindow ${answer?.let { "fy ${f(it.fy)} sell ${f(it.sellFx)} equip ${f(it.equipFx)} fw ${f(it.fw)}" } ?: "null"}")
                pinks.forEach { println("   pink  ${line(it)}") }
                blues.forEach { println("   blue  ${line(it)}") }
            }
            for (p in pinks) for (b in blues) {
                val dx = b.fx - p.fx
                if (dx <= 0) continue
                val dy = b.fy - p.fy
                if (kotlin.math.abs(dy) > 0.05) continue
                val gap = (b.fx - b.fw / 2) - (p.fx + p.fw / 2)
                val line = "%-6s dx/fw %.3f gap/fw %.3f dy %+.4f  w %.3f h %.3f  fills %.3f %.3f  white %.3f %.3f  pink fx %s fy %s fw %s fh %s%s  %s".format(
                    anchor.name, dx / b.fw, gap / b.fw, dy, p.fw / b.fw, p.fh / b.fh, p.fill, b.fill, p.white, b.white,
                    f(p.fx), f(p.fy), f(p.fw), f(p.fh), if (p.cut || b.cut) " CUT" else "", name)
                if (anchor == Dungeon.Anchor.MIDDLE) gearPairs += line
                println("   pair $line")
            }
        }
    }

    fun gearSummary() {
        println()
        priceLine("gearWindow", gearMs)
        println("gearWindow answers on ${gearAnswers.size}:")
        gearAnswers.forEach { println("   $it") }
        println("every pink with a blue right of it on its row, in the middle's rectangle (${gearPairs.size}), by |w-1|:")
        gearPairs.sortedBy { kotlin.math.abs(it.substringAfter(" w ").substringBefore(" ").toDouble() - 1.0) }
            .forEach { println("   $it") }
    }

    fun newsSummary() {
        previous?.second?.release()
        fun pct(xs: List<Double>, p: Double) = xs.sorted().let { if (it.isEmpty()) 0.0 else it[((it.size - 1) * p).toInt()] }
        fun line(label: String, cs: List<Cost>) {
            if (cs.isEmpty()) return
            val a = cs.map { it.announceMs }
            val c = cs.map { it.classifyMs }
            println("%-34s %4d  announcement median %.1f p95 %.1f max %.1f ms   classify median %.1f p95 %.1f ms".format(
                label, cs.size, pct(a, 0.5), pct(a, 0.95), a.maxOrNull() ?: 0.0, pct(c, 0.5), pct(c, 0.95)))
        }
        println()
        line("every picture", costs)
        line("not main (classify asks further)", costs.filter { it.screen != Director.MAIN })
        for ((screen, cs) in costs.groupBy { it.screen }.toSortedMap()) line("  $screen", cs)
        println("the dearest ten:")
        costs.sortedByDescending { it.announceMs }.take(10).forEach {
            println("   %.1f ms  %s  %s".format(it.announceMs, it.screen, it.name))
        }
    }
}
