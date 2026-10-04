package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import java.util.Locale
import kotlin.math.abs

/**
 * The Meat Field under the director (PLAN_ANDROID_APP.md 4, Session L):
 * `farm.FarmBot` and `farm.next_visit`, carried over from farm.py, cut at
 * the seam Skill.kt names for this skill --
 *
 *   run  = farm.py:1248  FarmBot.visit
 *   work = farm.py:1256  visit from the line after go_to_field
 *          (farm.py:1037) has answered True; the field is left standing,
 *          visit's own way home belongs to run
 *
 * One visit, start to finish: every ripe plot harvested, the free seed
 * planted in every empty one, the timers read, and then the clock -- which
 * is the whole point of this skill and the reason it is the one skill with
 * a memory. [nextVisit] says when to come back and why, [hasBudget] asks
 * whether that time has come, and [remember] is where the answer is kept
 * (`farm_due_at`, `farm_grow_seconds`, `farm_last_reason` in app.py).
 *
 * The readers are Farm.kt (farm.py's own), Explore.kt and Dungeon.kt. This
 * file holds what farm.py has beyond them: the schedule and the loop. Every
 * constant came over with its Python name and value; a number is changed
 * in this file, with a measurement and its sentence (NOTES.md, "One
 * project").
 *
 * Since 2026-09-28 a visit also waters (PLAN_MEAT_FIELD_GIESSEN.md 6): the
 * free cans are poured on the growing plot that ripens first, until they
 * are at 0, and what ripens is harvested and planted again; with the page's
 * switches it takes the Good and Great seeds once the free ones are out
 * (`farm_better_seeds`) and the field's two ads a day for seeds and for
 * cans (`farm_ads`). Every one of those actions has its proof and its
 * brake, said where it is done.
 *
 * What it never does, and the sentences that cost it:
 *
 *  - it never taps a water bubble. The bubble is checked fresh right before
 *    every tap on a plot, whatever state that plot was read as a moment ago
 *    (NOTES.md, "the state is a frame, not a truth").
 *  - it presses Water only on the popup of a plot it read as growing, and
 *    only to water it; a plot that turns out to be growing while it was
 *    meant to be planted or harvested still has its popup closed by a tap
 *    well clear of it (PLAN_MEAT_FIELD 9).
 *  - it never taps the Huge Watering Can (an hour a can, the blue one), and
 *    it never touches the dialog's MIN, -, + and MAX: the box opens at the
 *    fewest cans that ripen the plot, and that number is what Water spends
 *    (PLAN_MEAT_FIELD_GIESSEN.md 4.1, 5.2).
 *  - it never pours a can that does not ripen the plot, and never on a plot
 *    with `farm_water_min_minutes` or less left, or whose time left nobody
 *    could read (13, the player's rule of 2026-09-28): a box capped under
 *    the plot's need by the cans there are is closed without Water.
 *  - it never takes a Good or Great seed while a free one is there, nor
 *    with the switch off, nor on one witness: the header's sack and the
 *    free slot's own count both have to say 0 (4.4).
 *  - it never taps an ad with `farm_ads` off, and never a third one of a
 *    kind in a game day (4.6).
 *  - it never taps a locked plot, the three pots or the shop, and it buys
 *    nothing.
 *  - it presses OK on no prompt it did not raise, and it raises none: the
 *    field has no confirmation prompt of its own, so [Outcome.leaving] is
 *    always null here and the director presses nothing in the hand-over.
 *  - it never sends the back key. The way out is the field's own X, then the
 *    globe on the Explore menu that X lands on.
 */
