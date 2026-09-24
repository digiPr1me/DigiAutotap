package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * The director (PLAN_ANDROID_3_DIRECTOR.md): a screen-driven automaton that
 * looks at the screen instead of waiting for a Start button per skill, and
 * gives the screen to the skill that has something to do on it. It is the
 * passive helper's `tick` (passive.py:1199) extended to everything.
 *
 * Two halves, in the order director.py has them. The first is the
 * measurement, ported line for line and held to `oracle/director.json` on
 * every frame of the corpus (DirectorOracleTest): [classify], which names
 * exactly one screen or [UNKNOWN]; [motion] and [idle], the "who is at it"
 * rule of 3.3 with the numbers of 5.2. The second is the automaton of 3.4,
 * which is Kotlin only: [Director.tick], one look at the screen and at most
 * one tap of the director's own per round.
 *
 * Nothing from `android.*`: it is given a [Capture], the skills, a way to
 * ask for the settings, a log and a clock, and no more -- so that the flow
 * tests of the skill sessions can drive it with a fake capture the way
 * `test_*_flow.py` drive the Python skills (DirectorTest does that with
 * painted frames).
 */
object Director {

    // ------------------------------------------------------------------------
    // The screens
    // ------------------------------------------------------------------------
    // What classify answers with. Every name is a screen a skill works on, a
    // prompt the director has a rule for, or a window it has to see past;
    // a screen no skill serves is UNKNOWN, and UNKNOWN means stand still.
    const val MAIN = "main"                       // the plain main screen, nothing over it
    const val STAGE_FAILED = "stage_failed"       // the red banner over the main screen
    const val NO_TICKETS = "no_tickets"           // "Insufficient <mode> Summon Tickets"
    const val EXIT_GAME = "exit_game"             // "Exit the game?" -- grey Cancel, OK ends it
    const val PROMPT = "prompt"                   // a pink prompt: disband, return to title, raise
    const val CLAIM_REWARDS = "claim_rewards"     // the idle-rewards dialog with Claim on it
    const val PARTNER_WINDOW = "partner_window"   // a Digimon's own window, Partner or Buddy
    const val TITLE = "title"                     // the title screen, Touch To Start
    const val DUNGEON_LIST = "dungeon_list"
    const val SUMMON = "summon"                   // a Special Summon screen, its X live
    const val SUMMON_DIALOG = "summon_dialog"     // a Special Summon screen with a dialog over it
    const val EXPLORE_MENU = "explore_menu"
    const val BOARD = "board"                     // the Digital World Search board
    const val FIELD = "field"                     // the Meat Field, nothing over it
    const val FIELD_DIALOG = "field_dialog"       // the field with the seed menu or the water popup
    const val PARTNER_PAGE = "partner_page"       // the Partner grid
    const val EVENT_PAGE = "event_page"           // the Gekkomon Run event page: Play Game, Missions, its X
    const val DIALOG = "dialog"                   // some dialog with a blue button; the panel among them
    const val UNKNOWN = "unknown"

    val SCREENS = listOf(MAIN, STAGE_FAILED, NO_TICKETS, EXIT_GAME, PROMPT, CLAIM_REWARDS,
                         PARTNER_WINDOW, TITLE, DUNGEON_LIST, SUMMON, SUMMON_DIALOG,
                         EXPLORE_MENU, BOARD, FIELD, FIELD_DIALOG, PARTNER_PAGE, EVENT_PAGE,
                         DIALOG, UNKNOWN)

    // The field. meat_field_screen counts the plots it can see, 0 to 6. An
    // undimmed field reads 6 and a field under the seed menu or the water popup
    // reads 3 or 4 -- and so does the main screen over an orange or brown
    // stage, on 36 frames of the corpus, eight of them at 6. What the field
    // has and the main screen never does is the white X in its corner
    // (summon.exit_button, one piece of reused artwork): on every one of the
    // 23 undimmed fields in the corpus and on none of the 334 clear main
    // screens. A dialog over the field dims the X away, so the dimmed field is
    // known by the dialog itself: seed_menu and water_popup both fire on real
    // fields reading 3 and 4, and water_popup's twelve false answers elsewhere
    // all stand on frames reading 0.
    const val FIELD_CELLS = 6
    const val FIELD_DIALOG_CELLS = 3

    /**
     * `classify`'s dict: the screen and the reader that named it. [info] is
     * `recognise`'s answer on the same frame, which classify computed on
     * the way and the automaton needs for the prompts' buttons; it is not
     * part of the oracle's form.
     */
    class Answer(val screen: String, val by: String, val info: Dungeon.Recognition? = null) {
        fun toOracle(): Map<String, Any?> = mapOf("screen" to screen, "by" to by)
        override fun toString() = "$screen (by $by)"
    }

    private fun answer(screen: String, by: String, info: Dungeon.Recognition? = null) =
        Answer(screen, by, info)

