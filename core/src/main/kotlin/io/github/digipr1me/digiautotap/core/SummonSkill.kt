package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * The Summons skill under the director: `summon.py`'s SummonBot, cut at the
 * seam of PLAN_ANDROID_3_DIRECTOR.md 3.2 and carried over loop for loop.
 *
 *   run   = summon.py:1586  SummonBot.run -- `_spend`, `leave_summons` in a finally
 *   work  = summon.py:1605  `_spend` from the line after `open_summons`
 *           (summon.py:1118) has answered True
 *
 * [work] is given a frame the director has classified as [Director.SUMMON]:
 * the player opened Special Summon themselves, and the skill spends the
 * enabled modes and leaves them standing there. [run] is the whole walk --
 * the Summon icon, General, the modes, and out again -- which is what the
 * chain runs today.
 *
 * The readers are [Summon] (P3); nothing is read here. What is here is the
 * state machine, and its two hard-won rules:
 *
 *  - **Both ends of a mode, never one.** The game says "that draw is not
 *    affordable" twice: by turning the **price above the button red**, one
 *    frame before any tap goes out, and by putting up its own
 *    **"Insufficient <mode> Summon Tickets"** dialog once a tap has gone out
 *    anyway. They fail at different things -- the dialog is only recognised
 *    through the dimmed yellow button behind it, and a loading spinner over
 *    that button drops its fill from 0.96 to 0.73, which is how a live run
 *    hurry-tapped the dialog away and raised it again for as long as anybody
 *    watched (debug-queue/ticket-rot-asd.png; the laboratory's notes, "One
 *    way of asking is no way of asking"). So both, and neither is asked to be
 *    the only one.
 *  - **The stop is checked beside the pause.** On the PC `wait_while_paused`
 *    answers the pause question alone and says "go on" the moment the skill
 *    is not paused; this skill checked nothing else, and its Stop button and
 *    F8 therefore did nothing at all (the laboratory's notes,
 *    "`wait_while_paused` does not answer 'was I stopped'"). Here the two are
 *    one switch -- [MainSwitch] is the director's only stop -- and [goOn] is
 *    the one gate that reads it, between two actions and never inside one.
 *
 * Nothing from `android.*`: a [Capture], the settings, a log, a place to keep
 * a frame, a clock and a sleep, so that SummonSkillTest can drive it with
 * painted frames the way test_summon_flow.py drives the Python one.
 */
class SummonSkill(
    private val cap: Capture,
    /** The five keys of the Summons page, read afresh wherever they are asked. */
    private val settings: () -> Settings,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** Keep a frame worth looking at afterwards. The app writes files; core cannot. */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    /** The main switch: the pause and the stop in one, as the phone has it. */
    private val on: () -> Boolean = { MainSwitch.on },
    /**
     * How long this machine is given to settle, `summon.py`'s `patience`
     * (1.5 on the PC). Not a settings key: it is a property of the emulator
     * or phone, not a decision of the player's. The one pause it does not
     * scale is [tapEvery], for the reason SPAM_TAP_EVERY gives.
     */
    private val patience: Double = PATIENCE,
    private val tapEvery: Double = SPAM_TAP_EVERY,
    private val frozenSeconds: Double = FROZEN_SECONDS,
    /** Seconds, monotonic. Injected so that the flow test can move it. */
    private val now: () -> Double = { System.nanoTime() / 1e9 },
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
) : Skill {

    override val key = "summon"
    override val name = "Special Summon"

    /**
     * The Summons page, `app.py`'s `_start_summon` (PLAN_ANDROID_3_DIRECTOR.md
     * 5.3): `summon_skill`, `summon_support`, `summon_crest`, `summon_max`
     * (0 = every draw the tickets allow). [watchAdsFirst] was `summon_ads`
     * until 2026-09-24, always on from 2026-09-26, the page's own switch
     * from 2026-09-30, `summon_free_ads`, off by default
     * (Stored.SUMMON_ADS_KEY), and since 2026-10-03 that pick with the Ad
     * Skip Pass (Stored.summon, [FreeAds]): View Ads is tapped only where the
     * draw comes with the tap.
     */
    data class Settings(
        val skill: Boolean = true,
        val support: Boolean = true,
        val crest: Boolean = true,
        val watchAdsFirst: Boolean = true,
        val maxPerMode: Int = 0,
    ) {
        fun enabled(mode: Int): Boolean = when (mode) {
            SKILL -> skill
            SUPPORT -> support
            else -> crest
        }

        /** `chain.summon_has_mode` (chain.py:100), which is [Chain.summonHasMode]. */
        fun anyMode(): Boolean = Chain.summonHasMode(listOf(skill, support, crest))
    }

    // -- what the run did, as SummonBot.stats ---------------------------------
    var summons = 0
        private set
    var adsWatched = 0
        private set
    var modesDone = 0
        private set
    var modesSkipped = 0
        private set
    /**
     * Why the ad phase ended in a park -- the game asked for an ad where the
     * Ad Skip Pass was taken for granted ([FreeAds.PARK]) -- or null.
     */
    var adPark: String? = null
        private set

    /**
     * What this run counted, for the TODAY card ([SkillStats], [Counted]).
     * The names are SkillStats.SHOWN's, not this class's.
     *
     * [modesDone] is not among them since 2026-09-22. "modes finished" said
     * nothing to the player: a mode is this skill's own way through the
     * tabs, not a thing the day handed anybody. It is still counted, still
     * in the log's summary, and still what [modesSkipped] is read against.
     */
    val lastCounts: Map<String, Int>
        get() = mapOf("summons" to summons, "ads" to adsWatched)

    /** How many frames have been kept this run; `_dumps`. */
    private var dumps = 0
    /** Said once, however many nested loops notice it on the way out. */
    private var abortedSaid = false
    /**
     * Said once while the player stands on a Special Summon tab that is not
     * General, and cleared the moment General is back ([seesWork]). The
     * director asks that question about once a second all the while the
     * player is looking at the page, and a reason repeated once a second is
     * not a reason.
     */
    private var notGeneralSaid = false
    /**
     * The reference frame clicks go out in. `DungeonBot` measures it once at
     * construction and remembers it; here every grab refreshes it, which is
     * the same rectangle from the same source and one thing fewer to go
     * stale.
     */
    private var rect = Dungeon.GameRect(0, 0, 1080, 1920)
    /** The headroom of the frame [rect] was taken off (Dungeon.CanvasFrame). */
    private var room = 0

    // ------------------------------------------------------------------------
    // The seam
    // ------------------------------------------------------------------------

    /**
     * Only the Special Summon screen itself. The screen with a dialog over it
     * ([Director.SUMMON_DIALOG]) and the out-of-tickets dialog
     * ([Director.NO_TICKETS]) are not places work begins: a dialog belongs to
     * whoever opened it, and this skill closes only the one it raised itself,
     * inside a mode.
     */
    override fun worksOn(screen: String): Boolean = screen == Director.SUMMON

    /** `chain.summon_has_mode(enabled)`: asked fresh, never remembered. */
    override fun hasBudget(): Boolean = settings().anyMode()

    /**
     * Is the General page in front? The one question asked before anything
     * else here, and a frame is what answers it.
     *
     * Special Summon is four tabs and only the first is this skill's.
     * General holds the three modes it knows -- Skill Card, Support Digimon,
     * Crest, paid for in summon tickets -- while Buddy, SP Support and
     * Overdrive hold draws paid for in the game's bought gems. The player's
     * rule of 2026-09-22: nothing on those three is ever tapped, not even
     * the tab that would lead back to General, and the skill does not begin
     * until General is up.
     *
     * [Summon.modeDots] is what tells them apart, and it is the only reader
     * that does. Measured on four frames taken off the phone that day
     * (corpus/summon/tab_*.png): General answers 1, Buddy, SP Support and
     * Overdrive all answer null, because the three evenly spaced dots of the
     * same size are the mode carousel's and each of the other tabs draws a
     * single dot under its one banner. [Summon.generalTab] cannot stand in
     * for it -- it finds the General tab on the Buddy page just as readily
     * (fx 0.163 there against 0.467 on General) and finds nothing at all on
     * the other two.
     *
     * What the same measurement says about the cost of getting this wrong:
     * [Summon.summonButton] finds a live yellow button on all three foreign
     * tabs and [Summon.priceIsRed] answers false over it -- on SP Support
     * that button is a 10x draw for 3,000 bought gems against a purse of
     * 2,500. The brake that ends a mode before an unaffordable draw does not
     * fire there. Until that day nothing was tapped on those tabs only
     * because [gotoMode] happened to refuse first; this is the same refusal,
     * made a rule and made cheap.
     */
    private fun onGeneral(img: Mat): Boolean = Summon.modeDots(img) != null

    /**
     * False on any Special Summon tab but General, with the sentence said
     * once ([Skill.seesWork]): the director then stands still, hands nothing
     * over, keeps no frame, and looks again next round. Null on General,
     * where the two rules this would replace -- one work per visit, and
     * [hasBudget] -- are the right ones.
     */
    override fun seesWork(screen: String, img: Mat): Boolean? {
        if (onGeneral(img)) {
            notGeneralSaid = false
            return null
        }
        if (!notGeneralSaid) {
            notGeneralSaid = true
            log("$name: $NOT_GENERAL")
        }
        return false
    }

    /**
     * `_spend` on the screen the player opened: every enabled mode, and the
     * Special Summon screen left standing where it was found.
     *
     * [seesWork] has already refused every tab but General by the time the
     * director gets here, so the park below is the belt to those braces --
     * and the one thing still standing where this is called without the
     * director in front of it.
     */
    override fun work(img: Mat): Outcome = guarded {
        fresh()
        rect = Dungeon.gameRect(img)
        room = Dungeon.headroom(img)
        if (!onGeneral(img)) return@guarded Outcome.parked(PARK_NOT_GENERAL)
        spend()
    }

    /**
     * The chain's own step: into General ([walkIn]), the modes, and out to
     * the main screen again.
     *
     * The walk in was gone from 2026-09-22 to 2026-09-23 -- the icon opens
     * whichever tab the game pleases, twice measured as Buddy, and the old
     * walk's next tap landed on Buddy's tab row -- and the step did nothing
     * in the fully automatic mode at all. It is back under the player's rule
     * of 2026-09-23: the icon, and then **General and nothing else**, and
     * only where General is in view ([walkIn]). Where it is not, the step is
     * [Result.RETIRED] with the sentence: the chain says why once and goes on
     * to the next. Parking would have been the wrong word for it -- a parked
     * director stands still for every step, not only this one.
     *
     * The walk back out stays in a finally, and only around the spending:
     * [walkIn] leaves by itself when it does not arrive, and [leaveSummons]
     * taps General's X until the main screen is back.
     */
    override fun run(): Outcome = guarded {
        fresh()
        if (!look { onGeneral(it) }) {
            val why = walkIn()
            if (!on()) return@guarded walkStopped()
            if (why != null) return@guarded Outcome.retired(why)
        }
        try {
            spend()
        } finally {
            leaveSummons()
        }
    }

    // ------------------------------------------------------------------------
    // After a pause (PLAN_RELEASE_1_3.md B4, the player's rule of 2026-09-30)
    // ------------------------------------------------------------------------
    /** The last pass was stopped by the main switch and has not been gone on with or begun afresh since. */
    internal var carried = false
        private set
    /** The modes this pass finished, not drawn again when it goes on. */
    private val doneModes = HashSet<Int>()
    /** Draws per mode in this pass, against the page's limit ([Settings.maxPerMode]) across a pause. */
    private val drawnOn = HashMap<Int, Int>()
    /** Draws of the mode [spam] is on, from before a pause. */
    private var drawnBefore = 0
    /** The switch stopped the pass inside a draw: the game may still be showing it. */
    private var midDraw = false
    /** Every way home asks the switch first ([Stays]). */
    private val stays = Stays(on) { log(it) }

    /**
     * The switch stopped the pass in its walk in ([walkIn]), after the icon
     * and before General stood on two frames: the page the icon opens is the
     * pass's own, and it goes on there ([resumesOn], [resume]).
     */
    private var walkingIn = false

    /** A pass begun afresh: nothing carried. */
    private fun fresh() {
        carried = false
        walkingIn = false
        doneModes.clear()
        drawnOn.clear()
        midDraw = false
        stays.reset()
    }

    /**
     * The walk in stopped by the switch: a pass to go on with, from the page
     * its own icon tap opened. Until 2026-10-01 only a pass stopped in its
     * spending was carried, and one stopped a second after the icon had no
     * screen to go on from: live on instance 1 that day the summon page the
     * step itself had opened was parked on (PLAN_RELEASE_1_3.md B71).
     */
    private fun walkStopped(): Outcome {
        carried = true
        walkingIn = true
        return Outcome.STOPPED
    }

    /**
     * Where a pass the switch stopped goes on: the Special Summon page and a
     * dialog of it (the director's `summon`, `summon_dialog` and
     * `no_tickets`), and -- where it stopped inside a draw -- the draw's own
     * screens, which the director does not name (`unknown`). Not a pink
     * prompt: the View Ads question is one of three identical ones, and its
     * OK is never pressed here since 2026-10-03 ([adAsked]).
     */
    override fun resumesOn(screen: String, img: Mat): Boolean {
        if (!carried) return false
        // Stopped in its walk in: the page the icon opened -- General, or a
        // page with General's tab in view, which the walk in taps (the
        // player's rule of 2026-09-23) -- and the screens on the way to it.
        if (walkingIn) return when (screen) {
            Director.SUMMON -> onGeneral(img) || Summon.generalTab(img)?.let { it.fy < TAB_ROW_FY_MAX } == true
            Director.UNKNOWN -> true
            else -> false
        }
        // General's mode screen, once it has been seen in the pause, is the
        // one screen the pass goes on from: a page after it that is not
        // General is a tab the player chose, or a draw of their own -- and
        // on the other three tabs nothing is tapped (the player's rule of
        // 2026-09-22, [onGeneral]).
        if (screen == Director.SUMMON && onGeneral(img)) {
            generalSeen = true
            return true
        }
        if (generalSeen) return false
        return when (screen) {
            // The draw's own screens: a result, the out-of-tickets dialog.
            // Buddy's page is told by its tab row, which a result has not.
            Director.SUMMON -> midDraw && Summon.generalTab(img) == null
            Director.SUMMON_DIALOG, Director.NO_TICKETS -> midDraw
            Director.UNKNOWN -> midDraw
            else -> false
        }
    }

    /** General's mode screen was seen since the pass stopped ([resumesOn]). */
    private var generalSeen = false

    /**
     * The pass the switch stopped, gone on with: the out-of-tickets dialog
     * closed (the mode it ended is done), a draw's animation hurried to its
     * result as [spam] hurries it and stepped back from to the mode screen
     * ([backToMain]); then the modes that are left, the one it stopped in
     * with the draws it had made counted against the page's limit. [whole]
     * goes home after it. From the main screen (a chain step whose claim
     * ended) the walk in is [run]'s.
     */
    override fun resume(img: Mat, whole: Boolean): Outcome {
        if (!carried) return if (whole) run() else work(img)
        return guarded {
            stays.reset()
            rect = Dungeon.gameRect(img)
            room = Dungeon.headroom(img)
            log("going on with the Special Summon pass the main switch stopped")
            val fromMain = Dungeon.autoButton(img) != null
            if ((whole && fromMain) || walkingIn) {
                // From the main screen the walk in is [run]'s; from the page
                // a stopped walk in had opened, its second half ([arrive]).
                val why = if (fromMain) walkIn() else arrive()
                if (!on()) return@guarded walkStopped()
                walkingIn = false
                if (why != null) return@guarded Outcome.retired(why)
            }
            try {
                if (!backToModes()) {
                    if (!on()) return@guarded Outcome.STOPPED
                    return@guarded Outcome.parked(PARK_NOT_GENERAL)
                }
                spend()
            } finally {
                if (whole) leaveSummons()
            }
        }
    }

    /**
     * From wherever the stopped pass left Special Summon to General's mode
     * screen: the out-of-tickets dialog closed, a draw hurried along until its
     * button stands, and the X once from a result screen. True once the mode
     * dots read; nothing is tapped on a tab that is not General.
     */
    private fun backToModes(): Boolean {
        val end = now() + SPAM_TIMEOUT
        while (now() < end) {
            if (!goOn()) return false
            val img = grab()
            try {
                if (onGeneral(img)) return true
                val close = Summon.notEnoughTickets(img)
                if (close != null) {
                    // The game said the mode was over: it is done.
                    modeAt?.let { doneModes += it }
                    closeDialog(close)
                    continue
                }
                val button = Summon.summonButton(img)
                if (button == null) {
                    // The draw still showing: hurried as [spam] hurries it.
                    tap(NEUTRAL_TAP_FX, NEUTRAL_TAP_FY)
                    sleep(0.5 * patience)
                    continue
                }
                // A tab row that is not General's page: another tab, whose
                // X and buttons are the player's (walkIn's rule).
                if (Summon.generalTab(img) != null) return false
                if (!backToMain(img)) return false
            } finally {
                img.release()
            }
        }
        return false
    }

    /** The mode [spend] was on when the switch stopped it, or null. */
    private var modeAt: Int? = null

    /**
     * From the plain main screen into General, or the reason not; null once
     * General is up on two frames running.
     *
     * The player's rule of 2026-09-23: the Summon icon is tapped, and after
     * it nothing but General. Measured on the four tab frames
     * (corpus/summon/tab_*): the tab row is a carousel with the selected tab
     * in the middle, so General is in view from Buddy (at fx 0.163) and not
     * at all from SP Support or Overdrive. [Summon.generalTab] finds exactly
     * that tab and no other -- over the whole oracle it answers off General
     * on 16 frames, fifteen of them at fx 0.162 to 0.168, fy 0.135 to 0.139
     * (Buddy, the entry screen), and one main screen at fy 0.701, which
     * [TAB_ROW_FY_MAX] refuses. Where it finds nothing, nothing is tapped:
     * the page is left with the system's back key, which is not a tap on the
     * game, and the step retires.
     */
    internal fun walkIn(): String? {
        if (!look { Dungeon.autoButton(it) != null }) return RETIRE_NOT_MAIN
        log("opening Special Summon")
        tap(Summon.SUMMON_ICON_FX, Summon.SUMMON_ICON_FY)
        return arrive()
    }

    /**
     * [walkIn] after its icon: General on two frames running, its tab tapped
     * where another page shows it, or the page left by the back key and the
     * reason not. Also where a walk in the switch stopped goes on from.
     */
    private fun arrive(): String? {
        var generalTaps = 0
        var seen = 0
        for (round in 0 until WALK_ROUNDS) {
            sleep(WALK_ROUND * patience)
            if (!on()) return null
            val img = grabOrNull() ?: return RETIRE_NO_GENERAL
            try {
                if (onGeneral(img)) {
                    seen += 1
                    if (seen >= 2) return null
                    continue
                }
                seen = 0
                val tab = Summon.generalTab(img)
                if (tab != null && tab.fy < TAB_ROW_FY_MAX && generalTaps < GENERAL_TAPS_MAX) {
                    log("  another tab is open, tapping General")
                    tap(tab.fx, tab.fy, Summon.PAGE)
                    generalTaps += 1
                }
            } finally {
                img.release()
            }
        }
        grabOrNull()?.let { img -> dump(img, "no_general_in_view"); img.release() }
        leaveByBack()
        return RETIRE_NO_GENERAL
    }

    /**
     * Out of a Special Summon page that is not General without a tap: the
     * system's back key, looked after each time, until the main screen's auto
     * button is back ([BACK_KEYS_MAX] at most -- one more on the main screen
     * would raise the game's exit prompt).
     */
    private fun leaveByBack() {
        for (press in 0 until BACK_KEYS_MAX) {
            if (look { Dungeon.autoButton(it) != null }) return
            if (stays.now()) return
            // A window of the game's own is the director's ([Stays.over], B60).
            if (look { stays.over(it) }) return
            cap.back()
            sleep(WALK_ROUND * patience)
        }
        if (!look { Dungeon.autoButton(it) != null })
            log("  the back key did not bring the main screen back")
    }

    /** Where every way out of [work] and [run] meets a frame that never came. */
    private inline fun guarded(block: () -> Outcome): Outcome {
        abortedSaid = false
        // A pass's numbers are this pass's, as `SummonBot`'s are a run's.
        // They were counted from the moment the skill was built until
        // 2026-09-22 and nothing ever cleared them, while `Counted` reads
        // them at the end of every pass: a second pass of 5 summons after a
        // first of 10 put 15 more on the card, and the day said 25.
        summons = 0
        adsWatched = 0
        modesDone = 0
        modesSkipped = 0
        adPark = null
        return try {
            block()
        } catch (e: CaptureError) {
            Outcome.noFrame(e, "No picture of the screen")
        }
    }

    // ------------------------------------------------------------------------
    // Spending
    // ------------------------------------------------------------------------

    /** `_spend` (summon.py:1605), the seam itself. */
    internal fun spend(): Outcome {
        val out = spendModes()
        // A pass the switch stopped is one to go on with ([resume]); any
        // other end is the pass over.
        carried = out.result == Result.STOPPED
        generalSeen = false
        return out
    }

    private fun spendModes(): Outcome {
        val set = settings()
        midDraw = false
        for (mode in MODE_ORDER) {
            modeAt = mode
            if (!goOn()) return Outcome.STOPPED
            if (!set.enabled(mode)) {
                log("${modeName(mode)}: switched off, skipping")
                continue
            }
            // A mode this pass finished before a pause is not drawn again.
            if (mode in doneModes) continue
            log(modeName(mode))
            if (!gotoMode(mode)) {
                if (!on()) return Outcome.STOPPED
                modesSkipped += 1
                continue
            }
            watchAds(mode)
            // The ad is still in front, and nothing is drawn or tapped behind it.
            adPark?.let { return Outcome.parked(it) }
            if (!on()) return Outcome.STOPPED
            drawnBefore = drawnOn[mode] ?: 0
            val drawn = spam(mode)
            drawnOn[mode] = drawnBefore + drawn
            drawnBefore = 0
            // A mode cut short by the main switch is not a mode done. Nor is
            // there any point stepping back to the mode screen for a run that
            // is over -- the way out walks from wherever this leaves off,
            // result screen included. The pass that goes on after the pause
            // takes the draw up where it stands ([midDraw]).
            if (!on()) {
                midDraw = true
                return Outcome.STOPPED
            }
            modesDone += 1
            doneModes += mode
            // Only where another mode still has to be reached. backToMain
            // exists so that gotoMode has a mode screen to work on; after the
            // last enabled mode there is nothing to go to, and the way out
            // walks from a result screen perfectly well. A tap that buys
            // nothing can still cost something.
            if (MODE_ORDER.any { it > mode && set.enabled(it) && it !in doneModes }) backToMain()
        }
        return Outcome.DONE
    }

    // -- getting in -----------------------------------------------------------
    //
    // There is no getting in any more. `openSummons` stood here until
    // 2026-09-22 -- tap the Summon icon on the main screen, wait for the
    // General tab, tap it, wait for the mode banners -- and it is gone with
    // the player's rule that no tap goes out on any tab but General. The
    // second of its four steps was exactly such a tap: the icon opens
    // whichever tab the game pleases, twice measured as Buddy, and the tab
    // row it aimed at belongs to that page. `summon.py`'s `open_summons`
    // (summon.py:1118) is still the PC's way in; on the phone the way in is
    // the player.
    //
    // Two callers had it and neither is left without one. The chain's step
    // now retires itself with a sentence ([run]); the quest loop reaches a
    // mode through the quest card on the main screen instead
    // (`QuestSkill.enterSummonByCard`), which lands on the mode's own screen
    // and proves it with the same [Summon.modeDots] this skill asks, and
    // parks where that does not work.
    //
    // `_wait_for` went with it, its only caller. What waits here now is
    // [waitButtonBack], which waits for a settled place and not merely for a
    // reader to say yes.

    // -- switching mode -------------------------------------------------------

    /**
     * `goto_mode`: tap the neighbour banner until the dots read [target].
     *
     * A frame without the banners on it ends this, and the mode with it.
     * It used to walk back in instead -- `openSummons` from the main screen
     * -- on the reasoning that one route in is no route in; that route was
     * the Summon icon and then a tap on the tab row of whatever page the
     * icon opened, and it is gone (see "getting in" above). What is left is
     * the honest answer: the banners are not in front, so this is not a
     * screen to tap on, and the mode is skipped.
     */
    internal fun gotoMode(target: Int): Boolean {
        var current = look { Summon.modeDots(it) }
        if (current == null) {
            log("  mode banners not found, cannot navigate")
            return false
        }
        while (current != target) {
            if (!goOn()) return false
            val fx = if (target > current!!) Summon.NEIGHBOUR_FX_RIGHT else Summon.NEIGHBOUR_FX_LEFT
            var moved = false
            for (attempt in 0 until BANNER_TRIES) {
                tap(fx, Summon.BANNER_FY, Summon.PAGE)
                sleep(pauseLong())
                val seen = look { Summon.modeDots(it) }
                if (seen != null && seen != current) {
                    current = seen
                    moved = true
                    break
                }
                log("  tap had no effect, trying again")
            }
            if (!moved) {
                log("  the banner would not switch after $BANNER_TRIES tries, skipping this mode")
                return false
            }
        }
        return true
    }

    // -- getting back to the mode's own screen --------------------------------

    /**
     * Tap the X once, to step from a result screen back to the mode one.
     *
     * The X is found in its own right wherever it can be, and only aimed at by
     * the offset from the summon button where it cannot: measured over every
     * stored frame, a result screen's button puts it four thousandths from the
     * X's own place, a mode screen's button a tenth of the game across and
     * 0.07 above it, in empty sky.
     *
     * And the mode screen may never have been left. A mode that ends on the red
     * price ends before a single tap goes out, so the game is still standing on
     * the banners -- and the X found there is not the step back, it is the one
     * that closes Special Summon altogether. A live run tapped it: Skill Card
     * Summon ended at 0 summons, the game went to the main screen, and the two
     * modes after it were skipped with "mode banners not found". Ask where we
     * are before tapping anything.
     */
    internal fun backToMain(given: Mat? = null): Boolean {
        val img = given ?: grab()
        try {
            if (Summon.modeDots(img) != null) return true
            val x = Summon.exitButton(img)
            if (x != null) tap(x.fx, x.fy) else {
                // Beside the button, so in the button's rectangle: the X of a
                // result screen stands on the button's row.
                val button = Summon.summonButton(img) ?: return false
                val aim = Summon.xBesideButton(button)
                tap(aim.first, aim.second, Summon.PAGE)
            }
        } finally {
            if (given == null) img.release()
        }
        for (round in 0 until BACK_ROUNDS) {
            sleep(BACK_ROUND * patience)
            if (look { Summon.modeDots(it) } != null) return true
        }
        log("  tapping the X did not bring the mode screen back")
        return false
    }

    // -- getting back out -----------------------------------------------------

    /**
     * Tap the X until the plain main screen is back. True if it is.
     *
     * Not gated on [goOn], which says "stopped" and would end it at once --
     * but asked of the switch since 2026-09-30 all the same ([Stays]): the
     * switch is one of the ways a run ends, and after the pause the game
     * stays where it is, with no tap held back and no frame kept for it
     * (PLAN_WORLD_SEARCH_FORMATE.md F24). It is bounded, so Stop still ends
     * the session promptly.
     *
     * The X pops one screen, not all of them: from a result screen it goes
     * back to the mode screen, and only from there to the game. So this taps,
     * looks, and taps again, and what it looks for is `dungeon.auto_button` --
     * independent evidence that the main screen is in front with nothing over
     * it, never "the X is gone now".
     */
    /** The chain's second hand (Skill.leave): [leaveSummons], which is `run`'s own way out. */
    override fun leave(): Boolean = leaveSummons()

    internal fun leaveSummons(): Boolean {
        var taps = 0
        var blind = 0
        for (round in 0 until EXIT_ROUNDS) {
            // With the switch off the game stays where it is ([Stays], F24).
            if (stays.now()) return false
            val img = grabOrNull() ?: return false
            try {
                // The game's own window over the way home -- "Time Sale!" over
                // the Stage Failed banner after the X, live on 2026-10-01
                // (PLAN_RELEASE_1_3.md B72), or its news after the reset (B60)
                // -- is handed back as it stands, nothing tapped ([Stays.over]).
                if (stays.over(img)) return false
                if (Dungeon.autoButton(img) != null) {
                    if (taps > 0) log("back on the main screen")
                    return true
                }
                val close = Summon.notEnoughTickets(img)
                if (close != null) {
                    // The one screen that is reliably in the way here, and the
                    // one the X cannot be found on: the dialog dims the plate
                    // out of the white mask exactly as it dims the summon
                    // button. debug_summon/no_way_back_02.png is a picture of
                    // this branch not existing.
                    log("the out-of-tickets dialog is in the way, closing it")
                    closeDialog(close)
                    continue
                }
                val x = Summon.exitButton(img)
                if (x == null) {
                    // Mid-animation, or a screen this skill did not open.
                    // Either way there is nothing here worth tapping blindly
                    // at -- but a run that sits here has always cost a round of
                    // guessing afterwards, so one picture of it is kept.
                    blind += 1
                    if (blind == EXIT_BLIND_ROUNDS) {
                        log("neither the main screen nor an X to close -- " +
                            "keeping a picture of whatever is in front")
                        dump(img, "nothing_to_tap")
                    }
                    sleep(EXIT_ROUND * patience)
                    continue
                }
                blind = 0
                if (taps >= EXIT_TAPS_MAX) break
                if (taps == 0) log("leaving Special Summon")
                tap(x.fx, x.fy)
                taps += 1
                sleep(EXIT_ROUND * patience)
            } finally {
                img.release()
            }
        }
        if (stays.now()) return false
        log("could not get back to the main screen -- the game is left where it stands")
        grabOrNull()?.let {
            dump(it, "no_way_back")
            it.release()
        }
        return false
    }

    // -- waiting for the button to come back ----------------------------------

    /**
     * The draw that reached the limit, played out: the button goes, the
     * animation is hurried as [spam] hurries it, and the button comes back.
     *
     * [waitButtonBack] alone was not enough. Live on LDPlayer 2026-09-23, a
     * 35x Skill Card Summon at a limit of 1: the frame after the tap still
     * showed the button, twice at the same place, so the wait answered at
     * once -- and the next mode met the reveal screen and said "mode banners
     * not found" for Support and for Crest. So the button has to be seen
     * gone first; where it never goes within [DRAW_START_WAIT], nothing was
     * drawn and there is nothing to wait for.
     */
    private fun finishDraw(hurry: Boolean) {
        val start = now()
        val deadline = start + SPAM_TIMEOUT
        var gone = false
        while (now() < deadline) {
            if (!goOn()) return
            val there = look { Summon.summonButton(it) != null }
            if (!there) {
                gone = true
                if (hurry) tap(NEUTRAL_TAP_FX, NEUTRAL_TAP_FY)
            } else if (gone || now() - start >= DRAW_START_WAIT) {
                break
            }
            sleep(0.5 * patience)
        }
        waitButtonBack(SPAM_TIMEOUT)?.release()
    }

    /**
     * Wait until the summon button is found again, at a settled position. The
     * caller owns the frame that comes back.
     *
     * The ad phase and the limit only -- [spam] does its own looking and waits
     * for nothing. Nothing is tapped while this waits, and that is not caution
     * for its own sake: what is on screen during an ad is somebody else's
     * creative, and a tap into it can follow a link to a store page rather than
     * skip anything. Only the game's own animation is ours to hurry along.
     */
    private fun waitButtonBack(timeout: Double): Mat? {
        val deadline = now() + timeout
        var last: Pair<Double, Double>? = null
        while (now() < deadline) {
            if (!goOn()) return null
            val img = grab()
            val button = Summon.summonButton(img)
            if (button != null) {
                val here = round3(button.fx) to round3(button.fy)
                if (here == last) return img
                last = here
            } else {
                last = null
            }
            img.release()
            sleep(0.5 * patience)
        }
        log("  the summon button did not come back within ${seconds(timeout)}")
        val img = grabOrNull() ?: return null
        dump(img, "button_timeout")
        img.release()
        return null
    }

    // -- watching the free ads ------------------------------------------------

    /** `watch_ads`: the free rounds first, where the mode has any. */
    internal fun watchAds(mode: Int) {
        if (!settings().watchAdsFirst || MODE_HAS_ADS[mode] != true) return
        var remaining = look { Summon.adsLeft(it) }
        // Two sentences since 2026-10-02: the one line said both, and on the
        // Poco's report of that day (K7 of PLAN_ABSCHLUSS_1_3.md) every visit
        // after the quest loop had watched the day's two read 0.
        if (remaining == null) {
            log("  the ads counter could not be read")
            return
        }
        if (remaining == 0) {
            log("  no free ads left today")
            return
        }
        log("  $remaining free ad${plural(remaining)} to take with the pass first")
        while (remaining!! > 0) {
            if (!goOn()) return
            val button = look { Summon.viewAdsButton(it) }
            if (button == null) {
                log("  the View Ads button is gone, stopping the ad phase")
                return
            }
            tap(button.fx, button.fy, Summon.PAGE)
            // The pass taken for granted: the draw comes with the tap. Where
            // the game asks for an ad all the same, the switch is on and the
            // pass is not there -- nothing is tapped on the question or in
            // the ad, no back key is sent, and the phase parks where it
            // stands (2026-10-03, PLAN_ABSCHLUSS_1_3.md 3.6).
            if (adAsked()) {
                parkOnAd()
                return
            }
            val settled = waitButtonBack(AD_TIMEOUT)
            if (settled == null) {
                if (Ads.inFront(cap.adHand())) {
                    parkOnAd()
                    return
                }
                log("  stopping the ad phase, the screen did not settle")
                return
            }
            // Never the yellow button here -- it is a live 35x Summon on the
            // result screen the ad just opened, and pressing it would spend 30
            // real tickets on top of a free round.
            backToMain(settled)
            settled.release()
            val left = look { Summon.adsLeft(it) }
            if (left == null || left >= remaining!!) {
                log("  the ad counter did not fall, stopping the ad phase")
                return
            }
            adsWatched += 1
            remaining = left
        }
        log("  ads done")
    }

    /** The game wants an ad watched where the pass was taken for granted: said, and the phase's park. */
    private fun parkOnAd() {
        log("  the game asks for an ad although the Ad Skip Pass is switched on -- I never touch an ad")
        adPark = FreeAds.PARK
    }

    /**
     * Did the game ask for an ad after View Ads? Its question "You can view
     * ads to perform a Summon. View ads?", Cancel in pink and OK in blue,
     * measured live on LDPlayer instance 1 on 2026-09-28 (PLAN_WERBUNG.md
     * 7h, 9.4): on an account without the pass View Ads opens it and no ad,
     * 0.30 s after the tap on the first of six frames and on all six.
     * `Dungeon.recognise` reads it as the prompt with the pink Cancel
     * (`exit_kind` party, the director's `prompt`); the grey-Cancel prompt
     * is the game's exit and is not this question. Or an ad -- or where one
     * sends the player -- already in front. Either is how a pass taken for
     * granted finds out it is not there, and nothing is tapped on either: the
     * question stands for the player, as the ad does (2026-10-03).
     *
     * Looked for only while nothing else has happened: a frame that is
     * neither the question nor the page as it was before the tap (the View
     * Ads button still lit) -- the draw's animation, which follows the tap
     * with the pass -- ends the looking at once, and so does
     * [AD_CONFIRM_WAIT].
     */
    private fun adAsked(): Boolean {
        val hand = cap.adHand()
        val deadline = now() + AD_CONFIRM_WAIT
        while (true) {
            if (!goOn()) return false
            if (Ads.inFront(hand)) return true
            val (rec, page) = look { Dungeon.recognise(it) to (Summon.viewAdsButton(it) != null) }
            if (rec.state == Dungeon.EXIT && rec.exitKind == "party" && rec.exitOk != null) return true
            if (!page || now() >= deadline) return false
            sleep(0.5 * patience)
        }
    }

    // -- spending the tickets -------------------------------------------------

    /**
     * Sleep out what is left of the tap interval, and no more. The grab is part
     * of the interval rather than something added to it, so the taps keep the
     * pace they were asked for wherever the capture is quick enough, and go as
     * fast as the device allows where it is not.
     */
    private fun nap(started: Double) {
        val left = tapEvery - (now() - started)
        if (left > 0) sleep(left)
    }

    /**
     * Tap Close until the out-of-tickets dialog is gone. True if it is.
     *
     * The proof is the dialog being gone from a later frame, never the tap
     * having been sent. A tap that arrives while the game is still drawing the
     * dialog is eaten, and the answer to a swallowed tap is another tap rather
     * than more patience -- the quest loop's reward window taught that one,
     * where sixteen seconds of waiting bought nothing that a second tap would
     * not have bought in one.
     *
     * Every tap after the first is aimed off the frame that was just looked at,
     * never off the one that aimed the tap before it: the place Close stands in
     * is a card once the dialog has gone, and a card opens a window this skill
     * has no way out of (see [DIALOG_FRAMES]).
     */
    internal fun closeDialog(found: Dungeon.Button): Boolean {
        var close = found
        for (attempt in 0 until CLOSE_TAPS_MAX) {
            tap(close.fx, close.fy)
            for (looks in 0 until CLOSE_LOOKS) {
                nap(now())
                val again = look { Summon.notEnoughTickets(it) } ?: return true
                close = again
            }
        }
        log("  the out-of-tickets dialog would not close")
        val img = grabOrNull() ?: return false
        dump(img, "dialog_stuck")
        img.release()
        return false
    }

    /**
     * Tap the yellow button five times a second until the game says stop.
     * Returns roughly how many draws that was -- taps on the button, over the
     * taps a draw of this mode costs. A count of clicks, not a measurement of
     * the game, and the log says "about" because of it.
     *
     * Nothing is counted off the screen. Two things end a mode, and neither of
     * them is a number this skill worked out for itself: the **red price**,
     * which the game turns the moment the draw costs more than is in the bag
     * and before any tap is spent finding out, and the game's own
     * **"Insufficient <mode> Summon Tickets"** dialog, which it puts up once a
     * tap has gone out anyway. Both, not one -- see the class comment for what
     * happens when only the second one is asked.
     *
     * What that replaced was the ticket badge, read on every frame and trusted
     * to say when a mode was finished. It cost nothing in time but it was a
     * second opinion about a question the game answers itself, and every one of
     * its failure modes -- a stray mark read as a digit, a count read too low,
     * a dialog dimming the badge out of the mask -- ended a mode with tickets
     * still in the bag.
     *
     * Every round still grabs a frame and finds the button on it, so the taps
     * are fast, not blind, and none of them goes to a remembered position. A
     * frame with no button is the draw animation, the rare single-card reveal,
     * or the loading between two screens, and a tap anywhere ends all three.
     */
    internal fun spam(mode: Int): Int {
        val limit = settings().maxPerMode
        // Crest is the one mode whose tap raises a confirmation dialog, so a
        // Crest draw is two taps: one to open the dialog, one on the dialog's
        // own yellow button. It is also why Crest sends no tap at all at a
        // frame without a button -- the dialog takes a moment to be drawn, and
        // a tap into that gap lands beside it and closes it again. That cost
        // three Crest draws in a live run, the count sitting at 388 for three
        // rounds while every round looked like a click that achieved nothing.
        val perDraw = if (MODE_HAS_CONFIRM[mode] == true) 2 else 1
        val hurry = MODE_HAS_CONFIRM[mode] != true
        var taps = 0
        var dialogs = 0
        var reds = 0
        var goneSince: Double? = null
        var movedAt = now()
        var moved: Mat? = null
        try {
            while (true) {
                if (!goOn()) return taps / perDraw
                val started = now()
                val img = grab()
                var held = false
                try {
                    // The game saying the mode is over in words. Asked before
                    // the button, because the button it would otherwise tap is
                    // behind this dialog and dimmed out of reach anyway -- and
                    // before the red price, because on a frame with this dialog
                    // on it there is no live button to read a price above.
                    //
                    // A frame that showed the dialog is never tapped through,
                    // not even the first one: the tap would land on the
                    // dialog's backdrop and dismiss it, and the Close that
                    // follows would then arrive at a card (see DIALOG_FRAMES).
                    val close = Summon.notEnoughTickets(img)
                    if (close != null) {
                        dialogs += 1
                        if (dialogs < DIALOG_FRAMES) {
                            nap(started)
                            continue
                        }
                        val done = taps / perDraw
                        log("  the game says there are not enough tickets left -- " +
                            "${modeName(mode)} done, about $done summon${plural(done)}")
                        closeDialog(close)
                        return done
                    }
                    dialogs = 0

                    // A picture that has not moved at all while taps keep going
                    // out. Nothing else in this loop would ever notice a game
                    // that has stopped answering, now that no counter is being
                    // watched.
                    if (moved == null || !sameScreen(moved, img)) {
                        movedAt = started
                        moved?.release()
                        moved = img
                        held = true
                    } else if (started - movedAt >= frozenSeconds) {
                        log("  stopping: not one pixel has changed in ${seconds(frozenSeconds)} " +
                            "while the taps kept going out")
                        dump(img, "frozen")
                        return taps / perDraw
                    }

                    val button = Summon.summonButton(img)
                    if (button == null) {
                        dialogs = 0
                        if (goneSince == null) {
                            goneSince = started
                        } else if (started - goneSince!! >= SPAM_TIMEOUT) {
                            log("  stopping: nothing to tap for ${seconds(SPAM_TIMEOUT)}")
                            dump(img, "button_timeout")
                            return taps / perDraw
                        }
                        if (hurry) tap(NEUTRAL_TAP_FX, NEUTRAL_TAP_FY)
                        nap(started)
                        continue
                    }
                    goneSince = null

                    // The game saying the next draw is unaffordable, before a
                    // tap has been spent asking. A frame whose price reads red
                    // is never tapped through, not even the first one: if it
                    // was a misread the frame after it taps as usual, and if it
                    // was not, the tap it saved is the one that would have
                    // raised the dialog (see RED_FRAMES).
                    if (Summon.priceIsRed(img, button)) {
                        reds += 1
                        if (reds >= RED_FRAMES) {
                            val done = taps / perDraw
                            // The price is the yellow button's, and the game
                            // paints the bigger draw yellow on every mode -- 35x
                            // for 30 tickets, 10x for 10 crests -- with the
                            // smaller one blue beside it, which this skill never
                            // taps. So a red price can leave up to 29 tickets in
                            // the bag, and the line says how many, off the same
                            // frame; a counter it cannot read is left out
                            // (notes/summon.md, "The red price is the yellow
                            // button's, and the yellow button is the bigger draw").
                            val left = Summon.ticketCounter(img)
                            log("  the price above the yellow button is red" +
                                (if (left != null) ", $left ticket${plural(left)} left" else "") +
                                " -- ${modeName(mode)} done, about $done summon${plural(done)}" +
                                " (the task taps only the yellow draw)")
                            return done
                        }
                        nap(started)
                        continue
                    }
                    reds = 0

                    // The page's limit counts the draws this mode had before
                    // a pause as well ([drawnBefore]): a pass that goes on is
                    // the same pass, and does not draw the limit twice.
                    if (limit > 0 && drawnBefore + taps / perDraw >= limit) {
                        log("  limit of $limit reached")
                        return taps / perDraw
                    }
                    tap(button.fx, button.fy, Summon.PAGE)
                    taps += 1
                    if (taps % perDraw == 0) summons += 1
                    val done = taps / perDraw
                    if (limit > 0 && drawnBefore + done >= limit) {
                        log("  limit of $limit reached")
                        // Let the draw that was just paid for finish before
                        // handing the screen on. Whoever asked for a limit
                        // asked for whole draws, and the next step should not
                        // meet an animation.
                        finishDraw(hurry)
                        return done
                    }
                    nap(started)
                } finally {
                    if (!held) img.release()
                }
            }
        } finally {
            moved?.release()
        }
    }

    // ------------------------------------------------------------------------
    // The small print
    // ------------------------------------------------------------------------

    /**
     * False when the run must not go on -- the one gate, read between two
     * actions and never inside one.
     *
     * On the PC this is `control.is_set()` **and** `wait_while_paused()`,
     * because the pause and the stop were two things there and asking only the
     * second is what left the Stop button dead (NOTES.md). Here they are one
     * switch, and this is the only place that reads it inside the work.
     */
    private fun goOn(): Boolean {
        if (on()) return true
        if (!abortedSaid) {
            abortedSaid = true
            log("stopped")
        }
        return false
    }

    /** A frame of the screen, and the click rectangle refreshed off it. */
    private fun grab(): Mat {
        val img = cap.grab()
        rect = Dungeon.gameRect(img)
        room = Dungeon.headroom(img)
        return img
    }

    /** The same, for the steps that must not fail on a missing frame. */
    private fun grabOrNull(): Mat? = try {
        grab()
    } catch (e: CaptureError) {
        log("  no picture of the screen: ${e.message}")
        null
    }

    /** One look: grab, ask, release. */
    private inline fun <T> look(ask: (Mat) -> T): T {
        val img = grab()
        try {
            return ask(img)
        } finally {
            img.release()
        }
    }

    /**
     * A tap by fraction of the reference window, as `DungeonBot.tap` sends one.
     * The sentence the PC's tap carries is for its dry-run log; there is no dry
     * run here, and a phone log with five lines a second in it is no log.
     */
    private fun tap(fx: Double, fy: Double, anchor: Dungeon.Anchor = Dungeon.Anchor.BOTTOM) {
        // Not with the main switch off: the service would hold it back, and
        // the step it belongs to ends at its next look ([goOn]).
        if (!on()) return
        // A fraction is a place only in the rectangle it was read in: the
        // page's readers read at Summon.PAGE, so their answers are tapped
        // there, and everything else -- the icon, the X, the dialog -- at the
        // bottom, as it always was. The same place on any display under the
        // canvas ceiling (Dungeon.Anchor).
        val y0 = rect.y0 - Py.roundInt(room * anchor.share)
        cap.tap(Py.roundInt(rect.x0 + fx * rect.gw), Py.roundInt(y0 + fy * rect.gh))
    }

    /**
     * `dungeon._same_screen(before, after, MOVING_SHARE)`: did nothing at all
     * move? The share is `dungeon.MOVING_SHARE`, this question's own number,
     * passed in as the caller's decision (NOTES.md, "A threshold answers one
     * question").
     */
    private fun sameScreen(before: Mat?, after: Mat?): Boolean =
        Dungeon.sameScreen(before, after, MOVING_SHARE)

    private fun dump(img: Mat, tag: String) {
        // Nothing failed where the switch cut a step short (PLAN_RELEASE_1_3.md B4).
        if (!on()) return
        if (dumps >= DUMPS_MAX) return
        dumps += 1
        keep(img, tag)
        log("    kept what it saw, as $tag")
    }

    private fun pauseLong() = PAUSE_LONG * patience

    /** "%.0f s", as the Python log writes a timeout. */
    private fun seconds(value: Double) = "%.0f s".format(value)

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0

    /** "%d summons, %d ads, %d mode(s) done, %d skipped" -- SummonBot.status. */
    fun status(): String =
        "$summons summons, $adsWatched ads, $modesDone mode(s) done, $modesSkipped skipped"

    fun modeName(mode: Int): String = MODE_NAMES[mode] ?: "Summon"

    companion object {
        const val SKILL = 1
        const val SUPPORT = 2
        const val CREST = 3
        val MODE_ORDER = listOf(SKILL, SUPPORT, CREST)
        val MODE_NAMES = mapOf(SKILL to "Skill Card Summon",
                               SUPPORT to "Support Digimon Summon",
                               CREST to "Crest Summon")
        val MODE_HAS_ADS = mapOf(SKILL to true, SUPPORT to true, CREST to false)
        /**
         * Whether a tap on the yellow button raises a confirmation dialog
         * before anything is drawn. Only Crest does, and this table exists to
         * keep the animation-hurrying taps away from it. The price is that a
         * Crest draw's animation is not hurried along: a few seconds each on a
         * few dozen draws, against several hundred draws for Skill and Support
         * where the hurrying stays.
         */
        val MODE_HAS_CONFIRM = mapOf(SKILL to false, SUPPORT to false, CREST to true)

        /**
         * Why the skill is standing still, in three places that want it in
         * three shapes: the log line while the player is looking at a
         * foreign tab, the park [work] would answer with, and the sentence
         * the chain records when the step retires itself. One rule, so one
         * wording, and the player reads the same reason wherever they meet
         * it (see [onGeneral] for the measurement behind it).
         */
        const val NOT_GENERAL =
            "this is not the General page -- Special Summon is only worked there, " +
                "and nothing is tapped on Buddy, SP Support or Overdrive"
        const val PARK_NOT_GENERAL =
            "This is not the General page. Special Summon is only worked there, and nothing " +
                "is tapped on Buddy, SP Support or Overdrive."
        const val RETIRE_NO_GENERAL =
            "The Summon icon opened a Special Summon tab from which General is not in view " +
                "(SP Support or Overdrive), and nothing but General is ever tapped there. " +
                "Open General once yourself and the tickets are spent there."
        const val RETIRE_NOT_MAIN =
            "Special Summon is opened from the plain main screen, and something is in front of it."

        /** How long a paid draw may take to start its animation ([finishDraw]). */
        const val DRAW_START_WAIT = 3.0

        /** How many looks [walkIn] gives the icon and a General tap to land. */
        const val WALK_ROUNDS = 10
        const val WALK_ROUND = 0.7
        /** General is tapped at most this often on the way in: once, and once more if it did not take. */
        const val GENERAL_TAPS_MAX = 2
        /** Back presses to leave a page that is not General; see [leaveByBack]. */
        const val BACK_KEYS_MAX = 2
        /**
         * The tab row's floor. Every General tab [Summon.generalTab] finds in
         * the oracle stands at fy 0.135 to 0.139; its one answer outside the
         * row, on a main screen, at 0.701 (corpus/tall/main_bubble_1080x1920_a).
         */
        const val TAB_ROW_FY_MAX = 0.20

        /**
         * How long the summon button may be gone before that stops meaning "an
         * animation is running": a draw animation, a rare single-card reveal,
         * or (for the ad phase) a whole video.
         */
        const val SPAM_TIMEOUT = 25.0
        const val AD_TIMEOUT = 90.0
        /** The question before an ad stood 0.30 s after the tap ([adAsked]); ten times that for a slow phone. */
        const val AD_CONFIRM_WAIT = 3.0
        /** Where a tap goes on a frame that has no button to aim at. */
        const val NEUTRAL_TAP_FX = 0.5
        const val NEUTRAL_TAP_FY = 0.10

        /**
         * How often a tap goes out while a mode is being spent.
         *
         * The loop does not wait for anything. It looks, taps, and looks again,
         * five times a second, which is how the player plays this screen: the
         * draw animation ends on the first tap that reaches it, so the tap that
         * skips the animation and the tap that opens the next pack are the same
         * tap, and nothing has to decide in advance which one it is sending.
         *
         * It is the interval between the *starts* of two rounds, not a sleep
         * added on top of the grab, and it is a ceiling on the rate rather than
         * a promise of it: where the capture is slower, the taps go out as fast
         * as the device will answer and no faster.
         *
         * Deliberately not scaled by `patience`, unlike every other pause here.
         * Patience is how long this program waits for the device to settle, and
         * this loop never waits for a settled screen -- scaling it would make
         * the one number the player asked for mean something different on every
         * machine.
         */
        const val SPAM_TAP_EVERY = 0.2

        /**
         * How long the picture may sit there without one pixel changing while
         * taps keep going out. Not "how many taps did nothing" -- taps during a
         * load do nothing by definition, and counting those is what aborted
         * three good runs in dungeon.py. The question is "is anything moving at
         * all", which is `MOVING_SHARE`'s question: a blinking icon is enough to
         * say the game is still alive, and a screen that has not altered one
         * pixel in twenty seconds really is stuck.
         */
        const val FROZEN_SECONDS = 20.0
        /** `dungeon.MOVING_SHARE`: "did anything at all move" -- [Dungeon.MOVING_SHARE]. */
        const val MOVING_SHARE = Dungeon.MOVING_SHARE

        /**
         * How many frames running have to show the dialog before Close is aimed
         * at it. Two, and it is not caution for its own sake -- **where Close
         * sits is a card once the dialog is gone.** Measured on a plain result
         * screen: fx 0.373 fy 0.588 is the fourth row of the reward grid, and a
         * card opens its own window over everything, which is a screen with
         * neither a summon button nor an X on it. That is a run standing still
         * until something closes the window, and it is what a live run did on
         * 2026-09-09.
         *
         * One frame is how it got there. The dialog fades in, and on the frame
         * before it is recognised the button behind it is still bright enough to
         * be found and tapped -- that tap lands on the dialog's own backdrop and
         * dismisses it, so a Close aimed off the single frame that did see the
         * dialog arrives after the dialog has gone. Two frames costs 200 ms at
         * the end of a mode and closes that gap.
         */
        const val DIALOG_FRAMES = 2
        /**
         * The same two frames, for the same reason, on the answer that comes
         * first. The red price is a reading of the game's own screen rather than
         * the game's own words, so it gets confirmed across two frames before a
         * mode is ended on it -- 200 ms, once, at the end of a mode.
         */
        const val RED_FRAMES = 2
        /**
         * Closing the dialog: how many taps are worth sending, and how many
         * looks between them. A swallowed tap wants another tap rather than more
         * patience; what every one of those taps needs is a Close found on a
         * *fresh* frame, for the reason above.
         */
        const val CLOSE_TAPS_MAX = 3
        const val CLOSE_LOOKS = 3

        /**
         * How many rounds the way out may find neither the main screen nor the X
         * before it keeps a frame. It does not give up there -- something may
         * yet close whatever is in front -- but a run that stalls here has
         * always cost a round of guessing afterwards, and one picture answers it.
         */
        const val EXIT_BLIND_ROUNDS = 4
        /**
         * The X pops one screen, so the deepest this can ever be is two: a
         * result screen, then the mode screen, then the game. Four is that
         * doubled, because a tap that lands during an animation is eaten and has
         * to be sent again -- and it is a cap, not a target: once four taps have
         * gone out without the auto button appearing, whatever is on screen is
         * not what this skill thinks it is, and more taps into it would be
         * exactly the blind clicking NOTES.md warns about.
         */
        const val EXIT_TAPS_MAX = 4
        /**
         * Rounds, not a deadline. [backToMain] counts rounds for the same
         * reason: patience is a factor on the waiting, and a deadline built out
         * of it collapses to "no rounds at all" the moment patience is zero.
         */
        const val EXIT_ROUNDS = 20
        const val EXIT_ROUND = 0.6
        const val BACK_ROUNDS = 6
        const val BACK_ROUND = 0.4
        const val BANNER_TRIES = 3

        /** `DungeonBot.pause_long` = 1.0 * patience, and its default. */
        const val PAUSE_LONG = 1.0
        const val PATIENCE = 1.5
        /** `_dump`'s cap: five frames of one run, and no more. */
        const val DUMPS_MAX = 5
    }
}
