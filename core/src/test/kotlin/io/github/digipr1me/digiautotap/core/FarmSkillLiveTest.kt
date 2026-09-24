package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.util.concurrent.TimeUnit

/**
 * The Meat Field skill against the running game, over ADB. Skipped unless
 * `-Ddigiautotap.live=<mode>` is given, so `gradlew :core:test` never taps
 * anything.
 *
 *   gradlew :core:test --tests "*FarmSkillLiveTest*" -Ddigiautotap.live=probe
 *       one frame, read and printed. Taps nothing.
 *   ... -Ddigiautotap.live=work     the field is standing in front: work it
 *                                 and leave it standing -- the semi-automatic
 *                                 hand-over, with the player having opened it
 *   ... -Ddigiautotap.live=run      from the main screen: go there, work, home
 *
 * Why this and not the APK: at this commit the app's CoreService only reads
 * the screen (the director is not hung into it yet), so there is no
 * way in through the app. What runs here is exactly the class the app will
 * run -- core carries nothing from `android.*`, which is what lets it -- with
 * a Capture that shells out to adb instead of the accessibility service. The
 * run through the app itself is still owed and is written down as such.
 */
class FarmSkillLiveTest {

    /** `Capture` over adb: `exec-out screencap -p` in, `input tap` out. */
    class AdbCapture(private val adb: String, private val serial: String?) : Capture {

        private fun args(vararg rest: String): List<String> =
            listOf(adb) + (serial?.let { listOf("-s", it) } ?: emptyList()) + rest

        private fun run(vararg rest: String): ByteArray {
            val p = ProcessBuilder(args(*rest)).redirectErrorStream(false).start()
            val out = p.inputStream.readBytes()
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                throw CaptureError("adb ${rest.joinToString(" ")} did not answer")
            }
            return out
        }

        override fun grab(): Mat {
            // A fresh picture or an error, never the last good one again
            // (Capture.grab's own contract).
            val png = run("exec-out", "screencap", "-p")
            if (png.isEmpty()) throw CaptureError("screencap gave nothing")
            val img = Imgcodecs.imdecode(MatOfByte(*png), Imgcodecs.IMREAD_COLOR)
            if (img.empty()) throw CaptureError("screencap gave ${png.size} bytes that are not a PNG")
            return img
        }

        override fun tap(x: Int, y: Int) { run("shell", "input", "tap", "$x", "$y") }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {
            run("shell", "input", "swipe", "$x1", "$y1", "$x2", "$y2", "$ms")
        }

        override fun back() = throw UnsupportedOperationException(
            "this skill never presses the back key")

        override fun inFront(): String? {
            // `dumpsys window`, not `dumpsys window windows`: the second
            // form prints nothing on this Android and the answer came back
            // null on a game that was plainly in front.
            val text = String(run("shell", "dumpsys", "window"))
            val line = text.lineSequence().firstOrNull { "mCurrentFocus" in it } ?: return null
            return Regex("""\s([A-Za-z0-9_.]+)/""").find(line)?.groupValues?.get(1)
        }
    }

    /**
     * A setting from the command line. The environment is read as well as
     * the system properties, because a `-D` on the gradle command line
     * reaches gradle's own JVM and not the test's, and core/build.gradle.kts
     * belongs to another session.
     */
    private fun setting(name: String): String? =
        System.getProperty("digiautotap.$name")
            ?: System.getenv("DIGIAUTOTAP_" + name.uppercase())

    @Test
    fun `the live game, if this run was asked for one`() {
        val mode = setting("live")
        assumeTrue(mode != null, "no DIGIAUTOTAP_LIVE=probe|work|run")
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val adb = setting("adb")
            ?: (System.getProperty("user.home") + "/.devtools/android-sdk/platform-tools/adb.exe")
        val cap = AdbCapture(adb, setting("serial"))

        println("in front: ${cap.inFront()}")
        val img = cap.grab()
        val r = Dungeon.gameRect(img)
        println("frame ${img.cols()} x ${img.rows()}, game rect ${r.x0},${r.y0} ${r.gw}x${r.gh}")
        println("classify: ${Director.classify(img)}")
        println("meat_field_screen: ${Farm.meatFieldScreen(img)} of 6")
        for (row in 0 until 3) {
            for (col in 0 until 2) {
                val state = Farm.plotState(img, col, row)
                val extra = when (state) {
                    "growing" -> "timer ${Farm.plotTimer(img, col, row)}"
                    "ripe" -> "harvest ${Farm.plotHarvest(img, col, row)}"
                    else -> ""
                }
                println("  plot $col,$row  ${state ?: "-"}  tap ${Farm.plotTap(col, row)}" +
                            (if (Farm.bubbleOver(img, col, row)) "  BUBBLE OVER THE TAP POINT" else "") +
                            (if (extra.isEmpty()) "" else "  $extra"))
            }
        }
        println("free seeds: ${Farm.freeSeeds(img)}   refill: ${Farm.seedRefill(img)}")
        println("seed menu: ${Farm.seedMenu(img).first?.size}   water popup: ${Farm.waterPopup(img)}")
        img.release()
        if (mode == "probe") return

        var due: Double? = null
        var grow: Int? = null
        var why = ""
        val skill = FarmSkill(cap,
                              dueAt = { due },
                              remember = { at, g, w -> due = at; grow = g; why = w },
                              log = { println("  $it") })
        println("--- $mode ---")
        val outcome = when (mode) {
            "work" -> cap.grab().let { f -> try { skill.work(f) } finally { f.release() } }
            "run" -> skill.run()
            else -> throw IllegalArgumentException("digiautotap.live=$mode")
        }
        println("outcome: $outcome")
        println("farm_due_at: $due (in ${due?.let { (it - System.currentTimeMillis() / 1000.0) / 60 }} min)")
        println("farm_grow_seconds: $grow")
        println("farm_last_reason: $why")

        val after = cap.grab()
        println("after: ${Director.classify(after)}  cells ${Farm.meatFieldScreen(after)}")
        after.release()
    }
}
