package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * startup.py's two readers, carried over line for line: `title_bar`, the
 * Touch To Start bar, and `at_main`, the plain main screen. The walk that
 * uses them is a skill loop and comes later. Two of this project's own
 * since 2026-10-01: `menu_button`, the title's Menu, for the title the bar
 * is not found on (PLAN_RELEASE_1_3.md B43), and `announcement`, the OK of
 * the news the game puts up after its daily reset (B44). And two windows of
 * the game's that nothing here taps, read so that they are named and left
 * standing: `sale_window`, "Time Sale!" (B72), and `help_window`, the Help
 * tutorial of a page's first visit (B73).
 *
 * Every constant came over with its Python name and value, and the sentence
 * that says where it came from; a number is changed in this file, with a
 * measurement and its sentence, and then `writeOracle` writes the oracle
 * (NOTES.md, "One project").
 */
object Startup {

    // The one piece of the title screen that is the game's own furniture
    // rather than this month's advert: a wide, flat, translucent blue bar
    // across the bottom of the artwork, with the words on it in whatever
    // language the game is running in. The words are never read.
    //
    // Measured over the three real title frames there are -- a window frame
    // at 624 x 1076, an ADB frame at 1080 x 1920, and a second ADB frame with
    // the "Exit the game?" prompt dimming everything behind it -- as shares
    // of the reference window (see Dungeon.gameRect):
    //
    //   frame                        fx      fy      fw     aspect  fill
    //   window, 624 x 1076         0.483   0.848   0.460    6.9     0.90
    //   ADB, 1080 x 1920           0.483   0.846   0.459    7.1     0.91
    //   ADB, dimmed by the prompt  0.474   0.846   0.438    6.8     0.90
    //
    // The two sources agree to a thousandth in both directions, which is
    // what says these numbers are the game's and not the window's.
    //
    // The bar's own colour over those three frames: hue 100 to 120 with the
    // middle 90 % between 101 and 116, saturation 112 to 216, value 70 to
    // 178 -- the bottom of that value range being the dimmed one. The mask
    // started at 60 in both, which left the dimmed bar a sixth of its own
    // range to spare. It is wider than Dungeon.BLUE on purpose: this bar is
    // translucent, so what it measures depends on the advert behind it.
    //
    // And then the advert went. Since resources 1.4.0.D7X0146 the title is
    // a picture of the well that changes with the time of day
    // (PLAN_RELEASE_1_3.md B10, 2026-09-30), and the bar was found on
    // neither of the two pictures there are frames of. At night the rock
    // behind it is a dark blue that the old mask takes in -- hue 117 to 120,
    // value up to 77 -- and bar and night grew into one blob 824 x 350 px,
    // fill 0.496. At dusk the rock is red, and the bar's translucent ends
    // turn purple over it: the mask stopped at hue 120, the ends frayed, and
    // the fill came to 0.748 against BAR_FILL_MIN's 0.75. Measured with
    // `gradlew :core:titleProbe --args="bar corpus"` over the 1796 frames
    // the corpus had and the new titles (corpus/startup/title_* since), in
    // the bar's rows (fy 0.829 to 0.864, fx 0.28 to 0.68) against the rows
    // just above and below them, counting the pixels of the bar's hue and
    // saturation:
    //
    //                              bar, V p1   around it, V p99
    //   the four old titles        115 to 116      91
    //   dusk (185220)                 109          none of the bar's hue
    //   night (203943)                111          77
    //   dimmed by "Exit the game?"  67 to 70       45 to 55
    //
    // So the value starts at 100 now, the middle of 91 and 109: the night
    // and the old advert's blue stay out, the bar stays whole, 74 rows as
    // before. What goes is the bar dimmed behind "Exit the game?" (two
    // frames of the corpus and R2's live one), which nothing needs: classify
    // answers the exit prompt before it asks for the title. And the hue goes
    // to 125, for the dusk bar's frayed ends: the hue of its bright pixels
    // (S 60 up, V 100 up) has its median at 114 and its 95th percentile at
    // 129, and its fill is 0.748 with the mask ending at 120, 0.813 at 122,
    // 0.852 at 125 and 0.862 at 130 -- the ends are in by 125. The four old
    // titles keep 0.903 to 0.915 and their 74 rows at every one of those
    // ceilings. Over the whole corpus the mask finds the bar, at 122 to 130
    // alike, on the four old titles and on the two Food Effects frames it
    // found it on before (preset/food_open_*, which classify calls a preset
    // page first), and nowhere else. The day's picture has not been seen.
    val BAR_BLUE = Dungeon.Hsv(intArrayOf(95, 60, 100), intArrayOf(125, 255, 255))

    // The shape tests. Run over every stored frame in the project -- 495 of
    // them, both frame shapes, nine debug folders -- these three title frames
    // are the only ones that pass, and the load-bearing test is fy.
    //
    // What else in this game is a wide flat blue bar low down, and where it
    // sits:
    //
    //   the Special Summon banner        fy 0.797 to 0.801   (17 frames)
    //   the profile switcher's bar       fy 0.801
    //   a quest card's own edge          fy 0.887
    //   the summon sequence's footer     fy 0.949
    //
    // So the nearest impostor of any kind stands at 0.8013 and the bar at
    // 0.846. The band below starts at 0.820, which is 0.019 above the worst
    // impostor and 0.026 below the thing it has to catch -- a fifth of the
    // game's height between them either way would be luxury; two per cent is
    // what there is, and it is the same two per cent that let the Daily Bonus
    // wheel into the bond bubble's band. Hence the other four tests, none of
    // which is doing the work alone but each of which the impostors also
    // fail:
    //
    //   aspect  the bar 6.8 to 7.1; a hairline edge in the nav bar 103; the
    //           profile switcher's bar 3.46
    //   fill    the bar 0.90; the nearest thing that passes everything else 0.41
    //   fw      the bar 0.438 to 0.460; the nearest 0.062
    //   fx      nothing else comes close enough to matter
    //
    // The floors sit 17 % under the fill and 18 % under the width they have
    // to catch, which is the margin NOTES.md asks for; fy is the one that
    // cannot have it, and is why the other four are there.
    val BAR_FY = doubleArrayOf(0.820, 0.875)
    const val BAR_FX_OFF = 0.06
    val BAR_ASPECT = doubleArrayOf(4.0, 20.0)
    const val BAR_FILL_MIN = 0.75
    val BAR_FW = doubleArrayOf(0.36, 0.58)

