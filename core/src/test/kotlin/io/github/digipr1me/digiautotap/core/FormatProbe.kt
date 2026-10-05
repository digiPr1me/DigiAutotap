package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Does a display format read like 1080 x 1920? PLAN_FORMATE.md 6.
 *
 *   gradlew :core:formatProbe --no-daemon > format_probe.txt
 *   gradlew :core:formatProbe --no-daemon --args="1080x2340"
 *   gradlew :core:formatProbe --no-daemon --args="pair a.png b.png@100"
 *
 * Every frame of a format is laid beside its twin on 1080 x 1920, every
 * reader of every family in [OracleFamilies] is asked on both, and every
 * answer that differs is printed with both sides. The frames are the ones
 * in `corpus/tall/` and `corpus/formats/`, found by their names ([Shot]);
 * `pair` takes any two pictures instead, a reference first, for frames that
 * have no twin by the naming rule -- the S26 Ultra's JPGs, the Poco F3's
 * frames against an LDPlayer take.
 *
 * What "the same" means across two formats is not what it means in the
 * oracle, and four rules say it:
 *
 *  * A position is a fraction of game_rect, so it is compared with
 *    [POSITION_TOL]; a share of ink with [SHARE_TOL]; a ratio with
 *    [RATIO_TOL]; an int, a string, a truth value and null exactly; a list
 *    by its length and then element by element. A number that is a count
 *    of *pixels* -- a crop, a glyph's box, the board's calibration, a tab's
 *    ink area -- grows with the canvas and says nothing about the format:
 *    a pixel reader is held by whether it answered ([PIXEL_READERS]), a
 *    pixel field not at all ([PIXEL_FIELDS]).
 *  * A reader is held on the screens the bot asks it on ([HOME]); a
 *    difference anywhere else goes into a column of its own, "fremd".
 *  * A key that carries another reader's answer in its arguments -- the
 *    badge crop of the card at `fy=0.2813` -- is matched by its place
 *    among its own kind, not by the number: the same card reads 0.281 on
 *    the other format ([normalise]).
 *  * A reader whose answer the game changes and the format does not is
 *    *state*: its difference is printed in a column of its own and is not
 *    a finding. [STATE] names them with their reason; and a reader whose
 *    answer already changes between two frames of one screen on one
 *    format, taken seconds apart, is state on that screen too (the rule of
 *    PLAN_FORMATE.md 6), marked "(a/b)".
 *
 * The cut: a name in the new form carries the rows the app cut off the
 * display's top, in pixels -- the camera cutout's inset, and on the V3 frames
 * the band over the canvas ceiling where that was more (Dungeon.canvasTop;
 * PLAN_FORMATE.md 7; notes/formats.md, "The rules", 16) --, and a reader sees
 * the frame from there down, as `DigiAutotapService.grab` handed it over when
 * it was taken. The corpus holds the cut frame already (PLAN_FORMATE.md 5); a
 * picture that is still the display's full height is cut here before a reader
 * is asked; any other height is a name that lies and the pair is refused.
 *
 * Nothing here writes into the corpus or the oracle. The report goes to
 * stdout and, whole, to `core/build/formatProbe.txt`, because the one way
 * to lose a sweep is `| tail` (NOTES.md, "Two ways to lose a corpus sweep").
 */
object FormatProbe {

    const val REF_W = 1080
    const val REF_H = 1920

    /**
     * How far a position may move between two formats and still be the same
     * place: 0.004 of game_rect. Measured 2026-09-26 over the eighteen
     * corpus/tall pairs (1920 against 2340, both uncut), every position
     * field of every reader held on its own screen, both sides answering:
     * 394 numbers, median 0.0002, 99th percentile 0.0015, and the largest
     * 0.0033 -- `general_tab.fw` on summon_general, the tab the long display
     * cuts and the reader mends from its uncut twin (notes/summon.md, "The edge
     * fence is the right answer for a button and the wrong one for half of
     * a pair"). 0.004 is just over that, and an eighth of the flattest
     * button in the pairs (that same tab, fh 0.031): a place that moves more
     * is a reader that found something else.
     */
    const val POSITION_TOL = 0.004

    /**
     * How far a share of ink may move: 0.03. The same pairs hold sixteen,
     * all of them the white X's plate and mark (exit_button, close_x on the
     * summon screens): median 0.0026, largest 0.0034. Ten times that, because
     * sixteen numbers of one piece of artwork are a floor and not a spread,
     * and a share is resampled with the whole picture while a place is
     * found to the pixel.
     */
    const val SHARE_TOL = 0.03

    /**
     * How far a ratio may move, relative to itself: 0.03. A ratio is a
     * length over another length of the same picture -- the title bar's
     * aspect, a quest name's width in slash heights -- and both lengths are
     * whole pixels, so its error is relative. The pairs hold four, the
     * quest name's width, 8.62 against 8.50 slash heights, 0.014 relative;
     * 0.03 is twice that. It decides nothing yet (Quest.kt).
     */
    const val RATIO_TOL = 0.03

    /** Fields that are shares of ink, fill or colour, not places. */
    val SHARE_FIELDS = setOf("white", "fill", "plate", "mark", "share", "score", "v")

    /** Fields that are one length over another. */
    val RATIO_FIELDS = setOf("aspect", "name_w")

