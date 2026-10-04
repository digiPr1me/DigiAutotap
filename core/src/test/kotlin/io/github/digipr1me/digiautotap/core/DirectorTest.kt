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
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        /** Called on every back key, after it is counted: what the game does with it. */
        var onBack: () -> Unit = {}
        override fun back() { backs += 1; onBack() }
        override fun inFront(): String? = front
        /** Every overlayClear and overlayBack, in order. */
        val overlay = ArrayList<String>()
        override fun overlayClear(fy0: Double, fy1: Double) { overlay += "clear $fy0-$fy1" }
        override fun overlayBack() { overlay += "back" }
        /** The activity side of the phone, for the cases that play an ad ([FakeFront]). */
        var hand: AdHand? = null
        override fun adHand(): AdHand? = hand
        /** Called on every tap, after it is recorded: an ad world starts its ad here. */
        var onTap: (Int, Int) -> Unit = { _, _ -> }
        override fun tap(x: Int, y: Int) { taps += x to y; onTap(x, y) }
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
        /** The screens its `run` begins again on after a pause ([Skill.resumesOn]); none, as every skill but World Search. */
        var resumes: Set<String> = emptySet()
        override fun worksOn(screen: String) = screen == this.screen
        /** Screens no skill works on that its way home takes home from all the same ([Skill.leavesFrom]); none, as every skill but Dungeons. */
        var leaves: Set<String> = emptySet()
        override fun leavesFrom(screen: String, img: Mat) = screen in leaves
        override fun resumesOn(screen: String) = screen in resumes
        /** The screens it names as a window of its own ([Skill.opened]); none, as every round but two. */
        var opens: Set<String> = emptySet()
        override fun opened(screen: String) = screen in opens
        override fun hasBudget() = budget
        /** What the game does while [work] is at it. */
        var onWork: () -> Unit = {}
        override fun work(img: Mat): Outcome { worked += 1; inner?.run(); onWork(); return outcome }
        override fun run(): Outcome { ran += 1; inner?.run(); onRun(); return outcome }
        override fun leave(): Boolean { left += 1; return onLeave() }
        override fun beat(): Double? = wants
        /** How often a stopped pass was gone on with ([Skill.resume]); each also counts as the `run` or `work` it is. */
        var resumed = 0
        /** Whether each resume was whole (a chain step, a round, a task asked for) or a work. */
        val resumedWhole = ArrayList<Boolean>()
        override fun resume(img: Mat, whole: Boolean): Outcome {
            resumed += 1
            resumedWhole += whole
            return if (whole) run() else work(img)
        }
        /** How often its row's switch went off and on, as the director told it ([Skill.restarted]). */
        var restarts = 0
        override fun restarted() { restarts += 1 }
    }

    /** The director over a fake capture, with a clock the test moves. */
    class Rig(mode: String = SkillSettings.MODE_SEMI, steps: List<String> = emptyList(), repeat: Boolean = false) {
        val cap = FakeCapture()
        val log = ArrayList<String>()
        var t = 100.0
        var on = true
        var mode = mode
        val dungeons = Counting("dungeon", "Dungeons", Director.DUNGEON_LIST)
        val summons = Counting("summon", "Special Summon", Director.SUMMON)
        /** The Lost Sector Tower's stand-in, on the Crests page (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.2). */
        val tower = Counting("lost_sector", "Lost Sector Tower", Director.LOST_SECTOR)
        val passive = Counting("passive", "the passive helper", Director.MAIN)
        val quest = Counting("quest", "the quest loop", Director.MAIN)
        val kept = ArrayList<String>()
        /** What the overlay's plate was told, in order: a name on the way in, null on the way out. */
        val named = ArrayList<String?>()
        val chain = Chain(steps.map { Chain.Step(it) }, repeat, log = { log += it })
        /** The order the page has saved now; null is the one the rig was built with. */
        var saved: List<String>? = null
        /** Keys whose row is switched off, and the shell's sentence for why. */
        var off: Set<String> = emptySet()
        var offWhy: (Skill) -> String = { "not included" }
        val director = DirectorLoop(
            cap, listOf(dungeons, summons, tower), listOf(passive, quest), { GAME }, { this.mode }, chain,
            included = { it.key !in off }, offWhy = { offWhy(it) },
            on = { on }, log = { log += it }, keep = { _, tag -> kept += tag },
            busy = { named += it }, now = { t },
            order = { (saved ?: steps).map { Chain.Step(it) } to repeat })

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
            Paint.prompt(pink = false, lines = 1) to Director.EXIT_GAME,
            Paint.prompt(pink = false, lines = 2) to Director.DOWNLOAD,
            Paint.stageFailed() to Director.STAGE_FAILED,
            PaintMissions.main() to Director.MAIN,
            PaintMissions.window(Missions.COLLECTION) to Director.MISSIONS,
            PaintMissions.window(Missions.EX, r1 = true, listYellow = 1) to Director.EX_MISSIONS,
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

    /**
     * An ad is an activity of the game's own package (PLAN_WERBUNG.md 1): the
     * director answers "ad" before any frame is read, taps nothing in it,
     * and hands it to no skill -- an ad a skill asked for is closed inside
     * that skill's turn, and one the player opened is theirs.
     */
    @Test
    fun `an ad in front is seen by its class, and nothing is read or tapped`() {
        val r = Rig()
        val w = FakeFront()
        w.ad(FakeFront.ADMOB)
        r.cap.hand = w
        r.cap.scene = { Paint.dungeonList() }
        val t = r.run(Director.UNKNOWN_END + 5.0)
        assertEquals(Director.AD, t.screen)
        assertEquals(0, r.cap.grabs, "no frame of an ad is read")
        assertTrue(r.cap.taps.isEmpty())
        assertEquals(0, r.dungeons.worked)
        assertNull(r.director.parked, "a known ad is never a park")
        // Back in the game, the list is the list again.
        w.backInGame()
        assertEquals(Director.DUNGEON_LIST, r.tick().screen)
    }

    @Test
    fun `an unknown activity of the game parks after UNKNOWN_END in the fully automatic mode`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon"))
        val w = FakeFront()
        w.ad("com.example.sdk.SomeNewFullscreenActivity")
        r.cap.hand = w
        r.tick()
        assertNull(r.director.parked, "not at once")
        assertTrue(r.log.any { it.contains("unknown (com.example.sdk.SomeNewFullscreenActivity)") }, r.log.toString())
        r.run(Director.UNKNOWN_END + 2.0)
        assertTrue(r.director.parked?.contains("do not know") == true, "${r.director.parked}")
        assertTrue(r.cap.taps.isEmpty())
    }

    /**
     * A skill parked on its own ad -- a consent sheet -- and the ad is still
     * in front at the next round: the park stays. The change to the ad was
     * the skill's doing, and "the screen changed" is for the player's.
     */
    @Test
    fun `a skill's park on its ad outlives the change to the ad`() {
        val r = Rig()
        r.summons.outcome = Outcome.parked(FreeAds.PARK)
        r.cap.scene = { Paint.dungeonList() }
        r.dungeons.outcome = Outcome.parked(FreeAds.PARK)
        r.run(Director.IDLE_AFTER + 2.0)
        assertEquals(1, r.dungeons.worked)
        assertEquals("Dungeons parked: ${FreeAds.PARK}", r.director.parked)
        val w = FakeFront()
        w.ad(FakeFront.UNITY)
        r.cap.hand = w
        val t = r.tick()
        assertEquals(Director.AD, t.screen)
        assertEquals("Dungeons parked: ${FreeAds.PARK}", r.director.parked)
    }

    /**
     * The game's question before an ad, raised by the task's own film button
     * where the pass was taken for granted (live on instance 1, 2026-10-03
     * 15:38:26, where the director's own "A prompt is open that I did not
     * raise" took the task's sentence away a second later): the task's park
     * stands while the question does, nothing is tapped on it, and the
     * player's answer -- the screen behind it back -- ends it. Any other park
     * is over at the question, as every park is at a new screen.
     */
    @Test
    fun `a task's park on the game's question before an ad outlives the change to the prompt`() {
        val r = Rig()
        r.cap.scene = { Paint.dungeonList() }
        r.dungeons.outcome = Outcome.parked(FreeAds.PARK)
        r.run(Director.IDLE_AFTER + 2.0)
        assertEquals("Dungeons parked: ${FreeAds.PARK}", r.director.parked)
        val before = r.cap.taps.size
        r.cap.scene = { Paint.prompt(pink = true) }
        val t = r.run(Director.IDLE_AFTER + 5.0)
        assertEquals(Director.PROMPT, t.screen)
        assertEquals("Dungeons parked: ${FreeAds.PARK}", r.director.parked)
        assertEquals(before, r.cap.taps.size, "nothing tapped on the question")
        r.dungeons.outcome = Outcome.DONE
        r.cap.scene = { Paint.dungeonList() }
        r.tick()
        assertNull(r.director.parked, "the player's answer ends it")

        val other = Rig()
        other.cap.scene = { Paint.dungeonList() }
        other.dungeons.outcome = Outcome.parked("Something else is in the way.")
        other.run(Director.IDLE_AFTER + 2.0)
        assertEquals("Dungeons parked: Something else is in the way.", other.director.parked)
        other.cap.scene = { Paint.prompt(pink = true) }
        other.tick()
        assertTrue(other.director.parked?.contains("Something else") != true, "${other.director.parked}")
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

    /**
     * The page's button (DirectorLoop.runNow, "Switch now" on the Presets
     * page): the task's whole `run`, once, from the next plain main screen
     * -- and while another screen is up the request waits and the round goes
     * on as it would have. Any skill of the rig stands in for the Presets
     * task; the mechanism is the director's and knows no task.
     */
    @Test
    fun `a task asked for by its button runs once, from the main screen`() {
        val r = Rig()
        r.cap.scene = { Paint.dungeonList() }
        r.director.runNow = "summon"
        r.tick()
        assertEquals(0, r.summons.ran, "not off the main screen")
        assertEquals("summon", r.director.runNow, "the request waits")
        assertTrue(r.log.any { it == "Special Summon asked for; waiting for the main screen" }, r.log.toString())
        r.cap.scene = { Paint.battle(it) }
        val ran = r.tick()
        assertEquals("run summon", ran.did, "$ran")
        assertEquals(1, r.summons.ran)
        assertNull(r.director.runNow)
        assertEquals(0, r.passive.worked, "the button's round is the task's")
        r.run(6.0)
        assertEquals(1, r.summons.ran, "once")
        assertTrue(r.passive.worked >= 1, "and the rounds go on")
    }

    @Test
    fun `a button for a task that is not here is said and forgotten`() {
        val r = Rig()
        r.cap.scene = { Paint.battle(it) }
        r.director.runNow = "preset"
        r.tick()
        assertNull(r.director.runNow)
        assertTrue(r.log.any { "asked to run preset now, but that task is not here" in it }, r.log.toString())
    }

    /**
     * "Try again" on the park of a task asked for by its button runs that
     * task again (the player's call, 2026-09-27): live, after a Presets
     * park, it started the quest loop instead, which entered a dungeon.
     */
    @Test
    fun `Try again on the park of a task asked for by its button runs that task again`() {
        val r = Rig()
        r.cap.scene = { Paint.battle(it) }
        r.summons.outcome = Outcome.parked("could not start")
        r.director.runNow = "summon"
        r.tick()
        assertEquals(1, r.summons.ran)
        assertNotNull(r.director.parked)
        r.run(4.0)
        assertEquals(0, r.quest.worked, "a park stands still")
        r.summons.outcome = Outcome.DONE
        r.director.retry()
        assertEquals("summon", r.director.runNow, "the request is back")
        val again = r.tick()
        assertEquals("run summon", again.did, "$again")
        assertEquals(2, r.summons.ran)
        assertEquals(0, r.quest.worked, "not the quest loop")
        assertEquals(0, r.passive.worked)
        assertNull(r.director.parked)
        // Done this time: a later "Try again" has nothing of the button left.
        r.director.retry()
        assertNull(r.director.runNow)
    }

    @Test
    fun `Try again on any other park is a fresh look, and no task is asked for`() {
        val r = Rig()
        // A park of a task asked for, then over by a screen change: forgotten.
        r.cap.scene = { Paint.battle(it) }
        r.summons.outcome = Outcome.parked("could not start")
        r.director.runNow = "summon"
        r.tick()
        assertNotNull(r.director.parked)
        r.cap.scene = { Paint.prompt(pink = true) }
        r.tick()
        assertTrue(r.director.parked!!.startsWith("A prompt is open"), r.director.parked)
        r.director.retry()
        assertNull(r.director.runNow, "a prompt's park is not the button's")
        r.cap.scene = { Paint.battle(it) }
        r.tick()
        assertEquals(1, r.summons.ran)
        assertTrue(r.quest.worked >= 1, "the main screen's rounds, as before")
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

    /** Where the director's OK on the painted download dialog lands: recognise's OK, in the frame's pixels. */
    private fun downloadOk(): Pair<Int, Int> {
        val img = Paint.prompt(pink = false, lines = 2)
        val ok = Dungeon.recognise(img).exitOk!!
        img.release()
        return Py.roundInt(ok.fx * Paint.W) to Py.roundInt(ok.fy * Paint.H)
    }

    /**
     * PLAN_RELEASE_1_3.md B1. "Resource download required" wears the face of
     * "Exit the game?", and the director answered it Cancel after three
     * seconds -- the game closed (instance 1, 2026-09-29 12:50:18). Played on
     * the old director this case taps the mirrored Cancel at 103 s and fails.
     * The player's call of 2026-09-30 is OK, in either mode: once, behind the
     * three seconds, at the OK recognise read, and the next look without the
     * dialog is the proof.
     */
    @Test
    fun `the game's download dialog gets OK after three seconds in both modes, never Cancel`() {
        for (mode in listOf(SkillSettings.MODE_SEMI, SkillSettings.MODE_FULL)) {
            val r = Rig(mode, listOf("dungeon"))
            r.cap.scene = { Paint.prompt(pink = false, lines = 2) }
            // The OK takes: the download runs, a screen nothing here names.
            r.cap.onTap = { _, _ -> r.cap.scene = { Paint.blank() } }
            r.run(2.0)
            assertTrue(r.cap.taps.isEmpty(), "$mode: tapped inside the player's three seconds")
            r.run(3.0)
            assertEquals(listOf(downloadOk()), r.cap.taps, "$mode: ${r.log}")
            assertTrue(r.log.contains(Director.DOWNLOAD_SAYS), "$mode: ${r.log}")
            assertTrue(r.log.any { it.contains("the download dialog is gone after OK -- unknown now") }, "$mode: ${r.log}")
            assertEquals(listOf("download"), r.kept)
            assertEquals(0, r.dungeons.ran + r.dungeons.worked, "$mode: a task was given the dialog")
            assertNull(r.director.parked, "$mode: ${r.log}")
            r.run(Director.SETTLE)
            assertEquals(1, r.cap.taps.size, "$mode: one OK, and nothing more: ${r.log}")
        }
    }

    /**
     * The conductor's rule of 2026-09-30: an OK the dialog outlives is given
     * SETTLE seconds, then a second one; after that no third tap, but a park
     * with its sentence. The main screen -- the game past its loading --
     * starts the count again.
     */
    @Test
    fun `a download dialog that stands after two OKs is parked on and not tapped a third time`() {
        val r = Rig()
        r.cap.scene = { Paint.prompt(pink = false, lines = 2) }
        r.run(3.5)
        assertEquals(1, r.cap.taps.size, r.log.toString())
        r.run(Director.SETTLE - 2.0)
        assertEquals(1, r.cap.taps.size, "tapped again before the first OK had its time: ${r.log}")
        assertTrue(r.log.any { it.contains("waiting for the OK to take") }, r.log.toString())
        r.run(3.0)
        assertEquals(2, r.cap.taps.size, r.log.toString())
        assertTrue(r.log.any { it.contains("(OK 2 of ${Director.DOWNLOAD_OKS})") }, r.log.toString())
        val t = r.run(Director.SETTLE + 20.0)
        assertEquals(listOf(downloadOk(), downloadOk()), r.cap.taps, "a third tap: ${r.log}")
        assertNotNull(r.director.parked, r.log.toString())
        assertTrue(t.note.startsWith("The game asks to download its data again"), t.note)
        assertEquals(listOf("download", "download", "download_again"), r.kept)
        // The player answers it, the game comes up, and the next dialog --
        // another update, another day -- is the director's again.
        r.cap.scene = { Paint.mainScreen() }
        r.tick()
        assertNull(r.director.parked)
        r.cap.scene = { Paint.prompt(pink = false, lines = 2) }
        r.run(4.0)
        assertEquals(3, r.cap.taps.size, r.log.toString())
    }

    /** The same dialog gone after each OK and back again: two OKs, then the park. */
    @Test
    fun `a download dialog that comes back after each OK gets two, then a park`() {
        val r = Rig()
        var showing = true
        var hiddenAt = 0.0
        r.cap.scene = {
            if (!showing && r.t - hiddenAt >= 5.0) showing = true
            if (showing) Paint.prompt(pink = false, lines = 2) else Paint.blank()
        }
        r.cap.onTap = { _, _ -> showing = false; hiddenAt = r.t }
        r.run(40.0)
        assertEquals(2, r.cap.taps.size, r.log.toString())
        assertEquals(2, r.log.count { it.contains("the download dialog is gone after OK") }, r.log.toString())
        assertNotNull(r.director.parked, r.log.toString())
        assertTrue(r.cap.taps.all { it == downloadOk() }, "${r.cap.taps}")
    }

    /**
     * A task that meets the dialog in its pass taps nothing and hands the
     * screen back parked (DungeonSkill.DOWNLOAD_WHY); the park is over with
     * the screen change, and the director's OK is the one tap.
     */
    @Test
    fun `a task that met the download dialog hands it back, and the director answers it`() {
        val r = Rig()
        r.dungeons.outcome = Outcome.parked(DungeonSkill.DOWNLOAD_WHY)
        r.cap.scene = { Paint.dungeonList() }
        r.dungeons.inner = object : Skill {
            override val key = "game"
            override val name = "the game"
            override fun worksOn(screen: String) = false
            override fun hasBudget() = true
            override fun work(img: Mat) = Outcome.DONE
            override fun run(): Outcome {
                r.cap.scene = { Paint.prompt(pink = false, lines = 2) }
                return Outcome.DONE
            }
        }
        r.run(3.5)
        assertEquals(1, r.dungeons.worked, r.log.toString())
        assertTrue(r.cap.taps.isEmpty())
        r.run(4.0)
        assertEquals(listOf(downloadOk()), r.cap.taps, r.log.toString())
        assertTrue(r.log.any { it.contains("parked on dungeon_list: Dungeons parked: " + DungeonSkill.DOWNLOAD_WHY) },
                   r.log.toString())
        assertTrue(r.log.contains(Director.DOWNLOAD_SAYS), r.log.toString())
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

    /**
     * The Meat Field's can dialog and its two ad offers (PLAN_MEAT_FIELD_GIESSEN.md
     * 5.4 point 8, 6.8 case 9): dialogs of the field, named as one, and
     * nobody's to work -- the Meat Field task takes the plain field only, and
     * a dialog standing when the director looks is the player's. The task is
     * built with every switch on, so that nothing it would do is left out by
     * a switch.
     */
    @Test
    fun `the can dialog and the field's ad offers are field dialogs nobody works`() {
        val plots = List(6) { PaintFarm.Plot("growing", timer = "01:00:00") }
        val scenes = listOf(
            PaintFarm.field(plots, boost = PaintFarm.Boost("4", "2")) to "boost_popup",
            PaintFarm.field(plots, boost = PaintFarm.Boost("2/2", "0")) to "ad_dialog",
            PaintFarm.field(plots, menu = 0, slotCounts = listOf("0", "5", "5"), seedAd = "2/2") to "ad_dialog")
        for ((img, why) in scenes) {
            val c = Director.classify(img)
            assertEquals(Director.FIELD_DIALOG, c.screen, c.toString())
            assertTrue(c.toString().contains(why), c.toString())
            val cap = FakeCapture()
            cap.scene = { Paint.copy(img) }
            val farm = FarmSkill(cap, { null }, { _, _, _ -> },
                                 settings = { FarmSkill.Settings(betterSeeds = true, ads = true) })
            var t = 100.0
            val director = DirectorLoop(cap, listOf(farm), emptyList(), { GAME }, { SkillSettings.MODE_SEMI },
                                        Chain(emptyList(), log = {}), log = {}, now = { t })
            repeat(20) {
                val tick = director.tick()
                assertEquals(Director.FIELD_DIALOG, tick.screen)
                assertNull(tick.did, tick.toString())
                t += tick.beat
            }
            director.close()
            assertTrue(cap.taps.isEmpty(), "tapped on the $why: ${cap.taps}")
            img.release()
        }
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
    fun `a landscape frame is read like any other, and the next frame can be kept`() {
        // PLAN_FORMATE.md V19: the game upright in the middle of a 1920 x
        // 1080 display, black beside it, as LDPlayer draws it with
        // `wm set-ignore-orientation-request true`.
        val r = Rig()
        r.cap.scene = {
            val land = Mat.zeros(1080, 1920, org.opencv.core.CvType.CV_8UC3)
            val list = Paint.dungeonList()
            val small = Mat()
            org.opencv.imgproc.Imgproc.resize(list, small, org.opencv.core.Size(608.0, 1080.0),
                                              0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA)
            small.copyTo(land.submat(0, 1080, 656, 656 + 608))
            list.release(); small.release()
            land
        }
        val t = r.tick()
        assertEquals(Director.DUNGEON_LIST, t.screen, "a landscape display is read, not refused")
        assertEquals(1920 to 1080, r.director.frameSize)
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

    /**
     * G21, measured on LDPlayer on 2026-09-29 (notes/director.md, "A frame is
     * taken before the system says who is in front"): the player opens this
     * app's page, a frame of it is taken while the service still names the
     * game, and the service is told a few hundred ms later. Played as a
     * switch under the grab, with the picture DL5's page was read as: an
     * "Exit the game?" prompt, whose Cancel is a tap of the director's own.
     */
    @Test
    fun `a frame taken while this app came to the front is not read`() {
        val r = Rig()
        r.run(2.0)
        assertEquals(Director.MAIN, r.director.screen)
        r.cap.scene = { r.cap.front = APP; Paint.prompt(pink = false) }
        val t = r.tick()
        assertNull(t.screen, "read the app's page as the game's")
        assertEquals("the game is not in front", t.note)
        assertNull(r.director.screen)
        assertTrue(r.log.none { it.startsWith(Director.EXIT_GAME) }, "said something about it: ${r.log}")
        assertEquals(1, r.log.count { it == "the game is not in front ($APP)" })
        // Back in the game with the prompt really up: one round only looks,
        // the next reads it, and the three seconds start there.
        r.cap.front = GAME
        r.cap.scene = { Paint.prompt(pink = false) }
        assertEquals("the game just came to the front -- letting it settle", r.tick().note)
        assertEquals(Director.EXIT_GAME, r.tick().screen)
        assertTrue(r.cap.taps.isEmpty())
        assertTrue(r.kept.isEmpty(), "kept a frame: ${r.kept}")
    }

    /**
     * The other way round, from the same measurement: the round after the
     * game came back grabbed while the game's window still slid in (47 px
     * from the left) and read it as a dialog. The first round back grabs
     * nothing; the one after reads.
     */
    @Test
    fun `the first round back in the game only looks`() {
        val r = Rig()
        r.cap.front = APP
        r.tick()
        r.cap.front = GAME
        val t = r.tick()
        assertEquals(0, r.cap.grabs, "read a frame of the game still sliding in")
        assertNull(t.screen)
        assertEquals(Director.MAIN, r.tick().screen)
        assertTrue(r.cap.grabs > 0)
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

    /**
     * N3 b (PLAN_BEFUNDE_1_3.md): on the Poco on 2026-10-01 Gekkomon Run
     * stood first in the order and the quest loop ran first. Gekkomon Run
     * was switched off -- Chef's Special switched on at 19:53:58 had taken it
     * off, and the player switched it off again at 19:54:06 -- and the chain
     * skipped it at 20:21:15, 20:25:57 and 20:55:41 with "skipping runner --
     * not included", which named neither the task nor the reason. A wanted
     * skip; the sentence is the shell's ([DirectorLoop]'s `offWhy`), in the
     * log and in the round's note, under the task's name.
     */
    @Test
    fun `a step whose task is switched off is skipped with the reason in words`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("summon", "dungeon"))
        r.off = setOf("summon")
        val why = "its switch is off -- only one minigame runs at a time, and Chef's Special is on"
        r.offWhy = { why }
        r.cap.scene = { Paint.mainScreen() }
        val skipped = r.tick()
        assertEquals("chain: skipped Special Summon -- $why", skipped.note)
        assertTrue(r.log.contains("  chain: skipping summon -- $why"), r.log.toString())
        assertEquals(listOf("summon" to why), r.chain.skipped)
        r.run(4.0)
        assertEquals(0, r.summons.ran)
        assertEquals(1, r.dungeons.ran, r.log.toString())
    }

    /**
     * 2026-10-01: the order was read once, when the core started, and the
     * Meat Field moved to the front on the page while the core ran was not
     * the step played -- Dungeons was. The order is asked before every step.
     */
    @Test
    fun `an order changed while the core runs is the one played next`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.cap.scene = { Paint.mainScreen() }
        r.saved = listOf("summon", "dungeon")
        val first = r.tick()
        assertEquals("run summon", first.did, first.toString())
        assertEquals(0, r.dungeons.ran, "the old first step ran: ${r.log}")
        assertTrue(r.log.any { it.startsWith("chain: the order is now summon -> dungeon") }, r.log.toString())
        r.run(6.0)
        assertEquals(1, r.summons.ran)
        assertEquals(1, r.dungeons.ran)
        assertTrue(r.director.chain.finished)
    }

    /**
     * The other half: a step that ran before the change is not run again
     * after it. A pass's budgets begin at nought, so a Dungeons step played
     * a second time would spend its tickets twice.
     */
    @Test
    fun `a step that ran before the order changed does not run again`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.cap.scene = { Paint.mainScreen() }
        assertEquals("run dungeon", r.tick().did)
        r.saved = listOf("lost_sector", "summon", "dungeon")
        r.run(10.0)
        assertEquals(1, r.dungeons.ran, "dungeon ran twice: ${r.log}")
        assertEquals(1, r.tower.ran)
        assertEquals(1, r.summons.ran)
        assertTrue(r.director.chain.finished)
        // And a step added to the finished chain runs; nothing else does.
        r.saved = listOf("lost_sector", "summon", "dungeon", "summon")
        r.run(10.0)
        assertEquals(2, r.summons.ran)
        assertEquals(1, r.dungeons.ran)
        assertEquals(1, r.tower.ran)
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
    // The chain's way through (notes/runner.md, "A constant is a place on one display")
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
    // After a pause (PLAN_WORLD_SEARCH_FORMATE.md F27; notes/director.md, "A
    // pass the switch paused goes on where it stands, and a screen the player
    // opened is still the player's"). The mechanism, with a stand-in that
    // says yes on the dungeon list; the real skill that says yes, World
    // Search on the board, is WorldSearchFlowTest's.
    // ==================================================================

    /** The dungeon step, stopped by the switch on its list: `run` pauses the rig and leaves the list standing. */
    private fun pausedOnTheList(r: Rig) {
        r.dungeons.outcome = Outcome.STOPPED
        r.dungeons.onRun = { r.on = false; r.cap.scene = { Paint.dungeonList() } }
        r.cap.scene = { Paint.mainScreen() }
        assertEquals("run dungeon", r.tick().did)
        assertEquals("paused, sees dungeon_list", r.run(12.0).note)
    }

    @Test
    fun `a chain step the switch stopped goes on where it stands, if its run can begin there`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.dungeons.resumes = setOf(Director.DUNGEON_LIST)
        pausedOnTheList(r)
        // Back on, and stopped a second time the same way: it goes on again.
        r.on = true
        val again = r.tick()
        assertEquals("run dungeon", again.did, r.log.toString())
        assertEquals(2, r.dungeons.ran)
        assertEquals(1, r.log.count { it == "chain: resuming Dungeons on the dungeon_list" }, r.log.toString())
        r.run(12.0)
        r.on = true
        r.dungeons.outcome = Outcome.DONE
        r.dungeons.onRun = { r.cap.scene = { Paint.mainScreen() } }
        r.tick()
        assertEquals(3, r.dungeons.ran, r.log.toString())
        assertNull(r.director.parked, r.log.toString())
        // Home, and the chain goes on as after any step.
        r.run(6.0)
        assertEquals(1, r.summons.ran, r.log.toString())
        assertTrue(r.log.none { it.contains("did not come back") }, "a stopped step hands back nothing to wait for")
        assertEquals(0, r.dungeons.worked + r.dungeons.left, "neither the work alone nor the way home")
    }

    /**
     * The rounds until the director parks, at most [seconds]; the tick that
     * parked. Since 2026-09-30 a park right after the switch came back waits
     * out its second look first (Director.SECOND_LOOK).
     */
    private fun untilParked(r: Rig, seconds: Double = 10.0): Director.Tick {
        val until = r.t + seconds
        var last = r.tick()
        while (r.director.parked == null && r.t < until) last = r.tick()
        return last
    }

    @Test
    fun `without the skill's yes a stopped step's screen is the player's, as before`() {
        // As before, but after the second look (changed on 2026-09-30,
        // PLAN_RELEASE_1_3.md B4: the park came on the first look back on).
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        pausedOnTheList(r)
        r.on = true
        // Its claim ended with the first look of the pause, which the skill
        // said no to; the park comes after the second look.
        assertEquals("back on: the dungeon_list is not one I go on from -- looking once more before I park",
                     r.tick().note)
        val t = untilParked(r)
        assertEquals("The fully automatic mode starts from the main screen, and dungeon_list is open.", t.note)
        assertEquals(1, r.dungeons.ran)
        assertEquals(0, r.summons.ran)
    }

    @Test
    fun `a screen the player opened is theirs, before the first step and after another screen in the pause`() {
        // Before the first step: a skill's yes gives it nothing it did not stop on.
        val before = Rig(SkillSettings.MODE_FULL, listOf("dungeon"))
        before.dungeons.resumes = setOf(Director.DUNGEON_LIST)
        before.cap.scene = { Paint.dungeonList() }
        assertTrue(before.run(6.0).note.contains("dungeon_list is open"))
        assertEquals(0, before.dungeons.ran)

        // In the pause the player went home and opened the list again: one
        // look at the main screen, and the list after it is the player's.
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon"))
        r.dungeons.resumes = setOf(Director.DUNGEON_LIST)
        pausedOnTheList(r)
        r.cap.scene = { Paint.mainScreen() }
        assertEquals("paused, sees main", r.tick().note)
        r.cap.scene = { Paint.dungeonList() }
        r.run(4.0)
        r.on = true
        // After the second look since 2026-09-30 (PLAN_RELEASE_1_3.md B4).
        val t = untilParked(r)
        assertEquals("The fully automatic mode starts from the main screen, and dungeon_list is open.", t.note)
        assertEquals(1, r.dungeons.ran)
    }

    @Test
    fun `the game away in the pause is no other screen, and the step goes on`() {
        // The player looks at this app's page and comes back to the list:
        // nothing of the game was seen but the list.
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon"))
        r.dungeons.resumes = setOf(Director.DUNGEON_LIST)
        pausedOnTheList(r)
        r.cap.front = "io.github.digipr1me.digiautotap"
        r.run(4.0)
        r.cap.front = GAME
        r.on = true
        // Back in the game the list is a new sighting, and the three quiet
        // seconds hold for it as for any screen the director acts on. The
        // first round back in the game only looks (G21, "the game just came
        // to the front -- letting it settle"), so those seconds begin one
        // round later, and 4 s no longer reach the resume.
        r.run(5.0)
        assertEquals(2, r.dungeons.ran, r.log.toString())
        assertEquals(1, r.log.count { it == "chain: resuming Dungeons on the dungeon_list" }, r.log.toString())
        assertNull(r.director.parked)
    }

    // ==================================================================
    // Every task goes on after the pause, in both modes (PLAN_RELEASE_1_3.md
    // B4, the player's rule of 2026-09-30; notes/director.md, "A pass the
    // switch paused goes on where it stands" and its extension of that day).
    // The mechanism with the stand-ins; the tasks' own passes are
    // PauseFlowTest's.
    // ==================================================================

    /** The semi-automatic mode's work on the list, stopped by the switch: `work` pauses the rig and leaves the list. */
    private fun workPausedOnTheList(r: Rig) {
        r.dungeons.outcome = Outcome.STOPPED
        r.cap.scene = { Paint.dungeonList() }
        r.dungeons.onWork = { r.on = false }
        r.tick()
        assertEquals("work dungeon", r.run(3.0).did, r.log.toString())
        assertEquals("paused, sees dungeon_list", r.run(6.0).note)
    }

    @Test
    fun `semi-automatic, a work the switch stopped goes on as the same visit, twice`() {
        val r = Rig()
        r.dungeons.resumes = setOf(Director.DUNGEON_LIST)
        workPausedOnTheList(r)
        // Back on: the same visit goes on, with no "has worked this ...".
        r.on = true
        val again = r.tick()
        assertEquals("work dungeon", again.did, r.log.toString())
        assertEquals(1, r.log.count { it == "resuming Dungeons on the dungeon_list" }, r.log.toString())
        assertEquals(listOf(false), r.dungeons.resumedWhole, "a work goes on as a work")
        // Stopped a second time on the same list, and on again.
        r.run(6.0)
        r.on = true
        r.dungeons.outcome = Outcome.DONE
        r.dungeons.onWork = {}
        assertEquals("work dungeon", r.tick().did, r.log.toString())
        assertEquals(3, r.dungeons.worked)
        assertEquals(2, r.dungeons.resumed)
        // Done now: the visit's work is over, and the list is the player's.
        val later = r.run(10.0)
        assertEquals(3, r.dungeons.worked, "no fresh pass over the list after the resumed one")
        assertTrue(later.note.contains("has worked this dungeon_list; leaving it to you"), later.note)
        assertNull(r.director.parked)
        assertTrue(r.log.none { it.contains("parked on") }, r.log.toString())
    }

    @Test
    fun `semi-automatic, a work without the skill's yes is not handed the same list again as before`() {
        val r = Rig()
        workPausedOnTheList(r)
        r.on = true
        r.run(4.0)
        // Its claim ended; the list is a list the player stands on, and
        // handed over afresh once per visit -- the same visit here, so the
        // stopped work was not the visit's and a new one goes on it.
        assertEquals(0, r.dungeons.resumed)
        assertEquals(2, r.dungeons.worked, r.log.toString())
        assertTrue(r.log.any { it == "  the dungeon_list in the pause: Dungeons will not go on from it" }, r.log.toString())
    }

    @Test
    fun `a screen nobody knows in the pause keeps the claim, and the second look finds the step's screen`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.dungeons.resumes = setOf(Director.DUNGEON_LIST)
        pausedOnTheList(r)
        // A transition in the pause, and still one when the switch is back.
        r.cap.scene = { Paint.dungeonBattle(it) }
        assertEquals("paused, sees unknown", r.tick().note)
        r.on = true
        val first = r.tick()
        assertEquals("back on: Dungeons does not know the unknown yet -- looking once more", first.note, r.log.toString())
        assertNull(first.did)
        // The list comes within the second look: the step goes on, no park.
        r.cap.scene = { Paint.dungeonList() }
        r.run(6.0)
        assertEquals(1, r.log.count { it == "chain: resuming Dungeons on the dungeon_list" }, r.log.toString())
        assertEquals(2, r.dungeons.ran)
        assertNull(r.director.parked)
        assertTrue(r.log.none { it.contains("parked on") }, r.log.toString())
    }

    @Test
    fun `a screen the second look still does not know is parked on after it, not at once`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon"))
        r.dungeons.resumes = setOf(Director.DUNGEON_LIST)
        pausedOnTheList(r)
        r.cap.scene = { Paint.prompt(pink = true) }
        r.run(3.0)
        r.on = true
        val on = r.t
        val first = r.tick()
        assertNull(r.director.parked, "no park on the first look back: ${first.note}")
        assertTrue(first.note.startsWith("back on"), first.note)
        val t = untilParked(r)
        assertTrue(t.note.startsWith("A prompt is open that I did not raise"), t.note)
        assertTrue(r.t - on >= Director.SECOND_LOOK, "parked only after the second look")
    }

    @Test
    fun `a chain step whose claim ended goes on from the main screen as the same pass`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        pausedOnTheList(r)
        // The player goes home in the pause: the list is theirs no more.
        r.cap.scene = { Paint.mainScreen() }
        r.run(3.0)
        r.on = true
        r.dungeons.outcome = Outcome.DONE
        r.dungeons.onRun = {}
        r.tick()
        assertEquals(1, r.log.count { it == "chain: going on with Dungeons from the main screen" }, r.log.toString())
        assertEquals(listOf(true), r.dungeons.resumedWhole, "the same pass going on, not a fresh run")
        r.run(6.0)
        assertEquals(1, r.summons.ran, "the chain goes on after it: ${r.log}")
        assertEquals(0, r.dungeons.resumed - 1)
    }

    @Test
    fun `a round the switch stopped away from the main screen goes on there, in either mode`() {
        for (mode in listOf(SkillSettings.MODE_SEMI, SkillSettings.MODE_FULL)) {
            val r = Rig(mode, listOf("summon"))
            r.summons.budget = false
            // The quest loop's round walks to the list and the switch stops it there.
            r.quest.resumes = setOf(Director.DUNGEON_LIST)
            r.quest.outcome = Outcome.STOPPED
            r.quest.onWork = { r.on = false; r.cap.scene = { Paint.dungeonList() } }
            r.cap.scene = { Paint.mainScreen() }
            var n = 0
            while (r.quest.worked == 0 && n < 10) { r.tick(); n += 1 }
            assertEquals(1, r.quest.worked, "$mode: ${r.log}")
            assertEquals("paused, sees dungeon_list", r.run(4.0).note)
            r.on = true
            r.quest.outcome = Outcome.retired("the round has no end")
            r.quest.onWork = {}
            r.quest.onRun = { r.cap.scene = { Paint.mainScreen() } }
            r.run(4.0)
            assertEquals(1, r.log.count { it == "resuming the quest loop on the dungeon_list" }, "$mode: ${r.log}")
            assertEquals(listOf(true), r.quest.resumedWhole, "$mode: a round goes on to its end, home")
            assertEquals(0, r.dungeons.worked + r.dungeons.ran, "$mode: the list is the round's, not Dungeons'")
            assertNull(r.director.parked, "$mode: ${r.log}")
        }
    }

    @Test
    fun `a task asked for by its button goes on where the switch stopped it`() {
        val r = Rig()
        r.summons.resumes = setOf(Director.SUMMON)
        r.summons.outcome = Outcome.STOPPED
        r.summons.onRun = { r.on = false; r.cap.scene = { PaintSummon.modeScreen(1) } }
        r.cap.scene = { Paint.battle(it) }
        r.director.runNow = "summon"
        assertEquals("run summon", r.tick().did)
        r.run(4.0)
        r.on = true
        r.summons.outcome = Outcome.DONE
        r.summons.onRun = { r.cap.scene = { Paint.mainScreen() } }
        r.run(5.0)
        assertEquals(1, r.log.count { it == "resuming Special Summon on the summon, as asked" }, r.log.toString())
        assertEquals(listOf(true), r.summons.resumedWhole)
        assertEquals(2, r.summons.ran)
        assertNull(r.director.parked)
    }

    @Test
    fun `a pause in the chain's second hand keeps the step's leftover, and the way home is asked again`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.dungeons.onRun = { r.cap.scene = { Paint.dungeonList() } }
        // Its way home, asked by the second hand, meets the switch off and
        // taps nothing ([Stays]); the next time it goes home.
        var leaves = 0
        r.dungeons.onLeave = {
            leaves += 1
            if (leaves == 1) { r.on = false; false } else { r.cap.scene = { Paint.mainScreen() }; true }
        }
        r.cap.scene = { Paint.mainScreen() }
        assertEquals("run dungeon", r.tick().did)
        r.run(Director.SETTLE + 4.0)
        assertEquals(1, r.dungeons.left, r.log.toString())
        assertEquals("paused, sees dungeon_list", r.run(4.0).note)
        r.on = true
        r.run(Director.SETTLE + 8.0)
        assertEquals(2, r.dungeons.left, r.log.toString())
        assertEquals(1, r.summons.ran, r.log.toString())
        assertNull(r.director.parked, r.log.toString())
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

    // ==================================================================
    // The Lost Sector Tower's screen (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.2, 4.4)
    // ==================================================================

    /**
     * The semi-automatic mode: the Crests page (PaintLostSector.page, read by
     * the real `classify`) goes to the tower after three quiet seconds, once
     * per visit, and the director taps nothing on it itself.
     */
    @Test
    fun `the Crests page goes to the Lost Sector Tower after three seconds, once per visit`() {
        val r = Rig()
        r.cap.scene = { PaintLostSector.page() }
        val first = r.tick()
        assertEquals(Director.LOST_SECTOR, first.screen)
        assertEquals(0, r.tower.worked)
        val handed = r.run(3.0)
        assertEquals("work lost_sector", handed.did, "$handed")
        assertEquals(1, r.tower.worked)
        assertTrue(r.log.contains("giving the lost_sector to Lost Sector Tower"), r.log.toString())
        val later = r.run(10.0)
        assertEquals(1, r.tower.worked, "a second pass over the same visit")
        assertTrue(later.note.contains("Lost Sector Tower has worked this lost_sector; leaving it to you"), later.note)
        assertEquals(0, r.dungeons.worked + r.summons.worked)
        assertTrue(r.cap.taps.isEmpty())
        assertNull(r.director.parked)
    }

    /**
     * No minutes on the page, or the task left out (the Dungeons row off or
     * locked, Skills.rowFor): the page stands still and says so -- no park,
     * as the Crests page stood as `unknown` before it had a name.
     */
    @Test
    fun `with no minutes or the task left out, the Crests page stands still and is no park`() {
        val r = Rig()
        r.tower.budget = false
        r.cap.scene = { PaintLostSector.page() }
        r.run(6.0)
        assertEquals(0, r.tower.worked)
        assertTrue(r.log.any { it.contains("Lost Sector Tower: nothing to do on the lost_sector") }, r.log.toString())
        assertNull(r.director.parked)

        val off = Rig()
        val d = DirectorLoop(off.cap, listOf(off.dungeons, off.summons, off.tower), listOf(off.passive, off.quest),
                             { GAME }, { SkillSettings.MODE_SEMI }, off.chain,
                             included = { it.key != "lost_sector" },
                             log = { off.log += it }, keep = { _, _ -> }, now = { off.t })
        off.cap.scene = { PaintLostSector.page() }
        var last: Director.Tick? = null
        repeat(6) { last = d.tick().also { off.t += it.beat } }
        d.close()
        assertEquals(0, off.tower.worked)
        assertEquals("the Crests page; nothing to do here", last!!.note)
        assertNull(d.parked)
        assertTrue(off.cap.taps.isEmpty())
    }

    /**
     * The tower's panel is a dialog, like the dungeon panel, and the business
     * of whoever opened it (3.2 point 3): never handed to the tower, and a
     * panel nobody here opened is the ordinary park.
     */
    @Test
    fun `the tower's panel is nobody's to be handed`() {
        val r = Rig()
        r.cap.scene = { PaintLostSector.panel() }
        val t = r.run(6.0)
        assertEquals(Director.DIALOG, t.screen)
        assertEquals(0, r.tower.worked)
        assertEquals("Something is in the way that I did not put there: dialog.", r.director.parked)
        assertTrue(r.cap.taps.isEmpty())
    }

    /**
     * The fully automatic mode: `lost_sector` is a chain step of its own
     * (question 3), run from the main screen after the step before it, and
     * skipped with the chain's sentence where the page has no minutes.
     */
    @Test
    fun `fully automatic, the Lost Sector Tower is a step of its own`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "lost_sector"))
        r.cap.scene = { Paint.mainScreen() }
        assertEquals("run dungeon", r.tick().did)
        r.run(6.0)
        assertEquals(1, r.tower.ran, r.log.toString())
        assertEquals(0, r.tower.worked)
        assertTrue(r.log.contains("chain: starting Lost Sector Tower"), r.log.toString())
        assertTrue(r.chain.finished)

        val none = Rig(SkillSettings.MODE_FULL, listOf("lost_sector", "summon"))
        none.tower.budget = false
        none.cap.scene = { Paint.mainScreen() }
        none.run(6.0)
        assertEquals(0, none.tower.ran)
        assertEquals(1, none.summons.ran)
        assertEquals(listOf("lost_sector" to "nothing to do, on the settings as they stand"), none.chain.skipped)
    }

    /**
     * The chain's second hand (3.2 point 4): a step that leaves the Crests
     * page standing is asked for its own way home once SETTLE has run out
     * (Skill.leave), and the next step starts from the main screen it
     * brings back.
     */
    @Test
    fun `the Crests page left open after the tower's step is the tower's to leave, and the next step starts`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("lost_sector", "summon"))
        r.tower.onRun = { r.cap.scene = { PaintLostSector.page() } }
        r.tower.onLeave = { r.cap.scene = { Paint.mainScreen() }; true }
        r.cap.scene = { Paint.mainScreen() }
        assertEquals("run lost_sector", r.tick().did)
        r.run(Director.SETTLE - 1.0)
        assertEquals(0, r.tower.left, "asked to leave inside SETTLE")
        r.run(8.0)
        assertEquals(1, r.tower.left, r.log.toString())
        assertEquals(1, r.summons.ran, r.log.toString())
        assertTrue(r.log.contains("  chain: lost_sector left open after Lost Sector Tower; asking Lost Sector Tower to leave"),
                   r.log.toString())
        assertNull(r.director.parked)
        assertTrue(r.cap.taps.isEmpty(), "the director tapped something itself")
    }

    /**
     * And the one thing the tower must never leave behind: its panel. A
     * dialog has no skill and dims the globe, so the second hand has nothing
     * to press -- a park with the frame, which is why the tower's own `run`
     * closes its panel before it goes home.
     */
    @Test
    fun `the tower's panel left open after its step is a park with the frame`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("lost_sector", "summon"))
        r.tower.onRun = { r.cap.scene = { PaintLostSector.panel() } }
        r.cap.scene = { Paint.mainScreen() }
        r.tick()
        val t = r.run(Director.SETTLE + 4.0)
        assertEquals("The dialog is open after Lost Sector Tower, and I found no way home from it.", t.note)
        assertEquals(listOf("no_way_home"), r.kept)
        assertEquals(0, r.tower.left, "the panel is no screen of the tower's to leave from")
        assertEquals(0, r.summons.ran)
        assertTrue(r.cap.taps.isEmpty())
    }

    // ==================================================================
    // EX Missions: the Missions window and its EX tab (PLAN_EX_MISSIONS.md)
    // ==================================================================

    /** The director over the painted Missions world, with the real ExMissionsSkill and a stand-in step. */
    private class MissionsRig(mode: String = SkillSettings.MODE_SEMI, steps: List<String> = emptyList(),
                              included: Boolean = true) {
        val w = MissionsWorld()
        val log = ArrayList<String>()
        val kept = ArrayList<String>()
        val skill = ExMissionsSkill(w, log = { log += it }, on = { w.on }, keep = { _, tag -> kept += tag },
                                    sleep = { w.t += it }, now = { w.t })
        val other = Counting("dungeon", "Dungeons", Director.DUNGEON_LIST)
        val chain = Chain(steps.map { Chain.Step(it) }, log = { log += it })
        val director = DirectorLoop(
            w, listOf(other, skill), emptyList(), { MissionsWorld.GAME }, { mode }, chain,
            included = { included || it !== skill }, on = { w.on }, log = { log += it },
            keep = { _, tag -> kept += tag }, now = { w.t })

        fun tick(): Director.Tick = director.tick().also { w.t += it.beat }

        fun run(seconds: Double): Director.Tick {
            val until = w.t + seconds
            var last = tick()
            while (w.t < until) last = tick()
            return last
        }
    }

    @Test
    fun `the EX tab the player opened is the EX Missions task's after three quiet seconds, while it has yellow`() {
        val r = MissionsRig()
        r.w.screen = MissionsWorld.Screen.WINDOW
        r.w.tab = Missions.EX
        r.w.r2 = 2
        val first = r.tick()
        assertEquals(Director.EX_MISSIONS, first.screen)
        assertNull(first.did, "inside the player's three seconds: $first")
        val handed = r.run(3.0)
        assertEquals("work exmissions", handed.did, "$handed")
        assertTrue(r.log.contains("giving the ex_missions to EX Missions"), r.log.toString())
        assertEquals(2, r.w.claimsMade)
        // Clean now: nothing to do, and the window left to the player.
        val later = r.run(10.0)
        assertEquals(Director.EX_MISSIONS, later.screen)
        assertEquals("nothing to do on the ex_missions", later.note)
        assertEquals(2, r.w.claimsMade)
        // The battle counts on and row 2 turns yellow again: the task's once more.
        r.w.r2 = 1
        r.run(6.0)
        assertEquals(3, r.w.claimsMade)
        assertEquals(MissionsWorld.Screen.WINDOW, r.w.screen)
        assertEquals(emptyList(), r.w.taps.filter { it in MissionsWorld.FORBIDDEN })
        assertNull(r.director.parked)
    }

    /**
     * Question 6: the Missions window the player opens, on whichever tab, is
     * the task's too, and its work ends on the EX tab by its own tap -- the
     * same visit, which the director does not stand waiting for "the
     * missions" to come back from (DirectorLoop.expectBy).
     */
    @Test
    fun `the Missions window on Collection is handed over, and the EX tab it ends on is the same visit`() {
        val r = MissionsRig()
        r.w.screen = MissionsWorld.Screen.WINDOW
        r.w.tab = Missions.COLLECTION
        r.w.r2 = 1
        val handed = r.run(3.5)
        assertEquals("work exmissions", handed.did, "$handed")
        assertEquals(listOf("tab ex", "claim r2", "sheet"), r.w.taps)
        val next = r.tick()
        assertEquals(Director.EX_MISSIONS, next.screen)
        assertTrue(!next.note.startsWith("waiting"), next.note)
        r.run(8.0)
        assertTrue(r.log.none { "did not come back" in it }, r.log.toString())
        assertEquals(listOf("tab ex", "claim r2", "sheet"), r.w.taps, "worked once, and the clean tab left alone")
        assertNull(r.director.parked)
    }

    /** 10.11, the conductor's decision: Daily Missions is the player's tab, yellow Claims or not. */
    @Test
    fun `the Missions window on Daily stands still with the task on, and is no park`() {
        val r = MissionsRig()
        r.w.screen = MissionsWorld.Screen.WINDOW
        r.w.tab = Missions.DAILY
        r.w.r2 = 2
        val t = r.run(10.0)
        assertEquals(Director.MISSIONS, t.screen)
        assertEquals("nothing to do on the missions", t.note)
        assertNull(t.did)
        assertEquals(1, r.log.count { it == "  EX Missions: nothing to do on the missions just now" }, r.log.toString())
        assertNull(r.director.parked)
        assertTrue(r.w.taps.isEmpty(), "${r.w.taps}")
    }

    @Test
    fun `with the EX Missions task off, the Missions window stands still and is no park`() {
        val r = MissionsRig(included = false)
        for ((tab, screen) in listOf(Missions.COLLECTION to Director.MISSIONS, Missions.EX to Director.EX_MISSIONS)) {
            r.w.screen = MissionsWorld.Screen.WINDOW
            r.w.tab = tab
            r.w.r2 = 2
            val t = r.run(6.0)
            assertEquals(screen, t.screen)
            assertEquals("the Missions window; nothing to do here", t.note)
            assertNull(r.director.parked, "a park on the player's own window")
        }
        assertTrue(r.w.taps.isEmpty(), "${r.w.taps}")
    }

    @Test
    fun `the EX Missions chain step walks in from the main screen, claims and comes home`() {
        val r = MissionsRig(SkillSettings.MODE_FULL, listOf(ExMissionsSkill.KEY))
        r.w.r2 = 2
        r.w.r1 = 1
        val first = r.tick()
        assertEquals("run exmissions", first.did, "$first")
        assertTrue(r.log.contains("chain: starting EX Missions"), r.log.toString())
        assertEquals(3, r.w.claimsMade)
        assertEquals(MissionsWorld.Screen.MAIN, r.w.screen)
        r.run(6.0)
        assertTrue(r.chain.finished)
        assertTrue(r.log.any { it.startsWith("the chain is finished") }, r.log.toString())
        assertNull(r.director.parked)
        assertEquals(emptyList(), r.w.taps.filter { it in MissionsWorld.FORBIDDEN })
    }

    /** The chain's second hand: a Missions window standing after another step is EX Missions' to close. */
    @Test
    fun `a Missions window left open after a step is the EX Missions task's to leave`() {
        val r = MissionsRig(SkillSettings.MODE_FULL, listOf("dungeon", ExMissionsSkill.KEY))
        r.other.onRun = {
            r.w.screen = MissionsWorld.Screen.WINDOW
            r.w.tab = Missions.EX
        }
        assertEquals("run dungeon", r.tick().did)
        r.run(Director.SETTLE + 10.0)
        assertTrue(r.log.contains("  chain: ex_missions left open after Dungeons; asking EX Missions to leave"),
                   r.log.toString())
        assertTrue(r.log.contains("  chain: back on the main screen after Dungeons; going on"), r.log.toString())
        assertTrue(r.log.contains("chain: starting EX Missions"), r.log.toString())
        assertEquals(listOf("close", "tile", "tab ex", "close"), r.w.taps)
        assertTrue(r.chain.finished)
        assertNull(r.director.parked)
    }

    /**
     * The bond token's tap on the figure opens the Partner window whenever
     * the token was taken a moment earlier -- and the player keeps it that
     * way: a tap too many is cheaper than a token left lying (2026-09-30).
     * The round knows to close its own window (PassiveSkill.partnerRound),
     * but it is not a skill of any screen but the main one to the director,
     * and both modes parked on the window instead ("The fully automatic mode
     * starts from the main screen, and partner_window is open"). Played with
     * the real round on painted frames, over two windows, in both modes; and
     * a window the player opened is still left alone.
     */
    @Test
    fun `the Partner window the bond token's own tap opened is tapped away, not parked on`() {
        for (mode in listOf(SkillSettings.MODE_SEMI, SkillSettings.MODE_FULL)) {
            val s = PassiveSkillTest.Screen(Paint.W, Paint.H)
            var t = 100.0
            val log = ArrayList<String>()
            val passive = PassiveSkill(s.cap, { _, default -> default }, log = { log += it }, now = { t })
            val director = DirectorLoop(
                s.cap, emptyList(), listOf(passive), { GAME }, { mode }, Chain(emptyList(), log = { log += it }),
                on = { true }, log = { log += it }, keep = { _, _ -> }, now = { t })
            s.cap.scene = { s.frame() }
            fun tick() = director.tick().also { t += it.beat }

            repeat(2) { pass ->
                // The bubble is up, the round taps the figure ...
                s.bubbleAt = 0.51 to 0.34
                s.partner = false
                s.clearActions()
                t += PassiveSkill.BOND_RETRY
                assertEquals(Director.MAIN, tick().screen)
                assertEquals(1, s.figureTaps().size, "$mode, pass $pass: no tap on the figure: $log")
                // ... the token had been taken already, and the window opens.
                s.bubbleAt = null
                s.partner = true
                val close = tick()
                assertEquals(Director.PARTNER_WINDOW, close.screen)
                assertNull(director.parked, "$mode, pass $pass: parked on the bond token's own window: $log")
                assertEquals(1, s.closeTaps().size, "$mode, pass $pass: the window was not tapped away: $log")
                assertEquals(0, s.backs(), "$mode, pass $pass: the back key on a window a tap closes")
                assertEquals(Director.BEAT_MAIN, close.beat, "closed at the slow beat, as partnerRound counts")
                // The window goes, and the main screen is the round's again.
                s.partner = false
                assertEquals(Director.MAIN, tick().screen)
                assertNull(director.parked, "$mode, pass $pass: $log")
                t += PassiveSkill.PARTNER_OWN
            }

            // A window nobody here opened is the player's: no tap, as before.
            s.clearActions()
            s.partner = true
            repeat(3) { tick() }
            assertTrue(s.cap.taps.isEmpty(), "$mode: tapped the player's own window: $log")
            assertEquals(0, s.backs())
            s.partner = false
        }
    }

    /**
     * A prompt a round names as its own ([Skill.opened]) is that round's,
     * in both modes, and nothing is parked on it: the bond tour's "Raise
     * <name>?" where a tour broke off between its Raise and its OK, on
     * LDPlayer instance 0 on 2026-10-03 at 13:16, where the director parked
     * on it as on a prompt nobody raised (PLAN_ABSCHLUSS_1_3.md K4). The
     * director taps nothing on it itself; a prompt no round names parks as
     * before.
     */
    @Test
    fun `a prompt a round names as its own is given to it, not parked on`() {
        for (mode in listOf(SkillSettings.MODE_SEMI, SkillSettings.MODE_FULL)) {
            val r = Rig(mode)
            r.cap.scene = { Paint.prompt(pink = true) }
            r.quest.opens = setOf(Director.PROMPT)
            val own = r.tick()
            assertEquals(Director.PROMPT, own.screen)
            assertNull(r.director.parked, "$mode: parked on the round's own prompt: ${r.log}")
            assertEquals(1, r.quest.worked, "$mode: the prompt was not given to the round that named it")
            assertEquals(0, r.passive.worked, "$mode: nor to a round that did not")
            assertTrue(r.cap.taps.isEmpty(), "$mode: the director tapped the prompt itself")

            r.quest.opens = emptySet()
            r.tick()
            assertTrue(r.director.parked!!.startsWith("A prompt is open"), "$mode: ${r.director.parked}")
            assertEquals(1, r.quest.worked, "$mode: a prompt nobody names is nobody's")
        }
    }

    // ==================================================================
    // PLAN_RELEASE_1_3.md B71, B72, B73 and B75, 2026-10-01 (R3's findings
    // on instance 1).
    // ==================================================================

    /**
     * B71: live on instance 1 the Special Summon step tapped its icon at
     * 13:30:11 and the switch went off at :12; the first look of the pause,
     * at :13, still saw the main screen -- the tap was on its way -- and ended
     * the step's claim, so the summon page the step itself had opened was
     * the player's when the switch came back, and the director parked. The
     * main screen inside the second look of the stop no longer ends a claim;
     * one still standing after it does, as before.
     */
    @Test
    fun `a pause a second after a step's own tap keeps its claim over the main screen it is leaving`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("summon"))
        r.summons.resumes = setOf(Director.SUMMON)
        r.summons.outcome = Outcome.STOPPED
        // The step taps its icon, and the switch goes off a second later: the
        // game still shows the main screen on the pause's first look.
        r.summons.onRun = { r.on = false }
        r.cap.scene = { Paint.mainScreen() }
        assertEquals("run summon", r.tick().did)
        assertEquals("paused, sees main", r.tick().note)
        r.cap.scene = { PaintSummon.modeScreen(1) }
        assertEquals("paused, sees summon", r.run(4.0).note)
        r.on = true
        r.summons.outcome = Outcome.DONE
        r.summons.onRun = { r.cap.scene = { Paint.mainScreen() } }
        r.run(5.0)
        assertEquals(1, r.log.count { it == "chain: resuming Special Summon on the summon" }, r.log.toString())
        assertEquals(listOf(true), r.summons.resumedWhole, "the same pass going on")
        assertTrue(r.log.none { it.contains("will not go on from it") }, r.log.toString())
        assertTrue(r.log.none { it.contains("parked on") }, r.log.toString())
        assertNull(r.director.parked)

        // The main screen still standing after the second look of the stop is
        // the player gone home: the page opened after it is theirs, as before.
        val p = Rig(SkillSettings.MODE_FULL, listOf("summon"))
        p.summons.resumes = setOf(Director.SUMMON)
        p.summons.outcome = Outcome.STOPPED
        p.summons.onRun = { p.on = false }
        p.cap.scene = { Paint.mainScreen() }
        assertEquals("run summon", p.tick().did)
        p.run(Director.SECOND_LOOK + 2.0)
        assertTrue(p.log.contains("  the main in the pause: Special Summon will not go on from it"), p.log.toString())
        p.cap.scene = { PaintSummon.modeScreen(1) }
        p.run(4.0)
        p.on = true
        val t = untilParked(p)
        assertEquals("The fully automatic mode starts from the main screen, and summon is open.", t.note)
        assertEquals(0, p.summons.resumed)
    }

    /** A frame of the corpus, as `grab` would have handed it over. */
    private fun corpusFrame(path: String): Mat {
        val f = java.io.File(java.io.File(System.getProperty("digiautotap.repo") ?: ".."), path)
        org.junit.jupiter.api.Assumptions.assumeTrue(f.exists(), "$path is not in the corpus")
        return OracleFamilies.read(f)
    }

    /**
     * B72 and B73: the game's "Time Sale!" window over the Stage Failed
     * banner (1080 x 2235, after Special Summon's X) and its Help tutorial
     * over the Digivice's first visit (1080 x 1920), the frames the director
     * kept on instance 1 that day. What closes either is not measured, and
     * nothing taps them: the semi-automatic mode stands still with the
     * window's sentence, no park; the fully automatic one parks with it and
     * keeps the frame, after a step as before one.
     */
    @org.junit.jupiter.api.Tag(OracleFamilies.CORPUS_TAG)
    @Test
    fun `the game's sale window and its Help tutorial are read and nothing is tapped on them`() {
        val windows = listOf(
            Triple("corpus/summon/time_sale_over_main_1080x2340_hole105_134844.png", Director.SALE,
                   Director.SALE_SAYS to Director.SALE_PARK),
            Triple("corpus/preset/help_digivice_131037.png", Director.HELP,
                   Director.HELP_SAYS to Director.HELP_PARK))
        for ((path, screen, says) in windows) {
            val frame = corpusFrame(path)
            assertEquals(screen, Director.classify(frame).screen, path)

            val semi = Rig()
            semi.cap.scene = { Paint.copy(frame) }
            val stood = semi.run(10.0)
            assertEquals(says.first, stood.note, path)
            assertNull(semi.director.parked, "$path: ${semi.log}")
            assertTrue(semi.cap.taps.isEmpty(), "$path: ${semi.cap.taps}")
            assertEquals(1, semi.log.count { it == says.first }, "said once: ${semi.log}")

            // Fully automatic, the window come up on the way home of a step.
            val full = Rig(SkillSettings.MODE_FULL, listOf("summon", "dungeon"))
            full.summons.onRun = { full.cap.scene = { Paint.copy(frame) } }
            full.cap.scene = { Paint.mainScreen() }
            assertEquals("run summon", full.tick().did)
            val parked = untilParked(full)
            assertEquals(says.second, parked.note, "$path: ${full.log}")
            assertTrue(full.cap.taps.isEmpty(), "$path: ${full.cap.taps}")
            assertTrue(full.kept.contains(screen), "$path: ${full.kept}")
            assertEquals(0, full.dungeons.ran, "the chain stands under the window")
            // The player closes it: the chain goes on from the main screen.
            full.cap.scene = { Paint.mainScreen() }
            full.run(8.0)
            assertNull(full.director.parked, full.log.toString())
            assertEquals(1, full.dungeons.ran, full.log.toString())
        }
    }

    /**
     * B75: with the dot on, every return to the game on the Explore menu
     * parked red, "Something is in the way that I did not put there:
     * explore_menu." (R3, instance 1, 13:05:41 to 13:06:21) -- a menu the
     * player opened, with nothing of ours in the way, as B31 found for the
     * title. The semi-automatic mode stands still there now; the fully
     * automatic one parks as before, since its chain starts from the main
     * screen.
     */
    @Test
    fun `on the Explore menu the semi-automatic mode stands still and says so`() {
        val r = Rig()
        r.cap.scene = { PaintFarm.exploreMenu() }
        assertEquals(Director.EXPLORE_MENU, Director.classify(PaintFarm.exploreMenu()).screen)
        val t = r.run(10.0)
        assertEquals("the Explore menu; nothing to do here", t.note)
        assertNull(r.director.parked, r.log.toString())
        assertTrue(r.log.none { it.contains("Something is in the way") }, r.log.toString())
        assertTrue(r.cap.taps.isEmpty())
        // Back in the game from this app's page, twice: no park either time.
        repeat(2) {
            r.cap.front = APP
            r.run(2.0)
            r.cap.front = GAME
            r.run(6.0)
        }
        assertNull(r.director.parked, r.log.toString())
        assertTrue(r.log.none { it.contains("parked on") }, r.log.toString())

        val full = Rig(SkillSettings.MODE_FULL, listOf("dungeon"))
        full.cap.scene = { PaintFarm.exploreMenu() }
        assertEquals("The fully automatic mode starts from the main screen, and explore_menu is open.",
                     untilParked(full).note)
    }

    /**
     * N3 a (PLAN_BEFUNDE_1_3.md): Idle Rewards left the interface on
     * 2026-10-02 and no skill works on its window any more. The window the
     * player opens by hand is theirs, as the Missions window is: the
     * semi-automatic mode stands still and says so, nothing tapped, no park.
     * Red with the window in the old branch beside `dialog`, which parked
     * with "Something is in the way that I did not put there".
     */
    @org.junit.jupiter.api.Tag(OracleFamilies.CORPUS_TAG)
    @Test
    fun `the Idle Rewards window with no task is the player's in the semi-automatic mode`() {
        val frame = corpusFrame("corpus/quest/idle_window_two_080147.png")
        assertEquals(Director.CLAIM_REWARDS, Director.classify(frame).screen)
        val r = Rig()
        r.cap.scene = { Paint.copy(frame) }
        val t = r.run(10.0)
        assertEquals("the Idle Rewards window; nothing to do here", t.note)
        assertNull(r.director.parked, r.log.toString())
        assertTrue(r.cap.taps.isEmpty(), r.cap.taps.toString())
    }

    /**
     * N3 c (PLAN_BEFUNDE_1_3.md), the Poco on 2026-10-01: the chain's quest
     * step was playing its dungeon when a round ended in CaptureError (the
     * dot held down, this app opened, 20:34:24); back in the game, the list
     * the step had left was nobody's, and the director parked on it --
     * 20:34:33, 20:34:51, 20:35:42 -- until the player went home by hand. The
     * step's CaptureError is the step's park now, and a park of the fully
     * automatic mode that is not wanted goes home by itself after
     * PARK_RETRY, by the screen's own task's way home; the chain's step
     * begins again from the main screen. Red on the old director: the
     * CaptureError leaves `tick`, and the list is "the player's".
     */
    @Test
    fun `a park in the fully automatic mode goes home by itself after five seconds and tries again`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon"))
        r.cap.scene = { Paint.mainScreen() }
        // The step's first run loses its frame on its own list, as the
        // quest step's dungeon did when this app came to the front.
        r.dungeons.onRun = {
            r.cap.scene = { Paint.dungeonList() }
            if (r.dungeons.ran == 1) throw CaptureError("the game is not in front (io.github.digipr1me.digiautotap)")
        }
        assertEquals("run dungeon", r.tick().did)
        val parked = untilParked(r, 20.0)
        assertEquals("Dungeons parked: no frame: the game is not in front (io.github.digipr1me.digiautotap)",
                     parked.note, r.log.toString())
        r.dungeons.onLeave = { r.cap.scene = { Paint.mainScreen() }; true }
        // Not before the five seconds.
        r.run(Director.PARK_RETRY - 2.0)
        assertEquals(0, r.dungeons.left, r.log.toString())
        r.run(10.0)
        // A lost frame, so not one of N3's tries (A5g, below).
        assertTrue(r.log.any {
            it.endsWith("-- going home and going on with Dungeons after the game came back")
        }, r.log.toString())
        assertTrue(r.log.none { it.contains("going home and trying again") }, r.log.toString())
        assertEquals(1, r.dungeons.left, "the list's own way home: ${r.log}")
        assertTrue(r.log.contains("  chain: back on the main screen after the park; going on with dungeon"),
                   r.log.toString())
        assertEquals(2, r.dungeons.ran, "the step, tried again from the main screen: ${r.log}")
        r.run(20.0)
        assertNull(r.director.parked, r.log.toString())
        assertTrue(r.chain.finished, r.log.toString())
    }

    /**
     * N3 c, live on instance 0 on 2026-10-02: the same CaptureError on the
     * other way in, the quest loop's round of the main screen (after the
     * chain's first step, `justBack`) playing DemiDevimon when the dot was
     * held down at 11:05:43. The round's error left the tick at 11:05:46,
     * and the screen it left read as the player's at 11:06:25. A round's
     * leftover is taken home as a step's is, and nothing in the chain is
     * retired for it. Red on the old director: the CaptureError leaves
     * `tick`.
     */
    @Test
    fun `a round of the main screen that lost its frame away from it is taken home`() {
        val r = Rig(SkillSettings.MODE_FULL, emptyList())
        r.cap.scene = { Paint.mainScreen() }
        r.quest.onWork = {
            if (r.quest.worked == 1) {
                r.cap.scene = { Paint.dungeonList() }
                throw CaptureError("the game is not in front (io.github.digipr1me.digiautotap)")
            }
        }
        r.dungeons.onLeave = { r.cap.scene = { Paint.mainScreen() }; true }
        r.tick()
        assertTrue(r.log.contains("  the quest loop lost its frame in its round -- " +
                                  "the game is not in front (io.github.digipr1me.digiautotap)"), r.log.toString())
        r.run(20.0)
        assertEquals(1, r.dungeons.left, "the list's own way home: ${r.log}")
        assertTrue(r.log.none { it.contains("parked on") }, r.log.toString())
        assertTrue(r.log.contains("  chain: back on the main screen after the quest loop; going on with " +
                                  "the main screen's round"), r.log.toString())
        assertTrue(r.quest.worked >= 2, "the rounds go on: ${r.log}")
        assertTrue(r.chain.retired.isEmpty())
    }

    /**
     * A5i (PLAN_ABSCHLUSS_1_3.md, question 22): a screen no skill works on
     * that one skill's way home takes home all the same -- a dungeon's
     * panel, `dialog` -- is that skill's to leave where a round or a step
     * left it, as a skill's own screen is. A5g's look 5 on instance 0: the
     * quest loop's round lost its frame on DemiDevimon's panel, and the
     * second hand parked with "no way home" (here on the tower's painted
     * panel, which classifies as the same `dialog`; DungeonPanelHomeFlowTest
     * plays the real frames with the real Dungeons). The player's dialog
     * before any step stays a park, and is not asked about. Red on A5g's
     * director, which asked `worksOn` alone.
     */
    @Test
    fun `a dialog a round left is given to the skill whose way home leaves it`() {
        val r = Rig(SkillSettings.MODE_FULL, emptyList())
        r.dungeons.leaves = setOf(Director.DIALOG)
        r.cap.scene = { Paint.mainScreen() }
        r.quest.onWork = {
            if (r.quest.worked == 1) {
                r.cap.scene = { PaintLostSector.panel() }
                throw CaptureError("the game is not in front ($APP)")
            }
        }
        r.dungeons.onLeave = { r.cap.scene = { Paint.mainScreen() }; true }
        r.run(30.0)
        assertEquals(1, r.dungeons.left, r.log.toString())
        assertTrue(r.log.contains("  chain: dialog left open after the quest loop; asking Dungeons to leave"),
                   r.log.toString())
        assertTrue(r.log.none { it.contains("parked on") }, r.log.toString())
        assertTrue(r.quest.worked >= 2, "the rounds go on: ${r.log}")
        assertTrue(r.kept.isEmpty(), r.kept.toString())

        val player = Rig(SkillSettings.MODE_FULL, listOf("summon"))
        player.dungeons.leaves = setOf(Director.DIALOG)
        player.cap.scene = { PaintLostSector.panel() }
        player.run(Director.PARK_RETRY * 6)
        assertEquals("The fully automatic mode starts from the main screen, and dialog is open.",
                     player.director.parked, player.log.toString())
        assertEquals(0, player.dungeons.left)
        assertTrue(player.cap.taps.isEmpty() && player.cap.backs == 0)
    }

    /** N3 c: the player's screen before the first step is theirs, and stays a park (F27). */
    @Test
    fun `the player's screen before the first step stays a park`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon"))
        r.cap.scene = { Paint.dungeonList() }
        assertEquals("The fully automatic mode starts from the main screen, and dungeon_list is open.",
                     untilParked(r).note)
        r.run(Director.PARK_RETRY * 6)
        assertNotNull(r.director.parked)
        assertEquals(0, r.dungeons.left)
        assertTrue(r.log.none { it.contains("going home") }, r.log.toString())
    }

    /**
     * N3 c: three tries at the same step, then the park stands
     * PARK_LONG, the step is retired for this chain run, and the way home
     * is tried once more for the next step. A step that parks and does not
     * come home is tried again each time the main screen is reached.
     */
    @Test
    fun `three tries at the same step, then ten minutes and the next step`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("summon", "dungeon"))
        // Special Summon parks and leaves the dungeon list standing, whose
        // way home does nothing.
        r.summons.outcome = Outcome.parked("something in the way")
        r.summons.onRun = { r.cap.scene = { Paint.dungeonList() } }
        r.cap.scene = { Paint.mainScreen() }
        assertEquals("run summon", r.tick().did)
        assertEquals("Special Summon parked: something in the way", untilParked(r, 20.0).note)
        r.run(80.0)
        val tries = r.log.filter { it.contains("going home and trying again") }
        assertEquals(Director.PARK_RETRIES, tries.size, r.log.toString())
        assertEquals(Director.PARK_RETRIES, r.dungeons.left, "the list's way home, once a try")
        assertNotNull(r.director.parked, "after the last try the park stands: ${r.log}")
        assertEquals(1, r.kept.count { it == "parked_not_home" } + r.kept.count { it == "no_way_home" },
                     "one frame for the cause, not one a try: ${r.kept}")
        // Ten minutes later: the step is left for this run, and home again.
        r.dungeons.onLeave = { r.cap.scene = { Paint.mainScreen() }; true }
        r.run(Director.PARK_LONG)
        assertTrue(r.chain.retiredReason("summon")!!.startsWith("parked after ${Director.PARK_RETRIES} tries"),
                   r.chain.retired.toString())
        r.run(10.0)
        assertEquals(1, r.summons.ran, r.log.toString())
        assertEquals(1, r.dungeons.ran, "the next step: ${r.log}")
        assertNull(r.director.parked)
    }

    /** N3 c: a step that parked and did not come home is tried again once the way home has worked. */
    @Test
    fun `a step that parked away from home is tried again after the way home`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("summon"))
        r.summons.outcome = Outcome.parked("something in the way")
        r.summons.onRun = {
            r.cap.scene = { Paint.dungeonList() }
            if (r.summons.ran >= 2) r.summons.outcome = Outcome.DONE
        }
        r.dungeons.onLeave = { r.cap.scene = { Paint.mainScreen() }; true }
        r.cap.scene = { Paint.mainScreen() }
        r.run(60.0)
        assertEquals(2, r.summons.ran, r.log.toString())
        assertTrue(r.chain.finished, r.log.toString())
        assertNull(r.director.parked)
    }

    /**
     * N3 c: the wanted parks stay. The semi-automatic mode's park is the
     * player's screen, and a pink prompt nobody here raised is not answered
     * in either mode: neither goes home by itself.
     */
    @Test
    fun `a wanted park stays where it is`() {
        val semi = Rig()
        semi.cap.scene = { Paint.prompt(pink = true) }
        assertNotNull(untilParked(semi))
        semi.run(Director.PARK_RETRY * 6)
        assertNotNull(semi.director.parked)
        assertTrue(semi.log.none { it.contains("going home") }, semi.log.toString())

        val full = Rig(SkillSettings.MODE_FULL, listOf("dungeon"))
        full.cap.scene = { Paint.prompt(pink = true) }
        assertNotNull(untilParked(full))
        full.run(Director.PARK_RETRY * 6)
        assertNotNull(full.director.parked)
        assertTrue(full.log.none { it.contains("going home") }, full.log.toString())
        assertTrue(full.cap.taps.isEmpty(), full.cap.taps.toString())
    }

    /**
     * N3 d (PLAN_BEFUNDE_1_3.md): the bond token's look from inside the
     * quest loop's chain step (QuestSkill's `aside`). The other rounds work
     * on a frame of their own on the plain main screen, the running one is
     * not called again, and nothing happens off the main screen, with the
     * row switched off, or with the main switch off.
     */
    @Test
    fun `inside the quest step the other rounds of the main screen get their look`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("quest"))
        r.cap.scene = { Paint.mainScreen() }
        r.director.aside("quest")
        assertEquals(1, r.passive.worked)
        assertEquals(0, r.quest.worked, "the step itself is not a round of its own look")
        assertTrue(r.named.isEmpty(), "the plate goes on naming the task at work: ${r.named}")

        r.cap.scene = { Paint.dungeonList() }
        r.director.aside("quest")
        assertEquals(1, r.passive.worked, "off the main screen nothing")

        r.cap.scene = { Paint.mainScreen() }
        r.passive.budget = false
        r.director.aside("quest")
        assertEquals(1, r.passive.worked, "a round with nothing to do is not asked")
        r.passive.budget = true
        r.off = setOf("passive")
        r.director.aside("quest")
        assertEquals(1, r.passive.worked, "nor one switched off")
        r.off = emptySet()
        r.on = false
        r.director.aside("quest")
        assertEquals(1, r.passive.worked, "nor with the main switch off")
        r.on = true
        r.director.aside("quest")
        assertEquals(2, r.passive.worked)
    }

    // ==================================================================
    // A step that lost its frame (PLAN_ABSCHLUSS_1_3.md K1)
    // ==================================================================

    /**
     * Rounds until [done] or [seconds] are over. Whenever a step has put
     * this app in front, it stays there for [away] seconds and the game comes
     * back -- the player holding the dot, looking at the page and going back
     * into the game (18:27:45 to 18:27:50 in the report `5a8b5a23a3c0`).
     */
    private fun withLooks(r: Rig, seconds: Double, away: Double = 5.0, done: () -> Boolean = { false }) {
        val until = r.t + seconds
        var since: Double? = null
        while (r.t < until && !done()) {
            if (r.cap.front == APP) {
                val at = since ?: r.t.also { since = it }
                if (r.t - at >= away) {
                    r.cap.front = GAME
                    since = null
                }
            }
            r.tick()
        }
    }

    /**
     * K1, the report `5a8b5a23a3c0`: at 18:27:45 the player held the dot in
     * the middle of the quest loop's step, this app came to the front, the
     * step lost its frame ("the quest loop could not go on -- no frame: the
     * game is not in front"), and at 18:27:53, the game back on its main
     * screen, "chain: retiring quest for this run" -- a look into the app
     * cost the task for the whole chain run. The same step begins again
     * there now, over two rounds of the chain, and since A5g nothing counts
     * it ("after the game came back", no "n of 3"). The first round loses its frame as
     * World Search did at 18:58:44 (thrown, the director's own catch), the
     * second as the quest loop did (the skill's own outcome). Dungeons
     * stands in for the quest loop, which the rig has as a round only. Red
     * on the old director with "retiring dungeon for this run -- no frame".
     */
    @Test
    fun `a step that lost its frame begins again on the main screen, over two rounds of the chain`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"), repeat = true)
        r.cap.scene = { Paint.mainScreen() }
        val lost = "the game is not in front ($APP)"
        val fronts = ArrayList<String?>()
        r.dungeons.onRun = {
            fronts += r.cap.front
            r.dungeons.outcome = Outcome.DONE
            when (r.dungeons.ran) {
                1 -> { r.cap.front = APP; throw CaptureError(lost) }
                3 -> { r.cap.front = APP; r.dungeons.outcome = Outcome.noFrame(CaptureError(lost)) }
            }
        }
        r.summons.onRun = { fronts += r.cap.front }
        withLooks(r, 300.0) { r.director.chain.round > 2 }
        val retired = r.log.filter { it.contains("retiring") }
        assertTrue(retired.isEmpty(), "a step that only lost its frame was retired: $retired")
        assertEquals(4, r.dungeons.ran, r.log.toString())
        assertEquals(4, r.log.count { it == "chain: starting Dungeons" }, r.log.toString())
        assertEquals(2, r.summons.ran, "the next step after it, in each round: ${r.log}")
        assertEquals(2, r.log.count { it == "  chain: going on with Dungeons after the game came back" },
                     r.log.toString())
        assertTrue(r.log.none { it.contains(" of ${Director.PARK_RETRIES})") }, r.log.toString())
        assertTrue(fronts.all { it == GAME }, "a step began with the game not in front: $fronts")
        assertTrue(r.chain.retired.isEmpty(), r.chain.retired.toString())
        assertNull(r.director.parked)
        assertTrue(r.kept.isEmpty(), "kept a frame of a screen nothing was wrong with: ${r.kept}")
    }

    /**
     * What K1 must leave as it is: what decides is the outcome's own mark,
     * never its words. A step that could not get in and came home is R2's,
     * retired for this chain run, the next step after it -- even where its
     * sentence begins "no frame".
     */
    @Test
    fun `a step that could not get in still retires when it comes home, whatever its sentence says`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.dungeons.outcome = Outcome.parked("no frame to read the list on")
        r.cap.scene = { Paint.mainScreen() }
        r.run(20.0)
        assertEquals(1, r.dungeons.ran, r.log.toString())
        assertEquals(1, r.summons.ran)
        assertEquals("no frame to read the list on", r.chain.retiredReason("dungeon"))
        assertTrue(r.log.contains("  chain: retiring dungeon for this run -- no frame to read the list on"),
                   r.log.toString())
        assertTrue(r.log.none { it.contains("after the game came back") }, r.log.toString())
    }

    /**
     * A5g, the player on 2026-10-03 (PLAN_ABSCHLUSS_1_3.md, question 17):
     * "Der Bot soll immer einfach weitermachen", and on the reason for the
     * limit -- a tap of the bot's own that leads out of the game -- "nie
     * zählen". A step that loses its frame five times running, the game back
     * on its main screen each time, is begun again each time; never "lost
     * its frame again after", never N3's ten minutes, never retired. Its
     * sixth run gets through and the next step comes, in both rounds of a
     * repeating chain. Red on A5b's director, which parked ten minutes after
     * the fourth loss. What keeps a step from beginning blind is the game in
     * front, and nothing else (`withLooks` keeps it away for five seconds).
     */
    @Test
    fun `a step that loses its frame five times running is begun again each time, over two rounds of the chain`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"), repeat = true)
        r.cap.scene = { Paint.mainScreen() }
        val fronts = ArrayList<String?>()
        // In each round of the chain the first five runs lose their frame, the sixth is done.
        r.dungeons.onRun = {
            fronts += r.cap.front
            if ((r.dungeons.ran - 1) % 6 < 5) {
                r.cap.front = APP
                throw CaptureError("the game is not in front ($APP)")
            }
        }
        val start = r.t
        withLooks(r, 1200.0) { r.director.chain.round > 2 }
        assertEquals(12, r.dungeons.ran, r.log.toString())
        assertEquals(10, r.log.count { it == "  chain: going on with Dungeons after the game came back" },
                     r.log.toString())
        assertEquals(2, r.summons.ran, "the next step after it, in each round: ${r.log}")
        assertTrue(r.log.none { it.contains("lost its frame again") || it.contains("retiring") ||
                                it.contains("going home and trying again") }, r.log.toString())
        assertTrue(r.t - start < Director.PARK_LONG, "the chain stood ten minutes somewhere: ${r.log}")
        assertTrue(fronts.all { it == GAME }, "a step began with the game not in front: $fronts")
        assertTrue(r.chain.retired.isEmpty(), r.chain.retired.toString())
        assertNull(r.director.parked)
        assertTrue(r.kept.isEmpty(), "kept a frame of a screen nothing was wrong with: ${r.kept}")
    }

    /**
     * A5g, the other way back: the game comes back on the step's own screen
     * (World Search on its board at 18:58:44 in the report `5a8b5a23a3c0`,
     * here Dungeons on its list). The park goes home after
     * [Director.PARK_RETRY] by the screen's own way, as N3's does, but is
     * not one of its tries: five losses, five ways home, no ten minutes, the
     * step done at the sixth run and the next step after it. No frame is
     * kept: the list shows nothing that was wrong (K8). Red on A5b's director,
     * which counted these parks in N3's three.
     */
    @Test
    fun `a step that loses its frame away from home five times goes home and on each time, uncounted`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.cap.scene = { Paint.mainScreen() }
        r.dungeons.onRun = {
            r.cap.scene = { Paint.dungeonList() }
            if (r.dungeons.ran <= 5) {
                r.cap.front = APP
                throw CaptureError("the game is not in front ($APP)")
            }
        }
        r.dungeons.onLeave = { r.cap.scene = { Paint.mainScreen() }; true }
        val start = r.t
        withLooks(r, 1200.0) { r.chain.finished }
        assertEquals(6, r.dungeons.ran, r.log.toString())
        assertEquals(6, r.dungeons.left, "the list's own way home, once a loss and once after the run that got through: ${r.log}")
        assertEquals(5, r.log.count {
            it.endsWith("-- going home and going on with Dungeons after the game came back")
        }, r.log.toString())
        assertEquals(1, r.summons.ran, r.log.toString())
        assertTrue(r.log.none { it.contains("going home and trying again") || it.contains("retiring") },
                   r.log.toString())
        assertTrue(r.t - start < Director.PARK_LONG, "the chain stood ten minutes somewhere: ${r.log}")
        assertTrue(r.chain.retired.isEmpty(), r.chain.retired.toString())
        assertNull(r.director.parked)
        assertTrue(r.kept.isEmpty(), "kept a frame of a screen nothing was wrong with: ${r.kept}")
    }

    /**
     * What A5g leaves as it is: a park that is no lost frame is N3's, three
     * tries and then [Director.PARK_LONG] and the next step; and a lost
     * frame between two such parks neither clears that count nor adds to
     * it. Special Summon parks away from home ("something in the way") and
     * loses its frame on the main screen in turn: the parks say 1, 2 and 3
     * of 3, the three losses say nothing of a count, and the fourth park
     * stands ten minutes before the step is left for the run. Red on A5b's
     * director, which counted the losses with the parks.
     */
    @Test
    fun `a lost frame between a step's parks neither clears nor raises N3's count`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("summon", "dungeon"))
        r.cap.scene = { Paint.mainScreen() }
        r.summons.onRun = {
            if (r.summons.ran % 2 == 1) {
                r.summons.outcome = Outcome.parked("something in the way")
                r.cap.scene = { Paint.dungeonList() }
            } else {
                r.cap.front = APP
                throw CaptureError("the game is not in front ($APP)")
            }
        }
        r.dungeons.onLeave = { r.cap.scene = { Paint.mainScreen() }; true }
        withLooks(r, 400.0) { r.summons.ran == 7 && r.director.parked != null }
        assertEquals(7, r.summons.ran, r.log.toString())
        for (n in 1..Director.PARK_RETRIES) {
            assertEquals(1, r.log.count { it.endsWith("going home and trying again ($n of ${Director.PARK_RETRIES})") },
                         r.log.toString())
        }
        assertEquals(3, r.log.count { it == "  chain: going on with Special Summon after the game came back" },
                     r.log.toString())
        assertEquals("Special Summon parked: something in the way", r.director.parked, r.log.toString())
        assertEquals(0, r.dungeons.ran)
        r.run(Director.PARK_LONG - 10.0)
        assertNotNull(r.director.parked, "the park stands its ten minutes: ${r.log.takeLast(5)}")
        assertEquals(0, r.dungeons.ran)
        r.run(30.0)
        assertEquals("parked after ${Director.PARK_RETRIES} tries: Special Summon parked: something in the way",
                     r.chain.retiredReason("summon"), r.chain.retired.toString())
        assertEquals(1, r.dungeons.ran, "the next step: ${r.log}")
        assertEquals(7, r.summons.ran)
        assertNull(r.director.parked)
    }

    // ==================================================================
    // A row switched off and on (PLAN_ABSCHLUSS_1_3.md A2)
    // ==================================================================

    /**
     * The player on 2026-10-02: "Tasks, die ein daily limit haben, durch ein
     * Aus- und Einmachen des Tasks neustarten". A step the chain retired for
     * the day -- the quest loop at 18:33:35 on the Poco, out of tickets --
     * runs again in the same chain run once its row is switched off and on,
     * even where the round has passed it already, and the chain goes on
     * from where it stood: counted over the rounds, it ran once in the round
     * of the switch and nothing ran twice. Retired again by its own run, it
     * stays retired until the next switch.
     */
    @Test
    fun `a step retired for the day runs again after its switch, once in this chain run`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon", "lost_sector"), repeat = true)
        r.dungeons.outcome = Outcome.retired("out of tickets until 08:00")
        val dungeon = ArrayList<Int>()
        val summon = ArrayList<Int>()
        val tower = ArrayList<Int>()
        r.dungeons.onRun = { dungeon += r.director.chain.round }
        r.tower.onRun = { tower += r.director.chain.round }
        r.summons.onRun = {
            summon += r.director.chain.round
            // The switch off and on while Summon plays the second round,
            // which skipped the dungeon step as retired.
            if (r.director.chain.round == 2) r.director.restart("dungeon", false)
        }
        r.cap.scene = { Paint.mainScreen() }
        var n = 0
        while (r.director.chain.round < 4 && n++ < 200) r.tick()
        assertEquals(listOf(1, 2), dungeon, r.log.toString())
        assertEquals(listOf(1, 2, 3), summon)
        assertEquals(listOf(1, 2, 3), tower)
        assertEquals(1, r.dungeons.restarts)
        assertTrue(r.log.contains("Dungeons: started again by its switch -- no longer retired for this chain run " +
                                  "(out of tickets until 08:00)"), r.log.toString())
        assertTrue(r.log.contains("chain: dungeon started again by its switch, once more in this run -- next: dungeon"),
                   r.log.toString())
        assertEquals("out of tickets until 08:00", r.director.chain.retiredReason("dungeon"),
                     "retired again by its own run")
    }

    /**
     * K9's other shape: a step whose day's count is full is skipped, not
     * retired ("chain: skipping skewer -- 15 of 15 combos counted today",
     * 18:29:07 and 19:11:44). The shell takes the count out of the file and
     * says the day held it; the step runs in this chain run, the finished
     * chain included, and the step that ran is not run again. A second
     * restart with nothing of the day left is nothing.
     */
    @Test
    fun `a step its day held back runs again after its switch, a finished chain included`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("summon", "dungeon"))
        r.summons.budget = false
        r.cap.scene = { Paint.mainScreen() }
        r.run(10.0)
        assertTrue(r.director.chain.finished, r.log.toString())
        assertEquals(0, r.summons.ran)
        assertEquals(1, r.dungeons.ran)

        r.summons.budget = true
        r.director.restart("summon", true)
        r.run(10.0)
        assertEquals(1, r.summons.ran, r.log.toString())
        assertEquals(1, r.dungeons.ran, "the step that ran is not run again")
        assertTrue(r.director.chain.finished)
        assertTrue(r.log.contains("Special Summon: started again by its switch"), r.log.toString())
        assertTrue(r.log.contains("chain: summon started again by its switch, once more in this run -- next: summon"),
                   r.log.toString())

        r.director.restart("summon", false)
        r.run(10.0)
        assertEquals(1, r.summons.ran, "nothing of its day was left to start again")
    }

    /**
     * The semi-automatic mode: a task that ended its visit at its day's
     * limit -- the tower at its highest floor -- is "has worked this
     * lost_sector; leaving it to you" until the player leaves the page. Its
     * switch off and on gives the page back to it while the page stands.
     * Another row's switch, or this one's where no day held it, leaves the
     * visit as it was.
     */
    @Test
    fun `semi-automatic, a task its day held back works its screen again after its switch`() {
        val r = Rig()
        r.tower.outcome = Outcome.retired("the tower is at its highest floor until 2026-10-03 08:00")
        r.cap.scene = { PaintLostSector.page() }
        r.run(4.0)
        assertEquals(1, r.tower.worked)
        assertTrue(r.run(6.0).note.contains("Lost Sector Tower has worked this lost_sector; leaving it to you"))

        r.director.restart("dungeon", false)
        r.director.restart("lost_sector", false)
        assertTrue(r.run(6.0).note.contains("leaving it to you"))
        assertEquals(1, r.tower.worked)

        r.director.restart("lost_sector", true)
        r.run(4.0)
        assertEquals(2, r.tower.worked, r.log.toString())
        assertTrue(r.log.contains("Lost Sector Tower: started again by its switch -- the lost_sector is its own again"),
                   r.log.toString())
        assertNull(r.director.parked)
    }

    /**
     * A park that is the task's outcome, standing because of its day, goes
     * with the task's switch, and the task works the screen again. A park of
     * another task, or this task's switch where no day held it, leaves the
     * park standing, as before: it waits for the screen to change or "Try
     * again".
     */
    @Test
    fun `a park of the task's day goes with its switch, and no other park does`() {
        val r = Rig()
        r.tower.outcome = Outcome.parked("the tower is at its highest floor until 2026-10-03 08:00")
        r.cap.scene = { PaintLostSector.page() }
        r.run(4.0)
        assertEquals(1, r.tower.worked)
        val why = "Lost Sector Tower parked: the tower is at its highest floor until 2026-10-03 08:00"
        assertEquals(why, r.director.parked)

        r.director.restart("summon", true)
        r.director.restart("lost_sector", false)
        r.run(4.0)
        assertEquals(why, r.director.parked)
        assertEquals(1, r.tower.worked)

        r.tower.outcome = Outcome.DONE
        r.director.restart("lost_sector", true)
        r.run(4.0)
        assertNull(r.director.parked, r.log.toString())
        assertEquals(2, r.tower.worked)
        assertTrue(r.log.contains("  $why: lifted -- Lost Sector Tower was started again by its switch"),
                   r.log.toString())
    }

    /**
     * What the switch must not do: a task its day never held back is left
     * as it is -- a Dungeons step that ran is not run again, which would
     * spend its tickets a second time. Its skill is told all the same, and
     * so is a round's (the quest loop forgets its own ending,
     * QuestSkill.restarted).
     */
    @Test
    fun `a task its day never held back stays as it is after its switch`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.cap.scene = { Paint.mainScreen() }
        r.run(10.0)
        assertTrue(r.director.chain.finished)
        assertEquals(1, r.dungeons.ran)
        r.director.restart("dungeon", false)
        r.director.restart("quest", false)
        r.run(10.0)
        assertEquals(1, r.dungeons.ran, r.log.toString())
        assertEquals(1, r.summons.ran)
        assertEquals(1, r.dungeons.restarts)
        assertEquals(1, r.quest.restarts)
        assertTrue(r.log.none { it.contains("started again") }, r.log.toString())
    }

    /**
     * A restart asked while the task itself is at work breaks nothing: the
     * run goes on to its end, its outcome is the chain's as ever, and the
     * next step follows.
     */
    @Test
    fun `a restart while the task runs breaks nothing`() {
        val r = Rig(SkillSettings.MODE_FULL, listOf("dungeon", "summon"))
        r.dungeons.onRun = { r.director.restart("dungeon", false) }
        r.cap.scene = { Paint.mainScreen() }
        val first = r.tick()
        assertEquals("run dungeon", first.did, first.toString())
        assertEquals("Dungeons: done", first.note)
        r.run(10.0)
        assertEquals(1, r.dungeons.ran, r.log.toString())
        assertEquals(1, r.summons.ran)
        assertTrue(r.director.chain.finished)
        assertNull(r.director.parked)
    }

    private companion object {
        const val GAME = "com.bandainamcoent.dgup_ww"
        /** This app's own page. */
        const val APP = "io.github.digipr1me.digiautotap"
    }
}
