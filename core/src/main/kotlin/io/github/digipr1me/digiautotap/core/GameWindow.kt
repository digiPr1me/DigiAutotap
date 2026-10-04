package io.github.digipr1me.digiautotap.core

import kotlin.math.abs

/**
 * Which part of a screenshot is the game's: its window, as the system lays
 * it out, less the strip over a camera cutout and with the rows over the
 * canvas ceiling kept as headroom. PLAN_FORMATE.md 10 (S6).
 *
 * The system says where the game's window is (`AccessibilityService.
 * getWindows`, the window's bounds on the display), without a threshold and
 * without a measurement of the picture. Measured on LDPlayer 2026-09-27
 * with `dumpsys window` beside the app's own question: on every portrait
 * display LDPlayer can make -- 1080 x 1920, 2340, 2520, 1200 x 1920, 2076 x
 * 2152, 720 x 1600 -- the window is the whole display, and on the 1920 x
 * 1080 landscape display the game is letterboxed by the system into
 * [656,0][1264,1080], which is to the pixel the canvas `Dungeon.
 * pillaredGame` had worked out from the picture. Split screen, a pop-up
 * window, Samsung's 16:9 on a long phone and a navigation bar the game
 * does not draw under are all a window smaller than the display, and none
 * of them can be made on LDPlayer (PLAN_FORMATE.md 3c): what this does with
 * them is what the API says the bounds are, not a measurement.
 *
 * So the frame the readers get is the window, cut out of the screenshot
 * before anything else; game_rect then starts from the window's size and
 * not the display's, and every reader, today's and the next one, reads a
 * game that fills its picture as it does on 1080 x 1920. Where the window
 * is the display, which is every frame of the corpus but the landscape
 * ones, nothing changes.
 */
object GameWindow {

    /**
     * The cut: the game's part of a [frameW] x [frameH] screenshot, left,
     * top, width and height in its pixels, and the [room] of headroom that
     * part holds over the canvas (Dungeon.CanvasFrame).
     */
    data class Cut(val x: Int, val y: Int, val w: Int, val h: Int, val room: Int, val canvasTop: Int) {
        val whole: Boolean get() = x == 0 && y == 0 && room == 0 && canvasTop == 0
    }

    /**
     * The window [win] (left, top, right, bottom on the display, null when
     * the system did not say) made a cut of the [frameW] x [frameH]
     * screenshot, with the cutout's safe inset [cutoutTop] (0 where there is
     * none, or where it is not taken: DigiAutotapService.cutoutTop).
     *
     * A window that does not lie inside the picture is in another space --
     * the display was turning under the question (notes/overlay.md, "The window's
     * coordinates and the frame's are two spaces") -- and is not taken; nor
     * is one under [MIN_SHARE] of the picture in either direction, which no
     * game window is and a stale answer about a closing one may be. Inside
     * the window the cutout counts only for the rows of it the window
     * covers, and the canvas ceiling (Dungeon.canvasTop) is asked of the
     * window's size.
     */
    fun cut(frameW: Int, frameH: Int, win: IntArray?, cutoutTop: Int): Cut {
        val r = win?.let { snap(frameW, frameH, it) }?.takeIf { usable(frameW, frameH, it) }
            ?: intArrayOf(0, 0, frameW, frameH)
        val w = r[2] - r[0]
        val h = r[3] - r[1]
        val inset = maxOf(0, cutoutTop - r[1])
        val top = Dungeon.canvasTop(w, h, inset)
        return Cut(r[0], r[1] + inset, w, h - inset, top - inset, top)
    }

    /**
     * [win] with every edge that lies within [SNAP_PX] of the picture's
     * edge moved onto it. Measured on LDPlayer 2026-09-27: on a `wm size`
     * of 1080 x 2000 the system named the game's window [0,0][1079,1999]
     * while `dumpsys window` had its frame at [0,0][1080,2000] -- the
     * display drawn scaled onto a panel of another size, and the bounds
     * rounded down on the way back; at 1080 x 1920 and on the landscape
     * letterbox the two agreed to the pixel. A phone's own resolution
     * switch (Samsung's HD+/FHD+/WQHD+) scales the same way. A window one
     * pixel short of the display is the display, and cutting that pixel
     * would give every reader a frame of a size no format has.
     */
    fun snap(frameW: Int, frameH: Int, win: IntArray): IntArray {
        fun near(a: Int, b: Int) = abs(a - b) <= SNAP_PX
        return intArrayOf(
            if (near(win[0], 0)) 0 else win[0],
            if (near(win[1], 0)) 0 else win[1],
            if (near(win[2], frameW)) frameW else win[2],
            if (near(win[3], frameH)) frameH else win[3])
    }

    /** How far from the picture's edge a window's edge is still on it: [snap]. */
    const val SNAP_PX = 2

    /** Does [win] lie inside the picture and cover enough of it to be the game's? */
    fun usable(frameW: Int, frameH: Int, win: IntArray): Boolean {
        val (l, t, r, b) = win.toList()
        if (l < 0 || t < 0 || r > frameW || b > frameH || r <= l || b <= t) return false
        return (r - l) >= frameW * MIN_SHARE && (b - t) >= frameH * MIN_SHARE
    }

    /**
     * The smallest share of the picture's width and of its height a game
     * window takes: a quarter. Not a measurement of any window -- split
     * screen gives half a display, a pop-up about a third -- but the floor
     * under which a window is not a place to read a game in.
     */
    const val MIN_SHARE = 0.25
}
