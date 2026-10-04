package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * The objects of the board's bottom row (PLAN_WORLD_SEARCH_FORMATE.md F10),
 * `gradlew :core:boardProbe --no-daemon --args=objects`.
 *
 * Until F10 a power-up in the bottom row read, on every frame of the corpus,
 * but close to its bar (0.62 less the row's 0.06). This measures how close,
 * against what, and why: every power-up template's score on every coloured
 * cell of every board and on every cell of the bottom row, whole and cut to
 * its upper rows (the cut `readGrid` asks of the pyramid there since F2), the
 * same object in the upper rows of the same frame, where on its cell each one
 * sits, and how each third of the template matches there -- the rows the
 * ledge covers are the rows whose third stops matching.
 *
 * The corpus's bottom rows hold six power-ups, all SP Training Chips, so the
 * other kinds are laid under the ledge by hand: every power-up and every
 * coloured cell that is none of rows 0-3, with the ledge of each plain bottom
 * cell of its frame pasted over it ([ledgeMask], crops in `ledge/`). Three
 * labels come from looking at the crops, not from a reader: a chip behind the
 * figure's head or wings ([Synth.hidden], which readGrid reads as nothing and
 * which is therefore no "none"), a chip on a highlighted cell ([LIGHT]), and a
 * pyramid in the cell under an object, whose tip covers it once already.
 *
 * Then every way of asking the bottom row side by side (sections 5, 8, 8b,
 * 8c): the real and the laid chips' lowest score, how many take another name,
 * and the highest score of anything that is none; and the orange template's
 * left columns cropped in every row (section 10). `whole (today)` is the rule
 * before F10; `cut 0.50, orange~17` is the rule since. Crops of the cells go
 * to `core/build/boardProbe_cells/`, for the eye; nothing else is written.
 *
 * Nothing here is a reader: the scores are `Vision.readGrid`'s arithmetic
 * (the same patch, the same scaling, `TM_CCOEFF_NORMED`), asked of more
 * templates and more parts of them than `readGrid` asks.
 */
object BoardObjects {

    /** The cuts tried: the share of a template's rows kept, from the top. */
    val CUTS = listOf(1.0, 0.9, 0.85, 0.8, 0.75, 0.7, 2.0 / 3.0, 0.6, 0.55, 0.5, 0.45, 0.4)

    fun cutName(f: Double) = if (abs(f - 2.0 / 3.0) < 1e-9) "2/3" else String.format(Locale.ROOT, "%.2f", f)

    private val cutCache = HashMap<String, Mat>()

    fun cut(name: String, tpl: Mat, f: Double): Mat {
        if (f >= 1.0) return tpl
        return cutCache.getOrPut("$name@$f") {
            tpl.submat(0, Py.roundInt(tpl.rows() * f), 0, tpl.cols()).clone()
        }
    }

