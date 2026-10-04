package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Painted frames for the Meat Field's flow test, beside Paint.kt and on the
 * same window shape (805 x 1390, where game_rect is the whole image). What
 * test_farm_flow.py does with a dict and a dozen monkey-patched readers, this
 * does with pixels: every frame here goes through the **real** Farm.kt,
 * Explore.kt and Dungeon.kt readers, so the flow test proves the loop and the
 * readers together rather than the loop against a stub of itself.
 *
 * Every colour and place below is farm.py's own measurement, quoted where it
 * is used. Nothing here is a game image (notes/publishing.md, and release.py's
 * EXCLUDED_FILES): these are rectangles in the hues the readers measure.
 *
 * One honest limit, the same one test_passive_flow.py writes down: the digits
 * come from OpenCV's own Hershey font and not from the game's, so a painted
 * glyph is not the shape dungeon.read_digit was measured against. Measured
 * here, over every size these frames use: in white, 0 to 8 read back as
 * themselves and 9 reads as 4; in the refill countdown's pink, which is a
 * thinner mask, 0 and 5 hold and 4 reads as 7. So no frame here paints a 9,
 * and the countdown is only ever painted out of 0 and 5. What a game digit
 * reads as is the oracle test's business, on the game's own frames; what is
 * asked of a painted one is only that the flow around it can be followed.
 */
object PaintFarm {

    const val W = Paint.W
    const val H = Paint.H

    // ------------------------------------------------------------------------
    // The colours, each one farm.py's own measurement
    // ------------------------------------------------------------------------
    // The wood/dirt of a plot: Farm.PLOT_HUE is H 0-20, S >= 90, V >= 40. The
    // saturation is painted at 240 and not at the game's own because
    // Farm.SLOT_HUE (the seed menu's slot backgrounds) reaches into the same
    // hue at S 140-220 and its fy band, 0.44-0.52, covers this grid's own top
    // row at 0.507. On a real frame the two never meet, because a seed dialog
    // dims the field behind it and pushes the visible dirt below the band;
    // painting the dirt above the slots' saturation keeps them apart here for
    // the same reason and without inventing a place for either.
    private val DIRT = Paint.hsv(10, 240, 120)
    // The same dirt with the value a dialog's dimming leaves, under
    // PLOT_HUE's own floor of 40: the plots a dialog covers stop being
    // counted, which is what makes meat_field_screen read 4 on a real seed
    // menu (PLAN_MEAT_FIELD 2.2) and what Farm.seedMenu is gated on.
    private val DIRT_DIMMED = Paint.hsv(10, 240, 35)
    // The cream badge's dark green ring: Farm.BADGE_HUE, H 30-45, S >= 180,
    // V <= 140.
    private val BADGE = Paint.hsv(37, 220, 100)
    // The water bubble: Farm.BUBBLE_WHITE, H 95-112, S >= 110, V >= 180.
    // Blue, not white -- a light grey stone in the dirt would pass a
    // brightness mask and does not pass this one.
    private val BUBBLE = Paint.hsv(103, 180, 230)
    // The seed menu's slot backgrounds: Farm.SLOT_HUE, H 0-15, S 140-220,
    // V 60-160.
    private val SLOT = Paint.hsv(8, 180, 110)
    // The gold bracket around the chosen slot: Farm.BRACKET_HUE, H 8-22,
    // S >= 180, V >= 180.
    private val BRACKET = Paint.hsv(15, 241, 242)
    // The green Select button, and the water popup's own: Farm.SELECT_HUE,
    // H 45-65, S >= 100, V >= 150.
    private val GREEN = Paint.hsv(55, 200, 200)
    // The pink refill countdown: Farm.REFILL_HUE, measured H 162 S 217 V 255.
    private val PINK = Paint.hsv(162, 217, 255)
    private val WHITE = Scalar(255.0, 255.0, 255.0)
    // The Explore menu's dark body: Explore.MENU_DARK_HUE, H 95-130,
    // S >= 120, V 20-110.
    private val MENU_BODY = Paint.hsv(110, 200, 60)
    // The Digital World Search card's magenta screen: Explore.CARD_HUE,
    // H 140-160, S >= 90, V >= 120.
    private val CARD = Paint.hsv(148, 200, 200)
    // The Meat Field card's lime vegetable: Explore.FIELD_HUE, H 35-55.
    private val VEG = Paint.hsv(45, 200, 200)
    // The X's blue mark: Summon.EXIT_MARK, H 98-118, S 110-220, V >= 140.
    private val X_MARK = Paint.hsv(108, 165, 200)
    // The watering-can dialog's four yellow keys (MIN, -, +, MAX):
    // Farm.BOOST_KEY_HUE, H 15-32, S >= 150, V >= 190, measured H 17 to 30.
    // Painted at H 25, outside the bracket's 8-22 and the dirt's 0-20, so
    // that neither of those readers counts a key and no key reader counts
    // them -- in the game they are apart by place as well.
    private val KEY = Paint.hsv(25, 220, 230)
    // "Time Remaining" in the water popup: Farm.POPUP_TIMER_HUE, H 35-60,
    // S >= 80, V >= 160, the digits measured H 38 to 55, S 90 to 236, V 177
    // to 196. Under SELECT_HUE's own V floor it is not, so the digits are
    // painted small enough (share) that no button reader takes them.
    private val TIME_GREEN = Paint.hsv(45, 160, 190)
    // The violet "Ad (n/2)" in Select's place: Dungeon.VIOLET, H 117-145,
    // S >= 100, V >= 120 (measured H 122 to 130).
    private val VIOLET = Paint.hsv(126, 180, 200)
    // The Reward sheet's darker blue: Dungeon.SHEET_BLUE, H 100-118,
    // S >= 180, V 130-165 -- PaintRunner.rewardSheet's box.
    private val SHEET = Paint.hsv(110, 220, 150)
    private val BLACK = Scalar(0.0, 0.0, 0.0)

