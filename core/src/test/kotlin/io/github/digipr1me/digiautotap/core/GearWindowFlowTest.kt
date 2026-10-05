package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hologram device's gear window under the director (PLAN_ABSCHLUSS_1_3.md
 * K2, the player's word of 2026-10-04): "Equipped" over "Not Equipped", Sell
 * beside Equip, no X. Auto Spend raises it over the main screen by itself and
 * it stands until somebody chooses. Until this classify called it `dialog`,
 * the semi-automatic mode parked on it ("Something is in the way that I did
 * not put there: dialog."), and the fully automatic one too.
 *
 * The game here is a script of real frames ([Device]): the main screen, and
 * the window over it when the device raises it. A tap on the dimmed field
 * beside the window closes it -- what the player said and what was tried on
 * instance 1 --; a tap on Sell or on Equip is a fault this test names, and
 * any other tap is a stray. Every case runs two windows, one after the other.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GearWindowFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val loaded = HashMap<String, Mat>()

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    @AfterAll
    fun release() {
        loaded.values.forEach { it.release() }
    }

    private fun frame(path: String): Mat = loaded.getOrPut(path) {
        // No corpus at all is the public copy of the source (release.py), where
        // these cases have nothing to say; a frame missing from it is a fault.
        assumeTrue(File(repo, "corpus").isDirectory, "no corpus in this checkout")
        val file = File(repo, path)
        assertTrue(file.exists(), "no frame at $path")
        OracleFamilies.read(file).also { assertTrue(!it.empty(), "could not read $file") }
    }

    /** The gear windows the corpus holds, kept on the player's account and named by nobody until K2. */
    private val corpusGear = listOf(
        "corpus/bond/open_the_Digimon_page_140453.png", "corpus/bond/open_the_Digimon_page_163700.png",
        "corpus/passive/unclear_161516.png", "corpus/passive/unclear_170257.png",
        "corpus/passive/unclear_170757.png", "corpus/passive/unclear_182403.png",
        "corpus/passive/unclear_182903.png", "corpus/passive/unclear_183404.png",
        "corpus/passive/unclear_215930.png", "corpus/quest/no-x-to-get-out-with_082048.png",
        "corpus/quest/no-x-to-get-out-with_124001.png", "corpus/quest/no-x-to-get-out-with_133917.png")
    private val gear = "corpus/passive/unclear_161516.png"
    private val gear2 = "corpus/quest/no-x-to-get-out-with_124001.png"
    private val home = "corpus/tall/main_bubble_1080x1920_a.png"

    /** One gear window: its frame, and what a tap at x, y on it is. */
    private class Window(val name: String, val img: Mat) {
        private val g = Startup.gearWindow(img)!!
        private val r = Dungeon.gameRect(img, Startup.GEAR_ANCHOR)
        private fun on(x: Int, y: Int, fx: Double): Boolean =
            kotlin.math.abs(x - (r.x0 + fx * r.gw)) <= g.fw * r.gw / 2 &&
                kotlin.math.abs(y - (r.y0 + g.fy * r.gh)) <= 0.035 * r.gh

        /** "Sell", "Equip", "beside" (the dimmed field left of the cards, under the HUD's left column), or null. */
        fun what(x: Int, y: Int): String? = when {
            on(x, y, g.sellFx) -> "Sell"
            on(x, y, g.equipFx) -> "Equip"
            x < r.x0 + 0.145 * r.gw && y > r.y0 + 0.29 * r.gh && y < r.y0 + 0.80 * r.gh -> "beside"
            else -> null
        }
    }

    /**
     * The game: the main screen, and a gear window over it from [raise] on
     * until a tap beside it -- or never, where [closes] is false.
     */
    private class Device(val windows: List<Window>, val home: Mat, val closes: Boolean = true) {
        var up: Window? = null
        private var next = 0
        val closed = ArrayList<String>()
        val faults = ArrayList<String>()
        val strays = ArrayList<String>()
        fun raise() { up = windows[next % windows.size]; next += 1 }
        fun frame(): Mat = OracleFamilies.copy(up?.img ?: home)
        fun tap(x: Int, y: Int) {
            val w = up
            if (w == null) { strays += "the main screen at $x,$y"; return }
            when (val what = w.what(x, y)) {
                "Sell", "Equip" -> faults += "$what on ${w.name} at $x,$y"
                "beside" -> if (closes) { closed += w.name; up = null }
                else -> strays += "${w.name} at $x,$y ($what)"
            }
        }
    }

    private fun rig(mode: String, device: Device, steps: List<String> = emptyList()): DirectorTest.Rig =
        DirectorTest.Rig(mode, steps).also { r ->
            r.cap.scene = { device.frame() }
            r.cap.onTap = { x, y -> device.tap(x, y) }
        }

    private fun DirectorTest.Rig.until(seconds: Double, done: () -> Boolean): Director.Tick {
        val end = t + seconds
        var last = tick()
        while (!done() && t < end) last = tick()
        return last
    }

    private fun windows() = listOf(Window("unclear_161516", frame(gear)), Window("no-x_124001", frame(gear2)))

    @Test
    fun `every gear window of the corpus reads as gear, and the dot at any place of its strip moves none`() {
        for (path in corpusGear) {
            assertEquals(Director.GEAR, Director.classify(frame(path)).screen, path)
            assertNotNull(Startup.gearWindow(frame(path)), path)
        }
        assertEquals(Director.MAIN, Director.classify(frame(home)).screen)
        var fy = Dot.MEASURED_FY_MIN
        while (fy <= Dot.MEASURED_FY_MAX + 1e-9) {
            for (path in corpusGear) {
                val img = OracleFamilies.copy(frame(path))
                Dot.mask(img, listOf(Dot.square(img.cols(), img.rows(), false, fy),
                                     Dot.plate(img.cols(), img.rows(), false, fy)))
                assertEquals(Director.GEAR, Director.classify(img).screen, "$path under the dot at fy %.3f".format(fy))
                img.release()
            }
            fy += 0.005
        }
    }

    @Test
    fun `the close spot lies beside the window on every gear frame, and on neither button`() {
        for (path in corpusGear) {
            val img = frame(path)
            val w = Window(path, img)
            val r = Dungeon.gameRect(img, Startup.GEAR_CLOSE_ANCHOR)
            val x = Py.roundInt(r.x0 + Startup.GEAR_CLOSE[0] * r.gw)
            val y = Py.roundInt(r.y0 + Startup.GEAR_CLOSE[1] * r.gh)
            assertEquals("beside", w.what(x, y), "$path at $x,$y")
            assertTrue(x in 0 until img.cols() && y in 0 until img.rows(), "$path: $x,$y in the picture")
        }
    }

    /**
     * Semi-automatic, two windows: each is closed with one tap beside it after
     * the player's three seconds, the main screen's rounds go on after each,
     * and nothing parks. Neither Sell nor Equip is ever tapped.
     */
    @Test
    fun `semi-automatic, the gear window is closed beside it, two windows`() {
        val device = Device(windows(), frame(home))
        val r = rig(SkillSettings.MODE_SEMI, device)
        r.run(4.0)
        assertTrue(r.passive.worked > 0, "the main screen's rounds before the window")
        for (pass in 1..2) {
            val rounds = r.passive.worked
            device.raise()
            r.until(20.0) { device.up == null }
            assertNull(device.up, "pass $pass, the window closed: ${r.log}")
            r.run(8.0)
            assertTrue(r.passive.worked > rounds, "pass $pass: the rounds go on after the window")
        }
        assertEquals(listOf("unclear_161516", "no-x_124001"), device.closed)
        assertEquals(2, r.cap.taps.size, "one tap a window and nothing else: ${r.cap.taps}")
        assertTrue(device.faults.isEmpty(), "Sell or Equip tapped: ${device.faults}")
        assertTrue(device.strays.isEmpty(), "taps that closed nothing: ${device.strays}")
        assertNull(r.director.parked, r.log.toString())
        assertEquals(2, r.log.count { it.startsWith("the game's gear window is up -- closing it beside the window") }, r.log.toString())
        assertEquals(2, r.log.count { it == "  the gear window is gone after the tap beside it -- main now" }, r.log.toString())
        assertEquals(listOf("gear"), r.kept, "the first window's frame, once")
        assertTrue(r.log.none { it.contains("in the way") }, r.log.toString())
    }

    /**
     * Fully automatic: the window is up when the core comes up, before the
     * chain's first step; it is closed and the step starts on the main screen
     * under it. The step brings the second window back with it, which is
     * closed the same way before the chain's second step.
     */
    @Test
    fun `fully automatic, the gear window is closed and the chain goes on, two windows`() {
        val device = Device(windows(), frame(home))
        val r = rig(SkillSettings.MODE_FULL, device, listOf("dungeon", "summon"))
        r.dungeons.onRun = { device.raise() }
        device.raise()
        r.until(60.0) { r.summons.ran > 0 }
        assertEquals(1, r.dungeons.ran, r.log.toString())
        assertEquals(1, r.summons.ran, "the chain goes on after the second window: ${r.log}")
        assertEquals(listOf("unclear_161516", "no-x_124001"), device.closed)
        assertEquals(2, r.cap.taps.size, "one tap a window: ${r.cap.taps} ${r.log}")
        assertTrue(device.faults.isEmpty(), "Sell or Equip tapped: ${device.faults}")
        assertTrue(device.strays.isEmpty(), "taps that closed nothing: ${device.strays}")
        assertNull(r.director.parked, r.log.toString())
        val closedFirst = r.log.indexOfFirst { it.startsWith("the game's gear window is up") }
        assertTrue(closedFirst >= 0 && closedFirst < r.log.indexOfFirst { it.contains("chain: starting Dungeons") },
                   r.log.toString())
    }

    /**
     * A window that does not go: two taps beside it, three seconds apart, then
     * a park with its sentence and the frame -- never a third tap, never a
     * button. The player chooses; the main screen after it ends the park, and
     * the next window is closed afresh.
     */
    @Test
    fun `a window that stays after two taps beside it is a park, and the next one is closed afresh`() {
        for (mode in listOf(SkillSettings.MODE_SEMI, SkillSettings.MODE_FULL)) {
            val stuck = Device(windows(), frame(home), closes = false)
            val r = rig(mode, stuck)
            stuck.raise()
            val tick = r.run(30.0)
            assertEquals(Director.GEAR_PARK, r.director.parked, "$mode: ${r.log}")
            assertEquals(Director.GEAR_PARK, tick.note, mode)
            assertEquals(Director.GEAR_TAPS, r.cap.taps.size, "$mode: two taps and no third: ${r.cap.taps}")
            assertTrue(stuck.faults.isEmpty(), "$mode: ${stuck.faults}")
            assertTrue("gear_stays" in r.kept, "$mode: ${r.kept}")
            val taps = r.cap.taps.size
            r.run(20.0)
            assertEquals(taps, r.cap.taps.size, "$mode: the park taps nothing more")
            // The player chooses; the window goes, the next comes and is closed.
            stuck.up = null
            r.run(6.0)
            assertNull(r.director.parked, "$mode: the main screen ends the park")
            val device = Device(windows(), frame(home))
            r.cap.scene = { device.frame() }
            r.cap.onTap = { x, y -> device.tap(x, y) }
            device.raise()
            r.until(20.0) { device.up == null }
            assertNull(device.up, "$mode: the next window is closed: ${r.log}")
            assertTrue(device.faults.isEmpty() && device.strays.isEmpty(), "$mode: ${device.faults} ${device.strays}")
        }
    }
}
