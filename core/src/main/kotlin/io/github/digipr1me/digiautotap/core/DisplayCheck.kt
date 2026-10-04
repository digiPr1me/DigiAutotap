package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.abs

/**
 * Does the game stand where game_rect says it does? PLAN_FORMATE.md 10.
 *
 * Two landmarks the game draws on every device, looked for without
 * assuming where game_rect puts them, and then compared with where it
 * does: the globe in the middle of the bottom nav bar, the HUD's bottom
 * edge ([Dungeon.Anchor.BOTTOM]), and the green experience bar beside the
 * player's portrait, the HUD's top edge ([Dungeon.Anchor.TOP]). A screen
 * without either says nothing; a landmark where it is expected says the
 * format is known; one somewhere else is a display game_rect has no model
 * of, and the app says so once instead of tapping wrong in silence.
 */
object DisplayCheck {

    /** One landmark: the globe's centre or the bar's left end, as fractions of its anchor's game_rect. */
    data class Mark(val fx: Double, val fy: Double, val fw: Double, val fh: Double, val holes: Int = 0,
                    val fill: Double = 0.0) {
        fun say(): String = String.format(Locale.ROOT, "%.4f,%.4f w%.4f h%.4f", fx, fy, fw, fh) +
            (if (holes > 0) " o$holes" else "") + (if (fill > 0) String.format(Locale.ROOT, " f%.2f", fill) else "")
    }

    enum class Verdict { SILENT, KNOWN, UNKNOWN }

    data class Result(val verdict: Verdict, val globe: Mark?, val bar: Mark?) {
        /** For the log: both landmarks against their expected places. */
        fun say(): String = "nav globe " + (globe?.let { "${it.say()} (expected ${at(GLOBE_AT)})" } ?: "not seen") +
            ", top bar " + (bar?.let { "${it.say()} (expected ${at(BAR_AT)})" } ?: "not seen")
    }

    private fun at(a: DoubleArray) = String.format(Locale.ROOT, "%.4f,%.4f", a[0], a[1])

    // Measured 2026-09-27 over every frame of the oracle, 1515, all formats
    // (`gradlew :core:selfCheckProbe`, SelfCheckProbe.kt): every
    // globe-shaped white blob in the lower part of the picture and every flat
    // green run in the upper part, found without game_rect, then placed in
    // it.

    // --- the globe ----------------------------------------------------------
    // Dungeon.HOME_WHITE, HOME_ASPECT and HOME_FILL are the glyph. Found where
    // home_button finds it, on 657 frames: fx 0.4760 to 0.4787, fy 0.9377 to
    // 0.9456, fw 0.0467 to 0.0501, and 9 to 12 holes in it (616 of them 9).
    //
    // Searched in a window of the *picture*, not of game_rect, which is the
    // thing in doubt: the middle fifth of its width, because the canvas is
    // centred in every model and every window the system gives (GameWindow),
    // and the rows under 0.87, because a navigation bar the game does not
    // draw under lifts the globe by 48 dp, 0.066 of 1920. Over the whole
    // lower 40 % and the whole width the glyph's shape alone let 4211 other
    // blobs through; with 8 to 12 holes 145, all of them the item icons of
    // the main screen's panel (fx 0.35, fy 0.87) and a tap highlight at fx
    // 0.367 -- outside the middle fifth; three more lay at fy 0.853 to 0.859,
    // under the band. The width band is wide on purpose: a display game_rect
    // gets wrong draws the globe at another scale.
    val GLOBE_SEARCH = doubleArrayOf(0.40, 0.60, 0.87, 1.0)
    val GLOBE_W = doubleArrayOf(0.025, 0.10)
    val GLOBE_HOLES = 8..12
    val GLOBE_AT = doubleArrayOf(0.4769, 0.9429)