    // ------------------------------------------------------------------------
    // Where things are
    // ------------------------------------------------------------------------
    // A plot's own rectangle: wide and tall enough to clear
    // Farm.PLOT_MIN_SHARE (0.01 of the reference area) with room, and with a
    // gap to its neighbours so that six separate clumps come out and not one.
    private const val PLOT_HALF_W = 0.09
    private const val PLOT_HALF_H = 0.055
    // The badge sits above the plot's own centre: Farm._plot_badge looks
    // between cy - 0.16 and cy + 0.02, measured -0.049 to -0.025 on the real
    // frames.
    private const val BADGE_DY = -0.07
    private const val BADGE_HALF_W = 0.09
    private const val BADGE_HALF_H = 0.03
    // The bubble's own outer-top corner, measured on field_growing_adb.png:
    // column 0 at fx 0.216 against its centre 0.313, column 1 at 0.724
    // against 0.640 -- the two mirror around the screen's middle -- and
    // fy 0.427-0.465 against a row centre of 0.507, so dy -0.061.
    private const val BUBBLE_DX = 0.097
    private const val BUBBLE_DY = -0.061
    private const val BUBBLE_R = 12
    // The seed menu, measured on seed_menu_left_adb.png: three slots at
    // fx 0.291 / 0.476 / 0.661, all at fy 0.475, and the green Select button
    // at fx 0.476, fy 0.599, share 0.0106.
    private val SLOT_FX = doubleArrayOf(0.291, 0.476, 0.661)
    private const val SLOT_FY = 0.475
    private const val SLOT_HALF_W = 0.065
    private const val SLOT_HALF_H = 0.025
    // The bracket's four corners, measured on the same frame: 0.0605 either
    // side of the slot centre in fx and 0.034 in fy -- inside Farm's own
    // annulus, BRACKET_DX 0.03-0.10 and BRACKET_DY 0.015-0.06, and clear of
    // the hole in its middle where the slot's own icon sits.
    private const val BRACKET_DX = 0.0605
    private const val BRACKET_DY = 0.034
    private const val SELECT_FX = 0.476
    private const val SELECT_FY = 0.599
    // Select measured fw 0.244, fh 0.046 -- an aspect of 5.3 is what the
    // water popup's own button has, so Select is painted at the aspect it was
    // measured at (3.1) and Water at its own (see WATER_*). That difference
    // is the whole of what Farm.waterButton tells them apart by
    // (WATER_BUTTON_MIN_ASPECT, 4.0 until 2026-09-28 and 3.5 since).
    private const val SELECT_HALF_W = 0.122
    private const val SELECT_HALF_H = 0.023
    // The water popup's green button, measured on water_popup_adb.png:
    // fw 0.321, fh 0.046, so an aspect of 7.0 -- comfortably over the 4.0
    // floor, where Select's 5.3 would not be. Painted a shade flatter than
    // the measurement so the two sit either side of that floor as they do in
    // the game.
    //
    // Since 2026-09-28 at the place M1 measured (water_popup_012705.png: fx
    // 0.476, fy 0.6758) rather than at 0.62: "Time Remaining" is read in the
    // same green at fy 0.600 to 0.645 (Farm.POPUP_TIMER_BAND), and a button
    // painted there would be the whole of that band.
    const val WATER_FX = 0.476
    const val WATER_FY = 0.6758
    private const val WATER_HALF_W = 0.1605
    private const val WATER_HALF_H = 0.0215
    // The popup's "Time Remaining", right-aligned to fx 0.6827 at fy 0.6202
    // to 0.6313 (Farm.POPUP_TIMER_BAND): the text's right edge and its
    // baseline.
    private const val POPUP_TIME_RIGHT = 0.6827
    private const val POPUP_TIME_BASELINE = 0.633
    private const val POPUP_TIME_PX = 16

