package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The counter tracker's two rules and the third that came with
 * PLAN_WORLD_SEARCH_FORMATE.md F19: a reading with digits missing is thrown
 * away for good (CounterTracker.cutShort; notes/world-search.md, "A number
 * with digits missing is not the number"). No frame is read here -- the
 * numbers are the ones the format tour and the skin tour logged.
 */
class TrackerTest {

    /** A tracker that believes [key] is [value] and trusts it: read twice alike. */
    private fun trusting(key: String, value: Long): CounterTracker =
        CounterTracker(maxActions = 1).also {
            it.update(mapOf(key to value))
            it.update(mapOf(key to value))
        }

    /**
     * Every meter reading the tours threw away, as (believed, read): B4a four,
     * B4b five (47733 against 477 three times, against 47 once, 47614 against
     * 14), B7 three -- twelve over 70 passes.
     */
    private val thrownAway = listOf(
        46760L to 760L, 46836L to 836L, 47064L to 47L, 47103L to 7103L,
        47733L to 477L, 47733L to 47L, 47733L to 477L, 47733L to 477L, 47614L to 14L,
        47165L to 166L, 47227L to 227L, 47313L to 47L)

    @Test
    fun `every reading the tours threw away is a cut, and none of them is ever taken`() {
        for ((old, new) in thrownAway) {
            val t = trusting("meters", old)
            repeat(5) {
                t.update(mapOf("meters" to new))
                assertEquals(listOf(Triple("meters", old, new)), t.cut, "$old against $new is a cut")
                assertEquals(listOf(Triple("meters", old, new)), t.suspicious, "and it is thrown away")
                assertTrue(t.resynced.isEmpty(), "never pulled straight to it: $old against $new")
            }
            assertEquals(old, t.values["meters"], "$new five times over is still not the truth")
        }
    }

    @Test
    fun `the landscape pass's four readings, and the step after them`() {
        // 1920 x 1080, 2026-09-28 20:12:10 to 20:12:17: #16 477, #17 47, #18
        // and #19 477. Under the old rule the next 477 was the truth.
        val t = trusting("meters", 47733)
        for (read in listOf(477L, 47L, 477L, 477L, 477L, 477L)) {
            t.update(mapOf("meters" to read))
            assertTrue(t.resynced.isEmpty(), "$read is not pulled straight to")
        }
        assertEquals(47733L, t.values["meters"])
        val deltas = t.update(mapOf("meters" to 47734L))
        assertEquals(1L, deltas["meters"], "the next whole reading is a step again")
    }

    @Test
    fun `a jump the bot missed is still taken after three alike`() {
        // The tracker's second rule, as tracker.py has it: the same
        // implausible value three times is the truth and the stored one stale.
        val t = trusting("meters", 47733)
        t.update(mapOf("meters" to 47740L))
        t.update(mapOf("meters" to 47740L))
        assertTrue(t.resynced.isEmpty())
        t.update(mapOf("meters" to 47740L))
        assertEquals(listOf(Triple("meters", 47733L, 47740L)), t.resynced)
        assertEquals(47740L, t.values["meters"])
        assertTrue(t.cut.isEmpty(), "a longer or equal value is never a cut")
    }

    @Test
    fun `a cut in between does not reset a real jump's count`() {
        val t = trusting("meters", 47733)
        t.update(mapOf("meters" to 47740L))
        t.update(mapOf("meters" to 477L))
        t.update(mapOf("meters" to 47740L))
        t.update(mapOf("meters" to 47740L))
        assertEquals(47740L, t.values["meters"], "three alike, with a cut among them that counts for nothing")
    }

    @Test
    fun `a reset that is not the head or the tail of the old value is still taken`() {
        val t = trusting("meters", 47733)
        repeat(3) { t.update(mapOf("meters" to 0L)) }
        assertEquals(0L, t.values["meters"], "0 is no part of 47733 to 47736: an ordinary jump")
        assertTrue(t.cut.isEmpty())

        // The one change the rule keeps out, and says so: a reset to a value
        // that happens to be the head or the tail of one the counter can have
        // now -- 5, the tail of 47735. No reset of the metres has been seen.
        val u = trusting("meters", 47733)
        repeat(3) { u.update(mapOf("meters" to 5L)) }
        assertEquals(47733L, u.values["meters"])
        assertEquals(listOf(Triple("meters", 47733L, 5L)), u.cut)
    }

    @Test
    fun `a first reading is not trusted, so a digit too many there does not lock the truth out`() {
        // Were the first reading a misread with a digit too many, the true
        // value would look like a cut of it. So the first reading alone is not
        // enough to call anything a cut of it.
        val t = CounterTracker(maxActions = 1)
        t.update(mapOf("meters" to 477331L))
        repeat(3) { t.update(mapOf("meters" to 47733L)) }
        assertEquals(47733L, t.values["meters"], "pulled straight as before")
    }

    @Test
    fun `a value reached by a plausible step is trusted`() {
        val t = CounterTracker(maxActions = 1)
        t.update(mapOf("meters" to 47732L))
        t.update(mapOf("meters" to 47733L))
        t.update(mapOf("meters" to 477L))
        assertEquals(listOf(Triple("meters", 47733L, 477L)), t.cut)
    }

    @Test
    fun `the other counters, and the rewards that must still come in`() {
        val paws = trusting("paws", 1293)
        paws.update(mapOf("paws" to 93L))
        assertEquals(listOf(Triple("paws", 1293L, 93L)), paws.cut)
        paws.update(mapOf("paws" to 1303L))
        assertEquals(1303L, paws.values["paws"], "a paw token, +10, is a step as always")

        // The top counters only rise, by any amount -- a pickup of 125, or the
        // 10000 V18 read -- and a head or tail of the old value is a cut:
        // 2445 was V18's stale box, taken as the truth then.
        val top = trusting("top_orange", 12445)
        top.update(mapOf("top_orange" to 2445L))
        assertEquals(listOf(Triple("top_orange", 12445L, 2445L)), top.cut)
        top.update(mapOf("top_orange" to 22445L))
        assertEquals(22445L, top.values["top_orange"], "a big pickup is plausible and taken")

        // Two digits falling to one is a step, never a cut: 10 claws to 9.
        val claws = trusting("claws", 10)
        val d = claws.update(mapOf("claws" to 9L))
        assertEquals(-1L, d["claws"])
        assertTrue(claws.cut.isEmpty())
    }
}
