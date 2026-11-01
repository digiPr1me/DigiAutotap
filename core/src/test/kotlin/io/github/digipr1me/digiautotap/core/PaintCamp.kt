package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * The frames of the Camp task (CampSkillTest, DirectorTest), painted with the
 * colours and at the places C1 and C2 measured on corpus/camp/ (PLAN_CAMP.md
 * 6, Camp.kt, CampStation.kt): the main screen with the Camp tab, the Camp
 * screen with its title bar, the tent's roof and the nav bar, the Camp Tent
 * with Support and Check-In, the Camp Support window, the Camp Shop with its
 * cards -- each item's own template from templates/ in its picture, "n/m" and
 * the price on its plate -- and its purchase dialog, a station's window with
 * its counter and its two buttons, its Reward Boxes, the Results an Attempt
 * ends in, and the Reward sheet (PaintMissions.rewardSheet). Its own file, as
 * PaintMissions is.
 *
 * On Paint's window frame of 805 x 1390 game_rect is the whole image, so the
 * three anchors are one and every fraction below is the reader's own. The
 * digits are OpenCV's, drawn to the proportions Dungeon.readDigit was
 * measured on, with the slash drawn as a line and a zero as a ring
 * (PaintSummon.paintAds); what each painter has to pass is the real reader,
 * and CampSkillTest's first case holds every one of them to it.
 */
object PaintCamp {
    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    const val W = Paint.W
    const val H = Paint.H

    // ------------------------------------------------------------------------
    // The colours, each inside the range its reader measures
    // ------------------------------------------------------------------------
    val DIM = Scalar(35.0, 30.0, 30.0)          // the dimmed map around a window
    val BODY = Scalar(70.0, 45.0, 30.0)         // a window's own dark blue, in no range below
    val TITLE = Paint.hsv(101, 200, 240)        // Camp.TITLE_BLUE
    val ROOF = Paint.hsv(16, 175, 240)          // Camp.ROOF_ORANGE
    val PLATE = Paint.hsv(101, 216, 244)        // Camp.PLATE_BLUE
    val BLUE = Paint.hsv(100, 255, 209)         // Dungeon.BLUE: Support, the Info tab, Check-In lit, the squares
    val GREY = Paint.hsv(109, 94, 136)          // Camp.GREY: Check-In Complete
    val TAB_DARK = Paint.hsv(105, 120, 110)     // Member List, Manage: unlit, in no range
    val PINK = Paint.hsv(163, 232, 255)         // Missions.BADGE_PINK: the "!"
    val YELLOW = Paint.hsv(26, 213, 255)        // Runner.CLAIM_YELLOW: Support All lit
    val KHAKI = Paint.hsv(25, 75, 146)          // Camp.KHAKI: Support All dim
    val CARD = Paint.hsv(100, 60, 180)          // a card for sale, around its picture
    val CARD_SOLD = Paint.hsv(100, 40, 90)      // a sold-out card, dimmed
    val PLATE_WHITE = Scalar(250.0, 250.0, 250.0)
    val PLATE_GREY = Scalar(72.0, 72.0, 72.0)
    val PRICE_DARK = Scalar(40.0, 40.0, 40.0)   // Camp.PRICE_DARK
    val PRICE_PINK = Paint.hsv(165, 255, 255)   // Camp.PRICE_PINK
    val THUMB = Paint.hsv(102, 16, 244)         // Camp.THUMB_PALE
    val TURQUOISE = Paint.hsv(91, 255, 199)     // Camp.TURQUOISE: the price button
    val COIN = Paint.hsv(18, 230, 240)          // Camp.COIN_ORANGE, outside both reds
    val RED = Paint.hsv(0, 255, 247)            // Camp.RED_LOW: the price without the coins
    val WHITE = Scalar(255.0, 255.0, 255.0)
    val BANNER = Paint.hsv(103, 209, 236)       // CampStation.BANNER
    val TEAL_LIT = Paint.hsv(91, 255, 199)      // CampStation.AUTO_TEAL, V 199 lit
    val TEAL_DIM = Paint.hsv(91, 255, 138)      // ... V 138 dim
    val ATTEMPT_LIT = Paint.hsv(100, 255, 209)  // CampStation.ATTEMPT_BLUE, V 209 lit
    val ATTEMPT_DIM = Paint.hsv(100, 255, 145)  // ... V 145 dim
    val BROWN = Paint.hsv(12, 160, 90)          // CampStation.PICTURE_BROWN: the Dojo's walls
    val SKY = Paint.hsv(15, 150, 230)           // the Drill Site's sky, too bright for brown
    val PILL = Paint.hsv(100, 60, 200)          // the Reward Boxes and Ranking pills
    val TOTAL = Paint.hsv(99, 255, 232)         // CampStation.TOTAL_BLUE
    val BAR = Paint.hsv(101, 230, 200)          // CampStation.BAR_BLUE
    val GRASS = Paint.hsv(60, 90, 110)          // the map

