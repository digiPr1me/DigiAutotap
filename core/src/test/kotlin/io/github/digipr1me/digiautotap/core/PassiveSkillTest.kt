package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Mat
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * test_passive_flow.py in Kotlin: the same script, the same painted frames.
 *
 * Two halves, as the Python suite has them. The readers are given painted
 * frames -- a counter, a bond bubble, the Partner window's button pair --
 * and have to answer the same way at two window sizes. The skill itself is
 * given a fake screen and its taps are counted, which is what catches a
 * toggle being pressed twice or a bubble being tapped off a single frame.
 *
 * One difference from the Python suite, and it makes the cases stronger:
 * Python patches `P.holo_counter`, `P.bond_bubble` and `P.partner_menu` for
 * the flow half and hands the bot a bot-shaped stub. Here the frames are
 * painted for every round and go through the real readers, so a round that
 * taps is a round that read a painted bubble off a painted screen. What is
 * faked is the capture and the clock, nothing else.
 *
 * The cases that read real screenshots out of `debug_passive/` and
 * `debug-queue/` are not here. Those folders are not in the repository, and
 * the readers they hold to answer for themselves in PassiveOracleTest over
 * the whole corpus; this file is the loop's.
 */
class PassiveSkillTest {

    // ------------------------------------------------------------------------
    // The fake screen
    // ------------------------------------------------------------------------
    /**
     * What the skill sees, and what it did about it: test_passive_flow.py's
     * `Screen`, painting instead of patching. Every round is a fresh frame
     * built from these fields, which is also how the real thing works.
     */
    class Screen(val w: Int = 765, val h: Int = 1390) {
        /** "" paints no counter at all. */
        var counterText: String = ""
        var bubbleAt: Pair<Double, Double>? = null
        var partner = false
        /** The auto button, which is the proof that nothing is drawn over the screen. */
        var clear = true
        var failed = false
        var clock = 1000.0
        var on = true

        val cap = DirectorTest.FakeCapture()
        val messages = ArrayList<String>()
        val kept = ArrayList<String>()
        // A round reads no setting at all any more: "Watch the main screen",
        // "Collect the bond token" and "Show everything it sees" are gone
        // with the page's Auto Spend half, and the one key left --
        // `passive_all_digimon` -- is BondTourSkill's question, not this
        // round's. The map stays because the tour's test paints through this
        // Screen too.
        val flags = HashMap<String, Boolean>()

        fun skill() = PassiveSkill(
            cap, { key, default -> flags[key] ?: default },
            log = { messages += it }, now = { clock }, on = { on },
            keep = { _, tag -> kept += tag })

        /** One frame of whatever the fields say is on the screen. */
        fun frame(): Mat {
            val img = PaintPassive.blank(w, h)
            if (partner) {
                // A window dims the auto button away, which is the whole of
                // what tells a covered screen from a clear one.
                PaintPassive.partner(img)
                return img
            }
            if (clear) PaintPassive.autoButton(img)
            if (counterText.isNotEmpty()) PaintPassive.counter(img, counterText)
            bubbleAt?.let { PaintPassive.bubble(img, it.first, it.second) }
            if (failed) PaintPassive.stageFailed(img)
            return img
        }

        /** One round on a fresh frame, the way the director calls one. */
        fun tick(skill: PassiveSkill) {
            val img = frame()
            try {
                skill.work(img)
            } finally {
                img.release()
            }
        }

        /** Every tap so far, back in the fractions it was aimed with. */
        fun taps(): List<DoubleArray> {
            val r = Dungeon.gameRectWh(w, h)
            return cap.taps.map { (x, y) ->
                doubleArrayOf((x - r.x0) / r.gw.toDouble(), (y - r.y0) / r.gh.toDouble())
            }
        }

        /**
         * A tap goes out in whole pixels, so it comes back a pixel's worth
         * off the fraction it was aimed with. Half a pixel of the frame is
         * what "the same place" means here, and it is four hundred times
         * tighter than the gap between any two places this skill taps.
         */
        fun aimedAt(tap: DoubleArray, fx: Double, fy: Double): Boolean {
            val r = Dungeon.gameRectWh(w, h)
            return abs(tap[0] - fx) <= 1.0 / r.gw && abs(tap[1] - fy) <= 1.0 / r.gh
        }

