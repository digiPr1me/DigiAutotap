package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The part of a screenshot that is the game's (GameWindow, PLAN_FORMATE.md
 * S6). The windows are the ones LDPlayer's `dumpsys window` and the app's
 * own log gave on 2026-09-27, and the cuts they must give are the ones
 * `grab` made before the window was asked, wherever the window is the
 * display.
 */
class GameWindowTest {

    private fun cut(w: Int, h: Int, win: IntArray?, inset: Int) = GameWindow.cut(w, h, win, inset)

    @Test
    fun `a window that is the display cuts what grab always cut`() {
        val full = intArrayOf(0, 0, 1080, 1920)
        assertEquals(GameWindow.Cut(0, 0, 1080, 1920, 0, 0), cut(1080, 1920, full, 0))
        assertEquals(cut(1080, 1920, null, 0), cut(1080, 1920, full, 0))
        // LDPlayer's 136 px hole at 2340: the strip is cut, nothing over the canvas.
        assertEquals(GameWindow.Cut(0, 136, 1080, 2204, 0, 136), cut(1080, 2340, intArrayOf(0, 0, 1080, 2340), 136))
        // 1080 x 2520 without a cutout: nothing cut, 180 rows of headroom kept (V4).
        assertEquals(GameWindow.Cut(0, 0, 1080, 2520, 180, 180), cut(1080, 2520, intArrayOf(0, 0, 1080, 2520), 0))
        // Over the ceiling with a cutout: the camera's rows go, the rest is headroom.
        assertEquals(GameWindow.Cut(0, 100, 1080, 2540, 200, 300), cut(1080, 2640, intArrayOf(0, 0, 1080, 2640), 100))
    }

    @Test
    fun `the landscape letterbox is the canvas pillaredGame found`() {
        // [656,0][1264,1080], the system's window on LDPlayer's 1920 x 1080.
        val c = cut(1920, 1080, intArrayOf(656, 0, 1264, 1080), 0)
        assertEquals(GameWindow.Cut(656, 0, 608, 1080, 0, 0), c)
        // The frame model's canvas on the whole picture is the same rectangle,
        // and inside the cut the canvas fills the frame as on 1080 x 1920.
        val pillared = Dungeon.gameRectWh(1920, 1080)
        val inCut = Dungeon.gameRectWh(c.w, c.h)
        assertEquals(pillared.x0 - c.x, inCut.x0)
        assertEquals(pillared.y0, inCut.y0)
        assertEquals(pillared.gw, inCut.gw)
        assertEquals(pillared.gh, inCut.gh)
    }

    @Test
    fun `a window under the cutout takes only the rows of the cutout it covers`() {
        // A window laid out below a 105 px cutout (Samsung's "hide the cutout"
        // would be this, if the system says so): nothing left to cut in it.
        assertEquals(GameWindow.Cut(0, 105, 1080, 2235, 0, 0), cut(1080, 2340, intArrayOf(0, 105, 1080, 2340), 105))
        // Split screen, the game in the bottom half: the cutout is not in it at all.
        assertEquals(GameWindow.Cut(0, 1180, 1080, 1160, 0, 0), cut(1080, 2340, intArrayOf(0, 1180, 1080, 2340), 105))
    }

    @Test
    fun `a window a pixel short of a scaled display is the display`() {
        // LDPlayer at `wm size 1080x2000`: [0,0][1079,1999] from the system.
        assertEquals(cut(1080, 2000, null, 0), cut(1080, 2000, intArrayOf(0, 0, 1079, 1999), 0))
        // Three pixels in is a window of its own.
        assertEquals(GameWindow.Cut(3, 0, 1077, 2000, 0, 0), cut(1080, 2000, intArrayOf(3, 0, 1080, 2000), 0))
    }

    @Test
    fun `a window in another space or too small is not taken`() {
        val whole = cut(1080, 1920, null, 0)
        // The display turning under the question: a landscape window over a portrait picture.
        assertEquals(whole, cut(1080, 1920, intArrayOf(0, 0, 1920, 1080), 0))
        assertEquals(whole, cut(1080, 1920, intArrayOf(0, 0, 200, 1920), 0))
        assertFalse(GameWindow.usable(1080, 1920, intArrayOf(-1, 0, 1080, 1920)))
        assertTrue(GameWindow.usable(1080, 1920, intArrayOf(0, 960, 1080, 1920)))
    }
}