    // ------------------------------------------------------------------------
    // The places, as C1 and C2 measured them at 1080 x 1920
    // ------------------------------------------------------------------------
    const val GLOBE_FX = 0.481
    const val GLOBE_FY = 0.945
    /** The nav bar's seven labels by their distance from the globe (Explore.kt's table). */
    val NAV_DX = doubleArrayOf(-0.340, -0.235, -0.118, Camp.NAV_CAMP_DX, 0.235, 0.335)
    /** The tent's roof: a gable from fx0 0.555, fy0 0.173, 0.27 wide and 0.128 tall. */
    val ROOF_BOX = doubleArrayOf(0.555, 0.173, 0.825, 0.301)
    // Map places a tap must never meet (3.6): Camp Wars' gate and an avatar.
    const val WARS_FX = 0.704
    const val WARS_FY = 0.833
    const val AVATAR_FX = 0.48
    const val AVATAR_FY = 0.62
    // The tent: the Info tab, Support, Check-In, the two other tabs.
    const val TAB_FX = 0.252
    const val TAB_FY = 0.214
    val TAB_OTHER_FX = doubleArrayOf(0.476, 0.700)
    const val TENT_BUTTON_W = 0.2214
    const val TENT_BUTTON_H = 0.0394
    const val SUPPORT_FX = 0.3514
    const val CHECKIN_FX = 0.6007
    const val TENT_BUTTON_FY = 0.8328
    const val NOTICE_FY = 0.55
    // The Camp Support window: Support All, and the members' own Support.
    const val SUPPORT_ALL_FX = 0.4760
    const val SUPPORT_ALL_FY = 0.7740
    val MEMBER_FY = doubleArrayOf(0.36, 0.46, 0.56)
    const val MEMBER_FX = 0.70
    // The shop: three columns, three rows in sight, the scrollbar.
    val CARD_FX0 = doubleArrayOf(0.1787, 0.3862, 0.5937)
    const val CARD_PLATE_W = 0.1840
    val ROW_FY_TOP = doubleArrayOf(0.4308, 0.6197, 0.8091)
    val ROW_FY_BOTTOM = doubleArrayOf(0.4227, 0.6116, 0.8010)
    const val FILTER_FX = 0.74
    const val FILTER_FY = 0.225
    // The purchase dialog: the four squares, the count between them, the price button.
    val SQUARE_FX = doubleArrayOf(0.27, 0.38, 0.58, 0.69)
    val SQUARE_W = doubleArrayOf(0.07, 0.05, 0.05, 0.07)
    const val SQUARE_FY = 0.591
    const val BUY_FX = 0.4760
    const val BUY_FY = 0.6682
    const val BUY_W = 0.2441
    const val BUY_H = 0.0485
    // The dialog of an item with one piece left: no row, its price button higher (Camp.SINGLE_PLATE_FY).
    const val SINGLE_BUY_FY = 0.6427
    // A station: the banner, the picture, the counter, the two buttons, the pills.
    val AUTO_BOX = doubleArrayOf(0.2119, 0.6641, 0.4621, 0.7182)
    val ATTEMPT_BOX = doubleArrayOf(0.4935, 0.6662, 0.7376, 0.7152)
    const val PAIR_CY = 0.6911
    const val COUNTER_FX = 0.4778
    const val BOXES_PILL_FX = 0.318
    const val RANKING_PILL_FX = 0.630
    const val PILL_FY = 0.548
    const val OWN_ROW_FY = 0.49
    // Reward Boxes: the Current Total band, the chests, Claim Rewards.
    val TOTAL_BOX = doubleArrayOf(0.1561, 0.5662, 0.7960, 0.6338)
    val CLAIM_BOX = doubleArrayOf(0.3731, 0.7702, 0.5789, 0.8020)
    // Results: the two bars and Close.
    val CLOSE_BOX = doubleArrayOf(0.3758, 0.7763, 0.5772, 0.8162)

    fun box(img: Mat, fx0: Double, fy0: Double, fx1: Double, fy1: Double, colour: Scalar) {
        Imgproc.rectangle(img, Point(fx0 * W, fy0 * H), Point(fx1 * W, fy1 * H), colour, -1)
    }

    private fun box(img: Mat, b: DoubleArray, colour: Scalar) = box(img, b[0], b[1], b[2], b[3], colour)

    private fun centred(img: Mat, fx: Double, fy: Double, fw: Double, fh: Double, colour: Scalar) =
        box(img, fx - fw / 2, fy - fh / 2, fx + fw / 2, fy + fh / 2, colour)

    private fun window(fy0: Double, fy1: Double): Mat {
        val img = Mat(H, W, CvType.CV_8UC3, DIM)
        box(img, 0.12, fy0, 0.88, fy1, BODY)
        return img
    }

    private fun plate(img: Mat, fy: Double) = centred(img, 0.476, fy, 0.58, 0.04, PLATE)

    // ------------------------------------------------------------------------
    // Digits
    // ------------------------------------------------------------------------
    private const val FONT = Imgproc.FONT_HERSHEY_SIMPLEX

    /**
     * "[n]/[m]" (or [n] alone where [m] is null) centred on ([fx], [fy]), its
     * digits [h] pixels tall, in [colour]: the slash a third again as tall,
     * drawn as a line, and a zero as a ring -- OpenCV's zero has two holes
     * at this size, and readDigit reads two holes as an 8 (PaintSummon).
     */
    fun number(img: Mat, fx: Double, fy: Double, n: Int, m: Int?, colour: Scalar, h: Double = 16.0) {
        val scale = h / Imgproc.getTextSize("2", FONT, 1.0, 2, IntArray(1)).height
        val dw = Imgproc.getTextSize("2", FONT, scale, 2, IntArray(1)).width
        val gap = 0.45 * dw
        val slashW = 0.42 * h
        val left = n.toString()
        val right = m?.toString() ?: ""
        val total = left.length * (dw + gap) + (if (m != null) slashW + gap + right.length * (dw + gap) else 0.0) - gap
        var x = fx * W - total / 2
        val base = fy * H + h / 2
        fun digit(ch: Char) {
            if (ch == '0') {
                val stroke = maxOf(2, (0.18 * h).toInt())
                Imgproc.ellipse(img, Point(x + dw / 2, base - h / 2), Size(0.30 * h - stroke / 2.0, (0.92 * h - stroke) / 2),
                                0.0, 0.0, 360.0, colour, stroke)
            } else {
                Imgproc.putText(img, ch.toString(), Point(x, base), FONT, scale, colour, 2)
            }
            x += dw + gap
        }
        for (ch in left) digit(ch)
        if (m == null) return
        val slashH = 1.35 * h
        Imgproc.line(img, Point(x + slashW, base - slashH + 0.15 * h), Point(x, base + 0.15 * h), colour,
                     maxOf(2, (0.14 * h).toInt()))
        x += slashW + gap
        for (ch in right) digit(ch)
    }

