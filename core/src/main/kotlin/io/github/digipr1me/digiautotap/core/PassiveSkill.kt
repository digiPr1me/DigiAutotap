package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import java.util.Locale

/**
 * The passive helper, passive.py's `PassiveBot.tick` (passive.py:1199) as a
 * [Skill] under the director. One round: one look at the plain main screen,
 * at most one tap, and no end -- the director calls it again next round
 * (Skill.kt, "The passive helper is the one skill with no end").
 *
 * What it does on the main screen is the bond token: a speech bubble over
 * the partner means a token is waiting, and it is collected by tapping the
 * figure, not the bubble. That is the whole of the feature a player sees --
 * the page is called Bond token and has one box, "Collect for all of the
 * Digimon" (SkillSettings, and [BondTourSkill] is what walks).
 *
 * The hologram counter is read every round as well, and read is all that
 * is done with it: it is the game's own number, worth a line in the log on
 * the screen where it is plainly drawn. Pressing the auto button when the
 * counter has stopped falling was passive.py's Auto Spend, and it is gone
 * from here. The PC had already taken that box out of its interface
 * (app.py) and the phone never had one, so what was left was a press
 * nobody could ask for, inside a feature a player knows as Bond token.
 * What it was measured on is in this file's history.
 *
 * The readers are P2's: [Passive.bondBubble], [Passive.holoCounter],
 * [Passive.partnerMenu] and [Dungeon.autoButton]. Nothing here reads a
 * pixel of its own. What is here is the loop -- the aim, the fences around
 * it, the clocks -- carried over from passive.py constant by constant, each
 * with the sentence that says where it came from. A number is changed in
 * passive.py first, with a measurement, then here (NOTES.md, "Two
 * implementations, one direction").
 *
 * **What the director takes off this skill's hands.** The PC's helper looks
 * at the whole screen every two seconds and has to work out for itself
 * whether it is looking at the main screen at all. Under the director it is
 * only ever called with a frame `classify` has already named [Director.MAIN],
 * so three of passive.py's guards belong to the director now and are not
 * repeated here:
 *
 *   `stand_back`    there is no second claimant on the phone: the director
 *                   is the only caller, and it calls one skill at a time.
 *   `may_tap`       there is no shared mouse. The player's hand is the
 *                   director's three-second rule, and the main screen is
 *                   deliberately outside it (DirectorLoop.mainRound: "no
 *                   three seconds on the main screen").
 *   `obscured`      the accessibility service reads the display itself, so
 *                   there is no window for another window to cover.
 *
 * The guards that stayed are the ones about the game: the Stage Failed
 * banner, the auto button as proof that nothing is drawn over the screen,
 * and whose Partner window it is. They stay because a frame can still be
 * any of those between the director's look and this round's tap.
 */
