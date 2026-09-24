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
        rec.exitOk?.let { ok -> f.ask("confirm_kind(ok_button=recognise.exit_ok)") { Dungeon.confirmKind(img, ok) } }
        f.ask("reward_sheet") { Dungeon.rewardSheet(img) }
        // The panel's own counter hangs off its button: Attempt where there is
        // one, the ad button on a panel at 0/2 -- the one the skill reads it
        // from. Asked wherever recognise found either, dialog or not.
        val counterButton = rec.attempt ?: rec.ad
        if (counterButton != null) {
            val which = if (rec.attempt != null) "attempt" else "ad"
            f.ask("panel_tickets(button=recognise.$which)") { Dungeon.panelTickets(img, counterButton) }
        }
        // The party panel's counter stands above the panel, not over a button.
        f.ask("header_tickets") { Dungeon.headerTickets(img) }
        f.ask("title_bar") { Startup.titleBar(img)?.toOracle() }
        f.ask("at_main") { Startup.atMain(img) }
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
        val slots = Farm.seedSlots(img)
        f.ask("seed_slots") { slots.map { it.toOracle() } }
        // `slot=seed_slots[i]`: the i-th answer of seed_slots on this frame.
        slots.forEachIndexed { i, slot -> f.ask("seed_selected(slot=seed_slots[$i])") { Farm.seedSelected(img, slot) } }
        f.ask("select_button") { Farm.selectButton(img)?.toOracle() }
        f.ask("seed_menu") {
            val (menuSlots, button) = Farm.seedMenu(img)
            listOf(menuSlots?.map { it.toOracle() }, button?.toOracle())
        }
        f.ask("water_button") { Farm.waterButton(img)?.toOracle() }
        f.ask("water_popup") { Farm.waterPopup(img)?.toOracle() }
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
        listOf("game_rect", "find_buttons", "list_cards", "badge_crop", "badge_glyphs",
               "card_counters", "card_budget", "card_has_attempts", "confirm_kind",
               "party_slots_filled", "popup_ok", "claim_button", "stage_failed",
               "auto_button", "home_button", "recognise", "reward_sheet", "panel_tickets",
               "header_tickets",
               "title_bar", "at_main"),
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
        listOf("quest_progress", "quest_card", "quest_claimable", "stage_number", "close_x"),
        depends = listOf("Cv"), script = ::quest)
    val EXPLORE = Family(
        "explore", listOf("Explore", "Farm"),
        listOf("nav_tab", "explore_tab", "digimon_tab", "explore_menu",
               "world_search_card", "meat_field_card", "close_button",
               "meat_field_screen", "bubble_over", "plot_timer", "plot_badge_kind",
               "plot_harvest", "plot_state", "plots", "free_seeds", "seed_refill",
               "seed_slots", "seed_selected", "select_button", "seed_menu",
               "water_button", "water_popup"),
        depends = listOf("Cv"), script = ::explore)
    val VISION = Family(
        "vision", listOf("Vision"),
        listOf("find_card", "calibrate", "skill_button_ok", "read_grid",
               "search_patch", "has_object_colour", "find_figure", "banner_visible",
               "classify_banner", "read_number", "read_counters"),
        data = listOf("templates", "digits"), depends = listOf("Cv"), script = ::vision)
    val RUNNER = Family(
        "runner", listOf("Runner"),
        listOf("events_dialog", "event_page", "result_dialog", "pause_dialog", "missions_dialog",
               "claim_buttons", "missions_x", "reward_overlay", "obstacles", "answer",
               "orbs_in_air", "bar_state", "read_score"),
        depends = listOf("Summon", "Dungeon", "Cv"), script = ::runner)
    val DIRECTOR = Family(
        "director", listOf("Director"), listOf("classify"),
        depends = listOf("Dungeon", "Startup", "Passive", "Bond", "Summon", "Explore", "Farm", "Runner", "Cv"),
        script = ::director)

    val ALL: List<Family> = listOf(DUNGEON, PASSIVE, SUMMON, QUEST, EXPLORE, VISION, RUNNER, DIRECTOR)
    val BY_NAME: Map<String, Family> = ALL.associateBy { it.name }

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
    const val CORPUS = "corpus"

    /**
     * The JUnit tag every suite carries that reads a frame out of [CORPUS]:
     * the eight `*OracleTest`, and nothing else. They are 477 of the 506
     * seconds `:core:test` takes (measured 2026-09-21), so `:core:fastTest`
     * leaves them out and `:core:test` still runs everything, exactly as
     * NOTES.md says. The same string stands once more in
     * `core/build.gradle.kts`, which is the only other place that may name it.
     *
     * A suite that starts reading the corpus gets the tag; one that stops
     * reading it loses the tag. `CorpusTest` does not carry it -- it scans
     * the *sources* for a write site that names the folder and never opens
     * a frame.
     */
    const val CORPUS_TAG = "corpus"
    val FRAME_ASPECT = 1.6..2.5
    const val FRAME_MIN_W = 400

    /** Is a picture of this size a whole emulator frame, not a crop? */
    fun isFrame(w: Int, h: Int): Boolean = w >= FRAME_MIN_W && (h.toDouble() / w) in FRAME_ASPECT

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
    fun check(family: Family, oracle: JsonObject, repo: File, vision: Vision,
              prepare: (Mat) -> Unit = {}): Report {
        val frames = oracle["frames"]!!.jsonObject
        val mismatches = ArrayList<Mismatch>()
        val checked = HashMap<String, Int>()
        val answered = HashMap<String, Int>()
        val folders = HashMap<String, Int>()
        var windowFrames = 0
        for ((path, expectedEntry) in frames) {
            val file = File(repo, path)
            require(file.exists()) { "frame of the oracle is not on disk: $file" }
            val img = Imgcodecs.imread(file.path)
            require(!img.empty()) { "could not read $path" }
            prepare(img)
            val got = answers(family, img, vision)
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
            if (img.cols() != 1080 || img.rows() != 1920) windowFrames += 1
            img.release()
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
