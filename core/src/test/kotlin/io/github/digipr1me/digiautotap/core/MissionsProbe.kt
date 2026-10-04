package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.system.exitProcess

/**
 * The numbers behind the readers of [Missions] (PLAN_EX_MISSIONS.md 4.1),
 * printed before a threshold was chosen. Reads, writes nothing.
 *
 *   gradlew :core:missionsProbe --no-daemon --args="tile main staging/formats"
 *   gradlew :core:missionsProbe --no-daemon --args="window staging/missions"
 *
 * The first argument is what to measure: `tile` (every yellow clump in the
 * right-hand HUD column with the pale board under it and the navy around
 * it), `window` (the tab row's blobs, the lit tab, the header block and the
 * Claim buttons). Every other argument is a picture, a folder, or `main`:
 * every frame of the corpus that `oracle/director.json` calls the main
 * screen. `mask=0.06:0.155:0.005` asks again with the dot and its plate
 * masked in at every fy of that range, as readerProbe does.
 */
fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    if (args.size < 2 && args.firstOrNull() != "director") {
        println("missionsProbe wants a mode and pictures: `tile main staging/formats`")
        exitProcess(2)
    }
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val mode = args[0]
    if (mode == "director") {
        MissionsProbe.director(repo)
        return
    }
    val places: List<Double?> = args.drop(1).firstOrNull { it.startsWith("mask=") }?.removePrefix("mask=")
        ?.split(':')?.map { it.toDouble() }
        ?.let { (a, b, step) -> generateSequence(a) { it + step }.takeWhile { it <= b + 1e-9 }.toList() }
        ?: listOf(null)
    val files = ArrayList<File>()
    for (arg in args.drop(1).filter { !it.startsWith("mask=") }) {
        if (arg == "main") {
            val director = Json.parseToJsonElement(File(repo, "oracle/director.json").readText()).jsonObject
            val frames = director["frames"]!!.jsonObject
            for ((rel, entry) in frames) {
                val screen = ((entry as JsonObject)["classify"] as? JsonObject)?.get("screen")?.jsonPrimitive?.content
                if (screen == Director.MAIN) files.add(File(repo, rel))
            }
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
            val img = if (fy == null) raw else OracleFamilies.copy(raw).also { Dot.mask(it, MissionsProbe.dot(it, fy)) }
            val at = if (fy == null) "" else "[fy %.3f] ".format(fy)
            val name = file.path.replace('\\', '/').substringAfter("corpus/").substringAfter("staging/")
            when (mode) {
                "tile" -> MissionsProbe.tile(img, "$at$name")
                "window" -> MissionsProbe.window(img, "$at$name")
                "clear" -> MissionsProbe.clear(img, "$at$name")
                else -> { println("no mode '$mode'"); exitProcess(2) }
            }
            if (img !== raw) img.release()
        }
        raw.release()
    }
}

object MissionsProbe {

    private fun f(x: Double) = "%.4f".format(x)

    /**
     * The dot and its plate where the app draws them at [fy]: on a whole
     * frame over the canvas ceiling (a `none0` frame, which holds the rows
     * above the canvas, Dungeon.above) the app places them on the HUD's
     * canvas, those rows further down (Dot.displayY with the canvas top, as
     * PresetHeadroomFlowTest masks them); on any other frame the frame is
     * the canvas. readerProbe's `mask=` places them on the whole frame as if
     * it were the canvas, which on 1080 x 2520 puts the dot 157 to 175 rows
     * too high across the strip (186 x (1 - fy)) -- so the tile's price over
     * the ceiling is measured here.
     */
    fun dot(img: Mat, fy: Double): List<Dot.Box> {
        val top = Dungeon.above(img)
        val w = img.cols(); val h = img.rows() - top
        return listOf(Dot.square(w, h, false, fy), Dot.plate(w, h, false, fy))
            .map { Dot.Box(it.x, it.y + top, it.w, it.h) }
    }

    /** Every clump of [Missions.CLIP_YELLOW] in the column, with what surrounds it. */
    fun tile(img: Mat, name: String) {
        val main = Dungeon.autoButton(img) != null
        val answer = Missions.tile(img)
        val clumps = Missions.clipCandidates(img)
        val badge = Missions.badgeShare(img)
        println("$name  ${img.cols()}x${img.rows()} main=$main tile=${answer?.let { "${f(it.fx)},${f(it.fy)}" }} " +
                "badge=${badge?.let { f(it) }}")
        for (c in clumps) {
            println("    clip fx0 ${f(c.blob.fx0)} fy0 ${f(c.blob.fy0)} w ${f(c.blob.width)} h ${f(c.blob.height)} " +
                    "fill ${f(c.blob.fill)} pale ${f(c.pale)} navy ${f(c.navy)}")
        }
    }

