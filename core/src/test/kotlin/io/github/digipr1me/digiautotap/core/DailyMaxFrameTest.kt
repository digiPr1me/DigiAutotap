package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [DungeonSkill.dailyAtMax] on real frames (PLAN_DAILY_LOST_SECTOR_PRESETS.md
 * DL5, notes/dungeons.md, "At its highest level the daily dungeon's panel has
 * nothing to attempt"): the panel at MAX -- the one in the corpus from an
 * earlier day and the one DL5 met live (corpus/dungeon/daily_panel_max_170523,
 * moved in with DL5b) -- and the screens nearest to it, where it
 * answers nothing: the panel with Reset and Attempt, the prefab before it,
 * and Network Defense Ops' ad-only panel, the closest centred violet button
 * the oracle knows.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DailyMaxFrameTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")

    private val skill = DungeonSkill(
        object : Capture {
            override fun grab(): Mat = Mat()
            override fun tap(x: Int, y: Int) {}
            override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
            override fun back() {}
            override fun inFront(): String? = null
        },
        { DungeonSkill.Settings(budgets = emptyMap()) })

    @BeforeAll
    fun load() = System.loadLibrary(Core.NATIVE_LIBRARY_NAME)

    private fun frame(path: String): Mat {
        val f = File(repo, path)
        assumeTrue(f.isFile, "$path is not on this machine")
        return Imgcodecs.imread(f.path)
    }

    @Test
    fun `the panel at MAX is read, on the corpus's frame and on the live one`() {
        for (path in listOf("corpus/passive/unclear_155914.png", "corpus/dungeon/daily_panel_max_170523.png")) {
            val img = frame(path)
            val b = assertNotNull(skill.dailyAtMax(img), path)
            assertEquals(0.4769, b.fx, 0.002, path)
            assertEquals(0.7934, b.fy, 0.002, path)
            img.release()
        }
    }

    @Test
    fun `the panels beside it are not`() {
        for (path in listOf("corpus/dungeon/daily_panel_221737.png",
                            "corpus/dungeon/daily_panel_prefab_053129.png",
                            "corpus/dungeon/panel_ad_only_netdef_120145.png",
                            "corpus/dungeon/panel_ad_only_082002.png")) {
            val img = frame(path)
            assertNull(skill.dailyAtMax(img), path)
            img.release()
        }
    }
}
