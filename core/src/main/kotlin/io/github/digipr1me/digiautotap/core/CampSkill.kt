package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import kotlin.math.abs

/**
 * The Camp (PLAN_CAMP.md C3, the player's task of 2026-10-19): once a game
 * day, Support and Check-In in the Camp Tent, the ticked items of the Camp
 * Shop, the attempts left at Leomon's Dojo and Piximon's Drill Site and their
 * Reward Boxes -- and back. A supporter's task ([Paywall]).
 *
 * What the game does, as C1 and C2 measured it on LDPlayer on 2026-10-19
 * (the readers and their numbers are in Camp.kt and CampStation.kt, the
 * timings in PLAN_CAMP.md 6):
 *
 *  * The Camp tab of the nav bar opens the Camp screen, the map with the
 *    tent, the shop and the two stations' signs on it. Every window of the
 *    camp has no X: a tap in the dimmed field above it closes it, one layer
 *    a tap ([Camp.CLOSE_SPOT]); the globe goes home from the map.
 *  * The tent's Support opens the Camp Support window, not a reward: Support
 *    All there (lit while somebody is training) raised the Reward sheet,
 *    "Tap to close" brought the window back with Support All dim, and the
 *    tent after it had lost the "!" by its Support -- which is what lit means
 *    on the tent ([Camp.TentWindow]). Check-In stood complete (grey) on both
 *    accounts all day; its lit state and what it raises are C4's to see, and
 *    it is taken here as Support All is: a tap, the Reward sheet if it comes,
 *    the button grey on two frames.
 *  * A shop card opens its purchase dialog at a count of 1; its price button
 *    raised the Reward sheet over the shop, and after "Tap to close" the card
 *    said what was bought: its day's limit one less, or SOLD OUT -- and a
 *    card without a limit (the Acceleration Ticket) does not change, so its
 *    sheet is its witness. A sold-out card is not named (its picture is
 *    dimmed under the stamp) and opens nothing.
 *  * A station's Auto Clear is dim until the station has a record, and a dim
 *    one does nothing; lit, one tap spends one attempt at once and raises the
 *    Reward sheet. Attempt is a battle that plays itself for about 42 s and
 *    ends in a Results window; its Close leads through "Now Loading" back to
 *    the station window with one attempt less. Reward Boxes opens a window
 *    whose Claim Rewards was dim on both accounts (nothing came to claim).
 *
 * The pass ([run]): the Camp tab read on two frames and tapped, the Camp
 * screen on two frames, then the three parts, each only where its day is not
 * stamped yet (PLAN_CAMP.md 3.3) -- the tent (Support, Check-In), the shop,
 * the stations -- each from the Camp screen back to it, and home. A part
 * writes its stamp when it is done or found nothing to do; the stamps are
 * the file's ([Stored.camp]) and the pass's only memory, so a pass that goes
 * on after a pause or a visit later in the day does nothing twice: what is
 * done shows on the game's own screen (a grey button, a sold-out card, a
 * counter at 0/2) and is read there, not remembered.
 *
 * The player's rules (PLAN_CAMP.md 4): the five Digital Gates ticked by
 * default (question 1); one Acceleration Ticket x5 a day (4); Auto Clear by
 * default and Attempt on the page's switch (3); at a station whose Auto
 * Clear is dim while attempts are left, one Attempt -- the battle that plays
 * itself -- and then Auto Clear, never more than that one Attempt a station
 * and day (9, "das bricht die Regel 'nie der Knopf, den der Schalter nicht
 * wählt', spart dir aber den Handgriff"); a shop part the coins run out on
 * still stamps the day (6); a dialog of the game's over the Camp is a park
 * with its sentence, nothing tapped on it (10).
 *
 * Two deviations from 3.3, point 2: a piece is bought one dialog at a time at
 * the count the dialog opens with, and `+` is never tapped. `-` is never
 * tapped (3.6), so a `+` too many could not be taken back, and the dialog's
 * red price says for the one piece in front whether the coins reach; a card
 * with two left today is bought in two dialogs. And a red price skips its
 * item, not the whole shop: the next ticked item may cost less.
 *
 * What is counted and stamped is counted at its witness, before anything
 * closes it: the Reward sheet a button raised (EX Missions counts a Claim the
 * same way), the Results of an Attempt, or -- where no sheet comes -- the
 * button, the card or the counter changed on two frames. So a pass the switch
 * stops on a sheet has counted what the sheet says, and the pass that goes on
 * after the pause closes the sheet and finds the part done.
 *
 * Every tap is aimed at something read on the frame just before it; a change
 * of state counts on two frames; a button that is not lit is not touched;
 * the main switch is asked before every tap ([act]); never the back key,
 * never OK on a prompt, never Camp Wars, Ranking, Member List, Manage, the
 * (i), the shop's filter, an avatar, `-` or `-10` (PLAN_CAMP.md 3.6).
 */