class FarmSkill(
    private val cap: Capture,
    /**
     * `farm_due_at`: when the next visit is due, epoch seconds, or null
     * where nothing has ever been recorded. Read fresh on every [hasBudget],
     * never remembered here -- a chain step's "nothing to do" is a question,
     * not a state (chain.py, farm_is_due).
     */
    private val dueAt: () -> Double?,
    /**
     * What one visit leaves behind: `farm_due_at`, `farm_grow_seconds` (only
     * when this visit planted something and could read the fresh plate) and
     * `farm_last_reason`, exactly the three app.py saves after every visit
     * (app.py:3867). core has no settings store yet, so the shell
     * hands this in. What is read back out again is [knownGrowSeconds].
     */
    private val remember: (dueAt: Double, growSeconds: Int?, reason: String) -> Unit,
    /**
     * `farm_grow_seconds` read back: the grow duration the last visit that
     * planted something learned, or null where none was ever recorded. Asked
     * only on a visit that planted nothing itself -- a grow duration does not
     * go stale (the field is the same field), and without it such a visit caps
     * no field timer at all, which is the one case the cap was written for
     * (app.py, both `next_visit` call sites).
     */
    private val knownGrowSeconds: () -> Int? = { null },
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** The main switch, asked between two actions and never in the middle of one. */
    private val on: () -> Boolean = { MainSwitch.on },
    /** Keep a frame worth looking at afterwards. The app writes files; core cannot. */
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    /** farm.py's own default: every wait in this skill hangs off this one factor. */
    private val patience: Double = 1.5,
    /** Injected so the flow test can run the whole visit without waiting. */
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    /**
     * Epoch seconds, **not** the director's monotonic clock: `farm_due_at`
     * outlives the process, and a monotonic number means nothing after a
     * restart. app.py's own is `time.time()`.
     */
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    /** The page's two switches and the app's Ad Skip Pass ([Stored.farm]), asked afresh at every question. */
    private val settings: () -> Settings = { Settings() },
    /**
     * How many of the day's ads of a kind ([SEEDS], [CANS]) are spent --
     * collected, or given up on because the game or the proof said so --
     * in this game day ([Stored.farmAdsUsed]). The file is the second
     * witness: where the game writes "n/2" beside an ad, the game's count
     * is the one that decides (PLAN_MEAT_FIELD_GIESSEN.md 6.2).
     */
    private val adsUsed: (String) -> Int = { 0 },
    /** Writes [adsUsed] for the game day ([Stored.setFarmAdsUsed]). */
    private val setAdsUsed: (String, Int) -> Unit = { _, _ -> },
) : Skill {

    /**
     * The Meat Field page (SkillSettings, "farm"): [betterSeeds] is
     * `farm_better_seeds`, [ads] is `farm_ads`, both off by default
     * (PLAN_MEAT_FIELD_GIESSEN.md 4.5). Watering has no switch (4.8): with
     * the task on, the free cans are used -- since 2026-09-28 only on a plot
     * with more than [waterMinMinutes] left (`farm_water_min_minutes`, 13),
     * and only as many as ripen it.
     *
     * Since 2026-10-02 [ads] is the Ad Rewards card's Meat Field pick, and
     * since 2026-10-03 that pick with the Ad Skip Pass ([FreeAds],
     * [Stored.farm]): the field's ads are tapped only where the reward
     * comes with the tap.
     */
    data class Settings(val betterSeeds: Boolean = false, val ads: Boolean = false,
                        val waterMinMinutes: Int = WATER_MIN_MINUTES)

    override val key = "farm"
    override val name = "Meat Field"

    /**
     * The plain field, and only that. A field with the seed menu or the
     * water popup over it (Director.FIELD_DIALOG) is a dialog **the player**
     * raised -- this skill opens the seed menu itself and closes it again
     * inside one plot, so a dialog still standing when the director looks is
     * nobody's business here, and NOTES.md's rule about prompts is the same
     * rule. go_to_field asks the same question the other way round
     * (`meat_field_screen >= 6`, nothing covering it).
     */
    override fun worksOn(screen: String): Boolean = screen == Director.FIELD

    /**
     * chain.farm_is_due (chain.py:112), asked fresh every round.
     *
     * Skip rather than retire (PLAN_MEAT_FIELD 6.3): the free seeds refill
     * every hour, so there is no "nothing left until the daily reset" the way
     * the dungeon budgets have, and retiring the step would lock a long,
     * repeating chain out of the field for hours after the shortest timer
     * that happened to be running when the chain last reached it. None --
     * nothing ever recorded, e.g. right after the switch was turned on --
     * counts as due: activating the switch means visiting at once.
     */
    override fun hasBudget(): Boolean = farmIsDue(dueAt(), now())

    /**
     * Why a chain passes the field by: its clock, in minutes. "Nothing to
     * do, on the settings as they stand" was the sentence until 2026-10-01,
     * and a Meat Field first in the order and skipped for its clock read as
     * a Meat Field the chain had forgotten.
     */
    override fun noBudgetWhy(): String? {
        val at = dueAt() ?: return null
        val left = at - now()
        if (left <= 0) return null
        return "the field is not due yet -- next visit in ${Math.ceil(left / 60.0).toInt()} min"
    }

    /**
     * The field, read afresh on every round the player spends standing on
     * it: is a plot ripe, or is one empty with a seed in hand?
     *
     * This is the one skill whose screen the player stays on and changes
     * under the app, and the two questions the director asks without a frame
     * are both wrong there. "Has worked this field" is a sentence about a
     * visit the app made; the player harvesting a plot by hand ends that
     * visit's truth the second the plot turns empty. And the clock -- the
     * whole point of this skill, [nextVisit]'s answer -- is a prediction of
     * when the field will next be worth a walk from the main screen. Neither
     * survives a player standing in the field, so on the field the frame
     * decides and both of them stand aside ([Skill.seesWork]).
     *
     * What it costs: [Farm.plotBadgeKind] six times and [Farm.freeSeeds]
     * once, every two seconds while the field is in front. Not
     * [Farm.plotState]: "growing" is the one state with nothing to do in it,
     * and telling it apart from an unreadable plot costs [Farm.plotTimer] on
     * all six cells, which is the expensive half of the reader and buys
     * nothing here.
     *
     * The brake ([futileOn]): a work that harvested nothing and planted
     * nothing, entered because this reader said there was something to do,
     * writes down the field it looked at. The same field never gets a second
     * one -- a plot that will not plant would otherwise be tapped every
     * round for as long as the player stood there. It takes a picture that
     * differs (the player did something, a timer ran out) or the clock
     * coming due, which for exactly this case is [FALLBACK], ten minutes.
     *
     * Since 2026-09-28 the cans are a reason too (PLAN_MEAT_FIELD_GIESSEN.md
     * 6.5, 4.10): a can in hand and a plot growing is work, and so is an
     * empty plot with no free seed where the page lets a Good seed or the
     * seeds' ad fill it, and a growing plot with no can where the cans' ad
     * is still to be had today. Since the threshold of 13 (2026-09-28) the
     * growing plot has to be one the visit would water: its plate over
     * `farm_water_min_minutes` and, with cans in hand, its need
     * ([cansToRipen]) no more than they are. What that costs over the old
     * question is [Farm.waterCans] once and [Farm.plotTimer] on the cells
     * with no badge, and only where nothing ripe or empty has said "work"
     * already -- the plate is the one time the bare field shows; a plot
     * whose plate does not read is one the visit would not water either. That reading can be wrong while the dot
     * stands on the can's row (PLAN_MEAT_FIELD_GIESSEN.md 5.5, fy 0.110 to
     * 0.135 of the player's strip): too many cans is a visit that reads the
     * true count with the dot moved off and does nothing, which the brake
     * then holds shut; too few is a field that waits for the next visit
     * the clock books.
     */
    override fun seesWork(screen: String, img: Mat): Boolean? {
        if (screen != Director.FIELD) return null
        var ripe = false
        var empty = false
        val bare = ArrayList<Pair<Int, Int>>()
        val kinds = StringBuilder()
        for (row in 0 until 3) {
            for (col in 0 until 2) {
                val kind = Farm.plotBadgeKind(img, col, row)
                when (kind) {
                    "ripe" -> ripe = true
                    "empty" -> empty = true
                    null -> bare += col to row
                }
                kinds.append(kind ?: "-").append(',')
            }
        }
        val seeds = Farm.freeSeeds(img)
        val cans = Farm.waterCans(img)
        val s = settings()
        // A seed count that could not be read is not a reason to stand still:
        // plantOne reads it again for itself, and the brake catches a plot
        // that turns out to have nothing to plant into it.
        val seedInHand = seeds == null || seeds > 0 || s.betterSeeds || adLeft(SEEDS)
        // The rule of 13 on the bare field: a can is work only where a plot
        // reads over the threshold and the cans cover its need, or, at 0
        // cans with the day's ad still to be had, where a plot reads over the
        // threshold at all -- else the semi-automatic mode would run against
        // the brake every time the view changed. The plates of the cells
        // with no badge, which is [Farm.plotTimer] on at most six cells and
        // only when neither a ripe nor an empty plot has already said "work";
        // a plate that does not read is a plot the visit would not water
        // either ([pickWater] wants `growing`, which wants the plate).
        val min = maxOf(0, s.waterMinMinutes) * 60
        fun plotFor(limit: Int?) = bare.any { (col, row) ->
            val left = Farm.plotTimer(img, col, row)
            left != null && left > min && (limit == null || cansToRipen(left) <= limit)
        }
        // With the cans' ad still to be had, any can count is work where a
        // plot reads over the threshold: the rest is poured to open the ad
        // where it ripens nothing ([water]). At 0 cans the page's switch is
        // enough and not the file: the visit reads the game's own "n/2"
        // ([adAsk]), and the brake below holds a field whose offer said 0
        // shut -- live on LDPlayer on 2026-10-02 at 09:10 a file that had
        // the cans' ads spent from a misread kept the field at 0 cans with
        // the game's 1/2 on offer, as on the Poco the evening before.
        val work = ripe || (empty && seedInHand) ||
            (cans != null && cans > 0 && plotFor(cans)) ||
            (cans != null && cans > 0 && adLeft(CANS) && plotFor(null)) ||
            (cans == 0 && s.ads && plotFor(null))
        lastSeen = "$kinds/${seeds ?: "?"}/${cans ?: "?"}"
        if (!work) return false
        if (lastSeen == futileOn && !hasBudget()) return false
        return true
    }

    /**
     * The second half: the field is standing in front, work it and leave it
     * standing. [img] is the frame the director classified; the body grabs
     * its own from here on, as visit does.
     */
    override fun work(img: Mat): Outcome {
        rect = Dungeon.gameRect(img)
        room = Dungeon.headroom(img)
        // A fresh visit, and `work` is now called again for as long as the
        // player keeps changing the field under it ([seesWork]). Only a plot
        // THIS visit planted may supply the grow duration, so the set is
        // emptied here as `run` empties it -- carried over, the plot planted
        // two visits ago would hand [setClock] a remainder for a full grow.
        plantedCells.clear()
        val stats = begin()
        var ended = false
        try {
            try {
                clearHeader()
                body(stats)
                ended = true
            } finally {
                headerBack()
            }
        } finally {
            // A visit the main switch ended is not evidence about the
            // field: it got nowhere because it was stopped, and the brake
            // would then hold the same unchanged field shut after the
            // switch came back on. Nor is one an error unwound ([ended]
            // false): live on 2026-09-28 (M3, 07:08) the player opened this
            // app while the director was handing the field over, the visit's
            // first grab threw "the game is not in front", and the finally
            // booked "nothing growing, no timer read" for an hour and held
            // the field shut as futile -- with two plots ripe on it, which
            // then stood unharvested while the player looked at them. Such
            // a visit read nothing, so it books nothing ([setClock] only
            // where the visit ended) and the brake stays as it was.
            if (ended) {
                futileOn = if (stats.reason != "stopped" && stats.nothingDone()) lastSeen else null
                setClock(stats)
            }
        }
        return outcomeOf(stats)
    }

    /**
     * A pass's own count, from its first line: [lastVisit] is what the TODAY
     * card is handed ([lastCounts]), and a pass that ends in an error before
     * it counted anything must hand over nothing rather than the pass
     * before it (notes/director.md, "A counter nothing clears is counted again").
     */
    private fun begin(): Stats {
        val stats = Stats()
        lastVisit = stats
        betterOut = false
        seedAdSaid = false
        canAdSaid = false
        lastKind = null
        remaining.clear()
        shortOf.clear()
        said.clear()
        seedsOut = false
        tappedThisVisit.clear()
        boughtBefore.clear()
        adFailed.clear()
        adDone.clear()
        stays.reset()
        return stats
    }

    /** The whole: from the plain main screen, go there, work, come home. */
    override fun run(): Outcome {
        val stats = begin()
        fieldTapped = false
        plantedCells.clear()
        // The chain's own walk to the field: whatever [seesWork] wrote down
        // while the player last stood there says nothing about the field
        // this run is about to open.
        futileOn = null
        lastSeen = null
        var ended = false
        try {
            if (!goToField()) {
                stats.reason = "could not open the Meat Field"
            } else {
                clearHeader()
                body(stats)
            }
            ended = true
        } finally {
            // Both in a finally, and in this order, as visit and app.py have
            // them: the way home runs on every way out, and the clock is set
            // after every visit -- including one that got nowhere, which then
            // books the hour that "nothing growing, no timer read" gives it
            // rather than trying again on the next round for ever. The dot
            // goes back first: the way home reads nothing on its rows.
            headerBack()
            leaveField()
            // Not after an error unwound the visit (see [work]): it read
            // nothing, and "nothing growing" booked for an hour would keep
            // the chain away from a field it never saw.
            if (ended) setClock(stats)
        }
        return outcomeOf(stats)
    }

    // ------------------------------------------------------------------------
    // The schedule -- a pure function, no capture, fully testable
    // ------------------------------------------------------------------------
    /** What one visit found, `FarmBot.stats` key for key. */
    class Stats {
        var harvested = 0
        var planted = 0
        /** Field timers actually read this visit, seconds. */
        val timers = ArrayList<Int>()
        var emptyLeft = 0
        var freeSeeds: Int? = null
        var refillIn: Int? = null
        var growSeconds: Int? = null
        var reason = ""
        /** How many plots are growing at all, read or not -- visit's `growing`. */
        var growing = 0
        /** Cans poured, each one proved (PLAN_MEAT_FIELD_GIESSEN.md 6.2): the TODAY card's `watered`. */
        var watered = 0
        /** Ads whose reward came, seeds and cans together: the TODAY card's `ads`. */
        var ads = 0
        /** The green can's count at the end of the visit, or null where it did not read twice alike. */
        var cans: Int? = null
        /** Seconds until the next free can, or null. */
        var canRefillIn: Int? = null
        /** Watering ended by its brake with cans still in hand (6.3): the clock books [FALLBACK]. */
        var waterStopped = false
        /** Why an ad parked the visit -- an ad where the Ad Skip Pass was taken for granted ([FreeAds.PARK]) -- or null. */
        var adPark: String? = null
        /**
         * The time left, seconds at the visit's end, on every plot still
         * growing that was left unwatered because the cans in hand do not
         * ripen it (13): [nextVisit]'s row 10.
         */
        val wanting = ArrayList<Int>()

        /** Nothing at all came of the visit: the brake's question ([futileOn]). */
        fun nothingDone() = harvested == 0 && planted == 0 && watered == 0 && ads == 0

        override fun toString() =
            "harvested $harvested, planted $planted, watered $watered, ads $ads, timers $timers, " +
                "empty $emptyLeft, seeds $freeSeeds, refill $refillIn, cans $cans, " +
                "can refill $canRefillIn, grow $growSeconds, $reason"
    }

    /**
     * next_visit's two answers. Python writes the second into a
     * `reason_out` list because it has no second return value; this is that
     * list, with a name.
     */
    class Visit(val at: Double, val why: String) {
        override fun toString() = "$why (at $at)"
    }

    companion object {
        // ---- the schedule (farm.py, "The schedule") ----
        /** Seconds after a timer's own deadline, so it is surely done. */
        const val MARGIN = 20
        /** A free seed arrives once an hour. */
        const val REFILL = 60 * 60
        /** Something is growing but no timer on it could be read. */
        const val FALLBACK = 10 * 60

        // ---- the loop (farm.py, "FarmBot") ----
        const val NAV_TAPS_MAX = 3
        const val NAV_ROUNDS = 6
        const val PLOT_ROUNDS = 6
        const val EXIT_ROUNDS = 20
        const val EXIT_TAPS_MAX = 4

        /**
         * dungeon.py's HOME_ROUNDS and HOME_PRESSES_MAX, for the globe
         * [goHome] presses.
         */
        const val HOME_ROUNDS = 6
        const val HOME_PRESSES_MAX = 3

        /**
         * dungeon.py:85's NEUTRAL_TAP_Y, which is what farm.py taps the Stage
         * Failed banner away with -- not the director's own NEUTRAL_TAP_FY,
         * which is summon.py's 0.10. Two measured spots that both open
         * nothing; each caller keeps the one its Python has.
         */
        const val NEUTRAL_TAP_Y = 0.12

        // ---- watering (PLAN_MEAT_FIELD_GIESSEN.md, measured by M1) ----
        /**
         * What one Tiny Watering Can takes off a plot's timer. Measured
         * 2026-09-28 on two plots, one can each: 54:23 -> 24:21 two seconds
         * later, and 53:35 -> 23:11 in a frame 24 s later -- 30:00 to the
         * second (PLAN_MEAT_FIELD_GIESSEN.md 5.2).
         */
        const val CAN_SECONDS = 30 * 60
        /**
         * How far the popup's time after a watering may stand from "before
         * less 30:00 a can" (4.1, "± 2 min"): the two readings are a few
         * seconds and one popup apart, and the time runs between them.
         */
        const val WATER_TOL = 120
        /**
         * How far a button may stand from where the frame before put it and
         * still be the same button at rest ([waitSteady]), as a fraction of
         * the canvas. A blob's centre on a dialog at rest moves by a pixel or
         * two between frames (0.001 at 1920); the seed menu coming in on the
         * Poco on 2026-10-01 put Select 0.04 and more off its place at rest.
         */
        const val STEADY_TOL = 0.01
        /**
         * How far two readings of one plate may stray from "fell by the
         * time between them" ([runsDown]), seconds: a plate counts whole
         * seconds, and the frame is taken some time inside the grab the
         * clock is read around -- a second either side, and one more.
         */
        const val COUNT_TOL = 2
        /**
         * Rounds of [pauseLong] an ad's reward is waited for ([settle]). With
         * the Ad Skip Pass on the Poco on 2026-10-01 at 08:06:34 the Reward
         * sheet over the can dialog stood only 11 s after the tap, after the
         * six rounds of [PLOT_ROUNDS] had looked at frames that were neither
         * the sheet nor the field; the cans had come (20 on the dialog under
         * the sheet) and the visit wrote both of the day's cans' ads off.
         */
        const val SETTLE_ROUNDS = 20
        /**
         * `farm_water_min_minutes`' default: "everything over 20 minutes is
         * watered", the player's words of 2026-09-28
         * (PLAN_MEAT_FIELD_GIESSEN.md 13). A plot with this much or less
         * left ripens on its own soon enough; M3 had poured a can on one
         * with 29 s.
         */
        const val WATER_MIN_MINUTES = 20

        /**
         * The cans that ripen a plot with [seconds] left at once: one per
         * 30:00 begun, which is what the can dialog's box opens at (M1,
         * 5.2: 2 for 55:24, 1 for 19:39) -- the player's rule that a can is
         * only used where it brings the timer to zero (13).
         */
        fun cansToRipen(seconds: Int): Int = maxOf(1, (seconds + CAN_SECONDS - 1) / CAN_SECONDS)

        /**
         * Seconds from now until a plot with [left] seconds can be watered by
         * the rule of 13 -- its need, [cansToRipen], no more than the cans
         * there will be, and the plot still over [minSeconds] -- or null
         * where that moment never comes before it drops under the threshold
         * by itself. The cans there will be are [cans] now, one more at
         * [canRefillIn] (unread: [REFILL]) and one an hour after that
         * (M1: one an hour). The two ways it comes are the two the plan
         * names, whichever is earlier: enough cans arrive, or the plot's own
         * time shrinks to the cans in hand.
         */
        fun waterableIn(left: Int, cans: Int, canRefillIn: Int?, minSeconds: Int): Int? {
            val first = minOf(canRefillIn ?: REFILL, REFILL)
            // At most 20 cans are ever held (they refill to 20), and no plot
            // needs more than a full grow's worth: the loop is short.
            for (k in 0..20) {
                val start = if (k == 0) 0 else first + (k - 1) * REFILL
                val end = if (k == 0) first else start + REFILL
                if (left - start <= minSeconds) return null
                val t = maxOf(start, left - (cans + k) * CAN_SECONDS)
                if (t < end && left - t > minSeconds) return t
            }
            return null
        }
        /**
         * The field's ads of a day, per kind: "n/2" on the offer counts the
         * ones left (M1, 5.2). The file's count stops at this.
         */
        const val DAY_ADS = 2
        /** [Farm.AdOffer.kind] of the seed menu's "Ad (n/2)", and the key [adsUsed] is asked with. */
        const val SEEDS = "seeds"
        /** [Farm.AdOffer.kind] of the can dialog's "n/2" and "View Ads". */
        const val CANS = "cans"
        /** The seed menu's three slots, left to right: the free seed, Good, Great. */
        val SEED_NAMES = listOf("free", "Good", "Great")

        /**
         * chain.farm_is_due (chain.py:112), which is [Chain.farmIsDue] now:
         * the budget questions live beside the chain that asks them
         * (PLAN_ANDROID_APP.md 4). Kept as the name this skill's test and
         * its callers already use, and as nothing else.
         */
        fun farmIsDue(dueAt: Double?, now: Double): Boolean = Chain.farmIsDue(dueAt, now)

        /**
         * When to come back, and why. farm.next_visit line for line.
         *
         * [timers]: field timers actually READ this visit, seconds -- growing
         * plots whose plate happened to parse.
         * [growing]: how many plots are known to be growing at all, read or
         * not (defaults to timers.size, "assume every growing plot's timer was
         * read", for callers that have nothing better). Needed to tell "something
         * is growing but its plate did not parse this time" (row 5) apart from
         * "nothing at all is growing" (row 6) -- both look like an empty timer
         * list otherwise.
         * [growSeconds]: a full grow duration -- the cap under which no field
         * timer's own misreading can push the next visit too late (a field
         * cannot possibly take longer to ripen than a full grow), and row 5's
         * own answer when no timer was read at all. Two places it can come
         * from, and the caller picks: the plate of a seed THIS visit planted
         * ([Stats.growSeconds], the only one a visit learns), or, on a visit
         * that planted nothing, the one an earlier visit learned and the
         * caller kept ([knownGrowSeconds], `farm_grow_seconds`). A grow
         * duration does not go stale -- the field is the same field -- and
         * without the remembered one a visit that planted nothing has no
         * ceiling at all, which is the one case the cap was written for.
         * [emptyLeft]: plots that stayed empty (no seeds to plant, or planting
         * failed).
         * [freeSeeds]: the count read this visit, or null if it could not be read.
         * [refillIn]: seconds until the next free seed, or null if unreadable.
         *
         * A field timer is capped at [growSeconds]; the refill is capped at
         * [REFILL]. Either cap turns "a misread came out far too large" into
         * "one visit arrives a little early", which is cheap, rather than "the
         * visit is scheduled for a day from now", which is not.
         *
         * | situation                                    | next visit               |
         * |----------------------------------------------|--------------------------|
         * | 1 timer(s) read, no plot empty                | min(timers) + MARGIN     |
         * | 2 plot empty, 0 seeds, refill readable        | earlier of min(timers), refill, both +MARGIN |
         * | 3 plot empty, 0 seeds, refill unreadable      | earlier of min(timers), REFILL, both +MARGIN |
         * | 4 plot empty, seeds > 0 (planting failed)     | FALLBACK, logged why     |
         * | 5 something growing, no timer read            | grow_seconds if known, else FALLBACK |
         * | 6 nothing growing, nothing empty (or empty w/ 0 seeds and no timers) | refill or REFILL, +MARGIN |
         * | 7 something growing, cans 0, can refill readable   | the earlier of rows 1-6 and can + MARGIN |
         * | 8 something growing, cans 0, can refill unreadable | the earlier of rows 1-6 and REFILL + MARGIN |
         * | 9 something growing, cans left by the brake        | the earlier of rows 1-6 and FALLBACK |
         * | 10 growing, cans > 0, a plot left for want of cans | the earlier of rows 1-6 and [waterableIn] + MARGIN |
         *
         * Rows 7 to 9 are the cans (PLAN_MEAT_FIELD_GIESSEN.md 4.7, 6.4): a
         * can arriving is a reason to come back only while something grows
         * that it could be poured on, which is one visit an hour in the
         * fully automatic mode while the field is busy. [cans] is the count
         * the visit ended on, null where it did not read -- and then the
         * table is rows 1 to 6 as before. [canRefillIn] is capped at [REFILL],
         * as the seed's is: a can comes once an hour (59:50 measured right
         * after one arrived). [waterStopped] is the brake of 6.3 -- watering
         * ended with cans in hand because a proof did not come -- and the
         * cans left are worth a look in ten minutes, not in an hour.
         *
         * Row 10 is the threshold of 13 (the player's, 2026-09-28): a plot
         * is watered only with as many cans as ripen it, so a plot over the
         * threshold whose need the cans in hand do not cover stays as it is
         * -- 1:40:00 left and three cans, say. [wanting] is the time each
         * such plot had left, seconds from [now]; [waterMin] the threshold in
         * seconds. It comes back when the first of them can be watered:
         * enough cans have come (one at [canRefillIn], then one an hour), or
         * its time has shrunk to the cans in hand, whichever is earlier, and
         * never for a plot that drops under the threshold first -- that one
         * ripens by itself, which rows 1 to 6 already book.
         */
        fun nextVisit(now: Double, timers: List<Int>, growSeconds: Int? = null,
                      emptyLeft: Int = 0, freeSeeds: Int? = null, refillIn: Int? = null,
                      growing: Int? = null, cans: Int? = null, canRefillIn: Int? = null,
                      waterStopped: Boolean = false, wanting: List<Int> = emptyList(),
                      waterMin: Int = WATER_MIN_MINUTES * 60): Visit {
            val base = seedsAndTimers(now, timers, growSeconds, emptyLeft, freeSeeds, refillIn, growing)
            val howManyGrowing = growing ?: timers.size
            if (howManyGrowing == 0 || cans == null) return base
            val can = when {
                cans == 0 -> {
                    val c = canRefillIn?.let { minOf(it, REFILL) }
                    if (c != null) Visit(now + c + MARGIN, "a can arrives")
                    else Visit(now + REFILL + MARGIN, "a can arrives (refill unreadable)")
                }
                waterStopped -> Visit(now + FALLBACK, "cans left -- watering stopped short")
                else -> {
                    val t = wanting.mapNotNull { waterableIn(it, cans, canRefillIn, waterMin) }.minOrNull()
                        ?: return base
                    Visit(now + t + MARGIN, "enough cans for a plot left to grow")
                }
            }
            return if (can.at < base.at) can else base
        }

        /** Rows 1 to 6 of [nextVisit]'s table: farm.next_visit line for line. */
        private fun seedsAndTimers(now: Double, timers: List<Int>, growSeconds: Int?,
                                   emptyLeft: Int, freeSeeds: Int?, refillIn: Int?,
                                   growing: Int?): Visit {
            val howManyGrowing = growing ?: timers.size

            fun cap(value: Int?, ceiling: Int?): Int? {
                if (value == null) return null
                // Python: `min(value, ceiling) if ceiling else value` -- a
                // ceiling of 0 is no ceiling, the same as None.
                return if (ceiling != null && ceiling != 0) minOf(value, ceiling) else value
            }

            val cappedTimers = timers.mapNotNull { cap(it, growSeconds) }
            val cappedRefill = cap(refillIn, REFILL)

            fun done(reason: String, delay: Int) = Visit(now + delay, reason)

            if (cappedTimers.isNotEmpty() && emptyLeft == 0) {
                return done("field ripens", cappedTimers.min() + MARGIN)
            }

            if (emptyLeft > 0) {
                if (freeSeeds == 0) {
                    if (cappedTimers.isNotEmpty() && cappedRefill != null) {
                        return done("field ripens or seed arrives",
                                    minOf(cappedTimers.min() + MARGIN, cappedRefill + MARGIN))
                    }
                    if (cappedTimers.isNotEmpty()) {
                        return done("field ripens or seed arrives (refill unreadable)",
                                    minOf(cappedTimers.min() + MARGIN, REFILL + MARGIN))
                    }
                    if (cappedRefill != null) {
                        return done("seed arrives", cappedRefill + MARGIN)
                    }
                    return done("seed arrives (refill unreadable)", REFILL + MARGIN)
                }
                return done("empty plot with seeds on hand -- planting may have failed", FALLBACK)
            }

            if (howManyGrowing > 0) {
                if (growSeconds != null && growSeconds != 0) {
                    return done("something is growing, no timer read", growSeconds)
                }
                return done("something is growing, no timer read, grow time unknown", FALLBACK)
            }

            if (cappedRefill != null) {
                return done("nothing growing, waiting for a seed", cappedRefill + MARGIN)
            }
            return done("nothing growing, no timer read", REFILL + MARGIN)
        }
    }

    // ------------------------------------------------------------------------
    // The visit
    // ------------------------------------------------------------------------
    private val pauseLong = 1.0 * patience
    private val pauseShort = 0.5 * patience
    /**
     * The beat of the watering round (N2, the player's 2026-10-01: "wenn die
     * Zeiten der Felder gescannt sind, kann das Bewässern ruhig schneller
     * gehen"). A dialog of the field is still confirmed on two frames, and a
     * button only where it stands at the same place on both ([waitSteady]),
     * but the two are a third of [pauseLong] apart: measured over the 31
     * waterings of the Poco's report of 2026-10-01, a pour took 11 s from the
     * tap on the plot to its proof (10 to 12) and a pour to the next 20 s
     * (median of 25), of which the waits of [pauseLong] were about 13 s.
     */
    private val pauseBeat = patience / 3
    /** [PLOT_ROUNDS] at [pauseBeat]: the same time to wait, in rounds of the beat. */
    private val beatRounds = PLOT_ROUNDS * 3

    /**
     * The reference rect of the last frame read, which is what a tap is
     * aimed with. DungeonBot measures it once in `_device_size` and keeps
     * it; this keeps the newest, which is the same number on every frame of
     * one device and right again if the frame shape ever changes.
     */
    private var rect: Dungeon.GameRect? = null
    /** The headroom of the frame [rect] was taken off (Dungeon.CanvasFrame). */
    private var room = 0

    /** Only a field this visit opened (or found open) has an X worth tapping. */
    private var fieldTapped = false

    /** The field as [seesWork] last read it: the six badges and the seed count. */
    private var lastSeen: String? = null

    /** The field a work achieved nothing on, which gets no second one. */
    private var futileOn: String? = null

    /**
     * The (col, row) of every plot THIS visit planted with a free seed --
     * see [body]. Only a free seed teaches the grow duration: Good and Great
     * both grew 03:54:05 into a 1,200 Aruraumon where the free seed gave a
     * Tanemon of 58 minutes, a Palmon of about two hours or an Aruraumon
     * (PLAN_MEAT_FIELD_GIESSEN.md 5.2), and a cap learned from a bought seed
     * would be a cap nearly twice too high for the free ones.
     */
    private val plantedCells = mutableSetOf<Pair<Int, Int>>()

    /** Whether [clearHeader] asked the overlay off the header's can row this visit. */
    private var cleared = false
    /** Both bought seeds read 0 this visit: no menu is opened again for them. */
    private var betterOut = false
    /** "2/2 already today" said once a visit, not once a plot. */
    private var seedAdSaid = false
    private var canAdSaid = false
    /** The slot the last seed of this visit came from (0 free, 1 Good, 2 Great), for the log's changes. */
    private var lastKind: Int? = null
    /** A bought seed's count before it was planted, checked against the next menu that shows it. */
    private val boughtBefore = HashMap<Int, Int>()
    /**
     * A plot's time left as its popup last said it, and when (epoch
     * seconds): the plates are small and often unread, the popup large and
     * green (PLAN_MEAT_FIELD_GIESSEN.md 9), and a time read while watering
     * is the best there is for the next choice.
     */
    private val remaining = HashMap<Pair<Int, Int>, Pair<Int, Double>>()
    /**
     * A plot left to grow this visit because the cans in hand do not ripen
     * it (13), and when it ripens by itself (epoch seconds): the clock's row
     * 10 ([Stats.wanting]). A plot watered afterwards after all -- the cans'
     * ad came -- leaves it.
     */
    private val shortOf = HashMap<Pair<Int, Int>, Double>()
    /** The "left to grow" lines already said this visit: once a plot and a reason, not once a round. */
    private val said = HashSet<String>()

    /** The threshold of 13 in seconds: a plot is watered only with more than this left. */
    private fun minSeconds(): Int = maxOf(0, settings().waterMinMinutes) * 60

    private fun sayOnce(line: String) {
        if (said.add(line)) log(line)
    }

    /** Plot ([cell]) with [left] seconds is not watered for want of cans: said, and kept for the clock. */
    private fun leftShort(cell: Pair<Int, Int>, left: Int, need: Int, stock: Int) {
        shortOf[cell] = now() + left
        sayOnce("watering plot ${cell.first},${cell.second}: needs $need can${if (need == 1) "" else "s"}, " +
                    "$stock in stock -- left to grow")
    }

    /** Plot ([cell]) with [left] seconds is at or under the threshold: said, not watered. */
    private fun leftUnder(cell: Pair<Int, Int>, left: Int) {
        sayOnce("watering plot ${cell.first},${cell.second}: ${hms(left)} left, not over " +
                    "${settings().waterMinMinutes} min -- left to grow")
    }
    private fun grab(): Mat = cap.grab().also { rect = Dungeon.gameRect(it); room = Dungeon.headroom(it) }

    /**
     * A tap by fraction of the reference window, as DungeonBot.tap sends one,
     * in the rectangle at [anchor] (Dungeon.Anchor): the field is the
     * HUD's, and only an X read where another window stands carries another.
     */
    private fun tap(fx: Double, fy: Double, was: String,
                    anchor: Dungeon.Anchor = Dungeon.Anchor.BOTTOM) {
        // Not with the main switch off: the service would hold it back, and
        // the step it belongs to ends at its next look (pauseGate).
        if (!on()) return
        // Never into an ad's window: live on LDPlayer on 2026-10-02 at
        // 08:24:00, with the pass switched on for an account without it, a
        // frame of the ad read as the "Ad viewing limit" OK and the tap on it
        // opened the store.
        if (adInFront()) {
            log("    an ad is in front -- no tap ($was)")
            return
        }
        val r = rect ?: return
        val y0 = r.y0 - Py.roundInt(room * anchor.share)
        cap.tap(Py.roundInt(r.x0 + fx * r.gw), Py.roundInt(y0 + fy * r.gh))
    }

    private fun tap(target: Explore.Target, was: String) = tap(target.fx, target.fy, was, target.anchor)

    /** One of the game's activities that is not the game is in front: an ad ([Ads.foreign]). */
    private fun adInFront(): Boolean {
        val hand = cap.adHand() ?: return false
        return Ads.foreign(hand.front(), hand.game) != null
    }

    /**
     * False when the visit must not go on. On the PC this is both
     * `control.is_set()` and `wait_while_paused()` (NOTES.md:
     * wait_while_paused alone answers only the pause question); on the phone
     * there is one main switch and no pause, so this is that switch --
     * asked between two actions, never in the middle of one.
     */
    private fun pauseGate(): Boolean = on()

    private fun dump(img: Mat, tag: String) {
        // A step the switch cut short did not fail: the frame would show the
        // game standing where the pause found it (PLAN_RELEASE_1_3.md B4).
        if (!on()) return
        keep(img, tag)
        log("    kept what it saw as $tag")
    }

    /** grab, read, release -- the shape of every "one number off a fresh frame". */
    private fun <T> onFrame(read: (Mat) -> T): T {
        val img = grab()
        try {
            return read(img)
        } finally {
            img.release()
        }
    }

    /**
     * farm.read_timer_twice: call [read] twice, [pause] apart, and believe
     * the second only if it is smaller than the first by the time between
     * them (PLAN_MEAT_FIELD 2.4; [runsDown] since 2026-10-02, 0-4 s before).
     * A single frame is never trusted for a number that is supposed to be
     * counting down.
     */
    private fun readTwice(pause: Double = 2.0, read: () -> Int?): Int? {
        // Since 2026-10-02 against the clock between the two ([runsDown]),
        // not against 0 to 4 s, which a slow frame overran.
        val t1 = now()
        val first = read() ?: return null
        sleep(pause)
        val t2 = now()
        val second = read() ?: return null
        return if (runsDown(first, t1, second, t2)) second else null
    }

    /** A count that does not run down ([Farm.freeSeeds]): the same number on two frames [pause] apart, or null. */
    private fun sameTwice(pause: Double, read: () -> Int?): Int? {
        val first = read() ?: return null
        sleep(pause)
        val second = read() ?: return null
        return if (first == second) second else null
    }

    /**
     * Wait for [check] to be true twice running, at most [rounds] rounds --
     * a frame mid-animation can show a state briefly that has not really
     * arrived yet. The frame that confirmed it, or null; the caller owns it.
     */
    private fun waitFor(rounds: Int = NAV_ROUNDS, pause: Double = pauseLong, check: (Mat) -> Boolean): Mat? {
        var confirmedOnce = false
        for (i in 0 until rounds) {
            if (!pauseGate()) return null
            val img = grab()
            if (check(img)) {
                if (confirmedOnce) return img
                confirmedOnce = true
            } else {
                confirmedOnce = false
            }
            img.release()
            sleep(pause)
        }
        return null
    }

    /** The frames the last [waitSteady] grabbed: the log's witness of what the beat costs. */
    private var steadyFrames = 0

    /** [waitFor] at the watering round's beat ([pauseBeat]), for as long as [PLOT_ROUNDS] waited. */
    private fun waitBeat(check: (Mat) -> Boolean): Mat? = waitFor(beatRounds, pauseBeat, check)

    /**
     * Two frames a beat apart on which [where] answers the same place, within
     * [STEADY_TOL] of the canvas: a dialog that is up and has stopped moving.
     * The confirming frame, or null; the caller owns it. Live on the Poco on
     * 2026-10-01 at 20:06:05 a Select read off a seed menu still coming in
     * landed at fy 0.623 under a button that stood at 0.56 to 0.61 once the
     * menu had arrived, the plot stayed empty and the menu stood over the
     * field when the visit ended (the `field_dialog` park of 20:06:28). The
     * beat is what lets the round go faster; this is what keeps a tap off a
     * button in motion.
     */
    private fun waitSteady(where: (Mat) -> Pair<Double, Double>?): Mat? {
        var last: Pair<Double, Double>? = null
        for (i in 0 until beatRounds) {
            if (!pauseGate()) return null
            val img = grab()
            val here = where(img)
            steadyFrames = i + 1
            if (here != null && last != null &&
                abs(here.first - last.first) <= STEADY_TOL && abs(here.second - last.second) <= STEADY_TOL) {
                return img
            }
            last = here
            img.release()
            sleep(pauseBeat)
        }
        return null
    }

    private fun tapUntil(target: Explore.Target, was: String, tapsMax: Int = NAV_TAPS_MAX,
                         rounds: Int = NAV_ROUNDS, check: (Mat) -> Boolean): Mat? {
        for (i in 0 until tapsMax) {
            tap(target, was)
            val img = waitFor(rounds, check = check)
            if (img != null) return img
            if (!on()) return null
        }
        return null
    }

    // ------------------------------------------------------------------------
    /**
     * Main screen -> Explore menu -> Meat Field. True once it is open (all
     * six cells visible, nothing covering it).
     */
    fun goToField(): Boolean {
        var img = grab()
        try {
            if (Farm.meatFieldScreen(img) >= Director.FIELD_CELLS) {
                log("the field is already open")
                fieldTapped = true
                return true
            }
            if (Dungeon.stageFailed(img)) {
                log("the Stage Failed banner is up, tapping it away first")
                tap(0.5, NEUTRAL_TAP_Y, "dismiss Stage Failed")
                sleep(pauseLong)
                img.release()
                img = grab()
            }
            // Over a few seconds, not on one frame: the game draws over the
            // auto button by itself (MainScreen, K2 of PLAN_ABSCHLUSS_1_3.md).
            val main = MainScreen.settle(img, ::grab, sleep, now, ::pauseGate, log) { last, waited ->
                log("not the plain main screen after %.0f s, cannot open the Meat Field".format(waited))
                dump(last, "not_main_screen")
            } ?: return false
            if (main !== img) {
                img.release()
                img = main
            }
            val tab = Explore.exploreTab(img)
            if (tab == null) {
                log("the Explore tab was not found")
                dump(img, "no_explore_tab")
                return false
            }
            // farm.py waits for E._dark_body_share >= E.MENU_DARK_MIN, which
            // is private to Explore.kt. exploreMenu is that same dark body
            // plus the two things the very next lines need anyway: the globe
            // (leave_field's way home) and the Digital World Search card
            // (meat_field_card's own anchor). Nothing is loosened by it.
            val menu = tapUntil(Explore.Target(tab.fx, tab.fy), "Explore tab") {
                Explore.exploreMenu(it) != null
            }
            if (menu == null) {
                if (on()) {
                    log("the Explore menu did not open")
                    onFrame { dump(it, "no_explore_menu") }
                }
                return false
            }
            try {
                val card = Explore.meatFieldCard(menu)
                if (card == null) {
                    log("the Meat Field card was not found")
                    dump(menu, "no_field_card")
                    return false
                }
                fieldTapped = true
                val field = tapUntil(card, "Meat Field card") {
                    Farm.meatFieldScreen(it) >= Director.FIELD_CELLS
                }
                if (field == null) {
                    if (on()) {
                        log("the Meat Field did not open")
                        onFrame { dump(it, "no_field") }
                    }
                    return false
                }
                field.release()
                return true
            } finally {
                menu.release()
            }
        } finally {
            img.release()
        }
    }

    // ------------------------------------------------------------------------
    /**
     * True if this ripe plot was collected, false otherwise. Never a second
     * blind tap: the state after the first is the only proof asked for
     * (the laboratory's notes, "the proof a claim landed is a different
     * card, not a smaller number" -- here, a different state).
     */
    private fun harvestOne(col: Int, row: Int, stats: Stats): Boolean {
        val ripe = onFrame { img ->
            if (Farm.plotState(img, col, row) != "ripe") false
            else if (Farm.bubbleOver(img, col, row)) {
                sleep(pauseLong)
                false
            } else true
        }
        if (!ripe) return false
        if (!pauseGate()) return false
        tap(Farm.plotTap(col, row), "ripe plot $col,$row")
        // The proof is looked for whatever the switch says since 2026-09-30:
        // a tap that went out harvested the plot, and a visit the switch
        // stops right after it books what it did (PLAN_RELEASE_1_3.md B4) --
        // the pass that goes on after the pause finds the plot empty and
        // counts nothing of it again. Looking taps nothing.
        for (i in 0 until beatRounds) {
            sleep(pauseBeat)
            val img = grab()
            try {
                val popup = Farm.waterPopup(img)
                if (popup != null) {
                    log("    plot $col,$row was still growing -- closing the popup, never Water")
                    tap(popup, "close water popup")
                    sleep(pauseLong)
                    return false
                }
                if (Farm.plotState(img, col, row) == "empty") {
                    stats.harvested += 1
                    remaining.remove(col to row)
                    return true
                }
                if (i == beatRounds - 1) {
                    log("    tapping the ripe plot $col,$row did not clear it")
                    dump(img, "harvest_no_proof_c${col}_r$row")
                }
            } finally {
                img.release()
            }
        }
        return false
    }

    /**
     * "planted", "empty" (left alone), "stop" (no seed this visit can put
     * in, end the planting), "retry" (an ad brought seeds and the menu is
     * gone: the same plot again) or "park" (an ad parked the visit).
     */
    private fun plantOne(col: Int, row: Int, stats: Stats): String {
        val first = grab()
        // Null where neither reading came back: farm.py plants anyway and
        // only skips the "did it fall by one" check afterwards, because the
        // count that mattered -- "is it zero" -- was answered above.
        val sack: Int?
        try {
            if (Farm.plotState(first, col, row) != "empty") return "empty"
            // free_seeds counts to zero, not a price, so a single confident
            // reading is already a complete answer -- but a plot is only
            // worth planting into if the count is not moving the wrong way
            // between two frames a beat apart, so both are still read.
            val seeds = sameTwice(pauseBeat) { onFrame { Farm.freeSeeds(it) } } ?: Farm.freeSeeds(first)
            if (seeds == 0 && !zeroHasAWay()) {
                log("    free seeds are at 0 -- ending this visit")
                return "stop"
            }
            if (Farm.bubbleOver(first, col, row)) {
                sleep(pauseLong)
                return "empty"
            }
            sack = seeds
        } finally {
            first.release()
        }
        if (!pauseGate()) return "empty"
        tap(Farm.plotTap(col, row), "empty plot $col,$row")
        // The menu at rest: its Select (or the violet ad in its place) where
        // the frame before had it ([waitSteady]); a water popup has no
        // button of the menu's and answers a place of its own.
        val opened = waitSteady { img ->
            Farm.seedMenu(img).let { (s, b) -> if (s != null && b != null) b.fx to b.fy else null }
                ?: Farm.adDialog(img)?.takeIf { it.kind == SEEDS }?.let { it.button.fx to it.button.fy }
                ?: Farm.waterPopup(img)?.let { -1.0 to -1.0 }
        }
        // The switch off after the tap: the menu stands where the pause found
        // it, and the visit that goes on closes it first ([resume]).
        if (opened == null && !on()) return "empty"
        if (opened == null) {
            log("    tapping the empty plot $col,$row opened nothing")
            onFrame { dump(it, "plant_no_menu_c${col}_r$row") }
            return "empty"
        }
        try {
            val popup = Farm.waterPopup(opened)
            if (popup != null) {
                log("    plot $col,$row turned out to be growing -- closing the popup, never Water")
                tap(popup, "close water popup")
                sleep(pauseLong)
                return "empty"
            }
            return inMenu(col, row, sack, opened, stats)
        } finally {
            opened.release()
        }
    }

    /**
     * Is there a way to fill an empty plot with the free seeds at 0: the
     * seeds' ad of the day, or a bought seed with the switch on? Without
     * one no plot is tapped, as before 2026-09-28.
     */
    private fun zeroHasAWay(): Boolean = adAsk(SEEDS) || (settings().betterSeeds && !betterOut)

    /**
     * The free ads of [kind] are taken ([Settings.ads]), the file has one left today,
     * and none of the kind failed its proof this visit ([adFailed]).
     */
    private fun adLeft(kind: String): Boolean = settings().ads && spent(kind) < DAY_ADS && kind !in adFailed

    /**
     * Whether the visit asks the game for an ad of [kind] where one is
     * offered: the page lets it, and this visit has not had the kind's proof
     * fail ([adFailed]) or read the game's own "0/2" ([adDone]). Not the file:
     * the offer's "n/2" is the count that decides (2026-10-02). On the Poco
     * on 2026-10-01 the file said both cans' ads were spent from 08:06 on,
     * and the field stood at 0 cans with the game's offer open until the
     * player took it by hand at 20:10 and 20:15; on LDPlayer on 2026-10-02
     * at 08:24 a frame of an ad read as the game's limit wrote the day off
     * while the can dialog still said 2/2.
     */
    private fun adAsk(kind: String): Boolean = settings().ads && kind !in adFailed && kind !in adDone

    /** The kinds whose ad brought no proof this visit: not tapped again before the next one. */
    private val adFailed = HashSet<String>()

    /** The kinds whose offer the game showed at 0 this visit. */
    private val adDone = HashSet<String>()

    /**
     * The seed menu is up: three slots and Select -- or, with the free slot
     * chosen and at 0, the violet "Ad (n/2)" in Select's place, which
     * [Farm.seedMenu] does not call a menu because it asks for the green
     * button (M1: ad_dialog_seeds_013423.png).
     */
    private fun menuOpen(img: Mat): Boolean =
        Farm.seedMenu(img).first != null || Farm.adDialog(img)?.kind == SEEDS

    /** The plain field: six plots and the X, nothing over them -- the director's [Director.FIELD]. */
    private fun bare(img: Mat): Boolean =
        Farm.meatFieldScreen(img) >= Director.FIELD_CELLS && Summon.exitButton(img) != null

    /**
     * The seed menu is open over plot ([col], [row]); [sack] is the header's
     * count read before the tap. Two witnesses for "the free seeds are out"
     * (PLAN_MEAT_FIELD_GIESSEN.md 4.4): the sack and the free slot's own
     * count (`seedSlotCount`, M1: "4", "1", "0" under it, 22 px, in the
     * header's font). Where the slot says a number the slot is believed --
     * it is the dialog's own, undimmed, and the one the tap acts on; where
     * it says nothing the sack decides, as before. A bought seed wants the
     * sack at 0 and a second witness ([plantAtZero]); the ad, which costs
     * nothing and which the game only offers at 0, wants the game's own
     * offer.
     */
    private fun inMenu(col: Int, row: Int, sack: Int?, menu: Mat, stats: Stats): String {
        val slots = Farm.seedSlots(menu)
        if (slots.size != 3) {
            log("    the seed menu's three slots were not found -- leaving it")
            closeMenu()
            return "stop"
        }
        val leftCount = Farm.seedSlotCount(menu, slots[0])
        val free = leftCount?.let { it > 0 } ?: (sack == null || sack > 0)
        if (free) return plantFree(col, row, leftCount ?: sack, sack, menu, slots, stats)
        return plantAtZero(col, row, sack, leftCount == 0, false, slots, stats)
    }

    /** The free seed into plot ([col], [row]), the menu open on [menu]; [sack] is the header's count. */
    private fun plantFree(col: Int, row: Int, before: Int?, sack: Int?, menu: Mat, slots: List<Explore.Blob>,
                          stats: Stats): String {
        val left = slots[0]
        // The menu keeps the last choice: after a Great seed it opens on
        // Great (seed_menu_kept_great_012940.png), so the free slot is
        // asked for every time, never assumed.
        if (!Farm.seedSelected(menu, left)) {
            // The seed menu stands in the middle (Farm.DIALOG), and so
            // do the fractions its readers answer in.
            tap(left.fx, left.fy, "free seed slot", Farm.DIALOG)
            sleep(pauseBeat)
        }
        // Select from a fresh frame, and only the green one with the free
        // slot chosen: with the free slot at 0 the same place holds the
        // violet "Ad (n/2)", and a button remembered from the frame before
        // the slot's tap would be that ad (M1, 5.2).
        val fresh = grab()
        val button: Explore.Blob
        try {
            val (s, b) = Farm.seedMenu(fresh)
            if (s == null || b == null || !Farm.seedSelected(fresh, s[0])) {
                if (Farm.adDialog(fresh)?.kind == SEEDS) {
                    log("    the free slot offers an ad -- the free seeds are out after all")
                    return plantAtZero(col, row, sack, false, true, slots, stats)
                }
                log("    the free slot shows no Select -- leaving the menu")
                closeMenu()
                return "stop"
            }
            button = b
        } finally {
            fresh.release()
        }
        if (lastKind != null && lastKind != 0) {
            log("free seeds are back (${before ?: "?"}) -- the free seed again")
        }
        tap(button.fx, button.fy, "Select", Farm.DIALOG)
        if (!plantedProof(col, row, "free")) return "empty"
        val after = onFrame { Farm.freeSeeds(it) }
        // One line a plot, so that a report says which plot took what and
        // which stayed empty (the Poco, 2026-10-02 17:54: "planted 9" of ten,
        // and no line said which; PLAN_ABSCHLUSS_1_3.md K6).
        log("    plot $col,$row: free seed, ${before ?: "?"} -> ${after ?: "?"}")
        if (after != null && before != null && after != before - 1) {
            log("    free-seed counter did not fall by 1 after planting " +
                    "($before -> $after) -- not trusting it for the rest of this visit")
        }
        lastKind = 0
        stats.planted += 1
        plantedCells.add(col to row)
        return "planted"
    }

    /**
     * The free seeds are at 0 and the menu is open. The day's seed ad
     * first, because it is free and brings three free seeds (M1: the Reward
     * sheet, the sack 0 -> 3); then, with the switch on and two witnesses
     * that the free seeds are out, a Good seed, then a Great one, each only
     * while its own count reads over 0 (PLAN_MEAT_FIELD_GIESSEN.md 4.4, 6.3:
     * a slot whose count does not read is not tapped). This is 6.1's second
     * and third steps in one place: M1 found the ad only where the count is
     * 0, on this menu, so the ad cannot come before the planting that
     * reaches it.
     *
     * The witnesses: the header's [sack] at 0, and beside it either the free
     * slot's own number at 0 ([slotZero]) or the game's own word -- with the
     * free slot chosen, the violet "Ad (n/2)" in Select's place, which the
     * game offers only at 0 ([offered], else [offersSeedAd] asks). On
     * LDPlayer at 900 x 1600 (2026-10-02 and 2026-10-03, PLAN_ABSCHLUSS_1_3.md
     * K10) the number under the free slot read 4 for its 0 while the header
     * read 0 and the ad stood, and the switch planted nothing; the ad is the
     * witness that does not hang on a digit 19 px tall.
     */
    private fun plantAtZero(col: Int, row: Int, sack: Int?, slotZero: Boolean, offered: Boolean,
                            slots: List<Explore.Blob>, stats: Stats): String {
        if (adAsk(SEEDS)) {
            when (seedAd(slots, stats)) {
                AdEnd.REWARD -> {
                    // The menu comes back with Select and the three seeds
                    // (seed_menu_after_ad_013504.png): planted here. Where
                    // the field came back instead, the same plot is tried
                    // again from the field.
                    val img = grab()
                    try {
                        if (Farm.seedMenu(img).first != null) {
                            val s = Farm.seedSlots(img)
                            val count = Farm.seedSlotCount(img, s[0])
                            // The header's 0 is the field's before the ad,
                            // and no witness of anything after it.
                            return plantFree(col, row, count, null, img, s, stats)
                        }
                        if (!bare(img)) closeMenu()
                    } finally {
                        img.release()
                    }
                    return "retry"
                }
                AdEnd.PARK -> return "park"
                AdEnd.NONE -> {}
            }
        }
        if (!settings().betterSeeds || betterOut) {
            closeMenu()
            return "stop"
        }
        // Never a bought seed while a free one is there: the sack first, and
        // the second witness asked only where the sack says 0.
        if (sack != 0 || !(slotZero || offered || offersSeedAd(slots))) {
            log("    the free seeds read 0 on one witness only -- no bought seed")
            closeMenu()
            return "stop"
        }
        val img = grab()
        try {
            val s = Farm.seedSlots(img)
            if (s.size == 3) {
                for (kind in 1..2) {
                    val count = Farm.seedSlotCount(img, s[kind])
                    val had = boughtBefore.remove(kind)
                    // The bought seed's "after" of K6: its bag is read only
                    // here, on the next menu.
                    if (had != null && count != null) {
                        log("    the ${SEED_NAMES[kind]} seeds " +
                                (if (count == had - 1) "fell by 1" else "did not fall by 1") + " ($had -> $count)")
                    }
                    if (count == null) {
                        log("    the ${SEED_NAMES[kind]} seeds' count did not read -- not tapping them")
                        continue
                    }
                    if (count == 0) continue
                    return plantBought(col, row, kind, count, img, s, stats)
                }
            }
        } finally {
            img.release()
        }
        log("    Good and Great seeds are out too")
        betterOut = true
        closeMenu()
        return "stop"
    }

    /**
     * The game's own word that the free seeds are out: with the free slot
     * chosen, the violet "Ad (n/2)" where Select stands otherwise -- offered
     * only at 0 (M1, ad_dialog_seeds_013423.png), whatever "n" says and
     * whether it reads. The free slot is chosen first where the menu opened
     * on another one (it keeps the last choice); that tap selects, it buys
     * nothing.
     */
    private fun offersSeedAd(slots: List<Explore.Blob>): Boolean {
        val left = slots[0]
        var img = grab()
        try {
            if (!Farm.seedSelected(img, left)) {
                img.release()
                tap(left.fx, left.fy, "free seed slot", Farm.DIALOG)
                sleep(pauseBeat)
                img = grab()
            }
            val offer = Farm.seedSelected(img, left) && Farm.adDialog(img)?.kind == SEEDS
            if (offer) log("    the free slot offers the seeds' ad -- the game's word that the free seeds are out")
            return offer
        } finally {
            img.release()
        }
    }

    /** A Good ([kind] 1) or Great (2) seed into plot ([col], [row]); [count] is its slot's number. */
    private fun plantBought(col: Int, row: Int, kind: Int, count: Int, menu: Mat,
                            slots: List<Explore.Blob>, stats: Stats): String {
        val slot = slots[kind]
        if (lastKind != kind) {
            log("free seeds at 0 -- planting ${SEED_NAMES[kind]} seeds ($count left)")
        }
        if (!Farm.seedSelected(menu, slot)) {
            tap(slot.fx, slot.fy, "${SEED_NAMES[kind]} seed slot", Farm.DIALOG)
            sleep(pauseLong)
        }
        val button = onFrame { img ->
            val (s, b) = Farm.seedMenu(img)
            if (s != null && b != null && Farm.seedSelected(img, s[kind])) b else null
        }
        if (button == null) {
            log("    the ${SEED_NAMES[kind]} slot shows no Select -- leaving the menu")
            closeMenu()
            return "stop"
        }
        tap(button.fx, button.fy, "Select", Farm.DIALOG)
        if (!plantedProof(col, row, SEED_NAMES[kind])) return "empty"
        // K6's line; the bag's "after" is the next menu's ([plantAtZero]).
        log("    plot $col,$row: ${SEED_NAMES[kind]} seed, $count before")
        lastKind = kind
        boughtBefore[kind] = count
        stats.planted += 1
        return "planted"
    }

    /**
     * The plot reads `growing` after Select, within [PLOT_ROUNDS]' time; the
     * frame kept where it does not. The plate is then the plot's time for
     * the watering ([remaining]) where a second frame a beat later reads it
     * 0 to 4 s less: live on LDPlayer on 2026-10-02 at 08:22:26 one frame of
     * a seed just planted, a grow of 01:59:5x, gave "00:00:09", and the
     * watering left the plot "not over 20 min" and ended with 12 cans.
     */
    private fun plantedProof(col: Int, row: Int, what: String): Boolean {
        for (i in 0 until beatRounds) {
            sleep(pauseBeat)
            val t1 = now()
            val img = grab()
            try {
                if (Farm.plotState(img, col, row) == "growing") {
                    val first = Farm.plotTimer(img, col, row)
                    sleep(pauseBeat)
                    val t2 = now()
                    val second = onFrame { Farm.plotTimer(it, col, row) }
                    if (first != null && second != null && runsDown(first, t1, second, t2)) {
                        remaining[col to row] = second to t2
                    }
                    return true
                }
                if (i == beatRounds - 1) {
                    log("    OK did not plant plot $col,$row ($what seed)")
                    dump(img, "plant_no_proof_c${col}_r$row")
                }
            } finally {
                img.release()
            }
        }
        return false
    }

    /**
     * The seed menu, the watering-can dialog, the water popup: shut by a tap
     * outside, at [Farm.BOOST_CLOSE], and the bare field waited for. The
     * spot was measured for the can dialog, where it closes the dialog and
     * where on the bare field it opens nothing (the forest over the pots,
     * 2026-09-28); the seed menu dims the same forest and is shut the same
     * way -- that one is M3's to see live.
     */
    private fun closeMenu(): Boolean {
        // Twice at most: a Reward sheet over the can dialog is shut by the
        // first tap and leaves the dialog standing (the Poco, 2026-10-01
        // 08:06:46: `dialog_not_closed` with the can dialog on it, and the
        // field parked under it for ten minutes). A second tap goes out only
        // on a dialog of the field that is still there.
        for (i in 0 until 2) {
            tap(Farm.BOOST_CLOSE[0], Farm.BOOST_CLOSE[1], "close the dialog", Farm.DIALOG)
            val field = waitBeat { bare(it) }
            if (field != null) {
                field.release()
                return true
            }
            val again = onFrame { img ->
                !bare(img) && (Dungeon.rewardSheet(img) || Farm.seedMenu(img).first != null ||
                    Farm.boostPopup(img) != null || Farm.adDialog(img) != null)
            }
            if (!again || !on()) break
        }
        log("    the dialog did not close")
        onFrame { dump(it, "dialog_not_closed") }
        return false
    }

    // ------------------------------------------------------------------------
    // The field's ads (PLAN_MEAT_FIELD_GIESSEN.md 4.6, 6.1 point 2)
    // ------------------------------------------------------------------------
    /** How an ad ended, for the step that reached it. */
    private enum class AdEnd { REWARD, NONE, PARK }

    /** Ads of a kind tapped this visit: a second count beside the file's, in case the file does not keep it. */
    private val tappedThisVisit = HashMap<String, Int>()

    /** The day's ads of [kind] spent, by the file or by this visit, whichever says more. */
    private fun spent(kind: String): Int = maxOf(adsUsed(kind), tappedThisVisit[kind] ?: 0)

    /**
     * The seeds' ad on the open seed menu. The violet "Ad (n/2)" stands in
     * Select's place with the free slot chosen and at 0
     * (ad_dialog_seeds_013423.png), so the free slot is chosen first where
     * the menu opened on another one -- it remembers the last choice.
     */
    private fun seedAd(slots: List<Explore.Blob>, stats: Stats): AdEnd {
        val left = slots[0]
        var img = grab()
        try {
            if (!Farm.seedSelected(img, left)) {
                img.release()
                tap(left.fx, left.fy, "free seed slot", Farm.DIALOG)
                sleep(pauseLong)
                img = grab()
            }
            return takeAd(SEEDS, Farm.adDialog(img)?.takeIf { it.kind == SEEDS }, stats)
        } finally {
            img.release()
        }
    }

    /**
     * One ad of [kind] off its [offer], with its proof (6.2): the Reward
     * sheet, or the kind's own count over 0 afterwards -- the free slot's
     * number on the menu, the can dialog's, or the header's on the bare
     * field. The game's "n/2" counts the ads left (M1, 5.2) and decides:
     * at 0 the kind is done for the day and nothing is tapped, and the
     * file is set to what the game says. A proof that does not come ends
     * the kind for the day as well (6.1): a reward the game did not give is
     * no reason to tap the same button again.
     *
     * Tapped only with the Ad Skip Pass taken for granted ([Settings.ads]).
     * Where an ad comes all the same -- the switch is on and the account has
     * no pass; the field asks no question first -- nothing is tapped in it
     * or on the field behind it, no back key is sent, and the visit parks
     * with [FreeAds.PARK] (2026-10-03, PLAN_ABSCHLUSS_1_3.md 3.6).
     */
    private fun takeAd(kind: String, offer: Farm.AdOffer?, stats: Stats): AdEnd {
        if (offer == null) {
            log("ads: no $kind ad on offer")
            return AdEnd.NONE
        }
        val left = offer.left
        if (left == null) {
            // The seed menu's "Ad (0/2)" draws its 0 in red, which does not
            // read (notes/farm.md): asked once a visit, not once a plot.
            log("ads: the $kind ad's count did not read -- not tapping it")
            adDone += kind
            return AdEnd.NONE
        }
        // The game's count decides, either way (2026-10-02, [adAsk]).
        val used = DAY_ADS - minOf(DAY_ADS, left)
        if (left <= 0) {
            setAdsUsed(kind, DAY_ADS)
            adDone += kind
            val said = if (kind == SEEDS) seedAdSaid else canAdSaid
            if (!said) log("ads: $kind $DAY_ADS/$DAY_ADS already today")
            if (kind == SEEDS) seedAdSaid = true else canAdSaid = true
            return AdEnd.NONE
        }
        if (adsUsed(kind) > used) {
            log("ads: the file had $kind at ${adsUsed(kind)}/$DAY_ADS, the game offers $left/$DAY_ADS -- the game's count is taken")
        }
        setAdsUsed(kind, used)
        tappedThisVisit[kind] = used + 1
        tap(offer.button, "the $kind ad")
        val settled = settle()
        if (settled.ad) {
            log("  an ad came where the Ad Skip Pass was taken for granted -- I never touch an ad")
            stats.adPark = FreeAds.PARK
            stats.reason = "an ad parked the visit"
            return AdEnd.PARK
        }
        val sheet = settled.sheet
        val limit = settled.limit
        if (limit) {
            setAdsUsed(kind, DAY_ADS)
            log("ads: the game says the day's $kind ads are used up")
            return AdEnd.NONE
        }
        val count = onFrame { img ->
            when {
                kind == SEEDS && menuOpen(img) -> Farm.seedSlots(img).firstOrNull()?.let { Farm.seedSlotCount(img, it) }
                kind == CANS && Farm.boostPopup(img) != null -> Farm.boostCans(img)
                bare(img) -> if (kind == SEEDS) Farm.freeSeeds(img) else Farm.waterCans(img)
                else -> null
            }
        }
        if (sheet || (count != null && count > 0)) {
            val n = minOf(DAY_ADS, used + 1)
            setAdsUsed(kind, n)
            stats.ads += 1
            log("ads: $kind $n/$DAY_ADS collected, ${if (kind == SEEDS) "sack" else "cans"} 0 -> ${count ?: "?"}")
            return AdEnd.REWARD
        }
        // A reward that did not show is one ad spent, not the day's: the
        // game's own "n/2" on the next offer says what is left, and this
        // visit asks no more of the kind ([adFailed]). Until 2026-10-02 it
        // was the day -- on the Poco on 2026-10-01 at 08:06:45 a reward that
        // had come (20 cans under a Reward sheet the proof was not waiting
        // for) wrote both cans' ads off, and the field stood at 0 cans with
        // the game's second ad still on offer until the player took it by hand.
        val n = minOf(DAY_ADS, used + 1)
        setAdsUsed(kind, n)
        adFailed += kind
        log("ads: the $kind did not come -- $n/$DAY_ADS spent, no more this visit")
        onFrame { dump(it, "ad_no_reward_$kind") }
        return AdEnd.NONE
    }

    /**
     * After an ad's tap, until what stands is a screen of the field again:
     * the Reward sheet closed by a tap (it is "Tap to close"; the tap goes to
     * [Farm.BOOST_CLOSE], which on the bare field opens nothing), the game's
     * "Ad viewing limit reached." answered with its OK. Whether each was
     * seen, and whether an ad stood in front instead.
     */
    private fun settle(): Settled {
        var sheet = false
        var limit = false
        // Not done while the offer the tap went to still stands: the reward
        // can come seconds later ([SETTLE_ROUNDS]), and the dialog that only
        // still shows "View Ads" or "Ad (n/2)" is not yet the dialog after it.
        for (i in 0 until SETTLE_ROUNDS) {
            sleep(pauseLong)
            // An ad in front is nothing to read and nothing to tap: [takeAd] parks on it.
            if (adInFront()) return Settled(sheet, limit, ad = true)
            val img = grab()
            try {
                if (Dungeon.rewardSheet(img)) {
                    sheet = true
                    tap(Farm.BOOST_CLOSE[0], Farm.BOOST_CLOSE[1], "close the Reward sheet", Farm.DIALOG)
                    continue
                }
                // The limit box is the game's answer to a tap where no ad
                // came and none is left today.
                val ok = Farm.adLimit(img)
                if (ok != null) {
                    limit = true
                    dump(img, "ad_limit")
                    tap(ok, "OK on the ad limit")
                    continue
                }
                if (bare(img)) break
                if ((menuOpen(img) || Farm.boostPopup(img) != null) && Farm.adDialog(img) == null) break
            } finally {
                img.release()
            }
        }
        return Settled(sheet, limit, ad = false)
    }

    /** What [settle] saw: the Reward sheet, the game's "Ad viewing limit reached.", an ad in front. */
    private data class Settled(val sheet: Boolean, val limit: Boolean, val ad: Boolean)

    // ------------------------------------------------------------------------
    // Watering (PLAN_MEAT_FIELD_GIESSEN.md 6.1 point 4)
    // ------------------------------------------------------------------------
    private enum class Pour { OK, NO_PROOF, SKIP, BRAKE, ZERO }

    /** One watering's end: [cans] as read after it, [ripe] where the plot ripened, [why] where no proof came. */
    private class Poured(val how: Pour, val cans: Int? = null, val ripe: Boolean = false,
                         val why: String = "")

    /**
     * Pour the free cans until they are at 0 (4.3), each time on the growing
     * plot that ripens first (4.2) -- which is harvested and planted again
     * the moment it ripens, so the round goes on -- and, where the page
     * lets it, the day's ad for cans once they are out.
     *
     * Since 2026-09-28 by the player's rule (PLAN_MEAT_FIELD_GIESSEN.md 13):
     * only a plot with more than `farm_water_min_minutes` left, and only
     * with exactly as many cans as ripen it, [cansToRipen] -- where the cans
     * in hand do not cover that, the plot is left to grow and the next one
     * is taken whose need they do cover ([pickWater]); the clock comes back
     * for it (row 10 of [nextVisit]). Cans may be left over at the end.
     *
     * The brakes (6.3): two waterings in a row without their proof end the
     * watering for this visit with the frame kept (`water_no_proof_*`), and
     * the clock books [FALLBACK]; a can dialog this skill cannot read is
     * closed with nothing poured and ends it too; a plot that will not open
     * its popup is left for the rest of the visit.
     */
    private fun water(stats: Stats) {
        var cans = readCans()
        if (cans == null) {
            log("the can counter could not be read -- no watering this visit")
            return
        }
        scan()
        val skip = HashSet<Pair<Int, Int>>()
        var misses = 0
        var usedUp = false
        while (true) {
            if (!pauseGate()) {
                stats.reason = "stopped"
                return
            }
            val have = cans ?: run {
                log("the can counter did not read after a watering -- watering ends for this visit")
                stats.waterStopped = true
                return
            }
            if (have == 0) {
                if (stats.watered > 0 && !usedUp) {
                    log("cans used up -- watering done")
                    usedUp = true
                }
                cans = cansAd(stats, skip) ?: return
                usedUp = false
                continue
            }
            // No plot the cans in hand ripen: with the cans' ad still to be
            // had today, the rest goes on the plot that ripens first, so that
            // the counter stands at 0 -- the only place the game offers the
            // ad (M1, 5.2). The player's 2026-10-01: "0 Kannen, aber es
            // könnten noch Kannen durch Ads bekommen werden, Bot ist einfach
            // nur idle" -- on the Poco that evening the field stood with 1 or
            // 2 cans and every plot dearer, from 20:08:51 to 20:10:50 and
            // again from 20:14:41, until the player watched the ad by hand.
            val ripens = pickWater(have, skip)
            val pick = ripens ?: (if (adLeft(CANS)) pickWater(null, skip) else null) ?: return
            val drain = ripens == null
            val cell = pick.cell
            val (col, row) = cell
            val poured = waterOne(col, row, have, pick.left, stats, drain)
            when (poured.how) {
                Pour.OK -> {
                    misses = 0
                    cans = poured.cans
                    if (!drain) shortOf.remove(cell)
                    if (poured.ripe) {
                        // Ripe at once: harvested and planted again, and the
                        // round goes on with the next plot (6.1).
                        harvestOne(col, row, stats)
                        if (!seedsOut && replant(col, row, stats) == "park") return
                    }
                }
                Pour.NO_PROOF -> {
                    misses += 1
                    cans = poured.cans
                    if (misses >= 2) {
                        log("watering plot $col,$row: ${poured.why}, twice -- watering ends for this visit")
                        onFrame { dump(it, "water_no_proof_c${col}_r$row") }
                        stats.waterStopped = true
                        return
                    }
                    log("watering plot $col,$row: ${poured.why}, once")
                }
                Pour.SKIP -> skip += cell
                Pour.BRAKE -> {
                    stats.waterStopped = true
                    return
                }
                Pour.ZERO -> cans = 0
            }
            if (stats.reason == "stopped" || stats.adPark != null) return
        }
    }

    /** The green can's count on the bare field, twice alike a beat apart, or null. */
    private fun readCans(): Int? {
        val first = onFrame { Farm.waterCans(it) } ?: return null
        sleep(pauseShort)
        val second = onFrame { Farm.waterCans(it) } ?: return null
        return if (first == second) second else null
    }

    /**
     * The field's times, read once at the start of the watering (N2, the
     * player's 2026-10-01: "Meat field watering soll immer die Felder mit den
     * niedrigsten Zeiten zuerst wässern; wenn die Zeiten der Felder gescannt
     * sind ... muss nicht der Timer nochmal überprüft werden"): every plate
     * on two frames a second apart, kept in [remaining] where the second is
     * 0 to 4 s under the first, as [readTwice] keeps one. From here the
     * order is that memory, run on by the clock -- and what a plant proof or
     * a popup reads after it -- not what each round's frame happens to read:
     * on the Poco on 2026-10-01 at 20:11:55 a plot of 01:52:53 was watered
     * with four cans while plots planted in the minute before stood beside
     * it, which the next waterings found at 57 and 58 minutes -- the frame
     * of that one round was the choice's only witness.
     */
    private fun scan() {
        fun plates(img: Mat): Map<Pair<Int, Int>, Int> {
            val out = HashMap<Pair<Int, Int>, Int>()
            for (row in 0 until 3) for (col in 0 until 2) {
                if (Farm.plotBadgeKind(img, col, row) != null) continue
                Farm.plotTimer(img, col, row)?.let { out[col to row] = it }
            }
            return out
        }
        val t1 = now()
        val first = onFrame { plates(it) }
        sleep(1.0)
        val t = now()
        val second = onFrame { plates(it) }
        for ((cell, b) in second) {
            val a = first[cell] ?: continue
            if (runsDown(a, t1, b, t)) remaining[cell] = b to t
        }
        val known = remaining.entries
            .map { (cell, v) -> cell to v.first - (t - v.second).toInt() }
            .sortedBy { it.second }
        if (known.isNotEmpty()) {
            log("watering: the times, shortest first -- " +
                    known.joinToString(", ") { (c, s) -> "${c.first},${c.second} ${hms(s)}" })
        }
    }

    /**
     * Two readings of one countdown, [a] at [ta] and [b] at [tb] (epoch
     * seconds), are the same plate running down: it fell by the time between
     * them, within [COUNT_TOL]. Until 2026-10-02 the window was 0 to 4 s
     * whatever the time between, which a frame of a second or more on
     * LDPlayer (09:00, "screen: field ... 2581 ms") overran: two plates that
     * read on the still frame (03:57:54 and 03:42:45) went unkept, and the
     * cans' ad "waited" for want of a plot.
     */
    private fun runsDown(a: Int, ta: Double, b: Int, tb: Double): Boolean =
        abs((a - b) - (tb - ta)) <= COUNT_TOL

    /** What [remaining] says plot [cell] has left now, seconds, or null where nothing was read. */
    private fun known(cell: Pair<Int, Int>): Int? = remaining[cell]?.let { (s, at) -> s - (now() - at).toInt() }

    /** A plot [pickWater] chose, and the time it is thought to have left, seconds, or null where none read. */
    private class Pick(val cell: Pair<Int, Int>, val left: Int?)

    /**
     * The growing plot that ripens first (4.2), none of [skip], among the
     * ones the rule of 13 lets be watered: the time kept this visit ([scan],
     * a plant proof, a popup), run on by the clock. A plot with a badge on
     * this frame is not a candidate, and nor is one nobody kept a time for:
     * a cell nothing can be read on may be a locked plot, and a locked plot
     * is never tapped.
     *
     * The threshold of 13 (the player's, 2026-09-28): a plot with no more
     * than `farm_water_min_minutes` left is not a candidate, and with
     * [cans] given neither is one whose need, [cansToRipen], they do not
     * cover -- the next one is taken whose need they do, the shortest
     * first. Neither costs a tap: the plot is passed over on what the frame
     * says, and the line is said once a visit. [cans] null asks only for
     * the threshold (the cans' ad, which brings twenty).
     */
    private fun pickWater(cans: Int?, skip: Set<Pair<Int, Int>>): Pick? {
        val min = minSeconds()
        return onFrame { img ->
            val found = ArrayList<Pick>()
            for (row in 0 until 3) {
                for (col in 0 until 2) {
                    val cell = col to row
                    if (cell in skip) continue
                    // A badge says ripe or empty: no longer what was read.
                    if (Farm.plotBadgeKind(img, col, row) != null) {
                        remaining.remove(cell)
                        continue
                    }
                    // The time [scan], the plant proofs and the popups kept,
                    // each on two readings, run on -- never one frame's
                    // plate. A cell nothing kept is never a candidate: it may
                    // be a locked plot, or one whose plate does not read.
                    val left = known(cell) ?: continue
                    if (left <= min) {
                        leftUnder(cell, left)
                        continue
                    }
                    if (cans != null && cansToRipen(left) > cans) {
                        leftShort(cell, left, cansToRipen(left), cans)
                        continue
                    }
                    found += Pick(cell, left)
                }
            }
            found.sortedWith(compareBy(nullsLast()) { it.left }).firstOrNull()
        }
    }

    /**
     * Tap a growing plot -- no badge on it, and a time kept for it or its
     * plate read -- with no bubble over the tap point, and wait for its water
     * popup at rest. Its "Time Remaining" off the frame that confirmed it,
     * and that alone where it stands within [WATER_TOL] of the time kept
     * ([scan]): the two are two witnesses, and the second read a second
     * later that cost every pour until 2026-10-02 is asked only where they
     * disagree or nothing was kept. Null where the plot does not read
     * growing or no popup came.
     */
    private fun openPopup(col: Int, row: Int): Opened? {
        val cell = col to row
        val ok = onFrame { img ->
            Farm.plotBadgeKind(img, col, row) == null &&
                (known(cell) != null || Farm.plotTimer(img, col, row) != null) &&
                !Farm.bubbleOver(img, col, row)
        }
        if (!ok) return null
        tap(Farm.plotTap(col, row), "growing plot $col,$row")
        // The popup's own Water where the frame before had it; a popup whose
        // Water does not read answers a place of its own, and is shut below.
        val frame = waitSteady { img ->
            if (menuOpen(img)) -1.0 to -1.0
            else Farm.waterPopup(img)?.let { Farm.waterButton(img)?.let { b -> b.fx to b.fy } ?: (-2.0 to -2.0) }
        }
        if (frame == null) {
            log("    tapping the growing plot $col,$row opened nothing")
            onFrame { dump(it, "water_no_popup_c${col}_r$row") }
            return null
        }
        val quick: Int?
        val water: Explore.Blob?
        try {
            if (Farm.waterPopup(frame) == null) {
                log("    plot $col,$row opened the seed menu -- closing it")
                closeMenu()
                return null
            }
            quick = Farm.popupTimer(frame)
            water = Farm.waterButton(frame)
        } finally {
            frame.release()
        }
        val kept = known(cell)
        val agreed = quick != null && kept != null && abs(quick - kept) <= WATER_TOL
        log("    plot $col,$row: its popup at rest on frame $steadyFrames, " +
                if (agreed) "its time as kept" else "its time read twice (${quick ?: "?"} against ${kept ?: "?"})")
        val time = if (agreed) quick else readTwice(1.0) { onFrame { Farm.popupTimer(it) } }
        if (time != null) remaining[cell] = time to now()
        return Opened(time, water)
    }

    /**
     * A water popup that came up at rest: [remaining] its time left, seconds,
     * or null where it did not read; [water] its Water button off that frame.
     */
    private class Opened(val remaining: Int?, val water: Explore.Blob?)

    /** The water popup shut by its own close target, well clear of it, and the bare field waited for. */
    private fun closePopup() {
        val target = onFrame { Farm.waterPopup(it) }
        if (target != null) tap(target, "close water popup")
        val field = waitBeat { bare(it) }
        if (field == null) onFrame { dump(it, "popup_not_closed") } else field.release()
    }

    /**
     * One watering of plot ([col], [row]) with [cans] in hand: the popup,
     * Water, the can dialog, and Water with the number the dialog's box
     * opened at -- the fewest cans that ripen the plot, capped by the cans
     * there are (M1, 5.2), spent at one tap, which closes everything. The
     * Huge can, MIN, -, + and MAX are never touched (4.1).
     *
     * The proof (6.2), on the bare field: the header's count fell by exactly
     * that number, read twice alike; and the plot is ripe where its time was
     * no more than those cans take off, or its popup's time fell by 30:00 a
     * can, [WATER_TOL] either way. The header is dimmed to grey 148 under
     * every dialog and reads nothing there (5.2), which is why both are read
     * on the bare field. A time that could not be read on either side leaves
     * the counter as the one witness, and the log says so.
     *
     * [drain]: the cans in hand ripen no plot, and the cans' ad is still to
     * be had today -- all of them go on this plot, the box at the stock's
     * cap, so that the counter stands at 0 where the game offers the ad
     * ([water]). The proof is the same, with the plot still growing.
     */
    private fun waterOne(col: Int, row: Int, cans: Int, estimate: Int?, stats: Stats,
                         drain: Boolean = false): Poured {
        val cell = col to row
        val opened = openPopup(col, row) ?: return Poured(Pour.SKIP)
        // The rule of 13 again, on the popup's own time where it read and
        // the plate's where it did not: never a can on a plot whose need
        // nobody knows, nor on one at or under the threshold, nor with
        // fewer cans than ripen it -- unless they are the last ones, poured
        // to open the cans' ad ([drain]).
        val before = opened.remaining ?: estimate
        val readAt = remaining[cell]?.second ?: now()
        if (before == null) {
            sayOnce("watering plot $col,$row: the time left did not read -- left to grow")
            closePopup()
            return Poured(Pour.SKIP)
        }
        if (before <= minSeconds()) {
            leftUnder(cell, before)
            closePopup()
            return Poured(Pour.SKIP)
        }
        if (cansToRipen(before) > cans && !drain) {
            leftShort(cell, before, cansToRipen(before), cans)
            closePopup()
            return Poured(Pour.SKIP)
        }
        val water = opened.water
        if (water == null) {
            log("    the popup of plot $col,$row shows no Water button -- closing it")
            closePopup()
            return Poured(Pour.SKIP)
        }
        if (drain) {
            log("watering plot $col,$row: needs ${cansToRipen(before)} cans, $cans in stock -- " +
                    "pouring them to open the cans' ad")
        }
        tap(water.fx, water.fy, "Water", Farm.DIALOG)
        val dialog = waitSteady { img -> Farm.boostPopup(img)?.button?.let { it.fx to it.fy } }
        if (dialog == null) {
            // Not the can dialog M1 measured -- a dialog of the Huge can
            // alone, a price, anything: nothing on it is tapped (6.3).
            log("    Water opened no can dialog this app knows -- closing it, nothing tapped")
            onFrame { dump(it, "boost_unknown_c${col}_r$row") }
            closeMenu()
            return Poured(Pour.BRAKE)
        }
        log("    the can dialog at rest on frame $steadyFrames")
        var boost: Farm.Boost
        var amount: Int?
        var inDialog: Int?
        val offer: Farm.AdOffer?
        var tinyChosen: Boolean
        try {
            boost = Farm.boostPopup(dialog)!!
            amount = Farm.boostAmount(dialog)
            inDialog = Farm.boostCans(dialog)
            offer = Farm.adDialog(dialog)
            tinyChosen = tinyChosen(dialog, boost)
        } finally {
            dialog.release()
        }
        if (offer == null && !tinyChosen) {
            // The player's rule, 2026-09-28: Water is never pressed with the
            // Huge can chosen. Every can dialog M1 and M3 saw opened on the
            // Tiny can, and whether the dialog keeps the last choice as the
            // seed menu does is not known -- choosing the Huge can to find
            // out is not allowed -- so the choice is read before every Water
            // ([tinyChosen]) and the Tiny can tapped where it is not chosen.
            log("    the can dialog does not have the Tiny can chosen -- choosing it")
            tap(boost.tiny, "the Tiny can")
            sleep(pauseBeat)
            val again = grab()
            try {
                val b = Farm.boostPopup(again)
                tinyChosen = b != null && tinyChosen(again, b)
                if (b != null) {
                    boost = b
                    amount = Farm.boostAmount(again)
                    inDialog = Farm.boostCans(again)
                }
            } finally {
                again.release()
            }
            if (!tinyChosen) {
                log("    the Tiny can is still not chosen -- closing the dialog, nothing poured")
                onFrame { dump(it, "boost_not_tiny_c${col}_r$row") }
                closeMenu()
                return Poured(Pour.BRAKE)
            }
        }
        if (offer != null) {
            log("    the can dialog offers an ad -- the cans are at 0 (the header read $cans)")
            closeMenu()
            return Poured(Pour.ZERO, 0)
        }
        val have = inDialog ?: cans
        if (amount == null || amount < 1 || amount > have) {
            log("    the can dialog's number did not read (${amount ?: "?"} of $have) -- " +
                    "closing it, nothing poured")
            onFrame { dump(it, "boost_unread_c${col}_r$row") }
            closeMenu()
            return Poured(Pour.BRAKE)
        }
        if (inDialog != null && inDialog != cans) {
            log("    the can dialog says $inDialog cans, the header said $cans -- the dialog's is taken")
        }
        // The box opens at the fewest cans that ripen the plot, capped by
        // the cans there are (M1, 5.2): a number under the plot's need is the
        // cap, and a pour of it would leave the timer running -- which the
        // player's rule of 13 forbids. The need is taken as the time stands
        // now, the popup's time less the seconds since it was read, so that
        // a plot that has just crossed a half hour on its own is not refused
        // for the can it no longer needs.
        val needNow = cansToRipen(maxOf(1, before - (now() - readAt).toInt()))
        if (drain && amount != have) {
            // The box should stand at the cap, every can there is; anything
            // else is a time or a count this visit has wrong.
            log("    the can dialog asks $amount of $have cans -- not the rest, closing it")
            closeMenu()
            return Poured(Pour.SKIP)
        }
        if (amount < needNow && !drain) {
            leftShort(cell, before, needNow, inDialog ?: amount)
            closeMenu()
            return Poured(Pour.SKIP)
        }
        if (amount > needNow) {
            log("    the can dialog asks $amount cans where the time says $needNow -- the dialog's " +
                    "number is poured, it ripens the plot")
        }
        tap(boost.button, "Water with $amount can${if (amount == 1) "" else "s"}")
        // A plot watered is no longer a full grow: planted this visit or
        // not, its plate now carries a remainder, and the grow duration is
        // not learned from it (6.4).
        plantedCells.remove(col to row)
        // What the cans took off, kept until a popup says better below.
        remaining[cell] = (before - amount * CAN_SECONDS) to readAt
        val field = waitBeat { bare(it) }
        if (field == null) {
            log("    the field did not come back after Water")
            onFrame { dump(it, "water_no_field_c${col}_r$row") }
            return Poured(Pour.BRAKE)
        }
        field.release()
        val after = readCans()
        val state = onFrame { Farm.plotState(it, col, row) }
        val ripe = state == "ripe"
        var afterTime: Int? = null
        if (state == "growing") {
            val again = openPopup(col, row)
            if (again != null) {
                afterTime = again.remaining
                closePopup()
            }
        } else {
            remaining.remove(col to row)
        }
        val timeOk: Boolean? = when {
            ripe -> before <= amount * CAN_SECONDS + WATER_TOL
            afterTime != null -> abs(before - amount * CAN_SECONDS - afterTime) <= WATER_TOL
            else -> null
        }
        val fell = after != null && after == have - amount
        val times = "${hms(before)} -> " +
            (if (ripe) "ripe" else afterTime?.let { hms(it) } ?: "?")
        if (fell && timeOk != false) {
            log("watering plot $col,$row: $times, cans $have -> $after" +
                    if (timeOk == null) " (the time did not read; the counter is the proof)" else "")
            stats.watered += amount
            return Poured(Pour.OK, after, ripe)
        }
        val why = if (!fell) "the counter did not fall ($have -> ${after ?: "?"})"
                  else "the time did not fall by ${amount * CAN_SECONDS / 60} min ($times)"
        return Poured(Pour.NO_PROOF, after ?: have, why = why)
    }

    /**
     * Does the can dialog have the Tiny can chosen? The gold bracket the
     * seed menu marks its choice with stands around the chosen can too, and
     * [Farm.seedSelected] reads it there: true on the Tiny slot and false on
     * the Huge one on all ten can dialogs of the corpus (1080 x 1920, 2340
     * and 2520, with cans and at 0), the dialog opening on the Tiny can
     * every time M1 and M3 opened it (2026-09-28).
     */
    private fun tinyChosen(img: Mat, boost: Farm.Boost): Boolean =
        Farm.seedSelected(img, Explore.Blob(boost.tiny.fx, boost.tiny.fy, 0.0), boost.tiny.anchor)

    /**
     * The day's ad for cans (4.6), with the cans at 0: it stands where the
     * can would, in the can dialog Water opens, which shows "n/2" on the Tiny
     * slot and "View Ads" on its button at 0 cans
     * (ad_dialog_cans_013607.png) -- so a growing plot is what reaches it,
     * and Water is pressed there only on a plot that reads growing. The
     * cans that came, or null where none did.
     */
    private fun cansAd(stats: Stats, skip: Set<Pair<Int, Int>>): Int? {
        if (!adAsk(CANS)) return null
        // Only by way of a plot the cans could then be poured on: over the
        // threshold of 13, on the popup's time where it reads -- the Water
        // that opens the dialog is never pressed on a plot under it.
        val pick = pickWater(null, skip)
        if (pick == null) {
            sayOnce("ads: cans -- no plot has more than ${settings().waterMinMinutes} min left, the ad waits")
            return null
        }
        val cell = pick.cell
        val opened = openPopup(cell.first, cell.second) ?: return null
        val left = opened.remaining ?: pick.left
        if (left == null || left <= minSeconds()) {
            if (left != null) leftUnder(cell, left)
            closePopup()
            return null
        }
        val water = opened.water
        if (water == null) {
            closePopup()
            return null
        }
        tap(water.fx, water.fy, "Water, for the cans' ad", Farm.DIALOG)
        val dialog = waitSteady { img -> Farm.boostPopup(img)?.button?.let { it.fx to it.fy } }
        if (dialog == null) {
            log("    Water opened no can dialog this app knows -- closing it, nothing tapped")
            onFrame { dump(it, "boost_unknown_c${cell.first}_r${cell.second}") }
            closeMenu()
            return null
        }
        val offer = try { Farm.adDialog(dialog)?.takeIf { it.kind == CANS } } finally { dialog.release() }
        if (offer == null) {
            // Cans in the dialog after all: the header read 0 wrongly. Not
            // poured here -- the header is what the watering is proved by,
            // and it has just been wrong.
            log("    the can dialog shows cans, not an ad -- leaving it")
            closeMenu()
            return null
        }
        val end = takeAd(CANS, offer, stats)
        if (end == AdEnd.PARK) return null
        // The dialog may stand on with the new cans in it; it is shut, and
        // the cans read on the bare field, where the watering proves them.
        val up = onFrame { !bare(it) }
        if (up) closeMenu()
        if (end != AdEnd.REWARD) return null
        return readCans()?.takeIf { it > 0 }
    }

    /** [plantOne] for a plot that has just been harvested, once more if an ad brought the seeds. */
    private fun replant(col: Int, row: Int, stats: Stats): String {
        var r = plantOne(col, row, stats)
        if (r == "retry") r = plantOne(col, row, stats)
        if (r == "stop") seedsOut = true
        return r
    }

    /** No seed this visit can plant any more: the harvest the watering brings is not planted again. */
    private var seedsOut = false

    /** 01:26:34 -- the popup's own way of writing a time, for the log. */
    private fun hms(s: Int): String = String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)

    // ------------------------------------------------------------------------
    /**
     * Tap the X, then the globe on the Explore menu it lands on. True once
     * the main screen is back.
     *
     * It runs on the way out whatever else happened, the same shape as
     * explore.Nav.leave_board -- and, like it since 2026-09-30, it asks the
     * switch first: with it off the game stays where the pause found it
     * ([Stays]; PLAN_WORLD_SEARCH_FORMATE.md F24).
     */
    /**
     * The chain's second hand (Skill.leave): [leaveField] from a field the
     * director has classified as one -- six plots and the white X -- which
     * is the "found open" [fieldTapped] asks for.
     */
    override fun leave(): Boolean {
        fieldTapped = true
        return leaveField()
    }

    fun leaveField(): Boolean {
        var taps = 0
        for (i in 0 until EXIT_ROUNDS) {
            // With the switch off the game stays where it is ([Stays], F24).
            if (stays.now()) return false
            val img = grab()
            try {
                // One of the game's own windows is the director's ([Stays.over], B60).
                if (stays.over(img)) return false
                if (Dungeon.autoButton(img) != null) return true
                if (Dungeon.homeButton(img) != null) {
                    // The X has landed: closing the field goes back to the
                    // Explore menu, not home, and nothing on that menu is an
                    // X. Without this branch the loop sat out every remaining
                    // round there before reaching goHome below -- half a
                    // minute of the player watching the Explore menu.
                    return goHome()
                }
                if (taps < EXIT_TAPS_MAX && fieldTapped) {
                    // Only a field this visit opened (or found open) has an X
                    // worth tapping. leaveField runs in run's finally, so it
                    // also runs after goToField refused a screen it did not
                    // know -- and on 2026-09-19 that screen was a minigame the
                    // player was in, where the white plate matched something
                    // at (0.793, 0.957) and was tapped twice. The globe route
                    // above and goHome below stay open either way: both tap
                    // only a globe they have found.
                    //
                    // The same white X Quest.closeX already reuses:
                    // Summon.exitButton's fx/fy match the Meat Field's own X
                    // to the pixel, the same reused artwork (PLAN_MEAT_FIELD
                    // 2.2).
                    val x = Summon.exitButton(img)?.let { Explore.Target(it.fx, it.fy) }
                        ?: Explore.closeButton(img)?.let { Explore.Target(it.fx, it.fy, it.anchor) }
                    if (x != null) {
                        tap(x, "X to close the Meat Field")
                        taps += 1
                        sleep(pauseLong)
                        continue
                    }
                }
                sleep(pauseLong)
            } finally {
                img.release()
            }
        }
        if (goHome()) return true
        if (stays.now() || stays.met != null) return false
        log("could not get back to the main screen -- the game is left where it stands")
        onFrame { dump(it, "no_way_home") }
        return false
    }

    /**
     * dungeon.go_home (dungeon.py:2008) for the one way home this skill has:
     * press the globe until the auto button is back.
     *
     * Two things it deliberately does not do, both dungeon.py's own reasons.
     * It is bounded -- and since 2026-09-30 gated on the main switch after
     * all ([Stays]): the switch is one of the ways a visit ends, and where the
     * game is then left is where the pause found it (F24). And it never taps a position -- the nav bar is drawn
     * only on the list side of the game, so if the globe is not on the frame
     * the answer is not to tap where it would have been.
     *
     * What it checks after pressing is the auto button, not "the globe is
     * gone": independent evidence that the main screen is in front and clear.
     *
     * dungeon.go_home's third branch, `return_to_list` for a frame with no nav
     * bar at all, is not here: that is DungeonBot's own way out of a dungeon
     * panel, it presses the back key, and this skill presses no back key and
     * reaches goHome only from a frame whose globe it has just read.
     */
    private fun goHome(rounds: Int = HOME_ROUNDS): Boolean {
        var pressed = 0
        for (i in 0 until rounds) {
            if (stays.now()) return false
            val img = grab()
            try {
                if (stays.over(img)) return false
                if (Dungeon.autoButton(img) != null) {
                    if (pressed > 0) log("back on the main screen")
                    return true
                }
                val button = Dungeon.homeButton(img)
                if (button == null) {
                    sleep(pauseLong)
                    continue
                }
                if (pressed >= HOME_PRESSES_MAX) break
                if (pressed == 0) log("pressing the home button")
                tap(button.fx, button.fy, "home button")
                pressed += 1
                sleep(pauseLong)
            } finally {
                img.release()
            }
        }
        return false
    }

    // ------------------------------------------------------------------------
    /**
     * visit's body, from the line after go_to_field answered True: harvest
     * every ripe plot, plant every empty one, water, then read what is left
     * standing and what the header says.
     *
     * The order is PLAN_MEAT_FIELD_GIESSEN.md 6.1's, with one change M1's
     * measurement made: the ads are not a step of their own before the
     * planting, because the game offers one only where its count is at 0 --
     * the seeds' on the seed menu with the free slot at 0, the cans' in the
     * can dialog at 0 cans (5.2). So the seeds' ad is taken inside the
     * planting, the moment an empty plot meets a sack at 0, and before any
     * bought seed; the cans' inside the watering, the moment the cans run
     * out with a plot still growing. Both are still used in the visit that
     * brings them, which is what the order was for.
     */
    private fun body(stats: Stats) {
        for (row in 0 until 3) {
            for (col in 0 until 2) {
                if (!pauseGate()) {
                    stats.reason = "stopped"
                    return
                }
                harvestOne(col, row, stats)
            }
        }
        planting@ for (row in 0 until 3) {
            for (col in 0 until 2) {
                if (!pauseGate()) {
                    stats.reason = "stopped"
                    return
                }
                var planted = plantOne(col, row, stats)
                // An ad brought seeds and the field came back without the
                // menu: the same plot once more.
                if (planted == "retry") planted = plantOne(col, row, stats)
                if (planted == "park" || stats.reason == "stopped") return
                if (planted == "stop") {
                    seedsOut = true
                    if (stats.reason.isEmpty()) stats.reason = "free seeds used up"
                    break@planting
                }
            }
        }
        if (!pauseGate()) {
            stats.reason = "stopped"
            return
        }
        water(stats)
        if (stats.reason == "stopped" || stats.adPark != null) return
        val img = grab()
        try {
            for (row in 0 until 3) {
                for (col in 0 until 2) {
                    val state = Farm.plotState(img, col, row)
                    if (state == "empty") stats.emptyLeft += 1
                    if (state == "growing") {
                        stats.growing += 1
                        // A plot left for want of cans (13), with the time it
                        // has left now: the clock's row 10.
                        shortOf[col to row]?.let { stats.wanting += maxOf(1, (it - now()).toInt()) }
                        // The plate, and where it does not read the time a
                        // popup gave this visit, run on since (6.1 point 5).
                        val t = readTwice { onFrame { Farm.plotTimer(it, col, row) } }
                            ?: remaining[col to row]?.let { (s, at) -> s - (now() - at).toInt() }
                                ?.takeIf { it > 0 }
                        if (t != null) {
                            stats.timers.add(t)
                            if ((col to row) in plantedCells && stats.growSeconds == null) {
                                // Only a plot THIS visit planted carries a
                                // full grow. The test used to be "anything
                                // was planted", so the first growing plot of
                                // the closing round supplied it -- and a plot
                                // that was already growing when the visit
                                // began carries a remainder. Measured over
                                // the 15 corpus field frames with two or more
                                // readable timers (_grow_probe.py), the first
                                // growing plot reads 0.36 to 1.00 of the
                                // longest timer on the same field, 11 of them
                                // below 1.00. The cost was not a visit too
                                // early: a number taken out of `timers` is by
                                // construction one of the values being capped,
                                // so min(capped) stays min(timers) and the cap
                                // could never fire at all -- a field of timers
                                // all misread too large would have been left
                                // for a day, which is the one thing the cap
                                // was written to prevent.
                                stats.growSeconds = t
                            }
                        }
                    }
                }
            }
            stats.freeSeeds = Farm.freeSeeds(img)
            // The refill timer is only drawn while the count sits under its
            // cap of 20; above it there is nothing to wait for anyway.
            if (stats.freeSeeds != null && stats.freeSeeds!! < 20) {
                stats.refillIn = readTwice { onFrame { Farm.seedRefill(it) } }
            }
        } finally {
            img.release()
        }
        // The cans and their countdown, with the dot off their row
        // ([clearHeader]): the clock's rows 7 to 9 (nextVisit). The
        // countdown runs while the cans are under their cap, and at 0 too
        // (field_cans0_nobubble_013707.png: 0 to 1 in 32:22).
        stats.cans = readCans()
        stats.canRefillIn = readTwice { onFrame { Farm.canRefill(it) } }
        if (stats.reason.isEmpty()) stats.reason = "done"
    }

    /**
     * The dot and its plate off the header's can row for the visit
     * (`Capture.overlayClear`), as PresetSkill does off the Digivice bar and
     * World Search off its counters. Measured by M1 (PLAN_MEAT_FIELD_GIESSEN.md
     * 5.5) with the dot and plate the app draws: with the dot at fy 0.110 to
     * 0.135 of the player's strip `waterCans` reads wrong numbers (7, 2, 3, 5
     * for 4 or 0) or nothing, at 0.105 to 0.125 on 2520; `canRefill` reads
     * nothing at 0.130 to 0.155, the default place 0.1555 among them on one of
     * two fields. The rows are the can's count to the bottom of its
     * countdown, WATER_CANS_BAND to CAN_REFILL_BAND, 0.108 to 0.160 at the
     * header's anchor (Farm.HEADER, the top), in the HUD's fractions the
     * overlay speaks (Dungeon.bottomFy). Where the dot goes is Dot.clearFy.
     */
    private fun clearHeader() {
        val r = rect ?: return
        cap.overlayClear(Dungeon.bottomFy(Farm.WATER_CANS_BAND[0], Farm.HEADER, room, r.gh),
                         Dungeon.bottomFy(Farm.CAN_REFILL_BAND[1], Farm.HEADER, room, r.gh))
        cleared = true
        // The overlay moves on the main thread; the next frame is the one
        // the dot has left.
        sleep(pauseShort)
    }

    /** The dot back at the player's place, however the visit ended. */
    private fun headerBack() {
        if (cleared) cap.overlayBack()
        cleared = false
    }

    /**
     * The clock of this skill, after every visit: app.py's own three lines
     * (app.py:3861) -- next_visit on what the visit found, then
     * `farm_due_at`, `farm_grow_seconds` and `farm_last_reason` saved, with
     * [knownGrowSeconds] standing in for the cap where this visit planted
     * nothing.
     */
    private fun setClock(stats: Stats) {
        // The cap: what this visit planted, else what an earlier one learned.
        // `remember` still gets only [Stats.growSeconds], so a remembered
        // number is never written back over itself -- app.py's own
        // `if stats.get("grow_seconds")`.
        val cap = stats.growSeconds ?: knownGrowSeconds()
        val visit = nextVisit(now(), stats.timers, cap, stats.emptyLeft,
                              stats.freeSeeds, stats.refillIn, stats.growing,
                              stats.cans, stats.canRefillIn, stats.waterStopped,
                              stats.wanting, minSeconds())
        remember(visit.at, stats.growSeconds, visit.why)
        tally(stats)
        log("  back in ${((visit.at - now()) / 60).toInt()} min: ${visit.why}")
    }

    /** What a visit, or its part since a pause, did -- the log's witness of the TODAY card's numbers. */
    private fun tally(stats: Stats) {
        log("harvested ${stats.harvested}, planted ${stats.planted}, watered ${stats.watered}, " +
                "ads ${stats.ads} -- ${stats.reason}")
    }

    /** What the last visit found; null before the first. The TODAY card reads it. */
    var lastVisit: Stats? = null
        private set

    /**
     * What this visit counted, for the TODAY card ([SkillStats], [Counted]):
     * `watered` is cans poured and proved, `ads` the ads whose reward came.
     * [lastVisit] is set at the top of every pass ([begin]), so this is the
     * pass's own count and nothing of the one before it.
     */
    val lastCounts: Map<String, Int>
        get() = lastVisit?.let {
            mapOf("harvested" to it.harvested, "planted" to it.planted,
                  "watered" to it.watered, "ads" to it.ads)
        } ?: emptyMap()

    private fun outcomeOf(stats: Stats): Outcome {
        lastVisit = stats
        return outcomeFor(stats).also { carried = it.result == Result.STOPPED }
    }

    // ------------------------------------------------------------------------
    // After a pause (PLAN_RELEASE_1_3.md B4, the player's rule of 2026-09-30)
    // ------------------------------------------------------------------------
    /** The last visit was stopped by the main switch and has not been gone on with or begun afresh since. */
    internal var carried = false
        private set

    /** Every way home asks the switch first ([Stays]). */
    private val stays = Stays(on) { log(it) }

    /**
     * The field, and a dialog of the field over it -- the seed menu, the
     * water popup, the can dialog, the ad offers (Director.FIELD_DIALOG) --
     * where a visit the switch stopped goes on. The field is its own witness:
     * a plot harvested, planted or watered before the pause reads as such,
     * so the visit that goes on works what the field shows, as every visit
     * does, and nothing twice.
     */
    override fun resumesOn(screen: String, img: Mat): Boolean =
        carried && (screen == Director.FIELD || screen == Director.FIELD_DIALOG)

    /**
     * The visit the switch stopped, gone on with: a dialog of the field
     * closed the way the visit closes its own, then the visit's body again
     * on what the field shows -- with the ads it tapped and the seeds it
     * bought kept ([tappedThisVisit], [boughtBefore]), so that no ad is
     * asked for twice and no seed bought twice -- and the clock set at its
     * end. [whole] goes home after it, as [run] does. From the main screen
     * (a chain step whose claim ended in the pause) the way in is [run]'s.
     */
    override fun resume(img: Mat, whole: Boolean): Outcome {
        if (!carried) return if (whole) run() else work(img)
        rect = Dungeon.gameRect(img)
        room = Dungeon.headroom(img)
        val stats = goOn()
        log("going on with the Meat Field visit the main switch stopped")
        var ended = false
        try {
            try {
                if (whole && Dungeon.autoButton(img) != null) {
                    fieldTapped = false
                    if (!goToField()) {
                        stats.reason = if (!on()) "stopped" else "could not open the Meat Field"
                        ended = true
                        return outcomeOf(stats)
                    }
                } else {
                    fieldTapped = true
                    if (!closeOurs()) {
                        if (!on()) {
                            stats.reason = "stopped"
                            return outcomeOf(stats)
                        }
                        lastVisit = stats
                        carried = false
                        return Outcome.parked("The dialog over the Meat Field did not close.")
                    }
                }
                clearHeader()
                body(stats)
                ended = true
            } finally {
                headerBack()
                if (whole) leaveField()
            }
        } finally {
            // A part the switch stopped again leaves the clock as the first
            // part set it, and says what it did all the same: live on
            // LDPlayer 2026-09-30 23:18:18 to 23:19:03 four Good seeds went
            // into the field and onto the TODAY card with no line of the
            // log's saying so.
            if (ended) {
                if (stats.reason != "stopped") setClock(stats) else tally(stats)
            }
        }
        return outcomeOf(stats)
    }

    /**
     * The one dialog of the field standing, closed the way the visit closes
     * it: the water popup by its own X ([closePopup]), the seed menu, the
     * can dialog and the ad offers by the dialog's X ([closeMenu]). True once
     * the bare field stands, or where it stood already.
     */
    private fun closeOurs(): Boolean {
        val (popup, other) = onFrame { img ->
            (Farm.waterPopup(img) != null) to (!bare(img) && (Farm.seedMenu(img).first != null ||
                Farm.boostPopup(img) != null || Farm.adDialog(img) != null))
        }
        if (popup) {
            closePopup()
            return onFrame { bare(it) }
        }
        if (other) return closeMenu()
        return true
    }

    /**
     * A visit going on after a pause: its own count for the TODAY card, and
     * what the visit before the pause spent -- the ads it tapped, the seeds
     * it bought -- kept.
     */
    private fun goOn(): Stats {
        val stats = Stats()
        lastVisit = stats
        seedAdSaid = false
        canAdSaid = false
        said.clear()
        stays.reset()
        return stats
    }

    private fun outcomeFor(stats: Stats): Outcome = stats.adPark?.let { Outcome.parked(it) } ?: when (stats.reason) {
        "stopped" -> Outcome.STOPPED
        "could not open the Meat Field" -> Outcome.parked(
            "I could not open the Meat Field from here.")
        // No `leaving`: the field raises no confirmation prompt of its own,
        // so there is nothing for the director to press OK on afterwards.
        else -> Outcome.DONE
    }
}
