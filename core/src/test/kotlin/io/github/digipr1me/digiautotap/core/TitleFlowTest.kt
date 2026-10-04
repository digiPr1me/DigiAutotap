package io.github.digipr1me.digiautotap.core

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
 * The game's title under the director, on the corpus's title frames
 * (PLAN_RELEASE_1_3.md B10, 2026-09-30). Since resources 1.4.0.D7X0146 the
 * title is a picture of a well that changes with the time of day, and on
 * the night picture a boy's blue shirt read as the auto button: the title
 * was `main`, so the semi-automatic mode handed it to the main screen's
 * round-skills and the fully automatic one would have begun its chain on
 * it. `Dungeon.AUTO_GLYPH_MIN` and `Startup.BAR_BLUE` say what changed.
 *
 * The frames: the old title (corpus/startup/walk_000_title), the dusk and
 * night pictures R2 took on instance 1 (staging/r2/live_title_dusk_185220,
 * the app stopped; live_title_night_203943, a screencap with the dot paused
 * on it), and the director's own kept frame of the night picture with its
 * loading bar (staging/b10/title_night_loading_205737), which read
 * "Watching · main" live for a minute. The three new ones wait in staging/
 * for the corpus (corpus/startup/title_dusk_185220, title_night_203943,
 * title_night_loading_205737), and are read from there until they are in
 * it. With the old readers the two night frames read `main` and every case
 * here fails on them.
 *
 * And the day (B43, 2026-10-01): its picture is the bar's own pale blue, so
 * the bar is not found on it and the title is read by its Menu button
 * (`Startup.menuButton`); with the bar alone both day frames read `unknown`,
 * and every case here fails on them too.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TitleFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    /** Each frame by its places, the corpus first: the first that exists is read. */
    private val old = listOf("corpus/startup/walk_000_title.png")
    private val dusk = listOf("corpus/startup/title_dusk_185220.png", "staging/r2/live_title_dusk_185220.png")
    private val night = listOf("corpus/startup/title_night_203943.png", "staging/r2/live_title_night_203943.png")
    private val loading = listOf("corpus/startup/title_night_loading_205737.png",
                                 "staging/b10/title_night_loading_205737.png")
    /**
     * The day's picture, whose bar does not stand out from it and which is
     * read by its Menu button (PLAN_RELEASE_1_3.md B43): instance 0 on
     * 2026-10-01 at 08:54, 900 x 1600 with the plate on it, and a day title
     * of resources 1.3.1.D3X5131 the passive helper kept on 2026-09-21.
     */
    private val day = listOf("corpus/startup/title_day_085430.png", "staging/b6live/title_day_085430.png")
    private val dayOld = listOf("corpus/passive/unclear_090348.png")
    private val titles = listOf(old, dusk, night, day, dayOld)

    /** The main screen of the same display, 1080 x 1920, for the chain to begin on. */
    private val main = listOf("corpus/tall/main_bubble_1080x1920_a.png")

    private fun frame(places: List<String>): Mat {
        // No corpus at all is the public copy of the source (release.py), where
        // these cases have nothing to say; a frame missing from both places is a fault.
        assumeTrue(File(repo, "corpus").isDirectory, "no corpus in this checkout")
        val file = places.map { File(repo, it) }.firstOrNull { it.exists() }
        assertNotNull(file, "no frame at any of $places")
        val img = OracleFamilies.read(file)
        assertTrue(!img.empty(), "could not read $file")
        return img
    }

    private fun name(places: List<String>) = places.first().substringAfterLast('/')

    /** A rig whose capture hands out a fresh copy of [img] on every grab. */
    private fun rig(img: Mat, mode: String, steps: List<String> = emptyList()): DirectorTest.Rig =
        DirectorTest.Rig(mode, steps).also { r -> r.cap.scene = { OracleFamilies.copy(img) } }

    @Test
    fun `the title of every time of day reads as the title, and its loading screen as no screen`() {
        for (places in titles) {
            val img = frame(places)
            assertEquals(Director.TITLE, Director.classify(img).screen, name(places))
            assertEquals(null, Dungeon.autoButton(img), name(places))
            img.release()
        }
        val img = frame(loading)
        assertEquals(Director.UNKNOWN, Director.classify(img).screen, name(loading))
        assertEquals(null, Dungeon.autoButton(img), name(loading))
        img.release()
    }

    /**
     * Semi-automatic: the main screen's round-skills (the Bond token, the
     * quest loop) are the ones a `main` would have gone to. On the title
     * none of them works and nothing is tapped. The director stands still
     * there with its own sentence since 2026-10-01 (PLAN_RELEASE_1_3.md B31):
     * it parked red with "Something is in the way that I did not put there:
     * title." until then ("the core comes up switched on and parks on the
     * title", notes/ldplayer.md), which was not true of a title the player
     * opened. On the loading screen it stands still, as on any screen it
     * does not know.
     */
    @Test
    fun `in the semi-automatic mode no round-skill gets the title and nothing is tapped`() {
        for (places in titles + listOf(loading)) {
            val name = name(places)
            val img = frame(places)
            val r = rig(img, SkillSettings.MODE_SEMI)
            val last = r.run(Director.UNKNOWN_END + 5.0)
            assertEquals(0, r.passive.worked, "$name: the Bond token's round")
            assertEquals(0, r.quest.worked, "$name: the quest loop's round")
            assertEquals(0, r.dungeons.worked + r.summons.worked + r.tower.worked, name)
            assertTrue(r.cap.taps.isEmpty(), "$name: taps ${r.cap.taps}")
            assertNull(r.director.parked, "$name: no park in the semi-automatic mode")
            if (places != loading) {
                assertEquals("the title screen; nothing to do here", last.note, "$name: ${r.log}")
                assertTrue(r.log.none { it.contains("in the way") }, "$name: ${r.log}")
            }
            img.release()
        }
    }

    /**
     * Fully automatic: the chain starts from the main screen and not from
     * the title. On the title it parks at once; on the loading screen after
     * UNKNOWN_END, as on any screen it does not know. Once the main screen
     * is there, the park is over and the chain begins.
     */
    @Test
    fun `in the fully automatic mode the chain does not begin on the title, and begins on the main screen after it`() {
        for (places in titles + listOf(loading)) {
            val name = name(places)
            val img = frame(places)
            val r = rig(img, SkillSettings.MODE_FULL, listOf("dungeon"))
            r.run(Director.UNKNOWN_END + 5.0)
            assertEquals(0, r.dungeons.ran, "$name: the chain's first step")
            assertEquals(0, r.passive.worked + r.quest.worked, "$name: the main screen's round")
            assertTrue(r.cap.taps.isEmpty(), "$name: taps ${r.cap.taps}")
            assertNotNull(r.director.parked, "$name: the fully automatic mode starts from the main screen")
            assertTrue(r.log.none { it.contains("chain: starting") }, "$name: ${r.log}")
            // The player touches Start; the main screen is there.
            val home = frame(main)
            assertEquals(Director.MAIN, Director.classify(home).screen, name(main))
            r.cap.scene = { OracleFamilies.copy(home) }
            r.run(Director.IDLE_AFTER + 2.0)
            assertEquals(1, r.dungeons.ran, "$name: the chain begins on the main screen after the title")
            assertTrue(r.log.any { it.contains("chain: starting") }, "$name: ${r.log}")
            home.release()
            img.release()
        }
    }
}
