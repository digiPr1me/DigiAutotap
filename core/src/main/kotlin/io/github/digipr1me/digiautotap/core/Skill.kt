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
 *   leavesFrom(s, img) a screen no skill works on that leave() takes home
 *                     from all the same: a dungeon's panel, for Dungeons
 *   resumesOn(s, img) can the pass the main switch stopped go on from s
 *   resume(img, whole) go on with it: the same pass, where it stood
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
 * (the laboratory's notes, "Prompts and dialogs"), and it checks the main
 * switch between two actions, never in the middle of one (`guard.Stop`; the
 * laboratory's notes, "wait_while_paused does not answer 'was I stopped'").
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
     * Why [hasBudget] says no, where the skill knows a better reason than
     * the settings -- a day already over by a clock it wrote itself -- or
     * null, and the chain says "nothing to do, on the settings as they
     * stand" as it always did. Asked only after [hasBudget] answered false
     * (PLAN_DAILY_LOST_SECTOR_PRESETS.md G20: a retired Lost Sector Tower
     * was skipped "on the settings" while its minutes stood on the page).
     */
    fun noBudgetWhy(): String? = null

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
     * it. Bounded, and it taps only what it has recognised, as every way
     * home here does. And gated on the main switch since 2026-09-30, as
     * every way home is ([Stays]): with the switch off it taps nothing and
     * the game stays where it is (PLAN_WORLD_SEARCH_FORMATE.md F24). The
     * default is "I have none": the round-skills live on the main screen,
     * and the Tower has no screen of its own.
     */
    fun leave(): Boolean = false

    /**
     * Is [img], a [screen] no skill [worksOn], one this skill's [leave] takes
     * home from all the same? Asked by the chain's second hand only
     * (DirectorLoop's `secondHand`), on what a step or a round of the fully
     * automatic mode left standing -- never on a screen the player opened.
     *
     * A dungeon's panel is the one such screen (PLAN_ABSCHLUSS_1_3.md A5i,
     * question 22): the director calls it `dialog`, as it calls every pop-up,
     * no skill works on it, and the globe is not read under it, so the second
     * hand parked there with "no way home" -- live on instance 0 on
     * 2026-10-03 at 18:14:53, after the quest loop's round had opened
     * DemiDevimon's panel and lost its frame on it. Dungeons' own way home
     * closes a panel at the end of every card it plays, and says yes here on
     * the panels its readers prove (DungeonSkill.leavesFrom). It is asked
     * every look of the second hand, so it reads cheaply and taps nothing;
     * the default is no, and the screen parks as it did.
     */
    fun leavesFrom(screen: String, img: Mat): Boolean = false

    /**
     * Can the pass the main switch stopped go on from [screen]? The name
     * alone; [resumesOn] with the frame is what the director asks, and its
     * default asks this.
     *
     * The player's rule of 2026-09-29 (PLAN_WORLD_SEARCH_FORMATE.md F27) for
     * World Search on the board, made every task's on 2026-09-30
     * (PLAN_RELEASE_1_3.md B4): a pass the switch paused goes on where it
     * stood, in both modes. The default is false: the screen is the
     * player's, as before.
     */
    fun resumesOn(screen: String): Boolean = false

    /**
     * Can the pass the main switch stopped go on from [screen], with [img]
     * in front? Asked by the director (DirectorLoop) of the task the switch
     * stopped -- a chain step, the semi-automatic mode's work, a round of
     * the main screen, a task the page asked for -- on every look of the
     * pause and on the first looks after it: true on every look keeps the
     * claim, and with the switch back on hands this screen to [resume]; a
     * known screen that says false ends it, and the screen is the
     * player's, as it always was (notes/director.md, "A pass the switch
     * paused goes on where it stands, and a screen the player opened is
     * still the player's", and its extension of 2026-09-30).
     *
     * The frame is there for the screens the director's `classify` does
     * not name as the task's own: the Reward sheet after a won run is
     * `unknown` to the director and a dungeon panel `dialog`, and only the
     * task can say they are the ones it stopped on. It is asked every
     * round of a pause, so it reads cheaply and taps nothing.
     */
    fun resumesOn(screen: String, img: Mat): Boolean = resumesOn(screen)

    /**
     * Go on with the pass the main switch stopped, from [img], a screen
     * [resumesOn] said yes to -- or, for a chain step whose claim ended, the
     * plain main screen. The same pass, not a new one: what it has spent
     * (tickets, draws, ads, minutes, places) is the pass's and counts
     * against its limits; what it counts for the TODAY card is only what
     * it does from here, since the stopped part handed its own count over
     * as it stopped (notes/director.md, "A counter nothing clears is counted
     * again at the end of every pass").
     *
     * [whole]: end as [run] ends, at home -- a chain step, a round of the
     * main screen, a task the page asked for; false ends where [work]
     * ends, on the screen the semi-automatic mode handed over. The default
     * begins afresh, for a skill whose pass carries nothing: [run], or
     * [work] on [img].
     */
    fun resume(img: Mat, whole: Boolean): Outcome = if (whole) run() else work(img)

    /**
     * The player switched this task's row off and on (PLAN_ABSCHLUSS_1_3.md
     * A2): its day is gone from the file already (Stored.restart), and what
     * the skill itself still carries of an ending -- the quest loop's
     * "switched off" ([QuestSkill.resume]) -- is forgotten here. Called by the
     * director on its own thread, at the round after the switch; never in
     * the middle of a turn. The default carries nothing: every other task
     * asks the file afresh at every question.
     */
    fun restarted() {}

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

    /**
     * Is [screen] a window this round-skill's own tap opened a moment ago?
     *
     * Asked by the director of the round-skills (DirectorLoop's `rounds`) on
     * any screen but the main one, in both modes, before anything else is
     * decided about that screen: true gives it to [work], which closes it,
     * where the director would otherwise have parked on a window it did not
     * open itself. The bond token's tap on the figure opens the Partner
     * window whenever the token was taken a moment earlier, and the round
     * that knows to close it was never asked (notes/bond.md, "A window the
     * bond token's own tap opened is the bond token's to close"). The
     * default is false: nothing any other round taps opens a window.
     */
    fun opened(screen: String): Boolean = false
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
     * caller knows which one it raised (the laboratory's notes, "Prompts
     * and dialogs").
     */
    val leaving: String? = null,
    /**
     * The run ended because a frame could not be taken ([CaptureError]) --
     * the player opened another app, this one included, or the screenshot
     * failed -- and not because of anything on the screen. Such a step has
     * not failed: back on the main screen the director begins it again
     * instead of retiring it for the chain run (PLAN_ABSCHLUSS_1_3.md K1;
     * notes/director.md, "A step that lost its frame has not failed").
     * Carried by the outcome itself, never told from the words of [why].
     */
    val lostFrame: Boolean = false,
) {
    override fun toString(): String =
        result.name.lowercase() + (if (why.isNotEmpty()) ": $why" else "")

    companion object {
        val DONE = Outcome(Result.DONE)
        val STOPPED = Outcome(Result.STOPPED)
        fun parked(why: String) = Outcome(Result.PARKED, why)
        fun retired(why: String) = Outcome(Result.RETIRED, why)
        /** A park whose cause is a lost frame ([lostFrame]), with the error's sentence after [what]. */
        fun noFrame(e: CaptureError, what: String = "no frame") =
            Outcome(Result.PARKED, "$what: ${e.message}", lostFrame = true)
    }
}

/**
 * A way home that asks the main switch first: with it off, nothing is
 * tapped, nothing is kept, and the game stays where the pause found it,
 * said once a pass.
 *
 * The player's rule of 2026-09-28 for World Search
 * (PLAN_WORLD_SEARCH_FORMATE.md F13; notes/world-search.md, "After the pause
 * the game stays where it is"), made every task's on 2026-09-30
 * (PLAN_RELEASE_1_3.md B4, F24 there). Every walking skill's way home ran
 * in a finally "not gated on the main switch" -- the switch is one of the
 * ways a pass ends, and where the game was left was the point -- and since
 * 2026-09-22 the service holds every gesture while the switch is off
 * (notes/director.md, "The director asks "is the game in front" and "is
 * the switch on" once a round"), so after a pause the way home tapped into
 * nothing for its rounds and ended in "could not get back to the main
 * screen" and a `no_way_home` frame, both false. One sentence for all of
 * them, as World Search's own `stays` says it.
 */
class Stays(private val on: () -> Boolean, private val log: (String) -> Unit) {
    private var said = false

    /** True where the way home ends here: the switch is off. The sentence the first time in a pass. */
    fun now(): Boolean {
        if (on()) return false
        if (!said) {
            said = true
            log(LINE)
        }
        return true
    }

    /**
     * The window of the game's that ended a way home in this pass ([over]),
     * or null: a way home that calls another one asks this after it, so
     * that "could not get back" is not said over a window it was right to
     * leave alone.
     */
    var met: String? = null
        private set

    /**
     * True where the way home ends here because one of the game's own
     * windows is over [img] -- its news after the reset, its "Time Sale!"
     * window, its Help tutorial ([window]) -- with the sentence the first
     * time in a pass, nothing tapped and no frame kept. The way home hands
     * the screen back as it stands, and the director, which reads the same
     * windows, closes the news (notes/director.md, "The day's reset stacks
     * its windows over the main screen too") and says what the other two
     * are. Until 2026-10-01 every way home but Dungeons' tapped its globe or
     * its X past such a window, or waited it out blind, and said "could not
     * get back to the main screen" with a `no_way_home` frame
     * (PLAN_RELEASE_1_3.md B60, B72; notes/director.md, "A way home leaves the
     * game's own windows to the director").
     */
    fun over(img: Mat): Boolean {
        val what = window(img) ?: return false
        if (met == null) {
            met = what
            log("the game's $what is over the way home -- leaving it to the director, nothing tapped")
        }
        return true
    }

    /** A new pass: the sentences may be said again. */
    fun reset() {
        said = false
        met = null
    }

    companion object {
        const val LINE = "the main switch is off -- not going home, the game stays where it is"

        /**
         * Which of the game's own windows is over [img], in the words the
         * log says it with, or null: the readers the director's `classify`
         * names `news`, `sale` and `help` by.
         */
        fun window(img: Mat): String? = when {
            Startup.announcement(img) != null -> "news after its reset"
            Startup.saleWindow(img) != null -> "\"Time Sale!\" window"
            Startup.helpWindow(img) != null -> "Help tutorial"
            else -> null
        }
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
