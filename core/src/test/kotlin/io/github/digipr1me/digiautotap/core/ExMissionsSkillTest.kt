package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Mat
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EX Missions on painted frames (PaintMissions): the tile, the Missions
 * window on each tab, the EX tab with its rows yellow, grey or blue, the
 * Reward sheet, the Stage Failed banner. The world ([MissionsWorld]) answers
 * every tap the way the game did in EX1's measurement (PLAN_EX_MISSIONS.md
 * 4.1): the window opens on Collection, a Claim on the list takes every
 * claimable row, every Claim raises the sheet, a blue arrow throws the game
 * out to the main screen, the dimmed field closes the window.
 *
 * The cases of the plan's 4.2 as the player's answers and EX1's findings
 * changed them (section 7, 10): no brake, five ways back in, the Missions
 * window worked on any tab, no clock. And everything 3.6 says never happens
 * has a case that would see it: [assertNothingForbidden] after every one.
 */
class ExMissionsSkillTest {

    private class Rig(val w: MissionsWorld = MissionsWorld()) {
        val kept = ArrayList<String>()
        val log = ArrayList<String>()
        val skill = ExMissionsSkill(w, log = { log += it; println(it) }, on = { w.on },
                                    keep = { _, tag -> kept += tag }, sleep = { w.t += it }, now = { w.t })
        fun said(text: String) = log.any { it == text }
    }

    /** 3.6: nothing but the tile, EX Missions, a yellow Claim, the sheet, the dimmed field, the neutral spot. */
    private fun assertNothingForbidden(w: MissionsWorld) {
        assertEquals(emptyList(), w.taps.filter { it in MissionsWorld.FORBIDDEN }, "forbidden taps in ${w.taps}")
        assertEquals(0, w.backs, "the back key")
    }

    private fun claimTaps(w: MissionsWorld) = w.taps.filter { it.startsWith("claim") }

    // ------------------------------------------------------------------------
    @Test
    fun `the painted frames read as the real ones do`() {
        val main = PaintMissions.main()
        val tile = assertNotNull(Missions.tile(main))
        assertTrue(abs(tile.fx - 0.791) < 0.01 && abs(tile.fy - 0.169) < 0.01, "tile at ${tile.fx},${tile.fy}")
        assertEquals(Missions.HUD_TOP, tile.anchor)
        assertEquals(Director.MAIN, Director.classify(main).screen)
        assertNull(Missions.tile(PaintMissions.main(tile = false)))
        assertEquals(Director.MAIN, Director.classify(PaintMissions.main(tile = false)).screen)

        for (tab in listOf(Missions.COLLECTION, Missions.DAILY)) {
            val img = PaintMissions.window(tab)
            val w = assertNotNull(Missions.window(img), "tab $tab")
            assertEquals(tab, w.tab)
            assertTrue(abs(w.exButton.fx - PaintMissions.TAB_FX[2]) < 0.01, "EX Missions at ${w.exButton.fx}")
            assertNull(Missions.exWindow(img), "tab $tab")
            assertTrue(Missions.claimCandidates(img).isNotEmpty(), "tab $tab has yellow Claims of its own")
            assertEquals(Director.MISSIONS, Director.classify(img).screen, "tab $tab")
        }
        val ex = PaintMissions.window(Missions.EX, r1 = true, listYellow = 2)
        val exw = assertNotNull(Missions.exWindow(ex))
        assertEquals(listOf(1, 2, 3), Missions.exClaims(ex, exw).map { it.row })
        assertEquals(Director.EX_MISSIONS, Director.classify(ex).screen)
        val clean = PaintMissions.window(Missions.EX)
        assertEquals(emptyList(), Missions.exClaims(clean, Missions.exWindow(clean)!!), "grey and blue are no Claims")
        // The EX tab lit and its list not drawn yet: the window, not the tab.
        val coming = PaintMissions.window(Missions.EX, exDrawn = false)
        assertEquals(Missions.EX, Missions.window(coming)?.tab)
        assertNull(Missions.exWindow(coming))
        assertEquals(Director.MISSIONS, Director.classify(coming).screen)

        assertNull(Missions.window(PaintMissions.opening()))
        val sheet = PaintMissions.rewardSheet()
        assertTrue(Dungeon.rewardSheet(sheet))
        assertNull(Missions.window(sheet))
        assertTrue(Dungeon.stageFailed(Paint.stageFailed()))
    }

