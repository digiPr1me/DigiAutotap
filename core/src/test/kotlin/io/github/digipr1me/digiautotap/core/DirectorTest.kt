package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Mat
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The automaton against a script of painted frames (PLAN_ANDROID_3_DIRECTOR.md
 * 3.4): the main screen, the dungeon list, a prompt, a battle -- and the
 * test says whom the director gives the screen to and when it stands
 * still. The frames go through the real `classify`; the capture is a fake
 * that plays them back and records every tap, the way test_*_flow.py drive
 * the Python skills.
 */
class DirectorTest {

    /** Plays back whatever [scene] paints for the current call, and records taps. */
    class FakeCapture(var front: String? = GAME) : Capture {
        var scene: (Int) -> Mat = { Paint.mainScreen() }
        var grabs = 0
        val taps = ArrayList<Pair<Int, Int>>()
        var backs = 0

        override fun grab(): Mat = scene(grabs++)
        override fun tap(x: Int, y: Int) { taps += x to y }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun back() { backs += 1 }
        override fun inFront(): String? = front
        /** Every overlayClear and overlayBack, in order. */
        val overlay = ArrayList<String>()
        override fun overlayClear(fy0: Double, fy1: Double) { overlay += "clear $fy0-$fy1" }
        override fun overlayBack() { overlay += "back" }
    }

    /** A skill that answers for one screen and counts its calls. */
    class Counting(key: String, name: String, screen: String, var budget: Boolean = true,
                   var outcome: Outcome = Outcome.DONE,
                   /**
                    * A second skill this one plays inside its own turn, as the
                    * quest loop plays a dungeon: `QuestSkill.runDungeonStep`
                    * builds a `DungeonSkill` of its own through
                    * `QuestSkill.dungeonFor` and calls its `run`. That skill is
                    * the quest loop's, not the director's, so its turn never
                    * reaches [DirectorLoop]'s `busy` -- which is the whole
                    * reason the plate keeps saying "Quest loop" for the minute
                    * the dungeon takes.
                    */
                   var inner: Skill? = null) : Skill {
        override val key = key
        override val name = name
        private val screen = screen
        var worked = 0
        var ran = 0
        var left = 0
        /** The beat this one asks for on the main screen, null for the director's own. */
        var wants: Double? = null
        /** What the game does while [run] is at it: the screen the step leaves behind. */
        var onRun: () -> Unit = {}
        /** Its way home ([Skill.leave]): what the game does, and whether the main screen came back. */
        var onLeave: () -> Boolean = { false }
        override fun worksOn(screen: String) = screen == this.screen
        override fun hasBudget() = budget
        override fun work(img: Mat): Outcome { worked += 1; inner?.run(); return outcome }
        override fun run(): Outcome { ran += 1; inner?.run(); onRun(); return outcome }
        override fun leave(): Boolean { left += 1; return onLeave() }
        override fun beat(): Double? = wants
    }

    /** The director over a fake capture, with a clock the test moves. */
    class Rig(mode: String = SkillSettings.MODE_SEMI, steps: List<String> = emptyList()) {
        val cap = FakeCapture()
        val log = ArrayList<String>()
        var t = 100.0
        var on = true
        var mode = mode
        val dungeons = Counting("dungeon", "Dungeons", Director.DUNGEON_LIST)
        val summons = Counting("summon", "Special Summon", Director.SUMMON)
        val passive = Counting("passive", "the passive helper", Director.MAIN)
        val quest = Counting("quest", "the quest loop", Director.MAIN)
        val kept = ArrayList<String>()
        /** What the overlay's plate was told, in order: a name on the way in, null on the way out. */
        val named = ArrayList<String?>()
        val chain = Chain(steps.map { Chain.Step(it) }, log = { log += it })
        val director = DirectorLoop(
            cap, listOf(dungeons, summons), listOf(passive, quest), { GAME }, { this.mode }, chain,
            on = { on }, log = { log += it }, keep = { _, tag -> kept += tag },
            busy = { named += it }, now = { t })

