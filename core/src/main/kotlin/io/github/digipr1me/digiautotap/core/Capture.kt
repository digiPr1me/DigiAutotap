package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * What core reads the screen with and acts through (PLAN_ANDROID_5_SHELL.md
 * 3.1). The app fills it with the accessibility service; the flow tests of
 * the skill sessions fill it with a fake that plays back stored frames.
 *
 * Coordinates are pixels of the frame `grab` returned. That is the game's
 * window on the display as the system names it -- the whole display
 * wherever the game fills it, the letterbox on a landscape one (GameWindow)
 * -- less the strip over a camera cutout where the phone has one: the game
 * keeps its canvas under the cutout's safe inset, so the app cuts the strip
 * off the frame and moves every gesture back by it, and by the window's
 * place (DigiAutotapService.grab). A reader never sees the difference.
 */
interface Capture {
    /**
     * The game's part of the display, BGR, uint8 -- what `cv2.imread` gives and what every
     * reader in core expects. A fresh picture or a [CaptureError], never the
     * last good one again: that is the mss freeze from the PC (the laboratory's
     * notes, "Screen capture freezes while the display sleeps"), and a
     * caller that is handed an old frame cannot tell it from a still screen.
     */
    fun grab(): Mat

    fun tap(x: Int, y: Int)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long)
    fun back()

    /** The package whose window came to the front last, or null before any did. */
    fun inFront(): String?

    /**
     * [inFront], asked again after the last frame [grab] returned -- late
     * enough that a switch to another app which had begun under that frame
     * has been told by now. The system names the new app a few hundred ms
     * after its window is on the display (notes/director.md, "A frame is
     * taken before the system says who is in front"), so the app waits out
     * what is left of that lag; a fake that plays a switch has it at once.
     */
    fun inFrontAfterFrame(): String? = inFront()

    /**
     * Keep the app's own overlay off the rows [fy0] to [fy1] of the game
     * (fractions of `Dungeon.gameRect`), until [overlayBack]. The overlay is
     * masked out of every frame, and a counter under the mask is a counter
     * nobody can read: Network Defense Ops' stands under the plate at the
     * dot's default place (Dungeon.HEADER_COUNTER). The player's idea of
     * 2026-09-23: the bot moves the dot itself, for as long as it needs the
     * rows, and puts it back. Nothing to do where there is no overlay.
     */
    fun overlayClear(fy0: Double, fy1: Double) {}

    /** The overlay back at the player's own place, after [overlayClear]. */
    fun overlayBack() {}

    /**
     * The activity in front, for telling an ad from the game, or null where
     * there is none to be had -- a flow test that plays no ad. An ad is an
     * activity of the game's own package and not a picture
     * (PLAN_WERBUNG.md 1), so only the accessibility service can answer
     * this; the director and the tasks ask it ([Ads]), and nothing touches
     * what it names.
     */
    fun adHand(): AdHand? = null
}

/** No frame: the service is not running, the display is off, the system refused. */
class CaptureError(message: String) : Exception(message)