    /** Readers whose whole answer is a share. */
    val SHARE_READERS = setOf("runner.bar_state")

    /**
     * Readers whose answer is pixels: a crop (`{"image": [h, w], "sum"}`),
     * the glyph boxes under it, game_rect itself, the board's calibration.
     * Their numbers grow with the canvas, and so, the first sweep said, does
     * their structure: the top card's badge mask held seven glyphs at 1920
     * and six at 2340 while `card_budget`, which reads them, said 2 tickets on
     * both. So what is held of them is whether they answered at all (null, a
     * raise, or something), and what they *mean* is held in the reader that
     * reads them -- card_budget, card_has_attempts, read_grid, the preset
     * slot.
     */
    val PIXEL_READERS = setOf(
        "dungeon.game_rect", "dungeon.badge_crop", "dungeon.badge_glyphs", "dungeon.card_counters",
        "vision.calibrate", "vision.search_patch",
        // The Explore card's box in pixels, [98,0,884,1903] at 1080 x 1920
        // against [42,0,996,2141] at 2160: the picture, not a place.
        "vision.find_card",
        "preset.bar_text", "preset.row_text", "preset.header_name")

    /** Fields in pixels inside an answer that is otherwise fractions: ink area, the figure's pixel place. */
    val PIXEL_FIELDS = mapOf(
        "area" to null,                       // NavTab's ink, px²: every nav_tab, explore_tab, digimon_tab
        "x" to "vision.find_figure", "y" to "vision.find_figure")

    /**
     * The state readers: what the game changes on its own between two takes
     * minutes apart (a format change on LDPlayer is a restart), and not the
     * format. Only readers the game moves without the player; a counter only
     * the player moves -- tickets, ads, the board's claws while the bot is
     * paused -- stays a format reader, because its digits are exactly what a
     * small display breaks.
     */
    val STATE = mapOf(
        "passive.bond_bubble" to "the bubble goes with the Digimon and comes back with the respawn (notes/bond.md, bond bubble)",
        "runner.events_icon" to "the Events tile is not there every day, and a boss's banner covers it (notes/runner.md, the HUD is not the canvas)",
        "quest.stage_number" to "the idle battle moves the stage on by itself",
        "quest.quest_progress" to "the idle battle moves the quest counter by itself",
        "quest.quest_claimable" to "follows the quest counter",
        "explore.plot_timer" to "a plot's timer counts down",
        "explore.plot_state" to "follows the plot's timer",
        "explore.bubble_over" to "water bubbles come and go on the field",
        "explore.seed_refill" to "the seed refill timer counts down",
        "explore.can_refill" to "the can refill timer counts down",
        "explore.popup_timer" to "a plot's time remaining counts down in its popup",
        "runner.obstacles" to "a run frame is one moment of the run",
        "runner.answer" to "follows obstacles",
        "runner.orbs_in_air" to "a run frame is one moment of the run",
        "runner.bar_state" to "the egg bar fills during a run",
        "runner.read_score" to "the score counts during a run",
        "passive.holo_counter" to "the idle battle adds hologram tickets by itself (8885 and 8915 on the tall pairs)",
        "explore.plots" to "follows the plots' timers",
        // The board moves while nobody walks (PLAN_FORMATE.md 8, V10). Three
        // board frames of the phone tour have one geometry, 1080 x 2340 and
        // cell width 200.1 (waterfall, 2520, 2640), and the pyramid under the
        // figure scores 0.942, 0.951 and 0.795 against its template on them;
        // the half-hidden one in the bottom row 0.653, 0.733 and 0.492, on
        // either side of its 0.68. And the figure's wings flap into the two
        // cells left of it: their colour share runs 1.04 to 2.49 at r0 and
        // 1.39 to 1.94 at r1 over the formats, either side of
        // OBJECT_COLOUR_MIN (1.5), so one of the two is "coloured" and which
        // one is the moment's.
        "vision.has_object_colour" to "the figure's wings flap into the cells beside it",
        "vision.read_grid" to "the pyramids shimmer and the figure's wings flap (0.795 to 0.951 on one geometry)",
        "vision.find_figure" to "the figure's score follows its pose",
        "lost_sector.max_floor" to "the highest floor's toast stands about a second after a Subjugate (one frame of 45)",
        // Chef's Special: every guest brings an order of its own, and a wrong
        // skewer takes a life -- two takes of a round are two moments of it.
        "skewer.order" to "each guest brings its own order; two takes are two guests (notes/skewer.md)",
        "skewer.lives" to "a wrong skewer takes a life (notes/skewer.md)",
        // PLAN_EX_MISSIONS.md EX1: the tile's "!" stands while any tab of the
        // window has something to claim, and an EX row turns yellow as the
        // battle counts on (Defeat enemies rose some 1,000 an hour on
        // instance 1 while nobody touched anything).
        "missions.badge" to "the tile's \"!\" comes and goes with whatever mission has something to claim",
        "missions.ex_claims" to "an EX row turns yellow as the battle counts on",
    )

