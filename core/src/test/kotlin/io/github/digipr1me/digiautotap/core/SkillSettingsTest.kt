package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The skill pages' own consistency. Until 2026-09-21 every key, default and
 * range here was held to the PC version's `app.py` through
 * `oracle/seam.json`; the laboratory is retired and SkillSettings is where
 * a setting is decided now (PLAN_STANDALONE.md 3). What is left to check
 * is what the pages cannot say for themselves.
 */
class SkillSettingsTest {

    private val fields = SkillSettings.PAGES.flatMap { it.fields }

    @Test
    fun `every key is one field on one page`() {
        val keys = fields.map { it.key }
        assertEquals(keys.distinct(), keys, "a key is a field twice: ${keys.groupBy { it }.filter { it.value.size > 1 }.keys}")
        val pages = SkillSettings.PAGES.map { it.key }
        assertEquals(pages.distinct(), pages, "a page key twice")
        assertTrue(keys.none { it == SkillSettings.MODE_KEY }, "the mode is not a page field")
        // The Ad Skip Pass is the main page's one switch since 2026-09-24,
        // and the two task boxes it replaced are gone from their pages.
        assertTrue(keys.none { it == SkillSettings.AD_PASS_KEY }, "the pass is not a page field")
        assertTrue(keys.none { it == "ad_watch" }, "the watcher of 2026-10-02 is no field anywhere")
        assertTrue(keys.none { it == "use_ads" || it == "summon_ads" },
                   "an old ad box's key: an old file may still hold it")
    }

    /**
     * The three pages' ad switches -- Dungeons and Summon since 2026-09-30,
     * Meat Field since 2026-09-28 -- left their pages on 2026-10-02 for the
     * main page's Ad Rewards card, as its picks (question 13 of
     * PLAN_ABSCHLUSS_1_3.md), under the keys they had, so what a player set
     * there holds.
     */
    @Test
    fun `the ad switches are the Ad Rewards card's picks, under the keys they had`() {
        assertEquals(listOf("summon" to "summon_free_ads", "dungeon" to "dungeon_ads", "farm" to "farm_ads"),
                     Stored.AD_PICKS)
        val pageKeys = fields.map { it.key }.toSet()
        for ((task, key) in Stored.AD_PICKS) {
            assertTrue(key !in pageKeys, "$task's ad switch is still on a page")
        }
    }

    /**
     * World Search's page since 2026-09-28: one switch, the eager dash, off
     * by default, under the key `Stored.worldSearch` reads -- and a note that
     * says what off and on each do (PLAN_WORLD_SEARCH_DASH.md 4.2).
     */
    @Test
    fun `the World Search page has the dash switch and nothing else`() {
        val page = SkillSettings.page("mini")
        assertEquals(1, page.fields.size, "one switch on the page")
        val t = page.fields[0] as SkillSettings.Toggle
        assertEquals(Stored.WORLD_SEARCH_DASH_KEY, t.key)
        assertEquals("mini_dash_eager", t.key)
        assertEquals("Dash through two or more pyramids", t.label)
        assertEquals(false, t.default)
        assertEquals(false, t.supporter)
        assertTrue(t.note.startsWith("Off,") && "On," in t.note && "the log says what" in t.note, t.note)
        assertTrue(page.note.startsWith("When switched on, World Search plays the board"), page.note)
        assertTrue("Nothing to set" !in page.note, page.note)
        assertEquals(t.default, Stored.worldSearch(MapSettings()).dashEager)
    }

