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
        "R" to "ticket_pink", "K" to "claw", "T" to "paw", "F" to "fireball", "?" to "?")

    private fun grid(rows: List<String>): List<List<String?>> =
        rows.map { row -> row.split(" ").map { legend.getValue(it) } }

    private fun build(rows: List<String>, figRow: Int, figCol: Int, scroll: Int = 0): World {
        val grid = grid(rows)
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

    /**
     * The player's rule, confirmed on 2026-09-24: claws and dashes recharge,
     * a detour costs two steps of a hundred and a claw one of three, so with
     * ten claws in the bank a single pyramid is still walked around.
     */
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

    private val walledIn = listOf(". . P P P", ". . P P P", ". . P P P", ". . . . .", ". . . . .")

    /**
     * An unreadable fireball counter no longer forbids the dash (2026-09-24):
     * it reads as "there are charges", as an unreadable claw counter always
     * has. Walled in with no claws, the dash is the way forward, and the
     * log says the counter was not read.
     */
    @Test
    fun `an unreadable fireball counter does not forbid the dash when the path is blocked`() {
        val c = linkedMapOf<String, Long?>("paws" to 500L, "meters" to 100L,
                                           "fireballs" to null, "claws" to 0L)
        val act = Planner(build(walledIn, 1, 1)).nextAction(c)
        assertEquals("skill", act.kind, act.toString())
        assertTrue(act.reason.endsWith(", charges unknown"), act.reason)
    }

    /** The floor under the case above: a counter that reads 0 still forbids it. */
    @Test
    fun `fireballs read as 0 still forbid the dash`() {
        val c = linkedMapOf<String, Long?>("paws" to 500L, "meters" to 100L,
                                           "fireballs" to 0L, "claws" to 0L)
        val act = Planner(build(walledIn, 1, 1)).nextAction(c)
        assertEquals("step", act.kind, act.toString())
        assertTrue(act.reason.endsWith(", dash held: no charges"), act.reason)
    }

    /** One pyramid ahead is walked around, and the reason says why no dash came. */
    @Test
    fun `a dash kept back for its price says so`() {
        val rows = listOf(". . . . .", ". . . . .", ". . P . .", ". . . . .", ". . . . .")
        val act = Planner(build(rows, 2, 1)).nextAction(counters(fireballs = 5))
        assertEquals("step", act.kind, act.toString())
        assertTrue(act.reason.endsWith(", dash held: 1 pyramid(s), on foot 360 Bits against 375"), act.reason)
    }

    // ---- mini_dash_eager (PLAN_WORLD_SEARCH_DASH.md 4.3, version A) -------
    // Off, every case above is the proof that nothing moved: they all build
    // their Planner without the switch.

    private fun eager(rows: List<String>, c: Map<String, Long?>, on: Boolean = true) =
        Planner(build(rows, 2, 1), dashEager = on).nextAction(c)

    /** Two pyramids ahead and a ticket in the left column of another row. */
    private val twoAndTicket = listOf("G . . . .", ". . . . .", ". . P P .", ". . . . .", ". . . . .")

    @Test
    fun `off, a ticket that would scroll off holds two pyramids' dash`() {
        val act = eager(twoAndTicket, counters(fireballs = 5), on = false)
        assertTrue(act.kind != "skill", act.toString())
        assertTrue(act.reason.endsWith(", dash held: an item in another row would scroll off"), act.reason)
    }

    @Test
    fun `on, two pyramids ahead are dashed through and the reason names the ticket`() {
        val act = eager(twoAndTicket, counters(fireballs = 5))
        assertEquals("skill", act.kind, act.toString())
        assertEquals("eager: 2 pyramid(s) ahead, giving up ticket_green in r1c1", act.reason)
    }

    @Test
    fun `on, one pyramid ahead decides as it does off`() {
        val rows = listOf("G . . . .", ". . . . .", ". . P . .", ". . . . .", ". . . . .")
        val on = eager(rows, counters(fireballs = 5))
        val off = eager(rows, counters(fireballs = 5), on = false)
        assertEquals("step", on.kind, on.toString())
        assertEquals(off.toString(), on.toString())
    }

    @Test
    fun `on, no charges still hold the dash`() {
        val act = eager(twoAndTicket, counters(fireballs = 0))
        assertTrue(act.kind != "skill", act.toString())
        assertTrue(act.reason.endsWith(", dash held: no charges"), act.reason)
    }

    @Test
    fun `on, an object in the figure's own row is collected and not given up`() {
        val rows = listOf(". . . . .", ". . . . .", ". . O P P", ". . . . .", ". . . . .")
        val act = eager(rows, counters(fireballs = 5))
        assertEquals("skill", act.kind, act.toString())
        assertEquals("eager: 2 pyramid(s) ahead, nothing given up", act.reason)
    }

    @Test
    fun `on, one pyramid with the path on foot blocked is dashed through as well`() {
        // No claws and pyramids above and below it: nothing leads round.
        val rows = listOf("G . . . .", ". . P . .", ". . P . .", ". . P . .", ". . . . .")
        val c = counters(fireballs = 5, claws = 0)
        val off = eager(rows, c, on = false)
        assertTrue(off.kind != "skill", off.toString())
        val on = eager(rows, c)
        assertEquals("skill", on.kind, on.toString())
        assertEquals("eager: 1 pyramid(s) ahead, path on foot blocked, giving up ticket_green in r1c1", on.reason)
    }

    @Test
    fun `on, an unreadable counter still dashes and says so`() {
        val c = linkedMapOf<String, Long?>("paws" to 500L, "meters" to 100L, "fireballs" to null, "claws" to 10L)
        val act = eager(twoAndTicket, c)
        assertEquals("skill", act.kind, act.toString())
        assertTrue(act.reason.endsWith("giving up ticket_green in r1c1, charges unknown"), act.reason)
    }

    // ---- fetching from the left column (PLAN_WORLD_SEARCH_FORMATE.md 4.2, F1)
    // Not in test_planner.py: planner.py overflows on the first two.

    /**
     * The format tour's board as `read_grid` reads it on
     * corpus/formats/board_1080x1920_none0_225956.png: the figure in r2c2, a
     * Stamina Token in r4c1 behind the pyramids in r3c1 and r3c2, an SP
     * Training Chip beside it. The cheapest way to the token went right
     * round by column 3, which scrolls it off the board.
     */
    private val tour = listOf("? . . F .", ". . . . .", "P P . F .", "T O . P .", ". P . . .")

    @Test
    fun `the tour's board fetches the token with one claw`() {
        val w = build(tour, 1, 1)
        val act = Planner(w).nextAction(counters(claws = 3, fireballs = 3, paws = 100))
        // Down through r3c2 and left, 477 Bits; left and down through r3c1
        // costs the same, and the router's order of edges takes the first.
        assertEquals("destroy down r3c2 (destroy pyramid, 477 Bits for the whole path)", act.toString())
        assertTrue(w.unreachable.isEmpty(), "nothing given up: ${w.unreachable}")
    }

    @Test
    fun `with no claws the tour's token is given up and the planner goes on`() {
        val w = build(tour, 1, 1)
        val c = counters(claws = 0, fireballs = 3, paws = 100)
        val act = Planner(w).nextAction(c)
        assertEquals("step right r2c3 (towards ticket_orange, 4 steps, 343 Bits)", act.toString())
        assertEquals(setOf(0 to 3), w.unreachable, "the token in r4c1, given up")
        // And it stays given up: the next decision on the same board is the
        // same step, not a wait for the token (measured without the skip in
        // fetchLeft: "wait (too many unreachable targets)").
        assertEquals(act.toString(), Planner(w).nextAction(c).toString())
    }

    /**
     * Two objects in the left column, the nearer one only by column 3: the
     * safety rule fetches the other one, whose way stays in the two columns
     * -- what planner.py found as well, and the keepLeft search finds it
     * unchanged.
     */
    @Test
    fun `a way to the left column that stays on the board is still found`() {
        val rows = listOf("T . . . .", ". . . . .", "P . . . .", "T P . . .", ". . . . .")
        val act = Planner(build(rows, 2, 1)).nextAction(counters(claws = 0))
        assertEquals("step up r2c2 (fetching object in the left column, 3 steps, 264 Bits)", act.toString())
    }

    /**
     * F8, the player's rule of 2026-09-28: a thing given up holds no dash.
     * The token in r4c1 is walled off in the two columns and there are no
     * claws, so it is given up; then three pyramids ahead are dashed through
     * (680 Bits on foot against 325), where planner.py's veto -- "an item in
     * another row would scroll off" -- held the dash for that token.
     */
    @Test
    fun `a token given up does not hold the dash`() {
        val rows = listOf(". . . . .", ". . P P P", "P P . . .", "T P . . .", ". P . . .")
        val w = build(rows, 1, 1)
        val act = Planner(w).nextAction(counters(fireballs = 5, claws = 0))
        assertEquals(setOf(0 to 3), w.unreachable, "the token in r4c1, given up")
        assertEquals("skill (3 pyramids, path on foot costs 680 Bits against 325)", act.toString())
    }

    /** The other side of F8: a token that is not given up still holds it. */
    @Test
    fun `a token that can still be fetched holds the dash as before`() {
        val rows = listOf(". . . . .", ". . P P P", ". . . . .", "T . . . .", ". . . . .")
        val act = Planner(build(rows, 1, 1)).nextAction(counters(fireballs = 5, claws = 0))
        assertTrue(act.kind != "skill", act.toString())
        assertTrue(act.reason.endsWith(", dash held: an item in another row would scroll off"), act.reason)
    }

    // ---- walled in (PLAN_WORLD_SEARCH_FORMATE.md 4.2, F9) -----------------
    // Not in test_planner.py: planner.py waits on every one of them.

    /**
     * corpus/explore/more-skins-FILTER-C-2026-08-25 220501.png as `read_grid`
     * reads it, with the figure put on r5c2: pyramids left, right and above,
     * the board's edge below. With no claws the price had held the dash for
     * a detour over r4c3 the figure cannot get to -- "wait (no path found,
     * dash held: 1 pyramid(s), on foot 360 Bits against 375)", every round.
     */
    private val walledF9 = listOf("O . . P .", ". ? . . .", ". . P . .", ". P . . .", "P . P . .")

    @Test
    fun `walled in with no claws the figure dashes`() {
        val w = build(walledF9, 4, 1)
        val act = Planner(w).nextAction(counters(claws = 0, fireballs = 3, paws = 100))
        assertEquals("skill (walled in, no way on foot, skill clears it)", act.toString())
        assertEquals(setOf(0 to 0), w.unreachable, "the chip in r1c1, given up on the way")
    }

    @Test
    fun `walled in with an unread fireball counter the figure dashes and says so`() {
        val c = linkedMapOf<String, Long?>("paws" to 100L, "meters" to 100L, "fireballs" to null, "claws" to 0L)
        val act = Planner(build(walledF9, 4, 1)).nextAction(c)
        assertEquals("skill (walled in, no way on foot, skill clears it, charges unknown)", act.toString())
    }

    /**
     * With no fireballs either nothing moves the figure. It used to wait --
     * "wait (no path found, dash held: no charges)" -- and live a wait reads
     * the board again but not the counters, so a claw or a dash that
     * recharged was never seen, and the pass waited until the main switch.
     * The player's rule of 2026-09-28 (PLAN_WORLD_SEARCH_FORMATE.md F11):
     * then the bot says so. The planner calls it stuck, and the skill parks
     * on it (WorldSearchFlowTest).
     */
    @Test
    fun `walled in with no fireballs the figure is stuck`() {
        val act = Planner(build(walledF9, 4, 1)).nextAction(counters(claws = 0, fireballs = 0, paws = 100))
        assertEquals("stuck (walled in, no way on foot and no claws, dash held: no charges)", act.toString())
    }

    /**
     * The other waits are what they were (F11 changes one): seven targets
     * given up still wait at planner.py's depth limit, and it is the next
     * decision, on the same world, that says stuck.
     */
    @Test
    fun `walled in with seven targets and no fireballs waits once, then is stuck`() {
        val rows = listOf("O G R K T", "F O . . .", ". . . . .", ". P . . .", "P . P . .")
        val w = build(rows, 4, 1)
        val c = counters(claws = 0, fireballs = 0)
        assertEquals("wait (too many unreachable targets, dash held: no charges)", Planner(w).nextAction(c).toString())
        assertEquals("stuck (walled in, no way on foot and no claws, dash held: no charges)",
                     Planner(w).nextAction(c).toString())
    }

    /** With claws the rule does not come into it: a claw leads out, as before. */
    @Test
    fun `walled in with claws the figure claws its way out`() {
        val act = Planner(build(walledF9, 4, 1)).nextAction(counters(claws = 3, fireballs = 3, paws = 100))
        assertEquals("destroy up r4c2 (destroy pyramid, 639 Bits for the whole path, " +
                     "dash held: an item in another row would scroll off)", act.toString())
    }

    /**
     * Walled in is the router's answer, not the four neighbours: here the
     * figure in r2c1 can step up into r1c1, and every way out of the two
     * cells is a pyramid (the plannerProbe's synthetic#26). It stood as
     * well -- "on foot 280 Bits against 375".
     */
    @Test
    fun `a figure penned in a pocket dashes as well`() {
        val rows = listOf(". P . . .", ". P . . P", "P . . . .", ". O . G P", ". . . . .")
        val act = Planner(build(rows, 1, 0)).nextAction(counters(claws = 0, fireballs = 3))
        assertEquals("skill (walled in, no way on foot, skill clears it)", act.toString())
    }

    /**
     * Seven wanted things in view and none of them reachable: the first
     * decision gives all seven up and waits at planner.py's depth limit,
     * which F9 does not touch; the next one, on the same world, dashes.
     * Live that is one wait round before the dash.
     */
    @Test
    fun `walled in with seven targets in view the dash comes one decision later`() {
        val rows = listOf("O G R K T", "F O . . .", ". . . . .", ". P . . .", "P . P . .")
        val w = build(rows, 4, 1)
        val c = counters(claws = 0, fireballs = 3)
        val first = Planner(w).nextAction(c)
        assertEquals("wait", first.kind, first.toString())
        assertTrue(first.reason.startsWith("too many unreachable targets"), first.reason)
        assertEquals(7, w.unreachable.size, "${w.unreachable}")
        assertEquals("skill (walled in, no way on foot, skill clears it)", Planner(w).nextAction(c).toString())
    }

    // ---- the cell over the figure (PLAN_WORLD_SEARCH_FORMATE.md F16;
    // notes/world-search.md, "The cell over the figure is not read as empty")
    // Not in test_planner.py.

    /**
     * corpus/explore/board_skin10_1080x1920_none0_164502.png as `read_grid`
     * reads it: Imperialdramon FM in r5c2, and the pyramid in r4c2 behind its
     * head read as nothing (0.592 with the second look, against 0.60); the
     * wings are the two "?". The skin tour's first action there was `step up
     * r4c2`, and the game smashed the pyramid: `no_effect, claws -1`. The paw
     * is three steps away by r4c2 or by r5c3, and the first way is 2 Bits
     * cheaper on the middle bias only.
     */
    private val imperialdramon = listOf("P . . . P", ". . . . .", "P . T . P", "? . ? P .", ". . . . .")

    @Test
    fun `the unseen cell over the figure is gone round by a way of as many steps`() {
        val act = Planner(build(imperialdramon, 4, 1)).nextAction(counters(paws = 2571, claws = 37, fireballs = 1))
        assertEquals("step right r5c3 (towards paw, 3 steps, 243 Bits, round the unseen cell over the figure)",
                     act.toString())
    }

    /** The same with no claws: a way round of as many steps costs nothing, so the rule does not ask the claws. */
    @Test
    fun `with no claws the unseen cell is gone round as well`() {
        val act = Planner(build(imperialdramon, 4, 1)).nextAction(counters(claws = 0, fireballs = 1))
        assertEquals("step right r5c3 (towards paw, 3 steps, 243 Bits, round the unseen cell over the figure)",
                     act.toString())
    }

    /**
     * Never a longer way for it: round the cell to a paw two over the figure
     * is four steps, and a pyramid there only one of five cells over the
     * figure on the boards measured -- the step up stays (and a claw for a
     * pyramid there, one time in five, is cheaper by the player's prices than
     * two steps every time).
     */
    @Test
    fun `where no way of as many steps goes round it the step up stays`() {
        val rows = listOf(". . . . .", ". . . . .", ". T . . .", ". . . . .", ". . . . .")
        assertEquals("step up r4c2 (towards paw, 2 steps, 161 Bits)",
                     Planner(build(rows, 4, 1)).nextAction(counters()).toString())
    }

    /**
     * B4a's way into F16, with today's readers: the pyramid read up and right
     * of the figure, the step right out of the second column scrolls it over
     * the figure, and there its lower half is covered. Two covered looks used
     * to forget it (`World.confirmMisses`) and the next decision stepped up
     * into it -- a claw for nothing; the world keeps it now, and the way to
     * the paw goes round.
     */
    @Test
    fun `a pyramid seen before is not forgotten while the figure stands under it`() {
        val w = build(listOf(". . T . .", ". . P . .", ". . . . .", ". . . . .", ". . . . ."), 2, 1)
        w.applyStep("right")
        val covered = grid(listOf(". T . . .", ". . . . .", ". . . . .", ". . . . .", ". . . . ."))
        w.observe(covered, null)
        w.observe(covered, null)
        assertEquals("pyramid", w.at(2, 1), "still in the map after two covered looks")
        assertEquals("step right r3c3 (towards paw, 4 steps, 345 Bits)", Planner(w).nextAction(counters()).toString())
    }

    /**
     * B4a's own board, 1080 x 1920, as `read_grid` reads its two kept frames
     * today: on corpus/formats/board_1080x1920_none0_134804.png (the figure in
     * r3c2 after a claw into r3c3) the pyramid in r2c3 reads, 0.814 -- the
     * tour's reader had it as nothing, over the pyramid in r3c3 (F12). The
     * next step right scrolled it over the figure, and the step after went up
     * into it: `134812`, `no_effect, claws -1`. Here the view between the two
     * is 134812's grid with that pyramid covered and the claw's flash in r2c4
     * left out, looked at twice. Both ways to the paw are two steps through
     * a pyramid, so the claw is the router's now, on the one it prices
     * cheaper, and not a step into one it had forgotten.
     */
    @Test
    fun `B4a's pyramid over the figure is clawed on purpose or not at all`() {
        val w = build(listOf(". . P . P", "? . P T .", ". . . P .", ". . P . .", "P . . P ."), 2, 1)
        w.applyStep("right")
        val between = grid(listOf(". P . P .", "P . T . .", ". . P . .", ". P . . P", ". . P . ."))
        w.observe(between, null)
        w.observe(between, null)
        val act = Planner(w).nextAction(counters(paws = 3145, claws = 53, fireballs = 4))
        assertEquals("destroy right r3c3 (destroy pyramid, 376 Bits for the whole path, " +
                     "dash held: an item in another row would scroll off)", act.toString())
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
