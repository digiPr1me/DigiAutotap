package io.github.digipr1me.digiautotap.core

/**
 * The settings file, as core sees it: six shapes, because six shapes are
 * what `digiautotap.json` holds. The app writes the file (`SettingsStore`
 * over `org.json`); core only reads and writes through this, so that a
 * skill can be built and driven in a test with nothing around it
 * (PLAN_ANDROID_APP.md 4, "Es gibt keinen Einstellungsspeicher").
 *
 * Every skill used to take its settings as lambdas the shell filled in --
 * `FarmSkill(dueAt, remember)`, `WorldSearchSkill(settings)`,
 * `TowerSkill(point, interval)` -- and each L session invented its own
 * spelling for the same keys. The lambdas stay, because a skill asking
 * afresh on every question is the rule ("a chain step's nothing to do is a
 * question, not a state"); what is shared now is *what the key is called
 * and what it defaults to*, and that is [Stored] below.
 *
 * Read fresh on every question. A store built once at the start of a run
 * would answer with the switches as they were then, and the player may tick
 * a box while the director runs.
 */
interface Settings {
    fun bool(key: String, default: Boolean): Boolean
    fun num(key: String, default: Double): Double
    fun str(key: String, default: String): String

    /** A list of strings, as `"wanted": [...]` is written. */
    fun strings(key: String, default: List<String>): List<String>

    /**
     * `"attempts": {"0": n, ...}`, app.py's own shape: a dungeon's budget by
     * its place in the list. Missing keys are the caller's [default].
     */
    fun ints(key: String, default: Map<String, Int>): Map<String, Int>

    /** `"chain_steps": [{"key": ..., "minutes": ...}]`, chain.Step.to_dict. */
    fun steps(key: String): List<Chain.Step>?

    /** Saved at once, as app.py saves every change at once. Null removes the key. */
    fun put(key: String, value: Any?)

    /**
     * Several keys in one go: read, change all of them, write -- where two
     * keys belong together and a second store must never see one without
     * the other (notes/director.md, "Two copies of the settings file, and
     * the last save wins"). The app's store overrides this with one read and
     * one save; the default is enough for a map.
     */
    fun putAll(values: Map<String, Any?>) {
        for ((k, v) in values) put(k, v)
    }

    fun putInts(key: String, value: Map<String, Int>)
    fun putSteps(key: String, value: List<Chain.Step>)
}

/**
 * Everything on disk, once, with the key it is written under: what each
 * skill wants, read out of a [Settings].
 *
 * The keys and the defaults are app.py's, which is where a setting is
 * decided (SkillSettings, and SkillSettingsTest which reads app.py and
 * fails when the two drift). Nothing here decides anything of its own; it
 * is the one place that knows a skill's Settings object is built out of
 * these keys, so that the shell, the director and a test all build it the
 * same way.
 */
object Stored {

    /** "Am I in?", one key per row of the Skills list. The director asks it every round. */
    fun includedKey(key: String) = "included_$key"

    /**
     * Included unless the player switched it off. New on the phone: the PC
     * has a Start button per skill instead (PLAN_ANDROID_5_SHELL.md 5.6).
     * The supporter veto is the shell's, not this: a locked row reads as
     * off there, and the setting itself is left alone so that redeeming a
     * code later gives the player back what they had switched on.
     */
    fun included(s: Settings, key: String): Boolean = s.bool(includedKey(key), key !in OFF_UNTIL_SWITCHED)

    /**
     * Rows that are off until the player switches them on, where every other
     * row is in until switched off. Chef's Special alone (PLAN_SKEWER.md
     * 3.3): it came with 1.3, when every phone already had Gekkomon Run in
     * by the old default, and the two minigames are one at a time
     * ([MINIGAMES]) -- in by default too, the list would show both on, the
     * one thing the swap is there to prevent. Off is also the side whose
     * failure costs less (NOTES.md, "A change to the app", point 4): a task
     * the player has not asked for does nothing, and one switch puts it in.
     */
    val OFF_UNTIL_SWITCHED = setOf(SkewerSkill.KEY)

    /**
     * The Events window's minigames, of which the game runs one at a time
     * and rotates them every few updates (PLAN_SKEWER.md 7, question 12):
     * Gekkomon Run and Chef's Special. The player's rule of 2026-09-30
     * (3.3): switching one on switches the other off, and both off is
     * allowed.
     */
    val MINIGAMES = listOf("runner", SkewerSkill.KEY)

    /**
     * The row [key] switched [on], and -- a minigame switched on -- the other
     * minigame off, in one read, change and write ([Settings.putAll]): two
     * stores are alive at once, and a second one must not see Chef's Special
     * on beside Gekkomon Run for the length of a save. Returns the rows that
     * were on and are off now, for the page to say so.
     */
    fun include(s: Settings, key: String, on: Boolean): List<String> {
        val others = if (on && key in MINIGAMES) MINIGAMES.filter { it != key } else emptyList()
        val wereOn = others.filter { included(s, it) }
        s.putAll(mapOf(includedKey(key) to on) + others.associate { includedKey(it) to false })
        return wereOn
    }

