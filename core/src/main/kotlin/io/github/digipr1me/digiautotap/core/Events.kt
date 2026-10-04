package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat

/**
 * The Events window's cards, and which is which (PLAN_SKEWER.md 3.2).
 *
 * Until 2026-09-30 the way into Gekkomon Run tapped the top card of the
 * Events window blind (`Runner.eventCard`, gone since), because there was only ever one
 * card and nobody read it. The minigames rotate every few updates of the
 * game -- Gekkomon Run, Chef's Special -- and only one is in the window at a
 * time, so a card tapped blind is the other task's card on half the days.
 * So the cards are found and named here, and a task taps only its own.
 *
 * The oracle family `events` holds every reader here to `oracle/events.json`.
 */
object Events {

    // ------------------------------------------------------------------------
    // The rims
    // ------------------------------------------------------------------------
    // The Events window and every card in it stand in the same light-blue
    // rim, one flat colour 4 to 5 px thick at 1080 wide: H 103 S 198 V 239 on
    // events_1080x1920_none0_222047. Around it the box is Runner.EVENT_BLUE
    // (H 106 S 233 V 139); inside the Gekkomon Run card the art's sky next to
    // the rim is H 99 S 177-184 V 240, which the hue floor keeps out.
    val RIM = Dungeon.Hsv(intArrayOf(101, 188, 222), intArrayOf(105, 208, 255))
    const val RIM_MIN_SHARE = 0.002

    /** Every blob of [RIM] in the window rectangle, largest first: what the card reader is built on. */
    fun rims(img: Mat): List<Runner.Blob> = Runner.blobs(img, RIM, minShare = RIM_MIN_SHARE)

    // The window's rim and the card's, over the 44 Events frames of the
    // corpus (every format of the tour, both laboratory shapes, the phone):
    //
    //                 fx0            fx1            fy0            fy1
    //   window rim    0.1547-0.1567  0.7945-0.7970  0.2030-0.2041  0.7745-0.7837
    //   card rim      0.1760-0.1777  0.7720-0.7738  0.2538-0.2550  0.4041-0.4051
    //
    // (the phone's uncut 1080 x 2400 and LDPlayer's `double84` aside, whose
    // rectangles game_rect does not model, notes/formats.md rule 19). So the
    // window is the largest rim blob, over half the rectangle wide and 0.4 of
    // it tall, and a card is a rim blob inside it, the width of the window
    // less its margins, 0.596 of 0.641, and 0.150 tall. A card is asked to be at least 0.85 of the window's
    // width and 0.10 to 0.20 tall: the only other rim-coloured blobs in the
    // window are the card's own art, never that wide.
    const val CARD_W_OF_WINDOW = 0.85
    val CARD_H = doubleArrayOf(0.10, 0.20)

    /** The window's rim: the largest rim blob at least half the rectangle wide and 0.4 of it tall. */
    fun window(img: Mat): Runner.Blob? =
        rims(img).firstOrNull { it.height >= 0.4 && it.width >= 0.5 }

    const val GEKKOMON_RUN = "gekkomon_run"
    const val CHEFS_SPECIAL = "chefs_special"
    const val UNKNOWN = "unknown"

    /** One card of the Events window: its rim, window fractions, and what it is. */
    data class Card(val kind: String, val fx0: Double, val fy0: Double, val fx1: Double, val fy1: Double,
                    /** The art's sky-blue and leaf-green shares ([artShares]) it was named by. */
                    val sky: Double = 0.0, val leaf: Double = 0.0) {
        /** The card's own middle: the whole card is the button. */
        val target get() = Explore.Target((fx0 + fx1) / 2, (fy0 + fy1) / 2)
        fun toOracle(): Map<String, Any?> = mapOf("kind" to kind, "fx0" to fx0, "fy0" to fy0, "fx1" to fx1, "fy1" to fy1,
                                                   "sky" to sky, "leaf" to leaf)
    }

    /**
     * The cards of the Events window, top first, or null where there is no
     * Events window. An Events window with no card it can read is an empty
     * list, and a card it cannot name is [UNKNOWN] -- never a guess.
     */
    fun cards(img: Mat): List<Card>? {
        val window = window(img) ?: return null
        if (Runner.eventsDialog(img) == null) return null
        val out = ArrayList<Card>()
        for (b in rims(img)) {
            if (b == window) continue
            if (b.width < CARD_W_OF_WINDOW * window.width) continue
            if (b.height !in CARD_H[0]..CARD_H[1]) continue
            if (b.fx0 < window.fx0 || b.fx1 > window.fx1 || b.fy0 < window.fy0 || b.fy1 > window.fy1) continue
            val (sky, leaf) = artShares(img, b)
            out.add(Card(name(sky, leaf), b.fx0, b.fy0, b.fx1, b.fy1, sky, leaf))
        }
        return out.sortedBy { it.fy0 }
    }

    /** Where to tap the card of [kind], or null where the window has none. */
    fun card(img: Mat, kind: String): Explore.Target? = cards(img)?.firstOrNull { it.kind == kind }?.target

