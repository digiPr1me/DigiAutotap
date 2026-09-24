package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * The Meat Field under the director (PLAN_ANDROID_APP.md 4, Session L):
 * `farm.FarmBot` and `farm.next_visit`, carried over from farm.py, cut at
 * the seam Skill.kt names for this skill --
 *
 *   run  = farm.py:1248  FarmBot.visit
 *   work = farm.py:1256  visit from the line after go_to_field
 *          (farm.py:1037) has answered True; the field is left standing,
 *          visit's own way home belongs to run
 *
 * One visit, start to finish: every ripe plot harvested, the free seed
 * planted in every empty one, the timers read, and then the clock -- which
 * is the whole point of this skill and the reason it is the one skill with
 * a memory. [nextVisit] says when to come back and why, [hasBudget] asks
 * whether that time has come, and [remember] is where the answer is kept
 * (`farm_due_at`, `farm_grow_seconds`, `farm_last_reason` in app.py).
 *
 * The readers are Farm.kt (farm.py's own), Explore.kt and Dungeon.kt. This
 * file holds what farm.py has beyond them: the schedule and the loop. Every
 * constant keeps its Python name and value; a number here is changed in
 * farm.py first, with a measurement (NOTES.md, "Two implementations, one
 * direction").
 *
 * What it never does, and the sentences that cost it:
 *
 *  - it never taps a water bubble. The bubble is checked fresh right before
 *    every tap on a plot, whatever state that plot was read as a moment ago
 *    (NOTES.md, "the state is a frame, not a truth").
 *  - it never presses Water. A plot that turns out to be growing raises the
 *    game's own popup, and that popup is closed by tapping well clear of it,
 *    never by its button (PLAN_MEAT_FIELD 9).
 *  - it presses OK on no prompt it did not raise, and it raises none: the
 *    field has no confirmation prompt of its own, so [Outcome.leaving] is
 *    always null here and the director presses nothing in the hand-over.
 *  - it never sends the back key. The way out is the field's own X, then the
 *    globe on the Explore menu that X lands on.
 */
class FarmSkill(
    private val cap: Capture,
    /**
     * `farm_due_at`: when the next visit is due, epoch seconds, or null
     * where nothing has ever been recorded. Read fresh on every [hasBudget],
     * never remembered here -- a chain step's "nothing to do" is a question,
     * not a state (chain.py, farm_is_due).
     */
    private val dueAt: () -> Double?,
    /**
     * What one visit leaves behind: `farm_due_at`, `farm_grow_seconds` (only
     * when this visit planted something and could read the fresh plate) and
     * `farm_last_reason`, exactly the three app.py saves after every visit
     * (app.py:3867). core has no settings store yet, so the shell
     * hands this in. What is read back out again is [knownGrowSeconds].
     */
    private val remember: (dueAt: Double, growSeconds: Int?, reason: String) -> Unit,
    /**
     * `farm_grow_seconds` read back: the grow duration the last visit that
     * planted something learned, or null where none was ever recorded. Asked
     * only on a visit that planted nothing itself -- a grow duration does not
     * go stale (the field is the same field), and without it such a visit caps
     * no field timer at all, which is the one case the cap was written for
     * (app.py, both `next_visit` call sites).
     */
    private val knownGrowSeconds: () -> Int? = { null },
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** The main switch, asked between two actions and never in the middle of one. */
    private val on: () -> Boolean = { MainSwitch.on },
    /** Keep a frame worth looking at afterwards. The app writes files; core cannot. */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    /** farm.py's own default: every wait in this skill hangs off this one factor. */
    private val patience: Double = 1.5,
    /** Injected so the flow test can run the whole visit without waiting. */
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    /**
     * Epoch seconds, **not** the director's monotonic clock: `farm_due_at`
     * outlives the process, and a monotonic number means nothing after a
     * restart. app.py's own is `time.time()`.
     */
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) : Skill {

    override val key = "farm"
    override val name = "Meat Field"

    /**
     * The plain field, and only that. A field with the seed menu or the
     * water popup over it (Director.FIELD_DIALOG) is a dialog **the player**
     * raised -- this skill opens the seed menu itself and closes it again
     * inside one plot, so a dialog still standing when the director looks is
     * nobody's business here, and NOTES.md's rule about prompts is the same
     * rule. go_to_field asks the same question the other way round
     * (`meat_field_screen >= 6`, nothing covering it).
     */
    override fun worksOn(screen: String): Boolean = screen == Director.FIELD

    /**
     * chain.farm_is_due (chain.py:112), asked fresh every round.
     *
     * Skip rather than retire (PLAN_MEAT_FIELD 6.3): the free seeds refill
     * every hour, so there is no "nothing left until the daily reset" the way
     * the dungeon budgets have, and retiring the step would lock a long,
     * repeating chain out of the field for hours after the shortest timer
     * that happened to be running when the chain last reached it. None --
     * nothing ever recorded, e.g. right after the switch was turned on --
     * counts as due: activating the switch means visiting at once.
     */
    override fun hasBudget(): Boolean = farmIsDue(dueAt(), now())

    /**
     * The field, read afresh on every round the player spends standing on
     * it: is a plot ripe, or is one empty with a seed in hand?
     *
     * This is the one skill whose screen the player stays on and changes
     * under the app, and the two questions the director asks without a frame
     * are both wrong there. "Has worked this field" is a sentence about a
     * visit the app made; the player harvesting a plot by hand ends that
     * visit's truth the second the plot turns empty. And the clock -- the
     * whole point of this skill, [nextVisit]'s answer -- is a prediction of
     * when the field will next be worth a walk from the main screen. Neither
     * survives a player standing in the field, so on the field the frame
     * decides and both of them stand aside ([Skill.seesWork]).
     *
     * What it costs: [Farm.plotBadgeKind] six times and [Farm.freeSeeds]
     * once, every two seconds while the field is in front. Not
     * [Farm.plotState]: "growing" is the one state with nothing to do in it,
     * and telling it apart from an unreadable plot costs [Farm.plotTimer] on
     * all six cells, which is the expensive half of the reader and buys
     * nothing here.
     *
     * The brake ([futileOn]): a work that harvested nothing and planted
     * nothing, entered because this reader said there was something to do,
     * writes down the field it looked at. The same field never gets a second
     * one -- a plot that will not plant would otherwise be tapped every
     * round for as long as the player stood there. It takes a picture that
     * differs (the player did something, a timer ran out) or the clock
     * coming due, which for exactly this case is [FALLBACK], ten minutes.
     */
    override fun seesWork(screen: String, img: Mat): Boolean? {
        if (screen != Director.FIELD) return null
        var ripe = false
        var empty = false
        val kinds = StringBuilder()
        for (row in 0 until 3) {
            for (col in 0 until 2) {
                val kind = Farm.plotBadgeKind(img, col, row)
                when (kind) {
                    "ripe" -> ripe = true
                    "empty" -> empty = true
                }
                kinds.append(kind ?: "-").append(',')
            }
        }
        val seeds = Farm.freeSeeds(img)
        // A seed count that could not be read is not a reason to stand still:
        // plantOne reads it again for itself, and the brake catches a plot
        // that turns out to have nothing to plant into it.
        val work = ripe || (empty && (seeds == null || seeds > 0))
        lastSeen = "$kinds/${seeds ?: "?"}"
        if (!work) return false
        if (lastSeen == futileOn && !hasBudget()) return false
        return true
    }

    /**
     * The second half: the field is standing in front, work it and leave it
     * standing. [img] is the frame the director classified; the body grabs
     * its own from here on, as visit does.
     */
    override fun work(img: Mat): Outcome {
        rect = Dungeon.gameRect(img)
        // A fresh visit, and `work` is now called again for as long as the
        // player keeps changing the field under it ([seesWork]). Only a plot
        // THIS visit planted may supply the grow duration, so the set is
        // emptied here as `run` empties it -- carried over, the plot planted
        // two visits ago would hand [setClock] a remainder for a full grow.
        plantedCells.clear()
        val stats = Stats()
        try {
            body(stats)
        } finally {
            // A visit the main switch ended is not evidence about the
            // field: it got nowhere because it was stopped, and the brake
            // would then hold the same unchanged field shut after the
            // switch came back on.
            futileOn = if (stats.reason != "stopped" &&
                           stats.harvested == 0 && stats.planted == 0) lastSeen else null
            setClock(stats)
        }
        return outcomeOf(stats)
    }

    /** The whole: from the plain main screen, go there, work, come home. */
    override fun run(): Outcome {
        val stats = Stats()
        fieldTapped = false
        plantedCells.clear()
        // The chain's own walk to the field: whatever [seesWork] wrote down
        // while the player last stood there says nothing about the field
        // this run is about to open.
        futileOn = null
        lastSeen = null
        try {
            if (!goToField()) {
                stats.reason = "could not open the Meat Field"
            } else {
                body(stats)
            }
        } finally {
            // Both in a finally, and in this order, as visit and app.py have
            // them: the way home runs on every way out, and the clock is set
            // after every visit -- including one that got nowhere, which then
            // books the hour that "nothing growing, no timer read" gives it
            // rather than trying again on the next round for ever.
            leaveField()
            setClock(stats)
        }
        return outcomeOf(stats)
    }

    // ------------------------------------------------------------------------
    // The schedule -- a pure function, no capture, fully testable
    // ------------------------------------------------------------------------
    /** What one visit found, `FarmBot.stats` key for key. */
    class Stats {
        var harvested = 0
        var planted = 0
        /** Field timers actually read this visit, seconds. */
        val timers = ArrayList<Int>()
        var emptyLeft = 0
        var freeSeeds: Int? = null
        var refillIn: Int? = null
        var growSeconds: Int? = null
        var reason = ""
        /** How many plots are growing at all, read or not -- visit's `growing`. */
        var growing = 0

        override fun toString() =
            "harvested $harvested, planted $planted, timers $timers, empty $emptyLeft, " +
                "seeds $freeSeeds, refill $refillIn, grow $growSeconds, $reason"
    }

    /**
     * next_visit's two answers. Python writes the second into a
     * `reason_out` list because it has no second return value; this is that
     * list, with a name.
     */
    class Visit(val at: Double, val why: String) {
        override fun toString() = "$why (at $at)"
    }

    companion object {
        // ---- the schedule (farm.py, "The schedule") ----
        /** Seconds after a timer's own deadline, so it is surely done. */
        const val MARGIN = 20
        /** A free seed arrives once an hour. */
        const val REFILL = 60 * 60
        /** Something is growing but no timer on it could be read. */
        const val FALLBACK = 10 * 60

        // ---- the loop (farm.py, "FarmBot") ----
        const val NAV_TAPS_MAX = 3
        const val NAV_ROUNDS = 6
        const val PLOT_ROUNDS = 6
        const val EXIT_ROUNDS = 20
        const val EXIT_TAPS_MAX = 4

        /**
         * dungeon.py's HOME_ROUNDS and HOME_PRESSES_MAX, for the globe
         * [goHome] presses.
         */
        const val HOME_ROUNDS = 6
        const val HOME_PRESSES_MAX = 3

        /**
         * dungeon.py:85's NEUTRAL_TAP_Y, which is what farm.py taps the Stage
         * Failed banner away with -- not the director's own NEUTRAL_TAP_FY,
         * which is summon.py's 0.10. Two measured spots that both open
         * nothing; each caller keeps the one its Python has.
         */
        const val NEUTRAL_TAP_Y = 0.12

        /**
         * chain.farm_is_due (chain.py:112), which is [Chain.farmIsDue] now:
         * the budget questions live beside the chain that asks them
         * (PLAN_ANDROID_APP.md 4). Kept as the name this skill's test and
         * its callers already use, and as nothing else.
         */
        fun farmIsDue(dueAt: Double?, now: Double): Boolean = Chain.farmIsDue(dueAt, now)

        /**
         * When to come back, and why. farm.next_visit line for line.
         *
         * [timers]: field timers actually READ this visit, seconds -- growing
         * plots whose plate happened to parse.
         * [growing]: how many plots are known to be growing at all, read or
         * not (defaults to timers.size, "assume every growing plot's timer was
         * read", for callers that have nothing better). Needed to tell "something
         * is growing but its plate did not parse this time" (row 5) apart from
         * "nothing at all is growing" (row 6) -- both look like an empty timer
         * list otherwise.
         * [growSeconds]: a full grow duration -- the cap under which no field
         * timer's own misreading can push the next visit too late (a field
         * cannot possibly take longer to ripen than a full grow), and row 5's
         * own answer when no timer was read at all. Two places it can come
         * from, and the caller picks: the plate of a seed THIS visit planted
         * ([Stats.growSeconds], the only one a visit learns), or, on a visit
         * that planted nothing, the one an earlier visit learned and the
         * caller kept ([knownGrowSeconds], `farm_grow_seconds`). A grow
         * duration does not go stale -- the field is the same field -- and
         * without the remembered one a visit that planted nothing has no
         * ceiling at all, which is the one case the cap was written for.
         * [emptyLeft]: plots that stayed empty (no seeds to plant, or planting
         * failed).
         * [freeSeeds]: the count read this visit, or null if it could not be read.
         * [refillIn]: seconds until the next free seed, or null if unreadable.
         *
         * A field timer is capped at [growSeconds]; the refill is capped at
         * [REFILL]. Either cap turns "a misread came out far too large" into
         * "one visit arrives a little early", which is cheap, rather than "the
         * visit is scheduled for a day from now", which is not.
         *
         * | situation                                    | next visit               |
         * |----------------------------------------------|--------------------------|
         * | 1 timer(s) read, no plot empty                | min(timers) + MARGIN     |
         * | 2 plot empty, 0 seeds, refill readable        | earlier of min(timers), refill, both +MARGIN |
         * | 3 plot empty, 0 seeds, refill unreadable      | earlier of min(timers), REFILL, both +MARGIN |
         * | 4 plot empty, seeds > 0 (planting failed)     | FALLBACK, logged why     |
         * | 5 something growing, no timer read            | grow_seconds if known, else FALLBACK |
         * | 6 nothing growing, nothing empty (or empty w/ 0 seeds and no timers) | refill or REFILL, +MARGIN |
         */
        fun nextVisit(now: Double, timers: List<Int>, growSeconds: Int? = null,
                      emptyLeft: Int = 0, freeSeeds: Int? = null, refillIn: Int? = null,
                      growing: Int? = null): Visit {
            val howManyGrowing = growing ?: timers.size

            fun cap(value: Int?, ceiling: Int?): Int? {
                if (value == null) return null
                // Python: `min(value, ceiling) if ceiling else value` -- a
                // ceiling of 0 is no ceiling, the same as None.
                return if (ceiling != null && ceiling != 0) minOf(value, ceiling) else value
            }

            val cappedTimers = timers.mapNotNull { cap(it, growSeconds) }
            val cappedRefill = cap(refillIn, REFILL)

            fun done(reason: String, delay: Int) = Visit(now + delay, reason)

            if (cappedTimers.isNotEmpty() && emptyLeft == 0) {
                return done("field ripens", cappedTimers.min() + MARGIN)
            }

            if (emptyLeft > 0) {
                if (freeSeeds == 0) {
                    if (cappedTimers.isNotEmpty() && cappedRefill != null) {
                        return done("field ripens or seed arrives",
                                    minOf(cappedTimers.min() + MARGIN, cappedRefill + MARGIN))
                    }
                    if (cappedTimers.isNotEmpty()) {
                        return done("field ripens or seed arrives (refill unreadable)",
                                    minOf(cappedTimers.min() + MARGIN, REFILL + MARGIN))
                    }
                    if (cappedRefill != null) {
                        return done("seed arrives", cappedRefill + MARGIN)
                    }
                    return done("seed arrives (refill unreadable)", REFILL + MARGIN)
                }
                return done("empty plot with seeds on hand -- planting may have failed", FALLBACK)
            }

            if (howManyGrowing > 0) {
                if (growSeconds != null && growSeconds != 0) {
                    return done("something is growing, no timer read", growSeconds)
                }
                return done("something is growing, no timer read, grow time unknown", FALLBACK)
            }

            if (cappedRefill != null) {
                return done("nothing growing, waiting for a seed", cappedRefill + MARGIN)
            }
            return done("nothing growing, no timer read", REFILL + MARGIN)
        }
    }

    // ------------------------------------------------------------------------
    // The visit
    // ------------------------------------------------------------------------
    private val pauseLong = 1.0 * patience
    private val pauseShort = 0.5 * patience

    /**
     * The reference rect of the last frame read, which is what a tap is
     * aimed with. DungeonBot measures it once in `_device_size` and keeps
     * it; this keeps the newest, which is the same number on every frame of
     * one device and right again if the frame shape ever changes.
     */
    private var rect: Dungeon.GameRect? = null

    /** Only a field this visit opened (or found open) has an X worth tapping. */
    private var fieldTapped = false

    /** The field as [seesWork] last read it: the six badges and the seed count. */
    private var lastSeen: String? = null

    /** The field a work achieved nothing on, which gets no second one. */
    private var futileOn: String? = null

    /** The (col, row) of every plot THIS visit planted -- see [body]. */
    private val plantedCells = mutableSetOf<Pair<Int, Int>>()

    private fun grab(): Mat = cap.grab().also { rect = Dungeon.gameRect(it) }

    /** A tap by fraction of the reference window, as DungeonBot.tap sends one. */
    private fun tap(fx: Double, fy: Double, was: String) {
        val r = rect ?: return
        cap.tap(Py.roundInt(r.x0 + fx * r.gw), Py.roundInt(r.y0 + fy * r.gh))
    }

    private fun tap(target: Explore.Target, was: String) = tap(target.fx, target.fy, was)

    /**
     * False when the visit must not go on. On the PC this is both
     * `control.is_set()` and `wait_while_paused()` (NOTES.md:
     * wait_while_paused alone answers only the pause question); on the phone
     * there is one main switch and no pause, so this is that switch --
     * asked between two actions, never in the middle of one.
     */
    private fun pauseGate(): Boolean = on()

    private fun dump(img: Mat, tag: String) {
        keep(img, tag)
        log("    kept what it saw as $tag")
    }

    /** grab, read, release -- the shape of every "one number off a fresh frame". */
    private fun <T> onFrame(read: (Mat) -> T): T {
        val img = grab()
        try {
            return read(img)
        } finally {
            img.release()
        }
    }

    /**
     * farm.read_timer_twice: call [read] twice, [pause] apart, and believe
     * the second only if it is 0-4 s smaller than the first
     * (PLAN_MEAT_FIELD 2.4). A single frame is never trusted for a number
     * that is supposed to be counting down.
     */
    private fun readTwice(pause: Double = 2.0, read: () -> Int?): Int? {
        val first = read() ?: return null
        sleep(pause)
        val second = read() ?: return null
        return if (first - second in 0..4) second else null
    }

    /**
     * Wait for [check] to be true twice running, at most [rounds] rounds --
     * a frame mid-animation can show a state briefly that has not really
     * arrived yet. The frame that confirmed it, or null; the caller owns it.
     */
    private fun waitFor(rounds: Int = NAV_ROUNDS, check: (Mat) -> Boolean): Mat? {
        var confirmedOnce = false
        for (i in 0 until rounds) {
            if (!pauseGate()) return null
            val img = grab()
            if (check(img)) {
                if (confirmedOnce) return img
                confirmedOnce = true
            } else {
                confirmedOnce = false
            }
            img.release()
            sleep(pauseLong)
        }
        return null
    }

    private fun tapUntil(target: Explore.Target, was: String, tapsMax: Int = NAV_TAPS_MAX,
                         rounds: Int = NAV_ROUNDS, check: (Mat) -> Boolean): Mat? {
        for (i in 0 until tapsMax) {
            tap(target, was)
            val img = waitFor(rounds, check)
            if (img != null) return img
            if (!on()) return null
        }
        return null
    }

    // ------------------------------------------------------------------------
    /**
     * Main screen -> Explore menu -> Meat Field. True once it is open (all
     * six cells visible, nothing covering it).
     */
    fun goToField(): Boolean {
        var img = grab()
        try {
            if (Farm.meatFieldScreen(img) >= Director.FIELD_CELLS) {
                log("the field is already open")
                fieldTapped = true
                return true
            }
            if (Dungeon.stageFailed(img)) {
                log("the Stage Failed banner is up, tapping it away first")
                tap(0.5, NEUTRAL_TAP_Y, "dismiss Stage Failed")
                sleep(pauseLong)
                img.release()
                img = grab()
            }
            if (Dungeon.autoButton(img) == null) {
                log("not the plain main screen, cannot open the Meat Field")
                dump(img, "not_main_screen")
                return false
            }
            val tab = Explore.exploreTab(img)
            if (tab == null) {
                log("the Explore tab was not found")
                dump(img, "no_explore_tab")
                return false
            }
            // farm.py waits for E._dark_body_share >= E.MENU_DARK_MIN, which
            // is private to Explore.kt. exploreMenu is that same dark body
            // plus the two things the very next lines need anyway: the globe
            // (leave_field's way home) and the Digital World Search card
            // (meat_field_card's own anchor). Nothing is loosened by it.
            val menu = tapUntil(Explore.Target(tab.fx, tab.fy), "Explore tab") {
                Explore.exploreMenu(it) != null
            }
            if (menu == null) {
                if (on()) {
                    log("the Explore menu did not open")
                    onFrame { dump(it, "no_explore_menu") }
                }
                return false
            }
            try {
                val card = Explore.meatFieldCard(menu)
                if (card == null) {
                    log("the Meat Field card was not found")
                    dump(menu, "no_field_card")
                    return false
                }
                fieldTapped = true
                val field = tapUntil(card, "Meat Field card") {
                    Farm.meatFieldScreen(it) >= Director.FIELD_CELLS
                }
                if (field == null) {
                    if (on()) {
                        log("the Meat Field did not open")
                        onFrame { dump(it, "no_field") }
                    }
                    return false
                }
                field.release()
                return true
            } finally {
                menu.release()
            }
        } finally {
            img.release()
        }
    }

    // ------------------------------------------------------------------------
    /**
     * True if this ripe plot was collected, false otherwise. Never a second
     * blind tap: the state after the first is the only proof asked for
     * (NOTES.md, "the proof a claim landed is a different card, not a
     * smaller number" -- here, a different state).
     */
    private fun harvestOne(col: Int, row: Int, stats: Stats): Boolean {
        val ripe = onFrame { img ->
            if (Farm.plotState(img, col, row) != "ripe") false
            else if (Farm.bubbleOver(img, col, row)) {
                sleep(pauseLong)
                false
            } else true
        }
        if (!ripe) return false
        tap(Farm.plotTap(col, row), "ripe plot $col,$row")
        for (i in 0 until PLOT_ROUNDS) {
            if (!pauseGate()) return false
            sleep(pauseLong)
            val img = grab()
            try {
                val popup = Farm.waterPopup(img)
                if (popup != null) {
                    log("    plot $col,$row was still growing -- closing the popup, never Water")
                    tap(popup, "close water popup")
                    sleep(pauseLong)
                    return false
                }
                if (Farm.plotState(img, col, row) == "empty") {
                    stats.harvested += 1
                    return true
                }
                if (i == PLOT_ROUNDS - 1) {
                    log("    tapping the ripe plot $col,$row did not clear it")
                    dump(img, "harvest_no_proof_c${col}_r$row")
                }
            } finally {
                img.release()
            }
        }
        return false
    }

    /** "planted", "empty" (left alone) or "stop" (seeds are at zero, end the visit). */
    private fun plantOne(col: Int, row: Int, stats: Stats): String {
        val first = grab()
        // Null where neither reading came back: farm.py plants anyway and
        // only skips the "did it fall by one" check afterwards, because the
        // count that mattered -- "is it zero" -- was answered above.
        val before: Int?
        try {
            if (Farm.plotState(first, col, row) != "empty") return "empty"
            // free_seeds counts to zero, not a price, so a single confident
            // reading is already a complete answer -- but a plot is only
            // worth planting into if the count is not moving the wrong way
            // between two frames a beat apart, so both are still read.
            val seeds = readTwice(1.0) { onFrame { Farm.freeSeeds(it) } } ?: Farm.freeSeeds(first)
            if (seeds == 0) {
                log("    free seeds are at 0 -- ending this visit")
                return "stop"
            }
            if (Farm.bubbleOver(first, col, row)) {
                sleep(pauseLong)
                return "empty"
            }
            before = seeds
        } finally {
            first.release()
        }
        tap(Farm.plotTap(col, row), "empty plot $col,$row")
        val opened = waitFor(PLOT_ROUNDS) {
            Farm.seedMenu(it).first != null || Farm.waterPopup(it) != null
        }
        if (opened == null) {
            log("    tapping the empty plot $col,$row opened nothing")
            onFrame { dump(it, "plant_no_menu_c${col}_r$row") }
            return "empty"
        }
        var button: Explore.Blob
        try {
            val popup = Farm.waterPopup(opened)
            if (popup != null) {
                log("    plot $col,$row turned out to be growing -- closing the popup, never Water")
                tap(popup, "close water popup")
                sleep(pauseLong)
                return "empty"
            }
            val (slots, menuButton) = Farm.seedMenu(opened)
            val leftSlot = slots!![0]
            button = menuButton!!
            if (!Farm.seedSelected(opened, leftSlot)) {
                tap(leftSlot.fx, leftSlot.fy, "free seed slot")
                sleep(pauseLong)
                button = onFrame { Farm.selectButton(it) } ?: button
            }
            // PLAN_MEAT_FIELD 2.6 wants a second, independent check here --
            // does the left slot itself read 0 -- as a backstop to the
            // free_seeds == 0 check already made above before this plot was
            // ever tapped. Not built yet: no live frame of that slot at 0 has
            // been seen to measure a recogniser against (NOTES.md, no
            // threshold without a measurement), and the pre-check already
            // covers the case both are meant to catch.
        } finally {
            opened.release()
        }
        tap(button.fx, button.fy, "Select")
        for (i in 0 until PLOT_ROUNDS) {
            sleep(pauseLong)
            val img = grab()
            try {
                if (Farm.plotState(img, col, row) == "growing") {
                    val after = Farm.freeSeeds(img)
                    if (after != null && before != null && after != before - 1) {
                        log("    free-seed counter did not fall by 1 after planting " +
                                "($before -> $after) -- not trusting it for the rest of this visit")
                    }
                    stats.planted += 1
                    plantedCells.add(col to row)
                    return "planted"
                }
                if (i == PLOT_ROUNDS - 1) {
                    log("    OK did not plant plot $col,$row")
                    dump(img, "plant_no_proof_c${col}_r$row")
                }
            } finally {
                img.release()
            }
        }
        return "empty"
    }

    // ------------------------------------------------------------------------
    /**
     * Tap the X, then the globe on the Explore menu it lands on. True once
     * the main screen is back.
     *
     * Not gated on the main switch: this runs on the way out whatever else
     * happened, the same shape as explore.Nav.leave_board.
     */
    fun leaveField(): Boolean {
        var taps = 0
        for (i in 0 until EXIT_ROUNDS) {
            val img = grab()
            try {
                if (Dungeon.autoButton(img) != null) return true
                if (Dungeon.homeButton(img) != null) {
                    // The X has landed: closing the field goes back to the
                    // Explore menu, not home, and nothing on that menu is an
                    // X. Without this branch the loop sat out every remaining
                    // round there before reaching goHome below -- half a
                    // minute of the player watching the Explore menu.
                    return goHome()
                }
                if (taps < EXIT_TAPS_MAX && fieldTapped) {
                    // Only a field this visit opened (or found open) has an X
                    // worth tapping. leaveField runs in run's finally, so it
                    // also runs after goToField refused a screen it did not
                    // know -- and on 2026-09-19 that screen was a minigame the
                    // player was in, where the white plate matched something
                    // at (0.793, 0.957) and was tapped twice. The globe route
                    // above and goHome below stay open either way: both tap
                    // only a globe they have found.
                    //
                    // The same white X Quest.closeX already reuses:
                    // Summon.exitButton's fx/fy match the Meat Field's own X
                    // to the pixel, the same reused artwork (PLAN_MEAT_FIELD
                    // 2.2).
                    val x = Summon.exitButton(img)?.let { Explore.Target(it.fx, it.fy) }
                        ?: Explore.closeButton(img)?.let { Explore.Target(it.fx, it.fy) }
                    if (x != null) {
                        tap(x, "X to close the Meat Field")
                        taps += 1
                        sleep(pauseLong)
                        continue
                    }
                }
                sleep(pauseLong)
            } finally {
                img.release()
            }
        }
        if (goHome()) return true
        log("could not get back to the main screen -- the game is left where it stands")
        onFrame { dump(it, "no_way_home") }
        return false
    }

    /**
     * dungeon.go_home (dungeon.py:2008) for the one way home this skill has:
     * press the globe until the auto button is back.
     *
     * Two things it deliberately does not do, both dungeon.py's own reasons.
     * It is not gated on the main switch: by the time this runs the switch is
     * often already off, that being one of the ways a visit ends, and a gate
     * would skip the one step whose whole point is where the game is left. It
     * is bounded instead. And it never taps a position -- the nav bar is drawn
     * only on the list side of the game, so if the globe is not on the frame
     * the answer is not to tap where it would have been.
     *
     * What it checks after pressing is the auto button, not "the globe is
     * gone": independent evidence that the main screen is in front and clear.
     *
     * dungeon.go_home's third branch, `return_to_list` for a frame with no nav
     * bar at all, is not here: that is DungeonBot's own way out of a dungeon
     * panel, it presses the back key, and this skill presses no back key and
     * reaches goHome only from a frame whose globe it has just read.
     */
    private fun goHome(rounds: Int = HOME_ROUNDS): Boolean {
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
                if (pressed >= HOME_PRESSES_MAX) break
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
     * visit's body, from the line after go_to_field answered True: harvest
     * every ripe plot, plant every empty one, then read what is left
     * standing and what the header says.
     */
    private fun body(stats: Stats) {
        for (row in 0 until 3) {
            for (col in 0 until 2) {
                if (!pauseGate()) {
                    stats.reason = "stopped"
                    return
                }
                harvestOne(col, row, stats)
            }
        }
        planting@ for (row in 0 until 3) {
            for (col in 0 until 2) {
                if (!pauseGate()) {
                    stats.reason = "stopped"
                    return
                }
                if (plantOne(col, row, stats) == "stop") {
                    stats.reason = "free seeds used up"
                    break@planting
                }
            }
        }
        val img = grab()
        try {
            for (row in 0 until 3) {
                for (col in 0 until 2) {
                    val state = Farm.plotState(img, col, row)
                    if (state == "empty") stats.emptyLeft += 1
                    if (state == "growing") {
                        stats.growing += 1
                        val t = readTwice { onFrame { Farm.plotTimer(it, col, row) } }
                        if (t != null) {
                            stats.timers.add(t)
                            if ((col to row) in plantedCells && stats.growSeconds == null) {
                                // Only a plot THIS visit planted carries a
                                // full grow. The test used to be "anything
                                // was planted", so the first growing plot of
                                // the closing round supplied it -- and a plot
                                // that was already growing when the visit
                                // began carries a remainder. Measured over
                                // the 15 corpus field frames with two or more
                                // readable timers (_grow_probe.py), the first
                                // growing plot reads 0.36 to 1.00 of the
                                // longest timer on the same field, 11 of them
                                // below 1.00. The cost was not a visit too
                                // early: a number taken out of `timers` is by
                                // construction one of the values being capped,
                                // so min(capped) stays min(timers) and the cap
                                // could never fire at all -- a field of timers
                                // all misread too large would have been left
                                // for a day, which is the one thing the cap
                                // was written to prevent.
                                stats.growSeconds = t
                            }
                        }
                    }
                }
            }
            stats.freeSeeds = Farm.freeSeeds(img)
            // The refill timer is only drawn while the count sits under its
            // cap of 20; above it there is nothing to wait for anyway.
            if (stats.freeSeeds != null && stats.freeSeeds!! < 20) {
                stats.refillIn = readTwice { onFrame { Farm.seedRefill(it) } }
            }
        } finally {
            img.release()
        }
        if (stats.reason.isEmpty()) stats.reason = "done"
    }

    /**
     * The clock of this skill, after every visit: app.py's own three lines
     * (app.py:3861) -- next_visit on what the visit found, then
     * `farm_due_at`, `farm_grow_seconds` and `farm_last_reason` saved, with
     * [knownGrowSeconds] standing in for the cap where this visit planted
     * nothing.
     */
    private fun setClock(stats: Stats) {
        // The cap: what this visit planted, else what an earlier one learned.
        // `remember` still gets only [Stats.growSeconds], so a remembered
        // number is never written back over itself -- app.py's own
        // `if stats.get("grow_seconds")`.
        val cap = stats.growSeconds ?: knownGrowSeconds()
        val visit = nextVisit(now(), stats.timers, cap, stats.emptyLeft,
                              stats.freeSeeds, stats.refillIn, stats.growing)
        remember(visit.at, stats.growSeconds, visit.why)
        log("harvested ${stats.harvested}, planted ${stats.planted} -- ${stats.reason}")
        log("  back in ${((visit.at - now()) / 60).toInt()} min: ${visit.why}")
    }

    /** What the last visit found; null before the first. The TODAY card reads it. */
    var lastVisit: Stats? = null
        private set

    /** What this visit counted, for the TODAY card ([SkillStats], [Counted]). */
    val lastCounts: Map<String, Int>
        get() = lastVisit?.let { mapOf("harvested" to it.harvested, "planted" to it.planted) }
            ?: emptyMap()

    private fun outcomeOf(stats: Stats): Outcome {
        lastVisit = stats
        return outcomeFor(stats)
    }

    private fun outcomeFor(stats: Stats): Outcome = when (stats.reason) {
        "stopped" -> Outcome.STOPPED
        "could not open the Meat Field" -> Outcome.parked(
            "I could not open the Meat Field from here.")
        // No `leaving`: the field raises no confirmation prompt of its own,
        // so there is nothing for the director to press OK on afterwards.
        else -> Outcome.DONE
    }
}
