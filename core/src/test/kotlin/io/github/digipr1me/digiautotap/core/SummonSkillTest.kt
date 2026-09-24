package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Mat
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * test_summon_flow.py's bot-level cases in Kotlin: the same script, frame for
 * frame and assertion for assertion -- the dialog and the red price, the
 * single frame of either that is not enough, the swallowed Close, the limit,
 * the Crest double tap, the frozen picture, the stop, the walk in and the walk
 * out, and the ad phase that never presses the yellow button.
 *
 * Two things differ from the Python script, both on purpose:
 *
 *  - **The frames are painted, and the readers are real.** There the
 *    bot-level cases patch every reader and hand them plain dicts, because
 *    what was under test was the state machine and the pixel readers had their
 *    own cases above. Here the frames go through [Summon] itself ([PaintSummon]
 *    paints them), which is the only proof a port can make: a state machine
 *    that agrees with a stub written beside it says nothing about whether it
 *    agrees with the game.
 *  - **There is no dry run.** The PC's `--dry-run` plans a run and clicks
 *    nothing; the phone has no such switch, and the main switch is what stops
 *    a run here. The two Python cases that measure a dry run have no Kotlin
 *    counterpart; the two that measure the main switch do.
 *
 * The capture is [DirectorTest.FakeCapture], the fake the director's own test
 * drives, and the clock moves one round per grab the way the Python Screen's
 * does.
 */
class SummonSkillTest {

    /** Python's Clock.TICK: what one round costs live -- a capture, a look, a tap. */
    private val tick = 0.2

    /**
     * A queue of painted frames, a clock the test moves, and the skill over
     * them. Every grab hands out a copy, as a real capture does, and repeats
     * the last frame once the queue is empty -- the way a settled screen keeps
     * looking the same on every further read.
     */
    private class Rig(
        initial: SummonSkill.Settings = SummonSkill.Settings(),
        frozenSeconds: Double = SummonSkill.FROZEN_SECONDS,
        private val tick: Double = 0.2,
        vararg frames: Mat,
    ) {
        val cap = DirectorTest.FakeCapture()
        val log = ArrayList<String>()
        val kept = ArrayList<String>()
        val queue = frames.toMutableList()
        var t = 1000.0
        var on = true
        var settings = initial
        val skill = SummonSkill(
            cap, { this.settings }, log = { log += it }, keep = { _, tag -> kept += tag },
            on = { on },
            // No waiting: the clock moves once per grab, as the Python Screen's
            // does, and the taps go out as fast as the frames arrive.
            patience = 0.0, tapEvery = 0.0, frozenSeconds = frozenSeconds,
            now = { t }, sleep = { t += it })

        init {
            cap.scene = { i ->
                t += tick
                Paint.copy(queue[minOf(i, queue.size - 1)])
            }
        }

        /** A scene of its own, for the cases that need a frame built per round. */
        fun scene(paint: (Int) -> Mat) {
            cap.scene = { i ->
                t += tick
                paint(i)
            }
        }

        /** Every tap so far, by the name test_summon_flow.py gives it. */
        fun taps(): List<String> = cap.taps.map { (x, y) ->
            val fx = x / Paint.W.toDouble()
            val fy = y / Paint.H.toDouble()
            when {
                near(fx, fy, PaintSummon.BUTTON.fx, PaintSummon.BUTTON.fy) -> "summon"
                near(fx, fy, PaintSummon.CLOSE_FX, PaintSummon.CLOSE_FY) -> "Close"
                near(fx, fy, Summon.EXIT_BUTTON_FX, Summon.EXIT_BUTTON_FY) -> "X"
                near(fx, fy, PaintSummon.ADS_BUTTON.fx, PaintSummon.ADS_BUTTON.fy) -> "View Ads"
                near(fx, fy, SummonSkill.NEUTRAL_TAP_FX, SummonSkill.NEUTRAL_TAP_FY) -> "hurry"
                near(fx, fy, Summon.SUMMON_ICON_FX, Summon.SUMMON_ICON_FY) -> "icon"
                near(fx, fy, Summon.NEIGHBOUR_FX_RIGHT, Summon.BANNER_FY) -> "banner"
                near(fx, fy, Summon.NEIGHBOUR_FX_LEFT, Summon.BANNER_FY) -> "banner"
                near(fx, fy, GENERAL_FX, GENERAL_FY) -> "General"
                near(fx, fy, Summon.xBesideButton(PaintSummon.BUTTON).first,
                     Summon.xBesideButton(PaintSummon.BUTTON).second) -> "X beside the button"
                else -> "%.3f,%.3f".format(fx, fy)
            }
        }

        fun count(what: String) = taps().count { it == what }

        /** Where a tap landed, as a fraction of the frame. */
        fun at(i: Int): Pair<Double, Double> =
            cap.taps[i].first / Paint.W.toDouble() to cap.taps[i].second / Paint.H.toDouble()

        private fun near(fx: Double, fy: Double, wantX: Double, wantY: Double) =
            kotlin.math.abs(fx - wantX) < 0.02 && kotlin.math.abs(fy - wantY) < 0.02

        companion object {
            const val GENERAL_FX = 0.162
            const val GENERAL_FY = 0.135
        }
    }

