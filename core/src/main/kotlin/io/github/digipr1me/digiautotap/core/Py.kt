package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import org.opencv.core.Range

/**
 * The few places where Python and Kotlin mean different things by the same
 * spelling. Every reader port goes through these rather than the Kotlin
 * built-in, so that "line for line" stays true where the languages differ.
 */
object Py {

    /**
     * Python's `int(round(x))`: round half to even. Kotlin's `roundToInt` and
     * `Math.round` round half up, and `game_rect` rounds a window of 1080
     * wide to 1147 either way until the day a frame lands on .5 exactly.
     */
    fun roundInt(x: Double): Int = Math.rint(x).toInt()

    /**
     * Python's `int(x)` on a float: truncation towards zero. Kotlin's
     * `toInt()` does the same, and this exists so that the port says which
     * of the two it meant.
     */
    fun int(x: Double): Int = x.toInt()

    /**
     * numpy's `a[start:stop]` along one axis of length [len]: negative
     * indices count from the end, everything is clamped to the axis, and a
     * stop before the start is an empty slice rather than an error.
     */
    fun slice(start: Int, stop: Int, len: Int): IntArray {
        val s = if (start < 0) maxOf(0, start + len) else minOf(start, len)
        var e = if (stop < 0) maxOf(0, stop + len) else minOf(stop, len)
        if (e < s) e = s
        return intArrayOf(s, e)
    }

    /**
     * `img[y0:y1, x0:x1]` with numpy's slice rules, or null where numpy would
     * give an array of size 0 -- which is what every `if patch.size == 0`
     * in the readers asks.
     */
    fun crop(img: Mat, y0: Int, y1: Int, x0: Int, x1: Int): Mat? {
        val (r0, r1) = slice(y0, y1, img.rows()).let { it[0] to it[1] }
        val (c0, c1) = slice(x0, x1, img.cols()).let { it[0] to it[1] }
        if (r1 <= r0 || c1 <= c0) return null
        return img.submat(Range(r0, r1), Range(c0, c1))
    }
}
