package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

        // Summons: three modes on, no ceiling -- and the ads off, because
        // the Ad Skip Pass is off until the player says otherwise
        // (2026-09-24), in all three places that watch a free ad.
        assertEquals(SummonSkill.Settings(watchAdsFirst = false), Stored.summon(empty))
        assertFalse(Stored.adPass(empty))
        assertFalse(Stored.dungeon(empty).useAds)
        assertFalse(Stored.quest(empty).adPass)

        // World Search: no settings at all since 2026-09-22, so this is
        // WorldSearchSettings' own defaults -- everything collected, no
        // limit anywhere, and the pace that speeds up while it goes well.
        val mini = Stored.worldSearch()
        assertEquals(WorldConst.ALL_WANTED, mini.wanted)
        assertEquals(0, mini.minPaws)      // the page's 0, not engine.Settings' 20
        assertEquals(0, mini.maxActions)
        assertEquals(0, mini.targetMeters)
        assertEquals(0.7, mini.clickDelay)
        assertTrue(mini.adaptive)
        assertEquals(180, mini.waitForBoard)
        assertTrue(mini.navigate)

        // Meat Field: never visited is due (chain.farm_is_due).
        assertNull(Stored.farmDueAt(empty))
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
            "tower_fx" to 0.5, "tower_fy" to 0.25, "tower_interval" to 1.5,
            Stored.includedKey("farm") to false,
            SkillSettings.MODE_KEY to SkillSettings.MODE_FULL,
            "chain_steps" to listOf(Chain.Step("tower", 12)),
            "chain_repeat" to true,
            // The pass on, and the two old boxes off in the same file: the
            // pass is what the three readers answer to, the boxes nothing.
            SkillSettings.AD_PASS_KEY to true, "use_ads" to false, "summon_ads" to false))
        assertTrue(Stored.adPass(s))
        assertTrue(Stored.dungeon(s).useAds)
        assertTrue(Stored.summon(s).watchAdsFirst)
        assertTrue(Stored.quest(s).adPass)
        assertEquals(0, Stored.dungeon(s).budgets[0])
        assertEquals(2, Stored.dungeon(s).budgets[1])
        // Untouched cards still get the day's default.
        assertEquals(SkillSettings.DAY_ATTEMPTS, Stored.dungeon(s).budgets[2])
        assertFalse(Stored.summon(s).skill)
        assertEquals(3, Stored.summon(s).maxPerMode)
        assertEquals(1_700_000_000.0, Stored.farmDueAt(s))
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
        // The signature is the proof: there is no [Settings] to read from,
        // so the keys below -- which every file written before 2026-09-22
        // still holds -- cannot reach the skill however they stand.
        val mini = Stored.worldSearch()
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
}
