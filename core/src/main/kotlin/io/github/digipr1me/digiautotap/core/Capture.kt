package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * What core reads the screen with and acts through (PLAN_ANDROID_5_SHELL.md
 * 3.1). The app fills it with the accessibility service; the flow tests of
 * the skill sessions fill it with a fake that plays back stored frames.
 *
 * Coordinates are display pixels of the frame `grab` returned, which is
 * the whole display.
 */
interface Capture {
    /**
     * The whole display, BGR, uint8 -- what `cv2.imread` gives and what every
     * reader in core expects. A fresh picture or a [CaptureError], never the
     * last good one again: that is the mss freeze from the PC (NOTES.md,
     * "Screen capture freezes while the display sleeps"), and a caller that
     * is handed an old frame cannot tell it from a still screen.
     */
    fun grab(): Mat

    fun tap(x: Int, y: Int)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long)
    fun back()

    /** The package whose window came to the front last, or null before any did. */
    fun inFront(): String?

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
}

/** No frame: the service is not running, the display is off, the system refused. */
class CaptureError(message: String) : Exception(message)