    // ------------------------------------------------------------------------
    // The main screen and the Camp screen
    // ------------------------------------------------------------------------
    /** The globe and the seven labels of the nav bar, the Camp label among them where [camp]. */
    fun navBar(img: Mat, camp: Boolean = true) {
        PaintDungeon.paintHome(img)
        for (dx in NAV_DX) {
            if (!camp && dx == Camp.NAV_CAMP_DX) continue
            centred(img, GLOBE_FX + dx, 0.968, 0.05, 0.010, WHITE)
        }
    }

    /** The plain main screen (Paint's auto button) with its nav bar; without [camp], the bar of before 1.5.0. */
    fun main(camp: Boolean = true): Mat {
        val img = Paint.mainScreen()
        navBar(img, camp)
        return img
    }

    /** The Camp screen: the title bar, the map with the tent's roof, the nav bar undimmed. */
    fun campScreen(roof: Boolean = true): Mat {
        val img = Mat(H, W, CvType.CV_8UC3, GRASS)
        box(img, 0.0, 0.100, 1.0, 0.136, TITLE)
        if (roof) {
            val r = ROOF_BOX
            val gable = MatOfPoint(Point(r[0] * W, r[3] * H), Point((r[0] + r[2]) / 2 * W, r[1] * H),
                                   Point(r[2] * W, r[3] * H))
            Imgproc.fillPoly(img, listOf(gable), ROOF)
        }
        box(img, 0.0, 0.90, 1.0, 1.0, Scalar(60.0, 40.0, 25.0))
        navBar(img)
        return img
    }

    // ------------------------------------------------------------------------
    // The tent and the Camp Support window
    // ------------------------------------------------------------------------
    /** The Camp Tent on Info: Support with the "!" where [badge], Check-In lit or complete. */
    fun tent(badge: Boolean, checkIn: Boolean): Mat {
        val img = window(0.13, 0.88)
        plate(img, Camp.TENT_PLATE_FY)
        centred(img, TAB_FX, TAB_FY, 0.2214, 0.0328, BLUE)
        for (fx in TAB_OTHER_FX) centred(img, fx, TAB_FY, 0.2214, 0.0328, TAB_DARK)
        centred(img, 0.476, NOTICE_FY, 0.66, 0.16, Paint.hsv(100, 40, 70))
        centred(img, SUPPORT_FX, TENT_BUTTON_FY, TENT_BUTTON_W, TENT_BUTTON_H, BLUE)
        centred(img, CHECKIN_FX, TENT_BUTTON_FY, TENT_BUTTON_W, TENT_BUTTON_H, if (checkIn) BLUE else GREY)
        if (badge) {
            Imgproc.circle(img, Point((SUPPORT_FX + TENT_BUTTON_W / 2) * W, (TENT_BUTTON_FY - TENT_BUTTON_H / 2) * H),
                           (0.012 * W).toInt(), PINK, -1)
        }
        return img
    }

    /** The Camp Support window: its plate, the members' Support buttons, Support All yellow or khaki. */
    fun support(lit: Boolean): Mat {
        val img = window(0.18, 0.84)
        plate(img, Camp.SUPPORT_PLATE_FY)
        if (lit) for (fy in MEMBER_FY) centred(img, MEMBER_FX, fy, 0.15, 0.03, BLUE)
        centred(img, SUPPORT_ALL_FX, SUPPORT_ALL_FY, 0.2371, 0.0439, if (lit) YELLOW else KHAKI)
        return img
    }

    // ------------------------------------------------------------------------
    // The shop and its purchase dialog
    // ------------------------------------------------------------------------
    /** One card in sight: its item (a template's name, or null for a picture nobody knows), its day, its price. */
    class CardSpec(val item: String?, val left: Int?, val of: Int?, val soldOut: Boolean,
                   val price: Boolean? = null)

    private val templates = HashMap<String, Mat>()

    private fun template(item: String): Mat = templates.getOrPut(item) {
        val bytes = ClassPathAssets.bytes("templates/camp_$item.png") ?: error("templates/camp_$item.png is missing")
        Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
    }

