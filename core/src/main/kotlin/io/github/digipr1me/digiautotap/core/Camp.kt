package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * The Camp: the Camp tab of the nav bar, the Camp screen with its map, the
 * Camp Tent with Support and Check-In, the Camp Support window behind
 * Support, the Camp Shop with its cards and the purchase dialog
 * (PLAN_CAMP.md C1, measured on LDPlayer instance 0 on 2026-10-19 with
 * the app force-stopped, so that no overlay is in the frames).
 *
 * What the game draws, from the frames of that measurement
 * (corpus/camp/, and the camp rows of corpus/formats/):
 *
 *  * The nav bar has seven fields since 1.5.0, Camp between the globe and
 *    Explore. A tap opens the Camp screen: a light-blue title bar "CAMP"
 *    under the HUD, the map under it -- the tent top right, the shop left,
 *    Piximon's Drill Site right, Leomon's Dojo bottom left, Camp Wars
 *    bottom right -- and the nav bar with Camp lit. The map is canvas: on
 *    1080 x 2520 every building stands where the bottom anchor puts it.
 *    Members' avatars walk over the map and stand over the signs, and a
 *    tap on a sign with an avatar over it opens the sign's window all the
 *    same (the Drill Site, 2026-10-19 11:36).
 *  * Every window of the camp has a pale-blue title plate of the same
 *    colour and width, and no X: the tent's at one height, the shop's,
 *    the Camp Support window's and the purchase dialog's each at their own,
 *    and a tap in the dimmed field above a window closes it, one layer per
 *    tap. The windows stand in the middle over the canvas ceiling.
 *  * The tent on Info: Support (blue, always) and Check-In (blue, or grey
 *    as "Check-In Complete") along its bottom. What says there is something
 *    to support is the pink "!" at Support's corner, not the colour: after
 *    the day's supports the button is as blue as before and the "!" is
 *    gone. Support opens the Camp Support window: "Support Reward: Camp
 *    Coin x50 (n/10 daily)", a list of members in training, each with a
 *    Support button, and a yellow "Support All" with the same "!". Support
 *    All raised the Reward sheet (three coins x50; Dungeon.rewardSheet),
 *    "Tap to close" brought the window back with the list empty and
 *    Support All dimmed to khaki.
 *  * The shop: the coin counter top left, a filter "All" top right (never
 *    tapped), a grid of cards three wide and five rows, three rows in
 *    sight, and the scroll position is kept between two openings. A card:
 *    the item's picture, a blue "1Day" badge on the daily ones, "n/m" of
 *    the day's limit, the name, and a white plate with the coin and the
 *    price in pink. A sold-out card is dimmed with a "SOLD OUT" stamp over
 *    the picture, "0/1" in red, and its plate grey; a tap on it opens
 *    nothing (11:41).
 *  * The purchase dialog: the item's name as its title, the item's row,
 *    "Purchase using Camp Coin xN?", the row `-10 - n + +10` and a
 *    turquoise button with the coin and the price. `+` stops at the day's
 *    limit, which then stands as "n / m" under the row; without coins the
 *    count and the price are red. The price button closed the dialog and
 *    raised the Reward sheet over the shop (the ticket x5), and "Tap to
 *    close" left the shop standing.
 *
 * The findings: PLAN_CAMP.md 6, until C4 carries them into notes/camp.md.
 */
object Camp {

    // ------------------------------------------------------------------------
    // Where the pieces stand over the canvas ceiling (Dungeon.Anchor)
    // ------------------------------------------------------------------------
    /**
     * The Camp screen is canvas, like the HUD under it: on the whole 1080 x
     * 2520 frame (180 rows of headroom) the tent's roof reads at the bottom
     * anchor where it reads at 1920, and so do the title bar and the nav
     * bar; the middle anchor puts the roof 0.045 and the top 0.091 too high.
     * Measured on camp_1080x2520_none0 against camp_1080x1920_none0.
     */
    val MAP = Dungeon.Anchor.BOTTOM

    /**
     * The windows stand in the middle, like every popup: Support's middle
     * reads fy 0.830 at the middle anchor on the whole 2520 frame as at
     * 1920 (0.875 at the bottom, 0.785 at the top).
     */
    val WINDOW = Dungeon.Anchor.MIDDLE

    // ------------------------------------------------------------------------
    // The Camp tab
    // ------------------------------------------------------------------------
    /**
     * The Camp label of the nav bar stands right of the globe, where
     * Explore.NAV_EXPLORE_DX put Explore before 1.5.0 pushed it one field
     * out: measured over the main screens of the format rows, dx 0.1200 to
     * 0.1225 of the globe (the probe prints every label's dx). Half the way
     * to either neighbour, as Explore's tolerance is.
     */
    const val NAV_CAMP_DX = 0.121

    /**
     * Where to tap the Camp tab on the plain main screen, or null. Asked on
     * the plain main screen only, like the Missions tile: the nav bar under
     * a dialog is dimmed and never tapped.
     */
    fun campTab(img: Mat): Explore.Target? {
        if (Dungeon.autoButton(img) == null) return null
        val tab = Explore.navTab(img, NAV_CAMP_DX) ?: return null
        return Explore.Target(tab.fx, tab.fy)
    }

    // ------------------------------------------------------------------------
    // The Camp screen
    // ------------------------------------------------------------------------
    // The title bar: a light blue band the full width of the canvas under
    // the HUD, H 100-102, S 179-218, V 229-252 at 1920, "CAMP" in white in
    // its middle. Read on its left part only, fx 0.05 to 0.40, where
    // neither the word nor the overlay's plate (which lies to the right of
    // the middle at every place of the dot's strip) can cut it.
    // The share over the 22 Camp screens of the seven format rows (the map
    // in front): 0.888 to 0.903 at the map's anchor; on the main screens
    // 0.000 to 0.071, and 0.000 to 0.071 under every window of the camp,
    // which dims the bar. TITLE_MIN is the middle.
    val TITLE_BLUE = Dungeon.Hsv(intArrayOf(95, 140, 200), intArrayOf(108, 255, 255))
    val TITLE_BOX = doubleArrayOf(0.05, 0.40, 0.105, 0.130)
    const val TITLE_MIN = 0.48