    // ------------------------------------------------------------------------
    // Which card is which
    // ------------------------------------------------------------------------
    // The card is named by its art, not by its title. The title is white
    // text on the art, and the Gekkomon Run art's clouds are the same white:
    // a white mask over the card's top third runs from 0.03 to 0.99 of the
    // card's width on every Gekkomon Run frame, and from 0.03 to 0.92 on
    // Chef's Special -- the clouds, not the words. The art is where the two
    // cards differ outright. Over the middle band of the card (0.35 to 0.65
    // of its height, clear of the title above and the progress bar below),
    // the share of the saturated pixels (S > 80) whose hue is sky blue (H 90
    // to 115) and whose hue is leaf green (H 35 to 85), measured 2026-09-30
    // with this reader over every Events frame there is (readerProbe):
    //
    //                            frames   sky blue       leaf green
    //   Gekkomon Run (a city      42       0.915-0.920    0.001-0.002   every format of the tour but
    //     under a blue sky)                                             double84, both laboratory
    //                                                                   shapes, the phone
    //   Chef's Special (a camp     2       0.000          0.794-0.799   1080 x 1920 and 1080 x 2340
    //     in a green wood)                                              hole105, one session
    //
    // So a card is Gekkomon Run where sky blue is over ART_SHARE and green is
    // not, Chef's Special the other way round, and anything else is unknown:
    // a third minigame, or new art for one of these two, is a card nobody
    // taps until it has been measured (PLAN_SKEWER.md 3.2 and 11). 0.40 is the
    // middle of the narrower gap, green's 0.002 to 0.794 (0.398); blue's gap,
    // 0.000 to 0.915, has its middle at 0.458, and 0.40 stands 0.40 above its
    // lower side and 0.52 under its upper.
    val ART_BAND = doubleArrayOf(0.35, 0.65)
    const val ART_MARGIN = 0.03
    const val ART_S_MIN = 80
    val SKY = intArrayOf(90, 115)
    val LEAF = intArrayOf(35, 85)
    const val ART_SHARE = 0.40

    /** (sky blue, leaf green): the shares of the card's saturated art pixels in each hue band. */
    fun artShares(img: Mat, rim: Runner.Blob): Pair<Double, Double> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val mx = ART_MARGIN * rim.width
        val sub = Py.crop(img,
                          maxOf(0, Py.int(y0 + (rim.fy0 + ART_BAND[0] * rim.height) * gh)),
                          minOf(img.rows(), Py.int(y0 + (rim.fy0 + ART_BAND[1] * rim.height) * gh)),
                          maxOf(0, Py.int(x0 + (rim.fx0 + mx) * gw)),
                          minOf(img.cols(), Py.int(x0 + (rim.fx1 - mx) * gw))) ?: return 0.0 to 0.0
        val hsv = Cv.hsv(sub)
        val bytes = ByteArray(hsv.rows() * hsv.cols() * 3)
        hsv.get(0, 0, bytes)
        hsv.release()
        var saturated = 0; var sky = 0; var leaf = 0
        for (i in bytes.indices step 3) {
            if ((bytes[i + 1].toInt() and 0xff) <= ART_S_MIN) continue
            saturated += 1
            val h = bytes[i].toInt() and 0xff
            if (h in SKY[0]..SKY[1]) sky += 1
            if (h in LEAF[0]..LEAF[1]) leaf += 1
        }
        if (saturated == 0) return 0.0 to 0.0
        return sky / saturated.toDouble() to leaf / saturated.toDouble()
    }

    /** What a card of these [artShares] is. */
    internal fun name(sky: Double, leaf: Double): String {
        return when {
            sky > ART_SHARE && leaf <= ART_SHARE -> GEKKOMON_RUN
            leaf > ART_SHARE && sky <= ART_SHARE -> CHEFS_SPECIAL
            else -> UNKNOWN
        }
    }

    // ------------------------------------------------------------------------
    // The way out
    // ------------------------------------------------------------------------
    // The window has no X. A tap on the dimmed game beside it closes it: on
    // 2026-09-30, on LDPlayer instance 0, a tap at 60,700 of 1080 x 1920 and
    // at 50,740 of 1080 x 2235 each closed it and left the main screen; in
    // game_rect those two stood 0.179 and 0.146 under the window's top rim.
    // The place is taken between the picture's own left edge and the
    // window's rim -- on a long display the cover cuts the sides
    // (notes/formats.md rule 3), so "left of the window" is measured from
    // what the picture holds, not from game_rect's edge -- and 0.16 under
    // the rim, between the two that worked: below the left column's Notices
    // tile, whose label ends 0.10 under the rim at 1080 x 1920, on stage art.
    const val OUTSIDE_FY_BELOW_TOP = 0.16

    /**
     * Where a tap closes the Events window, or null where there is none.
     *
     * The rim alone is not the Events window: the same light-blue frame
     * stands around a Digimon's Partner window (45 frames of the corpus), six
     * dialogs and 17 other frames, and a tap beside one of those would be a
     * tap on the game. So the place is only given where the event's blue box
     * is inside it (Runner.eventsDialog) -- and RunnerSkill.leave asks it
     * before any other way out.
     */
    fun outside(img: Mat): Explore.Target? {
        if (Runner.eventsDialog(img) == null) return null
        val w = window(img) ?: return null
        val (x0, _, gw, _) = Dungeon.gameRect(img)
        val left = maxOf(0.0, -x0 / gw.toDouble())
        return Explore.Target((left + w.fx0) / 2, w.fy0 + OUTSIDE_FY_BELOW_TOP)
    }
}