    /**
     * Which screen is in front: one answer, first match wins. The order is
     * the measurement (director.py, and PLAN_ANDROID_3_DIRECTOR.md 5.1):
     *
     * 1. The Stage Failed banner. Any tap dismisses it, so a tap meant for
     *    anything else is eaten; asked before the auto button because the
     *    banner leaves the button in the blue range (test_wake_flow).
     * 2. A crisp auto button. Every dialog in this game dims it, so the
     *    plain main screen and nothing else -- over the corpus no real
     *    prompt, window, list, panel, menu, board, field or Partner page
     *    carries one (334 frames say main, and the eleven other claims that
     *    ever stood beside one were all readers wrong about a main screen;
     *    three readers were fixed for it, see the module docstring).
     * 3. The Insufficient Tickets dialog, before recognise: it wears the pink
     *    prompt's face to the pixel (Close at 0.372, Move at 0.61, the same
     *    row) and is told from it only by the dimmed yellow summon button
     *    behind it. recognise called all six the exit prompt.
     * 4. recognise's exit prompt: grey Cancel is "Exit the game?", the one
     *    whose OK ends the session; pink is one of three the director has
     *    no way to tell apart and leaves standing.
     * 5. The idle-rewards dialog, then a Digimon's window, then the title
     *    screen -- each before the generic DIALOG, which recognise answers
     *    on all three (the Claim button, the Move button and the Touch To
     *    Start bar are all wide blue buttons in its band).
     * 6. The skills' screens. The dungeon list by its cards. Special Summon
     *    by a live yellow button beside its X: the button alone fires on the
     *    Tamer's Skill Cards page (Level Up, 4 frames) and the Crest
     *    confirmation, the X alone on the field, PvP and the world map.
     *    The Explore menu, then the board by its violet X (22 frames, every
     *    one a board; vision.calibrate alone says board on three main
     *    screens). The field by six plots and the white X, the dimmed field
     *    by its own dialog. The Partner page by the grid disc and the
     *    sub-tab row: the disc alone fires on two summon reward screens,
     *    whose card grid even passes bond.cells.
     * 7. The Gekkomon Run event page, by the pink "Play Game" beside the
     *    same white X: added on 2026-09-22 with the runner, behind every
     *    screen above so that none of them could change its answer. The
     *    oracle diff of that day was the 34 runner frames and two frames
     *    of corpus/passive that had been called `unclear` since 2026-09-19
     *    -- both the event page, looked at, so the two are the reader
     *    naming what nothing had a name for.
     * 8. Whatever recognise still calls a dialog: the dungeon panel, the OK
     *    pop-ups, the Sell/Equip window, the device settings -- a dialog is
     *    open, and which one is the business of whoever opened it.
     *
     * The Reward sheet (recognise's REWARD, since 2026-09-23) is none of
     * these and stays UNKNOWN: a skill that raised it taps it away itself,
     * and one found standing is not the director's to close. It was
     * UNKNOWN before recognise named it, on all seven frames of it.
     */
    fun classify(img: Mat): Answer {
        if (Dungeon.stageFailed(img)) return answer(STAGE_FAILED, "stage_failed")
        if (Dungeon.autoButton(img) != null) return answer(MAIN, "auto_button")
        if (Summon.notEnoughTickets(img) != null) return answer(NO_TICKETS, "not_enough_tickets")
        val info = Dungeon.recognise(img)
        if (info.state == Dungeon.EXIT) {
            if (info.exitKind == "beenden") return answer(EXIT_GAME, "recognise", info)
            return answer(PROMPT, "recognise", info)
        }
        if (Dungeon.claimButton(img) != null) return answer(CLAIM_REWARDS, "claim_button", info)
        if (Passive.partnerMenu(img) != null) return answer(PARTNER_WINDOW, "partner_menu", info)
        if (Startup.titleBar(img) != null) return answer(TITLE, "title_bar", info)
        if (info.state == Dungeon.LIST) return answer(DUNGEON_LIST, "recognise", info)
        if (Summon.summonButton(img) != null && Summon.exitButton(img) != null) {
            return answer(SUMMON, "summon_button", info)
        }
        if (Summon.dimmedSummonButton(img) != null) return answer(SUMMON_DIALOG, "dimmed_summon_button", info)
        if (Explore.exploreMenu(img) != null) return answer(EXPLORE_MENU, "explore_menu", info)
        if (Explore.closeButton(img) != null) return answer(BOARD, "close_button", info)
        val cells = Farm.meatFieldScreen(img)
        if (cells >= FIELD_CELLS && Summon.exitButton(img) != null) {
            return answer(FIELD, "meat_field_screen", info)
        }
        if (cells >= FIELD_DIALOG_CELLS) {
            if (Farm.seedMenu(img).first != null) return answer(FIELD_DIALOG, "seed_menu", info)
            if (Farm.waterPopup(img) != null) return answer(FIELD_DIALOG, "water_popup", info)
        }
        if (Bond.gridButton(img) != null && Bond.partnerSubtab(img) != null) {
            return answer(PARTNER_PAGE, "grid_button", info)
        }
        if (Runner.eventPage(img) != null) return answer(EVENT_PAGE, "event_page", info)
        if (info.state == Dungeon.DIALOG || info.state == Dungeon.DIALOG_PARTY ||
            info.state == Dungeon.DIALOG_AD) {
            return answer(DIALOG, "recognise", info)
        }
        return answer(UNKNOWN, "none", info)
    }

    // ------------------------------------------------------------------------
    // Who is at it: the three seconds
    // ------------------------------------------------------------------------
    // The player's rule (PLAN_ANDROID_APP.md 3.2): three seconds in which
    // nothing moves, then the app is at it; any movement it did not cause
    // itself starts the clock again. It holds at the hand-over points, before
    // the director gives a screen to a skill; inside a skill's work the skill's
    // own checks hold, as in the chain today.
    const val IDLE_AFTER = 3.0

