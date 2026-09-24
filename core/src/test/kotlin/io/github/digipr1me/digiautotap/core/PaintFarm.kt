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
 * is used. Nothing here is a game image (NOTES.md, and release.py's
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
    // (WATER_BUTTON_MIN_ASPECT 4.0).
    private const val SELECT_HALF_W = 0.122
    private const val SELECT_HALF_H = 0.023
    // The water popup's green button, measured on water_popup_adb.png:
    // fw 0.321, fh 0.046, so an aspect of 7.0 -- comfortably over the 4.0
    // floor, where Select's 5.3 would not be. Painted a shade flatter than
    // the measurement so the two sit either side of that floor as they do in
    // the game.
    private const val WATER_FX = 0.5
    private const val WATER_FY = 0.62
    private const val WATER_HALF_W = 0.1605
    private const val WATER_HALF_H = 0.0215

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
                     colour: Scalar) {
        if (text.isEmpty()) return
        val font = Imgproc.FONT_HERSHEY_SIMPLEX
        // getTextSize("0") at scale 1 gives the cap height; the font scales
        // linearly, so one measurement is the whole answer.
        val unit = Imgproc.getTextSize("0", font, 1.0, 1, IntArray(1)).height
        val scale = px / unit
        val size = Imgproc.getTextSize(text, font, scale, 1, IntArray(1))
        Imgproc.putText(img, text, Point(fx * W - size.width / 2, fy * H), font, scale,
                        colour, 1)
    }

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
              menu: Int? = null, popup: Boolean = false): Mat {
        val img = Paint.blank()
        val dialog = menu != null || popup
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
            }
            rect(img, SELECT_FX, SELECT_FY, SELECT_HALF_W, SELECT_HALF_H, GREEN)
        }
        if (popup) rect(img, WATER_FX, WATER_FY, WATER_HALF_W, WATER_HALF_H, GREEN)
        exitX(img)
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
