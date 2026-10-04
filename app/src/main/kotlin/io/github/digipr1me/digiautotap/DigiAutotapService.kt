package io.github.digipr1me.digiautotap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.DisplayCutout
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.digipr1me.digiautotap.core.AdHand
import io.github.digipr1me.digiautotap.core.Ads
import io.github.digipr1me.digiautotap.core.Capture
import io.github.digipr1me.digiautotap.core.CaptureError
import io.github.digipr1me.digiautotap.core.Census
import io.github.digipr1me.digiautotap.core.Dot
import io.github.digipr1me.digiautotap.core.Dungeon
import io.github.digipr1me.digiautotap.core.Front
import io.github.digipr1me.digiautotap.core.Game
import io.github.digipr1me.digiautotap.core.GameWindow
import io.github.digipr1me.digiautotap.core.HelperLog
import io.github.digipr1me.digiautotap.core.MainSwitch
import io.github.digipr1me.digiautotap.core.SkillSettings
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
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

    /**
     * The activity in front, from the last window event that named one;
     * null after a change of package no event named. The game is one
     * activity (`Ads.GAME_ACTIVITY`) and every ad is another one of the
     * same package (PLAN_WERBUNG.md 1), so the package alone cannot tell
     * them apart and this can. Only an activity is taken: a window event
     * also names dialogs and popups by their view class, and a game with a
     * popup is not an ad.
     */
    @Volatile var frontClass: String? = null
        private set

    /** When [frontClass] last changed, elapsedRealtime: how long an event's word has stood ([Ads.stale]). */
    @Volatile private var classSince = 0L

    /**
     * The last activity each package had in front. Coming back from the
     * Play Store to an ad, the package changes under a poll ([inFront])
     * before the ad's own event arrives, and for that moment the ad would
     * read as the game -- and [withheld] would let a gesture into it.
     */
    private val lastClassOf = HashMap<String, String>()
    private val activityOf = HashMap<String, Boolean>()

    /** Is [cls] an activity of [pkg]? Asked of the package manager once per pair. */
    private fun isActivity(pkg: String, cls: String): Boolean = synchronized(activityOf) {
        activityOf.getOrPut("$pkg/$cls") {
            runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true }.getOrDefault(false)
        }
    }

    private var probe: AdProbe? = null

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
        // The windows list and the nodes a view marks as unimportant: what
        // the ad probe dumps is every window the system has, and much of an
        // ad's web page is not "important".
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        regame("service connected")
        probe = AdProbe(this).also { it.start() }
        startCore(this, "service connected")
        bound.forEach { it() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        noteFront(event.packageName?.toString() ?: return, event.className?.toString())
    }

    /** The active window's package, asked of the system now. */
    private fun activeWindow(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /**
     * [cls] is the event's class, or null from [inFront], which asks the
     * system for the package alone: that keeps the class the last event
     * named while the package stays, and forgets it when the package goes.
     */
    @Synchronized
    private fun noteFront(pkg: String, cls: String? = null) {
        val activity = cls?.takeIf { isActivity(pkg, it) }
        if (activity != null) lastClassOf[pkg] = activity
        if (pkg == front) {
            if (activity != null && activity != frontClass) {
                frontClass = activity
                classSince = SystemClock.elapsedRealtime()
                probe?.noted(pkg, activity)
            }
            return
        }
        front = pkg
        frontClass = activity ?: lastClassOf[pkg]
        classSince = SystemClock.elapsedRealtime()
        frontClass?.let { probe?.noted(pkg, it) }
        frontSince = SystemClock.elapsedRealtime()
        // Only while [regame] found nothing: a chosen or known game that is
        // installed is never replaced by whatever hinted app comes forward
        // (notes/director.md, "Which app is the game"). What is left is a build the
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
     * The line names every candidate, so that a shared log answers
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
        probe?.stop()
        HelperLog.line("$why -- stopping the core and its notification")
        stopService(Intent(this, CoreService::class.java))
        bound.forEach { it() }
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
        healStaleAd()
        return front
    }

    /**
     * [inFront], once [FRONT_TOLD_MS] have passed since the last screenshot
     * was asked for: the director asks it after reading a frame, and a
     * switch that began under the frame has been told by then
     * (notes/director.md, "A frame is taken before the system says who is
     * in front"). Where the reading took that long already -- a second on
     * LDPlayer -- nothing is waited.
     */
    override fun inFrontAfterFrame(): String? {
        val left = lastGrab + FRONT_TOLD_MS - SystemClock.elapsedRealtime()
        if (left > 0) SystemClock.sleep(left)
        return inFront()
    }

    /**
     * An ad's class that only its last event still names ([Ads.stale]):
     * the game's own node in the active window overrules it, and the game
     * is in front again. Asked only while a foreign class has stood
     * [Ads.STALE_AFTER_MS], so a running ad costs one short search of its
     * window per poll and the game none.
     */
    @Synchronized
    private fun healStaleAd() {
        val f = Front(front, frontClass)
        val held = SystemClock.elapsedRealtime() - classSince
        if (!Ads.stale(f, gamePackage, held) { activeHasGameView() }) return
        val pkg = f.pkg ?: return
        frontClass = Ads.GAME_ACTIVITY
        lastClassOf[pkg] = Ads.GAME_ACTIVITY
        classSince = SystemClock.elapsedRealtime()
        HelperLog.line("in front: the game's own window, after ${held / 1000.0} s of ${f.cls} " +
            "that only its last event still named")
        probe?.noted(pkg, Ads.GAME_ACTIVITY)
    }

    /**
     * Does the active window hold the game's own node ([Ads.GAME_VIEW])?
     * Breadth first and shallow: it stands five levels under the root, and
     * an ad's web page below that is never walked.
     */
    private fun activeHasGameView(): Boolean {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false
        var level = listOf(root)
        var seen = 0
        for (depth in 0..GAME_VIEW_DEPTH) {
            val next = ArrayList<AccessibilityNodeInfo>()
            for (n in level) {
                if (n.contentDescription?.toString() == Ads.GAME_VIEW) return true
                if (++seen > GAME_VIEW_NODES) return false
                for (i in 0 until n.childCount) n.getChild(i)?.let { next += it }
            }
            if (next.isEmpty()) return false
            level = next
        }
        return false
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
     * ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT (notes/director.md, "takeScreenshot
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
        // **The game's window, before anything else** (PLAN_FORMATE.md S6,
        // GameWindow): the part of the screenshot that is the game's, as the
        // system lays it out -- the whole display on every portrait display
        // LDPlayer makes, the letterbox on a landscape one, and what the API
        // says for split screen, a pop-up and a navigation bar -- less the
        // strip over a camera (below). The readers get that part, and
        // game_rect starts from its size.
        val win = gameWindow()
        val cut = GameWindow.cut(bgr.cols(), bgr.rows(), win, cutoutTop(bgr.cols(), bgr.rows()))
        displayChanged(bgr, cut)?.let { why ->
            bgr.release()
            throw CaptureError(why)
        }
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
        // **The strip over the camera is not the game's.** On a phone with a
        // cutout the game draws its background up to the top edge and keeps
        // its canvas under the cutout's safe inset: every button, card and
        // counter is scaled into the display less that strip. Measured
        // 2026-09-25 by cutting t px off the top and asking the readers
        // again: a player's Galaxy S26 Ultra (1080 x 2340) read its dungeon
        // list as the LDPlayer 1080 x 2340 twin at t 100 to 105 -- every
        // card's place, height and width to 0.002, where uncut the cards
        // were 0.690 wide against CARD_W_MIN 0.72 and the list was an
        // unknown screen -- and the Poco F3 (1080 x 2400) met the reference
        // at t 78 to 80 on three readers at once: the Events star
        // 0.6921/0.2254 against 0.6926/0.2249, the auto button 0.3688/0.7646
        // against 0.3687/0.7640, the result dialog's Quit 0.5927 against
        // 0.5924. So the frame the readers get starts under the camera's
        // strip -- where the canvas does on those two phones; over the
        // ceiling the rows above the canvas stay in it (below) --, after the
        // mask (whose rectangles are the display's), and [tap] and
        // [swipe] put the strip back. Without a cutout nothing is cut, and
        // LDPlayer and every corpus frame read as they always did.
        val shape = sayDisplay(bgr, win, cut)
        if (shape != toldShape) {
            toldShape = shape
            displaySaid = shape
            HelperLog.line("$shape (the system asked in ${"%.1f".format(Locale.ROOT, gameWindowMs)} ms)")
        }
        // **And a window may stand above the canvas.** Over the ceiling the
        // game's windows use the rows between the cutout and the canvas --
        // the Special Summon page and the dungeon panel at the top of them,
        // the World Search board and the popups in the middle
        // (Dungeon.Anchor, PLAN_FORMATE.md V4) -- so those rows stay in the
        // frame, from the cutout down, and the frame says how many there are
        // (Dungeon.CanvasFrame): the HUD's readers take the canvas under
        // them, a window's reader its own rectangle. The player's decision of
        // 2026-09-27, over the cut to the canvas V3 made: a window higher
        // than the cut lost its top rows, the board's three counters and the
        // Digivice bar among them. Only the camera's strip is cut.
        gameTopPx = cut.y
        gameLeftPx = cut.x
        val part = cut.x != 0 || cut.y != 0 || cut.w != bgr.cols() || cut.h != bgr.rows()
        val frame = when {
            cut.room > 0 -> Dungeon.CanvasFrame(cut.room, cut.room).also {
                bgr.submat(cut.y, cut.y + cut.h, cut.x, cut.x + cut.w).copyTo(it); bgr.release()
            }
            part -> bgr.submat(cut.y, cut.y + cut.h, cut.x, cut.x + cut.w).clone().also { bgr.release() }
            else -> bgr
        }
        // The overlay's heartbeat, and its glimpse at a skill's frame: every
        // frame anybody reads passes here, so this is where "DigiAutotap is
        // looking" is a fact and not a guess. After the mask and the cut, so
        // that what the glimpse classifies is the picture the readers get.
        Overlay.saw(frame)
        // Nothing of the frame is kept past this call since 1.3's fourth
        // candidate: the ring of the last three frames a report carried
        // (LastFrames, 23 MB of native memory on a 1080 x 2400 phone) went
        // with the report (notes/reports.md, "Nothing is sent and no picture
        // of the game is kept: the player shares the log").
        return frame
    }

    /**
     * **A display that changes under a pass ends the pass.** PLAN_FORMATE.md
     * V18, measured 2026-09-27 on LDPlayer with a World Search pass running
     * and `wm size` switched under it (1080 x 1920, 2340, 1920, 1440 x 2560,
     * 1920): the pass noticed nothing. It tapped on in the pixels of its
     * first calibration -- 527,975 and 199,975 on the 1440 display too --
     * read its counters out of the old boxes, booked 12445 on the orange
     * counter as "stale" and a pickup of +10000 the next action, gave a claw
     * away for no effect, and wrote eight resync frames; and the overlay,
     * built again only at the end of a round, stood unmasked in every frame
     * the pass read. Every skill measures its geometry once, on the frame
     * it begins with -- a board's calibration, a list's cards, a panel's
     * buttons -- and none of them can know the picture has another shape
     * now. This is the one place every frame passes, so it is asked here,
     * as the game being in front is: the first frame of another size, or
     * of another canvas top, is not handed to anybody. It is a
     * [CaptureError], which every skill already unwinds on, the next frame
     * of the same shape goes through, and the overlay is fitted to the new
     * display now rather than at the end of a round that may be minutes
     * away. The director's own next look starts from the new display.
     */
    private fun displayChanged(bgr: Mat, cut: GameWindow.Cut): String? {
        val now = Shape(bgr.cols(), bgr.rows(), cut)
        val was = lastShape
        lastShape = now
        if (was == null || was == now) return null
        Overlay.refresh()
        val why = "the display changed from ${shape(was)} to ${shape(now)} -- " +
            "what was begun on the old one ends here"
        HelperLog.line(why)
        return why
    }

    private fun shape(s: Shape): String = "${s.w} x ${s.h}" +
        (if (s.cut.w != s.w || s.cut.x != 0) " (game window ${s.cut.w} wide from x ${s.cut.x})" else "") +
        (if (s.cut.y + s.cut.room > 0) " (canvas from y ${s.cut.y + s.cut.room})" else "")

    /** A screenshot's size and the part of it that is the game's ([GameWindow.cut]). */
    private data class Shape(val w: Int, val h: Int, val cut: GameWindow.Cut)

    /** The shape of the last frame [grab] took. */
    @Volatile private var lastShape: Shape? = null

    /**
     * The "display:" line: the screenshot, the game's window in it, where
     * its canvas starts, the cutout and the status bar as the system names
     * them, and the screenshot's colour space -- the one axis only a phone
     * can show (PLAN_FORMATE.md 12: P3 values against sRGB thresholds).
     * Said whenever any of it changes.
     */
    private fun sayDisplay(bgr: Mat, win: IntArray?, cut: GameWindow.Cut): String {
        val w = bgr.cols()
        val h = bgr.rows()
        val taken = win?.let { GameWindow.snap(w, h, it) }?.takeIf { GameWindow.usable(w, h, it) }
        val raw = win?.let { "[${it[0]},${it[1]}][${it[2]},${it[3]}]" }
        val windowSaid = when {
            win == null -> "not given by the system, the whole picture taken"
            taken == null -> "$raw, not inside the picture, the whole picture taken"
            taken[0] == 0 && taken[1] == 0 && taken[2] == w && taken[3] == h ->
                "the whole display" + if (!win.contentEquals(taken)) " (said as $raw)" else ""
            else -> "$raw, ${taken[2] - taken[0]} x ${taken[3] - taken[1]} of it"
        }
        val canvas = cut.y + cut.room
        val inset = cut.y - (taken?.get(1) ?: 0)
        return "display: $w x $h, game window $windowSaid, the game's canvas starts $canvas px down " +
            "(display cutout ${cutout()?.let { "top ${it.safeInsetTop}, bottom ${it.safeInsetBottom}, " +
                "left ${it.safeInsetLeft}, right ${it.safeInsetRight}" } ?: "none"}; " +
            "status bar ${statusBarTop()} px); screenshot colour space ${lastColorSpace ?: "unknown"}" +
            when {
                cut.room > 0 -> " -- frames are read from ${cut.y} px down, ${cut.room} rows of them over the canvas"
                inset > 0 -> " -- frames are read from ${cut.y} px down"
                else -> ""
            }
    }

    /**
     * The bounds of the game's window on the display, left, top, right,
     * bottom, or null where the system does not say: `getWindows` with
     * flagRetrieveInteractiveWindows (the service's XML), the application
     * window whose root is the game's package. The window's id is kept, so
     * that the root -- a round trip -- is asked only when the id is gone.
     */
    private fun gameWindow(): IntArray? {
        val game = gamePackage ?: return null
        val t0 = System.nanoTime()
        try {
            val list = runCatching { windows }.getOrNull()
            if (list.isNullOrEmpty()) return null
            val apps = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val w = apps.firstOrNull { it.id == gameWindowId }
                ?: apps.firstOrNull { runCatching { it.root?.packageName?.toString() }.getOrNull() == game }
                ?: return null
            gameWindowId = w.id
            val r = Rect()
            w.getBoundsInScreen(r)
            return intArrayOf(r.left, r.top, r.right, r.bottom)
        } finally {
            gameWindowMs = (System.nanoTime() - t0) / 1e6
        }
    }

    @Volatile private var gameWindowId = -1

    /** How long the last [gameWindow] took, for the log. */
    @Volatile var gameWindowMs = 0.0
        private set

    /** The game's window as the last [grab] saw it, for about.txt. */
    @Volatile var displaySaid: String? = null
        private set

    /**
     * How many rows at the top of a portrait display of [w] x [h] are not
     * the game's canvas, and 0 everywhere else: the display cutout's safe
     * inset, or the band over a canvas that has reached its ceiling of
     * 19.5:9 (Dungeon.CANVAS_MAX_H_OF_W), whichever is more. The cutout is
     * the system's answer, not a measurement of the frame -- the two phones
     * above are where it was checked against one -- and more than an eighth
     * of the picture is not a camera, and is not taken from it. The band is
     * the game's own rule, measured on eight heights and three widths, and
     * holds on any height. [grab] asks the same rule of the game's window
     * ([GameWindow.cut]), which is this display wherever the game fills it:
     * the one rule, asked by the overlay too, which places its dot in the canvas
     * the readers get (`Dot.displayY`) and must cut the same strip [grab]
     * does, before there is a frame to ask.
     */
    fun canvasTop(w: Int, h: Int): Int {
        if (w >= h) return 0
        return Dungeon.canvasTop(w, h, cutoutTop(w, h))
    }

    /** The cutout's safe inset that [canvasTop] takes, 0 where it takes none. */
    private fun cutoutTop(w: Int, h: Int): Int {
        if (w >= h) return 0
        val top = cutout()?.safeInsetTop ?: 0
        return if (top in 1 until h / 8) top else 0
    }

    private fun cutout(): DisplayCutout? = runCatching {
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.cutout
    }.getOrNull()

    /** For the log only: whether the game's strip is the cutout or the status bar is what a new phone would ask. */
    private fun statusBarTop(): Int = runCatching {
        getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.statusBars()).top
    }.getOrDefault(-1)

    /**
     * Rows [grab] cut off the top of the last frame; a gesture in that
     * frame's pixels lands this much lower on the display.
     */
    @Volatile var gameTopPx = 0
        private set

    /** Columns [grab] cut off the left of the last frame (the game's window), added to a gesture's x. */
    @Volatile var gameLeftPx = 0
        private set
    @Volatile private var toldShape: String? = null
    @Volatile private var lastColorSpace: String? = null

    @Volatile private var toldColorSpace: String? = null

    /** The census (Census.COLOR_SPACE_KEY) wants the screenshot's colour space and cannot take one. */
    private fun noteColorSpace(name: String) {
        lastColorSpace = name
        if (name == toldColorSpace) return
        toldColorSpace = name
        runCatching { SettingsStore(this).put(Census.COLOR_SPACE_KEY, name) }
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
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var error: Int? = null
        // The call and its answer inside the lock, not only the clock
        // reading. Live on 2026-09-28 (PLAN_WERBUNG.md 9.2) two callers met
        // -- a skill's look and the ad probe's frame a second after an ad --
        // and one came back "called again too soon" with the pace kept: the
        // lock let the second caller go 350 ms after the first one's clock
        // reading, while the first one's call reached the system some time
        // after it. The skill parked on that one frame. Held through the
        // call, the gap is measured between two calls that were really made.
        synchronized(grabLock) {
            val since = SystemClock.elapsedRealtime() - lastGrab
            if (since in 0 until MIN_GRAB_GAP_MS) SystemClock.sleep(MIN_GRAB_GAP_MS - since)
            lastGrab = SystemClock.elapsedRealtime()
            takeScreenshot(Display.DEFAULT_DISPLAY, callbacks, object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    bitmap = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    noteColorSpace(s.colorSpace.name)
                    s.hardwareBuffer.close()
                    latch.countDown()
                }

                override fun onFailure(code: Int) {
                    error = code
                    latch.countDown()
                }
            })
            if (!latch.await(5, TimeUnit.SECONDS)) throw CaptureError("takeScreenshot did not answer in 5 s")
        }
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

    // In the pixels of the frame [grab] returned; the display is that plus
    // the strip it cut off the top ([gameTopPx]) and the columns left of the
    // game's window ([gameLeftPx]).
    override fun tap(x: Int, y: Int) {
        val dx = x + gameLeftPx
        val dy = y + gameTopPx
        val path = Path().apply { moveTo(dx.toFloat(), dy.toFloat()) }
        throughTheDot(60, dx to dy)
        gesture("tap $dx,$dy", GestureDescription.StrokeDescription(path, 0, 60))
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {
        val top = gameTopPx
        val left = gameLeftPx
        val dx1 = x1 + left
        val dx2 = x2 + left
        val dy1 = y1 + top
        val dy2 = y2 + top
        val path = Path().apply { moveTo(dx1.toFloat(), dy1.toFloat()); lineTo(dx2.toFloat(), dy2.toFloat()) }
        throughTheDot(ms, dx1 to dy1, dx2 to dy2)
        gesture("swipe $dx1,$dy1 -> $dx2,$dy2 in $ms ms", GestureDescription.StrokeDescription(path, 0, ms))
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
        val why = if (!MainSwitch.on) "the main switch is off" else notInFront() ?: adInFront()
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

    /**
     * An ad is in front: no gesture goes into it, and nothing in the app
     * touches an ad at all (notes/ads.md, "The app never touches an ad, and
     * nothing that closed one ships"). A tap meant for the game that lands on
     * an ad can open the store or skip the video -- which is how "it gets
     * stuck when it tries to watch ads" happened (notes/ads.md): the ad read
     * as an unknown screen of the game, and the unknown screen got its
     * neutral tap.
     */
    private fun adInFront(): String? =
        Ads.foreign(Front(front, frontClass), gamePackage)?.let { "an ad is in front ($it)" }

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
        dispatch(what, stroke, asked)
    }

    /** A gesture past its gate, whichever gate that was. */
    private fun dispatch(what: String, stroke: GestureDescription.StrokeDescription, asked: Long) {
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

    // --- the window in front, for whoever asks about an ad ---------------

    override fun adHand(): AdHand = hand

    /**
     * The game's package and the activity in front ([Front]): what tells an
     * ad from the game, asked by the director and the tasks. It has no
     * gesture of its own -- every gesture goes through [withheld], which
     * holds it while an ad is in front.
     */
    private val hand = object : AdHand {
        override val game: String? get() = gamePackage

        override fun front(): Front = Front(inFront(), frontClass)
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
        /**
         * The game's node stands five levels under its window's root on
         * every tree AdProbe kept (2026-09-28); eight leaves room for a
         * wrapper more, and the node count keeps an ad's page from being
         * walked at all.
         */
        const val GAME_VIEW_DEPTH = 8
        const val GAME_VIEW_NODES = 200

        /** How much longer than its gesture the dot lets taps through. */
        const val LET_THROUGH_EXTRA_MS = 300L
        /** How long a let-through waits for the window manager before the gesture goes out. */
        const val LET_THROUGH_SETTLE_MS = 50L

        /**
         * The floor the spike measured for `takeScreenshot`: one call every
         * 350 ms never failed, 333 ms and below failed about half the time
         * (notes/director.md, "takeScreenshot has a floor"). Not a guess at how fast
         * the app wants to read -- the director asks once a second -- but the
         * system's own limit, kept where the call is made.
         */
        const val MIN_GRAB_GAP_MS = 350L

        /**
         * How long after a screenshot is asked for the service is sure to
         * have been told of a switch that was on the display in it. Measured
         * on LDPlayer on 2026-09-29, opening this app's page over the game:
         * on nine openings the page's window event came 266, 269, 277, 279,
         * 282, 286, 310, 353 and 411 ms after the page was started, and the
         * page cannot be in a picture before it is started; a grab took 116
         * to 235 ms from its call to its frame. 411 + 235 is 646, and 800
         * leaves room. It costs a wait only where reading a frame is faster
         * than that (notes/director.md, "A frame is taken before the system
         * says who is in front").
         */
        const val FRONT_TOLD_MS = 800L

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
         *
         * Only the second opinion since 2026-10-02: whether the service can
         * see and tap is [instance], and this says which sentence goes with
         * a service that is not there. Each entry is read back as a
         * component rather than compared as text, because the setting can
         * hold the short spelling (`package/.DigiAutotapService`) as well as
         * the long one, and the text compare took the short one for "off".
         */
        fun enabledInSettings(c: Context): Boolean {
            val mine = ComponentName(c, DigiAutotapService::class.java)
            val list = Settings.Secure.getString(c.contentResolver,
                                                 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return list.split(':').any { ComponentName.unflattenFromString(it.trim()) == mine }
        }

        /**
         * Told when [instance] comes or goes: the page's set-up line was
         * built at the opening and said "missing" over a service that bound
         * a moment later, until the page was opened again.
         */
        val bound = CopyOnWriteArrayList<() -> Unit>()

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
