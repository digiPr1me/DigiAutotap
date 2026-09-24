package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.system.exitProcess

/**
 * The oracle, written from the readers as they are: what every reader
 * answers on every frame of the corpus, one file per family,
 * `oracle/<family>.json` (PLAN_STANDALONE.md 1).
 *
 *   gradlew :core:writeOracle                    write every family
 *   gradlew :core:writeOracle --args=dungeon     write one
 *   gradlew :core:test                           hold the files against the readers
 *
 * This is the rule that the laboratory had with `py oracle.py`: a reader is
 * changed, with a measurement; this writes the files again; and the `git
 * diff` of `oracle/` is the list of frames that now read differently.
 * Every one of them is looked at, and the reader and the files are
 * committed together. An oracle test that fails is never a reason to run
 * this and commit what comes out: it is a reason to look.
 *
 * Nothing here reads a clock, a capture or a random number: two runs give
 * the same bytes. The one exception is the "written" stamp, which is kept
 * from the old file when nothing else changed.
 */
object OracleWriter {

    /** {family: {rel: entry}} for these frames, the picture read once per frame. */
    fun collect(frames: List<String>, families: List<OracleFamilies.Family>, repo: File,
                workers: Int = minOf(Runtime.getRuntime().availableProcessors(), 8),
                log: (String) -> Unit = ::println): Map<String, Map<String, Map<String, Any?>>> {
        val results = families.associate { it.name to ConcurrentHashMap<String, Map<String, Any?>>() }
        // Vision keeps caches the Python module kept at module level; one
        // per thread, so that no two frames share one.
        val vision = ThreadLocal.withInitial { Vision(ClassPathAssets) }
        val started = System.nanoTime()
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val jobs: List<Future<*>> = frames.map { rel ->
                pool.submit {
                    val img = Imgcodecs.imread(File(repo, rel).path)
                    require(!img.empty()) { "cannot read $rel" }
                    try {
                        for (family in families) {
                            results[family.name]!![rel] = OracleFamilies.answers(family, img, vision.get())
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
        return results
    }

    /** Read the corpus once and write the files. */
    fun run(families: List<OracleFamilies.Family>, repo: File, log: (String) -> Unit = ::println) {
        val corpus = OracleFamilies.corpus(repo)
        log("${corpus.frames.size} frames, ${corpus.skipped.size} files skipped")
        val started = System.nanoTime()
        val results = collect(corpus.frames, families, repo, log = log)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        for (family in families) {
            val written = OracleFamilies.write(family, results[family.name]!!, corpus.skipped, repo, stamp)
            log(if (written) "  ${family.name}.json written, ${corpus.frames.size} frames"
                else "  ${family.name}.json unchanged")
        }
        log("done in ${(System.nanoTime() - started) / 1_000_000_000} s")
    }
}

fun main(args: Array<String>) {
    val unknown = args.filter { it !in OracleFamilies.BY_NAME }
    if (unknown.isNotEmpty()) {
        println("no such family: ${unknown.joinToString(", ")} (${OracleFamilies.BY_NAME.keys.joinToString(", ")})")
        exitProcess(2)
    }
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val families = if (args.isEmpty()) OracleFamilies.ALL else args.map { OracleFamilies.BY_NAME.getValue(it) }
    OracleWriter.run(families, repo)
}
