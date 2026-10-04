package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * The frames of the EX Missions task (ExMissionsSkillTest, DirectorTest),
 * painted with the colours and at the places EX1 measured on
 * corpus/missions/ (PLAN_EX_MISSIONS.md 4.1, Missions.kt): the Missions tile
 * on the main screen -- the yellow clip on its pale board inside the navy
 * square --, the window's tab row with one pale and two blue tabs, the EX tab
 * with its header block and its list, each Claim yellow, grey or a blue
 * arrow, Collection's cards with their own yellow Claims in the same column,
 * the Reward sheet, and the Stage Failed banner (Paint.stageFailed). Its own
 * file, as PaintQuest and PaintFarm are.
 *
 * On Paint's window frame of 805 x 1390 game_rect is the whole image, so the
 * three anchors are one and every fraction below is the reader's own.
 */
object PaintMissions {
    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    const val W = Paint.W
    const val H = Paint.H

    // Measured on the frames of EX1 (Missions.kt, "The tile", "The window
    // and its tabs", "The EX Missions tab").
    val CLIP = Paint.hsv(26, 200, 245)          // inside CLIP_YELLOW
    val BOARD = Paint.hsv(100, 40, 235)         // inside BOARD_PALE
    val NAVY = Paint.hsv(110, 220, 90)          // inside Runner.TILE_NAVY
    val TAB_LIT = Paint.hsv(98, 104, 255)
    val TAB_BLUE = Paint.hsv(99, 255, 219)
    val HEADER = Paint.hsv(105, 219, 156)
    val YELLOW = Paint.hsv(26, 213, 255)        // Runner.CLAIM_YELLOW, as measured
    val GREY_CLAIM = Paint.hsv(109, 94, 136)    // the header's Claim before its count
    val ARROW = Paint.hsv(107, 199, 164)        // a row's blue arrow
    val PALE = Paint.hsv(100, 25, 242)          // the list rows, Collection's cards
    val BODY = Scalar(70.0, 45.0, 30.0)         // the window's own dark blue, in no range above
    val DIM = Scalar(35.0, 30.0, 30.0)          // the dimmed field around the window

    // The tile: the clip at fx0 0.777, fy0 0.1507, 0.0277 x 0.0072, as on the
    // 1920 frames; its board under it and the navy square around both.
    val CLIP_BOX = doubleArrayOf(0.777, 0.1507, 0.8047, 0.1579)
    val TILE_NAVY_BOX = doubleArrayOf(0.745, 0.145, 0.838, 0.195)
    val TILE_BOARD_BOX = doubleArrayOf(0.772, 0.154, 0.810, 0.180)

    // The tab row: the three middles, a tab's width, the unlit and lit rows.
    val TAB_FX = doubleArrayOf(0.2559, 0.4760, 0.6962)
    const val TAB_HALF_W = 0.1066
    val TAB_UNLIT_FY = doubleArrayOf(0.7707, 0.8027)
    val TAB_LIT_FY = doubleArrayOf(0.7706, 0.8070)

    // The EX tab: the header block, its Claim, the list's rows.
    val HEADER_BOX = doubleArrayOf(0.162, 0.2072, 0.790, 0.3101)
    const val CLAIM_FX = 0.7045
    const val CLAIM_W = 0.1265
    const val CLAIM_H = 0.0282
    const val HEADER_CLAIM_FY = Missions.HEADER_ROW_FY
    const val LIST_ROWS = 5
    fun listRowFy(row: Int) = Missions.LIST_ROW_FY + (row - 2) * Missions.ROW_PITCH

    // Collection's cards: Claims at fx 0.7005, fy 0.2290 + n x 0.1212.
    const val COLLECTION_CLAIM_FX = 0.7005
    fun collectionFy(n: Int) = 0.2290 + n * 0.1212
    // Daily Missions was not seen (EX1 never changed the tab): rows of its own
    // with a yellow Claim each, only so that a tap on one would show.
    const val DAILY_CLAIM_FX = 0.700
    fun dailyFy(n: Int) = 0.30 + n * 0.11

    fun box(img: Mat, fx0: Double, fy0: Double, fx1: Double, fy1: Double, colour: Scalar) {
        val r = Dungeon.gameRect(img)
        Imgproc.rectangle(img, Point(r.x0 + fx0 * r.gw, r.y0 + fy0 * r.gh),
                          Point(r.x0 + fx1 * r.gw, r.y0 + fy1 * r.gh), colour, -1)
    }

