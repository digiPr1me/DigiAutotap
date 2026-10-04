package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The saved preset profiles (Stored.presetProfiles, design C of
 * 2026-09-26): what a first opening shows, that a slot is always 1 to 10,
 * add and remove, and that a switch arms exactly the profile it names.
 */
class PresetProfilesTest {

    private val all = Preset.Place.values().toList()

    @Test
    fun `a first opening has three profiles, every slot 1, and nothing armed`() {
        val s = MapSettings()
        val ps = Stored.presetProfiles(s)
        assertEquals(listOf("Bosses", "PvP", "Stages"), ps.map { it.name })
        ps.forEach { p -> assertEquals(all.associateWith { 1 }, p.slots) }
        assertNull(Stored.presetLast(s))
        assertEquals(emptyList(), Stored.preset(s).wanted, "nothing is switched before a button asks")
    }

    @Test
    fun `a slot is always 1 to 10, whatever was typed or stored`() {
        val s = MapSettings()
        val p = Stored.presetProfiles(s)[0]
        Stored.putPresetProfile(s, 0, p.copy(slots = p.slots + (Preset.Place.FOOD to 0) + (Preset.Place.SUPPORT to 42)))
        val back = Stored.presetProfiles(s)[0]
        assertEquals(1, back.slots[Preset.Place.FOOD])
        assertEquals(10, back.slots[Preset.Place.SUPPORT])
        s.putInts("preset_profile_1", mapOf("digivice" to -3))
        assertEquals(1, Stored.presetProfiles(s)[1].slots[Preset.Place.DIGIVICE])
    }

    /**
     * A profile saved before Overdrive was a place (every profile of 1.2)
     * holds five numbers; it reads slot 1 for the sixth, PRESET_SLOT_DEFAULT
     * (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.3 point 3), keeps the five it has,
     * and arming it asks for Overdrive 1 -- until the sheet says otherwise.
     */
    @Test
    fun `a profile saved before Overdrive reads slot 1 for it and keeps its five`() {
        assertEquals(6, all.size)
        assertEquals("overdrive", Preset.Place.OVERDRIVE.key)
        val s = MapSettings()
        s.putInts("preset_profile_1", mapOf("digivice" to 2, "tactical" to 2, "food" to 2,
                                            "skill_cards" to 3, "support" to 3))
        val old = Stored.presetProfiles(s)[1]
        assertEquals(1, old.slots[Preset.Place.OVERDRIVE])
        assertEquals(listOf(2, 2, 2, 3, 3), all.dropLast(1).map { old.slots[it] })
        Stored.armPreset(s, 1)
        assertEquals(1, s.num("preset_overdrive", 0.0).toInt())
        assertEquals(all, Stored.preset(s).wanted)
        // Set in the sheet, it is the profile's like the five.
        Stored.putPresetProfile(s, 1, old.copy(slots = old.slots + (Preset.Place.OVERDRIVE to 2)))
        Stored.armPreset(s, 1)
        assertEquals(2, Stored.preset(s).slots[Preset.Place.OVERDRIVE])
    }

    @Test
    fun `arming a profile writes its six slots and remembers it`() {
        val s = MapSettings()
        val pvp = Stored.presetProfiles(s)[1]
        Stored.putPresetProfile(s, 1, pvp.copy(slots = all.associateWith { 2 } + (Preset.Place.SKILL_CARDS to 3)))
        Stored.armPreset(s, 1)
        assertEquals(1, Stored.presetLast(s))
        val armed = Stored.preset(s)
        assertEquals(all, armed.wanted)
        assertEquals(3, armed.slots[Preset.Place.SKILL_CARDS])
        assertEquals(2, armed.slots[Preset.Place.DIGIVICE])
    }

    @Test
    fun `up to five profiles, at least one, and remove keeps the others in order`() {
        val s = MapSettings()
        assertEquals(3, Stored.addPresetProfile(s))
        assertEquals(4, Stored.addPresetProfile(s))
        assertNull(Stored.addPresetProfile(s), "a sixth")
        assertEquals(listOf("Bosses", "PvP", "Stages", "Profile 4", "Profile 5"), Stored.presetProfiles(s).map { it.name })

        Stored.armPreset(s, 2)                          // Stages
        Stored.removePresetProfile(s, 1)                // PvP goes
        assertEquals(listOf("Bosses", "Stages", "Profile 4", "Profile 5"), Stored.presetProfiles(s).map { it.name })
        assertEquals(1, Stored.presetLast(s), "the last asked for follows its profile")
        Stored.removePresetProfile(s, 1)                // the last asked for goes
        assertNull(Stored.presetLast(s))
        repeat(5) { Stored.removePresetProfile(s, 0) }
        assertEquals(1, Stored.presetProfiles(s).size)
        assertNull(s.data["preset_profile_1"], "a removed profile leaves no keys behind")
    }
}
