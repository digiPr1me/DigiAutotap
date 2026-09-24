package io.github.digipr1me.digiautotap.core

/**
 * `world.py`, carried over line for line: the world model.
 *
 * Core idea: the board only scrolls when stepping right out of the second
 * column. Every cell is therefore tracked in global coordinates, otherwise
 * pyramids and power-ups would drop out of the bookkeeping when scrolling.
 *
 *   global column = scroll_offset + visible column
 *
 * The figure lives only in visible columns 0 and 1. Stepping right out of
 * column 1 scrolls the world and yields exactly one metre; stepping right
 * out of column 0 only moves the figure.
 */
object WorldConst {
    /**
     * Power-up types, each one switchable on its own.
     *
     * These are identifiers, not labels. Every one of them is the name of a
     * shipped template file on disk, `templates/ticket_orange.png`, which
     * `Vision.loadTemplates` looks up by exactly this string -- and they are
     * also the `want_<key>` settings saved in everybody's settings file.
     * Renaming one here orphans the matching template and the saved list, so
     * it is the one thing in this file not to do.
     */
    val ALL_WANTED = listOf("ticket_orange", "ticket_green", "ticket_pink", "claw", "paw", "fireball")

    /**
     * What a player sees instead. This is the place to rename an item: the
     * pages read it and nothing on disk knows about it. An item with no
     * entry falls back to its key with the underscores opened out.
     */
    val WANTED_LABEL = mapOf(
        "ticket_orange" to "SP Training Chip",
        "ticket_green" to "Skill Card Summon Ticket",
        "ticket_pink" to "Support Summon Ticket",
        "claw" to "Attack Token",
        "paw" to "Stamina Token",
        "fireball" to "Dash Token",
    )

    /** The name to show for a board item. */
    fun wantedLabel(key: String): String = WANTED_LABEL[key] ?: key.replace("_", " ")

    /** The figure can only stand in visible column 0 or 1. */
    const val FIG_COL_MAX = 1
}

/**
 * The map the skill keeps of the board: where the figure is, what stands
 * where in global coordinates, and what has already been collected.
 *
 * A single picture is not proof. During the collecting animation a ticket
 * icon flies across the board and would otherwise produce phantom objects.
 * So an object has to turn up in two observations running, and it only goes
 * away after two observations without it.
 */
class World(isSelected: Collection<String>? = null) {

    val isSelected: MutableSet<String> =
        (isSelected ?: WorldConst.ALL_WANTED).toMutableSet()

    var scrollOffset: Int = 0
    /** The figure's row, or null before it has been found. */
    var row: Int? = null
    /** The figure's visible column, 0 or 1, or null. */
    var col: Int? = null

    /** (global column, row) -> object name, confirmed. */
    val cells = LinkedHashMap<Pair<Int, Int>, String>()
    val collected = LinkedHashSet<Pair<Int, Int>>()
    var lastGrid: List<List<String?>>? = null
        private set

    val confirmHits = 2
    val confirmMisses = 2
    private val hits = HashMap<Pair<Int, Int>, Int>()
    private val misses = HashMap<Pair<Int, Int>, Int>()

    /**
     * Objects unreachable without claws and without the skill. They are
     * skipped rather than nailing the bot to the spot.
     */
    val unreachable = LinkedHashSet<Pair<Int, Int>>()

    // ------------------------------------------------------------------------
    // Coordinates
    // ------------------------------------------------------------------------
    fun toGlobal(col: Int): Int = scrollOffset + col

    fun toVisible(gcol: Int): Int = gcol - scrollOffset

    val figGcol: Int? get() = col?.let { toGlobal(it) }

    // ------------------------------------------------------------------------
    // Carrying the state forward
    // ------------------------------------------------------------------------
    /** Carry the position forward after a confirmed step. */
    fun applyStep(direction: String) {
        when (direction) {
            "up" -> row = row!! - 1
            "down" -> row = row!! + 1
            "left" -> col = col!! - 1
            "right" -> if (col!! < WorldConst.FIG_COL_MAX) col = col!! + 1 else scrollOffset += 1
        }
        row = maxOf(0, minOf(Vision.ROWS - 1, row!!))
        col = maxOf(0, minOf(WorldConst.FIG_COL_MAX, col!!))
    }