    private fun box(img: Mat, b: DoubleArray, colour: Scalar) = box(img, b[0], b[1], b[2], b[3], colour)

    private fun centred(img: Mat, fx: Double, fy: Double, fw: Double, fh: Double, colour: Scalar) =
        box(img, fx - fw / 2, fy - fh / 2, fx + fw / 2, fy + fh / 2, colour)

    /** The plain main screen (Paint's auto button), with the Missions tile or without it (a banner over the HUD). */
    fun main(tile: Boolean = true): Mat {
        val img = Paint.mainScreen()
        if (tile) {
            box(img, TILE_NAVY_BOX, NAVY)
            box(img, TILE_BOARD_BOX, BOARD)
            box(img, CLIP_BOX, CLIP)
        }
        return img
    }

    /** The window growing out of the tile: its box, no tabs yet (missions_opening_*). */
    fun opening(): Mat {
        val img = Mat(H, W, CvType.CV_8UC3, DIM)
        box(img, 0.20, 0.25, 0.80, 0.75, BODY)
        return img
    }

    /**
     * The Missions window with [tab] lit. On Collection its cards with a
     * yellow Claim each, on Daily Missions rows with one each, on EX
     * Missions -- where [exDrawn] -- the header with its Claim yellow while
     * [r1] and the list's rows 2 to 6 yellow up to [listYellow] rows, blue
     * arrows after them. EX lit and not drawn is the tab on its way.
     */
    fun window(tab: Int, r1: Boolean = false, listYellow: Int = 0, exDrawn: Boolean = true): Mat {
        val img = Mat(H, W, CvType.CV_8UC3, DIM)
        box(img, 0.14, 0.15, 0.86, 0.83, BODY)
        for (i in 0 until 3) {
            val fy = if (i == tab) TAB_LIT_FY else TAB_UNLIT_FY
            box(img, TAB_FX[i] - TAB_HALF_W, fy[0], TAB_FX[i] + TAB_HALF_W, fy[1],
                if (i == tab) TAB_LIT else TAB_BLUE)
        }
        when (tab) {
            Missions.COLLECTION -> for (n in 0 until 4) {
                val fy = collectionFy(n)
                box(img, 0.17, fy - 0.05, 0.83, fy + 0.05, PALE)
                centred(img, COLLECTION_CLAIM_FX, fy, CLAIM_W, CLAIM_H, YELLOW)
            }
            Missions.DAILY -> for (n in 0 until 4) {
                val fy = dailyFy(n)
                box(img, 0.17, fy - 0.045, 0.83, fy + 0.045, PALE)
                centred(img, DAILY_CLAIM_FX, fy, CLAIM_W, CLAIM_H, YELLOW)
            }
            Missions.EX -> if (exDrawn) {
                box(img, HEADER_BOX, HEADER)
                centred(img, CLAIM_FX, HEADER_CLAIM_FY, CLAIM_W, CLAIM_H, if (r1) YELLOW else GREY_CLAIM)
                for (row in 2 until 2 + LIST_ROWS) {
                    val fy = listRowFy(row)
                    box(img, 0.168, fy - 0.04, 0.832, fy + 0.04, PALE)
                    centred(img, CLAIM_FX, fy, CLAIM_W, CLAIM_H, if (row - 1 <= listYellow) YELLOW else ARROW)
                }
            }
        }
        return img
    }

    /** The Reward sheet: its blue band across the middle and "Tap to close" under it (PaintQuest.rewardSheet). */
    fun rewardSheet(): Mat {
        val img = Mat(H, W, CvType.CV_8UC3, DIM)
        centred(img, 0.5, 0.50, 1.0, 0.40, Paint.hsv(108, 220, 150))
        centred(img, 0.475, 0.820, 0.10, 0.020, Scalar(250.0, 250.0, 250.0))
        return img
    }
}

/**
 * The game, as far as the EX Missions task meets it, answering every tap the
 * way EX1 saw the game answer on 2026-09-28/29 (PLAN_EX_MISSIONS.md 4.1): the
 * tile opens the window on Collection after a moment; EX Missions lights its
 * tab and draws the list after another; a yellow Claim claims -- on the list,
 * every claimable row at once -- and raises the Reward sheet a second later;
 * "Tap to close" brings the tab back; a blue arrow closes the window and lands
 * on the main screen (or on Stage Failed); the dimmed field above the window
 * closes it; the neutral spot takes the Stage Failed banner away. Every tap
 * is written down by what it hit, so a test can say what was never tapped.
 */