class CampSkill(
    private val cap: Capture,
    private val icons: CampIcons,
    /** The page and the day, asked afresh at every question ([Stored.camp]). */
    private val settings: () -> Settings,
    /** A part done for the game day ([Stored.stampCamp]). */
    private val stamp: (Part) -> Unit,
    /** A station's one Attempt of question 9 spent for the game day ([Stored.campAttempted]). */
    private val attempted: (CampStation.Which) -> Unit,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** The main switch, asked before every tap. */
    private val on: () -> Boolean = { MainSwitch.on },
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    private val patience: Double = 1.0,
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
    /** Seconds, monotonic. */
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) : Skill {

    override val key = KEY
    override val name = "Camp"

    /** The four parts of a day, each with its stamp (`camp_<key>_until`, PLAN_CAMP.md 3.4). */
    enum class Part(val key: String) { SUPPORT("support"), CHECKIN("checkin"), SHOP("shop"), STATIONS("stations") }

    /** What the page and the file say, read afresh at every question. */
    data class Settings(
        /** The ticked items by their template's name ([CampIcons.NAMES]), in the grid's order. */
        val buy: List<String>,
        /** The page's switch: Attempt instead of Auto Clear at the stations (question 3). */
        val attempt: Boolean = false,
        /** The parts stamped for this game day. */
        val done: Set<Part> = emptySet(),
        /** The stations whose one Attempt of question 9 is spent this game day. */
        val attempted: Set<CampStation.Which> = emptySet(),
    ) {
        /** The parts still to do today: not stamped, and the shop only with a box ticked. */
        val open: List<Part> get() = Part.values().filter { it !in done && (it != Part.SHOP || buy.isNotEmpty()) }
    }

    /** This pass's counts, for the TODAY card ([SkillStats.SHOWN], "camp"). */
    var lastCounts: Map<String, Int> = emptyMap()
        private set

    private var supports = 0
    private var checkins = 0
    /** Purchases proved, one per dialog (PLAN_CAMP.md 3.5). */
    private var bought = 0
    /** Attempts spent and proved on the counter, by Auto Clear or by Attempt. */
    private var cleared = 0
    /** Reward boxes claimed: the two chests' numbers before the Claim, or 1 where they did not read. */
    private var boxes = 0
    /** The rectangle and headroom of the last frame read: what every tap is aimed in. */
    private var rect: Dungeon.GameRect? = null
    private var room = 0

    override fun worksOn(screen: String): Boolean = screen in SCREENS

    /** Something not stamped yet today; a chain step with all four done does not open the Camp. */
    override fun hasBudget(): Boolean = settings().open.isNotEmpty()

    override fun noBudgetWhy(): String? = if (settings().open.isEmpty()) NO_BUDGET else null

    // seesWork stays null: once a visit, and the day's stamps through
    // hasBudget -- the Camp screen a player opens to chat is not worked
    // again every round (PLAN_CAMP.md 3.3).

    /**
     * The Camp screen or one of its windows, opened by the player: the same
     * pass, from the window that is open, and back to the Camp screen, not
     * home (PLAN_CAMP.md 3.3, question 5).
     */
    override fun work(img: Mat): Outcome = pass { workPass() }

    /** From the plain main screen: the tab, the three parts, and home. */
    override fun run(): Outcome = pass { runPass() }

    /** The chain's second hand: from any camp screen home, gated on the switch ([Stays]). */
    override fun leave(): Boolean = try {
        stays.reset()
        if (stays.now()) false else goHome()
    } catch (e: Stop) {
        false
    } catch (e: CaptureError) {
        false
    }

    // ------------------------------------------------------------------------
    // After a pause (PLAN_RELEASE_1_3.md B4)
    // ------------------------------------------------------------------------
    /** The last pass was stopped by the main switch and has not been gone on with since. */
    internal var carried = false
        private set
    private val stays = Stays(on) { log(it) }

    /**
     * The five camp screens, the Reward sheet a tap of the pass raised over
     * one of them (`unknown` to the director), and the Results an Attempt
     * ended in (the director's `dialog`). The stamps and the game's own
     * buttons are what the pass goes on from, so nothing is done twice.
     */
    override fun resumesOn(screen: String, img: Mat): Boolean =
        carried && (screen in SCREENS ||
            (screen == Director.UNKNOWN && Dungeon.rewardSheet(img)) ||
            ((screen == Director.DIALOG || screen == Director.UNKNOWN) && CampStation.results(img) != null))

    /**
     * The pass the switch stopped, gone on with where it stands: the sheet or
     * the Results closed, the parts that are not stamped, and -- [whole] --
     * home; from the main screen it is [run]'s.
     */
    override fun resume(img: Mat, whole: Boolean): Outcome {
        if (!carried) return if (whole) run() else work(img)
        return pass {
            log("camp: going on with the pass the main switch stopped")
            if (whole && Dungeon.autoButton(img) != null) runPass()
            else {
                val out = workPass()
                if (whole && out.result == Result.DONE) home() else out
            }
        }
    }

    // ------------------------------------------------------------------------
    // The pass
    // ------------------------------------------------------------------------
    /** The switch was off at a tap: the pass ends where it stands. */
    private class Stop : RuntimeException()

    /** The pass cannot go on, and says why; the frame is kept where it is thrown. */
    private class Park(val why: String) : RuntimeException(why)

    private inline fun pass(body: () -> Outcome): Outcome {
        begin()
        val out = try {
            body()
        } catch (e: Stop) {
            Outcome.STOPPED
        } catch (e: Park) {
            Outcome.parked(e.why)
        } catch (e: CaptureError) {
            Outcome.noFrame(e)
        }
        lastCounts = mapOf("supports" to supports, "checkins" to checkins, "bought" to bought,
                           "cleared" to cleared, "boxes" to boxes)
        carried = out.result == Result.STOPPED
        return out
    }

    /** The counters at the top of every pass (notes/director.md, "A counter nothing clears is counted again at the end of every pass"). */
    private fun begin() {
        supports = 0
        checkins = 0
        bought = 0
        cleared = 0
        boxes = 0
        lastCounts = emptyMap()
        stays.reset()
    }

    private fun runPass(): Outcome {
        when (enter()) {
            Entry.IN -> {}
            Entry.NOT_MAIN -> return Outcome.parked("The Camp task starts from the main screen.")
            // Nothing opened: nothing to undo, said and kept by [enter].
            Entry.NO_TAB -> return Outcome.DONE
        }
        camp(null)
        return home()
    }

    /**
     * Where a work begins: the Camp screen ([group] null), or the window of
     * one part -- the tent or its Camp Support window, the shop or its
     * purchase dialog, a station or its Reward Boxes -- and for a station
     * which one, where its window shows it.
     */
    private class Start(val group: Group?, val which: CampStation.Which? = null)

    private enum class Group { TENT, SHOP, STATIONS }

    private fun workPass(): Outcome {
        val start = settle() ?: return Outcome.DONE
        log(if (start.group == null) "camp: the Camp is open -- working it"
            else "camp: a window of the Camp is open -- working the Camp from it")
        camp(start)
        return Outcome.DONE
    }

    /**
     * What stands in front at the start of a work: a sheet or the Results of
     * this pass's own closed first, then the screen or window that is open.
     * Null where nothing of the Camp's reads any more (the player went on).
     */
    private fun settle(): Start? {
        repeat(SETTLE_LOOKS) {
            val img = grab()
            var sheet = false
            var results: CampStation.Results? = null
            var start: Start? = null
            try {
                sheet = Dungeon.rewardSheet(img)
                if (!sheet) results = CampStation.results(img)
                if (!sheet && results == null) start = startOf(img)
            } finally {
                img.release()
            }
            val r = results
            when {
                sheet -> closeSheet()
                r != null && r.close.lit -> closeResults(r, null)
                start != null -> return start
                else -> sleep(BEAT * patience)
            }
        }
        return null
    }

    private fun startOf(img: Mat): Start? = when {
        Camp.screen(img) != null -> Start(null)
        Camp.tentWindow(img) != null || Camp.supportWindow(img) != null -> Start(Group.TENT)
        Camp.shopWindow(img) != null || Camp.buyDialog(img) != null -> Start(Group.SHOP)
        else -> CampStation.window(img)?.let { Start(Group.STATIONS, it.which) }
            ?: if (CampStation.boxesWindow(img) != null) Start(Group.STATIONS) else null
    }

    /**
     * The three parts from the Camp screen or the window [start] names, that
     * one first: each where its day is not stamped, and each back to the Camp
     * screen -- a window that had nothing to do is closed all the same.
     */
    private fun camp(start: Start?) {
        val order = Group.values().toMutableList()
        val first = start?.group
        if (first != null) {
            order.remove(first)
            order.add(0, first)
        }
        for (g in order) {
            val s = settings()
            when (g) {
                Group.TENT -> if (Part.SUPPORT in s.open || Part.CHECKIN in s.open) tent(s)
                Group.SHOP -> if (Part.SHOP in s.open) shop(s)
                Group.STATIONS -> if (Part.STATIONS in s.open) stations(start?.takeIf { it.group == Group.STATIONS })
            }
            toCamp()
        }
        val left = settings().open
        log(if (left.isEmpty()) "camp: done for today" else "camp: left for another visit: " +
            left.joinToString(", ") { it.key })
    }

    private enum class Entry { IN, NOT_MAIN, NO_TAB }

    /**
     * From the plain main screen to the Camp screen: the Camp tab read on two
     * frames running and tapped, the Camp screen on two frames. A Stage
     * Failed banner is tapped away at the neutral spot first, as EX Missions
     * does: any tap takes it, a tap meant for the tab included.
     */
    private fun enter(): Entry {
        var main = false
        var seen = false
        var banners = 0
        var tab: Explore.Target? = null
        val begin = now()
        while (tab == null) {
            val img = grab()
            try {
                if (Dungeon.stageFailed(img)) {
                    main = true
                    seen = false
                    if (banners < NEUTRAL_TRIES) {
                        log("camp: the Stage Failed banner is up -- tapping it away")
                        act(NEUTRAL)
                        banners += 1
                    }
                } else {
                    if (Dungeon.autoButton(img) != null) main = true
                    val t = Camp.campTab(img)
                    if (t != null && seen) tab = t
                    seen = t != null
                }
            } finally {
                img.release()
            }
            if (tab != null || now() - begin >= START_WAIT) break
            sleep(BEAT * patience)
        }
        val t = tab ?: return if (main) {
            log("camp: the Camp tab is not read on this frame -- nothing opened")
            keepNow("camp_no_tab")
            Entry.NO_TAB
        } else {
            Entry.NOT_MAIN
        }
        log("camp: opening the Camp")
        act(t)
        if (waitTwice(OPEN_WAIT) { Camp.screen(it) } == null) {
            val img = grab()
            val home = try { Dungeon.autoButton(img) != null } finally { img.release() }
            if (home) {
                log("camp: the Camp screen did not open")
                keepNow("camp_no_screen")
                return Entry.NO_TAB
            }
            throw blocked("camp_no_screen", "The Camp screen did not open, and I do not know what is in front.")
        }
        return Entry.IN
    }

    // ------------------------------------------------------------------------
    // The tent (3.3, point 1)
    // ------------------------------------------------------------------------
    /**
     * The tent: Support where its "!" stands -- through the Camp Support
     * window and Support All --, then Check-In where it is lit, each part
     * only where its day is open. From the player's Camp Support window,
     * Support All there and the tent's Support not tapped again.
     */
    private fun tent(s: Settings) {
        // The player's Camp Support window: Support All there first, and
        // Support on the tent not tapped again in this pass.
        val player = waitTwice(LOOK_WAIT) { Camp.supportWindow(it) }
        var t: Camp.TentWindow
        if (player != null) {
            if (Part.SUPPORT in s.open) supportAll(player) else close(Layer.SUPPORT)
            t = waitTwice(TENT_WAIT) { Camp.tentWindow(it) } ?: throw blocked("camp_no_tent", "The Camp Tent went away.")
        } else {
            t = open("the Camp Tent", TENT_WAIT, { it.tent }) { Camp.tentWindow(it) } ?: return
        }
        if (player == null && Part.SUPPORT in settings().open) {
            if (!t.support.lit) {
                log("camp: supported already today")
                stamp(Part.SUPPORT)
            } else {
                log("camp: Support")
                act(t.support.target)
                val w = waitTwice(SUPPORT_WAIT) { Camp.supportWindow(it) }
                if (w == null) {
                    log("camp: the Camp Support window did not open")
                    keepNow("camp_no_support")
                } else {
                    supportAll(w)
                }
            }
            t = waitTwice(TENT_WAIT) { Camp.tentWindow(it) } ?: throw blocked("camp_no_tent", "The Camp Tent went away.")
        }
        if (Part.CHECKIN in settings().open) checkIn(t)
    }

    /**
     * Support All on the Camp Support window [w], read on the frame just
     * before: lit, a tap, and its proof -- the Reward sheet, or Support All
     * dim on two frames (C1: the list empties with it); then the window
     * closed onto the tent. Dim, nothing is tapped and the day is done.
     */
    private fun supportAll(w: Camp.SupportWindow) {
        if (!w.supportAll.lit) {
            log("camp: nobody to support -- Support All is dim")
            stamp(Part.SUPPORT)
            close(Layer.SUPPORT)
            return
        }
        press("Support All", w.supportAll.target,
              again = { Camp.supportWindow(it)?.supportAll?.takeIf { b -> b.lit }?.target },
              done = { Camp.supportWindow(it)?.supportAll?.lit == false }) {
            supports += 1
            log("camp: supported ($supports)")
            stamp(Part.SUPPORT)
        }
        close(Layer.SUPPORT)
    }

    /** Check-In on the tent [t]: grey is done; lit, a tap and its proof, the sheet or the button grey on two frames. */
    private fun checkIn(t: Camp.TentWindow) {
        if (!t.checkIn.lit) {
            log("camp: checked in already today")
            stamp(Part.CHECKIN)
            return
        }
        press("Check-In", t.checkIn.target,
              again = { Camp.tentWindow(it)?.checkIn?.takeIf { b -> b.lit }?.target },
              done = { Camp.tentWindow(it)?.checkIn?.lit == false }) {
            checkins += 1
            log("camp: checked in ($checkins)")
            stamp(Part.CHECKIN)
        }
    }

    /**
     * One button that gives a reward: a tap on [target] (read on the frame
     * just before), and its proof -- the Reward sheet, the game's own
     * witness, or without one [done] on two frames. [proved] is called at
     * the proof and before the sheet is closed, so that a pass the switch
     * stops on the sheet has counted and stamped what the sheet says (as EX
     * Missions counts a Claim by its sheet). A tap after which neither comes
     * is made once more where [again] still reads the button lit; then "the
     * button did not take" and no stamp (3.3, point 1).
     */
    private fun press(what: String, target: Explore.Target, again: (Mat) -> Explore.Target?, done: (Mat) -> Boolean,
                      proved: () -> Unit): Boolean {
        var aim = target
        for (i in 0 until 2) {
            log("camp: $what")
            act(aim)
            if (waitSheet()) {
                proved()
                closeSheet()
                if (waitTwice(SHEET_GONE) { if (done(it)) true else null } == null) {
                    log("camp: $what -- the sheet came, and the button still reads as before")
                    keepNow("camp_after_sheet")
                }
                return true
            }
            if (waitTwice(SHEET_GONE) { if (done(it)) true else null } != null) {
                proved()
                return true
            }
            if (i == 1) break
            aim = waitTwice(LOOK_WAIT, read = again) ?: break
            log("camp: $what changed nothing -- once more")
        }
        log("camp: $what -- the button did not take")
        keepNow("camp_did_not_take")
        return false
    }

    // ------------------------------------------------------------------------
    // The shop (3.3, point 2)
    // ------------------------------------------------------------------------
    private fun shop(s: Settings) {
        // A purchase dialog the player left open is theirs to have opened:
        // nothing is bought in it -- it is closed, and the shop worked.
        if (waitTwice(LOOK_WAIT) { Camp.buyDialog(it) } != null) close(Layer.DIALOG)
        var shop = open("the Camp Shop", WINDOW_WAIT, { it.shop }) { Camp.shopWindow(it, icons) } ?: return
        val wanted = s.buy
        log("camp: ticked in the shop: " + wanted.joinToString(", ") { SkillSettings.campLabel(it) })
        val handled = HashSet<String>()
        // The first rows: the list remembers where it was left (C1, 6).
        shop = scroll(shop, top = true)
        shop = buyInSight(shop, wanted, handled)
        if (wanted.any { it !in handled } && shop.atBottom != true) {
            shop = scroll(shop, top = false)
            buyInSight(shop, wanted, handled)
        }
        for (item in wanted.filter { it !in handled }) {
            log("camp: ${SkillSettings.campLabel(item)}: no card reads as it -- sold out, or not in the shop; left alone")
        }
        stamp(Part.SHOP)
    }

    /** The shop's cards on two frames running, by their state: the same cards, the same counts. */
    private fun shopNow(wait: Double = WINDOW_WAIT): Camp.ShopWindow? =
        waitTwice(wait, key = { w -> w.cards.map { Triple(it.item, it.left, it.soldOut) } to (w.atTop to w.atBottom) }) {
            Camp.shopWindow(it, icons)
        }

    /** The list to its top or its bottom, each swipe proved on two frames; as far as it goes after SWIPES_MAX. */
    private fun scroll(shop: Camp.ShopWindow, top: Boolean): Camp.ShopWindow {
        var s = shop
        repeat(SWIPES_MAX) {
            if ((if (top) s.atTop else s.atBottom) == true) return s
            log(if (top) "camp: the shop list to its top" else "camp: the shop list to its bottom")
            swipe(if (top) SWIPE_HIGH else SWIPE_LOW, if (top) SWIPE_LOW else SWIPE_HIGH)
            sleep(SWIPE_SETTLE * patience)
            s = shopNow() ?: throw blocked("camp_no_shop", "The Camp Shop went away.")
        }
        if ((if (top) s.atTop else s.atBottom) != true) {
            log("camp: the shop list did not reach its ${if (top) "top" else "bottom"} -- going on with what is in sight")
        }
        return s
    }

    /** Every ticked item whose card is in sight, in the grid's order: bought, or said why not. */
    private fun buyInSight(shop: Camp.ShopWindow, wanted: List<String>, handled: MutableSet<String>): Camp.ShopWindow {
        var s = shop
        for (item in wanted) {
            if (item in handled) continue
            val card = s.cards.firstOrNull { it.item == item } ?: continue
            handled += item
            s = buy(item, card, s)
        }
        return s
    }

    /**
     * One ticked item from its card [card], read on the frame just before:
     * a piece a dialog, for as long as the card's limit has one left today --
     * one a day where the card shows no limit (question 4). A piece is
     * counted by the Reward sheet its price button raised (C1: only after a
     * purchase), before the sheet is closed, or without a sheet by its card
     * on two frames: its count one less, or SOLD OUT. A card that does not
     * show the piece afterwards gets no second one. Never on a red price.
     *
     * The shop it ends on: [shop] -- or the last one read after a purchase --
     * as it was, where nothing was tapped since; read again, WINDOW_WAIT long,
     * only where a dialog was opened and closed. Live on 2026-11-01, instance
     * 1 at 220 Camp Coins, four Gates in a row skipped on their pink price
     * each asked the untouched shop again within LOOK_WAIT (then 1.6 s, two looks):
     * three reads came at 2.5 to 3 s apart, and the fourth missed its two
     * frames and parked the pass with "The Camp Shop went away" over the
     * standing shop (PLAN_CAMP.md C4).
     */
    private fun buy(item: String, card: Camp.Card, shop: Camp.ShopWindow): Camp.ShopWindow {
        val label = SkillSettings.campLabel(item)
        var c = card
        var last = shop
        var touched = false
        while (true) {
            if (c.soldOut || c.left?.first == 0) {
                log("camp: $label: sold out")
                break
            }
            if (c.affordable == false) {
                log("camp: not enough Camp Coins for $label -- its price is red")
                break
            }
            log("camp: $label -- its purchase dialog")
            touched = true
            act(c.target)
            val d = waitTwice(WINDOW_WAIT) { Camp.buyDialog(it) }
            if (d == null) {
                log("camp: the purchase dialog of $label did not open")
                keepNow("camp_no_dialog")
                break
            }
            if (!d.affordable) {
                log("camp: not enough Camp Coins for $label")
                close(Layer.DIALOG)
                break
            }
            if (d.count != null && d.count != 1) {
                log("camp: the purchase dialog of $label opened at ${d.count}, not 1 -- nothing bought")
                keepNow("camp_dialog_count")
                close(Layer.DIALOG)
                break
            }
            log("camp: buying $label")
            act(d.buy)
            val sheet = waitSheet()
            if (sheet) {
                bought += 1
                log("camp: bought $label ($bought)")
                closeSheet()
            } else if (waitTwice(LOOK_WAIT) { Camp.buyDialog(it) } != null) {
                log("camp: the purchase of $label did not take -- not tried again")
                keepNow("camp_buy_no_effect")
                close(Layer.DIALOG)
                break
            }
            val after = shopNow() ?: throw blocked("camp_no_shop", "The Camp Shop did not come back after a purchase.")
            last = after
            touched = false
            val card2 = after.cards.firstOrNull { near(it.target, c.target) }
            val before = c.left
            val shows = before != null && card2 != null && (card2.soldOut || card2.left?.first == before.first - 1)
            if (!sheet) {
                if (!shows) {
                    log("camp: the purchase of $label did not show -- not tried again")
                    keepNow("camp_buy_unshown")
                    return after
                }
                bought += 1
                log("camp: bought $label ($bought)")
            } else if (before != null && !shows) {
                log("camp: the purchase of $label did not show on its card -- no second one")
                keepNow("camp_buy_unshown")
                return after
            }
            if (before == null || card2 == null || card2.soldOut || card2.item != item) return after
            c = card2
        }
        if (!touched) return last
        return shopNow() ?: throw blocked("camp_no_shop", "The Camp Shop went away.")
    }

    private fun near(a: Explore.Target, b: Explore.Target) = abs(a.fx - b.fx) <= NEAR && abs(a.fy - b.fy) <= NEAR

    // ------------------------------------------------------------------------
    // The stations (3.3, point 3)
    // ------------------------------------------------------------------------
    /**
     * Dojo, then Drill Site -- or, from a station's window the player opened
     * ([start]), that station first; a Reward Boxes open over it is closed
     * onto it to see which one it is.
     */
    private fun stations(start: Start?) {
        var first = start?.which
        if (start != null && first == null) {
            if (waitTwice(LOOK_WAIT) { CampStation.boxesWindow(it) } != null) close(Layer.BOXES)
            first = waitTwice(LOOK_WAIT) { CampStation.window(it) }?.which
        }
        val order = if (first == CampStation.Which.DRILL) listOf(CampStation.Which.DRILL, CampStation.Which.DOJO)
                    else listOf(CampStation.Which.DOJO, CampStation.Which.DRILL)
        var done = true
        for ((i, which) in order.withIndex()) {
            if (!station(which)) done = false
            if (i == 0) toCamp()
        }
        if (done) {
            log("camp: both stations are done for today")
            stamp(Part.STATIONS)
        }
    }

    /**
     * One station: its window on two frames with the right picture, then its
     * attempts -- Auto Clear, or Attempt by the page's switch, and at a
     * station without a record the one Attempt of question 9 -- each proved by
     * its counter one less on two frames, then its Reward Boxes. True where
     * the station is done for the day.
     */
    private fun station(which: CampStation.Which): Boolean {
        val name = STATION_NAMES.getValue(which)
        var w = open(name, WINDOW_WAIT, { if (which == CampStation.Which.DOJO) it.dojo else it.drill }) {
            CampStation.window(it)
        } ?: return false
        if (w.which != which) {
            log("camp: that is ${w.which?.let { STATION_NAMES[it] } ?: "a station I cannot tell"}, not $name -- left alone")
            keepNow("camp_wrong_station")
            return false
        }
        var done = true
        while (true) {
            val counter = w.attempts
            if (counter == null) {
                log("camp: $name: its counter does not read -- no attempt spent")
                keepNow("camp_no_counter")
                done = false
                break
            }
            val n = counter.first
            if (n == 0) {
                log("camp: $name: 0/${counter.second} -- no attempts left today")
                break
            }
            val s = settings()
            val next: CampStation.StationWindow? = when {
                s.attempt -> if (w.attempt.lit) attempt(name, w) else {
                    log("camp: $name: Attempt is dim at $n/2 -- nothing tapped")
                    null
                }
                w.autoClear.lit -> autoClear(name, w)
                w.attempt.lit && which !in s.attempted -> {
                    log("camp: Auto Clear is dim at $name: it lights once the station has a record -- " +
                        "one Attempt there first, as you asked")
                    attempted(which)
                    attempt(name, w)?.also { after ->
                        if ((after.attempts?.first ?: 0) > 0 && !after.autoClear.lit) {
                            log("camp: Auto Clear stayed dim after the Attempt at $name -- no second Attempt")
                        }
                    }?.takeIf { (it.attempts?.first ?: 0) == 0 || it.autoClear.lit }
                }
                w.attempt.lit -> {
                    log("camp: Auto Clear is dim at $name, and its one Attempt of the day is spent -- nothing tapped")
                    break
                }
                else -> {
                    log("camp: $name: Auto Clear and Attempt are both dim at $n/2 -- nothing tapped")
                    null
                }
            }
            if (next == null) {
                done = false
                break
            }
            w = next
        }
        boxes(name)
        return done
    }

    /**
     * Auto Clear on [w], read on the frame just before, at n/2: a tap, and
     * its proof -- the Reward sheet (C2: under 1 s, the counter at n-1 behind
     * it), counted before it is closed, or the counter at n-1 on two frames.
     * After the sheet the counter is read at n-1 on two frames, or the
     * station is left. A tap that changes nothing is made once more while the
     * button still reads lit; then "Auto Clear did not take" (3.3, point 3).
     */
    private fun autoClear(name: String, w: CampStation.StationWindow): CampStation.StationWindow? {
        val n = w.attempts!!.first
        val less = { img: Mat -> CampStation.window(img)?.takeIf { it.attempts?.first == n - 1 } }
        var aim = w.autoClear.target
        for (i in 0 until 2) {
            log("camp: $name -- Auto Clear ($n/2)")
            act(aim)
            if (waitSheet()) {
                cleared += 1
                log("camp: $name cleared by Auto Clear ($cleared) -- ${n - 1}/2 left")
                closeSheet()
                return waitTwice(SHEET_GONE, read = less) ?: run {
                    log("camp: $name: the sheet came, and the counter does not say ${n - 1}/2")
                    keepNow("camp_clear_unshown")
                    null
                }
            }
            val after = waitTwice(SHEET_GONE, read = less)
            if (after != null) {
                cleared += 1
                log("camp: $name cleared by Auto Clear ($cleared) -- ${n - 1}/2 left")
                return after
            }
            if (i == 1) break
            aim = waitTwice(LOOK_WAIT) { CampStation.window(it)?.takeIf { s -> s.attempts?.first == n && s.autoClear.lit } }
                ?.autoClear?.target ?: break
            log("camp: Auto Clear changed nothing at $name -- once more")
        }
        log("camp: Auto Clear did not take at $name")
        keepNow("camp_clear_no_effect")
        return null
    }

    /**
     * Attempt on [w], read on the frame just before, at n/2: the battle that
     * plays itself (black, the VS screen, about 42 s), the Results on two
     * frames, Close, "Now Loading", and the station window with the counter at
     * n-1 on two frames. Nothing in the battle is tapped -- never Give Up.
     */
    private fun attempt(name: String, w: CampStation.StationWindow): CampStation.StationWindow? {
        val n = w.attempts!!.first
        log("camp: $name -- Attempt ($n/2): a battle that plays itself, about 42 s")
        act(w.attempt.target)
        val res = waitTwice(RESULTS_WAIT) { CampStation.results(it)?.takeIf { r -> r.close.lit } }
            ?: throw blocked("camp_no_results", "The Attempt at $name did not end in its Results window.")
        // The Results are the attempt's witness, counted before Close.
        cleared += 1
        log("camp: $name: Attempt played ($cleared)")
        return closeResults(res, name, n)
    }

    /**
     * Close on the Results [res], read on the frame just before, and the
     * station window back on two frames -- with its counter at [n]-1 where
     * the pass knows [n]. A Results the pass met at its start (after a pause)
     * was counted by the pass that saw it come.
     */
    private fun closeResults(res: CampStation.Results, name: String?, n: Int? = null): CampStation.StationWindow? {
        log("camp: closing the Results")
        act(res.close.target)
        val back = waitTwice(RELOAD_WAIT) { img ->
            CampStation.window(img)?.takeIf { n == null || it.attempts?.first == n - 1 }
        }
        if (back == null) {
            if (n != null && waitTwice(LOOK_WAIT) { CampStation.window(it) } != null) {
                log("camp: the Attempt at $name did not show on its counter")
                keepNow("camp_attempt_unshown")
                return null
            }
            throw blocked("camp_no_station", "The station window did not come back after the Results.")
        }
        val at = name ?: back.which?.let { STATION_NAMES[it] } ?: "the station"
        log("camp: $at: back from the Results" + (back.attempts?.let { " -- ${it.first}/${it.second} left" } ?: ""))
        return back
    }

    /** The station's Reward Boxes: Claim Rewards where it is lit, proved dim on two frames; closed onto the station. */
    private fun boxes(name: String) {
        val w = waitTwice(LOOK_WAIT) { CampStation.window(it) } ?: return
        log("camp: $name -- Reward Boxes")
        act(w.rewardBoxes)
        val b = waitTwice(WINDOW_WAIT) { CampStation.boxesWindow(it) }
        if (b == null) {
            log("camp: the Reward Boxes of $name did not open")
            keepNow("camp_no_boxes")
            return
        }
        if (!b.claim.lit) {
            log("camp: $name: nothing to claim in the Reward Boxes")
        } else {
            press("Claim Rewards", b.claim.target,
                  again = { CampStation.boxesWindow(it)?.claim?.takeIf { c -> c.lit }?.target },
                  done = { CampStation.boxesWindow(it)?.claim?.lit == false }) {
                val got = b.available?.let { it.first + it.second }?.takeIf { it > 0 } ?: 1
                boxes += got
                log("camp: $name: $got reward box${if (got == 1) "" else "es"} claimed")
            }
        }
        close(Layer.BOXES)
    }

    // ------------------------------------------------------------------------
    // Windows: open, close, back to the Camp screen, home
    // ------------------------------------------------------------------------
    /**
     * The window [read] answers on, on two frames: where it is open already
     * (the player's), as it is; else [target] tapped on the Camp screen read
     * on two frames just before. Null, said and kept, where it does not open.
     */
    private fun <T> open(what: String, wait: Double, target: (Camp.CampScreen) -> Explore.Target, read: (Mat) -> T?): T? {
        waitTwice(LOOK_WAIT, read = read)?.let { return it }
        val screen = waitTwice(OPEN_WAIT) { Camp.screen(it) }
            ?: throw blocked("camp_no_screen", "The Camp screen did not come back.")
        log("camp: $what")
        act(target(screen))
        return waitTwice(wait, read = read) ?: run {
            log("camp: $what did not open")
            keepNow("camp_not_open")
            null
        }
    }

    /** The layers of the Camp, each with its own reader; closed by the dimmed field, the sheet by its words. */
    private enum class Layer { SHEET, RESULTS, DIALOG, SUPPORT, TENT, SHOP, BOXES, STATION }

    private class Open(val layer: Layer, val close: Explore.Target)

    /** The top layer of the Camp in [img] and where it is closed, or null: the Camp screen, or nothing of the Camp's. */
    private fun layerOf(img: Mat): Open? {
        if (Dungeon.rewardSheet(img)) return Open(Layer.SHEET, SHEET_CLOSE)
        CampStation.results(img)?.let { r -> return if (r.close.lit) Open(Layer.RESULTS, r.close.target) else null }
        return when {
            Camp.buyDialog(img) != null -> Open(Layer.DIALOG, Camp.CLOSE_SPOT)
            Camp.supportWindow(img) != null -> Open(Layer.SUPPORT, Camp.CLOSE_SPOT)
            Camp.tentWindow(img) != null -> Open(Layer.TENT, Camp.CLOSE_SPOT)
            Camp.shopWindow(img) != null -> Open(Layer.SHOP, Camp.CLOSE_SPOT)
            CampStation.boxesWindow(img) != null -> Open(Layer.BOXES, Camp.CLOSE_SPOT)
            CampStation.window(img) != null -> Open(Layer.STATION, Camp.CLOSE_SPOT)
            else -> null
        }
    }

    private fun reads(layer: Layer, img: Mat): Boolean = layerOf(img)?.layer == layer

    /**
     * The [layer] in front closed -- read on a fresh frame and closed at its
     * own spot, then waited for until it is gone; once more after CLOSE_WAIT
     * where it still reads, never where it does not (a tap there would land on
     * the window under it).
     */
    private fun close(layer: Layer) {
        for (i in 0 until CLOSE_TRIES) {
            val img = grab()
            val open = try { layerOf(img) } finally { img.release() }
            // A sheet that came late stands over the window: its words first.
            if (open?.layer == Layer.SHEET && layer != Layer.SHEET) {
                closeSheet()
                continue
            }
            if (open == null || open.layer != layer) return
            act(open.close)
            val start = now()
            while (now() - start < CLOSE_WAIT) {
                sleep(BEAT * patience)
                val f = grab()
                val still = try { reads(layer, f) } finally { f.release() }
                if (!still) return
            }
        }
        throw blocked("camp_no_close", "A window of the Camp did not close.")
    }

    /**
     * "Tap to close" on the sheet, and the sheet waited out: a second tap only
     * after SHEET_GONE with the sheet still there, so that it cannot land on
     * the window under it (ExMissionsSkill.closeSheet).
     */
    private fun closeSheet() {
        for (i in 0 until SHEET_TAPS) {
            act(SHEET_CLOSE)
            val start = now()
            while (now() - start < SHEET_GONE) {
                sleep(BEAT * patience)
                val img = grab()
                val still = try { Dungeon.rewardSheet(img) } finally { img.release() }
                if (!still) return
            }
        }
        throw blocked("camp_sheet_stays", "The Reward sheet does not close.")
    }

    /** Back to the Camp screen on two frames, the layers over it closed one at a time. */
    private fun toCamp() {
        var taps = 0
        var seen = false
        val begin = now()
        while (now() - begin < BACK_WAIT) {
            val img = grab()
            val screen: Boolean
            val open: Open?
            try {
                screen = Camp.screen(img) != null
                open = if (screen) null else layerOf(img)
            } finally {
                img.release()
            }
            if (screen) {
                if (seen) return
                seen = true
                sleep(BEAT * patience)
                continue
            }
            seen = false
            if (open == null || taps >= BACK_TAPS) {
                sleep(BEAT * patience)
                continue
            }
            taps += 1
            if (open.layer == Layer.SHEET) closeSheet() else close(open.layer)
        }
        throw blocked("camp_not_back", "The Camp screen did not come back.")
    }

    /** Home at the end of [run]: the main screen, or a park with the frame kept. */
    private fun home(): Outcome {
        stays.reset()
        if (goHome()) {
            log("camp: back on the main screen")
            return Outcome.DONE
        }
        if (!on()) return Outcome.STOPPED
        if (stays.met != null) return Outcome.DONE
        keepNow("no_way_home")
        return Outcome.parked("The Camp did not close, and I found no way home from it.")
    }

    /**
     * Back to the plain main screen from anywhere in the Camp: a layer closed
     * where it reads, the globe on the Camp screen, each a few times at most
     * and each only where it reads on the frame just before; gated on the
     * switch, and a window of the game's own over the way is the director's
     * ([Stays.over]). True once the auto button is crisp.
     */
    private fun goHome(): Boolean {
        var taps = 0
        var globes = 0
        val start = now()
        while (now() - start < HOME_WAIT) {
            if (stays.now()) return false
            val img = grab()
            var aim: Explore.Target? = null
            try {
                if (stays.over(img)) return false
                if (Dungeon.autoButton(img) != null) return true
                if (Camp.screen(img) != null) {
                    val globe = Dungeon.homeButton(img)
                    if (globe != null && globes < HOME_PRESSES) {
                        log("camp: pressing the home button")
                        aim = Explore.Target(globe.fx, globe.fy)
                        globes += 1
                    }
                } else {
                    val open = layerOf(img)
                    if (open != null && taps < HOME_TAPS) {
                        aim = open.close
                        taps += 1
                    }
                }
            } finally {
                img.release()
            }
            if (aim != null) act(aim)
            sleep(PAUSE * patience)
        }
        return false
    }

    /**
     * The pass cannot go on: the frame kept under [tag], and a park with [why]
     * -- or, where a dialog of the game's stands in front, the sentence of
     * question 10: nothing is tapped on a window the pass did not open.
     */
    private fun blocked(tag: String, why: String): Park {
        val img = try { cap.grab() } catch (e: CaptureError) { return Park(why) }
        try {
            if (Director.classify(img).screen == Director.DIALOG) {
                keep(img, "camp_dialog")
                log("camp: a window of the game's is over the Camp -- nothing tapped on it")
                return Park(DIALOG_OVER)
            }
            keep(img, tag)
        } finally {
            img.release()
        }
        return Park(why)
    }

    // ------------------------------------------------------------------------
    // Frames and taps
    // ------------------------------------------------------------------------
    private fun grab(): Mat = cap.grab().also { rect = Dungeon.gameRect(it); room = Dungeon.headroom(it) }

    /**
     * [read] answering on two fresh frames running within [wait], the same by
     * [key] on both; the second answer, or null.
     */
    private fun <T> waitTwice(wait: Double, key: (T) -> Any? = { Unit }, read: (Mat) -> T?): T? {
        var before: Any? = null
        var seen = false
        val start = now()
        while (now() - start < wait) {
            sleep(BEAT * patience)
            val img = grab()
            try {
                val v = read(img)
                if (v != null) {
                    val k = key(v)
                    if (seen && k == before) return v
                    before = k
                    seen = true
                } else {
                    seen = false
                }
            } finally {
                img.release()
            }
        }
        return null
    }

    /**
     * After a tap that gives a reward: the Reward sheet on two frames running
     * within SHEET_WAIT; true where it came, left standing for the caller to
     * count and close. Waited for whatever the window under it already says:
     * the game changes the button under the incoming sheet (the EX Missions
     * list was sorted anew under it, notes/missions.md), and a proof read
     * before the sheet would leave the sheet standing over the next look.
     */
    private fun waitSheet(): Boolean = waitTwice(SHEET_WAIT) { if (Dungeon.rewardSheet(it)) true else null } != null

    private fun keepNow(tag: String) {
        val img = try { cap.grab() } catch (e: CaptureError) { return }
        try {
            keep(img, tag)
        } finally {
            img.release()
        }
    }

    /** A tap, with the main switch asked first: off, nothing is tapped and the pass ends ([Stop]). */
    private fun act(target: Explore.Target) {
        if (!on()) throw Stop()
        val r = rect ?: return
        val y0 = r.y0 - Py.roundInt(room * target.anchor.share)
        cap.tap(Py.roundInt(r.x0 + target.fx * r.gw), Py.roundInt(y0 + target.fy * r.gh))
    }

    /** A swipe along the shop's middle column from [fy0] to [fy1], in the window's rectangle; the switch asked first. */
    private fun swipe(fy0: Double, fy1: Double) {
        if (!on()) throw Stop()
        val r = rect ?: return
        val y0 = r.y0 - Py.roundInt(room * Camp.WINDOW.share)
        val x = Py.roundInt(r.x0 + SWIPE_FX * r.gw)
        cap.swipe(x, Py.roundInt(y0 + fy0 * r.gh), x, Py.roundInt(y0 + fy1 * r.gh), SWIPE_MS)
    }

    companion object {
        const val KEY = "camp"

        /** The director's five screens of the Camp (PLAN_CAMP.md 3.2). */
        val SCREENS = setOf(Director.CAMP, Director.CAMP_TENT, Director.CAMP_SHOP,
                            Director.CAMP_STATION, Director.CAMP_BOXES)

        /** Why a chain step with all four parts stamped is skipped. */
        const val NO_BUDGET = "done for today -- the tent, the shop and both stations; again after the reset at 8:00"

        /** Question 10: a window of the game's over the Camp, not measured, is the player's. */
        const val DIALOG_OVER = "A window of the game's is over the Camp, and I do not know it: close it yourself. " +
            "I tap nothing on a window I did not open."

        /** The stations' names as the game writes them, for the log. */
        val STATION_NAMES = mapOf(CampStation.Which.DOJO to "Leomon's Dojo",
                                  CampStation.Which.DRILL to "Piximon's Drill Site")

        /** Between two looks: the screenshot floor is 350 ms (notes/director.md, "`takeScreenshot` has a floor"). */
        const val BEAT = 0.5
        /** After a tap on the way home. */
        const val PAUSE = 1.0

        // The waits, each five times the longest C1 and C2 measured
        // (PLAN_CAMP.md 6; ExMissionsSkill's rule).
        /**
         * The start from the plain main screen, as every way in waits for it
         * (ExMissionsSkill.START_WAIT): the tab is read only where the auto
         * button is.
         */
        const val START_WAIT = MainScreen.WAIT
        /**
         * The Camp tab to the Camp screen, and a Camp screen after a window
         * closed. Not timed on its own by C1; the tent's 1.8 s, five times
         * over, as the windows below.
         */
        const val OPEN_WAIT = 9.0
        /** The tent to its window: under 1.8 s (C1). */
        const val TENT_WAIT = 9.0
        /** Support to the Camp Support window: under 1.0 s (C1). */
        const val SUPPORT_WAIT = 5.0
        /**
         * The shop, a purchase dialog, a station, its Reward Boxes: not timed
         * one by one (C1, C2: "opens", within the frames of the measurement);
         * the tent's wait.
         */
        const val WINDOW_WAIT = 9.0
        /**
         * What is open already at the start of a part: two looks and a little
         * over. 1.6 s until 2026-11-01, when it missed the Camp Shop standing
         * open on instance 1: a look at the shop takes up to 1.26 s there (the
         * director's "screen: camp_shop (1080 x 1920, 1264 ms)"), so two looks
         * with their beats are 2 x (0.5 + 1.26) = 3.5 s, and the pass that
         * found the shop open waited for the Camp screen under it and parked
         * ("The Camp screen did not come back", PLAN_CAMP.md C4).
         */
        const val LOOK_WAIT = 4.0
        /** A window to the one under it: every camp window closed in under 1.0 s (C1, C2). */
        const val CLOSE_WAIT = 5.0
        const val CLOSE_TRIES = 3
        /**
         * A tap to the Reward sheet: Support All 1.0 to 1.5 s, the purchase
         * 1.3 s, Auto Clear under 1 s (C1, C2).
         */
        const val SHEET_WAIT = 7.5
        /** "Tap to close" to the window under it: the purchase 1.2 s, Support 0.8 s, a station 0.5 s. */
        const val SHEET_GONE = 6.0
        const val SHEET_TAPS = 3
        /** Attempt to its Results: 46 s (C2, 11:31:58 to 11:32:44). */
        const val RESULTS_WAIT = 5 * 46.0
        /** The Results' Close to the station window, through "Now Loading": 3.5 s (C2). */
        const val RELOAD_WAIT = 5 * 3.5
        /** Back to the Camp screen, layer by layer: a few windows and a sheet, each under 1.2 s. */
        const val BACK_WAIT = 20.0
        const val BACK_TAPS = 6
        const val HOME_WAIT = 12.0
        const val HOME_TAPS = 6
        const val HOME_PRESSES = DungeonSkill.HOME_PRESSES_MAX
        const val NEUTRAL_TRIES = 3
        /** The looks at the start of a work for what stands in front, a sheet or the Results closed between. */
        const val SETTLE_LOOKS = 6

        // The shop's list: three of its five rows in sight, and the scroll
        // position kept between two openings (C1, 6). A swipe along the
        // middle column, from a card's height to the bottom row's, slow
        // enough to be a drag; not measured live (C4 sees it).
        const val SWIPE_FX = 0.476
        const val SWIPE_HIGH = 0.40
        const val SWIPE_LOW = 0.78
        const val SWIPE_MS = 600L
        /** After a swipe, before its two frames are asked. */
        const val SWIPE_SETTLE = 1.0
        const val SWIPES_MAX = 3
        /** A card after a purchase is the card at the same place: within this of it (the cards stand 0.19 apart). */
        const val NEAR = 0.03

        /** "Tap to close" on the Reward sheet, where ExMissionsSkill taps it. */
        val SHEET_CLOSE = ExMissionsSkill.SHEET_CLOSE

        /** The director's spot that opens nothing, in its rectangle: what takes the Stage Failed banner away. */
        val NEUTRAL = Explore.Target(Director.NEUTRAL_TAP_FX, Director.NEUTRAL_TAP_FY)
    }
}
