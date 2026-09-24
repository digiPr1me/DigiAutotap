package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * summon.py's readers, carried over line for line: the yellow summon button
 * live and dimmed, the X that closes Special Summon, the out-of-tickets
 * dialog, the mode dots, the General tab, the ticket counter, the red price
 * with its character count, View Ads and its counter. The skill's loop is
 * not here; it comes with its own session, under the director.
 *
 * Every constant keeps its Python name and value, and the sentence that says
 * where it came from. A number here is changed in summon.py first, with a
 * measurement, then the oracle is written again, then this file follows --
 * never the other way round (NOTES.md, "Two implementations, one
 * direction"). The frame is BGR, uint8, as `cv2.imread` gives it.
 */
object Summon {

    /** `exit_button`'s dict: where the X is, and the two shares that found it. */
    data class ExitButton(val fx: Double, val fy: Double, val plate: Double, val mark: Double) {
        fun toOracle(): Map<String, Any?> =
            mapOf("fx" to fx, "fy" to fy, "plate" to plate, "mark" to mark)
    }

    // ------------------------------------------------------------------------
    // The yellow summon button
    // ------------------------------------------------------------------------
    // Measured live on the running game: a live button (main screen, result
    // screen, Crest dialog) reads H 8-26 S 211-255 V 235-255; the same button
    // dimmed behind an open dialog drops to V 51-108 with its saturation
    // unchanged. The range's own floor at V 180 is what keeps the dimmed one
    // out -- the same trick dungeon.auto_button uses, so a Crest dialog with two
    // yellow buttons on screen (the live dialog one and the dimmed one behind
    // it) never finds the wrong one by accident.
    val YELLOW = Dungeon.Hsv(intArrayOf(10, 150, 180), intArrayOf(35, 255, 255))

    // Width as a fraction of the game width. Measured in PLAN_SUMMONS.md 4.1:
    // the rank badges on a result screen's cards are 0.124 and 0.157 wide, the
    // real button 0.245 to 0.266 -- room on both sides.
    val SUMMON_BUTTON_W = doubleArrayOf(0.20, 0.30)
    // A filled rectangle, not text on a badge: measured live 0.89 for a button
    // with a longer label ("10x Summon", dark text eating more of the yellow
    // than the plainer buttons) against 0.62 to 0.66 for a rank badge -- still
    // room to spare above the badges with the floor here.
    const val SUMMON_BUTTON_FILL_MIN = 0.85

    // The same button with a dialog drawn over it. Same hue, same saturation,
    // a fraction of the brightness: measured over the four stored frames of the
    // out-of-tickets dialog, in both frame shapes, the dimmed plate reads
    // H 10-30 S 153-255 V 74-88 (5th to 95th percentile, highest pixel 88)
    // against YELLOW's own floor of 180. A factor of two of air between them,
    // so neither range can ever answer for the other.
    //
    // It is not looked for in its own right. It answers one question --
    // "is this a Special Summon screen with a dialog over it" -- and that is
    // what keeps `not_enough_tickets` from firing on the three other dialogs in
    // this game that wear a pink button beside a blue one.
    val YELLOW_DIMMED = Dungeon.Hsv(intArrayOf(10, 150, 20), intArrayOf(35, 255, 150))

    private fun yellowButton(img: Mat, band: Dungeon.Hsv): Dungeon.Button? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val mask = Cv.hsvMask(img, band)
        val (n, stats) = Cv.components(mask)
        mask.release()
        var best: Dungeon.Button? = null
        var bestV = -1.0
        for (i in 1 until n) {
            val (x, y, w, h, area) = stats[i].toList()
            if (w == 0 || h == 0) continue
            val fw = w / gw.toDouble()
            if (!(SUMMON_BUTTON_W[0] <= fw && fw <= SUMMON_BUTTON_W[1])) continue
            if (area / (w * h).toDouble() < SUMMON_BUTTON_FILL_MIN) continue
            // cv2.cvtColor(img[y:y+h, x:x+w], BGR2HSV)[:, :, 2].mean()
            val patch = img.submat(Rect(x, y, w, h))
            val hsv = Cv.hsv(patch)
            val v = Core.mean(hsv).`val`[2]
            hsv.release()
            if (v > bestV) {
                bestV = v
                best = Dungeon.Button((x + w / 2.0 - x0) / gw,
                                      (y + h / 2.0 - y0) / gh,
                                      fw, h / gh.toDouble())
            }
        }
        return best
    }

    /**
     * The brightest wide, full yellow button on the screen, or null.
     *
     * Several yellow blobs can be on screen at once -- a dimmed one is
     * filtered out by YELLOW's own brightness floor already; if more than
     * one live one is ever found, the brightest is kept, never the first
     * found or the one nearest the bottom.
     */
    fun summonButton(img: Mat): Dungeon.Button? = yellowButton(img, YELLOW)

    /**
     * The summon button with a dialog over it, or null.
     *
     * The same shape and the same two filters as the live one -- it is the
     * same piece of interface, so it gets the same width band and the same
     * fill floor rather than a second set of numbers. Measured on the four
     * stored dialog frames: width 0.237 to 0.240 of the game, fill 0.956 to
     * 0.962.
     */
    fun dimmedSummonButton(img: Mat): Dungeon.Button? = yellowButton(img, YELLOW_DIMMED)

    // Where the X sits that returns to the mode screen, as a multiple of the
    // button's own width to its right. Measured on the result screen: button
    // centred at fx 0.638 (raw), X at 0.839, a gap of 0.201 against a button
    // width of 0.253 -- 0.79 button-widths. Position only, never colour: a
    // white square with a small blue mark does not separate from a busy
    // screen by colour the way the button does. The caller verifies the tap
    // worked by reading mode_dots afterwards, never by trusting this alone.
    const val X_BESIDE_OFFSET = 0.80

    fun xBesideButton(button: Dungeon.Button): Pair<Double, Double> =
        (button.fx + X_BESIDE_OFFSET * button.fw) to button.fy

    // ------------------------------------------------------------------------
    // The X that closes Special Summon
    // ------------------------------------------------------------------------
    // The same physical button x_beside_button aims at, found in its own right
    // instead of off a yellow button beside it, because the run has to walk out
    // of Special Summon from screens that have no button worth measuring from.
    //
    // It never moves. Measured at fx 0.7934 fy 0.9567 on fifteen frames of six
    // window shapes -- 573, 736, 757, 759 and 1080 wide, the last of them the
    // ADB frame -- and the whole spread across them is 0.0011 in fx and 0.0002
    // in fy. What moves is the summon button: on a result screen the button row
    // drops to the bottom and x_beside_button then lands within 0.004 of this
    // same X, which is why that route works; on a mode screen the row sits at
    // fy 0.885 and the same arithmetic points at empty sky 0.07 above the X.
    const val EXIT_BUTTON_FX = 0.7934
    const val EXIT_BUTTON_FY = 0.9567
    // The plate's width. The crop taken below is deliberately smaller than the
    // plate -- 0.35 of it either side of the centre, not 0.5 -- so that a window
    // shape this was never measured at cannot slide the background into it. That
    // is not theoretical: pushed 0.008 gw sideways, a crop of 0.45 drops the
    // plate share to 0.677 while one of 0.35 holds 0.720.
    const val EXIT_BUTTON_FW = 0.0785
    const val EXIT_CROP = 0.35
    // A near-white plate with a small blue X on it. Neither half tells it from
    // the game on its own -- the plate is the same white as half the UI, and the
    // main screen reads 0.189 of this very blue at this very spot -- so it is
    // the pair that decides, the way NOTES.md asks for. Measured over those
    // fifteen frames: plate 0.749 to 0.760, mark 0.211 to 0.226. The nearest
    // rival among every other frame in debug_summon is the main screen at plate
    // 0.000 mark 0.189, and a draw animation at plate 0.292 mark 0.002.
    val EXIT_PLATE = Dungeon.Hsv(intArrayOf(0, 0, 225), intArrayOf(180, 45, 255))
    val EXIT_MARK = Dungeon.Hsv(intArrayOf(98, 110, 140), intArrayOf(118, 220, 255))
    // Floors far enough below what they must catch to survive a misaligned crop:
    // 0.60 against a worst case of 0.720, 0.12 against 0.187.
    const val EXIT_PLATE_MIN = 0.60
    val EXIT_MARK_BAND = doubleArrayOf(0.12, 0.34)

    /**
     * The X that closes one Special Summon screen, or null.
     *
     * Null also answers "is a Special Summon screen in front at all": every
     * tab and every result screen carries this X, and a dialog drawn over it
     * dims the plate out of the white mask the same way dungeon.auto_button's
     * disc loses its edges. So a caller that finds it has evidence that a tap
     * there will land on something, not only where to send it.
     */
    fun exitButton(img: Mat): ExitButton? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val r = EXIT_CROP * EXIT_BUTTON_FW * gw
        val cx = x0 + EXIT_BUTTON_FX * gw
        val cy = y0 + EXIT_BUTTON_FY * gh
        val top = Py.int(cy - r)
        val bottom = Py.int(cy + r)
        val left = Py.int(cx - r)
        val right = Py.int(cx + r)
        if (top < 0 || left < 0 || bottom > img.rows() || right > img.cols()) return null
        val crop = Py.crop(img, top, bottom, left, right) ?: return null
        val hsv = Cv.hsv(crop)
        val n = (hsv.rows() * hsv.cols()).toDouble()
        // cv2.inRange(...).sum() / 255.0 / n: the sum is 255 per pixel, exact.
        val plateMask = Cv.inRange(hsv, EXIT_PLATE)
        val markMask = Cv.inRange(hsv, EXIT_MARK)
        val plate = Core.countNonZero(plateMask) * 255L / 255.0 / n
        val mark = Core.countNonZero(markMask) * 255L / 255.0 / n
        hsv.release(); plateMask.release(); markMask.release()
        if (plate < EXIT_PLATE_MIN) return null
        if (!(EXIT_MARK_BAND[0] <= mark && mark <= EXIT_MARK_BAND[1])) return null
        return ExitButton(EXIT_BUTTON_FX, EXIT_BUTTON_FY, plate, mark)
    }

    // ------------------------------------------------------------------------
    // "Insufficient ... Tickets" -- the game saying the mode is finished
    // ------------------------------------------------------------------------
    // What ends a mode now. The skill taps until the game itself says stop, and
    // this is the game saying it: a dialog headed "Insufficient <mode> Summon
    // Tickets" with a pink **Close** and a blue **Move** side by side. Close
    // dismisses it, Move would walk off to the shop, so only the pink one is
    // ever aimed at.
    //
    // Measured on eight stored frames, one window frame at 624 x 1076 and seven
    // ADB frames at 1080 x 1920 -- the same dialog, kept by three different
    // skills as the thing they got stuck on:
    //
    //   Close plate   H 148-152  S 157-214  V 200-223
    //   Move plate    H 100-107  S 255      V 193-212  (inside dungeon.BLUE)
    //   Close         fx 0.370-0.375  fy 0.5871-0.5886  fw 0.1648-0.1664
    //   same row      0.0005 apart      widths          1.00 the same
    //   gap           1.22 to 1.23 Close widths, centre to centre
    //
    // The pink is the pink NOTES.md already measured on the prompts' Cancel,
    // 147-149, two hue units above where VIOLET stops. That is deliberate and
    // it is also the trap: **three other dialogs in this game wear a pink
    // button beside a blue one at almost this very place** -- "Disband the
    // party and leave?", "Return to the title screen?" and the bond tour's
    // Raise dialog, all at fy 0.600 with a gap of 1.32. Two per cent and seven
    // per cent away, which is no fence at all (NOTES.md: no threshold within
    // 10 % of what it must catch).
    //
    // What separates them is the neighbour, the way Claim is separated from an
    // exit prompt: this dialog is drawn over a Special Summon screen, so the
    // yellow summon button is behind it, dimmed. None of the other three has a
    // yellow button anywhere. Over all 470 stored frames of every debug folder,
    // both frame shapes, the pair alone fires 12 times and the pair with the
    // dimmed button fires 8 -- every one of the 8 this dialog, and none of the
    // 4 it had to refuse.
    val NOT_ENOUGH_PINK = Dungeon.Hsv(intArrayOf(146, 120, 150), intArrayOf(158, 255, 255))
    // The play area, not a row band. The title is two lines here ("Insufficient
    // Support Summon Tickets") and a shorter mode name may well make it one,
    // which would carry the buttons up -- so the row is left wide on purpose
    // and the neighbour does the work.
    val NOT_ENOUGH_ROW = doubleArrayOf(0.35, 0.85)
    // Measured 0.0005, 1.00 and 1.22-1.23. Room enough that a frame shape this
    // was never seen at cannot slide any of the three out.
    const val NOT_ENOUGH_SAME_ROW = 0.02
    val NOT_ENOUGH_WIDTH = doubleArrayOf(0.75, 1.35)
    val NOT_ENOUGH_GAP = doubleArrayOf(0.90, 1.70)
    // The Close plate covers 0.0044 of the game on the frames above; the floor
    // leaves a third of that in hand.
    const val NOT_ENOUGH_AREA = 0.003

    /**
     * The out-of-tickets dialog's Close button, or null.
     *
     * Null also answers "is this dialog in the way", which is what
     * `leave_summons` asks it: the dialog dims the X out of the white mask
     * the same way it dims the summon button, so a run that ends with it on
     * screen cannot walk out. corpus/summon/no_way_back_02.png is a picture of
     * exactly that, and so are four frames the passive helper kept and two
     * the quest loop kept.
     */
    fun notEnoughTickets(img: Mat): Dungeon.Button? {
        if (dimmedSummonButton(img) == null) return null
        val pinks = Dungeon.findButtons(img, NOT_ENOUGH_PINK, minArea = NOT_ENOUGH_AREA,
                                        minY = NOT_ENOUGH_ROW[0])
            .filter { it.fy <= NOT_ENOUGH_ROW[1] }
        if (pinks.isEmpty()) return null
        val blues = Dungeon.findButtons(img, Dungeon.BLUE, minArea = NOT_ENOUGH_AREA,
                                        minY = NOT_ENOUGH_ROW[0])
            .filter { it.fy <= NOT_ENOUGH_ROW[1] }
        for (close in pinks) {
            for (move in blues) {
                if (abs(move.fy - close.fy) > NOT_ENOUGH_SAME_ROW) continue
                val width = move.fw / close.fw
                if (!(NOT_ENOUGH_WIDTH[0] <= width && width <= NOT_ENOUGH_WIDTH[1])) continue
                val gap = (move.fx - close.fx) / close.fw
                if (NOT_ENOUGH_GAP[0] <= gap && gap <= NOT_ENOUGH_GAP[1]) return close
            }
        }
        return null
    }

    // ------------------------------------------------------------------------
    // Which mode is selected
    // ------------------------------------------------------------------------
    // Measured live: the active dot's orange reads H 8-12 S 143-255 V 240-255,
    // well clear of the sky-blue background behind it. The two inactive dots do
    // not separate from that background by hue or saturation at all -- both
    // measure H 108-115 S 56-118, and the plain sky right beside a dot measures
    // the same H108 S60. What does separate them is brightness: the sky there
    // is a flat, textureless V 255, while the dot -- being a filled, shaded
    // disc -- dips to V 118-235. Without the upper bound on V the inactive mask
    // swallows the whole sky into one component 140,000 pixels large and the
    // two real dots vanish inside it.
    val DOT_ORANGE = Dungeon.Hsv(intArrayOf(4, 130, 150), intArrayOf(20, 255, 255))
    val DOT_INACTIVE = Dungeon.Hsv(intArrayOf(95, 40, 100), intArrayOf(125, 140, 235))
    // Raw fraction from a screenshot not taken through grab_screen.py: the dot
    // row sat at fy 0.26-0.28 there, between the mode banners above and the
    // ticket counter below. Widened for safety until re-measured on a
    // game_rect-correct frame.
    val DOT_Y_BAND = doubleArrayOf(0.18, 0.38)
    // Measured live: the real dots are about 9-10 px square at 607 px game
    // width, area 62-76 -- comfortably above this, with room below for a
    // smaller window.
    const val DOT_MIN_AREA = 0.00003
    val DOT_ASPECT = doubleArrayOf(0.5, 2.0)
    // The three dots sit close together, not spread across the screen -- used
    // to reject stray same-coloured artwork rather than trusting the band
    // alone.
    const val DOT_Y_TOL = 0.006
    const val DOT_X_SPREAD_MAX = 0.16
    // Three rules that say "these three blobs are the dot row" rather than
    // "these three blobs happen to be near each other". Without them the
    // reader took the first orange blob that had two companions of any size at
    // any spacing, and on the Buddy tab -- one banner, one dot -- it found a
    // row in the banner artwork and reported mode 2 for a screen that has no
    // modes at all.
    //
    // Measured on ADB-shaped frames of all three mode screens, the real dots:
    //
    //   skill    orange 17 x 17   inactive 20 x 18, 20 x 18
    //   support  orange 16 x 17   inactive 20 x 18, 20 x 18
    //   crest    orange 17 x 17   inactive 20 x 18, 20 x 18
    //
    // and the false row on the Buddy tab: 54 x 27, 111 x 70, 12 x 16. The real
    // rows spread 20/16 = 1.25 across their members, the false one 111/12 =
    // 9.25. 1.6 sits between with room either side.
    const val DOT_SIZE_RATIO_MAX = 1.6
    // Same story for the gaps. Real dots are evenly spaced -- 19 px and 19 px
    // on the skill screen -- while the false row measured 62 and 46, a ratio
    // of 1.35.
    const val DOT_GAP_RATIO_MAX = 1.25
    // And the baseline: the three real dots share one cy exactly, the false
    // row spread 21 px. The old tolerance of 0.02 gh was 28 px and let that
    // through; 0.006 gh is 8 px on a window frame and 12 on an ADB one.

    /**
     * One of `_dot_blobs`' dicts. A data class on purpose: `row.index(o)`
     * in Python finds the first member *equal* to o, and so does indexOf.
     */
    private data class Dot(val cx: Double, val cy: Double, val w: Int, val h: Int)

    private fun dotBlobs(mask: Mat, gw: Int, gh: Int): List<Dot> {
        val (n, stats) = Cv.components(mask)
        val out = ArrayList<Dot>()
        for (i in 1 until n) {
            val (x, y, w, h, area) = stats[i].toList()
            if (w == 0 || h == 0 || area < DOT_MIN_AREA * gw * gh) continue
            val aspect = w / h.toDouble()
            if (!(DOT_ASPECT[0] <= aspect && aspect <= DOT_ASPECT[1])) continue
            out.add(Dot(x + w / 2.0, y + h / 2.0, w, h))
        }
        return out
    }

    /**
     * True if these three blobs are the dot row and not three coincidences.
     *
     * Same size and evenly spaced. Either test alone would have caught the
     * Buddy tab; both are cheap and they fail differently, so both stay.
     */
    private fun looksLikeARow(row: List<Dot>): Boolean {
        val widths = row.map { it.w }
        val heights = row.map { it.h }
        for (side in listOf(widths, heights)) {
            if (side.max() > DOT_SIZE_RATIO_MAX * maxOf(1.0, side.min().toDouble())) return false
        }
        val gaps = (0 until row.size - 1).map { row[it + 1].cx - row[it].cx }
        if (gaps.min() <= 0) return false
        return gaps.max() <= DOT_GAP_RATIO_MAX * gaps.min()
    }

    /**
     * Which of the three mode banners is selected -- 1, 2 or 3 -- or null.
     *
     * Found by colour and count, not by position, because the row is centred
     * under the banners and a dot's own x drifts with the window. What does
     * not drift: there are three of them, close together, and exactly one is
     * orange. Counted from the left, per NOTES.md's own instruction for this
     * reader rather than read off the orange one's own position.
     */
    fun modeDots(img: Mat): Int? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val top = maxOf(0, Py.int(y0 + DOT_Y_BAND[0] * gh))
        val bottom = Py.int(y0 + DOT_Y_BAND[1] * gh)
        val sub = Py.crop(img, top, bottom, maxOf(0, x0), x0 + gw) ?: return null
        val hsv = Cv.hsv(sub)
        val orangeMask = Cv.inRange(hsv, DOT_ORANGE)
        val inactiveMask = Cv.inRange(hsv, DOT_INACTIVE)
        val oranges = dotBlobs(orangeMask, gw, gh)
        val inactives = dotBlobs(inactiveMask, gw, gh)
        hsv.release(); orangeMask.release(); inactiveMask.release()
        for (o in oranges) {
            val row = (listOf(o) + inactives.filter {
                abs(it.cy - o.cy) <= DOT_Y_TOL * gh && abs(it.cx - o.cx) <= DOT_X_SPREAD_MAX * gw
            }).sortedBy { it.cx }
            if (row.size != 3) continue
            if (!looksLikeARow(row)) continue
            return row.indexOf(o) + 1
        }
        return null
    }

    // ------------------------------------------------------------------------
    // General, and the neighbour banner
    // ------------------------------------------------------------------------
    // Buddy's green, measured live: H 76-77 S 166-221 V 215-221.
    val GREEN = Dungeon.Hsv(intArrayOf(65, 90, 140), intArrayOf(90, 255, 255))
    const val TAB_Y_TOL = 0.04

    // The area floor for the two tabs, and the reason this bot could not open
    // Special Summon at all on its first live run.
    //
    // It stood at 0.006 of the reference window, and that is what the tabs
    // themselves measure. On a window frame General covers 6,821 px of a
    // 803 x 1386 window space, which is 0.00613 -- over the line by 2 %. On the
    // ADB frame the bot actually runs on, the same tab covers 13,092 px of a
    // 1147 x 1980 window space, which is 0.00577 -- under the line by 4 %, and
    // Buddy misses by 1 %. The frames the suite paints are clean and generous,
    // so every test passed while the live run timed out on the entry screen
    // twice in a row.
    //
    // The two do not agree because min_area is taken against the window space,
    // which is larger than the image and by a different factor on each source
    // (1.060 against 1.096), and because an anti-aliased edge loses a different
    // share of its pixels at each scale. A 3 % margin cannot survive either.
    // This is NOTES.md's own trap, the one the glyph filter fell into at 0.28
    // against digits measuring 0.274.
    //
    // 0.003 leaves a factor of two below the smallest real measurement. What
    // keeps the lower floor from letting anything else in is not the area at
    // all -- it is the pair below.
    const val TAB_MIN_AREA = 0.003
    // Both tabs are the same size, measured 0.242 to 0.245 wide on window and
    // ADB frames alike. The bottom row's blue buttons measure 0.172 to 0.236,
    // so width alone would not separate them -- but they have no green tab
    // beside them, and that is what does.
    val TAB_FW = doubleArrayOf(0.18, 0.32)
    const val TAB_FW_TOL = 0.06

    // What a pair with a cut member has to bring instead of two measured
    // widths: the two tabs are the same height, and a side of the picture
    // takes width, never height.
    //
    // Lifting findButtons' edge fence here (see generalTab) is what lets the
    // long display's cut tab back in, and on its own it let eight main-screen
    // frames of corpus/passive in as well -- a blue and a green blob at the
    // same row, one of them running off the picture, at fy 0.066 and 0.722
    // where no tab row is. Their taller-relative height difference is 0.097 to
    // 0.423; the four real cut pairs all measure 0.066. The floor sits between
    // them with 21 % above what it must accept and 18 % below what it must
    // refuse.
    //
    // The obvious second test is not one. The gap between the two tabs,
    // measured in tab widths, is 1.253 to 1.273 over every real pair of both
    // frame shapes and both sources -- and two of those eight impostors measure
    // 1.281 and 1.309. Six thousandths of air is the same non-fence the bond
    // bubble's band was, so it is written down here rather than coded.
    const val TAB_FH_TOL = 0.08

    /**
     * `summon._mend`: a blob a side of the picture cut, put back with its
     * twin's width.
     *
     * On a long display the game covers rather than letterboxes, so 118 px of
     * 1316 go off each side at 1080 x 2340 (NOTES.md) -- and the tab row is
     * wide enough that one end of it is always among them. The cut takes the
     * blob's outer edge with it, so neither its width nor its centre is the
     * thing's any more; its *inner* edge is untouched, and the two tabs are
     * the same size, so the twin supplies what the picture lost.
     *
     * Measured against the 1920 twin of the same scene, `corpus/tall`:
     * General cut at the left reads fx 0.186 fw 0.193 and mends to 0.162
     * against 0.163; Buddy cut at the right reads 0.756/0.212 and mends to
     * 0.769 against 0.772. Three thousandths on a tab 0.24 wide.
     */
    private fun mend(blob: Dungeon.Button, otherFw: Double): Dungeon.Button {
        if (blob.cut == null) return blob
        val inner = if (blob.cut == "left") blob.fx + blob.fw / 2.0
                    else blob.fx - blob.fw / 2.0
        val fx = if (blob.cut == "left") inner - otherFw / 2.0
                 else inner + otherFw / 2.0
        return blob.copy(fx = fx, fw = otherFw)
    }

    /**
     * The blue General tab, identified by the green Buddy tab beside it.
     *
     * Position will not do here: General sits at rel. x 0.17 on the entry
     * screen and blue buttons of roughly the same shape turn up at other x
     * positions on every mode screen afterwards (15x Summon). The green
     * neighbour at the same height, and of the same width, is what is unique
     * to this one screen.
     *
     * The tab row reaches a side of the picture on a long display, and
     * findButtons' own answer to that -- refuse the blob, its width is the
     * picture's -- left this reader with nothing at all: General itself is
     * cut on the entry screen and Buddy on the General page, so generalTab
     * answered null on both 1080 x 2340 frames of `corpus/tall` while
     * answering on both their 1920 twins. The fence is right and the width of
     * a cut blob really is worthless; what this reader has that findButtons
     * has not is a second tab of the same size. So it asks with the fence
     * lifted, takes the width off whichever of the pair is whole, and mends
     * the other one's centre from its uncut side ([mend]). Both cut at once
     * leaves nothing to measure against and is refused, and a pair with one
     * cut member has to agree on its height as well ([TAB_FH_TOL]) -- lifting
     * the fence without that put a General tab on eight main screens.
     */
    fun generalTab(img: Mat): Dungeon.Button? {
        val blues = Dungeon.findButtons(img, Dungeon.BLUE, minArea = TAB_MIN_AREA,
                                        minY = 0.0, edge = false)
        val greens = Dungeon.findButtons(img, GREEN, minArea = TAB_MIN_AREA,
                                         minY = 0.0, edge = false)
        for (b in blues) {
            for (g in greens) {
                if (b.cut != null && g.cut != null) continue
                if (!Dungeon.near(g.fy, b.fy, TAB_Y_TOL)) continue
                // The width band and the same-width test are asked of the
                // whole one of the pair; a cut width is not evidence of
                // anything.
                val whole = if (b.cut != null) g else b
                if (!(TAB_FW[0] <= whole.fw && whole.fw <= TAB_FW[1])) continue
                if (b.cut != null || g.cut != null) {
                    // A whole pair is vouched for by two widths both measured
                    // on the picture. A cut pair has one, so it brings the
                    // height instead -- TAB_FH_TOL.
                    val tall = maxOf(b.fh, g.fh)
                    if (abs(b.fh - g.fh) / tall > TAB_FH_TOL) continue
                }
                val b2 = mend(b, g.fw)
                val g2 = mend(g, b.fw)
                if (abs(g2.fw - b2.fw) > TAB_FW_TOL) continue
                if (g2.fx <= b2.fx) continue
                return b2.copy(cut = null)
            }
        }
        return null
    }

    // Raw fractions from PLAN_SUMMONS.md 4.6: the banner row's own fy, and the
    // neighbour banner's centre to either side of the selected one.
    const val BANNER_FY = 0.20
    const val NEIGHBOUR_FX_RIGHT = 0.80
    const val NEIGHBOUR_FX_LEFT = 0.13

    // Where the Summon icon sits on the game's main screen. fx confirmed by
    // passive.py's own measurement (0.822); fy confirmed live on 2026-08-26 by
    // a real tap that opened Special Summon.
    const val SUMMON_ICON_FX = 0.80
    const val SUMMON_ICON_FY = 0.54

    // ------------------------------------------------------------------------
    // The ticket / crest counter, top right
    // ------------------------------------------------------------------------
    // White digits on a dark badge, the same shape dungeon.badge_glyphs reads --
    // confirmed live on both screens this counter appears on. Two positions
    // rather than one: fy about 0.23-0.33 on the mode's own main screen, about
    // 0.01-0.10 on the result screen; fx is the same narrow column on both,
    // confirmed live. A single band wide enough to cover both merged the badge
    // into the surrounding artwork and text -- one connected blob 350,000
    // pixels large -- so each position gets its own narrow crop instead, and
    // whichever one actually holds a badge is the one that parses.
    val TICKET_BANDS = listOf(doubleArrayOf(0.63, 0.87, 0.23, 0.33),
                              doubleArrayOf(0.63, 0.87, 0.01, 0.10))
    const val TICKET_SCALE = 4
    val TICKET_WHITE = intArrayOf(200, 255)
    // Measured live on "4,734": the four digits are 31-35 px wide by 46-47
    // tall, aspect 0.65-0.82; the ticket icon beside them, caught by the same
    // mask because its own centre is a white shape, is 80 x 81, aspect 0.99.
    // The upper bound sits between the two with margin either side.
    val TICKET_ASPECT = doubleArrayOf(0.15, 0.95)
    const val TICKET_FILL_MIN = 0.15
    // The comma is short -- 20 px against a 46 px digit, well under half. A
    // height floor high enough to keep it out a title banner drifting into the
    // crop above the badge would keep the comma out too, so height alone
    // cannot be the filter; _ticket_row below anchors on the tallest glyph
    // instead and reads everything near its own row, comma included.
    //
    // This floor is only there to throw away one- and two-pixel dirt. It must
    // NOT be asked to separate a comma from a speck, because it is a fraction
    // of the crop, and NOTES.md already records what happens then: a
    // threshold measured against the crop moves when the crop does.
    const val TICKET_MIN_H = 0.012
    // What actually separates them, measured against the digits themselves.
    //
    // This is the bug that made the first live run unreadable. The ticket icon
    // beside the number has a small white mark inside it, and on the frame
    // kept as corpus/summon/stalled_00.png it came out 14 x 20 next to digits
    // of 87 to 88 -- over the crop-relative floor of 0.03 (16.9 px) by three
    // pixels. It joined the row as a sixth character, "2,739" parsed as six
    // glyphs, and the whole counter went unread. Three rounds of that and the
    // mode gave up, while the number sat plainly on the screen.
    //
    // Every glyph as a share of the tallest one in the same crop, over every
    // frame in debug_summon/ that actually holds a badge:
    //
    //   digits             0.72 - 1.00
    //   the comma          0.42   (22 x 37 against 87, on three separate frames)
    //   icon marks, dirt   0.02 - 0.23
    //
    // 0.32 sits 24 % below the comma and 39 % above the worst speck. Anything
    // nearer than that to either is the mistake this replaces.
    const val TICKET_MIN_REL_H = 0.32

    // How many characters a crop must hold before it is believed to be the
    // badge at all.
    //
    // One is not enough, and that is not a guess: on the Crest result screen a
    // single stray 29 x 46 mark in the main-screen band parsed as "1" and was
    // returned, while the band beside it held a clean "446" and was never
    // tried, because the first band that parses wins. On the draw animation --
    // where there is no badge on the screen at all -- a lone 18 x 20 blob
    // parsed as "7". A made-up number is worse than an admitted None: the loop
    // compares this reading with the one before it to decide what happened.
    //
    // The cost of asking for two is that a genuine one-digit count reads as
    // None. That costs nothing here. Every price is 10 or 30, so a single
    // digit always means "stop", and the red price says so independently --
    // the counter is only ever the cross-check for it (PLAN_SUMMONS.md 0).
    //
    // Counted in DIGITS, not in row members, and that distinction is the
    // second half of the same bug. The row is split into digits and commas by
    // height further down, so a row of two whose shorter member is taken for a
    // comma yields a one-digit number after all -- which is how a lone "7"
    // from the Reward banner's artwork still got returned from a crop that had
    // passed a two-member check.
    const val TICKET_MIN_DIGITS = 2

    // And how tall a character of the counter is, as a share of the game's own
    // height. This is what finally separates the badge from everything else
    // that is white and roughly digit-shaped.
    //
    // The badge is a fixed piece of interface, so this hardly varies at all.
    // Measured over every frame in debug_summon/ whose reading is known to be
    // right -- 2199, 2642, 2739, 398, 496, 4734, 5299, 24, 54, 446, on window
    // and ADB frames alike:
    //
    //   every correct reading      0.0107 - 0.0112
    //   wrong ones, below          0.0018  0.0048  0.0052  0.0053  0.0067
    //                              0.0069  0.0087  0.0091
    //   wrong ones, above          0.0165  0.0167  0.0169  0.0171  0.0274
    //                              0.0326  0.0331
    //
    // The band below leaves 16 % to the nearest wrong reading underneath and
    // 27 % to the nearest above, with the real span sitting in the middle of
    // it. This is what would have stopped the live run's false abort: two
    // stray 5-pixel marks in the result band read as "77" at 0.0048, the run
    // took it for the count, and the next perfectly good 2,199 looked like the
    // number going up.
    val TICKET_H_OF_GAME = doubleArrayOf(0.009, 0.013)

    private fun ticketMask(img: Mat, band: DoubleArray): Mat? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val (fx0, fx1, fy0, fy1) = band.toList()
        val left = maxOf(0, Py.int(x0 + fx0 * gw))
        val top = maxOf(0, Py.int(y0 + fy0 * gh))
        val sub = Py.crop(img, top, Py.int(y0 + fy1 * gh), left, Py.int(x0 + fx1 * gw))
            ?: return null
        if (sub.rows() < 2 || sub.cols() < 2) return null
        val big = Mat()
        Imgproc.resize(sub, big, Size((sub.cols() * TICKET_SCALE).toDouble(),
                                      (sub.rows() * TICKET_SCALE).toDouble()),
                       0.0, 0.0, Imgproc.INTER_CUBIC)
        val gray = Cv.gray(big)
        val mask = Mat()
        Core.inRange(gray, Scalar(TICKET_WHITE[0].toDouble()), Scalar(TICKET_WHITE[1].toDouble()), mask)
        big.release(); gray.release()
        return mask
    }

    /**
     * Characters in the badge, left to right.
     *
     * Two passes, and the order is the point. The crop-relative floor only
     * clears away dirt; what decides whether something is a character is its
     * height against the tallest character found, never against the crop.
     * See TICKET_MIN_REL_H for the frame this got wrong.
     */
    private fun ticketGlyphs(mask: Mat): List<IntArray> {
        val (n, stats) = Cv.components(mask)
        val out = ArrayList<IntArray>()
        for (i in 1 until n) {
            val (_, _, w, h, area) = stats[i].toList()
            if (h == 0 || h < TICKET_MIN_H * mask.rows()) continue
            val aspect = w / h.toDouble()
            if (!(TICKET_ASPECT[0] <= aspect && aspect <= TICKET_ASPECT[1])) continue
            if (area / (w * h).toDouble() < TICKET_FILL_MIN) continue
            out.add(stats[i])
        }
        if (out.isEmpty()) return emptyList()
        val tallest = out.maxOf { it[3] }
        // Python's list.sort is stable, and so is sortedBy.
        return out.filter { it[3] >= TICKET_MIN_REL_H * tallest }.sortedBy { it[0] }
    }

    /**
     * Glyphs belonging to the counter, anchored on its tallest one.
     *
     * There is no slash to anchor on here, unlike dungeon's card badges or
     * passive's hologram counter, so the tallest surviving glyph stands in
     * for it -- always a digit, since the aspect filter above already took
     * the ticket icon out and a comma is shorter than a digit by design.
     * Everything else within a generous reach of its row, comma included,
     * joins it; a title banner drifting into the crop above the badge sits
     * nowhere near that row and is left out.
     */
    private fun ticketRow(glyphs: List<IntArray>): List<IntArray> {
        if (glyphs.isEmpty()) return emptyList()
        val tallest = glyphs.maxOf { it[3] }
        val anchor = glyphs.first { it[3] == tallest }
        val anchorCy = anchor[1] + anchor[3] / 2.0
        return glyphs.filter { abs((it[1] + it[3] / 2.0) - anchorCy) <= 0.9 * tallest }
    }

    /**
     * Remaining tickets or crests, top right, or null if unreadable.
     *
     * All or nothing, like dungeon.read_counter: a number missing a digit
     * reads as a number that looks perfectly fine and is wrong, which is
     * worse than admitting nothing was read. Tried against each known badge
     * position in turn -- only one of them ever actually holds a badge.
     */
    fun ticketCounter(img: Mat): Int? {
        val gh = Dungeon.gameRect(img).gh
        for (band in TICKET_BANDS) {
            val mask = ticketMask(img, band) ?: continue
            try {
                val row = ticketRow(ticketGlyphs(mask))
                if (row.isEmpty()) continue
                val tallest = row.maxOf { it[3] }
                // Is this the badge at all, or something else white in the crop?
                val share = tallest / TICKET_SCALE.toDouble() / gh.toDouble()
                if (!(TICKET_H_OF_GAME[0] <= share && share <= TICKET_H_OF_GAME[1])) continue
                val digits = row.filter { it[3] >= 0.75 * tallest }.sortedBy { it[0] }
                val commas = row.filter { it[3] < 0.75 * tallest }
                if (digits.size < TICKET_MIN_DIGITS) continue
                if (!commasHold(digits, commas)) continue
                val value = Dungeon.readCounter(mask, digits)
                if (value != null) return value
            } finally {
                mask.release()
            }
        }
        return null
    }

    /**
     * Three digits after every comma, and a comma for every thousand.
     *
     * summon.py calls passive's `_commas_hold`, and its Kotlin twin is
     * private to Passive.kt, which this session may not touch. So this is
     * the same function a second time, line for line, reading passive's own
     * HOLO_GROUP rather than a number of its own; it goes the moment
     * Passive.commasHold is made visible to the package.
     */
    private fun commasHold(unsorted: List<IntArray>, commas: List<IntArray>): Boolean {
        val digits = unsorted.sortedBy { it[0] }
        if (commas.isEmpty()) return digits.size <= Passive.HOLO_GROUP
        val edges = commas.map { it[0] }.sorted()
        val groups = ArrayList<List<IntArray>>()
        var rest: List<IntArray> = digits
        for (edge in edges) {
            groups.add(rest.filter { it[0] < edge })
            rest = rest.filter { it[0] > edge }
        }
        groups.add(rest)
        if (!groups.all { it.isNotEmpty() }) return false
        if (!(1 <= groups[0].size && groups[0].size <= Passive.HOLO_GROUP)) return false
        return groups.drop(1).all { it.size == Passive.HOLO_GROUP }
    }

    // ------------------------------------------------------------------------
    // The price, and whether it is red
    // ------------------------------------------------------------------------
    // Measured live on a result screen with an unaffordable draw: the red "30"
    // reads H 0 S 255 V 103-255 over a tight crop, against 0 red pixels in a
    // same-sized crop of the white "15" beside it. The pink "!" corner badge
    // sits near hue 150 (per NOTES.md's own measurement of that same pink on a
    // different dialog) and falls well outside this band either way.
    val RED = Dungeon.Hsv(intArrayOf(0, 150, 90), intArrayOf(8, 255, 255))
    val RED_HIGH = Dungeon.Hsv(intArrayOf(172, 150, 90), intArrayOf(179, 255, 255))
    // How much red has to be in the crop before it is worth looking at at all.
    // Measured live: a red "30" over the generous crop below scores 0.008, a
    // white "15" over the same crop scores 0.0. It is a floor on noise, not
    // the answer -- see below for why it cannot be the answer.
    const val PRICE_RED_MIN = 0.003
    // **The red price is counted, not weighed.** Weighing it was the whole
    // test once, and the whole test was wrong: the game's other summon banner
    // draws its own price -- "3,000" beside a rainbow star -- in this same red
    // on every frame, affordable or not. Measured over every stored frame with
    // a yellow button on it, both sources, 573 to 1080 px wide: that banner
    // scores 0.040 to 0.042 of the crop against 0.016 to 0.022 for a ticket
    // price the game really has turned red, so the impostor weighs *twice*
    // what the thing itself does and no threshold on ink can sit between them.
    //
    // What separates them is what the numbers are. Every ticket price in this
    // game is two characters -- 10, 15, 30, 35 -- and the banner's is five,
    // "3,000". So the red ink is broken into characters and they are counted,
    // the way the quest card's name is counted rather than read (NOTES.md),
    // and two is the only answer that means "this draw is unaffordable".
    //
    // Every number below is a share of the tallest red character in the same
    // crop, never of the crop, because a threshold measured against the crop
    // moves when the crop does. Measured over the eleven stored frames that
    // carry red above a summon button:
    //
    //   digits of one price   1.00 of the tallest, always -- they are one word
    //   the banner's comma    0.36 to 0.44
    //   dirt, edges, sparkle  0.01 to 0.25
    //
    // so the floor at 0.70 has a factor of 1.6 under it and 30 % of air above.
    const val PRICE_CHAR_MIN_H = 0.70
    // The two characters of a price stand side by side on one row and touch.
    // Measured on the same frames: the two digits of a real "30" share their
    // row to within a pixel (0.00 to 0.04 of their own height) and sit 0.02 to
    // 0.08 of that height apart. The nearest thing that ever passed the height
    // floor without being a price is a pair of sparkle slivers on the Digimon
    // page, which share a row by accident and stand **13 heights** apart, and
    // a "Level Up" frame whose two survivors are 2.6 heights apart in y.
    const val PRICE_SAME_ROW = 0.25
    const val PRICE_GAP_MAX = 0.40
    // Two, and only two. One character is not a number (NOTES.md), and the
    // banner's four digits are what this refuses.
    const val PRICE_CHARS = 2
    // The price sits directly above the button, roughly on its own column.
    // Cropped relative to the found button rather than from a fixed position,
    // so it lands right on the main screen, the result screen and the Crest
    // dialog alike. Measured live: the digits themselves sit 0.014 to 0.033 of
    // the game height above the button's own top edge -- a wider reach caught
    // the card artwork above it on the main screen, which is red often enough
    // (a fire-type Digimon) to read as the abort condition on a full wallet.
    val PRICE_ABOVE = doubleArrayOf(0.045, 0.008)
    const val PRICE_HALF_W = 0.55

    private fun priceCrop(img: Mat, button: Dungeon.Button): Mat? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val cx = x0 + button.fx * gw
        val topOfButton = y0 + (button.fy - button.fh / 2.0) * gh
        val halfW = PRICE_HALF_W * button.fw * gw
        val top = Py.int(topOfButton - PRICE_ABOVE[0] * gh)
        val bottom = Py.int(topOfButton - PRICE_ABOVE[1] * gh)
        val left = Py.int(cx - halfW)
        val right = Py.int(cx + halfW)
        return Py.crop(img, maxOf(0, top), maxOf(0, bottom), maxOf(0, left), right)
    }

    /**
     * The red character boxes above a button, left to right: x, y, w, h.
     *
     * Two hue ranges rather than one, because OpenCV wraps hue at 179/0 and
     * the reddest pixels at the centre of a stroke measure H 0-1: a single
     * range ending at 179 cuts the core out of every digit. The same fix
     * dungeon.stage_failed's own red needed.
     */
    private fun redCharacters(crop: Mat): List<IntArray> {
        val hsv = Cv.hsv(crop)
        val low = Cv.inRange(hsv, RED)
        val high = Cv.inRange(hsv, RED_HIGH)
        val red = Mat()
        Core.bitwise_or(low, high, red)
        hsv.release(); low.release(); high.release()
        try {
            val total = red.rows().toLong() * red.cols()
            if (Core.countNonZero(red).toDouble() / total < PRICE_RED_MIN) return emptyList()
            val (n, stats) = Cv.components(red)
            val boxes = (1 until n).map { stats[it] }.filter { it[2] != 0 && it[3] != 0 }
                .map { it.copyOfRange(0, 4) }
            if (boxes.isEmpty()) return emptyList()
            val tallest = boxes.maxOf { it[3] }
            return boxes.filter { it[3] >= PRICE_CHAR_MIN_H * tallest }.sortedBy { it[0] }
        } finally {
            red.release()
        }
    }

    /**
     * Is the price above this button drawn red -- the abort condition?
     *
     * True means the next tap of this mode is one the game will not take:
     * the price it costs is more than is in the bag, and the game says so by
     * turning that number red before it says anything else. It is the one
     * answer that ends a mode without a wasted tap -- the out-of-tickets
     * dialog only arrives after a tap has already been spent raising it.
     *
     * False is also the answer for the other summon banner's permanently red
     * "3,000", which is five characters and not two. See PRICE_CHARS.
     */
    fun priceIsRed(img: Mat, button: Dungeon.Button): Boolean {
        val crop = priceCrop(img, button) ?: return false
        val chars = redCharacters(crop)
        if (chars.size != PRICE_CHARS) return false
        val (ax, ay, aw, ah) = chars[0].toList()
        val (bx, by, _, bh) = chars[1].toList()
        val tall = maxOf(ah, bh).toDouble()
        if (abs((ay + ah / 2.0) - (by + bh / 2.0)) > PRICE_SAME_ROW * tall) return false
        return (bx - (ax + aw)) <= PRICE_GAP_MAX * tall
    }

    // ------------------------------------------------------------------------
    // View Ads and its counter
    // ------------------------------------------------------------------------

    /**
     * The View Ads button, or null -- also the answer for Crest, which has
     * none. Found relative to the yellow button rather than at a fixed
     * position: View Ads, 15x Summon and 35x Summon all sit at the same
     * height as it, and View Ads is the leftmost of the row.
     */
    fun viewAdsButton(img: Mat, button: Dungeon.Button? = null): Dungeon.Button? {
        val found = button ?: summonButton(img) ?: return null
        val row = Dungeon.findButtons(img, Dungeon.BLUE, minArea = 0.003, minY = 0.0)
            .filter { Dungeon.near(it.fy, found.fy, 0.04) }
            .sortedBy { it.fx }
        return if (row.size >= 2) row[0] else null
    }

    /**
     * Binary text mask, dark-on-light or light-on-dark alike.
     *
     * The ad counter is drawn dark on a light background, unlike the white-
     * on-dark ticket badge -- the same split NOTES.md notes for the price.
     * Otsu splits the crop into its two halves; the smaller one is the text,
     * a crop like this being mostly background either way.
     */
    private fun adaptiveTextMask(gray: Mat): Mat {
        val mask = Mat()
        Imgproc.threshold(gray, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        // mask.size // 2: integer division, as in Python.
        if (Core.countNonZero(mask) > mask.rows() * mask.cols() / 2) {
            val inverted = Mat()
            Core.bitwise_not(mask, inverted)
            mask.release()
            return inverted
        }
        return mask
    }

    // Measured live, the same way as the price above: a wider reach above the
    // button caught a hover-animation arrow well clear of the counter, and its
    // x happened to line up with the first digit closely enough that
    // dungeon.group_glyphs -- which groups by x-gap only, not by row -- pulled
    // it into the same group as "2 /2" and the slash search inside it failed.
    val ADS_ABOVE = doubleArrayOf(0.05, 0.005)
    const val ADS_HALF_W = 0.55
    const val ADS_SCALE = 4
    // Measured live: the film icon beside the counter is roughly square,
    // aspect 1.05, against 0.46 to 0.69 for the digits and slash -- the upper
    // bound sits between the two so the icon never joins dungeon.group_glyphs'
    // x-based grouping and swallows the slash search with its own height.
    val ADS_ASPECT = doubleArrayOf(0.15, 0.85)
    const val ADS_FILL_MIN = 0.15
    const val ADS_MIN_H = 0.06

    /**
     * Free ads left to watch, read from the "n /2" counter above View Ads.
     *
     * Both numbers count down, confirmed the hard way on the dungeon's cards
     * (NOTES.md): "2 /2" is two still to watch, not two watched.
     */
    fun adsLeft(img: Mat, button: Dungeon.Button? = null): Int? {
        val adsButton = viewAdsButton(img, button) ?: return null
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val cx = x0 + adsButton.fx * gw
        val topOfButton = y0 + (adsButton.fy - adsButton.fh / 2.0) * gh
        val halfW = ADS_HALF_W * adsButton.fw * gw
        val top = Py.int(topOfButton - ADS_ABOVE[0] * gh)
        val bottom = Py.int(topOfButton - ADS_ABOVE[1] * gh)
        val left = Py.int(cx - halfW)
        val right = Py.int(cx + halfW)
        val crop = Py.crop(img, maxOf(0, top), maxOf(0, bottom), maxOf(0, left), right)
            ?: return null
        if (crop.rows() < 2 || crop.cols() < 2) return null
        val big = Mat()
        Imgproc.resize(crop, big, Size((crop.cols() * ADS_SCALE).toDouble(),
                                       (crop.rows() * ADS_SCALE).toDouble()),
                       0.0, 0.0, Imgproc.INTER_CUBIC)
        val gray = Cv.gray(big)
        val mask = adaptiveTextMask(gray)
        big.release(); gray.release()
        try {
            val (n, stats) = Cv.components(mask)
            val glyphs = ArrayList<IntArray>()
            for (i in 1 until n) {
                val (_, _, w, h, area) = stats[i].toList()
                if (h == 0 || h < ADS_MIN_H * mask.rows()) continue
                val aspect = w / h.toDouble()
                if (!(ADS_ASPECT[0] <= aspect && aspect <= ADS_ASPECT[1])) continue
                if (area / (w * h).toDouble() < ADS_FILL_MIN) continue
                glyphs.add(stats[i])
            }
            val sorted = glyphs.sortedBy { it[0] }
            for (group in Dungeon.groupGlyphs(sorted)) {
                val digits = Dungeon.counterDigits(group)
                if (!digits.isNullOrEmpty()) return Dungeon.readCounter(mask, digits)
            }
            return null
        } finally {
            mask.release()
        }
    }
}
