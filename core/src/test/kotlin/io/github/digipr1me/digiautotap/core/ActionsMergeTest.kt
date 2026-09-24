package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Actions.mergeCounters`' early exit, over a real frame.
 *
 * The exit used to compare a map keyed by the bare counter names against
 * [Vision.COUNTER_KEYS], which are the ROI names -- two sets that share
 * nothing, so a caller that named no counters could never break early and
 * always sat out all four tries. Measured in Python over the 24 corpus
 * frames that calibrate to a board (`_merge_probe.py`): one grab against
 * four on a frame where all seven counters read at once.
 *
 * One stored frame, so every try reads the same picture and the only thing
 * being measured is whether the loop stops.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActionsMergeTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val vision = Vision(ClassPathAssets)

    /** A capture that hands out the same stored picture and counts the asking. */
    private class OneFrame(private val img: Mat) : Capture {
        var grabs = 0
        override fun grab(): Mat { grabs += 1; return img }
        override fun tap(x: Int, y: Int) = throw AssertionError("this suite taps nothing")
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) =
            throw AssertionError("this suite swipes nothing")
        override fun back() = throw AssertionError("this suite presses nothing")
        override fun inFront(): String? = null
    }

    @BeforeAll
    fun load() = System.loadLibrary(Core.NATIVE_LIBRARY_NAME)

    /** The corpus frame the Python probe reads all seven counters off. */
    private fun frame(): Pair<Mat, Calib>? {
        val file = File(repo, "corpus/bond/open_the_Digimon_page_191213.png")
        if (!file.exists()) return null
        val img = Imgcodecs.imread(file.path)
        return img to vision.calibrate(img)
    }

    @Test
    fun `the two counter spellings are what each of them says`() {
        assertEquals(Vision.COUNTER_KEYS.map { it.replace("roi_", "") }, Vision.COUNTER_NAMES)
        assertTrue(Vision.COUNTER_KEYS.intersect(Vision.COUNTER_NAMES.toSet()).isEmpty(),
                   "the ROI names and the bare names must not be compared with each other")
    }

    @Test
    fun `a caller that names no counters still stops on the first complete frame`() {
        val (img, calib) = frame() ?: return
        val counters = vision.readCounters(img, calib)
        assertEquals(Vision.COUNTER_NAMES, counters.keys.toList())
        assertTrue(counters.values.none { it == null },
                   "this frame is chosen because all seven read on it: $counters")

        val cap = OneFrame(img)
        val reading = Actions.mergeCounters(cap, vision, calib, sleep = {})
        assertEquals(1, cap.grabs, "all seven were in hand after the first frame")
        assertEquals(7, reading.counters.size, reading.counters.toString())
    }

    @Test
    fun `a caller that names its counters stops as soon as those are in hand`() {
        val (img, calib) = frame() ?: return
        val cap = OneFrame(img)
        Actions.mergeCounters(cap, vision, calib, need = listOf("paws", "meters"), sleep = {})
        assertEquals(1, cap.grabs, "paws and meters both read on the first frame")
    }

    @Test
    fun `a counter that never reads costs every try`() {
        val (img, calib) = frame() ?: return
        val cap = OneFrame(img)
        Actions.mergeCounters(cap, vision, calib, need = listOf("no_such_counter"), sleep = {})
        assertEquals(4, cap.grabs, "a name no frame can answer takes all the tries")
    }
}