    // What "nothing moves" is. NOT dungeon.MOVING_SHARE (0.0002, "is a load
    // still going") and NOT the passive helper's 20 s ("has the counter
    // stopped", a constant of its own until the auto button went): those
    // answer other questions, and NOTES.md counts three bugs from one
    // threshold shared between two of them.
    //
    // Measured with _motion_probe.py over every pair of frames in the corpus
    // taken within 15 s of each other (file times), as the share of pixels
    // whose grey value moved by more than IDLE_DIFF between the two -- the
    // same number dungeon._same_screen takes. The pairs that measure what a
    // screen does *by itself* are the corpus/tall a/b pairs, taken 3.6 to
    // 3.8 s apart with nobody touching anything, at 1080 x 1920 and again at
    // 1080 x 2340 (the 9:16 measurement, _tall_measure.py), plus a prompt
    // held for 4.7 s and three main screens 0.7 s apart:
    //
    //   screen                     1920     2340        what moves
    //   dungeon list             0.0080   0.0072      the reset timer, badges
    //   dungeon panel            0.0296   0.0329      the timer, a shimmer
    //   dungeon panel, ad        0.0000   0.0001      nothing
    //   Partner window           0.0398   0.0363      the Digimon's idle pose
    //   summon, mode screen      0.0372   0.0305      the banner's sparkle
    //   summon, General tab      0.0132   0.0150      the same, less of it
    //   Explore menu             0.0147   0.0006      the "!" badges
    //   exit prompt, 4.7 s       0.0231 / 0.0000      the field behind, dimmed
    //   main screen              0.2277   0.2660      the battle
    //   main screen, bubble      --       0.2015      the battle
    //   main screen, 0.7 s       0.0000 x 3           nothing yet
    //   board, 3 to 6 s          0.0822 to 0.1691     the figure and the banner
    //
    // And what a *change of screen* measures, from the pairs that have a tap
    // between them: a menu to the board 0.5952, the seed menu to the planted
    // field 0.8101, the water popup closing 0.8204, summon to its General tab
    // 0.4052 and 0.4733, the Crest draw sequence 0.7050 to 0.8627, the summon
    // result to PvP 0.8233. The smallest of them is 0.4052.
    //
    // So the screens the director hands over -- the list, the panel, the
    // windows, Special Summon, the Explore menu, a prompt -- shimmer at 0.000
    // to 0.040 over 3.6 s and never more, while the least a tap ever moved a
    // screen is 0.405: a factor of ten either side of 0.10. On those screens
    // "nothing moved" is motion() under IDLE_SHARE, and three seconds of it
    // is the player's rule met.
    //
    // Two screens move by themselves faster than that and are outside the
    // rule on purpose. The main screen's battle measures 0.20 to 0.27 over
    // 3.6 s -- there the three seconds are over the moment they begin, which
    // is what the player asked for ("the main screen is where the app was
    // left"), and what the director does on it is the passive helper's round,
    // at most one tap on a screen read twice (passive.tick). The board's
    // figure and banner measure 0.08 to 0.17, and the board belongs to the
    // minigame, whose own quiet_frame decides when a move may go out.
    const val IDLE_DIFF = 30
    const val IDLE_SHARE = 0.10
    /** The screens the motion rule does not apply to, with the numbers above. */
    val MOVES_BY_ITSELF = setOf(MAIN, BOARD)

    /**
     * How much moved between two frames: the share of pixels whose grey
     * value changed by more than IDLE_DIFF. 0.0 when the two are the same
     * picture; null when they cannot be compared.
     */
    fun motion(before: Mat?, after: Mat?): Double? {
        if (before == null || after == null || before.rows() != after.rows() ||
            before.cols() != after.cols() || before.channels() != after.channels()) {
            return null
        }
        val a = Cv.gray(before)
        val b = Cv.gray(after)
        val diff = Mat()
        Core.absdiff(a, b, diff)
        // `diff > IDLE_DIFF`: strictly greater, as numpy compares.
        val moved = Mat()
        Core.compare(diff, Scalar(IDLE_DIFF.toDouble()), moved, Core.CMP_GT)
        val share = Core.countNonZero(moved).toDouble() / (diff.rows().toLong() * diff.cols())
        a.release(); b.release(); diff.release(); moved.release()
        return share
    }

    /**
     * Did nothing move between these two frames, by the director's own
     * threshold? A pair that cannot be compared is not idle. On a screen
     * that moves by itself (MOVES_BY_ITSELF) the question has no answer in
     * the picture, and the director's rule for that screen holds instead --
     * this says true there, so that the clock is not held up by a battle.
     */
    fun idle(before: Mat?, after: Mat?, screen: String? = null): Boolean {
        if (screen in MOVES_BY_ITSELF) return true
        val share = motion(before, after)
        return share != null && share < IDLE_SHARE
    }

    // ------------------------------------------------------------------------
    // The automaton (3.4)
    // ------------------------------------------------------------------------

    /**
     * The beat between two looks. Slow where nothing is expected -- the main
     * screen, `passive.TICK` (2 s) -- and one look a second everywhere else,
     * which is CoreService's own beat and stays well above the system's
     * floor for takeScreenshot (NOTES.md, "The phone app"). Fast only inside
     * a skill's work, where the skill itself grabs. On the phone this is the
     * thermal rule (PLAN_ANDROID_APP.md 3.4); in LDPlayer it costs nothing.
     */
    const val BEAT_MAIN = 2.0
    const val BEAT = 1.0

    /**
     * The beat a round-skill may ask for instead ([Skill.beat]), and the
     * fastest there is to ask for: `takeScreenshot` refuses a call under
     * 350 ms (NOTES.md, "takeScreenshot has a floor"), and a main-screen
     * round costs about that again in LDPlayer -- classify 283 ms median
     * over the 32 main-screen rounds of one day's log, the helper's three
     * readers after it -- so half a second is a round with no sleep in it,
     * and asking for less would change nothing. What it is for is the bond
     * token, PassiveSkill.BOND_WATCH: a Digimon that dies takes its bubble
     * with it and brings it back with the respawn, and at the slow beat the
     * second tap came eight seconds after the first.
     */
    const val BEAT_WATCH = 0.5