class MissionsWorld : Capture {
    enum class Screen { MAIN, STAGE_FAILED, OPENING, WINDOW, SHEET, PROMPT }

    /** The world's clock: the skill's sleep and the director's beat both move it. */
    var t = 0.0
    var screen = Screen.MAIN
    var tab = Missions.COLLECTION
    private var exDrawn = true

    /** The main switch, as the skill and the director ask it. */
    var on = true

    // What the game does.
    var tile = true                // the tile reads on the main screen
    var opens = true               // the tile opens the window
    var promptOnTile = false       // the tile raises a prompt nobody here asked for
    var exTabWorks = true          // a tap on EX Missions lights it
    var closes = true              // the dimmed field closes the window
    var r1 = 0                     // Claims row 1 (the header) still has to give
    var r2 = 0                     // Claims the list's first row still has to give, level after level
    var extra = 0                  // rows under it yellow too until the first Claim on the list
    var sheet = true               // a Claim raises the Reward sheet
    var noEffect = 0               // the next this many Claims change nothing
    val jumpOnR2 = HashSet<Int>()  // the n-th tap on row 2 is a tap on its blue arrow after all
    var jumpTo = Screen.MAIN
    var blueAfterArrow = false     // ... and the list reads blue from that tap until the jump lands
    var playerClosesAt = Double.NaN  // the player closes the window at this time, with a tap of their own
    var failedBehind = false       // the window closes onto the Stage Failed banner
    var litUntil = Double.NEGATIVE_INFINITY  // the main screen has the hologram device lit until then (Paint.holoLight)
    /** Called after every Claim that took, with the world: the battle counting on behind the window. */
    var afterClaim: MissionsWorld.() -> Unit = {}

    // How long the game takes (4.1 point 11).
    var openDelay = 0.6
    var exDelay = 0.9
    var sheetDelay = 1.1
    var backDelay = 0.4
    var jumpDelay = 1.5
    var closeDelay = 0.3

    // What happened.
    val taps = ArrayList<String>()
    val overlay = ArrayList<String>()
    var backs = 0
    var grabs = 0
    var claimsMade = 0
    var r1Claims = 0
    private var r2Taps = 0

    private var pending: (() -> Unit)? = null
    private var pendingAt = 0.0
    private var hideYellow = false

    private fun later(delay: Double, f: () -> Unit) {
        pending = f
        pendingAt = t + delay
    }

    private fun settle() {
        if (!playerClosesAt.isNaN() && t >= playerClosesAt && screen == Screen.WINDOW) {
            playerClosesAt = Double.NaN
            pending = null
            screen = Screen.MAIN
            tab = Missions.COLLECTION
        }
        val p = pending ?: return
        if (t >= pendingAt) {
            pending = null
            p()
        }
    }

    /** List rows 2 to 1 + this are yellow. */
    fun listYellow(): Int = if (hideYellow) 0 else (if (r2 > 0) 1 else 0) + extra

    fun frame(): Mat = when (screen) {
        Screen.MAIN -> if (t < litUntil) PaintMissions.main(tile).let { m -> Paint.holoLight(m).also { m.release() } }
                       else PaintMissions.main(tile)
        Screen.STAGE_FAILED -> Paint.stageFailed()
        Screen.OPENING -> PaintMissions.opening()
        Screen.WINDOW -> PaintMissions.window(tab, r1 > 0, listYellow(), exDrawn)
        Screen.SHEET -> PaintMissions.rewardSheet()
        Screen.PROMPT -> Paint.prompt(pink = true)
    }

    override fun grab(): Mat {
        grabs += 1
        settle()
        return frame()
    }