    // The header's second row, the green can (Farm.WATER_CANS_BAND, fy
    // 0.108-0.136, fx 0.500-0.575; the digits measured at fy 0.1182 to
    // 0.1288, right-aligned to 0.5606) and its pink countdown under it
    // (Farm.CAN_REFILL_BAND, fy 0.130-0.160, fx 0.470-0.580; measured fx
    // 0.4996 to 0.5519, fy 0.1364 to 0.1444). The countdown is painted
    // smaller than the sack's, as it is in the game (16 px against 21), and
    // a shade smaller again, 14 px, so that "05:00" fits the band's 0.11 of
    // the width in this font.
    private const val CANS_RIGHT = 0.5606
    private const val CANS_BASELINE = 0.1300
    private const val CANS_PX = 21
    private const val CAN_REFILL_FX = 0.527
    private const val CAN_REFILL_BASELINE = 0.1575
    private const val CAN_REFILL_PX = 14

    // The number under a slot: 0.025 to 0.036 under its centre in both
    // dialogs (Farm.SLOT_COUNT_DY reads 0.022 to 0.048), 22 px.
    private const val SLOT_COUNT_BASELINE_DY = 0.043
    private const val SLOT_COUNT_PX = 22

    // The watering-can dialog, boost_012720.png: the Tiny slot at fx 0.3762
    // and the Huge one at 0.5759, both at fy 0.4477; the four keys in a row
    // at fy 0.554 to 0.589; the white box between "-" and "+" (fx 0.36 to
    // 0.58, the number black at fy 0.5621 to 0.5788); Water at fx 0.476, fy
    // 0.6616, an aspect of 3.10 -- under Farm.WATER_BUTTON_MIN_ASPECT, which
    // is what keeps the dialog from reading as the water popup.
    const val TINY_FX = 0.3762
    const val HUGE_FX = 0.5759
    const val CAN_SLOT_FY = 0.4477
    val KEY_FX = doubleArrayOf(0.22, 0.30, 0.66, 0.74)
    private const val KEY_FY = 0.571
    private const val KEY_HALF_W = 0.03
    private const val KEY_HALF_H = 0.015
    private val AMOUNT_BOX = doubleArrayOf(0.355, 0.545, 0.585, 0.595)
    private const val AMOUNT_BASELINE = 0.584
    private const val AMOUNT_PX = 26
    private const val AMOUNT_THICK = 1
    private const val POPUP_THICK = 2
    const val BOOST_BUTTON_FX = 0.476
    const val BOOST_BUTTON_FY = 0.6616
    private const val BOOST_BUTTON_HALF_W = 0.10
    private const val BOOST_BUTTON_HALF_H = 0.025

