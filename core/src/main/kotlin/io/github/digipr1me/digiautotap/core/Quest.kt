package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * quest.py's readers, carried over line for line: `quest_progress`,
 * `quest_card` with the name counter, `quest_claimable`, `stage_number` and
 * `close_x`, which is nothing but `Summon.exitButton` and then
 * `Explore.closeButton`. The loop itself -- the routine, the ticks, the
 * claim -- is not here; it comes with its own session, under the director.
 *
 * Every constant keeps its Python name and value, and the sentence that says
 * where it came from. A number here is changed in quest.py first, with a
 * measurement, then the oracle is written again, then this file follows --
 * never the other way round (NOTES.md, "Two implementations, one
 * direction"). The frame is BGR, uint8, as `cv2.imread` gives it.
 */
object Quest {

    // ------------------------------------------------------------------------
    // The quest counter, and the card around it
    // ------------------------------------------------------------------------
    // Where to look. Wide enough to hold the counter wherever a longer or
    // shorter quest name has pushed it, narrow enough to leave out the two
    // other slashes on the same screen: the XP bar's counter near the top of
    // the window and the hologram counter near the bottom, both well outside
    // this band, and the Summon icon's own badge, which sits inside the band
    // but carries no slash at all and so never survives the row search below.
    val QUEST_BAND = doubleArrayOf(0.55, 0.95, 0.45, 0.75)
    const val QUEST_SCALE = 4
    val QUEST_WHITE = intArrayOf(200, 255)
    // The ist side of the counter is red, not white (PLAN_QUEST_LOOP.md 3.3)
    // -- a grayscale threshold alone loses it entirely, since red converts to
    // a fairly dark grey. Measured on the real card: H median 173, S median
    // 244, V median 232. Kept apart from the game's pink "!" report badges,
    // which measure H median 163 -- ten hue units away, the same gap NOTES.md
    // already warns is too close for colour alone to decide anything. It does
    // not have to decide anything here: what makes a glyph the ist digit is
    // never its own colour, only that it sits directly left of the slash on
    // the row (see questRow) -- this range only has to be wide enough to bring
    // the glyph into the mask at all, with margin on both sides of the
    // ten-unit gap.
    //
    // Two ranges, not one: OpenCV's hue wraps at 179/0, and the reddest
    // pixels at the centre of the stroke measured H 0-1 -- inside a single
    // range ending at 179 that core was cut out of every stroke, and a solid
    // "0" came back as a hollow ring with a second ring of background running
    // right through the middle of it, which read_digit saw as an extra hole
    // and called an "8".
    val QUEST_RED_LOW = Dungeon.Hsv(intArrayOf(0, 120, 120), intArrayOf(8, 255, 255))
    val QUEST_RED_HIGH = Dungeon.Hsv(intArrayOf(158, 120, 120), intArrayOf(179, 255, 255))
    // And **green once the quest is finished**. Red is "not there yet"; the
    // moment the count reaches the target the same digit is redrawn in green,
    // and the card grows an animated green border that runs around it like a
    // snake. Missing this cost the whole feature its point: the card went
    // unreadable at exactly the moment it was worth tapping, because nothing
    // stood left of the slash in either of the two masks above -- so a
    // finished quest was never claimed, and the failure came back as "could
    // not read the quest card after the action", about a card that was
    // plainly on the screen.
    //
    // Measured on corpus/quest/loop-finished-quest-green-border.png, at
    // QUEST_SCALE, all four off the same frame:
    //
    //                              size      fill    H      S     V
    //   the green "2"              31x45     0.64    66    250   207
    //   the animated border       334x348    0.08    57    151   255
    //   the emerald reward chip   102x107    0.46    59    144   245
    //   the card's own panel         -         -    99-108
    //
    // So the range only has to be a green, twenty hue units clear of the
    // panel behind it. What keeps the border and the chip out of the counter
    // is not their colour and was never going to be: the border is a thin
    // outline and dies on QUEST_FILL_MIN, 0.08 against 0.15; the chip sits a
    // whole text row lower and dies on the row test; and both are far outside
    // the digit height band around the slash. Three separate reasons, none of
    // them a hue.
    val QUEST_GREEN = Dungeon.Hsv(intArrayOf(40, 120, 120), intArrayOf(85, 255, 255))

    // The glyph filter. Measured on the counter's own characters -- see the
    // module docstring's table in PLAN_QUEST_LOOP.md 3.3: the slash is the
    // tallest character at 14 px, the digits either side measure 11 to 12,
    // 0.79 to 0.86 of the slash. Kept relative to the slash, never to the
    // crop: NOTES.md already carries two counters that went unreadable the
    // other way.
    const val QUEST_MIN_H = 0.02          // crop-relative, a noise floor only
    val QUEST_ASPECT = doubleArrayOf(0.15, 1.20)
    const val QUEST_FILL_MIN = 0.15
    const val QUEST_SLASH_WH = 0.55       // width over height, ceiling for a slash candidate
    const val QUEST_DIGIT_H_MIN = 0.65    // of the slash's own height
    const val QUEST_DIGIT_H_MAX = 0.95
    const val QUEST_ROW_TOL = 0.25
    // How far a character may sit from its neighbour and still belong to the
    // same number, as a share of the slash's height.
    //
    // This was 1.0 and it was far too generous -- measured against 28 real
    // cards from one full turn of the routine, 19 of them were read wrong,
    // and every one of them the same way: the word next to the number was
    // swallowed into it. "Defeat 12/50" came back as 522/50, the "t" of
    // "Defeat" read as a digit; "Draw 0/30 Skill Card Summons" came back as
    // 0/305122, the letters of "Skill" strung onto the target; "0/50 times"
    // as 0/505. Nothing but the two dungeon quests survived, and only because
    // "Bakemon" and "DemiDevimon" happen to end in a short letter.
    //
    // Measured on those cards, in slash heights:
    //
    //   between two characters of the same number   0.13 to 0.27
    //   between a number and the word beside it     0.55 to 0.95
    //
    // 0.40 sits between them, 1.5 times above the widest gap inside a number
    // and 1.4 times below the narrowest space beside one. A letter is never
    // told from a digit by its height here -- an ascender ("t", "k", "l", a
    // capital) measures 0.75 to 0.85 of the slash and so does every digit.
    // It is the space that separates them, and on the ist side the colour
    // does it as well (see below).
    const val QUEST_GAP_MAX = 0.40
    // An absolute floor under QUEST_MIN_H, in the frame's own real pixels
    // before scaling. QUEST_MIN_H alone would never trigger it: it is a share
    // of this band's own crop height, and the digits stay roughly that same
    // share of the crop whatever the window size, because the crop is itself
    // a fraction of the game (PLAN_QUEST_LOOP.md 4.2's table). What actually
    // shrinks with a smaller window is the absolute pixel count digit_bitmap
    // has to work with -- 12.2 px at the reference window, 7.2 px at 440x806
    // -- and every digit reader in this program resizes onto a fixed grid
    // that stops meaning anything once the source is only a handful of pixels
    // tall. Six is below every window size this program's other readers were
    // ever measured at and above what a handful of pixels of anti-aliasing
    // noise could produce.
    const val QUEST_MIN_PX = 6

    // The card is translucent, and over a bright background its own panel
    // comes through coloured. Measured on the "Draw 0/30 Skill Card Summons"
    // card over an orange scene: the pink wash in the gap between the "w" of
    // "Draw" and the red 0 measured H 160, S 140 -- inside QUEST_RED_HIGH by
    // two hue units -- and formed a blob 70x88 at fill 0.38, the size and
    // shape of a digit, right beside the real one. It was read as a 3, and
    // "Draw 0/30" came back as 30/30: a finished quest, on a card that had
    // not started.
    //
    // Colour cannot throw it out (it is inside the range), position cannot
    // (0.23 of a slash off the row against 0.17 for real characters, too
    // close), and neither can fill (0.38 against a real minimum of 0.41).
    // Brightness can, and by a factor of two: text on this card is drawn
    // bright whatever its colour, and the panel behind it is dim -- the plan
    // measured the panel itself at V 114 to 121 (PLAN_QUEST_LOOP.md 3.2).
    //
    //   the upper quartile of V inside the box
    //     the 72 counter characters read off 25 real cards   239 to 255
    //     the pink wash that was read as a 3                 126
    //
    // 180 sits a third of the way from either, and well above the panel.
    const val QUEST_BRIGHT_PCT = 75
    const val QUEST_BRIGHT_MIN = 180

    // -- the quest's own name, and why it is counted rather than read -------
    // The target number does not say which quest is on the card: 2 stands
    // for both dungeon steps, 30 for both summon steps, 50 for six steps
    // between them. The script says what usually comes next, and a remembered
    // position is not evidence of anything -- a loop started on a day that
    // opened at "Clear Fight! Bakemon" played DemiDevimon instead, because
    // the target matched and nobody looked at the word beside it.
    //
    // What is on the card is a word, and this program has no letter shapes
    // stored anywhere and is not getting any. What it can do without them is
    // *count* the letters, which is enough for the only question being asked
    // -- "Bakemon" is 7 characters and "DemiDevimon" is 11, and no reading of
    // either can be mistaken for the other.
    //
    // Measured on corpus/quest/dailies-battle-stages.png, the counter row of
    // the real Bakemon card, at QUEST_SCALE:
    //
    //   the seven letters of "Bakemon"    h 32 to 45   (0.59 to 0.83 of the slash)
    //   the slash                         h 54
    //   the dot over an "i" (same card,
    //   the line above, "Fight!")         h 6          (0.11 of the slash)
    //
    // So a floor at 0.35 of the slash keeps every letter body and drops every
    // dot and every speck -- a factor of three either way, which is what
    // NOTES.md asks for and what a threshold at 0.5 would not have given.
    const val NAME_H_MIN = 0.35
    // How much of its own height a letter must share with the slash's rows to
    // count as standing on the counter's line. Half, which a descender ("g",
    // "p") still clears and a line above or below cannot.
    const val NAME_OVERLAP = 0.5
    // The card's own width, in game fractions (PLAN_QUEST_LOOP.md 3.1). Only
    // ever used to bound the name: the crop reaches further left than the
    // card does, and the game art out there draws bright shapes of its own.
    const val CARD_W = 0.264
    // The card's height, same table. Only the dump uses it, and it takes this
    // much above *and* below the card's middle -- twice the card, so a crop
    // taken to be looked at by hand shows its edges and the reward chip under
    // it rather than cutting the thing it is meant to explain.
    const val CARD_H = 0.068

    // The card's tap target, as an offset from the counter row rather than a
    // stored position. Measured on both frame shapes of the same real screen
    // (PLAN_QUEST_LOOP.md 3.1, 3.6):
    //
    //             window 732x1341   ADB 1080x1920
    //   card mid       0.7510/0.5987     0.7493/0.6008
    //   counter row     0.7838/0.5887     0.8099/0.5889
    //
    // The card's own x barely moves (three thousandths) while the row's x
    // moves by 0.026 between "Bakemon 0/2" and "DemiDevimon 0/2" -- a longer
    // name pushes the centred text sideways inside a card that does not move.
    // So the card's x is the constant below, never the row's own x; only its
    // y tracks the row, by the small, near-identical offset measured both
    // ways (0.0100, 0.0121).
    const val CARD_MID_FX = 0.750
    const val CARD_FY_ROW_OFFSET = 0.011

    /**
     * One connected component of one of the three masks. Python keeps the
     * stats row and looks its mask up by `id()`; here the two travel
     * together, and `===` on the holder is Python's `is` on the row.
     */
    private class Glyph(val s: IntArray, val mask: Mat, val coloured: Boolean)

    /** `_quest_row`'s dict. */
    private class Row(val ist: Int, val ziel: Int, val span: Int,
                      val rowFx: Double, val rowFy: Double,
                      val nameLen: Int, val afterLen: Int, val nameW: Double)

    /** `quest_card`'s dict. */
    data class Card(val fx: Double, val fy: Double, val ist: Int, val ziel: Int,
                    val rowFx: Double, val rowFy: Double,
                    val nameLen: Int, val nameW: Double, val afterLen: Int) {
        fun toOracle(): Map<String, Any?> = mapOf(
            "fx" to fx, "fy" to fy, "ist" to ist, "ziel" to ziel,
            "row_fx" to rowFx, "row_fy" to rowFy,
            "name_len" to nameLen, "name_w" to nameW, "after_len" to afterLen)
    }

    /**
     * Glyphs that reach `edge` in a contiguous chain, within QUEST_GAP_MAX *
     * unit of each other. Everything past the first wide gap is a quest name
     * or other artwork, not the counter.
     */
    private fun runTouching(glyphs: List<Glyph>, edge0: Int, unit: Int,
                            growLeft: Boolean): List<Glyph> {
        // sorted() is stable, and so is sortedBy; reversed() after it, as
        // Python reverses the sorted list.
        var ordered = glyphs.sortedBy { it.s[0] }
        if (growLeft) ordered = ordered.reversed()
        var edge = edge0
        val run = ArrayList<Glyph>()
        for (g in ordered) {
            val gap = if (growLeft) edge - (g.s[0] + g.s[2]) else g.s[0] - edge
            if (gap > QUEST_GAP_MAX * unit) break
            run.add(g)
            edge = if (growLeft) g.s[0] else g.s[0] + g.s[2]
        }
        return run.sortedBy { it.s[0] }
    }

    /**
     * `np.percentile(a, q)` with numpy's default, linear, method: the
     * virtual index q/100 * (n - 1), and numpy's own `_lerp` between the two
     * neighbours -- `a + (b - a) * t`, or `b - (b - a) * (1 - t)` from
     * t = 0.5 on, which is how numpy keeps the ends exact.
     */
    private fun percentile(values: IntArray, q: Int): Double {
        values.sort()
        val index = q / 100.0 * (values.size - 1)
        val lo = floor(index).toInt()
        val hi = minOf(ceil(index).toInt(), values.size - 1)
        val a = values[lo].toDouble()
        val b = values[hi].toDouble()
        val t = index - lo
        val diff = b - a
        return if (t >= 0.5) b - diff * (1 - t) else a + diff * t
    }

    /**
     * Is this shape drawn text, or the card's own panel showing through?
     *
     * See QUEST_BRIGHT_MIN. The one question a hue cannot answer here.
     */
    private fun brightEnough(value: Mat, stat: IntArray): Boolean {
        val (x, y, w, h) = stat
        val box = Py.crop(value, y, y + h, x, x + w) ?: return false
        val bytes = ByteArray(box.rows() * box.cols())
        // A submat is not continuous; copy it out before reading it flat.
        val flat = box.clone()
        flat.get(0, 0, bytes)
        flat.release()
        val values = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
        return percentile(values, QUEST_BRIGHT_PCT) >= QUEST_BRIGHT_MIN
    }

    /**
     * The quest's own letters on the counter's row, between two edges.
     *
     * Counted, never read -- see NAME_H_MIN. Called twice: once for what
     * stands in front of the number, bounded by the card's left edge and the
     * number's own, and once for what stands behind it, bounded by the
     * number's right edge and the card's. Both bounds matter: the crop
     * reaches past the card into the game art on one side, and the number
     * itself must never be counted as part of a word on the other.
     */
    private fun nameRun(stats: List<IntArray>, slash: IntArray, xTo: Double, xFrom: Double,
                        value: Mat?): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (s in stats) {
            val (x, y, w, h) = s
            if (h == 0 || h < NAME_H_MIN * slash[3]) continue
            // A letter is never a hairline. The card's own bright edge runs
            // down the right side of the row as a component 1 px wide and 138
            // tall, and it was counted as a word behind the number on the
            // Bakemon card -- 7/1 instead of 7/0. The same aspect floor the
            // counter's own characters go through, twenty times under it.
            if (w < QUEST_ASPECT[0] * h) continue
            if (x < xFrom || x + w > xTo) continue
            val shared = minOf(y + h, slash[1] + slash[3]) - maxOf(y, slash[1])
            if (shared < NAME_OVERLAP * h) continue
            if (value != null && !brightEnough(value, s)) continue
            out.add(s)
        }
        return out.sortedBy { it[0] }
    }

    /**
     * The counter's digits and where they sit, or null if none is found.
     *
     * Every narrow, tall character in the band is tried as the slash; the
     * one that turns out to have a valid number on each side wins, the same
     * proof passive._counter_row uses. A slash with digits on only one side
     * is thrown away rather than guessed at -- the Summon icon's own badge
     * sits in this band with no slash at all, and the game's own "!" report
     * badges carry no slash either, so nothing here needs to know their
     * colour.
     */
    private fun questRow(img: Mat): Row? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val (fx0, fx1, fy0, fy1) = QUEST_BAND.toList()
        val left = maxOf(0, Py.int(x0 + fx0 * gw))
        val top = maxOf(0, Py.int(y0 + fy0 * gh))
        val right = Py.int(x0 + fx1 * gw)
        val bottom = Py.int(y0 + fy1 * gh)
        val sub = Py.crop(img, top, bottom, left, right) ?: return null
        if (sub.rows() < 2 || sub.cols() < 2) return null
        val big = Mat()
        Imgproc.resize(sub, big, Size((sub.cols() * QUEST_SCALE).toDouble(),
                                      (sub.rows() * QUEST_SCALE).toDouble()),
                       0.0, 0.0, Imgproc.INTER_CUBIC)
        val gray = Cv.gray(big)
        val white = Mat()
        Core.inRange(gray, Scalar(QUEST_WHITE[0].toDouble()), Scalar(QUEST_WHITE[1].toDouble()), white)
        val hsv = Cv.hsv(big)
        val redLow = Cv.inRange(hsv, QUEST_RED_LOW)
        val redHigh = Cv.inRange(hsv, QUEST_RED_HIGH)
        val red = Mat()
        Core.bitwise_or(redLow, redHigh, red)
        val green = Cv.inRange(hsv, QUEST_GREEN)
        val value = Mat()
        Core.extractChannel(hsv, value, 2)
        big.release(); gray.release(); hsv.release(); redLow.release(); redHigh.release()
        try {
            return questRowOf(white, red, green, value, left, top, x0, y0, gw, gh)
        } finally {
            white.release(); red.release(); green.release(); value.release()
        }
    }

    private fun questRowOf(white: Mat, red: Mat, green: Mat, value: Mat,
                           left: Int, top: Int, x0: Int, y0: Int, gw: Int, gh: Int): Row? {
        // Kept as two masks, not merged into one. A red glyph's anti-aliased
        // edge brushes the white threshold too, and OR-ing the two together
        // left a second, thinner ring just outside the real one with a gap of
        // background between them -- which is a second hole to read_digit,
        // and a clean "0" came back as "8". Read alone, the red mask draws
        // the ist digit as cleanly as the white one draws everything else, so
        // each glyph is read from whichever mask it was actually found in.
        // The green mask joins them on the same terms and for the same
        // reason: on a finished card it is the ist digit, and nothing else on
        // that row.
        val glyphs = ArrayList<Glyph>()
        val unfiltered = ArrayList<IntArray>()
        for (mask in listOf(white, red, green)) {
            val (n, stats) = Cv.components(mask)
            for (i in 1 until n) unfiltered.add(stats[i])
            for (i in 1 until n) {
                val stat = stats[i]
                val (_, _, w, h, area) = stat.toList()
                if (h == 0 || h < QUEST_MIN_H * mask.rows()) continue
                val aspect = w / h.toDouble()
                if (!(QUEST_ASPECT[0] <= aspect && aspect <= QUEST_ASPECT[1])) continue
                if (area / (w * h).toDouble() < QUEST_FILL_MIN) continue
                if (!brightEnough(value, stat)) continue
                glyphs.add(Glyph(stat, mask, mask !== white))
            }
        }

        fun read(run: List<Glyph>): Int? {
            var number = 0
            for (g in run) {
                val d = Dungeon.readDigit(g.mask, g.s) ?: return null
                number = number * 10 + d
            }
            return number
        }

        var best: Row? = null
        for (slashG in glyphs) {
            val slash = slashG.s
            if (slash[3] < QUEST_MIN_PX * QUEST_SCALE) continue
            if (slash[2] / maxOf(slash[3], 1).toDouble() > QUEST_SLASH_WH) continue
            val middle = slash[1] + slash[3] / 2.0
            val leftG = ArrayList<Glyph>()
            val rightG = ArrayList<Glyph>()
            for (g in glyphs) {
                if (g === slashG) continue
                val s = g.s
                if (!(QUEST_DIGIT_H_MIN * slash[3] <= s[3] && s[3] <= QUEST_DIGIT_H_MAX * slash[3])) continue
                if (abs((s[1] + s[3] / 2.0) - middle) > QUEST_ROW_TOL * slash[3]) continue
                if (s[0] + s[2] <= slash[0]) {
                    // The ist side is never white. It is red while the quest
                    // is running and green once it is done, and the quest's
                    // own words are white -- so on this side the colour alone
                    // keeps "Defeat" out of "Defeat 12/50", with no threshold
                    // involved at all. Measured on all 28 cards of one turn
                    // of the routine: every ist digit red or green, every
                    // letter beside it white.
                    if (g.coloured) leftG.add(g)
                } else {
                    rightG.add(g)
                }
            }
            if (leftG.isEmpty() || rightG.isEmpty()) continue
            // Only the run that actually touches the slash is the counter's
            // own number. A quest name shares the row's height and can still
            // sit well to its left -- "Bakemon" and the digit are both
            // digit-height and both left of the slash, and only the gap
            // between them (five times the digit-to-slash gap, in the frame
            // this was measured on) tells them apart. The same shape of trap
            // as VIOLET against the prompt's pink: a threshold on the row
            // alone was not enough there either, and needed a neighbour to
            // decide.
            val leftRun = runTouching(leftG, slash[0], slash[3], growLeft = true)
            val rightRun = runTouching(rightG, slash[0] + slash[2], slash[3], growLeft = false)
            if (leftRun.isEmpty() || rightRun.isEmpty()) continue
            val ist = read(leftRun) ?: continue
            val ziel = read(rightRun) ?: continue
            val last = rightRun.last().s
            val span = last[0] + last[2] - leftRun[0].s[0]
            if (best == null || span > best.span) {
                val allStats = leftRun.map { it.s } + rightRun.map { it.s } + listOf(slash)
                val rowTop = allStats.minOf { it[1] }
                val rowBottom = allStats.maxOf { it[1] + it[3] }
                val rowLeft = leftRun[0].s[0]
                val rowRight = last[0] + last[2]
                val rowCx = left + (rowLeft + rowRight) / 2.0 / QUEST_SCALE
                val rowCy = top + (rowTop + rowBottom) / 2.0 / QUEST_SCALE
                val cardLeft = (x0 + (CARD_MID_FX - CARD_W / 2.0) * gw - left) * QUEST_SCALE
                val cardRight = (x0 + (CARD_MID_FX + CARD_W / 2.0) * gw - left) * QUEST_SCALE
                val name = nameRun(unfiltered, slash, rowLeft.toDouble(), cardLeft, value)
                val after = nameRun(unfiltered, slash, cardRight, rowRight.toDouble(), value)
                best = Row(
                    ist = ist, ziel = ziel, span = span,
                    rowFx = (rowCx - x0) / gw, rowFy = (rowCy - y0) / gh,
                    // A count, and zero is one of them: the hologram card
                    // carries no word in front of its number at all, and the
                    // stage card none on either side. Both are measurements,
                    // not the absence of one.
                    nameLen = name.size,
                    // And what stands *behind* the number. The word in front
                    // is not always there: measured across one full turn of
                    // the routine, "0/50 times" has nothing at all in front
                    // of it and "Draw 0/30 Skill" has a word on each side.
                    // The pair is what tells the quests apart -- see
                    // quest.py's ROUTINE "chars".
                    afterLen = after.size,
                    // The word's own width, in slash heights, so it means the
                    // same on a window frame and on an ADB one. Nothing
                    // decides anything by it yet.
                    nameW = if (name.isNotEmpty())
                        (name.last()[0] + name.last()[2] - name[0][0]) / slash[3].toDouble()
                    else 0.0)
            }
        }
        return best
    }

    /**
     * (ist, ziel) on the quest card, or null if no counter is found.
     *
     * All or nothing, the same rule as dungeon.read_counter: a made-up number
     * is worse than an admitted None, because the loop compares this reading
     * against the one before it to decide whether an action worked at all.
     */
    fun questProgress(img: Mat): Pair<Int, Int>? {
        val row = questRow(img) ?: return null
        return row.ist to row.ziel
    }

    /**
     * The quest card's tap target, plus what its counter says, or null.
     *
     * Found over the counter, not the card: the card itself is never searched
     * for as a coloured shape, because it is translucent and what it measures
     * depends on whatever background sits behind it (PLAN_QUEST_LOOP.md 3.2).
     *
     * Python's optional `row` -- a reading of the same frame done already --
     * is left for the loop's own session; it is a saving, not a reader.
     */
    fun questCard(img: Mat): Card? {
        val row = questRow(img) ?: return null
        return Card(fx = CARD_MID_FX, fy = row.rowFy + CARD_FY_ROW_OFFSET,
                    ist = row.ist, ziel = row.ziel,
                    rowFx = row.rowFx, rowFy = row.rowFy,
                    nameLen = row.nameLen, nameW = row.nameW, afterLen = row.afterLen)
    }

    /**
     * Is the current quest done? ist >= ziel, and nothing else.
     *
     * No colour, no glow, no third reader. PLAN_QUEST_LOOP.md 4.3: the game
     * writes the number itself, and a finished quest sits and waits for the
     * tap rather than resolving on its own. The green digit of a finished
     * card is already doing its job one step earlier: it is what puts the
     * digit in the mask at all (QUEST_GREEN). Reading the same evidence twice
     * is not a second opinion.
     */
    fun questClaimable(img: Mat): Boolean {
        val (ist, ziel) = questProgress(img) ?: return false
        return ist >= ziel
    }

    // ------------------------------------------------------------------------
    // The stage number
    // ------------------------------------------------------------------------
    // "Stage: 7,308", centred under the location banner. Measured on
    // corpus/quest/dailies-battle-stages.png: the word "Stage:" and the number
    // sit on one centred line, so a longer number pushes the whole line,
    // "Stage:" included, sideways -- there is no fixed x for either part.
    // What is fixed enough to anchor on is the gap: the space after the colon
    // measured about 2.3 times the widest digit-to-digit gap within the
    // number itself, on that one frame. So the number is found as the
    // right-most run of evenly spaced, digit-sized characters in a band wide
    // enough to hold "Stage: NNNN" at any length, cut off wherever a gap that
    // wide turns up.
    val STAGE_BAND = doubleArrayOf(0.30, 0.70, 0.168, 0.196)
    const val STAGE_SCALE = 4
    val STAGE_WHITE = intArrayOf(190, 255)
    const val STAGE_MIN_H = 0.06
    val STAGE_ASPECT = doubleArrayOf(0.15, 1.20)
    const val STAGE_FILL_MIN = 0.15
    // Gap over the row's own median glyph width. The comma itself is too
    // short to pass STAGE_MIN_H and never joins the glyph list, so the gap
    // either side of it merges into one -- measured 1.48 of a digit's width
    // on the real frame, against 2.39 for the space after the colon. The
    // split sits at their geometric mean, two full digit widths, a margin of
    // about 1.2 either way.
    const val STAGE_GAP_SPLIT = 2.0
    // A comma is short beside a digit. Measured 0.36 of a digit's height on
    // the one frame this was checked against; digits themselves are even, so
    // anything under half a digit's height is the comma, never a digit.
    const val STAGE_COMMA_MAX = 0.6

    /**
     * The stage number under the location banner, or null.
     *
     * Nothing taps because of this. It exists only to say why a "clear this
     * stage" step is still waiting -- PLAN_QUEST_LOOP.md 4.4 -- so if this
     * reader is ever wrong, the cost is a worse log line and nothing else:
     * the quest card's own ist/ziel counter is what actually decides the
     * claim, the same as every other step.
     */
    fun stageNumber(img: Mat): Int? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val (fx0, fx1, fy0, fy1) = STAGE_BAND.toList()
        val left = maxOf(0, Py.int(x0 + fx0 * gw))
        val top = maxOf(0, Py.int(y0 + fy0 * gh))
        val sub = Py.crop(img, top, Py.int(y0 + fy1 * gh), left, Py.int(x0 + fx1 * gw))
            ?: return null
        if (sub.rows() < 2 || sub.cols() < 2) return null
        val big = Mat()
        Imgproc.resize(sub, big, Size((sub.cols() * STAGE_SCALE).toDouble(),
                                      (sub.rows() * STAGE_SCALE).toDouble()),
                       0.0, 0.0, Imgproc.INTER_CUBIC)
        val gray = Cv.gray(big)
        val mask = Mat()
        Core.inRange(gray, Scalar(STAGE_WHITE[0].toDouble()), Scalar(STAGE_WHITE[1].toDouble()), mask)
        big.release(); gray.release()
        try {
            return stageNumberOf(mask)
        } finally {
            mask.release()
        }
    }

    private fun stageNumberOf(mask: Mat): Int? {
        val (n, stats) = Cv.components(mask)
        val found = ArrayList<IntArray>()
        for (i in 1 until n) {
            val (_, _, w, h, area) = stats[i].toList()
            if (h == 0 || h < STAGE_MIN_H * mask.rows()) continue
            val aspect = w / h.toDouble()
            if (!(STAGE_ASPECT[0] <= aspect && aspect <= STAGE_ASPECT[1])) continue
            if (area / (w * h).toDouble() < STAGE_FILL_MIN) continue
            found.add(stats[i])
        }
        if (found.isEmpty()) return null
        val glyphs = found.sortedBy { it[0] }
        val widths = glyphs.map { it[2] }.sorted()
        val medianW = widths[widths.size / 2].let { if (it == 0) 1 else it }

        // The trailing run: walk backwards from the right-most glyph, stop at
        // the first gap wider than the split. That is the number, whatever
        // "Stage:" happened to be in front of it.
        val run = ArrayList<IntArray>()
        run.add(glyphs.last())
        for (g in glyphs.dropLast(1).reversed()) {
            val gap = run[0][0] - (g[0] + g[2])
            if (gap > STAGE_GAP_SPLIT * medianW) break
            run.add(0, g)
        }
        if (run.size < 2) return null

        val tallest = run.maxOf { it[3] }
        val isDigit = run.map { it[3] >= (1 - STAGE_COMMA_MAX) * tallest }
        val digits = run.filterIndexed { i, _ -> isDigit[i] }
        val commas = run.filterIndexed { i, _ -> !isDigit[i] }
        if (!Passive.commasHold(digits, commas)) return null
        return Dungeon.readCounter(mask, digits.sortedBy { it[0] })
    }

    // ------------------------------------------------------------------------
    // The way out of a screen the loop did not open
    // ------------------------------------------------------------------------
    // Everything in this file waits for the plain main screen and does nothing
    // without it, which is right until the game stops going back there by
    // itself. A live run parked step 6 on "tapping the card did not open the
    // reward window" and then said "not the plain main screen" for as long as
    // anybody watched: the frame the passive helper kept at 09:24:59 was the
    // Special Summon reward screen, with the game's own X plainly drawn in the
    // corner. Waiting was never going to end that, because nothing was going to
    // happen. One tap would have.
    //
    // Neither X is new. Summon.exitButton is the white plate that closes a
    // Special Summon screen and Explore.closeButton the violet one that closes
    // the Digital World Search board, both measured where they were built. What
    // is new is asking for them here, and what makes that safe was measured
    // across all 249 stored frames of nine debug folders, both frame shapes
    // from 573 x 1056 to 1080 x 1920:
    //
    //   the white X fires on 30 of them, the violet on 25, never both on one
    //   frame, and -- the number this rests on -- **not one frame in the whole
    //   collection carries an X and a crisp auto button at once.**
    //
    // So an X can only ever be found on a screen that is already not the main
    // screen, which is the only place this is asked. The pair is wider than the
    // two screens they were built for, too: the same white plate reads 0.760
    // plate / 0.226 mark on the summon reward screen and on the PvP screen, to
    // the fourth decimal, because it is one piece of artwork the game reuses.

    /** `close_x`'s dict: the X's answer from whichever recogniser found it, and which. */
    data class CloseX(val fx: Double, val fy: Double, val plate: Double, val mark: Double,
                      val which: String) {
        fun toOracle(): Map<String, Any?> =
            mapOf("fx" to fx, "fy" to fy, "plate" to plate, "mark" to mark, "which" to which)
    }

    /**
     * The X that closes whatever is in front, or null.
     *
     * Asked in one place only -- a screen that has already failed the auto
     * button -- and answered by the two recognisers that already exist. The
     * white one first: it is the commoner of the two by a little and the one
     * the summon steps leave behind.
     */
    fun closeX(img: Mat): CloseX? {
        Summon.exitButton(img)?.let {
            return CloseX(it.fx, it.fy, it.plate, it.mark, "the white X")
        }
        Explore.closeButton(img)?.let {
            return CloseX(it.fx, it.fy, it.plate, it.mark, "the violet X")
        }
        return null
    }
}
