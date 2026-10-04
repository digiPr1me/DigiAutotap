package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The game's windows after its daily reset under the director, on the
 * frames of the morning they were seen (PLAN_RELEASE_1_3.md B50 and B51,
 * 2026-10-01). On instance 1 that morning the game put up, one after the
 * other, "New Buddy Added" twice, "New Overdrive Added", Notices, a login
 * bonus and Idle Rewards (corpus/dungeon/news_*, *_over_list_*, taken over
 * the dungeon list). Until this the director read the news `unknown` and
 * stood still in the semi-automatic mode, Notices and the login bonus a
 * `dialog` it parked on, and the fully automatic mode parked the chain
 * every morning until the player closed them; only Dungeons' way back to
 * the list knew them (B44).
 *
 * The game here is a script of those frames ([Morning]): a window closes on
 * the tap that closes it in the game -- the OK of a news window, a tap high
 * up outside Notices and the login bonus (the laboratory closed Notices so;
 * the login bonus closing on it was never seen, and is this script's
 * premise), a tap beside Idle Rewards -- and any other tap is a stray.
 * After the last window the main screen of the same display
 * (corpus/tall/main_bubble_1080x1920_a). On the director before B50 every
 * case here fails: no OK goes out, and the chain never starts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NewsFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val loaded = HashMap<String, Mat>()

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    @AfterAll
    fun release() {
        loaded.values.forEach { it.release() }
    }

    private fun frame(path: String): Mat = frame(listOf(path))

    /** A frame by its places, the corpus first: the first that exists is read. */
    private fun frame(places: List<String>): Mat = loaded.getOrPut(places.first()) {
        // No corpus at all is the public copy of the source (release.py), where
        // these cases have nothing to say; a frame missing from it is a fault.
        assumeTrue(File(repo, "corpus").isDirectory, "no corpus in this checkout")
        val file = places.map { File(repo, it) }.firstOrNull { it.exists() }
        assertNotNull(file, "no frame at any of $places")
        OracleFamilies.read(file).also { assertTrue(!it.empty(), "could not read $file") }
    }

    private val buddy1 = "corpus/dungeon/news_buddy_084458.png"
    private val buddy2 = "corpus/dungeon/news_buddy_084503.png"
    private val overdrive = "corpus/dungeon/news_overdrive_084646.png"
    private val notices = "corpus/dungeon/notices_over_list_085122.png"
    private val login = "corpus/dungeon/login_bonus_over_list_085143.png"
    private val idle = "corpus/dungeon/idle_over_list_085202.png"
    private val home = "corpus/tall/main_bubble_1080x1920_a.png"
    /** Two older news the passive helper and the quest loop kept over the main screen. */
    private val overMain = listOf("corpus/passive/unclear_090849.png", "corpus/quest/no-x-to-get-out-with_084447.png")
    private val allNews = listOf(buddy1, buddy2, "corpus/dungeon/news_overdrive_coming_in_084507.png", overdrive) + overMain

    /** One window of the stack: its frame, what it is, and whether a tap at x, y closes it. */
    private class Window(val name: String, val img: Mat, val kind: String, val closes: (Int, Int) -> Boolean)

    /** A news window closes on its OK: the button [Startup.announcement] reads, in its rectangle. */
    private fun news(path: String): Window = news(listOf(path))

    private fun news(places: List<String>): Window {
        val img = frame(places)
        val ok = assertNotNull(Startup.announcement(img), places.first())
        val r = Dungeon.gameRect(img, Startup.ANNOUNCE_ANCHOR)
        val cx = r.x0 + ok.fx * r.gw; val cy = r.y0 + ok.fy * r.gh
        val hw = ok.fw * r.gw / 2; val hh = ok.fh * r.gh / 2
        return Window(places.first().substringAfterLast('/'), img, "news") { x, y ->
            kotlin.math.abs(x - cx) <= hw && kotlin.math.abs(y - cy) <= hh
        }
    }

    /**
     * Notices and the login bonus close on a tap above them: Notices' window
     * begins at 383 px, the login bonus's banner at about 340 (1080 x 1920).
     */
    private fun dialog(path: String): Window = dialog(listOf(path))

    private fun dialog(places: List<String>) =
        Window(places.first().substringAfterLast('/'), frame(places), "dialog") { _, y -> y < 330 }

    /**
     * The stack over the main screen as R3 saw it on instance 1 on
     * 2026-10-01, at the login after the install of 12:51 -- not after 08:00:
     * "New Buddy Added" at 12:57:38 and the login bonus at 12:59:58, the main
     * screen behind both, the dot paused (staging/release13; the plate on the
     * second said `main`, the look before it -- the frame reads `dialog` at
     * every place of the strip). The corpus first, should they be moved in.
     */
    private val newsOverMainLive = listOf("corpus/startup/news_buddy_over_main_125738.png",
                                          "staging/release13/news_buddy_paused_unknown_125738.png")
    private val loginOverMainLive = listOf("corpus/startup/login_bonus_over_main_125958.png",
                                           "staging/release13/login_bonus_paused_main_125958.png")

    /** Idle Rewards closes on a tap below the window (it ends at 1510 px) and above the nav bar. */
    private fun idleWindow(path: String) = Window(path.substringAfterLast('/'), frame(path), "idle") { _, y -> y in 1516..1759 }

    /** The morning of 2026-10-01, in the order the windows came. */
    private fun stack() = listOf(news(buddy1), news(buddy2), news(overdrive), dialog(notices), dialog(login), idleWindow(idle))

    /**
     * The game: the main screen until [begin], then the windows one by one,
     * each gone on the tap that closes it; [flashAfter] names windows after
     * which the main screen shows for one frame before the next comes in.
     */
    private class Morning(val windows: List<Window>, val home: Mat, val flashAfter: Set<String> = emptySet()) {
        var at = -1
        private var flash = 0
        val strays = ArrayList<String>()
        val closed = ArrayList<String>()
        fun begin() { at = 0 }
        val up: Window? get() = if (flash > 0) null else windows.getOrNull(at)
        fun frame(): Mat {
            if (flash > 0) { flash -= 1; return OracleFamilies.copy(home) }
            return OracleFamilies.copy(windows.getOrNull(at)?.img ?: home)
        }
        fun tap(x: Int, y: Int) {
            val w = up
            if (w == null) { strays += "the main screen at $x,$y"; return }
            if (!w.closes(x, y)) { strays += "${w.name} at $x,$y"; return }
            closed += w.name
            next(w)
        }
        /** The player closes the window in front by hand. */
        fun playerCloses() = next(windows[at])
        private fun next(w: Window) {
            at += 1
            if (at >= windows.size) at = -1
            if (w.name in flashAfter && at >= 0) flash = 1
        }
    }

    private fun rig(mode: String, morning: Morning, steps: List<String> = emptyList()): DirectorTest.Rig =
        DirectorTest.Rig(mode, steps).also { r ->
            r.cap.scene = { morning.frame() }
            r.cap.onTap = { x, y -> morning.tap(x, y) }
        }

    /** Rounds until [done] or [seconds] have passed. */
    private fun DirectorTest.Rig.until(seconds: Double, done: () -> Boolean): Director.Tick {
        val end = t + seconds
        var last = tick()
        while (!done() && t < end) last = tick()
        return last
    }

    @Test
    fun `every news frame reads as news, the stack's other windows as before, and the dot at any place moves neither`() {
        for (path in allNews) assertEquals(Director.NEWS, Director.classify(frame(path)).screen, path)
        assertEquals(Director.DIALOG, Director.classify(frame(notices)).screen)
        assertEquals(Director.DIALOG, Director.classify(frame(login)).screen)
        assertEquals(Director.CLAIM_REWARDS, Director.classify(frame(idle)).screen)
        assertEquals(Director.MAIN, Director.classify(frame(home)).screen)
        // B51: with the dot and its plate at fy 0.155, the lowest place of the
        // strip, the Overdrive news read `field_dialog` by the water popup;
        // the news is asked before the field's dialogs now.
        var fy = Dot.MEASURED_FY_MIN
        while (fy <= Dot.MEASURED_FY_MAX + 1e-9) {
            for (path in allNews) {
                val img = OracleFamilies.copy(frame(path))
                Dot.mask(img, listOf(Dot.square(img.cols(), img.rows(), false, fy),
                                     Dot.plate(img.cols(), img.rows(), false, fy)))
                assertEquals(Director.NEWS, Director.classify(img).screen, "$path under the dot at fy %.3f".format(fy))
                img.release()
            }
            fy += 0.005
        }
    }

    /**
     * Semi-automatic, two mornings: the three news are closed by their OK,
     * nothing else is tapped; Notices, the login bonus and Idle Rewards (the
     * Idle Rewards task is not in this rig) stand with the sentence that they
     * are the player's -- no park --, the player closes them, and the main
     * screen's rounds go on. The second morning the same: what the first
     * counted is not counted again.
     */
    @Test
    fun `semi-automatic, the reset's news are closed by their OK and the rest is left to the player, two mornings`() {
        val morning = Morning(stack(), frame(home))
        val r = rig(SkillSettings.MODE_SEMI, morning)
        r.run(4.0)
        assertTrue(r.passive.worked > 0, "the main screen's rounds before the reset")
        for (day in 1..2) {
            val roundsBefore = r.passive.worked
            morning.begin()
            r.until(40.0) { morning.at == 3 }
            assertEquals(3, morning.at, "day $day, the news closed: ${morning.closed} ${r.log}")
            assertEquals(listOf("news_buddy_084458.png", "news_buddy_084503.png", "news_overdrive_084646.png"),
                         morning.closed.takeLast(3), "day $day")
            assertTrue(r.log.count { it.contains("the game's news after its reset is up") } == day, "day $day: ${r.log}")
            for (left in listOf("Notices", "the login bonus", "Idle Rewards")) {
                val last = r.run(10.0)
                assertEquals(Director.RESET_LEFT_SAYS, last.note, "day $day, $left: ${r.log}")
                assertNull(r.director.parked, "day $day, $left: no park")
                morning.playerCloses()
            }
            r.run(10.0)
            assertTrue(r.passive.worked > roundsBefore, "day $day: the rounds go on after the stack")
        }
        assertEquals(6, r.cap.taps.size, "three OKs a morning and nothing else: ${r.cap.taps}")
        assertTrue(morning.strays.isEmpty(), "taps that closed nothing: ${morning.strays}")
        assertEquals(listOf("news", "news"), r.kept, "the first window of each morning")
        assertTrue(r.log.none { it.contains("in the way") }, r.log.toString())
    }

    /**
     * Fully automatic: the core comes up on the morning's stack over the main
     * screen, with a chain of two steps that has not begun -- every window is
     * closed its own way, and the chain starts on the main screen after it.
     * The second stack comes when the first step comes home, with the main
     * screen showing for a frame between the last news and Notices: the
     * chain holds there (Director.RESET_NEXT), closes the rest, and goes on
     * with its second step. Six taps a stack, each on what closes its window.
     */
    @Test
    fun `fully automatic, the reset's windows are closed one by one and the chain goes on, two stacks`() {
        val morning = Morning(stack(), frame(home), flashAfter = setOf("news_overdrive_084646.png"))
        val r = rig(SkillSettings.MODE_FULL, morning, listOf("dungeon", "summon"))
        var stacks = 0
        r.dungeons.onRun = { morning.begin(); stacks += 1 }
        morning.begin()
        stacks += 1
        r.until(120.0) { r.summons.ran > 0 }
        assertEquals(2, stacks, r.log.toString())
        assertEquals(1, r.dungeons.ran, "the chain's first step after the first stack: ${r.log}")
        assertEquals(1, r.summons.ran, "the chain goes on after the second stack: ${r.log}")
        assertEquals(12, r.cap.taps.size, "six taps a stack: ${r.cap.taps} ${r.log}")
        assertEquals(stack().map { it.name } + stack().map { it.name }, morning.closed)
        assertTrue(morning.strays.isEmpty(), "taps that closed nothing: ${morning.strays}")
        assertNull(r.director.parked, r.log.toString())
        assertTrue(r.log.count { it.contains("the game's windows after its reset are over: 6 closed") } == 2, r.log.toString())
        assertTrue(r.log.any { it.contains("the dialog is gone after the tap") }, r.log.toString())
        assertTrue(r.log.any { it.contains("Idle Rewards after the game's news -- closing it beside the window") }, r.log.toString())
        // The flash of the main screen between the news and Notices was held,
        // not taken for the end of the stack: the second step started only
        // after Idle Rewards was gone.
        val held = r.log.indexOfFirst { it.contains("waiting a moment for the next") }
        assertTrue(held >= 0, r.log.toString())
        assertTrue(r.log.indexOfLast { it.contains("Idle Rewards after the game's news") } <
                   r.log.indexOfFirst { it.contains("chain: starting Special Summon") }, r.log.toString())
    }

    /**
     * The stack over the main screen, on R3's live frames of the same day: a
     * news window and the login bonus, each over the main screen. Twice in
     * each mode. Semi-automatic: the OK, and the login bonus left to the
     * player with its sentence, the rounds after it. Fully automatic: the
     * OK and the tap high up, and the chain's step -- once before it, once
     * when the step brings the stack back.
     */
    @Test
    fun `over the main screen, as R3 saw it, both modes, twice`() {
        val main = frame(home)
        // Semi-automatic.
        run {
            val morning = Morning(listOf(news(newsOverMainLive), dialog(loginOverMainLive)), main)
            val r = rig(SkillSettings.MODE_SEMI, morning)
            for (pass in 1..2) {
                morning.begin()
                r.until(20.0) { morning.at == 1 }
                assertEquals(1, morning.at, "semi, pass $pass: ${r.log}")
                assertEquals(Director.RESET_LEFT_SAYS, r.run(6.0).note, "semi, pass $pass: ${r.log}")
                assertNull(r.director.parked, "semi, pass $pass")
                morning.playerCloses()
                val rounds = r.passive.worked
                r.run(12.0)
                assertTrue(r.passive.worked > rounds, "semi, pass $pass: the rounds after it")
            }
            assertEquals(2, r.cap.taps.size, "semi: an OK a pass, nothing else: ${r.cap.taps}")
            assertTrue(morning.strays.isEmpty(), "semi: ${morning.strays}")
        }
        // Fully automatic.
        run {
            val morning = Morning(listOf(news(newsOverMainLive), dialog(loginOverMainLive)), main)
            val r = rig(SkillSettings.MODE_FULL, morning, listOf("dungeon"))
            r.dungeons.onRun = { morning.begin() }
            morning.begin()
            r.until(60.0) { r.dungeons.ran > 0 && morning.at == -1 && r.log.any { it.contains("the chain is finished") } }
            assertEquals(1, r.dungeons.ran, "full: ${r.log}")
            assertEquals(4, r.cap.taps.size, "full: an OK and a tap high up, twice: ${r.cap.taps} ${r.log}")
            assertEquals(listOf("news_buddy_over_main_125738.png", "login_bonus_over_main_125958.png").let { it + it },
                         morning.closed)
            assertTrue(morning.strays.isEmpty(), "full: ${morning.strays}")
            assertNull(r.director.parked, "full: ${r.log}")
        }
    }

    /**
     * A news window whose OK does nothing gets a second, never a third: a
     * park with its sentence and the frame kept (Director.RESET_SAME_TAPS),
     * in either mode.
     */
    @Test
    fun `a news window that stays after two OKs is parked on`() {
        for (mode in listOf(SkillSettings.MODE_SEMI, SkillSettings.MODE_FULL)) {
            val stuck = news(overdrive).let { w -> Window(w.name, w.img, "news") { _, _ -> false } }
            val morning = Morning(listOf(stuck), frame(home))
            val r = rig(mode, morning)
            morning.begin()
            val last = r.run(40.0)
            assertEquals(2, r.cap.taps.size, "$mode: ${r.cap.taps} ${r.log}")
            assertEquals(r.cap.taps[0], r.cap.taps[1], "$mode: the same OK")
            assertNotNull(r.director.parked, "$mode: ${r.log}")
            assertTrue(last.note.contains("stays after ${Director.RESET_SAME_TAPS} taps"), "$mode: ${last.note}")
            assertEquals(listOf("news", "reset_stays"), r.kept, mode)
        }
    }

    /**
     * More windows in a row than a stack ever had is something the director
     * does not understand: [Director.RESET_WINDOWS_MAX] OKs, then a park.
     */
    @Test
    fun `a stack longer than any seen ends in a park, not in a ninth tap`() {
        val endless = List(12) { i -> news(listOf(buddy1, buddy2, overdrive)[i % 3]) }
        val morning = Morning(endless, frame(home))
        val r = rig(SkillSettings.MODE_FULL, morning)
        morning.begin()
        r.run(90.0)
        assertEquals(Director.RESET_WINDOWS_MAX, r.cap.taps.size, "${r.cap.taps} ${r.log}")
        assertNotNull(r.director.parked, r.log.toString())
        assertTrue(r.director.parked!!.contains("more than ${Director.RESET_WINDOWS_MAX} windows"), r.director.parked)
        assertTrue(morning.strays.isEmpty(), "${morning.strays}")
    }

    /**
     * What is not the reset's stays the player's. A dialog with no news before
     * it -- Notices the player opened -- is parked on in both modes, as
     * before; and in the semi-automatic mode a news window that came up over
     * that dialog stands, with its sentence, and nothing is tapped.
     */
    @Test
    fun `a dialog without the news before it is the player's, and so is a news window over it in the semi-automatic mode`() {
        for (mode in listOf(SkillSettings.MODE_SEMI, SkillSettings.MODE_FULL)) {
            val morning = Morning(listOf(dialog(notices), news(buddy1)), frame(home))
            val r = rig(mode, morning)
            morning.begin()
            r.run(10.0)
            assertTrue(r.cap.taps.isEmpty(), "$mode: ${r.cap.taps}")
            assertNotNull(r.director.parked, "$mode: Notices nobody's news came before: ${r.log}")
            morning.playerCloses()
            val last = r.run(12.0)
            if (mode == SkillSettings.MODE_SEMI) {
                assertTrue(r.cap.taps.isEmpty(), "$mode: ${r.cap.taps} ${r.log}")
                assertEquals("a news window of the game's over the dialog; I leave it to you", last.note, r.log.toString())
                assertNull(r.director.parked, mode)
            } else {
                assertEquals(1, r.cap.taps.size, "$mode: the chain goes on from under it: ${r.log}")
            }
            assertTrue(morning.strays.isEmpty(), "$mode: ${morning.strays}")
        }
    }
}
