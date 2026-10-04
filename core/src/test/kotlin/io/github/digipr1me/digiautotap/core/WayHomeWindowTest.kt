package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PLAN_RELEASE_1_3.md B60, 2026-10-01: the ways home of every task but
 * Dungeons met the game's own windows without knowing them -- its news after
 * the reset, its "Time Sale!" window, its Help tutorial -- and tapped a globe
 * or an X past them, or waited them out blind, and said "could not get back
 * to the main screen" with a `no_way_home` frame. Each way home now asks
 * [Stays.over] on every look and hands such a window back as it stands:
 * nothing tapped, no frame kept, one sentence; the director reads the same
 * windows (`news`, `sale`, `help`) and closes the news itself.
 *
 * Played here on the real frames (the news the way back to the list met on
 * instance 1 that morning, a sale window, a Help tutorial), each way home
 * asked twice of the same skill -- as the chain's second hand asks again
 * after a pause -- on a capture that counts every tap.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WayHomeWindowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val frames = LinkedHashMap<String, Mat>()

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        for ((what, path) in listOf(
                "news after its reset" to "corpus/dungeon/news_buddy_084458.png",
                "\"Time Sale!\" window" to "corpus/summon/time_sale_over_main_1080x2340_hole105_134844.png",
                "Help tutorial" to "corpus/preset/help_digivice_131037.png")) {
            val f = File(repo, path)
            if (f.exists()) frames[what] = OracleFamilies.read(f)
        }
    }

    @AfterAll
    fun release() = frames.values.forEach(Mat::release)

    /** One way home asked twice over [frame]: what it answered, what it tapped, what it kept, what it said. */
    private class Seen(val home: List<Boolean>, val taps: Int, val backs: Int, val kept: List<String>,
                       val log: List<String>)

    private fun play(frame: Mat, way: (Capture, (String) -> Unit, (Mat, String) -> Unit) -> () -> Boolean): Seen {
        val cap = DirectorTest.FakeCapture()
        cap.scene = { Paint.copy(frame) }
        val log = ArrayList<String>()
        val kept = ArrayList<String>()
        val home = way(cap, { log += it }, { _, tag -> kept += tag })
        val answers = listOf(home(), home())
        return Seen(answers, cap.taps.size, cap.backs, kept, log)
    }

    /** The ways home, each built as its skill builds it, the clock moving with every look. */
    private val ways: Map<String, (Capture, (String) -> Unit, (Mat, String) -> Unit) -> () -> Boolean> = mapOf(
        "Meat Field" to { cap, log, keep ->
            val s = FarmSkill(cap, dueAt = { null }, remember = { _, _, _ -> }, log = log, on = { true },
                              keep = keep, patience = 0.0, sleep = {})
            ({ s.leave() })
        },
        "Special Summon" to { cap, log, keep ->
            val s = SummonSkill(cap, { SummonSkill.Settings() }, log = log, keep = keep, on = { true },
                                patience = 0.0, tapEvery = 0.0)
            ({ s.leaveSummons() })
        },
        "Gekkomon Run" to { cap, log, keep ->
            var t = 0.0
            val s = RunnerSkill(cap, { RunnerSkill.Settings() }, log = log, on = { true }, keep = keep,
                                sleep = { t += it }, now = { t += 0.1; t })
            ({ s.leave() })
        },
        "Lost Sector Tower" to { cap, log, keep ->
            var t = 0.0
            val s = LostSectorSkill(cap, { LostSectorSkill.Settings() }, log = log, on = { true }, keep = keep,
                                    sleep = { t += it }, now = { t += 0.1; t })
            ({ s.leave() })
        },
        "EX Missions" to { cap, log, keep ->
            var t = 0.0
            val s = ExMissionsSkill(cap, log = log, on = { true }, keep = keep, sleep = { t += it },
                                    now = { t += 0.1; t })
            ({ s.leave() })
        },
        "Presets" to { cap, log, keep ->
            val s = PresetSkill(cap, { PresetSkill.Settings() }, log = log, keep = keep, on = { true }, sleep = {})
            ({ s.goHome() })
        },
        "the bond tour" to { cap, log, keep ->
            val s = BondTour(cap, log = log, on = { true }, keep = keep, sleep = {})
            ({ s.goHome() })
        },
    )

    @Test
    fun `every way home hands the game's own windows back as they stand`() {
        assumeTrue(frames.size == 3, "the three frames are not all in the corpus: ${frames.keys}")
        for ((what, frame) in frames) {
            assertTrue(Stays.window(frame) == what, "$what reads as ${Stays.window(frame)}")
            for ((task, way) in ways) {
                val seen = play(frame, way)
                val where = "$task over the $what: ${seen.log}"
                assertEquals(listOf(false, false), seen.home, where)
                assertEquals(0, seen.taps, "$where: tapped")
                assertEquals(0, seen.backs, "$where: the back key")
                assertTrue(seen.kept.isEmpty(), "$where: kept ${seen.kept}")
                assertTrue(seen.log.any {
                    it.endsWith("the game's $what is over the way home -- leaving it to the director, nothing tapped")
                }, where)
                assertTrue(seen.log.none { it.contains("could not get back") }, where)
            }
        }
    }
}
