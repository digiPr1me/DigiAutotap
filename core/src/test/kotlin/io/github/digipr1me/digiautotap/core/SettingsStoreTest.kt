package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings store core got in the merge session (PLAN_ANDROID_APP.md 4,
 * points 7 and 8): every skill's settings read out of one file, by keys
 * that are app.py's, with app.py's defaults.
 *
 * What these cases are about is the *defaults*, because that is what a
 * fresh phone runs on: a settings file that has never been written has to
 * give every skill the same answer the PC's page gives it.
 */
class SettingsStoreTest {

    private val empty = MapSettings()

    @Test
    fun `a file that has never been written gives the pages' own defaults`() {
        // Dungeons: every card at app.py's DAY_ATTEMPTS, except the hidden
        // one, which keeps its place at 0 because the skill counts cards.
        val budgets = Stored.dungeon(empty).budgets
        assertEquals(SkillSettings.DUNGEON_NAMES.size, budgets.size)
        // The task plays the cards without the pre-check since 2026-09-22;
        // the quest loop passes its own `survey = true` (QuestSkill.dungeonFor).
        assertFalse(Stored.dungeon(empty).survey)
        val hidden = SkillSettings.DUNGEON_NAMES.indexOf("Daily changing dungeon")
        assertEquals(0, budgets[hidden])
        assertEquals(SkillSettings.DAY_ATTEMPTS, budgets[0])
        assertTrue(Chain.dungeonHasBudget(budgets))

        // Summons: three modes on, no ceiling, and no free ad taken until the
        // player picks it (the page's switch since 2026-09-30, the Ad Rewards
        // card's pick since 2026-10-02, off by default) and has the Ad Skip
        // Pass switched on (FreeAds, since 2026-10-03) -- in Summon, in
        // Dungeons and in the Quest Loop alike.
        assertEquals(SummonSkill.Settings(watchAdsFirst = false), Stored.summon(empty))
        assertFalse(Stored.freeAds(empty).tap, "no pass until switched on")
        assertFalse(Stored.adPass(empty))
        assertFalse(Stored.dungeon(empty).useAds)
        assertFalse(Stored.quest(empty).dungeonAds)
        assertFalse(Stored.quest(empty).summonAds)

        // World Search: WorldSearchSettings' own defaults since 2026-09-22 --
        // everything collected, no limit anywhere, and the pace that speeds
        // up while it goes well -- and since 2026-09-28 the page's one
        // switch, the eager dash, off.
        val mini = Stored.worldSearch(empty)
        assertFalse(mini.dashEager)
        assertEquals(WorldConst.ALL_WANTED, mini.wanted)
        assertEquals(0, mini.minPaws)      // the page's 0, not engine.Settings' 20
        assertEquals(0, mini.maxActions)
        assertEquals(0, mini.targetMeters)
        assertEquals(0.7, mini.clickDelay)
        assertTrue(mini.adaptive)
        assertEquals(180, mini.waitForBoard)
        assertTrue(mini.navigate)

        // Meat Field: never visited is due (chain.farm_is_due), and a plot
        // is watered with more than 20 minutes left (PLAN_MEAT_FIELD_GIESSEN.md 13).
        assertNull(Stored.farmDueAt(empty))
        assertEquals(20, Stored.farm(empty).waterMinMinutes)
        // Tower: no point until one is set, so the chain skips the step.
        assertNull(Stored.towerPoint(empty))
        assertEquals(3.0, Stored.towerInterval(empty))
        // The chain itself, and the mode.
        assertEquals(SkillSettings.CHAIN_DEFAULT, Stored.chainSteps(empty).map { it.key })
        assertFalse(Stored.chainRepeat(empty))
        assertEquals(SkillSettings.MODE_SEMI, Stored.mode(empty))
        // And every skill is in until it is switched off.
        assertTrue(Stored.included(empty, "dungeon"))
    }