    /**
     * The Camp Shop: its plate, nine cards in sight -- [cards] in the grid's
     * order, the list at its top or its bottom ([atTop]) -- and the
     * scrollbar's thumb at the matching end. [price]: dark where the coins
     * reach, pink where not, none where null.
     */
    fun shop(cards: List<CardSpec>, atTop: Boolean): Mat {
        val img = window(0.15, 0.88)
        plate(img, Camp.SHOP_PLATE_FY)
        centred(img, FILTER_FX, FILTER_FY, 0.10, 0.025, Paint.hsv(100, 30, 60))
        val rows = if (atTop) ROW_FY_TOP else ROW_FY_BOTTOM
        for ((i, c) in cards.take(9).withIndex()) {
            val fx0 = CARD_FX0[i % 3]
            val cy = rows[i / 3]
            val ph = if (c.soldOut) 0.0263 else 0.0242
            val bottom = cy + ph / 2
            val top = bottom - Camp.CARD_H
            val cx = fx0 + CARD_PLATE_W / 2
            box(img, fx0, top, fx0 + CARD_PLATE_W, cy - ph / 2 - 0.002, if (c.soldOut) CARD_SOLD else CARD)
            box(img, fx0, cy - ph / 2, fx0 + CARD_PLATE_W, bottom, if (c.soldOut) PLATE_GREY else PLATE_WHITE)
            // The picture in the icon's box (Camp.ICON_BOX).
            val ix0 = Py.int((cx + Camp.ICON_BOX[0] * CARD_PLATE_W) * W)
            val ix1 = Py.int((cx + Camp.ICON_BOX[1] * CARD_PLATE_W) * W)
            val iy0 = Py.int((top + Camp.ICON_BOX[2]) * H)
            val iy1 = Py.int((top + Camp.ICON_BOX[3]) * H)
            val icon = img.submat(iy0, iy1, ix0, ix1)
            if (c.item != null && !c.soldOut) {
                val small = Mat()
                Imgproc.resize(template(c.item), small, Size((ix1 - ix0).toDouble(), (iy1 - iy0).toDouble()),
                               0.0, 0.0, Imgproc.INTER_AREA)
                small.copyTo(icon)
                small.release()
            } else {
                // The SOLD OUT stamp over a dimmed picture: no template's.
                icon.setTo(Scalar(110.0, 110.0, 110.0))
                Imgproc.circle(icon, Point((ix1 - ix0) / 2.0, (iy1 - iy0) / 2.0), (ix1 - ix0) / 3, Scalar(40.0, 40.0, 160.0), 6)
                Imgproc.line(icon, Point(0.0, 0.0), Point((ix1 - ix0).toDouble(), (iy1 - iy0).toDouble()), Scalar(220.0, 220.0, 220.0), 5)
            }
            icon.release()
            if (c.left != null && c.of != null) {
                number(img, cx, top + (Camp.LEFT_BOX[2] + Camp.LEFT_BOX[3]) / 2, c.left, c.of,
                       if (c.soldOut || c.left == 0) RED else WHITE, h = 14.0)
            }
            // Only a pink price is painted: OpenCV's dark digits on the white
            // plate do not read as the game's do, and a card whose coins
            // reach has its dialog to say so (the skill asks the dialog).
            if (!c.soldOut && c.price == false) {
                number(img, fx0 + 0.66 * CARD_PLATE_W, cy, 12, null, PRICE_PINK, h = 14.0)
            }
        }
        val (t0, t1) = if (atTop) 0.27 to 0.63 else 0.49 to 0.83
        box(img, 0.79, t0, 0.7937, t1, THUMB)
        return img
    }

    /** The purchase dialog: its plate, the four squares, the count, the price button with its coin, red without coins. */
    fun dialog(affordable: Boolean, count: Int = 1): Mat {
        val img = window(0.28, 0.72)
        plate(img, Camp.DIALOG_PLATE_FY)
        for (i in 0 until 4) centred(img, SQUARE_FX[i], SQUARE_FY, SQUARE_W[i], 0.032, BLUE)
        number(img, 0.48, SQUARE_FY, count, null, if (affordable) WHITE else RED, h = 20.0)
        centred(img, BUY_FX, BUY_FY, BUY_W, BUY_H, TURQUOISE)
        Imgproc.circle(img, Point((BUY_FX - BUY_W / 2 + 0.04) * W, BUY_FY * H), (0.016 * W).toInt(), COIN, -1)
        centred(img, BUY_FX + 0.04, BUY_FY, 0.10, 0.02, if (affordable) WHITE else RED)
        return img
    }

    /** The purchase dialog of an item with one piece left: its plate lower, no row, the price button higher. */
    fun singleDialog(affordable: Boolean): Mat {
        val img = window(0.30, 0.67)
        plate(img, Camp.SINGLE_PLATE_FY)
        centred(img, BUY_FX, SINGLE_BUY_FY, BUY_W, BUY_H, TURQUOISE)
        Imgproc.circle(img, Point((BUY_FX - BUY_W / 2 + 0.04) * W, SINGLE_BUY_FY * H), (0.016 * W).toInt(), COIN, -1)
        centred(img, BUY_FX + 0.04, SINGLE_BUY_FY, 0.10, 0.02, if (affordable) WHITE else RED)
        return img
    }