    fun mode(s: Settings): String = s.str(SkillSettings.MODE_KEY, SkillSettings.MODE_DEFAULT)

    // ---- A row's day ---------------------------------------------------------
    /**
     * A row's day as the file has it: [says] in the player's words, and
     * whether it [holds] the row back -- the Quest Loop's lock, the
     * minigames' counts at the page's number, the Lost Sector Tower at its
     * highest floor, the Meat Field's clock not yet run out.
     */
    data class Day(val says: String, val holds: Boolean)

    /**
     * The day of row [key] ([Day]), or null where the file holds nothing of
     * it: no lock, no count, no clock, or one that has run out. The rows
     * that have one are [RESTARTS]; every other row answers null.
     */
    fun day(s: Settings, key: String, nowEpoch: Double = System.currentTimeMillis() / 1000.0): Day? = when (key) {
        "quest" -> questLockedUntil(s)?.takeIf { nowEpoch < it }
            ?.let { Day("out of dungeon tickets until ${QuestSkill.whenText(it)}", true) }
        SkewerSkill.KEY -> skewer(s, nowEpoch).takeIf { it.doneToday > 0 }
            ?.let { Day("${it.doneToday} of ${it.combos} combos counted today", it.combos > 0 && it.left == 0) }
        "runner" -> runner(s, nowEpoch).takeIf { it.doneToday > 0 }
            ?.let { Day("${it.doneToday} of ${it.fevers} Fever Times played today", it.fevers > 0 && it.left == 0) }
        "dungeon" -> lostSectorDoneUntil(s, lostSectorMinutes(s), nowEpoch)
            ?.let { Day("the Lost Sector Tower is at its highest floor until ${QuestSkill.whenText(it)}", true) }
        "farm" -> farmDueAt(s)?.takeIf { it > nowEpoch }
            ?.let { Day("the field's next visit is due in ${Math.ceil((it - nowEpoch) / 60.0).toInt()} min", true) }
        else -> null
    }

    /**
     * The keys that make up a row's day, and what a restart writes over each
     * (null takes the key out of the file). The TODAY card's numbers
     * (SkillStats) are not among them: the card is the player's day, the
     * page's number is the limit.
     */
    private fun dayCleared(key: String): Map<String, Any?> = when (key) {
        "quest" -> mapOf("quest_locked_until" to null, "quest_locked_reason" to null)
        SkewerSkill.KEY -> mapOf("skewer_combos_done" to 0)
        "runner" -> mapOf("runner_fevers_done" to 0)
        "dungeon" -> mapOf(LOST_SECTOR_DONE_UNTIL_KEY to null, LOST_SECTOR_DONE_MINUTES_KEY to null)
        "farm" -> mapOf("farm_due_at" to null)
        else -> emptyMap()
    }

    /**
     * The rows whose switch, thrown on, starts the day again ([restart]):
     * the Quest Loop, Chef's Special, Gekkomon Run, Dungeons for the Lost
     * Sector Tower, and the Meat Field's clock, so that off and on means
     * "now" on every row that keeps a day (PLAN_ABSCHLUSS_1_3.md A2,
     * question 4).
     */
    val RESTARTS = setOf("quest", SkewerSkill.KEY, "runner", "dungeon", "farm")

    /**
     * Row [key]'s switch on: its day taken out of the file in one read,
     * change and write ([Settings.putAll]) -- every time it is switched on,
     * not only at its limit (question 5: switching on is the player saying
     * "try now", as the Quest Loop row's switch has said since 2026-09-22).
     * The day as it stood comes back for the log, null where there was none.
     */
    fun restart(s: Settings, key: String, nowEpoch: Double = System.currentTimeMillis() / 1000.0): Day? {
        val was = day(s, key, nowEpoch)
        val cleared = dayCleared(key)
        if (cleared.isNotEmpty()) s.putAll(cleared)
        return was
    }

    // ---- Dungeons ----------------------------------------------------------
    /**
     * `"attempts"`, one number per card in [SkillSettings.DUNGEON_NAMES]
     * order. The hidden card keeps its place at 0 -- the skill counts cards,
     * and only its control goes away (app.py, HIDDEN_DUNGEONS).
     *
     * Tickets to spend since 2026-09-23, not attempts: the key kept its old
     * name so that an update keeps everybody's numbers
     * (DungeonSkill.Settings.budgets).
     */
    fun attempts(s: Settings): IntArray {
        val saved = s.ints("attempts", emptyMap())
        return IntArray(SkillSettings.DUNGEON_NAMES.size) { i ->
            if (SkillSettings.DUNGEON_NAMES[i] in SkillSettings.HIDDEN_DUNGEONS) 0
            else saved["$i"] ?: SkillSettings.DAY_ATTEMPTS
        }
    }

    fun putAttempts(s: Settings, values: IntArray) =
        s.putInts("attempts", values.withIndex().associate { (i, v) -> "$i" to v })

