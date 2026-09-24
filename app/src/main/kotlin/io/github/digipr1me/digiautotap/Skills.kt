package io.github.digipr1me.digiautotap

import io.github.digipr1me.digiautotap.core.Chain
import io.github.digipr1me.digiautotap.core.HelperLog
import io.github.digipr1me.digiautotap.core.QuestSkill
import io.github.digipr1me.digiautotap.core.SkillSettings
import io.github.digipr1me.digiautotap.core.Stored

/**
 * One row of the Tasks list (PLAN_ANDROID_DESIGN.md 2.2), in the order the
 * player set on 2026-09-22: World Search, Summon, Meat Field, Dungeons,
 * Bond token, Quest Loop, Gekkomon Run. Bond token stands among them and
 * not under a heading of its own any more, so [ALL] is the one list and
 * there is no second one to keep in step with it.
 *
 * Which rows need a supporter code is the player's decision of 2026-09-23:
 * Meat Field, Gekkomon Run, and since that evening Dungeons. World Search,
 * Summon, Bond token and the Quest Loop are everybody's -- the Quest Loop
 * still plays the dungeon quests it is given, which is its own way in and
 * not this row -- but the Bond token row's one box, collecting for all of
 * the Digimon, is a supporter's ([SkillSettings.Toggle.supporter]).
 *
 * The settings themselves stay in `core`'s [SkillSettings], which is where
 * app.py's fields, defaults and ranges are kept in step with the PC. This
 * list only says which page a row opens. The `passive` page used to be cut
 * in two by a key prefix, because core kept the quest loop and the passive
 * helper on one page as app.py does while the design showed them as two
 * rows; the quest half of that page is gone and the page is the Bond token
 * row's alone.
 *
 * What is **not** here on purpose: a Start button. Whether a skill is
 * included is this switch; when it runs is the director's.
 */
class SkillRow(
    val key: String,
    val name: String,
    /** The [SkillSettings] page this row opens, where it has one. */
    val page: String?,
    val supporter: Boolean = false,
    /** The card that says on which screen the skill wakes up. */
    val semi: String,
    /**
     * What the Settings card says where the row has no fields of its own.
     * The default is the one that was there before any skill had settings;
     * the Quest Loop's own is not a placeholder but the point -- it has
     * nothing to set because its switch does everything.
     */
    val noSettings: String = "Nothing to set.",
) {
    /**
     * "Am I in?" -- one key per row in digiautotap.json, beside the settings
     * the PC already keeps there. The director reads it, every round, to
     * decide whom a screen may go to. The key itself is core's
     * ([Stored.includedKey]), because the director asks the same question
     * from the other side.
     */
    val includedKey: String get() = Stored.includedKey(key)
}

object Skills {

    val PASSIVE = SkillRow("passive", "Bond token", "passive",
                           semi = "Works on the main screen, alongside the other tasks: " +
                               "collects the bond token of the Digimon you are raising.")

    val ALL = listOf(
        // "mini", not "world": the key is the one chain.py's CHAINABLE names
        // and the one WorldSearchSkill answers to. While the row was called
        // something else, `Skills.row(skill.key)` could not find its own
        // skill -- which nothing noticed while the skill was a stand-in built
        // from this list.
        // No page either, since 2026-09-22: the six "What to collect" ticks,
        // the three limits and the click delay are gone (SkillSettings), and
        // with them the row that could be switched on and still do nothing.
        SkillRow("mini", "World Search", null,
                 semi = "Works on the Digital World Search board. Started from the main " +
                     "screen, it opens the board by itself and goes back afterwards.",
                 noSettings = "Nothing to set: when switched on, World Search plays the board " +
                     "and collects everything on it -- tickets, claws, paws and fireballs -- " +
                     "until the paws run out."),
        // "on General", not "when Special Summon is open": the page has four
        // tabs and only the first is this task's. Buddy, SP Support and
        // Overdrive hold draws paid for in bought gems, nothing on them is
        // ever tapped, and the task does not start until General is up
        // (SummonSkill.onGeneral, the player's rule of 2026-09-22).
        SkillRow("summon", "Summon", "summon",
                 semi = "Works on the General tab of Special Summon, and only there. " +
                     "You stay on that tab afterwards."),
        // No page either, since 2026-09-22: "Keep the field farmed" was the
        // one box on it, unticked by default and read by nobody -- a second
        // switch in front of this row's own (SkillSettings, the farm page).
        SkillRow("farm", "Meat Field", null, supporter = true,
                 semi = "Works when the Meat Field is open. You stay there afterwards.",
                 noSettings = "Nothing to set: when switched on, the task harvests what is " +
                     "ripe and plants free seeds in the empty plots. In fully automatic " +
                     "mode it goes to the Meat Field by itself when there is something to " +
                     "do, and comes back afterwards."),
        SkillRow("dungeon", "Dungeons", "dungeon", supporter = true,
                 semi = "Works when the dungeon list is open. You stay on the list " +
                     "afterwards."),
        PASSIVE,
        // The switch beside the name includes it, and nothing on the page
        // chooses to do less. There were four boxes here -- work through the
        // quests, play the dungeon quests, do the summon quests, claim only --
        // and the first of them was a second switch in front of this one
        // (SkillSettings, the passive page). What the page has since
        // 2026-09-23 is the one number the player asked for, the quest's own
        // attempts before Clear Previous Difficulty; the sentence that stood
        // here as `noSettings` is the page's note now.
        SkillRow("quest", "Quest Loop", "quest",
                 semi = "Works on the main screen while the quest card is showing. " +
                     "You stay on the main screen afterwards."),
        // The Beatbreak event's runner. On the PC it was the one skill that
        // could not leave the PC (frames from the emulator window); on the
        // phone it reads at the phone's own pace and the page's note says so.
        SkillRow("runner", "Gekkomon Run", "runner", supporter = true,
                 semi = "Works when the Gekkomon Run event page is open (the one with Play " +
                     "Game and Missions). Plays runs until your Fever Times are reached " +
                     "and leaves the page open."),
    )

