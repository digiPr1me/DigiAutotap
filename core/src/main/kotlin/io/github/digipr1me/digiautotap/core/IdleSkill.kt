package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * Idle Rewards, a task of its own since 2026-09-28 (PLAN_WERBUNG.md 11 and
 * 16, the player's change: not a step of the Quest Loop). The game gathers
 * Idle Rewards for up to eight hours and hands them over in the window
 * behind the chest labelled "Rewards" on the main screen; the same window
 * offers "Extra Rewards", two ads a day for two hours' worth each.
 *
 * What a visit does, from the plain main screen: the chest
 * ([Quest.rewardsChest]), the window ([Quest.idleWindow], seen twice), Claim
 * while it is lit, then -- with `idle_ads` on and the Ad Skip Pass -- the
 * Extra Rewards while the game's "n/2" says there are any
 * ([Quest.idleExtraLeft]): the button, and the reward with the tap. Where
 * the game asks "View ads to receive 2 hours' worth of Idle Rewards?" all
 * the same, or an ad comes, the account has no pass: nothing is tapped on
 * either and the visit parks ([FreeAds.PARK]; 2026-10-03,
 * PLAN_ABSCHLUSS_1_3.md 3.6). Every reward is proved by the Reward sheet or
 * by the count falling, and the window is closed with a tap beside it,
 * where the measurement closed it, and the main screen is seen again.
 *
 * When: [hasBudget] is due by the clock ([EVERY] after the last visit) or,
 * with `idle_ads` on, while the day's ads are not spent by the file's count
 * -- the second witness; the game's "n/2" decides whenever the window is
 * open, and a visit that reads 0/2 writes the day as spent. A visit whose
 * chest or window did not answer books [RETRY] and not the full clock.
 *
 * It is a round on the main screen in the semi-automatic mode (as the quest
 * loop is), a chain step in the fully automatic mode, and it works the
 * window where the player or the game has opened it (Director.CLAIM_REWARDS,
 * which the game raises by itself after a long absence): there it claims,
 * takes the ads and leaves the window standing, as `work` leaves every
 * screen.
 *
 * What it never does: tap Extra Rewards with `idle_ads` off or without the
 * pass, tap a third one of a day, press OK on the game's question before an
 * ad, touch an ad, or send the back key.
 */
class IdleSkill(
    private val cap: Capture,
    private val settings: () -> Settings = { Settings() },
    /** `idle_due_at`, epoch seconds, null where no visit was ever made (due). */
    private val dueAt: () -> Double? = { null },
    private val remember: (Double) -> Unit = {},
    /** The day's Extra Rewards spent by the file's count ([Stored.idleAdsUsed]). */
    private val adsUsed: () -> Int = { 0 },
    private val setAdsUsed: (Int) -> Unit = {},
    private val log: (String) -> Unit = { HelperLog.line(it) },
    private val on: () -> Boolean = { MainSwitch.on },
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    private val patience: Double = 1.0,
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    /** Epoch seconds: the clock outlives the process. */
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) : Skill {

    /** `idle_ads` (off by default, the player's call of 2026-09-28), taken only with the Ad Skip Pass ([Stored.idle]). */
    data class Settings(val ads: Boolean = false)

    override val key = "idle"
    override val name = "Idle Rewards"

    /** This pass's count, for the TODAY card ([SkillStats.SHOWN], "idle"). */
    var lastCounts: Map<String, Int> = emptyMap()
        private set
    private var claims = 0
    private var ads = 0
    private var said: String? = null

    override fun worksOn(screen: String): Boolean = screen == Director.CLAIM_REWARDS

    /**
     * Due by the clock, or -- with the switch on -- while the file says the
     * day has ads left. The second is held to [RETRY] after a visit, so
     * that a count that did not read, or an ad that did not come, is not
     * walked into again every round.
     */
    override fun hasBudget(): Boolean {
        val due = dueAt()
        if (due == null || now() >= due) return true
        return adsOpen() && now() >= lastVisit + RETRY
    }

    /** When the last visit ended, epoch seconds; this process only. */
    private var lastVisit = Double.NEGATIVE_INFINITY

    private fun adsOpen() = settings().ads && adsUsed() < DAY_ADS

    /** On the window: work where Claim is lit or, with the switch on, an ad is left. */
    override fun seesWork(screen: String, img: Mat): Boolean? {
        if (screen != Director.CLAIM_REWARDS) return null
        val w = Quest.idleWindow(img) ?: return false
        if (w.lit) return true
        if (!settings().ads) return false
        val left = Quest.idleExtraLeft(img, w)
        return left != null && left > 0
    }

    /**
     * The main screen (a round) or the window itself. On the main screen
     * the whole visit, home again; on the window, its work, and the window
     * left standing.
     */
    override fun work(img: Mat): Outcome = held(workPass(img), fromMain = Dungeon.autoButton(img) != null)

    private fun workPass(img: Mat): Outcome {
        if (Dungeon.autoButton(img) != null) return visit(img)
        begin()
        val w = Quest.idleWindow(img) ?: return Outcome.DONE
        log("idle rewards: the window is open -- working it")
        val out = onWindow(w)
        setClock(if (out.result == Result.DONE) EVERY else RETRY)
        return out
    }

    override fun run(): Outcome = held(runPass(), fromMain = true)

    private fun runPass(): Outcome {
        val img = try { cap.grab() } catch (e: CaptureError) { return Outcome.noFrame(e) }
        try {
            if (Dungeon.autoButton(img) == null) {
                begin()
                return Outcome.parked("Idle Rewards start from the main screen.")
            }
            return visit(img)
        } finally {
            img.release()
        }
    }

    // ------------------------------------------------------------------------
    // After a pause (PLAN_RELEASE_1_3.md B4, the player's rule of 2026-09-30)
    // ------------------------------------------------------------------------
    /** The last visit was stopped by the main switch and has not been gone on with or begun afresh since. */
    internal var carried = false
        private set
    /** That visit began on the main screen, and so ends there, with the window closed. */
    private var carriedFromMain = false
    private val stays = Stays(on) { log(it) }

    private fun held(out: Outcome, fromMain: Boolean): Outcome {
        carried = out.result == Result.STOPPED
        carriedFromMain = fromMain
        stays.reset()
        return out
    }

    /**
     * The window, and the Reward sheet a Claim or an Extra Reward raised
     * over it (`unknown` to the director, [Dungeon.rewardSheet]). What is
     * owed is the window's own: Claim lit or not, the game's "n/2" of the
     * Extra Rewards, so a visit that goes on takes what is left and nothing
     * twice.
     */
    override fun resumesOn(screen: String, img: Mat): Boolean =
        carried && (screen == Director.CLAIM_REWARDS || (screen == Director.UNKNOWN && Dungeon.rewardSheet(img)))

    /**
     * The visit the switch stopped, gone on with: the sheet closed where one
     * stands, the window's work, and -- where the visit began on the main
     * screen, or [whole] asks for home -- the window closed as a visit closes
     * it. From the main screen (a chain step whose claim ended) a visit.
     */
    override fun resume(img: Mat, whole: Boolean): Outcome {
        if (!carried) return if (whole) run() else work(img)
        val home = whole || carriedFromMain
        if (Dungeon.autoButton(img) != null) return held(visit(img), fromMain = true)
        begin()
        log("idle rewards: going on with the visit the main switch stopped")
        if (Dungeon.rewardSheet(img)) settle()
        val w = waitWindow() ?: return held(
            if (!on()) Outcome.STOPPED else Outcome.parked("The Idle Rewards window did not come back."), home)
        var out = onWindow(w)
        if (home && out.result == Result.DONE && !closeWindow(w.anchor)) {
            if (!on()) out = Outcome.STOPPED
            else if (stays.met == null) {
                grabOrNull()?.let { keep(it, "idle_not_home"); it.release() }
                out = Outcome.parked("The Idle Rewards window did not close.")
            }
        }
        if (out.result != Result.STOPPED) setClock(if (out.result == Result.DONE) EVERY else RETRY) else counted()
        return held(out, home)
    }

    override fun leave(): Boolean {
        val img = grabOrNull() ?: return false
        try {
            val w = Quest.idleWindow(img) ?: return Dungeon.autoButton(img) != null
            return closeWindow(w.anchor)
        } finally {
            img.release()
        }
    }

    private fun begin() {
        claims = 0
        ads = 0
        lastCounts = emptyMap()
        // A window of the game's that ended the chain's second hand ([leave])
        // is not this visit's ([Stays.met]).
        stays.reset()
    }

    private fun counted() {
        lastCounts = mapOf("claims" to claims, "ads" to ads)
    }

    private fun once(text: String) {
        if (said != text) {
            said = text
            log(text)
        }
    }

    /** From the main screen [img]: the chest, the window, its work, home. */
    private fun visit(img: Mat): Outcome {
        begin()
        val chest = Quest.rewardsChest(img)
        if (chest == null) {
            // The box stands against the battle, and a bright background
            // swallows it now and then (Quest.CHEST_BAND); the next round is
            // another picture. Nothing is booked, nothing tapped.
            once("idle rewards: the chest is not read on this frame -- next round")
            return Outcome.DONE
        }
        said = null
        if (!on()) {
            counted()
            return Outcome.STOPPED
        }
        log("idle rewards: opening the chest")
        tap(chest.fx, chest.fy, chest.anchor)
        val w = waitWindow()
        if (w == null && !on()) {
            counted()
            return Outcome.STOPPED
        }
        if (w == null) {
            log("idle rewards: the chest did not open the Idle Rewards window -- again in ${(RETRY / 60).toInt()} min")
            grabOrNull()?.let { keep(it, "idle_no_window"); it.release() }
            setClock(RETRY)
            counted()
            return Outcome.DONE
        }
        var out = onWindow(w)
        val home = closeWindow(w.anchor)
        // A window of the game's own over the way home is the director's:
        // the visit's work is done, and no park says otherwise ([Stays.over]).
        if (!home && out.result == Result.DONE && stays.met == null) {
            grabOrNull()?.let { keep(it, "idle_not_home"); it.release() }
            out = Outcome.parked("The Idle Rewards window did not close.")
        }
        setClock(if (out.result == Result.DONE) EVERY else RETRY)
        return out
    }

    private fun setClock(after: Double) {
        lastVisit = now()
        remember(now() + after)
        counted()
    }

    /** The window [w] in front: Claim if lit, then the Extra Rewards. The window is left open. */
    private fun onWindow(first: IdleWindowRef): Outcome {
        var w = first
        if (w.lit) {
            log("idle rewards: Claim")
            tap(w.claim.fx, w.claim.fy, w.anchor)
            if (settle()) {
                claims += 1
                log("idle rewards: claimed")
            } else {
                log("idle rewards: no Reward sheet after Claim")
            }
            w = waitWindow() ?: return if (!on()) Outcome.STOPPED
                else Outcome.parked("The Idle Rewards window did not come back after Claim.")
        }
        if (!settings().ads) return Outcome.DONE
        var round = 0
        while (round < DAY_ADS) {
            round += 1
            if (!on()) return Outcome.STOPPED
            val left = look { Quest.idleWindow(it)?.let { win -> Quest.idleExtraLeft(it, win) } }
            if (left == null) {
                log("idle rewards: the Extra Rewards count did not read -- no ad tapped")
                return Outcome.DONE
            }
            if (left <= 0) {
                setAdsUsed(DAY_ADS)
                log("idle rewards: Extra Rewards 0/$DAY_ADS -- done for today")
                return Outcome.DONE
            }
            setAdsUsed(maxOf(adsUsed(), DAY_ADS - left))
            log("idle rewards: Extra Rewards $left/$DAY_ADS -- the ad")
            tap(w.extra.fx, w.extra.fy, w.anchor)
            if (adAsked()) {
                log("idle rewards: the game asks for an ad although the Ad Skip Pass is switched on -- I never touch an ad")
                return Outcome.parked(FreeAds.PARK)
            }
            val sheet = settle()
            val back = waitWindow()
            val after = back?.let { win -> look { Quest.idleExtraLeft(it, win) } }
            // The switch off before the ad's end was seen: the ad is counted
            // spent by the game's own "n/2" when the pass goes on, not here.
            if (!sheet && back == null && !on()) return Outcome.STOPPED
            if (sheet || (after != null && after < left)) {
                ads += 1
                setAdsUsed(DAY_ADS - (after ?: (left - 1)))
                log("idle rewards: Extra Rewards collected, $left -> ${after ?: "?"}")
            } else {
                setAdsUsed(DAY_ADS)
                log("idle rewards: the Extra Rewards did not come -- done for today")
                grabOrNull()?.let { keep(it, "idle_ad_no_reward"); it.release() }
                return Outcome.DONE
            }
            w = back ?: return if (!on()) Outcome.STOPPED
                else Outcome.parked("The Idle Rewards window did not come back after the ad.")
        }
        return Outcome.DONE
    }

    /**
     * Did the game ask for an ad after Extra Rewards? Its question -- the
     * Summon's in the Idle Rewards' words, read the same way
     * (SummonSkill.adAsked): `Dungeon.recognise` calls it the prompt with the
     * pink Cancel -- or an ad, or where one sends the player, in front. With
     * the pass the reward comes with the tap and neither stands; nothing is
     * tapped on either. Looked for until the Reward sheet or the window
     * stands without the question, or [CONFIRM_WAIT].
     */
    private fun adAsked(): Boolean {
        val hand = cap.adHand()
        val start = now()
        while (now() - start < CONFIRM_WAIT) {
            if (!on()) return false
            if (Ads.inFront(hand)) return true
            val img = grabOrNull() ?: return false
            try {
                val rec = Dungeon.recognise(img)
                if (rec.state == Dungeon.EXIT && rec.exitKind == "party" && rec.exitOk != null) return true
                if (Dungeon.rewardSheet(img)) return false
            } finally {
                img.release()
            }
            sleep(0.3 * patience)
        }
        return false
    }

    /**
     * After a tap that hands out a reward: the Reward sheet ("Tap to close")
     * waited for and closed with a tap on its words. Whether it was seen.
     * Ends as soon as the window stands again without a sheet over it.
     */
    private fun settle(): Boolean {
        var sheet = false
        val start = now()
        while (now() - start < SHEET_WAIT) {
            val img = grabOrNull() ?: return sheet
            try {
                if (Dungeon.rewardSheet(img)) {
                    sheet = true
                    val line = Dungeon.SHEET_CLOSE_LINE
                    tap((line[0] + line[1]) / 2.0, (line[2] + line[3]) / 2.0, Dungeon.POPUP)
                } else if (sheet && Quest.idleWindow(img) != null) {
                    return true
                }
            } finally {
                img.release()
            }
            sleep(0.5 * patience)
        }
        return sheet
    }

    /** The window on two fresh frames in a row ("never trust a single frame"), or null. */
    private fun waitWindow(): IdleWindowRef? {
        var seen = 0
        val start = now()
        while (now() - start < OPEN_WAIT) {
            sleep(0.5 * patience)
            val w = look { Quest.idleWindow(it) }
            if (w != null) {
                seen += 1
                if (seen >= 2) return w
            } else {
                seen = 0
            }
        }
        return null
    }

    /**
     * The window closed by a tap beside it, where the measurement closed it
     * (CLOSE_SPOT, 2026-09-28: below the window, in the dimmed field), and
     * the main screen waited for. Only ever tapped with the window read on
     * the frame just before, so the tap cannot land on the main screen.
     */
    private fun closeWindow(anchor: Dungeon.Anchor): Boolean {
        for (i in 0 until CLOSE_TRIES) {
            // The visit's way home, and asked like every way home: with the
            // switch off the window stays where the pause found it ([Stays]).
            if (stays.now()) return false
            val img = grabOrNull() ?: return false
            try {
                // One of the game's own windows is the director's ([Stays.over], B60).
                if (stays.over(img)) return false
                if (Dungeon.autoButton(img) != null) return true
                if (Quest.idleWindow(img) == null) {
                    // Neither the window nor the main screen: wait a beat.
                    sleep(0.5 * patience)
                    continue
                }
            } finally {
                img.release()
            }
            tap(CLOSE_SPOT[0], CLOSE_SPOT[1], anchor)
            sleep(1.0 * patience)
        }
        return look { Dungeon.autoButton(it) != null } == true
    }

    private fun <T> look(f: (Mat) -> T): T? {
        val img = grabOrNull() ?: return null
        try {
            return f(img)
        } finally {
            img.release()
        }
    }

    private fun grabOrNull(): Mat? = try { cap.grab() } catch (e: CaptureError) { null }

    private fun tap(fx: Double, fy: Double, anchor: Dungeon.Anchor) {
        // Not with the main switch off: the service would hold it back.
        if (!on()) return
        val img = grabOrNull() ?: return
        val r = Dungeon.gameRect(img, anchor)
        img.release()
        cap.tap(Py.roundInt(r.x0 + fx * r.gw), Py.roundInt(r.y0 + fy * r.gh))
    }

    companion object {
        /** Two Extra Rewards a day ("n/2"). */
        const val DAY_ADS = 2

        /**
         * How often the ordinary Idle Rewards are claimed. The game gathers
         * them for eight hours at most ("8 hours max." in the window), so a
         * visit every four keeps the box from ever filling, even with a
         * visit four hours late -- the phone asleep, the switch off.
         */
        const val EVERY = 4 * 3600.0

        /** A visit that did not get in books this, not [EVERY]. */
        const val RETRY = 10 * 60.0

        /** The window opened 2 s after the tap on the chest (2026-09-28); five times that. */
        const val OPEN_WAIT = 10.0

        /** The Reward sheet stood 2 s after Claim; after an ad it follows the game's return. */
        const val SHEET_WAIT = 12.0

        /** The Summon's question stood 0.30 s after its tap (SummonSkill.AD_CONFIRM_WAIT). */
        const val CONFIRM_WAIT = 3.0

        /** Below the window, in the dimmed field: (540, 1640) on 1080 x 1920 closed it, 2026-09-28. */
        val CLOSE_SPOT = doubleArrayOf(0.476, 0.857)
        const val CLOSE_TRIES = 4
    }
}

private typealias IdleWindowRef = Quest.IdleWindow
