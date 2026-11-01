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
 * The Camp readers (Camp.kt, PLAN_CAMP.md C1) against oracle/camp.json:
 * every frame of the corpus, every reader of the family. C1 wrote the file
 * and no suite held it; this one does, as every other family's does. The
 * frames that carry the Camp tab, the Camp screen, the tent, Camp Support,
 * the shop and its purchase dialog are in `corpus/camp/` and the camp rows
 * of `corpus/formats/`.
 *
 * A frame that answers differently is a reader change to look at, never a
 * reason to edit the oracle by hand (NOTES.md, "One project").
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampOracleTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var oracle: JsonObject

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val file = OracleFamilies.pathOf(OracleFamilies.CAMP, repo)
        assertTrue(file.exists(), "no oracle at $file -- run gradlew :core:writeOracle")
        oracle = Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `every reader gives the oracle's answer on every frame`() {
        val report = OracleFamilies.check(OracleFamilies.CAMP, oracle, repo)
        report.print()
        if (report.mismatches.isNotEmpty()) fail(report.summary())
    }
}
