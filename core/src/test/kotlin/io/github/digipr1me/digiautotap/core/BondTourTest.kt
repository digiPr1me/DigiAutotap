package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * test_bond_tour.py in Kotlin: the same script (PLAN_ANDROID_APP.md 4,
 * session L).
 *
 * The Python file has two halves and paints neither. Its reader half runs
 * over twelve real screenshots that stay on the machine that captured them;
 * on this side those readers are P2's and are held to `oracle/passive.json`
 * by [PassiveOracleTest] over the whole corpus, which is the same frames and
 * more of them. So what is here is the other half, the one the Python file
 * calls "BondTour's own control flow, _enter/_raise stood in for": the order
 * arithmetic, the retry rule, the way home, and the tour's own clock over
 * the passive helper's round.
 *
 * The frames that are painted are [Paint]'s: the main screen with its auto
 * button, which is the tour's opening gate and its proof of being home.
 */
class BondTourTest {

    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    // ------------------------------------------------------------------
    // The rig
    // ------------------------------------------------------------------
    /** test_bond_tour.FakeBot: what the tour tapped, and what the screen said. */
    class Rig(var home: Boolean = true) {
        val cap = DirectorTest.FakeCapture()
        val log = ArrayList<String>()
        val kept = ArrayList<String>()
        var t = 100.0
        var on = true

        /** How many frames have been handed out, for a script that changes. */
        var grabs = 0

        /** The frame every look gets. The seams answer instead of the pixels. */
        val frame: Mat = Paint.mainScreen()
    }

    /**
     * A tour whose two big steps are stood in for, as the Python cases mock
     * `_enter` and `_raise`: what is checked here is the order arithmetic and
     * the way home, never the recognisers.
     */
    open class Tour(
        val rig: Rig,
        val enterAnswer: () -> Pair<Int?, Int> = { 0 to 3 },
        val raiseAnswer: (Int) -> Boolean = { false },
    ) : BondTour(rig.cap, log = { rig.log += it }, on = { rig.on },
                 keep = { _, tag -> rig.kept += tag }, sleep = {}, now = { rig.t }) {

        var homeCalls = 0
        var backCalls = 0
        val raised = ArrayList<Int>()

        // A fresh copy every time: the tour releases what `grab` handed it.
        override fun grab(): Mat { rig.grabs += 1; return Paint.copy(rig.frame) }
        override fun tap(img: Mat, fx: Double, fy: Double) {}
        override fun enter(): Pair<Int?, Int> = enterAnswer()
        override fun raise(target: Int): Boolean {
            raised += target
            return raiseAnswer(target)
        }

        override fun goHome(): Boolean {
            homeCalls += 1
            return rig.home
        }
    }

    // ==================================================================
    // 1. The order arithmetic
    // ==================================================================
    /**
     * From a start index of 13 over 15 cells, the tour visits 14, 0, 1 ... 12
     * and ends by raising 13 again -- n Raise presses for n cells, so that a
     * finished tour leaves the player's own Digimon raised.
     */
    @Test
    fun `the visiting order wraps round and ends on the start cell`() {
        val rig = Rig()
        val seen = ArrayList<Int>()
        var last = 13
        val walk = object : BondTour(rig.cap, log = { rig.log += it }, on = { rig.on },
                                     keep = { _, _ -> }, sleep = {}, now = { rig.t }) {
            override fun grab(): Mat = Paint.copy(rig.frame)
            override fun tap(img: Mat, fx: Double, fy: Double) {}
            override fun goHome(): Boolean = true
            override fun enter(): Pair<Int?, Int> = last to 15
            override fun raise(target: Int): Boolean {
                seen += target
                last = target
                return true
            }
        }
        val stats = walk.run { false }
        assertEquals(listOf(14) + (0..12).toList() + listOf(13), seen, "the order")
        assertEquals((0..14).toList(), seen.sorted(), "no cell twice, none missed")
        assertTrue(stats.returned)
        assertEquals(15, stats.visited)
        assertEquals(13, stats.startedAt)
    }

