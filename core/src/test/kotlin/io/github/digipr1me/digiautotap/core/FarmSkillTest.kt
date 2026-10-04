package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Mat
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Meat Field skill offline, no emulator needed: test_farm_flow.py in
 * Kotlin, the same script and the same cases.
 *
 * Two differences from the Python, both in the same direction. The Python
 * test replaces a dozen readers with lambdas over a dict and tests the state
 * machine alone; here the world is **painted** (PaintFarm) and read by the
 * real Farm.kt, Explore.kt and Dungeon.kt, so a case proves the loop and the
 * readers together. And a tap is a pair of device pixels, not a label: what
 * says the bubble was never tapped is where the tap landed, not what the
 * caller called it.
 *
 * The capture is DirectorTest.FakeCapture, playing back whatever the world
 * paints for the current call and recording every tap; the world reads those
 * taps back and reacts the way the game would. A fixed queue of frames would
 * have to be exactly as long as however many times a visit happens to grab,
 * which is brittle to any change in a retry count; a world that reacts is not.
 */
class FarmSkillTest {

    // ------------------------------------------------------------------------
    // next_visit -- pure function, the cases from PLAN_MEAT_FIELD section 7
    // ------------------------------------------------------------------------
    @Test
    fun `next_visit answers the worked cases of the plan`() {
        fun at(v: FarmSkill.Visit) = v.at

        assertEquals(140.0, at(FarmSkill.nextVisit(0.0, listOf(7200, 3900, 120), emptyLeft = 0)),
                     "1 timers, no plot empty")
        assertEquals(7520.0, at(FarmSkill.nextVisit(0.0, listOf(99999), growSeconds = 7500,
                                                    emptyLeft = 0)),
                     "2 misread 99999 capped to grow_seconds 7500")
        assertEquals(7500.0, at(FarmSkill.nextVisit(0.0, emptyList(), growSeconds = 7500,
                                                    emptyLeft = 0, growing = 1)),
                     "3a no timer read, grow_seconds known")
        assertEquals(FarmSkill.FALLBACK.toDouble(),
                     at(FarmSkill.nextVisit(0.0, emptyList(), growSeconds = null, emptyLeft = 0,
                                            growing = 1)),
                     "3b no timer read, grow_seconds unknown")
        assertEquals(920.0, at(FarmSkill.nextVisit(0.0, listOf(5000), emptyLeft = 1,
                                                   freeSeeds = 0, refillIn = 900)),
                     "4 empty, 0 seeds, refill 900, shortest timer 5000")
        assertEquals(320.0, at(FarmSkill.nextVisit(0.0, listOf(300), emptyLeft = 1,
                                                   freeSeeds = 0, refillIn = 900)),
                     "5 empty, 0 seeds, refill 900, shortest timer 300")
        assertEquals(5020.0, at(FarmSkill.nextVisit(0.0, listOf(5000), emptyLeft = 0,
                                                    freeSeeds = 0, refillIn = 900)),
                     "6 no plot empty, 0 seeds, refill 900 -- not a reason")
        assertEquals((FarmSkill.REFILL + FarmSkill.MARGIN).toDouble(),
                     at(FarmSkill.nextVisit(0.0, emptyList(), emptyLeft = 1, freeSeeds = 0,
                                            refillIn = null)),
                     "7 empty, 0 seeds, refill unreadable -> REFILL")
        assertEquals((FarmSkill.REFILL + FarmSkill.MARGIN).toDouble(),
                     at(FarmSkill.nextVisit(0.0, emptyList(), emptyLeft = 1, freeSeeds = 0,
                                            refillIn = 99999)),
                     "8 refill misread 99999 capped to REFILL")
        assertEquals(FarmSkill.FALLBACK.toDouble(),
                     at(FarmSkill.nextVisit(0.0, emptyList(), emptyLeft = 1, freeSeeds = 5)),
                     "empty plot with seeds on hand -> FALLBACK")
        assertEquals((FarmSkill.REFILL + FarmSkill.MARGIN).toDouble(),
                     at(FarmSkill.nextVisit(0.0, emptyList(), emptyLeft = 0, growing = 0)),
                     "nothing growing, nothing empty, no timer -> REFILL")
        // A visit that planted nothing learns no grow duration of its own, so
        // the one it is given is the remembered `farm_grow_seconds` -- and
        // that is the only cap in the table that was NOT drawn out of
        // `timers`, so it is the only one that can ever fire (the laboratory's
        // notes, "A number taken out of the list it is meant to cap is not
        // a cap").
        assertEquals(7220.0,
                     at(FarmSkill.nextVisit(0.0, listOf(3 * 24 * 3600, 2 * 24 * 3600),
                                            growSeconds = 7200, emptyLeft = 0)),
                     "9a planted nothing, every timer misread, remembered grow caps it")
        assertEquals((2 * 24 * 3600 + FarmSkill.MARGIN).toDouble(),
                     at(FarmSkill.nextVisit(0.0, listOf(3 * 24 * 3600, 2 * 24 * 3600),
                                            growSeconds = null, emptyLeft = 0)),
                     "9b the same field with nothing remembered -- two days away")

        // Row 10, the threshold of 13 (PLAN_MEAT_FIELD_GIESSEN.md, 2026-09-28):
        // a plot left to grow because the cans do not ripen it.
        // 01:40:00 and three cans, a can in 40:00: three ripen it at 01:30:00,
        // ten minutes on -- before the can.
        assertEquals(620.0, at(FarmSkill.nextVisit(0.0, listOf(6000), cans = 3, canRefillIn = 2400,
                                                   wanting = listOf(6000))),
                     "10a the plot shrinks to the cans first")
        assertEquals("enough cans for a plot left to grow",
                     FarmSkill.nextVisit(0.0, listOf(6000), cans = 3, canRefillIn = 2400,
                                         wanting = listOf(6000)).why)
        // 03:00:00 and one can, the next in 05:00 and one an hour after: two
        // cans from 05:00 reach 1:00:00, three from 1:05:00 reach 1:30:00,
        // and the plot is down to 1:30:00 by itself at 1:30:00 on.
        assertEquals(5420.0, at(FarmSkill.nextVisit(0.0, listOf(10800), cans = 1, canRefillIn = 300,
                                                    wanting = listOf(10800))),
                     "10b the plot and the cans meet on the way")
        // 01:40:00, three cans, the can in 05:00: the can makes four first.
        assertEquals(320.0, at(FarmSkill.nextVisit(0.0, listOf(6000), cans = 3, canRefillIn = 300,
                                                   wanting = listOf(6000))),
                     "10c enough cans come first")
        // The countdown unread: one can an hour (REFILL) from now.
        assertEquals(620.0, at(FarmSkill.nextVisit(0.0, listOf(6000), cans = 3, canRefillIn = null,
                                                   wanting = listOf(6000))),
                     "10d the countdown unread, the plot shrinks first all the same")
        // A plot that drops under the threshold before the cans reach it is
        // no reason: it ripens by itself (row 1). 00:50:00 with one can: two
        // cans only from 05:00, at 45:00 left -- over a threshold of 40, so
        // at 05:00; under one of 46, never, and row 1 books its ripening.
        assertEquals(320.0, at(FarmSkill.nextVisit(0.0, listOf(3000), cans = 1, canRefillIn = 300,
                                                   wanting = listOf(3000), waterMin = 40 * 60)),
                     "10e reached at 45:00 left, over a threshold of 40 min")
        assertEquals(3020.0, at(FarmSkill.nextVisit(0.0, listOf(3000), cans = 1, canRefillIn = 300,
                                                    wanting = listOf(3000), waterMin = 46 * 60)),
                     "10f under a threshold of 46 min first -- the plot ripens by itself")
        // A field that ripens before the cans could: row 1 wins.
        assertEquals(620.0, at(FarmSkill.nextVisit(0.0, listOf(600, 6000), cans = 3, canRefillIn = 2400,
                                                   wanting = listOf(6000))),
                     "10g a plot ripens at the same moment: either")
        assertEquals(320.0, at(FarmSkill.nextVisit(0.0, listOf(300, 6000), cans = 3, canRefillIn = 2400,
                                                   wanting = listOf(6000))),
                     "10h a plot ripening earlier comes first")
        // Nothing wanting, cans left: rows 1 to 6 as before.
        assertEquals(6020.0, at(FarmSkill.nextVisit(0.0, listOf(6000), cans = 3, canRefillIn = 300)))

        // And it says why, every time.
        val why = FarmSkill.nextVisit(0.0, emptyList(), emptyLeft = 1, freeSeeds = 0,
                                      refillIn = null).why
        assertTrue(why.isNotEmpty() && why.contains("seed"), why)
    }

    @Test
    fun `farm_is_due counts a field nobody has ever visited as due`() {
        assertTrue(FarmSkill.farmIsDue(null, 1000.0), "nothing recorded has to count as due")
        assertTrue(FarmSkill.farmIsDue(1000.0, 1000.0))
        assertTrue(!FarmSkill.farmIsDue(1001.0, 1000.0))
    }

    // ------------------------------------------------------------------------
    // The painted frames, against the real readers
    // ------------------------------------------------------------------------
    @Test
    fun `the painted frames read as the field they are painted as`() {
        val img = PaintFarm.field(listOf(
            PaintFarm.Plot("ripe", harvest = "276"),
            PaintFarm.Plot("growing", timer = "00:50:00"),
            PaintFarm.Plot("empty"),
            PaintFarm.Plot(null),
            PaintFarm.Plot("growing", timer = "01:06:40"),
            PaintFarm.Plot("ripe")), seeds = "11", refill = "05:00")
        assertEquals(6, Farm.meatFieldScreen(img))
        assertEquals(Director.FIELD, Director.classify(img).screen,
                     "the director has to see a field here: ${Director.classify(img)}")
        assertEquals(listOf(listOf("ripe", "growing"), listOf("empty", null),
                            listOf("growing", "ripe")),
                     Farm.plots(img))
        assertEquals(3000, Farm.plotTimer(img, 1, 0))
        assertEquals(4000, Farm.plotTimer(img, 0, 2))
        assertEquals(276, Farm.plotHarvest(img, 0, 0))
        assertEquals(11, Farm.freeSeeds(img))
        assertEquals(300, Farm.seedRefill(img))
        assertNull(Farm.seedMenu(img).first, "no dialog is open on this frame")
        assertNull(Farm.waterPopup(img), "and no popup either")
        img.release()

        // The seed menu, and the water popup, each with the other refused:
        // both dim the field's top row away, so meat_field_screen reads 4,
        // which is what the real dialog measures (PLAN_MEAT_FIELD 2.2).
        val menu = PaintFarm.field(List(6) { PaintFarm.Plot(null) }, menu = 0)
        assertEquals(4, Farm.meatFieldScreen(menu))
        val (slots, button) = Farm.seedMenu(menu)
        assertEquals(3, slots?.size)
        assertNotNull(button)
        assertTrue(Farm.seedSelected(menu, slots!![0]), "the free seed is the leftmost slot")
        assertTrue(!Farm.seedSelected(menu, slots[1]) && !Farm.seedSelected(menu, slots[2]))
        assertNull(Farm.waterPopup(menu), "the seed menu is not the water popup")
        assertEquals(Director.FIELD_DIALOG, Director.classify(menu).screen)
        menu.release()

        val popup = PaintFarm.field(List(6) { PaintFarm.Plot(null) }, popup = true)
        assertEquals(4, Farm.meatFieldScreen(popup))
        assertNotNull(Farm.waterPopup(popup))
        assertNull(Farm.seedMenu(popup).first, "the water popup has no slots over it")
        assertEquals(Director.FIELD_DIALOG, Director.classify(popup).screen)
        popup.release()

        // The tap point of every cell clears the bubble the game draws at
        // that cell's own outer top corner -- the whole reason plot_tap pulls
        // toward the inner column and not simply right of centre.
        val bubbles = PaintFarm.field(List(6) { PaintFarm.Plot("ripe", bubble = true) })
        for (row in 0 until 3) {
            for (col in 0 until 2) {
                assertTrue(!Farm.bubbleOver(bubbles, col, row),
                           "the tap point of $col,$row runs into the water bubble")
            }
        }
        bubbles.release()

        // And one sitting on the tap point itself is seen and vetoes it.
        val over = PaintFarm.field(List(6) { PaintFarm.Plot("ripe", bubbleOnTap = true) })
        for (row in 0 until 3) {
            for (col in 0 until 2) {
                assertTrue(Farm.bubbleOver(over, col, row),
                           "a bubble on the tap point of $col,$row was not seen")
            }
        }
        over.release()

        // The way there and the way back.
        val main = PaintFarm.mainScreen()
        assertEquals(Director.MAIN, Director.classify(main).screen)
        assertNotNull(Explore.exploreTab(main), "the Explore tab go_to_field taps")
        main.release()
        val menuScreen = PaintFarm.exploreMenu()
        assertEquals(Director.EXPLORE_MENU, Director.classify(menuScreen).screen)
        assertNotNull(Explore.meatFieldCard(menuScreen))
        assertNotNull(Dungeon.homeButton(menuScreen), "the globe leave_field goes home by")
        menuScreen.release()
    }

