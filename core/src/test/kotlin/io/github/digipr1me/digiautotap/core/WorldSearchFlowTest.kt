package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The minigame's loop over real board frames, the way `test_*_flow.py` drive
 * the Python skills -- but with the corpus instead of paint, because the
 * board is the one screen a painter cannot fake: `calibrate` fits a grid to
 * the card's own edges, `find_figure` wants eyes or a dark body, and
 * `read_counters` wants glyphs.
 *
 * The capture is a little state machine over `corpus/explore/`: it hands out
 * a real frame and changes which one when a tap lands where a reader said
 * the thing is. So the test asks the only question worth asking of a loop --
 * **does it tap the right places, in the right order, and where does it
 * leave the game** -- and every answer it checks against is one the oracle
 * already holds for these frames.
 *
 * The frames and what the oracle says of them:
 *
 *   ws_main_adb.png   main screen, Explore tab at fx 0.7119 fy 0.9429,
 *                     globe at 0.4769, 0.9429
 *   ws_menu.png       Explore menu, the card at 0.2929, 0.2861
 *   ws_board.png      the board, its X at 0.799, 0.949; figure row 2
 *                     column 1, paws 1, claws 78, fireballs 0
 *   ws_afterx.png     the Explore menu again, globe at 0.4773, 0.9434
 *   more-skins 220440 / 220445 / 220448
 *                     three boards of one live run, 625 x 1150: paws 998,
 *                     995, 993 and metres 32320, 32323, 32324 -- a real
 *                     step's worth of counter movement between real frames
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldSearchFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val vision = Vision(ClassPathAssets)

    /** The game's package, as `Game.pick` finds it on a device. */
    private val GAME = Game.KNOWN

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    private fun frame(name: String): Mat {
        // No corpus at all is the public copy of the source (release.py), where
        // these cases have nothing to say; a corpus with a frame missing is a fault.
        assumeTrue(File(repo, "corpus").isDirectory, "no corpus in this checkout")
        val file = File(repo, "corpus/explore/$name")
        assertTrue(file.exists(), "no corpus frame at $file")
        val img = Imgcodecs.imread(file.path)
        assertTrue(!img.empty(), "could not read $file")
        return img
    }

    /** A fraction of the reference window, in the pixels a tap goes out at. */
    private fun pixel(img: Mat, fx: Double, fy: Double): Pair<Int, Int> {
        val r = Dungeon.gameRect(img)
        return Py.roundInt(r.x0 + fx * r.gw) to Py.roundInt(r.y0 + fy * r.gh)
    }

    /**
     * A capture over real frames whose screen changes when a tap lands where
     * a reader put something. [stay] is every other tap: a board cell, the
     * skill button -- the screen does not change, which is what a board that
     * was tapped in a cell really does for the tenth of a second before the
     * counters move.
     */
    private class Stage(start: String, private val frames: Map<String, Mat>,
                        private val routes: List<Route>) : Capture {
        class Route(val from: String, val to: String, val at: Pair<Int, Int>,
                    val radius: Int = 24, val name: String = "")

        var screen: String = start
            private set
        val taps = ArrayList<Triple<String, Int, Int>>()
        val went = ArrayList<String>()
        var grabs = 0

        override fun grab(): Mat {
            grabs += 1
            // A copy, as a real capture hands out a fresh picture every time.
            return Mat().also { frames.getValue(screen).copyTo(it) }
        }

        override fun tap(x: Int, y: Int) {
            taps.add(Triple(screen, x, y))
            for (r in routes) {
                if (r.from != screen) continue
                val dx = x - r.at.first
                val dy = y - r.at.second
                if (dx * dx + dy * dy <= r.radius * r.radius) {
                    went.add(r.name.ifEmpty { "${r.from}->${r.to}" })
                    screen = r.to
                    return
                }
            }
            went.add("$screen: tapped nothing that moves")
        }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun back() = throw AssertionError("this skill never sends the back key")
        override fun inFront(): String? = "com.bandainamcoent.dgup_ww"
    }

    /** A capture that replays one list of frames, one step on per tap. */
    private class Replay(private val frames: List<Mat>) : Capture {
        var taps = 0
        val points = ArrayList<Pair<Int, Int>>()
        override fun grab(): Mat {
            val src = frames[minOf(taps, frames.size - 1)]
            return Mat().also { src.copyTo(it) }
        }
        override fun tap(x: Int, y: Int) { points.add(x to y); taps += 1 }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun back() = throw AssertionError("this skill never sends the back key")
        override fun inFront(): String? = "com.bandainamcoent.dgup_ww"
    }

    private fun settings(maxActions: Int, navigate: Boolean = false,
                         wanted: List<String> = WorldConst.ALL_WANTED) =
        WorldSearchSettings(wanted = wanted, minPaws = 0, maxActions = maxActions,
                            clickDelay = 0.0, waitForBoard = 180, navigate = navigate,
                            dryRun = false, calibFrames = 5)

    // ------------------------------------------------------------------------
    @Test
    fun `the board is the screen this skill works on, and the director hands it over at once`() {
        val board = frame("ws_board.png")
        assertEquals(Director.BOARD, Director.classify(board).screen,
                     "the corpus frame has to read as the board")

        val skill = WorldSearchSkill(Replay(listOf(board)), vision, { settings(1) },
                                     log = {}, sleep = {}, now = { 0.0 })
        assertTrue(skill.worksOn(Director.BOARD))
        assertTrue(!skill.worksOn(Director.MAIN), "the main screen is not this skill's")
        assertTrue(!skill.worksOn(Director.EXPLORE_MENU), "the menu is the way in, not the work")
        assertEquals("mini", skill.key, "the chain names this step mini")

        // chain.mini_has_target: anything ticked at all.
        assertTrue(skill.hasBudget())
        val nothing = WorldSearchSkill(Replay(listOf(board)), vision,
                                       { settings(1, wanted = emptyList()) }, log = {}, sleep = {})
        assertTrue(!nothing.hasBudget(), "nothing ticked is nothing to do")

        // The contract this skill's own stillness rule rests on: the
        // director leaves the board out of its three seconds, because the
        // figure and the banner move it by 0.08 to 0.17 all by themselves.
        assertTrue(Director.BOARD in Director.MOVES_BY_ITSELF,
                   "the board has to be outside the director's motion rule")
        assertTrue(Director.idle(board, frame("ws_banner.png"), Director.BOARD),
                   "two different boards still count as idle, so the clock never holds")
        board.release()
    }

    @Test
    fun `work taps inside the board's own grid and leaves the board standing`() {
        val board = frame("ws_board.png")
        val cap = Replay(listOf(board))
        val kept = ArrayList<String>()
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(3) },
                                     log = { lines += it }, keep = { _, t -> kept += t },
                                     sleep = {}, now = { 0.0 })

        val outcome = skill.work(board)
        assertEquals(Result.DONE, outcome.result, "the run ends on its own limit: $outcome")
        assertEquals("action limit reached", outcome.why)
        assertEquals(3, cap.points.size, "one tap per action: ${cap.points}")

        // Every tap has to land inside the card `calibrate` found, which is
        // where the grid and the skill button are. The oracle has that card
        // at [98, 0, 884, 1903] on this frame.
        val calib = vision.calibrate(board)
        val (cx, cy, cw, ch) = calib.card.toList()
        for ((x, y) in cap.points) {
            assertTrue(x in cx until (cx + cw) && y in cy until (cy + ch),
                       "a tap at $x,$y is outside the board's card $cx,$cy ${cw}x$ch")
        }

        // The screen never changes, so nothing the taps do shows in the
        // counters: every action comes back as no effect and resyncs, and
        // the frame it resynced on is in the debug package under its own
        // number. That is the giving-up rule working, not a failure. The
        // board as the run found it comes first, once a run.
        assertEquals(listOf("board_start_00", "resync_00", "resync_01", "resync_02"), kept,
                     "each resync keeps its frame, numbered: $kept")

        // And the board is still the board afterwards: work hands back the
        // screen it began on.
        assertEquals(Director.BOARD, Director.classify(cap.grab()).screen)
        board.release()
    }

    @Test
    fun `counters that really move confirm the action, and only the start is kept`() {
        // Three boards of one live run, in order. Every tap moves the capture
        // on by one, so the counters between two frames are what the game
        // really did: paws 998 -> 995 -> 993, metres 32320 -> 32323 -> 32324.
        val frames = listOf("more-skins-FILTER-C-2026-08-25 220440.png",
                            "more-skins-FILTER-C-2026-08-25 220445.png",
                            "more-skins-FILTER-C-2026-08-25 220448.png").map { frame(it) }
        val cap = Replay(frames)
        val kept = ArrayList<String>()
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(2) },
                                     log = { lines += it }, keep = { _, t -> kept += t },
                                     sleep = {}, now = { 0.0 })

        val outcome = skill.work(frames[0])
        assertEquals(Result.DONE, outcome.result, "$outcome, log:\n${lines.joinToString("\n")}")
        assertEquals(2, cap.points.size)
        assertEquals(listOf("board_start_00"), kept,
                     "a confirmed action keeps no frame, only the start does; kept $kept\n${lines.joinToString("\n")}")
        assertTrue(lines.any { it.startsWith("counters at start: paws 998, claws 142, fireballs 16") },
                   "the counters the run began with are in the log:\n${lines.joinToString("\n")}")
        assertTrue(lines.any { it.contains(Actions.OK) },
                   "both actions were confirmed against the counters:\n${lines.joinToString("\n")}")

        // The tracker's own arithmetic on the real numbers, at the bot's one
        // action per frame: three paws and three metres are both inside what
        // one action can do, so neither is thrown away.
        val t = CounterTracker(maxActions = 1)
        t.update(vision.readCounters(frames[0], vision.calibrate(frames[0])))
        val deltas = t.update(vision.readCounters(frames[1], vision.calibrate(frames[1])))
        assertEquals(-3L, deltas["paws"])
        assertEquals(3L, deltas["meters"])
        assertTrue(t.suspicious.isEmpty(), "nothing here is implausible: ${t.suspicious}")
        frames.forEach { it.release() }
    }

    @Test
    fun `a run that cannot read the board keeps the frame it gave up on`() {
        // The Explore menu is not a board: `calibrate` finds no portrait card
        // on it. The old failure said so and kept nothing, and the two
        // answers behind it -- never opened, or opened and not recognised --
        // need different work (NOTES.md).
        val menu = frame("ws_menu.png")
        val cap = Replay(listOf(menu))
        val kept = ArrayList<String>()
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(1) },
                                     log = { lines += it }, keep = { _, t -> kept += t },
                                     sleep = {}, now = { 0.0 })

        val outcome = skill.work(menu)
        assertEquals(Result.PARKED, outcome.result, "$outcome")
        assertTrue(outcome.why.contains("debug package"), "the reason points at the frame: $outcome")
        assertEquals(listOf("no_start_00"), kept, "the frame it gave up on is kept: $kept")
        assertTrue(lines.any { it.contains("never on the screen") },
                   "and it says what to do about it:\n${lines.joinToString("\n")}")
        assertEquals(0, cap.points.size, "a run that cannot read the board taps nothing")
        menu.release()
    }

    @Test
    fun `run walks to the board, works it, and comes home`() {
        val main = frame("ws_main_adb.png")
        val menu = frame("ws_menu.png")
        val board = frame("ws_board.png")
        val afterX = frame("ws_afterx.png")

        // Where the readers say the four things are, in the pixels a tap
        // goes out at. Nothing here is a guess: each one is a reader's own
        // answer on the frame it is read from.
        val exploreTab = Explore.exploreTab(main)!!
        val card = Explore.exploreMenu(menu)!!
        val x = Explore.closeButton(board)!!
        val globe = Dungeon.homeButton(afterX)!!

        val stage = Stage("main",
            mapOf("main" to main, "menu" to menu, "board" to board, "afterx" to afterX),
            listOf(
                Stage.Route("main", "menu", pixel(main, exploreTab.fx, exploreTab.fy),
                            name = "the Explore tab"),
                Stage.Route("menu", "board", pixel(menu, card.fx, card.fy),
                            name = "the Digital World Search card"),
                Stage.Route("board", "afterx", pixel(board, x.fx, x.fy),
                            name = "the X"),
                Stage.Route("afterx", "main", pixel(afterX, globe.fx, globe.fy),
                            name = "the globe")))

        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(stage, vision, { settings(1, navigate = true) },
                                     log = { lines += it }, sleep = {}, now = { 0.0 })

        val outcome = skill.run()
        assertEquals("main", stage.screen,
                     "run ends at home:\n${lines.joinToString("\n")}\n${stage.went}")
        assertEquals(Result.DONE, outcome.result, "$outcome")

        // The route, in order, and nothing else that moved a screen.
        assertEquals(listOf("the Explore tab", "the Digital World Search card",
                            "the X", "the globe"),
                     stage.went.filter { !it.endsWith("tapped nothing that moves") },
                     "the four taps that matter, in order: ${stage.went}")
        // The one tap in between is the action on the board, and it is the
        // only one that changed nothing -- one action was asked for.
        assertEquals(1, stage.went.count { it.endsWith("tapped nothing that moves") },
                     "exactly the one board action: ${stage.went}")
        listOf(main, menu, board, afterX).forEach { it.release() }
    }

    @Test
    fun `an open board is not tapped at on the way in`() {
        // "It taps nothing at all when the board is already up, so it takes
        // nothing from anyone who starts there by hand" (NOTES.md).
        val board = frame("ws_board.png")
        val afterX = frame("ws_afterx.png")
        val main = frame("ws_main_adb.png")
        val x = Explore.closeButton(board)!!
        val globe = Dungeon.homeButton(afterX)!!
        val stage = Stage("board",
            mapOf("board" to board, "afterx" to afterX, "main" to main),
            listOf(Stage.Route("board", "afterx", pixel(board, x.fx, x.fy), name = "the X"),
                   Stage.Route("afterx", "main", pixel(afterX, globe.fx, globe.fy),
                               name = "the globe")))

        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(stage, vision, { settings(1, navigate = true) },
                                     log = { lines += it }, sleep = {}, now = { 0.0 })
        skill.run()
        assertTrue(lines.any { it.contains("the board is already open") },
                   "it says so and taps nothing:\n${lines.joinToString("\n")}")
        assertEquals(listOf("the X", "the globe"),
                     stage.went.filter { !it.endsWith("tapped nothing that moves") },
                     "only the way out was tapped: ${stage.went}")
        listOf(board, afterX, main).forEach { it.release() }
    }

    @Test
    fun `the main switch stops the run between two actions`() {
        val board = frame("ws_board.png")
        val cap = Replay(listOf(board))
        var on = true
        val skill = WorldSearchSkill(cap, vision, { settings(0) }, log = {},
                                     on = { on }, sleep = {}, now = { 0.0 })
        // Off before the first action: nothing is tapped and the run says
        // stopped, not done. maxActions is 0 here, so only the switch can
        // end it -- which is the point.
        on = false
        val outcome = skill.work(board)
        assertEquals(Result.STOPPED, outcome.result, "$outcome")
        assertEquals(0, cap.points.size, "a switched-off run taps nothing")
        board.release()
    }

    @Test
    fun `the director gives the standing board to this skill`() {
        val board = frame("ws_board.png")
        // DirectorTest's own fake, with its own package name: that test's
        // GAME constant is private to it, and this session does not touch
        // that file. Only the two ends have to agree, and both are here.
        val cap = DirectorTest.FakeCapture(GAME)
        cap.scene = { Mat().also { m -> board.copyTo(m) } }
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(1) }, log = { lines += it },
                                     sleep = {}, now = { 0.0 })
        val chain = Chain(emptyList(), log = {})
        val director = DirectorLoop(cap, listOf(skill), emptyList(), { GAME },
                                   { SkillSettings.MODE_SEMI }, chain,
                                   log = { lines += it }, now = { 100.0 })

        val tick = director.tick()
        assertEquals(Director.BOARD, tick.screen)
        assertEquals("work mini", tick.did,
                     "no three seconds on the board: $tick\n${lines.joinToString("\n")}")
        director.close()
        board.release()
    }
}
