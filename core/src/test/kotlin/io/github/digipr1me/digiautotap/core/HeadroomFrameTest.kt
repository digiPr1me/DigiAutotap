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
 * `grab` keeps the rows over the canvas on a display taller than the canvas
 * ceiling (PLAN_FORMATE.md V4, group 5, the player's decision of
 * 2026-09-27). Five whole frames of LDPlayer at 1080 x 2520 without a cutout
 * (`*_1080x2520_none0_*`, 180 rows of headroom, all of them in the frame),
 * each read twice: whole, as `grab` hands it over now, and cut to the canvas,
 * as it did since V3. The HUD's readers answer the same on both, because
 * they read the canvas under the headroom either way; what only the whole
 * frame has is a window that stands higher than the cut -- the World Search
 * board's three counters at its top, and the whole Digivice bar.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadroomFrameTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val whole = HashMap<String, Mat>()
    private val cut = HashMap<String, Mat>()
    private val vision = Vision(ClassPathAssets)

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val dir = File(repo, "${OracleFamilies.CORPUS}/formats")
        for (screen in listOf("main", "explore_menu", "board", "preset_compact", "preset_compact_list")) {
            val f = dir.listFiles()!!.filter { it.name.startsWith("${screen}_1080x2520_none0_") }.minBy { it.name }
            val img = OracleFamilies.read(f)
            whole[screen] = img
            cut[screen] = Dungeon.CanvasFrame(Dungeon.headroom(img), 0).also {
                img.submat(Dungeon.above(img), img.rows(), 0, img.cols()).copyTo(it)
            }
        }
    }

    @AfterAll
    fun release() {
        whole.values.forEach(Mat::release); cut.values.forEach(Mat::release)
    }

    @Test
    fun `the frames hold their headroom, and the canvas is where the cut put it`() {
        for ((screen, img) in whole) {
            assertEquals(180, Dungeon.headroom(img), screen)
            assertEquals(180, Dungeon.above(img), screen)
            val a = Dungeon.gameRect(img); val b = Dungeon.gameRect(cut.getValue(screen))
            assertEquals(b.copy(y0 = b.y0 + 180), a, "$screen: the bottom's rectangle, 180 rows lower in the whole frame")
            for (anchor in Dungeon.Anchor.values()) {
                val wa = Dungeon.gameRect(img, anchor); val ca = Dungeon.gameRect(cut.getValue(screen), anchor)
                assertEquals(ca.y0 + 180, wa.y0, "$screen at $anchor")
            }
        }
    }

    @Test
    fun `the HUD's readers answer the same on the whole frame and the cut one`() {
        for ((screen, img) in whole) {
            val c = cut.getValue(screen)
            assertEquals(Director.classify(c).screen, Director.classify(img).screen, screen)
            assertEquals(Dungeon.autoButton(c), Dungeon.autoButton(img), screen)
            assertEquals(Dungeon.homeButton(c), Dungeon.homeButton(img), screen)
            assertEquals(Dungeon.recognise(c).toOracle(), Dungeon.recognise(img).toOracle(), screen)
            assertEquals(Quest.questCard(c), Quest.questCard(img), screen)
            assertEquals(Runner.eventsIcon(c), Runner.eventsIcon(img), screen)
            assertEquals(Quest.closeX(c)?.toOracle(), Quest.closeX(img)?.toOracle(), screen)
        }
        assertEquals(Director.MAIN, Director.classify(whole.getValue("main")).screen)
        assertEquals(Director.EXPLORE_MENU, Director.classify(whole.getValue("explore_menu")).screen)
        assertEquals(Director.BOARD, Director.classify(whole.getValue("board")).screen)
        assertEquals(Director.PRESET, Director.classify(whole.getValue("preset_compact")).screen)
    }

    @Test
    fun `the board's counters at its top are read only on the whole frame`() {
        val board = whole.getValue("board")
        val read = vision.readCounters(board, vision.calibrate(board))
        for (k in listOf("top_orange", "top_green", "top_pink")) assertNotNull(read[k], "$k in $read")
        val c = cut.getValue("board")
        val cutRead = vision.readCounters(c, vision.calibrate(c))
        for (k in listOf("top_orange", "top_green", "top_pink")) assertNull(cutRead[k], "$k in $cutRead")
        assertEquals(read["paws"], cutRead["paws"])
        // And a tap on the board's X, aimed in the whole frame's rectangle
        // at its anchor, stands on the X's plate -- the frame's row 0 is the
        // display's, which is where `DigiAutotapService.tap` sends it.
        val x = Quest.closeX(board)!!
        val r = Dungeon.gameRect(board, x.anchor)
        val px = Py.roundInt(r.x0 + x.fx * r.gw); val py = Py.roundInt(r.y0 + x.fy * r.gh)
        val sub = board.submat(py - 10, py + 10, px - 40, px + 40)
        val hsv = Cv.hsv(sub); val m = Cv.inRange(hsv, Explore.CLOSE_PLATE)
        val plate = Core.countNonZero(m).toDouble() / (m.rows() * m.cols())
        hsv.release(); m.release()
        assertTrue(plate > 0.3, "X at $px,$py, plate $plate")
    }

    @Test
    fun `the Digivice bar is whole, and stands where its 1920 twin does`() {
        for (screen in listOf("preset_compact", "preset_compact_list")) {
            val (place, bar) = assertNotNull(Preset.place(whole.getValue(screen)), screen)
            assertEquals(Preset.Place.DIGIVICE, place)
            // The twin, corpus/formats/preset_compact_1080x1920_none0_230913: 0.0985.
            assertTrue(abs(bar.fy0 - 0.0985) <= FormatProbe.POSITION_TOL, "$screen: fy0 ${bar.fy0}")
            val cutBar = Preset.place(cut.getValue(screen))!!.second
            assertTrue(abs(cutBar.fy0 - 0.0985) > FormatProbe.POSITION_TOL, "$screen: cut, fy0 ${cutBar.fy0}")
        }
        val list = whole.getValue("preset_compact_list")
        assertEquals(Preset.ROWS, Preset.compactRows(list, Preset.place(list)!!.second)!!.size)
    }
}