    /** What `app._start_dungeon` hands DungeonBot. */
    fun dungeon(s: Settings) = DungeonSkill.Settings(
        budgets = attempts(s).withIndex().associate { (i, n) -> i to n },
        // `use_ads` until 2026-09-24, the pass alone from then, on either
        // way from 2026-09-26, the page's own switch from 2026-09-30, the
        // Ad Rewards card's pick from 2026-10-02, and since 2026-10-03 the pick
        // and the pass together ([freeAds]): the pick says whether here, the
        // pass whether at all.
        useAds = dungeonAds(s) && freeAds(s).tap,
        // No switch on the phone's page. app.py's default was on; the task
        // runs without it since 2026-09-22, because the pre-check's two
        // swipes and its reads bought a skipped card or two, and what the
        // player asked for is the cards they set played -- a card at zero
        // opens, shows its ad button, and is left in a few seconds. The
        // quest loop passes its own `survey = true` (QuestSkill.dungeonFor):
        // `dungeonShort` and `dungeonBlocked` are read off that survey.
        survey = s.bool("survey", false),
        attemptsBeforeClear = s.num(DUNGEON_CLEAR_KEY,
                                    DungeonSkill.ATTEMPTS_BEFORE_CLEAR.toDouble()).toInt(),
        dailyMinutes = dungeonDaily(s))

    /**
     * The Dungeons pick of the Ad Rewards card (since 2026-10-02, [AD_PICKS]):
     * the Dungeons page's "Watch the free ads for tickets" from 2026-09-30
     * to that day, under the same key, so that what a player had set there
     * holds. Off by default -- the player's answer, so an update leaves every
     * ad alone until the player asks for them. A new key and not `use_ads`,
     * which an old digiautotap.json may still hold from before 2026-09-24
     * and which nobody chose under this switch's meaning. The Quest Loop
     * reads it too ([quest]): off is no film button anywhere.
     */
    const val DUNGEON_ADS_KEY = "dungeon_ads"
    const val DUNGEON_ADS_DEFAULT = false

    fun dungeonAds(s: Settings): Boolean = s.bool(DUNGEON_ADS_KEY, DUNGEON_ADS_DEFAULT)

    /** The Dungeons page's "Failed attempts before Clear Previous Difficulty" (the lost runs alone since 2026-10-04). */
    const val DUNGEON_CLEAR_KEY = "dungeon_attempts_before_clear"

    /**
     * The Dungeons page's minutes for the daily dungeon, under the rule below
     * the tickets (PLAN_DAILY_LOST_SECTOR_PRESETS.md 3.1, 3.4): how long it
     * is attempted each time the task gets the list -- per pass, no account
     * kept over the day (the player's answer to question 2). 0 is off and
     * the default; there is no ceiling a player meets (question 11), and a
     * negative number in the file is 0.
     */
    const val DUNGEON_DAILY_KEY = "dungeon_daily_minutes"

    fun dungeonDaily(s: Settings): Int = maxOf(0, s.num(DUNGEON_DAILY_KEY, 0.0).toInt())

    /**
     * The Lost Sector Tower's minutes, the row beside the daily dungeon's on
     * the same page (3.2, 3.4): how long the tower's Subjugate is pressed,
     * run after run, each time the task gets the tower -- its own step of the
     * chain, or the Crests page handed over. 0 is off and the default, no
     * ceiling a player meets (question 11), a negative number in the file is
     * 0. The page writes it since DL3, `LostSectorSkill` reads it since DL4.
     */
    const val LOST_SECTOR_MINUTES_KEY = "lost_sector_minutes"

    fun lostSector(s: Settings, nowEpoch: Double = System.currentTimeMillis() / 1000.0): LostSectorSkill.Settings {
        val minutes = lostSectorMinutes(s)
        return LostSectorSkill.Settings(minutes = minutes, doneUntil = lostSectorDoneUntil(s, minutes, nowEpoch))
    }

    fun lostSectorMinutes(s: Settings): Int = maxOf(0, s.num(LOST_SECTOR_MINUTES_KEY, 0.0).toInt())

    /**
     * The tower's day, over (PLAN_DAILY_LOST_SECTOR_PRESETS.md G16, the
     * conductor's proposal to question 22): `lost_sector_done_until`, the
     * game day's reset ([QuestSkill.nextReset], 08:00 Vienna) written the
     * moment the highest floor's toast is read, and `lost_sector_done_minutes`,
     * the page's minutes when it was written. Until the reset the tower is
     * not walked into again -- `hasBudget` says no, the step that met the
     * toast retires -- as `runner_fevers_until` keeps Gekkomon Run's day.
     *
     * The lock holds only while the minutes are the ones it was written
     * with: new minutes on the page are the player saying "try again", the
     * way the Quest Loop row's switch lifts that loop's lock
     * (Skills.include); the page also clears it on a change
     * ([unretireLostSector]), and since 2026-10-02 the Dungeons row's switch
     * on its way to on ([restart]). Null where no lock holds: none written,
     * the reset reached, or other minutes.
     */
    fun lostSectorDoneUntil(s: Settings, minutes: Int, nowEpoch: Double): Double? {
        val until = s.num(LOST_SECTOR_DONE_UNTIL_KEY, Double.NaN)
        val with = s.num(LOST_SECTOR_DONE_MINUTES_KEY, Double.NaN)
        if (until.isNaN() || with.isNaN() || nowEpoch >= until) return null
        return if (with.toInt() == minutes) until else null
    }

