package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Mat
import kotlin.math.abs
import kotlin.test.assertEquals
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
        // `timers`, so it is the only one that can ever fire (NOTES.md, "A
        // number taken out of the list it is meant to cap is not a cap").
        assertEquals(7220.0,
                     at(FarmSkill.nextVisit(0.0, listOf(3 * 24 * 3600, 2 * 24 * 3600),
                                            growSeconds = 7200, emptyLeft = 0)),
                     "9a planted nothing, every timer misread, remembered grow caps it")
        assertEquals((2 * 24 * 3600 + FarmSkill.MARGIN).toDouble(),
                     at(FarmSkill.nextVisit(0.0, listOf(3 * 24 * 3600, 2 * 24 * 3600),
                                            growSeconds = null, emptyLeft = 0)),
                     "9b the same field with nothing remembered -- two days away")

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
        r.clock = r.due!!
        assertTrue(r.skill.hasBudget(), "the moment it falls due")
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
    fun `the main switch ends the visit and the way home still runs`() {
        val r = Rig(World(
            plots = (listOf(PaintFarm.Plot("ripe")) + List(5) { PaintFarm.Plot(null) })
                .toMutableList(),
            seeds = 5))
        r.on = false
        val outcome = r.skill.run()
        assertEquals(Result.STOPPED, outcome.result, outcome.toString())
        assertEquals(0, r.world.harvested(), "worked on after the switch went off")
        assertEquals(World.MAIN, r.world.screen, "the way home runs whatever else happened")
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
    // The world
    // ------------------------------------------------------------------------
    /**
     * The skill, a fake capture and a painted world that reacts to taps, plus
     * the three things the shell remembers for this skill.
     */
    class Rig(val world: World) {
        val cap = DirectorTest.FakeCapture()
        val log = ArrayList<String>()
        var clock = 1_000_000.0
        var on = true
        var due: Double? = null
        var growSeconds: Int? = null
        var reason = ""
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
            sleep = { },
            now = { clock })

        init {
            world.cap = cap
            cap.scene = { world.next() }
        }
    }

    /**
     * The game, as far as this skill can tell: which screen is up, what stands
     * on the six plots, how many free seeds are left, and what a tap does. Every
     * `grab` first applies whatever taps have been recorded since the last one,
     * then paints the state that leaves.
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
    ) {
        lateinit var cap: DirectorTest.FakeCapture

        var menuFor: Pair<Int, Int>? = null
        var menuSelected = 1            // never the free slot to begin with
        var popupFor: Pair<Int, Int>? = null
        var grabs = 0
        var grabsAtX: Int? = null
        var grabsAtGlobe: Int? = null
        val taps = ArrayList<String>()
        private var seen = 0
        private val started = plots.map { it.state }

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

        private fun paint(): Mat = when (screen) {
            MAIN -> PaintFarm.mainScreen()
            EXPLORE -> PaintFarm.exploreMenu()
            // A screen this skill has no name for: the inside of a dungeon,
            // or the minigame a player was in on 2026-09-19.
            FOREIGN -> Paint.dungeonBattle(0)
            else -> PaintFarm.field(plots, seeds = seeds?.toString() ?: "",
                                    refill = "05:00",
                                    menu = if (menuFor != null) menuSelected else null,
                                    popup = popupFor != null)
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
                    if (near(Summon.EXIT_BUTTON_FX, Summon.EXIT_BUTTON_FY, 0.04, 0.023)) return "X"
                    if (near(0.5, 0.10, 0.03, 0.03)) return "close water popup"
                    // The water popup's own button, which nothing may ever press.
                    if (popupFor != null && near(0.5, 0.62, 0.161, 0.022)) return "Water"
                    if (menuFor != null) {
                        if (near(0.476, 0.599, 0.122, 0.023)) return "Select"
                        for ((i, sx) in listOf(0.291, 0.476, 0.661).withIndex()) {
                            if (near(sx, 0.475, 0.065, 0.025)) return "seed slot $i"
                        }
                    }
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
                what == "X" && screen == FIELD -> {
                    screen = EXPLORE
                    grabsAtX = grabs
                    menuFor = null
                    popupFor = null
                }
                what == "globe" && screen == EXPLORE -> {
                    screen = MAIN
                    grabsAtGlobe = grabs
                }
                what == "Explore tab" && screen == MAIN -> screen = EXPLORE
                what == "Meat Field card" && screen == EXPLORE -> screen = FIELD
                what == "close water popup" -> popupFor = null
                what.startsWith("seed slot") -> menuSelected = what.last().digitToInt()
                what == "Select" -> {
                    val cell = menuFor
                    if (cell != null && menuSelected == 0 && (seeds ?: 0) > 0) {
                        set(cell, PaintFarm.Plot("growing", timer = "02:00:00"))
                        seeds = seeds!! - 1
                    }
                    menuFor = null
                }
                what.startsWith("plot ") -> {
                    val (col, row) = what.removePrefix("plot ").split(",").map { it.toInt() }
                    val cell = col to row
                    val state = at(cell).state
                    if (cell in secretlyGrowing) {
                        popupFor = cell
                    } else if (state == "ripe") {
                        set(cell, PaintFarm.Plot("empty"))
                    } else if (state == "empty") {
                        menuFor = cell
                        menuSelected = 1
                    }
                }
            }
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
        }
    }

    private companion object {
        // The package DirectorTest.FakeCapture answers with by default.
        const val GAME = "com.bandainamcoent.dgup_ww"
    }
}