    // ==================================================================
    // 2. attempt(): three tries, the look before the retry, the kept frame
    // ==================================================================
    @Test
    fun `attempt gives up after three tries and keeps the frame`() {
        val rig = Rig()
        val tour = Tour(rig)
        var calls = 0
        val result = tour.attempt<Any>("a test step", { calls += 1 }) { null }
        assertNull(result)
        assertEquals(BondTour.TRIES, calls, "$calls attempt(s)")
        assertEquals(listOf("a_test_step"), rig.kept, "the frame it gave up on is kept")
    }

    @Test
    fun `attempt sends nothing once the main switch is off`() {
        val rig = Rig()
        rig.on = false
        val tour = Tour(rig)
        var calls = 0
        val got = tour.attempt("a test step", { calls += 1 }) { Unit }
        assertNull(got)
        assertEquals(0, calls, "no action")
        assertTrue(rig.kept.isEmpty(), "and no dump: nothing was misread")
    }

    /**
     * A retry looks before it repeats. The first live run tapped the same
     * coordinate three times, and taps two and three went into a dialog the
     * first one had opened. So from the second attempt on, a known wrong
     * state has to be undone first -- and never on the first attempt, where
     * the caller has just looked.
     */
    @Test
    fun `a retry closes the dialog it walked into, and never before the first try`() {
        val rig = Rig()
        val closes = ArrayList<Pair<Double, Double>>()
        val tour = object : Tour(rig) {
            override fun partnerMenu(img: Mat): Dungeon.Button? =
                Dungeon.Button(0.5, 0.5, 0.2, 0.05)

            override fun tap(img: Mat, fx: Double, fy: Double) {
                if (fx == PassiveSkill.PARTNER_CLOSE[0] && fy == PassiveSkill.PARTNER_CLOSE[1]) {
                    closes += fx to fy
                }
            }
        }
        tour.attempt<Any>("a test step", { }) { null }
        assertEquals(BondTour.TRIES - 1, closes.size, "${closes.size} close(s)")

        // The same screen with no dialog on it must not be poked at all.
        val rig2 = Rig()
        val closes2 = ArrayList<Pair<Double, Double>>()
        val clean = object : Tour(rig2) {
            override fun partnerMenu(img: Mat): Dungeon.Button? = null
            override fun tap(img: Mat, fx: Double, fy: Double) { closes2 += fx to fy }
        }
        clean.attempt<Any>("a test step", { }) { null }
        assertTrue(closes2.isEmpty(), "a clear screen is left alone between retries")
    }

    // ==================================================================
    // 3. The way home
    // ==================================================================
    @Test
    fun `a step that always fails ends the tour with a reason, and home is asked once`() {
        val rig = Rig()
        val tour = Tour(rig, enterAnswer = { 0 to 3 }, raiseAnswer = { false })
        val stats = tour.run { false }
        assertFalse(stats.returned)
        assertTrue(stats.reason.isNotEmpty(), stats.reason)
        assertEquals(1, tour.homeCalls, "go_home is asked for exactly once")
        assertTrue(stats.seconds >= 0)
    }

    /**
     * The globe first, then the back key -- and only then, never as the first
     * resort. Every press is preceded by a look, so a screen that is already
     * home is never pressed at all: with no dialog open the back key raises
     * "Return to the title screen?", whose OK ends the session.
     */
    @Test
    fun `the back key is not pressed on a screen already home`() {
        val rig = Rig()
        val tour = object : BondTour(rig.cap, log = { rig.log += it }, on = { rig.on },
                                     keep = { _, _ -> }, sleep = {}, now = { rig.t }) {
            override fun grab(): Mat = Paint.copy(rig.frame)
            override fun tap(img: Mat, fx: Double, fy: Double) {}
            override fun autoButton(img: Mat) = Dungeon.Button(0.36, 0.77, 0.05, 0.05)
        }
        assertTrue(tour.goHome())
        assertEquals(0, rig.cap.backs, "the back key was not pressed")
        assertTrue(rig.cap.taps.isEmpty(), "and neither was the globe")
    }