    /** The tower's day over until [until]: both keys, with the minutes standing now ([lostSectorDoneUntil]). */
    fun retireLostSector(s: Settings, until: Double) {
        s.put(LOST_SECTOR_DONE_UNTIL_KEY, until)
        s.put(LOST_SECTOR_DONE_MINUTES_KEY, lostSectorMinutes(s))
    }

    /** Both keys gone, in one write: the page's minutes changed (MainActivity's number field). */
    fun unretireLostSector(s: Settings) = s.putAll(dayCleared("dungeon"))

    const val LOST_SECTOR_DONE_UNTIL_KEY = "lost_sector_done_until"
    const val LOST_SECTOR_DONE_MINUTES_KEY = "lost_sector_done_minutes"

    // ---- Special Summon ----------------------------------------------------
    fun summon(s: Settings) = SummonSkill.Settings(
        skill = s.bool("summon_skill", true),
        support = s.bool("summon_support", true),
        crest = s.bool("summon_crest", true),
        // `summon_ads` until 2026-09-24, the pass alone from then, on either
        // way from 2026-09-26, the page's own switch from 2026-09-30, the
        // Ad Rewards card's pick from 2026-10-02, and the pick with the pass
        // ([freeAds]) since 2026-10-03.
        watchAdsFirst = summonAds(s) && freeAds(s).tap,
        maxPerMode = s.num("summon_max", 0.0).toInt())

    /**
     * The Summon pick of the Ad Rewards card ([AD_PICKS]), the Summon page's
     * "Watch the free ads first" from 2026-09-30 to 2026-10-02 under the same
     * key; off by default for the same reason as [DUNGEON_ADS_KEY], and a
     * new key for the same reason: `summon_ads` may still stand in an old
     * file. The Quest Loop's summon step reads it too ([quest]).
     */
    const val SUMMON_ADS_KEY = "summon_free_ads"
    const val SUMMON_ADS_DEFAULT = false

    fun summonAds(s: Settings): Boolean = s.bool(SUMMON_ADS_KEY, SUMMON_ADS_DEFAULT)

    /**
     * Does the player have the game's Ad Skip Pass? Whether a free ad is
     * tapped at all is this -- Dungeons, Summon, the Quest Loop, Idle
     * Rewards and the Meat Field read it from here
     * (SkillSettings.AD_PASS_KEY, off by default): with it the reward comes
     * with the tap, and without it no free ad is tapped ([freeAds]). Which
     * tasks take their free ads is the Ad Rewards card's picks ([AD_PICKS])
     * and Idle Rewards' [IDLE_ADS_KEY].
     */
    fun adPass(s: Settings): Boolean = s.bool(SkillSettings.AD_PASS_KEY, SkillSettings.AD_PASS_DEFAULT)

    // ---- Free ads ----------------------------------------------------------
    /**
     * What may be done with a free ad ([FreeAds]): the pass alone, for
     * everybody alike. An `ad_watch` in an old file is read by nobody
     * (PLAN_ABSCHLUSS_1_3.md 3.6).
     */
    fun freeAds(s: Settings) = FreeAds(pass = adPass(s))

    /**
     * The tasks the Ad Rewards card picks, in its order, with the key each is
     * kept under: the three switches that stood at the head of these pages
     * until 2026-10-02 went onto the card as they were (question 13), keys
     * and all, so what a player had set there holds. The Quest Loop follows
     * the first two.
     */
    val AD_PICKS = listOf("summon" to SUMMON_ADS_KEY, "dungeon" to DUNGEON_ADS_KEY, "farm" to FARM_ADS_KEY)

    // ---- Digital World Search ----------------------------------------------
    /**
     * One key is read, [WORLD_SEARCH_DASH_KEY], the page's one switch since
     * 2026-09-28 (SkillSettings, PLAN_WORLD_SEARCH_DASH.md). Everything else
     * is [WorldSearchSettings]' own defaults, as since the old page went
     * with every field on it -- everything on the board collected, no paw
     * floor, no action limit, no metre target, 0.7 s between clicks, and the
     * pace speeding up while the moves land.
     *
     * The keys the old page used to write are left where they are in an old
     * `digiautotap.json`; a value nothing asks for does no harm, and a
     * `want_<key>` set to false on a settings file copied off a PC would
     * otherwise take a board item away from a player who has no way left to
     * put it back.
     */
    fun worldSearch(s: Settings) = WorldSearchSettings(
        dashEager = s.bool(WORLD_SEARCH_DASH_KEY, false))

    /** "Dash through two or more pyramids" (Planner's `dashEager`), off by default. */
    const val WORLD_SEARCH_DASH_KEY = "mini_dash_eager"

