package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Mat
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Lost Sector Tower (PLAN_DAILY_LOST_SECTOR_PRESETS.md 4.4, DL4) on a
 * game played by a clock, every frame painted (PaintLostSector) and read by
 * the **real** readers -- LostSector.page, subjugate, maxFloor, dimmedEnter,
 * Dungeon.recognise, autoButton, Summon.exitButton -- so that the test
 * proves the loop and the readers together, as IdleSkillTest does.
 *
 * What the world does is DL1a's measurement where there is one (4.1 point 4,
 * the player's account on the highest floor): the Crests tile opens the
 * Crests page, Enter raises the panel, Subjugate there answers with the
 * toast 1.4 s later for about a second and the panel stays, the back key
 * closes the panel to the page and the page to the main screen, the X goes
 * home. Everything about a **run** -- the battle, the Reward sheet, a Results
 * window, a level-up, a run that ends on the page -- is played the way the
 * daily dungeon's run is (DungeonSkillTest group 11) and was never seen at
 * the tower: those cases prove the skill, not the game.
 */
class LostSectorSkillTest {

    /**
     * The game, as a Capture. A frame costs [PACE] of the clock, as the
     * service's own floor does; a gesture sent while the switch is off is
     * withheld, as `DigiAutotapService.withheld` withholds it.
     */
    private class Tower(
        /** What each Subjugate does, in order; the last stands for every one after it. */
        val ends: List<String> = listOf(MAX),
        /** The battle, VS to the fade-out. */
        val battle: Double = 20.0,
        start: String = MAIN,
        val w: Int = Paint.W,
        val h: Int = Paint.H,
        val headroom: Int = 0,
        /** The black between Subjugate and VS, and the VS screen, seconds. */
        val black: Double = 1.5,
        val vs: Double = 1.0,
        /** The game's title, a real frame, where a run ends in [TITLE_END]. */
        val title: Mat? = null,
    ) : Capture {
        var t = 0.0
        var screen = start
        private var since = 0.0
        var switchOn = true
        /** The switch goes off as this battle begins (1-based), or never. */
        var offInRun = 0
        var iconWorks = true
        /** A panel of the tower's DL1a never saw after Enter: "price" or "button" (PaintLostSector.panel). */
        var extra: String? = null
        var presses = 0
        var battles = 0
        private var end = MAX
        private var beforePrompt = MAIN
        val messages = ArrayList<String>()
        /** Every tap: the place it hit, the screen it landed on, and when. */
        val taps = ArrayList<Triple<String, String, Double>>()
        /** The screen every back key landed on. */
        val backs = ArrayList<String>()
        var withheld = 0
        val kept = ArrayList<String>()
        val levelUpStood = ArrayList<Double>()
        /** How long the Crests page had stood when Enter was tapped on it. */
        val pageStood = ArrayList<Double>()
        private var phase = 0
        private val cache = HashMap<String, Mat>()
        private val ref: Mat = PaintLostSector.frame(w, h, headroom)

        private fun go(s: String, at: Double = t) {
            screen = s
            since = at
        }

        /** The clock's transitions, each at its own moment, however far [t] has run on. */
        private fun advance() {
            while (true) {
                val next: Pair<String, Double> = when (screen) {
                    "to_page" -> "page" to 0.8
                    "opening" -> (if (extra != null) "strange" else "panel") to 0.5
                    "toast_wait" -> "toast" to 1.4
                    "toast" -> "panel" to 1.0
                    "quick" -> "panel" to 1.0
                    "starting" -> "vs" to black
                    "vs" -> "battle" to vs
                    "battle" -> when (end) {
                        WON, LEVELUP -> "sheet"
                        RESULTS -> "results"
                        // Back on the game's title in the run, and there it stays.
                        TITLE_END -> "realtitle"
                        else -> "loading"
                    } to battle
                    "loading" -> when (end) {
                        PAGE_END -> "page"
                        STRANGE_END -> "strange"
                        DOWNLOAD_END -> "download"
                        else -> "panel"
                    } to 1.4
                    else -> return
                }
                if (t - since < next.second) return
                if (next.first == "battle") {
                    battles += 1
                    if (battles == offInRun) switchOn = false
                }
                if (next.first == "strange") extra = extra ?: "price"
                go(next.first, since + next.second)
            }
        }

        private fun paint(key: String): Mat {
            val f = PaintLostSector.frame(w, h, headroom)
            return when (key) {
                MAIN -> PaintLostSector.mainScreen(f)
                "page" -> PaintLostSector.page(f)
                "panel", "toast_wait" -> PaintLostSector.panel(f)
                "toast" -> PaintLostSector.panel(f, toast = true)
                "strange" -> PaintLostSector.panel(f, extra = extra)
                "results" -> PaintLostSector.results(f)
                "sheet" -> PaintQuest.rewardSheet()
                "battle0", "battle1", "battle2" -> Paint.dungeonBattle(key.last() - '0')
                "exit_prompt" -> Paint.prompt(pink = false)
                // The game's resource download: the exit prompt's face, a
                // message two lines tall (Dungeon.KIND_DOWNLOAD).
                "download" -> Paint.prompt(pink = false, lines = 2)
                // A level-up the game might raise after a won run: nobody's screen.
                "levelup" -> PaintLostSector.frame(w, h, headroom, 70.0)
                // The game's title, read by the real Startup.title.
                "realtitle" -> title!!.clone()
                // The tap's black, VS, "Now Loading", the page coming up: unknown
                // to `recognise`, and black to Dungeon.blackFrame since B6.
                else -> PaintLostSector.frame(w, h, headroom, 5.0)
            }
        }

        fun frame(): Mat {
            val key = if (screen == "battle") "battle${phase++ % 3}" else if (screen == "strange") "strange$extra" else screen
            val img = cache.getOrPut(key) { paint(if (screen == "strange") "strange" else key) }
            val out = PaintLostSector.frame(img.cols(), img.rows(), if (img.cols() == w && img.rows() == h) headroom else 0)
            img.copyTo(out)
            return out
        }

        override fun grab(): Mat {
            t += PACE
            advance()
            return frame()
        }

        override fun tap(x: Int, y: Int) {
            advance()
            if (!switchOn) {
                withheld += 1
                return
            }
            val b = Dungeon.gameRect(ref, Dungeon.Anchor.BOTTOM)
            val m = Dungeon.gameRect(ref, LostSector.PAGE)
            val bx = (x - b.x0) / b.gw.toDouble()
            val by = (y - b.y0) / b.gh.toDouble()
            val mx = (x - m.x0) / m.gw.toDouble()
            val my = (y - m.y0) / m.gh.toDouble()
            fun near(px: Double, py: Double, at: Dungeon.Button) =
                abs(px - at.fx) <= at.fw / 2 && abs(py - at.fy) <= at.fh / 2
            val place = when {
                screen == "results" && near(mx, my, CLOSE) -> "Close"
                screen == "exit_prompt" || screen == "download" -> if (bx < 0.5) "Cancel" else "OK"
                near(bx, by, ICON) -> "icon"
                near(mx, my, PaintLostSector.PILL) -> "Enter"
                near(mx, my, PaintLostSector.SUBJUGATE) -> "Subjugate"
                near(bx, by, X) -> "X"
                abs(by - DungeonSkill.NEUTRAL_TAP_Y) <= 0.02 -> "neutral"
                else -> "elsewhere %.3f,%.3f".format(bx, by)
            }
            taps += Triple(place, screen, t)
            when (screen) {
                MAIN -> if (place == "icon" && iconWorks) go("to_page")
                "page" -> when (place) {
                    "Enter" -> {
                        pageStood += t - since
                        go("opening")
                    }
                    "X" -> go(MAIN)
                }
                "panel" -> if (place == "Subjugate") press()
                "sheet" -> go(if (end == LEVELUP) "levelup" else "loading")
                "levelup" -> if (place == "neutral") {
                    levelUpStood += t - since
                    go("loading")
                }
                "results" -> if (place == "Close") go("loading")
                "exit_prompt" -> if (place == "Cancel") go(beforePrompt) else go("title")
                // Cancel ends the game, OK starts the download.
                "download" -> go(if (place == "Cancel") "closed" else "loading")
            }
        }

        private fun press() {
            presses += 1
            end = ends[minOf(presses, ends.size) - 1]
            when (end) {
                MAX -> go("toast_wait")
                NONE -> {}
                QUICK -> go("quick")
                else -> go("starting")
            }
        }

        override fun back() {
            advance()
            if (!switchOn) {
                withheld += 1
                return
            }
            backs += screen
            when (screen) {
                "panel", "toast", "toast_wait", "strange" -> go("page")
                "page" -> go(MAIN)
                MAIN -> {
                    beforePrompt = MAIN
                    go("exit_prompt")
                }
            }
        }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun inFront(): String? = GAME

        fun count(place: String) = taps.count { it.first == place }
        val said: String get() = messages.joinToString("\n")

        /** The wall clock: [EPOCH0] plus the sim's own seconds. */
        fun wall(): Double = EPOCH0 + t

        /**
         * The skill on this world. Without [store] its settings are the
         * minutes alone and no day is ever over; with one they are read out
         * of it as the app reads them (Stored.lostSector, on [wall]) and the
         * day at the highest floor is written into it (Stored.retireLostSector).
         */
        fun skill(minutes: Int = 1, store: MapSettings? = null): LostSectorSkill {
            store?.put(Stored.LOST_SECTOR_MINUTES_KEY, minutes)
            return LostSectorSkill(
                this, { if (store == null) LostSectorSkill.Settings(minutes) else Stored.lostSector(store, wall()) },
                log = { messages += it }, on = { switchOn }, keep = { _, tag -> kept += tag },
                sleep = { t += it }, now = { t += 1e-4; t },
                retire = { until -> store?.let { Stored.retireLostSector(it, until) } }, wall = { wall() })
        }

        companion object {
            const val MAX = "max"
            const val NONE = "none"
            const val QUICK = "quick"
            const val LOST = "lost"
            const val WON = "won"
            const val RESULTS = "results"
            const val LEVELUP = "levelup"
            const val PAGE_END = "page_end"
            const val STRANGE_END = "strange_end"
            /** The run over, the game back from a restart with its download dialog (PLAN_RELEASE_1_3.md B1). */
            const val DOWNLOAD_END = "download_end"
            /** The game back on its title in the run (PLAN_RELEASE_1_3.md B30). */
            const val TITLE_END = "title_end"
            /** The service's floor between two screenshots (notes/director.md, "takeScreenshot has a floor"). */
            const val PACE = 0.35
            val ICON = Dungeon.Button(LostSector.CRESTS_ICON.fx, LostSector.CRESTS_ICON.fy, 0.076, 0.043)
            val X = Dungeon.Button(Summon.EXIT_BUTTON_FX, Summon.EXIT_BUTTON_FY, 0.07, 0.04)
            val CLOSE = Dungeon.Button(0.503, 0.792, 0.216, 0.05)
            /** 2026-09-29 10:00 in Vienna: the game's day ends at 08:00 the next morning. */
            val EPOCH0: Double = ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, ZoneId.of("Europe/Vienna"))
                .toEpochSecond().toDouble()
            val RESET: Double = QuestSkill.nextReset(EPOCH0)
        }
    }

    /**
     * What holds on every pass, whatever it met: the tile only on the main
     * screen, Enter only on the Crests page, Subjugate only on the panel it
     * read -- never into the toast, a transition, a battle or a panel it did
     * not know -- the back key only on the panel, and never an OK.
     */
    private fun assertTheRules(g: Tower) {
        val said = g.said
        println(said + "\n  taps " + g.taps.map { "${it.first}@${it.second} %.1f".format(it.third) } +
                "\n  backs " + g.backs + "\n  kept " + g.kept + "\n")
        assertTrue(g.taps.filter { it.first == "icon" }.all { it.second == MAIN }, "${g.taps}\n$said")
        assertTrue(g.taps.filter { it.first == "Enter" }.all { it.second == "page" }, "${g.taps}\n$said")
        assertTrue(g.taps.filter { it.first == "Subjugate" }.all { it.second == "panel" }, "${g.taps}\n$said")
        assertTrue(g.taps.none { it.second in NO_TAP }, "a tap into ${g.taps.filter { it.second in NO_TAP }}\n$said")
        assertTrue(g.taps.none { it.first.startsWith("elsewhere") }, "a tap on nothing: ${g.taps}\n$said")
        assertTrue(g.taps.none { it.first == "OK" }, "${g.taps}")
        assertTrue(g.backs.all { it in setOf("panel", "toast", "toast_wait") }, "a back key off the panel: ${g.backs}\n$said")
    }

    @Test
    fun `the painted frames read as the real ones do`() {
        val page = PaintLostSector.page()
        val panel = PaintLostSector.panel()
        val toast = PaintLostSector.panel(toast = true)
        val price = PaintLostSector.panel(extra = "price")
        val button = PaintLostSector.panel(extra = "button")
        val results = PaintLostSector.results()
        val main = PaintLostSector.mainScreen()
        try {
            // The Crests page: the lit pill and the X, the director's LOST_SECTOR.
            val pill = assertNotNull(LostSector.page(page))
            assertTrue(abs(pill.fx - 0.331) < 0.005 && abs(pill.fy - 0.861) < 0.005, "$pill")
            assertEquals(Director.LOST_SECTOR, Director.classify(page).screen)
            assertNull(LostSector.subjugate(page))
            // The panel: Subjugate over the dimmed pill, a dialog to recognise and the director.
            val subjugate = assertNotNull(LostSector.subjugate(panel))
            assertTrue(abs(subjugate.fx - 0.476) < 0.005 && abs(subjugate.fy - 0.7917) < 0.005, "$subjugate")
            assertNotNull(LostSector.dimmedEnter(panel))
            assertNull(LostSector.page(panel))
            assertNull(LostSector.maxFloor(panel))
            assertEquals(Dungeon.DIALOG, Dungeon.recognise(panel).state)
            assertEquals(Director.DIALOG, Director.classify(panel).screen)
            // The toast over it: the highest floor, and the panel still under it.
            assertNotNull(LostSector.maxFloor(toast))
            assertNotNull(LostSector.subjugate(toast))
            // A panel DL1a never saw: a dialog over the dimmed pill that subjugate refuses.
            for (strange in listOf(price, button)) {
                assertNull(LostSector.subjugate(strange))
                assertNotNull(LostSector.dimmedEnter(strange))
                assertTrue(Dungeon.recognise(strange).state in DungeonSkill.DIALOGS)
            }
            // A window with a narrow Close, and the main screen.
            val close = assertNotNull(Dungeon.recognise(results).attempt)
            assertTrue(close.fw <= DungeonSkill.CLOSE_W_MAX, "$close")
            assertNull(LostSector.subjugate(results))
            assertNotNull(Dungeon.autoButton(main))
            assertEquals(Director.MAIN, Director.classify(main).screen)
        } finally {
            Paint.release(page, panel, toast, price, button, results, main)
        }
    }

    /**
     * The player's own case (G4, question 18 with the proposal): on the
     * highest floor Subjugate answers with the toast, which ends the pass --
     * one Subjugate, no second, no run counted -- and the way home is the
     * one DL1a walked: the back key to the page, the X to the main screen.
     * The pass retires the tower until the game's reset (question 22, the
     * proposal) and writes that reset down.
     */
    @Test
    fun `at the highest floor the toast ends the pass and nothing more is tapped`() {
        val g = Tower(listOf(Tower.MAX))
        val store = MapSettings()
        val s = g.skill(minutes = 5, store = store)
        val out = s.run()
        val said = g.said
        val until = "until " + QuestSkill.whenText(Tower.RESET)
        assertEquals(Result.RETIRED, out.result, said)
        assertEquals("the tower is at its highest floor -- nothing to subjugate $until", out.why)
        assertEquals(listOf("icon", "Enter", "Subjugate", "X"), g.taps.map { it.first }, said)
        assertEquals(1, g.backs.size, "one back key, on the panel: ${g.backs}\n$said")
        assertEquals(1, g.presses)
        assertEquals(MAIN, g.screen)
        assertTrue(g.messages.contains("  the tower is at its highest floor -- nothing to subjugate $until"), said)
        assertEquals(Tower.RESET, store.num(Stored.LOST_SECTOR_DONE_UNTIL_KEY, 0.0))
        assertEquals(5.0, store.num(Stored.LOST_SECTOR_DONE_MINUTES_KEY, 0.0))
        assertFalse(s.hasBudget(), "the tower's day is over")
        assertTrue(g.messages.contains("  back on the Crests page"), said)
        assertTrue(g.messages.contains("  back on the main screen"), said)
        assertEquals(mapOf(LostSectorSkill.CARD_RUNS to 0, LostSectorSkill.CARD_WON to 0), s.lastCounts)
        assertTrue(g.kept.isEmpty(), "${g.kept}")
        assertTheRules(g)
    }

    /**
     * The clock (4.3's "die Uhr" at the tower): runs of about 25 s at one
     * minute -- Subjugate before each, none after the minute, and the run
     * that crosses it is waited out (the minutes are a floor, question 10).
     */
    @Test
    fun `the minutes are up between two runs, and the last run is waited out`() {
        val g = Tower(listOf(Tower.LOST))
        val s = g.skill(minutes = 1)
        val out = s.run()
        val said = g.said
        assertEquals(Result.DONE, out.result, said)
        val presses = g.taps.filter { it.first == "Subjugate" }
        assertTrue(presses.size >= 2, said)
        assertTrue(presses.last().third - presses.first().third < 60.0, "a Subjugate after the minute: $presses")
        assertEquals(presses.size, g.battles, "every run begun was played out\n$said")
        assertTrue(g.messages.any { it.startsWith("  the minutes are up after ${presses.size} runs") }, said)
        assertEquals(presses.size, s.lastCounts[LostSectorSkill.CARD_RUNS])
        assertEquals(0, s.lastCounts[LostSectorSkill.CARD_WON])
        assertEquals(presses.size, g.messages.count { it == "  run lost, back without a Reward sheet" }, said)
        assertEquals(0, g.count("neutral"), "a transition was tapped: ${g.taps}")
        assertEquals(MAIN, g.screen)
        assertTheRules(g)
    }

    /** A won run: the Reward sheet read and tapped away, and counted won. Sim only. */
    @Test
    fun `a won run is counted when the Reward sheet came, and the sheet is tapped away`() {
        val g = Tower(listOf(Tower.WON, Tower.LOST))
        val s = g.skill(minutes = 1)
        assertEquals(Result.DONE, s.run().result, g.said)
        assertEquals(1, g.taps.count { it.second == "sheet" }, "${g.taps}")
        assertEquals(1, s.lastCounts[LostSectorSkill.CARD_WON])
        assertEquals(g.battles, s.lastCounts[LostSectorSkill.CARD_RUNS])
        assertTrue(g.messages.contains("  run won, the Reward sheet came"), g.said)
        assertTheRules(g)
    }

    /**
     * What a run might end in besides the panel (question 8: close it and go
     * on): a Results window by its narrow Close, and a level-up nobody names,
     * tapped high up only once it has stood UNKNOWN_HOLD. Sim only.
     */
    @Test
    fun `a results window and a level-up after a run are closed and the run counted once`() {
        val g = Tower(listOf(Tower.RESULTS, Tower.LEVELUP, Tower.LOST))
        val s = g.skill(minutes = 1)
        assertEquals(Result.DONE, s.run().result, g.said)
        assertEquals(1, g.count("Close"), "${g.taps}")
        assertTrue(g.messages.contains("  a window after the run, closing it"), g.said)
        assertEquals(1, g.levelUpStood.size, "${g.taps}\n${g.said}")
        assertTrue(g.levelUpStood.all { it >= LostSectorSkill.UNKNOWN_HOLD }, "${g.levelUpStood}")
        assertEquals(g.battles, s.lastCounts[LostSectorSkill.CARD_RUNS])
        assertEquals(1, s.lastCounts[LostSectorSkill.CARD_WON], "the level-up came after a sheet")
        assertTheRules(g)
    }

    /** A run that ends on the Crests page: Enter once more, after the hold, once a run. Sim only. */
    @Test
    fun `a run that ends on the Crests page enters the tower once more`() {
        val g = Tower(listOf(Tower.PAGE_END, Tower.LOST))
        val s = g.skill(minutes = 1)
        assertEquals(Result.DONE, s.run().result, g.said)
        assertEquals(1, g.messages.count { it == "  back on the Crests page, entering the tower once more" }, g.said)
        assertEquals(2, g.count("Enter"), "Enter on the way in, once more after the run, and no more: ${g.taps}")
        assertTrue(g.pageStood[1] >= LostSectorSkill.UNKNOWN_HOLD, "the page held before Enter: ${g.pageStood}")
        assertTrue(g.battles >= 2, g.said)
        assertTheRules(g)
    }

    /**
     * The switch between two runs and nowhere else: off as the first battle
     * begins, the run is waited out and counted, and no second Subjugate --
     * STOPPED, and the panel left where it stands (every gesture would be
     * withheld now).
     */
    @Test
    fun `the switch ends the pass between two runs, not inside one`() {
        val g = Tower(listOf(Tower.LOST), start = "page")
        g.offInRun = 1
        val s = g.skill(minutes = 5)
        val out = s.work(g.grab())
        val said = g.said
        assertEquals(Result.STOPPED, out.result, said)
        assertEquals(1, g.count("Subjugate"), said)
        assertEquals(1, g.battles)
        assertEquals(1, s.lastCounts[LostSectorSkill.CARD_RUNS], "the run begun was waited out and counted\n$said")
        assertTrue(g.messages.contains("  stopped"), said)
        assertEquals(0, g.withheld, "nothing was tried with the switch off")
        assertEquals(true, s.seesWork(Director.LOST_SECTOR, g.frame()), "a pass cut short leaves the page as work")
        assertTheRules(g)
    }

    /**
     * Two pauses in one pass (PLAN_RELEASE_1_3.md B4), two minutes on the
     * page: the switch goes off in the first run's battle, which is won -- the
     * pass stops on its Reward sheet -- and again in the second run's, which
     * is lost and comes back to the panel. Each time the pass goes on from
     * where it stands, with the minutes it has left: the paused pass plays as
     * many runs as one that was never paused, and the pauses, ten minutes
     * each, are not the player's minutes.
     */
    @Test
    fun `the Lost Sector Tower paused twice plays the minutes it has left, not the pauses`() {
        val straight = Tower(listOf(Tower.WON, Tower.LOST, Tower.WON), start = "page")
        val once = straight.skill(minutes = 2)
        assertEquals(Result.DONE, once.work(straight.grab()).result, straight.said)
        val runs = once.lastCounts[LostSectorSkill.CARD_RUNS]!!

        val g = Tower(listOf(Tower.WON, Tower.LOST, Tower.WON), start = "page")
        g.offInRun = 1
        val s = g.skill(minutes = 2)
        val counted = ArrayList<Int>()
        val first = s.work(g.grab())
        assertEquals(Result.STOPPED, first.result, g.said)
        counted += s.lastCounts[LostSectorSkill.CARD_RUNS] ?: 0
        assertEquals("sheet", g.screen, "the won run's sheet stands where the pause found it")
        g.t += 600.0
        val sheet = g.grab()
        assertTrue(s.resumesOn(Director.classify(sheet).screen, sheet), "the sheet is the pass's own")

        g.switchOn = true
        g.offInRun = g.battles + 1
        val second = s.resume(sheet, whole = false)
        assertEquals(Result.STOPPED, second.result, g.said)
        counted += s.lastCounts[LostSectorSkill.CARD_RUNS] ?: 0
        assertEquals("panel", g.screen, "the lost run came back to its panel by itself")
        g.t += 600.0
        val panel = g.grab()
        assertEquals(Director.DIALOG, Director.classify(panel).screen)
        assertTrue(s.resumesOn(Director.DIALOG, panel))

        g.switchOn = true
        g.offInRun = 0
        val third = s.resume(panel, whole = false)
        assertEquals(Result.DONE, third.result, g.said)
        counted += s.lastCounts[LostSectorSkill.CARD_RUNS] ?: 0
        assertEquals(runs, counted.sum(), "as many runs as a pass never paused: $counted against $runs\n${g.said}")
        assertEquals(counted.sum(), g.battles, g.said)
        assertEquals(0, g.withheld, "nothing was tried with the switch off")
        assertEquals("page", g.screen, "work ends on the page it was given")
        assertTrue(g.kept.none { it.startsWith("no_way") || it.startsWith("lost_sector_no") }, "${g.kept}")
        assertTheRules(g)
    }

    /** Subjugate that leaves the panel standing and shows no toast, twice: the pass ends with the frame kept. */
    @Test
    fun `a Subjugate without effect twice ends the pass with the frame kept`() {
        val g = Tower(listOf(Tower.NONE), start = "page")
        val s = g.skill(minutes = 5)
        val out = s.work(g.grab())
        assertEquals(Result.DONE, out.result, g.said)
        assertEquals(2, g.count("Subjugate"), g.said)
        assertEquals(2, g.messages.count { it == "  Subjugate had no effect" }, g.said)
        assertTrue(g.messages.contains("  Subjugate had no effect twice, leaving the tower"), g.said)
        assertTrue("lost_sector_no_effect" in g.kept, "${g.kept}")
        assertEquals("page", g.screen, "work ends on the page it was given")
        assertEquals(0, s.lastCounts[LostSectorSkill.CARD_RUNS])
        assertTheRules(g)
    }

    /** The panel back within a second or two is no run (MIN_BATTLE), and twice so in a row ends the pass. */
    /**
     * PLAN_RELEASE_1_3.md B1: the game's download dialog where the run
     * should come back, read by the real recognise on the painted prompt.
     * Its Cancel ends the game and its OK is the director's: the pass taps
     * nothing on it, sends no back key and takes no way home, and hands back
     * parked with the frame kept. Sim only: the dialog after a run was never
     * seen at the tower; it comes with the game's start after an update.
     */
    @Test
    fun `the game's download dialog after a run ends the pass, and nothing is tapped on it`() {
        val g = Tower(listOf(Tower.DOWNLOAD_END))
        val s = g.skill(minutes = 5)
        val out = s.run()
        assertEquals(Result.PARKED, out.result, g.said)
        assertEquals(DungeonSkill.DOWNLOAD_WHY, out.why)
        assertEquals("download", g.screen, "the dialog was answered: ${g.taps}")
        assertTrue(g.taps.none { it.second == "download" }, "${g.taps}")
        assertTrue(g.said.contains("  the game asks to download its data -- leaving it to the director, nothing tapped"),
                   g.said)
        assertEquals(listOf("download"), g.kept)
        assertTheRules(g)
    }

    /**
     * PLAN_RELEASE_1_3.md B6 (G12): the run's black and VS standing longer
     * than the hold -- 4.5 s each here, a black of 4 s seen live after a
     * dungeon run on 2026-09-29 -- are waited on as a battle is, and the tap
     * high up that went out on them after UNKNOWN_HOLD does not. The painted
     * black reads as Dungeon.blackFrame does on the real one.
     */
    @Test
    fun `a run's black and VS are waited on and never tapped`() {
        assertTrue(Dungeon.blackFrame(PaintLostSector.frame(Paint.W, Paint.H, 0, 5.0)), "the painted black")
        val g = Tower(listOf(Tower.LOST), black = 4.5, vs = 4.5, start = "page")
        val s = g.skill(minutes = 1)
        assertEquals(Result.DONE, s.work(g.grab()).result, g.said)
        assertTrue(g.battles >= 1, g.said)
        assertEquals(g.battles, s.lastCounts[LostSectorSkill.CARD_RUNS], g.said)
        assertEquals(0, g.count("neutral"), "${g.taps}")
        assertTrue(g.said.contains("a black frame -- a run's own transition, waited on and not tapped"), g.said)
        assertTrue(!g.said.contains("a screen nothing here reads has stood"), g.said)
        assertTheRules(g)
    }

    /**
     * PLAN_RELEASE_1_3.md B30: the game back on its title in the middle of a
     * run -- the real dusk title of 2026-09-30, read by the real
     * Startup.titleBar. A tap there is "Touch To Start", so nothing is
     * tapped, no way home is walked, the run is counted as nothing, and the
     * pass hands back parked with the frame kept.
     */
    @Test
    fun `the game's title in the middle of a run ends the pass, and nothing is tapped on it`() {
        titleEndsThePass(listOf("corpus/startup/title_dusk_185220.png", "staging/r2/live_title_dusk_185220.png"))
    }

    /**
     * PLAN_RELEASE_1_3.md B43: the same on the title's day picture (instance
     * 0, 2026-10-01 08:54, 900 x 1600), whose bar is not found and which is
     * read by its Menu button (Startup.title).
     */
    @Test
    fun `the game's title by day in the middle of a run ends the pass as well`() {
        titleEndsThePass(listOf("corpus/startup/title_day_085430.png", "staging/b6live/title_day_085430.png"))
    }

    private fun titleEndsThePass(places: List<String>) {
        val repo = java.io.File(System.getProperty("digiautotap.repo") ?: "..")
        if (!java.io.File(repo, "corpus").isDirectory) return
        val file = places.map { java.io.File(repo, it) }.firstOrNull { it.exists() }
        assertNotNull(file)
        val title = org.opencv.imgcodecs.Imgcodecs.imread(file.path)
        try {
            assertTrue(Startup.title(title), "the title reads as the title")
            val g = Tower(listOf(Tower.TITLE_END), title = title)
            val s = g.skill(minutes = 5)
            val out = s.run()
            assertEquals(Result.PARKED, out.result, g.said)
            assertEquals(DungeonSkill.TITLE_WHY, out.why)
            assertEquals("realtitle", g.screen)
            assertTrue(g.taps.none { it.second == "realtitle" }, "${g.taps}")
            assertTrue(g.said.contains("  the game is on its title screen -- the pass ends here, nothing tapped"), g.said)
            assertEquals(listOf("title"), g.kept)
            assertEquals(0, s.lastCounts[LostSectorSkill.CARD_RUNS], "the run's end was not seen\n${g.said}")
            assertTheRules(g)
        } finally {
            title.release()
        }
    }

    @Test
    fun `a run that does not start twice in a row ends the pass`() {
        val g = Tower(listOf(Tower.QUICK), start = "page")
        val s = g.skill(minutes = 5)
        assertEquals(Result.DONE, s.work(g.grab()).result, g.said)
        assertEquals(2, g.count("Subjugate"), g.said)
        assertEquals(0, g.battles)
        assertTrue(g.messages.contains("  no battle twice in a row, leaving the tower"), g.said)
        assertTrue("lost_sector_no_battle" in g.kept, "${g.kept}")
        assertEquals(0, s.lastCounts[LostSectorSkill.CARD_RUNS])
        assertTheRules(g)
    }

    /**
     * A panel with a price, or with a button DL1a never saw (3.2 point 7):
     * a park with its frame, no Subjugate, no back key -- the panel stays for
     * the player to look at, from the page as from the main screen, and
     * after a run as after Enter.
     */
    @Test
    fun `a panel with a price or another button parks and nothing is tapped on it`() {
        for (extra in listOf("price", "button")) {
            val g = Tower(start = "page")
            g.extra = extra
            val s = g.skill(minutes = 5)
            val out = s.work(g.grab())
            assertEquals(Result.PARKED, out.result, "$extra\n${g.said}")
            assertEquals(LostSectorSkill.STRANGE_PANEL, out.why)
            assertEquals(listOf("Enter"), g.taps.map { it.first }, "$extra: ${g.taps}")
            assertTrue(g.backs.isEmpty(), "$extra: ${g.backs}")
            assertTrue("lost_sector_unknown_panel" in g.kept, "$extra: ${g.kept}")
            assertTrue(s.seesWork(Director.LOST_SECTOR, PaintLostSector.page()) == true, "a park is no finished pass")
        }
        // From the main screen: the park, and the way home that finds no way.
        val g = Tower()
        g.extra = "price"
        val out = g.skill(minutes = 5).run()
        assertEquals(Result.PARKED, out.result, g.said)
        assertEquals(listOf("icon", "Enter"), g.taps.map { it.first }, "${g.taps}")
        assertTrue(g.backs.isEmpty(), "${g.backs}")
        assertEquals("strange", g.screen, "left standing for the player")
        assertTrue("no_way_home" in g.kept, "${g.kept}")
        // After a run: the next panel is not the one the tower had.
        val after = Tower(listOf(Tower.STRANGE_END), start = "page")
        val afterSkill = after.skill(minutes = 5)
        val afterOut = afterSkill.work(after.grab())
        assertEquals(Result.PARKED, afterOut.result, after.said)
        assertEquals(LostSectorSkill.STRANGE_PANEL, afterOut.why)
        assertEquals(1, after.count("Subjugate"))
        assertEquals(1, afterSkill.lastCounts[LostSectorSkill.CARD_RUNS], "the run happened")
        assertTrue(after.messages.contains("  the run did not come back to the tower's panel"), after.said)
        assertTrue(after.messages.none { it.startsWith("  run lost") }, after.said)
        assertTrue(after.backs.isEmpty(), "${after.backs}")
        assertTheRules(after)
    }

    /**
     * The tile is a constant, and what makes its tap safe is the frame it
     * goes out on: never off the plain main screen (4.4, "ein Icon, das
     * nicht liest, tippt nichts"), and a tile that opens nothing is tapped
     * three times, each on a fresh main-screen frame, and then parked with
     * the frame kept.
     */
    @Test
    fun `the tile is tapped only on the plain main screen`() {
        val g = Tower(start = "results")
        val out = g.skill(minutes = 5).run()
        assertEquals(Result.PARKED, out.result, g.said)
        assertEquals("Lost Sector Tower starts from the main screen.", out.why)
        assertTrue(g.taps.isEmpty(), "${g.taps}")
        assertTrue(g.backs.isEmpty(), "${g.backs}")
        assertTrue("lost_sector_not_main" in g.kept, "${g.kept}")

        val dead = Tower()
        dead.iconWorks = false
        val deadOut = dead.skill(minutes = 5).run()
        assertEquals(Result.PARKED, deadOut.result, dead.said)
        assertEquals("The Crests page did not open from the main screen.", deadOut.why)
        assertEquals(List(LostSectorSkill.NAV_TAPS_MAX) { "icon" }, dead.taps.map { it.first })
        assertTrue("lost_sector_no_page" in dead.kept, "${dead.kept}")
        assertEquals(MAIN, dead.screen)
        assertTheRules(dead)
    }

    /**
     * The semi-automatic mode's half: from the Crests page the director
     * hands over, to the same page (Skill.work) -- no X, no main screen -- and
     * the seam's three questions: the page is work with minutes, not without
     * them, and after a pass that ended in its own way the director's
     * one-work-per-visit rule is the one that holds.
     */
    @Test
    fun `work from the Crests page ends on the page, and the seam answers for it`() {
        val g = Tower(listOf(Tower.MAX), start = "page")
        val s = g.skill(minutes = 3)
        val page = g.grab()
        assertTrue(s.worksOn(Director.LOST_SECTOR))
        assertFalse(s.worksOn(Director.DIALOG), "the panel is nobody's to be handed")
        assertFalse(s.worksOn(Director.MAIN))
        assertTrue(s.hasBudget())
        assertEquals(true, s.seesWork(Director.LOST_SECTOR, page))
        assertNull(s.seesWork(Director.MAIN, page))
        val out = s.work(page)
        assertEquals(Result.RETIRED, out.result, "the highest floor: ${g.said}")
        assertEquals(listOf("Enter", "Subjugate"), g.taps.map { it.first }, g.said)
        assertEquals("page", g.screen)
        assertTrue(g.messages.contains("lost sector tower: the Crests page is open -- working it"), g.said)
        assertNull(s.seesWork(Director.LOST_SECTOR, g.frame()), "worked: one work per visit now")

        val none = Tower(start = "page").skill(minutes = 0)
        assertFalse(none.hasBudget())
        assertEquals(false, none.seesWork(Director.LOST_SECTOR, PaintLostSector.page()))
        assertTheRules(g)
    }

    /** The chain's second hand (Skill.leave): home from the page by its X, and from the panel by the back key first. */
    @Test
    fun `leave goes home from the Crests page and from the panel`() {
        for (start in listOf("page", "panel")) {
            val g = Tower(start = start)
            assertTrue(g.skill().leave(), "$start\n${g.said}")
            assertEquals(MAIN, g.screen)
            assertEquals(listOf("X"), g.taps.map { it.first }, "$start: ${g.taps}")
            assertEquals(if (start == "panel") listOf("panel") else emptyList(), g.backs, start)
            assertTheRules(g)
        }
        val home = Tower()
        assertTrue(home.skill().leave())
        assertTrue(home.taps.isEmpty() && home.backs.isEmpty(), "nothing to do on the main screen")
    }

    /**
     * Two passes in a row each hand over their own runs, and the Dungeons
     * card -- the tower's row is that one (Skills.rowFor) -- adds them up once
     * (3.5; notes/director.md, "A counter nothing clears is counted again").
     */
    @Test
    fun `two passes each hand over their own runs, on the Dungeons card`() {
        val g = Tower(listOf(Tower.LOST), start = "page")
        val s = g.skill(minutes = 1)
        val card = MapSettings()
        val day = 20_000L
        val each = ArrayList<Int>()
        for (pass in 1..2) {
            assertEquals(Result.DONE, s.work(g.frame()).result, "pass $pass\n${g.said}")
            each += s.lastCounts.getValue(LostSectorSkill.CARD_RUNS)
            SkillStats.add(card, "dungeon", s.lastCounts, day)
        }
        assertTrue(each.all { it >= 2 }, "$each")
        val read = SkillStats.read(card, "dungeon", day)
        assertEquals(each.sum(), read[LostSectorSkill.CARD_RUNS])
        assertEquals("${each.sum()} Lost Sector runs.", SkillStats.sentence("dungeon", read))
        assertEquals("1 Lost Sector run, 1 Lost Sector run won.",
                     SkillStats.sentence("dungeon", mapOf(LostSectorSkill.CARD_RUNS to 1, LostSectorSkill.CARD_WON to 1)))
    }

    /**
     * Over the canvas ceiling (a whole 1080 x 2520 frame, 180 rows of
     * headroom) the page's pill, the panel and the toast stand in the middle
     * of the headroom and the X at the bottom (LostSector.PAGE): every tap
     * lands on the thing it aimed at -- a tap aimed in the bottom's
     * rectangle would land 90 rows under the pill, outside it.
     */
    @Test
    fun `over the canvas ceiling every tap lands where the reader read it`() {
        val g = Tower(listOf(Tower.MAX), start = "page", w = 1080, h = 2520, headroom = 180)
        val s = g.skill(minutes = 3)
        assertEquals(Result.RETIRED, s.work(g.grab()).result, g.said)
        assertEquals(listOf("Enter", "Subjugate"), g.taps.map { it.first }, "${g.taps}\n${g.said}")
        assertTrue(s.leave(), g.said)
        assertEquals(listOf("Enter", "Subjugate", "X"), g.taps.map { it.first }, "${g.taps}")
        assertEquals(MAIN, g.screen)
        assertTheRules(g)
    }

    /**
     * The settings, the budget and the chain (3.4, 3.6): the page's key read
     * by Stored.lostSector, a negative number as 0; a chain step of its own
     * with its name, and not in the default chain (question 3).
     */
    @Test
    fun `the page's minutes, the budget and the chain step`() {
        val s = MapSettings()
        assertEquals(0, Stored.lostSector(s).minutes)
        s.put(Stored.LOST_SECTOR_MINUTES_KEY, 7)
        assertEquals(7, Stored.lostSector(s).minutes)
        s.put(Stored.LOST_SECTOR_MINUTES_KEY, -2)
        assertEquals(0, Stored.lostSector(s).minutes, "a negative number is 0")
        assertTrue(Chain.lostSectorHasWork(1))
        assertFalse(Chain.lostSectorHasWork(0))
        assertTrue("lost_sector" in SkillSettings.CHAINABLE)
        assertEquals("Lost Sector Tower", SkillSettings.CHAIN_NAMES["lost_sector"])
        assertFalse("lost_sector" in SkillSettings.CHAIN_DEFAULT)
        assertEquals("lost_sector", LostSectorSkill(Tower(), { LostSectorSkill.Settings() }).key)
    }

    /**
     * The director and the real skill together (the semi-automatic mode):
     * the Crests page is handed over after three quiet seconds, the tower
     * works it and hands the page back, and the page is not handed a second
     * time on the same visit.
     */
    @Test
    fun `the director hands the Crests page to the tower, and the tower hands it back`() {
        val g = Tower(listOf(Tower.MAX), start = "page")
        val s = g.skill(minutes = 2)
        val log = ArrayList<String>()
        val d = DirectorLoop(g, listOf(s), emptyList(), { GAME }, { SkillSettings.MODE_SEMI },
                             Chain(emptyList(), log = { log += it }), on = { g.switchOn },
                             log = { log += it }, keep = { _, _ -> }, now = { g.t })
        var worked = 0
        repeat(30) {
            val tick = d.tick()
            if (tick.did == "work lost_sector") worked += 1
            g.t += tick.beat
        }
        d.close()
        assertEquals(1, worked, log.joinToString("\n"))
        assertTrue(log.contains("giving the lost_sector to Lost Sector Tower"), log.joinToString("\n"))
        assertTrue(log.any { it.contains("Lost Sector Tower has worked this lost_sector; leaving it to you") },
                   log.joinToString("\n"))
        assertEquals(1, g.count("Subjugate"))
        assertEquals("page", g.screen)
        assertTheRules(g)
    }

    /**
     * A run of two minutes (G18, question 24's proposal): the wait for the
     * panel is BATTLE_WAIT, 150 s, where it was 90 -- a Bakemon run took over
     * 95 s live (G11 (5)). The run is waited out without a tap, comes back to
     * the panel and is counted; with the old 90 s it would have been "did not
     * stand within" and a pass ended in the middle of a battle.
     */
    @Test
    fun `a run of two minutes is waited out and counted`() {
        val g = Tower(listOf(Tower.LOST), battle = 116.0, start = "page")
        val s = g.skill(minutes = 1)
        val out = s.work(g.grab())
        val said = g.said
        assertEquals(Result.DONE, out.result, said)
        assertEquals(1, g.count("Subjugate"), said)
        assertEquals(1, g.battles)
        val presses = g.taps.filter { it.first == "Subjugate" }
        assertTrue(g.messages.any { it.startsWith("  battle finished after 12") }, "a run of about 120 s\n$said")
        assertTrue(g.messages.contains("  run lost, back without a Reward sheet"), said)
        assertTrue(g.messages.none { it.contains("did not stand within") }, said)
        assertEquals(1, s.lastCounts[LostSectorSkill.CARD_RUNS])
        assertTrue(g.messages.any { it.startsWith("  the minutes are up after 1 run") }, said)
        assertTrue(presses.single().third < 60.0)
        assertEquals("page", g.screen)
        assertEquals(DungeonSkill.BATTLE_TIMEOUT, LostSectorSkill.BATTLE_WAIT, "one number for both")
        assertTrue(LostSectorSkill.BATTLE_WAIT >= 150.0)
        assertTheRules(g)
    }

    // ------------------------------------------------------------------
    // The tower's day at the highest floor (G16, proposal 22)
    // ------------------------------------------------------------------

    /** The fully automatic mode over [g] with the real skill, chain [steps] repeating; the ticks run for [seconds]. */
    private fun chainRun(g: Tower, s: LostSectorSkill, log: ArrayList<String>, seconds: Double) {
        val chain = Chain(listOf(Chain.Step("lost_sector")), repeat = true, log = { log += it })
        val d = DirectorLoop(g, listOf(s), emptyList(), { GAME }, { SkillSettings.MODE_FULL }, chain,
                             on = { g.switchOn }, log = { log += it }, keep = { _, _ -> }, now = { g.t })
        val end = g.t + seconds
        while (g.t < end) g.t += d.tick().beat
        d.close()
    }

    /**
     * A chain that repeats walks into the tower at its highest floor exactly
     * once: the pass that met the toast retires the step for the chain run,
     * and the day written down keeps it out of the next chain run as well --
     * the core started again the same morning skips it on the settings.
     */
    @Test
    fun `at the highest floor a repeating chain goes into the tower exactly once`() {
        val g = Tower(listOf(Tower.MAX))
        val store = MapSettings()
        val s = g.skill(minutes = 5, store = store)
        val log = ArrayList<String>()
        chainRun(g, s, log, 120.0)
        val said = (log + g.messages).joinToString("\n")
        assertEquals(1, g.count("icon"), said)
        assertEquals(1, g.count("Subjugate"), said)
        assertTrue(log.contains("chain: starting Lost Sector Tower"), said)
        assertTrue(log.contains("  chain: retiring lost_sector -- the tower is at its highest floor -- nothing to " +
                                "subjugate until ${QuestSkill.whenText(Tower.RESET)}"), said)
        assertTrue(log.any { it.startsWith("the chain is finished") }, said)
        assertEquals(MAIN, g.screen)

        // The next chain run of the same day (a core started again): skipped, nothing tapped.
        val again = ArrayList<String>()
        val before = g.taps.size
        chainRun(g, s, again, 30.0)
        assertEquals(before, g.taps.size, "tapped on a tower whose day is over: ${g.taps}")
        // G20: the reason is the day, not the settings -- the minutes still stand on the page.
        assertTrue(again.contains("  chain: skipping lost_sector -- the tower is at its highest floor until " +
                                  QuestSkill.whenText(Tower.RESET)), again.joinToString("\n"))
        assertTrue(again.none { it.contains("on the settings as they stand") }, again.joinToString("\n"))
        // With no minutes on the page the settings are the reason, as before.
        val none = Tower(listOf(Tower.MAX)).skill(minutes = 0, store = MapSettings())
        assertEquals(null, none.noBudgetWhy())
        assertTrue(s.noBudgetWhy()!!.startsWith("the tower is at its highest floor until "))
        assertTheRules(g)
    }

    /** After the game's reset (08:00 Vienna) the tower is work again, and the pass goes in. */
    @Test
    fun `after the reset the tower is work again`() {
        val g = Tower(listOf(Tower.MAX))
        val store = MapSettings()
        val s = g.skill(minutes = 5, store = store)
        store.put(Stored.LOST_SECTOR_MINUTES_KEY, 5)
        Stored.retireLostSector(store, Tower.RESET)
        assertFalse(s.hasBudget())
        assertNull(s.seesWork(Director.LOST_SECTOR, PaintLostSector.page()), "the director's own rules, not the page")
        // Started anyway, the pass says so and taps nothing.
        val early = s.run()
        assertEquals(Result.RETIRED, early.result, g.said)
        assertTrue(g.taps.isEmpty(), "${g.taps}")

        g.t += Tower.RESET - g.wall() + 1.0
        assertNull(Stored.lostSector(store, g.wall()).doneUntil)
        assertTrue(s.hasBudget(), "a new day")
        // Null after a finished pass: the director's rules, and with a budget
        // the page's next visit is handed over.
        assertTrue(s.seesWork(Director.LOST_SECTOR, PaintLostSector.page()) != false)
        val out = s.run()
        assertEquals(Result.RETIRED, out.result, "the next day's highest floor\n${g.said}")
        assertEquals(listOf("icon", "Enter", "Subjugate", "X"), g.taps.map { it.first }, g.said)
        assertEquals(QuestSkill.nextReset(g.wall()), store.num(Stored.LOST_SECTOR_DONE_UNTIL_KEY, 0.0),
                     "the next reset is written")
        assertTheRules(g)
    }

    /**
     * New minutes on the page lift the tower's day, as the Quest Loop row's
     * switch lifts that loop's lock: the lock holds only with the minutes it
     * was written with, and the page clears it on a change
     * (Stored.unretireLostSector, MainActivity's number field) -- without
     * that, the old minutes typed back in would bring it back.
     */
    @Test
    fun `new minutes on the page lift the tower's day`() {
        val g = Tower(listOf(Tower.MAX), start = "page")
        val store = MapSettings()
        val s = g.skill(minutes = 5, store = store)
        assertEquals(Result.RETIRED, s.work(g.grab()).result, g.said)
        assertFalse(s.hasBudget())
        store.put(Stored.LOST_SECTOR_MINUTES_KEY, 6)
        assertNull(Stored.lostSector(store, g.wall()).doneUntil, "other minutes, no lock")
        assertTrue(s.hasBudget())
        assertTrue(s.seesWork(Director.LOST_SECTOR, PaintLostSector.page()) != false, "handed over at the next visit")
        store.put(Stored.LOST_SECTOR_MINUTES_KEY, 5)
        assertEquals(Tower.RESET, Stored.lostSector(store, g.wall()).doneUntil, "its own minutes again: the lock")
        Stored.unretireLostSector(store)
        assertNull(Stored.lostSector(store, g.wall()).doneUntil)
        assertTrue(store.num(Stored.LOST_SECTOR_DONE_UNTIL_KEY, Double.NaN).isNaN())
        assertTrue(store.num(Stored.LOST_SECTOR_DONE_MINUTES_KEY, Double.NaN).isNaN())
        assertTrue(s.hasBudget())
    }

    /**
     * The semi-automatic mode: the Crests page handed over once, the tower
     * at its highest floor retires, and the page is not handed over again
     * that day, on this visit or the next -- the director says there is
     * nothing to do on the settings as they stand.
     */
    @Test
    fun `semi-automatically the Crests page is not handed over again that day`() {
        val g = Tower(listOf(Tower.MAX), start = "page")
        val store = MapSettings()
        val s = g.skill(minutes = 2, store = store)
        val log = ArrayList<String>()
        val d = DirectorLoop(g, listOf(s), emptyList(), { GAME }, { SkillSettings.MODE_SEMI },
                             Chain(emptyList(), log = { log += it }), on = { g.switchOn },
                             log = { log += it }, keep = { _, _ -> }, now = { g.t })
        var worked = 0
        fun ticks(n: Int) = repeat(n) {
            val tick = d.tick()
            if (tick.did == "work lost_sector") worked += 1
            g.t += tick.beat
        }
        ticks(20)
        assertEquals(1, worked, log.joinToString("\n"))
        // Another visit: the player goes home and opens the Crests page again.
        g.screen = MAIN
        ticks(3)
        g.screen = "page"
        ticks(10)
        d.close()
        assertEquals(1, worked, log.joinToString("\n"))
        assertTrue(log.any { it.contains("Lost Sector Tower: nothing to do on the lost_sector, on the settings as they stand") },
                   log.joinToString("\n"))
        assertEquals(1, g.count("Subjugate"))
        assertTheRules(g)
    }

    private companion object {
        const val GAME = "com.bandainamcoent.dgup_ww"
        const val MAIN = "main"
        /** Screens a tap must never land on. */
        val NO_TAP = setOf("toast_wait", "toast", "strange", "battle", "starting", "vs", "loading",
                           "opening", "to_page", "quick", "title", "download", "realtitle")
    }
}
