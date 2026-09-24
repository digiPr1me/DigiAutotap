package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.system.exitProcess

/**
 * What a rectangle over the game costs, measured: the laboratory's
 * `_overlay_probe.py`, for the one question left open there -- how big the
 * word's plate may be.
 *
 *   gradlew :core:overlayProbe --no-daemon > probe.txt
 *
 * Every variant below is masked into every frame of the corpus at the
 * measured place (Dot.DEFAULT_FY, right edge), every reader of every family
 * is asked on the masked picture, and each answer is held against
 * `oracle/<family>.json`. A frame on which any answer differs is a *tipped*
 * frame, and the count of them is the price of that rectangle. Two of the
 * variants are the numbers PLAN_ANDROID_DESIGN.md 3.2 already has -- the
 * dot alone (3) and the dot with the 92 x 18 dp word (24) -- and they are
 * here so that the instrument is checked before it is believed.
 *
 * Nothing is drawn, only masked; OverlayOracleTest says why that is the
 * same picture. Nothing here writes anywhere: the corpus and the oracle
 * are read, and the answer is printed.
 */
object OverlayPlateProbe {

    /** A dp of the 411 dp reference phone, as a fraction of the screen (Dot.DOT_OF_SCREEN). */
    private fun dp(v: Double): Double = v * Dot.DOT_OF_SCREEN / 48.0

    class Variant(val name: String, val boxes: (Int, Int) -> List<Dot.Box>)

    /** The dot, and a plate of this size beside it. */
    private fun beside(wDp: Double, hDp: Double) = Variant("dot + plate %.0f x %.0f dp beside".format(wDp, hDp)) { w, h ->
        listOf(Dot.square(w, h, false, Dot.DEFAULT_FY),
               Dot.plate(w, h, false, Dot.DEFAULT_FY, dp(wDp), dp(hDp)))
    }

    /** One rectangle over plate, gap and dot together: a capsule. */
    private fun capsule(wDp: Double) = Variant("capsule %.0f dp plate + gap + dot, one box".format(wDp)) { w, h ->
        val dot = Dot.square(w, h, false, Dot.DEFAULT_FY)
        val plate = Dot.plate(w, h, false, Dot.DEFAULT_FY, dp(wDp), Dot.PLATE_H_OF_SCREEN)
        listOf(Dot.Box(plate.x, dot.y, dot.x + dot.w - plate.x, dot.h))
    }

    /** The dot, and a plate of this size under it, flush with the same edge. */
    private fun below(wDp: Double, hDp: Double) = Variant("dot + plate %.0f x %.0f dp below".format(wDp, hDp)) { w, h ->
        val dot = Dot.square(w, h, false, Dot.DEFAULT_FY)
        val screen = dot.w / Dot.DOT_OF_SCREEN
        val pw = dp(wDp) * screen; val ph = dp(hDp) * screen
        listOf(dot, Dot.Box(dot.x + dot.w - pw, dot.y + dot.h, pw, ph))
    }

    /** The dot, and a plate beside it whose top (or bottom) is the dot's rather than its centre. */
    private fun aligned(wDp: Double, hDp: Double, top: Boolean) =
        Variant("dot + plate %.0f x %.0f dp beside, %s-aligned".format(wDp, hDp, if (top) "top" else "bottom")) { w, h ->
            val dot = Dot.square(w, h, false, Dot.DEFAULT_FY)
            val centred = Dot.plate(w, h, false, Dot.DEFAULT_FY, dp(wDp), dp(hDp))
            val y = if (top) dot.y else dot.y + dot.h - centred.h
            listOf(dot, Dot.Box(centred.x, y, centred.w, centred.h))
        }