    // ------------------------------------------------------------------------
    // A station, its Reward Boxes, the Results
    // ------------------------------------------------------------------------
    /** A station's window: the banner, its picture, "n/2", Auto Clear and Attempt lit or dim, the two pills. */
    fun station(which: CampStation.Which, n: Int, autoLit: Boolean, attemptLit: Boolean): Mat {
        val img = window(0.25, 0.76)
        centred(img, 0.5, 0.2655 + 0.0164, 0.5754, 0.0328, BANNER)
        val p0 = PAIR_CY + CampStation.PICTURE_DY[0]
        val p1 = PAIR_CY + CampStation.PICTURE_DY[1]
        box(img, CampStation.PICTURE_FX[0], p0, CampStation.PICTURE_FX[1], p1, SKY)
        if (which == CampStation.Which.DOJO) {
            box(img, CampStation.PICTURE_FX[0], p0, CampStation.PICTURE_FX[0] + 0.30, p1, BROWN)
        }
        centred(img, 0.5, OWN_ROW_FY, 0.6181, 0.04, Paint.hsv(100, 60, 120))
        centred(img, BOXES_PILL_FX, PILL_FY, 0.25, 0.045, PILL)
        centred(img, RANKING_PILL_FX, PILL_FY, 0.25, 0.045, PILL)
        box(img, COUNTER_FX - 0.09, PAIR_CY - 0.105, COUNTER_FX + 0.09, PAIR_CY - 0.055, Scalar(25.0, 20.0, 15.0))
        number(img, COUNTER_FX, PAIR_CY - 0.080, n, 2, WHITE, h = 18.0)
        box(img, AUTO_BOX, if (autoLit) TEAL_LIT else TEAL_DIM)
        box(img, ATTEMPT_BOX, if (attemptLit) ATTEMPT_LIT else ATTEMPT_DIM)
        return img
    }

    /** Reward Boxes: the banner, the Current Total band, the two chests' numbers to claim (dim at 0), Claim Rewards. */
    fun boxes(available: Pair<Int, Int>, claimLit: Boolean): Mat {
        val img = window(0.17, 0.84)
        centred(img, 0.5, 0.1867 + 0.0164, 0.5754, 0.0328, BANNER)
        box(img, TOTAL_BOX, TOTAL)
        val row = TOTAL_BOX[1] + CampStation.AVAILABLE_DY + (CampStation.TOTAL_DIGITS_DY[0] + CampStation.TOTAL_DIGITS_DY[1]) / 2
        for ((i, n) in listOf(available.first, available.second).withIndex()) {
            val fx = (CampStation.CHEST_FX[i][0] + CampStation.CHEST_FX[i][1]) / 2
            if (n > 0) number(img, fx, row, n, null, WHITE, h = 14.0)
            else number(img, fx, row, 0, null, Scalar(150.0, 150.0, 150.0), h = 14.0)
        }
        box(img, CLAIM_BOX, if (claimLit) ATTEMPT_LIT else ATTEMPT_DIM)
        return img
    }

    /** The Results an Attempt ends in: the banner, Damage Record and Rank on two bars, Close lit. */
    fun results(): Mat {
        val img = window(0.14, 0.84)
        centred(img, 0.5, 0.1596 + 0.0187, 0.5336, 0.0374, BANNER)
        box(img, 0.18, 0.2419, 0.8147, 0.2783, BAR)
        box(img, 0.18, 0.3571, 0.8147, 0.3935, BAR)
        box(img, CLOSE_BOX, ATTEMPT_LIT)
        return img
    }

    /** Where a tap at ([fx], [fy]) lands against a centred box. */
    fun inside(fx: Double, fy: Double, cx: Double, cy: Double, w: Double, h: Double) =
        abs(fx - cx) <= w / 2 && abs(fy - cy) <= h / 2
}

/**
 * The game, as far as the Camp task meets it, answering every tap the way C1
 * and C2 saw the game answer on 2026-10-19 (PLAN_CAMP.md 6): the Camp tab
 * opens the Camp screen; the tent, the shop and the two signs open their
 * windows, and a tap in the dimmed field above a window closes it, one layer a
 * tap; Support opens the Camp Support window and Support All raises the Reward
 * sheet, after which it is dim and the tent has lost its "!"; Check-In (C4's
 * to measure) raises the sheet and goes grey; a card opens its purchase
 * dialog, whose price button raises the sheet and takes one off the card's day
 * -- a sold-out card opens nothing; a lit Auto Clear spends an attempt and
 * raises the sheet, a dim one does nothing; Attempt plays its battle for 42 s
 * and ends in the Results, whose Close comes back through "Now Loading" with
 * one attempt less and the station's record; Claim Rewards raises the sheet.
 * Every tap is written down by what it hit, so a test can say what was never
 * tapped (3.6).
 */
class CampWorld : Capture {
    enum class Screen { MAIN, CAMP, TENT, SUPPORT, SHOP, DIALOG, STATION, BOXES, SHEET, BLACK, BATTLE, RESULTS,
                        LOADING, GET_REWARD, OPENING }

    /** One card of the shop: its template's name, today's count and limit (null: none), its price. */
    class Item(val name: String, var left: Int?, val of: Int?, val price: Int)

    /** The world's clock: the skill's sleep and the director's beat both move it. */
    var t = 0.0
    /** What a look costs on the world's clock: 1.26 s at most on the instance (the director's "screen: camp_shop"). */
    var lookCost = 0.0
    var screen = Screen.MAIN
    /** The main switch, as the skill and the director ask it. */
    var on = true

