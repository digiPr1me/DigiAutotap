package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * EX Missions (PLAN_EX_MISSIONS.md, EX2, the player's task of 2026-09-28):
 * the rewards of the EX Missions tab behind the Missions tile of the main
 * screen, claimed until the tab is clean, and nothing else in that window
 * touched.
 *
 * What the game does, as EX1 measured it on LDPlayer instance 1 on
 * 2026-09-28/29 with 31 claims (the readers and their numbers are in
 * Missions.kt, the timings in the plan's 4.1):
 *
 *  * The tile opens one window with three tabs along its bottom --
 *    Collection, Daily Missions, EX Missions -- and it opens on Collection
 *    every time, whichever tab it was left on. Collection's cards carry
 *    yellow Claims in the same column as the EX rows. So a Claim is only
 *    ever tapped on a frame [Missions.exWindow] answers on -- the EX tab lit
 *    and its header block drawn -- and in the window nothing but the EX
 *    tab and the dimmed field that closes it is ever tapped.
 *  * On the EX tab, "row 1" is the header block ("EX Mission Achievement
 *    n", its own Claim grey until its count is reached) and "row 2" the
 *    first row of the list under it. Claimable rows stand first, and a tap
 *    on any yellow Claim of the list claims every claimable row at once; a
 *    row whose next level is already reached comes back yellow at the top.
 *    So what is tapped for row 2 is the topmost yellow Claim of the list,
 *    whatever row number it reads as -- on a list the player has scrolled
 *    the numbers from the top do not hold (the plan's 10.8), and it is the
 *    same tap -- and a third yellow row is never tapped on its own: the tap
 *    on row 2 takes it along (10.2, which settled the plan's question 4).
 *  * Every Claim raised the Reward sheet, 0.92 to 1.56 s after the tap
 *    (Dungeon.rewardSheet reads it from the frame on which "Tap to close"
 *    has faded in; Runner.rewardOverlay never did), and the tab was back
 *    0.32 to 0.57 s after "Tap to close" (once 1.09 s). The list is sorted
 *    anew under the incoming sheet.
 *  * The window has no X. A tap in the dimmed field above it closes it
 *    ([Missions.CLOSE_SPOT], the director's neutral spot), within 0.66 s.
 *  * A tap on a row's blue arrow closes the window and goes where that
 *    mission is played: the main screen for "Total Bits", and after a lost
 *    boss fight the main screen under "Stage Failed" with its "Growth
 *    Guide" window, whose buttons (Fight!, Hologram Device, Skill Cards,
 *    Digimon) are never tapped -- the neutral spot takes it away, as the
 *    director does (Director.NEUTRAL_TAP_FX), and that spot is the
 *    window's close as well.
 *  * The battle goes on behind the open window: row 2 turned yellow again
 *    while row 1 was being claimed, and the player asked for a clean window.
 *
 * The player's rules (the plan's section 7, 2026-09-28): row 2 until it is
 * no longer yellow, then row 1 until it is no longer yellow, and that pair
 * again until a whole round claimed nothing (the conductor's reading of
 * 10.5, "then this window should be clean"); **no brake** on the claims of
 * a row ("sometimes you have to tap hundreds of times", question 11) -- a
 * row ends only on two frames without its yellow, on a second tap that
 * changed nothing (the frame kept), or on the main switch; up to [RE_ENTER]
 * ways back in after a jump to the main screen, then the visit ends with
 * what it has and no park (question 8); no clock (question 2): the task
 * runs when the page's "Claim now" asks (DirectorLoop.runNow) and as a step
 * of the fully automatic chain, and in the semi-automatic mode it works the
 * Missions window wherever it is open -- on Collection it taps EX Missions
 * first (question 6) -- except on Daily Missions, the tab the window never
 * opens on and a player changes to only to claim by hand (10.11, the
 * conductor's decision). A supporter's task (question 5; the row in the app).
 *
 * Every tap is aimed at something read on the frame just before it:
 * the tile, the EX tab, a yellow Claim, "Tap to close" on a sheet that
 * reads, the dimmed field over a window that reads, the neutral spot over a
 * banner that reads. Never the back key, never OK on a prompt, never a tap
 * with the main switch off.
 *
 * The findings: notes/missions.md, "The EX Missions task claims what reads
 * yellow on the EX tab and nothing else" (the build) and "EX Missions ran
 * live from the page, from the window and as a chain step" (the proof).
 */
class ExMissionsSkill(
    private val cap: Capture,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** The main switch, asked before every tap. */
    private val on: () -> Boolean = { MainSwitch.on },
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    private val patience: Double = 1.0,
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    /** Seconds, monotonic. */
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) : Skill {

    override val key = KEY
    override val name = "EX Missions"

    /** This pass's count, for the TODAY card ([SkillStats.SHOWN], "exmissions"). */
    var lastCounts: Map<String, Int> = emptyMap()
        private set

    /** Claims that worked in this pass: a sheet came, or the row's yellow went. */
    private var claims = 0
    private var reentries = 0
    /** The overlay moved off the tile's rows in this pass ([clearTile]); put back in the pass's finally. */
    private var cleared = false
    /** The rectangle and headroom of the last frame read: what every tap is aimed in. */
    private var rect: Dungeon.GameRect? = null
    private var room = 0
    /** The yellow Claims of the last EX frame a row's loop read: whether a round left anything. */
    private var lastYellow: List<Missions.ExClaim> = emptyList()
    /** Rows whose Claim twice changed nothing in this pass: not tapped again before the next. */
    private val dead = HashSet<Row>()
    /** A work that ended on a Claim without effect holds the EX tab back until then ([seesWork]). */
    private var heldUntil = Double.NEGATIVE_INFINITY
    /** When the last Claim was tapped: a main screen long after it is no jump of its tap ([claimRow]). */
    private var tappedAt = Double.NEGATIVE_INFINITY
    /** The last claim counted by its row's colour alone, no sheet seen yet: a jump after it makes it the arrow. */
    private var unsheeted = false

    /** "row 2" and "row 1", in the order the player claims them. */
    private enum class Row(val label: String) { LIST("row 2"), HEADER("row 1") }

    override fun worksOn(screen: String): Boolean =
        screen == Director.MISSIONS || screen == Director.EX_MISSIONS

    /**
     * Always, while the row is switched on: no clock (the player's answer to
     * question 2). A chain step with nothing to claim opens the window, finds
     * nothing yellow and closes it again, in a few seconds.
     */
    override fun hasBudget(): Boolean = true

    /**
     * The EX tab: work where a yellow Claim stands. The Missions window on
     * Collection -- the tab it opens on -- not from a frame: Collection's
     * Claims are not this task's, and the EX tab cannot be seen from there,
     * so the director's own rules hold (once per visit, [hasBudget]). On
     * Daily Missions never: the window never opens there (the plan's 10.3),
     * so a window on Daily is a player who changed the tab to claim their
     * dailies by hand, and it is left to them (10.11, the conductor's
     * decision of 2026-09-29, the player's to overturn).
     */
    override fun seesWork(screen: String, img: Mat): Boolean? = when (screen) {
        Director.MISSIONS -> if (Missions.window(img)?.tab == Missions.DAILY) false else null
        Director.EX_MISSIONS -> when {
            now() < heldUntil -> false
            else -> Missions.exWindow(img)?.let { Missions.exClaims(img, it).isNotEmpty() } ?: false
        }
        else -> null
    }

    /**
     * On the EX tab: its claims. On the Missions window with Collection lit:
     * EX Missions, then its claims. On Daily Missions: nothing (see
     * [seesWork]). The window is left standing, on the EX tab, as `work`
     * leaves every screen.
     */
    override fun work(img: Mat): Outcome = workPass(img).also { carried = it.result == Result.STOPPED }

    private fun workPass(img: Mat): Outcome {
        begin()
        try {
            // A fresh frame to aim with: the director's is a round old.
            val first = grab()
            val (w, ex) = try { Missions.window(first) to Missions.exWindow(first) } finally { first.release() }
            if (w == null) return Outcome.DONE
            // The director asked [seesWork] on its own frame; the player may
            // have changed the tab since. Daily Missions is theirs (10.11).
            if (w.tab == Missions.DAILY) {
                log("ex missions: the Missions window is on Daily Missions -- that is yours, nothing tapped")
                return Outcome.DONE
            }
            if (ex == null) {
                log("ex missions: the Missions window is open")
                when (toExTab(w)) {
                    Entry.IN -> {}
                    Entry.STOPPED -> return Outcome.STOPPED
                    else -> return Outcome.DONE
                }
            } else {
                log("ex missions: the EX Missions tab is open -- working it")
            }
            val end = claimAll()
            if (end == End.STOPPED) return Outcome.STOPPED
            if (dead.isNotEmpty()) {
                heldUntil = now() + NO_EFFECT_HOLD
                log("ex missions: a Claim that changes nothing is not worked again for ${(NO_EFFECT_HOLD / 60).toInt()} min")
            }
            return Outcome.DONE
        } finally {
            finish()
        }
    }

    /** From the plain main screen: the tile, the window, the EX tab, the claims, and home. */
    override fun run(): Outcome = runPass().also { carried = it.result == Result.STOPPED }

    private fun runPass(): Outcome {
        begin()
        val first = try { grab() } catch (e: CaptureError) { return Outcome.noFrame(e) }
        first.release()
        try {
            when (enter(start = true)) {
                Entry.IN -> {}
                Entry.STOPPED -> return Outcome.STOPPED
                Entry.NOT_MAIN -> return Outcome.parked("EX Missions start from the main screen.")
                // No tile on a main screen: nothing tapped, nothing to undo
                // (said and kept by [enter]); the chain goes on.
                Entry.NO_TILE -> return Outcome.DONE
                // Something was tapped and nothing claimable reached: said
                // and kept by [enter], nothing counted, and home.
                Entry.NO_WINDOW, Entry.NO_TAB -> return home()
            }
            if (claimAll() == End.STOPPED) return Outcome.STOPPED
            return home()
        } finally {
            finish()
        }
    }

    /**
     * The chain's second hand: from the window, whichever tab, home. Gated
     * on the switch since 2026-09-30, as every way home is ([Stays],
     * PLAN_WORLD_SEARCH_FORMATE.md F24): with it off the game stays where it is.
     */
    override fun leave(): Boolean = try {
        stays.reset()
        if (stays.now()) false else goHome(gated = true)
    } catch (e: CaptureError) {
        false
    } finally {
        overlayBack()
    }

    // ------------------------------------------------------------------------
    // After a pause (PLAN_RELEASE_1_3.md B4, the player's rule of 2026-09-30)
    // ------------------------------------------------------------------------
    /** The last pass was stopped by the main switch and has not been gone on with or begun afresh since. */
    internal var carried = false
        private set
    private val stays = Stays(on) { log(it) }

    /**
     * The Missions window, on either tab, and the Reward sheet a Claim
     * raised over it (`unknown` to the director, [Dungeon.rewardSheet] on the
     * frame). What reads yellow is what is owed, so a pass that goes on
     * claims what is left and nothing twice.
     */
    override fun resumesOn(screen: String, img: Mat): Boolean =
        carried && (screen == Director.MISSIONS || screen == Director.EX_MISSIONS ||
            (screen == Director.UNKNOWN && Dungeon.rewardSheet(img)))

    /**
     * The pass the switch stopped, gone on with: the sheet closed where one
     * stands, the claims that are left on the EX tab, and -- [whole] -- home.
     * From the main screen (a chain step whose claim ended) the pass is
     * [run]'s: the claims are the screen's, and a second walk in claims only
     * what reads yellow.
     */
    override fun resume(img: Mat, whole: Boolean): Outcome {
        if (!carried) return if (whole) run() else work(img)
        log("ex missions: going on with the pass the main switch stopped")
        rect = Dungeon.gameRect(img)
        room = Dungeon.headroom(img)
        if (whole && Dungeon.autoButton(img) != null) return run()
        if (Dungeon.rewardSheet(img) && !closeSheet()) {
            if (!on()) return Outcome.STOPPED
            carried = false
            keepNow("ex_sheet_stays")
            return Outcome.parked("The Reward sheet over the Missions window does not close.")
        }
        val out = work(img)
        if (!whole || out.result != Result.DONE) return out
        return try { home() } finally { overlayBack() }.also { carried = it.result == Result.STOPPED }
    }

    // ------------------------------------------------------------------------
    // The pass
    // ------------------------------------------------------------------------
    private fun begin() {
        claims = 0
        reentries = 0
        lastYellow = emptyList()
        dead.clear()
        tappedAt = Double.NEGATIVE_INFINITY
        unsheeted = false
        lastCounts = emptyMap()
    }

    /** The count handed to the TODAY card, and the dot back at the player's place, however the pass ended. */
    private fun finish() {
        lastCounts = mapOf("claims" to claims)
        overlayBack()
    }

    private fun overlayBack() {
        if (cleared) cap.overlayBack()
        cleared = false
    }

    /** Home at the end of [run]: the main screen, or a park with the frame kept. */
    private fun home(): Outcome {
        stays.reset()
        if (goHome(gated = true)) {
            log("ex missions: back on the main screen")
            return Outcome.DONE
        }
        if (!on()) return Outcome.STOPPED
        // One of the game's own windows over the way home is the director's
        // ([Stays.over], PLAN_RELEASE_1_3.md B60): the claims are made, and no
        // park says otherwise.
        if (stays.met != null) return Outcome.DONE
        keepNow("no_way_home")
        return Outcome.parked("The Missions window did not close, and I found no way home from it.")
    }

    // ------------------------------------------------------------------------
    // The way in (the plan's 3.2, points 1 to 3)
    // ------------------------------------------------------------------------
    private enum class Entry { IN, STOPPED, NOT_MAIN, NO_TILE, NO_WINDOW, NO_TAB }

    /**
     * From the main screen to the EX tab: the tile read on two frames
     * running and tapped, the window on two frames, EX Missions tapped, the
     * EX tab on two frames. [start] is the pass's own beginning, where a
     * screen that is not the main one is not waited for long; after a jump
     * the main screen is waited for [MAIN_WAIT], its Stage Failed banner
     * tapped away first.
     */
    private fun enter(start: Boolean): Entry {
        val tile = when (val m = mainWithTile(if (start) START_WAIT else MAIN_WAIT)) {
            is Main.Tile -> m.target
            Main.Stopped -> return Entry.STOPPED
            Main.NotMain -> return Entry.NOT_MAIN
            Main.NoTile -> {
                log("ex missions: the Missions tile is not read on this frame -- nothing opened")
                keepNow("ex_no_tile")
                return Entry.NO_TILE
            }
        }
        if (!on()) return Entry.STOPPED
        log("ex missions: opening Missions")
        tap(tile)
        val w = waitTwice(OPEN_WAIT) { Missions.window(it) }
        if (w == null) {
            log("ex missions: the Missions window did not open")
            keepNow("missions_no_window")
            return Entry.NO_WINDOW
        }
        return toExTab(w)
    }

    /** From the Missions window [w] (read on the frame just before) to its EX tab. */
    private fun toExTab(w: Missions.MissionsWindow): Entry {
        if (w.tab != Missions.EX) {
            if (!on()) return Entry.STOPPED
            log("ex missions: EX Missions")
            tap(w.exButton)
        }
        if (waitTwice(EX_WAIT) { Missions.exWindow(it) } == null) {
            log("ex missions: the EX Missions tab did not open")
            keepNow("ex_no_tab")
            return Entry.NO_TAB
        }
        return Entry.IN
    }

    private sealed class Main {
        class Tile(val target: Explore.Target) : Main()
        object Stopped : Main()
        object NotMain : Main()
        object NoTile : Main()
    }

    /**
     * The plain main screen with its tile read on two frames running, within
     * [wait]. The overlay goes off the tile's rows first ([clearTile]). A
     * Stage Failed banner is tapped away at the neutral spot, at most
     * [NEUTRAL_TRIES] times: any tap takes it away, and a tap anywhere else
     * could land on the Growth Guide's buttons.
     */
    private fun mainWithTile(wait: Double): Main {
        if (!cleared) {
            clearTile()
            sleep(PAUSE * patience)
        }
        var main = false
        var seen = false
        var banner = 0
        val begin = now()
        while (true) {
            val img = grab()
            try {
                if (Dungeon.stageFailed(img)) {
                    main = true
                    seen = false
                    if (banner < NEUTRAL_TRIES) {
                        if (!on()) return Main.Stopped
                        log("ex missions: the Stage Failed banner is up -- tapping it away")
                        tap(NEUTRAL)
                        banner += 1
                    }
                } else {
                    if (Dungeon.autoButton(img) != null) main = true
                    val tile = Missions.tile(img)
                    if (tile != null && seen) return Main.Tile(tile)
                    seen = tile != null
                }
            } finally {
                img.release()
            }
            if (now() - begin >= wait) break
            sleep(BEAT * patience)
        }
        return if (main) Main.NoTile else Main.NotMain
    }

    /**
     * The dot and its plate off the tile's rows ([Missions.TILE_ROWS], the
     * top of the HUD's right-hand column), in the HUD's fractions the
     * overlay speaks (Dungeon.bottomFy): at the default place the plate lies
     * on the clip on every portrait format and the tile does not read
     * (Missions.tile, the plan's 10.4). Put back in the pass's finally.
     */
    private fun clearTile() {
        val r = rect ?: return
        cap.overlayClear(Dungeon.bottomFy(Missions.TILE_ROWS[0], Missions.HUD_TOP, room, r.gh),
                         Dungeon.bottomFy(Missions.TILE_ROWS[1], Missions.HUD_TOP, room, r.gh))
        cleared = true
    }

    // ------------------------------------------------------------------------
    // The claims (the plan's 3.3, and 10.5)
    // ------------------------------------------------------------------------
    private enum class End { CLEAN, STOPPED, AWAY }

    /**
     * On the EX tab: row 2 until it is not yellow, then row 1, and the pair
     * again for as long as a yellow Claim is left behind a round -- the
     * fights go on behind the window, and row 2 turns yellow again while row
     * 1 is being claimed. Every round taps only what reads yellow, and every
     * tap either claims or, twice without effect, takes its row out of the
     * pass ([dead]), so no round can come round without a claim for ever.
     */
    private fun claimAll(): End {
        var round = 0
        var idle = 0
        while (true) {
            round += 1
            val before = claims
            var i = 0
            val rows = listOf(Row.LIST, Row.HEADER)
            while (i < rows.size) {
                val row = rows[i]
                if (row in dead) {
                    i += 1
                    continue
                }
                when (claimRow(row)) {
                    RowEnd.CLEAN -> i += 1
                    RowEnd.NO_EFFECT -> {
                        dead += row
                        i += 1
                    }
                    RowEnd.STOPPED -> return End.STOPPED
                    RowEnd.AWAY -> return End.AWAY
                    RowEnd.JUMPED -> when (reEnter()) {
                        Entry.IN -> {}  // the same row again
                        Entry.STOPPED -> return End.STOPPED
                        else -> return End.AWAY
                    }
                }
            }
            val left = lastYellow.filter { rowOf(it) !in dead }
            // A yellow that stands on the last frame of two rounds running and
            // was never there to be tapped comes and goes by itself; it is
            // not chased a third time.
            idle = if (claims == before) idle + 1 else 0
            if (left.isEmpty() || idle >= 2) {
                log(when {
                    dead.isNotEmpty() || left.isNotEmpty() -> "ex missions: done with $claims claimed; not clean"
                    claims == 0 -> "ex missions: nothing to claim"
                    else -> "ex missions: the window is clean ($claims claimed)"
                })
                return End.CLEAN
            }
            log("ex missions: ${rowOf(left.first()).label} is yellow again -- round ${round + 1}")
        }
    }

    private fun rowOf(c: Missions.ExClaim) = if (c.row == 1) Row.HEADER else Row.LIST

    /** The Claim to tap for [row] among [all], or null: row 1 the header's, row 2 the topmost of the list's. */
    private fun pick(all: List<Missions.ExClaim>, row: Row): Missions.ExClaim? = when (row) {
        Row.HEADER -> all.firstOrNull { it.row == 1 }
        Row.LIST -> all.filter { it.row >= 2 }.minByOrNull { it.fy }
    }

    private enum class RowEnd { CLEAN, NO_EFFECT, STOPPED, JUMPED, AWAY }
    private enum class Where { SHEET, JUMPED, OTHER_TAB, UNKNOWN }

    /** What stands where the EX tab is not: the sheet, the main screen (a jump), another tab, or nothing known. */
    private fun where(img: Mat): Where {
        if (Dungeon.rewardSheet(img)) return Where.SHEET
        if (Dungeon.stageFailed(img) || Dungeon.autoButton(img) != null) return Where.JUMPED
        val w = Missions.window(img)
        // The EX tab lit and its list not drawn yet is still on its way.
        if (w != null && w.tab != Missions.EX) return Where.OTHER_TAB
        return Where.UNKNOWN
    }

    /**
     * One row, for as long as it is yellow (3.3). Before every tap a fresh
     * frame on which [Missions.exWindow] answers and the row's Claim reads
     * yellow; two frames running without that yellow end the row. After the
     * tap, [afterClaim]. No brake: the player's question 11.
     */
    private fun claimRow(row: Row): RowEnd {
        var notYellow = 0
        var noEffect = 0
        var awaySince = Double.NaN
        while (true) {
            if (!on()) return RowEnd.STOPPED
            val img = grab()
            var claim: Missions.ExClaim? = null
            try {
                val ex = Missions.exWindow(img)
                if (ex == null) {
                    when (where(img)) {
                        Where.SHEET -> {
                            // One the last Claim raised after its row had
                            // already changed ([afterClaim]): closed, not
                            // counted again -- and the witness that it was one.
                            awaySince = Double.NaN
                            unsheeted = false
                            if (!on()) return RowEnd.STOPPED
                            if (!closeSheet()) {
                                if (!on()) return RowEnd.STOPPED
                                log("ex missions: the Reward sheet does not close")
                                keepNow("ex_sheet_stays")
                                return RowEnd.AWAY
                            }
                            continue
                        }
                        Where.JUMPED -> {
                            // The main screen here, at a look of the row's own
                            // and not after a tap ([afterClaim] meets the jump
                            // of a tap), is the task's jump only in one case: a
                            // claim counted by its row's colour alone, with no
                            // sheet, moments before -- the row's blue arrow
                            // after all, on a frame that had read yellow; its
                            // window closes 1.2 to 2.0 s after the tap. That
                            // claim is taken back, and the way in walked again.
                            // Any other main screen is the window closed by
                            // somebody else -- the player, in the semi-automatic
                            // mode -- and it is not opened again.
                            if (!unsheeted || now() - tappedAt > MAIN_WAIT) {
                                log("ex missions: the window was closed, not by a tap of mine -- leaving it")
                                return RowEnd.AWAY
                            }
                            unsheeted = false
                            claims -= 1
                            log("ex missions: that was a blue arrow, not a Claim ($claims claimed)")
                            return RowEnd.JUMPED
                        }
                        Where.OTHER_TAB -> {
                            log("ex missions: the window is on another tab now -- leaving it")
                            return RowEnd.AWAY
                        }
                        Where.UNKNOWN -> {
                            // The sheet dimming in or out: waited for, as long as a sheet is.
                            if (awaySince.isNaN()) awaySince = now()
                            if (now() - awaySince >= SHEET_WAIT) {
                                log("ex missions: the EX Missions tab went away")
                                keep(img, "ex_lost")
                                return RowEnd.AWAY
                            }
                        }
                    }
                } else {
                    awaySince = Double.NaN
                    val all = Missions.exClaims(img, ex)
                    lastYellow = all
                    claim = pick(all, row)
                    if (claim == null) {
                        notYellow += 1
                        if (notYellow >= 2) {
                            log("ex missions: ${row.label}: no yellow left")
                            return RowEnd.CLEAN
                        }
                    } else {
                        notYellow = 0
                        unsheeted = false
                        log("ex missions: ${row.label} -- Claim")
                        tap(claim.target)
                        tappedAt = now()
                    }
                }
            } finally {
                img.release()
            }
            if (claim == null) {
                sleep(BEAT * patience)
                continue
            }
            val after = afterClaim(row)
            when (after) {
                After.SHEET, After.CHANGED -> {
                    claims += 1
                    noEffect = 0
                    unsheeted = after == After.CHANGED
                    log("ex missions: claimed ($claims)")
                }
                After.NONE -> {
                    noEffect += 1
                    if (noEffect >= 2) {
                        log("ex missions: ${row.label}: the Claim changed nothing twice -- leaving the row")
                        keepNow("ex_no_effect")
                        return RowEnd.NO_EFFECT
                    }
                    log("ex missions: ${row.label}: the Claim changed nothing -- reading the row again")
                }
                After.JUMPED -> return RowEnd.JUMPED
                After.AWAY -> return RowEnd.AWAY
            }
        }
    }

    private enum class After { SHEET, CHANGED, NONE, JUMPED, AWAY }

    /**
     * After a tap on [row]'s Claim, until [SHEET_WAIT]: the Reward sheet,
     * closed by "Tap to close" -- or the row's Claim not yellow on two frames
     * running (a claim without a sheet: the button changed; the list is
     * sorted anew under the incoming sheet, so this often comes first and
     * the sheet after it). Either is a claim. Neither is a tap without
     * effect. A frame of the main screen is the jump; another tab lit is the
     * player's.
     */
    private fun afterClaim(row: Row): After {
        var notYellow = 0
        val start = now()
        while (now() - start < SHEET_WAIT) {
            sleep(BEAT * patience)
            val img = grab()
            try {
                val ex = Missions.exWindow(img)
                if (ex != null) {
                    if (pick(Missions.exClaims(img, ex), row) == null) {
                        notYellow += 1
                        if (notYellow >= 2) return After.CHANGED
                    } else {
                        notYellow = 0
                    }
                    continue
                }
                notYellow = 0
                when (where(img)) {
                    Where.SHEET -> {
                        // The witness. With the switch gone off it is left
                        // standing -- no tap then -- and the row's loop stops
                        // at its next look.
                        if (on()) closeSheet()
                        return After.SHEET
                    }
                    Where.JUMPED -> return After.JUMPED
                    Where.OTHER_TAB -> {
                        log("ex missions: the window is on another tab now -- leaving it")
                        return After.AWAY
                    }
                    Where.UNKNOWN -> {}
                }
            } finally {
                img.release()
            }
        }
        return After.NONE
    }

    /**
     * "Tap to close" on the sheet read on the frame just before, and the
     * sheet waited out. A second tap only once [SHEET_GONE] has passed with
     * the sheet still there: a tap that comes after the sheet has gone lands
     * on the window under it, and the window's own dimmed field closes it.
     */
    private fun closeSheet(): Boolean {
        for (i in 0 until SHEET_TAPS) {
            if (!on()) return false
            tap(SHEET_CLOSE)
            val start = now()
            while (now() - start < SHEET_GONE) {
                sleep(BEAT * patience)
                val img = grab()
                val still = try { Dungeon.rewardSheet(img) } finally { img.release() }
                if (!still) return true
            }
        }
        return false
    }

    /**
     * After a jump to the main screen: the way in again (3.4), at most
     * [RE_ENTER] times a pass, and then the pass ends with what it has,
     * the frame kept and no park -- the player: "not bad if you tapped too
     * often".
     */
    private fun reEnter(): Entry {
        if (reentries >= RE_ENTER) {
            log("ex missions: thrown out of the window ${reentries + 1} times -- ending with $claims claimed")
            keepNow("ex_reentered")
            return Entry.NO_TAB
        }
        reentries += 1
        log("ex missions: the game went to the main screen -- opening Missions again ($reentries/$RE_ENTER)")
        return enter(start = false)
    }

    // ------------------------------------------------------------------------
    // Home (the plan's 3.4 and 10.1)
    // ------------------------------------------------------------------------
    /**
     * Back to the plain main screen: the window closed by a tap in the dimmed
     * field above it ([Missions.CLOSE_SPOT]), a sheet still up closed by its
     * words, a Stage Failed banner behind the window taken away at the
     * neutral spot, and the globe as the last hand -- each only where it
     * reads on the frame just before, each a few times at most. True once
     * the auto button is crisp.
     */
    private fun goHome(gated: Boolean): Boolean {
        var closes = 0
        var sheets = 0
        var banners = 0
        var globes = 0
        val start = now()
        while (now() - start < HOME_WAIT) {
            if (gated && !on()) return false
            val img = grab()
            try {
                if (stays.over(img)) return false
                val window = Missions.window(img)
                val globe = Dungeon.homeButton(img)
                when {
                    Dungeon.stageFailed(img) -> if (banners < NEUTRAL_TRIES) {
                        log("ex missions: the Stage Failed banner is up -- tapping it away")
                        tap(NEUTRAL)
                        banners += 1
                    }
                    Dungeon.autoButton(img) != null -> return true
                    Dungeon.rewardSheet(img) -> if (sheets < SHEET_TAPS) {
                        tap(SHEET_CLOSE)
                        sheets += 1
                    }
                    window != null -> if (closes < CLOSE_TRIES) {
                        log("ex missions: closing")
                        tap(Missions.CLOSE_SPOT)
                        closes += 1
                    } else {
                        return false
                    }
                    globe != null -> if (globes < HOME_PRESSES) {
                        log("ex missions: pressing the home button")
                        tap(Explore.Target(globe.fx, globe.fy))
                        globes += 1
                    }
                }
            } finally {
                img.release()
            }
            sleep(PAUSE * patience)
        }
        return false
    }

    // ------------------------------------------------------------------------
    // Frames and taps
    // ------------------------------------------------------------------------
    private fun grab(): Mat = cap.grab().also { rect = Dungeon.gameRect(it); room = Dungeon.headroom(it) }

    /** [read] answering on two fresh frames running within [wait]; the second answer, or null. */
    private fun <T> waitTwice(wait: Double, read: (Mat) -> T?): T? {
        var before = false
        val start = now()
        while (now() - start < wait) {
            sleep(BEAT * patience)
            val img = grab()
            try {
                val v = read(img)
                if (v != null && before) return v
                before = v != null
            } finally {
                img.release()
            }
        }
        return null
    }

    private fun keepNow(tag: String) {
        val img = try { cap.grab() } catch (e: CaptureError) { return }
        try {
            keep(img, tag)
        } finally {
            img.release()
        }
    }

    /** A tap in the rectangle [target] was read in (its anchor), on the last frame read. */
    private fun tap(target: Explore.Target) {
        val r = rect ?: return
        val y0 = r.y0 - Py.roundInt(room * target.anchor.share)
        cap.tap(Py.roundInt(r.x0 + target.fx * r.gw), Py.roundInt(y0 + target.fy * r.gh))
    }

    companion object {
        const val KEY = "exmissions"

        /**
         * Up to this many ways back in after a jump to the main screen, per
         * pass (the player's answer to question 8: "normally you should not
         * need them").
         */
        const val RE_ENTER = 5

        /** Between two looks: the screenshot floor is 350 ms (notes/director.md, "`takeScreenshot` has a floor"). */
        const val BEAT = 0.5
        /** After a tap that moves a screen, or the overlay. */
        const val PAUSE = 1.0

        // The waits, each five times the longest EX1 measured (4.1 point 11;
        // a screencap took 0.35 to 0.85 s there, so each number is a window).
        /** The tile to the whole window: 0.74 to 1.61 s. */
        const val OPEN_WAIT = 8.0
        /** "EX Missions" to the EX tab: 0.52 to 2.32 s. */
        const val EX_WAIT = 12.0
        /** A Claim to the Reward sheet read: 0.92 to 1.56 s, the latest frame without it at 1.67 s. */
        const val SHEET_WAIT = 8.5
        /** A blue arrow to the main screen: 1.2 to 2.0 s. After a jump, the main screen is waited for this long. */
        const val MAIN_WAIT = 10.0
        /**
         * The start from the plain main screen: the director or the chain has
         * just read it there, so a few looks -- two frames running with the
         * tile, as PresetSkill.START_ROUNDS has it for the same reason. As
         * long as every way in waits for the main screen (MainScreen.WAIT,
         * 3 s until 2026-10-03): the tile is read only where the auto button
         * is, and the game draws over that by itself for up to 1.7 s at a time
         * (K2 of PLAN_ABSCHLUSS_1_3.md).
         */
        const val START_WAIT = MainScreen.WAIT
        /**
         * "Tap to close" to the EX tab again: 0.32 to 0.57 s, once 1.09 s. A
         * second tap on a sheet only after this, so that it cannot land on
         * the window the sheet has uncovered ([closeSheet]).
         */
        const val SHEET_GONE = 2.5
        const val SHEET_TAPS = 3
        /** The dimmed field to the main screen: within 0.66 s. */
        const val CLOSE_TRIES = 3
        /** The way home, all hands together. */
        const val HOME_WAIT = 12.0
        const val NEUTRAL_TRIES = 3
        const val HOME_PRESSES = DungeonSkill.HOME_PRESSES_MAX

        /**
         * A work whose row ended on a Claim that twice changed nothing leaves
         * the EX tab to the player this long ([seesWork]): the director hands
         * over whatever reads yellow, and without it the same two taps and a
         * kept frame would come every few seconds. The ten minutes IdleSkill
         * gives a visit that did not get in (IdleSkill.RETRY). The page's
         * button and the chain are not held.
         */
        const val NO_EFFECT_HOLD = 10 * 60.0

        /** "Tap to close" on the Reward sheet, where IdleSkill.settle taps it. */
        val SHEET_CLOSE = Explore.Target((Dungeon.SHEET_CLOSE_LINE[0] + Dungeon.SHEET_CLOSE_LINE[1]) / 2.0,
                                         (Dungeon.SHEET_CLOSE_LINE[2] + Dungeon.SHEET_CLOSE_LINE[3]) / 2.0,
                                         Dungeon.POPUP)

        /** The director's spot that opens nothing, in its rectangle: what takes the Stage Failed banner away. */
        val NEUTRAL = Explore.Target(Director.NEUTRAL_TAP_FX, Director.NEUTRAL_TAP_FY)
    }
}