    @Test
    fun `a tour that cannot find the globe falls back on the back key`() {
        val rig = Rig()
        val tour = object : BondTour(rig.cap, log = { rig.log += it }, on = { rig.on },
                                     keep = { _, _ -> }, sleep = {}, now = { rig.t }) {
            override fun grab(): Mat = Paint.copy(rig.frame)
            override fun tap(img: Mat, fx: Double, fy: Double) {}
            override fun autoButton(img: Mat): Dungeon.Button? = null
            override fun homeButton(img: Mat): Dungeon.Button? = null
        }
        assertFalse(tour.goHome())
        assertEquals(BondTour.BACK_PRESSES, rig.cap.backs, "${rig.cap.backs} presses")
    }

    /**
     * The globe is tapped where it was found, and the proof it landed is the
     * auto button rather than the globe being gone.
     */
    @Test
    fun `the globe is tapped where it was found and confirmed by the auto button`() {
        val rig = Rig()
        var round = 0
        val taps = ArrayList<Pair<Double, Double>>()
        val tour = object : BondTour(rig.cap, log = { rig.log += it }, on = { rig.on },
                                     keep = { _, _ -> }, sleep = {}, now = { rig.t }) {
            override fun grab(): Mat { round += 1; return Paint.copy(rig.frame) }
            override fun tap(img: Mat, fx: Double, fy: Double) { taps += fx to fy }
            // Not home on the first look, home once the globe has been tapped.
            override fun autoButton(img: Mat): Dungeon.Button? =
                if (round > 1) Dungeon.Button(0.36, 0.77, 0.05, 0.05) else null

            override fun homeButton(img: Mat) = Dungeon.Button(0.477, 0.935, 0.1, 0.05)
        }
        assertTrue(tour.goHome())
        assertEquals(listOf(0.477 to 0.935), taps)
        assertEquals(0, rig.cap.backs, "the back key is not reached for at all")
    }

    /**
     * The tour's last step raises the Digimon it began with and then opens the
     * Partner page once more to check the Raise took -- so a tour that
     * finished perfectly used to hand the game back on the Partner screen,
     * with the passive helper's next round saying "not the plain main screen"
     * for as long as anybody watched. It goes home whether it finished or
     * failed now.
     */
    @Test
    fun `a finished tour ends on the main screen`() {
        val rig = Rig()
        var last = 0
        val collected = ArrayDeque(listOf(true, false, true))
        val tour = object : BondTour(rig.cap, log = { rig.log += it }, on = { rig.on },
                                     keep = { _, _ -> }, sleep = {}, now = { rig.t }) {
            var homeCalls = 0
            override fun grab(): Mat = Paint.copy(rig.frame)
            override fun tap(img: Mat, fx: Double, fy: Double) {}
            override fun goHome(): Boolean { homeCalls += 1; return true }
            override fun enter(): Pair<Int?, Int> = last to 3
            override fun raise(target: Int): Boolean { last = target; return true }
        }
        val stats = tour.run { collected.removeFirstOrNull() ?: false }
        assertTrue(stats.returned)
        assertEquals(3, stats.visited, "3 cells: the other 2 and the return to cell 0")
        assertEquals(2, stats.collected, "the token is collected wherever collect() said so")
        assertEquals(stats.visited + 1, tour.homeCalls,
                     "one home per cell raised, plus the one at the end")
    }

    @Test
    fun `a tour that cannot start from a clear main screen says so and taps nothing`() {
        val rig = Rig()
        val tour = object : Tour(rig) {
            override fun autoButton(img: Mat): Dungeon.Button? = null
        }
        val stats = tour.run { false }
        assertEquals("did not start from a clear main screen", stats.reason)
        assertEquals(0, tour.homeCalls, "not even the way home: nothing was opened")
        assertTrue(tour.raised.isEmpty())
    }

