package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Gekkomon Run, the endless runner of the Beatbreak event: the readers of
 * runner.py, line for line (PLAN_GEKKOMON_RUN.md in the laboratory holds
 * every number below and how it was measured, on 2026-09-19 in LDPlayer).
 * The loop that uses them is [RunnerSkill].
 *
 * Everything here is window space (Dungeon.gameRect), as everywhere: a
 * fraction means the same place on an ADB frame, a window frame and a
 * phone. The oracle family `runner` holds every reader here to
 * `oracle/runner.json` over `corpus/runner/`, which carries both frame
 * shapes of every dialog the laboratory kept.
 *
 * What is **not** here: the PC's MixedCapture, its freeze watch and its
 * mouse nudge. Those were the PC's own trouble -- an ADB screenshot took
 * 0.6 s there, so the frames came from the emulator window, and LDPlayer
 * stops drawing that window when nobody moves the mouse. On the phone
 * there is one capture, `takeScreenshot`, and it hands over a fresh frame
 * or a CaptureError, never the last one again (Capture).
 */
object Runner {

    // ------------------------------------------------------------------------
    // Places, all in window space
    // ------------------------------------------------------------------------
    // Measured on 2026-09-19 from the device taps that worked, converted with
    // game_rect_wh(1080, 1920). The three on the way in -- the Events icon,
    // the Gekkomon Run card, Play Game -- are readers since 2026-09-24
    // ([eventsIcon], [eventCard], [eventPage]); these are what is left.
    val MISSIONS = Explore.Target(0.284, 0.786)
    val PAUSE = Explore.Target(0.781, 0.105)
    val QUIT_RESULT = Explore.Target(0.475, 0.611)
    val QUIT_PAUSE = Explore.Target(0.343, 0.610)
    val JUMP = Explore.Target(0.260, 0.897)
    val SLIDE = Explore.Target(0.692, 0.897)
    val DAILY_TAB = Explore.Target(0.354, 0.822)
    val NEUTRAL = Explore.Target(0.476, 0.52)

    /**
     * One of `_blobs`' tuples: (fx0, fy0, fx1, fy1, share, fill), a
     * connected blob of one HSV range as fractions of the window.
     */
    data class Blob(val fx0: Double, val fy0: Double, val fx1: Double, val fy1: Double,
                    val share: Double, val fill: Double) {
        val width get() = fx1 - fx0
        val height get() = fy1 - fy0
        val cx get() = (fx0 + fx1) / 2
        val cy get() = (fy0 + fy1) / 2
        fun toOracle(): Map<String, Any?> = mapOf("fx0" to fx0, "fy0" to fy0, "fx1" to fx1, "fy1" to fy1)
    }