    // What the game holds.
    var supportOpen = true          // somebody to support: the "!" on the tent, Support All yellow
    var checkInOpen = true          // Check-In lit
    var supportTakes = true         // Support All does what it says
    var checkInTakes = true
    var checkInSheet = true         // Check-In raises the Reward sheet (C4 sees whether it does)
    var coins = 100_000
    var cardPrices = true           // the cards show their price, dark or pink
    var dialogLies = false          // the dialog says red although the card said dark
    var buyTakes = true
    var tabOnMain = true            // the Camp tab reads on the main screen
    var roof = true                 // the Camp screen reads (its roof drawn)
    var getRewardOnOpen = false     // question 10: a window of the game's over the Camp as it opens
    val stock: MutableList<Item> = mutableListOf(
        Item("gate_blue", 1, 1, 400), Item("gate_pink", 1, 1, 400), Item("gate_yellow", 1, 1, 1000),
        Item("gate_purple", 1, 1, 400), Item("gate_green", 1, 1, 400),
        Item("battle_pass", 2, 2, 500), Item("grand_prix_pass", 2, 2, 500), Item("acceleration_ticket", null, null, 250),
        Item("digivice", 1, 1, 3500), Item("d3", 1, 1, 3500), Item("d_ark", 1, 1, 3500), Item("d_scanner", 1, 1, 7000),
        Item("digivice_ic", 1, 1, 7000), Item("xros_loader", 1, 1, 10500), Item("digivice_2", 1, 1, 10500))
    var atTop = false               // the list remembers where it was left (C1, 6): at its bottom here
    val attempts = mutableMapOf(CampStation.Which.DOJO to 2, CampStation.Which.DRILL to 2)
    /** A station with a record: its Auto Clear lights while attempts are left (C2). */
    val record = mutableMapOf(CampStation.Which.DOJO to true, CampStation.Which.DRILL to true)
    var clearTakes = true
    /** An Attempt gives its station a record, and Auto Clear lights (C2); false is the case that never was. */
    var recordAfterAttempt = true
    /** The two chests' numbers to claim at each station. */
    val available = mutableMapOf(CampStation.Which.DOJO to (0 to 0), CampStation.Which.DRILL to (0 to 0))
    /** Called after every tap that changed the game, with the world: a test's hand in the middle of a pass. */
    var afterTap: CampWorld.(String) -> Unit = {}

    // How long the game takes (PLAN_CAMP.md 6).
    var campDelay = 1.5
    var windowDelay = 0.8
    var sheetDelay = 1.2
    var backDelay = 0.6
    var closeDelay = 0.4
    var battle = 42.0
    var loading = 3.5

    // What happened.
    val taps = ArrayList<String>()
    val swipes = ArrayList<String>()
    var backs = 0
    var station: CampStation.Which? = null
    var dialogItem: Int? = null
    val bought = ArrayList<String>()
    var supported = 0
    var checkedIn = 0
    var autoClears = 0
    val attemptsPlayed = ArrayList<CampStation.Which>()
    var claimed = 0
    private var under = Screen.CAMP
    private var phase = 0

    private class Event(val at: Double, val f: () -> Unit)
    private val pending = ArrayList<Event>()

    private fun later(delay: Double, f: () -> Unit) {
        pending += Event(t + delay, f)
    }

    private fun settle() {
        while (true) {
            val e = pending.filter { t >= it.at }.minByOrNull { it.at } ?: return
            pending.remove(e)
            e.f()
        }
    }

    /** The world's pending changes, the way a test waits for them: until nothing is pending. */
    val busy: Boolean get() = pending.isNotEmpty()

    private fun sheetOver(back: Screen) {
        under = back
        later(sheetDelay) { screen = Screen.SHEET }
    }

    fun cards(): List<PaintCamp.CardSpec> {
        val inSight = if (atTop) stock.subList(0, 9) else stock.subList(6, 15)
        return inSight.map {
            val sold = it.left == 0
            PaintCamp.CardSpec(if (sold) null else it.name, it.left, it.of, sold,
                               if (cardPrices) coins >= it.price else null)
        }
    }

    fun frame(): Mat = when (screen) {
        Screen.MAIN -> PaintCamp.main(tabOnMain)
        Screen.OPENING -> Mat(PaintCamp.H, PaintCamp.W, org.opencv.core.CvType.CV_8UC3, PaintCamp.GRASS)
        Screen.CAMP -> PaintCamp.campScreen(roof)
        Screen.TENT -> PaintCamp.tent(supportOpen, checkInOpen)
        Screen.SUPPORT -> PaintCamp.support(supportOpen)
        Screen.SHOP -> PaintCamp.shop(cards(), atTop)
        Screen.DIALOG -> {
            val it = stock[dialogItem!!]
            // The game's own: one piece left (a Gate at 1/1) is the dialog without a row.
            if (it.left == 1) PaintCamp.singleDialog(!dialogLies && coins >= it.price)
            else PaintCamp.dialog(!dialogLies && coins >= it.price)
        }
        Screen.STATION -> station!!.let { s ->
            val n = attempts.getValue(s)
            PaintCamp.station(s, n, n > 0 && record.getValue(s), n > 0)
        }
        Screen.BOXES -> station!!.let { s ->
            val a = available.getValue(s)
            PaintCamp.boxes(a, a.first + a.second > 0)
        }
        Screen.SHEET -> PaintMissions.rewardSheet()
        Screen.BLACK -> Paint.blank(4.0)
        Screen.BATTLE -> Paint.dungeonBattle(phase++)
        Screen.RESULTS -> PaintCamp.results()
        Screen.LOADING -> Paint.blank(20.0)
        Screen.GET_REWARD -> PaintDungeon.dailyPanel()
    }

    override fun grab(): Mat {
        t += lookCost
        settle()
        return frame()
    }

