package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * Digital World Search, the minigame, under the director: `engine.py`'s loop
 * with `world`, `planner`, `tracker`, `actions` and `router` beside it, and
 * `explore.Nav` for the way in and the way out.
 *
 * The seam is the one the plan names (PLAN_ANDROID_3_DIRECTOR.md 3.2):
 *
 *   run  = engine.py:297  `engine.run`: `open_board` (explore.py:600), the
 *          loop, `leave_board` (explore.py:645) in a finally
 *   work = engine.py:350  `find_start` on the board that is there, then
 *          `_run_loop`; it ends on the board, where the player left it
 *
 * Three things are deliberately not the Python line, and each is a phone
 * fact rather than a change of mind:
 *
 *  - **No pause, one switch.** `engine._run_loop` asks `stop.is_set()` and
 *    then `stop.wait_while_paused()`, and re-reads the board afterwards
 *    because a human may have moved the figure while it was paused. The
 *    phone has no pause: [MainSwitch] is the one stop, asked between two
 *    actions and never inside one, and an app that is switched off is not
 *    coming back mid-run. So the switch is one question at the top of the
 *    round, and the re-read after a pause has no counterpart here.
 *  - **No cold start and no hotkeys.** `autostart` starts LDPlayer and
 *    `guard.start` registers F7/F8; a phone has neither. The director owns
 *    the beat and the switch.
 *  - **The frame it gives up on goes to [keep], not to a file.** core writes
 *    no files (PLAN_ANDROID_APP.md 3.5); the app writes what it is handed
 *    only with the developer's switch, and the log names the frame's tag
 *    either way. The counting is `engine._save_debug`'s:
 *    [DEBUG_IMAGE_LIMIT] per kind, after that only counted, because the
 *    tenth resync of the same kind shows nothing new.
 *
 * What is the Python line, to the letter, is everything that decides a tap:
 * the board's own stillness rule ([quietFrame]), the two-sightings map, the
 * cost search, the verification against the counters, and the pace.
 */
