package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Gekkomon Run skill offline, no emulator needed: test_runner_flow.py in
 * Kotlin, the same cases where the phone has them.
 *
 * What the Python test did with a dozen monkey-patched readers over a list
 * of strings, this does with pixels: the world is **painted** (PaintRunner)
 * and read by the real Runner.kt, and it reacts to the taps it is sent the
 * way the game would -- Play Game starts a run, a press of the right kind
 * inside the window before an obstacle arrives survives it, a wrong or a
 * missing one ends the run in the result window. The clock is the test's:
 * every grab costs what the phone's costs, so the loop is run at the pace
 * it will really have, three frames a second, which is the whole question
 * about this skill on a phone.
 *
 * The frames the readers were measured on are the oracle test's business
 * (RunnerOracleTest over corpus/runner); what is asked of a painted one is
 * that the flow around it can be followed.
 */
class RunnerSkillTest {

    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    // ------------------------------------------------------------------------
    // The painted frames, against the real readers
    // ------------------------------------------------------------------------
    @Test
    fun `the painted frames read as what they are painted as`() {
        val page = PaintRunner.eventPage()
        assertNotNull(Runner.eventPage(page), "the event page")
        assertNull(Runner.eventsDialog(page))
        assertNull(Runner.missionsDialog(page))
        assertEquals(Director.EVENT_PAGE, Director.classify(page).screen,
                     "the director has to see the event page here: ${Director.classify(page)}")
        page.release()

        val events = PaintRunner.eventsDialog()
        assertNotNull(Runner.eventsDialog(events), "the Events window")
        assertNull(Runner.eventPage(events), "not the event page: no X, no Play Game")
        val card = Runner.eventCard(Runner.eventsDialog(events)!!)
        val c = PaintRunner.EVENT_CARD
        assertTrue(card.fx in c[0]..c[2] && card.fy in c[1]..c[3], "the card is tapped on the card: $card")
        assertNull(Runner.eventsIcon(events), "no Events icon under the window")
        events.release()

        val pageAgain = PaintRunner.eventPage()
        val play = Runner.eventPage(pageAgain)!!
        val b = PaintRunner.PLAY_BAND
        assertTrue(play.fx in b[0]..b[2] && play.fy in b[1]..b[3], "Play Game is tapped on its row: $play")
        pageAgain.release()

        val main = PaintRunner.mainScreen()
        val icon = Runner.eventsIcon(main)
        val t = PaintRunner.EVENTS_TILE
        assertNotNull(icon, "the painted Events tile")
        assertTrue(icon.fx in t[0]..t[2] && icon.fy in t[1]..t[3], "the icon is tapped on its tile: $icon")
        main.release()
        val bare = PaintRunner.mainScreen(events = false)
        assertNull(Runner.eventsIcon(bare), "a main screen with no tile")
        bare.release()

        val result = PaintRunner.resultDialog()
        assertNotNull(Runner.resultDialog(result), "the result window")
        assertNull(Runner.pauseDialog(result), "not the pause menu: no Continue")
        result.release()

        val pause = PaintRunner.pauseDialog()
        assertNotNull(Runner.pauseDialog(pause), "the pause menu")
        assertNull(Runner.resultDialog(pause), "not the result window: Quit is on the left")
        pause.release()

        // The game's own prompts wear the same pink-and-blue pair on the same
        // row; the dark plate is what keeps them out (PLAN_GEKKOMON_RUN.md 3).
        val prompt = Paint.prompt(pink = true)
        assertNull(Runner.pauseDialog(prompt))
        assertNull(Runner.resultDialog(prompt))
        prompt.release()

        val missions = PaintRunner.missions(claims = 2)
        assertNotNull(Runner.missionsDialog(missions), "the Missions window")
        val claims = Runner.claimButtons(missions)
        assertEquals(2, claims.size, "two Claims: $claims")
        assertTrue(claims[0].fy < claims[1].fy, "top first")
        assertTrue(abs(claims[0].fy - PaintRunner.claimAt(0).fy) < 0.01, "the first where it was painted: ${claims[0]}")
        assertNotNull(Runner.missionsX(missions), "the dimmed X under the window")
        assertNull(Summon.exitButton(missions), "which the white X's own reader refuses")
        assertNull(Runner.rewardOverlay(missions))
        assertNull(Runner.eventsDialog(missions), "the header block is not the Events window")
        missions.release()

        val none = PaintRunner.missions(claims = 0)
        assertEquals(emptyList<Explore.Target>(), Runner.claimButtons(none))
        none.release()

        val sheet = PaintRunner.rewardSheet()
        assertNotNull(Runner.rewardOverlay(sheet), "the Reward sheet")
        assertNull(Runner.missionsDialog(sheet), "not the Missions window under it")
        assertNull(Runner.missionsX(sheet), "and its X is not asked for there")
        sheet.release()

        // The road: each thing of the plan's table, and what beats it.
        for ((kind, want) in listOf("cube" to Runner.JUMP_KIND, "spike" to Runner.SLIDE_KIND,
                                    "glitch" to Runner.SLIDE_KIND)) {
            val img = PaintRunner.run(listOf(PaintRunner.Thing(kind, 0.60)))
            val found = Runner.obstacles(img)
            assertEquals(1, found.size, "$kind: one thing on the road, got $found")
            assertEquals(want, Runner.answer(found[0]), "$kind -> $want")
            assertTrue(abs(found[0].fx - 0.60) < 0.01, "$kind where it was painted: ${found[0]}")
            assertNull(Runner.resultDialog(img), "$kind is not a Quit button")
            assertNull(Runner.eventPage(img), "a run is not the event page")
            img.release()
        }
        val entering = PaintRunner.run(listOf(PaintRunner.Thing("cube", 0.92)))
        val cut = Runner.obstacles(entering)
        assertEquals(1, cut.size)
        assertTrue(cut[0].fx1 >= Runner.EDGE, "a cube entering at the edge: ${cut[0]}")
        entering.release()

        val orbs = PaintRunner.run(orbsAt = 0.50)
        assertTrue(Runner.orbsInAir(orbs), "a row of orbs at jump height")
        assertEquals(emptyList<Runner.Obstacle>(), Runner.obstacles(orbs), "orbs are pale, not obstacles")
        orbs.release()
        val clear = PaintRunner.run()
        assertTrue(!Runner.orbsInAir(clear))
        clear.release()

        val filling = PaintRunner.run(green = 0.5)
        val (g, p) = Runner.barState(filling)
        assertTrue(g > 0.4 && p < 0.05, "half green: $g / $p")
        filling.release()
        val fever = PaintRunner.run(pink = 0.4)
        val (g2, p2) = Runner.barState(fever)
        assertTrue(g2 < 0.05 && p2 >= Runner.FEVER_ON, "fever: $g2 / $p2")
        fever.release()
    }