    override fun tap(x: Int, y: Int) {
        settle()
        val fx = x / PaintCamp.W.toDouble()
        val fy = y / PaintCamp.H.toDouble()
        val what = if (!on) "while off" else when (screen) {
            Screen.MAIN -> mainTap(fx, fy)
            Screen.CAMP -> campTap(fx, fy)
            Screen.TENT -> tentTap(fx, fy)
            Screen.SUPPORT -> supportTap(fx, fy)
            Screen.SHOP -> shopTap(fx, fy)
            Screen.DIALOG -> dialogTap(fx, fy)
            Screen.STATION -> stationTap(fx, fy)
            Screen.BOXES -> boxesTap(fx, fy)
            Screen.SHEET -> {
                later(backDelay) { screen = under }
                if (PaintCamp.inside(fx, fy, 0.475, 0.820, 0.20, 0.04)) "sheet" else "sheet elsewhere"
            }
            Screen.RESULTS -> if (PaintCamp.inside(fx, fy, 0.4765, 0.79625, 0.2014, 0.0399)) {
                screen = Screen.LOADING
                later(loading) { screen = Screen.STATION }
                "results close"
            } else "results"
            Screen.BLACK, Screen.BATTLE, Screen.LOADING, Screen.OPENING -> "battle"
            Screen.GET_REWARD -> "get reward"
        }
        taps += what
        afterTap(what)
    }

    private fun mainTap(fx: Double, fy: Double): String {
        if (tabOnMain && PaintCamp.inside(fx, fy, PaintCamp.GLOBE_FX + Camp.NAV_CAMP_DX, 0.955, 0.08, 0.06)) {
            screen = Screen.OPENING
            later(campDelay) { screen = if (getRewardOnOpen) Screen.GET_REWARD else Screen.CAMP }
            return "camp tab"
        }
        return "main"
    }

    private fun near(fx: Double, fy: Double, t: Explore.Target) = abs(fx - t.fx) < 0.06 && abs(fy - t.fy) < 0.04

    private fun open(s: Screen, what: String): String {
        later(windowDelay) { screen = s }
        return what
    }

    private fun campTap(fx: Double, fy: Double): String = when {
        near(fx, fy, Camp.TENT) -> open(Screen.TENT, "tent")
        near(fx, fy, Camp.SHOP) -> open(Screen.SHOP, "shop")
        near(fx, fy, Camp.DRILL) -> { station = CampStation.Which.DRILL; open(Screen.STATION, "drill") }
        near(fx, fy, Camp.DOJO) -> { station = CampStation.Which.DOJO; open(Screen.STATION, "dojo") }
        abs(fx - PaintCamp.WARS_FX) < 0.08 && abs(fy - PaintCamp.WARS_FY) < 0.05 -> "camp wars"
        abs(fx - PaintCamp.AVATAR_FX) < 0.04 && abs(fy - PaintCamp.AVATAR_FY) < 0.04 -> "avatar"
        PaintCamp.inside(fx, fy, PaintCamp.GLOBE_FX, PaintCamp.GLOBE_FY, 0.08, 0.05) -> {
            later(backDelay) { screen = Screen.MAIN }
            "globe"
        }
        fy < 0.14 -> "camp title"
        else -> "map"
    }

    /** The dimmed field over a window ([Camp.CLOSE_SPOT]): one layer closed onto [to]. */
    private fun closes(fy: Double, top: Double, to: Screen): Boolean {
        if (fy >= top) return false
        later(closeDelay) { screen = to }
        return true
    }

    private fun tentTap(fx: Double, fy: Double): String {
        if (closes(fy, 0.13, Screen.CAMP)) return "close tent"
        val w = PaintCamp.TENT_BUTTON_W
        val h = PaintCamp.TENT_BUTTON_H
        if (PaintCamp.inside(fx, fy, PaintCamp.SUPPORT_FX, PaintCamp.TENT_BUTTON_FY, w, h)) {
            open(Screen.SUPPORT, "")
            return if (supportOpen) "support" else "support without its !"
        }
        if (PaintCamp.inside(fx, fy, PaintCamp.CHECKIN_FX, PaintCamp.TENT_BUTTON_FY, w, h)) {
            if (!checkInOpen) return "grey check-in"
            if (checkInTakes) {
                checkInOpen = false
                checkedIn += 1
                if (checkInSheet) sheetOver(Screen.TENT)
            }
            return "check-in"
        }
        if (abs(fy - PaintCamp.TAB_FY) < 0.025) {
            return when {
                abs(fx - PaintCamp.TAB_FX) < 0.11 -> "info tab"
                abs(fx - PaintCamp.TAB_OTHER_FX[0]) < 0.11 -> "member list"
                else -> "manage"
            }
        }
        if (fy < 0.17 && fx > 0.70) return "info i"
        if (abs(fy - PaintCamp.NOTICE_FY) < 0.08) return "notice"
        return "tent window"
    }

    private fun supportTap(fx: Double, fy: Double): String {
        if (closes(fy, 0.18, Screen.TENT)) return "close support"
        if (PaintCamp.inside(fx, fy, PaintCamp.SUPPORT_ALL_FX, PaintCamp.SUPPORT_ALL_FY, 0.2371, 0.0439)) {
            if (!supportOpen) return "dim support all"
            if (supportTakes) {
                supportOpen = false
                supported += 1
                sheetOver(Screen.SUPPORT)
            }
            return "support all"
        }
        if (PaintCamp.MEMBER_FY.any { abs(fy - it) < 0.02 } && abs(fx - PaintCamp.MEMBER_FX) < 0.08) return "member support"
        return "support window"
    }

