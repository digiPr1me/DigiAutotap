package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.Locale

/**
 * Objects the board covers from below (PLAN_WORLD_SEARCH_FORMATE.md F12,
 * F16, F17 and F14, the claws they cost), `gradlew :core:coverProbe`.
 *
 *   gradlew :core:coverProbe --no-daemon
 *   gradlew :core:coverProbe --no-daemon --args="extra=<dir or png> extra=... crops"
 *   gradlew :core:coverProbe --no-daemon --args="labels=<file> ..."
 *
 * Every board frame of the corpus (oracle/director.json `board`, or a name
 * that begins `board_`) and every frame named by `extra=` is read as the
 * skill reads it -- `calibrate`, `find_figure`, `read_grid` with the figure --
 * and then every cell is asked more than `readGrid` asks: the pyramid
 * template whole and cut to its upper rows (the cut `readGrid` asks in the
 * bottom row since F2), the arrow template, every power-up whole and cut to
 * its upper half (the bottom row's since F10), the colour prefilter's share
 * and the figure's body share -- beside what stands in the cell under it and
 * over it as `readGrid` read them. One line per cell goes to
 * `core/build/coverProbe.tsv`; with `crops`, the search patch of every cell a
 * pyramid or the arrow scores 0.45 on, and of every cell over the figure, goes
 * to `core/build/coverProbe_cells/`, named by that line's id, for the eye.
 *
 * With `labels=`, a file of `<id> <label>` lines (what the eye said: pyramid,
 * none, chip, ...), the report weighs the ways of asking a cell against the
 * labels: the lowest real pyramid and the highest cell that is none, per way.
 *
 * Nothing here is a reader: the scores are `Vision.readGrid`'s arithmetic
 * (the same patch, the same scaling, `TM_CCOEFF_NORMED`). Nothing is written
 * but the report, the table and the crops.
 */
object CoverProbe {

    /** The pyramid template's upper rows kept, from the top. */
    val PYR_CUTS = listOf(1.0, 0.9, 0.8, 0.75, 2.0 / 3.0, 0.6, 0.55, 0.5, 0.45, 0.4, 1.0 / 3.0)

    fun cutName(f: Double) = BoardObjects.cutName(f)

    class Row(
        val id: Int, val frame: String, val w: Int, val h: Int, val cellW: Double, val cellH: Double,
        val r: Int, val c: Int,
        /** readGrid's name, "figure" on the figure's own cell. */
        val read: String?,
        /** "fig", "above" (the figure is in the cell under it), "cross", "diag" or "" */
        val rel: String,
        val colourful: Boolean, val share: Double, val body: Double,
        val pyr: Map<Double, Double>,
        val arrow: Double?, val arrowTop: Double?,
        val bestPu: String?, val bestPuScore: Double?,
        val bestPuHalf: String?, val bestPuHalfScore: Double?,
        /** The cell under it and over it, as readGrid read them; "figure", "ledge", "top". */
        val below: String?, val above: String?,
        /** The pyramid's upper two thirds in the cell under it. */
        val belowPyrTop: Double?,
        /** Every power-up's whole template and its bottom-row part (the upper half, the chip cropped). */
        val pu: Map<String, Double> = emptyMap(),
        val puHalf: Map<String, Double> = emptyMap(),
    ) {
        val name get() = frame.substringAfterLast('/')
        fun p(f: Double) = pyr[f]
    }

    private fun f(x: Double?, d: Int = 3) = if (x == null || x.isNaN()) "-" else String.format(Locale.ROOT, "%.${d}f", x)

