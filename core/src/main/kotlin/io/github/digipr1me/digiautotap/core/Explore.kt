package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.abs

/**
 * explore.py's readers, carried over line for line: the bottom-nav tabs
 * (`nav_tab`, `explore_tab`, `digimon_tab`), the Explore menu and its two
 * cards (`explore_menu`, `world_search_card`, `meat_field_card`) and the
 * board's X (`close_button`). The navigator (`Nav`) is not here; it comes
 * with the Digital World Search skill's own session, under the director.
 *
 * Every constant keeps its Python name and value, and the sentence that says
 * where it came from. A number here is changed in explore.py first, with a
 * measurement, then the oracle is written again, then this file follows --
 * never the other way round (NOTES.md, "Two implementations, one
 * direction"). The frame is BGR, uint8, as `cv2.imread` gives it.
 */
object Explore {

    /** A tap target: centre of a thing, as fractions of the reference window. */
    data class Target(val fx: Double, val fy: Double) {
        fun toOracle(): Map<String, Any?> = mapOf("fx" to fx, "fy" to fy)
    }

    /**
     * A coloured clump of a hue search: centre and its share of the reference
     * area. `_card_blobs`, `_field_blobs` and `farm._hue_blobs` all keep these as
     * dicts with the same three keys.
     */
    data class Blob(val fx: Double, val fy: Double, val share: Double) {
        fun toOracle(): Map<String, Any?> = mapOf("fx" to fx, "fy" to fy, "share" to share)
    }

    // ------------------------------------------------------------------------
    // explore_tab -- the Explore tab in the bottom nav bar
    // ------------------------------------------------------------------------
    // The tab carries a red notification badge that comes and goes, and NOTES.md
    // is explicit that nothing may hang on it in either direction. It cannot: the
    // badge sits on top of the icon, above this band entirely, and it is red,
    // outside a mask with no hue term at all.
    //
    // Found at two things that are never the badge: the globe (dungeon.home_button,
    // which also proves nothing is dimming the bar), and the label clump nearest
    // NAV_EXPLORE_DX to its right. fy is taken from the globe rather than the
    // label -- the label of whichever tab is currently open turns white and
    // drifts a little, the globe's height does not.
    val NAV_LABEL_BAND = doubleArrayOf(0.955, 0.985)
    val NAV_LABEL_WHITE = Dungeon.Hsv(intArrayOf(0, 0, 215), intArrayOf(179, 235, 255))
    const val NAV_LABEL_MIN_AREA = 3
    // Clumps closer than this are one label, not two neighbours. Measured at
    // 0.012 gw in PLAN_WORLD_SEARCH_NAV.md 4.1.
    const val NAV_LABEL_GAP = 0.012

    // Measured on ws_main_adb.png, PLAN_WORLD_SEARCH_NAV.md 4.1: the Explore
    // label sits 0.2350 to the right of the globe, and the nearest neighbour
    // (Shop) 0.097 further out. Confirmed on two more frames, ws_menu.png and
    // explore-menu.png (the same scene, ADB and window), within 0.0074.
    const val NAV_EXPLORE_DX = 0.235
    // Half the distance to the nearest neighbour, so a clump has to be
    // unambiguously closer to 0.235 than to anything else out there.
    const val NAV_EXPLORE_TOL = 0.045

    /** One merged clump of the tab label row. */
    private class LabelClump(val fx: Double, val fw: Double, val area: Int)