    // --- the experience bar --------------------------------------------------
    // The green fill of the bar under the Tamer level, drawn on the main
    // screen, the dungeon list, the Explore menu and every page with the top
    // HUD, and anchored to the top of the display (on a display with
    // headroom it stands in the headroom, Dungeon.Anchor.TOP). Its left end
    // does not move with the experience: found on 680 frames at fx 0.2157
    // to 0.2199, fy 0.0720 to 0.0756, fh 0.0081 to 0.0100, green on 0.63
    // (a dimmed 720 x 1280 frame, the numbers over the bar cutting it) to
    // 0.99 of its box.
    //
    // What else is green and flat up there: the Buddy Summon banner's
    // lettering (0.15 of its box green) and a Friends list avatar (0.45), so
    // BAR_FILL_MIN is 0.55, between 0.45 and 0.63 -- the thinnest margin of
    // the check, and the one [Watch]'s second look is for; the Meat Field's
    // own bar, 0.0126 to 0.0134 tall, over BAR_H; the Runner's egg bar, 0.035.
    // BAR_EDGE_GREEN refuses a stripe of a larger green thing that letters
    // cut into rows. The window is the left 45 % of the picture and its top
    // 12 %: the bar's left end stands at 0.41 of a landscape display's
    // width, the furthest right of any picture the app gets whole.
    val BAR_GREEN = Dungeon.Hsv(intArrayOf(40, 150, 150), intArrayOf(80, 255, 255))
    val BAR_SEARCH = doubleArrayOf(0.0, 0.45, 0.0, 0.12)
    val BAR_H = doubleArrayOf(0.0065, 0.0115)
    const val BAR_ASPECT_MIN = 2.5
    const val BAR_FILL_MIN = 0.55
    val BAR_AT = doubleArrayOf(0.2171, 0.0727)

    // How far a landmark may stand from its place and still be there. The
    // right ones spread 0.004 at most (above); the wrong formats of the
    // corpus put the bar 0.029 to 0.0575 off -- the Poco F3's uncut frames
    // 0.031 low, `waterfall` 0.0375 right, LDPlayer's `double` 0.0575 right
    // -- and a `wm size` of 1080 x 2000, which the frame model takes for a
    // letterboxed window, 0.0288 high on the live proof. 0.01 is a factor of
    // 2.5 over the spread and 2.9 under the nearest wrong one. The globe of
    // that 2000 display stood 0.0084 low, inside it: the bottom anchor
    // absorbs what goes wrong at the top, which is why there are two
    // landmarks.
    const val POS_TOL = 0.01

    private fun window(img: Mat, s: DoubleArray): IntArray = intArrayOf(
        Py.int(img.cols() * s[0]), Py.int(img.cols() * s[1]), Py.int(img.rows() * s[2]), Py.int(img.rows() * s[3]))