        /**
         * Taps that landed on the auto button -- the disc at 0.361/0.765,
         * and nothing else this skill ever taps is within a tenth of it.
         * There must never be one: it is the proof that the screen is clear
         * and not a thing to press, which is the whole of what Auto Spend
         * leaving took with it.
         */
        fun presses(): List<DoubleArray> =
            taps().filter { abs(it[1] - 0.765) < 0.03 && abs(it[0] - 0.361) < 0.05 }

        /** Taps aimed where a bubble at [BUBBLE] says the figure is. */
        fun figureTaps(): List<DoubleArray> {
            val (fx, fy) = figureUnder(BUBBLE)
            return taps().filter { aimedAt(it, fx, fy) }
        }

        fun closeTaps(): List<DoubleArray> =
            taps().filter { aimedAt(it, PassiveSkill.PARTNER_CLOSE[0], PassiveSkill.PARTNER_CLOSE[1]) }

        fun backs() = cap.backs

        fun clearActions() {
            cap.taps.clear()
            cap.backs = 0
        }

        fun said(part: String) = messages.any { part in it }
    }

    companion object {
        /** Where every case here paints the bubble, and the only place Screen counts figure taps for. */
        private val BUBBLE = 0.51 to 0.34

        /** Where a bubble at this point puts the figure, as the skill computes it. */
        fun figureUnder(bubble: Pair<Double, Double>): Pair<Double, Double> =
            bubble.first + PassiveSkill.BOND_TAP_OFF[0] to bubble.second + PassiveSkill.BOND_TAP_OFF[1]
    }

    // ------------------------------------------------------------------------
    // The counter
    // ------------------------------------------------------------------------
    @Test
    fun `the hologram counter is read off a painted main screen`() {
        for ((w, h) in PaintPassive.SIZES) {
            val img = PaintPassive.mainScreen(w, h, "12,305 / 10")
            assertEquals(12305, Passive.holoCounter(img), "at $w x $h")
            img.release()
        }

        var img = PaintPassive.mainScreen(765, 1390, "1,000 / 10")
        assertEquals(1000, Passive.holoCounter(img), "the comma is not a digit")
        img.release()

        img = PaintPassive.mainScreen(765, 1390, "0 / 10")
        assertEquals(0, Passive.holoCounter(img), "a counter at zero reads as zero")
        img.release()

        img = PaintPassive.mainScreen(765, 1390, "77,605 / 20")
        assertEquals(77605, Passive.holoCounter(img), "the allowance is not read")
        img.release()

        img = PaintPassive.autoButton(PaintPassive.blank(765, 1390))
        PaintPassive.counter(img, "12,305 / 10", junk = true)
        assertEquals(12305, Passive.holoCounter(img), "a second row does not join the number")
        img.release()

        img = PaintPassive.autoButton(PaintPassive.blank(765, 1390))
        PaintPassive.counter(img, "12,305 / 10", clip = true)
        assertNull(Passive.holoCounter(img), "a clipped digit is refused whole")
        img.release()

        img = PaintPassive.autoButton(PaintPassive.blank(765, 1390))
        assertNull(Passive.holoCounter(img), "no counter, no number")
        img.release()
    }

    // ------------------------------------------------------------------------
    // The bubble, and where it may be
    // ------------------------------------------------------------------------
    @Test
    fun `the bond bubble is found in the middle of the field and nowhere else`() {
        for ((w, h) in PaintPassive.SHAPE_SIZES) {
            val img = PaintPassive.mainScreen(w, h, "", bubble = true)
            val found = Passive.bondBubble(img)
            assertNotNull(found, "no bubble at $w x $h")
            assertTrue(abs(found.fx - 0.51) < 0.02, "at ${found.fx} / ${found.fy}, $w x $h")
            img.release()
        }

        var img = PaintPassive.mainScreen(765, 1390, "")
        assertNull(Passive.bondBubble(img), "no bubble on a plain screen")
        img.release()

        img = PaintPassive.bubble(PaintPassive.autoButton(PaintPassive.blank(765, 1390)),
                                  fx = 0.90, fy = 0.34)
        assertNull(Passive.bondBubble(img), "a bubble outside the band is not one")
        img.release()
    }

