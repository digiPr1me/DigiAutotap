package io.github.digipr1me.digiautotap.core

/**
 * `planner.py`, carried over line for line: decides exactly one next action.
 *
 * Ground rules, derived from measurements
 *
 *  1. Every wanted power-up is collected, however expensive
 *  2. They are worked through left to right, so steps left never arise by
 *     default
 *  3. Vertical steps do not scroll the world, so a visible object cannot be
 *     lost
 *  4. Only what sits in visible column 0 is lost when stepping right out of
 *     column 1, so exactly that step is blocked then
 *  5. Metres come only from stepping right out of column 1 and from the skill
 *
 * Costs in Bits, derived from the shop prices: 50 paws cost 2,000, one claw
 * 200, one fireball 400. Every price here is a whole number of Bits, as in
 * Python, so the arithmetic is integer arithmetic and the `%d` in a reason
 * is exact; only the router works in floats, because of its middle bias.
 */
object PlannerConst {
    /**
     * Surcharge for steps left. They are allowed when cheaper, but not free,
     * otherwise back-and-forth would result.
     */
    const val BIT_LEFT_PENALTY = 20

    /**
     * Tiny surcharge per row of distance from the middle row. Only matters
     * on a tie. The greatest distance from the middle to any row is 2, from
     * an edge row it is 4, so standing in the middle is cheaper on average.
     */
    const val BIT_MIDDLE_BIAS = 1

    // Shop prices, 50 paws cost 2,000 Bits.
    //
    // The prices are the rule, and the player confirmed it on 2026-09-24
    // when players asked why the claws lie unused: stamina, claws and dashes
    // all recharge over time (100 steps, 3 claws, 3 dashes), and a detour
    // around a pyramid costs two steps of a hundred while a claw is one of
    // three. So a claw is spent only when there is no way around, a dash
    // only when walled in or when walking clearly costs more, never on an
    // empty row, and never when a power-up in another row would scroll off.
    const val BIT_PAW = 40
    const val BIT_CLAW = 200
    const val BIT_SKILL = 400

    /**
     * Value of a saved action in Bits. Standard 40, because items per time
     * is the goal and resources can be bought again. Set to 0, only
     * resources count.
     */
    const val BIT_PER_ACTION = 40

    /**
     * Expected value of the loot under a pyramid, in Bits. It comes out
     * lean, orange 20 instead of 125 and just 1 for everything else, and it
     * does not sit under every pyramid. Deliberately small because of that.
     * It only counts when the pyramid is in the way anyway; opening pyramids
     * on the chance of loot does not pay off.
     */
    const val BIT_PYRAMID_LOOT = 25

    /**
     * Tendency to change row earlier. 0 means only change when there are
     * genuinely fewer pyramids there. Higher values change more readily.
     */
    const val ROW_SLACK = 0

    /** `planner.DELTA`. */
    val DELTA = mapOf("up" to (-1 to 0), "down" to (1 to 0),
                      "left" to (0 to -1), "right" to (0 to 1))
}

/**
 * One action the planner decided on. [kind] is step, destroy, skill, wait,
 * stop -- or "unreachable", which never leaves [Planner.nextAction] and only
 * carries a target back to it.
 *
 * [cell] is the visible (row, col) for the click, except on "unreachable",
 * where it is the (global column, row) of the target given up on -- that is
 * how `planner.py` passes it to `world.mark_unreachable`, and the port keeps
 * the same shape rather than a tidier one that would read differently.
 */
class Action(
    val kind: String,
    val direction: String? = null,
    val cell: Pair<Int, Int>? = null,
    /** Mutable, because `_unreachable` rewrites the skill's own reason. */
    var reason: String = "",
) {
    override fun toString(): String {
        val parts = ArrayList<String>()
        parts.add(kind)
        if (direction != null) parts.add(direction)
        if (cell != null) parts.add("r%dc%d".format(cell.first + 1, cell.second + 1))
        return "%s (%s)".format(parts.joinToString(" "), reason)
    }
}

