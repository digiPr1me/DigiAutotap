package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
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
 *    other means (NOTES.md, "The dungeon's own panel closes only through
 *    that prompt"). But where `work` hands the screen back while such a
 *    prompt may *still* be standing, it does not sit there tapping at a
 *    screen it has already given away: it names what it was doing in
 *    [Outcome.leaving], and the director presses OK on that one prompt and
 *    on no other (Skill.kt).
 *  - **One switch, asked between two actions.** The phone has no F7, so
 *    `_time_left`'s pause half is gone, and its time limit went with the
 *    setting that fed it: what is left is the main switch, asked between
 *    two actions and never in the middle of one (NOTES.md,
 *    "wait_while_paused does not answer 'was I stopped'"). [stillOn] is
 *    the one gate.
 *  - **No dry run.** `app.py` passes `dry_run=False` and the director has
 *    no other way to call this, so the branch that clicks nothing is gone
 *    rather than carried as a mode nothing on the phone can reach.
 *
 * What one pass found is readable afterwards and not only in the log:
 * [stats] and [counted] are what the skill measured, because a caller that
 * cannot ask what another skill read ends up guessing at it (NOTES.md,
 * "Silencing another skill's log throws away its measurements").
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
        val useAds: Boolean = true,
        /**
         * `dungeon_attempts_before_clear` (`quest_attempts_before_clear` for
         * the quest loop's own card): after this many attempts on one
         * dungeon in a pass, won or lost, every ticket still wanted there
         * goes to Clear Previous Difficulty, which hands out the previous
         * difficulty's rewards at once and cannot be lost. Counted per
         * dungeon and pass, not per ticket -- the player's rule of
         * 2026-09-23. 0 is "never Attempt, Clear Previous Difficulty only".
         */
        val attemptsBeforeClear: Int = ATTEMPTS_BEFORE_CLEAR,
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
    /** Entries at the end that are skipped: the last one changes daily. */
    internal var skipLast = 1
    internal var battleTimeout = 90.0
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
     * of them ([loopsFor]); the 12 that stood here were a fixed number for
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
    private var useAds = true
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
     * on Bakemon and Digifactory (NOTES.md, "A swipe is proved, not assumed").
     */
    private var unreadableInARow = 0
    /** [book] gave up on this card's counter; the card ends, the pass goes on. */
    private var counterGaveUp = false
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
    // The seam (Skill)
    // ------------------------------------------------------------------
    override val key = "dungeon"
    override val name = "Dungeons"

    override fun worksOn(screen: String) = screen == Director.DUNGEON_LIST

    /** `chain.dungeon_has_budget(budgets)` (chain.py:96), which is [Chain.dungeonHasBudget]. */
    override fun hasBudget(): Boolean = Chain.dungeonHasBudget(settings().budgets)

    /**
     * The list is already in front: the director classified [img] as one
     * (Director.DUNGEON_LIST) and held the player's three seconds. So this
     * is `_play` from the line after `open_list` said True, and it ends
     * where it began.
     */
    override fun work(img: Mat): Outcome {
        begin(img)
        return handBack(play())
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
            return Outcome.parked("no frame to start from: ${e.message}")
        }
        begin(first)
        try {
            if (!openList()) return Outcome.parked(
                "the dungeon list did not open -- am I on the main screen?")
            return play()
        } finally {
            // On every way out and not only the good one: a pass that never
            // found the list, one cut short by the main switch, one that
            // threw. Each of those used to leave the game wherever it stood.
            goHome()
        }
    }

    /** Fresh state for a pass, and the reference rect its clicks go out in. */
    private fun begin(img: Mat) {
        val s = settings()
        budgets = s.budgets
        only = s.only
        skip = emptySet()
        entries = s.entries
        useAds = s.useAds
        surveyFirst = s.survey
        attemptsBeforeClear = maxOf(0, s.attemptsBeforeClear)
        stopped = false
        panelSeen = false
        parkedBecause = null
        unreadableInARow = 0
        rewardSeen = false
        saved = 0
        visibleAtBottom = 5
        spent.clear()
        exhausted.clear()
        counted.clear()
        stats.clear()
        for ((k, _) in SUMMARY_LABELS) stats[k] = 0
        val r = Dungeon.gameRect(img)
        origin = r.x0 to r.y0
        device = r.gw to r.gh
    }

    /**
     * The end of the seam (Skill.kt, [Outcome.leaving]). `work` ends on the
     * list; where a panel was open in this pass and the list is not back on
     * the last look, a prompt of this skill's own may still be standing --
     * one of three identical to the pixel, and only the caller knows which
     * (NOTES.md, "Prompts and dialogs"). So it says what it was doing and
     * lets the director answer that one prompt, rather than tapping at a
     * screen it has already handed back.
     */
    private fun handBack(outcome: Outcome): Outcome {
        if (!panelSeen) return outcome
        val state = try {
            recognise(grab()).state
        } catch (e: CaptureError) {
            Dungeon.UNKNOWN
        }
        if (state == Dungeon.LIST) return outcome
        return Outcome(outcome.result, outcome.why, LEAVING)
    }

    // ------------------------------------------------------------------
    // The pass (dungeon._play)
    // ------------------------------------------------------------------
    /**
     * One pass through the list, top half then bottom half.
     *
     * It used to go round and round. What the rounds actually produced was a
     * dungeon set to one attempt being handed one more in every round. A
     * lost battle is retried now, but inside [playEntry], on the card's own
     * panel and against its own counter -- not by a second pass.
     */
    internal open fun play(): Outcome {
        val counts = plan() ?: return Outcome.parked(
            parkedBecause ?: "no list cards recognised -- am I in the list?")
        val (playFromTop, playFromBottom) = counts

        var topList = (0 until playFromTop).filter { isSelected(it, TOP) }
        var bottomList = (0 until playFromBottom).filter { isSelected(it, BOTTOM) }
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
                return done()
            }
        }

        // A card's index is only a card under the scroll the plan measured
        // it at, so a half whose scroll could not be proved is not played:
        // the card tapped would be the neighbour's.
        if (topList.isNotEmpty() && scrollTop() == null) {
            log("the list would not scroll to the top, leaving the top half")
            topList = emptyList()
        }
        for (index in topList) {
            if (!stillOn()) break
            log("\n" + labelOf(index, TOP))
            playEntry(index, TOP, TOP to index)
            if (scrollTop() == null) {
                log("the list would not scroll back to the top, leaving the rest of the top half")
                break
            }
        }
        if (bottomList.isNotEmpty() && scrollBottom() == null) {
            log("the list would not scroll to the bottom, leaving the bottom half")
            bottomList = emptyList()
        }
        for (index in bottomList) {
            if (!stillOn()) break
            log("\n" + labelOf(index, BOTTOM))
            playEntry(index, BOTTOM, BOTTOM to index)
            if (scrollBottom() == null) {
                log("the list would not scroll back to the bottom, leaving the rest of the bottom half")
                break
            }
        }
        return done()
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
        if (img == null) {
            // Counted at the top, the four cards of the top view become the
            // four bottom ones: Bakemon is played twice, Digifactory under
            // Network Defense Ops' name, Network Defense Ops under Metal
            // Sea's budget -- and not at all when that stands at 0. Loud and
            // wrong beats quiet and one off, and the frame [scroll] kept is
            // the measurement if a display really shows four cards at the
            // bottom (NOTES.md, "A swipe is proved, not assumed").
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
            if (budget.total == 0) {
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
        // Network Defense Ops' counter stands where the overlay's plate
        // stands at its default place (Dungeon.HEADER_COUNTER), so the dot
        // is moved off those rows for this one card and put back after it.
        val netdef = SkillSettings.DUNGEON_NAMES.getOrNull(globalIndex(index, label)) == NETDEF
        if (netdef) cap.overlayClear(Dungeon.HEADER_COUNTER[2], Dungeon.HEADER_COUNTER[3])
        try {
            playOne(index, label, key)
        } finally {
            if (netdef) cap.overlayBack()
        }
    }

    private fun playOne(index: Int, label: String, key: Pair<String, Int>?) {
        val k = key ?: (label to index)
        unreadableInARow = 0
        counterGaveUp = false
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
        tap(Dungeon.CARD_X, cards[index], labelOf(index, label))
        sleep(pauseShort)

        // Two ceilings, and the lower one wins. The player's number says how
        // many tickets they want spent on this dungeon; the counted one says
        // how much the game will actually hand out. Neither knows the other,
        // and either can be the smaller.
        val allowed = attemptsFor(index, label)
        val read = counted[label to index]
        val readTotal = read?.total
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
        var attemptsMade = 0
        var noEffect = 0
        var clearRetried = false
        var adsUsed = 0
        var partySearches = 0
        var steps = 0
        var expectTicket = false
        var adsHadNoEffect = false
        var reopened = false
        var unknownSince: Double? = null
        while (steps < loops) {
            if (!stillOn()) break
            steps += 1
            val here = dialogSettled(2)
            val state = here.state
            if (state in DIALOGS) panelSeen = true
            if (state != Dungeon.UNKNOWN) unknownSince = null

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
                waitDialogBack(battleTimeout)
                continue
            }

            if (state == Dungeon.LIST) {
                if (reopened || attemptsMade == 0) {
                    log("  back in the list, done")
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
                reopened = true
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
                val since = unknownSince ?: now().also { unknownSince = it }
                if (now() - since < unknownHold) {
                    steps -= 1
                    sleep(tick)
                    continue
                }
                // Tap high up, that accepts rewards and does not trigger a
                // party prompt.
                tap(0.5, NEUTRAL_TAP_Y, "neutral")
                sleep(pauseShort)
                continue
            }

            val close = here.attempt
            if (close != null && isCloseButton(close)) {
                log("  reward window, closing")
                tap(close.fx, close.fy, "Close")
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
                log("  searching for a party, %d of 3 slots filled".format(here.partyVoll ?: 0))
                val party = here.party!!
                tap(party.fx, party.fy, "Find a Party")
                partySearches += 1
                // The search gets [partyWait], and the searching panel is
                // not tapped: the PC's two looks 4.5 s apart were never
                // measured against how long the game searches.
                val t0 = now()
                val found = waitForParty(partyWait)
                if (found == null) {
                    log("  no party after %.0f s".format(now() - t0))
                } else {
                    log("  the party panel changed after %.0f s: %s, %d of 3 slots filled"
                        .format(now() - t0, found.state, found.partyVoll ?: 0))
                }
                continue
            }

            val attempt = here.attempt
            if (attempt != null) {
                if (ticketsSpent >= wanted) {
                    log("  spent %d ticket%s, which is the limit".format(wanted, plural(wanted)))
                    break
                }
                if (attemptsMade < attemptsBeforeClear) {
                    // Every attempt counts towards the switch to Clear
                    // Previous Difficulty, won or lost (the player's rule).
                    val before = counterNow(here)
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
                    log("  attempt %d, %d of %d ticket%s spent".format(
                        attemptsMade + 1, ticketsSpent, wanted, plural(wanted)))
                    val t0 = now()
                    rewardSeen = false
                    tap(attempt.fx, attempt.fy, "Attempt")
                    bump("attempts", 1)
                    if (waitDialogGone(startTimeout)) {
                        attemptsMade += 1
                        noEffect = 0
                        val back = waitDialogBack(battleTimeout)
                        if (back == null) {
                            // The panel did not come back: the list, a
                            // prompt, or the wait ran out. On the list the
                            // card's own badge is the counter's second look
                            // (listCounter); elsewhere the sheet is the one
                            // witness left, and the loop's top looks at what
                            // is there.
                            val after = if (before != null) listCounter(index, label) else null
                            if (book(before, after, ATTEMPT) == Booked.TICKET) {
                                ticketsSpent += 1
                            }
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
                            tap(close.fx, close.fy, "Close")
                            sleep(pauseLong)
                            settled = dialogSettled(2)
                        }
                        val after = counterNow(settled)
                        if (book(before, after, ATTEMPT) == Booked.TICKET) {
                            ticketsSpent += 1
                        }
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

                // N attempts made: the rest goes to Clear Previous Difficulty.
                val clear = here.clear
                if (clear == null) {
                    log("  %d attempt%s made and no Clear Previous Difficulty on this panel, moving on"
                        .format(attemptsMade, plural(attemptsMade)))
                    saveUnknown(grab(), "no_clear_previous")
                    break
                }
                log("  Clear Previous Difficulty, %d of %d ticket%s spent".format(
                    ticketsSpent, wanted, plural(wanted)))
                val before = counterNow(here)
                rewardSeen = false
                tap(clear.fx, clear.fy, "Clear Previous Difficulty")
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
                    // Without the Ad Skip Pass the film button opens a video
                    // no reader knows (SkillSettings.AD_PASS_KEY).
                    log("  no tickets left, and no Ad Skip Pass to watch an ad with")
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
                log("  tickets empty, watching an ad")
                tap(ad.fx, ad.fy, "ad")
                adsUsed += 1
                bump("ads", 1)
                expectTicket = true
                sleep(pauseLong)
                continue
            }

            log("  nothing to do in state $state")
            break
        }

        if (steps >= loops) log("  loop limit reached, moving on")
        noteSpent(index, label, ticketsSpent)
        returnToList()
    }

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
        val first = panelCounter(grab(), button) ?: return null
        val second = panelCounter(grab(), button) ?: return null
        return if (first == second) first else null
    }

    /**
     * One look at the panel's counter: over its button, or -- on the party
     * panel of Network Defense Ops, which has none there -- above the panel
     * ([Dungeon.HEADER_COUNTER]). Asked only of a frame [counterNow] has
     * already found a panel on; on the main screen the header crop reads
     * other things (the dungeon oracle, `header_tickets`).
     */
    private fun panelCounter(img: Mat, button: Dungeon.Button): Int? =
        panelTickets(img, button) ?: headerTickets(img)

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
     * a reward window appears there too.
     */
    internal open fun waitDialogBack(timeout: Double): Dungeon.Recognition? {
        val end = now() + timeout
        var taps = 0
        var unknownSince: Double? = null
        while (now() < end) {
            val info = recognise(grab())
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
                if (!rewardSeen) log("  the Reward sheet is up, closing it")
                rewardSeen = true
                tap(0.5, NEUTRAL_TAP_Y, "close the Reward sheet")
                sleep(tick)
                continue
            }
            if (info.state == Dungeon.EXIT) {
                // The party prompt arises precisely from tapping somewhere
                // while a party exists. After OK the dungeon is left, so stop.
                dismissConfirm(info, LEAVING)
                return null
            }
            if (info.state == Dungeon.LIST) return null
            // Not at once: see UNKNOWN_HOLD. A screen between two known ones
            // is left alone until it has outstayed every transition measured.
            val since = unknownSince ?: now().also { unknownSince = it }
            if (now() - since < unknownHold) {
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
            sleep(tick)
        }
        return null
    }

    /**
     * Wait for the party search to end: the panel with two or more slots
     * filled, or any screen that is neither the searching panel nor
     * unknown. Nothing is tapped meanwhile -- what the panel looks like
     * while the game searches is unmeasured, and a tap outside it is the
     * one thing that closes it (NOTES.md, "A swipe is proved, not assumed"). Null when the
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
        for (i in 0 until tries) {
            val info = recognise(grab())
            if (info.state == Dungeon.LIST) return true
            if (info.state == Dungeon.EXIT) {
                dismissConfirm(info, if (panelBefore) LEAVING else null)
                panelBefore = false
                continue
            }
            panelBefore = info.state in DIALOGS
            if (panelBefore) panelSeen = true
            if (backAllowed && i < tries - 2 && info.state in DIALOGS) {
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

    /**
     * Android back key. Only pressed while a dialog is actually open: with
     * none open the game answers "Return to the title screen?", and OK on
     * that throws the session away.
     *
     * [wantOk] says what the caller was trying to do, and only a caller that
     * was closing a dungeon panel may pass one. See [dismissConfirm].
     */
    internal open fun back(onlyIfDialog: Boolean = true, wantOk: String? = null): Boolean {
        if (onlyIfDialog) {
            val state = recognise(grab()).state
            if (state == Dungeon.LIST || state == Dungeon.UNKNOWN || state == Dungeon.BATTLE) {
                log("    no dialog open, back key not pressed")
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
        bump("exit_caught", 1)
        if (kind == "party" && wantOk != null) {
            log("  $wantOk via OK")
            tap(ok.fx, ok.fy, "OK, leave the party")
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
            tap(cancelX, ok.fy, "Cancel")
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
        img.release()
        origin = r.x0 to r.y0
        device = r.gw to r.gh
        return goHome()
    }

    internal open fun goHome(rounds: Int = HOME_ROUNDS): Boolean {
        var pressed = 0
        var wentBack = false
        try {
            for (n in 0 until rounds) {
                val img = grab()
                if (autoButton(img) != null) {
                    if (pressed > 0) log("back on the main screen")
                    return true
                }
                val button = homeButton(img)
                if (button == null) {
                    // No bar on this frame: either something is still open
                    // over it, or it is dimmed by a dialog. returnToList is
                    // the way out of both, and it is worth one go, not one
                    // per round.
                    if (!wentBack) {
                        wentBack = true
                        log("\nnot on a screen with the nav bar, going back to the list first")
                        returnToList()
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
     * (NOTES.md, "A swipe is proved, not assumed"). So after the swipe the frame is let
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

    /** A tap by fraction of the reference frame, in that frame's coordinates. */
    internal open fun tap(fx: Double, fy: Double, was: String = "") {
        val (ox, oy) = origin
        val (dw, dh) = device
        cap.tap(Py.roundInt(ox + fx * dw), Py.roundInt(oy + fy * dh))
    }

    internal open fun recognise(img: Mat): Dungeon.Recognition = Dungeon.recognise(img)

    internal open fun listCards(img: Mat): List<Double> = Dungeon.listCards(img)

    internal open fun listCardsWithSize(img: Mat): List<Pair<Double, Double>> =
        Dungeon.listCardsWithSize(img)

    internal open fun cardBudget(img: Mat, fy: Double, fh: Double): Dungeon.Budget =
        Dungeon.cardBudget(img, fy, fh)

    internal open fun panelTickets(img: Mat, button: Dungeon.Button): Int? =
        Dungeon.panelTickets(img, button)

    internal open fun headerTickets(img: Mat): Int? = Dungeon.headerTickets(img)

    internal open fun autoButton(img: Mat): Dungeon.Button? = Dungeon.autoButton(img)

    internal open fun homeButton(img: Mat): Dungeon.Button? = Dungeon.homeButton(img)

    companion object {
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

        /** States in which a dungeon dialog is open. */
        val DIALOGS = setOf(Dungeon.DIALOG, Dungeon.DIALOG_PARTY, Dungeon.DIALOG_AD)

        /**
         * Neutral spot for tapping away rewards. Deliberately high up, above
         * any dialog: a tap in the middle of the screen acts like the back
         * key when a party exists and opens the "disband the party" prompt.
         */
        const val NEUTRAL_TAP_Y = 0.12

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
         * How long one "Find a Party" is given before the second, and the
         * second before "no party found". The PC looked twice, 4.5 s apart,
         * and never measured the game's search; 45 s is the plan's proposal
         * (NOTES.md, "A swipe is proved, not assumed"), to be set from the first live search
         * with a time in the log.
         */
        const val PARTY_WAIT = 45.0

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