    // The screens, painted once per case. Every one of them is a frame the real
    // readers answer on, which the first case is about.
    private fun main() = Paint.mainScreen()
    private fun mode(n: Int) = PaintSummon.modeScreen(n)
    private fun result() = PaintSummon.resultScreen()
    private fun button() = PaintSummon.frame(button = PaintSummon.BUTTON)
    private fun red() = PaintSummon.frame(button = PaintSummon.BUTTON, red = true)
    private fun dialog() = PaintSummon.notEnough()
    private fun animation() = PaintSummon.animation()
    private fun general() = PaintSummon.frame(general = true)
    private fun buddy() = PaintSummon.frame(button = PaintSummon.BUTTON, general = true)
    private fun ads(n: Int) = PaintSummon.frame(button = PaintSummon.BUTTON, ads = n)
    private fun exitOnly() = PaintSummon.frame(exitX = true)

    private fun rig(vararg frames: Mat, settings: SummonSkill.Settings = SummonSkill.Settings(),
                    frozenSeconds: Double = SummonSkill.FROZEN_SECONDS) =
        Rig(settings, frozenSeconds, tick, *frames)

    // ------------------------------------------------------------------------

    @Test
    fun `the painted frames read as the screens they are painted as`() {
        val modeScreen = mode(1)
        assertEquals(Director.SUMMON, Director.classify(modeScreen).screen)
        assertEquals(Director.SUMMON, Director.classify(result()).screen)
        assertEquals(Director.NO_TICKETS, Director.classify(dialog()).screen)
        assertEquals(Director.MAIN, Director.classify(main()).screen)
        // What the director hands this skill, and what it answers to.
        val skill = SummonSkill(DirectorTest.FakeCapture(), { SummonSkill.Settings() })
        assertTrue(skill.worksOn(Director.SUMMON))
        assertFalse(skill.worksOn(Director.SUMMON_DIALOG))
        assertFalse(skill.worksOn(Director.NO_TICKETS))
        assertFalse(skill.worksOn(Director.MAIN))

        for (n in 1..3) assertEquals(n, Summon.modeDots(mode(n)), "the dots of mode $n")
        assertNotNull(Summon.summonButton(modeScreen))
        assertNotNull(Summon.exitButton(modeScreen))
        assertNull(Summon.summonButton(animation()))
        assertNull(Summon.exitButton(animation()))
        assertNotNull(Summon.generalTab(general()))
        assertNotNull(Summon.notEnoughTickets(dialog()))
        assertNotNull(Summon.dimmedSummonButton(dialog()))
        // The two ends of a mode, on the frames that carry them.
        assertFalse(Summon.priceIsRed(modeScreen, Summon.summonButton(modeScreen)!!))
        val redFrame = red()
        assertTrue(Summon.priceIsRed(redFrame, Summon.summonButton(redFrame)!!))
        // The counter the ad phase reads, all three values it ever shows.
        for (n in 0..2) assertEquals(n, Summon.adsLeft(ads(n)), "the ad counter at $n")

        // And what the frozen-screen case stands on: the same frame twice has
        // not moved, two phases of it have.
        val a = PaintSummon.modeScreen(1, phase = 1)
        val b = PaintSummon.modeScreen(1, phase = 2)
        assertEquals(0.0, Director.motion(a, PaintSummon.modeScreen(1, phase = 1)))
        assertTrue(Director.motion(a, b)!! > SummonSkill.MOVING_SHARE)
    }