    /** `title_bar`'s dict: the bar's place and size, and the two shape numbers. */
    data class Bar(val fx: Double, val fy: Double, val fw: Double, val fh: Double,
                   val aspect: Double, val fill: Double) {
        fun toOracle(): Map<String, Any?> = mapOf(
            "fx" to fx, "fy" to fy, "fw" to fw, "fh" to fh, "aspect" to aspect, "fill" to fill)
    }

    /**
     * The Touch To Start bar, or null.
     *
     * Null also answers "is the title screen up", which is the only reason
     * anything here needs to know. It does NOT answer "is it safe to tap":
     * the bar was plainly visible behind the "Exit the game?" prompt --
     * measured, that is the third frame in the table above -- and since the
     * value starts at 100 (2026-09-30) it is not found there any more, but
     * that is a threshold and not a promise, so a caller still rules the
     * prompt out first. classify does; the exit prompt is asked before it.
     */
    fun titleBar(img: Mat): Bar? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        if (gw <= 0 || gh <= 0) return null
        val hsv = Dungeon.hsv(img)
        val mask = Dungeon.inRange(hsv, BAR_BLUE)
        val (count, stats) = Dungeon.components(mask)
        hsv.release(); mask.release()
        for (i in 1 until count) {
            val (x, y, w, h, area) = stats[i].toList()
            if (h < 3 || w < 10) continue
            val fx = (x + w / 2.0 - x0) / gw
            val fy = (y + h / 2.0 - y0) / gh
            val fw = w / gw.toDouble()
            if (!(BAR_FY[0] <= fy && fy <= BAR_FY[1])) continue
            if (kotlin.math.abs(fx - 0.5) > BAR_FX_OFF) continue
            val aspect = w / h.toDouble()
            if (!(BAR_ASPECT[0] <= aspect && aspect <= BAR_ASPECT[1])) continue
            val fill = area / (w * h).toDouble()
            if (fill < BAR_FILL_MIN) continue
            if (!(BAR_FW[0] <= fw && fw <= BAR_FW[1])) continue
            return Bar(fx, fy, fw, h / gh.toDouble(), aspect, fill)
        }
        return null
    }

    /**
     * Is the plain main screen in front, with nothing over it?
     *
     * Dungeon.autoButton already answers exactly this and was measured for
     * it: the game dims what is behind a dialog, the dimmed disc breaks up
     * out of the blue range, and a button found at full fill therefore means
     * both "here is the button" and "nothing is covering the screen". Checked
     * over all 497 stored frames, every frame with a crisp auto button also
     * has the home globe and no frame has the globe alone that matters here
     * -- so this is the stricter of the two and there is nothing to gain by
     * asking both.
     */
    fun atMain(img: Mat): Boolean = Dungeon.autoButton(img) != null

    // ------------------------------------------------------------------------
    // The title's Menu button (PLAN_RELEASE_1_3.md B43, 2026-10-01)
    // ------------------------------------------------------------------------
    // The title by day is not found by its bar. The bar is translucent, and
    // the day's picture behind it is the same pale blue: in the bar's rows
    // the stone measures S 114 to 145 and V 180 to 187 (row means, fx 0.30 to
    // 0.66), the bar S 168 to 182 and V 196 to 208, with the stone's cracks
    // showing through it and the words in its middle rows. BAR_BLUE takes in
    // bar and stone alike, and no floor of its own separates them: a mask
    // with S and V floors after a median blur fills 0.74 of the bar's box at
    // best (S from 160, V from 195, 13 px) against BAR_FILL_MIN's 0.75, and
    // beside the bar's right end the stone reaches S 165 to 173 where the
    // bar's own pixels start at 155 (p5) -- numbers taken with numpy on the
    // frame, staging/b6live/title_day_085430 (900 x 1600). There is no gap
    // there to put a threshold in, and BAR_BLUE stays as B10 set it.
    //
    // What does not change with the hour is the furniture that is opaque:
    // the Menu button in the bottom right corner, a blue button with "Menu"
    // written on it in white. Measured with `gradlew :core:titleProbe
    // --args="menu corpus staging/r2 staging/b10 staging/b6live"` over the
    // 1842 frames of the corpus and 27 staged pictures -- every blob of
    // Dungeon.BLUE in MENU_BAND of the bar's rectangle, 0.05 to 0.20 of the
    // width and 1.4 to 3.6 times as wide as tall, 273 of them on 170
    // pictures:
    //
    //                            fx      fy      fw     fill   white
    //   the Menu, 9 titles     0.7782  0.9615  0.1087  0.896   0.034
    //                       to 0.7790  0.9616  0.1090  0.905   0.045
    //
    // the four titles of the old advert (startup/walk_000_title, the two
    // quest/no-x-to-get-out-with titles, tall/title_1080x2340_a), the dusk
    // and the night of 2026-09-30, the day of 2026-10-01 at 900 x 1600, and
    // two day titles of resources 1.3.1.D3X5131 that the passive helper kept
    // as `unclear` on 2026-09-21 (passive/unclear_090348, _130907) and no
    // reader had named. The 264 other blobs: the nearest to the place stands
    // 0.0236 off it (passive/unclear_214137, a list's card, fw 0.188, fill
    // 0.488); within 0.03 of it a preset list's and a prompt's buttons, fw
    // 0.051 to 0.054, fill 0.665 to 0.729, white 0.000, and the Menu dimmed
    // behind "Exit the game?", fill 0.116 to 0.134, white 0.000; the most
    // white any of them encloses is 0.257, on a blob 0.063 wide at fx 0.881.
    // No other blob fails one test alone. So each test stands in the middle
    // of its gap: the place within MENU_PLACE_TOL of MENU_PLACE (half of
    // 0.0236, the larger of dfx and dfy), the width between the middles of
    // 0.054 and 0.109 and of 0.109 and 0.188 (MENU_FW), the fill over the
    // middle of 0.729 and 0.896, the white over the middle of 0.000 and
    // 0.034. The aspect is the window the blobs were measured in, not a
    // threshold of its own (the Menu 2.21 to 2.24). The loading title has no
    // Menu and no bar, and stays unread (B10, B30).
    //
    // The anchor is the bar's (the bottom's rectangle), and the Menu stood
    // at the same place on the 1080 x 2340 title; no title over the canvas
    // ceiling has been seen, so the anchor there is the bar's by
    // presumption, not by a measurement.
    val MENU_BAND = doubleArrayOf(0.60, 1.00, 0.88, 1.02)
    val MENU_PLACE = doubleArrayOf(0.7786, 0.9615)
    const val MENU_PLACE_TOL = 0.012
    val MENU_FW = doubleArrayOf(0.081, 0.149)
    val MENU_ASPECT = doubleArrayOf(1.4, 3.6)
    const val MENU_FILL_MIN = 0.81
    const val MENU_GLYPH_MIN = 0.017

    /**
     * A blob of one colour in a band: centre and size as fractions of the
     * rectangle it was read in, its width over its height in pixels, its
     * fill, the share of its box it encloses (pixels a flood from outside
     * the box cannot reach, any colour) and the share it encloses in white
     * (HOME_WHITE, Dungeon.glyphWhite), and whether it touches a side of the
     * band, which cut it (notes/formats.md, rule 7).
     */
    class Blob(val fx: Double, val fy: Double, val fw: Double, val fh: Double, val aspect: Double,
               val fill: Double, val enclosed: Double, val white: Double, val cut: Boolean) {
        fun button(): Dungeon.Button = Dungeon.Button(fx, fy, fw, fh)
    }

    /**
     * Every blob of [colour] in [band] (fx0, fx1, fy0, fy1 of [rect], the
     * band clamped to the picture), [fwRange] wide and [aspectRange] in
     * shape. One mask, no closing: what a blob encloses is the question.
     */
    internal fun blobs(img: Mat, rect: Dungeon.GameRect, colour: Dungeon.Hsv, band: DoubleArray,
                       fwRange: DoubleArray, aspectRange: DoubleArray): List<Blob> {
        val (x0, y0, gw, gh) = rect
        if (gw <= 0 || gh <= 0) return emptyList()
        val left = maxOf(0, Py.int(x0 + band[0] * gw))
        val right = minOf(img.cols(), Py.int(x0 + band[1] * gw))
        val top = maxOf(0, Py.int(y0 + band[2] * gh))
        val bottom = minOf(img.rows(), Py.int(y0 + band[3] * gh))
        if (right - left < 4 || bottom - top < 4) return emptyList()
        val sub = Py.crop(img, top, bottom, left, right) ?: return emptyList()
        val hsv = Dungeon.hsv(sub)
        val mask = Dungeon.inRange(hsv, colour)
        val white = Dungeon.inRange(hsv, Dungeon.HOME_WHITE)
        val all = Mat(sub.rows(), sub.cols(), org.opencv.core.CvType.CV_8U, org.opencv.core.Scalar(255.0))
        val labels = Mat()
        val table = Mat()
        val centroids = Mat()
        val count = org.opencv.imgproc.Imgproc.connectedComponentsWithStats(mask, labels, table, centroids, 8)
        val stats = Array(count) { i -> IntArray(5).also { table.get(i, 0, it) } }
        hsv.release(); mask.release(); table.release(); centroids.release()
        val out = ArrayList<Blob>()
        try {
            for (i in 1 until count) {
                val (x, y, w, h, area) = stats[i].toList()
                if (h < 3 || w < 3) continue
                val fw = w / gw.toDouble()
                if (!(fwRange[0] <= fw && fw <= fwRange[1])) continue
                val aspect = w / h.toDouble()
                if (!(aspectRange[0] <= aspect && aspect <= aspectRange[1])) continue
                val cut = x == 0 || y == 0 || x + w == sub.cols() || y + h == sub.rows()
                out += Blob((left + x + w / 2.0 - x0) / gw, (top + y + h / 2.0 - y0) / gh,
                            fw, h / gh.toDouble(), aspect, area / (w * h).toDouble(),
                            Dungeon.glyphWhite(labels, all, i, x, y, w, h),
                            Dungeon.glyphWhite(labels, white, i, x, y, w, h), cut)
            }
        } finally {
            labels.release(); white.release(); all.release(); sub.release()
        }
        return out
    }

    /** The blobs [menuButton] looks at, with their numbers (TitleProbe's `menu`). */
    internal fun menuBlobs(img: Mat): List<Blob> =
        blobs(img, Dungeon.gameRect(img), Dungeon.BLUE, MENU_BAND, doubleArrayOf(0.05, 0.20), MENU_ASPECT)

    internal fun isMenu(b: Blob): Boolean =
        !b.cut && kotlin.math.abs(b.fx - MENU_PLACE[0]) <= MENU_PLACE_TOL &&
            kotlin.math.abs(b.fy - MENU_PLACE[1]) <= MENU_PLACE_TOL &&
            MENU_FW[0] <= b.fw && b.fw <= MENU_FW[1] &&
            b.fill >= MENU_FILL_MIN && b.white >= MENU_GLYPH_MIN

    /** The title's Menu button, or null (B43). */
    fun menuButton(img: Mat): Dungeon.Button? = menuBlobs(img).firstOrNull { isMenu(it) }?.button()

    /**
     * Is the game's title in front: its Touch To Start bar, or, where the
     * bar does not stand out from a pale picture, its Menu button. What the
     * skills ask before a tap high up (B30), and what classify calls
     * `title`; classify still asks the exit prompt first, as [titleBar]
     * wants.
     */
    fun title(img: Mat): Boolean = titleBar(img) != null || menuButton(img) != null

    // ------------------------------------------------------------------------
    // The game's announcements: OK beside "Don't show again today" (B44)
    // ------------------------------------------------------------------------
    // After the day's reset at 08:00 the game puts its news up over the
    // first list or main screen it shows: "New Buddy Added", "New Overdrive
    // Added", "New SP Support Added", a card of artwork with a blue OK under
    // it and, left above the OK, an empty box and "Don't show again today"
    // (PLAN_RELEASE_1_3.md B44: three of them over the dungeon list on
    // instance 1 on 2026-10-01 from 08:44:58, before Notices, a login bonus
    // and Idle Rewards). recognise calls every one of them a battle -- the OK is
    // the blue button below fy 0.90 near the middle that Give Up is
    // (POS_GIVEUP_Y) -- and popupOk, measured on two of them in the
    // laboratory and on none in the corpus, answers here and on 115 frames
    // that are not. Its OK alone is not the window; the box beside it is.
    //
    // Measured with `gradlew :core:titleProbe --args="popup corpus
    // staging/r2 staging/b10 staging/b6live"` over the 1842 frames of the
    // corpus and 27 staged pictures: every blob of Dungeon.BLUE in
    // ANNOUNCE_BAND of the middle's rectangle, 0.015 to 0.09 of the width
    // and 0.6 to 1.6 times as wide as tall (ANNOUNCE_BOX_FW,
    // ANNOUNCE_BOX_ASPECT, the window measured), 12,051 of them on 1308
    // pictures, with what each encloses. The box's rim is BLUE (H 106, S 185,
    // V 175 at 1080 x 1920) round a dark inside (V 67) with nothing white in
    // it, the same on all seven frames of it -- the five of 2026-10-01 and
    // two the passive helper and the quest loop kept over the main screen
    // (passive/unclear_090849, quest/no-x-to-get-out-with_084447, which no
    // reader had named):
    //
    //            fw      fill   encloses   white    the nearest blob that fails
    //                                               this test (and one more at most)
    //   box    0.0427   0.287    0.683     0.000
    //   other  0.0297   0.200    0.463     0.098    (0.0600 the nearest wider)
    //
    // the narrower a part of a list's card, the less full and the less
    // enclosing an Overdrive page's and a prompt's corner, the whiter the
    // main screen's skill slot. Each test stands in the middle of its gap
    // (ANNOUNCE_BOX_SIZE, _FILL_MIN, _ENCLOSED_MIN, _WHITE_MAX), and the four
    // together pass on those seven frames and on no other blob.
    //
    // The OK, a blob of the same mask 0.12 to 0.45 wide and 2 to 9 times as
    // wide as tall (ANNOUNCE_OK_FW, _ASPECT, the window measured), stands at
    // dx 0.1722 and dy 0.0477 to 0.0485 from the box on all seven, 0.2476 to
    // 0.2493 wide; beside the blobs that fail one box test alone, the
    // nearest OK stands 0.0166 from that offset (dx 0.1888, dy 0.0452, fw
    // 0.2127, the Partner window's Move beside a filled square), so the OK
    // is taken within half of that in dx and dy (ANNOUNCE_OK_DX, _DY) and
    // from the middle of 0.2127 and 0.2476 in width (ANNOUNCE_OK_FW_MIN).
    // The frame of the window still coming in (ndo_084507, a glow round the
    // OK) reads the same: a tap then may be swallowed, so the skill wants it
    // on two looks and the screen changed after.
    //
    // MIDDLE, as the windows drawn across the middle are (the prompt, the
    // idle popup), by presumption: every frame of it is 1080 x 1920, where
    // the three anchors are one rectangle (notes/formats.md, rule 4). The
    // dot and its plate cost no answer at any fy of the strip (readerProbe
    // with mask=0.06:0.155:0.005 on the five frames of 2026-10-01).
    val ANNOUNCE_ANCHOR = Dungeon.Anchor.MIDDLE
    val ANNOUNCE_BAND = doubleArrayOf(0.0, 1.0, 0.70, 1.00)
    val ANNOUNCE_BOX_FW = doubleArrayOf(0.015, 0.09)
    val ANNOUNCE_BOX_ASPECT = doubleArrayOf(0.6, 1.6)
    val ANNOUNCE_OK_BAND = doubleArrayOf(0.25, 0.75, 0.80, 1.00)
    val ANNOUNCE_OK_FW = doubleArrayOf(0.12, 0.45)
    val ANNOUNCE_OK_ASPECT = doubleArrayOf(2.0, 9.0)
    val ANNOUNCE_BOX_SIZE = doubleArrayOf(0.036, 0.051)
    const val ANNOUNCE_BOX_FILL_MIN = 0.243
    const val ANNOUNCE_BOX_ENCLOSED_MIN = 0.573
    const val ANNOUNCE_BOX_WHITE_MAX = 0.049
    val ANNOUNCE_OK_DX = doubleArrayOf(0.164, 0.180)
    val ANNOUNCE_OK_DY = doubleArrayOf(0.040, 0.056)
    const val ANNOUNCE_OK_FW_MIN = 0.230

    /** The boxes and the OKs [announcement] looks at, with their numbers (TitleProbe's `popup`). */
    internal fun announcementBlobs(img: Mat): Pair<List<Blob>, List<Blob>> {
        val rect = Dungeon.gameRect(img, ANNOUNCE_ANCHOR)
        val boxes = blobs(img, rect, Dungeon.BLUE, ANNOUNCE_BAND, ANNOUNCE_BOX_FW, ANNOUNCE_BOX_ASPECT)
        if (boxes.isEmpty()) return boxes to emptyList()
        val oks = blobs(img, rect, Dungeon.BLUE, ANNOUNCE_OK_BAND, ANNOUNCE_OK_FW, ANNOUNCE_OK_ASPECT)
        return boxes to oks
    }

    internal fun isAnnounceBox(b: Blob): Boolean =
        !b.cut && ANNOUNCE_BOX_SIZE[0] <= b.fw && b.fw <= ANNOUNCE_BOX_SIZE[1] &&
            b.fill >= ANNOUNCE_BOX_FILL_MIN && b.enclosed >= ANNOUNCE_BOX_ENCLOSED_MIN &&
            b.white <= ANNOUNCE_BOX_WHITE_MAX

    internal fun isAnnounceOk(box: Blob, ok: Blob): Boolean =
        !ok.cut && ok.fw >= ANNOUNCE_OK_FW_MIN &&
            ANNOUNCE_OK_DX[0] <= ok.fx - box.fx && ok.fx - box.fx <= ANNOUNCE_OK_DX[1] &&
            ANNOUNCE_OK_DY[0] <= ok.fy - box.fy && ok.fy - box.fy <= ANNOUNCE_OK_DY[1]

    /**
     * The OK of one of the game's announcements ("New Buddy Added", "New
     * Overdrive Added", ...), in the rectangle of [ANNOUNCE_ANCHOR], or
     * null: a wide blue button with the empty box of "Don't show again today"
     * left above it. Only the OK is ever tapped; the box never.
     */
    fun announcement(img: Mat): Dungeon.Button? {
        val (boxes, oks) = announcementBlobs(img)
        for (box in boxes) {
            if (!isAnnounceBox(box)) continue
            val ok = oks.firstOrNull { isAnnounceOk(box, it) } ?: continue
            return ok.button()
        }
        return null
    }

    // ------------------------------------------------------------------------
    // The game's sale window, "Time Sale!" (PLAN_RELEASE_1_3.md B72)
    // ------------------------------------------------------------------------
    // On the way home from Special Summon on instance 1 (2026-10-01 13:48,
    // 1080 x 2235, the `hole105` row of rule 17) the game put up "Time Sale!"
    // over the Stage Failed banner and its Growth Guide: a window across the
    // whole picture with the sale's tabs on the left, a pack of 900 Support
    // Summon tickets, "Available 1/1" and one button, the purchase, 3,600
    // gems. No X, no Close, no Cancel. Nothing read it: classify said
    // `unknown`, the way home waited, said "could not get back to the main
    // screen" and the chain parked. Nothing here taps it -- not the button,
    // and not beside it either, because what closes the window has not been
    // seen (B80) -- it is read so that a way home hands it back and the
    // director says what it is.
    //
    // What it has that nothing else has: two thin yellow rims across the
    // canvas, above and below the window. Measured with `gradlew
    // :core:titleProbe --args="sale corpus staging/release13/kept
    // staging/release13 staging/b70"` over the 1906 frames of the corpus and
    // 31 staged pictures (staging/b72/titleProbe_sale*.txt), every run of
    // rows a yellow covers half the canvas's columns in or more: with the
    // measurement's yellow (H 10 to 40, S 100 up, V 150 up) the rims are H 24,
    // S 213 and V 255 at their fifth percentile 213 and 254, and every other
    // thin run that wide is paler -- the main screen's sky S 108 to 135
    // (tall/main_bubble), Special Summon's footer V 185 (summon/no_way_back_02)
    // -- so SALE_YELLOW starts at the middles, S 174 and V 220. With it:
    //
    //                         share   rows   fh       fy      the pair's gap
    //   the top rim           0.969    13    0.0056   0.3197      0.3818
    //   the bottom rim        0.968     5    0.0022   0.7015
    //   counter_141100,       0.946     1    0.0005   0.3581      0.1737
    //     unclear_195000      0.972     2    0.0010   0.5318
    //   the widest other run  0.828 (bond-not-the-middle_132800, alone), then
    //                         0.694 (the Overdrive news, 11 to 36 rows)
    //
    // So what tells the window from everything else is the pair and its
    // gap: SALE_GAP_MIN 0.278, the middle of 0.1737 and 0.3818, with no
    // upper end because no pair stands further apart. SALE_LINE_FH_MAX is
    // 0.0088, the middle of the thicker rim (0.0056) and the thinnest run
    // thicker than it (0.0121, the Overdrive news). The share asks only "a
    // line across the canvas", and it is not set at the rims' 0.968: at 1080
    // x 2235 the canvas is 1257 px wide with 88 px cut off each side, and a
    // window drawn in the canvas would cover 0.832 of it on a display the
    // canvas fits, where one drawn across the display covers 0.969 -- only
    // this one format has been seen. SALE_LINE_SHARE is 0.76, the middle of
    // the least the rims can measure (0.832) and the widest run of a window
    // under it (0.694); the one run between the two, bond-not-the-middle's
    // 0.828, is a single line with no second one to pair with.
    //
    // MIDDLE, as the windows across the middle are, by presumption: at 1080
    // x 2235 the three anchors are one rectangle (notes/formats.md, rule 4).
    // The dot and its plate stand far above both rims at every fy of the
    // strip.
    val SALE_ANCHOR = Dungeon.Anchor.MIDDLE
    val SALE_YELLOW = Dungeon.Hsv(intArrayOf(10, 174, 220), intArrayOf(40, 255, 255))
    const val SALE_LINE_SHARE = 0.76
    const val SALE_LINE_FH_MAX = 0.0088
    const val SALE_GAP_MIN = 0.278

    /** A run of rows a colour covers [share] of the canvas's columns in, at its widest row. */
    class Line(val fy0: Double, val fy1: Double, val rows: Int, val share: Double) {
        val fh get() = fy1 - fy0
    }

    /** Where the sale window stands: its two yellow rims, as fy of [SALE_ANCHOR]'s rectangle. */
    data class Sale(val fy0: Double, val fy1: Double) {
        fun toOracle(): Map<String, Any?> = mapOf("fy0" to fy0, "fy1" to fy1)
    }

    /**
     * Every run of rows in which [colour] covers [minShare] of the canvas's
     * columns in the picture or more, top to bottom, as fractions of
     * [SALE_ANCHOR]'s rectangle.
     */
    internal fun lines(img: Mat, colour: Dungeon.Hsv, minShare: Double): List<Line> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, SALE_ANCHOR)
        if (gw <= 0 || gh <= 0) return emptyList()
        val left = maxOf(0, Py.int(x0 + Dungeon.GAME_IN_WINDOW[0] * gw))
        val right = minOf(img.cols(), Py.int(x0 + (Dungeon.GAME_IN_WINDOW[0] + Dungeon.GAME_IN_WINDOW[2]) * gw))
        if (right - left < 10) return emptyList()
        val sub = Py.crop(img, 0, img.rows(), left, right) ?: return emptyList()
        val hsv = Dungeon.hsv(sub)
        val mask = Dungeon.inRange(hsv, colour)
        val sums = Mat()
        org.opencv.core.Core.reduce(mask, sums, 1, org.opencv.core.Core.REDUCE_SUM, org.opencv.core.CvType.CV_32S)
        val counts = IntArray(sums.rows())
        sums.get(0, 0, counts)
        hsv.release(); mask.release(); sums.release(); sub.release()
        val width = (right - left).toDouble()
        val out = ArrayList<Line>()
        var start = -1
        var best = 0.0
        for (y in 0..counts.size) {
            val share = if (y < counts.size) counts[y] / 255.0 / width else 0.0
            if (share >= minShare) {
                if (start < 0) { start = y; best = 0.0 }
                best = maxOf(best, share)
            } else if (start >= 0) {
                out += Line((start - y0) / gh.toDouble(), (y - y0) / gh.toDouble(), y - start, best)
                start = -1
            }
        }
        return out
    }

    /**
     * The game's "Time Sale!" window over whatever it came up on, or null:
     * two thin yellow rims across the canvas, [SALE_GAP_MIN] or more apart.
     * It has no X and one button, the purchase: nothing here is ever tapped
     * on it.
     */
    fun saleWindow(img: Mat): Sale? {
        val rims = lines(img, SALE_YELLOW, SALE_LINE_SHARE).filter { it.fh <= SALE_LINE_FH_MAX }
        for ((i, top) in rims.withIndex()) {
            for (bottom in rims.drop(i + 1)) {
                if (bottom.fy0 - top.fy0 >= SALE_GAP_MIN) return Sale(top.fy0, bottom.fy1)
            }
        }
        return null
    }

    // ------------------------------------------------------------------------
    // The game's Help tutorial, Back and Next (PLAN_RELEASE_1_3.md B73)
    // ------------------------------------------------------------------------
    // The first visit of a page the game has a tutorial for puts "Help"
    // over it: a picture, a sentence, a page counter "1/3", a Reward of 25
    // gems, and Back and Next. Seen on instance 1 on 2026-10-01 (R3,
    // 1080 x 1920) over the Digivice at 13:10 and over Tactical Memory at
    // 13:20, the first visits of both on that account: Presets tapped the
    // icon's constant three times, twice into the window, read no bar and
    // parked "could not get home"; the director called the window `dialog`
    // and parked on it (B73). Nothing here taps it -- what is on its last
    // page and what closes it have not been seen (B81) -- it is read so that
    // Presets stops at it and the director says what it is.
    //
    // What it has: a Next of Dungeon.BLUE beside a Back of its own size in
    // a darker blue (HELP_DARK; the Back measures H 107, S 205, V 113, the
    // window behind it V 85), on one row, and the page counter between them
    // above in the Back's colour. Measured with `gradlew :core:titleProbe
    // --args="help corpus staging/release13/kept staging/release13
    // staging/b70"` over the 1906 frames of the corpus and 31 staged
    // pictures (staging/b73/titleProbe_help.txt): every BLUE blob of a
    // button's size low in the middle's rectangle against every blob of
    // HELP_DARK of a button's size left of it, 313 pairs, nine of them the
    // Help -- the two kept frames, six screencaps of the same windows, and
    // one of the window still growing in (presets_run_a, 0.945 of its size):
    //
    //                          Help             the nearest other uncut pair
    //   sizes, |w-1|, |h-1|    0.010 at most    0.077 (summon/stalled_00), 0.27 (General's tabs)
    //   fills, both buttons    0.906 and up     0.390 (the fullest other Back)
    //   dx / Next's width      1.076 to 1.081   0.984 (General's tabs), 1.270
    //   dy                     0.0003           0.0024 (General's tabs)
    //   the counter, dx        0.0000           0.0521 (the nearest other box)
    //   the counter, above by  0.0667 to 0.0705 0.0508 and 0.0902
    //
    // Each test stands in the middle of its gap: HELP_PAIR_SIZE 0.044,
    // HELP_BUTTON_FILL_MIN 0.65, HELP_PAIR_DX 1.03 to 1.175 of the Next's
    // width, HELP_PAIR_DY 0.0014, HELP_COUNTER_DX 0.026, HELP_COUNTER_DY
    // 0.059 to 0.080. The sizes and the fills are what refuse every other
    // pair; the rest is the window's furniture where it stands.
    //
    // MIDDLE by presumption, as the windows across the middle are: both
    // windows were seen at 1080 x 1920 only, where the three anchors are one
    // rectangle (notes/formats.md, rule 4).
    val HELP_ANCHOR = Dungeon.Anchor.MIDDLE
    val HELP_DARK = Dungeon.Hsv(intArrayOf(100, 150, 95), intArrayOf(118, 255, 150))
    val HELP_NEXT_BAND = doubleArrayOf(0.30, 1.00, 0.60, 0.95)
    val HELP_BACK_BAND = doubleArrayOf(0.00, 0.70, 0.60, 0.95)
    val HELP_BUTTON_FW = doubleArrayOf(0.15, 0.45)
    val HELP_BUTTON_ASPECT = doubleArrayOf(1.5, 5.0)
    val HELP_COUNTER_BAND = doubleArrayOf(0.20, 0.80, 0.55, 0.90)
    val HELP_COUNTER_FW = doubleArrayOf(0.08, 0.25)
    val HELP_COUNTER_ASPECT = doubleArrayOf(1.5, 4.5)
    val HELP_PAIR_DX = doubleArrayOf(1.03, 1.175)
    const val HELP_PAIR_DY = 0.0014
    const val HELP_PAIR_SIZE = 0.044
    const val HELP_BUTTON_FILL_MIN = 0.65
    val HELP_COUNTER_DY = doubleArrayOf(0.059, 0.080)
    const val HELP_COUNTER_DX = 0.026

    /** The Nexts, the Backs and the page counters [helpWindow] looks at, with their numbers (TitleProbe's `help`). */
    internal fun helpBlobs(img: Mat): Triple<List<Blob>, List<Blob>, List<Blob>> {
        val rect = Dungeon.gameRect(img, HELP_ANCHOR)
        val nexts = blobs(img, rect, Dungeon.BLUE, HELP_NEXT_BAND, HELP_BUTTON_FW, HELP_BUTTON_ASPECT)
        if (nexts.isEmpty()) return Triple(nexts, emptyList(), emptyList())
        val backs = blobs(img, rect, HELP_DARK, HELP_BACK_BAND, HELP_BUTTON_FW, HELP_BUTTON_ASPECT)
        val counters = blobs(img, rect, HELP_DARK, HELP_COUNTER_BAND, HELP_COUNTER_FW, HELP_COUNTER_ASPECT)
        return Triple(nexts, backs, counters)
    }

    internal fun isHelpPair(next: Blob, back: Blob): Boolean =
        !next.cut && !back.cut && next.fill >= HELP_BUTTON_FILL_MIN && back.fill >= HELP_BUTTON_FILL_MIN &&
            HELP_PAIR_DX[0] * next.fw <= next.fx - back.fx && next.fx - back.fx <= HELP_PAIR_DX[1] * next.fw &&
            kotlin.math.abs(next.fy - back.fy) <= HELP_PAIR_DY &&
            kotlin.math.abs(back.fw / next.fw - 1.0) <= HELP_PAIR_SIZE &&
            kotlin.math.abs(back.fh / next.fh - 1.0) <= HELP_PAIR_SIZE

    internal fun isHelpCounter(next: Blob, back: Blob, counter: Blob): Boolean =
        !counter.cut && counter.fill >= HELP_BUTTON_FILL_MIN &&
            kotlin.math.abs(counter.fx - (next.fx + back.fx) / 2) <= HELP_COUNTER_DX &&
            HELP_COUNTER_DY[0] <= next.fy - counter.fy && next.fy - counter.fy <= HELP_COUNTER_DY[1]

    /**
     * The game's Help tutorial over a page, or null: a Next beside a Back of
     * its own size, and the page counter between them above. Its Next, which
     * nothing here taps.
     */
    fun helpWindow(img: Mat): Dungeon.Button? {
        val (nexts, backs, counters) = helpBlobs(img)
        for (next in nexts) {
            for (back in backs) {
                if (!isHelpPair(next, back)) continue
                if (counters.none { isHelpCounter(next, back, it) }) continue
                return next.button()
            }
        }
        return null
    }

    // ------------------------------------------------------------------------
    // The hologram device's gear window: Sell beside Equip (K2, 2026-10-04)
    // ------------------------------------------------------------------------
    // A pull of the hologram device that draws a piece of gear puts a window
    // over the main screen: two cards, "Equipped" over "Not Equipped" (with
    // "NEW"), and under them Sell, pink, beside Equip, blue -- no X, no
    // Close. Under Auto Spend with the Super Hologram Device it comes by
    // itself for a piece the S settings keep, and it stands until somebody
    // chooses: 89.5 s on instance 1 on 2026-10-03 (staging/k2/measure.txt).
    // classify called it `dialog` (recognise's blue button), the
    // semi-automatic mode parked on it, every way in from the main screen
    // waited MainScreen.WAIT for the auto button under it and gave up, and
    // popupOk reads Equip as an OK (notes/world-search.md, "A way in asks
    // for the main screen over a few seconds, because the game draws over its
    // auto button by itself"). The player's word of 2026-10-04: never Sell,
    // never Equip; a tap on the dimmed field beside the window closes it, and
    // the piece stays in the inventory ([GEAR_CLOSE]).
    //
    // What it has: a pink button and a blue one of one size side by side on
    // one row, low in the window, wider than any other pair the game draws.
    // Measured with `gradlew :core:titleProbe --args="gear corpus staging/k2
    // <the Poco's frame>"` over the 1893 frames of the corpus, the two K2
    // pictures and the Poco F3's kept frame of 2026-10-02 18:59:08
    // (staging/gear/titleProbe_gear_corpus.txt): every blob of GEAR_PINK
    // (the hue band of the pink prompt's Cancel, Dungeon.PARTY_PINK_HUE;
    // the Sell measures H 148, S 158, V 223 flat) and of Dungeon.BLUE (the
    // Equip H 100, S 255, V 209) 0.08 to 0.48 of the width and 1.3 to 6 times
    // as wide as tall, low in the middle's rectangle (fy 0.40 to 1.05), each
    // pink against each blue right of it on its row, 325 pairs; the gear
    // window's are 15, on the twelve corpus frames that are this window --
    // kept by the passive helper, the quest loop and the bond tour on the
    // player's account and named by nobody (bond/open_the_Digimon_page_140453,
    // _163700, passive/unclear_161516 to _215930, quest/no-x-to-get-out-with
    // _082048, _124001, _133917) -- and the three pictures:
    //
    //                         the gear window        the nearest other uncut pair
    //   the row, fy           0.8639 to 0.8641       0.6149 (the Partner tab's pair), the prompts 0.600 to 0.613
    //   each button's width   0.2642 to 0.2648       0.1967 (the prompts: Return, Disband, ad confirms, Chef's pause)
    //   dx / Equip's width    1.104 to 1.106         0.976 (the Partner tab's), 1.222 (passive/unclear_125331)
    //   dy                    0.0008 at most         0.0199 (the Partner tab's)
    //   sizes, |w-1|, |h-1|   0.027 at most          0.436 (the nearest pair at the gear's offset)
    //   fills, both buttons   0.919 and up           0.278 (the same pair)
    //
    // Each test stands in the middle of its gap: the band from fy 0.74
    // (GEAR_BAND), a button at least 0.23 wide (GEAR_BUTTON_FW, its upper end
    // the window measured), GEAR_PAIR_DX 1.04 to 1.164, GEAR_PAIR_DY 0.010,
    // GEAR_PAIR_SIZE 0.23, GEAR_FILL_MIN 0.60. The row and the width are what
    // refuse every prompt -- a pink Cancel beside a blue OK, the same face
    // half as low and a quarter narrower --; the rest is the pair's shape.
    // On the Poco's 1080 x 2320 the pair read where it reads at 1920, within
    // 0.0006 (the cover: rule 7's fence holds, neither button touches a side).
    //
    // The anchor is MIDDLE, measured (notes/formats.md, rule 4): one window
    // raised on LDPlayer instance 1 on 2026-10-04 and kept standing through
    // the rows of rule 17 with `wm size` (corpus/formats/gear_*, the same
    // window on every row, so every row's twin is the 1920 frame of it).
    // Asked at all three anchors, the 1920 twin's row, fy 0.8636, came at
    // MIDDLE over the ceiling -- 0.8637 at 1080 x 2520 (180 rows of headroom;
    // BOTTOM 0.8264, TOP 0.9010) and 0.8636 at 720 x 1600 (40 rows; BOTTOM
    // 0.8511, TOP 0.8760). Beside its twin on every row (`formatProbe pair`,
    // staging/gear/formatProbe_pair_result.txt) the answer moved 0.0011 at
    // the most; under the dot and its plate it read at all 20 places of the
    // strip on all seven rows (staging/gear/readerProbe_gear_mask.txt). It
    // costs classify 1.8 ms median a round over the corpus (p95 3.9, at most
    // 6.3), staging/gear/titleProbe_gear_reader.txt.
    val GEAR_ANCHOR = Dungeon.Anchor.MIDDLE
    val GEAR_PINK = Dungeon.Hsv(intArrayOf(135, 100, 150), intArrayOf(175, 255, 255))
    val GEAR_BAND = doubleArrayOf(0.0, 1.0, 0.74, 1.0)
    val GEAR_BUTTON_FW = doubleArrayOf(0.23, 0.48)
    val GEAR_BUTTON_ASPECT = doubleArrayOf(1.3, 6.0)
    val GEAR_PAIR_DX = doubleArrayOf(1.04, 1.164)
    const val GEAR_PAIR_DY = 0.010
    const val GEAR_PAIR_SIZE = 0.23
    const val GEAR_FILL_MIN = 0.60

    /**
     * Where the window is closed: QuestSkill.DEAD_TAP, in the bottom's
     * rectangle, the dimmed field left of the window at half height. The
     * spot the quest loop closes its reward window at, player-checked to open
     * nothing on the plain main screen and inside the picture on every row of
     * corpus/formats; left of the window's cards (from fx 0.145) and below
     * the HUD's left column. Never Sell, never Equip: the window's two
     * buttons stand from fx 0.20 at fy 0.83 to 0.90, and nothing aims at them.
     * Tried by hand on instance 1 on 2026-10-04: a tap at 109,874 (1080 x
     * 1920) and at 15,1245 (1080 x 2520) closed the window and opened
     * nothing, and two more at each on the plain main screen opened nothing
     * either. The piece stays in the device's capsule, and a tap on the
     * device opens the same window again, no hologram spent.
     */
    val GEAR_CLOSE = QuestSkill.DEAD_TAP
    val GEAR_CLOSE_ANCHOR = Dungeon.Anchor.BOTTOM

    /**
     * The gear window's two buttons, where they were read: the row, the
     * middle of Sell and of Equip, and Equip's width, in the rectangle of
     * [GEAR_ANCHOR]. No button: nothing here taps either of them.
     */
    class Gear(val fy: Double, val sellFx: Double, val equipFx: Double, val fw: Double) {
        fun toOracle(): Map<String, Any?> = mapOf("fy" to fy, "sell_fx" to sellFx, "equip_fx" to equipFx, "fw" to fw)
    }

    /** The pinks and the blues [gearWindow] looks at. */
    internal fun gearBlobs(img: Mat): Pair<List<Blob>, List<Blob>> {
        val rect = Dungeon.gameRect(img, GEAR_ANCHOR)
        val pinks = blobs(img, rect, GEAR_PINK, GEAR_BAND, GEAR_BUTTON_FW, GEAR_BUTTON_ASPECT)
        if (pinks.isEmpty()) return pinks to emptyList()
        return pinks to blobs(img, rect, Dungeon.BLUE, GEAR_BAND, GEAR_BUTTON_FW, GEAR_BUTTON_ASPECT)
    }

    internal fun isGearPair(sell: Blob, equip: Blob): Boolean =
        !sell.cut && !equip.cut && sell.fill >= GEAR_FILL_MIN && equip.fill >= GEAR_FILL_MIN &&
            GEAR_PAIR_DX[0] * equip.fw <= equip.fx - sell.fx && equip.fx - sell.fx <= GEAR_PAIR_DX[1] * equip.fw &&
            kotlin.math.abs(equip.fy - sell.fy) <= GEAR_PAIR_DY &&
            kotlin.math.abs(sell.fw / equip.fw - 1.0) <= GEAR_PAIR_SIZE &&
            kotlin.math.abs(sell.fh / equip.fh - 1.0) <= GEAR_PAIR_SIZE

    /**
     * The hologram device's gear window over the main screen, or null: a
     * pink Sell beside a blue Equip of its size, low and wide. Read so that
     * the director closes it beside the window ([GEAR_CLOSE]) and a way in
     * does the same; neither of its buttons is ever tapped.
     */
    fun gearWindow(img: Mat): Gear? {
        val (pinks, blues) = gearBlobs(img)
        for (sell in pinks) {
            for (equip in blues) {
                if (isGearPair(sell, equip)) return Gear(equip.fy, sell.fx, equip.fx, equip.fw)
            }
        }
        return null
    }
}
