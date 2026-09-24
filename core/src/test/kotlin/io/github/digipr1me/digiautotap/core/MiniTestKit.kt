package io.github.digipr1me.digiautotap.core

/**
 * What the minigame's offline suites need and the game does not provide: a
 * capture that is never asked and a calibration that is never read.
 *
 * `test_verify.py` and `test_pace.py` build their Actor with
 * `actions.Actor.__new__(actions.Actor)` -- an object with no constructor
 * run at all -- because `_judge` and the pace methods read none of it.
 * Kotlin has no such door, so the Actor is built properly and handed these
 * two: if `judge` or the pace ever reached for a frame or a cell, the suite
 * would say so instead of quietly passing.
 *
 * Named with the minigame's own prefix because the L sessions run beside one
 * another and all write into this one test folder.
 */
object MiniNeverAsked : Capture {
    override fun grab() = throw CaptureError("this suite reads no frame")
    override fun tap(x: Int, y: Int) = throw AssertionError("this suite taps nothing")
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) =
        throw AssertionError("this suite swipes nothing")
    override fun back() = throw AssertionError("this suite presses nothing")
    override fun inFront(): String? = null
}

/** A calibration of the shape `calibrate` gives, that nothing here looks at. */
val MINI_UNUSED_CALIB = Calib(
    card = intArrayOf(0, 0, 100, 200), gridX0 = 0.0, gridY0 = 0.0,
    cellW = 20.0, cellH = 16.0, rois = emptyMap(),
    skillButton = intArrayOf(0, 0), skillRadius = 1)
