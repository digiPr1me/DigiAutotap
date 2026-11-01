package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Camp on painted frames (PaintCamp): the world ([CampWorld]) answers
 * every tap the way the game did in C1's and C2's measurement (PLAN_CAMP.md
 * 6), and the real CampSkill walks it with its real readers and the real
 * stamps in a settings map ([Stored.camp]).
 *
 * The cases of PLAN_CAMP.md 3.3 with the player's answers of section 4
 * (questions 1, 2, 3 and 9; the proposals of 4 to 8 and 10), two passes for
 * the counters, and everything 3.6 says never happens with a case that would
 * see it -- [assertNothingForbidden] after every one, and the sentences of
 * 3.6 named in the cases' names.
 *
 * One thing the painting cannot do: at 805 px wide the purple gate's picture
 * does not part from the pink one's (0.88 against 0.88, where the 1920 cards
 * stand 0.12 apart, Camp.kt's CampIcons), so the painted shop shows the
 * purple gate as a card no template names. It is the case of "never an item
 * whose template does not match" in every pass with the default boxes.
 */
class CampSkillTest {

    private class Rig(val w: CampWorld = CampWorld()) {
        val store = MapSettings()
        var nowEpoch = NOON
        val kept = ArrayList<String>()
        val log = ArrayList<String>()
        val skill = CampSkill(w, icons, { Stored.camp(store, nowEpoch) },
                              stamp = { Stored.stampCamp(store, it, nowEpoch) },
                              attempted = { Stored.campAttempted(store, it, nowEpoch) },
                              log = { log += it; println(it) }, on = { w.on },
                              keep = { _, tag -> kept += tag }, sleep = { w.t += it }, now = { w.t })

        fun said(text: String) = log.any { it == text }
        fun saidPart(text: String) = log.any { text in it }
        fun done() = Stored.camp(store, nowEpoch).done
        fun tick(item: String, on: Boolean) = store.put(Stored.campBuyKey(item), on)
    }

    /** 3.6: nothing the world calls forbidden, no back key. */
    private fun assertNothingForbidden(w: CampWorld) {
        assertEquals(emptyList(), w.taps.filter { it in CampWorld.FORBIDDEN }, "forbidden taps in ${w.taps}")
        assertEquals(0, w.backs, "the back key")
    }

    private val gates = listOf("gate_blue", "gate_pink", "gate_yellow", "gate_purple", "gate_green")

    // ------------------------------------------------------------------------
    @Test
    fun `the painted frames read as the real ones do`() {
        val main = PaintCamp.main()
        assertEquals(Director.MAIN, Director.classify(main).screen)
        val tab = assertNotNull(Camp.campTab(main))
        assertTrue(abs(tab.fx - (PaintCamp.GLOBE_FX + Camp.NAV_CAMP_DX)) < 0.01, "the tab at ${tab.fx}")
        assertNull(Camp.campTab(PaintCamp.main(camp = false)), "the nav bar before 1.5.0")

        val camp = PaintCamp.campScreen()
        assertEquals(Director.CAMP, Director.classify(camp).screen)
        assertNotNull(Camp.screen(camp))
        assertNull(Camp.screen(PaintCamp.campScreen(roof = false)), "the map coming in")

        for ((badge, check) in listOf(true to true, false to false, true to false)) {
            val t = PaintCamp.tent(badge, check)
            assertEquals(Director.CAMP_TENT, Director.classify(t).screen)
            val tw = assertNotNull(Camp.tentWindow(t))
            assertEquals(badge, tw.support.lit, "Support's \"!\"")
            assertEquals(check, tw.checkIn.lit, "Check-In")
        }
        for (lit in listOf(true, false)) {
            val s = PaintCamp.support(lit)
            assertEquals(Director.CAMP_TENT, Director.classify(s).screen)
            assertEquals(lit, assertNotNull(Camp.supportWindow(s)).supportAll.lit)
            assertNull(Camp.tentWindow(s))
        }

        val w = CampWorld()
        for (top in listOf(true, false)) {
            w.atTop = top
            val img = PaintCamp.shop(w.cards(), top)
            assertEquals(Director.CAMP_SHOP, Director.classify(img).screen)
            val shop = assertNotNull(Camp.shopWindow(img, icons))
            assertEquals(top, shop.atTop)
            assertEquals(!top, shop.atBottom)
            val want = (if (top) w.stock.subList(0, 9) else w.stock.subList(6, 15))
                .map { if (it.name == "gate_purple") null else it.name }
            assertEquals(want, shop.cards.map { it.item }, "the cards' names")
            assertEquals((if (top) w.stock.subList(0, 9) else w.stock.subList(6, 15)).map { it.left?.let { n -> n to it.of } },
                         shop.cards.map { it.left }, "the cards' counts")
        }
        // Sold out and too dear: a grey card nobody names, a pink price.
        w.stock[0].left = 0
        w.coins = 300
        w.atTop = true
        val poor = assertNotNull(Camp.shopWindow(PaintCamp.shop(w.cards(), true), icons))
        assertTrue(poor.cards[0].soldOut)
        assertNull(poor.cards[0].item)
        assertEquals(0 to 1, poor.cards[0].left)
        assertEquals(false, poor.cards[1].affordable, "400 Camp Coins against 300")
        assertEquals(null, poor.cards[7].affordable, "the ticket's 250 reach: no price painted, the dialog says so")

        for (aff in listOf(true, false)) {
            val d = PaintCamp.dialog(aff)
            assertEquals(Director.CAMP_SHOP, Director.classify(d).screen)
            val bd = assertNotNull(Camp.buyDialog(d))
            assertEquals(aff, bd.affordable)
            assertEquals(1, bd.count)
        }
        // One piece left: the dialog without a row (C4, the game's 1.5.1).
        for (aff in listOf(true, false)) {
            val d = PaintCamp.singleDialog(aff)
            assertEquals(Director.CAMP_SHOP, Director.classify(d).screen)
            val bd = assertNotNull(Camp.buyDialog(d))
            assertEquals(aff, bd.affordable)
            assertNull(bd.count)
            assertNull(bd.plus)
            assertEquals(PaintCamp.SINGLE_BUY_FY, bd.buy.fy, 0.01)
        }
        for (which in CampStation.Which.values()) for (n in 0..2) {
            val st = PaintCamp.station(which, n, autoLit = n > 0, attemptLit = n > 0)
            assertEquals(Director.CAMP_STATION, Director.classify(st).screen, "$which $n")
            val sw = assertNotNull(CampStation.window(st))
            assertEquals(which, sw.which)
            assertEquals(n to 2, sw.attempts)
            assertEquals(n > 0, sw.autoClear.lit)
            assertEquals(n > 0, sw.attempt.lit)
        }
        val dim = assertNotNull(CampStation.window(PaintCamp.station(CampStation.Which.DOJO, 2, false, true)))
        assertFalse(dim.autoClear.lit, "Auto Clear dim without a record")
        assertTrue(dim.attempt.lit)
        for (a in listOf(0 to 0, 2 to 1)) {
            val b = PaintCamp.boxes(a, a.first + a.second > 0)
            assertEquals(Director.CAMP_BOXES, Director.classify(b).screen)
            val bw = assertNotNull(CampStation.boxesWindow(b))
            assertEquals(a, bw.available)
            assertEquals(a.first + a.second > 0, bw.claim.lit)
        }
        val r = PaintCamp.results()
        assertEquals(Director.DIALOG, Director.classify(r).screen, "Results stays the director's dialog (C2)")
        assertTrue(assertNotNull(CampStation.results(r)).close.lit)
        assertTrue(Dungeon.rewardSheet(PaintMissions.rewardSheet()))
        assertEquals(Director.DIALOG, Director.classify(PaintDungeon.dailyPanel()).screen, "the stand-in for Get Reward")
    }

