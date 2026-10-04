package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A claim whose Reward window comes late, on painted frames and the real
 * readers (PLAN_ABSCHLUSS_1_3.md K11).
 *
 * The Poco's report of 2026-10-02, 18:22:17: the quest loop tapped step
 * 13's card, the first frame after it had no auto button, the tap at the
 * dead spot went out on it, the auto button was back on the next frame and
 * the card still read 30/30 -- "claimed, but the counter did not fall --
 * trying again next round". Then "Reward ... Tap to close" came and stood:
 * "not the plain main screen for 46 s, and no X on it to get out with --
 * leaving it alone", for four and a half minutes, until the player's own
 * screenshot moved the step on. The sheet has no X; [Dungeon.recognise]
 * calls it `reward_sheet` on both of the report's frames.
 *
 * Nothing here replaces a reader. [Game] hands out [PaintQuest]'s frames --
 * the card, the blink without the auto button, the sheet -- and a tap
 * changes the game only where the real game would change: the card opens
 * the window, the dead spot closes a sheet that stands, and does nothing on
 * any other frame. The card that follows the claim reads 1/2 rather than
 * 0/2 because the painted 0 is not the game's (PaintQuest's head); it is a
 * different card all the same, which is the claim's proof.
 */
class QuestLateRewardFlowTest {

    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    private enum class Shown { DONE, BLINK, SHEET, NEXT }

    /**
     * The game behind the frames. [afterCard] is what the screen shows,
     * one frame each, after the card was tapped; then [then] stands until
     * a tap at the dead spot closes it.
     */
    private class Game(val afterCard: List<Shown>, val then: Shown) {
        var state = Shown.DONE
        val queue = ArrayDeque<Shown>()
        var shown = Shown.DONE
        val taps = ArrayList<Pair<Shown, String>>()

        fun next(): Mat {
            shown = if (queue.isNotEmpty()) queue.removeFirst() else state
            return when (shown) {
                Shown.DONE -> PaintQuest.card(2, 2, name = "Bakemon")
                Shown.BLINK -> PaintQuest.blank()
                Shown.SHEET -> PaintQuest.rewardSheet()
                Shown.NEXT -> PaintQuest.card(1, 2, name = "DemiDevimon")
            }
        }

        fun tap(fx: Double) {
            val what = when (fx) {
                QuestSkill.DEAD_TAP[0] -> "dead"
                Quest.CARD_MID_FX -> "card"
                else -> "other $fx"
            }
            taps += shown to what
            if (what == "card" && shown == Shown.DONE && state == Shown.DONE) {
                queue.addAll(afterCard)
                state = then
            }
            if (what == "dead" && shown == Shown.SHEET) {
                queue.clear()
                state = Shown.NEXT
            }
        }
    }

    private class Loop(val game: Game, val clock: QuestSkillTest.Clock = QuestSkillTest.Clock(),
                       val said: MutableList<String> = ArrayList(),
                       val kept: MutableList<String> = ArrayList(),
                       set: QuestSkill.Settings = QuestSkill.Settings(step = 1))
        : QuestSkill(DirectorTest.FakeCapture(), { set },
                     lock = { _, _ -> }, log = { said += it }, keep = { _, tag -> kept += tag },
                     sleep = {}, now = { clock.read() }) {
        override fun grab(): Mat = game.next()
        override fun tap(img: Mat, fx: Double, fy: Double, anchor: Dungeon.Anchor) = game.tap(fx)
        fun round() {
            val img = grab()
            try {
                tick(img)
            } finally {
                img.release()
            }
        }
    }

    @Test
    fun `the painted frames read as the game's`() {
        val sheet = PaintQuest.rewardSheet()
        assertEquals(Dungeon.REWARD, Dungeon.recognise(sheet).state)
        assertEquals(null, Dungeon.autoButton(sheet))
        assertEquals(null, Quest.closeX(sheet), "the sheet has no X, as the game's has none")
        sheet.release()
        val done = PaintQuest.card(2, 2, name = "Bakemon")
        assertNotEquals(Dungeon.REWARD, Dungeon.recognise(done).state)
        val card = Quest.questCard(done)
        assertEquals(2 to 2, card?.ist to card?.ziel)
        done.release()
        val next = PaintQuest.card(1, 2, name = "DemiDevimon")
        val n = Quest.questCard(next)
        assertEquals(1 to 2, n?.ist to n?.ziel)
        next.release()
    }

    /**
     * The report's own order: the window comes after the claim has read the
     * card again and given up on it, and the next round finds the sheet.
     * Over two rounds and the minute after them: the sheet is closed in the
     * round that first sees it, the claim is counted and the step moves on,
     * and nothing is said about an X.
     */
    @Test
    fun `a reward window that comes after the claim gave up is closed the next round and counted`() {
        // The claim's own look at the card ends long before this many frames.
        val game = Game(listOf(Shown.BLINK) + List(4) { Shown.DONE }, then = Shown.DONE)
        val loop = Loop(game)
        loop.round()
        assertEquals(1, loop.step, "round 1: the counter did not fall, the step stays: ${loop.said}")
        assertEquals(0, loop.lastCounts["claimed"])

        // Between the rounds the window comes, and stands.
        game.state = Shown.SHEET
        game.queue.clear()
        var rounds = 0
        while (rounds < 40 && loop.step == 1) {
            loop.clock.jump(QuestSkill.TICK)
            loop.round()
            rounds += 1
        }
        assertFalse(loop.said.any { it.contains("no X on it to get out with") },
                    "the sheet was left standing: ${loop.said}")
        assertEquals(1, rounds, "closed in the round that first saw it: ${loop.said}")
        assertEquals(2, loop.step, "the claim counted, the step moved on: ${loop.said}")
        assertEquals(1, loop.lastCounts["claimed"])
        assertEquals(Shown.NEXT, game.state)
        assertTrue(game.taps.filter { it.second == "dead" }.none { it.first == Shown.DONE || it.first == Shown.NEXT },
                   "no tap at the dead spot on the main screen once the sheet was the question: ${game.taps}")
        assertEquals(1, game.taps.count { it.second == "card" }, "the card is tapped once: ${game.taps}")
    }

    /**
     * The window a few frames late, while the claim is still looking: the
     * claim closes it itself, in its own round, and counts it.
     */
    @Test
    fun `a reward window that comes while the claim still looks is closed in the same round`() {
        val game = Game(listOf(Shown.BLINK, Shown.DONE, Shown.DONE, Shown.DONE), then = Shown.SHEET)
        val loop = Loop(game)
        loop.round()
        var rounds = 0
        while (rounds < 40 && loop.step == 1) {
            loop.clock.jump(QuestSkill.TICK)
            loop.round()
            rounds += 1
        }
        assertFalse(loop.said.any { it.contains("no X on it to get out with") },
                    "the sheet was left standing: ${loop.said}")
        assertEquals(0, rounds, "closed by the claim's own round: ${loop.said}")
        assertEquals(2, loop.step)
        assertEquals(1, loop.lastCounts["claimed"])
        assertTrue(loop.said.none { it.contains("did not fall") }, "${loop.said}")
    }
}