    // Director screens, by their names (Director.kt).
    private val D = Director
    private val DUNGEON_SCREENS = setOf(D.DUNGEON_LIST, D.DIALOG, D.PROMPT, D.EXIT_GAME, D.DOWNLOAD, D.NEWS,
                                        D.UNKNOWN, D.STAGE_FAILED, D.CLAIM_REWARDS, D.MAIN)
    private val RUN_SCREENS = setOf(D.MAIN, D.EVENT_PAGE, D.DIALOG, D.PROMPT, D.UNKNOWN)
    private val NAV_SCREENS = setOf(D.MAIN, D.EXPLORE_MENU, D.DUNGEON_LIST, D.PARTNER_PAGE)

    /**
     * Where the bot asks a reader: the screens, by the director's names, on
     * which the skill that owns it works, or null for every screen. Looked
     * up as `family.reader` first and `family` second. A reader asked on a
     * screen that is not its own answers with whatever the picture offers,
     * and the first sweep of corpus/tall said so again and again: free_seeds
     * reading 22 against 2 off the summon screen, Vision.calibrate finding a
     * board in it, find_buttons counting a label plate of the Explore menu --
     * every one the same picture on both sides, and none of them a question
     * the bot ever puts there (notes/director.md, "Every reader was measured against
     * its own skill's frames"). Such a difference is printed in a column of
     * its own and is not a finding.
     *
     * What recognise and its blobs decide on every other screen is the
     * director's answer, and director.classify is held on every frame.
     * The go-home readers -- the globe, the popups, the X -- are asked from
     * wherever a skill finds itself, so they are held everywhere.
     */
    val HOME: Map<String, Set<String>?> = mapOf(
        "director" to null,
        "dungeon" to DUNGEON_SCREENS,
        "dungeon.home_button" to null, "dungeon.popup_ok" to null, "dungeon.at_main" to null,
        "dungeon.auto_button" to null, "dungeon.stage_failed" to null, "dungeon.claim_button" to null,
        "dungeon.title_bar" to null, "dungeon.menu_button" to null, "dungeon.announcement" to null,
        // The game's sale window and Help tutorial (PLAN_RELEASE_1_3.md B72,
        // B73): classify and every way home ask them on whatever is in front.
        "dungeon.sale_window" to null, "dungeon.help_window" to null,
        // The gear window (K2): classify and every way in ask it on whatever
        // is in front.
        "dungeon.gear_window" to null,
        "dungeon.list_cards" to setOf(D.DUNGEON_LIST), "dungeon.list_at_top" to setOf(D.DUNGEON_LIST),
        "dungeon.list_at_bottom" to setOf(D.DUNGEON_LIST), "dungeon.badge_crop" to setOf(D.DUNGEON_LIST),
        "dungeon.badge_glyphs" to setOf(D.DUNGEON_LIST), "dungeon.card_counters" to setOf(D.DUNGEON_LIST),
        "dungeon.card_budget" to setOf(D.DUNGEON_LIST), "dungeon.card_has_attempts" to setOf(D.DUNGEON_LIST),
        "passive" to setOf(D.MAIN, D.PARTNER_WINDOW, D.PARTNER_PAGE),
        "summon" to setOf(D.SUMMON, D.SUMMON_DIALOG, D.NO_TICKETS),
        "summon.exit_button" to null,
        "quest" to setOf(D.MAIN, D.CLAIM_REWARDS),
        "quest.close_x" to null,
        "explore" to setOf(D.FIELD, D.FIELD_DIALOG),
        // The seed menu's readers are asked only once the menu is open
        // (FarmSkill: seedMenu after the tap on an empty plot, selectButton
        // on the frame after the slot's tap), and seedMenu itself refuses a
        // field that is not dimmed. On the bare field selectButton answers
        // with the grass, share 0.14 to 0.19 against a button's 0.0106, and
        // seed_slots with a plot: the phone tour's V7, which was this.
        "explore.seed_slots" to setOf(D.FIELD_DIALOG), "explore.seed_selected" to setOf(D.FIELD_DIALOG),
        "explore.select_button" to setOf(D.FIELD_DIALOG),
        // The dialogs' own readers (Farm.kt, PLAN_MEAT_FIELD_GIESSEN.md 5.4):
        // a slot's count, the can dialog and its box, the popup's time, the
        // ad offer -- each asked only once its dialog is open. The ad limit
        // is the game's message box, which the director calls a dialog.
        "explore.seed_slot_count" to setOf(D.FIELD_DIALOG), "explore.popup_timer" to setOf(D.FIELD_DIALOG),
        "explore.boost_popup" to setOf(D.FIELD_DIALOG), "explore.boost_cans" to setOf(D.FIELD_DIALOG),
        "explore.boost_amount" to setOf(D.FIELD_DIALOG), "explore.ad_dialog" to setOf(D.FIELD_DIALOG),
        "explore.ad_limit" to setOf(D.FIELD_DIALOG, D.DIALOG),
        "explore.nav_tab" to NAV_SCREENS, "explore.explore_tab" to NAV_SCREENS, "explore.digimon_tab" to NAV_SCREENS,
        "explore.explore_menu" to setOf(D.EXPLORE_MENU), "explore.world_search_card" to setOf(D.EXPLORE_MENU),
        "explore.meat_field_card" to setOf(D.EXPLORE_MENU), "explore.close_button" to setOf(D.BOARD),
        "vision" to setOf(D.BOARD),
        "runner" to RUN_SCREENS,
        "runner.events_icon" to setOf(D.MAIN),
        "preset" to setOf(D.PRESET),
        // The Overdrive page opens on Base, which the director calls
        // unknown; the tab row is what the skill asks there to find Drive
        // Ability (PLAN_DAILY_LOST_SECTOR_PRESETS.md G6).
        "preset.overdrive_tabs" to setOf(D.PRESET, D.UNKNOWN),
        // The Crests page and the tower's panel over it, which is a dialog.
        "lost_sector" to setOf(D.LOST_SECTOR, D.DIALOG),
        // The EX Missions task (PLAN_EX_MISSIONS.md): the tile on the plain
        // main screen, the window and its tabs on either of its two screens,
        // the EX tab's header and Claims on the EX tab.
        "missions" to setOf(D.MAIN, D.MISSIONS, D.EX_MISSIONS),
        "missions.tile" to setOf(D.MAIN), "missions.badge" to setOf(D.MAIN),
        "missions.window" to setOf(D.MISSIONS, D.EX_MISSIONS),
        "missions.ex_window" to setOf(D.EX_MISSIONS), "missions.ex_claims" to setOf(D.EX_MISSIONS),
        // The Events window's cards (PLAN_SKEWER.md 3.2): asked with the
        // window open, which the director does not name.
        "events" to setOf(D.UNKNOWN),
        // Chef's Special (PLAN_SKEWER.md SK1): the page, its stage popup, and
        // the round with its two dialogs; what a round carries only in play.
        "skewer" to setOf(D.SKEWER_MENU, D.SKEWER_STAGE, D.SKEWER_PLAY, D.SKEWER_PAUSE, D.SKEWER_OVER),
        "skewer.menu" to setOf(D.SKEWER_MENU), "skewer.stage" to setOf(D.SKEWER_STAGE),
        "skewer.grid" to setOf(D.SKEWER_PLAY), "skewer.order" to setOf(D.SKEWER_PLAY),
        "skewer.lives" to setOf(D.SKEWER_PLAY),
    )