    // The tent's roof: the one orange thing of its size at the top right of
    // the map, H 15-18, S 171-180, V 224-255. The map coming in (the frame
    // of 11:31:53, grass and paths and no building yet) has none, and the
    // screen is not read until the roof stands: a tap at the tent's place
    // before that hits grass.
    // Over the 22 Camp screens of the seven rows the roof is one blob at
    // fx0 0.5542-0.5547, fy0 0.1732-0.1737, 0.2693-0.2697 wide and
    // 0.1258-0.1302 tall (the flowers in front of it), fill 0.52-0.56 (a
    // gable is no rectangle); an avatar in front of it has not been seen
    // (they walk on the paths below). The Shop's Passes page carries the
    // same title band, the undimmed nav bar and the Battle Acceleration
    // Pass's orange card where the roof stands -- fx0 0.5545, w 0.272, h
    // 0.124, fill 0.45, but its top edge at fy0 0.2162, 0.043 under the
    // roof's (corpus/passive/unclear_163655, which read as the Camp screen
    // on 2026-10-19) -- so the roof's top edge is held too, and its fill
    // against a solid orange rectangle, which has not been met. The limits
    // are loose around the one shape it has.
    val ROOF_ORANGE = Dungeon.Hsv(intArrayOf(8, 120, 170), intArrayOf(24, 255, 255))
    val ROOF_BAND = doubleArrayOf(0.12, 0.34)
    val ROOF_FX0 = doubleArrayOf(0.50, 0.61)
    val ROOF_FY0 = doubleArrayOf(0.15, 0.195)
    val ROOF_W = doubleArrayOf(0.22, 0.32)
    val ROOF_H = doubleArrayOf(0.09, 0.16)
    val ROOF_FILL = doubleArrayOf(0.40, 0.70)
    const val ROOF_MIN_SHARE = 0.005

    // The four buildings, as fractions of the map's rectangle (MAP), from
    // the taps of 2026-10-19 that opened each of them at 1080 x 1920 --
    // the tent at 780,500, the shop at 270,850, the Drill Site at 800,1060,
    // the Dojo at 250,1330 -- and the same fractions opened the tent and
    // the shop on every format row.
    val TENT = Explore.Target(0.6853, 0.2813, MAP)
    val SHOP = Explore.Target(0.2406, 0.4581, MAP)
    val DRILL = Explore.Target(0.7027, 0.5641, MAP)
    val DOJO = Explore.Target(0.2232, 0.7005, MAP)

    /** The Camp screen with nothing over it: the four buildings as tap targets. */
    data class CampScreen(val tent: Explore.Target = TENT, val shop: Explore.Target = SHOP,
                          val drill: Explore.Target = DRILL, val dojo: Explore.Target = DOJO,
                          val roof: Runner.Blob) {
        fun toOracle(): Map<String, Any?> = mapOf("roof" to roof.toOracle())
    }

    private fun shareIn(img: Mat, colour: Dungeon.Hsv, box: DoubleArray, anchor: Dungeon.Anchor): Double {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val sub = Py.crop(img, maxOf(0, Py.int(y0 + box[2] * gh)), Py.int(y0 + box[3] * gh),
                          maxOf(0, Py.int(x0 + box[0] * gw)), Py.int(x0 + box[1] * gw)) ?: return 0.0
        val m = Cv.hsvMask(sub, colour)
        val s = Cv.share(m)
        m.release()
        return s
    }

    /** The title bar's blue share in [TITLE_BOX] (the probe prints it). */
    fun titleShare(img: Mat): Double = shareIn(img, TITLE_BLUE, TITLE_BOX, MAP)

    /** Every orange clump of the roof's colour in its band (the probe prints them). */
    fun roofCandidates(img: Mat): List<Runner.Blob> =
        Runner.blobs(img, ROOF_ORANGE, band = ROOF_BAND, minShare = ROOF_MIN_SHARE, anchor = MAP)

    private fun roof(img: Mat): Runner.Blob? = roofCandidates(img).firstOrNull {
        it.fx0 in ROOF_FX0[0]..ROOF_FX0[1] && it.fy0 in ROOF_FY0[0]..ROOF_FY0[1] &&
            it.width in ROOF_W[0]..ROOF_W[1] && it.height in ROOF_H[0]..ROOF_H[1] && it.fill in ROOF_FILL[0]..ROOF_FILL[1]
    }

    /**
     * The Camp screen, or null: the title bar, the nav bar undimmed with
     * its globe (Explore.navTab, which refuses a dimmed bar), and the
     * tent's roof on the map. Under any of the camp's windows the map and
     * the bar are dimmed and this says null.
     */
    fun screen(img: Mat): CampScreen? {
        if (titleShare(img) < TITLE_MIN) return null
        if (Explore.navTab(img, NAV_CAMP_DX) == null) return null
        val roof = roof(img) ?: return null
        return CampScreen(roof = roof)
    }

    // ------------------------------------------------------------------------
    // The windows' title plates
    // ------------------------------------------------------------------------
    // Every window's title plate is the same pale blue, H 100-102, S
    // 214-219, V 239-248, 0.58 wide, and each window's stands at its own
    // height (middle anchor, 1920): the tent's at fy 0.152, the shop's
    // 0.177, Camp Support's 0.209, the purchase dialog's 0.303. The title
    // bar of the screen is the same colour and the full width, which no
    // plate is.
    val PLATE_BLUE = Dungeon.Hsv(intArrayOf(96, 180, 215), intArrayOf(107, 240, 255))
    val PLATE_W = doubleArrayOf(0.50, 0.66)
    val PLATE_H = doubleArrayOf(0.030, 0.065)
    const val PLATE_MIN_SHARE = 0.01
    const val PLATE_DY = 0.015
    const val TENT_PLATE_FY = 0.1515
    const val SHOP_PLATE_FY = 0.1773
    const val SUPPORT_PLATE_FY = 0.2091
    const val DIALOG_PLATE_FY = 0.3025
    // The purchase dialog of an item with one piece left today (a Digital
    // Gate at 1/1) has no row of squares: the window is shorter by the row
    // and stands in the middle, its plate lower and its price button higher.
    // Measured 2026-11-01 on the game's 1.5.1, instance 0, the Blue Gate's
    // dialog on two frames: plate cy 0.3273 (0.0248 under DIALOG_PLATE_FY,
    // farther than PLATE_DY), the price button cy 0.6427 and 0.6437 against
    // 0.6682, of the same width 0.2441 (PLAN_CAMP.md C4).
    const val SINGLE_PLATE_FY = 0.3273

