package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FormatProbe's rules, without the corpus: which names it reads, which
 * frames it pairs, where it cuts, and what it calls the same. The probe
 * itself is a sweep and no test; these are the parts of it that a wrong
 * line would turn into a quiet wrong report.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormatProbeTest {

    @TempDir lateinit var tmp: File

    @BeforeAll
    fun opencv() = System.loadLibrary(Core.NATIVE_LIBRARY_NAME)

    private fun shot(name: String, folder: String = "formats") =
        FormatProbe.parse(File(File(tmp, folder), name), name)

    @Test
    fun `both name forms are read, and nothing else`() {
        val old = assertNotNull(shot("dungeon_panel_ad_1080x2340_b.png", "tall"))
        assertEquals("dungeon_panel_ad", old.screen)
        assertEquals(2340, old.h); assertEquals(0, old.inset); assertEquals("b", old.take)
        assertEquals("1080x2340_none0", old.format)

        val new = assertNotNull(shot("dungeon_list_1080x2340_hole100_101512.png"))
        assertEquals("dungeon_list", new.screen)
        assertEquals("hole", new.cutout); assertEquals(100, new.inset); assertEquals("101512", new.take)
        assertFalse(new.isReference)
        assertTrue(assertNotNull(shot("main_1080x1920_none0_101530.png")).isReference)
        assertTrue(assertNotNull(shot("main_1080x1920_a.png", "tall")).isReference)

        assertNull(shot("dungeon_list_1080x1920_a.png.masked.png", "tall"))
        assertNull(shot("phone_tall_main_195731.png"))
    }

    @Test
    fun `a twin is the same screen in the same folder, in order of time`() {
        val shots = listOf(
            "main_1080x1920_none0_101500.png", "main_1080x1920_none0_101503.png",
            "main_1080x2340_hole100_102000.png", "main_1080x2340_hole100_102003.png",
            "dungeon_list_1080x1920_none0_101600.png", "dungeon_list_1080x1920_none0_103000.png",
            "dungeon_list_1080x2340_hole100_102900.png",
            "explore_1080x2340_hole100_102100.png",
        ).map { assertNotNull(shot(it)) } +
            listOf("main_1080x1920_a.png").map { assertNotNull(shot(it, "tall")) }
        val p = FormatProbe.pairUp(shots)
        val pairs = p.pairs.map { (r, h) -> h.label to r.label }.toMap()
        assertEquals("main_1080x1920_none0_101500.png", pairs["main_1080x2340_hole100_102000.png"])
        assertEquals("main_1080x1920_none0_101503.png", pairs["main_1080x2340_hole100_102003.png"])
        // Two references and one take: the nearer in time.
        assertEquals("dungeon_list_1080x1920_none0_103000.png", pairs["dungeon_list_1080x2340_hole100_102900.png"])
        assertEquals(listOf("explore_1080x2340_hole100_102100.png"), p.orphans.map { it.label })
        // corpus/tall's reference is not a twin for corpus/formats.
        assertEquals(3, p.pairs.size)
    }

    private fun picture(name: String, w: Int, h: Int): File {
        val m = Mat(h, w, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        // A white first row, so that the test can tell whether it was cut.
        m.row(0).setTo(Scalar(255.0, 255.0, 255.0))
        val f = File(tmp, name)
        Imgcodecs.imwrite(f.path, m)
        m.release()
        return f
    }

    @Test
    fun `the inset comes off a whole frame, and not off a cut one`() {
        val whole = picture("main_40x80_hole10_101512.png", 40, 80)
        val a = FormatProbe.load(FormatProbe.parse(whole, whole.name)!!)
        assertEquals(70, a.rows()); assertEquals(0.0, a.get(0, 0)[0]); a.release()

        val cut = picture("main_40x80_hole10_101513.png", 40, 70)
        val b = FormatProbe.load(FormatProbe.parse(cut, cut.name)!!)
        assertEquals(70, b.rows()); assertEquals(255.0, b.get(0, 0)[0]); b.release()

        val liar = picture("main_40x80_hole10_101514.png", 40, 75)
        assertFailsWith<IllegalArgumentException> { FormatProbe.load(FormatProbe.parse(liar, liar.name)!!) }

        // By hand, `pair a.png@10`, whatever the name says.
        val any = picture("anything.png", 40, 80)
        val s = FormatProbe.Shot(any, any.name, "anything", 40, 80, "pair", 10, "")
        val c = FormatProbe.load(s, forced = 10)
        assertEquals(70, c.rows()); c.release()
    }

    @Test
    fun `a key fed by another reader's number is matched by its place`() {
        val keys = listOf("list_cards", "badge_crop(fy=0.2813,fh=0.2081)", "badge_crop(fy=0.4712,fh=0.1162)",
                          "find_buttons(colour=BLUE,min_y=0.0)", "seed_selected(slot=seed_slots[1])")
        val n = FormatProbe.normalise(keys)
        assertEquals("badge_crop(fy=#,fh=#)#0", n["badge_crop(fy=0.2813,fh=0.2081)"])
        assertEquals("badge_crop(fy=#,fh=#)#1", n["badge_crop(fy=0.4712,fh=0.1162)"])
        assertEquals("seed_selected(slot=seed_slots[1])", n["seed_selected(slot=seed_slots[1])"])
        assertEquals("dungeon.badge_crop", FormatProbe.reader("dungeon.badge_crop(fy=#,fh=#)#1"))
    }

    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test
    fun `what the same means across two formats`() {
        fun differ(r: String, a: String, b: String) = FormatProbe.differ(r, j(a), j(b), null, "")
        // A place within the tolerance, and past it.
        assertNull(differ("dungeon.auto_button", """{"fx":0.3687,"fy":0.764}""", """{"fx":0.369,"fy":0.7646}"""))
        assertNotNull(differ("dungeon.auto_button", """{"fx":0.3687,"fy":0.764}""", """{"fx":0.3687,"fy":0.7719}"""))
        // A share has its own, wider one.
        assertNull(differ("quest.close_x", """{"plate":0.76}""", """{"plate":0.78}"""))
        // Counts and words exactly; a list by its length first.
        assertNotNull(differ("dungeon.card_budget", """{"tickets":2}""", """{"tickets":3}"""))
        assertNotNull(differ("director.classify", """{"screen":"dungeon_list"}""", """{"screen":"unknown"}"""))
        assertNotNull(differ("dungeon.list_cards", "[0.28,0.47]", "[0.28]"))
        // A pixel reader by whether it answered; ink area not at all.
        assertNull(differ("dungeon.badge_glyphs", "[{\"image\":[316,1556]},[[1],[2]]]", "[{\"image\":[384,1900]},[[1]]]"))
        assertNotNull(differ("dungeon.badge_crop", "{\"image\":[79,389,3]}", "null"))
        assertNull(differ("explore.nav_tab", """{"area":1422,"fx":0.2419}""", """{"area":2021,"fx":0.2414}"""))
        // A ratio relative to itself.
        assertNull(differ("quest.quest_card", """{"name_w":8.6173}""", """{"name_w":8.5}"""))
        assertNotNull(differ("quest.quest_card", """{"name_w":8.6}""", """{"name_w":8.0}"""))
    }

    @Test
    fun `a reader is held where the bot asks it`() {
        assertTrue(FormatProbe.isHome("director.classify", listOf(Director.BOARD)))
        assertTrue(FormatProbe.isHome("dungeon.home_button", listOf(Director.SUMMON)))
        assertFalse(FormatProbe.isHome("dungeon.find_buttons", listOf(Director.SUMMON)))
        assertTrue(FormatProbe.isHome("dungeon.find_buttons", listOf(Director.DUNGEON_LIST)))
        assertFalse(FormatProbe.isHome("explore.free_seeds", listOf(Director.MAIN)))
        assertTrue(FormatProbe.isHome("explore.nav_tab", listOf(Director.MAIN)))
        assertFalse(FormatProbe.isHome("vision.calibrate", listOf(Director.SUMMON)))
        // Either side's screen will do: a format that reads another screen is a finding in the director.
        assertTrue(FormatProbe.isHome("vision.calibrate", listOf(Director.SUMMON, Director.BOARD)))
        // Every state reader and every table entry names a reader the families have.
        // BY_NAME: every family, the ones still waiting for their oracle file among them.
        val readers = OracleFamilies.BY_NAME.values.flatMap { f -> f.readers.map { "${f.name}.$it" } }.toSet() +
            OracleFamilies.BY_NAME.keys
        for (r in FormatProbe.STATE.keys + FormatProbe.HOME.keys + FormatProbe.PIXEL_READERS + FormatProbe.SHARE_READERS)
            assertTrue(r in readers, "$r is not a reader of OracleFamilies")
    }
}
