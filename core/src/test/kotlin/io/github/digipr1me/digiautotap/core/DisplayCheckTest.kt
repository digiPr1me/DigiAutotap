package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The display self-check (DisplayCheck, PLAN_FORMATE.md 10) over every
 * frame of the oracle: it may call a format unknown only where the format
 * is one game_rect has no model of, and it must call it known wherever the
 * nav bar's globe stands undimmed where home_button looks for it.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisplayCheckTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var frames: List<String>

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val oracle = Json.parseToJsonElement(File(repo, "oracle/director.json").readText()).jsonObject
        frames = oracle["frames"]!!.jsonObject.keys.sorted()
    }

    /**
     * The frames of the corpus whose format game_rect has no model of, and
     * which the check calls unknown -- every one of them looked at on
     * 2026-09-27:
     *
     *  * `double84`: LDPlayer drew a canvas of 985 x 1752 between a top and a
     *    bottom inset of 84 (PLAN_FORMATE.md V5, an artefact of the
     *    emulator, not a phone). The top bar stands at 0.2745/0.0628.
     *  * `waterfall0`: the game lays its HUD into the side insets' safe area
     *    (V11); the globe is where game_rect puts it, the experience bar
     *    0.0375 of the canvas further right.
     *  * `passive/phone_tall_*`: the Poco F3's frames of 2026-09-22, kept
     *    uncut, from before the app cut the strip over the camera (notes/formats.md,
     *    "On a phone with a camera cutout"); the top bar stands 80 rows low.
     */
    private val UNKNOWN = listOf("_double84_", "_waterfall0_", "passive/phone_tall_")

    /**
     * home_button answers and the check's globe is not it: the bond tour's
     * tap on the Partner tab, whose pale tap highlight lies over the globe
     * and joins it -- one blob of 50 x 60 px with 4 holes, against 8 to 12
     * (GLOBE_HOLES). The check says nothing on that frame, which is what it
     * may say.
     */
    private val GLOBE_MISSED = setOf("corpus/bond/select_the_Partner_tab_214743.png")

    @Test
    fun `unknown exactly on the formats game_rect has no model of, known wherever the globe is`() {
        val wrong = ArrayList<String>()
        var known = 0
        var unknown = 0
        for (rel in frames) {
            val img = OracleFamilies.read(File(repo, rel))
            if (img.empty()) continue
            val r = DisplayCheck.check(img)
            val home = Dungeon.homeButton(img)
            img.release()
            // On those formats a screen with neither landmark (the board,
            // the Events window) says nothing, and that is all it may say
            // besides "unknown"; everywhere else "unknown" is a false alarm.
            val expectUnknown = UNKNOWN.any { it in rel }
            if (expectUnknown && r.verdict == DisplayCheck.Verdict.KNOWN ||
                !expectUnknown && r.verdict == DisplayCheck.Verdict.UNKNOWN)
                wrong += "$rel: ${r.verdict} -- ${r.say()}"
            if (r.verdict == DisplayCheck.Verdict.UNKNOWN) unknown++
            if (home != null && rel !in GLOBE_MISSED && !expectUnknown && r.verdict != DisplayCheck.Verdict.KNOWN)
                wrong += "$rel: home_button answers, the check says ${r.verdict} -- ${r.say()}"
            if (r.verdict == DisplayCheck.Verdict.KNOWN) known++
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} frames:\n" + wrong.joinToString("\n"))
        assertTrue(known >= 700, "only $known frames known")
        // 2026-09-27: 4 double84, 3 waterfall0, 5 phone_tall.
        assertEquals(12, unknown, "frames called unknown")
    }

    /** A frame of 1080 x 1920 from the tour, with nav bar and top bar both. */
    private fun main(): Mat = OracleFamilies.read(File(repo, "corpus/formats/main_1080x1920_none0_221620.png"))

    @Test
    fun `a navigation bar the game does not draw under is an unknown format`() {
        // The game fitted above a bar of 48 dp at 420 dpi, 126 px: the
        // canvas 1794 tall, letterboxed, and the bar black under it -- a
        // shape the frame model reads as a 1080 x 1920 display.
        val src = main()
        val scaled = Mat()
        org.opencv.imgproc.Imgproc.resize(src, scaled, org.opencv.core.Size(1009.0, 1794.0), 0.0, 0.0,
                                          org.opencv.imgproc.Imgproc.INTER_AREA)
        val frame = Mat(1920, 1080, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        scaled.copyTo(frame.submat(Rect(35, 0, 1009, 1794)))
        val r = DisplayCheck.check(frame)
        assertEquals(DisplayCheck.Verdict.UNKNOWN, r.verdict, r.say())
        src.release(); scaled.release(); frame.release()
    }

    @Test
    fun `a cutout the system names and the game does not honour is an unknown format`() {
        // The whole 1920 canvas, with 80 rows cut off its top that were the
        // game's -- the Poco F3's inset: what the app would read if a phone
        // reported an inset the game draws over. The nav bar's globe stays
        // inside the tolerance (the bottom anchor absorbs a cut at the top);
        // the experience bar, 80 rows higher than expected, does not.
        val src = main()
        val frame = src.submat(80, 1920, 0, 1080).clone()
        val r = DisplayCheck.check(frame)
        assertEquals(DisplayCheck.Verdict.UNKNOWN, r.verdict, r.say())
        src.release(); frame.release()
    }

    @Test
    fun `the landscape display cut to the game's window reads as it did whole`() {
        // [656,0][1264,1080], the system's window (GameWindow): the director
        // names every screen the same and the check calls it known.
        val dir = File(repo, "corpus/formats")
        val shots = dir.listFiles()!!.filter { "_1920x1080_landscape0_" in it.name }.sortedBy { it.name }
        assertTrue(shots.size >= 11)
        for (f in shots) {
            val whole = OracleFamilies.read(f)
            val c = GameWindow.cut(whole.cols(), whole.rows(), intArrayOf(656, 0, 1264, 1080), 0)
            val part = whole.submat(c.y, c.y + c.h, c.x, c.x + c.w).clone()
            assertEquals(Director.classify(whole).screen, Director.classify(part).screen, f.name)
            assertEquals(DisplayCheck.check(whole).verdict, DisplayCheck.check(part).verdict, f.name)
            whole.release(); part.release()
        }
    }

    @Test
    fun `the watch says an unknown format once and keeps its frame`() {
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        val watch = DisplayCheck.Watch({ lines += it }, { _, tag -> kept += tag })
        val src = main()
        val frame = src.submat(80, 1920, 0, 1080).clone()
        watch.look(frame)
        watch.look(frame)
        assertEquals(1, lines.count { it.startsWith("unknown display format") }, lines.joinToString("\n"))
        assertEquals(listOf("unknown_display_format"), kept)
        watch.look(src)
        assertEquals(1, lines.count { it.startsWith("display check:") }, lines.joinToString("\n"))
        src.release(); frame.release()
    }

    @Test
    fun `one unknown frame before a known one is a display still turning, and says nothing`() {
        // The shape of 23:26 on LDPlayer: the first frame of the new size
        // still in the old layout, the next one settled.
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        val watch = DisplayCheck.Watch({ lines += it }, { _, tag -> kept += tag })
        // Both 1080 x 1920: the turning one is the picture 80 rows higher,
        // black under it, the settled one the picture as it is.
        val src = main()
        val turning = Mat(1920, 1080, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        src.submat(80, 1920, 0, 1080).copyTo(turning.submat(0, 1840, 0, 1080))
        assertEquals(DisplayCheck.Verdict.UNKNOWN, DisplayCheck.check(turning).verdict)
        watch.look(turning, "unknown")
        watch.look(src, "unknown")
        assertTrue(lines.none { it.startsWith("unknown display format") }, lines.joinToString("\n"))
        assertTrue(kept.isEmpty())
        // And the same shape, turning again twice: said, once.
        watch.look(turning, "main")
        watch.look(turning, "main")
        assertEquals(1, lines.count { it.startsWith("unknown display format") }, lines.joinToString("\n"))
        src.release(); turning.release()
    }
}
