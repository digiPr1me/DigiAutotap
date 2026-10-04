package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * The Lost Sector Tower (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.2, DL4): the
 * tower's Subjugate pressed run after run for the minutes the Dungeons page
 * gives it (`lost_sector_minutes`, [Stored.lostSector]) each time the task
 * gets the tower -- its own step of the chain (the player's answer to
 * question 3), or the Crests page handed over in the semi-automatic mode.
 * A run costs nothing (question 4); the minutes are a floor, not a cut
 * (question 10), and have no ceiling a player meets (question 11).
 *
 * The way there is DL1a's measurement on LDPlayer instance 0, 2026-09-28/29
 * (4.1 points 1 and 4, LostSector.kt): the tile right of the Digivice in
 * the icon row above the field ([LostSector.CRESTS_ICON], a constant with
 * its table over 90 main screens) opens the page **Crests**; its dark pill
 * "Enter ->" on the card "Lost Sector Tower" raises the tower's panel over
 * it -- the floor, the boss, "Subjugation Rewards" and one blue button,
 * **Subjugate**. No price, no counter, no Clear. The back key closes the
 * panel to the page, and the page's white X goes home.
 *
 * What a Subjugate does was seen once, on the highest floor (120, the
 * player's): no battle, the toast "You have reached the highest floor and
 * cannot proceed any further." over the panel for about a second, and the
 * panel stays (G4). Until the player answers question 18 that toast is the
 * task's "done" (the conductor's proposal): read once, it ends the pass and
 * nothing more is tapped -- and, the proposal to question 22 (G16), the
 * tower's day with it: the reset is written ([Stored.retireLostSector],
 * `lost_sector_done_until`, 08:00 Vienna by [QuestSkill.nextReset]), the
 * pass hands back RETIRED, and until the reset [hasBudget] says no, so that
 * a chain that repeats walks into the tower once and not every round. New
 * minutes on the page lift it ([Stored.lostSectorDoneUntil]). **A run below
 * the highest floor has never been
 * seen**; it is waited out the way the daily dungeon's is
 * (DungeonSkill.playDaily), and everything about it here is the flow
 * test's, not the game's.
 *
 * Every action has its witness and its brake:
 *
 *   the tile      tapped only on a fresh frame read as the plain main
 *                 screen (the start wants two of them); the Crests page on
 *                 two frames ([LostSector.page]) is the arrival;
 *                 [NAV_TAPS_MAX] taps, then a park with the frame kept
 *   Enter         the pill [LostSector.page] reads on a fresh frame, at its
 *                 anchor ([LostSector.PAGE]); the panel is [LostSector.subjugate]
 *                 on two frames at one place; a panel it does not name --
 *                 a dialog over the dimmed pill, a price or a second button
 *                 on it -- is a park with the frame, and nothing is tapped
 *                 there (3.2 point 7)
 *   Subjugate     only the button that reader named; then looked at frame
 *                 after frame, as fast as the capture goes, because the
 *                 toast stands about a second: the toast ([LostSector.maxFloor])
 *                 ends the pass, the panel gone on two frames is a run, and
 *                 neither within [SUBJUGATE_WAIT] is "no effect" -- twice,
 *                 and the pass ends with the frame kept
 *   the run       no tap in a battle; the Reward sheet read and tapped away;
 *                 a narrow Close closed; any other dialog never tapped; a
 *                 prompt answered Cancel; the main screen never tapped; the
 *                 Crests page left alone for [UNKNOWN_HOLD], then Enter once
 *                 a run; a screen nobody names left alone for the hold, then
 *                 one tap high up. Back in under [MIN_BATTLE] was no run, and
 *                 twice so in a row ends the pass
 *   the clock     [now], asked before a Subjugate and never inside a run,
 *                 started at the first one
 *   the switch    at the same place and nowhere else
 *   the back key  only on the panel read on two frames running at one place
 *
 * `work` begins and ends on the Crests page; `run` walks from the main
 * screen and goes home in its finally, as every walking skill does; `leave`
 * is that way home alone, for the chain's second hand.
 *
 * Counted: the runs and those the Reward sheet came in, under the keys the
 * Dungeons card shows them by ([CARD_RUNS], [CARD_WON]); a lost run is the
 * log's. Cleared at the top of every pass.
 */
