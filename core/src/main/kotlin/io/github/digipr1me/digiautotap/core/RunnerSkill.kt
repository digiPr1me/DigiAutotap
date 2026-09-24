package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * Gekkomon Run under the director: runner.py's `RunnerBot`, cut at the seam
 * Skill.kt names for every walking skill --
 *
 *   run  = runner.py:1075  RunnerBot.run: sources_agree, go_to_event, the
 *          runs until the Fever Times are in, leave in a finally
 *   work = the same from the line after go_to_event has answered True,
 *          ending on the event page rather than at home
 *
 * Plays run after run until the pass has reached the Fever Times the page
 * asks for, stops playing a run whose score has reached [SCORE_CAP] and
 * lets it die there, and goes home. runner.py's `claim_missions` is not
 * called any more ([claimMissions]). The readers are
 * [Runner]; this file is the loop,
 * with every constant of runner.py under its Python name and value, and
 * three things that are the phone's own and say so where they stand:
 *
 *  - **There is no window and no ADB here.** The PC took its frames from the
 *    emulator window at 50 a second because an ADB screenshot took 0.6 s;
 *    the phone has one capture, `takeScreenshot`, at one frame every 0.35 s
 *    at best (NOTES.md, "takeScreenshot has a floor"). MixedCapture, the
 *    freeze watch, the mouse nudge and `sources_agree` are the PC's and are
 *    not here; a frame that cannot be taken is a CaptureError, and a run
 *    that loses its frames is left.
 *  - **The clock replaces the frame counter.** runner.py looked for the
 *    dialogs every sixth frame and read the score every tenth, which at 50
 *    frames a second is every tenth of a second; here every frame is the
 *    sixth, and both are asked on every one.
 *  - **A press is scheduled, not only triggered.** At 50 frames a second
 *    "press when the arrival is 0.3 s away" is a frame that always comes;
 *    at three a second the next look may be after the obstacle. So when
 *    the next frame would come too late, the loop sleeps until the lead
 *    and presses without another look ([playRun], "wait < gap"). And with
 *    one clean sighting, where the PC had six, the speed is the last one
 *    this run measured or the game's own growth ([Runner.speedPrior]).
 *
 * What it never does: it presses OK on no prompt it did not raise, and it
 * raises none -- the run dialogs are the event's own and are answered
 * here, by their recognised Quit; the director never sees them. And a run
 * that has what it came for is not quit: a run left through the pause
 * menu banks nothing (PLAN_GEKKOMON_RUN.md 7a), so nothing is pressed any
 * more and the next obstacle ends it.
 */
