package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `test_planner.py`, case for case: the planner offline, no emulator needed.
 * Boards are built from the same text, the counters are the same, and the
 * chosen action is compared against the same expectation -- "step right"
 * matching as a prefix, as that script's `got.startswith(expect)` does.
 */
class PlannerTest {

    private val legend = mapOf(
        "." to null, "P" to "pyramid", "O" to "ticket_orange", "G" to "ticket_green",
        "R" to "ticket_pink", "K" to "claw", "T" to "paw", "F" to "fireball")

    private fun build(rows: List<String>, figRow: Int, figCol: Int, scroll: Int = 0): World {
        val grid = rows.map { row -> row.split(" ").map { legend.getValue(it) } }
        val w = World()
        w.scrollOffset = scroll
        // twice, because an object needs two sightings to be confirmed
        val figure = Figure(figRow, figCol, "test", null)
        w.observe(grid, figure)
        w.observe(grid, figure)
        return w
    }

    private fun counters(paws: Long = 500, meters: Long = 100, fireballs: Long = 0,
                         claws: Long = 10): Map<String, Long?> =
        linkedMapOf("paws" to paws, "meters" to meters, "fireballs" to fireballs, "claws" to claws)

    /** `("%s %s" % (act.kind, act.direction or "")).strip()`. */
    private fun spell(act: Action): String =
        ("${act.kind} ${act.direction ?: ""}").trim()

    private fun check(name: String, rows: List<String>, figRow: Int, figCol: Int,
                      counters: Map<String, Long?>, expect: String) {
        val w = build(rows, figRow, figCol)
        val act = Planner(w).nextAction(counters)
        val got = spell(act)
        assertTrue(got.startsWith(expect),
                   "$name -> $got (${act.reason}), expected $expect")
    }

    private val emptyBoard = listOf(". . . . .", ". . . . .", ". . . . .", ". . . . .", ". . . . .")

    @Test
    fun `an empty board is walked to the right`() {
        check("empty board, figure in column 2", emptyBoard, 2, 1,
              counters(fireballs = 10), "step right")
    }

    @Test
    fun `a ticket in the same row to the right is walked to`() {
        check("ticket same row, to the right", listOf(
            ". . . . .", ". . . . .", ". . . O .", ". . . . .", ". . . . ."),
            2, 1, counters(fireballs = 10), "step right")
    }

    @Test
    fun `a ticket one column right in another row is postponed`() {
        check("ticket one column right, other row, postponed", listOf(
            ". . . . .", ". . . . .", ". . . . .", ". . O . .", ". . . . ."),
            2, 1, counters(fireballs = 10), "step right")
    }

    @Test
    fun `a pyramid in the way with a clear neighbour row is gone around`() {
        check("pyramid in the way, neighbour row clear, go around", listOf(
            ". . . . .", ". . . . .", ". . P . .", ". . . . .", ". . . . ."),
            2, 1, counters(), "step")
    }

    @Test
    fun `a pyramid on the vertical path sends the figure right first`() {
        // Going right first and then vertically is cheaper than destroying it.
        check("pyramid on the vertical path, go right first", listOf(
            ". . . . .", ". . . . .", ". . . . .", ". P . . .", ". . O . ."),
            2, 1, counters(), "step right")
    }

    @Test
    fun `a target row full of pyramids is approached in the clear row`() {
        check("target row full of pyramids, continue in the clear row", listOf(
            ". . . . .", ". . . . .", ". . . . .", ". P P P O", ". . . . ."),
            2, 1, counters(), "step right")
    }

    @Test
    fun `a current row full of pyramids is left at once`() {
        check("current row full of pyramids, change now", listOf(
            ". . . . .", ". . . . .", ". . P P P", ". . . . O", ". . . . ."),
            2, 1, counters(), "step down")
    }

    @Test
    fun `three pyramids make the fireball pay despite a clear row`() {
        // With a time value, and because the fireball also picks up the loot
        // underneath, it pays off here despite a clear detour row.
        check("three pyramids, fireball pays despite a clear row", listOf(
            ". . . . .", ". . . . .", ". . P P P", ". . . . .", ". . . . ."),
            2, 1, counters(fireballs = 5), "skill")
    }

    @Test
    fun `a target far to the right postpones the row change`() {
        check("target far right, row change postponed", listOf(
            ". . . . .", ". . . . .", ". . . . .", ". . . . O", ". . . . ."),
            2, 1, counters(), "step right")
    }

