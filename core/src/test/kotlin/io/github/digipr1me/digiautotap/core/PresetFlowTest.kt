package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PresetSkill played on the real frames of 2026-09-25 (corpus/preset), and
 * Overdrive's of 2026-09-28/29 (PLAN_DAILY_LOST_SECTOR_PRESETS.md DL1; in
 * staging/preset until DL1b moves them into corpus/preset): a
 * world of named frames and the taps that lead from one to the next, each
 * tap's target taken from where it was tapped on LDPlayer that day. A tap
 * that lands on no target leaves the frame as it is and is counted as a
 * stray -- the skill taps only what it has read, so a pass has none.
 *
 * What it cannot say: how long the game takes to answer, and how far a
 * drag really moves a wide list -- a drag here moves it to the one frame
 * that was taken after one. Those are the live run's.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PresetFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val frames = HashMap<String, Mat>()

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val dir = File(repo, "corpus/preset")
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".png") }) {
            frames[f.name.substringBeforeLast('_')] = Imgcodecs.imread(f.path)
        }
        // The Overdrive frames of PLAN_DAILY_LOST_SECTOR_PRESETS.md DL1 wait
        // in staging/preset until DL1b moves them into corpus/preset under the
        // same names: the corpus is asked first, staging/ only for a name the
        // corpus does not have, so this reads the same before and after.
        for (f in File(repo, "staging/preset").listFiles()?.filter { it.name.endsWith(".png") } ?: emptyList()) {
            frames.getOrPut(f.name.substringBeforeLast('_')) { Imgcodecs.imread(f.path) }
        }
        frames["explore_menu"] = Imgcodecs.imread(File(repo, "corpus/explore/ws_menu.png").path)
    }

    /** Every Overdrive frame the flow below walks through (DL1, 2026-09-28/29, LDPlayer 1080 x 1920). */
    private val overdriveFrames = listOf(
        "overdrive_base", "overdrive_closed", "overdrive_open", "overdrive_slot1", "overdrive_open_slot1",
        "overdrive_back", "overdrive_open_slot2")

    /** Skip, saying why, where neither corpus/preset nor staging/preset holds them. */
    private fun needOverdrive() = assumeTrue(overdriveFrames.all { it in frames },
        "the Overdrive frames are in neither corpus/preset nor staging/preset: " +
            overdriveFrames.filter { it !in frames })

    @AfterAll
    fun release() = frames.values.forEach(Mat::release)

    /** A tap target in window space, or a drag's direction. */
    private class Edge(val from: String, val to: String, val fx: Double = 0.0, val fy: Double = 0.0,
                       val drag: Int = 0)

    private inner class World(start: String, private val edges: List<Edge>,
                              private val overlay: Boolean = false) : Capture {
        var at = start
        val taps = ArrayList<String>()
        var strays = 0
        private val rect = Dungeon.gameRectWh(1080, 1920)

        /**
         * With [overlay], the app's dot and plate masked into every frame at
         * [dotFy], as `grab` hands them to the readers, and moved the way
         * `Overlay.clearOf` moves them ([Dot.clearFy]) when either stands on
         * the rows asked for, back to [home] on [overlayBack].
         */
        var home = Dot.DEFAULT_FY
        var dotFy = home
        val overlayCalls = ArrayList<String>()
        private fun boxes(fy: Double) = listOf(Dot.square(1080, 1920, false, fy), Dot.plate(1080, 1920, false, fy))
        override fun overlayClear(fy0: Double, fy1: Double) {
            overlayCalls += "clear %.3f-%.3f".format(fy0, fy1)
            Dot.clearFy(dotFy, fy0, fy1, 1080, 1920, 0)?.let { dotFy = it; overlayCalls += "moved to %.4f".format(it) }
        }
        override fun overlayBack() {
            overlayCalls += "back"
            dotFy = home
        }

        /** Frames handed out before [at], one per grab: a screen on its way somewhere. */
        val lead = ArrayDeque<String>()
        override fun grab(): Mat =
            frames[lead.removeFirstOrNull() ?: at]!!.clone().also { if (overlay) Dot.mask(it, boxes(dotFy)) }
        /**
         * The Overdrive page's Drive Ability tab as the game shows it now:
         * the slot a switch left there is what the next visit finds. Found
         * on slot 2 ("2 FullStack") on LDPlayer, as DL1 did.
         */
        var drive = "overdrive_closed"

        override fun tap(x: Int, y: Int) {
            val fx = (x - rect.x0) / rect.gw.toDouble(); val fy = (y - rect.y0) / rect.gh.toDouble()
            val e = edges.firstOrNull { it.from == at && it.drag == 0 && abs(it.fx - fx) <= TOL && abs(it.fy - fy) <= TOL }
            if (e == null) {
                strays += 1
                taps += "stray %.3f/%.3f on %s".format(fx, fy, at)
                return
            }
            val to = if (e.to == DRIVE) drive else e.to
            if (to in DRIVE_CLOSED) drive = to
            taps += "${e.from} -> $to"
            at = to
        }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {
            val dir = if (y2 < y1) 1 else -1
            val e = edges.firstOrNull { it.from == at && it.drag == dir } ?: return
            taps += "${e.from} ~> ${e.to}"
            at = e.to
        }
        override fun back() {}
        override fun inFront(): String? = "game"
    }

    companion object {
        const val TOL = 0.03
        /** An edge's target that is whichever Drive Ability frame the world holds ([World.drive]). */
        const val DRIVE = "@drive"
        /** The Drive Ability tab with its list closed: on slot 2, on slot 1, on slot 2 again. */
        val DRIVE_CLOSED = setOf("overdrive_closed", "overdrive_slot1", "overdrive_back")
    }

    private fun target(img: String, read: (Mat) -> Explore.Target?): Explore.Target =
        read(frames[img]!!) ?: error("no target read on $img")

    /** Every place, closed -> open -> slot 2, and every way home, as it went on LDPlayer. */
    private fun edges(): List<Edge> {
        val main = "main_digivice_icon"
        val x = { img: String -> target(img) { Summon.exitButton(it)?.let { b -> Explore.Target(b.fx, b.fy) } } }
        val globe = { img: String -> target(img) { Dungeon.homeButton(it)?.let { b -> Explore.Target(b.fx, b.fy) } } }
        val nav = { dx: Double -> target(main) { Explore.navTab(it, dx)?.let { t -> Explore.Target(t.fx, t.fy) } } }
        fun e(from: String, to: String, t: Explore.Target) = Edge(from, to, t.fx, t.fy)
        fun row(place: Preset.Place, open: String, n: Int): Explore.Target {
            val bar = Preset.place(frames[open]!!)!!.second
            return Explore.Target((bar.fx0 + bar.fx1) / 2, Preset.compactRows(frames[open]!!, bar)!![n - 1].cy)
        }
        fun tick(open: String, n: Int) = Preset.headers(frames[open]!!).first { it.slot == n }.tick
        val menuGlobe = globe("explore_menu")
        // Overdrive (PLAN_DAILY_LOST_SECTOR_PRESETS.md DL1, 2026-09-28/29):
        // the icon opens the page on Base, the third tab is Drive Ability and
        // carries the compact bar; there it went 2 -> 1 -> 2, each switch
        // with the list opened again afterwards; home by the X from any tab.
        val od = Preset.Place.OVERDRIVE
        val overdrive = if (!overdriveFrames.all { it in frames }) emptyList() else listOf(
            e(main, "overdrive_base", PresetSkill.OVERDRIVE_ICON),
            e("overdrive_base", DRIVE, target("overdrive_base") { Preset.overdriveTabs(it)?.get(2)?.target }),
            e("overdrive_closed", "overdrive_open", od.arrow),
            e("overdrive_open", "overdrive_closed", od.arrow),
            e("overdrive_open", "overdrive_slot1", row(od, "overdrive_open", 1)),
            e("overdrive_slot1", "overdrive_open_slot1", od.arrow),
            e("overdrive_open_slot1", "overdrive_slot1", od.arrow),
            e("overdrive_open_slot1", "overdrive_back", row(od, "overdrive_open_slot1", 2)),
            e("overdrive_back", "overdrive_open_slot2", od.arrow),
            e("overdrive_open_slot2", "overdrive_back", od.arrow),
            e("overdrive_open_slot2", "overdrive_slot1", row(od, "overdrive_open_slot2", 1)),
        ) + listOf("overdrive_base", "overdrive_closed", "overdrive_slot1", "overdrive_back").map { e(it, main, x(it)) }
        return overdrive + listOf(
            // Digivice and Tactical Memory, from the main screen and back by the X.
            e(main, "digivice_closed", PresetSkill.DIGIVICE_ICON),
            e("digivice_closed", "digivice_open", Preset.Place.DIGIVICE.arrow),
            e("digivice_open", "digivice_closed", Preset.Place.DIGIVICE.arrow),
            e("digivice_open", "digivice_slot2", row(Preset.Place.DIGIVICE, "digivice_open", 2)),
            e("digivice_closed", main, x("digivice_closed")),
            e("digivice_slot2", main, x("digivice_slot2")),
            e(main, "tactical_closed", PresetSkill.TACTICAL_ICON),
            e("tactical_closed", "tactical_open", Preset.Place.TACTICAL.arrow),
            e("tactical_open", "tactical_slot2", row(Preset.Place.TACTICAL, "tactical_open", 2)),
            e("tactical_slot2", main, x("tactical_slot2")),
            // Food Effects through the Explore menu, home by the X and the globe.
            e(main, "explore_menu", nav(Explore.NAV_EXPLORE_DX)),
            e("explore_menu", "food_closed", PresetSkill.EAT_CARD),
            e("food_closed", "food_open", Preset.Place.FOOD.arrow),
            e("food_open", "food_slot2", row(Preset.Place.FOOD, "food_open", 2)),
            e("food_slot2", "explore_menu", x("food_slot2")),
            e("explore_menu", main, menuGlobe),
            // Skill Cards: the Tamer tab opens on it.
            e(main, "skillcards_closed", nav(PresetSkill.NAV_TAMER_DX)),
            e("skillcards_closed", "skillcards_open", Preset.Place.SKILL_CARDS.arrow),
            e("skillcards_open", "skillcards_slot2", tick("skillcards_open", 2)),
            e("skillcards_slot2", main, globe("skillcards_slot2")),
            // Support Digimon: the Digimon tab opens on Buddy.
            e(main, "digimon_tab_buddy", nav(Explore.NAV_DIGIMON_DX)),
            e("digimon_tab_buddy", "support_closed", PresetSkill.SUPPORT_SUBTAB),
            e("support_closed", "support_open", Preset.Place.SUPPORT.arrow),
            e("support_open", "support_closed", Preset.Place.SUPPORT.arrow),
            e("support_open", "support_slot2", tick("support_open", 2)),
            e("support_slot2", main, globe("support_slot2")),
            e("support_closed", main, globe("support_closed")),
            // The wide list scrolled: one drag up shows 6 to 9, another the bottom.
            Edge("support_open", "support_scrolled", drag = 1),
            Edge("support_scrolled", "support_bottom", drag = 1),
            Edge("support_bottom", "support_scrolled", drag = -1),
            Edge("support_scrolled", "support_open", drag = -1),
            e("support_scrolled", "support_closed", Preset.Place.SUPPORT.arrow),
            // A tick on an empty row that did not take: the bar still says Bosses.
            e("support_scrolled", "support_closed", tick("support_scrolled", 8)),
        )
    }

    // The five places this world was painted for on 2026-09-25, and Overdrive,
    // the sixth (PLAN_DAILY_LOST_SECTOR_PRESETS.md DL2), whose edges run over
    // corpus/preset/overdrive_* since 2026-09-29: the two "every place" cases
    // ask all six (DL1b had narrowed them to five before DL2's edges existed;
    // DL5b merged the two and kept DL2's six).

    private fun skill(world: World, slots: Map<Preset.Place, Int>, log: MutableList<String>) =
        PresetSkill(world, { PresetSkill.Settings(slots) }, log = { log += it }, on = { true },
                    sleep = {})

    @Test
    fun `every place goes to slot 2 and the pass comes home`() {
        needOverdrive()
        val world = World("main_digivice_icon", edges())
        world.drive = "overdrive_slot1"
        val log = ArrayList<String>()
        val s = skill(world, Preset.Place.values().associateWith { 2 }, log)
        val out = s.run()
        println(world.taps.joinToString("\n"))
        println(log.joinToString("\n"))
        assertEquals(Result.DONE, out.result, out.toString())
        assertEquals(0, world.strays, "stray taps")
        assertEquals("main_digivice_icon", world.at)
        assertEquals(mapOf("switched" to 6), s.lastCounts)
        assertEquals("overdrive_back", world.drive, "Overdrive on slot 2")
    }

    /**
     * Two pauses in one pass (PLAN_RELEASE_1_3.md B4): the Digivice and
     * Tactical Memory to slot 2. The switch goes off as the Digivice's list
     * opens, and again as Tactical Memory's does. Each time the pass stops
     * with the list open -- the director's `preset` -- and goes on from there:
     * home from the place it stopped in, the way every visit goes home, and
     * the places that are left. Each place switched once, the card's parts
     * adding up to two, home at the end.
     */
    @Test
    fun `Presets paused twice set each place once and come home`() {
        val main = "main_digivice_icon"
        val extra = listOf(
            Edge("tactical_open", "tactical_closed", Preset.Place.TACTICAL.arrow.fx, Preset.Place.TACTICAL.arrow.fy),
            target("tactical_closed") { Summon.exitButton(it)?.let { b -> Explore.Target(b.fx, b.fy) } }
                .let { Edge("tactical_closed", main, it.fx, it.fy) })
        val world = World(main, edges() + extra)
        var on = true
        var offAt = "digivice_open"
        val hand = object : Capture by world {
            override fun tap(x: Int, y: Int) {
                world.tap(x, y)
                if (world.at == offAt) on = false
            }
        }
        val log = ArrayList<String>()
        val s = PresetSkill(hand, { PresetSkill.Settings(mapOf(Preset.Place.DIGIVICE to 2, Preset.Place.TACTICAL to 2)) },
                            log = { log += it }, on = { on }, sleep = {})
        val switched = ArrayList<Int>()

        val first = s.run()
        assertEquals(Result.STOPPED, first.result, log.joinToString("\n"))
        assertEquals("digivice_open", world.at, "the list stands where the pause found it")
        switched += s.lastCounts["switched"] ?: 0
        val open = world.grab()
        assertEquals(Director.PRESET, Director.classify(open).screen)
        assertTrue(s.resumesOn(Director.PRESET, open))

        on = true
        offAt = "tactical_open"
        val second = s.resume(open, whole = true)
        assertEquals(Result.STOPPED, second.result, log.joinToString("\n"))
        assertEquals("tactical_open", world.at)
        switched += s.lastCounts["switched"] ?: 0
        val again = world.grab()
        assertTrue(s.resumesOn(Director.classify(again).screen, again))

        on = true
        offAt = ""
        val third = s.resume(again, whole = true)
        println(world.taps.joinToString("\n"))
        println(log.joinToString("\n"))
        assertEquals(Result.DONE, third.result, log.joinToString("\n"))
        switched += s.lastCounts["switched"] ?: 0
        assertEquals(2, switched.sum(), "each place once: $switched")
        assertEquals(0, world.strays, "stray taps: ${world.taps}")
        assertEquals(main, world.at, "home at the end")
        assertEquals(1, world.taps.count { it == "digivice_open -> digivice_slot2" }, "${world.taps}")
        assertEquals(1, world.taps.count { it == "tactical_open -> tactical_slot2" }, "${world.taps}")
    }

    /**
     * **Overdrive, the sixth place** (PLAN_DAILY_LOST_SECTOR_PRESETS.md DL2):
     * the icon, the Base page it always opens on, the Drive Ability tab read
     * off its row, the bar, the list, row 1 -- and a second pass back to 2,
     * the way DL1 went on LDPlayer. The card counts each pass, not both.
     */
    @Test
    fun `Overdrive goes to slot 1 over its Drive Ability tab and back to 2 on the next pass`() {
        needOverdrive()
        val world = World("main_digivice_icon", edges())
        val log = ArrayList<String>()
        var slots = mapOf(Preset.Place.OVERDRIVE to 1)
        val s = PresetSkill(world, { PresetSkill.Settings(slots) }, log = { log += it }, on = { true }, sleep = {})
        val first = s.run()
        println(world.taps.joinToString("\n"))
        println(log.joinToString("\n"))
        assertEquals(Result.DONE, first.result, first.toString())
        assertEquals(mapOf("switched" to 1), s.lastCounts)
        assertEquals("overdrive_slot1", world.drive)
        assertTrue(world.taps.first() == "main_digivice_icon -> overdrive_base" &&
                   "overdrive_base -> overdrive_closed" in world.taps, "${world.taps}")
        assertTrue(log.any { it == "presets: Overdrive set to slot 1" }, log.joinToString("\n"))

        world.taps.clear()
        slots = mapOf(Preset.Place.OVERDRIVE to 2)
        val second = s.run()
        println(world.taps.joinToString("\n"))
        assertEquals(Result.DONE, second.result, second.toString())
        assertEquals(mapOf("switched" to 1), s.lastCounts, "the second pass counts itself only")
        assertEquals("overdrive_back", world.drive)
        assertTrue("overdrive_base -> overdrive_slot1" in world.taps, "${world.taps}")
        assertTrue(log.any { it == "presets: Overdrive set to slot 2" }, log.joinToString("\n"))
        assertEquals(0, world.strays, "stray taps")
        assertEquals("main_digivice_icon", world.at)
    }

    @Test
    fun `Overdrive on its slot already is left, and the list is closed again`() {
        needOverdrive()
        val world = World("main_digivice_icon", edges())
        val log = ArrayList<String>()
        val s = skill(world, mapOf(Preset.Place.OVERDRIVE to 2), log)
        val out = s.run()
        println(world.taps.joinToString("\n"))
        assertEquals(Result.DONE, out.result, out.toString())
        assertEquals(mapOf("switched" to 0), s.lastCounts)
        assertTrue(log.any { it == "presets: Overdrive is on slot 2 already" }, log.joinToString("\n"))
        assertEquals(listOf("main_digivice_icon -> overdrive_base", "overdrive_base -> overdrive_closed",
                            "overdrive_closed -> overdrive_open", "overdrive_open -> overdrive_closed",
                            "overdrive_closed -> main_digivice_icon"), world.taps)
        assertEquals(0, world.strays)
    }

    /**
     * **The dot on the Overdrive bar** (PresetSkill.clearFor). At 1920 the
     * bar is lost with the dot anywhere from fy 0.130 down the player's
     * strip, its default place among it; the skill moves the dot off the bar
     * and the list before it taps the icon, and back once it is home.
     */
    @Test
    fun `the dot on the Overdrive bar is moved off it before the page opens`() {
        needOverdrive()
        val closed = frames["overdrive_closed"]!!.clone()
        Dot.mask(closed, listOf(Dot.square(1080, 1920, false, Dot.DEFAULT_FY),
                                Dot.plate(1080, 1920, false, Dot.DEFAULT_FY)))
        assertNull(Preset.place(closed), "the dot at its default place takes the bar")
        assertEquals(3, Preset.overdriveTabs(closed)?.size, "the tab row stays readable")
        closed.release()

        val world = World("main_digivice_icon", edges(), overlay = true)
        val log = ArrayList<String>()
        val s = skill(world, mapOf(Preset.Place.OVERDRIVE to 1), log)
        val out = s.run()
        println(world.overlayCalls.joinToString("\n"))
        assertEquals(Result.DONE, out.result, out.toString() + "\n" + log.joinToString("\n"))
        assertEquals(mapOf("switched" to 1), s.lastCounts)
        assertEquals(listOf("clear 0.139-0.579", "moved to 0.0600", "back"), world.overlayCalls)
        assertEquals(Dot.DEFAULT_FY, world.dotFy)
        assertEquals(0, world.strays)
    }

    /**
     * **With the app's own dot in the frame** (PLAN_FORMATE.md V13). At the
     * measured place the plate lies on the first row of the Digivice list,
     * and the masked list reads as not open; the skill moves the dot off the
     * rows for the one list it stands on and puts it back.
     */
    @Test
    fun `the dot is moved off the Digivice list and back, and every place still switches`() {
        val bar = Preset.place(frames["digivice_open"]!!)!!.second
        val masked = frames["digivice_open"]!!.clone()
        Dot.mask(masked, listOf(Dot.square(1080, 1920, false, Dot.DEFAULT_FY),
                                Dot.plate(1080, 1920, false, Dot.DEFAULT_FY)))
        assertEquals(null, Preset.compactRows(masked, bar), "the plate no longer covers the first row")
        masked.release()

        needOverdrive()
        val world = World("main_digivice_icon", edges(), overlay = true)
        world.drive = "overdrive_slot1"
        val log = ArrayList<String>()
        val s = skill(world, Preset.Place.values().associateWith { 2 }, log)
        val out = s.run()
        println(world.overlayCalls.joinToString("\n"))
        assertEquals(Result.DONE, out.result, out.toString() + "\n" + log.joinToString("\n"))
        assertEquals(0, world.strays, "stray taps")
        assertEquals(mapOf("switched" to 6), s.lastCounts)
        assertEquals(Dot.DEFAULT_FY, world.dotFy, "the dot is back at its place")
        // One clear and one back for each of the four compact places, Overdrive the fourth.
        assertEquals(4, world.overlayCalls.count { it.startsWith("clear") }, world.overlayCalls.toString())
        assertEquals(4, world.overlayCalls.count { it == "back" }, world.overlayCalls.toString())
    }

    /**
     * **The bar under the dot** (PLAN_FORMATE.md V14). At 1920 the Digivice
     * bar (rows 138 to 203) is lost with the dot at 0.100 to 0.130 of the
     * player's strip, and the page is not even known; the skill moves the
     * dot off the bar and the list before it taps the icon.
     */
    @Test
    fun `the dot on the Digivice bar is moved off it before the page opens`() {
        val closed = frames["digivice_closed"]!!.clone()
        Dot.mask(closed, listOf(Dot.square(1080, 1920, false, 0.11), Dot.plate(1080, 1920, false, 0.11)))
        assertNull(Preset.place(closed), "the dot at 0.11 takes the bar")
        closed.release()

        val world = World("main_digivice_icon", edges(), overlay = true)
        world.home = 0.11; world.dotFy = 0.11
        val log = ArrayList<String>()
        val s = skill(world, mapOf(Preset.Place.DIGIVICE to 2), log)
        val out = s.run()
        println(world.overlayCalls.joinToString("\n"))
        assertEquals(Result.DONE, out.result, out.toString() + "\n" + log.joinToString("\n"))
        assertEquals(mapOf("switched" to 1), s.lastCounts)
        assertEquals(listOf("clear 0.099-0.539", "moved to 0.0600", "back"), world.overlayCalls)
        assertEquals(0.11, world.dotFy)
    }

    @Test
    fun `a slot that is on already is left and counted as such`() {
        // The Digivice shows "1 Bosses", Support Digimon has its tick on 3.
        val world = World("main_digivice_icon", edges())
        val log = ArrayList<String>()
        val s = skill(world, mapOf(Preset.Place.DIGIVICE to 1, Preset.Place.SUPPORT to 3), log)
        val out = s.run()
        println(world.taps.joinToString("\n"))
        assertEquals(Result.DONE, out.result, out.toString())
        assertEquals(0, world.strays)
        assertEquals(mapOf("switched" to 0), s.lastCounts)
        assertEquals(2, log.count { "already" in it && "is on slot" in it }, log.joinToString("\n"))
        assertTrue(world.taps.none { "slot2" in it }, "nothing switched: ${world.taps}")
    }

    @Test
    fun `a slot further down is scrolled to, and a tick that did not take is said`() {
        // Row 8 is below the first view; the tap on its tick changes nothing
        // here, the row has no name, and the tick on the second look is off.
        val world = World("main_digivice_icon", edges())
        val log = ArrayList<String>()
        val s = skill(world, mapOf(Preset.Place.SUPPORT to 8), log)
        val out = s.run()
        println(world.taps.joinToString("\n"))
        println(log.joinToString("\n"))
        assertEquals(Result.PARKED, out.result)
        assertTrue("Support Digimon" in out.why, out.why)
        assertTrue(world.taps.any { "support_open ~> support_scrolled" in it }, "scrolled: ${world.taps}")
        assertEquals(0, world.strays)
        assertEquals("main_digivice_icon", world.at)
    }

    @Test
    fun `a preset page the player opens is theirs, and no park`() {
        // Semi-automatic, the Presets task not in the list at all -- off or
        // locked -- and then in it: the page is named and nobody is handed it.
        for (skills in listOf(emptyList<Skill>(),
                              listOf(skill(World("x", emptyList()), mapOf(Preset.Place.DIGIVICE to 2), ArrayList())))) {
            val world = World("digivice_closed", edges())
            val log = ArrayList<String>()
            val d = DirectorLoop(world, skills, emptyList(), { "game" }, { SkillSettings.MODE_SEMI },
                                 Chain(emptyList()), log = { log += it }, now = { 0.0 })
            val t = d.tick()
            assertEquals(Director.PRESET, t.screen)
            assertNull(d.parked, "parked: $log")
            assertTrue(world.taps.isEmpty())
        }
    }

    /**
     * **One frame is not the start** (PLAN_FORMATE.md S4 round 5). Live on
     * LDPlayer 2026-09-27 "Switch to" parked with "Presets start from the
     * main screen" in the round the director had read `main`: the director
     * runs the task on the first main frame (MAIN moves by itself, its gate
     * is open at once), and the one frame `run` took next was not. Here the
     * director's frame is the main screen, the next one the Explore menu the
     * globe was closing, and then the main screen again.
     */
    @Test
    fun `a single frame off the main screen right after the director's does not park the start`() {
        assertNull(Dungeon.autoButton(frames["explore_menu"]!!), "the in-between frame is not a main screen")
        val world = World("main_digivice_icon", edges())
        world.lead.addAll(listOf("main_digivice_icon", "explore_menu"))
        val log = ArrayList<String>()
        val preset = skill(world, mapOf(Preset.Place.DIGIVICE to 2), log)
        val d = DirectorLoop(world, listOf(preset), emptyList(), { "game" }, { SkillSettings.MODE_SEMI },
                             Chain(emptyList()), log = { log += it }, now = { 0.0 })
        d.runNow = "preset"
        val t = d.tick()
        println(log.joinToString("\n"))
        assertEquals(Director.MAIN, t.screen)
        assertNull(d.parked, "parked: $log")
        assertEquals(mapOf("switched" to 1), preset.lastCounts)
        assertEquals(0, world.strays, "stray taps")
        assertEquals("main_digivice_icon", world.at)
    }

    /**
     * **"Try again" on a Presets park is Presets again** (the player's call,
     * 2026-09-27). Live, the same park as above was answered "Try again", and
     * the director went on with the main screen's rounds: the quest loop
     * entered DemiDevimon twice. Here the start parks for real -- the page
     * stays up for every look -- and the retry switches the Digivice, with
     * the quest loop never handed the screen.
     */
    @Test
    fun `Try again on a Presets park runs Presets, not the quest loop`() {
        val world = World("main_digivice_icon", edges())
        world.lead.add("main_digivice_icon")
        repeat(PresetSkill.START_ROUNDS) { world.lead.add("digivice_closed") }
        val log = ArrayList<String>()
        val preset = skill(world, mapOf(Preset.Place.DIGIVICE to 2), log)
        val quest = DirectorTest.Counting("quest", "the quest loop", Director.MAIN)
        val d = DirectorLoop(world, listOf(preset), listOf(quest), { "game" }, { SkillSettings.MODE_SEMI },
                             Chain(emptyList()), log = { log += it }, now = { 0.0 })
        d.runNow = "preset"
        d.tick()
        assertEquals("Presets parked: Presets start from the main screen", d.parked, log.toString())
        d.retry()
        val t = d.tick()
        println(log.joinToString("\n"))
        assertEquals("run preset", t.did, "$t")
        assertNull(d.parked, "parked: $log")
        assertEquals(mapOf("switched" to 1), preset.lastCounts)
        assertEquals(0, quest.worked, "the quest loop was handed the screen")
        assertEquals(0, world.strays, "stray taps")
    }

    @Test
    fun `nothing starts off the main screen`() {
        val world = World("digivice_closed", edges())
        val out = skill(world, mapOf(Preset.Place.DIGIVICE to 2), ArrayList()).run()
        assertEquals(Result.PARKED, out.result)
        assertTrue(world.taps.isEmpty())
    }

    /**
     * PLAN_RELEASE_1_3.md B73, live on instance 1 on 2026-10-01: the first
     * visit of the Digivice and of Tactical Memory on that account put the
     * game's Help tutorial over the page (corpus/preset/help_*, the frames
     * the director kept). Presets tapped the icon's constant three times --
     * twice into the tutorial --, read no bar, kept `preset_no_way_home` and
     * parked "could not get home". Now the icon is tapped again only where
     * the main screen still stands, the tutorial ends the pass with its own
     * sentence, nothing is tapped on it and no frame is kept; the director
     * reads it as `help`. Two passes, two pages: the same each time.
     */
    @Test
    fun `the game's Help over a first visit's page ends the pass with its sentence and nothing tapped on it`() {
        val main = "main_digivice_icon"
        for ((place, icon, help) in listOf(
                Triple(Preset.Place.DIGIVICE, PresetSkill.DIGIVICE_ICON, "help_digivice"),
                Triple(Preset.Place.TACTICAL, PresetSkill.TACTICAL_ICON, "help_tactical"))) {
            assumeTrue(help in frames, "$help is not in corpus/preset")
            for (pass in 0 until 2) {
                val world = World(main, listOf(Edge(main, help, icon.fx, icon.fy)))
                val log = ArrayList<String>()
                val kept = ArrayList<String>()
                val s = PresetSkill(world, { PresetSkill.Settings(mapOf(place to 1)) }, log = { log += it },
                                    keep = { _, tag -> kept += tag }, on = { true }, sleep = {})
                val out = s.run()
                assertEquals(Result.PARKED, out.result, log.joinToString("\n"))
                assertEquals("the game's Help tutorial is open over ${place.label}; I tap nothing in it -- " +
                             "go through it yourself, then ask for the presets again", out.why)
                assertEquals(listOf("$main -> $help"), world.taps, "$help, pass $pass: one tap, the icon's")
                assertEquals(0, world.strays, "$help, pass $pass: ${world.taps}")
                assertTrue(kept.isEmpty(), "$help, pass $pass: $kept")
                assertTrue(log.contains("presets: ${place.label} not set to slot 1 -- " +
                                        "the game's Help tutorial came up over its page"), log.toString())
                assertEquals(Director.HELP, Director.classify(frames[help]!!).screen)
            }
        }
    }
}
