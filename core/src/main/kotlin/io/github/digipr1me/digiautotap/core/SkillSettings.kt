package io.github.digipr1me.digiautotap.core

/**
 * The settings of every skill page, as the PC's app.py has them: the same
 * keys in the same settings file (digiautotap.json), the same labels, the
 * same defaults and ranges (PLAN_ANDROID_5_SHELL.md 3.4: nothing new is
 * invented here). The page draws itself from this list and the skills read
 * their settings by these keys, so a field lives in one place on this side.
 *
 * This is where a field is added, dropped or a default changed: the
 * laboratory is retired and app.py no longer holds the contract
 * (PLAN_STANDALONE.md 3, SkillSettingsTest). A field dropped here is
 * dropped in the skill's Settings and in Stored too, so no key is read
 * that nobody can set -- an old digiautotap.json simply keeps a value nothing
 * asks for.
 *
 * No start buttons: the director starts the skills.
 */
object SkillSettings {

    sealed class Field(val key: String, val label: String)

    /**
     * A box. [supporter] marks a box that is a supporter's feature on a task
     * that is not: the page greys it out without a code, and the skill that
     * reads it asks the code again for itself. There was one, the Bond token
     * page's all-Digimon box, from 2026-09-23 to 2026-10-02 ([Paywall]); the
     * mark stays for the next.
     */
    class Toggle(key: String, label: String, val default: Boolean, val note: String = "",
                 val supporter: Boolean = false) : Field(key, label)

    /**
     * A number. [heading] marks the first number of a block of its own: the
     * page draws a rule above it and the heading under the rule, as the
     * Dungeons page's minutes stand under the tickets (the only block there
     * is, PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.4).
     */
    class Number(key: String, label: String, val default: Double, val min: Double,
                 val max: Double, val decimal: Boolean = false, val note: String = "",
                 val heading: String = "")
        : Field(key, label)

    /**
     * "attempts": {"0": n, ...}, one per dungeon card in [DUNGEON_NAMES]
     * order, with the "Set all" stamp above them. Tickets to spend since
     * 2026-09-23; the key is the old one, so an update keeps the numbers
     * (DungeonSkill.Settings.budgets), and so is the class's name.
     */
    class DungeonAttempts : Field("attempts", "How many tickets per dungeon")

    /** "chain_steps": [{"key": ..., "minutes": ...}, ...] as chain.Step.to_dict. */
    class ChainSteps : Field("chain_steps", "The chain")

    /** The preset profiles, each a name and a slot 1 to 10 per place ([Stored.presetProfiles]). */
    class PresetProfiles : Field("preset_profiles", "Profiles")

    /**
     * A button, not a setting: the task [task] runs once, from the next plain
     * main screen (DirectorLoop.runNow), as the Presets page's "Switch to"
     * runs Presets. Nothing is written under [key]; it is a key only so that
     * a page's fields stay one list. [note] is said beside the button.
     */
    class RunNow(val task: String, label: String, val note: String = "") : Field("${task}_now", label)

    /** Said wherever a preset switch is asked for: the two things it needs (the player's words, 2026-09-26). */
    const val PRESET_NEEDS = "The game must be on the main screen, and the overlay dot must not be grey."

    /**
     * Preset places the game does not have yet, shown greyed out under the
     * real ones in the Presets sheet, with no field and nothing to tap
     * (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.3 point 4). Gear comes with the
     * game's next update; the player's call of 2026-09-28 (question 6) is a
     * line in the interface and no code: no [Preset.Place], no key, no
     * slot in a profile. The day the update is out, Gear is measured like
     * Overdrive was and becomes a place, and leaves this list.
     */
    val PRESET_PLACEHOLDERS = listOf("Gear")
    const val PRESET_PLACEHOLDER_NOTE = "Comes with the game's next update."

    class Page(val key: String, val title: String, val why: String, val fields: List<Field>,
               val supporter: Boolean = false, val note: String = "")