    // -- the game's own dialog is what ends a mode ---------------------------

    @Test
    fun `the out-of-tickets dialog ends a mode`() {
        // Nothing on any of these frames is a ticket badge, and the mode still
        // ends: the count the loop reports is taps it sent, not a number it
        // read off the screen.
        val r = rig(button(), button(), dialog(), dialog(), button())
        val done = r.skill.spam(SummonSkill.SKILL)
        assertEquals(2, done)
        assertEquals(2, r.count("summon"))
        assertEquals(1, r.count("Close"))
        assertTrue(r.log.any { it.contains("not enough tickets left") }, r.log.toString())
    }

    @Test
    fun `a single frame of the dialog neither ends a mode nor is tapped at`() {
        // The dialog fades in, and on the frame before it is recognised the
        // button behind it is still bright: that tap lands on the backdrop and
        // dismisses the dialog, so a Close aimed off the one frame that did see
        // it arrives after it has gone. Where Close stands then is a card --
        // measured fx 0.373 fy 0.588, the fourth row of the reward grid -- and
        // a card opens a window with neither a summon button nor an X on it. A
        // live run stood in one for seven seconds.
        val r = rig(button(), dialog(), button(), button(), dialog(), dialog(), button())
        r.skill.spam(SummonSkill.SKILL)
        assertEquals(1, r.count("Close"))
        assertEquals(3, r.count("summon"))
    }

    @Test
    fun `a Close that is swallowed is sent again`() {
        // A swallowed tap wants another tap, not more patience: the quest
        // loop's reward window spent sixteen seconds proving that.
        val frames = Array(2 + SummonSkill.CLOSE_LOOKS) { dialog() } + arrayOf(button())
        val r = rig(*frames)
        r.skill.spam(SummonSkill.SKILL)
        assertEquals(2, r.count("Close"))
    }

    @Test
    fun `Close is not tapped for ever at a dialog that will not go`() {
        val r = rig(dialog())
        r.skill.spam(SummonSkill.SKILL)
        assertEquals(SummonSkill.CLOSE_TAPS_MAX, r.count("Close"))
        assertTrue(r.kept.contains("dialog_stuck"), r.kept.toString())
    }

    // -- the red price -------------------------------------------------------

    @Test
    fun `a red price ends a mode, and no tap is spent on it`() {
        // The game turns the price red the moment the draw costs more than is
        // in the bag, and it does that before any tap goes out. Reported from a
        // live run: the dialog that arrives after the tap went unrecognised --
        // a loading spinner over the button dropped its dimmed fill from 0.96
        // to 0.73 -- and the loop then hurry-tapped the dialog away and raised
        // it again for as long as anybody watched.
        val r = rig(button(), red(), red())
        val done = r.skill.spam(SummonSkill.SKILL)
        assertEquals(1, done)
        assertEquals(1, r.count("summon"))
        assertTrue(r.log.any { it.contains("the price above the button is red") }, r.log.toString())
    }

    @Test
    fun `a single red frame neither ends a mode nor is tapped at`() {
        // Same reasoning as DIALOG_FRAMES: never trust a single frame, and a
        // frame that may be the end of the mode is not tapped at while the
        // question is open -- if it was a misread the frame after it taps as
        // usual, and nothing is lost but 200 ms.
        val r = rig(red(), button(), button(), dialog(), dialog())
        val done = r.skill.spam(SummonSkill.SKILL)
        assertEquals(2, done)
        assertEquals(2, r.count("summon"))
    }

    // -- the limit and the Crest double tap ----------------------------------

    @Test
    fun `spam stops at the limit it was given`() {
        val r = rig(button(), settings = SummonSkill.Settings(maxPerMode = 2))
        val done = r.skill.spam(SummonSkill.SKILL)
        assertEquals(2, done)
        assertEquals(2, r.count("summon"))
        assertEquals(2, r.skill.summons)
    }

