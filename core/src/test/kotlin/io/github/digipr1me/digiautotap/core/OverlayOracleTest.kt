package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
 * **The overlay's proof** (PLAN_ANDROID_APP.md, "Das Overlay"): the dot
 * is drawn into every frame of the corpus at the place the overlay probe measured,
 * [Dot.mask] takes it back out, and the readers are asked again.
 *
 * It is green when it tips **exactly the three frames the overlay measurement names**, and not
 * when it tips none. A port whose mask fills a slightly different rectangle,
 * or picks the middle of its band by a slightly different rule, would tip a
 * different set -- and a test that only asked for "no tips" would be green
 * for a mask that painted the whole picture black.
 *
 * The three, from PLAN_ANDROID_DESIGN.md 3.2, with the readers that tip on each:
 *
 * | frame | readers |
 * |---|---|
 * | `passive/bond-not-the-middle_102953.png` | `find_buttons(BLUE)`, `recognise` |
 * | `tall/main_bubble_1080x1920_a.png` | the same two |
 * | `passive/unclear_130011.png` | `meat_field_screen` and the plot readers |
 *
 * All three are the mask's own doing and not the dot's: a flat fill
 * completes a blue blob on the first two and lies over a bed on the third.
 * They are the price of the mask, measured, and they are why the mask is
 * "Pflicht und nicht sicher" (PLAN_ANDROID_DESIGN.md 3.2) rather than merely hygiene.
 *
 * **Nothing is drawn here, only masked**, and that is not a shortcut: the
 * Python probe draws the dot and then fills its rectangle, this fills the
 * rectangle of the raw frame, and the two pictures are the same bytes. They
 * have to be -- the fill is sampled only *outside* the rectangles and the
 * drawing is confined *inside* them, so the dot cannot reach the band that
 * decides its own replacement, and the fill then covers everything the dot
 * put there. Checked rather than argued, over 60 corpus frames of both
 * sources: identical on 60, different on 0.
 *
 * Two families are asked, dungeon and explore, because between them they
 * carry every reader the overlay measurement names. What the Python probe proved over all 75
 * readers this proves over these two -- and a mask that agreed here and
 * differed elsewhere would have to be filling the same pixels with different
 * numbers, which is not a thing a rounding difference can do. The readers
 * are asked through [OracleFamilies], the same loop the oracle tests use,
 * so that this test cannot ask a different question than they do.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverlayOracleTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private lateinit var dungeonOracle: JsonObject
    private lateinit var exploreOracle: JsonObject

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        dungeonOracle = read("oracle/dungeon.json")
        exploreOracle = read("oracle/explore.json")
    }

    private fun read(rel: String): JsonObject {
        val file = File(repo, rel)
        assertTrue(file.exists(), "no oracle at $file -- run gradlew :core:writeOracle")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    // ------------------------------------------------------------------ geometry

    /**
     * The dot's square, against the table PLAN_ANDROID_DESIGN.md 3.2 prints: the same
     * arithmetic on both sides, or the mask is over the wrong place before
     * any reader is asked.
     *
     * Three of the four frame shapes cover the same field to a ten
     * thousandth, which is what says the number is the game's and not a
     * window's; the fourth is the long display, where the screen's edge sits
     * 0.085 further in.
     */
    @Test
    fun `the dot stands where the probe puts it`() {
        val cases = listOf(
            // w, h, x, y, size
            intArrayOf(1080, 1920, 954, 188, 126),
            intArrayOf(1080, 2340, 954, 243, 126),
            intArrayOf(805, 1390, 674, 172, 88),
            intArrayOf(573, 1056, 506, 140, 67))
        for (c in cases) {
            val box = Dot.square(c[0], c[1], left = false, fy = Dot.DEFAULT_FY)
            assertEquals(c[2].toDouble(), Math.round(box.x).toDouble(), 0.6,
                         "x on ${c[0]} x ${c[1]}")
            assertEquals(c[3].toDouble(), Math.round(box.y).toDouble(), 0.6,
                         "y on ${c[0]} x ${c[1]}")
            assertEquals(c[4].toDouble(), Math.round(box.w).toDouble(), 0.6,
                         "size on ${c[0]} x ${c[1]}")
            assertEquals(box.w, box.h, 1e-9, "the dot is square")
        }
    }

    /**
     * **The plate stops where the quest loop starts reading.**
     *
     * The corpus sweep of 2026-09-21 (`gradlew :core:overlayProbe`,
     * PLAN_ANDROID_DESIGN.md 3.1) priced the plate's height: 18 dp costs 34
     * tipped frames of 773, 20 dp costs 95, and 42 of those 61 are
     * `quest.stage_number`. The reason is arithmetic rather than luck --
     * the plate, centred on the dot at the measured place, ends within a
     * thousandth of [Quest.STAGE_BAND]'s top edge -- and this is that
     * arithmetic, on every frame shape the corpus has, so that a plate
     * grown by a later hand goes red here with the reason beside it
     * instead of quietly costing sixty frames.
     *
     * The other band, the Special Summon tab row at fy 0.115 to 0.155
     * (27 `general_tab` answers in `oracle/summon.json`), is not a fence
     * this can draw: the plate's lower third already lies over that row
     * and the reader finds its pair anyway. What that row prices is
     * *width*, and width has no edge to test against -- 128 dp tips no
     * `general_tab` and 160 dp tips three, which is a measurement and not
     * a boundary.
     */
    @Test
    fun `the plate ends where the stage band begins`() {
        val shapes = listOf(1080 to 1920, 1080 to 2340, 805 to 1390, 573 to 1056)
        val bandTop = Quest.STAGE_BAND[2]
        for ((w, h) in shapes) {
            val r = Dungeon.gameRectWh(w, h)
            val dot = Dot.square(w, h, left = false, fy = Dot.DEFAULT_FY)
            val plate = Dot.plate(w, h, left = false, fy = Dot.DEFAULT_FY)
            // Back into the vocabulary every number here is written in: a
            // fraction of the window, as Dot.square reads its fy.
            val bottom = (plate.y + plate.h - r.y0) / r.gh
            val top = (plate.y - r.y0) / r.gh
            println("$w x $h: plate fy %.4f to %.4f, stage band starts %.4f, %.4f to spare"
                        .format(top, bottom, bandTop, bandTop - bottom))
            assertTrue(bottom <= bandTop,
                       "on $w x $h the plate reaches fy $bottom, into Quest.STAGE_BAND at $bandTop " +
                           "-- the sweep prices that at 95 tipped frames against 34")
            // And it is the dot's own row: the two are one thing to look at,
            // and the mask has one band to sample around them.
            assertEquals(dot.y + dot.h / 2.0, plate.y + plate.h / 2.0, 1.0,
                         "the plate is not centred on the dot on $w x $h")
        }
    }

    /** The fill is the middle element of the sorted band, and nothing else. */
    @Test
    fun `the mask fills with the middle of its band`() {
        val img = Mat.zeros(200, 200, org.opencv.core.CvType.CV_8UC3)
        // A band of known values around a square: 60 % at 10, 40 % at 200,
        // so the middle element is 10 whatever the mean would say.
        for (y in 0 until 200) for (x in 0 until 200)
            img.put(y, x, byteArrayOf(if (y < 160) 10 else 200.toByte(),
                                      if (y < 160) 10 else 200.toByte(),
                                      if (y < 160) 10 else 200.toByte()))
        val box = Dot.Box(90.0, 90.0, 20.0)
        Dot.mask(img, listOf(box))
        val got = img.get(100, 100)
        assertEquals(10.0, got[0], 0.0, "the fill is the band's middle element, not its mean")
    }

    // ------------------------------------------------------------------ the corpus

    @Test
    fun `the dot at its measured place tips exactly the frames named below`() {
        assertEquals(dungeonOracle["frames"]!!.jsonObject.keys, exploreOracle["frames"]!!.jsonObject.keys,
                     "the two oracles cover different frames")
        val vision = Vision(ClassPathAssets)
        val mask: (Mat) -> Unit = { img ->
            Dot.mask(img, listOf(Dot.square(img.cols(), img.rows(), left = false, fy = Dot.DEFAULT_FY)))
        }
        // The two families through the same loop the oracle tests use, on
        // the masked picture: a difference of any kind -- another answer, or
        // a call asked on one side only because a reader it hangs on moved
        // -- is a tip of that reader on that frame.
        val tipped = sortedMapOf<String, MutableSet<String>>()
        var checked = 0
        for ((family, oracle) in listOf(OracleFamilies.DUNGEON to dungeonOracle,
                                        OracleFamilies.EXPLORE to exploreOracle)) {
            val report = OracleFamilies.check(family, oracle, repo, vision, prepare = mask)
            checked += report.checked.values.sum()
            for (m in report.mismatches) {
                tipped.getOrPut(m.path) { sortedSetOf() }.add("${family.name}.${OracleFamilies.readerName(m.key)}")
            }
        }
        println("frames: ${dungeonOracle["frames"]!!.jsonObject.size}, answers checked: $checked")
        for ((path, readers) in tipped) println("  tipped: $path  $readers")

        assertEquals(EXPECTED.keys, tipped.keys,
                     "the mask tips a different set of frames than the overlay probe measured")
        for ((path, readers) in EXPECTED)
            assertTrue(tipped[path]!!.containsAll(readers),
                       "$path was expected to tip $readers, and tipped ${tipped[path]}")
    }

    private companion object {
        /** PLAN_ANDROID_DESIGN.md 3.2, named. Nothing else in the corpus may move. */
        val EXPECTED = mapOf(
            "corpus/passive/bond-not-the-middle_102953.png" to
                setOf("dungeon.find_buttons", "dungeon.recognise"),
            "corpus/passive/unclear_130011.png" to
                setOf("explore.meat_field_screen"),
            "corpus/tall/main_bubble_1080x1920_a.png" to
                setOf("dungeon.find_buttons", "dungeon.recognise"),
            // A main screen, and a reader of 2026-09-23 that is only ever
            // asked on a dungeon panel: Network Defense Ops' counter above
            // its panel (Dungeon.HEADER_COUNTER), whose crop reaches the
            // dot's left edge. On the two netdef panels the dot moves nothing;
            // the plate at DEFAULT_FY does, and that is written beside the
            // reader.
            "corpus/passive/feature-bond-friendship_ 2026-08-24 191344.png" to
                setOf("dungeon.header_tickets"))
    }
}
