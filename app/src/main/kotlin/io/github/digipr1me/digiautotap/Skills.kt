package io.github.digipr1me.digiautotap

import io.github.digipr1me.digiautotap.core.Chain
import io.github.digipr1me.digiautotap.core.HelperLog
import io.github.digipr1me.digiautotap.core.Paywall
import io.github.digipr1me.digiautotap.core.SkillSettings
import io.github.digipr1me.digiautotap.core.Stored

/**
 * One row of the Tasks list (PLAN_ANDROID_DESIGN.md 2.2), in the order the
 * player set on 2026-09-22: World Search, Summon, Meat Field, Dungeons,
 * Bond token, Quest Loop, Gekkomon Run. Bond token stands among them and
 * not under a heading of its own any more, so [ALL] is the one list and
 * there is no second one to keep in step with it.
 *
 * Which rows need a supporter code is core's [Paywall], the player's cut of
 * 2026-10-02: Gekkomon Run, Chef's Special, EX Missions and Presets. Meat
 * Field and Dungeons were a supporter's from 2026-09-23 to that day, and so
 * was the Bond token row's one box, collecting for all of the Digimon; all
 * three are everybody's since, as World Search, Summon, Bond token and the
 * Quest Loop always were.
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

    /** Does the row need a supporter code ([Paywall], one table for the list, the director and the tests). */
    val supporter: Boolean get() = Paywall.needsCode(key)
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
        // No page from 2026-09-22: the six "What to collect" ticks, the three
        // limits and the click delay are gone (SkillSettings), and with them
        // the row that could be switched on and still do nothing. A page
        // again since 2026-09-28, with the one switch the player asked for,
        // the eager dash; the sentence that stood here as `noSettings` is the
        // page's note now.
        SkillRow("mini", "World Search", "mini",
                 semi = "Works on the Digital World Search board. Started from the main " +
                     "screen, it opens the board by itself and goes back afterwards."),
        // "on General", not "when Special Summon is open": the page has four
        // tabs and only the first is this task's. Buddy, SP Support and
        // Overdrive hold draws paid for in bought gems, nothing on them is
        // ever tapped, and the task does not start until General is up
        // (SummonSkill.onGeneral, the player's rule of 2026-09-22).
        SkillRow("summon", "Summon", "summon",
                 semi = "Works on the General tab of Special Summon, and only there. " +
                     "You stay on that tab afterwards."),
        // No page from 2026-09-22: "Keep the field farmed" was the one box
        // on it, unticked by default and read by nobody -- a second switch
        // in front of this row's own (SkillSettings, the farm page). A page
        // again since 2026-09-28 with the two switches the player asked for,
        // the Good and Great seeds and the field's ads
        // (PLAN_MEAT_FIELD_GIESSEN.md 4.5); the sentence that stood here as
        // `noSettings` is the page's note now, with the watering in it.
        SkillRow("farm", "Meat Field", "farm",
                 semi = "Works when the Meat Field is open. You stay there afterwards."),
        // The Lost Sector Tower is this row's too since 2026-09-29 (rowFor),
        // and wakes up on its own page, Crests.
        SkillRow("dungeon", "Dungeons", "dungeon",
                 semi = "Works when the dungeon list is open. You stay on the list " +
                     "afterwards. The Lost Sector Tower works when the Crests page is open, " +
                     "and you stay there."),
        PASSIVE,
        // The switch beside the name includes it, and nothing on the page
        // chooses to do less. There were four boxes here -- work through the
        // quests, play the dungeon quests, do the summon quests, claim only --
        // and the first of them was a second switch in front of this one
        // (SkillSettings, the passive page). What the page has since
        // 2026-09-23 is the one number the player asked for, the quest's own
        // failed attempts before Clear Previous Difficulty; the sentence that stood
        // here as `noSettings` is the page's note now.
        SkillRow("quest", "Quest Loop", "quest",
                 semi = "Works on the main screen while the quest card is showing. " +
                     "You stay on the main screen afterwards."),
        // The Beatbreak event's runner. On the PC it was the one skill that
        // could not leave the PC (frames from the emulator window); on the
        // phone it reads at the phone's own pace and the page's note says so.
        SkillRow("runner", "Gekkomon Run", "runner",
                 semi = "Works when the Gekkomon Run event page is open (the one with Play " +
                     "Game and Missions). Plays runs until your Fever Times are reached " +
                     "and leaves the page open."),
        // Chef's Special, since 2026-09-30 (PLAN_SKEWER.md): the Events
        // window's other minigame, a supporter's like Gekkomon Run (question
        // 5), and one of the two at a time (Stored.MINIGAMES, [include]).
        SkillRow("skewer", "Chef's Special", "skewer",
                 semi = "Works when the Chef's Special menu, its stage popup or a round's result " +
                     "is open. Plays rounds until your combos for the day are counted and leaves " +
                     "the menu open. It does not take over a round that is being played."),
        // Idle Rewards was a row here from 2026-09-28 (PLAN_WERBUNG.md 11 and
        // 16) and left the interface on 2026-10-02, the player's word ("bis
        // auf weiteres deaktivieren und aus dem Interface nehmen",
        // PLAN_BEFUNDE_1_3.md N3 a), with its page, its chain step and its
        // TODAY line. IdleSkill stays in core, unbuilt, as TowerSkill does:
        // a comeback is this row, the page, and the two lines in CoreService.
        // EX Missions, since 2026-09-29 (PLAN_EX_MISSIONS.md): the rewards of
        // the EX Missions tab behind the Missions tile. A supporter's task
        // (the player's answer to question 5), with no clock (question 2):
        // "Claim now" on its page runs it, the chain has it as a step, and in
        // the semi-automatic mode it works the Missions window the player
        // opens (question 6) -- on Collection and EX Missions, not on Daily
        // Missions, which a player only changes to to claim by hand (10.11).
        SkillRow("exmissions", "EX Missions", "exmissions",
                 semi = "Works when the Missions window is open on Collection or EX Missions: " +
                     "goes to EX Missions, claims every reward there and leaves the window open. " +
                     "The Daily Missions tab is left to you. It does not open the window by " +
                     "itself -- \"Claim now\" on this page does, from the main screen, and closes " +
                     "it again afterwards."),
        // Presets, since 2026-09-25 (PLAN_PRESET_SWITCH.md). The one task that
        // never takes a screen by itself: a preset page the player opens is
        // theirs, and the page's "Switch now" is what runs it.
        SkillRow("preset", "Presets", "preset",
                 semi = "Never starts by itself. Pick a profile on this page and tap \"Switch " +
                     "to\": from the main screen it visits all six places, sets them " +
                     "and comes back to the main screen."),
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
    fun rowFor(key: String): SkillRow = when (key) {
        "bond" -> PASSIVE
        // The Lost Sector Tower, the same way (PLAN_DAILY_LOST_SECTOR_PRESETS.md
        // 3.2 point 5): its minutes are a field of the Dungeons page, so it
        // stands or falls with the Dungeons row -- its switch and its
        // supporter code (question 9) -- and has no line in the list.
        LOST_SECTOR -> row("dungeon")
        else -> row(key)
    }

    /** The Lost Sector Tower's skill key (LostSectorSkill), which has no row of its own. */
    const val LOST_SECTOR = "lost_sector"

    /**
     * The name on a step's button in the order sheet: its row's name, which
     * is the list's, and for a step that has no row -- the Lost Sector Tower
     * -- the chain's own name for it (SkillSettings.CHAIN_NAMES). Not [row]:
     * on 2026-10-01 (PLAN_SKEWER.md SK3, LDPlayer instance 0, release build
     * of main 54eee91) "Edit order" ended the app twice at 05:19:26 and
     * 05:21:37 with "NoSuchElementException: Collection contains no element
     * matching the predicate" at Skills.row, from MainActivity.chainField --
     * "lost_sector" has been in SkillSettings.CHAINABLE since 2026-09-29 and
     * has no row, and the sheet asked [row] for every step it offers.
     */
    fun stepName(key: String): String =
        ALL.firstOrNull { it.key == key }?.name ?: SkillSettings.CHAIN_NAMES[key] ?: key

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
        Paywall.locked(r.key, unlocked) -> Paywall.why(r.key, unlocked)!!
        r.key == "summon" && !anySummonMode(store) -> "No mode chosen"
        // A ticket on a card or minutes on the daily dungeon, the skill's own
        // question (Chain.dungeonHasWork, PLAN_DAILY_LOST_SECTOR_PRESETS.md
        // 3.1) -- or, since DL4, minutes on the Lost Sector Tower, which is
        // this row's too (rowFor) and asks Chain.lostSectorHasWork (G10).
        // Only all three at 0 hold the row back: each of the two skills still
        // asks its own question in the chain.
        r.key == "dungeon" && dungeonRowIdle(store) -> "Every dungeon is set to 0 tickets and 0 minutes"
        else -> dayHold(r, store) ?: ""
    }

    /**
     * The row's day, over, in the words the row shows ([holdBack]), or null:
     * what switching it off and on starts again ([include], Stored.restart).
     * Asked of the file, the same way the task asks it, so the row and the
     * chain's skip cannot disagree about whether the day is over.
     *
     *  - The Quest Loop's lock (QuestSkill.lockedForTheDay): the loop stopped
     *    for want of dungeon tickets and is not asked again before the reset.
     *  - Chef's Special and Gekkomon Run at the page's number for the day --
     *    green until 2026-10-02, when only the chain said so ("skipping
     *    skewer -- 15 of 15 combos counted today", the Poco at 18:29:07;
     *    PLAN_ABSCHLUSS_1_3.md A2).
     *  - The Lost Sector Tower at its highest floor (G16), while it is the
     *    Dungeons row's only work: with tickets or daily minutes on the page
     *    the row still has a pass to play.
     *
     * The Meat Field's clock is no limit and keeps the row green: the field
     * is visited again when it is due, and the chain says when.
     */
    fun dayHold(r: SkillRow, store: SettingsStore): String? {
        if (r.key !in setOf("quest", "skewer", "runner", "dungeon")) return null
        if (r.key == "dungeon" && Stored.dungeon(store).let { Chain.dungeonHasWork(it.budgets, it.dailyMinutes) }) {
            return null
        }
        val day = Stored.day(store, r.key) ?: return null
        return if (day.holds) day.says.replaceFirstChar { it.uppercase() } else null
    }

    /** What the sheet says under a row its day holds back ([dayHold]): the way out before the reset. */
    fun againNote(on: Boolean): String =
        if (on) "Switch it off and on to start it again today."
        else "Switching it on starts it again today."

    /** No ticket, no daily minute and no Lost Sector minute on the Dungeons page. */
    fun dungeonRowIdle(store: SettingsStore): Boolean =
        Stored.dungeon(store).let { !Chain.dungeonHasWork(it.budgets, it.dailyMinutes) } &&
            !Chain.lostSectorHasWork(Stored.lostSector(store).minutes)

    /**
     * The row's switch, thrown: the one place both switches (the row's and
     * the sheet's) go through. On its way to *on* it starts the row's day
     * again ([restart]) -- the reason stood on the row and the switch was
     * thrown anyway, which is the player saying "try now", the sentence
     * `QuestSkill.resume` was written for and, since 2026-10-02, every row
     * that keeps a day (PLAN_ABSCHLUSS_1_3.md A2).
     *
     * And a minigame switched on switches the other off (PLAN_SKEWER.md
     * 3.3, the player's rule of 2026-09-30: the game runs one of Gekkomon
     * Run and Chef's Special at a time), in one read, change and write
     * ([Stored.include]). The rows switched off that way come back, for the
     * page to redraw and to say why.
     */
    fun include(r: SkillRow, store: SettingsStore, on: Boolean): List<SkillRow> {
        if (on) restart(r, store)
        val off = Stored.include(store, r.key, on).map { row(it) }
        HelperLog.line("${r.name}: " + if (on) "included" else "left out")
        for (o in off) HelperLog.line("${o.name}: left out -- only one minigame runs at a time")
        return off
    }

    /**
     * A row switched on starts its day again, every time (question 5): the
     * day out of the file in one write (Stored.restart, before the row is
     * in, so that no store sees the row in with the day still over), the
     * log told from which day, and the director told which of its skills
     * the day held back (DirectorLoop.restart) -- the Dungeons row is two of
     * them, its own and the Lost Sector Tower's, and only the tower keeps a
     * day. Rows without a day (Stored.RESTARTS) are left as they are.
     */
    private fun restart(r: SkillRow, store: SettingsStore) {
        if (r.key !in Stored.RESTARTS) return
        val was = Stored.restart(store, r.key)
        if (was != null) HelperLog.line("${r.name}: started again by its switch, from: ${was.says}")
        val held = was?.holds == true
        val director = CoreService.director ?: return
        if (r.key == "dungeon") {
            director.restart("dungeon", false)
            director.restart(LOST_SECTOR, held)
        } else {
            director.restart(r.key, held)
        }
    }

    /**
     * What the sheet of a minigame's row says under its switch: the other
     * one goes off when this one goes on (PLAN_SKEWER.md 3.3). Null for
     * every other row.
     */
    fun minigameNote(r: SkillRow): String? {
        if (r.key !in Stored.MINIGAMES) return null
        val other = Stored.MINIGAMES.filter { it != r.key }.joinToString(" and ") { row(it).name }
        return "Only one minigame runs at a time: switching ${r.name} on switches $other off."
    }

    /**
     * `chain.summon_has_mode` over the three mode switches of the page -- and
     * not the ad switch that stood above them from 2026-09-30 to 2026-10-02
     * (Stored.SUMMON_ADS_KEY, the Ad Rewards card's pick since), which is no
     * mode: with every mode off and the ads on there is nothing to draw.
     */
    fun anySummonMode(store: SettingsStore): Boolean = Chain.summonHasMode(
        SkillSettings.page("summon").fields
            .filterIsInstance<SkillSettings.Toggle>()
            .filter { it.key != Stored.SUMMON_ADS_KEY }
            .map { store.bool(it.key, it.default) })

    /**
     * Is the chain's step [key] skipped for its switch: its row off or
     * locked, or no row at all -- a step a saved order still names after
     * its task left the interface ("idle" since 2026-10-02). Not [rowFor],
     * which throws on such a key.
     */
    fun stepOff(key: String, store: SettingsStore, unlocked: Boolean?): Boolean {
        val r = if (key == LOST_SECTOR) row("dungeon") else ALL.firstOrNull { it.key == key } ?: return true
        return !included(r, store, unlocked)
    }

    /**
     * Why a row's task is not included, in the words the chain's skip and
     * the order's "off" say it, or null where it is. The minigame switched
     * off by the other one is named with it: on 2026-10-01 the Poco's chain
     * began with Gekkomon Run and ran the quest loop first, because Chef's
     * Special had been switched on at 19:53:58 and Gekkomon Run off with it,
     * and the log said only "skipping runner -- not included"
     * (PLAN_BEFUNDE_1_3.md N3 b).
     */
    fun offWhy(r: SkillRow, store: SettingsStore, unlocked: Boolean?): String? {
        if (included(r, store, unlocked)) return null
        if (Paywall.locked(r.key, unlocked)) return "it needs a supporter code"
        val other = Stored.MINIGAMES.filter { it != r.key && r.key in Stored.MINIGAMES }
            .firstOrNull { Stored.included(store, it) }
        if (other != null) {
            return "its switch is off -- only one minigame runs at a time, and ${row(other).name} is on"
        }
        return "its switch in the task list is off"
    }

    /** Locked rows read as off and cannot be switched on ([Paywall.included], which keeps the setting). */
    fun included(r: SkillRow, store: SettingsStore, unlocked: Boolean?): Boolean =
        Paywall.included(store, r.key, unlocked)
}