    fun row(key: String): SkillRow = ALL.first { it.key == key }

    /**
     * The row whose switch governs a skill, which for every skill but one is
     * its own row. The bond tour is the exception: it is the Bond token
     * row's one box (`passive_all_digimon`, which
     * `BondTourSkill.hasBudget` asks for itself), so it has no line in the
     * list and stands or falls with the helper it walks behind. The director
     * asks this rather than [row] because [row] would throw on a key that is
     * not a row, and a skill it cannot place must not be a crash.
     */
    fun rowFor(key: String): SkillRow = if (key == "bond") PASSIVE else row(key)

    /**
     * What holds a skill back, in the words the list shows beside its name.
     *
     * These are the director's own questions, asked here so that the list
     * answers what the chain would answer -- [Chain]'s six, over [Stored]'s
     * keys, which is the whole reason both of those are in one place now.
     * The line the row shows and the skip the log writes therefore cannot
     * drift apart. Two of these lines have gone the way their settings went:
     * "Nothing ticked to collect" with World Search's six ticks, and "No
     * point set yet" with Tower & Ruins, which left the interface on
     * 2026-09-22.
     */
    fun holdBack(r: SkillRow, store: SettingsStore, unlocked: Boolean?): String = when {
        r.supporter && unlocked == null -> "Checking the supporter code..."
        r.supporter && unlocked != true -> "Needs a supporter code"
        r.key == "summon" && !anySummonMode(store) -> "No mode chosen"
        r.key == "dungeon" && !Chain.dungeonHasBudget(Stored.dungeon(store).budgets) ->
            "Every dungeon is set to 0 tickets"
        // The day's lock (QuestSkill.lockedForTheDay): the loop stopped for
        // want of dungeon tickets and is not asked again before the reset.
        // Asked of the file, the same way the loop asks it, so the row and
        // the log cannot disagree about whether the loop is stopped.
        r.key == "quest" && questLocked(store) ->
            "Out of dungeon tickets until ${QuestSkill.whenText(Stored.questLockedUntil(store)!!)}"
        else -> ""
    }

    /** Is the quest loop's lock (`quest_locked_until`) still ahead of the wall clock? */
    fun questLocked(store: SettingsStore): Boolean {
        val until = Stored.questLockedUntil(store) ?: return false
        return System.currentTimeMillis() / 1000.0 < until
    }

    /**
     * The row's switch, thrown: the one place both switches (the row's and
     * the sheet's) go through. On its way to *on* it takes the quest
     * loop's day lock away -- the reason stood on the row and the switch
     * was thrown anyway, which is the player saying "try now", the same
     * sentence `QuestSkill.resume` was written for.
     */
    fun include(r: SkillRow, store: SettingsStore, on: Boolean) {
        if (on && r.key == "quest" && Stored.questLockedUntil(store) != null) {
            Stored.unlockQuest(store)
            HelperLog.line("${r.name}: the day's lock taken away by hand")
        }
        store.put(r.includedKey, on)
        HelperLog.line("${r.name}: " + if (on) "included" else "left out")
    }

    /** `chain.summon_has_mode` over the three mode switches of the page. */
    fun anySummonMode(store: SettingsStore): Boolean = Chain.summonHasMode(
        SkillSettings.page("summon").fields
            .filterIsInstance<SkillSettings.Toggle>()
            .map { store.bool(it.key, it.default) })

    /**
     * Locked rows read as off and cannot be switched on. The setting itself
     * is left alone while a code is missing: redeeming one later has to give
     * the player back what they had switched on, not a row of defaults.
     */
    fun included(r: SkillRow, store: SettingsStore, unlocked: Boolean?): Boolean =
        if (r.supporter && unlocked != true) false else Stored.included(store, r.key)
}