    fun measure(v: Vision, id0: Int, rel: String, file: File, cellsDir: File?): List<Row> {
        val img = OracleFamilies.read(file)
        require(!img.empty()) { "cannot read $rel" }
        try {
            val calib = try { v.calibrate(img) } catch (e: CalibrationError) { return emptyList() }
            val all = v.loadTemplates()
            val board = v.boardTemplates()
            val figure = v.findFigure(img, calib, all)
            val names = v.readGrid(img, calib, board, figure = figure).first
            val pyrTpl = all.getValue("pyramid")
            val arrowTpl = all["arrow"]
            fun nameAt(r: Int, c: Int): String? = when {
                r < 0 -> "top"
                r >= Vision.ROWS -> "ledge"
                figure != null && figure.row == r && figure.col == c -> "figure"
                else -> names[r][c]
            }
            val out = ArrayList<Row>()
            var id = id0
            val pyrTop = HashMap<Pair<Int, Int>, Double>()
            val cells = ArrayList<Triple<Int, Int, Map<Double, Double>>>()
            for (r in 0 until Vision.ROWS) for (c in 0 until Vision.COLS) {
                val patch = v.searchPatch(img, calib, r, c) ?: continue
                val pyr = LinkedHashMap<Double, Double>()
                for (fc in PYR_CUTS) {
                    val t = BoardProbe.scaledAny("pyramid@$fc", BoardObjects.cut("pyramid", pyrTpl, fc), calib.cellW)
                    BoardObjects.matchLoc(patch, t)?.let { pyr[fc] = it.first }
                }
                pyrTop[r to c] = pyr[2.0 / 3.0] ?: Double.NaN
                cells += Triple(r, c, pyr)
            }
            for ((r, c, pyr) in cells) {
                val patch = v.searchPatch(img, calib, r, c)!!
                val colourful = v.hasObjectColour(img, calib, r, c)
                val share = BoardObjects.colourShare(v, img, calib, r, c)
                val body = v.figureBodyFraction(img, calib, r, c)
                val arrow = arrowTpl?.let { t -> BoardObjects.matchLoc(patch, BoardProbe.scaledAny("arrow@1.0", t, calib.cellW))?.first }
                val arrowTop = arrowTpl?.let { t ->
                    BoardObjects.matchLoc(patch, BoardProbe.scaledAny("arrow@0.667", BoardObjects.cut("arrow", t, 2.0 / 3.0), calib.cellW))?.first }
                var bestPu: String? = null; var bestPuScore: Double? = null
                var bestHalf: String? = null; var bestHalfScore: Double? = null
                val pu = LinkedHashMap<String, Double>(); val puHalf = LinkedHashMap<String, Double>()
                for (n in Vision.POWERUPS) {
                    val t = all[n] ?: continue
                    BoardObjects.matchLoc(patch, BoardProbe.scaledAny("$n@1.0", t, calib.cellW))?.first?.let { s ->
                        pu[n] = s
                        if (bestPuScore == null || s > bestPuScore) { bestPu = n; bestPuScore = s }
                    }
                    BoardObjects.matchLoc(patch, BoardProbe.scaledAny("$n^half", v.bottomPart(n, t), calib.cellW))?.first?.let { s ->
                        puHalf[n] = s
                        if (bestHalfScore == null || s > bestHalfScore) { bestHalf = n; bestHalfScore = s }
                    }
                }
                val isFig = figure != null && figure.row == r && figure.col == c
                val relTo = when {
                    figure == null -> ""
                    isFig -> "fig"
                    figure.row == r + 1 && figure.col == c -> "above"
                    Math.abs(figure.row - r) + Math.abs(figure.col - c) == 1 -> "cross"
                    Math.abs(figure.row - r) == 1 && Math.abs(figure.col - c) == 1 -> "diag"
                    else -> ""
                }
                val row = Row(id, rel, img.cols(), img.rows(), calib.cellW, calib.cellH, r, c,
                    if (isFig) "figure" else names[r][c], relTo, colourful, share, body, pyr, arrow, arrowTop,
                    bestPu, bestPuScore, bestHalf, bestHalfScore,
                    nameAt(r + 1, c), nameAt(r - 1, c), pyrTop[r + 1 to c], pu, puHalf)
                out += row
                val maxPyr = pyr.values.maxOrNull() ?: 0.0
                if (cellsDir != null && (maxPyr >= 0.45 || (arrow ?: 0.0) >= 0.45 || relTo == "above" || colourful ||
                        row.read != null)) {
                    cellsDir.mkdirs()
                    Imgcodecs.imwrite(File(cellsDir, "%05d.png".format(id)).path, patch)
                }
                id += 1
            }
            return out
        } finally {
            img.release()
        }
    }

    fun header(): String = (listOf("id", "frame", "w", "h", "cellW", "r", "c", "read", "rel", "colourful", "share", "body") +
        PYR_CUTS.map { "pyr_" + cutName(it) } + listOf("arrow", "arrow_2/3", "bestPu", "bestPuScore",
        "bestPuHalf", "bestPuHalfScore", "below", "above", "belowPyrTop") +
        Vision.POWERUPS.map { "w_$it" } + Vision.POWERUPS.map { "h_$it" }).joinToString("\t")

    fun tsv(r: Row): String = (listOf(r.id.toString(), r.frame, r.w.toString(), r.h.toString(), f(r.cellW, 2),
        r.r.toString(), r.c.toString(), r.read ?: "-", r.rel.ifEmpty { "-" }, if (r.colourful) "1" else "0",
        f(r.share, 2), f(r.body, 2)) + PYR_CUTS.map { f(r.p(it), 4) } +
        listOf(f(r.arrow, 4), f(r.arrowTop, 4), r.bestPu ?: "-", f(r.bestPuScore, 4), r.bestPuHalf ?: "-",
            f(r.bestPuHalfScore, 4), r.below ?: "-", r.above ?: "-", f(r.belowPyrTop, 4)) +
        Vision.POWERUPS.map { f(r.pu[it], 4) } + Vision.POWERUPS.map { f(r.puHalf[it], 4) }).joinToString("\t")
}

fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val crops = "crops" in args
    val extras = args.filter { it.startsWith("extra=") }.map { File(it.removePrefix("extra=")) }
    val only = args.filter { it.startsWith("only=") }.map { it.removePrefix("only=") }
    val noCorpus = "nocorpus" in args
    val report = StringBuilder()
    val log: (String) -> Unit = { line -> println(line); report.append(line).append('\n') }
    try {
        val frames = ArrayList<Pair<String, File>>()
        if (!noCorpus) {
            val oracle = Json.parseToJsonElement(File(repo, "oracle/director.json").readText()).jsonObject
            (oracle["frames"] as JsonObject).entries.filter { (rel, e) ->
                val screen = (((e as? JsonObject)?.get("classify") as? JsonObject)?.get("screen") as? JsonPrimitive)?.content
                !rel.endsWith(".masked.png") && (screen == Director.BOARD || rel.substringAfterLast('/').startsWith("board_"))
            }.map { it.key }.sorted().forEach { frames += it to File(repo, it) }
        }
        for (e in extras) {
            val files = if (e.isDirectory) e.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name } else listOf(e)
            for (file in files) {
                val size = OracleFamilies.pngSize(file) ?: continue
                if (!OracleFamilies.isFrame(size.first, size.second)) continue
                frames += (e.name + "/" + file.name).let { if (e.isDirectory) it else file.name } to file
            }
        }
        if ("oracle" in args) {
            // The reader as it is against oracle/vision.json's read_grid, frame by
            // frame: every cell whose name or score (to four places) differs --
            // what writeOracle would write, without writing it.
            val key = "read_grid(calib=calibrate,templates=board_templates,figure=find_figure)"
            val vo = Json.parseToJsonElement(File(repo, "oracle/vision.json").readText()).jsonObject["frames"] as JsonObject
            val v = Vision(ClassPathAssets)
            var moved = 0; var cellsMoved = 0; var asked = 0
            for ((rel, e) in vo.entries.sortedBy { it.key }) {
                val ans = (e as? JsonObject)?.get(key) as? kotlinx.serialization.json.JsonArray ?: continue
                if (only.isNotEmpty() && only.none { it in rel }) continue
                asked += 1
                val img = OracleFamilies.read(File(repo, rel))
                try {
                    val calib = v.calibrate(img)
                    val fig = v.findFigure(img, calib, v.loadTemplates())
                    val (names, scores) = v.readGrid(img, calib, v.boardTemplates(), figure = fig)
                    val oNames = ans[0] as kotlinx.serialization.json.JsonArray
                    val oScores = ans[1] as kotlinx.serialization.json.JsonArray
                    val diffs = ArrayList<String>()
                    for (r in 0 until Vision.ROWS) for (c in 0 until Vision.COLS) {
                        val on = ((oNames[r] as kotlinx.serialization.json.JsonArray)[c] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        val os = ((oScores[r] as kotlinx.serialization.json.JsonArray)[c] as JsonPrimitive).content.toBigDecimal()
                        val nn = names[r][c]
                        val ns = Oracle.round(scores[r][c])
                        if (on != nn || os.compareTo(ns) != 0)
                            diffs += "r${r}c$c ${on ?: "-"} $os -> ${nn ?: "-"} $ns"
                    }
                    if (diffs.isNotEmpty()) {
                        moved += 1; cellsMoved += diffs.size
                        log("  $rel: ${diffs.joinToString("; ")}")
                    }
                } finally {
                    img.release()
                }
            }
            log("read_grid against oracle/vision.json: $asked frames asked, $moved moved, $cellsMoved cells")
            return
        }
        val chosen = frames.filter { (rel, _) -> only.isEmpty() || only.any { it in rel } }
        log("coverProbe  ${chosen.size} frames")
        val cellsDir = if (crops) File(repo, "core/build/coverProbe_cells").also { it.deleteRecursively() } else null
        val v = Vision(ClassPathAssets)
        val rows = ArrayList<CoverProbe.Row>()
        for ((rel, file) in chosen) {
            val rs = CoverProbe.measure(v, rows.size, rel, file, cellsDir)
            if (rs.isEmpty()) log("  $rel: calibrate raises")
            rows += rs
        }
        val tsv = File(repo, "core/build/coverProbe.tsv")
        tsv.parentFile.mkdirs()
        tsv.writeText(CoverProbe.header() + "\n" + rows.joinToString("\n") { CoverProbe.tsv(it) } + "\n")
        log("${rows.size} cells of ${rows.map { it.frame }.toSet().size} frames -> $tsv" + (cellsDir?.let { " (crops $it)" } ?: ""))
    } finally {
        val out = File(repo, "core/build/coverProbe.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
    }
}