    @Test
    fun `the middle of the field is where the token is drawn, and the icons are not in it`() {
        // Every sighting there is, over the whole stored collection and both
        // frame sources, against the four places the helper has been seen to
        // tap by mistake.
        for ((name, p) in listOf(
                "the leftmost sighting" to (0.4814 to 0.3362),
                "the rightmost sighting" to (0.5140 to 0.3563),
                "the highest sighting" to (0.4909 to 0.3170),
                "the lowest sighting" to (0.5069 to 0.4001))) {
            assertTrue(Passive.inMiddle(p.first, p.second), "$name is in the middle")
        }
        for ((name, p) in listOf(
                "the Daily Bonus wheel" to (0.6922 to 0.2798),
                "the Events icon" to (0.6940 to 0.2268),
                "the outer icon column" to (0.7899 to 0.1677),
                "a white and turquoise Digimon" to (0.3017 to 0.5263))) {
            assertTrue(!Passive.inMiddle(p.first, p.second), "$name is not in the middle")
        }

        // And the rule is applied where the reader answers. Painted inside the
        // search band but outside the middle -- 0.66, the only place that is
        // both -- at every shape, the one the helper really runs on included.
        for ((w, h) in PaintPassive.SHAPE_SIZES + listOf(1080 to 1920)) {
            for (fx in listOf(0.66)) {
                val img = PaintPassive.bubble(
                    PaintPassive.autoButton(PaintPassive.blank(w, h)), fx = fx, fy = 0.34)
                val said = ArrayList<String>()
                assertNull(Passive.bondBubble(img, said::add),
                           "a bubble at $fx is refused at $w x $h")
                assertEquals(1, said.size, "and it says where it was: $said")
                assertTrue("middle" in said[0], said[0])
                img.release()
            }
        }

        // A real one still passes with the log attached, and says nothing.
        val img = PaintPassive.mainScreen(765, 1390, "", bubble = true)
        val said = ArrayList<String>()
        assertNotNull(Passive.bondBubble(img, said::add))
        assertTrue(said.isEmpty(), "a bubble in the middle is not talked about: $said")
        img.release()
    }

    @Test
    fun `the Daily Bonus wheel is not a bubble, wherever it is drawn`() {
        for ((w, h) in PaintPassive.SHAPE_SIZES + listOf(1080 to 1920)) {
            var img = PaintPassive.wheel(PaintPassive.autoButton(PaintPassive.blank(w, h)))
            assertNull(Passive.bondBubble(img), "the wheel is a bubble at $w x $h")
            img.release()
            // A band keeps out an impostor whose place is known and says
            // nothing about one that turns up somewhere else.
            img = PaintPassive.wheel(PaintPassive.autoButton(PaintPassive.blank(w, h)),
                                     fx = 0.50, fy = 0.34)
            assertNull(Passive.bondBubble(img), "the wheel in the middle at $w x $h")
            img.release()
            img = PaintPassive.wheel(PaintPassive.autoButton(PaintPassive.blank(w, h)),
                                     fx = 0.50, fy = 0.34, solid = true)
            assertNull(Passive.bondBubble(img), "a solid wheel in the middle at $w x $h")
            img.release()
        }
    }

    @Test
    fun `a bubble is the partner's by the HP bar it hangs off`() {
        for ((w, h) in PaintPassive.SHAPE_SIZES + listOf(1080 to 1920, 1080 to 2400)) {
            // Wherever the slot stands: the 9:16 frames' 0.48 to 0.51, the
            // bonus event's 0.57 to 0.61, the four at 0.42 and the phone's.
            for (fx in listOf(0.42, 0.51, 0.59, 0.61)) {
                val img = PaintPassive.bubble(PaintPassive.autoButton(PaintPassive.blank(w, h)), fx = fx, fy = 0.36)
                val found = Passive.bondBubble(img)
                assertNotNull(found, "no bubble at $fx at $w x $h")
                assertEquals(fx, found.fx, 0.01, "at $w x $h")
                img.release()
            }
            // The same white and cyan with no bar under it is an attack
            // effect or a window's icon, not the token.
            val img = PaintPassive.bubble(PaintPassive.autoButton(PaintPassive.blank(w, h)), bar = false)
            assertNull(Passive.bondBubble(img), "a bubble with no bar is refused at $w x $h")
            img.release()
        }
    }