    /**
     * The main switch pressed in the middle of a tour. The tour must stop
     * sending taps at its next look, still go home, and say why it stopped
     * rather than blaming a step that only failed because it was told to.
     */
    @Test
    fun `the main switch ends the tour after the step in hand`() {
        val rig = Rig()
        val tour = object : Tour(rig, enterAnswer = { 0 to 5 }) {
            override fun raise(target: Int): Boolean {
                raised += target
                rig.on = false
                return true
            }
        }
        val stats = tour.run { false }
        assertEquals(listOf(1), tour.raised, "raised ${tour.raised}")
        assertFalse(stats.returned)
        assertEquals(BondTour.STOPPED, stats.reason)
        assertEquals(2, tour.homeCalls, "one after the step, one on the way out")
    }

    // ==================================================================
    // 4. TourCollect: the tour's own clock over the helper's round
    // ==================================================================
    /**
     * A collector over a scripted sequence of the helper's rounds.
     *
     * The real round is [BondTourSkill.roundOf] -- `passive.work(img)` and
     * then `passive.seen`. `PassiveSkill` is final and its `seen` has a
     * private setter, so nothing can stand in for one; the round is a
     * function for exactly that reason, and this is the fake.
     */
    private fun collector(rounds: List<PassiveSkill.Seen>, clock: QuestSkillTest.Clock,
                          log: MutableList<String> = ArrayList()): Pair<TourCollect, () -> Int> {
        val cap = DirectorTest.FakeCapture()
        var i = 0
        var calls = 0
        val round: (Mat) -> PassiveSkill.Seen = {
            calls += 1
            rounds[minOf(i++, rounds.size - 1)]
        }
        return TourCollect(cap, round, log = { log += it }, sleep = {},
                           now = { clock.read() }) to { calls }
    }

    private fun seen(did: String? = null, bubble: Passive.Bubble? = null,
                     clear: Boolean = true) =
        PassiveSkill.Seen(clear = clear, holo = null, bubble = bubble, did = did)

    private val BUBBLE = Passive.Bubble(0.50, 0.36, 0.08, 0.06, 0.45)

    @Test
    fun `a round that collected the token answers true, once the screen is back`() {
        val clock = QuestSkillTest.Clock()
        val log = ArrayList<String>()
        val (collect, _) = collector(listOf(
            seen(),                                     // no bubble yet
            seen(bubble = BUBBLE),                      // a sighting
            seen(did = TourCollect.COLLECTED, bubble = BUBBLE),
            seen(),                                     // gone -- or dead
            seen(did = TourCollect.GONE),               // stayed gone: the helper's word
            seen()), clock, log)
        assertTrue(collect.collect())
        assertTrue(log.any { it.contains("token collected") }, "$log")
    }

    /**
     * The player watched the tour move on to the next Digimon before it had
     * collected anything: the tap had found a dying Digimon, the bubble went
     * with it, and "no bubble after the tap" was the whole of the evidence.
     * The helper says when the bubble has stayed away (PassiveSkill.BOND_WATCH)
     * and taps again when it comes back first; the tour waits for its word.
     */
    @Test
    fun `the bubble gone after the tap is not yet the token -- the tour waits for the helper's word`() {
        val clock = QuestSkillTest.Clock()
        val log = ArrayList<String>()
        val beats = ArrayList<Double>()
        val cap = DirectorTest.FakeCapture()
        val rounds = listOf(
            seen(bubble = BUBBLE),
            seen(did = TourCollect.COLLECTED, bubble = BUBBLE),
            seen(),                                     // dead
            seen(),
            seen(bubble = BUBBLE),                      // back with the token
            seen(did = TourCollect.COLLECTED, bubble = BUBBLE),
            seen(),
            seen(did = TourCollect.GONE),
            seen())
        var i = 0
        val collect = TourCollect(cap, { rounds[minOf(i++, rounds.size - 1)] },
                                  beat = { Director.BEAT_WATCH },
                                  log = { log += it }, sleep = { beats += it },
                                  now = { clock.read() })
        assertTrue(collect.collect())
        assertEquals(9, i, "the eight scripted rounds in order, and the settle's one look: $log")
        assertTrue(beats.all { it == Director.BEAT_WATCH }, "looked at the helper's pace: $beats")
        assertEquals(1, log.count { it.contains("token collected") }, "$log")
    }

