package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * startup.py's two readers, carried over line for line: `title_bar`, the
 * Touch To Start bar, and `at_main`, the plain main screen. The walk that
 * uses them is a skill loop and comes later.
 *
 * Every constant keeps its Python name and value, and the sentence that says
 * where it came from; a number here is changed in startup.py first
 * (NOTES.md, "Two implementations, one direction").
 */
object Startup {

    // The one piece of the title screen that is the game's own furniture
    // rather than this month's advert: a wide, flat, translucent blue bar
    // across the bottom of the artwork, with the words on it in whatever
    // language the game is running in. The words are never read.
    //
    // Measured over the three real title frames there are -- a window frame
    // at 624 x 1076, an ADB frame at 1080 x 1920, and a second ADB frame with
    // the "Exit the game?" prompt dimming everything behind it -- as shares
    // of the reference window (see Dungeon.gameRect):
    //
    //   frame                        fx      fy      fw     aspect  fill
    //   window, 624 x 1076         0.483   0.848   0.460    6.9     0.90
    //   ADB, 1080 x 1920           0.483   0.846   0.459    7.1     0.91
    //   ADB, dimmed by the prompt  0.474   0.846   0.438    6.8     0.90
    //
    // The two sources agree to a thousandth in both directions, which is
    // what says these numbers are the game's and not the window's.
    //
    // The bar's own colour over those three frames: hue 100 to 120 with the
    // middle 90 % between 101 and 116, saturation 112 to 216, value 70 to
    // 178 -- the bottom of that value range being the dimmed one. The mask
    // below starts at 60 in both, which leaves the dimmed bar a sixth of its
    // own range to spare. It is wider than Dungeon.BLUE on purpose: this bar
    // is translucent, so what it measures depends on the advert behind it.
    val BAR_BLUE = Dungeon.Hsv(intArrayOf(95, 60, 60), intArrayOf(120, 255, 255))

    // The shape tests. Run over every stored frame in the project -- 495 of
    // them, both frame shapes, nine debug folders -- these three title frames
    // are the only ones that pass, and the load-bearing test is fy.
    //
    // What else in this game is a wide flat blue bar low down, and where it
    // sits:
    //
    //   the Special Summon banner        fy 0.797 to 0.801   (17 frames)
    //   the profile switcher's bar       fy 0.801
    //   a quest card's own edge          fy 0.887
    //   the summon sequence's footer     fy 0.949
    //
    // So the nearest impostor of any kind stands at 0.8013 and the bar at
    // 0.846. The band below starts at 0.820, which is 0.019 above the worst
    // impostor and 0.026 below the thing it has to catch -- a fifth of the
    // game's height between them either way would be luxury; two per cent is
    // what there is, and it is the same two per cent that let the Daily Bonus
    // wheel into the bond bubble's band. Hence the other four tests, none of
    // which is doing the work alone but each of which the impostors also
    // fail:
    //
    //   aspect  the bar 6.8 to 7.1; a hairline edge in the nav bar 103; the
    //           profile switcher's bar 3.46
    //   fill    the bar 0.90; the nearest thing that passes everything else 0.41
    //   fw      the bar 0.438 to 0.460; the nearest 0.062
    //   fx      nothing else comes close enough to matter
    //
    // The floors sit 17 % under the fill and 18 % under the width they have
    // to catch, which is the margin NOTES.md asks for; fy is the one that
    // cannot have it, and is why the other four are there.
    val BAR_FY = doubleArrayOf(0.820, 0.875)
    const val BAR_FX_OFF = 0.06
    val BAR_ASPECT = doubleArrayOf(4.0, 20.0)
    const val BAR_FILL_MIN = 0.75
    val BAR_FW = doubleArrayOf(0.36, 0.58)

    /** `title_bar`'s dict: the bar's place and size, and the two shape numbers. */
    data class Bar(val fx: Double, val fy: Double, val fw: Double, val fh: Double,
                   val aspect: Double, val fill: Double) {
        fun toOracle(): Map<String, Any?> = mapOf(
            "fx" to fx, "fy" to fy, "fw" to fw, "fh" to fh, "aspect" to aspect, "fill" to fill)
    }

    /**
     * The Touch To Start bar, or null.
     *
     * Null also answers "is the title screen up", which is the only reason
     * anything here needs to know. It does NOT answer "is it safe to tap":
     * the bar is still plainly visible behind the "Exit the game?" prompt --
     * measured, that is the third frame in the table above -- so a caller has
     * to rule the prompt out first. Starter does; see its loop.
     */
    fun titleBar(img: Mat): Bar? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        if (gw <= 0 || gh <= 0) return null
        val hsv = Dungeon.hsv(img)
        val mask = Dungeon.inRange(hsv, BAR_BLUE)
        val (count, stats) = Dungeon.components(mask)
        hsv.release(); mask.release()
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (h < 3 || w < 10) continue
            val fx = (x + w / 2.0 - x0) / gw
            val fy = (y + h / 2.0 - y0) / gh
            val fw = w / gw.toDouble()
            if (!(BAR_FY[0] <= fy && fy <= BAR_FY[1])) continue
            if (kotlin.math.abs(fx - 0.5) > BAR_FX_OFF) continue
            val aspect = w / h.toDouble()
            if (!(BAR_ASPECT[0] <= aspect && aspect <= BAR_ASPECT[1])) continue
            val fill = area / (w * h).toDouble()
            if (fill < BAR_FILL_MIN) continue
            if (!(BAR_FW[0] <= fw && fw <= BAR_FW[1])) continue
            return Bar(fx, fy, fw, h / gh.toDouble(), aspect, fill)
        }
        return null
    }

    /**
     * Is the plain main screen in front, with nothing over it?
     *
     * Dungeon.autoButton already answers exactly this and was measured for
     * it: the game dims what is behind a dialog, the dimmed disc breaks up
     * out of the blue range, and a button found at full fill therefore means
     * both "here is the button" and "nothing is covering the screen". Checked
     * over all 497 stored frames, every frame with a crisp auto button also
     * has the home globe and no frame has the globe alone that matters here
     * -- so this is the stricter of the two and there is nothing to gain by
     * asking both.
     */
    fun atMain(img: Mat): Boolean = Dungeon.autoButton(img) != null
}