    @Test
    fun `what is saved wins over the default`() {
        val s = MapSettings(mapOf(
            "attempts" to mapOf("0" to 0, "1" to 2),
            "summon_skill" to false, "summon_max" to 3,
            "farm_due_at" to 1_700_000_000.0,
            "farm_water_min_minutes" to 35,
            "tower_fx" to 0.5, "tower_fy" to 0.25, "tower_interval" to 1.5,
            Stored.includedKey("farm") to false,
            SkillSettings.MODE_KEY to SkillSettings.MODE_FULL,
            "chain_steps" to listOf(Chain.Step("tower", 12)),
            "chain_repeat" to true,
            // The pass on, the two old boxes off and the two switches of
            // 2026-09-30 on in the same file: the pass and the switches are
            // what the readers answer to, the old boxes nothing.
            SkillSettings.AD_PASS_KEY to true, "use_ads" to false, "summon_ads" to false,
            Stored.DUNGEON_ADS_KEY to true, Stored.SUMMON_ADS_KEY to true,
            Stored.WORLD_SEARCH_DASH_KEY to true))
        assertTrue(Stored.worldSearch(s).dashEager)
        assertEquals("mini_dash_eager", Stored.WORLD_SEARCH_DASH_KEY)
        assertTrue(Stored.adPass(s))
        assertTrue(Stored.dungeon(s).useAds)
        assertTrue(Stored.summon(s).watchAdsFirst)
        assertTrue(Stored.quest(s).dungeonAds)
        assertTrue(Stored.quest(s).summonAds)
        assertEquals(0, Stored.dungeon(s).budgets[0])
        assertEquals(2, Stored.dungeon(s).budgets[1])
        // Untouched cards still get the day's default.
        assertEquals(SkillSettings.DAY_ATTEMPTS, Stored.dungeon(s).budgets[2])
        assertFalse(Stored.summon(s).skill)
        assertEquals(3, Stored.summon(s).maxPerMode)
        assertEquals(1_700_000_000.0, Stored.farmDueAt(s))
        assertEquals(35, Stored.farm(s).waterMinMinutes)
        assertEquals(0.5 to 0.25, Stored.towerPoint(s))
        assertEquals(1.5, Stored.towerInterval(s))
        assertFalse(Stored.included(s, "farm"))
        assertEquals(SkillSettings.MODE_FULL, Stored.mode(s))
        assertEquals(listOf(Chain.Step("tower", 12)), Stored.chainSteps(s))
        assertTrue(Stored.chainRepeat(s))
    }

    /**
     * A half-saved Tower point is no point. Both halves or neither: the
     * player's F2 on the PC writes them together, and a file edited by hand
     * is the case this refuses rather than tapping at 0.5 / 0.
     */
    @Test
    fun `half a tower point is no point`() {
        assertNull(Stored.towerPoint(MapSettings(mapOf("tower_fx" to 0.5))))
        assertNull(Stored.towerPoint(MapSettings(mapOf("tower_fy" to 0.5))))
    }

    /**
     * The World Search keys an old settings file still holds -- the six
     * `want_<key>` ticks, the three limits, the click delay, `adaptive` --
     * are not read any more, and above all a `want_<key>` set to false on a
     * file copied off a PC cannot take a board item away from a player who
     * has no way left to put it back.
     */
    @Test
    fun `an old settings file cannot take a board item away`() {
        // The keys every file written before 2026-09-22 still holds, set
        // against every default: none of them reaches the skill. Only the
        // page's switch of 2026-09-28 is read.
        val mini = Stored.worldSearch(MapSettings(
            WorldConst.ALL_WANTED.associate { "want_$it" to false } + mapOf(
                "min_paws" to 50, "max_actions" to 10, "target_meters" to 500,
                "click_delay" to 2.0, "adaptive" to false)))
        assertFalse(mini.dashEager)
        assertEquals(WorldConst.ALL_WANTED, mini.wanted)
        assertTrue(Chain.miniHasTarget(mini.wanted))
        assertEquals(0, mini.minPaws)
        assertEquals(0, mini.maxActions)
        assertEquals(0, mini.targetMeters)
        assertEquals(0.7, mini.clickDelay)
        assertTrue(mini.adaptive)
    }