    // ------------------------------------------------------------------------
    // One visit, start to finish
    // ------------------------------------------------------------------------
    @Test
    fun `a visit harvests the ripe plots, plants the empty ones and goes home`() {
        val r = Rig(World(
            plots = mutableListOf(
                PaintFarm.Plot("ripe"),
                PaintFarm.Plot("growing", timer = "00:50:00"),
                PaintFarm.Plot("empty"),
                PaintFarm.Plot(null),
                PaintFarm.Plot("growing", timer = "01:06:40"),
                PaintFarm.Plot("ripe")),
            seeds = 5))
        val outcome = r.skill.run()

        assertEquals(Result.DONE, outcome.result, outcome.toString())
        assertNull(outcome.leaving, "the field raises no prompt of its own")
        assertEquals(2, r.world.harvested(), "both ripe plots: ${r.world.taps}")
        assertEquals(3, r.world.planted(), "the emptied plots and the one that was empty")
        assertTrue(r.world.taps.none { it == "plot 1,1" },
                   "tapped the plot nothing could be read on: ${r.world.taps}")
        assertEquals(World.MAIN, r.world.screen, "the visit has to end at home")
        // The globe follows the X at once: without that branch the way out
        // sat out every remaining round on the Explore menu.
        assertNotNull(r.world.grabsAtX)
        assertNotNull(r.world.grabsAtGlobe)
        assertTrue(r.world.grabsAtGlobe!! - r.world.grabsAtX!! <= 2,
                   "X at grab ${r.world.grabsAtX}, globe at grab ${r.world.grabsAtGlobe}")
        assertEquals(2, r.world.seeds, "five seeds, three planted")
        assertTrue(r.log.none { it.contains("did not fall by 1") }, r.log.toString())

        // The clock. Three plots were planted this visit at a full grow of
        // 02:00:00, and the shortest thing on the field is the 00:50:00 that
        // was already running -- so the next visit is that timer plus MARGIN,
        // and nothing about the seeds, because no plot was left empty.
        assertEquals("field ripens", r.reason)
        assertEquals(r.clock + 3000 + FarmSkill.MARGIN, r.due)
        assertEquals(7200, r.growSeconds, "the fresh plate is the grow duration")
        // And the skill now says it has nothing to do until then.
        assertTrue(!r.skill.hasBudget(), "due at ${r.due}, now ${r.clock}")
        // And says so with its clock, where a chain passes it by.
        val minutes = Math.ceil((3000 + FarmSkill.MARGIN) / 60.0).toInt()
        assertEquals("the field is not due yet -- next visit in $minutes min", r.skill.noBudgetWhy())
        r.clock = r.due!!
        assertTrue(r.skill.hasBudget(), "the moment it falls due")
        assertEquals(null, r.skill.noBudgetWhy())
    }

    @Test
    fun `the grow duration comes from a plot this visit planted`() {
        // (0,0) was already growing when the visit began and carries a
        // remainder, 00:10:00; (1,0) is planted here and starts at a full
        // 02:00:00. The closing round walks row-major, so the old test --
        // "has anything been planted at all" -- took the 00:10:00.
        val r = Rig(World(
            plots = mutableListOf(
                PaintFarm.Plot("growing", timer = "00:10:00"),
                PaintFarm.Plot("empty"),
                PaintFarm.Plot(null),
                PaintFarm.Plot(null),
                PaintFarm.Plot(null),
                PaintFarm.Plot(null)),
            seeds = 5))
        r.skill.run()

        assertEquals(1, r.world.planted(), "one empty plot: ${r.world.taps}")
        assertEquals(7200, r.growSeconds,
                     "the freshly planted plot's full grow, not the remainder")
        // The due time is unchanged either way, and that is the point: a cap
        // taken out of `timers` is one of the values being capped, so
        // min(capped) stays min(timers) and the cap can never fire.
        assertEquals("field ripens", r.reason)
        assertEquals(r.clock + 600 + FarmSkill.MARGIN, r.due)
    }

    @Test
    fun `nothing planted leaves the grow duration unknown`() {
        val r = Rig(World(
            plots = mutableListOf(
                PaintFarm.Plot("growing", timer = "00:10:00"),
                PaintFarm.Plot("empty"),
                PaintFarm.Plot(null),
                PaintFarm.Plot(null),
                PaintFarm.Plot(null),
                PaintFarm.Plot(null)),
            seeds = 0))
        r.skill.run()

        assertEquals(0, r.world.planted(), r.world.taps.toString())
        assertNull(r.growSeconds, "no fresh plate, so no grow duration")
    }

    @Test
    fun `a visit that plants nothing comes back at the remembered grow duration`() {
        // Every plot growing, every plate misread as a full day, no seed to
        // plant: this visit learns no grow duration of its own, so the cap is
        // the `farm_grow_seconds` an earlier visit left behind. Without it the
        // field would be left standing for a day, which is the one thing the
        // cap was written to prevent.
        fun world() = World(
            plots = MutableList(6) { PaintFarm.Plot("growing", timer = "23:59:59") },
            seeds = 0)

        val remembered = Rig(world())
        remembered.growSeconds = 7200         // what the last planting visit read
        remembered.skill.run()

        assertEquals(0, remembered.world.planted(), remembered.world.taps.toString())
        assertEquals("field ripens", remembered.reason)
        assertEquals(remembered.clock + 7200 + FarmSkill.MARGIN, remembered.due,
                     "capped at the remembered grow, not the misread plate")
        assertEquals(7200, remembered.growSeconds,
                     "a visit that planted nothing writes no grow duration back")

        // The same field with nothing remembered: the misread plate itself,
        // and that is what the read-back buys. Asked of what the reader
        // actually returned rather than of the painted 23:59:59 -- the painted
        // glyphs do not read back to the second, and which second it is makes
        // no difference to the point.
        val blank = Rig(world())
        blank.skill.run()
        val misread = blank.skill.lastVisit!!.timers.min()
        assertTrue(misread > 20 * 3600, "the plates read as most of a day: $misread")
        assertEquals(blank.clock + misread + FarmSkill.MARGIN, blank.due,
                     "nothing remembered, so nothing caps the misread plate")
        assertNull(blank.growSeconds)
    }

    @Test
    fun `a bubble over the tap point vetoes the harvest`() {
        val r = Rig(World(
            plots = (listOf(PaintFarm.Plot("ripe", bubbleOnTap = true)) +
                List(5) { PaintFarm.Plot(null) }).toMutableList(),
            seeds = 5))
        r.skill.run()
        assertTrue(r.world.taps.none { it.startsWith("plot 0,0") },
                   "tapped a plot with a bubble on its tap point: ${r.world.taps}")
        assertEquals(0, r.world.harvested())
        assertEquals(World.MAIN, r.world.screen)
    }

    @Test
    fun `a plot that turns out to be growing closes the popup and never presses Water`() {
        val r = Rig(World(
            plots = (listOf(PaintFarm.Plot("empty")) + List(5) { PaintFarm.Plot(null) })
                .toMutableList(),
            seeds = 5,
            secretlyGrowing = setOf(0 to 0)))
        r.skill.run()
        assertTrue(r.world.taps.none { it == "Water" },
                   "pressed Water: ${r.world.taps}")
        assertTrue(r.world.taps.any { it == "close water popup" },
                   "never closed the popup: ${r.world.taps}")
        assertEquals(0, r.world.planted())
        // The plot stayed empty and there are seeds on hand, which is the one
        // case the schedule calls a failure and comes back to quickly.
        assertEquals("empty plot with seeds on hand -- planting may have failed", r.reason)
        assertEquals(r.clock + FarmSkill.FALLBACK, r.due)
    }

    @Test
    fun `free seeds at zero end the visit without planting`() {
        val r = Rig(World(
            plots = (listOf(PaintFarm.Plot("empty"), PaintFarm.Plot("empty")) +
                List(4) { PaintFarm.Plot(null) }).toMutableList(),
            seeds = 0))
        r.skill.run()
        assertEquals(0, r.world.planted())
        assertTrue(r.world.taps.none { it.startsWith("plot") },
                   "tapped a plot with no seed to put in it: ${r.world.taps}")
        assertEquals("seed arrives", r.reason)
        assertEquals(r.clock + 300 + FarmSkill.MARGIN, r.due,
                     "the refill countdown on the header, plus the margin")
    }

    @Test
    fun `a screen it never opened gets no X tap on the way out`() {
        // Live, 2026-09-19: the player was in a minigame, go_to_field refused
        // it, and the way out then tapped an "X" on it twice.
        val r = Rig(World(plots = MutableList(6) { PaintFarm.Plot(null) },
                          screen = World.FOREIGN))
        val outcome = r.skill.run()
        assertEquals(Result.PARKED, outcome.result, outcome.toString())
        assertTrue(r.world.taps.isEmpty(), "tapped something: ${r.world.taps}")
        assertEquals(World.FOREIGN, r.world.screen, "the game is left where it stands")
        assertTrue(r.log.any { it.contains("not the plain main screen") }, r.log.toString())
    }

    @Test
    fun `the main switch ends the visit and the game stays where it is`() {
        // Changed on 2026-09-30 (PLAN_RELEASE_1_3.md B4, F24): the way home
        // ran whatever else had happened -- "the way home still runs" -- and
        // since 2026-09-22 the service held every one of its taps back. Now
        // it asks the switch first, taps nothing, and says so once; the visit
        // is one to go on with.
        val start = World(
            plots = (listOf(PaintFarm.Plot("ripe")) + List(5) { PaintFarm.Plot(null) })
                .toMutableList(),
            seeds = 5)
        val r = Rig(start)
        val before = r.world.screen
        r.on = false
        val outcome = r.skill.run()
        assertEquals(Result.STOPPED, outcome.result, outcome.toString())
        assertEquals(0, r.world.harvested(), "worked on after the switch went off")
        assertEquals(before, r.world.screen, "the game stays where it is")
        assertTrue(r.world.taps.isEmpty(), "tapped with the switch off: ${r.world.taps}")
        assertEquals(1, r.log.count { it == Stays.LINE }, r.log.toString())
        assertTrue(r.skill.carried)
    }

    /**
     * Two pauses in one visit (PLAN_RELEASE_1_3.md B4): the switch goes off
     * right after the first harvest's tap, and again right after the tap
     * that opens the seed menu. Each time the visit stops where it stands --
     * the second time with the menu open, the director's `field_dialog` --
     * and goes on from there when the switch is back: the menu closed as the
     * visit closes its own, the plots worked as the field shows them. Both
     * ripe plots harvested once, the three empty ones planted once, three
     * seeds spent, and the TODAY card's parts add up to that.
     */
    @Test
    fun `the Meat Field paused twice goes on where it stands and works each plot once`() {
        val r = Rig(World(
            plots = mutableListOf(
                PaintFarm.Plot("ripe"),
                PaintFarm.Plot("growing", timer = "00:50:00"),
                PaintFarm.Plot("empty"),
                PaintFarm.Plot(null),
                PaintFarm.Plot("growing", timer = "01:06:40"),
                PaintFarm.Plot("ripe")),
            seeds = 5))
        var held = 0
        var offAfter = 1
        r.cap.onTap = { _, _ ->
            if (!r.on) held += 1
            if (r.cap.taps.size == offAfter) r.on = false
        }
        val harvested = ArrayList<Int>()
        val planted = ArrayList<Int>()
        fun counted() {
            harvested += r.skill.lastCounts["harvested"] ?: 0
            planted += r.skill.lastCounts["planted"] ?: 0
        }

        val first = r.skill.work(r.cap.grab())
        assertEquals(Result.STOPPED, first.result, r.log.joinToString("\n"))
        assertEquals(1, r.world.harvested(), "the harvest whose tap went out is the visit's")
        counted()
        assertEquals(1, harvested.last(), "and it is booked before the stop")
        val field = r.cap.grab()
        assertEquals(Director.FIELD, Director.classify(field).screen)
        assertTrue(r.skill.resumesOn(Director.FIELD, field))

        // Back on: the second ripe plot, then the tap that opens the seed
        // menu for the first empty plot, and the switch off again.
        r.on = true
        offAfter = r.cap.taps.size + 2
        val second = r.skill.resume(field, whole = false)
        assertEquals(Result.STOPPED, second.result, r.log.joinToString("\n"))
        counted()
        val menu = r.cap.grab()
        assertEquals(Director.FIELD_DIALOG, Director.classify(menu).screen, "the menu stands where the pause found it")
        assertTrue(r.skill.resumesOn(Director.FIELD_DIALOG, menu))

        r.on = true
        offAfter = -1
        val third = r.skill.resume(menu, whole = false)
        val log = r.log.joinToString("\n")
        assertEquals(Result.DONE, third.result, log)
        counted()
        assertEquals(2, r.world.harvested(), "both ripe plots: ${r.world.taps}")
        assertEquals(3, r.world.planted(), "the emptied plots and the one that was empty: ${r.world.taps}")
        assertEquals(2, r.world.seeds, "three seeds, not more")
        assertEquals(2, harvested.sum(), "the TODAY card's harvests: $harvested")
        assertEquals(3, planted.sum(), "the TODAY card's plantings: $planted")
        assertEquals(0, held, "no tap with the switch off")
        assertEquals(World.FIELD, r.world.screen, "the semi-automatic work leaves the field standing")
        assertTrue(r.kept.none { it.startsWith("no_way") || it.startsWith("plant_no_menu") }, "${r.kept}")
        assertTrue(r.log.none { it.contains("opened nothing") || it.contains("could not get back") }, log)
        assertFalse(r.skill.carried)
        // Each part says what it did, the stopped ones too: the log's lines
        // add up to the card's numbers (live 2026-09-30 23:19:03, a resumed
        // part stopped again said nothing).
        val tallies = r.log.filter { it.startsWith("harvested ") }
        assertEquals(listOf("stopped", "stopped", "done"), tallies.map { it.substringAfterLast("-- ") }, log)
        assertEquals(harvested.sum(), tallies.sumOf { it.substringAfter("harvested ").substringBefore(",").toInt() }, log)
        assertEquals(planted.sum(), tallies.sumOf { it.substringAfter("planted ").substringBefore(",").toInt() }, log)
    }

