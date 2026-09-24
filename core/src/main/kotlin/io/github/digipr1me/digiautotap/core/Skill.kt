package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * The seam between the director and a skill (PLAN_ANDROID_3_DIRECTOR.md
 * 3.2). Every skill of the PC version begins on the main screen and walks
 * to its own screen itself; the director's semi-automatic mode needs the
 * second half alone -- "you are standing on the dungeon list, work it" --
 * and the fully automatic mode the whole walk, as the chain runs it today.
 * So a skill has two entry points and one question in front of each:
 *
 *   worksOn(screen)   which of the director's screens its work begins on
 *   hasBudget()       is there anything to do, on the settings as they stand
 *   seesWork(s, img)  is there anything to do on THIS frame -- the one
 *                     question with a picture in it, and the only one that
 *                     can see a screen the player changes under the app
 *   work(img)         the second half: the screen in front is one worksOn
 *                     said yes to; work it and end on that same screen
 *   run()             the whole: from the plain main screen, go there, work,
 *                     come home -- "hingehen, work, nach Hause"
 *   leave()           run's last third alone: from the skill's own screen,
 *                     home -- the chain's second hand (DirectorLoop.full)
 *
 * Where the seam lies in each Python skill, so that the skill sessions cut
 * it at the same place (the Python skills are **not** rebuilt for it; the
 * PC version is the laboratory, and a rebuild there would have no oracle
 * test covering it):
 *
 *   Dungeons      run  = dungeon.py:2653  DungeonBot.run: _play, go_home in a finally
 *                 work = dungeon.py:2673  _play from the line after `open_list`
 *                        (dungeon.py:1755) has answered True
 *   Summons       run  = summon.py:1586   SummonBot.run: _spend, leave_summons in a finally
 *                 work = summon.py:1605   _spend from the line after `open_summons`
 *                        (summon.py:1118) has answered True
 *   Meat Field    run  = farm.py:1248     FarmBot.visit
 *                 work = farm.py:1256     visit from the line after `go_to_field`
 *                        (farm.py:1037) has answered True; the field is left
 *                        standing, visit's own way home is the caller's
 *   World Search  run  = engine.py:297    engine.run: open_board (explore.py:600),
 *                        the loop, leave_board (explore.py:645) in a finally
 *                 work = engine.py:350    find_start on the board that is there,
 *                        then _run_loop
 *   Quest Loop    work = quest.py:1091    QuestLoop.tick -- already the seam: it
 *                        begins on the plain main screen with a readable card
 *                        and ends there; run is the same call
 *   Passive       work = passive.py:1199  PassiveBot.tick -- already the seam;
 *                        the director's round on the main screen is this call
 *   Tower         run  = tower.py:136     TowerBot.run -- taps a saved point
 *                        wherever it stands; there is no screen of its own to
 *                        recognise, so worksOn answers nothing and hasBudget is
 *                        chain.tower_has_point (chain.py:108): a Tower step is
 *                        in only while a point is saved
 *
 * Where `work` ends: on the screen it began on. The player led the app
 * there, and it leaves them there. `run` ends at home, as the chain does
 * today. The passive helper is the one skill with no end: its work is one
 * round, and the director calls it again next round.
 *
 * What a skill may not do is answer for the director. It taps nothing on a
 * screen it was not given, it presses OK on no prompt it did not raise
 * (NOTES.md, "Prompts and dialogs"), and it checks the main switch between
 * two actions, never in the middle of one (`guard.Stop`; NOTES.md,
 * "wait_while_paused does not answer 'was I stopped'").
 */
interface Skill {
    /** The settings key the shell knows it by: "dungeon", "summon", "farm", ... */
    val key: String

    /** The name the log calls it: "Dungeons", "Special Summon", ... */
    val name: String

    /** Is [screen] (one of [Director.SCREENS]) a screen this skill's work begins on? */
    fun worksOn(screen: String): Boolean

    /**
     * Is there anything to do, on the settings as they stand right now?
     * `chain.dungeon_has_budget`, `summon_has_mode`, `farm_is_due`,
     * `tower_has_point`, `mini_has_target` -- asked fresh every round, never
     * remembered: a chain step's "nothing to do" is a question, not a state
     * (chain.py, farm_is_due).
     */
    fun hasBudget(): Boolean

    /**
     * Is there work on THIS frame, on a screen [worksOn] said yes to?
     *
     * The two rules the director keeps in front of [work] -- one work per
     * visit of a screen, and [hasBudget] -- are both answered without a
     * frame. The first says "a second pass would only find what the first
     * left"; the second asks the settings and, for the Meat Field, a clock.
     * Both are right for a screen the app walked to itself and wrong for one
     * the player is standing on and changing under it: the player harvests a
     * plot by hand, the plot is empty from that second on, and the director
     * is still saying "has worked this field" while the schedule says "back
     * in forty minutes".
     *
     * A skill that can tell from one frame whether there is anything to do
     * answers here, and its answer replaces both rules on that screen: true
     * hands the screen over again, however often the picture changes; false
     * stands still and looks again next round, which is the scan. The
     * default is null -- "not from a frame" -- and the two old rules hold
     * unchanged for every skill that does not override it.
     *
     * It is asked every round the skill is a candidate on, so it reads
     * cheaply and taps nothing. Asked in the semi-automatic mode only: in
     * the fully automatic mode the app is walking the chain, not standing in
     * front of the screen, and the clock is the right question there.
     */
    fun seesWork(screen: String, img: Mat): Boolean? = null