    // ---- Quest loop --------------------------------------------------------
    /**
     * All the quest loop reads out of the settings file now: where it had
     * got to, and -- since 2026-09-23, on a page of its own again -- how
     * many attempts a quest's dungeon gets before Clear Previous
     * Difficulty. There were four switches beside it -- `quest_on`,
     * `quest_dungeon`, `quest_summon`, `quest_claim_only` -- and they are
     * gone with the page they stood on (SkillSettings): the row's own switch
     * in the Skills list says whether the loop runs, and running it works
     * every step of the routine. `passive_auto` went the same way before
     * them: `_sync_quest_loop` (app.py:3427) hands it over on the PC, so
     * that a loop with Auto Spend running would leave the hologram device
     * alone, and no interface on the phone ever set it. A setting nobody can
     * reach is not a setting.
     */
    fun quest(s: Settings) = QuestSkill.Settings(
        step = s.num("quest_step", 0.0).toInt(),
        lockedUntil = questLockedUntil(s),
        attemptsBeforeClear = s.num(QUEST_CLEAR_KEY,
                                    QuestSkill.ATTEMPTS_BEFORE_CLEAR.toDouble()).toInt(),
        // The two tasks' picks, not switches of the loop's own: a player
        // who wants no ad in Summon or Dungeons wants none there either
        // (the player, 2026-09-30) -- and under the same [freeAds] as the
        // two tasks themselves.
        dungeonAds = dungeonAds(s) && freeAds(s).tap,
        summonAds = summonAds(s) && freeAds(s).tap)

    /** The Quest Loop page's one field, the quest's own attempts before Clear Previous Difficulty. */
    const val QUEST_CLEAR_KEY = "quest_attempts_before_clear"

    /**
     * `quest_locked_until`: epoch seconds until which the quest loop is not
     * tried again, or null where it is free to run. Written by
     * [lockQuest] when the loop stopped for want of dungeon tickets
     * (QuestSkill.lockedForTheDay, the player's rule of 2026-09-22), and
     * read by the loop's `hasBudget` and by the row in the Tasks list.
     */
    fun questLockedUntil(s: Settings): Double? {
        val v = s.num("quest_locked_until", Double.NaN)
        return if (v.isNaN()) null else v
    }

    /** The sentence that went with the lock, for the row; "" where there is none. */
    fun questLockedReason(s: Settings): String = s.str("quest_locked_reason", "")

    /** The two the loop writes as it stops for the day. */
    fun lockQuest(s: Settings, until: Double, reason: String) {
        s.put("quest_locked_until", until)
        s.put("quest_locked_reason", reason)
    }

    /**
     * Both keys gone, in one write: what the Quest Loop row's switch does on
     * its way to on ([restart]), so that a player who bought tickets and
     * wants the loop back before eight has a way to say so.
     */
    fun unlockQuest(s: Settings) = s.putAll(dayCleared("quest"))

    /**
     * `app._save_quest_step` (app.py:3452): where in the 15-step routine the
     * loop stands, kept across a restart. Written from inside a round, as it
     * is on the PC -- the position is only worth anything if it survives the
     * round that reached it.
     */
    fun saveQuestStep(s: Settings, step: Int) = s.put("quest_step", step)

    // ---- Meat Field --------------------------------------------------------
    /**
     * `farm_due_at`: when the next visit is due, epoch seconds, or null
     * where nothing has ever been recorded -- which counts as due
     * (Chain.farmIsDue).
     */
    fun farmDueAt(s: Settings): Double? {
        val v = s.num("farm_due_at", Double.NaN)
        return if (v.isNaN()) null else v
    }

    /**
     * `farm_grow_seconds`: the grow duration the last visit that planted
     * something learned, or null where none was ever recorded. The cap
     * [FarmSkill.nextVisit] holds every field timer under on a visit that
     * planted nothing itself; a grow duration does not go stale, the field is
     * the same field (app.py, both `next_visit` call sites).
     */
    fun farmGrowSeconds(s: Settings): Int? {
        val v = s.num("farm_grow_seconds", Double.NaN)
        return if (v.isNaN()) null else v.toInt()
    }

    /**
     * The three app.py saves after every visit (app.py:3867), whatever the
     * visit achieved. `farm_grow_seconds` is only written where this visit
     * planted something and could read the fresh plate -- a remembered one is
     * never written back over itself.
     */
    fun rememberFarm(s: Settings, dueAt: Double, growSeconds: Int?, reason: String) {
        s.put("farm_due_at", dueAt)
        if (growSeconds != null) s.put("farm_grow_seconds", growSeconds)
        s.put("farm_last_reason", reason)
    }