    // ------------------------------------------------------------------------
    // Where the figure is
    // ------------------------------------------------------------------------
    @Test
    fun `the aim follows the bubble by the measured offset`() {
        // Every real sighting there is: the 9:16 frames at fx 0.48 to 0.51,
        // fy 0.32 to 0.40, and the phone's at 0.59 to 0.61, fy 0.39 to 0.41.
        for ((fx, fy) in listOf(0.51 to 0.34, 0.4814 to 0.317, 0.509 to 0.40,
                                0.590 to 0.391, 0.608 to 0.41)) {
            val got = PassiveSkill.figureFromBubble(Passive.Bubble(fx, fy, 0.05, 0.05, 0.4))
            assertNotNull(got, "no aim from a bubble at $fx / $fy")
            assertEquals(fx + PassiveSkill.BOND_TAP_OFF[0], got[0], 1e-9, "fx from $fx / $fy")
            assertEquals(fy + PassiveSkill.BOND_TAP_OFF[1], got[1], 1e-9, "fy from $fx / $fy")
        }
        // The offset is below and to the left: the figure stands under the
        // bar the bubble hangs off.
        assertTrue(PassiveSkill.BOND_TAP_OFF[0] < 0 && PassiveSkill.BOND_TAP_OFF[1] > 0)
    }

    @Test
    fun `the tap fence and the chat line bound where an impostor can send the tap`() {
        val (lo, hi, top, bottom) = PassiveSkill.BOND_TAP_LIMITS.toList()
        assertTrue(hi < 0.6922 - 0.10, "the fence ends at $hi, the icon column is at 0.6922")
        // Every bubble the middle rule lets through aims inside the fence's
        // sides, so the sides never refuse a real one.
        val (mx0, mx1, my0, _) = Passive.BOND_MIDDLE.toList()
        for (fx in listOf(mx0, mx1)) {
            val aim = PassiveSkill.figureFromBubble(Passive.Bubble(fx, my0, 0.05, 0.05, 0.4))
            assertNotNull(aim, "a bubble at the middle's edge, $fx / $my0, is refused")
            assertTrue(lo <= aim[0] && aim[0] <= hi && top <= aim[1] && aim[1] <= bottom)
        }
        // The two impostors that once put the tap on the Buddy, at fy 0.459
        // and 0.477, are refused by the bottom of the fence now, and so is
        // anything lower; the highest real bubble, fy 0.41, is not.
        assertNull(PassiveSkill.figureFromBubble(Passive.Bubble(0.51, 0.459, 0.05, 0.05, 0.4)))
        assertNull(PassiveSkill.figureFromBubble(Passive.Bubble(0.51, 0.477, 0.05, 0.05, 0.4)))
        assertNotNull(PassiveSkill.figureFromBubble(Passive.Bubble(0.60, 0.41, 0.05, 0.05, 0.4)))
        // Outside the field is no place: the white and turquoise Digimon at
        // the left (0.30) and the icon column (0.69) both send the aim out.
        assertNull(PassiveSkill.figureFromBubble(Passive.Bubble(0.30, 0.34, 0.05, 0.05, 0.4)))
        assertNull(PassiveSkill.figureFromBubble(Passive.Bubble(0.6922, 0.34, 0.05, 0.05, 0.4)))

        // No aim from a bubble the fence admits lands on the chat bar: the
        // fence's bottom, 0.52, is above the bar's top, 0.570.
        for (bubbleY in listOf(0.26, 0.40, 0.444)) {
            val aim = PassiveSkill.figureFromBubble(Passive.Bubble(0.51, bubbleY, 0.05, 0.05, 0.4))
            assertNotNull(aim, "an aim from fy $bubbleY")
            assertTrue(!PassiveSkill.onChatRow(aim[0], aim[1]), "an aim from fy $bubbleY")
        }
        // The zone covers where the game draws the bar, and nothing above it.
        assertTrue(PassiveSkill.onChatRow(0.30, 0.5853) && PassiveSkill.onChatRow(0.30, 0.6140) &&
                   PassiveSkill.onChatRow(0.1045, 0.60) && PassiveSkill.onChatRow(0.6026, 0.60),
                   "the chat line covers where the game draws it")
        assertTrue(!PassiveSkill.onChatRow(0.30, 0.50), "and nothing above it")
    }

