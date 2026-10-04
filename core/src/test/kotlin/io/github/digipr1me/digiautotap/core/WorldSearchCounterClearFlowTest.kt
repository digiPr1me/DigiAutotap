package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The board's three top counters under the dot at the top of the player's
 * strip (PLAN_FORMATE.md V15). At 1080 x 1920 the plate at fy 0.060 lies on
 * `top_pink` and reads it null; the run moves the dot off the counters'
 * rows before it reads its start ([Dot.clearFy], as `Overlay.clearOf`) and
 * puts it back when the run ends. Over the canvas ceiling (the whole 1080 x
 * 2520 board) the counters are clear of the dot already, and it stays.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldSearchCounterClearFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val vision = Vision(ClassPathAssets)
    private val frames = HashMap<String, Mat>()

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val dir = File(repo, "${OracleFamilies.CORPUS}/formats")
        // The frames the counters below were read off, by name: the tablet
        // tour of 2026-09-27 put another 1920 board beside them, with other
        // counters, and "the first by name" became that one.
        for ((fmt, take) in listOf("1080x1920_none0" to "225956", "1080x2520_none0" to "121154")) {
            frames[fmt] = OracleFamilies.read(File(dir, "board_${fmt}_$take.png"))
        }
    }

    @AfterAll
    fun release() = frames.values.forEach(Mat::release)

    /** The board with the dot and plate where the app draws them, on a display whose canvas starts [top] rows down. */
    private inner class Board(private val fmt: String, home: Double, private val clears: Boolean) : Capture {
        private val img = frames.getValue(fmt)
        private val top = Dungeon.above(img)
        val home = home
        var dotFy = home
        val overlayCalls = ArrayList<String>()

        private fun boxes(fy: Double) =
            listOf(Dot.square(img.cols(), img.rows() - top, false, fy), Dot.plate(img.cols(), img.rows() - top, false, fy))
                .map { Dot.Box(it.x, it.y + top, it.w, it.h) }

        override fun grab(): Mat = Dungeon.CanvasFrame(Dungeon.headroom(img), top).also {
            img.copyTo(it)
            Dot.mask(it, boxes(dotFy))
        }
        override fun tap(x: Int, y: Int) {}
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun back() {}
        override fun inFront(): String? = Game.KNOWN
        override fun overlayClear(fy0: Double, fy1: Double) {
            overlayCalls += "clear %.4f-%.4f".format(fy0, fy1)
            if (!clears) return
            Dot.clearFy(dotFy, fy0, fy1, img.cols(), img.rows(), top)?.let {
                dotFy = it; overlayCalls += "moved to %.4f".format(it)
            }
        }
        override fun overlayBack() {
            overlayCalls += "back"
            dotFy = home
        }
    }

    /** The run as far as its start counters: the switch goes off once they are said. */
    private fun start(board: Board): String {
        val log = ArrayList<String>()
        var said = false
        val s = WorldSearchSkill(board, vision,
            { WorldSearchSettings(navigate = false, waitForBoard = 0, dryRun = true) },
            log = { log += it; if (it.startsWith("counters at start")) said = true },
            on = { !said }, sleep = {})
        val out = s.work(board.grab())
        println(log.joinToString("\n"))
        println(board.overlayCalls.joinToString("\n"))
        assertEquals(Result.STOPPED, out.result, out.toString() + "\n" + log.joinToString("\n"))
        return log.first { it.startsWith("counters at start") }
    }

    @Test
    fun `at 1920 the plate at 0_06 takes the pink counter`() {
        val m = Board("1080x1920_none0", 0.06, clears = false).grab()
        assertNull(vision.readCounters(m, vision.calibrate(m))["top_pink"])
        m.release()
    }

    @Test
    fun `without the clear the run starts without its pink counter`() {
        val line = start(Board("1080x1920_none0", 0.06, clears = false))
        assertTrue("1499" !in line, line)
    }

    @Test
    fun `the dot is moved off the counters for the run and back after it`() {
        val board = Board("1080x1920_none0", 0.06, clears = true)
        val line = start(board)
        assertTrue("7660" in line && "1470" in line && "1499" in line, line)
        assertEquals(3, board.overlayCalls.size, board.overlayCalls.toString())
        // Just below the counters (Dot.clearFy): 0.1091, where they and the board read as unmasked.
        assertEquals("moved to 0.1091", board.overlayCalls[1], board.overlayCalls.toString())
        assertEquals("back", board.overlayCalls[2])
        assertEquals(0.06, board.dotFy)
    }

    @Test
    fun `over the ceiling the counters read, and the dot steps off their rows all the same`() {
        // The counters stand at HUD fy 0.0046 to 0.0390 there, the dot at
        // 0.060 reaches up to row 193 of the display -- the rows touch,
        // though the plate is not on a glyph (all three read at every fy of
        // the strip). Dot.clearFy asks rows, not glyphs: 0.0659, just below.
        val board = Board("1080x2520_none0", 0.06, clears = true)
        val line = start(board)
        assertTrue("135" in line && "2895" in line && "2906" in line, line)
        assertEquals(listOf("clear 0.0046-0.0390", "moved to 0.0659", "back"), board.overlayCalls)
        assertEquals(0.06, board.dotFy)
    }
}
