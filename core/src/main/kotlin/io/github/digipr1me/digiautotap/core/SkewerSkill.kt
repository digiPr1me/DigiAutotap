package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import kotlin.math.ceil

/**
 * Chef's Special under the director (PLAN_SKEWER.md SK2): the skewer
 * minigame of the Events window, played round after round until the day's
 * combos are counted, cut at the seam Skill.kt names for every walking skill
 * --
 *
 *   run  = the way in (the Events tile, the Chef's Special card, the menu),
 *          the rounds, and home in a finally
 *   work = the rounds from the menu, the stage popup or a round's result
 *          dialog the player has open, ending on the menu
 *
 * The laboratory's `skewer.py` (`SkewerBot`) is the model of the round and
 * not its source ("One project"): read the order on two frames, tap the
 * ingredients one after another and count the taps, then Complete, then the
 * next order. What is the phone's own, each with its sentence where it
 * stands:
 *
 *  - **Every tap on a fresh frame.** The laboratory tapped every 330 ms and
 *    read a frame in 21 ms; here a frame costs a screenshot, 0.35 s at the
 *    least (notes/director.md, "takeScreenshot has a floor"), so each tap
 *    goes out on the frame taken for it -- the round still in play, the
 *    order still the one being built -- at the cell the grill was read in
 *    this round (`cells`), and [TAP_GAP] keeps the laboratory's pace as a
 *    floor.
 *  - **The plate is not read** (notes/skewer.md, "The plate is not a
 *    lattice, and it is not read"). A guest is judged after Complete, and
 *    only there ([judge], the one place): a life lost is a mistake, the
 *    bubble gone with the life kept is a guest served right.
 *  - **Combos, not guests** (PLAN_SKEWER.md 7, question 13; notes/skewer.md,
 *    "The daily mission counts combos, not guests in a row, and the combo
 *    starts again with every round"): a guest served right after a guest
 *    served right in the same round is one combo, the game's "N Combo"; a
 *    mistake, a guest nobody could judge and a new round start the running
 *    combo again, and what is counted stays counted -- over the rounds and
 *    over the passes of the game day ([comboCounted]).
 *  - **The way in and out are readers** (notes/skewer.md, "The way through is
 *    five taps, each on something read"): the tile, the card -- and never a
 *    card of another minigame --, Play Game, Start, Close, the pause menu's
 *    Quit.
 *
 * What it never does: tap a card in the Events window that is not Chef's
 * Special's, press OK on a prompt, send the back key, choose a stage (the
 * game chooses it, question 3), or tap with the main switch off -- the hand
 * withholds every gesture then (DigiAutotapService.withheld), and a pass the
 * switch stops leaves the game where it stands; its round ends by itself
 * within the minute, and its result dialog is work again. Since 2026-09-30
 * (PLAN_RELEASE_1_3.md B4) the pass goes on from there when the switch is
 * back ([resumesOn], [resume]), and every way home asks the switch first.
 */
