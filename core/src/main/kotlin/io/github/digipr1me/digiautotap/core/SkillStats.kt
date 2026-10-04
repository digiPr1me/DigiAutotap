package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import java.util.Locale

/**
 * What a skill did today, for the TODAY card on its page
 * (PLAN_ANDROID_5_SHELL.md 5.6: "TODAY sagt auf jeder Skill-Seite 'Nothing
 * yet today.' -- `stats` gibt es erst mit den Skills selbst").
 *
 * Every skill already counts what it did -- `DungeonSkill.stats`,
 * `SummonSkill.summons`, `FarmSkill.Stats` -- and each of those is a
 * **pass's** count, cleared when the next pass begins. What was missing is
 * somewhere to add them up that outlives the pass, the run and the process,
 * because the card says "today" and Android kills a service whenever it
 * likes.
 *
 * So the numbers live in the settings file, one key each, under a day
 * stamp: `stats_day` and `stats_<skill>_<what>`. Plain numbers in
 * digiautotap.json rather than a blob, for the reason every other key there
 * is plain -- a player or a bug report can read it.
 *
 * The day is handed in rather than taken: a test that had to wait for the
 * day to end would not be a test. It is any number that stays the same for
 * a day and changes when the day does; the app passes
 * [QuestSkill.statsDay], the game's next reset in epoch seconds, since
 * 2026-10-04 -- the phone's epoch day before that, and the card fell at
 * midnight.
 */
object SkillStats {

    /** The day the numbers below belong to, as the app hands it in ([QuestSkill.statsDay]). */
    const val DAY_KEY = "stats_day"

    fun key(skill: String, what: String) = "stats_${skill}_$what"

    /**
     * One line of a card: the key the skill counts it under, what the card
     * calls it, and how the day's number is made of the passes' -- a
     * [How.SUM] for a thing done over and over, a [How.BEST] for a thing one
     * pass is measured by and the day keeps the highest of.
     */
    class Line(val what: String, val label: String, val how: How = How.SUM)

    enum class How { SUM, BEST }

