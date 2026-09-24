package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seven files of the oracle, before any reader runs: their heads
 * against [OracleFamilies], the frames they name against the corpus on
 * disk, and the writer's plumbing -- the frame rule at the corpus's edge
 * cases, the number format, and that writing twice gives the same bytes.
 * What each family's readers answer on each frame is the family's own
 * `*OracleTest`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleFilesTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var files: Map<String, JsonObject>

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        // The public copy of the source carries oracle/ empty (release.py) and
        // no corpus; there this suite has nothing to say. One file missing
        // beside the others is a fault, and the assertion below says so.
        assumeTrue(File(repo, "oracle").listFiles { f -> f.extension == "json" }.orEmpty().isNotEmpty(),
                   "no oracle in this checkout")
        files = OracleFamilies.ALL.associate { family ->
            val file = OracleFamilies.pathOf(family, repo)
            assertTrue(file.exists(), "no oracle at $file -- run gradlew :core:writeOracle")
            family.name to Json.parseToJsonElement(file.readText()).jsonObject
        }
    }

    @Test
    fun `every file is its family's, written with the OpenCV this test runs`() {
        for ((name, data) in files) {
            assertEquals(name, data["family"]!!.jsonPrimitive.content, "oracle/$name.json says it is another family")
            assertEquals(Core.VERSION, data["opencv"]!!.jsonPrimitive.content,
                         "oracle/$name.json and the desktop native disagree on the OpenCV version")
        }
    }

    /**
     * A reader in the table and not in the file was asked on no frame at
     * all; one in the file and not in the table was dropped from the table
     * and the file not written again. And a reader that answers null on
     * every frame, or the same thing on every frame, is no contract: the
     * Kotlin side could `return null` and pass. Fill the gap with frames.
     */
    @Test
    fun `every reader of every family is in its file, and says more than one thing`() {
        for (family in OracleFamilies.ALL) {
            val readers = files[family.name]!!["readers"]!!.jsonObject
            assertEquals(family.readers.toSet(), readers.keys,
                         "oracle/${family.name}.json and OracleFamilies disagree on the family")
            for ((reader, s) in readers) {
                assertTrue(s.jsonObject["answered"]!!.jsonPrimitive.int > 0,
                           "${family.name}.$reader answers null on every frame of the corpus")
                assertTrue(s.jsonObject["distinct"]!!.jsonPrimitive.int > 1,
                           "${family.name}.$reader gives the same answer on every frame of the corpus")
            }
        }
    }

    /**
     * Notes only, printed: a comment edited in Dungeon.kt changes its hash
     * and no answer, and the family's test says whether an answer moved.
     * What the hash is for is the other direction -- a file whose readers
     * have not changed since it was written needs no looking at.
     */
    @Test
    fun `the sources and the images are the ones the files were written from`() {
        var notes = 0
        for (family in OracleFamilies.ALL) {
            val data = files[family.name]!!
            val modules = OracleFamilies.moduleHashes(family, repo)
            for ((module, digest) in data["modules"]!!.jsonObject) {
                if (modules[module] != digest.jsonPrimitive.content) {
                    println("  note: $module.kt has changed since oracle/${family.name}.json was written")
                    notes += 1
                }
            }
            val images = OracleFamilies.dataHashes(family, repo)
            for ((folder, digest) in data["data"]!!.jsonObject) {
                if (images[folder] != digest.jsonPrimitive.content) {
                    println("  note: the images in $folder/ have changed since oracle/${family.name}.json was written")
                    notes += 1
                }
            }
        }
        println("heads: ${files.size} files, $notes notes")
    }

    /**
     * The corpus is the set of frames the files name: one that is gone
     * from disk is a red test, and one on disk that no file names yet is a
     * note -- `gradlew :core:writeOracle` adds it, and the diff is what it
     * answers.
     */
    @Test
    fun `the files name the corpus`() {
        val corpus = OracleFamilies.corpus(repo)
        assertTrue(corpus.frames.isNotEmpty(), "no frames under corpus/")
        val onDisk = corpus.frames.toSet()
        val listed = files.values.flatMap { it["frames"]!!.jsonObject.keys }.toSet()
        val missing = listed.filter { !File(repo, it).exists() }.sorted()
        assertTrue(missing.isEmpty(), "${missing.size} frames named in the oracle are gone from disk, e.g. ${missing.take(3)}")
        for ((name, data) in files) {
            assertEquals(listed, data["frames"]!!.jsonObject.keys, "oracle/$name.json names another set of frames than the rest")
        }
        val fresh = (onDisk - listed).sorted()
        if (fresh.isNotEmpty()) {
            println("  note: ${fresh.size} frames on disk are not in the oracle yet (gradlew :core:writeOracle adds them), e.g. ${fresh.first()}")
        }
        val stray = (listed - onDisk).sorted()
        assertTrue(stray.isEmpty(), "${stray.size} frames in the oracle fail the frame rule now, e.g. ${stray.take(3)}")
        println("corpus: ${corpus.frames.size} frames, ${corpus.skipped.size} files skipped, ${fresh.size} not in the oracle")
    }

    // ------------------------------------------------------------------------
    // The writer's plumbing
    // ------------------------------------------------------------------------

    /** The frame rule at the edge cases of the corpus (oracle.py's test_is_frame). */
    @Test
    fun `the frame rule`() {
        for ((w, h) in listOf(1080 to 1920, 805 to 1390, 573 to 1056, 730 to 1389,
                              // the narrowest window frame there is, corpus/passive/bond-3.png
                              497 to 914,
                              // the long displays: LDPlayer at 1080 x 2340, a 20:9 and a 21:9 phone
                              1080 to 2340, 1080 to 2400, 1080 to 2520)) {
            assertTrue(OracleFamilies.isFrame(w, h), "$w x $h is a frame")
        }
        for ((w, h) in listOf(210 to 42, 1920 to 1080, 303 to 269, 300 to 450,
                              // portrait but a crop: under the floor
                              399 to 720,
                              // portrait and too square, or too tall
                              1080 to 1700, 1080 to 2800)) {
            assertTrue(!OracleFamilies.isFrame(w, h), "$w x $h is not a frame")
        }
    }

    /** Python's `json.dumps` for the answers: sorted keys, `1.0` not `1`, four places, half to even. */
    @Test
    fun `answers are written as Python wrote them`() {
        assertEquals("""{"a": 1, "b": [true, null, "x"], "c": 1.0, "d": 0.1235}""",
                     Oracle.encode(mapOf("d" to 0.12345, "b" to listOf(true, null, "x"), "a" to 1, "c" to 1.0)))
        assertEquals("0.1234", Oracle.encode(0.12344))
        assertEquals("0.0", Oracle.encode(-0.00001))
        assertEquals("[0.5, 2]", Oracle.encode(0.5 to 2))
        assertEquals("{}", Oracle.encode(emptyMap<String, Any?>()))
    }

    /** Two runs give the same bytes, and a second write keeps the first stamp. */
    @Test
    fun `writing twice keeps the stamp and changes no byte`() {
        val family = OracleFamilies.QUEST
        val old = OracleFamilies.pathOf(family, repo).readText(Charsets.UTF_8).replace("\r\n", "\n")
        val tmp = File.createTempFile("oracle", ".json")
        try {
            val results = mapOf(
                "corpus/x/a.png" to mapOf<String, Any?>("size" to listOf(1080, 1920), "close_x" to null, "quest_progress" to (1 to 2)),
                "corpus/x/b.png" to mapOf<String, Any?>("size" to listOf(805, 1390), "close_x" to mapOf("fx" to 0.5), "quest_progress" to null))
            val skipped = mapOf<String, Any>("corpus/x/crop.png" to listOf(210, 42))
            assertTrue(OracleFamilies.write(family, results, skipped, repo, "2026-01-01T00:00:00", tmp))
            val first = tmp.readText(Charsets.UTF_8)
            assertTrue(!OracleFamilies.write(family, results, skipped, repo, "2026-01-02T00:00:00", tmp), "nothing changed, nothing written")
            assertEquals(first, tmp.readText(Charsets.UTF_8))
            assertTrue(first.startsWith("{\n\"written\": \"2026-01-01T00:00:00\",\n\"family\": \"quest\",\n\"opencv\": \"${Core.VERSION}\",\n"))
            assertTrue("\"readers\": {\"close_x\": {\"answered\": 1, \"distinct\": 2, \"frames\": 2}, " +
                       "\"quest_progress\": {\"answered\": 1, \"distinct\": 2, \"frames\": 2}}" in first)
            assertTrue("\"frames\": {\n\"corpus/x/a.png\": {\"close_x\": null, \"quest_progress\": [1, 2], \"size\": [1080, 1920]},\n" in first)
            assertTrue(first.endsWith("},\n\"skipped\": {\n\"corpus/x/crop.png\": [210, 42]\n}\n}\n"))
            // A changed answer is written, under a new stamp.
            val changed = results + ("corpus/x/a.png" to mapOf<String, Any?>("size" to listOf(1080, 1920), "close_x" to null, "quest_progress" to (1 to 3)))
            assertTrue(OracleFamilies.write(family, changed, skipped, repo, "2026-01-03T00:00:00", tmp))
            assertTrue(tmp.readText(Charsets.UTF_8).startsWith("{\n\"written\": \"2026-01-03T00:00:00\","))
        } finally {
            tmp.delete()
        }
        // The real file's head, as the writer reads it back: the regex finds the stamp.
        assertTrue(OracleFamilies.WRITTEN.find(old)?.range?.first == 2, "oracle/quest.json does not start with the written line")
    }
}