    /** Every blob of the plates' blue of a plate's size, top first (the probe prints them). */
    fun plates(img: Mat): List<Runner.Blob> =
        Runner.blobs(img, PLATE_BLUE, band = doubleArrayOf(0.08, 0.40), minShare = PLATE_MIN_SHARE, anchor = WINDOW)
            .filter { it.width in PLATE_W[0]..PLATE_W[1] && it.height in PLATE_H[0]..PLATE_H[1] }
            .sortedBy { it.fy0 }

    private fun plateAt(img: Mat, fy: Double): Runner.Blob? = plates(img).firstOrNull { kotlin.math.abs(it.cy - fy) <= PLATE_DY }

    /**
     * Where a window is closed: a tap in the dimmed field above it, at the
     * director's neutral spot (Director.NEUTRAL_TAP_FX/FY), in the window's
     * rectangle. No camp window has an X; measured by hand on every format
     * row (540,165 at 1080 x 1920; 540,201 at 2340; 540,192 at 1080 x 2235;
     * 540,291 at 2520; 360,154 at 720 x 1600; 600,165 at 1200 x 1920;
     * 960,93 in landscape): one layer closed per tap, the one under it
     * standing.
     */
    val CLOSE_SPOT = Explore.Target(Director.NEUTRAL_TAP_FX, Director.NEUTRAL_TAP_FY, WINDOW)

    /** A button of a window: where to tap it, and whether it is lit. */
    data class Button(val target: Explore.Target, val lit: Boolean, val fw: Double, val fh: Double) {
        fun toOracle(): Map<String, Any?> = mapOf("fx" to target.fx, "fy" to target.fy, "lit" to lit,
                                                  "fw" to fw, "fh" to fh)
    }

    private fun button(b: Runner.Blob, lit: Boolean) = Button(Explore.Target(b.cx, b.cy, WINDOW), lit, b.width, b.height)

    // ------------------------------------------------------------------------
    // The tent
    // ------------------------------------------------------------------------
    // Support and Check-In along the window's bottom: Support blue (H 100,
    // S 255, V 209: Dungeon.BLUE), 0.225 wide and 0.048 tall at fx 0.352, fy
    // 0.830; Check-In Complete grey (H 109, S 94, V 136) of the same size at
    // fx 0.601. The Info tab, lit, is the same blue at fx 0.254, fy 0.214,
    // 0.218 wide. Under the Camp Support window Support is dimmed to V 84
    // and out of BLUE, and that window's own plate covers the tab row.
    // Measured over the 17 tent frames of the seven rows: Support fx0
    // 0.2406, w 0.2214, h 0.0394, cx 0.3514, cy 0.8328; Check-In Complete
    // the same size at cx 0.6007; the Info tab w 0.2214, h 0.0328 at cx
    // 0.2520, cy 0.2139. The limits are loose around those shapes.
    val BUTTON_BAND = doubleArrayOf(0.76, 0.90)
    val BUTTON_W = doubleArrayOf(0.19, 0.26)
    val BUTTON_H = doubleArrayOf(0.030, 0.050)
    val SUPPORT_FX = doubleArrayOf(0.28, 0.43)
    val CHECKIN_FX = doubleArrayOf(0.53, 0.68)
    val GREY = Dungeon.Hsv(intArrayOf(90, 10, 110), intArrayOf(125, 130, 175))
    val TAB_BAND = doubleArrayOf(0.17, 0.26)
    val TAB_FX = doubleArrayOf(0.18, 0.33)
    val TAB_W = doubleArrayOf(0.18, 0.26)
    val TAB_H = doubleArrayOf(0.025, 0.042)
    // A fifth of the smallest button's area, the Info tab's; the squares of
    // the purchase dialog, 0.05 by 0.03 under their white signs, are read
    // with half of that (SQUARE_MIN_SHARE).
    const val BUTTON_MIN_SHARE = 0.002
    const val SQUARE_MIN_SHARE = 0.001

    // The pink "!" at a button's top right corner (H 163, S 232, V 255 with
    // a white "!" in it; Missions.BADGE_PINK): its box, in the button's
    // width from the button's right edge and in the button's height from
    // its top edge. The share: 0.1348 at Support's corner on the five tent
    // frames before Support All was tapped, 0.1145 at Support All's own
    // corner on the twelve Camp Support frames before it, and 0.0000 on
    // every button without one -- the Info tab, Support after the day's
    // supports (26 frames). BADGE_MIN is the middle of 0 and 0.1145.
    val BADGE_BOX = doubleArrayOf(-0.12, 0.12, -0.45, 0.35)
    const val BADGE_MIN = 0.057