    private fun shopTap(fx: Double, fy: Double): String {
        if (closes(fy, 0.15, Screen.CAMP)) return "close shop"
        if (PaintCamp.inside(fx, fy, PaintCamp.FILTER_FX, PaintCamp.FILTER_FY, 0.12, 0.04)) return "filter"
        val rows = if (atTop) PaintCamp.ROW_FY_TOP else PaintCamp.ROW_FY_BOTTOM
        for (i in 0 until 9) {
            val cx = PaintCamp.CARD_FX0[i % 3] + PaintCamp.CARD_PLATE_W / 2
            val bottom = rows[i / 3] + 0.0125
            val cy = bottom - Camp.CARD_H / 2
            if (PaintCamp.inside(fx, fy, cx, cy, PaintCamp.CARD_PLATE_W, Camp.CARD_H)) {
                val k = i + if (atTop) 0 else 6
                val item = stock[k]
                if (item.left == 0) return "sold out card"
                dialogItem = k
                open(Screen.DIALOG, "")
                return "card ${item.name}"
            }
        }
        return "shop window"
    }

    private fun dialogTap(fx: Double, fy: Double): String {
        if (closes(fy, 0.28, Screen.SHOP)) return "close dialog"
        val names = listOf("minus10", "minus", "plus", "plus10")
        for (i in 0 until 4) {
            if (PaintCamp.inside(fx, fy, PaintCamp.SQUARE_FX[i], PaintCamp.SQUARE_FY, PaintCamp.SQUARE_W[i], 0.032)) return names[i]
        }
        val single = stock[dialogItem!!].left == 1
        val buyFy = if (single) PaintCamp.SINGLE_BUY_FY else PaintCamp.BUY_FY
        if (PaintCamp.inside(fx, fy, PaintCamp.BUY_FX, buyFy, PaintCamp.BUY_W, PaintCamp.BUY_H)) {
            val item = stock[dialogItem!!]
            if (dialogLies || coins < item.price) return "red buy ${item.name}"
            if (buyTakes) {
                coins -= item.price
                item.left = item.left?.let { it - 1 }
                bought += item.name
                // The dialog goes at once, and the sheet comes over the shop.
                screen = Screen.SHOP
                sheetOver(Screen.SHOP)
            }
            return "buy ${item.name}"
        }
        return "dialog window"
    }

    private fun stationTap(fx: Double, fy: Double): String {
        val s = station!!
        val n = attempts.getValue(s)
        if (closes(fy, 0.25, Screen.CAMP)) return "close station"
        val a = PaintCamp.AUTO_BOX
        if (fx in a[0]..a[2] && fy in a[1]..a[3]) {
            if (n == 0 || !record.getValue(s)) return "dim auto clear"
            if (clearTakes) {
                attempts[s] = n - 1
                autoClears += 1
                sheetOver(Screen.STATION)
            }
            return "auto clear ${s.name.lowercase()}"
        }
        val b = PaintCamp.ATTEMPT_BOX
        if (fx in b[0]..b[2] && fy in b[1]..b[3]) {
            if (n == 0) return "dim attempt"
            val lit = record.getValue(s)
            screen = Screen.BLACK
            later(1.0) { screen = Screen.BATTLE }
            later(1.0 + battle) {
                screen = Screen.RESULTS
                attempts[s] = n - 1
                if (recordAfterAttempt) record[s] = true
                attemptsPlayed += s
            }
            return if (lit) "attempt ${s.name.lowercase()} with auto clear lit" else "attempt ${s.name.lowercase()}"
        }
        if (PaintCamp.inside(fx, fy, PaintCamp.BOXES_PILL_FX, PaintCamp.PILL_FY, 0.25, 0.045)) {
            return open(Screen.BOXES, "reward boxes")
        }
        if (PaintCamp.inside(fx, fy, PaintCamp.RANKING_PILL_FX, PaintCamp.PILL_FY, 0.25, 0.045)) return "ranking"
        if (abs(fy - PaintCamp.OWN_ROW_FY) < 0.025) return "own row"
        return "station window"
    }

    private fun boxesTap(fx: Double, fy: Double): String {
        val s = station!!
        if (closes(fy, 0.17, Screen.STATION)) return "close boxes"
        val c = PaintCamp.CLAIM_BOX
        if (fx in c[0]..c[2] && fy in c[1]..c[3]) {
            val a = available.getValue(s)
            if (a.first + a.second == 0) return "dim claim"
            claimed += a.first + a.second
            available[s] = 0 to 0
            sheetOver(Screen.BOXES)
            return "claim"
        }
        return "boxes window"
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {
        settle()
        if (!on || screen != Screen.SHOP) {
            taps += "swipe off the shop"
            return
        }
        atTop = y2 > y1
        swipes += if (atTop) "to top" else "to bottom"
    }

    override fun back() {
        backs += 1
        taps += "back"
    }

    override fun inFront(): String = GAME

    companion object {
        const val GAME = "com.bandainamcoent.dgup_ww"

        /** What a tap may hit and never does (PLAN_CAMP.md 3.6), or that does nothing where it lands. */
        val FORBIDDEN = setOf("camp wars", "avatar", "camp title", "map", "member list", "manage", "info tab",
                              "info i", "notice", "tent window", "support without its !", "grey check-in",
                              "dim support all", "member support", "support window", "filter", "sold out card",
                              "shop window", "minus10", "minus", "plus", "plus10", "dialog window",
                              "dim auto clear", "dim attempt", "ranking", "own row", "station window",
                              "dim claim", "boxes window", "results", "battle", "get reward", "sheet elsewhere",
                              "main", "while off", "back", "swipe off the shop")
    }
}
