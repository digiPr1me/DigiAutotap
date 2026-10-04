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
 * It is green when it tips **exactly the frames [EXPECTED] names** -- the three
 * the overlay measurement names, and the 51 of the format tours below them --,
 * and not when it tips none. A port whose mask fills a slightly different
 * rectangle, or picks the middle of its band by a slightly different rule,
 * would tip a different set -- and a test that only asked for "no tips" would
 * be green for a mask that painted the whole picture black.
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
            // 140 in the laboratory's table, whose square was fractional;
            // in the app's whole pixels (Dot.square, 2026-09-26) the disc is
            // 67 rather than 66.8 and the corner a row higher.
            intArrayOf(573, 1056, 506, 139, 67))
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

    /**
     * **The app's dot is the probe's dot, on every display** (notes/overlay.md,
     * "The overlay"; PLAN_FORMATE.md V2). The app puts the dot's centre at
     * [Dot.displayY] on the display and `grab` cuts the strip over the
     * camera off, so in the frame the readers get the dot has to stand
     * exactly where [Dot.square] puts it on that frame -- [Dot.DEFAULT_FY]
     * of the window, the vocabulary every count of this file and of the
     * overlay probe is in. Until 2026-09-26 it stood at DEFAULT_FY of the
     * frame, 0.1796 of the window, 48 px lower at 1920, and the plate in
     * the rows of [Quest.STAGE_BAND]; this is the test that would have
     * said so.
     *
     * At 1920 without a cutout that is display y 250.9, the square's
     * corner at 188 (the first test above), where the app stood at 299.
     */
    @Test
    fun `the app's dot stands where the probe measured it`() {
        // w, display height, the rows over the canvas: LDPlayer, the S26
        // Ultra (notes/formats.md, 100 to 105), the Poco F3 (78 to 80), LDPlayer's
        // own `hole` overlay at 1080 x 2340 (136), a 1080 x 2400 without a
        // cutout whose canvas has met its ceiling (60, Dungeon.canvasTop),
        // and the long display with nothing cut.
        val cases = listOf(intArrayOf(1080, 1920, 0), intArrayOf(1080, 2340, 100),
                           intArrayOf(1080, 2400, 79), intArrayOf(1080, 2340, 136),
                           intArrayOf(1080, 2400, 60), intArrayOf(1080, 2340, 0))
        for ((w, displayH, top) in cases) {
            val frameH = displayH - top
            val centre = Dot.displayY(Dot.DEFAULT_FY, w, displayH, top) - top
            val square = Dot.square(w, frameH, left = false, fy = Dot.DEFAULT_FY)
            val plate = Dot.plate(w, frameH, left = false, fy = Dot.DEFAULT_FY)
            val r = Dungeon.gameRectWh(w, frameH)
            fun window(y: Double) = (y - r.y0) / r.gh
            println(("$w x $displayH, canvas from $top: dot centre at display y %.1f, fy %.4f of the " +
                     "window; plate %.4f to %.4f of the window").format(centre + top, window(centre),
                     window(plate.y), window(plate.y + plate.h)))
            // The app's window corner: Math.round(displayY - touch / 2),
            // back in the frame; the probe's square has to be that square.
            assertEquals(Math.round(centre - square.h / 2.0).toDouble(), square.y, 0.0,
                         "on $w x $displayH the app's dot is not where the probe masks it")
            assertEquals(square.y + (square.h.toInt() - plate.h.toInt()) / 2, plate.y, 0.0,
                         "on $w x $displayH the plate is not where the app puts it")
            assertEquals(Dot.DEFAULT_FY,
                         Dot.fyAt(Dot.displayY(Dot.DEFAULT_FY, w, displayH, top), w, displayH, top), 1e-12,
                         "fyAt is not displayY backwards")
        }
        assertEquals(250.9, Dot.displayY(Dot.DEFAULT_FY, 1080, 1920, 0), 0.05,
                     "at 1920 the dot's centre is not at the measured place")
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
        val mask: (Mat) -> Unit = { img ->
            Dot.mask(img, listOf(Dot.square(img.cols(), img.rows(), left = false, fy = Dot.DEFAULT_FY)))
        }
        // The two families through the same loop the oracle tests use, on
        // the masked picture, each frame read and masked once for both: a
        // difference of any kind -- another answer, or a call asked on one
        // side only because a reader it hangs on moved -- is a tip of that
        // reader on that frame.
        val tipped = sortedMapOf<String, MutableSet<String>>()
        var checked = 0
        val files = listOf(OracleFamilies.DUNGEON to dungeonOracle, OracleFamilies.EXPLORE to exploreOracle)
        for ((file, report) in files.zip(OracleFamilies.check(files, repo, prepare = mask))) {
            val family = file.first
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
        val EXPECTED: Map<String, Set<String>> by lazy { mapOf(
            "corpus/passive/bond-not-the-middle_102953.png" to
                setOf("dungeon.find_buttons", "dungeon.recognise"),
            "corpus/passive/unclear_130011.png" to
                setOf("explore.meat_field_screen"),
            "corpus/tall/main_bubble_1080x1920_a.png" to
                setOf("dungeon.find_buttons", "dungeon.recognise")) +
            // Network Defense Ops' counter above its panel
            // (Dungeon.HEADER_COUNTER) tipped here on two screens it is never
            // asked on, a bond frame and a main screen at 1080 x 2412. Both
            // left with V16 (PLAN_FORMATE.md 8): a counter needs its "2" right
            // of the slash, and what the crop read there had none, so it
            // answers null masked and unmasked alike.
            formats(LIST_CORNER + PRESET_PAGE + WIDE_ELSEWHERE, "dungeon.find_buttons", "dungeon.recognise") }

        private fun formats(names: List<String>, vararg readers: String) =
            names.associate { "corpus/formats/$it.png" to readers.toSet() }

        /**
         * The phone tour of 2026-09-26/27 (PLAN_FORMATE.md V12), 51 frames,
         * each one looked at: the price of the mask on a display taller than
         * 9:16, not a finding about the dot's place.
         *
         * On such a display the canvas is cut at both sides and the dot stays
         * flush with the screen's edge, so the dot comes nearer the game's
         * content than it does at 1920. The first card of the dungeon list
         * ends at x 944 of 1080 at 1920, ten pixels short of the dot's square
         * (954); on the S26's 1080 x 2235 it ends at 1010, and the square lies
         * over the card's top right corner, 56 x 27 px. The flat fill cuts
         * the corner off the blue blob: in `find_buttons` its fx and fw move
         * by 0.0005 to 0.0031, and nothing else does -- `recognise` says
         * `liste` with the same cards and no Attempt, ad, Clear or Party on
         * all 32, `list_cards` and every budget read as unmasked. Tipped on
         * all 18 bottom-of-list frames taller than 9:16 and on 14 of the 20
         * top-of-list ones; the other six, 720 x 1560 and the V3 rows 2520
         * and 2640 among them, miss the corner by the rounding of a pixel.
         */
        val LIST_CORNER = listOf(
            "dungeon_list_bottom_1080x2160_none0_225152", "dungeon_list_bottom_1080x2220_none0_225205",
            "dungeon_list_bottom_1080x2280_hole100_225211", "dungeon_list_bottom_1080x2340_hole105_225224",
            "dungeon_list_bottom_1080x2400_hole80_225236", "dungeon_list_bottom_1080x2412_hole100_225255",
            "dungeon_list_bottom_1080x2424_hole100_225301", "dungeon_list_bottom_1080x2520_none180_225314",
            "dungeon_list_bottom_1080x2640_hole300_225327", "dungeon_list_bottom_1344x2992_hole124_225307",
            "dungeon_list_bottom_1440x2880_none0_225159", "dungeon_list_bottom_1440x3120_hole140_225230",
            "dungeon_list_bottom_1440x3200_hole133_225249", "dungeon_list_bottom_1440x3200_none80_225339",
            "dungeon_list_bottom_1644x3840_none278_225320", "dungeon_list_bottom_720x1560_hole67_225217",
            "dungeon_list_bottom_720x1600_hole67_225242", "dungeon_list_bottom_720x1600_none40_225333",
            "dungeon_list_top_1080x2160_none0_224911", "dungeon_list_top_1080x2220_none0_224924",
            "dungeon_list_top_1080x2280_hole100_224930", "dungeon_list_top_1080x2340_hole105_224942",
            "dungeon_list_top_1080x2400_hole80_224955", "dungeon_list_top_1080x2412_hole100_225014",
            "dungeon_list_top_1080x2424_hole100_225020", "dungeon_list_top_1344x2992_hole124_225027",
            "dungeon_list_top_1440x2880_none0_224917", "dungeon_list_top_1440x3120_hole140_224949",
            "dungeon_list_top_1440x3200_hole133_225008", "dungeon_list_top_1440x3200_none80_225059",
            "dungeon_list_top_1644x3840_none278_225040", "dungeon_list_top_720x1600_hole67_225001",
            // The tablet tour of 2026-09-27 (PLAN_FORMATE.md 9): the Pixel
            // Fold's outer 1080 x 2092 is covered like every long phone, and
            // its two lists tip the same way.
            "dungeon_list_bottom_1080x2092_none0_153307", "dungeon_list_top_1080x2092_none0_153149",
            // The daily dungeon's format rows of 2026-09-29
            // (PLAN_DAILY_LOST_SECTOR_PRESETS.md 4.1 point 8): the S26's
            // 1080 x 2235 and 1080 x 2340 without a cutout, the same corner;
            // their whole 2520 frame misses it, as the V3 rows do.
            "dungeon_list_bottom_1080x2235_none0_015148", "dungeon_list_bottom_1080x2340_none0_014702")

        /**
         * The tablet tour of 2026-09-27 (PLAN_FORMATE.md 9). On a display
         * wider than the game the dot stands flush with the display's edge,
         * in the background beside the canvas, and dot, gap and plate are
         * fractions of the canvas (Dot.shownWidth): the eight tablet and
         * inner-Fold lists tip nothing. What is left are the dungeon readers
         * on two screens they are never asked on, Skill Cards' list at
         * 1200 x 1920 (`Director.PRESET` first) and the Gekkomon page at
         * 2076 x 2152 (`event_page`), where the plate reaches the canvas's
         * right-hand column.
         */
        val WIDE_ELSEWHERE = listOf(
            "preset_wide_list_1200x1920_none0_154219", "runner_page_2076x2152_none0_152649")

        /**
         * The dungeon readers on a preset page, where they are never asked:
         * `Director.PRESET` stands before every skill's screen. The Skill
         * Cards page's frame, a blue blob 0.70 x 0.17, loses its top right
         * corner to the dot and falls out of `find_buttons` (on 10 frames;
         * at 1920 only the plate beside the dot reaches it, which is the
         * same blob on `corpus/preset/skillcards_closed_103101` in the
         * overlay probe's count); on the widest displays the compact list's
         * first two or three rows reach under the dot (5 frames). The
         * preset readers' own price is the overlay probe's, not this test's
         * (PLAN_FORMATE.md V13).
         */
        val PRESET_PAGE = listOf(
            "preset_compact_list_1344x2992_hole124_231301", "preset_compact_list_1440x3120_hole140_231224",
            "preset_compact_list_1440x3200_hole133_231243", "preset_compact_list_1440x3200_none80_231332",
            "preset_compact_list_1644x3840_none278_231314",
            "preset_wide_1080x2400_hole80_231524", "preset_wide_1080x2412_hole100_231543",
            "preset_wide_1080x2424_hole100_231549", "preset_wide_1080x2520_none180_231601",
            "preset_wide_1080x2640_hole300_231614", "preset_wide_1344x2992_hole124_231555",
            "preset_wide_1440x3200_hole133_231536", "preset_wide_1440x3200_none80_231627",
            "preset_wide_1644x3840_none278_231608", "preset_wide_720x1600_none40_231620")
    }
}
