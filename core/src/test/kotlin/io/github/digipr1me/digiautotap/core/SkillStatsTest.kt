package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Mat
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The TODAY card's numbers: what a pass counted, added up, and rolled at midnight. */
class SkillStatsTest {

    private val day = 20_000L

    @Test
    fun `passes add up, and the card says them in one line`() {
        val s = MapSettings()
        assertNull(SkillStats.sentence("dungeon", SkillStats.read(s, "dungeon", day)))
        SkillStats.add(s, "dungeon", mapOf("tickets" to 2, "attempts" to 4, "fights" to 3,
                                           "lost" to 2, "ads" to 0), day)
        SkillStats.add(s, "dungeon", mapOf("tickets" to 2, "attempts" to 2, "fights" to 2,
                                           "cleared" to 1, "ads" to 1), day)
        assertEquals(mapOf("tickets" to 4, "fights" to 5, "lost" to 2, "cleared" to 1, "ads" to 1),
                     SkillStats.read(s, "dungeon", day))
        assertEquals("4 tickets used, 5 fights, 2 runs lost, 1 cleared at once, 1 ad watched.",
                     SkillStats.sentence("dungeon", SkillStats.read(s, "dungeon", day)))
    }

    /**
     * The dungeon card counted attempts until 2026-09-23 and counts tickets
     * now. An `attempts` of the morning is not shown any more, and it is
     * still cleared at midnight: [SkillStats.RETIRED].
     */
    @Test
    fun `the dungeon card's retired attempts are still cleared at midnight`() {
        val s = MapSettings()
        s.put(SkillStats.DAY_KEY, day)
        s.put(SkillStats.key("dungeon", "attempts"), 6)
        assertTrue(SkillStats.read(s, "dungeon", day).isEmpty(), "attempts are not on the card")
        SkillStats.add(s, "dungeon", mapOf("tickets" to 1), day + 1)
        assertTrue(SkillStats.key("dungeon", "attempts") !in s.data.keys,
                   "yesterday's attempts outlived the day: ${s.data.keys}")
    }

    /**
     * One of a thing is one of a thing. The noun is the label's first word
     * that ends in an s, so "ads watched" loses it and "Digimon visited",
     * which has no such word, is already right.
     */
    @Test
    fun `one of a thing reads as one`() {
        assertEquals("ad watched", SkillStats.singular("ads watched"))
        assertEquals("bond token", SkillStats.singular("bond tokens"))
        assertEquals("Fever Time", SkillStats.singular("Fever Times"))
        assertEquals("quest claimed", SkillStats.singular("quests claimed"))
        assertEquals("Digimon visited", SkillStats.singular("Digimon visited"))
        assertEquals("harvested", SkillStats.singular("harvested"))
    }

    /**
     * The day's best run is the best of the passes and not their sum: three
     * runs of 400, 1,200 and 900 make a best of 1,200, and the card says it
     * with the words in front of the number, because "1,200 best run" is not
     * a sentence.
     */
    @Test
    fun `a best is kept, not added up`() {
        val s = MapSettings()
        SkillStats.add(s, "runner", mapOf("runs" to 1, "best" to 400), day)
        SkillStats.add(s, "runner", mapOf("runs" to 1, "best" to 1_200), day)
        SkillStats.add(s, "runner", mapOf("runs" to 1, "best" to 900), day)
        assertEquals(3, SkillStats.read(s, "runner", day)["runs"])
        assertEquals(1_200, SkillStats.read(s, "runner", day)["best"])
        assertEquals("3 runs, best run 1,200.",
                     SkillStats.sentence("runner", SkillStats.read(s, "runner", day)))
    }

    /**
     * The bond tour has no card of its own: what it walked and what it took
     * go on the Bond token row's, beside the helper's own tokens (CoreService
     * wraps it under that name).
     */
    @Test
    fun `the tour's tokens and the helper's are one line`() {
        val s = MapSettings()
        SkillStats.add(s, "passive", mapOf("tokens" to 1), day)
        SkillStats.add(s, "passive", mapOf("tokens" to 4, "visited" to 15), day)
        assertEquals("5 bond tokens, 15 Digimon visited.",
                     SkillStats.sentence("passive", SkillStats.read(s, "passive", day)))
    }

