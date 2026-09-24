package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * test_tower_flow.py in Kotlin, the same script: the coordinate the point
 * lands on, then the skill's own loop -- the tap protocol, the beat, the
 * limit, the stop -- against the fake capture DirectorTest plays its frames
 * with. Cases 1 to 5 of the Python script are about `point_from_screen`,
 * which turns a mouse position on the PC's desktop into a fraction and has
 * no counterpart here (the phone sets its point elsewhere); what they ended
 * in, the round trip of a fraction to a device pixel, is the first case
 * below. Case 10 there is the pause, which the phone does not have: one main
 * switch, and off is a stop.
 */
class TowerSkillTest {

    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    private fun frame(w: Int, h: Int): Mat = Mat(h, w, CvType.CV_8UC3)

    /** now() and sleep() the skill is handed instead of the real clock. */
    class FakeClock {
        var t = 0.0
        val sleeps = ArrayList<Double>()
        fun now() = t
        fun sleep(s: Double) { sleeps += s; t += s }
    }

    /** One skill over a fake capture and a clock the test moves. */
    class Rig(
        interval: Double = 3.0,
        minutes: Int = 0,
        var saved: Pair<Double, Double>? = ON_GAME,
        game: String? = null,
        dryRun: Boolean = false,
        heartbeat: Int = TowerSkill.HEARTBEAT_TAPS,
    ) {
        val cap = DirectorTest.FakeCapture()
        val clock = FakeClock()
        val log = ArrayList<String>()
        var on = true
        var interval = interval
        var minutes = minutes
        var beforeSleep: (Double) -> Unit = {}
        val skill = TowerSkill(
            cap, { saved }, { this.interval }, { this.minutes }, { game }, dryRun,
            on = { on }, log = { log += it }, now = clock::now,
            sleep = { beforeSleep(it); clock.sleep(it) }, heartbeatTaps = heartbeat)
    }

    // -------------------------------------------------------------------
    // The point
    // -------------------------------------------------------------------

    @Test
    fun `the centre of the game is device pixel 540, 960 on an ADB-shaped frame`() {
        // Case 3: the centre maps to 540 exactly, whatever the shape.
        val (fx0, fy0, fw, fh) = Dungeon.GAME_IN_WINDOW.toList()
        val rig = Rig(saved = fx0 + fw / 2.0 to fy0 + fh / 2.0)
        rig.cap.scene = { frame(1080, 1920) }
        rig.skill.tap(1)
        assertEquals(listOf(540 to 960), rig.cap.taps)
        // And case 1's number: 0.4969 / 0.5036 lands on 564 / 940, within two pixels.
        val rig2 = Rig(saved = 0.4969 to 0.5036)
        rig2.cap.scene = { frame(1080, 1920) }
        rig2.skill.tap(1)
        val (x, y) = rig2.cap.taps.single()
        assertTrue(abs(x - 564) <= 2 && abs(y - 940) <= 2, "$x, $y")
    }

    @Test
    fun `a fraction lands on the same spot of the game on every frame shape`() {
        // Case 2's rule, on the shapes core reads: a window frame and a
        // device frame put a point at the same place in the game.
        val (fx0, fy0, fw, fh) = Dungeon.GAME_IN_WINDOW.toList()
        val saved = fx0 + fw * 0.55 to fy0 + fh * 0.60
        val seen = ArrayList<Pair<Double, Double>>()
        for ((w, h) in listOf(805 to 1390, 1080 to 1920)) {
            val rig = Rig(saved = saved)
            rig.cap.scene = { frame(w, h) }
            rig.skill.tap(1)
            val (x, y) = rig.cap.taps.single()
            val r = Dungeon.gameRectWh(w, h)
            seen += ((x - r.x0) / r.gw.toDouble()) to ((y - r.y0) / r.gh.toDouble())
        }
        assertTrue(abs(seen[0].first - seen[1].first) < 0.004 && abs(seen[0].second - seen[1].second) < 0.004, "$seen")
    }

