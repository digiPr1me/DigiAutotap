package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * The Missions tile on the main screen, the Missions window behind it and
 * its EX Missions tab (PLAN_EX_MISSIONS.md, measured in EX1 on LDPlayer
 * instance 1, 2026-09-28/29, with the app's core stopped).
 *
 * What the game draws, from the frames of that measurement
 * (corpus/missions/, and the missions and ex_missions rows of
 * corpus/formats/):
 *
 *  * The tile stands in the right-hand column of the HUD, top of the
 *    column: a navy square with a clipboard (a yellow clip over a pale
 *    board) and "Missions" under it, and a pink "!" at its top right while
 *    any mission of the window has something to claim.
 *  * A tap on it opens one window, with no title bar and **no X**: three
 *    tabs along its bottom, Collection, Daily Missions and EX Missions, the
 *    lit one pale cyan and the other two saturated blue. It opens on
 *    Collection every time, whichever tab it was left on. Collection's
 *    cards have yellow Claim buttons in the same column as EX Missions'
 *    rows -- a Claim is only ever an EX Claim on a frame whose lit tab is
 *    EX Missions.
 *  * EX Missions is a tab of the same window, not a window of its own: a
 *    blue block on top ("EX Mission Achievement n", "Complete 10n EX
 *    Missions", its own Claim, grey until the count is reached), and under
 *    it a list of pale rows, each with a yellow Claim or a blue arrow.
 *    Claimable rows stand first. A tap on any yellow Claim in the list
 *    claims every claimable row at once, and a row whose next level is
 *    already reached comes back yellow.
 *  * The window closes by a tap in the dimmed field above it; the blue
 *    arrow of a row closes it too and goes wherever the mission is done
 *    (the main screen, for the rows measured).
 *
 * The findings: notes/missions.md, "The Missions tile stands in the HUD's
 * right-hand column, which hangs from the top of the display, and the
 * plate lies on it" and "The Missions window has no X and EX Missions is
 * its third tab".
 */
object Missions {

    // ------------------------------------------------------------------------
    // Where the pieces stand over the canvas ceiling (Dungeon.Anchor)
    // ------------------------------------------------------------------------
    /**
     * The right-hand column of the HUD stands at the **top** of the display,
     * not at the bottom as the auto button and the card tray do. Measured on
     * the whole main screens of the format rows, taken within twenty minutes
     * of each other: the clip's top edge at fy 0.1510 of the rectangle at
     * 1080 x 1920, and at the top anchor 0.1508 at 1080 x 2520 (180 rows of
     * headroom) and 0.1510 at 720 x 1600 (40), where the bottom's rectangle
     * puts the same pixels at 0.0762 at 2520. The column is laid into the
     * safe area from the top of the display like the bar above it, and the
     * canvas ceiling only adds rows between it and the battle. (The Events
     * star, in the same column, reads fy 0.1503 at 2520 against 0.2252 at
     * 1920 at the bottom anchor in oracle/runner.json: the same thing, on a
     * reader whose fy nobody compares.) notes/missions.md, "The Missions
     * tile stands in the HUD's right-hand column"; notes/formats.md, rule 4.
     */
    val HUD_TOP = Dungeon.Anchor.TOP

    /**
     * The window stands in the **middle**, like every popup: its tab row at
     * fy 0.7869 (unlit) at 1920 reads 0.7866 at the middle anchor on the
     * whole 2520 frame, 0.7493 at the bottom and 0.8239 at the top; 0.7865 at
     * 720 x 1600, 0.7864 in landscape.
     */
    val WINDOW = Dungeon.Anchor.MIDDLE

    // ------------------------------------------------------------------------
    // The tile
    // ------------------------------------------------------------------------
    // What is read is the clip: the one yellow in the column that sits on a
    // pale board inside navy -- the Events tile's yellow is a star on the
    // navy, the Battle Pass's a ticket twice as large, a damage number or a
    // "Counter" has no board under it and no navy around it. Measured over
    // the 431 frames oracle/director.json calls the main screen (2026-09-29)
    // and the six main screens of the format rows, every yellow clump of the
    // clip's size (w 0.020-0.036, h 0.004-0.011 of the rectangle) in the
    // column (fy 0.03-0.40 at HUD_TOP, fx0 from 0.50), with the pale share of
    // the box under it (the board, 0.1 to 1.2 clip widths down) and the navy
    // share of the box around it (0.6 clip widths either side, 0.3 up, 1.8
    // down):
    //
    //   the clip        429 frames   w 0.0265-0.0288  h 0.0063-0.0082
    //                                fill 0.698-0.784
    //                                pale 0.559-0.681  navy 0.263-0.365
    //   everything else 260 clumps   pale <= 0.416 where navy >= 0.15, and
    //                                navy <= 0.154 where pale >= 0.40
    //
    // The one clump that comes near on both is a yellow HP bar under a
    // small Digimon in front of Login Bonus (corpus/passive/counter_204048,
    // pale 0.416, navy 0.154). So PALE_MIN is 0.49 and NAVY_MIN 0.21, the
    // middles of the two gaps, and no frame has two clips. The two main
    // screens without one are the Gekkomon Run frames the auto button calls
    // main (runner/run_magenta_spikes_window_164514, bond/open_the_Digimon_
    // page_180642): no HUD at all. The Poco's uncut frames (passive/
    // phone_tall_*, runner/phone_tall_*) read the tile 0.027 lower (fy 0.197),
    // under the camera strip that was never cut from them, and the two
    // waterfall frames 0.038 further left (fx 0.7525): the column moves with
    // the safe area, and the reader goes by what it sees, not by a place.
    val CLIP_YELLOW = Dungeon.Hsv(intArrayOf(18, 120, 190), intArrayOf(35, 255, 255))
    val CLIP_BAND = doubleArrayOf(0.03, 0.40)
    val CLIP_FX0 = doubleArrayOf(0.50, 1.00)
    val CLIP_W = doubleArrayOf(0.020, 0.036)
    val CLIP_H = doubleArrayOf(0.004, 0.011)
    // Far under the smallest clip there is, landscape's 18 x 9 px on a 646 px
    // wide rectangle (0.00016 of it).
    const val CLIP_MIN_SHARE = 0.00003
    val BOARD_PALE = Dungeon.Hsv(intArrayOf(60, 0, 215), intArrayOf(115, 90, 255))
    const val PALE_MIN = 0.49
    const val NAVY_MIN = 0.21
    // The tap: the tile's middle, 0.92 clip widths under the clip's middle
    // at 1920 (the navy square x 851-952, y 234-324; the clip x 885-917,
    // y 242-258), in the clip's own size so that it scales with the tile.
    const val TILE_TAP_DOWN = 0.9

    /**
     * The rows of the tile, clip to label, at HUD_TOP: fy 0.1505 to 0.1924 at
     * 1920 (the clip's top edge to the navy square's bottom). What a skill
     * clears the overlay off before it reads the tile: the plate at the
     * dot's default place (fy 0.1555) takes the clip on every portrait
     * format, see [tile].
     */
    val TILE_ROWS = doubleArrayOf(0.145, 0.195)

    /** One clump the tile reader weighed: the clip's box and what surrounds it (MissionsProbe). */
    class Clip(val blob: Runner.Blob, val pale: Double, val navy: Double)

    private fun shareIn(img: Mat, colour: Dungeon.Hsv, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val sub = Py.crop(img, maxOf(0, y0), minOf(img.rows(), y1), maxOf(0, x0), minOf(img.cols(), x1))
            ?: return 0.0
        val m = Cv.hsvMask(sub, colour)
        val s = Cv.share(m)
        m.release()
        return s
    }

    /** Every clump of the clip's colour and size in the column, weighed (the probe prints them all). */
    fun clipCandidates(img: Mat): List<Clip> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, HUD_TOP)
        val out = ArrayList<Clip>()
        for (b in Runner.blobs(img, CLIP_YELLOW, band = CLIP_BAND, minShare = CLIP_MIN_SHARE, anchor = HUD_TOP)) {
            if (b.fx0 !in CLIP_FX0[0]..CLIP_FX0[1]) continue
            if (b.width !in CLIP_W[0]..CLIP_W[1] || b.height !in CLIP_H[0]..CLIP_H[1]) continue
            val px0 = x0 + b.fx0 * gw; val px1 = x0 + b.fx1 * gw
            val py0 = y0 + b.fy0 * gh; val py1 = y0 + b.fy1 * gh
            val cw = px1 - px0
            val pale = shareIn(img, BOARD_PALE, Py.int(px0), Py.int(py1 + 0.1 * cw), Py.int(px1), Py.int(py1 + 1.2 * cw))
            val navy = shareIn(img, Runner.TILE_NAVY, Py.int(px0 - 0.6 * cw), Py.int(py0 - 0.3 * cw),
                               Py.int(px1 + 0.6 * cw), Py.int(py1 + 1.8 * cw))
            out.add(Clip(b, pale, navy))
        }
        return out
    }

    /** The clip of the tile on this frame, or null. */
    private fun clip(img: Mat): Clip? =
        clipCandidates(img).firstOrNull { it.pale >= PALE_MIN && it.navy >= NAVY_MIN }

    /**
     * Where to tap the Missions tile on the plain main screen, or null: the
     * tile's middle, hung off its clip (see CLIP_YELLOW). Asked on the plain
     * main screen only, as the Events star is: a tile under a dialog is
     * dimmed and never tapped.
     *
     * **The overlay's plate lies on the clip.** The dot and its plate masked
     * in where the app draws them, at every fy of the dot's strip in steps
     * of 0.005, over the six main screens of the format rows (`gradlew
     * :core:missionsProbe --args="tile ... mask=0.06:0.16:0.005"`): the tile
     * reads with the dot at 0.060 to 0.140 at 1080 x 1920 and 1200 x 1920,
     * 0.060 to 0.125 at 1080 x 2340 and 0.060 to 0.100 at 720 x 1600, and is
     * lost above that -- at the default place, 0.1555, on all four. Over the
     * canvas ceiling it is the other way round: at 1080 x 2520 the dot at
     * 0.060 to 0.125 stands in the headroom on the tile, which the column
     * keeps at the top of the display, and from 0.130 it is below it. In
     * landscape the dot stands in the black beside the canvas and costs
     * nothing. The window's readers and the director lose nothing at any fy
     * on any row. So a skill moves the dot off [TILE_ROWS] before it reads
     * this -- Capture.overlayClear, with the rows turned into the HUD's
     * fractions through Dungeon.bottomFy(fy, HUD_TOP, ...), as PresetSkill
     * does for the Digivice bar, so that Dot.clearFy finds the place off
     * them on either side of the ceiling (notes/missions.md, "The Missions
     * tile stands in the HUD's right-hand column"; notes/formats.md, rule
     * 13).
     */
    fun tile(img: Mat): Explore.Target? {
        if (Dungeon.autoButton(img) == null) return null
        val c = clip(img) ?: return null
        val (_, _, gw, gh) = Dungeon.gameRect(img, HUD_TOP)
        val cwPx = c.blob.width * gw
        return Explore.Target(c.blob.cx, c.blob.cy + TILE_TAP_DOWN * cwPx / gh, HUD_TOP)
    }

    // The pink "!" at the tile's top right (x 926-958, y 214-246 at 1920,
    // H 163 S 232 V 255 with a white "!" in it): state, and only for the
    // log. It stands whenever any tab of the window has something to claim,
    // not only EX Missions, so it is no witness for a visit.
    val BADGE_PINK = Dungeon.Hsv(intArrayOf(155, 150, 190), intArrayOf(172, 255, 255))
    // The box the badge stands in, in clip widths from the clip's middle:
    // right 0.6 to 2.0, up 1.4 to down 0.2. Its pink share over the 429
    // main screens the tile reads on: 0.191 to 0.269 on the 426 with the
    // "!" (the lowest a Poco frame, passive/phone_tall_no_bubble_slot_left),
    // 0.000 on the three without it (passive/bond-bug-1, bond-not-the-
    // middle_140838 and _142704, looked at). BADGE_MIN is the middle.
    val BADGE_BOX = doubleArrayOf(0.6, 2.0, -1.4, 0.2)
    const val BADGE_MIN = 0.095

    /** Is the tile's "!" up? Null where the tile is not read. */
    fun badge(img: Mat): Boolean? {
        if (Dungeon.autoButton(img) == null) return null
        return badgeShare(img)?.let { it >= BADGE_MIN }
    }

    /** The pink share of [BADGE_BOX] around the clip, or null without a clip (the probe prints it). */
    fun badgeShare(img: Mat): Double? {
        val c = clip(img) ?: return null
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, HUD_TOP)
        val cw = c.blob.width * gw
        val cx = x0 + c.blob.cx * gw
        val cy = y0 + c.blob.cy * gh
        val b = BADGE_BOX
        return shareIn(img, BADGE_PINK, Py.int(cx + b[0] * cw), Py.int(cy + b[2] * cw),
                       Py.int(cx + b[1] * cw), Py.int(cy + b[3] * cw))
    }

    // ------------------------------------------------------------------------
    // The window and its tabs
    // ------------------------------------------------------------------------
    // The tab row along the window's bottom: the lit tab pale cyan (H 98,
    // S 104, V 255), the other two saturated blue (H 99, S 255, V 219).
    // Over the 41 frames of the measurement and every format row, each tab
    // is 0.2124-0.2139 wide; the unlit ones 0.0317-0.0323 tall at fy
    // 0.7864-0.7869, the lit one 0.0360-0.0368 at 0.7886-0.7889; the three
    // middles 0.2554-0.2570, 0.4752-0.4764, 0.6950-0.6962. What else is in
    // the band: Collection's "Skill Cards" sub-tab (blue, 0.2467 wide,
    // 0.0263 tall, fy 0.7399), and the window growing out of the tile, whose
    // three tabs are all pale and 0.18 wide at fy 0.7439 for half a second
    // (corpus/missions/missions_opening_*). So the row is exactly one lit and
    // two blue tabs of a tab's size, side by side at a tab's pitch.
    val TAB_LIT = Dungeon.Hsv(intArrayOf(93, 70, 225), intArrayOf(104, 145, 255))
    val TAB_BLUE = Dungeon.Hsv(intArrayOf(96, 200, 180), intArrayOf(106, 255, 240))
    val TAB_BAND = doubleArrayOf(0.70, 0.86)
    const val TAB_MIN_SHARE = 0.003
    val TAB_W = doubleArrayOf(0.195, 0.232)
    val TAB_H = doubleArrayOf(0.028, 0.042)
    // One row: the lit tab stands 0.002 lower than the other two, and every
    // tab's middle within this of the others'.
    const val TAB_ROW_DY = 0.008
    // Middle to middle, 0.2198-0.2206 on every frame.
    val TAB_PITCH = doubleArrayOf(0.205, 0.235)
    const val COLLECTION = 0
    const val DAILY = 1
    const val EX = 2

    /**
     * Where the window is closed: a tap in the dimmed field above it, at the
     * director's neutral spot (Director.NEUTRAL_TAP_FX/FY), in the window's
     * rectangle. The window has no X. Measured by hand on every format row
     * (568,141 at 1080 x 1920; 574,172 at 2340; 382,115 at 720 x 1600; 628,141
     * at 1200 x 1920; 975,79 in landscape): the main screen back within 0.66 s
     * at 1920, and nothing under the tap opened (notes/missions.md, "The
     * Missions window has no X").
     */
    val CLOSE_SPOT = Explore.Target(Director.NEUTRAL_TAP_FX, Director.NEUTRAL_TAP_FY, WINDOW)

    /** The Missions window: which tab is lit, the three tabs' middles (tap targets), and where it closes. */
    data class MissionsWindow(val tab: Int, val tabs: List<Explore.Target>, val anchor: Dungeon.Anchor = WINDOW) {
        /** The "EX Missions" tab: the one thing the task taps in this window besides [CLOSE_SPOT]. */
        val exButton get() = tabs[EX]
        val close get() = CLOSE_SPOT
        fun toOracle(): Map<String, Any?> =
            mapOf("tab" to tab, "tabs" to tabs.map { it.toOracle() })
    }

    /** Every blob of the two tab colours in the band, left to right (the probe prints them). */
    fun tabBlobs(img: Mat, anchor: Dungeon.Anchor = WINDOW): List<Pair<Runner.Blob, String>> {
        val out = ArrayList<Pair<Runner.Blob, String>>()
        for (b in Runner.blobs(img, TAB_LIT, band = TAB_BAND, minShare = TAB_MIN_SHARE, anchor = anchor)) out.add(b to "lit")
        for (b in Runner.blobs(img, TAB_BLUE, band = TAB_BAND, minShare = TAB_MIN_SHARE, anchor = anchor)) out.add(b to "blue")
        return out.sortedBy { it.first.fx0 }
    }

    /** The Missions window, whichever tab is lit, or null. See TAB_LIT. */
    fun window(img: Mat): MissionsWindow? {
        val tabs = tabBlobs(img).filter { (b, _) ->
            b.width in TAB_W[0]..TAB_W[1] && b.height in TAB_H[0]..TAB_H[1]
        }
        if (tabs.size != 3) return null
        if (tabs.count { it.second == "lit" } != 1) return null
        val ys = tabs.map { it.first.cy }
        if (ys.max() - ys.min() > TAB_ROW_DY) return null
        for (i in 1 until 3) {
            val pitch = tabs[i].first.cx - tabs[i - 1].first.cx
            if (pitch !in TAB_PITCH[0]..TAB_PITCH[1]) return null
        }
        val lit = tabs.indexOfFirst { it.second == "lit" }
        return MissionsWindow(lit, tabs.map { Explore.Target(it.first.cx, it.first.cy, WINDOW) })
    }

    // ------------------------------------------------------------------------
    // The EX Missions tab
    // ------------------------------------------------------------------------
    // The block on top is one flat blue, H 105 S 219 V 156: on all 24 EX
    // frames of the measurement and the format rows one blob of it 0.6254-
    // 0.6280 wide and 0.1025-0.1032 tall from fy 0.2072-0.2076, fill 0.69-
    // 0.70 (its text and bar are holes). Collection's cards hold a blue panel
    // of nearly that size lower down (fy 0.25, H 104 S 233 V 147), which
    // gives no blob of this colour at all on the 8 Collection frames, and the
    // list rows under the block are pale (S 25, V 242). The limits below are
    // loose around the one shape the block has.
    val HEADER_BLUE = Dungeon.Hsv(intArrayOf(102, 210, 146), intArrayOf(108, 228, 166))
    val HEADER_BAND = doubleArrayOf(0.15, 0.40)
    const val HEADER_W_MIN = 0.50
    val HEADER_FY0 = doubleArrayOf(0.19, 0.23)
    val HEADER_H = doubleArrayOf(0.07, 0.13)

    // The Claim buttons of the tab, yellow as the event's (Runner.CLAIM_YELLOW:
    // H 26 S 213 V 255 here too), 0.1254-0.1277 wide and 0.0278-0.0286 tall,
    // middles at fx 0.7036-0.7055 -- Collection's at 0.6997-0.7006, a card's
    // pitch apart. The header's at fy 0.2586, the list's at 0.3631 and one
    // row's pitch, 0.0899, under each other (0.3628 to 0.3631 on every
    // format row), as the tab opens: the list is not scrolled then.
    val CLAIM_FX = doubleArrayOf(0.66, 0.75)
    val CLAIM_W = doubleArrayOf(0.10, 0.15)
    val CLAIM_H = doubleArrayOf(0.020, 0.040)
    const val HEADER_ROW_FY = 0.2586
    const val LIST_ROW_FY = 0.3631
    const val ROW_PITCH = 0.0899

    /** The EX Missions tab: the window, and its header block. */
    data class ExWindow(val window: MissionsWindow, val header: Runner.Blob) {
        val anchor get() = window.anchor
        fun toOracle(): Map<String, Any?> = mapOf("header" to header.toOracle())
    }

    /** A yellow Claim of the EX tab: its row (1 the header, 2 the first list row) and where to tap it. */
    data class ExClaim(val row: Int, val fx: Double, val fy: Double, val anchor: Dungeon.Anchor = WINDOW) {
        val target get() = Explore.Target(fx, fy, anchor)
        fun toOracle(): Map<String, Any?> = mapOf("row" to row, "fx" to fx, "fy" to fy)
    }

    /** The EX Missions tab of the window, or null: the window with EX Missions lit and its header block. */
    fun exWindow(img: Mat): ExWindow? {
        val w = window(img) ?: return null
        if (w.tab != EX) return null
        for (b in Runner.blobs(img, HEADER_BLUE, band = HEADER_BAND, minShare = 0.02, anchor = w.anchor)) {
            if (b.width >= HEADER_W_MIN && b.fy0 in HEADER_FY0[0]..HEADER_FY0[1] &&
                b.height in HEADER_H[0]..HEADER_H[1]) return ExWindow(w, b)
        }
        return null
    }

    /** Every yellow Claim in the column of the Claim buttons, top first (the probe prints them). */
    fun claimCandidates(img: Mat, anchor: Dungeon.Anchor = WINDOW): List<Runner.Blob> =
        Runner.blobs(img, Runner.CLAIM_YELLOW, band = doubleArrayOf(0.15, 0.85), minShare = 0.002, anchor = anchor)
            .filter { it.width in CLAIM_W[0]..CLAIM_W[1] && it.height in CLAIM_H[0]..CLAIM_H[1] }
            .sortedBy { it.fy0 }

    /**
     * Every yellow Claim of [ex], top first, each with its row: 1 inside the
     * header block, else counted in list rows from [LIST_ROW_FY]. State: a
     * row turns yellow as the battle counts on. A tap on any yellow list
     * Claim claims every claimable row at once (notes/missions.md, "The
     * Missions window has no X").
     */
    fun exClaims(img: Mat, ex: ExWindow): List<ExClaim> {
        val out = ArrayList<ExClaim>()
        for (b in claimCandidates(img, ex.anchor)) {
            if (b.cx !in CLAIM_FX[0]..CLAIM_FX[1]) continue
            val row = if (b.cy <= ex.header.fy1) 1
                      else 2 + Math.round((b.cy - LIST_ROW_FY) / ROW_PITCH).toInt()
            out.add(ExClaim(row, b.cx, b.cy, ex.anchor))
        }
        return out
    }

}
