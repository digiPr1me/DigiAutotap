package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The port against the oracle: every frame oracle/dungeon.json names, every
 * reader this family carries, and the answer has to be the one in the file.
 * A frame that answers differently is a reader change to look at, never a
 * reason to edit the oracle by hand (NOTES.md, "The oracle is the seam"):
 * if the change was meant, with a measurement, `gradlew :core:writeOracle`
 * writes the file again and its diff is the list of frames that tipped.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DungeonOracleTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var oracle: JsonObject

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val file = OracleFamilies.pathOf(OracleFamilies.DUNGEON, repo)
        assertTrue(file.exists(), "no oracle at $file -- run gradlew :core:writeOracle")
        oracle = Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `every reader gives the oracle's answer on every frame`() {
        val report = OracleFamilies.check(OracleFamilies.DUNGEON, oracle, repo, Vision(ClassPathAssets))
        report.print()
        if (report.mismatches.isNotEmpty()) fail(report.summary())
    }

    @Test
    fun `recognise takes this long on the main screen`() {
        // ms per recognise on the JVM, for PLAN_ANDROID_1_SPIKE.md section 5
        // beside the Python and the in-app number. Not a threshold.
        val frames = oracle["frames"]!!.jsonObject
        val path = frames.entries.first { (p, e) ->
            e.jsonObject["auto_button"] !is JsonNull && p.startsWith("corpus/passive") &&
                e.jsonObject["size"].toString() == "[1080,1920]"
        }.key
        val img = Imgcodecs.imread(File(repo, path).path)
        repeat(5) { Dungeon.recognise(img) }
        val ms = (1..20).map {
            val t = System.nanoTime()
            Dungeon.recognise(img)
            (System.nanoTime() - t) / 1e6
        }.sorted()
        println("recognise on $path: min %.1f median %.1f max %.1f ms".format(
            ms.first(), (ms[9] + ms[10]) / 2, ms.last()))
    }
}