    // ------------------------------------------------------------------------
    // The Partner window
    // ------------------------------------------------------------------------
    @Test
    fun `the Partner window is recognised by its button pair`() {
        for ((w, h) in PaintPassive.SHAPE_SIZES) {
            val img = PaintPassive.partner(PaintPassive.blank(w, h))
            assertNotNull(Passive.partnerMenu(img), "no Partner window at $w x $h")
            img.release()
        }
        val img = PaintPassive.mainScreen(765, 1390, "")
        assertNull(Passive.partnerMenu(img), "no Partner window on the main screen")
        img.release()
    }

    // ------------------------------------------------------------------------
    // The counter
    //
    // Three tests stood here: a falling counter is a running Auto Spend and
    // is left alone, a stopped one gets one press and then a growing gap, an
    // unreadable one is not a stall. All three were about pressing the auto
    // button, which no page on the phone could ask for and which has gone
    // with the Auto Spend name. What is left of the counter is that it is
    // read and said, which `the counter is read on every clear round` below
    // holds it to.
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // The token
    // ------------------------------------------------------------------------
    @Test
    fun `the first sighting taps the figure, and not again before the game has answered`() {
        val s = Screen()
        val skill = s.skill()
        s.bubbleAt = BUBBLE
        s.tick(skill)
        // No waiting for a second frame: the character can die with the
        // bubble up, and then there is nothing left to collect.
        assertEquals(1, s.taps().size, "the first sighting did not tap: ${s.messages}")
        val (fx, fy) = figureUnder(BUBBLE)
        assertTrue(s.aimedAt(s.taps()[0], fx, fy), "tapped at ${s.taps()[0].toList()}")

        // Two seconds between rounds is faster than the game clears the
        // bubble, so a tap is not repeated straight away -- that would be a
        // tap at a token already collected, and it opens the Partner window.
        s.clock += Director.BEAT_MAIN
        s.tick(skill)
        assertEquals(1, s.taps().size, "a second tap before the game had answered")
        s.clock += PassiveSkill.BOND_RETRY
        s.tick(skill)
        assertEquals(2, s.taps().size, "no tap once the game had had its moment")
    }

    @Test
    fun `the bubble gone counts as collected once it has stayed gone`() {
        val s = Screen()
        val skill = s.skill()
        s.bubbleAt = BUBBLE
        val tapAt = s.clock
        s.tick(skill)
        assertEquals(PassiveSkill.DID_BOND, skill.seen.did)
        assertEquals(0, skill.lastCounts["tokens"], "a tap is not a token on the card")
        s.bubbleAt = null
        // Gone on the next look is what a dying Digimon looks like too, so
        // nothing is said yet, and the card gets nothing yet.
        s.clock += Director.BEAT_WATCH
        s.tick(skill)
        assertFalse(s.said("collected"), s.messages.toString())
        assertNull(skill.seen.did)
        // Fast looks up to the one at exactly BOND_WATCH after the tap.
        while (s.clock < tapAt + PassiveSkill.BOND_WATCH) {
            s.clock += Director.BEAT_WATCH
            s.tick(skill)
        }
        assertTrue(s.said("collected"), s.messages.toString())
        assertEquals(PassiveSkill.DID_BOND_GONE, skill.seen.did)
        assertEquals(1, skill.lastCounts["tokens"])
        // Said once: the rounds after it are quiet.
        s.clearActions()
        s.messages.clear()
        s.clock += Director.BEAT_MAIN
        s.tick(skill)
        assertFalse(s.said("collected"), s.messages.toString())
        assertNull(skill.seen.did)
    }

