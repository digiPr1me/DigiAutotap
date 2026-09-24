package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `test_verify.py`, case for case: the action verification offline.
 *
 * Exactly this part failed in the first real run, because it expected an
 * exact delta and a power-up collected at the same moment shifts that delta.
 *
 * The Python script calls `Actor._judge` on an instance made by `__new__`,
 * because the method reads none of the object. Kotlin has no such door, so
 * the Actor is built with a capture that is never asked and a calibration
 * that is never read -- which proves the same thing about `judge`: it
 * answers from the deltas and the banner alone.
 */
class VerifyTest {

    private val actor = Actor(MiniNeverAsked, Vision(ClassPathAssets), MINI_UNUSED_CALIB)

    private fun judge(kind: String, deltas: Map<String, Long>, banner: String? = null,
                      col: Int = 1, direction: String = "right"): String =
        actor.judge(Action(kind, direction, 0 to 0), col, deltas, banner)

    @Test
    fun `a step is proved by any counter that moved`() {
        assertEquals(Actions.OK, judge("step", mapOf("paws" to -1L)),
                     "step, one paw gone")
        assertEquals(Actions.OK, judge("step", mapOf("paws" to -1L, "meters" to 1L)),
                     "step right with scroll")
        assertEquals(Actions.OK, judge("step", mapOf("paws" to 4L)),
                     "step collects a paw power-up")
        assertEquals(Actions.OK, judge("step", mapOf("paws" to -1L, "top_orange" to 125L)),
                     "step collects a ticket")
        assertEquals(Actions.OK, judge("step", mapOf("top_orange" to 125L)),
                     "paws unreadable, but a ticket arrived")
    }

    @Test
    fun `nothing moved is read off the banner`() {
        assertEquals(Actions.BANNER_MOVE, judge("step", emptyMap(), "move"),
                     "nothing happened, move banner")
        assertEquals(Actions.NO_EFFECT, judge("step", emptyMap(), null),
                     "nothing happened, no banner")
    }

    @Test
    fun `a pyramid is proved by the claw, and a vanished one reports the step`() {
        assertEquals(Actions.OK, judge("destroy", mapOf("claws" to -1L)),
                     "pyramid, one claw gone")
        assertEquals(Actions.OK, judge("destroy", mapOf("claws" to -1L, "fireballs" to 1L)),
                     "pyramid plus power-up underneath")
        assertEquals(Actions.MOVED_INSTEAD,
                     judge("destroy", mapOf("paws" to -1L, "meters" to 1L)),
                     "pyramid already gone, the click was a step")
        assertEquals(Actions.NO_EFFECT, judge("destroy", emptyMap()),
                     "pyramid, nothing")
    }

    @Test
    fun `an Insufficient banner with no movement names an empty resource`() {
        assertEquals(Actions.INSUFFICIENT, judge("destroy", emptyMap(), "insufficient"),
                     "claws empty, Insufficient banner")
        assertEquals(Actions.INSUFFICIENT, judge("step", emptyMap(), "insufficient"),
                     "paws empty, Insufficient banner")
    }

    @Test
    fun `the skill is proved by the fireball or by two or three metres`() {
        assertEquals(Actions.OK, judge("skill", mapOf("fireballs" to -1L, "meters" to 3L)),
                     "skill from column 2")
        assertEquals(Actions.OK, judge("skill", mapOf("meters" to 2L)),
                     "skill, fireball unreadable, metres plus 2")
        assertEquals(Actions.NO_EFFECT, judge("skill", emptyMap()),
                     "skill, nothing")
    }
}
