package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
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

    /**
     * {family: {rel: entry}} for these frames, the picture read once per frame.
     * This is the one loop over the corpus: the writer fills the files from
     * it and [OracleFamilies.check] holds the files against it, so the tests
     * ask on the same threads the files were written on. `prepare` is what
     * happens to a picture before it is asked -- nothing, unless a test is
     * about a picture that was changed on purpose.
     *
     * Eight threads, and OpenCV's own left as they are. Measured 2026-10-03
     * on 20 logical processors, the director's file over 1886 frames: one
     * frame after the other 266 s, 8 threads 69 s, 16 threads 61 s; with
     * OpenCV's own threads off 78 s on 8 and 52 s on 20. A reader call
     * already spreads over the idle cores, so what is left to win is the
     * machine's and not the loop's, and `:core:test` runs four of these
     * loops side by side (core/build.gradle.kts).
     */
    fun collect(frames: List<String>, families: List<OracleFamilies.Family>, repo: File,
                workers: Int = minOf(Runtime.getRuntime().availableProcessors(), 8),
                prepare: (Mat) -> Unit = {},
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
                    val img = OracleFamilies.read(File(repo, rel))
                    require(!img.empty()) { "cannot read $rel" }
                    try {
                        prepare(img)
                        for (family in families) {
                            results[family.name]!![rel] = OracleFamilies.answers(family, img, vision.get())
                        }
                    } finally {
                        img.release()
                    }
                }
            }
            jobs.forEachIndexed { i, job ->
                // What a frame threw, as it was thrown: a test's report names
                // the reader and the frame, not the pool.
                try { job.get() } catch (e: ExecutionException) { throw e.cause ?: e }
                if ((i + 1) % 100 == 0) log("  ${i + 1} / ${frames.size}  ${(System.nanoTime() - started) / 1_000_000_000} s")
            }
        } finally {
            // After a frame that threw, the frames still queued are dropped;
            // after the last frame there is nothing left to drop.
            pool.shutdownNow()
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