    /** Is `reader` (`family.reader`) asked by the bot on any of these screens? */
    fun isHome(reader: String, screens: Collection<String>): Boolean {
        val family = reader.substringBefore('.')
        val home = if (reader in HOME) HOME[reader] else HOME[family]
        return home == null || screens.any { it in home }
    }

    /** The director's screen in a frame's answers. */
    fun screenOf(answers: Map<String, JsonElement>?): String? =
        ((answers?.get("director.classify") as? JsonObject)?.get("screen") as? JsonPrimitive)?.content

    // ------------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------------

    /**
     * One picture and what its name says about it. `take` is the old tall
     * pairs' letter (`a`, `b`) or the new form's `hhmmss`; `inset` the rows
     * the readers do not see at the top; `cut` whether the picture on disk
     * still has them.
     */
    class Shot(val file: File, val label: String, val screen: String, val w: Int, val h: Int,
               val cutout: String, val inset: Int, val take: String) {
        val format get() = "${w}x${h}_$cutout$inset"
        val isReference get() = w == REF_W && h == REF_H && inset == 0 && cutout in setOf("none", "")
        override fun toString() = label
    }

    /** corpus/tall: `<screen>_<W>x<H>_<a|b>.png`, a display without a cutout. */
    private val OLD = Regex("""^(.+)_(\d+)x(\d+)_([a-z])\.png$""")

    /** PLAN_FORMATE.md 7: `<screen>_<W>x<H>_<cutout><inset px>_<hhmmss>.png`. */
    private val NEW = Regex("""^(.+)_(\d+)x(\d+)_([a-z]+)(\d+)_(\d{6})\.png$""")

    fun parse(file: File, label: String): Shot? {
        NEW.matchEntire(file.name)?.let { m ->
            val (screen, w, h, cutout, inset, t) = m.destructured
            return Shot(file, label, screen, w.toInt(), h.toInt(), cutout, inset.toInt(), t)
        }
        OLD.matchEntire(file.name)?.let { m ->
            val (screen, w, h, tag) = m.destructured
            return Shot(file, label, screen, w.toInt(), h.toInt(), "none", 0, tag)
        }
        return null
    }

    private fun seconds(hhmmss: String): Int? =
        if (hhmmss.length == 6 && hhmmss.all { it.isDigit() })
            hhmmss.substring(0, 2).toInt() * 3600 + hhmmss.substring(2, 4).toInt() * 60 + hhmmss.substring(4).toInt()
        else null

    class Pairing(val pairs: List<Pair<Shot, Shot>>, val orphans: List<Shot>)