    /**
     * Where Dot.clearFy sends the dot off the tile's rows ([Missions.TILE_ROWS]
     * through Dungeon.bottomFy), from the default place and from the top of
     * the strip, and whether the tile reads with the dot there. Only for a
     * frame that is the whole display (no cutout): the canvas top is then
     * its rows above the canvas.
     */
    fun clear(img: Mat, name: String) {
        val top = Dungeon.above(img)
        val w = img.cols(); val h = img.rows()
        val gh = Dungeon.gameRect(img).gh
        val room = Dungeon.headroom(img)
        val fy0 = Dungeon.bottomFy(Missions.TILE_ROWS[0], Missions.HUD_TOP, room, gh)
        val fy1 = Dungeon.bottomFy(Missions.TILE_ROWS[1], Missions.HUD_TOP, room, gh)
        val out = StringBuilder("$name  rows ${f(fy0)}-${f(fy1)} of the HUD's")
        for (from in listOf(Dot.DEFAULT_FY, Dot.MEASURED_FY_MIN, Dot.MEASURED_FY_MAX)) {
            val to = Dot.clearFy(from, fy0, fy1, w, h, top)
            val at = to ?: from
            val masked = OracleFamilies.copy(img).also { Dot.mask(it, dot(it, at)) }
            val tile = Missions.tile(masked)
            masked.release()
            out.append("  | from ${f(from)} -> ${to?.let { f(it) } ?: "stays"}: tile ${if (tile != null) "reads" else "LOST"}")
        }
        println(out)
    }

    /**
     * `director`: Director.classify on every frame oracle/director.json
     * names, against the screen the file has -- the director's diff before
     * writeOracle may run, read only. Prints every frame that answers
     * otherwise, and every frame the two new readers answer on at all.
     */
    fun director(repo: File) {
        val file = File(repo, "oracle/director.json")
        val frames = Json.parseToJsonElement(file.readText()).jsonObject["frames"]!!.jsonObject
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(Runtime.getRuntime().availableProcessors(), 6))
        val lines = java.util.concurrent.ConcurrentHashMap<String, String>()
        val moved = java.util.concurrent.atomic.AtomicInteger()
        val jobs = frames.map { (rel, entry) ->
            pool.submit {
                val img = OracleFamilies.read(File(repo, rel))
                try {
                    val was = ((entry as JsonObject)["classify"] as JsonObject)
                    val now = Director.classify(img)
                    val window = Missions.window(img)
                    val tile = Missions.tile(img)
                    val same = was["screen"]!!.jsonPrimitive.content == now.screen && was["by"]!!.jsonPrimitive.content == now.by
                    if (!same) moved.incrementAndGet()
                    if (!same || window != null)
                        lines[rel] = "$rel  was ${was["screen"]!!.jsonPrimitive.content} (by ${was["by"]!!.jsonPrimitive.content})" +
                            "  now $now  window=${window?.toOracle()}  tile=${tile != null}"
                } finally {
                    img.release()
                }
            }
        }
        jobs.forEach { it.get() }
        pool.shutdown()
        lines.toSortedMap().values.forEach(::println)
        println("director over ${frames.size} frames of the oracle: ${moved.get()} answer otherwise, ${lines.size} printed")
    }

    /** The tab row, the lit tab, the header block and the Claims. */
    fun window(img: Mat, name: String) {
        val w = Missions.window(img)
        val ex = Missions.exWindow(img)
        println("$name  ${img.cols()}x${img.rows()} window=${w?.toOracle()} ex=${ex?.toOracle()}")
        for (anchor in Dungeon.Anchor.values()) {
            if (anchor != Missions.WINDOW && Dungeon.headroom(img) == 0) continue
            val tabs = Missions.tabBlobs(img, anchor)
            if (tabs.isEmpty()) continue
            println("    $anchor tabs: " + tabs.joinToString("  ") {
                "${it.second} fx ${f(it.first.cx)} fy ${f(it.first.cy)} w ${f(it.first.width)} h ${f(it.first.height)} fill ${f(it.first.fill)}"
            })
        }
        for (b in Runner.blobs(img, Missions.HEADER_BLUE, band = Missions.HEADER_BAND, minShare = 0.005,
                               anchor = Missions.WINDOW)) {
            println("    header-blue fx0 ${f(b.fx0)} fy0 ${f(b.fy0)} w ${f(b.width)} h ${f(b.height)} fill ${f(b.fill)}")
        }
        for (c in Missions.claimCandidates(img, ex?.anchor ?: Missions.WINDOW)) {
            println("    yellow fx ${f(c.cx)} fy ${f(c.cy)} w ${f(c.width)} h ${f(c.height)} fill ${f(c.fill)}")
        }
        if (ex != null) println("    ex_claims: " + Missions.exClaims(img, ex).joinToString("  ") {
            "row ${it.row} fx ${f(it.fx)} fy ${f(it.fy)}"
        })
        val sheet = Dungeon.rewardSheet(img)
        val overlay = Runner.rewardOverlay(img)
        val cls = Director.classify(img)
        println("    reward_sheet=$sheet reward_overlay=${overlay != null} stage_failed=${Dungeon.stageFailed(img)} classify=$cls")
    }
}