    // The seed menu's violet "Ad (n/2)", in Select's place (fx 0.476, fy
    // 0.5995, ad_dialog_seeds_013423.png).
    private const val AD_TEXT_PX = 20
    //
    // The sizes of the painted numbers above are the ones the readers read
    // back, measured on these frames 2026-09-28 over 0 to 8 and the counts
    // the tests paint -- the Hershey font is not the game's (the head of
    // this file): under a slot 22 and 23 px read every one, 18 to 21 px
    // read 4 as 7, 2 as 3 or 8 as 9, and 24 px 4 as 7 again; in the can
    // dialog's box 26 px reads every digit at a stroke of 1; the popup's
    // green time reads from 14 px at a stroke of 2 and not at all at a
    // stroke of 1 under 20 px (the crop is brought to 1920's scale first,
    // Farm.popupTimer); the ad's "n/2" reads from 19 px.

    // The free-seed counter on the field's own header: Farm.FREE_SEEDS_BAND
    // is fy 0.060-0.110, fx 0.375-0.455, and the digits measured 21 px tall
    // -- taller than a first crop suggests, which is the trap that read "11"
    // as 3 and 7 (NOTES.md, the quest counter's "1").
    private const val SEEDS_FX = 0.415
    private const val SEEDS_BASELINE = 0.098
    private const val SEEDS_PX = 21
    // The pink refill countdown under it: Farm.REFILL_BAND is fy 0.10-0.14,
    // fx 0.34-0.48. It is painted below the seed counter's own band so that
    // neither reader can see the other's glyphs -- which is also true in the
    // game, and is why one is read off a brightness mask and the other off a
    // hue one.
    private const val REFILL_FX = 0.41
    private const val REFILL_BASELINE = 0.137
    private const val REFILL_PX = 22
    // A plot's H:M:S plate. Farm.plotTimer crops cy - 0.02 to cy + 0.10 and
    // cx +- 0.15; the plate's own offset from the cell centre measured 0.018
    // to 0.058 below it across rows and frames, and the digits 13 px tall.
    private const val TIMER_DY = 0.045
    private const val TIMER_PX = 22
    // The harvest count on a ripe badge: white digits on the badge, 21-22 px
    // on the real frames. What makes a badge "ripe" rather than "empty" is
    // that there is a number on it at all (Farm.plotBadgeKind).
    private const val HARVEST_PX = 20

    // The X that closes the field, Summon.exitButton's own spot: fx 0.7934,
    // fy 0.9567, plate fw 0.0785. The reader crops 0.35 of that width either
    // side and wants a near-white plate over 0.60 of it with a blue mark on
    // 0.12-0.34 -- so a white square of the plate's width with a blue square
    // a fifth of the crop's area on it.
    private const val X_HALF = Summon.EXIT_BUTTON_FW / 2
    private const val X_MARK_HALF = 0.0125

    // The bottom nav bar. dungeon.homeButton wants a white ring of fw
    // 0.038-0.062 at an aspect near 1 and a fill of 0.30-0.70 -- a filled
    // disc is refused, the globe is a ring. Explore.navTab then looks for the
    // label clump 0.235 to its right, in the band fy 0.955-0.985. The globe
    // is painted at 0.935 rather than the band's own middle so its ring stays
    // out of the label row, which is the trap Explore.labelClumps describes.
    private const val GLOBE_FX = 0.4825
    private const val GLOBE_FY = 0.935
    private const val GLOBE_R = 20
    private const val GLOBE_THICK = 5
    private const val LABEL_FY = 0.969
    // The Explore menu: the dark body behind the cards has to cover 0.45 of
    // Explore.MENU_DARK_BAND (fy 0.16-0.86); the Digital World Search card's
    // magenta screen stands at Explore.CARD_TILE (0.294, 0.214); and the Meat
    // Field's vegetable a fixed step down and to the right of it --
    // Explore.FIELD_ANCHOR_DX 0.0715 and FIELD_ANCHOR_DY 0.2442 between the
    // two tap targets, which are the blobs plus their own CARD_TAP_DY 0.072
    // and FIELD_TAP_DY 0.073. That arithmetic puts the vegetable at
    // (0.3655, 0.4572), which is where the three real menu frames measured it
    // (0.364-0.366, 0.457).
    private const val WS_FX = 0.294
    private const val WS_FY = 0.214
    private const val FIELD_CARD_FX = WS_FX + Explore.FIELD_ANCHOR_DX
    private const val FIELD_CARD_FY =
        WS_FY + Explore.CARD_TAP_DY + Explore.FIELD_ANCHOR_DY - Explore.FIELD_TAP_DY

