package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * The game's rectangle on the displays of PLAN_FORMATE.md 3b, against what
 * LDPlayer drew there (section 9): scale and offset of the 1080 x 1920 twin,
 * fitted on the dungeon list's middle, 2026-09-27. The rectangle of a display
 * is the reference's, scaled by s and moved by tx and ty; it may miss the fit
 * by two pixels and a scale by 0.002.
 */
class WideDisplayTest {

    private val ref = Dungeon.gameRectWh(1080, 1920)

    private fun meets(w: Int, h: Int, s: Double, tx: Double, ty: Double) {
        val r = Dungeon.gameRectWh(w, h)
        val scale = r.gw / ref.gw.toDouble()
        val x0 = ref.x0 * s + tx
        val y0 = ref.y0 * s + ty
        assertTrue(abs(scale - s) <= 0.002 && abs(r.x0 - x0) <= 2 && abs(r.y0 - y0) <= 2,
                   "$w x $h: $r, scale %.4f, against s %.4f x0 %.1f y0 %.1f".format(scale, s, x0, y0))
    }

    @Test
    fun `a display wider than the game has the canvas the full height, centred`() {
        meets(800, 1340, 0.6994, 22.1, -1.6)    // Galaxy Tab A7 Lite
        meets(1200, 1920, 0.9994, 60.1, 0.4)    // Galaxy Tab A8
        meets(1200, 2000, 1.0411, 37.8, 0.2)    // Galaxy Tab S6 Lite
        meets(1600, 2560, 1.3327, 80.1, 0.1)    // Galaxy Tab S8, Pixel Tablet
        meets(2136, 3200, 1.6661, 168.1, 0.7)   // Xiaomi Pad 7
        meets(2000, 2800, 1.4583, 212.2, 0.2)   // OnePlus Pad
        meets(1812, 2176, 1.1327, 294.1, 0.1)   // Z Fold5 inside
        meets(2076, 2152, 1.1199, 433.0, 1.1)   // Pixel 9 Pro Fold inside
    }

    @Test
    fun `the Pixel Fold's outer display is covered like every long display`() {
        meets(1080, 2092, 1.0891, -48.4, 0.6)
    }

    @Test
    fun `the window frames keep their models`() {
        // LDPlayer's window with its chrome, and without the sidebar
        // (fittedGame), as the corpus has them: a window's rectangle starts
        // at its tab bar, a bare game frame's 57 px above the picture.
        val chrome = Dungeon.gameRectWh(805, 1390)
        assertTrue(chrome.x0 == 0 && chrome.y0 == 0 && chrome.gw == 805 && chrome.gh == 1390, "$chrome")
        for ((w, h) in listOf(765 to 1390, 584 to 1076, 730 to 1389, 736 to 1392)) {
            val r = Dungeon.gameRectWh(w, h)
            assertTrue(r.y0 >= 0, "$w x $h is still a window: $r")
        }
    }
}
