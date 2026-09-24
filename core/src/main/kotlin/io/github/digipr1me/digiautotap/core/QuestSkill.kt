package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The quest loop on the phone: `quest.py`'s `QuestLoop` under the director
 * (PLAN_ANDROID_APP.md 4, session L). The seam is where
 * PLAN_ANDROID_3_DIRECTOR.md 3.2 puts it, and it was already there:
 *
 *   `work` = quest.py:1091 `QuestLoop.tick` -- one round. It begins on the
 *            plain main screen with a readable card and ends there.
 *   `run`  = the same round, repeated until the loop switches itself off.
 *            See [run] for why the chain's call is not one tick.
 *
 * Every reader it asks belongs to somebody else -- [Quest.questCard],
 * [Quest.closeX] (P4), [Dungeon.autoButton], [Dungeon.stageFailed] (P1) --
 * and not one of them is touched here. What this file carries of quest.py
 * is the loop: the routine, the resync rule, the claim, the two steps it
 * plays, each number with the sentence it has there. A number is measured
 * in Python first and only then written here (NOTES.md, "Two
 * implementations, one direction").
 *
 * What the director takes off this loop's hands, the same three things it
 * took off the passive helper (PassiveSkill):
 *
 *   `reserve_emulator` / `release_emulator`   there is no second claimant
 *       on the phone. The director is the only caller and it calls one
 *       skill at a time, so the handoff callbacks and `current_control`
 *       are gone rather than carried as a mechanism nothing can reach.
 *   `may_tap`   there is no shared mouse: the player's hand is the
 *       director's three-second rule.
 *   `dry_run`   `app.py` passes False and the director has no other way to
 *       call this, so the branch that clicks nothing is gone.
 *
 * What stayed is every guard about the *game*: the Stage Failed banner, the
 * auto button as proof that nothing is drawn over the screen, the X out of
 * a screen that nothing is going to close, and both clocks under them.
 * Those stay because a frame can still be any of those between the
 * director's look and this round's tap.
 */
open class QuestSkill(
    private val cap: Capture,
    /**
     * The remembered position, and nothing else: the four switches that
     * used to come with it are gone (SkillSettings, the passive page). Read
     * fresh at the start of every round, never remembered: the app writes
     * the settings file, and a copy taken once would answer with the
     * position as it was then.
     */
    private val settings: () -> Settings,
    /** `save_step`: where in the routine the loop had got to (app.py:3452). */
    private val saveStep: (Int) -> Unit = {},
    /**
     * `quest_locked_until` and `quest_locked_reason`, written the moment the
     * loop stops for want of dungeon tickets -- see [switchOff] and
     * [lockedForTheDay]. Epoch seconds and the sentence; the app writes the
     * file, and a lock that only lived in this object would not survive the
     * restart that rebuilds it.
     */
    private val lock: (until: Double, reason: String) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** The main switch, asked between two actions and never in the middle of one. */
    private val on: () -> Boolean = { MainSwitch.on },
    /**
     * Keep a frame worth looking at afterwards -- `_keep_frame`'s own cases,
     * which are the ones nothing in the card crop can explain. The app
     * writes files; core cannot.
     */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    /** Seconds, monotonic. Injected so that the flow test can move it. */
    private val now: () -> Double = { System.nanoTime() / 1e9 },
    /**
     * Epoch seconds, the wall clock -- the second clock in here, and not
     * the same one as [now]: a lock that ends at eight in the morning is a
     * point on the calendar, and a monotonic clock does not know what day
     * it is. `FarmSkill` keeps its `farm_due_at` on the same clock, for the
     * same reason.
     */
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
) : Skill {

    /**
     * What the loop reads out of digiautotap.json: where it stands, its
     * lock, and since 2026-09-23 the one number the player sets on its page.
     *
     * There were four switches here -- `quest_on`, `quest_dungeon`,
     * `quest_summon`, `quest_claim_only` -- and the page that set them is
     * gone (SkillSettings, the passive page): the Quest Loop row's switch in
     * the Skills list says whether the loop is included, and included it
     * plays every step of the routine, dungeon quests and summon quests
     * alike. The three that chose to do less are not settings any more and
     * so are no longer branches in here either.
     */
    data class Settings(
        /** `quest_step`: where in [ROUTINE] the last run had got to. */
        val step: Int = 0,
        /**
         * `quest_locked_until`: epoch seconds until which the loop is not to
         * be tried again, or null. Written by [switchOff] through [lock]
         * when the day's dungeon tickets ran out; read fresh on every
         * question, so that a lock the player removed by hand is gone at
         * once.
         */
        val lockedUntil: Double? = null,
        /**
         * `quest_attempts_before_clear`: the quest's own number for
         * [DungeonSkill.Settings.attemptsBeforeClear], apart from the
         * Dungeons task's, because the two want different things -- the
         * task the day's rewards, the quest two clears of one dungeon, and a
         * run cleared through Clear Previous Difficulty counts for "Clear
         * Fight! ... 2x" like any other (the player, 2026-09-23).
         */
        val attemptsBeforeClear: Int = ATTEMPTS_BEFORE_CLEAR,
        /**
         * `ad_pass`: does the player have the game's Ad Skip Pass
         * (SkillSettings.AD_PASS_KEY)? With it the free ads are the
         * quest's to spend -- the card's ad tickets count towards the day
         * and the summon step watches its two ads first. Without it none
         * of them is ever tapped, and a dungeon card at 0 tickets is the
         * day being over, whatever its film symbol says. Until 2026-09-24
         * the loop built both its bots with the ads hard-wired on and asked
         * no switch at all; that was the ad video a player without the pass
         * met.
         */
        val adPass: Boolean = SkillSettings.AD_PASS_DEFAULT,
    )

    // ------------------------------------------------------------------------
    // What one round knows
    // ------------------------------------------------------------------------
    /** Where in [ROUTINE] the loop stands. Set from the settings on the first round. */
    var step = -1
        private set

    /** What the card last read, for the status line and for [stillUnfinished]. */
    var lastProgress: Pair<Int, Int>? = null
        private set
    /** The word pair last counted off the card. */
    var lastWords: Pair<Int, Int>? = null
        private set

    /** The reason this step is being left alone for a while, or null. */
    var parkedBecause: String? = null
        private set
    private var parkRetryAt = 0.0

    /** The reason the loop stopped for the day, or null. See [switchOff]. */
    var stoppedBecause: String? = null
        private set
    /**
     * Whether that ending lasts until the game's own daily reset. The reason
     * says so in words for a player to read; this says it in something the
     * director can act on -- it is what turns the outcome into
     * [Result.RETIRED], which retires the chain step rather than parking it.
     */
    var stoppedUntilReset = false
        private set

    /** `result` of one tick: what this round did, for the test and the shell. */
    class Did(var claimed: Boolean = false, var acted: Boolean = false)

    /** The last round's [Did]. */
    var did = Did()
        private set

    /**
     * What this pass counted, for the TODAY card ([SkillStats], [Counted]).
     *
     * A pass, not a round: [work] is one tick and [run] is as many as the
     * chain's step waits through, and [Counted] asks once when the screen
     * comes back. So they are summed over the pass and cleared at the start
     * of the next one, the way `DungeonSkill.begin` clears its own -- a
     * single tick's [did] would have reported the last tick of a step that
     * claimed six quests. [SkillStats.SHOWN] names both keys; a key it names
     * that nothing ever writes is a row that can only read zero.
     */
    val lastCounts: Map<String, Int>
        get() = mapOf("claimed" to claimedThisPass, "played" to playedThisPass)

    private var claimedThisPass = 0
    private var playedThisPass = 0

    private var mismatch: Triple<Int, Int, Pair<Int, Int>?>? = null
    private var retriedStep: Int? = null
    private var claimStallStep: Int? = null
    private var holoDead = 0
    private var said: String? = null
    private var blindSince: Double? = null
    private val dumped = HashSet<String>()

    // Since when the screen has not been the plain main screen, and what has
    // been done about it -- see [stuck].
    private var stuckSince: Double? = null
    private var stuckTaps = 0
    private var stuckTapAt = 0.0

    // The same park coming back on the same step, and how long the card has
    // read the same thing. Both are kept whether or not [stopWhenStuck] is
    // on, so that switching it on mid-run does not start counting from a
    // clean slate it has not earned.
    private var parkKey: Pair<Int, String>? = null
    private var parkTries = 0
    private var seen: Triple<Int, Int, Int>? = null
    private var movedAt: Double? = null

    /**
     * Running until nothing progresses -- what the chain's quest step needs,
     * and what a switch somebody left on for the day must not have. Set for
     * the length of [run] and cleared again afterwards, the way a Skill
     * Chain step sets it on a loop object it did not build (quest.py).
     */
    internal var stopWhenStuck = false

    // ------------------------------------------------------------------------
    // The seam (Skill)
    // ------------------------------------------------------------------------
    override val key = "quest"
    override val name = "the quest loop"

    /**
     * The plain main screen, and that alone. The quest card is drawn there
     * and nowhere else, and every reader in here is gated on the auto button
     * before it is asked anything.
     */
    override fun worksOn(screen: String): Boolean = screen == Director.MAIN

    /**
     * The day's lock ([lockedForTheDay]), and nothing else. A loop that
     * stopped for want of dungeon tickets is not asked again before the
     * game's reset, whatever mode the director is in and however often the
     * core is restarted in between. Asked fresh every round, never
     * remembered: a chain step's "nothing to do" is a question, not a state
     * (Skill.hasBudget).
     *
     * It used to be `quest_on` and the supporter code as well. `quest_on`
     * went because whether the loop runs is the Quest Loop row's own switch,
     * which the director asks beside this one (`included`), and a second
     * switch here only ever meant a row switched on that did nothing. The
     * code went on 2026-09-23, when the player made the quest loop free for
     * everybody; the supporter question moved to the bond tour's
     * all-Digimon box (BondTourSkill.hasBudget).
     */
    override fun hasBudget(): Boolean = !lockedForTheDay()

    /**
     * Is the loop stopped for the day, on the record in the settings file?
     *
     * The player asked for this on 2026-09-22: once the loop has stopped
     * because there were not enough dungeon tickets, it is not to try again
     * until the tickets come back, which is eight in the morning in their
     * time zone ([RESET_ZONE], [RESET_HOUR]). Before this, "until tomorrow"
     * was a word in the log and nothing else: the semi-automatic mode came
     * back to the same empty card every quarter of an hour
     * ([PARK_RETRY_EMPTY]), and a restart of the core -- a force-stop, a new
     * install, the chain being started again -- forgot the stop altogether
     * and surveyed the dungeon list once more for the answer it already
     * had. Every one of those surveys took the main screen off the passive
     * helper for a minute.
     *
     * Read fresh, never remembered ("a chain step's nothing to do is a
     * question, not a state"): the settings file is what carries the lock
     * across a restart, and it is also the one place a player can take it
     * away again.
     */
    internal fun lockedForTheDay(): Boolean {
        val until = settings().lockedUntil ?: return false
        if (clock() >= until) return false
        once("locked:$until",
             "  the quest loop is stopped until ${whenText(until)}: out of dungeon tickets")
        return true
    }

    /**
     * One round on the frame the director classified as [Director.MAIN]:
     * `tick`, and the seam itself. At most one claim or one action, never
     * both, and never a second tap this round on top of either -- the second
     * would be aimed with a picture taken before the first landed.
     */
    override fun work(img: Mat): Outcome {
        startPass()
        tick(img)
        return outcome()
    }

    /**
     * The chain's quest step: the same round, over and over, until the loop
     * switches itself off.
     *
     * **Not one tick.** The seam in the table of PLAN_ANDROID_3_DIRECTOR.md
     * 3.2 says `run` is the same *method*, which it is -- there is no walk
     * to a screen of its own and no way home, because the quest card is on
     * the main screen the loop is already standing on. But a chain step is
     * started once and its outcome decides what comes next
     * (`DirectorLoop.full`: DONE advances, RETIRED retires and advances),
     * and "quest" is the one key in [SkillSettings.CHAIN_WAITING]: the chain
     * waits here until the loop stops itself (app.py:2646,
     * `_start_quest_step`). One tick answering DONE would walk on after two
     * seconds. So the waiting the PC does in its helper thread is done here,
     * and [stopWhenStuck] -- which is exactly "tell me when there is nothing
     * left to wait for" -- is on for the length of it and off again after.
     */
    override fun run(): Outcome {
        startPass()
        stopWhenStuck = true
        try {
            while (true) {
                if (!on()) return Outcome.STOPPED
                val img = try {
                    grab()
                } catch (e: CaptureError) {
                    return Outcome.parked("no frame: ${e.message}")
                }
                try {
                    tick(img)
                } finally {
                    img.release()
                }
                if (stoppedBecause != null) return outcome()
                sleep(TICK)
            }
        } finally {
            stopWhenStuck = false
        }
    }

    /**
     * What a round hands back.
     *
     * A loop that switched itself off says so, and the two endings are not
     * the same thing: `until_reset` is the game withholding something no
     * round of this chain can reach -- the day's dungeon attempts -- so the
     * step is retired, while everything else is a box that can be ticked or
     * a screen that can be closed and so is a park the director shows and
     * comes back to.
     *
     * A round that is still going answers [Result.RETIRED] with the sentence
     * that says so, for the reason PassiveSkill gives at the end of its own
     * file: [Result] has no value for "still going", and `mainRound`
     * discards a round-skill's outcome anyway. It never reaches a chain --
     * the chain calls [run], and [run] only returns once there is a real
     * answer.
     */
    private fun outcome(): Outcome {
        val why = stoppedBecause
            ?: return Outcome.retired("the quest loop's round has no end; ask again next round")
        return if (stoppedUntilReset) Outcome.retired(why) else Outcome.parked(why)
    }

    // ------------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------------
    private fun once(key: String, text: String) {
        if (said != key) {
            said = key
            log(text)
        }
    }

    private fun say(text: String) {
        said = null
        log(text)
    }

    private fun park(reason: String, retryIn: Double = PARK_RETRY) {
        parkedBecause = reason
        parkRetryAt = now() + retryIn
        once("park:$reason",
             "  parked on step ${step + 1} (${ROUTINE[step].name}): $reason")
        val key = step to reason
        parkTries = if (key == parkKey) parkTries + 1 else 1
        parkKey = key
        if (retryIn >= PARK_RETRY_EMPTY) {
            // The card said so itself, and it will say the same in a quarter
            // of an hour -- see [dungeonBlocked], which is the only thing
            // that asks for this retry and which reads "out of tickets and
            // ads until tomorrow" off the card. In every mode, not only in
            // a chain: this is the one park whose answer the player has
            // asked not to be tried again before the reset
            // ([lockedForTheDay]), and a quarter-hour retry outside a chain
            // was exactly that.
            switchOff(reason, untilReset = true)
            return
        }
        if (!stopWhenStuck) return
        if (parkTries >= PARK_GIVE_UP) {
            // Everything else. A box can be ticked, a screen can be closed,
            // a ticket can arrive from somewhere else -- none of that waits
            // for the reset, so this ending is only about this run having
            // stopped getting anywhere.
            switchOff("$reason -- still true after $parkTries tries")
        }
    }

    /**
     * Stop for the day, rather than park and come back to it.
     *
     * A park says "try this again in a while", and everything else in here
     * parks, because everything else in here is waiting on something that
     * changes: a switch, a box, a screen the game will close by itself. This
     * one is not. Where the day's dungeon attempts were already too few to
     * finish the quest, no amount of waiting adds one -- the game hands them
     * out again at its own reset, and until then the routine's fixed order
     * means nothing behind that step can be reached either. Fifteen minutes
     * of surveys for an answer the card has already given costs the passive
     * helper the main screen every time round.
     *
     * The PC also asks its window to untick the switch. Here the outcome is
     * that message: the director retires or parks the step, and the shell
     * draws what the director says.
     */
    private fun switchOff(reason: String, untilReset: Boolean = false) {
        stoppedBecause = reason
        stoppedUntilReset = untilReset
        if (!untilReset) {
            say("  switching the quest loop off: $reason")
            return
        }
        // The day's lock, on the record: written to the settings file, so
        // that neither a restart nor the semi-automatic mode's next round
        // asks the dungeon list the same question before the tickets are
        // back. See [lockedForTheDay].
        val until = nextReset(clock())
        lock(until, reason)
        say("  switching the quest loop off until ${whenText(until)}: $reason")
    }

    /**
     * Undo a switch-off, because the player asked for the loop again.
     *
     * The reason stood on the page and the switch was ticked anyway -- most
     * likely on the next day, when the attempts are back. The park goes with
     * it: it was about the same step and the same empty card, and leaving it
     * would hold the loop for another quarter of an hour after it had just
     * been asked to run.
     */
    fun resume() {
        if (stoppedBecause == null) return
        stoppedBecause = null
        stoppedUntilReset = false
        parkedBecause = null
        parkRetryAt = 0.0
        said = null
        parkKey = null
        parkTries = 0
        movedAt = null
    }

    private fun advance(newStep: Int) {
        step = Math.floorMod(newStep, ROUTINE.size)
        parkedBecause = null
        parkKey = null
        parkTries = 0
        retriedStep = null
        claimStallStep = null
        holoDead = 0
        said = null
        saveStep(step)
    }

    // ------------------------------------------------------------------------
    // One round
    // ------------------------------------------------------------------------
    /** A new pass begins: what [lastCounts] reports is this pass's and no earlier one's. */
    private fun startPass() {
        claimedThisPass = 0
        playedThisPass = 0
    }

    /**
     * `tick` (quest.py:1091), plus the one thing a tick of the PC's does not
     * have to do: add what it did to the pass, because on the phone a pass
     * may be many ticks and the card is asked once at the end of it.
     */
    internal fun tick(img: Mat) {
        try {
            oneRound(img)
        } finally {
            if (did.claimed) claimedThisPass += 1
            if (did.acted) playedThisPass += 1
        }
    }

    private fun oneRound(img: Mat) {
        val result = Did()
        this.did = result
        val set = settings()
        if (step < 0) step = Math.floorMod(set.step, ROUTINE.size)
        if (stoppedBecause != null) {
            // Switched off from in here. The director has been told and the
            // shell draws it; nothing in the meantime plays another round --
            // unless the stop was the day's, and the day is over: the lock
            // it wrote has run out, or the player took it away, and either
            // is the switch being ticked again (resume).
            if (stoppedUntilReset && !lockedForTheDay()) resume() else return
        }
        if (nothingMoving()) return

        if (stageFailed(img)) {
            once("failed", "  the Stage Failed banner is up, not tapping through it")
            return
        }
        if (autoButton(img) == null) {
            stuck(img)
            return
        }
        backAgain()

        val card = questCard(img)
        if (card == null) {
            blind()
            return
        }
        blindSince = null
        val ist = card.ist
        val ziel = card.ziel
        lastProgress = ist to ziel
        noteReading(ist, ziel)
        val words = card.nameLen to card.afterLen
        lastWords = words

        // The card decides which step this is, not the remembered position.
        // The position is only a tie-breaker between steps the card itself
        // cannot tell apart -- see [matches].
        if (!matches(step, ziel, words)) {
            handleMismatch(ist, ziel, words)
            return
        }

        mismatch = null
        if (parkedBecause != null && now() < parkRetryAt) return

        if (ist >= ziel) {
            val (claimed, fell) = claim(card, img)
            if (claimed) {
                result.claimed = true
                advance(step + 1)
            } else if (fell == false) {
                if (claimStallStep == step) {
                    park("claimed, but the counter did not fall, twice in a row")
                } else {
                    claimStallStep = step
                    say("  claimed, but the counter did not fall -- trying again next round")
                }
            }
            return
        }

        val kind = ROUTINE[step].kind
        // The hologram step is played from here, always. It used to ask
        // `passive_auto` first -- the game's own Auto Spend fills the counter
        // by itself, and then there is nothing to tap for -- but no page on
        // the phone ever set that key, so the question only ever had one
        // answer. A tap on a counter the game is already filling costs a tap.
        if (kind == HOLO) {
            if (runHoloStep(ist, ziel, img)) result.acted = true
            return
        }
        if (kind == HOLO || kind == ENEMIES || kind == STAGE) {
            parkedBecause = null
            return
        }
        if (!identified()) {
            // Not a park and not a refusal: the loop still plays the step,
            // the same as it always has. It says so once because this is the
            // one line that names a card nobody has counted yet.
            once("unnamed:$step",
                 "  nothing on this card says it is step ${step + 1} " +
                     "(${ROUTINE[step].name}) rather than another with the " +
                     "same target -- playing it anyway")
        }
        // Neither of these asks a switch any more. `quest_dungeon` and
        // `quest_summon` were the two ways of telling the loop to play less
        // than the routine, and they are gone with the page (SkillSettings):
        // a step the loop refuses to play is a step nothing behind it can be
        // reached past either, because the routine has a fixed order.
        if (kind == DUNGEON) {
            runDungeonStep(ist, ziel)
            result.acted = true
            return
        }
        if (kind == SUMMON) {
            runSummonStep(ist, ziel, img)
            result.acted = true
        }
    }

    /**
     * The card said something. If it is not what it said last time, the run
     * is getting somewhere -- see [nothingMoving].
     */
    private fun noteReading(ist: Int, ziel: Int) {
        val now = Triple(step, ist, ziel)
        if (now != seen) {
            seen = now
            movedAt = this.now()
        }
    }

    /**
     * Has this run stopped getting anywhere at all?
     *
     * Only ever asked of a run that was told to end by itself. Everything
     * else in here parks and comes back, because a switch can be ticked and
     * a screen can be closed while nobody is watching -- but a chain step
     * waiting on this loop has to be told when there is nothing left to wait
     * for, and the loop is the only thing that can see it.
     *
     * The park counter answers the cases the loop knows a reason for. This
     * one answers the rest, which is everything it can only wait on: enemies
     * to be beaten, a hologram counter Auto Spend is filling in by itself, a
     * screen in the way that nothing here can close. None of those park, all
     * of them show as a card that reads the same thing round after round,
     * and the clock is the only witness there is.
     */
    private fun nothingMoving(): Boolean {
        if (!stopWhenStuck) return false
        val t = now()
        val since = movedAt
        if (since == null) {
            movedAt = t
            return false
        }
        val idle = t - since
        if (idle < NO_PROGRESS_AFTER) return false
        switchOff(String.format(Locale.US,
            "nothing on the quest card has moved for %.0f minutes", idle / 60.0))
        return true
    }

    /**
     * Keep one whole frame when a step fails on something unread.
     *
     * Not the card crop -- whatever went wrong is outside it. Once per tag
     * per run, and without any switch to remember: a failure nobody
     * photographed is a failure nobody can fix.
     */
    private fun keepFrame(img: Mat, tag: String) {
        if (!dumped.add(tag)) return
        keep(img, tag)
    }

    /**
     * Not the plain main screen. Wait, then look for an X to get out.
     *
     * Nothing at all for the first [STUCK_AFTER] seconds: the screen the
     * loop is looking at is most often one of its own on the way out, or an
     * animation, or the player's, and every one of those ends by itself.
     * What does not end by itself is a screen the loop tapped open and could
     * not close -- a live run sat on the Special Summon reward screen for as
     * long as anybody watched, saying this same line, with the game's own X
     * drawn in the corner of every frame.
     *
     * Then, and only then, the X. Never a tap at a remembered corner:
     * [Quest.closeX] has to find one, and if it does not this says so and
     * keeps the frame instead of guessing. One tap per round, checked by the
     * next round's auto button rather than by another look at the picture
     * that aimed it -- the same rule the rest of [tick] follows.
     */
    private fun stuck(img: Mat) {
        val t = now()
        if (stuckSince == null) stuckSince = t
        val waited = t - stuckSince!!
        if (waited < STUCK_AFTER) {
            once("busy", "  not the plain main screen, nothing done this round")
            return
        }
        val x = closeX(img)
        if (x == null) {
            once("noway", String.format(Locale.US,
                "  not the plain main screen for %.0f s, and no X on it to " +
                    "get out with -- leaving it alone", waited))
            keepFrame(img, "no-x-to-get-out-with")
            return
        }
        if (stuckTaps >= STUCK_TAPS_MAX) {
            once("xstuck", "  ${x.which} is still there after $stuckTaps taps -- " +
                "leaving it alone")
            keepFrame(img, "x-would-not-close")
            return
        }
        if (t - stuckTapAt < STUCK_TAP_EVERY) return
        stuckTaps += 1
        stuckTapAt = t
        say(String.format(Locale.US,
            "  not the plain main screen for %.0f s -- tapping %s at %.3f/%.3f " +
                "to get out (%d of %d)",
            waited, x.which, x.fx, x.fy, stuckTaps, STUCK_TAPS_MAX))
        tap(img, x.fx, x.fy)
    }

    /**
     * The main screen is back. Forget how long it was gone.
     *
     * Said out loud only where an X was actually tapped, because that is the
     * one case where the loop did something about it and the player has a
     * line saying so hanging in the log.
     */
    private fun backAgain() {
        if (stuckTaps > 0) say("  the main screen is back after $stuckTaps tap(s) on the X")
        stuckSince = null
        stuckTaps = 0
    }

    private fun blind() {
        val t = now()
        val since = blindSince
        if (since == null) {
            blindSince = t
        } else if (t - since >= BLIND_AFTER) {
            say(String.format(Locale.US,
                "  the quest counter has not been readable for %.0f s on a " +
                    "screen that looks right", t - since))
            blindSince = t
        }
    }

    /** One line for the shell. */
    fun status(): String {
        val i = if (step < 0) Math.floorMod(settings().step, ROUTINE.size) else step
        val q = ROUTINE[i]
        var text = "Step ${i + 1} of ${ROUTINE.size} -- ${q.name}"
        lastProgress?.let { text += ", ${it.first}/${it.second}" }
        if (lastWords != null && q.chars == null) {
            // Only where the routine has no words for this step yet: that
            // pair is what has to be written into ROUTINE's `chars`, and the
            // shell is where somebody will see it.
            text += " (${wordsSaid(lastWords)})"
        }
        stoppedBecause?.let { return "$text -- switched off: $it" }
        parkedBecause?.let { text += " -- parked: $it" }
        return text
    }

    // ------------------------------------------------------------------------
    // What the card says this step is
    // ------------------------------------------------------------------------
    /**
     * Could the card in front of us be step [i]?
     *
     * Two questions, and the card answers as much of each as it can. The
     * target has to be the step's target -- that one has always been asked.
     * The words either side of the number have to be the step's words,
     * wherever they have been counted: without them, a run that opened on
     * "Bakemon" played DemiDevimon, because 2 is 2 and nothing else was
     * looked at.
     *
     * A step whose card nobody has counted yet (`chars` null) is matched on
     * its target alone, the way every step was before. That is not a gap in
     * the check, it is the check saying what it does not know -- and the
     * "other candidate, once" rule in [afterAction] is still underneath it.
     *
     * A step with no fixed target -- the stage one, whose number is a stage
     * rather than a count -- is the other way round: never matched on the
     * number, always on the words.
     */
    internal fun matches(i: Int, ziel: Int, words: Pair<Int, Int>?): Boolean {
        val q = ROUTINE[i]
        if (q.target == null) {
            // A step with no target of its own takes the numbers no other
            // step claims. Without that, a card whose words went unread would
            // land here -- (0, 0) is what an empty reading looks like as well
            // -- and the loop would sit on a step that plays nothing while
            // the game showed something else entirely.
            if (ROUTINE.any { it.target == ziel }) return false
        } else if (q.target != ziel) {
            return false
        }
        val chars = q.chars ?: return q.target != null
        if (words == null) return q.target != null
        return abs(chars.first - words.first) <= NAME_TOL &&
            abs(chars.second - words.second) <= NAME_TOL
    }

    /** Is the step we are on the only one the card could be? */
    internal fun identified(): Boolean {
        val q = ROUTINE[step]
        if (q.chars == null) return false
        val others = ROUTINE.indices.filter {
            it != step && ROUTINE[it].target == q.target &&
                (ROUTINE[it].kind to ROUTINE[it].arg) != (q.kind to q.arg)
        }
        return others.all { ROUTINE[it].chars != null }
    }

    // ------------------------------------------------------------------------
    // Resync
    // ------------------------------------------------------------------------
    /**
     * Script and card disagree. Wait for a second frame, then resync.
     *
     * A single mismatched frame is not proof of anything -- it could be the
     * tail end of the very claim that is about to advance the step. Only a
     * mismatch that survives two rounds in a row is trusted, and even then
     * nothing is clicked: the step jumps forward to the next script entry
     * the card could actually be -- by its target, and by the length of the
     * words either side of it wherever those have been counted.
     */
    private fun handleMismatch(ist: Int, ziel: Int, words: Pair<Int, Int>?) {
        if (mismatch == Triple(step, ziel, words)) {
            val candidates = forwardCandidates(ziel, words)
            if (candidates.isNotEmpty()) {
                val old = step
                advance(candidates[0])
                say("  the card is not step ${old + 1} (${ROUTINE[old].name}): " +
                    "it shows $ist/$ziel, ${wordsSaid(words)} -- going to step " +
                    "${step + 1} (${ROUTINE[step].name})")
            } else {
                once("nomatch:$ziel:$words",
                     "  the card shows $ist/$ziel, ${wordsSaid(words)}, and no " +
                         "step in the routine looks like that -- parked")
                parkedBecause = "the card and the script disagree"
            }
            mismatch = null
        } else {
            mismatch = Triple(step, ziel, words)
        }
    }

    internal fun forwardCandidates(ziel: Int, words: Pair<Int, Int>?): List<Int> {
        val n = ROUTINE.size
        return (1..n).map { (step + it) % n }.filter { matches(it, ziel, words) }
    }

    /**
     * A different step with the same target, for one retry.
     *
     * Two attempts that did not move the counter mean the wrong quest was
     * played, and there is exactly one designed case of that -- DemiDevimon
     * and Bakemon both asking for 2. Matched on kind as well as target, and
     * on a different `arg`, so a stalled Skill Summon step is not "retried"
     * against a Support one that merely shares its target by coincidence.
     *
     * And never against a step the card itself rules out: with both dungeon
     * names counted, a stalled Bakemon step has no other candidate at all any
     * more, and the loop parks instead of playing the wrong dungeon a second
     * time.
     */
    internal fun otherCandidate(ziel: Int, words: Pair<Int, Int>? = null): Int? {
        val cur = ROUTINE[step]
        for ((i, q) in ROUTINE.withIndex()) {
            if (i != step && q.target == ziel && q.kind == cur.kind &&
                q.arg != cur.arg && matches(i, ziel, words)) return i
        }
        return null
    }

    // ------------------------------------------------------------------------
    // Claiming
    // ------------------------------------------------------------------------
    private fun waitFor(timeout: Double, pred: (Mat) -> Boolean): Boolean {
        val deadline = now() + timeout
        while (now() < deadline) {
            val img = grab()
            val hit = try {
                pred(img)
            } finally {
                img.release()
            }
            if (hit) return true
            sleep(0.3)
        }
        return false
    }

    /**
     * The card, once the screen is back to the plain main screen.
     *
     * The skill that just played comes back the moment *it* is finished,
     * which is not the moment the game is: the dungeon's own result screens
     * are still closing, and the very first frame after a run is as likely to
     * be a reward window as a main screen. One reading of that frame is not a
     * measurement -- taken as one, it parked a whole run on "could not read
     * the quest card after the action" two seconds before the card was
     * plainly readable again.
     *
     * So the same two gates [tick] uses, and then the card: no banner, a
     * crisp auto button, a counter that reads. Null only after the timeout,
     * which is then a real answer rather than a race.
     */
    private fun cardOnceSettled(timeout: Double = SETTLE_TIMEOUT): Quest.Card? {
        val deadline = now() + timeout
        while (true) {
            val img = grab()
            val card = try {
                if (!stageFailed(img) && autoButton(img) != null) questCard(img) else null
            } finally {
                img.release()
            }
            if (card != null) return card
            if (now() >= deadline) return null
            sleep(0.5)
        }
    }

    /**
     * Tap the card, wait for the reward, close it, check the fall.
     *
     * No back key anywhere in here. The reward window is closed with a tap at
     * a fixed, checked-empty spot, and the proof the claim really happened is
     * the card being a *different* card.
     *
     * Returns (claimed, fell). `fell` is null for a structural failure -- the
     * window never opened or never closed, already parked here on the spot --
     * and true/false once every tap landed and the only open question is
     * whether the number actually moved; the caller gives that one case a
     * single extra try before it counts as a park.
     */
    private fun claim(card: Quest.Card, img: Mat): Pair<Boolean, Boolean?> {
        val beforeIst = card.ist
        val beforeZiel = card.ziel
        say("  step ${step + 1} ready (${card.ist}/${card.ziel}), claiming")
        tap(img, card.fx, card.fy)
        if (!waitFor(CLAIM_OPEN_TIMEOUT) { autoButton(it) == null }) {
            park("tapping the card did not open the reward window")
            return false to null
        }
        var closed = false
        for (attempt in 0 until CLAIM_CLOSE_TAPS) {
            tap(img, DEAD_TAP[0], DEAD_TAP[1])
            if (waitFor(CLAIM_CLOSE_STEP) { autoButton(it) != null }) {
                closed = true
                break
            }
        }
        if (!closed) {
            val last = grabOrNull()
            if (last != null) {
                try {
                    keepFrame(last, "reward-window-stuck")
                } finally {
                    last.release()
                }
            }
            park("the reward window did not close after $CLAIM_CLOSE_TAPS taps")
            return false to null
        }
        val after = grabOrNull() ?: return false to false
        val progress = try {
            questCard(after)
        } finally {
            after.release()
        }
        if (progress == null) return false to false
        // Proof that the claim landed is the card being a *different* card,
        // and the ist side falling is only the usual way that shows. On the
        // step where the routine hands over to one with another target it
        // does not fall at all: 100/100 was claimed and the next card read
        // 610/610, which is not "the counter did not fall", it is the next
        // quest. Judged on the ist side alone that came back as a failed
        // claim and cost the loop a retry on a card it had already collected.
        if (progress.ziel == beforeZiel && progress.ist >= beforeIst) return false to false
        say("  claimed, next is ${progress.ist}/${progress.ziel}")
        return true to true
    }

    // ------------------------------------------------------------------------
    // Playing
    // ------------------------------------------------------------------------
    /**
     * Did the action move the counter? Advance, retry once, or park.
     *
     * A quest that did not move after a real attempt is not a loss to retry
     * -- it is the wrong quest, and the fix is to try the one other step the
     * script could have meant, once, and park if that does not move it
     * either.
     *
     * [blocked] is that rule's exception, and it comes from the skill that
     * just ran: a reason why there was no real attempt at all. A counter
     * standing still is then explained, so it is no longer evidence of the
     * wrong quest -- the other candidate is not played, because that would
     * spend the other dungeon's tickets on a guess the card has already
     * answered -- and the park says what the skill saw instead of the counter
     * it could not have moved.
     */
    private fun afterAction(beforeIst: Int, beforeZiel: Int, blocked: String? = null) {
        val parkIn = if (blocked != null) PARK_RETRY_EMPTY else PARK_RETRY
        val card = cardOnceSettled()
        if (card == null) {
            park(blocked ?: "could not read the quest card after the action", parkIn)
            return
        }
        val ist = card.ist
        val ziel = card.ziel
        // What the card says now, kept where the caller and the shell can
        // both read it: the dungeon step asks it whether the quest is still
        // short ([stillUnfinished]), and a status line built from the reading
        // before the action would be a round out of date.
        lastProgress = ist to ziel
        if (ziel != beforeZiel) {
            // Something else moved the routine on already; the next tick
            // re-reads the script against this card fresh.
            return
        }
        if (ist > beforeIst) {
            say("  progress: $beforeIst/$beforeZiel -> $ist/$ziel")
            return
        }
        if (blocked == null && retriedStep != step) {
            retriedStep = step
            val alt = otherCandidate(ziel, card.nameLen to card.afterLen)
            if (alt != null) {
                say("  no progress after the action, trying the other step " +
                    "with the same target instead")
                advance(alt)
                return
            }
        }
        park(blocked ?: "an action did not move the counter (still $ist/$ziel)", parkIn)
    }

    /**
     * Activate the hologram device once, and prove the quest counted it.
     *
     * One activation per round, not fifty in a loop: every tap is aimed with
     * a frame taken after the last one landed and is checked against the
     * quest's own counter, the same rule the rest of this file follows. Fifty
     * of them take a few minutes, which is the cheapest part of this whole
     * routine.
     *
     * Returns true if a tap went out.
     */
    private fun runHoloStep(ist: Int, ziel: Int, img: Mat): Boolean {
        val auto = autoButton(img) ?: return false
        // The sentence used to open with "auto spend is off", which is the
        // only reason this tap is needed and a name no page on the phone
        // says any more. What it is doing is the part worth reading.
        once("holo", "  activating the hologram device from here, ${ziel - ist} to go")
        tap(img, auto.fx + HOLO_TAP_OFF[0], auto.fy + HOLO_TAP_OFF[1])
        val deadline = now() + HOLO_COUNT_WAIT
        while (now() < deadline) {
            sleep(0.5)
            val frame = grabOrNull() ?: continue
            val card = try {
                if (stageFailed(frame) || autoButton(frame) == null) null else questCard(frame)
            } finally {
                frame.release()
            }
            if (card == null || card.ziel != ziel) continue
            if (card.ist > ist) {
                holoDead = 0
                lastProgress = card.ist to card.ziel
                return true
            }
        }
        // The device has nothing to spend, or the tap missed. Either way a
        // second dead tap is the answer, not a fiftieth.
        holoDead += 1
        if (holoDead >= 2) {
            park("the hologram device was tapped twice and the quest did not " +
                "count either one -- out of tickets?")
        }
        return true
    }

    private fun runDungeonStep(ist: Int, ziel: Int) {
        val q = ROUTINE[step]
        val arg = q.arg ?: return
        // How many runs this quest still wants, asked before anything is
        // played -- the survey the skill is about to take answers how many
        // the game will hand out, and the two together say whether the step
        // can be finished at all today ([dungeonShort]).
        val needed = ziel - ist
        say("  step ${step + 1}: ${q.name} -- playing the dungeon")
        val bot = dungeonFor(arg, needed)
        bot.run()
        val short = dungeonShort(bot, needed)
        afterAction(ist, ziel, blocked = dungeonBlocked(bot))
        if (short != null && stillUnfinished(ziel)) {
            // The reason itself ends "before the daily reset", and this is
            // that sentence in a form the director can act on.
            switchOff(short, untilReset = true)
        }
    }

    /**
     * Were there fewer tickets left today than the quest wants?
     *
     * The player asked for this after watching the loop set out to finish
     * "Clear Fight! Bakemon 0/2" with one ticket in hand: it played the one,
     * the quest stood at 1/2, and from there the fixed order of the routine
     * meant nothing behind that step could be reached either. Nothing hands
     * out an attempt before the game's own daily reset, so there is nothing
     * here to come back to, and the loop switches itself off instead.
     *
     * The evidence is the survey the Dungeons skill takes off the card before
     * it opens anything ([DungeonSkill.counted], one entry because `only`
     * picks a single card): `total` is what the game still owes today,
     * tickets and ads together. What one skill learns belongs in something
     * the caller can read, and the caller has to ask (NOTES.md, "Silencing
     * another skill's log throws away its measurements").
     *
     * Only a *counted* total says this, never a missing one. Null is "the ads
     * are behind a symbol the game has not drawn yet" -- the film symbol is
     * drawn only while tickets read 0 (NOTES.md), so a card with tickets
     * left says nothing at all about its ads, and reading that as "not
     * enough" would stop the loop on a quest it could still finish.
     */
    internal fun dungeonShort(bot: DungeonRun?, needed: Int): String? {
        if (bot == null || needed <= 0) return null
        val budget = bot.counted.values.firstOrNull() ?: return null
        val total = usable(budget) ?: return null
        if (total >= needed) return null
        return "the dungeon had $total ticket${if (total == 1) "" else "s"} left " +
            "and this quest wants $needed -- it cannot be finished before the daily reset"
    }

    /**
     * Is the card in front of us still the same quest, still short?
     *
     * The last thing [afterAction] read, which is the freshest reading there
     * is. A quest somebody finished by hand in the meantime, or a card that
     * has moved on to another target, is not something to switch off over --
     * and an unreadable card is: the survey has already said the attempts
     * were too few, and that answer does not depend on the counter.
     */
    private fun stillUnfinished(ziel: Int): Boolean {
        val p = lastProgress ?: return true
        return p.second == ziel && p.first < ziel
    }

    /**
     * What the Dungeons skill read off the card, said out loud.
     *
     * The skill surveys both counters from the list before it opens anything
     * and skips a card that has nothing left -- but this loop used to hand it
     * a silenced log and then never ask what it had found. An empty
     * DemiDevimon therefore came back as "an action did not move the counter
     * (still 1/2)", once a minute, with nothing anywhere saying that the
     * tickets were gone: the player could see it on the card and the skill,
     * which had read the very same number, could not. A reading thrown away
     * is a reading nobody took.
     *
     * Returns a park reason where the card says the day is over on this
     * dungeon, null wherever there was something to play -- the quest counter
     * is the witness again then, the way it is everywhere else.
     */
    internal fun dungeonBlocked(bot: DungeonRun?): String? {
        if (bot == null) return null
        // `only` picks a single card, so the survey holds exactly one budget.
        // Empty means the list never opened at all, which is not something a
        // ticket count can be read out of.
        val budget = bot.counted.values.firstOrNull() ?: return null
        val tickets = budget.tickets
        val ads = budget.ads
        val total = budget.total
        if (usable(budget) == 0) {
            if (settings().adPass) {
                say("  the dungeon card reads 0 tickets and no ads left")
                return "the dungeon is out of tickets and ads until tomorrow"
            }
            say("  the dungeon card reads 0 tickets, and the ads are not yours " +
                "without the Ad Skip Pass")
            return "the dungeon is out of tickets until tomorrow"
        }
        if (total != null) {
            say("  the dungeon card reads $tickets ticket${if (tickets == 1) "" else "s"} " +
                "and $ads ad${if (ads == 1) "" else "s"}")
        } else if (tickets != null && tickets > 0) {
            // The film symbol is drawn only at 0 tickets, so above zero the
            // ads are simply unknown -- NOTES.md, and the reason a card with
            // tickets left never counts as empty here.
            say("  the dungeon card reads $tickets ticket${if (tickets == 1) "" else "s"}, " +
                "ads unknown until they run out")
        } else {
            say("  the counters on the dungeon card could not be read")
        }
        return null
    }

    /**
     * Tap the quest card straight into Special Summon. True if it did.
     *
     * Confirmed by [modeDots], never assumed -- and since 2026-09-22 that
     * confirmation is the whole of the way in: a tap that lands anywhere
     * else parks the step, because the menu is not opened from the main
     * screen any more (see [runSummonStep]).
     */
    private fun enterSummonByCard(mode: Int): Boolean {
        val img = grabOrNull() ?: return false
        val card = try {
            questCard(img)
        } finally {
            img.release()
        }
        if (card == null) return false
        tapAt(card.fx, card.fy)
        sleep(SUMMON_CARD_PAUSE)
        val after = grabOrNull() ?: return false
        return try {
            modeDots(after) == mode
        } finally {
            after.release()
        }
    }

    private fun runSummonStep(ist: Int, ziel: Int, img: Mat) {
        val q = ROUTINE[step]
        val mode = q.arg ?: return
        // No counting before the menu is open. Both numbers this step needs
        // -- the ticket badge and the "n /2" over View Ads -- are drawn on the
        // summon screen and nowhere else, so asked of the main screen they can
        // only answer null. Asked there anyway, they parked the step on "not
        // enough tickets for a summon, and no free ads left today" every
        // single time, with a full stock of tickets and without the menu ever
        // having been opened. A reader is allowed to say null; a caller is not
        // allowed to read that as a number.
        say("  step ${step + 1}: ${q.name} -- opening Special Summon")
        // The quest card is the only way in left, and it is also the better
        // one: the card on the main screen is the game's own deep link to the
        // mode's own screen, and [enterSummonByCard] proves it landed there
        // with the mode carousel's dots.
        //
        // What stood behind it until 2026-09-22 was `bot.openSummons()`,
        // which tapped the Summon icon and then the General tab of whichever
        // Special Summon tab the icon had opened. That second tap is gone
        // with the player's rule that nothing outside General is ever tapped
        // -- on SP Support the yellow button beside it is a 10x draw for
        // 3,000 bought gems (SummonSkill.onGeneral). So a card that does not
        // lead in parks the step, as it already did whenever that fallback
        // failed too.
        //
        // The bot is built after that, not before: a step that never got in
        // has nothing to leave, and `leaveSummons` in the finally below is a
        // look at the screen and possibly a tap on an X.
        if (!enterSummonByCard(mode)) {
            park("could not reach the summon menu")
            return
        }
        val bot = summonBot()
        try {
            // Now there is something to count, and it is worth saying out
            // loud: this is the number the step lives or dies on.
            val tickets = readOnFresh { Summon.ticketCounter(it) }
            val ads = readOnFresh { Summon.adsLeft(it) }
            say("  on the summon screen: ${tickets ?: "could not read"} tickets, " +
                "${ads ?: "could not read"} free ad(s), a draw costs ${MODE_PRICE[mode]}")
            // The ads first, because they are free. No reading of the quest
            // card in between: it is not on this screen at all, and the check
            // that used to sit here was reading the summon menu for a card
            // that is behind it. Two free ads cannot finish a quest that wants
            // thirty draws anyway -- what finishes it is the one draw below.
            val before = bot.adsWatched
            // Asked here, of the loop's own settings, and not left to the
            // bot: a free ad without the Ad Skip Pass is a video, and this
            // step is the one place a quest ever taps View Ads.
            val pass = settings().adPass
            if (pass) bot.watchAds(mode)
            val drawn = bot.spam(mode)
            if (drawn == 0 && bot.adsWatched == before) {
                park("nothing to draw with: ${tickets?.toString() ?: "an unreadable count of"} " +
                    "tickets against a price of ${MODE_PRICE[mode]}, and " +
                    if (pass) "no free ads left" else "the free ads are not yours without the Ad Skip Pass")
                return
            }
        } finally {
            bot.leaveSummons()
        }
        afterAction(ist, ziel)
    }

    private fun <T> readOnFresh(read: (Mat) -> T?): T? {
        val img = grabOrNull() ?: return null
        return try {
            read(img)
        } finally {
            img.release()
        }
    }

    // ------------------------------------------------------------------------
    // The seams the flow test replaces, as test_quest_flow.py replaces the
    // module functions and the two bots.
    // ------------------------------------------------------------------------
    internal open fun grab(): Mat = cap.grab()

    private fun grabOrNull(): Mat? = try {
        grab()
    } catch (e: CaptureError) {
        null
    }

    /**
     * A tap by fraction of the reference window, aimed with [img] -- the
     * frame this round was given, and never a fresher one: a second grab here
     * would be a second picture inside one round, which is the thing "one tap
     * a round" exists to stop.
     */
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

    internal open fun questCard(img: Mat): Quest.Card? = Quest.questCard(img)
    internal open fun autoButton(img: Mat): Dungeon.Button? = Dungeon.autoButton(img)
    internal open fun stageFailed(img: Mat): Boolean = Dungeon.stageFailed(img)
    internal open fun closeX(img: Mat): Quest.CloseX? = Quest.closeX(img)

    /**
     * Which mode banner the quest card landed on, or null for anywhere else.
     * A seam like the four above since 2026-09-22, because the summon step
     * now lives or dies on it: the card is the only way into Special Summon
     * left ([runSummonStep]), and the flow test has to be able to say
     * whether it led in.
     */
    internal open fun modeDots(img: Mat): Int? = Summon.modeDots(img)

    /**
     * The Dungeons skill for one quest step: that card alone, as many
     * tickets as the quest still wants ([needed], at least one -- two were
     * fixed here while every run was a win), the quest's own attempts
     * before Clear Previous Difficulty, and the survey on, because the
     * survey is what [dungeonShort] and [dungeonBlocked] read their answer
     * out of.
     *
     * Its log is silenced, as quest.py silences the bot it builds -- but not
     * its measurements: [DungeonRun.counted] is what [dungeonBlocked] asks
     * afterwards, and a reading thrown away is a reading nobody took
     * (NOTES.md).
     */
    internal open fun dungeonFor(arg: Int, needed: Int): DungeonRun {
        val set = dungeonSettings(arg, needed)
        val skill = DungeonSkill(
            cap,
            { set },
            log = { }, on = on, keep = keep, now = now)
        return object : DungeonRun {
            override fun run(): Outcome = skill.run()
            override val counted: Map<Pair<String, Int>, Dungeon.Budget> get() = skill.counted
        }
    }

    /** What [dungeonFor] hands the Dungeons skill: one card, [needed] tickets, the quest's own N. */
    internal fun dungeonSettings(arg: Int, needed: Int) = DungeonSkill.Settings(
        budgets = mapOf(arg to maxOf(1, needed)),
        // `useAds` defaulted to true here until 2026-09-24, whatever the
        // Dungeons page said: the quest loop's own ad video.
        useAds = settings().adPass,
        attemptsBeforeClear = settings().attemptsBeforeClear,
        survey = true)

    /**
     * What a card still owes today, as far as it is this player's to
     * spend: tickets and ads together with the Ad Skip Pass, the tickets
     * alone without it. Null where the card did not say ([dungeonShort]
     * and [dungeonBlocked] both read this, so they cannot disagree).
     */
    private fun usable(budget: Dungeon.Budget): Int? =
        if (settings().adPass) budget.total else budget.tickets

    /**
     * The Summons skill for one quest step: the two modes that have quests,
     * the free ads first, and one draw. Crest has no quest and is off.
     * `watchAdsFirst` stays on: whether the ads are watched at all is the
     * Ad Skip Pass's question, and [runSummonStep] asks it before it
     * calls [SummonRun.watchAds].
     */
    internal open fun summonBot(): SummonRun {
        val skill = SummonSkill(
            cap,
            { SummonSkill.Settings(skill = true, support = true, crest = false,
                                   watchAdsFirst = true, maxPerMode = 1) },
            log = { }, keep = keep, on = on, now = now)
        return object : SummonRun {
            override fun watchAds(mode: Int) = skill.watchAds(mode)
            override fun spam(mode: Int): Int = skill.spam(mode)
            override fun leaveSummons() { skill.leaveSummons() }
            override val adsWatched: Int get() = skill.adsWatched
        }
    }

    /**
     * Just enough of another skill for one quest step, so that the flow test
     * can stand in for it the way test_quest_flow.py stands in for
     * `D.DungeonBot` and `S.SummonBot`.
     *
     * Neither of the two is used through [Skill] here, and that is the
     * point: a quest step does not hand a screen over, it borrows the
     * emulator for one dungeon or one draw and reads what the borrower
     * measured. The Summons half is an interface rather than the class
     * because `SummonSkill` is final and its `watchAds`, `spam` and
     * `leaveSummons` are `internal fun` rather than `internal open fun` --
     * see the session's report. It held `openSummons` and `gotoMode` too
     * until 2026-09-22: the first is gone from the skill altogether and the
     * second had no caller left once the way in was the quest card alone.
     */
    interface DungeonRun {
        fun run(): Outcome

        /** `DungeonBot.counted`: what the survey read off the card it played. */
        val counted: Map<Pair<String, Int>, Dungeon.Budget>
    }

    interface SummonRun {
        fun watchAds(mode: Int)
        fun spam(mode: Int): Int
        fun leaveSummons()

        /** `SummonBot.stats["ads"]`: free ads watched so far this run. */
        val adsWatched: Int
    }

    companion object {
        /**
         * The quest loop's attempts before Clear Previous Difficulty where
         * the page has no number yet: 4, the plan's proposal of 2026-09-23
         * -- a quest wants its two clears today, not the best rewards.
         */
        const val ATTEMPTS_BEFORE_CLEAR = 4

        // --------------------------------------------------------------
        // The routine, as a script
        // --------------------------------------------------------------
        // What the game itself hands out, in this order, repeating after the
        // last one. `target` is the only machine-readable property of a step
        // -- and it is not unique: 2 appears twice, 30 four times, 50 six
        // times. Only the order tells them apart, which is the whole reason
        // the loop keeps a remembered position rather than picking a step by
        // its numbers.
        const val DUNGEON = "dungeon"
        const val SUMMON = "summon"
        const val HOLO = "holo"
        const val ENEMIES = "enemies"
        const val STAGE = "stage"

        /** Positions in SkillSettings.DUNGEON_NAMES: apocalymon 0, demidevimon 1, bakemon 2. */
        const val DEMIDEVIMON = 1
        const val BAKEMON = 2

        /** SummonSkill.SKILL, SummonSkill.SUPPORT, named here for the routine's sake. */
        const val SKILL = SummonSkill.SKILL
        const val SUPPORT = SummonSkill.SUPPORT

        /**
         * What a draw of each mode costs, summon.py:921. SummonSkill has no
         * table of its own, so this is the one place on this side that says
         * it; it is the game's price list rather than a threshold, and it is
         * read for a log line and a park reason and nothing else.
         */
        val MODE_PRICE = mapOf(SKILL to 30, SUPPORT to 30, SummonSkill.CREST to 10)

        /**
         * One entry of the routine. `chars` is the pair of word lengths on
         * the counter's own row: how many letters stand in front of the
         * number, and how many behind it. It is the one thing on the card
         * that says *which* quest this is rather than how far along it is,
         * and it needs no stored letter shapes -- see Quest.NAME_H_MIN.
         *
         * Measured across one full turn of the routine, 23 readable cards
         * (debug_quest/, DGUP_DUMP_QUEST=1). Every one of them fell on
         * exactly these numbers, with no spread at all:
         *
         *   the card says                 in front   behind   n
         *   "DemiDevimon 0/2"                11        -      2
         *   "Bakemon 0/2"                     7        -      2
         *   "Defeat 12/50", "Defeat 0/100"    6        -      8
         *   "Draw 0/30 Skill"                 4        5      3
         *   "Draw 0/30"                       4        -      3
         *   "0/50 times"                      -        5      4
         *   "605/605"                         -        -      1
         *
         * A dash is "nothing on that side", counted as zero when it is
         * compared. The pair is what separates the two quests that share a
         * target: 4/5 against 4/0 for the two summon steps, where the word in
         * front is "Draw" on both and only what follows the number differs.
         * A null pair means nobody has counted that card yet, and then the
         * target decides alone, the way it did before any word was read.
         */
        class Entry(val target: Int?, val kind: String, val arg: Int?,
                    val chars: Pair<Int, Int>?, val name: String)

        val ROUTINE: List<Entry> = listOf(
            Entry(2, DUNGEON, DEMIDEVIMON, 11 to 0, "Clear Fight! DemiDevimon, 2x"),
            Entry(2, DUNGEON, BAKEMON, 7 to 0, "Clear Fight! Bakemon, 2x"),
            Entry(50, HOLO, null, 0 to 5, "Activate the Hologram Device, 50x"),
            Entry(50, ENEMIES, null, 6 to 0, "Defeat 50 enemies"),
            Entry(30, SUMMON, SKILL, 4 to 5, "Draw 30 Skill Card Summons"),
            Entry(30, SUMMON, SUPPORT, 4 to 0, "Draw 30 Support Summons"),
            Entry(50, HOLO, null, 0 to 5, "Activate the Hologram Device, 50x"),
            Entry(100, ENEMIES, null, 6 to 0, "Defeat 100 enemies"),
            // The stage step names a stage rather than a count, and the number
            // goes up every time round -- by five or ten, the player says, and
            // it names the stage the game wants reached. It is far behind: 605
            // on the turn this was watched, against a player standing at stage
            // 8,154. So the card arrives already finished, 605/605, and is
            // there to be claimed rather than played, and will stay that way
            // for a very long time. A null target means "whatever number it
            // shows"; a step like that is never matched on its target and
            // always on its word pair, which for this card is the only one in
            // the routine with nothing on either side of the number.
            Entry(null, STAGE, null, 0 to 0, "Clear the stage"),
            Entry(50, HOLO, null, 0 to 5, "Activate the Hologram Device, 50x"),
            Entry(50, ENEMIES, null, 6 to 0, "Defeat 50 enemies"),
            Entry(30, SUMMON, SKILL, 4 to 5, "Draw 30 Skill Card Summons"),
            Entry(30, SUMMON, SUPPORT, 4 to 0, "Draw 30 Support Summons"),
            Entry(50, HOLO, null, 0 to 5, "Activate the Hologram Device, 50x"),
            Entry(100, ENEMIES, null, 6 to 0, "Defeat 100 enemies"),
        )

        // --------------------------------------------------------------
        // The loop's own numbers, quest.py's, each with its sentence
        // --------------------------------------------------------------
        /** The pace of a round, `passive.TICK`'s own: 2 s on the main screen. */
        const val TICK = 2.0

        /**
         * How long a park lasts before the same step is tried again. Long
         * enough that a switched-off box or an empty ticket stock is not
         * hammered every two seconds; short enough that turning the box back
         * on is noticed within a couple of minutes rather than needing a
         * restart.
         */
        const val PARK_RETRY = 60.0

        /**
         * The same, for a step that is out of attempts until the game's daily
         * reset. Nothing is going to change about an empty ticket counter
         * within the minute, and every retry is a whole trip through the
         * dungeon list -- opened, scrolled, surveyed, closed -- with the
         * screen taken off the passive helper for the duration.
         *
         * Since 2026-09-22 a park with this retry is never retried at all:
         * it is the mark by which [park] tells the day's own ending from
         * every other, and that ending is a stop until [RESET_HOUR]
         * ([switchOff], [lockedForTheDay]). It used to be "short enough that
         * tickets bought by hand are noticed within a quarter hour", and the
         * player decided against that: tickets bought by hand are played by
         * hand, and the lock is on the record where it can be taken away.
         */
        const val PARK_RETRY_EMPTY = 900.0

        /**
         * When the game hands the day's dungeon tickets out again: eight in
         * the morning, in the player's time zone. The player's word
         * (2026-09-22), not a measurement -- and the zone is theirs rather
         * than the phone's, so that a phone on holiday still waits for the
         * same reset. If the game's reset is in fact fixed to another zone,
         * this is an hour out for the weeks their clocks disagree, which is
         * an hour of not trying, never an hour of trying too early on the
         * wrong side of it.
         */
        val RESET_ZONE: ZoneId = ZoneId.of("Europe/Vienna")
        const val RESET_HOUR = 8

        private val WHEN_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /**
         * The next reset after [nowEpoch], as epoch seconds: today's
         * [RESET_HOUR] if that is still ahead, otherwise tomorrow's. Exactly
         * on the hour counts as gone by -- a loop that finds no tickets at
         * 08:00:00 has just watched the reset hand out none.
         *
         * Through java.time on purpose: the day the clocks change is 23 or
         * 25 hours long, and 08:00 stays 08:00 (QuestSkillTest).
         */
        fun nextReset(nowEpoch: Double): Double {
            val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli((nowEpoch * 1000).toLong()), RESET_ZONE)
            var reset = now.toLocalDate().atTime(RESET_HOUR, 0).atZone(RESET_ZONE)
            if (!reset.isAfter(now)) reset = now.toLocalDate().plusDays(1).atTime(RESET_HOUR, 0).atZone(RESET_ZONE)
            return reset.toEpochSecond().toDouble()
        }

        /** "2026-09-23 08:00", in [RESET_ZONE] -- the log's and the row's word for a lock. */
        fun whenText(epoch: Double): String =
            ZonedDateTime.ofInstant(Instant.ofEpochMilli((epoch * 1000).toLong()), RESET_ZONE).format(WHEN_FMT)

        /**
         * Two ways to end, because there are two ways for a card to stand
         * still. A park says what is in the way and comes back to it, and
         * where the thing in the way is a switched-off box or an empty ticket
         * stock it comes back to the same answer for ever: this many tries of
         * the same reason on the same step -- three minutes at [PARK_RETRY]
         * -- is the run saying it cannot get past this one. A park carrying
         * the long retry needs no tries at all: that one is the card itself
         * saying the day is over on this step.
         */
        const val PARK_GIVE_UP = 3

        /**
         * The catch-all under both. A step the loop is only waiting on --
         * enemies to be beaten, holograms the Auto Spend switch is activating
         * by itself, a screen in the way that nothing here can close -- never
         * parks at all, so nothing above would ever end it. The same quarter
         * of an hour [PARK_RETRY_EMPTY] already calls "not going to change
         * today".
         */
        const val NO_PROGRESS_AFTER = 900.0

        const val CLAIM_OPEN_TIMEOUT = 8.0

        /**
         * Closing the reward window: how many taps at the dead spot, and how
         * long each one is given before the next.
         *
         * It was two taps of eight seconds, and a live run found the shape of
         * that wrong rather than the total. Four windows closed on the first
         * tap; the fifth, opened 25 seconds after the one before it, swallowed
         * both -- and eight seconds of waiting in between bought nothing,
         * because a window that is going to close does it within a second.
         * What a swallowed tap needs is another tap, not more patience.
         *
         * The dead spot opens nothing on the plain main screen
         * (player-checked), so a tap too many costs nothing at all -- which is
         * what makes five of them cheaper than two.
         */
        const val CLAIM_CLOSE_TAPS = 5
        const val CLAIM_CLOSE_STEP = 3.0

        const val BLIND_AFTER = 60.0

        /**
         * How long the card is given to come back after a skill has played.
         * The Dungeons skill returns while the game is still closing its own
         * result screens, and the first frame after a run is regularly a
         * window rather than the main screen -- see [cardOnceSettled]. Long
         * enough for that, short enough that a game genuinely stuck somewhere
         * else is still parked on within half a minute.
         */
        const val SETTLE_TIMEOUT = 30.0

        /**
         * The hologram device, for the one step that can be played with a
         * single tap. Where the game's own Auto Spend is running it does this
         * by itself -- watched over a full turn of the routine, the counter
         * went to 50/50 twice without a single tap from here -- and the tap
         * is then spent on nothing. Without it, this is the step the loop
         * could otherwise only park on, so the tap goes out either way.
         *
         * Never a stored position. The device is found from the auto button,
         * which every reader here already has to find anyway, and which sits
         * at its left. Measured on two window shapes of the same screen:
         *
         *                        the auto button      the device
         *   732 x 1341            0.362 / 0.766      0.477 / 0.816
         *   674 x 1238            0.370 / 0.765      0.485 / 0.815
         *
         * The offset holds to a thousandth across both, and the target it
         * lands on is the glass cylinder itself, checked by eye on both
         * frames.
         */
        val HOLO_TAP_OFF = doubleArrayOf(0.115, 0.050)

        /**
         * How long the game is given to count an activation. The device runs
         * an animation of a few seconds and the quest counter only moves once
         * it has finished, so a tap judged straight away always looks like a
         * failure.
         */
        const val HOLO_COUNT_WAIT = 12.0

        /**
         * Getting out of a screen that is not the main screen.
         *
         * How long the screen is given to come back by itself before an X is
         * reached for. Longer than [SETTLE_TIMEOUT] on purpose: the 30 s
         * there is what a skill's own result screens take to finish closing,
         * and this must not start pressing things while that is still going
         * on. It is also longer than any animation the game plays, and it is
         * not a hurry -- a screen nobody is going to close is just as stuck in
         * a minute as it is now.
         */
        const val STUCK_AFTER = 45.0

        /**
         * Between taps once it has started, and how many before it gives up.
         * A swallowed tap wants another tap rather than more patience
         * ([CLAIM_CLOSE_TAPS] learned that the hard way), and a screen can be
         * two deep -- the summon reward over the summon menu -- so six taps is
         * room for every layer the game has ever put up plus swallowed ones.
         */
        const val STUCK_TAP_EVERY = 3.0
        const val STUCK_TAPS_MAX = 6

        /**
         * The dead spot that closes the reward window. Player-checked in the
         * game itself: a tap here opens nothing on the plain main screen.
         * Left edge, half height -- outside PassiveSkill.BOND_TAP_LIMITS, so
         * it is provably not a figure, and far from the "Tap to close" label
         * itself (0.5, 0.82), which sits over the hologram device on the
         * screen behind the window and would open that instead if the window
         * had already closed by itself.
         */
        val DEAD_TAP = doubleArrayOf(0.04, 0.47)

        /**
         * How far a counted name may sit from the length written into the
         * routine and still be that quest. Two: a pair of letters merging
         * costs one, a speck of card edge surviving the floor costs one the
         * other way.
         *
         * The two lengths that have to stay apart are 7 and 11, and at a
         * tolerance of two their bands meet at exactly 9 -- a reading of 9
         * would fit both and identify neither. That is not the failure it
         * looks like: a card that fits two steps is a card that has said
         * nothing, and [matches] then leaves the script where it is, which is
         * what the whole loop did before any word was counted. What must never
         * happen is the other way round -- a clean 7 fitting the eleven-letter
         * step -- and four apart with a tolerance of two it cannot.
         */
        const val NAME_TOL = 2

        /**
         * After the quest card has been tapped into Special Summon, how long
         * before the screen behind it is asked what it is. `SummonBot.bot
         * .pause_long` on the PC, which is the same one second of patience.
         */
        const val SUMMON_CARD_PAUSE = 1.5

        /** The word pair, said in a log line. */
        fun wordsSaid(words: Pair<Int, Int>?): String {
            if (words == null || (words.first == 0 && words.second == 0)) {
                return "no word beside the number"
            }
            val before = if (words.first != 0) "${words.first} characters" else "nothing"
            val after = if (words.second != 0) "${words.second}" else "nothing"
            return "$before in front of the number, $after behind it"
        }
    }
}