    @Test
    fun `the whole walk goes main screen, Explore menu, field, and back`() {
        val r = Rig(World(
            plots = (listOf(PaintFarm.Plot("ripe")) + List(5) { PaintFarm.Plot(null) })
                .toMutableList(),
            seeds = 5,
            screen = World.MAIN))
        val outcome = r.skill.run()
        assertEquals(Result.DONE, outcome.result, outcome.toString())
        assertEquals(listOf("Explore tab", "Meat Field card"),
                     r.world.taps.take(2), "the way there: ${r.world.taps}")
        assertEquals(1, r.world.harvested())
        assertEquals(World.MAIN, r.world.screen)
        assertTrue(r.world.taps.count { it == "X" } == 1, "the X, once: ${r.world.taps}")
    }

    @Test
    fun `a main screen lit for a moment is waited out on the way in, two visits running`() {
        // K2 of PLAN_ABSCHLUSS_1_3.md: the way in asked the auto button on one
        // frame, and the hologram device's light over it ended the visit
        // before it began -- "not the plain main screen, cannot open the
        // Meat Field" (MainScreen).
        val r = Rig(World(
            plots = (listOf(PaintFarm.Plot("ripe")) + List(5) { PaintFarm.Plot(null) })
                .toMutableList(),
            seeds = 5,
            screen = World.MAIN))
        for (visit in 1..2) {
            r.world.lit = visit
            val before = r.world.taps.size
            val outcome = r.skill.run()
            assertEquals(Result.DONE, outcome.result, "visit $visit: $outcome\n${r.log.joinToString("\n")}")
            assertEquals(listOf("Explore tab", "Meat Field card"), r.world.taps.drop(before).take(2),
                         "visit $visit, the way there: ${r.world.taps}")
            assertEquals(World.MAIN, r.world.screen)
            assertEquals(0, r.world.lit, "visit $visit looked at every lit frame")
        }
        assertEquals(2, r.log.count { it.startsWith("  the plain main screen after ") }, r.log.toString())
        assertTrue(r.log.none { it.contains("cannot open") }, r.log.toString())
        assertTrue(r.kept.none { it == "not_main_screen" }, r.kept.toString())
    }

    // ------------------------------------------------------------------------
    // The seam
    // ------------------------------------------------------------------------
    @Test
    fun `work leaves the field standing and run brings it home`() {
        val r = Rig(World(
            plots = (listOf(PaintFarm.Plot("ripe")) + List(5) { PaintFarm.Plot(null) })
                .toMutableList(),
            seeds = 5))
        val img = r.cap.grab()
        val outcome = r.skill.work(img)
        img.release()

        assertEquals(Result.DONE, outcome.result)
        assertEquals(1, r.world.harvested())
        assertEquals(World.FIELD, r.world.screen, "work has to leave the player where he was")
        assertTrue(r.world.taps.none { it == "X" }, "work went home: ${r.world.taps}")
        assertNotNull(r.due, "the clock runs after every visit, work or run")
    }

    @Test
    fun `the director gives a still field to this skill and nothing else does`() {
        assertTrue(FarmSkill(DirectorTest.FakeCapture(), { null }, { _, _, _ -> })
                       .worksOn(Director.FIELD))
        for (screen in Director.SCREENS.filter { it != Director.FIELD }) {
            assertTrue(!FarmSkill(DirectorTest.FakeCapture(), { null }, { _, _, _ -> })
                           .worksOn(screen), "claimed $screen")
        }

        // Something to do on it: the director asks the frame before it asks
        // anything else now, and a field with nothing ripe and nothing empty
        // is watched rather than worked (the test below).
        val r = Rig(World(
            plots = (listOf(PaintFarm.Plot("ripe")) + List(5) { PaintFarm.Plot(null) })
                .toMutableList()))
        var t = 100.0
        val director = DirectorLoop(
            r.cap, listOf(r.skill), emptyList(), { GAME }, { SkillSettings.MODE_SEMI },
            Chain(emptyList(), log = {}), log = { r.log += it }, now = { t })
        // The player's three seconds first: the field is his until it has
        // stood still for them.
        var tick = director.tick()
        assertEquals(Director.FIELD, tick.screen)
        assertNull(tick.did, tick.toString())
        var guard = 0
        while (tick.did == null && guard++ < 10) {
            t += tick.beat
            tick = director.tick()
        }
        assertEquals("work farm", tick.did, tick.toString())
        assertTrue(r.log.any { it == "giving the field to Meat Field" }, r.log.toString())
        director.close()
    }

    // ------------------------------------------------------------------------
    // The field the player is standing on, scanned every round (Skill.seesWork)
    // ------------------------------------------------------------------------
    /** The director over [Rig], semi-automatic, with a clock the test moves. */
    private class Scan(val r: Rig) {
        var t = 100.0
        val director = DirectorLoop(
            r.cap, listOf(r.skill), emptyList(), { GAME }, { SkillSettings.MODE_SEMI },
            Chain(emptyList(), log = {}), log = { r.log += it }, now = { t })

        /** [rounds] rounds, the clock moved by whatever beat each asked for. */
        fun rounds(rounds: Int) { repeat(rounds) { t += director.tick().beat } }
    }

    @Test
    fun `a field with nothing ripe and nothing empty is watched, not worked`() {
        // Everything growing and the clock long due: there is still nothing
        // to harvest and nothing to plant, and the reader says so off the
        // frame before the schedule is ever consulted.
        val r = Rig(World(
            plots = MutableList(6) { PaintFarm.Plot("growing", timer = "02:00:00") },
            seeds = 5))
        val scan = Scan(r)
        assertTrue(r.skill.hasBudget(), "nothing recorded yet counts as due")
        scan.rounds(12)
        assertEquals(0, r.world.taps.size, "tapped a field with nothing to do: ${r.world.taps}")
        assertTrue(r.log.any { it.contains("nothing to do on the field just now") },
                   r.log.toString())
        // And it is still looking: the round is the scan.
        val grabs = r.world.grabs
        scan.rounds(4)
        assertTrue(r.world.grabs > grabs, "stopped looking at the field")
        scan.director.close()
    }

    @Test
    fun `a plot the player frees by hand is planted, whatever the clock says`() {
        // The whole point: the player stands in the field, the app has
        // already worked it once and booked its next visit for an hour from
        // now, and then the player harvests a plot himself.
        val r = Rig(World(
            plots = (listOf(PaintFarm.Plot(null)) +
                     List(5) { PaintFarm.Plot("growing", timer = "02:00:00") }).toMutableList(),
            seeds = 5))
        r.due = r.clock + 3600
        val scan = Scan(r)
        scan.rounds(12)
        assertEquals(0, r.world.taps.size, "tapped before the player did anything")

        // The plot falls free under the app.
        r.world.plots[0] = PaintFarm.Plot("empty")
        scan.rounds(40)
        assertEquals(1, r.world.planted(), "the freed plot was not planted: ${r.world.taps}")
        assertEquals(4, r.world.seeds, "a seed was spent")
        scan.director.close()
    }

    @Test
    fun `the field is worked again for as long as the player keeps changing it`() {
        val r = Rig(World(
            plots = MutableList(6) { PaintFarm.Plot("growing", timer = "02:00:00") },
            seeds = 5))
        r.due = r.clock + 3600
        val scan = Scan(r)
        var seeds = 5
        for (cell in listOf(0, 3, 5)) {
            r.world.plots[cell] = PaintFarm.Plot("ripe")
            scan.rounds(40)
            assertTrue(r.world.taps.contains("plot ${cell % 2},${cell / 2}"),
                       "the ripe plot at $cell was left standing: ${r.world.taps}")
            assertEquals("growing", r.world.plots[cell].state,
                         "harvested and not planted again: ${r.world.taps}")
            seeds -= 1
            assertEquals(seeds, r.world.seeds, "a seed per plot: ${r.world.taps}")
        }
        scan.director.close()
    }

    @Test
    fun `the frame question is the field's alone, and a stopped visit shuts nothing`() {
        val r = Rig(World(
            plots = (listOf(PaintFarm.Plot("empty")) +
                     List(5) { PaintFarm.Plot("growing", timer = "02:00:00") }).toMutableList(),
            seeds = 5))
        val img = r.cap.grab()
        try {
            // Off the field it answers nothing at all, and the director's two
            // old rules hold for this skill exactly as for every other.
            for (screen in Director.SCREENS.filter { it != Director.FIELD }) {
                assertNull(r.skill.seesWork(screen, img), "answered for $screen")
            }
            assertEquals(true, r.skill.seesWork(Director.FIELD, img))

            // A visit the main switch ends gets nowhere, and that is not
            // evidence about the field: the brake stays off.
            r.on = false
            assertEquals(Result.STOPPED, r.skill.work(img).result)
            r.on = true
            assertEquals(true, r.skill.seesWork(Director.FIELD, img),
                         "a visit the switch ended shut the field")
        } finally {
            img.release()
        }
    }

    @Test
    fun `a plot that will not plant is not tapped round after round`() {
        // The game says the plot is still growing however its badge is drawn
        // (PLAN_MEAT_FIELD 2.5). The first visit finds that out; nothing on
        // the field has changed since, so there is no second one until the
        // schedule brings the app back.
        val r = Rig(World(
            plots = (listOf(PaintFarm.Plot("empty")) +
                     List(5) { PaintFarm.Plot("growing", timer = "02:00:00") }).toMutableList(),
            seeds = 5,
            secretlyGrowing = setOf(0 to 0)))
        val scan = Scan(r)
        scan.rounds(40)
        assertEquals(0, r.world.planted(), "planted a plot the game refused")
        val taps = r.world.taps.size
        assertTrue(taps > 0, "never even tried")
        scan.rounds(40)
        assertEquals(taps, r.world.taps.size,
                     "tapped the same refusing plot again: ${r.world.taps}")
        scan.director.close()
    }

    // ------------------------------------------------------------------------
    // Watering, better seeds, the field's ads (PLAN_MEAT_FIELD_GIESSEN.md 6.8)
    // ------------------------------------------------------------------------
    /** Nothing that must never be done was done, and every tap landed on something named. */
    private fun assertClean(r: Rig) {
        assertTrue(r.world.forbidden.isEmpty(), "forbidden: ${r.world.forbidden} -- taps ${r.world.taps}")
        assertTrue(r.world.taps.none { it.startsWith("??") }, "a tap on nothing known: ${r.world.taps}")
        assertTrue(r.world.taps.none { it == "huge can" || it == "key" }, r.world.taps.toString())
    }

    private fun growing(t: String) = PaintFarm.Plot("growing", timer = t)