    /**
     * Bright clumps in the tab label row, merged left to right by gap.
     *
     * The band starts at 0.955, not the 0.945 PLAN_WORLD_SEARCH_NAV.md 4.1
     * measured. At 0.945 the globe's own decorative ring is bright enough to
     * pass this mask and close enough to the neighbouring labels that the
     * gap-merge below chains three or four tabs into one -- checked on
     * ws_main_adb.png, where 0.945 collapses Digimon, Dungeon, the globe and
     * Camp into a single clump. 0.955 leaves the ring out and reproduces every
     * dx in that section within 0.013, still an order of magnitude inside the
     * 0.097 gap to the nearest real neighbour.
     */
    private fun labelClumps(img: Mat, x0: Int, y0: Int, gw: Int, gh: Int): List<LabelClump> {
        val top = maxOf(0, Py.int(y0 + NAV_LABEL_BAND[0] * gh))
        val bottom = Py.int(y0 + NAV_LABEL_BAND[1] * gh)
        val left = maxOf(0, x0)
        val right = x0 + gw
        val sub = Py.crop(img, top, bottom, left, right) ?: return emptyList()
        val mask = Cv.hsvMask(sub, NAV_LABEL_WHITE)
        val (n, stats) = Cv.components(mask)
        mask.release()
        // (start, end, area) per component, and Python sorts those tuples.
        val comps = ArrayList<IntArray>()
        for (i in 1 until n) {
            val (x, _, w, _, area) = stats[i].toList()
            if (area < NAV_LABEL_MIN_AREA) continue
            comps.add(intArrayOf(left + x - x0, left + x + w - x0, area))
        }
        comps.sortWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
        if (comps.isEmpty()) return emptyList()
        val gap = NAV_LABEL_GAP * gw
        val groups = arrayListOf(arrayListOf(comps[0]))
        for (c in comps.drop(1)) {
            val prev = groups.last().last()
            if (c[0] - prev[1] > gap) {
                groups.add(arrayListOf(c))
            } else {
                groups.last().add(c)
            }
        }
        val out = ArrayList<LabelClump>()
        for (g in groups) {
            val xs0 = g.minOf { it[0] }
            val xs1 = g.maxOf { it[1] }
            out.add(LabelClump((xs0 + xs1) / 2.0 / gw, (xs1 - xs0).toDouble() / gw,
                               g.sumOf { it[2] }))
        }
        return out
    }

    // The Digimon tab, the mirror image of the Explore one. Measured on five
    // frames -- two window sizes and three ADB frames, main screens and Partner
    // pages -- the Digimon label sits at dx -0.2351 to -0.2374 from the globe,
    // within 0.0026, with Tamer 0.105 further out and Dungeon 0.117 nearer:
    //
    //   dx   -0.340   -0.235   -0.118   0.000  +0.121  +0.235  +0.335
    //         Tamer  Digimon  Dungeon  globe   Camp  Explore   Shop
    //
    // The same tolerance as Explore's, and for the same reason: half the way to
    // the nearest neighbour.
    const val NAV_DIGIMON_DX = -0.235

    /** `nav_tab`'s answer: the label's centre, the globe's height, width and ink. */
    data class NavTab(val fx: Double, val fy: Double, val fw: Double, val area: Int) {
        fun toOracle(): Map<String, Any?> =
            mapOf("fx" to fx, "fy" to fy, "fw" to fw, "area" to area)
    }

    /**
     * A bottom-nav tab, found at a fixed distance from the globe.
     *
     * Null also answers "is the bottom nav bar in front and undimmed", the
     * same second job dungeon.homeButton's own null already does -- this
     * function refuses to look any further once that one has. That second job
     * is what the Bond Tour leans on: with the small Partner dialog up, the
     * bar is dimmed and this says so rather than tapping into it.
     *
     * `fy` comes from the globe and not from the label, because the label of
     * whichever tab is currently open turns white and drifts a little while
     * the globe's height does not.
     */
    fun navTab(img: Mat, dx: Double, tol: Double = NAV_EXPLORE_TOL): NavTab? {
        val home = Dungeon.homeButton(img) ?: return null
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val clumps = labelClumps(img, x0, y0, gw, gh)
        if (clumps.isEmpty()) return null
        val best = clumps.minByOrNull { abs(it.fx - home.fx - dx) }!!
        if (abs(best.fx - home.fx - dx) > tol) return null
        return NavTab(best.fx, home.fy, best.fw, best.area)
    }

    /** The Explore tab, or null if it is not plainly there. */
    fun exploreTab(img: Mat): NavTab? = navTab(img, NAV_EXPLORE_DX)

    /**
     * The Digimon tab, or null if it is not plainly there.
     *
     * The way into the Partner screen. The lead figure in the middle of the
     * field is not: it opens the small Encyclopedia / Move dialog, which
     * `Passive.partnerMenu` recognises and which has no grid on it. See
     * PLAN_BOND_TOUR.md 1.2 -- a live run spent three taps finding that out.
     */
    fun digimonTab(img: Mat): NavTab? = navTab(img, NAV_DIGIMON_DX)

