package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import java.util.Locale

/**
 * The bond tour on the phone: `bond.py`'s `BondTour` under the director
 * (PLAN_ANDROID_APP.md 4, session L). It walks the Partner grid, presses
 * Raise on every Digimon in turn, and comes home between each two so that
 * the token of the Digimon just raised can be collected.
 *
 * Three pieces live in this file, and they are the three pieces bond.py and
 * passive.py have between them:
 *
 *   [BondTour]        the walk. `bond.BondTour.run(collect)`.
 *   [TourCollect]     one Digimon's token, on the tour's own clock.
 *                     `passive.PassiveBot._tour_collect` / `_tour_settle`.
 *   [BondTourSkill]   the seam: what the director calls, and when.
 *
 * Every reader it asks belongs to somebody else -- [Bond.gridButton],
 * [Bond.raiseButton], [Bond.cells], [Bond.raisedCell], [Bond.partnerSubtab]
 * (P2), [Explore.digimonTab], [Passive.partnerMenu], [Dungeon.recognise],
 * [Dungeon.autoButton], [Dungeon.homeButton] -- and not one of them is
 * touched here.
 *
 * What the director takes off the tour's hands is what it took off the
 * passive helper's: there is no second claimant on the phone, so
 * `stand_back`, `reserve_emulator` and `release_emulator` are gone. What
 * stood in for them is the main switch, asked between two actions and never
 * in the middle of one -- a tour told to stop finishes the step in hand,
 * goes home, and says that is why it stopped rather than blaming a step
 * that only failed because it was told to. `dry_run` is gone for the reason
 * DungeonSkill gives: the director has no way to call it.
 */