class Planner(
    private val world: World,
    private val minPaws: Int = 20,
    private val targetMeters: Int = 0,
    private val bitPaw: Int = PlannerConst.BIT_PAW,
    private val bitClaw: Int = PlannerConst.BIT_CLAW,
    private val bitSkill: Int = PlannerConst.BIT_SKILL,
    private val bitPyramidLoot: Int = PlannerConst.BIT_PYRAMID_LOOT,
    @Suppress("unused") private val rowSlack: Int = PlannerConst.ROW_SLACK,
    private val bitLeftPenalty: Int = PlannerConst.BIT_LEFT_PENALTY,
    private val bitMiddleBias: Int = PlannerConst.BIT_MIDDLE_BIAS,
    private val bitPerAction: Int = PlannerConst.BIT_PER_ACTION,
) {

    /**
     * Why the last [skillWorthIt] of this decision kept the dash back
     * although a pyramid lay ahead, or null. Not a decision of its own: it
     * goes onto the reason of whatever [nextAction] chose instead, so that
     * the log says which of the rule's reasons it was (2026-09-24, "it does
     * not use the dashes").
     */
    private var dashHeld: String? = null

    // ------------------------------------------------------------------------
    fun nextAction(counters: Map<String, Long?>, depth: Int = 0): Action {
        if (depth > 0) return decide(counters, depth)
        dashHeld = null
        val act = decide(counters, 0)
        dashHeld?.let { if (act.kind != "skill") act.reason += ", dash held: $it" }
        return act
    }

    private fun decide(counters: Map<String, Long?>, depth: Int): Action {
        val w = world
        if (w.row == null || w.col == null) return Action("wait", reason = "position unknown")

        val paws = counters["paws"]
        if (paws != null && paws <= minPaws) {
            return Action("stop", reason = "paws almost empty ($paws)")
        }
        val meters = counters["meters"]
        // target_meters 0 means no limit. The 10,000 in the game is only the
        // next reward, not the end
        if (targetMeters != 0 && meters != null && meters >= targetMeters) {
            return Action("stop", reason = "metre target reached ($meters m)")
        }

        // The skill used to be checked only when no power-up at all was
        // visible. Since one is visible almost always, it practically never
        // got used. Now it is checked first, even on the way to a target.
        skillWorthIt(counters)?.let { return it }

        val targets = w.visibleWanted()
        if (targets.isNotEmpty()) {
            val act = goToTarget(targets[0], counters)
            if (act.kind == "unreachable") {
                val (gcol, row) = act.cell!!
                w.markUnreachable(gcol, row)
                if (depth < 6) return nextAction(counters, depth + 1)
                return Action("wait", reason = "too many unreachable targets")
            }
            return act
        }
        return advance(counters)
    }

    // ------------------------------------------------------------------------
    /**
     * To the next wanted power-up, via cost search.
     *
     * No fixed scheme any more for when the row is changed. The cheapest
     * path decides, and it weighs pyramids in both rows, detours, steps left
     * and the tendency towards the middle row all at once.
     */
    private fun goToTarget(target: Triple<Int, Int, String>, counters: Map<String, Long?>): Action {
        val w = world
        val (gcol, row, kind) = target
        val col = gcol - w.scrollOffset
        if (col !in 0 until Vision.COLS) return Action("wait", reason = "target lies outside the board")

        skillWorthIt(counters)?.let { return it }

        val route = router(counters).search(w.row!! to w.col!!, setOf(row to col))
        return routeAction(route, counters, "towards $kind", target = gcol to row)
    }

    /**
     * No power-up visible, so make progress.
     *
     * Target is the furthest reachable column. That way the search dodges
     * pyramids by itself and, on a tie, prefers the middle row, because from
     * there the paths to the next object are shorter on average.
     */
    private fun advance(counters: Map<String, Long?>): Action {
        skillWorthIt(counters)?.let { return it }
        val w = world
        val route = router(counters).search(w.row!! to w.col!!, emptySet())
        return routeAction(route, counters, "making progress")
    }

    /**
     * Cost search over the visible board.
     *
     * The prices are the same as everywhere, paw plus action value per step,
     * and claw plus action minus expected loot for entering a pyramid cell.
     * That way a calculation decides whether to detour or destroy, instead
     * of an individual rule.
     */
    private fun router(counters: Map<String, Long?>): Router {
        val w = world
        val claws = counters["claws"]
        return Router(
            isPyramid = { row, col -> w.isPyramid(w.scrollOffset + col, row) },
            costStep = (bitPaw + bitPerAction).toDouble(),
            costDestroy = (bitClaw + bitPerAction - bitPyramidLoot).toDouble(),
            leftPenalty = bitLeftPenalty.toDouble(),
            middleBias = bitMiddleBias.toDouble(),
            canDestroy = claws == null || claws > 0)
    }

    /** Turn the first action of a found route into an [Action]. */
    private fun routeAction(route: Route, counters: Map<String, Long?>, reason: String,
                            target: Pair<Int, Int>? = null): Action {
        val w = world
        if (!route.reachable || route.first == null) return unreachable(counters, target)

        val direction = route.first
        val (dr, dc) = PlannerConst.DELTA.getValue(direction)
        val row = w.row!! + dr
        val col = w.col!! + dc
        val gcol = w.scrollOffset + col

        // Safety rule. A step right out of the second column scrolls and
        // would lose everything in the left visible column.
        if (direction == "right" && w.col!! >= WorldConst.FIG_COL_MAX) {
            if (wouldLoseItems(1)) return fetchLeft(counters)
        }

        if (w.isPyramid(gcol, row)) {
            return Action("destroy", direction, row to col,
                          "destroy pyramid, %d Bits for the whole path".format(route.cost!!.toInt()))
        }
        var detail = "%s, %d steps, %d Bits".format(reason, route.steps, route.cost!!.toInt())
        if (route.destroys != 0) detail += ", %d of them pyramid(s)".format(route.destroys)
        return Action("step", direction, row to col, detail)
    }

    /**
     * Something wanted sits in the left visible column and would be lost on
     * the next scroll. Fetch it first.
     */
    private fun fetchLeft(counters: Map<String, Long?>): Action {
        val w = world
        val leftGcol = w.scrollOffset
        for (row in 0 until Vision.ROWS) {
            if (!w.isWanted(leftGcol, row)) continue
            val route = router(counters).search(w.row!! to w.col!!, setOf(row to 0))
            return routeAction(route, counters, "fetching object in the left column",
                               target = leftGcol to row)
        }
        // Only reached if wouldLoseItems(1) and isWanted disagreed about the
        // left column, which they cannot: both ask world.isWanted. Kept as
        // planner.py has it.
        return Action("wait", reason = "safety rule with no target")
    }

    /** No path found. Check the skill first, then give up on the target. */
    private fun unreachable(counters: Map<String, Long?>, target: Pair<Int, Int>?): Action {
        val skill = skillWorthIt(counters, minPyramids = 1)
        if (skill != null) {
            skill.reason = "no path free, skill clears it" +
                (if (counters["fireballs"] == null) ", charges unknown" else "")
            return skill
        }
        if (target != null) {
            return Action("unreachable", cell = target, reason = "no path, no claws and no skill")
        }
        return Action("wait", reason = "no path found")
    }

    /**
     * What it costs to pass this cell, in Bits, and how many actions that
     * is. Without a pyramid, just the step. With a pyramid, either one claw
     * or a detour via the neighbouring row, whichever is cheaper counts.
     * (null, null) where the cell cannot be passed at all.
     */
    private fun cellCost(gcol: Int, row: Int, counters: Map<String, Long?>): Pair<Int?, Int?> {
        val cost = bitPaw
        if (!world.isPyramid(gcol, row)) return cost to 1
        val claws = counters["claws"]
        val options = ArrayList<Pair<Int, Int>>()
        if (claws == null || claws > 0) {
            // one claw plus one action, in exchange for the loot underneath
            options.add((bitClaw - bitPyramidLoot) to 1)
        }
        if (rowFreeAround(gcol, row)) {
            options.add((2 * bitPaw) to 2)  // up and back down
        }
        if (options.isEmpty()) return null to null  // not passable
        // comparison in one currency, Bits plus valued actions. Python's min
        // gives the first of equals, so a plain scan with `<` matches it.
        var best = options[0]
        var bestKey = best.first + best.second * bitPerAction
        for (o in options.drop(1)) {
            val key = o.first + o.second * bitPerAction
            if (key < bestKey) { best = o; bestKey = key }
        }
        return (cost + best.first) to (1 + best.second)
    }

    /**
     * Would scrolling by this many columns push a wanted object off the left
     * edge of the board?
     *
     * For a step right, [scroll] is 1; for the skill, 2 or 3. Objects in row
     * [keepRow] are collected by the skill and do not count.
     */
    private fun wouldLoseItems(scroll: Int, keepRow: Int? = null): Boolean {
        val w = world
        for (col in 0 until scroll) {
            val gcol = w.scrollOffset + col
            for (row in 0 until Vision.ROWS) {
                if (keepRow != null && row == keepRow) continue
                if (w.isWanted(gcol, row)) return true
            }
        }
        return false
    }

    private fun rowFreeAround(gcol: Int, row: Int): Boolean {
        for (other in listOf(row - 1, row + 1)) {
            if (other !in 0 until Vision.ROWS) continue
            if (!world.isPyramid(gcol, other)) return true
        }
        return false
    }

    /**
     * The skill pays off when walking would cost more than it does.
     *
     * It flies over cells without collecting, so never over a wanted
     * power-up. It is compared against the cheapest path on foot over the
     * same columns, i.e. steps plus, per pyramid, either one claw or a
     * detour.
     */
    private fun skillWorthIt(counters: Map<String, Long?>, minPyramids: Int? = null): Action? {
        val w = world
        val charges = counters["fireballs"]
        val span = if (w.col!! >= WorldConst.FIG_COL_MAX) 3 else 2
        val cells = (1..span).map { (w.figGcol!! + it) to w.row!! }
        val pyramids = cells.count { (gcol, row) -> w.isPyramid(gcol, row) }
        // What was said about a dash kept back, only where a pyramid lay
        // ahead: on an empty row there is nothing to explain.
        fun held(why: String): Action? {
            if (pyramids > 0) dashHeld = why
            return null
        }
        // Only a counter that reads 0 forbids the dash. planner.py forbade it
        // on an unreadable one too, while an unreadable claw counter reads
        // as "there are claws" (router, cellCost) -- and "never dash" is the
        // fault a player reported on 2026-09-24, should the counter not read
        // on a phone. A dash with no charge left costs one Insufficient
        // banner, and runLoop sets the counter to 0 after it: a cheap error
        // that corrects itself.
        if (charges == 0L) return held("no charges")
        // The skill also picks up from the ground, so it is allowed to fly
        // over items. But it scrolls by span columns, and that pushes
        // objects off the left edge of the board. In its own row they get
        // collected; in every other row they would be lost.
        if (wouldLoseItems(span, keepRow = w.row)) return held("an item in another row would scroll off")
        if (pyramids < (minPyramids ?: 1)) return null
        val unknown = if (charges == null) ", charges unknown" else ""

        var walkBits: Int? = 0
        var walkActs = 0
        for ((gcol, row) in cells) {
            val (bits, acts) = cellCost(gcol, row, counters)
            if (bits == null) {
                walkBits = null
                break
            }
            walkBits = walkBits!! + bits
            walkActs += acts!!
        }
        if (walkBits == null) {
            return Action("skill", reason = "path on foot blocked, %d pyramids%s".format(pyramids, unknown))
        }

        // The fireball collects whatever it uncovers under the pyramids
        val skillBits = bitSkill - pyramids * bitPyramidLoot
        // The skill is one action, walking is several. Anyone who values
        // time sets bit_per_action higher
        val walk = walkBits + maxOf(0, walkActs - 1) * bitPerAction
        if (walk <= skillBits) {
            return held("%d pyramid(s), on foot %d Bits against %d".format(pyramids, walk, skillBits))
        }
        return Action("skill",
                      reason = "%d pyramids, path on foot costs %d Bits against %d%s"
                          .format(pyramids, walk, skillBits, unknown))
    }
}