    @Test
    fun `the draw that reaches the limit is played out before the mode is left`() {
        // Live, 2026-09-23: the frame after the last paid tap still showed the
        // button, the wait took it for "back", and the next mode met the
        // reveal screen. The button has to go first, and the animation is
        // hurried like every other.
        val r = rig(button(), button(), animation(), animation(), button(),
                    settings = SummonSkill.Settings(maxPerMode = 1))
        val done = r.skill.spam(SummonSkill.SKILL)
        assertEquals(1, done)
        assertEquals(1, r.count("summon"), r.taps().toString())
        assertEquals(2, r.count("hurry"), r.taps().toString())
    }

    @Test
    fun `a Crest draw is two taps and one summon`() {
        // One to raise the confirmation dialog, one on the dialog's own yellow
        // button. Nothing here counts tickets, so this is the only thing that
        // knows a Crest draw is not one click.
        val r = rig(button(), settings = SummonSkill.Settings(maxPerMode = 2))
        val done = r.skill.spam(SummonSkill.CREST)
        assertEquals(2, done)
        assertEquals(4, r.count("summon"))
    }

    // -- the pictures that say the game has stopped answering ----------------

    @Test
    fun `spam stops at a picture that has not moved at all`() {
        val r = rig(button(), frozenSeconds = 3.0)
        val done = r.skill.spam(SummonSkill.SKILL)
        assertTrue(r.count("summon") > 0)
        assertEquals(r.count("summon"), done)
        assertTrue(r.log.any { it.contains("not one pixel has changed") }, r.log.toString())
        assertTrue(r.kept.contains("frozen"))
    }

    @Test
    fun `a screen that keeps changing is never called frozen`() {
        val r = rig(button(), frozenSeconds = 3.0)
        // Forty rounds of a moving screen, then the game's own word for it.
        r.scene { i ->
            if (i < 40) PaintSummon.frame(button = PaintSummon.BUTTON, phase = 1 + i % 3)
            else dialog()
        }
        val done = r.skill.spam(SummonSkill.SKILL)
        assertEquals(40, done)
        assertFalse(r.log.any { it.contains("not one pixel has changed") }, r.log.toString())
    }

    @Test
    fun `spam gives up when there is nothing to tap at all`() {
        val r = rig(animation(), frozenSeconds = 1e9)
        val done = r.skill.spam(SummonSkill.SKILL)
        assertEquals(0, done)
        assertTrue(r.log.any { it.contains("nothing to tap for") }, r.log.toString())
    }

    @Test
    fun `the draw animation is tapped along while it runs`() {
        val r = rig(animation(), animation(), animation(), dialog(), dialog())
        r.skill.spam(SummonSkill.SKILL)
        val hurry = r.taps().withIndex().filter { it.value == "hurry" }
        assertTrue(hurry.size >= 2, "${hurry.size} tap(s) while no button was on screen")
        // None of them lands near the button row: the tap that hurries an
        // animation goes where nothing opens.
        assertTrue(hurry.all { r.at(it.index).second <= 0.2 }, r.taps().toString())
    }

    @Test
    fun `a Crest round is never tapped along beside its dialog`() {
        // The confirmation dialog takes a moment to draw, and during that
        // moment there is no button on screen either -- a tap sent into that
        // gap lands beside the dialog and closes it. Live, that left the count
        // at 388 for three rounds and gave the mode up.
        val r = rig(animation(), animation(), animation(), dialog(), dialog())
        r.skill.spam(SummonSkill.CREST)
        assertEquals(0, r.count("hurry"))
    }

    // -- the main switch -----------------------------------------------------

    @Test
    fun `a stop ends spam without another tap`() {
        // On the PC this was the Stop button and F8, and the gate asked
        // wait_while_paused alone -- which answers only the pause question and
        // says "go on" whenever the skill is not paused. A stop that arrived
        // while it was running was therefore never seen, and the run could only
        // be ended by closing the console.
        val r = rig(button())
        r.on = false
        val done = r.skill.spam(SummonSkill.SKILL)
        assertEquals(0, done)
        assertTrue(r.cap.taps.isEmpty(), r.taps().toString())
        assertTrue(r.log.contains("stopped"), r.log.toString())
    }