    // ------------------------------------------------------------------------
    // The rule, the track and the counter -- pure functions
    // ------------------------------------------------------------------------
    @Test
    fun `answer is the plan's table`() {
        assertEquals(Runner.SLIDE_KIND, Runner.answer(bottom = 0.66, h = 0.33), "tall on the road is slide")
        assertEquals(Runner.JUMP_KIND, Runner.answer(bottom = 0.70, h = 0.08), "low on the road is jump")
        assertEquals(Runner.SLIDE_KIND, Runner.answer(bottom = 0.50, h = 0.07), "floating is slide whatever its height")
    }

    private fun sight(fx: Double, h: Double = 0.08, bottom: Double = 0.70, fx1: Double? = null) =
        Runner.Obstacle(fx = fx, fx1 = fx1 ?: (fx + 0.12), top = bottom - h, bottom = bottom,
                        h = h, w = 0.12, share = 0.005)

    @Test
    fun `the track answers test_runner_flow's cases on the PC's frames`() {
        // gap 0: runner.py's own 0.15 and 0.3.
        var t = Runner.Track()
        for ((i, fx) in listOf(0.90, 0.85, 0.80, 0.75).withIndex()) t.feed(i * 0.05, sight(fx))
        var est = t.estimate(0.15)
        assertNotNull(est, "speed from four sightings")
        assertTrue(abs(est.speed - 1.0) < 0.05, "speed: $est")
        assertTrue(abs(est.eta - 0.41) < 0.03, "eta from the last sighting: $est")
        assertTrue(est.measured)
        assertEquals(Runner.JUMP_KIND, t.kind, "a low thing on the road is a jump")

        // A sighting whose right edge touches the frame is still entering, and
        // its left edge stands still: not used for speed.
        t = Runner.Track()
        t.feed(0.00, sight(0.86, fx1 = 0.98))
        t.feed(0.05, sight(0.86, fx1 = 0.98))
        t.feed(0.10, sight(0.86, fx1 = 0.98))
        assertNull(t.estimate(0.10), "nothing to estimate from edge sightings alone")
        t.feed(0.15, sight(0.80))
        t.feed(0.20, sight(0.75))
        est = t.estimate(0.20)
        assertNotNull(est)
        assertTrue(abs(est.speed - 1.0) < 0.05, "speed once two clean sightings exist: $est")

        t = Runner.Track()
        t.feed(0.0, sight(0.50))
        t.feed(0.1, sight(0.40))
        t.feed(0.2, sight(0.85))
        assertEquals(1, t.seen.size, "a blob far to the right starts a new track")

        t = Runner.Track()
        t.feed(0.0, sight(0.60))
        t.feed(0.1, sight(0.50))
        assertNull(t.estimate(0.5), "stale after no sighting")

        t = Runner.Track()
        t.feed(0.0, sight(0.80, h = 0.05, bottom = 0.50))
        t.feed(0.1, sight(0.70, h = 0.05, bottom = 0.50))
        assertEquals(Runner.SLIDE_KIND, t.kind, "a floating thing asks for a slide")
    }