    /**
     * The list as it stands is the check of the instrument and the plate
     * that ships: dot alone 3, dot and 92 x 18 dp 24 (both from the overlay measurement), and
     * the 128 x 18 dp plate of the second version, 34. The three sweeps of
     * 2026-09-21 that priced every other shape -- heights 20 to 36 dp,
     * widths to 192 dp, the plate top- and bottom-aligned, under the dot,
     * and one capsule -- are tabled in PLAN_ANDROID_DESIGN.md 3.1, with
     * the two bands that explain them; add a variant here to ask about
     * another.
     *
     * Two things about running it: the output goes to a file, never
     * through `| tail`, and it runs with `--no-daemon`, because a
     * `gradlew --stop` from anywhere ends a daemon's sweep at whatever
     * frame it is on.
     */
    val VARIANTS: List<Variant> = listOf(
        Variant("dot alone") { w, h -> listOf(Dot.square(w, h, false, Dot.DEFAULT_FY)) },
        beside(92.0, 18.0),
        beside(128.0, 18.0),
    )

    class Tip(val frame: String, val readers: Set<String>)

    fun run(repo: File, variants: List<Variant>, log: (String) -> Unit = ::println) {
        val families = OracleFamilies.ALL
        val oracles: Map<String, JsonObject> = families.associate { f ->
            val file = File(repo, "oracle/${f.name}.json")
            require(file.exists()) { "no oracle at $file -- run gradlew :core:writeOracle" }
            f.name to Json.parseToJsonElement(file.readText()).jsonObject["frames"]!!.jsonObject
        }
        val frames = oracles.getValue(families[0].name).keys.sorted()
        log("${frames.size} frames, ${families.size} families, ${variants.size} variants")
        val tips: Map<String, ConcurrentHashMap<String, Set<String>>> =
            variants.associate { it.name to ConcurrentHashMap<String, Set<String>>() }
        val vision = ThreadLocal.withInitial { Vision(ClassPathAssets) }
        val started = System.nanoTime()
        val pool = Executors.newFixedThreadPool(minOf(Runtime.getRuntime().availableProcessors(), 8))
        try {
            val jobs: List<Future<*>> = frames.map { rel ->
                pool.submit {
                    val img = Imgcodecs.imread(File(repo, rel).path)
                    require(!img.empty()) { "cannot read $rel" }
                    try {
                        for (v in variants) {
                            val masked: Mat = img.clone()
                            try {
                                Dot.mask(masked, v.boxes(masked.cols(), masked.rows()))
                                val tipped = sortedSetOf<String>()
                                for (f in families) {
                                    val expected = oracles.getValue(f.name)[rel]!!.jsonObject
                                    val got = OracleFamilies.answers(f, masked, vision.get())
                                    for (key in expected.keys + got.keys) {
                                        val differs = key !in got || key !in expected ||
                                            !OracleFamilies.same(got[key], expected[key]!!)
                                        if (differs) tipped += "${f.name}.${OracleFamilies.readerName(key)}"
                                    }
                                }
                                if (tipped.isNotEmpty()) tips.getValue(v.name)[rel] = tipped
                            } finally {
                                masked.release()
                            }
                        }
                    } finally {
                        img.release()
                    }
                }
            }
            jobs.forEachIndexed { i, job ->
                job.get()
                if ((i + 1) % 100 == 0) log("  ${i + 1} / ${frames.size}  ${(System.nanoTime() - started) / 1_000_000_000} s")
            }
        } finally {
            pool.shutdown()
        }
        log("")
        log("tipped frames of ${frames.size}, masked at the right edge, fy ${Dot.DEFAULT_FY}:")
        for (v in variants) {
            val t = tips.getValue(v.name)
            val byReader = sortedMapOf<String, Int>()
            for (readers in t.values) for (r in readers) byReader.merge(r, 1, Int::plus)
            log("")
            log("  %-48s %4d   %s".format(v.name, t.size, byReader.entries.joinToString(", ") { "${it.key} ${it.value}" }))
            for ((frame, readers) in t.toSortedMap()) log("      $frame  $readers")
        }
        log("")
        log("done in ${(System.nanoTime() - started) / 1_000_000_000} s")
    }
}

fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val variants = if (args.isEmpty()) OverlayPlateProbe.VARIANTS
                   else OverlayPlateProbe.VARIANTS.filter { v -> args.any { v.name.startsWith(it) } }
    if (variants.isEmpty()) { println("no such variant; the names are ${OverlayPlateProbe.VARIANTS.map { it.name }}"); exitProcess(2) }
    OverlayPlateProbe.run(repo, variants)
}