    /**
     * How long the screen a skill began on is given to come back after
     * `work` returned. "A skill returns when it is finished; the game does
     * not" (NOTES.md): the frame right after the hand-back is the game
     * still closing its own result screen. Long enough for that and shorter
     * than any skill's own result screens, which the skill waits out itself.
     */
    const val SETTLE = 10.0

    /**
     * How long an unknown screen may stand in the fully automatic mode
     * before the chain stops waiting for it and says so (DirectorLoop.full,
     * R4 of the plan of 2026-09-24): counted from the frame it was first
     * seen, so that the [SETTLE] after a step is inside it and not on top.
     *
     * What an unknown screen lasts when nothing is wrong, from what is
     * measured: "Now Loading" after a battle, three frames and 1.4 s
     * (DungeonSkill.UNKNOWN_HOLD); and the longest in the phone's log of
     * 2026-09-24, the game coming up from the launcher, unknown at 19:54:21
     * and the main screen by 19:54:29 -- 8 s at most. And every step's
     * `run` ends by confirming the auto button, so an unknown screen after
     * a step is already off the way things go. 30 s is almost four times the
     * longest, and a stalled chain still says why within half a minute.
     */
    const val UNKNOWN_END = 30.0

    /** The chain's second hand presses the globe as often as every skill's `goHome` does. */
    const val HOME_PRESSES_MAX = DungeonSkill.HOME_PRESSES_MAX

    /**
     * The tap that takes the Stage Failed banner away, at a spot that opens
     * nothing on the plain main screen: `summon.NEUTRAL_TAP_FY`, where
     * open_summons taps it away too. The banner eats any tap, so nothing
     * else this tap could reach is there while it stands.
     */
    const val NEUTRAL_TAP_FX = 0.5
    const val NEUTRAL_TAP_FY = 0.10

    /** What one round did, for the log, the shell and the flow tests. */
    class Tick(
        /** classify's screen, or null where no frame was read this round. */
        val screen: String?,
        /** The one sentence the shell shows. */
        val note: String,
        /** The action of the director's own, if any: "cancel", "ok", "tap", "work <key>", "run <key>". */
        val did: String? = null,
        /** Seconds until the next look. */
        val beat: Double = BEAT,
    ) {
        override fun toString() = "${screen ?: "-"}: $note" + (if (did != null) " [$did]" else "")
    }
}

/**
 * The automaton itself, PLAN_ANDROID_3_DIRECTOR.md 3.4:
 *
 *     tick():
 *         img = grab()
 *         if not in_front():        stand still, say so once
 *         if not on:                read only, log what is seen
 *         screen = classify(img)
 *         if screen is a prompt:    the prompt's own rule (EXIT: cancel; party: OK; ...)
 *         if not idle_for(IDLE_AFTER): return
 *         mode semi:   skill = who_works_on(screen); skill.work(img) if enabled
 *                      and -- sees_work(img) if it answers on a frame, else
 *                      not worked this visit and has budget
 *         mode full:   chain.current() -> that skill's run(); on done, chain.advance()
 *                      between steps: the main screen, with the passive helper's round
 *
 * One tap of its own per round, as passive.tick, with the same reason: the
 * second tap would be aimed with a picture taken before the first one
 * landed. The three-second gate stands in front of **every** tap of the
 * director's own, the prompts' included: the player and the app share one
 * screen, and a prompt the player raised a second ago is the player's
 * action in progress -- an "Exit the game?" they meant is theirs to answer
 * within the three seconds, and one left standing is cancelled.
 *
 * What it presses OK on: nothing it did not raise itself, and it raises no
 * prompt of its own -- it never sends the back key. A pink prompt is one of
 * three identical to the pixel (NOTES.md, "Prompts and dialogs"), and only
 * the caller knows which; the one caller here is a skill's `work`, which
 * names what it was doing in [Outcome.leaving], and that is the whole of the
 * evidence OK is ever pressed on. Any other pink prompt is left standing and
 * said so, which is what parked means.
 */
