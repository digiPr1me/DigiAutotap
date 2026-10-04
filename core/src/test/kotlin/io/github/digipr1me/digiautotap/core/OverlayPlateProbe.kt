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
 * One place, the app's and this probe's: [Dot.DEFAULT_FY] of the window
 * (`Dungeon.gameRectWh`). Until 2026-09-26 the app stood at fy of the
 * frame, 48 px lower at 1920, and this probe had a `--place` switch to
 * price both (PLAN_FORMATE.md V2: 158 tipped frames there against 57
 * here). The app goes through `Dot.displayY` now, which is [Dot.square]'s
 * arithmetic on the frame the readers get, so a frame of a phone with a
 * camera cutout -- already cut, as every frame the readers see -- is
 * masked right as it is, and the `--cut` that went with the switch went
 * with it. And [Dot.square] and [Dot.plate] are the app's whole pixels
 * since the same day: the fractional plate half a row lower had kept two
 * `summon.general_tab` tips at 2340 out of the 57, and the count for what
 * the app draws is 59.
 *
 * **`runner.bar_state` is a share, not an answer.** It reads the egg bar's
 * strip, fx 0.20 to 0.83 and fy 0.150 to 0.185 of the window, and the
 * plate lies inside it at either place (23.6 % of the strip's pixels at
 * 1920 here, 22.9 % at the old place). The oracle keeps the green and
 * pink shares to four places, and one step of the fourth is five pixels
 * of the strip at 1920, so a few pixels of either colour under the plate
 * tip it -- on the main screen there nearly always are: 523 frames here,
 * 487 of them for that alone. What the skill asks is pink,
 * only during a run, against FEVER_ON 0.15 and FEVER_OFF 0.05, and on no
 * run frame of the corpus does the plate move pink across either
 * (2026-09-26: the Fever frame 0.3035 to 0.2798). So its count is printed
 * apart and the total is given without it.
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

    /** A rectangle set, asked of a frame's width and height and the dot's centre fy on that frame. */
    class Variant(val name: String, val boxes: (Int, Int, Double) -> List<Dot.Box>)

    /** The dot, and a plate of this size beside it. */
    private fun beside(wDp: Double, hDp: Double) = Variant("dot + plate %.0f x %.0f dp beside".format(wDp, hDp)) { w, h, fy ->
        listOf(Dot.square(w, h, false, fy),
               Dot.plate(w, h, false, fy, dp(wDp), dp(hDp)))
    }

    /** One rectangle over plate, gap and dot together: a capsule. */
    private fun capsule(wDp: Double) = Variant("capsule %.0f dp plate + gap + dot, one box".format(wDp)) { w, h, fy ->
        val dot = Dot.square(w, h, false, fy)
        val plate = Dot.plate(w, h, false, fy, dp(wDp), Dot.PLATE_H_OF_SCREEN)
        listOf(Dot.Box(plate.x, dot.y, dot.x + dot.w - plate.x, dot.h))
    }

    /** The dot, and a plate of this size under it, flush with the same edge. */
    private fun below(wDp: Double, hDp: Double) = Variant("dot + plate %.0f x %.0f dp below".format(wDp, hDp)) { w, h, fy ->
        val dot = Dot.square(w, h, false, fy)
        val screen = dot.w / Dot.DOT_OF_SCREEN
        val pw = dp(wDp) * screen; val ph = dp(hDp) * screen
        listOf(dot, Dot.Box(dot.x + dot.w - pw, dot.y + dot.h, pw, ph))
    }

    /** The dot, and a plate beside it whose top (or bottom) is the dot's rather than its centre. */
    private fun aligned(wDp: Double, hDp: Double, top: Boolean) =
        Variant("dot + plate %.0f x %.0f dp beside, %s-aligned".format(wDp, hDp, if (top) "top" else "bottom")) { w, h, fy ->
            val dot = Dot.square(w, h, false, fy)
            val centred = Dot.plate(w, h, false, fy, dp(wDp), dp(hDp))
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
        Variant("dot alone") { w, h, fy -> listOf(Dot.square(w, h, false, fy)) },
        beside(92.0, 18.0),
        beside(128.0, 18.0),
    )

    class Tip(val frame: String, val readers: Set<String>)

    /** A reader whose count is a share changing, not an answer (see the head of this file). */
    const val SHARE_READER = "runner.bar_state"

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
                    val img = OracleFamilies.read(File(repo, rel))
                    require(!img.empty()) { "cannot read $rel" }
                    try {
                        for (v in variants) {
                            val masked: Mat = OracleFamilies.copy(img)
                            try {
                                Dot.mask(masked, v.boxes(masked.cols(), masked.rows(), Dot.DEFAULT_FY))
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
        log("tipped frames of ${frames.size}, masked at the right edge, fy ${Dot.DEFAULT_FY} of the window " +
            "(and without $SHARE_READER, which is a share):")
        for (v in variants) {
            val t = tips.getValue(v.name)
            val byReader = sortedMapOf<String, Int>()
            for (readers in t.values) for (r in readers) byReader.merge(r, 1, Int::plus)
            log("")
            val answers = t.values.count { readers -> readers.any { it != SHARE_READER } }
            log("  %-48s %4d  (%4d)   %s".format(v.name, t.size, answers,
                byReader.entries.joinToString(", ") { "${it.key} ${it.value}" }))
            for ((frame, readers) in t.toSortedMap()) log("      $frame  $readers")
        }
        log("")
        log("done in ${(System.nanoTime() - started) / 1_000_000_000} s")
    }
}

/** `--args="[variant ...]"`: a variant is named by the start of its name. */
fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val names = args.toList()
    val variants = if (names.isEmpty()) OverlayPlateProbe.VARIANTS
                   else OverlayPlateProbe.VARIANTS.filter { v -> names.any { v.name.startsWith(it) } }
    if (variants.isEmpty()) { println("no such variant; the names are ${OverlayPlateProbe.VARIANTS.map { it.name }}"); exitProcess(2) }
    OverlayPlateProbe.run(repo, variants)
}