    // ------------------------------------------------------------------------
    /** One cell of the grid, as the world wants it painted. */
    class Plot(
        /** "growing", "ripe", "empty", or null for a cell nothing can be read on. */
        val state: String?,
        /** The H:M:S a growing plot's plate shows, e.g. "02:00:00". */
        val timer: String = "",
        /** The count on a ripe plot's badge -- any number makes it ripe. */
        val harvest: String = "12",
        /** A bubble at the plot's own outer top corner, where the game draws it. */
        val bubble: Boolean = false,
        /** A bubble sitting on the tap point itself, which has to veto the tap. */
        val bubbleOnTap: Boolean = false,
    )

    private fun rect(img: Mat, fx: Double, fy: Double, halfW: Double, halfH: Double,
                     colour: Scalar) {
        Imgproc.rectangle(img, Point((fx - halfW) * W, (fy - halfH) * H),
                          Point((fx + halfW) * W, (fy + halfH) * H), colour, -1)
    }

    /**
     * [text] painted so its capitals come out [px] tall, centred on [fx] with
     * its baseline on [fy]. The scale is solved from the font's own metrics
     * rather than guessed, because every reader here measures a glyph against
     * the glyph beside it and not against a pixel count.
     */
    private fun text(img: Mat, text: String, fx: Double, fy: Double, px: Int,
                     colour: Scalar, right: Boolean = false, thick: Int = 1) {
        if (text.isEmpty()) return
        val font = Imgproc.FONT_HERSHEY_SIMPLEX
        // getTextSize("0") at scale 1 gives the cap height; the font scales
        // linearly, so one measurement is the whole answer.
        val unit = Imgproc.getTextSize("0", font, 1.0, 1, IntArray(1)).height
        val scale = px / unit
        val size = Imgproc.getTextSize(text, font, scale, thick, IntArray(1))
        // [right]: [fx] is the right edge, for the counters the game aligns
        // to the right (the can's count, the popup's time).
        val x = if (right) fx * W - size.width else fx * W - size.width / 2
        Imgproc.putText(img, text, Point(x, fy * H), font, scale, colour, thick)
    }

    /**
     * The watering-can dialog as the world wants it: [tiny] under the Tiny
     * slot ("4", or "0/2" at 0 cans), [amount] in the box. [known] false
     * paints it without its four keys -- a dialog after Water that
     * Farm.boostPopup does not know, which the skill must close untouched.
     */
    class Boost(val tiny: String, val amount: String, val huge: String = "0",
                val known: Boolean = true,
                /** The can in the bracket: 0 the Tiny one, as every real dialog opened; 1 the Huge one. */
                val chosen: Int = 0)

