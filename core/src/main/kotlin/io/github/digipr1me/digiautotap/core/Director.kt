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
    const val DOWNLOAD = "download"               // "Resource download required" -- the same face, Cancel ends it
    const val PROMPT = "prompt"                   // a pink prompt: disband, return to title, raise
    const val NEWS = "news"                       // one of the game's news after its reset: OK under "Don't show again today"
    const val SALE = "sale"                       // the game's "Time Sale!" window: no X, one purchase button
    const val HELP = "help"                       // the game's Help tutorial over a page: Back, Next, a reward
    const val GEAR = "gear"                       // the hologram device's gear window: Sell beside Equip, no X
    const val CLAIM_REWARDS = "claim_rewards"     // the Idle Rewards window, its Claim lit or dimmed
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
    const val PRESET = "preset"                   // one of the six places with a preset bar, its list closed
    const val LOST_SECTOR = "lost_sector"         // the Crests page, its Enter pill to the Lost Sector Tower
    const val MISSIONS = "missions"               // the Missions window of the main screen, Collection or Daily lit
    const val EX_MISSIONS = "ex_missions"         // the same window with its EX Missions tab lit
    const val SKEWER_MENU = "skewer_menu"         // Chef's Special's page: Ranking, Missions, Play Game, its X
    const val SKEWER_STAGE = "skewer_stage"       // the stage popup over it, Start
    const val SKEWER_PLAY = "skewer_play"         // a round of Chef's Special: the grill, no dialog over it
    const val SKEWER_PAUSE = "skewer_pause"       // the round's pause menu, Quit and Continue
    const val SKEWER_OVER = "skewer_over"         // "Success!" or "Failed..." over the grill, Close
    const val DIALOG = "dialog"                   // some dialog with a blue button; the panel among them
    const val UNKNOWN = "unknown"
    // Not a picture and never classify's answer: an activity of the game's
    // own package that is not the game, an ad by its class (Ads). The loop
    // answers it before a frame is read.
    const val AD = "ad"

    val SCREENS = listOf(MAIN, STAGE_FAILED, NO_TICKETS, EXIT_GAME, DOWNLOAD, PROMPT, NEWS, SALE, HELP, GEAR, CLAIM_REWARDS,
                         PARTNER_WINDOW, TITLE, DUNGEON_LIST, SUMMON, SUMMON_DIALOG,
                         EXPLORE_MENU, BOARD, FIELD, FIELD_DIALOG, PARTNER_PAGE, EVENT_PAGE,
                         PRESET, LOST_SECTOR, MISSIONS, EX_MISSIONS, SKEWER_MENU, SKEWER_STAGE,
                         SKEWER_PLAY, SKEWER_PAUSE, SKEWER_OVER, DIALOG, UNKNOWN, AD)

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
    // The watering-can dialog stands taller than the other two and leaves
    // 2 plots in sight at 1920 and 2340 (4 at 2520), on every can dialog of
    // 2026-09-28 and on the ad offer it becomes at 0 cans
    // (corpus/farm/boost_*, ad_dialog_cans_*). It is known by its own shape
    // (Farm.boostPopup: four yellow keys in a row, two slots, a green
    // button), so 2 is asked of the field under it and not 3.
    const val FIELD_BOOST_CELLS = 2

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
     * 0. The game's "Time Sale!" window (Startup.saleWindow, its two yellow
     *    rims), added on 2026-10-01 (PLAN_RELEASE_1_3.md B72): it came up over
     *    the Stage Failed banner and its Growth Guide, and a window with a
     *    purchase button is asked before anything under it can be acted on.
     *    Over the corpus it names no frame (the three of it came that day),
     *    so nothing moves.
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
     * 3b. A round of Chef's Special, by its grill (Skewer.grill: red between
     *    twelve dark cells, which nothing else draws), before recognise:
     *    recognise calls the round's pause menu the pink prompt -- it is,
     *    to the pixel, the Gekkomon Run pause's twin -- and "Success!" or
     *    "Failed..." a dialog. Over the grill Runner's two readers name
     *    them, and the grill alone is a round in play. Added on 2026-09-30
     *    (PLAN_SKEWER.md SK1).
     * 4. recognise's exit prompt: grey Cancel is "Exit the game?", the one
     *    whose OK ends the session; pink is one of three the director has
     *    no way to tell apart and leaves standing. Grey with a message two
     *    lines tall is the game's resource download (Dungeon.KIND_DOWNLOAD,
     *    2026-09-30), whose Cancel ends the game as well: its own screen,
     *    DOWNLOAD. Over the corpus it names the three frames that had been
     *    called exit_game since they were kept -- startup/no_title_00 and
     *    quest/no-x-to-get-out-with_113000 and _183113, all three that
     *    dialog, looked at -- and nothing else.
     * 4b. One of the game's news after its reset (Startup.announcement: the
     *    OK with the empty box of "Don't show again today" left above it),
     *    added on 2026-10-01 (PLAN_RELEASE_1_3.md B50): a window the game puts
     *    over the main screen or the dungeon list, so it is asked before every
     *    reader of a screen under it and after the prompts, which stand over
     *    everything. recognise calls it a battle (its OK is where Give Up is),
     *    so it read `unknown` -- and with the dot and its plate at fy 0.155,
     *    the lowest place of the strip, the Overdrive news read `field_dialog`
     *    by the water popup (B51), which this order ends. Over the corpus it
     *    names the six frames the reader answers on, all of them news and all
     *    `unknown` before, and nothing else. Its price, over the 1850 frames
     *    of the corpus (`gradlew :core:titleProbe --args="news corpus"`,
     *    staging/b50/titleProbe_news_corpus.txt): 2.9 ms median on the 1400
     *    frames classify asks it on, 7.4 at p95, 12.7 at the most (a 2136 x
     *    3200 board), against classify's own 101 ms median there.
     * 4c. The game's Help tutorial (Startup.helpWindow: a Next beside a Back
     *    of its size, the page counter between them above), added on
     *    2026-10-01 (PLAN_RELEASE_1_3.md B73): it stands over a page -- the
     *    Digivice's, Tactical Memory's -- so it is asked before the readers of
     *    the pages under it; recognise calls it a dialog by its Next. Over the
     *    corpus it names no frame (the two of it came that day) and moves
     *    nothing.
     * 4d. The hologram device's gear window (Startup.gearWindow: a pink Sell
     *    beside a blue Equip of its size, low and wide), added on 2026-10-04
     *    (PLAN_ABSCHLUSS_1_3.md K2): Auto Spend raises it over the main
     *    screen by itself and it stands until somebody chooses; recognise
     *    calls it a dialog by its Equip, which 9. answered, and popupOk an OK.
     *    Over the corpus it names the twelve frames of it the passive helper,
     *    the quest loop and the bond tour kept, all `dialog` before, and
     *    nothing else.
     *    Then a preset page (Preset.place): added on 2026-09-25, and before
     *    everything after it on purpose. The overlay's plate said
     *    "dungeon_list" on the Skill Cards page live that day -- a screen the
     *    Dungeons task would be handed -- although its screencap, unmasked,
     *    reads unknown; and Food Effects with its list open reads as the
     *    title screen. The bar with its arrow at its place, with or without
     *    its pencil, stands on no other frame of the corpus: swept on the
     *    same day, the one look-alike is the Friends list's sort, which has
     *    no pencil and stands 0.008 from the Digivice's place.
     * 5. The Idle Rewards window (lit Claim, then dimmed), then a Digimon's window, then the title
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
     * 7b. The Crests page, by its lit Enter pill to the Lost Sector Tower
     *    beside the white X (LostSector.page): added on 2026-09-29 behind
     *    every skill's screen and before the dialogs, so that nothing named
     *    before can change its answer. Measured on the seven format rows of
     *    that night, where every screen above said unknown on it; the pill
     *    is dimmed under the tower's panel, which stays a dialog (9.)
     *    like the dungeon panel.
     * 7c. Chef's Special's page (Skewer.menu: the orange Play Game pennant
     *    beside the white X) and its stage popup (Skewer.stage: Start under
     *    the "Stage" tab), added on 2026-09-30 behind every skill's screen:
     *    event_page does not answer on the page (it has no pink "Play"),
     *    and recognise calls the popup a dialog, which 9. would answer.
     * 8. The Missions window of the main screen (Missions.window) and its
     *    EX Missions tab (Missions.exWindow): added on 2026-09-29 for the
     *    EX Missions task, behind every screen above so that none of them
     *    could change its answer, and before DIALOG. Every frame of the
     *    window in the measurement read `unknown` before -- recognise finds
     *    no dialog on it -- and what names it is its own tab row, one pale
     *    and two blue tabs of a tab's size side by side, which no screen
     *    above draws. Over the 1583 frames of the oracle it answers on three
     *    and on nothing else, and all three are this window, kept by the
     *    passive helper on the player's account on 2026-09-21 as `unclear`:
     *    Collection with its Support Digimon sub-tab (passive/unclear_110156,
     *    which recognise had called a dialog) and the EX tab with a Claim
     *    under the player's finger (unclear_135039, _135738, unknown). The
     *    six Missions frames of the Gekkomon Run event (runner/missions_*)
     *    are another window and stay what they were (notes/missions.md,
     *    "The Missions window has no X").
     * 9. Whatever recognise still calls a dialog: the dungeon panel, the OK
     *    pop-ups, the device settings -- a dialog is
     *    open, and which one is the business of whoever opened it.
     *
     * The Reward sheet (recognise's REWARD, since 2026-09-23) is none of
     * these and stays UNKNOWN: a skill that raised it taps it away itself,
     * and one found standing is not the director's to close. It was
     * UNKNOWN before recognise named it, on all seven frames of it.
     */
    fun classify(img: Mat): Answer {
        if (Startup.saleWindow(img) != null) return answer(SALE, "sale_window")
        if (Dungeon.stageFailed(img)) return answer(STAGE_FAILED, "stage_failed")
        if (Dungeon.autoButton(img) != null) return answer(MAIN, "auto_button")
        if (Summon.notEnoughTickets(img) != null) return answer(NO_TICKETS, "not_enough_tickets")
        // Under a dialog the grill is asked for Skewer.DIALOG_POINTS only: over
        // the ceiling the dialog's edge covers its first row (Skewer.over).
        val grill = Skewer.grill(img)
        if (grill.red >= Skewer.DIALOG_POINTS) {
            if (Runner.pauseDialog(img) != null) return answer(SKEWER_PAUSE, "skewer_paused")
            if (Runner.resultDialog(img) != null) return answer(SKEWER_OVER, "skewer_over")
        }
        if (grill.ok) return answer(SKEWER_PLAY, "skewer_grill")
        val info = Dungeon.recognise(img)
        if (info.state == Dungeon.EXIT) {
            if (info.exitKind == "beenden") return answer(EXIT_GAME, "recognise", info)
            if (info.exitKind == Dungeon.KIND_DOWNLOAD) return answer(DOWNLOAD, "recognise", info)
            return answer(PROMPT, "recognise", info)
        }
        if (Startup.announcement(img) != null) return answer(NEWS, "announcement", info)
        if (Startup.helpWindow(img) != null) return answer(HELP, "help_window", info)
        if (Startup.gearWindow(img) != null) return answer(GEAR, "gear_window", info)
        if (Preset.place(img) != null) return answer(PRESET, "preset_place", info)
        if (Dungeon.claimButton(img) != null) return answer(CLAIM_REWARDS, "claim_button", info)
        // The same window once its Claim is taken: the button stays, dimmed
        // under BLUE, and the window read `unknown` (Quest.IDLE_CLAIM_ANY,
        // 2026-09-28). Asked right after claimButton, so no frame that it
        // named moves; over the corpus it names two more, both that window.
        if (Quest.idleWindow(img) != null) return answer(CLAIM_REWARDS, "idle_window", info)
        if (Passive.partnerMenu(img) != null) return answer(PARTNER_WINDOW, "partner_menu", info)
        if (Startup.titleBar(img) != null) return answer(TITLE, "title_bar", info)
        // The title by day, whose bar does not stand out from the picture:
        // by its Menu button (PLAN_RELEASE_1_3.md B43). Asked right after the
        // bar, so no frame the bar named moves; over the corpus it names two
        // more, both a day title the passive helper kept as `unclear`.
        if (Startup.menuButton(img) != null) return answer(TITLE, "menu_button", info)
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
        if (cells >= FIELD_BOOST_CELLS) {
            if (Farm.adDialog(img) != null) return answer(FIELD_DIALOG, "ad_dialog", info)
            if (Farm.boostPopup(img) != null) return answer(FIELD_DIALOG, "boost_popup", info)
        }
        if (Bond.gridButton(img) != null && Bond.partnerSubtab(img) != null) {
            return answer(PARTNER_PAGE, "grid_button", info)
        }
        if (Runner.eventPage(img) != null) return answer(EVENT_PAGE, "event_page", info)
        if (LostSector.page(img) != null) return answer(LOST_SECTOR, "lost_sector_page", info)
        if (Skewer.menu(img) != null) return answer(SKEWER_MENU, "skewer_menu", info)
        if (Skewer.stage(img) != null) return answer(SKEWER_STAGE, "skewer_stage", info)
        val missions = Missions.window(img)
        if (missions != null) {
            // The EX tab lit and its header block drawn; a frame with the tab
            // lit and the list not yet swapped is still the window.
            if (missions.tab == Missions.EX && Missions.exWindow(img) != null) {
                return answer(EX_MISSIONS, "ex_window", info)
            }
            return answer(MISSIONS, "missions_window", info)
        }
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
     * Inside every window of the reset's stack, as fx0, fx1, fy0, fy1 of the
     * middle's rectangle, where those windows stand (notes/formats.md, rule
     * 4): read off the frames of 2026-10-01 at 1080 x 1920, the news card
     * spans fx 0.132 to 0.820 and fy 0.137 to 0.832, Notices 0.153 to 0.799
     * and 0.223 to 0.764, the login bonus's calendar 0.127 to 0.825 and 0.291
     * to 0.771, Idle Rewards 0.180 to 0.772 and 0.223 to 0.791 -- all of them
     * hold fx 0.180 to 0.772, fy 0.291 to 0.764, and this is that, a little
     * in from each side. What stands there is the window and nothing behind
     * it: the main screen's battle moves 0.20 to 0.27 by itself ([IDLE_SHARE]),
     * and around a window it is still in sight.
     */
    val RESET_WINDOW_CROP = doubleArrayOf(0.22, 0.73, 0.32, 0.74)

    /**
     * [motion] inside [RESET_WINDOW_CROP]: whether the window in front is
     * another one, with whatever the game does around it left out. Null where
     * the two cannot be compared.
     */
    fun windowMotion(before: Mat?, after: Mat?): Double? {
        if (before == null || after == null || before.rows() != after.rows() || before.cols() != after.cols()) {
            return null
        }
        val r = Dungeon.gameRect(after, Dungeon.Anchor.MIDDLE)
        val c = RESET_WINDOW_CROP
        val y0 = Py.int(r.y0 + c[2] * r.gh)
        val y1 = Py.int(r.y0 + c[3] * r.gh)
        val x0 = Py.int(r.x0 + c[0] * r.gw)
        val x1 = Py.int(r.x0 + c[1] * r.gw)
        val a = Py.crop(before, y0, y1, x0, x1) ?: return null
        val b = Py.crop(after, y0, y1, x0, x1) ?: run { a.release(); return null }
        try {
            return motion(a, b)
        } finally {
            a.release(); b.release()
        }
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
     * floor for takeScreenshot (notes/director.md, "takeScreenshot has a floor"). Fast only inside
     * a skill's work, where the skill itself grabs. On the phone this is the
     * thermal rule (PLAN_ANDROID_APP.md 3.4); in LDPlayer it costs nothing.
     */
    const val BEAT_MAIN = 2.0
    const val BEAT = 1.0

    /**
     * The beat a round-skill may ask for instead ([Skill.beat]), and the
     * fastest there is to ask for: `takeScreenshot` refuses a call under
     * 350 ms (notes/director.md, "takeScreenshot has a floor"), and a main-screen
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
     * How long after the switch comes back a screen the first look does not
     * know to go on from is looked at again before anything is parked on it
     * (the player's rule of 2026-09-30, PLAN_RELEASE_1_3.md B4: "Wenn er
     * das Fenster nicht sofort erkennt, sollte er einmal das Fenster
     * scannen ... Es sollte nicht sofort das Parked-Logo kommen").
     *
     * What stands between two screens a task knows, measured: "Now Loading"
     * and the black frame after a lost dungeon run, three frames and 1.4 s
     * at LDPlayer's 2.2 frames a second (DungeonSkill.UNKNOWN_HOLD, three
     * recorded runs of 2026-09-23); the daily dungeon's prefab over the main
     * screen for about a second before its panel (DL1a, 2026-09-29); a
     * dialog of the game opening or closing, shorter than either. So the
     * same 3 s the skills give an unknown screen before they tap it, twice
     * the longest transition: a pause that fell into one has ended it by
     * then, and a screen still unknown after it is not a transition.
     */
    const val SECOND_LOOK = 3.0

    /**
     * A park of the fully automatic mode that is not wanted resolves itself
     * (PLAN_BEFUNDE_1_3.md N3 c, the player, 2026-10-02: "Park neuer Versuch
     * nach 5 sek, 3 mal"): after it has stood [PARK_RETRY] seconds the
     * director takes the way home it already has -- the screen's own task's
     * way home, then the globe, never the back key, never a button it has
     * not read -- and the chain's step is tried again from the main screen.
     * [PARK_RETRIES] tries at the same step; a park after the last of them
     * stands [PARK_LONG], the step is left for this chain run, and the way
     * home is tried once more for the next step. 600 s is the plan's
     * proposal, which the player's answer left standing. The parks that are
     * wanted stay what they were: the day's (a step retired until the
     * reset), a prompt nobody here raised, the download dialog after its two
     * OKs, the reset's windows after their brake, the sale window, the Help
     * tutorial, an ad, the title, and the player's screen -- every park of
     * the semi-automatic mode, and in the fully automatic one a screen open
     * before the first step or after a pause no step goes on from (F27).
     * What resolves is what a step left: its park away from home, and no
     * way home found after it. A step's lost frame is none of the tries
     * (PLAN_ABSCHLUSS_1_3.md, question 17, the player on 2026-10-03: "nie
     * zählen"): it goes home after [PARK_RETRY] and on, as often as it comes.
     */
    const val PARK_RETRY = 5.0
    const val PARK_RETRIES = 3
    const val PARK_LONG = 600.0

    /**
     * How many times the game's download dialog ([DOWNLOAD]) is answered OK
     * before a dialog that keeps coming back is left to the player (the
     * conductor's rule of 2026-09-30: twice, never a third time). A second
     * OK is for a first one the game swallowed or a download that asked
     * again; what asks a third time is something the director does not
     * understand, and a park says so. Counted until the game is past its
     * loading -- the main screen or the title -- or "Try again".
     */
    const val DOWNLOAD_OKS = 2

    /** What the plate and the log say on the download dialog, the player's call of 2026-09-30. */
    const val DOWNLOAD_SAYS = "the game asks to download its data -- answering OK"

    // The game's own windows that nothing here closes (PLAN_RELEASE_1_3.md
    // B72, B73, 2026-10-01): "Time Sale!" ([SALE]), which has no X and one
    // button, the purchase, and the Help tutorial of a page's first visit
    // ([HELP]), whose last page has not been seen. What closes either is not
    // measured, and the conductor's word of that day is no tap at all: the
    // semi-automatic mode stands still on them with its sentence, the fully
    // automatic one parks with it, as on a pink prompt nobody here raised
    // (notes/director.md, "The game's sale window and its Help tutorial are
    // read and left to the player").

    /** What the plate and the log say on [SALE] in the semi-automatic mode. */
    const val SALE_SAYS = "the game's \"Time Sale!\" window -- no X I know and one purchase button; " +
        "I tap nothing in it, close it yourself"
    /** The fully automatic mode's park on [SALE]. */
    const val SALE_PARK = "The game put up its \"Time Sale!\" window, with a purchase button and no X I know. " +
        "Close it yourself; I tap nothing in a shop."
    /** What the plate and the log say on [HELP] in the semi-automatic mode. */
    const val HELP_SAYS = "the game's Help tutorial over this page -- go through it yourself; I tap nothing in it"
    /** The fully automatic mode's park on [HELP]. */
    const val HELP_PARK = "The game's Help tutorial is open over a page. Go through it yourself; I tap nothing in it."

    // ------------------------------------------------------------------------
    // The hologram device's gear window (PLAN_ABSCHLUSS_1_3.md K2, 2026-10-04)
    // ------------------------------------------------------------------------
    // Auto Spend with the Super Hologram Device raises it over the main
    // screen for a piece the S settings keep, and it stands until somebody
    // chooses ([GEAR], Startup.gearWindow). The player's word of 2026-10-04:
    // the director closes it in both modes, beside the window, and never
    // taps Sell or Equip; the piece stays in the inventory (notes/director.md,
    // "The hologram device's gear window is closed beside it, in both modes,
    // and neither of its buttons is ever tapped").

    /** What the plate and the log say on the gear window. */
    const val GEAR_SAYS = "the game's gear window -- closing it beside the window; never Sell, never Equip"

    /**
     * Taps beside one gear window that is still standing after them, before a
     * park: a second for a first the game swallowed, never a third -- the
     * rule of [DOWNLOAD_OKS] and [RESET_SAME_TAPS].
     */
    const val GEAR_TAPS = 2

    /** The park on a gear window still standing after [GEAR_TAPS] taps beside it. */
    const val GEAR_PARK = "The game's gear window stays after $GEAR_TAPS taps beside it. " +
        "Choose Sell or Equip yourself; I tap neither."

    // ------------------------------------------------------------------------
    // The game's windows after its reset (PLAN_RELEASE_1_3.md B50)
    // ------------------------------------------------------------------------
    // After the day's reset at 08:00 the game stacks its windows over the
    // first main screen or list it shows: its news ([NEWS]), Notices and a
    // login bonus (both `dialog`), Idle Rewards ([CLAIM_REWARDS]) -- on
    // 2026-10-01 on instance 1 three news, Notices, a login bonus and Idle
    // Rewards, one after the other (notes/dungeons.md, "After the day's reset
    // the game stacks its news over the list, and the way back closes them one
    // by one"). DirectorLoop.news and DirectorLoop.resetWindow close them;
    // what they may close in which mode is told there (notes/director.md,
    // "The day's reset stacks its windows over the main screen too, and the
    // director closes them: its news in both modes, the rest in the fully
    // automatic one").

    /** What the plate and the log say on a news window. */
    const val NEWS_SAYS = "the game's news after its reset -- closing it with OK"

    /** What the plate says on a window of the stack the semi-automatic mode leaves to the player. */
    const val RESET_LEFT_SAYS = "the game's windows after its reset; in the semi-automatic mode " +
        "I close only its news -- this one is yours"

    /**
     * The windows of one stack the director closes in a row before it stops
     * and parks: seven came on 2026-10-01 -- six in frames and one more by the
     * count of that morning's pass -- and one more is the spare, the count
     * DungeonSkill keeps for the same stack over the list
     * (DungeonSkill.RESET_WINDOWS_MAX). A ninth is something the director
     * does not understand.
     */
    const val RESET_WINDOWS_MAX = DungeonSkill.RESET_WINDOWS_MAX

    /**
     * Taps on one window that is still standing after them, before a park:
     * a second for a first the game swallowed, never a third -- the rule of
     * [DOWNLOAD_OKS] for the same reason.
     */
    const val RESET_SAME_TAPS = 2

    /**
     * Did the tap close the window, or is the same one still standing: the
     * share of pixels moved inside the windows ([windowMotion]) from the
     * frame the tap went out on to a frame of the same screen after it.
     * Measured with `gradlew :core:titleProbe --args="news ..."`
     * (staging/b50/titleProbe_news_stack.txt) on the stacked windows of
     * 2026-10-01 and the two older news over the main screen, every frame
     * 1080 x 1920, inside [RESET_WINDOW_CROP] and, for comparison, over the
     * whole frame:
     *
     *                                                       inside   whole
     *   one window to the next  New Buddy Added to the next  0.4712  0.1289
     *                           that to New Overdrive Added  0.5132  0.1972
     *                           Overdrive to New Buddy       0.4390  0.1477
     *                           two over the main screen     0.5238  0.1821
     *                           news to Notices, Notices to
     *                           the login bonus, that to
     *                           Idle Rewards                 0.5628 to 0.7651
     *   the same window         Overdrive coming in and 99 s
     *                           later, and once more later   0.0000  0.0078, 0.0163
     *
     * so 0.22, the middle of 0.0000 and 0.4390. Over the whole frame the gap
     * was a seventh of that, and the main screen's battle, which moves 0.24
     * by itself between two frames 3.6 s apart (corpus/tall/main_bubble a and
     * b), is in sight around a window over it. Not IDLE_SHARE: that one asks
     * whether anything moved -- the player's hand -- and this one whether the
     * director's own tap achieved something (NOTES.md, "A threshold answers
     * one question").
     */
    const val RESET_NEXT_SHARE = 0.22

    /**
     * After the director closed a window of the stack, how long another
     * screen -- the main screen between two windows, the list -- is waited on
     * before it is the end of the stack: a round's tap there, or a chain
     * step's, would land on the next window coming in. On 2026-10-01 the
     * third news was seen coming in 3 s after the tap that closed the one
     * before it (the way back's tap at 08:45:04, ndo_084507), and twice that,
     * as [SECOND_LOOK] is twice the longest transition measured.
     */
    const val RESET_NEXT = 6.0

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
 * three identical to the pixel (the laboratory's notes, "Prompts and
 * dialogs"), and only the caller knows which; the one caller here is a skill's
 * `work`, which names what it was doing in [Outcome.leaving], and that is the
 * whole of the evidence OK is ever pressed on. Any other pink prompt is left
 * standing and said so, which is what parked means.
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
    /** The fully automatic mode's order, as it was read at the start ([order] keeps it current). */
    chain: Chain,
    /** "Am I in?" -- the shell's included_<key> switch, read every round. */
    private val included: (Skill) -> Boolean = { true },
    /**
     * Why [included] said no, in the player's words -- the row switched
     * off, switched off because the other minigame was switched on, a
     * supporter code missing -- for the chain's skip, which said "not
     * included" until 2026-10-02 (PLAN_BEFUNDE_1_3.md N3 b: Gekkomon Run
     * first in the order and the quest loop ran, because Chef's Special had
     * been switched on and Gekkomon Run off with it).
     */
    private val offWhy: (Skill) -> String = { "not included" },
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
    /**
     * The order and its repeat switch as they are saved now, asked before
     * every step the chain is about to choose; null keeps the order it was
     * built with. Until 2026-10-01 the order was read once, when the core
     * started, and an order changed on the page while the core ran was not
     * the one played: Meat Field moved to the front, and Dungeons ran.
     */
    private val order: (() -> Pair<List<Chain.Step>, Boolean>)? = null,
) {
    /** The fully automatic mode's order: the one built with, or the last the page changed it to ([order]). */
    var chain: Chain = chain
        private set
    /** The last frame, owned here, for the motion measurement. */
    private var before: Mat? = null
    /** The display self-check, at every change of screen (PLAN_FORMATE.md 10). */
    private val display = DisplayCheck.Watch(log, keep)
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
    /**
     * A task the page asked for by its button ("Switch now"): the skill
     * with this key runs its whole `run` from the next plain main screen,
     * once, in either mode, behind the three seconds like every action of
     * the director's own. Written by the shell's thread, taken here. A task
     * whose row is off or locked is not run, and says so.
     */
    @Volatile var runNow: String? = null
    /**
     * The key of a [runNow] task whose `run` ended PARKED, for as long as
     * that park stands: "Try again" on it is a second go at the task the
     * player asked for, not a look at the main screen ([retry]). The
     * player's call of 2026-09-27, after "Try again" on a Presets park
     * (PLAN_FORMATE.md S4 round 5) started the quest loop instead, which
     * entered DemiDevimon twice. The request itself is taken before the run
     * begins, so without this nothing is left of it once the run has parked.
     */
    @Volatile private var parkedRunNow: String? = null
    /**
     * Rows the player switched off and on ([restart]): a skill's key, and
     * whether its day held it back when the switch came on. Written by the
     * shell's thread, taken at the top of the next round ([restarts]).
     */
    private val restartsAsked = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Boolean>>()
    /**
     * Steps started again by their switch, waiting for the fully automatic
     * mode's next choice of a step on the main screen ([full]), where the
     * chain meets them once more in this run ([Chain.again]).
     */
    private val again = LinkedHashSet<String>()
    /** The skill whose outcome the park standing now is, or null ([park]): what [restarts] may lift. */
    private var parkedBy: String? = null
    /** "<key>@<screen>": the skill that worked this visit of this screen. */
    private var handedOver: String? = null
    /** The screen `work` began on, expected back until [expectUntil]. */
    private var expect: String? = null
    private var expectUntil = 0.0
    /**
     * The skill whose `work` [expect] waits for: any screen it works on is
     * the work coming back. One skill has two, EX Missions -- the Missions
     * window and its EX tab -- and a work handed the window on Collection
     * ends on the EX tab by its own tap (ExMissionsSkill.work); without
     * this the director stood [Director.SETTLE] seconds "waiting for the
     * missions to come back". Every other skill works on one screen, and
     * for it nothing changes.
     */
    private var expectBy: Skill? = null
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
    /** When the park standing now began, and whether it goes home by itself ([Director.PARK_RETRY]). */
    private var parkedAt = 0.0
    private var parkResolves = false
    /** That park is a chain step's lost frame: [resolve] takes it home and does not count it (A5g). */
    private var parkLost = false
    /** The way home after such a park is under way: [full] gives the screen to the second hand. */
    private var homing = false
    /**
     * The round whose leftover that way home is ([roundLost]), by name; null
     * for a chain step's, whose park may retire the step ([resolve]).
     */
    private var homeAfter: String? = null
    /** Tries at the same chain step ([parkTriesFor], its key; null with the chain finished). */
    private var parkTries = 0
    private var parkTriesFor: String? = null
    /** Who a task the main switch stopped was running for, and so how it ends when it goes on ([stopped]). */
    private enum class Hold {
        /** A chain step's `run` (the fully automatic mode): goes on in that mode, and ends at home. */
        STEP,
        /** The semi-automatic mode's `work` on a screen it was handed: goes on in that mode, ends there. */
        WORK,
        /** A round of the main screen (the bond tour, Idle Rewards, the quest loop): ends at home. */
        ROUND,
        /** A task the page asked for ([runNow]): ends at home, in either mode. */
        ASKED,
    }

    /**
     * [screen]: for a work, the screen it was handed -- the visit it goes on
     * with is that screen's, whatever screen it goes on from (a dungeon's
     * panel is the list's visit), so that the list coming back after it is
     * "has worked this dungeon_list" and not a second pass of the budgets.
     */
    private class Stopped(val skill: Skill, val hold: Hold, val screen: String,
                          /** When the switch stopped it: its own last tap went out before this ([tick], B71). */
                          val at: Double)

    /**
     * The task whose pass the main switch stopped, for as long as every look
     * since has seen a screen it can go on from ([Skill.resumesOn], asked
     * with the frame): when the switch comes back there, the screen goes to
     * its [Skill.resume] and the pass goes on where it stood. A known screen
     * it says no to -- the player went home, closed the board and opened it
     * again, the game came back on its title -- ends the claim, and what is
     * open is the player's, as before; a screen nobody knows does not end it
     * (a pause that fell into a transition), and neither does the game out
     * of front. Not [leftBy], and not cleared with it while paused: that one
     * is a leftover to walk home from, this one is work to go on with
     * (notes/director.md, "A pass the switch paused goes on where it stands,
     * and a screen the player opened is still the player's", extended on
     * 2026-09-30 to every task and both modes).
     */
    private var stopped: Stopped? = null

    /**
     * The chain step whose `run` the switch stopped, until that step ends in
     * any other way. Its next start on the main screen -- after a claim that
     * ended, or a player who went home in the pause -- is not a new pass but
     * the same one going on ([Skill.resume]): a new pass would spend its
     * budgets a second time (tickets, draws), and the chain has not moved on
     * from the step.
     */
    private var stoppedStep: Skill? = null

    /** A look with the switch off, or a task the switch stopped, since the last look with it on. */
    private var offSeen = false

    /**
     * When the first look with the switch back on came, while it counts: a
     * screen nothing goes on from and a park are held for
     * [Director.SECOND_LOOK] from here, and looked at again (the player's
     * rule of 2026-09-30).
     */
    private var backOnAt: Double? = null

    /**
     * The first screen the pause saw. What a chain step left behind
     * ([leftBy]) stays that step's leftover while the pause sees nothing but
     * this screen: the switch off in the middle of the chain's second hand
     * is not the player opening a screen of their own.
     */
    private var pauseSeen: String? = null
    /** OKs on the game's download dialog since the game was last past its loading ([Director.DOWNLOAD_OKS]). */
    private var downloadOks = 0
    /** When the last of them went out, and whether a look without the dialog has come since. */
    private var downloadAt = 0.0
    private var downloadPending = false

    /**
     * The game's windows after its reset, while a stack of them is open: when
     * the director last closed one -- its own tap -- or null while none is
     * open ([news], [resetWindow], [resetStack]).
     */
    private var resetAt: Double? = null
    /** The windows of this stack the director closed ([Director.RESET_WINDOWS_MAX]). */
    private var resetClosed = 0
    /** The screen and the frame the last of those taps went out on, until a look shows what it did. */
    private var resetScreen: String? = null
    private var resetFrame: Mat? = null
    /** Taps on the window standing now, while no look has shown it go ([Director.RESET_SAME_TAPS]). */
    private var resetSame = 0
    /** The screen of the look before the screen in front, for whether a news window is the reset's ([news]). */
    private var lastScreen: String? = null
    /** Taps beside the gear window while no look has shown it go ([Director.GEAR_TAPS]), and when the last went out. */
    private var gearTaps = 0
    private var gearAt = 0.0
    /** Whether a gear window's frame has been kept since the core started ([gear]). */
    private var gearKept = false

    /** The shell's "Try again": look afresh at a screen the director had parked on. */
    fun retry() {
        // A park of a task the page asked for is that task's to try again
        // ([parkedRunNow]); any other park is a fresh look, as before.
        val again = parkedRunNow
        parkedRunNow = null
        if (again != null) runNow = again
        // A park that goes home by itself ([resolve]) goes home now: before
        // 2026-10-02 the look afresh found the step's leftover forgotten and
        // parked again as the player's screen (22:08:33 on the Poco, after a
        // Try again on Special Summon's "no way home").
        if (parkResolves && mode() == SkillSettings.MODE_FULL) {
            homing = true
            askedToLeave = false
            homePresses = 0
        }
        parkResolves = false
        parked = null
        said = null
        handedOver = null
        // The player asked for another go at the download dialog too, and at
        // the reset's windows.
        downloadOks = 0
        downloadPending = false
        endStack()
        log("trying again on ${screen ?: "the screen"}" + (again?.let { " -- $it once more, as asked" } ?: ""))
    }

    /**
     * The row of the skill [key] was switched off and on (PLAN_ABSCHLUSS_1_3.md
     * A2, the player on 2026-10-02: "Tasks, die ein daily limit haben, durch
     * ein Aus- und Einmachen des Tasks neustarten und damit die
     * Daily-Limit-Meldung wegmachen"). The shell has taken the row's day out
     * of the file already (Stored.restart); [held] says whether that day held
     * the task back. Called from the shell's thread, as [runNow] is set, and
     * taken at the top of the next round ([restarts]).
     */
    fun restart(key: String, held: Boolean) {
        restartsAsked.add(key to held)
    }

    /**
     * The restarts the shell asked for since the last round. Every one tells
     * its skill ([Skill.restarted]: the quest loop forgets its own ending).
     * Where the task's day held it back, or the chain had retired its step,
     * the director forgets its own part of that day too: the retirement, the
     * step met once more in this chain run (fully automatic, [again], taken
     * in [full]), "has worked this ...; leaving it to you" for its screen
     * (semi-automatic: the task works again as soon as its screen stands),
     * and a park that is its outcome. A task its day never held back stays
     * as it is: a Dungeons pass played a second time spends its tickets a
     * second time, and nobody asked for that by switching a row on.
     */
    private fun restarts() {
        while (true) {
            val (key, held) = restartsAsked.poll() ?: return
            val skill = skills.firstOrNull { it.key == key } ?: rounds.firstOrNull { it.key == key }
            skill?.restarted()
            val name = skill?.name ?: key
            val retired = chain.retiredReason(key)
            if (!held && retired == null) continue
            val did = ArrayList<String>()
            if (retired != null) {
                chain.retired.remove(key)
                did += "no longer retired for this chain run ($retired)"
            }
            if (chain.steps.any { it.key == key }) again += key
            val visit = handedOver
            if (visit != null && visit.startsWith("$key@")) {
                handedOver = null
                did += "the ${visit.substringAfter('@')} is its own again"
            }
            if (parked != null && parkedBy == key) {
                log("  ${parked}: lifted -- $name was started again by its switch")
                // A park that would have gone home by itself goes home now,
                // as "Try again" takes it ([retry]).
                if (parkResolves && mode() == SkillSettings.MODE_FULL) {
                    homing = true
                    askedToLeave = false
                    homePresses = 0
                }
                parked = null
                parkResolves = false
                parkedRunNow = null
                said = null
                did += "its park lifted"
            }
            log("$name: started again by its switch" + (if (did.isEmpty()) "" else " -- " + did.joinToString("; ")))
        }
    }

    /** Whatever a chain step left behind is forgotten: the screen is the player's from here on. */
    private fun forgetStep(keepHome: Boolean = false) {
        leftBy = null
        stepParked = null
        askedToLeave = false
        homePresses = 0
        homing = false
        // The second hand's own park keeps whose leftover it was ([resolve]).
        if (!keepHome) homeAfter = null
    }

    /** Release the frame held for the motion measurement, and the one of the last tap on a reset's window. */
    fun close() {
        before?.release()
        before = null
        endStack()
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

    /**
     * [front] is not the game: the player is elsewhere, or the game is gone.
     * "Has it crashed" is a question for the system, not for the screen
     * (notes/director.md). Whatever was seen before is over, clock included,
     * and the next round with the game in front only looks ([awaySeen]).
     */
    private fun away(front: String?): Director.Tick {
        screen = null
        awaySeen = true
        // The package in front goes to the log and not to the note: the
        // note is the sentence on the app's page and in the notification,
        // where the app in front is DigiAutotap itself and its package
        // name read as a link to GitHub (the player's call, 2026-09-24).
        val away = "the game is not in front"
        once(away + (front?.let { " ($it)" } ?: ""))
        return Director.Tick(null, away, beat = Director.BEAT)
    }

    /** The last round found another app in front: the next one with the game only looks. */
    private var awaySeen = false

    /** One look at the screen, and at most one tap of the director's own. */
    fun tick(): Director.Tick {
        takeOverIn = null
        restarts()
        val pkg = game() ?: return still("no game package found on this device", null)
        val front = cap.inFront()
        if (front != pkg) return away(front)
        // **Back in the game, the first round only looks.** Measured on
        // LDPlayer on 2026-09-29 (notes/director.md, "A frame is taken before
        // the system says who is in front"): the round after the game came
        // back grabbed while its window still slid in, 47 px from the left,
        // and read the picture as a dialog. The shell's own guard asks the
        // front once before this round and missed it: the front changed
        // between its question and this one.
        if (awaySeen) {
            awaySeen = false
            return still("the game just came to the front -- letting it settle", null)
        }
        cap.adHand()?.let { hand -> Ads.foreign(hand.front(), pkg)?.let { return ad(it) } }
        val img = try {
            cap.grab()
        } catch (e: CaptureError) {
            return still("no frame: ${e.message}", null)
        }
        frameSize = img.cols() to img.rows()
        // A landscape display is read like any other since 2026-09-27
        // (PLAN_FORMATE.md V19, the player's call): the game stands upright
        // in the middle of it, the full height, and `Dungeon.gameRectWh`
        // finds that canvas as it finds a tablet's (pillaredGame). Measured
        // on eleven LDPlayer frames at 1920 x 1080 against their 1080 x 1920
        // twins: every screen reads as its twin, the quest card since
        // QUEST_RED_TIGHT_LOW. Until then this said "the screen is in
        // landscape -- not supported, not reading", and the dot said nothing.
        val answer = Director.classify(img)
        // **And the front asked again, after the reading.** The same
        // measurement, the other way: opening this app's page, one frame of
        // it was taken while every question the service can ask -- the
        // window events, the active window, the list of windows -- still
        // named the game, and the app's own event came 266 to 411 ms after
        // the page was started. So "the game was in front before the frame"
        // says nothing about the frame; a frame is the game's only if the
        // game is still in front once the switch would have been told
        // (Capture.inFrontAfterFrame). Such a frame read as exit_game twice on
        // 2026-09-29 (PLAN_DAILY_LOST_SECTOR_PRESETS.md 10, G21), and nothing
        // may be done or remembered of it.
        val after = cap.inFrontAfterFrame()
        if (after != pkg) {
            img.release()
            return away(after)
        }
        keepNext?.let { tag ->
            keepNext = null
            keep(img, "${tag}_${answer.screen}")
        }
        val t = now()
        note(answer.screen, img, t)
        // The proof of an OK on the download dialog is the next look without
        // it ([download]); the game past its loading starts the count again.
        if (downloadPending && answer.screen != Director.DOWNLOAD) {
            downloadPending = false
            log("  the download dialog is gone after OK -- ${answer.screen} now")
        }
        if (answer.screen == Director.MAIN || answer.screen == Director.TITLE) downloadOks = 0
        // And of a tap on one of the reset's windows: the window gone, or the
        // next one up in its place ([resetProof]).
        resetProof(answer.screen, img)
        // And of a tap beside the gear window: the next look without it ([gear]).
        if (gearTaps > 0 && answer.screen != Director.GEAR) {
            log("  the gear window is gone after the tap beside it -- ${answer.screen} now")
            gearTaps = 0
        }

        if (!on()) {
            // The player has the game: a stack of the reset's windows the
            // director was closing is over, and what is open after the pause
            // is looked at afresh -- a dialog then is the player's.
            if (resetAt != null) {
                log("  the reset's windows: ${resetClosed} closed, the rest is the player's -- the main switch is off")
                endStack()
            }
            // Read only: what is seen goes to the log, nothing is done about
            // it. The player may do anything meanwhile: a known screen the
            // stopped task does not go on from ends its claim ([stopped]),
            // and one other than the pause's first ends what a chain step
            // left behind ([pauseSeen]).
            offSeen = true
            backOnAt = null
            stopped?.let { s ->
                // The main screen within [Director.SECOND_LOOK] of the stop is
                // the screen the task's own last tap is still leaving, not the
                // player going home (PLAN_RELEASE_1_3.md B71): live on instance
                // 1 on 2026-10-01 the Special Summon step tapped the icon at
                // 13:30:11, the switch went off at :12, the first look of the
                // pause at :13 still saw the main screen and ended the claim,
                // and the summon page that step had opened was the player's
                // by :16 -- a park instead of going on. A main screen still
                // standing after the second look ends it, as before.
                val leaving = answer.screen == Director.MAIN && t - s.at < Director.SECOND_LOOK
                if (answer.screen != Director.UNKNOWN && !leaving && !s.skill.resumesOn(answer.screen, img)) {
                    log("  the ${answer.screen} in the pause: ${s.skill.name} will not go on from it")
                    stopped = null
                }
            }
            val first = pauseSeen
            if (first == null) pauseSeen = answer.screen
            else if (answer.screen != first && answer.screen != Director.UNKNOWN) forgetStep()
            return still("paused, sees ${answer.screen}", beat = Director.BEAT_MAIN)
        }
        if (offSeen) {
            // The first look with the switch back on: the second look's
            // clock starts here ([Director.SECOND_LOOK]).
            offSeen = false
            pauseSeen = null
            backOnAt = t
        }
        if (parked != null) {
            resolve(answer.screen, t)?.let { return it }
            return Director.Tick(answer.screen, parked!!, beat = Director.BEAT_MAIN)
        }

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
            Director.DOWNLOAD -> return download(answer, img, t)
            Director.NEWS -> return news(answer, img, t)
            Director.SALE, Director.HELP -> return gameWindow(answer, img)
            Director.GEAR -> return gear(answer, img, t)
        }
        // The rest of a stack of the reset's windows, while one is open.
        resetStack(answer, img, t)?.let { return it }

        // The task the switch stopped goes on where it stood, before anything
        // else is made of the screen -- a prompt included: the task knows
        // the one it raised itself, the director does not.
        stopped?.let { s -> claim(s, answer, img, t)?.let { return it } }

        if (answer.screen == Director.PROMPT) {
            val intent = leaving
            if (intent != null && t < leavingUntil) {
                return gated(answer, img, t) {
                    val ok = answer.info!!.exitOk!!
                    leaving = null
                    tap(img, ok.fx, ok.fy)
                    Director.Tick(answer.screen, "$intent via OK", "ok")
                }
            }
            // A round's own, which it could not answer before the game left
            // the front: the bond tour's "Raise <name>?" of a tour that broke
            // off between Raise and OK, which it answers with Cancel
            // (BondTourSkill.opened; PLAN_ABSCHLUSS_1_3.md K4).
            val own = rounds.filter { included(it) && it.opened(answer.screen) }
            if (own.isNotEmpty()) return ownWindow(answer, img, own)
            // One of three identical to the pixel, raised by nobody
            // here. Left standing, and said so.
            if (!secondLook()) keep(img, "prompt_not_mine")
            return park(answer.screen, "A prompt is open that I did not raise. " +
                "Answer it yourself; I press OK on nothing I did not open.")
        }

        // "A skill returns when it is finished; the game does not": the
        // screen the work began on is given SETTLE seconds to come back.
        var justBack = false
        if (expect != null) {
            val back = answer.screen == expect || expectBy?.worksOn(answer.screen) == true
            if (back || t >= expectUntil) {
                if (!back) log("  the $expect did not come back after the work; going on with ${answer.screen}")
                expect = null
                expectBy = null
                justBack = true
            } else {
                return still("waiting for the $expect to come back after the work")
            }
        }
        // A window a round's own tap opened a moment ago is that round's to
        // close, in both modes and before either decides the screen is in
        // its way: the bond token's tap opens the Partner window whenever
        // the token was taken just before, and both modes parked on it
        // (notes/bond.md, "A window the bond token's own tap opened is the
        // bond token's to close"). A window the player opened is nobody's
        // here, and goes on below as it always did.
        if (answer.screen != Director.MAIN) {
            val own = rounds.filter { included(it) && it.opened(answer.screen) }
            if (own.isNotEmpty()) return ownWindow(answer, img, own)
        }
        runNow?.let { key -> asked(key, answer, img, t)?.let { return it } }
        if (mode() == SkillSettings.MODE_FULL) return full(answer, img, t, justBack)
        forgetStep()
        // A step started again by its switch is the chain's next only while
        // the chain runs: the semi-automatic mode has no chain run, and one
        // begun later is not the one the switch was thrown in.
        again.clear()
        return semi(answer, img, t)
    }

    /** Inside the second look after the switch came back ([Director.SECOND_LOOK]). */
    private fun secondLook(): Boolean {
        val back = backOnAt ?: return false
        if (now() - back < Director.SECOND_LOOK) return true
        backOnAt = null
        return false
    }

    /**
     * The claim of the task the switch stopped ([stopped]), with the switch
     * on: the screen in front goes to its [Skill.resume] where it says yes,
     * behind the player's three seconds like every hand-over; where it says
     * no inside the second look, the round stands still and looks again;
     * after that the claim is over and null hands the screen to whatever
     * the mode would do with it -- a park included, as before.
     *
     * A chain step's claim holds in the fully automatic mode, while it is
     * still the chain's step and nothing is left over; a work's in the
     * semi-automatic mode; a round's and a task the page asked for in
     * either. The main screen needs no second look: nothing is in the way
     * there, and a stopped chain step starts again from it ([stoppedStep]).
     */
    private fun claim(s: Stopped, answer: Director.Answer, img: Mat, t: Double): Director.Tick? {
        val full = mode() == SkillSettings.MODE_FULL
        val fits = when (s.hold) {
            Hold.STEP -> full && leftBy == null && chain.current()?.key == s.skill.key
            Hold.WORK -> !full
            Hold.ROUND, Hold.ASKED -> true
        }
        if (fits && s.skill.resumesOn(answer.screen, img)) return resume(s, answer, img, t)
        if (fits && answer.screen != Director.MAIN && secondLook()) {
            return still("back on: ${s.skill.name} does not know the ${answer.screen} yet -- looking once more",
                         answer.screen)
        }
        // A chain step on the main screen is not over: [full] goes on with
        // it from there ([stoppedStep]) and says so itself; a round lives
        // there and is ticked again as every round is.
        if (!((s.hold == Hold.STEP || s.hold == Hold.ROUND) && answer.screen == Director.MAIN)) {
            log("  ${s.skill.name} does not go on from the ${answer.screen}")
        }
        stopped = null
        return null
    }

    /**
     * The stopped task's pass goes on ([Skill.resume]), from the screen in
     * front, and what it hands back is taken as it would have been had it
     * never stopped: a chain step's ends the step, a work's is the visit's,
     * a round's is dropped as every round's is, a task the page asked for
     * can be tried again after a park.
     */
    private fun resume(s: Stopped, answer: Director.Answer, img: Mat, t: Double): Director.Tick =
        gated(answer, img, t) {
            stopped = null
            backOnAt = null
            val skill = s.skill
            val screen = answer.screen
            when (s.hold) {
                Hold.STEP -> {
                    log("chain: resuming ${skill.name} on the $screen")
                    chain.recordStart(skill.key)
                    stepEnded(skill, stepRun(skill) { skill.resume(img, true) })
                }
                Hold.WORK -> {
                    log("resuming ${skill.name} on the $screen")
                    workEnded(skill, working(skill) { skill.resume(img, false) }, s.screen)
                }
                Hold.ROUND -> {
                    log("resuming ${skill.name} on the $screen")
                    val outcome = working(skill) { skill.resume(img, true) }
                    ownMotion = true
                    if (outcome.result == Result.STOPPED) held(skill, Hold.ROUND, screen)
                    Director.Tick(screen, "${skill.name}: ${outcome.result.name.lowercase()}", "round")
                }
                Hold.ASKED -> {
                    log("resuming ${skill.name} on the $screen, as asked")
                    val outcome = working(skill) { skill.resume(img, true) }
                    askedEnded(skill, outcome, screen)
                }
            }
        }

    /** A task the switch stopped: its claim, and nothing to wait for -- the game stays where the pause found it. */
    private fun held(skill: Skill, hold: Hold, screen: String) {
        stopped = Stopped(skill, hold, screen, now())
        offSeen = true
    }

    /**
     * An activity of the game that is not the game is in front: an ad by
     * its class, or one nobody has named. No frame is read and nothing is
     * tapped -- the app never touches an ad (notes/ads.md, "The app never
     * touches an ad, and nothing that closed one ships"): what stands here
     * is the player's own ad, or one a task met where the Ad Skip Pass was
     * taken for granted and parked on. An unnamed class is logged as
     * `unknown` and parks after [Director.UNKNOWN_END] in the fully
     * automatic mode, as an unknown screen does (PLAN_WERBUNG.md 5.1). A
     * task's park on its ad is kept: the change of "screen" to the ad is
     * the task's own doing and not the player's.
     */
    private fun ad(cls: String): Director.Tick {
        val t = now()
        if (screen != Director.AD) {
            screen = Director.AD
            seenSince = t
            said = null
            before?.release()
            before = null
        }
        val net = Ads.network(cls)
        if (net == null) once("an activity of the game in front that is no known ad: unknown ($cls)")
        if (!on()) {
            // An ad is no screen of the game's, as the game out of front is
            // not: the pause goes on, and no claim ends here.
            offSeen = true
            return still("paused, sees an ad", Director.AD, Director.BEAT_MAIN)
        }
        parked?.let { return Director.Tick(Director.AD, it, beat = Director.BEAT_MAIN) }
        if (net == null && mode() == SkillSettings.MODE_FULL && t - seenSince >= Director.UNKNOWN_END) {
            return park(Director.AD, "An activity of the game I do not know is in front ($cls).")
        }
        return still("an ad is in front (${net ?: "unknown"}); I never touch an ad",
                     Director.AD, Director.BEAT_MAIN)
    }

    /**
     * "Resource download required" ([Director.DOWNLOAD]): the game after an
     * update, with the buttons of "Exit the game?" and a Cancel that ends
     * the game just the same (notes/director.md, "The game's download dialog
     * wears the exit prompt's face, and only its message is two lines"). The
     * player's call of 2026-09-30 is OK, in either mode, and this is the one
     * place that taps it: a skill that meets the dialog taps nothing and
     * hands the screen back (DungeonSkill.dismissConfirm,
     * LostSectorSkill.cancel).
     *
     * One OK, behind the player's three seconds like every tap of the
     * director's own, at the OK recognise read on this frame, in the
     * rectangle it read it in (notes/formats.md, rule 6). The proof is the
     * next look: the dialog gone ([tick] says so in the log). Still standing,
     * it is given [Director.SETTLE] seconds for the tap to take before
     * anything else is done; standing after that, or back after the download
     * began, it gets a second OK; and after [Director.DOWNLOAD_OKS] a park,
     * with no third tap.
     */
    private fun download(answer: Director.Answer, img: Mat, t: Double): Director.Tick {
        if (downloadPending && t - downloadAt < Director.SETTLE) {
            return still("the game asks to download its data -- waiting for the OK to take")
        }
        if (downloadOks >= Director.DOWNLOAD_OKS) {
            downloadPending = false
            keep(img, "download_again")
            return park(answer.screen, "The game asks to download its data again after " +
                "${Director.DOWNLOAD_OKS} OKs. Answer it yourself; I tap it no more.")
        }
        return gated(answer, img, t) {
            val info = answer.info!!
            val ok = info.exitOk!!
            downloadOks += 1
            downloadAt = now()
            downloadPending = true
            keep(img, "download")
            log(Director.DOWNLOAD_SAYS + (if (downloadOks > 1) " (OK $downloadOks of ${Director.DOWNLOAD_OKS})" else ""))
            tap(img, ok.fx, ok.fy, info.anchor)
            Director.Tick(answer.screen, Director.DOWNLOAD_SAYS, "ok")
        }
    }

    /**
     * One of the game's own windows that nothing here closes: "Time Sale!"
     * ([Director.SALE], B72) and the Help tutorial ([Director.HELP], B73). No
     * tap in either mode -- the sale's one button is the purchase, and what
     * closes either window has not been seen. The semi-automatic mode stands
     * still with the window's sentence, no park and no red: nothing of ours
     * put it there, and the player is the one to close it. The fully
     * automatic mode parks with it, the frame kept, after the second look:
     * its chain starts from the main screen and cannot go on under it. A way
     * home that met either handed it back as it stood (Stays.over).
     */
    private fun gameWindow(answer: Director.Answer, img: Mat): Director.Tick {
        val sale = answer.screen == Director.SALE
        if (mode() != SkillSettings.MODE_FULL) {
            return still(if (sale) Director.SALE_SAYS else Director.HELP_SAYS, answer.screen, Director.BEAT_MAIN)
        }
        if (!secondLook()) keep(img, answer.screen)
        return park(answer.screen, if (sale) Director.SALE_PARK else Director.HELP_PARK)
    }

    /**
     * The hologram device's gear window ([Director.GEAR]): "Equipped" over
     * "Not Equipped", Sell beside Equip, no X. Closed in both modes with one
     * tap beside it, on the dimmed field left of the window
     * ([Startup.GEAR_CLOSE], the spot that opens nothing on the plain main
     * screen), behind the player's three seconds like every tap of the
     * director's own; the piece stays in the inventory. Never Sell, never
     * Equip -- nothing here aims at either. The three seconds come: the
     * window standing over the battle moved 0.0235 and 0.0172 of the frame
     * between looks 0.5 and 2.9 s apart (instance 1, 2026-10-04, `titleProbe
     * news`, staging/gear/titleProbe_gear_motion.txt), under IDLE_SHARE's
     * 0.10. The proof is the next look without the window ([tick]); the next tap on a window still standing
     * waits [Director.IDLE_AFTER] for the first to take, and a window still
     * standing after [Director.GEAR_TAPS] taps is a park with its frame kept.
     * The first window the director closes after the core starts is kept as
     * `gear` (with the developer's switch only, as every kept frame).
     */
    private fun gear(answer: Director.Answer, img: Mat, t: Double): Director.Tick {
        if (gearTaps > 0 && t - gearAt < Director.IDLE_AFTER) {
            return still("the gear window -- waiting for the tap beside it to take", answer.screen)
        }
        if (gearTaps >= Director.GEAR_TAPS) {
            keep(img, "gear_stays")
            gearTaps = 0
            return park(answer.screen, Director.GEAR_PARK)
        }
        return gated(answer, img, t) {
            if (gearTaps == 0) {
                log("the game's gear window is up -- closing it beside the window, never Sell or Equip; " +
                    "the piece stays in the inventory")
            } else {
                log("  the gear window is still up after the tap -- beside it once more")
            }
            if (!gearKept) {
                gearKept = true
                keep(img, "gear")
            }
            gearTaps += 1
            gearAt = now()
            tap(img, Startup.GEAR_CLOSE[0], Startup.GEAR_CLOSE[1], Startup.GEAR_CLOSE_ANCHOR)
            Director.Tick(answer.screen, Director.GEAR_SAYS, "tap")
        }
    }

    // ------------------------------------------------------------------------
    // The game's windows after its reset (PLAN_RELEASE_1_3.md B50)
    // ------------------------------------------------------------------------

    /**
     * The screens a news window of the reset's comes up over, by the look
     * before it: the main screen (and its Stage Failed banner), the dungeon
     * list, the title and the game coming to the front (a login), and a
     * screen nothing names -- the loading, a window growing in. The game puts
     * its news up over the first of these it shows after its reset or a
     * login; over a window or a page the player opened it never came.
     */
    private val newsOver = setOf(null, Director.MAIN, Director.STAGE_FAILED, Director.DUNGEON_LIST,
                                 Director.TITLE, Director.UNKNOWN, Director.NEWS)

    /**
     * One of the game's news after its reset ([Director.NEWS], the
     * announcement: a card of artwork, an OK, and "Don't show again today"
     * beside an empty box): its OK, at the place [Startup.announcement] read
     * on this frame, in its rectangle (notes/formats.md, rule 6), behind the
     * player's three seconds -- a window still growing in moves, and the
     * three seconds are its settling too. Never the box: whether the day's
     * news comes again at the next login is the player's to say. The proof
     * is the next look ([resetProof]): the window gone, or the next one up.
     *
     * In both modes, and in the semi-automatic one only where it is plainly
     * the reset's: the look before it saw a screen the game puts its news
     * over ([newsOver]), or a window of this same stack. A news window over
     * anything else -- a window or page the player opened -- stands, and the
     * plate says so (the conductor's rule of 2026-10-01: in the
     * semi-automatic mode a window of the player's stays theirs). The fully
     * automatic mode closes it wherever it stands: the chain goes on from
     * the main screen under it.
     *
     * After an OK the next tap waits for the window to have stood still for
     * the three seconds since it ([Director.IDLE_AFTER]); a window that is
     * still standing after [Director.RESET_SAME_TAPS] OKs, or a stack longer
     * than [Director.RESET_WINDOWS_MAX], is a park with its sentence and the
     * frame kept.
     */
    private fun news(answer: Director.Answer, img: Mat, t: Double): Director.Tick {
        val semi = mode() != SkillSettings.MODE_FULL
        if (semi && resetAt == null && lastScreen !in newsOver) {
            return still("a news window of the game's over the $lastScreen; I leave it to you", answer.screen,
                         Director.BEAT_MAIN)
        }
        resetAt?.let { at ->
            if (resetScreen == Director.NEWS && t - at < Director.IDLE_AFTER) {
                return still("the game's news -- waiting for the OK to take")
            }
        }
        brake(answer.screen, img)?.let { return it }
        val ok = Startup.announcement(img) ?: return still("the game's news -- its OK is not read", answer.screen)
        return gated(answer, img, t) {
            val first = resetAt == null
            closing(answer.screen, img)
            if (first) {
                // One frame a stack: the morning's first window, for a look at
                // what the game put up and over which screen.
                log("the game's news after its reset is up -- closing it with OK, never \"Don't show again today\"")
                keep(img, "news")
            }
            log("  OK on the news (window $resetClosed)" + (if (resetSame > 1) ", the same one again" else ""))
            tap(img, ok.fx, ok.fy, Startup.ANNOUNCE_ANCHOR)
            Director.Tick(answer.screen, Director.NEWS_SAYS, "ok")
        }
    }

    /**
     * The rest of the stack, while one is open ([resetAt]): Notices and the
     * login bonus (`dialog`), Idle Rewards ([Director.CLAIM_REWARDS]); and
     * any other screen in between. Null hands the screen to the round as it
     * would have gone.
     *
     * What the stack's windows are is what came after a news window the
     * director closed, with nothing but the stack's own windows and screens
     * nothing names between: the evidence DungeonSkill.closeResetWindow
     * takes on its way back to the list. A dialog without news before it --
     * Notices the player opened, a dungeon's panel -- is no part of it.
     *
     *   semi-automatic   nothing but the news is closed: a dialog stands
     *                    with [Director.RESET_LEFT_SAYS] instead of a park, as
     *                    it might be the player's; Idle Rewards goes as it
     *                    always went, to the Idle Rewards task where it is on
     *                    ([semi])
     *   fully automatic  each closed as the way back to the list closes it
     *                    ([resetWindow]), so that the chain goes on
     *   another screen   waited on for [Director.RESET_NEXT] after the last
     *                    tap, in both modes -- the main screen between two
     *                    windows is no place for a round's tap or a chain
     *                    step --, then the stack is over
     */
    private fun resetStack(answer: Director.Answer, img: Mat, t: Double): Director.Tick? {
        val at = resetAt ?: return null
        val screen = answer.screen
        return when (screen) {
            Director.DIALOG, Director.CLAIM_REWARDS ->
                if (mode() == SkillSettings.MODE_FULL) resetWindow(answer, img, t) else null
            // A window coming in or going: the mode's own rule for a screen
            // nothing names, which taps nothing.
            Director.UNKNOWN -> null
            else -> {
                if (t - at < Director.RESET_NEXT) {
                    return still("the game's windows after its reset: $resetClosed closed -- " +
                                 "waiting a moment for the next", screen)
                }
                log("the game's windows after its reset are over: $resetClosed closed -- $screen now")
                endStack()
                null
            }
        }
    }

    /**
     * A dialog or Idle Rewards of the stack, in the fully automatic mode:
     * closed the way DungeonSkill.closeResetWindow closes it over the list,
     * one tap behind the player's three seconds, the proof the next look.
     *
     *   a dialog       (Notices, the login bonus) a tap high up, outside it, at
     *                  the spot that opens nothing on the plain main screen
     *                  ([Director.NEUTRAL_TAP_FX], the Stage Failed banner's
     *                  tap): the laboratory closed Notices with a tap outside
     *                  it (startup.py). Not the back key, which on the screen
     *                  under it asks "Return to the title screen?"
     *   Idle Rewards   a tap beside the window where the Idle Rewards task
     *                  closes it (IdleSkill.CLOSE_SPOT), in its rectangle:
     *                  nothing claimed, no Extra Rewards -- the rewards stay
     *                  in the chest for that task. Only where
     *                  [Quest.idleWindow] reads the window: a Claim alone may
     *                  be another window's, and is left to the round
     */
    private fun resetWindow(answer: Director.Answer, img: Mat, t: Double): Director.Tick? {
        val screen = answer.screen
        val idle = if (screen == Director.CLAIM_REWARDS) (Quest.idleWindow(img) ?: return null) else null
        if (resetScreen == screen && t - (resetAt ?: 0.0) < Director.IDLE_AFTER) {
            return still("the game's windows after its reset -- waiting for the tap to take")
        }
        brake(screen, img)?.let { return it }
        return gated(answer, img, t) {
            closing(screen, img)
            val again = if (resetSame > 1) ", the same one again" else ""
            val note: String
            if (idle != null) {
                note = "Idle Rewards after the game's news -- closing it beside the window, nothing claimed"
                log("  $note (window $resetClosed$again)")
                keep(img, "reset_idle")
                tap(img, IdleSkill.CLOSE_SPOT[0], IdleSkill.CLOSE_SPOT[1], idle.anchor)
            } else {
                note = "a dialog after the game's news -- tapping high up, outside it"
                log("  $note (window $resetClosed$again)")
                keep(img, "reset_dialog")
                tap(img, Director.NEUTRAL_TAP_FX, Director.NEUTRAL_TAP_FY)
            }
            Director.Tick(screen, note, "tap")
        }
    }

    /**
     * Before a tap on one of the reset's windows: a park where the stack is
     * longer than [Director.RESET_WINDOWS_MAX], or the window in front has
     * stood after [Director.RESET_SAME_TAPS] taps; null lets the tap go.
     */
    private fun brake(screen: String, img: Mat): Director.Tick? {
        val same = resetScreen == screen && resetFrame != null
        val why = when {
            same && resetSame >= Director.RESET_SAME_TAPS ->
                "The game's $screen after its reset stays after ${Director.RESET_SAME_TAPS} taps. " +
                    "Close it yourself; I tap it no more."
            resetClosed >= Director.RESET_WINDOWS_MAX ->
                "The game has put up more than ${Director.RESET_WINDOWS_MAX} windows in a row after its reset. " +
                    "Close the rest yourself; I tap no more of them."
            else -> return null
        }
        keep(img, "reset_stays")
        val tick = park(screen, why)
        if (parked != null) endStack()
        return tick
    }

    /** A tap on one of the reset's windows is about to go out: counted, its frame kept for the proof. */
    private fun closing(screen: String, img: Mat) {
        resetSame = if (resetScreen == screen && resetFrame != null) resetSame + 1 else 1
        resetClosed += 1
        resetAt = now()
        resetScreen = screen
        resetFrame?.release()
        resetFrame = img.clone()
    }

    /**
     * The proof of the last tap on one of the reset's windows, on the next
     * looks: another screen, or the same screen with another window in it
     * ([Director.windowMotion] from [Director.RESET_NEXT_SHARE]) -- the next
     * one up -- and the tap took; the same picture is the same window, still
     * standing, and the next tap on it is counted as the same.
     */
    private fun resetProof(seen: String, img: Mat) {
        val frame = resetFrame ?: return
        val tapped = resetScreen ?: return
        val moved = if (seen == tapped) Director.windowMotion(frame, img) else null
        if (seen == tapped && moved != null && moved < Director.RESET_NEXT_SHARE) return
        log(if (seen == tapped) "  the $tapped after the tap is the next one (moved %s)".format(
                moved?.let { "%.3f".format(it) } ?: "-")
            else "  the $tapped is gone after the tap -- $seen now")
        frame.release()
        resetFrame = null
        resetSame = 0
    }

    /** No stack of the reset's windows is open any more. */
    private fun endStack() {
        resetAt = null
        resetClosed = 0
        resetScreen = null
        resetFrame?.release()
        resetFrame = null
        resetSame = 0
    }

    /**
     * The page's button ([runNow]): on the plain main screen, the task's
     * `run`, once. Anywhere else the request waits, said once, and the
     * round goes on as it would have -- null hands it back to the mode.
     */
    private fun asked(key: String, answer: Director.Answer, img: Mat, t: Double): Director.Tick? {
        val skill = skills.firstOrNull { it.key == key }
        if (skill == null || !included(skill)) {
            runNow = null
            log("asked to run $key now, but that task is ${if (skill == null) "not here" else "switched off or locked"}")
            return null
        }
        if (answer.screen != Director.MAIN) {
            once("${skill.name} asked for; waiting for the main screen")
            return null
        }
        return gated(answer, img, t) {
            runNow = null
            log("running ${skill.name} now, as asked")
            askedEnded(skill, working(skill) { skill.run() }, answer.screen)
        }
    }

    /**
     * What a task the page asked for hands back: a park that "Try again"
     * runs again, a stop that goes on where it stood ([Hold.ASKED]), and
     * otherwise the main screen waited for, as after any `run`.
     */
    private fun askedEnded(skill: Skill, outcome: Outcome, screen: String): Director.Tick {
        ownMotion = true
        if (outcome.result == Result.STOPPED) {
            held(skill, Hold.ASKED, screen)
        } else {
            expect = Director.MAIN
            expectUntil = now() + Director.SETTLE
        }
        return after(skill, outcome, screen, "run").also {
            if (outcome.result == Result.PARKED) parkedRunNow = skill.key
        }
    }

    /** The motion measurement and the clock, on every frame read. */
    private fun note(seen: String, img: Mat, t: Double) {
        // Does the game stand where game_rect says? Asked where the screen
        // or the display changes, not on every frame (DisplayCheck.Watch).
        display.look(img, seen)
        if (seen != screen) {
            // A different screen: a new clock, and whatever the director was
            // parked on is gone. A new visit too -- unless the screen is the
            // one a skill's work is being waited for on: the game closing the
            // skill's own result screen in between is the same visit.
            lastScreen = screen
            screen = seen
            quietSince = t
            seenSince = t
            if (expect == null) handedOver = null
            // A task's park on the game's question before an ad stays while
            // the question stands: the change to `prompt` is the task's own
            // View Ads or film button, not the player, and its sentence -- close
            // it yourself, switch the pass off -- is the one the player needs.
            // Live on instance 1 on 2026-10-03 at 15:38:26 the director's own
            // "A prompt is open that I did not raise" took its place a second
            // later. The player's answer to the question changes the screen
            // again and ends it, as every park ends.
            if (!(seen == Director.PROMPT && parked?.endsWith(FreeAds.PARK) == true)) {
                if (parked != null) log("  ${parked}: over, the screen changed")
                parked = null
                parkResolves = false
                // The park is over without "Try again", and the request with it.
                parkedRunNow = null
            }
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

    /**
     * [resolves]: the fully automatic mode's park that is not wanted -- the
     * screen a step left or the player's, with no way home found -- which
     * [resolve] takes home after [Director.PARK_RETRY]. Every other park
     * stands until the screen changes or "Try again", as it always did.
     * [by]: the skill whose outcome it is, for [restarts]. [lost]: the
     * step's park is a lost frame ([Outcome.lostFrame]), which [resolve]
     * takes home without counting it.
     */
    private fun park(screen: String, why: String, resolves: Boolean = false, by: String? = null,
                     lost: Boolean = false): Director.Tick {
        // Not yet, right after the switch came back: the screen gets its
        // second look first (Director.SECOND_LOOK, the player's rule of
        // 2026-09-30) -- a transition, a dialog on its way open or shut.
        if (secondLook()) {
            return still("back on: the $screen is not one I go on from -- looking once more before I park",
                         screen)
        }
        // A new park is nobody's request until [asked] says it is.
        parkedRunNow = null
        parked = why
        parkedBy = by
        parkedAt = now()
        parkResolves = resolves
        parkLost = lost
        log("  parked on $screen: $why")
        return Director.Tick(screen, why, beat = Director.BEAT_MAIN)
    }

    /**
     * A park of the fully automatic mode is not an end any more
     * (PLAN_BEFUNDE_1_3.md N3 c, the player's finding on the Poco: "Bot
     * sollte, wenn er in parked state geht, sicher selber reseten und raus
     * und nochmal versuchen"). After [Director.PARK_RETRY] seconds the park
     * is lifted and the way home taken ([homing]: [full] hands the screen to
     * [secondHand], the task's own way home first, then the globe); from the
     * main screen the chain's step, which a park never advanced, is tried
     * again. [Director.PARK_RETRIES] tries at the same step; a park after
     * the last stands [Director.PARK_LONG], the step is retired for this
     * chain run with its sentence, and the way home is tried once more for
     * the next step. Null while the park is to stand.
     *
     * In the report of 2026-10-01 this is what four parks of the evening
     * would have done by themselves: the quest step's own dungeon list,
     * parked on at 20:34:33, 20:34:51 and 20:35:42 after a round ended in
     * CaptureError (the dot held down, the app opened), and the dialog in
     * between at 20:35:34 -- each was closed by the player's hand.
     */
    private fun resolve(screen: String, t: Double): Director.Tick? {
        if (!parkResolves || mode() != SkillSettings.MODE_FULL) return null
        val step = chain.current()?.key
        if (step != parkTriesFor) {
            parkTries = 0
            parkTriesFor = step
        }
        // A step's lost frame is none of the tries, and leaves their count
        // as it stands (A5g; [full], where the game came back on the main
        // screen): home after PARK_RETRY, never PARK_LONG, never retired.
        val counted = !parkLost
        val wait = if (counted && parkTries >= Director.PARK_RETRIES) Director.PARK_LONG else Director.PARK_RETRY
        if (t - parkedAt < wait) return null
        val why = parked ?: return null
        if (!counted) {
            log("  the park has stood %.0f s -- going home and going on with %s after the game came back".format(
                t - parkedAt, skills.firstOrNull { it.key == step }?.name ?: step ?: "the chain"))
        } else {
            if (parkTries >= Director.PARK_RETRIES) {
                // A round's leftover ([homeAfter]) is no step's: nothing retired.
                if (step != null && homeAfter == null) {
                    log("  chain: retiring $step for this run -- parked after ${parkTries} tries: $why")
                    chain.retire(step, "parked after $parkTries tries: $why")
                    chain.advance()
                }
                parkTries = 0
                parkTriesFor = chain.current()?.key
            }
            parkTries += 1
            log("  the park has stood %.0f s -- going home and trying again (%d of %d)".format(
                t - parkedAt, parkTries, Director.PARK_RETRIES))
        }
        parkLost = false
        parked = null
        parkResolves = false
        said = null
        homing = true
        askedToLeave = false
        homePresses = 0
        return still("going home after the park, to try again", screen)
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
                // A preset page is the player's own: nothing is in the way,
                // and the Presets task, switched off or locked, is simply not
                // there to say "nothing to do" itself (PresetSkill.seesWork).
                Director.PRESET -> still("a preset page; nothing to do here", screen, Director.BEAT_MAIN)
                // The Crests page was `unknown` until it had a name
                // (2026-09-29), and stood still there; with the Lost Sector
                // task off or locked it still does -- nothing is in the way.
                Director.LOST_SECTOR -> still("the Crests page; nothing to do here", screen, Director.BEAT_MAIN)
                // The same for the Missions window with the EX Missions task
                // off or locked (a supporter's): a window the player opens to
                // claim their missions by hand, where nothing of ours is in
                // the way -- a park there would say otherwise.
                Director.MISSIONS, Director.EX_MISSIONS ->
                    still("the Missions window; nothing to do here", screen, Director.BEAT_MAIN)
                // The game's title, which the player opens with the game
                // (PLAN_RELEASE_1_3.md B31): nothing of ours is in the way and
                // nothing here is tapped -- "Touch To Start" is the player's,
                // and a pass that met the title ended there (B30). It parked
                // red with "Something is in the way" until 2026-10-01, a
                // sentence that was not true of it.
                Director.TITLE -> still("the title screen; nothing to do here", screen, Director.BEAT_MAIN)
                // The Explore menu, which the player opens to choose where to
                // go (PLAN_RELEASE_1_3.md B75): no task works on it -- World
                // Search, the Meat Field and Food Effects only walk through it
                // -- and nothing of ours is in the way there. It parked red with
                // "Something is in the way that I did not put there" at every
                // return to the game until 2026-10-01 (R3 on instance 1, five
                // times between 13:05:41 and 13:06:21), as the title did (B31).
                Director.EXPLORE_MENU -> still("the Explore menu; nothing to do here", screen, Director.BEAT_MAIN)
                // A dialog or Idle Rewards after the game's news, in a stack
                // the director is closing ([resetStack]): the semi-automatic
                // mode closes only the news, and a dialog it cannot tell from
                // one the player opened is no park either -- nothing of ours
                // put it there (PLAN_RELEASE_1_3.md B50).
                Director.DIALOG ->
                    if (resetAt != null) still(Director.RESET_LEFT_SAYS, screen, Director.BEAT_MAIN)
                    else park(screen, "Something is in the way that I did not put there: $screen.")
                // The Idle Rewards window has had no task since 2026-10-02
                // (PLAN_BEFUNDE_1_3.md N3 a, the task left the interface): the
                // player opens it to claim by hand, or the game puts it up after
                // its reset, and nothing of ours is in the way there -- the
                // Missions window's rule.
                Director.CLAIM_REWARDS ->
                    if (resetAt != null) still(Director.RESET_LEFT_SAYS, screen, Director.BEAT_MAIN)
                    else still("the Idle Rewards window; nothing to do here", screen, Director.BEAT_MAIN)
                // The minigames' pages with their task off -- Chef's Special
                // while Gekkomon Run is on, and the other way round, or either
                // locked -- are the player playing by hand. Before 2026-09-30
                // (PLAN_SKEWER.md K6) every one of these parked with "Something
                // is in the way that I did not put there" under the player's
                // own round.
                Director.EVENT_PAGE, Director.SKEWER_MENU, Director.SKEWER_STAGE,
                Director.SKEWER_PLAY, Director.SKEWER_PAUSE, Director.SKEWER_OVER ->
                    still("a minigame whose task is off; nothing to do here", screen, Director.BEAT_MAIN)
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
                workEnded(skill, working(skill) { skill.work(img) }, screen)
            }
        }
        return Director.Tick(screen, "nothing to do on the $screen", beat = Director.BEAT_MAIN)
    }

    /**
     * What a work hands back, on the [screen] it was handed. One work per
     * visit, and the screen waited for as it comes back -- but a work the
     * switch stopped is not the visit's work done: it is the same visit
     * going on when the switch comes back ([Hold.WORK]), with nothing to wait
     * for in between, since the game stays where the pause found it (the
     * player's rule of 2026-09-30: no more "has worked this ...; leaving it
     * to you" after a pause).
     */
    private fun workEnded(skill: Skill, outcome: Outcome, screen: String): Director.Tick {
        ownMotion = true
        if (outcome.result == Result.STOPPED) {
            handedOver = null
            held(skill, Hold.WORK, screen)
        } else {
            handedOver = "${skill.key}@$screen"
            expect = screen
            expectBy = skill
            expectUntil = now() + Director.SETTLE
        }
        return after(skill, outcome, screen)
    }

    /**
     * The fully automatic mode: the chain from the main screen, as the PC's
     * chain runs it. Between two steps the main screen gets the passive
     * helper's round ([justBack]: the round after a step handed the screen
     * back), so that a token is not walked past between two skills.
     */
    private fun full(answer: Director.Answer, img: Mat, t: Double, justBack: Boolean): Director.Tick {
        val screen = answer.screen
        // A step the main switch stopped on a screen it goes on from has
        // gone on already ([claim]); what is here is anything else.
        if (screen != Director.MAIN) {
            // The way home after a park that resolves itself ([resolve]).
            if (homing) return secondHand(answer, img, t, homeAfter ?: "the park")
            val step = leftBy
            if (step == null) {
                // The player's screen, before the first step or after a
                // pause no step goes on from: theirs, and a park that stays
                // (F27's rule; a step's own screen is a step's since
                // 2026-10-02 even where its round ended in CaptureError,
                // [stepRun]). An unknown one included -- but with an end and a
                // sentence, where it used to stand still round after round
                // with neither (R4).
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
                // blind -- a park, which goes home by itself after
                // Director.PARK_RETRY and tries the step again ([resolve]).
                // A step that lost its frame here (the game came back on its
                // board, its list) parks as well, goes home as well, and
                // is not counted ([parkLost]); its screen was nothing that
                // was wrong, and no frame is kept of it (K8).
                forgetStep()
                if (parkTries == 0 && !stopped.lostFrame) keep(img, "parked_not_home")
                return park(screen, "${step.name} parked: ${stopped.why}", resolves = true, by = step.key,
                            lost = stopped.lostFrame)
            }
            return secondHand(answer, img, t, step.name)
        }
        if (homing) {
            homing = false
            log("  chain: back on the main screen after ${homeAfter ?: "the park"}; going on with " +
                (chain.current()?.key ?: "the main screen's round"))
        }
        homeAfter = null
        val back = leftBy
        if (back != null) {
            val stopped = stepParked
            if (stopped != null && stopped.lostFrame) {
                // K1 (PLAN_ABSCHLUSS_1_3.md 5): a step that lost its frame
                // has not failed -- the player held the dot and looked at
                // this app's page, and the game is back on its main screen.
                // The report of 2026-10-02 retired the quest loop for the
                // whole chain run at 18:27:53 for a look into the app eight
                // seconds long. The same step begins again from here, as
                // often as the game comes back, and it is none of N3's tries
                // ([resolve]): the player on 2026-10-03, question 17 -- "Der
                // Bot soll immer einfach weitermachen", and "nie zählen" on
                // the limit A5b had set. No step begins while the game is not
                // in front ([tick]'s `away`), and that is the only brake. No
                // frame is kept: the main screen shows nothing that was wrong.
                log("  chain: going on with ${back.name} after the game came back")
            } else if (stopped != null) {
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
        // The order as saved now, before a step is chosen from it -- but not
        // while a step the switch stopped is still to go on: that one is the
        // chain's current step, and its claim asks for it by that place.
        val saved = order?.invoke()
        if (saved != null && stoppedStep == null &&
            (saved.first != chain.steps || saved.second != chain.repeat)) {
            chain = chain.reordered(saved.first, saved.second)
            log("chain: the order is now " +
                (if (saved.first.isEmpty()) "empty" else saved.first.joinToString(" -> ") { it.key }) +
                (if (saved.second) ", repeating" else "") +
                "; going on with what this round has not run" +
                (chain.current()?.let { " -- next: ${it.key}" } ?: " -- nothing left this round"))
        }
        // Steps started again by their switch ([restarts]), met once more in
        // this chain run, under the same rule as the order: not while a step
        // the switch stopped is still to go on.
        if (again.isNotEmpty() && stoppedStep == null) {
            val keys = again.toList()
            again.clear()
            chain = chain.again(keys)
            log("chain: " + keys.joinToString(", ") + " started again by " +
                (if (keys.size == 1) "its switch" else "their switches") + ", once more in this run" +
                (chain.current()?.let { " -- next: ${it.key}" } ?: " -- nothing left this round"))
        }
        val step = chain.current() ?: return mainRound(answer, img, "the chain is finished (${chain.summary()})")
        if (justBack) return mainRound(answer, img)
        val skill = skills.firstOrNull { it.key == step.key }
        val skip = when {
            skill == null -> "no such skill on the phone"
            chain.retiredReason(step.key) != null -> chain.retiredReason(step.key)
            !included(skill) -> offWhy(skill)
            !skill.hasBudget() -> skill.noBudgetWhy() ?: "nothing to do, on the settings as they stand"
            else -> null
        }
        if (skip != null) {
            if (stoppedStep?.key == step.key) stoppedStep = null
            chain.recordSkip(step.key, skip)
            chain.advance()
            // The task's name, not its key: the note is the notification's
            // sentence, read by the player ("skipped runner" said nothing).
            return Director.Tick(screen, "chain: skipped ${skill?.name ?: step.key} -- $skip",
                                 beat = Director.BEAT_MAIN)
        }
        val s = skill!!
        return gated(answer, img, t) {
            // The step the switch stopped, started again from the main
            // screen -- its claim ended in the pause, or it was on the main
            // screen when it stopped: the same pass going on, not a new one
            // that would spend its budgets again ([stoppedStep]).
            val again = stoppedStep?.key == s.key
            log(if (again) "chain: going on with ${s.name} from the main screen" else "chain: starting ${s.name}")
            chain.recordStart(step.key)
            stepEnded(s, stepRun(s) { if (again) s.resume(img, true) else s.run() })
        }
    }

    /**
     * A chain step's turn, whose CaptureError is the step's park and not
     * the end of the round. On the Poco on 2026-10-01 the quest step was
     * playing its dungeon when the dot was held down and this app opened
     * (20:34:24, "the round ended in CaptureError"): the error went past
     * [stepEnded], so the step never handed its screen back, and back in
     * the game its dungeon list read as the player's -- "The fully automatic
     * mode starts from the main screen, and dungeon_list is open." at
     * 20:34:33, 20:34:51 and 20:35:42, closed by the player's hand each time
     * (PLAN_BEFUNDE_1_3.md N3 c). Now it is the step's own park ([stepParked]),
     * which goes home by itself and tries the step again ([resolve]); and
     * where the game comes back on its main screen, the step begins again
     * from there ([Outcome.lostFrame], [full]; PLAN_ABSCHLUSS_1_3.md K1).
     */
    private inline fun stepRun(s: Skill, f: () -> Outcome): Outcome = try {
        working(s, f)
    } catch (e: CaptureError) {
        log("  chain: ${s.name} lost its frame -- ${e.message}")
        Outcome.noFrame(e)
    }

    /**
     * What a chain step's `run` -- or its [Skill.resume] -- hands back.
     * Whatever stands after SETTLE is this step's to be looked after. A step
     * the switch ended hands back nothing to wait for -- the game stays where
     * the pause found it (notes/world-search.md, "After the pause the game
     * stays where it is") -- and is the one to go on when the switch comes
     * back ([stopped], [stoppedStep]).
     */
    private fun stepEnded(s: Skill, outcome: Outcome): Director.Tick {
        ownMotion = true
        forgetStep()
        if (outcome.result == Result.STOPPED) {
            stoppedStep = s
            held(s, Hold.STEP, Director.MAIN)
        } else {
            if (stoppedStep?.key == s.key) stoppedStep = null
            leftBy = s
            expect = Director.MAIN
            expectUntil = now() + Director.SETTLE
        }
        // A step that got through starts the count of a park's tries again
        // ([resolve]): a repeating chain meets the same key next round.
        if (outcome.result == Result.DONE || outcome.result == Result.RETIRED) parkTries = 0
        when (outcome.result) {
            Result.DONE -> chain.advance()
            Result.RETIRED -> {
                // Said in the log as a skip is: live on LDPlayer
                // 2026-09-23 the Special Summon step came and went in
                // three seconds with nothing written between "starting"
                // and the next step, and its sentence was only ever on
                // the overlay for the length of one round.
                log("  chain: retiring ${s.key} -- ${outcome.why}")
                chain.retire(s.key, outcome.why)
                chain.advance()
            }
            Result.STOPPED -> {}
            Result.PARKED -> {
                // Not a park yet (R2). A step that could not get in and
                // came home anyway -- the runner's own `run` goes home in
                // its finally -- used to park the whole chain on a main
                // screen from which the next step could have started.
                // The next look decides: the main screen retires this
                // step for the run and the chain goes on -- or begins it
                // again, where all it did was lose its frame (K1); anything
                // else is a park, as it was.
                stepParked = outcome
                leaving = outcome.leaving
                leavingUntil = if (outcome.leaving != null) now() + Director.SETTLE else 0.0
                log("  chain: ${s.name} could not go on -- ${outcome.why}")
                return Director.Tick(Director.MAIN, "${s.name} could not go on: ${outcome.why}", "run ${s.key}")
            }
        }
        return after(s, outcome, Director.MAIN, "run")
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
     *     So is a screen no skill works on that one skill's way home takes
     *     home from all the same ([Skill.leavesFrom]): a dungeon's panel.
     *  2. A frame on which the globe is read: the globe, at most
     *     HOME_PRESSES_MAX times, as every skill's `goHome` presses it --
     *     never a position, and only while it is read.
     *  3. Neither: a park, with the frame kept.
     *
     * An unknown screen gets no skill (it has none) and no hand at all
     * until it has stood [Director.UNKNOWN_END]: a loading screen is
     * unknown, and so is the game closing a result it opened. Any other
     * dialog is not answered here either -- it dims the nav bar, the globe
     * is not read, and it is a park, because the director presses OK on
     * nothing it did not open.
     */
    private fun secondHand(answer: Director.Answer, img: Mat, t: Double, after: String): Director.Tick {
        val screen = answer.screen
        if (screen == Director.UNKNOWN && t - seenSince < Director.UNKNOWN_END) {
            return still("waiting for the main screen after $after", screen)
        }
        // A dungeon's panel is `dialog` to classify and no skill works on it,
        // and the globe is not read under it: the quest loop's round that lost
        // its frame on DemiDevimon's panel parked here with "no way home" on
        // 2026-10-03 at 18:14:53, three tries and ten minutes, again and
        // again (PLAN_ABSCHLUSS_1_3.md A5i, question 22). Dungeons' way home
        // leaves it, whichever of ours left it -- a round, a step, the quest
        // loop's dungeon or Dungeons' own --, and only here: the player's
        // panel is the player's, as it always was ([full], [semi]).
        val owner = if (screen == Director.UNKNOWN || askedToLeave) null
                    else skills.firstOrNull { it.worksOn(screen) } ?: skills.firstOrNull { it.leavesFrom(screen, img) }
        if (owner != null) {
            return gated(answer, img, t) {
                askedToLeave = true
                log("  chain: $screen left open after $after; asking ${owner.name} to leave")
                val home = working(owner) { owner.leave() }
                // A way home the switch cut short taps nothing and leaves the
                // game where it stood ([Stays]): it is asked again once the
                // switch is back, and not skipped for the globe.
                if (!home && !on()) askedToLeave = false
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
                    log("  chain: $left after $after; pressing the home button")
                }
                homePresses += 1
                tap(img, globe.fx, globe.fy)
                Director.Tick(screen, "pressing the home button after $after", "home")
            }
        }
        forgetStep(keepHome = true)
        // One frame a cause, not one every try ([resolve]).
        if (parkTries == 0) keep(img, "no_way_home")
        val what = if (screen == Director.UNKNOWN) "A screen I do not know" else "The $screen"
        return park(screen, "$what is open after $after, and I found no way home from it.",
                    resolves = screen != Director.TITLE)
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
            val outcome = if (i == 0) {
                try {
                    working(s) { s.work(img) }
                } catch (e: CaptureError) {
                    roundLost(s, e)
                    break
                }
            } else {
                val fresh = try {
                    cap.grab()
                } catch (e: CaptureError) {
                    once("  no frame for ${s.name}'s round: ${e.message}")
                    break
                }
                try {
                    working(s) { s.work(fresh) }
                } catch (e: CaptureError) {
                    roundLost(s, e)
                    break
                } finally {
                    fresh.release()
                }
            }
            // A round's outcome is dropped, all but one: a round the switch
            // stopped away from the main screen -- the bond tour on the
            // Partner page, Idle Rewards with its window open, the quest
            // loop's dungeon -- goes on where it stood when the switch comes
            // back ([Hold.ROUND]), and the rounds after it wait for that.
            if (outcome.result == Result.STOPPED) {
                held(s, Hold.ROUND, answer.screen)
                break
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
     * A round of the main screen that lost its frame away from it: the
     * quest loop's round playing a dungeon when this app came to the front.
     * Live on instance 0 on 2026-10-02 at 11:05:46 (PLAN_BEFUNDE_1_3.md N3
     * c): the round's CaptureError left the tick, and back in the game the
     * dungeon's Reward sheet the round had left was the player's -- "The
     * fully automatic mode starts from the main screen, and I do not know the
     * screen that is open." In the fully automatic mode what it left is taken
     * home as a step's leftover is ([homing], [secondHand]), and a park on
     * the way resolves itself ([resolve]) without retiring the chain's step,
     * which this was not. The semi-automatic mode gives the screen to whoever
     * works on it, as ever.
     */
    private fun roundLost(s: Skill, e: CaptureError) {
        log("  ${s.name} lost its frame in its round -- ${e.message}")
        if (mode() != SkillSettings.MODE_FULL) return
        homing = true
        homeAfter = s.name
        askedToLeave = false
        homePresses = 0
    }

    /**
     * The main screen's other rounds, once, from inside the turn of the
     * round-skill [running] -- the quest loop's chain step, which waits on
     * its card for minutes and is one turn of the director's all that time
     * (QuestSkill's `aside`, called between two of its rounds that tapped
     * nothing). PLAN_BEFUNDE_1_3.md N3 d, the player on 2026-10-01: "Wenn
     * Quest Loop läuft, sollen zwischendurch die Bond Tokens collected
     * werden". Between two steps the chain gives the main screen to the
     * rounds already ([full], `justBack`), and in the semi-automatic mode
     * every round of the main screen ticks them all in turn ([mainRound]);
     * inside the step nobody did.
     *
     * What [mainRound] does, on frames of its own: every included round with
     * a budget but [running] -- the bond token, its tour where its box is
     * on -- each on a fresh frame, and only on the plain main screen. A
     * window one of them opened with its own tap ([Skill.opened]) is that
     * round's to close, as [ownWindow] does. Not [working]: the plate goes
     * on naming the task at work. An outcome is dropped, as a round's is;
     * a switch gone off is [running]'s to see at its next look.
     */
    fun aside(running: String) {
        if (!on()) return
        val others = rounds.filter { it.key != running && included(it) && it.hasBudget() }
        if (others.isEmpty()) return
        val first = try {
            cap.grab()
        } catch (e: CaptureError) {
            return
        }
        try {
            val seen = Director.classify(first).screen
            if (seen != Director.MAIN) {
                others.firstOrNull { it.opened(seen) }?.let { s ->
                    log("  ${s.name} is closing the $seen its own tap opened")
                    s.work(first)
                }
                return
            }
            for ((i, s) in others.withIndex()) {
                if (i == 0) {
                    s.work(first)
                    continue
                }
                val fresh = try {
                    cap.grab()
                } catch (e: CaptureError) {
                    return
                }
                try {
                    s.work(fresh)
                } finally {
                    fresh.release()
                }
            }
        } finally {
            first.release()
        }
    }

    /**
     * A window the [own] round-skills opened with their own tap
     * ([Skill.opened]): the first of them works it, which closes it, at the
     * slow beat -- the bond token's round counts its taps before it tries
     * the back key, and half a second apart the key would come before the
     * first tap had closed anything. Not gated, as the main screen's rounds
     * are not: the window is the round's by its own clock, and waiting three
     * seconds on it would only let that clock run down.
     */
    private fun ownWindow(answer: Director.Answer, img: Mat, own: List<Skill>): Director.Tick {
        val s = own.first()
        working(s) { s.work(img) }
        ownMotion = true
        return Director.Tick(answer.screen, "${s.name} is closing the ${answer.screen} its own tap opened",
                             "close", Director.BEAT_MAIN)
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
        // A screen handed over is past its second look: what the skill
        // parks on afterwards is its own park, said at once.
        backOnAt = null
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
            Result.PARKED -> park(screen, "${skill.name} parked: ${outcome.why}", by = skill.key)
                .let { Director.Tick(screen, it.note, did) }
        }
    }

    /**
     * A tap by fraction of the reference window, as `DungeonBot.tap` sends
     * one, in the rectangle at [anchor] -- the one the reader that aimed it
     * read in (Dungeon.Recognition.anchor); the bottom's where it has none.
     */
    private fun tap(img: Mat, fx: Double, fy: Double, anchor: Dungeon.Anchor = Dungeon.Anchor.BOTTOM) {
        val r = Dungeon.gameRect(img, anchor)
        ownMotion = true
        cap.tap(Py.roundInt(r.x0 + fx * r.gw), Py.roundInt(r.y0 + fy * r.gh))
    }
}