    // ------------------------------------------------------------------------
    // explore_menu -- is the Explore menu open
    // ------------------------------------------------------------------------
    // The obvious feature is the wrong one. The hellblau EXPLORE header band
    // measures 0.708 on this menu against 0.683 on the main screen, which has
    // blue sky at the same spot -- 2.5 points is not a threshold, it is a coin
    // flip that loses on the next background. What separates them is the dark
    // body behind the cards, present only here.
    val MENU_DARK_BAND = doubleArrayOf(0.16, 0.86)
    val MENU_DARK_HUE = Dungeon.Hsv(intArrayOf(95, 120, 20), intArrayOf(130, 255, 110))
    // Measured, PLAN_WORLD_SEARCH_NAV.md 4.2: the menu itself 0.544 to 0.552
    // across three frames and two frame shapes, the nearest real screen (the
    // banner-covered board) 0.371. 0.45 sits 18 % under the one and 21 % over
    // the other.
    const val MENU_DARK_MIN = 0.45

    private fun darkBodyShare(img: Mat, x0: Int, y0: Int, gw: Int, gh: Int): Double {
        val top = maxOf(0, Py.int(y0 + MENU_DARK_BAND[0] * gh))
        val bottom = Py.int(y0 + MENU_DARK_BAND[1] * gh)
        val left = maxOf(0, x0)
        val sub = Py.crop(img, top, bottom, left, x0 + gw) ?: return 0.0
        val mask = Cv.hsvMask(sub, MENU_DARK_HUE)
        val share = Core.countNonZero(mask).toDouble() / (mask.rows().toLong() * mask.cols())
        mask.release()
        return share
    }

    /**
     * The Digital World Search card's tap target, or null if this is not
     * the Explore menu.
     *
     * Not null only when all three hold: the nav bar undimmed under it, the
     * dark body behind the cards, and the card itself found by
     * worldSearchCard. Same shape as summon.general_tab -- the screen
     * counts as recognised once what is needed on it has been found on it,
     * not before.
     *
     * The nav bar is dungeon.homeButton's second job: the game dims the
     * bar under a dialog and the dimmed globe leaves the white range. The
     * Insufficient Summon Tickets dialog and the bond tour's Raise prompt
     * both put a pink button over a dark, dimmed body, and the pink is the
     * card's own hue -- measured, this answered "menu" on eight such frames
     * (PLAN_ANDROID_3_DIRECTOR.md 3.1), the globe on none of them, and on
     * all twelve real menus. go_home needs the globe anyway.
     */
    fun exploreMenu(img: Mat): Target? {
        if (Dungeon.homeButton(img) == null) return null
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        if (darkBodyShare(img, x0, y0, gw, gh) < MENU_DARK_MIN) return null
        return worldSearchCard(img)
    }

    // ------------------------------------------------------------------------
    // world_search_card -- the card in the Explore menu
    // ------------------------------------------------------------------------
    // The Digital World Search card carries a magenta pixel-device screen, and
    // it is the largest magenta thing in the menu by a wide margin -- measured
    // ratio 9.9 (window) and 9.7 (ADB) against the next largest, a training
    // card's own artwork. 0.006 of the reference area is the same order of
    // magnitude the summon bot's General tab measured, and that one threshold
    // disagreed by a wrong direction between window and ADB frames (NOTES.md).
    // So this decides by ratio to the runner-up, not by an absolute area.
    // Measured over five frames of the menu in both frame shapes: every real
    // magenta thing on it -- the card's screen, its own pink seam, the Training
    // card, the fourth card -- sits at H 146 to 150. The old upper bound of 175
    // was a round number, never measured against anything it had to refuse, and
    // it reached far enough into red to swallow a brick wall (H 173) out of the
    // scene behind the header. 160 is 10 units above what it must catch and 13
    // below what it must not.
    val CARD_HUE = Dungeon.Hsv(intArrayOf(140, 90, 120), intArrayOf(160, 255, 255))
    // A floor against dust, a factor of three under the real card -- which
    // measures 0.0060 to 0.0061 of the reference area on window and ADB frames
    // alike -- and a factor of two over the largest magenta speck that ever
    // stood in the card's own place on a screen that is not the menu: two
    // main screens at 0.0009 and 0.0010 (PLAN_ANDROID_3_DIRECTOR.md 5). The
    // area is still not what decides between cards, the ratio is; this only
    // says how small a thing is not a card at all.
    const val CARD_MIN_SHARE = 0.002
    // What counts as a different card rather than this one's own pink seam.
    // Measured: the seam sits 0.072 away (Euclidean, in fx/fy), the nearest
    // real rival 0.62 to 0.64 away.
    const val CARD_FOREIGN_GAP = 0.17
    // Half the measured ratio, room on both sides.
    const val CARD_RATIO_MIN = 3.0
    // The device screen sits in the upper part of the card; the tile's own
    // centre -- what should be tapped -- is measured 0.072 further down.
    const val CARD_TAP_DY = 0.072
    // Where the card stands. The menu's layout is fixed and the card is its
    // first tile, so its screen has one place: measured over the twelve real
    // menu frames in the corpus, both sources (window frames 625 x 1150 to
    // 759 x 1387, ADB 1080 x 1920), fx 0.293 to 0.295 and fy 0.214 on every
    // one, a spread of two thousandths. Without this the colour and ratio
    // tests alone found a "card" on 118 of 747 frames -- the pink of a
    // dungeon-list card (0.415/0.262 and 0.495/0.270), a gem icon on the Buddy
    // page (0.214/0.455), the pink Cancel of a prompt (0.343/0.600) -- and
    // the screen inventory of PLAN_ANDROID_3_DIRECTOR.md 3.1 had the Explore
    // menu claimed on the dungeon list and on two dialogs. The nearest of
    // those stands 0.13 away, twice the band.
    val CARD_TILE = doubleArrayOf(0.294, 0.214)
    const val CARD_TILE_TOL = 0.06

