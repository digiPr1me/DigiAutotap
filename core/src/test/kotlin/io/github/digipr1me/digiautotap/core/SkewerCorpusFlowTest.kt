package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SkewerSkill's own questions on the frames SK1 took on LDPlayer instance 0
 * (PLAN_SKEWER.md 4.2 point 4, "und den Korpus-Frames"): what the task makes
 * of a real guest served right, a real mistake, the round's end at the last
 * life, the pink "N Combo" -- through the one place a guest is judged
 * ([SkewerSkill.judge]) -- and where its tap beside the stage popup lands on
 * the real popup and on the real menu behind it.
 *
 * The frames go into `corpus/skewer/` with SK1b; until then this suite has
 * nothing to read and says so (a skip, not a pass). `SKEWER_FRAMES=<dir>`
 * in the environment points it at frames that are still staged.
 */
@Tag(OracleFamilies.CORPUS_TAG)
class SkewerCorpusFlowTest {

    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val dir = System.getenv("SKEWER_FRAMES")?.let(::File) ?: File(repo, "${OracleFamilies.CORPUS}/skewer")
    private val icons = SkewerIcons(ClassPathAssets)

    /**
     * SK1b put them in two places (staging/skewer/sk1b_move.txt): the 1920
     * frames of one screen each in `corpus/skewer/` under `<what>_<hhmmss>`,
     * without the staged `skewer_` in front, and the frames with a hole105
     * twin in `corpus/formats/` under their staged names. [prefix] is the
     * staged name's beginning, and finds both.
     */
    private fun frames(prefix: String): List<File> {
        val formats = File(repo, "${OracleFamilies.CORPUS}/formats")
        fun pick(d: File, p: String) =
            d.listFiles { f -> f.name.startsWith(p) && f.name.endsWith(".png") }.orEmpty().toList()
        return (pick(dir, prefix) + pick(dir, prefix.removePrefix("skewer_")) + pick(formats, prefix))
            .distinct().sortedBy { it.name }
    }

    private fun frame(prefix: String): Mat? = frames(prefix).firstOrNull()?.let { OracleFamilies.read(it) }

    private fun needFrames() = assumeTrue(frames("skewer_").isNotEmpty(), "no Chef's Special frames in $dir yet (SK1b)")

    @Test
    fun `real guests are judged right, wrong and not yet`() {
        needFrames()
        // A guest served right: the bubble gone, the plate empty, x3 kept.
        frame("skewer_served_")?.let { img ->
            val look = SkewerSkill.look(img, icons, livesBefore = 3)
            assertTrue(look.playing, "$look")
            assertEquals(null, look.order, "the bubble has gone")
            assertEquals(3, look.lives)
            assertEquals(SkewerIcons.Served.UNCLEAR, SkewerSkill.judge(look, SkewerSkill.Judging(3, true, 1.0)),
                         "not yet a second after Complete")
            assertEquals(SkewerIcons.Served.RIGHT,
                         SkewerSkill.judge(look, SkewerSkill.Judging(3, true, SkewerSkill.JUDGE_SETTLE)))
            img.release()
        }
        // A mistake: the bubble still up, the life gone at once (1920) or a frame late (2235).
        for (f in frames("skewer_mistake_") .filter { "gone" !in it.name }) {
            val img = OracleFamilies.read(f)
            val look = SkewerSkill.look(img, icons, livesBefore = 3)
            assertEquals(2, look.lives, f.name)
            assertEquals(SkewerIcons.Served.WRONG, SkewerSkill.judge(look, SkewerSkill.Judging(3, false, 0.5)), f.name)
            img.release()
        }
        // The last life: "Failed..." with x0 under it.
        frame("skewer_failed_lives0_")?.let { img ->
            val look = SkewerSkill.look(img, icons, livesBefore = 1)
            assertNotNull(look.over, "the round's end over the grill")
            assertEquals(SkewerIcons.Served.WRONG, SkewerSkill.judge(look, SkewerSkill.Judging(1, true, 3.0)))
            img.release()
        }
        // "Success!" with no life lost: the guest in its last second is not counted.
        frame("skewer_success_")?.let { img ->
            val look = SkewerSkill.look(img, icons, livesBefore = look0(img))
            assertNotNull(look.over)
            assertEquals(SkewerIcons.Served.UNCLEAR,
                         SkewerSkill.judge(look, SkewerSkill.Judging(look.lives!!, true, 3.5)))
            img.release()
        }
        // The pink "N Combo": RIGHT at once, from the frame itself, where its
        // glyphs read; faded over a white guest ("8 Combo" at 191652) it says
        // nothing, and never WRONG (notes/skewer.md, "One frame after
        // Complete says right, wrong or not yet").
        for (f in frames("skewer_combo")) {
            val img = OracleFamilies.read(f)
            val look = SkewerSkill.look(img, icons, livesBefore = look0(img))
            val v = SkewerSkill.judge(look, SkewerSkill.Judging(look.lives!!, false, 0.4))
            assertEquals(if (Skewer.comboGlyphs(img) >= 1) SkewerIcons.Served.RIGHT else SkewerIcons.Served.UNCLEAR,
                         v, f.name)
            img.release()
        }
        frame("skewer_combo2_")?.let { img ->
            assertTrue(Skewer.comboGlyphs(img) >= 1, "the fresh \"2 Combo\" reads")
            img.release()
        }
    }

    /** The lives a frame shows, as the task would have read them before Complete. */
    private fun look0(img: Mat): Int = icons.lives(img)!!.n

    @Test
    fun `the tap beside the stage popup is off the popup and off the menu's pennants`() {
        needFrames()
        // The popup's own blue, as SK2's probe found the popup by (H 100-118, S >= 150).
        val popups = frames("skewer_stage").filter { "opening" !in it.name }
        assertTrue(popups.isNotEmpty(), "no stage popup among ${dir.list()?.size} frames")
        for (f in popups) {
            val img = OracleFamilies.read(f)
            assertNotNull(Skewer.stage(img), f.name)
            val t = SkewerSkill.stageOutside(img)
            val r = Dungeon.gameRect(img)
            val x = Py.roundInt(r.x0 + t.fx * r.gw)
            val y = Py.roundInt(r.y0 + t.fy * r.gh)
            assertTrue(x in 0 until img.cols() && y in 0 until img.rows(), "${f.name}: $x,$y off the picture")
            val hsv = PaintSkewer.hsvAt(img, x, y)!!
            assertTrue(!(hsv[0] in 100..118 && hsv[1] >= 150),
                       "${f.name}: beside the popup at $x,$y is the popup's blue ${hsv.toList()}")
            assertTrue(t.fx < SkewerSkill.STAGE_POPUP_FX0, "${f.name}: $t")
            img.release()
        }
        for (f in frames("skewer_menu_")) {
            val img = OracleFamilies.read(f)
            val t = SkewerSkill.stageOutside(img)
            val r = Dungeon.gameRect(img)
            val hsv = PaintSkewer.hsvAt(img, Py.roundInt(r.x0 + t.fx * r.gw), Py.roundInt(r.y0 + t.fy * r.gh))!!
            // Skewer.PENNANT: H 0-14, S >= 160, V >= 180.
            val pennant = hsv[0] <= 14 && hsv[1] >= 160 && hsv[2] >= 180
            assertTrue(!pennant, "${f.name}: the place is on Play Game's pennant ${hsv.toList()}")
            img.release()
        }
    }
}
