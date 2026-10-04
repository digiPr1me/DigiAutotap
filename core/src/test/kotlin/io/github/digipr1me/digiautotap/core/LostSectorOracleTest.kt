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
 * The Lost Sector Tower's readers against oracle/lost_sector.json: every
 * frame of the corpus, every reader of the family. The frames that carry the
 * Crests page and the tower's panel are `corpus/lost_sector/` and the format
 * rows of 2026-09-28/29 in `corpus/formats/`, taken on LDPlayer with the app
 * stopped (PLAN_DAILY_LOST_SECTOR_PRESETS.md 4.1), and one panel of another
 * day in `corpus/passive/`; the rest of the corpus is where the readers must
 * say nothing.
 *
 * A frame that answers differently is a reader change to look at, never a
 * reason to edit the oracle by hand (NOTES.md, "One project").
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LostSectorOracleTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var oracle: JsonObject

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val file = OracleFamilies.pathOf(OracleFamilies.LOST_SECTOR, repo)
        assertTrue(file.exists(), "no oracle at $file -- run gradlew :core:writeOracle")
        oracle = Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `every reader gives the oracle's answer on every frame`() {
        val report = OracleFamilies.check(OracleFamilies.LOST_SECTOR, oracle, repo)
        report.print()
        if (report.mismatches.isNotEmpty()) fail(report.summary())
    }
}
