package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * test_quest_flow.py in Kotlin: the same script, the same painted frames
 * (PLAN_ANDROID_APP.md 4, session L).
 *
 * Two halves, as the Python file has. The painted half puts a real counter
 * row on a real frame and asks the real reader, [PaintQuest]; the scripted
 * half replaces the reader on [QuestSkill]'s own seams -- as the Python file
 * monkeypatches `Q._quest_row`, `D.auto_button`, `D.stage_failed` and
 * `Q.close_x` -- and drives the state machine, the resync rule and the two
 * steps it plays.
 *
 * **One difference from the Python cases, and it is not a port mistake.**
 * Python's `F()` hands the loop `name_len=None`, "a card whose word was not
 * counted", and `_matches` then falls back to the target alone. Kotlin has
 * no such reading: `Quest.questRow` answers `nameLen = name.size`, and zero
 * is a measurement rather than the absence of one -- the hologram card
 * carries no word in front of its number at all (Quest.kt). So every frame
 * here carries the word pair its card really has. The one place a null pair
 * survives is [QuestSkill.otherCandidate] called without one, which is what
 * a stalled step with nothing counted still does, and it has its own case.
 */
class QuestSkillTest {

    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    // ------------------------------------------------------------------
    // The rig
    // ------------------------------------------------------------------
    /**
     * One frame of the script, as test_quest_flow.F: what the readers say
     * about it, rather than pixels. The pictures have cases of their own
     * below and in [QuestOracleTest].
     */
    class Frame(
        val progress: Pair<Int, Int>? = null,
        val clear: Boolean = true,
        val failed: Boolean = false,
        val nameLen: Int = 0,
        val afterLen: Int = 0,
        val x: Quest.CloseX? = null,
        /**
         * Which mode banner Special Summon is showing, or null for any other
         * screen. The summon step's whole way in is the quest card and
         * `Summon.modeDots` saying it landed, so a frame has to be able to
         * say so (QuestSkill.modeDots).
         */
        val dots: Int? = null,
        /** The Reward sheet, "Tap to close", in front (QuestSkill.rewardSheet). */
        val sheet: Boolean = false,
    )

    /** test_quest_flow.Screen: frames in, taps out. */
    class Screen {
        val frames = ArrayDeque<Frame>()
        var last: Frame = Frame()
        val taps = ArrayList<Pair<Double, Double>>()

        fun push(vararg f: Frame): Screen {
            frames.addAll(f)
            return this
        }

        fun push(n: Int, f: () -> Frame): Screen {
            repeat(n) { frames.addLast(f()) }
            return this
        }

        fun next(): Frame {
            if (frames.isNotEmpty()) last = frames.removeFirst()
            return last
        }
    }

    /**
     * A clock that actually advances, so a wait whose predicate never comes
     * true still hits its deadline instead of looping for ever -- a wrong
     * frame sequence should fail loudly, not hang the suite. [jump] is the
     * Python file's `Hand`: a park is an hour of ticks, and what those cases
     * are about is which round the loop gives up on, not how long it took.
     */
    class Clock {
        var t = 0.0
        fun read(): Double {
            t += 0.5
            return t
        }

        fun jump(seconds: Double) {
            t += seconds
        }
    }

    /** What the fake Dungeons skill was built with and what it did. */
    class FakeDungeon(val budget: Dungeon.Budget?) : QuestSkill.DungeonRun {
        var ran = false
        override fun run(): Outcome {
            ran = true
            return Outcome.DONE
        }

        override val counted: Map<Pair<String, Int>, Dungeon.Budget> =
            if (budget != null) mapOf(("top" to 1) to budget) else emptyMap()
    }

    /** test_quest_flow.FakeSummonBot. */
    class FakeSummon(val events: MutableList<String>, var drew: Int = 0,
                     var adsToWatch: Int = 0, val parkOnAd: String? = null) : QuestSkill.SummonRun {
        override fun watchAds(mode: Int) { events += "ads"; adsWatched += adsToWatch; adPark = parkOnAd }
        override var adPark: String? = null
        override fun spam(mode: Int): Int { events += "spam"; return drew }
        override fun leaveSummons() { events += "leave" }
        override var adsWatched: Int = 0
    }

    /**
     * The loop over a scripted screen. Every reader is a seam, as the Python
     * file patches every reader as a module function; the picture each of
     * them would have had is painted and read for real further down.
     */
    /**
     * The settings file and the wall clock, which is where the day's lock
     * lives: what the loop writes through `lock` is what it reads back
     * through `settings`, as the app's file does it, and [t] is epoch
     * seconds that only move when a case moves them -- 2026-09-22 14:07 in
     * Vienna to begin with, the afternoon the rule was asked for.
     */
    class Wall(var settings: QuestSkill.Settings) {
        var t: Double = at(2026, 9, 22, 14, 7)
        val locked = ArrayList<Pair<Double, String>>()

        fun lock(until: Double, why: String) {
            locked += until to why
            settings = settings.copy(lockedUntil = until)
        }
    }

    class Loop(
        val screen: Screen,
        val clock: Clock = Clock(),
        set: QuestSkill.Settings = QuestSkill.Settings(),
        val said: MutableList<String> = ArrayList(),
        val kept: MutableList<String> = ArrayList(),
        val wall: Wall = Wall(set),
        /** The director's look for the other rounds between two of the step's ([QuestSkill]'s `aside`). */
        aside: () -> Unit = {},
    ) : QuestSkill(DirectorTest.FakeCapture(), { wall.settings },
                   lock = { until, why -> wall.lock(until, why) },
                   log = { said += it }, keep = { _, tag -> kept += tag },
                   sleep = {}, now = { clock.read() }, clock = { wall.t }, aside = aside) {

        var dungeon: FakeDungeon = FakeDungeon(Dungeon.Budget(2, null, null))
        var summon: FakeSummon = FakeSummon(ArrayList())

        /** The one Mat the script hands out; the seams answer from [Screen.last]. */
        private val frame: Mat = PaintQuest.blank()

        /**
         * Pop the next scripted frame and hand out the Mat that stands for it.
         *
         * A fresh copy every time, because the loop releases what `grab`
         * handed it -- as it must: a real capture answers with a new picture
         * or an error, never the last good one again (Capture.grab).
         */
        fun look(): Mat {
            screen.next()
            return Paint.copy(frame)
        }

        fun tick() = tick(look())

        override fun grab(): Mat = look()
        override fun tap(img: Mat, fx: Double, fy: Double, anchor: Dungeon.Anchor) { screen.taps.add(fx to fy) }

        override fun questCard(img: Mat): Quest.Card? {
            val p = screen.last.progress ?: return null
            return Quest.Card(fx = Quest.CARD_MID_FX, fy = 0.601, ist = p.first, ziel = p.second,
                              rowFx = 0.78, rowFy = 0.59,
                              nameLen = screen.last.nameLen, nameW = 5.6,
                              afterLen = screen.last.afterLen)
        }

        override fun autoButton(img: Mat): Dungeon.Button? =
            if (screen.last.clear) Dungeon.Button(0.36, 0.77, 0.05, 0.05) else null

        override fun stageFailed(img: Mat): Boolean = screen.last.failed
        override fun closeX(img: Mat): Quest.CloseX? = screen.last.x
        override fun rewardSheet(img: Mat): Boolean = screen.last.sheet
        override fun modeDots(img: Mat): Int? = screen.last.dots

        /** What each dungeon step asked for: the card and the tickets it still wanted. */
        val dungeonAsked = ArrayList<Pair<Int, Int>>()

        /** A dungeon step of the case's own making, where [dungeon] will not do (the pause's case). */
        var dungeonRun: DungeonRun? = null

        override fun dungeonFor(arg: Int, needed: Int): DungeonRun {
            dungeonAsked += arg to needed
            return dungeonRun ?: dungeon
        }
        override fun summonBot(): SummonRun = summon
    }

    /**
     * The taps a round sent, named the way the Python cases name them. The
     * aim is what says which tap it was: Kotlin's `tap` carries no `was=`,
     * so the loop's own fractions are the name.
     */
    private fun cardTaps(s: Screen) = s.taps.filter { it.first == Quest.CARD_MID_FX }
    private fun deadTaps(s: Screen) = s.taps.filter { it.first == QuestSkill.DEAD_TAP[0] }
    private fun xTaps(s: Screen) = s.taps.filter { it.first == X_HERE.fx }

    private val X_HERE = Quest.CloseX(0.793, 0.957, 0.8, 0.1, "the white X")

    /** A frame of the plain main screen with the given counter on it. */
    private fun f(ist: Int, ziel: Int, name: Int = 7, after: Int = 0, dots: Int? = null) =
        Frame(progress = ist to ziel, nameLen = name, afterLen = after, dots = dots)

    /** Bakemon is step 2 of the routine: 7 characters in front, none behind. */
    private fun bakemon(ist: Int, ziel: Int = 2) = f(ist, ziel, name = 7)

    /** DemiDevimon is step 1: eleven characters. */
    private fun demidevimon(ist: Int, ziel: Int = 2) = f(ist, ziel, name = 11)