    @Test
    fun `a point off the game refuses outright, zero taps`() {
        // Case 12.
        val rig = Rig(saved = 0.01 to 0.01)
        val stats = rig.skill.tap(0)
        assertEquals(0, stats.clicks)
        assertTrue(rig.cap.taps.isEmpty())
        assertEquals("the saved point is not on the game", stats.reason)
        assertEquals(Result.PARKED, stats.end)
        assertEquals("parked: the saved point is not on the game", rig.skill.run().toString())
    }

    @Test
    fun `no saved point is no budget, and no tap`() {
        assertTrue(!TowerSkill.hasPoint(null, null))
        assertTrue(!TowerSkill.hasPoint(0.5, null))
        assertTrue(!TowerSkill.hasPoint(null, 0.5))
        assertTrue(TowerSkill.hasPoint(0.5, 0.5))
        val rig = Rig(saved = null)
        assertTrue(!rig.skill.hasBudget())
        assertEquals("parked: no point is saved", rig.skill.run().toString())
        assertTrue(rig.cap.taps.isEmpty())
        rig.saved = ON_GAME
        assertTrue(rig.skill.hasBudget(), "asked afresh, not remembered")
    }

    @Test
    fun `minutes at an interval are taps, chain_tower_limit's own table`() {
        assertEquals(0, TowerSkill.limit(0, 3.0), "no time asked: no bound")
        assertEquals(0, TowerSkill.limit(10, 0.0), "an interval of 0 is no bound, not a division")
        assertEquals(200, TowerSkill.limit(10, 3.0))
        assertEquals(240, TowerSkill.limit(12, 3.0))
        assertEquals(1, TowerSkill.limit(1, 120.0), "at least one tap for any time asked")
        assertEquals(9, TowerSkill.limit(1, 7.0), "60 / 7 = 8.57")
        assertEquals(2, TowerSkill.limit(1, 24.0), "2.5 rounds to the even one, as Python's round does")
        assertEquals(4, TowerSkill.limit(1, 15.0))
    }

    // -------------------------------------------------------------------
    // The skill itself
    // -------------------------------------------------------------------

    @Test
    fun `five clicks at 3 s are five taps at one pixel and four gaps`() {
        // Case 6: the limit stops the run right after the fifth tap, before a
        // trailing sleep that would never be felt by anyone.
        val rig = Rig(interval = 3.0)
        val stats = rig.skill.tap(5)
        assertEquals(5, stats.clicks)
        assertEquals(1, rig.cap.taps.toSet().size, "${rig.cap.taps}")
        assertEquals(5, rig.cap.taps.size)
        assertTrue(abs(rig.clock.t - 4 * 3.0) < 1e-9, "${rig.clock.t}")
        assertEquals("reached the limit", stats.reason)
        assertEquals(Result.DONE, stats.end)
        // The clock ran 12 s in 0.1 s slices, so int() of it is 11 or 12, as in Python.
        assertTrue(Regex("5 clicks in 1[12] s, reached the limit").matches(rig.log.last()), rig.log.last())
    }

    @Test
    fun `limit 1 taps exactly once`() {
        // Case 7.
        val rig = Rig()
        val stats = rig.skill.tap(1)
        assertEquals(1, stats.clicks)
        assertEquals(1, rig.cap.taps.size)
        assertTrue(rig.clock.sleeps.isEmpty(), "slept after the only tap")
    }

    @Test
    fun `a dry run counts its clicks and taps nothing`() {
        // Case 8.
        val rig = Rig(interval = 1.0, dryRun = true)
        val stats = rig.skill.tap(2)
        assertEquals(2, stats.clicks)
        assertTrue(rig.cap.taps.isEmpty(), "${rig.cap.taps}")
        assertTrue(rig.log.any { it.contains("would tap") }, rig.log.toString())
    }

    @Test
    fun `the main switch off between two taps ends the run inside a tenth of a second`() {
        // Case 9: interval 60, stopped after the first sleep -> the run returns
        // at once, and no single sleep was longer than TICK.
        val rig = Rig(interval = 60.0)
        rig.beforeSleep = { if (rig.clock.sleeps.size == 1) rig.on = false }
        val stats = rig.skill.tap(0)
        assertEquals(1, stats.clicks)
        assertEquals("stopped by the main switch", stats.reason)
        assertEquals(Result.STOPPED, stats.end)
        assertTrue(rig.clock.sleeps.all { it <= TowerSkill.TICK + 1e-9 }, "${rig.clock.sleeps}")
        assertEquals(2, rig.clock.sleeps.size, "one full slice, then the switch was seen")
        assertEquals("stopped", Outcome.STOPPED.toString())
    }

