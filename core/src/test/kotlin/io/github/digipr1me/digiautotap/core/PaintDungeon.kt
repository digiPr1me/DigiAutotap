package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The frames test_dungeon_flow.py paints that [Paint] does not: the party
 * panel, the loading panel, and the nav bar with its globe. Its own file so
 * that two skill sessions painting at the same time do not both edit
 * Paint.kt (PLAN_ANDROID_APP.md 4, the three rules).
 *
 * Every one of them is rebuilt from a real frame rather than captured from
 * one, and what each has to pass is the real reader.
 */
object PaintDungeon {
    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    /**
     * Every size the real frames were measured at, plus the two shapes that
     * catch the mistakes: 1080 x 1920 is an ADB frame, where the reference
     * rect starts above and left of the image, and 730 x 1389 is a window of
     * the wrong shape, where LDPlayer letterboxes the game and every fraction
     * slides. A recogniser that has only been run on one of those has not
     * been tested (NOTES.md).
     */
    val HOME_SIZES = listOf(805 to 1390, 765 to 1390, 657 to 1198, 573 to 1056,
                            497 to 914, 1080 to 1920, 730 to 1389)

    fun blank(w: Int, h: Int): Mat = Mat(h, w, CvType.CV_8UC3, Scalar(30.0, 30.0, 30.0))

    private fun rect(img: Mat, x0: Int, y0: Int, x1: Int, y1: Int, colour: Scalar) =
        Imgproc.rectangle(img, Point(x0.toDouble(), y0.toDouble()),
                          Point(x1.toDouble(), y1.toDouble()), colour, -1)

    /**
     * The dungeon dialog with its three party slots.
     *
     * What matters is the thing that fooled the reader: the panel's own
     * bright edge sits just to the right of the third slot, and a crop that
     * reaches it measures contrast that is the panel, not a team-mate.
     */
    fun partyPanel(filled: List<Boolean> = listOf(false, true, false)): Mat {
        val w = Paint.W
        val h = Paint.H
        val img = Mat(h, w, CvType.CV_8UC3, Scalar(18.0, 18.0, 18.0))
        // the panel, with a bright border down its right-hand side
        rect(img, (0.11 * w).toInt(), (0.18 * h).toInt(),
             (0.845 * w).toInt(), (0.83 * h).toInt(), Scalar(90.0, 45.0, 20.0))
        rect(img, (0.815 * w).toInt(), (0.18 * h).toInt(),
             (0.845 * w).toInt(), (0.83 * h).toInt(), Scalar(240.0, 190.0, 90.0))
        for ((i, fx) in Dungeon.PARTY_SLOTS.withIndex()) {
            val x = ((fx - 0.09) * w).toInt()
            val y = ((Dungeon.PARTY_SLOT_Y - 0.05) * h).toInt()
            rect(img, x, y, x + (0.18 * w).toInt(), y + (0.10 * h).toInt(),
                 Scalar(55.0, 30.0, 14.0))
            if (filled[i]) {
                // a figure: bright, and nothing like the flat slot behind it
                Imgproc.circle(img, Point((x + (0.09 * w).toInt()).toDouble(),
                                          (y + (0.05 * h).toInt()).toDouble()),
                               (0.03 * w).toInt(), Scalar(230.0, 230.0, 240.0), -1)
                rect(img, x + (0.05 * w).toInt(), y + (0.06 * h).toInt(),
                     x + (0.13 * w).toInt(), y + (0.09 * h).toInt(),
                     Scalar(60.0, 200.0, 240.0))
            }
        }
        return img
    }

    /**
     * The dungeon panel before its artwork has arrived.
     *
     * Placeholder text, empty slots, and an Attempt button that is narrower
     * than the loaded one -- 0.214 against 0.251, measured -- which is what
     * slipped it inside the exit prompt's width band. Its violet Clear
     * Previous Difficulty sits beside it at the same height, and that pair is
     * what no prompt has.
     */
    fun loadingPanel(): Mat {
        val w = Paint.W
        val h = Paint.H
        val img = Mat(h, w, CvType.CV_8UC3, Scalar(20.0, 20.0, 20.0))
        rect(img, (0.09 * w).toInt(), (0.24 * h).toInt(),
             (0.86 * w).toInt(), (0.80 * h).toInt(), Paint.BODY)
        for ((fx, colour) in listOf(0.358 to Paint.hsv(130, 200, 200),
                                    0.595 to Scalar(230.0, 150.0, 40.0))) {
            rect(img, ((fx - 0.107) * w).toInt(), (0.556 * h).toInt(),
                 ((fx + 0.107) * w).toInt(), (0.592 * h).toInt(), colour)
        }
        // Find a Party, below and centred, as on the real frame
        rect(img, (0.364 * w).toInt(), (0.738 * h).toInt(),
             (0.589 * w).toInt(), (0.774 * h).toInt(), Scalar(230.0, 150.0, 40.0))
        return img
    }

