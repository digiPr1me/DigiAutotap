package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The Camp's two stations, Leomon's Dojo and Piximon's Drill Site, their
 * Reward Boxes, and the Results window an Attempt ends in (PLAN_CAMP.md C2,
 * measured on LDPlayer instance 1, the test account, on 2026-10-19 with the
 * app's core stopped).
 *
 * What the game draws (staging/camp/ until the oracle takes them):
 *
 *  * A tap on a station's sign on the Camp map opens one window, the same for
 *    both: a cyan title banner with the name and an (i), a picture in a frame
 *    (the Dojo brown and white with Leomon, the Drill Site an orange sky with
 *    Piximon and a stone statue), the player's own row (rank, Digimon, "Total
 *    Damage Today"), two pills "Reward Boxes" and "Ranking", a box with the
 *    counter "n/2", and at the bottom a teal **Auto Clear** beside a blue
 *    **Attempt**. No X: a tap in the dimmed field above closes it.
 *  * **Attempt** fades to black, shows the VS screen, and plays a battle by
 *    itself for about 42 s (a timer at the top, the boss's HP, the skill
 *    cards, "Give Up"); it ends in a **Results** window (Damage Record, Rank,
 *    Participation Rewards, a blue Close), and Close leads through "Now
 *    Loading" back to the station window with one attempt less.
 *  * **Auto Clear** is dim until the station has a record (an Attempt there,
 *    that day at least): a dim one does nothing at all. Lit, one tap spends an
 *    attempt with no battle and no question -- the station's damage goes up
 *    by the record once more -- and the game's Reward sheet ("Tap to close")
 *    comes up at once, read by [Dungeon.rewardSheet]. At 0/2 both buttons are
 *    dim.
 *  * **Reward Boxes** opens a window of that name: "Today's Records" (the
 *    camp's members with their damage and box counts, or "Nothing acquired
 *    yet."), "Current Total" with two chests and their numbers on a bright
 *    blue band, "Rewards Available" with the same two chests, and "Claim
 *    Rewards" under them, dim at 0 and 0. No X either.
 *
 * Every place is a fraction of `Dungeon.gameRect(img, WINDOW)`, every window
 * stands in the middle (notes/formats.md, rule 4), and every answer carries
 * its anchor. The findings: PLAN_CAMP.md section 6, until C4 carries them to
 * notes/camp.md.
 */
object CampStation {

    /** The station window, the Reward Boxes and the Results stand in the middle, like every popup. */
    val WINDOW = Dungeon.Anchor.MIDDLE

    enum class Which { DOJO, DRILL }

    /** A button of a window: where to tap it, and whether it is lit (its fill's value, for the probe). */
    data class Button(val target: Explore.Target, val lit: Boolean, val v: Double) {
        fun toOracle(): Map<String, Any?> = mapOf("target" to target.toOracle(), "lit" to lit)
    }

    // ------------------------------------------------------------------------
    // The title banner: the window is in front
    // ------------------------------------------------------------------------
    // The three windows wear one banner, a flat cyan (H 103 S 209 V 236 at
    // 1080 x 1920), 0.5754 of the rectangle wide and 0.0328 tall on the
    // station and on Reward Boxes, 0.5336 by 0.0374 on Results. A layer over
    // the window -- the Reward sheet, Reward Boxes over the station -- dims
    // it out of this range, so the banner is what says the window is the one
    // in front and its buttons' colours are their own.
    val BANNER = Dungeon.Hsv(intArrayOf(99, 170, 215), intArrayOf(107, 240, 255))
    val BANNER_W = doubleArrayOf(0.48, 0.66)
    val BANNER_H = doubleArrayOf(0.020, 0.048)
    const val BANNER_FILL_MIN = 0.60
    val BANNER_BAND = doubleArrayOf(0.10, 0.40)
    // Where each window's banner begins (fy0 at WINDOW): the station 0.2651
    // to 0.2657 on all seven rows of rule 17, Reward Boxes 0.1865 to 0.1869,
    // Results 0.1596 (0.1636 while it grows in). Beside them the Camp Shop's
    // banner at 0.1545 and its buy dialog's at 0.2778, and Reward Boxes
    // growing in at 0.2298 -- none of them with a station's buttons or the
    // boxes' band under it.
    val STATION_BANNER_FY0 = doubleArrayOf(0.258, 0.273)
    val BOXES_BANNER_FY0 = doubleArrayOf(0.180, 0.194)
    val RESULTS_BANNER_FY0 = doubleArrayOf(0.150, 0.172)

    /** One banner-coloured blob of a banner's shape in the band, or null. */
    fun bannerCandidates(img: Mat): List<Runner.Blob> =
        Runner.blobs(img, BANNER, band = BANNER_BAND, minShare = 0.004, anchor = WINDOW)
            .filter { it.width in BANNER_W[0]..BANNER_W[1] && it.height in BANNER_H[0]..BANNER_H[1] &&
                      it.fill >= BANNER_FILL_MIN }

    private fun banner(img: Mat, fy0: DoubleArray): Runner.Blob? =
        bannerCandidates(img).firstOrNull { it.fy0 in fy0[0]..fy0[1] }

    /** The mean value (HSV's V) of the pixels of [colour] in [b]'s box: a flat fill's brightness. */
    private fun fillValue(img: Mat, b: Runner.Blob, colour: Dungeon.Hsv, anchor: Dungeon.Anchor): Double {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val box = Py.crop(img, maxOf(0, Py.int(y0 + b.fy0 * gh)), Py.int(y0 + b.fy1 * gh),
                          maxOf(0, Py.int(x0 + b.fx0 * gw)), Py.int(x0 + b.fx1 * gw)) ?: return 0.0
        val hsv = Cv.hsv(box)
        val mask = Cv.inRange(hsv, colour)
        val v = Core.mean(hsv, mask).`val`[2]
        hsv.release(); mask.release()
        return v
    }

    // ------------------------------------------------------------------------
    // The station window
    // ------------------------------------------------------------------------
    // The two buttons: Auto Clear a flat teal (H 91 S 255), Attempt a flat
    // blue (H 100 S 255), side by side at the window's bottom. At 1080 x 1920
    // Auto Clear is fx 0.2119-0.4621, fy 0.6641-0.7182 (0.2502 x 0.0540),
    // Attempt fx 0.4935-0.7376, fy 0.6662-0.7152 (0.2441 x 0.0490), dimmed
    // 0.4908-0.7384 (0.2476 x 0.0500): middles 0.2786 apart, 0.001 apart in
    // height. The player's own row is the same blue while it has no record,
    // 0.6181 wide -- not a button's shape.
    val AUTO_TEAL = Dungeon.Hsv(intArrayOf(86, 200, 100), intArrayOf(96, 255, 230))
    val ATTEMPT_BLUE = Dungeon.Hsv(intArrayOf(97, 230, 120), intArrayOf(102, 255, 230))
    val BUTTON_BAND = doubleArrayOf(0.55, 0.85)
    val BUTTON_W = doubleArrayOf(0.21, 0.29)
    val BUTTON_H = doubleArrayOf(0.040, 0.066)
    const val PAIR_DY = 0.012
    val PAIR_PITCH = doubleArrayOf(0.25, 0.31)

    // Lit or dim is the fill's value, one flat colour either way: Auto Clear
    // V 199 lit against 138 dim (no record yet, or 0/2), Attempt V 209 lit
    // against 145 dim (0/2), on every station frame of the measurement. The
    // thresholds are the middles.
    const val AUTO_LIT_V = 168.0
    const val ATTEMPT_LIT_V = 177.0

    // The counter box "n/2" stands centred over the gap between the two
    // buttons: its digits at fy 0.597-0.626 against the buttons' middle at
    // 0.691, fx 0.408-0.544. The crop is the box, hung off the pair.
    val COUNTER_DY = doubleArrayOf(-0.100, -0.060)
    const val COUNTER_HALF_W = 0.075
    // The digits measure 0.28 of the crop and the slash 0.35 at 1920.
    const val COUNTER_GLYPH_MIN_H = 0.18

    // The pill "Reward Boxes", hung off Auto Clear: its middle at fx 0.318, fy
    // 0.548 against Auto Clear's 0.337 and 0.691 (the pill x 200-518, y
    // 997-1060 at 1920). Never the Ranking pill beside it (fx 0.50 to 0.76).
    const val REWARD_BOXES_DX = -0.019
    const val REWARD_BOXES_DY = -0.143

    // Which station: the picture in the frame, hung off the pair, fy 0.327 to
    // 0.433 (cy - 0.364 to cy - 0.258), fx 0.162 to 0.790. Its share of dark
    // brown -- the Dojo's wooden walls, H 0-25, S 80 and over, V 40-139 --
    // is 0.418 on the Dojo and 0.188 on the Drill Site, where the orange sky
    // is bright and the statue grey; DOJO_BROWN_MIN is the middle.
    val PICTURE_DY = doubleArrayOf(-0.364, -0.258)
    val PICTURE_FX = doubleArrayOf(0.162, 0.790)
    val PICTURE_BROWN = Dungeon.Hsv(intArrayOf(0, 80, 40), intArrayOf(25, 255, 139))
    const val DOJO_BROWN_MIN = 0.30

    /** What [window] weighed: the pair it found, and every number it decided on (the probe prints it). */
    class StationParts(val auto: Runner.Blob, val attempt: Runner.Blob, val autoV: Double, val attemptV: Double,
                       val brown: Double, val banner: Runner.Blob?)

    data class StationWindow(val which: Which?, val attempts: Pair<Int, Int>?, val autoClear: Button,
                             val attempt: Button, val rewardBoxes: Explore.Target,
                             val anchor: Dungeon.Anchor = WINDOW) {
        fun toOracle(): Map<String, Any?> = mapOf(
            "which" to which?.name, "attempts" to attempts?.let { listOf(it.first, it.second) },
            "auto_clear" to autoClear.toOracle(), "attempt" to attempt.toOracle(),
            "reward_boxes" to rewardBoxes.toOracle())
    }

    /** Every teal-and-blue pair of a button's size side by side in the band (the probe prints them). */
    fun pairCandidates(img: Mat): List<Pair<Runner.Blob, Runner.Blob>> {
        fun buttons(colour: Dungeon.Hsv) = Runner.blobs(img, colour, band = BUTTON_BAND, minShare = 0.004, anchor = WINDOW)
            .filter { it.width in BUTTON_W[0]..BUTTON_W[1] && it.height in BUTTON_H[0]..BUTTON_H[1] }
        val teal = buttons(AUTO_TEAL)
        val blue = buttons(ATTEMPT_BLUE)
        val out = ArrayList<Pair<Runner.Blob, Runner.Blob>>()
        for (a in teal) for (b in blue) {
            if (kotlin.math.abs(a.cy - b.cy) > PAIR_DY) continue
            if (b.cx - a.cx !in PAIR_PITCH[0]..PAIR_PITCH[1]) continue
            out.add(a to b)
        }
        return out
    }

    /** The station window's parts, banner or not (the probe prints them); null without a pair. */
    fun stationParts(img: Mat): StationParts? {
        val (a, b) = pairCandidates(img).firstOrNull() ?: return null
        val cy = (a.cy + b.cy) / 2
        return StationParts(a, b, fillValue(img, a, AUTO_TEAL, WINDOW), fillValue(img, b, ATTEMPT_BLUE, WINDOW),
                            brownShare(img, cy), banner(img, STATION_BANNER_FY0))
    }

    private fun brownShare(img: Mat, cy: Double): Double {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, WINDOW)
        val crop = Py.crop(img, maxOf(0, Py.int(y0 + (cy + PICTURE_DY[0]) * gh)), Py.int(y0 + (cy + PICTURE_DY[1]) * gh),
                           maxOf(0, Py.int(x0 + PICTURE_FX[0] * gw)), Py.int(x0 + PICTURE_FX[1] * gw)) ?: return 0.0
        val mask = Cv.hsvMask(crop, PICTURE_BROWN)
        val share = Cv.share(mask)
        mask.release()
        return share
    }

    /**
     * The station window in front, or null: its banner at its place, Auto
     * Clear and Attempt side by side under it. `which` from the picture,
     * `attempts` the "n/2" (state: an Attempt or an Auto Clear takes one),
     * each button lit or dim (state), and where the Reward Boxes pill is.
     */
    fun window(img: Mat): StationWindow? {
        val p = stationParts(img) ?: return null
        if (p.banner == null) return null
        val a = p.auto; val b = p.attempt
        val which = if (p.brown >= DOJO_BROWN_MIN) Which.DOJO else Which.DRILL
        val cy = (a.cy + b.cy) / 2
        return StationWindow(
            which, attempts(img, (a.fx1 + b.fx0) / 2, cy),
            Button(Explore.Target(a.cx, a.cy, WINDOW), p.autoV >= AUTO_LIT_V, p.autoV),
            Button(Explore.Target(b.cx, b.cy, WINDOW), p.attemptV >= ATTEMPT_LIT_V, p.attemptV),
            Explore.Target(a.cx + REWARD_BOXES_DX, a.cy + REWARD_BOXES_DY, WINDOW))
    }

    /** The counter box's crop, hung off the pair's gap ([cx]) and middle ([cy]). */
    fun counterCrop(img: Mat, cx: Double, cy: Double): Mat? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, WINDOW)
        return Py.crop(img, maxOf(0, Py.int(y0 + (cy + COUNTER_DY[0]) * gh)), Py.int(y0 + (cy + COUNTER_DY[1]) * gh),
                       maxOf(0, Py.int(x0 + (cx - COUNTER_HALF_W) * gw)), Py.int(x0 + (cx + COUNTER_HALF_W) * gw))
    }

    /**
     * "n/2" as (n, 2), or null: the white characters of the counter box,
     * read by the dungeon counter's own lines -- the slash as the proof, one
     * glyph right of it ([Dungeon.counterDigits]), each digit by
     * [Dungeon.readDigit] -- all or nothing.
     */
    fun attempts(img: Mat, cx: Double, cy: Double): Pair<Int, Int>? {
        val crop = counterCrop(img, cx, cy) ?: return null
        val (mask, glyphs) = whiteGlyphs(crop, COUNTER_GLYPH_MIN_H, 4)
        try {
            val counter = Dungeon.groupGlyphs(glyphs.filter {
                it[2] / maxOf(it[3], 1).toDouble() <= Dungeon.COUNTER_GLYPH_WH_MAX
            }).firstOrNull { Dungeon.counterDigits(it) != null } ?: return null
            val left = Dungeon.counterDigits(counter)!!
            val slash = counter.maxBy { it[3] }
            val right = counter.filter { it[0] >= slash[0] + slash[2] }
            val n = Dungeon.readCounter(mask, left) ?: return null
            val of = Dungeon.readCounter(mask, right) ?: return null
            return n to of
        } finally {
            mask.release()
        }
    }

    /**
     * The white characters of a crop, left to right, and their mask: the
     * dungeon counter's `glyphsIn`, line for line (Dungeon.kt, "badge_glyphs
     * from the crop on") -- the crop scaled by [scale] with INTER_CUBIC, grey
     * 200 and over, a component of more than 200 px, 0.15 to 1.2 as wide as
     * tall and taller than [minH] of the crop.
     */
    private fun whiteGlyphs(crop: Mat, minH: Double, scale: Int, floor: Double = 200.0): Pair<Mat, List<IntArray>> {
        val big = Mat()
        Imgproc.resize(crop, big, Size((crop.cols() * scale).toDouble(), (crop.rows() * scale).toDouble()),
                       0.0, 0.0, Imgproc.INTER_CUBIC)
        val gray = Cv.gray(big)
        val mask = Mat()
        Core.inRange(gray, Scalar(floor), Scalar(255.0), mask)
        big.release(); gray.release()
        val (n, stats) = Cv.components(mask)
        val out = ArrayList<IntArray>()
        for (i in 1 until n) {
            val s = stats[i]
            val ratio = s[2] / maxOf(s[3], 1).toDouble()
            if (s[4] > 200 && 0.15 < ratio && ratio < 1.2 && s[3] > minH * mask.rows()) out.add(s)
        }
        return mask to out.sortedBy { it[0] }
    }

    // ------------------------------------------------------------------------
    // Reward Boxes
    // ------------------------------------------------------------------------
    // "Current Total" sits on a band of bright blue (H 99 S 255 V 232),
    // fx 0.1561-0.7960, fy 0.5662-0.6338 at 1920 (0.6399 x 0.0677), which no
    // other window of the three draws. "Claim Rewards" is the station's
    // button blue, fx 0.3731-0.5789, fy0 0.7702 (0.2058 x 0.0318), 0.136 under
    // the band; dim it is V 145, Attempt's dim to the unit. A lit Claim was
    // not seen (nothing came to claim on the test account, and the player's
    // stood at 0 and 0): CLAIM_LIT_V is Attempt's middle, on the word that the
    // game's blue buttons are one style -- lit Attempt and the Results' Close
    // are both V 209. Live on 2026-11-01 (game 1.5.1, instance 0, Available
    // 500 and 50 at both stations) it read lit, its tap raised the Reward
    // sheet, and the second visit read it dim; no frame of a lit Claim was
    // kept, so the oracle still holds none (PLAN_CAMP.md C4).
    val TOTAL_BLUE = Dungeon.Hsv(intArrayOf(96, 230, 200), intArrayOf(102, 255, 255))
    val TOTAL_W = doubleArrayOf(0.58, 0.70)
    val TOTAL_H = doubleArrayOf(0.050, 0.085)
    val TOTAL_FY0 = doubleArrayOf(0.54, 0.60)
    val CLAIM_W = doubleArrayOf(0.17, 0.24)
    val CLAIM_H = doubleArrayOf(0.024, 0.045)
    val CLAIM_DY = doubleArrayOf(0.11, 0.16)
    const val CLAIM_LIT_V = ATTEMPT_LIT_V

    // The chests' numbers: white, on the bottom of each chest tile. The
    // tiles of "Current Total" at fx 0.3784-0.4708 and 0.4813-0.5737 inside
    // the band, those of "Rewards Available" the same columns, 0.1278 lower
    // (y 1331-1437 against 1078-1184 at 1920); the digits ("500" and "50" on
    // the player's) 0.0454 to 0.0555 under the band's top, the chest's
    // highlights over them from 0.038.
    val CHEST_FX = arrayOf(doubleArrayOf(0.3784, 0.4708), doubleArrayOf(0.4813, 0.5737))
    val TOTAL_DIGITS_DY = doubleArrayOf(0.040, 0.062)
    const val AVAILABLE_DY = 0.1278
    const val CHEST_GLYPH_MIN_H = 0.30
    // The digits are white (grey 255) in a black outline; the light-blue
    // chest's highlights beside and above them are grey 200, at most 229 at
    // an edge (the player's "500" and "50", 2026-10-19) -- so the digits are
    // what stands at CHEST_WHITE and over, the middle. At 0 the game dims a
    // chest of "Rewards Available", digit and all: its "0" is grey 169 and
    // the chest 155 at the most, on both accounts, where a lit chest's digit
    // row holds white -- a digit row with nothing at CHEST_LIT_MIN (the
    // middle of 169 and 229) is a dimmed chest, and a dimmed chest is 0.
    const val CHEST_WHITE = 242.0
    const val CHEST_LIT_MIN = 199.0

    class BoxesParts(val banner: Runner.Blob?, val total: Runner.Blob?, val claim: Runner.Blob?, val claimV: Double)

    data class BoxesWindow(val claim: Button, val available: Pair<Int, Int>?, val total: Pair<Int, Int>?,
                           val anchor: Dungeon.Anchor = WINDOW) {
        fun toOracle(): Map<String, Any?> = mapOf(
            "claim" to claim.toOracle(),
            "available" to available?.let { listOf(it.first, it.second) },
            "total" to total?.let { listOf(it.first, it.second) })
    }

    /** What [boxesWindow] weighed (the probe prints it). */
    fun boxesParts(img: Mat): BoxesParts {
        val banner = banner(img, BOXES_BANNER_FY0)
        val total = Runner.blobs(img, TOTAL_BLUE, band = doubleArrayOf(0.45, 0.70), minShare = 0.01, anchor = WINDOW)
            .firstOrNull { it.width in TOTAL_W[0]..TOTAL_W[1] && it.height in TOTAL_H[0]..TOTAL_H[1] &&
                           it.fy0 in TOTAL_FY0[0]..TOTAL_FY0[1] }
        val claim = if (total == null) null else
            Runner.blobs(img, ATTEMPT_BLUE, band = doubleArrayOf(total.fy1, total.fy1 + 0.25), minShare = 0.002, anchor = WINDOW)
                .firstOrNull { it.width in CLAIM_W[0]..CLAIM_W[1] && it.height in CLAIM_H[0]..CLAIM_H[1] &&
                               it.fy0 - total.fy1 in CLAIM_DY[0]..CLAIM_DY[1] }
        val v = if (claim == null) 0.0 else fillValue(img, claim, ATTEMPT_BLUE, WINDOW)
        return BoxesParts(banner, total, claim, v)
    }

    /**
     * The Reward Boxes window in front, or null: its banner at its place, the
     * Current Total band, Claim Rewards under it. `claim.lit` and the chests'
     * numbers are state.
     */
    fun boxesWindow(img: Mat): BoxesWindow? {
        val p = boxesParts(img)
        if (p.banner == null || p.total == null || p.claim == null) return null
        val c = p.claim
        val totalTop = p.total.fy0
        return BoxesWindow(Button(Explore.Target(c.cx, c.cy, WINDOW), p.claimV >= CLAIM_LIT_V, p.claimV),
                           chests(img, totalTop + AVAILABLE_DY), chests(img, totalTop))
    }

    /**
     * The two chests' numbers in the row whose band begins at [top], or null
     * where either is unread: a dimmed chest is 0 (CHEST_WHITE), a lit one is
     * its white digits read by [Dungeon.readDigit], all or nothing.
     */
    fun chests(img: Mat, top: Double): Pair<Int, Int>? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, WINDOW)
        val read = CHEST_FX.map { fx ->
            val crop = Py.crop(img, maxOf(0, Py.int(y0 + (top + TOTAL_DIGITS_DY[0]) * gh)),
                               Py.int(y0 + (top + TOTAL_DIGITS_DY[1]) * gh),
                               maxOf(0, Py.int(x0 + fx[0] * gw)), Py.int(x0 + fx[1] * gw)) ?: return null
            val grey = Cv.gray(crop)
            val brightest = Core.minMaxLoc(grey).maxVal
            grey.release()
            if (brightest < CHEST_LIT_MIN) return@map 0
            val (mask, glyphs) = whiteGlyphs(crop, CHEST_GLYPH_MIN_H, 4, CHEST_WHITE)
            val digits = glyphs.filter { it[2] / maxOf(it[3], 1).toDouble() <= Dungeon.COUNTER_GLYPH_WH_MAX }
            val n = if (digits.isEmpty()) null else Dungeon.readCounter(mask, digits)
            mask.release()
            n ?: return null
        }
        return read[0] to read[1]
    }

    // ------------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------------
    // An Attempt's end: "Damage Record" and "Rank" on two bars of one blue
    // (H 101-104, S 200 and over), each 0.6347 wide and 0.0364 tall at fy0
    // 0.2419 and 0.3571 (0.1152 apart), and a lit blue Close, fx 0.3758-
    // 0.5772, fy 0.7763-0.8162 (0.2014 x 0.0399), V 209. Apocalymon Wall's
    // runs end in the same window (DungeonSkill closes it from
    // Dungeon.recognise, which calls it a dialog); the director keeps
    // calling it that.
    val BAR_BLUE = Dungeon.Hsv(intArrayOf(99, 200, 170), intArrayOf(105, 255, 245))
    val BAR_W = doubleArrayOf(0.58, 0.69)
    val BAR_H = doubleArrayOf(0.028, 0.046)
    val BAR_PITCH = doubleArrayOf(0.100, 0.130)
    val CLOSE_W = doubleArrayOf(0.17, 0.24)
    val CLOSE_H = doubleArrayOf(0.030, 0.050)
    val CLOSE_FY0 = doubleArrayOf(0.74, 0.81)

    data class Results(val close: Button, val anchor: Dungeon.Anchor = WINDOW) {
        fun toOracle(): Map<String, Any?> = mapOf("close" to close.toOracle())
    }

    /** The two bars of Results, top first, of a bar's shape (the probe prints them). */
    fun barCandidates(img: Mat): List<Runner.Blob> =
        Runner.blobs(img, BAR_BLUE, band = doubleArrayOf(0.15, 0.50), minShare = 0.005, anchor = WINDOW)
            .filter { it.width in BAR_W[0]..BAR_W[1] && it.height in BAR_H[0]..BAR_H[1] }
            .sortedBy { it.fy0 }

    /** The Results window in front, or null: its banner, the two bars, and Close. */
    fun results(img: Mat): Results? {
        if (banner(img, RESULTS_BANNER_FY0) == null) return null
        val bars = barCandidates(img)
        if (bars.size != 2 || bars[1].fy0 - bars[0].fy0 !in BAR_PITCH[0]..BAR_PITCH[1]) return null
        val close = Runner.blobs(img, ATTEMPT_BLUE, band = doubleArrayOf(0.70, 0.86), minShare = 0.002, anchor = WINDOW)
            .firstOrNull { it.width in CLOSE_W[0]..CLOSE_W[1] && it.height in CLOSE_H[0]..CLOSE_H[1] &&
                           it.fy0 in CLOSE_FY0[0]..CLOSE_FY0[1] } ?: return null
        val v = fillValue(img, close, ATTEMPT_BLUE, WINDOW)
        return Results(Button(Explore.Target(close.cx, close.cy, WINDOW), v >= ATTEMPT_LIT_V, v))
    }
}