    // ------------------------------------------------------------------------
    // The visit from the main screen (3.2)
    // ------------------------------------------------------------------------
    @Test
    fun `a main screen lit at the start is waited for as every way in waits, two visits running`() {
        // K2 of PLAN_ABSCHLUSS_1_3.md: the tile is read only where the auto
        // button is, and the hologram device's light takes the button. The
        // start waited 3 s; a light of 5 s parked the visit on "EX Missions
        // start from the main screen." -- it waits MainScreen.WAIT now.
        val r = Rig()
        for (visit in 1..2) {
            r.w.r2 = 1
            r.w.litUntil = r.w.t + 5.0
            val before = r.w.taps.size
            val out = r.skill.run()
            assertEquals(Result.DONE, out.result, "visit $visit: ${out.why}\n${r.log.joinToString("\n")}")
            assertEquals(listOf("tile", "tab ex", "claim r2", "sheet", "close"), r.w.taps.drop(before), "visit $visit")
            assertEquals(MissionsWorld.Screen.MAIN, r.w.screen)
            assertEquals(mapOf("claims" to 1), r.skill.lastCounts, "visit $visit counts its own claim")
        }
        assertEquals(2, r.w.claimsMade)
        assertTrue(r.log.none { it.contains("not read on this frame") }, r.log.toString())
        assertNothingForbidden(r.w)
    }

    @Test
    fun `a visit claims row 2 until it is done, then row 1, and closes the window`() {
        val r = Rig()
        r.w.r2 = 3
        r.w.r1 = 2
        val out = r.skill.run()
        assertEquals(Result.DONE, out.result, out.why)
        assertEquals(listOf("tile", "tab ex",
                            "claim r2", "sheet", "claim r2", "sheet", "claim r2", "sheet",
                            "claim r1", "sheet", "claim r1", "sheet",
                            "close"), r.w.taps)
        assertEquals(MissionsWorld.Screen.MAIN, r.w.screen)
        assertEquals(mapOf("claims" to 5), r.skill.lastCounts)
        assertEquals(5, r.w.claimsMade)
        // The order the player gave: every tap on row 2 before the first on row 1.
        assertTrue(r.w.taps.lastIndexOf("claim r2") < r.w.taps.indexOf("claim r1"))
        // The witnesses of the plan's 4.3, row 1.
        for (line in listOf("ex missions: opening Missions", "ex missions: EX Missions", "ex missions: row 2 -- Claim",
                            "ex missions: claimed (1)", "ex missions: row 2: no yellow left",
                            "ex missions: row 1 -- Claim", "ex missions: row 1: no yellow left",
                            "ex missions: the window is clean (5 claimed)", "ex missions: closing",
                            "ex missions: back on the main screen")) {
            assertTrue(r.said(line), "no \"$line\" in ${r.log}")
        }
        // The dot off the tile's rows before the tile is read, and back at the end.
        assertEquals(listOf("clear 0.145-0.195", "back"), r.w.overlay)
        assertTrue(r.kept.isEmpty(), "${r.kept}")
        assertNothingForbidden(r.w)
    }