    // ------------------------------------------------------------------------
    /**
     * The Meat Field. [plots] is the grid in row-major order, cell `row * 2 +
     * col`, the way `Farm.plots` indexes it.
     *
     * [menu] is the slot the seed dialog has selected, or null while it is
     * shut; [popup] is the game's "this plot is still growing" dialog. Either
     * of them dims the field's top row out of the plot mask, so
     * `meat_field_screen` reads 4 -- what the real dialog measures
     * (PLAN_MEAT_FIELD 2.2) and what `Farm.seedMenu` is gated on.
     */
    fun field(plots: List<Plot>, seeds: String = "", refill: String = "",
              menu: Int? = null, popup: Boolean = false,
              /** The green can's count on the header's second row, and its pink countdown. */
              cans: String = "", canRefill: String = "",
              /** The numbers under the seed menu's three slots, while [menu] is open. */
              slotCounts: List<String> = emptyList(),
              /** "2/2": the menu's violet "Ad (n/2)" stands where Select would. */
              seedAd: String? = null,
              /** The water popup's "Time Remaining", H:M:S or M:S. */
              popupTime: String = "",
              /** The watering-can dialog. */
              boost: Boost? = null,
              /** The Reward sheet over everything. */
              sheet: Boolean = false): Mat {
        if (sheet) return rewardSheet()
        val img = Paint.blank()
        val dialog = menu != null || popup || boost != null
        for (row in 0 until 3) {
            for (col in 0 until 2) {
                val cx = Farm.PLOT_COLS[col]
                val cy = Farm.PLOT_ROWS[row]
                val plot = plots[row * 2 + col]
                rect(img, cx, cy, PLOT_HALF_W, PLOT_HALF_H,
                     if (dialog && row == 0) DIRT_DIMMED else DIRT)
                when (plot.state) {
                    "ripe" -> {
                        rect(img, cx, cy + BADGE_DY, BADGE_HALF_W, BADGE_HALF_H, BADGE)
                        text(img, plot.harvest, cx, cy + BADGE_DY + 0.008, HARVEST_PX, WHITE)
                    }
                    "empty" -> {
                        // The same badge with a trowel on it instead of a
                        // number: grey, so that nothing of it reaches the
                        // badge's own brightness mask and Farm.plotBadgeKind
                        // reads "empty" by the absence of digits.
                        rect(img, cx, cy + BADGE_DY, BADGE_HALF_W, BADGE_HALF_H, BADGE)
                        rect(img, cx, cy + BADGE_DY, 0.02, 0.012, Scalar(120.0, 120.0, 120.0))
                    }
                    "growing" -> text(img, plot.timer, cx, cy + TIMER_DY, TIMER_PX, WHITE)
                }
                if (plot.bubble) {
                    val bx = if (col == 0) cx - BUBBLE_DX else cx + BUBBLE_DX
                    Imgproc.circle(img, Point(bx * W, (cy + BUBBLE_DY) * H), BUBBLE_R, BUBBLE, -1)
                }
                if (plot.bubbleOnTap) {
                    val tap = Farm.plotTap(col, row)
                    Imgproc.circle(img, Point(tap.fx * W, (tap.fy - 0.05) * H), BUBBLE_R,
                                   BUBBLE, -1)
                }
            }
        }
        text(img, seeds, SEEDS_FX, SEEDS_BASELINE, SEEDS_PX, WHITE)
        text(img, refill, REFILL_FX, REFILL_BASELINE, REFILL_PX, PINK)
        text(img, cans, CANS_RIGHT, CANS_BASELINE, CANS_PX, WHITE, right = true)
        text(img, canRefill, CAN_REFILL_FX, CAN_REFILL_BASELINE, CAN_REFILL_PX, PINK)
        if (menu != null) {
            for ((i, fx) in SLOT_FX.withIndex()) {
                rect(img, fx, SLOT_FY, SLOT_HALF_W, SLOT_HALF_H, SLOT)
                if (i == menu) {
                    for (dx in listOf(-BRACKET_DX, BRACKET_DX)) {
                        for (dy in listOf(-BRACKET_DY, BRACKET_DY)) {
                            rect(img, fx + dx, SLOT_FY + dy, 0.015, 0.01, BRACKET)
                        }
                    }
                }
                text(img, slotCounts.getOrElse(i) { "" }, fx, SLOT_FY + SLOT_COUNT_BASELINE_DY,
                     SLOT_COUNT_PX, WHITE)
            }
            if (seedAd != null) {
                rect(img, SELECT_FX, SELECT_FY, SELECT_HALF_W, SELECT_HALF_H, VIOLET)
                text(img, seedAd, SELECT_FX, SELECT_FY + 0.0075, AD_TEXT_PX, WHITE)
            } else {
                rect(img, SELECT_FX, SELECT_FY, SELECT_HALF_W, SELECT_HALF_H, GREEN)
            }
        }
        if (popup) {
            rect(img, WATER_FX, WATER_FY, WATER_HALF_W, WATER_HALF_H, GREEN)
            text(img, popupTime, POPUP_TIME_RIGHT, POPUP_TIME_BASELINE, POPUP_TIME_PX, TIME_GREEN,
                 right = true, thick = POPUP_THICK)
        }
        if (boost != null) {
            for ((fx, count) in listOf(TINY_FX to boost.tiny, HUGE_FX to boost.huge)) {
                rect(img, fx, CAN_SLOT_FY, SLOT_HALF_W, SLOT_HALF_H, SLOT)
                text(img, count, fx, CAN_SLOT_FY + SLOT_COUNT_BASELINE_DY, SLOT_COUNT_PX, WHITE)
            }
            // The chosen can in the bracket: the Tiny one on every dialog seen.
            val chosenFx = if (boost.chosen == 1) HUGE_FX else TINY_FX
            for (dx in listOf(-BRACKET_DX, BRACKET_DX)) {
                for (dy in listOf(-BRACKET_DY, BRACKET_DY)) {
                    rect(img, chosenFx + dx, CAN_SLOT_FY + dy, 0.015, 0.01, BRACKET)
                }
            }
            if (boost.known) for (fx in KEY_FX) rect(img, fx, KEY_FY, KEY_HALF_W, KEY_HALF_H, KEY)
            Imgproc.rectangle(img, Point(AMOUNT_BOX[0] * W, AMOUNT_BOX[1] * H),
                              Point(AMOUNT_BOX[2] * W, AMOUNT_BOX[3] * H), WHITE, -1)
            text(img, boost.amount, (AMOUNT_BOX[0] + AMOUNT_BOX[2]) / 2, AMOUNT_BASELINE, AMOUNT_PX,
                 BLACK, thick = AMOUNT_THICK)
            rect(img, BOOST_BUTTON_FX, BOOST_BUTTON_FY, BOOST_BUTTON_HALF_W, BOOST_BUTTON_HALF_H,
                 GREEN)
        }
        exitX(img)
        return img
    }