class LostSectorSkill(
    private val cap: Capture,
    /** The page's minutes, asked afresh at every question, and once at the start of a pass for the pass. */
    private val settings: () -> Settings,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** The main switch, asked between two runs and never inside one. */
    private val on: () -> Boolean = { MainSwitch.on },
    /** Keep a frame worth looking at afterwards. The app writes files; core cannot. */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    /** Every wait between two actions hangs off this one factor, DungeonSkill's. */
    private val patience: Double = 1.5,
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    /** Seconds, monotonic. Injected so that the flow test can move it. */
    private val now: () -> Double = { System.nanoTime() / 1e9 },
    /** The tower's day over until the given epoch second ([Stored.retireLostSector]). The app writes the file; core cannot. */
    private val retire: (Double) -> Unit = {},
    /** Epoch seconds, for the day's reset: the monotonic [now] knows no day. */
    private val wall: () -> Double = { System.currentTimeMillis() / 1000.0 },
) : Skill {

    /**
     * `lost_sector_minutes` ([Stored.lostSector]): 0 is off and the default.
     * [doneUntil]: the reset until which the tower is at its highest floor,
     * or null where no such day holds ([Stored.lostSectorDoneUntil]).
     */
    data class Settings(val minutes: Int = 0, val doneUntil: Double? = null)

    override val key = "lost_sector"
    override val name = "Lost Sector Tower"

    override fun worksOn(screen: String): Boolean = screen == Director.LOST_SECTOR

    /** Minutes on the page ([Chain.lostSectorHasWork]) and no day over ([Settings.doneUntil]), asked afresh every round. */
    override fun hasBudget(): Boolean = settings().let { Chain.lostSectorHasWork(it.minutes) && it.doneUntil == null }

    /**
     * The day over, said as the day over: "the tower is at its highest floor
     * until <reset>" rather than the chain's "on the settings as they stand",
     * while the minutes stand on the page (G20). No minutes is the settings'
     * reason, and says so as before.
     */
    override fun noBudgetWhy(): String? = settings().let {
        val until = it.doneUntil
        if (Chain.lostSectorHasWork(it.minutes) && until != null)
            "the tower is at its highest floor until ${QuestSkill.whenText(until)}" else null
    }

    /**
     * On the Crests page: false with no minutes on the page; true until a
     * pass has ended in its own way, and null from then on, so that the
     * director's one-work-per-visit rule holds ("Lost Sector Tower has
     * worked this lost_sector; leaving it to you"). A pass the switch cut
     * short leaves the page as work, as RunnerSkill.seesWork does: the
     * switch back on with the page still open is the same visit, and the
     * player should not have to leave it and come back. And null while
     * the tower's day is over ([Settings.doneUntil]): the director's own
     * rules then say "nothing to do, on the settings as they stand".
     */
    override fun seesWork(screen: String, img: Mat): Boolean? {
        if (screen != Director.LOST_SECTOR) return null
        val s = settings()
        if (!Chain.lostSectorHasWork(s.minutes)) return false
        if (s.doneUntil != null) return null
        return if (finished) null else true
    }

    // ------------------------------------------------------------------------
    // What one pass knows, all of it set again at the top of every pass
    // ------------------------------------------------------------------------
    /** The last pass ended in its own way ([Result.DONE] or [Result.RETIRED]); see [seesWork]. */
    private var finished = false
    private var runs = 0
    private var won = 0
    private var lost = 0
    private var stopped = false
    /** Why the pass stopped short, or null; the outcome is a park with it. */
    private var parkedBecause: String? = null
    /**
     * The game's download dialog stood in this pass ([cancel]): the pass
     * taps nothing more and hands the screen back, and the director answers
     * the dialog (DirectorLoop.download).
     */
    private var downloadAsked = false
    /**
     * The game went back to its title in this pass (Startup.title;
     * PLAN_RELEASE_1_3.md B30): a tap there is "Touch To Start", so the pass
     * taps nothing more and hands the screen back, as for [downloadAsked]
     * (DungeonSkill.onTitle).
     */
    private var onTitle = false
    /** A screen this pass must not touch stands in front: the download dialog or the title. */
    private val handsOff get() = downloadAsked || onTitle
    private var saved = 0
    /** The Reward sheet came since the last Subjugate: a won run. */
    private var sheet = false
    /** The highest floor's toast was read in this pass: the reset it retires the tower until. */
    private var atTop: Double? = null
    /** The bottom rectangle and the headroom of the last frame read: what a tap is aimed with. */
    private var rect: Dungeon.GameRect? = null
    private var room = 0

    /**
     * This pass's count, for the TODAY card ([Counted]): the Dungeons card's
     * lines, because the tower has no row of its own (SkillStats.SHOWN).
     */
    var lastCounts: Map<String, Int> = emptyMap()
        private set

    private val pauseShort = 0.5 * patience
    private val pauseLong = 1.0 * patience

    private fun begin() {
        goOn()
        carried = false
        minutesPlayed = 0.0
    }

    /** What every stretch of a pass starts from, fresh or going on after a pause: the reasons and the count. */
    private fun goOn() {
        runs = 0
        won = 0
        lost = 0
        stopped = false
        parkedBecause = null
        downloadAsked = false
        onTitle = false
        saved = 0
        sheet = false
        atTop = null
        lastCounts = emptyMap()
        stays.reset()
    }

    // ------------------------------------------------------------------------
    // After a pause (PLAN_RELEASE_1_3.md B4, the player's rule of 2026-09-30)
    // ------------------------------------------------------------------------
    /** The last pass was stopped by the main switch and has not been gone on with or begun afresh since. */
    internal var carried = false
        private set
    /** Seconds of the page's minutes this pass has played, the pauses not among them. */
    private var minutesPlayed = 0.0
    /** Every way home asks the switch first ([Stays]). */
    private val stays = Stays(on) { log(it) }

    /**
     * Where a pass the switch stopped goes on: the Crests page, the tower's
     * panel (the director's `dialog`, [LostSector.subjugate] on the frame),
     * and a run's Reward sheet or battle (`unknown` to the director,
     * `recognise` on the frame).
     */
    override fun resumesOn(screen: String, img: Mat): Boolean {
        if (!carried) return false
        return when (screen) {
            Director.LOST_SECTOR -> true
            Director.DIALOG -> LostSector.subjugate(img) != null
            Director.UNKNOWN -> Dungeon.recognise(img).state.let { it == Dungeon.REWARD || it == Dungeon.BATTLE }
            else -> false
        }
    }

    /**
     * The pass the switch stopped, gone on with for the minutes it has left
     * ([minutesPlayed]): a run's sheet or battle waited out as a run is
     * ([runBack]), then Subjugate on the panel standing, or Enter from the
     * page. [whole] goes home after it, as [run]; from the main screen (a
     * chain step whose claim ended) the way in is [run]'s.
     */
    override fun resume(img: Mat, whole: Boolean): Outcome {
        if (!carried) return if (whole) run() else work(img)
        goOn()
        rect = Dungeon.gameRect(img)
        room = Dungeon.headroom(img)
        log("lost sector tower: going on with the pass the main switch stopped")
        try {
            if (whole && Dungeon.autoButton(img) != null) return end(walk())
            dayOver()?.let { return end(it) }
            var panel = look { LostSector.subjugate(it) }
            if (panel == null && look { LostSector.page(it) } == null) {
                // A run's end in front: waited out as a run's is.
                panel = runBack()
                if (panel == null) return end(done())
            }
            return end(pass(panel))
        } finally {
            if (whole) goHome()
        }
    }

    private fun counted() {
        lastCounts = mapOf(CARD_RUNS to runs, CARD_WON to won)
    }

    // ------------------------------------------------------------------------
    // The seam
    // ------------------------------------------------------------------------
    /** The Crests page the director read: Enter, the runs, and the panel closed back to the page. */
    override fun work(img: Mat): Outcome {
        begin()
        rect = Dungeon.gameRect(img)
        room = Dungeon.headroom(img)
        log("lost sector tower: the Crests page is open -- working it")
        return end(dayOver() ?: pass())
    }

    /** The whole: the tile from the plain main screen, the page, [pass], and home in a finally. */
    override fun run(): Outcome {
        begin()
        try {
            return end(walk())
        } finally {
            // On every way out: a pass that never found the page, one cut
            // short by the switch, one that threw. Bounded, and it swallows
            // its own errors, so the one worth reading is the pass's.
            goHome()
        }
    }

    /** `run`'s last third alone, for the chain's second hand (Skill.leave). */
    override fun leave(): Boolean = goHome()

    private fun end(out: Outcome): Outcome {
        finished = out.result == Result.DONE || out.result == Result.RETIRED
        carried = out.result == Result.STOPPED
        counted()
        log("lost sector tower: %d run%s, %d won, %d lost -- %s".format(runs, plural(runs), won, lost, out))
        return out
    }

    private fun done(): Outcome = when {
        stopped -> Outcome.STOPPED
        parkedBecause != null -> Outcome.parked(parkedBecause!!)
        atTop != null -> Outcome.retired(atTopWhy(atTop!!))
        else -> Outcome.DONE
    }

    /** What the pass hands back where the tower's day is over: RETIRED, and when it ends. */
    private fun atTopWhy(until: Double) =
        "the tower is at its highest floor -- nothing to subjugate until ${QuestSkill.whenText(until)}"

    /**
     * The tower's day already over by the file ([Settings.doneUntil]):
     * RETIRED, and nothing is tapped. The director does not start such a
     * pass ([hasBudget]); this is the pass saying so itself if it is started
     * anyway.
     */
    private fun dayOver(): Outcome? {
        val until = settings().doneUntil ?: return null
        log("lost sector tower: " + atTopWhy(until))
        return Outcome.retired(atTopWhy(until))
    }

    // ------------------------------------------------------------------------
    // The way in
    // ------------------------------------------------------------------------
    /** From the main screen, or from the Crests page already open, through [pass]. */
    private fun walk(): Outcome {
        dayOver()?.let { return it }
        // Two frames running, not one: the start is where the chain handed
        // over a main screen, and a window opening over it is no main screen.
        val where = twice(START_WAIT) { img ->
            when {
                LostSector.page(img) != null -> PAGE
                Dungeon.autoButton(img) != null -> MAIN
                else -> null
            }
        }
        when (where) {
            PAGE -> log("lost sector tower: the Crests page is already open")
            MAIN -> if (!openPage()) {
                if (stopped) return done()
                log("lost sector tower: the Crests page did not open")
                keepFrame("lost_sector_no_page")
                parkedBecause = "The Crests page did not open from the main screen."
                return done()
            }
            else -> {
                keepFrame("lost_sector_not_main")
                parkedBecause = "Lost Sector Tower starts from the main screen."
                return done()
            }
        }
        return pass()
    }

    /**
     * The Crests tile, from the plain main screen: a constant
     * ([LostSector.CRESTS_ICON]; notes/runner.md, "A constant is a place on
     * one display", is why it carries its table), so what makes a tap safe
     * is the frame it goes out on -- a fresh one read as the plain main
     * screen, never a window the tap before may have opened. True once the
     * page has stood on two frames.
     */
    private fun openPage(): Boolean {
        for (i in 0 until NAV_TAPS_MAX) {
            if (!stillOn()) return false
            val main = look { Dungeon.autoButton(it) != null }
            if (!main) {
                // Something came: the page after the last wait ran out, or
                // a window nobody asked for. Only the page is an answer.
                return waitFor(PAGE_WAIT) { LostSector.page(it) != null }
            }
            log("lost sector tower: the Crests tile")
            val icon = LostSector.CRESTS_ICON
            tap(icon.fx, icon.fy, icon.anchor)
            if (waitFor(PAGE_WAIT) { LostSector.page(it) != null }) return true
        }
        return false
    }

    /**
     * The Crests page -> Enter -> the panel on two frames at one place: its
     * Subjugate button, or null with [parkedBecause] said (or [stopped]).
     * Enter is tapped only where [LostSector.page] reads it on a fresh
     * frame, which also keeps a second tap out of the panel the first one
     * raised: the pill is dimmed under it.
     */
    private fun openPanel(): Dungeon.Button? {
        for (i in 0 until NAV_TAPS_MAX) {
            if (!stillOn()) return null
            val pill = look { LostSector.page(it) } ?: return waitPanel(PANEL_WAIT) ?: cannotOpen()
            log("  Enter")
            tap(pill.fx, pill.fy, LostSector.PAGE)
            waitPanel(PANEL_WAIT)?.let { return it }
        }
        return cannotOpen()
    }

    /**
     * The panel did not stand: said, the frame kept, and a park -- which
     * names a panel of the tower's that [LostSector.subjugate] does not know
     * (a price, a counter, a second button: 3.2 point 7) apart from nothing
     * having opened.
     */
    private fun cannotOpen(): Dungeon.Button? {
        val strange = look { strangePanel(it) }
        parkedBecause = if (strange) STRANGE_PANEL else "The Lost Sector Tower's panel did not open."
        log("  " + parkedBecause)
        keepFrame(if (strange) "lost_sector_unknown_panel" else "lost_sector_no_panel")
        return null
    }

    /**
     * A dialog over the Crests page whose panel is not the one DL1a measured:
     * the page's pill dimmed under it ([LostSector.dimmedEnter]), a dialog by
     * `recognise`, and no Subjugate by [LostSector.subjugate] -- which asks
     * for exactly one centred button of its width and nothing beside it.
     */
    private fun strangePanel(img: Mat): Boolean =
        LostSector.subjugate(img) == null && LostSector.dimmedEnter(img) != null &&
            Dungeon.recognise(img).state in DIALOGS

    // ------------------------------------------------------------------------
    // The pass
    // ------------------------------------------------------------------------
    /** From the Crests page: the panel, the runs for the minutes, and back to the page. */
    private fun pass(standing: Dungeon.Button? = null): Outcome {
        val minutes = settings().minutes
        log("lost sector tower: %d minute%s".format(minutes, plural(minutes)) +
            if (minutesPlayed > 0) ", %.0f s of them played before the pause".format(minutesPlayed) else "")
        // The panel may stand already, where a pass the switch stopped goes
        // on ([resume]); then nothing is tapped to open it.
        val first = standing ?: openPanel() ?: return done()
        subjugateFor(first, minutes)
        // The panel closed the way DL1a closed it, and the page seen again --
        // not when the switch ended the pass (every gesture is withheld then)
        // and not on a park, whose frame is the player's to look at.
        if (!stopped && parkedBecause == null) {
            if (closePanel()) log("  back on the Crests page")
            else {
                log("  the Crests page did not come back after the panel")
                keepFrame("lost_sector_not_back")
            }
        }
        return done()
    }

    /**
     * Subjugate, run after run, until the minutes are up -- or the toast
     * says the tower has no floor above this one, or a brake ends it.
     * [first] is the button of the panel standing now.
     */
    private fun subjugateFor(first: Dungeon.Button, minutes: Int) {
        // The minutes this stretch has run, from its first Subjugate, on top
        // of what the pass played before a pause ([minutesPlayed]): a pause
        // is not the player's minutes, and a pass that goes on plays what is
        // left of them (PLAN_RELEASE_1_3.md B4).
        var start: Double? = null
        fun playedBy(t: Double) = minutesPlayed + (start?.let { t - it } ?: 0.0)
        try {
            subjugateFrom(first, minutes, { start }, { start = it }, ::playedBy)
        } finally {
            minutesPlayed = playedBy(now())
        }
    }

    private fun subjugateFrom(first: Dungeon.Button, minutes: Int, begun: () -> Double?,
                              begin: (Double) -> Unit, playedBy: (Double) -> Double) {
        var button = first
        var presses = 0
        var noEffect = 0
        var noBattle = 0
        while (true) {
            // Between two runs, and only here: the switch, then the clock.
            if (!stillOn()) return
            val t0 = now()
            if ((begun() != null || minutesPlayed > 0) && playedBy(t0) >= minutes * 60.0) {
                log("  the minutes are up after %d run%s (%.0f s)".format(runs, plural(runs), playedBy(t0)))
                return
            }
            if (begun() == null) begin(t0)
            presses += 1
            log("  Subjugate %d".format(presses))
            sheet = false
            tap(button.fx, button.fy, LostSector.PAGE)
            when (watch()) {
                Watch.MAX -> {
                    // G4: no battle and nothing spent; the panel stays and the
                    // toast goes by itself. Pressed again it says the same, so
                    // it is not pressed again (question 18, the proposal), and
                    // not walked into again before the game's reset either
                    // (question 22, the proposal): the pass retires the tower.
                    val until = QuestSkill.nextReset(wall())
                    retire(until)
                    atTop = until
                    log("  the tower is at its highest floor -- nothing to subjugate until ${QuestSkill.whenText(until)}")
                    return
                }
                Watch.NONE -> {
                    log("  Subjugate had no effect")
                    noEffect += 1
                    if (noEffect >= 2) {
                        log("  Subjugate had no effect twice, leaving the tower")
                        keepFrame("lost_sector_no_effect")
                        return
                    }
                    button = waitPanel(PANEL_WAIT) ?: run {
                        if (look { strangePanel(it) }) {
                            parkedBecause = STRANGE_PANEL
                            log("  " + STRANGE_PANEL)
                            keepFrame("lost_sector_unknown_panel")
                        }
                        return
                    }
                    continue
                }
                Watch.RAN -> {}
            }
            noEffect = 0
            val back = runBack()
            // The title or the download dialog in the run: its end was not
            // seen, and it is counted as nothing (B30).
            if (handsOff) return
            val duration = now() - t0
            if (duration >= MIN_BATTLE) {
                // A run, whatever it ended in: the Subjugate took the panel
                // away for longer than any message does. Won where the sheet
                // came; lost only where the panel came back without it -- a run
                // that ended anywhere else is said so and called neither.
                noBattle = 0
                runs += 1
                log("  battle finished after %.0f s".format(duration))
                if (sheet) {
                    won += 1
                    log("  run won, the Reward sheet came")
                } else if (back != null) {
                    lost += 1
                    log("  run lost, back without a Reward sheet")
                } else {
                    log("  the run did not come back to the tower's panel")
                }
            } else {
                // The panel only briefly gone -- a message, not a run.
                log("  only %.0f s, that was no battle".format(duration))
                noBattle += 1
                if (noBattle >= 2) {
                    log("  no battle twice in a row, leaving the tower")
                    keepFrame("lost_sector_no_battle")
                    return
                }
            }
            button = back ?: return
        }
    }

    private enum class Watch { MAX, RAN, NONE }

    /**
     * What the Subjugate just tapped did, looked at frame after frame with no
     * pause of this skill's own: the capture keeps its pace (a frame every
     * 0.35 s at the most, notes/director.md, "takeScreenshot has a floor"),
     * and the toast stood on one frame of DL1a's 45 -- about a second. The
     * toast read once is enough to end the pass: ending costs nothing, and
     * the reader asks the panel under it as well. A look that misses it
     * finds the panel standing, which is "no effect", and the second
     * Subjugate asks again; two misses end the pass the same way, with the
     * frame kept.
     */
    private fun watch(): Watch {
        val end = now() + SUBJUGATE_WAIT
        var gone = 0
        while (now() < end) {
            val img = grab()
            try {
                if (LostSector.maxFloor(img) != null) return Watch.MAX
                if (LostSector.subjugate(img) != null) {
                    gone = 0
                } else {
                    gone += 1
                    if (gone >= 2) return Watch.RAN
                }
            } finally {
                img.release()
            }
        }
        return Watch.NONE
    }

    /**
     * After a Subjugate that took the panel away: the panel standing again
     * on two frames at one place, and its button; null, with the frame kept,
     * where it does not within [BATTLE_WAIT] (and [BACK_WAIT] after Enter
     * was tapped again). What stands between is dealt with by what it is,
     * as the daily dungeon's run is (DungeonSkill.dailyBack):
     *
     *   the Reward sheet    read ([sheet]) and tapped away at once
     *   a narrow Close      a window after a run, closed
     *   another dialog      never tapped; waited on (a strange one is a park
     *                       at the end of the wait)
     *   a prompt            Cancel: nothing here raised one
     *   a battle            waited on, never tapped
     *   the Crests page     left alone for [UNKNOWN_HOLD], then Enter once a run
     *   the main screen     never tapped
     *   anything else       left alone for the hold, then one tap high up,
     *                       and the hold again after every tap
     */
    private fun runBack(): Dungeon.Button? {
        var reopens = 1
        var end = now() + BATTLE_WAIT
        var last: Dungeon.Button? = null
        var held: String? = null
        var since = 0.0
        var taps = 0
        val said = HashSet<String>()
        while (now() < end) {
            val img = grab()
            try {
                val panel = LostSector.subjugate(img)
                if (panel != null) {
                    held = null
                    val before = last
                    if (before != null && samePlace(before, panel)) return panel
                    last = panel
                    sleep(pauseShort)
                    continue
                }
                last = null
                val info = Dungeon.recognise(img)
                if (info.state == Dungeon.REWARD) {
                    held = null
                    val first = !sheet
                    sheet = true
                    // Read, and with the main switch off left standing: the
                    // run is won, and the pass stops on its sheet, where the
                    // pass that goes on after the pause takes it up.
                    if (!on()) {
                        stillOn()
                        return null
                    }
                    if (first) log("  the Reward sheet is up, closing it")
                    tap(0.5, NEUTRAL_TAP_Y, Dungeon.Anchor.BOTTOM)
                    sleep(TICK)
                    continue
                }
                // With the switch off nothing below is tapped: the run's end
                // is waited for as a battle is, the panel coming back by
                // itself after a lost one.
                if (!on()) {
                    held = null
                    sleep(TICK)
                    continue
                }
                if (info.state == Dungeon.EXIT) {
                    held = null
                    cancel(img, info)
                    if (downloadAsked) return null
                    continue
                }
                val close = info.attempt
                if (info.state in DIALOGS && close != null && close.fw <= CLOSE_W_MAX) {
                    held = null
                    log("  a window after the run, closing it")
                    tap(close.fx, close.fy, info.anchor)
                    sleep(pauseLong)
                    continue
                }
                if (info.state in DIALOGS || info.state == Dungeon.BATTLE) {
                    held = null
                    sleep(TICK)
                    continue
                }
                // The game's title: the run's end will not come, and a tap
                // there is "Touch To Start" -- the pass ends, nothing tapped
                // (PLAN_RELEASE_1_3.md B30, DungeonSkill.metTitle).
                if (metTitle(img)) return null
                // A run's own black or VS: waited on as a battle is, never
                // tapped, and the screen after it gets the hold afresh
                // (PLAN_RELEASE_1_3.md B6; DungeonSkill.passing, G12's tap).
                val kind = Dungeon.runTransition(img)
                if (kind != null) {
                    if (said.add(kind)) log("  $kind -- a run's own transition, waited on and not tapped")
                    held = null
                    sleep(TICK)
                    continue
                }
                val pill = LostSector.page(img)
                val state = when {
                    pill != null -> PAGE
                    Dungeon.autoButton(img) != null -> MAIN
                    else -> info.state
                }
                // The page and a screen nobody names wait out the hold first,
                // each on its own clock: what stands between two known
                // screens is shorter (UNKNOWN_HOLD).
                if (held != state) {
                    held = state
                    since = now()
                }
                if (now() - since < UNKNOWN_HOLD) {
                    sleep(TICK)
                    continue
                }
                when {
                    state == PAGE && pill != null -> {
                        if (reopens <= 0) {
                            log("  on the Crests page and not on the tower's panel, leaving it")
                            return null
                        }
                        reopens -= 1
                        log("  back on the Crests page, entering the tower once more")
                        tap(pill.fx, pill.fy, LostSector.PAGE)
                        held = null
                        end = now() + BACK_WAIT
                        sleep(pauseLong)
                    }
                    // The main screen, or the dungeon list, with nothing of
                    // this skill's over it: a tap high up there is on the stage.
                    state == MAIN || state == Dungeon.LIST -> sleep(TICK)
                    else -> {
                        if (taps == 0) log("  a screen nothing here reads has stood %.0f s, tapping high up"
                                               .format(now() - since))
                        tap(0.5, NEUTRAL_TAP_Y, Dungeon.Anchor.BOTTOM)
                        taps += 1
                        held = null
                        sleep(TICK)
                    }
                }
            } finally {
                img.release()
            }
        }
        if (!stillOn()) return null
        if (look { strangePanel(it) }) {
            parkedBecause = STRANGE_PANEL
            log("  " + STRANGE_PANEL)
            keepFrame("lost_sector_unknown_panel")
        } else {
            log("  the tower's panel did not stand within %.0f s, leaving it".format(BATTLE_WAIT))
            keepFrame("lost_sector_no_panel")
        }
        return null
    }

    /** The panel, standing: [LostSector.subjugate] on two frames running at one place, or null. */
    private fun waitPanel(seconds: Double): Dungeon.Button? {
        val end = now() + seconds
        var last: Dungeon.Button? = null
        while (now() < end) {
            val b = look { LostSector.subjugate(it) }
            val before = last
            if (b != null && before != null && samePlace(before, b)) return b
            last = b
            sleep(pauseShort)
        }
        return null
    }

    // ------------------------------------------------------------------------
    // The way out
    // ------------------------------------------------------------------------
    /**
     * The panel closed by the back key, the way DL1a closed it, and the
     * Crests page seen on two frames. The key goes only to the panel read on
     * two frames running at one place: sent to the page it would go home,
     * and on the main screen it raises "Exit the game?".
     */
    private fun closePanel(): Boolean {
        val end = now() + PAGE_WAIT * 2
        var pages = 0
        var backs = 0
        var last: Dungeon.Button? = null
        while (now() < end) {
            val img = grab()
            try {
                if (LostSector.page(img) != null) {
                    last = null
                    pages += 1
                    if (pages >= 2) return true
                    sleep(pauseShort)
                    continue
                }
                pages = 0
                val panel = LostSector.subjugate(img)
                if (panel != null) {
                    val before = last
                    last = panel
                    if (before != null && samePlace(before, panel) && backs < BACKS_MAX) {
                        backs += 1
                        back()
                        last = null
                        sleep(pauseLong)
                    } else {
                        sleep(pauseShort)
                    }
                    continue
                }
                last = null
                val info = Dungeon.recognise(img)
                if (info.state == Dungeon.EXIT) {
                    cancel(img, info)
                    if (downloadAsked) return false
                    continue
                }
            } finally {
                img.release()
            }
            sleep(pauseShort)
        }
        return false
    }

    /**
     * Home to the plain main screen from wherever the tower left the game:
     * the panel by the back key (two frames at one place), the Crests page
     * by its white X ([Summon.exitButton], read on the frame), a Reward sheet
     * tapped away, a prompt Cancel, the globe where one is read. Bounded,
     * and true once the main screen has stood on two frames. This is how a
     * pass ends, the switch among the ways; since 2026-09-30 it asks the
     * switch first, and with it off the game stays where it is ([Stays],
     * PLAN_WORLD_SEARCH_FORMATE.md F24).
     * Every error inside is said and swallowed: it runs in `run`'s finally,
     * and the error worth reading is the pass's.
     */
    private fun goHome(): Boolean {
        // The game's download dialog is the director's; there is no way
        // home from under it that does not press one of its buttons. And
        // none from the title but "Touch To Start", the player's ([onTitle]).
        if (handsOff) return false
        try {
            val end = now() + HOME_WAIT
            var mains = 0
            var backs = 0
            var xs = 0
            var globes = 0
            var last: Dungeon.Button? = null
            while (now() < end) {
                // With the switch off the game stays where it is ([Stays], F24).
                if (stays.now()) return false
                val img = grab()
                try {
                    // One of the game's own windows is the director's ([Stays.over], B60).
                    if (stays.over(img)) return false
                    if (Dungeon.autoButton(img) != null) {
                        mains += 1
                        if (mains >= 2) {
                            if (backs + xs + globes > 0) log("  back on the main screen")
                            return true
                        }
                        sleep(pauseShort)
                        continue
                    }
                    mains = 0
                    val panel = LostSector.subjugate(img)
                    if (panel != null) {
                        val before = last
                        last = panel
                        if (before != null && samePlace(before, panel) && backs < BACKS_MAX) {
                            backs += 1
                            back()
                            last = null
                            sleep(pauseLong)
                        } else {
                            sleep(pauseShort)
                        }
                        continue
                    }
                    last = null
                    if (LostSector.page(img) != null) {
                        val x = Summon.exitButton(img)
                        if (x != null && xs < HOME_PRESSES_MAX) {
                            if (xs == 0) log("  the Crests page's X")
                            xs += 1
                            tap(x.fx, x.fy, Dungeon.Anchor.BOTTOM)
                            sleep(pauseLong)
                            continue
                        }
                    }
                    val info = Dungeon.recognise(img)
                    if (info.state == Dungeon.REWARD) {
                        tap(0.5, NEUTRAL_TAP_Y, Dungeon.Anchor.BOTTOM)
                        sleep(TICK)
                        continue
                    }
                    if (info.state == Dungeon.EXIT) {
                        cancel(img, info)
                        if (handsOff) return false
                        continue
                    }
                    if (metTitle(img)) return false
                    val globe = Dungeon.homeButton(img)
                    if (globe != null && globes < HOME_PRESSES_MAX) {
                        if (globes == 0) log("  pressing the home button")
                        globes += 1
                        tap(globe.fx, globe.fy, Dungeon.Anchor.BOTTOM)
                        sleep(pauseLong)
                        continue
                    }
                } finally {
                    img.release()
                }
                sleep(pauseShort)
            }
            if (stays.now()) return false
            log("  could not get back to the main screen -- the game is left where it stands")
            keepFrame("no_way_home")
        } catch (e: Exception) {
            log("  the way home failed: ${e.message}")
        }
        return false
    }

    /**
     * A prompt nothing here raised -- this skill raises none but through a
     * back key gone astray -- answered Cancel whichever kind it is, the grey
     * "Exit the game?" with its frame kept (DungeonSkill.dismissConfirm
     * without a `wantOk`: OK is never this skill's to press). Not the game's
     * download dialog, whose Cancel ends the game too: nothing is tapped
     * there, and the pass hands the screen back to the director.
     */
    /**
     * The game's title in front (Startup.title: its bar, or by day its Menu
     * button, B43): the pass ends where it stands -- said, its frame kept,
     * parked with DungeonSkill.TITLE_WHY -- and nothing more is tapped
     * ([onTitle]). True where it is.
     */
    private fun metTitle(img: Mat): Boolean {
        if (!Startup.title(img)) return false
        if (!onTitle) {
            log("  the game is on its title screen -- the pass ends here, nothing tapped")
            if (saved < KEEP_MAX) {
                saved += 1
                keep(img, "title")
            }
        }
        onTitle = true
        if (parkedBecause == null) parkedBecause = DungeonSkill.TITLE_WHY
        return true
    }

    private fun cancel(img: Mat, info: Dungeon.Recognition) {
        val ok = info.exitOk ?: return
        if (info.exitKind == Dungeon.KIND_DOWNLOAD) {
            // The game's download dialog: Cancel ends the game, and OK is
            // the director's, in one place (DirectorLoop.download). Nothing
            // is tapped, and the pass ends where it stands ([downloadAsked]),
            // as DungeonSkill.dismissConfirm ends its own.
            if (!downloadAsked) {
                if (saved < KEEP_MAX) {
                    saved += 1
                    keep(img, "download")
                }
                log("  the game asks to download its data -- leaving it to the director, nothing tapped")
            }
            downloadAsked = true
            if (parkedBecause == null) parkedBecause = DungeonSkill.DOWNLOAD_WHY
            return
        }
        if (info.exitKind == "beenden") {
            if (saved < KEEP_MAX) {
                saved += 1
                keep(img, "confirm_beenden")
            }
            log("  exit dialog, leaving via Cancel")
        } else {
            log("  an in-game prompt nothing here raised -- Cancel")
        }
        val cancelX = if (ok.fx > 0.5) 1.0 - ok.fx else Dungeon.POS_EXIT_CANCEL
        tap(cancelX, ok.fy, info.anchor)
        sleep(pauseLong)
    }

    // ------------------------------------------------------------------------
    // The hand and the eye
    // ------------------------------------------------------------------------
    private fun grab(): Mat = cap.grab().also { rect = Dungeon.gameRect(it); room = Dungeon.headroom(it) }

    /** grab, read, release: one answer off a fresh frame. */
    private fun <T> look(read: (Mat) -> T): T {
        val img = grab()
        try {
            return read(img)
        } finally {
            img.release()
        }
    }

    /** The same non-null answer of [read] on two fresh frames running, within [seconds]; or null. */
    private fun twice(seconds: Double, read: (Mat) -> String?): String? {
        val end = now() + seconds
        var last: String? = null
        while (now() < end) {
            val answer = look(read)
            if (answer != null && answer == last) return answer
            last = answer
            sleep(pauseShort)
        }
        return null
    }

    /** [check] true on two fresh frames running within [seconds] ("never trust a single frame"). */
    private fun waitFor(seconds: Double, check: (Mat) -> Boolean): Boolean =
        twice(seconds) { if (check(it)) "yes" else null } != null

    /**
     * A tap by fraction of the rectangle at [anchor] of the last frame read
     * (Dungeon.gameRect): the page's pill, the panel and the toast stand in
     * the middle of the headroom ([LostSector.PAGE]), the tile, the X, the
     * neutral spot and the globe at the bottom with the HUD. One rectangle on
     * every display under the canvas ceiling.
     */
    private fun tap(fx: Double, fy: Double, anchor: Dungeon.Anchor) {
        // Not with the main switch off: the service would hold it back, and
        // the pass stops at its next look ([stillOn]). Nor over the download
        // dialog or on the title ([handsOff]).
        if (handsOff || !on()) return
        val r = rect ?: return
        val top = r.y0 - Py.roundInt(room * anchor.share)
        cap.tap(Py.roundInt(r.x0 + fx * r.gw), Py.roundInt(top + fy * r.gh))
    }

    private fun back() {
        if (handsOff || !on()) return
        try {
            cap.back()
        } catch (e: Exception) {
            log("  back key failed: ${e.message}")
        }
    }

    private fun stillOn(): Boolean {
        // Not the switch: the game's download dialog is up ([downloadAsked]),
        // or its title ([onTitle]).
        if (handsOff) return false
        if (on()) return true
        if (!stopped) log("  stopped")
        stopped = true
        return false
    }

    /** A frame worth looking at afterwards, capped so that a long pass cannot fill the disk. */
    private fun keepFrame(tag: String) {
        // Nothing failed where the switch cut a step short (PLAN_RELEASE_1_3.md B4).
        if (!on()) return
        if (saved >= KEEP_MAX) return
        val img = try { grab() } catch (e: CaptureError) { return }
        try {
            saved += 1
            keep(img, tag)
            log("  frame kept: $tag")
        } finally {
            img.release()
        }
    }

    /** Two reads of one button at the same place, to the thousandth (DungeonSkill's fingerprint). */
    private fun samePlace(a: Dungeon.Button, b: Dungeon.Button): Boolean =
        Py.roundInt(a.fx * 1000) == Py.roundInt(b.fx * 1000) &&
            Py.roundInt(a.fy * 1000) == Py.roundInt(b.fy * 1000)

    private fun plural(n: Int) = if (n == 1) "" else "s"

    companion object {
        /** The Dungeons card's lines this skill's pass goes on (SkillStats.SHOWN, "dungeon"). */
        const val CARD_RUNS = "lost_sector_runs"
        const val CARD_WON = "lost_sector_won"

        /** What a panel of the tower's that DL1a never saw is parked with (3.2 point 7). */
        const val STRANGE_PANEL = "The Lost Sector Tower's panel is not the one I know -- a price, a " +
            "counter or another button on it. I tap nothing there."

        /** Where [walk] and [runBack] find themselves, beside `recognise`'s states. */
        private const val MAIN = "main"
        private const val PAGE = "page"

        /** RunnerSkill's number: a tap sent into an animation is swallowed, so three, never more. */
        const val NAV_TAPS_MAX = 3

        /** Two frames of the main screen or the page at the start; a window opening is neither. */
        const val START_WAIT = 6.0

        /**
         * How long the Crests page, and after Enter the panel, are given to
         * stand. Not measured as a latency: DL1a found each on the next frame
         * it took (main_icon_013327 then page_013331, 4 s apart at that
         * capture's pace; the panel likewise). 8 s is two of those.
         */
        const val PAGE_WAIT = 8.0
        const val PANEL_WAIT = 8.0

        /**
         * How long a Subjugate is watched for the toast or the panel going.
         * The toast stood on the frame 1.4 s after the tap (G4); the daily
         * dungeon's panel was gone 1.2 to 2.0 s after its Attempt (4.1 point
         * 6). 8 s is four times the longest, as DungeonSkill's Attempt gets
         * eight (times its patience).
         */
        const val SUBJUGATE_WAIT = 8.0

        /**
         * How long a run is waited for, from the Subjugate's panel going to
         * its panel back: DungeonSkill's own number for the same question,
         * [DungeonSkill.BATTLE_TIMEOUT], so that one measurement moves both
         * (G18). That number is 150 s since the live pass of 2026-09-29 outgrew
         * the PC's 90 (PLAN_DAILY_LOST_SECTOR_PRESETS.md G11 (5), question 24;
         * notes/dungeons.md, "A run can outlast 90 s"): Bakemon runs of 84 s
         * and over 95 s, the transitions on top of that. A run of the tower
         * itself was never measured (G12), so this is the dungeons' number
         * and not the tower's.
         */
        const val BATTLE_WAIT = DungeonSkill.BATTLE_TIMEOUT

        /** After Enter was tapped again inside [runBack]: DungeonSkill.DAILY_WAIT. */
        const val BACK_WAIT = DungeonSkill.DAILY_WAIT

        /** DungeonSkill.minBattle: back in under this was a message, not a run. */
        const val MIN_BATTLE = 6.0

        /** DungeonSkill.UNKNOWN_HOLD: the transition between two known screens measures 1.4 s. */
        const val UNKNOWN_HOLD = DungeonSkill.UNKNOWN_HOLD

        /** DungeonSkill's tick between two looks inside a wait. */
        const val TICK = 1.0

        /** DungeonSkill.NEUTRAL_TAP_Y: high up, above any window. */
        const val NEUTRAL_TAP_Y = DungeonSkill.NEUTRAL_TAP_Y

        /** DungeonSkill.CLOSE_W_MAX: a narrow Close, not an Attempt. */
        const val CLOSE_W_MAX = DungeonSkill.CLOSE_W_MAX

        /** Back keys a way out sends at most: one is what it takes, a second for one swallowed. */
        const val BACKS_MAX = 3

        /**
         * How long the way home looks, with DungeonSkill.HOME_PRESSES_MAX
         * taps of each kind at most. The longest way, panel -> page -> main
         * screen, is two gestures and six looks, about 8 s at the patience of
         * 1.5 in the flow test; 20 s is more than twice that. It is also what
         * a pass the switch ended spends looking, its gestures all withheld.
         */
        const val HOME_WAIT = 20.0
        const val HOME_PRESSES_MAX = DungeonSkill.HOME_PRESSES_MAX

        /** Frames one pass keeps at most. */
        const val KEEP_MAX = 6

        val DIALOGS = DungeonSkill.DIALOGS
    }
}
