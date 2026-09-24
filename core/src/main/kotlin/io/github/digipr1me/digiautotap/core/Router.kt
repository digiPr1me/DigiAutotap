package io.github.digipr1me.digiautotap.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.PriorityQueue

/**
 * `router.py`, carried over line for line: pathfinding as a cost search.
 *
 * Special-case rules used to pile up here about when to dodge and when to
 * destroy. On the way to an item the planner destroyed a pyramid as soon as
 * claws were available, without ever comparing the detour. That was exactly
 * the expensive choice.
 *
 * Now it calculates. Dijkstra over the five by five visible cells with real
 * prices, so the desired behaviours follow by themselves instead of being
 * coded individually.
 *
 *   detour when it pays        two steps cost 80 Bits each, a claw 200
 *   dodge towards the centre   a tiny bias per row of distance from the
 *                              middle row, which only breaks ties
 *   step left when cheaper     allowed, with a surcharge so no shuffling
 *                              arises
 *   destroy as a last resort   when no detour is cheaper
 *
 * Simplification that keeps this small: planning happens in the visible
 * frame. A step right out of the second column really moves the world rather
 * than the figure, but the sequence of cells traversed is the same, so for
 * path planning the two are equivalent. Replanning happens after every
 * action, so looking at the current window is enough.
 *
 * Three places where Python's spelling and Kotlin's differ, and where the
 * port has to say which it meant -- each of them decides which of two equal
 * paths comes out, and therefore which direction the figure walks:
 *
 *  - `heapq` compares the whole tuple `(cost, (row, col))`, so two nodes of
 *    the same cost come off the heap in row-then-column order. A
 *    `PriorityQueue` with only the cost compared has no order among equals
 *    at all, so the comparator below carries the node as well.
 *  - `DIRECTIONS.items()` walks a dict in insertion order (up, down, left,
 *    right) and the relaxation below keeps a node only on a strict `<`, so
 *    the first of equal directions wins. The order is part of the answer.
 *  - `best` is a dict and `min(...)` over it returns the first of equals in
 *    that same insertion order, which is what picks the advance target among
 *    equally cheap cells of the furthest column. Hence a LinkedHashMap, and
 *    re-assigning a key must not move it (it does not, in either language).
 */
object RouterConst {
    /** Python's `round(x, 2)`: the exact binary value, half to even. */
    fun round2(x: Double): Double =
        BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toDouble()
}

/** The result of a search, `router.Route`. */
class Route(
    /** "up", "down", "left", "right" or null. */
    val first: String? = null,
    /** Bits, null when unreachable. */
    val cost: Double? = null,
    val steps: Int = 0,
    val destroys: Int = 0,
    /** The cells walked, as (row, col). */
    val path: List<Pair<Int, Int>> = emptyList(),
) {
    val reachable: Boolean get() = cost != null

    override fun toString(): String =
        "Route($first, $cost Bits, $steps Schritte, $destroys Zerstoerungen)"
}

/**
 * @param isPyramid  (row, col) in the visible frame
 * @param costStep   the price of a step, paw plus action value
 * @param costDestroy the surcharge for entering a pyramid cell, claw plus
 *                   action minus the expected loot
 * @param leftPenalty surcharge for steps left, which stops back-and-forth
 * @param middleBias a tiny surcharge per row of distance from the middle
 *                   row. It only shows on a tie and must never outweigh a
 *                   real saving
 * @param canDestroy false when there are no claws
 */
class Router(
    private val isPyramid: (Int, Int) -> Boolean,
    costStep: Double,
    costDestroy: Double,
    leftPenalty: Double = 20.0,
    middleBias: Double = 1.0,
    private val rows: Int = Vision.ROWS,
    private val cols: Int = Vision.COLS,
    @Suppress("unused") private val figColMax: Int = 1,
    private val canDestroy: Boolean = true,
) {
    private val costStep = costStep
    private val costDestroy = costDestroy
    private val leftPenalty = leftPenalty
    private val middleBias = middleBias
    private val middle = (rows - 1) / 2.0

    companion object {
        /** `router.DIRECTIONS`, in the order Python's dict hands them out. */
        val DIRECTIONS: List<Pair<String, Pair<Int, Int>>> = listOf(
            "up" to (-1 to 0), "down" to (1 to 0), "left" to (0 to -1), "right" to (0 to 1))
    }

    // ------------------------------------------------------------------------
    /** What entering this cell costs, or null where it cannot be entered. */
    private fun enterCost(row: Int, col: Int, direction: String): Double? {
        var cost = costStep
        if (direction == "left") cost += leftPenalty
        if (isPyramid(row, col)) {
            if (!canDestroy) return null
            cost += costDestroy
        }
        cost += middleBias * Math.abs(row - middle)
        return cost
    }

    /**
     * The cheapest way from [start] to one of [goals].
     *
     * [goals] is a set of (row, col). Empty, the cheapest way to the
     * furthest reachable column is searched instead, which is plain
     * advancing.
     */
    fun search(start: Pair<Int, Int>, goals: Set<Pair<Int, Int>>): Route {
        val best = LinkedHashMap<Pair<Int, Int>, Double>()
        best[start] = 0.0
        val came = HashMap<Pair<Int, Int>, Pair<Pair<Int, Int>, String>>()
        // heapq's tuple order: cost, then row, then column.
        val queue = PriorityQueue<Pair<Double, Pair<Int, Int>>>(
            compareBy({ it.first }, { it.second.first }, { it.second.second }))
        queue.add(0.0 to start)
        var reached: Pair<Int, Int>? = null

        while (queue.isNotEmpty()) {
            val (cost, node) = queue.poll()
            if (cost > (best[node] ?: Double.POSITIVE_INFINITY)) continue
            if (node in goals) {
                reached = node
                break
            }
            val (row, col) = node
            for ((direction, delta) in DIRECTIONS) {
                val nrow = row + delta.first
                val ncol = col + delta.second
                if (!(nrow in 0 until rows && ncol in 0 until cols)) continue
                val extra = enterCost(nrow, ncol, direction) ?: continue
                val new = cost + extra
                val cell = nrow to ncol
                if (new < (best[cell] ?: Double.POSITIVE_INFINITY)) {
                    best[cell] = new
                    came[cell] = node to direction
                    queue.add(new to cell)
                }
            }
        }

        if (goals.isEmpty()) {
            // Advancing. The target is the furthest reachable column, and on
            // a tie the cheapest way there.
            if (best.isEmpty()) return Route()
            val far = best.keys.maxOf { it.second }
            // Python's min over the dict: the first of equals in insertion order.
            var pick: Pair<Int, Int>? = null
            var pickCost = Double.POSITIVE_INFINITY
            for ((cell, cost) in best) {
                if (cell.second != far) continue
                if (cost < pickCost) {
                    pick = cell
                    pickCost = cost
                }
            }
            reached = pick
        }

        if (reached == null || reached == start) return Route()
        return build(start, reached, came, best.getValue(reached))
    }

    private fun build(start: Pair<Int, Int>, goal: Pair<Int, Int>,
                      came: Map<Pair<Int, Int>, Pair<Pair<Int, Int>, String>>,
                      cost: Double): Route {
        val path = ArrayList<Pair<Int, Int>>()
        var node = goal
        var first: String? = null
        var destroys = 0
        while (node != start) {
            val (prev, direction) = came.getValue(node)
            path.add(node)
            if (isPyramid(node.first, node.second)) destroys += 1
            first = direction
            node = prev
        }
        path.reverse()
        return Route(first = first, cost = RouterConst.round2(cost), steps = path.size,
                     destroys = destroys, path = path)
    }
}