    @Test
    fun `a stopped run that did spend still walks back to the main screen`() {
        // leave_summons must not consult the switch: by the time it runs the
        // switch is often already off -- that is one of the ways a run ends --
        // and walking out is the one step that still has to happen. It is
        // asked of a run that found General and therefore had something to
        // walk out of; a run that never spent has nothing open of its own and
        // taps nothing (see the cases under "every way out of run()").
        val r = rig(mode(1), exitOnly(), exitOnly(), main())
        r.on = false
        val outcome = r.skill.run()
        assertEquals(Result.STOPPED, outcome.result)
        assertTrue(r.count("X") >= 1, r.taps().toString())
    }

    // -- back to the mode screen ---------------------------------------------

    @Test
    fun `back to the mode screen aims at the X it has actually found`() {
        // Measured over every stored frame: on a result screen the offset
        // agrees with the X to four thousandths, on a mode screen it lands 0.10
        // across and 0.07 above it, in empty sky.
        val r = rig(result(), mode(1))
        assertTrue(r.skill.backToMain())
        assertEquals(listOf("X"), r.taps())
    }

    @Test
    fun `back to the mode screen falls back on the offset with no X on the frame`() {
        val r = rig(button(), mode(1))
        assertTrue(r.skill.backToMain())
        assertEquals(listOf("X beside the button"), r.taps())
    }

    @Test
    fun `back to the mode screen taps nothing where the banners are already up`() {
        // A mode that ends on the red price ends before a tap has gone out, so
        // the game never left the mode screen -- and the X standing there is the
        // one that closes Special Summon. A live run tapped it: Skill Card
        // Summon finished at 0 summons, the game went to the main screen, and
        // both modes after it were skipped with "mode banners not found".
        val r = rig(mode(1))
        assertTrue(r.skill.backToMain())
        assertTrue(r.cap.taps.isEmpty(), r.taps().toString())
    }

    // -- switching mode ------------------------------------------------------

    @Test
    fun `switching mode taps nothing at all where the banners are gone`() {
        // It used to walk back in here -- the Summon icon, then the General
        // tab -- and that walk is gone (SummonSkill, "getting in"). The
        // General tab it tapped stands on whichever Special Summon tab the
        // icon opened, and the player's rule of 2026-09-22 is that no tap
        // goes out on any of those. A frame without the banners is not a
        // screen to tap on at all, so the mode is skipped.
        val r = rig(main(), main(), general(), mode(1), mode(1))
        assertFalse(r.skill.gotoMode(SummonSkill.SKILL))
        assertTrue(r.cap.taps.isEmpty(), r.taps().toString())
        assertTrue(r.log.any { it.contains("cannot navigate") }, r.log.toString())
    }

    @Test
    fun `switching mode gives up on a summon screen with no banners on it`() {
        val r = rig(button())
        assertFalse(r.skill.gotoMode(SummonSkill.SKILL))
        assertTrue(r.cap.taps.isEmpty(), r.taps().toString())
        assertTrue(r.log.any { it.contains("cannot navigate") }, r.log.toString())
    }

    @Test
    fun `switching mode gives up after three tries`() {
        val r = rig(mode(1))
        assertFalse(r.skill.gotoMode(SummonSkill.CREST))
        assertEquals(SummonSkill.BANNER_TRIES, r.count("banner"))
    }

    @Test
    fun `switching mode walks the banners to the one it was asked for`() {
        val r = rig(mode(1), mode(2), mode(3))
        assertTrue(r.skill.gotoMode(SummonSkill.CREST))
        assertEquals(2, r.count("banner"))
    }

    // -- the ad phase --------------------------------------------------------

    @Test
    fun `the ad phase watches both free ads and never presses the yellow button`() {
        val r = rig(ads(2), ads(2),                    // read the counter, then aim
                    result(), result(), mode(1),       // the ad, the settled screen, back
                    ads(1), ads(1),
                    result(), result(), mode(1),
                    ads(0))
        r.skill.watchAds(SummonSkill.SKILL)
        assertEquals(2, r.count("View Ads"))
        assertEquals(2, r.skill.adsWatched)
        // Nothing is tapped while the ad runs: what is on screen then is
        // somebody else's creative, and a tap into it can follow a link.
        assertEquals(0, r.count("hurry"))
        // And never the yellow button -- it is a live 35x Summon on the result
        // screen the ad just opened, and pressing it would spend 30 real
        // tickets on top of a free round.
        assertEquals(0, r.count("summon"))
        assertTrue(r.log.any { it == "  ads done" }, r.log.toString())
    }

