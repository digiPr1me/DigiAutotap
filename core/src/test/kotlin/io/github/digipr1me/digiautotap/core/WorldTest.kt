package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `test_world.py`, case for case: the world model offline, above all the
 * scrolling. Same boards, same order, same answers -- every assertion below
 * is one line of that script's `ok` list, under its own name.
 */
class WorldTest {

    private fun empty(): List<List<String?>> =
        List(Vision.ROWS) { List<String?>(Vision.COLS) { null } }

    private fun grid(vararg cells: Triple<Int, Int, String>): List<List<String?>> {
        val g = List(Vision.ROWS) { arrayOfNulls<String>(Vision.COLS) }
        for ((r, c, name) in cells) g[r][c] = name
        return g.map { it.toList() }
    }

    private fun fig(row: Int, col: Int) = Figure(row, col, "t", null)

    @Test
    fun `an object needs two sightings, and a phantom never gets in`() {
        val w = World()
        val g = grid(Triple(2, 3, "ticket_orange"))
        w.observe(g, fig(2, 1))
        assertNull(w.at(3, 2), "one sighting is not enough")
        w.observe(g, fig(2, 1))
        assertEquals("ticket_orange", w.at(3, 2), "two sightings confirm")

        // A phantom out of an animation frame must not get into the map.
        val phantom = grid(Triple(0, 0, "ticket_orange"), Triple(2, 3, "ticket_orange"))
        w.observe(phantom, fig(2, 1))
        assertNull(w.at(0, 0), "phantom after one frame ignored")
        w.observe(g, fig(2, 1))
        assertNull(w.at(0, 0), "phantom disappears again")
        assertEquals("ticket_orange", w.at(3, 2), "real ticket stays")

        // A single frame without the ticket must not delete it.
        w.observe(empty(), fig(2, 1))
        assertEquals("ticket_orange", w.at(3, 2), "one bad frame does not delete")
        w.observe(empty(), fig(2, 1))
        assertNull(w.at(3, 2), "two bad frames delete")
    }

    @Test
    fun `only a step right out of the second column scrolls the world`() {
        val w = World()
        val g = grid(Triple(2, 3, "ticket_orange"))
        w.observe(g, fig(2, 1))
        w.observe(g, fig(2, 1))

        // A step right out of column 0: the figure walks, the world stays.
        w.col = 0
        w.applyStep("right")
        assertTrue(w.col == 1 && w.scrollOffset == 0, "column 0 to the right, no scroll")

        // A step right out of column 1: the world scrolls.
        w.applyStep("right")
        assertTrue(w.col == 1 && w.scrollOffset == 1, "column 1 to the right, scroll")
        assertEquals(2, w.toVisible(3), "ticket now in visible column 2")

        // After the scroll the ticket has to lie at visible column 2.
        val g2 = grid(Triple(2, 2, "ticket_orange"))
        w.observe(g2, fig(2, 1))
        w.observe(g2, fig(2, 1))
        assertEquals("ticket_orange", w.at(3, 2), "ticket unchanged globally after scroll")

        // A vertical step does not scroll.
        val before = w.scrollOffset
        w.applyStep("down")
        assertTrue(w.scrollOffset == before && w.row == 3, "vertical does not scroll")
    }

    @Test
    fun `the skill gives 3 from column 1 and 2 from column 0`() {
        val w = World()
        w.row = 2
        w.col = 1
        var s = w.scrollOffset
        var gain = w.applySkill()
        assertTrue(gain == 3 && w.scrollOffset == s + 3 && w.col == 1,
                   "skill from column 1 gives 3")
        w.col = 0
        s = w.scrollOffset
        gain = w.applySkill()
        assertTrue(gain == 2 && w.scrollOffset == s + 2 && w.col == 1,
                   "skill from column 0 gives 2")
    }

    @Test
    fun `the rows are a hard boundary`() {
        val w = World()
        w.col = 1
        w.row = 0
        w.applyStep("up")
        assertEquals(0, w.row, "row 1 is a hard boundary")
        w.row = Vision.ROWS - 1
        w.applyStep("down")
        assertEquals(Vision.ROWS - 1, w.row, "row 5 is a hard boundary")
    }

    @Test
    fun `a collected object does not come back`() {
        val w = World()
        val g = grid(Triple(1, 2, "ticket_green"))
        w.observe(g, fig(1, 1))
        w.observe(g, fig(1, 1))
        w.markCollected(2, 1)
        w.observe(g, fig(1, 1))
        assertNull(w.at(2, 1), "collected items stay gone")
    }

    /**
     * The names shown on the pages. They are meant to be edited, and the
     * keys beside them are template filenames that are not -- so this checks
     * that a rename stayed on the right side of that line: an entry for
     * every item, no entry for anything that is not one (a mistyped key
     * would silently show the fallback instead), and no empty name, which
     * would draw a tick box with nothing beside it.
     */
    @Test
    fun `every item has a name and nothing else has one`() {
        val labels = WorldConst.WANTED_LABEL
        assertTrue(WorldConst.ALL_WANTED.all { it in labels }, "every item has a name")
        assertTrue(labels.keys.all { it in WorldConst.ALL_WANTED },
                   "no name for something that is not an item")
        assertTrue(labels.values.all { it.trim().isNotEmpty() }, "no name is empty")
        assertEquals("ticket blue", WorldConst.wantedLabel("ticket_blue"),
                     "an item with no entry falls back to its key")
    }
}
