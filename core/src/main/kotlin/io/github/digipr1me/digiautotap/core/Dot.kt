package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * The overlay's geometry and its mask, ported from `_overlay_probe.py`
 * (the overlay probe). The app draws the dot; this says where it stands and takes
 * it back out of every frame before a reader sees one.
 *
 * **Why there is a mask at all**, measured on 2026-09-20 in LDPlayer 14 and
 * written into PLAN_ANDROID_DESIGN.md 3.2: the app's own dot *does* appear in
 * the app's own `takeScreenshot`. A DOT_WARN disc at the measured place read
 * back B30 G38 R179, which is DOT_WARN to the unit in every channel, while a
 * control square of the same size on the other edge moved 0.7 grey levels
 * over the same two frames. So a reader that is handed a raw frame is handed
 * a frame with something in it the game never drew -- which on that place
 * alone tips 19 frames of the corpus drawn and 3 masked, and in DOT_WARN
 * tips `stage_failed` and with it the whole screen the director thinks it is
 * looking at (PLAN_ANDROID_DESIGN.md 3.2).
 *
 * **One place.** The mask is called from `Capture.grab` and nowhere else --
 * not in the readers, not in the director -- or the rule is at 75 sites and
 * missing from one (PLAN_ANDROID_DESIGN.md 3.2).
 *
 * Nothing here reads a clock or a random number, and the fill is the middle
 * element of a **sorted** band rather than an averaged median, because two
 * languages round that differently and an oracle test cannot tell a rounding
 * difference from a porting mistake.
 */
object Dot {

    /**
     * **How much of the screen the dot covers, and that is the number, not
     * the dp.**
     *
     * 48 dp on a 1080 px 420 dpi phone -- 411.43 dp across -- is 0.11667 of
     * the screen, and that is what the overlay probe measured the corpus with. A dp
     * is not a fraction of anything until a density is named, and the width
     * of a phone in dp is not constant: 360 dp is the narrowest still sold,
     * where the same 48 dp covers 0.1333, fourteen per cent more.
     *
     * Measured, because the overlay probe's write-up quoted that 0.1333 without measuring it: at the
     * winning place, masked, over the whole corpus, a 48 dp dot on a 360 dp
     * screen tips **39 frames of 773 against 3**, and 68 against 24 with the
     * word beside it. So a dot sized in dp is a dot whose measurement is of
     * one phone.
     *
     * The dot is therefore drawn -- and masked -- at this fraction of the
     * screen on every phone, and the *touch* area stays at least 48 dp
     * around it. On a 411 dp phone they are the same square; on a narrower
     * one the disc is 42 dp inside a 48 dp window, and the extra ring draws
     * nothing, so there is nothing there to mask.
     */
    const val DOT_OF_SCREEN = 48.0 / (1080.0 / (420.0 / 160.0))

    /**
     * The plate beside the dot, as a fraction of the screen, for the same
     * reason and from the same 411 dp phone: 128 x 18 dp is 0.3111 x 0.0438.
     *
     * Both numbers are measured, not chosen: `gradlew :core:overlayProbe`
     * over all 773 frames and every reader of all seven families,
     * 2026-09-21 (the table is in PLAN_ANDROID_DESIGN.md 3.1).
     *
     * **18 dp is the whole height there is.** Centred on the dot, an 18 dp
     * plate reaches fy 0.1434 to 0.1680 and stops exactly where
     * [Quest.STAGE_BAND] begins (0.168): 20 dp costs 95 tipped frames
     * against 34, of which `quest.stage_number` is 42, and every taller
     * plate costs 68 to 182 whatever row it stands in -- a plate raised
     * clear of the stage band runs into the Special Summon tab row
     * instead, which the oracle puts at fy 0.115 to 0.155 (27 answers,
     * `summon.general_tab`), and tips that on real Summon screens.
     *
     * **128 dp is the widest that tips no `general_tab`.** 92 dp costs 24
     * frames, 128 costs 34, 160 costs 37 with three Summon screens among
     * them, 176 costs 44 with four. The plate's bottom edge already lies
     * over the last third of that tab row and the reader still finds its
     * pair; reaching further left it does not.
     */
    const val PLATE_W_OF_SCREEN = 128.0 / (1080.0 / (420.0 / 160.0))
    const val PLATE_H_OF_SCREEN = 18.0 / (1080.0 / (420.0 / 160.0))
    const val GAP_OF_SCREEN = 6.0 / (1080.0 / (420.0 / 160.0))

