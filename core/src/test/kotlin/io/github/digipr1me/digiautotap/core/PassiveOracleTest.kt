package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The passive and bond readers against oracle/passive.json: every frame it
 * names, every reader of the family, and the answer has to be the one in
 * the file.
 * A frame that answers differently is a reader change to look at, never a
 * reason to edit the oracle by hand (NOTES.md, "The oracle is the seam"):
 * if the change was meant, with a measurement, `gradlew :core:writeOracle`
 * writes the file again and its diff is the list of frames that tipped.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassiveOracleTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var oracle: JsonObject

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val file = OracleFamilies.pathOf(OracleFamilies.PASSIVE, repo)
        assertTrue(file.exists(), "no oracle at $file -- run gradlew :core:writeOracle")
        oracle = Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `every reader gives the oracle's answer on every frame`() {
        val report = OracleFamilies.check(OracleFamilies.PASSIVE, oracle, repo, Vision(ClassPathAssets))
        report.print()
        if (report.mismatches.isNotEmpty()) fail(report.summary())
    }
}