    @Test
    fun `the ad phase stops where the counter does not fall`() {
        val r = rig(ads(2), ads(2), result(), result(), mode(1), ads(2))
        r.skill.watchAds(SummonSkill.SKILL)
        assertEquals(1, r.count("View Ads"))
        assertEquals(0, r.skill.adsWatched)
        assertTrue(r.log.any { it.contains("the ad counter did not fall") }, r.log.toString())
    }

    @Test
    fun `Crest has no free ads and is not looked at for any`() {
        val r = rig(ads(2))
        r.skill.watchAds(SummonSkill.CREST)
        assertTrue(r.cap.taps.isEmpty())
        assertEquals(0, r.cap.grabs, "read a frame for a mode that has no ads")
    }

    // -- walking out ---------------------------------------------------------

    @Test
    fun `leaving closes the out-of-tickets dialog first`() {
        // The dialog dims the X out of the white mask exactly as it dims the
        // summon button, so this used to find nothing to tap and give up with
        // the game left standing on it (debug_summon/no_way_back_02.png).
        val r = rig(dialog(), exitOnly(), exitOnly(), main())
        assertTrue(r.skill.leaveSummons())
        assertEquals(1, r.count("Close"))
        assertEquals(1, r.count("X"))
    }

    @Test
    fun `leaving taps the X until the main screen is back`() {
        // A result screen, then the mode screen, then the game itself: the X
        // pops one screen, not all of them.
        val r = rig(exitOnly(), exitOnly(), main())
        assertTrue(r.skill.leaveSummons())
        assertEquals(2, r.count("X"))
        assertTrue(r.log.contains("back on the main screen"), r.log.toString())
    }

    @Test
    fun `leaving taps nothing on the main screen`() {
        val r = rig(main())
        assertTrue(r.skill.leaveSummons())
        assertTrue(r.cap.taps.isEmpty(), r.taps().toString())
    }

    @Test
    fun `leaving clicks nothing on a screen it cannot place`() {
        // No X and no auto button: a battle, a dialog, somebody else's ad. The
        // one wrong answer here is to start clicking at the corner anyway,
        // which is how the stray tap in debug_summon happened.
        val r = rig(animation())
        assertFalse(r.skill.leaveSummons())
        assertTrue(r.cap.taps.isEmpty(), r.taps().toString())
        assertTrue(r.kept.contains("nothing_to_tap"), r.kept.toString())
    }

    @Test
    fun `leaving gives up rather than tapping for ever`() {
        val r = rig(exitOnly())
        assertFalse(r.skill.leaveSummons())
        assertEquals(SummonSkill.EXIT_TAPS_MAX, r.count("X"))
        assertTrue(r.kept.contains("no_way_back"), r.kept.toString())
    }

    // -- every way out of run() ------------------------------------------------

    @Test
    fun `a run from the main screen opens Special Summon and spends on General`() {
        // The player's rule of 2026-09-23: the Summon icon, and General.
        // Here the icon lands on General, so nothing but the icon is tapped
        // on the way in -- General on two frames running is the arrival.
        val r = rig(main(), main(), mode(1), mode(1), mode(1), result(), result(), dialog(), dialog(),
                    result(), exitOnly(), main(),
                    settings = SummonSkill.Settings(support = false, crest = false,
                                                    watchAdsFirst = false))
        val outcome = r.skill.run()
        assertEquals(Result.DONE, outcome.result, r.log.toString())
        assertEquals("icon", r.taps().first())
        assertEquals(0, r.count("General"), r.taps().toString())
        assertEquals(2, r.skill.summons)
    }

    @Test
    fun `from Buddy the one tap on the way in is General`() {
        // Buddy: the tab pair with General in view at fx 0.162, a live yellow
        // button, and no mode dots. General is tapped, and nothing else.
        val r = rig(main(), main(), buddy(), mode(1), mode(1), mode(1), result(), result(),
                    dialog(), dialog(), result(), exitOnly(), main(),
                    settings = SummonSkill.Settings(support = false, crest = false,
                                                    watchAdsFirst = false))
        val outcome = r.skill.run()
        assertEquals(Result.DONE, outcome.result, r.log.toString())
        assertEquals(listOf("icon", "General"), r.taps().take(2), r.taps().toString())
        assertEquals(1, r.count("General"))
        assertEquals(0, r.cap.backs)
    }