    /**
     * The Meat Field's page since 2026-09-28: the Good and Great seeds of
     * PLAN_MEAT_FIELD_GIESSEN.md 4.5, off, under the key `Stored.farm` reads
     * -- and no switch for the watering (4.8). Its ad switch is the Ad Rewards
     * card's Meat Field pick since 2026-10-02, and everybody's page since.
     */
    @Test
    fun `the Meat Field page has the seeds' switch, off, and none for watering`() {
        val page = SkillSettings.page("farm")
        val toggles = page.fields.filterIsInstance<SkillSettings.Toggle>()
        assertEquals(listOf(Stored.FARM_BETTER_SEEDS_KEY), toggles.map { it.key })
        assertEquals(listOf("farm_better_seeds"), toggles.map { it.key })
        assertTrue(toggles.none { it.default }, "a switch on by default")
        assertFalse(page.supporter, "the Meat Field is everybody's since 2026-10-02")
        assertEquals(FarmSkill.Settings(), Stored.farm(MapSettings()), "the defaults are the skill's")
        assertTrue("water" in page.note && "never used" in page.note, page.note)
        val s = MapSettings()
        s.put(Stored.FARM_ADS_KEY, true)
        s.put(SkillSettings.AD_PASS_KEY, true)
        assertEquals(FarmSkill.Settings(betterSeeds = false, ads = true), Stored.farm(s), "with the pass for everybody")
        s.put(SkillSettings.AD_PASS_KEY, false)
        assertEquals(FarmSkill.Settings(betterSeeds = false, ads = false), Stored.farm(s), "and without it for nobody")
    }

    /**
     * The watering threshold of PLAN_MEAT_FIELD_GIESSEN.md 13, the player's
     * of 2026-09-28: one number on the page, in minutes, 20 by default, 0
     * for every growing plot -- the same kind of field as the runner's Fever
     * Times -- and the skill reads what the file holds.
     */
    @Test
    fun `the Meat Field page has the watering threshold, 20 minutes by default`() {
        val page = SkillSettings.page("farm")
        val numbers = page.fields.filterIsInstance<SkillSettings.Number>()
        assertEquals(listOf("farm_water_min_minutes"), numbers.map { it.key })
        val n = numbers.single()
        assertEquals(Stored.FARM_WATER_MIN_KEY, n.key)
        assertEquals(20.0, n.default)
        assertEquals(0.0, n.min, "0 is every growing plot")
        assertFalse(n.decimal)
        assertTrue(n.note.contains("0 waters every growing plot"), n.note)
        assertEquals(20, FarmSkill.Settings().waterMinMinutes)
        assertEquals(20, Stored.farm(MapSettings()).waterMinMinutes)
        val s = MapSettings()
        s.put(Stored.FARM_WATER_MIN_KEY, 45)
        assertEquals(45, Stored.farm(s).waterMinMinutes)
        s.put(Stored.FARM_WATER_MIN_KEY, 0)
        assertEquals(0, Stored.farm(s).waterMinMinutes)
        s.put(Stored.FARM_WATER_MIN_KEY, -5)
        assertEquals(0, Stored.farm(s).waterMinMinutes, "a negative number is 0")
    }

    /**
     * The field's ads of a day are counted until the game day's reset
     * (QuestSkill.nextReset, 08:00 Vienna) and are 0 after it; one kind's
     * count starting a new day clears the other's.
     */
    @Test
    fun `the field's ads are counted for the game day and forgotten after its reset`() {
        val s = MapSettings()
        val morning = 1_790_000_000.0
        val reset = QuestSkill.nextReset(morning)
        assertEquals(0, Stored.farmAdsUsed(s, FarmSkill.SEEDS, morning))
        Stored.setFarmAdsUsed(s, FarmSkill.SEEDS, 1, morning)
        Stored.setFarmAdsUsed(s, FarmSkill.CANS, 2, morning)
        assertEquals(1, Stored.farmAdsUsed(s, FarmSkill.SEEDS, morning + 60))
        assertEquals(2, Stored.farmAdsUsed(s, FarmSkill.CANS, reset - 1))
        assertEquals(0, Stored.farmAdsUsed(s, FarmSkill.CANS, reset), "the reset starts a new day")
        Stored.setFarmAdsUsed(s, FarmSkill.SEEDS, 1, reset + 60)
        assertEquals(1, Stored.farmAdsUsed(s, FarmSkill.SEEDS, reset + 60))
        assertEquals(0, Stored.farmAdsUsed(s, FarmSkill.CANS, reset + 60), "yesterday's cans are not today's")
    }