open class BondTour(
    private val cap: Capture,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** The main switch. Asked before every tap, never in the middle of one. */
    private val on: () -> Boolean = { MainSwitch.on },
    /**
     * Keep the frame a step gave up on. On the PC this writes into
     * `debug_bond/` under a name taken from the clock -- both of which cost a
     * piece of evidence to learn, because the borrowed DungeonBot's
     * `save_unknown` wrote `<tag>_00.png` with a counter that starts over
     * every run, and the second run of an afternoon wrote over the first
     * one's frame. core writes no files; the app does the naming.
     */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) {

    /** `bond.BondTour.run`'s stats dict. */
    class Stats(
        var cells: Int = 0,
        var startedAt: Int? = null,
        var visited: Int = 0,
        var collected: Int = 0,
        var returned: Boolean = false,
        var reason: String = "",
        var seconds: Double = 0.0,
    )

    /**
     * The reason a step failed, unless the real one is that the main switch
     * went off mid-step: a tour told to stop fails its next [attempt] on
     * purpose, and "failed three times in a row" would send whoever reads the
     * log after a bug that is not there.
     */
    private fun why(reason: String): String = if (!on()) STOPPED else reason

    // ------------------------------------------------------------------------
    // The steps
    // ------------------------------------------------------------------------
    /**
     * Tap the Digimon tab, if it can be found on this frame.
     *
     * Found, never assumed. A null here is not a silent no-op: it is the nav
     * bar reporting that it is dimmed or off screen, which is what a dialog
     * somebody left open looks like -- and [attempt]'s next round is what
     * closes that.
     */
    private fun tapDigimonTab() {
        val img = grabOrNull() ?: return
        try {
            val tab = digimonTab(img)
            if (tab == null) {
                log("  bond tour: no Digimon tab on this frame, the bottom bar " +
                    "is dimmed or gone")
                return
            }
            tap(img, tab.fx, tab.fy)
        } finally {
            img.release()
        }
    }

    /** Select the Partner sub-tab, wherever the page happened to open. */
    private fun tapPartnerSubtab() {
        val img = grabOrNull() ?: return
        try {
            val tab = partnerSubtab(img)
            if (tab == null) {
                log("  bond tour: no sub-tab row on this frame")
                return
            }
            if (tab.active) log("  bond tour: already on the Partner tab")
            tap(img, tab.fx, tab.fy)
        } finally {
            img.release()
        }
    }

    /**
     * Back to the main screen. True if it got there.
     *
     * Two routes, because one route home is no route home (NOTES.md). The
     * globe first: a globe that has actually been found, confirmed by the
     * auto button, which costs a look and nothing else where it does not
     * apply.
     *
     * Then the back key, up to [BACK_PRESSES] times -- the Encyclopedia is
     * three deep and has no nav bar to find a globe on. Every press is
     * preceded by a look, so a screen that is already home is never pressed
     * at all: with no dialog open the back key raises "Return to the title
     * screen?", whose OK ends the session.
     *
     * `DungeonSkill.goHome` is not borrowed for this, although it is the same
     * walk: its `tap` goes out in a reference rectangle that only its private
     * `begin` sets, so a borrowed one would tap in the wrong frame -- see the
     * session's report.
     */
    internal open fun goHome(): Boolean {
        var pressed = 0
        for (round in 0 until HOME_ROUNDS) {
            val img = grabOrNull() ?: break
            val done: Boolean
            try {
                if (autoButton(img) != null) {
                    if (pressed > 0) log("  bond tour: back on the main screen")
                    return true
                }
                val globe = homeButton(img)
                done = if (globe != null && pressed < HOME_PRESSES_MAX) {
                    tap(img, globe.fx, globe.fy)
                    pressed += 1
                    false
                } else {
                    true
                }
            } finally {
                img.release()
            }
            if (done) break
            sleep(STEP_PAUSE)
        }
        for (i in 0 until BACK_PRESSES) {
            val img = grabOrNull()
            if (img != null) {
                val home = try {
                    autoButton(img) != null
                } finally {
                    img.release()
                }
                if (home) return true
            }
            log("  bond tour: not home yet, pressing back")
            cap.back()
            sleep(STEP_PAUSE)
        }
        val img = grabOrNull() ?: return false
        return try {
            autoButton(img) != null
        } finally {
            img.release()
        }
    }

    /**
     * Close the small Partner dialog if it is what is in the way.
     *
     * The one wrong state this tour can walk into that it knows by name. A
     * tap on a figure opens it -- the tour does not aim at figures any more,
     * but the player's own hand and a mis-aimed nav tap both can -- and while
     * it is up the nav bar is dimmed, so every later tap would go into a
     * dialog instead of the bar.
     *
     * Closed the way the passive helper closes its own: a tap above the
     * panel, never the back key, which raises "Return to the title screen?"
     * when no dialog is open.
     */
    private fun clearStrayDialog(): Boolean {
        val img = grabOrNull() ?: return false
        return try {
            if (partnerMenu(img) == null) return false
            log("  bond tour: the small Partner dialog is in the way, closing it")
            tap(img, PassiveSkill.PARTNER_CLOSE[0], PassiveSkill.PARTNER_CLOSE[1])
            sleep(STEP_PAUSE)
            true
        } finally {
            img.release()
        }
    }

    /**
     * look -> tap -> settle -> grab -> check, up to [TRIES] times.
     *
     * Whatever `check` returns when it is not null is handed back, so a
     * caller that needs the recognised thing itself (the disc, the Raise
     * button) does not have to grab and read the screen a second time.
     *
     * **The look before the tap is the point.** The first live run repeated
     * one fixed tap three times; the first of them opened a dialog and the
     * second and third landed inside it, over a panel that happened to be
     * inert. An action that changed the screen has not failed the same way
     * twice, so before every retry the frame is asked what state it is
     * actually in and a known wrong one is undone first.
     */
    internal fun <T : Any> attempt(name: String, action: () -> Unit,
                                   check: (Mat) -> T?): T? {
        for (n in 0 until TRIES) {
            // No dump and no blame: nothing was misread, the switch is off.
            if (!on()) return null
            // Only from the second go round: the caller has just looked at
            // the screen to decide to call this at all.
            if (n > 0) clearStrayDialog()
            action()
            sleep(STEP_PAUSE)
            val img = grabOrNull() ?: continue
            val result = try {
                check(img)
            } finally {
                img.release()
            }
            if (result != null) return result
        }
        log("  bond tour: $name did not work after $TRIES tries")
        grabOrNull()?.let {
            try {
                keep(it, name.replace(" ", "_"))
            } finally {
                it.release()
            }
        }
        return null
    }

    /**
     * Open a fresh Partner page, expanded, and read who is raised.
     *
     * Returns (raisedIndex, totalCells) or (null, 0) on failure. Every turn
     * calls this once: on the first it learns where to come back to, on every
     * later one it is the proof the previous Raise landed.
     */
    internal open fun enter(): Pair<Int?, Int> {
        if (attempt("open the Digimon page", ::tapDigimonTab) { partnerSubtab(it) } == null) {
            return null to 0
        }
        // The page opens on whichever sub-tab was used last, not always on
        // Partner (player-confirmed, and a live run gave up on the Buddy
        // tab). Only Partner carries the grid, so it is selected every time
        // rather than only when it looks wrong: tapping the tab already
        // showing is inert, and `active` is a log line, not a decision.
        var disc = attempt("select the Partner tab", ::tapPartnerSubtab) { gridButton(it) }
            ?: return null to 0
        if (!disc.expanded) {
            val aim = disc
            disc = attempt("expand the grid",
                           { tapAt(aim.fx, aim.fy) },
                           { img -> gridButton(img)?.takeIf { it.expanded } })
                ?: return null to 0
        }
        val img = grabOrNull() ?: return null to 0
        return try {
            val points = cells(img)
            if (points.isNullOrEmpty()) {
                log("  bond tour: no Digimon cells found on the Partner screen")
                return null to 0
            }
            val idx = raisedCell(img)
            if (idx == null) {
                log("  bond tour: no Digimon reads as raised on a freshly opened page")
                return null to 0
            }
            idx to points.size
        } finally {
            img.release()
        }
    }

    /** Tap the target cell, then Raise, then confirm. True if it worked. */
    internal open fun raise(target: Int): Boolean {
        val img = grabOrNull() ?: return false
        val point = try {
            val points = cells(img)
            if (points == null || target >= points.size) {
                log("  bond tour: the grid is not readable, not tapping a cell blind")
                return false
            }
            points[target]
        } finally {
            img.release()
        }
        val tapped = attempt("select the next Digimon",
                             { tapAt(point.first, point.second) },
                             { raiseButton(it) }) ?: return false
        val confirmed = attempt("press Raise",
                                { tapAt(tapped.fx, tapped.fy) },
                                { raisePrompt(it) }) ?: return false
        confirmRaise(confirmed)
        return attempt("close the confirmation", { }) { gridButton(it) } != null
    }

    /**
     * The Raise prompt, or null. Nothing new to read: [Dungeon.recognise]
     * already answers EXIT with an `exitKind` of "party" for "Raise <name>?",
     * identically to the dungeon's own leaving prompt.
     */
    private fun raisePrompt(img: Mat): Dungeon.Recognition? {
        val info = recognise(img)
        return if (info.state == Dungeon.EXIT && info.exitKind == "party") info else null
    }

    /**
     * Press OK on the prompt this tour raised, and on no other.
     *
     * This is `dismiss_confirm`'s `want_ok` contract with the caller's intent
     * filled in: three prompts wear the same face, "Exit the game?" has a
     * grey Cancel and must be cancelled, and the two with a pink one --
     * "Disband the party and leave?" and "Return to the title screen?" -- are
     * identical to the pixel, so only the caller knows which one it raised
     * (NOTES.md, "Prompts and dialogs"). The tour knows: it tapped Raise a
     * second ago, and the grey one is refused by [raisePrompt] before this is
     * ever reached.
     *
     * `DungeonSkill.dismissConfirm` is not borrowed for the same reason
     * `goHome` is not -- see the session's report.
     */
    private fun confirmRaise(info: Dungeon.Recognition) {
        val ok = info.exitOk ?: return
        log("  bond tour: $WANT_OK via OK")
        tapAt(ok.fx, ok.fy)
        sleep(STEP_PAUSE)
    }

    /**
     * One full tour.
     *
     * [collect] answers whether this Digimon's token was there and taken --
     * it is the passive helper's own bond round on the tour's clock
     * ([TourCollect]) and not a second copy of it.
     */
    fun run(collect: () -> Boolean): Stats {
        val stats = Stats()
        val t0 = now()
        val first = grabOrNull()
        val clear = if (first == null) false else try {
            autoButton(first) != null
        } finally {
            first.release()
        }
        if (!clear) {
            stats.reason = "did not start from a clear main screen"
            return stats
        }
        var current: Int? = null
        var start: Int? = null
        try {
            val (s, total) = enter()
            if (s == null) {
                stats.reason = why("could not open the Partner screen")
                return stats
            }
            start = s
            stats.cells = total
            stats.startedAt = s
            // Every Digimon in turn and the one it began with last, so that a
            // finished tour leaves the player's own choice raised again.
            val order = (0 until total - 1).map { (s + 1 + it) % total } + s
            current = s
            var broke = false
            for (target in order) {
                if (!on()) {
                    stats.reason = STOPPED
                    broke = true
                    break
                }
                if (!raise(target)) {
                    stats.reason = why("a step failed three times in a row")
                    broke = true
                    break
                }
                stats.visited += 1
                if (!goHome()) {
                    stats.reason = "could not get back to the main screen"
                    broke = true
                    break
                }
                if (collect()) stats.collected += 1
                if (!on()) {
                    stats.reason = STOPPED
                    current = target
                    broke = true
                    break
                }
                val (next, seen) = enter()
                stats.cells = if (seen > 0) seen else stats.cells
                if (next != target) {
                    stats.reason = why("the last Raise did not take")
                    broke = true
                    break
                }
                current = target
            }
            if (!broke) stats.returned = true
        } finally {
            // Home either way. The last step of `order` is the Digimon the
            // tour started with, and the loop ends by opening the Partner
            // page again to check that its Raise took -- so a tour that
            // finished perfectly used to hand the game back **on the Partner
            // screen**, with the passive helper's next round reporting "not
            // the plain main screen" for as long as anybody watched. Only the
            // failing path went home, which is the one path where it was
            // obvious something was needed. `goHome` taps a globe it has
            // actually found and confirms with the auto button, so calling it
            // on a screen that is already home costs a look and nothing else.
            goHome()
            stats.seconds = now() - t0
        }
        if (!stats.returned && current != start) {
            log("  bond tour: stopped with $current raised instead of the Digimon " +
                "you started with ($start) -- ${stats.reason}")
        }
        return stats
    }

    // ------------------------------------------------------------------------
    // The seams the flow test replaces
    // ------------------------------------------------------------------------
    internal open fun grab(): Mat = cap.grab()

    private fun grabOrNull(): Mat? = try {
        grab()
    } catch (e: CaptureError) {
        null
    }

    /** A tap by fraction of the reference window, aimed with the frame it was read on. */
    internal open fun tap(img: Mat, fx: Double, fy: Double) {
        val r = Dungeon.gameRect(img)
        cap.tap(Py.roundInt(r.x0 + fx * r.gw), Py.roundInt(r.y0 + fy * r.gh))
    }

    /** The same tap where the only frame there is is the one just grabbed. */
    private fun tapAt(fx: Double, fy: Double) {
        val img = grabOrNull() ?: return
        try {
            tap(img, fx, fy)
        } finally {
            img.release()
        }
    }

    internal open fun gridButton(img: Mat): Bond.Disc? = Bond.gridButton(img)
    internal open fun raiseButton(img: Mat): Bond.Raise? = Bond.raiseButton(img)
    internal open fun cells(img: Mat): List<Pair<Double, Double>>? = Bond.cells(img)
    internal open fun raisedCell(img: Mat): Int? = Bond.raisedCell(img)
    internal open fun partnerSubtab(img: Mat): Bond.Subtab? = Bond.partnerSubtab(img)
    internal open fun digimonTab(img: Mat): Explore.NavTab? = Explore.digimonTab(img)
    internal open fun partnerMenu(img: Mat): Dungeon.Button? = Passive.partnerMenu(img)
    internal open fun autoButton(img: Mat): Dungeon.Button? = Dungeon.autoButton(img)
    internal open fun homeButton(img: Mat): Dungeon.Button? = Dungeon.homeButton(img)
    internal open fun recognise(img: Mat): Dungeon.Recognition = Dungeon.recognise(img)

    companion object {
        const val TRIES = 3
        const val STEP_PAUSE = 1.2

        /**
         * How many back presses the way home is allowed, when the globe
         * cannot be found. Three, because that is what the deepest screen
         * this tour can end up on actually costs: the small Partner dialog's
         * violet button opens the Encyclopedia, and the player counted three
         * ESC from there to the main screen. Each press is checked first --
         * see [goHome] -- so a screen that comes home in one does not spend
         * the other two on the title-screen prompt.
         */
        const val BACK_PRESSES = 3

        /** `DungeonBot.go_home`'s own two, for the globe half of the way home. */
        const val HOME_ROUNDS = DungeonSkill.HOME_ROUNDS
        const val HOME_PRESSES_MAX = DungeonSkill.HOME_PRESSES_MAX

        /** What the tour was doing when it raised a pink prompt. */
        const val WANT_OK = "raising a Digimon"

        /** The reason a tour gives when the main switch went off under it. */
        const val STOPPED = "the main switch went off, stopped and went home"
    }
}