    /**
     * What each skill counts, and what the card calls it. The names are the
     * skills' own: `DungeonSkill.SUMMARY_LABELS` for the first, the
     * `SummonBot.stats` keys for the second, `FarmSkill.Stats` for the
     * third. Only the few worth a line on a card -- the whole summary is
     * the log's job, and it is written there after every pass.
     *
     * The rule for what earns a line is the card's own word, Today: a thing
     * the player got out of the day. Not the bookkeeping a pass needed to
     * get it -- "modes finished" stood on the summon card until 2026-09-22
     * and was the skill counting its own way through the tabs.
     */
    val SHOWN: Map<String, List<Line>> = mapOf(
        // Tickets, not attempts, since 2026-09-23: a lost run costs no
        // ticket and is tried again, so "6 attempts" stopped saying what the
        // day gave. What it gave is the tickets used, the runs it took, and
        // how many were cleared at once through Clear Previous Difficulty
        // (DungeonSkill.book). "cleared at once" rather than the button's
        // name, which [singular] would cut to "Previou".
        //
        // The daily dungeon's runs since 2026-09-29, and those the Reward
        // sheet came in (DungeonSkill.playDaily, PLAN_DAILY_LOST_SECTOR_
        // PRESETS.md 3.5); a lost one is in the log, because what the day
        // gave for it is the attempt, and that is the run. The card is to be
        // reworked later, the player's word: these two lines and no more.
        //
        // And the Lost Sector Tower's since the same day (LostSectorSkill,
        // 3.5), on this card and not one of its own: the tower has no row in
        // the Tasks list -- it stands or falls with the Dungeons row
        // (Skills.rowFor) -- and a key with no row is a number nothing shows,
        // which is why the bond tour counts under the passive helper's name
        // (notes/director.md, "A number on the card is a thing the day gave
        // the player"). CoreService adds its pass here under "dungeon".
        "dungeon" to listOf(Line("tickets", "tickets used"), Line("fights", "fights"),
                            Line("lost", "runs lost"), Line("cleared", "cleared at once"),
                            Line("ads", "ads watched"),
                            Line("daily", "daily dungeon runs"),
                            Line("daily_won", "daily dungeon runs won"),
                            Line(LostSectorSkill.CARD_RUNS, "Lost Sector runs"),
                            Line(LostSectorSkill.CARD_WON, "Lost Sector runs won")),
        "summon" to listOf(Line("summons", "summons"), Line("ads", "ads watched")),
        "mini" to listOf(Line("actions", "moves"), Line("meters", "metres"),
                         Line("rewards", "rewards collected")),
        // Cans poured and proved, and the field's ads whose reward came,
        // since 2026-09-28 (PLAN_MEAT_FIELD_GIESSEN.md 6.5). "cans used"
        // reads "1 can used" for one ([singular]).
        "farm" to listOf(Line("harvested", "harvested"), Line("planted", "planted"),
                         Line("watered", "cans used"), Line("ads", "ads watched")),
        "tower" to listOf(Line("clicks", "taps")),
        // The Bond token card counts tokens and nothing else. "times Auto
        // Spend restarted" stood here while the row was called "Bond token,
        // Auto Spend"; the pressing has no switch on the phone, so the line
        // was a nought that only ever explained a feature nobody has.
        //
        // The bond tour is this row's one box and has no row of its own
        // ([Skills.rowFor]), so what it walked and what it took are counted
        // here: `visited` is the tour's alone, and `tokens` is the helper's
        // own round and the tour's collect added together, which are two
        // different Digimon and never the same token twice.
        "passive" to listOf(Line("tokens", "bond tokens"), Line("visited", "Digimon visited")),
        "quest" to listOf(Line("claimed", "quests claimed"), Line("played", "quests played")),
        // "rewards claimed" stood here until 2026-09-23, when the task
        // stopped claiming the daily missions ([RETIRED]).
        "runner" to listOf(Line("runs", "runs"), Line("fevers", "Fever Times"),
                           Line("best", "best run", How.BEST)),
        // Chef's Special, since 2026-09-30 (PLAN_SKEWER.md 3.5): the rounds,
        // the combos the day's mission counts, and the longest combo of a
        // round -- the game's own "N Combo", a thing one round is measured by
        // and the day keeps the best of, as the runner's best run.
        "skewer" to listOf(Line("rounds", "rounds"), Line("combos", "combos"),
                           Line("best", "best combo", How.BEST)),
        // A place already on its slot is not counted: nothing changed there.
        "preset" to listOf(Line("switched", "presets switched")),
        // Idle Rewards, since 2026-09-28: the claims and the Extra Rewards
        // whose reward came (IdleSkill).
        "idle" to listOf(Line("claims", "idle rewards claimed"), Line("ads", "ads watched")),
        // EX Missions, since 2026-09-29 (ExMissionsSkill): the Claims that
        // worked, counted as taps and not as missions -- one tap on the list
        // claims every claimable row at once, up to five in EX1's measurement
        // (PLAN_EX_MISSIONS.md 10.2), so "rewards claimed" would count one
        // where the day gave five. "1 EX mission claim" for one ([singular]).
        "exmissions" to listOf(Line("claims", "EX mission claims")),
    )

    /**
     * Keys a card used to show, and no longer does. [clear] still clears
     * them: it walks [SHOWN], so a key taken out of that table is a key
     * nothing would ever remove again, and yesterday's number would sit in
     * digiautotap.json for good. Five, each from a card that lost a line
     * -- `summon_modes` on 2026-09-22, `passive_auto` with Auto Spend,
     * `runner_claimed` when Gekkomon Run stopped claiming on 2026-09-23,
     * `dungeon_attempts` when the dungeon card went over to tickets the same
     * day, and `ads_closed`, the main page's "free ads" line, gone on
     * 2026-10-03.
     */
    val RETIRED = listOf("summon" to "modes", "passive" to "auto", "runner" to "claimed",
                         "dungeon" to "attempts", "ads" to "closed")

    /**
     * Add what one pass counted. The day stamp is rolled here and nowhere
     * else: the first thing written after the reset clears yesterday, so a
     * card cannot show a number from a day that is over.
     *
     * A pass that counted nothing writes nothing at all. That is not
     * tidiness: the passive helper's round is one of these and it comes
     * round every two seconds all day, and every `put` rewrites
     * digiautotap.json.
     */
    fun add(s: Settings, skill: String, counts: Map<String, Int>, today: Long) {
        // Only what this skill's card shows. A skill hands over everything it
        // counted -- DungeonSkill's map has eight keys -- and a key written
        // here that [SHOWN] does not name is a key [clear] would not clear,
        // so it would outlive the day it belongs to. Seen live: a live pass
        // wrote stats_dungeon_skipped, which no card reads.
        val mine = (SHOWN[skill] ?: return).associateBy { it.what }
        val real = counts.filterKeys { it in mine }.filterValues { it > 0 }
        val sameDay = s.num(DAY_KEY, -1.0).toLong() == today
        if (real.isEmpty() && sameDay) return
        if (!sameDay) clear(s, today)
        for ((what, n) in real) {
            val had = s.num(key(skill, what), 0.0).toInt()
            // A [How.BEST] line is not added up: the day keeps the best pass,
            // and a worse one after it writes nothing at all -- the run that
            // scored 400 after one that scored 1,200 has not made 1,600.
            val now = if (mine.getValue(what).how == How.BEST) maxOf(had, n) else had + n
            if (now != had) s.put(key(skill, what), now)
        }
    }