    /**
     * Connected blobs of one HSV range, window space, largest first. [band]
     * restricts the search to (fy0, fy1) of the window; the boxes still come
     * back in whole-window fractions.
     */
    internal fun blobs(img: Mat, colour: Dungeon.Hsv, band: DoubleArray? = null,
                       minShare: Double = 0.0002): List<Blob> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val top = if (band == null) 0 else maxOf(0, Py.int(y0 + band[0] * gh))
        val bottom = if (band == null) img.rows() else minOf(img.rows(), Py.int(y0 + band[1] * gh))
        val sub = Py.crop(img, top, bottom, 0, img.cols()) ?: return emptyList()
        val mask = Cv.hsvMask(sub, colour)
        val (n, stats) = Cv.components(mask)
        mask.release()
        val out = ArrayList<Blob>()
        val floor = minShare * gw * gh
        for (i in 1 until n) {
            val s = stats[i]
            val x = s[0]; val y = s[1]; val w = s[2]; val h = s[3]; val a = s[4]
            if (a < floor) continue
            out.add(Blob((x - x0) / gw.toDouble(), (top + y - y0) / gh.toDouble(),
                         (x + w - x0) / gw.toDouble(), (top + y + h - y0) / gh.toDouble(),
                         a / (gw.toDouble() * gh), a / (w.toDouble() * h)))
        }
        // Python: `out.sort(key=lambda b: -b[4])`, a stable sort by share, largest first.
        return out.sortedByDescending { it.share }
    }

    private fun within(b: Blob, fx0: Double, fx1: Double, fy0: Double, fy1: Double,
                       tol: Double = 0.03): Boolean =
        kotlin.math.abs(b.fx0 - fx0) <= tol && kotlin.math.abs(b.fx1 - fx1) <= tol &&
            kotlin.math.abs(b.fy0 - fy0) <= tol && kotlin.math.abs(b.fy1 - fy1) <= tol

    /** numpy's median of a byte channel: the middle value, or the mean of the two middle ones. */
    private fun median(values: IntArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2].toDouble()
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** One channel of an 8-bit Mat as ints, row-major, the way numpy flattens it. */
    private fun channel(m: Mat, c: Int): IntArray {
        val ch = Mat()
        Core.extractChannel(m, ch, c)
        val bytes = ByteArray(ch.rows() * ch.cols())
        ch.get(0, 0, bytes)
        ch.release()
        return IntArray(bytes.size) { bytes[it].toInt() and 0xff }
    }

    // ------------------------------------------------------------------------
    // The way in: the Events icon on the main screen
    // ------------------------------------------------------------------------
    // **A constant is a place on one display.** The way in was three
    // constants until 2026-09-24, "measured from the device taps that
    // worked" on a 9:16 frame, and the HUD is not the canvas: on the long
    // display it stands elsewhere in window space, as the Bond bubble did
    // (NOTES.md, "The token is not only ever drawn in the middle"). The
    // Events tile's own star, read on the frames of both shapes:
    //
    //                                   fx0 - fx1         fy0 - fy1
    //   ADB 1080 x 1920 (main_screen)   0.6678-0.6905     0.2081-0.2212
    //   Poco F3 1080 x 2400 (passive)   0.6611-0.6827     0.2343-0.2473
    //
    // The old constant stood at 0.693/0.225: 0.017 below the star's top edge
    // on the ADB frame and 0.009 *above* it on the phone's. So the icon is
    // read, and the tap hangs off what was read.
    //
    // What is read is the yellow star in the tile's upper left, the one
    // piece of the HUD's artwork that no other tile carries, together with
    // the dark navy of the tile around it. Measured over every frame of the
    // corpus, every yellow blob of a star's size and fill (0.018-0.030 gw
    // wide, 0.010-0.017 gh tall, fill 0.28-0.44) between fy 0.12 and 0.36
    // and right of fx 0.45, with the navy share of a box half its size
    // larger on every side:
    //
    //   the Events star        167 frames   w 0.0216-0.0237  h 0.0116-0.0134
    //                                       fill 0.34-0.39   navy 0.32-0.52
    //   everything else on      48 blobs    navy 0.00-0.18
    //   the main screens                    (damage numbers, stage art, the
    //                                       Battle Pass ticket's corners)
    //
    // So navy 0.25, the middle of the gap, and no frame has two. The Events
    // label under the tile, which the plan first named, is a pale cyan on
    // stage art that is sometimes snow; the star on its tile does not share
    // that trouble.
    //
    // Null on 179 of the 346 main screens, and every one of them was looked
    // at: 169 have no Events tile at all -- the HUD there is Missions alone,
    // the 66 bond frames of 584 x 1076 among them -- eight have a boss's name
    // banner across the tile, and two are the run frames auto_button calls
    // main. The banner is up for seconds, and the skill waits it out rather
    // than tapping where the tile would be. And only on the plain main
    // screen: the tile shows, dimmed, under 24 other frames of the corpus,
    // and the main screen is the one place a tap on it is ever sent.
    val STAR_YELLOW = Dungeon.Hsv(intArrayOf(20, 120, 200), intArrayOf(35, 255, 255))
    val TILE_NAVY = Dungeon.Hsv(intArrayOf(100, 170, 40), intArrayOf(120, 255, 130))
    val STAR_BAND = doubleArrayOf(0.12, 0.36)
    val STAR_FX0 = doubleArrayOf(0.45, 0.85)
    val STAR_W = doubleArrayOf(0.018, 0.030)
    val STAR_H = doubleArrayOf(0.010, 0.017)
    val STAR_FILL = doubleArrayOf(0.28, 0.44)
    // A floor far under the smallest real star, which is the phone's: under
    // 0.0001 of the window, where the ADB star was just over it.
    const val STAR_MIN_SHARE = 0.00003
    const val STAR_NAVY_MIN = 0.25
    // The tap that worked on 2026-09-19, 789,389 on the ADB frame, as a
    // distance from that frame's star in the star's own size: 0.61 of its
    // width to the right and 0.79 of its height down, the middle of the
    // tile's cone. In the star's own size, so that it scales with the tile.
    const val EVENTS_TAP_DX = 0.6
    const val EVENTS_TAP_DY = 0.8

    /** The navy share of the box around [b], half its size larger on every side, the star included. */
    private fun navyAround(img: Mat, b: Blob): Double {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val ex = b.width * 0.5; val ey = b.height * 0.5
        val sub = Py.crop(img, maxOf(0, Py.int(y0 + (b.fy0 - ey) * gh)),
                          minOf(img.rows(), Py.int(y0 + (b.fy1 + ey) * gh)),
                          maxOf(0, Py.int(x0 + (b.fx0 - ex) * gw)),
                          minOf(img.cols(), Py.int(x0 + (b.fx1 + ex) * gw))) ?: return 0.0
        val m = Cv.hsvMask(sub, TILE_NAVY)
        val share = Cv.share(m)
        m.release()
        return share
    }

    /** Where to tap the Events icon on the plain main screen, or null. */
    fun eventsIcon(img: Mat): Explore.Target? {
        if (Dungeon.autoButton(img) == null) return null
        for (b in blobs(img, STAR_YELLOW, band = STAR_BAND, minShare = STAR_MIN_SHARE)) {
            if (b.fx0 !in STAR_FX0[0]..STAR_FX0[1]) continue
            if (b.width !in STAR_W[0]..STAR_W[1] || b.height !in STAR_H[0]..STAR_H[1]) continue
            if (b.fill !in STAR_FILL[0]..STAR_FILL[1]) continue
            if (navyAround(img, b) < STAR_NAVY_MIN) continue
            return Explore.Target(b.cx + EVENTS_TAP_DX * b.width, b.cy + EVENTS_TAP_DY * b.height)
        }
        return null
    }

    // ------------------------------------------------------------------------
    // The dialogs on the way in and out
    // ------------------------------------------------------------------------
    // The event's own blue, H 101-106 S 233 V 139 on every panel it draws; the
    // Events dialog is one solid box of it (fill 0.88, share 0.33).
    val EVENT_BLUE = Dungeon.Hsv(intArrayOf(98, 180, 100), intArrayOf(110, 255, 190))

    /** The Events window's box, the Gekkomon Run card in it, or null. */
    fun eventsDialog(img: Mat): Blob? {
        // The panel below its lighter title bar: fx 0.160-0.793, fy 0.245-0.782,
        // share 0.25, fill 0.74 on the ADB frame it was measured on.
        for (b in blobs(img, EVENT_BLUE, minShare = 0.18)) {
            if (within(b, 0.160, 0.793, 0.245, 0.782, tol = 0.04) && b.fill >= 0.65) return b
        }
        return null
    }

    // The card stands at a fixed place in the box: the tap that worked,
    // 0.476/0.332, is the box's own middle across and 0.087 under its top
    // edge on the frame the box was measured on (0.245). Hung off the top
    // edge rather than the height, because the card is one card high
    // however far the box reaches down.
    const val EVENT_CARD_BELOW = 0.087

    /** Where to tap the Gekkomon Run card in the Events window [box]. */
    fun eventCard(box: Blob): Explore.Target = Explore.Target(box.cx, box.fy0 + EVENT_CARD_BELOW)

    // "Play Game" is pink text on a translucent band; the three rows below it
    // are the same pink. Together with the white X plate at the bottom right
    // (summon.exit_button, the same reused artwork) that is the event page.
    val PINK_TEXT = Dungeon.Hsv(intArrayOf(140, 120, 150), intArrayOf(170, 255, 255))

    /**
     * The event page (Play Game / Pull / Missions / Ranking), or null; where
     * it is, the middle of the word "Play", which is where Play Game is
     * tapped. The word is the button's own label, so a tap on it lands on
     * the button whatever the band's own extent -- the tap that worked on
     * 2026-09-19, 0.319/0.680, stood 0.022 right of it, between the words.
     */
    fun eventPage(img: Mat): Explore.Target? {
        if (Summon.exitButton(img) == null) return null
        // "Play" and "Game" come back as two blobs; "Play" is the one asked for,
        // fx 0.188-0.297 fy 0.664-0.699 on both frame shapes, share 0.0008.
        for (b in blobs(img, PINK_TEXT, band = doubleArrayOf(0.60, 0.75), minShare = 0.0005)) {
            if (b.fx0 in 0.16..0.22 && b.fx1 in 0.26..0.33 && b.fy0 in 0.64..0.69 &&
                b.fy1 in 0.68..0.72) return Explore.Target(b.cx, b.cy)
        }
        return null
    }

    // Both in-run dialogs sit on the same blue box; the pink Quit is where they
    // differ. Result: Quit centred at fx 0.375-0.570. Pause: Quit at 0.245-0.441
    // with a cyan Continue beside it at 0.505-0.698.
    val QUIT_PINK = Dungeon.Hsv(intArrayOf(143, 120, 180), intArrayOf(157, 200, 255))
    val CONTINUE_CYAN = Dungeon.Hsv(intArrayOf(95, 200, 150), intArrayOf(108, 255, 255))
    val BUTTON_ROW = doubleArrayOf(0.585, 0.640)

    private fun quitButton(img: Mat): Blob? {
        for (b in blobs(img, QUIT_PINK, band = BUTTON_ROW, minShare = 0.004)) {
            if (b.width in 0.16..0.24 && b.fill >= 0.75) return b
        }
        return null
    }

    private fun continueButton(img: Mat): Blob? {
        for (b in blobs(img, CONTINUE_CYAN, band = BUTTON_ROW, minShare = 0.004)) {
            if (b.width in 0.16..0.24 && b.fill >= 0.70) return b
        }
        return null
    }

    // The game's own prompts -- "Return to the title screen?", "Disband the
    // party and leave?" -- draw the same pink-left, blue-right pair on the same
    // row, to the hundredth: swept over 882 stored frames, the pair alone fired
    // on four of them. What the run dialogs have and the prompts do not is the
    // dark plate the title and the two score rows sit on: its top strip
    // measures V 77 (S 212) on the pause and the result dialog, both frame
    // shapes, against V 139 (S 233) on every prompt.
    //
    // **And the strip is measured from the button, not from the top of the
    // window.** runner.py asked at an absolute fy 0.395-0.425, which is where
    // the plate stands on the two frame shapes the laboratory had. On the
    // player's Poco F3, 1080 x 2400, the whole dialog sits 0.013 of the height
    // lower than those: the Quit button's own top edge reads fy 0.6057 against
    // 0.5924 on the ADB frame of the same window. A strip only 0.030 tall then
    // slides off the plate onto the bright blue rim above it, `darkPlate`
    // answered false, and `resultDialog` answered null on a result window that
    // was plainly there -- so the run loop never learned the run had ended and
    // went on pressing jump into a dead screen for twenty seconds, three runs
    // in a row on 2026-09-22.
    //
    // The button is found on every shape, so it is what the plate is measured
    // from. And the distance has to be chosen against the prompts as well as
    // the dialogs, which is the whole reason this reader exists: the first
    // distance tried, 0.17, read the plate on all six dialogs and on all four
    // prompts, and the oracle said so at once -- four frames that had answered
    // null for a year answered `pause_dialog`. Median V of a 0.03 strip by its
    // distance above the button's top edge, every frame in the corpus that
    // draws that pink button at all:
    //
    //                    .175  .180  .185  .190  .195  .200  .205  .210  .215
    //   result 2400        77    77    77    77    77    77   139   139   139
    //   result 1920        77    77    77    77    77    77    77    77   139
    //   result 1076 x2     77    77    77    77    77    77    77    77   139
    //   pause 1920         77    77    77    77    77    77    77    77   139
    //   the four prompts   77    77   139   139   139   139   139     -     -
    //
    // 0.185 to 0.200 is the one band where every dialog is the plate and every
    // prompt is not, and 0.195 is its middle, 0.010 from either edge. It is
    // also, to 0.002, the distance the old absolute strip happened to sample
    // on the laboratory's own frames -- so this keeps the laboratory's number
    // and only gives it something to hang from.
    val PLATE_BAND = doubleArrayOf(0.20, 0.76)
    const val PLATE_ABOVE_QUIT = 0.195
    const val PLATE_H = 0.03
    const val PLATE_V_MAX = 105

    internal fun darkPlate(img: Mat, quitFy0: Double): Boolean {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val fx0 = PLATE_BAND[0]; val fx1 = PLATE_BAND[1]
        val fy0 = quitFy0 - PLATE_ABOVE_QUIT; val fy1 = fy0 + PLATE_H
        val sub = Py.crop(img, Py.int(y0 + fy0 * gh), Py.int(y0 + fy1 * gh),
                          Py.int(x0 + fx0 * gw), Py.int(x0 + fx1 * gw)) ?: return false
        val hsv = Cv.hsv(sub)
        val v = median(channel(hsv, 2))
        hsv.release()
        return v <= PLATE_V_MAX
    }

    /** 'Current Record' after a death, or null. Its Quit is centred and stands alone. */
    fun resultDialog(img: Mat): Explore.Target? {
        val q = quitButton(img)
        if (q == null || q.cx !in 0.42..0.53) return null
        if (continueButton(img) != null || !darkPlate(img, q.fy0)) return null
        return QUIT_RESULT
    }

    /** The pause menu, or null: Quit on the left, Continue on the right. */
    fun pauseDialog(img: Mat): Explore.Target? {
        val q = quitButton(img)
        if (q == null || q.cx !in 0.29..0.40) return null
        if (continueButton(img) == null || !darkPlate(img, q.fy0)) return null
        return QUIT_PAUSE
    }

    // The Missions window: its header block (the event points track) and the
    // tab row below the list. The Claim button is yellow, H 26-30 S 213 V 255,
    // 0.126 wide; the blue arrow buttons in the same column are H 107-109.
    val MISSION_BLUE = Dungeon.Hsv(intArrayOf(100, 150, 120), intArrayOf(112, 255, 255))
    val CLAIM_YELLOW = Dungeon.Hsv(intArrayOf(20, 150, 200), intArrayOf(35, 255, 255))

    /** The Missions window, or null. */
    fun missionsDialog(img: Mat): Explore.Target? {
        var header = false
        var tab = false
        for (b in blobs(img, MISSION_BLUE, band = doubleArrayOf(0.25, 0.85), minShare = 0.004)) {
            if (within(b, 0.161, 0.791, 0.280, 0.414, tol = 0.04) && b.fill >= 0.6) header = true
            if (b.fx0 in 0.20..0.26 && b.fx1 in 0.44..0.50 && b.fy0 in 0.80..0.84) tab = true
        }
        return if (header && tab) DAILY_TAB else null
    }

    /** Every yellow Claim in the visible part of the list, top first. */
    fun claimButtons(img: Mat): List<Explore.Target> {
        val found = ArrayList<Explore.Target>()
        for (b in blobs(img, CLAIM_YELLOW, band = doubleArrayOf(0.42, 0.80), minShare = 0.002)) {
            if (b.fx0 in 0.60..0.70 && b.width in 0.10..0.15 && b.height in 0.02..0.04 &&
                b.fill >= 0.7) found.add(Explore.Target(b.cx, b.cy))
        }
        return found.sortedBy { it.fy }
    }

    // The white X of the event page (summon.exit_button) is still there under
    // the Missions window and still closes it -- but the window dims it to a
    // grey plate, V 108 S 24 on four frames of both shapes against V 255 S 21
    // in the clear, and exit_button's white floor refuses it. Under the Reward
    // sheet the same plate measures V 54: two layers deep, and not the thing
    // to tap there (Tap to close is). So the dimmed plate is only ever asked
    // for on a frame the Missions window has already been recognised on.
    val DIMMED_X_V = doubleArrayOf(85.0, 135.0)
    const val DIMMED_X_S_MAX = 50.0

    /** The dimmed X: where it is, and the plate's V for the oracle. */
    data class DimmedX(val fx: Double, val fy: Double, val v: Double) {
        fun toOracle(): Map<String, Any?> = mapOf("fx" to fx, "fy" to fy, "v" to v)
        val target get() = Explore.Target(fx, fy)
    }

    /** The X that closes the Missions window, or null. */
    fun missionsX(img: Mat): DimmedX? {
        if (missionsDialog(img) == null) return null
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val r = Summon.EXIT_CROP * Summon.EXIT_BUTTON_FW * gw
        val cx = x0 + Summon.EXIT_BUTTON_FX * gw
        val cy = y0 + Summon.EXIT_BUTTON_FY * gh
        val top = Py.int(cy - r); val bottom = Py.int(cy + r)
        val left = Py.int(cx - r); val right = Py.int(cx + r)
        if (top < 0 || left < 0 || bottom > img.rows() || right > img.cols()) return null
        val sub = Py.crop(img, top, bottom, left, right) ?: return null
        val hsv = Cv.hsv(sub)
        val v = median(channel(hsv, 2))
        val sat = median(channel(hsv, 1))
        hsv.release()
        if (v !in DIMMED_X_V[0]..DIMMED_X_V[1] || sat > DIMMED_X_S_MAX) return null
        return DimmedX(Summon.EXIT_BUTTON_FX, Summon.EXIT_BUTTON_FY, v)
    }

    val REWARD_BLUE = Dungeon.Hsv(intArrayOf(100, 180, 100), intArrayOf(118, 255, 200))
    // A full-width blue sheet is the game's favourite way to dim a screen: over
    // 882 stored frames the box alone fired on fourteen. The Reward sheet's own
    // blue is darker, V 148-149 against 114 or 180-184 on every impostor, and it
    // writes "Tap to close" in white along fy 0.82 where the others write
    // nothing: 0.09-0.11 of that box against 0.00 on all fourteen.
    val REWARD_V = doubleArrayOf(130.0, 165.0)
    val CLOSE_LINE = doubleArrayOf(0.39, 0.56, 0.808, 0.832)
    const val CLOSE_WHITE_MIN = 0.04

    /** The 'Reward -- Tap to close' sheet a Claim raises, or null. */
    fun rewardOverlay(img: Mat): Explore.Target? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        for (b in blobs(img, REWARD_BLUE, minShare = 0.18)) {
            if (!(b.width >= 0.90 && b.fy0 in 0.20..0.26 && b.fy1 >= 0.66)) continue
            val box = Py.crop(img, Py.int(y0 + b.fy0 * gh), Py.int(y0 + b.fy1 * gh),
                              Py.int(x0 + b.fx0 * gw), Py.int(x0 + b.fx1 * gw)) ?: continue
            val hsv = Cv.hsv(box)
            val m = Cv.inRange(hsv, REWARD_BLUE)
            if (Core.countNonZero(m) == 0) { hsv.release(); m.release(); continue }
            // `np.median(hsv[:, :, 2][m > 0])`: the V of the masked pixels only.
            val vs = channel(hsv, 2)
            val ms = ByteArray(m.rows() * m.cols()).also { m.get(0, 0, it) }
            val v = median(vs.filterIndexed { i, _ -> ms[i].toInt() != 0 }.toIntArray())
            hsv.release(); m.release()
            if (v !in REWARD_V[0]..REWARD_V[1]) continue
            val fx0 = CLOSE_LINE[0]; val fx1 = CLOSE_LINE[1]; val fy0 = CLOSE_LINE[2]; val fy1 = CLOSE_LINE[3]
            val line = Py.crop(img, Py.int(y0 + fy0 * gh), Py.int(y0 + fy1 * gh),
                               Py.int(x0 + fx0 * gw), Py.int(x0 + fx1 * gw)) ?: continue
            val gray = Cv.gray(line)
            val white = Mat()
            Core.compare(gray, Scalar(230.0), white, Core.CMP_GE)
            val share = Cv.share(white)
            gray.release(); white.release()
            if (share >= CLOSE_WHITE_MIN) return NEUTRAL
        }
        return null
    }

    // ------------------------------------------------------------------------
    // The run: what is on the road
    // ------------------------------------------------------------------------
    // The lane the obstacles stand or float in, and where the character is.
    val LANE = doubleArrayOf(0.30, 0.72)
    const val AHEAD = 0.36
    const val CHAR_RIGHT = 0.34
    // Obstacles are saturated -- red at both ends of the hue wheel, the magenta
    // spikes and the pink glitch block at 145-165; the orbs to collect are pale
    // (S 80-135) and stay out of this mask.
    val OBSTACLE_RED = Dungeon.Hsv(intArrayOf(0, 180, 100), intArrayOf(8, 255, 255))
    val OBSTACLE_MAGENTA = Dungeon.Hsv(intArrayOf(140, 180, 100), intArrayOf(179, 255, 255))
    const val OBSTACLE_MIN_SHARE = 0.0008
    // Right of this a blob is still entering the picture and its left edge is
    // not yet where it will be; such a sighting is not used for speed.
    const val EDGE = 0.95
    // Taller than this stands from the road to the sky: the spike, slide under.
    const val TALL = 0.18
    // A bottom edge above this floats: the glitch block, slide under.
    const val FLOAT_BOTTOM = 0.58

    /** One of `obstacles`' dicts, window space. */
    data class Obstacle(val fx: Double, val fx1: Double, val top: Double, val bottom: Double,
                        val h: Double, val w: Double, val share: Double) {
        fun toOracle(): Map<String, Any?> = mapOf("fx" to fx, "fx1" to fx1, "top" to top,
                                                   "bottom" to bottom, "h" to h, "w" to w, "share" to share)
    }

    /** Saturated blobs in the lane ahead of the character, left first. */
    fun obstacles(img: Mat): List<Obstacle> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val top = Py.int(y0 + LANE[0] * gh)
        val bottom = Py.int(y0 + LANE[1] * gh)
        val left = Py.int(x0 + AHEAD * gw)
        val sub = Py.crop(img, top, bottom, left, img.cols()) ?: return emptyList()
        val hsv = Cv.hsv(sub)
        val red = Cv.inRange(hsv, OBSTACLE_RED)
        val magenta = Cv.inRange(hsv, OBSTACLE_MAGENTA)
        val mask = Mat()
        Core.bitwise_or(red, magenta, mask)
        hsv.release(); red.release(); magenta.release()
        val (n, stats) = Cv.components(mask)
        mask.release()
        val out = ArrayList<Obstacle>()
        for (i in 1 until n) {
            val s = stats[i]
            val x = s[0]; val y = s[1]; val w = s[2]; val h = s[3]; val a = s[4]
            if (a < OBSTACLE_MIN_SHARE * gw * gh) continue
            out.add(Obstacle(fx = (left + x - x0) / gw.toDouble(), fx1 = (left + x + w - x0) / gw.toDouble(),
                             top = (top + y - y0) / gh.toDouble(), bottom = (top + y + h - y0) / gh.toDouble(),
                             h = h / gh.toDouble(), w = w / gw.toDouble(), share = a / (gw.toDouble() * gh)))
        }
        return out.sortedBy { it.fx }
    }

    const val SLIDE_KIND = "slide"
    const val JUMP_KIND = "jump"

    /** Which button beats this obstacle: "slide" or "jump". */
    fun answer(bottom: Double, h: Double): String {
        if (bottom < FLOAT_BOTTOM) return SLIDE_KIND
        if (h > TALL) return SLIDE_KIND
        return JUMP_KIND
    }

    fun answer(o: Obstacle): String = answer(o.bottom, o.h)

    // A row of orbs in the air: pale pink, V bright, centred at jump height.
    val ORB = Dungeon.Hsv(intArrayOf(138, 60, 225), intArrayOf(158, 140, 255))
    val ORB_AIR = doubleArrayOf(0.49, 0.59)
    val ORB_REACH = doubleArrayOf(0.40, 0.62)

    /** True when a row of orbs is hanging at jump height just ahead. */
    fun orbsInAir(img: Mat): Boolean {
        for (b in blobs(img, ORB, band = doubleArrayOf(0.45, 0.62), minShare = 0.0004)) {
            if (b.cy in ORB_AIR[0]..ORB_AIR[1] && b.fx0 in ORB_REACH[0]..ORB_REACH[1] &&
                b.width in 0.02..0.09) return true
        }
        return false
    }

    // ------------------------------------------------------------------------
    // The egg bar and the score
    // ------------------------------------------------------------------------
    val BAR = doubleArrayOf(0.20, 0.83, 0.150, 0.185)
    val BAR_GREEN = Dungeon.Hsv(intArrayOf(35, 120, 120), intArrayOf(90, 255, 255))
    val BAR_PINK = Dungeon.Hsv(intArrayOf(135, 150, 150), intArrayOf(170, 255, 255))
    const val FEVER_ON = 0.15
    const val FEVER_OFF = 0.05

    /** (green share, pink share) of the egg bar strip. Green fills as orbs come in; pink is Fever Time. */
    fun barState(img: Mat): Pair<Double, Double> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val fx0 = BAR[0]; val fx1 = BAR[1]; val fy0 = BAR[2]; val fy1 = BAR[3]
        val sub = Py.crop(img, Py.int(y0 + fy0 * gh), Py.int(y0 + fy1 * gh),
                          Py.int(x0 + fx0 * gw), Py.int(x0 + fx1 * gw)) ?: return 0.0 to 0.0
        val hsv = Cv.hsv(sub)
        val g = Cv.inRange(hsv, BAR_GREEN)
        val p = Cv.inRange(hsv, BAR_PINK)
        val green = Cv.share(g)
        val pink = Cv.share(p)
        hsv.release(); g.release(); p.release()
        return green to pink
    }

    /** Counts rising edges of the bar's pink: one Fever Time each. */
    class FeverCounter {
        var count = 0
        var inFever = false

        /** True on the frame a Fever Time begins. */
        fun feed(pink: Double): Boolean {
            if (!inFever && pink >= FEVER_ON) {
                inFever = true
                count += 1
                return true
            }
            if (inFever && pink < FEVER_OFF) inFever = false
            return false
        }
    }

    val SCORE_BOX = doubleArrayOf(0.33, 0.62, 0.055, 0.105)
    const val SCORE_SCALE = 4
    const val SCORE_BRIGHT = 200
    // Of the crop's height: digits are 17 of 53 px, 0.32, at the window size
    // this was measured on; the floor is two thirds of that.
    const val SCORE_MIN_H = 0.22
    // A digit is at least this wide for its height: the narrowest, a 1, measures
    // 0.64 here. What this refuses is the glow's edge, a 3 x 42 sliver that
    // passed the height test and read as a 3, turning 10,463 into 104,633.
    const val SCORE_DIGIT_ASPECT_MIN = 0.35
    // Every digit stands the same height; anything shorter than this share of
    // the tallest is the comma (0.41) or dirt, and neither is read.
    const val SCORE_DIGIT_MIN = 0.85

    /**
     * The score on the plate at the top, or null.
     *
     * Bright glyphs on a dark plate, the digits named by dungeon.read_counter.
     * The comma is not asked for: at the brightness the digits are cut at it
     * does not survive, and the value does not need it -- every glyph of
     * digit height and digit width, left to right, is the number. Only the
     * cap is decided by this, so null is always an acceptable answer, and the
     * loop wants two equal readings before it acts on one.
     */
    fun readScore(img: Mat): Int? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val fx0 = SCORE_BOX[0]; val fx1 = SCORE_BOX[1]; val fy0 = SCORE_BOX[2]; val fy1 = SCORE_BOX[3]
        val sub = Py.crop(img, maxOf(0, Py.int(y0 + fy0 * gh)), Py.int(y0 + fy1 * gh),
                          maxOf(0, Py.int(x0 + fx0 * gw)), Py.int(x0 + fx1 * gw)) ?: return null
        if (sub.rows() < 4) return null
        val big = Mat()
        Imgproc.resize(sub, big, Size((sub.cols() * SCORE_SCALE).toDouble(), (sub.rows() * SCORE_SCALE).toDouble()),
                       0.0, 0.0, Imgproc.INTER_CUBIC)
        val gray = Cv.gray(big)
        val mask = Mat()
        Core.inRange(gray, Scalar(SCORE_BRIGHT.toDouble()), Scalar(255.0), mask)
        big.release(); gray.release()
        try {
            val (n, stats) = Cv.components(mask)
            val glyphs = (1 until n).map { stats[it] }.filter { it[3] >= SCORE_MIN_H * mask.rows() }
            if (glyphs.isEmpty()) return null
            val tallest = glyphs.maxOf { it[3] }
            val digits = glyphs.filter { it[3] >= SCORE_DIGIT_MIN * tallest && it[2] >= SCORE_DIGIT_ASPECT_MIN * it[3] }
            if (digits.isEmpty() || digits.size > 6) return null
            return Dungeon.readCounter(mask, digits.sortedBy { it[0] })
        } finally {
            mask.release()
        }
    }

    // ------------------------------------------------------------------------
    // The track: when to press
    // ------------------------------------------------------------------------
    val LEAD = mapOf(JUMP_KIND to 0.30, SLIDE_KIND to 0.25)
    const val COOLDOWN = 0.6
    const val TRACK_LEN = 6
    // runner.py's own two: an estimate is stale 0.15 s after the last sighting,
    // and a sighting 0.3 s after the last one is a new object. Both are
    // multiples of the PC's 17 ms frame; [Track] takes the frame gap it is
    // running at and stretches them where the frames are further apart.
    const val TRACK_STALE = 0.15
    const val TRACK_BREAK = 0.3
    const val NEW_OBJECT = 0.05
    const val ORB_CLEARANCE = 0.9
    // The PC saw an obstacle at 50 frames a second and had six sightings
    // within a tenth of a second. The phone sees one every 0.35 s at best
    // (NOTES.md, "takeScreenshot has a floor"), and the same obstacle at
    // one width a second crosses the lane in 0.6 s: two sightings, one of
    // them often still entering at the edge. So a track with one clean
    // sighting is not thrown away here; it is timed with the last speed
    // this run measured, or, before there was one, with the speed the game
    // starts at and the rate it grows -- 0.55 widths/s at the start and
    // over 1.8 at 50 s (PLAN_GEKKOMON_RUN.md 4.2), a straight line through
    // those two. A prior, marked as one, and the live run is what tests it.
    //
    // **Measured on the phone, and the laboratory's slope was an artefact.**
    // The PC's two points were 0.55 at the start and "over 1.8 at 50 s", and
    // the second one is the very reading PLAN_GEKKOMON_RUN.md 4.2 warns about
    // in the paragraph above it: a sighting whose right edge touches the
    // picture's edge stands still for three frames and then jumps, "v 1.78
    // against a real 1.0". A slope built on that is too steep.
    //
    // 2026-09-22, from one phone session: every sighting was logged, and the
    // speed taken from each pair of adjacent sightings of the same obstacle --
    // 88 pairs, outside the loop's own track logic:
    //
    //     t in the run    n    median v    the PC's line said
    //      0-10 s        26      0.63           0.68
    //     10-20 s        31      0.65           0.93
    //     20-30 s        17      0.78           1.18
    //     30-40 s        11      0.94           1.43
    //     40-60 s         3      0.91           1.80
    //
    // Least squares over all 88: v = 0.536 + 0.0095 t. The start is the PC's
    // within noise; the slope is not. This method can only see v < 1.46 (two
    // sightings 0.35 s apart need the obstacle to cross 0.512 widths more
    // slowly than that), so it was checked for a pile-up at the ceiling and
    // there is none: 2% of the readings are over 1.20 and the largest is 1.31.
    //
    // It matters because `RunnerSkill` refuses a measured speed below its
    // prior, so in practice every press is timed on this line. Too steep a
    // line makes eta too small and the press too early -- at 35 s the old one
    // put the press 0.46 s before arrival, just outside the 0.45 the jump can
    // still cover.
    const val SPEED_AT_START = 0.54
    const val SPEED_PER_SECOND = 0.0095

    /** The game's speed at [t] seconds into a run, as the two PC measurements put it. */
    fun speedPrior(t: Double): Double = SPEED_AT_START + SPEED_PER_SECOND * t

    /** (speed in widths per second, seconds to arrival), and whether the speed was measured or assumed. */
    data class Estimate(val speed: Double, val eta: Double, val measured: Boolean)

    /**
     * The leading obstacle over its last few sightings, and the time it
     * will take to reach the character.
     *
     * [gap] is the seconds between two frames of the loop that feeds it:
     * 0 on the PC's window frames, where runner.py's 0.15 and 0.3 hold as
     * they are, and whatever the phone measures between its own grabs.
     */
    class Track(private val gap: () -> Double = { 0.0 }) {
        /** (t, fx, usable) */
        val seen = ArrayList<Triple<Double, Double, Boolean>>()
        var kind: String? = null
        var bottom = 1.0
        var height = 0.0

        private fun stale() = maxOf(TRACK_STALE, 2.0 * gap())
        private fun breakAfter() = maxOf(TRACK_BREAK, 2.5 * gap())

        fun feed(t: Double, obstacle: Obstacle?) {
            if (obstacle == null) return
            if (seen.isNotEmpty() && (obstacle.fx > seen.last().second + NEW_OBJECT ||
                                      t - seen.last().first > breakAfter())) {
                seen.clear()
                kind = null
                height = 0.0
                bottom = 1.0
            }
            val usable = obstacle.fx1 < EDGE
            seen.add(Triple(t, obstacle.fx, usable))
            while (seen.size > TRACK_LEN) seen.removeAt(0)
            height = maxOf(height, obstacle.h)
            bottom = minOf(bottom, obstacle.bottom)
            kind = answer(bottom, height)
        }

        /**
         * runner.py's estimate: the slope from the first clean sighting to
         * the last, or null. [prior] is what the phone falls back on with
         * one clean sighting, in widths per second; null keeps the PC's
         * answer, which is null.
         */
        fun estimate(t: Double, prior: Double? = null): Estimate? {
            val good = seen.filter { it.third }
            if (good.isEmpty() || t - seen.last().first > stale()) return null
            if (good.size >= 2) {
                val (ta, xa, _) = good.first()
                val (tb, xb, _) = good.last()
                if (tb - ta >= 0.03 && xa > xb) {
                    val v = (xa - xb) / (tb - ta)
                    val fxNow = xb - v * (t - tb)
                    return Estimate(v, (fxNow - CHAR_RIGHT) / v, measured = true)
                }
            }
            val v = prior ?: return null
            if (v <= 0) return null
            val (tb, xb, _) = good.last()
            val fxNow = xb - v * (t - tb)
            return Estimate(v, (fxNow - CHAR_RIGHT) / v, measured = false)
        }
    }
}
