package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

        /** The player's own hand: the game shows [to] without a tap of ours. */
        fun show(to: String) {
            require(to in frames) { "no frame for $to" }
            screen = to
        }

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
        // the frame it resynced on goes to keep under its own
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
        assertEquals("I could not read the board.", outcome.why, "$outcome")
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

    // ---- walled in with no claws and no fireballs (PLAN_WORLD_SEARCH_FORMATE.md F11;
    // notes/world-search.md, "Walled in with nothing left to get out")
    //
    // The F9 board, corpus/explore/more-skins-FILTER-C-2026-08-25 220501.png,
    // with the position the plannerProbe put on it painted in: no frame of the
    // corpus has a figure walled in, and none has its claws and fireballs at 0
    // together. The painting uses nothing but real pixels, and every case
    // first asks the readers what they see on it, so that a case can only
    // pass on the picture it says it plays:
    //
    //   the figure   cell r2c2 moved to r5c2, between the pyramids in r5c1,
    //                r5c3 and r4c2, the board's edge below; r2c2 filled with
    //                the empty cell r2c1 beside it
    //   the counters ws_board.png's fireball box, which reads 0, scaled into
    //                the claws box and, where asked, the fireball box
    //   the chips    where asked, the SP Training Chip of r1c1 into six more
    //                cells of rows 1 and 2, seven targets none of which the
    //                figure can reach -- planner.py's depth limit, the one
    //                wait a real pass can meet

    private val f9Name = "more-skins-FILTER-C-2026-08-25 220501.png"

    /** Cell [row], [col] of [calib] in whole pixels: x0, y0, x1, y1. */
    private fun cellBox(calib: Calib, row: Int, col: Int): IntArray = intArrayOf(
        Math.round(calib.gridX0 + col * calib.cellW).toInt(),
        Math.round(calib.gridY0 + row * calib.cellH).toInt(),
        Math.round(calib.gridX0 + (col + 1) * calib.cellW).toInt(),
        Math.round(calib.gridY0 + (row + 1) * calib.cellH).toInt())

    /** [src]'s picture in [from] into [dst]'s box [to], scaled where the two differ by rounding. */
    private fun paste(src: Mat, from: IntArray, dst: Mat, to: IntArray) {
        val patch = src.submat(from[1], from[3], from[0], from[2])
        val sized = Mat()
        Imgproc.resize(patch, sized, Size((to[2] - to[0]).toDouble(), (to[3] - to[1]).toDouble()),
                       0.0, 0.0, Imgproc.INTER_AREA)
        sized.copyTo(dst.submat(to[1], to[3], to[0], to[2]))
        sized.release()
    }

    private fun roiBox(calib: Calib, key: String): IntArray =
        calib.roi(key).let { (x, y, w, h) -> intArrayOf(x, y, x + w, y + h) }

    /** The F9 board with the F9 position painted in; see the head of this section. */
    private fun walledBoard(fireballsZero: Boolean = true, chips: Boolean = false): Mat {
        val orig = frame(f9Name)
        val img = orig.clone()
        val calib = vision.calibrate(orig)
        paste(orig, cellBox(calib, 1, 1), img, cellBox(calib, 4, 1))
        paste(orig, cellBox(calib, 1, 0), img, cellBox(calib, 1, 1))
        if (chips) {
            for ((r, c) in listOf(0 to 1, 0 to 2, 0 to 4, 1 to 0, 1 to 1, 1 to 2)) {
                paste(orig, cellBox(calib, 0, 0), img, cellBox(calib, r, c))
            }
        }
        val zeroFrame = frame("ws_board.png")
        val zero = roiBox(vision.calibrate(zeroFrame), "roi_fireballs")
        paste(zeroFrame, zero, img, roiBox(calib, "roi_claws"))
        if (fireballsZero) paste(zeroFrame, zero, img, roiBox(calib, "roi_fireballs"))
        zeroFrame.release()
        orig.release()
        return img
    }

    /** What the readers see on a painted board, asked before the skill is: the painting's own proof. */
    private fun assertReads(img: Mat, fireballs: Long, targets: Int) {
        val calib = vision.calibrate(img)
        val figure = vision.findFigure(img, calib, vision.loadTemplates())
        assertTrue(figure != null && figure.row == 4 && figure.col == 1,
                   "the figure has to read on r5c2: ${figure?.toOracle()}")
        val grid = vision.readGrid(img, calib, vision.boardTemplates(), figure = figure).first
        for ((r, c) in listOf(3 to 1, 4 to 0, 4 to 2)) {
            assertEquals("pyramid", grid[r][c], "a pyramid has to read on r${r + 1}c${c + 1}: $grid")
        }
        assertEquals(targets, grid.flatten().count { it == "ticket_orange" }, "the chips: $grid")
        val counters = vision.readCounters(img, calib)
        assertEquals(0L, counters["claws"], "the claws counter: $counters")
        assertEquals(fireballs, counters["fireballs"], "the fireball counter: $counters")
        assertEquals(986L, counters["paws"], "the paws are the frame's own: $counters")
    }

    @Test
    fun `walled in with no claws and no fireballs the pass parks and says why`() {
        val board = walledBoard()
        assertReads(board, fireballs = 0, targets = 1)
        val cap = Replay(listOf(board))
        val kept = ArrayList<String>()
        val lines = ArrayList<String>()
        // A limit, so that a pass that waits instead of parking ends and
        // fails here rather than standing, as it did before.
        val skill = WorldSearchSkill(cap, vision, { settings(5) },
                                     log = { lines += it }, keep = { _, t -> kept += t },
                                     sleep = {}, now = { 0.0 })

        val outcome = skill.work(board)
        val log = lines.joinToString("\n")
        assertEquals(Result.PARKED, outcome.result, "$outcome\n$log")
        assertEquals("The figure is walled in with no claws and no fireballs -- stuck until they " +
                     "recharge.", outcome.why)
        assertEquals(listOf("board_start_00", "walled_in_00"), kept, "the frame it parked on is kept: $kept")
        assertEquals(0, cap.points.size, "nothing is tapped on a board nothing can be done on: ${cap.points}")
        // Two decisions, one look between them, and no move on the TODAY card.
        assertEquals(2, lines.count { it.startsWith("  1. stuck (walled in, no way on foot and no claws, " +
                                                    "dash held: no charges)") }, log)
        assertTrue(lines.any { it.contains("one more look at the board and the counters") }, log)
        assertTrue(lines.any { it.contains("looked again: paws 986, claws 0, fireballs 0") }, log)
        assertTrue(lines.any { it.startsWith("Digital World Search: walled in with no claws and no fireballs after 0 actions") }, log)
        assertEquals(0, skill.lastCounts["actions"], "a stuck pass has made no move: ${skill.lastCounts}")
        board.release()
    }

    @Test
    fun `a fireball that is there on the second look lets the pass go on`() {
        // The first "stuck" is a look, not a park: the counters it was decided
        // on can be the tracker's own 0 after an Insufficient banner. Here the
        // look reads the board with its fireball counter as it really is, 16.
        val stuck = walledBoard()
        val charged = walledBoard(fireballsZero = false)
        assertReads(charged, fireballs = 16, targets = 1)
        val cap = Replay(listOf(stuck))
        var frame = stuck
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        val switching = object : Capture by cap {
            override fun grab(): Mat = Mat().also { frame.copyTo(it) }
        }
        val skill = WorldSearchSkill(switching, vision, { settings(1) },
                                     log = { lines += it; if (it.contains("one more look")) frame = charged },
                                     keep = { _, t -> kept += t }, sleep = {}, now = { 0.0 })

        val outcome = skill.work(stuck)
        val log = lines.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, "$outcome\n$log")
        assertTrue(lines.any { it.contains("looked again: paws 986, claws 0, fireballs 16") }, log)
        assertTrue(lines.any { it.startsWith("  1. skill (walled in, no way on foot, skill clears it)") }, log)
        val calib = vision.calibrate(stuck)
        assertEquals(listOf(calib.skillButton[0] to calib.skillButton[1]), cap.points,
                     "the one tap is the dash: ${cap.points}")
        assertTrue("walled_in_00" !in kept, "no park, no park frame: $kept")
        listOf(stuck, charged).forEach { it.release() }
    }

    @Test
    fun `an ordinary wait is a round and not a park`() {
        // Seven chips none of which the figure can reach: planner.py's depth
        // limit gives them up and waits once -- a wait of the kind F11 leaves
        // as it was -- and on the next decision the figure, walled in with a
        // fireball left, dashes. The pass is not parked, and the wait round is
        // counted as every wait always was.
        val board = walledBoard(fireballsZero = false, chips = true)
        assertReads(board, fireballs = 16, targets = 7)
        val cap = Replay(listOf(board))
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(2) },
                                     log = { lines += it }, keep = { _, t -> kept += t },
                                     sleep = {}, now = { 0.0 })

        val outcome = skill.work(board)
        val log = lines.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, "$outcome\n$log")
        assertEquals("action limit reached", outcome.why)
        assertTrue(lines.any { it.startsWith("  1. wait (too many unreachable targets") }, log)
        assertTrue(lines.any { it.startsWith("  2. skill (walled in, no way on foot, skill clears it)") }, log)
        assertTrue(lines.none { it.contains("one more look") }, "a wait takes no look of its own:\n$log")
        val calib = vision.calibrate(board)
        assertEquals(listOf(calib.skillButton[0] to calib.skillButton[1]), cap.points,
                     "the wait taps nothing, the dash taps the button: ${cap.points}")
        assertTrue("walled_in_00" !in kept, "$kept")
        assertEquals(2, skill.lastCounts["actions"], "${skill.lastCounts}")
        board.release()
    }

    @Test
    fun `run parks on a walled-in board and still goes home`() {
        // The fully automatic mode's case: `run` goes home in its finally, so
        // the director finds the main screen after the park and retires the
        // step for this chain run (notes/runner.md, "A constant is a place on
        // one display"; DirectorTest, "a step that parks but came home
        // retires for the run"), and the next step starts.
        val main = frame("ws_main_adb.png")
        val menu = frame("ws_menu.png")
        val board = walledBoard()
        val afterX = frame("ws_afterx.png")
        val exploreTab = Explore.exploreTab(main)!!
        val card = Explore.exploreMenu(menu)!!
        val x = Explore.closeButton(board)!!
        val globe = Dungeon.homeButton(afterX)!!
        val stage = Stage("main",
            mapOf("main" to main, "menu" to menu, "board" to board, "afterx" to afterX),
            listOf(
                Stage.Route("main", "menu", pixel(main, exploreTab.fx, exploreTab.fy), name = "the Explore tab"),
                Stage.Route("menu", "board", pixel(menu, card.fx, card.fy), name = "the Digital World Search card"),
                Stage.Route("board", "afterx", pixel(board, x.fx, x.fy), name = "the X"),
                Stage.Route("afterx", "main", pixel(afterX, globe.fx, globe.fy), name = "the globe")))
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(stage, vision, { settings(5, navigate = true) },
                                     log = { lines += it }, sleep = {}, now = { 0.0 })

        val outcome = skill.run()
        assertEquals(Result.PARKED, outcome.result, "$outcome\n${lines.joinToString("\n")}")
        assertEquals("main", stage.screen, "home after the park:\n${lines.joinToString("\n")}\n${stage.went}")
        assertEquals(listOf("the Explore tab", "the Digital World Search card", "the X", "the globe"), stage.went,
                     "in and out, and not one tap on the board: ${stage.went}")
        listOf(main, menu, board, afterX).forEach { it.release() }
    }

    // ---- after the pause the game stays where it is (PLAN_WORLD_SEARCH_FORMATE.md F13;
    // notes/world-search.md, "After the pause the game stays where it is")
    //
    // The service holds every gesture while the main switch is off
    // (DigiAutotapService.withheld), and this capture does the same: a tap
    // with the switch off goes nowhere and is counted. So the old way out
    // plays here as it played on all 35 passes of the format tour -- the X
    // withheld, the rounds run out, "could not get back", two frames kept.

    /** [stage] with the service's gate in front of every tap, and a hook after each tap that went out. */
    private class Gated(private val stage: Stage, private val on: () -> Boolean,
                        private val after: (Stage) -> Unit = {}) : Capture by stage {
        var withheld = 0
        override fun tap(x: Int, y: Int) {
            if (!on()) { withheld += 1; return }
            stage.tap(x, y)
            after(stage)
        }
    }

    /** main -> menu -> board -> afterx -> main, each route where its reader says; the game on [start]. */
    private fun wayInAndOut(board: Mat, start: String = "main"): Stage {
        val main = frame("ws_main_adb.png")
        val menu = frame("ws_menu.png")
        val afterX = frame("ws_afterx.png")
        val exploreTab = Explore.exploreTab(main)!!
        val card = Explore.exploreMenu(menu)!!
        val x = Explore.closeButton(board)!!
        val globe = Dungeon.homeButton(afterX)!!
        return Stage(start,
            mapOf("main" to main, "menu" to menu, "board" to board, "afterx" to afterX),
            listOf(
                Stage.Route("main", "menu", pixel(main, exploreTab.fx, exploreTab.fy), name = "the Explore tab"),
                Stage.Route("menu", "board", pixel(menu, card.fx, card.fy), name = "the Digital World Search card"),
                Stage.Route("board", "afterx", pixel(board, x.fx, x.fy), name = "the X"),
                Stage.Route("afterx", "main", pixel(afterX, globe.fx, globe.fy), name = "the globe")))
    }

    @Test
    fun `a pass the switch ends leaves the game on the board and says so`() {
        val board = frame("ws_board.png")
        val stage = wayInAndOut(board)
        var on = true
        // The player pauses right after the first action on the board.
        val cap = Gated(stage, { on }) { st ->
            if (st.screen == "board" && st.went.last().endsWith("tapped nothing that moves")) on = false
        }
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        // No limit: only the switch can end this pass.
        val skill = WorldSearchSkill(cap, vision, { settings(0, navigate = true) }, log = { lines += it },
                                     keep = { _, t -> kept += t }, on = { on }, sleep = {}, now = { 0.0 })

        val outcome = skill.run()
        val log = lines.joinToString("\n")
        assertEquals(Result.STOPPED, outcome.result, "$outcome\n$log")
        assertEquals("board", stage.screen, "the game stays on the board:\n$log\n${stage.went}")
        assertEquals(listOf("the Explore tab", "the Digital World Search card", "board: tapped nothing that moves"),
                     stage.went, "in, one action, and nothing on the way out: ${stage.went}")
        assertEquals(0, cap.withheld, "not a tap is even tried with the switch off")
        assertEquals(listOf("board_start_00", "resync_00"), kept,
                     "no no_way_home, no no_way_back -- a paused game is not a lost one: $kept")
        assertEquals(1, lines.count { it == "the main switch is off -- not going home, the game stays where it is" }, log)
        assertTrue(lines.none { it.contains("could not get back") || it.contains("leaving the Digital World Search") }, log)
        assertTrue(lines.any { it.startsWith("Digital World Search: stopped by the main switch after 1 action") }, log)
        board.release()
    }

    @Test
    fun `a way in the switch ends is a stop, not a board that would not open`() {
        val board = frame("ws_board.png")
        val stage = wayInAndOut(board)
        var on = true
        // The player pauses as the Explore menu comes up.
        val cap = Gated(stage, { on }) { st -> if (st.screen == "menu") on = false }
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(0, navigate = true) }, log = { lines += it },
                                     keep = { _, t -> kept += t }, on = { on }, sleep = {}, now = { 0.0 })

        val outcome = skill.run()
        val log = lines.joinToString("\n")
        assertEquals(Result.STOPPED, outcome.result, "not a park the player never earned: $outcome\n$log")
        assertEquals("menu", stage.screen, "the game stays where the pause found it")
        assertTrue(lines.none { it.contains("could not open") }, log)
        assertTrue(kept.isEmpty(), "nothing kept: $kept")
        assertEquals(1, lines.count { it == "the main switch is off -- not going home, the game stays where it is" }, log)
        board.release()
    }

    // ---- the main screen, asked over a few seconds (PLAN_ABSCHLUSS_1_3.md
    // A5c, K2; MainScreen)
    //
    // The Poco, 2026-10-02, 19:14:16 to 19:14:20: back on the main screen, the
    // chain starts World Search, and its one look falls into the light the
    // hologram device throws around its own Auto -- the auto button. "not the
    // plain main screen, cannot open the Digital World Search", and the chain
    // retired the task for the run; a second later the button read again.

    /** [stage], with its main screen lit ([Paint.holoLight]) for the next [lit] looks at it. */
    private class Lit(private val stage: Stage, private val light: Mat) : Capture by stage {
        var lit = 0
        var shown = 0
        override fun grab(): Mat {
            if (stage.screen == "main" && lit > 0) {
                lit -= 1
                shown += 1
                return Mat().also { light.copyTo(it) }
            }
            return stage.grab()
        }
    }

    @Test
    fun `a main screen lit for a moment is waited out and the board opens, two passes running`() {
        val board = frame("ws_board.png")
        val stage = wayInAndOut(board)
        val main = frame("ws_main_adb.png")
        val light = Paint.holoLight(main)
        assertNotNull(Dungeon.autoButton(main), "the main screen reads")
        assertNull(Dungeon.autoButton(light), "the light takes the auto button, as on the Poco's frame")
        val cap = Lit(stage, light)
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        var t = 0.0
        val skill = WorldSearchSkill(cap, vision, { settings(1, navigate = true) }, log = { lines += it },
                                     keep = { _, k -> kept += k }, sleep = { t += it }, now = { t })
        for (pass in 1..2) {
            // One lit look on the first pass, two on the second: the light
            // came and went on one frame and on two (staging/k2/measure.txt).
            cap.lit = pass
            val before = stage.went.size
            val outcome = skill.run()
            val log = lines.joinToString("\n")
            assertEquals(Result.DONE, outcome.result, "pass $pass: $outcome\n$log")
            assertEquals("main", stage.screen, "pass $pass ends at home:\n$log")
            assertEquals(listOf("the Explore tab", "the Digital World Search card", "the X", "the globe"),
                         stage.went.drop(before).filter { !it.endsWith("tapped nothing that moves") },
                         "pass $pass, in and out: ${stage.went}")
        }
        val log = lines.joinToString("\n")
        assertEquals(3, cap.shown, "every lit look was looked at")
        assertEquals(2, lines.count { it.startsWith("  the plain main screen after ") }, "the wait says itself:\n$log")
        assertTrue(lines.none { it.contains("cannot open") }, log)
        assertTrue(kept.none { it.startsWith("not_main_screen") }, "$kept")
        listOf(board, main, light).forEach { it.release() }
    }

    @Test
    fun `a main screen that stays lit through the wait is given up on as before, two passes running`() {
        val board = frame("ws_board.png")
        val stage = wayInAndOut(board)
        val main = frame("ws_main_adb.png")
        val light = Paint.holoLight(main)
        val cap = Lit(stage, light).apply { lit = Int.MAX_VALUE }
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        var t = 0.0
        val skill = WorldSearchSkill(cap, vision, { settings(1, navigate = true) }, log = { lines += it },
                                     keep = { _, k -> kept += k }, sleep = { t += it }, now = { t })
        for (pass in 1..2) {
            val t0 = t
            val outcome = skill.run()
            val log = lines.joinToString("\n")
            assertEquals(Result.PARKED, outcome.result, "pass $pass: $outcome\n$log")
            assertTrue(t - t0 >= MainScreen.WAIT, "pass $pass waited ${t - t0} s")
            assertTrue(stage.went.none { !it.contains("tapped nothing that moves") },
                       "nothing opened from a screen that never read: ${stage.went}")
        }
        val log = lines.joinToString("\n")
        assertEquals(2, lines.count { it == "not the plain main screen after 8 s, cannot open the Digital World Search" }, log)
        assertEquals(2, kept.count { it.startsWith("not_main_screen") }, "$kept")
        listOf(board, main, light).forEach { it.release() }
    }

    @Test
    fun `a pass that ends on its limit still goes home, and stops where it stands if paused on the way`() {
        // The limit ends the pass with the switch on: the X, as ever. Then the
        // player pauses on the Explore menu, before the globe.
        val board = frame("ws_board.png")
        val stage = wayInAndOut(board)
        var on = true
        val cap = Gated(stage, { on }) { st -> if (st.screen == "afterx") on = false }
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(1, navigate = true) }, log = { lines += it },
                                     keep = { _, t -> kept += t }, on = { on }, sleep = {}, now = { 0.0 })

        val outcome = skill.run()
        val log = lines.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, "$outcome\n$log")
        assertEquals("afterx", stage.screen, "the X went out, the globe was never tried:\n$log")
        assertEquals(0, cap.withheld, "no tap tried after the pause: ${stage.went}")
        assertTrue(kept.none { it.startsWith("no_way") }, "$kept")
        assertTrue(lines.any { it == "leaving the Digital World Search" }, log)
        assertEquals(1, lines.count { it == "the main switch is off -- not going home, the game stays where it is" }, log)
        board.release()
    }

    // ---- after the pause the pass goes on (PLAN_WORLD_SEARCH_FORMATE.md F27,
    // the player's rule of 2026-09-29; notes/director.md, "A pass the switch
    // paused goes on where it stands, and a screen the player opened is still
    // the player's")
    //
    // The real skill under the real director, over the corpus frames of the
    // way in and out, with the service's gate in front of every tap. Before
    // F27 the fully automatic mode parked on the resumed board: "The fully
    // automatic mode starts from the main screen, and board is open."

    /** The director with World Search as its one skill, one chain step of it, the clock moved by each tick's beat. */
    private class Rounds(cap: Capture, skill: Skill, mode: String, on: () -> Boolean, lines: MutableList<String>) {
        var t = 100.0
        val chain = Chain(listOf(Chain.Step("mini")), log = { lines += it })
        val director = DirectorLoop(cap, listOf(skill), emptyList(), { Game.KNOWN }, { mode }, chain,
                                    on = on, log = { lines += it }, now = { t })
        fun tick(): Director.Tick = director.tick().also { t += it.beat }
    }

    /** A pass that pauses itself after its first action on the board -- the player's tap on the dot, once. */
    private class PausedPass(val stage: Stage) {
        var on = true
        var paused = false
        val cap = Gated(stage, { on }) { st ->
            if (!paused && st.screen == "board" && st.went.last().endsWith("tapped nothing that moves")) {
                paused = true
                on = false
            }
        }
    }

    @Test
    fun `fully automatic, a pass paused on the board goes on when the switch comes back`() {
        val board = frame("ws_board.png")
        val p = PausedPass(wayInAndOut(board))
        // No limit on the first pass: only the pause ends it. The second one
        // ends on its limit, so that it goes home and the chain goes on.
        var limit = 0
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(p.cap, vision, { settings(limit, navigate = true) }, log = { lines += it },
                                     on = { p.on }, sleep = {}, now = { 0.0 })
        // The board and nothing else is where its run begins again, and the
        // TODAY card's wrapper, which is what the director holds on the
        // phone (CoreService), forwards the answer.
        assertTrue(skill.resumesOn(Director.BOARD))
        assertTrue(!skill.resumesOn(Director.EXPLORE_MENU) && !skill.resumesOn(Director.MAIN))
        assertTrue(Counted(skill, { emptyMap() }) { _, _ -> }.resumesOn(Director.BOARD))
        val r = Rounds(p.cap, skill, SkillSettings.MODE_FULL, { p.on }, lines)

        assertEquals("run mini", r.tick().did, lines.joinToString("\n"))
        assertEquals("board", p.stage.screen, "the pause leaves the game on the board (F13)")
        // Paused for longer than SETTLE: the director reads and does nothing.
        repeat(8) { assertEquals("paused, sees board", r.tick().note) }
        limit = 1
        p.on = true
        repeat(4) { r.tick() }
        val log = lines.joinToString("\n")
        assertNull(r.director.parked, "a park after the resume:\n$log")
        assertTrue(lines.none { it.contains("parked on") }, log)
        assertEquals(1, lines.count { it == "chain: resuming Digital World Search on the board" }, log)
        assertTrue(lines.none { it.contains("did not come back") }, "a stopped step hands back nothing to wait for:\n$log")
        assertEquals(1, lines.count { it == "the board is already open" }, "the resumed run opens nothing:\n$log")
        assertEquals(listOf("the Explore tab", "the Digital World Search card", "board: tapped nothing that moves",
                            "board: tapped nothing that moves", "the X", "the globe"),
                     p.stage.went, "in, one action, the pause, one action, and home: ${p.stage.went}")
        assertEquals("main", p.stage.screen)
        assertEquals(0, p.cap.withheld, "no tap tried with the switch off")
        assertTrue(r.chain.finished, "the chain goes on after the resumed step:\n$log")
        assertTrue(lines.any { it.startsWith("the chain is finished") }, log)
        board.release()
    }

    @Test
    fun `fully automatic, a board the player opened before the first step is still the player's`() {
        val board = frame("ws_board.png")
        val p = PausedPass(wayInAndOut(board, start = "board"))
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(p.cap, vision, { settings(1, navigate = true) }, log = { lines += it },
                                     on = { p.on }, sleep = {}, now = { 0.0 })
        val r = Rounds(p.cap, skill, SkillSettings.MODE_FULL, { p.on }, lines)
        val t = r.tick()
        val log = lines.joinToString("\n")
        assertEquals("The fully automatic mode starts from the main screen, and board is open.", t.note, log)
        assertEquals("The fully automatic mode starts from the main screen, and board is open.", r.director.parked)
        repeat(3) { r.tick() }
        // "Try again" looks afresh, and the board is still the player's.
        r.director.retry()
        r.tick()
        assertNotNull(r.director.parked, log)
        assertTrue(p.stage.taps.isEmpty(), "tapped the player's board: ${p.stage.taps}")
        assertTrue(lines.none { it.contains("chain: starting") || it.contains("chain: resuming") }, log)
        board.release()
    }

    @Test
    fun `fully automatic, a board the player left and opened again during the pause is the player's`() {
        val board = frame("ws_board.png")
        val p = PausedPass(wayInAndOut(board))
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(p.cap, vision, { settings(0, navigate = true) }, log = { lines += it },
                                     on = { p.on }, sleep = {}, now = { 0.0 })
        val r = Rounds(p.cap, skill, SkillSettings.MODE_FULL, { p.on }, lines)
        assertEquals("run mini", r.tick().did)
        assertEquals("paused, sees board", r.tick().note)
        // The player closes the board by hand and opens it again.
        p.stage.show("afterx")
        assertEquals("paused, sees explore_menu", r.tick().note)
        p.stage.show("board")
        assertEquals("paused, sees board", r.tick().note)
        p.on = true
        // The park after its second look (Director.SECOND_LOOK, the player's
        // rule of 2026-09-30): the first look back on stands still and says
        // so, and the board is still the player's after it.
        assertEquals("back on: the board is not one I go on from -- looking once more before I park",
                     r.tick().note, lines.joinToString("\n"))
        var t = r.tick()
        while (r.director.parked == null && r.t < 120.0) t = r.tick()
        val log = lines.joinToString("\n")
        assertEquals("The fully automatic mode starts from the main screen, and board is open.", t.note, log)
        assertTrue(lines.none { it.contains("chain: resuming") }, log)
        assertEquals(3, p.stage.went.size, "nothing tapped after the pause: ${p.stage.went}")
        board.release()
    }

    @Test
    fun `semi-automatic, a pass paused on the board goes on as the same visit`() {
        // Changed on 2026-09-30 (PLAN_RELEASE_1_3.md B4, the player's rule:
        // every task goes on after the pause, in both modes). Until then the
        // semi-automatic mode handed the board back once per visit, and a
        // resume was the same visit, done: "Digital World Search has worked
        // this board; leaving it to you" after one action. Now the stopped
        // work goes on on the board, and only a work that ended in its own
        // way is the visit's.
        val board = frame("ws_board.png")
        val p = PausedPass(wayInAndOut(board, start = "board"))
        var limit = 0
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(p.cap, vision, { settings(limit, navigate = true) }, log = { lines += it },
                                     on = { p.on }, sleep = {}, now = { 0.0 })
        val r = Rounds(p.cap, skill, SkillSettings.MODE_SEMI, { p.on }, lines)
        assertEquals("work mini", r.tick().did, lines.joinToString("\n"))
        repeat(3) { assertEquals("paused, sees board", r.tick().note) }
        limit = 1
        p.on = true
        assertEquals("work mini", r.tick().did, lines.joinToString("\n"))
        repeat(3) { r.tick() }
        val t = r.tick()
        val log = lines.joinToString("\n")
        assertEquals(1, lines.count { it == "resuming Digital World Search on the board" }, log)
        assertEquals("Digital World Search has worked this board; leaving it to you", t.note, log)
        assertNull(r.director.parked, log)
        assertEquals(listOf("board: tapped nothing that moves", "board: tapped nothing that moves"), p.stage.went,
                     "one action, the pause, one action on the same board, then nothing: ${p.stage.went}")
        assertEquals("board", p.stage.screen, "the semi-automatic work leaves the board standing")
        assertEquals(0, p.cap.withheld, "no tap tried with the switch off")
        assertTrue(lines.none { it.contains("chain:") }, log)
        board.release()
    }

    // ---- the counters: F18 (a counter the start's merge missed) and F19 (a
    // reading cut short); notes/world-search.md, "A number with digits missing
    // is not the number"
    //
    // Painted on the live run's own frames (more-skins 220440 / 220445 /
    // 220448, metres 32320, 32323, 32324) and only with their own pixels: the
    // metre label's digits are covered with the label's own colour, the way
    // the figure covers them in the bottom row.

    private val runFrames = listOf("more-skins-FILTER-C-2026-08-25 220440.png",
                                   "more-skins-FILTER-C-2026-08-25 220445.png",
                                   "more-skins-FILTER-C-2026-08-25 220448.png")

    /** [img] with the metre label covered from [fromX] (a fraction of the box) to its right end by its own plate. */
    private fun coverMeters(img: Mat, fromX: Double): Mat {
        val calib = vision.calibrate(img)
        val (x, y, w, h) = calib.roi("roi_meters").toList()
        val out = img.clone()
        val box = out.submat(y, y + h, x, x + w)
        // The plate is what is not digit: the label's pixels under grey 175,
        // the bright mask `segmentDigits` reads the metres with.
        val grey = Mat()
        Imgproc.cvtColor(box, grey, Imgproc.COLOR_BGR2GRAY)
        val px = ArrayList<DoubleArray>()
        for (r in 0 until box.rows()) for (c in 0 until box.cols()) {
            if (grey.get(r, c)[0] < 175) px.add(box.get(r, c))
        }
        fun median(i: Int) = px.map { it[i] }.sorted()[px.size / 2]
        val plate = org.opencv.core.Scalar(median(0), median(1), median(2))
        val x0 = (fromX * w).toInt()
        box.submat(0, h, x0, w).setTo(plate)
        grey.release()
        return out
    }

    /** [img] with its metre label cut so that exactly [digits] read, searched from the right. */
    private fun cutMeters(img: Mat, digits: Long): Mat {
        for (i in 100 downTo 0) {
            val cut = coverMeters(img, i / 100.0)
            if (vision.readCounters(cut, vision.calibrate(cut))["meters"] == digits) return cut
            cut.release()
        }
        throw AssertionError("no cut of the metre label reads $digits")
    }

    @Test
    fun `a counter the start's merge missed is read off the calm frame the pass starts on`() {
        // B7, Seraphimon: `meters None` at the start while the calm frame the
        // pass then used read 47283, and the TODAY card booked 0 metres. Here
        // the metres are covered on every frame up to the first pause -- the
        // calibration's five, the merge's one, the calm frame's first look --
        // and whole after it, as a sparkle over the label comes and goes.
        val frames = runFrames.map { frame(it) }
        val covered = coverMeters(frames[0], 0.0)
        assertEquals(null, vision.readCounters(covered, vision.calibrate(covered))["meters"],
                     "the painting has to hide the metres")
        var whole = false
        var taps = 0
        val cap = object : Capture {
            override fun grab(): Mat = Mat().also { (if (whole) frames[minOf(taps, 2)] else covered).copyTo(it) }
            override fun tap(x: Int, y: Int) { taps += 1 }
            override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
            override fun back() = throw AssertionError("no back key here")
            override fun inFront(): String? = GAME
        }
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(2) }, log = { lines += it },
                                     sleep = { whole = true }, now = { 0.0 })

        val outcome = skill.work(covered)
        val log = lines.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, "$outcome\n$log")
        assertTrue(lines.any { it.startsWith("counters at start: paws 998, claws 142, fireballs 16, meters 32320") },
                   "no None at the start:\n$log")
        assertEquals(4, skill.lastCounts["meters"], "32320 to 32324 on the card: ${skill.lastCounts}\n$log")
        (frames + covered).forEach { it.release() }
    }

    @Test
    fun `a meter reading cut short is thrown away, named, and its frame kept`() {
        // The second action's frame has its metre label cut to 323 -- the
        // head of 32324, as 477 was of 47733 on the landscape pass. The
        // tracker believes 32323 by then (a plausible step from 32320), so
        // this is a cut and nothing else; the third action reads the whole
        // 32324 again, one step on.
        val frames = runFrames.map { frame(it) }
        val cut = cutMeters(frames[2], 323)
        val cap = Replay(listOf(frames[0], frames[1], cut, frames[2]))
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(3) }, log = { lines += it },
                                     keep = { _, t -> kept += t }, sleep = {}, now = { 0.0 })

        val outcome = skill.work(frames[0])
        val log = lines.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, "$outcome\n$log")
        assertTrue(lines.any { it == "     a reading of meters cut short, thrown away: 32323 against 323 -- " +
                                     "frame counter_cut_00" }, log)
        assertEquals(listOf("board_start_00", "counter_cut_00"), kept, log)
        assertTrue(lines.none { it.contains("pulled straight") }, log)
        assertTrue(lines.any { it.startsWith("  counters:") && it.contains("meters 32324") } ||
                   lines.any { it.contains("meters +1") }, "the whole reading after it is a step:\n$log")
        (frames + cut).forEach { it.release() }
    }

    // ---- the cell over the figure (PLAN_WORLD_SEARCH_FORMATE.md F16;
    // notes/world-search.md, "The cell over the figure is not read as empty")

    @Test
    fun `the unseen cell over a tall partner is not the first step, two passes running`() {
        // The skin tour's Imperialdramon FM on 1080 x 1920, the frame its pass
        // began on: the pyramid in r4c2 behind its head reads as nothing, and
        // the first action was `step up r4c2` -- `no_effect, claws -1`. The
        // paw in r3c3 is three steps away by r4c2 or by r5c3. The capture
        // never moves, so each action comes back without effect and the next
        // decision is taken on the same board.
        val board = frame("board_skin10_1080x1920_none0_164502.png")
        val calib = vision.calibrate(board)
        val figure = vision.findFigure(board, calib, vision.loadTemplates())
        assertTrue(figure != null && figure.row == 4 && figure.col == 1, "the figure on r5c2: ${figure?.toOracle()}")
        val grid = vision.readGrid(board, calib, vision.boardTemplates(), figure = figure).first
        assertNull(grid[3][1], "the pyramid behind the head has to read as nothing: $grid")
        assertEquals("paw", grid[2][2], "$grid")
        val cap = Replay(listOf(board))
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(2) }, log = { lines += it }, sleep = {}, now = { 0.0 })
        for (pass in 1..2) {
            val outcome = skill.work(board)
            assertEquals(Result.DONE, outcome.result, "pass $pass: $outcome")
        }
        val log = lines.joinToString("\n")
        assertEquals(4, lines.count {
            it.contains(". step right r5c3 (towards paw, 3 steps, 243 Bits, round the unseen cell over the figure)")
        }, log)
        assertTrue(lines.none { it.contains("step up r4c2") }, log)
        assertEquals(List(4) { vision.cellCenter(calib, 4, 2) }, cap.points, "every tap on r5c3, none on r4c2")
        board.release()
    }

    // ---- which look confirmed (PLAN_WORLD_SEARCH_FORMATE.md F15)

    /** Two boards of the live run, one on per tap; with [late], the first look after a tap still sees the old one. */
    private class Alternating(private val a: Mat, private val b: Mat, private val late: Boolean) : Capture {
        var taps = 0
        private var sinceTap = 0
        override fun grab(): Mat {
            sinceTap += 1
            val shown = if (late && sinceTap == 1) taps - 1 else taps
            return Mat().also { (if (shown % 2 == 0) a else b).copyTo(it) }
        }
        override fun tap(x: Int, y: Int) { taps += 1; sinceTap = 0 }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun back() = throw AssertionError("no back key here")
        override fun inFront(): String? = "com.bandainamcoent.dgup_ww"
    }

    @Test
    fun `the pace goes down on first looks only, and the look is in the result`() {
        // 998 and 995 paws, one board and the next: every tap flips the frame,
        // so every step is confirmed by the paws. On time, the first look sees
        // it and from the third action on the pace goes down; a look late,
        // the second one does, and the pace stays where it began -- the rule
        // as engine.py wrote it, which is what the format tour saw on 31 of
        // 35 passes.
        val a = frame(runFrames[0])
        val b = frame(runFrames[1])
        val calib = vision.calibrate(a)
        for (late in listOf(false, true)) {
            val cap = Alternating(a, b, late)
            var t = 0.0
            val actor = Actor(cap, vision, calib, clickDelay = 0.7, settle = 0.45, dryRun = false,
                              log = {}, sleep = { t += it }, now = { t })
            var before: Map<String, Long> = vision.readCounters(a, calib).filterValues { it != null }
                .mapValues { it.value!! }
            val looks = ArrayList<Int>()
            repeat(5) {
                val r = actor.perform(Action("step", "right", 1 to 2), before, 1)
                assertEquals(Actions.OK, r.state, "late $late: ${r.deltas}")
                looks.add(r.look)
                assertEquals(r.look, r.lookAt.size, "one time per look taken")
                before = r.counters
            }
            if (!late) {
                assertEquals(listOf(1, 1, 1, 1, 1), looks)
                assertEquals(0.9 * 0.9 * 0.9, actor.pace, 1e-9, "three clean actions, then down each time")
            } else {
                assertEquals(listOf(2, 2, 2, 2, 2), looks)
                assertEquals(1.0, actor.pace, "a second look is no clean action")
            }
        }
        listOf(a, b).forEach { it.release() }
    }

    @Test
    fun `the skill says which look confirmed each action, and how the pass went`() {
        val a = frame(runFrames[0])
        val b = frame(runFrames[1])
        val cap = Alternating(a, b, late = true)
        var t = 0.0
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(2) }, log = { lines += it },
                                     sleep = { t += it }, now = { t })
        skill.work(a)
        val log = lines.joinToString("\n")
        // settle 0.45 at pace 1.00, then clickDelay / 3 -- 0 in these settings.
        assertEquals(2, lines.count { it.endsWith("pace 1.00, tick 0.00 s, look 2 at 0.45/0.45 s") }, log)
        assertTrue(lines.any { it == "confirmed on look 2: 2; the first look 0.45 to 0.45 s after the tap, " +
                                     "the confirming one 0.45 to 0.45 s; pace 1.00, tick 0.00 s" }, log)
        listOf(a, b).forEach { it.release() }
    }

    // ---- the board's memory and its anchor (PLAN_BEFUNDE_1_3.md N1;
    // notes/world-search.md, "The board's memory holds only as long as its
    // scroll is the board's")
    //
    // The live run's 220440 and 220445 again, and one painting between them:
    // 220445 with 220440's metre label pasted over its own -- the paws have
    // come (995), the metres not yet (32320), as the Poco's first look saw
    // them after a step out of column 2.

    /** [img] with [from]'s metre label scaled over its own. */
    private fun metresOf(img: Mat, from: Mat): Mat {
        val out = img.clone()
        paste(from, roiBox(vision.calibrate(from), "roi_meters"), out, roiBox(vision.calibrate(img), "roi_meters"))
        return out
    }

    /** After n taps the grabs show [after]`[n]` one by one, its last frame standing. */
    private class Script(private val after: List<List<Mat>>) : Capture {
        var taps = 0
        private var sinceTap = 0
        val points = ArrayList<Pair<Int, Int>>()
        override fun grab(): Mat {
            val seq = after[minOf(taps, after.size - 1)]
            val shown = seq[minOf(sinceTap, seq.size - 1)]
            sinceTap += 1
            return Mat().also { shown.copyTo(it) }
        }
        override fun tap(x: Int, y: Int) { points.add(x to y); taps += 1; sinceTap = 0 }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun back() = throw AssertionError("no back key here")
        override fun inFront(): String? = "com.bandainamcoent.dgup_ww"
    }

    private fun known(img: Mat): Map<String, Long> =
        vision.readCounters(img, vision.calibrate(img)).filterValues { it != null }.mapValues { it.value!! }

    @Test
    fun `a step out of column 2 waits for its metre, and the next step is not confirmed by it`() {
        val a = frame(runFrames[0])
        val b = frame(runFrames[1])
        val half = metresOf(b, a)
        // The painting's own proof: the paws of the frame after, the metres of the one before.
        assertEquals(995L, known(half)["paws"], "${known(half)}")
        assertEquals(32320L, known(half)["meters"], "${known(half)}")
        assertEquals(32323L, known(b)["meters"])

        // The step's first look sees the painting, the second the frame after;
        // then a step down whose tap does nothing -- the board stays on 220445.
        val cap = Script(listOf(listOf(a), listOf(half, b), listOf(b)))
        var t = 0.0
        val actor = Actor(cap, vision, vision.calibrate(a), clickDelay = 0.7, settle = 0.45, dryRun = false,
                          log = {}, sleep = { t += it }, now = { t })
        val step = actor.perform(Action("step", "right", 1 to 2), known(a), 1)
        assertEquals(Actions.OK, step.state)
        assertEquals(2, step.look, "the paw alone is not the answer: ${step.deltas}")
        assertEquals(mapOf("paws" to -3L, "meters" to 3L), step.deltas)

        val nothing = actor.perform(Action("step", "down", 2 to 1), step.counters, 1)
        assertEquals(Actions.NO_EFFECT, nothing.state,
                     "the metre belonged to the step before, and this tap moved nothing: ${nothing.deltas}")
        listOf(a, b, half).forEach { it.release() }
    }

    @Test
    fun `an answer that comes after the last look is carried, scroll and all`() {
        // Two frames of the landscape pass of 2026-09-29, one action apart:
        // on 024400 the figure stands in r3c2 with a paw in r3c3 (paws 1761,
        // metres 47799), on 024405 it has stepped onto it (1764, 47800). The
        // first action's three looks see the board as it was; the resync's
        // grab after the banner's two seconds sees the frame after -- the
        // Poco's `366.`, its metre come late. Against the old resync the World
        // stays where it was and the second action is decided one column
        // behind the board.
        val a = frame("counter_cut_608x1080_window0_024400.png")
        val b = frame("counter_cut_608x1080_window0_024405.png")
        val cap = Script(listOf(listOf(a), listOf(a, a, a, b), listOf(b)))
        val lines = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(2) }, log = { lines += it },
                                     sleep = {}, now = { 0.0 })
        val outcome = skill.work(a)
        val log = lines.joinToString("\n")
        assertEquals(Result.DONE, outcome.result, "$outcome\n$log")
        assertTrue(lines.any { it == "  1. step right r3c3 (towards paw, 1 steps, 80 Bits) -- " +
                                     "row 3 column 2 (global 1) scroll 0" }, log)
        val late = lines.indexOf("     the answer came late: paws +3, meters +1 -- carried as ok")
        assertTrue(late > 0 && lines[late - 1].startsWith("     no_effect, nothing moved"),
                   "said under the result it corrects:\n$log")
        val second = lines.first { it.startsWith("  2. ") }
        assertTrue(second.endsWith("(global 2) scroll 1"), "the World scrolled with the board:\n$log")
        listOf(a, b).forEach { it.release() }
    }

    @Test
    fun `a board the player closes is handed back with no tap more, and the next pass goes home as ever`() {
        val board = frame("ws_board.png")
        val main = frame("ws_main_adb.png")
        val menu = frame("ws_menu.png")
        val afterX = frame("ws_afterx.png")
        val fieldFile = File(repo, "corpus/farm/field_cans_013050.png")
        assertTrue(fieldFile.exists(), "no corpus frame at $fieldFile")
        val field = Imgcodecs.imread(fieldFile.path)
        assertEquals(Director.FIELD, Director.classify(field).screen, "the Meat Field has to read as itself")
        assertNull(Explore.closeButton(field), "and show no X")
        val x = Explore.closeButton(board)!!
        val globe = Dungeon.homeButton(afterX)!!
        val stage = Stage("board",
            mapOf("main" to main, "menu" to menu, "board" to board, "afterx" to afterX, "field" to field),
            listOf(Stage.Route("board", "afterx", pixel(board, x.fx, x.fy), name = "the X"),
                   Stage.Route("afterx", "main", pixel(afterX, globe.fx, globe.fy), name = "the globe")))
        // Pass 1: right after the first action on the board the player opens
        // the Meat Field, as on the Poco at 20:04.
        var leave = true
        val cap = Gated(stage, { true }) { st ->
            if (leave && st.screen == "board" && st.went.last().endsWith("tapped nothing that moves")) st.show("field")
        }
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        val skill = WorldSearchSkill(cap, vision, { settings(5, navigate = true) }, log = { lines += it },
                                     keep = { _, t -> kept += t }, sleep = {}, now = { 0.0 })
        val first = skill.run()
        var log = lines.joinToString("\n")
        assertEquals(Result.DONE, first.result, "$first\n$log")
        assertEquals("the board was closed", first.why)
        assertEquals(1, stage.taps.size, "the one action before the board went, and nothing after: ${stage.went}")
        assertEquals("field", stage.screen, "the field stays the player's: ${stage.went}")
        assertTrue(lines.any { it.startsWith("the board is gone (field in front) -- handing back, no tap more") }, log)
        assertTrue("board_gone_00" in kept, "$kept")
        assertTrue(lines.none { it.contains("leaving the Digital World Search") || it.contains("could not get back") }, log)

        // Pass 2: the player is back on the board, and the pass ends on its
        // limit and goes home by the X and the globe, as every pass does.
        leave = false
        stage.show("board")
        lines.clear()
        val second = skill.run()
        log = lines.joinToString("\n")
        assertEquals(Result.DONE, second.result, "$second\n$log")
        assertEquals("action limit reached", second.why)
        assertEquals("main", stage.screen, "home again:\n$log\n${stage.went}")
        assertEquals(listOf("the X", "the globe"),
                     stage.went.drop(1).filter { !it.endsWith("tapped nothing that moves") }, "${stage.went}")
        listOf(board, main, menu, afterX, field).forEach { it.release() }
    }
}