    @Test
    fun `the painted watering frames read as the field they are painted as`() {
        val plots = List(6) { growing("01:00:00") }
        val field = PaintFarm.field(plots, seeds = "11", refill = "05:00", cans = "14", canRefill = "50:00")
        assertEquals(Director.FIELD, Director.classify(field).screen)
        assertEquals(14, Farm.waterCans(field))
        assertEquals(3000, Farm.canRefill(field))
        assertEquals(11, Farm.freeSeeds(field), "the can's row is not the sack's")
        assertEquals(300, Farm.seedRefill(field), "nor its countdown the seed's")
        field.release()

        val popup = PaintFarm.field(plots, popup = true, popupTime = "01:30:00")
        assertEquals(5400, Farm.popupTimer(popup))
        assertNotNull(Farm.waterButton(popup))
        assertNull(Farm.boostPopup(popup), "the water popup is not the can dialog")
        popup.release()

        val boost = PaintFarm.field(plots, boost = PaintFarm.Boost("14", "2"))
        val b = Farm.boostPopup(boost)
        assertNotNull(b)
        assertEquals(14, Farm.boostCans(boost))
        assertEquals(2, Farm.boostAmount(boost))
        assertNull(Farm.adDialog(boost), "cans in the dialog: no ad")
        assertNull(Farm.waterPopup(boost), "the can dialog's Water is not the popup's")
        assertNull(Farm.seedMenu(boost).first, "two slots are not the seed menu")
        assertEquals(Director.FIELD_DIALOG, Director.classify(boost).screen)
        assertTrue(abs(b!!.huge.fx - PaintFarm.HUGE_FX) < 0.01, "the Huge can is where it is painted: $b")
        boost.release()

        val canAd = PaintFarm.field(plots, boost = PaintFarm.Boost("2/2", "0"))
        assertEquals(Farm.AdOffer(FarmSkill.CANS, 2, Farm.boostPopup(canAd)!!.button), Farm.adDialog(canAd))
        assertNull(Farm.boostCans(canAd))
        canAd.release()

        val menu = PaintFarm.field(plots, menu = 1, slotCounts = listOf("0", "45", "25"))
        val slots = Farm.seedSlots(menu)
        assertEquals(listOf(0, 45, 25), slots.map { Farm.seedSlotCount(menu, it) })
        assertNotNull(Farm.seedMenu(menu).first)
        menu.release()

        val seedAd = PaintFarm.field(plots, menu = 0, slotCounts = listOf("0", "45", "25"), seedAd = "1/2")
        assertEquals(FarmSkill.SEEDS, Farm.adDialog(seedAd)?.kind)
        assertEquals(1, Farm.adDialog(seedAd)?.left)
        assertNull(Farm.seedMenu(seedAd).first, "no green Select: not the menu Farm.seedMenu knows")
        assertEquals(Director.FIELD_DIALOG, Director.classify(seedAd).screen)
        seedAd.release()

        val sheet = PaintFarm.field(plots, sheet = true)
        assertTrue(Dungeon.rewardSheet(sheet))
        sheet.release()
    }

    @Test
    fun `cans are poured on the plot that ripens first, and what ripens is harvested and planted again`() {
        val r = Rig(World(
            plots = mutableListOf(growing("01:00:00"), growing("00:30:00"),
                                  growing("02:00:00"), PaintFarm.Plot(null),
                                  PaintFarm.Plot(null), PaintFarm.Plot(null)),
            seeds = 5, cans = 5))
        val outcome = r.skill.run()

        assertEquals(Result.DONE, outcome.result, outcome.toString())
        assertClean(r)
        // 00:30:00 first (one can, ripe), then 01:00:00 (two, ripe); both
        // harvested and planted again. What is left is three full 02:00:00
        // plots, four cans each, against the two in hand: the rule of 13
        // pours no can that does not ripen its plot, so the two stay.
        assertEquals(3, r.world.watered, r.world.taps.toString())
        assertEquals(2, r.world.cans)
        assertEquals(3, r.skill.lastVisit!!.watered, r.log.toString())
        assertEquals(2, r.skill.lastVisit!!.harvested)
        assertEquals(2, r.skill.lastVisit!!.planted)
        assertEquals(3, r.world.seeds)
        val order = r.log.filter { it.startsWith("watering plot") && it.contains("->") }
        assertEquals(listOf("watering plot 1,0: 00:30:00 -> ripe, cans 5 -> 4",
                            "watering plot 0,0: 01:00:00 -> ripe, cans 4 -> 2"),
                     order, r.log.toString())
        assertTrue(r.log.any { it == "watering plot 0,0: needs 4 cans, 2 in stock -- left to grow" },
                   r.log.toString())
        assertEquals(2, r.world.taps.count { it == "boost Water" }, r.world.taps.toString())
        assertEquals(7200, r.growSeconds)
        assertTrue(r.log.none { it == "cans used up -- watering done" }, r.log.toString())
        assertTrue(r.log.any { it == "harvested 2, planted 2, watered 3, ads 0 -- done" }, r.log.toString())
        // The clock, row 10: a can in 05:00 makes three, and three cans
        // ripen a plot at 01:30:00 -- half an hour from the last look.
        assertEquals("enough cans for a plot left to grow", r.reason)
        assertTrue(r.due!! <= r.clock + 1800 + FarmSkill.MARGIN && r.due!! > r.clock + 1500,
                   "due ${r.due!! - r.clock} s from the end")
        // Watering asked for the dot off the header's can row and put it back.
        assertEquals("back", r.cap.overlay.last(), r.cap.overlay.toString())
        assertTrue(r.cap.overlay.first().startsWith("clear 0.108-0.16"), r.cap.overlay.toString())
        assertEquals(mapOf("harvested" to 2, "planted" to 2, "watered" to 3, "ads" to 0), r.skill.lastCounts)
        assertEquals(World.MAIN, r.world.screen)
    }

    // ---- the threshold of PLAN_MEAT_FIELD_GIESSEN.md 13 (the player's, 2026-09-28) ----
    private fun onePlot(time: String, cans: Int) = World(
        plots = (listOf(growing(time)) + List(5) { PaintFarm.Plot(null) }).toMutableList(),
        seeds = 0, cans = cans)

    @Test
    fun `50 minutes and two cans are watered at once with both`() {
        val r = Rig(onePlot("00:50:00", 2))
        r.skill.run()
        assertClean(r)
        assertEquals(1, r.world.taps.count { it == "boost Water" }, r.world.taps.toString())
        assertEquals(2, r.world.watered)
        assertEquals(0, r.world.cans)
        assertTrue(r.log.any { it == "watering plot 0,0: 00:50:00 -> ripe, cans 2 -> 0" }, r.log.toString())
    }

    @Test
    fun `100 minutes and three cans are left to grow, nothing tapped but the way out`() {
        val r = Rig(onePlot("01:40:00", 3))
        r.skill.run()
        assertClean(r)
        assertEquals(0, r.world.watered)
        assertEquals(3, r.world.cans)
        assertTrue(r.world.taps.none { it.startsWith("plot") || it == "Water" || it == "boost Water" },
                   r.world.taps.toString())
        assertEquals(listOf("X", "globe"), r.world.taps, "only the way home")
        assertTrue(r.log.any { it == "watering plot 0,0: needs 4 cans, 3 in stock -- left to grow" },
                   r.log.toString())
        // Back when it can be ripened: the can in 05:00 makes four, before
        // the plot shrinks to three cans' worth ten minutes on.
        assertEquals("enough cans for a plot left to grow", r.reason)
        assertEquals(r.clock + 300 + FarmSkill.MARGIN, r.due)
    }

    @Test
    fun `a box the cans cap under the plot's need is closed without Water`() {
        // The header reads four, the bag holds three: the plot, 01:40:00,
        // needs four, and the can dialog opens at three -- the cap, not the
        // need. Closed, nothing poured, the proof never asked for.
        val r = Rig(onePlot("01:40:00", 3))
        r.world.headerCans = "4"
        r.skill.run()
        assertClean(r)
        assertEquals(1, r.world.taps.count { it == "Water" }, r.world.taps.toString())
        assertTrue(r.world.taps.none { it == "boost Water" }, r.world.taps.toString())
        assertTrue(r.world.taps.contains("outside"), r.world.taps.toString())
        assertEquals(0, r.world.watered)
        assertTrue(r.log.any { it == "watering plot 0,0: needs 4 cans, 3 in stock -- left to grow" },
                   r.log.toString())
    }

    @Test
    fun `a plot at or under the threshold is left to grow, and at threshold 0 it is watered`() {
        val under = Rig(onePlot("00:15:00", 3))
        under.skill.run()
        assertClean(under)
        assertEquals(0, under.world.watered)
        assertTrue(under.world.taps.none { it.startsWith("plot") || it == "Water" }, under.world.taps.toString())
        assertTrue(under.log.any { it == "watering plot 0,0: 00:15:00 left, not over 20 min -- left to grow" },
                   under.log.toString())
        // Exactly the threshold is not over it.
        val at = Rig(onePlot("00:20:00", 3))
        at.skill.run()
        assertEquals(0, at.world.watered, at.log.toString())

        val zero = Rig(onePlot("00:15:00", 3))
        zero.settings = FarmSkill.Settings(waterMinMinutes = 0)
        zero.skill.run()
        assertClean(zero)
        assertEquals(1, zero.world.watered, zero.log.toString())
        assertTrue(zero.log.any { it == "watering plot 0,0: 00:15:00 -> ripe, cans 3 -> 2" }, zero.log.toString())

        // And a threshold of 60 leaves a 50-minute plot alone.
        val sixty = Rig(onePlot("00:50:00", 3))
        sixty.settings = FarmSkill.Settings(waterMinMinutes = 60)
        sixty.skill.run()
        assertEquals(0, sixty.world.watered, sixty.log.toString())
    }

    @Test
    fun `a time nobody can read is no can`() {
        // No plate and no badge: the plot does not read as growing, and is
        // not even tapped, whatever the game knows its time to be.
        val blind = Rig(World(plots = (listOf(PaintFarm.Plot("growing")) + List(5) { PaintFarm.Plot(null) })
                                  .toMutableList(), seeds = 0, cans = 3))
        blind.world.remaining[0 to 0] = 3000
        blind.skill.run()
        assertClean(blind)
        assertEquals(0, blind.world.watered)
        assertTrue(blind.world.taps.none { it.startsWith("plot") }, blind.world.taps.toString())

        // The popup's time blank: the plate is the time (13, "sonst aus dem
        // Schild") and the rule holds on it -- 00:50:00, two cans.
        val plate = Rig(onePlot("00:50:00", 3))
        plate.world.popupTimeBlank = true
        plate.skill.run()
        assertClean(plate)
        assertEquals(2, plate.world.watered, plate.log.toString())
        // ... and on it the plot under the threshold stays unwatered.
        val plateUnder = Rig(onePlot("00:15:00", 3))
        plateUnder.world.popupTimeBlank = true
        plateUnder.skill.run()
        assertEquals(0, plateUnder.world.watered, plateUnder.log.toString())
    }

    @Test
    fun `where the shorter plot turns out too dear, the longer one the cans cover is watered`() {
        // The need grows with the time, so on one reading the shorter plot is
        // never the dearer one: the case is a plate and a popup that
        // disagree. Plot 0,0's plate says 00:50:00 (two cans), its popup
        // 01:40:00 (four); plot 1,0 is 01:10:00 on both (three). Three cans:
        // 0,0 is opened first, its popup refuses it, and 1,0 is watered.
        val r = Rig(World(
            plots = (listOf(growing("00:50:00"), growing("01:10:00")) + List(4) { PaintFarm.Plot(null) })
                .toMutableList(), seeds = 0, cans = 3))
        r.world.remaining[0 to 0] = 6000
        r.skill.run()
        assertClean(r)
        assertEquals(3, r.world.watered, r.log.toString())
        assertTrue(r.log.any { it == "watering plot 0,0: needs 4 cans, 3 in stock -- left to grow" }, r.log.toString())
        assertTrue(r.log.any { it == "watering plot 1,0: 01:10:00 -> ripe, cans 3 -> 0" }, r.log.toString())
        assertEquals(1, r.world.taps.count { it == "boost Water" }, r.world.taps.toString())
        val taps = r.world.taps
        assertTrue(taps.indexOf("plot 0,0") < taps.indexOf("plot 1,0"), "the shorter plate first: $taps")
        // Nothing but the close on 0,0's popup: no Water there.
        assertEquals("close water popup", taps[taps.indexOf("plot 0,0") + 1], taps.toString())
    }

    @Test
    fun `with the free seeds out the switch takes Good, then Great, and the free seed as soon as one is back`() {
        fun world() = World(
            plots = (List(4) { PaintFarm.Plot("empty") } + List(2) { PaintFarm.Plot(null) }).toMutableList(),
            seeds = 0, good = 1, great = 5)

        val on = Rig(world())
        on.settings = FarmSkill.Settings(betterSeeds = true)
        // A free seed arrives while the Good one goes in (one an hour).
        var came = false
        on.world.onSelect = { if (goodPlanted == 1 && !came) { came = true; seeds = 1 } }
        on.skill.run()
        assertClean(on)
        assertEquals(4, on.world.planted(), on.world.taps.toString())
        assertEquals(1, on.world.goodPlanted)
        assertEquals(2, on.world.greatPlanted, "Good at 0: Great, and only while the free one is out")
        assertEquals(0, on.world.seeds, "the free seed that came back went in first")
        assertEquals(listOf("free seeds at 0 -- planting Good seeds (1 left)",
                            "free seeds are back (1) -- the free seed again",
                            "free seeds at 0 -- planting Great seeds (5 left)"),
                     on.log.filter { it.startsWith("free seeds") }, on.log.toString())
        // Only a free seed teaches the grow duration: the one planted here.
        assertEquals(7200, on.growSeconds)

        // The switch off: as before 2026-09-28, no plot is even tapped.
        val off = Rig(world())
        off.skill.run()
        assertClean(off)
        assertEquals(0, off.world.planted())
        assertTrue(off.world.taps.none { it.startsWith("plot") || it.startsWith("seed slot") }, off.world.taps.toString())
    }

