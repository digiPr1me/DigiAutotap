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
     * A box. [supporter] marks the one box that is a supporter's feature on
     * a task that is not: the page greys it out without a code, and the
     * skill that reads it asks the code again for itself.
     */
    class Toggle(key: String, label: String, val default: Boolean, val note: String = "",
                 val supporter: Boolean = false) : Field(key, label)

    class Number(key: String, label: String, val default: Double, val min: Double,
                 val max: Double, val decimal: Boolean = false, val note: String = "")
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
    val CHAINABLE = listOf("dungeon", "summon", "mini", "farm", "runner")
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
     * a summon mode -- and so the free ads are watched, in Dungeons, in
     * Summon and in the Quest Loop alike. Without it the same tap opens an
     * ad video that no reader knows, so no free ad is ever tapped.
     *
     * Off by default: the failure with the pass and the switch off is a
     * free reward left lying, said on the main page; the failure without
     * the pass and the switch on was a video being tapped into. It stood
     * as two task boxes until that day, `use_ads` on Dungeons and
     * `summon_ads` on Summon, both on by default and neither asked by the
     * quest loop, which built its bots with the ads hard-wired on. An old
     * digiautotap.json keeps the two keys; nothing reads them.
     */
    const val AD_PASS_KEY = "ad_pass"
    const val AD_PASS_DEFAULT = false

    /** Said on the Dungeons and Summon pages, where the two boxes used to be. */
    const val AD_PASS_NOTE = "The free ads are watched with your Ad Skip Pass -- its switch " +
        "is on the main page."

    val PAGES: List<Page> = listOf(
        Page("dungeon", "Dungeons", "Spends the day's dungeon tickets.", listOf(
            DungeonAttempts(),
            Number(Stored.DUNGEON_CLEAR_KEY, "Attempts before Clear Previous Difficulty",
                   DungeonSkill.ATTEMPTS_BEFORE_CLEAR.toDouble(), 0.0, 99.0,
                   note = CLEAR_NOTE),
            // "Use ad tickets" (`use_ads`) stood here until 2026-09-24, with
            // the note "Ads are only watched if you have bought the ad
            // pass" -- which is to say the box was the pass question in
            // disguise. It is [AD_PASS_KEY] now, once, on the main page.
        ), note = AD_PASS_NOTE),
        Page("summon", "Special Summon",
             "Spends summon tickets on the General tab, mode by mode.", listOf(
            Toggle("summon_skill", "Skill Card Summon", true),
            Toggle("summon_support", "Support Digimon Summon", true),
            Toggle("summon_crest", "Crest Summon", true),
            // "Watch the free ads first" (`summon_ads`) stood here until
            // 2026-09-24; it is [AD_PASS_KEY] now, the same switch the
            // Dungeons page lost.
            Number("summon_max", "Summons per mode (0 = all)", 0.0, 0.0, 500.0),
        ),
             // Said on the page because it is the one thing about this task a
             // player has to do themselves, and because the Summon icon does
             // not land there: it opens whichever tab the game pleases
             // (SummonSkill.onGeneral, the player's rule of 2026-09-22).
             note = "Open the General tab of Special Summon yourself. DigiAutotap only " +
                 "works there and never taps anything on Buddy, SP Support or Overdrive, " +
                 "where draws cost purchased gems. " + AD_PASS_NOTE),
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
            Number(Stored.QUEST_CLEAR_KEY, "Attempts before Clear Previous Difficulty",
                   QuestSkill.ATTEMPTS_BEFORE_CLEAR.toDouble(), 0.0, 99.0,
                   note = "For the dungeon quests. $CLEAR_NOTE A run cleared this way counts " +
                       "for the quest."),
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
            // That choice is a supporter's since 2026-09-23; the row itself
            // is everybody's (BondTourSkill.hasBudget asks the code).
            Toggle("passive_all_digimon", "Collect for all of the Digimon", false,
                   "Goes through the Partner screen and taps Raise on each Digimon. That " +
                       "only changes which Digimon you are raising and costs nothing.",
                   supporter = true),
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
            "today, counted over all runs, then stops until the daily reset at 8:00. " +
            "It does not claim any rewards. " +
            "One limit is built in and cannot be changed: at 15,000 points it stops " +
            "playing and lets the run end, then starts the next one. That is on purpose, " +
            "so that no leaderboard record is set by a bot. Ended runs cost nothing."),
        Page("chain", "Skill Chain", "The order of the fully automatic mode.", listOf(
            ChainSteps(),
            Toggle("chain_repeat", "Repeat the whole sequence until Stop", false,
                   "Off, the chain stops after the last step. On, it starts again from the top."),
        )),
    )

    fun page(key: String): Page = PAGES.first { it.key == key }

    /** What Clear Previous Difficulty is, said once for both pages that set it. */
    const val CLEAR_NOTE = "A lost run costs no ticket and is tried again. After this many " +
        "attempts on one dungeon, the tickets still to spend go to Clear Previous " +
        "Difficulty, which hands out the previous difficulty's rewards at once."
}