    @Test
    fun `on the phone's frames the track stretches its clocks and times one sighting with a prior`() {
        // Frames 0.4 s apart: the second sighting is 0.4 s after the first,
        // which at the PC's 0.3 would already be a new object.
        val t = Runner.Track { 0.4 }
        t.feed(0.0, sight(0.80))
        t.feed(0.4, sight(0.60))
        assertEquals(2, t.seen.size, "one track over two frames 0.4 s apart")
        val est = t.estimate(0.4, prior = 5.0)
        assertNotNull(est)
        assertTrue(est.measured && abs(est.speed - 0.5) < 0.01, "measured, not the prior: $est")
        assertTrue(abs(est.eta - 0.52) < 0.01, "0.26 to go at 0.5: $est")
        assertNotNull(t.estimate(0.4 + 0.7, prior = null), "still fresh 0.7 s on, two frames' worth")
        assertNull(t.estimate(0.4 + 0.9, prior = null), "stale after two frames and more")

        // One clean sighting: nothing on the PC, the prior on the phone.
        val one = Runner.Track { 0.4 }
        one.feed(0.0, sight(0.98, fx1 = 1.0))
        one.feed(0.4, sight(0.70))
        assertNull(one.estimate(0.4), "no prior, no answer -- the PC's own")
        val guessed = one.estimate(0.4, prior = 0.6)
        assertNotNull(guessed)
        assertTrue(!guessed.measured && abs(guessed.eta - 0.6) < 0.01, "0.36 to go at 0.6: $guessed")

        // The prior itself, against the measurement it is drawn through.
        //
        // This used to read `speedPrior(50.0) >= 1.8`, which was the PC's
        // second point restated -- and that point is the artefact
        // PLAN_GEKKOMON_RUN.md 4.2 warns about, a sighting at the picture's
        // edge read as v 1.78 against a real 1.0. The line is the phone's own
        // now (Runner.SPEED_AT_START, SPEED_PER_SECOND), so what holds it is
        // the 88 pairs of adjacent sightings it was fitted to: the median
        // speed in each ten-second window of a run, 2026-09-22.
        assertEquals(Runner.SPEED_AT_START, Runner.speedPrior(0.0))
        for ((t, measured) in listOf(5.0 to 0.63, 15.0 to 0.65, 25.0 to 0.78,
                                     35.0 to 0.94, 50.0 to 0.91)) {
            val line = Runner.speedPrior(t)
            assertTrue(abs(line - measured) < 0.15,
                       "at $t s the line says $line and the phone measured $measured")
        }
    }

    @Test
    fun `the fever counter counts rising edges`() {
        var fc = Runner.FeverCounter()
        val edges = listOf(0.0, 0.0, 0.3, 0.4, 0.3, 0.02, 0.0, 0.35, 0.1, 0.0).map { fc.feed(it) }
        assertEquals(2, fc.count, "two rising edges: $edges")
        assertEquals(2, edges.count { it })
        fc = Runner.FeverCounter()
        listOf(0.3, 0.1, 0.12, 0.3).forEach { fc.feed(it) }
        assertEquals(1, fc.count, "a dip that never falls below FEVER_OFF is one fever")
    }

    // ------------------------------------------------------------------------
    // The world: a game that reacts, at the phone's pace
    // ------------------------------------------------------------------------
    /** An obstacle the world sends down the road. */
    class Coming(val kind: String, val spawn: Double, val speed: Double) {
        val thing = PaintRunner.Thing(kind, 1.0)
        /** When its left edge reaches the character. */
        val arrival: Double get() = spawn + (1.0 - Runner.CHAR_RIGHT) / speed
        fun at(t: Double): PaintRunner.Thing? {
            val fx = 1.0 - speed * (t - spawn)
            return if (t < spawn || fx + thing.w < Runner.AHEAD) null else PaintRunner.Thing(kind, fx)
        }
        var pressed: Double? = null
    }

