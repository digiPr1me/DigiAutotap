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
 * Clear Previous Difficulty after N of them (notes/dungeons.md, "A lost dungeon run",
 * 2026-09-23) -- a lost run, the two witnesses of a ticket and where they
 * disagree, the ceiling, and two passes in a row.
 *
 * Group 11 is the daily dungeon by the minute (PLAN_DAILY_LOST_SECTOR_
 * PRESETS.md 4.3, DL3), a game played by the clock as 10b's Network Defense
 * Ops is, and none of the cases before it moved for it.
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
        override fun tap(fx: Double, fy: Double, was: String, anchor: Dungeon.Anchor) { sim.clicks.add(was) }
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
        // What the two readers a wait asks on every look say about the rig's
        // one frame, asked once: a hold of three seconds at a millisecond a
        // look is 1,500 looks, and both readers on every one of them were
        // 203 of this suite's 209 seconds, all in "the loop ceiling holds"
        // (measured 2026-10-03). Any other picture is read as ever.
        private val framePassing: String? by lazy { super.passing(frame) }
        private val frameTitle: Boolean by lazy { super.titleScreen(frame) }
        override fun passing(img: Mat): String? = if (img === frame) framePassing else super.passing(img)
        override fun titleScreen(img: Mat): Boolean = if (img === frame) frameTitle else super.titleScreen(img)
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
        override fun scrollTop(swipes: Int): Mat? { tops += 1; return frame }
        override fun scrollBottom(swipes: Int): Mat? { bottoms += 1; return frame }
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
                override fun survey(positions: List<Int>, label: String, img: Mat): List<Int> = positions
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

        // The game's download dialog (PLAN_RELEASE_1_3.md B1): grey, its
        // message two lines tall. Its Cancel ends the game as the exit
        // prompt's OK does, and its OK is the director's -- so nothing at
        // all is tapped here, and the pass is over.
        val two = Paint.prompt(pink = false, lines = 2)
        val one = Paint.prompt(pink = false, lines = 1)
        assertEquals(Dungeon.KIND_DOWNLOAD, Dungeon.confirmKind(two, Paint.GREY_OK))
        assertEquals("beenden", Dungeon.confirmKind(one, Paint.GREY_OK))
        assertEquals(Dungeon.KIND_DOWNLOAD, Dungeon.recognise(two).exitKind)
        Paint.release(two, one)
        val (downloadClicks, downloadKept) = answer(Dungeon.KIND_DOWNLOAD, DungeonSkill.LEAVING)
        println("  the download, even on the way out of a dungeon: $downloadClicks, kept $downloadKept")
        assertEquals(emptyList(), downloadClicks)
        assertEquals(listOf("download"), downloadKept)
        assertTrue(bot.downloadAsked)
        assertEquals(DungeonSkill.DOWNLOAD_WHY, bot.parkedBecause)
        assertFalse(bot.stillOn(), "the pass goes on over the download dialog")
    }

    /**
     * The download dialog in the middle of a card: the loop taps nothing
     * after it -- no Attempt, no back key, no way home -- and the pass
     * hands back parked, for the director to answer it.
     */
    @Test
    fun `the game's download dialog in the middle of a card ends the pass with nothing tapped`() {
        // In the list, the card tapped, and where its panel should be, the dialog.
        val sim = Sim(listOf(Dungeon.LIST, Dungeon.EXIT, Dungeon.DIALOG, Dungeon.DIALOG),
                      confirmKind = Dungeon.KIND_DOWNLOAD)
        val bot = Bot(sim, stubDismissConfirm = false)
        bot.playEntry(0, DungeonSkill.TOP)
        println("  the download dialog on a card: clicks ${sim.clicks}, looks ${sim.i}")
        assertEquals(listOf("Test"), sim.clicks, "only the card, before the dialog")
        assertEquals(2, sim.i, "the card was looked at again after the dialog")
        assertTrue(sim.messages.any { it.contains("the game asks to download its data -- leaving it to the director") },
                   "${sim.messages}")
        assertEquals(DungeonSkill.DOWNLOAD_WHY, bot.parkedBecause)
        assertFalse(bot.goHome(), "no way home from under the dialog")
        assertEquals(listOf("Test"), sim.clicks)
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
            override fun tap(fx: Double, fy: Double, was: String, anchor: Dungeon.Anchor) { screen.presses += 1 }
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
            override fun tap(fx: Double, fy: Double, was: String, anchor: Dungeon.Anchor) { dead.presses += 1 }
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
            override fun tap(fx: Double, fy: Double, was: String, anchor: Dungeon.Anchor) { thrower.presses += 1 }
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

    /**
     * The Dungeons page's ad switch, off (2026-09-30, the default): the
     * panel at 0 tickets shows the film button, and the card ends there with
     * nothing tapped on it -- booked as done for this session, so a second
     * look does not open it again.
     */
    @Test
    fun `with the ad switch off the film button ends the card untapped`() {
        val sim = Sim(List(6) { Dungeon.DIALOG_AD })
        val bot = Bot(sim)
        bot.useAds = false
        bot.budgets = mapOf(0 to 4)
        bot.playEntry(0, DungeonSkill.TOP)
        assertEquals(0, sim.clicks.count { it == "ad" }, "${sim.clicks}")
        assertTrue(sim.messages.any { it.contains("the free ads are not taken here (the Ad Rewards card)") },
                   "${sim.messages}")
        assertEquals(emptyList(), bot.survey(listOf(0), DungeonSkill.TOP, bot.frame),
                     "the card is not surveyed as playable again")
    }

    /**
     * A bot on what [Stored.dungeon] -- or the Quest Loop's
     * [QuestSkill.dungeonSettings] -- hands a pass, whose window in front is
     * an ad from the film button's tap on where [adAfterTap] says so.
     */
    private class FileBot(sim: Sim, s: DungeonSkill.Settings, val adAfterTap: Boolean = false) : Bot(sim) {
        init {
            useAds = s.useAds
            budgets = mapOf(0 to 4)
        }
        override fun adInFront(): Boolean = adAfterTap && "ad" in sim.clicks
    }

    /** The file a phone can carry into 1.3: the picks on, the pass as [pass], and `ad_watch` on, which nothing reads. */
    private fun adFile(pass: Boolean) = MapSettings(mapOf(
        SkillSettings.AD_PASS_KEY to pass, "ad_watch" to true,
        Stored.DUNGEON_ADS_KEY to true, Stored.SUMMON_ADS_KEY to true))

    /** What a pass reads out of [file], on a phone with a supporter code or without: [Stored] asks no code. */
    private fun stored(file: MapSettings) = Stored.dungeon(file)

    /**
     * Without the Ad Skip Pass the film button is never tapped, for
     * anybody: the Dungeons pick on, a code, and `ad_watch` on in the file
     * -- counted over two passes over the card (2026-10-03,
     * PLAN_ABSCHLUSS_1_3.md 3.6). The card ends on its tickets, and nothing
     * parks for the ads left on it.
     */
    @Test
    fun `without the pass no film button is tapped, with a code and ad_watch in the file, over two passes`() {
        val sim = Sim(List(12) { Dungeon.DIALOG_AD })
        val bot = FileBot(sim, stored(adFile(pass = false)))
        bot.playEntry(0, DungeonSkill.TOP)
        bot.playEntry(0, DungeonSkill.TOP)
        assertEquals(0, sim.clicks.count { it == "ad" }, "${sim.clicks}")
        assertNull(bot.parkedBecause)
        assertTrue(sim.messages.any { it.contains("the free ads are not taken here") }, "${sim.messages}")
    }

    /**
     * With the pass: the film button, and the ticket comes with the tap --
     * counted at the look after it, where the game's question would have
     * stood.
     */
    @Test
    fun `with the pass the film button is tapped and the ticket comes`() {
        val sim = Sim(listOf(Dungeon.DIALOG_AD, Dungeon.DIALOG_AD, Dungeon.DIALOG_AD, Dungeon.DIALOG, Dungeon.LIST))
        val bot = FileBot(sim, stored(adFile(pass = true)))
        bot.playEntry(0, DungeonSkill.TOP)
        assertEquals(1, sim.clicks.count { it == "ad" }, "${sim.clicks}")
        assertEquals(1, bot.stats["ads"], "the ticket the pass gave is counted")
        assertNull(bot.parkedBecause)
    }

    /**
     * The pass switched on for an account without it: the film button
     * raises the game's question "View ads?" all the same. Nothing is
     * tapped on it -- with a code as well -- and the pass parks with the
     * one sentence; until 2026-10-02 the panel's loop read the question as
     * the party's prompt and answered OK, which started the ad.
     */
    @Test
    fun `with the pass the game's question parks the pass untouched, with a code as well`() {
        val sim = Sim(listOf(Dungeon.DIALOG_AD, Dungeon.DIALOG_AD, Dungeon.DIALOG_AD, Dungeon.EXIT),
                      confirmKind = "party")
        val bot = FileBot(sim, stored(adFile(pass = true)))
        bot.playEntry(0, DungeonSkill.TOP)
        assertEquals(listOf("ad"), sim.clicks.dropWhile { it != "ad" }, "nothing after the film button: ${sim.clicks}")
        assertEquals(0, bot.cap.backs)
        assertEquals(FreeAds.PARK, bot.parkedBecause)
        assertTrue(bot.adParked)
        assertFalse(bot.stillOn(), "no next card behind the question")
    }

    /** The same with the video in front at the look after the film button: no gesture after it, and the park. */
    @Test
    fun `with the pass an ad in front after the film button parks the pass and nothing touches it`() {
        val sim = Sim(List(8) { Dungeon.DIALOG_AD })
        val bot = FileBot(sim, stored(adFile(pass = true)), adAfterTap = true)
        bot.playEntry(0, DungeonSkill.TOP)
        assertEquals(listOf("ad"), sim.clicks.dropWhile { it != "ad" }, "nothing after the film button: ${sim.clicks}")
        assertEquals(0, bot.cap.backs)
        assertTrue(bot.cap.taps.isEmpty(), "no tap reached the hand: ${bot.cap.taps}")
        assertEquals(FreeAds.PARK, bot.parkedBecause)
        assertFalse(bot.goHome(), "and no way home through it")
    }

    /**
     * The Quest Loop hands its card to a bot built on
     * [QuestSkill.dungeonSettings]: without the pass that bot taps no film
     * button either, a code and `ad_watch` in the file, over two passes.
     */
    @Test
    fun `the Quest Loop's dungeon bot taps no film button without the pass, over two passes`() {
        val loop = QuestSkill(DirectorTest.FakeCapture(), { Stored.quest(adFile(pass = false)) })
        val sim = Sim(List(12) { Dungeon.DIALOG_AD })
        val bot = FileBot(sim, loop.dungeonSettings(0, 4))
        bot.playEntry(0, DungeonSkill.TOP)
        bot.playEntry(0, DungeonSkill.TOP)
        assertEquals(0, sim.clicks.count { it == "ad" }, "${sim.clicks}")
        val withPass = QuestSkill(DirectorTest.FakeCapture(), { Stored.quest(adFile(pass = true)) })
        assertTrue(withPass.dungeonSettings(0, 4).useAds, "with the pass the loop's card takes them")
    }

    /**
     * Off, the survey reads a card by its tickets alone: 0 tickets with two
     * ads left is nothing for this pass, 3 tickets are a counted 3 -- the
     * ceiling the panel then plays to -- and the same cards with the switch
     * on read as they always did.
     */
    @Test
    fun `with the ad switch off the survey counts the tickets alone`() {
        fun survey(budget: Dungeon.Budget, useAds: Boolean): Pair<List<Int>, Sim> {
            val sim = Sim(listOf(Dungeon.LIST))
            val bot = object : Bot(sim) {
                override fun cardBudget(img: Mat, fy: Double, fh: Double) = budget
            }
            bot.useAds = useAds
            bot.budgets = mapOf(0 to 4)
            return bot.survey(listOf(0), DungeonSkill.TOP, bot.frame) to sim
        }
        val (empty, emptySim) = survey(Dungeon.Budget(0, 2, 2), useAds = false)
        assertEquals(emptyList(), empty, "${emptySim.messages}")
        assertTrue(emptySim.messages.any { it.contains("no tickets left today, and ads are switched off") },
                   "${emptySim.messages}")
        val (three, threeSim) = survey(Dungeon.Budget(3, null, null), useAds = false)
        assertEquals(listOf(0), three)
        assertTrue(threeSim.messages.any { it.contains("3 tickets, ads switched off, spending 3") },
                   "${threeSim.messages}")
        assertEquals(listOf(0), survey(Dungeon.Budget(0, 2, 2), useAds = true).first, "on: the ads are the card's")
        assertEquals(3, DungeonSkill.owed(Dungeon.Budget(3, null, null), useAds = false))
        assertNull(DungeonSkill.owed(Dungeon.Budget(3, null, null), useAds = true))
        assertEquals(2, DungeonSkill.owed(Dungeon.Budget(0, 2, 2), useAds = true))
        assertEquals(0, DungeonSkill.owed(Dungeon.Budget(0, 2, 2), useAds = false))
    }

    /**
     * From the card to the skill: the Dungeons pick, off by default, is what
     * a pass runs on -- and since 2026-10-03 only with the pass beside it
     * (PaywallTest has the whole table).
     */
    @Test
    fun `the ad switch reaches the skill off by default`() {
        assertFalse(Stored.dungeon(MapSettings()).useAds)
        val s = MapSettings()
        s.put(Stored.DUNGEON_ADS_KEY, true)
        assertFalse(Stored.dungeon(s).useAds, "not without the pass")
        s.put(SkillSettings.AD_PASS_KEY, true)
        assertTrue(Stored.dungeon(s).useAds)
    }

    /** The Dungeons page's number counts the failed attempts alone (2026-10-04). */
    @Test
    fun `the Dungeons page counts only failed attempts towards Clear Previous Difficulty`() {
        val s = MapSettings()
        s.put(Stored.DUNGEON_CLEAR_KEY, 3)
        assertTrue(Stored.dungeon(s).countLostOnly)
        assertEquals(3, Stored.dungeon(s).attemptsBeforeClear)
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
    // 10. Tickets, not attempts (2026-09-23, notes/dungeons.md "A lost dungeon run")
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
     * N failed attempts on one dungeon, and every ticket still wanted goes
     * to Clear Previous Difficulty -- the count does not start again per
     * ticket (the player's rule).
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

    /**
     * Only a failed attempt counts (the player, 2026-10-04: "Failed attempts
     * before clicking Clear Previous Difficulty"): a run that ends without a
     * reward and goes back to the panel. A won run spends its ticket and
     * leaves the count where it was, so with N = 1 a win and then a loss
     * come before Clear Previous Difficulty -- until that day the win alone
     * was the one attempt, and the second ticket went to Clear at once.
     */
    @Test
    fun `a won run does not count towards Clear Previous Difficulty, a failed one does`() {
        val (bot, _) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            Dungeon.REWARD, "dialog:1", "dialog:1",     // won: the sheet, 2 -> 1
            "dialog:1",
            "dialog:1", "dialog:1",                     // failed: back, and still 1
            "dialog:1",
            Dungeon.REWARD, "dialog:0", "dialog:0",     // Clear: the sheet, 1 -> 0
            "dialog:0"), tickets = 2, clearAfter = 1)
        val said = bot.sim.messages.joinToString("\n")
        assertEquals(2, bot.taps("Attempt"), said)
        assertEquals(1, bot.taps("Clear Previous Difficulty"), said)
        assertEquals(2, bot.stats["tickets"], said)
        assertEquals(1, bot.stats["lost"], said)
        assertEquals(1, bot.stats["cleared"], said)
        assertTrue(bot.sim.messages.any { "attempt 2, 1 of 2 tickets spent, 0 of 1 failed" in it }, said)
        assertTrue(bot.sim.messages.any {
            "1 failed attempt -- the tickets still wanted go to Clear Previous Difficulty" in it }, said)

        // Every attempt counted, as the quest loop's card still has it: the
        // win is the one attempt, and the second ticket goes to Clear.
        val sim = Sim(listOf(
            Dungeon.LIST, "dialog:2",
            Dungeon.REWARD, "dialog:1", "dialog:1",     // won: the sheet, 2 -> 1
            "dialog:1",
            Dungeon.REWARD, "dialog:0", "dialog:0",     // Clear: the sheet, 1 -> 0
            "dialog:0"))
        val every = Bot(sim, attemptWorks = true)
        every.minBattle = 0.0
        every.budgets = mapOf(0 to 2)
        every.attemptsBeforeClear = 1
        every.countLostOnly = false
        for (k in listOf("tickets", "attempts", "fights", "lost", "cleared")) every.stats[k] = 0
        every.playEntry(0, DungeonSkill.TOP)
        assertEquals(1, every.taps("Attempt"), sim.messages.toString())
        assertEquals(1, every.taps("Clear Previous Difficulty"), sim.messages.toString())
        assertEquals(2, every.stats["tickets"])
    }

    /**
     * A run whose counter will not read after it and that raised no sheet is
     * [DungeonSkill.book]'s "counted as a lost run": no reward was seen, so
     * it is a failed attempt too.
     */
    @Test
    fun `a run with no sheet and an unread counter counts as failed`() {
        val (bot, kept) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            "dialog", "dialog",                         // no sheet, the counter unread
            "dialog:2",
            Dungeon.REWARD, "dialog:1", "dialog:1",     // Clear: the sheet, 2 -> 1
            "dialog:1"), tickets = 1, clearAfter = 1)
        val said = bot.sim.messages.joinToString("\n")
        assertEquals(1, bot.taps("Attempt"), said)
        assertEquals(1, bot.taps("Clear Previous Difficulty"), said)
        assertEquals(1, bot.stats["tickets"], said)
        assertTrue("counter_unreadable" in kept, "kept $kept")
    }

    /**
     * The failed attempts are the pass's: a second pass over the same card
     * starts from none and attempts again before it clears (NOTES.md, "A
     * new reader, or a new task", 6; notes/director.md, "A counter nothing
     * clears is counted again at the end of every pass").
     */
    @Test
    fun `two passes in a row each count their own failed attempts`() {
        val script = listOf(Dungeon.LIST, "dialog:2",
                            "dialog:2", "dialog:2",                 // failed: back, and still 2
                            "dialog:2",
                            Dungeon.REWARD, "dialog:1", "dialog:1", // Clear: the sheet, 2 -> 1
                            "dialog:1", Dungeon.LIST)
        val sim = Sim(script)
        val bot = object : Bot(sim, attemptWorks = true,
                               settings = { DungeonSkill.Settings(budgets = mapOf(0 to 1), survey = false,
                                                                  attemptsBeforeClear = 1) }) {
            override fun plan(): Pair<Int, Int> = 1 to 0
            override fun scrollTop(swipes: Int): Mat? = frame
            override fun scrollBottom(swipes: Int): Mat? = frame
        }
        bot.minBattle = 0.0
        for (pass in 1..2) {
            sim.i = 0
            sim.clicks.clear()
            bot.work(bot.frame)
            val said = "pass $pass: ${bot.sim.messages}"
            assertEquals(1, bot.taps("Attempt"), said)
            assertEquals(1, bot.taps("Clear Previous Difficulty"), said)
            assertEquals(1, bot.lastCounts["tickets"], said)
            assertEquals(1, bot.lastCounts["lost"], said)
        }
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
     * as an attempt -- a failed one since 2026-10-04, though the sheet came:
     * it may have been won, and left out of the count a counter that reads
     * wrong after every won run would have Attempt pressed on.
     */
    @Test
    fun `a sheet with a counter that did not move books nothing`() {
        val (bot, kept) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            Dungeon.REWARD, "dialog:2", "dialog:2",
            "dialog:2"), tickets = 1, clearAfter = 1, withClear = false)
        assertEquals(0, bot.stats["tickets"])
        assertEquals(0, bot.stats["lost"])
        assertEquals(1, bot.taps("Attempt"), "the one failed attempt is N: ${bot.sim.messages}")
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
    fun `an unreadable counter twice in a row ends the card, not the pass`() {
        val (bot, kept) = ticketCase(listOf(
            Dungeon.LIST, "dialog",
            "dialog", "dialog",
            "dialog",
            "dialog", "dialog",
            "dialog"), tickets = 3, clearAfter = 10)
        assertEquals(2, bot.taps("Attempt"), bot.sim.messages.toString())
        assertEquals(2, bot.stats["lost"])
        assertNull(bot.parkedBecause, "the pass used to park here; it leaves the card now")
        assertTrue(bot.sim.messages.any { it.contains("could not be read twice in a row, leaving it") },
                   bot.sim.messages.toString())
        assertEquals(2, kept.count { it == "counter_unreadable" })
    }

    /**
     * notes/dungeons.md, "A swipe is proved, not assumed": `unreadableInARow` counted over the pass,
     * so the last lost run on Bakemon and the first on Digifactory parked
     * the pass, and Network Defense Ops and Metal Sea were never opened.
     * Per card now: two unreadable runs end that card, the next card is
     * still opened, and the pass ends DONE.
     */
    @Test
    fun `after an unreadable card the next card is still played`() {
        val script = listOf(
            // card 1: two runs whose counter never reads
            Dungeon.LIST, "dialog", "dialog", "dialog", "dialog", "dialog", "dialog",
            // card 2: one won run, the sheet and the counter agree
            Dungeon.LIST, "dialog:2", Dungeon.REWARD, "dialog:1", "dialog:1", "dialog:1",
            Dungeon.LIST)
        val sim = Sim(script)
        val kept = ArrayList<String>()
        val bot = object : Bot(sim, attemptWorks = true,
                               settings = { DungeonSkill.Settings(budgets = mapOf(0 to 1, 1 to 1),
                                                                  survey = false) }) {
            override fun plan(): Pair<Int, Int> = 2 to 0
            override fun scrollTop(swipes: Int): Mat? = frame
            override fun scrollBottom(swipes: Int): Mat? = frame
            override fun listCards(img: Mat): List<Double> = listOf(0.3, 0.5, 0.7, 0.9, 1.0)
            /** The list with two cards on it, so that card 2 is there to open. */
            override fun recognise(img: Mat): Dungeon.Recognition =
                sim.nextState().let { if (it.state == Dungeon.LIST) it.copy(karten = listOf(0.3, 0.5)) else it }
            override fun saveUnknown(img: Mat, tag: String) { kept += tag }
        }
        bot.minBattle = 0.0
        val outcome = bot.work(bot.frame)
        assertEquals(Result.DONE, outcome.result, "${outcome} ${sim.messages}")
        assertEquals(3, bot.taps("Attempt"), sim.messages.toString())
        assertEquals(2, bot.stats["lost"])
        assertEquals(1, bot.stats["tickets"], "the second card's ticket was booked")
        assertEquals(2, kept.count { it == "counter_unreadable" })
        assertNull(bot.parkedBecause)
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
            override fun panelTickets(img: Mat, button: Dungeon.Button, anchor: Dungeon.Anchor): Int? = null
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
            override fun panelTickets(img: Mat, button: Dungeon.Button, anchor: Dungeon.Anchor): Int? = reads.removeFirst()
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

    // ------------------------------------------------------------------
    // 8b. The scroll is proved (notes/dungeons.md, "A swipe is proved, not assumed")
    // ------------------------------------------------------------------
    /** A skill whose list answers a scripted view per settled frame. */
    private open class Views(sim: Sim, views: List<List<Pair<Double, Double>>>,
                             settings: () -> DungeonSkill.Settings = { DungeonSkill.Settings(budgets = emptyMap()) })
        : Bot(sim, settings = settings) {
        val views = ArrayDeque(views)
        /** Settled frames looked at, one per swipe. */
        var served = 0
        private var last: List<Pair<Double, Double>> = BOTTOM_VIEW
        override fun listCardsWithSize(img: Mat): List<Pair<Double, Double>> {
            served += 1
            last = views.removeFirstOrNull() ?: BOTTOM_VIEW
            return last
        }
        /** The plan's count on the frame the scroll handed it: the same view, not another look. */
        override fun listCards(img: Mat): List<Double> = last.map { it.first }
        override fun labelOf(index: Int, label: String): String =
            DungeonSkill.dungeonLabel(index, label == DungeonSkill.BOTTOM, entries, visibleAtBottom)
    }

    /**
     * notes/dungeons.md, "A swipe is proved, not assumed": a swipe sent into the list's opening
     * animation is swallowed, the plan counts the four cards of the top view
     * as the bottom four, and every bottom card is played one off -- Network
     * Defense Ops under Metal Sea's budget, and not at all when that is 0.
     * The swipe is proved on a settled frame now: the first "bottom" here is
     * the top view, banner and all, the second is the bottom, and the plan
     * says 2 and 4, not 3 and 3, with card 3 of the bottom half Network
     * Defense Ops.
     */
    @Test
    fun `a swallowed swipe is repeated, and the plan counts the proved bottom`() {
        val bot = Views(Sim(listOf(Dungeon.LIST)), listOf(TOP_VIEW, BOTTOM_VIEW))
        assertEquals(2 to 4, bot.plan(), bot.sim.messages.toString())
        assertEquals(5, bot.visibleAtBottom)
        assertEquals(DungeonSkill.NETDEF, bot.labelOf(2, DungeonSkill.BOTTOM))
        assertTrue(bot.sim.messages.any { it.contains("did not take (4 cards, list at the top: true), once more") },
                   bot.sim.messages.toString())
        assertEquals(2, bot.served, "one look per swipe")
    }

    /** The list at the top, three times over: no count is taken on it, and the pass parks with the frame kept. */
    @Test
    fun `a swipe that never takes parks the plan`() {
        val kept = ArrayList<String>()
        val bot = object : Views(Sim(listOf(Dungeon.LIST)), List(6) { TOP_VIEW }) {
            override fun saveUnknown(img: Mat, tag: String) { kept += tag }
        }
        assertNull(bot.plan(), bot.sim.messages.toString())
        assertEquals(DungeonSkill.SCROLL_TRIES, bot.served)
        assertEquals(listOf("list_not_at_bottom"), kept)
        assertNotNull(bot.parkedBecause)
        assertTrue(bot.parkedBecause!!.contains("did not reach the bottom"), bot.parkedBecause)
        // The pass says the same, and plays nothing.
        val outcome = bot.play()
        assertEquals(Result.PARKED, outcome.result, outcome.toString())
        assertTrue(outcome.why.contains("did not reach the bottom"), outcome.toString())
        assertEquals(0, bot.taps("Test") + bot.taps("Attempt"))
    }

    /** A list in the middle of a scroll is neither end: four cards without a banner is not the bottom. */
    @Test
    fun `the bottom wants no banner and five cards`() {
        assertEquals(true, Dungeon.listAtTop(TOP_VIEW))
        assertEquals(false, Dungeon.listAtTop(BOTTOM_VIEW))
        assertEquals(false, Dungeon.listAtTop(MID_VIEW))
        assertEquals(true, Dungeon.listAtBottom(BOTTOM_VIEW))
        assertEquals(false, Dungeon.listAtBottom(MID_VIEW))
        assertEquals(false, Dungeon.listAtBottom(TOP_VIEW))
        assertNull(Dungeon.listAtTop(listOf(0.5 to 0.17)))
        assertNull(Dungeon.listAtBottom(emptyList()))
    }

    /** The painted list, top and bottom, measured by the real readers. */
    @Test
    fun `the banner is read off a painted list`() {
        val top = PaintDungeon.list(atTop = true)
        val bottom = PaintDungeon.list(atTop = false)
        val topCards = Dungeon.listCardsWithSize(top)
        val bottomCards = Dungeon.listCardsWithSize(bottom)
        println("  painted top: %d cards, first fh %.3f; bottom: %d cards, first fh %.3f".format(
            topCards.size, topCards.firstOrNull()?.second ?: -1.0,
            bottomCards.size, bottomCards.firstOrNull()?.second ?: -1.0))
        assertEquals(4, topCards.size)
        assertEquals(5, bottomCards.size)
        assertEquals(true, Dungeon.listAtTop(topCards))
        assertEquals(true, Dungeon.listAtBottom(bottomCards))
        assertEquals(false, Dungeon.listAtBottom(topCards))
        Paint.release(top, bottom)
    }

    /**
     * The same finding: the party search got two looks 4.5 s apart
     * and a neutral tap on anything it did not know, and a tap outside the
     * panel is what raises "Disband the party?". The search gets
     * [DungeonSkill.PARTY_WAIT] now, nothing is tapped while the panel shows
     * fewer than two slots or an unknown screen, and the second "Find a
     * Party" comes only after the wait.
     */
    @Test
    fun `the party search waits and taps nothing meanwhile`() {
        val sim = Sim(listOf(Dungeon.LIST) + List(400) { Dungeon.DIALOG_PARTY } + List(400) { Dungeon.UNKNOWN })
        val bot = Bot(sim)
        bot.partyWait = 0.1     // 100 reads of the clock
        bot.playEntry(0, DungeonSkill.TOP)
        assertEquals(2, bot.taps("Find a Party"), sim.messages.toString())
        assertEquals(0, bot.taps("neutral"), "tapped into the searching panel: ${sim.clicks}")
        assertEquals(0, bot.taps("Attempt"))
        assertEquals(2, sim.messages.count { it.startsWith("  no party after") }, sim.messages.toString())
        assertTrue(sim.messages.last().contains("no party found, moving on"), sim.messages.toString())
        // Two waits of 100 reads each, and the reads in between.
        assertTrue(sim.i >= 200, "the wait was cut short: ${sim.i} reads")
    }

    /** A party found ends the wait, and the loop goes on to Attempt. */
    @Test
    fun `a party found ends the wait`() {
        val sim = Sim(listOf(Dungeon.LIST, Dungeon.DIALOG_PARTY, Dungeon.UNKNOWN, Dungeon.UNKNOWN,
                             "dialog:0"))
        val bot = Bot(sim)
        bot.partyWait = 10.0
        bot.playEntry(0, DungeonSkill.TOP)
        assertEquals(1, bot.taps("Find a Party"), sim.messages.toString())
        assertTrue(sim.messages.any { it.contains("the party panel changed after") }, sim.messages.toString())
        assertTrue(sim.i < 20, "the wait ran on after the panel changed: ${sim.i} reads")
    }

    /**
     * The main loop's neutral tap on an unknown screen gets the patience
     * [DungeonSkill.waitDialogBack] has had since UNKNOWN_HOLD: a screen
     * between two known ones is not tapped, one that stays is.
     */
    @Test
    fun `an unknown screen in the main loop is held before it is tapped`() {
        val patient = Bot(Sim(listOf(Dungeon.LIST, Dungeon.UNKNOWN, Dungeon.UNKNOWN, "dialog:0")))
        patient.playEntry(0, DungeonSkill.TOP)
        assertEquals(0, patient.taps("neutral"), "tapped into a transition: ${patient.sim.clicks}")
        assertTrue(patient.sim.messages.any { it.contains("0 tickets left") }, patient.sim.messages.toString())

        val hasty = Bot(Sim(listOf(Dungeon.LIST, Dungeon.UNKNOWN, Dungeon.UNKNOWN, "dialog:0")))
        hasty.unknownHold = 0.0
        hasty.playEntry(0, DungeonSkill.TOP)
        assertEquals(2, hasty.taps("neutral"), "a screen that stays is tapped: ${hasty.sim.clicks}")
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
     * (notes/director.md, "A counter nothing clears is counted again").
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
            override fun scrollTop(swipes: Int): Mat? = frame
            override fun scrollBottom(swipes: Int): Mat? = frame
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
    // 10b. Network Defense Ops played through (PLAN_DAILY_LOST_SECTOR_PRESETS.md G1)
    // ------------------------------------------------------------------
    /**
     * Network Defense Ops as the game plays it, by the clock, from what the
     * live log of 2026-09-23 on LDPlayer says (the full-auto test's
     * `live.log`, 15:50 to 16:34) and one frame of it measured with today's
     * readers:
     *
     *  - A panel with no team-mates shows Find a Party; once a party is
     *    found **the game starts the run by itself** -- both searches of
     *    that day (15:50:44, 16:32:54) went on to a battle with no Attempt
     *    tapped, and the second one took the ticket the ad had just given.
     *    Found within six seconds both times.
     *  - The run ends in a Reward sheet the readers do not name: `recognise`
     *    says unknown on the frame of 15:52 (the sheet's blue band 0.184 of
     *    [Dungeon.SHEET_BAND] against [Dungeon.SHEET_SHARE_MIN] 0.37), and
     *    all five runs of that day were closed by the wait's tap high up
     *    after [DungeonSkill.UNKNOWN_HOLD]. It takes its tap only after a
     *    moment (three taps a second apart every time).
     *  - The party stays for the next run; a tap outside the panel, or into
     *    the panel as it comes back, raises "leave the party?" -- 15:52:28,
     *    one run of five -- and OK on it leaves the party and shows the list.
     *  - At 0 tickets the panel shows the film button, party or not; the ad
     *    (with the pass) gives a ticket and a Reward sheet the readers do
     *    name. The back key on the panel raises the same prompt.
     *
     * The numbers of the clock: the search 5 s, a run 30 s (31 and 33 live),
     * the sheet ready after 4.5 s, and the way from the sheet to the panel
     * 1.4 s -- the transition UNKNOWN_HOLD was measured on.
     */
    private class Netdef(var tickets: Int, var ads: Int,
                         /** False: the party fills the panel and Attempt starts the run (the pass of 2026-09-29). */
                         val autoStart: Boolean = true,
                         /** Seconds after the party fills in which the header counter does not read. */
                         val blindAfterJoin: Double = 0.0,
                         /** How long a run lasts, seconds. */
                         val runLength: Double = RUN,
                         /**
                          * The run's Reward sheet as `recognise` reads it since
                          * B11 (PLAN_RELEASE_1_3.md, 2026-10-01): named, where the
                          * cases before it play the day's reading, unknown.
                          */
                         val sheetNamed: Boolean = false,
                         /** False: the party leaves after every run, and the next one wants a search. */
                         val partyStays: Boolean = true,
                         /**
                          * The windows of the day's reset the game puts over the
                          * list the first time the panel is left ([NEWS],
                          * [NOTICES], [LOGIN], [IDLE]; PLAN_RELEASE_1_3.md B44),
                          * each closing on its own taps once it has stood
                          * [ENTRY], as the morning of 2026-10-01 showed.
                          */
                         resetWindows: List<String> = emptyList()) {
        private var blindUntil = 0.0
        /** The reset's windows still to come, the first in front while [phase] is "reset". */
        val pending = ArrayDeque(resetWindows)
        /** Taps on a reset window that are not one of the ways it closes: Claim, the box, the campaign. */
        val wrong = ArrayList<String>()
        /** How each reset window went: (kind, the tap that closed it). */
        val closedBy = ArrayList<Pair<String, String>>()
        /** Every tap while one of the reset's windows was in front. */
        val resetClicks = ArrayList<String>()
        var t = 0.0
        var phase = "list"
        private var since = 0.0
        var party = false
        /** Runs the game played, whoever started them. */
        var runs = 0
        val clicks = ArrayList<String>()

        private fun go(p: String) { phase = p; since = t }
        private fun startRun() { tickets -= 1; runs += 1; go("battle") }

        fun state(): Dungeon.Recognition {
            when (phase) {
                "search" -> if (t - since >= SEARCH) {
                    party = true
                    if (autoStart) startRun() else { go("panel"); blindUntil = t + blindAfterJoin }
                }
                "battle" -> if (t - since >= runLength) go("sheet")
                "coming" -> if (t - since >= TRANSITION) {
                    go("panel")
                    if (!partyStays) party = false
                }
            }
            return when (phase) {
                "list" -> rec(Dungeon.LIST, karten = CARDS)
                "panel" -> when {
                    tickets == 0 -> rec(Dungeon.DIALOG_AD, ad = PARTY_BUTTON)
                    else -> rec(Dungeon.DIALOG_PARTY, attempt = ATTEMPT, party = PARTY_BUTTON,
                                clear = CLEAR, partyVoll = if (party) 3 else 1)
                }
                "battle" -> rec(Dungeon.BATTLE, giveup = GIVE_UP)
                // As recognise reads the real frames: the news's OK is a
                // Give Up, Notices' Campaigns tab and the login bonus's tile
                // an Attempt, Idle Rewards' Claim an Attempt beside a Clear.
                "reset" -> when (pending.first()) {
                    NEWS -> rec(Dungeon.BATTLE, giveup = NEWS_OK)
                    IDLE -> rec(Dungeon.DIALOG, attempt = IDLE_CLAIM, clear = IDLE_EXTRA)
                    else -> rec(Dungeon.DIALOG, attempt = Dungeon.Button(0.476, 0.7323, 0.1988, 0.0263))
                }
                "sheet" -> if (sheetNamed) rec(Dungeon.REWARD) else rec(Dungeon.UNKNOWN)
                "adsheet" -> rec(Dungeon.REWARD)
                "prompt" -> rec(Dungeon.EXIT, exitOk = OK)
                else -> rec(Dungeon.UNKNOWN)    // the search, the sheet, the panel coming back
            }
        }

        fun tap(was: String) {
            clicks += was
            if (phase == "reset") resetClicks += was
            when (phase) {
                "list" -> if (was == DungeonSkill.NETDEF) go("panel")
                "panel" -> when (was) {
                    "Find a Party" -> if (!party) go("search")
                    "Attempt" -> if (party && tickets > 0) startRun()
                    "ad" -> if (ads > 0) { ads -= 1; tickets += 1; go("adsheet") }
                    else -> if (was.startsWith("neutral")) go("prompt")   // outside the panel
                }
                "sheet" -> if (t - since >= SHEET_READY) go("coming")
                "coming" -> go("prompt")      // the panel as it comes in: outside it
                "adsheet" -> go("panel")
                "prompt" -> when (was) {
                    "OK, leave the party" -> { party = false; go(if (pending.isEmpty()) "list" else "reset") }
                    "Cancel" -> go("panel")
                }
                "reset" -> {
                    val kind = pending.first()
                    // A window still growing in takes no tap (084458 to 084507).
                    if (t - since < ENTRY) return
                    val closes = when (kind) {
                        NEWS -> was == "OK of the news" || was.startsWith("neutral") || was == "dungeon tab"
                        IDLE -> was == "beside Idle Rewards" || was.startsWith("neutral") || was == "dungeon tab"
                        else -> was.startsWith("neutral") || was == "dungeon tab"
                    }
                    if (!closes) { wrong += "$kind: $was"; return }
                    closedBy += kind to was
                    pending.removeFirst()
                    go(if (pending.isEmpty()) "list" else "reset")
                }
            }
        }

        /** The reset window in front, or null. */
        fun window(): String? = if (phase == "reset") pending.first() else null

        fun back() { if (phase == "panel") go("prompt") }

        /** The "n/2" over the panel (Dungeon.HEADER_COUNTER), on the panel alone. */
        fun header(): Int? = if (phase == "panel" && t >= blindUntil) tickets else null

        private fun rec(state: String, attempt: Dungeon.Button? = null, party: Dungeon.Button? = null,
                        ad: Dungeon.Button? = null, clear: Dungeon.Button? = null,
                        karten: List<Double> = emptyList(), giveup: Dungeon.Button? = null,
                        partyVoll: Int = 0, exitOk: Dungeon.Button? = null) =
            Dungeon.Recognition(state, attempt, party, ad, clear, emptyList(), emptyList(), karten,
                                giveup, partyVoll = partyVoll, exitOk = exitOk,
                                exitKind = if (exitOk != null) "party" else null)

        companion object {
            const val SEARCH = 5.0
            const val RUN = 30.0
            const val SHEET_READY = 4.5
            const val TRANSITION = 1.4
            /** The list at the bottom, five cards; Network Defense Ops is the third. */
            val CARDS = listOf(0.229, 0.378, 0.528, 0.678, 0.828)
            // corpus/dungeon/panel_party_netdef_155153, as the oracle reads it
            val ATTEMPT = Dungeon.Button(0.6155, 0.5821, 0.2511, 0.0439)
            val CLEAR = Dungeon.Button(0.3378, 0.5821, 0.2502, 0.0439)
            val PARTY_BUTTON = Dungeon.Button(0.4769, 0.7955, 0.265, 0.0444)
            val GIVE_UP = Dungeon.Button(0.476, 0.955, 0.218, 0.041)
            val OK = Dungeon.Button(0.602, 0.600, 0.20, 0.04)

            /** The reset's windows (B44), by kind. */
            const val NEWS = "news"
            const val NOTICES = "notices"
            const val LOGIN = "login bonus"
            const val IDLE = "Idle Rewards"
            /** How long a window grows in, taking no tap; about a second, ndo_084507. */
            const val ENTRY = 1.0
            /** staging/b6live/ndo_084458, as Startup.announcement and recognise read it. */
            val NEWS_OK = Dungeon.Button(0.4760, 0.9048, 0.2493, 0.0460)
            /** staging/b6live/i1_25, as Quest.idleWindow reads it. */
            val IDLE_CLAIM = Dungeon.Button(0.6007, 0.7439, 0.2197, 0.0515)
            val IDLE_EXTRA = Dungeon.Button(0.3514, 0.7452, 0.2214, 0.0551)
        }
    }

    /** The whole skill on [Netdef]: every wait real, the clock the game's, only the pictures stood in for. */
    private open class NetdefBot(val game: Netdef, val messages: MutableList<String>, budget: Int)
        : DungeonSkill(
            object : Capture {
                override fun grab(): Mat = Mat()
                override fun tap(x: Int, y: Int) {}
                override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
                override fun back() = game.back()
                override fun inFront(): String? = "com.bandainamcoent.dgup_ww"
            },
            { DungeonSkill.Settings(budgets = mapOf(4 to budget), survey = false) },
            log = { messages += it }, on = { true }, keep = { _, _ -> },
            sleep = { game.t += it }, now = { game.t += 1e-4; game.t }) {
        val frame: Mat = Mat(1920, 1080, CvType.CV_8UC3, Scalar(30.0, 30.0, 30.0))
        val kept = ArrayList<String>()

        init {
            entries = 7
            visibleAtBottom = 5
            only = null
            budgets = mapOf(4 to budget)
        }

        override fun grab(): Mat = frame
        override fun tap(fx: Double, fy: Double, was: String, anchor: Dungeon.Anchor) = game.tap(was)
        override fun recognise(img: Mat): Dungeon.Recognition = game.state()
        override fun listCards(img: Mat): List<Double> = Netdef.CARDS
        override fun listCardsWithSize(img: Mat): List<Pair<Double, Double>> = Netdef.CARDS.map { it to 0.116 }
        override fun cardBudget(img: Mat, fy: Double, fh: Double) = Dungeon.Budget(game.tickets, null, null)
        override fun scrollTop(swipes: Int): Mat? = frame
        override fun scrollBottom(swipes: Int): Mat? = frame
        override fun panelTickets(img: Mat, button: Dungeon.Button, anchor: Dungeon.Anchor): Int? = null
        override fun headerTickets(img: Mat): Int? = game.header()
        override fun saveUnknown(img: Mat, tag: String) { kept += tag }
        override fun announcement(img: Mat): Dungeon.Button? =
            if (game.window() == Netdef.NEWS) Netdef.NEWS_OK else null
        override fun idleWindow(img: Mat): Quest.IdleWindow? =
            if (game.window() == Netdef.IDLE) Quest.IdleWindow(Netdef.IDLE_EXTRA, Netdef.IDLE_CLAIM, true) else null
    }

    /**
     * [NetdefBot] with the reset's windows of 2026-10-01 as they were: the
     * real frames of instance 1 in front of the game while [Netdef.window]
     * says so, each read by the real readers -- recognise, the news's OK,
     * the Idle Rewards window, the title -- and the game's clock for the rest.
     */
    private class RealNetdefBot(game: Netdef, messages: MutableList<String>, budget: Int,
                                val real: Map<String, List<Mat>>) : NetdefBot(game, messages, budget) {
        private var shown: Mat? = null
        override fun grab(): Mat {
            val kind = game.window() ?: return frame.also { shown = null }
            // The same window kind more than once (the news): the n-th one in
            // front is the n-th frame of that kind.
            val n = game.closedBy.count { it.first == kind }
            val frames = real.getValue(kind)
            return frames[minOf(n, frames.size - 1)].also { shown = it }
        }
        private fun isReal(img: Mat) = shown != null && img === shown
        override fun recognise(img: Mat): Dungeon.Recognition =
            if (isReal(img)) Dungeon.recognise(img) else game.state()
        override fun announcement(img: Mat): Dungeon.Button? =
            if (isReal(img)) Startup.announcement(img) else null
        override fun idleWindow(img: Mat): Quest.IdleWindow? =
            if (isReal(img)) Quest.idleWindow(img) else null
        override fun titleScreen(img: Mat): Boolean = isReal(img) && Startup.title(img)
    }

    /**
     * The player's report of 2026-09-28: Network Defense Ops set to 5, the
     * Dungeons task semi-automatic, and fewer runs than tickets. Three
     * tickets and two ads are five (the corpus's lists carry 3/2 on this card).
     *
     * What it was, read in the source against the live log: the wait after a
     * run taps an unknown screen once it has stood [DungeonSkill.UNKNOWN_HOLD]
     * and then once a second after that, and the tap after the one that
     * closed the Reward sheet met the panel coming back -- "leave the party?",
     * OK, the list. The run before was the one the game started itself after
     * Find a Party, which no Attempt of this card counts, so the list read as
     * "nothing done here" and the card was over after one run of five.
     */
    @Test
    fun `Network Defense Ops plays every ticket and both ads`() {
        val game = Netdef(tickets = 3, ads = 2)
        val messages = ArrayList<String>()
        val bot = NetdefBot(game, messages, budget = 5)
        bot.playEntry(2, DungeonSkill.BOTTOM)
        val said = messages.joinToString("\n")
        println(said)
        assertEquals(5, game.runs, "runs played\n$said")
        assertEquals(0 to 0, game.tickets to game.ads, "tickets and ads left\n$said")
        assertEquals(1, game.clicks.count { it == "Find a Party" }, "the party stays; one search\n$said")
        assertEquals(1, game.clicks.count { it == "OK, leave the party" },
                     "the party is left once, on the way out\n$said")
        // Changed on 2026-10-01 (PLAN_RELEASE_1_3.md B5, G3 (1)): the run the
        // party started is booked too, so all five are, where four were.
        assertEquals(5, bot.stats["tickets"], "the four runs Attempt started and the party's own are booked\n$said")
        assertTrue(messages.any { it.contains("a run no Attempt of this card started") }, said)
        assertTrue(messages.any { it.contains("Network Defense Ops: 5 of 5 tickets booked") }, said)
    }

    /**
     * G11 (3), live on LDPlayer 2026-09-29 09:01 to 09:05: Fight! DemiDevimon
     * set to 5 with 2/2 on its card was left at 1/2. Two lost runs ended on
     * the list (09:03:32, 09:04:59; the tap high up after a black screen that
     * had stood 4 s met the panel coming back), and the second list found the
     * card "opened once more already". A re-open is owed once per run: the
     * card is opened again as long as something was done since the last time
     * it was, and a card that closes itself with nothing done still ends.
     */
    @Test
    fun `a card that drops to the list after every lost run is opened again each time`() {
        val sim = Sim(listOf(
            Dungeon.LIST, "dialog:2",
            Dungeon.LIST,                                // lost, and the list
            Dungeon.LIST, "dialog:2",                    // opened once more
            Dungeon.LIST,                                // lost, and the list again
            Dungeon.LIST, "dialog:2",                    // opened a third time
            Dungeon.REWARD, "dialog:1", "dialog:1",      // won: the sheet, 2 -> 1
            "dialog:1"))
        val listReads = ArrayDeque(listOf(2, 2))
        val bot = object : Bot(sim, attemptWorks = true) {
            override fun waitDialogBack(timeout: Double): Dungeon.Recognition? {
                val info = super.waitDialogBack(timeout)
                return if (info?.state == Dungeon.LIST) null else info
            }
            override fun listCounter(index: Int, label: String): Int? = listReads.removeFirstOrNull()
        }
        bot.minBattle = 0.0
        bot.budgets = mapOf(0 to 1)
        bot.attemptsBeforeClear = 10
        for (k in listOf("tickets", "attempts", "fights", "lost", "cleared")) bot.stats[k] = 0
        bot.playEntry(0, DungeonSkill.TOP)
        val said = sim.messages.joinToString("\n")
        assertEquals(3, bot.taps("Test"), "the card opened three times\n$said")
        assertEquals(3, bot.taps("Attempt"), said)
        assertEquals(2, bot.stats["lost"], said)
        assertEquals(1, bot.stats["tickets"], "the ticket the card had left is spent\n$said")

        // And a card the game closes with nothing done in between still ends.
        val shut = Sim(listOf(Dungeon.LIST, "dialog:2", Dungeon.LIST, Dungeon.LIST, Dungeon.LIST, Dungeon.LIST))
        val closing = object : Bot(shut, attemptWorks = true) {
            override fun waitDialogBack(timeout: Double): Dungeon.Recognition? {
                val info = super.waitDialogBack(timeout)
                return if (info?.state == Dungeon.LIST) null else info
            }
            override fun listCounter(index: Int, label: String): Int? = 2
        }
        closing.minBattle = 0.0
        closing.budgets = mapOf(0 to 1)
        closing.playEntry(0, DungeonSkill.TOP)
        assertEquals(2, closing.taps("Test"), shut.messages.joinToString("\n"))
        assertTrue(shut.messages.any { it.contains("back in the list, done") }, shut.messages.joinToString("\n"))
    }

    /**
     * G11 (1), the same pass at 09:14:40 to 09:15:23: this time the party
     * filled the panel ("2 of 3 slots filled") and Attempt started the run,
     * but the counter before it did not read, and the run was "counter
     * unread -> 1, counted as a lost run" -- "3 of 5 tickets booked" for four
     * runs. Why it did not read no frame says (the panel after the run reads
     * 1 with today's reader, a "2/2" pasted from it reads 2); the model here
     * leaves the header blind for 3 s after the party fills. The counter read
     * before Find a Party is the one that stands: nothing can spend a ticket
     * between the two but a run the game starts itself, and that run clears it.
     */
    @Test
    fun `a counter that will not read after the party fills is the one read before the search`() {
        val game = Netdef(tickets = 2, ads = 2, autoStart = false, blindAfterJoin = 3.0)
        val messages = ArrayList<String>()
        val bot = NetdefBot(game, messages, budget = 5)
        bot.playEntry(2, DungeonSkill.BOTTOM)
        val said = messages.joinToString("\n")
        println(said)
        assertEquals(4, game.runs, "runs played\n$said")
        assertEquals(4, bot.stats["tickets"], "every run booked\n$said")
        assertTrue(messages.any { it.contains("Network Defense Ops: 4 of 5 tickets booked") }, said)
        assertTrue(messages.any { it.contains("the one read before the party search, 2, stands") }, said)
    }

    /**
     * G11 (2), the same pass at 09:00:08 and 09:01:33: both of Apocalymon
     * Wall's runs were read after the Results window's Close on the loading
     * screen that follows it ("Now Loading", "Entering... 96.0%", the kept
     * frames), both booked as lost, and the card left with its two tickets
     * spent. After Close the panel is waited for, and nothing is tapped.
     */
    @Test
    fun `after the Results window's Close the counter waits for the panel`() {
        val (bot, kept) = ticketCase(listOf(
            Dungeon.LIST, "dialog:2",
            "close", "close",                           // the run's Results window
            Dungeon.UNKNOWN, Dungeon.UNKNOWN, Dungeon.UNKNOWN, // Now Loading after Close
            "dialog:1",                                 // the panel, 2 -> 1
            "dialog:1"), tickets = 1, clearAfter = 5, withClear = false)
        val said = bot.sim.messages.joinToString("\n")
        assertEquals(1, bot.taps("Close"), said)
        assertEquals(0, bot.taps("neutral"), "nothing tapped on the loading screen\n$said")
        assertEquals(1, bot.stats["tickets"], said)
        assertEquals(0, bot.stats["lost"], said)
        assertTrue("counter_unreadable" !in kept, "kept $kept")
    }

    /**
     * G11 (5), the same pass at 09:05:23: Bakemon's first run outlasted the
     * wait for the panel (90 s then), and the counter was asked of a battle
     * -- "2 -> unread", booked as lost by luck, since it was lost; another
     * Bakemon run that morning took 84 s. A run of 120 s is waited out and
     * booked now, both runs of it.
     */
    @Test
    fun `a run of 120 s is waited out and booked`() {
        val game = Netdef(tickets = 2, ads = 0, autoStart = false, runLength = 120.0)
        val messages = ArrayList<String>()
        val bot = NetdefBot(game, messages, budget = 2)
        bot.playEntry(2, DungeonSkill.BOTTOM)
        val said = messages.joinToString("\n")
        assertEquals(2, game.runs, "runs played\n$said")
        assertEquals(2, bot.stats["tickets"], "both runs booked\n$said")
        assertEquals(0, bot.stats["lost"] ?: 0, said)
        assertEquals(DungeonSkill.BATTLE_TIMEOUT, bot.battleTimeout)
    }

    /**
     * PLAN_DAILY_LOST_SECTOR_PRESETS.md G3 (1) (PLAN_RELEASE_1_3.md B5): the
     * run the game starts by itself once Find a Party has found one was said
     * and not booked, so a card set under what it held played one run too
     * many -- here Network Defense Ops set to 2 with 4 tickets on it, the
     * party's run and two Attempts, a third ticket spent. Booked now on the
     * counter read before the search and after the run, and on the sheet
     * the readers name since B11: two runs a pass, each pass handing its own
     * two over to the TODAY card, which adds them up once.
     */
    @Test
    fun `the run a found party starts is booked, and a card set under its tickets plays no run too many`() {
        val game = Netdef(tickets = 4, ads = 0, sheetNamed = true)
        val messages = ArrayList<String>()
        val bot = NetdefBot(game, messages, budget = 2)
        val card = MapSettings()
        val day = 20_000L
        for (pass in 1..2) {
            val runsBefore = game.runs
            val outcome = bot.work(bot.frame)
            val said = messages.joinToString("\n")
            assertEquals(Result.DONE, outcome.result, "pass $pass\n$said")
            assertEquals(2, game.runs - runsBefore, "pass $pass: runs played, the party's own among them\n$said")
            assertEquals(2, bot.lastCounts["tickets"], "pass $pass: both runs booked\n$said")
            assertEquals(2, bot.lastCounts["fights"], "pass $pass\n$said")
            assertEquals(0, bot.lastCounts["lost"] ?: 0, "pass $pass\n$said")
            assertTrue(messages.any { it.contains("Network Defense Ops: 2 of 2 tickets booked, 1 attempt, 1 party search, 1 run the game started itself") },
                       "pass $pass\n$said")
            SkillStats.add(card, "dungeon", bot.lastCounts, day)
        }
        assertEquals(0, game.tickets, "four tickets, two a pass")
        assertEquals(4, SkillStats.read(card, "dungeon", day)["tickets"])
        assertEquals(4, SkillStats.read(card, "dungeon", day)["fights"])
        assertTrue(messages.none { it.contains("not booked") }, messages.joinToString("\n"))
    }

    /**
     * G3 (2): `partySearches` counted every Find a Party, so a card whose
     * party leaves after every run -- each run wanting a search of its own,
     * and each search finding one -- ended after two runs with "no party
     * found". A search that found a party is a run, not a search.
     */
    @Test
    fun `a party found is no search, and a party that leaves after every run is found again before each`() {
        val game = Netdef(tickets = 4, ads = 0, sheetNamed = true, partyStays = false)
        val messages = ArrayList<String>()
        val bot = NetdefBot(game, messages, budget = 4)
        bot.playEntry(2, DungeonSkill.BOTTOM)
        val said = messages.joinToString("\n")
        println(said)
        assertEquals(4, game.runs, "runs played\n$said")
        assertEquals(4, game.clicks.count { it == "Find a Party" }, "a search before each run\n$said")
        assertEquals(4, bot.stats["tickets"], "every run booked\n$said")
        assertTrue(messages.none { it.contains("no party found") }, said)
    }

    /**
     * G3 (3): the main loop's own tap high up had the clock that G1 (b) took
     * out of the wait after a run -- started on the screen the tap closed --
     * so the next unknown look, the panel coming back, was tapped at once,
     * and on Network Defense Ops a tap outside the panel is "leave the
     * party?". Every tap of the loop wants a screen that has stood the hold
     * since the last one: four unknown looks here, the hold three of the
     * rig's clock ticks long, one tap and not two.
     */
    @Test
    fun `after the main loop's tap high up the next unknown screen is held again`() {
        val sim = Sim(listOf(Dungeon.LIST, Dungeon.UNKNOWN, Dungeon.UNKNOWN, Dungeon.UNKNOWN, Dungeon.UNKNOWN,
                             "dialog:0"))
        val bot = Bot(sim)
        bot.unknownHold = 2.5 * TICK
        bot.playEntry(0, DungeonSkill.TOP)
        assertEquals(1, bot.taps("neutral"), "the screen after the tap was tapped at once: ${sim.clicks}\n${sim.messages}")
        assertTrue(sim.messages.any { it.contains("0 tickets left") }, sim.messages.toString())
    }

    // ------------------------------------------------------------------
    // 10c. The day's reset over the list (PLAN_RELEASE_1_3.md B44)
    // ------------------------------------------------------------------
    /**
     * The reset's windows of 2026-10-01 on instance 1 (08:44:58 to 08:52:02),
     * by kind, from the corpus where they have been moved and from staging/
     * until then; null where the checkout has neither.
     */
    private fun resetFrames(): Map<String, List<Mat>>? {
        val buddy = runFrame("corpus/dungeon/news_buddy_084458.png", "staging/b6live/ndo_084458.png") ?: return null
        return mapOf(
            Netdef.NEWS to listOf(buddy,
                runFrame("corpus/dungeon/news_buddy_084503.png", "staging/b6live/ndo_084503.png")!!,
                runFrame("corpus/dungeon/news_overdrive_084646.png", "staging/b6live/ndo_084646.png")!!),
            Netdef.NOTICES to listOf(runFrame("corpus/dungeon/notices_over_list_085122.png", "staging/b6live/i1_23.png")!!),
            Netdef.LOGIN to listOf(runFrame("corpus/dungeon/login_bonus_over_list_085143.png", "staging/b6live/i1_24.png")!!),
            Netdef.IDLE to listOf(runFrame("corpus/dungeon/idle_over_list_085202.png", "staging/b6live/i1_25.png")!!))
    }

    private fun release(frames: Map<String, List<Mat>>) = frames.values.flatten().forEach { it.release() }

    /**
     * PLAN_RELEASE_1_3.md B44, the case the conductor asked for: the first
     * run after 08:00, and when the panel is left the game puts two of its
     * announcements over the list, one after the other -- the real "New
     * Buddy Added" frames of 2026-10-01, which recognise reads as a battle
     * (their OK is a Give Up to it). The way back tapped high up and the
     * dungeon tab under them; it closes each with its OK now, on the second
     * look that reads it, and taps nothing else while they stand.
     */
    @Test
    fun `two announcements after the run are closed with their OK, and the way back finds the list`() {
        val frames = resetFrames() ?: return
        try {
            for (f in frames.getValue(Netdef.NEWS)) {
                assertEquals(Dungeon.BATTLE, Dungeon.recognise(f).state, "recognise reads the news as a battle")
                assertNotNull(Startup.announcement(f))
            }
            val game = Netdef(tickets = 2, ads = 0, sheetNamed = true, resetWindows = listOf(Netdef.NEWS, Netdef.NEWS))
            val messages = ArrayList<String>()
            val bot = RealNetdefBot(game, messages, budget = 1, real = frames)
            val outcome = bot.work(bot.frame)
            val said = messages.joinToString("\n")
            println(said)
            assertEquals(Result.DONE, outcome.result, said)
            assertEquals(listOf(Netdef.NEWS to "OK of the news", Netdef.NEWS to "OK of the news"), game.closedBy,
                         "${game.resetClicks}\n$said")
            assertTrue(game.resetClicks.all { it == "OK of the news" }, "a tap beside the news: ${game.resetClicks}\n$said")
            assertTrue(game.wrong.isEmpty(), "${game.wrong}")
            assertTrue(messages.none { it.contains("could not find the way back to the list") }, said)
            assertTrue(messages.any { it.contains("the game's news after its reset is over the list") }, said)
            assertEquals(1, bot.lastCounts["tickets"], "the run the party started is booked\n$said")
        } finally {
            release(frames)
        }
    }

    /**
     * The whole morning of 2026-10-01 as it came over the list on instance 1:
     * "New Buddy Added" twice and "New Overdrive Added", Notices, the login
     * bonus under "Camp Wars Support Campaign!", and Idle Rewards -- six
     * windows on their real frames, after the one run of the day's first
     * pass. The way back that morning had five tries for all of it, spent
     * one on the panel and four on taps high up and on the dungeon tab, and
     * said "could not find the way back to the list". Each closes the way it
     * closes now: the news by its OK, Notices and the login bonus by a tap
     * outside them, Idle Rewards beside it, nothing claimed, no box ticked,
     * no campaign opened. The second pass of the day meets none of them and
     * books its own run.
     */
    @Test
    fun `the morning's six windows over the list are closed one by one, and the second pass meets none`() {
        val frames = resetFrames() ?: return
        try {
            assertEquals(Dungeon.DIALOG, Dungeon.recognise(frames.getValue(Netdef.NOTICES)[0]).state)
            assertEquals(Dungeon.DIALOG, Dungeon.recognise(frames.getValue(Netdef.LOGIN)[0]).state)
            assertNotNull(Quest.idleWindow(frames.getValue(Netdef.IDLE)[0]))
            val morning = listOf(Netdef.NEWS, Netdef.NEWS, Netdef.NEWS, Netdef.NOTICES, Netdef.LOGIN, Netdef.IDLE)
            val game = Netdef(tickets = 4, ads = 0, sheetNamed = true, resetWindows = morning)
            val messages = ArrayList<String>()
            val bot = RealNetdefBot(game, messages, budget = 1, real = frames)
            for (pass in 1..2) {
                val outcome = bot.work(bot.frame)
                val said = messages.joinToString("\n")
                assertEquals(Result.DONE, outcome.result, "pass $pass\n$said")
                assertEquals(1, bot.lastCounts["tickets"], "pass $pass: its run booked\n$said")
                assertTrue(messages.none { it.contains("could not find the way back to the list") }, "pass $pass\n$said")
            }
            val said = messages.joinToString("\n")
            println(said)
            assertEquals(listOf(Netdef.NEWS to "OK of the news", Netdef.NEWS to "OK of the news",
                                Netdef.NEWS to "OK of the news",
                                Netdef.NOTICES to "neutral, outside the reset's window",
                                Netdef.LOGIN to "neutral, outside the reset's window",
                                Netdef.IDLE to "beside Idle Rewards"), game.closedBy, "${game.resetClicks}\n$said")
            assertTrue(game.wrong.isEmpty(), "${game.wrong}")
            assertTrue(game.resetClicks.none { it == "dungeon tab" || it == "neutral" }, "${game.resetClicks}")
            assertEquals(2, game.runs, "one run a pass\n$said")
        } finally {
            release(frames)
        }
    }

    // ------------------------------------------------------------------
    // 11. The daily dungeon by the minute (PLAN_DAILY_LOST_SECTOR_PRESETS.md 4.3, DL3)
    // ------------------------------------------------------------------
    /**
     * The daily dungeon as the game plays it, by the clock, from what DL1a
     * measured on LDPlayer instance 0 on 2026-09-29 (the plan's 4.1 point 3
     * and 6; three runs of "Defense Type", level 100, all lost): the card is
     * the last of the list at its bottom; its panel carries Reset beside
     * Attempt; a tap on Attempt dims the panel and it is gone within 2 s;
     * black, the VS screen, the battle with Give Up (8.5 to 9.3 s, VS
     * included), "Now Loading", for about a second the panel's prefab (which
     * `recognise` calls DIALOG_AD and `Dungeon.dailyPanel` nothing), and the
     * panel again over the list at its top -- no window after a lost run. The
     * numbers of the clock: 1.5 s from the tap to the battle's VS, VS 1 s,
     * the battle, loading 1.4 s (the transition UNKNOWN_HOLD rests on), the
     * prefab 1 s.
     *
     * What DL1a did not see is played the way the tickets meet it, and only
     * here: a won run's Reward sheet ([WON]), a Results window with a narrow
     * Close ([RESULTS]), a level-up window that is nobody's screen and goes
     * with a tap ([LEVELUP]), a run that ends on the list ([LIST]), and a
     * panel that is back within two seconds ([QUICK]).
     */
    private class Daily(
        /** What each run ends in, in order; the last one stands for every run after it. */
        val ends: List<String> = listOf(LOST),
        /** The battle, VS to fade-out less the VS: 8.5 to 9.3 s with the VS measured. */
        val battle: Double = 7.8,
        var attemptWorks: Boolean = true,
        /**
         * The panel is at its highest level (MAX, only Reset) from this many
         * battles on: 0 from the start, -1 never (DL5, "Attack Type", seen
         * live on 2026-09-29).
         */
        val maxAfter: Int = -1,
        /** The black between the tap and VS, seconds ([STARTING] as DL1a measured it). */
        val black: Double = STARTING,
        /** The VS screen, seconds. */
        val vs: Double = VS,
        /**
         * A black frame after the battle, before "Now Loading", seconds: 0 is
         * none, as DL1a's three runs had it folded into the loading; live on
         * 2026-09-29 one stood 4 s (09:04:54, G17).
         */
        val blackAfter: Double = 0.0,
    ) {
        var t = 0.0
        var phase = "list"
        private var since = 0.0
        /** The list's scroll: it is handed over at the top, and after a run it comes back there. */
        var atTop = true
        var switchOn = true
        /** The switch goes off as this run's battle begins (1-based), or never. */
        var offInRun = 0
        /** Attempts that started something, and the battles among them. */
        var started = 0
        var battles = 0
        private var end = LOST
        val messages = ArrayList<String>()
        /** Every tap: what it was, the phase it landed in, and when. */
        val taps = ArrayList<Triple<String, String, Double>>()
        val attemptAnchors = ArrayList<Dungeon.Anchor>()
        /** How long the level-up window had stood when a tap closed it. */
        val levelUpStood = ArrayList<Double>()

        private fun go(p: String, at: Double = t) { phase = p; since = at }

        /** The clock's transitions, each at its own moment, however far [t] has run on. */
        private fun advance() {
            while (true) {
                val next: Pair<String, Double> = when (phase) {
                    "opening" -> panelNow() to OPENING
                    "starting" -> "vs" to black
                    "vs" -> "battle" to vs
                    "battle" -> when (end) {
                        WON, LEVELUP -> "sheet"
                        RESULTS -> "results"
                        // The game back on its title in the middle of the run
                        // (another device took the account): it stays there.
                        TITLE -> "title"
                        else -> if (blackAfter > 0) "blackout" else "loading"
                    } to battle
                    "blackout" -> "loading" to blackAfter
                    "loading" -> (if (end == LIST) "list" else "prefab") to LOADING
                    "prefab" -> panelNow() to PREFAB
                    "quick" -> "panel" to QUICK_BACK
                    else -> return
                }
                if (t - since < next.second) return
                if (next.first == "battle") {
                    battles += 1
                    if (battles == offInRun) switchOn = false
                }
                if (next.first == "list") atTop = true
                go(next.first, since + next.second)
            }
        }

        private fun panelNow() = if (maxAfter in 0..battles) "max" else "panel"

        /** What DungeonSkill.dailyAtMax says: the lone Reset on the panel at MAX, and nothing anywhere else. */
        fun max(): Dungeon.Button? {
            advance()
            return if (phase == "max") MAX_RESET else null
        }

        /** The list's cards as the oracle reads them at its top and its bottom, and none off the list. */
        fun cards(): List<Pair<Double, Double>> {
            advance()
            return if (phase == "list" || phase == "opening") (if (atTop) TOP_VIEW else BOTTOM_VIEW)
                   else emptyList()
        }

        fun state(): Dungeon.Recognition {
            advance()
            return when (phase) {
                "list", "opening" -> rec(Dungeon.LIST, karten = cards().map { it.first })
                "panel" -> rec(Dungeon.DIALOG, attempt = ATTEMPT, clear = RESET)
                "wrong" -> rec(Dungeon.DIALOG, attempt = TICKETS_ATTEMPT, clear = RESET)
                "battle" -> rec(Dungeon.BATTLE, giveup = GIVE_UP)
                "sheet" -> rec(Dungeon.REWARD)
                "results" -> rec(Dungeon.DIALOG, attempt = CLOSE)
                "prefab" -> rec(Dungeon.DIALOG_AD, ad = PREFAB_RESET)
                "max" -> rec(Dungeon.DIALOG_AD, ad = MAX_RESET)
                "prompt" -> rec(Dungeon.EXIT, exitOk = OK)
                else -> rec(Dungeon.UNKNOWN)   // the tap's black, VS, loading, a level-up, a message
            }
        }

        /** What Dungeon.dailyPanel says: the Attempt on the panel, and nothing on the prefab or anywhere else. */
        fun daily(): Dungeon.Button? {
            advance()
            return if (phase == "panel") ATTEMPT else null
        }

        /** What Dungeon.runTransition says: the run's black and its VS screen, nothing else. */
        fun passing(): String? {
            advance()
            return when (phase) {
                "starting", "blackout" -> "a black frame"
                "vs" -> "the VS screen"
                else -> null
            }
        }

        /** What Startup.titleBar says: the title, and nothing else. */
        fun title(): Boolean {
            advance()
            return phase == "title"
        }

        /** The phase now, the clock's transitions taken. */
        fun now(): String {
            advance()
            return phase
        }

        fun tap(was: String, anchor: Dungeon.Anchor) {
            advance()
            taps += Triple(was, phase, t)
            when (phase) {
                // The last card at the bottom is the daily dungeon's; at the
                // top it is another dungeon's, with tickets on it.
                "list" -> if (was == CARD) go(if (atTop) "wrong" else "opening")
                "panel" -> when {
                    was == "Attempt" -> {
                        attemptAnchors += anchor
                        if (attemptWorks) startRun()
                    }
                    was.startsWith("neutral") -> go("prompt")   // outside the panel
                }
                "sheet" -> if (was == "close the Reward sheet") go(if (end == LEVELUP) "levelup" else "loading")
                "results" -> if (was == "Close") go("loading")
                "levelup" -> if (was.startsWith("neutral")) {
                    levelUpStood += t - since
                    go("loading")
                }
                "prompt" -> when (was) {
                    "OK, leave the party" -> { atTop = true; go("list") }
                    "Cancel" -> go("panel")
                }
            }
        }

        private fun startRun() {
            started += 1
            end = ends[minOf(started, ends.size) - 1]
            go(if (end == QUICK) "quick" else "starting")
        }

        fun back() {
            advance()
            if (phase == "panel") go("prompt")
            // Measured live (DL5): the back key on the panel at MAX closed it
            // to the list, "back in the list" 4 s after it, no prompt.
            if (phase == "max") { atTop = true; go("list") }
        }

        fun swipe(toBottom: Boolean) {
            advance()
            if (phase == "list") atTop = !toBottom
        }

        fun count(was: String) = taps.count { it.first == was }

        private fun rec(state: String, attempt: Dungeon.Button? = null, ad: Dungeon.Button? = null,
                        clear: Dungeon.Button? = null, karten: List<Double> = emptyList(),
                        giveup: Dungeon.Button? = null, exitOk: Dungeon.Button? = null) =
            Dungeon.Recognition(state, attempt, null, ad, clear, emptyList(), emptyList(), karten,
                                giveup, partyVoll = 0, exitOk = exitOk,
                                exitKind = if (exitOk != null) "party" else null)

        companion object {
            const val LOST = "lost"
            const val WON = "won"
            const val RESULTS = "results"
            const val LEVELUP = "levelup"
            const val LIST = "list"
            const val QUICK = "quick"
            const val TITLE = "title"

            const val OPENING = 0.5
            const val STARTING = 1.5
            const val VS = 1.0
            const val LOADING = 1.4
            const val PREFAB = 1.0
            const val QUICK_BACK = 2.0

            /** The card's label: the seventh name, the one [DungeonSkill.skipLast] leaves out of the halves. */
            val CARD = SkillSettings.DUNGEON_NAMES.last()
            // staging/dungeon/daily_panel_221737, as DL1a reads it
            val ATTEMPT = Dungeon.Button(0.6164, 0.7934, 0.2528, 0.0394)
            val RESET = Dungeon.Button(0.337, 0.7934, 0.2521, 0.0396)
            val PREFAB_RESET = Dungeon.Button(0.575, 0.7548, 0.23, 0.034)
            // corpus/dungeon/daily_panel_max_170523 and corpus/passive/unclear_155914
            val MAX_RESET = Dungeon.Button(0.4769, 0.7934, 0.2528, 0.0394)
            /** Apocalymon Wall's Close, the one narrow button the tickets know (CLOSE_W_MAX). */
            val CLOSE = Dungeon.Button(0.503, 0.792, 0.216, 0.05)
            val TICKETS_ATTEMPT = Dungeon.Button(0.6155, 0.7083, 0.2511, 0.052)
            val GIVE_UP = Dungeon.Button(0.476, 0.955, 0.218, 0.041)
            val OK = Dungeon.Button(0.602, 0.600, 0.20, 0.04)
        }
    }

    /** The whole skill on [Daily]: every wait real, the clock the game's, only the pictures stood in for. */
    private open class DailyBot(val game: Daily, minutes: Int, budgets: Map<Int, Int> = emptyMap())
        : DungeonSkill(
            object : Capture {
                override fun grab(): Mat = Mat()
                override fun tap(x: Int, y: Int) {}
                override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) = game.swipe(toBottom = y1 > y2)
                override fun back() = game.back()
                override fun inFront(): String? = "com.bandainamcoent.dgup_ww"
            },
            { DungeonSkill.Settings(budgets = budgets, survey = false, dailyMinutes = minutes) },
            log = { game.messages += it }, on = { game.switchOn }, keep = { _, _ -> },
            sleep = { game.t += it }, now = { game.t += 1e-4; game.t }) {
        val frame: Mat = Mat(1920, 1080, CvType.CV_8UC3, Scalar(30.0, 30.0, 30.0))
        val kept = ArrayList<String>()

        override fun grab(): Mat = frame
        override fun tap(fx: Double, fy: Double, was: String, anchor: Dungeon.Anchor) = game.tap(was, anchor)
        override fun recognise(img: Mat): Dungeon.Recognition = game.state()
        override fun dailyAttempt(img: Mat): Dungeon.Button? = game.daily()
        override fun dailyAtMax(img: Mat): Dungeon.Button? = game.max()
        override fun listCards(img: Mat): List<Double> = game.cards().map { it.first }
        override fun listCardsWithSize(img: Mat): List<Pair<Double, Double>> = game.cards()
        override fun autoButton(img: Mat): Dungeon.Button? = null
        override fun homeButton(img: Mat): Dungeon.Button? = null
        override fun passing(img: Mat): String? = game.passing()
        override fun titleScreen(img: Mat): Boolean = game.title()
        override fun saveUnknown(img: Mat, tag: String) { kept += tag }
    }

    /**
     * [DailyBot] with real frames for some phases: on those the grab hands
     * out the frame and the real readers read it -- `recognise`, the run's
     * transitions, the title -- and everywhere else the game's stand-ins
     * answer as before.
     */
    private class RealDailyBot(game: Daily, minutes: Int, val real: Map<String, Mat>) : DailyBot(game, minutes) {
        override fun grab(): Mat = real[game.now()] ?: frame
        private fun isReal(img: Mat) = real.values.any { it === img }
        override fun recognise(img: Mat): Dungeon.Recognition =
            if (isReal(img)) Dungeon.recognise(img) else game.state()
        override fun passing(img: Mat): String? = if (isReal(img)) Dungeon.runTransition(img) else game.passing()
        override fun titleScreen(img: Mat): Boolean = if (isReal(img)) Startup.title(img) else game.title()
    }

    /**
     * A frame of the run, read from the corpus where it has been moved and
     * from staging/ until then (TitleFlowTest's two places); null where the
     * checkout has neither (the public copy of the source, release.py).
     */
    private fun runFrame(vararg places: String): Mat? {
        val repo = java.io.File(System.getProperty("digiautotap.repo") ?: "..")
        if (!java.io.File(repo, "corpus").isDirectory) return null
        val file = places.map { java.io.File(repo, it) }.firstOrNull { it.exists() }
        assertNotNull(file, "no frame at any of ${places.toList()}")
        return org.opencv.imgcodecs.Imgcodecs.imread(file.path).also { assertFalse(it.empty(), "$file") }
    }

    /** One pass of the Dungeons task on [game], handed the list as the director hands it. */
    private fun dailyPass(game: Daily, minutes: Int): Pair<DailyBot, Outcome> {
        val bot = DailyBot(game, minutes)
        val outcome = bot.work(bot.frame)
        println(game.messages.joinToString("\n"))
        return bot to outcome
    }

    /**
     * What holds on every run whatever it ends in: Attempt only on the panel
     * the reader named and at its anchor, never on the prefab or another
     * dungeon's panel, never Reset (`recognise`'s `clear` there, question
     * 17), and nothing tapped into a battle or a transition.
     */
    private fun assertTheRules(game: Daily) {
        val said = game.messages.joinToString("\n")
        val attempts = game.taps.filter { it.first == "Attempt" }
        assertTrue(attempts.all { it.second == "panel" }, "an Attempt off the panel: ${game.taps}\n$said")
        assertTrue(game.attemptAnchors.all { it == Dungeon.DAILY }, "${game.attemptAnchors}")
        assertTrue(game.taps.none { it.first == "Reset" || it.first == "Clear Previous Difficulty" },
                   "${game.taps}")
        assertTrue(game.taps.none { it.second in setOf("prefab", "battle", "starting", "vs", "blackout", "loading",
                                                         "wrong", "title") },
                   "a tap into a transition: ${game.taps.filter { it.second != "panel" && it.second != "list" }}\n$said")
    }

    /**
     * The clock (4.3, "die Uhr"): runs of about 30 s and one minute -- two
     * Attempts, and then "the minutes are up". And the minutes are a floor:
     * with runs of about 50 s the second Attempt still comes inside the
     * minute and its run is waited out past it, never cut (question 10).
     */
    @Test
    fun `the daily dungeon is attempted until the minutes are up, and the last run is waited out`() {
        val game = Daily(battle = 26.0)
        val (bot, outcome) = dailyPass(game, minutes = 1)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, said)
        assertEquals(2, game.count("Attempt"), said)
        assertTrue(game.messages.any { it.startsWith("  the minutes are up after 2 runs") }, said)
        assertEquals(2, bot.lastCounts["daily"])
        assertTrue(game.messages.contains("\ndaily dungeon: 1 minute"), said)
        assertTheRules(game)

        val long = Daily(battle = 45.0)
        val (_, longOutcome) = dailyPass(long, minutes = 1)
        val longSaid = long.messages.joinToString("\n")
        assertEquals(Result.DONE, longOutcome.result, longSaid)
        val attempts = long.taps.filter { it.first == "Attempt" }
        assertEquals(2, attempts.size, longSaid)
        val start = attempts[0].third
        assertTrue(attempts[1].third - start < 60.0, "the second Attempt came inside the minute: $attempts")
        assertEquals(2, long.battles, "the run that crossed the minute was played to its end\n$longSaid")
        assertTrue(long.messages.any { it.startsWith("  the minutes are up after 2 runs") }, longSaid)
        assertTheRules(long)
    }

    /** A lost run (all three DL1a saw): no sheet, the panel back, `daily` and `daily_lost`, nothing tapped between. */
    @Test
    fun `a lost daily run is counted as a run and as lost, and nothing between is tapped`() {
        val game = Daily(listOf(Daily.LOST))
        val (bot, outcome) = dailyPass(game, minutes = 1)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, said)
        assertTrue(game.battles >= 2, said)
        assertEquals(game.battles, bot.lastCounts["daily"], said)
        assertEquals(game.battles, bot.lastCounts["daily_lost"])
        assertEquals(0, bot.lastCounts["daily_won"])
        assertEquals(0, game.count("neutral") + game.count("neutral, accept the reward"),
                     "a transition tapped: ${game.taps}")
        assertEquals(0, game.count("close the Reward sheet"))
        assertTrue(game.messages.any { it.contains("run lost, back without a Reward sheet") }, said)
        assertTrue(game.messages.any { it.contains("battle finished after") }, said)
        assertEquals(1, game.count(Daily.CARD), "the card is opened once")
        // The hand-over: the pass ends on the list the director handed it,
        // and names no prompt of its own (Outcome.leaving).
        assertEquals("list", game.phase)
        assertNull(outcome.leaving)
        assertTrue(game.messages.contains("  back in the list"), said)
        // The tickets' own counts are not the daily dungeon's.
        assertEquals(0, bot.lastCounts["fights"])
        assertEquals(0, bot.lastCounts["lost"])
        assertTheRules(game)
    }

    /** A won run: the Reward sheet read and tapped away, `daily_won`. Played here only; DL1a saw none. */
    @Test
    fun `a won daily run is counted when the Reward sheet came, and the sheet is tapped away`() {
        val game = Daily(listOf(Daily.WON))
        val (bot, outcome) = dailyPass(game, minutes = 1)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, said)
        assertTrue(game.battles >= 2, said)
        assertEquals(game.battles, game.count("close the Reward sheet"), said)
        assertEquals(game.battles, bot.lastCounts["daily"])
        assertEquals(game.battles, bot.lastCounts["daily_won"])
        assertEquals(0, bot.lastCounts["daily_lost"])
        assertTrue(game.messages.any { it.contains("run won, the Reward sheet came") }, said)
        assertTheRules(game)
    }

    /** A Results window with a narrow Close after the run: closed, and the run counted once. Sim only. */
    @Test
    fun `a results window after a daily run is closed and the run counted once`() {
        val game = Daily(listOf(Daily.RESULTS))
        val (bot, outcome) = dailyPass(game, minutes = 1)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, said)
        assertTrue(game.battles >= 2, said)
        assertEquals(game.battles, game.count("Close"), said)
        assertEquals(game.battles, bot.lastCounts["daily"])
        assertTrue(game.messages.any { it.contains("a window after the run, closing it") }, said)
        assertTheRules(game)
    }

    /**
     * A level-up after a won run (question 8: close it and go on, count
     * nothing more): a screen no reader names, tapped high up only once it
     * has stood [DungeonSkill.UNKNOWN_HOLD]. Sim only.
     */
    @Test
    fun `a level-up after a won daily run is closed after the hold and not counted`() {
        val game = Daily(listOf(Daily.LEVELUP))
        val (bot, outcome) = dailyPass(game, minutes = 1)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, said)
        assertTrue(game.battles >= 2, said)
        assertEquals(game.battles, game.levelUpStood.size, "each level-up closed once\n$said")
        assertTrue(game.levelUpStood.all { it >= DungeonSkill.UNKNOWN_HOLD }, "${game.levelUpStood}")
        assertEquals(game.battles, bot.lastCounts["daily"])
        assertEquals(game.battles, bot.lastCounts["daily_won"])
        assertEquals(0, bot.lastCounts["daily_lost"])
        assertTheRules(game)
    }

    /**
     * The main switch between two runs and nowhere else: off in the middle
     * of the first battle, the run is waited out and counted, and the next
     * Attempt is not tapped -- STOPPED, back on the list.
     */
    @Test
    fun `the switch ends the daily dungeon between two runs, not inside one`() {
        val game = Daily(listOf(Daily.LOST))
        game.offInRun = 1
        val (bot, outcome) = dailyPass(game, minutes = 5)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.STOPPED, outcome.result, said)
        assertFalse(game.switchOn)
        assertEquals(1, game.count("Attempt"), said)
        assertEquals(1, game.battles)
        assertEquals(1, bot.lastCounts["daily"], "the run begun was waited out and counted\n$said")
        assertTrue(game.messages.contains("stopped"), said)
        // Changed on 2026-09-30 (PLAN_RELEASE_1_3.md B4): the pass the switch
        // stopped leaves the game where it stands -- on the daily panel,
        // where a pass that goes on takes it up -- and no longer walks back
        // to the list with every tap held back.
        assertEquals("panel", game.phase)
        assertTrue(bot.carried, "the pass is one to go on with")
        assertTheRules(game)
    }

    /**
     * Two pauses in the daily dungeon's minutes (PLAN_RELEASE_1_3.md B4): the
     * switch goes off as the first run's battle begins, and again as a later
     * one's does; each run is waited out, and the pass stops on the panel it
     * comes back to. Ten minutes of pause each time, and back on the pass goes
     * on on the panel with the minutes it has left: as many Attempts in all as
     * a pass that was never paused.
     */
    @Test
    fun `the daily dungeon paused twice plays the minutes it has left, not the pauses`() {
        val straight = Daily(battle = 26.0)
        val (_, once) = dailyPass(straight, minutes = 2)
        assertEquals(Result.DONE, once.result, straight.messages.joinToString("\n"))
        val attempts = straight.count("Attempt")

        val game = Daily(battle = 26.0)
        game.offInRun = 1
        val bot = DailyBot(game, 2)
        assertEquals(Result.STOPPED, bot.work(bot.frame).result, game.messages.joinToString("\n"))
        assertEquals("panel", game.phase)
        game.t += 600.0
        assertTrue(bot.resumesOn(Director.DIALOG, bot.frame), "the daily panel is the pass's own")
        game.switchOn = true
        game.offInRun = game.battles + 1
        assertEquals(Result.STOPPED, bot.resume(bot.frame, whole = false).result, game.messages.joinToString("\n"))
        game.t += 600.0
        game.switchOn = true
        game.offInRun = 0
        val third = bot.resume(bot.frame, whole = false)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, third.result, said)
        assertEquals(attempts, game.count("Attempt"), "as many Attempts as a pass never paused\n$said")
        assertEquals(1, game.count(Daily.CARD), "the card opened once; the pass went on on its panel\n$said")
        assertEquals("list", game.phase)
        assertTheRules(game)
    }

    /** An Attempt that leaves the panel standing, twice: the phase ends, with the frame kept (4.3, "ohne Wirkung"). */
    @Test
    fun `an Attempt without effect twice ends the daily dungeon`() {
        val game = Daily(attemptWorks = false)
        val (bot, outcome) = dailyPass(game, minutes = 5)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, said)
        assertEquals(2, game.count("Attempt"), said)
        assertEquals(2, game.messages.count { it == "  attempt had no effect" }, said)
        assertTrue(game.messages.any { it.contains("had no effect twice, leaving it") }, said)
        assertTrue("daily_no_effect" in bot.kept, "${bot.kept}")
        assertEquals(0, bot.lastCounts["daily"])
        assertTheRules(game)
    }

    /** The panel back within two seconds is no run, and twice so in a row ends the phase ([DungeonSkill.minBattle]). */
    @Test
    fun `a daily run that does not start twice in a row ends the daily dungeon`() {
        val game = Daily(listOf(Daily.QUICK))
        val (bot, outcome) = dailyPass(game, minutes = 5)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, said)
        assertEquals(2, game.count("Attempt"), said)
        assertEquals(0, game.battles)
        assertTrue(game.messages.any { it.contains("no battle twice in a row") }, said)
        assertTrue("daily_no_battle" in bot.kept, "${bot.kept}")
        assertEquals(0, bot.lastCounts["daily"])
        assertTheRules(game)
    }

    /**
     * 0 minutes, the default: the pass is the tickets' pass it always was --
     * no tap on the daily card, no line about it -- and the seam's budget is
     * a ticket or a minute.
     */
    @Test
    fun `with 0 minutes the daily dungeon is left alone`() {
        val game = Daily()
        val (bot, outcome) = dailyPass(game, minutes = 0)
        assertEquals(Result.DONE, outcome.result)
        assertEquals(0, game.count(Daily.CARD), "${game.taps}")
        assertTrue(game.taps.isEmpty(), "${game.taps}")
        assertTrue(game.messages.none { it.contains("daily dungeon") }, "${game.messages}")
        assertFalse(bot.hasBudget(), "no ticket and no minute")
        assertTrue(DailyBot(Daily(), 1).hasBudget(), "a minute is a budget")
        assertTrue(DailyBot(Daily(), 0, budgets = mapOf(2 to 1)).hasBudget(), "a ticket still is one")
        assertFalse(Chain.dungeonHasWork(mapOf(0 to 0), 0))
        assertTrue(Chain.dungeonHasWork(mapOf(0 to 0), 3))
    }

    /**
     * A run that ends on the list, which the game hands back at its top: the
     * card is opened once more -- at the list's proved bottom, never at the
     * top, where the last card is another dungeon's.
     */
    @Test
    fun `a daily run that ends on the list opens the card once more at the bottom`() {
        val game = Daily(listOf(Daily.LIST, Daily.LOST))
        val (bot, outcome) = dailyPass(game, minutes = 1)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, said)
        assertEquals(2, game.count(Daily.CARD), said)
        assertEquals(1, game.messages.count { it.contains("opening the daily dungeon once more") }, said)
        assertTrue(game.taps.filter { it.first == Daily.CARD }.all { it.second == "list" }, "${game.taps}")
        assertTrue(game.battles >= 2, said)
        assertEquals(game.battles, bot.lastCounts["daily"])
        assertTheRules(game)
    }

    /**
     * The panel at its highest level (DL5, LDPlayer instance 0, 2026-09-29:
     * "Attack Type" at MAX, Reset alone in the middle of the pair's row, no
     * Attempt): the phase ends as soon as the lone Reset has stood on two
     * frames -- no 30 s wait for an Attempt, no frame kept, nothing tapped on
     * the panel -- and the back key takes it to the list, as it did live.
     */
    @Test
    fun `a daily panel at its highest level ends the phase at once, with nothing tapped on it`() {
        val game = Daily(maxAfter = 0)
        val (bot, outcome) = dailyPass(game, minutes = 3)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, said)
        assertEquals(listOf(Daily.CARD), game.taps.map { it.first }, "only the card: ${game.taps}")
        assertEquals(1, game.messages.count { it.contains("at its highest level (MAX") }, said)
        assertTrue(game.messages.none { it.contains("did not stand within") }, said)
        assertTrue(bot.kept.isEmpty(), "${bot.kept}")
        assertEquals(0, game.started)
        assertEquals(0, bot.lastCounts["daily"] ?: 0)
        assertTrue(game.messages.any { it.contains("back in the list") }, said)
        assertTrue(game.t < 15.0, "the phase took ${game.t} s")
        assertTheRules(game)
    }

    /** A won run that lifts the level to MAX: the run is counted as won, and then the phase ends at the lone Reset. */
    @Test
    fun `a daily run won up to the highest level is counted, and the phase ends there`() {
        val game = Daily(listOf(Daily.WON), maxAfter = 1)
        val (bot, outcome) = dailyPass(game, minutes = 3)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, said)
        assertEquals(1, game.started, said)
        assertEquals(1, bot.lastCounts["daily"], said)
        assertEquals(1, bot.lastCounts["daily_won"], said)
        assertEquals(1, game.messages.count { it.contains("at its highest level (MAX") }, said)
        assertTrue(bot.kept.isEmpty(), "${bot.kept}")
        assertTheRules(game)
    }

    /**
     * The tickets' halves as they were, and the daily dungeon after them: a
     * card of the bottom half is played first, then the daily card.
     */
    @Test
    fun `the tickets are played first and the daily dungeon after the bottom half`() {
        val game = Daily()
        val order = ArrayList<String>()
        val bot = object : DailyBot(game, 1, budgets = mapOf(5 to 1)) {
            override fun playEntry(index: Int, label: String, key: Pair<String, Int>?) {
                order += "${labelOf(index, label)} ($label $index)"
            }
            override fun playDaily() {
                order += "daily"
                super.playDaily()
            }
        }
        val outcome = bot.work(bot.frame)
        assertEquals(Result.DONE, outcome.result, game.messages.joinToString("\n"))
        assertEquals(listOf("Metal Sea (bottom 3)", "daily"), order)
        assertTrue(game.count("Attempt") >= 1)
        assertTheRules(game)
    }

    /**
     * Two passes in a row each hand over their own runs, and the TODAY card
     * adds them up once (3.5; notes/director.md, "A counter nothing clears
     * is counted again").
     */
    @Test
    fun `two passes of the daily dungeon each hand over their own runs`() {
        val game = Daily(battle = 26.0)
        val bot = DailyBot(game, 1)
        val card = MapSettings()
        val day = 20_000L
        for (pass in 1..2) {
            val outcome = bot.work(bot.frame)
            assertEquals(Result.DONE, outcome.result, "pass $pass\n${game.messages.joinToString("\n")}")
            assertEquals(2, bot.lastCounts["daily"], "pass $pass")
            SkillStats.add(card, "dungeon", bot.lastCounts, day)
        }
        assertEquals(4, SkillStats.read(card, "dungeon", day)["daily"])
        assertEquals("4 daily dungeon runs.",
                     SkillStats.sentence("dungeon", SkillStats.read(card, "dungeon", day)))
        assertEquals("1 daily dungeon run, 1 daily dungeon run won.",
                     SkillStats.sentence("dungeon", mapOf("daily" to 1, "daily_won" to 1)))
    }

    /**
     * The readers the phase stands on, on a painted panel (PaintDungeon, so
     * that nothing here waits for DL1b's frames): `Dungeon.dailyPanel` names
     * the Attempt, `recognise` calls the panel a dialog with Reset as its
     * `clear` -- which is why the phase never asks `recognise` for a button
     * there -- the director calls it a dialog and not the Partner window
     * (Passive.PARTNER_H_MIN, G5 b), and the prefab has no daily panel.
     */
    @Test
    fun `the daily panel's readers on a painted panel`() {
        val panel = PaintDungeon.dailyPanel()
        val prefab = PaintDungeon.dailyPanel(prefab = true)
        try {
            val attempt = Dungeon.dailyPanel(panel)
            assertNotNull(attempt, "no daily panel on the painted one")
            assertTrue(abs(attempt.fx - 0.616) <= 0.01 && abs(attempt.fy - 0.7934) <= 0.01, "$attempt")
            val info = Dungeon.recognise(panel)
            assertEquals(Dungeon.DIALOG, info.state)
            assertNotNull(info.clear, "recognise's clear is the Reset")
            assertTrue(abs(info.clear!!.fx - 0.337) <= 0.01, "${info.clear}")
            assertNull(Passive.partnerMenu(panel), "the Partner window's reader took the flat pair")
            assertEquals(Director.DIALOG, Director.classify(panel).screen)
            assertNull(Dungeon.dailyPanel(prefab), "the prefab is not the panel")
        } finally {
            Paint.release(panel, prefab)
        }
    }

    /**
     * PLAN_RELEASE_1_3.md B6 (PLAN_DAILY_LOST_SECTOR_PRESETS.md G8, G17): the
     * wait after Attempt tapped high up on any screen no reader named once it
     * had stood UNKNOWN_HOLD, and a run's own black and VS were such screens
     * -- live on LDPlayer 2026-09-29 09:04:54 the tap went out on a black
     * frame that had stood 4 s and closed the panel coming back. Black after
     * the tap, VS and black after the battle, 4.5 s each here: waited on as a
     * battle is, never tapped, and said once a wait.
     */
    @Test
    fun `a run's black and VS are waited on and never tapped, however long they stand`() {
        val game = Daily(listOf(Daily.LOST), black = 4.5, vs = 4.5, blackAfter = 4.5)
        val (bot, outcome) = dailyPass(game, minutes = 1)
        val said = game.messages.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, said)
        assertTrue(game.battles >= 2, said)
        assertEquals(game.battles, bot.lastCounts["daily"], said)
        assertEquals(0, game.count("neutral") + game.count("neutral, accept the reward"), "${game.taps}")
        assertTrue(game.messages.any { it == "  the VS screen -- a run's own transition, waited on and not tapped" }, said)
        assertTrue(game.messages.any { it == "  a black frame -- a run's own transition, waited on and not tapped" }, said)
        assertTrue(game.messages.none { it.contains("a screen nothing here reads has stood") }, said)
        assertTheRules(game)
    }

    /**
     * B6 and B11 on the run's real frames: the VS screen of the daily
     * dungeon (corpus/dungeon/daily_vs_053118), the black after a lost run
     * of the live pass of 2026-09-29 (09:04:54), and the won run's Reward
     * sheet of 2026-09-30 22:55:04 -- "VS. SP-Type Digimon! 100" on its sand
     * and sky, which `recognise` called unknown until the sheet's blue was
     * measured over a bright stage (Dungeon.SHEET_BLUE). Then the wait
     * tapped it high up after the hold and the run was booked "run lost,
     * back without a Reward sheet" ("daily dungeon: 3 runs, 0 won" while the
     * level went from 100 to MAX). Read by the real readers here, two passes:
     * every run won, the sheet closed by its own tap, VS and black untouched,
     * each pass handing its own runs to the TODAY card.
     */
    @Test
    fun `the daily dungeon on its real frames waits on VS and black, and its won sheet is a won run`() {
        val vsFrame = runFrame("corpus/dungeon/daily_vs_053118.png") ?: return
        val black = runFrame("corpus/dungeon/black_after_lost_run_090454.png", "staging/b6/capture_090454.png")!!
        val sheet = runFrame("corpus/dungeon/daily_won_sheet_225504.png", "staging/daily_won_sheet_paused_225504.png")!!
        try {
            assertEquals("the VS screen", Dungeon.runTransition(vsFrame))
            assertEquals("a black frame", Dungeon.runTransition(black))
            assertEquals(Dungeon.REWARD, Dungeon.recognise(sheet).state)
            val game = Daily(listOf(Daily.WON), black = 4.5, vs = 4.5)
            val bot = RealDailyBot(game, 1, mapOf("starting" to black, "vs" to vsFrame, "sheet" to sheet))
            val card = MapSettings()
            val day = 20_000L
            for (pass in 1..2) {
                val battles = game.battles
                val outcome = bot.work(bot.frame)
                val said = game.messages.joinToString("\n")
                assertEquals(Result.DONE, outcome.result, "pass $pass\n$said")
                val runs = game.battles - battles
                assertTrue(runs >= 2, "pass $pass\n$said")
                assertEquals(runs, bot.lastCounts["daily"], "pass $pass\n$said")
                assertEquals(runs, bot.lastCounts["daily_won"], "pass $pass: every run won\n$said")
                assertEquals(0, bot.lastCounts["daily_lost"], "pass $pass\n$said")
                SkillStats.add(card, "dungeon", bot.lastCounts, day)
            }
            val said = game.messages.joinToString("\n")
            assertEquals(game.battles, game.count("close the Reward sheet"), said)
            assertEquals(0, game.count("neutral") + game.count("neutral, accept the reward"), "${game.taps}")
            assertTrue(game.messages.none { it.contains("run lost, back without a Reward sheet") }, said)
            assertEquals(game.battles, SkillStats.read(card, "dungeon", day)["daily_won"])
            assertTheRules(game)
        } finally {
            Paint.release(vsFrame, black, sheet)
        }
    }

    /**
     * PLAN_RELEASE_1_3.md B30: the game back on its title in the middle of a
     * run (another device took the account, the game restarted) -- the real
     * dusk title of 2026-09-30, which `recognise` reads as unknown. The wait
     * tapped it high up after the hold, and a tap there is "Touch To Start".
     * The pass ends where it stands: nothing tapped, no way back to the list,
     * the run counted as nothing, parked with the frame kept.
     */
    @Test
    fun `the game's title in the middle of a run ends the pass, and nothing is tapped on it`() {
        val title = runFrame("corpus/startup/title_dusk_185220.png", "staging/r2/live_title_dusk_185220.png") ?: return
        try {
            assertNotNull(Startup.titleBar(title), "the title reads as the title")
            assertEquals(Dungeon.UNKNOWN, Dungeon.recognise(title).state)
            val game = Daily(listOf(Daily.TITLE))
            val bot = RealDailyBot(game, 3, mapOf("title" to title))
            val outcome = bot.work(bot.frame)
            val said = game.messages.joinToString("\n")
            assertEquals(Result.PARKED, outcome.result, said)
            assertEquals(DungeonSkill.TITLE_WHY, outcome.why)
            assertTrue(bot.onTitle)
            assertEquals("title", game.phase)
            assertEquals(1, game.count("Attempt"), said)
            assertTrue(game.taps.none { it.second == "title" }, "${game.taps}")
            assertTrue(game.messages.contains("  the game is on its title screen -- the pass ends here, nothing tapped"), said)
            assertTrue("title" in bot.kept, "${bot.kept}")
            assertEquals(0, bot.lastCounts["daily_lost"] ?: 0, "the run's end was not seen\n$said")
            assertFalse(bot.goHome(), "no way home from the title")
            assertTrue(game.taps.none { it.second == "title" }, "${game.taps}")
            assertTheRules(game)
        } finally {
            title.release()
        }
    }

    /**
     * PLAN_RELEASE_1_3.md B43: the same on the title's day picture (instance
     * 0, 2026-10-01 08:54, 900 x 1600), whose bar the bar's reader does not
     * find and which is read by its Menu button: until then the pass tapped
     * it high up after the hold, where a tap is "Touch To Start".
     */
    @Test
    fun `the game's title by day in the middle of a run ends the pass as well`() {
        val title = runFrame("corpus/startup/title_day_085430.png", "staging/b6live/title_day_085430.png") ?: return
        try {
            assertNull(Startup.titleBar(title), "the bar is not found by day")
            assertNotNull(Startup.menuButton(title), "the Menu is")
            assertEquals(Dungeon.UNKNOWN, Dungeon.recognise(title).state)
            val game = Daily(listOf(Daily.TITLE))
            val bot = RealDailyBot(game, 3, mapOf("title" to title))
            val outcome = bot.work(bot.frame)
            val said = game.messages.joinToString("\n")
            assertEquals(Result.PARKED, outcome.result, said)
            assertEquals(DungeonSkill.TITLE_WHY, outcome.why)
            assertTrue(bot.onTitle)
            assertTrue(game.taps.none { it.second == "title" }, "${game.taps}\n$said")
            assertTrue(game.messages.contains("  the game is on its title screen -- the pass ends here, nothing tapped"), said)
            assertFalse(bot.goHome(), "no way home from the title")
            assertTheRules(game)
        } finally {
            title.release()
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
            override fun tap(fx: Double, fy: Double, was: String, anchor: Dungeon.Anchor) {}
            override fun scrollTop(swipes: Int): Mat? = frame
            override fun scrollBottom(swipes: Int): Mat? = frame
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

        /** The list at the top, as the oracle has it on 25 frames: the banner, then three cards. */
        val TOP_VIEW = listOf(0.281 to 0.208, 0.471 to 0.116, 0.621 to 0.116, 0.771 to 0.116)
        /** The list at the bottom, on its three frames: five cards, no banner. */
        val BOTTOM_VIEW = listOf(0.229 to 0.115, 0.378 to 0.116, 0.528 to 0.116, 0.678 to 0.116, 0.828 to 0.115)
        /** A list in the middle of a scroll (corpus/passive/unclear_214359): four cards, no banner. */
        val MID_VIEW = listOf(0.36 to 0.116, 0.51 to 0.116, 0.66 to 0.116, 0.81 to 0.116)
    }
}
