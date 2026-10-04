package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Raw candidates for the display self-check (PLAN_FORMATE.md 10), over the
 * whole corpus: every globe-shaped white blob in the lower part of the frame
 * and every flat bright-green bar in the upper part, position-free, with
 * where game_rect expects them. Prints; writes nothing but its own report.
 *
 *   gradlew :core:selfCheckProbe --no-daemon > build/selfcheck_raw.txt
 */
fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val frames = OracleFamilies.corpus(repo).frames
    val out = StringBuilder()
    fun p(s: String) { out.append(s).append('\n') }
    val times = ArrayList<Double>()
    val missed = ArrayList<String>()
    for (rel in frames) {
        if (args.isNotEmpty() && args.none { it in rel }) continue
        val img = OracleFamilies.read(File(repo, rel))
        if (img.empty()) { p("$rel unreadable"); continue }
        val t0 = System.nanoTime()
        val v = DisplayCheck.check(img)
        times += (System.nanoTime() - t0) / 1e6
        val home = Dungeon.homeButton(img)
        if (home != null && v.globe?.let { abs(it.fx - home.fx) < 0.002 && abs(it.fy - home.fy) < 0.002 } != true)
            missed += rel
        p(String.format(Locale.ROOT, "%s %dx%d | verdict %s | home %s | globes %s | bars %s", rel, img.cols(), img.rows(),
            v.verdict, home?.let { String.format(Locale.ROOT, "%.4f,%.4f w%.4f", it.fx, it.fy, it.fw) } ?: "-",
            DisplayCheck.globes(img).joinToString(";") { it.say() },
            DisplayCheck.bars(img).joinToString(";") { it.say() }))
        img.release()
    }
    p("home_button answered and the check's globe is not it: ${missed.size}")
    missed.forEach { p("  $it") }
    // Timed again, warm: the first pass pays for the JIT.
    times.clear()
    var gT = 0.0; var bT = 0.0
    for (rel in frames.take(400)) {
        val img = OracleFamilies.read(File(repo, rel))
        if (img.empty()) continue
        val t0 = System.nanoTime()
        DisplayCheck.check(img)
        times += (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime(); DisplayCheck.globes(img); val t2 = System.nanoTime(); DisplayCheck.bars(img)
        val t3 = System.nanoTime()
        gT += (t2 - t1) / 1e6; bT += (t3 - t2) / 1e6
        img.release()
    }
    p(String.format(Locale.ROOT, "mean: globes %.3f ms, bars %.3f ms", gT / times.size, bT / times.size))
    times.sort()
    if (times.isNotEmpty()) p(String.format(Locale.ROOT, "time per check: median %.3f ms, p90 %.3f, max %.3f over %d",
        times[times.size / 2], times[times.size * 9 / 10], times.last(), times.size))
    print(out)
    File(repo, "core/build").mkdirs()
    File(repo, "core/build/selfCheckProbe.txt").writeText(out.toString())
}