    // dungeon.py's DUNGEON_NAMES, in card order. The hidden one keeps its
    // place in the list at 0 attempts, because the skill counts cards: only
    // its control goes away (app.py, HIDDEN_DUNGEONS).
    val DUNGEON_NAMES = listOf(
        "Apocalymon Wall", "Fight! DemiDevimon", "Fight! Bakemon", "Fight! Digifactory",
        "Network Defense Ops", "Metal Sea", "Daily changing dungeon")
    val HIDDEN_DUNGEONS = setOf("Daily changing dungeon")
    // app.py: what an unset dungeon starts at, and what "Set all" offers --
    // in tickets since 2026-09-23: two a day and two ad tickets.
    const val DAY_ATTEMPTS = 4
    const val SET_ALL_DEFAULT = 5

    // chain.py: which keys a step may name, and the chain nobody has built yet.
    val CHAIN_WAITING = listOf("quest")
    // "lost_sector" since 2026-09-29 (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.6):
    // a step of its own, added in the sheet by whoever wants it and not in
    // CHAIN_DEFAULT (the player's answer to question 3). The daily dungeon
    // needs no step: it lies on the Dungeons step's list.
    // "exmissions" since 2026-09-29 (PLAN_EX_MISSIONS.md, the player's answer
    // to question 7): offered in the chain's sheet, not in CHAIN_DEFAULT.
    // "skewer" since 2026-09-30 (PLAN_SKEWER.md 3.1 and 4.2 point 3): Chef's
    // Special, the other minigame of the Events window, right after
    // Gekkomon Run; offered in the sheet, not in CHAIN_DEFAULT.
    // "idle" was one from 2026-09-28 to 2026-10-02, when Idle Rewards left the
    // interface (PLAN_BEFUNDE_1_3.md N3 a); a saved chain that still names it
    // is skipped as any unknown key is ("no such skill on the phone").
    val CHAINABLE = listOf("dungeon", "summon", "mini", "farm", "runner", "skewer", "lost_sector",
                           "exmissions")
    // There were two more, `quest_on` and `quest_off`: steps that ticked and
    // unticked the quest loop's own switch in the middle of a chain. The
    // switch they flipped is gone (the passive page below), and no skill on
    // the phone ever answered to either key -- the director skipped both with
    // "no such skill on the phone" -- so the picker offered two steps that
    // did nothing whichever way the chain ran.
    //
    // "tower" left with them on 2026-09-22, and with it the only step that
    // ever carried minutes: a `Step`'s `minutes` is read by nothing now, and
    // an old digiautotap.json that still names a tower step is skipped the
    // way any unknown key is ("no such skill on the phone").
    val CHAIN_DEFAULT = listOf("quest", "dungeon", "summon")
    val CHAIN_NAMES = mapOf(
        "quest" to "Quest Loop (until it is done)",
        "dungeon" to "Dungeons",
        "summon" to "Special Summon",
        "mini" to "Digital World Search",
        "farm" to "Meat Field",
        "runner" to "Gekkomon Run",
        "skewer" to "Chef's Special",
        "lost_sector" to "Lost Sector Tower",
        "exmissions" to "EX Missions",
    )

    /** Semi- or fully automatic (PLAN_ANDROID_APP.md 5.1), one setting. */
    const val MODE_KEY = "mode"
    const val MODE_SEMI = "semi"
    const val MODE_FULL = "full"
    const val MODE_DEFAULT = MODE_SEMI