class SkewerSkill(
    private val cap: Capture,
    private val icons: SkewerIcons,
    /** The page's one field and the day's count, asked afresh at the start of every pass. */
    private val settings: () -> Settings,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** The main switch, asked between two actions and never in the middle of one. */
    private val on: () -> Boolean = { MainSwitch.on },
    /** Keep a frame worth looking at afterwards. The app writes files; core cannot. */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    /**
     * One more combo for the day's count ([Settings.doneToday]), said the
     * moment it is counted, so that a pass the switch cuts short keeps what
     * it played. The app writes the file; core cannot.
     */
    private val comboCounted: () -> Unit = {},
    /** How many rounds a pass plays at most before it parks ([ROUNDS_MAX]). */
    private val roundsMax: Int = ROUNDS_MAX,
    private val patience: Double = 1.0,
    /** Injected so the flow test can play whole rounds without waiting. */
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    /** Seconds, monotonic. Injected so that the flow test can move it. */
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) : Skill {

    /**
     * The page's one field (SkillSettings, the skewer page) and what the game
     * day has counted so far (`Stored.skewer`): the combos a day wants, 16 by
     * default -- the event's mission counts fifteen and the sixteenth is the
     * player's margin (PLAN_SKEWER.md 3.1).
     */
    data class Settings(val combos: Int = COMBO_TARGET, val doneToday: Int = 0) {
        /** What is still owed today; the day is the game's, [QuestSkill.nextReset]. */
        val left: Int get() = maxOf(0, combos - doneToday)
    }

    /**
     * One frame of the round, as the loop reads it: the grill ([Skewer.grill]),
     * the round's two dialogs over it ([Skewer.over], [Skewer.paused], on one
     * reading of the grill), the order in the bubble -- asked only while the
     * round is in play, where a dialog or the countdown would read as
     * dark_meat (SkewerIcons.BUBBLE_WHITE) -- the lives, and, after
     * Complete, what the frame itself says of the skewer that went out
     * ([SkewerIcons.served]).
     */
    data class Look(val grill: Boolean, val over: Explore.Target? = null, val paused: Explore.Target? = null,
                    val order: List<String>? = null, val lives: Int? = null,
                    val served: SkewerIcons.Served? = null) {
        val playing: Boolean get() = grill && over == null && paused == null
    }

    /**
     * What a guest is judged against: the lives read with its order, whether
     * the bubble has gone (or another order stands) since Complete, and the
     * seconds since Complete.
     */
    data class Judging(val lives: Int, val gap: Boolean, val since: Double)

    override val key = KEY
    override val name = "Chef's Special"

    /**
     * The five screens of Chef's Special (Director.SKEWER_*): the menu, the
     * stage popup and a round's result dialog are where the work begins; a
     * round in play and its pause menu are this task's too -- [seesWork]
     * says no there, and the chain's second hand asks [leave] of whoever
     * works on a screen.
     */
    override fun worksOn(screen: String): Boolean = screen in SCREENS

    /** Asked afresh every round: a day that has its combos is nothing to do. */
    override fun hasBudget(): Boolean = settings().left > 0

    override fun noBudgetWhy(): String? {
        val s = settings()
        if (s.combos <= 0 || s.left > 0) return null
        return "${s.doneToday} of ${s.combos} combos counted today; the count starts again at the daily reset"
    }

    /**
     * The menu, the stage popup and a result dialog: work while a pass of
     * this task is owed and not finished -- a pass the switch cut short is
     * taken up again there, as Gekkomon Run's (RunnerSkill.seesWork) -- and
     * the director's own rules from then on. A round in play and its pause
     * menu never: a round the player plays by hand is theirs, and a round a
     * stopped pass left standing ends by itself within the minute, on a
     * result dialog, which is work again.
     */
    override fun seesWork(screen: String, img: Mat): Boolean? = when (screen) {
        Director.SKEWER_PLAY, Director.SKEWER_PAUSE -> false
        in SCREENS -> if (!finished && hasBudget()) true else null
        else -> null
    }

    companion object {
        const val KEY = "skewer"
        val SCREENS = setOf(Director.SKEWER_MENU, Director.SKEWER_STAGE, Director.SKEWER_PLAY,
                            Director.SKEWER_PAUSE, Director.SKEWER_OVER)
        /** The screens a pass the switch stopped goes on from ([resumesOn]). */
        val RESUMES = setOf(Director.SKEWER_MENU, Director.SKEWER_STAGE, Director.SKEWER_PLAY,
                            Director.SKEWER_OVER)

        /** The page's default: fifteen for the event's mission, one for the player's margin (3.1). */
        const val COMBO_TARGET = 16

        /**
         * How many rounds a pass plays at most (PLAN_SKEWER.md 3.1): a round
         * costs nothing, but a reader that never counts a combo would
         * otherwise play the day away. SK1's prototype played 10 to 11
         * guests a round, 9 combos the best; sixteen take two to three
         * rounds, and ten is three times that. Then the pass parks and says
         * why. Ten is the ceiling for the page's default; a pass that wants
         * more combos gets as many more rounds at the same rate ([roundsFor]).
         */
        const val ROUNDS_MAX = 10

        /**
         * The ceiling of rounds for a pass that wants [combos]: [roundsMax]
         * per [COMBO_TARGET] combos, and never under [roundsMax]. The page
         * goes to 999 since 2026-10-04, the player's wish, and ten rounds
         * count 80 to 110 at the live rate -- a pass that wanted more would
         * park "something is not read right" with every guest read right.
         * At 999 it is 625 rounds, five times the 125 that eight combos a
         * round take (notes/skewer.md, "The rounds a pass may play grow with
         * the combos it wants").
         */
        fun roundsFor(combos: Int, roundsMax: Int = ROUNDS_MAX): Int =
            maxOf(roundsMax, ceil(combos.toDouble() * roundsMax / COMBO_TARGET).toInt())

        /**
         * The floor between two taps. The laboratory's own number
         * (skewer.py, SkewerBot): 180 ms at first, then the player asked for
         * 45 % less speed after watching it reach for a grid the emulator
         * had not finished redrawing, "which costs an order rather than
         * saving time"; 180 / 0.55 is 327. On the phone a frame costs more
         * than this already, so it binds only where frames come faster.
         */
        const val TAP_GAP = 0.33

        /** Between two looks inside a round: the screenshot is the pace (its floor is 0.35 s). */
        const val ROUND_BEAT = 0.1
        /** Between two looks on the way in and out, where the game animates a popup. */
        const val NAV_BEAT = 0.7
        const val NAV_ROUNDS = 12
        const val NAV_TAPS_MAX = 3

        /**
         * How long the next order is waited for. A round opens with a
         * countdown in which the grill stands and the bubble does not
         * (notes/skewer.md, SkewerIcons.BUBBLE_WHITE: 0.000-0.038 of white),
         * then the first guest walks in; between two guests the bubble is
         * gone for a moment. Twelve seconds is the countdown four times over.
         * No order read in that time on a round in play is an order this
         * task cannot read -- six ingredients were never seen, and read null
         * (SkewerIcons.order) -- and the pass leaves the round and parks.
         */
        const val ORDER_WAIT = 12.0
        /**
         * Neither the grill nor a dialog for this long ends the looking. The
         * "PERFECT" burst at a round's end is no grill for the second it
         * lasts (Skewer.GRILL_POINTS), and the result dialog follows it.
         */
        const val ABSENT_WAIT = 5.0
        /** Frames inside one guest on which the round or the order does not read, before the guest is given up. */
        const val STRAY_LOOKS = 8

        /**
         * A skewer that has not said WRONG this long after Complete went out
         * right (notes/skewer.md, "One frame after Complete says right, wrong
         * or not yet, and "not yet" is not a mistake"): over the 54 serves
         * SK1 played back frame by frame, all five wrong ones said WRONG at 1
         * or 2 s, and no right one ever did. The bubble goes on a right one
         * (skewer_served 191312) and stays on a wrong one while the life
         * goes (skewer_mistake 191910).
         */
        const val JUDGE_SETTLE = 3.0
        /** How long a guest is judged at most after its Complete; nothing decided by then is UNCLEAR, and not counted. */
        const val JUDGE_WAIT = 8.0
        /**
         * Complete with no effect at all by now -- the same order still
         * standing, no bubble gone, no life lost -- was a tap the game did
         * not take, and it is tapped once more. A mistake has shown its lost
         * life by then ([JUDGE_SETTLE]), and a right one's bubble is gone.
         */
        const val COMPLETE_AGAIN = JUDGE_SETTLE

        /**
         * A round lasts 60 s, its countdown and its end a few more; past this
         * the loop is watching something that is not a round any more.
         */
        const val ROUND_TIME_MAX = 100.0

        /** The way in and out: at most this many screens walked. */
        const val WALK_STEPS = 8

        /**
         * The stage popup has no Close: a tap beside it closes it to the menu
         * (notes/skewer.md, "The way through is five taps"). Measured on the
         * six staged popups, 1080 x 1920 and 1080 x 2340 hole105 (a probe
         * over the popup's own blue, H 100-118 S >= 150): the popup stands at
         * fx 0.149-0.803, fy 0.313-0.714 of game_rect on both. The tap goes
         * between the picture's own left edge and the popup's left rim -- on
         * a long display the cover cuts the sides (notes/formats.md rule 3),
         * so the edge is what the picture holds, as Events.outside takes it
         * -- at fy 0.60, level with the popup's lower third, where the menu
         * behind it has bushes and no pennant (the Missions pennant ends at
         * fx 0.44 to the left). SK1 closed the popup by a tap beside it and
         * did not note where; this place is proved by SK3's live run, not
         * yet.
         */
        const val STAGE_POPUP_FX0 = 0.149
        const val STAGE_OUTSIDE_FY = 0.60

        /** The reason of a pass whose Events window had no Chef's Special card. */
        const val NOT_IN_WINDOW = "not in the Events window"
        const val NOT_IN_WINDOW_WHY = "Chef's Special is not in the Events window today."
        /** The reason of a pass that met an order it could not read. */
        const val UNREADABLE = "an order that does not read"

        /**
         * **The one place a guest is judged**, on one frame after Complete:
         * right, wrong, or not yet. What the frame itself says first
         * ([SkewerIcons.served], SK1's reader: a life less is WRONG, the pink
         * "N Combo" is RIGHT); where it says nothing, the rule its
         * measurement gave: no WRONG [JUDGE_SETTLE] after Complete, the lives
         * read as they were, the round in play and the bubble gone since
         * Complete -- the tap took; a served skewer "is known by the order
         * changing with the lives unchanged" (notes/skewer.md) -- is a guest
         * served right. Anything else is not yet, and the loop decides only on
         * two frames running that say the same ([judgeGuest]), so the lives
         * are read on two frames for every verdict.
         */
        fun judge(look: Look, j: Judging): SkewerIcons.Served {
            when (look.served) {
                SkewerIcons.Served.WRONG -> return SkewerIcons.Served.WRONG
                SkewerIcons.Served.RIGHT -> return SkewerIcons.Served.RIGHT
                else -> {}
            }
            val lives = look.lives ?: return SkewerIcons.Served.UNCLEAR
            if (lives < j.lives) return SkewerIcons.Served.WRONG
            if (lives > j.lives || !look.playing || !j.gap) return SkewerIcons.Served.UNCLEAR
            return if (j.since >= JUDGE_SETTLE) SkewerIcons.Served.RIGHT else SkewerIcons.Served.UNCLEAR
        }

        /**
         * One frame of the round ([Look]). With [livesBefore], the lives read
         * with the order before Complete, the frame is also asked what it
         * says of the skewer that went out ([SkewerIcons.served]).
         * [readOrder] false leaves the bubble unread where nothing asks it:
         * the order is the dearer reader of a frame, twelve templates laid
         * over the bubble.
         */
        fun look(img: Mat, icons: SkewerIcons, livesBefore: Int? = null, readOrder: Boolean = true): Look {
            // Skewer.over and Skewer.paused, on one reading of the grill: under
            // a dialog it is asked for Skewer.DIALOG_POINTS only.
            val grill = Skewer.grill(img)
            val dim = grill.red >= Skewer.DIALOG_POINTS
            val over = if (dim) Runner.resultDialog(img) else null
            val paused = if (dim && over == null) Runner.pauseDialog(img) else null
            val playing = over == null && paused == null
            if (playing && !grill.ok) return Look(false)
            return Look(true, over, paused,
                        order = if (playing && readOrder) icons.order(img)?.names else null,
                        lives = icons.lives(img)?.n,
                        served = livesBefore?.let { icons.served(img, it) })
        }

        /** Where a tap closes the stage popup ([STAGE_POPUP_FX0], [STAGE_OUTSIDE_FY]). */
        fun stageOutside(img: Mat): Explore.Target {
            val (x0, _, gw, _) = Dungeon.gameRect(img)
            val left = maxOf(0.0, -x0 / gw.toDouble())
            return Explore.Target((left + STAGE_POPUP_FX0) / 2, STAGE_OUTSIDE_FY, Skewer.STAGE)
        }
    }

    /** What one pass did. */
    class Stats {
        var rounds = 0
        var guests = 0
        var right = 0
        var mistakes = 0
        var unclear = 0
        var combos = 0
        /** The longest combo of a round, the game's own "N Combo". */
        var best = 0
        var reason = ""
    }

    // ------------------------------------------------------------------------
    /** The reference rect and headroom of the last frame read, which is what a tap is aimed with. */
    private var rect: Dungeon.GameRect? = null
    private var room = 0

    private var stats = Stats()
    /** Right guests in a row in this round: the combo the game shows is one less. */
    private var streak = 0
    /**
     * The grill's twelve as this round read them, [SkewerIcons.NAMES]' cells
     * row by row, or null until they are read. Read once a round and again
     * after a mistake, not before every tap: the reader lays twelve templates
     * on each of twelve cells, 475 ms a frame on the desktop at 805 wide and
     * 416 ms at 1080 (SK2's probe on a painted round) -- four times the
     * order's 48 and 180 ms -- and the twelve stood in the same cells on 391
     * of the 392 frames of the rounds SK1 played (SkewerIcons.grid). A
     * mistake is the one sign a cell might be misread, so it is read again.
     */
    private var cells: List<String>? = null
    private var lastTap = -9.0
    /** When this round's last Complete went out, NaN before its first: what a guest's pace is told from. */
    private var lastComplete = Double.NaN
    /** The combos this pass plays for, read off the page once per pass ([target]). */
    private var wanted: Int? = null
    /** A pass ended by reaching its combos, [ceiling] or an unreadable order -- not stopped, not lost ([seesWork]). */
    private var finished = false
    /** This pass found the Events window without a Chef's Special card. */
    private var notInWindow = false

    /** What the last pass found; null before the first. */
    var lastSession: Stats? = null
        private set

    /**
     * What this pass counted, for the TODAY card ([SkillStats], [Counted]):
     * the rounds, the combos, and the longest combo, which the day keeps the
     * best of ([SkillStats.How.BEST]).
     */
    val lastCounts: Map<String, Int>
        get() = lastSession?.let { mapOf("rounds" to it.rounds, "combos" to it.combos, "best" to it.best) }
            ?: emptyMap()

    /**
     * The page's number, asked once at the start of a pass and kept for it:
     * a pass that changed its target halfway would end on a count nobody set.
     */
    private fun target(): Int = wanted ?: settings().left.also { wanted = it }

    /** The rounds this pass plays at most: [roundsMax], more where the pass wants more combos ([roundsFor]). */
    private fun ceiling(): Int = roundsFor(target(), roundsMax)

    private fun grab(): Mat = cap.grab().also { rect = Dungeon.gameRect(it); room = Dungeon.headroom(it) }

    /** A tap in the rectangle [t] was read in (its anchor), on the last frame read. */
    private fun tap(t: Explore.Target, what: String) {
        // Not with the main switch off: the service would hold it back (PLAN_RELEASE_1_3.md B4).
        if (!on()) return
        val r = rect ?: return
        val y0 = r.y0 - Py.roundInt(room * t.anchor.share)
        cap.tap(Py.roundInt(r.x0 + t.fx * r.gw), Py.roundInt(y0 + t.fy * r.gh))
    }

    private fun dump(img: Mat, tag: String) {
        // Nothing failed where the switch cut a step short (PLAN_RELEASE_1_3.md B4).
        if (!on()) return
        keep(img, tag)
        log("    kept what it saw as $tag")
    }

    private fun keepNow(tag: String) {
        val img = try { cap.grab() } catch (e: CaptureError) { return }
        try { dump(img, tag) } finally { img.release() }
    }

    /** grab, read, release -- the shape of every "one answer off a fresh frame". */
    private fun <T> onFrame(read: (Mat) -> T): T {
        val img = grab()
        try {
            return read(img)
        } finally {
            img.release()
        }
    }

    private fun look(img: Mat, livesBefore: Int? = null, readOrder: Boolean = true): Look =
        look(img, icons, livesBefore, readOrder)

    private fun secs(s: Double): String = String.format(java.util.Locale.ROOT, "%.1f s", s)

    /**
     * Wait for [check] to be true twice running, at most [rounds] looks --
     * a frame mid-animation can show a state briefly that has not really
     * arrived yet. The frame that confirmed it, or null; the caller owns it.
     */
    private fun waitFor(rounds: Int = NAV_ROUNDS, gated: Boolean = true, check: (Mat) -> Boolean): Mat? {
        var confirmed = false
        for (i in 0 until rounds) {
            if (gated && !on()) return null
            val img = grab()
            if (check(img)) {
                if (confirmed) return img
                confirmed = true
            } else {
                confirmed = false
            }
            img.release()
            sleep(NAV_BEAT * patience)
        }
        return null
    }

    /**
     * A tap aimed off a fresh frame [read] answered on, then [check] on two
     * looks running; again, up to [NAV_TAPS_MAX] times, each on a fresh frame
     * the reader still answers on -- which is also what keeps a second tap out
     * of the screen the first one opened (RunnerSkill.tapRead).
     */
    private fun tapRead(what: String, read: (Mat) -> Explore.Target?, gated: Boolean = true,
                        rounds: Int = NAV_ROUNDS, check: (Mat) -> Boolean): Mat? {
        for (i in 0 until NAV_TAPS_MAX) {
            if (gated && !on()) return null
            val target = onFrame(read) ?: return null
            tap(target, what)
            val img = waitFor(rounds, gated, check)
            if (img != null) return img
        }
        return null
    }

    // ------------------------------------------------------------------------
    // The way in
    // ------------------------------------------------------------------------
    /** Main screen -> Events -> the Chef's Special menu. True once the menu or its stage popup shows. */
    fun goToMenu(): Boolean {
        val img = grab()
        try {
            if (Skewer.menu(img) != null || Skewer.stage(img) != null) {
                log("chef's special: the menu is already open")
                return true
            }
            if (Runner.eventsDialog(img) != null) return openCard()
            // Over a few seconds, not on one frame: the game draws over the
            // auto button by itself (MainScreen, K2 of PLAN_ABSCHLUSS_1_3.md).
            val main = MainScreen.settle(img, ::grab, sleep, now, on, log, beside = {
                tap(Explore.Target(Startup.GEAR_CLOSE[0], Startup.GEAR_CLOSE[1], Startup.GEAR_CLOSE_ANCHOR),
                    "beside the gear window")
            }) { last, waited ->
                log("chef's special: not the plain main screen after %.0f s, cannot open the event".format(waited))
                dump(last, "skewer_not_main")
            } ?: return false
            if (main !== img) main.release()
        } finally {
            img.release()
        }
        // The Events tile, on two looks running: a boss's name banner covers
        // it for the seconds it is up (Runner.eventsIcon), and where it is not
        // read nothing is tapped.
        val icon = waitFor { Runner.eventsIcon(it) != null }
        if (icon == null) {
            if (on()) {
                log("chef's special: the Events icon is not on the main screen")
                keepNow("skewer_no_events_icon")
            }
            return false
        }
        icon.release()
        val events = tapRead("Events icon", { Runner.eventsIcon(it) }) { Runner.eventsDialog(it) != null }
        if (events == null) {
            if (on()) {
                log("chef's special: the Events window did not open")
                keepNow("skewer_no_events")
            }
            return false
        }
        events.release()
        return openCard()
    }

    /**
     * The Events window, and in it the Chef's Special card and nothing else
     * (PLAN_SKEWER.md 3.2): the cards read on two looks running
     * ([Events.cards]); where none of them is Chef's Special nothing is
     * tapped, the pass says so, goes home beside the window, and the chain
     * retires the step for its run ([notInWindow], [end]).
     */
    private fun openCard(): Boolean {
        val window = waitFor { Events.cards(it) != null }
        if (window == null) {
            if (on()) {
                log("chef's special: the Events window did not settle")
                keepNow("skewer_no_events_cards")
            }
            return false
        }
        val cards = try { Events.cards(window)!! } finally { window.release() }
        if (cards.none { it.kind == Events.CHEFS_SPECIAL }) {
            log("Chef's Special is not in the Events window (cards: " +
                (if (cards.isEmpty()) "none" else cards.joinToString { it.kind }) + ")")
            notInWindow = true
            return false
        }
        val menu = tapRead("Chef's Special card", { img -> Events.card(img, Events.CHEFS_SPECIAL) }) {
            Skewer.menu(it) != null
        }
        if (menu == null) {
            if (on()) {
                log("chef's special: the menu did not open")
                keepNow("skewer_no_menu")
            }
            return false
        }
        menu.release()
        return true
    }

    // ------------------------------------------------------------------------
    // A round
    // ------------------------------------------------------------------------
    private enum class Start { IN, STOPPED, LOST }

    /**
     * From the menu, the stage popup or a round's result dialog to a round in
     * play: Play Game, Close, Start -- each tapped where its reader answered,
     * and the next screen seen on two looks. The stage is the game's own
     * choice (question 3): Close keeps it after "Failed...", and after
     * "Success!" the game has chosen the next.
     */
    private fun startRound(): Start {
        for (step in 0 until WALK_STEPS) {
            if (!on()) return Start.STOPPED
            val img = grab()
            val where = try {
                when {
                    Skewer.playing(img) -> return Start.IN
                    Skewer.over(img) != null -> "over"
                    Skewer.stage(img) != null -> "stage"
                    Skewer.menu(img) != null -> "menu"
                    Skewer.paused(img) != null -> {
                        log("chef's special: a round is paused, and not by me -- leaving it")
                        return Start.LOST
                    }
                    else -> null
                }
            } finally {
                img.release()
            }
            val next = when (where) {
                "over" -> tapRead("Close", { Skewer.over(it) }) { Skewer.stage(it) != null }
                "stage" -> tapRead("Start", { Skewer.stage(it) }) { Skewer.playing(it) }
                    ?.also { it.release(); return Start.IN }
                "menu" -> tapRead("Play Game", { Skewer.menu(it) }) { Skewer.stage(it) != null }
                else -> {
                    sleep(NAV_BEAT * patience)
                    continue
                }
            }
            if (next == null) {
                if (!on()) return Start.STOPPED
                log("chef's special: $where did not lead on")
                keepNow("skewer_no_start")
                return Start.LOST
            }
            next.release()
        }
        log("chef's special: no round could be started from here")
        keepNow("skewer_no_round")
        return Start.LOST
    }

    private enum class RoundEnd { OVER, TARGET, STOPPED, AWAY, UNREADABLE, OVERLONG }

    /** One round, guest after guest, until it ends or the pass has its combos. */
    private fun playRound(): RoundEnd {
        stats.rounds += 1
        streak = 0
        cells = null
        lastComplete = Double.NaN
        pending = null
        val start = now()
        val before = Triple(stats.guests, stats.right, stats.combos)
        log("chef's special: round ${stats.rounds}")
        var prev: List<String>? = null
        try {
            while (true) {
                if (!on()) return RoundEnd.STOPPED
                if (now() - start > ROUND_TIME_MAX) {
                    log("chef's special: ${(now() - start).toInt()} s and no end to the round in sight")
                    keepNow("skewer_overlong")
                    return RoundEnd.OVERLONG
                }
                val guest = when (val n = nextGuest(prev)) {
                    is Next.Guest -> n
                    Next.Over -> return RoundEnd.OVER
                    Next.Target -> return RoundEnd.TARGET
                    Next.Stopped -> return RoundEnd.STOPPED
                    Next.Away -> return RoundEnd.AWAY
                    Next.Unreadable -> return RoundEnd.UNREADABLE
                }
                prev = when (val s = serve(guest)) {
                    is Served.Out -> s.order
                    Served.Over -> return RoundEnd.OVER
                    Served.Target -> return RoundEnd.TARGET
                    Served.Stopped -> return RoundEnd.STOPPED
                    Served.Away -> return RoundEnd.AWAY
                }
            }
        } finally {
            // A guest whose round ended before two frames could say what it
            // was: UNCLEAR, never counted ([book]).
            if (pending != null) {
                log("    the round ended before the last guest was judged")
                decide(SkewerIcons.Served.UNCLEAR)
            }
            log("  the round: ${stats.guests - before.first} guests, ${stats.right - before.second} right, " +
                "${stats.combos - before.third} combos")
        }
    }

    private sealed class Next {
        /** [seen]: when the order was read; it is believed once [serve] has read it again. */
        class Guest(val order: List<String>, val lives: Int, val seen: Double) : Next()
        object Over : Next()
        object Target : Next()
        object Stopped : Next()
        object Away : Next()
        object Unreadable : Next()
    }

    /**
     * The next order, read on one frame with the lives beside it -- the
     * second frame of "never trust a single frame" is [serve]'s first, on
     * which nothing is tapped unless the same order and lives stand again
     * (skewer.py's `settled_order` was written after an order read off one
     * frame, half drawn, cost a life). Or the round's end ("Success!" or
     * "Failed..." on two looks), a pause menu this task did not raise, a
     * round that is gone, or no readable order in [ORDER_WAIT].
     *
     * Every frame here also judges the guest still [pending] ([settle]).
     * Its bubble is not the next order: [prev], the order last served,
     * counts only once a frame without it has been seen -- a right guest's
     * bubble goes about a second after Complete, a wrong one's stays while
     * the guest leaves angry (skewer_mistake 191910).
     */
    private fun nextGuest(prev: List<String>?): Next {
        var gap = prev == null
        var overs = 0
        var pauses = 0
        var absentSince = Double.NaN
        val start = now()
        while (true) {
            if (!on()) return Next.Stopped
            val look = onFrame { look(it, pending?.lives) }
            settle(look)
            if (stats.combos >= target()) return Next.Target
            when {
                look.over != null -> {
                    overs += 1
                    if (overs >= 2) return Next.Over
                }
                look.paused != null -> {
                    pauses += 1
                    if (pauses >= 2) {
                        log("chef's special: the round's pause menu is open, and I did not open it")
                        return Next.Away
                    }
                }
                look.playing -> {
                    overs = 0; pauses = 0; absentSince = Double.NaN
                    val o = look.order
                    val l = look.lives
                    if (!gap && o != prev) gap = true
                    if (gap && o != null && l != null) return Next.Guest(o, l, now())
                }
                else -> {
                    overs = 0; pauses = 0
                    if (absentSince.isNaN()) absentSince = now()
                    if (now() - absentSince >= ABSENT_WAIT) {
                        log("chef's special: the round is gone")
                        keepNow("skewer_round_gone")
                        return Next.Away
                    }
                }
            }
            if (now() - start >= ORDER_WAIT) {
                log("chef's special: no order read in ${ORDER_WAIT.toInt()} s")
                keepNow("skewer_no_order")
                return Next.Unreadable
            }
            sleep(ROUND_BEAT * patience)
        }
    }

    private sealed class Served {
        /** Complete went out on [order]; the guest is [pending] now. */
        class Out(val order: List<String>) : Served()
        object Over : Served()
        object Target : Served()
        object Stopped : Served()
        object Away : Served()
    }

    /**
     * One guest: its order read once more with the same lives, then the
     * ingredients tapped one after another on the grill, each on a fresh
     * frame on which the round is in play and the order is still the one
     * being built, at the cell the round's grill read put it in ([cells],
     * twelve different ingredients); then Complete, and the guest is
     * [pending] until the frames of the next one have judged it ([settle]).
     *
     * Complete waits until the guest before is judged: a life lost after it
     * could be either's. The lives a guest is judged against are those of
     * the frame Complete goes out on, the same on the frame before -- a
     * guest before that went out wrong has already taken its life.
     *
     * An order that changes under the hand, on two frames running, is taken
     * up where the plate so far is its beginning; where it is not, the plate
     * holds something the new order does not want, it cannot be taken off
     * (nothing reads it, notes/skewer.md), and it is served as it stands to
     * clear it -- a life, said in the log -- rather than built on.
     */
    private fun serve(g: Next.Guest): Served {
        var target = g.order
        var lives = g.lives
        var settled = false
        var settledAt = Double.NaN
        var built = 0
        var changedTo: List<String>? = null
        var strays = 0
        var overs = 0
        var taps = 0
        var firstTap = Double.NaN
        var livesBefore: Int? = null
        /** The plate holds what the order does not want: Complete clears it, nothing more goes on. */
        var clearing = false
        while (true) {
            if (!on()) return Served.Stopped
            val img = grab()
            var aim: Explore.Target? = null
            var what = ""
            var complete = false
            try {
                val look = look(img, pending?.lives)
                settle(look)
                if (stats.combos >= target()) return Served.Target
                val order = look.order
                when {
                    look.over != null -> {
                        overs += 1
                        if (overs >= 2) return Served.Over
                    }
                    !look.playing || order == null -> strays += 1
                    !settled -> {
                        overs = 0
                        if (order == target && look.lives == lives) {
                            // The second frame running with this order and these
                            // lives: the guest is believed, and the first
                            // ingredient goes out on this very frame.
                            settled = true
                            settledAt = now()
                            log("  guest ${stats.guests + (if (pending != null) 2 else 1)}: " +
                                "${target.joinToString(", ")} (lives $lives" +
                                (if (lastComplete.isNaN()) "" else
                                    "; read ${secs(g.seen - lastComplete)} and settled " +
                                    "${secs(settledAt - lastComplete)} after the last Complete") +
                                ")")
                        } else {
                            target = order
                            lives = look.lives ?: lives
                        }
                    }
                    order != target && order != changedTo -> changedTo = order
                    else -> {
                        overs = 0
                        if (order != target) {
                            // The same other order on two frames running.
                            if (built == 0 || order.take(built) == target.take(built)) {
                                log("    the order changed under the hand: ${target.joinToString(", ")} -> " +
                                    "${order.joinToString(", ")}" +
                                    (if (built > 0) " -- the plate so far is its start, going on" else ""))
                                target = order
                            } else {
                                log("    the order changed under the hand: ${target.joinToString(", ")} -> " +
                                    "${order.joinToString(", ")}, with $built on the plate that are not its " +
                                    "start -- serving the plate as it is to clear it")
                                dump(img, "skewer_order_changed")
                                target = order
                                clearing = true
                            }
                        }
                        changedTo = null
                    }
                }
                if (settled && look.playing && order == target && changedTo == null) {
                    if (clearing || built >= target.size) {
                        // Complete only once the guest before is judged, and
                        // on lives read alike on two frames running.
                        val l = look.lives
                        if (pending == null && l != null && l == livesBefore) {
                            aim = Skewer.COMPLETE
                            what = "Complete"
                            complete = true
                            lives = l
                        }
                    } else {
                        val names = cells ?: icons.grid(img).map { it.name }.takeIf {
                            // A round's grid is believed only as twelve
                            // different names (SkewerIcons.grid: a dialog
                            // growing over the grill read ten cells dark_meat).
                            it.toSet().size == SkewerIcons.NAMES.size
                        }?.also {
                            cells = it
                            log("    the grill: ${it.joinToString(", ")}")
                        }
                        val i = names?.indexOf(target[built]) ?: -1
                        if (i < 0) {
                            strays += 1
                        } else {
                            aim = Skewer.cell(i / Skewer.CELL_FX.size, i % Skewer.CELL_FX.size)
                            what = target[built]
                        }
                    }
                }
                livesBefore = look.lives
                if (aim == null && strays >= STRAY_LOOKS) {
                    log("    the round or the order did not read for $strays frames -- leaving this guest")
                    dump(img, "skewer_guest_lost")
                    return Served.Away
                }
            } finally {
                img.release()
            }
            if (aim == null) {
                sleep(ROUND_BEAT * patience)
                continue
            }
            strays = 0
            // The laboratory's pace as a floor between two taps; the frame
            // itself usually took longer.
            val wait = TAP_GAP - (now() - lastTap)
            if (wait > 0) sleep(wait)
            tap(aim, what)
            lastTap = now()
            taps += 1
            if (firstTap.isNaN()) firstTap = lastTap
            if (complete) {
                lastComplete = lastTap
                pending = Pending(lives, target, lastTap)
                // Where a guest's seconds go, for the pace a report can be read for.
                log("    $taps taps in ${secs(lastComplete - firstTap)} from ${secs(firstTap - settledAt)} " +
                    "after the order settled")
                return Served.Out(target)
            }
            built += 1
        }
    }

    /**
     * A guest Complete has gone out on, not judged yet: [lives] those it is
     * judged against, [order] the order served, [completed] when Complete
     * went out (again, after a second one).
     */
    private class Pending(val lives: Int, val order: List<String>, var completed: Double) {
        var gap = false
        var gapAt = Double.NaN
        var last: SkewerIcons.Served? = null
        var again = false
        var overs = 0
    }

    /** The guest served and not yet judged, at most one ([serve] waits for it before Complete). */
    private var pending: Pending? = null

    /**
     * After Complete, on every frame the round takes anyway -- the next
     * order read, its ingredients tapped (notes/skewer.md, "A guest is judged
     * on the next guest's frames"): [judge] until two frames running say
     * the same thing other than "not yet", at most [JUDGE_WAIT]. The bubble
     * going, or another order standing, is what [Judging.gap] records. A
     * Complete that changed nothing at all by [COMPLETE_AGAIN] is tapped once
     * more. The round ending on its dialog decides a lost life; a guest
     * served in its last second, with no life lost, is UNCLEAR and not
     * counted.
     */
    private fun settle(look: Look) {
        val p = pending ?: return
        val t = now()
        if (look.playing && !p.gap && look.order != p.order) {
            p.gap = true
            p.gapAt = t
        }
        val v = judge(look, Judging(p.lives, p.gap, t - p.completed))
        if (v != SkewerIcons.Served.UNCLEAR && v == p.last) return decide(v)
        p.last = v
        if (look.over != null) {
            p.overs += 1
            if (p.overs >= 2 && v == SkewerIcons.Served.UNCLEAR) return decide(v)
        }
        if (t - p.completed >= JUDGE_WAIT) return decide(SkewerIcons.Served.UNCLEAR)
        if (!p.again && !p.gap && look.playing && look.order == p.order && look.lives == p.lives &&
            t - p.completed >= COMPLETE_AGAIN) {
            if (!on()) return
            log("    Complete changed nothing -- once more")
            tap(Skewer.COMPLETE, "Complete")
            lastTap = now()
            p.completed = lastTap
            p.last = null
            p.again = true
        }
    }

    /** The [pending] guest's verdict, said with when it came, and booked. */
    private fun decide(v: SkewerIcons.Served) {
        val p = pending ?: return
        pending = null
        log("    judged ${secs(now() - p.completed)} after Complete, the bubble gone " +
            (if (p.gapAt.isNaN()) "never" else "at ${secs(p.gapAt - p.completed)}"))
        book(v)
    }

    /**
     * A guest's verdict into the pass's count: a right one after a right one
     * in the same round is a combo, counted at once for the day
     * ([comboCounted]); a wrong one and one nobody could judge start the
     * running combo again -- an UNCLEAR is never counted as right, because a
     * combo counted that did not happen would end the day's pass short of
     * the mission.
     */
    private fun book(v: SkewerIcons.Served) {
        stats.guests += 1
        when (v) {
            SkewerIcons.Served.RIGHT -> {
                stats.right += 1
                streak += 1
                if (streak >= 2) {
                    stats.combos += 1
                    stats.best = maxOf(stats.best, streak - 1)
                    comboCounted()
                    log("    right -- ${streak - 1} Combo; ${stats.combos} of ${target()} this pass")
                } else {
                    log("    right")
                }
            }
            SkewerIcons.Served.WRONG -> {
                stats.mistakes += 1
                streak = 0
                cells = null
                log("    wrong -- a life lost, the combo starts again")
            }
            SkewerIcons.Served.UNCLEAR -> {
                stats.unclear += 1
                streak = 0
                log("    not clear whether it was right -- not counted, the combo starts again")
            }
        }
    }

    // ------------------------------------------------------------------------
    // The way out
    // ------------------------------------------------------------------------
    private enum class Goal { STAGE, MENU, MAIN }

    private class Move(val what: String, val read: (Mat) -> Explore.Target?, val next: (Mat) -> Boolean)

    /**
     * From wherever Chef's Special stands to [goal], one screen at a time,
     * each step tapped where its reader answered and the next screen seen on
     * two looks: a round in play is paused and quit (the player's way out,
     * PLAN_SKEWER.md 7, question 9), a result dialog closed, the stage popup
     * closed by a tap beside it ([stageOutside]), the menu left by its X, an
     * Events window beside it. True once [goal] stands.
     */
    private fun walk(goal: Goal, gated: Boolean): Boolean {
        for (step in 0 until WALK_STEPS) {
            // Every way home asks the switch first since 2026-09-30 ([Stays]):
            // with it off the game stays where it is, said once a pass.
            if (stays.now()) return false
            if (gated && !on()) return false
            val img = try { grab() } catch (e: CaptureError) { return false }
            val move: Move? = try {
                when {
                    // One of the game's own windows is the director's ([Stays.over], B60).
                    goal == Goal.MAIN && stays.over(img) -> return false
                    goal == Goal.MAIN && Dungeon.autoButton(img) != null -> return true
                    Skewer.over(img) != null -> Move("Close", { Skewer.over(it) }) { Skewer.stage(it) != null }
                    Skewer.paused(img) != null -> Move("Quit", { Skewer.paused(it) }) { Skewer.stage(it) != null }
                    Skewer.playing(img) -> Move("pause", { if (Skewer.playing(it)) Skewer.PAUSE else null }) {
                        Skewer.paused(it) != null || Skewer.over(it) != null
                    }
                    Skewer.stage(img) != null -> if (goal == Goal.STAGE) return true
                        else Move("beside the stage popup", { if (Skewer.stage(it) != null) stageOutside(it) else null }) {
                            Skewer.menu(it) != null
                        }
                    Skewer.menu(img) != null -> if (goal != Goal.MAIN) return goal == Goal.MENU
                        else Move("X", { if (Skewer.menu(it) != null) Explore.Target(Summon.EXIT_BUTTON_FX, Summon.EXIT_BUTTON_FY) else null }) {
                            Dungeon.autoButton(it) != null
                        }
                    Events.outside(img) != null -> if (goal != Goal.MAIN) return false
                        // The Events window has no X: a tap beside it closes it
                        // (Events.outside), and nothing on a card is touched.
                        else Move("beside the Events window", { Events.outside(it) }) { Dungeon.autoButton(it) != null }
                    goal == Goal.MAIN && Dungeon.homeButton(img) != null -> return goHome()
                    else -> null
                }
            } finally {
                img.release()
            }
            if (move == null) {
                sleep(NAV_BEAT * patience)
                continue
            }
            val next = try {
                tapRead(move.what, move.read, gated = gated, check = move.next)
            } catch (e: CaptureError) {
                return false
            }
            if (next == null) {
                if (gated && !on()) return false
                if (stays.now()) return false
                log("chef's special: ${move.what} did not lead on")
                keepNow("skewer_way_out")
                return false
            }
            next.release()
        }
        return false
    }

    /** dungeon.go_home for the one way home a page of the game has: the globe until the auto button is back. */
    private fun goHome(rounds: Int = FarmSkill.HOME_ROUNDS): Boolean {
        var pressed = 0
        for (i in 0 until rounds) {
            if (stays.now()) return false
            val img = try { grab() } catch (e: CaptureError) { return false }
            try {
                if (stays.over(img)) return false
                if (Dungeon.autoButton(img) != null) {
                    if (pressed > 0) log("chef's special: back on the main screen")
                    return true
                }
                val button = Dungeon.homeButton(img)
                if (button != null) {
                    if (pressed >= FarmSkill.HOME_PRESSES_MAX) return false
                    if (pressed == 0) log("chef's special: pressing the home button")
                    tap(Explore.Target(button.fx, button.fy), "home button")
                    pressed += 1
                }
            } finally {
                img.release()
            }
            sleep(NAV_BEAT * patience)
        }
        return false
    }

    /**
     * The chain's second hand, and [run]'s own last third: from any screen of
     * Chef's Special or the Events window, home. Bounded, it taps only what
     * it has read, and it asks the switch first, as every way home does
     * since 2026-09-30 ([Stays], PLAN_RELEASE_1_3.md B4).
     */
    override fun leave(): Boolean {
        if (walk(Goal.MAIN, gated = false)) return true
        if (stays.now() || stays.met != null) return false
        log("chef's special: could not get back to the main screen -- the game is left where it stands")
        keepNow("skewer_no_way_home")
        return false
    }

    // ------------------------------------------------------------------------
    // A pass
    // ------------------------------------------------------------------------
    /**
     * Rounds until the pass has its combos: begins on the menu, the stage
     * popup or a round's result dialog, and ends on the stage popup with the
     * round left through its pause menu (question 9), or on the result dialog
     * of the round that brought the last combo.
     *
     * Four ways out besides the combos: the switch goes off, a round is lost
     * to somebody else's hand, an order does not read, or [ceiling] rounds
     * have been played short of them.
     */
    private fun session() {
        while (stats.combos < target()) {
            if (!on()) {
                stats.reason = "stopped"
                return
            }
            if (stats.rounds >= ceiling()) {
                stats.reason = "${ceiling()} rounds and still short"
                finished = true
                return
            }
            when (startRound()) {
                Start.IN -> {}
                Start.STOPPED -> { stats.reason = "stopped"; return }
                Start.LOST -> { stats.reason = "lost"; return }
            }
            when (playRound()) {
                RoundEnd.OVER -> {}
                RoundEnd.TARGET -> {
                    log("chef's special: ${stats.combos} combos -- leaving the round through its pause menu")
                    walk(Goal.STAGE, gated = false)
                }
                RoundEnd.STOPPED -> { stats.reason = "stopped"; return }
                RoundEnd.AWAY -> { stats.reason = "lost"; return }
                RoundEnd.UNREADABLE -> {
                    walk(Goal.STAGE, gated = false)
                    stats.reason = UNREADABLE
                    finished = true
                    return
                }
                RoundEnd.OVERLONG -> {
                    walk(Goal.STAGE, gated = false)
                    stats.reason = "lost"
                    return
                }
            }
        }
        stats.reason = "done"
        finished = true
    }

    private fun begin() {
        stats = Stats()
        streak = 0
        cells = null
        lastTap = -9.0
        wanted = null
        finished = false
        notInWindow = false
        stays.reset()
    }

    private fun end(): Outcome {
        lastSession = stats
        log("Chef's Special: ${stats.rounds} round" + (if (stats.rounds == 1) "" else "s") +
            ", ${stats.guests} guests (${stats.right} right, ${stats.mistakes} wrong, ${stats.unclear} unclear)" +
            ", ${stats.combos} of ${target()} combos" + (if (stats.best > 0) ", best ${stats.best} Combo" else "") +
            " -- ${stats.reason}")
        carried = stats.reason == "stopped"
        return when (stats.reason) {
            "stopped" -> Outcome.STOPPED
            // Not a park: the game has another minigame in the Events window
            // today, and no round of this chain can change that. The chain
            // retires the step for its run (DirectorLoop.full, Result.RETIRED).
            NOT_IN_WINDOW -> Outcome.retired(NOT_IN_WINDOW_WHY)
            "could not open" -> Outcome.parked("I could not open Chef's Special from here.")
            "lost" -> Outcome.parked("A round of Chef's Special was ended or paused from outside, or a " +
                "screen came up I did not expect.")
            UNREADABLE -> Outcome.parked("The order in the speech bubble did not read, so the round was " +
                "left.")
            "done" -> Outcome.DONE
            else -> Outcome.parked("Chef's Special played ${ceiling()} rounds and counted only ${stats.combos} " +
                "of ${target()} combos. Something is not read right; each guest is in the log.")
        }
    }

    /**
     * The second half: [img] is the menu, the stage popup or a round's
     * result dialog the director classified. Rounds until the pass has its
     * combos, and the menu left open (PLAN_SKEWER.md 3.1).
     */
    override fun work(img: Mat): Outcome {
        rect = Dungeon.gameRect(img)
        room = Dungeon.headroom(img)
        begin()
        try {
            session()
        } finally {
            // A stopped pass leaves the game where it stands (the hand
            // withholds every tap then), and a lost one is somebody else's.
            if (stats.rounds > 0 && stats.reason != "stopped" && stats.reason != "lost") {
                walk(Goal.MENU, gated = false)
            }
        }
        return end()
    }

    // ------------------------------------------------------------------------
    // After a pause (PLAN_RELEASE_1_3.md B4, the player's rule of 2026-09-30)
    // ------------------------------------------------------------------------
    /** The last pass was stopped by the main switch and has not been gone on with or begun afresh since. */
    internal var carried = false
        private set

    /** Every way home asks the switch first ([Stays]). */
    private val stays = Stays(on) { log(it) }

    /**
     * Where the pass the switch stopped goes on from: the menu, the stage
     * popup, a round's result dialog, and a round still in play -- the switch
     * stops the pass between two looks and taps nothing more, the game goes on
     * with the round by itself, and the guests wait for their orders
     * (notes/skewer.md: no guest left on time in any round). Not the pause
     * menu: this task never leaves one standing on a pause (it raises one only
     * to leave a round, [walk]), so one in front is the player's.
     *
     * What the pass is owed is the day's count on the page's number
     * ([Settings.left], written as each combo is counted, [comboCounted]), so
     * a pass that goes on plays for what is left and counts nothing twice.
     * The running combo is not carried: a round the switch stopped may have
     * been played on by hand, and a combo counted that the game did not show
     * would end the day short of the mission ([book]) -- the first right
     * guest after a pause starts it again.
     */
    override fun resumesOn(screen: String, img: Mat): Boolean =
        carried && screen in RESUMES

    /**
     * The pass the switch stopped, gone on with: from the screen it stands on
     * ([startRound] takes a round in play as it is, a result dialog by its
     * Close, the popup by Start, the menu by Play Game), rounds until the day
     * has its combos. [whole] goes home after it, as [run] does; from the main
     * screen (a chain step whose claim ended) the way in is [run]'s.
     */
    override fun resume(img: Mat, whole: Boolean): Outcome {
        if (!carried) return if (whole) run() else work(img)
        rect = Dungeon.gameRect(img)
        room = Dungeon.headroom(img)
        begin()
        log("going on with the Chef's Special pass the main switch stopped")
        try {
            if (whole && Dungeon.autoButton(img) != null) {
                if (!goToMenu()) {
                    stats.reason = if (!on()) "stopped" else if (notInWindow) NOT_IN_WINDOW else "could not open"
                } else {
                    session()
                }
            } else {
                session()
            }
        } finally {
            if (whole) {
                if (stats.reason != "stopped") leave()
            } else if (stats.rounds > 0 && stats.reason != "stopped" && stats.reason != "lost") {
                walk(Goal.MENU, gated = false)
            }
        }
        return end()
    }

    /** The whole: from the plain main screen, go there, play, come home. */
    override fun run(): Outcome {
        begin()
        try {
            if (!goToMenu()) {
                stats.reason = if (notInWindow) NOT_IN_WINDOW else if (on()) "could not open" else "stopped"
            } else {
                session()
            }
        } finally {
            if (stats.reason != "stopped") leave()
        }
        return end()
    }
}
