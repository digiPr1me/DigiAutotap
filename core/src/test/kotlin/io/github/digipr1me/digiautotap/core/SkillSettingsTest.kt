package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
        assertTrue(keys.none { it == "use_ads" || it == "summon_ads" },
                   "an ad box on a task page: the pass is one switch")
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
}