        /** One round, then the clock moves by the beat the round asked for. */
        fun tick(): Director.Tick = director.tick().also { t += it.beat }

        /** Rounds until [seconds] have passed, the last tick returned. */
        fun run(seconds: Double): Director.Tick {
            val until = t + seconds
            var last = tick()
            while (t < until) last = tick()
            return last
        }
    }

    @Test
    fun `the painted frames read as the screens they are painted as`() {
        val cases = listOf(
            Paint.mainScreen() to Director.MAIN,
            Paint.battle(0) to Director.MAIN,
            Paint.dungeonBattle(0) to Director.UNKNOWN,
            Paint.dungeonList() to Director.DUNGEON_LIST,
            Paint.dungeonList(0.5) to Director.DUNGEON_LIST,
            Paint.prompt(pink = true) to Director.PROMPT,
            Paint.prompt(pink = false) to Director.EXIT_GAME,
            Paint.stageFailed() to Director.STAGE_FAILED,
        )
        for ((img, want) in cases) {
            val got = Director.classify(img)
            assertEquals(want, got.screen, "classify says $got")
            img.release()
        }
        // The motion the painters produce, against the director's own number.
        val a = Paint.dungeonList(); val b = Paint.dungeonList(0.5); val c = Paint.dungeonList()
        assertTrue(Director.motion(a, b)!! > Director.IDLE_SHARE, "a scrolled list has to read as moving")
        assertEquals(0.0, Director.motion(a, c))
        assertTrue(Director.idle(a, c, Director.DUNGEON_LIST))
        assertTrue(!Director.idle(a, b, Director.DUNGEON_LIST))
        assertTrue(Director.idle(a, b, Director.MAIN), "the main screen is outside the rule")
        assertNull(Director.motion(a, Mat()), "a pair that cannot be compared has no answer")
        Paint.release(a, b, c)
    }

    /**
     * The helper after a tap on the figure asks for Director.BEAT_WATCH for
     * PassiveSkill.BOND_WATCH, so that a bubble the Digimon's death took
     * away is seen the moment it is back. The director takes the shortest
     * answer of its round-skills, and its own slow beat when none has one.
     */
    @Test
    fun `a round-skill that asks for a faster beat gets it on the main screen, and only there`() {
        val r = Rig()
        r.cap.scene = { Paint.battle(it) }
        r.passive.wants = Director.BEAT_WATCH
        assertEquals(Director.BEAT_WATCH, r.tick().beat)
        r.quest.wants = 1.5
        assertEquals(Director.BEAT_WATCH, r.tick().beat, "the shortest answer wins")
        r.passive.wants = null
        assertEquals(1.5, r.tick().beat)
        r.quest.wants = null
        assertEquals(Director.BEAT_MAIN, r.tick().beat, "nobody asking: the slow beat")
        // A skill's wish counts on the main screen only: the dungeon list is
        // the director's own clock.
        r.passive.wants = Director.BEAT_WATCH
        r.cap.scene = { Paint.dungeonList() }
        val t = r.tick()
        assertEquals(Director.DUNGEON_LIST, t.screen)
        assertEquals(Director.BEAT, t.beat)
    }

    @Test
    fun `the main screen is the passive helper's at once, with the slow beat`() {
        val r = Rig()
        r.cap.scene = { Paint.battle(it) }
        val first = r.tick()
        assertEquals(Director.MAIN, first.screen)
        assertEquals("round", first.did, "no three seconds on the main screen: $first")
        assertEquals(Director.BEAT_MAIN, first.beat)
        // Both round-skills, the second on a frame of its own.
        assertEquals(1, r.passive.worked)
        assertEquals(1, r.quest.worked)
        assertEquals(2, r.cap.grabs)
        r.run(6.0)
        assertTrue(r.passive.worked >= 4, "a round every beat: ${r.passive.worked}")
        assertEquals(r.passive.worked, r.quest.worked)
        assertEquals(0, r.dungeons.worked)
        assertTrue(r.cap.taps.isEmpty(), "the director itself taps nothing on the main screen")
    }

