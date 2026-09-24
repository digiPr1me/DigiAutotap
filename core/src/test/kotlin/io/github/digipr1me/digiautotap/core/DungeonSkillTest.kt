package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * test_dungeon_flow.py in Kotlin: the same 44 cases, the same script, the
 * same painted frames (PLAN_ANDROID_APP.md 4, session L -- "der Flow-Test
 * der PC-Version in Kotlin nachgebaut, gleiches Skript").
 *
 * State sequences are played back and what the skill taps is counted. That
 * surfaces endless loops and miscounting without an emulator; bugs of
 * exactly that kind cost several test runs while dungeon.py was written.
 *
 * Where the Python test replaces a module function (`D.recognise`,
 * `D.list_cards`, `D.auto_button`, `D.home_button`) or pokes a bound method
 * (`bot.dialog_settled = ...`), this overrides the matching seam on
 * [DungeonSkill]. The frames each of those readers would have had are not
 * dropped: they have cases of their own further down, painted by
 * [PaintDungeon] and read by the real reader.
 *
 * The 44, group by group, in the order `main()` prints them: re-opening a
 * card 2, back to the list 2, confirmation prompts 9, party slots 4, dungeon
 * selection 4, the home button 10, the way home 8, and the four loose cases
 * at the end.
 *
 * **43 of them port one for one.** The one that does not is the way home's
 * "dry run presses once": the phone has no dry run -- `app.py` passes
 * `dry_run=False` and the director has no other way in -- so that branch is
 * not in [DungeonSkill] to test. Its place is taken by the case the phone
 * has instead, the seam's own [Outcome.leaving]. The two cases after that
 * are the seam as well, and the PC has nothing to compare them with.
 *
 * Group 10 is the phone's alone as well: tickets instead of attempts, and
 * Clear Previous Difficulty after N of them (NOTES.md, "A lost dungeon run",
 * 2026-09-23) -- a lost run, the two witnesses of a ticket and where they
 * disagree, the ceiling, and two passes in a row.
 */
class DungeonSkillTest {

    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    // ------------------------------------------------------------------
    // The rig: Sim and a skill whose seams the script drives
    // ------------------------------------------------------------------

    /**
     * test_dungeon_flow.Sim: one state per look, and the buttons that go with
     * it. A state may carry the panel's counter after a colon, "dialog:2",
     * which is what [Bot.counterNow] reads off the last look; without one
     * the counter is unreadable. A panel has Clear Previous Difficulty
     * beside Attempt unless [withClear] says otherwise.
     */
    class Sim(sequence: List<String>, private val confirmKind: String = "party",
              private val withClear: Boolean = true) {
        val sequence = sequence.toList()
        var i = 0
        val clicks = ArrayList<String>()
        val messages = ArrayList<String>()
        /** The counter the last look showed, or null. */
        var lastTickets: Int? = null

        fun nextState(): Dungeon.Recognition {
            val raw = if (sequence.isEmpty()) Dungeon.UNKNOWN
                      else sequence[minOf(i, sequence.size - 1)]
            i += 1
            val state = raw.substringBefore(":")
            lastTickets = raw.substringAfter(":", "").toIntOrNull()
            return Dungeon.Recognition(
                state = state,
                attempt = when (state) {
                    "close" -> button(0.21)
                    Dungeon.DIALOG -> button(0.30)
                    else -> null
                },
                party = if (state == Dungeon.DIALOG_PARTY) button(0.28, 0.79) else null,
                ad = if (state == Dungeon.DIALOG_AD) button(0.62) else null,
                clear = if (state == Dungeon.DIALOG && withClear) Dungeon.Button(0.34, 0.70, 0.28, 0.05)
                        else null,
                blau = emptyList(), violett = emptyList(),
                karten = listOf(0.3), giveup = null, partyVoll = 0,
                // For the party dialog OK is the correct answer, it ends the
                // dungeon. The exit dialog, in contrast, is cancelled and the
                // flow continues.
                exitOk = button(0.20), exitKind = confirmKind)
        }

        private fun button(w: Double, fy: Double = 0.70) = Dungeon.Button(0.5, fy, w, 0.05)
    }

    /**
     * test_dungeon_flow.make_bot: a skill with every seam the script drives,
     * and the waits taken out. The clock ticks on every read, so a wait with
     * a timeout always ends -- `make_bot` leaves `time.time()` real and gets
     * the same thing, a battle of no measurable length.
     */
    open class Bot(
        val sim: Sim,
        val cap: DirectorTest.FakeCapture = DirectorTest.FakeCapture(),
        settings: () -> Settings = { Settings(budgets = emptyMap()) },
        val attemptWorks: Boolean = false,
        /**
         * `bot.return_to_list = lambda tries=5: True`. The two cases that are
         * *about* the way back say false and get the real one -- Kotlin has
         * no `D.DungeonBot.return_to_list.__get__(bot)`, and `super` in a
         * second subclass would reach this stub rather than the skill.
         */
        val stubReturnToList: Boolean = true,
        /** The same, for `bot.dismiss_confirm = D.DungeonBot.dismiss_confirm.__get__(bot)`. */
        val stubDismissConfirm: Boolean = true,
        /** The same, for the wait after a run and the counter read over two frames. */
        val stubWaitBack: Boolean = true,
    ) : DungeonSkill(cap, settings, log = { sim.messages.add(it) }, on = { true },
                     keep = { _, _ -> }, sleep = {}, now = { CLOCK += TICK; CLOCK },
                     patience = 0.0) {

        /** The one frame the script hands out; nothing reads it, the seams answer instead. */
        val frame: Mat = Mat(1390, 805, CvType.CV_8UC3, Scalar(30.0, 30.0, 30.0))

        init {
            maxLoops = 14
            battleTimeout = 1.0
            startTimeout = 1.0
            entries = 7
            visibleAtBottom = 5
            only = null
            skip = emptySet()
        }

        override fun grab(): Mat = frame
        override fun tap(fx: Double, fy: Double, was: String) { sim.clicks.add(was) }
        override fun back(onlyIfDialog: Boolean, wantOk: String?): Boolean = true
        override fun returnToList(tries: Int): Boolean =
            if (stubReturnToList) true else super.returnToList(tries)
        override fun dialogSettled(tries: Int, pause: Double?): Dungeon.Recognition = sim.nextState()
        override fun waitDialogGone(timeout: Double): Boolean = attemptWorks
        /** The sheets on the way are seen and passed, as the real wait taps them away. */
        override fun waitDialogBack(timeout: Double): Dungeon.Recognition? {
            if (!stubWaitBack) return super.waitDialogBack(timeout)
            var info = sim.nextState()
            var n = 0
            while (info.state == Dungeon.REWARD && n < 20) {
                rewardSeen = true
                info = sim.nextState()
                n += 1
            }
            return info
        }
        override fun counterNow(info: Dungeon.Recognition): Int? =
            if (!stubWaitBack) super.counterNow(info)
            else if (info.state in DungeonSkill.DIALOGS) sim.lastTickets else null
        override fun dismissConfirm(info: Dungeon.Recognition?, wantOk: String?) {
            if (!stubDismissConfirm) super.dismissConfirm(info, wantOk)
        }
        override fun saveUnknown(img: Mat, tag: String) {}
        override fun labelOf(index: Int, label: String): String = "Test"
        override fun recognise(img: Mat): Dungeon.Recognition = sim.nextState()
        override fun listCards(img: Mat): List<Double> = listOf(0.3)
        override fun listCardsWithSize(img: Mat): List<Pair<Double, Double>> = listOf(0.3 to 0.12)
    }

    /** test_dungeon_flow.run_case: play one entry through a scripted sequence. */
    private fun runCase(sequence: List<String>, name: String, expectedAds: Int? = null,
                        attemptWorks: Boolean = false, minBattle: Double = 6.0,
                        maxAds: Int = 2, budgets: Map<Int, Int> = emptyMap(),
                        counted: Map<Pair<String, Int>, Dungeon.Budget> = emptyMap()
    ): Triple<Boolean, Int, Sim> {
        val sim = Sim(sequence)
        val bot = Bot(sim, attemptWorks = attemptWorks)
        bot.minBattle = minBattle
        bot.maxAds = maxAds
        bot.budgets = budgets
        bot.counted.putAll(counted)
        bot.playEntry(0, DungeonSkill.TOP)
        val adClicks = sim.clicks.count { it == "ad" }
        val good = expectedAds == null || adClicks == expectedAds
        println("%-44s ad clicks %d%s".format(name, adClicks,
            if (expectedAds == null) "" else "  expected $expectedAds  ${if (good) "ok" else "FAILED"}"))
        return Triple(good, adClicks, sim)
    }

    // ------------------------------------------------------------------
    // 1. Re-opening a card (2)
    // ------------------------------------------------------------------
    /**
     * A card that drops back to the list gets one more look. A live run left
     * Metal Sea with an attempt unspent: the panel closed itself after a
     * battle, the list showed, and the dungeon counted as finished. Once,
     * though -- a card the game keeps closing must not become a loop.
     */
    @Test
    fun `re-opening a card`() {
        // In the list, open the card, one attempt, and the panel is gone again.
        val (_, _, first) = runCase(listOf(Dungeon.LIST, Dungeon.DIALOG, Dungeon.LIST),
                                    "card re-opened after dropping to the list",
                                    attemptWorks = true, minBattle = 0.0)
        assertEquals(2, first.clicks.count { it == "Test" },
                     "card taps, clicks ${first.clicks}")

        // Nothing was attempted, so the list means it never opened. No re-open.
        val (_, _, second) = runCase(listOf(Dungeon.LIST, Dungeon.LIST),
                                     "list straight away is not re-opened")
        assertEquals(1, second.clicks.count { it == "Test" }, "card taps")
    }

    /** A skill that counts its scrolls and plays nothing, for the two cases below. */
    private open class Scrolls(
        sim: Sim,
        settings: () -> DungeonSkill.Settings = { DungeonSkill.Settings(budgets = emptyMap()) },
        attemptWorks: Boolean = false,
    ) : Bot(sim, settings = settings, attemptWorks = attemptWorks) {
        var tops = 0
        var bottoms = 0
        override fun scrollTop(swipes: Int) { tops += 1 }
        override fun scrollBottom(swipes: Int) { bottoms += 1 }
        override fun listCards(img: Mat): List<Double> = listOf(0.2, 0.4, 0.6, 0.8, 1.0)
    }

    /**
     * The list the game hands back after a battle is at the top, whatever
     * the pass had scrolled to, and a card's index is only a card under the
     * scroll the plan measured it at. Measured on LDPlayer 2026-09-22:
     * Digifactory, second card of the bottom half, was opened at fy 0.361
     * and "once more" at 0.456 -- DemiDevimon's place on a list at the top,
     * whose empty panel then booked Digifactory as exhausted with a ticket
     * still on its card. So the re-open scrolls to the card's own half
     * first, and the top half, which was right by accident, scrolls too.
     */
    @Test
    fun `re-opening a card scrolls back to its half of the list`() {
        for ((label, expected) in listOf(DungeonSkill.BOTTOM to (0 to 1),
                                         DungeonSkill.TOP to (1 to 0))) {
            val bot = Scrolls(Sim(listOf(Dungeon.LIST, Dungeon.DIALOG, Dungeon.LIST)),
                              attemptWorks = true)
            bot.minBattle = 0.0
            bot.playEntry(0, label)
            assertEquals(2, bot.sim.clicks.count { it == "Test" }, "$label: card taps ${bot.sim.clicks}")
            assertEquals(expected, bot.tops to bot.bottoms, "$label: scrolls top to bottom")
        }
    }

    /**
     * The pass scrolls to a half of the list only when a card of the
     * player's is in it. With the survey off (Stored.dungeon, 2026-09-22)
     * the plan's one swipe to the bottom is the whole of the pre-check, and
     * a pass with bottom-half cards only never goes to the top at all.
     */
    @Test
    fun `a half with no card in it is not scrolled to`() {
        fun pass(budgets: Map<Int, Int>, survey: Boolean): Pair<Int, Int> {
            val bot = object : Scrolls(Sim(listOf(Dungeon.LIST)),
                                       settings = { DungeonSkill.Settings(budgets, survey = survey) }) {
                override fun playEntry(index: Int, label: String, key: Pair<String, Int>?) {}
                override fun survey(positions: List<Int>, label: String): List<Int> = positions
            }
            assertEquals(Result.DONE, bot.work(bot.frame).result)
            return bot.tops to bot.bottoms
        }
        // Bottom half only: the plan's swipe, the swipe before the loop and
        // one after the card -- and the top is never seen.
        assertEquals(0 to 3, pass(mapOf(3 to 1), survey = false), "bottom only, no survey")
        // Top half only: the plan still has to count the bottom, once.
        assertEquals(2 to 1, pass(mapOf(0 to 1), survey = false), "top only, no survey")
        // With the survey on, each half costs its one more swipe.
        assertEquals(0 to 4, pass(mapOf(3 to 1), survey = true), "bottom only, survey")
        assertEquals(3 to 1, pass(mapOf(0 to 1), survey = true), "top only, survey")
    }

    // ------------------------------------------------------------------
    // 2. Back to the list (2)
    // ------------------------------------------------------------------
    /**
     * Whether OK may be pressed depends on what was on screen one frame
     * earlier. A dungeon panel means the prompt is the dungeon's own;
     * anything else means it could be "Return to the title screen?", which
     * must not be confirmed.
     */
    @Test
    fun `back to the list`() {
        for ((before, expected) in listOf(Dungeon.DIALOG_PARTY to DungeonSkill.LEAVING,
                                          Dungeon.UNKNOWN to null)) {
            val sim = Sim(listOf(before, Dungeon.EXIT, Dungeon.LIST))
            val seen = ArrayList<String?>()
            val bot = object : Bot(sim, stubReturnToList = false) {
                override fun dismissConfirm(info: Dungeon.Recognition?, wantOk: String?) {
                    seen.add(wantOk)
                }
            }
            bot.returnToList()
            println("  prompt after %-13s -> OK allowed: %s".format(before, seen))
            assertEquals(listOf(expected), seen)
        }
    }

    // ------------------------------------------------------------------
    // 3. Confirmation prompts (9)
    // ------------------------------------------------------------------
    /**
     * Three dialogs wear the same face. The pink pair is identical to the
     * pixel, so colour can only rule out the one whose OK ends the session;
     * which of the other two it is comes from the caller.
     */
    @Test
    fun `confirmation prompts`() {
        // A prompt is a prompt, and a loading panel is not -- read end to
        // end, not by handing confirmKind a button recognise never found.
        for ((img, pair) in listOf(
                Paint.prompt(pink = true) to ("pink prompt" to Dungeon.EXIT),
                Paint.prompt(pink = false) to ("grey prompt" to Dungeon.EXIT),
                PaintDungeon.loadingPanel() to ("loading panel" to Dungeon.DIALOG))) {
            val got = Dungeon.recognise(img).state
            println("  %-14s reads as %s".format(pair.first, got))
            assertEquals(pair.second, got, pair.first)
            img.release()
        }
        val pink = Paint.prompt(pink = true)
        val grey = Paint.prompt(pink = false)
        val kindPink = Dungeon.confirmKind(pink, Paint.PINK_OK)
        val kindGrey = Dungeon.confirmKind(grey, Paint.GREY_OK)
        println("  pink Cancel reads '$kindPink', grey Cancel reads '$kindGrey'")
        assertEquals("party", kindPink)
        assertEquals("beenden", kindGrey)
        Paint.release(pink, grey)

        val sim = Sim(emptyList())
        val kept = ArrayList<String>()
        val bot = object : Bot(sim, stubDismissConfirm = false) {
            override fun saveUnknown(img: Mat, tag: String) { kept.add(tag) }
        }

        fun answer(kind: String, wantOk: String? = null): Pair<List<String>, List<String>> {
            sim.clicks.clear()
            kept.clear()
            bot.dismissConfirm(Dungeon.Recognition(
                state = Dungeon.EXIT, attempt = null, party = null, ad = null, clear = null,
                blau = emptyList(), violett = emptyList(), karten = emptyList(), giveup = null,
                exitOk = Paint.PINK_OK, exitKind = kind), wantOk)
            return sim.clicks.toList() to kept.toList()
        }

        var (clicks, _) = answer("party", DungeonSkill.LEAVING)
        println("  pink, on the way out of a dungeon: $clicks")
        assertEquals(listOf("OK, leave the party"), clicks)

        clicks = answer("party").first
        println("  pink, anywhere else: $clicks")
        assertEquals(listOf("Cancel"), clicks)

        // Even asked to leave a dungeon, the exit prompt is never confirmed.
        val (greyClicks, greyKept) = answer("beenden", DungeonSkill.LEAVING)
        println("  grey, even on the way out of a dungeon: $greyClicks, kept $greyKept")
        assertEquals(listOf("Cancel"), greyClicks)
        assertEquals(listOf("confirm_beenden"), greyKept)
    }

    // ------------------------------------------------------------------
    // 4. Party slots (4)
    // ------------------------------------------------------------------
    /**
     * One team-mate must read as one. A live run read two, never searched for
     * a party, and pressed Attempt without one -- which does nothing. It then
     * sat in the dialog until it gave up.
     */
    @Test
    fun `party slots`() {
        val img = PaintDungeon.partyPanel()
        val got = Dungeon.partySlotsFilled(img)
        // The wide crop that reached the panel's own bright edge. Kotlin
        // cannot poke a const the way the Python test pokes the module, so
        // the half-width is a parameter here -- and the copy is held to the
        // real reader at the real half-width, one line below.
        val fooled = slotsFilled(img, 0.10)
        assertEquals(got, slotsFilled(img, Dungeon.PARTY_SLOT_HALF_W),
                     "the parameterised copy has to be the reader at its own half-width")
        println("  one filled slot: $got with the crop as it is, $fooled with the wide " +
                "crop that reached the panel edge")
        assertEquals(1, got)
        assertTrue(fooled > got, "the wide crop has to be fooled: $fooled")

        val all = PaintDungeon.partyPanel(listOf(true, true, true))
        val none = PaintDungeon.partyPanel(listOf(false, false, false))
        assertEquals(3, Dungeon.partySlotsFilled(all))
        assertEquals(0, Dungeon.partySlotsFilled(none))
        println("  three filled read as 3, none read as 0")
        Paint.release(img, all, none)
    }

    /** `Dungeon.partySlotsFilled` with the half-width open, for the case above. */
    private fun slotsFilled(img: Mat, halfW: Double): Int {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        var filled = 0
        for (fx in Dungeon.PARTY_SLOTS) {
            val x = Py.int(x0 + (fx - halfW) * gw)
            val w = Py.int(2 * halfW * gw)
            val y = Py.int(y0 + (Dungeon.PARTY_SLOT_Y - 0.05) * gh)
            val h = Py.int(0.10 * gh)
            val patch = Py.crop(img, maxOf(0, y), y + h, maxOf(0, x), x + w) ?: continue
            val gray = Cv.gray(patch)
            if (Cv.std(gray) >= Dungeon.PARTY_SLOT_MIN_STD) filled += 1
            gray.release()
        }
        return filled
    }

    // ------------------------------------------------------------------
    // 5. Dungeon selection (4)
    // ------------------------------------------------------------------
    /**
     * The short-name selection must act on the right cards. What matters is
     * that numbers refer to the overall list, not cards in the current view:
     * counted from the bottom, the first visible card sits at total minus
     * visible.
     */
    @Test
    fun `dungeon selection`() {
        val cases = listOf(
            Case(null, emptySet(), "no selection",
                 listOf(0 to DungeonSkill.TOP, 4 to DungeonSkill.BOTTOM), listOf(true, true)),
            Case(null, setOf(0), "skip apocalymon",
                 listOf(0 to DungeonSkill.TOP, 1 to DungeonSkill.TOP), listOf(false, true)),
            Case(setOf(0, 4), emptySet(), "only apocalymon and network",
                 listOf(0 to DungeonSkill.TOP, 1 to DungeonSkill.TOP, 2 to DungeonSkill.BOTTOM),
                 listOf(true, false, true)),
            Case(null, setOf(5), "skip metalsea",
                 listOf(3 to DungeonSkill.BOTTOM, 2 to DungeonSkill.BOTTOM), listOf(false, true)),
        )
        for (c in cases) {
            val bot = Bot(Sim(emptyList()))
            bot.entries = 7
            bot.visibleAtBottom = 5
            bot.only = c.only
            bot.skip = c.skip
            val got = c.cards.map { (i, label) -> bot.isSelected(i, label) }
            val details = c.cards.zip(got).joinToString(", ") { (card, g) ->
                "${DungeonSkill.dungeonLabel(card.first, card.second == DungeonSkill.BOTTOM, 7, 5)} $g"
            }
            println("  %-30s %s   %s".format(c.name, if (got == c.expected) "ok" else "FAILED", details))
            assertEquals(c.expected, got, c.name)
        }
    }

    private class Case(val only: Set<Int>?, val skip: Set<Int>, val name: String,
                       val cards: List<Pair<Int, String>>, val expected: List<Boolean>)

    // ------------------------------------------------------------------
    // 6. The home button (10)
    // ------------------------------------------------------------------
    @Test
    fun `the home button`() {
        for ((w, h) in PaintDungeon.HOME_SIZES) {
            val img = PaintDungeon.paintHome(PaintDungeon.blank(w, h))
            val found = Dungeon.homeButton(img)
            println("  %4d x %-4d  globe -> %s".format(w, h,
                if (found == null) "not found"
                else "fx %.3f fy %.3f fw %.4f".format(found.fx, found.fy, found.fw)))
            assertNotNull(found, "$w x $h: no globe")
            assertTrue(abs(found.fx - 0.481) <= 0.01 && abs(found.fy - 0.945) <= 0.01,
                       "$w x $h: globe at ${found.fx} / ${found.fy}")
            img.release()
        }
        val negatives = listOf(
            "dimmed by a dialog" to PaintDungeon.paintHome(PaintDungeon.blank(805, 1390), bright = false),
            "white panel over the bar" to PaintDungeon.paintWhiteBar(PaintDungeon.blank(805, 1390)),
            "nothing down there" to PaintDungeon.blank(805, 1390))
        for ((name, img) in negatives) {
            val found = Dungeon.homeButton(img)
            println("  %-24s -> %s".format(name, if (found != null) "found" else "None"))
            assertNull(found, name)
            img.release()
        }
    }

    // ------------------------------------------------------------------
    // 7. The way home (8 of the PC's 9; the dry-run case is the seam's, below)
    // ------------------------------------------------------------------
    /**
     * One word per round: what [DungeonSkill.goHome] sees when it looks.
     * "main" is the main screen with nothing over it, "bar" a screen with the
     * nav bar on it, "away" anything else. Scripted rather than painted: the
     * pictures have their own cases above, these are about the order of the
     * steps.
     */
    class HomeScreen(script: List<String>) {
        val frames = listOf("main", "bar", "away").associateWith {
            Mat(1390, 805, CvType.CV_8UC3, Scalar(30.0, 30.0, 30.0))
        }
        private val script = ArrayList(script)
        var presses = 0
        var backToList = 0

        fun grab(): Mat = frames[if (script.size > 1) script.removeAt(0) else script[0]]!!
        fun isA(word: String, img: Mat) = frames[word] === img
    }

    private fun homeBot(screen: HomeScreen, sim: Sim = Sim(listOf(Dungeon.LIST))): Bot =
        object : Bot(sim) {
            override fun grab(): Mat = screen.grab()
            override fun tap(fx: Double, fy: Double, was: String) { screen.presses += 1 }
            override fun returnToList(tries: Int): Boolean { screen.backToList += 1; return true }
            override fun autoButton(img: Mat): Dungeon.Button? =
                if (screen.isA("main", img)) Dungeon.Button(0.361, 0.766, 0.05, 0.05) else null
            override fun homeButton(img: Mat): Dungeon.Button? =
                if (screen.isA("bar", img)) Dungeon.Button(0.481, 0.945, 0.05, 0.05) else null
        }

    @Test
    fun `the way home`() {
        val cases = listOf(
            HomeCase("already on the main screen", listOf("main"), true, 0, 0),
            HomeCase("from the list, one press", listOf("bar", "main"), true, 1, 0),
            HomeCase("first press swallowed", listOf("bar", "bar", "main"), true, 2, 0),
            HomeCase("never gets there", List(8) { "bar" }, false, DungeonSkill.HOME_PRESSES_MAX, 0),
            HomeCase("no bar, back to the list first", listOf("away", "bar", "main"), true, 1, 1),
            HomeCase("nowhere it knows", List(8) { "away" }, false, 0, 1))
        for (c in cases) {
            val screen = HomeScreen(c.script)
            val got = homeBot(screen).goHome()
            println("  %-30s -> %-5s %d press(es), %d back to the list".format(
                c.name, got, screen.presses, screen.backToList))
            assertEquals(c.expected, got, c.name)
            assertEquals(c.presses, screen.presses, "${c.name}: presses")
            assertEquals(c.backs, screen.backToList, "${c.name}: back to the list")
        }

        // goHome runs in a finally. A pass that ended because the capture
        // died has to end with its own error, not with this one on top.
        val dead = HomeScreen(listOf("bar"))
        val said = ArrayList<String>()
        val deadSim = Sim(listOf(Dungeon.LIST))
        val deadBot = object : Bot(deadSim) {
            override fun grab(): Mat = throw RuntimeException("capture is gone")
            override fun tap(fx: Double, fy: Double, was: String) { dead.presses += 1 }
            override fun returnToList(tries: Int) = true
        }
        deadSim.messages.clear()
        assertFalse(deadBot.goHome())
        said.addAll(deadSim.messages)
        println("  %-30s -> says so and carries on".format("a dead capture"))
        assertTrue(said.any { "capture is gone" in it }, said.toString())

        // And it happens on the bad way out of run, not only the good one.
        // One frame more than the PC's script: `run` looks once before it
        // starts, for the reference rect its clicks go out in.
        val thrower = HomeScreen(listOf("bar", "bar", "main"))
        val bot = object : Bot(Sim(listOf(Dungeon.LIST)),
                               settings = { DungeonSkill.Settings(budgets = mapOf(0 to 1)) }) {
            override fun grab(): Mat = thrower.grab()
            override fun tap(fx: Double, fy: Double, was: String) { thrower.presses += 1 }
            override fun returnToList(tries: Int): Boolean { thrower.backToList += 1; return true }
            override fun openList() = true
            override fun play(): Outcome = throw RuntimeException("mid-run")
            override fun autoButton(img: Mat): Dungeon.Button? =
                if (thrower.isA("main", img)) Dungeon.Button(0.361, 0.766, 0.05, 0.05) else null
            override fun homeButton(img: Mat): Dungeon.Button? =
                if (thrower.isA("bar", img)) Dungeon.Button(0.481, 0.945, 0.05, 0.05) else null
        }
        var threw = false
        try {
            bot.run()
        } catch (err: RuntimeException) {
            threw = true
        }
        println("  %-30s -> error kept, %d press(es)".format("a run that throws still goes home",
                                                             thrower.presses))
        assertTrue(threw, "the error was swallowed")
        assertEquals(1, thrower.presses)
    }

    private class HomeCase(val name: String, val script: List<String>, val expected: Boolean,
                           val presses: Int, val backs: Int)

    // ------------------------------------------------------------------
    // 8. The four loose cases at the end of main() (4)
    // ------------------------------------------------------------------
    @Test
    fun `the party dialog ends the dungeon`() {
        // In a test run it appeared four times in a row, because Cancel keeps
        // the bot stuck in the dialog.
        val sim = Sim(List(8) { Dungeon.EXIT }, confirmKind = "party")
        val seen = ArrayList<String?>()
        val bot = object : Bot(sim) {
            override fun dismissConfirm(info: Dungeon.Recognition?, wantOk: String?) {
                seen.add(wantOk)
            }
        }
        bot.playEntry(0, DungeonSkill.TOP)
        println("%-44s leave_exit %dx  should be at most 2".format(
            "party dialog ends the dungeon", seen.size))
        assertTrue(seen.size <= 2, "dismissConfirm ${seen.size} times")
    }

    @Test
    fun `an ad that yields nothing stops after one`() {
        // Otherwise it runs six ads into nothing, as it did in a test run.
        val (good, _, _) = runCase(List(12) { Dungeon.DIALOG_AD },
                                   "ad without effect, must stop after 1", expectedAds = 1)
        assertTrue(good)
    }

    @Test
    fun `an ad works, then Attempt appears`() {
        val (good, ads, _) = runCase(
            listOf(Dungeon.DIALOG_AD, Dungeon.DIALOG, Dungeon.DIALOG_AD, Dungeon.DIALOG),
            "ad works, then Attempt")
        assertTrue(good)
        assertEquals(1, ads)
    }

    @Test
    fun `a reward window in between is closed and does not block`() {
        val (good, _, sim) = runCase(
            listOf("close", Dungeon.DIALOG_AD, "close", Dungeon.DIALOG_AD),
            "reward window in between")
        println("     click sequence: ${sim.clicks.take(6).joinToString(", ")}")
        assertTrue(good)
        assertEquals(listOf("Test", "Close", "ad"), sim.clicks.take(3))
    }

    // ------------------------------------------------------------------
    // 10. Tickets, not attempts (2026-09-23, NOTES.md "A lost dungeon run")
    // ------------------------------------------------------------------
    /**
     * One card, played through a script whose panels carry their counter:
     * "dialog:2" is the panel at 2/2. Nothing on the list is read -- the
     * card is the one entry [runCase] plays -- and battles take no time, so
     * minBattle is 0.
     */
    private fun ticketCase(sequence: List<String>, tickets: Int, clearAfter: Int,
                           withClear: Boolean = true): Pair<Bot, List<String>> {
        val sim = Sim(sequence, withClear = withClear)
        val kept = ArrayList<String>()
        val bot = object : Bot(sim, attemptWorks = true) {
            override fun saveUnknown(img: Mat, tag: String) { kept += tag }
        }
        bot.minBattle = 0.0
        bot.budgets = mapOf(0 to tickets)
        bot.attemptsBeforeClear = clearAfter
        for (k in listOf("tickets", "attempts", "fights", "lost", "cleared")) bot.stats[k] = 0
        bot.playEntry(0, DungeonSkill.TOP)
        return bot to kept
    }

    private fun Bot.taps(was: String) = sim.clicks.count { it == was }

    /**
     * The player's case: Bakemon is lost more often than won now. A lost run
     * leaves the counter where it was and raises no sheet -- the battle goes
     * black, "Now Loading", and the panel is back (measured on LDPlayer) --
     * and it costs no ticket, so it is tried again until the ticket is spent.
     */
    @Test
    fun `a lost run costs no ticket and is tried again`() {
        val (bot, _) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            "dialog:2", "dialog:2",                     // lost: back, and still 2
            "dialog:2",                                 // the panel, standing
            Dungeon.REWARD, "dialog:1", "dialog:1",     // won: the sheet, and 1
            "dialog:1"), tickets = 1, clearAfter = 10)
        assertEquals(2, bot.taps("Attempt"), bot.sim.messages.toString())
        assertEquals(1, bot.stats["tickets"], "one ticket spent")
        assertEquals(1, bot.stats["lost"], "one run lost")
        assertEquals(2, bot.stats["fights"])
        assertEquals(0, bot.taps("Clear Previous Difficulty"))
        assertTrue(bot.sim.messages.any { "spent 1 ticket, which is the limit" in it },
                   bot.sim.messages.toString())
    }

    /**
     * N attempts on one dungeon, won or lost, and every ticket still wanted
     * goes to Clear Previous Difficulty -- the count does not start again
     * per ticket (the player's rule).
     */
    @Test
    fun `after N attempts the rest goes to Clear Previous Difficulty`() {
        val (bot, _) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            "dialog:2", "dialog:2",                     // the one attempt, lost
            "dialog:2",
            Dungeon.REWARD, "dialog:1", "dialog:1",     // Clear: the sheet, 2 -> 1
            "dialog:1",
            Dungeon.REWARD, "dialog:0", "dialog:0",     // Clear again: 1 -> 0
            "dialog:0"), tickets = 2, clearAfter = 1)
        assertEquals(1, bot.taps("Attempt"))
        assertEquals(2, bot.taps("Clear Previous Difficulty"), bot.sim.messages.toString())
        assertEquals(2, bot.stats["tickets"])
        assertEquals(2, bot.stats["cleared"])
        assertEquals(1, bot.stats["lost"])
    }

    /** 0 attempts before Clear Previous Difficulty: Attempt is never tapped. */
    @Test
    fun `no attempts before Clear Previous Difficulty never taps Attempt`() {
        val (bot, _) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            Dungeon.REWARD, "dialog:1", "dialog:1",
            "dialog:1"), tickets = 1, clearAfter = 0)
        assertEquals(0, bot.taps("Attempt"))
        assertEquals(1, bot.taps("Clear Previous Difficulty"))
        assertEquals(1, bot.stats["tickets"])
    }

    /**
     * No sheet and the counter where it was: the tap fell into an animation.
     * Once more, and once only -- then the frame is kept and the card ends.
     */
    @Test
    fun `Clear Previous Difficulty that does nothing is tried once more, then left`() {
        val (bot, kept) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            "dialog:2", "dialog:2",
            "dialog:2",
            "dialog:2", "dialog:2"), tickets = 2, clearAfter = 0)
        assertEquals(2, bot.taps("Clear Previous Difficulty"), bot.sim.messages.toString())
        assertEquals(0, bot.stats["tickets"])
        assertTrue("clear_no_effect" in kept, "kept $kept")
    }

    /** A panel with no Clear Previous Difficulty -- Apocalymon's -- ends the card after N. */
    @Test
    fun `a panel without Clear Previous Difficulty ends the card after N attempts`() {
        val (bot, kept) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            "dialog:2", "dialog:2",
            "dialog:2"), tickets = 2, clearAfter = 1, withClear = false)
        assertEquals(1, bot.taps("Attempt"))
        assertEquals(0, bot.taps("Clear Previous Difficulty"))
        assertTrue("no_clear_previous" in kept, "kept $kept")
        assertTrue(bot.sim.messages.any { "no Clear Previous Difficulty on this panel" in it })
    }

    /**
     * The two witnesses disagree: the sheet came up and the counter did not
     * move. Nothing is booked, the frame is kept, and the run still counts
     * as an attempt.
     */
    @Test
    fun `a sheet with a counter that did not move books nothing`() {
        val (bot, kept) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            Dungeon.REWARD, "dialog:2", "dialog:2",
            "dialog:2"), tickets = 1, clearAfter = 1, withClear = false)
        assertEquals(0, bot.stats["tickets"])
        assertEquals(0, bot.stats["lost"])
        assertTrue("sheet_without_ticket" in kept, "kept $kept")
    }

    /** A sheet and no counter to read: the sheet alone books the ticket. */
    @Test
    fun `the sheet alone books a ticket when the counter cannot be read`() {
        val (bot, _) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            Dungeon.REWARD, "dialog", "dialog",
            "dialog:1"), tickets = 1, clearAfter = 5)
        assertEquals(1, bot.stats["tickets"])
        assertTrue(bot.sim.messages.any { "the counter could not be read" in it })
    }

    /**
     * Neither witness: a counter that cannot be read and no sheet. Counted
     * as a lost run -- and a second in a row parks the pass, because
     * nothing it spends can be counted any more.
     */
    @Test
    fun `an unreadable counter twice in a row parks the pass`() {
        val (bot, kept) = ticketCase(listOf(
            Dungeon.LIST, "dialog",
            "dialog", "dialog",
            "dialog",
            "dialog", "dialog",
            "dialog"), tickets = 3, clearAfter = 10)
        assertEquals(2, bot.taps("Attempt"), bot.sim.messages.toString())
        assertEquals(2, bot.stats["lost"])
        assertNotNull(bot.parkedBecause)
        assertTrue(bot.parkedBecause!!.contains("could not be read twice"), bot.parkedBecause)
        assertEquals(2, kept.count { it == "counter_unreadable" })
    }

    /**
     * Apocalymon Wall: no Reward sheet, a Results window with a narrow Close
     * over the panel instead. Read with the window up, the counter was
     * unreadable on both runs of a live pass (LDPlayer 2026-09-23) and the
     * pass parked with two tickets gone. The window is closed first and the
     * counter read off the panel under it.
     */
    @Test
    fun `a results window after the run is closed before the counter is read`() {
        val (bot, kept) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            "close", "close",                           // the run's Results window
            "dialog:1",                                 // closed: the panel, 2 -> 1
            "dialog:1"), tickets = 1, clearAfter = 5, withClear = false)
        assertEquals(1, bot.taps("Attempt"), bot.sim.messages.toString())
        assertEquals(1, bot.taps("Close"))
        assertEquals(1, bot.stats["tickets"], bot.sim.messages.toString())
        assertEquals(0, bot.stats["lost"])
        assertNull(bot.parkedBecause)
        assertTrue("counter_unreadable" !in kept, "kept $kept")
    }

    /**
     * Network Defense Ops, the party dungeon: its panel has no counter over
     * its buttons, the "n/2" stands above the panel, and a look that finds
     * nothing over the button asks there (LDPlayer 2026-09-23, two runs
     * unreadable and the chain parked).
     */
    @Test
    fun `the party panel's counter is read above the panel`() {
        val sim = Sim(listOf(Dungeon.DIALOG))
        val bot = object : Bot(sim, stubWaitBack = false) {
            override fun panelTickets(img: Mat, button: Dungeon.Button): Int? = null
            override fun headerTickets(img: Mat): Int? = 1
        }
        assertEquals(1, bot.counterNow(sim.nextState()))
    }

    /**
     * The overlay's plate stands on that counter at its default place, so
     * the dot is moved off the counter's rows for Network Defense Ops and put
     * back after it -- for that card alone, and also when the card ends by
     * throwing (the player's idea, 2026-09-23).
     */
    @Test
    fun `the overlay is moved off Network Defense Ops and back, and only there`() {
        val netdef = SkillSettings.DUNGEON_NAMES.indexOf(DungeonSkill.NETDEF)
        for ((index, label, want) in listOf(
                Triple(netdef, DungeonSkill.TOP, 2), Triple(0, DungeonSkill.TOP, 0))) {
            val bot = Bot(Sim(listOf(Dungeon.LIST, Dungeon.LIST)))
            bot.playEntry(index, label)
            assertEquals(want, bot.cap.overlay.size, "card $index: ${bot.cap.overlay}")
            if (want > 0) {
                assertEquals("clear ${Dungeon.HEADER_COUNTER[2]}-${Dungeon.HEADER_COUNTER[3]}",
                             bot.cap.overlay[0])
                assertEquals("back", bot.cap.overlay[1])
            }
        }
    }

    /**
     * The same dungeon ends a run on the list rather than on its panel. The
     * card's own badge is then the second look at the counter, and the run
     * is booked off it instead of counted as unreadable.
     */
    @Test
    fun `a run that ends on the list is booked off the card's badge`() {
        val sim = Sim(listOf(Dungeon.LIST, "dialog:2", Dungeon.LIST, "dialog:1", "dialog:1"),
                      withClear = false)
        val listReads = ArrayDeque(listOf(1))
        val bot = object : Bot(sim, attemptWorks = true) {
            override fun waitDialogBack(timeout: Double): Dungeon.Recognition? {
                val info = super.waitDialogBack(timeout)
                return if (info?.state == Dungeon.LIST) null else info
            }
            override fun listCounter(index: Int, label: String): Int? = listReads.removeFirstOrNull()
        }
        bot.minBattle = 0.0
        bot.budgets = mapOf(0 to 1)
        bot.attemptsBeforeClear = 5
        for (k in listOf("tickets", "attempts", "fights", "lost", "cleared")) bot.stats[k] = 0
        bot.playEntry(0, DungeonSkill.TOP)
        assertEquals(1, bot.taps("Attempt"), bot.sim.messages.toString())
        assertEquals(1, bot.stats["tickets"], bot.sim.messages.toString())
        assertEquals(0, bot.stats["lost"])
        assertNull(bot.parkedBecause)
    }

    /**
     * Apocalymon at 0/2 keeps its Attempt -- it has no ad button to show
     * instead -- and a tap on it only raises a window. A panel whose counter
     * reads 0 is left without a tap.
     */
    @Test
    fun `a panel that reads 0 is not attempted`() {
        val (bot, _) = ticketCase(listOf(
            Dungeon.LIST, "dialog:0",
            "dialog:0"), tickets = 5, clearAfter = 10, withClear = false)
        assertEquals(0, bot.taps("Attempt"), bot.sim.messages.toString())
        assertTrue(bot.sim.messages.any { "the panel says 0 tickets left" in it })
    }

    /**
     * The real wait: a battle is waited out, the sheet is read and then
     * tapped away, and the panel is handed back. And the counter is believed
     * only when two looks at the standing panel agree.
     */
    @Test
    fun `the wait reads the sheet before it taps it, and the counter wants two looks`() {
        val sim = Sim(listOf(Dungeon.BATTLE, Dungeon.REWARD, Dungeon.REWARD, Dungeon.DIALOG))
        val reads = ArrayDeque(listOf(2, 2, 2, 1))
        val bot = object : Bot(sim, stubWaitBack = false) {
            override fun panelTickets(img: Mat, button: Dungeon.Button): Int? = reads.removeFirst()
        }
        bot.tick = 0.0
        val back = bot.waitDialogBack(60.0)
        assertEquals(Dungeon.DIALOG, back?.state)
        assertTrue(bot.rewardSeen, "the sheet was seen")
        assertEquals(2, bot.taps("close the Reward sheet"), "and tapped, once per look it stood")
        assertTrue(sim.messages.count { "the Reward sheet is up" in it } == 1, "said once")
        assertEquals(2, bot.counterNow(back!!), "two looks, both 2")
        assertNull(bot.counterNow(back), "2 then 1: not a counter to book against")
    }

    /**
     * The black frame and "Now Loading" between a lost run and its panel are
     * not tapped: the tap high up is the one that closes a panel, and live on
     * LDPlayer one landed just as the panel came back. A screen no reader
     * knows is tapped only once it has stood [DungeonSkill.UNKNOWN_HOLD].
     */
    @Test
    fun `a screen between two known ones is not tapped, one that stays is`() {
        val short = Sim(listOf(Dungeon.UNKNOWN, Dungeon.UNKNOWN, Dungeon.UNKNOWN, Dungeon.DIALOG))
        val quick = Bot(short, stubWaitBack = false)
        quick.tick = 0.0
        assertEquals(Dungeon.DIALOG, quick.waitDialogBack(60.0)?.state)
        assertEquals(0, quick.taps("neutral, accept the reward"), "tapped into a transition")

        val long = Sim(listOf(Dungeon.UNKNOWN, Dungeon.UNKNOWN, Dungeon.UNKNOWN, Dungeon.DIALOG))
        val stuck = Bot(long, stubWaitBack = false)
        stuck.tick = 0.0
        stuck.unknownHold = 0.0
        assertEquals(Dungeon.DIALOG, stuck.waitDialogBack(60.0)?.state)
        assertTrue(stuck.taps("neutral, accept the reward") > 0, "a screen that stays is tapped")
    }

    /** The ceiling holds: a panel that never becomes anything is left. */
    @Test
    fun `the loop ceiling holds`() {
        val (bot, _) = ticketCase(List(300) { Dungeon.UNKNOWN }, tickets = 5, clearAfter = 10)
        assertTrue(bot.sim.messages.any { "loop limit reached" in it }, bot.sim.messages.toString())
        assertEquals(14, bot.taps("neutral"), "the flow rig's maxLoops")
        bot.maxLoops = DungeonSkill.LOOP_CAP
        assertEquals(3 * (10 + 5) + 6, bot.loopsFor(5))
        bot.attemptsBeforeClear = 99
        assertEquals(DungeonSkill.LOOP_CAP, bot.loopsFor(99))
    }

    /**
     * Two passes in a row count right: the card adds up what a pass hands
     * over, so the second pass hands over its own and not both
     * (NOTES.md, "A counter nothing clears is counted again").
     */
    @Test
    fun `two passes in a row each hand over their own tickets`() {
        val script = listOf(Dungeon.LIST, "dialog:2",
                            Dungeon.REWARD, "dialog:1", "dialog:1",
                            "dialog:1", Dungeon.LIST)
        val sim = Sim(script)
        val bot = object : Bot(sim, attemptWorks = true,
                               settings = { DungeonSkill.Settings(budgets = mapOf(0 to 1), survey = false,
                                                                  attemptsBeforeClear = 3) }) {
            override fun plan(): Pair<Int, Int> = 1 to 0
            override fun scrollTop(swipes: Int) {}
            override fun scrollBottom(swipes: Int) {}
        }
        bot.minBattle = 0.0
        for (pass in 1..2) {
            sim.i = 0
            bot.work(bot.frame)
            assertEquals(1, bot.lastCounts["tickets"], "pass $pass: ${bot.sim.messages}")
            assertEquals(1, bot.lastCounts["fights"], "pass $pass")
            assertEquals(3, bot.attemptsBeforeClear, "the setting arrived")
        }
    }

    // ------------------------------------------------------------------
    // 9. The seam (the phone's own; the PC has nothing to compare with)
    // ------------------------------------------------------------------
    /**
     * The 44th case, in place of the PC's dry run: a `work` that had a panel
     * open and does not find the list again says what it was doing, and the
     * director presses OK on that one prompt (Skill.kt, [Outcome.leaving]).
     * A `work` that ends on the list, and one that never opened a panel, say
     * nothing -- and a prompt after those is nobody's.
     */
    @Test
    fun `work names the prompt it may have left standing, and only then`() {
        // A panel was open, and the last look is the prompt: named.
        assertEquals(DungeonSkill.LEAVING,
                     seamCase(listOf(Dungeon.DIALOG_PARTY, Dungeon.EXIT), panel = true).leaving)
        // A panel was open and the list is back: nothing to name.
        assertNull(seamCase(listOf(Dungeon.DIALOG_PARTY, Dungeon.LIST), panel = true).leaving)
        // No panel was ever open, so the prompt in front is not this skill's.
        assertNull(seamCase(listOf(Dungeon.EXIT, Dungeon.EXIT), panel = false).leaving)
    }

    /** One `work` whose play does nothing but walk the list-return, or not. */
    private fun seamCase(script: List<String>, panel: Boolean): Outcome {
        val sim = Sim(script)
        val bot = object : Bot(sim, settings = { DungeonSkill.Settings(budgets = mapOf(0 to 1)) },
                               stubReturnToList = false) {
            override fun play(): Outcome {
                if (panel) returnToList(tries = 1)
                return Outcome.DONE
            }
        }
        return bot.work(bot.frame)
    }

    @Test
    fun `the seam answers for the dungeon list and for a budget above zero`() {
        var budgets = mapOf(0 to 0, 1 to 2)
        val skill = Bot(Sim(emptyList()), settings = { DungeonSkill.Settings(budgets) })
        assertTrue(skill.worksOn(Director.DUNGEON_LIST))
        assertFalse(skill.worksOn(Director.MAIN))
        assertFalse(skill.worksOn(Director.UNKNOWN))
        // chain.dungeon_has_budget: a dungeon with n > 0, asked fresh.
        assertTrue(skill.hasBudget())
        budgets = mapOf(0 to 0, 1 to 0)
        assertFalse(skill.hasBudget())
        budgets = emptyMap()
        assertFalse(skill.hasBudget())
    }

    @Test
    fun `the main switch is asked between two actions and ends the pass`() {
        val sim = Sim(listOf(Dungeon.LIST))
        var switch = true
        val bot = object : DungeonSkill(
            DirectorTest.FakeCapture(),
            { DungeonSkill.Settings(budgets = mapOf(0 to 1, 1 to 1), survey = false) },
            log = { sim.messages.add(it) }, on = { switch },
            sleep = {}, now = { CLOCK += TICK; CLOCK }, patience = 0.0) {
            val frame: Mat = Mat(1390, 805, CvType.CV_8UC3, Scalar(30.0, 30.0, 30.0))
            var played = 0
            override fun grab(): Mat = frame
            override fun tap(fx: Double, fy: Double, was: String) {}
            override fun scrollTop(swipes: Int) {}
            override fun scrollBottom(swipes: Int) {}
            override fun listCards(img: Mat): List<Double> = listOf(0.3, 0.5, 0.7, 0.9, 1.0)
            override fun recognise(img: Mat): Dungeon.Recognition = sim.nextState()
            override fun labelOf(index: Int, label: String) = "Test"
            override fun playEntry(index: Int, label: String, key: Pair<String, Int>?) {
                played += 1
                switch = false    // the player hits the switch between two cards
            }
        }
        val outcome = bot.work(bot.frame)
        assertEquals(1, bot.played, "a card was started after the switch went off")
        assertEquals(Result.STOPPED, outcome.result, outcome.toString())
        assertTrue(sim.messages.contains("stopped"), sim.messages.toString())
    }

    private companion object {
        /**
         * A clock that moves on every read, so that every `while (now() <
         * end)` in the skill ends. make_bot leaves `time.time()` real and
         * gets the same thing: a battle of no measurable length, which
         * min_battle then calls no battle.
         */
        const val TICK = 0.001
        var CLOCK = 0.0
    }
}