    /**
     * Measured live on 2026-09-21 at 23:50: tapped at :01, the bubble still
     * up at :05, gone at :07, back at :09, tapped, gone for good at :11. The
     * Digimon had died under the first tap and come back with its token,
     * and at the slow beat the second tap was eight seconds behind the first.
     */
    @Test
    fun `a bubble the Digimon's death took away is looked for fast and tapped again when it is back`() {
        val s = Screen()
        val skill = s.skill()
        assertNull(skill.beat(), "nothing seen yet: the director's own beat")
        s.bubbleAt = BUBBLE
        s.tick(skill)
        assertEquals(1, s.figureTaps().size)
        assertEquals(Director.BEAT_WATCH, skill.beat(), "after a tap the round asks for the fast beat")
        // Dead: the bubble is gone for two seconds.
        s.bubbleAt = null
        repeat(4) {
            s.clock += Director.BEAT_WATCH
            s.tick(skill)
            assertEquals(Director.BEAT_WATCH, skill.beat(), "still fast while the watch runs")
        }
        assertFalse(s.said("collected"), "gone for two seconds is not collected: ${s.messages}")
        // Back with the token, 2.5 s after the tap: seen at once, tapped as
        // soon as BOND_RETRY allows, which is a second and a half later.
        s.bubbleAt = BUBBLE
        s.clock += Director.BEAT_WATCH
        s.tick(skill)
        s.clock += Director.BEAT_WATCH
        s.tick(skill)
        assertEquals(1, s.figureTaps().size, "not before BOND_RETRY: ${s.messages}")
        s.clock += 2 * Director.BEAT_WATCH
        val tapAt = s.clock
        s.tick(skill)
        assertEquals(2, s.figureTaps().size, "tapped again once the game had had its moment: ${s.messages}")
        // Gone for good this time.
        s.bubbleAt = null
        while (s.clock < tapAt + PassiveSkill.BOND_WATCH) {
            s.clock += Director.BEAT_WATCH
            s.tick(skill)
        }
        assertTrue(s.said("collected"), s.messages.toString())
        assertNull(skill.beat(), "the watch is over: the director's own beat again")
        assertEquals(2, s.figureTaps().size)
    }

    @Test
    fun `the fast beat is asked for on the clear main screen only`() {
        // A window the tap opened is closed at the slow beat: partnerRound
        // counts rounds, and its third is the back key.
        val s = Screen()
        val skill = s.skill()
        s.bubbleAt = BUBBLE
        s.tick(skill)
        assertEquals(Director.BEAT_WATCH, skill.beat())
        s.partner = true
        s.clock += Director.BEAT_WATCH
        s.tick(skill)
        assertNull(skill.beat(), "a window over the screen: the slow beat")
        assertEquals(0, s.backs())
    }

    @Test
    fun `a bubble that will not go is left alone rather than hammered`() {
        // It drifts a pixel or two between frames, so the counting must not
        // start over every time it moves.
        val s = Screen()
        val skill = s.skill()
        for (n in 0 until 20) {
            s.bubbleAt = (0.51 + 0.004 * (n % 3)) to (0.34 - 0.004 * (n % 2))
            s.clock += maxOf(Director.BEAT_MAIN, PassiveSkill.BOND_RETRY)
            s.tick(skill)
        }
        assertEquals(PassiveSkill.BOND_TRIES, s.taps().size,
                     "${s.taps().size} taps at a bubble that will not go")
        assertTrue(s.said("leaving it alone until it goes"), s.messages.toString())
    }

    @Test
    fun `a low bubble is collected like any other, and no tap lands on the chat line`() {
        // A bubble low enough that the old aim landed behind the bar bought
        // nothing but an open chat window -- and this skill does not close
        // windows the player might have opened. The aim is a place now, a
        // fifth of the picture above the bar.
        val s = Screen()
        val skill = s.skill()
        repeat(8) {
            s.bubbleAt = 0.51 to 0.44
            s.clock += maxOf(Director.BEAT_MAIN, PassiveSkill.BOND_RETRY)
            s.tick(skill)
        }
        assertEquals(PassiveSkill.BOND_TRIES, s.taps().size, "${s.taps().size} taps")
        assertTrue(s.taps().none { PassiveSkill.onChatRow(it[0], it[1]) },
                   "a tap on the chat line: ${s.taps().map { it.toList() }}")
    }

    @Test
    fun `a round taps at most once`() {
        // One tap a round, and it is the token's: a second would be aimed
        // with a picture taken before the first one landed. The round used
        // to have a second thing it could do here -- press the auto button
        // for a counter that had stopped -- and this test was what held the
        // two to one tap between them.
        val s = Screen()
        val skill = s.skill()
        s.counterText = "5,000 / 10"
        s.tick(skill)
        s.clock += Director.BEAT_MAIN
        s.clearActions()
        s.bubbleAt = BUBBLE
        s.tick(skill)
        assertEquals(1, s.taps().size, "${s.taps().size} actions in one round")
        assertTrue(s.figureTaps().size == 1, "the token comes first: ${s.taps().map { it.toList() }}")
    }