/**
 * One Digimon's token, on the tour's own clock.
 *
 * `passive.PassiveBot._tour_collect` and `_tour_settle`. The passive helper
 * looks at the main screen every two seconds all day, so for it "no bubble
 * this round" costs nothing -- the next round is two seconds away and the
 * token is not going anywhere. The tour has no next round. It raises a
 * Digimon, comes home, asks once, and goes straight back into the Partner
 * screen for the next one, so a single frame used to be the whole of what it
 * ever saw of that Digimon. The player watched it move on before it had
 * collected anything, and the numbers said he was right: over one live tour,
 * from the frame the globe confirmed the main screen to the frame the bubble
 * was first seen on, **11.9 s, 6.4 s, 11.7 s, 6.1 s, 11.9 s** -- not one of
 * them on the first frame, none under six seconds.
 *
 * **The tapping is not repeated here.** The round that finds the bubble,
 * fences the aim and taps the figure is [PassiveSkill.work], and this drives
 * that round on a faster clock. What this loop adds is the waiting and the
 * schedule it gives up on.
 */
class TourCollect(
    private val cap: Capture,
    /**
     * One round of the passive helper on one frame, and what it saw. The real
     * one is [BondTourSkill.roundOf]: `passive.work(img)` followed by
     * `passive.seen`, which is the only way in -- a caller that cannot ask
     * what another skill read ends up guessing at it (NOTES.md, "Silencing
     * another skill's log throws away its measurements"). It is a function
     * rather than the skill itself because `PassiveSkill` is final and its
     * `seen` has a private setter, so a flow test cannot stand in for one --
     * see the session's report.
     */
    private val round: (Mat) -> PassiveSkill.Seen,
    /**
     * The pace the helper asks for, `PassiveSkill.beat`: Director.BEAT_WATCH
     * for BOND_WATCH after its tap, null otherwise. One clock owner -- the
     * round that tapped is the one that knows when to look again.
     */
    private val beat: () -> Double? = { null },
    private val log: (String) -> Unit = { HelperLog.line(it) },
    private val on: () -> Boolean = { MainSwitch.on },
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) {

    /**
     * Collect this Digimon's token, or leave when it has none.
     *
     * * a window is up and something was tapped -- the tap found nothing. See
     *   below;
     * * no bubble yet, and [TOUR_BUBBLE_WAIT] not up -- look again;
     * * no bubble, nothing else up, and something was tapped -- the token is
     *   collected;
     * * no bubble and nothing tapped by the deadline -- this Digimon has
     *   none, and that is not a failure. The tour goes on to the next one
     *   either way: every Digimon is visited, whether or not it had a token
     *   to give.
     *
     * **The window is what says whether the token was taken.** The player
     * named the rule: tapping the Digimon in the middle collects the bubble,
     * and where there is no bubble the same tap opens that Digimon's own
     * window instead. So the game answers the question itself, and it answers
     * it better than "the bubble is gone" ever did: a window covers the
     * bubble, so a tap that took nothing looked exactly like a tap that took
     * the token, and the log said "token collected" for both.
     *
     * **And the bubble gone is not the token taken either.** The Digimon
     * fights while the tour waits, dies, and takes its bubble with it for
     * two to four seconds (PassiveSkill.BOND_WATCH); the tour used to move
     * on to the next Digimon on the first frame without a bubble, which the
     * player watched happen. So the collected answer is the helper's own,
     * [GONE], said on the round BOND_WATCH after its tap that still found no
     * bubble -- and until it comes the tour looks at the helper's pace,
     * which is the fast one for exactly that long.
     *
     * A sighting resets the clock to [TOUR_COLLECT_WAIT], because from then
     * on there is something to wait for.
     */
    fun collect(): Boolean {
        var deadline = now() + TOUR_BUBBLE_WAIT
        var tapped = false
        var seen = false
        while (true) {
            if (!on()) return false
            val img = try {
                cap.grab()
            } catch (e: CaptureError) {
                return false
            }
            val seenNow = try {
                round(img)
            } finally {
                img.release()
            }
            val window = seenNow.did == CLOSED_WINDOW
            val bubble = seenNow.bubble
            if (window) {
                if (tapped) {
                    // The game's own answer, and it is "there was nothing
                    // there". Asked again it would say the same, so this
                    // Digimon is done: the round has already tapped the window
                    // away, and the next Digimon is a fresh screen.
                    log("  bond tour: the tap opened the Digimon's own window, " +
                        "so there was no token to take")
                    settle()
                    return false
                }
                // Nobody has tapped anything yet, so this window is not an
                // answer to a question this loop asked -- it is something in
                // the way. The round closes its own; keep looking, unless the
                // clock has run out.
                if (now() >= deadline) {
                    log("  bond tour: a window is in the way and will not go, going on")
                    return false
                }
                sleep(TOUR_LOOK)
                continue
            }
            if (seenNow.did == COLLECTED) {
                tapped = true
                seen = true
                deadline = now() + TOUR_COLLECT_WAIT
            } else if (seenNow.did == GONE) {
                log("  bond tour: token collected")
                settle()
                return true
            } else if (bubble != null) {
                if (!seen) {
                    seen = true
                    deadline = now() + TOUR_COLLECT_WAIT
                }
                if (now() >= deadline) {
                    log(String.format(Locale.US,
                        "  bond tour: the bubble is still there after %.0f s, going on",
                        TOUR_COLLECT_WAIT))
                    return false
                }
            } else {
                if (tapped) {
                    // The bubble went after the tap, which is what a dying
                    // Digimon looks like too. The helper says [GONE] itself
                    // once the bubble has stayed away for BOND_WATCH, and
                    // taps again if it comes back first; this waits for one
                    // or the other, at the helper's pace.
                    if (now() >= deadline) {
                        log(String.format(Locale.US,
                            "  bond tour: the bubble went after the tap but did not stay " +
                                "gone for %.0f s in %.0f s, going on",
                            PassiveSkill.BOND_WATCH, TOUR_COLLECT_WAIT))
                        return false
                    }
                } else if (now() >= deadline) {
                    log(String.format(Locale.US,
                        "  bond tour: no token for this one after %.0f s, going on",
                        TOUR_BUBBLE_WAIT))
                    return false
                }
            }
            sleep(beat() ?: TOUR_LOOK)
        }
    }

    /**
     * Wait for the plain main screen after a collect. True if it came.
     *
     * A tap on the figure does not always take a token: it opens that
     * Digimon's own window instead when there is nothing to collect, and it
     * opened one on a live tour when the aim landed on the Buddy standing
     * beside the partner. The tour then walked straight into the Partner page
     * with a window over the nav bar, gave up three times on "no Digimon tab
     * on this frame", and ended a run that had thirteen of fifteen Digimon
     * behind it.
     *
     * The passive helper already knows how to close that window and, more to
     * the point, knows whose it is -- its own tap a second ago is what makes
     * it its own. Closing it here is the same round it runs on the main
     * screen, on a faster clock, not a second way of doing it.
     */
    private fun settle(): Boolean {
        val deadline = now() + TOUR_SETTLE
        while (now() < deadline) {
            if (!on()) return false
            val img = try {
                cap.grab()
            } catch (e: CaptureError) {
                return false
            }
            val seenNow = try {
                round(img)
            } finally {
                img.release()
            }
            if (seenNow.clear) return true
            sleep(TOUR_LOOK)
        }
        log(String.format(Locale.US,
            "  bond tour: the main screen did not come back in %.0f s, going on anyway",
            TOUR_SETTLE))
        return false
    }

    companion object {
        // The two sentences PassiveSkill.work writes into `seen.did`. The
        // first is [PassiveSkill.DID_BOND] now -- the merge session gave it
        // a name because `lastCounts` there needs it too, so this side asks
        // for the constant rather than keeping a second copy of the words.
        // The second is still a string literal over there, so this is still
        // the only place on this side that says what it is; if it drifts, no
        // test falls. It belongs beside DID_BOND -- see the session's report.
        const val COLLECTED = PassiveSkill.DID_BOND
        const val GONE = PassiveSkill.DID_BOND_GONE
        const val CLOSED_WINDOW = "closed the partner window"

        /**
         * No bubble yet -- how long before this one is counted as having
         * none. Every Digimon is visited whether or not it has a token, so
         * this is the price of a Digimon with nothing to collect, paid once
         * each. The wait was 12.0 s for the run that was measured and the
         * worst sighting came in at 11.9, so the two that were "no token
         * after 12 s" may well have been half a second late rather than
         * absent: it is double the worst measured now.
         */
        const val TOUR_BUBBLE_WAIT = 25.0

        /**
         * A bubble has been seen -- how long to keep at it, counted from the
         * sighting. Longer, because there is something there:
         * PassiveSkill.BOND_RETRY between taps and BOND_TRIES of them comes
         * to twelve seconds before the round gives up by itself.
         */
        const val TOUR_COLLECT_WAIT = 20.0

        /**
         * After a collect -- how long to give the main screen to come back
         * before opening the Partner page again.
         */
        const val TOUR_SETTLE = 12.0

        /**
         * The pace of looking: the tour's TICK, where the helper has no
         * pace of its own to ask for (`beat`).
         */
        const val TOUR_LOOK = 1.0
    }
}