    // ------------------------------------------------------------------------
    // The pass from the main screen (3.3)
    // ------------------------------------------------------------------------
    @Test
    fun `a pass goes through the tent, the shop and both stations and comes home`() {
        val r = Rig()
        r.w.available[CampStation.Which.DOJO] = 2 to 1
        val out = r.skill.run()
        assertEquals(Result.DONE, out.result, out.why)
        assertEquals(CampWorld.Screen.MAIN, r.w.screen)
        // The tent: Support, Support All, Check-In.
        assertEquals(1, r.w.supported)
        assertEquals(1, r.w.checkedIn)
        // The shop: the ticked gates the shop names, each once; purple is
        // the card no template names, and is never bought.
        assertEquals(listOf("gate_blue", "gate_pink", "gate_yellow", "gate_green"), r.w.bought)
        assertTrue(r.saidPart("Digital Gate: Purple x1: no card reads as it"), "${r.log}")
        assertEquals(listOf("to top", "to bottom"), r.w.swipes, "the list was left at its bottom")
        // The stations: Auto Clear twice at each, never Attempt; the Dojo's boxes.
        assertEquals(4, r.w.autoClears)
        assertTrue(r.w.attemptsPlayed.isEmpty())
        assertEquals(0, r.w.attempts.values.sum())
        assertEquals(3, r.w.claimed)
        assertEquals(mapOf("supports" to 1, "checkins" to 1, "bought" to 4, "cleared" to 4, "boxes" to 3),
                     r.skill.lastCounts)
        assertEquals(CampSkill.Part.values().toSet(), r.done(), "all four parts stamped")
        assertFalse(r.skill.hasBudget())
        assertEquals(CampSkill.NO_BUDGET, r.skill.noBudgetWhy())
        for (line in listOf("camp: opening the Camp", "camp: the Camp Tent", "camp: Support", "camp: Support All",
                            "camp: supported (1)", "camp: Check-In", "camp: checked in (1)",
                            "camp: the Camp Shop", "camp: bought Digital Gate: Blue x1 (1)",
                            "camp: Leomon's Dojo -- Auto Clear (2/2)", "camp: Leomon's Dojo cleared by Auto Clear (1) -- 1/2 left",
                            "camp: Leomon's Dojo: 3 reward boxes claimed",
                            "camp: Piximon's Drill Site: nothing to claim in the Reward Boxes",
                            "camp: both stations are done for today", "camp: done for today",
                            "camp: back on the main screen")) {
            assertTrue(r.said(line), "no \"$line\" in ${r.log}")
        }
        assertTrue(r.kept.isEmpty(), "${r.kept}")
        assertNothingForbidden(r.w)
    }