    /** The pink share of [BADGE_BOX] at the corner of [b] (the probe prints it). */
    fun badgeShare(img: Mat, b: Runner.Blob): Double {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, WINDOW)
        val w = b.width * gw; val h = b.height * gh
        val cx = x0 + b.fx1 * gw; val cy = y0 + b.fy0 * gh
        val k = BADGE_BOX
        val sub = Py.crop(img, maxOf(0, Py.int(cy + k[2] * h)), Py.int(cy + k[3] * h),
                          maxOf(0, Py.int(cx + k[0] * w)), Py.int(cx + k[1] * w)) ?: return 0.0
        val m = Cv.hsvMask(sub, Missions.BADGE_PINK)
        val s = Cv.share(m)
        m.release()
        return s
    }

    /** Every blob of [colour] of a bottom button's size in the band, left to right (the probe prints them). */
    fun buttonBlobs(img: Mat, colour: Dungeon.Hsv, band: DoubleArray = BUTTON_BAND,
                    w: DoubleArray = BUTTON_W, h: DoubleArray = BUTTON_H,
                    minShare: Double = BUTTON_MIN_SHARE): List<Runner.Blob> =
        Runner.blobs(img, colour, band = band, minShare = minShare, anchor = WINDOW)
            .filter { it.width in w[0]..w[1] && it.height in h[0]..h[1] }
            .sortedBy { it.fx0 }

    /** The tent on Info: Support with its "!", Check-In lit or complete. */
    data class TentWindow(val support: Button, val checkIn: Button, val tab: Runner.Blob) {
        val close get() = CLOSE_SPOT
        fun toOracle(): Map<String, Any?> =
            mapOf("support" to support.toOracle(), "check_in" to checkIn.toOracle(), "tab" to tab.toOracle())
    }

    /**
     * The Camp Tent on Info, or null: the lit Info tab, a blue Support in
     * its place and a Check-In beside it, blue (lit) or grey. Support's
     * `lit` is its "!" (see BADGE_BOX): the button itself is blue either
     * way. Check-In's `lit` is its colour: grey is "Check-In Complete". The
     * lit Check-In, borrowed from Support until then, was measured on
     * 2026-11-01 after the reset (game 1.5.1, instance 0): the same blue
     * button at fx 0.6007, fy 0.8328, 0.2214 by 0.0394, Support's size to
     * the digit, with its own "!" (PLAN_CAMP.md C4).
     */
    fun tentWindow(img: Mat): TentWindow? {
        val tab = buttonBlobs(img, Dungeon.BLUE, TAB_BAND, TAB_W, TAB_H).firstOrNull { it.cx in TAB_FX[0]..TAB_FX[1] }
            ?: return null
        val blue = buttonBlobs(img, Dungeon.BLUE)
        val support = blue.firstOrNull { it.cx in SUPPORT_FX[0]..SUPPORT_FX[1] } ?: return null
        val checkLit = blue.firstOrNull { it.cx in CHECKIN_FX[0]..CHECKIN_FX[1] }
        val checkGrey = if (checkLit == null) buttonBlobs(img, GREY).firstOrNull { it.cx in CHECKIN_FX[0]..CHECKIN_FX[1] } else null
        val check = checkLit ?: checkGrey ?: return null
        if (kotlin.math.abs(check.cy - support.cy) > PLATE_DY) return null
        return TentWindow(button(support, badgeShare(img, support) >= BADGE_MIN), button(check, checkLit != null), tab)
    }

    // ------------------------------------------------------------------------
    // The Camp Support window
    // ------------------------------------------------------------------------
    // Support All: yellow as every Claim (H 26, S 213, V 255:
    // Runner.CLAIM_YELLOW), 0.2371 wide and 0.0439 tall at fx 0.4760, fy
    // 0.7740 on every row, with the "!" at its corner while there is
    // somebody to support; khaki (H 25, S 75, V 146) of the same size with
    // the list empty.
    val SUPPORT_ALL_BAND = doubleArrayOf(0.70, 0.84)
    val SUPPORT_ALL_FX = doubleArrayOf(0.40, 0.56)
    val SUPPORT_ALL_W = doubleArrayOf(0.20, 0.28)
    val KHAKI = Dungeon.Hsv(intArrayOf(15, 40, 110), intArrayOf(35, 130, 175))

    /** The Camp Support window: its plate, and Support All, lit or not. */
    data class SupportWindow(val plate: Runner.Blob, val supportAll: Button) {
        val close get() = CLOSE_SPOT
        fun toOracle(): Map<String, Any?> = mapOf("plate" to plate.toOracle(), "support_all" to supportAll.toOracle())
    }

    /**
     * The Camp Support window, or null: its own plate at its height, and
     * Support All in its place, yellow (lit) or khaki. Lit is the colour
     * here, not the "!": the list is what the button acts on, and it empties
     * with the button.
     */
    fun supportWindow(img: Mat): SupportWindow? {
        val plate = plateAt(img, SUPPORT_PLATE_FY) ?: return null
        val lit = buttonBlobs(img, Runner.CLAIM_YELLOW, SUPPORT_ALL_BAND, SUPPORT_ALL_W)
            .firstOrNull { it.cx in SUPPORT_ALL_FX[0]..SUPPORT_ALL_FX[1] }
        val dim = if (lit == null) buttonBlobs(img, KHAKI, SUPPORT_ALL_BAND, SUPPORT_ALL_W)
            .firstOrNull { it.cx in SUPPORT_ALL_FX[0]..SUPPORT_ALL_FX[1] } else null
        val b = lit ?: dim ?: return null
        return SupportWindow(plate, button(b, lit != null))
    }

    // ------------------------------------------------------------------------
    // The shop
    // ------------------------------------------------------------------------
    // A card is found by its price plate along its bottom: white (S 0, V
    // 248) on a card for sale, grey (S 0, V 65-75) on a sold-out one,
    // 0.1840 wide and 0.0242 (white) or 0.0263 (grey) tall, the card
    // 0.1667 tall above the plate's bottom edge. Three columns at fx0
    // 0.1787, 0.3862, 0.5937 and three rows in sight, the plates' middles
    // at fy 0.4308, 0.6197, 0.8091 with the list at its top and 0.4227,
    // 0.6116, 0.8010 at its bottom (the same on every row).
    val PLATE_WHITE = Dungeon.Hsv(intArrayOf(0, 0, 235), intArrayOf(179, 25, 255))
    val PLATE_GREY = Dungeon.Hsv(intArrayOf(0, 0, 50), intArrayOf(179, 25, 95))
    val CARD_BAND = doubleArrayOf(0.24, 0.86)
    val CARD_PLATE_W = doubleArrayOf(0.165, 0.205)
    val CARD_PLATE_H = doubleArrayOf(0.014, 0.030)
    const val CARD_H = 0.1667
    const val CARD_ROW_DY = 0.02
    // The icon's box, in the card's width from its middle and in the
    // rectangle's height from the card's top: the picture without the "1Day"
    // badge at the card's top right corner.
    val ICON_BOX = doubleArrayOf(-0.333, 0.333, 0.0202, 0.0909)
    // The "n/m" under the picture, white or red: its box, as the icon's.
    val LEFT_BOX = doubleArrayOf(-0.30, 0.30, 0.0934, 0.1136)
    // The price on the plate, right of the coin: from a third of the card's
    // width in to its right edge, in the plate's rows. Its digits are dark
    // (S 0, V under 80) while the coins reach, and pink (H 162-168, S 255,
    // V 255) where they do not -- the price turns red before the dialog
    // does, as on the Special Summon page (Summon.priceIsRed): instance 1
    // with 0 coins drew every price pink, instance 0 with 529K every one
    // dark. So a card's `affordable` is the price's colour.
    const val PRICE_FROM = 0.33
    val PRICE_DARK = Dungeon.Hsv(intArrayOf(0, 0, 0), intArrayOf(179, 80, 120))
    // The "1Day" badge at the card's top right: its box, as the icon's.
    val DAILY_BOX = doubleArrayOf(0.26, 0.52, -0.008, 0.016)
    val DAILY_BLUE = Dungeon.Hsv(intArrayOf(105, 150, 150), intArrayOf(122, 255, 255))
    const val DAILY_MIN = 0.05
    // The list's scrollbar along the window's right edge: a pale line (H
    // 102, S 16, V 244) 4 px wide at fx 0.79 at 1920, whose thumb stands at
    // the track's top with the list at its top (fy0 0.27, fy1 0.63) and at
    // its bottom with the list there (0.49 to 0.83). The thresholds are the
    // middles of the two spans' ends.
    val THUMB_PALE = Dungeon.Hsv(intArrayOf(95, 0, 225), intArrayOf(112, 45, 255))
    val THUMB_FX0 = doubleArrayOf(0.74, 0.86)
    val THUMB_W = doubleArrayOf(0.001, 0.010)
    val THUMB_H = doubleArrayOf(0.12, 0.60)
    const val THUMB_TOP_MAX = 0.38
    const val THUMB_BOTTOM_MIN = 0.73

    /** One card of the shop. `item` is null without the templates (the director asks so) or where none fits. */
    data class Card(val target: Explore.Target, val fx0: Double, val fx1: Double, val top: Double, val bottom: Double,
                    val soldOut: Boolean, val daily: Boolean, val left: Pair<Int, Int>?, val price: Int?,
                    val affordable: Boolean?, val item: String?, val score: Double?) {
        val width get() = fx1 - fx0
        fun toOracle(): Map<String, Any?> = mapOf(
            "fx" to target.fx, "fy" to target.fy, "fx0" to fx0, "fx1" to fx1, "top" to top, "bottom" to bottom,
            "sold_out" to soldOut, "daily" to daily, "left" to left?.toList(), "price" to price,
            "affordable" to affordable, "item" to item, "score" to score)
    }

    /** The shop: its cards in the grid's order, and whether the list stands at its top or its bottom (null without a scrollbar). */
    data class ShopWindow(val plate: Runner.Blob, val cards: List<Card>, val thumb: Runner.Blob?) {
        val close get() = CLOSE_SPOT
        val atTop: Boolean? get() = thumb?.let { it.fy0 <= THUMB_TOP_MAX }
        val atBottom: Boolean? get() = thumb?.let { it.fy1 >= THUMB_BOTTOM_MIN }
        fun toOracle(): Map<String, Any?> = mapOf("plate" to plate.toOracle(), "cards" to cards.map { it.toOracle() },
                                                  "thumb" to thumb?.toOracle(), "at_top" to atTop, "at_bottom" to atBottom)
    }

    /** The scrollbar's thumb, or null (the probe prints it). */
    fun thumb(img: Mat): Runner.Blob? =
        Runner.blobs(img, THUMB_PALE, band = CARD_BAND, minShare = 0.0001, anchor = WINDOW).firstOrNull {
            it.fx0 in THUMB_FX0[0]..THUMB_FX0[1] && it.width in THUMB_W[0]..THUMB_W[1] && it.height in THUMB_H[0]..THUMB_H[1]
        }

    /** Every plate of a card's size in the band, white and grey, row by row (the probe prints them). */
    fun cardPlates(img: Mat): List<Pair<Runner.Blob, Boolean>> {
        val out = ArrayList<Pair<Runner.Blob, Boolean>>()
        for ((colour, sold) in listOf(PLATE_WHITE to false, PLATE_GREY to true)) {
            for (b in Runner.blobs(img, colour, band = CARD_BAND, minShare = BUTTON_MIN_SHARE, anchor = WINDOW)) {
                if (b.width in CARD_PLATE_W[0]..CARD_PLATE_W[1] && b.height in CARD_PLATE_H[0]..CARD_PLATE_H[1]) out.add(b to sold)
            }
        }
        // Rows by height, then left to right within a row.
        return out.sortedWith(compareBy({ Math.round(it.first.cy / CARD_ROW_DY) }, { it.first.fx0 }))
    }

    private fun box(img: Mat, anchor: Dungeon.Anchor, fx0: Double, fx1: Double, fy0: Double, fy1: Double): Mat? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        return Py.crop(img, maxOf(0, Py.int(y0 + fy0 * gh)), Py.int(y0 + fy1 * gh),
                       maxOf(0, Py.int(x0 + fx0 * gw)), Py.int(x0 + fx1 * gw))
    }

    /** The icon's crop of a card found by its plate [p] (the templates are cut from it). */
    fun iconCrop(img: Mat, p: Runner.Blob): Mat? {
        val top = p.fy1 - CARD_H
        val cx = p.cx; val w = p.width
        return box(img, WINDOW, cx + ICON_BOX[0] * w, cx + ICON_BOX[1] * w, top + ICON_BOX[2], top + ICON_BOX[3])
    }

    /**
     * The shop, or null: its plate at its height and at least one card.
     * With [icons] every card is named by its template; without, `item` is
     * null and so are `atTop` and `atBottom`.
     */
    fun shopWindow(img: Mat, icons: CampIcons? = null): ShopWindow? {
        val plate = plateAt(img, SHOP_PLATE_FY) ?: return null
        val cards = ArrayList<Card>()
        for ((p, sold) in cardPlates(img)) {
            val top = p.fy1 - CARD_H
            val cx = p.cx; val w = p.width
            val leftCrop = box(img, WINDOW, cx + LEFT_BOX[0] * w, cx + LEFT_BOX[1] * w, top + LEFT_BOX[2], top + LEFT_BOX[3])
            val left = leftCrop?.let { readPair(it) }
            val priceCrop = box(img, WINDOW, p.fx0 + PRICE_FROM * w, p.fx1 - 0.02 * w, p.fy0, p.fy1)
            val dark = if (sold) null else priceCrop?.let { readNumber(it, PRICE_DARK) }
            val pink = if (sold || dark != null) null else priceCrop?.let { readNumber(it, PRICE_PINK) }
            val price = dark ?: pink
            val affordable = if (sold || price == null) null else dark != null
            val dailyCrop = box(img, WINDOW, cx + DAILY_BOX[0] * w, cx + DAILY_BOX[1] * w, top + DAILY_BOX[2], top + DAILY_BOX[3])
            val daily = dailyCrop?.let { c -> Cv.hsvMask(c, DAILY_BLUE).let { m -> Cv.share(m).also { m.release() } } }?.let { it >= DAILY_MIN } ?: false
            val named = icons?.let { ic -> iconCrop(img, p)?.let { ic.item(it) } }
            cards.add(Card(Explore.Target(cx, top + CARD_H / 2, WINDOW), p.fx0, p.fx1, top, p.fy1, sold, daily,
                           left, price, affordable, named?.first, named?.second))
        }
        if (cards.isEmpty()) return null
        return ShopWindow(plate, cards, thumb(img))
    }

    // ------------------------------------------------------------------------
    // The purchase dialog
    // ------------------------------------------------------------------------
    // The row `-10 - n + +10`: four blue squares (H 100, S 255, V 209:
    // Dungeon.BLUE under their white signs) at fy 0.591, -10 and +10 0.07
    // wide, - and + 0.05, the count in the dark box between - and +. The
    // price button turquoise (H 91, S 255, V 199), 0.2441 wide and 0.0485
    // tall at fx 0.4760, fy 0.6682 on every row. Without the coins the
    // count and the price are red (H 0, S 255, V 247).
    val ROW_BAND = doubleArrayOf(0.55, 0.63)
    val SQUARE_W = doubleArrayOf(0.035, 0.09)
    val SQUARE_H = doubleArrayOf(0.022, 0.042)
    val TURQUOISE = Dungeon.Hsv(intArrayOf(84, 180, 150), intArrayOf(94, 255, 255))
    val BUY_BAND = doubleArrayOf(0.60, 0.70)
    val BUY_W = doubleArrayOf(0.20, 0.29)
    // Red, both ends of the hue circle: the dialog's count and price without
    // the coins (H 0 or 176, S 255, V 181-255), and a sold-out card's "0/1",
    // which is dark (H 4 to 168 around the wrap, S 109-160, V 45-71). The
    // red share of the price button with these ranges: 0.0370 on the dialog
    // of instance 1 at 0 coins, 0.0075 to 0.0100 on the nine dialogs of
    // instance 0 (the star's red-orange rim); RED_MIN is the middle.
    val RED_LOW = Dungeon.Hsv(intArrayOf(0, 80, 40), intArrayOf(10, 255, 255))
    val RED_HIGH = Dungeon.Hsv(intArrayOf(150, 80, 40), intArrayOf(179, 255, 255))
    const val RED_MIN = 0.023
    // The "n / m" of the day's limit under the row, white, where the item
    // has one: 0.039 under the row's middle at 1920 (y 1190 against 1113).
    val LIMIT_BOX = doubleArrayOf(0.40, 0.56, 0.026, 0.054)
    // On the dialog without a row the "1 / 1" stands over the price button:
    // y 1138 at 1920, 0.0392 over its middle (0.6035 against 0.6427); the box
    // in fy from the button's middle, the same height as LIMIT_BOX.
    val SINGLE_LIMIT_DY = doubleArrayOf(-0.053, -0.025)
    // The same dialog is the game's one purchase widget: the DD Box sells
    // Stamina for Data Shards through it (corpus/bond/press_Raise_182012,
    // open_the_Digimon_page_133000, both read as this dialog on
    // 2026-10-19). What says Camp Coin is the coin on the price button, a
    // star in orange (H 10-25, S 200+, V 200+) where the Data Shard is a
    // cyan diamond: the orange share of the button's left third, 0.0616
    // to 0.1034 on the ten purchase dialogs of the rows and of instance 1,
    // 0.0000 on the DD Box's two. COIN_MIN is the middle.
    val COIN_ORANGE = Dungeon.Hsv(intArrayOf(8, 160, 180), intArrayOf(28, 255, 255))
    const val COIN_MIN = 0.03

    /** The purchase dialog: the four squares, the count, the price button and whether the coins reach. */
    /**
     * The purchase dialog. The four squares and the count are null on the
     * dialog of an item with one piece left (SINGLE_PLATE_FY): it has no row,
     * only its "n / m" over the price button.
     */
    data class BuyDialog(val minus10: Explore.Target?, val minus: Explore.Target?, val plus: Explore.Target?,
                         val plus10: Explore.Target?, val count: Int?, val buy: Explore.Target,
                         val affordable: Boolean, val limit: Pair<Int, Int>?) {
        val close get() = CLOSE_SPOT
        fun toOracle(): Map<String, Any?> = mapOf(
            "minus10" to minus10?.toOracle(), "minus" to minus?.toOracle(), "plus" to plus?.toOracle(),
            "plus10" to plus10?.toOracle(), "count" to count, "buy" to buy.toOracle(),
            "affordable" to affordable, "limit" to limit?.toList())
    }

    /** The red share of a crop, both ends of the hue circle. */
    fun redShare(crop: Mat): Double {
        val a = Cv.hsvMask(crop, RED_LOW); val b = Cv.hsvMask(crop, RED_HIGH)
        val m = Mat()
        Core.bitwise_or(a, b, m)
        val s = Cv.share(m)
        a.release(); b.release(); m.release()
        return s
    }

    /** The four blue squares of the row, left to right (the probe prints them). */
    fun squares(img: Mat): List<Runner.Blob> = buttonBlobs(img, Dungeon.BLUE, ROW_BAND, SQUARE_W, SQUARE_H, SQUARE_MIN_SHARE)

    /** The orange share of the price button's left third: the Camp Coin's star (the probe prints it). */
    fun coinShare(img: Mat, buy: Runner.Blob): Double {
        val crop = box(img, WINDOW, buy.fx0, buy.fx0 + buy.width / 3, buy.fy0, buy.fy1) ?: return 0.0
        val m = Cv.hsvMask(crop, COIN_ORANGE)
        return Cv.share(m).also { m.release() }
    }

    /**
     * The purchase dialog, or null: its plate at its height, the four
     * squares in one row and the turquoise price button under them. The
     * count is read between - and +; `affordable` is false where the price
     * or the count is red (Summon.priceIsRed is the pattern: the game turns
     * the number red before it says anything else).
     */
    fun buyDialog(img: Mat): BuyDialog? {
        if (plateAt(img, DIALOG_PLATE_FY) == null) return singleDialog(img)
        val row = squares(img)
        if (row.size != 4) return null
        val ys = row.map { it.cy }
        if (ys.max() - ys.min() > PLATE_DY) return null
        val buy = buttonBlobs(img, TURQUOISE, BUY_BAND, BUY_W).firstOrNull() ?: return null
        if (coinShare(img, buy) < COIN_MIN) return null
        val cy = ys.average()
        val countCrop = box(img, WINDOW, row[1].fx1 + 0.01, row[2].fx0 - 0.01, cy - 0.016, cy + 0.016)
        val count = countCrop?.let { readNumber(it, WHITE) ?: readNumber(it, null) }
        val countRed = countCrop?.let { redShare(it) >= RED_MIN } ?: false
        val buyCrop = box(img, WINDOW, buy.fx0, buy.fx1, buy.fy0, buy.fy1)
        val priceRed = buyCrop?.let { redShare(it) >= RED_MIN } ?: false
        val limitCrop = box(img, WINDOW, LIMIT_BOX[0], LIMIT_BOX[1], cy + LIMIT_BOX[2], cy + LIMIT_BOX[3])
        val limit = limitCrop?.let { readPair(it) }
        fun t(b: Runner.Blob) = Explore.Target(b.cx, b.cy, WINDOW)
        return BuyDialog(t(row[0]), t(row[1]), t(row[2]), t(row[3]), count, t(buy), !countRed && !priceRed, limit)
    }

    /**
     * The dialog of an item with one piece left (SINGLE_PLATE_FY), or null:
     * its plate at its height, no square in the row's band, and the turquoise
     * price button with the Camp Coin on it -- the same button and the same
     * coin as the dialog with a row. `affordable` is the price not red.
     */
    private fun singleDialog(img: Mat): BuyDialog? {
        plateAt(img, SINGLE_PLATE_FY) ?: return null
        if (squares(img).isNotEmpty()) return null
        val buy = buttonBlobs(img, TURQUOISE, BUY_BAND, BUY_W).firstOrNull() ?: return null
        if (coinShare(img, buy) < COIN_MIN) return null
        val buyCrop = box(img, WINDOW, buy.fx0, buy.fx1, buy.fy0, buy.fy1)
        val priceRed = buyCrop?.let { redShare(it) >= RED_MIN } ?: false
        val limitCrop = box(img, WINDOW, LIMIT_BOX[0], LIMIT_BOX[1], buy.cy + SINGLE_LIMIT_DY[0], buy.cy + SINGLE_LIMIT_DY[1])
        val limit = limitCrop?.let { readPair(it) }
        return BuyDialog(null, null, null, null, null, Explore.Target(buy.cx, buy.cy, WINDOW), !priceRed, limit)
    }

    // ------------------------------------------------------------------------
    // Digits
    // ------------------------------------------------------------------------
    // Read as the dungeon's counters are (Dungeon.badgeGlyphs, readDigit):
    // the crop four times its size, cubic, the characters as connected
    // clumps of the digits' colour, a digit by its shape. White for a count
    // ("2/2", the dialog's n); pink for a price (H 162-168 on the white
    // plate); red for a sold-out card's "0/1" and a count the coins do not
    // reach, which is read by whichever of white and red gives a number.
    // White up to S 80: the dialog's "n / m" under the row is a pale blue
    // (H 93, S 57-59, V 206-255), the counts on the cards and in the box S
    // 0-2.
    const val GLYPH_SCALE = 4
    val WHITE = Dungeon.Hsv(intArrayOf(0, 0, 200), intArrayOf(179, 80, 255))
    val PRICE_PINK = Dungeon.Hsv(intArrayOf(150, 60, 180), intArrayOf(176, 255, 255))
    const val GLYPH_MIN_H = 0.30
    const val GLYPH_MIN_AREA = 200
    // A comma is under half a digit tall; a slash is narrower than a digit
    // and taller than the digits beside it (Dungeon.SLASH_MIN_RATIO).
    const val COMMA_MAX_H = 0.55
    const val SLASH_MAX_WH = 0.55

    class Glyphs(val mask: Mat, val boxes: List<IntArray>)

    /** The characters of [colour] in [crop] (null: white or red), left to right, at GLYPH_SCALE. */
    fun glyphs(crop: Mat, colour: Dungeon.Hsv?): Glyphs {
        val big = Mat()
        Imgproc.resize(crop, big, Size((crop.cols() * GLYPH_SCALE).toDouble(), (crop.rows() * GLYPH_SCALE).toDouble()),
                       0.0, 0.0, Imgproc.INTER_CUBIC)
        val mask = if (colour != null) Cv.hsvMask(big, colour) else {
            val w = Cv.hsvMask(big, WHITE); val a = Cv.hsvMask(big, RED_LOW); val b = Cv.hsvMask(big, RED_HIGH)
            val m = Mat(); Core.bitwise_or(w, a, m); Core.bitwise_or(m, b, m)
            w.release(); a.release(); b.release()
            m
        }
        big.release()
        val (n, stats) = Cv.components(mask)
        val out = ArrayList<IntArray>()
        for (i in 1 until n) {
            val s = stats[i]
            val ratio = s[2] / maxOf(s[3], 1).toDouble()
            if (s[4] > GLYPH_MIN_AREA && 0.1 < ratio && ratio < 1.2 && s[3] > GLYPH_MIN_H * mask.rows()) out.add(s)
        }
        return Glyphs(mask, out.sortedBy { it[0] })
    }

    // A piece of ink beside the digits that was not read as one -- a digit
    // broken in two by the smallest canvas (the "5" of a landscape "500",
    // which read as "00" on one frame of 2026-10-19) -- refuses the number:
    // any clump of at least this share of a digit's height and half the
    // narrowest digit's width whose columns touch no digit's. A comma is
    // narrower than that (a third of a digit) and is not a stray.
    const val STRAY_MIN_H = 0.15
    const val STRAY_MIN_W = 0.5

    /** A number of [colour]'s digits in [crop], commas dropped, or null: all or nothing, as Dungeon.readCounter. */
    fun readNumber(crop: Mat, colour: Dungeon.Hsv?): Int? {
        val g = glyphs(crop, colour)
        try {
            if (g.boxes.isEmpty()) return null
            val tallest = g.boxes.maxOf { it[3] }
            val digits = g.boxes.filter { it[3] >= COMMA_MAX_H * tallest }
            if (digits.isEmpty()) return null
            if (stray(g.mask, digits, tallest)) return null
            return Dungeon.readCounter(g.mask, digits)
        } finally {
            g.mask.release()
        }
    }

    /** Is there ink of a digit's size in [mask] whose columns touch none of [digits]? See STRAY_MIN_H. */
    private fun stray(mask: Mat, digits: List<IntArray>, tallest: Int): Boolean {
        val (n, stats) = Cv.components(mask)
        val narrowest = digits.minOf { it[2] }
        for (i in 1 until n) {
            val s = stats[i]
            if (s[3] < STRAY_MIN_H * tallest || s[2] < STRAY_MIN_W * narrowest) continue
            if (s[2] / maxOf(s[3], 1).toDouble() >= 1.2) continue
            val x0 = s[0]; val x1 = s[0] + s[2]
            if (digits.none { d -> x0 < d[0] + d[2] && x1 > d[0] }) return true
        }
        return false
    }

    /**
     * "n/m" in [crop], white or red, or null: the slash is the tallest
     * character and narrower than SLASH_MAX_WH of its height, with at least
     * one digit on either side.
     */
    fun readPair(crop: Mat): Pair<Int, Int>? {
        val g = glyphs(crop, null)
        try {
            if (g.boxes.size < 3) return null
            val slash = g.boxes.maxBy { it[3] }
            if (slash[2] / maxOf(slash[3], 1).toDouble() > SLASH_MAX_WH) return null
            val left = g.boxes.filter { it[0] + it[2] <= slash[0] }
            val right = g.boxes.filter { it[0] >= slash[0] + slash[2] }
            if (left.isEmpty() || right.isEmpty()) return null
            val n = Dungeon.readCounter(g.mask, left) ?: return null
            val m = Dungeon.readCounter(g.mask, right) ?: return null
            return n to m
        } finally {
            g.mask.release()
        }
    }
}

