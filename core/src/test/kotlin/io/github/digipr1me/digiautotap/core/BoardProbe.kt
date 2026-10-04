package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * The Digital World Search board on every frame of the corpus that has one
 * (PLAN_WORLD_SEARCH_FORMATE.md 4.1, B0).
 *
 *   gradlew :core:boardProbe --no-daemon
 *   gradlew :core:boardProbe --no-daemon --args=twin
 *   gradlew :core:boardProbe --no-daemon --args="twin 1644x3840"
 *   gradlew :core:boardProbe --no-daemon --args="glyphs board_720x"
 *   gradlew :core:boardProbe --no-daemon --args=objects        the bottom row's objects (F10, [BoardObjects])
 *   gradlew :core:boardProbe --no-daemon --args="meters calibrated"   where the counters' digits touch (F25, [MeterGlyphs])
 *   gradlew :core:boardProbe --no-daemon --args="meters nocorpus extra=<png or folder> masks meterPad=0.025"
 *
 * `extra=` reads frames outside the corpus by their path (a live run's
 * kept frames, before they go in), `nocorpus` only those; `calibrated`
 * adds every frame `oracle/vision.json` reads counters on, boards or not,
 * and `meters` then says how many of them read differently from it;
 * `masks` keeps the metre label's crop and mask in build/boardProbe_meters/;
 * `meterPad=` reads the label once more with its box that share of a cell
 * taller at the bottom (F26).
 *
 * A frame is on the list when `oracle/director.json` calls it `board` or its
 * name begins with `board_`. Each is read as `DigiAutotapService.grab` hands
 * it over (with its headroom, [OracleFamilies.read]) and gets one line: its
 * size and headroom ([OracleFamilies.room]), what `calibrate` found (cell
 * and card), `find_figure` (cell, way, score), the grid in one line of
 * letters, the seven counters, `close_button`, `director.classify`, and the
 * time of `calibrate`, `readGrid`, `findFigure` and `readCounters` in ms --
 * warm (one untimed call first), the median of three. Under it the score of
 * every occupied cell, the bottom row cell by cell with its best candidate
 * and the pyramid's score whether it read or not (the half-hidden pyramid of
 * F2 is exactly a score that did not read), and for a counter that read null
 * `readNumberDebug`'s reason.
 *
 * `twin` lays every frame beside its 1080 x 1920 twin of the same scene --
 * the same `paws` and `meters`, which only the player moves -- and prints
 * every cell that reads differently, with both scores. Two cells are known
 * to be state, not format (PLAN_FORMATE.md V10): the cells beside the
 * figure, into which its wings flap, and the bottom row, where the pyramid
 * shimmers around its lowered bar. They are printed and marked, never left
 * out.
 *
 * The candidate scores are computed here the way `Vision.readGrid` computes
 * them (the same patch, the same colour prefilter, the same template scaled
 * the same way, the same bar), because `readGrid` hands back a score only for
 * a cell that read. The probe checks itself against `readGrid` on every cell
 * that read and says how many disagreed; that count must be 0.
 *
 * After the frames, the bottom row on its own (F2 of the plan): the pyramid's
 * score with the upper two thirds of its template, which `readGrid` asks
 * there, and with the whole template, on every cell that read a pyramid and
 * on every other, and the gap between them. `glyphs` adds every component of
 * every counter as `segmentDigits` sees it, with the filter that drops it,
 * and per frame width how much room each of its rules leaves (F4).
 *
 * Nothing here writes anywhere but its own report: stdout, and the whole of
 * it in `core/build/boardProbe.txt` (NOTES.md, "Two ways to lose a corpus
 * sweep"). No reader, no constant and no oracle file changes.
 */
object BoardProbe {

    const val REF_W = 1080
    const val REF_H = 1920

    /** One letter per object, so a board fits in one line. */
    val LETTERS = mapOf(
        "ticket_orange" to 'O', "ticket_green" to 'G', "ticket_pink" to 'K', "claw" to 'C',
        "paw" to 'P', "fireball" to 'F', "pyramid" to 'A', "?" to '?', null to '.')

    const val REF_CELL_W = 87.5

    class Cand(val name: String, val value: Double, val need: Double) {
        val margin get() = value - need
    }

    /** One frame, read. */
    class Board(
        val rel: String, val w: Int, val h: Int, val room: Int, val above: Int,
        val screen: String, val by: String,
        val calib: Calib?, val figure: Figure?,
        val names: List<List<String?>>?, val scores: List<List<Double>>?,
        /** Every cell's candidates, best margin first. */
        val cands: List<List<List<Cand>>>?,
        val colourful: List<List<Boolean>>?,
        val counters: Map<String, Long?>?, val counterWhy: Map<String, String>,
        val close: Explore.CloseButton?,
        val ms: Map<String, Double>,
        val selfCheckMisses: List<String>,
        /** The whole pyramid template's score on each bottom cell ([wholePyramid]). */
        val bottomWhole: List<Double?> = emptyList(),
    ) {
        val name get() = rel.substringAfterLast('/')
        val take: Int? get() = Regex("""_(\d{2})(\d{2})(\d{2})\.png$""").find(name)
            ?.destructured?.let { (a, b, c) -> a.toInt() * 3600 + b.toInt() * 60 + c.toInt() }
        /** A reference in FormatProbe's sense: 1080 x 1920, no cutout (the density takes are not). */
        val isRef get() = w == REF_W && h == REF_H && room == 0 && name.startsWith("board_1080x1920_none0_")
        val scene: Pair<Long?, Long?> get() = counters?.get("paws") to counters?.get("meters")
        val cellW get() = calib?.cellW ?: 0.0
    }

    private val scaledCache = HashMap<String, Mat>()

    /** `Vision.scaled`, the same arithmetic: nothing within 2 %, else a resize to truncated sizes. */
    private fun scaled(name: String, tpl: Mat, cellW: Double): Mat {
        val factor = cellW / REF_CELL_W
        if (abs(factor - 1.0) < 0.02) return tpl
        val key = "$name@" + String.format(Locale.ROOT, "%.4f", factor)
        return scaledCache.getOrPut(key) {
            Mat().also {
                Imgproc.resize(tpl, it, Size(maxOf(4, Py.int(tpl.cols() * factor)).toDouble(),
                                             maxOf(4, Py.int(tpl.rows() * factor)).toDouble()))
            }
        }
    }

    /** `Vision.matchMax`. */
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
     * The whole pyramid template's score on a bottom cell, which `readGrid`
     * no longer asks there (it asks the upper two thirds,
     * [Vision.PYRAMID_BOTTOM_SHARE]): printed beside it, because F2 was
     * measured as the difference between the two.
     */
    fun wholePyramid(v: Vision, img: Mat, calib: Calib, templates: Map<String, Mat>, r: Int, c: Int): Double? {
        val patch = v.searchPatch(img, calib, r, c) ?: return null
        val tpl = templates["pyramid"] ?: return null
        val tplS = scaled("pyramid", tpl, calib.cellW)
        if (tplS.rows() > patch.rows() || tplS.cols() > patch.cols()) return null
        return matchMax(patch, tplS)
    }

    /** The candidates `readGrid` weighs for one cell, best margin first (ties: readGrid's order). */
    fun candidates(v: Vision, img: Mat, calib: Calib, templates: Map<String, Mat>, r: Int, c: Int): Pair<Boolean, List<Cand>> {
        val patch = v.searchPatch(img, calib, r, c) ?: return false to emptyList()
        val colourful = v.hasObjectColour(img, calib, r, c)
        val powerups = templates.keys.filter { it in Vision.POWERUPS }
        var names = if (colourful) powerups + "pyramid" else listOf("pyramid")
        val arrow = v.loadTemplates()["arrow"]
        if (colourful && arrow != null) names = names + "arrow"
        val out = ArrayList<Cand>()
        for (name in names) {
            val whole = (if (name == "arrow") arrow else templates[name]) ?: continue
            // readGrid's bottom row: the pyramid's upper two thirds, a power-up's
            // upper half (the orange chip's without its left columns), with its own bar.
            val bottom = r == Vision.ROWS - 1
            val top = bottom && name == "pyramid"
            val part = bottom && name in Vision.POWERUPS
            val tpl = when {
                top -> v.upperPart(whole)
                part -> v.bottomPart(name, whole)
                else -> whole
            }
            val tplS = scaled(if (top) "pyramid^top" else if (part) "$name^bottom" else name, tpl, calib.cellW)
            if (tplS.rows() > patch.rows() || tplS.cols() > patch.cols()) continue
            val value = matchMax(patch, tplS)
            var need = Vision.THRESHOLDS[name] ?: Vision.DEFAULT_THRESHOLD
            if (bottom) need = if (part) Vision.POWERUP_BOTTOM_MIN else need - 0.06
            out += Cand(name, value, need)
        }
        // readGrid keeps the first of equal margins (`>`), so a stable sort.
        return colourful to out.sortedByDescending { it.margin }
    }

    /**
     * What `Vision.readGrid` names a cell after its race ([cands], best first):
     * a pyramid that reads where the arrow won, the winner where it reached
     * its bar, else in rows 0-3 the second look at a covered object
     * (`Vision.PYRAMID_COVERED_MIN`), never on the figure's own cell. Null
     * where none of them names it ("?" and nothing are readGrid's own).
     */
    fun decision(v: Vision, img: Mat, calib: Calib, templates: Map<String, Mat>, r: Int, c: Int,
                 colourful: Boolean, cands: List<Cand>, figure: Figure?): Cand? {
        val patch = v.searchPatch(img, calib, r, c) ?: return null
        val bottom = r == Vision.ROWS - 1
        val best = cands.firstOrNull()
        val pyr = cands.firstOrNull { it.name == "pyramid" }
        fun top(): Double? {
            if (pyr == null || pyr.value < Vision.PYRAMID_COVERED_FLOOR) return null
            val t = scaledAny("pyramid^top", v.upperPart(templates.getValue("pyramid")), calib.cellW)
            if (t.rows() > patch.rows() || t.cols() > patch.cols()) return null
            return matchMax(patch, t)
        }
        if (best != null && best.name == "arrow" && best.margin >= 0) {
            if (pyr == null) return null
            if (bottom) return pyr.takeIf { it.margin >= 0 }
            if (pyr.margin >= 0) return pyr
            return top()?.takeIf { it >= Vision.PYRAMID_COVERED_MIN }?.let { Cand("pyramid", it, Vision.PYRAMID_COVERED_MIN) }
        }
        if (best != null && best.margin >= 0) return best
        if (bottom || (figure != null && figure.row == r && figure.col == c)) return null
        var out: Cand? = null
        if (colourful) for (name in templates.keys.filter { it in Vision.POWERUPS }) {
            val t = scaledAny("$name^bottom", v.bottomPart(name, templates.getValue(name)), calib.cellW)
            if (t.rows() > patch.rows() || t.cols() > patch.cols()) continue
            val cand = Cand(name, matchMax(patch, t), Vision.POWERUP_BOTTOM_MIN)
            if (cand.margin >= 0 && cand.margin > (out?.margin ?: -1.0)) out = cand
        }
        val value = top()
        if (value != null) {
            val cand = Cand("pyramid", value, Vision.PYRAMID_COVERED_MIN)
            if (cand.margin >= 0 && cand.margin > (out?.margin ?: -1.0)) out = cand
        }
        return out
    }

    /**
     * One connected component of a counter's mask, as `Vision.segmentDigits`
     * builds it (the crop, grey, 3x cubic, the polarity's bar), with the first
     * of its filters that drops it -- or "kept" -- and the height against the
     * median of the boxes that reached the height test.
     */
    class Glyph(val x: Int, val y: Int, val w: Int, val h: Int, val area: Int, val ratio: Double,
                val sat: Double?, var relH: Double?, var verdict: String, val rows: Int)

    /** `segmentDigits` with every component and every filter written out (F4). */
    fun glyphs(img: Mat, roi: IntArray, polarity: String, scale: Int = 3): List<Glyph> {
        val (x, y, w, h) = roi
        val crop = Py.crop(img, maxOf(0, y), y + h, maxOf(0, x), x + w) ?: return emptyList()
        val small = Cv.gray(crop)
        val gray = Mat()
        Imgproc.resize(small, gray, Size(), scale.toDouble(), scale.toDouble(), Imgproc.INTER_CUBIC)
        val binary = Mat()
        if (polarity == "bright") Core.inRange(gray, org.opencv.core.Scalar(175.0), org.opencv.core.Scalar(255.0), binary)
        else Core.inRange(gray, org.opencv.core.Scalar(0.0), org.opencv.core.Scalar(140.0), binary)
        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids, 8)
        val hsv = Cv.hsv(crop)
        val colour = Mat()
        Imgproc.resize(hsv, colour, Size(gray.cols().toDouble(), gray.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        val width = binary.cols()
        val labelData = IntArray(binary.rows() * width); labels.get(0, 0, labelData)
        val colourData = ByteArray(colour.rows() * colour.cols() * 3); colour.get(0, 0, colourData)
        val out = ArrayList<Glyph>()
        for (i in 1 until n) {
            val s = IntArray(5).also { stats.get(i, 0, it) }
            val gx = s[0]; val gy = s[1]; val gw = s[2]; val gh = s[3]; val area = s[4]
            val ratio = gw / gh.toDouble()
            var sat: Double? = null
            if (polarity == "bright") {
                var sum = 0L; var count = 0
                for (yy in gy until gy + gh) for (xx in gx until gx + gw) {
                    if (labelData[yy * width + xx] > 0) { sum += colourData[(yy * width + xx) * 3 + 1].toInt() and 0xFF; count += 1 }
                }
                if (count > 0) sat = sum.toDouble() / count
            }
            val verdict = when {
                area < 50 -> "area"
                gx <= 1 -> "left edge"
                gh > 0.95 * binary.rows() -> "tall"
                ratio < 0.28 || ratio > 0.98 -> "ratio"
                sat != null && sat > 90 -> "colour"
                else -> "height?"
            }
            out += Glyph(gx, gy, gw, gh, area, ratio, sat, null, verdict, binary.rows())
        }
        val boxes = out.filter { it.verdict == "height?" }
        if (boxes.isNotEmpty()) {
            val hs = boxes.map { it.h }.sorted()
            val refH = if (hs.size % 2 == 1) hs[hs.size / 2].toDouble() else (hs[hs.size / 2 - 1] + hs[hs.size / 2]) / 2.0
            for (g in boxes) {
                g.relH = g.h / refH
                g.verdict = if (g.h < 0.80 * refH || g.h > 1.25 * refH) "height" else "kept"
            }
        }
        listOf(small, gray, binary, labels, stats, centroids, hsv, colour).forEach { it.release() }
        return out.sortedBy { it.x }
    }

    private fun median(xs: List<Double>): Double = xs.sorted().let { it[it.size / 2] }

    /** Times [block]: one call untimed, then the median of three. */
    private fun <T> timed(block: () -> T): Pair<T, Double> {
        val first = block()
        val ts = (0 until 3).map {
            val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1e6
        }
        return first to median(ts)
    }

    /** A corpus frame by its path under the repository, or a frame outside it by its absolute path (`extra=`). */
    fun file(repo: File, rel: String): File = File(rel).let { if (it.isAbsolute) it else File(repo, rel) }

    fun read(repo: File, rel: String, v: Vision): Board {
        val file = file(repo, rel)
        val img = OracleFamilies.read(file)
        require(!img.empty()) { "cannot read $rel" }
        try {
            val (room, above) = OracleFamilies.room(file.name, img.rows())
            val answer = Director.classify(img)
            val close = Explore.closeButton(img)
            val ms = LinkedHashMap<String, Double>()
            val calib = try {
                val (c, t) = timed { v.calibrate(img) }
                ms["calibrate"] = t
                c
            } catch (e: CalibrationError) {
                null
            }
            if (calib == null) {
                return Board(rel, img.cols(), img.rows(), room, above, answer.screen, answer.by,
                    null, null, null, null, null, null, null, emptyMap(), close, ms, emptyList())
            }
            val templates = v.loadTemplates()
            val board = v.boardTemplates()
            val (figure, tFig) = timed { v.findFigure(img, calib, templates) }
            ms["findFigure"] = tFig
            val (grid, tGrid) = timed { v.readGrid(img, calib, board, figure = figure) }
            ms["readGrid"] = tGrid
            val (counters, tCnt) = timed { v.readCounters(img, calib) }
            ms["readCounters"] = tCnt
            val why = LinkedHashMap<String, String>()
            for ((key, name) in Vision.COUNTER_KEYS.zip(Vision.COUNTER_NAMES)) {
                if (counters[name] == null) why[name] = v.readNumberDebug(img, key, calib).second
            }
            val colourful = ArrayList<List<Boolean>>()
            val cands = ArrayList<List<List<Cand>>>()
            val misses = ArrayList<String>()
            for (r in 0 until Vision.ROWS) {
                val rowCol = ArrayList<Boolean>(); val rowC = ArrayList<List<Cand>>()
                for (c in 0 until Vision.COLS) {
                    val (col, cs) = candidates(v, img, calib, board, r, c)
                    rowCol += col; rowC += cs
                    // The probe against readGrid, wherever readGrid named an object.
                    val said = grid.first[r][c]
                    if (said != null && said != "?") {
                        val best = decision(v, img, calib, board, r, c, col, cs, figure)
                        if (best == null || best.name != said || abs(best.value - grid.second[r][c]) > 1e-6)
                            misses += "r${r}c$c readGrid $said %.4f, probe %s".format(grid.second[r][c],
                                best?.let { "${it.name} %.4f".format(it.value) } ?: "none")
                    }
                }
                colourful += rowCol; cands += rowC
            }
            val whole = (0 until Vision.COLS).map { c -> wholePyramid(v, img, calib, board, Vision.ROWS - 1, c) }
            return Board(rel, img.cols(), img.rows(), room, above, answer.screen, answer.by,
                calib, figure, grid.first, grid.second, cands, colourful, counters, why, close, ms, misses, whole)
        } finally {
            img.release()
        }
    }

    // ------------------------------------------------------------------------
    // Printing
    // ------------------------------------------------------------------------

    fun f(x: Double, d: Int = 3) = String.format(Locale.ROOT, "%.${d}f", x)

    fun gridLine(b: Board): String {
        val names = b.names ?: return "-"
        return (0 until Vision.ROWS).joinToString("/") { r ->
            (0 until Vision.COLS).joinToString("") { c ->
                if (b.figure?.row == r && b.figure.col == c && names[r][c] == null) "@"
                else (LETTERS[names[r][c]] ?: '#').toString()
            }
        }
    }

    fun countersLine(b: Board): String {
        val k = b.counters ?: return "-"
        fun n(x: String) = k[x]?.toString() ?: "null"
        return "top ${n("top_orange")}/${n("top_green")}/${n("top_pink")} paws ${n("paws")} claws ${n("claws")} " +
            "fireballs ${n("fireballs")} meters ${n("meters")} (${k.values.count { it != null }}/7)"
    }

    fun figureText(fig: Figure?) = fig?.let { "r${it.row}c${it.col} ${it.how} ${it.score?.let { s -> f(s, 2) } ?: "-"}" } ?: "null"

    fun cellText(b: Board, r: Int, c: Int): String {
        val name = b.names?.get(r)?.get(c)
        val cs = b.cands?.get(r)?.get(c).orEmpty()
        val pyr = cs.firstOrNull { it.name == "pyramid" }
        return when {
            name != null && name != "?" -> "$name ${f(b.scores!![r][c])}"
            name == "?" -> "? (best ${cs.firstOrNull()?.let { "${it.name} ${f(it.value)}/${f(it.need, 2)}" } ?: "-"})"
            else -> "empty (${cs.firstOrNull()?.let { "best ${it.name} ${f(it.value)}/${f(it.need, 2)}" } ?: "-"}" +
                (if (pyr != null && pyr !== cs.firstOrNull()) ", pyr ${f(pyr.value)}" else "") + ")"
        }
    }

    fun line(b: Board): List<String> {
        val out = ArrayList<String>()
        val size = "${b.w}x${b.h}" + (if (b.room > 0) " room ${b.room}/${b.above}" else "")
        val closeText = b.close?.let { "X ${f(it.fx)},${f(it.fy)}" } ?: "X null"
        val ms = listOf("calibrate", "readGrid", "findFigure", "readCounters")
            .joinToString(" ") { k -> "${k.take(4)} ${b.ms[k]?.let { f(it, 1) } ?: "-"}" }
        if (b.calib == null) {
            out += "${b.rel}  $size  [${b.screen} by ${b.by}]  calibrate RAISES  $closeText  ms $ms"
            return out
        }
        val cal = b.calib
        out += "${b.rel}  $size  [${b.screen} by ${b.by}]  cell ${f(cal.cellW, 1)}x${f(cal.cellH, 1)} " +
            "card ${cal.card.toList()}  fig ${figureText(b.figure)}  grid ${gridLine(b)}  $closeText  ms $ms"
        val occupied = ArrayList<String>()
        for (r in 0 until Vision.ROWS) for (c in 0 until Vision.COLS) {
            val name = b.names!![r][c] ?: continue
            occupied += "r${r}c$c ${if (name == "?") cellText(b, r, c) else "$name ${f(b.scores!![r][c])}"}"
        }
        out += "    cells: " + (occupied.joinToString(", ").ifEmpty { "none" })
        val r = Vision.ROWS - 1
        out += "    bottom: " + (0 until Vision.COLS).joinToString("  ") { c ->
            val cs = b.cands!![r][c]
            val pyr = cs.firstOrNull { it.name == "pyramid" }
            "c$c ${if (b.colourful!![r][c]) "col " else ""}" +
                (b.names!![r][c] ?: "-") + " [" +
                (cs.firstOrNull()?.let { "${it.name} ${f(it.value)}/${f(it.need, 2)}" } ?: "-") +
                (if (pyr != null && pyr !== cs.firstOrNull()) ", pyr ${f(pyr.value)}" else "") +
                (b.bottomWhole.getOrNull(c)?.let { ", whole ${f(it)}" } ?: "") + "]"
        }
        out += "    counters: ${countersLine(b)}" +
            (if (b.counterWhy.isNotEmpty()) "   why null: " + b.counterWhy.entries.joinToString(", ") { "${it.key}: ${it.value}" } else "")
        if (b.selfCheckMisses.isNotEmpty()) out += "    PROBE DISAGREES WITH readGrid: ${b.selfCheckMisses.joinToString("; ")}"
        return out
    }

    // ------------------------------------------------------------------------
    // Twins
    // ------------------------------------------------------------------------

    /**
     * The twin of [b]: a 1080 x 1920 frame of the same scene, nearest in
     * time; a 1080 x 1920 frame's twin is another one of its scene. The scene
     * is `paws` and `meters`. Where one of the two reads null on [b] (F3's
     * meters on 1644 x 3840), the reference must agree on every counter both
     * read, paws among them -- said beside the pair.
     */
    fun twin(b: Board, refs: List<Board>): Pair<Board, String>? {
        val k = b.counters ?: return null
        val pool = refs.filter { it !== b && it.counters != null }
        fun near(xs: List<Board>) = xs.minByOrNull { r -> (b.take ?: 0).let { t -> abs((r.take ?: 0) - t) } }
        val exact = pool.filter { it.scene == b.scene && b.scene.first != null && b.scene.second != null }
        near(exact)?.let { return it to "paws ${b.scene.first}, meters ${b.scene.second}" }
        if (k["paws"] == null) return null
        val loose = pool.filter { r ->
            r.counters!!["paws"] == k["paws"] &&
                Vision.COUNTER_NAMES.all { n -> k[n] == null || r.counters[n] == null || k[n] == r.counters[n] }
        }
        return near(loose)?.let { it to "paws ${k["paws"]}, meters ${k["meters"]} -- matched on every counter both read" }
    }

    /** Is (r, c) a cell the figure's wings reach on either side: its eight neighbours? */
    private fun wings(fig: Figure?, r: Int, c: Int) =
        fig != null && maxOf(abs(fig.row - r), abs(fig.col - c)) == 1

    fun twinLines(here: Board, ref: Board, why: String): List<String> {
        val out = ArrayList<String>()
        val head = "${here.name}  <- ${ref.name}  ($why)"
        if (here.names == null || ref.names == null) {
            out += "$head   calibrate: ${if (ref.names == null) "raises on the twin" else "ok on the twin"}, " +
                "${if (here.names == null) "raises here" else "ok here"}"
            return out
        }
        val diffs = ArrayList<String>()
        if (here.figure?.row != ref.figure?.row || here.figure?.col != ref.figure?.col)
            diffs += "figure: ${figureText(ref.figure)} against ${figureText(here.figure)}"
        for (r in 0 until Vision.ROWS) for (c in 0 until Vision.COLS) {
            val a = ref.names[r][c]; val h = here.names[r][c]
            if (a == h) continue
            val marks = listOfNotNull(
                if (wings(ref.figure, r, c) || wings(here.figure, r, c)) "state: beside the figure (wings)" else null,
                if (r == Vision.ROWS - 1) "state: bottom row" else null)
            diffs += "r${r}c$c  ref ${cellText(ref, r, c)}  here ${cellText(here, r, c)}" +
                (if (marks.isNotEmpty()) "   [${marks.joinToString("; ")}]" else "   [FORMAT?]")
        }
        // A frame the tour stored cut to the canvas (V3) holds no row of the
        // headroom, and the board's three top counters stand there (V4, group 5).
        val cut = here.room > 0 && here.above == 0
        for (n in Vision.COUNTER_NAMES) {
            val a = ref.counters?.get(n); val h = here.counters?.get(n)
            if (a != h) diffs += "counter $n: $a against $h" + (here.counterWhy[n]?.let { "  ($it)" } ?: "") +
                (if (cut && n.startsWith("top_") && h == null) "   [cut: stored without its ${here.room} rows of headroom]" else "")
        }
        if ((ref.close == null) != (here.close == null)) diffs += "close_button: ${ref.close != null} against ${here.close != null}"
        if (ref.screen != here.screen) diffs += "classify: ${ref.screen} against ${here.screen}"
        out += "$head   ${if (diffs.isEmpty()) "the same" else "${diffs.size} differ"}"
        diffs.forEach { out += "    $it" }
        return out
    }
}

fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val twin = "twin" in args
    val glyphMode = "glyphs" in args
    val objectMode = "objects" in args
    // Frames outside the corpus (a live run's kept frames, F25), by path; with
    // `nocorpus` only they are read.
    val extras = args.filter { it.startsWith("extra=") }.map { File(it.removePrefix("extra=")) }.flatMap { e ->
        (if (e.isDirectory) e.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name } else listOf(e))
            .filter { f -> OracleFamilies.pngSize(f)?.let { OracleFamilies.isFrame(it.first, it.second) } == true }
            .map { it.absolutePath.replace('\\', '/') }
    }
    val noCorpus = "nocorpus" in args
    // `masks`: the meter label's mask as segmentDigits builds it, into core/build/boardProbe_meters/.
    val maskMode = "masks" in args
    // `meters`: where each counter's digits touch, and what a too-wide one would split into (F25, [MeterGlyphs]).
    val meterMode = "meters" in args
    // `calibrated`: every frame oracle/vision.json reads counters on as well -- the
    // boards and the frames that are none but calibrate (F25: where a counter rule reaches).
    val calibrated = "calibrated" in args
    // `hang` (with `meters`): where the metre label's digits stand against three anchors (F26).
    val hangMode = "hang" in args
    val filters = args.filter { it != "twin" && it != "glyphs" && it != "objects" && it != "masks" && it != "meters" &&
        it != "nocorpus" && it != "calibrated" && it != "hang" && !it.startsWith("extra=") && !it.startsWith("meterPad=") }
    val report = StringBuilder()
    val log: (String) -> Unit = { line -> println(line); report.append(line).append('\n') }
    try {
        val oracle = Json.parseToJsonElement(File(repo, "oracle/director.json").readText()).jsonObject
        val visionFrames = (Json.parseToJsonElement(File(repo, "oracle/vision.json").readText()).jsonObject["frames"] as JsonObject)
        val withCounters = visionFrames.entries.filter { (_, e) -> (e as? JsonObject)?.containsKey("read_counters(calib=calibrate)") == true }
            .map { it.key }.toSet()
        val frames = ((oracle["frames"] as JsonObject).entries.filter { (rel, e) ->
            val screen = (((e as? JsonObject)?.get("classify") as? JsonObject)?.get("screen") as? JsonPrimitive)?.content
            !rel.endsWith(".masked.png") && (screen == Director.BOARD || rel.substringAfterLast('/').startsWith("board_"))
        }.map { it.key } + (if (calibrated) withCounters.filter { !it.endsWith(".masked.png") } else emptyList())).distinct().sorted()
        // What the oracle holds for a frame's counters, to say where a reader change would move it.
        fun oracleCounters(rel: String): Map<String, Long?>? =
            ((visionFrames[rel] as? JsonObject)?.get("read_counters(calib=calibrate)") as? JsonObject)?.let { o ->
                Vision.COUNTER_NAMES.associateWith { n -> (o[n] as? JsonPrimitive)?.content?.toLongOrNull() }
            }
        val chosen = (if (noCorpus) emptyList() else frames.filter { rel -> filters.isEmpty() || filters.any { it in rel } }) + extras
        if (chosen.isEmpty()) {
            println("no board frames${if (filters.isNotEmpty()) " for $filters" else ""}")
            exitProcess(2)
        }
        log("boardProbe  ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}  opencv ${Core.VERSION}")
        log("${chosen.size} frames: oracle/director.json `board` or a name beginning `board_`" +
            (if (filters.isNotEmpty()) ", only $filters" else "") + (if (twin) ", with twins" else ""))
        log("grid letters: " + BoardProbe.LETTERS.entries.joinToString(" ") { "${it.value}=${it.key ?: "empty"}" } +
            " @=figure (an empty cell), rows r0/r1/r2/r3/r4")
        log("rows and columns count from 0, as the oracle does: the plans' 1-based r5c2 is r4c1 here")
        log("a score is `value/need` where it did not read; need is the bar, 0.06 lower in the bottom row, " +
            "${Vision.POWERUP_BOTTOM_MIN} there for a power-up")
        log("the bottom row's pyramid score is the template's upper two thirds (F2); `whole` beside it is the whole template's")
        log("the bottom row's power-up score is the template's upper half, the orange chip's without its left " +
            "${Vision.ORANGE_BOTTOM_LEFT} columns (F10)")
        log("ms: warm, the median of three calls, one thread")
        log("")
        val v = Vision(ClassPathAssets)
        // The JIT, once, on the first frame: its numbers are thrown away, so
        // that the first frame's times are as warm as the last one's.
        repeat(2) { BoardProbe.read(repo, chosen.first(), v) }
        val boards = chosen.map { rel -> BoardProbe.read(repo, rel, v).also { b -> BoardProbe.line(b).forEach(log) } }

        // The formats: one row per frame, for the plan's table.
        log("")
        log("=".repeat(78))
        log("per frame: size, headroom, cell, figure, counters read, X, bottom row (name or best score), classify")
        for (b in boards) {
            val bottom = if (b.cands == null) "-" else (0 until Vision.COLS).joinToString(" ") { c ->
                val n = b.names!![Vision.ROWS - 1][c]
                val best = b.cands[Vision.ROWS - 1][c].firstOrNull { it.name == "pyramid" }
                (BoardProbe.LETTERS[n] ?: '#').toString() + (best?.let { BoardProbe.f(it.value, 2) } ?: "")
            }
            log(String.format(Locale.ROOT, "  %-58s %9s %4s  cell %6s  fig %-16s cnt %s  X %s  bottom %s  %s",
                b.rel.removePrefix("corpus/"), "${b.w}x${b.h}", b.room,
                b.calib?.let { BoardProbe.f(it.cellW, 1) } ?: "-", BoardProbe.figureText(b.figure),
                b.counters?.let { "${it.values.count { x -> x != null }}/7" } ?: "-",
                if (b.close != null) "yes" else "no", bottom, b.screen))
        }

        // Time per width.
        log("")
        log("=".repeat(78))
        log("ms per reader by frame width (median over the frames of that width; each frame the median of three)")
        log(String.format(Locale.ROOT, "  %6s %3s %10s %9s %10s %12s %8s", "width", "n", "calibrate", "readGrid", "findFigure", "readCounters", "cell"))
        for ((w, bs) in boards.filter { it.calib != null }.groupBy { it.w }.toSortedMap()) {
            fun m(k: String) = bs.mapNotNull { it.ms[k] }.sorted().let { it[it.size / 2] }
            log(String.format(Locale.ROOT, "  %6d %3d %10.1f %9.1f %10.1f %12.1f %8s", w, bs.size,
                m("calibrate"), m("readGrid"), m("findFigure"), m("readCounters"),
                bs.map { it.cellW }.let { "${BoardProbe.f(it.min(), 0)}-${BoardProbe.f(it.max(), 0)}" }))
        }
        val misses = boards.sumOf { it.selfCheckMisses.size }
        log("")
        log("probe against readGrid on every cell that read: $misses disagreements")

        // The bottom row (F2): the pyramid's score with the upper two thirds of
        // its template, which readGrid asks, and with the whole one, which it
        // asked until F2 -- on the cells that read a pyramid and on every other.
        log("")
        log("=".repeat(78))
        log("bottom row (F2): pyramid score, upper two thirds of the template (read) and whole (before F2), bar ${BoardProbe.f(Vision.THRESHOLDS.getValue("pyramid") - 0.06, 2)}")
        class Bot(val b: BoardProbe.Board, val c: Int, val top: Double, val whole: Double?, val read: String?)
        val bots = boards.filter { it.cands != null }.flatMap { b ->
            (0 until Vision.COLS).mapNotNull { c ->
                val top = b.cands!![Vision.ROWS - 1][c].firstOrNull { it.name == "pyramid" }?.value ?: return@mapNotNull null
                Bot(b, c, top, b.bottomWhole.getOrNull(c), b.names!![Vision.ROWS - 1][c])
            }
        }
        val pyr = bots.filter { it.read == "pyramid" }.sortedBy { it.top }
        val other = bots.filter { it.read != "pyramid" }.sortedByDescending { it.top }
        fun bt(x: Bot) = "${x.b.rel.removePrefix("corpus/")} c${x.c} ${BoardProbe.f(x.top)} (whole ${x.whole?.let { BoardProbe.f(it) } ?: "-"})"
        if (pyr.isNotEmpty()) {
            log("  read as pyramid: ${pyr.size} cells, upper part ${BoardProbe.f(pyr.first().top)} to ${BoardProbe.f(pyr.last().top)}, " +
                "whole ${BoardProbe.f(pyr.mapNotNull { it.whole }.min())} to ${BoardProbe.f(pyr.mapNotNull { it.whole }.max())}")
            pyr.take(5).forEach { log("    lowest: ${bt(it)}") }
        }
        if (other.isNotEmpty()) {
            log("  every other bottom cell: ${other.size}, upper part at most ${BoardProbe.f(other.first().top)}")
            other.take(5).forEach { log("    highest: ${bt(it)}") }
        }
        if (pyr.isNotEmpty() && other.isNotEmpty())
            log("  gap ${BoardProbe.f(pyr.first().top - other.first().top)}: the bar is ${BoardProbe.f(pyr.first().top - (Vision.THRESHOLDS.getValue("pyramid") - 0.06))} " +
                "under the lowest pyramid and ${BoardProbe.f(Vision.THRESHOLDS.getValue("pyramid") - 0.06 - other.first().top)} over the highest other cell")

        if (glyphMode) {
            // F4: every component of every counter, as segmentDigits sees it,
            // with the filter that drops it; then per width how much room each
            // rule leaves to the digits it keeps.
            log("")
            log("=".repeat(78))
            log("glyphs (F4): segmentDigits at scale 3 -- every component with its box, area, w/h, saturation (bright only),")
            log("height against the median of the boxes that reach the height test, and the first filter that drops it")
            log("rules: area >= 50, x > 1, h <= 0.95 of the crop, w/h 0.28 to 0.98, saturation <= 90 (bright), h 0.80 to 1.25 of the median")
            class Row(val w: Int, val g: BoardProbe.Glyph, val frame: String, val counter: String)
            val all = ArrayList<Row>()
            for (b in boards) {
                val cal = b.calib ?: continue
                val img = OracleFamilies.read(BoardProbe.file(repo, b.rel))
                try {
                    log("${b.rel}  ${b.w}x${b.h}  cell ${BoardProbe.f(cal.cellW, 1)}")
                    for ((key, name) in Vision.COUNTER_KEYS.zip(Vision.COUNTER_NAMES)) {
                        val gs = BoardProbe.glyphs(img, cal.roi(key), Vision.POLARITY.getValue(key))
                        // The summary is over counters that read: a counter the
                        // frame holds only part of (a V3 frame's top row) keeps
                        // the parts of its digits, and they are not a size.
                        if (b.counters?.get(name) != null) gs.forEach { all += Row(b.w, it, b.name, name) }
                        log("    %-11s %-6s read %-6s crop h %3d: %s".format(name, Vision.POLARITY.getValue(key),
                            b.counters?.get(name)?.toString() ?: "null", gs.firstOrNull()?.rows ?: 0,
                            gs.filter { it.area >= 15 }.joinToString("  ") { g ->
                                "${g.verdict}(@${g.x},${g.y} ${g.w}x${g.h} a${g.area} r${BoardProbe.f(g.ratio, 2)}" +
                                    (g.relH?.let { " h${BoardProbe.f(it, 2)}" } ?: "") + (g.sat?.let { " s${BoardProbe.f(it, 0)}" } ?: "") + ")"
                            }))
                    }
                    if (maskMode) {
                        // The meter label as segmentDigits sees it: the crop, grey, 3x
                        // cubic, the bright bar -- side by side with the crop itself.
                        val (x, y, w, h) = cal.roi("roi_meters")
                        val crop = Py.crop(img, maxOf(0, y), y + h, maxOf(0, x), x + w)
                        if (crop != null) {
                            val big = Mat(); Imgproc.resize(crop, big, Size(), 3.0, 3.0, Imgproc.INTER_CUBIC)
                            val grey = Mat(); Imgproc.resize(Cv.gray(crop), grey, Size(), 3.0, 3.0, Imgproc.INTER_CUBIC)
                            val bin = Mat(); Core.inRange(grey, org.opencv.core.Scalar(175.0), org.opencv.core.Scalar(255.0), bin)
                            val binC = Mat(); Imgproc.cvtColor(bin, binC, Imgproc.COLOR_GRAY2BGR)
                            val both = Mat(); Core.vconcat(listOf(big, binC), both)
                            val dir = File(repo, "core/build/boardProbe_meters").also { it.mkdirs() }
                            org.opencv.imgcodecs.Imgcodecs.imwrite(File(dir, b.name.removeSuffix(".png") + "_meters.png").path, both)
                            listOf(big, grey, bin, binC, both).forEach { it.release() }
                        }
                    }
                } finally {
                    img.release()
                }
            }
            log("")
            log("per width, over the counters that read: the digits kept (area min, w/h min..max, h/median min..max) and the dropped nearest to passing")
            for ((w, rows) in all.groupBy { it.w }.toSortedMap()) {
                val kept = rows.filter { it.g.verdict == "kept" }
                if (kept.isEmpty()) { log("  $w: nothing kept"); continue }
                val aMin = kept.minBy { it.g.area }
                val rMin = kept.minBy { it.g.ratio }; val rMax = kept.maxBy { it.g.ratio }
                val hMin = kept.minBy { it.g.relH!! }; val hMax = kept.maxBy { it.g.relH!! }
                val bigDropped = rows.filter { it.g.verdict == "area" }.maxByOrNull { it.g.area }
                val low = rows.filter { it.g.verdict == "height" && it.g.relH!! < 0.80 }.maxByOrNull { it.g.relH!! }
                val high = rows.filter { it.g.verdict == "height" && it.g.relH!! > 1.25 }.minByOrNull { it.g.relH!! }
                fun hx(r: Row?) = r?.let { "${BoardProbe.f(it.g.relH!!, 2)} ${it.g.w}x${it.g.h} (${it.counter})" } ?: "-"
                log(String.format(Locale.ROOT, "  %5d  %3d digits  area min %4d (%s %s, %.1fx the 50)  w/h %.2f..%.2f  h/median %.2f..%.2f" +
                    "  | largest dropped for area %s  | dropped for height, highest under 0.80 %s, lowest over 1.25 %s",
                    w, kept.size, aMin.g.area, aMin.frame.take(34), aMin.counter, aMin.g.area / 50.0,
                    rMin.g.ratio, rMax.g.ratio, hMin.g.relH, hMax.g.relH,
                    bigDropped?.let { "${it.g.area} (${it.counter})" } ?: "-", hx(low), hx(high)))
            }
        }

        if (meterMode) {
            // F25: where each counter's digits touch ([MeterGlyphs]).
            log("")
            log("=".repeat(78))
            log("meters (F25): per counter the digits kept (x+w at scale 3), each gap's columns, valley v (the lowest of the")
            log("columns' brightest pixel across the gap -- the bar is 175, and a valley at or over it joins the two) and ground g")
            log("(the darkest pixel between them); WIDE = a component as tall as a digit (h/median) dropped for w/h > 0.98, with")
            log("the columns where it would split into digits of the kept digits' median width (pixels there, the bridge's")
            log("brightest pixel) and the pieces' widths; for a dark counter v and g are 255 - grey against 115")
            class M(val b: BoardProbe.Board, val name: String, val r: MeterGlyphs.Result)
            val ms = ArrayList<M>()
            // `meterPad=<share of a cell's height>`: the metre label read once more with its
            // box that much taller at the bottom, beside the reading as it is.
            val pads = args.filter { it.startsWith("meterPad=") }.map { it.removePrefix("meterPad=").toDouble() }
            val padMoves = ArrayList<String>()
            for (b in boards) {
                val cal = b.calib ?: continue
                val img = OracleFamilies.read(BoardProbe.file(repo, b.rel))
                try {
                    log("${b.rel}  ${b.w}x${b.h}  cell ${BoardProbe.f(cal.cellW, 1)}x${BoardProbe.f(cal.cellH, 1)}  gridY0 ${BoardProbe.f(cal.gridY0, 1)}")
                    for ((key, name) in Vision.COUNTER_KEYS.zip(Vision.COUNTER_NAMES)) {
                        val r = MeterGlyphs.measure(img, cal.roi(key), Vision.POLARITY.getValue(key)) ?: continue
                        ms += M(b, name, r)
                        // The metre label hangs from the grid's bottom edge: where its digits
                        // stand under that edge, in cell heights (since F26 the roi hangs from five rows of cellW / ASPECT).
                        val hang = if (key != "roi_meters" || r.kept.isEmpty()) "" else {
                            val gridBottom = cal.gridY0 + Vision.ROWS * cal.cellH
                            val y0 = cal.roi(key)[1]
                            "  label %s..%s cellH above the grid's bottom".format(
                                MeterGlyphs.f((gridBottom - (y0 + r.kept.minOf { it.y } / 3.0)) / cal.cellH, 3),
                                MeterGlyphs.f((gridBottom - (y0 + r.kept.maxOf { it.bottom } / 3.0)) / cal.cellH, 3))
                        }
                        // What the reader makes of each glyph it hands on (after any split).
                        val scores = if (key != "roi_meters") "" else "  glyphs " +
                            v.digitScores(img, key, cal).joinToString(" ") { (d, s) -> "${d ?: '?'}:${MeterGlyphs.f(s, 3)}" }
                        log("    %-11s read %-6s roi %s  %s%s%s".format(name, b.counters?.get(name)?.toString() ?: "null",
                            cal.roi(key).toList(), MeterGlyphs.line(r), hang, scores))
                        if (key == "roi_meters") for (pad in pads) {
                            val box = cal.roi(key)
                            val rois = LinkedHashMap(cal.rois)
                            rois[key] = intArrayOf(box[0], box[1], box[2], box[3] + Math.round(pad * cal.cellH).toInt())
                            val padded = Calib(cal.card, cal.gridX0, cal.gridY0, cal.cellW, cal.cellH, rois, cal.skillButton, cal.skillRadius)
                            val value = v.readNumber(img, key, padded)
                            val rp = MeterGlyphs.measure(img, padded.roi(key), "bright")
                            log("      pad %s: read %s  bottom margin %s  %s".format(MeterGlyphs.f(pad, 3), value ?: "null",
                                rp?.bottomMargin ?: "-", v.digitScores(img, key, padded).joinToString(" ") { (d, s) -> "${d ?: '?'}:${MeterGlyphs.f(s, 3)}" }))
                            if (value != b.counters?.get(name)) padMoves += "pad ${MeterGlyphs.f(pad, 3)} ${b.name}: ${b.counters?.get(name)} -> $value"
                        }
                    }
                } finally {
                    img.release()
                }
            }
            log("")
            log("per counter, over the frames where it read: the highest valley of a gap that stayed open, the lowest ground,")
            log("the fewest columns; and every WIDE component anywhere")
            for ((name, rows) in ms.groupBy { it.name }) {
                val read = rows.filter { it.b.counters?.get(name) != null }
                val gaps = read.flatMap { m -> m.r.gaps.filter { it.cols > 0 }.map { m to it } }
                val hi = gaps.maxByOrNull { it.second.valley }
                val few = gaps.minByOrNull { it.second.cols }
                log("  %-11s %3d frames read, %4d gaps: valley max %s  ground min %s  columns min %s".format(name, read.size, gaps.size,
                    hi?.let { "${it.second.valley} (${it.first.b.name})" } ?: "-",
                    gaps.minByOrNull { it.second.ground }?.let { "${it.second.ground} (${it.first.b.name})" } ?: "-",
                    few?.let { "${it.second.cols} (${it.first.b.name})" } ?: "-"))
                val margins = rows.mapNotNull { m -> m.r.bottomMargin?.let { m to it } }
                margins.minByOrNull { it.second }?.let { (m, v) ->
                    log("  %-11s digits' bottom margin in the crop: min %d of %d rows (%s), median %d".format(name, v, m.r.rows, m.b.name,
                        margins.map { it.second }.sorted()[margins.size / 2]))
                }
                val tops = rows.mapNotNull { m -> m.r.topMargin?.let { m to it } }
                tops.minByOrNull { it.second }?.let { (m, v) ->
                    log("  %-11s digits' top in the crop: min %d (%s), median %d".format(name, v, m.b.name, tops.map { it.second }.sorted()[tops.size / 2]))
                }
                for (m in rows) for (wd in m.r.wides) {
                    log("    WIDE %-11s %s read %s: %s".format(name, m.b.name, m.b.counters?.get(name)?.toString() ?: "null",
                        "@${wd.c.x} ${wd.c.w}x${wd.c.h} h${MeterGlyphs.f(wd.relH)} n${wd.n} sat ${wd.c.sat?.let { MeterGlyphs.f(it, 0) } ?: "-"}" +
                            (if (wd.splits.isNotEmpty()) " split " + wd.splits.joinToString(",") { "@${it.at} px${it.count}/${wd.c.h} " +
                                "(${MeterGlyphs.f(it.count / wd.c.h.toDouble())}) bridge${it.bridge}" } +
                                " pieces ${wd.pieces.joinToString("/")} digitW ${m.r.digitW?.let { MeterGlyphs.f(it, 1) }}" else "")))
                }
            }
            if (pads.isNotEmpty()) {
                log("")
                log("the metre label with its box taller at the bottom: ${padMoves.size} readings differ from the box as it is")
                padMoves.forEach { log("  $it") }
            }
            if (hangMode) {
                // F26: where the label's digits stand, found in a band wide enough for any
                // frame (0.45 cellH over the grid's bottom to 0.10 under it), against three
                // anchors, in cell widths: the grid's bottom as calibrate has it (five fitted
                // row heights), the bottom with the row height the aspect gives the cell
                // width, and the card's top.
                log("")
                log("hang (F26): the label's digits found in a wide band; top and bottom in frame px, and against")
                log("  g = gridY0 + 5 cellH (the box's anchor now), a = gridY0 + 5 cellW/ASPECT, c = the card's top + its height; all / cellW")
                class H(val b: BoardProbe.Board, val read: Long?, val top: Double, val bot: Double, val g: Double, val a: Double, val c: Double)
                val hs = ArrayList<H>()
                for (b in boards) {
                    val cal = b.calib ?: continue
                    val img = OracleFamilies.read(BoardProbe.file(repo, b.rel))
                    try {
                        val gBottom = cal.gridY0 + Vision.ROWS * cal.cellH
                        val box = cal.roi("roi_meters")
                        val y0 = Math.round(gBottom - 0.45 * cal.cellH).toInt()
                        val y1 = Math.round(gBottom + 0.10 * cal.cellH).toInt()
                        val wide = intArrayOf(box[0], y0, box[2], y1 - y0)
                        val rois = LinkedHashMap(cal.rois); rois["roi_meters"] = wide
                        val wcal = Calib(cal.card, cal.gridX0, cal.gridY0, cal.cellW, cal.cellH, rois, cal.skillButton, cal.skillRadius)
                        val read = v.readNumber(img, "roi_meters", wcal)
                        val r = MeterGlyphs.measure(img, wide, "bright")
                        if (r == null || r.kept.size < 3) { log("  ${b.name}: no digits in the wide band"); continue }
                        val top = y0 + r.kept.minOf { it.y } / 3.0
                        val bot = y0 + r.kept.maxOf { it.bottom } / 3.0
                        val aBottom = cal.gridY0 + Vision.ROWS * cal.cellW / Vision.ASPECT
                        val cBottom = (cal.card[1] + cal.card[3]).toDouble()
                        val h = H(b, read, top, bot, (gBottom - bot) / cal.cellW, (aBottom - bot) / cal.cellW, (cBottom - bot) / cal.cellW)
                        hs += h
                        log("  %-62s %9s read %-6s now %-6s cellW %6.1f cellH %6.1f  digits %7.1f..%7.1f (%4.1f px)  g %.4f  a %.4f  c %.4f".format(
                            b.name.take(62), "${b.w}x${b.h}", read ?: "null", b.counters?.get("meters") ?: "null", cal.cellW, cal.cellH,
                            top, bot, bot - top, h.g, h.a, h.c))
                    } finally {
                        img.release()
                    }
                }
                fun spread(name: String, xs: List<Double>) {
                    val s = xs.sorted()
                    log("  %s: %.4f .. %.4f (spread %.4f), median %.4f".format(name, s.first(), s.last(), s.last() - s.first(), s[s.size / 2]))
                }
                log("")
                log("the digits' bottom under each anchor, in cell widths, over ${hs.size} frames:")
                spread("g, the grid's bottom  ", hs.map { it.g })
                spread("a, 5 cellW/ASPECT     ", hs.map { it.a })
                spread("c, the card's bottom  ", hs.map { it.c })
                log("  the digits' height / cellW: %.4f .. %.4f".format(hs.minOf { (it.bot - it.top) / it.b.calib!!.cellW },
                    hs.maxOf { (it.bot - it.top) / it.b.calib!!.cellW }))

                // The candidate boxes, each read on every frame: the box as it is (the
                // grid's bottom, five fitted row heights), the same box 0.025 cellH taller
                // at the bottom, the box hung from five row heights the aspect gives the
                // cell width, and that one 0.025 of its row taller.
                log("")
                log("candidate metre boxes on every frame: a reading that differs from the box as it is, and the digits' bottom margin (rows at scale 3)")
                fun boxOf(cal: Calib, rowH: Double, pad: Double): IntArray {
                    val bottom = cal.gridY0 + Vision.ROWS * rowH
                    val midX = cal.gridX0 + 2.5 * cal.cellW
                    return intArrayOf(Py.int(midX - 0.85 * cal.cellW), Py.int(bottom - 0.325 * rowH),
                                      Py.int(1.70 * cal.cellW), Py.int((0.30 + pad) * rowH))
                }
                val variants = listOf<Pair<String, (Calib) -> IntArray>>(
                    "grid +0.025" to { c -> boxOf(c, c.cellH, 0.025) },
                    "aspect" to { c -> boxOf(c, c.cellW / Vision.ASPECT, 0.0) },
                    "aspect +0.025" to { c -> boxOf(c, c.cellW / Vision.ASPECT, 0.025) },
                )
                val moves = variants.associate { it.first to ArrayList<String>() }
                val margins = variants.associate { it.first to ArrayList<Pair<Int, String>>() }
                for (b in boards) {
                    val cal = b.calib ?: continue
                    val img = OracleFamilies.read(BoardProbe.file(repo, b.rel))
                    try {
                        val now = b.counters?.get("meters")
                        for ((name, box) in variants) {
                            val roi = box(cal)
                            val rois = LinkedHashMap(cal.rois); rois["roi_meters"] = roi
                            val vc = Calib(cal.card, cal.gridX0, cal.gridY0, cal.cellW, cal.cellH, rois, cal.skillButton, cal.skillRadius)
                            val read = v.readNumber(img, "roi_meters", vc)
                            if (read != now) moves.getValue(name) += "${b.name}: $now -> $read  (box ${cal.roi("roi_meters").toList()} -> ${roi.toList()})"
                            if (read != null) MeterGlyphs.measure(img, roi, "bright")?.bottomMargin?.let { margins.getValue(name) += it to b.name }
                        }
                    } finally {
                        img.release()
                    }
                }
                for ((name, _) in variants) {
                    val m = margins.getValue(name).sortedBy { it.first }
                    log("  %-14s %d readings differ; bottom margin where it reads: min %s, median %s".format(name, moves.getValue(name).size,
                        m.firstOrNull()?.let { "${it.first} (${it.second})" } ?: "-", m.getOrNull(m.size / 2)?.first ?: "-"))
                    moves.getValue(name).forEach { log("      $it") }
                }
            }
            // The counters as the readers read them now, against what oracle/vision.json holds.
            val compared = boards.filter { oracleCounters(it.rel) != null }
            val moved = compared.filter { b -> oracleCounters(b.rel) != (b.counters ?: Vision.COUNTER_NAMES.associateWith { null }) }
            log("")
            log("against oracle/vision.json: ${compared.size} frames compared, ${moved.size} read differently")
            for (b in moved) {
                val o = oracleCounters(b.rel)!!
                log("  ${b.rel}: " + Vision.COUNTER_NAMES.filter { o[it] != b.counters?.get(it) }
                    .joinToString(", ") { "$it ${o[it]} -> ${b.counters?.get(it)}" })
            }
        }

        if (objectMode) {
            // F10: the bottom row's objects against every template, whole and cut.
            val cellsDir = File(repo, "core/build/boardProbe_cells")
            cellsDir.deleteRecursively()
            val cells = boards.filter { it.calib != null }.flatMap { b ->
                val img = OracleFamilies.read(BoardProbe.file(repo, b.rel))
                try {
                    BoardObjects.measure(v, img, b.calib!!, b.rel, b.names!!, b.figure, cellsDir)
                } finally {
                    img.release()
                }
            }
            BoardObjects.report(cells, log)
            val syn = boards.filter { it.calib != null }.flatMap { b ->
                val img = OracleFamilies.read(BoardProbe.file(repo, b.rel))
                try {
                    BoardObjects.synthetic(v, img, b.calib!!, b.rel, b.names!!, b.figure, File(cellsDir, "ledge"))
                } finally {
                    img.release()
                }
            }
            BoardObjects.reportSynthetic(syn, cells, log)
            // Where the ledge begins, and where each power-up template's cut ends, both in cell heights from the cell's top.
            val tops = boards.filter { it.calib != null }.flatMap { b ->
                val img = OracleFamilies.read(BoardProbe.file(repo, b.rel))
                try {
                    (0 until Vision.COLS).filter { c -> b.names!![Vision.ROWS - 1][c] == null && !b.colourful!![Vision.ROWS - 1][c] }
                        .mapNotNull { c -> BoardObjects.ledgeTop(v, img, b.calib!!, c) }
                } finally {
                    img.release()
                }
            }.sorted()
            log("")
            log("9. the ledge's highest point in a plain bottom cell, from the cell's top in cell heights: ${tops.size} cells, " +
                "${BoardProbe.f(tops.first())} .. ${BoardProbe.f(tops[tops.size / 2])} .. ${BoardProbe.f(tops.last())}")
            val tpls = v.loadTemplates()
            for (t in Vision.POWERUPS) {
                val cs = cells.filter { it.read == t && !it.bottom }
                if (cs.isEmpty()) continue
                val rows = tpls.getValue(t).rows()
                log("   %-14s place y %s..%s; the cut's last row (place + share x template height): %s".format(t,
                    BoardProbe.f(cs.minOf { it.placeY[t]!! }), BoardProbe.f(cs.maxOf { it.placeY[t]!! }),
                    listOf(1.0, 0.6, 0.55, 0.5, 0.45, 0.4).joinToString("  ") { fc ->
                        "${BoardObjects.cutName(fc)} ${BoardProbe.f(cs.maxOf { it.placeY[t]!! + fc * rows * (it.cellW / BoardProbe.REF_CELL_W) / it.cellH })}" }))
            }
            log("(cell crops: $cellsDir)")
        }

        if (twin) {
            // Twins need every reference, filtered or not.
            val refs = (if (filters.isEmpty()) boards else frames.filter { it.substringAfterLast('/').startsWith("board_1080x1920_") }
                .map { rel -> boards.firstOrNull { it.rel == rel } ?: BoardProbe.read(repo, rel, v) }).filter { it.isRef }
            log("")
            log("=".repeat(78))
            log("twins: every frame beside a 1080 x 1920 frame of its scene (paws, meters); every cell that reads differently")
            log("references: " + refs.joinToString(", ") { "${it.name} (paws ${it.scene.first}, meters ${it.scene.second})" })
            val scenes = boards.groupBy { it.scene }
            log("scenes: " + scenes.entries.joinToString("; ") { (k, bs) -> "paws ${k.first} meters ${k.second}: ${bs.size}" })
            var same = 0; var differ = 0
            val orphans = ArrayList<String>()
            val cellTally = HashMap<String, Int>()
            for (b in boards) {
                val t = BoardProbe.twin(b, refs)
                if (t == null) { orphans += "${b.name} (${BoardProbe.countersLine(b)})"; continue }
                val lines = BoardProbe.twinLines(b, t.first, t.second + if (b.isRef) "; 1920 against 1920" else "")
                lines.forEach(log)
                if (lines.size == 1 && lines[0].endsWith("the same")) same += 1 else differ += 1
                lines.drop(1).filter { it.trimStart().startsWith("r") }.forEach { l ->
                    val cell = l.trim().substringBefore(' ')
                    val mark = when {
                        "wings" in l -> "beside the figure"
                        "bottom row" in l -> "bottom row"
                        else -> "FORMAT?"
                    }
                    cellTally.merge("$cell $mark", 1, Int::plus)
                }
            }
            log("")
            log("twins: $same the same, $differ differing, ${orphans.size} without a twin")
            cellTally.entries.sortedByDescending { it.value }.forEach { log("  ${it.key}: ${it.value} frames") }
            if (orphans.isNotEmpty()) { log("without a twin:"); orphans.forEach { log("  $it") } }
        }
    } finally {
        val out = File(repo, "core/build/boardProbe.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println("(the whole report: $out)")
    }
}
