package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The budget questions that now live beside the chain that asks them
 * (PLAN_ANDROID_APP.md 4): chain.py's six free functions, and the skills
 * that used to carry a copy of each.
 *
 * The cases are chain.py's own edges, the ones its docstrings name: a
 * dungeon at 0 is not a budget, no mode ticked is nothing to do, an
 * interval of 0 has to answer "no bound" rather than be divided by, and a
 * Meat Field that has never been visited is due.
 */
class ChainBudgetTest {

    @Test
    fun `dungeon_has_budget is a card above zero`() {
        assertFalse(Chain.dungeonHasBudget(emptyMap()))
        assertFalse(Chain.dungeonHasBudget(mapOf(0 to 0, 1 to 0)))
        assertTrue(Chain.dungeonHasBudget(mapOf(0 to 0, 1 to 2)))
    }

    @Test
    fun `summon_has_mode is any mode at all`() {
        assertFalse(Chain.summonHasMode(emptyList()))
        assertFalse(Chain.summonHasMode(listOf(false, false, false)))
        assertTrue(Chain.summonHasMode(listOf(false, true, false)))
        // And the skill's own Settings asks exactly that, of its three modes
        // -- summon_ads is not one of them, as the page's own note says.
        assertFalse(SummonSkill.Settings(skill = false, support = false, crest = false,
                                         watchAdsFirst = true).anyMode())
        assertTrue(SummonSkill.Settings(skill = false, support = false, crest = true).anyMode())
    }

    @Test
    fun `mini_has_target is anything ticked`() {
        assertFalse(Chain.miniHasTarget(emptyList()))
        assertTrue(Chain.miniHasTarget(listOf("paw")))
    }

    @Test
    fun `tower_has_point needs both halves`() {
        assertTrue(Chain.towerHasPoint(0.5, 0.5))
        // 0.0 is a point: the top left corner of the game is somewhere, and
        // only null says nothing was ever saved.
        assertTrue(Chain.towerHasPoint(0.0, 0.0))
        assertFalse(Chain.towerHasPoint(null, 0.5))
        assertFalse(Chain.towerHasPoint(0.5, null))
        assertFalse(Chain.towerHasPoint(null, null))
        assertEquals(Chain.towerHasPoint(0.5, 0.5), TowerSkill.hasPoint(0.5, 0.5))
        assertEquals(Chain.towerHasPoint(null, null), TowerSkill.hasPoint(null, null))
    }

    @Test
    fun `farm_is_due counts a field never visited as due`() {
        assertTrue(Chain.farmIsDue(null, 1000.0))
        assertTrue(Chain.farmIsDue(1000.0, 1000.0))
        assertTrue(Chain.farmIsDue(999.0, 1000.0))
        assertFalse(Chain.farmIsDue(1001.0, 1000.0))
        assertEquals(Chain.farmIsDue(null, 5.0), FarmSkill.farmIsDue(null, 5.0))
        assertEquals(Chain.farmIsDue(9.0, 5.0), FarmSkill.farmIsDue(9.0, 5.0))
    }

    @Test
    fun `tower_limit converts minutes once, and 0 is no bound`() {
        assertEquals(0, Chain.towerLimit(0, 3.0))
        assertEquals(0, Chain.towerLimit(10, 0.0))
        assertEquals(200, Chain.towerLimit(10, 3.0))
        // At least one tap where any time at all was asked for.
        assertEquals(1, Chain.towerLimit(1, 600.0))
        // Python's round is half-to-even: 90 s at 4 s apart is 22.5.
        assertEquals(22, Chain.towerLimit(3, 8.0))
        assertEquals(TowerSkill.limit(10, 3.0), Chain.towerLimit(10, 3.0))
    }

    /**
     * The one thing this move must not do: change what a skill answers. Each
     * of these asked the same question in its own words before.
     */
    @Test
    fun `the skills answer through the chain's rules`() {
        val dungeon = DungeonSkill(DirectorTest.FakeCapture(), { DungeonSkill.Settings(budgets = mapOf(0 to 0)) })
        assertFalse(dungeon.hasBudget())
        val playing = DungeonSkill(DirectorTest.FakeCapture(), { DungeonSkill.Settings(budgets = mapOf(0 to 3)) })
        assertTrue(playing.hasBudget())

        val summon = SummonSkill(DirectorTest.FakeCapture(),
            { SummonSkill.Settings(skill = false, support = false, crest = false) })
        assertFalse(summon.hasBudget())

        val tower = TowerSkill(DirectorTest.FakeCapture(), point = { null }, interval = { 3.0 })
        assertFalse(tower.hasBudget())
        val aimed = TowerSkill(DirectorTest.FakeCapture(), point = { 0.5 to 0.5 }, interval = { 3.0 })
        assertTrue(aimed.hasBudget())

        val farm = FarmSkill(DirectorTest.FakeCapture(), dueAt = { null }, remember = { _, _, _ -> })
        assertTrue(farm.hasBudget())
    }
}