    /** Yesterday's numbers are not today's. */
    fun clear(s: Settings, today: Long) {
        for ((skill, shown) in SHOWN) for (line in shown) s.put(key(skill, line.what), null)
        for ((skill, what) in RETIRED) s.put(key(skill, what), null)
        s.put(DAY_KEY, today)
    }

    /** What this skill has counted today, or an empty map where nothing has. */
    fun read(s: Settings, skill: String, today: Long): Map<String, Int> {
        if (s.num(DAY_KEY, -1.0).toLong() != today) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (line in SHOWN[skill] ?: return emptyMap()) {
            val v = s.num(key(skill, line.what), Double.NaN)
            if (!v.isNaN()) out[line.what] = v.toInt()
        }
        return out
    }

    /**
     * "6 attempts, 5 fights, 1 ad watched." -- the TODAY card's one line, or
     * null where this skill has done nothing today. A count of 0 is left out
     * of the sentence but not out of the file: "6 attempts" reads better
     * than "6 attempts, 0 ads watched", and a card with every number at zero
     * has nothing to say.
     *
     * A [How.BEST] line reads the other way round -- "best run 1,204" --
     * because it is not a count of anything: putting the number first would
     * say the day held 1,204 best runs.
     *
     * Every number goes through [Locale.US]. On a German phone
     * `String.format("%,d")` wrote "5.000" for five thousand (NOTES.md, the
     * passive helper's first test run), and this is a line a player reads.
     */
    fun sentence(skill: String, counts: Map<String, Int>): String? {
        val shown = SHOWN[skill] ?: return null
        val parts = shown.mapNotNull { line ->
            val n = counts[line.what] ?: return@mapNotNull null
            when {
                n == 0 -> null
                line.how == How.BEST -> String.format(Locale.US, "%s %,d", line.label, n)
                else -> String.format(Locale.US, "%,d %s", n,
                                      if (n == 1) singular(line.label) else line.label)
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(", ") + "."
    }

    /**
     * "1 ads watched" is not a line anybody writes. The noun of a label is
     * its first word that ends in an s -- "ads watched", "bond tokens",
     * "Fever Times" -- and where no word does, "Digimon visited" and
     * "harvested" among them, the label already reads for one. A label whose
     * first s-word is not a plural ("a boss beaten") would want its own
     * spelling here; there is none, and this is the place to add it.
     */
    internal fun singular(label: String): String {
        val words = label.split(" ")
        val i = words.indexOfFirst { it.length > 1 && it.endsWith("s") }
        if (i < 0) return label
        return words.mapIndexed { j, w -> if (j == i) w.dropLast(1) else w }.joinToString(" ")
    }
}

/**
 * A skill, plus the counting of what it did. [inner] is asked for its
 * numbers the moment it hands the screen back -- that is when they are
 * this pass's, before the next pass clears them -- and they go to [add].
 *
 * A wrapper rather than a line inside each skill for two reasons. A skill
 * that keeps a running total is a skill with a second memory to get wrong,
 * and the L sessions were told to touch only their own file: adding the
 * same three lines to six skills is how six copies of a rule start
 * (PLAN_ANDROID_APP.md 4).
 */
class Counted(
    private val inner: Skill,
    /** This pass's numbers, read straight off the skill as it hands back. */
    private val counts: () -> Map<String, Int>,
    private val add: (String, Map<String, Int>) -> Unit,
) : Skill by inner {

    override fun work(img: Mat): Outcome {
        val outcome = inner.work(img)
        add(inner.key, counts())
        return outcome
    }

    override fun run(): Outcome {
        val outcome = inner.run()
        add(inner.key, counts())
        return outcome
    }

    /**
     * A pass the switch paused and the director goes on with: counted like
     * any other, because the stopped part handed its own numbers over as it
     * stopped and the part that goes on counts from its own start (Skill.resume).
     */
    override fun resume(img: Mat, whole: Boolean): Outcome {
        val outcome = inner.resume(img, whole)
        add(inner.key, counts())
        return outcome
    }
}