    @Test
    fun `a sack at 0 on one witness buys no seed`() {
        // The header reads 0, the free slot says 1: the slot is believed,
        // and the free seed goes in.
        val slotSays = Rig(World(plots = (listOf(PaintFarm.Plot("empty")) + List(5) { PaintFarm.Plot(null) })
                                     .toMutableList(), seeds = 1, good = 5, great = 5))
        slotSays.world.headerSeeds = "0"
        slotSays.settings = FarmSkill.Settings(betterSeeds = true)
        slotSays.skill.run()
        assertClean(slotSays)
        assertEquals(1, slotSays.world.planted())
        assertEquals(0, slotSays.world.goodPlanted + slotSays.world.greatPlanted, slotSays.world.taps.toString())
        assertEquals(0, slotSays.world.seeds)

        // The header reads 0 and the slots read nothing, but with the free
        // slot chosen the game offers its seeds' ad, which it does only at 0:
        // two witnesses since 2026-10-03 (PLAN_ABSCHLUSS_1_3.md K10). No
        // count of the Good bag reads either, so no Good seed: the bag that
        // does not read is not tapped, and nothing is bought.
        val blind = Rig(World(plots = (listOf(PaintFarm.Plot("empty")) + List(5) { PaintFarm.Plot(null) })
                                  .toMutableList(), seeds = 0, good = 5, great = 5))
        blind.world.slotNumbers = false
        blind.world.seedAdsLeft = 0
        blind.settings = FarmSkill.Settings(betterSeeds = true)
        blind.skill.run()
        assertClean(blind)
        assertEquals(0, blind.world.planted(), blind.world.taps.toString())
        assertTrue(blind.world.taps.none { it == "Select" }, blind.world.taps.toString())
        assertTrue(blind.log.none { it.contains("one witness only") }, blind.log.toString())
        assertTrue(blind.log.any { it.contains("the Good seeds' count did not read") }, blind.log.toString())

        // The header does not read, the slot says 0 and the ad stands: the
        // header's 0 is the witness nothing replaces, so nothing is bought.
        val noHeader = Rig(World(plots = (listOf(PaintFarm.Plot("empty")) + List(5) { PaintFarm.Plot(null) })
                                     .toMutableList(), seeds = 0, good = 5, great = 5))
        noHeader.world.headerSeeds = ""
        noHeader.world.seedAdsLeft = 0
        noHeader.settings = FarmSkill.Settings(betterSeeds = true)
        noHeader.skill.run()
        assertClean(noHeader)
        assertEquals(0, noHeader.world.planted(), noHeader.world.taps.toString())
        assertTrue(noHeader.world.taps.none { it == "Select" }, noHeader.world.taps.toString())
        assertTrue(noHeader.log.any { it.contains("one witness only") }, noHeader.log.toString())
    }

    /**
     * PLAN_ABSCHLUSS_1_3.md K10, LDPlayer at 900 x 1600 on 2026-10-02 and
     * 2026-10-03: the header read 0, the 0 under the free slot read 4, the
     * menu stood with the violet "Ad (0/2)" in Select's place -- and the
     * switch planted nothing, "the free seeds read 0 on one witness only".
     * The ad is the game's own word that the free seeds are out, and beside
     * the header it is the second witness; over two visits, so that nothing
     * the first one learnt holds the second back.
     */
    @Test
    fun `the header at 0 and the game's seed ad plant the bought seed where the free slot's number misreads`() {
        val r = Rig(World(plots = (List(5) { PaintFarm.Plot("empty") } + listOf(growing("01:00:00")))
                              .toMutableList(), seeds = 0, good = 20, great = 5))
        r.world.freeSlotShows = "4"
        r.world.seedAdsLeft = 0
        r.settings = FarmSkill.Settings(betterSeeds = true)
        for (visit in 1..2) {
            r.skill.run()
            assertClean(r)
            assertTrue(r.log.none { it.contains("one witness only") }, r.log.toString())
            assertEquals(5 * visit, r.world.goodPlanted, r.world.taps.toString())
            assertEquals(0, r.world.greatPlanted)
            assertEquals(20 - 5 * visit, r.world.good)
            // K6: a line a plot, the bag and its count.
            assertEquals(5, r.log.count { it.matches(Regex(" +plot \\d,\\d: Good seed, \\d+ before")) } - 5 * (visit - 1),
                         r.log.toString())
            // The five ripe, harvested by hand, and empty for the next visit.
            for (i in 0 until 5) {
                r.world.plots[i] = PaintFarm.Plot("empty")
                r.world.remaining.remove(i % 2 to i / 2)
            }
            r.due = null
        }
        assertTrue(r.world.taps.none { it == "seed ad" }, "the day's two are gone: ${r.world.taps}")
        assertTrue(r.log.any { it.startsWith("free seeds at 0 -- planting Good seeds") }, r.log.toString())
    }

    @Test
    fun `no bought seed is taken while its count reads 0, and none is left for the next plot`() {
        val r = Rig(World(plots = (List(3) { PaintFarm.Plot("empty") } + List(3) { PaintFarm.Plot(null) })
                              .toMutableList(), seeds = 0, good = 0, great = 0))
        r.settings = FarmSkill.Settings(betterSeeds = true)
        r.skill.run()
        assertClean(r)
        assertEquals(0, r.world.planted())
        assertTrue(r.world.taps.none { it == "Select" || it == "seed slot 1" || it == "seed slot 2" },
                   r.world.taps.toString())
        // One menu opened, read, and shut: not one per empty plot.
        assertEquals(1, r.world.taps.count { it.startsWith("plot") }, r.world.taps.toString())
        assertTrue(r.log.any { it.contains("Good and Great seeds are out too") }, r.log.toString())
    }

    /**
     * An empty plot and a growing one, no seed, no can: what the two ads
     * are for. The growing plot takes two cans of the twenty the ad brings,
     * so the counter goes 20, 18, 14, 10 -- a painted 9 reads as 4
     * (PaintFarm), and none is drawn.
     */
    private fun adWorld() = World(
        plots = (listOf(PaintFarm.Plot("empty"), growing("01:00:00")) + List(4) { PaintFarm.Plot(null) })
            .toMutableList(),
        seeds = 0, cans = 0).also {
        it.seedAdsLeft = 1
        it.canAdsLeft = 1
    }

    @Test
    fun `with the pass the seeds' ad and the cans' ad each give their reward, once a day each`() {
        val r = Rig(adWorld())
        r.settings = FarmSkill.Settings(ads = true)
        val today = 20_000L
        val card = MapSettings()
        r.skill.run()
        SkillStats.add(card, "farm", r.skill.lastCounts, today)
        assertClean(r)
        assertEquals(1, r.world.taps.count { it == "seed ad" }, r.world.taps.toString())
        assertEquals(1, r.world.taps.count { it == "View Ads" }, r.world.taps.toString())
        assertEquals(2, r.skill.lastVisit!!.ads)
        assertEquals(mapOf(FarmSkill.SEEDS to 2, FarmSkill.CANS to 2), r.adsUsed.toMap(),
                     "the game said 1/2 left: the file holds 2 of 2 after the one")
        assertTrue(r.log.any { it == "ads: seeds 2/2 collected, sack 0 -> 3" }, r.log.toString())
        assertTrue(r.log.any { it == "ads: cans 2/2 collected, cans 0 -> 20" }, r.log.toString())
        assertTrue(r.world.watered > 0 && r.skill.lastVisit!!.watered == r.world.watered,
                   "the cans the ad brought were poured in the same visit: ${r.log}")
        assertTrue(r.world.taps.count { it == "close sheet" } >= 2, r.world.taps.toString())

        // The same day again: the game's own 0/2 is read, and nothing is tapped for them.
        r.world.seeds = 0
        r.world.cans = 0
        r.world.plots[0] = PaintFarm.Plot("empty")
        r.world.plots[1] = growing("01:00:00")
        r.world.remaining[1 to 0] = 3600
        val before = r.world.taps.size
        r.skill.run()
        SkillStats.add(card, "farm", r.skill.lastCounts, today)
        val second = r.world.taps.drop(before)
        assertTrue(second.none { it == "seed ad" || it == "View Ads" }, second.toString())
        assertEquals(0, r.skill.lastVisit!!.ads)
        assertTrue(r.log.any { it == "ads: cans 2/2 already today" }, r.log.toString())
        assertClean(r)

        // A file that says both are spent against a game that offers one
        // more (the Poco, 2026-10-01): the game's count is taken.
        r.world.seeds = 0
        r.world.cans = 0
        r.world.seedAdsLeft = 1
        r.world.canAdsLeft = 1
        r.world.plots[0] = PaintFarm.Plot("empty")
        r.world.plots[1] = growing("01:00:00")
        r.world.remaining[1 to 0] = 3600
        val stale = r.world.taps.size
        r.skill.run()
        SkillStats.add(card, "farm", r.skill.lastCounts, today)
        assertEquals(1, r.world.taps.drop(stale).count { it == "seed ad" }, r.world.taps.toString())
        assertEquals(1, r.world.taps.drop(stale).count { it == "View Ads" }, r.world.taps.toString())
        assertTrue(r.log.any { it == "ads: the file had cans at 2/2, the game offers 1/2 -- the game's count is taken" },
                   r.log.toString())
        assertClean(r)

        // After the reset: again.
        r.newDay()
        r.world.seedAdsLeft = 1
        r.world.canAdsLeft = 1
        val third = r.world.taps.size
        r.skill.run()
        SkillStats.add(card, "farm", r.skill.lastCounts, today)
        assertEquals(1, r.world.taps.drop(third).count { it == "seed ad" })
        assertEquals(1, r.world.taps.drop(third).count { it == "View Ads" })
        assertEquals(2, r.skill.lastVisit!!.ads)
        assertClean(r)

        // The card: four passes added, the second one with no ad, nothing twice.
        assertEquals(6, SkillStats.read(card, "farm", today)["ads"], SkillStats.read(card, "farm", today).toString())
    }

    @Test
    fun `a reward that comes seconds late under the sheet is the reward, and the dialog under it is shut`() {
        // The Poco, 2026-10-01 08:06:34: with the pass, the Reward sheet over
        // the can dialog stood 11 s after the tap, the proof had stopped
        // looking, and the visit wrote both cans' ads off with 20 cans in hand.
        val r = Rig(adWorld())
        r.world.canAdsLeft = 2
        r.world.lateFrames = 8
        r.settings = FarmSkill.Settings(ads = true)
        r.skill.run()
        assertClean(r)
        assertTrue(r.log.any { it == "ads: cans 1/2 collected, cans 0 -> 20" }, r.log.toString())
        assertEquals(1, r.adsUsed[FarmSkill.CANS])
        assertTrue(r.world.watered > 0, r.log.toString())
        assertTrue(r.kept.none { it.startsWith("dialog_not_closed") || it.startsWith("ad_no_reward") }, r.kept.toString())
    }

    @Test
    fun `with the cans' ad still open the last cans go on the shortest plot, and the ad brings more`() {
        // The Poco, 2026-10-01 20:14:41: one can, every plot dearer, the ad
        // on offer -- and the field stood until the player took it by hand.
        // (No count with a 9 in it: a painted 9 reads as 4, PaintFarm.)
        val r = Rig(World(
            plots = (listOf(growing("01:20:00"), growing("01:40:00")) + List(4) { PaintFarm.Plot(null) })
                .toMutableList(), seeds = 0, cans = 1))
        r.world.canAdsLeft = 2
        r.world.seedAdsLeft = 0             // no seed comes back: the harvests stay empty
        r.settings = FarmSkill.Settings(ads = true)
        r.skill.run()
        assertClean(r)
        assertTrue(r.log.any { it == "watering plot 0,0: needs 3 cans, 1 in stock -- pouring them to open the cans' ad" },
                   r.log.toString())
        assertTrue(r.log.any { it == "watering plot 0,0: 01:20:00 -> 00:50:00, cans 1 -> 0" }, r.log.toString())
        assertTrue(r.log.any { it == "ads: cans 1/2 collected, cans 0 -> 20" }, r.log.toString())
        // Then both ripened: 0,0 on two cans, 1,0 on four.
        assertTrue(r.log.any { it == "watering plot 0,0: 00:50:00 -> ripe, cans 20 -> 18" }, r.log.toString())
        assertTrue(r.log.any { it == "watering plot 1,0: 01:40:00 -> ripe, cans 18 -> 14" }, r.log.toString())
        assertEquals(14, r.world.cans)

        // Without the ad on the page, the rule of 13 holds: nothing poured.
        val off = Rig(World(
            plots = (listOf(growing("01:00:00")) + List(5) { PaintFarm.Plot(null) }).toMutableList(),
            seeds = 0, cans = 1))
        off.skill.run()
        assertClean(off)
        assertEquals(0, off.world.watered, off.log.toString())
        // Nor with the day's cans' ads spent.
        val spent = Rig(World(
            plots = (listOf(growing("01:00:00")) + List(5) { PaintFarm.Plot(null) }).toMutableList(),
            seeds = 0, cans = 1))
        spent.settings = FarmSkill.Settings(ads = true)
        spent.adsUsed[FarmSkill.CANS] = 2
        spent.skill.run()
        assertEquals(0, spent.world.watered, spent.log.toString())
    }