    @Test
    fun `a Claim that changes the row without a sheet counts`() {
        val r = Rig()
        r.w.r2 = 1
        r.w.sheet = false
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("tile", "tab ex", "claim r2", "close"), r.w.taps)
        assertEquals(mapOf("claims" to 1), r.skill.lastCounts)
        assertNothingForbidden(r.w)
    }

    @Test
    fun `with nothing yellow nothing in the tab is tapped`() {
        val r = Rig()
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("tile", "tab ex", "close"), r.w.taps)
        assertEquals(mapOf("claims" to 0), r.skill.lastCounts)
        assertTrue(r.said("ex missions: nothing to claim"), "${r.log}")
        assertNothingForbidden(r.w)
    }

    /** 10.2: one tap on the list claims every claimable row, so a third yellow row is never tapped itself. */
    @Test
    fun `rows 2 and 3 yellow are one tap on row 2`() {
        val r = Rig()
        r.w.r2 = 1
        r.w.extra = 2
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("claim r2"), claimTaps(r.w))
        assertEquals(listOf("tile", "tab ex", "claim r2", "sheet", "close"), r.w.taps)
        assertNothingForbidden(r.w)
    }

    /** No brake (question 11): "sometimes you have to tap hundreds of times". */
    @Test
    fun `a row that stays yellow is claimed for as long as it does`() {
        val r = Rig()
        r.w.r2 = 60
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(60, claimTaps(r.w).size)
        assertEquals(mapOf("claims" to 60), r.skill.lastCounts)
        assertNothingForbidden(r.w)
    }

    /** 10.5, the conductor's reading: the pair again until a round leaves nothing yellow. */
    @Test
    fun `row 2 turning yellow again while row 1 is claimed is claimed in a second round`() {
        val r = Rig()
        r.w.r2 = 2
        r.w.r1 = 3
        r.w.afterClaim = { if (r1Claims == 1 && claimsMade == 3) r2 += 1 }
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("claim r2", "claim r2", "claim r1", "claim r1", "claim r1", "claim r2"), claimTaps(r.w))
        assertTrue(r.said("ex missions: row 2 is yellow again -- round 2"), "${r.log}")
        assertTrue(r.said("ex missions: the window is clean (6 claimed)"), "${r.log}")
        assertEquals(0, r.w.listYellow() + r.w.r1)
        assertNothingForbidden(r.w)
    }

    // ------------------------------------------------------------------------
    // The Claim's proof and its end (3.3)
    // ------------------------------------------------------------------------
    @Test
    fun `a Claim that twice changes nothing ends its row and keeps the frame`() {
        val r = Rig()
        r.w.r2 = 2
        r.w.noEffect = 2
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("claim r2 (no effect)", "claim r2 (no effect)"), claimTaps(r.w))
        assertEquals(listOf("ex_no_effect"), r.kept)
        assertEquals(mapOf("claims" to 0), r.skill.lastCounts)
        assertEquals(2, r.w.r2, "nothing was claimed")
        assertTrue(r.said("ex missions: done with 0 claimed; not clean"), "${r.log}")
        assertEquals("close", r.w.taps.last())
        assertNothingForbidden(r.w)
    }

    /**
     * A work that ends like that holds the EX tab back from the director for
     * NO_EFFECT_HOLD, or the same two taps and a kept frame would come every
     * few seconds while the row reads yellow.
     */
    @Test
    fun `after a work whose Claim did nothing the tab is not seen as work for a while`() {
        val r = Rig()
        r.w.screen = MissionsWorld.Screen.WINDOW
        r.w.tab = Missions.EX
        r.w.r2 = 2
        r.w.noEffect = 2
        val img = r.w.grab()
        assertEquals(true, r.skill.seesWork(Director.EX_MISSIONS, img))
        assertEquals(Result.DONE, r.skill.work(img).result)
        assertEquals(false, r.skill.seesWork(Director.EX_MISSIONS, img), "held")
        r.w.t += ExMissionsSkill.NO_EFFECT_HOLD
        assertEquals(true, r.skill.seesWork(Director.EX_MISSIONS, img), "and let go")
        img.release()
    }

    @Test
    fun `the switch between two Claims stops the pass with no tap after it`() {
        val r = Rig()
        r.w.r2 = 5
        r.w.afterClaim = { if (claimsMade == 2) on = false }
        val out = r.skill.run()
        assertEquals(Result.STOPPED, out.result)
        assertEquals(listOf("tile", "tab ex", "claim r2", "sheet", "claim r2"), r.w.taps)
        // The second Claim's sheet is its witness, and it is left standing.
        assertEquals(mapOf("claims" to 2), r.skill.lastCounts)
        assertEquals(listOf("clear 0.145-0.195", "back"), r.w.overlay)
        assertNothingForbidden(r.w)
    }

    /**
     * Two pauses in one chain step (PLAN_RELEASE_1_3.md B4): five claims on
     * row 2 and two on row 1. The switch goes off after the second Claim --
     * the pass stops on its Reward sheet, the director's `unknown` -- and
     * again after the fifth, on the EX tab. Each time the pass goes on where
     * it stands and claims what reads yellow; seven claims in all, each once,
     * the card's parts adding up to seven, and home at the end.
     */
    @Test
    fun `EX Missions paused twice claims each once and goes home`() {
        val r = Rig()
        r.w.r2 = 5
        r.w.r1 = 2
        r.w.afterClaim = { if (claimsMade == 2) on = false }
        val claims = ArrayList<Int>()
        assertEquals(Result.STOPPED, r.skill.run().result)
        claims += r.skill.lastCounts["claims"] ?: 0
        assertEquals(MissionsWorld.Screen.SHEET, r.w.screen)
        r.w.t += 1.5
        val sheet = r.w.grab()
        assertTrue(r.skill.resumesOn(Director.classify(sheet).screen, sheet), "the sheet is the pass's own")

        r.w.on = true
        r.w.afterClaim = { if (claimsMade == 5) on = false }
        assertEquals(Result.STOPPED, r.skill.resume(sheet, whole = true).result)
        claims += r.skill.lastCounts["claims"] ?: 0
        r.w.t += 1.5
        val tab = r.w.grab()
        assertTrue(r.skill.resumesOn(Director.classify(tab).screen, tab), "${Director.classify(tab).screen}")

        r.w.on = true
        r.w.afterClaim = {}
        val out = r.skill.resume(tab, whole = true)
        assertEquals(Result.DONE, out.result, out.why)
        claims += r.skill.lastCounts["claims"] ?: 0
        assertEquals(7, r.w.claimsMade, "${r.w.taps}")
        assertEquals(7, claims.sum(), "the card's parts: $claims")
        assertEquals(7, r.w.taps.count { it.startsWith("claim") }, "a Claim tapped once each: ${r.w.taps}")
        assertTrue(r.w.taps.none { it == "while off" }, "${r.w.taps}")
        assertEquals(MissionsWorld.Screen.MAIN, r.w.screen, "home at the end of the step")
        assertNothingForbidden(r.w)
    }

    // ------------------------------------------------------------------------
    // The jump and the way back in (3.4, 10.7)
    // ------------------------------------------------------------------------
    @Test
    fun `thrown out to the main screen, the way in is walked again and row 2 goes on`() {
        val r = Rig()
        r.w.r2 = 5
        r.w.jumpOnR2 += 3
        val out = r.skill.run()
        assertEquals(Result.DONE, out.result, out.why)
        assertEquals(listOf("tile", "tab ex", "claim r2", "sheet", "claim r2", "sheet", "arrow r2",
                            "tile", "tab ex", "claim r2", "sheet", "claim r2", "sheet", "claim r2", "sheet",
                            "close"), r.w.taps)
        assertEquals(mapOf("claims" to 5), r.skill.lastCounts)
        assertTrue(r.said("ex missions: the game went to the main screen -- opening Missions again (1/5)"), "${r.log}")
        assertTrue(r.kept.isEmpty(), "${r.kept}")
        assertNothingForbidden(r.w)
    }

    @Test
    fun `five ways back in, and after the sixth jump the visit ends with no park`() {
        val r = Rig()
        r.w.r2 = 20
        r.w.jumpOnR2 += 1..20
        val out = r.skill.run()
        assertEquals(Result.DONE, out.result, out.why)
        assertEquals(1 + ExMissionsSkill.RE_ENTER, r.w.taps.count { it == "tile" })
        assertEquals(1 + ExMissionsSkill.RE_ENTER, r.w.taps.count { it == "arrow r2" })
        assertEquals(listOf("ex_reentered"), r.kept)
        assertEquals(mapOf("claims" to 0), r.skill.lastCounts)
        assertEquals(MissionsWorld.Screen.MAIN, r.w.screen)
        assertNothingForbidden(r.w)
    }

    /** 10.7: after a lost boss fight the jump lands on Stage Failed and its Growth Guide, never tapped. */
    @Test
    fun `a jump onto Stage Failed takes the banner away at the neutral spot and walks in again`() {
        val r = Rig()
        r.w.r2 = 3
        r.w.jumpOnR2 += 2
        r.w.jumpTo = MissionsWorld.Screen.STAGE_FAILED
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("tile", "tab ex", "claim r2", "sheet", "arrow r2", "neutral",
                            "tile", "tab ex", "claim r2", "sheet", "claim r2", "sheet", "close"), r.w.taps)
        assertEquals(mapOf("claims" to 3), r.skill.lastCounts)
        assertTrue(r.said("ex missions: the Stage Failed banner is up -- tapping it away"), "${r.log}")
        assertNothingForbidden(r.w)
    }

    /**
     * The frame that lied: row 2 read yellow, the tap met its blue arrow,
     * the list read blue after it -- a claim by its colour alone -- and the
     * main screen came. That claim is taken back and the way in walked again.
     */
    @Test
    fun `a claim counted by its colour and followed by the jump was the arrow, and is taken back`() {
        val r = Rig()
        r.w.r2 = 3
        r.w.jumpOnR2 += 2
        r.w.blueAfterArrow = true
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("tile", "tab ex", "claim r2", "sheet", "arrow r2",
                            "tile", "tab ex", "claim r2", "sheet", "claim r2", "sheet", "close"), r.w.taps)
        assertTrue(r.said("ex missions: that was a blue arrow, not a Claim (1 claimed)"), "${r.log}")
        assertEquals(3, r.w.claimsMade)
        assertEquals(mapOf("claims" to 3), r.skill.lastCounts, "the card says what the game gave")
        assertNothingForbidden(r.w)
    }

    /**
     * The main screen at a row's own look, with no claim of the task's to
     * explain it, is the window closed by somebody else -- the player, in
     * the semi-automatic mode -- and the task does not open it again.
     */
    @Test
    fun `a window the player closes is not opened again`() {
        val r = Rig()
        r.w.screen = MissionsWorld.Screen.WINDOW
        r.w.tab = Missions.EX
        r.w.r2 = 1
        r.w.r1 = 0
        // After the Claim's sheet is gone, while row 1 is being looked at.
        r.w.afterClaim = { playerClosesAt = t + 2.2 }
        val img = r.w.grab()
        assertEquals(Result.DONE, r.skill.work(img).result)
        img.release()
        assertEquals(listOf("claim r2", "sheet"), r.w.taps)
        assertEquals(MissionsWorld.Screen.MAIN, r.w.screen)
        assertTrue(r.said("ex missions: the window was closed, not by a tap of mine -- leaving it"), "${r.log}")
        assertEquals(mapOf("claims" to 1), r.skill.lastCounts)
        assertNothingForbidden(r.w)
    }

    @Test
    fun `a window that closes onto Stage Failed is followed home through the banner`() {
        val r = Rig()
        r.w.r2 = 1
        r.w.failedBehind = true
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("tile", "tab ex", "claim r2", "sheet", "close", "neutral"), r.w.taps)
        assertEquals(MissionsWorld.Screen.MAIN, r.w.screen)
        assertNothingForbidden(r.w)
    }

    // ------------------------------------------------------------------------
    // What does not open (3.2, points 1 to 3)
    // ------------------------------------------------------------------------
    @Test
    fun `a tile the frame does not show is not tapped, and nothing is counted`() {
        val r = Rig()
        r.w.tile = false
        val out = r.skill.run()
        assertEquals(Result.DONE, out.result)
        assertTrue(r.w.taps.isEmpty(), "${r.w.taps}")
        assertEquals(listOf("ex_no_tile"), r.kept)
        assertEquals(mapOf("claims" to 0), r.skill.lastCounts)
        assertEquals(listOf("clear 0.145-0.195", "back"), r.w.overlay)
    }

    @Test
    fun `a window that does not come is one tap on the tile and a kept frame`() {
        val r = Rig()
        r.w.opens = false
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("tile"), r.w.taps)
        assertEquals(listOf("missions_no_window"), r.kept)
        assertEquals(mapOf("claims" to 0), r.skill.lastCounts)
        assertNothingForbidden(r.w)
    }

    /** 10.3: the window opens on Collection, whose Claims stand in the EX rows' column. */
    @Test
    fun `Collection's Claims are never tapped, even when the EX tab will not come`() {
        val r = Rig()
        r.w.exTabWorks = false
        r.w.r2 = 3
        assertEquals(Result.DONE, r.skill.run().result)
        assertEquals(listOf("tile", "tab ex", "close"), r.w.taps)
        assertEquals(listOf("ex_no_tab"), r.kept)
        assertEquals(0, r.w.claimsMade)
        assertNothingForbidden(r.w)
    }

    @Test
    fun `a prompt nobody here raised is never answered`() {
        val r = Rig()
        r.w.promptOnTile = true
        val out = r.skill.run()
        assertEquals(Result.PARKED, out.result)
        assertEquals(listOf("tile"), r.w.taps)
        assertEquals(listOf("missions_no_window", "no_way_home"), r.kept)
        assertNothingForbidden(r.w)
    }

    @Test
    fun `a window that will not close is a park with the frame kept`() {
        val r = Rig()
        r.w.closes = false
        val out = r.skill.run()
        assertEquals(Result.PARKED, out.result)
        assertEquals("The Missions window did not close, and I found no way home from it.", out.why)
        assertEquals(listOf("tile", "tab ex", "close", "close", "close"), r.w.taps)
        assertEquals(listOf("no_way_home"), r.kept)
        assertNothingForbidden(r.w)
    }

    @Test
    fun `run starts from the main screen only`() {
        val r = Rig()
        r.w.screen = MissionsWorld.Screen.PROMPT
        val out = r.skill.run()
        assertEquals(Result.PARKED, out.result)
        assertEquals("EX Missions start from the main screen.", out.why)
        assertTrue(r.w.taps.isEmpty())
    }

    // ------------------------------------------------------------------------
    // The window the player opened (question 6), and the chain's second hand
    // ------------------------------------------------------------------------
    @Test
    fun `the EX tab handed over is claimed and left standing`() {
        val r = Rig()
        r.w.screen = MissionsWorld.Screen.WINDOW
        r.w.tab = Missions.EX
        r.w.r2 = 2
        r.w.r1 = 1
        val img = r.w.grab()
        assertTrue(r.skill.worksOn(Director.EX_MISSIONS))
        assertEquals(true, r.skill.seesWork(Director.EX_MISSIONS, img))
        assertEquals(Result.DONE, r.skill.work(img).result)
        img.release()
        assertEquals(listOf("claim r2", "sheet", "claim r2", "sheet", "claim r1", "sheet"), r.w.taps)
        assertEquals(MissionsWorld.Screen.WINDOW, r.w.screen)
        assertEquals(Missions.EX, r.w.tab)
        assertEquals(mapOf("claims" to 3), r.skill.lastCounts)
        val after = r.w.grab()
        assertEquals(false, r.skill.seesWork(Director.EX_MISSIONS, after), "clean: nothing to do")
        after.release()
        assertTrue(r.w.overlay.isEmpty(), "no tile read, the dot not moved: ${r.w.overlay}")
        assertNothingForbidden(r.w)
    }

    @Test
    fun `the Missions window handed over on Collection goes to EX Missions and claims there`() {
        val r = Rig()
        r.w.screen = MissionsWorld.Screen.WINDOW
        r.w.tab = Missions.COLLECTION
        r.w.r2 = 1
        val img = r.w.grab()
        assertTrue(r.skill.worksOn(Director.MISSIONS))
        assertNull(r.skill.seesWork(Director.MISSIONS, img), "not from a frame: the director's own rules")
        assertEquals(Result.DONE, r.skill.work(img).result)
        img.release()
        assertEquals(listOf("tab ex", "claim r2", "sheet"), r.w.taps)
        assertEquals(Missions.EX, r.w.tab)
        assertEquals(MissionsWorld.Screen.WINDOW, r.w.screen)
        assertNothingForbidden(r.w)

        // And when the tab will not come: EX Missions tapped, nothing else.
        val s = Rig()
        s.w.screen = MissionsWorld.Screen.WINDOW
        s.w.tab = Missions.COLLECTION
        s.w.exTabWorks = false
        s.w.r2 = 1
        val collection = s.w.grab()
        assertEquals(Result.DONE, s.skill.work(collection).result)
        collection.release()
        assertEquals(listOf("tab ex"), s.w.taps)
        assertEquals(listOf("ex_no_tab"), s.kept)
        assertNothingForbidden(s.w)
    }

    /**
     * 10.11, the conductor's decision: the window never opens on Daily
     * Missions, so a window standing there is a player claiming their
     * dailies by hand. Not the task's, from a frame -- and not in `work`
     * either, should the tab change between the director's look and it.
     */
    @Test
    fun `the window on Daily stays standing, even with yellow Claims there`() {
        val r = Rig()
        r.w.screen = MissionsWorld.Screen.WINDOW
        r.w.tab = Missions.DAILY
        r.w.r2 = 2
        val daily = r.w.grab()
        assertTrue(Missions.claimCandidates(daily).isNotEmpty(), "Daily's own yellow Claims are painted")
        assertEquals(Director.MISSIONS, Director.classify(daily).screen)
        assertEquals(false, r.skill.seesWork(Director.MISSIONS, daily))
        // The tab the window opens on is still the director's to hand over.
        r.w.tab = Missions.COLLECTION
        val collection = r.w.grab()
        assertNull(r.skill.seesWork(Director.MISSIONS, collection))
        collection.release()
        // Handed over on Collection, and Daily lit by the time the work looks.
        r.w.tab = Missions.DAILY
        assertEquals(Result.DONE, r.skill.work(daily).result)
        daily.release()
        assertTrue(r.w.taps.isEmpty(), "${r.w.taps}")
        assertTrue(r.said("ex missions: the Missions window is on Daily Missions -- that is yours, nothing tapped"),
                   "${r.log}")
        assertEquals(Missions.DAILY, r.w.tab)
        assertEquals(mapOf("claims" to 0), r.skill.lastCounts)
    }

    @Test
    fun `leave closes the window from either tab`() {
        for (tab in listOf(Missions.EX, Missions.COLLECTION)) {
            val r = Rig()
            r.w.screen = MissionsWorld.Screen.WINDOW
            r.w.tab = tab
            r.w.r2 = 2
            assertTrue(r.skill.leave(), "tab $tab")
            assertEquals(listOf("close"), r.w.taps)
            assertEquals(MissionsWorld.Screen.MAIN, r.w.screen)
            assertEquals(0, r.w.claimsMade, "leave claims nothing")
        }
    }

    // ------------------------------------------------------------------------
    // The TODAY card (3.5)
    // ------------------------------------------------------------------------
    @Test
    fun `two passes put their own claims on the card, not the first twice`() {
        val r = Rig()
        val store = MapSettings()
        val counted = Counted(r.skill, { r.skill.lastCounts }) { key, c -> SkillStats.add(store, key, c, 1L) }
        r.w.r2 = 3
        assertEquals(Result.DONE, counted.run().result)
        assertEquals(mapOf("claims" to 3), r.skill.lastCounts)
        r.w.r2 = 2
        assertEquals(Result.DONE, counted.run().result)
        assertEquals(mapOf("claims" to 2), r.skill.lastCounts, "the second pass's own")
        assertEquals(mapOf("claims" to 5), SkillStats.read(store, "exmissions", 1L))
        assertEquals("5 EX mission claims.", SkillStats.sentence("exmissions", mapOf("claims" to 5)))
        assertEquals("1 EX mission claim.", SkillStats.sentence("exmissions", mapOf("claims" to 1)))
        // A pass with nothing to claim adds nothing.
        assertEquals(Result.DONE, counted.run().result)
        assertEquals(mapOf("claims" to 5), SkillStats.read(store, "exmissions", 1L))
    }

    // ------------------------------------------------------------------------
    // The page, the chain, the lock (questions 1, 2, 5, 7)
    // ------------------------------------------------------------------------
    @Test
    fun `Claim now runs the task from the next main screen`() {
        val w = MissionsWorld()
        w.r2 = 2
        val log = ArrayList<String>()
        val skill = ExMissionsSkill(w, log = { log += it }, on = { w.on }, sleep = { w.t += it }, now = { w.t })
        val d = DirectorLoop(w, listOf(skill), emptyList(), { MissionsWorld.GAME }, { SkillSettings.MODE_SEMI },
                             Chain(emptyList(), log = {}), on = { w.on }, log = { log += it }, now = { w.t })
        d.runNow = ExMissionsSkill.KEY
        val t = d.tick()
        assertEquals("run exmissions", t.did, "$t")
        assertNull(d.runNow)
        assertTrue(log.contains("running EX Missions now, as asked"), "$log")
        assertEquals(2, w.claimsMade)
        assertEquals(MissionsWorld.Screen.MAIN, w.screen)
        assertNothingForbidden(w)
        d.close()
    }

    @Test
    fun `the page is the button, and the chain offers the step without making it a default`() {
        val page = SkillSettings.page(ExMissionsSkill.KEY)
        assertTrue(page.supporter, "a supporter's task (question 5)")
        val button = page.fields.single() as SkillSettings.RunNow
        assertEquals(ExMissionsSkill.KEY, button.task)
        assertEquals("Claim now", button.label)
        assertTrue(ExMissionsSkill.KEY in SkillSettings.CHAINABLE)
        assertTrue(ExMissionsSkill.KEY !in SkillSettings.CHAIN_DEFAULT, "question 7")
        assertEquals("EX Missions", SkillSettings.CHAIN_NAMES[ExMissionsSkill.KEY])
        assertEquals("EX Missions", ExMissionsSkill(MissionsWorld()).name)
        assertTrue(ExMissionsSkill(MissionsWorld()).hasBudget(), "no clock (question 2)")
    }

    /**
     * Question 5: a supporter's task. The lock is the app's row, which asks
     * core's Paywall since 2026-10-02 (PaywallTest holds the list to it), so
     * what core can prove is the row as written, the table, and what the
     * director does with a task that is not included: the button is
     * refused, the windows are nobody's, the chain skips the step.
     */
    @Test
    fun `without a supporter code the task does nothing anywhere`() {
        val repo = File(System.getProperty("digiautotap.repo") ?: "..")
        val skills = File(repo, "app/src/main/kotlin/io/github/digipr1me/digiautotap/Skills.kt").readText()
        assertTrue(Regex("""SkillRow\("exmissions",\s*"EX Missions",\s*"exmissions",""")
                       .containsMatchIn(skills), "the EX Missions row is not in Skills.kt")
        assertTrue(Paywall.needsCode(ExMissionsSkill.KEY), "the EX Missions row is not a supporter's")

        for (mode in listOf(SkillSettings.MODE_SEMI, SkillSettings.MODE_FULL)) {
            val w = MissionsWorld()
            w.r2 = 3
            val log = ArrayList<String>()
            val skill = ExMissionsSkill(w, log = { log += it }, on = { w.on }, sleep = { w.t += it }, now = { w.t })
            val chain = Chain(listOf(Chain.Step(ExMissionsSkill.KEY)), log = { log += it })
            val d = DirectorLoop(w, listOf(skill), emptyList(), { MissionsWorld.GAME }, { mode }, chain,
                                 included = { false }, on = { w.on }, log = { log += it }, now = { w.t })
            d.runNow = ExMissionsSkill.KEY
            repeat(3) { w.t += d.tick().beat }
            assertTrue(log.any { "asked to run exmissions now, but that task is switched off or locked" in it }, "$log")
            if (mode == SkillSettings.MODE_FULL) {
                assertEquals(listOf(ExMissionsSkill.KEY to "not included"), chain.skipped)
            } else {
                // The window the player opens: nobody's, and no park either.
                w.screen = MissionsWorld.Screen.WINDOW
                w.tab = Missions.EX
                repeat(6) { w.t += d.tick().beat }
                assertNull(d.parked)
            }
            assertTrue(w.taps.isEmpty(), "$mode: ${w.taps}")
            d.close()
        }
    }
}
