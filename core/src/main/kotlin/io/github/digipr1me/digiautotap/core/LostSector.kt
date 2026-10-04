package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * The Lost Sector Tower (PLAN_DAILY_LOST_SECTOR_PRESETS.md 2.2, 3.2, 4.1
 * point 4): the tile in the icon row above the field opens the **Crests**
 * page -- a grid of crests with their levels, and at its foot a card "Lost
 * Sector Tower" with a dark "Enter ->" pill -- and Enter raises the tower's
 * panel over it: the floor in a box, the boss, "Subjugation Rewards", and
 * one blue button, **Subjugate**, in the middle.
 *
 * Measured on LDPlayer instance 0 (the player's account, floor 120, the
 * highest) on 2026-09-28/29 with the app stopped: the page and the panel on
 * 1080 x 1920 and six format rows (1080 x 2235, 2340, the whole 2520, 720 x
 * 1600, 1200 x 1920, landscape), and one Subjugate, which the game answered
 * with a toast and no battle: "You have reached the highest floor and cannot
 * proceed any further." What a run shows at its end was therefore never
 * seen (G4); there is no reader for it. The corpus already held one panel,
 * `corpus/passive/unclear_175706.png` (floor 88, another day), which every
 * reader here reads as its own.
 *
 * Every place is a fraction of the rectangle at [PAGE], the middle: the
 * Crests page's grid and card, the panel and the toast stand in the middle of
 * the headroom -- the Enter pill at display y 1970 at 1080 x 2340 and 2060 on
 * the whole 2520 frame (180 rows of headroom, half of them), y 1333 at 720 x
 * 1600 where 1920 scaled says 1313 (40 rows, half of them) -- while the
 * page's X, the white one of Summon.exitButton, stands at the bottom with the
 * HUD (fx 0.7934, fy 0.9567 on all seven rows at the bottom's rectangle).
 */
object LostSector {

    /** Where the Crests page's content, the panel and the toast stand (Dungeon.Anchor, V4). */
    val PAGE = Dungeon.Anchor.MIDDLE

    // ------------------------------------------------------------------------
    // The way in: the Crests tile
    // ------------------------------------------------------------------------
    // The tile right of the Digivice in the row above the field, the sun
    // crest on it. A constant and not a reader: the row stands still on every
    // display. Measured on 2026-09-29 over 90 main screens -- every main_* of
    // corpus/formats and corpus/tall and the seven format rows of this
    // measurement, 720 x 1280 to 2136 x 3200, cutouts, side insets, tablets,
    // landscape, V3 rows cut and whole -- the light-blue tiles of the row
    // (H 95-108, S 170-245, V 200+) in window fractions:
    //
    //   tile         middle fx        middle fy        w              h
    //   Digivice     0.1695-0.1704    0.7745-0.7748    0.0764-0.0785  0.0432-0.0443
    //   Crests       0.2606-0.2613    0.7745-0.7751    0.0759-0.0778  0.0435-0.0443
    //   Overdrive    0.6904-0.6912    0.7745-0.7748    0.0764-0.0779  0.0432-0.0443
    //   Tactical     0.7821-0.7827    0.7745-0.7751    0.0762-0.0777  0.0435-0.0443
    //
    // A spread of 0.0008 against a tile 0.077 wide; the two frames it misses
    // on are the `double84` row, the game drawn for the display before. The
    // plan had guessed "fx 0.27, fy 0.77, orange" from a screenshot; the disc
    // is orange, its tile is the row's blue. The arrival is proved by the
    // page ([page]), never by the tap.
    val CRESTS_ICON = Explore.Target(0.2610, 0.7747)

    // ------------------------------------------------------------------------
    // The Crests page: the Enter pill
    // ------------------------------------------------------------------------
    // One colour, H 111 S 93 V 88, the pill's own, with "Enter" and the arrow
    // lighter inside it (the closing of findButtons fills them). Measured on
    // the eight pages there are (1080 x 1920 twice and six format rows) at
    // the middle's rectangle:
    //
    //   fx 0.3305-0.3321   fy 0.8604-0.8609   fw 0.2860-0.2865   fh 0.0312-0.0318
    //
    // Under the panel the same pill is dimmed to H 113 S 122 V 25 on every
    // panel frame there is, the corpus's of another day among them, which is
    // the panel's second witness ([subjugate]).
    val ENTER = Dungeon.Hsv(intArrayOf(108, 80, 75), intArrayOf(114, 106, 100))
    val ENTER_DIMMED = Dungeon.Hsv(intArrayOf(110, 110, 18), intArrayOf(116, 135, 32))
    const val ENTER_FX = 0.331
    const val ENTER_FY = 0.861
    const val ENTER_TOL = 0.03
    val ENTER_W = doubleArrayOf(0.25, 0.32)
    val ENTER_H = doubleArrayOf(0.025, 0.04)

    /** The Enter pill in [colour] at its place, or null. */
    private fun pill(img: Mat, colour: Dungeon.Hsv): Dungeon.Button? =
        Dungeon.findButtons(img, colour, minY = 0.0, anchor = PAGE).firstOrNull { b ->
            Dungeon.near(b.fx, ENTER_FX, ENTER_TOL) && Dungeon.near(b.fy, ENTER_FY, ENTER_TOL) &&
                b.fw in ENTER_W[0]..ENTER_W[1] && b.fh in ENTER_H[0]..ENTER_H[1]
        }

    /** The Crests page's Enter pill, lit, where it is tapped (at [PAGE]); null on any other screen. */
    fun enterButton(img: Mat): Dungeon.Button? = pill(img, ENTER)

    /**
     * The Enter pill dimmed under a window over the Crests page: the second
     * witness [subjugate] asks, on its own. A dialog over it that [subjugate]
     * does not name is a window of the tower's that DL1a never saw -- a panel
     * with a price, a counter or a second button on it -- and
     * LostSectorSkill parks on it rather than tapping anything there
     * (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.2 point 7). Added with DL4 on
     * 2026-09-29: the computation [subjugate] already ran, made visible; no
     * reader's answer moved, and it is in no oracle family.
     */
    fun dimmedEnter(img: Mat): Dungeon.Button? = pill(img, ENTER_DIMMED)

    /**
     * The Crests page, nothing over it: the lit Enter pill and the page's
     * white X. Null on any other screen, the panel over the page among them
     * (the pill is dimmed there and the X is gone). The answer is the pill.
     */
    fun page(img: Mat): Dungeon.Button? {
        val enter = enterButton(img) ?: return null
        return if (Summon.exitButton(img) != null) enter else null
    }

    // ------------------------------------------------------------------------
    // The panel: Subjugate
    // ------------------------------------------------------------------------
    // One blue button, centred, at the middle's rectangle: fx 0.4752-0.4768,
    // fy 0.7915-0.7919, fw 0.2597-0.2601, fh 0.0535-0.0541 on the nine panels
    // there are (1080 x 1920 twice, the six format rows, and the corpus's of
    // another day, floor 88). `recognise` names the panel a dialog with that
    // button as its Attempt, and asks this reader to take it in the middle
    // on a display with headroom, where the tickets' panel stands at the top. The
    // button alone is not the panel: the Super Hologram Device's Apply is as
    // tall and centred too (corpus/passive/unclear_130011, fw 0.3173), and
    // the dungeon panel's lone Attempts are 0.199 to 0.246 wide. So the width,
    // between them, and the dimmed Enter pill under the panel.
    val SUBJUGATE_W = doubleArrayOf(0.25, 0.28)
    val SUBJUGATE_H = doubleArrayOf(0.045, 0.065)

    /**
     * The tower's panel and its Subjugate button, read at [PAGE]; null on any
     * other screen. A tap on it goes to that rectangle.
     */
    fun subjugate(img: Mat): Dungeon.Button? {
        val r = Dungeon.recogniseAt(img, PAGE)
        if (r.state != Dungeon.DIALOG) return null
        if (r.clear != null || r.ad != null || r.party != null) return null
        val b = r.attempt ?: return null
        if (!Dungeon.near(b.fx, Dungeon.POS_CENTER)) return null
        if (b.fw !in SUBJUGATE_W[0]..SUBJUGATE_W[1] || b.fh !in SUBJUGATE_H[0]..SUBJUGATE_H[1]) return null
        return if (pill(img, ENTER_DIMMED) != null) b else null
    }

    // ------------------------------------------------------------------------
    // The highest floor
    // ------------------------------------------------------------------------
    // A Subjugate on the highest floor starts nothing: a toast over the
    // panel, "You have reached the highest floor and cannot proceed any
    // further.", a navy box (H 107 S 241 V 94) with a cyan rim and white text,
    // for about a second -- on the frame 1.4 s after the tap and on none of
    // the 44 after it, 2.6 to 60 s. One frame is all there is
    // (corpus/lost_sector/panel_max_toast_013525): fx 0.476, fy 0.507, fw
    // 0.656, fh 0.063 at the middle's rectangle, 0.765 of its box the navy and
    // 0.137 white. The bands below are that frame's numbers with room either
    // side, not a spread, and the comment says so; what keeps them honest is
    // the panel under the toast, asked as well.
    val TOAST = Dungeon.Hsv(intArrayOf(105, 230, 85), intArrayOf(109, 250, 105))
    const val TOAST_FY = 0.507
    const val TOAST_TOL = 0.04
    val TOAST_W = doubleArrayOf(0.55, 0.75)
    val TOAST_H = doubleArrayOf(0.045, 0.08)
    const val TOAST_WHITE = 200.0
    const val TOAST_WHITE_MIN = 0.05

    /**
     * The toast that says the tower has no floor above this one, over the
     * tower's own panel; null on any other frame, and on the panel once the
     * toast is gone.
     */
    fun maxFloor(img: Mat): Dungeon.Button? {
        val toast = Dungeon.findButtons(img, TOAST, minY = 0.0, anchor = PAGE).firstOrNull { b ->
            Dungeon.near(b.fx, Dungeon.POS_CENTER) && Dungeon.near(b.fy, TOAST_FY, TOAST_TOL) &&
                b.fw in TOAST_W[0]..TOAST_W[1] && b.fh in TOAST_H[0]..TOAST_H[1]
        } ?: return null
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, PAGE)
        val crop = Py.crop(img, Py.roundInt(y0 + (toast.fy - toast.fh / 2) * gh), Py.roundInt(y0 + (toast.fy + toast.fh / 2) * gh),
                           Py.roundInt(x0 + (toast.fx - toast.fw / 2) * gw), Py.roundInt(x0 + (toast.fx + toast.fw / 2) * gw))
            ?: return null
        val grey = Cv.gray(crop)
        val white = org.opencv.core.Mat()
        Imgproc.threshold(grey, white, TOAST_WHITE, 255.0, Imgproc.THRESH_BINARY)
        val share = Cv.share(white)
        grey.release(); white.release()
        if (share < TOAST_WHITE_MIN) return null
        return if (subjugate(img) != null) toast else null
    }
}