    fun globes(img: Mat): List<Mark> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, Dungeon.Anchor.BOTTOM)
        val (left, right, top, bottom) = window(img, GLOBE_SEARCH).toList()
        val sub = Py.crop(img, top, bottom, left, right) ?: return emptyList()
        val hsv = Dungeon.hsv(sub)
        val mask = Dungeon.inRange(hsv, Dungeon.HOME_WHITE)
        hsv.release()
        val labels = Mat(); val st = Mat(); val cen = Mat()
        val count = Imgproc.connectedComponentsWithStats(mask, labels, st, cen, 8)
        mask.release(); cen.release()
        val all = stats(st, count)
        val out = ArrayList<Mark>()
        for (i in 1 until count) {
            val x = all[i * 5]; val y = all[i * 5 + 1]; val w = all[i * 5 + 2]; val h = all[i * 5 + 3]
            val area = all[i * 5 + 4]
            if (h == 0) continue
            val fw = w / gw.toDouble()
            if (fw < GLOBE_W[0] || fw > GLOBE_W[1]) continue
            val aspect = w / h.toDouble()
            if (aspect < Dungeon.HOME_ASPECT[0] || aspect > Dungeon.HOME_ASPECT[1]) continue
            val fill = area / (w * h).toDouble()
            if (fill < Dungeon.HOME_FILL[0] || fill > Dungeon.HOME_FILL[1]) continue
            val holes = holes(labels, i, x, y, w, h)
            if (holes !in GLOBE_HOLES) continue
            out += Mark((left + x + w / 2.0 - x0) / gw, (top + y + h / 2.0 - y0) / gh, fw, h / gh.toDouble(), holes)
        }
        labels.release(); st.release()
        return out
    }

    /** The whole stats table in one read: a JNI call per row costs more than the rest of the check. */
    private fun stats(st: Mat, count: Int): IntArray {
        val all = IntArray(count * 5)
        if (count > 0) st.get(0, 0, all)
        return all
    }

    /** Background islands inside component [i]'s box that touch no edge of it: the glyph's holes. */
    private fun holes(labels: Mat, i: Int, x: Int, y: Int, w: Int, h: Int): Int {
        val roi = labels.submat(y, y + h, x, x + w)
        val inside = Mat()
        Core.compare(roi, Scalar(i.toDouble()), inside, Core.CMP_NE)
        val lab = Mat(); val st = Mat(); val cen = Mat()
        val n = Imgproc.connectedComponentsWithStats(inside, lab, st, cen, 4)
        val row = IntArray(5)
        var holes = 0
        for (k in 1 until n) {
            st.get(k, 0, row)
            if (row[0] > 0 && row[1] > 0 && row[0] + row[2] < w && row[1] + row[3] < h) holes++
        }
        roi.release(); inside.release(); lab.release(); st.release(); cen.release()
        return holes
    }

    /**
     * Green runs of the bar's height, the pieces of one row taken together:
     * the numbers written over the bar cut its green into pieces, and on a
     * narrow display the first piece alone is too short to be a bar.
     */
    fun bars(img: Mat): List<Mark> {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, Dungeon.Anchor.TOP)
        val (left, right, top, bottom) = window(img, BAR_SEARCH).toList()
        val sub = Py.crop(img, top, bottom, left, right) ?: return emptyList()
        val hsv = Dungeon.hsv(sub)
        val mask = Dungeon.inRange(hsv, BAR_GREEN)
        val labels = Mat(); val st = Mat(); val cen = Mat()
        val count = Imgproc.connectedComponentsWithStats(mask, labels, st, cen, 8)
        hsv.release(); labels.release(); cen.release()
        val all = stats(st, count)
        st.release()
        val pieces = (1 until count).map { all.copyOfRange(it * 5, it * 5 + 5) }
            .filter { it[3] > 0 && it[3] / gh.toDouble() in BAR_H[0]..BAR_H[1] }
            .sortedBy { it[0] }
        val rows = ArrayList<IntArray>()   // x, y, right, bottom, green pixels
        for (p in pieces) {
            val r = rows.firstOrNull { abs(it[1] - p[1]) <= 2 && abs(it[3] - (p[1] + p[3])) <= 2 }
            if (r == null) rows += intArrayOf(p[0], p[1], p[0] + p[2], p[1] + p[3], p[4])
            else { r[2] = maxOf(r[2], p[0] + p[2]); r[4] += p[4] }
        }
        val found = rows.filter { (it[2] - it[0]) / (it[3] - it[1]).toDouble() >= BAR_ASPECT_MIN && alone(mask, it) &&
            it[4] / ((it[2] - it[0]) * (it[3] - it[1])).toDouble() >= BAR_FILL_MIN }
        mask.release()
        return found.map {
            Mark((left + it[0] - x0) / gw.toDouble(), (top + it[1] - y0) / gh.toDouble(),
                 (it[2] - it[0]) / gw.toDouble(), (it[3] - it[1]) / gh.toDouble(),
                 fill = it[4] / ((it[2] - it[0]) * (it[3] - it[1])).toDouble())
        }
    }

    /**
     * Is the row group [r] (x, y, right, bottom in the mask) a bar and not a
     * stripe of a larger green thing -- a panel, a pixel-art avatar -- that
     * the letters on it cut into rows? The bar's frame is dark: the rows
     * [BAR_EDGE_GAP] above and below it carry at most [BAR_EDGE_GREEN] of green.
     */
    private fun alone(mask: Mat, r: IntArray): Boolean {
        for (y in intArrayOf(r[1] - BAR_EDGE_GAP, r[3] - 1 + BAR_EDGE_GAP)) {
            if (y < 0 || y >= mask.rows()) continue
            val line = mask.submat(y, y + 1, r[0], r[2])
            val share = Core.countNonZero(line) / (r[2] - r[0]).toDouble()
            line.release()
            if (share > BAR_EDGE_GREEN) return false
        }
        return true
    }

    const val BAR_EDGE_GAP = 2
    const val BAR_EDGE_GREEN = 0.10

    private fun near(m: Mark, at: DoubleArray): Boolean =
        abs(m.fx - at[0]) <= POS_TOL && abs(m.fy - at[1]) <= POS_TOL

    fun check(img: Mat): Result {
        val g = globes(img)
        val b = bars(img)
        val globe = g.firstOrNull { near(it, GLOBE_AT) } ?: g.firstOrNull()
        val bar = b.firstOrNull { near(it, BAR_AT) } ?: b.firstOrNull()
        val globeOk = globe?.let { near(it, GLOBE_AT) }
        val barOk = bar?.let { near(it, BAR_AT) }
        val verdict = when {
            globeOk == false || barOk == false -> Verdict.UNKNOWN
            globeOk == true || barOk == true -> Verdict.KNOWN
            else -> Verdict.SILENT
        }
        return Result(verdict, globe, bar)
    }

    /**
     * The check where the screen or the display changes (DirectorLoop.note,
     * every frame the director reads, [look] passing straight through
     * while neither has): a few rows of pixels once per screen, never per
     * frame. A display of a shape the check has called unknown is said once
     * -- "unknown display format", the numbers, the frame kept -- and not
     * checked again; a known one is said once too, so that the first log of
     * every new phone carries the two landmarks and what the check cost
     * there.
     *
     * **Unknown is said on two frames, never on one** ("Never trust a single
     * frame"). Measured live on LDPlayer 2026-09-27 23:26: `wm size` back
     * from 1080 x 2000 to 1920, and the first 1080 x 1920 frame still held
     * the game's 2000 layout -- the top bar at fy 0.0763 against 0.0727, the
     * globe gone, the director's screen "unknown" -- and one look called it
     * an unknown format, and, the shape being remembered, the display was
     * never looked at again. So an unknown look is only a suspicion: the
     * next [CONFIRM_LOOKS] frames are looked at whatever the screen, a known
     * one ends it, and it is said when a second look finds the same
     * landmark off the same way (within [POS_TOL]).
     */
    class Watch(private val log: (String) -> Unit, private val keep: (Mat, String) -> Unit) {
        private var unknownFor: List<Int>? = null
        private var knownFor: List<Int>? = null
        private var lastScreen: String? = null
        private var lastShape: List<Int>? = null
        private var suspect: Result? = null
        private var suspectShape: List<Int>? = null
        private var looksLeft = 0

        /** [look] if [screen] or the frame's shape is not the last one's, or while a suspicion waits; nothing otherwise. */
        fun look(img: Mat, screen: String) {
            val shape = listOf(img.cols(), img.rows(), Dungeon.headroom(img))
            val waiting = suspect != null && shape == suspectShape
            if (!waiting && screen == lastScreen && shape == lastShape) return
            lastScreen = screen
            lastShape = shape
            look(img)
        }

        fun look(img: Mat) {
            val shape = listOf(img.cols(), img.rows(), Dungeon.headroom(img))
            if (shape == unknownFor) return
            if (shape != suspectShape) { suspect = null; suspectShape = null }
            val t0 = System.nanoTime()
            val r = check(img)
            val ms = (System.nanoTime() - t0) / 1e6
            val rect = Dungeon.gameRect(img)
            val head = String.format(Locale.ROOT, "%d x %d%s, game_rect [%d, %d, %d, %d]", img.cols(), img.rows(),
                if (shape[2] > 0) " (headroom ${shape[2]})" else "", rect.x0, rect.y0, rect.gw, rect.gh)
            val cost = String.format(Locale.ROOT, "%.1f ms", ms)
            when (r.verdict) {
                Verdict.UNKNOWN -> {
                    val before = suspect
                    if (before != null && agree(before, r)) {
                        suspect = null; suspectShape = null
                        unknownFor = shape
                        log("unknown display format: $head -- ${r.say()}; the landmarks are not where game_rect " +
                            "puts them, on two frames, so taps here may land wrong -- frame kept ($cost)")
                        keep(img, "unknown_display_format")
                    } else {
                        suspect = r; suspectShape = shape; looksLeft = CONFIRM_LOOKS
                    }
                }
                Verdict.KNOWN -> {
                    suspect = null; suspectShape = null
                    if (shape != knownFor) {
                        knownFor = shape
                        log("display check: $head is a known format -- ${r.say()} ($cost)")
                    }
                }
                Verdict.SILENT -> if (suspect != null && --looksLeft <= 0) { suspect = null; suspectShape = null }
            }
        }

        /** Two unknown looks that found at least one landmark off in the same place. */
        private fun agree(a: Result, b: Result): Boolean {
            fun same(x: Mark?, y: Mark?, at: DoubleArray) = x != null && y != null && !near(x, at) &&
                abs(x.fx - y.fx) <= POS_TOL && abs(x.fy - y.fy) <= POS_TOL
            return same(a.globe, b.globe, GLOBE_AT) || same(a.bar, b.bar, BAR_AT)
        }
    }

    /** Frames a suspicion of an unknown format waits for its second look through screens without landmarks. */
    const val CONFIRM_LOOKS = 3
}