    @Test
    fun `a still dungeon list goes to Dungeons after three seconds, once per visit`() {
        val r = Rig()
        r.cap.scene = { Paint.dungeonList() }
        val first = r.tick()
        assertEquals(Director.DUNGEON_LIST, first.screen)
        assertNull(first.did)
        assertNotNull(r.director.takeOverIn)
        assertTrue(first.note.startsWith("dungeon_list: taking over in 3 s"), first.note)
        assertEquals(0, r.dungeons.worked)
        val handed = r.run(3.0)
        assertEquals("work dungeon", handed.did, "after three quiet seconds: $handed")
        assertEquals(1, r.dungeons.worked)
        assertTrue(r.log.any { it == "giving the dungeon_list to Dungeons" }, r.log.toString())
        // The list is still there: worked once this visit, and left to the player.
        val later = r.run(10.0)
        assertEquals(1, r.dungeons.worked, "a second pass over the same list")
        assertTrue(later.note.contains("leaving it to you"), later.note)
        assertTrue(r.cap.taps.isEmpty())
    }

    @Test
    fun `a list the player is scrolling is the player's`() {
        val r = Rig()
        // Every frame a little further down: the clock never runs out.
        r.cap.scene = { Paint.dungeonList(shift = 0.5 * (it % 2)) }
        r.run(8.0)
        assertEquals(0, r.dungeons.worked, "handed over while the player was scrolling")
        assertTrue(r.log.any { it.contains("taking over in") }, r.log.toString())
        // Hands off the mouse: three quiet seconds, then the hand-over.
        r.cap.scene = { Paint.dungeonList() }
        r.run(2.0)
        assertEquals(0, r.dungeons.worked, "too early")
        r.run(2.0)
        assertEquals(1, r.dungeons.worked)
    }

    @Test
    fun `a skill with no budget is asked and not given the screen`() {
        val r = Rig()
        r.dungeons.budget = false
        r.cap.scene = { Paint.dungeonList() }
        r.run(6.0)
        assertEquals(0, r.dungeons.worked)
        assertTrue(r.log.any { it.contains("nothing to do on the dungeon_list") }, r.log.toString())
    }

    @Test
    fun `Exit the game is cancelled after three seconds, and the frame is kept`() {
        val r = Rig()
        r.cap.scene = { Paint.prompt(pink = false) }
        r.run(2.0)
        assertTrue(r.cap.taps.isEmpty(), "cancelled inside the player's three seconds")
        val t = r.run(2.0)
        assertEquals("cancel", t.did, t.toString())
        // Cancel sits mirrored relative to OK (dungeon.dismiss_confirm), OK
        // as recognise measures it on the painted frame.
        val ok = Dungeon.recognise(Paint.prompt(pink = false)).exitOk!!
        val want = Py.roundInt((1.0 - ok.fx) * Paint.W) to Py.roundInt(ok.fy * Paint.H)
        assertEquals(listOf(want), r.cap.taps)
        assertEquals(listOf("confirm_beenden"), r.kept)
        assertEquals(0, r.cap.backs)
    }

    @Test
    fun `a pink prompt nobody here raised is left standing`() {
        val r = Rig()
        r.cap.scene = { Paint.prompt(pink = true) }
        val t = r.run(6.0)
        assertTrue(r.cap.taps.isEmpty(), "pressed something on a prompt it did not raise")
        assertNotNull(r.director.parked)
        assertTrue(t.note.startsWith("A prompt is open that I did not raise"), t.note)
        assertEquals(listOf("prompt_not_mine"), r.kept)
        // The player answers it: the screen changes and the park is over.
        r.cap.scene = { Paint.mainScreen() }
        val home = r.tick()
        assertNull(r.director.parked)
        assertEquals("round", home.did)
    }