    /** The Reward sheet, as PaintRunner paints it: the band's darker blue and "Tap to close" along fy 0.82. */
    fun rewardSheet(): Mat {
        val img = Paint.blank()
        Imgproc.rectangle(img, Point(0.005 * W, 0.226 * H), Point(0.947 * W, 0.685 * H), SHEET, -1)
        Imgproc.rectangle(img, Point(0.42 * W, 0.812 * H), Point(0.53 * W, 0.828 * H), WHITE, -1)
        return img
    }

    /**
     * The game's "Ad viewing limit reached." box as [Farm.adLimit] finds it:
     * a panel in Dungeon.BLUE 0.66 wide and 0.24 tall (ad_limit_cans_013641.png,
     * 0.6626 x 0.2384), and in its lower half an OK of 0.20 x 0.042 at fy
     * 0.60 -- ringed in white so that the two come out as two blobs, as the
     * game's darker panel and lighter button do. The ring is wider than the
     * 15 x 5 px closing Dungeon.findButtons gives its mask (24 px across, 14
     * down at 805 x 1390), or the two would be one.
     */
    fun limitBox(): Mat {
        val img = Paint.blank()
        val blue = Paint.hsv(105, 200, 200)
        rect(img, 0.5, 0.514, 0.331, 0.119, blue)
        rect(img, 0.5, 0.60, 0.128, 0.031, WHITE)
        rect(img, 0.5, 0.60, 0.098, 0.021, blue)
        return img
    }

    /** The white X with its blue mark, in the corner every screen of this path has it. */
    private fun exitX(img: Mat) {
        rect(img, Summon.EXIT_BUTTON_FX, Summon.EXIT_BUTTON_FY, X_HALF, X_HALF * W / H.toDouble(),
             WHITE)
        rect(img, Summon.EXIT_BUTTON_FX, Summon.EXIT_BUTTON_FY, X_MARK_HALF,
             X_MARK_HALF * W / H.toDouble(), X_MARK)
    }

    /** The globe and the Explore label beside it: the bottom nav bar. */
    private fun navBar(img: Mat) {
        Imgproc.circle(img, Point(GLOBE_FX * W, GLOBE_FY * H), GLOBE_R, WHITE, GLOBE_THICK)
        rect(img, GLOBE_FX + Explore.NAV_EXPLORE_DX, LABEL_FY, 0.022, 0.005, WHITE)
    }

    /**
     * The plain main screen with the nav bar under it: Paint.mainScreen's own
     * auto button, which is what says "the main screen, nothing over it", plus
     * the globe and the Explore tab that go_to_field taps.
     */
    fun mainScreen(): Mat = Paint.mainScreen().also { navBar(it) }

    /** The Explore menu: the dark body, the two cards, and the nav bar under them. */
    fun exploreMenu(): Mat {
        val img = Paint.blank()
        rect(img, 0.5, 0.51, 0.45, 0.33, MENU_BODY)
        rect(img, WS_FX, WS_FY, 0.05, 0.025, CARD)
        rect(img, FIELD_CARD_FX, FIELD_CARD_FY, 0.05, 0.02, VEG)
        navBar(img)
        return img
    }
}
