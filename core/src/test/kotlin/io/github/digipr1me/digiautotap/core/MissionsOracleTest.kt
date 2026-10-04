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
 * The EX Missions readers against oracle/missions.json: every frame of the
 * corpus, every reader of the family. The frames that carry the tile, the
 * Missions window and its EX tab are `corpus/missions/`, taken on LDPlayer
 * instance 1 on 2026-09-28 and 29 before, during and after 31 claims, and
 * the `missions` and `ex_missions` rows of `corpus/formats/`; the tile
 * reads on every main screen of the corpus, and the rest is where the
 * readers must say nothing.
 *
 * A frame that answers differently is a reader change to look at, never a
 * reason to edit the oracle by hand (NOTES.md, "One project").
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MissionsOracleTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var oracle: JsonObject

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val file = OracleFamilies.pathOf(OracleFamilies.MISSIONS, repo)
        assertTrue(file.exists(), "no oracle at $file -- run gradlew :core:writeOracle")
        oracle = Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `every reader gives the oracle's answer on every frame`() {
        val report = OracleFamilies.check(OracleFamilies.MISSIONS, oracle, repo)
        report.print()
        if (report.mismatches.isNotEmpty()) fail(report.summary())
    }
}