    /**
     * A line a card has lost still has to be cleared at midnight, or
     * yesterday's number sits in digiautotap.json for good: [SkillStats.clear]
     * walks [SkillStats.SHOWN], and a key taken out of that table is a key
     * nothing else would ever remove.
     */
    @Test
    fun `a retired line is still cleared`() {
        val s = MapSettings()
        s.put(SkillStats.DAY_KEY, day)
        s.put("stats_summon_modes", 3)
        s.put("stats_passive_auto", 2)
        SkillStats.add(s, "summon", mapOf("summons" to 1), day + 1)
        assertTrue(s.data.keys.none { it == "stats_summon_modes" || it == "stats_passive_auto" },
                   s.data.keys.toString())
    }

    /**
     * A skill hands over everything it counted; the card shows a few of
     * them. A key written that [SkillStats.SHOWN] does not name is one
     * [SkillStats.clear] would not clear either, so it would outlive its
     * day -- a live pass wrote `stats_dungeon_skipped` that way.
     */
    @Test
    fun `only what the card shows is written`() {
        val s = MapSettings()
        SkillStats.add(s, "dungeon", mapOf("tickets" to 1, "attempts" to 1, "fights" to 1,
                                           "skipped" to 5, "not_selected" to 2), day)
        assertEquals(setOf(SkillStats.DAY_KEY, "stats_dungeon_tickets", "stats_dungeon_fights"),
                     s.data.keys)
        // And a skill with no card at all writes nothing rather than keys
        // nobody will ever read or clear.
        SkillStats.add(s, "nobody", mapOf("things" to 3), day)
        assertTrue(s.data.keys.none { it.startsWith("stats_nobody") })
    }

    @Test
    fun `yesterday's numbers are not today's`() {
        val s = MapSettings()
        SkillStats.add(s, "summon", mapOf("summons" to 3), day)
        assertEquals(mapOf("summons" to 3), SkillStats.read(s, "summon", day))
        // Read on another day: gone, and gone for every skill at once.
        assertTrue(SkillStats.read(s, "summon", day + 1).isEmpty())
        SkillStats.add(s, "summon", mapOf("summons" to 1), day + 1)
        assertEquals(mapOf("summons" to 1), SkillStats.read(s, "summon", day + 1))
    }

    /**
     * A round that did nothing writes nothing. The passive helper's round is
     * one of these and it comes round every two seconds all day; every `put`
     * rewrites digiautotap.json.
     */
    @Test
    fun `a pass that counted nothing touches no key`() {
        val s = MapSettings()
        SkillStats.add(s, "passive", mapOf("tokens" to 0, "auto" to 0), day)
        val after = LinkedHashMap(s.data)
        SkillStats.add(s, "passive", mapOf("tokens" to 0, "auto" to 0), day)
        assertEquals(after, s.data)
        // And the day stamp is the only thing the first one could have set.
        assertTrue(s.data.keys.none { it.startsWith("stats_passive") })
    }

    /** [Counted] asks the skill for its numbers exactly when it hands the screen back. */
    @Test
    fun `Counted takes the numbers as the skill hands back`() {
        val s = MapSettings()
        var counts = mapOf("clicks" to 7)
        val inner = object : Skill {
            override val key = "tower"
            override val name = "Tower & Ruins"
            override fun worksOn(screen: String) = false
            override fun hasBudget() = true
            override fun work(img: Mat) = Outcome.DONE
            override fun run(): Outcome {
                // A run's numbers are this run's, and they are read after it.
                counts = mapOf("clicks" to 20)
                return Outcome.DONE
            }
        }
        val counted = Counted(inner, { counts }) { k, c -> SkillStats.add(s, k, c, day) }
        assertEquals("tower", counted.key)
        assertTrue(counted.hasBudget())
        assertEquals(Result.DONE, counted.run().result)
        assertEquals(mapOf("clicks" to 20), SkillStats.read(s, "tower", day))
        assertEquals("20 taps.", SkillStats.sentence("tower", SkillStats.read(s, "tower", day)))
    }

    /** Every skill the app builds has a line on its card, and the keys match. */
    @Test
    fun `every skill of the Skills list is counted under a name of its own`() {
        for (key in listOf("dungeon", "summon", "mini", "farm", "tower", "passive", "quest",
                           "runner")) {
            assertTrue(key in SkillStats.SHOWN, key)
        }
    }
}