    /**
     * The second half: [img] is the frame the director classified as a
     * screen [worksOn] said yes to. Work it, sending taps through the
     * skill's own capture, and end on that same screen.
     */
    fun work(img: Mat): Outcome

    /** The whole: from the plain main screen, go there, [work], come home. */
    fun run(): Outcome

    /**
     * The last third of [run] on its own: from a screen [worksOn] says yes
     * to, home to the plain main screen. True once the main screen is back.
     *
     * Asked by the director's fully automatic mode when a chain step has
     * handed the screen back and one of the skills' screens is still
     * standing after [Director.SETTLE] -- the second hand of the chain
     * (DirectorLoop.full). Every walking skill already has this way home in
     * its `run`'s finally; this is the seam that lets somebody else ask for
     * it. Not gated on the main switch, bounded, and it taps only what it
     * has recognised, as every way home here does. The default is "I have
     * none": the round-skills live on the main screen, and the Tower has no
     * screen of its own.
     */
    fun leave(): Boolean = false

    /**
     * The beat this skill wants until its next round, in seconds, or null
     * for the director's own. Asked of the round-skills on the main screen
     * only (DirectorLoop.mainRound), where the director's beat is the slow
     * one and a round that has just seen something -- the passive helper
     * after a tap on the figure -- knows better than the director how soon
     * the next look is worth taking. The director takes the shortest
     * answer, and a skill that has nothing to say answers null.
     */
    fun beat(): Double? = null
}

/**
 * What a skill says when it hands the screen back. [DONE] is the chain's
 * `("done", "")`; [RETIRED] is the quest loop's `("quest_off", reason)` with
 * `until_reset` -- nothing left until the game hands something out again,
 * which no round of this chain can reach (chain.py, retire); [STOPPED] is
 * the main switch having gone off between two actions; [PARKED] is a run
 * that could not go on and says why in [why], the way every skill parks
 * today -- the director shows the reason and stands still.
 */
enum class Result { DONE, RETIRED, STOPPED, PARKED }

class Outcome(
    val result: Result,
    val why: String = "",
    /**
     * `dismiss_confirm`'s `want_ok` at the seam: a skill that hands the
     * screen back while a pink prompt of its own may still be standing names
     * what it was doing -- "leaving the dungeon" -- and the director then
     * presses OK on a pink prompt in the hand-over right after, and on no
     * other. The three pink prompts are identical to the pixel and only the
     * caller knows which one it raised (NOTES.md, "Prompts and dialogs").
     */
    val leaving: String? = null,
) {
    override fun toString(): String =
        result.name.lowercase() + (if (why.isNotEmpty()) ": $why" else "")

    companion object {
        val DONE = Outcome(Result.DONE)
        val STOPPED = Outcome(Result.STOPPED)
        fun parked(why: String) = Outcome(Result.PARKED, why)
        fun retired(why: String) = Outcome(Result.RETIRED, why)
    }
}

/**
 * A skill that only answers [worksOn] and writes to the log in [work]: what
 * the director is given until the skill sessions replace it
 * (PLAN_ANDROID_3_DIRECTOR.md 6). Says where it would have worked -- once,
 * and again after [SAY_EVERY] seconds, because a round-skill on the main
 * screen is asked every two seconds all day -- and taps nothing.
 */
class LogSkill(
    override val key: String,
    override val name: String,
    private val screens: Set<String>,
    private val budget: () -> Boolean = { true },
    private val log: (String) -> Unit = { HelperLog.line(it) },
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) : Skill {
    private val lastSaid = HashMap<String, Double>()

    override fun worksOn(screen: String): Boolean = screen in screens
    override fun hasBudget(): Boolean = budget()

    private fun say(text: String) {
        val t = now()
        if (t - (lastSaid[text] ?: Double.NEGATIVE_INFINITY) < SAY_EVERY) return
        lastSaid[text] = t
        log(text)
    }

    override fun work(img: Mat): Outcome {
        say("  $name would work here now (${img.cols()} x ${img.rows()}); " +
            "the skill is not on the phone yet, nothing tapped")
        return Outcome.DONE
    }

    override fun run(): Outcome {
        say("  $name would run now; the skill is not on the phone yet, nothing tapped")
        return Outcome.DONE
    }

    companion object {
        const val SAY_EVERY = 60.0
    }
}
