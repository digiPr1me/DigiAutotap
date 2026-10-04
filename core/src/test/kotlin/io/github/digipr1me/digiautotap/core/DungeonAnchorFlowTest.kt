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
import kotlin.test.assertTrue

/**
 * DungeonSkill's taps on a display taller than the canvas ceiling
 * (PLAN_FORMATE.md V4, group 2): the tour's own frames of 1080 x 2520 (180
 * rows of headroom), 1644 x 3840 (278) and 720 x 1600 (40), handed over as
 * `grab` hands them. The list is the HUD's and read at the bottom, the panel
 * a window at the top ([Dungeon.PANEL]); a card is tapped at the bottom, the
 * panel's Attempt where [Dungeon.recognise] read it. What says a tap landed
 * is the picture under it: the blue of the card, the blue of Attempt, and
 * not blue the headroom lower, where the bottom's rectangle would have sent
 * the Attempt tap.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DungeonAnchorFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val formats = listOf("1080x2520_none180", "1644x3840_none278", "720x1600_none40")
    private val frames = HashMap<String, Mat>()

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val dir = File(repo, "${OracleFamilies.CORPUS}/formats")
        for (format in formats) for (screen in listOf("dungeon_list_top", "dungeon_panel", "popup_exit")) {
            val f = dir.listFiles()!!.filter { it.name.startsWith("${screen}_${format}_") }.minBy { it.name }
            frames["$screen@$format"] = OracleFamilies.read(f)
        }
    }

    @AfterAll
    fun release() = frames.values.forEach(Mat::release)

    private fun frame(screen: String, format: String) = frames.getValue("$screen@$format")

    /** The skill over a queue of frames, each handed out as a fresh copy with its headroom. */
    private class Rig(vararg queue: Mat) {
        val cap = DirectorTest.FakeCapture()
        val log = ArrayList<String>()
        var t = 1000.0
        val skill = object : DungeonSkill(cap, { Settings(budgets = mapOf(0 to 1)) },
                                          log = { log += it }, on = { true }, keep = { _, _ -> },
                                          sleep = { t += it }, now = { t }, patience = 0.0) {
            // One card and nothing else: the list is not planned or scrolled.
            override fun play(): Outcome {
                playEntry(0, DungeonSkill.TOP)
                return Outcome.DONE
            }
        }

        init {
            skill.startTimeout = 1.0
            skill.battleTimeout = 1.0
            cap.scene = { i ->
                t += 0.2
                OracleFamilies.copy(queue[minOf(i, queue.size - 1)])
            }
        }
    }

    private fun blue(img: Mat, x: Int, y: Int, r: Int = 16): Double {
        val sub = img.submat(maxOf(0, y - r / 2), minOf(img.rows(), y + r / 2),
                             maxOf(0, x - r), minOf(img.cols(), x + r))
        val hsv = Cv.hsv(sub)
        val mask = Cv.inRange(hsv, Dungeon.BLUE)
        val out = Core.countNonZero(mask).toDouble() / (mask.rows() * mask.cols())
        hsv.release(); mask.release()
        return out
    }

    /** Is there a row between [y0] and [y1] that is mostly blue for 300 px around [x]? */
    private fun blueEdge(img: Mat, x: Int, y0: Int, y1: Int): Boolean {
        val top = maxOf(0, y0)
        val bottom = minOf(img.rows(), y1)
        if (bottom <= top) return false
        val sub = img.submat(top, bottom, maxOf(0, x - 150), minOf(img.cols(), x + 150))
        val hsv = Cv.hsv(sub)
        val mask = Cv.inRange(hsv, Dungeon.BLUE)
        try {
            for (y in 0 until mask.rows()) {
                if (Core.countNonZero(mask.row(y)) > 0.3 * mask.cols()) return true
            }
            return false
        } finally {
            hsv.release(); mask.release()
        }
    }

    @Test
    fun `the panel is read at the top and the list at the bottom`() {
        for (format in formats) {
            assertEquals(Dungeon.Anchor.TOP, Dungeon.recognise(frame("dungeon_panel", format)).anchor, format)
            assertEquals(Dungeon.Anchor.BOTTOM, Dungeon.recognise(frame("dungeon_list_top", format)).anchor, format)
            assertEquals(Dungeon.Anchor.MIDDLE, Dungeon.recognise(frame("popup_exit", format)).anchor, format)
        }
    }

    @Test
    fun `the card is tapped at the bottom and Attempt at the top`() {
        for (format in formats) {
            val list = frame("dungeon_list_top", format)
            val panel = frame("dungeon_panel", format)
            val r = Rig(list, list, panel)
            r.skill.work(list)
            val taps = r.cap.taps
            assertTrue(taps.size >= 2, "$format: ${taps} ${r.log}")
            // A card is a blue frame round its artwork: the tap stands
            // between its top and bottom edge, each within the card's height.
            val (cx, cy) = taps[0]
            val card = Dungeon.gameRect(list).gh * Dungeon.listCardsWithSize(list)[0].second
            assertTrue(blueEdge(list, cx, cy - card.toInt() / 2 - 8, cy) &&
                       blueEdge(list, cx, cy, cy + card.toInt() / 2 + 8),
                       "$format: card tapped at $cx,$cy")
            val (ax, ay) = taps[1]
            assertTrue(blue(panel, ax, ay, r = 40) > 0.3, "$format: Attempt tapped at $ax,$ay, ${r.log}")
            // 40 rows at 720 x 1600 are half the button's height and stay on
            // it; 180 and 278 leave it.
            val low = ay + Dungeon.headroom(panel)
            if (Dungeon.headroom(panel) >= 100) {
                assertTrue(blue(panel, ax, low, r = 40) < 0.3, "$format: the bottom's place $ax,$low is blue too")
            }
        }
    }

    @Test
    fun `the prompt's Cancel is tapped in the middle`() {
        for (format in formats) {
            val prompt = frame("popup_exit", format)
            val list = frame("dungeon_list_top", format)
            val r = Rig(list, prompt)
            r.skill.work(list)                  // sets the pass's rectangle off the list
            r.cap.taps.clear()
            r.skill.dismissConfirm(Dungeon.recognise(prompt))
            assertEquals(1, r.cap.taps.size, "$format: ${r.log}")
            val (x, y) = r.cap.taps[0]
            // The exit prompt's Cancel is the grey or pink one, not blue:
            // what is asserted is that the tap stands on the button row the
            // prompt's OK stands on, which the reader found blue.
            val ok = Dungeon.recognise(prompt).exitOk!!
            val rect = Dungeon.gameRect(prompt, Dungeon.POPUP)
            val okY = Py.roundInt(rect.y0 + ok.fy * rect.gh)
            assertEquals(okY, y, "$format: Cancel on OK's row")
            assertTrue(blue(prompt, Py.roundInt(rect.x0 + ok.fx * rect.gw), okY) > 0.3, "$format: OK is blue there")
        }
    }
}