    @Test
    fun `a tap the helper never confirms is given up on after the collect wait`() {
        val clock = QuestSkillTest.Clock()
        val log = ArrayList<String>()
        val (collect, _) = collector(listOf(
            seen(bubble = BUBBLE),
            seen(did = TourCollect.COLLECTED, bubble = BUBBLE),
            seen()), clock, log)
        assertFalse(collect.collect())
        assertTrue(log.any { it.contains("did not stay gone") }, "$log")
    }

    /**
     * Coming home is not the same as being ready: over one live tour, from
     * the frame the globe confirmed the main screen to the frame the bubble
     * was first seen on, 11.9 s, 6.4 s, 11.7 s, 6.1 s, 11.9 s -- not one of
     * them on the first frame, none under six seconds. A single look was the
     * whole bug.
     */
    @Test
    fun `no bubble on the first look is not no bubble`() {
        val clock = QuestSkillTest.Clock()
        val log = ArrayList<String>()
        val rounds = ArrayList<PassiveSkill.Seen>()
        // Nine quiet looks, then the bubble -- the worst measured sighting.
        repeat(9) { rounds += seen() }
        rounds += seen(bubble = BUBBLE)
        rounds += seen(did = TourCollect.COLLECTED, bubble = BUBBLE)
        rounds += seen()
        rounds += seen(did = TourCollect.GONE)
        rounds += seen()
        val (collect, calls) = collector(rounds, clock, log)
        assertTrue(collect.collect(), "the token is collected after the wait")
        assertTrue(calls() > 9, "the helper was asked ${calls()} times, not once")
    }

    @Test
    fun `a Digimon with no token at all is left after the wait, and that is not a failure`() {
        val clock = QuestSkillTest.Clock()
        val log = ArrayList<String>()
        val (collect, _) = collector(listOf(seen()), clock, log)
        assertFalse(collect.collect())
        assertTrue(log.any { it.contains("no token for this one") }, "$log")
    }

    /**
     * The window is what says whether the token was taken. The player named
     * the rule: tapping the Digimon in the middle collects the bubble, and
     * where there is no bubble the same tap opens that Digimon's own window
     * instead. A window covers the bubble, so a tap that took nothing used to
     * look exactly like a tap that took the token.
     */
    @Test
    fun `a window after the tap means there was nothing to take`() {
        val clock = QuestSkillTest.Clock()
        val log = ArrayList<String>()
        val (collect, _) = collector(listOf(
            seen(bubble = BUBBLE),
            seen(did = TourCollect.COLLECTED, bubble = BUBBLE),
            seen(did = TourCollect.CLOSED_WINDOW, clear = false),
            seen()), clock, log)
        assertFalse(collect.collect())
        assertTrue(log.any { it.contains("no token to take") }, "$log")
    }

    /**
     * A window that was up before anything was tapped is not an answer to a
     * question this loop asked -- it is something in the way. The helper's own
     * round closes it; this keeps looking until the clock runs out.
     */
    @Test
    fun `a window nobody opened is waited out, not read as an answer`() {
        val clock = QuestSkillTest.Clock()
        val log = ArrayList<String>()
        val (collect, calls) = collector(
            listOf(seen(did = TourCollect.CLOSED_WINDOW, clear = false)), clock, log)
        assertFalse(collect.collect())
        assertTrue(log.any { it.contains("will not go") }, "$log")
        assertTrue(calls() > 1, "it looked again rather than answering at once")
    }