    /** A 40 dp disc with a 2 dp white rim inside the 48 dp square (design 3.1). */
    const val DISC_OF_DOT = 40.0 / 48.0
    const val RIM_OF_DOT = 2.0 / 48.0

    /**
     * How far outside a rectangle the mask samples its colour: a quarter of
     * the dot. Wide enough that one stray pixel cannot decide it, narrow
     * enough that the colour is still the dot's own surroundings and not the
     * next thing along.
     */
    const val BAND_OF_DOT = 0.25

    /**
     * The place the overlay probe measured over the whole corpus: flush against the
     * right edge of the screen, centre at fy 0.1555 (section 5). It tips 3
     * frames of 773 masked, and 0 of the 28 long-display frames; the runners
     * up at 0.025 gw above and below tip 7 and 11. There is no place on
     * either edge with the 0.05 gw of margin the rule asks for -- that is the
     * result, not a gap in it (section 5, last paragraph).
     *
     * A fraction of the window (`Dungeon.gameRectWh`), in the probe
     * ([square]) and in the app ([displayY]) alike, since 2026-09-26: until
     * then the app read it as a fraction of the frame and stood 48 px lower
     * at 1920 than this was measured (PLAN_FORMATE.md, V2).
     */
    const val DEFAULT_FY = 0.1555

    /**
     * The narrow band of fy the sweep found quiet on the right edge (PLAN_ANDROID_DESIGN.md 3.2).
     * Like [DEFAULT_FY], a fraction of the window, which is what the sweep
     * measured in: see [displayY].
     */
    const val MEASURED_FY_MIN = 0.060
    const val MEASURED_FY_MAX = 0.155

    /**
     * Where a place's [fy] stands on a display [w] x [h] whose game canvas
     * starts [top] rows down, in display pixels: the rows over the camera,
     * then fy in the readers' own vocabulary of the frame they get -- the
     * display less those rows, `DigiAutotapService.grab` cuts them off --
     * which is a fraction of the window (`Dungeon.gameRectWh`), the
     * arithmetic [square] does on a frame. So the dot the app draws is the
     * dot the probe measured, on every display, with a cutout or without.
     *
     * Until 2026-09-26 the app read fy as a fraction of the frame, twice
     * over. First of the display, which under a cutout is a picture the
     * readers never see (V1: the plate stood at fy 0.154 to 0.176 of the
     * frame on LDPlayer at 1080 x 2340 with a 136 px strip). Then of the
     * frame, which is not the window either: 0.1555 of a 9:16 frame is
     * 0.1796 of the window, 48 px below the measured place at 1920, and the
     * plate stood in the rows of [Quest.STAGE_BAND] -- 158 frames tipped by
     * the 128 dp plate against the 57 the probe had priced, `stage_number`
     * 99 of them (V2, PLAN_FORMATE.md; notes/overlay.md, "The overlay").
     */
    fun displayY(fy: Double, w: Int, h: Int, top: Int): Double {
        val r = Dungeon.gameRectWh(w, h - top)
        return top + r.y0 + fy * r.gh
    }

    /** [displayY] backwards: the fy of display row [y]. */
    fun fyAt(y: Double, w: Int, h: Int, top: Int): Double {
        val r = Dungeon.gameRectWh(w, h - top)
        return (y - top - r.y0) / r.gh
    }