    // ------------------------------------------------------------------------
    // Screens that must not be touched
    // ------------------------------------------------------------------------
    @Test
    fun `nothing is touched with a window over the screen or the red banner up`() {
        // The back key counts here as much as the taps do.
        for ((name, set) in listOf<Pair<String, (Screen) -> Unit>>(
                "a window over the screen" to { it.clear = false },
                "the red banner up" to { it.failed = true })) {
            val s = Screen()
            set(s)
            s.counterText = "5,000 / 10"
            s.bubbleAt = BUBBLE
            val skill = s.skill()
            repeat(8) {
                s.clock += Director.BEAT_MAIN
                s.tick(skill)
            }
            assertEquals(0, s.taps().size, "$name: ${s.taps().size} tap(s)")
            assertEquals(0, s.backs(), "$name: ${s.backs()} back key(s)")
        }
    }

    @Test
    fun `a window this did not open is left alone`() {
        // It happened in a live session, once to a window that was not even
        // this one.
        val s = Screen()
        s.partner = true
        val skill = s.skill()
        repeat(8) {
            s.clock += Director.BEAT_MAIN
            s.tick(skill)
        }
        assertEquals(0, s.taps().size, "${s.taps().size} tap(s)")
        assertEquals(0, s.backs(), "${s.backs()} back key(s)")
        assertTrue(s.said("did not open"), s.messages.toString())
    }

    @Test
    fun `the window its own tap opened is tapped away from above, not with the back key`() {
        val s = Screen()
        s.bubbleAt = BUBBLE
        val skill = s.skill()
        s.tick(skill)                       // taps the figure, may open a window
        s.clearActions()
        s.bubbleAt = null
        s.partner = true
        s.clock += Director.BEAT_MAIN
        s.tick(skill)
        assertEquals(0, s.backs(), "the back key was used: with no dialog open it raises " +
            "\"Return to the title screen?\", and in a live run it did, three times")
        assertEquals(1, s.closeTaps().size, "${s.taps().size} tap(s): ${s.taps().map { it.toList() }}")

        // ... and only a window that will not go gets the back key.
        repeat(PassiveSkill.PARTNER_TAPS + 1) {
            s.clock += Director.BEAT_MAIN
            s.tick(skill)
        }
        assertTrue(s.backs() >= 1, "a window that will not go never got the back key")
    }

    @Test
    fun `ownership runs out, and a late window is left alone`() {
        // A window that turns up long after this skill's own tap is somebody
        // else's, whatever the recogniser makes of it. It is not extended by
        // anything, because an ownership that can be pushed forward drifts
        // onto whatever is on screen when the player finally lets go.
        val s = Screen()
        s.bubbleAt = BUBBLE
        val skill = s.skill()
        s.tick(skill)
        s.clearActions()
        s.bubbleAt = null
        s.partner = true
        s.clock += PassiveSkill.PARTNER_OWN + Director.BEAT_MAIN
        repeat(4) {
            s.clock += Director.BEAT_MAIN
            s.tick(skill)
        }
        assertEquals(0, s.taps().size, "${s.taps().size} tap(s)")
        assertEquals(0, s.backs(), "${s.backs()} back key(s)")
    }

    // ------------------------------------------------------------------------
    // The quiet rounds
    // ------------------------------------------------------------------------
    @Test
    fun `an unreadable counter on a screen that looks right eventually says so`() {
        val s = Screen()
        val skill = s.skill()
        repeat((2 * PassiveSkill.BLIND_AFTER / Director.BEAT_MAIN).toInt()) {
            s.clock += Director.BEAT_MAIN
            s.tick(skill)
        }
        assertTrue(s.said("not been readable"), s.messages.toString())
        assertTrue(s.kept.any { "counter" in it }, "and keeps the frame: ${s.kept}")
    }

    @Test
    fun `a blocked round keeps saying why, once a minute`() {
        // Silence is what made the red-background bug look like a helper that
        // had stopped.
        val s = Screen()
        s.failed = true
        s.counterText = "5,000 / 10"
        val skill = s.skill()
        repeat((2 * PassiveSkill.HEARTBEAT / Director.BEAT_MAIN).toInt()) {
            s.clock += Director.BEAT_MAIN
            s.tick(skill)
        }
        val beats = s.messages.filter { "still watching" in it }
        assertTrue(beats.size >= 2, "${beats.size} line(s) in ${2 * PassiveSkill.HEARTBEAT} s")
    }