    /**
     * The Dungeons page's minutes (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.4):
     * under a rule below the tickets, "Daily Dungeon" (the player's name,
     * question 1) and "Lost Sector Tower", both from 0, 0 by default, with no
     * ceiling a player meets (question 11), the page's sentence under them,
     * and the daily one read by the skill -- the Lost Sector's by
     * LostSectorSkill since DL4 (Stored.lostSector, LostSectorSkillTest).
     */
    @Test
    fun `the Dungeons page has the two minutes under a rule, 0 by default`() {
        val page = SkillSettings.page("dungeon")
        // The tickets first again since 2026-10-02: the ad switch that stood
        // above them from 2026-09-30 is the Ad Rewards card's pick.
        assertTrue(page.fields[0] is SkillSettings.DungeonAttempts, "the tickets open the page")
        val numbers = page.fields.filterIsInstance<SkillSettings.Number>()
        val minutes = numbers.filter { it.key in setOf(Stored.DUNGEON_DAILY_KEY, Stored.LOST_SECTOR_MINUTES_KEY) }
        assertEquals(listOf("dungeon_daily_minutes", "lost_sector_minutes"), minutes.map { it.key })
        assertEquals(listOf("Daily Dungeon", "Lost Sector Tower"), minutes.map { it.label })
        assertEquals(page.fields.takeLast(2), minutes, "the minutes are the page's last block")
        for (n in minutes) {
            assertEquals(0.0, n.default, n.key)
            assertEquals(0.0, n.min, n.key)
            assertTrue(n.max >= 10_000.0, "${n.key}: a ceiling a player could meet")
            assertFalse(n.decimal, n.key)
        }
        assertEquals("How many minutes", minutes[0].heading, "the rule and the heading come with the first")
        assertEquals("", minutes[1].heading)
        assertEquals(SkillSettings.MINUTES_NOTE, minutes[1].note)
        assertTrue("each time Dungeons runs" in minutes[1].note && "0 leaves them out" in minutes[1].note)
        // And when the tower runs, which is not with Dungeons (DL4): its own
        // chain step, or its Crests page.
        assertTrue("its own step of the chain" in minutes[1].note && "Crests page" in minutes[1].note)
        assertTrue(numbers.filter { it !in minutes }.all { it.heading.isEmpty() }, "one block, one rule")

        assertEquals(0, Stored.dungeon(MapSettings()).dailyMinutes)
        assertEquals(0, DungeonSkill.Settings(budgets = emptyMap()).dailyMinutes)
        val s = MapSettings()
        s.put(Stored.DUNGEON_DAILY_KEY, 10)
        s.put(Stored.LOST_SECTOR_MINUTES_KEY, 7)
        assertEquals(10, Stored.dungeon(s).dailyMinutes, "the Lost Sector's minutes are not the daily's")
        s.put(Stored.DUNGEON_DAILY_KEY, -4)
        assertEquals(0, Stored.dungeon(s).dailyMinutes, "a negative number is 0")
    }

    /**
     * The Presets page since PLAN_DAILY_LOST_SECTOR_PRESETS.md DL2: six places,
     * Overdrive the sixth, and Gear a greyed line and nothing else (the
     * player's answer to question 6) -- no place, no key, no slot in a
     * profile -- and the page's note still true of every place with a number.
     */
    @Test
    fun `the Presets page has six places and Gear only as a placeholder`() {
        val page = SkillSettings.page("preset")
        assertTrue(page.supporter, "Presets is a supporter's task")
        assertEquals(listOf(SkillSettings.PresetProfiles::class), page.fields.map { it::class })
        assertEquals(listOf("digivice", "tactical", "food", "skill_cards", "support", "overdrive"),
                     Preset.Place.values().map { it.key })
        assertEquals("Overdrive", Preset.Place.OVERDRIVE.label)
        assertEquals(listOf("Gear"), SkillSettings.PRESET_PLACEHOLDERS)
        assertEquals("Comes with the game's next update.", SkillSettings.PRESET_PLACEHOLDER_NOTE)
        for (name in SkillSettings.PRESET_PLACEHOLDERS) {
            assertTrue(Preset.Place.values().none { it.label.equals(name, true) || it.key.equals(name, true) },
                       "$name is a placeholder, not a place")
            assertTrue(Stored.presetProfiles(MapSettings()).all { p -> p.slots.keys.none { it.label == name } })
            assertTrue(fields.none { it.key.contains(name, true) }, "$name has a key")
        }
        assertTrue("every place with a number" in page.note, page.note)
    }