    /**
     * The dot's rows on a display [w] x [h] whose game canvas starts [top]
     * rows down, at [fy]: first and one past the last, in display pixels.
     * The plate stands inside them, lowered by half the difference of the
     * two heights ([plate]), so these are the rows of both windows.
     */
    fun rowsAt(fy: Double, w: Int, h: Int, top: Int): Pair<Double, Double> {
        val sq = square(w, h - top, false, fy)
        return top + sq.y to top + sq.y + sq.h
    }

    /**
     * Where the overlay stands off the readers' rows [fy0] to [fy1] while a
     * skill reads them (`Capture.overlayClear`), from [fy]: null where it is
     * off them already, else the place to stand at for the while. The top
     * of the measured strip, [MEASURED_FY_MIN], as since 2026-09-23; where
     * that is on the rows too, just above them, and where that is off the
     * display, just below them; where nothing is clear, [MEASURED_FY_MIN]
     * again, and the app says it is still in the way.
     *
     * Why the top of the strip is not always enough (PLAN_FORMATE.md V14,
     * measured 2026-09-27 on the corpus's whole 1080 x 2520 frames with the
     * dot and plate the app draws): over the canvas ceiling the Digivice
     * page stands at the top of the headroom, its bar at display rows 168
     * to 248, and the dot at 0.060 stands at 193 to 319 -- the bar is lost
     * at 0.060 and 0.070 and the page reads `unknown`. At 1920 the same bar
     * (rows 138 to 203) is lost with the dot at 0.100 to 0.130, and its list
     * with the plate anywhere from 0.140 down. The place just above the bar
     * is in the headroom at 2520 (the dot at rows 41 to 167) and is 0.060
     * itself at 1920. A place off the player's strip is only ever one of
     * these, for as long as one page is read: where the player may put the
     * dot (`Overlay.drop`) is unchanged.
     */
    fun clearFy(fy: Double, fy0: Double, fy1: Double, w: Int, h: Int, top: Int): Double? {
        val first = displayY(fy0, w, h, top)
        val last = displayY(fy1, w, h, top)
        fun inTheWay(f: Double): Boolean {
            val (a, b) = rowsAt(f, w, h, top)
            return a < last && b > first
        }
        fun onDisplay(f: Double): Boolean {
            val (a, b) = rowsAt(f, w, h, top)
            return a >= 0 && b <= h
        }
        if (!inTheWay(fy)) return null
        if (!inTheWay(MEASURED_FY_MIN)) return MEASURED_FY_MIN
        val size = rowsAt(fy, w, h, top).let { it.second - it.first }
        // One row of margin, and one more for the rounding of the corner.
        val above = fyAt(first - size / 2.0 - 2.0, w, h, top)
        if (!inTheWay(above) && onDisplay(above)) return above
        val below = fyAt(last + size / 2.0 + 2.0, w, h, top)
        if (!inTheWay(below) && onDisplay(below)) return below
        return MEASURED_FY_MIN
    }

    /**
     * A rectangle in frame pixels. The dot's is square -- the probe writes
     * it as x, y and one side -- and the word's plate beside it is not, which
     * is the only reason this carries two lengths (PLAN_ANDROID_DESIGN.md 3.1).
     */
    class Box(val x: Double, val y: Double, val w: Double, val h: Double) {
        constructor(x: Double, y: Double, size: Double) : this(x, y, size, size)

        /** Clipped to a picture of this size, as integer bounds: x1, y1, x2, y2. */
        fun bounds(iw: Int, ih: Int): IntArray = intArrayOf(
            maxOf(0, Math.round(x).toInt()),
            maxOf(0, Math.round(y).toInt()),
            minOf(iw, Math.round(x + w).toInt()),
            minOf(ih, Math.round(y + h).toInt()))