    /** The biggest clump and its rival, or (null, null) -- `_card_blobs` and `_field_blobs`. */
    private class Rivals(val biggest: Blob?, val foreign: Blob?)

    /**
     * The clumps of one hue in the menu body, biggest first and its rival.
     *
     * Only the body. The header above it is the currency bar, and the game
     * draws the player's current scene behind it at whatever colour that
     * scene happens to be -- an orange stage put a dark red brick wall there
     * measuring 0.00566 against the card's own 0.00601, and the ratio rule,
     * which is what this recogniser rests on, collapsed to 1.1. The cards are
     * drawn in the body and nowhere else, so that is where they are looked
     * for; MENU_DARK_BAND already says where the body is.
     *
     * Judged by the clump's centre rather than by cropping the image: a crop
     * cuts whatever straddles its edge, and a clump that lost half its pixels
     * to the crop would be compared against a rival that kept all of its own.
     *
     * explore.py has this twice, as `_card_blobs` and `_field_blobs`, with
     * the same construction and different hue, floor and gap; the two are
     * kept as two functions below so that each keeps its own sentence.
     */
    private fun menuBlobs(img: Mat, hue: Dungeon.Hsv, minShare: Double, foreignGap: Double): Rivals {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val mask = Cv.hsvMask(img, hue)
        val (n, stats) = Cv.components(mask)
        mask.release()
        val blobs = ArrayList<Blob>()
        for (i in 1 until n) {
            val (x, y, w, h, area) = stats[i].toList()
            val share = area / (gw * gh).toDouble()
            if (share < minShare) continue
            val fy = (y + h / 2.0 - y0) / gh
            if (!(MENU_DARK_BAND[0] <= fy && fy <= MENU_DARK_BAND[1])) continue
            blobs.add(Blob((x + w / 2.0 - x0) / gw, fy, share))
        }
        if (blobs.isEmpty()) return Rivals(null, null)
        val biggest = blobs.maxByOrNull { it.share }!!
        val foreign = blobs.filter {
            it !== biggest &&
                Math.pow((it.fx - biggest.fx) * (it.fx - biggest.fx) +
                         (it.fy - biggest.fy) * (it.fy - biggest.fy), 0.5) >= foreignGap
        }.maxByOrNull { it.share }
        return Rivals(biggest, foreign)
    }

    private fun cardBlobs(img: Mat): Rivals =
        menuBlobs(img, CARD_HUE, CARD_MIN_SHARE, CARD_FOREIGN_GAP)

    /**
     * The Digital World Search card's tap target, or null.
     *
     * Null both when no magenta clump clears CARD_MIN_SHARE at all, and when
     * the biggest one found is not convincingly the largest -- a menu with a
     * different layout, or no menu at all.
     */
    fun worldSearchCard(img: Mat): Target? {
        val (biggest, foreign) = cardBlobs(img).let { it.biggest to it.foreign }
        if (biggest == null) return null
        if (foreign != null && biggest.share < CARD_RATIO_MIN * foreign.share) return null
        if (abs(biggest.fx - CARD_TILE[0]) > CARD_TILE_TOL ||
            abs(biggest.fy - CARD_TILE[1]) > CARD_TILE_TOL) return null
        return Target(biggest.fx, biggest.fy + CARD_TAP_DY)
    }

