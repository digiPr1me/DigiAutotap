package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * The numbers behind the readers of [CampStation] (PLAN_CAMP.md C2), printed
 * before a threshold was chosen. Reads, writes nothing but its report.
 *
 *   gradlew :core:campStationProbe --no-daemon --args="parts staging/camp" > build/c2/parts.txt
 *   gradlew :core:campStationProbe --no-daemon --args="sweep corpus staging/camp" > build/c2/sweep.txt
 *
 * `parts` prints, per picture, everything the readers weighed: the banner
 * candidates, the teal-and-blue pairs with their fill values, the picture's
 * brown share and the counter read off the pair, Reward Boxes' band and
 * Claim, Results' bars and Close. `sweep` asks the same on every frame named
 * (`corpus` is every frame of the corpus, read as the oracle reads it) and
 * prints only frames on which any part was found, so that the gap between
 * the windows and everything else stands in one file. `mask=a:b:step` asks
 * again with the dot and its plate masked in at every fy of that range.
 */
fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    if (args.size < 2) {
        println("campStationProbe wants a mode and pictures: `parts staging/camp`")
        exitProcess(2)
    }
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val mode = args[0]
    val places: List<Double?> = args.drop(1).firstOrNull { it.startsWith("mask=") }?.removePrefix("mask=")
        ?.split(':')?.map { it.toDouble() }
        ?.let { (a, b, step) -> generateSequence(a) { it + step }.takeWhile { it <= b + 1e-9 }.toList() }
        ?: listOf(null)
    val files = ArrayList<File>()
    for (arg in args.drop(1).filter { !it.startsWith("mask=") }) {
        if (arg == "corpus") {
            files += OracleFamilies.corpus(repo).frames.map { File(repo, it) }
            continue
        }
        val f = File(arg).let { if (it.isAbsolute) it else File(repo, arg) }
        if (f.isDirectory) files += f.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name }
        else files.add(f)
    }
    println("$mode over ${files.size} pictures")
    val pool = Executors.newFixedThreadPool(8)
    val jobs = files.map { file ->
        pool.submit<String> {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
            val raw = OracleFamilies.framed(Imgcodecs.imread(file.path), file.name)
            val out = StringBuilder()
            for (fy in places) {
                val img = if (fy == null) raw else OracleFamilies.copy(raw).also {
                    Dot.mask(it, listOf(Dot.square(it.cols(), it.rows(), false, fy), Dot.plate(it.cols(), it.rows(), false, fy)))
                }
                val at = if (fy == null) "" else "[fy %.3f] ".format(fy)
                val name = file.path.replace('\\', '/').substringAfter("corpus/").substringAfter("staging/")
                val text = CampStationProbe.parts(img)
                if (mode == "parts" || text.isNotEmpty()) out.append("== $at$name ${img.cols()} x ${img.rows()}\n").append(text)
                if (img !== raw) img.release()
            }
            raw.release()
            out.toString()
        }
    }
    for (j in jobs) print(j.get())
    pool.shutdown()
}

object CampStationProbe {

    private fun f(x: Double) = "%.4f".format(x)

    private fun blob(b: Runner.Blob) =
        "fx ${f(b.fx0)}-${f(b.fx1)} fy ${f(b.fy0)}-${f(b.fy1)} w ${f(b.width)} h ${f(b.height)} fill ${"%.3f".format(b.fill)}"

    /** Every part the readers weigh on [img], one line each; empty where nothing was found. */
    fun parts(img: Mat): String {
        val out = StringBuilder()
        for (b in CampStation.bannerCandidates(img)) out.append("  banner      ${blob(b)}\n")
        for ((a, b) in CampStation.pairCandidates(img)) {
            out.append("  pair auto   ${blob(a)}\n  pair try    ${blob(b)}  pitch ${f(b.cx - a.cx)} dy ${f(b.cy - a.cy)}\n")
        }
        CampStation.stationParts(img)?.let { p ->
            val cy = (p.auto.cy + p.attempt.cy) / 2
            out.append("  station     autoV ${"%.1f".format(p.autoV)} attemptV ${"%.1f".format(p.attemptV)}" +
                       " brown ${"%.3f".format(p.brown)} banner ${p.banner != null}" +
                       " attempts ${CampStation.attempts(img, (p.auto.fx1 + p.attempt.fx0) / 2, cy)}\n")
        }
        CampStation.window(img)?.let { out.append("  window      ${Oracle.encode(it.toOracle())}\n") }
        val bp = CampStation.boxesParts(img)
        if (bp.total != null) out.append("  boxes total ${blob(bp.total)} banner ${bp.banner != null}\n")
        if (bp.claim != null) out.append("  boxes claim ${blob(bp.claim)} v ${"%.1f".format(bp.claimV)}\n")
        CampStation.boxesWindow(img)?.let { out.append("  boxes       ${Oracle.encode(it.toOracle())}\n") }
        val bars = CampStation.barCandidates(img)
        for (b in bars) out.append("  bar         ${blob(b)}\n")
        CampStation.results(img)?.let { out.append("  results     ${Oracle.encode(it.toOracle())}\n") }
        return out.toString()
    }
}