    @Test
    fun `the Meat Field's three keys are the three app py saves`() {
        val s = MapSettings()
        Stored.rememberFarm(s, 1000.0, 600, "two plots growing")
        assertEquals(1000.0, Stored.farmDueAt(s))
        assertEquals(600.0, s.num("farm_grow_seconds", -1.0))
        assertEquals("two plots growing", s.str("farm_last_reason", ""))
        // A visit that planted nothing leaves the last grow time alone
        // rather than overwriting it with a guess.
        Stored.rememberFarm(s, 2000.0, null, "nothing growing")
        assertEquals(2000.0, Stored.farmDueAt(s))
        assertEquals(600.0, s.num("farm_grow_seconds", -1.0))
        // And it comes back out again: the cap the next such visit holds its
        // field timers under (FarmSkill's knownGrowSeconds).
        assertEquals(600, Stored.farmGrowSeconds(s))
        assertNull(Stored.farmGrowSeconds(MapSettings()),
                   "nothing recorded yet is no cap, not a zero one")
    }

    /**
     * The quest loop's day lock (QuestSkill.lockedForTheDay): two keys
     * written together when the loop stops for want of tickets, read back
     * into the loop's own Settings, and both taken away by the row's
     * switch.
     */
    @Test
    fun `the quest loop's lock is two keys, and the switch takes both away`() {
        val s = MapSettings()
        assertNull(Stored.quest(s).lockedUntil)
        assertEquals("", Stored.questLockedReason(s))
        Stored.lockQuest(s, 1_800_000_000.0, "the dungeon is out of tickets and ads until tomorrow")
        assertEquals(1_800_000_000.0, Stored.questLockedUntil(s))
        assertEquals(1_800_000_000.0, Stored.quest(s).lockedUntil)
        assertEquals("the dungeon is out of tickets and ads until tomorrow", Stored.questLockedReason(s))
        Stored.unlockQuest(s)
        assertNull(Stored.questLockedUntil(s))
        assertEquals("", Stored.questLockedReason(s))
        assertFalse(s.data.containsKey("quest_locked_until"), "gone from the file, not set to something")
    }