/**
 * The seam: what the director calls, and when.
 *
 * On the PC the tour is started from inside the passive helper's own round --
 * `passive.tick` collects a token, sees `do_all_digimon`, and walks the rest
 * of the Partner page for the same reason (passive.py, `_tour`). Under the
 * director that trigger is the same one, asked from outside: a round that
 * has just collected a token is a page whose other Digimon probably have one
 * too. What it asks is [PassiveSkill.seen], because that is what a caller is
 * given to ask with.
 *
 * It is deliberately not "there is a bubble on this frame". Two round-skills
 * on the main screen are called one after the other with a frame each, so by
 * the time this one looks the helper has already tapped the figure and the
 * bubble is gone -- the collection is the evidence, and the bubble is a race.
 */
class BondTourSkill(
    private val cap: Capture,
    /** The passive helper the director already runs. Its round is the tour's collect. */
    private val passive: PassiveSkill,
    /** `passive_all_digimon`, asked fresh every round. */
    private val flag: (String, Boolean) -> Boolean,
    /**
     * Whether a supporter code is redeemed on this phone: walking all of the
     * Digimon is a supporter's feature since 2026-09-23, collecting the one
     * being raised is everybody's. Core has no `Unlock.unlocked()` of its
     * own -- [Unlock] decides whether a code holds and the shell decides
     * whether one is saved -- so the shell passes the answer in. True by
     * default, because a flow test is not about the paywall.
     */
    private val supporter: () -> Boolean = { true },
    private val log: (String) -> Unit = { HelperLog.line(it) },
    private val on: () -> Boolean = { MainSwitch.on },
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) : Skill {

    override val key = "bond"
    override val name = "the bond tour"

    /** The main screen, where a tour begins and where it ends. */
    override fun worksOn(screen: String): Boolean = screen == Director.MAIN

    /**
     * The one box, asked fresh: has the player asked for all of the Digimon
     * rather than only the one being raised? What used to stand in front of
     * it -- the helper is watching, the helper is collecting -- is the Bond
     * token row's own switch now, and the director asks that itself
     * (`included`, over the shell's `Skills.rowFor`, which sends this skill
     * to that row). And the supporter code in front of the box: a box
     * ticked on a phone with no code is left standing (the shell greys it
     * out and keeps the setting), so it is asked here as well, fresh every
     * round like the box itself.
     */
    override fun hasBudget(): Boolean =
        supporter() && flag(PassiveSkill.PASSIVE_ALL_DIGIMON, false)

    /** The last tour's stats, for the shell and for the test. */
    var stats: BondTour.Stats? = null
        private set

    /**
     * The tour this pass walked, or none where it walked none. [stats] is
     * the last tour there was and outlives the pass on purpose; this does
     * not, because [lastCounts] is read after every round and nearly every
     * round tours nothing -- counting [stats] there would have added the
     * same tour to the day's numbers once every two seconds.
     */
    private var thisPass: BondTour.Stats? = null

    /**
     * What this pass did, for the Bond token card ([SkillStats], "passive").
     * The tokens are counted here and nowhere else: the helper's own round
     * counts the one it collected on the main screen, and every token the
     * tour takes is [TourCollect]'s, which no `Counted` wraps.
     */
    val lastCounts: Map<String, Int>
        get() = thisPass?.let { mapOf("tokens" to it.collected, "visited" to it.visited) }
            ?: emptyMap()

    /**
     * One round: a tour, but only where the helper's round just collected
     * something. Every other round this does nothing at all, which is what
     * keeps a walk of fifteen Digimon off a main screen with no token on it.
     */
    override fun work(img: Mat): Outcome {
        thisPass = null
        if (!hasBudget()) return going()
        // The round that saw the token stay collected, not the round that
        // tapped: a tour that set off on the tap walked into the Partner
        // grid with the first Digimon's bubble about to come back
        // (PassiveSkill.BOND_WATCH).
        if (passive.seen.did != TourCollect.GONE) return going()
        return tour()
    }

    /** The whole: the same tour, started from wherever the caller stands. */
    override fun run(): Outcome {
        thisPass = null
        return tour()
    }

    private fun tour(): Outcome {
        val walk = BondTour(cap, log = log, on = on, keep = keep, sleep = sleep, now = now)
        val collect = TourCollect(cap, roundOf(passive), beat = passive::beat, log = log,
                                  on = on, sleep = sleep, now = now)
        val s = walk.run(collect::collect)
        stats = s
        // A tour that broke off halfway still walked what it walked and took
        // what it took, so the day's numbers are set here and not after the
        // reason has been looked at.
        thisPass = s
        if (s.reason.isNotEmpty()) {
            log("  bond tour: ${s.reason}")
            return if (s.reason == BondTour.STOPPED) Outcome.STOPPED else Outcome.parked(s.reason)
        }
        log("  bond tour: ${s.visited} of ${s.cells} Digimon, ${s.collected} token(s) collected")
        return Outcome.DONE
    }

    /**
     * What a round answers when there was nothing to walk for. The same
     * sentence PassiveSkill's own rounds answer with, and for the same
     * reason: [Result] has no value for "still going", and `mainRound`
     * discards a round-skill's outcome anyway.
     */
    private fun going(): Outcome = Outcome.retired(
        "the bond tour walks only after a token was collected; ask again next round")

    companion object {
        /**
         * The passive helper's round, as [TourCollect] asks for it: one round
         * on one frame, then what it saw. Both halves matter -- the round is
         * what taps the figure, and `seen` is the only way to find out whether
         * it did.
         */
        fun roundOf(passive: PassiveSkill): (Mat) -> PassiveSkill.Seen = { img ->
            passive.work(img)
            passive.seen
        }
    }
}