    /**
     * Every non-reference shot with its reference twin: the same screen in
     * the same folder on 1080 x 1920 without a cutout. With as many takes on
     * both sides they pair in order of time (the main screen's two frames,
     * 3 s apart, against the reference's two); otherwise each goes to the
     * reference take nearest in time, and an old-form letter to the same
     * letter.
     */
    fun pairUp(shots: List<Shot>): Pairing {
        val pairs = ArrayList<Pair<Shot, Shot>>()
        val orphans = ArrayList<Shot>()
        val byFolder = shots.groupBy { it.file.parentFile.canonicalPath }
        for (group in byFolder.values) {
            val refs = group.filter { it.isReference }.groupBy { it.screen }
            val others = group.filter { !it.isReference }.groupBy { it.format to it.screen }
            for ((key, mine) in others.toSortedMap(compareBy({ it.first }, { it.second }))) {
                val theirs = refs[key.second].orEmpty().sortedBy { it.take }
                val sorted = mine.sortedBy { it.take }
                when {
                    theirs.isEmpty() -> orphans += sorted
                    theirs.size == sorted.size -> sorted.zip(theirs).forEach { (s, r) -> pairs += r to s }
                    else -> for (s in sorted) {
                        val same = theirs.firstOrNull { it.take == s.take }
                        val st = seconds(s.take)
                        val near = same ?: if (st == null) theirs.first()
                                            else theirs.minBy { r -> seconds(r.take)?.let { abs(it - st) } ?: Int.MAX_VALUE }
                        pairs += near to s
                    }
                }
            }
        }
        return Pairing(pairs, orphans)
    }

    // ------------------------------------------------------------------------
    // Pictures
    // ------------------------------------------------------------------------

    /**
     * The picture as a reader sees it: `inset` rows off the top when the
     * picture on disk still has them (it is `h` tall), as it is when it was
     * cut already (`h - inset` tall). `forced` is an inset given by hand
     * (`pair a.png@100`), cut whatever the height.
     */
    fun load(shot: Shot, forced: Int? = null): Mat {
        val img = Imgcodecs.imread(shot.file.path)
        require(!img.empty()) { "cannot read ${shot.file}" }
        val inset = forced ?: shot.inset
        val cut = when {
            forced != null -> forced > 0
            inset == 0 -> false
            img.rows() == shot.h -> true
            img.rows() == shot.h - inset -> false
            else -> throw IllegalArgumentException(
                "${shot.label}: ${img.cols()} x ${img.rows()} on disk, and the name says ${shot.w} x ${shot.h} " +
                    "with $inset px cut -- neither the whole display nor the cut frame")
        }
        if (!cut) return OracleFamilies.framed(img, shot.file.name)
        val out = img.submat(inset, img.rows(), 0, img.cols()).clone()
        img.release()
        return OracleFamilies.framed(out, shot.file.name)
    }

    // ------------------------------------------------------------------------
    // Answers
    // ------------------------------------------------------------------------

    private val NUMBER_ARG = Regex("""=(-?\d+(?:\.\d+)?)(?=[,)])""")

    /**
     * `badge_crop(fy=0.2813,fh=0.2081)` -> `badge_crop(fy=#,fh=#)#0`: an
     * argument that is a number is another reader's answer, which moves with
     * the format; the n-th such call is the n-th card either way. A key
     * without a number in it is its own name.
     */
    fun normalise(keys: List<String>): Map<String, String> {
        val seen = HashMap<String, Int>()
        val out = LinkedHashMap<String, String>()
        for (key in keys) {
            if (!NUMBER_ARG.containsMatchIn(key)) { out[key] = key; continue }
            val shape = NUMBER_ARG.replace(key, "=#")
            val n = seen.merge(shape, 1, Int::plus)!! - 1
            out[key] = "$shape#$n"
        }
        return out
    }

