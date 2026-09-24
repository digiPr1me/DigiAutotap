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
    fun included(s: Settings, key: String): Boolean = s.bool(includedKey(key), true)

    fun mode(s: Settings): String = s.str(SkillSettings.MODE_KEY, SkillSettings.MODE_DEFAULT)

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
        // `use_ads` until 2026-09-24; see [adPass].
        useAds = adPass(s),
        // No switch on the phone's page. app.py's default was on; the task
        // runs without it since 2026-09-22, because the pre-check's two
        // swipes and its reads bought a skipped card or two, and what the
        // player asked for is the cards they set played -- a card at zero
        // opens, shows its ad button, and is left in a few seconds. The
        // quest loop passes its own `survey = true` (QuestSkill.dungeonFor):
        // `dungeonShort` and `dungeonBlocked` are read off that survey.
        survey = s.bool("survey", false),
        attemptsBeforeClear = s.num(DUNGEON_CLEAR_KEY,
                                    DungeonSkill.ATTEMPTS_BEFORE_CLEAR.toDouble()).toInt())

    /** The Dungeons page's "Attempts before Clear Previous Difficulty". */
    const val DUNGEON_CLEAR_KEY = "dungeon_attempts_before_clear"

    // ---- Special Summon ----------------------------------------------------
    fun summon(s: Settings) = SummonSkill.Settings(
        skill = s.bool("summon_skill", true),
        support = s.bool("summon_support", true),
        crest = s.bool("summon_crest", true),
        // `summon_ads` until 2026-09-24; see [adPass].
        watchAdsFirst = adPass(s),
        maxPerMode = s.num("summon_max", 0.0).toInt())

    /**
     * Does the player have the game's Ad Skip Pass? The one switch behind
     * every free ad the app watches -- Dungeons, Summon and the Quest Loop
     * read it from here (SkillSettings.AD_PASS_KEY, off by default).
     */
    fun adPass(s: Settings): Boolean = s.bool(SkillSettings.AD_PASS_KEY, SkillSettings.AD_PASS_DEFAULT)

    // ---- Digital World Search ----------------------------------------------
    /**
     * Nothing is read at all, which is why this asks for no [Settings]: the
     * World Search page is gone with every field on it (SkillSettings), so
     * the skill runs on [WorldSearchSettings]' own defaults -- everything on
     * the board collected, no paw floor, no action limit, no metre target,
     * 0.7 s between clicks, and the pace speeding up while the moves land.
     *
     * The keys the page used to write are left where they are in an old
     * `digiautotap.json`; a value nothing asks for does no harm, and a
     * `want_<key>` set to false on a settings file copied off a PC would
     * otherwise take a board item away from a player who has no way left to
     * put it back.
     */
    fun worldSearch() = WorldSearchSettings()

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
        adPass = adPass(s))

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
     * Both keys gone: what the Quest Loop row's switch does on its way to
     * on, so that a player who bought tickets and wants the loop back
     * before eight has a way to say so.
     */
    fun unlockQuest(s: Settings) {
        s.put("quest_locked_until", null)
        s.put("quest_locked_reason", null)
    }

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