    /** 3.6: what the game already shows as done is read there and not tapped -- no grey button, no sold-out card. */
    @Test
    fun `what is done already is read and nothing grey is tapped`() {
        val r = Rig()
        r.w.supportOpen = false
        r.w.checkInOpen = false
        for (g in gates) r.w.stock.first { it.name == g }.left = 0
        r.w.attempts[CampStation.Which.DOJO] = 0
        r.w.attempts[CampStation.Which.DRILL] = 0
        val out = r.skill.run()
        assertEquals(Result.DONE, out.result, out.why)
        for (line in listOf("camp: supported already today", "camp: checked in already today",
                            "camp: Leomon's Dojo: 0/2 -- no attempts left today",
                            "camp: Piximon's Drill Site: 0/2 -- no attempts left today")) {
            assertTrue(r.said(line), "no \"$line\" in ${r.log}")
        }
        assertEquals(listOf("camp tab", "tent", "close tent", "shop", "close shop", "dojo", "reward boxes",
                            "close boxes", "close station", "drill", "reward boxes", "close boxes", "close station",
                            "globe"), r.w.taps)
        assertTrue(r.w.bought.isEmpty())
        assertEquals(CampSkill.Part.values().toSet(), r.done())
        assertEquals(mapOf("supports" to 0, "checkins" to 0, "bought" to 0, "cleared" to 0, "boxes" to 0), r.skill.lastCounts)
        assertNothingForbidden(r.w)
    }

