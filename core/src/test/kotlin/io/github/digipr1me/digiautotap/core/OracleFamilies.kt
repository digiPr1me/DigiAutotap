package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * The oracle's families: which readers belong to `oracle/<family>.json`,
 * and how each is asked on a frame. One table, used from both sides --
 * the `*OracleTest.kt` suites hold the readers to the files with it, and
 * [OracleWriter] writes the files with it -- so that the two cannot drift
 * (PLAN_STANDALONE.md 1). Until 2026-09-21 this table lived in the
 * laboratory's `oracle.py` (`FAMILIES`) and the Kotlin tests each carried a
 * copy of their family's loop; the laboratory is retired and this is the
 * only table now.
 *
 * A reader call is the key: the reader's name, and in brackets the
 * arguments that are not `img`. An argument that is another reader's
 * answer is written as that reader's name, so that the key says what to
 * feed in without reading the skill: `price_is_red(button=summon_button)`.
 * Explicit, no introspection: a new reader is entered on purpose, and the
 * family's test says when the file and the table disagree.
 *
 * An answer is what the reader returns, in JSON types: maps, lists,
 * strings, ints, truth values, null, and every double at four places
 * ([Oracle.encode]). A reader that raises answers `{"raises": "<name>"}`;
 * that is an answer and the contract holds it too. A picture -- a crop, a
 * mask -- is `{"image": [h, w, ...], "sum": <sum of its bytes>}`: where it
 * was cut and what it holds, without the pixels.
 *
 * Every script asks in the order the skill does, and what a reader takes
 * besides `img` comes from another reader of the same family.
 */
object OracleFamilies {

    /** One picture, and the answers collected on it so far. */
    class Frame(val img: Mat, val vision: Vision) {
        val answers = LinkedHashMap<String, Any?>()

