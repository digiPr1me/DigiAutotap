package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `test_pace.py`, case for case: the adaptive pacing offline.
 *
 * The bot speeds up after clean actions and becomes cautious again on any
 * failure. That has to stay traceable. The script's `make()` builds an Actor
 * with `__new__` and sets six fields; here the constructor takes the same
 * six, and `min_pace` is 0.35 as the script sets it rather than the 0.60 the
 * skill runs with.
 */
class PaceTest {

    private fun make(): Actor = Actor(
        MiniNeverAsked, Vision(ClassPathAssets), MINI_UNUSED_CALIB,
        clickDelay = 1.5, settle = 0.55, verifyPause = 0.2,
        minPace = 0.35, adaptive = true)

    @Test
    fun `it takes three clean actions before it speeds up`() {
        val a = make()
        a.onCleanSuccess()
        a.onCleanSuccess()
        assertEquals(1.0, a.pace, "first two successes do not speed up")

        a.onCleanSuccess()
        assertTrue(a.pace < 1.0, "from the third success it speeds up")
    }

    @Test
    fun `the lower bound is respected`() {
        val a = make()
        repeat(33) { a.onCleanSuccess() }
        assertTrue(Math.abs(a.pace - a.minPace) < 1e-9, "lower bound is respected: ${a.pace}")
    }

    @Test
    fun `a failure resets to cautious at once`() {
        val a = make()
        repeat(33) { a.onCleanSuccess() }
        a.onTrouble()
        assertEquals(1.0, a.pace, "a failure resets to cautious at once")
        assertEquals(0, a.cleanStreak, "a failure resets the streak")
    }

    /**
     * A second try only brakes a little, it does not reset. Before this was
     * fixed it threw the pace factor back to 1.0 every three actions.
     */
    @Test
    fun `a retry only slows down slightly`() {
        val c = make()
        repeat(10) { c.onCleanSuccess() }
        val p = c.pace
        c.onSlowConfirm()
        assertTrue(p < c.pace && c.pace < 1.0, "a retry only slows down slightly: $p -> ${c.pace}")
    }

    @Test
    fun `a fixed tick stays fixed`() {
        val b = make()
        b.adaptive = false
        repeat(20) { b.onCleanSuccess() }
        assertEquals(1.0, b.pace, "a fixed tick stays fixed")
    }

    /** The three waits are the base values times the pace factor. */
    @Test
    fun `every wait hangs off the one factor`() {
        val a = make()
        assertEquals(1.5, a.clickDelay)
        assertEquals(0.55, a.settle)
        assertEquals(0.2, a.verifyPause)
        a.pace = 0.5
        assertEquals(0.75, a.clickDelay)
        assertEquals(0.275, a.settle)
        assertEquals(0.1, a.verifyPause)
    }
}
