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
 * The taps on the windows that stand in the middle of a display taller than
 * the canvas ceiling (PLAN_FORMATE.md V4, group 3): the World Search board's
 * X, the run's result window, the Partner window and the idle popup, on the
 * tour's own frames of 1080 x 2520 (180 rows of headroom), 1644 x 3840 (278)
 * and 720 x 1600 (40), handed over as `grab` hands them. What says a tap
 * landed is the picture under it, and for the two large headrooms that the
 * bottom's rectangle, a headroom and a half lower, would not have.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WindowAnchorFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val formats = listOf("1080x2520_none180", "1644x3840_none278", "720x1600_none40")
    private val screens = listOf("board", "main", "runner_page", "runner_result", "partner_window", "popup_idle")
    private val frames = HashMap<String, Mat>()

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val dir = File(repo, "${OracleFamilies.CORPUS}/formats")
        for (format in formats) for (screen in screens) {
            val f = dir.listFiles()!!.filter { it.name.startsWith("${screen}_${format}_") }.minBy { it.name }
            frames["$screen@$format"] = OracleFamilies.read(f)
        }
    }

    @AfterAll
    fun release() = frames.values.forEach(Mat::release)

    private fun frame(screen: String, format: String) = frames.getValue("$screen@$format")

    /** One frame per tap: the first until something is tapped, the next after it. */
    private class Replay(private val queue: List<Mat>) : Capture {
        val points = ArrayList<Pair<Int, Int>>()
        override fun grab(): Mat = OracleFamilies.copy(queue[minOf(points.size, queue.size - 1)])
        override fun tap(x: Int, y: Int) { points.add(x to y) }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun back() {}
        override fun inFront(): String? = "com.bandainamcoent.dgup_ww"
    }

    private fun share(img: Mat, x: Int, y: Int, band: Dungeon.Hsv, r: Int = 16): Double {
        val sub = img.submat(maxOf(0, y - r / 2), minOf(img.rows(), y + r / 2),
                             maxOf(0, x - r), minOf(img.cols(), x + r))
        val hsv = Cv.hsv(sub)
        val mask = Cv.inRange(hsv, band)
        val out = Core.countNonZero(mask).toDouble() / (mask.rows() * mask.cols())
        hsv.release(); mask.release()
        return out
    }

    /** The place the bottom's rectangle would have put a middle window's tap. */
    private fun lower(img: Mat, y: Int) = y + Dungeon.headroom(img) / 2

    @Test
    fun `the board's X is read and tapped in the middle`() {
        val vision = Vision(ClassPathAssets)
        for (format in formats) {
            val board = frame("board", format)
            val x = Quest.closeX(board)!!
            assertEquals(Dungeon.Anchor.MIDDLE, x.anchor, format)
            val cap = Replay(listOf(board, frame("main", format)))
            val skill = WorldSearchSkill(cap, vision, {
                WorldSearchSettings(wanted = WorldConst.ALL_WANTED, minPaws = 0, maxActions = 1,
                                    clickDelay = 0.0, waitForBoard = 180, navigate = false,
                                    dryRun = false, calibFrames = 5)
            }, log = {}, sleep = {}, now = { 0.0 })
            assertTrue(skill.leave(), "$format: ${cap.points}")
            val (px, py) = cap.points[0]
            assertTrue(share(board, px, py, Explore.CLOSE_PLATE, r = 40) > 0.3, "$format: X tapped at $px,$py")
            if (Dungeon.headroom(board) >= 100) {
                assertTrue(share(board, px, lower(board, py), Explore.CLOSE_PLATE, r = 40) < 0.3,
                           "$format: the bottom's place is the plate too")
            }
        }
    }

    @Test
    fun `the result window's Quit is tapped in the middle`() {
        val quitPink = Runner.QUIT_PINK
        for (format in formats) {
            val page = frame("runner_page", format)
            val result = frame("runner_result", format)
            val cap = Replay(listOf(page, result, page))
            var t = 0.0
            val log = ArrayList<String>()
            val skill = RunnerSkill(cap, { RunnerSkill.Settings() }, log = { log += it },
                                    on = { true }, sleep = { t += it }, now = { t += 0.1; t })
            skill.playRun()
            assertTrue(cap.points.size >= 2, "$format: ${cap.points} $log")
            val (qx, qy) = cap.points[1]
            assertTrue(share(result, qx, qy, quitPink, r = 40) > 0.3, "$format: Quit tapped at $qx,$qy, $log")
            if (Dungeon.headroom(result) >= 100) {
                assertTrue(share(result, qx, lower(result, qy), quitPink, r = 40) < 0.3,
                           "$format: the bottom's place is pink too")
            }
        }
    }

    @Test
    fun `the Partner window and the idle popup are read in the middle`() {
        for (format in formats) {
            val partner = frame("partner_window", format)
            assertEquals(Director.PARTNER_WINDOW, Director.classify(partner).screen, format)
            val menu = Passive.partnerMenu(partner)!!
            val r = Dungeon.gameRect(partner, Passive.PARTNER)
            val x = Py.roundInt(r.x0 + menu.fx * r.gw); val y = Py.roundInt(r.y0 + menu.fy * r.gh)
            assertTrue(share(partner, x, y, Dungeon.VIOLET, r = 40) > 0.3, "$format: menu at $x,$y")
            // The close tap above the window lands above its top edge, which
            // the passive helper measured at 0.199 of the window's height.
            val close = Py.roundInt(r.y0 + PassiveSkill.PARTNER_CLOSE[1] * r.gh)
            val top = Py.roundInt(r.y0 + 0.199 * r.gh)
            assertTrue(close < top, "$format: the close tap $close under the window's top $top")

            val idle = frame("popup_idle", format)
            assertEquals(Director.CLAIM_REWARDS, Director.classify(idle).screen, format)
            val claim = Dungeon.claimButton(idle)!!
            val ri = Dungeon.gameRect(idle, Dungeon.POPUP)
            assertTrue(share(idle, Py.roundInt(ri.x0 + claim.fx * ri.gw), Py.roundInt(ri.y0 + claim.fy * ri.gh),
                             Dungeon.BLUE, r = 40) > 0.3, "$format: Claim")
        }
    }
}