    /**
     * The dot stayed red on the World Search board while the figure walked
     * (2026-09-24, from the phone): the shell read a copy of the park the
     * service took *after* every round, while the director clears the park
     * *inside* the round, the moment the screen changes, and hands a screen
     * that moves by itself straight to its skill -- a round of minutes. The
     * main screen is the other such screen, and its round-skill is where the
     * shell is asked here, from inside the work.
     */
    @Test
    fun `a park the screen change cleared is off the shell before the skill's work is over`() {
        val r = Rig()
        val during = ArrayList<HelperState>()
        r.passive.inner = object : Skill {
            override val key = "probe"
            override val name = "probe"
            override fun worksOn(screen: String) = false
            override fun hasBudget() = true
            override fun work(img: Mat) = Outcome.DONE
            override fun run(): Outcome { during += Shell.state(); return Outcome.DONE }
        }
        Shell.alive = { true }
        Shell.parked = { r.director.parked }
        Shell.seen = { r.director.screen to "" }
        MainSwitch.set(true)
        try {
            r.cap.scene = { Paint.prompt(pink = true) }
            r.run(6.0)
            assertEquals(HelperState.PARKED, Shell.state())
            r.cap.scene = { Paint.battle(it) }
            r.tick()
            assertEquals(1, r.passive.worked)
            assertEquals(listOf(HelperState.RUNNING), during, "the shell still read the park the round had cleared")
        } finally {
            Shell.parked = { null }
            Shell.alive = { false }
            Shell.seen = { null to "" }
        }
    }

    @Test
    fun `a pink prompt right after a skill said it was leaving gets OK, and only then`() {
        val r = Rig()
        r.dungeons.outcome = Outcome(Result.DONE, leaving = "leaving the dungeon")
        r.cap.scene = { Paint.dungeonList() }
        r.run(3.5)
        assertEquals(1, r.dungeons.worked)
        // The skill's own prompt is still standing when the director looks again.
        r.cap.scene = { Paint.prompt(pink = true) }
        val t = r.run(3.5)
        assertEquals("ok", t.did, t.toString())
        val ok = Dungeon.recognise(Paint.prompt(pink = true)).exitOk!!
        assertEquals(listOf(Py.roundInt(ok.fx * Paint.W) to Py.roundInt(ok.fy * Paint.H)), r.cap.taps)
        // Once. A second pink prompt after that is nobody's again.
        r.cap.scene = { Paint.mainScreen() }
        r.tick()
        r.cap.scene = { Paint.prompt(pink = true) }
        r.run(6.0)
        assertEquals(1, r.cap.taps.size, "OK on a prompt the intent did not cover")
        assertNotNull(r.director.parked)
    }

    @Test
    fun `the Stage Failed banner is tapped away at the spot that opens nothing`() {
        val r = Rig()
        r.cap.scene = { Paint.stageFailed() }
        r.run(2.0)
        assertTrue(r.cap.taps.isEmpty())
        val t = r.run(2.0)
        assertEquals("tap", t.did, t.toString())
        assertEquals(listOf(Py.roundInt(Director.NEUTRAL_TAP_FX * Paint.W) to
                                Py.roundInt(Director.NEUTRAL_TAP_FY * Paint.H)), r.cap.taps)
        assertEquals(0, r.passive.worked, "the auto button under the banner is not the main screen")
    }

    @Test
    fun `a battle inside a dungeon is nobody's screen`() {
        val r = Rig()
        r.cap.scene = { Paint.dungeonBattle(it) }
        val t = r.run(8.0)
        assertEquals(Director.UNKNOWN, t.screen)
        assertEquals("no skill works on this screen", t.note)
        assertTrue(r.cap.taps.isEmpty())
        assertEquals(0, r.passive.worked + r.dungeons.worked)
        assertNull(r.director.parked)
    }

