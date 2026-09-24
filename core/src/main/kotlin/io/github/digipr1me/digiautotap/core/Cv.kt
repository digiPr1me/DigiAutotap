package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

/**
 * The OpenCV calls every reader family makes, each spelled the way the
 * Python line it replaces means it. No thresholds live here, only the
 * translation: a reader port says `Cv.share(mask)` where Python says
 * `float(mask.mean()) / 255.0`, and this file is where that is made exact.
 */
internal object Cv {

    /** `cv2.cvtColor(img, cv2.COLOR_BGR2HSV)`, as Dungeon has it. */
    fun hsv(img: Mat): Mat = Dungeon.hsv(img)

    /** `cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)`. */
    fun gray(img: Mat): Mat {
        val out = Mat()
        Imgproc.cvtColor(img, out, Imgproc.COLOR_BGR2GRAY)
        return out
    }

    /** `cv2.inRange(hsv, np.array(lo), np.array(hi))`, as Dungeon has it. */
    fun inRange(hsv: Mat, colour: Dungeon.Hsv): Mat = Dungeon.inRange(hsv, colour)

    /** `cv2.inRange(hsv, lo, hi)` straight off an image, HSV conversion included. */
    fun hsvMask(img: Mat, colour: Dungeon.Hsv): Mat {
        val hsv = hsv(img)
        val mask = inRange(hsv, colour)
        hsv.release()
        return mask
    }

    /**
     * `cv2.connectedComponentsWithStats(mask, 8)`, as Dungeon has it: the
     * count and the stats table, columns x, y, w, h, area. Label 0 is the
     * background, as in Python.
     */
    fun components(mask: Mat): Pair<Int, Array<IntArray>> = Dungeon.components(mask)

    /**
     * `float(mask.mean()) / 255.0` on a 0/255 mask, computed the way numpy
     * does: the sum is exact (255 times the count), divided by the number
     * of pixels, then by 255. Not countNonZero / size, which is the same
     * number mathematically and can differ in the last bit.
     */
    fun share(mask: Mat): Double {
        val total = mask.rows().toLong() * mask.cols()
        return 255.0 * Core.countNonZero(mask) / total / 255.0
    }

    /** numpy's `.std()` of a single-channel patch: population deviation, ddof 0. */
    fun std(patch: Mat): Double {
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(patch, mean, std)
        val out = std.get(0, 0)[0]
        mean.release(); std.release()
        return out
    }
}
