package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Digivice bar in the headroom, under the dot at the top of the
 * player's strip (PLAN_FORMATE.md V14). LDPlayer at 1080 x 2520 without a
 * cutout: the HUD's canvas starts 180 rows down, the Digivice page stands at
 * the top of those rows (Preset.Place.anchor), its bar at display rows 168
 * to 248, and a dot the player keeps at fy 0.06 of the HUD stands at 193 to
 * 319 with its plate at 232 to 279 -- on the bar, and the page reads
 * `unknown`. PresetSkill played on the three whole frames of 2026-09-27
 * (`*_1080x2520_none0_*`), the dot and plate masked into every frame where
 * the app draws them and moved as `Overlay.clearOf` moves them
 * ([Dot.clearFy]).
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PresetHeadroomFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val frames = HashMap<String, Mat>()
    private val w = 1080
    private val h = 2520
    /** Where the HUD's canvas starts: `DigiAutotapService.canvasTop`, 2520 - 1080 * 13/6. */
    private val top = 180

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val dir = File(repo, "${OracleFamilies.CORPUS}/formats")
        for (screen in listOf("main", "preset_compact", "preset_compact_list")) {
            val f = dir.listFiles()!!.filter { it.name.startsWith("${screen}_1080x2520_none0_") }.minBy { it.name }
            frames[screen] = OracleFamilies.read(f)
        }
    }

    @AfterAll
    fun release() = frames.values.forEach(Mat::release)

    /** The dot and plate where the app draws them on this display, in its rows. */
    private fun boxes(fy: Double): List<Dot.Box> =
        listOf(Dot.square(w, h - top, false, fy), Dot.plate(w, h - top, false, fy))
            .map { Dot.Box(it.x, it.y + top, it.w, it.h) }

    private fun masked(screen: String, fy: Double): Mat {
        val img = frames.getValue(screen)
        return Dungeon.CanvasFrame(Dungeon.headroom(img), Dungeon.above(img)).also {
            img.copyTo(it)
            Dot.mask(it, boxes(fy))
        }
    }

    private class Edge(val from: String, val to: String, val target: Explore.Target)

    private inner class World(private val edges: List<Edge>, home: Double, private val clears: Boolean) : Capture {
        var at = "main"
        var home = home
        var dotFy = home
        val taps = ArrayList<String>()
        val overlayCalls = ArrayList<String>()
        var strays = 0

        override fun grab(): Mat = masked(at, dotFy)
        override fun tap(x: Int, y: Int) {
            val img = frames.getValue(at)
            val e = edges.firstOrNull { e ->
                if (e.from != at) return@firstOrNull false
                val r = Dungeon.gameRect(img, e.target.anchor)
                abs(r.x0 + e.target.fx * r.gw - x) <= TOL * r.gw && abs(r.y0 + e.target.fy * r.gh - y) <= TOL * r.gh
            }
            if (e == null) {
                strays += 1
                taps += "stray $x,$y on $at"
                return
            }
            taps += "${e.from} -> ${e.to}"
            at = e.to
        }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun back() {}
        override fun inFront(): String? = "game"
        override fun overlayClear(fy0: Double, fy1: Double) {
            overlayCalls += "clear %.4f-%.4f".format(fy0, fy1)
            if (!clears) return
            Dot.clearFy(dotFy, fy0, fy1, w, h, top)?.let { dotFy = it; overlayCalls += "moved to %.4f".format(it) }
        }
        override fun overlayBack() {
            overlayCalls += "back"
            dotFy = home
        }
    }

    companion object {
        const val TOL = 0.03
    }

    private fun edges(): List<Edge> {
        val closed = frames.getValue("preset_compact")
        val bar = Preset.place(closed)!!.second
        val x = Summon.exitButton(closed)!!
        return listOf(
            Edge("main", "preset_compact", PresetSkill.DIGIVICE_ICON),
            Edge("preset_compact", "preset_compact_list", bar.arrow),
            Edge("preset_compact_list", "preset_compact", bar.arrow),
            Edge("preset_compact", "main", Explore.Target(x.fx, x.fy)))
    }

    /** The slot the bar shows: the row whose text is the bar's, on the open list. */
    private fun shownSlot(): Int {
        val open = frames.getValue("preset_compact_list")
        val bar = Preset.place(open)!!.second
        val rows = Preset.compactRows(open, bar)!!
        val barText = Preset.barText(open, bar)
        val slot = rows.indexOfFirst { r -> Preset.rowText(open, bar, r).let { t -> Preset.isSameText(barText, t).also { t?.release() } } } + 1
        barText?.release()
        assertTrue(slot >= 1, "the bar's text is one of the rows")
        return slot
    }

    private fun skill(world: World, slot: Int, log: MutableList<String>) =
        PresetSkill(world, { PresetSkill.Settings(mapOf(Preset.Place.DIGIVICE to slot)) },
                    log = { log += it }, on = { true }, sleep = {})

    @Test
    fun `the dot at 0_06 stands on the Digivice bar in the headroom`() {
        assertEquals(Director.PRESET, Director.classify(frames.getValue("preset_compact")).screen)
        val (a, b) = Dot.rowsAt(0.06, w, h, top)
        println("dot at 0.06: rows $a to $b")
        for (screen in listOf("preset_compact", "preset_compact_list")) {
            val m = masked(screen, 0.06)
            assertNull(Preset.place(m), "$screen: the bar under the dot")
            assertEquals(Director.UNKNOWN, Director.classify(m).screen, screen)
            m.release()
        }
        // And where Dot.clearFy puts it for the bar and the list: just above
        // the bar, in the headroom, where every reader of the page reads as
        // unmasked.
        val bar = Preset.Place.DIGIVICE
        val gh = Dungeon.gameRect(frames.getValue("main")).gh
        val fy0 = Dungeon.bottomFy(bar.barFy0, bar.anchor, 180, gh)
        val fy1 = Dungeon.bottomFy(bar.barFy0 + PresetSkill.COMPACT_SPAN, bar.anchor, 180, gh)
        val to = assertNotNull(Dot.clearFy(0.06, fy0, fy1, w, h, top))
        val (c, d) = Dot.rowsAt(to, w, h, top)
        println("cleared: fy %.4f, rows $c to $d (the rows %.4f to %.4f)".format(to, fy0, fy1))
        assertTrue(to < Dot.MEASURED_FY_MIN && c >= 0, "above the strip and on the display: $to, $c")
        for (screen in listOf("preset_compact", "preset_compact_list")) {
            val m = masked(screen, to)
            val img = frames.getValue(screen)
            val (p, mb) = assertNotNull(Preset.place(m), screen)
            assertEquals(Preset.place(img)!!.second, mb, screen)
            assertEquals(Preset.compactRows(img, mb), Preset.compactRows(m, mb), screen)
            assertEquals(Preset.Place.DIGIVICE, p)
            m.release()
        }
        val list = masked("preset_compact_list", to)
        val lb = Preset.place(list)!!.second
        val rows = Preset.compactRows(list, lb)!!
        assertEquals(Preset.ROWS, rows.count { r -> Preset.rowText(list, lb, r)?.also { it.release() } != null })
        list.release()
    }

    @Test
    fun `without the clear the page never opens`() {
        val world = World(edges(), home = 0.06, clears = false)
        val log = ArrayList<String>()
        val out = skill(world, shownSlot(), log).run()
        println(log.joinToString("\n"))
        assertEquals(Result.PARKED, out.result, out.toString())
        assertTrue(log.any { "Digivice did not open" in it }, log.joinToString("\n"))
    }

    @Test
    fun `the dot is moved off the bar and the list, the slot is read, and the dot is back`() {
        val world = World(edges(), home = 0.06, clears = true)
        val log = ArrayList<String>()
        val slot = shownSlot()
        val s = skill(world, slot, log)
        val out = s.run()
        println(world.taps.joinToString("\n"))
        println(world.overlayCalls.joinToString("\n"))
        println(log.joinToString("\n"))
        assertEquals(Result.DONE, out.result, out.toString() + "\n" + log.joinToString("\n"))
        assertEquals(0, world.strays, world.taps.toString())
        assertEquals("main", world.at)
        assertTrue(log.any { "Digivice is on slot $slot already" in it }, log.joinToString("\n"))
        assertEquals(3, world.overlayCalls.size, world.overlayCalls.toString())
        // Above the strip, in the headroom over the bar: -0.0030, rows 41 to 167.
        assertEquals("moved to -0.0030", world.overlayCalls[1], world.overlayCalls.toString())
        assertEquals("back", world.overlayCalls[2])
        assertEquals(0.06, world.dotFy, "the dot is back at the player's place")
    }
}