        /**
         * How far outside itself this rectangle's band reaches: a quarter of
         * its shorter side. On the dot, which is square, that is the probe's
         * own quarter of the dot unchanged.
         */
        val pad: Int get() = Math.round(minOf(w, h) * BAND_OF_DOT).toInt()
    }

    /**
     * The dot's square on a frame of this size, in that frame's pixels.
     *
     * `left` puts it flush against the left edge of the *screen*, which on a
     * long display is well inside the canvas; the fy is the centre, as a
     * fraction of the window -- the vocabulary every number in notes/overlay.md is
     * written in. `_overlay_probe.geometry` and `dot_rect`, in one.
     *
     * **In whole pixels, the way the app lays its windows out**
     * (`Overlay.DotView`: the disc a rounded fraction of the screen, the
     * window's corner rounded from [displayY]). Until 2026-09-26 this was
     * the laboratory's fractional square, and the plate beside it sat half
     * a row off the app's: at 1080 x 2340 its top at 282.6, drawn as 283,
     * where the app draws 282 -- and that one row is what decides whether
     * `summon.general_tab` finds Buddy on the General page there (notes/overlay.md,
     * "The overlay"). A probe that prices a rectangle the app does not draw
     * prices nothing.
     */
    fun square(w: Int, h: Int, left: Boolean, fy: Double): Box {
        val r = Dungeon.gameRectWh(w, h)
        val (edgeL, edgeR) = edges(w, h)
        val size = Math.round(DOT_OF_SCREEN * (edgeR - edgeL)).toDouble()
        // Flush with the display's edge, as the app lays its window out
        // (`Overlay`: lp.x 0 or display width less the window). On a display
        // wider than the game that edge is in the background beside the
        // canvas ([Dungeon.pillared]); everywhere else it is the canvas's.
        val (sideL, sideR) = if (Dungeon.pillared(w, h)) 0.0 to w.toDouble() else edgeL to edgeR
        val x = if (left) Math.round(sideL).toDouble() else Math.round(sideR) - size
        return Box(x, Math.round(r.y0 + fy * r.gh - size / 2.0).toDouble(), size)
    }

    /**
     * The width the dot, the gap and the plate are fractions of: the part of
     * the canvas the screen shows. The screen itself on a phone, the canvas
     * on a display wider than the game -- sized by the display, a 2076 px
     * tablet drew a 242 px disc and a 646 px plate, reaching 470 px into the
     * canvas, where the phone's plate reaches 336 of 1080 (PLAN_FORMATE.md
     * 9). The app sizes its windows by this ([Overlay]), the probe by the
     * same, so the mask costs a tablet what it costs a phone.
     */
    fun shownWidth(w: Int, h: Int): Double = edges(w, h).let { it.second - it.first }

    /**
     * The plate beside the dot, on the side away from the edge: the same
     * row, centred on the dot, [GAP_OF_SCREEN] between them, and its two
     * sides given as fractions of the screen the way [PLATE_W_OF_SCREEN] is.
     * `_overlay_probe.word_rect`, with the plate's size as an argument so
     * that a probe can ask what another size would cost. Whole pixels, as
     * [square] and as `Overlay.DotView.plateParams`: every side rounded, and
     * the plate lowered from the dot's corner by half the difference of the
     * two heights in whole pixels.
     */
    fun plate(w: Int, h: Int, left: Boolean, fy: Double,
              plateW: Double = PLATE_W_OF_SCREEN, plateH: Double = PLATE_H_OF_SCREEN): Box {
        val dot = square(w, h, left, fy)
        val (edgeL, edgeR) = edges(w, h)
        val screen = edgeR - edgeL
        val pw = Math.round(plateW * screen).toInt()
        val ph = Math.round(plateH * screen).toInt()
        val gap = Math.round(GAP_OF_SCREEN * screen).toInt()
        val size = dot.w.toInt()
        val x = if (left) dot.x + size + gap else dot.x - gap - pw
        return Box(x, dot.y + (size - ph) / 2, pw.toDouble(), ph.toDouble())
    }