    /**
     * The Meat Field page's two switches, both off by default
     * (PLAN_MEAT_FIELD_GIESSEN.md 4.5): the Good and Great seeds once the
     * free ones are out, and the field's two ads a day for seeds and cans --
     * the second the Ad Rewards card's Meat Field pick since 2026-10-02
     * ([AD_PICKS]), under the same key.
     */
    const val FARM_BETTER_SEEDS_KEY = "farm_better_seeds"
    const val FARM_ADS_KEY = "farm_ads"
    /**
     * The watering threshold in minutes (PLAN_MEAT_FIELD_GIESSEN.md 13, the
     * player's of 2026-09-28): a growing plot is watered only with more than
     * this left, and 0 is every growing plot. A negative number in the file
     * is 0.
     */
    const val FARM_WATER_MIN_KEY = "farm_water_min_minutes"

    /** What the Meat Field reads at every question: the page's switch, its threshold, and the free ads ([freeAds]). */
    fun farm(s: Settings) = FarmSkill.Settings(
        betterSeeds = s.bool(FARM_BETTER_SEEDS_KEY, false),
        ads = s.bool(FARM_ADS_KEY, false) && freeAds(s).tap,
        waterMinMinutes = maxOf(0, s.num(FARM_WATER_MIN_KEY, FarmSkill.WATER_MIN_MINUTES.toDouble()).toInt()))

    /**
     * The field's ads of a kind ("seeds", "cans") spent in this game day:
     * `farm_ads_seeds` and `farm_ads_cans`, which hold only while
     * `farm_ads_until` is the day's own reset ([QuestSkill.nextReset], 08:00
     * Vienna -- an assumption for these ads, PLAN_MEAT_FIELD_GIESSEN.md 9;
     * where the game writes "n/2" the game's count decides). From the reset
     * on it is a count of a day that is over, and the answer is 0.
     */
    fun farmAdsUsed(s: Settings, kind: String, nowEpoch: Double = System.currentTimeMillis() / 1000.0): Int {
        val until = s.num("farm_ads_until", 0.0)
        return if (until == QuestSkill.nextReset(nowEpoch)) s.num("farm_ads_$kind", 0.0).toInt() else 0
    }

    /** [farmAdsUsed] set to [n] for the game day; the other kind's count starts the day at 0. */
    fun setFarmAdsUsed(s: Settings, kind: String, n: Int,
                       nowEpoch: Double = System.currentTimeMillis() / 1000.0) {
        val reset = QuestSkill.nextReset(nowEpoch)
        if (s.num("farm_ads_until", 0.0) != reset) {
            for (k in listOf(FarmSkill.SEEDS, FarmSkill.CANS)) s.put("farm_ads_$k", 0)
            s.put("farm_ads_until", reset)
        }
        s.put("farm_ads_$kind", n)
    }

    // ---- Idle Rewards ------------------------------------------------------
    /**
     * The Idle Rewards page's one switch: the two Extra Rewards ads a day.
     * Off by default (the player, 2026-09-28), and since 2026-10-03 taken
     * only with the pass ([freeAds]), as every free ad is.
     */
    const val IDLE_ADS_KEY = "idle_ads"

    fun idle(s: Settings) = IdleSkill.Settings(ads = s.bool(IDLE_ADS_KEY, false) && freeAds(s).tap)

    /** `idle_due_at`: when the next visit is due, epoch seconds, null where none was ever made (due). */
    fun idleDueAt(s: Settings): Double? {
        val v = s.num("idle_due_at", Double.NaN)
        return if (v.isNaN()) null else v
    }

    fun rememberIdle(s: Settings, dueAt: Double) = s.put("idle_due_at", dueAt)

    /**
     * The Extra Rewards spent in this game day: `idle_ads_used`, which holds
     * only while `idle_ads_until` is the day's own reset ([QuestSkill.nextReset],
     * as `runner_fevers_until`). The second witness: the game's "n/2" decides
     * whenever the window is open.
     */
    fun idleAdsUsed(s: Settings, nowEpoch: Double = System.currentTimeMillis() / 1000.0): Int {
        val until = s.num("idle_ads_until", 0.0)
        return if (until == QuestSkill.nextReset(nowEpoch)) s.num("idle_ads_used", 0.0).toInt() else 0
    }

    fun setIdleAdsUsed(s: Settings, n: Int, nowEpoch: Double = System.currentTimeMillis() / 1000.0) {
        s.put("idle_ads_until", QuestSkill.nextReset(nowEpoch))
        s.put("idle_ads_used", n)
    }

    // ---- Tower & Ruins -----------------------------------------------------
    /**
     * `tower_fx` and `tower_fy`, or null while no point is saved -- which is
     * every phone today: the PC sets it with F2 and the phone has nothing
     * yet (SkillSettings, the tower page's note).
     */
    fun towerPoint(s: Settings): Pair<Double, Double>? {
        val fx = s.num("tower_fx", Double.NaN)
        val fy = s.num("tower_fy", Double.NaN)
        return if (fx.isNaN() || fy.isNaN()) null else fx to fy
    }

    fun towerInterval(s: Settings): Double = s.num("tower_interval", 3.0)