    @Test
    fun `the order is the times the scan read, also where a plate stops reading`() {
        // Two plates read at the start, then none: the shorter one first, and
        // the other from what the scan kept, its popup the second witness.
        val r = Rig(World(
            plots = (listOf(growing("01:30:00"), growing("00:50:00")) + List(4) { PaintFarm.Plot(null) })
                .toMutableList(), seeds = 0, cans = 5))
        r.cap.onTap = { _, _ -> r.world.hidePlates = true }
        r.skill.run()
        assertClean(r)
        assertTrue(r.log.any { it == "watering: the times, shortest first -- 1,0 00:50:00, 0,0 01:30:00" },
                   r.log.toString())
        val order = r.log.filter { it.startsWith("watering plot") && it.contains("->") }
        assertEquals(listOf("watering plot 1,0: 00:50:00 -> ripe, cans 5 -> 3",
                            "watering plot 0,0: 01:30:00 -> ripe, cans 3 -> 0"), order, r.log.toString())
    }

    /** The file a phone can carry into 1.3: the Meat Field pick on, the pass as [pass], and `ad_watch` on, which nothing reads. */
    private fun adFile(pass: Boolean) = MapSettings(mapOf(
        SkillSettings.AD_PASS_KEY to pass, "ad_watch" to true, Stored.FARM_ADS_KEY to true))

    /** What a pass reads out of [file], on a phone with a supporter code or without: [Stored] asks no code. */
    private fun stored(file: MapSettings) = Stored.farm(file)

    /**
     * Without the Ad Skip Pass the field's ads are never tapped, for
     * anybody: the Meat Field pick on, a code, and `ad_watch` on in the
     * file -- counted over two visits (2026-10-03, PLAN_ABSCHLUSS_1_3.md
     * 3.6). The visit plants and waters what it has, and parks on nothing.
     */
    @Test
    fun `without the pass no ad is tapped, with a code and ad_watch in the file, over two visits`() {
        val r = Rig(adWorld())
        r.world.pass = false
        r.settings = stored(adFile(pass = false))
        r.cap.hand = FakeFront()
        r.skill.run()
        r.skill.run()
        assertClean(r)
        assertTrue(r.world.taps.none { it == "seed ad" || it == "View Ads" }, r.world.taps.toString())
        assertEquals(0, r.skill.lastVisit!!.ads)
    }

    /**
     * The pass switched on for an account without it: the ad that comes all
     * the same is not watched -- with a code as well; nothing is tapped in
     * it or on the field behind it, no back is sent -- and the visit parks
     * with the one sentence. The ad still stands, for the player.
     */
    @Test
    fun `with the pass an ad that comes all the same parks the visit untouched, with a code as well`() {
        val r = Rig(adWorld())
        r.world.pass = false
        r.settings = stored(adFile(pass = true))
        val front = FakeFront()
        r.cap.hand = front
        var tapsInAd = 0
        r.cap.onTap = { x, y ->
            if (front.inAd) tapsInAd += 1
            if (r.world.adButtonAt(x, y)) front.ad()
        }
        val img = r.cap.grab()
        val outcome = r.skill.work(img)
        img.release()
        assertEquals(Result.PARKED, outcome.result, outcome.toString())
        assertEquals(FreeAds.PARK, outcome.why)
        assertEquals(0, tapsInAd, "nothing tapped while the ad is in front")
        assertEquals(0, r.cap.backs)
        assertTrue(front.inAd, "the ad still stands, for the player")
        r.cap.grab().release()      // the world names the taps it has been sent
        assertEquals(1, r.world.taps.count { it == "seed ad" }, r.world.taps.toString())
        assertTrue(r.world.taps.none { it == "View Ads" }, "the visit went on after the park: ${r.world.taps}")
    }

    @Test
    fun `with the ads switched off nothing is tapped for them`() {
        val r = Rig(adWorld())
        r.skill.run()
        assertClean(r)
        assertTrue(r.world.taps.none { it == "seed ad" || it == "View Ads" || it == "Water" },
                   r.world.taps.toString())
        assertTrue(r.world.taps.none { it.startsWith("plot") }, "a plot tapped for nothing: ${r.world.taps}")
        assertEquals(0, r.world.seedAdsLeft + r.world.canAdsLeft - 2, "the game's ads untouched")
    }

    @Test
    fun `the cans' ad at 0 of 2 is not tapped, and the Huge can never`() {
        val r = Rig(adWorld())
        r.world.canAdsLeft = 0
        r.world.seedAdsLeft = 0
        r.settings = FarmSkill.Settings(ads = true)
        r.skill.run()
        assertClean(r)
        // The dialog was opened -- the game's own count is what says 0/2 --
        // and shut again with nothing tapped on it.
        assertTrue(r.world.taps.contains("Water"), r.world.taps.toString())
        assertTrue(r.world.taps.none { it == "View Ads" || it == "seed ad" }, r.world.taps.toString())
        assertEquals(mapOf(FarmSkill.SEEDS to 2, FarmSkill.CANS to 2), r.adsUsed.toMap())
        assertTrue(r.log.any { it == "ads: cans 2/2 already today" }, r.log.toString())
    }

    @Test
    fun `a counter that does not fall twice ends the watering, keeps the frame and comes back soon`() {
        val r = Rig(World(
            // Two plots two cans ripen, and a third that keeps something
            // growing when both have: the brake's clock is row 9.
            plots = (listOf(growing("00:50:00"), growing("00:50:00"), growing("03:00:00")) +
                     List(3) { PaintFarm.Plot(null) }).toMutableList(),
            seeds = 5, cans = 2))
        r.world.cansStuck = true
        r.skill.run()
        assertClean(r)
        assertEquals(0, r.skill.lastVisit!!.watered, r.log.toString())
        assertTrue(r.log.any { it == "watering plot 0,0: the counter did not fall (2 -> 2), once" }, r.log.toString())
        assertTrue(r.log.any { it.endsWith("twice -- watering ends for this visit") }, r.log.toString())
        assertTrue(r.kept.any { it.startsWith("water_no_proof_c") }, r.kept.toString())
        assertEquals("cans left -- watering stopped short", r.reason)
        assertEquals(r.clock + FarmSkill.FALLBACK, r.due)
    }

    @Test
    fun `a can dialog nobody knows is closed with nothing on it tapped`() {
        val r = Rig(World(
            plots = (listOf(growing("01:00:00")) + List(5) { PaintFarm.Plot(null) }).toMutableList(),
            seeds = 5, cans = 3))
        r.world.boostUnknown = true
        r.skill.run()
        assertClean(r)
        assertEquals(1, r.world.taps.count { it == "Water" }, r.world.taps.toString())
        assertTrue(r.world.taps.none { it == "boost Water" || it == "tiny can" }, r.world.taps.toString())
        assertEquals(0, r.world.watered)
        assertTrue(r.kept.any { it.startsWith("boost_unknown_c0_r0") }, r.kept.toString())
        assertEquals(r.clock + FarmSkill.FALLBACK, r.due, r.reason)
    }

    @Test
    fun `a can dialog that opens on the Huge can gets the Tiny one chosen before Water`() {
        // Never seen (every real dialog opened on the Tiny can), and never
        // to be measured by choosing the Huge can: the player's rule of
        // 2026-09-28 is that Water is never pressed with it chosen.
        val r = Rig(World(
            plots = (listOf(growing("00:50:00")) + List(5) { PaintFarm.Plot(null) }).toMutableList(),
            seeds = 5, cans = 3))
        r.world.boostOpensOn = 1
        r.skill.run()
        assertClean(r)
        // One watering (the plot ripens on two cans and is planted again; the
        // third can does not ripen a full grow, 13), with the Tiny can
        // chosen first.
        val taps = r.world.taps
        assertEquals(1, taps.count { it == "boost Water" }, taps.toString())
        assertTrue(taps.indices.filter { taps[it] == "boost Water" }.all { taps[it - 1] == "tiny can" }, taps.toString())
        assertEquals(2, r.world.watered, r.log.toString())
        assertTrue(r.log.any { it.contains("does not have the Tiny can chosen") }, r.log.toString())
    }

    @Test
    fun `a visit an error unwound books no clock and does not hold the field shut`() {
        // Live 2026-09-28 07:08: the player opened the app as the field was
        // handed over, the first grab threw, and the visit booked "nothing
        // growing" for an hour and called the field futile -- two plots ripe.
        val r = Rig(World(plots = (List(2) { PaintFarm.Plot("ripe") } + List(4) { growing("01:00:00") })
                              .toMutableList(), seeds = 5, cans = 0))
        val booked = r.clock + 3600
        r.due = booked
        val img = r.cap.grab()
        assertEquals(true, r.skill.seesWork(Director.FIELD, img))
        val scene = r.cap.scene
        r.cap.scene = { throw CaptureError("the game is not in front") }
        assertFailsWith<CaptureError> { r.skill.work(img) }
        img.release()
        assertEquals(booked, r.due, "the clock of the visit before stands")
        assertEquals("", r.reason)
        r.cap.scene = scene
        val again = r.cap.grab()
        assertEquals(true, r.skill.seesWork(Director.FIELD, again), "the same field is still work")
        again.release()
    }

    @Test
    fun `a Tiny can that will not be chosen is no Water at all`() {
        val r = Rig(World(
            plots = (listOf(growing("00:50:00")) + List(5) { PaintFarm.Plot(null) }).toMutableList(),
            seeds = 5, cans = 3))
        r.world.boostOpensOn = 1
        r.world.tinyStuck = true
        r.skill.run()
        assertClean(r)
        assertTrue(r.world.taps.none { it == "boost Water" }, r.world.taps.toString())
        assertEquals(0, r.world.watered)
        assertEquals(3, r.world.cans)
        assertTrue(r.kept.any { it.startsWith("boost_not_tiny_c0_r0") }, r.kept.toString())
        assertEquals(r.clock + FarmSkill.FALLBACK, r.due, r.reason)
    }

    @Test
    fun `no locked or unread plot and no bubble is ever tapped to water it`() {
        val r = Rig(World(
            plots = mutableListOf(PaintFarm.Plot("growing", timer = "01:00:00", bubbleOnTap = true),
                                  PaintFarm.Plot(null), PaintFarm.Plot(null), PaintFarm.Plot(null),
                                  PaintFarm.Plot(null), PaintFarm.Plot(null)),
            seeds = 5, cans = 4))
        r.skill.run()
        assertClean(r)
        assertTrue(r.world.taps.none { it.startsWith("plot") }, r.world.taps.toString())
        assertEquals(4, r.world.cans)
    }

    @Test
    fun `the free seed slot is the only one tapped while the sack has seeds`() {
        val r = Rig(World(plots = (List(3) { PaintFarm.Plot("empty") } + List(3) { PaintFarm.Plot(null) })
                              .toMutableList(), seeds = 2, good = 5, great = 5))
        r.settings = FarmSkill.Settings(betterSeeds = true)
        r.skill.run()
        // The world holds every Select to it (World.act: "a bought seed with
        // N free seeds"); the third plot meets the sack at 0 and takes Good.
        assertClean(r)
        assertEquals(3, r.world.planted())
        assertEquals(1, r.world.goodPlanted)
        assertEquals(0, r.world.greatPlanted)
    }

