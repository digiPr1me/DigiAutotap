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
 * SummonSkill's taps on a display taller than the canvas ceiling
 * (PLAN_FORMATE.md V4): the tour's own frames of 1080 x 2520 (180 rows of
 * headroom), 1644 x 3840 (278) and 720 x 1600 (40), handed over as `grab`
 * hands them, with their headroom. The page's readers read at Summon.PAGE,
 * the top; a tap aimed with the bottom's rectangle would land the headroom
 * too low. What says a tap landed is the picture under it, not the reader
 * that aimed it: blue ink of the General tab, the yellow of the summon
 * button, the white plate of the X.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SummonAnchorFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val formats = listOf("1080x2520_none180", "1644x3840_none278", "720x1600_none40")
    private val frames = HashMap<String, Mat>()

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val dir = File(repo, "${OracleFamilies.CORPUS}/formats")
        for (format in formats) for (screen in listOf("main", "summon_buddy", "summon_general")) {
            val f = dir.listFiles()!!.filter { it.name.startsWith("${screen}_${format}_") }.minBy { it.name }
            frames["$screen@$format"] = OracleFamilies.read(f)
        }
    }

    @AfterAll
    fun release() = frames.values.forEach(Mat::release)

    private fun frame(screen: String, format: String) = frames.getValue("$screen@$format")

    /** The skill over a queue of frames, each handed out as a fresh copy with its headroom. */
    private class Rig(vararg queue: Mat, settings: SummonSkill.Settings = SummonSkill.Settings()) {
        val cap = DirectorTest.FakeCapture()
        val log = ArrayList<String>()
        var t = 1000.0
        val skill = SummonSkill(cap, { settings }, log = { log += it }, keep = { _, _ -> },
                                on = { true }, patience = 0.0, tapEvery = 0.0,
                                frozenSeconds = 1.0, now = { t }, sleep = { t += it })

        init {
            cap.scene = { i ->
                t += 0.2
                OracleFamilies.copy(queue[minOf(i, queue.size - 1)])
            }
        }
    }

    /**
     * The share of a box of 2r x r pixels around a tap that lies in [band]:
     * a button's centre is its label, dark or white, and the button is the
     * colour around it.
     */
    private fun share(img: Mat, x: Int, y: Int, band: Dungeon.Hsv, r: Int = 16): Double {
        val sub = img.submat(maxOf(0, y - r / 2), minOf(img.rows(), y + r / 2),
                             maxOf(0, x - r), minOf(img.cols(), x + r))
        val hsv = Cv.hsv(sub)
        val mask = Cv.inRange(hsv, band)
        val out = Core.countNonZero(mask).toDouble() / (mask.rows() * mask.cols())
        hsv.release(); mask.release()
        return out
    }

    private val WHITE = Dungeon.Hsv(intArrayOf(0, 0, 200), intArrayOf(179, 60, 255))

    @Test
    fun `the frames carry the headroom the app cut above them`() {
        assertEquals(180, Dungeon.headroom(frame("summon_general", "1080x2520_none180")))
        assertEquals(278, Dungeon.headroom(frame("summon_general", "1644x3840_none278")))
        assertEquals(40, Dungeon.headroom(frame("summon_general", "720x1600_none40")))
    }

    @Test
    fun `from Buddy the General tap lands on the General tab`() {
        for (format in formats) {
            val buddy = frame("summon_buddy", format)
            val general = frame("summon_general", format)
            val r = Rig(frame("main", format), buddy, general, general)
            assertNull(r.skill.walkIn(), "$format: ${r.log}")
            assertEquals(2, r.cap.taps.size, "$format: the icon and General, ${r.cap.taps}")
            val (x, y) = r.cap.taps[1]
            val blue = share(buddy, x, y, Dungeon.BLUE, r = 60)
            assertTrue(blue > 0.3, "$format: General tapped at $x,$y, blue $blue")
            // And the bottom's rectangle, the headroom lower, would have missed it.
            val old = share(buddy, x, y + Dungeon.headroom(buddy), Dungeon.BLUE, r = 60)
            assertTrue(old < 0.3, "$format: the old place $x,${y + Dungeon.headroom(buddy)} is blue $old")
        }
    }

    @Test
    fun `the summon button is tapped on its yellow`() {
        for (format in formats) {
            val general = frame("summon_general", format)
            val r = Rig(general)
            r.skill.spam(SummonSkill.SKILL)
            assertTrue(r.cap.taps.isNotEmpty(), "$format: ${r.log}")
            val (x, y) = r.cap.taps.first()
            val yellow = share(general, x, y, Summon.YELLOW)
            assertTrue(yellow > 0.3, "$format: summon tapped at $x,$y, yellow $yellow")
        }
    }

    @Test
    fun `the X that closes the page stays at the bottom`() {
        // Buddy has no mode dots, so backToMain taps the X it finds -- a
        // reader of the bottom, tapped at the bottom.
        for (format in formats) {
            val buddy = frame("summon_buddy", format)
            val r = Rig(buddy)
            r.skill.backToMain()
            assertTrue(r.cap.taps.isNotEmpty(), "$format: ${r.log}")
            val (x, y) = r.cap.taps.first()
            val white = share(buddy, x, y, WHITE, r = 40)
            assertTrue(white > 0.3, "$format: X tapped at $x,$y, white $white")
        }
    }
}