    /** Question 4: an item without a limit is one dialog a day, and its sheet is the proof. */
    @Test
    fun `the Acceleration Ticket is bought once a day, proved by its sheet`() {
        val r = Rig()
        for (g in gates) r.tick(g, false)
        r.tick("acceleration_ticket", true)
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("acceleration_ticket"), r.w.bought)
        assertEquals(1, r.w.taps.count { it == "buy acceleration_ticket" })
        assertEquals(1, r.skill.lastCounts["bought"])
        assertTrue(CampSkill.Part.SHOP in r.done())
        assertNothingForbidden(r.w)
    }

    /**
     * A card with two left today is bought in two dialogs, each proved by its
     * count one less: `+` is never tapped (the skill's head, the deviation
     * from 3.3 point 2), and `-` and `-10` never either (3.6).
     */
    @Test
    fun `two left today are two dialogs, and no square of the dialog is ever tapped`() {
        val r = Rig()
        for (g in gates) r.tick(g, false)
        r.tick("battle_pass", true)
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("battle_pass", "battle_pass"), r.w.bought)
        assertEquals(0, r.w.stock.first { it.name == "battle_pass" }.left)
        assertEquals(2, r.skill.lastCounts["bought"])
        assertTrue(r.w.taps.none { it in setOf("plus", "plus10", "minus", "minus10") }, "${r.w.taps}")
        assertNothingForbidden(r.w)
    }

    /** 3.6: no purchase of an item without its box -- whatever the shop offers. */
    @Test
    fun `an item whose box is not ticked is never bought`() {
        val r = Rig()
        for (g in gates) r.tick(g, false)
        r.tick("d_scanner", true)
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("d_scanner"), r.w.bought)
        assertTrue(r.w.taps.filter { it.startsWith("card ") }.all { it == "card d_scanner" }, "${r.w.taps}")
        assertNothingForbidden(r.w)
    }

    /** With no box ticked the shop has nothing to do: not opened, and nothing stamped for it (question 1). */
    @Test
    fun `with no box ticked the shop is not opened`() {
        val r = Rig()
        for (g in gates) r.tick(g, false)
        assertEquals(Result.DONE, r.skill.run().result)
        assertTrue("shop" !in r.w.taps, "${r.w.taps}")
        assertFalse(CampSkill.Part.SHOP in r.done())
        assertFalse(r.skill.hasBudget(), "a shop with nothing ticked is no work")
        assertNothingForbidden(r.w)
    }

    /** 3.6, no purchase at a red price: the card's pink price skips it without its dialog (question 6: the day is stamped). */
    @Test
    fun `a pink price on the card is not opened, and the day is stamped`() {
        val r = Rig()
        r.w.coins = 300
        assertEquals(Result.DONE, r.skill.run().result)
        assertTrue(r.w.bought.isEmpty())
        assertTrue(r.w.taps.none { it.startsWith("card gate") }, "${r.w.taps}")
        assertTrue(r.said("camp: not enough Camp Coins for Digital Gate: Blue x1 -- its price is red"), "${r.log}")
        assertTrue(CampSkill.Part.SHOP in r.done())
        assertNothingForbidden(r.w)
    }

    /**
     * Live on 2026-11-01, instance 1 at 220 Camp Coins: each Gate skipped on
     * its pink price asked the untouched shop again within 1.6 s, and the
     * fourth park said "The Camp Shop went away" over the standing shop.
     */
    @Test
    fun `gates skipped on their pink price at a slow look do not lose the shop`() {
        val r = Rig()
        r.w.coins = 300
        r.w.lookCost = 1.3
        assertEquals(Result.DONE, r.skill.run().result)
        assertTrue(r.w.bought.isEmpty())
        assertEquals(emptyList(), r.kept, "${r.log}")
        assertTrue(r.said("camp: not enough Camp Coins for Digital Gate: Green x1 -- its price is red"), "${r.log}")
        assertTrue(CampSkill.Part.SHOP in r.done())
        assertEquals(CampWorld.Screen.MAIN, r.w.screen)
        assertNothingForbidden(r.w)
    }

    /** The same morning: the shop standing open, handed over, was not seen within 1.6 s, and the pass waited for the Camp screen. */
    @Test
    fun `the shop standing open is seen at a slow look`() {
        val r = Rig()
        r.w.screen = CampWorld.Screen.SHOP
        r.w.lookCost = 1.3
        val img = r.w.grab()
        assertEquals(Result.DONE, r.skill.work(img).result)
        img.release()
        assertTrue(r.w.taps.first().startsWith("card "), "the shop first: ${r.w.taps}")
        assertEquals(CampWorld.Screen.CAMP, r.w.screen)
        assertEquals(CampSkill.Part.values().toSet(), r.done())
        assertNothingForbidden(r.w)
    }

    /** 3.6, no purchase at a red price: the dialog's red price closes the dialog, and the price button is never tapped. */
    @Test
    fun `a red price in the dialog closes it unbought`() {
        val r = Rig()
        r.w.cardPrices = false
        r.w.dialogLies = true
        assertEquals(Result.DONE, r.skill.run().result)
        assertTrue(r.w.bought.isEmpty())
        assertTrue(r.w.taps.none { it.startsWith("red buy") || it.startsWith("buy ") }, "${r.w.taps}")
        assertEquals(4, r.w.taps.count { it == "close dialog" }, "each named gate's dialog closed: ${r.w.taps}")
        assertTrue(r.said("camp: not enough Camp Coins for Digital Gate: Blue x1"), "${r.log}")
        assertNothingForbidden(r.w)
    }

    /** 3.3 point 2: a purchase that shows nothing is not tried again. */
    @Test
    fun `a purchase that does not take is not tried again`() {
        val r = Rig()
        for (g in gates) r.tick(g, false)
        r.tick("gate_blue", true)
        r.w.buyTakes = false
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(1, r.w.taps.count { it == "buy gate_blue" })
        assertTrue(r.said("camp: the purchase of Digital Gate: Blue x1 did not take -- not tried again"), "${r.log}")
        assertEquals(0, r.skill.lastCounts["bought"])
        assertEquals(listOf("camp_buy_no_effect"), r.kept)
        assertNothingForbidden(r.w)
    }

    /** 3.3 point 1: a button that stays lit is tapped once more, then "the button did not take" and no stamp. */
    @Test
    fun `Support All that does not take is tapped twice and left without a stamp`() {
        val r = Rig()
        r.w.supportTakes = false
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(2, r.w.taps.count { it == "support all" })
        assertTrue(r.said("camp: Support All -- the button did not take"), "${r.log}")
        assertFalse(CampSkill.Part.SUPPORT in r.done())
        assertTrue(CampSkill.Part.CHECKIN in r.done(), "Check-In went on")
        assertTrue(r.skill.hasBudget(), "the support is owed")
        assertNothingForbidden(r.w)
    }

    @Test
    fun `a Check-In without a sheet counts by its grey button`() {
        val r = Rig()
        r.w.checkInSheet = false
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(1, r.w.checkedIn)
        assertEquals(1, r.skill.lastCounts["checkins"])
        assertTrue(CampSkill.Part.CHECKIN in r.done())
        assertNothingForbidden(r.w)
    }

    // ------------------------------------------------------------------------
    // The stations (3.3 point 3, questions 3 and 9)
    // ------------------------------------------------------------------------
    /**
     * Question 9, the player's alternative: at a station whose Auto Clear is
     * dim while attempts are left, one Attempt -- the battle that plays
     * itself -- then Auto Clear for the rest. The one exception to "never the
     * button the switch does not choose" (3.6).
     */
    @Test
    fun `a station without a record gets one Attempt, then Auto Clear`() {
        val r = Rig()
        r.w.record[CampStation.Which.DOJO] = false
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf(CampStation.Which.DOJO), r.w.attemptsPlayed)
        assertEquals(1, r.w.taps.count { it == "attempt dojo" })
        assertEquals(1, r.w.taps.count { it == "results close" })
        assertEquals(3, r.w.autoClears, "one at the Dojo after the Attempt, two at the Drill Site")
        assertEquals(4, r.skill.lastCounts["cleared"])
        assertTrue(r.said("camp: Auto Clear is dim at Leomon's Dojo: it lights once the station has a record -- " +
                          "one Attempt there first, as you asked"), "${r.log}")
        assertEquals(setOf(CampStation.Which.DOJO), Stored.camp(r.store, r.nowEpoch).attempted)
        assertTrue(r.w.taps.none { it.endsWith("with auto clear lit") }, "${r.w.taps}")
        assertTrue(CampSkill.Part.STATIONS in r.done())
        assertNothingForbidden(r.w)
    }

    /** 3.6: never more than that one Attempt a station and day -- not in the pass, not in the next. */
    @Test
    fun `a station whose Auto Clear stays dim gets no second Attempt that day`() {
        val r = Rig()
        r.w.record[CampStation.Which.DOJO] = false
        r.w.recordAfterAttempt = false
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf(CampStation.Which.DOJO), r.w.attemptsPlayed)
        assertTrue(r.said("camp: Auto Clear stayed dim after the Attempt at Leomon's Dojo -- no second Attempt"), "${r.log}")
        assertFalse(CampSkill.Part.STATIONS in r.done(), "the station is not done")
        // The next pass of the same day: the Attempt is spent.
        r.w.screen = CampWorld.Screen.MAIN
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf(CampStation.Which.DOJO), r.w.attemptsPlayed, "no second Attempt")
        assertTrue(r.said("camp: Auto Clear is dim at Leomon's Dojo, and its one Attempt of the day is spent -- nothing tapped"),
                   "${r.log}")
        assertEquals(1, r.w.taps.count { it == "attempt dojo" })
        assertNothingForbidden(r.w)
    }

    /** Question 3: the page's switch on Attempt: every attempt is a battle, and Auto Clear is never tapped. */
    @Test
    fun `with the switch on Attempt every attempt is an Attempt and Auto Clear is never tapped`() {
        val r = Rig()
        r.store.put(Stored.CAMP_ATTEMPT_KEY, true)
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(4, r.w.attemptsPlayed.size)
        assertEquals(0, r.w.autoClears)
        assertTrue(r.w.taps.none { it.startsWith("auto clear") }, "${r.w.taps}")
        assertEquals(4, r.skill.lastCounts["cleared"])
        assertNothingForbidden(r.w)
    }

    /** Question 3, the other side: with the switch on Auto Clear, Attempt is never tapped where Auto Clear is lit. */
    @Test
    fun `with the switch on Auto Clear no Attempt is tapped where Auto Clear is lit`() {
        val r = Rig()
        assertEquals(Result.DONE, r.skill.run().result)
        assertTrue(r.w.taps.none { it.startsWith("attempt") }, "${r.w.taps}")
        assertNothingForbidden(r.w)
    }

    @Test
    fun `Auto Clear that does not take is tapped twice and the stations are not stamped`() {
        val r = Rig()
        r.w.clearTakes = false
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(2, r.w.taps.count { it == "auto clear dojo" })
        assertEquals(2, r.w.taps.count { it == "auto clear drill" })
        assertTrue(r.said("camp: Auto Clear did not take at Leomon's Dojo"), "${r.log}")
        assertFalse(CampSkill.Part.STATIONS in r.done())
        assertNothingForbidden(r.w)
    }

    // ------------------------------------------------------------------------
    // The switch, the pause, the park (3.3, 3.6)
    // ------------------------------------------------------------------------
    /**
     * Two pauses in one pass (PLAN_RELEASE_1_3.md B4): the switch goes off
     * right after Support All -- the pass stops on its Reward sheet, the
     * director's `unknown` -- and again after the first purchase. Each time
     * the pass goes on where it stands, nothing done twice, the card's parts
     * adding up, and home at the end. No tap while the switch is off.
     */
    @Test
    fun `the Camp paused twice does each thing once and goes home`() {
        val r = Rig()
        r.w.afterTap = { if (it == "support all") on = false }
        val counts = ArrayList<Map<String, Int>>()
        assertEquals(Result.STOPPED, r.skill.run().result)
        counts += r.skill.lastCounts
        r.w.t += 2.0
        val sheet = r.w.grab()
        assertTrue(Dungeon.rewardSheet(sheet), "stopped under the sheet")
        assertTrue(r.skill.resumesOn(Director.classify(sheet).screen, sheet), "the sheet is the pass's own")

        r.w.on = true
        r.w.afterTap = { if (it == "buy gate_blue") on = false }
        assertEquals(Result.STOPPED, r.skill.resume(sheet, whole = true).result)
        counts += r.skill.lastCounts
        r.w.t += 2.0
        val next = r.w.grab()
        assertTrue(r.skill.resumesOn(Director.classify(next).screen, next), "${Director.classify(next)}")

        r.w.on = true
        r.w.afterTap = {}
        val out = r.skill.resume(next, whole = true)
        assertEquals(Result.DONE, out.result, out.why)
        counts += r.skill.lastCounts
        assertEquals(CampWorld.Screen.MAIN, r.w.screen)
        assertEquals(1, r.w.supported)
        assertEquals(listOf("gate_blue", "gate_pink", "gate_yellow", "gate_green"), r.w.bought)
        val sum = counts.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        assertEquals(1, sum["supports"], "$counts")
        assertEquals(4, sum["bought"], "$counts")
        assertEquals(4, sum["cleared"], "$counts")
        assertTrue(r.w.taps.none { it == "while off" }, "${r.w.taps}")
        assertEquals(CampSkill.Part.values().toSet(), r.done())
        assertNothingForbidden(r.w)
    }

    /**
     * The switch off during the Attempt's battle: nothing is tapped in the
     * battle, the pass stops at the Results' Close, and the pass that goes on
     * from the Results (the director's `dialog`) closes them, counts the
     * Attempt once -- the stopped pass counted it at the Results -- and goes
     * on with Auto Clear.
     */
    @Test
    fun `paused in the Attempt's battle, the pass goes on from its Results`() {
        val r = Rig()
        r.w.record[CampStation.Which.DOJO] = false
        r.w.afterTap = { if (it == "attempt dojo") on = false }
        val counts = ArrayList<Map<String, Int>>()
        assertEquals(Result.STOPPED, r.skill.run().result)
        counts += r.skill.lastCounts
        assertEquals(CampWorld.Screen.RESULTS, r.w.screen)
        val results = r.w.grab()
        assertTrue(r.skill.resumesOn(Director.classify(results).screen, results), "the Results are the pass's own")
        r.w.on = true
        r.w.afterTap = {}
        val out = r.skill.resume(results, whole = true)
        results.release()
        assertEquals(Result.DONE, out.result, out.why)
        counts += r.skill.lastCounts
        assertEquals(4, counts.sumOf { it["cleared"] ?: 0 }, "$counts")
        assertEquals(listOf(CampStation.Which.DOJO), r.w.attemptsPlayed)
        assertEquals(1, r.w.taps.count { it == "results close" })
        assertTrue(r.w.taps.none { it == "while off" || it == "battle" }, "${r.w.taps}")
        assertEquals(CampWorld.Screen.MAIN, r.w.screen)
        assertNothingForbidden(r.w)
    }

    /** 3.6, no tap with the switch off: off at the start, nothing at all. */
    @Test
    fun `with the switch off nothing is tapped`() {
        val r = Rig()
        r.w.on = false
        assertEquals(Result.STOPPED, r.skill.run().result)
        assertTrue(r.w.taps.isEmpty(), "${r.w.taps}")
    }

    /**
     * Question 10's proposal: a window of the game's over the Camp (the
     * "Get Reward" C1 met, the director's `dialog`) is a park with its
     * sentence, and nothing on it is tapped -- 3.6, no OK on a prompt the task
     * did not raise.
     */
    @Test
    fun `a window of the game's over the Camp is a park, nothing tapped on it`() {
        val r = Rig()
        r.w.getRewardOnOpen = true
        val out = r.skill.run()
        assertEquals(Result.PARKED, out.result)
        assertEquals(CampSkill.DIALOG_OVER, out.why)
        assertEquals(listOf("camp tab"), r.w.taps)
        assertEquals(listOf("camp_dialog"), r.kept)
        assertNothingForbidden(r.w)
    }

    @Test
    fun `run starts from the main screen only`() {
        val r = Rig()
        r.w.screen = CampWorld.Screen.STATION
        r.w.station = CampStation.Which.DOJO
        val out = r.skill.run()
        assertEquals(Result.PARKED, out.result)
        assertEquals("The Camp task starts from the main screen.", out.why)
        assertTrue(r.w.taps.isEmpty())
    }

    @Test
    fun `a main screen without the Camp tab is no tap, and the frame is kept`() {
        val r = Rig()
        r.w.tabOnMain = false
        assertEquals(Result.DONE, r.skill.run().result)
        assertTrue(r.w.taps.isEmpty(), "${r.w.taps}")
        assertEquals(listOf("camp_no_tab"), r.kept)
    }

    // ------------------------------------------------------------------------
    // The Camp the player opened (question 5), and the chain's second hand
    // ------------------------------------------------------------------------
    @Test
    fun `the Camp screen handed over is worked and left standing`() {
        val r = Rig()
        r.w.screen = CampWorld.Screen.CAMP
        val img = r.w.grab()
        assertTrue(r.skill.worksOn(Director.CAMP))
        assertNull(r.skill.seesWork(Director.CAMP, img), "once a visit, by the stamps")
        val out = r.skill.work(img)
        img.release()
        assertEquals(Result.DONE, out.result, out.why)
        assertEquals(CampWorld.Screen.CAMP, r.w.screen, "left on the Camp screen, not home")
        assertTrue("globe" !in r.w.taps)
        assertEquals(CampSkill.Part.values().toSet(), r.done())
        assertTrue(r.said("camp: the Camp is open -- working it"), "${r.log}")
        assertNothingForbidden(r.w)
    }

    /** From the window that is open, that part first; then the rest, and back to the Camp screen. */
    @Test
    fun `the shop handed over is worked first, then the rest`() {
        val r = Rig()
        r.w.screen = CampWorld.Screen.SHOP
        val img = r.w.grab()
        assertEquals(Director.CAMP_SHOP, Director.classify(img).screen)
        assertEquals(Result.DONE, r.skill.work(img).result)
        img.release()
        val first = r.w.taps.first()
        assertTrue(first.startsWith("card "), "the shop first: ${r.w.taps}")
        assertTrue(r.w.taps.indexOf("tent") > r.w.taps.indexOf("close shop"), "${r.w.taps}")
        assertEquals(CampWorld.Screen.CAMP, r.w.screen)
        assertEquals(CampSkill.Part.values().toSet(), r.done())
        assertNothingForbidden(r.w)
    }

    @Test
    fun `the Drill Site handed over is worked first`() {
        val r = Rig()
        r.w.screen = CampWorld.Screen.STATION
        r.w.station = CampStation.Which.DRILL
        val img = r.w.grab()
        assertEquals(Result.DONE, r.skill.work(img).result)
        img.release()
        assertEquals("auto clear drill", r.w.taps.first(), "${r.w.taps}")
        assertTrue(r.w.taps.indexOf("dojo") > r.w.taps.lastIndexOf("auto clear drill"), "${r.w.taps}")
        assertEquals(CampWorld.Screen.CAMP, r.w.screen)
        assertNothingForbidden(r.w)
    }

    /** The player's purchase dialog: nothing bought in it -- it is closed, and the shop worked from its list. */
    @Test
    fun `a purchase dialog the player left open is closed, not bought in`() {
        val r = Rig()
        for (g in gates) r.tick(g, false)
        r.tick("gate_blue", true)
        r.w.atTop = true
        r.w.dialogItem = 8
        r.w.screen = CampWorld.Screen.DIALOG
        val img = r.w.grab()
        assertEquals(Result.DONE, r.skill.work(img).result)
        img.release()
        assertEquals("close dialog", r.w.taps.first())
        assertEquals(listOf("gate_blue"), r.w.bought, "the digivice the dialog was open on is not bought")
        assertNothingForbidden(r.w)
    }

    @Test
    fun `leave goes home from any window, and not with the switch off`() {
        for (s in listOf(CampWorld.Screen.BOXES, CampWorld.Screen.SHOP, CampWorld.Screen.TENT, CampWorld.Screen.CAMP)) {
            val r = Rig()
            r.w.screen = s
            r.w.station = CampStation.Which.DOJO
            assertTrue(r.skill.leave(), "$s")
            assertEquals(CampWorld.Screen.MAIN, r.w.screen, "$s")
            assertEquals("globe", r.w.taps.last())
            assertTrue(r.w.bought.isEmpty() && r.w.autoClears == 0 && r.w.supported == 0, "leave does nothing else")
            assertNothingForbidden(r.w)
        }
        val off = Rig()
        off.w.screen = CampWorld.Screen.SHOP
        off.w.on = false
        assertFalse(off.skill.leave())
        assertTrue(off.w.taps.isEmpty())
        assertTrue(off.said(Stays.LINE), "${off.log}")
    }

    // ------------------------------------------------------------------------
    // The day, the card, the page (3.4, 3.5)
    // ------------------------------------------------------------------------
    /** The stamps are the game day's: from 08:00 on every part is open again, and a second pass counts its own. */
    @Test
    fun `two days are two passes, each counted on its own`() {
        val r = Rig()
        val card = MapSettings()
        val counted = Counted(r.skill, { r.skill.lastCounts }) { key, c -> SkillStats.add(card, key, c, 1L) }
        r.w.available[CampStation.Which.DRILL] = 1 to 0
        assertEquals(Result.DONE, counted.run().result)
        assertEquals(mapOf("supports" to 1, "checkins" to 1, "bought" to 4, "cleared" to 4, "boxes" to 1),
                     SkillStats.read(card, "camp", 1L))
        assertFalse(r.skill.hasBudget())
        // The next game day: the game hands out again, the stamps are yesterday's.
        r.nowEpoch = QuestSkill.nextReset(NOON) + 60
        assertTrue(r.skill.hasBudget())
        r.w.supportOpen = true
        r.w.checkInOpen = true
        r.w.attempts[CampStation.Which.DOJO] = 2
        r.w.attempts[CampStation.Which.DRILL] = 2
        for (g in gates) r.w.stock.first { it.name == g }.left = 1
        assertEquals(Result.DONE, counted.run().result)
        assertEquals(mapOf("supports" to 1, "checkins" to 1, "bought" to 4, "cleared" to 4, "boxes" to 0),
                     r.skill.lastCounts, "the second pass's own")
        assertEquals(mapOf("supports" to 2, "checkins" to 2, "bought" to 8, "cleared" to 8, "boxes" to 1),
                     SkillStats.read(card, "camp", 1L))
        assertEquals("2 camp supports, 2 camp check-ins, 8 Camp Shop purchases, 8 station clears, 1 reward box claimed.",
                     SkillStats.sentence("camp", SkillStats.read(card, "camp", 1L)))
        assertNothingForbidden(r.w)
    }

    @Test
    fun `the day says the Camp is done, and the row's switch starts it again`() {
        val s = MapSettings()
        assertNull(Stored.day(s, CampSkill.KEY, NOON))
        Stored.stampCamp(s, CampSkill.Part.SUPPORT, NOON)
        assertEquals(Stored.Day("1 of 4 Camp parts done today", false), Stored.day(s, CampSkill.KEY, NOON))
        for (p in CampSkill.Part.values()) Stored.stampCamp(s, p, NOON)
        Stored.campAttempted(s, CampStation.Which.DRILL, NOON)
        assertEquals(Stored.Day("the Camp is done for today", true), Stored.day(s, CampSkill.KEY, NOON))
        assertNull(Stored.day(s, CampSkill.KEY, QuestSkill.nextReset(NOON)), "08:00 opens the day again")
        assertEquals(Stored.Day("the Camp is done for today", true), Stored.restart(s, CampSkill.KEY, NOON))
        assertTrue(Stored.camp(s, NOON).done.isEmpty())
        assertEquals(setOf(CampStation.Which.DRILL), Stored.camp(s, NOON).attempted,
                     "the station's one Attempt stays spent: 3.6, switch or no switch")
    }

    @Test
    fun `the page is the button, the switch and the shop's boxes under a heading`() {
        val page = SkillSettings.page(CampSkill.KEY)
        assertTrue(page.supporter)
        val run = page.fields[0] as SkillSettings.RunNow
        assertEquals(CampSkill.KEY, run.task)
        assertEquals("Run now", run.label)
        val sw = page.fields[1] as SkillSettings.Toggle
        assertEquals(Stored.CAMP_ATTEMPT_KEY, sw.key)
        assertFalse(sw.default, "Auto Clear by default (question 3)")
        val boxes = page.fields.drop(2).map { it as SkillSettings.Toggle }
        assertEquals(CampIcons.NAMES.map { Stored.campBuyKey(it) }, boxes.map { it.key }, "a box per item, in the grid's order")
        assertEquals(CampIcons.NAMES, SkillSettings.CAMP_ITEMS.map { it.item })
        assertEquals("Camp Shop", boxes[0].heading)
        assertTrue(boxes.drop(1).all { it.heading.isEmpty() })
        assertEquals(gates.map { Stored.campBuyKey(it) }, boxes.filter { it.default }.map { it.key }, "question 1: the gates")
        assertEquals(gates, Stored.camp(MapSettings(), NOON).buy)
        assertTrue(CampSkill.KEY in SkillSettings.CHAINABLE)
        assertTrue(CampSkill.KEY !in SkillSettings.CHAIN_DEFAULT)
        assertEquals("Camp", SkillSettings.CHAIN_NAMES[CampSkill.KEY])
        assertTrue(Paywall.needsCode(CampSkill.KEY), "a supporter's task")
        assertTrue(CampSkill.KEY in SkillStats.SHOWN)
        assertTrue(CampSkill.KEY in Stored.RESTARTS)
        // The row in the app's list, and the director's wiring.
        val repo = File(System.getProperty("digiautotap.repo") ?: "..")
        val skills = File(repo, "app/src/main/kotlin/io/github/digipr1me/digiautotap/Skills.kt").readText()
        assertTrue(Regex("""SkillRow\("camp",\s*"Camp",\s*"camp",""").containsMatchIn(skills), "the Camp row")
        val core = File(repo, "app/src/main/kotlin/io/github/digipr1me/digiautotap/CoreService.kt").readText()
        assertTrue("counted(camp) { camp.lastCounts }" in core, "the Camp is not counted in CoreService")
    }

    @Test
    fun `Run now runs the Camp from the next main screen`() {
        val w = CampWorld()
        val log = ArrayList<String>()
        val store = MapSettings()
        val skill = CampSkill(w, icons, { Stored.camp(store, NOON) }, stamp = { Stored.stampCamp(store, it, NOON) },
                              attempted = { Stored.campAttempted(store, it, NOON) },
                              log = { log += it }, on = { w.on }, sleep = { w.t += it }, now = { w.t })
        val d = DirectorLoop(w, listOf(skill), emptyList(), { CampWorld.GAME }, { SkillSettings.MODE_SEMI },
                             Chain(emptyList(), log = {}), on = { w.on }, log = { log += it }, now = { w.t })
        d.runNow = CampSkill.KEY
        val t = d.tick()
        assertEquals("run camp", t.did, "$t")
        assertTrue(log.contains("running Camp now, as asked"), "$log")
        assertEquals(CampWorld.Screen.MAIN, w.screen)
        assertEquals(4, w.bought.size)
        assertNothingForbidden(w)
        d.close()
    }

    /** A supporter's task: without a code the button is refused, the screens are nobody's, the chain skips it. */
    @Test
    fun `without a supporter code the Camp does nothing anywhere`() {
        for (mode in listOf(SkillSettings.MODE_SEMI, SkillSettings.MODE_FULL)) {
            val w = CampWorld()
            val log = ArrayList<String>()
            val store = MapSettings()
            val skill = CampSkill(w, icons, { Stored.camp(store, NOON) }, stamp = { Stored.stampCamp(store, it, NOON) },
                                  attempted = { Stored.campAttempted(store, it, NOON) },
                                  log = { log += it }, on = { w.on }, sleep = { w.t += it }, now = { w.t })
            val chain = Chain(listOf(Chain.Step(CampSkill.KEY)), log = { log += it })
            val d = DirectorLoop(w, listOf(skill), emptyList(), { CampWorld.GAME }, { mode }, chain,
                                 included = { false }, on = { w.on }, log = { log += it }, now = { w.t })
            d.runNow = CampSkill.KEY
            repeat(3) { w.t += d.tick().beat }
            assertTrue(log.any { "asked to run camp now, but that task is switched off or locked" in it }, "$log")
            if (mode == SkillSettings.MODE_FULL) {
                assertEquals(listOf(CampSkill.KEY to "not included"), chain.skipped)
            } else {
                w.screen = CampWorld.Screen.CAMP
                repeat(6) { w.t += d.tick().beat }
                assertNull(d.parked)
            }
            assertTrue(w.taps.isEmpty(), "$mode: ${w.taps}")
            d.close()
        }
    }

    private companion object {
        val icons = CampIcons(ClassPathAssets)

        /** 2026-09-23 12:00 in Vienna (CEST, UTC+2), as SettingsStoreTest has it. */
        const val NOON = 1790157600.0
    }
}