    /** Every family's answers on one picture, `family.normalised key` -> answer as JSON. */
    fun ask(img: Mat, vision: Vision): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for (f in OracleFamilies.ALL) {
            val raw = OracleFamilies.answers(f, img, vision)
            val names = normalise(raw.keys.filter { it != "size" })
            for ((key, norm) in names) out["${f.name}.$norm"] = Json.parseToJsonElement(Oracle.encode(raw[key]))
        }
        return out
    }

    /** `summon.general_tab` from `summon.general_tab`, `dungeon.badge_crop` from `dungeon.badge_crop(fy=#,fh=#)#0`. */
    fun reader(key: String): String = key.substringBefore('(').substringBefore('#')

    // ------------------------------------------------------------------------
    // Comparing
    // ------------------------------------------------------------------------

    enum class Kind { POSITION, SHARE, RATIO }

    /** One numeric field that was compared, for the table of what the tolerances were measured on. */
    class Delta(val reader: String, val field: String, val kind: Kind, val d: Double, val where: String)

    /**
     * Where two answers part, or null when they are the same by the rules
     * above. `deltas` collects every numeric difference that was within the
     * structure, whether it passed or not.
     */
    fun differ(reader: String, a: JsonElement, b: JsonElement, deltas: MutableList<Delta>?, where: String,
               field: String = "", path: String = ""): String? {
        if (reader in PIXEL_READERS && path.isEmpty()) {
            fun kind(e: JsonElement) = when {
                e is JsonNull -> "null"
                e is JsonObject && "raises" in e -> "raises"
                else -> "an answer"
            }
            return if (kind(a) == kind(b)) null else "answer: ${kind(a)} against ${kind(b)}"
        }
        val pixels = field in PIXEL_FIELDS && PIXEL_FIELDS[field].let { it == null || it == reader }
        return when {
            a is JsonNull || b is JsonNull -> if (a is JsonNull && b is JsonNull) null else "${path.ifEmpty { "answer" }}: one side null"
            a is JsonObject && b is JsonObject -> {
                if (a.keys != b.keys) return "${path.ifEmpty { "answer" }}: keys ${a.keys.sorted()} against ${b.keys.sorted()}"
                a.keys.sorted().firstNotNullOfOrNull { k -> differ(reader, a[k]!!, b[k]!!, deltas, where, k, "$path.$k") }
            }
            a is JsonArray && b is JsonArray -> {
                if (a.size != b.size) return "${path.ifEmpty { "answer" }}: ${a.size} items against ${b.size}"
                a.indices.firstNotNullOfOrNull { i -> differ(reader, a[i], b[i], deltas, where, field, "$path[$i]") }
            }
            a is JsonPrimitive && b is JsonPrimitive -> {
                if (a.isString || b.isString) return if (a.isString && b.isString && a.content == b.content) null
                                                      else "${path.ifEmpty { "answer" }}: ${a} against ${b}"
                val an = a.content.toDoubleOrNull(); val bn = b.content.toDoubleOrNull()
                if (an == null || bn == null) return if (a.content == b.content) null else "${path.ifEmpty { "answer" }}: ${a.content} against ${b.content}"
                if (pixels) return null
                val real = '.' in a.content || '.' in b.content || 'E' in a.content.uppercase() || 'E' in b.content.uppercase()
                if (!real) return if (an == bn) null else "${path.ifEmpty { "answer" }}: ${a.content} against ${b.content}"
                val kind = when {
                    field in RATIO_FIELDS -> Kind.RATIO
                    field in SHARE_FIELDS || reader in SHARE_READERS -> Kind.SHARE
                    else -> Kind.POSITION
                }
                val d = if (kind == Kind.RATIO) abs(an - bn) / maxOf(abs(an), abs(bn), 1e-9) else abs(an - bn)
                deltas?.add(Delta(reader, field.ifEmpty { "answer" }, kind, d, where))
                val tol = when (kind) { Kind.SHARE -> SHARE_TOL; Kind.RATIO -> RATIO_TOL; Kind.POSITION -> POSITION_TOL }
                if (d > tol + 1e-9) "${path.ifEmpty { "answer" }}: ${a.content} against ${b.content} (%.4f > %s %s)".format(d, kind.name.lowercase(), tol)
                else null
            }
            else -> "${path.ifEmpty { "answer" }}: ${a::class.simpleName} against ${b::class.simpleName}"
        }
    }

    class Finding(val key: String, val why: String, val ref: JsonElement?, val here: JsonElement?)

    /**
     * Every key of either side that differs. `deltas`, when given, gets the
     * numeric differences of the readers `keep` says yes to -- the ones that
     * are held, so that the table the tolerances stand on is not the noise
     * of readers asked where they do not belong.
     */
    fun compare(ref: Map<String, JsonElement>, here: Map<String, JsonElement>,
                deltas: MutableList<Delta>?, where: String, keep: (String) -> Boolean = { true }): List<Finding> {
        val out = ArrayList<Finding>()
        for (key in (ref.keys + here.keys).distinct().sortedWith(keyOrder)) {
            val a = ref[key]; val b = here[key]
            val mine = if (deltas != null && keep(reader(key))) ArrayList<Delta>() else null
            val why = when {
                a == null -> "asked here, not on the reference"
                b == null -> "asked on the reference, not here"
                else -> differ(reader(key), a, b, mine, where)
            }
            if (mine != null) deltas!!.addAll(mine)
            if (why != null) out += Finding(key, why, a, b)
        }
        return out
    }

    private val familyIndex = OracleFamilies.ALL.withIndex().associate { it.value.name to it.index }
    private val keyOrder = compareBy<String>({ familyIndex[it.substringBefore('.')] ?: 99 }, { it })

    // ------------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------------

    class Job(val ref: Shot, val here: Shot, val refInset: Int? = null, val hereInset: Int? = null)

    private fun clip(e: JsonElement?, max: Int = 600): String {
        val s = e?.toString() ?: "<not asked>"
        return if (s.length <= max) s else s.substring(0, max) + " ... (${s.length} chars)"
    }

    fun run(repo: File, jobs: List<Job>, orphans: List<Shot>, source: String, log: (String) -> Unit) {
        val started = System.nanoTime()
        log("formatProbe  ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}  opencv ${Core.VERSION}")
        log("source: $source")
        log("tolerances: position $POSITION_TOL of game_rect, share $SHARE_TOL, ratio $RATIO_TOL relative; pixel readers by whether they answer")
        log("${jobs.size} pairs, ${OracleFamilies.ALL.size} families")

        // Every picture once, with its forced inset if the pair names one.
        val pictures = LinkedHashMap<String, Pair<Shot, Int?>>()
        for (j in jobs) {
            pictures.putIfAbsent("${j.ref.file.canonicalPath}@${j.refInset}", j.ref to j.refInset)
            pictures.putIfAbsent("${j.here.file.canonicalPath}@${j.hereInset}", j.here to j.hereInset)
        }
        // For the (a/b) rule, every other take of a screen that already has one here.
        val answers = ConcurrentHashMap<String, Map<String, JsonElement>>()
        val sizes = ConcurrentHashMap<String, String>()
        val rects = ConcurrentHashMap<String, String>()
        val refused = ConcurrentHashMap<String, String>()
        val vision = ThreadLocal.withInitial { Vision(ClassPathAssets) }
        val pool = Executors.newFixedThreadPool(minOf(Runtime.getRuntime().availableProcessors(), 8))
        try {
            pictures.map { (id, p) ->
                pool.submit {
                    try {
                        val img = load(p.first, p.second)
                        try {
                            sizes[id] = "${img.cols()} x ${img.rows()}"
                            rects[id] = Oracle.encode(Dungeon.gameRect(img).toOracle())
                            answers[id] = ask(img, vision.get())
                        } finally {
                            img.release()
                        }
                    } catch (e: IllegalArgumentException) {
                        refused[id] = e.message ?: e.toString()
                    }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        fun id(s: Shot, inset: Int?) = "${s.file.canonicalPath}@$inset"

        // State by the (a/b) rule: per format and screen, the readers whose
        // answers differ between two takes of that screen on either side.
        val abState = HashMap<Pair<String, String>, MutableSet<String>>()
        val byScreen = jobs.groupBy { it.here.format to it.here.screen }
        for ((key, js) in byScreen) {
            val set = HashSet<String>()
            for (side in listOf(js.map { id(it.ref, it.refInset) }.distinct(), js.map { id(it.here, it.hereInset) }.distinct())) {
                val got = side.mapNotNull { answers[it] }
                for (i in got.indices) for (k in i + 1 until got.size)
                    compare(got[i], got[k], null, "").forEach { set += reader(it.key) }
            }
            abState[key] = set
        }

        val deltas = ArrayList<Delta>()
        val byFormat = jobs.groupBy { it.here.format }.toSortedMap()
        for ((format, js) in byFormat) {
            log("")
            log("=".repeat(78))
            val first = js.first()
            log("format $format   ${sizes[id(first.here, first.hereInset)] ?: "?"} as the readers see it, " +
                "game_rect ${rects[id(first.here, first.hereInset)] ?: "?"}   (reference game_rect ${rects[id(first.ref, first.refInset)] ?: "?"})")
            val screensDiffering = sortedSetOf<String>()
            var pairsSame = 0
            val body = ArrayList<String>()
            val stateLines = ArrayList<String>()
            val awayLines = ArrayList<String>()
            for (j in js.sortedWith(compareBy({ it.here.screen }, { it.here.take }))) {
                val rid = id(j.ref, j.refInset); val hid = id(j.here, j.hereInset)
                val head = "${j.here.label}   <- ${j.ref.label}   [${listOfNotNull(screenOf(answers[rid]), screenOf(answers[hid])).distinct().joinToString(" / ")}]"
                val bad = refused[rid] ?: refused[hid]
                if (bad != null) { body += "  $head"; body += "      REFUSED: $bad"; screensDiffering += j.here.screen; continue }
                val ra = answers.getValue(rid); val ha = answers.getValue(hid)
                val screens = listOfNotNull(screenOf(ra), screenOf(ha)).distinct()
                val ab = abState[j.here.format to j.here.screen].orEmpty()
                fun isState(r: String) = r in STATE || r in ab
                val findings = compare(ra, ha, deltas, j.here.label) { r -> isHome(r, screens) && !isState(r) }
                val (away, mine) = findings.partition { !isHome(reader(it.key), screens) }
                val (state, real) = mine.partition { isState(reader(it.key)) }
                // One line per reader off its screen, however many keys it asked.
                for ((r, fs) in away.groupBy { reader(it.key) }) {
                    awayLines += "  ${j.here.label}   $r   [${screens.joinToString("/")}]   " +
                        (if (fs.size > 1) "${fs.size} keys, first: " else "") + "${fs.first().key.substringAfter('.')}  ${fs.first().why}"
                }
                if (real.isEmpty()) pairsSame += 1 else screensDiffering += j.here.screen
                if (real.isNotEmpty()) {
                    body += "  $head"
                    for (f in real) {
                        body += "      ${f.key}   ${f.why}"
                        body += "          ref:  ${clip(f.ref)}"
                        body += "          here: ${clip(f.here)}"
                    }
                }
                for (f in state) {
                    val why = if (reader(f.key) in STATE) "state" else "state (a/b)"
                    stateLines += "  ${j.here.label}   ${f.key}   [$why]   ${f.why}"
                    stateLines += "          ref:  ${clip(f.ref, 200)}"
                    stateLines += "          here: ${clip(f.here, 200)}"
                }
            }
            val screens = js.map { it.here.screen }.toSortedSet()
            log("  ${screens.size} screens, ${screens.size - screensDiffering.size} the same, ${screensDiffering.size} differing" +
                "   (${js.size} pairs, $pairsSame the same)" +
                (if (screensDiffering.isNotEmpty()) "   differing: ${screensDiffering.joinToString(", ")}" else ""))
            val formatOrphans = orphans.filter { it.format == format }
            if (formatOrphans.isNotEmpty()) log("  no twin: ${formatOrphans.joinToString(", ") { it.label }}")
            if (body.isNotEmpty()) { log(""); log("  DIFFERS"); body.forEach(log) }
            if (stateLines.isNotEmpty()) { log(""); log("  ZUSTAND (state, not a finding)"); stateLines.forEach(log) }
            if (awayLines.isNotEmpty()) { log(""); log("  FREMD (a reader off the screens the bot asks it on, not a finding)"); awayLines.forEach(log) }
        }
        val formatsWithout = orphans.map { it.format }.toSortedSet() - byFormat.keys
        for (f in formatsWithout) { log(""); log("format $f: no twin for ${orphans.filter { it.format == f }.joinToString(", ") { it.label }}") }

        // What the tolerances stand on: every numeric difference that was
        // compared, by kind and by field.
        log("")
        log("=".repeat(78))
        log("measured |d| over every compared number (both sides answered, same structure)")
        for (kind in Kind.values()) {
            val ds = deltas.filter { it.kind == kind }.sortedBy { it.d }
            if (ds.isEmpty()) continue
            fun q(p: Double) = ds[((ds.size - 1) * p).toInt()].d
            val worst = ds.last()
            log("  %-8s n %5d   median %.4f   p95 %.4f   p99 %.4f   max %.4f  (%s.%s, %s)".format(
                kind.name.lowercase(), ds.size, q(0.5), q(0.95), q(0.99), worst.d, worst.reader, worst.field, worst.where))
            val byField = ds.groupBy { "${it.reader}.${it.field}" }
                .mapValues { (_, v) -> v.maxBy { it.d } }.values.sortedByDescending { it.d }.take(12)
            for (w in byField) log("      %-44s max %.4f  %s".format("${w.reader}.${w.field}", w.d, w.where))
        }
        log("")
        log("done in ${(System.nanoTime() - started) / 1_000_000_000} s")
    }

    /** `path` or `path@inset`, against the repository first and the working directory second. */
    fun resolve(repo: File, arg: String): Pair<File, Int?> {
        val m = Regex("""^(.*)@(\d+)$""").matchEntire(arg)
        val path = m?.groupValues?.get(1) ?: arg
        val file = File(path).let { if (it.isAbsolute) it else File(repo, path).takeIf { f -> f.exists() } ?: it.absoluteFile }
        require(file.exists()) { "no such picture: $arg" }
        return file to m?.groupValues?.get(2)?.toInt()
    }

    /** The label a picture is printed under: its path inside the repository when it is in there. */
    fun label(repo: File, file: File): String {
        val r = repo.canonicalFile; val f = file.canonicalFile
        return if (f.path.startsWith(r.path + File.separator)) f.relativeTo(r).path.replace(File.separatorChar, '/') else f.path
    }

    /** Every named shot in corpus/tall and corpus/formats, recursive. */
    fun corpusShots(repo: File): List<Shot> {
        val out = ArrayList<Shot>()
        for (folder in listOf("tall", "formats")) {
            val base = File(repo, "${OracleFamilies.CORPUS}/$folder")
            if (!base.isDirectory) continue
            base.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                parse(f, label(repo, f))?.let { out += it }
            }
        }
        return out
    }
}

fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val report = StringBuilder()
    val log: (String) -> Unit = { line -> println(line); report.append(line).append('\n') }
    try {
        if (args.firstOrNull() == "pair") {
            val rest = args.drop(1)
            if (rest.isEmpty() || rest.size % 2 != 0) {
                println("pair wants pictures two by two: a reference and a format, `pair ref.png here.png[@inset] ...`")
                exitProcess(2)
            }
            val jobs = rest.chunked(2).map { (a, b) ->
                val (fa, ia) = FormatProbe.resolve(repo, a)
                val (fb, ib) = FormatProbe.resolve(repo, b)
                fun shot(f: File) = FormatProbe.parse(f, FormatProbe.label(repo, f))
                    ?: FormatProbe.Shot(f, FormatProbe.label(repo, f), f.nameWithoutExtension, 0, 0, "pair", 0, "")
                val ra = shot(fa); val rb = shot(fb)
                // An unnamed picture's format is its own size and the cut given by hand.
                fun named(s: FormatProbe.Shot, inset: Int?): FormatProbe.Shot {
                    if (s.w > 0) return s
                    val img = Imgcodecs.imread(s.file.path)
                    require(!img.empty()) { "cannot read ${s.file}" }
                    val out = FormatProbe.Shot(s.file, s.label, s.screen, img.cols(), img.rows(), "pair", inset ?: 0, "")
                    img.release()
                    return out
                }
                FormatProbe.Job(named(ra, ia), named(rb, ib), ia, ib)
            }
            FormatProbe.run(repo, jobs, emptyList(), "pair, ${jobs.size} given on the command line", log)
        } else {
            val shots = FormatProbe.corpusShots(repo)
            val pairing = FormatProbe.pairUp(shots)
            val jobs = pairing.pairs.map { (r, h) -> FormatProbe.Job(r, h) }
                .filter { j -> args.isEmpty() || args.any { j.here.format.startsWith(it) || j.here.label.contains(it) } }
            val orphans = pairing.orphans.filter { o -> args.isEmpty() || args.any { o.format.startsWith(it) || o.label.contains(it) } }
            if (jobs.isEmpty()) {
                println("no pairs${if (args.isNotEmpty()) " for ${args.toList()}" else ""}; " +
                    "formats in the corpus: ${pairing.pairs.map { it.second.format }.toSortedSet()}")
                exitProcess(2)
            }
            FormatProbe.run(repo, jobs, orphans, "corpus/tall and corpus/formats" +
                (if (args.isNotEmpty()) ", only ${args.toList()}" else ""), log)
        }
    } finally {
        val out = File(repo, "core/build/formatProbe.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println("(the whole report: $out)")
    }
}