    // ------------------------------------------------------------------------
    // meat_field_card -- the Meat Field card in the Explore menu
    // ------------------------------------------------------------------------
    // Same shape of recogniser as world_search_card: a rare colour, decided by
    // ratio to the runner-up rather than an absolute area, in the menu body only.
    // The anchor is the lime-green vegetable of the card's Vegiemon-and-Togemon
    // artwork. Measured on three frames -- two ADB (current_state.png,
    // ws_menu.png) and one window (explore-menu.png), two different accounts --
    // the vegetable sits at fx 0.364-0.366, fy 0.457 every time, share 0.0062,
    // hue 42-49. The nearest rival on any of the three is the Training card's
    // teal kettlebell head, at share 0.0005-0.0008 -- a ratio of 8 to 11, the
    // same order of magnitude world_search_card's own 9.7-9.9 magenta ratio.
    // 3.0 is half the weakest of those three measured ratios, same margin
    // world_search_card keeps.
    val FIELD_HUE = Dungeon.Hsv(intArrayOf(35, 60, 60), intArrayOf(55, 255, 255))
    const val FIELD_MIN_SHARE = 0.001
    const val FIELD_FOREIGN_GAP = CARD_FOREIGN_GAP
    const val FIELD_RATIO_MIN = 3.0
    // The label banner's text sits below the vegetable; measured centre fy on
    // the same three frames is 0.520-0.552 against the vegetable's 0.457 --
    // noisier than the card's own icon-to-label offset because the text mask
    // picks up stray white flecks in the artwork above it, but every reading
    // clusters within 0.02 of 0.073, which is also world_search_card's own
    // CARD_TAP_DY to the unit. Tapping anywhere on the card opens it, so the
    // exact fraction only has to stay clear of the row above (the icon) and the
    // row below (the Eat card, a further 0.28 down) -- both true with room to
    // spare.
    const val FIELD_TAP_DY = 0.073

    // Green is not a rare colour the way world_search_card's magenta is: run
    // over the whole debug* collection (668 frames, none of them the Explore
    // menu on purpose), the hue-and-ratio test above alone still fired on a
    // Partner "Raise Seraphimon?" dialog and a bond-token-collect frame -- a
    // green Digimon portrait in a grid can be exactly this big and exactly this
    // green. Both dialogs also happen to clear world_search_card on their own
    // (a pink Cancel button reads as the same magenta), so gating on "is
    // world_search_card present" alone is not enough either.
    // What the two real screens share and the two impostors do not: the fixed
    // menu layout puts Meat Field a constant step down and to the right of
    // Digital World Search. Measured on eleven confirmed Explore-menu frames --
    // both ADB and window shapes, four different accounts and window sizes --
    // the tap targets sit at dx 0.0711-0.0719, dy 0.2441-0.2444, agreement to
    // the thousandth. The Partner dialog measures dx -0.174, dy 0.099; the
    // bond-token frame dx -0.176, dy 0.105 -- an order of magnitude off on dx
    // alone. 0.03 is well outside the real spread and well inside the gap to
    // either impostor.
    const val FIELD_ANCHOR_DX = 0.0715
    const val FIELD_ANCHOR_DY = 0.2442
    const val FIELD_ANCHOR_TOL = 0.03

    /**
     * The lime-green clumps in the menu body, biggest first and its rival.
     *
     * Same construction as cardBlobs, including why the search is confined
     * to MENU_DARK_BAND: a stage background behind the header can put an
     * unrelated hue there, and the cards are drawn in the body and nowhere
     * else.
     */
    private fun fieldBlobs(img: Mat): Rivals =
        menuBlobs(img, FIELD_HUE, FIELD_MIN_SHARE, FIELD_FOREIGN_GAP)