    @Test
    fun `a bubble that will not go is given up on after the collect wait`() {
        val clock = QuestSkillTest.Clock()
        val log = ArrayList<String>()
        val (collect, _) = collector(listOf(seen(bubble = BUBBLE)), clock, log)
        assertFalse(collect.collect())
        assertTrue(log.any { it.contains("still there after") }, "$log")
    }

    @Test
    fun `the main switch off ends a collect at once`() {
        val clock = QuestSkillTest.Clock()
        val cap = DirectorTest.FakeCapture()
        val collect = TourCollect(cap, { seen() }, log = {}, on = { false }, sleep = {},
                                  now = { clock.read() })
        assertFalse(collect.collect())
    }

    // ==================================================================
    // 5. The seam
    // ==================================================================
    @Test
    fun `the tour works on the main screen and nowhere else`() {
        val cap = DirectorTest.FakeCapture()
        val passive = PassiveSkill(cap, { _, d -> d }, log = {})
        val skill = BondTourSkill(cap, passive, { _, d -> d }, log = {})
        assertTrue(skill.worksOn(Director.MAIN))
        for (screen in Director.SCREENS) {
            if (screen == Director.MAIN) continue
            assertFalse(skill.worksOn(screen), screen)
        }
    }

    /**
     * There used to be three boxes in front of this walk -- the helper is
     * watching, the helper is collecting the token, and all of the Digimon
     * -- and the first two have gone with the rest of the page's switches:
     * the Bond token row's own switch says both, and the director asks that
     * one itself (`included`). What is left is the box that says whether the
     * others are visited at all.
     */
    @Test
    fun `the one box has to be ticked`() {
        val cap = DirectorTest.FakeCapture()
        val passive = PassiveSkill(cap, { _, d -> d }, log = {})
        for (all in listOf(true, false)) {
            val flags = mapOf(PassiveSkill.PASSIVE_ALL_DIGIMON to all)
            val skill = BondTourSkill(cap, passive, { k, d -> flags[k] ?: d }, log = {})
            assertEquals(all, skill.hasBudget(), "$flags")
        }
        // And nothing else in the file is asked: an empty settings file walks
        // nowhere, a file with the one box ticked walks.
        assertFalse(BondTourSkill(cap, passive, { _, d -> d }, log = {}).hasBudget(),
                    "the default is not to walk")
    }

    /**
     * Walking all of the Digimon is a supporter's feature since 2026-09-23.
     * The box can stay ticked on a phone with no code -- the page greys it
     * out and keeps the setting, so a code redeemed later gives it back --
     * and so the tour asks the code itself, beside the box.
     */
    @Test
    fun `the ticked box walks only with a supporter code`() {
        val cap = DirectorTest.FakeCapture()
        val passive = PassiveSkill(cap, { _, d -> d }, log = {})
        for (code in listOf(true, false)) {
            val skill = BondTourSkill(cap, passive, { _, _ -> true }, supporter = { code }, log = {})
            assertEquals(code, skill.hasBudget(), "code=$code")
        }
    }

    /**
     * A tour walks only after a token was collected. Every other round this
     * does nothing at all, which is what keeps a walk of fifteen Digimon off
     * a main screen with no token on it -- and it is the same trigger the PC
     * has, where `passive.tick` starts the tour from inside its own round.
     */
    @Test
    fun `a round with no collection behind it walks nowhere`() {
        val cap = DirectorTest.FakeCapture()
        val passive = PassiveSkill(cap, { _, d -> d }, log = {})
        val skill = BondTourSkill(cap, passive, { _, _ -> true }, log = {},
                                  sleep = {}, now = { 0.0 })
        val img = Paint.mainScreen()
        assertEquals(Result.RETIRED, skill.work(img).result)
        assertNull(skill.stats, "no tour was walked")
        assertTrue(cap.taps.isEmpty())
        img.release()
    }
}
