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
 * The Camp station readers against oracle/camp_station.json: every frame of
 * the corpus, every reader of the family (PLAN_CAMP.md C2). The frames that
 * carry a station's window, its Reward Boxes and an Attempt's Results are in
 * `corpus/camp/` -- LDPlayer instance 1 on 2026-10-19, before, during and
 * after one Attempt and one Auto Clear, and the plan session's station frames
 * of both instances -- and the `camp_station_*` and `camp_boxes_*` rows of
 * `corpus/formats/`; on every other frame the readers must say nothing.
 *
 * A frame that answers differently is a reader change to look at, never a
 * reason to edit the oracle by hand (NOTES.md, "One project").
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampStationOracleTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var oracle: JsonObject

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val file = OracleFamilies.pathOf(OracleFamilies.CAMP_STATION, repo)
        assertTrue(file.exists(), "no oracle at $file -- run gradlew :core:writeOracle")
        oracle = Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `every reader gives the oracle's answer on every frame`() {
        val report = OracleFamilies.check(OracleFamilies.CAMP_STATION, oracle, repo)
        report.print()
        if (report.mismatches.isNotEmpty()) fail(report.summary())
    }
}