    /**
     * A row switched on starts its day again (PLAN_ABSCHLUSS_1_3.md A2): each
     * row that keeps a day has it taken out of the file in one read, change
     * and write, and says what it was. The TODAY card's numbers and every
     * other key stay as they are.
     */
    @Test
    fun `a row switched on starts its day again, each in one write`() {
        val writes = ArrayList<Map<String, Any?>>()
        val inner = MapSettings()
        val s = object : Settings by inner {
            override fun put(key: String, value: Any?) { writes += mapOf(key to value); inner.put(key, value) }
            override fun putAll(values: Map<String, Any?>) { writes += values; values.forEach { (k, v) -> inner.put(k, v) } }
        }
        val reset = QuestSkill.nextReset(NOON)
        fun restarted(key: String): Stored.Day? {
            writes.clear()
            return Stored.restart(s, key, NOON).also {
                assertEquals(1, writes.size, "$key: one write, $writes")
            }
        }

        // The Quest Loop's lock: both keys out of the file.
        Stored.lockQuest(s, reset, "the dungeon is out of tickets and ads until tomorrow")
        assertEquals(Stored.Day("out of dungeon tickets until 2026-09-24 08:00", true), Stored.day(s, "quest", NOON))
        assertEquals(Stored.Day("out of dungeon tickets until 2026-09-24 08:00", true), restarted("quest"))
        assertEquals(mapOf("quest_locked_until" to null, "quest_locked_reason" to null), writes.single())
        assertNull(Stored.questLockedUntil(s))
        assertTrue(Stored.quest(s).lockedUntil == null)

        // Chef's Special at the page's number: the count is 0, the day owes it all again.
        s.put(Stored.SKEWER_COMBOS_KEY, 15)
        repeat(15) { Stored.addSkewerCombo(s, NOON) }
        assertEquals(Stored.Day("15 of 15 combos counted today", true), restarted("skewer"))
        assertEquals(mapOf<String, Any?>("skewer_combos_done" to 0), writes.single())
        assertEquals(15, Stored.skewer(s, NOON).left)

        // Gekkomon Run, the same.
        repeat(RunnerSkill.FEVER_TARGET) { Stored.addRunnerFever(s, NOON) }
        val nine = RunnerSkill.FEVER_TARGET
        assertEquals(Stored.Day("$nine of $nine Fever Times played today", true), restarted("runner"))
        assertEquals(RunnerSkill.FEVER_TARGET, Stored.runner(s, NOON).left)

        // The Dungeons row: the Lost Sector Tower at its highest floor.
        s.put(Stored.LOST_SECTOR_MINUTES_KEY, 10)
        Stored.retireLostSector(s, reset)
        assertEquals(reset, Stored.lostSector(s, NOON).doneUntil)
        assertEquals(Stored.Day("the Lost Sector Tower is at its highest floor until 2026-09-24 08:00", true),
                     restarted("dungeon"))
        assertNull(Stored.lostSector(s, NOON).doneUntil)
        assertEquals(10, Stored.lostSectorMinutes(s), "the page's minutes stay")

        // The Meat Field's clock: the visit is due now; what the field
        // learned of its grow time stays.
        Stored.rememberFarm(s, NOON + 600, 3600, "growing")
        assertEquals(Stored.Day("the field's next visit is due in 10 min", true), restarted("farm"))
        assertNull(Stored.farmDueAt(s))
        assertEquals(3600, Stored.farmGrowSeconds(s))
        assertTrue(Chain.farmIsDue(Stored.farmDueAt(s), NOON))

        // Every switch on, not only at the limit (question 5): a count short
        // of the page's number goes too, and says it did not hold the row.
        s.put(Stored.SKEWER_COMBOS_KEY, 16)
        repeat(5) { Stored.addSkewerCombo(s, NOON) }
        assertEquals(Stored.Day("5 of 16 combos counted today", false), restarted("skewer"))
        assertEquals(0, Stored.skewer(s, NOON).doneToday)

        // Nothing standing: written all the same, and nothing to say.
        for (key in Stored.RESTARTS) assertNull(restarted(key), key)
        // A row that keeps no day is not written at all.
        writes.clear()
        assertNull(Stored.restart(s, "summon", NOON))
        assertTrue(writes.isEmpty(), "$writes")
    }

    /**
     * What the row and the log read as the day: the lock and the counts only
     * while they stand, the field's clock only while it is ahead.
     */
    @Test
    fun `a day that has run out is no day`() {
        val s = MapSettings()
        val reset = QuestSkill.nextReset(NOON)
        Stored.lockQuest(s, reset, "out of tickets")
        repeat(16) { Stored.addSkewerCombo(s, NOON) }
        Stored.rememberFarm(s, NOON - 60, null, "ripe")
        assertNotNull(Stored.day(s, "quest", NOON))
        assertNull(Stored.day(s, "quest", reset), "08:00 has lifted the lock")
        assertNull(Stored.day(s, "skewer", reset), "08:00 has started the count again")
        assertNull(Stored.day(s, "farm", NOON), "a visit already due holds nothing back")
        assertNull(Stored.day(s, "summon", NOON))
    }

    private companion object {
        /** 2026-09-23 12:00 in Vienna (CEST, UTC+2). */
        const val NOON = 1790157600.0
    }
}