    /**
     * The game's Ad Skip Pass, one setting for the whole app and not a
     * page field (the player's decision of 2026-09-24, PLAN_AD_PASS.md).
     * With the pass every reward the game puts behind an ad comes without
     * the video -- the two ad tickets of a dungeon, the two free draws of
     * a summon mode. Without it the same tap opens an ad video, an
     * activity of its own, and the app never touches one: without the pass
     * no free ad is tapped at all ([FreeAds]; notes/ads.md, "The app never
     * touches an ad, and nothing that closed one ships"). Which tasks take
     * their free ads is each task's pick ([Stored.AD_PICKS], off by
     * default).
     *
     * Off by default: off with the pass leaves a free reward lying, which
     * the player sees on the card; on without the pass makes the game ask
     * for an ad all the same, and the task parks on it with nothing tapped
     * ([FreeAds.PARK]). It stood as two task boxes until 2026-09-24,
     * `use_ads` on Dungeons and `summon_ads` on Summon, both on by default
     * and neither asked by the quest loop, which built its bots with the
     * ads hard-wired on. An old digiautotap.json may keep the two keys,
     * `ad_pass_seen` and `ad_watch` as well; nothing reads them, and the
     * switches of 2026-09-30 have new keys so that it never will.
     *
     * Since 2026-10-02 it is the head of the main page's Ad Rewards card.
     */
    const val AD_PASS_KEY = "ad_pass"
    const val AD_PASS_DEFAULT = false

    /**
     * Which installed app is the game, as the player picked it under
     * Settings; no key (or an empty one) is "find it" ([Game.pick]). Only
     * the app reads it, and it stands here because the keys are one table.
     */
    const val GAME_KEY = "game_package"

