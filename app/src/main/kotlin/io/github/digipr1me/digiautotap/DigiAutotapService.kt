package io.github.digipr1me.digiautotap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import io.github.digipr1me.digiautotap.core.Capture
import io.github.digipr1me.digiautotap.core.CaptureError
import io.github.digipr1me.digiautotap.core.Dot
import io.github.digipr1me.digiautotap.core.Game
import io.github.digipr1me.digiautotap.core.HelperLog
import io.github.digipr1me.digiautotap.core.MainSwitch
import io.github.digipr1me.digiautotap.core.SkillSettings
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The only way at the screen (PLAN_ANDROID_5_SHELL.md 3.1), and the
 * [Capture] core reads and acts through. It holds no loop of its own: the
 * loop is [CoreService]'s, and this starts and stops it with itself, so that
 * a notification never stands over a service that is gone (section 4, "the
 * service dies quietly").
 *
 * The configuration names no package. Which one is the game is found here
 * ([regame]): the player's choice under Settings, then the game's own name,
 * then ldplayer.py's hints -- and the find goes into the log.
 */
class DigiAutotapService : AccessibilityService(), Capture {

    private val callbacks = Executors.newSingleThreadExecutor()

    @Volatile private var front: String? = null

    /** When the window in front last changed, elapsedRealtime. */
    @Volatile var frontSince: Long = 0L
        private set
    @Volatile var gamePackage: String? = null
        private set

    override fun onServiceConnected() {
        instance = this
        // No window event comes until the window changes, so a service that
        // connects over a running game would say "not in front" until the
        // player switched apps. The active window's package says it now.
        front = activeWindow()
        frontSince = SystemClock.elapsedRealtime()
        regame("service connected")
        startCore(this, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        noteFront(event.packageName?.toString() ?: return)
    }

    /** The active window's package, asked of the system now. */
    private fun activeWindow(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    @Synchronized
    private fun noteFront(pkg: String) {
        if (pkg == front) return
        front = pkg
        frontSince = SystemClock.elapsedRealtime()
        // Only while [regame] found nothing: a chosen or known game that is
        // installed is never replaced by whatever hinted app comes forward
        // (NOTES.md, "Which app is the game"). What is left is a build the
        // launcher list does not show.
        val game = gamePackage ?: Game.pick(listOf(pkg))?.also {
            gamePackage = it
            HelperLog.line("game package found in front: $it")
        }
        HelperLog.line("in front: $pkg" + if (pkg == game) " (the game)" else "")
        Status.update { it.copy(inFront = pkg) }
    }

    /**
     * Which app is the game, asked again: at connecting, and after every
     * choice in Settings, or a choice would only hold from the next connect.
     * The director asks [gamePackage] every round and needs nothing else.
     *
     * The line names every candidate, so that a debug package answers
     * "which other app was it" by itself -- the one of 2026-09-24 could not.
     */
    @Synchronized
    fun regame(why: String) {
        val names = launcherNames(this)
        val chosen = chosenGame(this)
        val pick = Game.explain(names, chosen)
        gamePackage = pick?.name
        val how = when (pick?.by) {
            Game.By.CHOSEN -> " (chosen)"
            Game.By.KNOWN -> " (known)"
            Game.By.HINT -> " (by hint '${pick.hint}')"
            null -> ""
        }
        val fallback = if (chosen != null && pick?.by != Game.By.CHOSEN)
            "chosen $chosen is not installed -- falling back; " else ""
        val candidates = Game.candidates(names, chosen).ifEmpty { listOf("none") }
        HelperLog.line("$why; ${fallback}game package: ${pick?.name ?: "none found"}$how; " +
            "candidates: ${candidates.joinToString(", ")}; in front: ${front ?: "unknown"}")
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        gone("service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        gone("service destroyed")
        callbacks.shutdownNow()
        super.onDestroy()
    }

    private fun gone(why: String) {
        if (instance !== this) return
        instance = null
        HelperLog.line("$why -- stopping the core and its notification")
        stopService(Intent(this, CoreService::class.java))
    }

    // --- Capture -----------------------------------------------------------

    /**
     * The window events alone are not enough, measured: pull the
     * notification shade down over the game and close it again, and the
     * last event is com.android.systemui's -- the game gets its focus back
     * without sending one, and a loop that trusted the events stopped
     * reading until the player switched apps. So the system is asked every
     * time; the events stay, as the early signal and for the log.
     */
    override fun inFront(): String? {
        activeWindow()?.let { noteFront(it) }
        return front
    }

    /**
     * takeScreenshot, waited for, as BGR. The spike measured the conversion
     * (hardware bitmap -> ARGB_8888 -> RGBA Mat -> BGR) and the system's
     * answer to a caller that retries at once: "Too many Binders sent to
     * SYSTEM" and a dead app. So a failure is thrown, never retried here.
     *
     * **The pace is kept here, because only here is it known.** The spike
     * also measured the floor: one call every 350 ms never failed, and at
     * 333 ms and below about half come back
     * ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT (NOTES.md, "takeScreenshot
     * has a floor"). The director's own beat is a second and never came
     * near it -- but a **skill** grabs at its own pace, and the first live
     * hand-over to Dungeons hit the floor inside a second:
     * `settledFrame` waits 0.15 s between two looks, by design, because
     * that is how you see whether a list has stopped gliding.
     *
     * So the wait belongs to the caller no longer. It is a fixed pace and
     * never a retry: whoever asks too soon is held here until the floor has
     * passed, which costs the pace nothing it was going to get anyway and
     * cannot turn into the burst that killed the process.
     */
    override fun grab(): Mat {
        // No frame of another app, either: a skill in the middle of its
        // work would read that app's screen for as long as its wait lasts
        // -- Dungeons' battle wait is 77 s -- and aim at it. Thrown, so that
        // the skill unwinds at its next look, the way every skill already
        // unwinds on a display that is off; the taps it would have sent on
        // the way out are held by [withheld].
        notInFront()?.let { throw CaptureError(it) }
        val bgr = grabRaw()
        // **The mask, at the one place there is**
        // (PLAN_ANDROID_DESIGN.md 3.2). Measured in LDPlayer before the overlay was built: the
        // app's own dot appears in the app's own screenshot, DOT_WARN to the
        // unit. So the frame carries something the game never drew, and it
        // is filled with the colour around it here -- before any reader sees
        // the Mat, not in the readers and not in the director, or the rule
        // would be at 75 sites and missing from one. The footprint is empty
        // whenever the overlay is off, and then this costs a branch.
        //
        // **And only where the two agree about the display.** The rectangles
        // are the window's, in the WindowManager's coordinate space; this is
        // the frame's. Measured the morning after the overlay was built:
        // LDPlayer's display is 1920 x 1080 with autoRotate, the game turns
        // it to 1080 x 1920 while it runs, and while the game was restarting
        // `currentWindowMetrics` said 1920 x 1080 with `takeScreenshot` still
        // answering 1080 x 1920 -- the window sat at x 1667, off the right
        // edge of a picture 1080 wide. Filling a rectangle measured in
        // another space is worse than filling none: at best it covers air,
        // at worst a part of the game the dot was never over.
        val fp = Overlay.footprint()
        if (fp.boxes.isNotEmpty()) {
            if (fp.w == bgr.cols() && fp.h == bgr.rows()) Dot.mask(bgr, fp.boxes)
            else HelperLog.line("the dot was not taken out of this frame: the window is on a " +
                "${fp.w} x ${fp.h} display and the picture is ${bgr.cols()} x ${bgr.rows()}")
        }
        // The overlay's heartbeat, and its glimpse at a skill's frame: every
        // frame anybody reads passes here, so this is where "DigiAutotap is
        // looking" is a fact and not a guess. After the mask, so that what
        // the glimpse classifies is the picture the readers get.
        Overlay.saw(bgr)
        return bgr
    }

    /**
     * The frame as the system hands it over, with nothing taken out of it.
     * [grab] is the one caller and masks the dot out of what comes back;
     * the two are apart because the overlay probe had to ask
     * whether the dot was in the picture at all, which a masked frame can
     * never answer.
     */
    private fun grabRaw(): Mat {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive) throw CaptureError("the display is off")
        synchronized(grabLock) {
            val since = SystemClock.elapsedRealtime() - lastGrab
            if (since in 0 until MIN_GRAB_GAP_MS) SystemClock.sleep(MIN_GRAB_GAP_MS - since)
            lastGrab = SystemClock.elapsedRealtime()
        }
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var error: Int? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, callbacks, object : TakeScreenshotCallback {
            override fun onSuccess(s: ScreenshotResult) {
                bitmap = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                s.hardwareBuffer.close()
                latch.countDown()
            }

            override fun onFailure(code: Int) {
                error = code
                latch.countDown()
            }
        })
        if (!latch.await(5, TimeUnit.SECONDS)) throw CaptureError("takeScreenshot did not answer in 5 s")
        val hw = bitmap ?: throw CaptureError("takeScreenshot failed: ${errorName(error)}")
        val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
        hw.recycle()
        val rgba = Mat()
        Utils.bitmapToMat(soft, rgba)
        soft.recycle()
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }

    override fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        throughTheDot(60, x to y)
        gesture("tap $x,$y", GestureDescription.StrokeDescription(path, 0, 60))
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        throughTheDot(ms, x1 to y1, x2 to y2)
        gesture("swipe $x1,$y1 -> $x2,$y2 in $ms ms", GestureDescription.StrokeDescription(path, 0, ms))
    }

    /**
     * A gesture of the app's own that would land on its own dot goes
     * through it: the window takes no taps for the gesture's length and a
     * little more ([Overlay.letThrough], and the measurement there -- the
     * app once paused itself this way). Asked of the ends of the stroke; a
     * swipe that only crosses the window is passed through by the system as
     * the game's already, because a touch belongs to the window it went
     * down in. The wait after it is for the window manager to have the new
     * flag before the touch arrives, and is paid only when the dot is hit.
     */
    private fun throughTheDot(ms: Long, vararg at: Pair<Int, Int>) {
        if (at.none { (x, y) -> Overlay.covers(x, y) }) return
        Overlay.letThrough(ms + LET_THROUGH_EXTRA_MS)
        SystemClock.sleep(LET_THROUGH_SETTLE_MS)
    }

    override fun overlayClear(fy0: Double, fy1: Double) = Overlay.clearOf(fy0, fy1)

    override fun overlayBack() = Overlay.backToPlace()

    override fun back() {
        if (withheld("back")) return
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        GESTURES.incrementAndGet()
        HelperLog.line("back sent: $ok")
        Status.update { it.copy(gestures = GESTURES.get()) }
    }

    /**
     * The one gate in front of every gesture: nothing goes out while the
     * main switch is off or the game is not the window in front.
     *
     * Measured on LDPlayer, 2026-09-22 14:07: the switch went off in the
     * middle of Dungeons' battle wait, three seconds later the player
     * switched to this app, and the skill went on tapping its neutral spot
     * once a second for 54 s -- into this app's own page -- and then, once
     * it had heard "stopped", tapped the dungeon tab four times and swiped
     * on its way home, still in the wrong app. Every skill asks the switch
     * between two actions, as Skill.kt says it must, and the Tower alone
     * asks whether the game is in front; but a battle wait is one action
     * of 77 s. "No tap outside the game and none while paused" is a rule
     * about the hand, not about any skill, so it sits here as the mask
     * sits in [grab]: one place, every caller. What a withheld tap costs a
     * skill is a verification that says "no effect", which is what the
     * verification is for.
     *
     * Said once per stretch and counted at its end, not once per tap: a
     * battle wait would otherwise write sixty lines.
     */
    private fun withheld(what: String): Boolean {
        val why = if (!MainSwitch.on) "the main switch is off" else notInFront()
        synchronized(holdLock) {
            if (why == null) {
                if (held > 0) HelperLog.line("$held gesture(s) withheld while $heldWhy")
                held = 0
                heldWhy = null
                return false
            }
            if (why != heldWhy) {
                if (held > 0) HelperLog.line("$held gesture(s) withheld while $heldWhy")
                heldWhy = why
                held = 0
                HelperLog.line("$what withheld: $why")
            }
            held += 1
            return true
        }
    }

    /** Why the game is not the window in front, or null while it is (or while no game is known). */
    private fun notInFront(): String? {
        val game = gamePackage ?: return null
        val front = inFront()
        if (front == game) return null
        return "the game is not in front" + (front?.let { " ($it)" } ?: "")
    }

    private val holdLock = Any()
    private var held = 0
    private var heldWhy: String? = null

    private fun gesture(what: String, stroke: GestureDescription.StrokeDescription) {
        // What the gate costs is on the critical path of every press Gekkomon
        // Run schedules to the tenth of a second, and `rootInActiveWindow` is
        // a round trip to the system. So it is timed, not assumed.
        val asked = SystemClock.elapsedRealtime()
        if (withheld(what)) return
        val gate = SystemClock.elapsedRealtime() - asked
        val g = GestureDescription.Builder().addStroke(stroke).build()
        GESTURES.incrementAndGet()
        Status.update { it.copy(gestures = GESTURES.get()) }
        val t0 = SystemClock.elapsedRealtime()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) =
                HelperLog.line("$what completed in ${SystemClock.elapsedRealtime() - t0} ms").let {}

            override fun onCancelled(d: GestureDescription?) = HelperLog.line("$what cancelled").let {}
        }, null)
        HelperLog.line("$what dispatched: $sent, ${gate} ms in the gate, " +
            "${SystemClock.elapsedRealtime() - asked} ms in hand")
    }

    private fun errorName(code: Int?): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal error"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no accessibility access"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "called again too soon"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
        ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "invalid window"
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "a secure window is showing"
        else -> "code $code"
    }

    /** The pace [grab] keeps: one lock, one clock reading, per process. */
    private val grabLock = Any()
    @Volatile private var lastGrab = 0L

    companion object {
        /** How much longer than its gesture the dot lets taps through. */
        const val LET_THROUGH_EXTRA_MS = 300L
        /** How long a let-through waits for the window manager before the gesture goes out. */
        const val LET_THROUGH_SETTLE_MS = 50L

        /**
         * The floor the spike measured for `takeScreenshot`: one call every
         * 350 ms never failed, 333 ms and below failed about half the time
         * (NOTES.md, "takeScreenshot has a floor"). Not a guess at how fast
         * the app wants to read -- the director asks once a second -- but the
         * system's own limit, kept where the call is made.
         */
        const val MIN_GRAB_GAP_MS = 350L

        /** Every gesture and back ever sent in this process; the proof of "no tap" is this at 0. */
        val GESTURES = AtomicInteger(0)

        @Volatile var instance: DigiAutotapService? = null
            private set

        /**
         * ldplayer.py's `pm list packages` walk, over what the launcher can
         * start: the manifest's <queries> makes those visible without
         * QUERY_ALL_PACKAGES. Here and not on the instance, because the
         * page's way into the game asks it with no service running.
         */
        fun findGame(c: Context): String? = Game.pick(launcherNames(c), chosenGame(c))

        /** What the launcher can start, this app left out: [findGame]'s list and the sheet's. */
        fun launcherNames(c: Context): List<String> {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return c.packageManager.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }.distinct().filter { it != c.packageName }
        }

        /** The player's choice under Settings (SkillSettings.GAME_KEY), or null for "find it". */
        fun chosenGame(c: Context): String? =
            SettingsStore(c).str(SkillSettings.GAME_KEY, "").ifEmpty { null }

        /**
         * Is it switched on in the system settings? Asked fresh every time
         * (section 3.2): the player switches services off between sessions,
         * and a remembered answer is not a measurement.
         */
        fun enabledInSettings(c: Context): Boolean {
            val mine = ComponentName(c, DigiAutotapService::class.java).flattenToString()
            val list = Settings.Secure.getString(c.contentResolver,
                                                 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return list.split(':').any { it.equals(mine, ignoreCase = true) }
        }

        /**
         * Start the foreground service. From the accessibility service this is
         * a start from the background, which Android 12+ allows only under an
         * exemption -- the one that applies here is the battery optimisation
         * the onboarding asks to lift. Where it is refused, the app starts it
         * the next time it is opened, and the log says which it was.
         *
         * A Stop from the notification or the app is honoured here, in the one place
         * every caller goes through. Measured in LDPlayer: Stop worked, the
         * notification went, and a second later the accessibility service
         * rebound, [onServiceConnected] started the core again, and the Stop
         * was undone by something the player never touched. `force` is the
         * Start button, which is the one thing that is allowed to say no to
         * the flag.
         */
        fun startCore(c: Context, why: String, force: Boolean = false) {
            if (CoreService.stoppedByPlayer && !force) {
                HelperLog.line("core not started ($why): stopped by the player -- " +
                    "Start brings it back")
                return
            }
            try {
                c.startForegroundService(Intent(c, CoreService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                HelperLog.line("core not started ($why): the system refused a start from the " +
                    "background -- open the app, or lift the battery optimisation")
            }
        }
    }
}