    @Test
    fun `where General is not in view nothing is tapped and the page is left by the back key`() {
        // SP Support and Overdrive: no General tab in the carousel, a live
        // yellow button that draws for bought gems. The icon is the only tap;
        // the way out is the system's back key, and the step retires.
        val r = rig(main(), main(), *Array(SummonSkill.WALK_ROUNDS + 2) { button() }, main())
        val outcome = r.skill.run()
        assertEquals(Result.RETIRED, outcome.result)
        assertEquals(SummonSkill.RETIRE_NO_GENERAL, outcome.why)
        assertEquals(listOf("icon"), r.taps(), r.taps().toString())
        assertEquals(1, r.cap.backs, "one back key, then the main screen")
        assertTrue(r.kept.contains("no_general_in_view"), r.kept.toString())
    }

    @Test
    fun `a run on a Special Summon screen without the banners retires too`() {
        // Started anywhere but the plain main screen -- here a foreign tab --
        // run() does not walk in from there: nothing is tapped at all.
        val r = rig(button())
        val outcome = r.skill.run()
        assertEquals(Result.RETIRED, outcome.result)
        assertEquals(SummonSkill.RETIRE_NOT_MAIN, outcome.why)
        assertTrue(r.cap.taps.isEmpty(), r.taps().toString())
    }

    @Test
    fun `a run that found General spends and walks out`() {
        // The `work` case's frames with one General frame in front of them --
        // the look run() takes before it begins -- and the way home behind.
        val r = rig(mode(1), mode(1), result(), result(), dialog(), dialog(), result(),
                    exitOnly(), main(),
                    settings = SummonSkill.Settings(support = false, crest = false,
                                                    watchAdsFirst = false))
        val outcome = r.skill.run()
        assertEquals(Result.DONE, outcome.result)
        assertEquals(2, r.skill.summons)
        assertEquals(1, r.count("X"), r.taps().toString())
    }

    @Test
    fun `a run whose capture failed parks and taps nothing`() {
        // The Python case is a run that threw; here the one thing that throws
        // is the capture. It used to walk out afterwards, back when run()
        // opened Special Summon itself and could therefore have left it
        // standing. It opens nothing now, so there is nothing of its own to
        // close -- and a tap sent without a picture is the blind tap this
        // project does not send.
        val r = rig(main())
        r.scene { throw CaptureError("no frame") }
        val outcome = r.skill.run()
        assertEquals(Result.PARKED, outcome.result)
        assertTrue(outcome.why.startsWith("No picture of the screen"), outcome.why)
        assertTrue(r.cap.taps.isEmpty(), r.taps().toString())
    }

    // -- the seam ------------------------------------------------------------

    @Test
    fun `there is nothing to do with every mode switched off`() {
        val r = rig(mode(1))
        r.settings = SummonSkill.Settings(skill = false, support = false, crest = false)
        assertFalse(r.skill.hasBudget())
        r.settings = SummonSkill.Settings(skill = false, support = false, crest = true)
        assertTrue(r.skill.hasBudget())
    }

    @Test
    fun `work spends the enabled modes and leaves the player on the summon screen`() {
        // The seam itself: the director hands over the screen the player
        // opened, and the skill hands it back. No step to the mode screen after
        // the last enabled mode, and no walk home -- a tap that buys nothing
        // can still cost something.
        val r = rig(mode(1), result(), result(), dialog(), dialog(), result(),
                    settings = SummonSkill.Settings(support = false, crest = false,
                                                    watchAdsFirst = false))
        val outcome = r.skill.work(mode(1))
        assertEquals(Result.DONE, outcome.result)
        assertEquals(2, r.skill.summons)
        assertEquals(1, r.skill.modesDone)
        assertEquals(0, r.skill.modesSkipped)
        assertEquals(0, r.count("X"), "walked out of a screen it was asked to work")
        assertEquals(0, r.count("X beside the button"))
        assertTrue(r.log.contains("Support Digimon Summon: switched off, skipping"), r.log.toString())
    }

