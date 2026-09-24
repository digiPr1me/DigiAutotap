package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The director's eye against oracle/director.json: every frame it names,
 * and `classify` has to name the screen the file names, by the reader the
 * file names it by. The order of the questions is in the oracle and
 * binding: a reader that tips a second frame's screen fails here. A frame
 * that answers differently is a reader change to look at, never a reason
 * to edit the oracle by hand (NOTES.md, "The oracle is the seam"): if the
 * change was meant, with a measurement, `gradlew :core:writeOracle` writes
 * the file again and its diff is the list of frames that tipped.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectorOracleTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var oracle: JsonObject

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val file = OracleFamilies.pathOf(OracleFamilies.DIRECTOR, repo)
        assertTrue(file.exists(), "no oracle at $file -- run gradlew :core:writeOracle")
        oracle = Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `classify names the oracle's screen on every frame`() {
        val report = OracleFamilies.check(OracleFamilies.DIRECTOR, oracle, repo, Vision(ClassPathAssets))
        report.print()
        val screens = HashMap<String, Int>()
        for (entry in oracle["frames"]!!.jsonObject.values) {
            screens.merge(entry.jsonObject["classify"]!!.jsonObject["screen"]!!.jsonPrimitive.content, 1, Int::plus)
        }
        println("screens: ${screens.toSortedMap()}")
        assertTrue(Director.SCREENS.containsAll(screens.keys), "a screen name not in SCREENS: ${screens.keys}")
        if (report.mismatches.isNotEmpty()) fail(report.summary())
    }
}
