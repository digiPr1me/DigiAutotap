package io.github.digipr1me.digiautotap

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.digipr1me.digiautotap.core.Director
import io.github.digipr1me.digiautotap.core.Dot
import io.github.digipr1me.digiautotap.core.Dungeon
import io.github.digipr1me.digiautotap.core.HelperLog
import io.github.digipr1me.digiautotap.core.HelperState
import io.github.digipr1me.digiautotap.core.Shell
import org.opencv.core.Mat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The dot over the game (PLAN_ANDROID_DESIGN.md 3): one window of the
 * accessibility service's own, of type TYPE_ACCESSIBILITY_OVERLAY, holding a
 * 48 dp square with the dot in it and, beside it, a plate with one line --
 * what DigiAutotap is doing, and after a dot the name of the screen it sees.
 *
 * It does four things and no more: it shows the state in colour and with a
 * ring that is the three-second clock, a tap is the main switch, a long
 * press opens the app, and a drag moves it. It never taps the game -- every
 * tap on the game comes out of the accessibility service, so that there is
 * one way and not two.
 *
 * **Why the mask exists.** Measured in LDPlayer 14 on 2026-09-20, before a
 * line of this was written (PLAN_ANDROID_DESIGN.md 3.2): the app's
 * own dot *does* appear in the app's own `takeScreenshot` -- DOT_WARN read
 * back to the unit in all three channels, while a control square on the
 * other edge moved 0.7 grey levels over the same two frames. So the frame a
 * reader gets has something in it the game never drew, and [footprint] is
 * what `Capture.grab` takes back out of it. Everything drawn here stays
 * inside those two rectangles: a shadow or a glow that spilled past them
 * would be measured by the readers as the game (NOTES.md, "A tool that
 * draws outside the rectangle it masks measures its own spill").
 *
 * **No fourth thing to ask the player for.** The window went up with no
 * SYSTEM_ALERT_WINDOW anywhere in the manifest, which is the other half of
 * the same measurement (PLAN_ANDROID_DESIGN.md 3.2): an accessibility service is
 * allowed this type on its own, and the onboarding stays at three asks.
 */
object Overlay {

    /**
     * The smallest the touch area may be. What is *drawn* is
     * [Dot.DOT_OF_SCREEN] of the screen, which is the number the corpus was
     * measured with; this is the floor under it, so that a phone narrower
     * than 411 dp gets a smaller disc inside a 48 dp target rather than a
     * bigger footprint than anyone measured.
     */
    const val TOUCH_DP = 48

    /**
     * The plate is [Dot.PLATE_W_OF_SCREEN] x [Dot.PLATE_H_OF_SCREEN] of the
     * screen and not a size the text decides, for two reasons that were
     * both paid for.
     *
     * It was font-measured first: the plate is a second rectangle over the
     * game, it goes into the same mask as the dot, and the mask is only as
     * good as the corpus measurement behind it -- so a footprint that is the
     * system font's size is a measurement of one phone's font. And it is a
     * fraction of the screen rather than dp for the same reason the dot is:
     * see [Dot.DOT_OF_SCREEN].
     *
     * **One line, and why not two.** Measured on 2026-09-21 with
     * `gradlew :core:overlayProbe` over all 773 frames and all seven
     * families (PLAN_ANDROID_DESIGN.md 3.1 has the table): beside the dot
     * there is room for exactly 18 dp of height. Centred on the dot, the
     * plate reaches fy 0.1434 to 0.1680 and stops where
     * [Quest.STAGE_BAND] begins, with 0.0006 to spare -- about one pixel
     * at 1920 -- and 20 dp costs 95 tipped frames against 34. Lifted
     * clear of that band it runs into the Special Summon tab row instead,
     * which the oracle puts at fy 0.115 to 0.155 (27 `general_tab`
     * answers in `oracle/summon.json`), and tips that on real Summon
     * screens. Those two readers are the ones the overlay measurement named as the worst kind
     * to tip. Width is nearly free to 128 dp and costs `general_tab`
     * beyond it. So the plate is one wide line, and what does not fit is
     * cut with an ellipsis; the plate never grows.
     */

    /** The three-second clock the ring draws (design 3.1, decision 2). */
    const val CLOCK_MS = 3000L

    /** The ring redraws at 20 fps while it is filling, and not at all otherwise. */
    const val TICK_MS = 50L

    /**
     * How long Parked's ring is on and off again (design 3.1: "blinkt
     * langsam"). Slow on purpose -- it is the one state that wants to be
     * noticed from the corner of an eye without being a strobe over a game.
     */
    const val BLINK_MS = 1400L

    /**
     * How long the heartbeat shows after a frame was read. Frames come at
     * most three a second (DigiAutotapService.MIN_GRAB_GAP_MS), so at this
     * length the mark is a pulse and never a steady light.
     */
    const val BLIP_MS = 220L

    /**
     * A skill's turn shorter than this is not named on the plate: the
     * passive helper takes the main screen for a tenth of a second every
     * round, and a word that appears and vanishes twice a second is a
     * flicker, not information. A turn that lasts is named once it has.
     */
    const val TASK_GRACE_MS = 400L

    /**
     * While a skill has the screen, the screen's name on the plate is refreshed
     * from the skill's own frames, at most once a second: a dungeon run
     * is a minute, and the director's own round -- the only other thing
     * that names the screen -- does not return until it is over. One
     * `Director.classify` a second is what the director itself costs
     * between two turns, on a copy the skill's readers never see.
     */
    const val LOOK_EVERY_MS = 1000L

    private val main = Handler(Looper.getMainLooper())

    private var view: DotView? = null
    private var service: AccessibilityService? = null

    /**
     * Where the overlay stands, **and on a display of what size**.
     *
     * The size is half the answer and not a detail. The rectangles come out
     * of the window's own layout, which is the WindowManager's coordinate
     * space; the mask fills them in the frame's. Those are ordinarily the
     * same space and one morning they were not: LDPlayer's display is
     * 1920 x 1080 with autoRotate, the game turns it to 1080 x 1920 while it
     * runs, and between the game shutting down and starting again
     * `currentWindowMetrics` answered 1920 x 1080 while `takeScreenshot`
     * still returned 1080 x 1920. The window sat at x 1667 -- off the right
     * edge of a picture 1080 wide.
     *
     * Masking a rectangle that was measured in another space is worse than
     * masking nothing: at best it fills air, at worst it flattens a part of
     * the game the dot was never over. So the size travels with the
     * rectangles and [Capture.grab] compares before it fills.
     */
    class Footprint(val w: Int, val h: Int, val boxes: List<Dot.Box>)

    @Volatile
    private var shown = Footprint(0, 0, emptyList())

    /**
     * What `Capture.grab` fills before any reader sees the frame. Its boxes
     * are empty whenever the overlay is not on the screen, which is the
     * ordinary case for a player who switched it off -- and then nothing is
     * masked, which is the right answer and not a special case.
     */
    fun footprint(): Footprint = shown

    // --- the window ---------------------------------------------------------

    /**
     * Every window call goes through here. `WindowManager.addView` builds a
     * Handler for the view, which throws on a thread that has no Looper --
     * and every caller this has is one of those: the director's loop, the
     * probe's worker. Measured the first time the probe was run: "Can't
     * create handler inside thread Thread-3 that has not called
     * Looper.prepare()". So the rule is here, once, rather than at each
     * caller.
     */
    private fun onMain(what: String, f: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) { f(); return }
        val latch = CountDownLatch(1)
        main.post { try { f() } finally { latch.countDown() } }
        if (!latch.await(2, TimeUnit.SECONDS))
            HelperLog.line("overlay: $what did not finish in 2 s on the main thread")
    }

    /** Is the window up? */
    val up: Boolean get() = view != null

    fun show(svc: AccessibilityService): Boolean {
        if (view != null) return true
        var ok = false
        onMain("show") {
            val v = DotView(svc)
            try {
                windowManager(svc).addView(v, v.params())
                view = v
                service = svc
                shown = v.footprint()
                ok = true
                HelperLog.line("overlay: on, at " + v.where())
            } catch (e: Throwable) {
                HelperLog.line("overlay: the window was refused -- " +
                    "${e::class.java.simpleName}: ${e.message}")
            }
        }
        return ok
    }

    fun hide() {
        val v = view ?: return
        val svc = service ?: return
        view = null
        service = null
        shown = Footprint(0, 0, emptyList())
        onMain("hide") {
            runCatching { windowManager(svc).removeView(v) }
            HelperLog.line("overlay: off")
        }
    }

    /**
     * Called after every round of the director: the colour, the ring and the
     * two lines all come from what that round left behind, and the window is
     * hidden while the game is not in front (design 3.3). The window itself
     * stays; what goes with the picture is [FLAG_KEEP_SCREEN_ON] (see
     * [DotView.awake]).
     */
    fun update(state: HelperState, screen: String?, gameInFront: Boolean, task: String? = Status.now.task) {
        lastInFront = gameInFront
        if (view == null) return
        onMain("update") { fitted()?.set(state, screen, task, gameInFront) }
    }

    /**
     * The window, on the display it is standing on **now** -- measured
     * again if that is no longer the display it was built for.
     *
     * Measured live on 2026-09-21, which is the bug this answers: LDPlayer's
     * display is 1920 x 1080 and the game turns it to 1080 x 1920 while it
     * runs, so a core started from the home screen -- the ordinary case of
     * "DigiAutotap was already running and then the player started the game" --
     * measured the window on the landscape display and put it at
     * `1071,56 849 x 224 px on a 1920 x 1080 display`. The game was then
     * started from the launcher, and the system's own window dump had that
     * window still at (1071,56) 849 x 224, isVisible=true, while the display
     * was 1080 x 1920: nine pixels of the plate on the screen and the dot
     * itself, which stands at the far end of the window on the right edge,
     * at x 1696 -- off the picture altogether. The screenshot shows a game
     * with no dot on it, and nothing in the log says anything is wrong.
     *
     * It is built again rather than moved: the disc, the gap and the plate
     * are every one of them a fraction of the screen's width ([Dot]), and
     * re-deriving them outside the constructor is a second place that has to
     * agree with the first. What that costs is the few milliseconds between
     * `removeView` and `addView`, and with them FLAG_KEEP_SCREEN_ON, once,
     * on a display that has just changed size under the window.
     *
     * The state is not lost with the view: the caller sets it on the new one
     * in the same breath.
     */
    private fun fitted(): DotView? {
        val v = view ?: return null
        if (!v.stale()) return v
        val svc = service ?: return v
        HelperLog.line("overlay: the display is no longer the ${v.measuredOn()} " +
            "the window was measured on -- measuring it again")
        hide()
        return if (show(svc)) view else null
    }

    /** Whether the game was in front at the last round; [refresh] has no frame of its own. */
    @Volatile
    private var lastInFront = true

    /**
     * Draw the dot again **now**, for a change that did not come from a
     * round.
     *
     * [update] runs once per round of the director, and a round is as long
     * as the skill inside it: measured live, a tap on the dot said "main
     * switch: off" in the log and the dot stayed green, because `tick()` was
     * somewhere inside a dungeon run and would not return for a minute. A
     * switch that takes a minute to show is a switch people press again --
     * and pressing it again is Resume.
     *
     * So the things that change without a round say so themselves: the main
     * switch, the tap that threw it, and the skill whose turn begins or
     * ends. The screen's name is a round's to know, or a [saw] glimpse's,
     * and keeps the one it has.
     */
    fun refresh() {
        val v = view ?: return
        // Not held for: a refresh comes from inside the director's own
        // thread, at the start of a skill's turn, and a director that waits
        // two seconds on the main thread before every turn would be paying
        // for a picture.
        main.post {
            // The screen's name is read here and handed on, because a window
            // that had to be measured again is a new view and does not know it.
            val name = v.screenName
            fitted()?.set(Shell.state(), name, Status.now.task, lastInFront)
        }
    }

    // --- the heartbeat, and the glimpse ---------------------------------------

    private val looker = Executors.newSingleThreadExecutor { r -> Thread(r, "digiautotap-glimpse") }
    private val looking = AtomicBoolean(false)
    @Volatile private var lastLook = 0L

    /**
     * A frame was read: every one anybody reads passes `DigiAutotapService.grab`
     * and lands here, masked. Two things come of it.
     *
     * The heartbeat: a mark on the plate for [BLIP_MS], so that "DigiAutotap is
     * looking" is something the player sees happen, once a second while it
     * watches and several times a second while a skill works -- and *stops*
     * seeing when nothing is being read, which is the honest picture of a
     * core that has stalled.
     *
     * The glimpse: while a skill has the screen, the screen's name on the plate is
     * refreshed from the skill's own frames ([LOOK_EVERY_MS]), on a copy and
     * on a thread of its own, dropped rather than queued when the last one
     * is still being read. It changes nothing the director decides on -- the
     * director names the screen itself at every round of its own -- it only
     * keeps the word on the plate from lying for the length of a dungeon
     * run.
     *
     * **Two threads in `classify` at once is safe, and that was checked
     * rather than assumed.** The glimpse runs while the director may be
     * classifying a frame of its own. None of the eight readers
     * `Director.classify` reaches for -- Dungeon, Startup, Passive, Bond,
     * Summon, Explore, Farm, Cv -- holds a single field of mutable state
     * between calls: they are functions of the Mat they are given. (Vision
     * does keep caches, which is why the oracle writer gives each thread
     * one of its own; `classify` does not use Vision.) The Mat itself is a
     * clone, so the skill's own frame is never touched.
     */
    fun saw(frame: Mat) {
        val v = view ?: return
        main.post { v.blip() }
        if (Status.now.task == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLook < LOOK_EVERY_MS) return
        if (!looking.compareAndSet(false, true)) return
        lastLook = now
        val copy = frame.clone()
        looker.execute {
            try {
                val name = runCatching { Director.classify(copy).screen }.getOrNull()
                if (name != null) main.post { v.glimpsed(name) }
            } finally {
                copy.release()
                looking.set(false)
            }
        }
    }

    /**
     * Portrait only, as the whole app is (PLAN_ANDROID_APP.md 3.4). A
     * display that is wider than it is tall is one the game does not run on
     * and one the readers were never measured on, and it is also the state
     * in which the window's coordinates and the frame's part company -- see
     * [Footprint]. The dot goes away rather than stand somewhere it cannot
     * be masked out of.
     */
    fun portrait(w: Int, h: Int): Boolean = h >= w

    private fun windowManager(c: Context) = c.getSystemService(WindowManager::class.java)

    // --- where it stands ----------------------------------------------------

    /**
     * Off the game's rows [fy0] to [fy1] until [backToPlace], if the dot or
     * its plate stands on them: `Capture.overlayClear`, the player's idea of
     * 2026-09-23. Network Defense Ops' counter (Dungeon.HEADER_COUNTER) is
     * under the plate at the default place, and the mask flattens what it
     * covers. The dot goes to the top of the one measured strip
     * (Dot.MEASURED_FY_MIN) and nothing is written to the settings: the
     * player's own place is where [backToPlace] puts it.
     */
    fun clearOf(fy0: Double, fy1: Double) {
        if (view == null) return
        onMain("clear") { view?.clearOf(fy0, fy1) }
    }

    /**
     * Is ([x], [y]), in display pixels, inside the window -- where a
     * gesture of the service's own would land on the dot rather than on the
     * game? Read off the window's own layout, which is what the system hands
     * a touch to, drawn or not.
     */
    fun covers(x: Int, y: Int): Boolean {
        val v = view ?: return false
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return false
        if (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) return false
        return x >= lp.x && x < lp.x + lp.width && y >= lp.y && y < lp.y + lp.height
    }

    /**
     * No taps for [ms]: the window lets the service's own gesture through to
     * the game underneath. Measured live on LDPlayer 2026-09-23: with the dot
     * at its default place (fy 0.1555, window 602,236 478 x 126), Special
     * Summon's tap on the neighbour banner went out at (912, 339), inside the
     * window, and the log said "overlay: tapped -- the main switch" and
     * "main switch: off" -- the app had paused itself. A gesture is the
     * game's, wherever the dot happens to stand.
     */
    fun letThrough(ms: Long) {
        if (view == null) return
        onMain("let through") { view?.passFor(ms) }
    }

    /** Back to the player's place after [clearOf]; nothing if it never moved. */
    fun backToPlace() {
        if (view == null) return
        onMain("back to place") { view?.backToPlace() }
    }

    /**
     * The place, remembered per device in `digiautotap.json` -- the side and
     * the centre fy, which is the vocabulary the overlay probe measured in
     * (PLAN_ANDROID_DESIGN.md 3.2).
     */
    class Place(val left: Boolean, val fy: Double)

    const val KEY_ON = "overlay_on"
    const val KEY_LEFT = "overlay_left"
    const val KEY_FY = "overlay_fy"

    fun placeOf(store: SettingsStore) = Place(
        store.bool(KEY_LEFT, false),
        store.num(KEY_FY, Dot.DEFAULT_FY))

    /**
     * Where a drag may be let go, and the whole of the drop rule.
     *
     * The overlay probe replaced the mockup's three guessed forbidden strips with
     * one measurement and one sentence: **it is a list of what is allowed,
     * not a list of what is not** (PLAN_ANDROID_DESIGN.md 3.2). Of 68 places measured
     * coarsely, 65 tip a reader even masked, and of 40 measured finely, all
     * 40 do. So there is exactly one measured strip -- the right edge between
     * fy 0.060 and 0.155, which tips 3 to 8 frames of 773 -- and a drop
     * anywhere else is pulled to the nearest point of it. Whoever wants a
     * second strip measures it, with `_overlay_probe.py`, over the whole
     * corpus.
     */
    fun drop(left: Boolean, fy: Double): Place {
        val y = minOf(maxOf(fy, Dot.MEASURED_FY_MIN), Dot.MEASURED_FY_MAX)
        // The left edge has no measured strip at all: its quietest places
        // tip 6 and 7 frames where the right edge's tip 3, and its top two
        // tip 71 and 79. A side that was never clean is not offered.
        return Place(false, y)
    }

    /** True where a drop would be moved rather than taken as it is. */
    fun wouldMove(left: Boolean, fy: Double): Boolean {
        val p = drop(left, fy)
        return p.left != left || Math.abs(p.fy - fy) > 1e-9
    }

    /** A skill's log name as the plate says it: "the bond token" is "Bond token". */
    fun taskLabel(name: String): String =
        name.removePrefix("the ").replaceFirstChar { it.titlecase(Locale.ROOT) }

    // --- the view -----------------------------------------------------------

    /**
     * The dot, its ring and the plate. One view so that a drag moves both and
     * so that the mask has one window's rectangles to work from rather than
     * two windows to keep in step.
     */
    class DotView(val svc: AccessibilityService) : View(svc) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = Rect()
        private val store = SettingsStore(svc)
        private var place = placeOf(store)

        private var state: HelperState = Shell.state()
        /** classify's name for the screen, from the last round or the last glimpse. */
        var screenName: String? = null
            private set
        private var task: String? = null
        private var taskSince = 0L
        private var visible = true

        /**
         * Is the display still portrait? Read once a round rather than in
         * `onDraw`, which runs twenty times a second while the ring fills.
         */
        private var upright = true

        private val density = svc.resources.displayMetrics.density

        /**
         * The display this window was measured on. Every pixel below is a
         * fraction of [screenPx] and the window's place in [params] is a
         * corner of this rectangle, both taken once -- so the display it was
         * taken on is half the answer, the same way it is for the mask
         * ([Footprint]). [Overlay.fitted] is what asks whether it is still
         * that display.
         */
        private val measured = display()
        private val screenPx = measured.width()

        /** What the window was measured on, for the log. */
        fun measuredOn(): String = "${measured.width()} x ${measured.height()}"

        /** Is the display no longer the one [measured] on? */
        fun stale(): Boolean = display().let {
            it.width() != measured.width() || it.height() != measured.height()
        }

        /** What is drawn and what is masked: a fraction of the screen. */
        private val disc = Math.round(Dot.DOT_OF_SCREEN * screenPx).toInt()

        /** What a thumb has to hit: never less than 48 dp, whatever that is here. */
        private val touch = maxOf(disc, Math.round(TOUCH_DP * density))

        private val gap = Math.round(Dot.GAP_OF_SCREEN * screenPx).toInt()
        private val plateW = Math.round(Dot.PLATE_W_OF_SCREEN * screenPx).toInt()
        private val plateH = Math.round(Dot.PLATE_H_OF_SCREEN * screenPx).toInt()

        init {
            // The plate is a constant and the text is what gives way. Sizes
            // are fractions of the plate rather than sp, so that the plate
            // reads the same on every phone the mask was measured for; a
            // line that does not fit is cut with an ellipsis, never let out.
            // Condensed, because the plate is one line and 18 dp tall
            // (measured, see Dot.PLATE_H_OF_SCREEN): "Dungeons · dungeon_list"
            // is 23 characters, and a condensed face fits a fifth more of
            // them into the 128 dp than the regular one.
            text.typeface = BOLD
        }

        private fun display(): Rect =
            svc.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds

        /**
         * Does the window draw anything at all? [onDraw] returns before its
         * first stroke while the game is not in front or the display is not
         * portrait, and those are the two states [flags] has to know about.
         */
        private fun drawing(): Boolean =
            visible && display().let { portrait(it.width(), it.height()) }

        /**
         * Does the display have to stay on? Only while there is something
         * to read and the switch says to read it: the game in front and
         * the main switch on. Until 2026-09-23 the flag stood for as long
         * as the window did -- paused, and over every other app -- and the
         * player asked why their phone never went dark. A paused DigiAutotap
         * taps nothing, and the service withholds every gesture while the
         * game is not in front, so a display that sleeps then costs
         * nothing a screenshot was needed for. Parked keeps it: the
         * switch is on, and the director leaves Parked by itself when the
         * screen changes.
         */
        private fun awake(): Boolean =
            visible && state != HelperState.PAUSED && state != HelperState.STOPPED

        /**
         * The flags, and the two of them that come and go: the one about
         * taps, below, and the one about the display ([awake]).
         *
         * A window takes every tap that lands inside its frame, drawn or
         * not, and this frame is the plate and the dot together -- 849 x 224
         * px when it was built on LDPlayer's 1920 x 1080 home screen.
         * Measured live on 2026-09-22 with the service running and nothing
         * on the screen to show for it: `input tap 1491 250` on the
         * launcher's DigiAutotap icon left the launcher in front, and the same
         * tap after `am force-stop` opened `digiautotap/.MainActivity`. The
         * window had swallowed it. Two of the launcher's icons sit under
         * that rectangle, and a player who starts DigiAutotap before the game
         * -- the ordinary order -- cannot tap them.
         *
         * So a window that draws nothing takes nothing either. The flag is
         * the whole fix: the window stays up, and with it FLAG_KEEP_SCREEN_ON,
         * which is the reason [Overlay.update] keeps it up rather than
         * hiding it while the game is away. What is left is the tap that
         * lands on the plate, or in the transparent corners beside it, while
         * both are drawn -- one window is what lets a drag move the plate
         * and the dot together and gives the mask one rectangle pair to work
         * from ([DotView]), and a touchable region of its own would want the
         * hidden `OnComputeInternalInsetsListener`.
         */
        private fun flags(drawing: Boolean, awake: Boolean): Int {
            var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (!drawing) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // The one flag that is not about the dot: the display must not
            // sleep while the app plays, because takeScreenshot fails on a
            // dark screen (PLAN_ANDROID_APP.md 3.4). It keeps the display
            // on, not bright -- the brightness stays the player's.
            if (awake) f = f or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            return f
        }

        /** The window: as wide as the plate, the gap and the dot together, as tall as the touch area. */
        fun params(): WindowManager.LayoutParams {
            val lp = WindowManager.LayoutParams(
                plateW + gap + touch, maxOf(touch, plateH),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags(drawing(), awake()),
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            val d = display()
            lp.x = if (place.left) 0 else d.width() - lp.width
            lp.y = Math.round(place.fy * d.height() - lp.height / 2.0).toInt()
            return lp
        }

        /**
         * Are the window's flags still the ones its picture and its state
         * deserve -- touchable while drawn, keeping the display on while
         * [awake]? Asked once a round, beside the picture itself, and the
         * system is only called when the answer has changed --
         * `updateViewLayout` on every round of the director would be a
         * window rebuilt twenty times a minute for nothing.
         */
        private fun touchable() {
            val lp = layoutParams as? WindowManager.LayoutParams ?: return
            val drawn = visible && upright
            val want = flags(drawn && !passing(), awake())
            if (lp.flags == want) return
            val was = lp.flags
            lp.flags = want
            runCatching {
                svc.getSystemService(WindowManager::class.java).updateViewLayout(this, lp)
            }
            val taps = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // A gesture let through is not news; the window's own changes are.
            if (!quiet && (was xor want) and taps != 0)
                HelperLog.line("overlay: the window " +
                    if (drawn) "is drawn again and takes taps"
                    else "draws nothing, so it takes no taps either")
            val on = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            if ((was xor want) and on != 0)
                HelperLog.line("overlay: " +
                    if (want and on != 0) "keeping the display on"
                    else "the display may sleep again")
        }

        /** The dot's square on the display, in pixels, and the plate beside it. */
        fun footprint(): Footprint {
            val d = display()
            val lp = layoutParams as? WindowManager.LayoutParams
                ?: return Footprint(d.width(), d.height(), emptyList())
            // Nothing is drawn while the game is not in front, so there is
            // nothing to take out of the frame either -- and an empty list
            // is cheaper than a mask that fills the frame with itself.
            if (!visible || !portrait(d.width(), d.height()))
                return Footprint(d.width(), d.height(), emptyList())
            // The dot's own square, from the same arithmetic the corpus
            // probe uses -- except for its size, which is the one number the
            // two cannot share: a stored frame carries no density, so the
            // probe has to work 48 dp back from an assumed 411 dp screen
            // (Dot.DOT_OF_SCREEN) while a phone simply knows. They agree on a
            // 420 dpi phone and nowhere else -- LDPlayer at 280 dpi draws 84
            // px where the probe measured 126. See PLAN_ANDROID_DESIGN.md 3.2
            // for what that costs and what is still to measure.
            // The *disc*, not the window: the transparent ring that makes
            // the touch area 48 dp on a narrow phone has nothing drawn in
            // it, and a mask over it would flatten a part of the game the
            // dot was never on.
            val inset = (touch - disc) / 2
            val dotX = inset + if (place.left) lp.x else lp.x + plateW + gap
            val dotY = lp.y + (lp.height - disc) / 2
            val plateX = if (place.left) lp.x + touch + gap else lp.x
            val plateY = lp.y + (lp.height - plateH) / 2
            return Footprint(d.width(), d.height(), listOf(
                Dot.Box(dotX.toDouble(), dotY.toDouble(), disc.toDouble()),
                Dot.Box(plateX.toDouble(), plateY.toDouble(), plateW.toDouble(), plateH.toDouble())))
        }

        fun where(): String {
            val lp = layoutParams as? WindowManager.LayoutParams ?: return "nowhere yet"
            return String.format(Locale.ROOT,
                "%s edge, fy %.4f -- window %d,%d %d x %d px on a %d x %d display",
                if (place.left) "left" else "right", place.fy,
                lp.x, lp.y, lp.width, lp.height, display().width(), display().height())
        }

        fun set(s: HelperState, screen: String?, t: String?, gameInFront: Boolean) {
            state = s
            screenName = screen
            if (t != task) taskSince = SystemClock.elapsedRealtime()
            task = t
            visible = gameInFront
            val d = display()
            upright = portrait(d.width(), d.height())
            invalidate()
            touchable()
            shown = footprint()
            if (s == HelperState.WAITING || (s == HelperState.PARKED && !stillness()))
                tick() else main.removeCallbacks(ticker)
        }

        /** A glimpse named the screen between two rounds: only the word changes. */
        fun glimpsed(name: String) {
            if (name == screenName) return
            screenName = name
            invalidate()
        }

        // --- the heartbeat ----------------------------------------------------

        private var blipAt = 0L
        private val unblip = Runnable { invalidate() }

        fun blip() {
            blipAt = SystemClock.elapsedRealtime()
            invalidate()
            main.removeCallbacks(unblip)
            main.postDelayed(unblip, BLIP_MS)
        }

        private val ticker = object : Runnable {
            override fun run() {
                val filling = state == HelperState.WAITING
                val blinking = state == HelperState.PARKED && !stillness()
                if (!filling && !blinking) return
                invalidate()
                main.postDelayed(this, if (filling) TICK_MS else BLINK_MS / 2)
            }
        }

        /**
         * Has the player asked for less movement? Then Parked does not blink
         * -- the design says so in the same breath as the blink itself
         * (decision 2, "ohne Blinken bei reduce motion"). Asked of the
         * system rather than remembered, like every other such question
         * here; a scale of zero is what the developer options and the
         * accessibility page both write.
         */
        private fun stillness(): Boolean = runCatching {
            android.provider.Settings.Global.getFloat(
                svc.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

        /**
         * The ring only redraws while it is filling. A view that renews its
         * own callback off a *state* rather than off the time that is left
         * never goes quiet again -- which is exactly how the shell's
         * countdown once kept a window from ever being idle
         * (PLAN_ANDROID_5_SHELL.md 5.5).
         */
        private fun tick() {
            main.removeCallbacks(ticker)
            main.post(ticker)
        }

        // --- drawing --------------------------------------------------------

        /** The task once its turn has lasted [TASK_GRACE_MS]; a redraw is booked for the moment it has. */
        private val nameTask = Runnable { invalidate() }

        private fun taskShown(now: Long): String? {
            val t = task ?: return null
            val waited = now - taskSince
            if (waited >= TASK_GRACE_MS) return t
            main.removeCallbacks(nameTask)
            main.postDelayed(nameTask, TASK_GRACE_MS - waited)
            return null
        }

        /**
         * The first line: what DigiAutotap is doing, in as few words as the
         * plate holds. The state's own word where the state is the news
         * (Paused, Parked, the clock), the skill's name while one has the
         * screen, and otherwise "Watching" -- or "Unknown screen", which is
         * the one word that tells a player why nothing happens.
         */
        private fun status(now: Long, shownTask: String?): String = when (state) {
            HelperState.PAUSED -> "Paused"
            HelperState.STOPPED -> "Stopped"
            // "Parked", and the screen's name after it -- not "tap to
            // retry", which is the same length as the name and crowds it
            // out. Measured live on 2026-09-21: "Parked · tap to retry"
            // filled the plate and the name was dropped, on exactly the
            // screen where a player wants to know which one it is. The
            // tap is in the notification and in the app; the name is
            // nowhere else.
            HelperState.PARKED -> "Parked"
            HelperState.WAITING -> {
                val left = Shell.takeOverAt - now
                "Taking over in ${maxOf(0L, (left + 999) / 1000)} s"
            }
            HelperState.RUNNING, HelperState.IDLE -> when {
                shownTask != null -> taskLabel(shownTask)
                screenName == Director.UNKNOWN -> "Unknown screen"
                else -> "Watching"
            }
        }

        override fun onDraw(canvas: Canvas) {
            // Asked once a round in [set] and remembered, never here: onDraw
            // runs twenty times a second while the ring fills, and
            // currentWindowMetrics is a call into the system.
            if (!visible || !upright) return
            val now = SystemClock.elapsedRealtime()
            // Asked once for the whole draw: it books the redraw for the
            // moment the grace is over, and asking twice would book it twice.
            val shownTask = taskShown(now)
            // The window is `touch` across and the dot is `disc`, which on
            // a narrow phone is the smaller of the two: what is drawn stays
            // the fraction of the screen the corpus was measured with, and
            // the rest of the window is transparent room for a thumb.
            val size = disc.toFloat()
            val dotLeft = if (place.left) (touch - disc) / 2f
                          else (plateW + gap + (touch - disc) / 2).toFloat()
            val cx = dotLeft + size / 2f
            val cy = height / 2f
            val working = state == HelperState.RUNNING && shownTask != null

            drawPlate(canvas, status(now, shownTask), now)
            drawDot(canvas, cx, cy, size, working)
        }

        /**
         * The dot: a dark halo, the state's disc with a white rim on it, and
         * the ring around that. The halo is what makes the four colours
         * readable on every stage -- the game draws stages in orange, pale
         * stone and violet, and a colour alone vanishes on one of them -- and
         * it stays inside the masked square where a shadow would not.
         */
        private fun drawDot(canvas: Canvas, cx: Float, cy: Float, size: Float, working: Boolean) {
            val p = Theme.LIGHT
            paint.style = Paint.Style.FILL
            paint.color = p.DOT_PLATE
            paint.alpha = HALO_ALPHA
            canvas.drawCircle(cx, cy, size * HALO_OF_DOT, paint)

            val r = size * DISC_OF_DOT
            paint.color = state.dot(p)
            paint.alpha = 255
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * RIM_OF_DOT
            paint.color = p.DOT_RIM
            paint.alpha = RIM_ALPHA
            canvas.drawCircle(cx, cy, r, paint)

            drawGlyph(canvas, cx, cy, size, working)
            drawRing(canvas, cx, cy, size, working)
        }

        /**
         * A second channel beside the colour, for a glance and for anyone
         * who does not tell green from red: two bars for Paused, a mark for
         * Parked, and a small hollow while DigiAutotap only watches -- a solid
         * green disc is one that is working.
         */
        private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, working: Boolean) {
            val p = Theme.LIGHT
            paint.style = Paint.Style.FILL
            when (state) {
                HelperState.PAUSED, HelperState.STOPPED -> {
                    paint.color = p.DOT_RIM
                    val w = size * 0.045f; val h = size * 0.16f; val g = size * 0.05f
                    canvas.drawRoundRect(RectF(cx - g - w, cy - h, cx - g, cy + h), w / 2, w / 2, paint)
                    canvas.drawRoundRect(RectF(cx + g, cy - h, cx + g + w, cy + h), w / 2, w / 2, paint)
                }
                HelperState.PARKED -> {
                    paint.color = p.DOT_RIM
                    val w = size * 0.05f
                    canvas.drawRoundRect(RectF(cx - w / 2, cy - size * 0.17f, cx + w / 2, cy + size * 0.06f), w / 2, w / 2, paint)
                    canvas.drawCircle(cx, cy + size * 0.14f, w * 0.7f, paint)
                }
                HelperState.RUNNING, HelperState.IDLE -> if (!working) {
                    paint.color = p.DOT_PLATE
                    paint.alpha = HALO_ALPHA
                    canvas.drawCircle(cx, cy, size * 0.06f, paint)
                    paint.alpha = 255
                }
                HelperState.WAITING -> {}
            }
        }

        /**
         * The three-second clock (design 3.1): it fills clockwise while the
         * app waits to see whether the player is still busy, and is full when
         * it takes over. Where the state is not Waiting there is nothing to
         * count, so a full ring stands for a skill at work, a blinking one
         * for Parked, and none at all while DigiAutotap only watches, and for
         * Paused -- a state nobody can see is one people argue about. Where
         * there is a ring there is its track, so that a quarter-full clock
         * reads as a quarter and not as a stray arc.
         */
        private fun drawRing(canvas: Canvas, cx: Float, cy: Float, size: Float, working: Boolean) {
            val share = when (state) {
                HelperState.WAITING -> {
                    val left = Shell.takeOverAt - SystemClock.elapsedRealtime()
                    1.0 - (left.toDouble() / CLOCK_MS).coerceIn(0.0, 1.0)
                }
                HelperState.RUNNING -> if (working) 1.0 else return
                // Parked keeps a whole ring and blinks it, which is the one
                // state a player is meant to notice without looking for it.
                // Off half the cycle, unless they have asked for stillness.
                HelperState.PARKED ->
                    if (!stillness() &&
                        (SystemClock.elapsedRealtime() / (BLINK_MS / 2)) % 2 == 0L) 0.0
                    else 1.0
                else -> return
            }
            val r = size * RING_OF_DOT
            val box = RectF(cx - r, cy - r, cx + r, cy + r)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * RING_STROKE_OF_DOT
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Theme.LIGHT.DOT_RIM
            paint.alpha = TRACK_ALPHA
            canvas.drawCircle(cx, cy, r, paint)
            if (share > 0.0) {
                paint.alpha = 255
                canvas.drawArc(box, -90f, (360.0 * share).toFloat(), false, paint)
            }
            paint.strokeCap = Paint.Cap.BUTT
        }

        /**
         * The plate: one line on a dark, slightly translucent slab with a
         * hairline edge, inset one pixel so that the edge itself stays inside
         * the masked rectangle. First, bold, what DigiAutotap is doing; after a
         * dot, dimmer, the name `classify` answered with -- the same word as
         * the status pill, the notification and the log (design 3.1,
         * decision 7). At the end the heartbeat, a mark that lights for
         * [BLIP_MS] every time a frame is read. The status is the news and
         * is cut last: the name gives way first, then the status itself.
         */
        private fun drawPlate(canvas: Canvas, first: String, now: Long) {
            val p = Theme.LIGHT
            val x = if (place.left) (touch + gap).toFloat() else 0f
            val top = (height - plateH) / 2f
            val h = plateH.toFloat()
            val w = plateW.toFloat()
            val inset = 1f
            val radius = h * 0.5f
            val slab = RectF(x + inset, top + inset, x + w - inset, top + h - inset)

            paint.style = Paint.Style.FILL
            paint.color = p.DOT_PLATE
            paint.alpha = PLATE_ALPHA
            canvas.drawRoundRect(slab, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = p.DOT_PLATE_FG
            paint.alpha = EDGE_ALPHA
            canvas.drawRoundRect(slab, radius, radius, paint)
            paint.alpha = 255

            // The heartbeat at the end of the line: a small disc that fades
            // over BLIP_MS, in room the text never gets.
            val beatX = x + w - h * 0.5f
            val since = now - blipAt
            if (since in 0 until BLIP_MS) {
                paint.style = Paint.Style.FILL
                paint.color = p.DOT_PLATE_FG
                paint.alpha = (255 * (1.0 - since.toDouble() / BLIP_MS)).toInt().coerceIn(0, 255)
                canvas.drawCircle(beatX, top + h / 2f, h * 0.11f, paint)
                paint.alpha = 255
            }

            val padX = h * 0.5f
            val avail = beatX - h * 0.35f - (x + padX)
            text.textSize = h * TEXT_OF_PLATE
            val baseline = top + h / 2f + text.textSize * 0.36f
            text.color = p.DOT_PLATE_FG
            text.alpha = 255
            text.typeface = BOLD
            val status = cut(first, avail)
            canvas.drawText(status, x + padX, baseline, text)

            val name = screenName
            if (name != null && status == first) {
                val used = text.measureText(status)
                text.typeface = REGULAR
                text.alpha = SECOND_ALPHA
                val rest = cut(" · $name", avail - used)
                if (rest.length > 3) canvas.drawText(rest, x + padX + used, baseline, text)
                text.alpha = 255
            }
        }

        /** The line, or as much of it as fits with an ellipsis; the plate never grows. */
        private fun cut(s: String, avail: Float): String =
            TextUtils.ellipsize(s, text, avail, TextUtils.TruncateAt.END).toString()

        // --- the gestures ---------------------------------------------------

        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private var dragging = false
        private val slop = ViewConfiguration.get(svc).scaledTouchSlop
        private val longPress = ViewConfiguration.getLongPressTimeout().toLong()
        private var longPressed = false

        private val onLongPress = Runnable {
            longPressed = true
            openApp()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val lp = layoutParams as WindowManager.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = lp.x; startY = lp.y
                    downAt = SystemClock.elapsedRealtime()
                    dragging = false
                    longPressed = false
                    main.postDelayed(onLongPress, longPress)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        dragging = true
                        main.removeCallbacks(onLongPress)
                    }
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching {
                            svc.getSystemService(WindowManager::class.java)
                                .updateViewLayout(this, lp)
                        }
                        shown = footprint()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(onLongPress)
                    if (dragging) letGo(lp)
                    else if (!longPressed && event.actionMasked == MotionEvent.ACTION_UP) pressed()
                    return true
                }
            }
            return false
        }

        /** Until when the window takes no taps ([Overlay.letThrough]), uptime ms. */
        private var passUntil = 0L

        /** While a gesture is let through, and until the window takes taps again: no log line. */
        private var quiet = false

        private fun passing() = SystemClock.uptimeMillis() < passUntil

        fun passFor(ms: Long) {
            passUntil = SystemClock.uptimeMillis() + ms
            quiet = true
            touchable()
            main.postDelayed({ touchable(); quiet = false }, ms + 20)
        }

        /** Moved by [clearOf], not by the player: [backToPlace] undoes it. */
        private var aside = false

        fun clearOf(fy0: Double, fy1: Double) {
            val d = display()
            val r = Dungeon.gameRectWh(d.width(), d.height())
            val top = r.y0 + fy0 * r.gh
            val bottom = r.y0 + fy1 * r.gh
            fun inTheWay() = footprint().boxes.any { it.y < bottom && it.y + it.h > top }
            if (!inTheWay()) return
            val was = place.fy
            moveTo(Place(place.left, Dot.MEASURED_FY_MIN))
            aside = true
            HelperLog.line(String.format(Locale.ROOT,
                "overlay: moved from fy %.4f to %.4f for a counter under it%s", was, place.fy,
                if (inTheWay()) " -- and it is still in the way" else ""))
        }

        fun backToPlace() {
            if (!aside) return
            aside = false
            moveTo(placeOf(store))
            HelperLog.line(String.format(Locale.ROOT, "overlay: back at fy %.4f", place.fy))
        }

        /** The window at [to], as [letGo] places it, without writing the settings. */
        private fun moveTo(to: Place) {
            place = to
            val lp = layoutParams as? WindowManager.LayoutParams ?: return
            val p = params()
            lp.x = p.x; lp.y = p.y
            runCatching {
                svc.getSystemService(WindowManager::class.java).updateViewLayout(this, lp)
            }
            shown = footprint()
        }

        /**
         * Let go: the dot jumps to the nearest measured place, which is the
         * whole of [drop]. A drag over the middle of the screen would put it
         * on the other edge -- except that the other edge has no measured
         * place on it, so it comes back, and the log says so rather than the
         * dot silently disobeying.
         */
        private fun letGo(lp: WindowManager.LayoutParams) {
            val d = display()
            val wantLeft = lp.x + lp.width / 2 < d.width() / 2
            val fy = (lp.y + lp.height / 2.0) / d.height()
            val moved = wouldMove(wantLeft, fy)
            place = drop(wantLeft, fy)
            store.put(KEY_LEFT, place.left)
            store.put(KEY_FY, place.fy)
            val p = params()
            lp.x = p.x; lp.y = p.y
            runCatching {
                svc.getSystemService(WindowManager::class.java).updateViewLayout(this, lp)
            }
            shown = footprint()
            HelperLog.line(String.format(Locale.ROOT,
                "overlay: dropped at %s fy %.4f, %s",
                if (wantLeft) "the left edge" else "the right edge", fy,
                if (moved) "moved to the nearest measured place, ${where()}"
                else "which is a measured place"))
        }

        /**
         * A tap is the main switch -- the same call the big button in the app
         * and the notification's first action make, so that three switches
         * can never mean three different things. It had a second meaning
         * until 2026-09-22: while Tower & Ruins was arming it, the tap wrote
         * that skill's point instead. The feature left the interface and the
         * tap is one thing again.
         */
        private fun pressed() {
            HelperLog.line("overlay: tapped -- the main switch")
            ActionReceiver.press(svc)
            // At once, not at the end of whatever round is running.
            refresh()
        }

        private fun openApp() {
            HelperLog.line("overlay: long press -- opening the app")
            runCatching {
                svc.startActivity(Intent(svc, MainActivity::class.java)
                                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        private companion object {
            val BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            val REGULAR: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            // The dot's proportions, as fractions of its masked square. The
            // ring's outer edge is at 0.39 of the square where it was at
            // 0.50 -- the player found it a size too big -- and the halo
            // stays inside the square, as everything drawn here must.
            const val HALO_OF_DOT = 0.42f
            const val DISC_OF_DOT = 0.25f
            const val RIM_OF_DOT = 0.03f
            const val RING_OF_DOT = 0.345f
            const val RING_STROKE_OF_DOT = 0.05f
            const val HALO_ALPHA = 150
            const val RIM_ALPHA = 235
            const val TRACK_ALPHA = 70
            // The plate's.
            const val PLATE_ALPHA = 222
            const val EDGE_ALPHA = 30
            const val SECOND_ALPHA = 178
            const val TEXT_OF_PLATE = 0.62f
        }
    }
}
