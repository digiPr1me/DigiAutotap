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
 * Chef's Special's readers (Skewer.kt, SkewerIcons) against
 * oracle/skewer.json: every frame it names, every reader of the family, and
 * the answer has to be the one in the file. The frames are the round, its
 * menu, stage popup and dialogs, taken on 2026-09-30 at 1080 x 1920
 * (`corpus/skewer/`) and with their hole105 twins (`corpus/formats/skewer_*`),
 * PLAN_SKEWER.md SK1. Written by the oracle's holder in SK1b; until then the
 * family is `OracleFamilies.STAGED` and this suite has no file to read.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkewerOracleTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var oracle: JsonObject

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val file = OracleFamilies.pathOf(OracleFamilies.SKEWER, repo)
        assertTrue(file.exists(), "no oracle at $file -- run gradlew :core:writeOracle")
        oracle = Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `every reader gives the oracle's answer on every frame`() {
        val report = OracleFamilies.check(OracleFamilies.SKEWER, oracle, repo)
        report.print()
        if (report.mismatches.isNotEmpty()) fail(report.summary())
    }
}
