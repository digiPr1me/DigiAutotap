package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The Dungeons skill on the phone: `dungeon.py`'s `DungeonBot` under the
 * director (PLAN_ANDROID_APP.md 4, session L). The seam is where
 * PLAN_ANDROID_3_DIRECTOR.md 3.2 puts it:
 *
 *   `run`  = dungeon.py:2653 `DungeonBot.run` -- `open_list`, `_play`,
 *            `go_home` in a finally. Ends at home.
 *   `work` = dungeon.py:2673 `_play` from the line after `open_list`
 *            (dungeon.py:1755) has answered True. Ends on the list.
 *
 * Every reader it asks is `Dungeon.kt`'s (P1) and not one of them is touched
 * here. What this file carries of `dungeon.py` besides the loop is the bot's
 * own numbers -- the ones no reader needs, which P1 therefore left behind --
 * each with the sentence it has there: [NEUTRAL_TAP_Y], [CLOSE_W_MAX],
 * [NAV_DUNGEON], [MOVING_SHARE], [HOME_PRESSES_MAX], [HOME_ROUNDS] and
 * `dungeon_label`.
 *
 * Three things differ from the PC, and all three come from the director:
 *
 *  - **The prompt it leaves standing.** Inside `work` a prompt this skill
 *    raised itself is its own to answer, on the evidence `dismiss_confirm`
 *    already uses: a dungeon panel on the frame before it. That is the only
 *    way out of a panel -- the panel closes through that prompt and by no
 *    other means (the laboratory's notes, "The dungeon's own panel closes
 *    only through that prompt"). But where `work` hands the screen back while
 *    such a prompt may *still* be standing, it does not sit there tapping at a
 *    screen it has already given away: it names what it was doing in
 *    [Outcome.leaving], and the director presses OK on that one prompt and on
 *    no other (Skill.kt).
 *  - **One switch, asked between two actions.** The phone has no F7, so
 *    `_time_left`'s pause half is gone, and its time limit went with the
 *    setting that fed it: what is left is the main switch, asked between
 *    two actions and never in the middle of one (the laboratory's notes,
 *    "wait_while_paused does not answer 'was I stopped'"). [stillOn] is the one
 *    gate.
 *  - **No dry run.** `app.py` passes `dry_run=False` and the director has
 *    no other way to call this, so the branch that clicks nothing is gone
 *    rather than carried as a mode nothing on the phone can reach.
 *
 * What one pass found is readable afterwards and not only in the log:
 * [stats] and [counted] are what the skill measured, because a caller that
 * cannot ask what another skill read ends up guessing at it (the laboratory's
 * notes, "Silencing another skill's log throws away its measurements").
 */
open class DungeonSkill(
    private val cap: Capture,
    /**
     * Read fresh at the start of every pass, never remembered: the app
     * writes the settings file, and a copy taken once would answer with the
     * switches as they were then (CoreService, `store`).
     */
    private val settings: () -> Settings,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** The main switch. True means carry on; it is asked in [stillOn] alone. */
    private val on: () -> Boolean = { MainSwitch.on },
    /**
     * Keep a frame worth looking at afterwards -- the prompt whose OK ends
     * the session, a screen this skill could not read. The app writes files;
     * core cannot.
     */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    private val sleep: (Double) -> Unit = { Thread.sleep((it * 1000).toLong()) },
    /** Seconds, monotonic. Injected so that the flow test can move it. */
    private val now: () -> Double = { System.nanoTime() / 1e9 },
    /**
     * `DungeonBot`'s patience: every wait in here hangs off this one factor.
     * The animation windows measured take about half a second longer than
     * assumed, so it is 1.5.
     */
    patience: Double = 1.5,
) : Skill {

    /**
     * What the dungeon page holds (SkillSettings, and the table in
     * PLAN_ANDROID_3_DIRECTOR.md 5.3), read the way `app._start_dungeon`
     * reads it.
     */
    class Settings(
        /**
         * `dg_<i>` on the PC, "attempts" in digiautotap.json: how many
         * **tickets** the player wants spent on each card, by position in
         * the list. A dungeon at 0 is not played at all -- that is what the
         * checkbox used to say.
         *
         * Attempts until 2026-09-23, when the game's dungeons grew harder
         * and a run could be lost: a lost run costs no ticket and is tried
         * again, so the number the player cares about is the tickets, and
         * attempts are what [attemptsBeforeClear] counts. The key stayed
         * "attempts" so that nobody's numbers went with the update.
         */
        val budgets: Map<Int, Int>,
        /**
         * Tap the film button at all: the Dungeons page's "Watch the free
         * ads for tickets" (Stored.DUNGEON_ADS_KEY, off by default) since
         * 2026-09-30, and the Quest Loop's card reads the same switch --
         * since 2026-10-03 only with the Ad Skip Pass beside it
         * (Stored.dungeon, [FreeAds]), so that the ticket comes with the tap.
         * Off, a card is its tickets alone ([owed]): the survey calls a card
         * at 0 tickets "nothing left today" and the panel's film button ends
         * the card. On is the default here for the flow tests, which were
         * all written while the button was always tapped.
         */
        val useAds: Boolean = true,
        /**
         * `dungeon_attempts_before_clear` (`quest_attempts_before_clear` for
         * the quest loop's own card): after this many attempts on one
         * dungeon in a pass -- the lost ones alone where [countLostOnly]
         * says so -- every ticket still wanted there goes to Clear Previous
         * Difficulty, which hands out the previous difficulty's rewards at
         * once and cannot be lost. Counted per dungeon and pass, not per
         * ticket -- the player's rule of 2026-09-23. 0 is "never Attempt,
         * Clear Previous Difficulty only".
         */
        val attemptsBeforeClear: Int = ATTEMPTS_BEFORE_CLEAR,
        /**
         * Only a failed attempt counts towards [attemptsBeforeClear]: a run
         * that ends with no Reward sheet and the counter where it was, back
         * on the panel or the list ([book]'s "run lost", and its "counted as
         * a lost run"), and a run whose two witnesses disagree (`bookRun` in
         * [playEntry] says why). A won run spends a ticket and is not
         * counted. The
         * Dungeons page's number since 2026-10-04, the player's words:
         * "Failed attempts before clicking Clear Previous Difficulty" --
         * until then it was every attempt, won or lost, and a card that won
         * its first runs reached Clear Previous Difficulty with no run lost
         * at all. The quest loop's card keeps counting every attempt
         * ([QuestSkill.dungeonSettings]): its page has its own number, and
         * the player asked about the Dungeons task's.
         */
        val countLostOnly: Boolean = true,
        /**
         * `survey`: read both counters off every card before playing
         * anything, so a card at zero is skipped without being opened. The
         * Dungeons task runs without it since 2026-09-22 (Stored.dungeon):
         * the player wants the cards they set played, not counted, and the
         * loop finds out inside the panel what the card would have said.
         * The quest loop keeps it on for its single card, because
         * `dungeonShort` and `dungeonBlocked` are read off it.
         */
        val survey: Boolean = true,
        /**
         * Not a setting: how many dungeons the list holds is the length of
         * the list the player is looking at (`app._start_dungeon`).
         */
        val entries: Int = SkillSettings.DUNGEON_NAMES.size,
        /**
         * `dungeon_daily_minutes` (Stored.dungeonDaily): how long the daily
         * dungeon -- the seventh card, the one whose boss changes every day
         * -- is attempted at the end of every pass, run after run
         * ([playDaily], PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.1). 0 is off and
         * the default, and the quest loop's own bot never sets it (the
         * player's answer to question 12). The minutes are a floor, not a
         * cut: a run begun is waited out (question 10).
         */
        val dailyMinutes: Int = 0,
    ) {
        /** `app._start_dungeon`'s `only`: the cards with a number above 0. */
        val only: Set<Int> get() = budgets.filterValues { it > 0 }.keys
    }

    // ------------------------------------------------------------------
    // The knobs of dungeon.py that are nobody's setting on either side.
    // `internal var` rather than constructor arguments for the same reason
    // test_dungeon_flow.make_bot pokes them: a flow case wants one of them
    // moved and nothing else.
    // ------------------------------------------------------------------
    /**
     * Entries at the end that are skipped: the last one changes daily. It
     * has no tickets, and since 2026-09-29 the minutes the page gives it are
     * played after the halves by a phase of its own ([playDaily]).
     */
    internal var skipLast = 1
    /** See [BATTLE_TIMEOUT]. */
    internal var battleTimeout = BATTLE_TIMEOUT
    internal var tick = 1.0
    /**
     * The ceiling when nothing was read from the card. Elapsed time still
     * decides whether a battle really happened: a battle takes 7 to 40
     * seconds, and a dialog returning faster than that means nothing
     * happened.
     */
    internal var maxAttempts = 6
    /**
     * Hard upper limits. They should never trigger, but are the last
     * safeguard against a loop nobody foresaw. Two ads per dungeon per day,
     * each granting exactly one ticket.
     *
     * A round of the loop is one look at the screen, and a lost run costs
     * three of them -- the panel, the battle, the panel again -- so a card
     * gets `3 * (attempts before Clear Previous Difficulty + tickets) + 6`
     * of them ([loopsFor]) -- with the lost runs alone counted since
     * 2026-10-04 ([Settings.countLostOnly]) a card still runs at most N
     * lost and its tickets won; the 12 that stood here were a fixed number for
     * a card that spent at most four tickets and never lost. This is the
     * ceiling over that, the last halt, and the one a flow case lowers.
     */
    internal var maxAds = 2
    internal var maxLoops = LOOP_CAP
    /** Battles measured take about 20 seconds, short ones about 7. Below this it was a rejection. */
    internal var minBattle = 6.0
    /**
     * How long a screen no reader knows has to stand before [waitDialogBack]
     * taps it. The tap high up is the one that closes a dungeon panel
     * ([returnToList] leaves a panel that way), and it used to go out on the
     * first unknown look: live on LDPlayer 2026-09-23 a lost Bakemon run came
     * back to the panel between a look and its tap, the tap closed it, and
     * the counter that was to say "no ticket spent" was never read. What
     * stands between two known screens of a run is short: in the three
     * recorded runs of that day (2.2 frames a second) the black frame and
     * "Now Loading" after a lost battle were three frames, 1.4 s, and so was
     * the moment between Attempt and the battle. 3 s is twice that; the
     * Reward sheet, which does need its tap, is named now and tapped at once.
     */
    internal var unknownHold = UNKNOWN_HOLD
    /** See [PARTY_WAIT]. */
    internal var partyWait = PARTY_WAIT
    /** See [DAILY_WAIT]. */
    internal var dailyWait = DAILY_WAIT
    internal var swipes = 1
    internal var patience = patience
    /** After Attempt the game needs a moment before the dialog goes away. Measured about 3 s at Apocalymon Wall. */
    internal var startTimeout = 8.0 * patience
    internal var pauseShort = 0.5 * patience
    internal var pauseLong = 1.0 * patience

    // ------------------------------------------------------------------
    // What one pass knows. All of it is set again at the start of every
    // pass: a `work` is a visit, and DungeonBot is built afresh per run on
    // the PC.
    // ------------------------------------------------------------------
    /**
     * Click origin and size: `game_rect` of the frame the pass began on, as
     * `DungeonBot._device_size` takes it once. Clicks go out in the
     * reference frame's coordinates, not in fractions of the picture
     * (NOTES.md, "A fraction of the picture is not a fraction of the game").
     */
    private var origin = 0 to 0
    private var device = 1080 to 1920
    /** The headroom of the frame [origin] was taken off (Dungeon.CanvasFrame). */
    private var room = 0
    internal var entries = SkillSettings.DUNGEON_NAMES.size
    /**
     * How many cards are visible at the bottom, measured during planning and
     * remembered here, so the names from the bottom are mapped correctly.
     */
    internal var visibleAtBottom = 5
    /** The selection, as `DungeonBot` takes it: `only` wins over `skip`. */
    internal var only: Set<Int>? = null
    internal var skip: Set<Int> = emptySet()
    internal var budgets: Map<Int, Int> = emptyMap()
    internal var attemptsBeforeClear = ATTEMPTS_BEFORE_CLEAR
    /** See [Settings.countLostOnly]. */
    internal var countLostOnly = true
    /** The daily dungeon's minutes for this pass ([Settings.dailyMinutes]); 0 leaves it out. */
    internal var dailyMinutes = 0
    internal var useAds = true
    /**
     * The game asked for an ad after a film button the Ad Skip Pass was
     * taken for granted for ([FreeAds.PARK]): the pass stops where it
     * stands, and nothing is tapped on the question or in the ad.
     */
    internal var adParked = false
        private set
    /**
     * The game's download dialog stood in this pass (Dungeon.KIND_DOWNLOAD):
     * its Cancel ends the game, its OK is the director's to tap
     * (DirectorLoop.download), so the pass taps nothing more and hands the
     * screen back, as [adParked] does for an ad the game asked for.
     */
    internal var downloadAsked = false
        private set
    /**
     * The game went back to its title in this pass (Startup.title;
     * PLAN_RELEASE_1_3.md B30): the session is gone -- another device took
     * the account, the game restarted -- and a tap high up there would be
     * the "Touch To Start" that takes it back. The pass taps nothing more
     * and hands the screen back, as [downloadAsked] does.
     */
    internal var onTitle = false
        private set
    /** A screen this pass must not touch stands in front: the download dialog or the title. */
    private val handsOff get() = downloadAsked || onTitle
    private var surveyFirst = true
    /**
     * Why the pass stopped short of the list's end, or null: the panel's
     * counter could not be read twice in a row ([book]), so nothing this
     * skill did could be counted any more. [play] hands it back parked.
     */
    internal var parkedBecause: String? = null
        private set
    /** Panels in a row whose counter could not be read with no sheet to say otherwise. */
    /**
     * Unreadable counters in a row on the card being played. Per card, not
     * per pass: the pass-wide count (2026-09-23) was meant for a reader
     * that broke, and what it cost was every card after the unreadable one
     * -- Network Defense Ops and Metal Sea never opened after two lost runs
     * on Bakemon and Digifactory (notes/dungeons.md, "A swipe is proved, not assumed").
     */
    private var unreadableInARow = 0
    /** [book] gave up on this card's counter; the card ends, the pass goes on. */
    private var counterGaveUp = false
    /** "Find a Party" taps on the card being played, for its line in the log ([playEntry]). */
    private var cardSearches = 0
    /** Runs on the card being played that no Attempt started -- the game's own after a party is found (the BATTLE branch). */
    private var cardSelfStarted = 0
    /** Whether the Reward sheet was seen since the last tap on Attempt or Clear Previous Difficulty. */
    internal var rewardSeen = false

    /** What the whole pass counted, for the log and for whoever asks after it. */
    val stats = LinkedHashMap<String, Int>()

    /** What this pass counted, for the TODAY card ([SkillStats], [Counted]). */
    val lastCounts: Map<String, Int> get() = LinkedHashMap(stats)
    /**
     * What the last survey read off each card: a ceiling on what the panel
     * will hand out, known before the card is opened, and what the quest
     * loop reads its `dungeonShort` out of. Inside the panel the loop reads
     * the panel's own counter ([Dungeon.panelTickets]).
     */
    val counted = HashMap<Pair<String, Int>, Dungeon.Budget>()
    /**
     * Tickets already spent per dungeon, across the whole pass. The number
     * the player sets is a budget for the pass; asked once per round it
     * handed Apocalymon its single attempt again in round two.
     */
    private val spent = HashMap<Int, Int>()
    /**
     * Dungeons that have demonstrably yielded nothing more this session. The
     * evidence comes from operation, not from the image: by colour an
     * exhausted ad button looks the same as a usable one, measured both
     * H 125 S 170 V 235.
     */
    private val exhausted = HashSet<Pair<String, Int>>()
    private var saved = 0
    /** The main switch went off between two actions. */
    private var stopped = false
    /**
     * A dungeon panel was open at some point in this pass, so a prompt right
     * after it is this skill's own. See [handBack].
     */
    private var panelSeen = false

    // ------------------------------------------------------------------
    // What a pass the main switch stopped carries into its going on
    // (Skill.resume; PLAN_RELEASE_1_3.md B4, the player's rule of
    // 2026-09-30). Kept from the stop to the resume and cleared by a fresh
    // [begin]: the resume is the same pass, so what it spent counts against
    // the page's numbers, and a card finished before the pause is not
    // opened again. The TODAY card's [stats] are the one thing a resume
    // starts from nothing -- the stopped part handed its count over as it
    // stopped (notes/director.md, "A counter nothing clears is counted
    // again at the end of every pass").
    // ------------------------------------------------------------------
    /** The last pass ended STOPPED and has not been gone on with or begun afresh since. */
    internal var carried = false
        private set
    /** Cards ([TOP]/[BOTTOM] and index) this pass played to their end. */
    private val finishedCards = HashSet<Pair<String, Int>>()
    /** Attempts per card position in this pass, won or lost, across a pause: the re-open's brake counts them. */
    private val attemptsOn = HashMap<Int, Int>()
    /** Failed attempts per card position in this pass (`bookRun`): [attemptsBeforeClear] counts a card's, across a pause. */
    private val lostOn = HashMap<Int, Int>()
    /** Ads watched per card position in this pass, against the card's own count of ads. */
    private val adsOn = HashMap<Int, Int>()
    /** This pass's plan ([plan]): measured once, where the pass began. */
    private var planned: Pair<Int, Int>? = null
    /** The card whose panel was open when the switch stopped the pass: gone on with on that panel. */
    private var inFlight: Pair<String, Int>? = null
    /** Seconds of the daily dungeon's minutes this pass has played, the pauses not among them. */
    private var dailyPlayed = 0.0
    /** The next [playEntry] plays on from the card's panel in front, and opens nothing ([played]). */
    private var onPanel = false
    /** The daily dungeon's phase ended in its own way in this pass: the minutes, MAX, a brake. */
    private var dailyOver = false
    /** The switch stopped the pass in the daily dungeon's phase. */
    private var dailyInFlight = false
    /** Every way home asks the switch first ([Stays]). */
    private val stays = Stays(on) { log(it) }

    // ------------------------------------------------------------------
    // The seam (Skill)
    // ------------------------------------------------------------------
    override val key = "dungeon"
    override val name = "Dungeons"

    override fun worksOn(screen: String) = screen == Director.DUNGEON_LIST

    /**
     * `chain.dungeon_has_budget(budgets)` (chain.py:96), and since the daily
     * dungeon's minutes (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.1) a ticket on
     * a card *or* minutes on the daily one: [Chain.dungeonHasWork], which the
     * row in the Tasks list asks too.
     */
    override fun hasBudget(): Boolean = settings().let { Chain.dungeonHasWork(it.budgets, it.dailyMinutes) }

    /**
     * The list is already in front: the director classified [img] as one
     * (Director.DUNGEON_LIST) and held the player's three seconds. So this
     * is `_play` from the line after `open_list` said True, and it ends
     * where it began.
     */
    override fun work(img: Mat): Outcome {
        begin(img)
        return carry(handBack(play()))
    }

    /** The whole: to the list, [play], and home again -- home in a finally, as `run` does. */
    override fun run(): Outcome {
        val first = try {
            grab()
        } catch (e: CaptureError) {
            // Out before [begin] has zeroed anything, and [lastCounts] is
            // read after every pass: the last pass's attempts would go on
            // the card a second time.
            for ((k, _) in SUMMARY_LABELS) stats[k] = 0
            return Outcome.noFrame(e, "no frame to start from")
        }
        begin(first)
        try {
            if (!openList()) return carry(if (!on()) Outcome.STOPPED else Outcome.parked(
                "the dungeon list did not open -- am I on the main screen?"))
            return carry(play())
        } finally {
            // On every way out and not only the good one: a pass that never
            // found the list, one cut short by the main switch, one that
            // threw. Each of those used to leave the game wherever it stood.
            // With the switch off it stays where it stands ([Stays]).
            goHome()
        }
    }

    /**
     * The screens a pass the switch stopped goes on from ([Skill.resumesOn]):
     * the list, and what its own runs stand on -- a dungeon's panel (the
     * director's `dialog`), the Reward sheet after a won run and a battle
     * under way (both `unknown` to the director), each asked of the frame
     * with the reader the pass itself asks ([recognise]). Nothing without a
     * stopped pass to go on with ([carried]).
     */
    override fun resumesOn(screen: String, img: Mat): Boolean {
        if (!carried) return false
        return when (screen) {
            Director.DUNGEON_LIST -> true
            Director.DIALOG, Director.UNKNOWN -> {
                val state = recognise(img).state
                state in DIALOGS || state == Dungeon.REWARD || state == Dungeon.BATTLE
            }
            else -> false
        }
    }

    /**
     * The pass the switch stopped, gone on with where it stands: a run's
     * Reward sheet closed and a battle waited out; the card whose panel is
     * open played on from that panel with what it has spent; then the rest
     * of the pass's plan and the daily dungeon's minutes that are left. From
     * the main screen (a chain step whose claim ended in the pause) the list
     * is opened first. [whole] ends at home, as [run]; otherwise on the
     * list, as [work].
     */
    override fun resume(img: Mat, whole: Boolean): Outcome {
        if (!carried) return if (whole) run() else work(img)
        goOn(img)
        if (!whole) return carry(handBack(playOn()))
        try {
            if (autoButton(img) != null && !openList()) return carry(if (!on()) Outcome.STOPPED else Outcome.parked(
                "the dungeon list did not open -- am I on the main screen?"))
            return carry(playOn())
        } finally {
            goHome()
        }
    }

    /** What the pass hands back, and whether it is one to go on with ([carried]). */
    private fun carry(out: Outcome): Outcome {
        carried = out.result == Result.STOPPED
        return out
    }

    /** Fresh state for a pass, and the reference rect its clicks go out in. */
    private fun begin(img: Mat) {
        settle(img)
        surveyFirst = settings().survey
        visibleAtBottom = 5
        spent.clear()
        exhausted.clear()
        counted.clear()
        carried = false
        finishedCards.clear()
        attemptsOn.clear()
        lostOn.clear()
        adsOn.clear()
        planned = null
        inFlight = null
        dailyPlayed = 0.0
        dailyOver = false
        dailyInFlight = false
    }

    /**
     * The pass the switch stopped, going on ([resume]): the page read again
     * -- the player may have changed a number in the pause -- and what the
     * pass spent kept; the TODAY card's count and the park's reasons start
     * afresh, and so does the survey, which the pass has had.
     */
    private fun goOn(img: Mat) {
        settle(img)
        surveyFirst = false
        log("\ngoing on with the pass the main switch stopped")
    }

    /** What every stretch of a pass starts from, fresh or going on: the page, the reasons, the count, the rect. */
    private fun settle(img: Mat) {
        val s = settings()
        budgets = s.budgets
        only = s.only
        skip = emptySet()
        entries = s.entries
        useAds = s.useAds
        adParked = false
        downloadAsked = false
        onTitle = false
        attemptsBeforeClear = maxOf(0, s.attemptsBeforeClear)
        countLostOnly = s.countLostOnly
        dailyMinutes = maxOf(0, s.dailyMinutes)
        stopped = false
        panelSeen = false
        parkedBecause = null
        unreadableInARow = 0
        rewardSeen = false
        saved = 0
        stays.reset()
        stats.clear()
        for ((k, _) in SUMMARY_LABELS) stats[k] = 0
        val r = Dungeon.gameRect(img)
        origin = r.x0 to r.y0
        device = r.gw to r.gh
        room = Dungeon.headroom(img)
    }

    /**
     * The end of the seam (Skill.kt, [Outcome.leaving]). `work` ends on the
     * list; where a panel was open in this pass and the list is not back on
     * the last look, a prompt of this skill's own may still be standing --
     * one of three identical to the pixel, and only the caller knows which
     * (the laboratory's notes, "Prompts and dialogs"). So it says what it
     * was doing and lets the director answer that one prompt, rather than
     * tapping at a screen it has already handed back.
     */
    private fun handBack(outcome: Outcome): Outcome {
        if (!panelSeen || adParked || handsOff) return outcome
        val state = try {
            recognise(grab()).state
        } catch (e: CaptureError) {
            Dungeon.UNKNOWN
        }
        if (state == Dungeon.LIST) return outcome
        return Outcome(outcome.result, outcome.why, LEAVING, outcome.lostFrame)
    }

    // ------------------------------------------------------------------
    // The pass (dungeon._play)
    // ------------------------------------------------------------------
    /**
     * One pass through the list, top half then bottom half, and then -- where
     * the page gives it minutes -- the daily dungeon ([playDaily]).
     *
     * It used to go round and round. What the rounds actually produced was a
     * dungeon set to one attempt being handed one more in every round. A
     * lost battle is retried now, but inside [playEntry], on the card's own
     * panel and against its own counter -- not by a second pass.
     */
    internal open fun play(): Outcome {
        val counts = planned ?: plan()?.also { planned = it } ?: return if (stopped) done() else Outcome.parked(
            parkedBecause ?: "no list cards recognised -- am I in the list?")
        val (playFromTop, playFromBottom) = counts

        // A card this pass has played to its end is not opened again when the
        // pass goes on after a pause ([finishedCards]), nor one it has spent
        // the page's number on or found empty; on a fresh pass all three
        // are empty and every selected card is in.
        fun open(index: Int, label: String) = isSelected(index, label) && label to index !in finishedCards &&
            label to index !in exhausted && attemptsFor(index, label) > 0
        var topList = (0 until playFromTop).filter { open(it, TOP) }
        var bottomList = (0 until playFromBottom).filter { open(it, BOTTOM) }
        // A half with no card of the player's in it is not scrolled to,
        // here or below: the pass used to swipe to the top and back for a
        // survey of six "not selected" and then once more to play them.
        if (surveyFirst) {
            log("\nPre-check: which dungeons still have tickets")
            if (topList.isNotEmpty()) {
                topList = scrollTop()?.let { survey(topList, TOP, it) } ?: emptyList()
            }
            if (bottomList.isNotEmpty()) {
                bottomList = scrollBottom()?.let { survey(bottomList, BOTTOM, it) } ?: emptyList()
            }
            log("Playable, top %s, bottom %s".format(
                if (topList.isEmpty()) "none" else topList.map { it + 1 }.toString(),
                if (bottomList.isEmpty()) "none" else bottomList.map { it + 1 }.toString()))
            bump("skipped", playFromTop - topList.size + playFromBottom - bottomList.size)
            if (topList.isEmpty() && bottomList.isEmpty()) {
                log("nothing left to collect")
                // The daily dungeon has no counter to survey: its minutes
                // are played whatever the tickets said.
                playDailyIfSet()
                return done()
            }
        }

        // A card's index is only a card under the scroll the plan measured
        // it at, so a half whose scroll could not be proved is not played:
        // the card tapped would be the neighbour's.
        if (topList.isNotEmpty() && stillOn() && scrollTop() == null) {
            if (stillOn()) log("the list would not scroll to the top, leaving the top half")
            topList = emptyList()
        }
        for (index in topList) {
            if (!stillOn()) break
            log("\n" + labelOf(index, TOP))
            played(index, TOP)
            if (adParked || stopped) break
            if (scrollTop() == null) {
                if (stillOn()) log("the list would not scroll back to the top, leaving the rest of the top half")
                break
            }
        }
        if (bottomList.isNotEmpty() && stillOn() && scrollBottom() == null) {
            if (stillOn()) log("the list would not scroll to the bottom, leaving the bottom half")
            bottomList = emptyList()
        }
        for (index in bottomList) {
            if (!stillOn()) break
            log("\n" + labelOf(index, BOTTOM))
            played(index, BOTTOM)
            if (adParked || stopped) break
            if (scrollBottom() == null) {
                if (stillOn()) log("the list would not scroll back to the bottom, leaving the rest of the bottom half")
                break
            }
        }
        playDailyIfSet()
        return done()
    }

    /**
     * One card of the plan, opened from the list ([open]) or played on from
     * its panel after a pause, and written down as played to its end unless
     * the switch stopped it -- then it is the card to go on with ([inFlight]).
     */
    private fun played(index: Int, label: String, open: Boolean = true) {
        inFlight = null
        onPanel = !open
        playEntry(index, label, label to index)
        onPanel = false
        // A card's way back to the list asks the switch as every way home
        // does, and may have ended there with nothing said: the switch is
        // asked once more, so that the card is gone on with and not taken
        // for finished.
        if (!stopped && !on()) stillOn()
        if (stopped) inFlight = label to index
        else if (!adParked && !handsOff) finishedCards.add(label to index)
    }

    /**
     * The body of [resume]: what stands in front taken up first -- the
     * Reward sheet of a run tapped away and a battle waited out, both as a
     * run's own wait does it ([waitDialogBack]); on a panel, the card that
     * was being played goes on there ([inFlight]), or the daily dungeon's
     * minutes on its own panel; a panel of neither is closed the way every
     * panel is ([returnToList]). Then the rest of the plan ([play]).
     */
    private fun playOn(): Outcome {
        var here = recognise(grab())
        if (here.state == Dungeon.REWARD || here.state == Dungeon.BATTLE || here.state == Dungeon.UNKNOWN) {
            log("  a run's end is in front -- waiting it out")
            waitDialogBack(battleTimeout)?.let { here = it }
            if (!stillOn()) return done()
            here = recognise(grab())
        }
        if (here.state in DIALOGS) {
            val card = inFlight
            when {
                dailyInFlight -> {
                    log("  on the daily dungeon's panel")
                    onPanel = true
                    playDaily()
                    onPanel = false
                }
                card != null -> {
                    log("\n" + labelOf(card.second, card.first) + ", going on on its panel")
                    played(card.second, card.first, open = false)
                }
                else -> returnToList()
            }
            if (stopped || adParked || handsOff) return done()
        }
        return play()
    }

    private fun done(): Outcome = when {
        stopped -> Outcome.STOPPED
        parkedBecause != null -> Outcome.parked(parkedBecause!!)
        else -> Outcome.DONE
    }

    /**
     * Two-phase plan, so that without name recognition no entry is played
     * twice or not at all. The list is longer than the window: the first
     * cards are visible at the top, the last ones at the bottom, and from
     * the total count and the number visible at the bottom follows how many
     * must be played from the top. null where no card was recognised at all.
     */
    internal open fun plan(): Pair<Int, Int>? {
        val img = scrollBottom()
        if (img == null && !on()) {
            // The switch, not the list: the pass stops before it has a plan.
            stillOn()
            return null
        }
        if (img == null) {
            // Counted at the top, the four cards of the top view become the
            // four bottom ones: Bakemon is played twice, Digifactory under
            // Network Defense Ops' name, Network Defense Ops under Metal
            // Sea's budget -- and not at all when that stands at 0. Loud and
            // wrong beats quiet and one off, and the frame [scroll] kept is
            // the measurement if a display really shows four cards at the
            // bottom (notes/dungeons.md, "A swipe is proved, not assumed").
            parkedBecause = "the dungeon list did not reach the bottom after $SCROLL_TRIES " +
                "swipes, so no card can be counted"
            log(parkedBecause!!)
            return null
        }
        val nBottom = listCards(img).size
        if (nBottom == 0) {
            log("no list cards recognised, am I in the list?")
            return null
        }
        visibleAtBottom = nBottom
        val playFromTop = max(0, entries - nBottom)
        val playFromBottom = max(0, nBottom - skipLast)
        log("plan: %d cards visible at the bottom. Play %d from the top, %d from the bottom, %d of %d in total"
            .format(nBottom, playFromTop, playFromBottom, playFromTop + playFromBottom, entries))
        return playFromTop to playFromBottom
    }

    /**
     * `dungeon._time_left`, minus both of the PC's halves: no pause -- the
     * phone has no F7 -- and no time limit, whose setting the dungeon page
     * no longer carries. What is left is the main switch, asked between two
     * actions and never in the middle of one, so no click is left
     * unverified.
     */
    internal fun stillOn(): Boolean {
        // Not the switch: the ad is still in front, and every tap would be
        // withheld by the service anyway (DigiAutotapService.withheld). Nor
        // the game's download dialog, which is the director's to answer, nor
        // its title ([onTitle]).
        if (adParked || handsOff) return false
        if (on()) return true
        log("stopped")
        stopped = true
        return false
    }

    /**
     * Read from the list in advance which dungeons still have tickets.
     *
     * From the list and not from the dialog, which saves opening and closing
     * per dungeon; the Attempt button is not a reliable witness anyway, it
     * is visible even at 0 tickets. Both counters are read, and what comes
     * out is what the card can still yield today: tickets plus the ads not
     * yet watched. A card whose counters cannot be read is still played --
     * unreadable is not the same as empty.
     */
    internal open fun survey(positions: List<Int>, label: String, img: Mat): List<Int> {
        val playable = ArrayList<Int>()
        // [img] is the settled frame the scroll proved, not a fresh grab.
        for (index in positions) {
            if (!stillOn()) break
            if (!isSelected(index, label)) {
                log("  %-22s not selected".format(labelOf(index, label)))
                bump("not_selected", 1)
                continue
            }
            if (label to index in exhausted) {
                log("  %-22s already exhausted this session".format(labelOf(index, label)))
                continue
            }
            if (attemptsFor(index, label) <= 0) {
                log("  %-22s had its %d, done".format(
                    labelOf(index, label), spent[globalIndex(index, label)] ?: 0))
                continue
            }
            val cards = listCardsWithSize(img)
            if (index >= cards.size) continue
            val (fy, fh) = cards[index]
            val budget = cardBudget(img, fy, fh)
            val allowed = attemptsFor(index, label)
            counted[label to index] = budget

            val reason: String
            if (!useAds && budget.tickets != null) {
                // The ads are not this pass's to spend (the page's switch),
                // so the tickets alone are the whole answer, and a counted
                // one: no symbol left to wait for.
                if (budget.tickets == 0) {
                    reason = "no tickets left today, and ads are switched off"
                } else {
                    playable.add(index)
                    reason = "%d tickets, ads switched off, spending %d".format(
                        budget.tickets, min(budget.tickets, allowed))
                }
            } else if (budget.total == 0) {
                reason = "nothing left today"
            } else if (budget.total != null) {
                playable.add(index)
                // Both numbers, because they answer different questions:
                // what the game still owes, and what the player allowed.
                reason = "%d tickets and %d ad%s, %d possible, spending %d".format(
                    budget.tickets, budget.ads, if (budget.ads == 1) "" else "s",
                    budget.total, min(budget.total!!, allowed))
            } else if ((budget.tickets ?: 0) != 0) {
                playable.add(index)
                // The ads are behind a symbol the game has not drawn yet.
                reason = "%d tickets, ads unknown until they run out, spending up to %d"
                    .format(budget.tickets, allowed)
            } else {
                playable.add(index)
                reason = "counters unreadable, will try it"
            }
            log("  %-22s %s".format(labelOf(index, label), reason))
        }
        return playable
    }

    /**
     * Open a dungeon and play it until nothing more can be done.
     *
     * Reactive loop. Before every action it checks which screen is visible
     * and derives exactly one action from that. The earlier fixed sequence
     * broke wherever something unexpected happened in between; here a new
     * screen is one more line.
     */
    internal open fun playEntry(index: Int, label: String = "", key: Pair<String, Int>? = null) {
        // The card's panel may be in front already ([played] after a pause):
        // then nothing is tapped to open it.
        val open = !onPanel
        onPanel = false
        // Network Defense Ops' counter stands where the overlay's plate
        // stands at its default place (Dungeon.HEADER_COUNTER), so the dot
        // is moved off those rows for this one card and put back after it.
        val netdef = SkillSettings.DUNGEON_NAMES.getOrNull(globalIndex(index, label)) == NETDEF
        // In the HUD's fractions, which the overlay speaks: the counter is the
        // panel's and stands at the top over the canvas ceiling (Dungeon.PANEL).
        if (netdef) cap.overlayClear(
            Dungeon.bottomFy(Dungeon.HEADER_COUNTER[2], Dungeon.PANEL, room, device.second),
            Dungeon.bottomFy(Dungeon.HEADER_COUNTER[3], Dungeon.PANEL, room, device.second))
        val pos = globalIndex(index, label)
        val wanted = attemptsFor(index, label)
        val bookedBefore = spent[pos] ?: 0
        val attemptsBefore = stats["attempts"] ?: 0
        try {
            playOne(index, label, key, open)
        } finally {
            if (netdef) cap.overlayBack()
        }
        // One line per visit of the party dungeon, the card the player has
        // found short (PLAN_DAILY_LOST_SECTOR_PRESETS.md G1): what was booked
        // against what was wanted, the Attempts tapped, the searches, and the
        // runs the game started itself (booked since B5 like the others).
        if (netdef) log("  %s: %d of %d ticket%s booked, %d attempt%s, %d party search%s, %d run%s the game started itself"
            .format(NETDEF, (spent[pos] ?: 0) - bookedBefore, wanted, plural(wanted),
                    (stats["attempts"] ?: 0) - attemptsBefore, plural((stats["attempts"] ?: 0) - attemptsBefore),
                    cardSearches, if (cardSearches == 1) "" else "es", cardSelfStarted, plural(cardSelfStarted)))
    }

    private fun playOne(index: Int, label: String, key: Pair<String, Int>?, open: Boolean = true) {
        val k = key ?: (label to index)
        unreadableInARow = 0
        counterGaveUp = false
        cardSearches = 0
        cardSelfStarted = 0
        // [open] false: the card's panel is in front already, where the
        // switch stopped the pass ([playOn]); nothing is tapped to get there.
        if (open) {
            var img = grab()
            var info = recognise(img)
            if (info.state != Dungeon.LIST) {
                log("  not in the list, state ${info.state}")
                bump("unknown", 1)
                saveUnknown(img, "no_list")
                if (!returnToList()) return
                img = scrollTo(label) ?: run {
                    log("  the list would not scroll to the card's half, skipping it")
                    bump("skipped", 1)
                    return
                }
                info = recognise(img)
            }

            val cards = if (info.karten.isNotEmpty()) info.karten else listCards(img)
            if (index >= cards.size) {
                log("  %s not visible, %d cards recognised".format(labelOf(index, label), cards.size))
                bump("skipped", 1)
                return
            }
            if (!stillOn()) return
            tap(Dungeon.CARD_X, cards[index], labelOf(index, label))
            sleep(pauseShort)
        }

        // Two ceilings, and the lower one wins. The player's number says how
        // many tickets they want spent on this dungeon; the counted one says
        // how much the game will actually hand out. Neither knows the other,
        // and either can be the smaller.
        val allowed = attemptsFor(index, label)
        val read = counted[label to index]
        val readTotal = read?.let { owed(it, useAds) }
        var wanted = allowed
        if (readTotal != null) wanted = min(allowed, readTotal)
        // Ads likewise: what the card says is left, or the old blanket cap
        // when the counter could not be read.
        val adLimit = read?.ads ?: maxAds
        if (wanted != allowed || adLimit != maxAds) {
            log("  spending at most %d (allowed %d, counted %s), ads %d"
                .format(wanted, allowed, readTotal ?: "none", adLimit))
        }
        val loops = loopsFor(wanted)

        var ticketsSpent = 0
        // The card's attempts, lost runs and ads so far in this pass: nought
        // on a fresh pass, what it had when the switch stopped it on one that
        // goes on ([attemptsOn], [lostOn], [adsOn]) -- ten failed attempts
        // before Clear Previous Difficulty are ten, pause or not, and an ad
        // watched is not watched again. The tickets are [spent], which
        // [attemptsFor] already asks.
        val pos = globalIndex(index, label)
        var attemptsMade = attemptsOn[pos] ?: 0
        /** The runs of this card no ticket was booked for ([book]), across a pause as [attemptsMade]. */
        var lostMade = lostOn[pos] ?: 0
        /** What [attemptsBeforeClear] is held against ([countLostOnly]). */
        fun towardClear() = if (countLostOnly) lostMade else attemptsMade
        /**
         * [book] for one run: a won run spends a ticket, and every other run
         * is a failed attempt -- lost (no sheet, the counter where it was),
         * unread (no sheet, no counter), and the run whose witnesses
         * disagree (the sheet, and the counter where it was). That last one
         * may have been won, but counting it is the side whose mistake costs
         * less: left out, a counter that reads wrong after every won run
         * would have Attempt pressed until the loop's ceiling, each run a
         * real ticket nobody books, where the old count stopped at N.
         */
        fun bookRun(before: Int?, after: Int?) {
            if (book(before, after, ATTEMPT) == Booked.TICKET) ticketsSpent += 1
            else lostMade += 1
        }
        var noEffect = 0
        var clearRetried = false
        var adsUsed = adsOn[pos] ?: 0
        var partySearches = 0
        var steps = 0
        var expectTicket = false
        var adsHadNoEffect = false
        /** Attempts plus tickets at the last re-open from the list, or -1 (the LIST branch). */
        var reopenedAt = -1
        var unknownSince: Double? = null
        /** Since when the run's black or VS has stood in the loop's own looks ([passing]). */
        var passingSince: Double? = null
        val saidOn = HashSet<String>()
        var counterAtSearch: Int? = null
        /**
         * The film button was tapped with the pass taken for granted, and the
         * look after it is the one that says whether the game asked for an ad
         * all the same.
         */
        var adTapped = false

        try {
        while (steps < loops) {
            if (!stillOn()) break
            steps += 1
            val here = dialogSettled(2)
            val state = here.state
            if (state in DIALOGS) panelSeen = true
            if (state != Dungeon.UNKNOWN) {
                unknownSince = null
                passingSince = null
            }

            // The look after a film button tapped with the pass taken for
            // granted (2026-10-02, question 11 of PLAN_ABSCHLUSS_1_3.md). The
            // game's question "View ads?" here, or the video already in
            // front, means the switch is on and the pass is not there; until
            // 2026-10-02 the EXIT branch below read the question as the
            // party's prompt and answered OK, which started the ad, and the
            // card ended "left the party". Nothing is tapped on the question
            // or in the ad, no back key is sent, and the pass parks where it
            // stands (2026-10-03, PLAN_ABSCHLUSS_1_3.md 3.6).
            if (adTapped) {
                adTapped = false
                val asked = state == Dungeon.EXIT && here.exitKind == "party"
                if (asked || adInFront()) {
                    log("  the game asks for an ad although the Ad Skip Pass is switched on -- I never touch an ad")
                    parkedBecause = FreeAds.PARK
                    adParked = true
                    return
                }
                // No question: the ticket came with the tap, as the pass has it.
                adsUsed += 1
                bump("ads", 1)
                expectTicket = true
            }
            // Every tap of this loop goes out only with the switch on, asked
            // again after the look -- dialogSettled takes a second and more,
            // and a tap the service holds back is a verification that says
            // "no effect", booked as a declined attempt, a card exhausted
            // or a party left (PLAN_RELEASE_1_3.md B4). Nothing that waits
            // tapless (a battle, the party search) asks it here.
            if (state != Dungeon.BATTLE && !stillOn()) break

            if (state == Dungeon.EXIT) {
                val kind = here.exitKind ?: "beenden"
                dismissConfirm(here, LEAVING)
                if (kind == "party") {
                    // OK leaves the party and returns to the list. This
                    // dungeon is done with that; searching on would be a loop.
                    log("  left the party, dungeon done")
                    noteSpent(index, label, ticketsSpent)
                    return
                }
                continue
            }

            if (state == Dungeon.BATTLE) {
                // A run no Attempt of this loop started: every Attempt waits
                // out its own run. On Network Defense Ops that is the run the
                // game starts by itself once Find a Party has found one (both
                // searches of the live log of 2026-09-23), and it takes a
                // ticket. Said with the counter either side since G1
                // (PLAN_DAILY_LOST_SECTOR_PRESETS.md), and booked since
                // 2026-10-01 as an Attempt's run is, on the same two
                // witnesses -- the counter read before the search and after
                // the run, and the Reward sheet: left out of the count, it let
                // a card whose budget was under what it held play one run too
                // many (G3 (1); PLAN_RELEASE_1_3.md B5). A run of this card,
                // won or lost, so one attempt toward Clear Previous Difficulty
                // as well, and a fight on the TODAY card.
                cardSelfStarted += 1
                val before = counterAtSearch
                // That run spends what the search read: it stands no more.
                counterAtSearch = null
                log("  a run no Attempt of this card started is under way -- counter before %s, %d of %d ticket%s booked"
                    .format(before ?: "unread", ticketsSpent, wanted, plural(wanted)))
                rewardSeen = false
                val back = waitDialogBack(battleTimeout)
                // The title or the download dialog in the run: its end was
                // not seen, and nothing is booked.
                if (handsOff) break
                attemptsMade += 1
                bump("fights", 1)
                val after = if (back == null) (if (before != null) listCounter(index, label) else null)
                            else counterNow(dialogSettled(2))
                bookRun(before, after)
                if (counterGaveUp) break
                continue
            }

            if (state == Dungeon.LIST) {
                // Once per run, not once per visit. The brake is for a card
                // the game keeps closing with nothing done, and "nothing done
                // since the last re-open" says exactly that; a card that
                // drops to the list after every lost run is not that card.
                // Live on LDPlayer 2026-09-29 09:03:32 and 09:04:59: two lost
                // runs of Fight! DemiDevimon ended on the list -- the wait's
                // tap high up, after a black screen that had stood 4 s, met
                // the panel coming back (the six lost runs of that pass with
                // no tap came back to the panel, two of the three with one to
                // the list) -- and the second found the card "opened once
                // more already", 1/2 left on it (notes/dungeons.md, "A card
                // is opened again once per run, not once per visit"). Every
                // re-open wants an attempt or a ticket since the last, and
                // both are bounded, so this cannot become a loop.
                val done = attemptsMade + ticketsSpent
                if (attemptsMade == 0 || done == reopenedAt) {
                    log("  back in the list, done (%s; %d of %d ticket%s booked)".format(
                        if (reopenedAt >= 0) "opened once more, and nothing done since"
                        else "no Attempt from here yet",
                        ticketsSpent, wanted, plural(wanted)))
                    noteSpent(index, label, ticketsSpent)
                    return
                }
                // Finishing an attempt sometimes drops the panel and leaves
                // the list showing, with tickets still on the card. Metal Sea
                // ended a live run that way with one attempt unspent. Open it
                // once more -- once, so a card the game keeps closing cannot
                // become a loop.
                //
                // In the card's own half of the list, which the list the
                // game hands back is not: it comes back scrolled to the top.
                // Measured on LDPlayer 2026-09-22, twice: Digifactory (bottom
                // half, second card) was opened at fy 0.361 after the scroll
                // and "once more" at 0.456 without one -- DemiDevimon's place
                // on a list at the top, a card with nothing left, whose
                // panel shows the ad button; the ad yielded nothing, and
                // Digifactory was booked as exhausted with a ticket still on
                // its card. Every bottom-half card could end that way, and
                // the top half was right by accident.
                reopenedAt = done
                val scrolled = scrollTo(label) ?: run {
                    log("  back in the list, which would not scroll to the card's half, done")
                    noteSpent(index, label, ticketsSpent)
                    return
                }
                val visible = listCards(scrolled)
                if (index >= visible.size) {
                    log("  back in the list, done")
                    noteSpent(index, label, ticketsSpent)
                    return
                }
                log("  back in the list, opening the card once more")
                tap(Dungeon.CARD_X, visible[index], labelOf(index, label))
                sleep(pauseLong)
                continue
            }

            if (state == Dungeon.REWARD) {
                // A sheet the waits below did not close -- one found at the
                // top of the loop. Read, then tapped away.
                log("  the Reward sheet is up, closing it")
                rewardSeen = true
                tap(0.5, NEUTRAL_TAP_Y, "close the Reward sheet")
                sleep(pauseShort)
                continue
            }

            if (state == Dungeon.UNKNOWN) {
                // Reward, result, or an intermediate screen. Not at once:
                // a screen between two known ones is left alone for
                // [unknownHold], as [waitDialogBack] leaves it -- a tap
                // outside the panel is the one thing that closes it, and on
                // the party panel that tap raises "Disband the party?",
                // which the loop answers with OK. A held look is not a step.
                val img = grab()
                // The game's title: the pass ends here, nothing tapped (B30).
                if (metTitle(img)) break
                // A run's own black or VS: waited on, never tapped, and the
                // screen after it gets its hold afresh (B6). Not a step while
                // it stands no longer than a run's wait -- past that it is no
                // transition, and the loop's ceiling counts it again.
                val kind = passing(img)
                if (kind != null) {
                    waitedOn(kind, saidOn)
                    unknownSince = null
                    val first = passingSince ?: now().also { passingSince = it }
                    if (now() - first < battleTimeout) steps -= 1
                    sleep(tick)
                    continue
                }
                passingSince = null
                val since = unknownSince ?: now().also { unknownSince = it }
                if (now() - since < unknownHold) {
                    steps -= 1
                    sleep(tick)
                    continue
                }
                // Tap high up, that accepts rewards and does not trigger a
                // party prompt.
                tap(0.5, NEUTRAL_TAP_Y, "neutral")
                // A tap re-arms the hold, as it does in [waitDialogBack]
                // (PLAN_DAILY_LOST_SECTOR_PRESETS.md G3 (3)): the clock had
                // started on the screen the tap closed, so the next unknown
                // look -- the panel coming back -- was tapped at once, outside
                // the panel, and on Network Defense Ops that is "leave the
                // party?" answered OK (notes/dungeons.md, "A tap on a screen
                // nobody reads waits for the next one to stand").
                unknownSince = null
                sleep(pauseShort)
                continue
            }

            val close = here.attempt
            if (close != null && isCloseButton(close)) {
                log("  reward window, closing")
                tap(close.fx, close.fy, "Close", here.anchor)
                sleep(pauseLong)
                continue
            }

            // After an ad, an Attempt button must appear. If the ad button
            // reappears instead, it did not yield a ticket.
            if (expectTicket) {
                expectTicket = false
                if (state == Dungeon.DIALOG_AD) adsHadNoEffect = true
            }

            if (state == Dungeon.DIALOG_PARTY && (here.partyVoll ?: 0) < 2) {
                // Without team-mates, Attempt does nothing. The dialog looks
                // identical with and without a party, both buttons sit at the
                // same place. It can only be told apart by the party slots,
                // measured standard deviation 0.0 when empty against 28 to 48
                // when occupied.
                if (partySearches >= 2) {
                    log("  no party found, moving on")
                    break
                }
                // A party found starts a run by itself (the BATTLE branch),
                // so no search goes out for a ticket the player did not
                // want spent: the budget is asked here as before Attempt.
                if (ticketsSpent >= wanted) {
                    log("  spent %d ticket%s, which is the limit".format(wanted, plural(wanted)))
                    break
                }
                log("  searching for a party, %d of 3 slots filled".format(here.partyVoll ?: 0))
                // The counter before a run the party may start by itself
                // (the BATTLE branch above says it).
                counterAtSearch = counterNow(here)
                val party = here.party!!
                if (!stillOn()) break
                tap(party.fx, party.fy, "Find a Party", here.anchor)
                cardSearches += 1
                // The search gets [partyWait], and the searching panel is
                // not tapped: the PC's two looks 4.5 s apart were never
                // measured against how long the game searches.
                val t0 = now()
                val found = waitForParty(partyWait)
                // Only a search that found nobody counts toward the two a
                // visit gets (PLAN_DAILY_LOST_SECTOR_PRESETS.md G3 (2),
                // PLAN_RELEASE_1_3.md B5): a party found is a run, its own
                // (BATTLE) or Attempt's, and a card whose party leaves after
                // every run needs a search before each one.
                val partyFound = found != null && (found.state == Dungeon.BATTLE ||
                    (found.state in DIALOGS && (found.partyVoll ?: 0) >= 2))
                if (!partyFound) partySearches += 1
                if (found == null) {
                    log("  no party after %.0f s".format(now() - t0))
                } else {
                    log("  the party panel changed after %.0f s: %s, %d of 3 slots filled; counter before the search %s"
                        .format(now() - t0, found.state, found.partyVoll ?: 0, counterAtSearch ?: "unread"))
                }
                continue
            }

            val attempt = here.attempt
            if (attempt != null) {
                if (ticketsSpent >= wanted) {
                    log("  spent %d ticket%s, which is the limit".format(wanted, plural(wanted)))
                    break
                }
                if (towardClear() < attemptsBeforeClear) {
                    // A lost run counts towards the switch to Clear Previous
                    // Difficulty, a won one does not ([countLostOnly], the
                    // player's rule of 2026-10-04; every attempt, won or
                    // lost, until then and on the quest loop's card still).
                    //
                    // Where the counter will not read now, the one read
                    // before Find a Party stands: between the two nothing
                    // spends a ticket but a run the game starts itself, and
                    // the BATTLE branch clears it for that. Live on LDPlayer
                    // 2026-09-29 09:14:46 the party had just filled the
                    // Network Defense Ops panel, the header read nothing, and
                    // the run was booked as lost with 2 -> 1 on the counter
                    // (notes/dungeons.md, "The counter read before the party
                    // search stands for the first run"). Why it did not read
                    // no frame says.
                    var before = counterNow(here)
                    if (before == null && counterAtSearch != null) {
                        before = counterAtSearch
                        log("  the counter does not read now; the one read before the party search, $before, stands")
                    }
                    counterAtSearch = null
                    if (before == 0) {
                        // Apocalymon Wall has no ad button, so at 0/2 its
                        // panel still offers Attempt, and a tap on it only
                        // raises a window to close. Live on LDPlayer
                        // 2026-09-23 that was two taps, two windows and 30 s
                        // per pass on a card with nothing left. Every other
                        // panel at 0 shows the ad button instead of Attempt.
                        log("  the panel says 0 tickets left, moving on")
                        exhausted.add(k)
                        break
                    }
                    if (!stillOn()) break
                    log("  attempt %d, %d of %d ticket%s spent".format(
                        attemptsMade + 1, ticketsSpent, wanted, plural(wanted)) +
                        if (countLostOnly) ", %d of %d failed before Clear Previous Difficulty"
                            .format(lostMade, attemptsBeforeClear) else "")
                    val t0 = now()
                    rewardSeen = false
                    tap(attempt.fx, attempt.fy, "Attempt", here.anchor)
                    bump("attempts", 1)
                    if (waitDialogGone(startTimeout)) {
                        attemptsMade += 1
                        noEffect = 0
                        val back = waitDialogBack(battleTimeout)
                        // The title or the download dialog in the run: its
                        // end was not seen, and nothing is booked (B30).
                        if (handsOff) break
                        if (back == null) {
                            // The panel did not come back: the list, a
                            // prompt, or the wait ran out. On the list the
                            // card's own badge is the counter's second look
                            // (listCounter); elsewhere the sheet is the one
                            // witness left, and the loop's top looks at what
                            // is there.
                            val after = if (before != null) listCounter(index, label) else null
                            bookRun(before, after)
                            if (counterGaveUp) break
                            continue
                        }
                        val duration = now() - t0
                        if (duration < minBattle) {
                            // Too short for a battle. The dialog was only
                            // briefly gone, e.g. because of a message. Not a
                            // battle, otherwise six missed clicks would look
                            // like six battles in the log.
                            log("  only %.0f s, that was no battle".format(duration))
                            bump("declined", 1)
                            break
                        }
                        bump("fights", 1)
                        log("  battle finished after %.0f s".format(duration))
                        // Apocalymon Wall ends every run in a Results window
                        // (Damage Record, Rank, Participation Rewards) with a
                        // narrow Close, and no Reward sheet at all. The wait
                        // above hands it back as a panel, and the counter
                        // crop hung from its Close reads nothing: live on
                        // LDPlayer 2026-09-23, two runs 2 -> unread, two
                        // tickets spent and booked as lost, and the pass
                        // parked. The window is closed first, as the loop's
                        // top would have, and the counter read off the panel
                        // under it.
                        var settled = dialogSettled(2)
                        if (isCloseButton(settled.attempt)) {
                            log("  a results window, closing it before the counter is read")
                            val close = settled.attempt!!
                            tap(close.fx, close.fy, "Close", settled.anchor)
                            sleep(pauseLong)
                            settled = dialogSettled(2)
                            // And the panel is waited for, untapped: Close
                            // leads through "Now Loading" first. Live on
                            // LDPlayer 2026-09-29 09:00:08 and 09:01:33 both of
                            // Apocalymon's counters were read on that screen
                            // ("Entering... 96.0%", the frames kept), two
                            // tickets booked as lost runs and the card left;
                            // the panel stood again within 7 s both times
                            // (notes/dungeons.md, "After Apocalymon's Close the
                            // counter waits for the panel").
                            val end = now() + startTimeout
                            while (settled.state !in DIALOGS && settled.state != Dungeon.LIST && now() < end) {
                                sleep(tick)
                                settled = dialogSettled(2)
                            }
                        }
                        val after = counterNow(settled)
                        bookRun(before, after)
                        if (counterGaveUp) break
                        continue
                    }
                    // The dialog stayed open. Either rejected, or the click fell
                    // inside an animation. The next pass will show which of the
                    // two, since then the ad button appears instead of Attempt.
                    log("  attempt had no effect")
                    bump("declined", 1)
                    noEffect += 1
                    if (noEffect >= 2) break
                    continue
                }

                // N attempts made (N lost, [countLostOnly]): the rest goes
                // to Clear Previous Difficulty.
                val made = if (countLostOnly) "%d failed attempt%s".format(lostMade, plural(lostMade))
                           else "%d attempt%s made".format(attemptsMade, plural(attemptsMade))
                val clear = here.clear
                if (clear == null) {
                    log("  $made and no Clear Previous Difficulty on this panel, moving on")
                    saveUnknown(grab(), "no_clear_previous")
                    break
                }
                if (saidOn.add("switch to Clear Previous Difficulty")) {
                    log("  $made -- the tickets still wanted go to Clear Previous Difficulty")
                }
                log("  Clear Previous Difficulty, %d of %d ticket%s spent".format(
                    ticketsSpent, wanted, plural(wanted)))
                val before = counterNow(here)
                if (!stillOn()) break
                rewardSeen = false
                tap(clear.fx, clear.fy, "Clear Previous Difficulty", here.anchor)
                // The sheet comes at once, with no prompt before it (measured
                // twice on LDPlayer 2026-09-23); the wait sees it, taps it
                // away and hands back the panel.
                sleep(pauseLong)
                val back = waitDialogBack(startTimeout)
                val after = if (back == null) null else counterNow(dialogSettled(2))
                val booked = book(before, after, CLEAR)
                if (booked == Booked.TICKET) {
                    ticketsSpent += 1
                    bump("cleared", 1)
                    continue
                }
                if (counterGaveUp) break
                if (booked == Booked.NONE && !clearRetried) {
                    // No sheet and the counter where it was: the tap fell
                    // into an animation. Once more, and once only.
                    log("  Clear Previous Difficulty had no effect, trying once more")
                    clearRetried = true
                    continue
                }
                log("  Clear Previous Difficulty did not spend a ticket, moving on")
                saveUnknown(grab(), "clear_no_effect")
                break
            }

            val ad = here.ad
            if (state == Dungeon.DIALOG_AD && ad != null) {
                if (!useAds) {
                    // The free ads not taken here (the Ad Rewards card's pick,
                    // or no Ad Skip Pass): the card is done for today as far
                    // as this skill goes, and is not opened again.
                    log("  no tickets left, and the free ads are not taken here (the Ad Rewards card)")
                    exhausted.add(k)
                    break
                }
                if (adsHadNoEffect) {
                    // An ad that yielded no ticket will not yield one next
                    // time either. The game then reports "Ad viewing limit
                    // reached". Without this rule the bot used to watch six
                    // ads in a row for nothing.
                    log("  ads no longer yield a ticket, moving on")
                    exhausted.add(k)
                    break
                }
                if (adsUsed >= adLimit) {
                    log("  no ads left, %d watched".format(adsUsed))
                    exhausted.add(k)
                    break
                }
                if (ticketsSpent >= wanted) {
                    // The limit, met on the last ticket the card had: no ad
                    // for a ticket nobody is going to spend.
                    log("  spent %d ticket%s, which is the limit".format(wanted, plural(wanted)))
                    break
                }
                if (!stillOn()) break
                log("  tickets empty, the free ad with the Ad Skip Pass")
                tap(ad.fx, ad.fy, "ad", here.anchor)
                // The pass taken for granted: the ticket comes with the tap.
                // Whether the game asked for an ad all the same is the next
                // look's to say ([adTapped]), and the ad is counted there.
                adTapped = true
                sleep(pauseLong)
                continue
            }

            log("  nothing to do in state $state")
            break
        }

        if (steps >= loops) log("  loop limit reached, moving on")
        noteSpent(index, label, ticketsSpent)
        returnToList()
        } finally {
            // A film button the pass took for granted, and no look after it:
            // the ad counts as the pass has it, so that a pass going on does
            // not ask the card for one more than it had.
            if (adTapped) {
                adsUsed += 1
                bump("ads", 1)
            }
            attemptsOn[pos] = attemptsMade
            lostOn[pos] = lostMade
            adsOn[pos] = adsUsed
        }
    }

    // ------------------------------------------------------------------
    // The daily dungeon (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.1, DL3)
    // ------------------------------------------------------------------
    /** [playDaily] where the page gives it minutes and nothing has ended the pass. */
    private fun playDailyIfSet() {
        if (dailyMinutes <= 0 || dailyOver || stopped || adParked || handsOff) return
        if (!stillOn()) return
        playDaily()
    }

    /**
     * The daily dungeon, attempted run after run for [dailyMinutes]: the
     * last card of the list's proved bottom, which [skipLast] leaves out of
     * the tickets' halves, opened, and its Attempt pressed until the minutes
     * are up. It costs nothing (the player's answer to question 4), has no
     * counter to book against, and is mostly lost -- the attempt is the
     * point. Measured on LDPlayer instance 0 on 2026-09-29 (DL1a, 4.1 point
     * 3 and 6; three runs of "Defense Type", level 100, all lost): the tap,
     * black, the VS screen, the ordinary battle with Give Up, black, "Now
     * Loading", for about a second the panel's prefab over the main screen
     * ("Title", level 99, three buttons Clear, Reset, Attempt), and the
     * panel again over the list at its top -- no window at all. From the tap
     * the panel was gone after 1.2 to 2.0 s and standing still again after
     * 13.2 to 15.4 s; the battle itself was 8.5 to 9.3 s. A won run, its
     * sheet and a level-up were not seen; they are played here the way the
     * tickets meet them (Dungeon.rewardSheet, a narrow Close) and closed and
     * not counted as anything but the run (question 8).
     *
     * Every action has its witness and its brake:
     *
     *   the card      tapped only on a list proved to be at its bottom
     *                 ([tapDailyCard]); the panel it opens is the one
     *                 [dailyAttempt] names on two frames running, else the
     *                 phase ends and the frame is kept (`daily_no_panel`)
     *   at MAX        the panel with its lone Reset ([dailyAtMax]) on two
     *                 frames running: nothing to attempt, the phase ends at
     *                 once, nothing tapped, no frame kept
     *   Attempt      only on that panel -- never on the prefab, which the
     *                 reader answers null on, and never its Reset, which
     *                 `recognise` calls `clear` (question 17); proved by the
     *                 panel going ([waitDialogGone]); twice without effect
     *                 ends the phase (`daily_no_effect`)
     *   the run       waited out as the tickets' is ([waitDialogBack]: no
     *                 tap in a battle, the sheet read and tapped away, a
     *                 screen nobody names tapped only after [unknownHold]);
     *                 back in under [minBattle] was no run, and twice so in
     *                 a row ends the phase
     *   back          [dailyBack]: the panel on two frames again, a window
     *                 with a narrow Close closed, the list -- the card opened
     *                 once more, once a run
     *   the clock     [now], asked before an Attempt and never inside a run:
     *                 the minutes are a floor, and the run that crosses them
     *                 is waited out (question 10); started at the first
     *                 Attempt, so the walk to the card costs none of them
     *   the switch    [stillOn], at the same place and nowhere else
     *
     * Counted: `daily` a run, `daily_won` one the Reward sheet came in,
     * `daily_lost` one that came back without it (3.5; the card shows the
     * first two). Ends where the tickets' cards end, on the list
     * ([returnToList]).
     */
    internal open fun playDaily() {
        // Its panel may be in front already ([playOn] after a pause).
        val open = !onPanel
        onPanel = false
        val minutes = dailyMinutes
        log("\ndaily dungeon: %d minute%s".format(minutes, plural(minutes)) +
            if (dailyPlayed > 0) ", %.0f s of them played before the pause".format(dailyPlayed) else "")
        dailyInFlight = true
        // On its panel after a pause ([resume]), the card is not opened again.
        var attempt = if (open) openDaily() else dailyBack(reopen = false)
        // The minutes this stretch has run, from its first Attempt, on top of
        // what the pass played before a pause ([dailyPlayed]): a pause is not
        // the player's minutes, and a pass that goes on after one plays what
        // is left of them, not all of them again (PLAN_RELEASE_1_3.md B4).
        var start: Double? = null
        fun playedBy(t: Double) = dailyPlayed + (start?.let { t - it } ?: 0.0)
        var attempts = 0
        var runs = 0
        var won = 0
        var noEffect = 0
        var noBattle = 0
        while (attempt != null) {
            // Between two runs, and only here: the switch, then the clock.
            if (!stillOn()) break
            val t0 = now()
            if ((start != null || dailyPlayed > 0) && playedBy(t0) >= minutes * 60.0) {
                log("  the minutes are up after %d run%s (%.0f s)".format(runs, plural(runs), playedBy(t0)))
                break
            }
            if (start == null) start = t0
            attempts += 1
            log("  attempt %d".format(attempts))
            rewardSeen = false
            if (!stillOn()) break
            tap(attempt.fx, attempt.fy, "Attempt", Dungeon.DAILY)
            if (!waitDialogGone(startTimeout)) {
                // The panel stayed: the tap fell into an animation, or the
                // game said no. Once more from a panel read again, and not
                // a third time.
                log("  attempt had no effect")
                bump("declined", 1)
                noEffect += 1
                if (noEffect >= 2) {
                    log("  the daily dungeon's Attempt had no effect twice, leaving it")
                    saveUnknown(grab(), "daily_no_effect")
                    break
                }
                attempt = dailyBack(reopen = false)
                continue
            }
            noEffect = 0
            waitDialogBack(battleTimeout)
            // The game went to its title, or asked to download its data, in
            // the run: its end was not seen, so it is counted as nothing.
            if (handsOff) break
            val duration = now() - t0
            val ran = duration >= minBattle
            if (ran) {
                noBattle = 0
                runs += 1
                bump("daily", 1)
                log("  battle finished after %.0f s".format(duration))
            } else {
                // The panel was only briefly gone -- a message, not a run.
                log("  only %.0f s, that was no battle".format(duration))
                bump("declined", 1)
                noBattle += 1
                if (noBattle >= 2) {
                    log("  no battle twice in a row, leaving the daily dungeon")
                    saveUnknown(grab(), "daily_no_battle")
                    break
                }
            }
            attempt = dailyBack(reopen = ran)
            if (ran) {
                // The sheet may come in the wait or after it; both set
                // [rewardSeen], and the panel standing again ends both. A
                // run whose way back met the title is lost only if the sheet
                // never came -- which the title does not say.
                if (rewardSeen) {
                    won += 1
                    bump("daily_won", 1)
                    log("  run won, the Reward sheet came")
                } else if (!handsOff) {
                    bump("daily_lost", 1)
                    log("  run lost, back without a Reward sheet")
                }
            }
        }
        dailyPlayed = playedBy(now())
        // A phase the switch cut short is the one to go on with; any other
        // end -- the minutes, MAX, a brake -- is the phase over for this pass.
        if (!on()) stillOn()
        dailyOver = !stopped
        dailyInFlight = stopped
        log("  daily dungeon: %d run%s, %d won".format(runs, plural(runs), won))
        if (!stopped && returnToList()) log("  back in the list")
    }

    /** To the daily dungeon's panel from the list: the card ([tapDailyCard]), then its panel ([dailyBack]). */
    private fun openDaily(): Dungeon.Button? {
        if (!tapDailyCard()) return null
        return dailyBack(reopen = false)
    }

    /**
     * The daily dungeon's card, tapped: the last card of the list at its
     * bottom, the seventh of [SkillSettings.DUNGEON_NAMES]. Only on a list
     * the scroll has proved to be at its bottom ([scrollBottom],
     * [Dungeon.listAtBottom]) -- a card's place is a card only under the
     * scroll it was counted at, and the list the game hands back after a run
     * is at its top (notes/dungeons.md, "The list the game hands back is not
     * the list the pass scrolled to"), where the last card is another
     * dungeon's with tickets on it.
     */
    private fun tapDailyCard(): Boolean {
        val img = scrollBottom() ?: run {
            log("  the list would not scroll to the bottom, leaving the daily dungeon")
            return false
        }
        val cards = listCardsWithSize(img)
        if (Dungeon.listAtBottom(cards) != true) {
            log("  the list is not at its bottom (%d cards), leaving the daily dungeon".format(cards.size))
            saveUnknown(img, "daily_no_card")
            return false
        }
        val index = cards.size - 1
        tap(Dungeon.CARD_X, cards[index].first, labelOf(index, BOTTOM))
        sleep(pauseShort)
        return true
    }

    /**
     * The daily panel standing, and its Attempt: [dailyAttempt] on two frames
     * running, at the same place (NOTES.md, "Never trust a single frame"; the
     * prefab stands a second before it, and the reader answers null there).
     * What stands between is dealt with by what it is:
     *
     *   the Reward sheet    read ([rewardSeen]) and tapped away at once
     *   a narrow Close      a window after a run ([isCloseButton]), closed
     *   another dialog      never tapped -- the prefab is one; waited on
     *   a prompt            Cancel: the panel is where this wants to be
     *   a battle            waited on, never tapped
     *   black, VS           a run's own transition ([passing]): waited on as
     *                       a battle, never tapped (PLAN_RELEASE_1_3.md B6)
     *   the title           the pass ends there, nothing tapped ([onTitle], B30)
     *   the list            left alone for [unknownHold], then the card
     *                       opened once more where [reopen] allows it
     *   the main screen     never tapped
     *   anything else       left alone for [unknownHold], then one tap high
     *                       up, and the hold again after every tap (the
     *                       level-up of a won run, if the game has one)
     *
     * Null, with the frame kept, when no panel stands within [dailyWait].
     */
    private fun dailyBack(reopen: Boolean): Dungeon.Button? {
        var reopens = if (reopen) 1 else 0
        var end = now() + dailyWait
        var last: Dungeon.Button? = null
        var held: String? = null
        var since = 0.0
        var taps = 0
        var lastMax: Dungeon.Button? = null
        val said = HashSet<String>()
        while (now() < end) {
            val img = grab()
            val panel = dailyAttempt(img)
            if (panel != null) {
                panelSeen = true
                held = null
                lastMax = null
                val before = last
                if (before != null && samePlace(before, panel)) return panel
                last = panel
                sleep(0.5 * patience)
                continue
            }
            last = null
            // The panel at its highest level: Reset alone, and no Attempt to
            // wait for (DL5, notes/dungeons.md, "At its highest level the
            // daily dungeon's panel has nothing to attempt"). Two frames
            // running at one place, as the Attempt; then the phase ends at
            // once, with nothing tapped and no frame kept -- it is the day's
            // answer, not a fault.
            val max = dailyAtMax(img)
            if (max != null) {
                panelSeen = true
                held = null
                val before = lastMax
                if (before != null && samePlace(before, max)) {
                    log("  the daily dungeon is at its highest level (MAX: only Reset, no Attempt) -- nothing to attempt")
                    return null
                }
                lastMax = max
                sleep(0.5 * patience)
                continue
            }
            lastMax = null
            val info = recognise(img)
            val state = info.state
            if (state == Dungeon.REWARD) {
                held = null
                val first = !rewardSeen
                rewardSeen = true
                // Read, and with the main switch off left standing: the run
                // is won, the pass stops here, and one that goes on after the
                // pause takes it up ([playOn]).
                if (!on()) return null
                if (first) log("  the Reward sheet is up, closing it")
                tap(0.5, NEUTRAL_TAP_Y, "close the Reward sheet")
                sleep(tick)
                continue
            }
            if (state == Dungeon.EXIT) {
                held = null
                if (!on()) return null
                dismissConfirm(info, null)
                if (handsOff) return null
                continue
            }
            val close = info.attempt
            if (state in DIALOGS && close != null && isCloseButton(close)) {
                held = null
                if (!on()) return null
                log("  a window after the run, closing it")
                tap(close.fx, close.fy, "Close", info.anchor)
                sleep(pauseLong)
                continue
            }
            if (state in DIALOGS || state == Dungeon.BATTLE) {
                held = null
                sleep(tick)
                continue
            }
            // The title ends the phase and the pass, nothing tapped (B30);
            // black and VS are waited on as a battle is (B6), see [waitDialogBack].
            if (metTitle(img)) return null
            val kind = passing(img)
            if (kind != null) {
                waitedOn(kind, said)
                held = null
                sleep(tick)
                continue
            }
            // The list and a screen nobody names wait out the hold first,
            // each on its own clock: what stands between two known screens
            // is shorter (UNKNOWN_HOLD).
            if (held != state) {
                held = state
                since = now()
            }
            if (now() - since < unknownHold) {
                sleep(tick)
                continue
            }
            // With the switch off nothing below is tapped: the list and a
            // screen nobody reads are waited on, as a battle is, until the
            // wait runs out -- or the switch is back, and then the hold starts
            // again, as in [waitDialogBack]: what stood through the pause gets
            // its [unknownHold] after it before a tap.
            if (!on()) {
                held = null
                sleep(tick)
                continue
            }
            if (state == Dungeon.LIST) {
                if (reopens <= 0) {
                    log("  in the list and not on the daily dungeon's panel, leaving it")
                    saveUnknown(img, "daily_no_panel")
                    return null
                }
                reopens -= 1
                log("  back in the list, opening the daily dungeon once more")
                if (!tapDailyCard()) return null
                held = null
                end = now() + dailyWait
                continue
            }
            if (autoButton(img) != null) {
                // The main screen with nothing over it: nothing of this
                // skill's to close, and a tap high up there is on the stage.
                sleep(tick)
                continue
            }
            if (taps == 0) log("  a screen nothing here reads has stood %.0f s, tapping high up"
                                   .format(now() - since))
            tap(0.5, NEUTRAL_TAP_Y, "neutral")
            taps += 1
            held = null
            sleep(tick)
        }
        if (!on()) return null
        log("  the daily dungeon's panel did not stand within %.0f s, leaving it".format(dailyWait))
        saveUnknown(grab(), "daily_no_panel")
        return null
    }

    /** Two reads of one button at the same place, to the thousandth ([dialogSettled]'s fingerprint). */
    private fun samePlace(a: Dungeon.Button, b: Dungeon.Button): Boolean =
        Py.roundInt(a.fx * 1000) == Py.roundInt(b.fx * 1000) &&
            Py.roundInt(a.fy * 1000) == Py.roundInt(b.fy * 1000)

    // ------------------------------------------------------------------
    // What a ticket is: two witnesses
    // ------------------------------------------------------------------
    /** What [book] made of one tap on Attempt or Clear Previous Difficulty. */
    internal enum class Booked { TICKET, NONE, CONTRADICTION, UNREADABLE }

    /**
     * Did that run spend a ticket? Asked of two witnesses that do not know
     * of each other: the panel's counter before and after ([counterNow]),
     * and whether the Reward sheet came up in between ([rewardSeen]). A won
     * run and Clear Previous Difficulty take one off the counter and raise
     * the sheet; a lost run does neither -- no defeat window at all, the
     * battle goes black, "Now Loading", and the panel is back with the
     * counter where it was (measured on LDPlayer 2026-09-23, twice).
     *
     *   counter   sheet       booked
     *   -1        seen        a ticket
     *   -1        not seen    a ticket, and the log says the sheet was missed
     *   same      not seen    a lost run (after Attempt), no effect (after Clear)
     *   same      seen        nothing, and the frame is kept: the witnesses disagree
     *   unread    seen        a ticket, and the log says the counter was not read
     *   unread    not seen    a lost run; the frame is kept, and a second in a
     *                         row on the same card ends that card -- what it
     *                         spends cannot be counted, and the next card can
     */
    internal fun book(before: Int?, after: Int?, what: String): Booked {
        val seen = rewardSeen
        val moved = if (before != null && after != null) before - after else null
        val said = "counter %s -> %s, Reward sheet %s".format(
            before ?: "unread", after ?: "unread", if (seen) "seen" else "not seen")
        if (moved != null && moved > 0) {
            unreadableInARow = 0
            if (moved != 1) {
                // One run takes one ticket. Two gone is a counter read wrong
                // on one side, or something else spent one in between.
                log("  the counter moved by $moved in one run ($said)")
                saveUnknown(grab(), "counter_jump")
            }
            log(if (seen) "  a ticket spent ($said)"
                else "  a ticket spent ($said) -- the sheet was not seen")
            bump("tickets", 1)
            return Booked.TICKET
        }
        if (moved == 0) {
            unreadableInARow = 0
            if (seen) {
                log("  the sheet was seen and the counter did not move ($said) -- no ticket booked")
                saveUnknown(grab(), "sheet_without_ticket")
                return Booked.CONTRADICTION
            }
            if (what == ATTEMPT) {
                log("  run lost, no ticket spent ($said)")
                bump("lost", 1)
            } else {
                log("  nothing happened ($said)")
            }
            return Booked.NONE
        }
        // The counter was not read on one side, or it went up.
        if (seen) {
            unreadableInARow = 0
            log("  a ticket spent ($said) -- the counter could not be read")
            bump("tickets", 1)
            return Booked.TICKET
        }
        unreadableInARow += 1
        log("  the counter could not be read and no sheet was seen ($said) -- " +
            if (what == ATTEMPT) "counted as a lost run" else "counted as nothing")
        saveUnknown(grab(), "counter_unreadable")
        if (what == ATTEMPT) bump("lost", 1)
        if (unreadableInARow >= 2) {
            // Per card: two runs a card is the ceiling on tickets nobody
            // counts, and no card pays for the one before it.
            counterGaveUp = true
            log("  the counter on this card could not be read twice in a row, leaving it")
        }
        return Booked.UNREADABLE
    }

    /**
     * The panel's counter, or null. Read on two frames and only believed
     * when they agree (NOTES.md, "Never trust a single frame"): the panel
     * this is asked on has stood still for [dialogSettled], and a counter
     * that differs between two looks at it is not one to book against.
     */
    internal open fun counterNow(info: Dungeon.Recognition): Int? {
        if (info.state !in DIALOGS) return null
        val button = info.attempt ?: info.ad ?: return null
        val first = panelCounter(grab(), button, info.anchor) ?: return null
        val second = panelCounter(grab(), button, info.anchor) ?: return null
        return if (first == second) first else null
    }

    /**
     * One look at the panel's counter: over its button, or -- on the party
     * panel of Network Defense Ops, which has none there -- above the panel
     * ([Dungeon.HEADER_COUNTER]). Asked only of a frame [counterNow] has
     * already found a panel on; on the main screen the header crop reads
     * other things (the dungeon oracle, `header_tickets`).
     */
    private fun panelCounter(img: Mat, button: Dungeon.Button, anchor: Dungeon.Anchor): Int? =
        panelTickets(img, button, anchor) ?: headerTickets(img)

    /**
     * The card's counter on the list, when a run ends there rather than on
     * its panel -- Network Defense Ops does, measured live on LDPlayer
     * 2026-09-23 (corpus/dungeon/panel_party_netdef_155153 and the list
     * after it). The list the game hands back is at the top, so the card's
     * half is scrolled to first ([scrollTo], as the re-open does), and the
     * badge is believed only when two looks agree. Null anywhere but the list.
     */
    internal open fun listCounter(index: Int, label: String): Int? {
        if (recognise(grab()).state != Dungeon.LIST) return null
        val scrolled = scrollTo(label) ?: return null
        fun look(img: Mat): Int? {
            val cards = listCardsWithSize(img)
            if (index >= cards.size) return null
            val (fy, fh) = cards[index]
            return cardBudget(img, fy, fh).tickets
        }
        val first = look(scrolled) ?: return null
        sleep(pauseShort)
        val second = look(grab()) ?: return null
        return if (first == second) first else null
    }

    /** How many looks one card may take, for [wanted] tickets. See [maxLoops]. */
    internal fun loopsFor(wanted: Int): Int =
        min(maxLoops, 3 * (attemptsBeforeClear + wanted) + 6)

    private fun plural(n: Int) = if (n == 1) "" else "s"

    // ------------------------------------------------------------------
    // Waiting, and what the waits are evidence of
    // ------------------------------------------------------------------
    /**
     * A frame the screen has stopped moving in.
     *
     * The pre-check reads digits off the list, and digits read while the
     * list is still gliding are digits read from a smear. Two frames that
     * match is the proof it has come to rest, and [MOVING_SHARE] is the
     * threshold for "did anything at all move", which is exactly the
     * question here. A screen that never settles returns its last frame
     * rather than waiting for ever.
     */
    internal open fun settledFrame(timeout: Double = 2.5): Mat {
        var previous = grab()
        val end = now() + timeout
        while (now() < end) {
            sleep(0.15)
            val current = grab()
            if (sameScreen(previous, current, MOVING_SHARE)) return current
            previous = current
        }
        return previous
    }

    /** `dungeon._same_screen`, which is [Dungeon.sameScreen] now. */
    private fun sameScreen(before: Mat?, after: Mat?, minShare: Double): Boolean =
        Dungeon.sameScreen(before, after, minShare)

    /**
     * Wait until the dialog disappears. This is the evidence that a click
     * had an effect: measuring the time until return instead caught the
     * dialog while it had not actually gone, which read as 0.1 seconds and
     * the false report that no battle had happened.
     */
    internal open fun waitDialogGone(timeout: Double = 8.0): Boolean {
        val end = now() + timeout
        while (now() < end) {
            if (recognise(grab()).state !in DIALOGS) return true
            sleep(0.4)
        }
        return false
    }

    /**
     * Wait until the dialog has actually settled.
     *
     * After a battle a return animation runs for 2 to 3 seconds. The dialog
     * is already visible during that but not yet interactive, and a click on
     * Attempt then has no effect -- which the bot used to read as a rejection
     * for want of tickets. "Settled" means several consecutive frames show
     * the same state at the same button position: a single frame is no proof.
     */
    internal open fun dialogSettled(tries: Int = 3, pause: Double? = null): Dungeon.Recognition {
        val wait = pause ?: (0.5 * patience)
        var previous: Triple<String, Int?, Int?>? = null
        var sameCount = 0
        for (n in 0 until tries * 4) {
            val info = recognise(grab())
            // `round(fy, 3)` as a whole number of thousandths: the same
            // fingerprint, without a decimal rounding rule in the middle of
            // an equality test.
            val fingerprint = Triple(info.state,
                                     info.attempt?.let { Py.roundInt(it.fy * 1000) },
                                     info.ad?.let { Py.roundInt(it.fy * 1000) })
            if (fingerprint == previous) {
                sameCount += 1
                if (sameCount >= tries - 1) return info
            } else {
                sameCount = 0
                previous = fingerprint
            }
            sleep(wait)
        }
        return recognise(grab())
    }

    /**
     * Wait until the dialog is back, tapping away rewards along the way.
     *
     * No tapping during a battle, that could hit Give Up. Outside a battle a
     * tap accepts the reward, which also applies after the ad button, since
     * a reward window appears there too. Nor on the run's own black or VS
     * ([passing], PLAN_RELEASE_1_3.md B6), which are waited on as a battle
     * is; and the game's title ends the wait and the pass ([onTitle], B30).
     */
    internal open fun waitDialogBack(timeout: Double): Dungeon.Recognition? {
        val end = now() + timeout
        var taps = 0
        var unknownSince: Double? = null
        val said = HashSet<String>()
        while (now() < end) {
            val img = grab()
            val info = recognise(img)
            if (info.state in DIALOGS) {
                // Close windows are no longer tapped here, the main loop does
                // that. Otherwise this would never return such a window and
                // the caller would check against nothing.
                return info
            }
            if (info.state == Dungeon.BATTLE) {
                unknownSince = null
                sleep(tick)
                continue
            }
            if (info.state == Dungeon.REWARD) {
                unknownSince = null
                // Read first, then tapped: the sheet is the second witness of
                // a ticket spent ([book]), and it stands until it is tapped.
                // With the main switch off it is read and not tapped: the run
                // is over and won, the sheet is what says so, and it stands
                // where the pause found it -- handed back, so that the run is
                // booked on it and the pass stops there (PLAN_RELEASE_1_3.md
                // B4). A pass that goes on after the pause taps it away first.
                if (!on()) {
                    rewardSeen = true
                    log("  the Reward sheet is up -- the main switch is off, it stays")
                    return info
                }
                if (!rewardSeen) log("  the Reward sheet is up, closing it")
                rewardSeen = true
                tap(0.5, NEUTRAL_TAP_Y, "close the Reward sheet")
                sleep(tick)
                continue
            }
            if (info.state == Dungeon.EXIT) {
                // The party prompt arises precisely from tapping somewhere
                // while a party exists. After OK the dungeon is left, so stop.
                // Said, because it costs the party (notes/dungeons.md, "A tap
                // on a screen nobody reads waits for the next one to stand").
                // With the switch off nothing here tapped, and nothing answers
                // it: the prompt is handed back as it stands.
                if (!on()) return info
                log("  a prompt came up in the wait for the panel, after %d tap%s high up"
                    .format(taps, plural(taps)))
                dismissConfirm(info, LEAVING)
                return null
            }
            if (info.state == Dungeon.LIST) return null
            // The game's title: the run's end will not come, and a tap there
            // is "Touch To Start" (PLAN_RELEASE_1_3.md B30).
            if (metTitle(img)) return null
            // A run's own transition, black or VS, is waited on as a battle
            // is and never tapped, and the screen after it starts the hold
            // afresh (PLAN_RELEASE_1_3.md B6): live on LDPlayer 2026-09-29
            // 09:04:54 the tap after the hold went out on a black frame that
            // had stood 4 s and landed on the panel coming back.
            val kind = passing(img)
            if (kind != null) {
                waitedOn(kind, said)
                unknownSince = null
                sleep(tick)
                continue
            }
            // Not at once: see UNKNOWN_HOLD. A screen between two known ones
            // is left alone until it has outstayed every transition measured.
            val since = unknownSince ?: now().also { unknownSince = it }
            if (now() - since < unknownHold) {
                sleep(tick)
                continue
            }
            // No tap with the main switch off: the run's end is waited for as
            // a battle is -- a lost run comes back to the panel by itself,
            // and a won one to its sheet, both of which end this. And the
            // hold starts again when the switch is back: a screen that stood
            // through the pause is looked at for [unknownHold] after it before
            // it is tapped, the second look the director gives a screen it
            // does not know after a pause. Live on LDPlayer 2026-09-30
            // 22:55:03 the daily dungeon's won sheet, which no reader names,
            // came up as the switch went off, "had stood 36 s" when it came
            // back and was tapped in the same second (PLAN_RELEASE_1_3.md B4;
            // notes/director.md, "Every task the switch paused goes on where
            // it stands").
            if (!on()) {
                unknownSince = null
                sleep(tick)
                continue
            }
            if (taps > 0 && taps % 4 == 0) {
                back()
            } else {
                // Tap high up, outside any dialog. A tap in the middle of the
                // screen can trigger a confirmation prompt; with a party it
                // acts like the back key. Still tapped on a screen no reader
                // knows -- a reward of another kind, the loading screen -- but
                // said once, so that a tap nobody could account for is not a
                // silent one.
                if (taps == 0) log("  a screen nothing here reads has stood %.0f s, tapping high up"
                                       .format(now() - since))
                tap(0.5, NEUTRAL_TAP_Y, "neutral, accept the reward")
            }
            taps += 1
            // A tap re-arms the hold. The screen after the one a tap closed
            // is as unknown as the one before it, and the hold's clock had
            // started on that one, so the next look tapped at once -- a
            // second later, into the panel coming back. On Network Defense
            // Ops that tap is outside the panel and raises "leave the
            // party?", which is answered OK below: live on LDPlayer
            // 2026-09-23 15:52:24 to :28, one run of that card's five, whose
            // Reward sheet no reader names (its blue 0.184 of the band against
            // SHEET_SHARE_MIN 0.37, today's readers on that day's frame). The
            // card lost its party, and after the run the game had started by
            // itself, with no Attempt counted, the list read as "nothing done
            // here" and the card was over after one run of five
            // (notes/dungeons.md, "A tap on a screen nobody reads waits for
            // the next one to stand"). So every tap wants a screen that has
            // stood [unknownHold] since the last one.
            unknownSince = null
            sleep(tick)
        }
        return null
    }

    /**
     * Wait for the party search to end: the panel with two or more slots
     * filled, or any screen that is neither the searching panel nor
     * unknown. Nothing is tapped meanwhile -- what the panel looks like
     * while the game searches is unmeasured, and a tap outside it is the
     * one thing that closes it (notes/dungeons.md, "A swipe is proved, not assumed"). Null when the
     * wait ran out, or the main switch went off.
     */
    internal open fun waitForParty(timeout: Double): Dungeon.Recognition? {
        val end = now() + timeout
        while (now() < end) {
            if (!on()) return null
            val info = recognise(grab())
            val searching = info.state == Dungeon.UNKNOWN ||
                (info.state == Dungeon.DIALOG_PARTY && (info.partyVoll ?: 0) < 2)
            if (!searching) return info
            sleep(tick)
        }
        return null
    }

    /** Narrow, centred button, so Close and not Attempt. */
    internal open fun isCloseButton(button: Dungeon.Button?): Boolean =
        button != null && button.fw <= CLOSE_W_MAX

    // ------------------------------------------------------------------
    // Getting about
    // ------------------------------------------------------------------
    /**
     * Open the dungeon list and confirm that it is open. Without the
     * confirmation this would tap blindly somewhere after a failed click on
     * the tab, which is more dangerous in the menu than in the minigame.
     */
    internal open fun openList(): Boolean {
        if (recognise(grab()).state == Dungeon.LIST) {
            log("already in the list")
            return true
        }
        if (!stillOn()) return false
        tap(NAV_DUNGEON.first, NAV_DUNGEON.second, "dungeon tab")
        sleep(pauseLong)
        for (n in 0 until 6) {
            if (recognise(grab()).state == Dungeon.LIST) {
                log("list is open")
                return true
            }
            sleep(pauseShort)
        }
        log(("list not recognised. Am I on the main screen, and does the dungeon tab " +
            "really sit at rel. %.3f, %.3f?").format(NAV_DUNGEON.first, NAV_DUNGEON.second))
        return false
    }

    /**
     * Back to the list, via the tab if necessary. Without this the bot used
     * to get stuck somewhere after a dialog and skip the following cards.
     */
    internal open fun returnToList(tries: Int = 5): Boolean {
        // back() answers false when the press raised the exit prompt instead
        // of closing anything. Some panels -- the dungeon's own, measured --
        // do not handle the back key at all, and repeating it there only
        // produced the exit prompt three more times in a live run.
        var backAllowed = true
        var neutralTried = false
        // Whether the frame before this one showed a dungeon panel. That is
        // the whole evidence for answering the prompt with OK, so it is
        // tracked rather than assumed: a prompt already on screen when this
        // was called is none of its doing and gets Cancel.
        var panelBefore = false
        // The windows the game puts over the list after its daily reset
        // (PLAN_RELEASE_1_3.md B44): taken one by one before anything here
        // taps, and counted apart from [tries], which seven of them in a row
        // used up on 2026-10-01 ([closeResetWindow]).
        val reset = ResetWindows()
        var i = 0
        while (i < tries) {
            if (handsOff) return false
            // Part of the way home, and asked like it: with the switch off
            // the panel stays where the pause found it ([Stays]).
            if (stays.now()) return false
            val img = grab()
            val info = recognise(img)
            if (info.state == Dungeon.LIST) return true
            if (info.state == Dungeon.EXIT) {
                dismissConfirm(info, if (panelBefore) LEAVING else null)
                panelBefore = false
                i += 1
                continue
            }
            // The tap high up below would be "Touch To Start" there (B30).
            if (info.state !in DIALOGS && metTitle(img)) return false
            if (closeResetWindow(img, info, reset)) {
                panelBefore = false
                continue
            }
            i += 1
            panelBefore = info.state in DIALOGS
            if (panelBefore) panelSeen = true
            if (backAllowed && i <= tries - 2 && info.state in DIALOGS) {
                // A dungeon panel is what is open here, so the prompt the
                // back key raises is the dungeon's own and OK is the way out.
                backAllowed = back(wantOk = LEAVING)
            } else if (!neutralTried) {
                // A tap high up, outside whatever panel is in front. In the
                // live run the dungeon's own panel answered the back key with
                // the exit prompt and ignored the dungeon tab, and this is the
                // one remaining way out that cannot do harm.
                log("    tapping high up, outside the panel")
                tap(0.5, NEUTRAL_TAP_Y, "neutral")
                neutralTried = true
            } else {
                tap(NAV_DUNGEON.first, NAV_DUNGEON.second, "dungeon tab")
            }
            sleep(pauseLong)
        }
        log("  could not find the way back to the list")
        return false
    }

    /** What one way back to the list has met of the reset's windows ([closeResetWindow]). */
    internal class ResetWindows {
        /** Windows closed: OKs, taps beside, taps outside. */
        var closed = 0
        /** Looks spent on them, closing or waiting. */
        var looks = 0
        /** One of them was met: a dialog after it is the reset's, not the dungeon's panel. */
        var seen = false
        /** The OK the look before read on an announcement, for the second look. */
        var lastOk: Dungeon.Button? = null
        /** Looks in a row at nothing read since the last window closed. */
        var waits = 0
    }

    /** The OK of one of the game's announcements, or null ([Startup.announcement]); a seam for the tests. */
    internal open fun announcement(img: Mat): Dungeon.Button? = Startup.announcement(img)

    /** The Idle Rewards window, or null ([Quest.idleWindow]); a seam for the tests. */
    internal open fun idleWindow(img: Mat): Quest.IdleWindow? = Quest.idleWindow(img)

    /**
     * The windows the game puts up over the dungeon list after its daily
     * reset, closed one at a time, each the way it closes and nothing else
     * of it tapped (PLAN_RELEASE_1_3.md B44; notes/dungeons.md, "After the
     * day's reset the game stacks its news over the list, and the way back
     * closes them one by one"). On 2026-10-01 at 08:44:58, after the first
     * run past 08:00 on instance 1, they came once the panel was left --
     * "New Buddy Added" twice, "New Overdrive Added", Notices, a login bonus
     * under "Camp Wars Support Campaign!" and Idle Rewards, six in frames and
     * seven by the count of that morning's pass. The way back took the first
     * for a battle (recognise reads its OK as Give Up), tapped high up and
     * the dungeon tab three times, and gave up.
     *
     *   an announcement     its OK ([announcement]) on the second look that
     *                       reads it at the same place -- each grows in for
     *                       about a second, and that morning four taps closed
     *                       two --, never the box "Don't show again today"
     *   Idle Rewards        a tap beside it, IdleSkill.CLOSE_SPOT, where
     *                       that task closes it: nothing claimed, no Extra
     *                       Rewards, the rewards stay for Idle Rewards
     *   a dialog after one  of them (Notices, the login bonus): a tap high
     *                       up, outside it, which the laboratory measured
     *                       closing Notices (startup.py, the dead spot); not
     *                       the back key, which on the list behind it asks
     *                       "Return to the title screen?", a prompt whose OK
     *                       looks like the leave prompt's (dismissConfirm)
     *   anything unread     after one of them: waited on a beat, the next
     *                       window coming in, RESET_SETTLE looks at most
     *
     * True where this look was one of them, taken or waited on; false hands
     * the look to the way back as before. RESET_WINDOWS_MAX closes and
     * RESET_LOOKS_MAX looks at most, then the way back goes on as before.
     */
    internal open fun closeResetWindow(img: Mat, info: Dungeon.Recognition, reset: ResetWindows): Boolean {
        if (reset.closed >= RESET_WINDOWS_MAX || reset.looks >= RESET_LOOKS_MAX) return false
        val ok = announcement(img)
        if (ok != null) {
            reset.waits = 0
            reset.looks += 1
            val before = reset.lastOk
            reset.lastOk = ok
            if (!reset.seen) log("  the game's news after its reset is over the list -- closing it with OK")
            reset.seen = true
            if (before == null || abs(before.fx - ok.fx) > RESET_OK_SAME || abs(before.fy - ok.fy) > RESET_OK_SAME) {
                // The first look at it: it may still be coming in.
                sleep(pauseShort)
                return true
            }
            if (!stillOn()) return true
            tap(ok.fx, ok.fy, "OK of the news", Startup.ANNOUNCE_ANCHOR)
            reset.closed += 1
            reset.lastOk = null
            sleep(pauseLong)
            return true
        }
        reset.lastOk = null
        val idle = idleWindow(img)
        if (idle != null) {
            reset.waits = 0
            reset.looks += 1
            reset.seen = true
            if (!stillOn()) return true
            log("  Idle Rewards is over the list -- closing it beside the window, nothing claimed")
            tap(IdleSkill.CLOSE_SPOT[0], IdleSkill.CLOSE_SPOT[1], "beside Idle Rewards", idle.anchor)
            reset.closed += 1
            sleep(pauseLong)
            return true
        }
        if (!reset.seen) return false
        if (info.state in DIALOGS) {
            reset.waits = 0
            reset.looks += 1
            if (!stillOn()) return true
            log("    another of the reset's windows (${info.state}) -- tapping high up, outside it")
            tap(0.5, NEUTRAL_TAP_Y, "neutral, outside the reset's window")
            reset.closed += 1
            sleep(pauseLong)
            return true
        }
        if (reset.waits < RESET_SETTLE) {
            reset.waits += 1
            reset.looks += 1
            sleep(pauseShort)
            return true
        }
        return false
    }

    /**
     * Android back key. Only pressed while a dialog is actually open: with
     * none open the game answers "Return to the title screen?", and OK on
     * that throws the session away.
     *
     * [wantOk] says what the caller was trying to do, and only a caller that
     * was closing a dungeon panel may pass one. See [dismissConfirm].
     */
    internal open fun back(onlyIfDialog: Boolean = true, wantOk: String? = null): Boolean {
        if (handsOff || !on()) return false
        if (onlyIfDialog) {
            val here = recognise(grab())
            val state = here.state
            if (state == Dungeon.LIST || state == Dungeon.UNKNOWN || state == Dungeon.BATTLE) {
                log("    no dialog open, back key not pressed")
                return false
            }
            // On the game's download dialog the back key is its Cancel.
            if (state == Dungeon.EXIT && here.exitKind == Dungeon.KIND_DOWNLOAD) {
                dismissConfirm(here, wantOk)
                return false
            }
        }
        try {
            cap.back()
            sleep(pauseShort)
        } catch (err: Exception) {
            log("    back key failed: ${err.message}")
            return false
        }
        // Check whether we ended up in the exit confirmation.
        val info = recognise(grab())
        if (info.state == Dungeon.EXIT) {
            dismissConfirm(info, wantOk)
            return false
        }
        return true
    }

    /**
     * Leave a confirmation dialog, differently depending on its kind. OK is
     * pressed for exactly one of the three, and only when the caller says
     * what it was doing:
     *
     *   grey Cancel            "Exit the game?" -- Cancel, always.
     *   grey, two lines        the game's download (Dungeon.KIND_DOWNLOAD)
     *                          -- nothing: its Cancel ends the game too, the
     *                          pass ends and the director taps its OK
     *                          ([downloadAsked]).
     *   pink, [wantOk] set     the dungeon's own prompt -- OK, which is the
     *                          only way out of it. Cancel leaves the caller
     *                          stuck in it.
     *   pink, [wantOk] null    could be "Return to the title screen?", so
     *                          Cancel.
     *
     * The pink ones cannot be told apart from the picture, measured identical
     * to the pixel, so the caller's intent is the whole of the evidence.
     * Being wrong in the cautious direction costs a Cancel that was not
     * needed; being wrong the other way costs the session.
     */
    internal open fun dismissConfirm(info: Dungeon.Recognition? = null, wantOk: String? = null) {
        val here = info ?: recognise(grab())
        if (here.state != Dungeon.EXIT) return
        // recognise returns EXIT only with the button; a frame that somehow
        // did not would be a crash in dungeon.py and is nothing here.
        val ok = here.exitOk ?: return
        val kind = here.exitKind ?: "beenden"
        if (kind == Dungeon.KIND_DOWNLOAD) {
            // The game's download dialog, not a prompt of any kind this
            // answers: Cancel ends the game, and OK is the director's, in one
            // place (DirectorLoop.download). Nothing is tapped; the pass ends
            // here and hands the screen back ([downloadAsked]).
            if (!downloadAsked) {
                saveUnknown(grab(), "download")
                log("  the game asks to download its data -- leaving it to the director, nothing tapped")
            }
            downloadAsked = true
            if (parkedBecause == null) parkedBecause = DOWNLOAD_WHY
            return
        }
        // No answer with the main switch off: the service would hold the tap
        // back, and the prompt stays where the pause found it.
        if (!on()) return
        bump("exit_caught", 1)
        if (kind == "party" && wantOk != null) {
            log("  $wantOk via OK")
            tap(ok.fx, ok.fy, "OK, leave the party", here.anchor)
        } else {
            if (kind == "party") {
                log("  an in-game prompt, but nothing here was leaving a dungeon -- Cancel")
            } else {
                // Keep the frame. This is the prompt whose OK ends the
                // session, so any surprise about where it came from is worth
                // being able to look at afterwards.
                saveUnknown(grab(), "confirm_beenden")
                log("  exit dialog, leaving via Cancel")
            }
            // Cancel sits mirrored relative to OK.
            val cancelX = if (ok.fx > 0.5) 1.0 - ok.fx else Dungeon.POS_EXIT_CANCEL
            tap(cancelX, ok.fy, "Cancel", here.anchor)
        }
        sleep(pauseLong)
    }

    /**
     * Leave the game on its main screen. True if it is showing.
     *
     * Two things it deliberately does not do. It is not gated on the main
     * switch: by the time this runs that switch is often already off, that
     * being one of the ways a pass ends, and a gate would skip the one step
     * whose whole point is where the game is left. It is bounded instead.
     * And it never taps a position -- the nav bar is drawn only on the list
     * side of the game, so if the globe is not on the frame the answer is to
     * get back to the list and look again.
     *
     * What it checks after pressing is [autoButton], not "the globe is gone":
     * independent evidence that the main screen is in front and clear.
     */
    /**
     * The chain's second hand (Skill.leave): [goHome], `run`'s own way out,
     * aimed with the frame in front -- a pass that never began has no
     * origin of its own to aim with.
     */
    override fun leave(): Boolean {
        val img = try {
            grab()
        } catch (e: CaptureError) {
            log("no frame to leave the dungeon list from: ${e.message}")
            return false
        }
        val r = Dungeon.gameRect(img)
        // The headroom as well: from a panel ([leavesFrom]) this way answers
        // the prompt its back key raises, and that OK is tapped in the
        // middle's rectangle, which over the ceiling stands half the headroom
        // higher than the bottom's ([tap]). With the last pass's headroom, or
        // none, it went out 90 rows under the OK at 1080 x 2520
        // (DungeonPanelHomeFlowTest, PLAN_ABSCHLUSS_1_3.md A5i).
        room = Dungeon.headroom(img)
        img.release()
        origin = r.x0 to r.y0
        device = r.gw to r.gh
        stays.reset()
        // A title, an ad or the download dialog the last pass met is asked
        // again on the frames of this way: the director hands it a screen it
        // read as none of them, and a panel the quest loop left is no
        // leftover of that pass.
        onTitle = false
        adParked = false
        downloadAsked = false
        return goHome()
    }

    /**
     * A dungeon's panel, which the director calls `dialog` as it calls every
     * pop-up of the game, is this skill's to leave all the same
     * (Skill.leavesFrom; PLAN_ABSCHLUSS_1_3.md A5i, question 22). [leave]
     * goes back to the list through the panel's own prompt -- the back key,
     * then OK on "leave", as every card of a pass leaves its panel
     * ([returnToList]) -- and home by the globe. That back key and that OK
     * are right over a dungeon's panel and nowhere else: on a screen with
     * nothing open the back key asks "Return to the title screen?", the same
     * picture to the pixel ([dismissConfirm]). So the panel is proved by
     * what only a panel has, measured on 2026-10-03 over the 100 frames of
     * the corpus that `classify` calls a dialog:
     *
     *   its own counter, "n/2" hung from Attempt or
     *   the film button ([Dungeon.panelTickets])          42, every tickets' panel
     *   Network Defense Ops: Find a Party beside
     *   Attempt (DIALOG_PARTY), or its counter over
     *   the panel ([Dungeon.headerTickets])               2
     *   the daily dungeon's ([Dungeon.dailyPanel],
     *   [dailyAtMax])                                     13
     *
     * -- and not one of them on the other 43: the OK pop-ups, the reset's
     * windows over the list, the Digimon page's windows, the Super Hologram
     * Device's settings, Sell/Equip, the tower's panel, the daily panel's
     * prefab, two main screens. Over all 1886 frames DIALOG_PARTY and the
     * header counter answer on Network Defense Ops' two panels alone. Under
     * the dot at its twenty places (readerProbe, mask 0.06 to 0.155) the
     * tickets' counter and the daily panel read at every place, the header
     * counter at 19 of 20 -- at 0.155 the party panel still says it by its
     * pair, and Network Defense Ops' film-button panel is not proved there:
     * it parks, as it did.
     */
    override fun leavesFrom(screen: String, img: Mat): Boolean {
        if (screen != Director.DIALOG) return false
        val info = recognise(img)
        if (info.state !in DIALOGS) return false
        val button = info.attempt ?: info.ad
        return (button != null && panelTickets(img, button, info.anchor) != null) ||
            info.state == Dungeon.DIALOG_PARTY || headerTickets(img) != null ||
            dailyAttempt(img) != null || dailyAtMax(img) != null
    }

    internal open fun goHome(rounds: Int = HOME_ROUNDS): Boolean {
        // An ad that would not close is still in front, and the service
        // holds every tap there: the way home is the player's. The game's
        // download dialog is the director's, and it goes home from nowhere;
        // nor does the title ([onTitle]).
        if (adParked || handsOff) return false
        var pressed = 0
        var wentBack = false
        try {
            for (n in 0 until rounds) {
                // With the switch off the game stays where it is: nothing
                // tapped, nothing kept, said once ([Stays], F24).
                if (stays.now()) return false
                val img = grab()
                if (autoButton(img) != null) {
                    if (pressed > 0) log("back on the main screen")
                    return true
                }
                val button = homeButton(img)
                if (button == null) {
                    // The game's title: no way home from there but "Touch To
                    // Start", which is the player's (B30).
                    if (metTitle(img)) return false
                    // No bar on this frame: either something is still open
                    // over it, or it is dimmed by a dialog. returnToList is
                    // the way out of both, and it is worth one go, not one
                    // per round.
                    if (!wentBack) {
                        wentBack = true
                        log("\nnot on a screen with the nav bar, going back to the list first")
                        returnToList()
                        if (handsOff) return false
                        continue
                    }
                    sleep(pauseLong)
                    continue
                }
                if (pressed >= HOME_PRESSES_MAX) break
                if (pressed == 0) log("\npressing the home button")
                tap(button.fx, button.fy, "home button")
                pressed += 1
                sleep(pauseLong)
            }
            if (stays.now()) return false
            log("could not get back to the main screen -- the game is left where it stands")
            saveUnknown(grab(), "no_way_home")
        } catch (err: Exception) {
            // This runs in a finally. A pass that ended because the capture
            // died would otherwise end with this error instead of its own,
            // and the real one is the one worth reading.
            log("the way home failed: ${err.message}")
        }
        return false
    }

    /** A screen worth looking at afterwards, capped so a long pass cannot fill the disk. */
    internal open fun saveUnknown(img: Mat, tag: String) {
        if (saved >= 6) return
        saved += 1
        keep(img, tag)
        log("  unclear screen kept: $tag")
    }

    /**
     * Swipe to one end of the list, and prove it. A swipe is proved, not
     * assumed: one sent into the list's opening animation is swallowed --
     * the same case as a press swallowed on the way home -- and a plan
     * counted on the wrong half plays every bottom card one off
     * (notes/dungeons.md, "A swipe is proved, not assumed"). So after the swipe the frame is let
     * settle, [Dungeon.listAtTop] is asked, and a swipe that did not take is
     * repeated, [SCROLL_TRIES] at most. The frame returned is the settled one
     * the answer was read on; null when the list was there and would not
     * reach that end, with the last frame kept. A frame with no list on it
     * is returned as it is, and at once: a swipe on something other than
     * the list is not a swipe that did not take, and the caller reads what
     * it can off the frame ([plan] says "no list cards recognised").
     */
    private fun scroll(swipes: Int, up: Boolean): Mat? {
        val (ox, oy) = origin
        val (dw, dh) = device
        val x = Py.int(ox + 0.5 * dw)
        val yNear = Py.int(oy + 0.30 * dh)
        val yFar = Py.int(oy + 0.88 * dh)
        val where = if (up) "top" else "bottom"
        var img: Mat? = null
        for (attempt in 1..SCROLL_TRIES) {
            // A swipe the service would hold back proves nothing, and three
            // of them keep a frame of a list that never moved: with the
            // switch off the scroll ends here, the pass stops at its next
            // look ([stillOn]).
            if (!on()) return null
            // One swipe is enough, measured. Then half a second, otherwise
            // reading happens during the trailing motion and the cards sit
            // at the wrong positions.
            for (n in 0 until swipes) {
                if (up) swipe(x, yNear, x, yFar) else swipe(x, yFar, x, yNear)
                sleep(0.5)
            }
            img = settledFrame()
            val cards = listCardsWithSize(img)
            val atTop = Dungeon.listAtTop(cards) ?: return img
            val arrived = if (up) atTop else Dungeon.listAtBottom(cards) == true
            if (arrived) return img
            if (attempt < SCROLL_TRIES) {
                log("  the swipe to the $where did not take (%d cards, list at the top: %s), once more"
                    .format(cards.size, atTop))
            } else {
                log("  the swipe to the $where did not take $SCROLL_TRIES times (%d cards, list at the top: %s)"
                    .format(cards.size, atTop))
                saveUnknown(img, "list_not_at_$where")
            }
        }
        return null
    }

    /** The settled frame at the top of the list, or null when the list would not go there. See [scroll]. */
    internal open fun scrollTop(swipes: Int = this.swipes): Mat? = scroll(swipes, true)

    /** The settled frame at the bottom of the list, or null. See [scroll]. */
    internal open fun scrollBottom(swipes: Int = this.swipes): Mat? = scroll(swipes, false)

    /**
     * To the half of the list a card is counted in. A card's index is only
     * a card under the scroll the plan measured it at, and a list the game
     * hands back on its own -- after a battle, after a panel closed itself
     * -- is at the top, whatever the pass had scrolled to.
     */
    private fun scrollTo(label: String): Mat? = if (label == BOTTOM) scrollBottom() else scrollTop()

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 300) {
        // Over the game's download dialog a swipe that starts or ends on a
        // button presses it ([downloadAsked]); on the title any is a touch.
        if (handsOff) return
        try {
            cap.swipe(x1, y1, x2, y2, ms)
        } catch (err: Exception) {
            log("    swipe failed: ${err.message}")
        }
    }

    // ------------------------------------------------------------------
    // Which card is which
    // ------------------------------------------------------------------
    /** Number within the overall list, whether counting is from the top or the bottom. */
    internal fun globalIndex(index: Int, label: String): Int =
        if (label == BOTTOM) entries - visibleAtBottom + index else index

    /** Is this dungeon selected? [only] wins over [skip], so a stale skip cannot override it. */
    internal open fun isSelected(index: Int, label: String): Boolean {
        val pos = globalIndex(index, label)
        val chosen = only
        if (chosen != null) return pos in chosen
        return pos !in skip
    }

    /** Tickets this dungeon has left to spend, of what the player allowed. */
    internal fun attemptsFor(index: Int, label: String): Int {
        val pos = globalIndex(index, label)
        val allowed = budgets[pos] ?: maxAttempts
        return max(0, allowed - (spent[pos] ?: 0))
    }

    /** Book what a visit used, so the rest of the pass knows about it. */
    internal fun noteSpent(index: Int, label: String, count: Int) {
        if (count != 0) {
            val pos = globalIndex(index, label)
            spent[pos] = (spent[pos] ?: 0) + count
        }
    }

    /**
     * Name instead of card number, for the log only. The skill itself keeps
     * counting cards; reading names would be text recognition and would break
     * on every game update.
     */
    internal open fun labelOf(index: Int, label: String): String =
        dungeonLabel(index, label == BOTTOM, entries, visibleAtBottom)

    private fun bump(key: String, by: Int) {
        stats[key] = (stats[key] ?: 0) + by
    }

    /** `dungeon.format_summary`: the counters laid out for reading. */
    fun formatSummary(): String {
        val width = SUMMARY_LABELS.maxOf { it.second.length }
        return (listOf("Summary") + SUMMARY_LABELS.map { (k, label) ->
            "  %-${width}s %d".format(label, stats[k] ?: 0)
        }).joinToString("\n")
    }

    // ------------------------------------------------------------------
    // The readers, and the capture. One seam each, for the same reason
    // test_dungeon_flow.py replaces the module functions: the flow cases are
    // about the order of the steps, and the pictures have cases of their own.
    // ------------------------------------------------------------------
    internal open fun grab(): Mat = cap.grab()

    /**
     * An ad, or where one sends the player, in front: asked of the system,
     * no frame ([Ads.inFront]). With the game's question "You can view ads to
     * receive a Dungeon Ticket. View ads?" -- the Summon's in the dungeon's
     * words, measured on LDPlayer instance 1 on 2026-09-28 at 08:15,
     * corpus/dungeon/ad_confirm_082003, read by `recognise` as the
     * pink-Cancel prompt -- it is what the look after a film button asks
     * ([adTapped]).
     */
    internal open fun adInFront(): Boolean = Ads.inFront(cap.adHand())

    /**
     * A tap by fraction of the reference frame, in that frame's coordinates.
     * A fraction is a place only in the rectangle it was read in: a button
     * of [Dungeon.recognise]'s answer is tapped at its [anchor]
     * ([Dungeon.Recognition.anchor]), the panel's at the top of a display
     * with headroom, and everything else -- the cards, the nav bar, the
     * neutral spot -- at the bottom, as it always was. One rectangle on any
     * display under the canvas ceiling.
     */
    internal open fun tap(fx: Double, fy: Double, was: String = "",
                          anchor: Dungeon.Anchor = Dungeon.Anchor.BOTTOM) {
        // The game's download dialog is up and the director's to answer: a
        // tap of this pass can only land on its Cancel or beside it.
        if (downloadAsked) {
            log("    $was: not tapped, the game's download dialog is up")
            return
        }
        // Nor on the game's title, where any tap is "Touch To Start" ([onTitle]).
        if (onTitle) {
            log("    $was: not tapped, the game is on its title")
            return
        }
        val (ox, oy) = origin
        val (dw, dh) = device
        val top = oy - Py.roundInt(room * anchor.share)
        cap.tap(Py.roundInt(ox + fx * dw), Py.roundInt(top + fy * dh))
    }

    internal open fun recognise(img: Mat): Dungeon.Recognition = Dungeon.recognise(img)

    internal open fun listCards(img: Mat): List<Double> = Dungeon.listCards(img)

    internal open fun listCardsWithSize(img: Mat): List<Pair<Double, Double>> =
        Dungeon.listCardsWithSize(img)

    internal open fun cardBudget(img: Mat, fy: Double, fh: Double): Dungeon.Budget =
        Dungeon.cardBudget(img, fy, fh)

    internal open fun panelTickets(img: Mat, button: Dungeon.Button,
                                   anchor: Dungeon.Anchor = Dungeon.Anchor.BOTTOM): Int? =
        Dungeon.panelTickets(img, button, anchor)

    internal open fun headerTickets(img: Mat): Int? = Dungeon.headerTickets(img)

    /** The daily dungeon's Attempt, read at [Dungeon.DAILY]; null on every other screen, its prefab included. */
    internal open fun dailyAttempt(img: Mat): Dungeon.Button? = Dungeon.dailyPanel(img)

    /**
     * The daily dungeon's panel at its highest level: the level box says
     * "MAX", and where Reset and Attempt stood side by side there is one
     * violet Reset in the middle of the pair's row and no Attempt at all.
     * Seen live on LDPlayer instance 0 on 2026-09-29 ("Attack Type", MAX;
     * corpus/dungeon/daily_panel_max_170523) and already in the corpus from
     * an earlier day (corpus/passive/unclear_155914), both read at
     * [Dungeon.DAILY]:
     *
     *   Reset alone   fx 0.4769   fy 0.7934   fw 0.2528   fh 0.0394
     *
     * -- the pair's own row and size ([Dungeon.DAILY_ROW_FY], [Dungeon.DAILY_W],
     * [Dungeon.DAILY_H]) and, to the unit, the middle of the pair (Reset
     * 0.337, Attempt 0.616, their middle 0.4765). `recognise` calls it
     * DIALOG_AD and the Reset its `ad`, and [Dungeon.dailyPanel] null, so the
     * phase used to wait out [dailyWait] for an Attempt that never comes and
     * keep the frame as `daily_no_panel`. Over the 1742 frames of the oracle
     * (its find_buttons answers, VIOLET and BLUE) this shape answers on that
     * one corpus frame and nowhere else; the nearest other centred violet
     * button is Network Defense Ops' ad-only panel, 0.0444 tall and 0.265
     * wide, outside [Dungeon.DAILY_H]; the prefab's are at fy 0.7548 and 0.034
     * tall. What it decides is only to leave: nothing is ever tapped on it,
     * Reset least of all (question 17).
     */
    internal open fun dailyAtMax(img: Mat): Dungeon.Button? {
        fun shaped(b: Dungeon.Button) =
            b.fw in Dungeon.DAILY_W[0]..Dungeon.DAILY_W[1] && b.fh in Dungeon.DAILY_H[0]..Dungeon.DAILY_H[1] &&
                Dungeon.near(b.fy, Dungeon.DAILY_ROW_FY, Dungeon.DAILY_TOL)
        val reset = Dungeon.findButtons(img, Dungeon.VIOLET, minY = 0.0, anchor = Dungeon.DAILY)
            .filter { shaped(it) && Dungeon.near(it.fx, DAILY_MAX_FX, Dungeon.DAILY_TOL) }
        if (reset.size != 1) return null
        if (Dungeon.findButtons(img, Dungeon.BLUE, minY = 0.0, anchor = Dungeon.DAILY).any { shaped(it) }) return null
        return reset[0]
    }

    internal open fun autoButton(img: Mat): Dungeon.Button? = Dungeon.autoButton(img)

    internal open fun homeButton(img: Mat): Dungeon.Button? = Dungeon.homeButton(img)

    /**
     * A run's own transition in front, by name -- the black between the tap
     * and VS or after the battle, the VS screen -- or null ([Dungeon.blackFrame],
     * [Dungeon.vsScreen]; PLAN_RELEASE_1_3.md B6). Waited on as a battle is,
     * and never tapped: the screen after it gets the hold of its own.
     */
    internal open fun passing(img: Mat): String? = Dungeon.runTransition(img)

    /**
     * The game's title ([Startup.title]: its bar, or by day its Menu button;
     * the director's `title`; PLAN_RELEASE_1_3.md B30, B43).
     */
    internal open fun titleScreen(img: Mat): Boolean = Startup.title(img)

    /**
     * The title in front ([titleScreen]): the pass ends where it stands --
     * said, its frame kept, parked with [TITLE_WHY] -- and nothing more is
     * tapped ([onTitle]). True where it is.
     */
    private fun metTitle(img: Mat): Boolean {
        if (!titleScreen(img)) return false
        if (!onTitle) {
            log("  the game is on its title screen -- the pass ends here, nothing tapped")
            saveUnknown(img, "title")
        }
        onTitle = true
        if (parkedBecause == null) parkedBecause = TITLE_WHY
        return true
    }

    /** A run's transition ([passing]), said once per wait and kind. */
    private fun waitedOn(kind: String, said: MutableSet<String>) {
        if (said.add(kind)) log("  $kind -- a run's own transition, waited on and not tapped")
    }

    companion object {
        /**
         * What a card still owes a pass: the game's total, tickets and ads
         * together, where the ads are the pass's to spend ([Settings.useAds]),
         * and the tickets alone where they are not -- counted then even while
         * the film symbol is hidden, since nothing behind it is wanted. Null
         * where the card did not say. The survey, the panel's ceiling and the
         * Quest Loop's `usable` all ask this, so they cannot disagree.
         */
        fun owed(budget: Dungeon.Budget, useAds: Boolean): Int? =
            if (useAds) budget.total else budget.tickets

        /** The party dungeon, whose counter stands above its panel (Dungeon.HEADER_COUNTER). */
        const val NETDEF = "Network Defense Ops"

        /** Top and bottom half of the list. `dungeon.py` writes them in German; these are the same two keys. */
        const val TOP = "top"
        const val BOTTOM = "bottom"

        /**
         * What a skill that may have left one of its own prompts standing
         * calls it, in [Outcome.leaving] and in `dismiss_confirm`'s `want_ok`
         * alike: one string, so the two can never mean different things.
         */
        const val LEAVING = "leaving the dungeon"

        /**
         * What a pass the game's download dialog ended hands back, parked:
         * the director answers the dialog itself (DirectorLoop.download),
         * and LostSectorSkill says the same.
         */
        const val DOWNLOAD_WHY = "the game asks to download its data; the director answers it"

        /**
         * What a pass the game's title ended hands back, parked
         * ([DungeonSkill.onTitle], PLAN_RELEASE_1_3.md B30); LostSectorSkill
         * says the same.
         */
        const val TITLE_WHY = "the game went back to its title screen; nothing more is tapped in this pass"

        /** States in which a dungeon dialog is open. */
        val DIALOGS = setOf(Dungeon.DIALOG, Dungeon.DIALOG_PARTY, Dungeon.DIALOG_AD)

        /**
         * Neutral spot for tapping away rewards. Deliberately high up, above
         * any dialog: a tap in the middle of the screen acts like the back
         * key when a party exists and opens the "disband the party" prompt.
         */
        const val NEUTRAL_TAP_Y = 0.12

        /**
         * The reset's windows one way back to the list closes at most
         * ([closeResetWindow], B44): seven came on 2026-10-01, and one more
         * is the spare -- startup.py's eight Claims, "more than has ever been
         * seen and still a number". The looks are the closes, a second look
         * at each announcement and the waits between, three times that.
         */
        const val RESET_WINDOWS_MAX = 8
        const val RESET_LOOKS_MAX = 24

        /**
         * Two looks read an announcement's OK at one place when it moved by
         * no more than this, a fraction of the rectangle: every frame of it
         * has the OK at fx 0.4760, fy 0.9040 to 0.9048 (Startup.ANNOUNCE_*),
         * and the window coming in draws it at the same place.
         */
        const val RESET_OK_SAME = 0.01

        /**
         * Looks in a row at a screen nothing reads, after one of the reset's
         * windows, waited on before the way back taps as before: the next
         * window coming in (each announcement grows in from the middle in
         * about a second, ndo_084507).
         */
        const val RESET_SETTLE = 3

        /**
         * The Close button on Apocalymon Wall's results screen sits centred at
         * 0.503 and 0.792, almost exactly where Apocalymon's own Attempt
         * button sits, measured 0.501 and 0.756. They are told apart by width,
         * measured Close 0.216 against Attempt 0.261 to 0.301; the threshold
         * sits between them with margin on both sides.
         */
        const val CLOSE_W_MAX = 0.24

        /** Bottom nav bar, dungeon tab. Fixed, because the bar does not scroll. */
        val NAV_DUNGEON = 0.377 to 0.957

        /**
         * "Is anything moving at all": [Dungeon.MOVING_SHARE], which is where
         * dungeon.py's own number lives now, beside [Dungeon.sameScreen] that
         * reads it. A different question from the director's
         * [Director.IDLE_SHARE] and from the 20 s the passive helper gave
         * "has the counter stopped" (NOTES.md, "A threshold answers one
         * question").
         */
        const val MOVING_SHARE = Dungeon.MOVING_SHARE

        /**
         * How hard [goHome] tries. One press is all it takes from the list,
         * and the second is there because a press sent into an animation is
         * swallowed. Three is that with a spare; past it, whatever is on
         * screen is not what this takes it for. The rounds are the looking,
         * and there are more of them than presses because a round that finds
         * no bar spends itself waiting rather than tapping.
         */
        const val HOME_PRESSES_MAX = 3
        const val HOME_ROUNDS = 6

        /**
         * Attempts on one dungeon before the rest of its tickets go to Clear
         * Previous Difficulty, where the page has no number yet. The player
         * spoke of "20, 30 attempts for 5 tickets" on 2026-09-23; 10 is the
         * plan's proposal, and the page is where it is changed. The quest
         * loop has its own, [QuestSkill.ATTEMPTS_BEFORE_CLEAR].
         */
        const val ATTEMPTS_BEFORE_CLEAR = 10

        /** See [unknownHold]. */
        const val UNKNOWN_HOLD = 3.0

        /**
         * How long the wait after Attempt gives a run to hand the panel back.
         * 90 s was the PC's, set when a battle took 7 to 40 s; the dungeons
         * grew harder on 2026-09-23. Live on LDPlayer 2026-09-29 a Bakemon
         * run took 84 s, and the one at 09:05:23 outlasted the 90 s --
         * no "battle finished", the counter asked of the battle, "2 ->
         * unread", booked as lost only because it was lost; a won run there
         * goes unbooked, and two in a row leave the card. Over 95 s with the
         * VS screen, the sheet and "Now Loading" either side (a few seconds
         * each, measured 2026-09-23 and 09-29), so 150 s, the conductor's
         * proposal while the player's answer (question 24) is out
         * (notes/dungeons.md, "A run can outlast 90 s").
         */
        const val BATTLE_TIMEOUT = 150.0

        /**
         * How long one "Find a Party" is given before the second, and the
         * second before "no party found". The PC looked twice, 4.5 s apart,
         * and never measured the game's search; 45 s is the plan's proposal
         * (notes/dungeons.md, "A swipe is proved, not assumed"), to be set from the first live search
         * with a time in the log.
         */
        const val PARTY_WAIT = 45.0

        /**
         * How long [DungeonSkill.dailyBack] gives the daily dungeon's panel to
         * stand again once the run's wait is over, and to open after its card
         * is tapped. What comes in that time was measured (DL1a, 2026-09-29):
         * "Now Loading", the prefab for about a second, the panel -- the wait
         * after a run hands back the prefab, and the panel stood still 13.2 to
         * 15.4 s after the tap on Attempt. What was not seen, a won run's
         * sheet and whatever a level-up raises, gets [UNKNOWN_HOLD] before
         * each tap; 30 s is ten of those.
         */
        const val DAILY_WAIT = 30.0

        /** Where the daily panel's lone Reset stands at its highest level: the middle of the pair (dailyAtMax). */
        const val DAILY_MAX_FX = 0.477

        /** Swipes [scroll] sends before it gives up proving its end of the list. */
        const val SCROLL_TRIES = 3

        /** The last halt of [maxLoops]: no card takes more looks than this. */
        const val LOOP_CAP = 200

        /** What [book] is told the tap was, so that it names the outcome right. */
        const val ATTEMPT = "attempt"
        const val CLEAR = "clear"

        /** Label and order for [formatSummary], kept apart from [stats]. */
        val SUMMARY_LABELS = listOf(
            "tickets" to "Tickets used",
            "attempts" to "Attempts",
            "fights" to "Fights",
            "lost" to "Runs lost",
            "cleared" to "Cleared at once",
            "ads" to "Ads watched",
            "skipped" to "Skipped",
            "not_selected" to "Not selected",
            "unknown" to "Unclear screens",
            "declined" to "Declined",
            "exit_caught" to "Exit prompts caught",
            // The daily dungeon (playDaily), here so that [begin] zeroes them
            // with the rest at the top of every pass (notes/director.md, "A
            // counter nothing clears is counted again").
            "daily" to "Daily dungeon runs",
            "daily_won" to "Daily runs won",
            "daily_lost" to "Daily runs lost",
        )

        /**
         * `dungeon.dungeon_label`: the name of an entry. Counted from the
         * bottom, the first visible entry sits at total minus visible.
         */
        fun dungeonLabel(index: Int, fromBottom: Boolean = false,
                         total: Int = 7, visible: Int = 5): String {
            val pos = if (fromBottom) total - visible + index else index
            return if (pos in SkillSettings.DUNGEON_NAMES.indices)
                SkillSettings.DUNGEON_NAMES[pos] else "Entry ${pos + 1}"
        }
    }
}