class WorldSearchSkill(
    private val cap: Capture,
    private val vision: Vision,
    /** Read fresh every round, as the PC reads its page's variables. */
    private val settings: () -> WorldSearchSettings,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** A frame worth looking at afterwards, under a tag (the app's `keep`). */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    /** The main switch, asked between two actions. */
    private val on: () -> Boolean = { MainSwitch.on },
    private val sleep: (Double) -> Unit = { Thread.sleep((it * 1000).toLong()) },
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) : Skill {

    override val key = "mini"
    override val name = "Digital World Search"

    /**
     * The board and nothing else. `work` is given the frame the director
     * classified as [Director.BOARD] -- `explore.close_button` found the
     * violet X on it -- and it hands that same board back.
     */
    override fun worksOn(screen: String): Boolean = screen == Director.BOARD

    /** `chain.mini_has_target(wanted)` (chain.py:104), which is [Chain.miniHasTarget]. */
    override fun hasBudget(): Boolean = Chain.miniHasTarget(settings().wanted)

    // ------------------------------------------------------------------------
    // The kept frames
    // ------------------------------------------------------------------------
    companion object {
        /**
         * Limit debug images. A run of several hours would otherwise produce
         * hundreds of them. Only the first few cases are kept, after that
         * just counted.
         */
        const val DEBUG_IMAGE_LIMIT = 8

        // explore.py's navigator. Same numbers as dungeon.go_home, same
        // reason: one tap is enough from a settled screen, the rest exist
        // because a tap sent into an animation is swallowed, and a look that
        // finds nothing spends itself waiting rather than tapping again.
        const val NAV_TAPS_MAX = 3
        const val NAV_ROUNDS = 6

        // The X pops one screen only, the Explore menu, never further. Kept
        // at summon.py's own numbers regardless: a run that gives up on the
        // way out is worse than one that tried a few times too many.
        const val EXIT_ROUNDS = 20
        const val EXIT_TAPS_MAX = 4
        const val EXIT_ROUND = 0.6

        /** `dungeon.HOME_ROUNDS` and `HOME_PRESSES_MAX`. */
        const val HOME_ROUNDS = 6
        const val HOME_PRESSES_MAX = 3

        /** `DungeonBot(patience=1.5).pause_long`. */
        const val PAUSE_LONG = 1.5

        /** `dungeon.NEUTRAL_TAP_Y`: a tap high up, outside whatever is in front. */
        const val NEUTRAL_TAP_Y = 0.12
    }

    private val debugCounts = LinkedHashMap<String, Int>()

    /** What the last run counted, for the TODAY card ([SkillStats], [Counted]). */
    var lastCounts: Map<String, Int> = emptyMap()
        private set

    /** `engine._save_debug`: keep the first few of a kind, count the rest. */
    private fun saveDebug(img: Mat, kind: String): String? {
        val n = debugCounts[kind] ?: 0
        debugCounts[kind] = n + 1
        if (n >= DEBUG_IMAGE_LIMIT) {
            if (n == DEBUG_IMAGE_LIMIT) {
                log("further $kind frames will only be counted, not saved")
            }
            return null
        }
        val tag = "%s_%02d".format(kind, n)
        keep(img, tag)
        return tag
    }

    fun debugCounts(): Map<String, Int> = LinkedHashMap(debugCounts)

    /**
     * Every counter the run believes, None for one it has never read. The
     * run wrote none of them until 2026-09-24, and a player's "it never
     * dashes" could not be told apart from a fireball counter that does not
     * read on their phone.
     */
    private fun countersText(values: Map<String, Long>): String {
        fun v(key: String) = values[key]?.toString() ?: "None"
        return "paws %s, claws %s, fireballs %s, meters %s, top %s/%s/%s".format(
            v("paws"), v("claws"), v("fireballs"), v("meters"),
            v("top_orange"), v("top_green"), v("top_pink"))
    }

    // ------------------------------------------------------------------------
    // The board's own readings
    // ------------------------------------------------------------------------
    /**
     * `engine.merge_counters`: merge counters across several frames.
     *
     * A single frame may fall inside an animation, making a counter
     * unreadable. Across three to four frames, practically all values show
     * up. The first readable value per counter counts. Not
     * [Actions.mergeCounters]: that one also classifies the banner and has
     * its own default for [need], and the two are separate functions in
     * Python for that reason.
     */
    private fun mergeCounters(calib: Calib, tries: Int = 4, pause: Double = 0.25,
                              need: List<String> = listOf("paws")): Pair<Map<String, Long>, Mat?> {
        val merged = LinkedHashMap<String, Long>()
        var last: Mat? = null
        for (i in 0 until tries) {
            last = cap.grab()
            for ((key, value) in vision.readCounters(last, calib)) {
                if (value != null && key !in merged) merged[key] = value
            }
            if (need.all { it in merged }) break
            sleep(pause)
        }
        return merged to last
    }

    /** What [quietFrame] came back with. */
    class Quiet(val img: Mat?, val grid: List<List<String?>>?, val calm: Boolean)

    /**
     * `engine.quiet_frame`: wait for a calm frame before reading the board.
     *
     * Animations run after an action. While collecting, a ticket icon flies
     * across the board and would be logged as an object on the wrong cell.
     * Calm means two frames produce the same grid.
     *
     * This is the board's stillness rule, and it is the skill's own. The
     * director's three-second rule does not apply here -- the board's figure
     * and banner measure 0.08 to 0.17 between frames all by themselves
     * (notes/director.md, and [Director.MOVES_BY_ITSELF] has [Director.BOARD] in it
     * for exactly this reason), so the director hands the board over at once
     * and this decides when a move may go out.
     */
    fun quietFrame(calib: Calib, templates: Map<String, Mat>, tries: Int = 4,
                   pause: Double = 0.25, first: Mat? = null, assumeCalm: Boolean = false,
                   figure: Figure? = null): Quiet {
        val board = vision.boardTemplates(templates)
        var lastGrid: List<List<String?>>? = null
        var img: Mat? = null
        var grid: List<List<String?>>? = null
        if (first != null) {
            img = first
            lastGrid = vision.readGrid(img, calib, board, figure = figure).first
            grid = lastGrid
            if (assumeCalm) return Quiet(img, grid, true)
        }
        for (i in 0 until tries) {
            img = cap.grab()
            grid = vision.readGrid(img, calib, board, figure = figure).first
            if (lastGrid != null && grid == lastGrid) return Quiet(img, grid, true)
            lastGrid = grid
            sleep(pause)
        }
        return Quiet(img, grid, false)
    }

    /**
     * `engine.board_visible`: is the minigame open? Geometry must fit and
     * the figure must be found. Both together are reliable evidence, neither
     * alone is.
     */
    fun boardVisible(img: Mat, templates: Map<String, Mat>): Pair<Calib?, Figure?> {
        val calib = try {
            vision.calibrate(img)
        } catch (e: CalibrationError) {
            return null to null
        }
        if (vision.bannerVisible(img, calib)) return calib to null
        return calib to vision.findFigure(img, calib, templates)
    }

    /**
     * `engine.wait_for_board`: wait until the minigame is visible.
     *
     * This covers the case where the navigation failed and the player opens
     * the board by hand. It is the fallback for a run with `navigate` off;
     * with `navigate` on, [Nav.openBoard] walks there itself.
     *
     * What it gives up on is kept, for the same reason [findStart] keeps its
     * frame: a wait that ran out says nothing about whether the board was
     * never opened or was opened and not recognised.
     */
    fun waitForBoard(templates: Map<String, Mat>, seconds: Int, poll: Double = 1.5): Boolean {
        val deadline = now() + seconds
        var said = false
        var last: Mat? = null
        while (now() < deadline) {
            if (!on()) return false
            val img = try {
                cap.grab()
            } catch (e: CaptureError) {
                log("no frame: ${e.message}")
                sleep(poll)
                continue
            }
            last = img
            val (calib, figure) = boardVisible(img, templates)
            if (calib != null && figure != null) {
                log("minigame recognised, taking over")
                return true
            }
            if (!said) {
                log("waiting for the minigame, please navigate there yourself")
                said = true
            }
            sleep(poll)
        }
        log("minigame not recognised within $seconds s")
        if (last != null) {
            saveDebug(last, "no_board")?.let { log("the last frame: $it") }
        }
        return false
    }

    /** What [findStart] hands back. */
    class Start(val calib: Calib, val img: Mat, val grid: List<List<String?>>,
                val figure: Figure, val counters: Map<String, Long>)

    /**
     * `engine.find_start`: wait for a calm frame in which geometry, figure
     * and paws are certain.
     *
     * Whatever it gives up on, it keeps the frame. A run that ends here used
     * to leave nothing behind but "no portrait-format card found", which
     * says what the reader missed and not what was on the screen -- and the
     * two answers ("the board was not open" against "the board was open and
     * the reader could not see it") need completely different work. The
     * frame is the only thing that tells them apart afterwards (the
     * laboratory's notes, "A run that gives up must keep the frame it gave
     * up on").
     */
    fun findStart(templates: Map<String, Mat>, s: WorldSearchSettings): Start? {
        val samples = ArrayList<Calib>()
        var calib: Calib? = null
        var last: Mat? = null
        var ever = false
        for (i in 0 until 30) {
            if (!on()) return null
            var img = cap.grab()
            last = img
            val fresh = try {
                vision.calibrate(img)
            } catch (e: CalibrationError) {
                log("calibration not possible yet: ${e.message}")
                sleep(0.4)
                continue
            }
            ever = true
            if (vision.bannerVisible(img, fresh)) {
                log("banner visible, waiting")
                sleep(0.6)
                continue
            }
            samples.add(fresh)
            calib = vision.medianCalib(samples)
            if (samples.size < s.calibFrames) continue

            val (counters, merged) = mergeCounters(calib)
            if (merged != null) {
                img = merged
                last = merged
            }
            var figure = vision.findFigure(img, calib, templates)
            if (figure != null && counters["paws"] != null) {
                val quiet = quietFrame(calib, templates)
                val qimg = quiet.img ?: img
                figure = vision.findFigure(qimg, calib, templates) ?: figure
                // Every counter the merge did not have, off the calm frame the
                // pass starts on. The merge stops at the first frame that
                // reads the paws, and a counter that did not read on exactly
                // that frame began the pass unknown: live on 2026-09-28
                // (PLAN_WORLD_SEARCH_FORMATE.md F18, B7, Seraphimon at 1080 x
                // 1920) `meters None` at the start while the calm frame the
                // pass then used read 47283 -- and the TODAY card, which counts
                // the metres from the start, booked 0 for twelve `meters +1`.
                // One reading of a frame already in hand, no grab more; the
                // merge's own values stand where it has them (not in engine.py).
                val all = LinkedHashMap(counters)
                if (quiet.img != null) {
                    for ((key, value) in vision.readCounters(qimg, calib)) {
                        if (value != null && key !in all) all[key] = value
                    }
                }
                return Start(calib, qimg, quiet.grid ?: emptyGrid(), figure, all)
            }
            log("waiting for a calm frame, figure %s, paws %s"
                    .format(figure?.how ?: "None", counters["paws"]?.toString() ?: "None"))
            sleep(0.5)
        }

        if (!ever) {
            log("the minigame board was never on the screen. Open Digital World Search in " +
                "the game yourself, wait for the board, then start again.")
        }
        if (last != null) {
            saveDebug(last, "no_start")?.let {
                log("the frame it gave up on: $it")
            }
        }
        return null
    }

    private fun emptyGrid(): List<List<String?>> =
        List(Vision.ROWS) { List<String?>(Vision.COLS) { null } }

    // ------------------------------------------------------------------------
    // The seam
    // ------------------------------------------------------------------------
    /**
     * The second half: the board is standing in front of us, work it and
     * leave it standing. [img] is the frame the director classified; the
     * loop takes its own from here, because [findStart] needs
     * `calib_frames` of them to take a median over and a single frame
     * cannot say whether the board is still.
     */
    override fun work(img: Mat): Outcome = runLoop(settings(), waitFirst = false)

    /**
     * The whole: open the board from the main screen, work it, and go back
     * there.
     *
     * `open_board` is inside the try, not before it: a run that fails to
     * reach the board still needs its way back, the same "every way out, not
     * only the good one" rule that puts `go_home` in a finally elsewhere. A
     * return before the finally would skip it.
     */
    /** The chain's second hand (Skill.leave): the X and the globe, `run`'s own way out. */
    override fun leave(): Boolean = Nav(settings()).leaveBoard()

    /**
     * The board (Skill.resumesOn): [run] taps nothing on a standing board
     * ("the board is already open", Nav.openBoard) and begins its pass there
     * as every pass begins, from [findStart]'s own look -- which a board the
     * player touched during the pause needs anyway. Nothing of the stopped
     * pass is carried over: it handed its counts to the TODAY card as it
     * stopped, and the pass that goes on counts from its own start
     * (PLAN_WORLD_SEARCH_FORMATE.md F27; notes/director.md, "A pass the
     * switch paused goes on where it stands, and a screen the player opened
     * is still the player's").
     */
    override fun resumesOn(screen: String): Boolean = screen == Director.BOARD

    override fun run(): Outcome {
        val s = settings()
        val nav = if (s.navigate) Nav(s) else null
        boardClosed = false
        return try {
            if (nav != null && !nav.openBoard() && !s.dryRun) {
                // A way in that the main switch ended is a stop, not a board
                // that would not open: `openBoard` gives up the moment the
                // switch goes off, and "could not open" was a false sentence
                // and a park the player had not earned (PLAN_WORLD_SEARCH_
                // FORMATE.md F13; notes/world-search.md, "After the pause the
                // game stays where it is").
                if (!on()) {
                    Outcome.STOPPED
                } else {
                    log("could not open the Digital World Search")
                    Outcome.parked("I could not open the Digital World Search from the main screen.")
                }
            } else {
                runLoop(s, waitFirst = true)
            }
        } finally {
            if (!boardClosed) nav?.leaveBoard()
        }
    }

    // ------------------------------------------------------------------------
    // The loop
    // ------------------------------------------------------------------------
    /** Whether [clearCounters] asked the overlay off the board's top counters this pass. */
    private var cleared = false

    /**
     * Whether this pass ended because somebody else closed the board. Then
     * the screen in front is theirs, and [run] does not walk it home: the
     * player's rule for a screen the player opened (notes/director.md, "A
     * pass the switch paused goes on where it stands, and a screen the
     * player opened is still the player's").
     */
    private var boardClosed = false

    /** [runLoopClear], and the overlay back at the player's place however it ends. */
    private fun runLoop(s: WorldSearchSettings, waitFirst: Boolean): Outcome =
        try {
            runLoopClear(s, waitFirst)
        } finally {
            if (cleared) cap.overlayBack()
            cleared = false
        }

    /**
     * The overlay off the rows of the board's three top counters for the
     * run (`Capture.overlayClear`), as off Network Defense Ops' counter and
     * the Digivice bar (PLAN_FORMATE.md V15). Measured 2026-09-27 on
     * `corpus/formats/board_1080x1920_none0_225956` with the dot and plate
     * the app draws: with the dot at fy 0.060 and 0.065, the top of the
     * player's strip, the plate (x 602 to 938, rows 38 to 85 and 48 to 95)
     * lies on `top_pink` and reads it null; from 0.070 on it reads 1499.
     * `top_pink` is one of the counters a pickup is verified by (Actions,
     * `pickup`) and part of what the TODAY card calls rewards. Moved off,
     * the dot stands just below them, at 0.1091 on that frame, where the
     * whole board reads as unmasked. Over the canvas ceiling (1080 x 2520)
     * the board stands in the middle of the headroom and all three read at
     * every fy of the strip; their rows still touch the dot's at 0.060, and
     * it steps to 0.0659 for the run, which costs nothing. The rows are the
     * calibration's own boxes, in the HUD's fractions the overlay speaks
     * (Dungeon.gameRect of the frame, as Dungeon.bottomFy).
     */
    private fun clearCounters() {
        val img = try { cap.grab() } catch (e: CaptureError) { return }
        try {
            val calib = try { vision.calibrate(img) } catch (e: CalibrationError) { return }
            val boxes = listOf("roi_top_orange", "roi_top_green", "roi_top_pink").mapNotNull { calib.rois[it] }
            if (boxes.isEmpty()) return
            val r = Dungeon.gameRect(img)
            val y0 = boxes.minOf { it[1] }
            val y1 = boxes.maxOf { it[1] + it[3] }
            cap.overlayClear((y0 - r.y0) / r.gh.toDouble(), (y1 - r.y0) / r.gh.toDouble())
            cleared = true
        } finally {
            img.release()
        }
    }

    /** `engine._run_loop`. [waitFirst] is `settings.wait_for_board`, which only `run` reaches. */
    private fun runLoopClear(s: WorldSearchSettings, waitFirst: Boolean): Outcome {
        // This pass has counted nothing yet, and there are four ways out of
        // here before it counts anything. Left standing, the last pass's
        // moves and metres would go on the card again every time the board
        // did not open.
        lastCounts = emptyMap()
        boardClosed = false
        val templates = vision.loadTemplates()
        if (templates.isEmpty()) {
            log("no templates found in the templates folder")
            return Outcome.parked("The board's templates are missing from this build.")
        }

        if (waitFirst && s.waitForBoard > 0) {
            if (!waitForBoard(templates, s.waitForBoard)) {
                return if (!on()) Outcome.STOPPED
                       else Outcome.parked("The board did not appear within ${s.waitForBoard} s.")
            }
        }

        clearCounters()
        val started = findStart(templates, s)
        if (started == null) {
            if (!on()) return Outcome.STOPPED
            log("no usable start frame")
            return Outcome.parked(
                "I could not read the board.")
        }
        val calib = started.calib

        val world = World(isSelected = s.wanted)
        // Observe twice, because an object needs two sightings to be
        // confirmed. At the start the frame is calm, so that is not a concern.
        world.observe(started.grid, started.figure)
        world.observe(started.grid, started.figure)

        val plan = Planner(
            world, minPaws = s.minPaws, targetMeters = s.targetMeters,
            bitPaw = s.bitPaw, bitClaw = s.bitClaw, bitSkill = s.bitSkill,
            bitPerAction = s.bitPerAction, bitPyramidLoot = s.bitPyramidLoot,
            rowSlack = s.rowSlack, bitLeftPenalty = s.bitLeftPenalty,
            bitMiddleBias = s.bitMiddleBias, dashEager = s.dashEager)
        val actor = Actor(cap, vision, calib, clickDelay = s.clickDelay, settle = s.settle,
                          minPace = s.minPace, adaptive = s.adaptive, dryRun = s.dryRun,
                          log = log, sleep = sleep, now = now)
        // Which look confirmed each action, for the pass's last lines (F15).
        val confirmedOn = java.util.TreeMap<Int, Int>()
        val firstLooks = ArrayList<Double>()
        val confirmingLooks = ArrayList<Double>()
        val countersState = CounterTracker(maxActions = 1)
        countersState.update(started.counters)

        log("cell %.2f x %.2f, start row %d column %d"
                .format(calib.cellW, calib.cellH, started.figure.row + 1, started.figure.col + 1))
        log("counters at start: " + countersText(countersState.values))
        // The board as the run found it, once a run: the corpus had no board
        // from a phone at all when the counters' reading there was the
        // question (2026-09-24), and this frame is whole, so it can go into
        // corpus/explore as it comes.
        saveDebug(started.img, "board_start")?.let { log("the start frame: $it") }
        var countersShown = countersState.values.keys.toSet()

        var done = 0
        val startMeters = countersState.values["meters"]
        // The board's three top counters as the pass found them. They are
        // the only counters that can never fall (TrackerConst.ONLY_UP), so
        // what they have gained by the end is what this pass picked up off
        // the board -- read the same way as the metres, off the counters the
        // run verifies every action against, and not guessed from the moves.
        val startTops = TrackerConst.ONLY_UP.associateWith { countersState.values[it] }
        var reason = "action limit reached"
        var stopped = false
        // The park a pass ends in when the figure is stuck (F11), or null.
        var parkWhy: String? = null
        // Whether the board and the counters have been looked at again since
        // the planner last said "stuck".
        var lookedAgain = false
        var img: Mat? = started.img

        loop@ while (s.maxActions == 0 || done < s.maxActions) {
            // The main switch, between two actions and never inside one.
            if (!on()) {
                reason = "stopped by the main switch"
                stopped = true
                break@loop
            }

            // The board, every round: its X on the last frame, or on a fresh
            // one. A Poco F3 report of 2026-10-01: the player closed the board
            // and opened the Meat Field, and the pass went on -- four rounds,
            // four taps into the field's plots, the dot saying Digital World
            // Search -- because nothing in the loop asked: a step that moved
            // no counter resynced on the field, `findFigure` found a figure
            // there on the board's old calibration, and the planner planned
            // on. Neither of the two the start asks is a test of the board:
            // over the corpus's 1856 frames, `calibrate` and `findFigure` both
            // answer on 8 that are no board (Special Summon pages, an
            // Overdrive page, a main screen), where `Explore.closeButton`
            // answers on none; it answers on 151 boards, a banner over the
            // board included, and misses one: LDPlayer's `double` artefact, a
            // board drawn for the display before (notes/formats.md, "A frame
            // taken right after a display change"). Two frames running, as
            // every state change: the X gone from one frame is an animation
            // until a second says so. Gone, the pass hands back without a tap
            // and leaves the screen to whoever opened it -- no way home
            // ([boardClosed]). Not in engine.py.
            if (img == null || Explore.closeButton(img) == null) {
                val again = cap.grab()
                if (Explore.closeButton(again) == null) {
                    val kept = saveDebug(again, "board_gone")
                    log("the board is gone (${Director.classify(again).screen} in front) -- " +
                        "handing back, no tap more" + (kept?.let { "; frame $it" } ?: ""))
                    reason = "the board was closed"
                    boardClosed = true
                    break@loop
                }
                img = again
            }

            // The counters again every 25 actions, and whenever one is read
            // for the first time: a counter the tracker believes never goes
            // back to unknown, so that is the one change there is to say.
            if (done > 0 && (done % 25 == 0 || countersState.values.keys != countersShown)) {
                countersShown = countersState.values.keys.toSet()
                log("  counters: " + countersText(countersState.values))
            }
            val action = plan.nextAction(countersState.values)
            log("  %d. %s -- %s".format(done + 1, action.toString(), world.describe()))

            if (action.kind == "stop") {
                reason = action.reason
                break@loop
            }

            // Walled in with no claws and no fireballs (PLAN_WORLD_SEARCH_
            // FORMATE.md F11): nothing on the board can move the figure, and
            // a wait would stand there, a round every 1.5 s, until the main
            // switch. The player's rule of 2026-09-28 -- "then the bot should
            // give an error" -- is a park: a sentence, the frame kept, and
            // no move counted for it. Not on one reading, though: the
            // counters a decision is made on are the tracker's, and after an
            // Insufficient banner a 0 there is the loop's own assumption
            // rather than something read. So the first "stuck" buys one more
            // look -- the board until it is calm, taken in twice as at the
            // start because a calm grid is two sightings, and the counters
            // read afresh -- and only a second "stuck" on that is the park.
            // A claw or a fireball that has come back in the meantime lets
            // the pass go on. Neither round is a move. Not in engine.py
            // (notes/world-search.md, "Walled in with nothing left to get out").
            if (action.kind == "stuck") {
                if (!lookedAgain) {
                    lookedAgain = true
                    log("     no way out -- one more look at the board and the counters")
                    actor.waitTick()
                    val quiet = quietFrame(calib, templates)
                    img = quiet.img ?: img
                    val seen = quiet.img
                    if (quiet.grid != null && seen != null) {
                        val figure = vision.findFigure(seen, calib, templates)
                        world.observe(quiet.grid, figure)
                        if (quiet.calm) world.observe(quiet.grid, figure)
                    }
                    countersState.update(mergeCounters(calib, need = listOf("claws", "fireballs")).first)
                    log("     looked again: " + countersText(countersState.values))
                    continue@loop
                }
                val kept = img?.let { saveDebug(it, "walled_in") }
                kept?.let { log("the frame it parked on: $it") }
                reason = "walled in with no claws and no fireballs"
                parkWhy = "The figure is walled in with no claws and no fireballs -- stuck until they " +
                    "recharge."
                break@loop
            }
            lookedAgain = false

            if (action.kind == "wait") {
                actor.waitTick()
                val quiet = quietFrame(calib, templates)
                img = quiet.img
                if (quiet.grid != null && img != null) {
                    world.observe(quiet.grid, vision.findFigure(img, calib, templates))
                }
                done += 1
                continue
            }

            val before = LinkedHashMap(countersState.values)
            val result = actor.perform(action, before, world.col!!)
            img = result.img ?: img

            if (actor.dryRun) {
                // In a dry run, the effect is assumed, so the planner can be
                // judged over several steps.
                applyAction(world, action)
                actor.waitTick()
                val quiet = quietFrame(calib, templates)
                img = quiet.img
                if (quiet.grid != null && img != null) {
                    world.observe(quiet.grid, vision.findFigure(img, calib, templates))
                }
                done += 1
                continue
            }

            if (result.state == Actions.BANNER_UNKNOWN) {
                val roi = img?.let { vision.bannerRoi(it, calib) }
                if (roi != null) saveDebug(roi, "banner_unknown")
                log("unknown banner text, aborting")
                reason = "unknown banner text"
                break@loop
            }

            // The resync's late answer, said under the result it corrects.
            var cameLate: String? = null
            when (result.state) {
                Actions.OK -> {
                    applyAction(world, action)
                    countersState.update(result.counters)
                }
                Actions.INSUFFICIENT -> {
                    val resource = when (action.kind) {
                        "destroy" -> "claws"
                        "skill" -> "fireballs"
                        else -> "paws"
                    }
                    countersState.values[resource] = 0
                    log("$resource used up, planner adapts")
                    if (resource == "paws") {
                        reason = "no paws left"
                        break@loop
                    }
                    sleep(2.1)
                }
                Actions.MOVED_INSTEAD -> {
                    movedInstead(world, action)
                    countersState.update(result.counters)
                }
                else -> {
                    img?.let { saveDebug(it, "resync") }
                    sleep(2.1)  // the banner takes about two seconds to fade
                    val fresh = actor.grab()
                    img = fresh
                    val read = vision.readCounters(fresh, calib)
                    // An answer after the last look is still the action's
                    // answer. The figure is found again below, but a step
                    // out of column 2 leaves it in column 2, and the one
                    // witness that the board scrolled is the metre: carried
                    // as nothing, the step put the World's scroll one column
                    // behind the board's, and everything it remembered one
                    // column off. A Poco F3 report of 2026-10-01: `366. step
                    // right r3c3` came back `no_effect, nothing moved` after
                    // looks at 0.45/0.87/1.32 s and its metre came in the
                    // resync; two actions on, `368. step up r2c2` was a claw
                    // on a pyramid the World had held since it came into
                    // view, forgotten when the frames put it one column off
                    // (notes/world-search.md, "The board's memory holds only
                    // as long as its scroll is the board's"). So the resync's
                    // reading is judged as a fourth look -- on the board
                    // only, where the readers' numbers mean the board's
                    // counters -- and an action it confirms is carried as if
                    // the third look had. Not in engine.py.
                    if (result.state == Actions.NO_EFFECT && Explore.closeButton(fresh) != null) {
                        val late = LinkedHashMap<String, Long>()
                        for ((k, v) in read) {
                            val old = before[k] ?: continue
                            if (v != null && v != old) late[k] = v - old
                        }
                        when (val again = actor.judge(action, world.col!!, late, null)) {
                            Actions.OK, Actions.MOVED_INSTEAD -> {
                                cameLate = "     the answer came late: ${deltaText(late)} -- carried as $again"
                                if (again == Actions.OK) applyAction(world, action) else movedInstead(world, action)
                            }
                        }
                    }
                    val found = vision.findFigure(fresh, calib, templates)
                    if (found != null) {
                        world.row = found.row
                        world.col = minOf(found.col, WorldConst.FIG_COL_MAX)
                    }
                    countersState.update(read)
                }
            }

            log("     %s, %s, %s%s".format(result.state, deltaText(result.deltas), actor.tempo(), lookText(result)))
            cameLate?.let { log(it) }
            if (result.state == Actions.OK && result.look > 0) {
                confirmedOn.merge(result.look, 1, Int::plus)
                result.lookAt.firstOrNull()?.let { firstLooks.add(it) }
                confirmingLooks.add(result.lookAt[result.look - 1])
            }
            for ((k, old, new) in countersState.suspicious) {
                if (Triple(k, old, new) in countersState.cut) {
                    // A reading with digits missing (F19, CounterTracker.cutShort):
                    // never the truth, and the frame is what the reader's own
                    // fix wants -- the tour had none, because only a no_effect
                    // kept one.
                    val kept = img?.let { saveDebug(it, "counter_cut") }
                    log("     a reading of $k cut short, thrown away: $old against $new" +
                        (kept?.let { " -- frame $it" } ?: ""))
                } else {
                    log("     a reading of $k thrown away: $old against $new")
                }
            }
            for ((k, old, new) in countersState.resynced) {
                log("     $k pulled straight: $old was stale, $new is the truth")
            }

            actor.waitTick()
            // Without collecting anything the board stays still, so the
            // check frame is enough.
            val picked = result.deltas.values.any { it > 0 }
            // `figure=(world.row, world.col)` at the Python call site, where
            // `_is_cross_neighbour` takes a pair as readily as a dict. The
            // Kotlin reader takes a Figure, and only its row and column are
            // ever read, so this carries the pair in one.
            val where = Figure(world.row!!, world.col!!, "bookkeeping", null)
            val quiet = quietFrame(calib, templates, tries = 2, pause = 0.15 * actor.pace,
                                   first = img, assumeCalm = !picked, figure = where)
            img = quiet.img
            if (!quiet.calm) log("     frame still unsettled, observation provisional")
            quiet.grid?.let { world.observe(it, null) }
            done += 1
        }

        if (debugCounts.isNotEmpty()) {
            log("debug frames: " + debugCounts.entries.sortedBy { it.key }
                .joinToString(", ") { "${it.key} ${it.value}" })
        }
        // The pace in one line a phone report can answer from: which look at
        // the counters confirmed the actions, and when those looks came. Only
        // the first counts as clean (Actor.onCleanSuccess), so a pass that
        // stayed at `pace 1.00` says here whether the game had not moved its
        // counters yet at the first look (PLAN_WORLD_SEARCH_FORMATE.md F15).
        if (confirmedOn.isNotEmpty()) {
            fun span(xs: List<Double>) =
                if (xs.isEmpty()) "-" else "%.2f to %.2f s".format(xs.minOrNull()!!, xs.maxOrNull()!!)
            log("confirmed on look " + confirmedOn.entries.joinToString(", ") { "${it.key}: ${it.value}" } +
                "; the first look ${span(firstLooks)} after the tap, the confirming one ${span(confirmingLooks)}" +
                "; ${actor.tempo()}")
        }
        log("$name: $reason after $done action" + (if (done == 1) "" else "s") +
            ", ${world.describe()}")
        // What the TODAY card adds up (SkillStats, Counted): the moves made,
        // the metres the board's own counter moved while they were made, and
        // what the three top counters gained -- the counters, not a guess
        // from the actions, because a counter is what the run already
        // verifies every action against.
        val gained = countersState.values["meters"]
        val rewards = TrackerConst.ONLY_UP.sumOf { key ->
            val from = startTops[key]
            val to = countersState.values[key]
            if (from != null && to != null && to > from) (to - from).toInt() else 0
        }
        lastCounts = mapOf(
            "actions" to done,
            "meters" to (if (startMeters != null && gained != null && gained > startMeters)
                (gained - startMeters).toInt() else 0),
            "rewards" to rewards)
        return when {
            stopped -> Outcome.STOPPED
            parkWhy != null -> Outcome.parked(parkWhy)
            else -> Outcome(Result.DONE, reason)
        }
    }

    private fun deltaText(deltas: Map<String, Long>): String =
        if (deltas.isEmpty()) "nothing moved"
        else deltas.entries.joinToString(", ") { "${it.key} ${if (it.value > 0) "+" else ""}${it.value}" }

    /** ", look 2 at 0.46/1.12 s": the look that decided, and when each look began after the tap (F15). */
    private fun lookText(r: Performed): String =
        if (r.look == 0) ""
        else ", look ${r.look} at " + r.lookAt.joinToString("/") { "%.2f".format(it) } + " s"

    /** A click on a pyramid that was already gone: carried as the step it became. */
    private fun movedInstead(world: World, action: Action) {
        log("pyramid was already gone, logged as step ${action.direction}")
        // engine.py's column, kept: right is figGcol + 1 and every other
        // direction figGcol + 0, which for a step left is the figure's own
        // column and not the one the pyramid stood in (figGcol - 1). It only
        // matters when a pyramid to the left vanishes under a tap; the log
        // line above says when that happens (2026-09-24, noted, not changed).
        world.forget(world.figGcol!! + (if (action.direction == "right") 1 else 0),
                     action.cell!!.first)
        world.applyStep(action.direction!!)
        world.markCollected(world.figGcol!!, world.row!!)
    }

    /** `engine._apply`: carry the confirmed action into the map. */
    private fun applyAction(world: World, action: Action) {
        when (action.kind) {
            "step" -> {
                world.applyStep(action.direction!!)
                world.markCollected(world.figGcol!!, world.row!!)
            }
            "destroy" -> world.forget(world.toGlobal(action.cell!!.second), action.cell.first)
            "skill" -> {
                val gain = world.applySkill()
                // The skill collects everything and clears pyramids along its path
                for (back in 0..gain) {
                    world.forget(world.figGcol!! - back, world.row!!)
                    world.markCollected(world.figGcol!! - back, world.row!!)
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // The navigator (explore.Nav)
    // ------------------------------------------------------------------------
    /**
     * `explore.Nav`: opens the board from the main screen and returns there
     * when the run is done.
     *
     * Three screens, two taps, three checks -- never whether the screen
     * before is gone, always whether the one after has arrived, and twice
     * running, so an animation mid-transition is not mistaken for the real
     * thing.
     *
     *   Main screen   -- tap the Explore tab -->   Explore menu
     *   Explore menu  -- tap the card        -->   Board
     *   Board         -- tap the X           -->   Explore menu
     *   Explore menu  -- tap the globe       -->   Main screen
     *
     * Python borrows a `DungeonBot` for tapping and for `go_home`. There is
     * no bot on this side, so [goHome] is the same routine written out here,
     * with one branch that could not come along: `return_to_list` is the
     * dungeon's own way out of a dungeon panel and means nothing on this
     * path, so a frame with no nav bar on it is waited out instead of being
     * tapped at. Nothing is tapped blindly either way.
     */
    private inner class Nav(private val s: WorldSearchSettings) {

        private fun tap(img: Mat, fx: Double, fy: Double, was: String,
                        anchor: Dungeon.Anchor = Dungeon.Anchor.BOTTOM) {
            // The board's X is read where the board stands (Explore.BOARD)
            // and tapped there; everything else here is the HUD's.
            val r = Dungeon.gameRect(img, anchor)
            val x = Py.roundInt(r.x0 + fx * r.gw)
            val y = Py.roundInt(r.y0 + fy * r.gh)
            if (s.dryRun) {
                log("    [dry run] click %s at rel. %.3f, %.3f, pixel %d,%d".format(was, fx, fy, x, y))
                return
            }
            cap.tap(x, y)
        }

        /**
         * False when the run must not go on. `wait_while_paused` alone
         * answers only the pause question; the phone has one switch, and
         * this is it.
         */
        private fun gate(): Boolean = on()

        private fun dump(img: Mat, tag: String) {
            saveDebug(img, tag)?.let { log("    kept what it saw as $it") }
        }

        /** Whether [stays] has said its sentence on this way out. */
        private var saidStays = false

        /**
         * The main switch is off, so the way out ends here: the player's rule
         * of 2026-09-28, "after the pause the game stays where it is"
         * (PLAN_WORLD_SEARCH_FORMATE.md F13). Said once, nothing kept -- a
         * game that stays where the player paused it is not a failure to get
         * home, and a frame of it shows nothing anybody needs.
         */
        private fun stays(): Boolean {
            if (on()) return false
            if (!saidStays) {
                saidStays = true
                log("the main switch is off -- not going home, the game stays where it is")
            }
            return true
        }

        /** The window of the game's that ended this way out ([over]), or null. */
        private var met: String? = null

        /**
         * One of the game's own windows over [img] -- its news after the
         * reset, "Time Sale!", its Help tutorial -- ends the way out here,
         * nothing tapped and no frame kept: the director reads them too, and
         * closes the news (Stays.over, PLAN_RELEASE_1_3.md B60).
         */
        private fun over(img: Mat): Boolean {
            val what = Stays.window(img) ?: return false
            if (met == null) {
                met = what
                log("the game's $what is over the way home -- leaving it to the director, nothing tapped")
            }
            return true
        }

        /**
         * Wait for [recogniser] to answer twice running, at most
         * [NAV_ROUNDS] rounds. Twice and not once: a frame mid-animation can
         * show a state briefly that has not really arrived yet.
         */
        private fun waitFor(recogniser: (Mat) -> Boolean): Mat? {
            var confirmedOnce = false
            for (i in 0 until NAV_ROUNDS) {
                if (!gate()) return null
                val img = cap.grab()
                if (recogniser(img)) {
                    if (confirmedOnce) return img
                    confirmedOnce = true
                } else {
                    confirmedOnce = false
                }
                sleep(PAUSE_LONG)
            }
            return null
        }

        /**
         * Tap [fx], [fy], wait for [recogniser], retap up to [NAV_TAPS_MAX]
         * times if it does not come. Never checks whether the screen tapped
         * from is gone, only whether the one tapped towards has arrived.
         */
        private fun tapUntil(img: Mat, fx: Double, fy: Double, recogniser: (Mat) -> Boolean,
                             was: String): Mat? {
            for (i in 0 until NAV_TAPS_MAX) {
                tap(img, fx, fy, was)
                if (s.dryRun) return null
                val seen = waitFor(recogniser)
                if (seen != null) return seen
                if (!on()) return null
            }
            return null
        }

        /**
         * Main screen -> Explore menu -> board. True once the board is up.
         *
         * If the board is already open, nothing is tapped at all -- that is
         * the normal case for anyone who starts this way by hand, and the
         * reason this takes nothing from them.
         */
        fun openBoard(): Boolean {
            var img = cap.grab()
            if (Explore.closeButton(img) != null) {
                log("the board is already open")
                return true
            }
            if (Dungeon.stageFailed(img)) {
                log("the Stage Failed banner is up, tapping it away first")
                tap(img, 0.5, NEUTRAL_TAP_Y, "dismiss Stage Failed")
                sleep(PAUSE_LONG)
                img = cap.grab()
            }
            // Over a few seconds, not on one frame: the game draws over the
            // auto button by itself (MainScreen, K2 of PLAN_ABSCHLUSS_1_3.md).
            img = MainScreen.settle(img, cap::grab, sleep, now, ::gate, log) { last, waited ->
                log("not the plain main screen after %.0f s, cannot open the Digital World Search".format(waited))
                dump(last, "not_main_screen")
            } ?: return false
            val tab = Explore.exploreTab(img)
            if (tab == null) {
                log("the Explore tab was not found")
                dump(img, "no_explore_tab")
                return false
            }
            val menu = tapUntil(img, tab.fx, tab.fy, { Explore.exploreMenu(it) != null },
                                "Explore tab")
            if (menu == null) {
                if (on()) {
                    log("the Explore menu did not open")
                    dump(cap.grab(), "no_explore_menu")
                }
                return false
            }
            // exploreMenu already found the card; asking again would be the
            // same search twice for no reason, and would fail differently
            // than the check that just passed.
            val card = Explore.exploreMenu(menu)
            if (card == null) {
                log("the Explore menu was recognised and then was not")
                dump(menu, "no_explore_menu")
                return false
            }
            val board = tapUntil(menu, card.fx, card.fy, { Explore.closeButton(it) != null },
                                 "Digital World Search card")
            if (board == null) {
                if (on()) {
                    log("the board did not open")
                    dump(cap.grab(), "no_board")
                }
                return false
            }
            return true
        }

        /**
         * Tap the X, then the globe. True once the main screen is back.
         * Bounded, and gated on the main switch ([stays]).
         *
         * The gate is new, and the old sentence here said why there was
         * none: "by the time this runs the switch is usually already off --
         * one of the ways a run ends -- and a gate here would skip the one
         * step whose whole point is where the game is left". Since
         * 2026-09-22 the service holds every gesture while the switch is off
         * (DigiAutotapService.withheld; notes/director.md, "The director asks
         * \"is the game in front\" and \"is the switch on\" once a round"),
         * so after a pause the X and the globe were withheld, the rounds ran
         * out -- 13 to 15 s -- and the run said "could not get back to the
         * main screen" and kept `no_way_home` and `no_way_back`: on every one
         * of the 35 passes the format tour ended with the dot
         * (PLAN_WORLD_SEARCH_FORMATE.md F13). The player's rule of 2026-09-28
         * is that the game stays where the pause found it, and the withheld
         * tap stays too. So with the switch off this taps nothing, keeps
         * nothing and says so once; a run that ended on its limit, a park or
         * anything else goes home as it always did, and stops where it
         * stands if the switch goes off on the way.
         */
        fun leaveBoard(): Boolean {
            var taps = 0
            for (round in 0 until EXIT_ROUNDS) {
                if (stays()) return false
                val img = cap.grab()
                if (over(img)) return false
                if (Dungeon.autoButton(img) != null) {
                    if (taps > 0) log("back on the main screen")
                    return true
                }
                if (Explore.exploreMenu(img) != null) {
                    // The X has landed. The second step is exactly
                    // dungeon.go_home: look for the globe, tap it at most
                    // three times, confirm with auto_button.
                    return goHome()
                }
                val x = Explore.closeButton(img)
                if (x == null) {
                    // Mid-animation, or a screen this skill did not open.
                    // Nothing here is worth tapping blindly at.
                    sleep(EXIT_ROUND * s.patience)
                    continue
                }
                if (taps >= EXIT_TAPS_MAX) break
                if (taps == 0) log("leaving the Digital World Search")
                tap(img, x.fx, x.fy, "X to close the Digital World Search", x.anchor)
                taps += 1
                if (s.dryRun) {
                    // Nothing was clicked, so nothing will change; sitting
                    // out the remaining rounds would only report a failure
                    // that never happened.
                    return false
                }
                sleep(EXIT_ROUND * s.patience)
            }

            // The rounds ran out. Whatever is on the screen, this skill does
            // not know it -- but the nav bar is drawn across most of the
            // game's list side, so the globe is worth one honest try before
            // giving up. It is not a blind tap: goHome taps only a globe it
            // has found and confirms with auto_button.
            //
            // This is not theory. When world_search_card could not see the
            // card behind a red brick wall, explore_menu answered null, the
            // branch above was the only route to the globe, and the player
            // was left sitting in the Explore menu -- with the globe plainly
            // on the frame the run saved as it gave up (the laboratory's
            // notes, "One route home is no route home").
            if (!s.dryRun && goHome()) return true
            if (stays() || met != null) return false
            log("could not get back to the main screen -- the game is left where it stands")
            dump(cap.grab(), "no_way_back")
            return false
        }

        /**
         * `dungeon.go_home`: leave the game on its main screen. True if it
         * is showing. What it checks after pressing is `auto_button`, not
         * "the globe is gone": independent evidence that the main screen is
         * in front and clear. It never taps a position -- the nav bar is
         * drawn only on the list side of the game, so if the globe is not on
         * the frame the answer is to look again, not to tap where it would
         * have been.
         */
        fun goHome(rounds: Int = HOME_ROUNDS): Boolean {
            var pressed = 0
            try {
                for (round in 0 until rounds) {
                    // The switch, as in leaveBoard: after the pause the game
                    // stays where it is (F13).
                    if (stays()) return false
                    val img = cap.grab()
                    if (over(img)) return false
                    if (Dungeon.autoButton(img) != null) {
                        if (pressed > 0) log("back on the main screen")
                        return true
                    }
                    val button = Dungeon.homeButton(img)
                    if (button == null) {
                        // No bar on this frame: either something is still
                        // open over it, or it is dimmed by a dialog.
                        sleep(PAUSE_LONG)
                        continue
                    }
                    if (pressed >= HOME_PRESSES_MAX) break
                    if (pressed == 0) log("pressing the home button")
                    tap(img, button.fx, button.fy, "home button")
                    pressed += 1
                    if (s.dryRun) return false
                    sleep(PAUSE_LONG)
                }
                if (stays() || met != null) return false
                log("could not get back to the main screen -- the game is left where it stands")
                dump(cap.grab(), "no_way_home")
            } catch (err: Exception) {
                // This runs on the way out. A run that ended because the
                // capture died would otherwise end with this error instead
                // of its own, and the real one is the one worth reading.
                log("the way home failed: $err")
            }
            return false
        }
    }
}

/**
 * What the World Search skill runs on, by the keys `app.py` used to save
 * them under (`_start_mini`, app.py:2152) and with that page's defaults --
 * not `engine.Settings`', where `min_paws` is 20 and the page offered 0.
 *
 * Every default below is now the whole of it but one: the old page is gone
 * since 2026-09-22, and [Stored.worldSearch] reads only [dashEager], the new
 * page's one switch since 2026-09-28 (SkillSettings). They
 * stay parameters because the engine is Python's, line for line, and
 * because a test drives the skill with its own -- WorldSearchFlowTest sets
 * `clickDelay` to 0, a `maxActions` of 1, and an empty `wanted`, which is
 * still what [hasBudget] answers "nothing to do" to.
 */
class WorldSearchSettings(
    /** `want_<key>`, the ticked items. Nothing ticked is "nothing to do". */
    val wanted: List<String> = WorldConst.ALL_WANTED,
    /** Stop below this many paws. 0 keeps going until they run out. */
    val minPaws: Int = 0,
    /** Stop after this many actions. 0 is no limit. */
    val maxActions: Int = 0,
    /** Stop at this many metres. 0 is no limit. */
    val targetMeters: Int = 0,
    /** Seconds between clicks, before the pace factor. */
    val clickDelay: Double = 0.7,
    /** Speed up while it goes well. */
    val adaptive: Boolean = true,
    /**
     * Seconds to wait for a board the player opens by hand. Only the
     * fallback now that [navigate] is on: three minutes, because 0 is the
     * old refusal that gave up in fifteen seconds on a board nobody had
     * opened yet.
     */
    val waitForBoard: Int = 180,
    /** Open the board from the main screen and go back there. Always on. */
    val navigate: Boolean = true,
    /** `engine.Settings.dry_run`: decide and log, tap nothing. */
    val dryRun: Boolean = false,
    val settle: Double = 0.45,
    val minPace: Double = 0.60,
    val calibFrames: Int = 5,
    /** `explore.Nav.patience`. */
    val patience: Double = 1.5,
    val bitPaw: Int = PlannerConst.BIT_PAW,
    val bitClaw: Int = PlannerConst.BIT_CLAW,
    val bitSkill: Int = PlannerConst.BIT_SKILL,
    val bitPerAction: Int = PlannerConst.BIT_PER_ACTION,
    val bitPyramidLoot: Int = PlannerConst.BIT_PYRAMID_LOOT,
    val rowSlack: Int = PlannerConst.ROW_SLACK,
    val bitLeftPenalty: Int = PlannerConst.BIT_LEFT_PENALTY,
    val bitMiddleBias: Int = PlannerConst.BIT_MIDDLE_BIAS,
    /** `mini_dash_eager`, the page's one switch: see Planner's `dashEager`. */
    val dashEager: Boolean = false,
)
