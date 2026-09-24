package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The minigame readers against oracle/vision.json: every frame it names,
 * every reader of the family, and the answer has to be the one in the file.
 * A frame that answers differently is a reader change to look at, never a
 * reason to edit the oracle by hand (NOTES.md, "The oracle is the seam"):
 * if the change was meant, with a measurement, `gradlew :core:writeOracle`
 * writes the file again and its diff is the list of frames that tipped.
 *
 * The templates and the digit references come in through the asset source,
 * from the class path here and from the APK in the app: what the first test
 * below proves is that the bytes that way are the repository's files.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisionOracleTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var oracle: JsonObject

    /** One instance for the whole run, as the module-level caches are in Python. */
    private val vision = Vision(ClassPathAssets)

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val file = OracleFamilies.pathOf(OracleFamilies.VISION, repo)
        assertTrue(file.exists(), "no oracle at $file -- run gradlew :core:writeOracle")
        oracle = Json.parseToJsonElement(file.readText()).jsonObject
    }

    // ------------------------------------------------------------------------
    // The images the readers carry, through the asset source
    // ------------------------------------------------------------------------

    private fun sameImage(a: Mat, b: Mat): Boolean {
        if (a.rows() != b.rows() || a.cols() != b.cols() || a.type() != b.type()) return false
        val diff = Mat()
        Core.absdiff(a, b, diff)
        val sum = Core.sumElems(diff)
        return sum.`val`.all { it == 0.0 }
    }

    @Test
    fun `the templates load through the asset source as the files read`() {
        val templates = vision.loadTemplates()
        val wanted = (Vision.OBJECT_TYPES + Vision.NON_BOARD_TEMPLATES).distinct()
        assertEquals(wanted, templates.keys.toList(),
                     "templates/ misses a file the reader names, or the load order changed")
        for ((name, mat) in templates) {
            val direct = Imgcodecs.imread(File(repo, "templates/$name.png").path)
            assertTrue(sameImage(mat, direct), "templates/$name.png differs between the asset source and imread")
            assertEquals(3, mat.channels(), "templates/$name.png is not BGR")
        }
        // Python's board_templates: everything but the non-board ones, the
        // arrow kept in.
        assertEquals(listOf("ticket_orange", "ticket_green", "ticket_pink", "claw", "paw", "fireball",
                            "pyramid", "arrow"), vision.boardTemplates().keys.toList())
    }

    @Test
    fun `the digit references load through the asset source as the folders list them`() {
        val stats = vision.digitStats()
        assertEquals("0123456789".toList(), stats.keys.toList())
        var files = 0
        for (d in "0123456789") {
            val listed = File(repo, "digits/shared/$d").list { _, n -> n.endsWith(".png") }!!.sorted()
            // The asset source has no listing: the loader asks for 00.png,
            // 01.png ... and this is where a folder that stops being named
            // that way is noticed.
            assertEquals(listed, listed.indices.map { String.format("%02d.png", it) },
                         "digits/shared/$d is not 00.png, 01.png ... without a gap")
            assertEquals(listed.size, stats[d], "digits/shared/$d: the loader found another number of files")
            files += listed.size
        }
        println("digit references: $files, per digit $stats")
    }

    // ------------------------------------------------------------------------
    // The readers, on every frame
    // ------------------------------------------------------------------------

    // This test used to carry a list of frames the oracle named whose file
    // was no longer the picture it had been written from: the debug folders
    // were the corpus and the skills' output folders at once, and a live run
    // of Digital World Search wrote resync_00 to 04 over five boards on
    // 2026-09-19. The corpus is corpus/<folder>/ now and nothing writes
    // there (OracleFamilies.CORPUS; CorpusTest scans the sources for a
    // write site that names it), so every frame in the file is held.

    @Test
    fun `every reader gives the oracle's answer on every frame`() {
        val report = OracleFamilies.check(OracleFamilies.VISION, oracle, repo, vision)
        report.print()
        if (report.mismatches.isNotEmpty()) fail(report.summary())

        // Every answer above agreed, so what is left to rule out is a test
        // that agreed by not looking: each reader of the oracle has to have
        // been asked, and to have said something other than nothing at least
        // once, or the oracle would be no contract for it (its header says
        // the same with "distinct").
        for ((name, head) in oracle["readers"]!!.jsonObject) {
            assertTrue((report.checked[name] ?: 0) > 0, "$name: never asked")
            assertTrue((report.answered[name] ?: 0) > 0, "$name: never answered anything but null")
            assertTrue(head.jsonObject["distinct"]!!.jsonPrimitive.int > 1, "$name: the oracle has one answer only")
        }
    }

    // ------------------------------------------------------------------------
    // What the oracle does not ask
    // ------------------------------------------------------------------------

    private fun calib(card: List<Int>, gx: Double, gy: Double, cw: Double, ch: Double): Calib =
        Calib(card.toIntArray(), gx, gy, cw, ch, emptyMap(), intArrayOf(0, 0), 0)

    /**
     * `median_calib` and `cell_center` / `cell_rect` are not readers, and the
     * oracle does not run them; these are what Python answers on three of the
     * oracle's own calibrations (1080 x 1920, two of them a half pixel apart
     * in grid_y0), so the median of an even and an odd count are both there.
     */
    @Test
    fun `median_calib, cell_center and cell_rect give Python's numbers`() {
        val a = calib(listOf(98, 0, 884, 1903), 117.0, 638.4, 164.03, 134.6673)
        val b = calib(listOf(98, 0, 884, 1903), 117.0, 637.9, 164.03, 134.6673)

        val odd = vision.medianCalib(listOf(a, b, b)).toOracle()
        val even = vision.medianCalib(listOf(a, b)).toOracle()
        val boxes = mapOf(
            "roi_claws" to listOf(305, 1697, 216, 72), "roi_fireballs" to listOf(305, 1773, 216, 72),
            "roi_meters" to listOf(387, 1267, 278, 40), "roi_paws" to listOf(305, 1621, 216, 72),
            "roi_top_green" to listOf(476, 26, 205, 68), "roi_top_orange" to listOf(281, 26, 205, 68),
            "roi_top_pink" to listOf(670, 26, 205, 68))
        val expectedOdd = mapOf("card" to listOf(98, 0, 884, 1903), "cell_h" to 134.6673, "cell_w" to 164.03,
                                "grid_x0" to 117.0, "grid_y0" to 637.9, "skill_button" to listOf(677, 1722),
                                "skill_radius" to 66) + boxes
        assertEquals(Oracle.encode(expectedOdd), Oracle.encode(odd))
        assertEquals(Oracle.encode(expectedOdd + ("grid_y0" to 638.15)), Oracle.encode(even))

        assertEquals(691 to 975, vision.cellCenter(a, 2, 3))
        assertEquals(listOf(281, 1177, 164, 135), vision.cellRect(a, 4, 1).toList())
    }
}