class PassiveSkill(
    private val cap: Capture,
    /**
     * The shell's settings, read fresh every round. One key is left of the
     * five this skill used to read, and it is not even this round's:
     * `passive_all_digimon`, which [BondTourSkill] asks with. Asked rather
     * than held, so that a box ticked while the director runs takes effect
     * at once, both ways.
     */
    private val flag: (String, Boolean) -> Boolean,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    private val now: () -> Double = { System.nanoTime() / 1e9 },
    /** The main switch, asked between two actions and never in the middle of one. */
    private val on: () -> Boolean = { MainSwitch.on },
    /**
     * Keep a frame that could not be read, for a bug report: passive.py's
     * `_dump`, which writes into `debug_passive/`. core writes no files, so
     * the app passes the same callable the director is given. Rate limited
     * here, by [DUMP_MAX] and [DUMP_EVERY], because that limit is part of
     * the rule and not part of the writing.
     */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
) : Skill {

    override val key = "passive"
    // What the log and the overlay plate call it -- Overlay.taskLabel makes
    // "Bond token" of this -- so it is the name the Skills list gives the
    // row and not the name the class has carried since passive.py.
    override val name = "the bond token"

    /**
     * The plain main screen, and that alone. The Partner window this skill's
     * own tap opens is [Director.PARTNER_WINDOW] to `classify`, and it is in
     * this list for one reason: the window is the price of tapping a bubble
     * off a single frame, and nobody else can tell this skill's window from
     * the player's. See [partnerRound], which closes its own and no other.
     */
    override fun worksOn(screen: String): Boolean =
        screen == Director.MAIN || screen == Director.PARTNER_WINDOW

    /**
     * There is nothing left to ask. The page had a switch of its own,
     * "Watch the main screen", beside the row's switch in the Skills list,
     * and the two said the same thing twice: with the Bond token row
     * included there is always a bubble worth looking for. So the row's
     * switch is the whole of it, and the director asks that one itself
     * (`included && hasBudget`, DirectorLoop.mainRound).
     */
    override fun hasBudget(): Boolean = true

    // ------------------------------------------------------------------------
    // What one round saw, for the test and for whoever asks
    // ------------------------------------------------------------------------
    /** passive.py's `seen` dict: what this round found and what it did about it. */
    class Seen(
        var clear: Boolean = false,
        var holo: Int? = null,
        var bubble: Passive.Bubble? = null,
        var did: String? = null,
    )

    /** The last round's [Seen]. The shell's status line reads it; nothing else does. */
    var seen = Seen()
        private set

    /**
     * What this round did, for the TODAY card ([SkillStats], [Counted]).
     * A round, not a run: this skill has no end, so what is counted is the
     * one thing the round achieved, and the adding up is the card's.
     *
     * Counted on the round that saw the bubble stay away ([DID_BOND_GONE]),
     * not on the round that tapped: a tap at a dying Digimon is a tap and
     * not a token, and the two taps of 2026-09-21 23:50 went into the card
     * as two tokens for one.
     */
    val lastCounts: Map<String, Int>
        get() = mapOf("tokens" to (if (seen.did == DID_BOND_GONE) 1 else 0))

    companion object {
        // passive.py's loop constants, in its order, minus the four the auto
        // button took with it: STALL_AFTER (20 s, "has the counter stopped"),
        // PRESS_CHECK, PRESS_GAPS and the state they were read against. The
        // 20 s is still worth knowing as a number -- it is the third of the
        // three thresholds NOTES.md keeps apart, beside Director.IDLE_SHARE
        // and MOVING_SHARE -- and Director and DungeonSkill name it in their
        // own sentences where they say what they are *not*.

        /**
         * What a round did, in the words the log, [lastCounts] and the tour
         * read. Two sentences, because a tap and a token are two things:
         * [DID_BOND] is the round that tapped the figure under a bubble, and
         * [DID_BOND_GONE] the round, [BOND_WATCH] later, that found the
         * bubble had stayed away -- the token, as far as the screen can say.
         */
        const val DID_BOND = "tapped the figure for the bond token"
        const val DID_BOND_GONE = "collected the bond token"

        // -- the settings' keys, as app.py writes them ----------------------
        const val PASSIVE_ALL_DIGIMON = "passive_all_digimon"

        // -- the bond token --------------------------------------------------
        /**
         * Where the figure stands, from the bubble that owns it: below and
         * to the left, by this much of the window.
         *
         * **The bubble does say where to tap, because the slot moves.** The
         * aim was a constant from 2026-09-19 to 2026-09-22, the mean of
         * every tap that had ever landed on the figure (fx 0.421, fy 0.434),
         * chosen after two impostor bubbles at fy 0.459 and 0.477 had put an
         * offset tap on the Buddy. That constant was measured on 9:16 frames
         * only, and the Poco F3 (1080 x 2400, corpus/passive/phone_tall_*)
         * stands the partner's slot elsewhere -- 0.1 gw further right on
         * three frames, and on a fourth of the same afternoon 0.03 further
         * left (phone_tall_no_bubble_slot_left_152202). The phone's log of
         * 2026-09-22 has hundreds of taps at the constant, 15:29 to 19:54,
         * every one on bare ground beside the Digimon: no token, and no
         * Partner window either, which is what a tap on a figure with no
         * token would have opened. The badge, the bar and the bubble are
         * one drawing, the figure stands under the bar, and the bubble is
         * the part the reader finds.
         *
         * The offset is the laboratory's own, `passive.BOND_TAP_OFF`,
         * measured bubble to the middle of the figure on three frames at
         * three window sizes: -0.0710/+0.0756, -0.0709/+0.0754,
         * -0.0707/+0.0766. Marked again on the phone: the bubble at
         * 0.590/0.391 and Guilmon at 0.514/0.461 (-0.076/+0.070); mid-attack
         * 0.590/0.402 against 0.497/0.481 (-0.093/+0.079). The figure is
         * about 0.07 gw across and 0.10 gh tall, so the point lands on the
         * body in every one of the five.
         *
         * What made the constant safe was that a wrong bubble could only
         * cost a Partner window. This costs a tap wherever the impostor
         * points, and the fences below are what bound it: [BOND_TAP_LIMITS]
         * and [CHAT_ROW].
         */
        val BOND_TAP_OFF = doubleArrayOf(-0.071, 0.076)

        /**
         * Where a tap may land: [Passive.BOND_MIDDLE] moved by the offset
         * and cut short at the bottom. fx 0.40 to 0.64 and fy 0.26 to 0.50
         * become 0.329 to 0.569 and 0.336 to 0.576; the bottom stops at
         * 0.52, because the highest real bubble there is stands at fy 0.41
         * (the phone; 0.40 on the 9:16 frames) and aims at 0.49, while the
         * Buddy the old offset once tapped stands at 0.535. A bubble lower
         * than 0.444 has never been one. See [Passive.BOND_MIDDLE] for why
         * the sides are where they are.
         */
        val BOND_TAP_LIMITS = doubleArrayOf(0.32, 0.57, 0.33, 0.52)

        /**
         * The chat line, which is not a place to tap.
         *
         * The game writes the world chat across the bottom of the field,
         * over the scenery and over whoever is standing there: a translucent
         * bar with the last message in it, a round chat symbol at its left
         * and a collapse arrow left of that. A tap anywhere on it opens the
         * chat window -- reported from a live session, twice, with the
         * window then swallowing the taps of the rounds that followed.
         *
         * There is no reading to be done here. The bar is where it is, and
         * the fix is simply not to aim into it:
         *
         *     the bar, over three frames at three shapes, window and ADB
         *         top     0.5853 to 0.5873    bottom  0.6136 to 0.6140
         *         left    0.1045 (collapse arrow)
         *         right   0.5921 to 0.6026, growing with the message
         *
         * The zone below covers that with about three per cent of air on
         * every side, because it is a place to stay out of and being too
         * large costs nothing. On the other side of it, the taps a real
         * bubble has ever asked for run from 0.393 to 0.477, a fifth of the
         * picture above the zone. So this guard is a fence at the end of a
         * field nobody walks in. It stays because that is what a fence is
         * for.
         *
         * **The chat window is never closed either, and that is deliberate:
         * this skill cannot tell its own tap from the player's hand**, and
         * closing a window somebody just opened is worse than leaving one
         * open. With nothing aiming into the bar there is nothing to close
         * -- and while a window is up the auto button is dimmed, every round
         * does nothing, and that is the right answer whoever opened it.
         */
        val CHAT_ROW = doubleArrayOf(0.06, 0.64, 0.570, 0.630)

        /**
         * After a tap on the figure, how long to leave the bubble alone
         * before tapping again. The game needs a moment to take the token
         * and clear the bubble -- measured once at under five seconds -- and
         * at a two second pace the next round would otherwise tap a token
         * that is already collected, which opens the Partner window for
         * nothing.
         */
        const val BOND_RETRY = 4.0

        /**
         * How often a tap is repeated while the bubble is still there. If
         * three have not made it go away, something is wrong with the aim
         * and more will not fix it.
         */
        const val BOND_TRIES = 3

        /**
         * After a tap on the figure: how long the round asks to be looked
         * at fast ([beat], Director.BEAT_WATCH), and how long the bubble
         * has to stay away before the token counts as collected.
         *
         * **The bubble going is not the token being taken.** The Digimon
         * fights while the bubble is up, and when it dies the bubble goes
         * with it; it comes back with the respawn, token and all. Measured
         * live on 2026-09-21 at 23:50 (the app's log): tapped at :01, the
         * bubble still up at :05, gone at :07, back at :09, tapped, gone at
         * :11 and not seen again for twenty minutes. So the first tap had
         * found a dying Digimon, the bubble was hidden for two to four
         * seconds, and the round called that first going "token collected"
         * -- and at the slow beat the second tap came eight seconds after
         * the first. The player, on 2026-09-22: three seconds from the
         * bubble to a tap, the Digimon dead, the screen redrawn, the token
         * back, and often too long. Ten seconds is the measured hiding time
         * with a factor of two and a half on top; the price is twenty fast
         * rounds once every twenty minutes, and none at all while a bubble
         * stands through its three taps, which stays at the slow beat.
         */
        const val BOND_WATCH = 10.0

        // -- the Partner window ----------------------------------------------
        /**
         * Whose window is it? This is the whole of what was once missing,
         * and leaving it out closed windows the player had just opened for
         * themselves -- reported from a live session, and once for a window
         * that was not even this one.
         *
         * The skill knows its own: it taps the figure only when it has seen
         * a bubble, and a Partner window is what that tap opens when the
         * token turns out to have been collected already. So a window is
         * this skill's for this long after its own tap on the figure, and
         * nobody else's ever.
         *
         * Fifteen seconds against a round of two: the window is seen on the
         * next round, and two taps and the back key all fall well inside it.
         * It does not get extended -- an ownership that can be pushed
         * forward is one that drifts onto whatever is on screen when the
         * player finally lets go, which is the bug itself in slower motion.
         * Miss the fifteen seconds and the window waits to be closed by
         * hand, which is the safe way round.
         */
        const val PARTNER_OWN = 15.0

        /**
         * How it is closed: a tap above it, not the back key, and never an X.
         *
         * The back key was the first answer and it is a loaded gun in this
         * game. With no dialog open it raises "Return to the title screen?",
         * and in one live run it did exactly that three times in three
         * minutes -- the window had closed by itself between the frame and
         * the key. A tap cannot raise it at all.
         *
         * 0.15 of the height is above the panel, whose top edge measured
         * 0.199 over three frames, and what sits there is the stage banner:
         * a label, not a button. So the tap either closes the window or does
         * nothing.
         */
        val PARTNER_CLOSE = doubleArrayOf(0.50, 0.15)

        /**
         * After this many taps that changed nothing, the back key gets its
         * turn after all. Better a prompt that gets cancelled than a helper
         * stuck behind a window it opened itself.
         */
        const val PARTNER_TAPS = 2

        // -- the quiet rounds --------------------------------------------------
        /**
         * How long the counter may be unreadable on a clear screen before a
         * frame is kept. Long enough that the XP numbers rising off the
         * device, which cover the digits for a few seconds at a time, never
         * trigger it.
         */
        const val BLIND_AFTER = 60.0

        /**
         * A line a minute on a screen where nothing is happening, and on a
         * screen where something is in the way. Both matter: a log that says
         * one thing and then goes silent for twenty minutes cannot be told
         * from a log that stopped, and that is exactly how the
         * red-background bug looked in the window.
         */
        const val HEARTBEAT = 60.0

        /**
         * Keeping a frame is a diagnosis, not a habit, and every one of
         * these files is a picture of the game screen.
         */
        const val DUMP_MAX = 5
        const val DUMP_EVERY = 300.0

        /**
         * 77605 as 77,605, the way the game writes it -- and the way it is
         * written wherever this runs. Every number this skill logs goes
         * through [Locale.US] for that reason: a German phone would
         * otherwise group with dots and put a comma where the decimal point
         * belongs, and the log of a bug report would no longer read like
         * the log the PC writes.
         */
        fun num(value: Int): String = String.format(Locale.US, "%,d", value)

        /** Would a tap here land on the chat line? */
        fun onChatRow(fx: Double, fy: Double): Boolean =
            CHAT_ROW[0] <= fx && fx <= CHAT_ROW[1] && CHAT_ROW[2] <= fy && fy <= CHAT_ROW[3]

        /** Where this bubble says the figure is, wherever that falls: see [BOND_TAP_OFF]. */
        fun aimAtFigure(bubble: Passive.Bubble): DoubleArray =
            doubleArrayOf(bubble.fx + BOND_TAP_OFF[0], bubble.fy + BOND_TAP_OFF[1])

        /**
         * Where to tap for the token, or null if that is not a place to tap.
         *
         * Two ways of being no place: outside the field, which means the
         * bubble was never a bubble, and on the chat line, which means the
         * figure is standing behind it. Both end the round the same way and
         * the caller says which it was.
         */
        fun figureFromBubble(bubble: Passive.Bubble): DoubleArray? {
            val aim = aimAtFigure(bubble)
            val (x0, x1, y0, y1) = BOND_TAP_LIMITS.toList()
            if (!(x0 <= aim[0] && aim[0] <= x1 && y0 <= aim[1] && aim[1] <= y1)) return null
            if (onChatRow(aim[0], aim[1])) return null
            return aim
        }
    }

    // ------------------------------------------------------------------------
    // What one round carries into the next
    // ------------------------------------------------------------------------
    /**
     * The counter's last reading and when it changed -- what the log's
     * "(+n in m s)" is made of. They used to be the evidence a press stood
     * on as well; with the auto button gone they are only ever read out.
     */
    private var lastValue: Int? = null
    private var lastChange: Double? = null
    /** A bubble that survives its taps is given up on until it goes. */
    private var bondTries = 0
    private var nextBond = 0.0
    /** When the figure was last tapped: [BOND_WATCH] runs from here. */
    private var bondTappedAt = Double.NEGATIVE_INFINITY
    /** A tap went out and the bubble has not yet stayed away for [BOND_WATCH]. */
    private var collectPending = false
    private var partnerTries = 0
    /** When this skill last tapped the figure -- the only way it can open a window. */
    private var openedPartner = 0.0
    private var lastHeartbeat = 0.0
    private var said: String? = null
    private var dumps = 0
    private var nextDump = 0.0
    /** When the counter first went unreadable while the screen was clear. */
    private var blindSince: Double? = null

    /**
     * Say this only when it is not what was said last time. A passive skill
     * ticks all day: "a window is open that this did not open" belongs in
     * the log the first time and not four hundred times after it.
     */
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

    /** Keep one frame that could not be read, for a bug report (passive._dump). */
    private fun dump(img: Mat, tag: String) {
        if (dumps >= DUMP_MAX || now() < nextDump) return
        dumps += 1
        nextDump = now() + DUMP_EVERY
        keep(img, tag)
    }

    // ------------------------------------------------------------------------
    // One round
    // ------------------------------------------------------------------------
    /**
     * One pass over the screen. Taps at most once.
     *
     * Never twice: the second tap would be aimed with a picture taken before
     * the first one landed.
     *
     * **It never answers [Result.DONE].** A round is not a piece of work
     * that finishes; the director asks again in two seconds, all day. There
     * is no value in [Result] for "still going" -- see the note at the end
     * of this file -- so an ordinary round answers [Result.RETIRED] with the
     * sentence that says so, and the one answer that really means something
     * is [Outcome.STOPPED] when the main switch has gone off.
     */
    override fun work(img: Mat): Outcome {
        val round = Seen()
        seen = round
        if (!on()) return Outcome.STOPPED

        if (Dungeon.stageFailed(img)) {
            // Any click at all dismisses this banner, so a tap meant for the
            // figure or the button would be eaten by it.
            once("failed", "  the Stage Failed banner is up, not tapping through it")
            heartbeat(blocked = "the Stage Failed banner is up")
            return going()
        }
        if (Passive.partnerMenu(img) != null) return partnerRound(img, round)
        partnerTries = 0

        // The auto button answers "is the plain main screen in front, with
        // nothing drawn over it". The game dims what is behind a dialog and a
        // dimmed disc loses its edges out of the colour mask, so a button
        // found at full roundness is proof of both things at once: the screen
        // is clear, and here is where to tap it. Measured 0.72 fill on a
        // clear screen against 0.43 with a dialog over it.
        //
        // `popup_ok` is deliberately not asked. It answers true on a
        // perfectly ordinary main screen -- checked on four of them -- and
        // would veto every round.
        // Asked as a question now, not for its coordinates: nothing here
        // taps the auto button any more.
        if (Dungeon.autoButton(img) == null) {
            once("busy", "  not the plain main screen, nothing done this round")
            dump(img, "unclear")
            heartbeat(blocked = "not the plain main screen")
            return going()
        }
        round.clear = true

        // Read and said, and nothing more done with it. It was once tied to
        // the Auto Spend box, for no better reason than that pressing the
        // button needed it, and a player who wanted the token alone got a
        // counter nobody had looked at on a screen where the number was
        // plainly drawn. The box is gone and the reading stayed.
        val value = Passive.holoCounter(img)
        round.holo = value
        noteCounter(value, img)

        // Not asked any more: the token is what this skill is, and "Collect
        // the bond token" was a box that could switch the feature off inside
        // a feature that was already switched on.
        val bubble = Passive.bondBubble(img, notTheMiddle(img))
        round.bubble = bubble
        if (bondRound(img, bubble, round)) {
            round.did = DID_BOND
            // `passive_all_digimon` is not read here. On the PC the tour is
            // started from inside this round (passive.py, `_tour`); under the
            // director it is [BondTourSkill], the next round-skill in the
            // list, and its trigger is the round BOND_WATCH after this one,
            // the one that says DID_BOND_GONE -- a token that stayed
            // collected. So the box is asked where the walking is done, and
            // this round does what it always did and hands back.
            return going()
        }

        heartbeat(value, round.bubble)
        return going()
    }

    /**
     * The whole: the same round on a frame of its own. There is no walk to
     * a screen of its own and no way home -- the passive helper's screen is
     * the main screen, and it is already standing on it.
     */
    override fun run(): Outcome {
        val img = try {
            cap.grab()
        } catch (e: CaptureError) {
            return Outcome.parked("no frame: ${e.message}")
        }
        return try {
            work(img)
        } finally {
            img.release()
        }
    }

    /**
     * A Partner window is up. Close it if this skill opened it.
     *
     * Whose it is decides everything here. This skill's own is the price of
     * tapping a bubble off a single frame -- it opens when the token was
     * collected a moment earlier -- and leaving it there stops the skill for
     * the rest of the day. The player's own is theirs, and a helper that
     * closes windows out from under somebody using the game is worse than
     * one that does nothing at all.
     */
    private fun partnerRound(img: Mat, round: Seen): Outcome {
        if (now() - openedPartner > PARTNER_OWN) {
            once("theirs", "  a window is open that this did not open, leaving it alone")
            heartbeat(blocked = "a window is open that this did not open")
            return going()
        }
        partnerTries += 1
        if (partnerTries <= PARTNER_TAPS) {
            say("  the Partner window is open, tapping above it")
            tap(img, PARTNER_CLOSE[0], PARTNER_CLOSE[1])
        } else {
            say("  the Partner window will not go, using the back key")
            cap.back()
        }
        round.did = "closed the partner window"
        return going()
    }

    // ------------------------------------------------------------------------
    // The counter
    // ------------------------------------------------------------------------
    /** Remember what the counter says, and say what changed. */
    private fun noteCounter(value: Int?, img: Mat?) {
        val t = now()
        if (value == null) {
            val since = blindSince
            if (since == null) {
                blindSince = t
            } else if (t - since >= BLIND_AFTER && img != null) {
                say(String.format(Locale.US,
                "  the counter has not been readable for %.0f s on a screen that looks right",
                t - since))
                dump(img, "counter")
                blindSince = t
            }
            return
        }
        blindSince = null
        if (lastValue == null) {
            lastValue = value
            lastChange = t
            say("  holograms ${num(value)}, first reading")
            return
        }
        if (value != lastValue) {
            val fell = lastValue!! - value
            val span = t - (lastChange ?: t)
            say("  holograms ${num(value)} " +
                String.format(Locale.US, "(%+d in %.0f s)", -fell, span))
            lastValue = value
            lastChange = t
        }
        // A counter that says the same thing again is not a line in the log.
        // "Show everything it sees" used to write one every round; the box is
        // gone with the rest of the page's switches, and what is left is the
        // events and a line a minute ([heartbeat]).
    }

    // ------------------------------------------------------------------------
    // The token
    // ------------------------------------------------------------------------
    /**
     * The log the bubble reader refuses a candidate through.
     *
     * Something white and turquoise off to the side of the field is the one
     * thing the reader can see and the round cannot, so it is also the only
     * place a wrong tap can be reported from -- and the frame it was refused
     * on is worth more than the sentence.
     */
    private fun notTheMiddle(img: Mat): (String) -> Unit = { text ->
        once("notmiddle", text)
        dump(img, "bond-not-the-middle")
    }

    /**
     * Tap the figure the bubble belongs to. True if tapped.
     *
     * No waiting for a second frame, which is the opposite of what this
     * program does everywhere else: the character can die while the bubble
     * is up, and the bubble goes with it. A second frame costs a round and
     * loses tokens. What makes it affordable is that a wrong tap is cheap --
     * it opens the Partner window, which the next round knows to be this
     * skill's own and closes again.
     *
     * The count of tries is not tied to where the bubble was, because it
     * drifts a little from frame to frame -- it counts rounds in which one
     * was up, and clears the moment none is.
     */
    private fun bondRound(img: Mat, bubble: Passive.Bubble?, round: Seen): Boolean {
        if (bubble == null) {
            // Gone is not taken: a Digimon that dies takes its bubble with it
            // and brings it back with the respawn (BOND_WATCH). The count of
            // tries clears now, as it always did, so that a bubble that
            // comes back is tapped again; what waits is the sentence, and
            // the tour and the card wait with it.
            bondTries = 0
            if (collectPending && now() - bondTappedAt >= BOND_WATCH) {
                collectPending = false
                round.did = DID_BOND_GONE
                say(String.format(Locale.US,
                    "    bubble gone for %.0f s, token collected", BOND_WATCH))
            }
            return false
        }
        if (bondTries >= BOND_TRIES) {
            once("stuck", "  the bubble is still there after $BOND_TRIES taps, " +
                "leaving it alone until it goes")
            return false
        }
        if (now() < nextBond) return false
        val target = figureFromBubble(bubble)
        if (target == null) {
            val aim = aimAtFigure(bubble)
            if (onChatRow(aim[0], aim[1])) {
                // The figure is standing behind the chat line. Nothing to be
                // done about it this round and nothing to be gained by aiming
                // somewhere else: the token waits, the figure moves, and the
                // chat window stays shut.
                once("chatrow", String.format(Locale.US,
                    "  bubble at %.3f/%.3f, but the figure is behind the chat line, " +
                        "not tapping -- that would open the chat", bubble.fx, bubble.fy))
            } else {
                once("offfield", String.format(Locale.US,
                    "  bubble at %.3f/%.3f, but the figure would be outside the middle of the " +
                        "field, not tapping", bubble.fx, bubble.fy))
            }
            return false
        }
        bondTries += 1
        nextBond = now() + BOND_RETRY
        say(String.format(Locale.US, "  bond bubble at %.3f/%.3f, tapping the figure at %.3f/%.3f",
                          bubble.fx, bubble.fy, target[0], target[1]))
        // The frame the tap was aimed with. The phone kept five frames on
        // 2026-09-22 and every one was a frame the reader had refused
        // something on; not one was a frame it tapped on, and the aim was
        // wrong on hundreds of those. Rate limited with the rest.
        dump(img, "bond-tap")
        tap(img, target[0], target[1])
        // From here this skill owns whatever window the tap opens, for as
        // long as PARTNER_OWN says and no longer.
        openedPartner = now()
        bondTappedAt = now()
        collectPending = true
        return true
    }

    /**
     * Fast for [BOND_WATCH] after a tap on the figure, so that a bubble the
     * Digimon's death took away is seen the moment it is back, and the next
     * tap is [BOND_RETRY] behind the last rather than a beat behind the
     * respawn. On the clear main screen only: a window the tap opened is
     * closed at the slow beat, because [partnerRound] counts rounds and its
     * third is the back key -- two taps half a second apart would reach it
     * before the first had closed anything. And the slow beat everywhere
     * else: a bubble that stands through its three taps is an aim problem
     * or an impostor, and either would keep a phone fast all afternoon.
     */
    override fun beat(): Double? =
        if (seen.clear && now() - bondTappedAt < BOND_WATCH) Director.BEAT_WATCH else null

    // ------------------------------------------------------------------------
    // The quiet rounds
    // ------------------------------------------------------------------------
    /**
     * One line a minute, whether or not anything is happening.
     *
     * [blocked] is for the rounds that end early. Those say their reason once
     * and then fall silent, which is right for a log that runs all day and
     * wrong for anyone trying to tell a waiting helper from a dead one -- so
     * the reason comes round again every minute.
     */
    private fun heartbeat(value: Int? = null, bubble: Passive.Bubble? = null,
                          blocked: String? = null) {
        val t = now()
        if (t - lastHeartbeat < HEARTBEAT) return
        lastHeartbeat = t
        if (blocked != null) {
            log("  still watching, nothing done: $blocked")
            return
        }
        log("  still watching: holograms ${if (value != null) num(value) else "unreadable"}, " +
            "${if (bubble != null) "a bubble is up" else "no bubble"}")
    }

    /** One line for the shell, not for the log. */
    fun status(): String {
        val v = lastValue ?: return "watching, no counter read yet"
        return "watching -- holograms ${num(v)}"
    }

    /**
     * A tap by fraction of the reference window, as `DungeonBot.tap` sends
     * one. Aimed with [img] -- the frame this round was given, and never a
     * fresher one: a second grab here would be a second picture inside one
     * round, which is the thing "one tap a round" exists to stop.
     */
    private fun tap(img: Mat, fx: Double, fy: Double) {
        val r = Dungeon.gameRect(img)
        cap.tap(Py.roundInt(r.x0 + fx * r.gw), Py.roundInt(r.y0 + fy * r.gh))
    }

    /**
     * What a round answers when it is neither stopped nor stuck.
     *
     * [Result] has four values and none of them is "still going": DONE is
     * the chain's "this step is finished, move on", which would make a
     * director that ever asked stop calling this skill. Until core grows a
     * value for a skill with no end, the round says RETIRED with the
     * sentence that explains it -- and nothing reads it today, because a
     * round-skill's outcome is discarded by `DirectorLoop.mainRound`.
     */
    private fun going(): Outcome = Outcome.retired(
        "the bond token round has no end; ask again next round")
}