    // ---- Gekkomon Run -------------------------------------------------------
    /**
     * The one field the runner page has: `runner_fevers`, the Fever Times a
     * pass plays for -- the PC's key, back on the page since 2026-09-23.
     * `runner_claim` (the claims, gone the same day) and `runner_cap` are
     * read nowhere: both are left where they stand in an old
     * `digiautotap.json` -- a value nobody asks for does no harm, and a
     * `runner_cap` copied off a PC must not be able to lift the app's own
     * ceiling, [RunnerSkill.SCORE_CAP].
     */
    fun runner(s: Settings, nowEpoch: Double = System.currentTimeMillis() / 1000.0) =
        RunnerSkill.Settings(
            fevers = s.num("runner_fevers", RunnerSkill.FEVER_TARGET.toDouble()).toInt(),
            doneToday = runnerFeversToday(s, nowEpoch))

    /**
     * The Fever Times played in this game day: `runner_fevers_done`, which
     * holds only while `runner_fevers_until` is the day's own reset
     * ([QuestSkill.nextReset]); from the reset on it is a count of a day
     * that is over, and the answer is 0.
     */
    fun runnerFeversToday(s: Settings, nowEpoch: Double): Int {
        val until = s.num("runner_fevers_until", 0.0)
        return if (until == QuestSkill.nextReset(nowEpoch)) s.num("runner_fevers_done", 0.0).toInt() else 0
    }

    /** One more Fever Time for the game day ([runnerFeversToday]). */
    fun addRunnerFever(s: Settings, nowEpoch: Double = System.currentTimeMillis() / 1000.0) {
        val done = runnerFeversToday(s, nowEpoch)
        s.put("runner_fevers_until", QuestSkill.nextReset(nowEpoch))
        s.put("runner_fevers_done", done + 1)
    }

    // ---- Chef's Special ----------------------------------------------------
    /**
     * The page's one field (SkillSettings, the skewer page): how many combos
     * a day wants, 16 by default (PLAN_SKEWER.md 3.1 and 7, question 13 --
     * the event's mission counts fifteen, the sixteenth is the player's
     * margin). The plan named the key `skewer_guests` while the goal was
     * guests in a row; the goal is combos since the measurement, and so is
     * the key.
     */
    const val SKEWER_COMBOS_KEY = "skewer_combos"

    fun skewer(s: Settings, nowEpoch: Double = System.currentTimeMillis() / 1000.0) =
        SkewerSkill.Settings(
            combos = s.num(SKEWER_COMBOS_KEY, SkewerSkill.COMBO_TARGET.toDouble()).toInt(),
            doneToday = skewerCombosToday(s, nowEpoch))

    /**
     * The combos counted in this game day: `skewer_combos_done`, which holds
     * only while `skewer_combos_until` is the day's own reset
     * ([QuestSkill.nextReset], 08:00 Vienna -- the day Gekkomon Run's Fever
     * Times keep, the player's answer to question 11); from the reset on it
     * is a count of a day that is over, and the answer is 0.
     */
    fun skewerCombosToday(s: Settings, nowEpoch: Double): Int {
        val until = s.num("skewer_combos_until", 0.0)
        return if (until == QuestSkill.nextReset(nowEpoch)) s.num("skewer_combos_done", 0.0).toInt() else 0
    }

    /** One more combo for the game day ([skewerCombosToday]), both keys in one go. */
    fun addSkewerCombo(s: Settings, nowEpoch: Double = System.currentTimeMillis() / 1000.0) {
        val done = skewerCombosToday(s, nowEpoch)
        s.putAll(mapOf("skewer_combos_until" to QuestSkill.nextReset(nowEpoch),
                       "skewer_combos_done" to done + 1))
    }

    // ---- Presets -----------------------------------------------------------
    /**
     * `preset_<place>`, one per place of [Preset.Place]: the slot the next
     * switch sets there, 1 to 10. Written by [armPreset] from the profile a
     * button asked for, and read by the skill at the start of its pass; 0,
     * the default, is a place no profile has ever been armed for.
     */
    fun presetKey(place: Preset.Place) = "preset_${place.key}"

    fun preset(s: Settings) = PresetSkill.Settings(
        Preset.Place.values().associateWith { s.num(presetKey(it), 0.0).toInt() })

    /**
     * A saved profile: a name and a slot for every place (the player's
     * design of 2026-09-26, "A": picked and switched to on the Presets page).
     * Every place always has a slot, 1 to 10 -- the player struck "0 = leave
     * as it is" the same day. A profile saved before a place existed has no
     * number for it and reads [PRESET_SLOT_DEFAULT] there: Overdrive, the
     * sixth place since PLAN_DAILY_LOST_SECTOR_PRESETS.md DL2 (3.3 point 3),
     * is slot 1 in every profile of 1.2, until the sheet says otherwise.
     */
    data class PresetProfile(val name: String, val slots: Map<Preset.Place, Int>)