    /**
     * What of the canvas the screen actually shows, left and right, in the
     * picture's pixels: the canvas where it fits, the picture's own width
     * where the canvas is wider (cover).
     */
    private fun edges(w: Int, h: Int): Pair<Double, Double> {
        val r = Dungeon.gameRectWh(w, h)
        val g = Dungeon.GAME_IN_WINDOW
        val gx = r.x0.toDouble() + g[0] * r.gw.toDouble()
        val gw = g[2] * r.gw.toDouble()
        return maxOf(gx, 0.0) to minOf(gx + gw, w.toDouble())
    }

    /**
     * Every rectangle filled with the colour around it, in place.
     *
     * The band outside a rectangle decides its colour, one channel at a
     * time, by the middle element of the sorted band. Pixels that belong to
     * *any* of the rectangles are left out of every band, so that the dot
     * cannot colour the word's fill or the other way round -- with one
     * rectangle this is the probe's own rule unchanged. All the fills are
     * taken before anything is painted, so the order of the list cannot
     * change the answer.
     *
     * A rectangle wholly outside the picture is skipped; an empty band (it
     * cannot happen, the rectangle is inside the picture) fills black, which
     * is an answer and not a guess.
     */
    fun mask(img: Mat, rects: List<Box>) {
        if (rects.isEmpty()) return
        val w = img.cols()
        val h = img.rows()
        val ch = img.channels()
        val kept = rects.filter { val b = it.bounds(w, h); b[2] > b[0] && b[3] > b[1] }
        val boxes = kept.map { it.bounds(w, h) }
        if (boxes.isEmpty()) return
        val fills = ArrayList<IntArray?>(boxes.size)
        for ((i, b) in boxes.withIndex()) {
            val pad = kept[i].pad
            val bx1 = maxOf(0, b[0] - pad)
            val by1 = maxOf(0, b[1] - pad)
            val bx2 = minOf(w, b[2] + pad)
            val by2 = minOf(h, b[3] + pad)
            fills.add(fill(img, bx1, by1, bx2, by2, boxes, ch))
        }
        for ((i, b) in boxes.withIndex()) {
            val f = fills[i] ?: IntArray(ch)
            paint(img, b, f, ch)
        }
    }

    /**
     * The middle element of the sorted band, per channel. Counted into 256
     * bins rather than sorted: the element at index n/2 of the sorted values
     * is the same either way, and a histogram does not allocate the band.
     */
    private fun fill(img: Mat, bx1: Int, by1: Int, bx2: Int, by2: Int,
                     boxes: List<IntArray>, ch: Int): IntArray? {
        val width = bx2 - bx1
        val row = ByteArray(width * ch)
        val hist = Array(ch) { IntArray(256) }
        var n = 0
        for (y in by1 until by2) {
            img.get(y, bx1, row)
            for (x in bx1 until bx2) {
                var inside = false
                for (b in boxes) {
                    if (x >= b[0] && x < b[2] && y >= b[1] && y < b[3]) { inside = true; break }
                }
                if (inside) continue
                val o = (x - bx1) * ch
                for (c in 0 until ch) hist[c][row[o + c].toInt() and 0xFF] += 1
                n += 1
            }
        }
        if (n == 0) return null
        val want = n / 2
        return IntArray(ch) { c ->
            var seen = 0
            var value = 255
            for (v in 0..255) {
                seen += hist[c][v]
                if (seen > want) { value = v; break }
            }
            value
        }
    }

    private fun paint(img: Mat, b: IntArray, f: IntArray, ch: Int) {
        val width = b[2] - b[0]
        val row = ByteArray(width * ch)
        for (x in 0 until width) for (c in 0 until ch) row[x * ch + c] = f[c].toByte()
        for (y in b[1] until b[3]) img.put(y, b[0], row)
    }
}