class RunnerSkill(
    private val cap: Capture,
    /** The three fields of the page, asked afresh at the start of every pass. */
    private val settings: () -> Settings,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** The main switch, asked between two actions and never in the middle of one. */
    private val on: () -> Boolean = { MainSwitch.on },
    /** Keep a frame worth looking at afterwards. The app writes files; core cannot. */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    /** runner.py's own default: every wait between the runs hangs off this one factor. */
    private val patience: Double = 1.5,
    /**
     * How many runs a sitting plays at most before it gives up on its
     * Fever Times ([session]). Injected so that the flow test can play a
     * whole sitting out in one run's worth of painted frames.
     */
    private val runsMax: Int = RUNS_MAX,
    /**
     * One more Fever Time for the day's count ([Settings.doneToday]), said
     * the moment it is counted, so that a pass the switch cuts short keeps
     * what it played. The app writes the file; core cannot.
     */
    private val feverCounted: () -> Unit = {},
    /** Injected so the flow test can run a whole session without waiting. */
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    /** Seconds, monotonic. Injected so that the flow test can move it. */
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) : Skill {

    /**
     * The one field of the page (SkillSettings, the runner page): how many
     * Fever Times a pass plays for, added up over its runs.
     *
     * The page has had three shapes. The PC's three fields -- this one, the
     * score to end a run at, and "claim the daily missions afterwards" --
     * until 2026-09-22; then the claim alone, the task playing for as long
     * as its switch was on; and since 2026-09-23 this one alone, the
     * player's call: the task plays for a number of Fever Times again, and
     * it claims nothing. The score it stops playing a run at is
     * [SCORE_CAP], the app's own rule and not the player's number.
     */
    data class Settings(val fevers: Int = FEVER_TARGET, val doneToday: Int = 0) {
        /**
         * What is still owed today. The target is the game day's since
         * 2026-09-23, the player's call ("das Fever-Ziel soll pro Tag
         * sein"): the event's daily mission counts nine for the day, and a
         * second chain round played another nine for nothing. The day is the
         * game's, [QuestSkill.nextReset] (08:00, Europe/Vienna) --
         * `Stored.runner` hands in what the day has played so far.
         */
        val left: Int get() = maxOf(0, fevers - doneToday)
    }

    override val key = "runner"
    override val name = "Gekkomon Run"

    override fun worksOn(screen: String): Boolean = screen == Director.EVENT_PAGE

    /** Asked afresh every round: a target of nought is a page that wants nothing. */
    override fun hasBudget(): Boolean = settings().left > 0

    /**
     * True on the event page until a pass has finished, and it reads
     * nothing; null from then on, and the director's own rules hold.
     *
     * A pass that has its Fever Times is the whole task, and the
     * one-work-per-visit rule is what says so ("Gekkomon Run has worked
     * this event page; leaving it to you"). What that rule gets wrong is a
     * pass that did *not* finish: the switch went off in the middle of a
     * run and on again with the page still open -- one visit, one pass
     * handed over, and the player would have to leave the page and come
     * back for the Fever Times still owed. So a pass that was cut short
     * leaves the page as work, as [Skill.seesWork] is written for, and one
     * that ran to its end hands the question back.
     */
    override fun seesWork(screen: String, img: Mat): Boolean? =
        if (screen == Director.EVENT_PAGE && !finished) true else null

    companion object {
        const val NAV_ROUNDS = 12
        const val NAV_TAPS_MAX = 3
        // What the page offers until the player says otherwise: the event's
        // daily mission asks for nine Fever Times (runner.py's own number).
        const val FEVER_TARGET = 9
        // The Daily Missions list is six cards long; three pages see all of it.
        const val CLAIM_SWEEPS = 3
        const val RUN_TIME_CAP = 100.0
        // No run lasts this long by itself; past it the loop is watching something
        // that is not a run any more, whatever the readers say.
        const val RUN_TIME_MAX = 180.0
        // How long to wait for the next obstacle to end a run once nothing is
        // pressed any more. Obstacles come every two seconds or so; this is ten of
        // them.
        const val DIE_TIMEOUT = 25.0
        /**
         * How long a run is watched without pressing for anything.
         *
         * runner.py's own number was 3.0 and it cost the player every run.
         * Measured on the phone over four runs on 2026-09-22: the first
         * obstacle stands in the picture at 2.68 to 2.70 s and reaches the
         * character at about 3.3, so the frame that should schedule the press
         * is the one taken at t 2.84 -- and `t > START_GRACE` refused it by a
         * hundredth of a second. The next look came at 3.22, 0.15 s past the
         * lead, and the character walked into the obstacle. The log of that
         * run, in one line:
         *
         *     slide at 3.22 s: eta 0.10, wait -0.31, age 0.15, v 0.64 measured
         *
         * eta 0.10 at 3.22 is an arrival at 3.32; the lead wanted 3.07, and the
         * grace was open from 3.00. It was never the tap's latency, the gate or
         * the frame's age -- those measure 3 to 10 ms, 1 to 7 ms and 0.10 s.
         *
         * 2.0, because that is where the road is measured empty:
         * `corpus/runner/phone_tall_road_empty_171232.png` is t 2.0 of one of
         * those runs and every reader answers nothing on it. What the grace is
         * for -- not reading the opening as a road -- it still does.
         */
        const val START_GRACE = 2.0
        /**
         * The score a run is no longer played at. Reached, nothing is
         * pressed any more and the next obstacle ends the run; the next run
         * starts as usual, so this stops a *run* and never the task.
         *
         * The player's rule of 2026-09-22, and deliberately not a setting:
         * the event's board is other players' too, and a run played to the
         * end by a machine is a record nobody can reach by hand. A run left
         * to die still gains a few hundred points before the result window,
         * so what the board sees is a little over this number. The page
         * says the number out loud (SkillSettings, the runner page), and
         * RunnerSkillTest holds the two to each other.
         */
        const val SCORE_CAP = 15000
        // How many runs a sitting plays at most. A pass that has not reached
        // its Fever Times by then gives up and says so, as runner.py did.
        const val RUNS_MAX = 30
        /**
         * How old a frame is by the time `grab` hands it over: the spike
         * measured the picture itself at 10 ms and turning it into a BGR Mat
         * at 77 ms (NOTES.md, "takeScreenshot has a floor"). The obstacle
         * in the frame stood where it stood this long before the clock was
         * read, and the track is fed that time, not the later one.
         */
        const val FRAME_AGE = 0.08
        /** What the loop takes for the gap to its next look before it has measured one. */
        const val GAP_GUESS = 0.4
    }

    /** What one session did, `RunnerBot.stats` key for key. */
    class Stats {
        var runs = 0
        var fevers = 0
        var best = 0
        var claimed = 0
        var reason = ""
        override fun toString() = "runs $runs, fevers $fevers, best $best, claimed $claimed, $reason"
    }

    // ------------------------------------------------------------------------
    private val pauseLong = 1.0 * patience

    /** The reference rect of the last frame read, which is what a tap is aimed with. */
    private var rect: Dungeon.GameRect? = null

    private var fever = Runner.FeverCounter()
    private var stats = Stats()

    /** The Fever Times this pass plays for, read off the page once per pass ([target]). */
    private var wanted: Int? = null

    /**
     * The page's number, asked once at the start of a pass and kept for
     * it: a pass that changed its target halfway would end on a count
     * nobody set. A run played on its own ([playRun] from a test) asks it
     * the first time it is needed.
     */
    private fun target(): Int = wanted ?: settings().left.also { wanted = it }

    /** A pass ended by reaching its target or [runsMax] -- not stopped, not lost ([seesWork]). */
    private var finished = false

    /** What the last session found; null before the first. */
    var lastSession: Stats? = null
        private set

    /**
     * What this session counted, for the TODAY card ([SkillStats],
     * [Counted]). `best` is the highest score a run of this session reached
     * and is the one number there the day does not add up -- it keeps the
     * best of the passes ([SkillStats.How.BEST]).
     */
    val lastCounts: Map<String, Int>
        get() = lastSession?.let { mapOf("runs" to it.runs, "fevers" to it.fevers, "best" to it.best) }
            ?: emptyMap()

    private fun grab(): Mat = cap.grab().also { rect = Dungeon.gameRect(it) }

    /** A tap by fraction of the reference window, as DungeonBot.tap sends one. */
    private fun tap(fx: Double, fy: Double, was: String) {
        val r = rect ?: return
        cap.tap(Py.roundInt(r.x0 + fx * r.gw), Py.roundInt(r.y0 + fy * r.gh))
    }

    private fun tap(target: Explore.Target, was: String) = tap(target.fx, target.fy, was)

    private fun px(fx: Double, fy: Double): Pair<Int, Int> {
        val r = rect ?: return 0 to 0
        return Py.roundInt(r.x0 + fx * r.gw) to Py.roundInt(r.y0 + fy * r.gh)
    }

    /** False when the session must not go on: the main switch, asked between two actions. */
    private fun pauseGate(): Boolean = on()

    private fun dump(img: Mat, tag: String) {
        keep(img, tag)
        log("    kept what it saw as $tag")
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

    /**
     * Wait for [check] to be true twice running, at most [rounds] rounds --
     * a frame mid-animation can show a state briefly that has not really
     * arrived yet. The frame that confirmed it, or null; the caller owns it.
     * [gated]: asked the main switch between two looks, which every wait on
     * the way in is and the way out of a run is not ([quitRun]).
     */
    private fun waitFor(rounds: Int = NAV_ROUNDS, gated: Boolean = true, check: (Mat) -> Boolean): Mat? {
        var confirmed = false
        for (i in 0 until rounds) {
            if (gated && !pauseGate()) return null
            val img = grab()
            if (check(img)) {
                if (confirmed) return img
                confirmed = true
            } else {
                confirmed = false
            }
            img.release()
            sleep(pauseLong)
        }
        return null
    }

    /**
     * Tap [target] and wait for [check]; again, up to [tapsMax] times. Every
     * step of the way in and out taps only once the screen is recognised and
     * confirms the tap with the screen after it: the Ranking window once ate
     * "Play Game" and cost a run of waiting (PLAN_GEKKOMON_RUN.md 3).
     */
    private fun tapUntil(target: Explore.Target, was: String, tapsMax: Int = NAV_TAPS_MAX,
                         rounds: Int = NAV_ROUNDS, gated: Boolean = true, check: (Mat) -> Boolean): Mat? {
        for (i in 0 until tapsMax) {
            tap(target, was)
            val img = waitFor(rounds, gated, check)
            if (img != null) return img
            if (gated && !on()) return null
        }
        return null
    }

    // ------------------------------------------------------------------------
    /** Main screen -> Events -> the event page. True once it shows. */
    fun goToEvent(): Boolean {
        val img = grab()
        try {
            if (Runner.eventPage(img) != null) {
                log("the event page is already open")
                return true
            }
            if (Runner.eventsDialog(img) != null) return openCard()
            if (Dungeon.autoButton(img) == null) {
                log("not the plain main screen, cannot open the event")
                dump(img, "not_main_screen")
                return false
            }
        } finally {
            img.release()
        }
        val events = tapUntil(Runner.EVENTS_ICON, "Events icon") { Runner.eventsDialog(it) != null }
        if (events == null) {
            if (on()) {
                log("the Events window did not open")
                onFrame { dump(it, "no_events") }
            }
            return false
        }
        events.release()
        return openCard()
    }

    private fun openCard(): Boolean {
        val page = tapUntil(Runner.EVENT_CARD, "Gekkomon Run card") { Runner.eventPage(it) != null }
        if (page == null) {
            if (on()) {
                log("the event page did not open")
                onFrame { dump(it, "no_event_page") }
            }
            return false
        }
        page.release()
        return true
    }

    // ------------------------------------------------------------------------
    /**
     * One run from Play Game to the event page. Returns why it ended:
     * "died", "capped", "enough", "stopped" or "lost".
     */
    fun playRun(): String {
        val started = tapUntil(Runner.PLAY_GAME, "Play Game", rounds = 4) { Runner.eventPage(it) == null }
        if (started == null) {
            if (on()) {
                log("Play Game did not start a run")
                onFrame { dump(it, "no_run") }
            }
            return if (on()) "lost" else "stopped"
        }
        started.release()
        stats.runs += 1
        log("run ${stats.runs}")
        fever.inFever = false
        // The gap between this loop's own frames, measured as it goes: what
        // stretches the track's two clocks and decides whether the next look
        // comes in time (the class comment).
        var gap = 0.0
        val track = Runner.Track { if (gap > 0) gap else GAP_GUESS }
        val t0 = now()
        var lastTap = -9.0
        var lastScore: Int? = null
        var scoreReads = 0
        var scoreAt = t0
        var dialogSeen = 0
        var tPrev = -1.0
        var dying: String? = null
        var dyingSince: Double? = null
        var lastSpeed: Double? = null
        var lastSpeedAt = 0.0
        var presses = 0
        var assumed = 0
        var firstSeen = -1.0
        var costDialogs = 0.0; var costBar = 0.0; var costScore = 0.0; var costRoad = 0.0
        var reads = 0; var sawRoad = 0; var orbPresses = 0
        while (true) {
            if (!on()) {
                quitRun("stopped")
                return "stopped"
            }
            val img = try {
                grab()
            } catch (e: CaptureError) {
                // The PC's frozen window, in the phone's form: no picture is
                // no run. Nothing is pressed on a frame nobody has.
                log("  no frame in the run (${e.message}) -- leaving it")
                quitRun("no frame")
                return "lost"
            }
            val t = now() - t0 - FRAME_AGE
            if (tPrev >= 0) gap = t - tPrev
            tPrev = t
            try {
                // dead? the result dialog, on two looks in a row. And a run
                // that finds itself on the event page was ended by somebody
                // else's hand: there is nothing left to play here.
                var mark = now()
                if (Runner.resultDialog(img) != null) {
                    dialogSeen += 1
                    if (dialogSeen >= 2) break
                } else {
                    dialogSeen = 0
                }
                if (Runner.eventPage(img) != null) {
                    log("  the run is gone -- the event page is back")
                    return "lost"
                }
                costDialogs += now() - mark; mark = now()
                if (t > RUN_TIME_MAX) {
                    log("  ${t.toInt()} s and no end to the run in sight -- leaving it")
                    dump(img, "run_overlong")
                    quitRun("overlong")
                    return "capped"
                }

                // the egg bar: fever times, counted over the whole pass. The
                // one that reaches the target ends the run the way the cap
                // does -- nothing is pressed any more (below).
                val (_, pink) = Runner.barState(img)
                costBar += now() - mark; mark = now()
                if (fever.feed(pink)) {
                    stats.fevers = fever.count
                    feverCounted()
                    log("  Fever Time ${fever.count} of ${target()}, at ${t.toInt()} s")
                    if (fever.count >= target() && dying == null) {
                        dying = "enough"
                        log("  that is enough -- letting the run end")
                    }
                }

                // the score: ends a run early at [SCORE_CAP], and, as
                // everywhere else here, never off a single frame.
                val score = Runner.readScore(img)
                costScore += now() - mark; mark = now()
                if (score != null) {
                    scoreAt = now()
                    scoreReads = if (score == lastScore) scoreReads + 1 else 1
                    lastScore = score
                    if (score >= SCORE_CAP && scoreReads >= 2 && dying == null) {
                        dying = "capped"
                        log("  score $score is at the cap of $SCORE_CAP -- not playing this run any further")
                    }
                }
                if (t > RUN_TIME_CAP && now() - scoreAt > 5 && dying == null) {
                    dying = "capped"
                    log("  ${t.toInt()} s in and the score cannot be read -- letting the run end")
                }

                // A run that is quit banks nothing: five Fever Times were
                // reached in runs left through the pause menu on 2026-09-19 and
                // the mission still read 2/9 afterwards, the two from the one
                // run that had ended by dying. So a run that has what it came
                // for is not quit, it is left to its next obstacle -- nothing
                // is pressed any more, and the result window follows within a
                // couple of seconds. Only a run that takes too long to die, or
                // one the player stopped, goes through the pause menu.
                if (dying != null) {
                    if (dyingSince == null) {
                        dyingSince = now()
                    } else if (now() - dyingSince!! > DIE_TIMEOUT) {
                        log("  no obstacle ended the run in ${DIE_TIMEOUT.toInt()} s -- quitting it")
                        quitRun(dying)
                        return dying
                    }
                    continue
                }

                // the road
                val found = Runner.obstacles(img)
                costRoad += now() - mark
                reads += 1
                if (found.isNotEmpty()) sawRoad += 1
                track.feed(t, found.firstOrNull())
                val prior = aged(t, lastSpeed, lastSpeedAt)
                // **A measured speed below the prior is two obstacles, not one.**
                // `Track` starts a new spur when a sighting stands more than
                // NEW_OBJECT to the right of the last one, or when the last one
                // is older than breakAfter() -- 0.875 s at this frame gap.
                // Neither catches the case the phone makes common: obstacle A
                // last seen late, at fx 0.40, a frame with nothing, then B
                // picked up late as well at 0.36. That is inside both fences,
                // the spur spans both, and the slope it gives is nonsense.
                // Read off the log of 2026-09-22 19:14: six of seven runs ended
                // on a press whose speed had just been "measured" at 0.23 to
                // 0.33 while the same runs measured 0.62 to 0.73 in their first
                // twenty seconds. A run only ever gets faster, so a measurement
                // that says it slowed is not a measurement; the prior stands,
                // and eta is scaled back to it (eta is (fx - CHAR_RIGHT) / v on
                // a sighting that does not move, so it goes as 1/v).
                var est = track.estimate(t, prior = prior)
                if (est != null && est.measured && est.speed < prior) {
                    est = Runner.Estimate(prior, est.eta * est.speed / prior, measured = false)
                }
                if (est != null && est.measured) { lastSpeed = est.speed; lastSpeedAt = t }
                val tNow = now() - t0
                // Ten of fifty-one runs in the log of 2026-09-22 died without
                // a single press: the grace was still running. It is counted
                // from a clock that starts when Play Game is *confirmed* --
                // two looks with a pause between them -- so say what the grace
                // is holding back, once per run.
                if (track.kind != null && firstSeen < 0) {
                    firstSeen = t
                    log("    first obstacle in view at %.2f s (grace ends at %.2f)".format(t, START_GRACE))
                }
                if (t > START_GRACE && tNow - lastTap > Runner.COOLDOWN) {
                    val kind = track.kind
                    // An arrival already behind the character is not pressed
                    // for. runner.py presses on `eta <= LEAD`, negative
                    // included, and never met one unpressed: at 50 frames a
                    // second its track went stale 0.15 s after the obstacle
                    // slid behind the character, inside the cooldown. Here
                    // the track stays fresh for two frames, 0.8 s, and the
                    // first flow test at this pace pressed twice for every
                    // obstacle -- once in time and once after it had gone.
                    if (est != null && kind != null && est.eta >= 0.0) {
                        // The lead is measured from the frame's own moment;
                        // what is left of it now is that less the time the
                        // frame took to arrive.
                        val wait = est.eta - Runner.LEAD.getValue(kind) - (tNow - t)
                        val nextLook = if (gap > 0) gap else GAP_GUESS
                        if (wait <= 0.0 || wait < nextLook) {
                            // The next frame comes after the moment to press:
                            // sleep until that moment and press blind, on the
                            // arithmetic of the sightings there are.
                            if (wait > 0.0) sleep(wait)
                            lastTap = now() - t0
                            press(kind)
                            presses += 1
                            if (!est.measured) assumed += 1
                            probe(kind, tNow, est, wait, gap, tNow - t, Runner.speedPrior(t))
                        }
                    } else if ((est == null || est.eta > Runner.ORB_CLEARANCE) && Runner.orbsInAir(img)) {
                        lastTap = now() - t0
                        press(Runner.JUMP_KIND)
                        orbPresses += 1
                        log("    jump at %.2f s for orbs (no obstacle tracked: est %s, kind %s)"
                            .format(tNow, if (est == null) "none" else "%.2f".format(est.eta), track.kind))
                    }
                }
            } finally {
                img.release()
            }
        }
        if (lastScore != null) stats.best = maxOf(stats.best, lastScore)
        val t = now() - t0
        log("  the run ended at ${t.toInt()} s" + (if (lastScore == null) "" else ", score $lastScore") +
            ", $presses presses" + (if (assumed > 0) " ($assumed on an assumed speed)" else ""))
        if (reads > 0) log(("  the road was in %d of %d frames, %d presses for orbs; the frame cost: " +
            "dialogs %.0f, bar %.0f, score %.0f, road %.0f ms").format(sawRoad, reads, orbPresses,
            costDialogs / reads * 1000, costBar / reads * 1000,
            costScore / reads * 1000, costRoad / reads * 1000))
        tapUntil(Runner.QUIT_RESULT, "Quit") { Runner.eventPage(it) != null }?.release()
        return dying ?: "died"
    }

    /**
     * The speed to time an obstacle with when there is only one clean
     * sighting: what this run last measured, carried forward at the rate the
     * game speeds up -- or, before there was a measurement, the game's own
     * curve ([Runner.speedPrior]).
     *
     * **A measured speed was set once and never aged.** Read off the log on
     * 2026-09-22: a run timed its obstacle at 12.74 s with v 0.59, the speed
     * it had measured seconds earlier, while the game's own curve says 0.87
     * for that moment. A speed that is too low makes `eta` too large and the
     * press too late -- there by half, some 0.2 s -- and later the longer the
     * run lasts, which is how the player described it. The game only speeds
     * up, at a rate that was measured ([Runner.SPEED_PER_SECOND]), so a
     * speed from `t - at` seconds ago is that much too low now.
     *
     * And it matters more the longer a run goes: at speed the obstacle
     * crosses the lane inside one frame gap, two clean sightings stop
     * happening, and nearly every press is timed on this prior.
     */
    private fun aged(t: Double, last: Double?, at: Double): Double =
        if (last == null) Runner.speedPrior(t) else last + Runner.SPEED_PER_SECOND * (t - at)

    /**
     * What the last press cost between the loop's decision and the gesture
     * being out of this app's hands: the front-and-switch gate and
     * `dispatchGesture` itself. Not the stroke, which runs on after it.
     */
    private var handCost = 0.0

    private fun press(kind: String) {
        val before = now()
        tap(if (kind == Runner.JUMP_KIND) Runner.JUMP else Runner.SLIDE, kind)
        handCost = now() - before
    }

    /**
     * One line per press, so that "it jumps too late" is a number and not an
     * impression. Written after the press, never before it.
     */
    private fun probe(kind: String, tNow: Double, est: Runner.Estimate, wait: Double, gap: Double,
                      age: Double, curve: Double) {
        log(("    %s at %.2f s: eta %.2f, wait %.2f, age %.2f, v %.2f%s (curve %.2f), " +
             "gap %.2f, hand %.0f ms").format(kind, tNow, est.eta, wait, age, est.speed,
             if (est.measured) " measured" else " assumed", curve, gap, handCost * 1000))
    }

    /**
     * Pause, then Quit. Lands on the event page.
     *
     * Not gated on the main switch, unlike runner.py's `_quit_run`: there
     * every wait asks the Stop flag first, so a run the player stopped got
     * its Pause tapped and was then left standing in the pause menu, with
     * "the pause menu did not open" in the log. On the phone the switch is
     * the notification's Pause, pressed without looking, and what a resume
     * must find is the event page -- the pause menu is a prompt to the
     * director, and a prompt it did not raise is a park. So the way out is
     * walked whatever the switch says, as [leave] is; it is bounded, and it
     * taps only what it has recognised.
     */
    private fun quitRun(why: String) {
        log("  leaving the run ($why)")
        val pause = tapUntil(Runner.PAUSE, "pause", rounds = 4, gated = false) { Runner.pauseDialog(it) != null }
        if (pause == null) {
            val img = try { grab() } catch (e: CaptureError) { return }
            try {
                if (Runner.resultDialog(img) != null) {
                    tapUntil(Runner.QUIT_RESULT, "Quit", gated = false) { Runner.eventPage(it) != null }?.release()
                    return
                }
                log("  the pause menu did not open")
                dump(img, "no_pause")
            } finally {
                img.release()
            }
            return
        }
        pause.release()
        tapUntil(Runner.QUIT_PAUSE, "Quit", gated = false) { Runner.eventPage(it) != null }?.release()
    }

    // ------------------------------------------------------------------------
    /**
     * Missions -> Daily Missions -> every Claim -> back to the event page.
     * Returns how many were claimed.
     *
     * Called by nothing since 2026-09-23: the player took the claims out of
     * the task, and the page's switch for them with it. Kept, and held by
     * its flow test, so that bringing it back is a field on the page and a
     * line in [session] rather than a port -- as TowerSkill is kept.
     */
    fun claimMissions(): Int {
        val missions = tapUntil(Runner.MISSIONS, "Missions") { Runner.missionsDialog(it) != null }
        if (missions == null) {
            if (on()) {
                log("the Missions window did not open")
                onFrame { dump(it, "no_missions") }
            }
            return 0
        }
        missions.release()
        tap(Runner.DAILY_TAB, "Daily Missions tab")
        sleep(pauseLong)
        // The list opens where it was last left: on 2026-09-19 that was
        // the bottom, with the claimable "3x" out of sight above and the
        // first sweep reading a list of Completes. So it is scrolled to
        // the top first, then swept downwards.
        for (i in 0 until 2) scroll(up = true)
        var claimed = 0
        for (sweep in 0 until CLAIM_SWEEPS) {
            for (i in 0 until 8) {
                if (!pauseGate()) return claimed
                val img = grab()
                val buttons: List<Explore.Target>
                try {
                    if (Runner.missionsDialog(img) == null) {
                        if (Runner.rewardOverlay(img) != null) {
                            tap(Runner.NEUTRAL, "Tap to close")
                            sleep(pauseLong)
                            continue
                        }
                        break
                    }
                    buttons = Runner.claimButtons(img)
                    if (buttons.isEmpty()) {
                        if (sweep == 0 && claimed == 0) dump(img, "missions_no_claim")
                        break
                    }
                } finally {
                    img.release()
                }
                tap(buttons[0], "Claim")
                claimed += 1
                sleep(pauseLong)
                val sheet = waitFor(rounds = 4) { Runner.rewardOverlay(it) != null }
                if (sheet != null) {
                    sheet.release()
                    tap(Runner.NEUTRAL, "Tap to close")
                    sleep(pauseLong)
                }
            }
            if (sweep < CLAIM_SWEEPS - 1) scroll(up = false)
        }
        stats.claimed += claimed
        log("claimed $claimed mission reward" + (if (claimed == 1) "" else "s"))
        closeToEventPage()
        return claimed
    }

    /**
     * One page of the Missions list, by a drag over the list itself.
     *
     * The cards run from fy 0.43 to 0.79 of the window; a drag that starts
     * on the header block above them scrolls nothing at all, measured twice
     * on 2026-09-19 with drags from fy 0.36. So both ends of the drag are
     * inside the cards, fx 0.476, and clear of the tab row below them.
     */
    private fun scroll(up: Boolean) {
        val (a, b) = if (up) 0.48 to 0.78 else 0.78 to 0.48
        val (x1, y1) = px(0.476, a)
        val (x2, y2) = px(0.476, b)
        cap.swipe(x1, y1, x2, y2, 400)
        sleep(pauseLong)
    }

    /**
     * From the Missions window (or the Reward sheet over it) back to the
     * event page. Each tap is aimed at something recognised on the frame:
     * the sheet's Tap to close, the window's dimmed X.
     */
    private fun closeToEventPage(): Boolean {
        for (i in 0 until 4) {
            val img = grab()
            try {
                if (Runner.eventPage(img) != null) return true
                if (Runner.rewardOverlay(img) != null) {
                    tap(Runner.NEUTRAL, "Tap to close")
                } else {
                    val x = Runner.missionsX(img)?.target
                        ?: Summon.exitButton(img)?.let { Explore.Target(it.fx, it.fy) }
                    if (x != null) tap(x, "X")
                }
            } finally {
                img.release()
            }
            sleep(pauseLong)
        }
        return onFrame { Runner.eventPage(it) != null }
    }

    /**
     * Event page -> X -> main screen, the globe as the fallback. A Missions
     * window or Reward sheet still up is closed on the way. Not gated on the
     * main switch: it runs on the way out whatever else happened, as every
     * skill's way home does.
     */
    fun leave(): Boolean {
        for (i in 0 until 6) {
            val img = try { grab() } catch (e: CaptureError) { return false }
            try {
                if (Dungeon.autoButton(img) != null) return true
                if (Dungeon.homeButton(img) != null) return goHome()
                if (Runner.rewardOverlay(img) != null) {
                    tap(Runner.NEUTRAL, "Tap to close")
                } else {
                    val x = Runner.missionsX(img)?.target
                        ?: Summon.exitButton(img)?.let { Explore.Target(it.fx, it.fy) }
                    if (x != null) tap(x, "X")
                }
            } finally {
                img.release()
            }
            sleep(pauseLong)
        }
        if (goHome()) return true
        log("could not get back to the main screen -- the game is left where it stands")
        onFrame { dump(it, "no_way_home") }
        return false
    }

    /** dungeon.go_home for the one way home this skill has: the globe until the auto button is back. */
    private fun goHome(rounds: Int = FarmSkill.HOME_ROUNDS): Boolean {
        var pressed = 0
        for (i in 0 until rounds) {
            val img = grab()
            try {
                if (Dungeon.autoButton(img) != null) {
                    if (pressed > 0) log("back on the main screen")
                    return true
                }
                val button = Dungeon.homeButton(img)
                if (button == null) {
                    sleep(pauseLong)
                    continue
                }
                if (pressed >= FarmSkill.HOME_PRESSES_MAX) break
                if (pressed == 0) log("pressing the home button")
                tap(button.fx, button.fy, "home button")
                pressed += 1
                sleep(pauseLong)
            } finally {
                img.release()
            }
        }
        return false
    }

    // ------------------------------------------------------------------------
    /**
     * A sitting's runs until the Fever Times are in: RunnerBot.run from the
     * line after go_to_event, without the claims and without the way home.
     * Begins and ends on the event page.
     *
     * Four ways out: the target is reached, the switch goes off, a run is
     * lost, or [runsMax] runs have been played short of it.
     */
    private fun session() {
        while (fever.count < target()) {
            if (!pauseGate()) {
                stats.reason = "stopped"
                return
            }
            val why = playRun()
            if (why == "stopped" || why == "lost") {
                stats.reason = why
                return
            }
            if (why == "enough") break
            if (stats.runs >= runsMax) {
                stats.reason = "$runsMax runs and still short"
                finished = true
                return
            }
        }
        stats.reason = "done"
        finished = true
    }

    private fun begin() {
        stats = Stats()
        fever = Runner.FeverCounter()
        wanted = null
        finished = false
    }

    private fun end(): Outcome {
        lastSession = stats
        log("Gekkomon Run: ${stats.runs} run" + (if (stats.runs == 1) "" else "s") +
            ", ${stats.fevers} of ${target()} Fever Time" + (if (target() == 1) "" else "s") +
            (if (stats.best > 0) ", best ${stats.best}" else "") + " -- ${stats.reason}")
        return when (stats.reason) {
            "stopped" -> Outcome.STOPPED
            "could not open the event" -> Outcome.parked("I could not open the Gekkomon Run event from here.")
            "lost" -> Outcome.parked("Play Game did not start a run, or the run was ended from outside. " +
                "Is a window open over the event page?")
            else -> Outcome.DONE
        }
    }

    /**
     * The second half: [img] is the event page the director classified. Work
     * it and end on the event page; the body grabs its own frames from here on.
     */
    override fun work(img: Mat): Outcome {
        rect = Dungeon.gameRect(img)
        begin()
        try {
            session()
        } finally {
            // Wherever the session stopped -- a Missions window, the sheet --
            // the screen handed back is the one handed over.
            if (stats.reason != "lost") closeToEventPage()
        }
        return end()
    }

    /** The whole: from the plain main screen, go there, work, come home. */
    override fun run(): Outcome {
        begin()
        try {
            if (!goToEvent()) {
                stats.reason = "could not open the event"
            } else {
                session()
            }
        } finally {
            leave()
        }
        return end()
    }
}