    // How many profiles there may be, and the ones a first opening shows.
    // Stored as a count, a name each and a map of slots each -- the shapes
    // both stores write alike; a list of names would reach the file as the
    // text of a list through org.json.
    const val PRESET_PROFILES_MAX = 5
    val PRESET_PROFILE_NAMES = listOf("Bosses", "PvP", "Stages")
    const val PRESET_SLOT_DEFAULT = 1
    private const val PROFILES_KEY = "preset_profiles"
    private const val LAST_KEY = "preset_last"
    private fun profileKey(i: Int) = "preset_profile_$i"
    private fun profileNameKey(i: Int) = "preset_profile_${i}_name"

    fun clampSlot(n: Int): Int = n.coerceIn(1, Preset.ROWS)

    fun presetProfiles(s: Settings): List<PresetProfile> {
        val n = s.num(PROFILES_KEY, PRESET_PROFILE_NAMES.size.toDouble()).toInt().coerceIn(1, PRESET_PROFILES_MAX)
        return (0 until n).map { i ->
            val slots = s.ints(profileKey(i), emptyMap())
            PresetProfile(
                s.str(profileNameKey(i), PRESET_PROFILE_NAMES.getOrElse(i) { "Profile ${i + 1}" }),
                Preset.Place.values().associateWith { clampSlot(slots[it.key] ?: PRESET_SLOT_DEFAULT) })
        }
    }

    fun putPresetProfile(s: Settings, i: Int, p: PresetProfile) {
        s.put(profileNameKey(i), p.name)
        s.putInts(profileKey(i), p.slots.entries.associate { (place, n) -> place.key to clampSlot(n) })
    }

    private fun putPresetProfiles(s: Settings, all: List<PresetProfile>) {
        val before = s.num(PROFILES_KEY, PRESET_PROFILE_NAMES.size.toDouble()).toInt()
        all.forEachIndexed { i, p -> putPresetProfile(s, i, p) }
        for (i in all.size until maxOf(before, all.size)) {
            s.put(profileNameKey(i), null)
            s.put(profileKey(i), null)
        }
        s.put(PROFILES_KEY, all.size)
    }

    /** One more profile, all slots 1, unless there are [PRESET_PROFILES_MAX]. Its index, or null. */
    fun addPresetProfile(s: Settings): Int? {
        val all = presetProfiles(s)
        if (all.size >= PRESET_PROFILES_MAX) return null
        val name = "Profile ${all.size + 1}"
        putPresetProfiles(s, all + PresetProfile(name, Preset.Place.values().associateWith { PRESET_SLOT_DEFAULT }))
        return all.size
    }

    /** Profile [i] gone, the ones after it one up; the last one stays. */
    fun removePresetProfile(s: Settings, i: Int) {
        val all = presetProfiles(s)
        if (all.size <= 1 || i !in all.indices) return
        putPresetProfiles(s, all.filterIndexed { k, _ -> k != i })
        val last = presetLast(s)
        if (last != null) s.put(LAST_KEY, if (last == i) -1 else if (last > i) last - 1 else last)
    }

    /** The profile a button last asked for, or null. */
    fun presetLast(s: Settings): Int? =
        s.num(LAST_KEY, -1.0).toInt().takeIf { it in presetProfiles(s).indices }

    /** Profile [i]'s slots as the ones the next switch sets ([preset]), and [i] as the last asked for. */
    fun armPreset(s: Settings, i: Int) {
        val p = presetProfiles(s)[i]
        for ((place, n) in p.slots) s.put(presetKey(place), clampSlot(n))
        s.put(LAST_KEY, i)
    }

    // ---- The chain ---------------------------------------------------------
    /** `chain.DEFAULT_STEPS` where nothing was ever saved; an emptied chain stays empty. */
    fun chainSteps(s: Settings): List<Chain.Step> =
        s.steps("chain_steps") ?: SkillSettings.CHAIN_DEFAULT.map { Chain.Step(it) }

    fun chainRepeat(s: Settings): Boolean = s.bool("chain_repeat", false)
}

/**
 * A [Settings] that is nothing but a map: what a test drives a skill with,
 * and what the live test writes its handful of keys into. The app's own
 * store is the same six answers over `digiautotap.json`.
 */
class MapSettings(initial: Map<String, Any?> = emptyMap()) : Settings {
    val data = LinkedHashMap<String, Any?>(initial)

    override fun bool(key: String, default: Boolean) = data[key] as? Boolean ?: default
    override fun num(key: String, default: Double) = (data[key] as? Number)?.toDouble() ?: default
    override fun str(key: String, default: String) = data[key] as? String ?: default

    @Suppress("UNCHECKED_CAST")
    override fun strings(key: String, default: List<String>) =
        data[key] as? List<String> ?: default

    @Suppress("UNCHECKED_CAST")
    override fun ints(key: String, default: Map<String, Int>) =
        data[key] as? Map<String, Int> ?: default

    @Suppress("UNCHECKED_CAST")
    override fun steps(key: String) = data[key] as? List<Chain.Step>

    override fun put(key: String, value: Any?) {
        if (value == null) data.remove(key) else data[key] = value
    }

    override fun putInts(key: String, value: Map<String, Int>) { data[key] = value }
    override fun putSteps(key: String, value: List<Chain.Step>) { data[key] = value }
}