    @Test
    fun `dodging picks the direction towards the middle`() {
        check("dodging picks the direction towards the middle", listOf(
            ". . . . .", ". . P . .", ". . . . .", ". . . . .", ". . . . ."),
            1, 1, counters(claws = 0), "step down")
    }

    @Test
    fun `three pyramids with no way around make the skill pay`() {
        // Without a detour row only the claw is left, three claws cost 600
        // Bits. Then the fireball pays off.
        check("three pyramids, no way around, skill pays", listOf(
            ". . P P P", ". . P P P", ". . P P P", ". . . . .", ". . . . ."),
            1, 1, counters(fireballs = 5), "skill")
    }

    @Test
    fun `an object in the left column blocks the step right`() {
        check("object in the left column, step right blocked", listOf(
            ". . . . .", ". . . . .", "O . . . .", ". . . . .", ". . . . ."),
            2, 1, counters(), "step left")
    }

    @Test
    fun `almost empty paws stop the run`() {
        check("paws almost empty", emptyBoard, 2, 1, counters(paws = 5), "stop")
    }

    @Test
    fun `a high metre count with no limit set keeps going`() {
        check("high metre count, no limit set", emptyBoard, 2, 1,
              counters(meters = 98000), "step right")
    }

    @Test
    fun `a ticket behind a pyramid with no claws is skipped`() {
        check("no claws, ticket behind a pyramid, skipped", listOf(
            ". . . . .", ". . . . .", ". . . . .", ". P . . .", ". . O . ."),
            2, 1, counters(fireballs = 5, claws = 0), "step right")
    }

    @Test
    fun `with no claws a pyramid straight ahead is gone around`() {
        check("claws empty, pyramid directly right, go around instead of clicking", listOf(
            ". . . . .", ". . . . .", ". . P . .", ". . . . .", ". . . . ."),
            2, 1, counters(claws = 0), "step")
    }

    @Test
    fun `an edge row is left early when the target is far away`() {
        // Figure in edge row 1, target in edge row 5. Both paths have 5
        // steps, but the search leaves the edge row earlier because the
        // middle surcharge sums the row distance along the whole path, 6
        // against 8. From the middle, the paths to the next object are
        // shorter on average.
        check("leave the edge row when the target is far away", listOf(
            ". . . . .", ". . . . .", ". . . . .", ". . . . .", ". . R . ."),
            0, 1, counters(), "step down")
    }

    @Test
    fun `a centred figure postpones the row change`() {
        // Cross-check. If the figure already stands centred, it stays in the
        // row and only changes at the target column, here 0 against 1 on the
        // middle surcharge.
        check("stay centred and postpone the row change", listOf(
            ". . . . .", ". . . . .", ". . . . .", ". . . . O", ". . . . ."),
            2, 1, counters(), "step right")
    }

    @Test
    fun `a ticket in the same column is walked to vertically`() {
        check("ticket in the same column, go vertical now", listOf(
            ". . . . .", ". . . . .", ". . . . .", ". . . . .", ". R . . ."),
            0, 1, counters(), "step down")
    }

    /**
     * `test_planner.time_cases`: how the time value tips the skill decision.
     * The script prints the four lines; here they are the assertion, because
     * a value of time that stopped counting would still print.
     */
    @Test
    fun `the value of a saved action tips the skill decision`() {
        val rows = listOf(". . . . .", ". . . . .", ". . P P P", ". . . . .", ". . . . .")
        val c = counters(fireballs = 5)
        val kinds = listOf(0, 20, 40, 80).map { bpa ->
            bpa to Planner(build(rows, 2, 1), bitPerAction = bpa).nextAction(c).kind
        }
        // Each of the three pyramids is cheaper gone around than clawed --
        // 80 Bits of detour against 175 -- so walking costs 3 x (40 + 80) =
        // 360 Bits in resources against the skill's 400 - 75 = 325. The
        // skill already wins at a time value of 0, and every further step
        // adds 8 x the value of an action to the walk, so it keeps winning.
        // The script prints this table rather than deciding anything by it.
        assertEquals(listOf("skill", "skill", "skill", "skill"), kinds.map { it.second },
                     "the skill's verdict across the four time values: $kinds")
    }
}