    /**
     * Step 5, "Draw 30 Skill Card Summons": four words in front, five
     * behind -- and the card leads in, which since 2026-09-22 is a thing a
     * frame has to say: the dots read [SummonSkill.SKILL], the game's own
     * answer that the tap landed on that mode's screen (QuestSkill.modeDots).
     */
    private fun summonCard(ist: Int = 1) =
        f(ist, 30, name = 4, after = 5, dots = SummonSkill.SKILL)

    // ==================================================================
    // 1. The painted card: the real reader on a real frame
    // ==================================================================
    @Test
    fun `the painted counter reads the way it looks`() {
        assertNull(Quest.questProgress(PaintQuest.autoButton(PaintQuest.blank())),
                   "no card on the screen -> null")

        // 0 and 9 are left out on purpose: OpenCV's own Hershey font draws
        // both in a way this reader's hole count gets wrong once the 4x cubic
        // resize in questRow has been through them -- 0 as 8, 9 as 4. The real
        // "0/2" shape is what the corpus frames read, off the game's own font.
        for ((ist, ziel) in listOf(1 to 2, 12 to 58, 58 to 58, 188 to 188)) {
            val img = PaintQuest.card(ist, ziel)
            assertEquals(ist to ziel, Quest.questProgress(img), "counter $ist/$ziel")
            img.release()
        }
    }

    @Test
    fun `a report badge with no slash beside it is not a counter`() {
        val img = PaintQuest.reportBadge(PaintQuest.autoButton(PaintQuest.blank()), 0.60, 0.51)
        assertNull(Quest.questProgress(img))
        img.release()
    }

    @Test
    fun `characters too small to trust are not a guessed number`() {
        val img = PaintQuest.card(1, 2, scaleMul = 0.15)
        assertNull(Quest.questProgress(img))
        img.release()
    }

    @Test
    fun `the card's own tap target does not move with the quest name`() {
        val short = Quest.questCard(PaintQuest.card(1, 2, name = "Bakemon"))
        val long = Quest.questCard(PaintQuest.card(1, 2, name = "DemiDevimon"))
        assertNotNull(short); assertNotNull(long)
        assertEquals(Quest.CARD_MID_FX, short.fx)
        assertEquals(Quest.CARD_MID_FX, long.fx)
    }

    /**
     * Not read, counted -- see Quest.NAME_H_MIN. What has to hold is that the
     * two quests sharing a target of 2 never come out as each other's number:
     * seven characters against eleven, with a tolerance of two.
     */
    @Test
    fun `the word in front of the number is counted, not read`() {
        val short = Quest.questCard(PaintQuest.card(1, 2, name = "Bakemon"))
        val long = Quest.questCard(PaintQuest.card(1, 2, name = "DemiDevimon"))
        assertNotNull(short); assertNotNull(long)
        assertEquals(7, short.nameLen, "Bakemon counts as seven characters")
        assertEquals(11, long.nameLen, "DemiDevimon counts as eleven, dots over the i's and all")

        val probe = Loop(Screen())
        val shortW = short.nameLen to short.afterLen
        val longW = long.nameLen to long.afterLen
        assertTrue(probe.matches(1, 2, shortW) && !probe.matches(0, 2, shortW),
                   "a counted Bakemon fits the Bakemon step and not the other")
        assertTrue(probe.matches(0, 2, longW) && !probe.matches(1, 2, longW),
                   "a counted DemiDevimon fits its own step and not the other")
    }

    @Test
    fun `the two summon steps are told apart by the word behind the number`() {
        val probe = Loop(Screen())
        val skill = 4 to 5
        val support = 4 to 0
        assertTrue(probe.matches(4, 30, skill) && !probe.matches(5, 30, skill))
        assertTrue(probe.matches(5, 30, support) && !probe.matches(4, 30, support))
    }

    @Test
    fun `the stage step is matched on its words, whatever number it shows`() {
        val probe = Loop(Screen())
        assertTrue(probe.matches(8, 605, 0 to 0))
        assertTrue(probe.matches(8, 7308, 0 to 0))
        assertFalse(probe.matches(8, 605, 6 to 0))
        // ...but never a target another step already owns
        assertFalse(probe.matches(8, 50, 0 to 0))
    }

    @Test
    fun `a counter with no word in front of it counts none, not one`() {
        val got = Quest.questCard(PaintQuest.card(1, 2, name = ""))
        assertNotNull(got)
        assertEquals(0, got.nameLen)
    }

    /**
     * Half the routine's cards carry a word behind the number, and two of
     * them carry nothing else. The letters must be counted, never read as
     * digits -- an ascender is exactly as tall as a digit, and only the space
     * in front of it says where the number ended.
     */
    @Test
    fun `a word behind the number is not read into it`() {
        val got = Quest.questCard(PaintQuest.card(1, 55, name = "", after = "times"))
        assertNotNull(got)
        assertEquals(1 to 55, got.ist to got.ziel)
        assertEquals(5, got.afterLen, "...and is counted instead")
        assertEquals(0, got.nameLen, "...with nothing counted in front of it")
    }

    @Test
    fun `a word on each side leaves the number just the number`() {
        val got = Quest.questCard(PaintQuest.card(1, 33, name = "Draw", after = "Skill"))
        assertNotNull(got)
        assertEquals(1 to 33, got.ist to got.ziel)
        assertEquals(4 to 5, got.nameLen to got.afterLen)
    }

    /**
     * The letter that broke this: "Defeat" ends in an ascender, exactly as
     * tall as the digit beside it, and it was read as one -- 19 of 28 real
     * cards came back with a number that had a letter in it.
     */
    @Test
    fun `an ascender at the end of the word stays out of the number`() {
        val got = Quest.questCard(PaintQuest.card(12, 55, name = "Defeat"))
        assertNotNull(got)
        assertEquals(12 to 55, got.ist to got.ziel)
    }

    /**
     * The game redraws the ist digit green the moment the quest is done. Read
     * only in white and red, that card has nothing at all left of its slash:
     * it went unreadable at exactly the moment it was worth tapping.
     */
    @Test
    fun `a finished card's green ist digit is read like the red one`() {
        val done = PaintQuest.card(2, 2, istColour = PaintQuest.GREEN)
        assertEquals(2 to 2, Quest.questProgress(done))
        assertTrue(Quest.questClaimable(done), "...and the card then reads as claimable")
        val running = PaintQuest.card(1, 2)
        assertFalse(Quest.questClaimable(running), "an unfinished card is not claimable")
    }

    // ==================================================================
    // 2. The routine and its resync
    // ==================================================================
    @Test
    fun `15 steps in the routine`() {
        assertEquals(15, QuestSkill.ROUTINE.size)
    }

    @Test
    fun `the forward search wraps and a counted name narrows it`() {
        val loop = Loop(Screen())
        loop.tick(loop.look())            // step 0, nothing on the frame
        // Forward from the current step, wrapping -- so from step 0 the other
        // target-2 step (1) is found before circling back to 0 itself.
        assertEquals(listOf(1, 0), loop.forwardCandidates(2, null))
        assertEquals(listOf(1), loop.forwardCandidates(2, 7 to 0),
                     "a counted name narrows the forward search to one step")
        assertEquals(listOf(0), loop.forwardCandidates(2, 11 to 0),
                     "a name matching the step we are on still finds it, further on")
        assertEquals(emptyList<Int>(), loop.forwardCandidates(2, 3 to 0),
                     "a name matching neither dungeon leaves no candidate at all")
    }

