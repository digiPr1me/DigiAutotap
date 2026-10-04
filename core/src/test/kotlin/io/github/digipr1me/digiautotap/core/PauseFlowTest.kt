package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every task goes on after the pause (PLAN_RELEASE_1_3.md B4, the player's
 * rule of 2026-09-30; notes/director.md, "A pass the switch paused goes on
 * where it stands" and its extension of that day): a pass the main switch
 * stops leaves the game where it stands -- no way home with its taps held
 * back, no "could not get back", no frame -- and goes on from there when the
 * switch comes back, the same pass: what it spent counts against the page's
 * numbers, and what it counts for the TODAY card is its own part.
 *
 * Here the Dungeons task on a game played by the clock, paused twice, in
 * both modes. The director's half -- the claim, the second look, the kinds
 * of task -- is DirectorTest's with its stand-ins; each other task's two
 * pauses are in its own test file.
 */
class PauseFlowTest {

    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    /**
     * One ticket card, played by the clock: the main screen, the list, the
     * card's panel with its "n/2", Attempt, a battle of [RUN] s that is won
     * or lost as [ends] says, a won run's Reward sheet that stands until it
     * is tapped (a ticket taken off), a lost run's way back to the panel
     * through "Now Loading" (nothing taken). The back key on the panel raises
     * the dungeon's own prompt, OK closes it to the list; the globe goes home.
     *
     * The main switch is the game's too: [offAt] turns it off at a moment of
     * the game's -- the start of a battle, a panel -- and every tap that
     * reaches the game while it is off is counted in [held], which is what
     * the service would have held back. [backAfter] turns it on again that
     * many seconds after [offTime], at a look of the skill's as the player's
     * tap on the dot would; [sheetRead] false makes the won run's sheet one
     * no reader names, as the daily dungeon's was live.
     */
    class TicketGame(var tickets: Int, val ends: List<String> = listOf(WON), val sheetRead: Boolean = true) {
        var t = 0.0
        var phase = "main"
        private var since = 0.0
        var on = true
        /** Turn the switch off when this says so; asked on every look. */
        var offAt: TicketGame.() -> Boolean = { false }
        var offTime: Double? = null
        var backAfter: Double? = null
        /** Every tap that reached the game or was held, with the game's time. */
        val tapTimes = ArrayList<Pair<String, Double>>()
        var runs = 0
        var won = 0
        val taps = ArrayList<String>()
        val held = ArrayList<String>()
        private var end = WON

        private fun go(p: String) { phase = p; since = t }

        private fun advance() {
            while (true) {
                val next: Pair<String, Double> = when (phase) {
                    "battle" -> (if (end == WON) "sheet" else "loading") to RUN
                    "loading" -> "panel" to LOADING
                    else -> break
                }
                if (t - since < next.second) break
                if (next.first == "sheet") { tickets -= 1; won += 1 }
                phase = next.first
                since += next.second
            }
            if (on && offAt()) { on = false; offTime = t }
            val off = offTime
            val back = backAfter
            if (!on && off != null && back != null && t >= off + back) on = true
        }

        fun state(): Dungeon.Recognition {
            advance()
            return when (phase) {
                "list" -> rec(Dungeon.LIST, karten = CARDS)
                "panel" -> rec(Dungeon.DIALOG, attempt = ATTEMPT, clear = CLEAR)
                "battle" -> rec(Dungeon.BATTLE)
                "sheet" -> if (sheetRead) rec(Dungeon.REWARD) else rec(Dungeon.UNKNOWN)
                "prompt" -> rec(Dungeon.EXIT, exitOk = OK)
                else -> rec(Dungeon.UNKNOWN)   // the main screen, "Now Loading"
            }
        }

        fun main(): Boolean { advance(); return phase == "main" }
        fun globe(): Boolean { advance(); return phase == "main" || phase == "list" }
        fun counter(): Int? { advance(); return if (phase == "panel") tickets else null }

        fun tap(was: String) {
            advance()
            tapTimes += was to t
            if (!on) { held += was; return }
            taps += was
            when (phase) {
                "main" -> if (was == "dungeon tab") go("list")
                "list" -> when (was) {
                    CARD -> go("panel")
                    "home button" -> go("main")
                }
                "panel" -> if (was == "Attempt" && tickets > 0) {
                    runs += 1
                    end = ends[minOf(runs, ends.size) - 1]
                    go("battle")
                }
                "sheet" -> if (was == "close the Reward sheet" || was.startsWith("neutral")) go("loading")
                "prompt" -> when (was) {
                    "OK, leave the party" -> go("list")
                    "Cancel" -> go("panel")
                }
            }
        }

        fun back() {
            advance()
            if (!on) { held += "back"; return }
            taps += "back"
            if (phase == "panel") go("prompt")
        }

        private fun rec(state: String, attempt: Dungeon.Button? = null, clear: Dungeon.Button? = null,
                        karten: List<Double> = emptyList(), exitOk: Dungeon.Button? = null) =
            Dungeon.Recognition(state, attempt, null, null, clear, emptyList(), emptyList(), karten,
                                null, partyVoll = 0, exitOk = exitOk,
                                exitKind = if (exitOk != null) "party" else null)

        companion object {
            const val WON = "won"
            const val LOST = "lost"
            const val RUN = 20.0
            const val LOADING = 1.4
            /** The list at the bottom, five cards; the card played is the second, Fight! Digifactory. */
            val CARDS = listOf(0.229, 0.378, 0.528, 0.678, 0.828)
            val CARD = SkillSettings.DUNGEON_NAMES[3]
            val ATTEMPT = Dungeon.Button(0.6155, 0.7083, 0.2511, 0.052)
            val CLEAR = Dungeon.Button(0.3378, 0.7083, 0.2502, 0.052)
            val OK = Dungeon.Button(0.602, 0.600, 0.20, 0.04)
        }
    }

    /** The whole Dungeons skill on [TicketGame]: every wait real, the clock the game's, only the pictures stood in for. */
    class TicketBot(val game: TicketGame, val messages: MutableList<String>, budget: Int)
        : DungeonSkill(
            object : Capture {
                override fun grab(): Mat = Mat()
                override fun tap(x: Int, y: Int) {}
                override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
                override fun back() = game.back()
                override fun inFront(): String? = "com.bandainamcoent.dgup_ww"
            },
            { DungeonSkill.Settings(budgets = mapOf(3 to budget), survey = false) },
            log = { messages += it }, on = { game.on }, keep = { _, _ -> },
            sleep = { game.t += it }, now = { game.t += 1e-4; game.t }) {
        val frame: Mat = Mat(1920, 1080, CvType.CV_8UC3, Scalar(30.0, 30.0, 30.0))
        val kept = ArrayList<String>()

        override fun grab(): Mat = frame
        override fun tap(fx: Double, fy: Double, was: String, anchor: Dungeon.Anchor) = game.tap(was)
        override fun recognise(img: Mat): Dungeon.Recognition = game.state()
        override fun listCards(img: Mat): List<Double> = TicketGame.CARDS
        override fun listCardsWithSize(img: Mat): List<Pair<Double, Double>> = TicketGame.CARDS.map { it to 0.116 }
        override fun cardBudget(img: Mat, fy: Double, fh: Double) = Dungeon.Budget(game.tickets, null, null)
        override fun scrollTop(swipes: Int): Mat? = if (game.state().state == Dungeon.LIST) frame else null
        override fun scrollBottom(swipes: Int): Mat? = if (game.state().state == Dungeon.LIST) frame else null
        override fun panelTickets(img: Mat, button: Dungeon.Button, anchor: Dungeon.Anchor): Int? = game.counter()
        override fun headerTickets(img: Mat): Int? = null
        override fun autoButton(img: Mat): Dungeon.Button? =
            if (game.main()) Dungeon.Button(0.9, 0.9, 0.1, 0.05) else null
        override fun homeButton(img: Mat): Dungeon.Button? =
            if (game.globe()) Dungeon.Button(0.5, 0.957, 0.1, 0.05) else null
        override fun labelOf(index: Int, label: String): String =
            if (label == BOTTOM && index == 1) TicketGame.CARD else "card $label $index"
        override fun saveUnknown(img: Mat, tag: String) { kept += tag }

        /** What the director's classify would name the screen, for [resumesOn]. */
        fun screen(): String = when (game.state().state) {
            Dungeon.LIST -> Director.DUNGEON_LIST
            Dungeon.DIALOG -> Director.DIALOG
            Dungeon.EXIT -> Director.PROMPT
            else -> if (game.main()) Director.MAIN else Director.UNKNOWN
        }
    }

    /** What holds after every stretch of a paused pass: nothing tapped with the switch off, nothing false said or kept. */
    private fun assertStayed(game: TicketGame, bot: TicketBot, messages: List<String>) {
        val said = messages.joinToString("\n")
        assertTrue(game.held.isEmpty(), "taps with the switch off: ${game.held}\n$said")
        assertTrue(messages.none { it.contains("could not get back") || it.contains("could not find the way back") },
                   said)
        assertTrue(bot.kept.none { it.startsWith("no_way") || it == "counter_unreadable" }, "${bot.kept}")
    }

    /**
     * Semi-automatic: the list handed over, three tickets wanted and three on
     * the card. The switch goes off as the second run's battle begins: the
     * battle is waited out, the run is won and booked on its sheet, and the
     * pass stops there with the sheet standing. Back on, the pass goes on
     * from the sheet ([Skill.resumesOn] on the director's `unknown`): the
     * sheet tapped away, the card played on from its panel. The switch goes
     * off again on the panel before the third Attempt; back on, the pass goes
     * on from the panel (`dialog`), spends the third ticket, and ends on the
     * list. Three runs, three tickets, and the TODAY card's three parts add up
     * to three -- not three and then three more.
     */
    @Test
    fun `semi-automatic, Dungeons paused in a battle and on the panel spends exactly its tickets`() {
        val game = TicketGame(tickets = 3)
        game.phase = "list"
        val messages = ArrayList<String>()
        val bot = TicketBot(game, messages, budget = 3)
        game.offAt = { runs == 2 && phase == "battle" }

        val first = bot.work(bot.frame)
        assertEquals(Result.STOPPED, first.result, messages.joinToString("\n"))
        assertEquals("sheet", game.phase, "the won run's sheet stands where the pause found it")
        assertEquals(2, game.runs)
        val counted = ArrayList<Int>()
        counted += bot.lastCounts["tickets"] ?: 0
        assertEquals(2, counted.last(), "both runs booked, the second on its sheet alone")
        assertTrue(bot.carried)
        assertStayed(game, bot, messages)

        // The pause: minutes pass, nothing is tapped.
        game.t += 300.0
        assertEquals(Director.UNKNOWN, bot.screen())
        assertTrue(bot.resumesOn(bot.screen(), bot.frame), "the sheet is the pass's own")
        game.on = true
        game.offAt = { runs == 2 && phase == "panel" && won == 2 }
        val second = bot.resume(bot.frame, whole = false)
        assertEquals(Result.STOPPED, second.result, messages.joinToString("\n"))
        assertEquals("panel", game.phase)
        counted += bot.lastCounts["tickets"] ?: 0
        assertStayed(game, bot, messages)

        game.t += 120.0
        assertEquals(Director.DIALOG, bot.screen())
        assertTrue(bot.resumesOn(Director.DIALOG, bot.frame), "the card's panel is the pass's own")
        game.on = true
        game.offAt = { false }
        val third = bot.resume(bot.frame, whole = false)
        val said = messages.joinToString("\n")
        assertEquals(Result.DONE, third.result, said)
        counted += bot.lastCounts["tickets"] ?: 0
        assertEquals(3, game.runs, "three runs for three tickets\n$said")
        assertEquals(0, game.tickets, said)
        assertEquals(3, counted.sum(), "the TODAY card's parts: $counted\n$said")
        assertEquals("list", game.phase, "the semi-automatic work ends on the list")
        assertFalse(bot.carried)
        assertEquals(1, game.taps.count { it == TicketGame.CARD }, "the card opened once, gone on with on its panel: ${game.taps}")
        assertStayed(game, bot, messages)
    }

    /**
     * Fully automatic: the chain's step from the main screen, three tickets
     * wanted and four on the card. The switch goes off on the panel after the
     * first run: the step stops there. In the pause the player goes home
     * (the claim ends); back on, the step goes on from the main screen as the
     * same pass ([Skill.resume] with the carry): the list, the card, and only
     * the two tickets that are left. The switch goes off again in the third
     * run's battle, which is lost -- back to the panel, nothing booked; back
     * on, the pass goes on on the panel, spends the last ticket and goes home.
     */
    @Test
    fun `fully automatic, Dungeons paused twice goes on from where it stands and from the main screen`() {
        val game = TicketGame(tickets = 4, ends = listOf(TicketGame.WON, TicketGame.WON, TicketGame.LOST, TicketGame.WON))
        val messages = ArrayList<String>()
        val bot = TicketBot(game, messages, budget = 3)
        game.offAt = { runs == 1 && won == 1 && phase == "panel" }

        val first = bot.run()
        assertEquals(Result.STOPPED, first.result, messages.joinToString("\n"))
        assertEquals("panel", game.phase, "the pass stops on the panel, and no way home is walked")
        assertEquals(1, messages.count { it == Stays.LINE }, messages.joinToString("\n"))
        var tickets = bot.lastCounts["tickets"] ?: 0
        assertStayed(game, bot, messages)

        // In the pause the player closes the panel and goes home.
        game.phase = "main"
        assertFalse(bot.resumesOn(Director.MAIN, bot.frame), "the main screen is no screen of the pass's")
        game.on = true
        game.offAt = { runs == 3 && phase == "battle" }
        val second = bot.resume(bot.frame, whole = true)
        assertEquals(Result.STOPPED, second.result, messages.joinToString("\n"))
        tickets += bot.lastCounts["tickets"] ?: 0
        assertEquals("panel", game.phase, "the lost run came back to its panel by itself")
        assertEquals(1, bot.lastCounts["lost"], "the lost run is booked as lost, on the panel's counter")
        assertStayed(game, bot, messages)

        game.t += 60.0
        assertTrue(bot.resumesOn(bot.screen(), bot.frame))
        game.on = true
        game.offAt = { false }
        val third = bot.resume(bot.frame, whole = true)
        val said = messages.joinToString("\n")
        assertEquals(Result.DONE, third.result, said)
        tickets += bot.lastCounts["tickets"] ?: 0
        assertEquals(3, tickets, "three tickets over the three parts\n$said")
        assertEquals(1, game.tickets, "one left on the card: the page asked for three\n$said")
        assertEquals(4, game.runs, said)
        assertEquals("main", game.phase, "home at the end of the step")
        assertStayed(game, bot, messages)
    }

    /**
     * The live case of 2026-09-30 22:55:03 on LDPlayer, on the clock: the
     * switch goes off as the battle begins, the run is won, and its sheet is
     * one no reader names (the daily dungeon's). The pass waits on it with
     * the switch off and taps nothing; the switch comes back 40 s after it
     * went, when the sheet has stood 20 s, and the sheet is tapped only after
     * [DungeonSkill.UNKNOWN_HOLD] more -- the second look after the pause,
     * not a tap in the same second.
     */
    @Test
    fun `a screen nobody reads that stood through the pause is looked at again before a tap`() {
        val game = TicketGame(tickets = 1, sheetRead = false)
        game.phase = "list"
        val messages = ArrayList<String>()
        val bot = TicketBot(game, messages, budget = 1)
        game.offAt = { runs == 1 && phase == "battle" && offTime == null }
        game.backAfter = 40.0

        bot.work(bot.frame)
        val said = messages.joinToString("\n")
        val off = game.offTime ?: error("the switch never went off\n$said")
        val neutral = game.tapTimes.filter { it.first.startsWith("neutral") }
        assertTrue(neutral.isNotEmpty(), "the unread sheet is tapped away once the switch is back\n$said")
        val after = neutral.first().second - (off + 40.0)
        assertTrue(after >= DungeonSkill.UNKNOWN_HOLD - 0.01,
                   "tapped %.2f s after the switch came back, not after a hold of its own\n%s".format(after, said))
        assertStayed(game, bot, messages)
    }
}