    @Test
    fun `a field with cans and a growing plot is worked, and the brake holds a watering that fails`() {
        // Cans in hand and something growing: work, whatever the clock says.
        val r = Rig(World(
            plots = (listOf(growing("01:00:00")) + List(5) { growing("02:00:00") }).toMutableList(),
            seeds = 5, cans = 3))
        r.due = r.clock + 3600
        val img = r.cap.grab()
        assertEquals(true, r.skill.seesWork(Director.FIELD, img))
        img.release()
        // No cans, nothing ripe, nothing empty: nothing.
        val none = Rig(World(plots = MutableList(6) { growing("02:00:00") }, seeds = 5, cans = 0))
        val still = none.cap.grab()
        assertEquals(false, none.skill.seesWork(Director.FIELD, still))
        still.release()
        // No cans, but the day's cans ad on the page: work.
        none.settings = FarmSkill.Settings(ads = true)
        val ad = none.cap.grab()
        assertEquals(true, none.skill.seesWork(Director.FIELD, ad))
        ad.release()

        // A file that has the cans' ads spent is no reason to stand at 0
        // cans (LDPlayer, 2026-10-02 09:10): the switch is, one visit reads
        // the game's own count, and where that says 0/2 the brake holds the
        // unchanged field shut after it.
        val stale = Rig(World(plots = MutableList(6) { growing("02:00:00") }, seeds = 5, cans = 0))
        stale.settings = FarmSkill.Settings(ads = true)
        stale.adsUsed[FarmSkill.CANS] = 2
        stale.world.canAdsLeft = 0
        stale.due = stale.clock + 3600
        val f1 = stale.cap.grab()
        assertEquals(true, stale.skill.seesWork(Director.FIELD, f1))
        stale.skill.work(f1)
        f1.release()
        assertTrue(stale.log.any { it == "ads: cans 2/2 already today" }, stale.log.toString())
        assertTrue(stale.world.taps.none { it == "View Ads" }, stale.world.taps.toString())
        val f2 = stale.cap.grab()
        assertEquals(false, stale.skill.seesWork(Director.FIELD, f2), stale.log.toString())
        f2.release()

        // The threshold of 13 on the bare field: cans in hand, but no plot
        // the visit would water -- every one dearer than the cans, or at or
        // under 20 minutes -- is no work, or the semi-automatic mode would
        // walk into the brake every time the view changed.
        fun sees(vararg times: String, cans: Int, min: Int = FarmSkill.WATER_MIN_MINUTES): Boolean? {
            val w = Rig(World(plots = (times.map { growing(it) } + List(6 - times.size) { PaintFarm.Plot(null) })
                                  .toMutableList(), seeds = 5, cans = cans))
            w.settings = FarmSkill.Settings(waterMinMinutes = min)
            val f = w.cap.grab()
            try { return w.skill.seesWork(Director.FIELD, f) } finally { f.release() }
        }
        assertEquals(false, sees("02:00:00", "01:40:00", cans = 3), "every plot dearer than three cans")
        assertEquals(true, sees("02:00:00", "01:30:00", cans = 3), "01:30:00 is three cans")
        assertEquals(false, sees("00:15:00", "00:20:00", cans = 3), "at or under 20 minutes")
        assertEquals(true, sees("00:15:00", cans = 3, min = 0), "threshold 0: every growing plot")
        assertEquals(false, sees("02:00:00", cans = 0), "no can, no ad")

        // A watering that cannot be done is tried once, not every round.
        val stuck = Rig(World(plots = (listOf(growing("01:00:00")) + List(5) { growing("02:00:00") })
                                  .toMutableList(), seeds = 5, cans = 3))
        stuck.world.boostUnknown = true
        stuck.due = stuck.clock + 3600
        val scan = Scan(stuck)
        scan.rounds(40)
        val waters = stuck.world.taps.count { it == "Water" }
        assertEquals(1, waters, stuck.world.taps.toString())
        scan.rounds(40)
        assertEquals(waters, stuck.world.taps.count { it == "Water" }, "tried again: ${stuck.world.taps}")
        scan.director.close()
    }

    @Test
    fun `two passes put both on the card, and nothing of the first twice`() {
        val r = Rig(World(
            plots = mutableListOf(growing("00:30:00"), PaintFarm.Plot("ripe"), PaintFarm.Plot(null),
                                  PaintFarm.Plot(null), PaintFarm.Plot(null), PaintFarm.Plot(null)),
            seeds = 5, cans = 1))
        val card = MapSettings()
        val today = 20_000L
        r.skill.run()
        SkillStats.add(card, "farm", r.skill.lastCounts, today)
        assertEquals(mapOf("harvested" to 2, "planted" to 2, "watered" to 1, "ads" to 0), r.skill.lastCounts,
                     r.log.toString())
        // The second pass finds nothing to do: it hands over nothing.
        r.skill.run()
        SkillStats.add(card, "farm", r.skill.lastCounts, today)
        assertEquals(mapOf("harvested" to 0, "planted" to 0, "watered" to 0, "ads" to 0), r.skill.lastCounts)
        // A third with a can and a plot it ripens (13: a can goes only
        // where it brings the timer to zero).
        r.world.cans = 1
        r.world.plots[2] = growing("00:25:00")
        r.world.remaining[0 to 1] = 1500
        r.skill.run()
        SkillStats.add(card, "farm", r.skill.lastCounts, today)
        val read = SkillStats.read(card, "farm", today)
        assertEquals(mapOf("harvested" to 3, "planted" to 3, "watered" to 2), read, r.log.toString())
        assertEquals("3 harvested, 3 planted, 2 cans used.", SkillStats.sentence("farm", read))
    }

    @Test
    fun `next_visit books the can where it comes before everything else`() {
        fun at(v: FarmSkill.Visit) = v.at
        // Row 7: growing, cans 0, a can in 05:00 -- before the 01:00:00 plot.
        assertEquals(320.0, at(FarmSkill.nextVisit(0.0, listOf(3600), cans = 0, canRefillIn = 300)))
        assertEquals("a can arrives", FarmSkill.nextVisit(0.0, listOf(3600), cans = 0, canRefillIn = 300).why)
        // The plot ripens first: the plot.
        assertEquals(620.0, at(FarmSkill.nextVisit(0.0, listOf(600), cans = 0, canRefillIn = 3000)))
        // Row 8: the countdown unread -- REFILL, where nothing else is earlier.
        assertEquals((FarmSkill.REFILL + FarmSkill.MARGIN).toDouble(),
                     at(FarmSkill.nextVisit(0.0, listOf(7200), cans = 0, canRefillIn = null)))
        assertEquals("a can arrives (refill unreadable)",
                     FarmSkill.nextVisit(0.0, listOf(7200), cans = 0, canRefillIn = null).why)
        // A countdown misread as a day is capped at REFILL, as the seed's is.
        assertEquals((FarmSkill.REFILL + FarmSkill.MARGIN).toDouble(),
                     at(FarmSkill.nextVisit(0.0, listOf(7200), cans = 0, canRefillIn = 99999)))
        // Nothing growing: a can is no reason to come.
        assertEquals((FarmSkill.REFILL + FarmSkill.MARGIN).toDouble(),
                     at(FarmSkill.nextVisit(0.0, emptyList(), growing = 0, cans = 0, canRefillIn = 300)))
        // Row 9: cans left by the brake -- ten minutes.
        assertEquals(FarmSkill.FALLBACK.toDouble(),
                     at(FarmSkill.nextVisit(0.0, listOf(7200), cans = 3, waterStopped = true)))
        // Cans left without the brake, or cans unread: the table as before.
        assertEquals(7220.0, at(FarmSkill.nextVisit(0.0, listOf(7200), cans = 3)))
        assertEquals(7220.0, at(FarmSkill.nextVisit(0.0, listOf(7200), cans = null, canRefillIn = 300)))
    }

    // ------------------------------------------------------------------------
    // The world
    // ------------------------------------------------------------------------
    /**
     * The skill, a fake capture and a painted world that reacts to taps, plus
     * the things the shell remembers for this skill: the clock, the grow
     * duration, and since 2026-09-28 the page's switches and the day's ads.
     *
     * The clock moves with every sleep of the skill's: the skill measures
     * its waits on it, and a clock that never moved would wait for ever.
     */
    class Rig(val world: World) {
        val cap = DirectorTest.FakeCapture()
        val log = ArrayList<String>()
        var clock = 1_000_000.0
        var on = true
        var due: Double? = null
        var growSeconds: Int? = null
        var reason = ""
        var settings = FarmSkill.Settings()
        /** The frames the skill kept, by tag. */
        val kept = ArrayList<String>()
        /** `farm_ads_seeds` and `farm_ads_cans` of the game day; [newDay] is the reset. */
        val adsUsed = HashMap<String, Int>()
        val skill = FarmSkill(
            cap,
            dueAt = { due },
            remember = { at, grow, why ->
                due = at
                if (grow != null) growSeconds = grow
                reason = why
            },
            // Settings read back out: what an earlier visit left in
            // `farm_grow_seconds`. Set [growSeconds] before a run to stand for
            // one (Stored.farmGrowSeconds on the phone).
            knownGrowSeconds = { growSeconds },
            log = { log += it },
            on = { on },
            keep = { _, tag -> kept += tag },
            sleep = { clock += it },
            now = { clock },
            settings = { settings },
            adsUsed = { adsUsed[it] ?: 0 },
            setAdsUsed = { kind, n -> adsUsed[kind] = n })

        init {
            world.cap = cap
            cap.scene = { world.next() }
        }

        /** The game's day turns over: the file's counts and the game's own "n/2" start again. */
        fun newDay() {
            adsUsed.clear()
            world.seedAdsLeft = 2
            world.canAdsLeft = 2
        }
    }