    /**
     * The Meat Field card's tap target, or null.
     *
     * Lives here rather than in farm.py: it is a feature of the Explore menu,
     * the same menu worldSearchCard already reads, and exploreMenu's own
     * contract stays untouched -- this is a second question asked of the
     * same screen, not a change to the first.
     *
     * Anchored on worldSearchCard rather than trusted alone -- see
     * FIELD_ANCHOR_DX/DY above for why a green blob and a menu-dark background
     * are not enough by themselves.
     */
    fun meatFieldCard(img: Mat): Target? {
        val (biggest, foreign) = fieldBlobs(img).let { it.biggest to it.foreign }
        if (biggest == null) return null
        if (foreign != null && biggest.share < FIELD_RATIO_MIN * foreign.share) return null
        val fx = biggest.fx
        val fy = biggest.fy + FIELD_TAP_DY
        val ws = worldSearchCard(img) ?: return null
        val dx = fx - ws.fx
        val dy = fy - ws.fy
        if (abs(dx - FIELD_ANCHOR_DX) > FIELD_ANCHOR_TOL ||
            abs(dy - FIELD_ANCHOR_DY) > FIELD_ANCHOR_TOL) return null
        return Target(fx, fy)
    }

    // ------------------------------------------------------------------------
    // close_button -- the X that closes the board
    // ------------------------------------------------------------------------
    // Built like summon.exit_button: a fixed plate plus a mark on it, in a crop
    // smaller than the plate itself so a window shape this was never measured on
    // cannot slide the background into it. Measured, PLAN_WORLD_SEARCH_NAV.md
    // 4.4, on ws_board.png and ws_banner.png, ADB only -- no window frame of the
    // board exists to measure this against, which is why the floors sit well
    // below what was actually caught (0.60 against 0.799, not 0.70).
    //
    // summon.exit_button sits 0.006/0.008 away at almost the same spot on
    // screen, and the two cannot be confused: it needs a near-white plate
    // (V >= 225, S <= 45) and finds nothing at all on either of these frames.
    const val CLOSE_FX = 0.7990
    const val CLOSE_FY = 0.9490
    const val CLOSE_FW = 0.0846
    const val CLOSE_CROP = 0.35
    val CLOSE_PLATE = Dungeon.Hsv(intArrayOf(95, 40, 155), intArrayOf(125, 75, 205))
    val CLOSE_MARK = Dungeon.Hsv(intArrayOf(95, 78, 105), intArrayOf(125, 115, 150))
    const val CLOSE_PLATE_MIN = 0.60
    val CLOSE_MARK_BAND = doubleArrayOf(0.10, 0.30)

    /** `close_button`'s answer: where the X is and how much of it is plate and mark. */
    data class CloseButton(val fx: Double, val fy: Double, val plate: Double, val mark: Double) {
        fun toOracle(): Map<String, Any?> =
            mapOf("fx" to fx, "fy" to fy, "plate" to plate, "mark" to mark)
    }

    /**
     * The X that closes the board, or null.
     *
     * Null also answers "is the board (or whatever it left up) in front" --
     * every other screen on this path measures 0.000 on both halves.
     */
    fun closeButton(img: Mat): CloseButton? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val r = CLOSE_CROP * CLOSE_FW * gw
        val cx = x0 + CLOSE_FX * gw
        val cy = y0 + CLOSE_FY * gh
        val top = Py.int(cy - r)
        val bottom = Py.int(cy + r)
        val left = Py.int(cx - r)
        val right = Py.int(cx + r)
        if (top < 0 || left < 0 || bottom > img.rows() || right > img.cols()) return null
        val crop = Py.crop(img, top, bottom, left, right) ?: return null
        val hsv = Cv.hsv(crop)
        val n = (hsv.rows() * hsv.cols()).toDouble()
        val plateMask = Cv.inRange(hsv, CLOSE_PLATE)
        val markMask = Cv.inRange(hsv, CLOSE_MARK)
        hsv.release()
        // mask.sum() / 255.0 / n on a 0/255 mask: the sum is 255 times the
        // count, so dividing it by 255.0 gives the count exactly.
        val plate = (255L * Core.countNonZero(plateMask)) / 255.0 / n
        val mark = (255L * Core.countNonZero(markMask)) / 255.0 / n
        plateMask.release(); markMask.release()
        if (plate < CLOSE_PLATE_MIN) return null
        if (!(CLOSE_MARK_BAND[0] <= mark && mark <= CLOSE_MARK_BAND[1])) return null
        return CloseButton(CLOSE_FX, CLOSE_FY, plate, mark)
    }
}