/**
 * The shop's items by their pictures: fifteen templates out of the APK,
 * `templates/camp_<item>.png`, each the icon's box ([Camp.ICON_BOX]) of a
 * card for sale at 1080 x 1920 (cut by `gradlew :core:campProbe
 * --args="cut ..."`). A card's icon is cut to the same box, scaled to the
 * template's size and compared in colour by normalised correlation over
 * the three channels: in grey the two passes, a pale ticket and a gold
 * one of the same shape, stood within 0.03 to 0.09 of each other on the
 * small rows and once the wrong way round (720 x 1600 and landscape,
 * 2026-10-19). A sold-out card is not named: its picture is dimmed under
 * a "SOLD OUT" stamp and correlates 0.05 to 0.12 with its own template.
 */
class CampIcons(private val assets: AssetSource) {

    companion object {
        /** The catalogue in the grid's order (PLAN_CAMP.md 2.4). */
        val NAMES = listOf("gate_blue", "gate_pink", "gate_yellow", "gate_purple", "gate_green",
                           "battle_pass", "grand_prix_pass", "acceleration_ticket",
                           "digivice", "d3", "d_ark", "d_scanner", "digivice_ic", "xros_loader", "digivice_2")
        /** The side of a template as cut, 140 px of the card's 210 at 1080 x 1920. */
        const val TEMPLATE_W = 140.0
        /**
         * The least correlation a name is believed at, and the least margin
         * over the runner-up. Over the 50 live cards of the rows and of
         * instance 1's two shop frames, in colour: the right name 0.87 to
         * 1.00 and the runner-up at most 0.86 (purple against pink, 0.863
         * under 0.986 at 1920), the least margin 0.122; a sold-out card's
         * own template 0.05 to 0.12. SCORE_MIN is the middle of 0.12 and
         * 0.87, MARGIN_MIN half the least margin seen, with no wrong name
         * in colour to set the other side.
         */
        const val SCORE_MIN = 0.50
        const val MARGIN_MIN = 0.06
    }