    /**
     * The game, as far as this skill can tell: which screen is up, what stands
     * on the six plots, how many seeds of each kind and how many cans are
     * left, how many of the day's ads, and what a tap does. Every `grab` first
     * applies whatever taps have been recorded since the last one, then
     * paints the state that leaves.
     *
     * What the game does, M1's measurement (PLAN_MEAT_FIELD_GIESSEN.md 5.2):
     * a tap on a growing plot opens the water popup; Water there opens the
     * can dialog, whose box holds the fewest cans that ripen the plot, capped
     * by the cans there are; its Water spends that many and closes
     * everything; at 0 cans the dialog shows "n/2" and View Ads. The seed
     * menu keeps the last choice, and with the free slot chosen at 0 its
     * button is the violet "Ad (n/2)". With the pass an ad's reward comes at
     * once, under the Reward sheet.
     */
    class World(
        /** The grid in row-major order, cell `row * 2 + col`. */
        val plots: MutableList<PaintFarm.Plot>,
        var seeds: Int? = null,
        var screen: String = FIELD,
        /**
         * Plots the game knows are still growing however they are drawn: tapping
         * one of these raises the water popup instead of doing what the badge
         * promised (PLAN_MEAT_FIELD 2.5 -- the game says so itself).
         */
        private val secretlyGrowing: Set<Pair<Int, Int>> = emptySet(),
        /** The green can's count, or null for a header that shows none (the fields before cans). */
        var cans: Int? = null,
        var good: Int = 0,
        var great: Int = 0,
        /** The player has the Ad Skip Pass: the reward comes the moment the ad is tapped. */
        var pass: Boolean = true,
    ) {
        lateinit var cap: DirectorTest.FakeCapture

        var menuFor: Pair<Int, Int>? = null
        var menuSelected = 1
        /** What the menu opens on: the last choice (M1), never the free slot to begin with. */
        var lastChoice = 1
        var popupFor: Pair<Int, Int>? = null
        var boostFor: Pair<Int, Int>? = null
        var sheet = false
        var canRefill = "05:00"
        var seedAdsLeft = 2
        var canAdsLeft = 2
        /** The header's can counter stands still while the cans are spent: the proof that never comes. */
        var cansStuck = false
        /** Water opens a dialog this app has never seen (no keys): the brake of 6.3. */
        var boostUnknown = false
        /** The can the dialog opens on: 0 the Tiny one (every real dialog), 1 the Huge one (never seen). */
        var boostOpensOn = 0
        /** The can the open dialog has chosen. */
        var boostChosen = 0
        /** A tap on the Tiny can does not choose it. */
        var tinyStuck = false
        /** What the header's sack shows where it is not [seeds]: a header that reads wrong. */
        var headerSeeds: String? = null
        /** What the header's can shows where it is not [cans]: a can count that reads wrong. */
        var headerCans: String? = null
        /** The water popup shows no "Time Remaining": the time is the plate's, or nobody's. */
        var popupTimeBlank = false
        /** The numbers under the seed menu's slots are drawn; off, none of them reads. */
        var slotNumbers = true
        /** What the free slot's number shows where it is not [seeds]: the 0 that read 4 at 900 x 1600 (K10). */
        var freeSlotShows: String? = null
        /** No growing plot's plate is drawn: the plates stop reading (the popups still show the time). */
        var hidePlates = false
        /**
         * Frames after an ad's tap that are neither the field nor the sheet,
         * before the reward and its sheet stand (the Poco with the pass,
         * 2026-10-01 08:06: 11 s).
         */
        var lateFrames = 0
        private var lateLeft = 0
        /** The next looks at the main screen that find the hologram device lit ([Paint.holoLight]). */
        var lit = 0
        /** Called after every Select: what the game does meanwhile (a free seed arriving). */
        var onSelect: World.() -> Unit = {}
        /** Each plot's true time left, seconds; the plate and the popup paint it. */
        val remaining = HashMap<Pair<Int, Int>, Int>()
        /** Cans spent, Good and Great seeds planted, as the game counts them. */
        var watered = 0
        var goodPlanted = 0
        var greatPlanted = 0
        var grabs = 0
        var grabsAtX: Int? = null
        var grabsAtGlobe: Int? = null
        val taps = ArrayList<String>()
        /** Everything done that must never be, with what stood when it was done. */
        val forbidden = ArrayList<String>()
        private var seen = 0
        private val started = plots.map { it.state }

        init {
            for ((i, p) in plots.withIndex()) {
                if (p.state == "growing" && p.timer.isNotEmpty()) remaining[i % 2 to i / 2] = seconds(p.timer)
            }
        }

        fun harvested(): Int = plots.indices.count {
            started[it] == "ripe" && plots[it].state != "ripe"
        }

        fun planted(): Int = plots.indices.count {
            started[it] != "growing" && plots[it].state == "growing"
        }

        fun next(): Mat {
            applyTaps()
            grabs += 1
            return paint()
        }

        /** The fewest cans that ripen [cell], capped by the cans there are -- what the dialog's box opens at. */
        fun amountFor(cell: Pair<Int, Int>): Int {
            val rem = remaining[cell] ?: 0
            return minOf(cans ?: 0, maxOf(1, (rem + FarmSkill.CAN_SECONDS - 1) / FarmSkill.CAN_SECONDS))
        }

        private fun paint(): Mat = when {
            lateLeft > 0 -> { lateLeft -= 1; Paint.blank() }
            screen == MAIN && lit > 0 -> {
                lit -= 1
                val main = PaintFarm.mainScreen()
                Paint.holoLight(main).also { main.release() }
            }
            screen == MAIN -> PaintFarm.mainScreen()
            screen == EXPLORE -> PaintFarm.exploreMenu()
            // A screen this skill has no name for: the inside of a dungeon,
            // or the minigame a player was in on 2026-09-19.
            screen == FOREIGN -> Paint.dungeonBattle(0)
            else -> PaintFarm.field(
                if (hidePlates) plots.map { if (it.state == "growing") PaintFarm.Plot("growing") else it }
                else plots,
                seeds = headerSeeds ?: seeds?.toString() ?: "", refill = "05:00",
                menu = if (menuFor != null) menuSelected else null,
                popup = popupFor != null,
                cans = headerCans ?: cans?.toString() ?: "", canRefill = if (cans != null) canRefill else "",
                slotCounts = if (slotNumbers) listOf(freeSlotShows ?: seeds?.toString() ?: "", "$good", "$great")
                             else emptyList(),
                seedAd = if (menuFor != null && menuSelected == 0 && seeds == 0) "$seedAdsLeft/2" else null,
                popupTime = if (popupTimeBlank) "" else popupFor?.let { remaining[it] }?.let { hms(it) } ?: "",
                boost = boostFor?.let {
                    if ((cans ?: 0) == 0) PaintFarm.Boost("$canAdsLeft/2", "0", known = !boostUnknown)
                    else PaintFarm.Boost("$cans", "${amountFor(it)}", known = !boostUnknown, chosen = boostChosen)
                },
                sheet = sheet)
        }

        /**
         * Where a tap landed, by name, or null where it landed on nothing.
         *
         * Asked of the screen that is actually up, not of a list of every
         * target there is: the Meat Field card's place on the Explore menu
         * and the tap point of the field's own top-left plot are 0.02 apart,
         * and a namer that does not know which screen it is looking at reads
         * one for the other.
         */
        private fun named(fx: Double, fy: Double): String? {
            fun near(tx: Double, ty: Double, rx: Double, ry: Double) =
                abs(fx - tx) <= rx && abs(fy - ty) <= ry
            when (screen) {
                MAIN ->
                    if (near(GLOBE_FX + Explore.NAV_EXPLORE_DX, GLOBE_FY, 0.03, 0.02))
                        return "Explore tab"
                EXPLORE -> {
                    if (near(GLOBE_FX, GLOBE_FY, 0.03, 0.02)) return "globe"
                    if (near(0.3652, 0.5302, 0.05, 0.03)) return "Meat Field card"
                }
                FIELD -> {
                    // The Reward sheet is "Tap to close": wherever the tap lands.
                    if (sheet) return "close sheet"
                    if (near(Summon.EXIT_BUTTON_FX, Summon.EXIT_BUTTON_FY, 0.04, 0.023)) return "X"
                    if (boostFor != null) {
                        if (near(PaintFarm.TINY_FX, PaintFarm.CAN_SLOT_FY, 0.065, 0.025)) return "tiny can"
                        if (near(PaintFarm.HUGE_FX, PaintFarm.CAN_SLOT_FY, 0.065, 0.025)) return "huge can"
                        if (near(PaintFarm.BOOST_BUTTON_FX, PaintFarm.BOOST_BUTTON_FY, 0.10, 0.025))
                            return if ((cans ?: 0) == 0) "View Ads" else "boost Water"
                        if (PaintFarm.KEY_FX.any { near(it, 0.571, 0.03, 0.015) }) return "key"
                        if (near(0.5, 0.18, 0.05, 0.03)) return "outside"
                        return null
                    }
                    if (popupFor != null) {
                        if (near(0.5, 0.10, 0.03, 0.03)) return "close water popup"
                        // The popup's own button, pressed only to water a
                        // plot that reads growing.
                        if (near(PaintFarm.WATER_FX, PaintFarm.WATER_FY, 0.161, 0.022)) return "Water"
                        return null
                    }
                    if (menuFor != null) {
                        if (near(0.476, 0.599, 0.122, 0.023))
                            return if (menuSelected == 0 && seeds == 0) "seed ad" else "Select"
                        for ((i, sx) in listOf(0.291, 0.476, 0.661).withIndex()) {
                            if (near(sx, 0.475, 0.065, 0.025)) return "seed slot $i"
                        }
                        if (near(0.5, 0.18, 0.05, 0.03)) return "outside"
                        return null
                    }
                    if (near(0.5, 0.10, 0.03, 0.03)) return "close water popup"
                    if (near(0.5, 0.18, 0.05, 0.03)) return "outside"
                    for (row in 0 until 3) {
                        for (col in 0 until 2) {
                            val t = Farm.plotTap(col, row)
                            if (near(t.fx, t.fy, 0.02, 0.02)) return "plot $col,$row"
                        }
                    }
                }
            }
            return null
        }

        /** Is (x, y) the ad button of what stands now -- for a test's hand, which starts its ad on the tap. */
        fun adButtonAt(x: Int, y: Int): Boolean {
            val fx = x / PaintFarm.W.toDouble()
            val fy = y / PaintFarm.H.toDouble()
            if (sheet) return false
            if (boostFor != null && (cans ?: 0) == 0)
                return abs(fx - PaintFarm.BOOST_BUTTON_FX) <= 0.10 && abs(fy - PaintFarm.BOOST_BUTTON_FY) <= 0.025
            if (menuFor != null && menuSelected == 0 && seeds == 0)
                return abs(fx - 0.476) <= 0.122 && abs(fy - 0.599) <= 0.023
            return false
        }

        private fun applyTaps() {
            while (seen < cap.taps.size) {
                val (x, y) = cap.taps[seen++]
                val fx = x / PaintFarm.W.toDouble()
                val fy = y / PaintFarm.H.toDouble()
                val what = named(fx, fy) ?: "?? at %.3f/%.3f".format(fx, fy)
                taps += what
                act(what)
            }
        }

        private fun act(what: String) {
            when {
                what == "close sheet" -> sheet = false
                what == "X" && screen == FIELD -> {
                    screen = EXPLORE
                    grabsAtX = grabs
                    menuFor = null
                    popupFor = null
                    boostFor = null
                }
                what == "globe" && screen == EXPLORE -> {
                    screen = MAIN
                    grabsAtGlobe = grabs
                }
                what == "Explore tab" && screen == MAIN -> screen = EXPLORE
                what == "Meat Field card" && screen == EXPLORE -> screen = FIELD
                what == "close water popup" -> popupFor = null
                what == "outside" -> {
                    menuFor = null
                    popupFor = null
                    boostFor = null
                }
                what == "huge can" -> forbidden += "the Huge can, cans $cans"
                what == "tiny can" -> if (!tinyStuck) boostChosen = 0
                what == "key" -> forbidden += "a key of the can dialog"
                what == "Water" -> {
                    val cell = popupFor ?: return
                    if (at(cell).state != "growing" && cell !in secretlyGrowing)
                        forbidden += "Water on plot $cell, which is ${at(cell).state}"
                    popupFor = null
                    boostFor = cell
                    boostChosen = boostOpensOn
                }
                what == "boost Water" -> {
                    val cell = boostFor ?: return
                    if (boostChosen != 0) forbidden += "Water with the Huge can chosen, cans $cans"
                    val a = amountFor(cell)
                    if (!cansStuck) cans = (cans ?: 0) - a
                    watered += a
                    val rem = (remaining[cell] ?: 0) - a * FarmSkill.CAN_SECONDS
                    if (rem <= 0) {
                        remaining.remove(cell)
                        set(cell, PaintFarm.Plot("ripe"))
                    } else {
                        remaining[cell] = rem
                        set(cell, PaintFarm.Plot("growing", timer = hms(rem)))
                    }
                    boostFor = null
                }
                what == "View Ads" -> {
                    if (canAdsLeft <= 0) {
                        forbidden += "View Ads at 0/2"
                        return
                    }
                    canAdsLeft -= 1
                    if (pass) {
                        cans = (cans ?: 0) + 20
                        sheet = true
                        lateLeft = lateFrames
                    }
                }
                what == "seed ad" -> {
                    if (seedAdsLeft <= 0) {
                        forbidden += "the seed ad at 0/2"
                        return
                    }
                    seedAdsLeft -= 1
                    if (pass) {
                        seeds = (seeds ?: 0) + 3
                        sheet = true
                    }
                }
                what.startsWith("seed slot") -> menuSelected = what.last().digitToInt()
                what == "Select" -> {
                    val cell = menuFor
                    if (cell != null) {
                        when (menuSelected) {
                            0 -> if ((seeds ?: 0) > 0) {
                                plant(cell, "02:00:00")
                                seeds = seeds!! - 1
                            }
                            else -> {
                                if ((seeds ?: 0) > 0) forbidden += "a bought seed with ${seeds} free seeds"
                                val left = if (menuSelected == 1) good else great
                                if (left <= 0) {
                                    forbidden += "slot $menuSelected at 0"
                                } else {
                                    // Good and Great both grow 03:54:05 (M1).
                                    plant(cell, "03:54:05")
                                    if (menuSelected == 1) { good -= 1; goodPlanted += 1 }
                                    else { great -= 1; greatPlanted += 1 }
                                }
                            }
                        }
                    }
                    lastChoice = menuSelected
                    menuFor = null
                    onSelect()
                }
                what.startsWith("plot ") -> {
                    val (col, row) = what.removePrefix("plot ").split(",").map { it.toInt() }
                    val cell = col to row
                    val plot = at(cell)
                    if (plot.bubbleOnTap) forbidden += "the bubble over plot $cell"
                    val state = plot.state
                    if (cell in secretlyGrowing) {
                        popupFor = cell
                    } else if (state == "ripe") {
                        set(cell, PaintFarm.Plot("empty"))
                    } else if (state == "empty") {
                        menuFor = cell
                        menuSelected = lastChoice
                    } else if (state == "growing") {
                        popupFor = cell
                    }
                }
            }
        }

        private fun plant(cell: Pair<Int, Int>, timer: String) {
            set(cell, PaintFarm.Plot("growing", timer = timer))
            remaining[cell] = seconds(timer)
        }

        private fun at(cell: Pair<Int, Int>) = plots[cell.second * 2 + cell.first]
        private fun set(cell: Pair<Int, Int>, plot: PaintFarm.Plot) {
            plots[cell.second * 2 + cell.first] = plot
        }

        companion object {
            // The nav bar PaintFarm draws, so a tap on it can be named.
            const val GLOBE_FX = 0.4825
            const val GLOBE_FY = 0.935
            const val FIELD = "field"
            const val EXPLORE = "explore"
            const val MAIN = "main"
            const val FOREIGN = "foreign"

            fun seconds(t: String): Int = t.split(":").map { it.toInt() }.fold(0) { a, n -> a * 60 + n }

            fun hms(s: Int): String = "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        }
    }

    private companion object {
        // The package DirectorTest.FakeCapture answers with by default.
        const val GAME = "com.bandainamcoent.dgup_ww"
    }
}