    @Test
    fun `every number starts inside its range`() {
        for (f in fields) {
            if (f !is SkillSettings.Number) continue
            assertTrue(f.min < f.max, "${f.key}: min ${f.min} is not under max ${f.max}")
            assertTrue(f.default in f.min..f.max, "${f.key}: default ${f.default} is outside ${f.min}..${f.max}")
            assertTrue(f.label.isNotBlank(), "${f.key} has no label")
        }
    }

    @Test
    fun `the dungeons and the chain agree with themselves`() {
        assertEquals(7, SkillSettings.DUNGEON_NAMES.size)
        assertTrue(SkillSettings.DUNGEON_NAMES.containsAll(SkillSettings.HIDDEN_DUNGEONS),
                   "a hidden dungeon that is not a dungeon")
        assertTrue(SkillSettings.DAY_ATTEMPTS in 1..SkillSettings.SET_ALL_DEFAULT)

        val steps = SkillSettings.CHAINABLE + SkillSettings.CHAIN_WAITING
        assertEquals(steps.distinct(), steps, "a chain key in two lists")
        assertTrue(steps.containsAll(SkillSettings.CHAIN_DEFAULT), "a default step that is no step")
        assertEquals(steps.toSet(), SkillSettings.CHAIN_NAMES.keys, "every step has a name, and no name is without a step")
    }

    /**
     * The order sheet offers a button for every step of CHAIN_WAITING and
     * CHAINABLE, and on 2026-10-01 "Edit order" ended the app (PLAN_SKEWER.md
     * SK3, LDPlayer instance 0, release build of main 54eee91, twice:
     * NoSuchElementException at Skills.row from MainActivity.chainField):
     * the sheet named each button by `Skills.row(key).name`, and
     * "lost_sector" is chainable without a row of its own. The rows live in
     * the app, so the test reads them where ExMissionsSkillTest reads the
     * supporter flag -- in Skills.kt -- and holds the sheet to a name that
     * cannot throw: the row's where there is one, the chain's otherwise.
     */
    @Test
    fun `every step the order sheet offers has a name, row or not`() {
        val repo = java.io.File(System.getProperty("digiautotap.repo") ?: "..")
        val app = java.io.File(repo, "app/src/main/kotlin/io/github/digipr1me/digiautotap")
        val rows = Regex("""SkillRow\("(\w+)"""").findAll(java.io.File(app, "Skills.kt").readText())
            .map { it.groupValues[1] }.toSet()
        val offered = SkillSettings.CHAIN_WAITING + SkillSettings.CHAINABLE
        val rowless = offered.filter { it !in rows }
        // The case that ended the app: a step with no row.
        assertTrue("lost_sector" in rowless, "the Lost Sector Tower has a row now: $rows")
        for (key in rowless) {
            assertTrue(SkillSettings.CHAIN_NAMES[key].orEmpty().isNotBlank(), "$key has neither a row nor a chain name")
        }
        val activity = java.io.File(app, "MainActivity.kt").readText()
        val sheet = activity.substringAfter("private fun chainField(").substringBefore("\n    }\n")
        assertFalse("Skills.row(" in sheet, "the order sheet asks Skills.row for a step's name, which throws on $rowless")
        assertTrue("Skills.stepName(key)" in sheet, "the order sheet names its buttons by something else")
    }
}