    /**
     * The dungeon list at its top or its bottom: blue cards centred at
     * [Dungeon.CARD_X], 0.77 wide, at the centres and heights the oracle
     * reads on the real frames (DungeonSkillTest.TOP_VIEW, BOTTOM_VIEW).
     * The top view's first card is the banner, 0.208 tall against 0.116;
     * the bottom view has five plain cards. What the painted list has to
     * pass is the real reader, [Dungeon.listAtTop].
     */
    fun list(atTop: Boolean): Mat {
        val w = Paint.W
        val h = Paint.H
        val img = Mat(h, w, CvType.CV_8UC3, Scalar(18.0, 18.0, 18.0))
        val (x0, y0, gw, gh) = Dungeon.gameRect(img)
        val cards = if (atTop) listOf(0.281 to 0.208, 0.471 to 0.116, 0.621 to 0.116, 0.771 to 0.116)
                    else listOf(0.229 to 0.115, 0.378 to 0.116, 0.528 to 0.116, 0.678 to 0.116, 0.828 to 0.115)
        for ((fy, fh) in cards) {
            rect(img, (x0 + (Dungeon.CARD_X - 0.385) * gw).toInt(), (y0 + (fy - fh / 2) * gh).toInt(),
                 (x0 + (Dungeon.CARD_X + 0.385) * gw).toInt(), (y0 + (fy + fh / 2) * gh).toInt(),
                 Paint.hsv(105, 200, 200))
        }
        return img
    }

    /**
     * The wireframe globe, drawn eight times over and shrunk down, as a 0/255
     * mask.
     *
     * Drawn straight at the target size, the stroke is one whole pixel wide
     * or two, and the glyph's fill jumps from 0.27 to 0.62 between two window
     * sizes twenty pixels apart -- an artefact of the drawing, not of the
     * shape, and exactly the sort of thing that makes a painted case pass or
     * fail for a reason the game knows nothing about. Shrinking a big drawing
     * keeps the stroke the same share of the glyph: fill stays at 0.42 to
     * 0.48 over every size, against 0.42 to 0.49 measured on the real button.
     */
    fun globeTile(side: Int): Mat {
        val big = 8 * side
        val tile = Mat.zeros(big, big, CvType.CV_8UC1)
        val c = big / 2
        val g = big / 2 - 1
        val t = maxOf(1, (big * 0.055).toInt())
        val white = Scalar(255.0)
        val centre = Point(c.toDouble(), c.toDouble())
        Imgproc.circle(tile, centre, g, white, t)
        Imgproc.ellipse(tile, centre, Size((g * 0.45).toInt().toDouble(), g.toDouble()),
                        0.0, 0.0, 360.0, white, t)
        Imgproc.line(tile, Point((c - g).toDouble(), c.toDouble()),
                     Point((c + g).toDouble(), c.toDouble()), white, t)
        Imgproc.ellipse(tile, centre, Size(g.toDouble(), (g * 0.52).toInt().toDouble()),
                        0.0, 0.0, 360.0, white, t)
        val small = Mat()
        Imgproc.resize(tile, small, Size(side.toDouble(), side.toDouble()), 0.0, 0.0,
                       Imgproc.INTER_AREA)
        // `small >= 110`, as a mask to paint through.
        val mask = Mat()
        Core.compare(small, Scalar(110.0), mask, Core.CMP_GE)
        tile.release(); small.release()
        return mask
    }

    /**
     * The home button where the game draws it, in the bottom nav bar.
     *
     * [bright] off is the same button with a dialog over it: the game dims
     * what is behind one, and the dimmed glyph leaves the white range
     * entirely -- which is what lets one test answer both "where do I tap"
     * and "is anything covering it".
     */
    fun paintHome(img: Mat, bright: Boolean = true): Mat {
        val r0 = Dungeon.gameRect(img)
        val cx = (r0.x0 + 0.481 * r0.gw).toInt()
        val cy = (r0.y0 + 0.945 * r0.gh).toInt()
        val r = (0.081 * r0.gw / 2).toInt()
        val centre = Point(cx.toDouble(), cy.toDouble())
        Imgproc.circle(img, centre, r,
                       if (bright) Scalar(210.0, 140.0, 60.0) else Scalar(70.0, 60.0, 50.0), -1)
        Imgproc.circle(img, centre, (r * 0.62).toInt(), Scalar(90.0, 40.0, 20.0), -1)
        val side = Py.roundInt(0.0485 * r0.gw)
        val tile = globeTile(side)
        val x = cx - side / 2
        val y = cy - side / 2
        val patch = img.submat(y, y + side, x, x + side)
        patch.setTo(if (bright) Scalar(255.0, 255.0, 255.0) else Scalar(110.0, 110.0, 110.0), tile)
        tile.release()
        return img
    }

    /**
     * A white panel across the bottom, which is what a loading screen and
     * Special Summon standing open over the game both look like down there.
     * It fills the whole crop -- 0.125 wide against the globe's 0.048 -- and
     * is why the width band has a ceiling and not only a floor.
     */
    fun paintWhiteBar(img: Mat): Mat {
        val r = Dungeon.gameRect(img)
        rect(img, maxOf(0, (r.x0 + 0.30 * r.gw).toInt()), (r.y0 + 0.88 * r.gh).toInt(),
             (r.x0 + 0.70 * r.gw).toInt(), (r.y0 + 0.99 * r.gh).toInt(),
             Scalar(250.0, 250.0, 250.0))
        return img
    }
}