    override fun tap(x: Int, y: Int) {
        settle()
        val img = frame()
        val (fx, fy) = Dungeon.gameRect(img).let { r -> (x - r.x0) / r.gw.toDouble() to (y - r.y0) / r.gh.toDouble() }
        img.release()
        fun near(ax: Double, ay: Double) = abs(fx - ax) < 0.07 && abs(fy - ay) < 0.02
        val what = if (!on) "while off" else when (screen) {
            Screen.MAIN -> if (tile && near(0.791, 0.169)) {
                when {
                    promptOnTile -> screen = Screen.PROMPT
                    opens -> {
                        screen = Screen.OPENING
                        later(openDelay) { screen = Screen.WINDOW; tab = Missions.COLLECTION; exDrawn = true }
                    }
                }
                "tile"
            } else "main"
            Screen.STAGE_FAILED -> if (near(Director.NEUTRAL_TAP_FX, Director.NEUTRAL_TAP_FY)) {
                screen = Screen.MAIN
                "neutral"
            } else "growth guide"
            Screen.OPENING -> "opening"
            Screen.SHEET -> {
                later(backDelay) { screen = Screen.WINDOW }
                if (near(0.475, 0.820)) "sheet" else "sheet elsewhere"
            }
            Screen.PROMPT -> "prompt"
            Screen.WINDOW -> windowTap(fx, fy, ::near)
        }
        taps += what
    }

    private fun windowTap(fx: Double, fy: Double, near: (Double, Double) -> Boolean): String {
        if (fy in 0.765..0.81) {
            return when {
                fx < 0.366 -> { tab = Missions.COLLECTION; "tab collection" }
                fx < 0.586 -> { tab = Missions.DAILY; "tab daily" }
                else -> {
                    if (exTabWorks && tab != Missions.EX) {
                        tab = Missions.EX
                        exDrawn = false
                        later(exDelay) { exDrawn = true }
                    }
                    "tab ex"
                }
            }
        }
        if (fy < 0.15 || fy > 0.83) {
            if (closes) later(closeDelay) {
                screen = if (failedBehind) Screen.STAGE_FAILED else Screen.MAIN
                tab = Missions.COLLECTION
            }
            return "close"
        }
        when (tab) {
            Missions.COLLECTION -> for (n in 0 until 4) {
                if (near(PaintMissions.COLLECTION_CLAIM_FX, PaintMissions.collectionFy(n))) return "collection claim"
            }
            Missions.DAILY -> for (n in 0 until 4) {
                if (near(PaintMissions.DAILY_CLAIM_FX, PaintMissions.dailyFy(n))) return "daily claim"
            }
            Missions.EX -> if (exDrawn) {
                if (near(PaintMissions.CLAIM_FX, PaintMissions.HEADER_CLAIM_FY)) return rowOne()
                for (row in 2 until 2 + PaintMissions.LIST_ROWS) {
                    if (near(PaintMissions.CLAIM_FX, PaintMissions.listRowFy(row))) return listRow(row)
                }
            }
        }
        return "window"
    }

    private fun rowOne(): String {
        if (r1 <= 0) return "grey r1"
        if (noEffect > 0) {
            noEffect -= 1
            return "claim r1 (no effect)"
        }
        r1 -= 1
        r1Claims += 1
        claimed()
        return "claim r1"
    }

    private fun listRow(row: Int): String {
        if (row == 2) r2Taps += 1
        if ((row == 2 && r2Taps in jumpOnR2) || row - 1 > listYellow()) {
            // The blue arrow: the window closes and the game goes where the mission is played.
            if (blueAfterArrow) hideYellow = true
            later(jumpDelay) {
                screen = jumpTo
                tab = Missions.COLLECTION
                hideYellow = false
            }
            return "arrow r$row"
        }
        if (noEffect > 0) {
            noEffect -= 1
            return "claim r$row (no effect)"
        }
        // Every claimable row of the list at once; a row whose next level is
        // reached already comes back yellow at the top.
        if (r2 > 0) r2 -= 1
        extra = 0
        claimed()
        return "claim r$row"
    }

    private fun claimed() {
        claimsMade += 1
        if (sheet) later(sheetDelay) { screen = Screen.SHEET }
        afterClaim()
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) { taps += "swipe" }
    override fun back() { backs += 1; taps += "back" }
    override fun inFront(): String = GAME
    override fun overlayClear(fy0: Double, fy1: Double) {
        overlay += String.format(java.util.Locale.ROOT, "clear %.3f-%.3f", fy0, fy1)
    }
    override fun overlayBack() { overlay += "back" }

    companion object {
        const val GAME = "com.bandainamcoent.dgup_ww"
        /** What a tap in the window may hit and never does (3.6). */
        val FORBIDDEN = setOf("tab collection", "tab daily", "collection claim", "daily claim", "grey r1",
                              "growth guide", "prompt", "main", "window", "while off", "back", "swipe")
    }
}