    @Test
    fun `off, the director reads and does nothing`() {
        val r = Rig()
        r.on = false
        r.cap.scene = { Paint.dungeonList() }
        val t = r.run(8.0)
        assertEquals(Director.DUNGEON_LIST, t.screen)
        assertEquals("paused, sees dungeon_list", t.note)
        assertEquals(0, r.dungeons.worked)
        assertTrue(r.log.contains("paused, sees dungeon_list"))
        // Back on: the clock ran while it was paused, and the list goes at once.
        r.on = true
        assertEquals("work dungeon", r.tick().did)
    }

    @Test
    fun `a landscape frame is not read, and the next frame can be kept`() {
        val r = Rig()
        r.cap.scene = { Mat(Paint.W, Paint.H, org.opencv.core.CvType.CV_8UC3) }
        val t = r.tick()
        assertNull(t.screen)
        assertEquals("the screen is in landscape -- not supported, not reading", t.note)
        assertEquals(Paint.H to Paint.W, r.director.frameSize)
        r.cap.scene = { Paint.dungeonList() }
        r.director.keepNext = "phone"
        r.tick()
        r.tick()
        assertEquals(listOf("phone_dungeon_list"), r.kept, "once, under the tag and the screen")
        assertNull(r.director.keepNext)
    }

    @Test
    fun `nothing is read while the game is not in front`() {
        val r = Rig()
        r.cap.front = "com.android.launcher3"
        val t = r.run(4.0)
        assertNull(t.screen)
        assertEquals(0, r.cap.grabs, "grabbed a frame of somebody else's app")
        // The package in front is the log's, not the sentence's.
        assertEquals("the game is not in front", t.note)
        assertEquals(1, r.log.count { it == "the game is not in front (com.android.launcher3)" }, "said once")
        // Back in the game: a fresh clock, not the one from before.
        r.cap.front = GAME
        r.cap.scene = { Paint.dungeonList() }
        assertNull(r.tick().did)
        assertEquals(0, r.dungeons.worked)
    }

    @Test
    fun `the screen a skill began on is waited for after its work`() {
        val r = Rig()
        r.cap.scene = { Paint.dungeonList() }
        r.run(3.5)
        assertEquals(1, r.dungeons.worked)
        // The game is still closing the skill's result screen.
        r.cap.scene = { Paint.dungeonBattle(it) }
        val t = r.tick()
        assertEquals("waiting for the dungeon_list to come back after the work", t.note)
        r.cap.scene = { Paint.dungeonList() }
        r.run(6.0)
        assertEquals(1, r.dungeons.worked, "the same visit, worked twice")
    }

    /**
     * The quest loop stands in both lists -- a chain step of its own
     * ("run until it stops", CHAIN_WAITING) and a round on the main screen --
     * and it is the only skill in `skills` that answers `main`. The rig
     * above has no such skill, which is how the main screen came to be handed
     * over like a dungeon list: once per visit, and then "has worked this
     * main; leaving it to you" for as long as the player stood there, with
     * the passive helper never ticking again. With the switch off it is the
     * silent half of the same bug -- no budget, the candidate loop falls
     * through, and the rounds are skipped without a word.
     */
    @Test
    fun `a skill in both lists does not take the main screen away from the rounds`() {
        for (budget in listOf(true, false)) {
            val r = Rig()
            val step = Counting("quest", "the quest loop", Director.MAIN, budget = budget)
            val d = DirectorLoop(
                r.cap, listOf(r.dungeons, step), listOf(r.passive, step), { GAME },
                { SkillSettings.MODE_SEMI }, r.chain,
                log = { r.log += it }, keep = { _, _ -> }, now = { r.t })
            r.cap.scene = { Paint.mainScreen() }
            repeat(4) { d.tick().also { r.t += it.beat } }
            assertEquals(4, r.passive.worked, "the passive helper stopped, budget=$budget")
            // And `hasBudget` decides whether it works, which for the quest
            // loop is the day's lock: `included` is the row in the Skills
            // list, and there is no second switch behind it any more. `work`
            // asks neither of itself, so a round that is not filtered here is
            // a switch that does nothing.
            assertEquals(if (budget) 4 else 0, step.worked, "budget=$budget")
            assertEquals(0, step.ran, "a round is not a chain step, budget=$budget")
            assertNull(d.parked, "nothing to park on, budget=$budget")
            d.close()
        }
    }