    @Test
    fun `the switch off before the first tap is no tap, and never in the middle of one`() {
        val rig = Rig()
        rig.on = false
        val stats = rig.skill.tap(3)
        assertEquals(0, stats.clicks)
        assertTrue(rig.cap.taps.isEmpty())
        assertEquals(Result.STOPPED, stats.end)
        // On again: the same skill goes on from nothing.
        rig.on = true
        assertEquals(3, rig.skill.tap(3).clicks)
    }

    @Test
    fun `a CaptureError at the heartbeat ends the run with a reason`() {
        // Case 11: the opening grab succeeds, the heartbeat does not.
        val rig = Rig(interval = 0.01, heartbeat = 1)
        rig.cap.scene = { n -> if (n >= 1) throw CaptureError("test: the service went away") else frame(805, 1390) }
        val stats = rig.skill.tap(0)
        assertTrue(stats.reason.startsWith("lost the screen"), stats.reason)
        assertEquals(Result.PARKED, stats.end)
        assertEquals(1, stats.clicks)
        val outcome = rig.skill.run()
        assertEquals(Result.PARKED, outcome.result, "$outcome")
    }

    @Test
    fun `no frame at all is a reason, not an exception`() {
        val rig = Rig()
        rig.cap.scene = { throw CaptureError("test: no frame") }
        val stats = rig.skill.tap(0)
        assertEquals(0, stats.clicks)
        assertEquals("could not reach the screen: test: no frame", stats.reason)
        assertEquals(Result.PARKED, stats.end)
    }

    @Test
    fun `the heartbeat aims again on the frame it grabs, and says how far it has come`() {
        val rig = Rig(interval = 0.1, heartbeat = 20)
        val stats = rig.skill.tap(45)
        assertEquals(45, stats.clicks)
        assertEquals(1 + 2, rig.cap.grabs, "the opening grab and one per twenty taps")
        assertEquals(listOf("clicked 20 times, going on", "clicked 40 times, going on"),
                     rig.log.filter { it.startsWith("clicked") })
    }

    @Test
    fun `a screen that turned to landscape ends the run at the heartbeat`() {
        val rig = Rig(interval = 0.1, heartbeat = 2)
        rig.cap.scene = { n -> if (n == 0) frame(805, 1390) else frame(1390, 805) }
        val stats = rig.skill.tap(0)
        assertEquals(2, stats.clicks)
        assertEquals("the screen turned to landscape", stats.reason)
        assertEquals(Result.PARKED, stats.end)
    }

    @Test
    fun `a tap is never sent while the game is not in front`() {
        val rig = Rig(interval = 1.0, game = GAME)
        // The player leaves the game after the second tap.
        rig.beforeSleep = { if (rig.cap.taps.size == 2) rig.cap.front = "com.android.launcher3" }
        val stats = rig.skill.tap(0)
        assertEquals(2, rig.cap.taps.size, "tapped into somebody else's app: ${rig.cap.taps}")
        assertEquals(Result.PARKED, stats.end)
        assertEquals("the game is not in front any more (com.android.launcher3)", stats.reason)
        // And before the first: nothing at all.
        val rig2 = Rig(game = GAME)
        rig2.cap.front = "com.android.launcher3"
        rig2.skill.tap(0)
        assertTrue(rig2.cap.taps.isEmpty())
    }

    @Test
    fun `format_summary reads as a sentence`() {
        // Case 13.
        val text = TowerSkill.formatSummary(TowerSkill.Stats(44, 134.0, "stopped from the launcher"))
        assertEquals("44 clicks in 2 min 14 s, stopped from the launcher", text)
        assertEquals("3 clicks in 9 s, finished", TowerSkill.formatSummary(TowerSkill.Stats(3, 9.4)))
    }

    // -------------------------------------------------------------------
    // The seam
    // -------------------------------------------------------------------

