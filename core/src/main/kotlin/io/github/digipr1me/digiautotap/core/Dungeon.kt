package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * dungeon.py's readers, carried over line for line: `recognise` and
 * everything it stands on -- `game_rect`, `find_buttons`, `list_cards`,
 * `confirm_kind`, `party_slots_filled`, `claim_button`, `violet_beside` --
 * the list card's counters (`badge_crop`, `badge_glyphs`, `card_counters`,
 * `card_budget`, `card_has_attempts` and the digit reader under them), and
 * beside them `popup_ok`, `stage_failed`, `auto_button` and `home_button`.
 *
 * Every constant keeps its Python name and value, and the sentence that says
 * where it came from. A number here is changed in dungeon.py first, with a
 * measurement, then the oracle is written again, then this file follows --
 * never the other way round (NOTES.md, "Two implementations, one
 * direction"). The frame is BGR, uint8, as `cv2.imread` gives it.
 */
object Dungeon {

    // Device aspect ratio. Used to compute away the emulator window's title
    // bar without detecting it.
    const val DEVICE_ASPECT = 1080 / 1920.0

    /** An HSV range, (low, high), in OpenCV's 0-179 hue scale. */
    class Hsv(val lo: Scalar, val hi: Scalar) {
        constructor(lo: IntArray, hi: IntArray) : this(
            Scalar(lo[0].toDouble(), lo[1].toDouble(), lo[2].toDouble()),
            Scalar(hi[0].toDouble(), hi[1].toDouble(), hi[2].toDouble()))
    }

    // Colour ranges in HSV
    val BLUE = Hsv(intArrayOf(95, 150, 150), intArrayOf(115, 255, 255))
    // Measured on the buttons themselves, H 122 to 125, S 152 to 170, V 235.
    // The old lower bound of 125 sat exactly on the edge and found the ad
    // button or not depending on the frame.
    val VIOLET = Hsv(intArrayOf(117, 100, 120), intArrayOf(145, 255, 255))

    // Expected horizontal position of the buttons, relative to the game width
    const val POS_ATTEMPT = 0.66
    const val POS_CENTER = 0.50
    const val POS_CLEAR = 0.34
    const val POS_TOLERANCE = 0.06

    // Attempt sits at 0.66 when Clear Previous Difficulty is next to it. If
    // that is missing, Attempt sits centred, measured at Apocalymon Wall
    // 0.501. A single blue button in the dialog is therefore always Attempt.
    const val BUTTON_MAX_Y = 0.88
    val ATTEMPT_W = doubleArrayOf(0.18, 0.36)

    // Give Up during a battle, centred at the very bottom. Measured 0.502 at
    // 0.954. Without this detection the bot mistook battle artwork for the
    // Attempt button, measured 0.681 at 0.698 with matching size.
    const val POS_GIVEUP_Y = 0.90

    // Buttons sit in the lower part of the dialog. The filter keeps artwork
    // out, for example DemiDevimon's violet wings at y 0.34.
    const val BUTTON_MIN_Y = 0.45

    // A blob that touches a side of the picture was cut by it, and its width
    // is then the picture's, not the thing's -- so it is not a button,
    // whatever its width says. On a long display (COVER_GAP) the DUNGEON and
    // EXPLORE headers, 0.94 of the reference width at 1920 and refused by
    // every width band, are cut to 0.77, inside the card band, and the
    // half-hidden card and nav bar at the bottom of the list are cut to the
    // same 0.77 at fy 0.925: the list grew a fifth card and a Give Up, and
    // read as a battle. Nothing that is a button comes near a side: the
    // closest card over the corpus is 51 px in at 1920, and at 2340 a card
    // spans 1017 of the 1080 px, 31 px in. One pixel of tolerance for an
    // anti-aliased edge; a cut blob runs to the very edge.
    const val BUTTON_EDGE_PX = 1

    // Party slots at Network Defense Ops. Three fields side by side. An empty
    // slot is a uniformly dark area, measured standard deviation 0.0, an
    // occupied one shows a figure, measured 28 to 56. The dialog itself looks
    // identical with and without a party, both buttons sit at the same place,
    // so this is the only way to see the difference before clicking.
    //
    // The half-width is 0.07 and not 0.10 because the wider crop reached past
    // the right-hand slot onto the panel's own bright edge. Measured on a
    // real frame with one slot filled, half-width against standard deviation:
    //
    //              left (empty)   middle (full)   right (empty)
    //   0.10            3.0            49.9            13.8   <- counted as full
    //   0.08            0.0            55.0             8.5
    //   0.07            0.0            56.0             3.6
    //
    // That misread said two slots were filled, so the bot never searched for
    // a party, pressed Attempt without one, and Attempt does nothing without
    // one.
    val PARTY_SLOTS = doubleArrayOf(0.27, 0.50, 0.73)
    const val PARTY_SLOT_Y = 0.45
    const val PARTY_SLOT_HALF_W = 0.07
    const val PARTY_SLOT_MIN_STD = 12.0

    // The OK button of the pop-ups the game opens after logging in. Measured
    // on two of them: centred at 0.475 with the button spanning roughly a
    // third of the width, at 0.905 down the screen.
    //
    // Note what this collides with: POS_GIVEUP_Y is 0.90, so recognise()
    // calls any blue button below that near the centre a battle's Give Up
    // button, and these pop-ups therefore read as BATTLE. That is only safe
    // to act on while waking the game up, when no battle can be running yet
    // -- see wake_up.
    val POPUP_OK_Y = doubleArrayOf(0.86, 0.95)
    val POPUP_OK_FX = doubleArrayOf(0.35, 0.65)
    const val POPUP_OK_MIN_W = 0.15

    // The Claim button of the idle-rewards dialog, measured off a real one:
    // centred at 0.597, 0.746, with a violet "Extra Rewards" button
    // immediately to its left that watches ads for more.
    //
    // That violet neighbour is not decoration, it is the identification. The
    // Notices dialog puts its "Campaigns" tab at 0.476, 0.733 -- same colour,
    // same band, near enough the same width -- and an earlier version pressed
    // it repeatedly believing it was Claim. No position test separates those
    // two. The pair does: Notices has no violet button anywhere.
    val POS_CLAIM_Y = doubleArrayOf(0.68, 0.82)
    val POS_CLAIM_FX = doubleArrayOf(0.45, 0.85)
    const val POS_CLAIM_MIN_W = 0.12
    const val CLAIM_PAIR_DY = 0.03

    // The pair alone is not enough, and the frames say so. Swept over every
    // stored frame in the project, this found a Claim on nine frames that are
    // **the Partner and Buddy windows** -- a violet Encyclopedia with a blue
    // Move beside it, the same colours, the same widths, the same band. Not
    // one of them is a rewards dialog, and pressing "Claim" on one is
    // pressing Move, which opens the formation screen over whatever the
    // caller was doing.
    //
    // Both sources, 573 x 1056 to 1080 x 1920, every frame there is of
    // either:
    //
    //   dialog                 frames   violet fx        gap            height
    //   Idle Rewards              3     0.346 - 0.352   0.245 - 0.251   0.052 - 0.055
    //   Partner / Buddy window    9     0.323 - 0.332   0.279 - 0.298   0.039 - 0.046
    //
    // The gap and the height each separate them and neither has much room:
    // 0.265 sits 5.6 % above the widest real gap and 5.0 % below the
    // narrowest false one, 0.049 sits 5.8 % under the shortest real button
    // and 6.5 % over the tallest false one. That is under the tenth NOTES.md
    // asks for, which is why there are two of them rather than one -- an
    // impostor has to pass both, and the two clusters are on opposite sides
    // of each. The violet's own place is the third thing that differs and
    // the weakest, 0.014 of air, so it is not used at all.
    //
    // The third Idle Rewards frame is the reason the red "0/2" on the Extra
    // Rewards button is not the test it looks like: on that one the same
    // button reads "2/2" in white.
    val CLAIM_PAIR_GAP = doubleArrayOf(0.20, 0.265)
    const val CLAIM_BUTTON_FH = 0.049

    // List cards. They all sit centred at relative x 0.49 and have width
    // 0.77, measured across both window sizes. Dialogs and the battle screen
    // show different widths, so this signature separates the list
    // unambiguously.
    const val CARD_X = 0.49
    const val CARD_X_TOL = 0.05
    const val CARD_W_MIN = 0.72
    const val CARD_W_MAX = 0.85
    // A card is at most the banner at the top of the list, fh 0.21: 22 of
    // those over the corpus against 98 ordinary cards at 0.12. The blob that
    // read as a card at 0.336 (corpus/passive/unclear_214821.png) was two
    // cards and the nav bar joined by a loading ring the game had drawn
    // between them; its badge crop then landed on the nav bar and the globe
    // read as 925 tickets. 0.25 is 19 % over the banner and 26 % under that
    // blob. What else it refuses, 0.254 to 0.288, stands on dialogs and a
    // Partner window and was never a card.
    const val CARD_H_MAX = 0.25
    const val LIST_MIN_CARDS = 3

    // Ticket badge in the list card. The ticket count sits at the bottom
    // left, possibly with a second counter for ad attempts next to it.
    const val BADGE_X = 0.10
    const val BADGE_W = 0.34  // wide enough for the ticket and ad counters side by side
    // Distance of the badge from the card's bottom edge, plus its height
    const val BADGE_BOTTOM_OFF = 0.008
    const val BADGE_H = 0.040

    // Whether a counter reads 0 can be read from the leading digit, without
    // any digit recognition. The zero has a hole in the middle, measured 0.00
    // against 0.33 to 0.93 for all other digits. The digit set from the
    // minigame does not fit here, the font is different.
    const val ZERO_HOLE_MAX = 0.15

    // Smallest character in a counter, as a share of the badge crop's height.
    // Measured over nine cards on two frames, and the spread within each kind
    // is under half a percent:
    //
    //   ticket digits   0.269 - 0.279      ticket slash   0.337 - 0.346
    //   ad digits       0.226 - 0.231      ad slash       0.288 - 0.293
    //
    // This used to be 0.28, which falls between the ticket digits and the ad
    // slash -- the worst place there is. It threw away every digit of the
    // counter that matters and kept the slash of the one that does not, so
    // the ticket count read as unreadable on every card and every dungeon was
    // played "unclear, will try it". At 0.20 all four kinds come through;
    // what else comes with them is thrown out by the slash rule below.
    const val GLYPH_MIN_H = 0.20

    // A slash is narrow and clearly taller than the digits beside it.
    // Measured: 71 against 57 in the ticket counter, 60 against 47 in the ad
    // counter, so 1.25 and 1.26 -- the same proportion in both, which is what
    // a font does.
    const val SLASH_MIN_RATIO = 1.12
    const val SLASH_MAX_WH = 0.60

    // A counter is made of digits and a slash, and neither is square. The
    // ticket icon in front of the counter is, and on the Digifactory card it
    // is yellow and bright enough for the white mask: badge_glyphs found it
    // on 20 cards of the corpus, 0.36 of the crop tall -- taller than the
    // slash -- so it became the group's "slash", failed SLASH_MAX_WH, and the
    // whole ticket counter was thrown out with it. What was left was read as
    // the tickets: the film counter on a card at 0/2 (2 tickets read off a
    // card with none, corpus/bond/select_the_next_Digimon_143619.png), or
    // nothing at all on a card at 3/2 -- and the quest loop reads its
    // dungeonShort off exactly this. Measured 2026-09-23 over every card of
    // every list in the corpus, width over height:
    //
    //   digits of a counter (232)   0.61 - 0.75
    //   slashes (217)               0.42 - 0.49
    //   everything else (47)        0.91 - 1.18   the icon 0.97 - 1.01,
    //                                             the word "Rewards" at 1.12
    //
    // 0.85 sits 13 % over the widest digit and 7 % under the narrowest of
    // the rest. The oracle moved on 25 cards, each one looked at: a 3/2
    // card (two frames) and a 2/2 card read now where they read nothing,
    // the card with 0 tickets and 2 ads no longer reads as 2 tickets,
    // eleven more at 0/2 read their film counter as the film counter and
    // not as the tickets (card_has_attempts calls them "unclear, try it"
    // now, its rule for a card that shows a film counter), and ten "7"s
    // are gone that the word "Rewards" and
    // a yellow stroke had made on screens that are no dungeon list.
    const val COUNTER_GLYPH_WH_MAX = 0.85

    // How a digit is measured. The glyph is squashed onto a 12 x 16 grid
    // first, so a counter reads the same whatever size the emulator window is
    // -- these very numbers were taken from two windows, 760 x 1310 and
    // 619 x 1059.
    //
    // Deliberately no stored pictures of the digits. They would be images
    // taken from the game, which is the one thing this program does not ship,
    // and it would turn the dungeon bot into a bot that needs setting up.
    // Shapes are described by numbers instead, the way every other threshold
    // here is.
    //
    // Measured on Apocalymon counting down from 46 to 37, which walks the
    // units place through all ten digits, plus the tens place giving 4 and 3:
    //
    //   digit  holes  hole y   ink   top    bottom  right
    //     0      1     0.47    0.49  0.42    0.54    0.64
    //     1      0      -      0.41  0.33    0.88    0.16
    //     2      0      -      0.44  0.50    1.00    0.53
    //     3      0      -      0.45  0.54    0.71    0.67
    //     4      1     0.45    0.41  0.29    0.17    0.58
    //     5      0      -      0.49  0.79    0.67    0.50
    //     6      1     0.63    0.53  0.58    0.58    0.53
    //     7      0      -      0.35  1.00    0.21    0.30
    //     8      2     0.47    0.56  0.58    0.71    0.67
    //     9      1     0.33    0.52  0.50    0.54    0.66
    //
    // The thresholds below sit in the gaps of that table. The two roomiest
    // are the 4, whose bottom is empty at 0.17 against 0.54 for every other
    // one-hole digit, and the 2, whose bottom bar is solid. The tightest are
    // the hole positions that separate 9, 0 and 6, with 0.05 to 0.08 either
    // side; those are the ones to widen first if a digit is ever read
    // wrongly.
    val DIGIT_GRID = intArrayOf(12, 16)
    const val D4_BOTTOM_MAX = 0.35        // 4 against 0, 6, 9
    const val D9_HOLE_MAX = 0.40          // 9 against 4 and 0
    const val D6_HOLE_MIN = 0.55          // 6 against 0
    const val D7_BOTTOM_MAX = 0.35        // 7, the only holeless digit with an empty foot
    const val D1_RIGHT_MAX = 0.25         // 1, which is empty on its right
    // ...except where the 1 stands on a serif. The quest card's font draws
    // it with a full bar under it, and that bar reaches the right edge:
    // measured on "Defeat 12/50", right came to 0.38 over the whole glyph,
    // sailed past the test above, and the 1 was then read as a 2 on the next
    // line down (bottom 0.92, over D2_BOTTOM_MIN). Six other 1s on the same
    // run of cards measured 0.12 to 0.20 and were read correctly, so this is
    // not a threshold to widen -- 0.38 is a different shape, not a noisier
    // one.
    //
    // What no 1 has, serif or not, is ink on its right *above* the foot.
    // Measured over the rows between the flag and the bar, the last three
    // columns of the grid:
    //
    //   1 (n=7)                     0.00
    //   every other digit (n=65)    0.45 to 0.91
    //
    // So the floor sits at 0.15: three times under the lowest of the others,
    // and above nothing at all, which is what a 1 has there.
    val D1_ROWS = intArrayOf(2, 13)
    const val D1_RIGHT_COLS = 3
    const val D1_UPPER_RIGHT_MAX = 0.15
    const val D2_BOTTOM_MIN = 0.85        // 2 against 3 and 5

    // 5 against 3, and this one was learned the hard way. It went by the top
    // band first, on the reasoning that the 5 opens with a solid bar while
    // the 3 opens with an arc -- 0.79 against 0.54, which looked like room
    // enough. In a live window one size smaller the arc of a 3 filled out to
    // 0.75 and it was read as a 5, so 34 tickets became 54.
    //
    // The row below the top says it far more plainly, and it says it about
    // the shape rather than about how thickly the shape was drawn: under its
    // opening the 5 carries a stem down the left with nothing on the right,
    // and the 3 carries its bulge on the right with nothing on the left.
    //
    //   rows 3 to 6      left quarter    right quarter
    //     5                  0.75            0.00
    //     3                  0.00            0.85
    //
    // So the test is which flank is heavier, and the gap is the whole width
    // of the glyph rather than a tenth of a measurement.
    val D53_ROWS = intArrayOf(3, 7)

    // The daily allowance, the number after the slash on both counters. The
    // game resets two tickets a day and allows two watched ads a day.
    const val DAILY_ALLOWANCE = 2

    // States. The strings are dungeon.py's own, and they are what the oracle
    // holds.
    const val LIST = "liste"
    const val DIALOG = "dialog"
    const val DIALOG_PARTY = "dialog_party"
    const val DIALOG_AD = "dialog_werbung"
    // What the game is doing while nothing else is: it is an idle game and
    // the party clears stages in the background the whole time. The name is
    // printed straight into the log, so it says that rather than "kampf".
    const val BATTLE = "battling in stages"
    // Confirmation dialogs with two buttons side by side, cancel on the left,
    // confirm on the right. Two cases are known and both are dangerous.
    //
    //   "Exit the game?"                OK closes the game
    //   "Disband the party and leave?"  OK disbands the party
    //
    // Measured, both buttons sit at the same place, OK at 0.59 to 0.64 and
    // Cancel at 0.36 to 0.40, same size each. Hence one state for both,
    // leaving is always done via the left button.
    const val EXIT = "sicherheitsabfrage"

    // THREE dialogs wear this face, and they do not share an answer. Cancel
    // is not always the safe choice, and neither is OK.
    //
    //   "Exit the game?"                 grey Cancel, OK at 0.561 / 0.652.
    //                                    Only ever seen on the title screen.
    //                                    OK ends the session, so Cancel.
    //   "Disband the party and leave?"   pink Cancel, OK at 0.602 / 0.599.
    //                                    Cancel keeps the bot stuck in the
    //                                    dungeon panel, OK returns to the
    //                                    list. Looks the same whether or not
    //                                    there is a party in the slots,
    //                                    confirmed by the player -- so the
    //                                    pink test holds for the solo case
    //                                    too.
    //   "Return to the title screen?"    pink Cancel, OK at 0.602 / 0.599.
    //                                    What the back key raises in the game
    //                                    with nothing open. OK throws the
    //                                    session away, so Cancel.
    //
    // The last two are the same picture. Measured on real frames of both, the
    // sample over the Cancel button is identical to the pixel: 0.648 of it in
    // hue 140-150 either way. Nothing in the dialog separates them -- only
    // the text does, and this bot reads no text so that it works in every
    // language.
    //
    // So colour answers one question only: is this the game-exit prompt
    // (grey, measured 0.000 in that hue band) or one of the two in-game
    // prompts (pink, 0.648). Which of the two in-game ones it is has to come
    // from the caller, which knows whether it was just trying to leave a
    // dungeon. See dismiss_confirm.
    const val EXIT_CANCEL_SAT_MAX = 100
    // Hue of the pink Cancel button, in OpenCV's 0-179 scale. Saturation
    // alone cannot carry this: the dialog's own background is blue and just
    // as saturated, and sampling that instead of the button is how an exit
    // dialog came to be read as a party dialog. Pink is a hue; blue is a
    // different one.
    //
    // The band starts at 135 and not at 150 because the real button measures
    // hue 140-150 with saturation 147. At (150, 179) it scored 0.021 against
    // the 0.15 the test asks for, so every pink prompt read as the exit
    // prompt, was answered with Cancel, and the bot sat in the dungeon panel
    // until it gave up. The grey Cancel of the real exit prompt sits at hue
    // 100-110 and scores 0.000 in this band, so the margin is the whole
    // range.
    val PARTY_PINK_HUE = intArrayOf(135, 175)
    // "Reward -- Tap to close" over a won run or over the panel after Clear
    // Previous Difficulty (rewardSheet). Not a dialog: a tap anywhere closes it.
    const val REWARD = "reward_sheet"
    const val UNKNOWN = "unknown"

    // The game's exit confirmation. It appears when the back key is pressed
    // and no dialog is open. Two buttons side by side at about half height,
    // grey Cancel on the left at 0.40 and blue OK on the right at 0.59,
    // measured. OK would close the game, so this screen must be reliably
    // recognised and left via Cancel.
    const val POS_EXIT_OK = 0.61
    const val POS_EXIT_CANCEL = 0.38
    const val EXIT_TOL = 0.07
    val EXIT_Y = doubleArrayOf(0.50, 0.75)
    val EXIT_W = doubleArrayOf(0.12, 0.24)

    // Every fx/fy in this file is a fraction of the *window image*, because
    // that is what they were measured on: 805 x 1390 frames from screen
    // capture, with LDPlayer's own chrome part of the picture.
    // GAME_IN_WINDOW says where the game sat inside that reference window --
    // left, top, width, height as fractions of it -- and so defines what
    // those numbers mean.
    //
    // Measured by matching a window frame against the ADB frame of the same
    // screen, correlation 0.99: the game is 758 x 1348 at 4, 40. A 40 px tab
    // bar on top and a 43 px sidebar on the right are therefore inside every
    // fraction in this file. (capture.py, next to the window class it
    // describes.)
    val GAME_IN_WINDOW = doubleArrayOf(4 / 805.0, 40 / 1390.0, 758 / 805.0, 1348 / 1390.0)

    // The same chrome in pixels: left, top, right, bottom. It does not scale
    // with the window, which is why a fraction of the window image is only
    // worth anything once the window has been measured -- at 619 wide the
    // sidebar is 6.9 % of the width instead of 5.3 %, and a fraction read as
    // if the window were still 805 wide lands 18 device pixels off.
    // The 43 px on the right are LDPlayer's own sidebar, the 40 on top its
    // tab bar. Both are in every window frame and in none of an ADB frame,
    // which is the whole reason the two have to be reconciled at all.
    val WINDOW_CHROME = intArrayOf(4, 40, 43, 2)

    // The chrome above, cross-checked against the one thing that cannot
    // change: the game's own aspect. If what is left after taking the chrome
    // off is not that shape, this is not the window layout that was
    // measured, and the frame is used as it comes rather than on a guess.
    const val CHROME_ASPECT_TOL = 0.01

    // How far a frame's aspect may sit from the device's before it is taken
    // for a window frame rather than a bare game frame. The two are 0.5625
    // against 0.5791, so anything under half that gap separates them with
    // room to spare.
    const val DEVICE_ASPECT_TOL = 0.008

    // A long display: the game covers it. Measured in LDPlayer at 1080 x 2340
    // against 1080 x 1920, seven screens, two frames each, every 96 px patch
    // of the 9:16 frame looked for in the long one at five scales
    // (_tall_measure.py, corpus/tall): 1077 of 1191 patches land within 6 px
    // of x' = 1.21875 x - 118, y' = 1.21875 y at scale 2340 / 1920 in both
    // directions, and whole frames match that model at 0.94 to 0.98 against
    // 0.61 to 0.77 for stretching and 0.31 to 0.52 for bars at the top or
    // bottom. So the game scales its 9:16 canvas evenly to the display's
    // height, centres it, and cuts 118 px off each side: circles stay
    // circles, every fraction of the canvas is what it was, and the canvas
    // on the long display is the one rectangle (w - h * DEVICE_ASPECT) / 2,
    // 0, h * DEVICE_ASPECT by h. Only what stands in the outer 97 px (1920
    // measure) of the canvas is lost, and a blob that touches a side of the
    // picture was cut by it -- see BUTTON_EDGE_PX.
    //
    // What says a frame is such a display and not a letterboxed window --
    // the fittedGame case below, which is also a frame taller than the game
    // -- is how far its aspect sits under the game's. The window that model
    // was measured on, 730 x 1389, is 0.037 under; the long display is 0.101
    // under, an 18:9 phone would be 0.0625 and a 20:9 one 0.1125. 0.05 sits
    // 26 % over the window and 20 % under the nearest display. A phone
    // between 16:9 and 17.5:9, which this would take for a window, is one
    // nobody has made since 2017; the size alone cannot tell the two apart
    // any closer than that, and the app never sees a window at all.
    const val COVER_GAP = 0.05

    // The "Stage Failed..." banner: big red letters across the upper third.
    // It appears when the character dies, the stage restarts by itself, and
    // nothing needs doing about it -- except that it stays until something
    // is clicked, and ANY click dismisses it. A tap on the auto button while
    // it is up is swallowed by the banner and the press is lost.
    //
    // Measured, share of saturated red in the band 0.10 to 0.22 of the
    // height:
    //
    //   the banner (and dimmed by a dialog on top of it)   0.0584
    //   the main screen, its red notification badges       0.0053
    //   every other frame there is                         0.0000
    //
    // 0.02 sits between with an order of magnitude either side, and the
    // measured banner was a dimmed one, so an undimmed banner scores higher
    // still.
    val STAGE_FAILED_BAND = doubleArrayOf(0.10, 0.22)
    const val STAGE_FAILED_RED = 0.02
    val STAGE_FAILED_HUE = arrayOf(
        intArrayOf(0, 120, 120), intArrayOf(8, 255, 255),
        intArrayOf(170, 120, 120), intArrayOf(179, 255, 255))

    // Red in that band is necessary and is not sufficient, which cost a whole
    // feature a run. The stage the player was on, Binary Road, is drawn on a
    // red grid: the band measured 0.484 red, twenty-four times the
    // threshold, and the passive helper concluded the banner was up and did
    // nothing at all -- for as long as that stage lasted. Two other frames
    // say the same in the other direction: a dungeon card's artwork scores
    // 0.069 and an orange cave 0.063, both above 0.02 and neither a banner.
    //
    // What the banner has and none of them do is a white outline around the
    // letters, which is how this game draws every headline. Measured as the
    // share of the band that is red with white within two pixels of it:
    //
    //   the banner                          0.0236
    //   the red stage that broke it         0.0037
    //   a dungeon card's red artwork        0.0001
    //   an orange cave                      0.0000
    //   every ordinary main screen          0.0000 to 0.0002
    //
    // 0.010 sits a factor of two under the banner and a factor of nearly
    // three over the worst impostor. And the two ways of being wrong do not
    // cost the same: missing a banner costs one tap, which is what dismisses
    // it anyway, while seeing one that is not there stops everything until
    // the screen changes. So this test is meant to lean towards "not a
    // banner".
    val STAGE_FAILED_WHITE = Hsv(intArrayOf(0, 0, 200), intArrayOf(179, 60, 255))
    const val STAGE_FAILED_HALO = 0.010

    // The auto button on the main game screen: a blue disc with an A between
    // two arrows, below and left of the middle. Pressing it makes the game
    // spend the tickets by itself.
    //
    // Measured on a real frame, 805 x 1390. In the band searched below, the
    // blue mask finds four things, and only one of them is this button:
    //
    //   the panel edge        241 x 12 px            far too wide
    //   the sun icon           64 x 62, fill 0.62    a rounded square
    //   THE AUTO BUTTON        43 x 43, fill 0.72    a disc, 0.053 of the width
    //   a bar right of it      49 x 17               too flat
    //
    // So width and roundness carry it, not position alone.
    //
    // Roundness does a second job, and this is the useful part. The game dims
    // everything behind a dialog, and a dimmed disc loses its edges out of
    // the blue range and breaks up. Measured on the same button, twice:
    //
    //   main screen clear          43 x 43, area 1331   fill 0.72
    //   a dialog open over it      42 x 42, area  754   fill 0.43
    //
    // So a button found at full fill is evidence of both things a caller
    // needs: where to tap, and that nothing is covering the screen. 0.65
    // keeps its distance from the dimmed case, which is the error that
    // matters -- failing to find the button costs nothing but a press that
    // does not happen.
    //
    // Its own blue range, a little wider than BLUE: this is an icon, not one
    // of the flat buttons BLUE was measured on, and its disc is shaded.
    val AUTO_BLUE = Hsv(intArrayOf(95, 120, 120), intArrayOf(115, 255, 255))
    val POS_AUTO = doubleArrayOf(0.361, 0.766)
    val AUTO_BAND = doubleArrayOf(0.28, 0.45, 0.72, 0.82)
    val AUTO_W = doubleArrayOf(0.040, 0.068)
    val AUTO_ASPECT = doubleArrayOf(0.80, 1.25)
    const val AUTO_FILL_MIN = 0.65

    // The home button: the globe in the middle of the bottom nav bar, and the
    // way back to the main screen from every screen that bar is drawn on.
    // That is the catch, and it is the reason go_home walks back to the list
    // first -- the bar belongs to the list side of the game and is not drawn
    // over a dungeon panel, a battle or a dialog.
    //
    // Found by its glyph, not by its position: the white wireframe globe
    // inside the disc. Measured over 49 real frames -- window frames 497 to
    // 805 wide and ADB frames of 1080 x 1920, both frame shapes of the same
    // scene -- as shares of the reference window:
    //
    //   the globe        fw 0.0473 to 0.0501   aspect 0.96 to 1.03   fill 0.42 to 0.49
    //   next widest white thing in that crop, over every frame there is:
    //     a tab's label edge          fw 0.0155
    //     a white panel across the bar, on a loading screen or with Special
    //     Summon open over the game    fw 0.125, fill 1.00
    //
    // So the width band below keeps 15 % clear of the globe at either end
    // and still leaves a factor of two either way to the nearest wrong thing,
    // and fill throws out the solid white panel that width alone would have
    // to argue with. Exactly one blob passed on each of the 49 frames, none
    // on any other.
    //
    // It does the same second job auto_button does: the game dims what is
    // behind a dialog, and the dimmed globe leaves the white range entirely
    // -- measured on a frame with the leave prompt up, no white blob at all
    // in that crop. So a globe found is evidence of both things a caller
    // needs, where to tap and that nothing is covering it.
    //
    // The button's own centre measures 0.481, 0.945, which is 0.104 to the
    // right of NAV_DUNGEON and a little higher, as the bar is drawn. It is
    // written down here for the record only: what gets tapped is the glyph
    // that was found.
    val HOME_BAND = doubleArrayOf(0.41, 0.555, 0.905, 0.985)
    val HOME_WHITE = Hsv(intArrayOf(0, 0, 200), intArrayOf(179, 60, 255))
    val HOME_W = doubleArrayOf(0.038, 0.062)
    val HOME_ASPECT = doubleArrayOf(0.80, 1.25)
    val HOME_FILL = doubleArrayOf(0.30, 0.70)

    /** `game_rect`'s answer: left, top, width, height in this frame's pixels. */
    data class GameRect(val x0: Int, val y0: Int, val gw: Int, val gh: Int) {
        fun toOracle(): List<Any?> = listOf(x0, y0, gw, gh)
    }

    /** One of `find_buttons`' dicts: centre, width and height as fractions. */
    data class Button(val fx: Double, val fy: Double, val fw: Double, val fh: Double,
                      /**
                       * "left" or "right" when a side of the picture cut this
                       * blob, null when it did not -- and null always unless
                       * [findButtons] was asked with `edge = false`. Python
                       * puts a "cut" key in the dict only then, for the same
                       * reason: no other caller's answer may change shape.
                       * Not in [toOracle]; the oracle records what a reader
                       * answers, and no reader answers a raw blob.
                       */
                      val cut: String? = null) {
        fun toOracle(): Map<String, Any?> = mapOf("fx" to fx, "fy" to fy, "fw" to fw, "fh" to fh)
    }

    // ------------------------------------------------------------------------
    // Two frames of the same screen
    // ------------------------------------------------------------------------
    /**
     * `dungeon.MOVING_SHARE` (dungeon.py:1023): "is a load still going".
     * Everything that moves on a loading screen is tiny -- a progress bar
     * advancing 2 % is 0.007 % of the frame, one more loading dot 0.029 %,
     * the small sprite animating 0.183 % -- so a test asking for 0.2 % calls
     * all of it stillness, which is how the loop once decided a perfectly
     * healthy load was a screen it could not get past.
     */
    const val MOVING_SHARE = 0.0002

    /**
     * `dungeon.CHANGED_SHARE` (dungeon.py:1026): "did my tap do anything".
     * A blunt one, so that a blinking icon does not count as an answer.
     */
    const val CHANGED_SHARE = 0.01

    /**
     * `dungeon._same_screen` (dungeon.py:1049): did nothing change?
     *
     * The threshold is the **caller's** decision and is passed in every
     * time: "is anything moving at all" and "did my click achieve
     * something" are different questions with different numbers, and
     * sharing one between them has caused three separate bugs (NOTES.md,
     * "A threshold answers one question"). Two frames that cannot be
     * compared are not the same screen, as Python's shape test says.
     *
     * The computation is [Director.motion]'s -- absdiff on grey, strictly
     * over 30, as a share of the picture -- because that is the same line
     * of Python, written once. It lives here because [SummonSkill] and
     * [DungeonSkill] each carried their own copy of it and of the share
     * (PLAN_ANDROID_APP.md 4, the merge list).
     */
    fun sameScreen(before: Mat?, after: Mat?, minShare: Double = CHANGED_SHARE): Boolean {
        val share = Director.motion(before, after) ?: return false
        return share < minShare
    }

    // ------------------------------------------------------------------------

    /**
     * The window a game area of this size and place would sit in.
     *
     * Returned rather than the game area itself so that one vocabulary
     * covers both sources. See GAME_IN_WINDOW for why that vocabulary is the
     * window and not the game.
     */
    private fun windowSpace(x0: Int, y0: Int, gw: Int, gh: Int): GameRect =
        windowSpace(x0.toDouble(), y0.toDouble(), gw.toDouble(), gh.toDouble())

    private fun windowSpace(x0: Double, y0: Double, gw: Double, gh: Double): GameRect {
        val (fx0, fy0, fw, fh) = GAME_IN_WINDOW.toList()
        val ww = gw / fw
        val wh = gh / fh
        return GameRect(Py.roundInt(x0 - ww * fx0), Py.roundInt(y0 - wh * fy0),
                        Py.roundInt(ww), Py.roundInt(wh))
    }

    /**
     * Reference rect for every fraction in this file, in this frame's pixels.
     *
     * Two kinds of frame arrive here and both have to answer with the same
     * vocabulary, because the same constants are read off both.
     *
     * A frame from ADB is the game area and nothing else, so what comes back
     * is wider than the image and starts above and left of it -- the window
     * the game would sit in. Checked against the auto button: found at
     * 0.3778 and 0.7604 of a device frame, which is 0.3607 and 0.7662 of that
     * window, against the measured constant 0.361 and 0.766.
     *
     * A window frame has the chrome in it. The game area is found by taking
     * WINDOW_CHROME off, and is then stretched back to the reference window,
     * so that a window of any size answers with the numbers the reference
     * window would have given. At 805 x 1390 that is exactly the frame as it
     * comes, which is what every constant here was measured against.
     *
     * A long display -- a phone at 19.5:9, LDPlayer at 1080 x 2340 -- is
     * covered by the game: the canvas scaled to the height, centred, the
     * sides cut (COVER_GAP). Checked against the 1920 answers on the same
     * screens: auto_button, home_button, title_bar, the Explore readers,
     * the quest card and the summon button meet them to 0.0005.
     *
     * Anything whose shape does not fit the measured layout is used as it
     * comes. That is the old behaviour, and it is the right answer for a
     * picture this function has no business making assumptions about.
     */
    fun gameRectWh(w: Int, h: Int): GameRect {
        if (abs(w / h.toDouble() - DEVICE_ASPECT) <= DEVICE_ASPECT_TOL) {
            return windowSpace(0, 0, w, h)
        }
        val (left, top, right, bottom) = WINDOW_CHROME.toList()
        val gw = w - left - right
        val gh = h - top - bottom
        if (gw > 0 && gh > 0 && abs(gw / gh.toDouble() - DEVICE_ASPECT) <= CHROME_ASPECT_TOL) {
            return windowSpace(left, top, gw, gh)
        }
        if (h > 0 && DEVICE_ASPECT - w / h.toDouble() > COVER_GAP) {
            val covered = coveredGame(w, h)
            return windowSpace(covered[0], covered[1], covered[2], covered[3])
        }
        val fitted = fittedGame(w, h)
        if (fitted != null) {
            return windowSpace(fitted[0], fitted[1], fitted[2], fitted[3])
        }
        return GameRect(0, 0, w, h)
    }

    /**
     * The game's canvas on a display taller than it: scaled to the height,
     * centred, wider than the picture. At 1080 x 2340 that is -118, 0,
     * 1316 x 2340 (COVER_GAP).
     */
    private fun coveredGame(w: Int, h: Int): IntArray {
        val gw = h * DEVICE_ASPECT
        return intArrayOf(Py.roundInt((w - gw) / 2.0), 0, Py.roundInt(gw), h)
    }

    /**
     * Reference rect for every fraction in this file, in this frame's pixels.
     *
     * See gameRectWh: everything here needs only the frame's width and
     * height, and this is the caller that has a picture to take them from.
     */
    fun gameRect(img: Mat): GameRect = gameRectWh(img.cols(), img.rows())

    /**
     * The game area in a window that has no sidebar, or null.
     *
     * The layout above is LDPlayer with its sidebar out: tab bar on top,
     * sidebar on the right, and the game filling the rest exactly. Without
     * the sidebar that arithmetic no longer comes out at the game's aspect,
     * and what used to happen then was that the whole image was used as it
     * came. That works while the window is roughly the shape of the game and
     * drifts the moment it is not: the game is then letterboxed inside the
     * window, and every fraction in this file slides by however wide the bars
     * are.
     *
     * It cost the passive helper a session. Resized to 730 x 1389 -- an
     * aspect of 0.5256 where the game wants 0.5625 -- the bars came to 25
     * pixels top and bottom, the hologram counter slid out of the top of its
     * crop, and every round for two minutes reported that it could not read
     * a number that was plainly on the screen.
     *
     * So: take the tab bar off the top, fit the game into what is left
     * keeping its own aspect, and centre it. Checked against the auto
     * button, whose place in the game is known, over eight frames at five
     * window shapes:
     *
     *     window        this model    top-aligned instead
     *     765 x 1390    0 / +2 px     0 / +2 px
     *     657 x 1198    0 / +2 px     0 / +2 px
     *     651 x 1195    0 / -0 px     0 / -0 px
     *     573 x 1056    0 / -0 px     0 / -0 px
     *     497 x  914    0 / +2 px     0 / +2 px
     *     730 x 1389    0 / +1 px     0 / -25 px
     *
     * The last row is the one that decides it: where the window is the wrong
     * shape the bars are real, and they are shared top and bottom.
     */
    private fun fittedGame(w: Int, h: Int): IntArray? {
        val space = h - WINDOW_CHROME[1]
        if (space <= 0 || w <= 0) return null
        val gw = minOf(w.toDouble(), space * DEVICE_ASPECT)
        val gh = gw / DEVICE_ASPECT
        // A picture this model would read as mostly border is not a window
        // with a game in it, and is better used as it comes.
        if (gw < 0.5 * w || gh < 0.5 * h) return null
        return intArrayOf(Py.roundInt((w - gw) / 2.0),
                          Py.roundInt(WINDOW_CHROME[1] + (space - gh) / 2.0),
                          Py.roundInt(gw), Py.roundInt(gh))
    }

    internal fun hsv(img: Mat): Mat {
        val out = Mat()
        Imgproc.cvtColor(img, out, Imgproc.COLOR_BGR2HSV)
        return out
    }

    internal fun inRange(hsv: Mat, colour: Hsv): Mat {
        val mask = Mat()
        Core.inRange(hsv, colour.lo, colour.hi, mask)
        return mask
    }

    private fun ones(rows: Int, cols: Int): Mat = Mat.ones(rows, cols, CvType.CV_8U)

    /**
     * `cv2.connectedComponentsWithStats(mask, 8)`: the count and the stats
     * table, one row per label, columns x, y, w, h, area as CC_STAT_* says.
     */
    internal fun components(mask: Mat): Pair<Int, Array<IntArray>> {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
        val table = Array(n) { i ->
            val row = IntArray(5)
            stats.get(i, 0, row)
            row
        }
        labels.release(); stats.release(); centroids.release()
        return n to table
    }

    /** Find wide, strongly coloured buttons. */
    /**
     * Find wide, strongly coloured buttons.
     *
     * [edge] is the fence below: a blob that touches a side of the picture
     * is refused, because its width is then the picture's and not the
     * thing's. A reader whose thing can reach a side and which has other
     * evidence for the width -- [Summon.generalTab], whose two tabs are the
     * same size -- asks with `edge = false` and gets those blobs too, each
     * marked "left" or "right" under [Button.cut].
     */
    fun findButtons(img: Mat, colour: Hsv, minArea: Double = 0.004,
                    minY: Double = BUTTON_MIN_Y, edge: Boolean = true): List<Button> {
        val (x0, y0, gw, gh) = gameRect(img)
        val hsv = hsv(img)
        val raw = inRange(hsv, colour)
        val mask = Mat()
        Imgproc.morphologyEx(raw, mask, Imgproc.MORPH_CLOSE, ones(5, 15))
        val (n, stats) = components(mask)
        hsv.release(); raw.release(); mask.release()
        val out = ArrayList<Button>()
        for (i in 1 until n) {
            val (x, y, w, h, area) = stats[i].toList()
            if (area < minArea * gw * gh) continue
            // The ad button, at about 7 to 1, is clearly wider than the
            // others. With the old upper bound of 6 it fell through and the
            // bot treated the dialog as unknown.
            val ratio = w / maxOf(h, 1).toDouble()
            if (!(1.2 < ratio && ratio < 9.0)) continue
            val cut = when {
                x <= BUTTON_EDGE_PX -> "left"
                x + w >= img.cols() - BUTTON_EDGE_PX -> "right"
                else -> null
            }
            if (cut != null && edge) {
                continue                  // cut by the picture, BUTTON_EDGE_PX
            }
            // x0 comes off fx as y0 comes off fy. It did not until
            // 2026-09-20, which cost nothing on the 805 x 1390 window every
            // band in this file was measured on (x0 is 0 there), 0.005 on an
            // ADB frame (x0 is -6) and 0.09 on a long display (x0 is -125),
            // where Attempt, the ad button and every card fell out of their
            // bands. With it, Attempt, the ad button, Move, the Partner
            // window and General meet the 1920 answer at 2340 to 0.0004. The
            // 0.005 moved every ADB frame's fx in the oracle, and what
            // changed sides was what had been sitting within 0.001 of a band
            // edge: a Partner-page button at 0.4439 against POS_CENTER's
            // 0.44, the Claim button at 0.5955 against POS_ATTEMPT's 0.60, a
            // main-screen blob at 0.349 against POPUP_OK_FX's 0.35 -- on
            // screens the director never reads through those bands, which
            // is why it moved on one frame of 745.
            val fx = (x + w / 2.0 - x0) / gw
            val fy = (y + h / 2.0 - y0) / gh
            if (fy < minY) continue
            out.add(Button(fx, fy, w / gw.toDouble(), h / gh.toDouble(),
                           if (edge) null else cut))
        }
        // Python's list.sort is stable, and so is sortedBy.
        return out.sortedBy { it.fy }
    }

    fun near(value: Double, target: Double, tol: Double = POS_TOLERANCE): Boolean =
        abs(value - target) <= tol

    /**
     * Vertical centres of the list entries, from top to bottom, each with
     * the card's height (`list_cards(img, with_size=True)`).
     *
     * Detected live instead of read from fixed positions. That way it fits
     * any scroll position and any window size. The height is needed because
     * the first entry is a taller banner and a fixed offset from the centre
     * would not hold for it.
     */
    fun listCardsWithSize(img: Mat): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (b in findButtons(img, BLUE, minArea = 0.002, minY = 0.0)) {
            if (!near(b.fx, CARD_X, CARD_X_TOL)) continue
            if (!(CARD_W_MIN <= b.fw && b.fw <= CARD_W_MAX)) continue
            if (b.fh > CARD_H_MAX) continue
            out.add(b.fy to b.fh)
        }
        // A sort of tuples: by fy, then by fh.
        return out.sortedWith(compareBy({ it.first }, { it.second }))
    }

    /** `list_cards(img)`: the vertical centres alone. */
    fun listCards(img: Mat): List<Double> = listCardsWithSize(img).map { it.first }

    /**
     * Crop containing a list card's counters.
     *
     * The reference point is the card's bottom edge, not its centre. Cards
     * vary in height, the first banner is noticeably taller than the rest.
     * With a fixed offset from the centre, the crop missed there.
     *
     * Null where numpy would give an empty array.
     */
    fun badgeCrop(img: Mat, fy: Double, fh: Double? = null): Mat? {
        val (x0, y0, gw, gh) = gameRect(img)
        // `fh if fh else 0.118`: a height of 0.0 takes the default as well.
        val height = if (fh != null && fh != 0.0) fh else 0.118
        val bottomList = fy + height / 2.0
        val y = Py.int(y0 + (bottomList - BADGE_BOTTOM_OFF - BADGE_H) * gh)
        val h = Py.int(BADGE_H * gh)
        val x = Py.int(x0 + BADGE_X * gw)
        val w = Py.int(BADGE_W * gw)
        return Py.crop(img, maxOf(0, y), y + h, maxOf(0, x), x + w)
    }

    /**
     * `badge_glyphs`' pair: the white mask of the enlarged badge, and the
     * characters in it as stats rows (x, y, w, h, area), left to right.
     * `(None, [])` in Python is a null mask and no glyphs here.
     */
    class Glyphs(val mask: Mat?, val glyphs: List<IntArray>)

    /** Characters in the ticket badge, from left to right. */
    fun badgeGlyphs(img: Mat, fy: Double, fh: Double? = null, scale: Int = 4): Glyphs {
        val crop = badgeCrop(img, fy, fh) ?: return Glyphs(null, emptyList())
        return glyphsIn(crop, GLYPH_MIN_H, scale)
    }

    /**
     * The white characters of a counter crop, left to right: `badge_glyphs`
     * from the crop on, so that the panel's counter ([panelGlyphs]) is read
     * by the very lines the card's is, with its own floor for the height.
     */
    private fun glyphsIn(crop: Mat, minH: Double, scale: Int): Glyphs {
        val big = Mat()
        Imgproc.resize(crop, big, Size((crop.cols() * scale).toDouble(), (crop.rows() * scale).toDouble()),
                       0.0, 0.0, Imgproc.INTER_CUBIC)
        val gray = Mat()
        Imgproc.cvtColor(big, gray, Imgproc.COLOR_BGR2GRAY)
        val mask = Mat()
        Core.inRange(gray, Scalar(200.0), Scalar(255.0), mask)
        big.release(); gray.release()
        val (n, stats) = components(mask)
        val out = ArrayList<IntArray>()
        for (i in 1 until n) {
            val s = stats[i]
            val ratio = s[2] / maxOf(s[3], 1).toDouble()
            if (s[4] > 200 && 0.15 < ratio && ratio < 1.2 &&
                s[3] > minH * mask.rows()) {
                out.add(s)
            }
        }
        // Python's list.sort is stable, and so is sortedBy.
        return Glyphs(mask, out.sortedBy { it[0] })
    }

    /**
     * Characters split into counters, by the gap between them.
     *
     * A counter reads n/2 and its characters sit close together; a clear gap
     * separates one counter from the next.
     */
    fun groupGlyphs(glyphs: List<IntArray>): List<List<IntArray>> {
        if (glyphs.isEmpty()) return emptyList()
        val groups = arrayListOf(arrayListOf(glyphs[0]))
        for (i in 1 until glyphs.size) {
            val before = glyphs[i - 1]
            val now = glyphs[i]
            val gap = now[0] - (before[0] + before[2])
            if (gap > 3 * before[2]) {
                groups.add(arrayListOf(now))
            } else {
                groups.last().add(now)
            }
        }
        return groups
    }

    /**
     * The digits of one counter, or null if this group is not a counter.
     *
     * The proof is the slash: narrow, and taller than the digits around it.
     * It is what tells a counter from the rest of the artwork in the crop --
     * the Apocalymon card carries a medal and the word "Rose" in the same
     * strip, and its letters are the height of a digit. They have no slash,
     * so they are not a counter.
     *
     * Only what stands to the left of the slash is returned. That is the
     * count; to the right is the daily allowance, which is always two.
     */
    fun counterDigits(group: List<IntArray>): List<IntArray>? {
        if (group.size < 2) return null
        // max() gives the first of equals, and so does maxBy.
        val tallest = group.maxBy { it[3] }
        // `g is not tallest`: by identity, not by value.
        val others = group.filter { it !== tallest }.map { it[3] }
        if (others.isEmpty()) return null
        val median = others.sorted()[others.size / 2]
        if (tallest[3] < SLASH_MIN_RATIO * median) return null
        if (tallest[2] / maxOf(tallest[3], 1).toDouble() > SLASH_MAX_WH) return null
        val left = group.filter { it[0] + it[2] <= tallest[0] }
        return left.ifEmpty { null }
    }

    /** `mask[y:y+h, x:x+w]` for one stats row. */
    private fun glyphOf(mask: Mat, stat: IntArray): Mat? =
        Py.crop(mask, stat[1], stat[1] + stat[3], stat[0], stat[0] + stat[2])

    /** A single-channel uint8 Mat as unsigned values, row by row. */
    private fun bytesOf(m: Mat): IntArray {
        val c = if (m.isContinuous) m else m.clone()
        val raw = ByteArray(c.rows() * c.cols())
        c.get(0, 0, raw)
        if (c !== m) c.release()
        return IntArray(raw.size) { raw[it].toInt() and 0xff }
    }

    /** Is this digit a zero? Measured by the hole in the middle. */
    fun isZeroDigit(mask: Mat, stat: IntArray): Boolean {
        val glyph = glyphOf(mask, stat) ?: return false
        val small = Mat()
        // cv2.resize's default interpolation is INTER_LINEAR.
        Imgproc.resize(glyph, small, Size(20.0, 28.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val px = bytesOf(small)
        small.release()
        // (glyph[9:19, 7:13] > 127).mean()
        var ink = 0
        for (r in 9 until 19) for (c in 7 until 13) if (px[r * 20 + c] > 127) ink += 1
        return ink / 60.0 <= ZERO_HOLE_MAX
    }

    /**
     * The glyph on a fixed grid, so its size no longer matters: DIGIT_GRID
     * is (width, height), so the answer is 16 rows of 12.
     */
    fun digitBitmap(mask: Mat, stat: IntArray): Array<BooleanArray>? {
        val glyph = glyphOf(mask, stat) ?: return null
        val (gw, gh) = DIGIT_GRID.toList()
        val small = Mat()
        Imgproc.resize(glyph, small, Size(gw.toDouble(), gh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val px = bytesOf(small)
        small.release()
        return Array(gh) { r -> BooleanArray(gw) { c -> px[r * gw + c] > 127 } }
    }

    /** Enclosed background areas, and how far down each one sits. */
    private fun holes(bits: Array<BooleanArray>): List<Double> {
        // np.pad((~bits).astype(np.uint8), 1, constant_values=1)
        val rows = bits.size + 2
        val cols = bits[0].size + 2
        val flat = ByteArray(rows * cols) { 1 }
        for (r in bits.indices) for (c in bits[r].indices) {
            flat[(r + 1) * cols + c + 1] = if (bits[r][c]) 0 else 1
        }
        val padded = Mat(rows, cols, CvType.CV_8U)
        padded.put(0, 0, flat)
        val labels = Mat()
        // The background is 8-connected, so a hole counts only where the ink
        // around it closes without a diagonal gap. dungeon.py read
        // `connectedComponents(padded, 4)` for a year, and that 4 landed in
        // the binding's `labels` argument, not in `connectivity`: it ran with
        // the default 8 the whole time, and 8 is what every digit threshold
        // above was measured against. Measured the other way round on
        // 2026-09-20, over all 301 digit glyphs on the 153 cards of the
        // corpus: with 4 not one real digit changes its holes; the only
        // glyphs that do are two pieces of the nav bar's globe on the blob
        // CARD_H_MAX refuses now (corpus/passive/unclear_214821.png, 925
        // against 829). dungeon.py writes `connectivity=8` out now.
        val count = Imgproc.connectedComponents(padded, labels, 8)
        val lab = IntArray(rows * cols)
        labels.get(0, 0, lab)
        padded.release(); labels.release()
        val found = ArrayList<Double>()
        for (k in 1 until count) {
            var sumY = 0L
            var n = 0
            var outside = false
            for (r in 0 until rows) for (c in 0 until cols) {
                if (lab[r * cols + c] != k) continue
                sumY += r
                n += 1
                if (r == 0 || c == 0 || r == rows - 1 || c == cols - 1) outside = true
            }
            if (outside) continue                  // that one is the world outside
            found.add(sumY.toDouble() / n / rows)
        }
        return found
    }

    /** `bits[r0:r1, c0:c1].mean()`, with numpy's slice rules. */
    private fun mean(bits: Array<BooleanArray>, r0: Int, r1: Int, c0: Int, c1: Int): Double {
        val (a, b) = Py.slice(r0, r1, bits.size).let { it[0] to it[1] }
        val (x, y) = Py.slice(c0, c1, bits[0].size).let { it[0] to it[1] }
        var ink = 0
        for (r in a until b) for (c in x until y) if (bits[r][c]) ink += 1
        return ink.toDouble() / ((b - a) * (y - x))
    }

    /**
     * Which digit this is, or null if it does not look like one.
     *
     * Holes first, because they split the ten into three groups that no
     * amount of ink measuring separates as cleanly.
     */
    fun readDigit(mask: Mat, stat: IntArray): Int? {
        val bits = digitBitmap(mask, stat) ?: return null
        val rows = bits.size
        val cols = bits[0].size
        val holes = holes(bits)
        val bottom = mean(bits, -2, rows, 0, cols)
        val right = mean(bits, 0, rows, -4, cols)

        if (holes.size >= 2) return 8
        if (holes.size == 1) {
            if (bottom < D4_BOTTOM_MAX) return 4
            if (holes[0] < D9_HOLE_MAX) return 9
            if (holes[0] > D6_HOLE_MIN) return 6
            return 0
        }
        if (bottom < D7_BOTTOM_MAX) return 7
        if (right < D1_RIGHT_MAX ||
            mean(bits, D1_ROWS[0], D1_ROWS[1], -D1_RIGHT_COLS, cols) < D1_UPPER_RIGHT_MAX) {
            return 1
        }
        if (bottom >= D2_BOTTOM_MIN) return 2
        // band = bits[D53_ROWS[0]:D53_ROWS[1]]
        val left = mean(bits, D53_ROWS[0], D53_ROWS[1], 0, 4)
        val rightFlank = mean(bits, D53_ROWS[0], D53_ROWS[1], -4, cols)
        return if (left > rightFlank) 5 else 3
    }

    /**
     * The number a counter shows, or null if any digit was unreadable.
     *
     * All or nothing on purpose. Half a number is worse than no number: 4
     * read out of 46 would have the bot stop after four attempts and call the
     * card empty.
     */
    fun readCounter(mask: Mat, digits: List<IntArray>): Int? {
        if (digits.isEmpty()) return null
        var value = 0
        for (stat in digits) {
            val one = readDigit(mask, stat) ?: return null
            value = value * 10 + one
        }
        return value
    }

    /**
     * `card_counters`' pair: the mask, and one digit list per counter.
     * `(None, [])` in Python is a null mask and no counters here.
     */
    class Counters(val mask: Mat?, val counters: List<List<IntArray>>)

    /**
     * The counters on a list card: the mask, and one digit list per counter.
     *
     * First the tickets, then the ads if the card has them. Apocalymon has
     * only the one.
     */
    fun cardCounters(img: Mat, fy: Double, fh: Double? = null): Counters {
        val (mask, glyphs) = badgeGlyphs(img, fy, fh).let { it.mask to it.glyphs }
        if (mask == null || glyphs.isEmpty()) return Counters(null, emptyList())
        val found = groupGlyphs(counterGlyphs(glyphs)).mapNotNull { counterDigits(it) }
        return Counters(mask, found)
    }

    /** What may stand in a counter at all: no square glyph (COUNTER_GLYPH_WH_MAX). */
    private fun counterGlyphs(glyphs: List<IntArray>): List<IntArray> =
        glyphs.filter { it[2] / maxOf(it[3], 1).toDouble() <= COUNTER_GLYPH_WH_MAX }

    /** `card_budget`'s dict. */
    data class Budget(val tickets: Int?, val ads: Int?, val total: Int?) {
        fun toOracle(): Map<String, Any?> = mapOf("tickets" to tickets, "ads" to ads, "total" to total)
    }

    /**
     * How many attempts this card can still yield today.
     *
     * Both counters count down, and both say what is left. 46/2 is
     * forty-six tickets in hand, with two more arriving each day; 0/2 on the
     * film symbol is no ads left today, out of the two a day gives.
     *
     * The film symbol is drawn only while the ticket counter reads 0/2.
     * Confirmed both ways: it appears once tickets first run out, and it
     * disappears again the moment tickets are above zero for any reason --
     * including a repurchase, and regardless of how many ads the symbol had
     * been showing right before the purchase. So a card with tickets left
     * carries one counter, full stop, and that says nothing whatever about
     * its ads: reading the missing symbol as "no ads" is a conclusion the
     * card does not support, and it would have the bot stop as the last
     * ticket went and walk away from two free attempts. Ads are unknown
     * whenever tickets are not at zero.
     *
     * Hence the three shapes an answer can have:
     *
     *   tickets  > 0, (no film symbol, it is hidden while this holds) ->
     *                 ads unknown, total unknown, at least that many tickets
     *   tickets == 0, film symbol N  -> N, and that is all there is
     *   tickets == 0, no film symbol -> 0, this card has no ads to give
     *
     * A total of null means "play it, and find out inside". Only a total of
     * 0 lets a card be skipped without opening it.
     */
    fun cardBudget(img: Mat, fy: Double, fh: Double? = null): Budget {
        val found = cardCounters(img, fy, fh)
        val mask = found.mask
        val counters = found.counters
        if (counters.isEmpty() || mask == null) return Budget(null, null, null)

        val tickets = readCounter(mask, counters[0])
        val ads = if (counters.size > 1) readCounter(mask, counters[1]) else null

        val total = if (tickets == null) {
            null
        } else if (ads != null) {
            // Observed only at tickets == 0, per the rule above -- the symbol
            // hides itself otherwise -- but added rather than assumed to be
            // zero, in case that ever stops holding.
            tickets + ads
        } else if (tickets == 0) {
            // Tickets gone and still no film symbol: this card has no ads.
            0
        } else {
            // Tickets left, so the symbol is hidden and the ads behind it
            // cannot be counted. Unknown, not zero.
            null
        }
        return Budget(tickets, ads, total)
    }

    /**
     * Does this list card still have attempts left?
     *
     * Reads whether the ticket counter's leading digit is a zero. Three
     * outcomes: tickets present means play, tickets at 0 with no ad counter
     * means safe to skip, tickets at 0 with an ad counter means unclear and
     * gets tried. The third case costs one open-and-close, after which the
     * bot remembers the outcome.
     *
     * Returns null if nothing could be read. The card is then played to be
     * safe, not skipped.
     */
    fun cardHasAttempts(img: Mat, fy: Double, fh: Double? = null): Boolean? {
        val found = cardCounters(img, fy, fh)
        val mask = found.mask
        val counters = found.counters
        if (counters.isEmpty() || mask == null) return null
        val tickets = counters[0]
        if (!isZeroDigit(mask, tickets[0])) return true
        return if (counters.size > 1) null else false
    }

    // ------------------------------------------------------------------------
    // Inside the panel: its own counter, and the sheet a ticket raises
    // ------------------------------------------------------------------------
    // The panel carries the card's ticket counter too, "n/2" over its buttons,
    // and it is the one witness of a ticket spent that is there on both sides
    // of a run: a lost run leaves it standing, a won run and Clear Previous
    // Difficulty take one off. Measured on LDPlayer 2026-09-23 (corpus/dungeon/
    // panel_tickets_*): the digits stand 0.054 of the height over the centre
    // of Attempt or of the ad button, fy 0.6475 to 0.6606 against a button at
    // 0.7083, in the card badge's own font -- so it is read by the card's own
    // lines, from a crop hung off the button and not off a place on the
    // screen: the runner's result dialog stands 0.013 lower on a phone than
    // on LDPlayer, and a strip measured from the top slid off its plate
    // (Runner.PLATE_ABOVE_QUIT). The crop is as tall as BADGE_H and centred
    // on the digits; it starts right of the ticket icon, fx 0.39 to 0.42,
    // which is yellow on Digifactory (COUNTER_GLYPH_WH_MAX).
    const val PANEL_COUNTER_TOP = 0.075
    const val PANEL_COUNTER_BOTTOM = 0.035
    val PANEL_COUNTER_X = doubleArrayOf(0.43, 0.60)

    // The height floor is the panel's own, not GLYPH_MIN_H. Apocalymon Wall
    // writes its counter smaller, over a centred Attempt: its digits measure
    // 0.199 of the crop at 1920 and 0.2005 at 2340 (corpus/tall/
    // dungeon_panel_*), on either side of 0.20, so the same panel read 2 on
    // the long display and nothing on the short one. Every other counter's
    // digits measure 0.269 to 0.282. Lowered to 0.12, not one answer moved on
    // the 773 frames of the corpus or on the 626 frames of the three recorded
    // runs of 2026-09-23 but those two; 0.15 sits a quarter under Apocalymon
    // and keeps the rest of the noise out of the groups.
    const val PANEL_GLYPH_MIN_H = 0.15

    /** The panel counter's characters, left to right. See PANEL_COUNTER_TOP. */
    fun panelGlyphs(img: Mat, button: Button, scale: Int = 4): Glyphs {
        val (x0, y0, gw, gh) = gameRect(img)
        val y = Py.int(y0 + (button.fy - PANEL_COUNTER_TOP) * gh)
        val h = Py.int((PANEL_COUNTER_TOP - PANEL_COUNTER_BOTTOM) * gh)
        val x = Py.int(x0 + PANEL_COUNTER_X[0] * gw)
        val w = Py.int((PANEL_COUNTER_X[1] - PANEL_COUNTER_X[0]) * gw)
        val crop = Py.crop(img, maxOf(0, y), y + h, maxOf(0, x), x + w) ?: return Glyphs(null, emptyList())
        return glyphsIn(crop, PANEL_GLYPH_MIN_H, scale)
    }

    /**
     * The tickets the open panel says are left: the number left of the slash
     * of the counter over [button] -- `Recognition.attempt`, or `.ad` on a
     * panel at 0/2 -- or null. The slash is the proof it is a counter, as on
     * the card. Null too on a panel that is fading in or out: the game dims
     * it and the digits leave the white mask, which is the answer a caller
     * wants from a panel that is not standing yet. Over the three recorded
     * runs (626 frames) it read the right number on every standing panel and
     * nothing in the battle, on the loading screens or under the Reward
     * sheet.
     */
    fun panelTickets(img: Mat, button: Button): Int? {
        val found = panelGlyphs(img, button)
        return firstCounter(found)
    }

    // Network Defense Ops is the party dungeon, and its panel carries no
    // counter over its buttons: the "n/2" stands above the panel, at the top
    // right beside a purple ticket, and the crop hung from Attempt lands on
    // the party's names. Live on LDPlayer 2026-09-23 that was two runs
    // unreadable and the fully automatic chain parked. Measured on the two
    // panels there are (corpus/dungeon/panel_ad_only_netdef_120145, 0/2, and
    // panel_party_netdef_155153, 1/2): the digits stand at fx 0.764 to 0.805
    // and fy 0.163 to 0.177 of the game on both, the ad-only panel and the
    // party one alike, 0.011 tall like the panel counter's -- so the crop is
    // the panel counter's height, centred on them, and read by the same
    // lines. Not hung from a button: the party panel's Attempt stands at
    // 0.57 and the ad-only panel's ad button at 0.79, and the counter does not
    // move with either.
    //
    // A hazard, not a finding: the overlay's plate at its default place
    // (Dot.DEFAULT_FY 0.1555) covers the top third of these digits, and the
    // masked frame reads nothing on both panels; at 0.0609 (where this
    // LDPlayer's dot stands), 0.10 and 0.12 both read. A player who has not
    // moved the dot still gets the list's badge after a run that ends on the
    // list (DungeonSkill.listCounter), and nothing on the panel.
    val HEADER_COUNTER = doubleArrayOf(0.74, 0.86, 0.150, 0.190)

    /** The party panel's counter, above the panel, or null. See HEADER_COUNTER. */
    fun headerTickets(img: Mat): Int? {
        val (x0, y0, gw, gh) = gameRect(img)
        val b = HEADER_COUNTER
        val crop = Py.crop(img, maxOf(0, Py.int(y0 + b[2] * gh)), Py.int(y0 + b[3] * gh),
                           maxOf(0, Py.int(x0 + b[0] * gw)), Py.int(x0 + b[1] * gw)) ?: return null
        return firstCounter(glyphsIn(crop, PANEL_GLYPH_MIN_H, 4))
    }

    /** The first counter among [found]'s characters, read, or null. */
    private fun firstCounter(found: Glyphs): Int? {
        val mask = found.mask ?: return null
        val counters = groupGlyphs(counterGlyphs(found.glyphs)).mapNotNull { counterDigits(it) }
        if (counters.isEmpty()) return null
        return readCounter(mask, counters[0])
    }

    // The "Reward -- Tap to close" sheet. A won run raises it over the battle,
    // Clear Previous Difficulty over the dimmed panel, and it stands until
    // something is tapped (60 s watched). It is the game's one widget for every
    // reward -- the runner's sheet (Runner.rewardOverlay), the Tower's, the
    // quest's -- a band of translucent blue across the whole width with
    // "Tap to close" in white under it.
    //
    // Runner.rewardOverlay reads it from the blob of that blue and answers on
    // one of the three dungeon sheets: its band top was measured on the
    // runner's, 0.21 to 0.22, and over a dungeon the title's glow and the
    // dimmed Attempt join the blob or not (tops 0.255 and 0.330, bottoms 0.685
    // to 0.772). So this asks the rows the band always covers, fy 0.34 to
    // 0.66, and the share of them in the sheet's blue at the sheet's V
    // (Runner.REWARD_BLUE, V inside Runner.REWARD_V). Measured over the corpus
    // and the three recorded runs:
    //
    //                                                   band     words
    //   the dungeon's three sheets, the Tower's        0.775 - 0.919   0.051 - 0.141
    //   the runner's three sheets                       0.621 - 0.656   0.129 - 0.139
    //   the Events window                               0.567 - 0.603   0.000
    //   Metal Sea's sheet, two rows of ten rewards      0.408 - 0.409   0.071 - 0.119
    //   Partner windows, the Crest summon               0.343 - 0.362   0.000
    //   a quest's sheet over the bright main screen     0.324           0.126
    //   everything else with words on that line         0.213 and under
    //
    // The share alone has no room between the runner's sheet and the Events
    // window, and the words are what they do not share: the "words" column
    // is the share of the "Tap to close" box at grey 200 and over. 0.45 sits
    // 27 % under the runner's sheet and 39 % over the quest's, the nearest
    // thing below that has words at all. At 200 and not the runner's 230,
    // because the words fade in: on the Digifactory sheet after Clear Previous
    // Difficulty none of them had reached 230 yet. What that leaves out is a
    // sheet over a bright scene -- the quest's over the main screen, whose
    // blue the scene turns violet -- which no dungeon run shows; a sheet over
    // a won run on a bright stage is the case to measure when one is seen.
    //
    // 0.45 left out Metal Sea. Its sheet carries ten rewards in two rows,
    // and their icons stand on the band: 0.408 and 0.409 on two won runs,
    // live on LDPlayer 2026-09-23 (corpus/dungeon/reward_two_rows_metal_sea_*),
    // where the sheet was taken for the panel, the counter read under it,
    // and two runs in a row unreadable parked the fully automatic chain.
    // 0.37 is the middle between Metal Sea and the quest's sheet (0.330, the
    // nearest thing under it with words), and it moves no other frame of the
    // corpus. A sheet with a third row would stand lower still and is the
    // next thing to measure if one is seen.
    val SHEET_BLUE = Hsv(intArrayOf(100, 180, 130), intArrayOf(118, 255, 165))
    val SHEET_BAND = doubleArrayOf(0.05, 0.95, 0.34, 0.66)
    const val SHEET_SHARE_MIN = 0.37
    val SHEET_CLOSE_LINE = doubleArrayOf(0.39, 0.56, 0.808, 0.832)
    const val SHEET_CLOSE_GREY = 200.0
    const val SHEET_CLOSE_MIN = 0.02

    /** Is the Reward sheet up? See SHEET_BLUE. */
    fun rewardSheet(img: Mat): Boolean {
        val (x0, y0, gw, gh) = gameRect(img)
        fun box(b: DoubleArray): Mat? = Py.crop(img,
            maxOf(0, Py.int(y0 + b[2] * gh)), Py.int(y0 + b[3] * gh),
            maxOf(0, Py.int(x0 + b[0] * gw)), Py.int(x0 + b[1] * gw))
        val band = box(SHEET_BAND) ?: return false
        val blue = Cv.hsvMask(band, SHEET_BLUE)
        val share = Cv.share(blue)
        blue.release()
        if (share < SHEET_SHARE_MIN) return false
        val line = box(SHEET_CLOSE_LINE) ?: return false
        val grey = Cv.gray(line)
        val white = Mat()
        Core.compare(grey, Scalar(SHEET_CLOSE_GREY), white, Core.CMP_GE)
        val words = Cv.share(white)
        grey.release(); white.release()
        return words >= SHEET_CLOSE_MIN
    }

    /**
     * Which confirmation dialog is this, "beenden" (exit) or "party".
     *
     * Told apart by the left button: grey for the exit dialog, where OK
     * closes the game, pink for the party dialog, where OK is the correct
     * answer.
     *
     * "Party" has to be proved, and the proof is pink pixels. It used to be
     * enough for the sample to be saturated, which the dialog's blue interior
     * also is -- and the sample lands on that interior whenever the two
     * buttons are not symmetric about the middle of the screen. Measured on
     * a real exit dialog: OK at 0.561, Cancel at 0.383, mirror at 0.439,
     * which is the gap between them. That read as party, and the answer to
     * party is OK.
     *
     * Anything inconclusive is therefore the exit dialog. Being wrong that
     * way costs a Cancel that was not needed; being wrong the other way ends
     * the session.
     */
    fun confirmKind(img: Mat, okButton: Button): String {
        val (x0, y0, gw, gh) = gameRect(img)
        val left = 1.0 - okButton.fx
        // Wide enough to cover the button even when the mirror is off by the
        // measured 0.056, which is what happens on a dialog that is not
        // centred.
        val x = Py.int(x0 + (left - 0.09) * gw)
        val w = Py.int(0.18 * gw)
        val y = Py.int(y0 + (okButton.fy - 0.015) * gh)
        val h = Py.int(0.03 * gh)
        val patch = Py.crop(img, maxOf(0, y), y + h, maxOf(0, x), x + w) ?: return "beenden"
        val hsv = hsv(patch)
        // hue in PARTY_PINK_HUE and saturation above EXIT_CANCEL_SAT_MAX,
        // any value: on uint8 "> 100" is ">= 101".
        val pink = Mat()
        Core.inRange(hsv,
            Scalar(PARTY_PINK_HUE[0].toDouble(), (EXIT_CANCEL_SAT_MAX + 1).toDouble(), 0.0),
            Scalar(PARTY_PINK_HUE[1].toDouble(), 255.0, 255.0), pink)
        val share = Core.countNonZero(pink).toDouble() / (pink.rows() * pink.cols())
        hsv.release(); pink.release()
        return if (share >= 0.15) "party" else "beenden"
    }

    /** How many party slots are filled. Returns a count from 0 to 3. */
    fun partySlotsFilled(img: Mat): Int {
        val (x0, y0, gw, gh) = gameRect(img)
        var filled = 0
        for (fx in PARTY_SLOTS) {
            val x = Py.int(x0 + (fx - PARTY_SLOT_HALF_W) * gw)
            val w = Py.int(2 * PARTY_SLOT_HALF_W * gw)
            val y = Py.int(y0 + (PARTY_SLOT_Y - 0.05) * gh)
            val h = Py.int(0.10 * gh)
            val patch = Py.crop(img, maxOf(0, y), y + h, maxOf(0, x), x + w) ?: continue
            if (grayStd(patch) >= PARTY_SLOT_MIN_STD) filled += 1
        }
        return filled
    }

    /** numpy's `.std()` of the grayscale patch: population deviation, ddof 0. */
    private fun grayStd(patch: Mat): Double {
        val gray = Mat()
        Imgproc.cvtColor(patch, gray, Imgproc.COLOR_BGR2GRAY)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(gray, mean, std)
        val out = std.get(0, 0)[0]
        gray.release(); mean.release(); std.release()
        return out
    }

    /**
     * A pop-up's OK button, or null.
     *
     * Found by colour and rough position, not by a remembered pixel, so a
     * slightly different card still works. Meant to be narrow: a wide blue
     * button low down and near the middle.
     *
     * It is not narrow. Counted over the whole corpus (`_popup_probe.py`),
     * it answers on 115 of 773 frames and **not one of them is a pop-up**:
     * the Partner window's Move button on 12, a summon screen's own 1x/10x
     * button on 25, a battle's Give Up on 15, and the nav bar's blue hump
     * around the globe on 63 plain main screens -- all 63 ADB-shaped, none
     * of the 83 window-shaped main screens. The four overlap in every
     * number the reader tests (fx 0.354 to 0.638, fy 0.863 to 0.941, fw
     * 0.186 to 0.501), so no band drawn between them would be a fence.
     *
     * What it costs is pressAuto, which refuses while this answers and so
     * never pressed the auto button on those 63. What it does not cost is a
     * stray tap: the cold-start walks tap what this returns, and on a main
     * screen that lands on the globe, which is where they are going anyway.
     *
     * Left exactly as it is, deliberately. The two frames POPUP_OK_* was
     * measured on -- centred at 0.475, 0.905, about a third of the width --
     * are not in the corpus, so there is nothing here to measure a new
     * threshold against. The first real OK to reach `corpus/` is what
     * unblocks this.
     */
    fun popupOk(img: Mat): Button? {
        for (b in findButtons(img, BLUE, minY = POPUP_OK_Y[0])) {
            if (POPUP_OK_Y[0] <= b.fy && b.fy <= POPUP_OK_Y[1] &&
                POPUP_OK_FX[0] <= b.fx && b.fx <= POPUP_OK_FX[1] &&
                b.fw >= POPUP_OK_MIN_W) {
                return b
            }
        }
        return null
    }

    /**
     * The idle-rewards Claim button, or null.
     *
     * A wide blue button in the lower middle WITH a violet one beside it.
     * The violet half is what makes it Claim rather than the Notices
     * dialog's Campaigns tab, which is otherwise the same button in the same
     * place.
     *
     * And then how far away that violet one stands, and how tall the pair
     * is, because the Partner and Buddy windows put the same pair in the
     * same band -- see CLAIM_PAIR_GAP for the two clusters and why neither
     * number is carrying this alone.
     */
    fun claimButton(img: Mat): Button? {
        fun inBand(b: Button) = POS_CLAIM_Y[0] <= b.fy && b.fy <= POS_CLAIM_Y[1]

        val blue = findButtons(img, BLUE, minY = POS_CLAIM_Y[0]).filter { b ->
            inBand(b) && b.fw >= POS_CLAIM_MIN_W && b.fh >= CLAIM_BUTTON_FH &&
                POS_CLAIM_FX[0] <= b.fx && b.fx <= POS_CLAIM_FX[1]
        }
        val violet = findButtons(img, VIOLET, minY = POS_CLAIM_Y[0]).filter { inBand(it) }
        for (b in blue) {
            for (v in violet) {
                if (abs(v.fy - b.fy) > CLAIM_PAIR_DY || v.fx >= b.fx) continue
                val gap = b.fx - v.fx
                if (!(CLAIM_PAIR_GAP[0] <= gap && gap <= CLAIM_PAIR_GAP[1])) continue
                return b
            }
        }
        return null
    }

    /**
     * Is the red Stage Failed banner up?
     *
     * Red letters, and not the words themselves: reading those would be text
     * recognition, which this bot does without so that it works in every
     * language. What is measured is the shape of how the game draws a
     * headline -- saturated red with a white outline around it -- because
     * red alone is also a red stage, a red card and a red cave. See the
     * numbers above STAGE_FAILED_HALO.
     */
    fun stageFailed(img: Mat): Boolean {
        val (x0, y0, gw, gh) = gameRect(img)
        // Clamped, because on an ADB frame the reference rect starts above
        // and left of the image and a negative index would wrap to the far
        // edge.
        val top = maxOf(0, Py.int(y0 + STAGE_FAILED_BAND[0] * gh))
        val band = Py.crop(img, top, Py.int(y0 + STAGE_FAILED_BAND[1] * gh),
                           maxOf(0, x0), x0 + gw) ?: return false
        val hsv = hsv(band)
        val low = inRange(hsv, Hsv(STAGE_FAILED_HUE[0], STAGE_FAILED_HUE[1]))
        val high = inRange(hsv, Hsv(STAGE_FAILED_HUE[2], STAGE_FAILED_HUE[3]))
        val red = Mat()
        Core.bitwise_or(low, high, red)
        val size = (red.rows() * red.cols()).toDouble()
        try {
            if (Core.countNonZero(red) / size < STAGE_FAILED_RED) return false
            // Red letters, not a red picture: the outline is the difference.
            val white = inRange(hsv, STAGE_FAILED_WHITE)
            val near = Mat()
            Imgproc.dilate(white, near, ones(5, 5))
            val both = Mat()
            Core.bitwise_and(red, near, both)
            val halo = Core.countNonZero(both) / size
            white.release(); near.release(); both.release()
            return halo >= STAGE_FAILED_HALO
        } finally {
            hsv.release(); low.release(); high.release(); red.release()
        }
    }

    /**
     * The auto button on the main screen, or null if it is not plainly
     * there.
     *
     * Null is also the answer to "is the main screen really in front, with
     * nothing over it". Every dialog in this game is drawn across the middle
     * and covers this button, so a caller that finds it has evidence for
     * both questions at once -- where to tap, and that it is safe to.
     */
    fun autoButton(img: Mat): Button? {
        val (x0, y0, gw, gh) = gameRect(img)
        val (fx0, fx1, fy0, fy1) = AUTO_BAND.toList()
        val left = maxOf(0, Py.int(x0 + fx0 * gw))
        val top = maxOf(0, Py.int(y0 + fy0 * gh))
        val sub = Py.crop(img, top, Py.int(y0 + fy1 * gh), left, Py.int(x0 + fx1 * gw))
            ?: return null
        val hsv = hsv(sub)
        val mask = inRange(hsv, AUTO_BLUE)
        val (count, stats) = components(mask)
        hsv.release(); mask.release()
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (h == 0) continue
            val fw = w / gw.toDouble()
            if (!(AUTO_W[0] <= fw && fw <= AUTO_W[1])) continue
            val aspect = w / h.toDouble()
            if (!(AUTO_ASPECT[0] <= aspect && aspect <= AUTO_ASPECT[1])) continue
            if (area / (w * h).toDouble() < AUTO_FILL_MIN) continue
            return Button((left + x + w / 2.0 - x0) / gw,
                          (top + y + h / 2.0 - y0) / gh,
                          fw, h / gh.toDouble())
        }
        return null
    }

    /**
     * The home button in the bottom nav bar, or null if it is not plainly
     * there.
     *
     * Null also answers "is the nav bar in front and undimmed", for the same
     * reason autoButton answers it: see the numbers above HOME_BAND.
     */
    fun homeButton(img: Mat): Button? {
        val (x0, y0, gw, gh) = gameRect(img)
        val (fx0, fx1, fy0, fy1) = HOME_BAND.toList()
        // Clamped: on an ADB frame the reference rect starts above and left
        // of the image, and a negative index would wrap to the far edge.
        val left = maxOf(0, Py.int(x0 + fx0 * gw))
        val top = maxOf(0, Py.int(y0 + fy0 * gh))
        val sub = Py.crop(img, top, Py.int(y0 + fy1 * gh), left, Py.int(x0 + fx1 * gw))
            ?: return null
        val hsv = hsv(sub)
        val mask = inRange(hsv, HOME_WHITE)
        val (count, stats) = components(mask)
        hsv.release(); mask.release()
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (h == 0) continue
            val fw = w / gw.toDouble()
            if (!(HOME_W[0] <= fw && fw <= HOME_W[1])) continue
            val aspect = w / h.toDouble()
            if (!(HOME_ASPECT[0] <= aspect && aspect <= HOME_ASPECT[1])) continue
            val fill = area / (w * h).toDouble()
            if (!(HOME_FILL[0] <= fill && fill <= HOME_FILL[1])) continue
            return Button((left + x + w / 2.0 - x0) / gw,
                          (top + y + h / 2.0 - y0) / gh,
                          fw, h / gh.toDouble())
        }
        return null
    }

    /**
     * Is there a violet button at the same height, to the left of this one?
     *
     * That pair is the dungeon panel's Clear Previous Difficulty next to
     * Attempt, and no confirmation prompt has it. Measured over real frames
     * of all three prompts: none carries a violet button within
     * CLAIM_PAIR_DY of its OK, while the panel's pair sits at exactly the
     * same fy.
     */
    fun violetBeside(button: Button, violet: List<Button>): Boolean =
        violet.any { v -> abs(v.fy - button.fy) <= CLAIM_PAIR_DY && v.fx < button.fx }

    /**
     * `recognise`'s dict. Which keys it carries depends on the state, as in
     * dungeon.py: the exit prompt adds exit_ok and exit_kind and has no
     * party_voll, a battle has neither.
     */
    data class Recognition(
        val state: String,
        val attempt: Button?, val party: Button?, val ad: Button?, val clear: Button?,
        val blau: List<Button>, val violett: List<Button>, val karten: List<Double>,
        val giveup: Button?,
        val partyVoll: Int? = null,
        val exitOk: Button? = null, val exitKind: String? = null,
    ) {
        fun toOracle(): Map<String, Any?> {
            val out = linkedMapOf<String, Any?>(
                "state" to state, "attempt" to attempt?.toOracle(),
                "party" to party?.toOracle(), "ad" to ad?.toOracle(),
                "clear" to clear?.toOracle(), "blau" to blau.map { it.toOracle() },
                "violett" to violett.map { it.toOracle() }, "karten" to karten,
                "giveup" to giveup?.toOracle())
            if (state == EXIT) {
                out["exit_ok"] = exitOk?.toOracle()
                out["exit_kind"] = exitKind
            } else if (state != BATTLE) {
                out["party_voll"] = partyVoll
            }
            return out
        }
    }

    /** Which screen is visible and where clicking is allowed. */
    fun recognise(img: Mat): Recognition {
        val blue = findButtons(img, BLUE, minY = 0.0)
        val violet = findButtons(img, VIOLET, minY = 0.0)
        val cards = listCards(img)

        // A crisp auto button rules every dialog out. The game dims what is
        // behind a dialog and the dimmed disc falls under the fill floor --
        // that is the second job the button has done all along -- so a
        // screen with the button plainly on it has nothing drawn over it.
        // Measured over every frame there is (747, both frame shapes, the
        // screen inventory of PLAN_ANDROID_3_DIRECTOR.md 3.1): of the 20
        // frames this called the exit prompt, 3 carried a crisp auto button,
        // and all three are the plain main screen -- an effect over the
        // field, 0.17 wide and 0.062 to 0.064 tall, where the real OK is
        // 0.031 to 0.042. Of the 59 it called a dialog, 5 carried one, all
        // five main screens. None of the 17 real prompts and none of the
        // real panels does. A false prompt here is a Cancel tapped into the
        // field. (This veto was missing from the port and the oracle did
        // not notice until 2026-09-20: on every main screen the nav bar,
        // cut at both sides, had been the Give Up that returned BATTLE
        // before the veto was ever reached -- BUTTON_EDGE_PX.)
        val mainClear = autoButton(img) != null

        // Exit confirmation first. It has a blue button of a size and
        // position nothing else has, and a wrong click next to it would be
        // costly.
        val exitOk = blue.firstOrNull { b ->
            !mainClear &&
                near(b.fx, POS_EXIT_OK, EXIT_TOL) &&
                EXIT_Y[0] <= b.fy && b.fy <= EXIT_Y[1] &&
                EXIT_W[0] <= b.fw && b.fw <= EXIT_W[1]
        }
        // ... unless a violet button sits beside it. Two dialogs put a blue
        // button where the exit prompt's OK is looked for, and both are told
        // apart by their violet neighbour rather than by another position
        // band: the idle-rewards dialog, whose Extra Rewards would be tapped
        // by the "Cancel" that follows, and the dungeon panel while it is
        // still loading. Measured: a panel whose artwork has not arrived yet
        // draws a narrower Attempt, 0.214 against the loaded 0.251, which
        // slips inside EXIT_W -- and the mirrored Cancel then lands on Clear
        // Previous Difficulty. That happened four times in one live dungeon.
        if (exitOk != null && cards.size < LIST_MIN_CARDS && claimButton(img) == null &&
            !violetBeside(exitOk, violet)) {
            return Recognition(EXIT, null, null, null, null, blue, violet, cards, null,
                               exitOk = exitOk, exitKind = confirmKind(img, exitOk))
        }

        // The Reward sheet, before the battle and before the panel. Under the
        // sheet the panel's Attempt and the battle's Give Up are dimmed and
        // were not found on any of the three sheets in the corpus -- but that
        // is the dimming's doing, not a proof, and a sheet read as the panel
        // would be tapped on as one, or waited on as a battle for as long as
        // it stands. Not a dialog either: nothing on it but "Tap to close".
        if (rewardSheet(img)) {
            return Recognition(REWARD, null, null, null, null, blue, violet, cards, null,
                               partyVoll = 0)
        }

        // Check for battle next. During a battle there is game artwork that
        // looks very similar to a button. The Give Up button, centred at the
        // very bottom, is unambiguous by contrast.
        val giveup = blue.firstOrNull { b -> near(b.fx, POS_CENTER) && b.fy > POS_GIVEUP_Y }
        if (giveup != null) {
            return Recognition(BATTLE, null, null, null, null, blue, violet, cards, giveup)
        }

        // Only buttons at one of the known positions. Without this
        // restriction, a part of a list card at 0.42 was once mistaken for
        // Attempt.
        val usable = blue.filter { b ->
            BUTTON_MIN_Y <= b.fy && b.fy <= BUTTON_MAX_Y &&
                ATTEMPT_W[0] <= b.fw && b.fw <= ATTEMPT_W[1] &&
                (near(b.fx, POS_CENTER) || near(b.fx, POS_ATTEMPT))
        }
        val inRange = { b: Button -> BUTTON_MIN_Y <= b.fy && b.fy <= BUTTON_MAX_Y }
        val ad = violet.firstOrNull { near(it.fx, POS_CENTER) && inRange(it) }
        val clear = violet.firstOrNull { near(it.fx, POS_CLEAR) && inRange(it) }

        var attempt: Button? = null
        var party: Button? = null
        if (usable.size >= 2) {
            // two buttons, the right one is Attempt, the centred one is Find
            // a Party
            attempt = usable.firstOrNull { near(it.fx, POS_ATTEMPT) }
            party = usable.firstOrNull { near(it.fx, POS_CENTER) }
            if (attempt == null) attempt = usable[0]
        } else if (usable.size == 1) {
            attempt = usable[0]
        }

        // Clear Previous Difficulty does not count as evidence of a dialog.
        // The failure screen has violet areas at the same position, and a
        // dialog always has either Attempt or the ad button -- and never a
        // crisp auto button beside it, see `mainClear` above.
        val dialogOpen = (attempt != null || ad != null) && !mainClear
        val state = if (dialogOpen) {
            if (attempt != null && party != null) DIALOG_PARTY
            else if (attempt != null) DIALOG
            else if (ad != null) DIALOG_AD
            else DIALOG
        } else if (cards.size >= LIST_MIN_CARDS) {
            LIST
        } else {
            UNKNOWN
        }

        val partyVoll = if (state == DIALOG_PARTY) partySlotsFilled(img) else 0
        return Recognition(state, attempt, party, ad, clear, blue, violet, cards, null,
                           partyVoll = partyVoll)
    }
}
