package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `test_router.py`, case for case: pathfinding offline, no emulator, no
 * interface. Boards are written as text -- P is a pyramid, F the figure, Z
 * the target -- and the four cases, the dodge direction and the step left
 * are exactly that script's.
 */
class RouterTest {

    companion object {
        const val PAW = 40.0
        const val CLAW = 200.0
        const val ACTION = 40.0
        const val LOOT = 25.0
        const val COST_STEP = PAW + ACTION            // 80
        const val COST_DESTROY = CLAW + ACTION - LOOT  // 215
    }

    private class Board(val start: Pair<Int, Int>, val goal: Pair<Int, Int>?,
                        val pyramids: Set<Pair<Int, Int>>)

    private fun build(rows: List<String>): Board {
        var start: Pair<Int, Int>? = null
        var goal: Pair<Int, Int>? = null
        val pyr = HashSet<Pair<Int, Int>>()
        for ((r, line) in rows.map { it.replace(" ", "") }.withIndex()) {
            for ((c, ch) in line.withIndex()) {
                when (ch) {
                    'P' -> pyr.add(r to c)
                    'F' -> start = r to c
                    'Z' -> goal = r to c
                }
            }
        }
        return Board(start!!, goal, pyr)
    }

    private fun route(rows: List<String>, canDestroy: Boolean = true,
                      goals: Set<Pair<Int, Int>>? = null): Route {
        val b = build(rows)
        val rt = Router({ r, c -> (r to c) in b.pyramids }, COST_STEP, COST_DESTROY,
                        canDestroy = canDestroy)
        return rt.search(b.start, goals ?: (b.goal?.let { setOf(it) } ?: emptySet()))
    }

    @Test
    fun `a clear path to the right goes right`() {
        val rt = route(listOf(".....", ".....", ".F..Z", ".....", "....."))
        assertEquals("right", rt.first)
        assertEquals(0, rt.destroys)
    }

    @Test
    fun `a pyramid in the way is cheaper to walk around`() {
        // Two steps of detour cost 160 Bits, one claw 215. So go around.
        val rt = route(listOf(".....", ".....", ".FP.Z", ".....", "....."))
        assertEquals(0, rt.destroys, "the detour, not the claw")
        assertTrue(rt.reachable)
    }

    @Test
    fun `a vertical wall is destroyed, and without claws it is impassable`() {
        val wall = listOf("..P..", "..P..", ".FP.Z", "..P..", "..P..")
        val rt = route(wall)
        assertEquals("right", rt.first)
        assertEquals(1, rt.destroys, "no way around, so one pyramid falls")

        val none = route(wall, canDestroy = false)
        assertTrue(!none.reachable, "without claws a vertical wall is impassable")
    }

    /**
     * The dodge direction on a tie, expected towards the centre. The script
     * prints these two rather than asserting; the numbers it printed are the
     * assertion here, because a bias that stopped working would still print.
     */
    @Test
    fun `dodging goes towards the middle row`() {
        val above = route(listOf(".....", ".FP.Z", ".....", ".....", "....."))
        assertEquals("down", above.first, "figure in row 2, the centre is below")
        val below = route(listOf(".....", ".....", ".....", ".FP.Z", "....."))
        assertEquals("up", below.first, "figure in row 4, the centre is above")
    }

    @Test
    fun `a step left is taken when it is the cheapest path`() {
        val rows = listOf("PPPPP", "PPPPP", ".FPPP", "PPPPP", "PPPPP")
        val rt = route(rows, goals = setOf(2 to 0))
        assertEquals("left", rt.first, "the target lies left of the figure")
        assertTrue(rt.reachable)
        // One step left: the step, the left penalty, and no middle bias in
        // row 2, which is the middle row.
        assertEquals(COST_STEP + 20.0, rt.cost)
    }

    @Test
    fun `an empty goal set advances to the furthest column`() {
        val rt = route(listOf(".....", ".....", ".F...", ".....", "....."),
                       goals = emptySet())
        assertEquals("right", rt.first)
        assertEquals(3, rt.steps, "from column 1 to column 4")
    }
}
