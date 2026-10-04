package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The preset pages on a display taller than the canvas ceiling
 * (PLAN_FORMATE.md V4, group 4), on the tour's own frames: the Digivice bar
 * and its list at the top, the Skill Cards bar at the bottom and its list's
 * heads in the middle ([Preset.Place.anchor], [Preset.WIDE_LIST]). A target
 * is tapped in the rectangle it carries, as PresetSkill.tap does, and what
 * says it landed is the picture under it: the arrow's blue, the row's blue,
 * the tick box's blue. The Digivice bar is whole only at 40 and 80 rows of
 * headroom; at 180 and more its top rows are above the cut, which V4's fifth
 * group mends.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PresetAnchorFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val compact = listOf("720x1600_none40", "1440x3200_none80")
    private val wide = listOf("1080x2520_none180", "1644x3840_none278", "720x1600_none40")
    private val frames = HashMap<String, Mat>()

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val dir = File(repo, "${OracleFamilies.CORPUS}/formats")
        for ((screens, formats) in listOf(listOf("preset_compact", "preset_compact_list") to compact,
                                          listOf("preset_wide", "preset_wide_list") to wide)) {
            for (format in formats) for (screen in screens) {
                val f = dir.listFiles()!!.filter { it.name.startsWith("${screen}_${format}_") }.minBy { it.name }
                frames["$screen@$format"] = OracleFamilies.read(f)
            }
        }
    }

    @AfterAll
    fun release() = frames.values.forEach(Mat::release)

    private fun frame(screen: String, format: String) = frames.getValue("$screen@$format")

    /** PresetSkill.tap's arithmetic: the target in the rectangle it carries. */
    private fun px(img: Mat, t: Explore.Target): Pair<Int, Int> {
        val r = Dungeon.gameRect(img, t.anchor)
        return Py.roundInt(r.x0 + t.fx * r.gw) to Py.roundInt(r.y0 + t.fy * r.gh)
    }

    private fun share(img: Mat, at: Pair<Int, Int>, band: Dungeon.Hsv, r: Int = 8): Double {
        val (x, y) = at
        val sub = img.submat(maxOf(0, y - r / 2), minOf(img.rows(), y + r / 2),
                             maxOf(0, x - r), minOf(img.cols(), x + r))
        val hsv = Cv.hsv(sub)
        val mask = Cv.inRange(hsv, band)
        val out = Core.countNonZero(mask).toDouble() / (mask.rows() * mask.cols())
        hsv.release(); mask.release()
        return out
    }

    @Test
    fun `the Digivice bar and its rows are read and tapped at the top`() {
        for (format in compact) {
            val closed = frame("preset_compact", format)
            val (place, bar) = assertNotNull(Preset.place(closed), format)
            assertEquals(Preset.Place.DIGIVICE, place, format)
            assertEquals(Dungeon.Anchor.TOP, bar.anchor, format)
            assertTrue(share(closed, px(closed, bar.arrow), Preset.ARROW_BLUE, r = 24) > 0.3, "$format: the arrow")
            val open = frame("preset_compact_list", format)
            val openBar = Preset.place(open)!!.second
            val rows = assertNotNull(Preset.compactRows(open, openBar), format)
            assertEquals(Preset.ROWS, rows.size, format)
            val row = Explore.Target((openBar.fx0 + openBar.fx1) / 2, rows[1].cy, openBar.anchor)
            // The row's band, a quarter of the way in, is its blue.
            val band = Explore.Target(openBar.fx0 + 0.25 * (openBar.fx1 - openBar.fx0), rows[1].cy, openBar.anchor)
            assertTrue(share(open, px(open, band), Preset.ARROW_BLUE) > 0.3, "$format: row 2 at ${px(open, row)}")
        }
    }

    /**
     * Is [at] inside a blob of [colour] whose box is [fw] wide and [fh] tall
     * in window fractions -- a tab or a tile whose middle carries text or art
     * of another colour.
     */
    private fun inBlob(img: Mat, at: Pair<Int, Int>, colour: Dungeon.Hsv, fw: DoubleArray, fh: DoubleArray): Boolean {
        val (_, _, gw, gh) = Dungeon.gameRect(img)
        val mask = Cv.hsvMask(img, colour)
        val (n, stats) = Cv.components(mask)
        mask.release()
        val (x, y) = at
        return (1 until n).any { i ->
            val (bx, by, bw, bh, _) = stats[i].toList()
            bw / gw.toDouble() in fw[0]..fw[1] && bh / gh.toDouble() in fh[0]..fh[1] &&
                x in bx until bx + bw && y in by until by + bh
        }
    }

    /**
     * A whole frame of the Overdrive measurement (PLAN_DAILY_LOST_SECTOR_PRESETS.md
     * DL1): `corpus/<dir>` first, then `staging/<dir>`, where DL1a left them
     * until DL1b moves them into the corpus under the same names; null where
     * neither has one.
     */
    private fun measured(dir: String, prefix: String): Mat? =
        listOf("${OracleFamilies.CORPUS}/$dir", "staging/$dir").asSequence()
            .mapNotNull { d -> File(repo, d).listFiles()?.filter { it.name.startsWith(prefix) }?.minByOrNull { it.name } }
            .firstOrNull()?.let { OracleFamilies.read(it) }

    /**
     * **Overdrive** (PLAN_DAILY_LOST_SECTOR_PRESETS.md 4.1 points 1 and 2):
     * its bar and the tab row under it stand at the bottom on every display,
     * the whole 1080 x 2520 frame with 180 rows of headroom and 720 x 1600
     * with 40 among them, and the icon is a place in the main screen's row of
     * tiles. What says a tap landed is the picture under it: the arrow's
     * blue, the open tab's cyan, the X's white plate, the tile's light blue.
     */
    @Test
    fun `the Overdrive bar, its tab, its X and its icon are tapped at the bottom`() {
        // The tile's light blue and its size (DL1: H 95-108, S 170-245, V 200
        // and over, 0.076 to 0.079 of the width, 0.043 to 0.044 tall): the
        // middle of the tile is its own pixel art, so the tap is held to the
        // tile's box rather than to the colour under it.
        val tile = Dungeon.Hsv(intArrayOf(95, 170, 200), intArrayOf(108, 245, 255))
        val pale = Dungeon.Hsv(intArrayOf(0, 0, 200), intArrayOf(179, 60, 255))
        for (format in listOf("1080x2520_none0", "720x1600_none0")) {
            val page = measured("formats", "overdrive_${format}_")
            val main = measured("formats", "main_${format}_")
            assumeTrue(page != null && main != null, "no Overdrive frames at $format in corpus/formats or staging/formats")
            try {
                assertTrue(Dungeon.headroom(page!!) > 0, "$format: a frame over the ceiling")
                val (place, bar) = assertNotNull(Preset.place(page), format)
                assertEquals(Preset.Place.OVERDRIVE, place, format)
                assertEquals(Dungeon.Anchor.BOTTOM, bar.anchor, format)
                val arrow = share(page, px(page, bar.arrow), Preset.ARROW_BLUE, r = 24)
                val tabs = assertNotNull(Preset.overdriveTabs(page), format)
                assertTrue(tabs[2].on, "$format: Drive Ability is the open tab")
                val x = assertNotNull(Summon.exitButton(page), format)
                val white = share(page, px(page, Explore.Target(x.fx, x.fy)), pale, r = 24)
                println("$format: arrow %.2f, X %.2f".format(arrow, white))
                assertTrue(arrow > 0.3, "$format: the arrow")
                assertTrue(inBlob(page, px(page, tabs[2].target), Preset.TAB_ON, Preset.TAB_W, Preset.TAB_H),
                           "$format: the Drive Ability tab at ${px(page, tabs[2].target)}")
                assertTrue(white > 0.1, "$format: the X")
                assertTrue(inBlob(main!!, px(main, PresetSkill.OVERDRIVE_ICON), tile,
                                  doubleArrayOf(0.07, 0.085), doubleArrayOf(0.038, 0.05)),
                           "$format: the Overdrive tile at ${px(main, PresetSkill.OVERDRIVE_ICON)}")
            } finally {
                page?.release(); main?.release()
            }
        }
    }

    @Test
    fun `the Skill Cards bar is the bottom's and its heads are the middle's`() {
        for (format in wide) {
            val closed = frame("preset_wide", format)
            val (place, bar) = assertNotNull(Preset.place(closed), format)
            assertEquals(Preset.Place.SKILL_CARDS, place, format)
            assertEquals(Dungeon.Anchor.BOTTOM, bar.anchor, format)
            val list = frame("preset_wide_list", format)
            val heads = Preset.headers(list)
            assertTrue(Preset.inTurn(heads), "$format: $heads")
            for (h in heads) {
                assertEquals(Dungeon.Anchor.MIDDLE, h.anchor, format)
                assertTrue(share(list, px(list, h.tick), Preset.TICK_BOX, r = 20) > 0.3 ||
                           share(list, px(list, h.tick), Dungeon.Hsv(intArrayOf(0, 0, 220), intArrayOf(179, 60, 255)), r = 20) > 0.1,
                           "$format: tick of slot ${h.slot} at ${px(list, h.tick)}")
            }
        }
    }
}
