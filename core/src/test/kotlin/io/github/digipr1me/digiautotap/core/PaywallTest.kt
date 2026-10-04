package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a supporter code opens, the player's cut of 2026-10-02
 * (PLAN_ABSCHLUSS_1_3.md 3.4 and 0.4): four rows of the Tasks list, and
 * nothing about an ad since 2026-10-03 (3.6). The rows live in the app
 * (Skills.kt), so the list of them is read where SkillSettingsTest reads
 * it, and every row is asked what the list and the director ask --
 * included, and the line beside a locked one -- with a code, without one,
 * and before it is known. Then the free ads: [FreeAds] is the pass alone,
 * and what [Stored] hands each of the five ways to a free ad.
 */
class PaywallTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val app = File(repo, "app/src/main/kotlin/io/github/digipr1me/digiautotap")

    /** The rows of the Tasks list, by key, in the list's order. */
    private val rows: List<String> = Regex("""SkillRow\("(\w+)"""")
        .findAll(File(app, "Skills.kt").readText()).map { it.groupValues[1] }.toList()

    private val free = listOf("mini", "summon", "farm", "dungeon", "passive", "quest")
    private val locked = listOf("runner", "skewer", "exmissions", "preset")

    @Test
    fun `the cut is the player's, row for row`() {
        // Bond token is written above the list (Skills.PASSIVE), so the file
        // names it first: the set is the list's, the order the locked rows'.
        assertEquals((free + locked).toSet(), rows.toSet(), "the Tasks list is not the one the cut was made on")
        assertEquals(rows.size, rows.toSet().size)
        assertEquals(locked, rows.filter { it in locked })
        assertEquals(locked, Paywall.TASKS)
        for (key in free) assertFalse(Paywall.needsCode(key), "$key is everybody's")
        for (key in locked) assertTrue(Paywall.needsCode(key), "$key needs a code")
        // The Lost Sector Tower and the bond tour have no row: they stand or
        // fall with Dungeons and Bond token (Skills.rowFor), both free.
        assertFalse(Paywall.needsCode("lost_sector"))
        assertFalse(Paywall.needsCode("bond"))
    }

    /**
     * Skills.kt asks Paywall and keeps no table of its own: no row carries a
     * flag, the row's `supporter` is Paywall's answer, and `included`,
     * `holdBack` and `offWhy` go through it -- so what this test holds is
     * what the list and the director say.
     */
    @Test
    fun `the list asks Paywall and keeps no table of its own`() {
        val skills = File(app, "Skills.kt").readText()
        assertFalse("supporter = true" in skills, "a row of Skills.kt carries a supporter flag of its own")
        assertTrue("val supporter: Boolean get() = Paywall.needsCode(key)" in skills)
        assertTrue("Paywall.included(store, r.key, unlocked)" in skills, "Skills.included is not Paywall's")
        assertTrue("Paywall.locked(r.key, unlocked) -> Paywall.why(r.key, unlocked)!!" in skills,
                   "Skills.holdBack does not begin with Paywall's line")
        assertTrue("if (Paywall.locked(r.key, unlocked)) return \"it needs a supporter code\"" in skills)
        // And the pages say the same as the rows.
        for (page in SkillSettings.PAGES.filter { it.key in rows }) {
            assertEquals(Paywall.needsCode(page.key), page.supporter, "the ${page.key} page")
        }
    }

    /** Every row, switched on, with a code, without one, and while the code is still being read. */
    @Test
    fun `every row is included or locked as the cut says`() {
        val on = MapSettings(rows.associate { Stored.includedKey(it) to true })
        for (key in rows) {
            assertTrue(Paywall.included(on, key, true), "$key with a code")
            val open = key in free
            assertEquals(open, Paywall.included(on, key, false), "$key without a code")
            assertEquals(open, Paywall.included(on, key, null), "$key while the code is read")
            assertNull(Paywall.why(key, true), "$key with a code")
            assertEquals(if (open) null else Paywall.NEEDS_CODE, Paywall.why(key, false), "$key without a code")
            assertEquals(if (open) null else Paywall.CHECKING, Paywall.why(key, null), "$key while the code is read")
        }
        assertEquals("Needs a supporter code", Paywall.NEEDS_CODE)
        assertEquals("Checking the supporter code...", Paywall.CHECKING)
    }

    /**
     * A setting a player set while the row was locked holds once it is free:
     * the Meat Field switched off stays off, Dungeons switched on is on, and
     * the box for all of the Digimon ticked walks (BondTourTest) -- nothing
     * is put back to a default by the update.
     */
    @Test
    fun `what was set while a row was locked holds once it is free`() {
        val s = MapSettings(mapOf(Stored.includedKey("farm") to false, Stored.includedKey("dungeon") to true,
                                  PassiveSkill.PASSIVE_ALL_DIGIMON to true))
        assertFalse(Paywall.included(s, "farm", false), "switched off before, off now")
        assertTrue(Paywall.included(s, "dungeon", false))
        assertTrue(Paywall.included(MapSettings(), "farm", false), "never set: in, as every row is")
        val tour = BondTourSkill(DirectorTest.FakeCapture(),
                                 PassiveSkill(DirectorTest.FakeCapture(), { _, d -> d }, log = {}),
                                 { k, d -> s.bool(k, d) }, log = {})
        assertTrue(tour.hasBudget(), "the box ticked while it was locked walks")
        // A locked row keeps what it had for the day a code comes.
        val runner = MapSettings(mapOf(Stored.includedKey("runner") to true))
        assertFalse(Paywall.included(runner, "runner", false))
        assertTrue(runner.bool(Stored.includedKey("runner"), false), "the setting is left alone")
        assertTrue(Paywall.included(runner, "runner", true))
    }

    /**
     * Since 2026-10-03 the free ads are the pass alone (PLAN_ABSCHLUSS_1_3.md
     * 3.6): [FreeAds] has one switch and no way to watch an ad, and the park
     * where the pass is taken for granted says that the app never touches an
     * ad, that the player closes it and switches the pass off.
     */
    @Test
    fun `the free ads are the pass alone, and nothing watches an ad`() {
        assertTrue(FreeAds(pass = true).tap)
        assertFalse(FreeAds(pass = false).tap)
        assertEquals(listOf("pass"), FreeAds::class.java.declaredFields
                         .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name },
                     "one switch, no code, no watcher")
        assertTrue(FreeAds::class.java.methods.none { it.name.lowercase().contains("watch") },
                   "FreeAds has no watch")
        // The default of the file: no pass, and an old ad_watch read by nobody.
        assertEquals(FreeAds(pass = false), Stored.freeAds(MapSettings()))
        assertEquals(FreeAds(pass = false), Stored.freeAds(MapSettings(mapOf("ad_watch" to true))))
        assertEquals(FreeAds(pass = true), Stored.freeAds(MapSettings(mapOf(SkillSettings.AD_PASS_KEY to true))))
        assertEquals("An ad came although the Ad Skip Pass is switched on: close it yourself -- " +
                     "I never touch an ad -- and switch the pass off on the main page if you do not have it.",
                     FreeAds.PARK)
    }

    /**
     * A settings file with the pass as [pass], `ad_watch` -- "Watch the free
     * ads for me" from 2026-10-02 to 2026-10-03, which nothing reads any
     * more -- as [watcher], and every pick, Idle Rewards' among them, as
     * [picks].
     */
    private fun file(pass: Boolean, watcher: Boolean, picks: Boolean = true) = MapSettings(
        mapOf(SkillSettings.AD_PASS_KEY to pass, "ad_watch" to watcher, Stored.IDLE_ADS_KEY to picks) +
            Stored.AD_PICKS.associate { it.second to picks })

    /**
     * What each of the five ways to a free ad is handed (2026-10-03,
     * PLAN_ABSCHLUSS_1_3.md 3.6): Summon, Dungeons, Meat Field, the Quest
     * Loop's two bots and Idle Rewards, each with its pick on. Without the
     * pass no film button, whatever `ad_watch` an old file holds -- and
     * [Stored] asks no code at all --, with it the tap, and a pick off is no
     * tap anywhere.
     */
    @Test
    fun `each way to a free ad asks the pass alone`() {
        for (watcher in listOf(false, true)) {
            val say = "ad_watch=$watcher"
            val without = file(pass = false, watcher = watcher)
            assertFalse(Stored.summon(without).watchAdsFirst, "summon, $say")
            assertFalse(Stored.dungeon(without).useAds, "dungeon, $say")
            assertFalse(Stored.farm(without).ads, "farm, $say")
            assertFalse(Stored.quest(without).summonAds, "quest's summon, $say")
            assertFalse(Stored.quest(without).dungeonAds, "quest's dungeon, $say")
            assertFalse(Stored.idle(without).ads, "idle, $say")

            val with = file(pass = true, watcher = watcher)
            assertTrue(Stored.summon(with).watchAdsFirst, "summon, $say")
            assertTrue(Stored.dungeon(with).useAds, "dungeon, $say")
            assertTrue(Stored.farm(with).ads, "farm, $say")
            assertTrue(Stored.quest(with).summonAds && Stored.quest(with).dungeonAds, "quest, $say")
            assertTrue(Stored.idle(with).ads, "idle, $say")

            val picksOff = file(pass = true, watcher = watcher, picks = false)
            assertFalse(Stored.summon(picksOff).watchAdsFirst)
            assertFalse(Stored.dungeon(picksOff).useAds)
            assertFalse(Stored.farm(picksOff).ads)
            assertFalse(Stored.quest(picksOff).summonAds || Stored.quest(picksOff).dungeonAds)
            assertFalse(Stored.idle(picksOff).ads)
        }
    }
}