    /**
     * The other half of the same rule: with every round switched off at its
     * own page, the main screen has nothing to do and says so -- rather than
     * ticking skills whose switches are down.
     */
    @Test
    fun `a round whose page switch is off is not ticked`() {
        val r = Rig()
        r.passive.budget = false
        r.quest.budget = false
        r.cap.scene = { Paint.mainScreen() }
        val t = r.run(6.0)
        assertEquals(0, r.passive.worked + r.quest.worked, "a switch that is off still worked")
        assertEquals(Director.MAIN, t.screen)
        assertNull(t.did, "nothing was done: $t")
        assertTrue(t.note.contains("nothing to do here"), t.note)
        assertTrue(r.cap.taps.isEmpty(), "and nothing was tapped")
    }

    @Test
    fun `fully automatic runs the chain from the main screen and skips what has nothing to do`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon", "tower"))
        r.summons.budget = false
        r.cap.scene = { Paint.mainScreen() }
        val first = r.tick()
        assertEquals("run dungeon", first.did, first.toString())
        assertEquals(1, r.dungeons.ran)
        assertEquals(0, r.dungeons.worked)
        assertEquals(1, r.chain.index)
        // The frame after a run is the settle round: the passive helper's.
        val settle = r.tick()
        assertEquals("round", settle.did, settle.toString())
        // Then Summons, with nothing to do, is skipped; tower is no skill here.
        r.run(6.0)
        assertEquals(0, r.summons.ran)
        assertTrue(r.chain.finished, "the chain: index ${r.chain.index}, finished ${r.chain.finished}")
        assertEquals(listOf("summon" to "nothing to do, on the settings as they stand",
                            "tower" to "no such skill on the phone"), r.chain.skipped)
        // After the last step: the main screen, the passive helper's, and the chain's summary once.
        assertTrue(r.log.any { it.startsWith("the chain is finished (1 round, skipped summon") }, r.log.toString())
        assertTrue(r.passive.worked >= 2)
    }

    @Test
    fun `fully automatic parks on a screen the player left open`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon"))
        r.cap.scene = { Paint.dungeonList() }
        val t = r.run(6.0)
        assertEquals(0, r.dungeons.ran + r.dungeons.worked)
        assertNotNull(r.director.parked)
        assertTrue(t.note.contains("dungeon_list is open"), t.note)
        // "Try again" looks afresh, and the list is still the player's.
        r.director.retry()
        assertNull(r.director.parked)
        r.tick()
        assertNotNull(r.director.parked)
        assertEquals(0, r.dungeons.left, "the second hand is for a step's leftovers, not for the player's screen")
    }

    // ==================================================================
    // The chain's way through (NOTES.md, "A constant is a place on one display")
    // ==================================================================

    /**
     * R2. The Gekkomon Run step on the player's Poco F3: it could not get
     * in, its `run` came home in its finally, and it said PARKED -- and the
     * chain stood still on a main screen from which the next step could have
     * started. A step that parks and is home retires for this chain run; one
     * that parks and is not home is still a park.
     */
    @Test
    fun `a step that parks but came home retires for the run, and the next step starts`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.dungeons.outcome = Outcome.parked("I could not open the dungeon list from here.")
        r.cap.scene = { Paint.mainScreen() }
        val first = r.tick()
        assertEquals("run dungeon", first.did, first.toString())
        assertNull(r.director.parked, "parked the chain on the main screen")
        r.run(6.0)
        assertEquals(1, r.summons.ran, r.log.toString())
        assertEquals(1, r.dungeons.ran, "the retired step ran again in the same chain run")
        assertEquals("I could not open the dungeon list from here.", r.chain.retiredReason("dungeon"))
        assertTrue(r.log.contains("  chain: retiring dungeon for this run -- I could not open the dungeon list from here."),
                   r.log.toString())
        assertNull(r.director.parked)

        // Parked and not home: something is in the way, and that is a park.
        val r2 = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r2.dungeons.outcome = Outcome.parked("the panel would not close")
        r2.dungeons.onRun = { r2.cap.scene = { Paint.dungeonList() } }
        r2.cap.scene = { Paint.mainScreen() }
        r2.tick()
        val t = r2.run(Director.SETTLE + 2.0)
        assertNotNull(r2.director.parked, t.toString())
        assertEquals("Dungeons parked: the panel would not close", t.note)
        assertEquals(0, r2.summons.ran)
        assertEquals(0, r2.dungeons.left, "a parked step is not asked to leave")
        assertTrue("parked_not_home" in r2.kept, r2.kept.toString())
    }

    /**
     * R3. A step that leaves its own screen standing: once SETTLE has run
     * out, the skill that works on that screen is asked for its way home,
     * and the chain goes on from the main screen it brings back.
     */
    @Test
    fun `a list left open after the dungeon step is the dungeon skill's to leave, and Summon starts after`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.dungeons.onRun = { r.cap.scene = { Paint.dungeonList() } }
        r.dungeons.onLeave = { r.cap.scene = { Paint.mainScreen() }; true }
        r.cap.scene = { Paint.mainScreen() }
        assertEquals("run dungeon", r.tick().did)
        // SETTLE first: the game may still be closing what the step opened.
        r.run(Director.SETTLE - 1.0)
        assertEquals(0, r.dungeons.left, "asked to leave inside SETTLE")
        r.run(8.0)
        assertEquals(1, r.dungeons.left, r.log.toString())
        assertEquals(1, r.summons.ran, r.log.toString())
        assertNull(r.director.parked)
        assertTrue(r.log.contains("  chain: dungeon_list left open after Dungeons; asking Dungeons to leave"),
                   r.log.toString())
        assertTrue(r.log.contains("  chain: back on the main screen after Dungeons; going on"), r.log.toString())
        assertTrue(r.cap.taps.isEmpty(), "the director tapped something itself")
        // The plate names the skill that is walking home while it walks.
        assertEquals(listOf("Dungeons", "Dungeons", "Special Summon"),
                     r.named.filter { it == "Dungeons" || it == "Special Summon" })
    }

    /**
     * R3, no skill's screen: the Explore menu has no skill of its own here,
     * but the globe stands on it, and the globe is what every skill's way
     * home presses -- found on the frame, never a position.
     */
    @Test
    fun `a screen with the globe on it after a step gets the globe, and the chain goes on`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.dungeons.onRun = {
            r.cap.scene = { if (r.cap.taps.isEmpty()) PaintFarm.exploreMenu() else Paint.mainScreen() }
        }
        r.cap.scene = { Paint.mainScreen() }
        r.tick()
        r.run(Director.SETTLE + 8.0)
        val menu = PaintFarm.exploreMenu()
        val globe = Dungeon.homeButton(menu)!!
        menu.release()
        assertEquals(listOf(Py.roundInt(globe.fx * Paint.W) to Py.roundInt(globe.fy * Paint.H)), r.cap.taps)
        assertEquals(1, r.summons.ran, r.log.toString())
        assertNull(r.director.parked)
        assertTrue(r.log.contains("  chain: explore_menu left open after Dungeons; pressing the home button"),
                   r.log.toString())
    }

    /**
     * R4. An unknown screen after a step is waited for -- a loading screen
     * is unknown -- and then it has an end: no globe to read on it, so a
     * park, with a sentence and the frame, where it used to stand still
     * round after round saying nothing to the player.
     */
    @Test
    fun `an unknown screen after a step ends in a park with a sentence and a frame`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.dungeons.onRun = { r.cap.scene = { Paint.dungeonBattle(it) } }
        r.cap.scene = { Paint.mainScreen() }
        r.tick()
        r.run(Director.UNKNOWN_END - 2.0)
        assertNull(r.director.parked, "parked inside UNKNOWN_END")
        assertTrue(r.kept.isEmpty())
        val t = r.run(4.0)
        assertNotNull(r.director.parked, t.toString())
        assertEquals("A screen I do not know is open after Dungeons, and I found no way home from it.", t.note)
        assertEquals(listOf("no_way_home"), r.kept)
        assertEquals(0, r.summons.ran)
        assertTrue(r.cap.taps.isEmpty(), "tapped an unknown screen")
    }

    @Test
    fun `a parked skill stays parked until the screen changes or Try again`() {
        val r = Rig()
        r.dungeons.outcome = Outcome.parked("could not read the first card")
        r.cap.scene = { Paint.dungeonList() }
        val t = r.run(3.5)
        assertEquals("Dungeons parked: could not read the first card", t.note)
        r.run(6.0)
        assertEquals(1, r.dungeons.worked)
        r.dungeons.outcome = Outcome.DONE
        r.director.retry()
        r.run(3.5)
        assertEquals(2, r.dungeons.worked, "Try again gives the same screen once more")
    }

    // ==================================================================
    // What the overlay's plate is told
    // ==================================================================

    @Test
    fun `a turn names its skill on the plate and un-names it on the way out`() {
        val r = Rig()
        r.cap.scene = { Paint.dungeonList() }
        // The three quiet seconds first: nothing is named while the director
        // is still deciding whether the list is the player's.
        r.tick()
        assertEquals(emptyList(), r.named, "named before anybody had the screen")
        r.run(3.0)
        assertEquals(1, r.dungeons.worked)
        assertEquals(listOf("Dungeons", null), r.named)
    }

    @Test
    fun `the plate is named for the task, not for the skill the task plays inside it`() {
        val r = Rig()
        // The quest loop's dungeon step. The dungeon is played by a skill the
        // quest loop built and holds itself, so the whole of it is one turn of
        // the quest loop's and the plate says "the quest loop" from the first
        // frame to the last -- never "Dungeons", which is a minute in which a
        // player would be told the wrong task.
        val inside = Counting("dungeon", "Dungeons", Director.DUNGEON_LIST)
        r.quest.inner = inside
        r.cap.scene = { Paint.mainScreen() }
        r.tick()
        assertEquals(1, r.quest.worked)
        assertEquals(1, inside.ran, "the quest loop played its dungeon")
        assertEquals(listOf("the passive helper", null, "the quest loop", null), r.named)
    }

    @Test
    fun `a chain step is named for the whole of its run`() {
        // The fully automatic mode, where the quest loop is a step of its own
        // and its dungeon is inside `run` rather than beside it: the same
        // sentence. The skill stands in both lists, as it does on the phone.
        val r = Rig(SkillSettings.MODE_FULL, listOf("quest"))
        val inside = Counting("dungeon", "Dungeons", Director.DUNGEON_LIST)
        val step = Counting("quest", "the quest loop", Director.MAIN, inner = inside)
        val named = ArrayList<String?>()
        val d = DirectorLoop(
            r.cap, listOf(r.dungeons, step), listOf(r.passive, step), { GAME },
            { SkillSettings.MODE_FULL }, r.chain,
            log = { r.log += it }, keep = { _, _ -> }, busy = { named += it }, now = { r.t })
        r.cap.scene = { Paint.mainScreen() }
        d.tick()
        assertEquals(1, step.ran)
        assertEquals(1, inside.ran, "the quest loop played its dungeon")
        assertEquals(listOf("the quest loop", null), named)
        d.close()
    }

    private companion object {
        const val GAME = "com.bandainamcoent.dgup_ww"
    }
}