    /**
     * The skill always ends in column 1. From column 0 the world scrolls by
     * 2, from column 1 by 3. Returns the gain.
     */
    fun applySkill(): Int {
        val gain = if (col!! >= WorldConst.FIG_COL_MAX) 3 else 2
        scrollOffset += gain
        col = WorldConst.FIG_COL_MAX
        return gain
    }

    fun markCollected(gcol: Int, row: Int) {
        val key = gcol to row
        collected.add(key)
        cells.remove(key)
        hits.remove(key)
    }

    fun markUnreachable(gcol: Int, row: Int) {
        unreachable.add(gcol to row)
    }

    /** Forget an object at once, a destroyed pyramid for instance. */
    fun forget(gcol: Int, row: Int) {
        val key = gcol to row
        cells.remove(key)
        hits.remove(key)
    }

    // ------------------------------------------------------------------------
    // Updating the map
    // ------------------------------------------------------------------------
    /**
     * Take the visible grid into the global map.
     *
     * An object is only taken over after two sightings running and only
     * dropped after two misses running. That filters out the animation
     * frames in which a collected ticket flies across the board.
     */
    fun observe(grid: List<List<String?>>, figure: Figure? = null) {
        if (figure != null) {
            row = figure.row
            col = minOf(figure.col, WorldConst.FIG_COL_MAX)
        }

        for (c in 0 until Vision.COLS) {
            val gcol = toGlobal(c)
            for (r in 0 until Vision.ROWS) {
                val cell = grid[r][c]
                val key = gcol to r

                // The figure's cell is skipped. The figure is colourful
                // itself and would otherwise be reported as an unknown
                // object, and something can be hidden underneath it too.
                if (cell == "figure" || (r == row && c == col)) continue
                if (key in collected) continue

                if (cell == null) {
                    hits.remove(key)
                    if (key in cells) {
                        misses[key] = (misses[key] ?: 0) + 1
                        if (misses.getValue(key) >= confirmMisses) {
                            cells.remove(key)
                            misses.remove(key)
                        }
                    }
                    continue
                }

                misses.remove(key)
                if (cells[key] == cell) continue
                hits[key] = (hits[key] ?: 0) + 1
                if (hits.getValue(key) >= confirmHits) {
                    cells[key] = cell
                    hits.remove(key)
                }
            }
        }
        lastGrid = grid
    }

    // ------------------------------------------------------------------------
    // Questions
    // ------------------------------------------------------------------------
    fun at(gcol: Int, row: Int): String? = cells[gcol to row]

    fun isPyramid(gcol: Int, row: Int): Boolean = at(gcol, row) == "pyramid"

    fun isWanted(gcol: Int, row: Int): Boolean = at(gcol, row) in isSelected

    /**
     * Every wanted power-up in the visible area, sorted by global column.
     * That works them left to right, and steps left do not arise in the
     * normal case. Python's sort is stable, so equal keys keep the order
     * they were found in -- column by column, row by row.
     */
    fun visibleWanted(): List<Triple<Int, Int, String>> {
        val out = ArrayList<Triple<Int, Int, String>>()
        for (c in 0 until Vision.COLS) {
            val gcol = toGlobal(c)
            for (r in 0 until Vision.ROWS) {
                if (isWanted(gcol, r) && (gcol to r) !in unreachable) {
                    out.add(Triple(gcol, r, cells.getValue(gcol to r)))
                }
            }
        }
        val here = row ?: 0
        // sortedBy is stable, as Python's list.sort is.
        return out.sortedWith(compareBy({ it.first }, { Math.abs(it.second - here) }))
    }

    fun describe(): String = "row %s column %s (global %s) scroll %d".format(
        row?.plus(1)?.toString() ?: "None",
        col?.plus(1)?.toString() ?: "None",
        figGcol?.toString() ?: "None",
        scrollOffset)
}
