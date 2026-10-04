package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * Presets: set the game's preset at up to six places to the slot the page
 * names (PLAN_PRESET_SWITCH.md). The player numbers their presets the same
 * way everywhere -- slot 1 bosses, slot 2 PvP, whatever they like -- and
 * this walks the places and taps the slot; it reads no name.
 *
 * Only ever asked: the page's "Switch now" hands the director the key
 * ([DirectorLoop.runNow]) and the director calls [run] on the next plain
 * main screen. A preset page the player opens is theirs -- they may be
 * there to change a preset by hand -- so [seesWork] says "nothing to do"
 * on every one and [work] is never handed a screen.
 *
 * Every place is one visit from the main screen and back: open it, set it,
 * come home. A place that cannot be opened or set is said and passed over,
 * and the pass ends PARKED naming it, on the main screen; the others are
 * still set. What proves a switch is the bar afterwards -- the text of the
 * row that was tapped (compact) or the name on its head (wide), compared as
 * pictures ([Preset.sameText]) -- never the tap.
 */
class PresetSkill(
    private val cap: Capture,
    private val settings: () -> Settings,
    private val log: (String) -> Unit = { HelperLog.line(it) },
    /** The main switch, asked between two places and never in the middle of one. */
    private val on: () -> Boolean = { MainSwitch.on },
    private val keep: (Mat, String) -> Unit = { _, _ -> },
    private val sleep: (Double) -> Unit = { s -> if (s > 0) Thread.sleep((s * 1000).toLong()) },
) : Skill {

    /** The slot per place, 1 to 10, from the profile armed last (Stored.armPreset); 0 is a place never armed. */
    data class Settings(val slots: Map<Preset.Place, Int> = emptyMap()) {
        /** The places the page asks for, in the order they are walked. */
        val wanted: List<Preset.Place>
            get() = Preset.Place.values().filter { (slots[it] ?: 0) in 1..Preset.ROWS }
    }

    override val key = "preset"
    override val name = "Presets"

    /** Its own screen, so that the chain's second hand can ask it to [leave]. */
    override fun worksOn(screen: String): Boolean = screen == Director.PRESET

    override fun hasBudget(): Boolean = settings().wanted.isNotEmpty()

    /** Nothing, on any preset page: the player's own, unless the page's button asked. */
    override fun seesWork(screen: String, img: Mat): Boolean? = if (screen == Director.PRESET) false else null

    override fun work(img: Mat): Outcome = Outcome.DONE

    companion object {
        // The icons on the main screen and the sub-tabs, in window space:
        // where the taps that worked on LDPlayer (1080 x 1920) on 2026-09-25
        // stood. Constants, not readers -- the arrival is checked by the
        // place's own bar ([Preset.place]) and a miss taps nothing further.
        // The Digivice and the Tactical Memory icon stand in the row above
        // the field, the Digivice leftmost, the USB stick rightmost.
        val DIGIVICE_ICON = Explore.Target(0.1683, 0.7712)
        val TACTICAL_ICON = Explore.Target(0.7803, 0.7717)
        // The Overdrive icon, the tile left of the USB stick. A constant and
        // not a reader, because the row stands still on every display
        // (PLAN_DAILY_LOST_SECTOR_PRESETS.md 4.1 point 1). Measured on
        // 2026-09-29 over 90 main screens -- every main_* of corpus/formats
        // and corpus/tall and the seven format rows of the Overdrive
        // measurement, 720 x 1280 to 2136 x 3200, cutouts, side insets,
        // tablets, landscape, V3 rows cut and whole -- the light-blue tiles
        // of the row (H 95-108, S 170-245, V 200+) in window fractions:
        //
        //   tile         middle fx        middle fy        w              h
        //   Digivice     0.1695-0.1704    0.7745-0.7748    0.0764-0.0785  0.0432-0.0443
        //   Crests       0.2606-0.2613    0.7745-0.7751    0.0759-0.0778  0.0435-0.0443
        //   Overdrive    0.6904-0.6912    0.7745-0.7748    0.0764-0.0779  0.0432-0.0443
        //   Tactical     0.7821-0.7827    0.7745-0.7751    0.0762-0.0777  0.0435-0.0443
        //
        // A spread of 0.0008 against a tile 0.077 wide, so a tap at the
        // middle lands inside on every format; the two frames it misses on
        // are the `double84` row, the game drawn for the display before
        // (notes/formats.md, "A frame taken right after a display change").
        // The Tactical Memory tile beside it is 0.091 away: a tap that landed
        // there would open Tactical Memory, which the arrival check refuses
        // (the page is known by its tab row, Preset.overdriveTabs).
        val OVERDRIVE_ICON = Explore.Target(0.6908, 0.7747)
        val EAT_CARD = Explore.Target(0.2825, 0.7672)
        // The Tamer tab opened on Skill Cards both times; the Digimon tab
        // opened on Buddy three times, whatever was open before.
        val SKILL_CARDS_SUBTAB = Explore.Target(0.4760, 0.8783)
        val SUPPORT_SUBTAB = Explore.Target(0.3793, 0.8773)
        // The Tamer label, from the nav bar's own table (Explore.NAV_DIGIMON_DX).
        const val NAV_TAMER_DX = -0.340
        // A wide list is scrolled by short drags in its middle: 0.14 of the
        // height, about one row -- a 500 px drag moved it 650 px, it runs on.
        const val SCROLL_FX = 0.47
        val SCROLL_FY = doubleArrayOf(0.70, 0.56)
        const val SCROLL_MS = 500L
        const val SCROLL_MAX = 12
        const val NAV_ROUNDS = 12
        const val NAV_TAPS_MAX = 3
        const val HOME_STEPS = 6
        const val PAUSE = 1.0
        // The start is two main-screen frames running, at most this many
        // looks a PAUSE apart. Live on LDPlayer 2026-09-27 (PLAN_FORMATE.md
        // S4 round 5, the V14 proof) "Switch to" parked with "Presets start
        // from the main screen" in the very round the director had read
        // `main`: the director takes the first main frame after the globe
        // (MAIN moves by itself, so its gate is open at once), and the one
        // frame `run` then took failed `autoButton`; the second try went
        // through. Never trust a single frame -- either way round. Five
        // looks leave a frame or two of the way home room to pass and still
        // park within about five seconds on a screen that is not the main.
        const val START_ROUNDS = 5
        // From a compact bar's top to under its list's tenth row, in window
        // fy: 0.3939 on the Digivice at 1920 (the bar 0.0985 to 0.1313, ten
        // rows at a pitch of one bar, one more for the gap under the bar,
        // as V13 cleared off the bar that was read), and a tenth more on
        // Food Effects, whose rows are 57 at 70 against 51 at 64.
        const val COMPACT_SPAN = 0.44
    }

    /** What one pass did, place by place. */
    class Stats {
        var switched = 0
        var already = 0
        val failed = ArrayList<String>()
    }

    private var rect: Dungeon.GameRect? = null
    /** The headroom of the frame [rect] was taken off (Dungeon.CanvasFrame). */
    private var room = 0
    private var stats = Stats()
    /** The place being walked, for the way home out of its open list. */
    private var current: Preset.Place? = null

    /** What the last pass counted, for the TODAY card ([SkillStats], [Counted]). */
    var lastCounts: Map<String, Int> = emptyMap()
        private set

    private fun grab(): Mat = cap.grab().also { rect = Dungeon.gameRect(it); room = Dungeon.headroom(it) }

    /**
     * A tap by fraction of the reference window, in the rectangle the target
     * was read in ([Explore.Target.anchor], Dungeon.Anchor).
     */
    private fun tap(target: Explore.Target) {
        // Not with the main switch off: the service would hold it back, and
        // the pass stops after the visit it belongs to ([places]).
        if (!on()) return
        val r = rect ?: return
        val y0 = r.y0 - Py.roundInt(room * target.anchor.share)
        cap.tap(Py.roundInt(r.x0 + target.fx * r.gw), Py.roundInt(y0 + target.fy * r.gh))
    }

    /** A frame kept, but not where the switch cut the step short: nothing failed there (PLAN_RELEASE_1_3.md B4). */
    private fun kept(img: Mat, tag: String) {
        if (on()) keep(img, tag)
    }

    private fun <T> onFrame(read: (Mat) -> T): T {
        val img = grab()
        try {
            return read(img)
        } finally {
            img.release()
        }
    }

    /** [check] true on two frames running, at most [rounds] looks; the frame that confirmed it, or null. */
    private fun waitFor(rounds: Int = NAV_ROUNDS, check: (Mat) -> Boolean): Mat? {
        var confirmed = false
        for (i in 0 until rounds) {
            val img = grab()
            if (check(img)) {
                if (confirmed) return img
                confirmed = true
            } else {
                confirmed = false
            }
            img.release()
            sleep(PAUSE)
        }
        return null
    }

    /**
     * Tap a constant and wait for [check], up to [NAV_TAPS_MAX] times -- the
     * second and third time only where [from], the screen the constant
     * stands on, is still in front. Live on 2026-10-01 (PLAN_RELEASE_1_3.md
     * B73) the first tap on the Tactical Memory icon opened the page with the
     * game's Help tutorial over it, the bar was never read, and the two taps
     * after it went to the icon's place on the tutorial (889,1471, a hand's
     * breadth right of its Next): never click blindly.
     */
    private fun tapUntil(target: Explore.Target, from: ((Mat) -> Boolean)? = null, check: (Mat) -> Boolean): Mat? {
        for (i in 0 until NAV_TAPS_MAX) {
            if (i > 0 && from != null && !onFrame(from)) return null
            tap(target)
            waitFor(check = check)?.let { return it }
        }
        return null
    }

    /** Tap what [read] finds on a fresh frame and wait for [check]; a frame it finds nothing on is not tapped. */
    private fun tapRead(read: (Mat) -> Explore.Target?, check: (Mat) -> Boolean): Mat? {
        for (i in 0 until NAV_TAPS_MAX) {
            val target = onFrame(read) ?: return null
            tap(target)
            waitFor(check = check)?.let { return it }
        }
        return null
    }

    private fun isMain(img: Mat) = Dungeon.autoButton(img) != null
    private fun at(place: Preset.Place, img: Mat) = Preset.place(img)?.first == place
    /** A page of the nav bar that is not the main screen: the Tamer or the Digimon tab. */
    private fun onTab(img: Mat) = !isMain(img) && Dungeon.homeButton(img) != null
    private fun navTab(dx: Double): (Mat) -> Explore.Target? =
        { img -> if (isMain(img)) Explore.navTab(img, dx)?.let { Explore.Target(it.fx, it.fy) } else null }

    // ------------------------------------------------------------------------
    override fun run(): Outcome {
        carried = false
        donePlaces.clear()
        failedPlaces.clear()
        return places()
    }

    /**
     * The places of the page that are left, each one visit from the main
     * screen and home. Those this pass set before a pause ([donePlaces]) are
     * not visited again; the one it stopped in is visited again from the
     * start, which a preset allows -- a place on its slot says "already".
     */
    private fun places(): Outcome {
        stats = Stats()
        lastCounts = emptyMap()
        stays.reset()
        val wanted = settings()
        // Two main-screen frames running, not one (START_ROUNDS).
        val start = try { waitFor(rounds = START_ROUNDS, check = ::isMain) } catch (e: CaptureError) {
            return Outcome.noFrame(e)
        }
        if (start == null) {
            if (!on()) return stop(null)
            return Outcome.parked("Presets start from the main screen")
        }
        start.release()
        for (place in wanted.wanted) {
            if (place in donePlaces) continue
            if (!on()) return stop(place)
            val slot = wanted.slots[place]!!
            current = place
            clearFor(place)
            try {
                val said = try {
                    visit(place, slot)
                } catch (e: CaptureError) {
                    "no frame: ${e.message}"
                }
                // A visit the switch cut short did not fail: it stops where
                // it stands, and the pass goes on with this place after the
                // pause (PLAN_RELEASE_1_3.md B4).
                if (!on()) return stop(place)
                if (said != null) {
                    stats.failed.add(place.label)
                    failedPlaces += place
                    log("presets: ${place.label} not set to slot $slot -- $said")
                }
                donePlaces += place
                if (!goHome()) {
                    if (!on()) return stop(null)
                    current = null
                    finish()
                    // One of the game's own windows over the page -- the Help
                    // tutorial of a first visit (PLAN_RELEASE_1_3.md B73) -- is
                    // said for what it is: nothing here taps it, and the
                    // places after this one wait for the next "Switch to".
                    stays.met?.let { return Outcome.parked(helpWhy(it, place)) }
                    return Outcome.parked("could not get home from ${place.label}")
                }
            } finally {
                if (place.kind == Preset.Kind.COMPACT) cap.overlayBack()
            }
            current = null
        }
        finish()
        val failed = failedPlaces.map { it.label }
        carried = false
        if (failed.isNotEmpty()) return Outcome.parked("not set: " + failed.joinToString(", "))
        return Outcome.DONE
    }

    /** The switch stopped the pass, before [place] or inside it: said, and the pass one to go on with. */
    private fun stop(place: Preset.Place?): Outcome {
        log("presets: stopped" + (place?.let { " at ${it.label}" } ?: ""))
        finish()
        carried = true
        return Outcome.STOPPED
    }

    // ------------------------------------------------------------------------
    // After a pause (PLAN_RELEASE_1_3.md B4, the player's rule of 2026-09-30)
    // ------------------------------------------------------------------------
    /** The last pass was stopped by the main switch and has not been gone on with or begun afresh since. */
    internal var carried = false
        private set
    /** Places this pass has visited to their end, set or not. */
    private val donePlaces = HashSet<Preset.Place>()
    /** Places this pass could not set, for its park at the end. */
    private val failedPlaces = LinkedHashSet<Preset.Place>()
    /** Every way home asks the switch first ([Stays]). */
    private val stays = Stays(on) { log(it) }

    /**
     * Where a pass the switch stopped goes on: the main screen it walks from,
     * a place's page (the director's `preset`), and the pages on the way
     * that the director does not name (`unknown`, `explore_menu`): a wide
     * list open ([Preset.headers]), a tab of the nav bar, the Explore menu.
     */
    override fun resumesOn(screen: String, img: Mat): Boolean {
        if (!carried) return false
        return when (screen) {
            Director.MAIN, Director.PRESET, Director.EXPLORE_MENU -> true
            Director.UNKNOWN -> Preset.inTurn(Preset.headers(img)) || onTab(img)
            else -> false
        }
    }

    /**
     * The pass the switch stopped, gone on with: home from the place it
     * stopped in, the way every visit goes home ([goHome]), and the places
     * that are left. A task the page asked for, so always to the end, home.
     */
    override fun resume(img: Mat, whole: Boolean): Outcome {
        if (!carried) return run()
        log("presets: going on with the pass the main switch stopped")
        stays.reset()
        rect = Dungeon.gameRect(img)
        room = Dungeon.headroom(img)
        if (!isMain(img) && !goHome()) {
            if (!on()) return stop(current)
            carried = false
            stays.met?.let { return Outcome.parked(helpWhy(it, current)) }
            return Outcome.parked("could not get home from ${current?.label ?: "the page"}")
        }
        return places()
    }

    /** The park of a pass a window of the game's own stopped on [place]'s page ([Stays.over]). */
    private fun helpWhy(what: String, place: Preset.Place?): String =
        "the game's $what is open over ${place?.label ?: "the page"}; I tap nothing in it -- " +
            "go through it yourself, then ask for the presets again"

    private fun finish() {
        lastCounts = mapOf("switched" to stats.switched)
        log("presets: ${stats.switched} switched, ${stats.already} already right" +
            if (stats.failed.isEmpty()) "" else ", not set: ${stats.failed.joinToString(", ")}")
    }

    override fun leave(): Boolean = goHome()

    /** One place from the main screen: open it and set it. Null when it is set, else why not. */
    private fun visit(place: Preset.Place, slot: Int): String? {
        val open = open(place) ?: return onFrame { Stays.window(it) }
            ?.let { "the game's $it came up over its page" } ?: "${place.label} did not open"
        try {
            return when (place.kind) {
                Preset.Kind.COMPACT -> setCompact(place, slot, open)
                Preset.Kind.WIDE -> setWide(place, slot, open)
            }
        } finally {
            open.release()
        }
    }

    /** From the main screen to [place]; the frame that shows its bar, or null. */
    private fun open(place: Preset.Place): Mat? {
        val arrived = { img: Mat -> at(place, img) }
        val onMenu = { img: Mat -> Explore.exploreMenu(img) != null }
        return when (place) {
            Preset.Place.DIGIVICE -> if (onFrame(::isMain)) tapUntil(DIGIVICE_ICON, ::isMain, arrived) else null
            Preset.Place.TACTICAL -> if (onFrame(::isMain)) tapUntil(TACTICAL_ICON, ::isMain, arrived) else null
            Preset.Place.FOOD -> {
                val menu = tapRead(navTab(Explore.NAV_EXPLORE_DX), onMenu) ?: return null
                menu.release()
                tapUntil(EAT_CARD, onMenu, arrived)
            }
            Preset.Place.SKILL_CARDS -> {
                val tab = tapRead(navTab(NAV_TAMER_DX), ::onTab) ?: return null
                if (at(place, tab)) return tab
                tab.release()
                tapUntil(SKILL_CARDS_SUBTAB, ::onTab, arrived)
            }
            Preset.Place.SUPPORT -> {
                val tab = tapRead(navTab(Explore.NAV_DIGIMON_DX), ::onTab) ?: return null
                if (at(place, tab)) return tab
                tab.release()
                tapUntil(SUPPORT_SUBTAB, ::onTab, arrived)
            }
            // The page opens on Base, and the bar is on Drive Ability, the
            // third tab (Preset.overdriveTabs): the page is proved by its tab
            // row, and the tab is tapped where it was read.
            Preset.Place.OVERDRIVE -> {
                if (!onFrame(::isMain)) return null
                val page = tapUntil(OVERDRIVE_ICON, ::isMain) { Preset.overdriveTabs(it) != null } ?: return null
                if (at(place, page)) return page
                val drive = Preset.overdriveTabs(page)?.get(2)
                page.release()
                if (drive == null) return null
                tapUntil(drive.target, { Preset.overdriveTabs(it) != null }, arrived)
            }
        }
    }

    // ------------------------------------------------------------------------
    // The compact list: all ten rows at once
    // ------------------------------------------------------------------------
    /**
     * The overlay off a compact place's bar and list before the page is
     * opened, and back when the visit is home again (in [run]). The list's
     * rows since V13: at the measured place (PLAN_FORMATE.md V2) the plate
     * lies on the band compactRows reads down, over exactly the Digivice
     * list's first row -- fy 0.143 to 0.168 against the row's 0.1414 to
     * 0.1682 at 1920, x 602 to 938 against the band's 596 to 623 -- and the
     * list reads as not open on every display. The bar since V14: the page
     * is not even known with the bar under the dot, so the rows are cleared
     * before the tap that opens it, from the place's own constant, and not
     * off a bar that was read. Measured 2026-09-27 with the dot and plate the
     * app draws: at 1080 x 2520 the Digivice bar stands in the headroom at
     * display rows 168 to 248 and is lost with the dot at 0.060 and 0.070,
     * the top of the player's strip; at 1920 it is lost at 0.100 to 0.130.
     * Where the dot goes is Dot.clearFy. In the HUD's fractions, which the
     * overlay speaks (Dungeon.bottomFy): the Digivice page stands at the top
     * over the canvas ceiling. Tactical Memory's and Food Effects' pages
     * stand far below the dot and move nothing; the wide bars, Skill Cards
     * and Support Digimon at fy 0.174, read under the dot at every fy of the
     * strip on both displays, and are not cleared.
     *
     * Overdrive, the sixth place (PLAN_DAILY_LOST_SECTOR_PRESETS.md DL2),
     * needs it as the Digivice does. Measured 2026-09-29 with `readerProbe
     * mask=0.06:0.16:0.005` on the page's Drive Ability frames: its bar (fy
     * 0.1394 to 0.1717, the plate's x 602 to 938 over its right end and the
     * arrow) is not found with the dot at fy 0.130 and lower down the strip
     * at 1080 x 1920, list closed, and at 0.140 and lower with the list open
     * -- the default place, 0.1555, among them -- and at 1080 x 2340 from
     * 0.140 (0.130 too, 0.135 not); at 1080 x 2520 with its 180 rows of
     * headroom and at 720 x 1600 it reads at every fy of the strip. The
     * page itself is known by its tab row at the bottom, which the dot never
     * reaches, so the clear before the icon's tap is what lets the arrival
     * at the bar be read at all (notes/presets.md, "Overdrive is the sixth
     * place, behind a tab and under the dot").
     */
    private fun clearFor(place: Preset.Place) {
        if (place.kind != Preset.Kind.COMPACT) return
        val r = rect ?: return
        cap.overlayClear(Dungeon.bottomFy(place.barFy0, place.anchor, room, r.gh),
                         Dungeon.bottomFy(place.barFy0 + COMPACT_SPAN, place.anchor, room, r.gh))
    }

    private fun setCompact(place: Preset.Place, slot: Int, closed: Mat): String? {
        val bar = Preset.place(closed)?.second ?: return "its bar was not read"
        return setCompactClear(place, slot, closed, bar)
    }

    private fun setCompactClear(place: Preset.Place, slot: Int, closed: Mat, bar: Preset.Bar): String? {
        val rowsOf = { img: Mat -> Preset.place(img)?.takeIf { it.first == place }?.let { Preset.compactRows(img, it.second) } }
        val open = if (rowsOf(closed) != null) closed.clone()
                   else tapUntil(bar.arrow) { rowsOf(it) != null } ?: return "the list did not open"
        try {
            val rows = rowsOf(open) ?: return "the list did not open"
            val want = Preset.rowText(open, bar, rows[slot - 1]) ?: return "row $slot has no text"
            try {
                if (Preset.isSameText(Preset.barText(open, bar), want)) {
                    stats.already += 1
                    log("presets: ${place.label} is on slot $slot already")
                    tap(bar.arrow)
                    waitFor { rowsOf(it) == null }?.release()
                    return null
                }
                tap(Explore.Target((bar.fx0 + bar.fx1) / 2, rows[slot - 1].cy, bar.anchor))
                val after = waitFor { img ->
                    val p = Preset.place(img)
                    p != null && p.first == place && Preset.compactRows(img, p.second) == null &&
                        Preset.isSameText(Preset.barText(img, p.second), want)
                }
                if (after == null) {
                    onFrame { kept(it, "preset_${place.key}_not_set") }
                    return "the bar does not show slot $slot after the tap"
                }
                after.release()
                stats.switched += 1
                log("presets: ${place.label} set to slot $slot")
                return null
            } finally {
                want.release()
            }
        } finally {
            open.release()
        }
    }

    // ------------------------------------------------------------------------
    // The wide list: rows with a number and a tick, three or four at a time
    // ------------------------------------------------------------------------
    private fun setWide(place: Preset.Place, slot: Int, closed: Mat): String? {
        val bar = Preset.place(closed)?.second ?: return "its bar was not read"
        val open = openWide(place) ?: return "the list did not open"
        val (frame, target) = findRow(slot, open) ?: return "row $slot was not found in the list"
        try {
            if (target.on) {
                stats.already += 1
                log("presets: ${place.label} is on slot $slot already")
                closeWide(place)
                return null
            }
            // An unnamed slot has nothing to compare: null, and the tick decides.
            val want = Preset.headerName(frame, target)
            try {
                tap(target.tick)
                val after = waitFor { img -> at(place, img) && Preset.headers(img).isEmpty() }
                    ?: return "the list did not close after the tap"
                val named = want != null && Preset.isSameText(Preset.barText(after, Preset.place(after)?.second ?: bar), want)
                after.release()
                if (named) {
                    stats.switched += 1
                    log("presets: ${place.label} set to slot $slot")
                    return null
                }
            } finally {
                want?.release()
            }
        } finally {
            frame.release()
        }
        // No name on the row, or two presets of one name, or a bar that did
        // not match: the list is opened once more and row N's tick decides.
        val again = openWide(place) ?: return "the list did not open again to check the tick"
        val (check, head) = findRow(slot, again) ?: return "row $slot was not found again to check the tick"
        check.release()
        closeWide(place)
        if (!head.on) {
            onFrame { kept(it, "preset_${place.key}_not_set") }
            return "row $slot's tick is not on after the tap"
        }
        stats.switched += 1
        log("presets: ${place.label} set to slot $slot (by its tick)")
        return null
    }

    /** Open [place]'s wide list by its arrow; the list dims the bar, so an open list is known by its heads alone. */
    private fun openWide(place: Preset.Place): Mat? = tapUntil(place.arrow) { Preset.inTurn(Preset.headers(it)) }

    private fun closeWide(place: Preset.Place) {
        tap(place.arrow)
        waitFor { Preset.headers(it).isEmpty() }?.release()
    }

    /**
     * Scroll the open list until row [slot]'s head is whole in the picture;
     * that frame and the head, or null. [start] is the open list, and is
     * released here whatever comes back.
     */
    private fun findRow(slot: Int, start: Mat): Pair<Mat, Preset.Header>? {
        var frame = start
        for (i in 0..SCROLL_MAX) {
            val heads = Preset.headers(frame)
            heads.firstOrNull { it.slot == slot }?.let { return frame to it }
            if (i == SCROLL_MAX) break
            // Rows further down come up when the drag goes up.
            val (from, to) = if (slot > heads.last().slot!!) SCROLL_FY[0] to SCROLL_FY[1] else SCROLL_FY[1] to SCROLL_FY[0]
            frame.release()
            val r = rect!!
            cap.swipe(Py.roundInt(r.x0 + SCROLL_FX * r.gw), Py.roundInt(r.y0 + from * r.gh),
                      Py.roundInt(r.x0 + SCROLL_FX * r.gw), Py.roundInt(r.y0 + to * r.gh), SCROLL_MS)
            sleep(PAUSE)
            frame = waitFor { Preset.inTurn(Preset.headers(it)) } ?: return null
        }
        frame.release()
        return null
    }

    // ------------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------------
    /**
     * From wherever a visit left the game, back to the plain main screen:
     * an open list is closed by its arrow, a place's window by its X, a tab
     * page by the globe -- each only where it is read, one step at a time.
     */
    fun goHome(): Boolean {
        for (i in 0 until HOME_STEPS) {
            // With the switch off the game stays where it is ([Stays], F24).
            if (stays.now()) return false
            val img = try { grab() } catch (e: CaptureError) { return false }
            try {
                // The game's Help tutorial over the page of a first visit
                // (B73), and every other window of the game's own, is the
                // director's: nothing tapped, no frame kept ([Stays.over]).
                if (stays.over(img)) return false
                if (isMain(img)) return true
                val p = Preset.place(img)
                val x = Summon.exitButton(img)
                val globe = Dungeon.homeButton(img)
                when {
                    Preset.inTurn(Preset.headers(img)) -> {
                        // A wide list open: its arrow closes it. The bar is dimmed
                        // under it, so the arrow is where that place's bar has it.
                        val place = current?.takeIf { it.kind == Preset.Kind.WIDE } ?: return false
                        tap(place.arrow)
                    }
                    p != null && p.first.kind == Preset.Kind.COMPACT && Preset.compactRows(img, p.second) != null ->
                        tap(p.second.arrow)
                    x != null -> tap(Explore.Target(x.fx, x.fy))
                    globe != null -> tap(Explore.Target(globe.fx, globe.fy))
                    else -> {
                        kept(img, "preset_no_way_home")
                        return false
                    }
                }
            } finally {
                img.release()
            }
            waitFor(rounds = 4) { true }?.release()
        }
        return onFrame(::isMain)
    }
}