    @Test
    fun `it has no screen of its own, and work parks instead of tapping`() {
        val rig = Rig()
        for (screen in Director.SCREENS) assertTrue(!rig.skill.worksOn(screen), "answered for $screen")
        val img = frame(805, 1390)
        val outcome = rig.skill.work(img)
        assertEquals(Result.PARKED, outcome.result)
        assertTrue(rig.cap.taps.isEmpty())
        img.release()
    }

    @Test
    fun `run turns the chain step's minutes into taps at the saved interval, asked afresh`() {
        val rig = Rig(interval = 30.0, minutes = 2)
        assertEquals(Result.DONE, rig.skill.run().result)
        assertEquals(4, rig.cap.taps.size, "2 minutes at 30 s")
        // Tightened between two rounds of a chain: the step still means two minutes.
        rig.cap.taps.clear()
        rig.interval = 60.0
        rig.skill.run()
        assertEquals(2, rig.cap.taps.size, "2 minutes at 60 s")
    }

    @Test
    fun `the director runs a Tower-only chain fully automatically and the chain ends`() {
        val rig = Rig(interval = 3.0, minutes = 1, game = GAME)
        val chain = Chain(listOf(Chain.Step("tower", 1)), log = { rig.log += it })
        var t = 100.0
        rig.beforeSleep = { t += it }
        val director = DirectorLoop(
            rig.cap, listOf(rig.skill), emptyList(), { GAME }, { SkillSettings.MODE_FULL }, chain,
            on = { rig.on }, log = { rig.log += it }, now = { t })
        rig.cap.scene = { Paint.mainScreen() }
        val first = director.tick()
        assertEquals(Director.MAIN, first.screen)
        assertEquals("run tower", first.did, first.toString())
        // 1 minute at 3 s: twenty taps on one pixel, from the plain main screen.
        assertEquals(20, rig.cap.taps.size)
        assertEquals(1, rig.cap.taps.toSet().size)
        assertTrue(chain.finished, "index ${chain.index}")
        assertEquals(1, chain.ran)
        assertEquals(0, rig.cap.backs)
        director.close()
    }

    @Test
    fun `the director skips a Tower step while no point is saved`() {
        val rig = Rig(saved = null, game = GAME)
        val chain = Chain(listOf(Chain.Step("tower", 1)), log = { rig.log += it })
        var t = 100.0
        val director = DirectorLoop(
            rig.cap, listOf(rig.skill), emptyList(), { GAME }, { SkillSettings.MODE_FULL }, chain,
            on = { rig.on }, log = { rig.log += it }, now = { t })
        rig.cap.scene = { Paint.mainScreen() }
        val first = director.tick()
        assertNull(first.did)
        assertEquals(listOf("tower" to "nothing to do, on the settings as they stand"), chain.skipped)
        assertTrue(rig.cap.taps.isEmpty())
        assertNotNull(first.note)
        director.close()
    }

    @Test
    fun `the switch off in the middle of a chain step stops the step and the chain stays on it`() {
        val rig = Rig(interval = 3.0, minutes = 10, game = GAME)
        val chain = Chain(listOf(Chain.Step("tower", 10)), log = { rig.log += it })
        var t = 100.0
        rig.beforeSleep = { t += it; if (rig.cap.taps.size == 5) rig.on = false }
        val director = DirectorLoop(
            rig.cap, listOf(rig.skill), emptyList(), { GAME }, { SkillSettings.MODE_FULL }, chain,
            on = { rig.on }, log = { rig.log += it }, now = { t })
        rig.cap.scene = { Paint.mainScreen() }
        val first = director.tick()
        assertEquals(5, rig.cap.taps.size)
        assertEquals("Tower & Ruins: stopped by the main switch", first.note)
        assertTrue(!chain.finished && chain.index == 0, "a stopped step is not a finished one")
        director.close()
    }

    private companion object {
        const val GAME = "com.bandainamcoent.dgup_ww"
        val ON_GAME: Pair<Double, Double> =
            Dungeon.GAME_IN_WINDOW.let { (it[0] + it[2] / 2.0) to (it[1] + it[3] / 2.0) }
    }
}