class DirectorLoop(
    private val cap: Capture,
    private val skills: List<Skill>,
    /**
     * The skills whose work is one round on the plain main screen -- the
     * passive helper and the quest loop -- called in this order every round
     * the main screen is nobody else's, each on a frame of its own, as the
     * PC's `_passive_loop` ticks them (app.py:3602). Each taps at most once
     * per round by its own rule; the director taps nothing there.
     */
    private val rounds: List<Skill>,
    /** The game's package, or null while none is known: nothing is read then. */
    private val game: () -> String?,
    /** SkillSettings.MODE_SEMI or MODE_FULL, read every round. */
    private val mode: () -> String,
    /** The fully automatic mode's order. */
    val chain: Chain,
    /** "Am I in?" -- the shell's included_<key> switch, read every round. */
    private val included: (Skill) -> Boolean = { true },
    /** The main switch: read only while it is off. */
    private val on: () -> Boolean = { MainSwitch.on },
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** Keep a frame worth looking at afterwards: the exit prompt, a park. The app writes files; core cannot. */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    /**
     * Who has the screen right now: a skill's name as its `work` or `run`
     * begins, null as it hands the screen back. A round is as long as the
     * skill inside it -- a dungeon run is a minute -- and nothing else
     * says, during that minute, that anybody is working; the overlay's
     * status line is what listens.
     */
    private val busy: (String?) -> Unit = {},
    /** Seconds, monotonic. Injected so that the flow tests can move it. */
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) {
    /** The last frame, owned here, for the motion measurement. */
    private var before: Mat? = null
    /** classify's answer on the last frame read. */
    var screen: String? = null
        private set
    /** When the current screen was last seen moving, or first seen. */
    private var quietSince = 0.0
    /** The next frame's motion is the director's own tap landing. */
    private var ownMotion = false
    /** The last sentence said, so that a round says a thing once (passive._once). */
    private var said: String? = null
    /**
     * Why the director stands still, or null. A change of screen clears it.
     * Written by the core's thread, read by the shell's from the main one
     * (`Shell.parked`), in the middle of a round as well.
     */
    @Volatile var parked: String? = null
        private set
    /** Seconds left on the three-second clock while the director holds for it, else null. */
    var takeOverIn: Double? = null
        private set
    /** Width and height of the last frame read, for whoever aims a debug gesture. */
    @Volatile var frameSize: Pair<Int, Int>? = null
        private set
    /** The debug corner's "save the next frame": the next frame read goes to [keep] under this tag. */
    @Volatile var keepNext: String? = null
    /** "<key>@<screen>": the skill that worked this visit of this screen. */
    private var handedOver: String? = null
    /** The screen `work` began on, expected back until [expectUntil]. */
    private var expect: String? = null
    private var expectUntil = 0.0
    /** A skill's own prompt may still be standing: what it said it was doing, until when. */
    private var leaving: String? = null
    private var leavingUntil = 0.0
    /** When the screen in front was first seen, for [Director.UNKNOWN_END]. */
    private var seenSince = 0.0
    /**
     * The fully automatic mode's step whose `run` handed the screen back
     * last, for as long as the main screen has not been seen since: a
     * screen standing then is that step's leftover and gets the chain's
     * second hand ([full]); with this null, whatever is open is the
     * player's.
     */
    private var leftBy: Skill? = null
    /** That step came back PARKED, and the next look decides what the park means ([full], R2). */
    private var stepParked: Outcome? = null
    /** The second hand's own count: whether the screen's skill was asked to leave, and globe presses. */
    private var askedToLeave = false
    private var homePresses = 0

    /** The shell's "Try again": look afresh at a screen the director had parked on. */
    fun retry() {
        parked = null
        said = null
        handedOver = null
        log("trying again on ${screen ?: "the screen"}")
    }

    /** Whatever a chain step left behind is forgotten: the screen is the player's from here on. */
    private fun forgetStep() {
        leftBy = null
        stepParked = null
        askedToLeave = false
        homePresses = 0
    }

    /** Release the frame held for the motion measurement. */
    fun close() {
        before?.release()
        before = null
    }

    private fun once(text: String) {
        if (said != text) {
            said = text
            log(text)
        }
    }

    private fun still(note: String, screen: String? = this.screen, beat: Double = Director.BEAT): Director.Tick {
        once(note)
        return Director.Tick(screen, note, beat = beat)
    }

    /** One look at the screen, and at most one tap of the director's own. */
    fun tick(): Director.Tick {
        takeOverIn = null
        val pkg = game() ?: return still("no game package found on this device", null)
        val front = cap.inFront()
        if (front != pkg) {
            // The player is elsewhere, or the game is gone: "has it crashed"
            // is a question for the system, not for the screen (NOTES.md).
            // Whatever was seen before is over, clock included.
            screen = null
            // The package in front goes to the log and not to the note: the
            // note is the sentence on the app's page and in the notification,
            // where the app in front is DigiAutotap itself and its package
            // name read as a link to GitHub (the player's call, 2026-09-24).
            val away = "the game is not in front"
            once(away + (front?.let { " ($it)" } ?: ""))
            return Director.Tick(null, away, beat = Director.BEAT)
        }
        val img = try {
            cap.grab()
        } catch (e: CaptureError) {
            return still("no frame: ${e.message}", null)
        }
        frameSize = img.cols() to img.rows()
        if (img.cols() > img.rows()) {
            // Not supported, and not read: every band here is a portrait band.
            img.release()
            return still("the screen is in landscape -- not supported, not reading", null)
        }
        val answer = Director.classify(img)
        keepNext?.let { tag ->
            keepNext = null
            keep(img, "${tag}_${answer.screen}")
        }
        val t = now()
        note(answer.screen, img, t)

        if (!on()) {
            // Read only: what is seen goes to the log, nothing is done about
            // it. And the player may do anything meanwhile, so what a chain
            // step left behind is not its leftover any more when the switch
            // comes back.
            forgetStep()
            return still("paused, sees ${answer.screen}", beat = Director.BEAT_MAIN)
        }
        if (parked != null) return Director.Tick(answer.screen, parked!!, beat = Director.BEAT_MAIN)

        // The prompts' own rules first, before anything the director may be
        // waiting for: a skill's own prompt standing after its work is
        // answered, not waited out.
        when (answer.screen) {
            Director.STAGE_FAILED -> return gated(answer, img, t) {
                // Any click dismisses it and a tap meant for anything else is
                // eaten by it, so the one tap this round is the tap that takes
                // it away, at the spot that opens nothing.
                tap(img, Director.NEUTRAL_TAP_FX, Director.NEUTRAL_TAP_FY)
                Director.Tick(answer.screen, "the Stage Failed banner is up, tapping it away", "tap")
            }
            Director.EXIT_GAME -> return gated(answer, img, t) {
                // "Exit the game?": the one prompt whose OK ends the session,
                // told by its grey Cancel. Cancel, always; Cancel sits mirrored
                // relative to OK (dungeon.dismiss_confirm). The frame is kept:
                // any surprise about where it came from is worth a look.
                val ok = answer.info!!.exitOk!!
                keep(img, "confirm_beenden")
                val cancelX = if (ok.fx > 0.5) 1.0 - ok.fx else Dungeon.POS_EXIT_CANCEL
                tap(img, cancelX, ok.fy)
                Director.Tick(answer.screen, "\"Exit the game?\" is up -- Cancel", "cancel")
            }
            Director.PROMPT -> {
                val intent = leaving
                if (intent != null && t < leavingUntil) {
                    return gated(answer, img, t) {
                        val ok = answer.info!!.exitOk!!
                        leaving = null
                        tap(img, ok.fx, ok.fy)
                        Director.Tick(answer.screen, "$intent via OK", "ok")
                    }
                }
                // One of three identical to the pixel, raised by nobody
                // here. Left standing, and said so.
                keep(img, "prompt_not_mine")
                return park(answer.screen, "A prompt is open that I did not raise. " +
                    "Answer it yourself; I press OK on nothing I did not open.")
            }
        }

        // "A skill returns when it is finished; the game does not": the
        // screen the work began on is given SETTLE seconds to come back.
        var justBack = false
        if (expect != null) {
            if (answer.screen == expect || t >= expectUntil) {
                if (answer.screen != expect) log("  the $expect did not come back after the work; going on with ${answer.screen}")
                expect = null
                justBack = true
            } else {
                return still("waiting for the $expect to come back after the work")
            }
        }
        if (mode() == SkillSettings.MODE_FULL) return full(answer, img, t, justBack)
        forgetStep()
        return semi(answer, img, t)
    }

    /** The motion measurement and the clock, on every frame read. */
    private fun note(seen: String, img: Mat, t: Double) {
        if (seen != screen) {
            // A different screen: a new clock, and whatever the director was
            // parked on is gone. A new visit too -- unless the screen is the
            // one a skill's work is being waited for on: the game closing the
            // skill's own result screen in between is the same visit.
            screen = seen
            quietSince = t
            seenSince = t
            if (expect == null) handedOver = null
            if (parked != null) log("  ${parked}: over, the screen changed")
            parked = null
            said = null
        } else if (!Director.idle(before, img, seen)) {
            if (ownMotion) {
                // The director's own tap landing is not the player.
            } else {
                quietSince = t
            }
        }
        ownMotion = false
        before?.release()
        before = img
    }

    /**
     * The three-second gate in front of every action of the director's own:
     * the player's rule, met on this screen, or the round says how long is
     * left and stands still.
     */
    private fun gated(answer: Director.Answer, img: Mat, t: Double,
                      act: () -> Director.Tick): Director.Tick {
        val quiet = if (answer.screen in Director.MOVES_BY_ITSELF) Director.IDLE_AFTER else t - quietSince
        val left = Director.IDLE_AFTER - quiet
        if (left > 0) {
            takeOverIn = left
            return still("${answer.screen}: taking over in %.0f s unless you touch the game"
                .format(kotlin.math.ceil(left)), answer.screen)
        }
        said = null
        return act()
    }

    private fun park(screen: String, why: String): Director.Tick {
        parked = why
        log("  parked on $screen: $why")
        return Director.Tick(screen, why, beat = Director.BEAT_MAIN)
    }

    /** The semi-automatic mode: act on the screen in front, and leave the player there. */
    private fun semi(answer: Director.Answer, img: Mat, t: Double): Director.Tick {
        val screen = answer.screen
        // The main screen is never a hand-over. There it is not one skill
        // per visit but every included round in turn (5.5), and the two
        // rules cannot both hold on one screen: a skill that is in both
        // lists -- the quest loop, a chain step of its own and a round --
        // would take the main screen once per visit and then be answered
        // "has worked this main; leaving it to you" for as long as the
        // player stayed on it, with the passive helper never ticking again.
        // With its switch off it is worse: `hasBudget` says no, the loop
        // over the candidates falls through, and the round-skills are
        // skipped without anything being said at all.
        //
        // This was hidden for as long as the quest loop was a `LogSkill`
        // with nothing to do, and `DirectorTest`'s rig has no skill in both
        // lists, so no case could see it either.
        if (screen == Director.MAIN) return mainRound(answer, img)
        val candidates = skills.filter { included(it) && it.worksOn(screen) }
        if (candidates.isEmpty()) {
            return when (screen) {
                Director.UNKNOWN -> still("no skill works on this screen", screen, Director.BEAT_MAIN)
                else -> park(screen, "Something is in the way that I did not put there: $screen.")
            }
        }
        for (skill in candidates) {
            // The one question with a picture in it, asked first because its
            // answer replaces the two below (Skill.seesWork). A skill that
            // can read "there is something to do" off the frame in front is
            // not held to one work per visit and not held to its clock: the
            // player is standing on that screen and changing it, and the
            // frame is evidence where both of those are predictions. Null is
            // every skill but the Meat Field, and there nothing changes.
            val sees = skill.seesWork(screen, img)
            if (sees == false) {
                once("  ${skill.name}: nothing to do on the $screen just now")
                continue
            }
            if (sees == null) {
                if (handedOver == "${skill.key}@$screen") {
                    // One work per visit: the list is worked once, and a second
                    // pass over the same list would find what the first left.
                    return still("${skill.name} has worked this $screen; leaving it to you", screen, Director.BEAT_MAIN)
                }
                if (!skill.hasBudget()) {
                    once("  ${skill.name}: nothing to do on the $screen, on the settings as they stand")
                    continue
                }
            }
            return gated(answer, img, t) {
                log("giving the $screen to ${skill.name}")
                val outcome = working(skill) { skill.work(img) }
                handedOver = "${skill.key}@$screen"
                ownMotion = true
                expect = screen
                expectUntil = now() + Director.SETTLE
                after(skill, outcome, screen)
            }
        }
        return Director.Tick(screen, "nothing to do on the $screen", beat = Director.BEAT_MAIN)
    }

    /**
     * The fully automatic mode: the chain from the main screen, as the PC's
     * chain runs it. Between two steps the main screen gets the passive
     * helper's round ([justBack]: the round after a step handed the screen
     * back), so that a token is not walked past between two skills.
     */
    private fun full(answer: Director.Answer, img: Mat, t: Double, justBack: Boolean): Director.Tick {
        val screen = answer.screen
        if (screen != Director.MAIN) {
            val step = leftBy
            if (step == null) {
                // The player's screen, before the first step or after a
                // pause: theirs, and a park, as it always was. An unknown
                // one included -- but with an end and a sentence, where it
                // used to stand still round after round with neither (R4).
                if (screen == Director.UNKNOWN) {
                    if (t - seenSince < Director.UNKNOWN_END) {
                        return still("no skill works on this screen", screen, Director.BEAT_MAIN)
                    }
                    return park(screen, "The fully automatic mode starts from the main screen, " +
                        "and I do not know the screen that is open.")
                }
                return park(screen, "The fully automatic mode starts from the main screen, and $screen is open.")
            }
            val stopped = stepParked
            if (stopped != null) {
                // R2's other half: a step that parked and did not come home.
                // Something is in the way, and the next step would begin
                // blind -- a park, as before.
                forgetStep()
                keep(img, "parked_not_home")
                return park(screen, "${step.name} parked: ${stopped.why}")
            }
            return secondHand(answer, img, t, step)
        }
        val back = leftBy
        if (back != null) {
            val stopped = stepParked
            if (stopped != null) {
                // R2: a step that could not get in but came home does not
                // hold the chain. Retired for this chain run -- it would
                // meet the same wall again next round -- and the next step
                // starts from this main screen.
                log("  chain: retiring ${back.key} for this run -- ${stopped.why}")
                chain.retire(back.key, stopped.why)
                chain.advance()
            } else if (askedToLeave || homePresses > 0) {
                log("  chain: back on the main screen after ${back.name}; going on")
            }
            forgetStep()
        }
        val step = chain.current() ?: return mainRound(answer, img, "the chain is finished (${chain.summary()})")
        if (justBack) return mainRound(answer, img)
        val skill = skills.firstOrNull { it.key == step.key }
        val skip = when {
            skill == null -> "no such skill on the phone"
            chain.retiredReason(step.key) != null -> chain.retiredReason(step.key)
            !included(skill) -> "not included"
            !skill.hasBudget() -> "nothing to do, on the settings as they stand"
            else -> null
        }
        if (skip != null) {
            chain.recordSkip(step.key, skip)
            chain.advance()
            return Director.Tick(screen, "chain: skipped ${step.key} -- $skip", beat = Director.BEAT_MAIN)
        }
        val s = skill!!
        return gated(answer, img, t) {
            log("chain: starting ${s.name}")
            chain.recordStart(step.key)
            val outcome = working(s) { s.run() }
            ownMotion = true
            expect = Director.MAIN
            expectUntil = now() + Director.SETTLE
            forgetStep()
            // Whatever stands after SETTLE is this step's to be looked after,
            // unless the switch ended it: then the screen is the player's.
            if (outcome.result != Result.STOPPED) leftBy = s
            when (outcome.result) {
                Result.DONE -> chain.advance()
                Result.RETIRED -> {
                    // Said in the log as a skip is: live on LDPlayer
                    // 2026-09-23 the Special Summon step came and went in
                    // three seconds with nothing written between "starting"
                    // and the next step, and its sentence was only ever on
                    // the overlay for the length of one round.
                    log("  chain: retiring ${step.key} -- ${outcome.why}")
                    chain.retire(step.key, outcome.why)
                    chain.advance()
                }
                Result.STOPPED -> {}
                Result.PARKED -> {
                    // Not a park yet (R2). A step that could not get in and
                    // came home anyway -- the runner's own `run` goes home in
                    // its finally -- used to park the whole chain on a main
                    // screen from which the next step could have started.
                    // The next look decides: the main screen retires this
                    // step for the run and the chain goes on; anything else
                    // is a park, as it was.
                    stepParked = outcome
                    leaving = outcome.leaving
                    leavingUntil = if (outcome.leaving != null) now() + Director.SETTLE else 0.0
                    log("  chain: ${s.name} could not go on -- ${outcome.why}")
                    return@gated Director.Tick(Director.MAIN, "${s.name} could not go on: ${outcome.why}",
                                               "run ${s.key}")
                }
            }
            after(s, outcome, Director.MAIN, "run")
        }
    }

    /**
     * The chain's second hand (R3 and R4 of the plan of 2026-09-24): a
     * screen that is still standing when [Director.SETTLE] has run out after
     * a step, where the next step needs the main screen. Before this, the
     * chain parked there, and nobody asked whether the way home was one tap
     * away -- a step that did not come home stopped every step after it.
     *
     * In this order, one action a round, each gated like every action of
     * the director's own:
     *
     *  1. A skill's screen: that skill is asked for its own way home
     *     ([Skill.leave]) -- once, and the main screen is waited for again.
     *  2. A frame on which the globe is read: the globe, at most
     *     HOME_PRESSES_MAX times, as every skill's `goHome` presses it --
     *     never a position, and only while it is read.
     *  3. Neither: a park, with the frame kept.
     *
     * An unknown screen gets no skill (it has none) and no hand at all
     * until it has stood [Director.UNKNOWN_END]: a loading screen is
     * unknown, and so is the game closing a result it opened. A dialog no
     * skill opened is not answered here either -- it dims the nav bar, the
     * globe is not read, and it is a park, because the director presses OK
     * on nothing it did not open.
     */
    private fun secondHand(answer: Director.Answer, img: Mat, t: Double, step: Skill): Director.Tick {
        val screen = answer.screen
        if (screen == Director.UNKNOWN && t - seenSince < Director.UNKNOWN_END) {
            return still("waiting for the main screen after ${step.name}", screen)
        }
        val owner = if (screen == Director.UNKNOWN) null else skills.firstOrNull { it.worksOn(screen) }
        if (owner != null && !askedToLeave) {
            return gated(answer, img, t) {
                askedToLeave = true
                log("  chain: $screen left open after ${step.name}; asking ${owner.name} to leave")
                val home = working(owner) { owner.leave() }
                ownMotion = true
                expect = Director.MAIN
                expectUntil = now() + Director.SETTLE
                Director.Tick(screen, if (home) "${owner.name} went home" else "${owner.name} could not go home",
                              "leave ${owner.key}")
            }
        }
        val globe = Dungeon.homeButton(img)
        if (globe != null && homePresses < Director.HOME_PRESSES_MAX) {
            return gated(answer, img, t) {
                if (homePresses == 0) {
                    val left = if (screen == Director.UNKNOWN) "an unknown screen for %.0f s".format(t - seenSince)
                               else "$screen left open"
                    log("  chain: $left after ${step.name}; pressing the home button")
                }
                homePresses += 1
                tap(img, globe.fx, globe.fy)
                Director.Tick(screen, "pressing the home button after ${step.name}", "home")
            }
        }
        forgetStep()
        keep(img, "no_way_home")
        val what = if (screen == Director.UNKNOWN) "A screen I do not know" else "The $screen"
        return park(screen, "$what is open after ${step.name}, and I found no way home from it.")
    }

    /**
     * The main screen's round: every included round-skill in turn, the
     * first on the frame just classified and each one after it on a fresh
     * frame, so that none of them aims with a picture taken before the one
     * before it tapped.
     */
    private fun mainRound(answer: Director.Answer, img: Mat, why: String = ""): Director.Tick {
        // Both questions, as `semi` asks both of a candidate: `included` is
        // the row's switch in the Skills list, and `hasBudget` is whatever
        // else has to be true -- the quest loop's day lock, the bond tour's
        // `passive_all_digimon` and supporter code, and for the passive helper nothing at
        // all, because the Bond token row's switch is its whole answer. The
        // quest loop used to ask `quest_on` here as well, a second switch on
        // a page behind the first: a supporter could switch the row on, watch
        // the dot go green and get nothing, because the box underneath was
        // unticked. That box is gone (SkillSettings) and the row's switch is
        // the only one there is. BondTourSkill.work asks `hasBudget` in its
        // own first line, which is the same answer arrived at from the other
        // side.
        val active = rounds.filter { included(it) && it.hasBudget() }
        if (active.isEmpty()) {
            return still(if (why.isEmpty()) "the main screen; nothing to do here" else why,
                         answer.screen, Director.BEAT_MAIN)
        }
        if (why.isNotEmpty()) once(why)
        for ((i, s) in active.withIndex()) {
            if (i == 0) {
                working(s) { s.work(img) }
                continue
            }
            val fresh = try {
                cap.grab()
            } catch (e: CaptureError) {
                once("  no frame for ${s.name}'s round: ${e.message}")
                break
            }
            try {
                working(s) { s.work(fresh) }
            } finally {
                fresh.release()
            }
        }
        ownMotion = true
        // The slow beat, unless a round has just seen something worth a
        // faster look -- the helper for BOND_WATCH after a tap (Skill.beat).
        val beat = active.mapNotNull { it.beat() }.minOrNull() ?: Director.BEAT_MAIN
        return Director.Tick(answer.screen, "the main screen; " + active.joinToString(", ") { it.name },
                             "round", beat)
    }

    /**
     * A skill's turn with the screen, said to [busy] on the way in and on the
     * way out -- also when it throws.
     *
     * **This is the task, not whatever is on the screen at the moment.** A
     * skill may play another skill inside its own turn: the quest loop's
     * dungeon step builds a `DungeonSkill` of its own through
     * `QuestSkill.dungeonFor` and calls its `run`, and for the minute that
     * takes the game is standing on the dungeon list. That inner skill is
     * the quest loop's and never reaches the director, so nothing here is
     * said a second time and the overlay's plate goes on naming the quest
     * loop, which is what the player asked it to do. The only name the plate
     * ever carries is the one the director handed the screen to. Pinned by
     * `DirectorTest`, "the plate is named for the task, not for the skill the
     * task plays inside it".
     */
    private inline fun <T> working(skill: Skill, f: () -> T): T {
        busy(skill.name)
        try {
            return f()
        } finally {
            busy(null)
        }
    }

    /** What a skill's outcome means for the rounds that follow. */
    private fun after(skill: Skill, outcome: Outcome, screen: String, what: String = "work"): Director.Tick {
        leaving = outcome.leaving
        leavingUntil = if (outcome.leaving != null) now() + Director.SETTLE else 0.0
        val did = "$what ${skill.key}"
        return when (outcome.result) {
            Result.DONE -> Director.Tick(screen, "${skill.name}: done", did)
            Result.RETIRED -> Director.Tick(screen, "${skill.name}: nothing left until the reset -- ${outcome.why}", did)
            Result.STOPPED -> Director.Tick(screen, "${skill.name}: stopped by the main switch", did)
            Result.PARKED -> park(screen, "${skill.name} parked: ${outcome.why}").let { Director.Tick(screen, it.note, did) }
        }
    }

    /** A tap by fraction of the reference window, as `DungeonBot.tap` sends one. */
    private fun tap(img: Mat, fx: Double, fy: Double) {
        val r = Dungeon.gameRect(img)
        ownMotion = true
        cap.tap(Py.roundInt(r.x0 + fx * r.gw), Py.roundInt(r.y0 + fy * r.gh))
    }
}
