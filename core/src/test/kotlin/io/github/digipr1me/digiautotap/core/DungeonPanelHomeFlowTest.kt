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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A dungeon's panel that a round or a step of the fully automatic mode left
 * standing, taken home by Dungeons' own way (PLAN_ABSCHLUSS_1_3.md A5i,
 * question 22).
 *
 * Live on LDPlayer instance 0 on 2026-10-03 (A5g, look 5): after a look into
 * this app, the first round back on the main screen was the quest loop's,
 * which opened DemiDevimon's panel ("Fight! DemiDevimon", Attempt at
 * 18:14:24) and lost its frame there. Back in the game the panel read
 * `dialog`, no task works on a dialog, the globe is not read under it, and
 * the second hand parked -- "The dialog is open after the quest loop, and I
 * found no way home from it." at 18:14:53 --, three tries, ten minutes, and
 * again, until the player closed the panel.
 *
 * Nothing here replaces a reader or the skill: the director is the real one,
 * Dungeons the real [DungeonSkill], and [Game] hands out the format tour's
 * own frames of one session -- the main screen, the panel, the pink prompt
 * the back key raises over it, the list -- and changes them only where the
 * game would: the back key on the panel raises the prompt, OK on the prompt
 * is the list, the globe on the list is the main screen. What a tap hit is
 * read off the picture under it, with the reader that found the button: OK,
 * the globe -- and Attempt and Clear Previous Difficulty, which spend a
 * ticket and must never be hit. The round and the step are stand-ins
 * ([DirectorTest.Counting]) that put the panel up and lose their frame as
 * the quest loop did, the stand-in in both of the director's lists as the
 * quest loop is.
 *
 * Two formats: 1080 x 1920, the live run's, and 1080 x 2520 with 180 rows
 * of headroom, where the prompt stands in the middle and its OK is tapped in
 * the middle's rectangle -- which needs the headroom of the frame the way
 * home began on.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DungeonPanelHomeFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val formats = listOf("1080x1920_none0", "1080x2520_none180")
    private val frames = HashMap<String, Mat>()

    /** The screens of the world, by the name the tour gave their frames. */
    private enum class Shown(val file: String) {
        MAIN("main"), PANEL("dungeon_panel"), PROMPT("popup_exit"), LIST("dungeon_list_top")
    }

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val dir = File(repo, "${OracleFamilies.CORPUS}/formats")
        // The tour of the evening, 22:16 to 22:56: one session for each format.
        for (format in formats) for (screen in Shown.values()) {
            val f = dir.listFiles()!!.filter { it.name.startsWith("${screen.file}_${format}_") }.maxBy { it.name }
            frames["$screen@$format"] = OracleFamilies.read(f)
        }
    }

    @AfterAll
    fun release() = frames.values.forEach(Mat::release)

    /** The game behind the frames of one format, and what each tap and back key met. */
    private inner class Game(val format: String) {
        val cap = DirectorTest.FakeCapture()
        var shown = Shown.MAIN
        /** "back on PANEL", "OK at x,y", "globe at x,y", "Attempt at x,y", ... in order. */
        val hits = ArrayList<String>()

        init {
            cap.scene = { OracleFamilies.copy(frame(shown)) }
            cap.onBack = {
                hits += "back on $shown"
                shown = when (shown) {
                    Shown.PANEL -> Shown.PROMPT
                    Shown.PROMPT -> Shown.PANEL
                    else -> shown
                }
            }
            cap.onTap = { x, y -> tapped(x, y) }
        }

        fun frame(s: Shown): Mat = frames.getValue("$s@$format")

        /** Is [x],[y] on [b], a button read off [img] at [anchor]? */
        private fun on(b: Dungeon.Button?, img: Mat, anchor: Dungeon.Anchor, x: Int, y: Int): Boolean {
            if (b == null) return false
            val r = Dungeon.gameRect(img, anchor)
            return abs(x - (r.x0 + b.fx * r.gw)) <= b.fw * r.gw / 2 &&
                abs(y - (r.y0 + b.fy * r.gh)) <= b.fh * r.gh / 2
        }

        private fun tapped(x: Int, y: Int) {
            val img = frame(shown)
            val rec = Dungeon.recognise(img)
            val hit = when (shown) {
                Shown.PROMPT -> if (on(rec.exitOk, img, rec.anchor, x, y)) "OK" else "beside OK"
                Shown.PANEL -> when {
                    on(rec.attempt, img, rec.anchor, x, y) -> "Attempt"
                    on(rec.clear, img, rec.anchor, x, y) -> "Clear Previous Difficulty"
                    else -> "on the panel"
                }
                Shown.LIST -> if (on(Dungeon.homeButton(img), img, Dungeon.Anchor.BOTTOM, x, y)) "globe"
                              else "on the list"
                Shown.MAIN -> "on the main screen"
            }
            hits += "$hit at $x,$y"
            shown = when {
                shown == Shown.PROMPT && hit == "OK" -> Shown.LIST
                // Cancel, or beside the prompt: it closes back to the panel.
                shown == Shown.PROMPT -> Shown.PANEL
                shown == Shown.LIST && hit == "globe" -> Shown.MAIN
                else -> shown
            }
        }
    }

    /** The real director over [game], with the real Dungeons and the quest loop's stand-in in both lists. */
    private class Run(val game: Game, mode: String, steps: List<String> = emptyList(), repeat: Boolean = false) {
        var t = 1000.0
        val log = ArrayList<String>()
        val kept = ArrayList<String>()
        val dungeons = DungeonSkill(game.cap, { DungeonSkill.Settings(budgets = mapOf(0 to 1)) },
                                    log = { log += "    [Dungeons] $it" }, on = { true },
                                    keep = { _, tag -> kept += tag }, sleep = { t += it }, now = { t })
        val quest = DirectorTest.Counting("quest", "the quest loop", Director.MAIN)
        val chain = Chain(steps.map { Chain.Step(it) }, repeat, log = { log += it })
        val director = DirectorLoop(game.cap, listOf(dungeons, quest), listOf(quest), { GAME }, { mode }, chain,
                                    on = { true }, log = { log += it }, keep = { _, tag -> kept += tag },
                                    now = { t })

        /**
         * Rounds until [seconds] have passed or [done]. Whenever a stand-in
         * has put this app in front, it stays there five seconds and the game
         * comes back -- the player holding the dot and going back in.
         */
        fun play(seconds: Double, done: () -> Boolean = { false }) {
            val until = t + seconds
            var since: Double? = null
            while (t < until && !done()) {
                if (game.cap.front == APP) {
                    val at = since ?: t.also { since = it }
                    if (t - at >= 5.0) {
                        game.cap.front = GAME
                        since = null
                    }
                }
                t += director.tick().beat
            }
        }
    }

    /** Taps and back keys that a way home from a panel must never send. */
    private fun wrong(hits: List<String>) = hits.filter {
        it.startsWith("Attempt") || it.startsWith("Clear Previous") || it.startsWith("beside OK") ||
            it.startsWith("on the") || it == "back on MAIN" || it == "back on LIST"
    }

    @Test
    fun `the tour's frames read as the screens the world plays them as`() {
        for (format in formats) {
            val g = Game(format)
            assertEquals(Director.MAIN, Director.classify(g.frame(Shown.MAIN)).screen, format)
            assertEquals(Director.DIALOG, Director.classify(g.frame(Shown.PANEL)).screen, format)
            assertEquals(Director.PROMPT, Director.classify(g.frame(Shown.PROMPT)).screen, format)
            assertEquals(Director.DUNGEON_LIST, Director.classify(g.frame(Shown.LIST)).screen, format)
            val panel = Dungeon.recognise(g.frame(Shown.PANEL))
            assertTrue(panel.attempt != null && panel.clear != null, "$format: Attempt beside Clear Previous Difficulty")
            assertEquals(null, Dungeon.homeButton(g.frame(Shown.PANEL)), "$format: the globe is not read under the panel")
        }
    }

    /**
     * A5g's look 5, twice. The quest loop's round opens the panel and loses
     * its frame on it in its first and its third round; back in the game the
     * second hand asks Dungeons, whose way home presses the back key on the
     * panel, OK on the prompt it raises and the globe on the list -- the way
     * every card of a pass leaves its panel -- and the main screen's rounds
     * go on. No park, no frame kept, nothing tapped on the panel. Red on the
     * director of A5g: "The dialog is open after the quest loop, and I found
     * no way home from it."; and at 2520 on a way home that took the
     * headroom of nothing, whose OK went out 90 rows under the button.
     */
    @Test
    fun `a panel a round left as it lost its frame is closed by Dungeons' way home, over two passes`() {
        for (format in formats) {
            val g = Game(format)
            val r = Run(g, SkillSettings.MODE_FULL)
            r.quest.onWork = {
                if (r.quest.worked == 1 || r.quest.worked == 3) {
                    g.shown = Shown.PANEL
                    g.cap.front = APP
                    throw CaptureError("the game is not in front ($APP)")
                }
            }
            r.play(300.0) { r.quest.worked >= 5 }
            assertEquals(2, r.log.count { it == "  the quest loop lost its frame in its round -- the game is not in front ($APP)" },
                         "$format: ${r.log}")
            assertEquals(2, r.log.count { it == "  chain: dialog left open after the quest loop; asking Dungeons to leave" },
                         "$format: ${r.log}")
            assertEquals(2, r.log.count {
                it == "  chain: back on the main screen after the quest loop; going on with the main screen's round"
            }, "$format: ${r.log}")
            assertTrue(r.log.none { it.contains("parked on") || it.contains("no way home") }, "$format: ${r.log}")
            assertNull(r.director.parked, "$format: ${r.log}")
            assertEquals(Shown.MAIN, g.shown)
            assertEquals(listOf("back on PANEL", "OK", "globe", "back on PANEL", "OK", "globe"),
                         g.hits.map { it.substringBefore(" at ") }, "$format: ${g.hits}")
            assertTrue(wrong(g.hits).isEmpty(), "$format: ${g.hits}")
            assertTrue(r.kept.isEmpty(), "$format: kept ${r.kept}")
            assertTrue(r.quest.worked >= 5, "$format: the rounds go on: ${r.log}")
        }
    }

    /**
     * The same for what a chain step leaves: the quest loop's step plays its
     * dungeon, and in each of two rounds of the chain its first run loses
     * its frame on the panel -- A5g's park, uncounted, then home by
     * Dungeons' way after PARK_RETRY -- and its second hands back with the
     * panel standing, which the second hand gives Dungeons once SETTLE is
     * over. Four runs, four ways home, never "no way home", never one of
     * N3's tries, nothing retired. Red on the director of A5g, which found
     * no way home from the panel and counted three tries.
     */
    @Test
    fun `a panel a chain step left is closed the same way, lost frame or not, over two rounds of the chain`() {
        val g = Game("1080x1920_none0")
        val r = Run(g, SkillSettings.MODE_FULL, listOf("quest"), repeat = true)
        r.quest.onRun = {
            g.shown = Shown.PANEL
            if (r.quest.ran % 2 == 1) {
                g.cap.front = APP
                throw CaptureError("the game is not in front ($APP)")
            }
        }
        // Until the fourth run's panel is gone: the chain's third round has begun by then.
        r.play(900.0) { r.quest.ran == 4 && g.shown == Shown.MAIN }
        assertEquals(4, r.quest.ran, r.log.toString())
        assertEquals(3, r.director.chain.round, r.log.toString())
        assertEquals(2, r.log.count { it.endsWith("-- going home and going on with the quest loop after the game came back") },
                     r.log.toString())
        assertEquals(2, r.log.count { it == "  chain: dialog left open after the park; asking Dungeons to leave" },
                     r.log.toString())
        assertEquals(2, r.log.count { it == "  chain: dialog left open after the quest loop; asking Dungeons to leave" },
                     r.log.toString())
        assertTrue(r.log.none {
            it.contains("no way home") || it.contains(" of ${Director.PARK_RETRIES})") || it.contains("retiring")
        }, r.log.toString())
        assertTrue(r.log.filter { it.contains("parked on") }.all { it.contains("the quest loop parked: no frame") },
                   r.log.toString())
        assertTrue(r.director.chain.retired.isEmpty(), r.director.chain.retired.toString())
        assertNull(r.director.parked, r.log.toString())
        assertEquals(Shown.MAIN, g.shown)
        assertEquals(4, g.hits.count { it == "back on PANEL" }, g.hits.toString())
        assertTrue(wrong(g.hits).isEmpty(), g.hits.toString())
        assertTrue(r.kept.isEmpty(), "kept ${r.kept}")
    }

    /**
     * What stays: a panel the player opened is theirs -- in the
     * semi-automatic mode, and in the fully automatic one before any step or
     * round left anything. A park that stands, and no back key and no tap.
     */
    @Test
    fun `a panel the player opened is theirs in either mode, and nothing is pressed on it`() {
        val semi = Game("1080x1920_none0")
        semi.shown = Shown.PANEL
        val s = Run(semi, SkillSettings.MODE_SEMI)
        s.play(Director.PARK_RETRY * 6)
        assertEquals("Something is in the way that I did not put there: dialog.", s.director.parked, s.log.toString())
        assertTrue(semi.hits.isEmpty(), semi.hits.toString())

        val full = Game("1080x1920_none0")
        full.shown = Shown.PANEL
        val f = Run(full, SkillSettings.MODE_FULL, listOf("dungeon"))
        f.play(Director.PARK_RETRY * 6)
        assertEquals("The fully automatic mode starts from the main screen, and dialog is open.",
                     f.director.parked, f.log.toString())
        assertTrue(f.log.none { it.contains("going home") }, f.log.toString())
        assertTrue(full.hits.isEmpty(), full.hits.toString())
    }

    /**
     * The witness Dungeons answers by ([DungeonSkill.leavesFrom]), on frames
     * of every kind of panel it leaves and of the dialogs it must not touch:
     * the back key it presses there, and the OK it gives the pink prompt
     * after it, are only right over a dungeon's panel -- on a screen with
     * nothing open the same prompt asks to return to the title.
     */
    @Test
    fun `Dungeons takes home from a dungeon's panel and from no other dialog`() {
        val dungeons = DungeonSkill(DirectorTest.FakeCapture(), { DungeonSkill.Settings(budgets = emptyMap()) },
                                    log = {}, on = { true })
        val yes = listOf(
            "formats/dungeon_panel_1080x1920_none0_225429",          // Attempt and Clear, 2/2
            "dungeon/panel_tickets_0of2_ad_demidevimon_122121",      // the film button alone, 0/2
            "tall/dungeon_panel_1080x2340_a",                        // Apocalymon's lone Attempt
            "dungeon/panel_party_netdef_155153",                     // Network Defense Ops with its party
            "dungeon/panel_ad_only_netdef_120145",                   // ... and with its film button
            "formats/daily_panel_1080x1920_none0_014300",            // the daily dungeon
            "dungeon/daily_panel_max_170523",                        // ... at its highest level
            "formats/dungeon_panel_1080x2520_none180_225556",        // over the ceiling
        )
        val no = listOf(
            "formats/lost_sector_panel_1080x1920_none0_014347",      // the tower's panel
            "summon/ad_limit_031057",                                // an OK pop-up
            "dungeon/login_bonus_over_list_085143",                  // the reset's windows over the list
            "dungeon/notices_over_list_085122",
            "dungeon/daily_panel_prefab_053129",                     // the daily panel's prefab, a second
        )
        for ((names, want) in listOf(yes to true, no to false)) for (name in names) {
            val img = OracleFamilies.read(File(repo, "${OracleFamilies.CORPUS}/$name.png"))
            assertEquals(Director.DIALOG, Director.classify(img).screen, name)
            assertEquals(want, dungeons.leavesFrom(Director.DIALOG, img), name)
            assertFalse(dungeons.leavesFrom(Director.UNKNOWN, img), "$name: only a dialog")
            img.release()
        }
        // The hologram device's gear window, which this list called "a window
        // of the Digimon page" until classify named it (PLAN_ABSCHLUSS_1_3.md
        // K2, 2026-10-04; the bond tour kept it on its way to that page): no
        // dialog any more, and nothing Dungeons leaves from either way.
        val gear = OracleFamilies.read(File(repo, "${OracleFamilies.CORPUS}/bond/open_the_Digimon_page_140453.png"))
        assertEquals(Director.GEAR, Director.classify(gear).screen)
        assertFalse(dungeons.leavesFrom(Director.GEAR, gear))
        assertFalse(dungeons.leavesFrom(Director.DIALOG, gear))
        gear.release()
    }

    companion object {
        const val GAME = "com.bandainamcoent.dgup_ww"
        const val APP = "io.github.digipr1me.digiautotap"
    }
}