    /** `matchTemplate` max and where: (value, x, y) in the patch. */
    fun matchLoc(patch: Mat, tpl: Mat): Triple<Double, Int, Int>? {
        if (tpl.rows() > patch.rows() || tpl.cols() > patch.cols()) return null
        val res = Mat()
        Imgproc.matchTemplate(patch, tpl, res, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(res)
        res.release()
        return Triple(mm.maxVal, mm.maxLoc.x.toInt(), mm.maxLoc.y.toInt())
    }

    /** `Vision.hasObjectColour`'s share, in per cent, rather than its answer. */
    fun colourShare(v: Vision, img: Mat, calib: Calib, r: Int, c: Int): Double {
        val patch = v.searchPatch(img, calib, r, c) ?: return 0.0
        val hsv = Cv.hsv(patch)
        val low = Mat(); val high = Mat()
        Core.inRange(hsv, Scalar(0.0, 111.0, 111.0), Scalar(79.0, 255.0, 255.0), low)
        Core.inRange(hsv, Scalar(141.0, 111.0, 111.0), Scalar(255.0, 255.0, 255.0), high)
        Core.bitwise_or(low, high, low)
        val share = Core.countNonZero(low).toDouble() / (low.rows().toLong() * low.cols())
        hsv.release(); low.release(); high.release()
        return 100.0 * share
    }

    /** The mean hue of the strongly coloured pixels of a cell (the prefilter's mask), on a circle of 180. */
    fun hueOf(v: Vision, img: Mat, calib: Calib, r: Int, c: Int): Double? {
        val patch = v.searchPatch(img, calib, r, c) ?: return null
        val hsv = Cv.hsv(patch)
        val buf = ByteArray(hsv.rows() * hsv.cols() * 3); hsv.get(0, 0, buf)
        var sx = 0.0; var sy = 0.0; var n = 0
        for (i in 0 until hsv.rows() * hsv.cols()) {
            val h = buf[i * 3].toInt() and 0xFF; val s = buf[i * 3 + 1].toInt() and 0xFF; val vv = buf[i * 3 + 2].toInt() and 0xFF
            if (s > 110 && vv > 110 && (h < 80 || h > 140)) {
                val a = h * Math.PI / 90.0
                sx += Math.cos(a); sy += Math.sin(a); n += 1
            }
        }
        hsv.release()
        if (n == 0) return null
        var a = Math.atan2(sy, sx) * 90.0 / Math.PI
        if (a < 0) a += 180.0
        return a
    }

    class Cell(
        val frame: String, val w: Int, val h: Int, val cellW: Double, val cellH: Double,
        val r: Int, val c: Int, val share: Double, val hue: Double?,
        /** What readGrid said, with the figure cell as "figure". */
        val read: String?,
        val crossOfFigure: Boolean,
        /** Score per (template, cut). */
        val scores: Map<Pair<String, Double>, Double>,
        /** The whole template's best place in the patch, per template: y from the cell's top in cell heights. */
        val placeY: Map<String, Double>,
        val placeX: Map<String, Double>,
        /** Thirds of the whole template at the place of its upper two thirds: top, middle, bottom. */
        val thirds: Map<String, List<Double>>,
        /** What stands in the cell under this one: its reading, "figure", or "ledge" under the bottom row. */
        val below: String?,
    ) {
        val name get() = frame.substringAfterLast('/')
        fun s(t: String, f: Double = 1.0) = scores[t to f]
        val bottom get() = r == Vision.ROWS - 1
        val isPowerup get() = read in Vision.POWERUPS
    }

    /**
     * The orange template without its left [x0] columns: the template carries a
     * piece of a neighbouring object and dark floor there, left of the chip
     * (x 17 to 48 of 50). Named `ticket_orange~x0`, so no naming loop over
     * [Vision.POWERUPS] ever meets it.
     */
    val ORANGE_CROPS = listOf(6, 10, 13, 16, 17, 18, 20)
    private val cropCache = HashMap<Int, Mat>()
    fun orangeCrops(all: Map<String, Mat>): List<Pair<String, Mat>> {
        val o = all["ticket_orange"] ?: return emptyList()
        return ORANGE_CROPS.map { x0 -> "ticket_orange~$x0" to cropCache.getOrPut(x0) { o.submat(0, o.rows(), x0, o.cols()).clone() } }
    }

    fun measure(v: Vision, img: Mat, calib: Calib, rel: String, names: List<List<String?>>, figure: Figure?,
                cellsDir: File?): List<Cell> {
        val all = v.loadTemplates()
        val templates = Vision.POWERUPS.mapNotNull { n -> all[n]?.let { n to it } } +
            listOfNotNull(all["arrow"]?.let { "arrow" to it }, all["pyramid"]?.let { "pyramid" to it }) + orangeCrops(all)
        val out = ArrayList<Cell>()
        for (r in 0 until Vision.ROWS) for (c in 0 until Vision.COLS) {
            val share = colourShare(v, img, calib, r, c)
            val bottom = r == Vision.ROWS - 1
            val isFig = figure != null && figure.row == r && figure.col == c
            val read = if (isFig) "figure" else names[r][c]
            // Every coloured cell, and every bottom cell whatever its colour.
            if (!bottom && share < Vision.OBJECT_COLOUR_MIN && read == null) continue
            if (!bottom && read == "pyramid") continue
            val patch = v.searchPatch(img, calib, r, c) ?: continue
            // searchPatch's origin, so a place in the patch is a place on the cell.
            val (x, y, cw, ch) = v.cellRect(calib, r, c)
            val y1 = maxOf(0, y - Py.int(0.14 * ch))
            val x1 = maxOf(0, x - Py.int(0.12 * cw))
            val scores = HashMap<Pair<String, Double>, Double>()
            val placeY = HashMap<String, Double>(); val placeX = HashMap<String, Double>()
            val thirds = HashMap<String, List<Double>>()
            for ((tname, tpl) in templates) {
                for (f in CUTS) {
                    val t = BoardProbe.scaledAny("$tname@$f", cut(tname, tpl, f), calib.cellW)
                    val m = matchLoc(patch, t) ?: continue
                    scores[tname to f] = m.first
                    if (f == 1.0) {
                        placeY[tname] = (y1 + m.third - y) / calib.cellH
                        placeX[tname] = (x1 + m.second - x) / calib.cellW
                    }
                    if (abs(f - 2.0 / 3.0) < 1e-9) {
                        // The thirds of the whole template, each on its own,
                        // at the place the upper two thirds chose.
                        val whole = BoardProbe.scaledAny("$tname@1.0", tpl, calib.cellW)
                        val th = whole.rows() / 3
                        val bands = listOf(0 to th, th to 2 * th, 2 * th to whole.rows())
                        thirds[tname] = bands.map { (a, b) ->
                            val band = whole.submat(a, b, 0, whole.cols())
                            val ya = m.third + a
                            val sub = if (ya + (b - a) <= patch.rows() && m.second + whole.cols() <= patch.cols())
                                patch.submat(ya, ya + (b - a), m.second, m.second + whole.cols()) else null
                            if (sub == null) Double.NaN else {
                                val res = Mat()
                                Imgproc.matchTemplate(sub, band, res, Imgproc.TM_CCOEFF_NORMED)
                                val value = Core.minMaxLoc(res).maxVal
                                res.release(); value
                            }
                        }
                    }
                }
            }
            val cross = figure != null && abs(figure.row - r) + abs(figure.col - c) == 1
            val below = if (bottom) "ledge" else
                if (figure != null && figure.row == r + 1 && figure.col == c) "figure" else names[r + 1][c]
            val cell = Cell(rel, img.cols(), img.rows(), calib.cellW, calib.cellH, r, c, share,
                hueOf(v, img, calib, r, c), read, cross, scores, placeY, placeX, thirds, below)
            out += cell
            if (cellsDir != null && (bottom && (share >= Vision.OBJECT_COLOUR_MIN || read != null) || (!bottom && read in Vision.POWERUPS) ||
                    (!bottom && read != "figure" && share >= Vision.OBJECT_COLOUR_MIN))) {
                cellsDir.mkdirs()
                val tag = rel.substringAfterLast('/').removeSuffix(".png").replace(' ', '_')
                // "?" is no character of a Windows file name.
                Imgcodecs.imwrite(File(cellsDir, "r${r}c${c}_${(read ?: "none").replace("?", "unknown")}__$tag.png").path, patch)
            }
        }
        return out
    }

    // ------------------------------------------------------------------------
    // The ledge laid over the upper rows' objects
    // ------------------------------------------------------------------------

    /**
     * The ledge (and the meter label's letters) in a bottom cell's patch: the
     * ledge's light faces are hue 85 to 107, saturation under 180, value over
     * 140 (the dark floor is saturation ~245 at value ~100, a walked cell
     * ~230 at ~215, a pyramid hue 108 to 120), the label's letters white. The
     * ledge is a wall, so from its top edge down every row of that column is
     * ledge. Only the lower 60 % of the patch is asked.
     */
    fun ledgeMask(b: Mat): Mat {
        val hsv = Cv.hsv(b)
        val rows = b.rows(); val cols = b.cols()
        val buf = ByteArray(rows * cols * 3); hsv.get(0, 0, buf); hsv.release()
        val mask = Mat.zeros(rows, cols, org.opencv.core.CvType.CV_8U)
        val out = ByteArray(rows * cols)
        val from = (rows * 0.4).toInt()
        for (x in 0 until cols) {
            var top = -1
            for (y in from until rows) {
                val i = (y * cols + x) * 3
                val h = buf[i].toInt() and 0xFF; val s = buf[i + 1].toInt() and 0xFF; val v = buf[i + 2].toInt() and 0xFF
                if ((h in 85..107 && s < 180 && v > 140) || (s < 40 && v > 200)) { top = y; break }
            }
            if (top >= 0) for (y in top until rows) out[y * cols + x] = 255.toByte()
        }
        mask.put(0, 0, out)
        return mask
    }

    /** The ledge's highest point in a bottom cell, in cell heights from the cell's top (the mask's first row). */
    fun ledgeTop(v: Vision, img: Mat, calib: Calib, c: Int): Double? {
        val last = Vision.ROWS - 1
        val b = v.searchPatch(img, calib, last, c) ?: return null
        val mask = ledgeMask(b)
        val rows = mask.rows(); val cols = mask.cols()
        val buf = ByteArray(rows * cols); mask.get(0, 0, buf); mask.release()
        for (y in 0 until rows) for (x in 0 until cols) if (buf[y * cols + x] != 0.toByte()) {
            val (_, cy, _, ch) = v.cellRect(calib, last, c)
            val y1 = maxOf(0, cy - Py.int(0.14 * ch))
            return (y1 + y - cy) / calib.cellH
        }
        return null
    }

    class Synth(val frame: String, val r: Int, val c: Int, val kind: String, val fromCol: Int, val covered: Double,
                val scores: Map<Pair<String, Double>, Double>, val below: String?) {
        fun s(t: String, f: Double = 1.0) = scores[t to f]
        val name get() = frame.substringAfterLast('/')
        /** A chip behind the figure's head or wings, which readGrid does not read: looked at, not a thing that is none. */
        val hidden get() = r == 0 && c == 1 && (name.endsWith("220458.png") || TOUR.containsMatchIn(name))
        /** A chip on a highlighted (walkable) cell, light blue under it: looked at. */
        val light get() = kind == "ticket_orange" && LIGHT.any { (n, rc) -> name.endsWith(n) && rc == (r to c) }
        /** A pyramid in the cell under it pokes its tip over the object's lower part: covered twice once the ledge is on. */
        val twice get() = below == "pyramid"
    }

    /** The format tour of 2026-09-26 23:00 (one scene; the chip behind the figure stands at r0c1 on every frame). */
    val TOUR = Regex("""^board_.*_2[23]\d{4}\.png$""")
    val LIGHT = listOf("open_the_Digimon_page_191213.png" to (2 to 2), "220510.png" to (3 to 2), "220529.png" to (3 to 1))

    /**
     * Every upper-row cell of [kinds] (a power-up readGrid read, or a coloured
     * cell that is none) with the ledge of every plain bottom cell of the same
     * frame laid over it -- the object as it would stand in the bottom row, for
     * the kinds the corpus has in no bottom row.
     */
    fun synthetic(v: Vision, img: Mat, calib: Calib, rel: String, names: List<List<String?>>, figure: Figure?,
                  dir: File?): List<Synth> {
        val all = v.loadTemplates()
        val templates = Vision.POWERUPS.mapNotNull { n -> all[n]?.let { n to it } } +
            listOfNotNull(all["arrow"]?.let { "arrow" to it }) + orangeCrops(all)
        val last = Vision.ROWS - 1
        val plain = (0 until Vision.COLS).filter { c ->
            names[last][c] == null && colourShare(v, img, calib, last, c) < Vision.OBJECT_COLOUR_MIN &&
                !(figure != null && figure.row == last && figure.col == c)
        }
        if (plain.isEmpty()) return emptyList()
        val out = ArrayList<Synth>()
        for (r in 0 until last) for (c in 0 until Vision.COLS) {
            val isFig = figure != null && figure.row == r && figure.col == c
            val read = names[r][c]
            val kind = when {
                isFig -> continue
                read in Vision.POWERUPS -> read!!
                read == "pyramid" -> continue
                colourShare(v, img, calib, r, c) >= Vision.OBJECT_COLOUR_MIN -> if (read == "?") "?" else "coloured, none"
                else -> continue
            }
            val p = v.searchPatch(img, calib, r, c) ?: continue
            for (bc in plain) {
                val b = v.searchPatch(img, calib, last, bc) ?: continue
                if (b.rows() != p.rows() || b.cols() != p.cols()) continue
                val mask = ledgeMask(b)
                val syn = p.clone()
                b.copyTo(syn, mask)
                val covered = Core.countNonZero(mask).toDouble() / (mask.rows() * mask.cols())
                val scores = HashMap<Pair<String, Double>, Double>()
                for ((tname, tpl) in templates) for (fc in CUTS) {
                    val t = BoardProbe.scaledAny("$tname@$fc", cut(tname, tpl, fc), calib.cellW)
                    matchLoc(syn, t)?.let { scores[tname to fc] = it.first }
                }
                val below = if (figure != null && figure.row == r + 1 && figure.col == c) "figure" else names[r + 1][c]
                out += Synth(rel, r, c, kind, bc, covered, scores, below)
                if (dir != null && kind in Vision.POWERUPS && bc == plain.first()) {
                    dir.mkdirs()
                    val tag = rel.substringAfterLast('/').removeSuffix(".png").replace(' ', '_')
                    Imgcodecs.imwrite(File(dir, "r${r}c${c}_${kind}_ledge_c${bc}__$tag.png").path, syn)
                }
                mask.release(); syn.release()
            }
        }
        return out
    }

    fun reportSynthetic(syn: List<Synth>, cells: List<Cell>, log: (String) -> Unit) {
        val pu = Vision.POWERUPS
        log("")
        log("7. the ledge laid over the upper rows: every power-up (and every coloured cell that is none) of rows 0-3, with the")
        log("   ledge of each plain bottom cell of its frame pasted over it (ledgeMask); per kind the lowest own score and how many")
        log("   composites take another name -- named by the cut itself (`cut`) or by the whole templates (`whole`)")
        val kinds = pu.filter { k -> syn.any { it.kind == k } }
        log("   covered share of the patch: %s .. %s".format(f(syn.minOfOrNull { it.covered }, 2), f(syn.maxOfOrNull { it.covered }, 2)))
        for (k in kinds) {
            val ss = syn.filter { it.kind == k }
            log("   %-14s %d composites of %d cells".format(k, ss.size, ss.map { it.frame to (it.r to it.c) }.toSet().size))
            for (fc in CUTS) {
                val own = ss.mapNotNull { it.s(k, fc) }
                val byCut = ss.count { s -> pu.maxBy { s.s(it, fc) ?: -1.0 } != k }
                val byWhole = ss.count { s -> pu.maxBy { s.s(it, 1.0) ?: -1.0 } != k }
                val margin = ss.minOf { s -> s.s(k, fc)!! - pu.filter { it != k }.maxOf { s.s(it, fc) ?: -1.0 } }
                log("     cut %-5s own %s .. %s   named wrong: by the cut %d (margin min %s), by the whole %d".format(
                    cutName(fc), f(own.min()), f(own.sorted()[own.size / 2]), byCut, f(margin), byWhole))
            }
        }
        // The same frames' real bottom chips beside their composites: is the pasted ledge the real one?
        log("   against the real bottom chips (whole template): ")
        for (b in cells.filter { it.bottom && it.isPowerup }) {
            val same = syn.filter { it.frame == b.frame && it.kind == b.read }
            val sameSize = syn.filter { s -> s.kind == b.read && cells.any { it.frame == s.frame && it.w == b.w && it.h == b.h } }
            log("     ${b.name} r4c${b.c} ${b.read} real %s; composites of its frame %s; of its size %s".format(
                f(b.s(b.read!!)), if (same.isEmpty()) "-" else "${f(same.minOf { it.s(b.read)!! })}..${f(same.maxOf { it.s(b.read)!! })}",
                if (sameSize.isEmpty()) "-" else "${f(sameSize.minOf { it.s(b.read)!! })}..${f(sameSize.maxOf { it.s(b.read)!! })} (${sameSize.size})"))
        }
        // 8. Every way against all four sets at once, and what each bar costs.
        val realPlus = cells.filter { it.bottom && it.isPowerup }
        val realMinus = cells.filter { it.bottom && !it.isPowerup && it.share >= Vision.OBJECT_COLOUR_MIN }
        val synPlus = syn.filter { it.kind in pu }
        val synMinus = syn.filter { it.kind !in pu }
        val bars = listOf(0.56, 0.60, 0.62, 0.64, 0.66, 0.68, 0.70, 0.72)
        log("")
        log("8. each way on four sets: the real bottom power-ups (R+ ${realPlus.size}), the composites of rows 0-3's power-ups (S+ ${synPlus.size}),")
        log("   the real coloured bottom cells that are none (R- ${realMinus.size}), the composites of rows 0-3's coloured cells that are none")
        log("   (S- ${synMinus.size}). Per bar: power-ups under it or misnamed (R+ / S+), and cells that are none over it (R- / S-).")
        log("   %-22s %-7s %-22s %-7s %-7s  %s".format("way", "R+ min", "S+ min / 5% / median", "R- max", "S- max",
            bars.joinToString(" ") { "%-18s".format("bar ${f(it, 2)}") }))
        for (fc in CUTS.filter { it >= 1.0 || it <= 0.6 + 1e-9 }) for (byWhole in listOf(false, true)) {
            if (fc >= 1.0 && byWhole) continue
            val nf = if (byWhole) 1.0 else fc
            fun nameC(c: Cell) = pu.maxBy { c.s(it, nf) ?: -1.0 }
            fun nameS(s: Synth) = pu.maxBy { s.s(it, nf) ?: -1.0 }
            val rp = realPlus.map { nameC(it) to (it.s(nameC(it), fc) ?: -1.0) }
            val sp = synPlus.map { nameS(it) to (it.s(nameS(it), fc) ?: -1.0) }
            val rm = realMinus.map { it.s(nameC(it), fc) ?: -1.0 }
            val sm = synMinus.map { it.s(nameS(it), fc) ?: -1.0 }
            val spv = sp.map { it.second }.sorted()
            val label = if (fc >= 1.0) "whole (today)" else "cut ${cutName(fc)}, named by ${if (byWhole) "whole" else "cut"}"
            log("   %-22s %-7s %-22s %-7s %-7s  %s".format(label, f(rp.minOf { it.second }),
                "${f(spv.first())} / ${f(spv[spv.size / 20])} / ${f(spv[spv.size / 2])}", f(rm.max()), f(sm.max()),
                bars.joinToString(" ") { b ->
                    val rMiss = realPlus.indices.count { i -> rp[i].second < b || rp[i].first != realPlus[i].read }
                    val sMiss = synPlus.indices.count { i -> sp[i].second < b || sp[i].first != synPlus[i].kind }
                    "%-18s".format("$rMiss/$sMiss + ${rm.count { it >= b }}/${sm.count { it >= b }}")
                }))
        }
        // 8b. The same with the sets as they were looked at: S- without the chips hidden behind the figure;
        // S+ split into a dark cell covered once (what the bottom row holds), covered twice (a pyramid under
        // it as well: harsher than any bottom cell) and a highlighted cell.
        val clean = synMinus.filter { !it.hidden }
        val hiddenN = synMinus.count { it.hidden }
        val sDark = synPlus.filter { !it.light && !it.twice }
        val sTwice = synPlus.filter { !it.light && it.twice }
        val sLight = synPlus.filter { it.light }
        log("")
        log("8b. the sets as looked at: S- less the $hiddenN composites of chips hidden behind the figure (${clean.size} left); S+ dark cells")
        log("   covered once (${sDark.size}), covered twice (a pyramid under them too, ${sTwice.size}), on a highlighted cell (${sLight.size}).")
        log("   Per bar: missed (under it) / wrong (another power-up over it) for R+ | S+ once | S+ twice | S+ light, and over it for R- | S-.")
        log("   %-24s %-6s %-6s %-6s %-6s %-6s %-6s  %s".format("way", "R+min", "S1min", "S2min", "SLmin", "R-max", "S-max",
            bars.joinToString(" ") { "%-34s".format("bar ${f(it, 2)}") }))
        for (fc in CUTS.filter { it >= 1.0 || it <= 0.6 + 1e-9 }) for (byWhole in listOf(false, true)) {
            if (fc >= 1.0 && byWhole) continue
            val nf = if (byWhole) 1.0 else fc
            fun nameC(c: Cell) = pu.maxBy { c.s(it, nf) ?: -1.0 }
            fun nameS(s: Synth) = pu.maxBy { s.s(it, nf) ?: -1.0 }
            fun scoreC(c: Cell) = c.s(nameC(c), fc) ?: -1.0
            fun scoreS(s: Synth) = s.s(nameS(s), fc) ?: -1.0
            fun ownS(xs: List<Synth>) = xs.map { it.s(it.kind, fc) ?: -1.0 }
            fun tally(xs: List<Synth>, b: Double) =
                "${xs.count { scoreS(it) < b }}/${xs.count { nameS(it) != it.kind && scoreS(it) >= b }}"
            val label = if (fc >= 1.0) "whole (today)" else "cut ${cutName(fc)}, named by ${if (byWhole) "whole" else "cut"}"
            log("   %-24s %-6s %-6s %-6s %-6s %-6s %-6s  %s".format(label,
                f(realPlus.minOf { if (nameC(it) == it.read) scoreC(it) else -1.0 }),
                f(ownS(sDark).min()), f(ownS(sTwice).min()), f(ownS(sLight).min()),
                f(realMinus.maxOf { scoreC(it) }), f(clean.maxOf { scoreS(it) }),
                bars.joinToString(" ") { b ->
                    val rp = "${realPlus.count { scoreC(it) < b }}/${realPlus.count { nameC(it) != it.read && scoreC(it) >= b }}"
                    "%-34s".format("$rp|${tally(sDark, b)}|${tally(sTwice, b)}|${tally(sLight, b)} ${realMinus.count { scoreC(it) >= b }}|${clean.count { scoreS(it) >= b }}")
                }))
        }
        // 8c. The same with the orange template's left columns cropped in the bottom row (section 10 says why):
        // named and scored by the cut, the orange's cut taken from the cropped template.
        log("")
        log("8c. as 8b, named and scored by the cut, the orange template without its left x0 columns (~x0); other power-ups as they are")
        log("   %-24s %-6s %-6s %-6s %-6s %-6s %-6s %-6s  %s".format("way", "R+min", "S1min", "S2min", "SLmin", "R-max", "S-max", "name m",
            bars.joinToString(" ") { "%-34s".format("bar ${f(it, 2)}") }))
        for (x0 in ORANGE_CROPS) for (fc in listOf(0.6, 0.55, 0.5, 0.45, 0.4)) {
            val key = "ticket_orange~$x0"
            fun k(t: String) = if (t == "ticket_orange") key else t
            fun nameC(c: Cell) = pu.maxBy { c.s(k(it), fc) ?: -1.0 }
            fun nameS(s: Synth) = pu.maxBy { s.s(k(it), fc) ?: -1.0 }
            fun scoreC(c: Cell) = c.s(k(nameC(c)), fc) ?: -1.0
            fun scoreS(s: Synth) = s.s(k(nameS(s)), fc) ?: -1.0
            fun ownS(xs: List<Synth>) = xs.map { it.s(k(it.kind), fc) ?: -1.0 }
            fun tally(xs: List<Synth>, b: Double) =
                "${xs.count { scoreS(it) < b }}/${xs.count { nameS(it) != it.kind && scoreS(it) >= b }}"
            // The name's margin: own less the best other power-up, over R+ and every S+.
            val m = (realPlus.map { c -> (c.s(k(c.read!!), fc) ?: -1.0) - pu.filter { it != c.read }.maxOf { c.s(k(it), fc) ?: -1.0 } } +
                synPlus.map { s -> (s.s(k(s.kind), fc) ?: -1.0) - pu.filter { it != s.kind }.maxOf { s.s(k(it), fc) ?: -1.0 } }).min()
            val orangeMin = (realPlus.map { it.s(key, fc) ?: -1.0 } +
                synPlus.filter { it.kind == "ticket_orange" }.map { it.s(key, fc) ?: -1.0 }).min()
            val kindsMin = pu.filter { it != "ticket_orange" }.joinToString(" ") { t ->
                val xs = synPlus.filter { it.kind == t }.mapNotNull { it.s(t, fc) }
                if (xs.isEmpty()) "" else "${t.take(4)} ${f(xs.min())}" }.trim()
            log("   (orange's own lowest over R+ and every orange composite ${f(orangeMin)}; the other kinds' composites: $kindsMin)")
            log("   %-24s %-6s %-6s %-6s %-6s %-6s %-6s %-6s  %s".format("cut ${cutName(fc)}, orange~$x0",
                f(realPlus.minOf { if (nameC(it) == it.read) scoreC(it) else -1.0 }),
                f(ownS(sDark).min()), f(ownS(sTwice).min()), f(ownS(sLight).min()),
                f(realMinus.maxOf { scoreC(it) }), f(clean.maxOf { scoreS(it) }), f(m),
                bars.joinToString(" ") { b ->
                    val rp = "${realPlus.count { scoreC(it) < b }}/${realPlus.count { nameC(it) != it.read && scoreC(it) >= b }}"
                    "%-34s".format("$rp|${tally(sDark, b)}|${tally(sTwice, b)}|${tally(sLight, b)} ${realMinus.count { scoreC(it) >= b }}|${clean.count { scoreS(it) >= b }}")
                }))
        }
        log("   the highest S- per way with the cropped orange (named as the way names it):")
        for (x0 in ORANGE_CROPS) for (fc in listOf(0.55, 0.5, 0.45)) {
            val key = "ticket_orange~$x0"
            fun k(t: String) = if (t == "ticket_orange") key else t
            fun nameS(s: Synth) = pu.maxBy { s.s(k(it), fc) ?: -1.0 }
            val top = clean.sortedByDescending { s -> s.s(k(nameS(s)), fc) ?: -1.0 }.distinctBy { "${it.frame}${it.r}${it.c}" }.take(4)
            log("     cut %-5s ~%-2d %s".format(cutName(fc), x0, top.joinToString("; ") { s ->
                "${s.name.takeLast(26)} r${s.r}c${s.c} ${s.kind} ${nameS(s)} ${f(s.s(k(nameS(s)), fc))}" }))
        }

        log("   the highest S- per way (named as the way names it):")
        for (fc in listOf(1.0, 0.55, 0.5, 0.45, 0.4)) for (byWhole in listOf(false, true)) {
            if (fc >= 1.0 && byWhole) continue
            val nf = if (byWhole) 1.0 else fc
            fun nameS(s: Synth) = pu.maxBy { s.s(it, nf) ?: -1.0 }
            val top = clean.sortedByDescending { s -> s.s(nameS(s), fc) ?: -1.0 }.distinctBy { "${it.frame}${it.r}${it.c}" }.take(4)
            log("     cut %-5s by %-5s %s".format(cutName(fc), if (byWhole) "whole" else "cut", top.joinToString("; ") { s ->
                "${s.name.takeLast(26)} r${s.r}c${s.c} ${s.kind} ${nameS(s)} ${f(s.s(nameS(s), fc))}" }))
        }

        // 10. The orange template's own left margin, every row: its score, whole and cut to its upper rows,
        // with its left columns cropped, on every orange chip against everything else that is coloured.
        fun cellLight(c: Cell) = c.read == "ticket_orange" && LIGHT.any { (n, rc) -> c.name.endsWith(n) && rc == (c.r to c.c) }
        fun cellHidden(c: Cell) = c.r == 0 && c.c == 1 && (c.name.endsWith("220458.png") || TOUR.containsMatchIn(c.name))
        val upOrange = cells.filter { !it.bottom && it.read == "ticket_orange" }
        val posGroups = listOf(
            "up dark" to upOrange.filter { !cellLight(it) && it.below != "pyramid" },
            "up pyr under" to upOrange.filter { !cellLight(it) && it.below == "pyramid" },
            "up light" to upOrange.filter { cellLight(it) },
            "R+" to realPlus)
        val synPos = listOf("S+ once" to sDark.filter { it.kind == "ticket_orange" },
            "S+ twice" to sTwice.filter { it.kind == "ticket_orange" }, "S+ light" to sLight)
        val negCells = listOf(
            "up other pu" to cells.filter { !it.bottom && it.isPowerup && it.read != "ticket_orange" },
            "up none" to cells.filter { !it.bottom && !it.isPowerup && it.read != "figure" && it.share >= Vision.OBJECT_COLOUR_MIN && !cellHidden(it) },
            "R-" to realMinus)
        val negSyn = listOf("S- clean" to clean, "S other pu" to synPlus.filter { it.kind != "ticket_orange" })
        log("")
        log("10. the orange template with its left columns cropped (~x0), every row: the lowest score on each group of real chips,")
        log("    the highest on each group of everything else coloured; `hidden` chips behind the figure are left out of both")
        log("   %-22s %s | %s".format("template, cut", (posGroups.map { it.first } + synPos.map { it.first }).joinToString(" ") { "%-12s".format(it) },
            (negCells.map { it.first } + negSyn.map { it.first }).joinToString(" ") { "%-12s".format(it) }))
        for (t in listOf("ticket_orange") + ORANGE_CROPS.map { "ticket_orange~$it" }) for (fc in listOf(1.0, 0.6, 0.55, 0.5, 0.45)) {
            val pos = posGroups.map { (_, cs) -> cs.mapNotNull { it.s(t, fc) }.minOrNull() } +
                synPos.map { (_, ss) -> ss.mapNotNull { it.s(t, fc) }.minOrNull() }
            val neg = negCells.map { (_, cs) -> cs.mapNotNull { it.s(t, fc) }.maxOrNull() } +
                negSyn.map { (_, ss) -> ss.mapNotNull { it.s(t, fc) }.maxOrNull() }
            log("   %-22s %s | %s   gap %s".format("${t.removePrefix("ticket_")} ${cutName(fc)}",
                pos.joinToString(" ") { "%-12s".format(f(it)) }, neg.joinToString(" ") { "%-12s".format(f(it)) },
                f((pos.filterNotNull().minOrNull() ?: 0.0) - (neg.filterNotNull().maxOrNull() ?: 0.0))))
        }

        // Which composites a way misses or names wrong, and the highest cells that are none, for the ways that look best.
        for (fc in listOf(1.0, 0.55, 0.5, 0.45)) for (byWhole in listOf(false, true)) {
            if (fc >= 1.0 && byWhole) continue
            val nf = if (byWhole) 1.0 else fc
            fun nameS(s: Synth) = pu.maxBy { s.s(it, nf) ?: -1.0 }
            val bar = 0.56
            val miss = synPlus.filter { s -> val n = nameS(s); n != s.kind || (s.s(n, fc) ?: -1.0) < bar }
            val way = "cut ${cutName(fc)} named by ${if (byWhole) "whole" else "the cut"}"
            log("   $way: S+ under ${f(bar, 2)} or misnamed ${miss.size}: " +
                miss.groupBy { "${it.name.takeLast(30)} r${it.r}c${it.c} ${it.kind}" }.entries.joinToString("; ") { (k, ss) ->
                    "$k (${ss.joinToString(",") { s -> val n = nameS(s); (if (n != s.kind) "$n " else "") + f(s.s(n, fc)) }})" })
            val top = synMinus.sortedByDescending { s -> s.s(nameS(s), fc) ?: -1.0 }.take(6)
            log("   $way: S- highest: " + top.joinToString("; ") { s ->
                "${s.name.takeLast(30)} r${s.r}c${s.c} ${s.kind} ledge c${s.fromCol} ${nameS(s)} ${f(s.s(nameS(s), fc))}" })
        }

        // The cells behind S-, highest first, so they can be looked at (crops `r*_S-_*` in the cells folder).
        log("   the S- cells by their highest power-up score over the cuts 0.55 to 0.4 (named by the cut), with the ledge:")
        val lows = listOf(0.55, 0.5, 0.45, 0.4)
        synMinus.groupBy { "${it.frame}|${it.r}|${it.c}" }.values
            .map { ss -> ss to ss.maxOf { s -> lows.maxOf { fc -> s.s(pu.maxBy { s.s(it, fc) ?: -1.0 }, fc) ?: -1.0 } } }
            .sortedByDescending { it.second }.filter { it.second >= 0.5 }.forEach { (ss, best) ->
                val s = ss.first()
                log("     %-44s r%dc%d %-15s %s   whole today %s".format(s.name.takeLast(44), s.r, s.c, s.kind, f(best),
                    f(ss.maxOf { x -> x.s(pu.maxBy { x.s(it) ?: -1.0 }) ?: -1.0 })))
            }

        val others = syn.filter { it.kind !in pu }
        if (others.isNotEmpty()) {
            log("   coloured cells that are no power-up, ledge pasted (${others.size} composites): highest power-up score, named by the whole")
            for (fc in CUTS) {
                val best = others.maxBy { s -> val n = pu.maxBy { s.s(it, 1.0) ?: -1.0 }; s.s(n, fc) ?: -1.0 }
                val n = pu.maxBy { best.s(it, 1.0) ?: -1.0 }
                log("     cut %-5s %s %s (%s r${best.r}c${best.c}, ledge of c${best.fromCol})".format(cutName(fc), f(best.s(n, fc)), n, best.name.takeLast(30)))
            }
        }
    }

    private fun f(x: Double?, d: Int = 3) = if (x == null || x.isNaN()) "-" else String.format(Locale.ROOT, "%.${d}f", x)

    /** The report. [cells] are every board's. */
    fun report(cells: List<Cell>, log: (String) -> Unit) {
        val pu = Vision.POWERUPS
        val bar = Vision.DEFAULT_THRESHOLD
        val bottomBar = bar - 0.06
        log("")
        log("=".repeat(78))
        log("F10, the bottom row's objects: every power-up template on every coloured cell and every bottom cell")
        log("bar ${f(bar, 2)} for a power-up, ${f(bottomBar, 2)} in the bottom row until F10 (the whole template; `whole (today)` below), " +
            "${f(Vision.POWERUP_BOTTOM_MIN, 2)} since (the upper half, the orange without its left ${Vision.ORANGE_BOTTOM_LEFT} columns: " +
            "`cut 0.50, orange~${Vision.ORANGE_BOTTOM_LEFT}` in 8c)")
        log("`share` is the colour prefilter's per cent (it passes at ${Vision.OBJECT_COLOUR_MIN})")

        // 1. Each power-up where readGrid reads it, upper rows against the bottom row.
        log("")
        log("1. where readGrid reads a power-up: its own template's whole score, rows 0-3 against row 4")
        for (t in pu) {
            val up = cells.filter { it.read == t && !it.bottom }.mapNotNull { it.s(t) }.sorted()
            val lo = cells.filter { it.read == t && it.bottom }.mapNotNull { it.s(t) }.sorted()
            fun d(xs: List<Double>) = if (xs.isEmpty()) "none" else "${xs.size}: ${f(xs.first())} .. ${f(xs[xs.size / 2])} .. ${f(xs.last())}"
            log(String.format(Locale.ROOT, "  %-14s rows 0-3 %-30s row 4 %s", t, d(up), d(lo)))
        }
        // Each power-up's margin over the second best power-up, rows 0-3.
        log("  the margin of the name, rows 0-3 (own score less the best other power-up's):")
        for (t in pu) {
            val ms = cells.filter { it.read == t && !it.bottom }.map { c ->
                c.s(t)!! - pu.filter { it != t }.maxOf { c.s(it) ?: -1.0 } }.sorted()
            if (ms.isNotEmpty()) log("    %-14s %d cells, margin %s .. %s".format(t, ms.size, f(ms.first()), f(ms.last())))
        }

        // 2. Every bottom power-up, with every template and its frame's upper chips.
        val bots = cells.filter { it.bottom && (it.isPowerup || it.share >= Vision.OBJECT_COLOUR_MIN) }
        log("")
        log("2. every coloured bottom cell (and every bottom power-up), whole templates; the same frame's upper-row cells of that object")
        for (b in bots) {
            val ranked = (pu + "arrow").mapNotNull { t -> b.s(t)?.let { t to it } }.sortedByDescending { it.second }
            log("  ${b.name} r${b.r}c${b.c} read ${b.read ?: "-"} share ${f(b.share, 2)} hue ${f(b.hue, 0)} cell ${f(b.cellW, 0)}px: " +
                ranked.joinToString("  ") { "${it.first} ${f(it.second)}" })
            val t = b.read?.takeIf { it in pu } ?: ranked.first().first
            val same = cells.filter { it.frame == b.frame && !it.bottom && it.read == t }
            if (same.isNotEmpty()) log("      same frame, rows 0-3: " + same.joinToString("  ") { "r${it.r}c${it.c} ${t} ${f(it.s(t))}" })
            val scene = cells.filter { it.w == b.w && it.h == b.h && !it.bottom && it.read == t }.mapNotNull { it.s(t) }.sorted()
            if (scene.isNotEmpty()) log("      frames of ${b.w}x${b.h}, rows 0-3: ${scene.size} cells ${f(scene.first())} .. ${f(scene.last())}")
        }

        // 2b. Every orange chip, row by row: what stands in the cell under it, and the cut scores.
        log("")
        log("2b. every cell that reads ticket_orange: the cell under it, the whole score and the upper cuts (own / best other power-up)")
        val show = listOf(1.0, 0.8, 2.0 / 3.0, 0.6, 0.55, 0.5, 0.45, 0.4)
        log("   %-44s %-5s %-9s %s".format("frame", "cell", "under", show.joinToString(" ") { "%-13s".format(cutName(it)) }))
        for (o in cells.filter { it.read == "ticket_orange" }.sortedWith(compareBy({ it.bottom }, { it.s("ticket_orange") }))) {
            val under = o.below ?: "(plain)"
            log("   %-44s r%dc%d %-9s %s".format(o.name.takeLast(44), o.r, o.c, under.take(9), show.joinToString(" ") { fc ->
                val other = pu.filter { it != "ticket_orange" }.maxBy { o.s(it, fc) ?: -1.0 }
                "%-13s".format("${f(o.s("ticket_orange", fc))}/${f(o.s(other, fc))}")
            }))
        }

        // 3. Why: where each object sits on its cell, and how each third of its template matches.
        log("")
        log("3. why: the object's place on its cell (whole template's best place, from the cell's top-left, in cells) and each third of")
        log("   the whole template matched on its own at the place of the upper two thirds (top / middle / bottom)")
        for (t in pu) {
            val up = cells.filter { it.read == t && !it.bottom }
            val lo = cells.filter { it.read == t && it.bottom }
            if (up.isEmpty() && lo.isEmpty()) continue
            fun stat(xs: List<Double>) = if (xs.isEmpty()) "-" else xs.sorted().let { "${f(it.first(), 2)}..${f(it.last(), 2)}" }
            for ((what, cs) in listOf("rows 0-3" to up, "row 4" to lo)) {
                if (cs.isEmpty()) continue
                val th = cs.mapNotNull { it.thirds[t] }
                log("  %-14s %-8s n %3d  place y %s x %s  thirds top %s mid %s bottom %s".format(t, what, cs.size,
                    stat(cs.mapNotNull { it.placeY[t] }), stat(cs.mapNotNull { it.placeX[t] }),
                    stat(th.map { it[0] }), stat(th.map { it[1] }), stat(th.map { it[2] })))
            }
        }
        for (b in bots.filter { it.isPowerup }) {
            val t = b.read!!
            log("    ${b.name} r${b.r}c${b.c}: place y ${f(b.placeY[t], 3)} x ${f(b.placeX[t], 3)}, thirds " +
                (b.thirds[t]?.joinToString(" / ") { f(it) } ?: "-"))
        }

        // 4. The cuts: every power-up template's upper rows, on every cell that reads one, top and bottom.
        log("")
        log("4. every power-up template cut to its upper rows: own score (min over the cells) and the name's margin")
        log("   (own less the best other power-up's, same cut; min) -- rows 0-3 and row 4 apart")
        log("   %-6s %s".format("cut", pu.joinToString(" ") { "%-24s".format(it) }))
        for (fc in CUTS) {
            for ((what, sel) in listOf("0-3" to { c: Cell -> !c.bottom }, "4" to { c: Cell -> c.bottom })) {
                val parts = pu.map { t ->
                    val cs = cells.filter { it.read == t && sel(it) }
                    if (cs.isEmpty()) "%-24s".format("-") else {
                        val own = cs.mapNotNull { it.s(t, fc) }.min()
                        val margin = cs.map { c -> c.s(t, fc)!! - pu.filter { it != t }.maxOf { c.s(it, fc) ?: -1.0 } }.min()
                        val wrong = cs.count { c -> pu.maxBy { c.s(it, fc) ?: -1.0 } != t }
                        "%-24s".format("${f(own)} m${f(margin)}" + (if (wrong > 0) " x$wrong" else ""))
                    }
                }
                log("   %-6s %-3s %s".format(cutName(fc), what, parts.joinToString(" ")))
            }
        }

        // 5. The ways of asking the bottom row, side by side.
        log("")
        log("5. the ways of asking the bottom row. `chips`: the bottom power-ups' lowest score under the way and how many take")
        log("   another name; `others`: the highest power-up score (as the way names it) on every other bottom cell, coloured")
        log("   or not -- a coloured one meets the templates, an uncoloured one only if the prefilter let it through")
        val lo = cells.filter { it.bottom }
        val chips = lo.filter { it.isPowerup }
        val colOthers = lo.filter { !it.isPowerup && it.share >= Vision.OBJECT_COLOUR_MIN }
        val plainOthers = lo.filter { !it.isPowerup && it.share < Vision.OBJECT_COLOUR_MIN }
        // A way: which cut names the object, which cut scores it.
        class Way(val label: String, val nameCut: Double, val scoreCut: Double)
        val ways = listOf(Way("whole (today)", 1.0, 1.0)) +
            CUTS.drop(1).map { Way("cut ${cutName(it)}", it, it) } +
            CUTS.drop(1).map { Way("name whole, score ${cutName(it)}", 1.0, it) }
        // Rows 0-3's coloured cells that are no power-up (the figure's wings, the arrow, "?"): what the
        // upper part of a template makes of things that are not objects, uncovered.
        val upOthers = cells.filter { !it.bottom && !it.isPowerup && it.read != "figure" && it.share >= Vision.OBJECT_COLOUR_MIN }
        log("   %-28s %-26s %-40s %-26s %s".format("way", "chips (${chips.size})", "coloured others (${colOthers.size})",
            "plain others (${plainOthers.size})", "rows 0-3 coloured, none (${upOthers.size})"))
        for (w in ways) {
            fun named(c: Cell) = pu.maxBy { c.s(it, w.nameCut) ?: -1.0 }
            fun score(c: Cell) = c.s(named(c), w.scoreCut) ?: -1.0
            val chipMin = chips.minOfOrNull { score(it) }
            val wrong = chips.count { named(it) != it.read }
            val co = colOthers.maxByOrNull { score(it) }
            val pl = plainOthers.maxByOrNull { score(it) }
            val uo = upOthers.maxByOrNull { score(it) }
            log("   %-28s %-26s %-40s %-26s %s".format(w.label,
                "${f(chipMin)}" + (if (wrong > 0) " ($wrong misnamed)" else ""),
                co?.let { "${f(score(it))} ${named(it)} (${it.name.takeLast(22)} r4c${it.c})" } ?: "-",
                pl?.let { "${f(score(it))} ${named(it)}" } ?: "-",
                uo?.let { "${f(score(it))} ${named(it)} (${it.name.takeLast(22)} r${it.r}c${it.c})" } ?: "-"))
        }

        // 6. The colour decision: the hue of the coloured pixels, per power-up, rows 0-3 and 4.
        log("")
        log("6. the hue of a cell's strongly coloured pixels (the prefilter's mask, mean on the circle of 180), per object")
        for (t in pu + listOf("figure", "?", null)) {
            for ((what, sel) in listOf("0-3" to { c: Cell -> !c.bottom }, "4" to { c: Cell -> c.bottom })) {
                val cs = cells.filter { it.read == t && sel(it) && it.share >= Vision.OBJECT_COLOUR_MIN }
                val hs = cs.mapNotNull { it.hue }.sorted()
                if (hs.isEmpty()) continue
                val shares = cs.map { it.share }.sorted()
                log("   %-14s row %-3s n %3d  hue %s .. %s   share %s .. %s".format(t ?: "empty (coloured)", what, hs.size,
                    f(hs.first(), 0), f(hs.last(), 0), f(shares.first(), 2), f(shares.last(), 2)))
            }
        }
        val plainShares = plainOthers.map { it.share }.sorted()
        if (plainShares.isNotEmpty()) log("   bottom cells under the prefilter: ${plainShares.size}, share at most ${f(plainShares.last(), 2)}")
    }
}

/** [BoardProbe]'s scaling, for a template this file cut: the same arithmetic, its own key. */
private val anyCache = HashMap<String, Mat>()

fun BoardProbe.scaledAny(key: String, tpl: Mat, cellW: Double): Mat {
    val factor = cellW / REF_CELL_W
    if (abs(factor - 1.0) < 0.02) return tpl
    return anyCache.getOrPut(key + "@" + String.format(Locale.ROOT, "%.4f", factor)) {
        Mat().also {
            Imgproc.resize(tpl, it, Size(maxOf(4, Py.int(tpl.cols() * factor)).toDouble(),
                                         maxOf(4, Py.int(tpl.rows() * factor)).toDouble()))
        }
    }
}