    // The three pages' ad switches -- "Watch the free ads for tickets" on
    // Dungeons, "Watch the free ads first" on Summon, "Watch the free ads
    // for seeds and cans" on Meat Field, each its page's first line, and the
    // note they shared -- went onto the main page's Ad Rewards card on
    // 2026-10-02 as its picks, keys and all (Stored.AD_PICKS, question 13
    // of PLAN_ABSCHLUSS_1_3.md). The sheets point there.
    val PAGES: List<Page> = listOf(
        Page("dungeon", "Dungeons", "Spends the day's dungeon tickets.", listOf(
            DungeonAttempts(),
            // Failed attempts since 2026-10-04, the player's words
            // (DungeonSkill.Settings.countLostOnly); the key is the old one,
            // so that nobody's number went with the change.
            Number(Stored.DUNGEON_CLEAR_KEY, "Failed attempts before Clear Previous Difficulty",
                   DungeonSkill.ATTEMPTS_BEFORE_CLEAR.toDouble(), 0.0, 99.0,
                   note = DUNGEON_CLEAR_NOTE),
            // "Use ad tickets" (`use_ads`) stood here until 2026-09-24, with
            // the note "Ads are only watched if you have bought the ad
            // pass" -- which is to say the box was the pass question in
            // disguise. It is [AD_PASS_KEY] now, once, on the main page, and
            // the switch at the top of this page is whether, not how.
            //
            // Under a rule since 2026-09-29, the two that are played by the
            // minute rather than by the ticket (PLAN_DAILY_LOST_SECTOR_
            // PRESETS.md 3.4): the daily dungeon, "Daily Dungeon" on the page
            // (the player's name, question 1) and "Daily changing dungeon" in
            // the log, and the Lost Sector Tower. Below the tickets' number
            // for Clear Previous Difficulty, which belongs to the tickets, so
            // that the rule parts the page in two. No ceiling a player meets
            // (question 11): 10,000 is only the number the field needs, and
            // the page names no limit. "Set all" stamps the tickets alone.
            Number(Stored.DUNGEON_DAILY_KEY, "Daily Dungeon", 0.0, 0.0, MINUTES_MAX,
                   heading = "How many minutes"),
            // Read by LostSectorSkill since DL4 (Stored.lostSector). On this
            // page because the tower has no row of its own: it stands or
            // falls with the Dungeons row (Skills.rowFor).
            Number(Stored.LOST_SECTOR_MINUTES_KEY, "Lost Sector Tower", 0.0, 0.0, MINUTES_MAX,
                   note = MINUTES_NOTE),
        )),
        Page("summon", "Special Summon",
             "Spends summon tickets on the General tab, mode by mode.", listOf(
            Toggle("summon_skill", "Skill Card Summon", true),
            Toggle("summon_support", "Support Digimon Summon", true),
            Toggle("summon_crest", "Crest Summon", true),
            // "Watch the free ads first" (`summon_ads`) stood here until
            // 2026-09-24; it was [AD_PASS_KEY] until 2026-09-30, the switch
            // at the top of this page under a new key until 2026-10-02, and
            // the Ad Rewards card's Summon pick since.
            Number("summon_max", "Summons per mode (0 = all)", 0.0, 0.0, 500.0),
        ),
             // Said on the page because it is the one thing about this task a
             // player has to do themselves, and because the Summon icon does
             // not land there: it opens whichever tab the game pleases
             // (SummonSkill.onGeneral, the player's rule of 2026-09-22).
             note = "Open the General tab of Special Summon yourself. DigiAutotap only " +
                 "works there and never taps anything on Buddy, SP Support or Overdrive, " +
                 "where draws cost purchased gems."),
        // World Search has no page any more, and no fields: the row's own
        // switch is the whole of its interface, and switched on it collects
        // everything the board has. What stood here was app.py's `_page_mini`
        // (app.py:2072) field for field -- six ticks under "What to collect",
        // three limits that all defaulted to "no limit", the click delay, and
        // "Speed up while it goes well". Every one of them only ever chose to
        // do less of what the row had just been switched on for, and the six
        // ticks had a worse way of saying it: unticked to the last one, the
        // row went dead with "Nothing ticked to collect" (Skills.holdBack).
        // Dropped here, dropped in Stored.worldSearch with them, so no key is
        // read that nobody can set -- [WorldSearchSettings]' own defaults are
        // what the skill runs on now, everything wanted and no limit. The two
        // that were never on the page either, `wait_for_board` (180 s) and
        // `navigate`, are among them.
        //
        // It has a page again since 2026-09-28 with one switch on it, which
        // the player asked for on 2026-09-27: whether two or more pyramids in
        // the row ahead are dashed through even when that loses something in
        // another row (PLAN_WORLD_SEARCH_DASH.md, version A; Planner's
        // `dashEager`). Off is the rule as it was. Like the Quest Loop's
        // number, it is a choice the player wants made, not a second switch
        // in front of the row's.
        Page("mini", "World Search", "Plays the Digital World Search board.", listOf(
            Toggle(Stored.WORLD_SEARCH_DASH_KEY, "Dash through two or more pyramids", false,
                   "Off, a dash is taken only when walking would clearly cost more, and never " +
                       "while something in another row would scroll off the board. On, two or " +
                       "more pyramids in the row ahead are always dashed through, even if that " +
                       "loses something in another row -- the log says what."),
        ), note = "When switched on, World Search plays the board and collects everything on " +
            "it -- tickets, claws, paws and fireballs -- until the paws run out. Claws and " +
            "dashes recharge slowly, so it walks around a pyramid whenever it can and breaks " +
            "one only when there is no way around; it dashes when it is walled in or walking " +
            "would clearly cost more, and, unless the switch above is on, never past a " +
            "power-up that would scroll off."),
        // The quest loop's switches are gone from here and stay gone: the
        // Quest Loop row's switch in the list is what includes it, and
        // switched on the loop works every step of the routine -- the dungeon
        // quests, the summon quests, and every claim. What stood here until
        // 2026-09-22 were four switches in front of one: `included_quest`
        // already decided whether the loop ran at all, and a supporter had to
        // find and tick "Work through the repeating quests" underneath the row
        // they had just switched on before anything happened. The three under
        // it only ever chose to do less.
        //
        // It has a page again since 2026-09-23 with one number on it, which
        // the player asked for: how many attempts a quest's dungeon gets
        // before Clear Previous Difficulty, apart from the Dungeons task's
        // own. The objection to the old page was switches in front of the
        // switch; a number the player wants set is not one.
        Page("quest", "Quest Loop", "Works through the repeating quests.", listOf(
            // Failed attempts since 2026-10-04, as on the Dungeons page (the
            // player, the same day); the key is the old one, so that nobody's
            // number went with the change.
            Number(Stored.QUEST_CLEAR_KEY, "Failed attempts before Clear Previous Difficulty",
                   QuestSkill.ATTEMPTS_BEFORE_CLEAR.toDouble(), 0.0, 99.0,
                   note = "For the dungeon quests. $DUNGEON_CLEAR_NOTE A run cleared this way " +
                       "counts for the quest."),
        ), note = "When switched on, the Quest Loop works through the repeating quests by " +
            "itself -- it claims finished quests, plays the dungeon and summon quests, and " +
            "stops when there is nothing left for the day. If you run out of dungeon " +
            "tickets, it waits for the daily reset; switch it off and on again to try " +
            "earlier."),
        Page("passive", "Bond token", "Collects the bond token on the main screen.", listOf(
            // The Bond token half of the page is one box. The row's own
            // switch in the Skills list is what turns the collecting on
            // (Skills.PASSIVE), so there is no "Watch the main screen" and no
            // "Collect the bond token" beside it: with the feature included,
            // the token of the Digimon being raised is collected, and the one
            // thing left to choose is whether the others are visited too.
            // That choice was a supporter's from 2026-09-23 to 2026-10-02,
            // and is everybody's since, as the row is ([Paywall]).
            Toggle("passive_all_digimon", "Collect for all of the Digimon", false,
                   "Goes through the Partner screen and taps Raise on each Digimon. That " +
                       "only changes which Digimon you are raising and costs nothing."),
        )),
        // The Meat Field had a page here until 2026-09-22, and one switch on
        // it: "Keep the field farmed", off by default. It was the same
        // second switch in front of the first that the quest loop's page had
        // -- the Meat Field row's own switch in the Tasks list is what
        // includes the task, and `farm_on` was read by nobody: no skill, no
        // store, no test. A supporter who switched the row on got a page
        // whose one box was unticked and changed nothing either way.
        // Dropped, and with it the page, so the row's Settings card says
        // what the task does instead (Skills.kt).
        //
        // A page again since 2026-09-28 with the two switches the player
        // asked for on 2026-09-27 (PLAN_MEAT_FIELD_GIESSEN.md 4.5), both off
        // by default because both spend or start something not every player
        // wants: the Good and Great seeds once the free ones are out, and the
        // field's own ads for seeds and cans. Neither is a second switch in
        // front of the row's: watering has no switch (4.8), and with the row
        // on and both off the task does what it did, and waters. The row's
        // `noSettings` sentence is this page's note now.
        Page("farm", "Meat Field", "Harvests, plants and waters the Meat Field.", listOf(
            Toggle(Stored.FARM_BETTER_SEEDS_KEY, "Use Good and Great seeds when the free ones are out", false,
                   "Off, planting stops when the free seeds are at 0. On, the empty plots get Good " +
                       "seeds, then Great ones -- only while no free seed is left; as soon as one " +
                       "has come back (one an hour), the free seed is planted again."),
            // "Watch the free ads for seeds and cans" (Stored.FARM_ADS_KEY)
            // stood here from 2026-09-28 to 2026-10-02, and is the Ad Rewards
            // card's Meat Field pick since.
            // The player's threshold of 2026-09-28 (PLAN_MEAT_FIELD_GIESSEN.md
            // 13): "everything over 20 minutes is watered", and only with as
            // many cans as ripen the plot at once.
            Number(Stored.FARM_WATER_MIN_KEY, "Water only plots with more than this many minutes left",
                   FarmSkill.WATER_MIN_MINUTES.toDouble(), 0.0, 240.0,
                   note = "A plot is watered only when the cans in hand ripen it at once -- " +
                       "30 minutes a can. 0 waters every growing plot."),
        ),
            // A supporter's page from 2026-09-28 to 2026-10-02, everybody's since ([Paywall]).
            note = "When switched on, the task harvests what is ripe, plants the free seeds in " +
                "the empty plots and waters the growing plots with the free watering cans -- " +
                "the shortest times first, and only with as many cans as ripen a plot -- " +
                "and harvests and plants again what that ripens. Where the field's free ads are " +
                "taken (the Ad Rewards card on the main page), the cans' ad is still to be had " +
                "today and the cans in hand ripen no plot, they go on the plot that ripens " +
                "first, so that the game offers the ad. The large watering can is " +
                "never used, and nothing is bought. In fully automatic mode it goes to the Meat Field by itself when there " +
                "is something to do, and comes back afterwards."),
        // Tower & Ruins had a page here until 2026-09-22 -- one field, the
        // seconds between clicks, and a note about setting its point with
        // the overlay dot. The player took the whole feature out of the
        // interface: no row, no page, no chain step. TowerSkill and its
        // tests stay in core, and nothing builds one.
        // The Beatbreak event's runner. The PC's page (app.py, the Gekkomon
        // Run page) had three keys and so did this one until 2026-09-22:
        // "Fever Times to reach", "End a run at this score" (up to 37,000)
        // and "Claim the daily missions afterwards". The score went first --
        // the score at which a run stops being played is
        // [RunnerSkill.SCORE_CAP], the app's own rule against a leaderboard
        // record set by a machine -- and for a day the Fever Times went with
        // it and the task played while its switch was on. Since 2026-09-23
        // it is the other way round, the player's call: the Fever Times are
        // back, the one field, and the claims are gone. A rule the player
        // cannot change is a rule the page has to say out loud, so the note
        // names the cap, and RunnerSkillTest holds the note and the constant
        // to each other.
        Page("runner", "Gekkomon Run", "Plays the Beatbreak runner for its Fever Times.", listOf(
            Number("runner_fevers", "Fever Times per day", RunnerSkill.FEVER_TARGET.toDouble(), 1.0, 20.0),
        ), supporter = true,
            note = "Plays one run after another until this many Fever Times are reached " +
            "today, counted over all runs, then stops until the daily reset at 8:00 -- or until " +
            "you switch it off and on, which starts the day's count again. " +
            "It does not claim any rewards. " +
            "One limit is built in and cannot be changed: at 15,000 points it stops " +
            "playing and lets the run end, then starts the next one. That is on purpose, " +
            "so that no leaderboard record is set by a bot. Ended runs cost nothing."),
        // Chef's Special, since 2026-09-30 (PLAN_SKEWER.md 3.1): the skewer
        // minigame of the Events window, a supporter's task like Gekkomon Run
        // (question 5). One number, the combos a day wants, 16 by default,
        // 1 to 999 (30 until 2026-10-04, when the player asked for 999): the
        // event's daily mission counts fifteen ("Achieve Minigame combo 15
        // times", question 13), and the sixteenth is the player's margin.
        // The rounds a pass may play grow with the number
        // (SkewerSkill.roundsFor). No score cap (question 4) and no stage
        // choice (question 3): the stage is the one the game has chosen.
        Page("skewer", "Chef's Special", "Plays the skewer minigame for the day's combos.", listOf(
            Number(Stored.SKEWER_COMBOS_KEY, "Combos per day", SkewerSkill.COMBO_TARGET.toDouble(), 1.0, 999.0),
        ), supporter = true,
            note = "Plays one round after another until this many combos are counted today, then " +
                "leaves the round through its pause menu and stops until the daily reset at 8:00 -- or " +
                "until you switch it off and on, which starts the day's count again. " +
                "A combo is a guest served right after a guest served right in the same round, the " +
                "\"N Combo\" the game shows; a mistake or a new round starts the running combo again, " +
                "and what is counted stays counted. The event's daily mission needs 15. Rounds cost " +
                "nothing, and the stage is the one the game has chosen. Only one minigame runs at a " +
                "time: switching this task on switches Gekkomon Run off, and the other way round."),
        // Presets, since 2026-09-25 (PLAN_PRESET_SWITCH.md): saved profiles,
        // each a name and a slot per place, picked and switched to on this
        // page (design A of 2026-09-26). The note is two sentences since the
        // player's edit of 2026-09-26; the one between them, "keep the same
        // kind of preset in the same slot everywhere", went.
        Page("preset", "Presets", "Sets the game's presets to one of your profiles.",
             listOf(PresetProfiles()), supporter = true,
             note = "Presets are chosen by their number, not their name. \"Switch to\" goes " +
                 "through every place with a number, sets it and comes back to the main screen."),
        // Idle Rewards had a page here from 2026-09-28 (PLAN_WERBUNG.md 11 and
        // 16) to 2026-10-02, when the task left the interface
        // (PLAN_BEFUNDE_1_3.md N3 a). Its one switch was the Extra Rewards'
        // two ads a day, `idle_ads` ([Stored.IDLE_ADS_KEY]), which IdleSkill
        // still reads; a saved `idle_ads` disturbs nothing.
        // EX Missions, since 2026-09-29 (PLAN_EX_MISSIONS.md): a supporter's
        // task (question 5) with no clock (question 2) -- its button runs it,
        // the chain has it as a step (question 7), and in the semi-automatic
        // mode it works the Missions window wherever it is open (question 6).
        // So the page is the button and nothing else.
        Page("exmissions", "EX Missions", "Claims the rewards of the EX Missions tab.", listOf(
            RunNow(ExMissionsSkill.KEY, "Claim now", note = PRESET_NEEDS),
        ), supporter = true,
            note = "When switched on, the task claims the rewards in the EX Missions tab of the " +
                "Missions window -- the second row from the top until it is no longer yellow, then the " +
                "top row, and again until nothing is yellow -- and touches nothing else in that " +
                "window. It never starts by itself: tap \"Claim now\", add it to the fully automatic " +
                "order, or open the Missions window yourself in semi-automatic mode (the Daily " +
                "Missions tab is left to you). When it opened the window itself, it closes it again."),
        Page("chain", "Skill Chain", "The order of the fully automatic mode.", listOf(
            ChainSteps(),
            Toggle("chain_repeat", "Repeat the whole sequence until Stop", false,
                   "Off, the chain stops after the last step. On, it starts again from the top."),
        )),
    )

