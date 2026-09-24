package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * passive.py's readers, carried over line for line: `bond_bubble` with both
 * of its ways in (the cyan ring and the white body), `partner_menu` and
 * `holo_counter`. The skill's loop is not here; it comes with its own
 * session, under the director.
 *
 * Every constant keeps its Python name and value, and the sentence that says
 * where it came from. A number here is changed in passive.py first, with a
 * measurement, then the oracle is written again, then this file follows --
 * never the other way round (NOTES.md, "Two implementations, one
 * direction"). The frame is BGR, uint8, as `cv2.imread` gives it.
 */
object Passive {

    // ------------------------------------------------------------------------
    // The bond bubble
    // ------------------------------------------------------------------------
    // What is looked for is the bubble's frame, not what is in it: the food
    // symbol changes -- bread, apple, meat -- while the frame does not. It is
    // a white rounded square with a tail at the bottom, and inside the white
    // there is a cyan ring, drawn as two corner brackets facing each other
    // across the symbol: one at the upper left and one at the lower right, or
    // the other way round.
    //
    // **The cyan is the anchor, not the white.** For a long time this went
    // the other way round -- a white blob of about the right size with cyan
    // somewhere against it -- and it worked until the game drew a stage in
    // pale stone. On Haunted House the walls measure inside the same white
    // range as the bubble, the bubble's white ran into the wall behind it,
    // and what came back was a 69 x 96 blob that fell straight through the
    // width filter. The player sent a screenshot of a bubble plainly on the
    // screen that the helper could not see; a second frame, on pale purple,
    // had been sitting unread in the collection since the feature was built.
    // White is the game's most common colour and tells the bubble from
    // nothing.
    //
    // The cyan does. In the whole band there is nothing else of that hue
    // drawn that thinly, and the two brackets are a **pair**, which is the
    // rule this program already leans on everywhere else: a thing is
    // identified by its neighbour, not by its own measurements. Their
    // midpoint is the bubble's centre, to three thousandths on every frame
    // there is.
    //
    // Measured on six frames -- five window shapes and one ADB frame, two of
    // them the pale stages the white reader lost:
    //
    //   one bracket   fw 0.0320 to 0.0446   fill 0.23 to 0.29
    //   the pair      centres dx 0.33 to 0.71 of a bracket width
    //                          dy 0.56 to 1.14 of a bracket height
    //                 areas within 0.88 of each other
    //   white in a bubble-sized box at their midpoint   0.43 to 0.48
    //
    // and against that, everything else in the band that got as far as being
    // paired: white 0.02 to 0.14, except one mis-pairing of the real bubble's
    // own brackets with a stray, which sat at dx 1.07.
    //
    // The band. The player: the token can only be in the middle of the
    // picture and nowhere else, which the frames bear out -- six sightings
    // between fx 0.487 and 0.514, fy 0.317 and 0.401, and five older ones
    // inside the same window. So the search is the middle of the field with a
    // fifth of air on every side, and the two columns of side icons are
    // nowhere near it. That matters: the Daily Bonus wheel is new in the
    // inner column at fx 0.694 and it is a white and cyan disc -- the old
    // band ended at 0.68, two per cent away, and the player reported the
    // wheel being taken for the token. Its cyan quarters are solid, filling
    // 0.64 of their boxes against a bracket's 0.29, so the pair test refuses
    // it as well wherever it is drawn.
    //
    // **The slot is not where the 9:16 frames have it on a long display.**
    // The Poco F3 (1080 x 2400, corpus/passive/phone_tall_*) drew the
    // partner's badge and bar 0.1 further right than any stored frame, and
    // the bubble over it at fx 0.590, 0.590 and 0.608 -- the last of them
    // with its right bracket beyond the band's old edge at 0.62, where
    // neither reader saw a bubble plainly on the screen
    // (phone_tall_bubble_right_ice_164440). So the band reaches to 0.70,
    // and the middle rule below is what keeps the icon column out of it.
    // The same at the left: four 1080 x 1920 frames have the bubble at fx
    // 0.422 to 0.424 with a ring 0.046 to 0.050 across, its left edge
    // within 0.003 of the old 0.40, and a painted one at 0.42 is cut and
    // lost -- so the band starts at 0.36, a whole bubble of air.
    val BUBBLE_BAND = doubleArrayOf(0.36, 0.70, 0.26, 0.50)
    val BUBBLE_WHITE = Dungeon.Hsv(intArrayOf(0, 0, 200), intArrayOf(179, 45, 255))
    val BUBBLE_CYAN = Dungeon.Hsv(intArrayOf(86, 90, 160), intArrayOf(100, 255, 255))
    // One bracket: thin, and about half the bubble across.
    val BUBBLE_ARM_W = doubleArrayOf(0.024, 0.058)
    val BUBBLE_ARM_FILL = doubleArrayOf(0.15, 0.42)
    // The pair, in units of a bracket's own width and height. A bracket that
    // is not part of a ring has nothing sitting diagonally beside it. The
    // upper bound was 1.30 until a battle frame (corpus/passive/bond-1.png)
    // measured 1.353 on a real pair -- fill 0.29 and 0.29, ratio 0.99,
    // nothing else in the band near that shape -- and widening it is free:
    // the next thing this test has ever had to refuse scored 1.14.
    const val BUBBLE_PAIR_DX = 0.85
    val BUBBLE_PAIR_DY = doubleArrayOf(0.40, 1.36)
    // Two arcs of the same ring are drawn the same. 0.88 at worst, and the
    // one mis-pairing that got this far scored 0.87 -- so this is a second
    // opinion, not the thing that decides.
    const val BUBBLE_PAIR_RATIO = 0.70
    // The bubble is still white, and this is where that is asked: over a
    // bubble-sized box at the pair's midpoint. 0.43 to 0.48 against 0.14 for
    // the best impostor, a factor of 1.4 either way -- and it holds on the
    // pale stages, where the wall only adds white to a box that is mostly
    // white already.
    const val BUBBLE_WHITE_MIN = 0.30
    // How big the box is that white share is taken over: the bubble's own
    // width, measured 0.0535 to 0.0610 across the ring plus its border.
    const val BUBBLE_SIDE = 0.056

    // The middle of the field, which is the only place the token is ever
    // drawn.
    //
    // The player, after a live session in which the helper tapped the white
    // and turquoise Digimon standing off to the left and the new Daily Bonus
    // icon on the right: the bond bubble can only be in the middle of the
    // screen, so a rule is wanted that refuses everything else. It is the
    // right rule, and the frames bear it out. Every sighting there is, over
    // the whole stored collection and both frame sources -- 573 x 1056 up to
    // 1080 x 1920:
    //
    //   fx  0.4814 to 0.5090   (16 frames), and 0.487 to 0.514 recorded before
    //   fy  0.3170 to 0.4001
    //
    // so 0.033 of the width across everything the game has ever shown. The
    // middle below is 0.12 wide, three times that spread on either side of
    // it, because a rule that refuses a real bubble costs the player the
    // token.
    //
    // What it refuses, measured on corpus/passive/unclear_175031.png, an ADB
    // frame of the main screen with the new icons on it:
    //
    //   Daily Bonus wheel   fx 0.6922    Events    fx 0.6940
    //   the outer column    fx 0.7899    a white and turquoise Digimon fx 0.3017
    //
    // 0.13 of the width from the nearest of them to the edge of the middle, a
    // fifth of the field -- not the two per cent of air that let the wheel in
    // the first time. And it is a rule about the middle rather than about
    // those four icons: a band keeps out the impostor whose place is known
    // and says nothing about the one the game draws somewhere else tomorrow.
    //
    // BUBBLE_BAND stays wider than this on purpose. It is the crop the blobs
    // are found in, and a bubble whose ring is clipped by the edge of the
    // crop fails the width and fill tests -- so the search has room and the
    // *centre* is what has to sit in the middle.
    //
    // **The middle was 0.44 to 0.56 until 2026-09-22, and the token is not
    // only ever drawn there.** The sightings above are the frames a reader
    // answered on; the frames it refused had been kept all along under
    // `bond-not-the-middle`, and looked at together with the Poco F3's,
    // fourteen of them are the real bubble: at fx 0.565 to 0.612 on ten
    // 1080 x 1920 frames of a bonus event, at 0.590 to 0.608 on the phone,
    // and at 0.422 to 0.434 on four more and the 1080 x 2340 twin. The slot
    // moves with the party's formation, and a rule about the middle refused
    // the token on the phone for a whole afternoon (the log of 2026-09-22,
    // 15:22 to 19:54). What says "this is the token" now is the bar it
    // hangs off (BAR_GREEN and the block above it), asked inside both
    // readers; what is left for this fence is the icon column, 0.05 outside
    // it, and the band's own edges.
    val BOND_MIDDLE = doubleArrayOf(0.40, 0.64, 0.26, 0.50)

    /** Is this where a bond bubble can be at all? */
    fun inMiddle(fx: Double, fy: Double): Boolean {
        val (x0, x1, y0, y1) = BOND_MIDDLE.toList()
        return x0 <= fx && fx <= x1 && y0 <= fy && fy <= y1
    }

    /** BUBBLE_BAND cut out of the frame, with the crop's corner. */
    private class BandCrop(val left: Int, val top: Int, val sub: Mat?)

    /**
     * `img[top:int(y0 + fy1 * gh), left:int(x0 + fx1 * gw)]` over
     * BUBBLE_BAND, and null for the `sub.size == 0 or sub.shape[0] < 2 or
     * sub.shape[1] < 2` that all three band readers ask first.
     */
    private fun bubbleBand(img: Mat, x0: Int, y0: Int, gw: Int, gh: Int): BandCrop {
        val (fx0, fx1, fy0, fy1) = BUBBLE_BAND.toList()
        val left = maxOf(0, Py.int(x0 + fx0 * gw))
        val top = maxOf(0, Py.int(y0 + fy0 * gh))
        val sub = Py.crop(img, top, Py.int(y0 + fy1 * gh), left, Py.int(x0 + fx1 * gw))
        return if (sub == null || sub.rows() < 2 || sub.cols() < 2) BandCrop(left, top, null)
        else BandCrop(left, top, sub)
    }

    // Which colour to anchor on is a property of the frame, not of the
    // bubble. Binary Road is drawn in the bubble's own cyan, and on that
    // stage the ring is the same colour as the ground under it: the two
    // brackets are not two blobs any more, they and the background are one.
    // Measured, cyan share of the whole band against white:
    //
    //                                    cyan     white
    //   bond-bug-2   pale stone           0.002    0.866
    //   171510       Binary Road          0.742    0.022
    //   bubble-cyan-adb Binary Road, ADB  0.449    0.026
    //   42 stored full frames, median     0.010    0.042
    //
    // On every frame there is, at least one of the two is rare -- so the
    // anchor is whichever share is smaller here, decided fresh each frame
    // rather than fixed in advance for every stage. Cyan is what most frames
    // want, which is why the pair-of-brackets reader above is unchanged and
    // stays the one that runs most of the time.

    /** Cyan and white as a share of the whole search band, for that choice. */
    private fun bubbleBandShares(img: Mat, x0: Int, y0: Int, gw: Int, gh: Int): Pair<Double, Double> {
        val sub = bubbleBand(img, x0, y0, gw, gh).sub ?: return 0.0 to 0.0
        val hsv = Cv.hsv(sub)
        val cyan = Cv.inRange(hsv, BUBBLE_CYAN)
        val white = Cv.inRange(hsv, BUBBLE_WHITE)
        val out = Cv.share(cyan) to Cv.share(white)
        hsv.release(); cyan.release(); white.release()
        return out
    }

    // The fallback, for the frames where white is the rare one: the bubble's
    // own white body, found the way the Digital World Search card is -- a
    // clump of the colour of the bubble's size, biggest first.
    //
    // Measured on the two Binary Road frames, both sources:
    //
    //            area  fw      fh      fill    next largest blob in the band
    //   window    454  0.0501  0.0300  0.458   40  (a factor of 11)
    //   ADB      1518  0.0532  0.0298  0.422   212 (a factor of 7)
    //
    // and the cyan ring around it still shows, in a bubble-sized box at the
    // blob's own centre -- 0.23 and 0.28, well above a background that is
    // mostly solid cyan away from the bubble but well under it, because the
    // box there is mostly the bubble's own white: a second opinion, not the
    // thing that decides.
    //
    // Until 2026-09-22 the biggest clump had to be three times the runner-up
    // or the reader answered nothing. That was the whole of what told the
    // bubble from another white clump, and the moment the band grew to 0.70
    // it cost two real bubbles on 1080 x 1920 (corpus/bond,
    // open_the_Digimon_page_120033 and select_the_Partner_tab_120648): an
    // explosion beside the bubble was the runner-up. What tells the bubble
    // from a clump now is the bar it hangs off (BAR_* below), and every
    // clump of the right size is asked in turn, biggest first.
    val BUBBLE_BODY_W = doubleArrayOf(0.044, 0.060)
    val BUBBLE_BODY_ASPECT = doubleArrayOf(0.80, 1.25)
    val BUBBLE_BODY_FILL = doubleArrayOf(0.30, 0.60)
    const val BUBBLE_BODY_CYAN_MIN = 0.15

    // **The bubble hangs off the partner's HP bar, and the bar is what says
    // it is the partner's.** The badge, the green bar and the bubble are one
    // drawing the game puts over whichever Digimon is being raised, and the
    // Digimon stands under it; the drawing moves with the party's formation,
    // and the middle of the field turned out to be no rule for where it is.
    // Measured 2026-09-22 over every candidate the two readers above offer
    // on every main-screen frame of the corpus, 189 of them, 9:16 frames
    // and the Poco F3's 1080 x 2400 alike:
    //
    //   a real bubble, ~150 frames: a flat green blob below and to its
    //   left, left end -0.095 to -0.097 gw from the bubble's centre, the
    //   blob's own centre +0.019 to +0.028 gh under it, 0.0065 to 0.0077 gh
    //   tall, fill 0.88 to 1.00 -- and 0.011 to 0.077 gw wide, because the
    //   green is what is left of the HP, so the right end is no evidence.
    //
    //   an impostor -- ice, fire, a reward window's ticket, a WILD card, a
    //   snowflake, the buff icons beside the badge -- 40 candidates: no
    //   green in the box at all on 34; on three the partner's own bar, far
    //   off, left end -0.16; on two a fleck of 0.0035 gw; on one a bar whose
    //   left end sat at -0.053.
    //
    // Colour, sampled on the bar itself on four frames of both sources:
    // H 45 to 50, S 190 to 239, V 153 to 255, median 50/233/255. The box
    // and the left end have 0.015 of air either side of every real
    // reading, and the closest impostor is 0.03 outside.
    //
    // What it costs: a bubble whose bar is hidden or gone. Two frames of
    // ~150 -- corpus/passive/bond-not-the-middle_211258, where an attack
    // effect covers the bar, and corpus/bond/select_the_Partner_tab_120136,
    // a page just opened with no bar drawn yet -- read null now, and a
    // Digimon with no HP left is about to take its bubble with it anyway
    // (PassiveSkill.BOND_WATCH).
    val BAR_GREEN = Dungeon.Hsv(intArrayOf(42, 150, 140), intArrayOf(56, 255, 255))
    // The box below and left of a candidate the bar is looked for in, in
    // window units from the candidate's centre: x, then y.
    val BAR_BOX = doubleArrayOf(-0.12, -0.01, 0.008, 0.040)
    // Where the bar's left end has to be, and where its centre.
    val BAR_LEFT = doubleArrayOf(-0.11, -0.08)
    val BAR_DY = doubleArrayOf(0.012, 0.035)
    const val BAR_W_MIN = 0.008
    const val BAR_H_MAX = 0.012
    const val BAR_FILL_MIN = 0.80

    /**
     * What `bond_bubble` returns, the same shape as dungeon.auto_button: fx,
     * fy, fw, fh as fractions of the reference window, plus the white share
     * that won.
     */
    data class Bubble(val fx: Double, val fy: Double, val fw: Double, val fh: Double,
                      val white: Double) {
        fun toOracle(): Map<String, Any?> =
            mapOf("fx" to fx, "fy" to fy, "fw" to fw, "fh" to fh, "white" to white)
    }

    /** The bubble found by its cyan ring, as a pair of corner brackets. */
    private fun bubbleByRing(img: Mat, x0: Int, y0: Int, gw: Int, gh: Int): Bubble? {
        val (left, top, arms) = cyanArms(img, x0, y0, gw, gh)
        var best: Bubble? = null
        for (i in arms.indices) {
            val one = arms[i]
            for (other in arms.subList(i + 1, arms.size)) {
                val wide = (one[2] + other[2]) / 2.0
                val tall = maxOf((one[3] + other[3]) / 2.0, 1.0)
                val dx = abs((one[0] + one[2] / 2.0) - (other[0] + other[2] / 2.0)) / wide
                val dy = abs((one[1] + one[3] / 2.0) - (other[1] + other[3] / 2.0)) / tall
                if (dx > BUBBLE_PAIR_DX) continue
                if (!(BUBBLE_PAIR_DY[0] <= dy && dy <= BUBBLE_PAIR_DY[1])) continue
                if (minOf(one[4], other[4]) / maxOf(one[4], other[4]).toDouble()
                        < BUBBLE_PAIR_RATIO) continue
                val cx = left + ((one[0] + one[2] / 2.0) + (other[0] + other[2] / 2.0)) / 2.0
                val cy = top + ((one[1] + one[3] / 2.0) + (other[1] + other[3] / 2.0)) / 2.0
                val share = whiteShare(img, cx, cy, gw)
                if (share < BUBBLE_WHITE_MIN) continue
                if (!hasBar(img, cx, cy, x0, y0, gw, gh)) continue
                if (best != null && share <= best.white) continue
                // The box the two brackets span, which is the bubble to
                // within its own border: 0.054 to 0.061 of the window
                // across, against the 0.0535 to 0.0593 the white reader
                // used to report.
                val ringW = maxOf(one[0] + one[2], other[0] + other[2]) - minOf(one[0], other[0])
                val ringH = maxOf(one[1] + one[3], other[1] + other[3]) - minOf(one[1], other[1])
                best = Bubble((cx - x0) / gw, (cy - y0) / gh,
                              ringW / gw.toDouble(), ringH / gh.toDouble(), share)
            }
        }
        return best
    }

    /** The bubble found by its white body, on a stage drawn in its own cyan. */
    private fun bubbleByBody(img: Mat, x0: Int, y0: Int, gw: Int, gh: Int): Bubble? {
        val band = bubbleBand(img, x0, y0, gw, gh)
        val sub = band.sub ?: return null
        val white = Cv.hsvMask(sub, BUBBLE_WHITE)
        val (count, stats) = Cv.components(white)
        white.release()
        // Each candidate as Python builds it: (area, x, y, w, h).
        val candidates = ArrayList<IntArray>()
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (w == 0 || h == 0) continue
            val fw = w / gw.toDouble()
            if (!(BUBBLE_BODY_W[0] <= fw && fw <= BUBBLE_BODY_W[1])) continue
            val aspect = w / h.toDouble()
            if (!(BUBBLE_BODY_ASPECT[0] <= aspect && aspect <= BUBBLE_BODY_ASPECT[1])) continue
            val fill = area / (w * h).toDouble()
            if (!(BUBBLE_BODY_FILL[0] <= fill && fill <= BUBBLE_BODY_FILL[1])) continue
            candidates.add(intArrayOf(area, x, y, w, h))
        }
        // `candidates.sort(key=lambda c: -c[0])`: stable, and so is sortedBy.
        for (candidate in candidates.sortedBy { -it[0] }) {
            val (area, x, y, w, h) = candidate.toList()
            val cx = band.left + x + w / 2.0
            val cy = band.top + y + h / 2.0
            if (cyanShare(img, cx, cy, gw) < BUBBLE_BODY_CYAN_MIN) continue
            if (!hasBar(img, cx, cy, x0, y0, gw, gh)) continue
            return Bubble((cx - x0) / gw, (cy - y0) / gh, w / gw.toDouble(), h / gh.toDouble(),
                          area / (w * h).toDouble())
        }
        return null
    }

    /**
     * Is the partner's HP bar where a bubble at this point would hang off
     * it? The measurement is above BAR_GREEN. [cx] and [cy] are in the
     * frame's pixels, the rest is the reference rect.
     */
    private fun hasBar(img: Mat, cx: Double, cy: Double, x0: Int, y0: Int, gw: Int, gh: Int): Boolean {
        val left = Py.int(cx + BAR_BOX[0] * gw)
        val right = Py.int(cx + BAR_BOX[1] * gw)
        val top = Py.int(cy + BAR_BOX[2] * gh)
        val bottom = Py.int(cy + BAR_BOX[3] * gh)
        val sub = Py.crop(img, maxOf(0, top), bottom, maxOf(0, left), right) ?: return false
        val green = Cv.hsvMask(sub, BAR_GREEN)
        val (count, stats) = Cv.components(green)
        green.release()
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (w == 0 || h == 0) continue
            if (w / gw.toDouble() < BAR_W_MIN || h / gh.toDouble() > BAR_H_MAX) continue
            if (area / (w * h).toDouble() < BAR_FILL_MIN) continue
            val start = (maxOf(0, left) + x - cx) / gw
            val dy = (maxOf(0, top) + y + h / 2.0 - cy) / gh
            if (start < BAR_LEFT[0] || start > BAR_LEFT[1]) continue
            if (dy < BAR_DY[0] || dy > BAR_DY[1]) continue
            return true
        }
        return false
    }

    // Where the figure is -- BOND_FIGURE, BOND_TAP_LIMITS, CHAT_ROW and with
    // them aim_at_figure and figure_from_bubble -- is the skill's aim and not
    // a reader. It comes over with the passive helper's loop, sentences and
    // all.

    /**
     * The corner brackets in the middle of the field, left to right.
     *
     * Everything of the bubble's cyan that is drawn as thinly as the bubble
     * draws it. What comes back is the raw stats rows, in the crop's own
     * pixels, with the crop's corner so the caller can put them back.
     */
    private fun cyanArms(img: Mat, x0: Int, y0: Int, gw: Int, gh: Int): Triple<Int, Int, List<IntArray>> {
        val band = bubbleBand(img, x0, y0, gw, gh)
        val sub = band.sub ?: return Triple(band.left, band.top, emptyList())
        val cyan = Cv.hsvMask(sub, BUBBLE_CYAN)
        val (count, stats) = Cv.components(cyan)
        cyan.release()
        val arms = ArrayList<IntArray>()
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (w == 0 || h == 0) continue
            val fw = w / gw.toDouble()
            if (!(BUBBLE_ARM_W[0] <= fw && fw <= BUBBLE_ARM_W[1])) continue
            val fill = area / (w * h).toDouble()
            if (!(BUBBLE_ARM_FILL[0] <= fill && fill <= BUBBLE_ARM_FILL[1])) continue
            arms.add(intArrayOf(x, y, w, h, area))
        }
        return Triple(band.left, band.top, arms.sortedBy { it[0] })
    }

    /**
     * A bubble-sized box at this point, `img[max(0, top):top + side,
     * max(0, left):left + side]`, or null where numpy's would be empty.
     */
    private fun bubbleBox(img: Mat, cx: Double, cy: Double, gw: Int): Mat? {
        val side = maxOf(4, Py.int(BUBBLE_SIDE * gw))
        val top = Py.int(cy - side / 2.0)
        val left = Py.int(cx - side / 2.0)
        return Py.crop(img, maxOf(0, top), top + side, maxOf(0, left), left + side)
    }

    /**
     * How much of a bubble-sized box at this point is white.
     *
     * Taken from the frame rather than from the band's crop, so that a
     * bubble near the edge of the band is measured over the same box as one
     * in the middle of it.
     */
    private fun whiteShare(img: Mat, cx: Double, cy: Double, gw: Int): Double {
        val box = bubbleBox(img, cx, cy, gw) ?: return 0.0
        val white = Cv.hsvMask(box, BUBBLE_WHITE)
        val out = Cv.share(white)
        white.release()
        return out
    }

    /**
     * How much of a bubble-sized box at this point is cyan.
     *
     * bubbleByBody's own second opinion, the same shape of check as
     * whiteShare above and taken the same way, from the frame rather than
     * the crop.
     */
    private fun cyanShare(img: Mat, cx: Double, cy: Double, gw: Int): Double {
        val box = bubbleBox(img, cx, cy, gw) ?: return 0.0
        val cyan = Cv.hsvMask(box, BUBBLE_CYAN)
        val out = Cv.share(cyan)
        cyan.release()
        return out
    }

    /**
     * The bond bubble, or null if the screen is not showing one.
     *
     * Which reader runs is decided fresh every frame: cyan and white are
     * measured over the whole search band, and whichever is rarer there is
     * the one the bubble is found by. Most frames want cyan, which is why
     * bubbleByRing is the one that has always lived here; bubbleByBody is
     * the frames where the stage itself is drawn in the bubble's own cyan,
     * and the ring stops being two blobs because it and the ground are one.
     * See the block above BUBBLE_BODY_W for the numbers.
     *
     * **Both of them run, rarer colour first.** The share only says which
     * is the better bet on this frame, and the better bet still misses: the
     * game put an orange "x10" starburst on the bubble's top right corner
     * during a bonus event -- over one of the two cyan brackets, merging
     * what was left of it with the badge's own blue outline into a blob
     * 1.47 bracket widths from its partner where the pair rule allows 0.85.
     * The ring reader answered None with the bubble plainly on the screen
     * and the tour walked past a token for 25 seconds. The body reader had
     * it exactly right on the same frame, at 0.480/0.362 -- the badge does
     * not touch the white body. They fail at different things, which is the
     * whole reason to have two, and choosing between them in advance threw
     * that away. Cheap, too: the second one only runs on a frame where the
     * first found nothing.
     *
     * Whichever of the two answers, the answer has to sit in the middle of
     * the field -- BOND_MIDDLE, and the block above it for why. Something
     * white and turquoise that the game draws somewhere else is not this
     * token, and a candidate that is refused here is the only thing this
     * reader has to say about a wrong tap, so `log` is where it says it.
     */
    fun bondBubble(img: Mat, log: ((String) -> Unit)? = null): Bubble? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val (cyanShare, whiteShare) = bubbleBandShares(img, x0, y0, gw, gh)
        val ring = { bubbleByRing(img, x0, y0, gw, gh) }
        val body = { bubbleByBody(img, x0, y0, gw, gh) }
        val readers = if (cyanShare <= whiteShare) listOf(ring, body) else listOf(body, ring)
        var found: Bubble? = null
        for (reader in readers) {
            found = reader()
            if (found != null) break
        }
        if (found == null) return null
        if (!inMiddle(found.fx, found.fy)) {
            log?.invoke(("  something bubble-shaped at %.3f/%.3f, which is not the " +
                "middle of the field -- the token is only ever drawn there, " +
                "so this is not one").format(found.fx, found.fy))
            return null
        }
        return found
    }

    // ------------------------------------------------------------------------
    // The Partner window
    // ------------------------------------------------------------------------
    // What opens when the figure is tapped and no token is there. The picture
    // in it is a different Digimon every time, and the name and skills are
    // text, so neither can be what identifies it. Its two buttons can: a
    // violet Encyclopedia and a blue Move, side by side, the same size. That
    // is the pair rule the dungeon bot already leans on -- a button is
    // identified by its neighbour, not by its own position.
    //
    // **There are two of these windows, and the row was measured on one.**
    // The tap on the figure opens the Partner window for the Digimon that
    // owns the bubble -- and the Buddy's own window when the aim lands on the
    // Buddy standing beside it, which happened twice on one live tour. Same
    // pair, same widths, same gap; a taller panel, so the row sits lower.
    // Every frame of either window there is, both sources, 571 x 1053 to
    // 1080 x 1920:
    //
    //   Partner  violet fx 0.3254 - 0.3292   row fy 0.7320 - 0.7359
    //   Buddy    violet fx 0.3235 - 0.3317   row fy 0.7866 - 0.7934
    //   both     gap 0.2794 - 0.2977   fw 0.229 - 0.252
    //
    // A fixed row of 0.733 with 0.04 either way stops at 0.773 and the Buddy
    // window sits at 0.787: outside by fourteen thousandths, so it was not
    // recognised, not closed, and the tour walked into the Partner page with
    // it over the nav bar.
    //
    // So the row is a band wide enough for both, and what keeps that band
    // honest is the pair rather than the position. The main screen has its
    // own violet and blue pair along the bottom -- fx 0.469, row fy 0.933 to
    // 0.941, gap 0.255 to 0.260, on five stored frames -- and it is refused
    // by the row (0.10 away) and by the violet's own place (0.14 away) both.
    const val PARTNER_VIOLET_X = 0.327
    const val PARTNER_BLUE_X = 0.622
    // The Partner window's own row, which is what the suite paints.
    const val PARTNER_Y = 0.733
    const val PARTNER_TOL = 0.04
    // The band both windows fall in: 0.032 under the highest real row and
    // 0.037 over the lowest, against 0.103 to the main screen's pair.
    val PARTNER_ROW = doubleArrayOf(0.70, 0.83)
    // The two buttons share a row and stand a fixed distance apart.
    const val PARTNER_ROW_TOL = 0.02
    val PARTNER_GAP = doubleArrayOf(0.26, 0.32)
    // **The dungeon panel draws the same pair, wider.** Clear Previous
    // Difficulty (violet) beside Attempt (blue) is a violet-and-blue pair at
    // the same place: on the Fight! Bakemon panel of 2026-09-22
    // (corpus/dungeon/panel_clear_previous_114807.png) the violet stands at
    // fx 0.3235, row 0.7083, gap 0.3042 -- inside every band above, and the
    // director parked on "partner_window" in the middle of a dungeon run.
    // What the pair cannot share is its width: the window's buttons measure
    // fw 0.229 to 0.252 over all 21 window frames in the oracle, 571 x 1053
    // to 1080 x 2340, Partner and Buddy alike; the panel's measure 0.2842,
    // on Bakemon's panel and on Digifactory's to the digit
    // (panel_clear_previous_digifactory_120140.png). The ceiling sits
    // between them, 0.017 over the widest window and 0.014 under the panel.
    // The height is no use, 0.039 to 0.046 against 0.052.
    const val PARTNER_W_MAX = 0.27

    // Whose window it is and how it is closed -- PARTNER_OWN, PARTNER_CLOSE,
    // PARTNER_TAPS -- belong to the skill's loop and come over with it.

    /**
     * A Digimon's own window -- the Partner one or the Buddy one -- or null.
     *
     * A yes or no. The answer is the violet button, and nothing uses it --
     * the window is closed by a tap above the panel and then by the back
     * key, see PARTNER_CLOSE and PARTNER_TAPS in passive.py.
     *
     * Found by the pair and not by a row: the two windows put the same pair
     * at two different heights, and it was the height that was written
     * down. See the block above PARTNER_VIOLET_X.
     *
     * Not proof of whose window it is. One live report has this matching a
     * screen that was not the Partner window at all, so the caller decides
     * by what the helper itself did, not by what this recognised.
     */
    fun partnerMenu(img: Mat): Dungeon.Button? {
        val violets = Dungeon.findButtons(img, Dungeon.VIOLET, minArea = 0.004, minY = 0.0)
            .filter { b ->
                Dungeon.near(b.fx, PARTNER_VIOLET_X, PARTNER_TOL) &&
                    PARTNER_ROW[0] <= b.fy && b.fy <= PARTNER_ROW[1] &&
                    b.fw <= PARTNER_W_MAX               // the dungeon panel's pair is wider
            }
        if (violets.isEmpty()) return null
        val blues = Dungeon.findButtons(img, Dungeon.BLUE, minArea = 0.004, minY = 0.0)
        for (violet in violets) {
            for (blue in blues) {
                if (abs(blue.fy - violet.fy) > PARTNER_ROW_TOL) continue
                val gap = blue.fx - violet.fx
                if (!(PARTNER_GAP[0] <= gap && gap <= PARTNER_GAP[1])) continue
                return violet
            }
        }
        return null
    }

    // ------------------------------------------------------------------------
    // The hologram counter
    // ------------------------------------------------------------------------
    // The number under the hologram device, "77,605 / 10". The left side is
    // what is left, the right side is Items per Activation from the device's
    // own settings and is not read.
    //
    // Measured again after game_rect learned to find the game inside a
    // window that has no sidebar. Every fraction in this file moved a little
    // when that landed, and this band is the tightest one there is -- the old
    // numbers put its edge through the tops of the digits, and a window
    // resized to a different shape then slid them out of it altogether.
    //
    // Over nine frames at six window shapes, from 497 x 914 to 765 x 1390,
    // the counter's own box now measures:
    //
    //   left edge of the first digit   0.4109 - 0.4129
    //   top edge of the row            0.8617 - 0.8648
    //   bottom edge of the row         0.8729 - 0.8755
    //
    // A spread of two thousandths across every shape, which is what a stable
    // coordinate vocabulary looks like. The band keeps a full digit height of
    // air above and below, so nothing sits on an edge.
    val HOLO_BAND = doubleArrayOf(0.36, 0.62, 0.845, 0.895)
    const val HOLO_SCALE = 4
    val HOLO_WHITE = intArrayOf(200, 255)
    // The glyph filter, all of it relative to the crop so that the window
    // size does not matter. Measured over the same four frames, as a share of
    // the scaled crop's height:
    //
    //   digits          0.278 - 0.300
    //   the slash       0.343 - 0.369
    //   the comma       0.111 - 0.125
    //   what else is in the band (XP numbers, the device's rim) is wider than
    //   it is tall, 1.4 to 7.9, where a digit measures 0.58 to 0.78
    //
    // The comma is the new one against the dungeon's card counters, and it
    // is the one glyph that must never reach read_digit -- it would come back
    // as a digit and turn 1,000 into 10000. dungeon.GLYPH_MIN_H at 0.20
    // already parts it from the digits with room on both sides, so it is
    // reused rather than a second number invented.
    val HOLO_ASPECT = doubleArrayOf(0.15, 1.20)
    const val HOLO_FILL_MIN = 0.15
    // A noise floor and nothing more. It used to be a real threshold, set
    // against the crop's height -- and then the crop was made taller to
    // survive a resized window, every proportion to it shrank, and the comma
    // dropped through. With no comma the number lost the rule that says three
    // digits follow one, and a counter that had been read for days went
    // unreadable.
    //
    // The lesson is the one NOTES.md already carries: measure the shape
    // against something that belongs to the thing itself. Here that is the
    // slash, and every proportion that decides anything is taken against it
    // further down. What is left here is only "too small to be a character at
    // all": the comma measures 0.07 of the crop at its smallest, single-pixel
    // noise far less.
    const val HOLO_MIN_H = 0.04
    // The slash, and the row it stands in.
    //
    // dungeon.group_glyphs splits a badge into counters by the gaps between
    // characters, which is right for a strip that holds nothing else. This
    // crop does hold something else: the XP numbers the device throws up sit
    // directly above the counter and are white, the same size and the same
    // shape as a digit. Split by gaps alone they join the number and 77,105
    // reads as 7,727,375 -- measured, on bond-5.
    //
    // So the row is what selects here, and the slash defines it. Measured
    // over four frames:
    //
    //   slash, width over height   0.45 - 0.46
    //   digits, width over height  0.58 - 0.78
    //   digit height over slash    0.78 - 0.81
    //   comma height over slash    0.33 - 0.34
    //   digit centre off the slash centre   2 to 4 px of a 51 px slash
    //
    // The XP numbers sit a whole row higher, 60 px off that centre, and that
    // is what throws them out.
    const val HOLO_SLASH_WH = 0.52
    const val HOLO_DIGIT_H_MIN = 0.60
    // Digits are shorter than the slash beside them, measured 0.78 to 0.85 of
    // it. The upper bound is what stops a row of digits from being taken for
    // the slash of some other row.
    const val HOLO_DIGIT_H_MAX = 0.95
    const val HOLO_ROW_TOL = 0.25
    // The comma, as a share of the slash: short, and sitting low. Measured on
    // the four frames, and the two numbers cluster tightly -- height 0.32 to
    // 0.34 of the slash, centre 0.29 to 0.33 of a slash height below the
    // row's own centre, where a digit sits within 0.06 of it.
    //
    // Tight on purpose. The device's rim throws off specks: one of them
    // measured 0.25 and 0.54 and a window loose enough to hold it made bond-1
    // read as a number with two commas, which the grouping below then threw
    // away whole.
    val HOLO_COMMA_H = doubleArrayOf(0.28, 0.40)
    val HOLO_COMMA_DROP = doubleArrayOf(0.22, 0.42)
    // The digits of one number are all the same height, and a digit that is
    // not is a digit something is drawn over. That is not a rare case here:
    // the XP numbers rising off the device clip the tops of the digits under
    // them, and a clipped digit does not come back as unreadable, it comes
    // back as another digit. On bond-5 a clipped 1 and 0 read as 2 and 3, and
    // 77,105 became 77,235 -- a number that looks perfectly reasonable and is
    // wrong, which is the worst kind.
    //
    // Measured, shortest digit of a number over the tallest:
    //
    //   the four clean frames   0.958, 0.968, 0.976, 1.000
    //   bond-5, two clipped     0.878
    //
    // So an uneven row is thrown away whole, and the next tick reads it
    // again.
    const val HOLO_DIGIT_EVEN = 0.93

    // Two structural checks on top, because a clipped digit does not always
    // come back shorter -- sometimes it does not come back at all, and a
    // number that has quietly lost a digit still looks like a number. 77,605
    // reading as 7,605 would be a fall of seventy thousand that never
    // happened.
    //
    //   The commas. The game writes thousands separators, so the digits
    //   after a comma are exactly three, and a number of four digits or more
    //   without one is not a number that was read properly.
    //
    //   The spacing. Measured between neighbouring characters of the
    //   counter, including the comma: 11 to 16 pixels where a digit is 27
    //   wide. A missing digit leaves a hole of 41. One digit width is the
    //   line between them, with room on both sides.
    const val HOLO_GROUP = 3
    const val HOLO_GAP_MAX = 1.0

    /** The counter's crop, scaled up and reduced to its white text. */
    private fun holoMask(img: Mat): Mat? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val (fx0, fx1, fy0, fy1) = HOLO_BAND.toList()
        val left = maxOf(0, Py.int(x0 + fx0 * gw))
        val top = maxOf(0, Py.int(y0 + fy0 * gh))
        val sub = Py.crop(img, top, Py.int(y0 + fy1 * gh), left, Py.int(x0 + fx1 * gw))
            ?: return null
        if (sub.rows() < 2 || sub.cols() < 2) return null
        val big = Mat()
        Imgproc.resize(sub, big, Size((sub.cols() * HOLO_SCALE).toDouble(),
                                      (sub.rows() * HOLO_SCALE).toDouble()),
                       0.0, 0.0, Imgproc.INTER_CUBIC)
        val gray = Cv.gray(big)
        val mask = Mat()
        Core.inRange(gray, Scalar(HOLO_WHITE[0].toDouble()), Scalar(HOLO_WHITE[1].toDouble()), mask)
        big.release(); gray.release()
        return mask
    }

    /**
     * Characters in the counter crop, from left to right.
     *
     * The same job as dungeon.badge_glyphs, with one difference that
     * matters: the area filter there is an absolute number of pixels, and
     * this crop is a good deal smaller than a dungeon card's badge. At 497 px
     * window width the slash measures 150 pixels against that filter's 200,
     * so it would be thrown away and with it the only thing that says where
     * the number ends. Everything here is therefore relative to the crop.
     */
    private fun holoGlyphs(mask: Mat): List<IntArray> {
        val (count, stats) = Cv.components(mask)
        val out = ArrayList<IntArray>()
        for (i in 1 until count) {
            val (_, _, w, h, area) = stats[i].toList()
            if (h == 0 || h < HOLO_MIN_H * mask.rows()) continue
            val aspect = w / h.toDouble()
            if (!(HOLO_ASPECT[0] <= aspect && aspect <= HOLO_ASPECT[1])) continue
            if (area / (w * h).toDouble() < HOLO_FILL_MIN) continue
            out.add(stats[i])
        }
        return out.sortedBy { it[0] }
    }

    /**
     * How many hologram tickets are left, or null if it cannot be read.
     *
     * All or nothing, like dungeon.read_counter: half a number would be read
     * as a fall that never happened and would have the bot press a button
     * that is already doing its job.
     *
     * Null is the answer on any screen that is not the clear main screen.
     * The game dims what is behind a dialog and the dimmed digits drop out
     * of the white mask -- checked on two frames of the Partner window, where
     * this finds no glyphs at all.
     */
    fun holoCounter(img: Mat): Int? {
        val mask = holoMask(img) ?: return null
        try {
            val digits = counterRow(holoGlyphs(mask))
            if (digits.isNullOrEmpty()) return null
            return Dungeon.readCounter(mask, digits)
        } finally {
            mask.release()
        }
    }

    /**
     * The digits of the counter, or null if none of the crop is one.
     *
     * Every narrow character is tried as the slash, and the one that turns
     * out to have a proper number written to its left wins. Taking the
     * tallest and hoping was the first version, and it worked only while the
     * crop was cut so tightly around the counter that nothing else could get
     * in -- which is exactly the tightness that lost the counter altogether
     * when the player resized the window. A crop with air in it holds the
     * device's own icons, and those are taller than the slash: measured 0.33
     * and 0.49 of the crop against the slash's 0.25.
     *
     * So the anchor has to prove itself instead. The proof is the number:
     * even digits, evenly spaced, three of them after every comma. Junk does
     * not have that, and the one row that does is the counter wherever it
     * sits.
     *
     * All of it exists to avoid returning a number that has quietly lost a
     * digit. Such a number reads perfectly well and is simply wrong, which is
     * the one failure this bot cannot notice by itself -- it would look like
     * the fall it is watching for.
     */
    private fun counterRow(glyphs: List<IntArray>): List<IntArray>? {
        var best: List<IntArray>? = null
        for (slash in glyphs) {
            if (slash[2] / maxOf(slash[3], 1).toDouble() > HOLO_SLASH_WH) continue
            val digits = digitsLeftOf(glyphs, slash) ?: continue
            // A counter reads "so many out of so many", so there is a number
            // on the other side of the slash as well. Requiring it is what
            // stops a stray pair of specks from passing as a one digit
            // counter: on one frame whose real row was damaged, junk read as
            // 7 -- and a wrong small number is far worse than none, it looks
            // like the whole stock spent in a single round.
            if (digitsBeside(glyphs, slash, right = true).isEmpty()) continue
            if (best == null || digits.size > best.size) best = digits
        }
        return best
    }

    /** Characters of digit height in the slash's own row, on one side. */
    private fun digitsBeside(glyphs: List<IntArray>, slash: IntArray,
                             right: Boolean = false): List<IntArray> {
        val middle = slash[1] + slash[3] / 2.0
        val out = ArrayList<IntArray>()
        for (s in glyphs) {
            if (s === slash) continue
            if (right && s[0] < slash[0] + slash[2]) continue
            if (!right && s[0] + s[2] > slash[0]) continue
            if (!(HOLO_DIGIT_H_MIN * slash[3] <= s[3] && s[3] <= HOLO_DIGIT_H_MAX * slash[3])) continue
            if (abs((s[1] + s[3] / 2.0) - middle) > HOLO_ROW_TOL * slash[3]) continue
            out.add(s)
        }
        return out
    }

    /** The number written left of this character, or null if there is none. */
    private fun digitsLeftOf(glyphs: List<IntArray>, slash: IntArray): List<IntArray>? {
        val middle = slash[1] + slash[3] / 2.0
        val digits = ArrayList<IntArray>()
        val commas = ArrayList<IntArray>()
        for (s in glyphs) {
            if (s === slash || s[0] + s[2] > slash[0]) continue   // the allowance, right of the slash
            val off = (s[1] + s[3] / 2.0) - middle
            if (HOLO_DIGIT_H_MIN * slash[3] <= s[3] && s[3] <= HOLO_DIGIT_H_MAX * slash[3] &&
                abs(off) <= HOLO_ROW_TOL * slash[3]) {
                digits.add(s)
            } else if (HOLO_COMMA_H[0] * slash[3] <= s[3] && s[3] <= HOLO_COMMA_H[1] * slash[3] &&
                       HOLO_COMMA_DROP[0] * slash[3] <= off && off <= HOLO_COMMA_DROP[1] * slash[3]) {
                commas.add(s)
            }
        }
        if (digits.isEmpty()) return null
        val tallest = digits.maxOf { it[3] }
        if (digits.minOf { it[3] } < HOLO_DIGIT_EVEN * tallest) return null
        if (!spacingHolds(digits + commas)) return null
        if (!commasHold(digits, commas)) return null
        return digits
    }

    /** Are the characters evenly spaced, with no hole where one is missing? */
    private fun spacingHolds(chars: List<IntArray>): Boolean {
        val row = chars.sortedBy { it[0] }
        val width = row.map { it[2] }.sorted()[row.size / 2]
        for (i in 1 until row.size) {
            val before = row[i - 1]
            val now = row[i]
            if (now[0] - (before[0] + before[2]) > HOLO_GAP_MAX * width) return false
        }
        return true
    }

    /**
     * Three digits after every comma, and a comma for every thousand.
     * Internal because quest.stage_number asks it too, as quest.py does.
     */
    internal fun commasHold(unsorted: List<IntArray>, commas: List<IntArray>): Boolean {
        val digits = unsorted.sortedBy { it[0] }
        if (commas.isEmpty()) return digits.size <= HOLO_GROUP
        val edges = commas.map { it[0] }.sorted()
        val groups = ArrayList<List<IntArray>>()
        var rest: List<IntArray> = digits
        for (edge in edges) {
            groups.add(rest.filter { it[0] < edge })
            rest = rest.filter { it[0] > edge }
        }
        groups.add(rest)
        if (!groups.all { it.isNotEmpty() }) return false
        if (!(1 <= groups[0].size && groups[0].size <= HOLO_GROUP)) return false
        return groups.drop(1).all { it.size == HOLO_GROUP }
    }
}