    @Test
    fun `the other candidate for a stalled step is a different step of the same kind`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 4))
        screen.push(f(1, 30, name = 4, after = 5))
        loop.tick()
        val alt = loop.otherCandidate(30)
        assertTrue(alt != null && alt != 4, "other candidate $alt")

        val screen2 = Screen()
        val loop2 = Loop(screen2, set = QuestSkill.Settings(step = 1))
        screen2.push(bakemon(0))
        loop2.tick()
        assertNull(loop2.otherCandidate(2, 7 to 0),
                   "a stalled Bakemon step is not retried as DemiDevimon once the card named it")
        assertEquals(0, loop2.otherCandidate(2, null),
                     "...but is, while nobody has counted the word")
    }

    // ==================================================================
    // 3. The state machine
    // ==================================================================
    @Test
    fun `no click when the counter cannot be read`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(Frame(progress = null))
        loop.tick()
        assertTrue(screen.taps.isEmpty())
    }

    @Test
    fun `no click while the quest is not yet done`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(demidevimon(1))
        loop.tick()
        assertTrue(cardTaps(screen).isEmpty())
    }

    /**
     * Exactly the four looks a claim makes when every wait settles on its
     * first: the initial read, the open-window check (not clear), the
     * close-window check (clear again), and the progress re-read after
     * closing.
     */
    private fun claimFrames(next: Pair<Int, Int>?, ist: Int = 2, ziel: Int = 2,
                            name: Int = 11) = arrayOf(
        f(ist, ziel, name = name),
        Frame(progress = null, clear = false),
        Frame(progress = null, clear = true),
        if (next == null) Frame(progress = null)
        else f(next.first, next.second, name = name))

    @Test
    fun `the ist side falling advances the step`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(*claimFrames(0 to 2))
        loop.tick()
        assertTrue(cardTaps(screen).isNotEmpty(), "claiming taps the card")
        assertTrue(deadTaps(screen).isNotEmpty(), "claiming closes the reward at the dead spot")
        assertEquals(1, loop.step, "the ist side falling advances the step")
    }

    /**
     * The live failure: four reward windows closed on the first tap and the
     * fifth swallowed two. Waiting longer buys nothing -- a window that
     * closes does it within a second -- so the answer is more taps.
     */
    @Test
    fun `a window that swallows a tap gets another one`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(f(2, 2, name = 11))
        screen.push(8) { Frame(progress = null, clear = false) }
        screen.push(Frame(progress = null, clear = true), f(0, 2, name = 11))
        loop.tick()
        assertTrue(deadTaps(screen).size >= 2, "${deadTaps(screen).size} tap(s)")
        assertNull(loop.parkedBecause)
    }

    @Test
    fun `a window that never closes parks, after five taps`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(f(2, 2, name = 11))
        screen.push(80) { Frame(progress = null, clear = false) }
        loop.tick()
        assertEquals(QuestSkill.CLAIM_CLOSE_TAPS, deadTaps(screen).size)
        assertNotNull(loop.parkedBecause)
    }

    /**
     * 100/100 was claimed and the card that came up read 610/610. The ist
     * side did not fall, and judged on that alone the claim looked like a
     * failure -- on a quest that had just been collected.
     */
    @Test
    fun `a claim that hands over to a different target counts as done`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 7))
        screen.push(f(100, 100, name = 6),
                    Frame(progress = null, clear = false),
                    Frame(progress = null, clear = true),
                    f(610, 610, name = 0, after = 0))
        loop.tick()
        assertEquals(8, loop.step)
        assertNull(loop.parkedBecause)
    }

    @Test
    fun `claimed but the counter did not fall retries once, then parks`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(*claimFrames(2 to 2))
        loop.tick()
        assertNull(loop.parkedBecause, "not yet parked the first time")
        assertEquals(0, loop.step, "still on the same step")
        screen.push(*claimFrames(2 to 2))
        loop.tick()
        assertNotNull(loop.parkedBecause, "twice in a row: parked")
    }

    @Test
    fun `a tick taps the card at most once`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(*claimFrames(0 to 2))
        loop.tick()
        assertTrue(cardTaps(screen).size <= 1, "${cardTaps(screen).size} tap(s)")
    }

    // ------------------------------------------------------------------
    // The X out of a screen the loop cannot work on
    // ------------------------------------------------------------------
    private fun stuckLoop(): Pair<Screen, Loop> {
        // Every frame here is a screen with no auto button on it, so a round
        // never reaches a step to play at all -- which is the point: what is
        // being watched is the way out of a screen. The two switches that
        // used to hold the loop back as well have gone (SkillSettings).
        val screen = Screen()
        val loop = Loop(screen)
        return screen to loop
    }

    @Test
    fun `a screen that will not go gets the X tapped, and only then`() {
        val (screen, loop) = stuckLoop()
        repeat(4) {
            screen.push(Frame(progress = null, clear = false, x = X_HERE))
            loop.tick()
            loop.clock.jump(5.0)
        }
        assertTrue(xTaps(screen).isEmpty(), "a screen that has only just appeared is left alone")

        loop.clock.jump(QuestSkill.STUCK_AFTER)
        screen.push(Frame(progress = null, clear = false, x = X_HERE))
        loop.tick()
        assertEquals(1, xTaps(screen).size, "a screen that will not go gets the X tapped")

        screen.push(Frame(progress = null, clear = false, x = X_HERE))
        loop.tick()
        assertEquals(1, xTaps(screen).size, "...but not twice in the same breath")

        loop.clock.jump(QuestSkill.STUCK_TAP_EVERY)
        screen.push(Frame(progress = null, clear = false, x = X_HERE))
        loop.tick()
        assertEquals(2, xTaps(screen).size, "...and again once the gap has passed")

        // The main screen back is the independent check that the tap landed --
        // never another look at the picture that aimed it.
        screen.push(demidevimon(1))
        loop.tick()
        assertTrue(loop.said.any { it.contains("the main screen is back") },
                   "the main screen coming back clears the whole thing: ${loop.said}")
    }

    @Test
    fun `a screen with no X on it is never tapped at`() {
        val (screen, loop) = stuckLoop()
        repeat(6) {
            screen.push(Frame(progress = null, clear = false))
            loop.tick()
            loop.clock.jump(QuestSkill.STUCK_AFTER)
        }
        assertTrue(screen.taps.isEmpty(), "${screen.taps}")
        assertTrue(loop.kept.contains("no-x-to-get-out-with"), "the frame is kept: ${loop.kept}")
    }

    @Test
    fun `an X that changes nothing is given up on, not hammered`() {
        val (screen, loop) = stuckLoop()
        repeat(QuestSkill.STUCK_TAPS_MAX + 4) {
            loop.clock.jump(QuestSkill.STUCK_AFTER)
            screen.push(Frame(progress = null, clear = false, x = X_HERE))
            loop.tick()
        }
        assertEquals(QuestSkill.STUCK_TAPS_MAX, xTaps(screen).size)
        assertTrue(loop.kept.contains("x-would-not-close"), "${loop.kept}")
    }

    /**
     * A Reward sheet nobody here opened -- the phone's over Network Defense
     * Ops on 2026-10-01 -- has no X, and stood for minutes on "no X on it to
     * get out with". It is given the same [QuestSkill.STUCK_AFTER] as any
     * screen, then tapped at the dead spot, one tap a round, the main screen
     * coming back the proof.
     */
    @Test
    fun `a Reward sheet the loop did not open is tapped closed, after the wait and at the dead spot`() {
        val (screen, loop) = stuckLoop()
        repeat(4) {
            screen.push(Frame(progress = null, clear = false, sheet = true))
            loop.tick()
            loop.clock.jump(5.0)
        }
        assertTrue(screen.taps.isEmpty(), "a sheet that has only just appeared is left alone: ${screen.taps}")

        loop.clock.jump(QuestSkill.STUCK_AFTER)
        screen.push(Frame(progress = null, clear = false, sheet = true))
        loop.tick()
        assertEquals(1, deadTaps(screen).size, "${screen.taps}")
        assertEquals(1, screen.taps.size, "the dead spot and nothing else: ${screen.taps}")
        assertTrue(loop.said.none { it.contains("no X on it") }, "${loop.said}")

        screen.push(demidevimon(1))
        loop.tick()
        assertTrue(loop.said.any { it.contains("the main screen is back") }, "${loop.said}")
        assertEquals(0, loop.step, "a sheet the loop did not open claims nothing")
    }

    /** The same sheet that will not go is given up on, as an X is. */
    @Test
    fun `a Reward sheet that will not close is given up on, not hammered`() {
        val (screen, loop) = stuckLoop()
        repeat(QuestSkill.STUCK_TAPS_MAX + 4) {
            loop.clock.jump(QuestSkill.STUCK_AFTER)
            screen.push(Frame(progress = null, clear = false, sheet = true))
            loop.tick()
        }
        assertEquals(QuestSkill.STUCK_TAPS_MAX, deadTaps(screen).size)
        assertTrue(loop.kept.contains("sheet-would-not-close"), "${loop.kept}")
    }

    /**
     * The claim's own window, late: the claim gave up on an unmoved card, and
     * the next round finds the sheet. Closed at once -- no wait, it is the
     * loop's own -- and the claim counted when the card has moved on.
     */
    @Test
    fun `the sheet of a claim that gave up is closed at once and the claim counted`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(*claimFrames(2 to 2))
        loop.tick()
        assertEquals(0, loop.step)
        screen.push(Frame(progress = null, clear = false, sheet = true),
                    Frame(progress = null, clear = true),
                    bakemon(0))
        loop.tick()
        assertEquals(1, loop.step, "${loop.said}")
        assertTrue(loop.did.claimed)
        assertNull(loop.parkedBecause)
    }

    /**
     * The same late window over an unknown finished card: counted when the
     * card has moved on, and the step stays, as an unknown card's claim
     * leaves it ([QuestSkill.claimUnknown]).
     */
    @Test
    fun `a late window over an unknown card is counted and leaves the step where it was`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 2))
        screen.push(f(10, 10, name = 0, after = 5))
        loop.tick()
        screen.push(f(10, 10, name = 0, after = 5), Frame(progress = null, clear = false),
                    Frame(progress = null, clear = true), f(10, 10, name = 0, after = 5))
        loop.tick()
        assertFalse(loop.did.claimed)
        screen.push(Frame(progress = null, clear = false, sheet = true),
                    Frame(progress = null, clear = true), f(15, 15, name = 6))
        loop.tick()
        assertTrue(loop.did.claimed, "${loop.said}")
        assertEquals(2, loop.step, "the step stays: ${loop.said}")
        assertNull(loop.parkedBecause)
    }

    /**
     * And where a frame without the auto button is not the sheet, the
     * claim's late look taps nothing: a blink is not a window.
     */
    @Test
    fun `a claim's late look taps nothing that is not the sheet`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(*claimFrames(2 to 2))
        screen.push(6) { Frame(progress = null, clear = false) }
        loop.tick()
        assertEquals(1, deadTaps(screen).size, "the claim's own tap and no other: ${screen.taps}")
        assertEquals(0, loop.step)
    }

    /**
     * The other half of that, and the half that makes tapping it safe: where
     * the main screen is up, there is no X to find. Measured over all 249
     * stored frames -- not one carries an X and a crisp auto button at once
     * -- and checked here with the real readers on a painted main screen.
     */
    @Test
    fun `no X is found on the plain main screen`() {
        val img = PaintQuest.card(1, 2)
        assertNotNull(Dungeon.autoButton(img))
        assertNull(Quest.closeX(img))
        img.release()
    }

    // ------------------------------------------------------------------
    // Resync
    // ------------------------------------------------------------------
    @Test
    fun `one mismatched frame does not resync, two do`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(f(0, 50, name = 0, after = 5))   // step 0 expects target 2
        loop.tick()
        assertEquals(0, loop.step)
        assertNull(loop.parkedBecause)
        screen.push(f(0, 50, name = 0, after = 5))
        loop.tick()
        assertEquals(50, QuestSkill.ROUTINE[loop.step].target,
                     "two mismatched frames in a row resync to the matching step")
    }

    /**
     * The live failure: the loop came up on step 1 (DemiDevimon), the game
     * was showing Bakemon, both want a target of 2 -- and the old check saw
     * two targets of 2 and played the wrong dungeon.
     */
    @Test
    fun `the card names the quest, the saved step does not`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings())
        screen.push(bakemon(0))
        loop.tick()
        assertEquals(0, loop.step, "one frame naming another quest does not jump yet")
        screen.push(bakemon(0))
        loop.tick()
        assertEquals(1, loop.step, "two frames naming Bakemon move the loop off DemiDevimon")
        assertTrue(screen.taps.isEmpty(), "and nothing was tapped on the way")
    }

    @Test
    fun `a card whose word matches the step stays on it`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings())
        screen.push(demidevimon(0), demidevimon(0))
        loop.tick()
        loop.tick()
        assertEquals(0, loop.step)
    }

    @Test
    fun `a target nothing in the script expects parks the loop`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(f(0, 999, name = 3), f(0, 999, name = 3))
        loop.tick()
        loop.tick()
        assertNotNull(loop.parkedBecause)
    }

    /**
     * notes/director.md, "A finished quest card the routine does not know is
     * claimed, not parked on": a Poco F3's report of 2026-09-30, 10/10 with
     * five characters behind the number and 15/15 with six in front, two
     * passes of the director's ([QuestSkill.work]) each. Claimed on the
     * second frame, never the first, the count is each pass's own, the step
     * does not move, and an unknown card that is not finished parks as
     * before.
     */
    @Test
    fun `a finished card the routine does not know is claimed, pass by pass, not parked on`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 2))
        fun pass(): Map<String, Int> {
            loop.work(loop.look())
            return loop.lastCounts
        }
        // Pass 1: 10/10 "times", twice; the reward window opens and closes, then 15/15.
        screen.push(f(10, 10, name = 0, after = 5))
        assertEquals(0, pass()["claimed"], "one frame is not proof of anything")
        assertTrue(cardTaps(screen).isEmpty())
        screen.push(f(10, 10, name = 0, after = 5), Frame(progress = null, clear = false),
                    Frame(progress = null, clear = true), f(15, 15, name = 6))
        assertEquals(1, pass()["claimed"])
        assertEquals(1, cardTaps(screen).size)
        assertNull(loop.parkedBecause)
        assertEquals(2, loop.step, "the step stays; the next card is matched afresh")
        // Pass 2: 15/15 "Defeat", twice; claimed, and the count is this pass's own.
        screen.push(f(15, 15, name = 6))
        assertEquals(0, pass()["claimed"])
        screen.push(f(15, 15, name = 6), Frame(progress = null, clear = false),
                    Frame(progress = null, clear = true), f(0, 15, name = 6))
        assertEquals(1, pass()["claimed"])
        assertEquals(2, cardTaps(screen).size)
        assertTrue(loop.said.any { "it is finished, so claiming it all the same" in it })
        // 0/15: unknown and not finished -- parked, nothing tapped.
        screen.push(f(0, 15, name = 6), f(0, 15, name = 6))
        pass()
        pass()
        assertEquals("the card and the script disagree", loop.parkedBecause)
        assertEquals(2, cardTaps(screen).size)
    }

    @Test
    fun `an unknown finished card that does not go parks, and is not claimed again at once`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 2))
        screen.push(f(10, 10, name = 0, after = 5), f(10, 10, name = 0, after = 5),
                    Frame(progress = null, clear = false), Frame(progress = null, clear = true),
                    f(10, 10, name = 0, after = 5))
        loop.tick()
        loop.tick()
        assertEquals("an unknown finished card did not go when it was claimed", loop.parkedBecause)
        screen.push(f(10, 10, name = 0, after = 5), f(10, 10, name = 0, after = 5))
        loop.tick()
        loop.tick()
        assertEquals(1, cardTaps(screen).size, "inside the park nothing is claimed again")
    }

    // ------------------------------------------------------------------
    // Park reasons
    // ------------------------------------------------------------------
    /*
     * Three cases stood here and have gone with the switches they were
     * about: "the dungeon-quests switch off parks a dungeon step", "the
     * summon-quests switch off parks a summon step" and "claim-only does
     * not play an unfinished dungeon step". `quest_dungeon`, `quest_summon`
     * and `quest_claim_only` are not settings any more (SkillSettings, the
     * passive page), so there is no way left to ask the loop to play less
     * than the routine, and nothing left to test about it.
     */

    // ------------------------------------------------------------------
    // The hologram step
    // ------------------------------------------------------------------
    /**
     * There was a second test above this one: with `passive_auto` on, the
     * loop left the device alone. That key never had a switch on the phone,
     * so the branch was dead and it has gone with the Auto Spend feature --
     * the hologram step is played from here now, whatever the game's own
     * button is doing.
     */
    @Test
    fun `the hologram device is tapped, not parked on`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 2))
        // The tap goes out, then the counter is watched: one frame that moves
        // it is all the proof there is to have.
        screen.push(f(1, 50, name = 0, after = 5))
        screen.push(8) { f(12, 50, name = 0, after = 5) }
        loop.tick()
        val holo = screen.taps.filter { it.first > 0.4 && it.first < 0.6 }
        assertEquals(1, holo.size, "the device is tapped once: ${screen.taps}")
        assertNull(loop.parkedBecause)
        assertEquals(12 to 50, loop.lastProgress)
    }

    @Test
    fun `two dead taps on the device park the step`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 2))
        repeat(2) {
            screen.push(40) { f(1, 50, name = 0, after = 5) }
            loop.tick()
        }
        assertNotNull(loop.parkedBecause)
    }

    // ------------------------------------------------------------------
    // The card came back late, and that is not a park
    // ------------------------------------------------------------------
    /**
     * 10:23:17 in the live log: the Dungeons skill had just handed the screen
     * back, the game was still closing its own result screen, one frame was
     * read, and the whole run parked on "could not read the quest card after
     * the action" two seconds before the card was readable again -- so the
     * finished quest was never claimed.
     */
    @Test
    fun `a screen still settling after an action is waited out, not parked on`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(demidevimon(0))
        loop.tick()          // reads the card, then plays the dungeon
        assertTrue(loop.dungeon.ran, "the dungeon was played")
    }

    @Test
    fun `an empty dungeon parks on the tickets, not on the counter`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings())
        loop.dungeon = FakeDungeon(Dungeon.Budget(0, 0, 0))
        screen.push(20) { demidevimon(1) }
        loop.tick()
        assertTrue(loop.parkedBecause?.contains("out of tickets") == true,
                   "${loop.parkedBecause}")
        assertTrue(loop.said.any { it.contains("0 tickets and no ads") },
                   "...and says the number it read: ${loop.said}")
        assertEquals(0, loop.step, "...and does not go and play the other dungeon on top of it")
    }

    /**
     * The free ads the loop's to take ([QuestSkill.Settings.dungeonAds]: the
     * Dungeons pick with the Ad Skip Pass): a card at 0 tickets with two ads
     * left is not the day being over, and the bot it is handed to taps the
     * film button.
     */
    @Test
    fun `with the free ads taken a card with ads left is still played`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(dungeonAds = true))
        loop.dungeon = FakeDungeon(Dungeon.Budget(0, 2, 2))
        screen.push(20) { demidevimon(1) }
        loop.tick()
        assertFalse(loop.parkedBecause?.contains("out of tickets") == true, "${loop.parkedBecause}")
        assertTrue(loop.dungeon.ran, "the card was handed to the Dungeons bot")
        assertTrue(loop.dungeonSettings(1, 1).useAds, "the film button is tapped")
    }

    /**
     * The Dungeons page's ad switch, off (the player, 2026-09-30: the Quest
     * Loop follows it): the card's two ads are not the loop's to spend, so a
     * card at 0 tickets is the day being over, ads left or not, and the bot
     * it hands a card to never taps the film button.
     */
    @Test
    fun `with the Dungeons ad switch off a card at 0 tickets is the day being over`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(dungeonAds = false))
        loop.dungeon = FakeDungeon(Dungeon.Budget(0, 2, 2))
        screen.push(20) { demidevimon(1) }
        loop.tick()
        assertTrue(loop.parkedBecause?.contains("out of tickets until tomorrow (ads switched off)") == true,
                   "${loop.parkedBecause}")
        assertTrue(loop.said.any { it.contains("its free ads are not taken (the Ad Rewards card)") }, "${loop.said}")
        assertFalse(loop.dungeonSettings(1, 1).useAds, "the film button is not tapped")
        assertTrue(Loop(Screen(), set = QuestSkill.Settings(dungeonAds = true)).dungeonSettings(1, 1).useAds,
                   "...where with the switch on it is")
    }

    /** Off, the tickets alone are a counted answer: 1 ticket for a quest that wants 2 cannot be finished today. */
    @Test
    fun `with the Dungeons ad switch off the tickets alone say whether a quest can be finished`() {
        val loop = Loop(Screen(), set = QuestSkill.Settings(dungeonAds = false))
        assertNotNull(loop.dungeonShort(FakeDungeon(Dungeon.Budget(1, null, null)), 2))
        assertNull(loop.dungeonShort(FakeDungeon(Dungeon.Budget(2, null, null)), 2))
        val on = Loop(Screen(), set = QuestSkill.Settings(dungeonAds = true))
        assertNull(on.dungeonShort(FakeDungeon(Dungeon.Budget(1, null, null)), 2),
                   "with ads on, a card with tickets left says nothing about its ads")
    }

    @Test
    fun `a card with attempts left still parks on the counter`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 1))
        loop.dungeon = FakeDungeon(Dungeon.Budget(2, 1, 3))
        // Both words counted, so the card itself rules out the other dungeon
        // step and the retry is not what parks this.
        screen.push(20) { bakemon(1) }
        loop.tick()
        assertTrue(loop.parkedBecause?.contains("did not move the counter") == true,
                   "${loop.parkedBecause}")
        assertTrue(loop.said.any { it.contains("2 tickets and 1 ad") },
                   "...and says what it had to play with: ${loop.said}")
    }

    /**
     * The player's own case: a dungeon quest wants two runs, the card has one
     * attempt left, and the fixed order of the routine means nothing behind
     * that step can be reached either. Nothing hands out an attempt before
     * the game's daily reset.
     */
    @Test
    fun `one attempt against a quest that wants two switches the loop off`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 1))
        loop.dungeon = FakeDungeon(Dungeon.Budget(1, 0, 1))
        screen.push(20) { bakemon(0) }
        val outcome = loop.work(loop.look())
        assertNotNull(loop.stoppedBecause)
        assertTrue(loop.stoppedUntilReset, "...and says it lasts until the daily reset")
        assertEquals(Result.RETIRED, outcome.result,
                     "...which is what the director retires the chain step on")
        assertTrue(loop.stoppedBecause!!.contains("1 ticket left") &&
                       loop.stoppedBecause!!.contains("wants 2"),
                   "...and says both numbers: ${loop.stoppedBecause}")
    }

    /**
     * Two pauses in one dungeon step (PLAN_RELEASE_1_3.md B4): the quest's
     * dungeon is stopped by the switch, and stopped again as it goes on. The
     * round stops with it, the game on the dungeon's screens; each time it
     * goes on the same dungeon pass goes on ([QuestSkill.DungeonRun.resume]) --
     * not a second one built afresh with the quest's whole want -- and once it
     * is done the card is read as after any action.
     */
    @Test
    fun `the quest loop's dungeon step paused twice goes on with the same dungeon pass`() {
        class Pausing : QuestSkill.DungeonRun {
            val outcomes = ArrayDeque(listOf(Outcome.STOPPED, Outcome.STOPPED, Outcome.DONE))
            var runs = 0
            var resumes = 0
            override fun run(): Outcome { runs += 1; return outcomes.removeFirst() }
            override fun resume(img: Mat): Outcome { resumes += 1; return outcomes.removeFirst() }
            override fun resumesOn(screen: String, img: Mat): Boolean = screen == Director.DUNGEON_LIST
            override val counted: Map<Pair<String, Int>, Dungeon.Budget> = mapOf(("top" to 1) to Dungeon.Budget(2, null, null))
        }
        // The round the director calls is `work`: it hands the stop back.
        val loop2 = Loop(Screen().push(bakemon(0)), set = QuestSkill.Settings(step = 1))
        val d2 = Pausing()
        loop2.dungeonRun = d2
        assertEquals(Result.STOPPED, loop2.work(loop2.look()).result, loop2.said.toString())
        assertTrue(loop2.resumesOn(Director.DUNGEON_LIST, loop2.look()))
        assertTrue(loop2.resumesOn(Director.MAIN, loop2.look()), "the loop lives on the main screen")
        assertFalse(loop2.resumesOn(Director.SUMMON, loop2.look()), "not the summon step's screen")
        assertEquals(Result.STOPPED, loop2.resume(loop2.look(), whole = true).result, loop2.said.toString())
        loop2.screen.push(20) { bakemon(2) }
        val done = loop2.resume(loop2.look(), whole = true)
        assertTrue(done.result != Result.STOPPED, "$done ${loop2.said}")
        assertEquals(1, d2.runs, "one dungeon pass, built once")
        assertEquals(2, d2.resumes, "gone on with twice")
        assertEquals(1, loop2.dungeonAsked.size, "no second dungeon for the same step: ${loop2.dungeonAsked}")
        assertTrue(loop2.said.any { it.contains("progress: 0/2 -> 2/2") }, loop2.said.toString())
        assertFalse(loop2.resumesOn(Director.DUNGEON_LIST, loop2.look()), "nothing left to go on with")
    }

    /**
     * The quest's dungeon is handed the tickets the quest still wants, not a
     * fixed two, and the quest's own attempts before Clear Previous
     * Difficulty (2026-09-23): a quest at 1/2 wants one ticket, and a quest
     * whose runs are lost is to reach its clears through the violet button.
     */
    @Test
    fun `the quest's dungeon gets the tickets it still wants and the quest's own N`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 1))
        loop.dungeon = FakeDungeon(Dungeon.Budget(2, null, null))
        screen.push(20) { bakemon(1) }
        loop.tick()
        assertEquals(listOf(QuestSkill.ROUTINE[1].arg!! to 1), loop.dungeonAsked,
                     "one ticket for a quest at 1/2")

        val set = Loop(Screen(), set = QuestSkill.Settings(attemptsBeforeClear = 7))
            .dungeonSettings(2, 1)
        assertEquals(mapOf(2 to 1), set.budgets)
        assertEquals(7, set.attemptsBeforeClear)
        assertFalse(set.countLostOnly,
                    "the quest's N counts every attempt; the Dungeons page's the failed ones (2026-10-04)")
        assertTrue(set.survey, "the survey stays on: dungeonShort reads it")
        assertEquals(mapOf(2 to 1), Loop(Screen()).dungeonSettings(2, 0).budgets,
                     "never a budget of 0, which the skill reads as 'do not play'")
        assertEquals(QuestSkill.ATTEMPTS_BEFORE_CLEAR,
                     Loop(Screen()).dungeonSettings(2, 2).attemptsBeforeClear)
    }

    /**
     * The quest loop stays as it is (PLAN_DAILY_LOST_SECTOR_PRESETS.md,
     * question 12): the Dungeons bot it builds for a quest plays no daily
     * dungeon, whatever the Dungeons page gives it.
     */
    @Test
    fun `the quest's dungeon bot gets no minutes for the daily dungeon`() {
        assertEquals(0, Loop(Screen()).dungeonSettings(2, 1).dailyMinutes)
    }

    @Test
    fun `the last run of a quest is not short of one`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 1))
        loop.dungeon = FakeDungeon(Dungeon.Budget(1, 0, 1))
        screen.push(20) { bakemon(1) }
        loop.tick()
        assertNull(loop.stoppedBecause)
    }

    @Test
    fun `a card whose ads are still unknown never switches it off`() {
        // The film symbol is only drawn at 0 tickets, so the card says nothing
        // about how many attempts are left, and a loop that stopped on that
        // would stop on a quest it could still finish.
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings())
        loop.dungeon = FakeDungeon(Dungeon.Budget(1, null, null))
        screen.push(20) { demidevimon(0) }
        loop.tick()
        assertNull(loop.stoppedBecause)
    }

    @Test
    fun `a quest finished after the action is not switched off over`() {
        val screen = Screen()
        val loop = Loop(screen)
        loop.dungeon = FakeDungeon(Dungeon.Budget(0, 0, 0))
        screen.push(demidevimon(0))
        screen.push(20) { demidevimon(2) }
        loop.tick()
        assertNull(loop.stoppedBecause)
    }

    @Test
    fun `an empty card switches the loop off, and then nothing happens`() {
        val screen = Screen()
        val loop = Loop(screen)
        loop.dungeon = FakeDungeon(Dungeon.Budget(0, 0, 0))
        screen.push(20) { demidevimon(0) }
        loop.tick()
        assertNotNull(loop.stoppedBecause)
        val before = screen.taps.size
        screen.push(20) { demidevimon(0) }
        loop.tick()
        assertEquals(before, screen.taps.size, "...and then does nothing on the rounds after it")
        loop.resume()
        assertNull(loop.stoppedBecause, "...until the switch is ticked again by hand")
        assertNull(loop.parkedBecause)
    }

    // ------------------------------------------------------------------
    // The summon step
    // ------------------------------------------------------------------
    /**
     * The live failure: both numbers this step needs are drawn inside Special
     * Summon, they were asked of the main screen, they answered null there,
     * and the step parked on "not enough tickets" with a full stock and the
     * menu never opened.
     */
    @Test
    fun `the summon step opens the menu before it counts anything`() {
        val events = ArrayList<String>()
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 4))
        loop.summon = FakeSummon(events)
        screen.push(20) { summonCard(0) }
        loop.tick()
        assertTrue(cardTaps(screen).isNotEmpty(), "the card was tapped: ${screen.taps}")
        assertTrue(events.contains("ads"), "and the step got as far as looking: $events")
        assertTrue(loop.parkedBecause?.contains("nothing to draw with") == true,
                   "nothing to draw with, after looking, parks: ${loop.parkedBecause}")
        assertTrue(events.contains("leave"), "and the menu is left again either way: $events")
    }

    /**
     * The Summon page's ad switch, off: the bot the step builds watches no ad
     * (its watchAds returns at once), and a step with nothing to draw says
     * why it did not look for ads.
     */
    @Test
    fun `with the Summon ad switch off the step watches no ad and says so when it parks`() {
        val events = ArrayList<String>()
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 4, summonAds = false))
        loop.summon = FakeSummon(events)
        screen.push(20) { summonCard(0) }
        loop.tick()
        assertFalse(loop.summonSettings().watchAdsFirst, "the bot is built without the ad phase")
        assertTrue(Loop(Screen(), set = QuestSkill.Settings(summonAds = true)).summonSettings().watchAdsFirst)
        assertTrue(loop.parkedBecause?.contains("the free ads not taken in Summon (the Ad Rewards card)") == true,
                   "${loop.parkedBecause}")
    }

    /** The file a phone can carry into 1.3: both picks on, the pass as [pass], and `ad_watch` on, which nothing reads. */
    private fun adFile(pass: Boolean) = MapSettings(mapOf(
        SkillSettings.AD_PASS_KEY to pass, "ad_watch" to true,
        Stored.DUNGEON_ADS_KEY to true, Stored.SUMMON_ADS_KEY to true))

    /**
     * The Quest Loop's two ways to a free ad are the bots it builds, with
     * what [Stored.quest] reads (2026-10-03, PLAN_ABSCHLUSS_1_3.md 3.6).
     * Without the pass -- both picks on, a supporter code, and `ad_watch` on
     * in the file -- neither bot taps a film button or View Ads, and a card
     * at 0 tickets is the day being over, over two passes; with the pass the
     * tap is everybody's. DungeonSkillTest and SummonSkillTest play each
     * bot through on these settings.
     */
    @Test
    fun `without the pass the quest loop taps no free ad, with a code and ad_watch in the file, over two passes`() {
        val none = Stored.quest(adFile(pass = false))
        val screen = Screen()
        val loop = Loop(screen, set = none)
        loop.dungeon = FakeDungeon(Dungeon.Budget(0, 2, 2))
        screen.push(40) { demidevimon(1) }
        loop.tick()
        loop.tick()
        assertTrue(loop.parkedBecause?.contains("out of tickets until tomorrow (ads switched off)") == true,
                   "${loop.parkedBecause}")
        // The card is handed to the bot all the same -- its survey is what
        // reads the counters -- and the bot is built with no film button.
        assertFalse(loop.dungeonSettings(1, 1).useAds, "no film button")
        assertFalse(loop.summonSettings().watchAdsFirst, "no View Ads")

        val pass = Loop(Screen(), set = Stored.quest(adFile(pass = true)))
        assertTrue(pass.dungeonSettings(1, 1).useAds)
        assertTrue(pass.summonSettings().watchAdsFirst)
    }

    /**
     * The summon step's bot met an ad where the pass was taken for granted
     * and parked on it: nothing is drawn behind it, and the step parks with
     * the bot's sentence.
     */
    @Test
    fun `an ad in front after View Ads parks the summon step before any draw`() {
        val events = ArrayList<String>()
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 4))
        loop.summon = FakeSummon(events, parkOnAd = FreeAds.PARK)
        screen.push(20) { summonCard(0) }
        loop.tick()
        assertFalse(events.contains("spam"), "no draw behind the ad: $events")
        assertEquals(FreeAds.PARK, loop.parkedBecause)
    }

    /**
     * The player's rule of 2026-09-22: Special Summon is not opened from the
     * main screen any more, because the Summon icon lands on whichever tab
     * the game pleases and the tab row of a Buddy or SP Support page is not
     * a place this app taps (SummonSkill.onGeneral). So a quest card that
     * does not lead straight to the mode parks the step, and nothing at all
     * is asked of the bot.
     */
    @Test
    fun `a card that does not lead into the mode parks the step`() {
        val events = ArrayList<String>()
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 4))
        loop.summon = FakeSummon(events)
        // Same card, but the tap lands nowhere: no mode banner on the frame
        // after it.
        screen.push(20) { f(0, 30, name = 4, after = 5) }
        loop.tick()
        assertTrue(cardTaps(screen).isNotEmpty(), "the card was tapped: ${screen.taps}")
        assertEquals("could not reach the summon menu", loop.parkedBecause)
        assertTrue(events.isEmpty(), "and the bot was never asked for anything: $events")
    }

    /**
     * The wrong mode is not the right one. The card for step 5 landing on
     * Support Digimon Summon is a tap that went somewhere else, and the step
     * does not spend there.
     */
    @Test
    fun `a card that lands on another mode parks the step too`() {
        val events = ArrayList<String>()
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 4))
        loop.summon = FakeSummon(events)
        screen.push(20) { f(0, 30, name = 4, after = 5, dots = SummonSkill.SUPPORT) }
        loop.tick()
        assertEquals("could not reach the summon menu", loop.parkedBecause)
        assertTrue(events.isEmpty(), "$events")
    }

    @Test
    fun `a draw that went through is never 'nothing to draw with'`() {
        val events = ArrayList<String>()
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 4))
        loop.summon = FakeSummon(events, drew = 1)
        screen.push(20) { summonCard(0) }
        loop.tick()
        // It may still park -- the fake screen never moves the counter, and
        // afterAction is right to say so. What it must not say is that there
        // was nothing to draw with, because there was.
        assertFalse(loop.parkedBecause?.contains("nothing to draw with") == true,
                    "${loop.parkedBecause}")
        assertTrue(events.contains("leave"))
    }

    @Test
    fun `a free ad watched is not 'nothing to draw with' either`() {
        val events = ArrayList<String>()
        val screen = Screen()
        // With the pass, or the ad would not be taken in the first place.
        val loop = Loop(screen, set = QuestSkill.Settings(step = 4))
        loop.summon = FakeSummon(events, adsToWatch = 2)
        screen.push(20) { summonCard(0) }
        loop.tick()
        assertFalse(loop.parkedBecause?.contains("nothing to draw with") == true,
                    "${loop.parkedBecause}")
    }

    // ------------------------------------------------------------------
    // Running until nothing progresses
    // ------------------------------------------------------------------
    /**
     * What the chain's quest step waits for. Off everywhere else: a switch
     * somebody left on for the day parks and comes back, because a box can be
     * ticked and a screen can be closed while nobody is watching. A chain
     * step has nobody to wait for.
     */
    private fun parkingLoop(stopWhenStuck: Boolean): Pair<Screen, Loop> {
        // A loop on step 5, the Skill Card Summon step, against a summon bot
        // with no ad to watch and nothing to draw: the card is readable, the
        // step is one the loop plays, and every round it can do nothing but
        // park on the same reason. It used to be the dungeon box off on step
        // 2, which is not a park anybody can arrange any more -- the boxes
        // have gone (SkillSettings, the passive page) -- and this is the
        // cheapest one left that repeats round after round.
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 4))
        loop.summon = FakeSummon(ArrayList(), drew = 0, adsToWatch = 0)
        loop.stopWhenStuck = stopWhenStuck
        return screen to loop
    }

    @Test
    fun `three tries of the same park switch the loop off, but only for a chain`() {
        val (screen, loop) = parkingLoop(stopWhenStuck = true)
        repeat(QuestSkill.PARK_GIVE_UP - 1) {
            screen.push(summonCard())
            loop.tick()
            loop.clock.jump(QuestSkill.PARK_RETRY + 1)
        }
        assertNull(loop.stoppedBecause, "a park that comes back is not the end of it at once")
        screen.push(summonCard())
        loop.tick()
        assertNotNull(loop.stoppedBecause)
        assertTrue(loop.stoppedBecause!!.contains("nothing to draw with") &&
                       loop.stoppedBecause!!.contains("${QuestSkill.PARK_GIVE_UP} tries"),
                   "saying what was in the way, and how often: ${loop.stoppedBecause}")
        // An empty ticket stock is not the game withholding anything until
        // its reset: a ticket can arrive from somewhere else at any moment,
        // so a chain is free to try this step again in its next round.
        assertFalse(loop.stoppedUntilReset)

        val (screen2, loop2) = parkingLoop(stopWhenStuck = false)
        repeat(QuestSkill.PARK_GIVE_UP + 3) {
            screen2.push(summonCard())
            loop2.tick()
            loop2.clock.jump(QuestSkill.PARK_RETRY + 1)
        }
        assertNull(loop2.stoppedBecause, "the same park outside a chain never ends")
        assertNotNull(loop2.parkedBecause)
    }

    /**
     * Nothing parks on the steps the loop can only wait on -- enemies to be
     * beaten, a hologram counter Auto Spend fills in by itself, a screen in
     * the way that nothing here can close. A card that reads the same for a
     * quarter of an hour is the only witness there is for those.
     */
    @Test
    fun `a card that stands still for a quarter of an hour ends a chain's step`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 1))
        loop.stopWhenStuck = true
        repeat(3) {
            screen.push(bakemon(0))
            loop.tick()
        }
        assertNull(loop.stoppedBecause, "not straight away")
        loop.clock.jump(QuestSkill.NO_PROGRESS_AFTER + 1)
        screen.push(bakemon(0))
        loop.tick()
        assertNotNull(loop.stoppedBecause)
        // A standstill nobody could explain is not the game withholding
        // anything either -- whatever was in the way may well be gone by the
        // next round of a chain.
        assertFalse(loop.stoppedUntilReset)
    }

    @Test
    fun `a counter that keeps moving keeps the run alive`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 1))
        loop.stopWhenStuck = true
        for (ist in listOf(0, 1)) {
            screen.push(bakemon(ist))
            loop.tick()
            loop.clock.jump(QuestSkill.NO_PROGRESS_AFTER * 0.9)
        }
        assertNull(loop.stoppedBecause)
    }

    /**
     * PLAN_RELEASE_1_3.md B70, live on instance 1 on 2026-10-01: the
     * semi-automatic rounds read the card at 13:15 and parked on it; at
     * 13:29:32 the chain's quest step began on the same card, and at 13:30:06
     * -- 34 s in -- it switched itself off with "nothing on the quest card has
     * moved for 15 minutes": the clock was the rounds' reading's. Two chain
     * runs over two such starts: each runs its own quarter of an hour before
     * it gives up, and the second is not handed the first's ending.
     */
    @Test
    fun `a chain step's standstill clock starts with the step, run after run`() {
        // "Defeat 50 enemies": a card the loop only waits on, as it waited live.
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 3))
        val starts = ArrayList<Double>()
        val lasted = ArrayList<Double>()
        repeat(2) { run ->
            // The round reads the card, and nothing moves for twenty minutes.
            screen.push(f(10, 50, name = 6))
            loop.work(loop.look())
            loop.clock.jump(QuestSkill.NO_PROGRESS_AFTER + 300)
            starts += loop.clock.t
            val out = loop.run()
            lasted += loop.clock.t - starts.last()
            assertEquals(Result.PARKED, out.result, "run $run: ${loop.said}")
            assertTrue(out.why.startsWith("nothing on the quest card has moved for"), out.why)
        }
        for ((run, seconds) in lasted.withIndex()) {
            assertTrue(seconds >= QuestSkill.NO_PROGRESS_AFTER,
                       "run $run gave up after %.0f s, not after its own quarter of an hour: %s".format(seconds, loop.said))
        }
        assertEquals(2, loop.said.count { it.contains("switching the quest loop off: nothing on the quest card has moved") },
                     loop.said.toString())
    }

    /**
     * PLAN_BEFUNDE_1_3.md N3 d: "Wenn Quest Loop läuft, sollen zwischendurch
     * die Bond Tokens collected werden". The chain's step waits on "Defeat
     * 50 enemies" for minutes inside one turn of the director's, and the
     * bond token had no round in all that time. Every round of the step
     * that tapped nothing hands the main screen to the other rounds once
     * ([QuestSkill]'s `aside`); a semi-automatic round (`work`) does not,
     * since the director's own round of the main screen ticks them all.
     * With the call taken out of the loop it is red, 0 looks.
     */
    @Test
    fun `the chain's step gives the other rounds a look between two of its rounds that tapped nothing`() {
        val screen = Screen()
        var looks = 0
        val loop = Loop(screen, set = QuestSkill.Settings(step = 3), aside = { looks += 1 })
        screen.push(f(10, 50, name = 6))
        loop.work(loop.look())
        assertEquals(0, looks, "a round of the semi-automatic mode is not a step")
        val out = loop.run()
        assertEquals(Result.PARKED, out.result, loop.said.toString())
        assertTrue(screen.taps.isEmpty(), screen.taps.toString())
        assertTrue(looks >= 2, "the other rounds looked $looks times: ${loop.said}")
    }

    @Test
    fun `a standstill on its own switch waits for ever`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 1))
        screen.push(bakemon(0))
        loop.tick()
        loop.clock.jump(QuestSkill.NO_PROGRESS_AFTER * 3)
        screen.push(bakemon(0))
        loop.tick()
        assertNull(loop.stoppedBecause)
    }

    // ==================================================================
    // 4. The seam
    // ==================================================================
    @Test
    fun `the loop works on the main screen and nowhere else`() {
        val loop = Loop(Screen())
        assertTrue(loop.worksOn(Director.MAIN))
        for (screen in Director.SCREENS) {
            if (screen == Director.MAIN) continue
            assertFalse(loop.worksOn(screen), screen)
        }
    }

    /**
     * It used to be `quest_on && supporter`. The page switch has gone: what
     * says whether the loop runs is the row's own switch in the Skills list,
     * which the director asks beside this one, so a second switch in here
     * could only ever mean a row switched on that does nothing (Director,
     * `mainRound`). And the supporter code has gone since 2026-09-23: the
     * quest loop is everybody's, so a loop with no lock on it has a budget.
     */
    @Test
    fun `the budget is the day's lock alone`() {
        val clear = object : QuestSkill(
            DirectorTest.FakeCapture(), { QuestSkill.Settings() }, log = {}) {}
        assertTrue(clear.hasBudget(), "no lock, no code: the loop is free")
    }

    @Test
    fun `a round that is still going is never the chain's 'done'`() {
        val screen = Screen()
        val loop = Loop(screen)
        screen.push(demidevimon(1))
        val outcome = loop.work(loop.look())
        assertFalse(outcome.result == Result.DONE,
                    "a DONE would advance the chain past a step that is still running")
    }

    @Test
    fun `a switch-off that is not until the reset parks rather than retires`() {
        val (screen, loop) = parkingLoop(stopWhenStuck = true)
        repeat(QuestSkill.PARK_GIVE_UP) {
            screen.push(summonCard())
            loop.tick()
            loop.clock.jump(QuestSkill.PARK_RETRY + 1)
        }
        assertNotNull(loop.stoppedBecause)
        screen.push(summonCard())
        assertEquals(Result.PARKED, loop.work(loop.look()).result)
    }

    @Test
    fun `the remembered step is saved wherever it moves`() {
        val saved = ArrayList<Int>()
        val screen = Screen()
        val loop = object : QuestSkill(
            DirectorTest.FakeCapture(), { QuestSkill.Settings() },
            saveStep = { saved += it }, log = {}, sleep = {}, now = { 0.0 }) {
            override fun questCard(img: Mat): Quest.Card? {
                val p = screen.last.progress ?: return null
                return Quest.Card(Quest.CARD_MID_FX, 0.601, p.first, p.second, 0.78, 0.59,
                                  screen.last.nameLen, 5.6, screen.last.afterLen)
            }
            override fun autoButton(img: Mat) =
                if (screen.last.clear) Dungeon.Button(0.36, 0.77, 0.05, 0.05) else null
            override fun stageFailed(img: Mat) = false
            override fun grab(): Mat = PaintQuest.blank()
        }
        val frame = PaintQuest.blank()
        repeat(2) {
            screen.push(bakemon(0))
            screen.next()
            loop.tick(frame)
        }
        assertEquals(listOf(1), saved, "the resync to Bakemon is saved once")
    }

    // ==================================================================
    // 5. The day's lock
    // ==================================================================
    /**
     * The player's rule of 2026-09-22: once the loop has stopped for want of
     * dungeon tickets it is not tried again before the tickets come back,
     * which is eight in the morning in Vienna. Not "in eight hours" and not
     * "at eight UTC": a calendar time in one zone, which is why the two
     * days the clocks change are the cases worth having.
     */
    @Test
    fun `the next reset is eight in the morning in Vienna`() {
        assertEquals(at(2026, 9, 23, 8, 0), QuestSkill.nextReset(at(2026, 9, 22, 14, 7)),
                     "an afternoon stop waits for the next morning")
        assertEquals(at(2026, 9, 23, 8, 0), QuestSkill.nextReset(at(2026, 9, 23, 7, 59)),
                     "a stop just before eight waits for that eight")
        assertEquals(at(2026, 9, 24, 8, 0), QuestSkill.nextReset(at(2026, 9, 23, 8, 0)),
                     "on the hour itself the reset has just gone by")
        // The clocks go back on 2026-10-25: that night is 25 hours long, and
        // 08:00 is still 08:00 -- 13 hours after 20:00, not 12.
        val backNight = QuestSkill.nextReset(at(2026, 10, 24, 20, 0))
        assertEquals(at(2026, 10, 25, 8, 0), backNight)
        assertEquals(13 * 3600.0, backNight - at(2026, 10, 24, 20, 0))
        // And forward on 2026-03-29: 11 hours.
        val forwardNight = QuestSkill.nextReset(at(2026, 3, 28, 20, 0))
        assertEquals(at(2026, 3, 29, 8, 0), forwardNight)
        assertEquals(11 * 3600.0, forwardNight - at(2026, 3, 28, 20, 0))
        assertEquals("2026-09-23 08:00", QuestSkill.whenText(at(2026, 9, 23, 8, 0)))
    }

    /**
     * The TODAY card's day is the game's (PLAN_REPORT_RAUS.md 2.12): one
     * number from one reset to the next, a new one at 08:00 Vienna and not
     * at midnight, on the two nights the clocks change as well -- and the
     * phone's own zone does not come into it, so a phone in Tokyo counts the
     * same day as one in Vienna.
     */
    @Test
    fun `the TODAY card's day falls at the reset, not at midnight`() {
        val before = TimeZone.getDefault()
        try {
            for (zone in listOf("Europe/Vienna", "Asia/Tokyo", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val evening = QuestSkill.statsDay(at(2026, 9, 22, 23, 59))
                assertEquals(evening, QuestSkill.statsDay(at(2026, 9, 23, 0, 1)), "$zone: midnight is not a new day")
                assertEquals(evening, QuestSkill.statsDay(at(2026, 9, 23, 7, 59)), "$zone: still the day before eight")
                assertEquals(at(2026, 9, 23, 8, 0).toLong(), evening, "$zone: the day is the reset that ends it")
                val morning = QuestSkill.statsDay(at(2026, 9, 23, 8, 0))
                assertTrue(morning > evening, "$zone: eight o'clock is a new day")
                assertEquals(morning, QuestSkill.statsDay(at(2026, 9, 24, 7, 59)), "$zone: and it lasts until the next")
                // The night the clocks go back (25 hours) and forward (23).
                assertEquals(QuestSkill.statsDay(at(2026, 10, 24, 8, 0)), QuestSkill.statsDay(at(2026, 10, 25, 7, 59)))
                assertTrue(QuestSkill.statsDay(at(2026, 10, 25, 8, 0)) > QuestSkill.statsDay(at(2026, 10, 25, 7, 59)))
                assertEquals(QuestSkill.statsDay(at(2026, 3, 28, 8, 0)), QuestSkill.statsDay(at(2026, 3, 29, 7, 59)))
                assertTrue(QuestSkill.statsDay(at(2026, 3, 29, 8, 0)) > QuestSkill.statsDay(at(2026, 3, 29, 7, 59)))
            }
        } finally {
            TimeZone.setDefault(before)
        }
    }

    /**
     * The whole rule in one run: the stop writes the lock, the lock holds
     * the budget until the reset and not a second less, and at the reset
     * the loop plays again by itself -- nobody restarts anything at eight in
     * the morning.
     */
    @Test
    fun `out of tickets, the loop is locked until the reset and comes back after it`() {
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 1))
        loop.dungeon = FakeDungeon(Dungeon.Budget(1, 0, 1))
        screen.push(20) { bakemon(0) }
        assertTrue(loop.hasBudget(), "free to run before anything happened")
        loop.work(loop.look())
        assertNotNull(loop.stoppedBecause)

        val until = QuestSkill.nextReset(loop.wall.t)
        assertEquals(listOf(until to loop.stoppedBecause!!), loop.wall.locked,
                     "the stop is on the record: when, and why")
        assertTrue(loop.said.any { it.contains("until 2026-09-23 08:00") },
                   "...and the log says when: ${loop.said}")
        assertFalse(loop.hasBudget(), "no budget while the lock stands")

        loop.wall.t = until - 1.0
        assertFalse(loop.hasBudget(), "...and not a second before it ends")
        val before = screen.taps.size
        screen.push(20) { bakemon(0) }
        loop.tick()
        assertEquals(before, screen.taps.size, "a round in the meantime plays nothing")
        assertNotNull(loop.stoppedBecause)

        loop.wall.t = until
        assertTrue(loop.hasBudget(), "at the reset the budget is back")
        loop.dungeon = FakeDungeon(Dungeon.Budget(2, null, null))
        screen.push(20) { bakemon(0) }
        loop.tick()
        assertNull(loop.stoppedBecause, "...and the next round resumes the loop by itself")
        assertTrue(loop.dungeon.ran, "...and plays the dungeon again")
        assertEquals(1, loop.wall.locked.size, "a lock that has run out is not written again for it")
    }

    /**
     * The other half of "not again today": a restart of the core. The lock
     * is in the settings file, so a loop built fresh reads it and has no
     * budget, and says so once; the same file with the lock behind it, or
     * with none, is a loop free to run.
     */
    @Test
    fun `a lock in the settings file holds a freshly built loop`() {
        val said = ArrayList<String>()
        fun loopAt(t: Double, lockedUntil: Double?) = object : QuestSkill(
            DirectorTest.FakeCapture(), { QuestSkill.Settings(lockedUntil = lockedUntil) },
            log = { said += it }, clock = { t }) {}

        val until = at(2026, 9, 23, 8, 0)
        val held = loopAt(at(2026, 9, 22, 23, 30), until)
        assertFalse(held.hasBudget())
        assertFalse(held.hasBudget())
        assertEquals(1, said.count { it.contains("stopped until 2026-09-23 08:00") },
                     "said once, not once a round: $said")
        // The chain's skip names the lock, where it said "nothing to do, on
        // the settings as they stand" (PLAN_ABSCHLUSS_1_3.md A2, the Poco at
        // 19:12:27 on 2026-10-02).
        assertEquals("stopped until 2026-09-23 08:00: out of dungeon tickets", held.noBudgetWhy())
        assertNull(loopAt(at(2026, 9, 23, 8, 0), until).noBudgetWhy())
        assertTrue(loopAt(at(2026, 9, 23, 8, 0), until).hasBudget(), "the lock has run out")
        assertTrue(loopAt(at(2026, 9, 22, 23, 30), null).hasBudget(), "no lock, no hold")
        // The player took the lock away by hand (the row's switch): the
        // stopped loop notices on its next round, the same as at the reset.
        val screen = Screen()
        val loop = Loop(screen, set = QuestSkill.Settings(step = 1))
        loop.dungeon = FakeDungeon(Dungeon.Budget(1, 0, 1))
        screen.push(20) { bakemon(0) }
        loop.tick()
        assertNotNull(loop.stoppedBecause)
        loop.wall.settings = loop.wall.settings.copy(lockedUntil = null)
        loop.dungeon = FakeDungeon(Dungeon.Budget(2, null, null))
        screen.push(20) { bakemon(0) }
        loop.tick()
        assertNull(loop.stoppedBecause)
        assertTrue(loop.dungeon.ran)
    }

    /**
     * Not every stop is the day's. A park that gave up after three tries is
     * a box that can be ticked or a screen that can be closed, and it writes
     * no lock -- the chain may try that step again in its next round, as
     * before.
     */
    @Test
    fun `a stop that is not until the reset writes no lock`() {
        val (screen, loop) = parkingLoop(stopWhenStuck = true)
        repeat(QuestSkill.PARK_GIVE_UP) {
            screen.push(summonCard())
            loop.tick()
            loop.clock.jump(QuestSkill.PARK_RETRY + 1)
        }
        assertNotNull(loop.stoppedBecause)
        assertTrue(loop.wall.locked.isEmpty(), "${loop.wall.locked}")
        assertTrue(loop.hasBudget())
    }

    /**
     * The row switched off and on (PLAN_ABSCHLUSS_1_3.md A2): the loop's own
     * ending goes with it, and not only the day's. A stop after three tries
     * held every semi-automatic round after it until the chain's next quest
     * step; the switch is the player saying "try now".
     */
    @Test
    fun `the row's switch takes the loop's own ending away`() {
        val (screen, loop) = parkingLoop(stopWhenStuck = true)
        repeat(QuestSkill.PARK_GIVE_UP) {
            screen.push(summonCard())
            loop.tick()
            loop.clock.jump(QuestSkill.PARK_RETRY + 1)
        }
        val why = assertNotNull(loop.stoppedBecause)
        screen.push(summonCard())
        loop.tick()
        assertEquals(why, loop.stoppedBecause, "a round in the meantime leaves the stop where it is")
        loop.restarted()
        assertNull(loop.stoppedBecause)
        assertNull(loop.parkedBecause)
        // A loop that never stopped has nothing to forget.
        val (_, fresh) = parkingLoop(stopWhenStuck = true)
        fresh.restarted()
        assertNull(fresh.stoppedBecause)
    }

    companion object {
        /** Epoch seconds of a wall-clock time in [QuestSkill.RESET_ZONE]. */
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Double =
            ZonedDateTime.of(y, mo, d, h, mi, 0, 0, QuestSkill.RESET_ZONE).toEpochSecond().toDouble()
    }
}