    fun page(key: String): Page = PAGES.first { it.key == key }

    /**
     * The ceiling of the two minutes fields on the Dungeons page. Not a
     * limit anybody is meant to meet -- the player's answer to question 11
     * was "no maximum" -- but [Number] needs one, and the page does not say it.
     */
    const val MINUTES_MAX = 10_000.0

    /**
     * The sentence under the minutes (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.4),
     * "each time" per question 2. Since DL4 it says when each of the two
     * runs: the tower is a step of its own in the chain (question 3) and the
     * Crests page's task in the semi-automatic mode, not a part of the
     * Dungeons pass, so "each time Dungeons runs" alone was true of the daily
     * dungeon only.
     */
    const val MINUTES_NOTE = "Both are played for this many minutes, attempt after attempt -- " +
        "the daily dungeon each time Dungeons runs, the Lost Sector Tower each time its own " +
        "step of the chain runs or its Crests page is open. They cost nothing. 0 leaves them out."

    /**
     * What Clear Previous Difficulty is, said once for both pages that set
     * it: only a failed attempt counts (DungeonSkill.Settings.countLostOnly),
     * on the Dungeons page and on the Quest Loop's, both since 2026-10-04.
     */
    const val DUNGEON_CLEAR_NOTE = "A failed attempt is a run that ends without rewards and " +
        "goes back to the dungeon; it costs no ticket and is tried again. A won run does not " +
        "count. After this many failed attempts on one dungeon, the tickets still to spend go " +
        "to Clear Previous Difficulty, which hands out the previous difficulty's rewards at once."
}
