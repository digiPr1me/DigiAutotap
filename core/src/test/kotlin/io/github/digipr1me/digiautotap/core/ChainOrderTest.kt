package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Chain.reordered]: an order changed on the page while the core runs
 * (2026-10-01, the Meat Field moved to the front and Dungeons ran). The new
 * order goes on from where the round stands, and no step this round has
 * already moved past runs a second time.
 */
class ChainOrderTest {

    private fun steps(vararg keys: String) = keys.map { Chain.Step(it) }

    @Test
    fun `a new order before anything ran starts at its own first step`() {
        val c = Chain(steps("dungeon", "farm"), log = {}).reordered(steps("farm", "dungeon"), false)
        assertEquals("farm", c.current()?.key)
        c.advance()
        assertEquals("dungeon", c.current()?.key)
        c.advance()
        assertTrue(c.finished)
    }

    @Test
    fun `a step this round already ran is passed by where the new order has it`() {
        val old = Chain(steps("dungeon", "summon", "farm"), log = {})
        old.recordStart("dungeon")
        old.advance()                                    // dungeon done, summon next
        val c = old.reordered(steps("farm", "dungeon", "summon"), false)
        assertEquals("farm", c.current()?.key)
        c.advance()
        assertEquals("summon", c.current()?.key, "dungeon ran this round and is passed by")
        c.advance()
        assertTrue(c.finished)
        assertEquals(1, c.round)
    }

    @Test
    fun `a finished chain runs only a step that was added to it`() {
        val old = Chain(steps("dungeon"), log = {})
        old.advance()
        assertTrue(old.finished)
        val c = old.reordered(steps("dungeon", "farm"), false)
        assertFalse(c.finished)
        assertEquals("farm", c.current()?.key, "dungeon finished this round and does not run again")
        val same = old.reordered(steps("dungeon"), false)
        assertTrue(same.finished, "nothing new: still finished")
        assertNull(same.current())
    }

    @Test
    fun `a step twice in the order is passed by as often as it ran`() {
        val old = Chain(steps("summon", "dungeon", "summon"), log = {})
        old.advance()                                    // the first summon
        val c = old.reordered(steps("dungeon", "summon", "summon"), false)
        assertEquals("dungeon", c.current()?.key)
        c.advance()
        assertEquals("summon", c.current()?.key, "one summon ran, the second is still owed")
        c.advance()
        assertTrue(c.finished)
    }

    @Test
    fun `skips and retirements go with the new order, and a new round forgets the old one`() {
        val log = ArrayList<String>()
        val old = Chain(steps("dungeon", "summon"), repeat = true, log = { log += it })
        old.recordSkip("dungeon", "nothing to do")
        old.retire("dungeon", "no list")
        old.advance()
        old.recordStart("summon")
        val c = old.reordered(steps("summon", "dungeon", "farm"), true)
        assertEquals(listOf("dungeon" to "nothing to do"), c.skipped)
        assertEquals("no list", c.retiredReason("dungeon"))
        assertEquals("summon", c.current()?.key, "summon had not been moved past yet")
        c.advance()
        assertEquals("farm", c.current()?.key, "dungeon is passed by, farm is new")
        c.advance()
        assertEquals(2, c.round, "the round wraps after farm")
        assertEquals("summon", c.current()?.key, "and a new round runs every step again")
        assertTrue(log.contains("  chain: round 2 starting"), log.toString())
    }
}