    /**
     * The game, as far as this skill can see it. `grab` costs what the
     * phone's costs -- the pace floor, then the picture, then the
     * conversion (RunnerSkill.FRAME_AGE) -- and paints the screen as it
     * stood when the picture was taken. A tap is read back by the fraction
     * it landed on and moves the screen the way the game would.
     *
     * Obstacle times are seconds after Play Game was tapped. The real run
     * opens with a countdown, which is what START_GRACE is for, and the
     * skill's own clock starts once Play Game is confirmed (two looks, a
     * pause between): the first obstacle of a world stands past both.
     */
    class World(private val obstacles: List<Coming> = emptyList(),
                /** (from, to) of each Fever Time, seconds into the run. */
                private val fevers: List<Pair<Double, Double>> = emptyList(),
                var claims: Int = 0,
                /** A window over the event page that eats Play Game. */
                var playGameDead: Boolean = false,
                /** The main screen has its Events tile. */
                var eventsIcon: Boolean = true) : Capture {
        var t = 100.0
        var screen = "page"
        var runStart = 0.0
        /** When the run died, or null while it goes on. */
        var deadAt: Double? = null
        val taps = ArrayList<String>()
        val log = ArrayList<String>()
        var grabs = 0
        var swipes = 0
        var runs = 0
        var survived = 0

        companion object {
            const val PACE = 0.35
            const val PICTURE = 0.01
            /**
             * A press between this long and [LATEST] before an obstacle
             * arrives beats it: the jump's own half second, less the time
             * the tap takes to show (PLAN_GEKKOMON_RUN.md 2: ~120 ms).
             */
            const val EARLIEST = 0.45
            const val LATEST = 0.10
        }

        fun sleep(s: Double) { t += s }

        private fun runTime(at: Double) = at - runStart

        override fun grab(): Mat {
            grabs += 1
            t += PACE + PICTURE
            val img = paint(t)
            t += RunnerSkill.FRAME_AGE - PICTURE
            return img
        }

        private fun paint(at: Double): Mat = when (screen) {
            "main" -> PaintRunner.mainScreen(eventsIcon)
            "events" -> PaintRunner.eventsDialog()
            "page" -> PaintRunner.eventPage()
            "run" -> runFrame(at)
            "result" -> PaintRunner.resultDialog()
            "pause" -> PaintRunner.pauseDialog()
            "missions" -> PaintRunner.missions(claims)
            "reward" -> PaintRunner.rewardSheet()
            else -> throw IllegalStateException(screen)
        }

        private fun runFrame(at: Double): Mat {
            val rt = runTime(at)
            // An obstacle that arrived without the right press ends the run
            // a second later, in the result window.
            for (o in obstacles) {
                if (deadAt == null && rt > o.arrival && !beaten(o)) {
                    deadAt = o.arrival + 1.0
                    log.add("died on the ${o.kind} at ${"%.2f".format(o.arrival)} (pressed ${o.pressed})")
                }
            }
            val dead = deadAt
            if (dead != null && rt >= dead) {
                screen = "result"
                return PaintRunner.resultDialog()
            }
            val things = obstacles.mapNotNull { it.at(rt) }
            val pink = if (fevers.any { rt in it.first..it.second }) 0.4 else 0.0
            return PaintRunner.run(things, green = 0.3, pink = pink)
        }

        private fun beaten(o: Coming): Boolean {
            val p = o.pressed ?: return false
            val before = o.arrival - p
            return before <= EARLIEST && before >= LATEST
        }

        private fun near(fx: Double, fy: Double, target: Explore.Target) =
            abs(fx - target.fx) < 0.03 && abs(fy - target.fy) < 0.03

        /** Inside a painted place (fx0, fy0, fx1, fy1): where the game would take the tap. */
        private fun inside(fx: Double, fy: Double, r: DoubleArray) = fx in r[0]..r[2] && fy in r[1]..r[3]

        override fun tap(x: Int, y: Int) {
            val r = Dungeon.gameRectWh(PaintRunner.W, PaintRunner.H)
            val fx = (x - r.x0) / r.gw.toDouble()
            val fy = (y - r.y0) / r.gh.toDouble()
            val exitX = Explore.Target(Summon.EXIT_BUTTON_FX, Summon.EXIT_BUTTON_FY)
            when (screen) {
                "main" -> if (eventsIcon && inside(fx, fy, PaintRunner.EVENTS_TILE)) {
                    taps += "Events icon"; screen = "events"
                } else taps += "main %.3f/%.3f".format(fx, fy)
                "events" -> if (inside(fx, fy, PaintRunner.EVENT_CARD)) { taps += "card"; screen = "page" }
                    else taps += "events %.3f/%.3f".format(fx, fy)
                "page" -> when {
                    inside(fx, fy, PaintRunner.PLAY_BAND) -> {
                        taps += "Play Game"
                        if (!playGameDead) {
                            screen = "run"; runStart = t; deadAt = null; runs += 1
                            obstacles.forEach { it.pressed = null }
                        }
                    }
                    near(fx, fy, Runner.MISSIONS) -> { taps += "Missions"; screen = "missions" }
                    near(fx, fy, exitX) -> { taps += "X"; screen = "main" }
                }
                "run" -> {
                    val kind = when {
                        near(fx, fy, Runner.JUMP) -> Runner.JUMP_KIND
                        near(fx, fy, Runner.SLIDE) -> Runner.SLIDE_KIND
                        near(fx, fy, Runner.PAUSE) -> { taps += "pause"; screen = "pause"; return }
                        else -> return
                    }
                    val rt = runTime(t)
                    taps += "$kind@${"%.2f".format(rt)}"
                    // The press goes to the next obstacle still ahead.
                    val next = obstacles.filter { it.arrival > rt }.minByOrNull { it.arrival } ?: return
                    if (next.pressed == null && next.thing.answer == kind) {
                        next.pressed = rt
                        if (beaten(next)) survived += 1
                    }
                }
                "pause" -> if (near(fx, fy, Runner.QUIT_PAUSE)) { taps += "Quit"; screen = "page" }
                "result" -> if (near(fx, fy, Runner.QUIT_RESULT)) { taps += "Quit"; screen = "page" }
                "missions" -> when {
                    claims > 0 && near(fx, fy, PaintRunner.claimAt(0)) -> { taps += "Claim"; claims -= 1; screen = "reward" }
                    near(fx, fy, Runner.DAILY_TAB) -> taps += "Daily Missions tab"
                    near(fx, fy, exitX) -> { taps += "X"; screen = "page" }
                }
                "reward" -> { taps += "Tap to close"; screen = "missions" }
            }
        }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) { swipes += 1 }
        override fun back() {}
        override fun inFront(): String? = "game"
    }

    /**
     * One skill over a world, with the world's clock. [runs] is the
     * sitting's ceiling: a session short of its Fever Times would otherwise
     * paint thirty runs' worth of frames to give up.
     */
    class Rig(val world: World, fevers: Int = RunnerSkill.FEVER_TARGET, runs: Int = RunnerSkill.RUNS_MAX) {
        val log = ArrayList<String>()
        var on = true
        val kept = ArrayList<String>()
        val skill = RunnerSkill(
            world, { RunnerSkill.Settings(fevers) },
            log = { log += it }, on = { on }, keep = { _, tag -> kept += tag },
            runsMax = runs, sleep = { world.sleep(it) }, now = { world.t })
    }

    /**
     * Four things on the road. The Fever Time the flow tests paint, 16 to
     * 17.5 s, comes after the third has arrived (15.3 s) and before the
     * fourth.
     */
    private fun fourThings() = listOf(Coming("cube", 7.5, 0.6), Coming("spike", 11.0, 0.7),
                                      Coming("glitch", 14.5, 0.8), Coming("cube", 18.0, 0.9))

    @Test
    fun `a run at three frames a second beats its obstacles and lets the run end at its Fever Times`() {
        // A cube to jump, a spike and a floating block to slide under, one
        // Fever Time, and a fourth thing on the road. The target is one, so
        // the run has what it came for once the bar turns pink and the last
        // cube goes unpressed: a run that is quit banks nothing.
        val world = World(obstacles = fourThings(), fevers = listOf(16.0 to 17.5))
        val rig = Rig(world, fevers = 1)
        val why = rig.skill.playRun()
        assertEquals("enough", why, "${rig.log} / ${world.taps}")
        assertEquals(3, world.survived, "three obstacles beaten: ${world.taps} / ${world.log}")
        assertEquals(listOf(Runner.JUMP_KIND, Runner.SLIDE_KIND, Runner.SLIDE_KIND),
                     world.taps.filter { "@" in it }.map { it.substringBefore('@') },
                     "one press per obstacle, and none after the Fever Time: ${world.taps}")
        assertTrue(rig.log.any { it.contains("Fever Time 1 of 1,") }, rig.log.toString())
        assertTrue(rig.log.any { it.contains("that is enough") }, rig.log.toString())
        assertTrue(world.log.any { it.startsWith("died on the cube") }, "the run died, it was not quit: ${world.log}")
        assertTrue("pause" !in world.taps, world.taps.toString())
        assertEquals("page", world.screen, "back on the event page: ${world.taps}")
        assertEquals("Quit", world.taps.last(), world.taps.toString())
    }

    @Test
    fun `short of its target a run plays on past a Fever Time`() {
        // The same road with the page's own nine: one Fever Time is not
        // enough, so all four are beaten and the run is played on.
        val world = World(obstacles = fourThings(), fevers = listOf(16.0 to 17.5))
        val rig = Rig(world)
        val why = rig.skill.playRun()
        assertEquals(4, world.survived, "four obstacles beaten: ${world.taps} / ${world.log}")
        assertEquals(listOf(Runner.JUMP_KIND, Runner.SLIDE_KIND, Runner.SLIDE_KIND, Runner.JUMP_KIND),
                     world.taps.filter { "@" in it }.map { it.substringBefore('@') },
                     "one press per obstacle, of its own kind, the last one included: ${world.taps}")
        assertTrue(rig.log.any { it.contains("Fever Time 1 of 9,") }, rig.log.toString())
        assertTrue(rig.log.none { it.contains("that is enough") }, rig.log.toString())
        // Nothing comes after the fourth and no score can be read off a
        // painted frame, so the run ends the way an empty road ends it.
        assertEquals("capped", why, "${rig.log} / ${world.taps}")
        assertEquals("page", world.screen, "back on the event page: ${world.taps}")
        assertTrue(world.taps.last() == "Quit", world.taps.toString())
    }

    @Test
    fun `the time cap lets the run die, and quits it when nothing comes`() {
        // No score can be read off a painted frame, so RUN_TIME_CAP is the
        // cap, and with no obstacle left to die on DIE_TIMEOUT quits through
        // the pause menu.
        val world = World(obstacles = listOf(Coming("cube", 7.5, 0.6)))
        val rig = Rig(world)
        val why = rig.skill.playRun()
        assertEquals("capped", why, "${rig.log} / ${world.taps}")
        assertEquals(1, world.survived, "the one cube was jumped first: ${world.taps} / ${world.log}")
        assertTrue(rig.log.any { it.contains("score cannot be read") }, rig.log.toString())
        assertEquals(listOf("pause", "Quit"), world.taps.takeLast(2), world.taps.toString())
        assertEquals("page", world.screen)
    }

    @Test
    fun `the main switch leaves a run through the pause menu`() {
        val world = World(obstacles = listOf(Coming("cube", 30.0, 0.6)))
        val log = ArrayList<String>()
        var frames = 0
        // The switch goes off after a few frames of the run.
        val counting = object : Capture by world {
            override fun grab(): Mat { frames += 1; return world.grab() }
        }
        val skill = RunnerSkill(counting, { RunnerSkill.Settings() }, log = { log += it },
                                on = { frames < 8 }, sleep = { world.sleep(it) }, now = { world.t })
        val why = skill.playRun()
        assertEquals("stopped", why, "$log / ${world.taps}")
        assertEquals(listOf("pause", "Quit"), world.taps.takeLast(2), world.taps.toString())
        assertTrue(world.runs == 1 && world.screen == "page")
    }

    @Test
    fun `Play Game that starts nothing is lost, and only Play Game was tried`() {
        val world = World(playGameDead = true)
        val rig = Rig(world)
        val why = rig.skill.playRun()
        assertEquals("lost", why)
        assertTrue(world.taps.isNotEmpty() && world.taps.all { it == "Play Game" }, world.taps.toString())
        assertEquals(0, world.runs)
        assertTrue(rig.kept.contains("no_run"), rig.kept.toString())
    }

    @Test
    fun `go_to_event walks main to Events to the page, and refuses a screen it does not know`() {
        val world = World().apply { screen = "main" }
        val rig = Rig(world)
        assertTrue(rig.skill.goToEvent(), "${rig.log} / ${world.taps}")
        assertEquals(listOf("Events icon", "card"), world.taps)
        assertEquals("page", world.screen)

        val strange = World().apply { screen = "pause" }
        val rig2 = Rig(strange)
        assertTrue(!rig2.skill.goToEvent())
        assertEquals(emptyList<String>(), strange.taps, "an unknown screen is refused without a tap")
        assertTrue(rig2.kept.contains("not_main_screen"))
    }

    /**
     * The way in hangs off a reader, and a main screen the reader does not
     * answer on -- no Events tile that day, or a boss's name banner across
     * it -- is not tapped where the tile used to be (NOTES.md, "A constant
     * is a place on one display"). Kept, so that the frame says why.
     */
    @Test
    fun `a main screen without the Events icon is refused without a tap`() {
        val world = World(eventsIcon = false).apply { screen = "main" }
        val rig = Rig(world)
        assertTrue(!rig.skill.goToEvent())
        assertEquals(emptyList<String>(), world.taps, "tapped a main screen with no Events tile")
        assertEquals("main", world.screen)
        assertTrue(rig.kept.contains("no_events_icon"), rig.kept.toString())
        assertTrue(rig.log.any { it == "the Events icon is not on the main screen" }, rig.log.toString())

        // And run() goes on from there as a skill that could not get in:
        // parked, home, nothing tapped on the way.
        val world2 = World(eventsIcon = false).apply { screen = "main" }
        val rig2 = Rig(world2)
        val outcome = rig2.skill.run()
        assertEquals(Result.PARKED, outcome.result)
        assertEquals(emptyList<String>(), world2.taps)
    }

    /** Called by nothing since 2026-09-23, and kept working for the day it is wanted back. */
    @Test
    fun `claim_missions takes the one Claim, closes its sheet and comes back to the page`() {
        val world = World(claims = 1)
        val rig = Rig(world)
        val n = rig.skill.claimMissions()
        assertEquals(1, n, "${rig.log} / ${world.taps}")
        assertEquals("page", world.screen, "back on the event page: ${world.taps}")
        val order = world.taps
        assertTrue(order.indexOf("Missions") < order.indexOf("Claim") &&
                   order.indexOf("Claim") < order.indexOf("Tap to close") &&
                   order.indexOf("Tap to close") < order.lastIndexOf("X"), order.toString())
        assertTrue(world.swipes >= 2 + RunnerSkill.CLAIM_SWEEPS - 1, "two up, then a page down per sweep: ${world.swipes}")
        assertEquals(0, world.claims)
    }

    @Test
    fun `work plays to its Fever Times, claims nothing and ends on the event page, and run ends at home`() {
        // A Claim is waiting under Missions, and the pass does not go there.
        val world = World(obstacles = listOf(Coming("cube", 9.0, 0.6)), fevers = listOf(6.0 to 7.5), claims = 1)
        val rig = Rig(world, fevers = 1)
        val page = PaintRunner.eventPage()
        assertEquals(true, rig.skill.seesWork(Director.EVENT_PAGE, page), "before the pass the page is work")
        val outcome = rig.skill.work(page)
        assertEquals(Result.DONE, outcome.result, "${rig.log} / ${world.taps}")
        assertEquals("page", world.screen, "work hands back the screen it was given")
        assertEquals(1, world.runs, "one Fever Time was the target, and the first run had it: ${world.taps}")
        assertTrue("Missions" !in world.taps && world.claims == 1, "nothing claimed: ${world.taps}")
        assertEquals(mapOf("runs" to 1, "fevers" to 1, "best" to 0),
                     rig.skill.lastCounts, "no score can be read off a painted frame, so no best")
        assertTrue(rig.log.last().contains("1 of 1 Fever Time") && rig.log.last().endsWith("done"), rig.log.last())
        // A pass that has its Fever Times hands the page back to the
        // director's one-work-per-visit rule.
        assertNull(rig.skill.seesWork(Director.EVENT_PAGE, page), "after a finished pass the director decides")
        page.release()

        val world2 = World(obstacles = listOf(Coming("cube", 9.0, 0.6)), fevers = listOf(6.0 to 7.5))
            .apply { screen = "main" }
        val rig2 = Rig(world2, fevers = 1)
        val outcome2 = rig2.skill.run()
        assertEquals(Result.DONE, outcome2.result, "${rig2.log} / ${world2.taps}")
        assertEquals("main", world2.screen, "run goes home: ${world2.taps}")
    }

    @Test
    fun `Fever Times add up over the runs of a pass, and a pass short of them gives up at the ceiling`() {
        // One Fever Time a run and a target of two: the first run is played
        // on past its Fever Time, the second is let die at it.
        val world = World(obstacles = listOf(Coming("cube", 9.0, 0.6)), fevers = listOf(6.0 to 7.5))
        val rig = Rig(world, fevers = 2)
        val page = PaintRunner.eventPage()
        assertEquals(Result.DONE, rig.skill.work(page).result, "${rig.log} / ${world.taps}")
        assertEquals(2, world.runs, world.taps.toString())
        assertEquals(1, world.survived, "the cube was jumped in the first run only: ${world.log}")
        assertTrue(rig.log.any { it.contains("Fever Time 2 of 2,") }, rig.log.toString())
        assertEquals(2, rig.skill.lastCounts["fevers"])

        // A target of nine with a ceiling of one run: short, said so, and done.
        val world2 = World(obstacles = listOf(Coming("cube", 9.0, 0.6)), fevers = listOf(6.0 to 7.5))
        val rig2 = Rig(world2, runs = 1)
        assertEquals(Result.DONE, rig2.skill.work(page).result, "${rig2.log} / ${world2.taps}")
        assertEquals(1, world2.runs)
        assertTrue(rig2.log.last().endsWith("1 runs and still short"), rig2.log.last())
        assertNull(rig2.skill.seesWork(Director.EVENT_PAGE, page), "a pass that gave up has finished too")
        page.release()
    }

    @Test
    fun `a pass the switch stopped leaves the page as work`() {
        val world = World(obstacles = listOf(Coming("cube", 30.0, 0.6)))
        val rig = Rig(world)
        var frames = 0
        val counting = object : Capture by world {
            override fun grab(): Mat { frames += 1; return world.grab() }
        }
        val skill = RunnerSkill(counting, { RunnerSkill.Settings() }, log = { rig.log += it },
                                on = { frames < 8 }, sleep = { world.sleep(it) }, now = { world.t })
        val page = PaintRunner.eventPage()
        assertEquals(Result.STOPPED, skill.work(page).result, "${rig.log} / ${world.taps}")
        assertEquals(true, skill.seesWork(Director.EVENT_PAGE, page),
                     "the Fever Times are still owed, so the page is handed back when the switch is on again")
        page.release()
    }

    @Test
    fun `the skill answers for the event page and nothing else`() {
        val skill = RunnerSkill(World(), { RunnerSkill.Settings() })
        assertTrue(skill.worksOn(Director.EVENT_PAGE))
        assertTrue(!skill.worksOn(Director.MAIN) && !skill.worksOn(Director.DUNGEON_LIST))
        // A target is the budget, and a target of nought is nothing to do.
        assertTrue(skill.hasBudget())
        assertTrue(!RunnerSkill(World(), { RunnerSkill.Settings(0) }).hasBudget())
        val page = PaintRunner.eventPage()
        assertEquals(true, skill.seesWork(Director.EVENT_PAGE, page),
                     "the event page is work until a pass has finished")
        assertNull(skill.seesWork(Director.MAIN, page), "and nothing is said about any other screen")
        page.release()
        assertEquals("runner", skill.key)
        assertTrue("runner" in SkillSettings.CHAINABLE && SkillSettings.CHAIN_NAMES.containsKey("runner"))
        // One field on the page, the Fever Times, and the two that went carry
        // no weight even where an old settings file still holds them.
        val fields = SkillSettings.page("runner").fields
        assertEquals(listOf("runner_fevers"), fields.map { it.key })
        val fevers = fields.single() as SkillSettings.Number
        assertEquals(RunnerSkill.FEVER_TARGET.toDouble(), fevers.default)
        assertEquals(1.0 to 20.0, fevers.min to fevers.max)
        assertEquals(RunnerSkill.Settings(9), Stored.runner(MapSettings(), NOON))
        assertEquals(RunnerSkill.Settings(3), Stored.runner(MapSettings(mapOf("runner_fevers" to 3)), NOON))
        assertEquals(RunnerSkill.Settings(9),
                     Stored.runner(MapSettings(mapOf("runner_claim" to true, "runner_cap" to 37000)), NOON),
                     "the dropped keys are read nowhere, not even off a file that has them")
        // The one limit the player cannot set is the one the page has to
        // name, so these two may not drift apart.
        assertEquals(15000, RunnerSkill.SCORE_CAP)
        assertTrue("15,000" in SkillSettings.page("runner").note,
                   "the page has to say the cap out loud: ${SkillSettings.page("runner").note}")
    }

    /**
     * The target is the game day's (the player, 2026-09-23): what one pass
     * played counts for the next, and the count starts again at the reset,
     * 08:00 Europe/Vienna -- not at midnight.
     */
    @Test
    fun `the Fever Times are counted over the game day`() {
        val s = MapSettings()
        repeat(6) { Stored.addRunnerFever(s, NOON) }
        assertEquals(RunnerSkill.Settings(9, 6), Stored.runner(s, NOON))
        assertEquals(3, Stored.runner(s, NOON).left)
        repeat(4) { Stored.addRunnerFever(s, NOON + 3600) }
        assertEquals(0, Stored.runner(s, NOON + 3600).left, "ten played, nine wanted")
        assertTrue(!RunnerSkill(World(), { Stored.runner(s, NOON + 3600) }).hasBudget(),
                   "a day that has its Fever Times is nothing to do")
        // 23:30 is still the same game day, 08:00 the next morning is not.
        assertEquals(10, Stored.runner(s, NOON + 11.5 * 3600).doneToday)
        assertEquals(0, Stored.runner(s, NOON + 20 * 3600).doneToday)
    }

    private companion object {
        /** 2026-09-23 12:00 in Vienna (CEST, UTC+2). */
        const val NOON = 1790157600.0
    }
}