    /**
     * What the TODAY card is given is a pass's count and not the skill's
     * whole life. Nothing cleared these until 2026-09-22, and `Counted` adds
     * `lastCounts` up after every pass, so the second pass put the first
     * pass's summons on the card a second time.
     */
    @Test
    fun `a second pass counts only itself`() {
        val r = rig(mode(1), result(), result(), dialog(), dialog(), result(),
                    settings = SummonSkill.Settings(support = false, crest = false,
                                                    watchAdsFirst = false))
        r.skill.work(mode(1))
        assertEquals(mapOf("summons" to 2, "ads" to 0), r.skill.lastCounts)
        // The same skill again, with nothing switched on: a pass that drew
        // nothing hands over nothing.
        r.settings = SummonSkill.Settings(skill = false, support = false, crest = false,
                                          watchAdsFirst = false)
        r.skill.work(mode(1))
        assertEquals(mapOf("summons" to 0, "ads" to 0), r.skill.lastCounts)
        assertEquals(0, r.skill.modesDone)
    }

    @Test
    fun `a mode the banners will not reach is skipped, not given up on`() {
        val r = rig(mode(1), settings = SummonSkill.Settings(skill = false, support = true,
                                                            crest = false, watchAdsFirst = false))
        // The dots never move: Support cannot be reached, and it is the only
        // mode switched on.
        val outcome = r.skill.work(mode(1))
        assertEquals(Result.DONE, outcome.result)
        assertEquals(1, r.skill.modesSkipped)
        assertEquals(0, r.skill.modesDone)
    }

    // -- General, and nowhere else -------------------------------------------
    //
    // The player's rule of 2026-09-22. Special Summon is four tabs and only
    // General is this skill's; Buddy, SP Support and Overdrive hold draws
    // paid for in bought gems, and nothing on them is ever tapped -- not the
    // yellow button, not the tab that would lead back. Measured on four
    // frames off the phone (corpus/summon/tab_*.png): all four read as
    // Director.SUMMON, all four carry a live yellow button, and only General
    // answers Summon.modeDots. On SP Support that button is a 10x draw for
    // 3,000 gems against a purse of 2,500, and Summon.priceIsRed answers
    // false over it -- the brake does not fire there.

    @Test
    fun `the director is told there is nothing to do on any tab but General`() {
        val r = rig(mode(1))
        // A Special Summon screen with no mode banners on it: Buddy, SP
        // Support or Overdrive.
        assertFalse(r.skill.seesWork(Director.SUMMON, button())!!)
        assertTrue(r.log.any { it.contains("not the General page") }, r.log.toString())
        assertTrue(r.cap.taps.isEmpty(), r.taps().toString())
    }

    @Test
    fun `on General the two older rules decide, not the frame`() {
        // Null is "not from a frame": one work per visit and hasBudget are
        // the right questions there, and seesWork stands aside.
        val r = rig(mode(1))
        assertNull(r.skill.seesWork(Director.SUMMON, mode(1)))
        assertTrue(r.log.isEmpty(), r.log.toString())
    }

    @Test
    fun `the reason is said once, and again after General has been and gone`() {
        // The director asks this about once a second for as long as the
        // player is looking at the page, and a reason repeated once a second
        // is not a reason.
        val r = rig(mode(1))
        repeat(5) { r.skill.seesWork(Director.SUMMON, button()) }
        assertEquals(1, r.log.count { it.contains("not the General page") }, r.log.toString())
        r.skill.seesWork(Director.SUMMON, mode(1))
        r.skill.seesWork(Director.SUMMON, button())
        assertEquals(2, r.log.count { it.contains("not the General page") }, r.log.toString())
    }

    @Test
    fun `work parks on a tab that is not General and taps nothing`() {
        // seesWork has already refused by the time the director gets here, so
        // this is the belt to those braces -- and the one thing still standing
        // where work is called without the director in front of it.
        val r = rig(button())
        val outcome = r.skill.work(button())
        assertEquals(Result.PARKED, outcome.result)
        assertEquals(SummonSkill.PARK_NOT_GENERAL, outcome.why)
        assertTrue(r.cap.taps.isEmpty(), r.taps().toString())
    }
}