    @Test
    fun `the counter is read on every clear round, and nothing is pressed for it`() {
        // It was once read only when the Auto Spend box was ticked, so a
        // player who wanted the bond token alone got "holograms could not be
        // read this round" for every round of the day. Now it is read
        // because the number is drawn there -- and a counter that has stood
        // still for a minute is still not a reason to tap anything.
        val s = Screen()
        s.counterText = "5,000 / 10"
        val skill = s.skill()
        repeat((60 / Director.BEAT_MAIN).toInt()) {
            s.clock += Director.BEAT_MAIN
            s.tick(skill)
        }
        assertTrue(s.said("holograms 5,000"), s.messages.toString())
        assertEquals(0, s.taps().size, "a stalled counter was tapped for")
        assertEquals(0, s.presses().size, "the auto button was pressed")
        // And no line a player reads says Auto Spend any more -- not the
        // log, not the heartbeat, not the status line.
        assertTrue("holograms 5,000" in skill.status(), skill.status())
        assertFalse("auto spend" in skill.status(), skill.status())
        assertFalse(s.said("auto spend"), s.messages.toString())
    }

    // ------------------------------------------------------------------------
    // The seam with the director
    // ------------------------------------------------------------------------
    @Test
    fun `the row's switch is the whole budget, and the main switch stops a round dead`() {
        val s = Screen()
        val skill = s.skill()
        assertTrue(skill.worksOn(Director.MAIN), "the main screen is this skill's")
        assertTrue(skill.worksOn(Director.PARTNER_WINDOW), "and the window its own tap opens")
        assertTrue(!skill.worksOn(Director.DUNGEON_LIST))

        // `passive_on` was a second switch in front of the row's own, and
        // both said the same thing. With it gone this answer is yes whatever
        // is in the settings file: what decides is `included`, which the
        // director asks beside this one.
        assertTrue(skill.hasBudget(), "the row's switch is the only one left")
        s.flags.clear()
        assertTrue(skill.hasBudget(), "nothing in the file may hold the round back")

        // The main switch is asked between two actions: a round that begins
        // with it off does nothing at all and says so with STOPPED.
        s.on = false
        s.bubbleAt = BUBBLE
        val img = s.frame()
        val outcome = skill.work(img)
        img.release()
        assertEquals(Result.STOPPED, outcome.result, "$outcome")
        assertEquals(0, s.taps().size, "a stopped round tapped")
    }

    @Test
    fun `a round never answers done, because the director asks again`() {
        val s = Screen()
        val skill = s.skill()
        s.counterText = "5,000 / 10"
        val img = s.frame()
        val outcome = skill.work(img)
        img.release()
        assertTrue(outcome.result != Result.DONE,
                   "a round that says done is a skill the director stops calling: $outcome")
        assertTrue(outcome.result != Result.PARKED, "a quiet round does not park the director: $outcome")

        // run() is the same round on a frame of its own.
        s.cap.scene = { s.frame() }
        val before = s.cap.grabs
        skill.run()
        assertEquals(before + 1, s.cap.grabs, "run() grabs exactly one frame")
    }

    @Test
    fun `the token is collected without asking, and the tour box is read elsewhere`() {
        // "Collect the bond token" is gone: a box that could switch the
        // feature off inside a feature that was already switched on. A
        // bubble on an included round is tapped for, and nothing is asked
        // first.
        val s = Screen()
        s.flags.clear()
        val skill = s.skill()
        s.bubbleAt = BUBBLE
        s.tick(skill)
        assertEquals(1, s.taps().size, "a bubble was seen and the figure was not tapped")

        // The bond tour is on the phone since the merge session, and it is
        // not this round: it is BondTourSkill, the next round-skill in the
        // director's list, and what starts it is this round's own answer.
        // So the box is not read here at all any more, nothing is said about
        // it, and what this round has to leave behind is the one sentence
        // the tour asks for.
        val t = Screen()
        t.flags[PassiveSkill.PASSIVE_ALL_DIGIMON] = true
        val tourSkill = t.skill()
        t.bubbleAt = BUBBLE
        t.tick(tourSkill)
        assertFalse(t.said("not on the phone yet"), t.messages.toString())
        assertEquals(PassiveSkill.DID_BOND, tourSkill.seen.did,
                     "the tour is started by this sentence and by nothing else")
        assertEquals(TourCollect.COLLECTED, tourSkill.seen.did,
                     "and the tour's own name for it is the same string")
    }
}