    private fun decode(path: String): Mat? {
        val bytes = assets.bytes(path) ?: return null
        val mat = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
        return if (mat.empty()) null else mat
    }

    val icons: Map<String, Mat> by lazy {
        NAMES.associateWith { decode("templates/camp_$it.png") ?: throw IllegalStateException("templates/camp_$it.png is missing") }
    }

    /** Every template's correlation with [crop], best first (the probe prints them). */
    fun scores(crop: Mat): List<Pair<String, Double>> {
        val small = Mat()
        Imgproc.resize(crop, small, Size(TEMPLATE_W, TEMPLATE_W), 0.0, 0.0, Imgproc.INTER_AREA)
        val out = icons.map { (name, t) ->
            val r = Mat()
            Imgproc.matchTemplate(small, t, r, Imgproc.TM_CCOEFF_NORMED)
            val v = r.get(0, 0)[0]
            r.release()
            name to v
        }.sortedByDescending { it.second }
        small.release()
        return out
    }

    /** The item the crop shows and its score, or null where no template is near enough or two are. */
    fun item(crop: Mat): Pair<String, Double>? {
        if (crop.rows() < 8 || crop.cols() < 8) return null
        val s = scores(crop)
        if (s.isEmpty() || s[0].second < SCORE_MIN) return null
        if (s.size > 1 && s[0].second - s[1].second < MARGIN_MIN) return null
        return s[0]
    }
}
