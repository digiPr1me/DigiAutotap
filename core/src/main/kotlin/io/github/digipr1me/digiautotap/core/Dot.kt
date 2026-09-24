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
     */
    const val DEFAULT_FY = 0.1555

    /** The narrow band of fy the sweep found quiet on the right edge (PLAN_ANDROID_DESIGN.md 3.2). */
    const val MEASURED_FY_MIN = 0.060
    const val MEASURED_FY_MAX = 0.155

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
     * fraction of the window -- the vocabulary every number in NOTES.md is
     * written in. `_overlay_probe.geometry` and `dot_rect`, in one.
     */
    fun square(w: Int, h: Int, left: Boolean, fy: Double): Box {
        val r = Dungeon.gameRectWh(w, h)
        val (edgeL, edgeR) = edges(w, h)
        val size = DOT_OF_SCREEN * (edgeR - edgeL)
        val x = if (left) edgeL else edgeR - size
        return Box(x, r.y0 + fy * r.gh - size / 2.0, size)
    }

    /**
     * The plate beside the dot, on the side away from the edge: the same
     * row, centred on the dot, [GAP_OF_SCREEN] between them, and its two
     * sides given as fractions of the screen the way [PLATE_W_OF_SCREEN] is.
     * `_overlay_probe.word_rect`, with the plate's size as an argument so
     * that a probe can ask what another size would cost.
     */
    fun plate(w: Int, h: Int, left: Boolean, fy: Double,
              plateW: Double = PLATE_W_OF_SCREEN, plateH: Double = PLATE_H_OF_SCREEN): Box {
        val dot = square(w, h, left, fy)
        val (edgeL, edgeR) = edges(w, h)
        val screen = edgeR - edgeL
        val pw = plateW * screen
        val ph = plateH * screen
        val gap = GAP_OF_SCREEN * screen
        val x = if (left) dot.x + dot.w + gap else dot.x - gap - pw
        return Box(x, dot.y + dot.h / 2.0 - ph / 2.0, pw, ph)
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