        /**
         * Record what `reader` answers under `key`, and give the answer
         * back so that the next call can be fed with it. A reader that
         * raises a [CalibrationError] answers with the exception's name --
         * the one exception a reader raises on purpose -- and gives null
         * back; anything else is a bug and goes up with the key on it.
         */
        fun <T> ask(key: String, reader: () -> T): T? {
            try {
                val raw = reader()
                answers[key] = raw
                return raw
            } catch (e: CalibrationError) {
                answers[key] = mapOf("raises" to "CalibrationError")
                return null
            } catch (e: Throwable) {
                throw IllegalStateException("$key threw ${e::class.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * One file of the oracle. `modules` are the Kotlin files the readers
     * live in and `depends` the ones they call into without defining a
     * reader of this family (both hashed into the file's head, so that a
     * change beneath the family is noticed); `data` the folders of images
     * the readers read the screen with; `readers` every reader of the
     * family, by its Python name.
     */
    class Family(val name: String, val modules: List<String>, val readers: List<String>,
                 val data: List<String> = emptyList(), val depends: List<String> = emptyList(),
                 val script: (Frame) -> Unit)

    // The Kotlin files under core/src/main/kotlin/.../core, by their name.
    private const val SOURCES = "core/src/main/kotlin/io/github/digipr1me/digiautotap/core"

    /** A picture in the oracle's form: numpy's shape, and the sum of every value. */
    fun picture(m: Mat?): Any? {
        if (m == null) return null
        val shape = if (m.channels() == 1) listOf(m.rows(), m.cols())
                    else listOf(m.rows(), m.cols(), m.channels())
        return mapOf("image" to shape, "sum" to Core.sumElems(m).`val`.sum().toLong())
    }

    private fun stats(glyphs: List<IntArray>): List<List<Int>> = glyphs.map { it.toList() }

    // ------------------------------------------------------------------------
    // The families
    // ------------------------------------------------------------------------

    private fun dungeon(f: Frame) {
        val img = f.img
        f.ask("game_rect") { Dungeon.gameRect(img).toOracle() }
        f.ask("find_buttons(colour=BLUE,min_y=0.0)") {
            Dungeon.findButtons(img, Dungeon.BLUE, minY = 0.0).map { it.toOracle() }
        }
        f.ask("find_buttons(colour=VIOLET,min_y=0.0)") {
            Dungeon.findButtons(img, Dungeon.VIOLET, minY = 0.0).map { it.toOracle() }
        }
        val cards = Dungeon.listCardsWithSize(img)
        f.ask("list_cards") { cards.map { it.first } }
        f.ask("list_cards(with_size=True)") { cards }
        f.ask("list_at_top") { Dungeon.listAtTop(cards) }
        f.ask("list_at_bottom") { Dungeon.listAtBottom(cards) }
        // The list-card readers, once per card list_cards found, with that
        // card's fy and fh in the key as Python renders them.
        for ((fy, fh) in cards) {
            val args = "(fy=${Oracle.encode(fy)},fh=${Oracle.encode(fh)})"
            f.ask("badge_crop$args") { picture(Dungeon.badgeCrop(img, fy, fh)) }
            f.ask("badge_glyphs$args") {
                val glyphs = Dungeon.badgeGlyphs(img, fy, fh)
                listOf(picture(glyphs.mask), stats(glyphs.glyphs))
            }
            f.ask("card_counters$args") {
                val counters = Dungeon.cardCounters(img, fy, fh)
                listOf(picture(counters.mask), counters.counters.map { stats(it) })
            }
            f.ask("card_budget$args") { Dungeon.cardBudget(img, fy, fh).toOracle() }
            f.ask("card_has_attempts$args") { Dungeon.cardHasAttempts(img, fy, fh) }
        }
        f.ask("popup_ok") { Dungeon.popupOk(img)?.toOracle() }
        f.ask("claim_button") { Dungeon.claimButton(img)?.toOracle() }
        f.ask("stage_failed") { Dungeon.stageFailed(img) }
        f.ask("auto_button") { Dungeon.autoButton(img)?.toOracle() }
        f.ask("home_button") { Dungeon.homeButton(img)?.toOracle() }
        f.ask("party_slots_filled") { Dungeon.partySlotsFilled(img) }
        val rec = Dungeon.recognise(img)
        f.ask("recognise") { rec.toOracle() }
        // The one prompt whose OK the caller has to know the colour of.
        // recognise finds the button; asked on its own, the answer is the
        // same one recognise already carries as exit_kind.
        rec.exitOk?.let { ok -> f.ask("confirm_kind(ok_button=recognise.exit_ok)") { Dungeon.confirmKind(img, ok, rec.anchor) } }
        // What tells the grey prompt's two apart: the message's height, one
        // line for "Exit the game?", two for the game's download (2026-09-30).
        rec.exitOk?.let { ok -> f.ask("prompt_text_h(ok_button=recognise.exit_ok)") { Dungeon.promptTextH(img, ok, rec.anchor) } }
        f.ask("reward_sheet") { Dungeon.rewardSheet(img) }
        // A run's own transitions, which the waits after Attempt do not tap
        // (PLAN_RELEASE_1_3.md B6, 2026-10-01).
        f.ask("black_frame") { Dungeon.blackFrame(img) }
        f.ask("vs_screen") { Dungeon.vsScreen(img) }
        // The panel's own counter hangs off its button: Attempt where there is
        // one, the ad button on a panel at 0/2 -- the one the skill reads it
        // from. Asked wherever recognise found either, dialog or not.
        val counterButton = rec.attempt ?: rec.ad
        if (counterButton != null) {
            val which = if (rec.attempt != null) "attempt" else "ad"
            f.ask("panel_tickets(button=recognise.$which)") { Dungeon.panelTickets(img, counterButton, rec.anchor) }
        }
        // The party panel's counter stands above the panel, not over a button.
        f.ask("header_tickets") { Dungeon.headerTickets(img) }
        // The daily dungeon's panel, its Attempt beside a Reset (2026-09-29).
        f.ask("daily_panel") { Dungeon.dailyPanel(img)?.toOracle() }
        f.ask("title_bar") { Startup.titleBar(img)?.toOracle() }
        f.ask("at_main") { Startup.atMain(img) }
        // The title by day by its Menu button (PLAN_RELEASE_1_3.md B43), and
        // the OK of the game's news after its reset (B44), 2026-10-01.
        f.ask("menu_button") { Startup.menuButton(img)?.toOracle() }
        f.ask("announcement") { Startup.announcement(img)?.toOracle() }
        // The game's "Time Sale!" window and its Help tutorial, which nothing
        // taps (PLAN_RELEASE_1_3.md B72, B73), 2026-10-01.
        f.ask("sale_window") { Startup.saleWindow(img)?.toOracle() }
        f.ask("help_window") { Startup.helpWindow(img)?.toOracle() }
    }

    private fun passive(f: Frame) {
        val img = f.img
        f.ask("bond_bubble") { Passive.bondBubble(img)?.toOracle() }
        f.ask("partner_menu") { Passive.partnerMenu(img)?.toOracle() }
        f.ask("holo_counter") { Passive.holoCounter(img) }
        f.ask("grid_button") { Bond.gridButton(img)?.toOracle() }
        f.ask("raise_button") { Bond.raiseButton(img)?.toOracle() }
        f.ask("cells") { Bond.cells(img) }
        f.ask("raised_cell") { Bond.raisedCell(img) }
        f.ask("partner_subtab") { Bond.partnerSubtab(img)?.toOracle() }
    }

    private fun summon(f: Frame) {
        val img = f.img
        val button = Summon.summonButton(img)
        f.ask("summon_button") { button?.toOracle() }
        f.ask("dimmed_summon_button") { Summon.dimmedSummonButton(img)?.toOracle() }
        f.ask("exit_button") { Summon.exitButton(img)?.toOracle() }
        f.ask("not_enough_tickets") { Summon.notEnoughTickets(img)?.toOracle() }
        f.ask("mode_dots") { Summon.modeDots(img) }
        f.ask("general_tab") { Summon.generalTab(img)?.toOracle() }
        f.ask("ticket_counter") { Summon.ticketCounter(img) }
        // The price is written over the button, so it is asked where a
        // button was found -- on a screen with none it can only be False,
        // and that is not something the skill ever asks.
        if (button != null) f.ask("price_is_red(button=summon_button)") { Summon.priceIsRed(img, button) }
        f.ask("view_ads_button") { Summon.viewAdsButton(img)?.toOracle() }
        f.ask("ads_left") { Summon.adsLeft(img) }
    }

    private fun quest(f: Frame) {
        val img = f.img
        f.ask("quest_progress") { Quest.questProgress(img) }
        f.ask("quest_card") { Quest.questCard(img)?.toOracle() }
        f.ask("quest_claimable") { Quest.questClaimable(img) }
        f.ask("stage_number") { Quest.stageNumber(img) }
        f.ask("close_x") { Quest.closeX(img)?.toOracle() }
        f.ask("rewards_chest") { Quest.rewardsChest(img)?.toOracle() }
        val idle = Quest.idleWindow(img)
        f.ask("idle_window") { idle?.toOracle() }
        if (idle != null) f.ask("idle_extra_left(window=idle_window)") { Quest.idleExtraLeft(img, idle) }
    }

    // The meat field's cells are asked only on a screen that reads as the
    // field: the skill does the same, and on any other screen thirty
    // answers per frame would say null thirty times. Four is what the seed
    // menu's dimming leaves (Farm.meatFieldScreen).
    const val FIELD_CELLS_FROM = 4

    private fun explore(f: Frame) {
        val img = f.img
        f.ask("nav_tab(dx=NAV_EXPLORE_DX)") { Explore.navTab(img, Explore.NAV_EXPLORE_DX)?.toOracle() }
        f.ask("nav_tab(dx=NAV_DIGIMON_DX)") { Explore.navTab(img, Explore.NAV_DIGIMON_DX)?.toOracle() }
        f.ask("explore_tab") { Explore.exploreTab(img)?.toOracle() }
        f.ask("digimon_tab") { Explore.digimonTab(img)?.toOracle() }
        f.ask("explore_menu") { Explore.exploreMenu(img)?.toOracle() }
        f.ask("world_search_card") { Explore.worldSearchCard(img)?.toOracle() }
        f.ask("meat_field_card") { Explore.meatFieldCard(img)?.toOracle() }
        f.ask("close_button") { Explore.closeButton(img)?.toOracle() }
        val screen = f.ask("meat_field_screen") { Farm.meatFieldScreen(img) }
        f.ask("plots") { Farm.plots(img) }
        f.ask("free_seeds") { Farm.freeSeeds(img) }
        f.ask("seed_refill") { Farm.seedRefill(img) }
        f.ask("water_cans") { Farm.waterCans(img) }
        f.ask("can_refill") { Farm.canRefill(img) }
        val slots = Farm.seedSlots(img)
        f.ask("seed_slots") { slots.map { it.toOracle() } }
        // `slot=seed_slots[i]`: the i-th answer of seed_slots on this frame.
        slots.forEachIndexed { i, slot -> f.ask("seed_selected(slot=seed_slots[$i])") { Farm.seedSelected(img, slot) } }
        slots.forEachIndexed { i, slot -> f.ask("seed_slot_count(slot=seed_slots[$i])") { Farm.seedSlotCount(img, slot) } }
        f.ask("select_button") { Farm.selectButton(img)?.toOracle() }
        f.ask("seed_menu") {
            val (menuSlots, button) = Farm.seedMenu(img)
            listOf(menuSlots?.map { it.toOracle() }, button?.toOracle())
        }
        f.ask("water_button") { Farm.waterButton(img)?.toOracle() }
        f.ask("water_popup") { Farm.waterPopup(img)?.toOracle() }
        f.ask("popup_timer") { Farm.popupTimer(img) }
        f.ask("boost_popup") { Farm.boostPopup(img)?.toOracle() }
        f.ask("boost_cans") { Farm.boostCans(img) }
        f.ask("boost_amount") { Farm.boostAmount(img) }
        f.ask("ad_dialog") { Farm.adDialog(img)?.toOracle() }
        f.ask("ad_limit") { Farm.adLimit(img)?.toOracle() }
        if (screen != null && screen >= FIELD_CELLS_FROM) {
            for (col in 0 until 2) for (row in 0 until 3) {
                val args = "(col=$col,row=$row)"
                f.ask("plot_timer$args") { Farm.plotTimer(img, col, row) }
                f.ask("plot_badge_kind$args") { Farm.plotBadgeKind(img, col, row) }
                f.ask("plot_harvest$args") { Farm.plotHarvest(img, col, row) }
                f.ask("plot_state$args") { Farm.plotState(img, col, row) }
                f.ask("bubble_over$args") { Farm.bubbleOver(img, col, row) }
            }
        }
    }

    private fun vision(f: Frame) {
        val img = f.img
        val v = f.vision
        f.ask("find_card") { v.findCard(img).toList() }
        val calib = try {
            v.calibrate(img)
        } catch (e: CalibrationError) {
            // No board: every reader below needs calib and the skill never
            // asks one without. That calibrate raised is the answer here.
            f.answers["calibrate"] = mapOf("raises" to "CalibrationError")
            return
        }
        f.ask("calibrate") { calib.toOracle() }
        f.ask("skill_button_ok(calib=calibrate)") { v.skillButtonOk(img, calib) }
        val figure = v.findFigure(img, calib, v.loadTemplates())
        f.ask("find_figure(calib=calibrate,templates=load_templates)") { figure?.toOracle() }
        f.ask("read_grid(calib=calibrate,templates=board_templates,figure=find_figure)") {
            v.readGrid(img, calib, v.boardTemplates(), figure = figure)
        }
        for (row in 0 until Vision.ROWS) for (col in 0 until Vision.COLS) {
            val args = "(calib=calibrate,row=$row,col=$col)"
            f.ask("search_patch$args") {
                picture(v.searchPatch(img, calib, row, col)
                    ?: throw IllegalStateException("an empty patch, which the oracle has no form for"))
            }
            f.ask("has_object_colour$args") { v.hasObjectColour(img, calib, row, col) }
        }
        f.ask("banner_visible(calib=calibrate)") { v.bannerVisible(img, calib) }
        f.ask("classify_banner(calib=calibrate)") { v.classifyBanner(img, calib) }
        for (key in Vision.COUNTER_KEYS) {
            f.ask("read_number(roi_key='$key',calib=calibrate)") { v.readNumber(img, key, calib) }
        }
        f.ask("read_counters(calib=calibrate)") { v.readCounters(img, calib) }
    }

    // The runner's readers, in the order the skill asks them: the dialogs
    // on the way in and out, then what a run frame carries. `answer` is
    // asked once per obstacle, fed that obstacle, as seed_selected is fed
    // its slot.
    private fun runner(f: Frame) {
        val img = f.img
        f.ask("events_icon") { Runner.eventsIcon(img)?.toOracle() }
        f.ask("events_dialog") { Runner.eventsDialog(img)?.toOracle() }
        f.ask("event_page") { Runner.eventPage(img)?.toOracle() }
        f.ask("result_dialog") { Runner.resultDialog(img)?.toOracle() }
        f.ask("pause_dialog") { Runner.pauseDialog(img)?.toOracle() }
        f.ask("missions_dialog") { Runner.missionsDialog(img)?.toOracle() }
        f.ask("claim_buttons") { Runner.claimButtons(img).map { it.toOracle() } }
        f.ask("missions_x") { Runner.missionsX(img)?.toOracle() }
        f.ask("reward_overlay") { Runner.rewardOverlay(img)?.toOracle() }
        val found = Runner.obstacles(img)
        f.ask("obstacles") { found.map { it.toOracle() } }
        found.forEachIndexed { i, o -> f.ask("answer(obstacle=obstacles[$i])") { Runner.answer(o) } }
        f.ask("orbs_in_air") { Runner.orbsInAir(img) }
        f.ask("bar_state") { Runner.barState(img).toList() }
        f.ask("read_score") { Runner.readScore(img) }
    }

    // The Events window (Events.kt): its rim, its cards named, and where a tap closes it.
    private fun events(f: Frame) {
        val img = f.img
        val window = Events.window(img)
        f.ask("window") { window?.toOracle() }
        f.ask("cards") { Events.cards(img)?.map { it.toOracle() } }
        f.ask("outside") { Events.outside(img)?.toOracle() }
    }

    // One per thread, as the writer's Vision is: SkewerIcons keeps the
    // order's templates at the last frame's scale, and two frames of two
    // widths asked at once would hand each other theirs.
    private val skewerIcons = ThreadLocal.withInitial { SkewerIcons(ClassPathAssets) }

    // Chef's Special (Skewer.kt): the screens around the round, then what a
    // round carries -- the grill's twelve, the order, the lives.
    private fun skewer(f: Frame) {
        val img = f.img
        val icons = skewerIcons.get()
        f.ask("menu") { Skewer.menu(img)?.toOracle() }
        f.ask("stage") { Skewer.stage(img)?.toOracle() }
        val grill = Skewer.grill(img)
        f.ask("grill") { grill.toOracle() }
        f.ask("over") { Skewer.over(img)?.toOracle() }
        f.ask("paused") { Skewer.paused(img)?.toOracle() }
        f.ask("playing") { Skewer.playing(img) }
        if (grill.ok) {
            f.ask("grid") { icons.grid(img).map { it.toOracle() } }
            f.ask("order") { icons.order(img)?.toOracle() }
            f.ask("lives") { icons.lives(img)?.toOracle() }
            f.ask("combo") { Skewer.comboGlyphs(img) }
        }
    }
    // The preset readers, in the order the skill asks them: the bars and the
    // place; on a place, the bar's text; on a compact place its ten rows and
    // each row's text. The wide list's heads are asked on every frame --
    // an open wide list dims its bar, so there is no place to hang them
    // from, and the skill asks them wherever it opened one -- and each
    // head's name after them. A text crop goes in as a picture.
    private fun preset(f: Frame) {
        val img = f.img
        fun pic(m: Mat?): Any? = picture(m).also { m?.release() }
        f.ask("preset_bars") { Preset.bars(img).map { it.toOracle() } }
        val place = Preset.place(img)
        f.ask("preset_place") { place?.let { mapOf("place" to it.first.key, "bar" to it.second.toOracle()) } }
        if (place != null) {
            val bar = place.second
            f.ask("bar_text(bar=preset_place)") { pic(Preset.barText(img, bar)) }
            if (place.first.kind == Preset.Kind.COMPACT) {
                val rows = Preset.compactRows(img, bar)
                f.ask("compact_rows(bar=preset_place)") { rows?.map { it.toOracle() } }
                rows?.forEachIndexed { i, r ->
                    f.ask("row_text(bar=preset_place,row=compact_rows[$i])") { pic(Preset.rowText(img, bar, r)) }
                }
            }
        }
        val heads = Preset.headers(img)
        f.ask("preset_headers") { heads.map { it.toOracle() } }
        heads.forEachIndexed { i, h -> f.ask("header_name(head=preset_headers[$i])") { pic(Preset.headerName(img, h)) } }
        // The Overdrive page's tab row, asked on every frame: the page opens
        // on a tab without the bar, so there is no place to hang it from.
        f.ask("overdrive_tabs") { Preset.overdriveTabs(img)?.map { it.toOracle() } }
    }

    // The Lost Sector Tower's readers, in the order the skill meets them:
    // the Crests page (its Enter pill, the page), then the panel's Subjugate
    // and the toast of the highest floor over it.
    private fun lostSector(f: Frame) {
        val img = f.img
        f.ask("enter_button") { LostSector.enterButton(img)?.toOracle() }
        f.ask("page") { LostSector.page(img)?.toOracle() }
        f.ask("subjugate") { LostSector.subjugate(img)?.toOracle() }
        f.ask("max_floor") { LostSector.maxFloor(img)?.toOracle() }
    }

    // The EX Missions readers (Missions.kt), in the order the task asks
    // them: the tile and its "!" on the main screen, the window with its lit
    // tab, and on the EX tab its header block and every yellow Claim with
    // its row.
    private fun missions(f: Frame) {
        val img = f.img
        f.ask("tile") { Missions.tile(img)?.toOracle() }
        f.ask("badge") { Missions.badge(img) }
        f.ask("window") { Missions.window(img)?.toOracle() }
        val ex = Missions.exWindow(img)
        f.ask("ex_window") { ex?.toOracle() }
        if (ex != null) f.ask("ex_claims(window=ex_window)") { Missions.exClaims(img, ex).map { it.toOracle() } }
    }

    // One answer per frame, the director's own: which screen is in front.
    // It asks nothing new -- every reader it calls is in a family above --
    // so what it adds to the contract is the order, and the file is the
    // screen inventory of PLAN_ANDROID_3_DIRECTOR.md 3.1 frozen: a reader
    // change that tips a second frame's screen shows here.
    private fun director(f: Frame) {
        f.ask("classify") { Director.classify(f.img).toOracle() }
    }

    val DUNGEON = Family(
        "dungeon", listOf("Dungeon", "Startup"),
        listOf("game_rect", "find_buttons", "list_cards", "list_at_top", "list_at_bottom",
               "badge_crop", "badge_glyphs",
               "card_counters", "card_budget", "card_has_attempts", "confirm_kind", "prompt_text_h",
               "party_slots_filled", "popup_ok", "claim_button", "stage_failed",
               "auto_button", "home_button", "recognise", "reward_sheet", "black_frame", "vs_screen",
               "panel_tickets",
               "header_tickets", "daily_panel",
               "title_bar", "at_main", "menu_button", "announcement", "sale_window", "help_window"),
        depends = listOf("Cv"), script = ::dungeon)
    val PASSIVE = Family(
        "passive", listOf("Passive", "Bond"),
        listOf("bond_bubble", "partner_menu", "holo_counter", "grid_button",
               "raise_button", "cells", "raised_cell", "partner_subtab"),
        depends = listOf("Cv"), script = ::passive)
    val SUMMON = Family(
        "summon", listOf("Summon"),
        listOf("summon_button", "dimmed_summon_button", "exit_button",
               "not_enough_tickets", "mode_dots", "general_tab", "ticket_counter",
               "price_is_red", "view_ads_button", "ads_left"),
        depends = listOf("Cv"), script = ::summon)
    val QUEST = Family(
        "quest", listOf("Quest"),
        listOf("quest_progress", "quest_card", "quest_claimable", "stage_number", "close_x",
               "rewards_chest", "idle_window", "idle_extra_left"),
        depends = listOf("Dungeon", "Cv"), script = ::quest)
    val EXPLORE = Family(
        "explore", listOf("Explore", "Farm"),
        listOf("nav_tab", "explore_tab", "digimon_tab", "explore_menu",
               "world_search_card", "meat_field_card", "close_button",
               "meat_field_screen", "bubble_over", "plot_timer", "plot_badge_kind",
               "plot_harvest", "plot_state", "plots", "free_seeds", "seed_refill",
               "water_cans", "can_refill",
               "seed_slots", "seed_selected", "seed_slot_count", "select_button", "seed_menu",
               "water_button", "water_popup", "popup_timer", "boost_popup", "boost_cans",
               "boost_amount", "ad_dialog", "ad_limit"),
        depends = listOf("Cv"), script = ::explore)
    val VISION = Family(
        "vision", listOf("Vision"),
        listOf("find_card", "calibrate", "skill_button_ok", "read_grid",
               "search_patch", "has_object_colour", "find_figure", "banner_visible",
               "classify_banner", "read_number", "read_counters"),
        data = listOf("templates", "digits"), depends = listOf("Cv"), script = ::vision)
    val RUNNER = Family(
        "runner", listOf("Runner"),
        listOf("events_icon", "events_dialog", "event_page", "result_dialog", "pause_dialog",
               "missions_dialog", "claim_buttons", "missions_x", "reward_overlay", "obstacles", "answer",
               "orbs_in_air", "bar_state", "read_score"),
        depends = listOf("Summon", "Dungeon", "Cv"), script = ::runner)
    val EVENTS = Family(
        "events", listOf("Events"),
        listOf("window", "cards", "outside"),
        depends = listOf("Runner", "Dungeon", "Cv"), script = ::events)
    val SKEWER = Family(
        "skewer", listOf("Skewer"),
        listOf("menu", "stage", "grill", "over", "paused", "playing", "grid", "order", "lives", "combo"),
        data = listOf("templates"), depends = listOf("Runner", "Summon", "Dungeon", "Cv"), script = ::skewer)
    val PRESET = Family(
        "preset", listOf("Preset"),
        listOf("preset_bars", "preset_place", "bar_text", "compact_rows", "row_text",
               "preset_headers", "header_name", "overdrive_tabs"),
        depends = listOf("Dungeon", "Cv"), script = ::preset)
    val LOST_SECTOR = Family(
        "lost_sector", listOf("LostSector"),
        listOf("enter_button", "page", "subjugate", "max_floor"),
        depends = listOf("Dungeon", "Summon", "Passive", "Cv"), script = ::lostSector)
    val MISSIONS = Family(
        "missions", listOf("Missions"),
        listOf("tile", "badge", "window", "ex_window", "ex_claims"),
        depends = listOf("Dungeon", "Runner", "Cv"), script = ::missions)
    val DIRECTOR = Family(
        "director", listOf("Director"), listOf("classify"),
        depends = listOf("Dungeon", "Startup", "Passive", "Bond", "Summon", "Explore", "Farm", "Runner",
                         "Preset", "Quest", "LostSector", "Missions", "Skewer", "Cv"),
        script = ::director)

    val ALL: List<Family> = listOf(DUNGEON, PASSIVE, SUMMON, QUEST, EXPLORE, VISION, RUNNER, EVENTS, SKEWER, PRESET, LOST_SECTOR,
                                   MISSIONS, DIRECTOR)
    /**
     * Families whose frames wait in `staging/` for the oracle's holder
     * (NOTES.md, "A new reader, or a new task", step 1): readerProbe reads
     * them, and writeOracle and the oracle tests do not know them yet. A
     * family moves into [ALL] in the commit that writes its oracle file.
     */
    val STAGED: List<Family> = emptyList()
    val BY_NAME: Map<String, Family> = (ALL + STAGED).associateBy { it.name }

    /** One family's entry for one frame: `{"size": [w, h], call: answer}`. */
    fun answers(family: Family, img: Mat, vision: Vision): Map<String, Any?> {
        val frame = Frame(img, vision)
        family.script(frame)
        val entry = LinkedHashMap<String, Any?>()
        entry["size"] = listOf(img.cols(), img.rows())
        entry.putAll(frame.answers)
        return entry
    }

    // ------------------------------------------------------------------------
    // Where the frames are
    // ------------------------------------------------------------------------
    // corpus/<folder>/, one folder per skill, and NOTHING WRITES THERE: a frame
    // is promoted into the corpus by moving it there by hand, which is what
    // makes it a deliberate act (CorpusTest scans the sources for a write site
    // that names the folder). The frame rule and the floor are the
    // laboratory's, measured on the corpus of 2026-09-19: window frames run
    // 1.727 to 1.843 high to wide, ADB frames 1.778, a long display 2.167,
    // the tallest phone sold 2.336; nothing that is not a frame sits between
    // 1.85 and 2.16, and every crop fails the aspect outright. 400 wide is
    // 19 % under the narrowest window frame there is (497 x 914).
    //
    // The aspect was 1.6 to 2.5 until 2026-09-27, and a tablet or a Fold fell
    // out of it without a word, into `skipped` (PLAN_FORMATE.md 9). The
    // devices the game and the app share run from the Pixel 9 Pro Fold's
    // inner display, 2076 x 2152 (1.037), through the OnePlus Pad's 1.4, the
    // Xiaomi Pad 7's 1.498 and the Galaxy tablets' 1.6 to 1.68, to the Z
    // Fold3's outer display, 832 x 2268 (2.726). So a frame is upright, 1.0
    // to 2.8: the game only ever runs portrait, a landscape emulator frame
    // (1920 x 1080, 0.5625) is still refused, and so is every crop wider than
    // it is tall. On 2026-09-27 the corpus held 1360 PNGs and every one was a
    // frame under both rules, so the widening took nothing in and let
    // nothing out.
    //
    // And a display on its side is a frame since the same evening
    // (PLAN_FORMATE.md V19): the game does run on one, upright in the middle
    // of a landscape emulator that does not turn, and the player chose to
    // support it. [LANDSCAPE_ASPECT] is a whole landscape display, 21:9
    // (0.43) to 16:10 (0.625); a crop that wide is not in the corpus. Counted
    // before it was written: of the 1508 PNGs then in corpus/ not one is
    // under 1.0, so the rule took in only the landscape frames put there for
    // it and let nothing out.
    const val CORPUS = "corpus"

    /**
     * The JUnit tag every suite carries that reads a frame out of [CORPUS]:
     * the `*OracleTest`, and the flow tests that play on corpus frames. They
     * are eleven of the fourteen minutes `:core:test` takes (measured
     * 2026-10-03), so `:core:fastTest` leaves them out and `:core:test`
     * still runs everything, exactly as NOTES.md says. The same string
     * stands once more in `core/build.gradle.kts`, which is the only other
     * place that may name it.
     *
     * A suite that starts reading the corpus gets the tag; one that stops
     * reading it loses the tag. `CorpusTest` does not carry it -- it scans
     * the *sources* for a write site that names the folder and never opens
     * a frame.
     */
    const val CORPUS_TAG = "corpus"
    val FRAME_ASPECT = 1.0..2.8
    val LANDSCAPE_ASPECT = 0.42..0.63
    const val FRAME_MIN_W = 400

    private val FORMAT_NAME = Regex("""_(\d+)x(\d+)_([a-z]+)(\d+)_\d{6}\.png$""")

    /**
     * The headroom of a tour frame whose name does not say it
     * (PLAN_FORMATE.md 3a, row 23): `1080x2640_hole300` stands for a phone
     * with a 100 px hole, and LDPlayer ran it as 1080 x 2540 without a
     * cutout, where the app cut 200 -- both leave 200 rows between the top
     * and the canvas, and the fits of V4 said -100 and -200.
     */
    val HEADROOM_BY_FORMAT = mapOf("1080x2640_hole300" to 200)

    /**
     * Rows of headroom (Dungeon.CanvasFrame) of a corpus frame [rows] tall,
     * by its name (PLAN_FORMATE.md 7): a `none` frame was cut by the canvas
     * ceiling alone, so every row the app cut is headroom; a cutout's rows
     * are the camera's, not the game's; and a frame of any other name was
     * never cut.
     */
    fun headroom(name: String, rows: Int): Int = room(name, rows).first

    /**
     * The headroom of a corpus frame and how many of its rows it holds above
     * the canvas (Dungeon.CanvasFrame.above). A `none` frame's headroom is
     * the band the canvas ceiling leaves, H - W * 13/6; the frame holds the
     * part of it the app did not cut. The V3 frames of the tour were cut to
     * the canvas (`none180` at 2520, above 0); a frame kept whole since V4's
     * fifth group is `none0` and holds all of it.
     */
    fun room(name: String, rows: Int): Pair<Int, Int> {
        val m = FORMAT_NAME.find(name) ?: return 0 to 0
        val (w, h, kind, cut) = m.destructured
        HEADROOM_BY_FORMAT["${w}x${h}_$kind$cut"]?.let { return it to 0 }
        if (kind != "none" || rows != h.toInt() - cut.toInt()) return 0 to 0
        val band = Dungeon.canvasTop(w.toInt(), h.toInt(), 0)
        if (band <= 0 || cut.toInt() > band) return 0 to 0
        return band to band - cut.toInt()
    }

    /** A corpus frame as `DigiAutotapService.grab` would have handed it over: with its headroom. */
    fun read(file: File): Mat = framed(Imgcodecs.imread(file.path), file.name)

    /** [img], which was read from a file called [name], with its headroom; [img] itself when it has none. */
    fun framed(img: Mat, name: String): Mat {
        val (room, above) = if (img.empty()) 0 to 0 else room(name, img.rows())
        if (room == 0) return img
        return Dungeon.CanvasFrame(room, above).also { img.copyTo(it); img.release() }
    }

    /** A copy of [img] that keeps its headroom, where `clone` would lose it. */
    fun copy(img: Mat): Mat {
        val room = Dungeon.headroom(img)
        if (room == 0) return img.clone()
        return Dungeon.CanvasFrame(room, Dungeon.above(img)).also { img.copyTo(it) }
    }

    /** Is a picture of this size a whole emulator frame, not a crop? */
    fun isFrame(w: Int, h: Int): Boolean = w >= FRAME_MIN_W &&
        (h.toDouble() / w).let { it in FRAME_ASPECT || it in LANDSCAPE_ASPECT }

    /** (width, height) from the PNG header, or null if it is not a PNG. */
    fun pngSize(file: File): Pair<Int, Int>? {
        val head = ByteArray(24)
        val read = file.inputStream().use { it.read(head) }
        if (read < 24) return null
        val sig = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
                              0x0d, 0x0a, 0x1a, 0x0a)
        if (!head.copyOfRange(0, 8).contentEquals(sig)) return null
        if (String(head, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        fun int(at: Int) = ((head[at].toInt() and 0xff) shl 24) or ((head[at + 1].toInt() and 0xff) shl 16) or
            ((head[at + 2].toInt() and 0xff) shl 8) or (head[at + 3].toInt() and 0xff)
        return int(16) to int(20)
    }

    class Corpus(val frames: List<String>, val skipped: Map<String, Any>)

    /**
     * Whole frames sorted by path, and what was left out: every folder under
     * corpus/, recursive, .png only. Paths are relative to the repository,
     * with forward slashes -- the order is part of the file.
     */
    fun corpus(repo: File): Corpus {
        val frames = ArrayList<String>()
        val skipped = LinkedHashMap<String, Any>()
        val base = File(repo, CORPUS)
        if (!base.isDirectory) return Corpus(frames, skipped)
        for (folder in base.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            for (file in walk(folder)) {
                if (!file.name.lowercase().endsWith(".png")) continue
                val rel = file.relativeTo(repo).path.replace(File.separatorChar, '/')
                val size = pngSize(file)
                if (size == null) skipped[rel] = "unreadable"
                else if (isFrame(size.first, size.second)) frames.add(rel)
                else skipped[rel] = listOf(size.first, size.second)
            }
        }
        return Corpus(frames, skipped)
    }

    /** os.walk, top-down, files then folders, both sorted at every level. */
    private fun walk(dir: File): List<File> {
        val entries = dir.listFiles()?.toList() ?: emptyList()
        val out = ArrayList<File>()
        out += entries.filter { it.isFile }.sortedBy { it.name }
        for (sub in entries.filter { it.isDirectory }.sortedBy { it.name }) out += walk(sub)
        return out
    }

    // ------------------------------------------------------------------------
    // The head of a file
    // ------------------------------------------------------------------------

    /** sha1 of a file, the same file with the other line endings being the same file. */
    fun sha1(file: File): String {
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1).replace("\r\n", "\n")
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.ISO_8859_1))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Per source file of the family, keyed as Python keyed its modules: `dungeon`, `startup`. */
    fun moduleHashes(family: Family, repo: File): Map<String, String> =
        (family.modules + family.depends).associate { it.lowercase() to sha1(File(repo, "$SOURCES/$it.kt")) }

    /** Per image folder of the family: one digest over every file's path and sha1. */
    fun dataHashes(family: Family, repo: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (folder in family.data) {
            val base = File(repo, folder)
            val digest = MessageDigest.getInstance("SHA-1")
            for (file in walk(base)) {
                digest.update(file.relativeTo(base).path.replace(File.separatorChar, '/').toByteArray())
                digest.update(sha1(file).toByteArray())
            }
            out[folder] = digest.digest().joinToString("") { "%02x".format(it) }
        }
        return out
    }

    /** The reader a call key names: `find_buttons(colour=BLUE,min_y=0.0)` -> `find_buttons`. */
    fun readerName(key: String): String = key.substringBefore('(')

    /**
     * Per reader: on how many frames it was asked, on how many it answered
     * something other than null, and how many *different* answers it gave.
     * A reader that says the same thing on every frame is no contract --
     * the Kotlin side could `return null` and pass -- and the head is
     * where that shows.
     */
    fun readerStats(entries: Map<String, Map<String, Any?>>): Map<String, Map<String, Int>> {
        val asked = HashMap<String, Int>()
        val answered = HashMap<String, Int>()
        val seen = HashMap<String, HashSet<String>>()
        for (entry in entries.values) {
            val got = LinkedHashMap<String, Boolean>()
            for ((key, answer) in entry) {
                if (key == "size") continue
                val name = readerName(key)
                got[name] = (got[name] ?: false) || answer != null
                seen.getOrPut(name) { HashSet() }.add(Oracle.encode(answer))
            }
            for ((name, some) in got) {
                asked.merge(name, 1, Int::plus)
                answered.merge(name, if (some) 1 else 0, Int::plus)
            }
        }
        return asked.keys.sorted().associateWith { name ->
            mapOf("frames" to asked[name]!!, "answered" to answered[name]!!, "distinct" to seen[name]!!.size)
        }
    }

    /**
     * The text of oracle/<family>.json after its "written" line: the head,
     * then `frames` with one frame per line and sorted paths, then
     * `skipped`. Python's `json.dumps` with sorted keys, line for line, so
     * that a file written here and one the laboratory wrote read the same.
     */
    fun build(family: Family, results: Map<String, Map<String, Any?>>, skipped: Map<String, Any>,
              repo: File): String {
        val head = listOf(
            "family" to family.name,
            "opencv" to Core.VERSION,
            "modules" to moduleHashes(family, repo),
            "data" to dataHashes(family, repo),
            "readers" to readerStats(results))
        val lines = head.map { (k, v) -> "${Oracle.encode(k)}: ${Oracle.encode(v)}" }
        val frames = results.keys.sorted().map { rel -> "${Oracle.encode(rel)}: ${Oracle.encode(results[rel])}" }
        val skips = skipped.keys.sorted().map { rel -> "${Oracle.encode(rel)}: ${Oracle.encode(skipped[rel])}" }
        val parts = listOf(
            lines.joinToString(",\n"),
            "\"frames\": {\n" + frames.joinToString(",\n") + "\n}",
            "\"skipped\": {\n" + skips.joinToString(",\n") + "\n}")
        return parts.joinToString(",\n") + "\n}\n"
    }

    val WRITTEN = Regex("^\"written\": \"[^\"]*\",\n", RegexOption.MULTILINE)

    fun pathOf(family: Family, repo: File): File = File(repo, "oracle/${family.name}.json")

    /**
     * Write the file, keeping the old "written" stamp when nothing else
     * changed: two runs give the same bytes. True when it was written.
     */
    fun write(family: Family, results: Map<String, Map<String, Any?>>, skipped: Map<String, Any>,
              repo: File, stamp: String, path: File = pathOf(family, repo)): Boolean {
        val body = build(family, results, skipped, repo)
        if (path.exists()) {
            // A checkout with autocrlf hands the file back with CRLF; the
            // same file with the other line endings is the same file.
            val old = path.readText(Charsets.UTF_8).replace("\r\n", "\n")
            val kept = WRITTEN.find(old)
            if (kept != null && old.substring(kept.range.last + 1) == body) return false
        }
        path.parentFile.mkdirs()
        path.writeText("{\n\"written\": \"$stamp\",\n$body", Charsets.UTF_8)
        return true
    }

    // ------------------------------------------------------------------------
    // Holding a file to the readers
    // ------------------------------------------------------------------------

    class Mismatch(val path: String, val key: String, val python: String, val kotlin: String) {
        override fun toString() = "$path  $key\n    oracle: $python\n    kotlin: $kotlin"
    }

    /** What [check] found over a file: the differences, and the counts the tests print. */
    class Report(val frames: Int, val mismatches: List<Mismatch>, val checked: Map<String, Int>,
                 val answered: Map<String, Int>, val folders: Map<String, Int>, val windowFrames: Int) {
        fun print() {
            println("frames: $frames, of which not 1080 x 1920: $windowFrames")
            println("per folder: ${folders.toSortedMap()}")
            println("answers checked per reader: ${checked.toSortedMap()}")
            println("of which not null: ${answered.toSortedMap()}")
        }

        fun summary(shown: Int = 40): String =
            "${mismatches.size} answers differ:\n" + mismatches.take(shown).joinToString("\n")
    }

    /**
     * Every frame the file names through the family's script, and every
     * answer against the file's: a call the file has and the script did
     * not ask, or the other way round, is a difference like any other.
     * `prepare` is what happens to the picture before it is asked --
     * nothing, unless a test is about a picture that was changed on purpose.
     */
    fun check(family: Family, oracle: JsonObject, repo: File, prepare: (Mat) -> Unit = {}): Report =
        check(listOf(family to oracle), repo, prepare).single()

    /**
     * Several families' files over the frames they share: each picture read
     * once and asked by every one of them, and a report per file, in their
     * order. The frames are asked on [OracleWriter.collect]'s threads, the
     * loop the files were written with, and compared here in the file's
     * order, so a report reads the same whichever frame was done first.
     * Until 2026-10-03 this asked one frame after the other, and the
     * fourteen suites that call it were 49 of the 60 minutes `:core:test`
     * had grown to.
     */
    fun check(files: List<Pair<Family, JsonObject>>, repo: File, prepare: (Mat) -> Unit = {}): List<Report> {
        val paths = files.first().second["frames"]!!.jsonObject.keys
        for ((family, oracle) in files) {
            require(oracle["frames"]!!.jsonObject.keys == paths) {
                "oracle/${family.name}.json names another set of frames than oracle/${files.first().first.name}.json"
            }
        }
        for (path in paths) File(repo, path).let { require(it.exists()) { "frame of the oracle is not on disk: $it" } }
        val got = OracleWriter.collect(paths.toList(), files.map { it.first }, repo, prepare = prepare, log = {})
        return files.map { (family, oracle) -> compare(oracle, got.getValue(family.name)) }
    }

    /** One file against what its family answers now, frame by frame in the file's order. */
    private fun compare(oracle: JsonObject, answers: Map<String, Map<String, Any?>>): Report {
        val frames = oracle["frames"]!!.jsonObject
        val mismatches = ArrayList<Mismatch>()
        val checked = HashMap<String, Int>()
        val answered = HashMap<String, Int>()
        val folders = HashMap<String, Int>()
        var windowFrames = 0
        for ((path, expectedEntry) in frames) {
            val got = answers.getValue(path)
            val expected = expectedEntry.jsonObject
            for (key in (expected.keys + got.keys).sorted()) {
                val name = readerName(key)
                if (key != "size") checked.merge(name, 1, Int::plus)
                when {
                    key !in got -> mismatches += Mismatch(path, key, expected[key].toString(), "<not asked>")
                    key !in expected -> mismatches += Mismatch(path, key, "<not in the oracle>", Oracle.encode(got[key]))
                    else -> {
                        val value = got[key]
                        if (key != "size" && value != null && !(value is Map<*, *> && value.containsKey("raises")))
                            answered.merge(name, 1, Int::plus)
                        if (!same(value, expected[key]!!))
                            mismatches += Mismatch(path, key, expected[key].toString(), Oracle.encode(value))
                    }
                }
            }
            folders.merge(path.substringAfter('/').substringBefore('/'), 1, Int::plus)
            if (got["size"] != listOf(1080, 1920)) windowFrames += 1
        }
        return Report(frames.size, mismatches, checked, answered, folders, windowFrames)
    }

    /** A Kotlin answer against the file's JSON: numbers by value, doubles at four places. */
    fun same(got: Any?, expected: JsonElement): Boolean = when (got) {
        null -> expected is JsonNull
        is Boolean -> expected is JsonPrimitive && !expected.isString && expected.booleanOrNull == got
        is String -> expected is JsonPrimitive && expected.isString && expected.content == got
        is Int, is Long -> expected is JsonPrimitive && !expected.isString &&
            expected.content.toBigDecimalOrNull()?.compareTo(BigDecimal((got as Number).toLong())) == 0
        is Double -> expected is JsonPrimitive && !expected.isString &&
            expected.content.toBigDecimalOrNull()?.compareTo(Oracle.round(got)) == 0
        is Map<*, *> -> expected is JsonObject && expected.keys == got.keys &&
            got.all { (k, v) -> same(v, expected[k as String]!!) }
        is Pair<*, *> -> same(listOf(got.first, got.second), expected)
        is List<*> -> expected is JsonArray && expected.size == got.size &&
            got.indices.all { same(got[it], expected[it]) }
        else -> false
    }
}
