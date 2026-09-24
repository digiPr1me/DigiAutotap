package io.github.digipr1me.digiautotap

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import io.github.digipr1me.digiautotap.core.Dungeon
import io.github.digipr1me.digiautotap.core.Oracle
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The spike (PLAN_ANDROID_1_SPIKE.md 2.3). It measures and taps
 * nothing, and it may be thrown away: what stays is core and the build.
 *
 * Driven from the PC, one broadcast per measurement:
 *
 *   adb shell am broadcast -a io.github.digipr1me.digiautotap.SPIKE --es cmd bench [--el interval 1000]
 *   ... --es cmd rate
 *   ... --es cmd grab           one frame to files/spike/, recognise on it
 *   ... --es cmd file --es path spike/some.png
 *
 * Every number goes to logcat under the tag DigiAutotapSpike as "key: value".
 */
class SpikeService : AccessibilityService() {

    private val worker = Executors.newSingleThreadExecutor()
    private val callbacks = Executors.newSingleThreadExecutor()
    private var receiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        val loaded = OpenCVLoader.initLocal()
        log("service", "connected, OpenCV loaded=$loaded version=${Core.VERSION}")
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val cmd = intent.getStringExtra("cmd") ?: "grab"
                val path = intent.getStringExtra("path")
                val n = intent.getIntExtra("n", 50)
                val interval = intent.getLongExtra("interval", 1000L)
                worker.execute { runCatching { run(cmd, path, n, interval) }.onFailure {
                    Log.e(TAG, "cmd $cmd failed", it) } }
            }
        }
        registerReceiver(r, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
        receiver = r
    }

    override fun onDestroy() {
        receiver?.let { unregisterReceiver(it) }
        worker.shutdownNow()
        callbacks.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun run(cmd: String, path: String?, n: Int, interval: Long) {
        when (cmd) {
            "bench" -> bench(n, interval)
            "rate" -> rate()
            "grab" -> grab()
            "file" -> file(path ?: error("file needs --es path"))
            else -> log("error", "unknown cmd $cmd")
        }
    }

    // ----------------------------------------------------------------------

    /** One takeScreenshot, waited for: the bitmap, the error code, the ms. */
    private class Shot(val bitmap: Bitmap?, val error: Int, val ms: Double, val colorSpace: String)

    private fun shoot(): Shot {
        val latch = CountDownLatch(1)
        var result: Shot? = null
        val t0 = SystemClock.elapsedRealtimeNanos()
        takeScreenshot(Display.DEFAULT_DISPLAY, callbacks,
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                    val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    s.hardwareBuffer.close()
                    result = Shot(hw, 0, ms, s.colorSpace.name)
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                    result = Shot(null, errorCode, ms, "")
                    latch.countDown()
                }
            })
        latch.await(10, TimeUnit.SECONDS)
        return result ?: Shot(null, -1, 10_000.0, "")
    }

    /**
     * Hardware bitmap -> software ARGB_8888 -> RGBA Mat -> BGR Mat, which is
     * what every reader in core expects (cv2.imread's order). Returns the
     * BGR frame and the ms for the conversion.
     */
    private fun toBgr(hw: Bitmap): Pair<Mat, Double> {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat()
        Utils.bitmapToMat(soft, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        soft.recycle()
        return bgr to (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
    }

    private fun stats(xs: List<Double>): String {
        if (xs.isEmpty()) return "none"
        val s = xs.sorted()
        val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        return "min %.1f median %.1f max %.1f n %d".format(java.util.Locale.ROOT, s.first(), median, s.last(), s.size)
    }

    /**
     * 2.3: ms per takeScreenshot, ms per conversion, ms per recognise.
     *
     * One call every [interval] ms, measured from the start of the last one.
     * Calling again the moment a call fails is what the first version did,
     * and the system killed the app four seconds in: "Too many Binders sent
     * to SYSTEM". A caller that retries without a pause is not an option on
     * this API, whatever the interval turns out to be.
     */
    private fun bench(n: Int, interval: Long) {
        val shotMs = ArrayList<Double>()
        val convMs = ArrayList<Double>()
        val errors = HashMap<Int, Int>()
        var last: Mat? = null
        var colorSpace = ""
        var i = 0
        var next = SystemClock.elapsedRealtime()
        while (shotMs.size < n && i < n * 2) {
            i += 1
            SystemClock.sleep(maxOf(0L, next - SystemClock.elapsedRealtime()))
            next = SystemClock.elapsedRealtime() + interval
            val shot = shoot()
            if (shot.bitmap == null) {
                errors.merge(shot.error, 1, Int::plus)
                continue
            }
            shotMs += shot.ms
            colorSpace = shot.colorSpace
            val (bgr, ms) = toBgr(shot.bitmap)
            shot.bitmap.recycle()
            convMs += ms
            last?.release()
            last = bgr
        }
        log("takeScreenshot_ms", stats(shotMs) + " errors $errors (one call every $interval ms)")
        log("convert_ms", stats(convMs) + " hardware bitmap -> ARGB_8888 -> RGBA Mat -> BGR")
        val frame = last ?: return log("error", "no frame")
        log("frame", "${frame.cols()} x ${frame.rows()} colorSpace $colorSpace")
        save(frame, "bench")
        recogniseTimed(frame, "service frame")
        frame.release()
    }

    /**
     * 2.3: the rate limit, measured and not read out of AOSP. Twelve calls at
     * each fixed interval between call starts, shortest last so that a
     * failing run cannot take the ones after it down with it; the smallest
     * interval with no failure is the limit. Never flat out -- see bench.
     */
    private fun rate() {
        for (interval in listOf(1000L, 750L, 500L, 400L, 350L, 334L, 333L, 300L, 250L, 200L, 100L)) {
            var ok = 0
            val err = HashMap<Int, Int>()
            val ms = ArrayList<Double>()
            var next = SystemClock.elapsedRealtime()
            repeat(12) {
                SystemClock.sleep(maxOf(0L, next - SystemClock.elapsedRealtime()))
                next = SystemClock.elapsedRealtime() + interval
                val shot = shoot()
                if (shot.bitmap != null) { ok += 1; ms += shot.ms; shot.bitmap.recycle() }
                else err.merge(shot.error, 1, Int::plus)
            }
            log("rate_every_$interval", "ok $ok of 12, errors $err, ms ${stats(ms)}")
            SystemClock.sleep(1500)
        }
        log("rate_codes", "ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT=" +
            "$ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT INTERNAL_ERROR=$ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR")
    }

    /** One frame, saved beside its recognise, for the ADB comparison. */
    private fun grab() {
        val shot = shoot()
        val hw = shot.bitmap ?: return log("error", "takeScreenshot failed: ${shot.error}")
        val (bgr, ms) = toBgr(hw)
        hw.recycle()
        log("frame", "${bgr.cols()} x ${bgr.rows()} colorSpace ${shot.colorSpace} " +
            "shot %.1f ms convert %.1f ms".format(java.util.Locale.ROOT, shot.ms, ms))
        firstGameRow(bgr)
        save(bgr, "grab")
        recogniseTimed(bgr, "service frame", runs = 1)
        bgr.release()
    }

    /** 3.3's trap: a corpus frame, read in the app with the Android native. */
    private fun file(path: String) {
        // Internal files first: a file adb pushes into Android/data belongs
        // to the shell and the app cannot read it, so the corpus frames go
        // in with "run-as ... cat > files/..." instead.
        val f = File(filesDir, path).takeIf { it.exists() } ?: File(getExternalFilesDir(null), path)
        val img = Imgcodecs.imread(f.path)
        if (img.empty()) return log("error", "could not read $f")
        log("file", "${f.name} ${img.cols()} x ${img.rows()}")
        recogniseTimed(img, f.name)
        log("oracle_game_rect", Oracle.encode(Dungeon.gameRect(img).toOracle()))
        log("oracle_auto_button", Oracle.encode(Dungeon.autoButton(img)?.toOracle()))
        log("oracle_stage_failed", Oracle.encode(Dungeon.stageFailed(img)))
        img.release()
    }

    private fun recogniseTimed(img: Mat, what: String, runs: Int = 20) {
        val rec = Dungeon.recognise(img)
        log("recognise", "$what -> " + Oracle.encode(rec.toOracle()))
        if (runs > 1) {
            val ms = (1..runs).map {
                val t = SystemClock.elapsedRealtimeNanos()
                Dungeon.recognise(img)
                (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            }
            log("recognise_ms", "$what: ${stats(ms)}")
        }
    }

    /**
     * 2.3, status bar and cutout: the first and last pixel rows that are not
     * all black, against the frame's height. takeScreenshot gives the whole
     * display, and game_rect has to know where the game begins in it.
     */
    private fun firstGameRow(bgr: Mat) {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        fun lit(row: Int) = Core.countNonZero(gray.row(row)) > gray.cols() / 20
        val first = (0 until gray.rows()).firstOrNull { lit(it) } ?: -1
        val last = (gray.rows() - 1 downTo 0).firstOrNull { lit(it) } ?: -1
        val m = resources.displayMetrics
        log("display", "frame rows ${gray.rows()}, first lit row $first, last lit row $last, " +
            "displayMetrics ${m.widthPixels} x ${m.heightPixels} dpi ${m.densityDpi}")
        gray.release()
    }

    private fun save(bgr: Mat, name: String) {
        val dir = File(getExternalFilesDir(null), "spike").apply { mkdirs() }
        val f = File(dir, "$name.png")
        Imgcodecs.imwrite(f.path, bgr)
        log("saved", f.path)
    }

    private fun log(key: String, value: String) {
        Log.i(TAG, "$key: $value")
    }

    companion object {
        const val TAG = "DigiAutotapSpike"
        const val ACTION = "io.github.digipr1me.digiautotap.SPIKE"
    }
}
