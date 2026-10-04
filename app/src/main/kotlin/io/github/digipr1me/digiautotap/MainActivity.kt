package io.github.digipr1me.digiautotap

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.digipr1me.digiautotap.core.Activation
import io.github.digipr1me.digiautotap.core.Chain
import io.github.digipr1me.digiautotap.core.Game
import io.github.digipr1me.digiautotap.core.HelperLog
import io.github.digipr1me.digiautotap.core.HelperState
import io.github.digipr1me.digiautotap.core.MainSwitch
import io.github.digipr1me.digiautotap.core.Preset
import io.github.digipr1me.digiautotap.core.Shell
import io.github.digipr1me.digiautotap.core.SkillSettings
import io.github.digipr1me.digiautotap.core.SkillStats
import io.github.digipr1me.digiautotap.core.Stored
import io.github.digipr1me.digiautotap.core.Unlock
import io.github.digipr1me.digiautotap.core.Updates
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * One page (PLAN_ANDROID_DESIGN.md 2.8, the layout of 2026-09-22): the
 * status and the main switch, the tasks with their switches, Today, and a
 * set-up line at the foot, and the Discord tile under it. Settings is the
 * cog in the app bar's top right corner (called More until the evening of
 * 2026-09-23, then at the left end for a day) and opens as a page with a
 * back arrow at the left end, the log and About as pages over Settings (the log was a
 * second bar button until 2026-09-23, when the player asked for a tile on
 * that page instead); a task
 * opens as a sheet from the bottom over the page it came from, and so do
 * the mode, the order and the set-up. There were four tabs along the
 * bottom until that day -- DigiAutotap, Tasks, Log, More -- and the player
 * called the result a prototype: the same person opens the app to see
 * whether it runs and what ran, and that was two tabs away from each
 * other.
 *
 * The Activity holds nothing the core needs. It can die and come back
 * (rotation, memory, a change of light or dark) at any time; the loop is
 * [CoreService]'s, the switch is core's `MainSwitch`, what is on the screen
 * is [Status]'s and what DigiAutotap is doing is [Shell]'s. Every page reads
 * those afresh -- a second copy in here is what `wait_while_paused` already
 * cost once (NOTES.md, the Summons skill's Stop button).
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: SettingsStore
    private lateinit var ui: Ui

    private lateinit var root: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var barLine: View
    private lateinit var barBack: ImageView
    private lateinit var barTitle: TextView
    private lateinit var barPill: FrameLayout
    private lateinit var barSettings: View
    private lateinit var host: FrameLayout

    /** null is the one page; "settings" opens over it, "log" and "about" over Settings. */
    private var page: String? = null
    private var onboarding = -1

    /**
     * The status bar's height, as the window last heard it: the bar's top
     * padding while there is a bar, the page's while there is not
     * (onboarding). Nought until the first insets arrive, which is before
     * the first frame is drawn.
     */
    private var insetTop = 0

    /** Set by whichever page is showing; null where nothing on it moves. */
    private var refresh: (() -> Unit)? = null
    private var logSink: ((String) -> Unit)? = null
    private var logHeld = false
    private var scroll: ScrollView? = null

    /** The update popup while it is up, so that an opening does not stack a second one on it. */
    private var updateDialog: Dialog? = null

    private val onStatus: (Status) -> Unit = { main.post { refresh?.invoke() } }
    private val onSwitch: (Boolean) -> Unit = { main.post { refresh?.invoke() } }
    private val onLog: (String) -> Unit = { line -> main.post { logSink?.invoke(line) } }
    // The whole page, not its status panel: the set-up line and every ask
    // are built from the bound service (Setup.asks).
    private val onBound: () -> Unit = { main.post { if (!isFinishing && !isDestroyed) render() } }

    /**
     * The chosen night mode, written into the context the Activity is built
     * from, so that res/values-night decides this window's background the
     * same way [Theme] decides the colours the pages draw. Android reads the
     * style out of the manifest before `onCreate`, which is why the choice
     * has to be here and cannot be made on a page.
     */
    override fun attachBaseContext(base: Context) {
        Theme.load(SettingsStore(base))
        super.attachBaseContext(Theme.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The page is laid out under the status and navigation bars, and
        // keeps out of them itself (buildChrome), on every Android alike
        // since 2026-09-28: Android 15 does this to every app whatever it
        // asks, and the ones before drew a grey status bar of their own
        // over the page -- Theme.Material.Light's, which is no colour of
        // this app's. res/values/theme.xml says which colour the bars'
        // icons take on which design.
        window.setDecorFitsSystemWindows(false)
        store = SettingsStore(this)
        ui = Ui(this)
        CoreService.channel(this)
        if (savedInstanceState == null) {
            // The player's decision of 2026-09-19: on when the app is opened.
            // Through the dot's own Start, which waits for a stopped task
            // still letting go (CoreService.startLogic).
            CoreService.startLogic("the app opening")
            if (!store.bool(ONBOARDING_SEEN, false)) onboarding = 0
        } else {
            page = savedInstanceState.getString("page", null)
            onboarding = savedInstanceState.getInt("onboarding", -1)
        }
        Supporter.load(this) { main.post { render() } }
        buildChrome()
        render()
        // Once per opening, not per rebuild: a change of light or dark
        // builds the Activity again with a saved state.
        if (savedInstanceState == null) {
            checkForUpdate(asked = false)
            census(this)
            opened()
        }
    }

    /**
     * At every opening: a shared log ZIP an hour old goes ([LogShare.sweep]).
     * Until 1.3's fourth candidate this also sent an open report on and asked
     * about a crash since the last opening; the app sends nothing by itself
     * now and asks nothing after a crash -- the crash is in the log, and in
     * the crash.txt of a shared ZIP (notes/reports.md, "Nothing is sent and
     * no picture of the game is kept: the player shares the log").
     */
    private fun opened() {
        thread(name = "share-sweep") { LogShare.sweep(this) }
    }

    /**
     * Back in front after the game, the home screen or a locked display: that
     * is an opening too, and the player's rule of 2026-09-24 is that a newer
     * version is said at every opening. What this process already found is
     * said at once; otherwise GitHub is asked again.
     */
    override fun onRestart() {
        super.onRestart()
        val found = newer
        if (found != null) offerUpdate(found) else checkForUpdate(asked = false)
        census(this)
        opened()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        page?.let { out.putString("page", it) }
        out.putInt("onboarding", onboarding)
    }

    override fun onResume() {
        super.onResume()
        Status.listen(onStatus)
        MainSwitch.listen(onSwitch)
        HelperLog.listen(onLog)
        DigiAutotapService.bound += onBound
        // The app is in front, so the system allows this start whatever the
        // battery settings say; the service's own start may have been
        // refused. A core the player stopped (the app or the notification) stays
        // stopped: startCore is where that is decided, for every caller.
        if (DigiAutotapService.instance != null) DigiAutotapService.startCore(this, "app opened")
        // The file as it stands now, not as it stood when the Activity was
        // made: a store reads the whole file once and answers from that copy
        // (SettingsStore), and the core writes the day's counts, the quest
        // lock and the rest through stores of its own. Since Android 12 the
        // back key only moves the app behind, so the same Activity comes back
        // for hours. Seen on LDPlayer on 2026-09-29 (PLAN_EX_MISSIONS.md
        // EX3): the TODAY card said "5 EX mission claims" after a second
        // "Claim now" had made it 7, and said 7 only once a display change
        // rebuilt the Activity. notes/director.md, "A page that keeps its
        // store shows the numbers of the moment it was opened".
        store = SettingsStore(this)
        // Every ask is asked again on every opening (5_SHELL 3.2).
        render()
    }

    override fun onPause() {
        Status.unlisten(onStatus)
        MainSwitch.unlisten(onSwitch)
        HelperLog.unlisten(onLog)
        DigiAutotapService.bound -= onBound
        super.onPause()
    }

    /**
     * The update popup goes with the app, so that every opening is asked
     * afresh ([onRestart]): left up, it came back ticked "Don't remind me"
     * as the player had left it -- seen on LDPlayer, 2026-09-24.
     */
    override fun onStop() {
        updateDialog?.dismiss()
        super.onStop()
    }

    // Android's own, and deprecated in favour of an AndroidX dispatcher the
    // app does not carry. It is still what the system calls while
    // enableOnBackInvokedCallback is off, which is the default.
    @Deprecated("Superseded by OnBackPressedDispatcher, which needs AndroidX")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (page == null) super.onBackPressed() else goBack()
    }

    /** The bar's arrow and the system's back, on a page: the log and About go back to Settings, Settings to the page. */
    private fun goBack() {
        page = if (page == "about" || page == "log") "settings" else null
        render()
    }

    // --- the chrome around every page ---------------------------------------

    private fun buildChrome() {
        root = ui.column().apply { setBackgroundColor(ui.p.BG) }
        bar = ui.row().apply {
            setBackgroundColor(ui.p.SURFACE)
            setPadding(ui.dp(Ui.BAR_SIDE), ui.dp(10), ui.dp(Ui.BAR_SIDE), ui.dp(10))
        }
        // The window is laid out under the system bars (onCreate), so what
        // they cover is the app's to keep out of: the app bar runs on under
        // the status bar in its own colour and carries its height as
        // padding -- the page does while the bar is away (onboarding, in
        // render) -- and the root keeps clear of the navigation bar and of
        // the keyboard, which is what lets the supporter code's field rise
        // above the keys.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val around = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            insetTop = insets.getInsets(WindowInsets.Type.statusBars()).top
            v.setPadding(around.left, if (onboarding >= 0) insetTop else 0, around.right, around.bottom)
            bar.setPadding(ui.dp(Ui.BAR_SIDE), ui.dp(10) + insetTop, ui.dp(Ui.BAR_SIDE), ui.dp(10))
            insets
        }
        barBack = ui.barBack { goBack() }
        barTitle = ui.barTitle("DigiAutotap")
        barPill = FrameLayout(this)
        // The one thing that is not on the page: Settings. A button in the bar
        // rather than a tab, and only there on the page itself -- a page
        // that is open has the back arrow instead, at the left end. The cog
        // stands in the top right corner, after the state chip, since
        // 2026-09-24 (the player's call; it stood at the left end for the
        // day before, and was More beside the chip until then).
        barSettings = ui.barButton(R.drawable.ic_settings, "Settings") { page = "settings"; render() }
        bar.addView(barBack)
        bar.addView(ui.grow(barTitle))
        bar.addView(barPill)
        bar.addView(barSettings)
        root.addView(bar)
        barLine = ui.divider()
        root.addView(barLine)

        host = FrameLayout(this)
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun render() {
        refresh = null
        logSink = null
        scroll = null
        host.removeAllViews()
        val chrome = if (onboarding >= 0) View.GONE else View.VISIBLE
        listOf(bar, barLine).forEach { it.visibility = chrome }
        // The status bar's rows are the bar's while there is one and the
        // page's while there is not, in the onboarding page's own colour.
        root.setPadding(root.paddingLeft, if (onboarding >= 0) insetTop else 0,
                        root.paddingRight, root.paddingBottom)
        root.setBackgroundColor(if (onboarding >= 0) ui.p.SURFACE else ui.p.BG)
        if (onboarding >= 0) return pageOnboarding()
        val open = page
        barBack.visibility = if (open == null) View.GONE else View.VISIBLE
        barSettings.visibility = if (open == null) View.VISIBLE else View.GONE
        // Without the arrow the title stands 14 dp in, level with the cog's
        // box at the other end; after the arrow's square it needs no more.
        barTitle.setPadding(if (open == null) ui.dp(14 - Ui.BAR_SIDE) else 0, 0, 0, 0)
        barPill.removeAllViews()
        barTitle.text = when (open) {
            "about" -> "About"
            "log" -> "Log"
            "settings" -> "Settings"
            else -> "DigiAutotap"
        }
        when (open) {
            "about" -> pageAbout()
            "log" -> pageLog()
            "settings" -> pageSettings()
            else -> pageHome()
        }
        // The state chip belongs to the app bar and not to a page, so it is
        // drawn again whatever page is open: every page's refresh is wrapped
        // rather than asked to remember it. Onboarding has no bar at all.
        val ofPage = refresh
        refresh = {
            if (onboarding < 0) {
                barPill.removeAllViews()
                barPill.addView(ui.pill(Shell.state(), small = true), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            ofPage?.invoke()
        }
        refresh?.invoke()
    }

    /** A scrolling page body, and the column the panels go into. */
    private fun scroller(): LinearLayout {
        val sv = ScrollView(this).apply { isFillViewport = true }
        val col = ui.column().apply {
            setPadding(ui.dp(Ui.PAD), ui.dp(2), ui.dp(Ui.PAD), ui.dp(20))
        }
        sv.addView(col)
        host.addView(sv)
        scroll = sv
        return col
    }

    private fun card(col: LinearLayout, eyebrow: String? = null): LinearLayout {
        val c = ui.card()
        col.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                 ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(8)
        })
        if (eyebrow != null) c.addView(ui.eyebrow(eyebrow))
        return c
    }

    // --- the page ---------------------------------------------------------------

    private fun pageHome() {
        val col = scroller()
        val asks = Setup.asks(this)

        // The status panel: one sentence, and under it the main switch with
        // the mode beside it. It carried three fields as well until
        // 2026-09-22 -- screen, mode, tasks -- and the player struck them:
        // the sentence already names the screen, the mode button names the
        // mode, and the tasks are the list underneath.
        val status = card(col)
        val sentence = ui.statusLine("")
        val buttonSlot = ui.row()
        status.addView(sentence)
        status.addView(buttonSlot, marginTop(10))

        // The tasks, on the page and not on a tab of their own: seven names
        // and their switches, the passive helper last. The line under a name
        // -- "no point set yet", "no mode chosen" -- went with the tab: the
        // dot in front is amber where something holds a task back, and the
        // reason is in the task's own sheet, beside the control that mends
        // it.
        col.addView(ui.sectionHead("Tasks"), marginTop(16))
        val list = ui.listBox()
        col.addView(list)
        Skills.ALL.forEachIndexed { i, r ->
            if (i > 0) list.addView(ui.hairline())
            list.addView(taskRow(r))
        }

        // What the day has come to, off the same counters each task's own
        // sheet reads (SkillStats). A task that has done nothing today has
        // no line: a column of noughts says less than one number does.
        val counted = card(col, "Today")
        val lines = Skills.ALL.mapNotNull { r ->
            SkillStats.sentence(r.key, SkillStats.read(store, r.key, today()))?.let { r.name to it }
        }
        if (lines.isEmpty()) counted.addView(ui.mono("nothing counted yet", 11f, ui.p.TEXT_2))
        else for ((name, line) in lines) counted.addView(ui.field(name.lowercase(), line))

        // The set-up, as one line at the foot: green while the three asks
        // are answered, amber with the first missing one's name while they
        // are not. The card it used to be stood between the switch and the
        // tasks every day of the app's life, for three questions that are
        // answered once; what still has to be loud is the moment the player
        // presses Start with one of them open, and that is [press].
        col.addView(setupLine(asks), marginTop(8))
        // The free ads, a card of their own under a heading since 2026-10-02
        // ([freeAdsCard]), where the Ad Skip Pass's line stood alone. The
        // heading said "Free ads" until 2026-10-04 and says "Ad Rewards"
        // since, the player's word (PLAN_REPORT_RAUS.md 2.11).
        col.addView(ui.sectionHead("Ad Rewards"), marginTop(16))
        freeAdsAt = freeAdsCard().also { col.addView(it) }
        if (toFreeAds) {
            toFreeAds = false
            val sv = scroll
            val at = freeAdsAt
            if (sv != null && at != null) sv.post { sv.smoothScrollTo(0, maxOf(0, at.top - ui.dp(40))) }
        }

        // The way to the server, as a tile and not the text link it was
        // until 2026-09-23 -- the player called the link too quiet. It is
        // where every question goes since that day (the Settings page's Feedback
        // row, which opened GitHub, is gone), and since 1.3 where a bug comes
        // too: the player shares the log there (Settings, Log, Share). The
        // line is the player's own sentence of 2026-10-04.
        col.addView(ui.tile(R.drawable.ic_discord, ui.p.ON_DISCORD, ui.p.DISCORD, null,
                            "Join the Discord", "If you need help or have feedback of any kind.",
                            R.drawable.ic_external) { openInBrowser(DISCORD_INVITE) },
                    marginTop(8))

        refresh = {
            val state = Shell.state()
            sentence.text = Shell.sentence(state)
            buttonSlot.removeAllViews()
            val stopped = state == HelperState.STOPPED
            buttonSlot.addView(ui.bigButton(if (stopped) "Start" else ActionReceiver.TURN_OFF,
                                            if (stopped) R.drawable.ic_play else R.drawable.ic_stop) {
                press(state)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(Ui.SWITCH_H), 1f)
            })
            buttonSlot.addView(ui.ghostButton(modeLabel() + "  ▾", modeDot()) { modeSheet() })
            buttonSlot.addView(ui.ghostIcon(R.drawable.ic_game, "Open Digimon UP") { openGame() })
            // The three-second clock is the one thing on this page that
            // moves by itself, so it is the one thing that asks to be drawn
            // again (PLAN_ANDROID_DESIGN.md 4.1) -- and only while it has
            // time left to show. Reposting on the state alone never stops:
            // measured in LDPlayer, a page left in Waiting redrew itself
            // four times a second for as long as it was open, and a window
            // that never goes idle is a window `uiautomator dump` cannot
            // read either.
            if (state == HelperState.WAITING &&
                Shell.takeOverAt > android.os.SystemClock.elapsedRealtime()) {
                main.postDelayed({ refresh?.invoke() }, 250)
            }
        }
    }

    /**
     * The big button: Start while the service is off, Turn off in every
     * other state -- Stopped and Parked included. The player's decision of
     * 2026-09-23: in the app the game is not in front anyway, so a pause
     * here is a pause of nothing, and the one button does what it says.
     * Stop and Start of the task are the dot's (and the notification's),
     * where the game is; and a parked director also moves on by itself
     * when the screen changes. The button said Stop until 2026-10-02, when
     * that word went to the dot (PLAN_BEFUNDE_1_3.md N5 b).
     *
     * Start with one of the three asks still open goes to the set-up sheet
     * first, which is where the player learns what is missing -- and, where
     * it is only a warning, can still start anyway. Asked of the system at
     * the press and not off the page's own list: the player may have come
     * back from the settings since the page was built.
     */
    private fun press(state: HelperState) {
        if (state != HelperState.STOPPED) {
            ActionReceiver.stop(this, "the app")
            render()
            return
        }
        if (Setup.asks(this).any { !it.ok }) return setupSheet(start = true)
        pressStart()
    }

    /** Start, and what stood in the way said where the player is looking. */
    private fun pressStart() {
        ActionReceiver.press(this, "the app")?.let { why ->
            Toast.makeText(this, "Not started: $why", Toast.LENGTH_LONG).show()
        }
        render()
    }

    /**
     * The gamepad beside the mode: the game brought to the front, the way
     * the launcher's icon does it -- the launcher's own two flags, so a
     * running game comes back as it was and a game that is not running
     * starts. Whether it is running is not asked: this app cannot see
     * another app's process without a permission it does not hold, and the
     * launcher's intent needs no answer. The service's package first,
     * because it may have found the game in front rather than in the list.
     */
    private fun openGame() {
        val game = DigiAutotapService.instance?.gamePackage ?: DigiAutotapService.findGame(this)
        val launch = game?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            Toast.makeText(this, "Digimon UP is not installed on this phone", Toast.LENGTH_SHORT).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(launch) }
            .onSuccess { HelperLog.line("game opened from the app: $game") }
            .onFailure {
                HelperLog.line("opening the game failed: $it")
                Toast.makeText(this, "Digimon UP could not be opened", Toast.LENGTH_SHORT).show()
            }
    }

    private fun modeLabel(): String =
        if (store.str(SkillSettings.MODE_KEY, SkillSettings.MODE_DEFAULT) == SkillSettings.MODE_FULL)
            "Fully automatic" else "Semi-automatic"

    /** The overlay dot's colour in the mode that holds, for the mode's button. */
    private fun modeDot(): Int =
        if (store.str(SkillSettings.MODE_KEY, SkillSettings.MODE_DEFAULT) == SkillSettings.MODE_FULL)
            ui.p.DOT_ON else ui.p.DOT_SEMI

    private fun setupLine(asks: List<Ask>): View {
        val open = asks.filter { !it.ok }
        val ok = open.isEmpty()
        val fg = if (ok) ui.p.PILL_OK_FG else ui.p.PILL_PAUSE_FG
        val line = ui.row().apply {
            background = ui.round(if (ok) ui.p.SURFACE else ui.p.PILL_PAUSE_BG,
                                  if (ok) ui.p.LINE else ui.p.PILL_PAUSE_EDGE)
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            isClickable = true
            setOnClickListener { setupSheet(start = false) }
        }
        line.addView(if (ok) ui.okChip() else ui.chip("${open.size} missing", ui.p.PILL_PAUSE_FG,
                                                       ui.p.PILL_PAUSE_BG, ui.p.PILL_PAUSE_EDGE))
        line.addView(ui.grow(ui.mono(
            if (ok) "set-up complete, checked on every opening"
            else open.joinToString(", ") { it.label.lowercase() } + " -- tap to fix",
            11f, if (ok) ui.p.TEXT_2 else fg)).apply { setPadding(ui.dp(8), 0, ui.dp(6), 0) })
        line.addView(ui.chevron())
        return line
    }

    /** The Ad Rewards card on the page, for a task sheet's pointer to scroll to ([adsPointer]). */
    private var freeAdsAt: View? = null

    /**
     * A sheet's pointer was tapped: the page that the sheet's dismissal
     * draws anew ([bottomSheet]) scrolls to the card once it is laid out.
     */
    private var toFreeAds = false
    /**
     * The free ads, as one card on the main page (2026-10-02, design A of
     * staging/mockups/supporter_ads_mockup.html, the player's choice), cut
     * down on 2026-10-03 to what the app does with a free ad: it takes the
     * reward with the game's Ad Skip Pass, and without the pass it leaves the
     * ad alone (question 15 of PLAN_ABSCHLUSS_1_3.md; notes/ads.md, "The app
     * never touches an ad, and nothing that closed one ships"). At the head
     * the pass with its sentence and its switch, everybody's
     * (SkillSettings.AD_PASS_KEY); under it the tasks it is for, the three
     * page switches of 2026-09-30 under the keys they had (Stored.AD_PICKS),
     * dimmed while the pass is off and still the player's to set.
     */
    private fun freeAdsCard(): View {
        val slot = ui.column()
        fun fill() {
            slot.removeAllViews()
            val ads = Stored.freeAds(store)
            // 1 dp inside the border, so that the row with a background of
            // its own (the one that throws the switch) leaves the line standing.
            val card = ui.listBox().apply { setPadding(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1)) }

            val head = ui.row().apply {
                gravity = Gravity.TOP
                setPadding(ui.dp(10), ui.dp(11), ui.dp(10), ui.dp(10))
            }
            head.addView(ui.icon(R.drawable.ic_ads, 20, ui.p.STATE_FG).apply {
                background = ui.round(ui.p.STATE_BG, ui.p.STATE_EDGE)
                setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
                layoutParams = LinearLayout.LayoutParams(ui.dp(38), ui.dp(38))
            })
            val words = ui.column()
            words.addView(ui.taskName("Ad Skip Pass"))
            words.addView(
                if (ads.pass) ui.chip("on", ui.p.PILL_OK_FG, ui.p.PILL_OK_BG, ui.p.PILL_OK_EDGE)
                else ui.chip("off", ui.p.PILL_NEUTRAL_FG, ui.p.PILL_NEUTRAL_BG, ui.p.PILL_NEUTRAL_EDGE),
                wrapTop(3))
            words.addView(ui.hint(
                "With the game's pass, DigiAutotap takes the free rewards of the tasks below. " +
                    "Without it, it leaves the free ads alone."), marginTop(4))
            head.addView(ui.grow(words).apply {
                (layoutParams as LinearLayout.LayoutParams).apply {
                    leftMargin = ui.dp(10)
                    rightMargin = ui.dp(8)
                }
            })
            val pass = ui.switch(ads.pass) { v ->
                store.put(SkillSettings.AD_PASS_KEY, v)
                HelperLog.line("ad skip pass: " + if (v) "on" else "off")
                fill()
            }
            head.addView(pass)
            card.addView(ui.tappable(head) { pass.performClick() })

            // Which tasks take their free ads: always the player's to set,
            // and dimmed while the pass is off and none is taken.
            val where = ui.row().apply {
                setPadding(ui.dp(58), 0, ui.dp(10), ui.dp(6))
                if (!ads.tap) alpha = 0.5f
            }
            where.addView(ui.mono("where", 10f, ui.p.TEXT_2).apply { setPadding(0, 0, ui.dp(4), 0) })
            for ((task, key) in Stored.AD_PICKS) {
                val on = store.bool(key, false)
                where.addView(adPick(Skills.row(task).name, on) {
                    store.put(key, !on)
                    HelperLog.line("free ads in ${Skills.row(task).name}: " + if (on) "off" else "on")
                    fill()
                })
            }
            card.addView(where)

            slot.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                         ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fill()
        return slot
    }

    /** One task under the Ad Rewards card's pass: a round chip, green while its free ads are taken. */
    private fun adPick(name: String, on: Boolean, onClick: () -> Unit): View {
        val chip = ui.hint(name).apply {
            textSize = 12f
            setTextColor(if (on) ui.p.PILL_OK_FG else ui.p.PILL_NEUTRAL_FG)
            background = if (on) ui.round(ui.p.PILL_OK_BG, ui.p.PILL_OK_EDGE, radius = 14)
                         else ui.round(ui.p.PILL_NEUTRAL_BG, ui.p.PILL_NEUTRAL_EDGE, radius = 14)
            setPadding(ui.dp(9), ui.dp(4), ui.dp(9), ui.dp(4))
        }
        // The chip is the picture; the touch target is the 40 dp around it.
        return FrameLayout(this).apply {
            minimumHeight = ui.dp(40)
            setPadding(ui.dp(3), 0, ui.dp(3), 0)
            addView(chip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                   ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /**
     * What a task sheet says where the Ad Rewards card's pick [key] is its
     * own (2026-10-02): the switch that stood at the head of the page is on
     * the main page now, and this line says how the task's free ads go and
     * leads there.
     */
    private fun adsPointer(key: String, d: Dialog): View {
        val ads = Stored.freeAds(store)
        val how = if (store.bool(key, false) && ads.tap) "Free ads: taken with your Ad Skip Pass."
                  else "Free ads: left alone."
        val box = ui.row().apply {
            background = ui.round(ui.p.STATE_BG, ui.p.STATE_EDGE)
            setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9))
            minimumHeight = ui.dp(Ui.TOUCH)
            isClickable = true
            setOnClickListener {
                toFreeAds = true
                d.dismiss()
            }
        }
        box.addView(ui.icon(R.drawable.ic_ads, 16, ui.p.STATE_FG))
        box.addView(ui.grow(ui.hint("$how Set on the main page.").apply {
            setTextColor(ui.p.STATE_FG)
            setPadding(ui.dp(8), 0, ui.dp(6), 0)
        }))
        box.addView(ui.icon(R.drawable.ic_chevron, 16, ui.p.STATE_FG))
        return box
    }

    /**
     * The three asks (5_SHELL 3.2), as a sheet: from the set-up line at the
     * foot of the page, and from Start while one of them is open. The
     * second is the one that matters -- "Start" with the accessibility
     * service off used to start nothing and say so only in the log -- and
     * it still lets the player start anyway, because a missing notification
     * or battery exception is a warning and not a wall.
     *
     * The accessibility service is the wall: without it nothing can see the
     * game or tap it, and "Start anyway" offered over it started nothing
     * and said so only in the log -- the Poco on 2026-10-01, 20:24:02 and
     * :06, with the service switched on in the settings and not running
     * (notes/director.md, "The bound service is the answer, and a start
     * without it is refused out loud"). So while it is the one missing,
     * the sheet says why there is no start and offers only the way to it.
     */
    private fun setupSheet(start: Boolean): Unit = bottomSheet { sheet, d ->
        val asks = Setup.asks(this)
        val missing = asks.firstOrNull { !it.ok }
        val wall = missing?.key == "acc"
        sheet.addView(ui.title(when {
            start -> "Not ready to start"
            else -> "Set-up"
        }))
        sheet.addView(ui.hint(when {
            missing == null -> "Everything DigiAutotap needs is in place."
            start && wall -> missing.missing + ". DigiAutotap cannot start without it."
            start -> missing.missing + ". You can start anyway."
            else -> missing.missing + "."
        }), marginTop(4))
        for (a in asks) {
            sheet.addView(rule(8))
            val r = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            r.addView(TextView(this).apply {
                text = a.glyph
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(ui.p.STATE_FG)
                background = ui.round(ui.p.STATE_BG, ui.p.STATE_EDGE, radius = 6)
                layoutParams = LinearLayout.LayoutParams(ui.dp(28), ui.dp(28))
                    .apply { rightMargin = ui.dp(10) }
            })
            val left = ui.column()
            left.addView(ui.name(a.label))
            // The one ask whose answer is not yes or no: switched on in the
            // settings and not running reads as neither, and the sentence
            // that says so belongs where the cross is.
            if (a.key == "acc") left.addView(ui.sub(Setup.accessibilityState(this)))
            r.addView(ui.grow(left))
            r.addView(if (a.ok) ui.okChip() else ui.missingChip())
            if (!a.ok) r.addView(ui.outButton("Fix") { a.fix(this) }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(8) }
            })
            sheet.addView(r)
            // Same second way out as the onboarding card's, for the player
            // who meets the greyed-out switch on a later opening.
            if (!a.ok) a.alt?.let { alt ->
                sheet.addView(ui.textButton(alt.label) { alt.go(this) })
            }
        }
        sheet.addView(ui.hint("Checked again every time you open the app."), marginTop(12))
        val foot = ui.row().apply { setPadding(0, ui.dp(12), 0, 0) }
        if (start && wall) {
            foot.addView(ui.primaryButton(missing!!.button) { missing.fix(this) }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                         ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        } else if (start && missing != null) {
            foot.addView(ui.outButton("Start anyway") {
                d.dismiss()
                pressStart()
            }.apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            foot.addView(ui.primaryButton(missing.button) { missing.fix(this) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = ui.dp(8) }
            })
        } else {
            foot.addView(ui.primaryButton("Done") { d.dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                         ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }
        sheet.addView(foot)
    }

    /**
     * The mode, as a sheet from the button beside the main switch: the two
     * choices, what each does, and in the fully automatic one the order
     * with its Edit. It was a card of its own on the page until 2026-09-22.
     */
    private fun modeSheet(): Unit = bottomSheet { sheet, d ->
        val full = store.str(SkillSettings.MODE_KEY, SkillSettings.MODE_DEFAULT) ==
            SkillSettings.MODE_FULL
        sheet.addView(ui.eyebrow("Mode"))
        sheet.addView(ui.segmented(listOf("Semi-automatic", "Fully automatic"),
                                   if (full) 1 else 0,
                                   listOf(Ui.Note("blue dot", ui.p.DOT_SEMI),
                                          Ui.Note("green dot", ui.p.DOT_ON))) { i ->
            val chosen = if (i == 0) SkillSettings.MODE_SEMI else SkillSettings.MODE_FULL
            store.put(SkillSettings.MODE_KEY, chosen)
            HelperLog.line("mode: " + if (i == 0) "semi-automatic" else "fully automatic")
            // The dot says the mode now, not at the end of a round that can
            // be a minute long.
            Overlay.mode(chosen == SkillSettings.MODE_FULL)
            d.dismiss()
            modeSheet()
        })
        sheet.addView(ui.hint(
            if (full) "Goes through your tasks in the order below, then back to the main screen."
            else "Only helps on the screen you have open, and leaves you there."), marginTop(8))
        if (full) {
            sheet.addView(chainBox(), marginTop(10))
            sheet.addView(ui.outButton("Edit order") { d.dismiss(); orderSheet() }, wrapTop(8))
        }
        sheet.addView(ui.hint("DigiAutotap watches the screen and starts the matching task " +
            "once you have not touched the game for three seconds. Apart from its tasks, it " +
            "only taps to cancel \"Exit the game?\" and to close the Stage Failed banner."),
            marginTop(12))
        sheet.addView(ui.primaryButton("Done") { d.dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                     ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(12) }
        })
    }

    /**
     * The fully automatic order as the Mode panel shows it: one numbered
     * line per step. It was one wrapped sentence with arrows in it until the
     * console design; a list that is read to find the third step reads
     * better as a list.
     */
    private fun chainBox(): LinearLayout {
        val box = ui.column()
        val steps = Stored.chainSteps(store)
        if (steps.isEmpty()) {
            box.addView(ui.mono("no step in the chain yet", 11f, ui.p.TEXT_2))
            return box
        }
        steps.forEachIndexed { i, (key, _) ->
            val r = ui.row().apply { setPadding(0, ui.dp(1), 0, ui.dp(1)) }
            r.addView(ui.mono(String.format(java.util.Locale.ROOT, "%02d", i + 1), 11f,
                              ui.p.PRIMARY, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ui.dp(24), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            r.addView(ui.grow(ui.mono((SkillSettings.CHAIN_NAMES[key] ?: key).lowercase(),
                                      11f, ui.p.TEXT)))
            // A step whose task is switched off is skipped, and says so here
            // (PLAN_BEFUNDE_1_3.md N3 b: Gekkomon Run stood first and was
            // off, since Chef's Special had been switched on).
            if (Skills.stepOff(key, store, Supporter.unlocked)) r.addView(ui.mono("off", 11f, ui.p.PAUSE))
            box.addView(r)
        }
        if (steps.any { (key, _) -> Skills.stepOff(key, store, Supporter.unlocked) }) {
            box.addView(ui.mono("a step marked off is skipped: its task is switched off", 11f, ui.p.TEXT_2).apply {
                setPadding(ui.dp(24), ui.dp(1), 0, 0)
            })
        }
        box.addView(ui.mono("then back to the main screen", 11f, ui.p.TEXT_2).apply {
            setPadding(ui.dp(24), ui.dp(1), 0, 0)
        })
        return box
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == Setup.REQ_NOTIFY && results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            // Refused once, the system stops asking; the switch lives in the app's settings.
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                              .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        render()
    }

    // --- the tasks ------------------------------------------------------------

    /**
     * One row of the list: the dot, the name, the switch, a chevron. The
     * dot answers "is it in" and, since the line under the name went, "is
     * anything holding it back" -- amber for a task that is switched on and
     * cannot run yet (no point set, no mode chosen, a supporter code
     * missing), which is what the amber line used to say in words.
     */
    private fun taskRow(r: SkillRow): View {
        val unlocked = Supporter.unlocked
        val on = Skills.included(r, store, unlocked)
        val locked = r.supporter && unlocked != true
        val held = Skills.holdBack(r, store, unlocked).isNotEmpty()

        val box = ui.row().apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(10), ui.dp(4)) }
        val colour = when {
            locked || (on && held) -> ui.p.PAUSE
            on -> ui.p.OK
            else -> ui.p.DISABLED_BG
        }
        box.addView(ui.navDot(colour, halo = on && Shell.state() == HelperState.RUNNING))
        box.addView(ui.grow(ui.taskName(r.name)).apply { setPadding(ui.dp(10), 0, ui.dp(6), 0) })
        box.addView(ui.switch(on, enabled = !locked) { v -> include(r, v) })
        box.addView(ui.chevron().apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = ui.dp(6)
        })
        return ui.tappable(box) { taskSheet(r) }
    }

    /**
     * A row's switch, thrown ([Skills.include]). A minigame switched on
     * switches the other one off (PLAN_SKEWER.md 3.3); the page says which,
     * and why, and draws the list again, so that the other row's switch is
     * seen going off -- after the tap that threw this one has finished. The
     * list is drawn again, too, where switching on started a row's day again
     * (PLAN_ABSCHLUSS_1_3.md A2): its dot goes from amber to green.
     */
    private fun include(r: SkillRow, on: Boolean) {
        val before = Skills.holdBack(r, store, Supporter.unlocked)
        val off = Skills.include(r, store, on)
        if (off.isNotEmpty()) {
            Toast.makeText(this, "${off.joinToString(" and ") { it.name }} switched off: only one " +
                "minigame runs at a time", Toast.LENGTH_LONG).show()
        }
        if (off.isNotEmpty() || Skills.holdBack(r, store, Supporter.unlocked) != before) host.post { render() }
    }

    /**
     * A task, as a sheet over the page: its switch and what holds it back
     * at the head, then the same fields the PC has, Today, and the sentence
     * that says on which screen it wakes up. A page with a back arrow until
     * 2026-09-22; a sheet leaves the page where it was, which is the point
     * of having one page.
     */
    private fun taskSheet(r: SkillRow): Unit = bottomSheet { sheet, d ->
        val unlocked = Supporter.unlocked
        val locked = r.supporter && unlocked != true
        val on = Skills.included(r, store, unlocked)
        val hold = Skills.holdBack(r, store, unlocked)

        // What holds the task back, and -- where it is the day's (Skills.dayHold)
        // -- the way out before the reset under it. Both go when the switch
        // has started the day again (PLAN_ABSCHLUSS_1_3.md A2), without the
        // sheet being opened anew.
        val holdLine = ui.sub(hold.lowercase(), amber = true)
        val againLine = ui.sub("")
        fun holdShown(on: Boolean) {
            val now = Skills.holdBack(r, store, Supporter.unlocked)
            holdLine.text = now.lowercase()
            holdLine.visibility = if (now.isEmpty()) View.GONE else View.VISIBLE
            val day = now.isNotEmpty() && now == Skills.dayHold(r, store)
            againLine.text = Skills.againNote(on)
            againLine.visibility = if (day) View.VISIBLE else View.GONE
        }

        val head = ui.row()
        head.addView(ui.grow(ui.taskName(r.name).apply { textSize = 17f }))
        head.addView(when {
            locked -> ui.chip("locked", ui.p.PILL_PAUSE_FG, ui.p.PILL_PAUSE_BG, ui.p.PILL_PAUSE_EDGE)
            on -> ui.chip("included", ui.p.PILL_OK_FG, ui.p.PILL_OK_BG, ui.p.PILL_OK_EDGE)
            else -> ui.chip("left out", ui.p.PILL_NEUTRAL_FG, ui.p.PILL_NEUTRAL_BG,
                            ui.p.PILL_NEUTRAL_EDGE)
        })
        head.addView(ui.switch(on, enabled = !locked) { v -> include(r, v); holdShown(v) }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = ui.dp(8) }
        })
        sheet.addView(head)
        sheet.addView(holdLine, marginTop(2))
        sheet.addView(againLine, marginTop(2))
        holdShown(on)
        // The two minigames: why the other switch goes when this one comes on.
        Skills.minigameNote(r)?.let { sheet.addView(ui.sub(it), marginTop(2)) }

        if (locked) {
            sheet.addView(ui.body(
                if (unlocked == null) "Checking the supporter code..."
                else "This task needs a supporter code. Enter it under Settings."), marginTop(12))
            if (unlocked != null) {
                sheet.addView(ui.outButton("Go to Settings") { d.dismiss(); page = "settings"; render() },
                              wrapTop(10))
            }
        } else {
            val fields = r.page?.let { SkillSettings.page(it).fields } ?: emptyList()
            // The Presets page has its own headings (design A), so no "Settings" over them;
            // nor over a page whose one field is a button (EX Missions' "Claim now").
            val buttonsOnly = fields.isNotEmpty() && fields.all { it is SkillSettings.RunNow }
            val c = section(sheet, if (r.key == "preset" || buttonsOnly) null else "Settings")
            // Where the page's ad switch stood until 2026-10-02: how this
            // task's free ads go, and the way to the card that sets it.
            Stored.AD_PICKS.firstOrNull { it.first == r.key }?.let { (_, key) ->
                c.addView(adsPointer(key, d), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = ui.dp(10) })
            }
            if (fields.isNotEmpty()) {
                for (f in fields) when (f) {
                    // A supporter's box on everybody's task (the Bond token
                    // row's all-Digimon, 2026-09-23): off and greyed out
                    // without a code, the way a locked row is, and the
                    // setting left alone so a code redeemed later gives it
                    // back. The skill asks the code again for itself.
                    is SkillSettings.Toggle -> if (f.supporter && unlocked != true) {
                        val why = if (unlocked == null) "Checking the supporter code..."
                        else "Needs a supporter code -- enter it under Settings."
                        c.addView(ui.switchRow(f.label, listOf(f.note, why)
                            .filter { it.isNotEmpty() }.joinToString(" "),
                                               false, enabled = false) {})
                    } else c.addView(ui.switchRow(
                        f.label, f.note, store.bool(f.key, f.default)) { v ->
                        store.put(f.key, v)
                    })
                    is SkillSettings.Number -> {
                        // The first number of a block of its own: a rule, then
                        // its heading (the Dungeons page's minutes, 3.4).
                        if (f.heading.isNotEmpty()) {
                            c.addView(rule(12))
                            c.addView(ui.body(f.heading), marginTop(10))
                        }
                        c.addView(numberField(f.key, f.label, f.default, f.min, f.max, f.decimal))
                        if (f.note.isNotEmpty()) c.addView(ui.hint(f.note))
                    }
                    is SkillSettings.DungeonAttempts -> attemptsField(c, f.label)
                    is SkillSettings.PresetProfiles -> profilesField(c)
                    is SkillSettings.RunNow -> runNowField(c, r, f)
                    is SkillSettings.ChainSteps -> Unit  // the chain is the mode sheet's
                }
                r.page?.let { key ->
                    val note = SkillSettings.page(key).note
                    if (note.isNotEmpty()) c.addView(ui.hint(note), marginTop(8))
                }
            } else {
                c.addView(ui.hint(r.noSettings))
            }
        }

        // What this task has counted today, out of the settings file where
        // every pass adds to it (SkillStats). "Nothing yet today." is still
        // the answer where it has done nothing -- and it is the true one now,
        // rather than the only one there was.
        val counted = if (locked) null
        else SkillStats.sentence(r.key, SkillStats.read(store, r.key, today()))
        section(sheet, "Today").addView(ui.body(when {
            locked -> "Locked"
            counted != null -> counted
            else -> "Nothing yet today."
        }))

        val semi = ui.column().apply {
            background = ui.round(ui.p.STATE_BG, ui.p.STATE_EDGE)
            setPadding(ui.dp(Ui.PAD), ui.dp(10), ui.dp(Ui.PAD), ui.dp(10))
        }
        semi.addView(ui.eyebrow("In semi-automatic mode").apply { setTextColor(ui.p.STATE_FG) })
        semi.addView(ui.body(r.semi).apply { setTextColor(ui.p.STATE_FG) })
        sheet.addView(semi, marginTop(14))
    }

    // --- presets ------------------------------------------------------------------

    /** The profile the Presets sheet shows, and the one its big button switches to. */
    private var presetPicked = 0

    /**
     * Switch to profile [i]: its slots become the ones the next pass sets
     * (Stored.armPreset) and the director runs the task from the next plain
     * main screen (DirectorLoop.runNow). What stands in the way is said at
     * once rather than left to a switch that never comes.
     */
    private fun switchPreset(i: Int) {
        val profiles = Stored.presetProfiles(store)
        if (i !in profiles.indices) return
        val director = CoreService.director
        val say = when {
            !Skills.included(Skills.row("preset"), store, Supporter.unlocked) -> "Switch the Presets task on first"
            director == null -> "DigiAutotap is not running -- press Start first"
            !MainSwitch.on -> "DigiAutotap is stopped (the dot is grey) -- tap the dot first"
            else -> {
                Stored.armPreset(store, i)
                director.runNow = "preset"
                HelperLog.line("Presets: switching to \"${profiles[i].name}\" asked for")
                "Switching to ${profiles[i].name} at the next main screen"
            }
        }
        Toast.makeText(this, say, Toast.LENGTH_LONG).show()
    }

    /**
     * A page's button that runs its task once (SkillSettings.RunNow, EX
     * Missions' "Claim now"): the director runs it from the next plain main
     * screen (DirectorLoop.runNow), as "Switch to" runs Presets, and what
     * stands in the way is said at once.
     */
    private fun runNowField(parent: LinearLayout, r: SkillRow, f: SkillSettings.RunNow) {
        if (f.note.isNotEmpty()) {
            val needs = ui.column().apply {
                background = ui.round(ui.p.PILL_PAUSE_BG, ui.p.PILL_PAUSE_EDGE)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            }
            needs.addView(ui.title("Before you tap").apply { setTextColor(ui.p.PILL_PAUSE_FG) })
            needs.addView(ui.body(f.note).apply { setTextColor(ui.p.PILL_PAUSE_FG) }, marginTop(4))
            parent.addView(needs, marginTop(4))
        }
        parent.addView(ui.primaryButton(f.label) { runNow(r, f.label) }.apply {
            textSize = 16f
            minimumHeight = ui.dp(52)
        }, marginTop(10))
    }

    private fun runNow(r: SkillRow, label: String) {
        val director = CoreService.director
        val say = when {
            !Skills.included(r, store, Supporter.unlocked) -> "Switch the ${r.name} task on first"
            director == null -> "DigiAutotap is not running -- press Start first"
            !MainSwitch.on -> "DigiAutotap is stopped (the dot is grey) -- tap the dot first"
            else -> {
                director.runNow = r.key
                HelperLog.line("${r.name}: \"$label\" asked for")
                "${r.name} at the next main screen"
            }
        }
        Toast.makeText(this, say, Toast.LENGTH_LONG).show()
    }

    /** A profile's button in the sheet: filled while it is the one shown, outlined otherwise. */
    private fun profileChip(label: String, picked: Boolean, onClick: () -> Unit): TextView =
        ui.name(label).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            isSingleLine = true
            setTextColor(if (picked) ui.p.ON_PRIMARY else ui.p.TEXT)
            background = if (picked) ui.round(ui.p.PRIMARY, ui.p.PRIMARY) else ui.round(ui.p.BG, ui.p.LINE)
            setPadding(ui.dp(8), 0, ui.dp(8), 0)
            minimumHeight = ui.dp(44)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** Buttons [columns] to a row, each row's cells the same width. */
    private fun buttonGrid(parent: LinearLayout, buttons: List<View>, columns: Int = 3) {
        for (chunk in buttons.chunked(columns)) {
            val row = ui.row()
            chunk.forEachIndexed { k, v ->
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (k > 0) leftMargin = ui.dp(8)
                })
            }
            // A short last row keeps the cells of the rows above.
            repeat(columns - chunk.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = ui.dp(8) })
            }
            parent.addView(row, marginTop(8))
        }
    }

    /**
     * The Presets sheet (design A of 2026-09-26): the profiles as buttons,
     * the picked one's six slots (1 to 10, saved as a field loses the
     * focus; Overdrive the sixth since PLAN_DAILY_LOST_SECTOR_PRESETS.md DL2)
     * and the greyed places the game does not have yet
     * (SkillSettings.PRESET_PLACEHOLDERS), its name and Remove, the yellow
     * box, and "Switch to <name>".
     * Drawn again in place when the pick changes.
     */
    private fun profilesField(parent: LinearLayout) {
        val box = ui.column()
        parent.addView(box)
        presetPicked = Stored.presetLast(store) ?: presetPicked
        fun draw() {
            box.removeAllViews()
            val profiles = Stored.presetProfiles(store)
            presetPicked = presetPicked.coerceIn(0, profiles.size - 1)
            val p = profiles[presetPicked]
            fun pick(i: Int) {
                box.findFocus()?.clearFocus()
                presetPicked = i
                draw()
            }

            box.addView(ui.eyebrow("Profiles"), marginTop(4))
            val chips = profiles.mapIndexed { i, q -> profileChip(q.name, i == presetPicked) { pick(i) } }
            val add = if (profiles.size < Stored.PRESET_PROFILES_MAX) listOf(profileChip("+ Add", false) {
                box.findFocus()?.clearFocus()
                Stored.addPresetProfile(store)?.let { presetPicked = it }
                draw()
            }.apply { setTextColor(ui.p.TEXT_2) }) else emptyList()
            buttonGrid(box, chips + add)
            box.addView(ui.hint("Tap a profile to load its slots. A number you change below is saved " +
                                "in that profile. Up to five profiles."), marginTop(6))

            box.addView(rule(12))
            box.addView(ui.eyebrow("Slots in “${p.name}”"), marginTop(10))
            for (place in Preset.Place.values()) {
                val row = ui.row().apply { setPadding(0, ui.dp(3), 0, ui.dp(3)) }
                val e = ui.edit(numeric = true).apply { setText(p.slots[place].toString()); minEms = 3 }
                e.setOnFocusChangeListener { _, has ->
                    if (has) return@setOnFocusChangeListener
                    val n = Stored.clampSlot(e.text.toString().toIntOrNull() ?: Stored.PRESET_SLOT_DEFAULT)
                    e.setText(n.toString())
                    val now = Stored.presetProfiles(store)[presetPicked]
                    Stored.putPresetProfile(store, presetPicked, now.copy(slots = now.slots + (place to n)))
                }
                row.addView(e)
                row.addView(ui.grow(ui.body(place.label)).apply { setPadding(ui.dp(10), 0, 0, 0) })
                box.addView(row)
            }
            // The places the game does not have yet (Gear, question 6 of
            // PLAN_DAILY_LOST_SECTOR_PRESETS.md): a greyed line under the real
            // ones, no field, nothing to tap, no slot in the profile.
            for (label in SkillSettings.PRESET_PLACEHOLDERS) {
                val row = ui.row().apply { setPadding(0, ui.dp(6), 0, ui.dp(3)) }
                row.addView(ui.body(label).apply { setTextColor(ui.p.TEXT_2); isEnabled = false })
                row.addView(ui.grow(ui.hint(SkillSettings.PRESET_PLACEHOLDER_NOTE))
                                .apply { setPadding(ui.dp(10), 0, 0, 0) })
                box.addView(row)
            }

            // The name, and Remove: not in the mockup, and a profile needs both.
            val nameRow = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            val name = ui.edit("Name").apply { setText(p.name); minEms = 7; isSingleLine = true }
            name.setOnFocusChangeListener { _, has ->
                if (has) return@setOnFocusChangeListener
                val n = name.text.toString().trim().take(16)
                val now = Stored.presetProfiles(store)[presetPicked]
                if (n.isNotEmpty() && n != now.name) {
                    Stored.putPresetProfile(store, presetPicked, now.copy(name = n))
                    box.post { draw() }
                }
            }
            nameRow.addView(name)
            nameRow.addView(ui.grow(ui.body("Name")).apply { setPadding(ui.dp(10), 0, 0, 0) })
            if (profiles.size > 1) nameRow.addView(ui.outButton("Remove") {
                box.findFocus()?.clearFocus()
                Stored.removePresetProfile(store, presetPicked)
                presetPicked = (presetPicked - 1).coerceAtLeast(0)
                draw()
            })
            box.addView(nameRow)

            val needs = ui.column().apply {
                background = ui.round(ui.p.PILL_PAUSE_BG, ui.p.PILL_PAUSE_EDGE)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            }
            needs.addView(ui.title("Before you switch").apply { setTextColor(ui.p.PILL_PAUSE_FG) })
            needs.addView(ui.body(SkillSettings.PRESET_NEEDS).apply { setTextColor(ui.p.PILL_PAUSE_FG) },
                          marginTop(4))
            box.addView(needs, marginTop(14))
            box.addView(ui.primaryButton("Switch to ${p.name}") {
                box.findFocus()?.clearFocus()
                switchPreset(presetPicked)
            }.apply {
                textSize = 16f
                minimumHeight = ui.dp(52)
            }, marginTop(10))
        }
        draw()
    }

    /** A part of a sheet: a rule, an eyebrow, and the column under it. */
    private fun section(sheet: LinearLayout, eyebrow: String?): LinearLayout {
        sheet.addView(rule(12))
        val c = ui.column().apply { setPadding(0, ui.dp(10), 0, 0) }
        if (eyebrow != null) c.addView(ui.eyebrow(eyebrow))
        sheet.addView(c)
        return c
    }

    /**
     * A hairline with room above it. The hairline carries its own height in
     * its layout params, and a `marginTop` set of params would replace that
     * height with WRAP_CONTENT, which for a bare View is nought.
     */
    private fun rule(above: Int): View = ui.hairline().apply {
        (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(above)
    }

    private fun numberField(key: String, text: String, default: Double, min: Double,
                            max: Double, decimal: Boolean): View {
        val row = ui.row().apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        val edit = ui.edit(numeric = true, decimal = decimal).apply {
            val v = store.num(key, default)
            setText(if (decimal) v.toString() else v.toInt().toString())
            minEms = 3
        }
        edit.setOnFocusChangeListener { _, has ->
            if (has) return@setOnFocusChangeListener
            val v = (edit.text.toString().toDoubleOrNull() ?: default).coerceIn(min, max)
            edit.setText(if (decimal) v.toString() else v.toInt().toString())
            val before = store.num(key, default)
            // app.py's _spin writes an int where its variable is an IntVar.
            store.put(key, if (decimal) v else v.toInt())
            // New minutes for the Lost Sector Tower are the player saying "try
            // again" to a tower that is at its highest floor for the day
            // (Stored.lostSectorDoneUntil, G16), as the Quest Loop row's
            // switch lifts that loop's day lock (Skills.include).
            if (key == Stored.LOST_SECTOR_MINUTES_KEY && v != before &&
                !store.num(Stored.LOST_SECTOR_DONE_UNTIL_KEY, Double.NaN).isNaN()) {
                Stored.unretireLostSector(store)
                HelperLog.line("Lost Sector Tower: new minutes, the day's highest floor forgotten")
            }
        }
        row.addView(edit)
        row.addView(ui.grow(ui.body(text)).apply { setPadding(ui.dp(10), 0, 0, 0) })
        return row
    }

    private fun attemptsField(parent: LinearLayout, label: String) {
        // Under the ad switch since 2026-09-30, whose note it touched on
        // LDPlayer without the air between them.
        parent.addView(ui.body(label), marginTop(12))
        val values = Stored.attempts(store)
        val edits = ArrayList<Pair<Int, EditText>>()
        val stampRow = ui.row().apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) }
        val stamp = ui.edit(numeric = true).apply {
            setText(SkillSettings.SET_ALL_DEFAULT.toString())
            minEms = 3
        }
        stampRow.addView(stamp)
        stampRow.addView(ui.outButton("Set all") {
            val n = (stamp.text.toString().toIntOrNull() ?: 0).coerceIn(0, 99)
            edits.forEach { (i, e) -> values[i] = n; e.setText(n.toString()) }
            Stored.putAttempts(store, values)
        }.apply { setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)) })
        parent.addView(stampRow)
        SkillSettings.DUNGEON_NAMES.forEachIndexed { i, dungeon ->
            if (dungeon in SkillSettings.HIDDEN_DUNGEONS) return@forEachIndexed
            val row = ui.row().apply { setPadding(0, ui.dp(3), 0, ui.dp(3)) }
            val e = ui.edit(numeric = true).apply { setText(values[i].toString()); minEms = 3 }
            e.setOnFocusChangeListener { _, has ->
                if (has) return@setOnFocusChangeListener
                values[i] = (e.text.toString().toIntOrNull() ?: 0).coerceIn(0, 99)
                e.setText(values[i].toString())
                Stored.putAttempts(store, values)
            }
            edits += i to e
            row.addView(e)
            row.addView(ui.grow(ui.body(dungeon)).apply { setPadding(ui.dp(10), 0, 0, 0) })
            parent.addView(row)
        }
    }

    // --- sheets from the bottom ------------------------------------------------

    /**
     * A sheet from the bottom: SURFACE with rounded top corners, a grip, and
     * whatever [build] puts under it, scrolling when it is taller than the
     * screen. Every sheet the page opens -- a task, the mode, the order, the
     * set-up -- is this one shape, and the page is drawn again when it goes,
     * so a switch thrown in the sheet is a dot changed on the page.
     *
     * A field that still has the focus when the sheet goes is given it back
     * first: the number fields save on losing focus, and a sheet dismissed
     * by a tap outside it would otherwise take the last number with it.
     *
     * It goes three ways, whatever its height: the back key, a tap above it,
     * and a pull down from its top ([SheetScroll]). The Dungeons sheet on a
     * Poco F3 (2026-09-30) was taller than the screen: the dialog filled it,
     * nothing outside was left to tap, and the grip did not move -- only the
     * phone's back key closed it.
     */
    private fun bottomSheet(build: (LinearLayout, Dialog) -> Unit) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCanceledOnTouchOutside(true)
        val sheet = ui.column().apply {
            background = GradientDrawable().apply {
                setColor(ui.p.SURFACE)
                val r = ui.dp(14).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(20))
        }
        sheet.addView(View(this).apply {
            background = ui.round(ui.p.LINE, radius = 2)
        }, LinearLayout.LayoutParams(ui.dp(32), ui.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = ui.dp(10)
        })
        build(sheet, d)
        // A strip at the top stays free for the tap outside: an eighth of
        // the window, and never less than 96 dp, which is still a thumb's
        // width below a tall status bar.
        val sv = SheetScroll(this, gap = { h -> maxOf(ui.dp(96), h / 8) }) { d.cancel() }
            .apply { addView(sheet) }
        // Where the sheet's window is laid out under the navigation bar or
        // the keyboard, the sheet's own bottom padding grows by that much,
        // in its own colour; where the window keeps clear of them by itself
        // the inset is nought and nothing changes.
        sv.setOnApplyWindowInsetsListener { _, insets ->
            val under = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime()).bottom
            sheet.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(20) + under)
            insets
        }
        d.setContentView(sv)
        d.setOnDismissListener {
            sheet.findFocus()?.clearFocus()
            render()
        }
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        d.show()
    }

    /** The fully automatic order, from the mode sheet's Edit. */
    private fun orderSheet(): Unit = bottomSheet { sheet, d ->
        sheet.addView(ui.eyebrow("Fully automatic: order"))
        chainField(sheet) { d.dismiss(); orderSheet() }
        val repeat = SkillSettings.page("chain").fields
            .filterIsInstance<SkillSettings.Toggle>().first()
        sheet.addView(ui.switchRow(repeat.label, repeat.note,
                                   store.bool(repeat.key, repeat.default)) { v ->
            store.put(repeat.key, v)
        })
        sheet.addView(ui.hint("Tasks that are switched off are skipped. At the end it goes " +
            "back to the main screen."),
                      marginTop(6))
        sheet.addView(ui.primaryButton("Done") { d.dismiss() }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                     ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(12) }
        })
    }

    private fun chainField(parent: LinearLayout, again: () -> Unit) {
        val steps = Stored.chainSteps(store).toMutableList()
        if (steps.isEmpty()) parent.addView(ui.hint("No step yet."))
        // Dragged by the grip into place (OrderList), not moved a place at a
        // time by ▲ and ▼ with the sheet built again after every tap: the
        // player's ask of 2026-10-01. Saved the moment a row is let go; the
        // director reads the order before every step it chooses.
        val numbers = ArrayList<TextView>()
        lateinit var list: OrderList
        list = OrderList(this) { from, to ->
            steps.add(to, steps.removeAt(from))
            store.putSteps("chain_steps", steps)
            numbers.add(to, numbers.removeAt(from))
            numbers.forEachIndexed { i, n -> n.text = "${i + 1}" }
        }
        steps.forEachIndexed { i, (key, _) ->
            val row = ui.row().apply {
                // Its own ground, so that the row lifted over the others hides them.
                background = ui.round(ui.p.SURFACE)
                setPadding(0, ui.dp(2), 0, ui.dp(2))
            }
            val grip = FrameLayout(this).apply {
                addView(ui.icon(R.drawable.ic_drag, 20, ui.p.TEXT_2).apply {
                    layoutParams = FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER)
                })
                contentDescription = "Drag to move"
                layoutParams = LinearLayout.LayoutParams(ui.dp(Ui.TOUCH), ui.dp(Ui.TOUCH))
            }
            row.addView(grip)
            val n = ui.sub("${i + 1}").apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(22), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            numbers += n
            row.addView(n)
            row.addView(ui.grow(ui.body(SkillSettings.CHAIN_NAMES[key] ?: key)))
            // Skipped while its task is switched off (PLAN_BEFUNDE_1_3.md N3 b).
            if (Skills.stepOff(key, store, Supporter.unlocked)) row.addView(ui.sub("off").apply {
                setTextColor(ui.p.PAUSE)
            })
            row.addView(FrameLayout(this).apply {
                addView(ui.icon(R.drawable.ic_delete, 18, ui.p.TEXT_2).apply {
                    layoutParams = FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER)
                })
                contentDescription = "Remove"
                layoutParams = LinearLayout.LayoutParams(ui.dp(Ui.TOUCH), ui.dp(Ui.TOUCH))
                // Where the row stands now: a drag may have moved it.
                setOnClickListener {
                    steps.removeAt(list.indexOfChild(row))
                    store.putSteps("chain_steps", steps)
                    again()
                }
            })
            list.addRow(row, grip)
        }
        parent.addView(list)
        if (steps.size > 1) parent.addView(ui.hint("Hold a step by its dots and drag it into place."),
                                           marginTop(2))
        // A step is added by a button of its own, two to a row, rather than
        // picked from a platform Spinner and confirmed with Add: the Spinner
        // was the one control on the page the design did not draw itself,
        // and it looked it (2026-09-28). The buttons carry the rows' names,
        // which are the list's; the steps above keep the chain's own longer
        // ones. No minutes field any more: Tower & Ruins was the one step
        // that had them (SkillSettings.CHAIN_TIMED), and it left the
        // interface on 2026-09-22.
        val keys = SkillSettings.CHAIN_WAITING + SkillSettings.CHAINABLE
        parent.addView(ui.eyebrow("Add a step"), marginTop(14))
        buttonGrid(parent, keys.map { key ->
            ui.outButton(Skills.stepName(key)) {
                steps += Chain.Step(key, 0)
                store.putSteps("chain_steps", steps)
                again()
            }
        }, columns = 2)
        parent.addView(ui.hint("A step runs until it is done."), marginTop(8))
    }

    // --- Log -------------------------------------------------------------------

    private fun pageLog() {
        val outer = ui.column()
        val head = ui.row().apply {
            setBackgroundColor(ui.p.SURFACE)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(ui.p.TEXT)
            setTextIsSelectable(true)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(16))
            setLineSpacing(0f, 1.25f)
            this.text = HelperLog.snapshot().joinToString("\n")
        }
        val sv = ScrollView(this).apply { setBackgroundColor(ui.p.SURFACE); addView(text) }

        head.addView(ui.outButton("Copy") {
            val lines = HelperLog.snapshot()
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("DigiAutotap log", lines.joinToString("\n")))
            Toast.makeText(this, "Copied ${lines.size} lines", Toast.LENGTH_SHORT).show()
        })
        // Hold freezes the view so a line can be read while more arrive; the
        // log itself keeps filling behind it, and Follow shows it whole again.
        head.addView(ui.outButton(if (logHeld) "Follow" else "Hold") {
            logHeld = !logHeld
            render()
        }.apply { layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = ui.dp(8) } })
        // The way a bug reaches us since 1.3's fourth candidate: the log as a
        // ZIP, handed to the share sheet (LogShare). Built off the main
        // thread -- logcat alone can take a second.
        head.addView(ui.outButton("Share") {
            thread(name = "log-share") {
                val zip = runCatching { LogShare.build(this) }
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    zip.onSuccess { LogShare.share(this, it) }.onFailure {
                        HelperLog.line("log share: failed, ${it::class.java.simpleName}: ${it.message}")
                        Toast.makeText(this, "The log could not be packed.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = ui.dp(8) } })

        outer.addView(head)
        outer.addView(ui.divider())
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.addView(outer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                     ViewGroup.LayoutParams.MATCH_PARENT))
        if (!logHeld) {
            logSink = { line ->
                text.append(if (text.text.isEmpty()) line else "\n$line")
                sv.post { sv.fullScroll(View.FOCUS_DOWN) }
            }
            sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // --- Settings ----------------------------------------------------------------

    /**
     * A redeemed code, said here and nowhere else: the green tick after the
     * app's name in the bar went on 2026-09-23, the player's choice of four
     * sketches. The card thanks the player and has nothing left to enter --
     * the field, and the sentence about what redeeming sends, are for a
     * phone that has no code that holds.
     */
    private fun supporterThanks(col: LinearLayout) {
        val sup = card(col)
        sup.addView(ui.eyebrow("Supporter code · redeemed").apply { setTextColor(ui.p.OK) })
        sup.addView(ui.row().apply {
            gravity = Gravity.TOP
            addView(ui.icon(R.drawable.ic_heart, 16, ui.p.OK).apply {
                (layoutParams as LinearLayout.LayoutParams).apply {
                    rightMargin = ui.dp(8)
                    topMargin = ui.dp(1)
                }
            })
            addView(ui.grow(ui.body("Thank you for supporting DigiAutotap.")))
        })
        // The code itself stays, small (the player's one change to design A,
        // 2026-10-02; question 14): covered but for its first group, because
        // a picture of this page goes into Discord and a code works on two
        // devices, and a tap shows it whole. A phone that redeemed before
        // 1.3 kept only the token, and says so.
        val code = Supporter.code
        sup.addView(if (code != null) codeLine(code)
                    else ui.mono("your code  redeemed before this version -- the code itself is " +
                                 "not kept on this phone", 11f, ui.p.TEXT_2), marginTop(8))
        sup.addView(ui.hint("This phone is device ${Supporter.token?.slot ?: 1} of 2. " +
            "New phone? Ask on Discord to free one up."), marginTop(4))
    }

    /** "your code  ABCD-····-····  show": the redeemed code, covered until tapped ([Unlock.masked]). */
    private fun codeLine(code: String): View {
        var shown = false
        val value = ui.mono(Unlock.masked(code), 11f, ui.p.TEXT)
        val toggle = ui.mono("show", 11f, ui.p.PRIMARY, bold = true)
        return ui.row().apply {
            minimumHeight = ui.dp(Ui.TOUCH)
            addView(ui.mono("your code", 11f, ui.p.TEXT_2).apply { setPadding(0, 0, ui.dp(10), 0) })
            addView(value)
            addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                     ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = ui.dp(12) })
            isClickable = true
            setOnClickListener {
                shown = !shown
                value.text = if (shown) Unlock.pretty(code) else Unlock.masked(code)
                toggle.text = if (shown) "hide" else "show"
            }
        }
    }

    /** The supporter card of a phone with no code that holds yet: what it has, and the field. */
    private fun supporterRedeem(col: LinearLayout) {
        val sup = card(col, "Supporter code")
        // What the phone has, as Supporter read it once off the main thread.
        // The code itself is not here to be shown any more: the file holds
        // the token the code was traded for, and the token is bound to this
        // phone rather than readable as a code.
        sup.addView(ui.body(when (Supporter.have) {
            // BOUND is thanked in its own card and never reaches this one.
            Supporter.Have.CHECKING, Supporter.Have.BOUND -> "Checking..."
            // What a code opens is said by the task list itself, on each
            // locked row; the card's two lists went in 1.3's fourth candidate.
            Supporter.Have.NONE -> "No code on this phone."
            Supporter.Have.OLD_CODE ->
                "Your code was redeemed with an older version of DigiAutotap. Enter it " +
                "once more to keep it working on this phone."
            Supporter.Have.BROKEN ->
                "The saved code cannot be used on this phone -- it belongs to another " +
                "device, or it is damaged. Enter your code again."
        }))
        val field = ui.row().apply { setPadding(0, ui.dp(10), 0, 0) }
        val edit = ui.edit("ABCD-EFGH-JKMN").apply {
            setSingleLine()
            // The hyphens type themselves: after the fourth and the eighth
            // character, while typing forwards. Deleting is left alone, or
            // a Backspace on "ABCD-" would put the hyphen straight back.
            addTextChangedListener(object : android.text.TextWatcher {
                var before = 0
                var busy = false
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    before = s?.length ?: 0
                }
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (busy || s == null) return
                    val plain = Unlock.normalise(s.toString()).take(Unlock.LENGTH)
                    var shaped = Unlock.pretty(plain)
                    if (s.length > before && plain.isNotEmpty() && plain.length < Unlock.LENGTH &&
                        plain.length % Unlock.GROUP == 0) shaped += "-"
                    if (shaped == s.toString()) return
                    busy = true
                    s.replace(0, s.length, shaped)
                    busy = false
                }
            })
        }
        field.addView(ui.grow(edit))
        val answer = ui.answer("")
        answer.visibility = View.GONE
        fun say(t: String) { answer.text = t; answer.visibility = View.VISIBLE }
        field.addView(ui.primaryButton("Unlock") {
            val code = edit.text.toString()
            if (!Unlock.looksLikeCode(code)) {
                say("${Unlock.pretty(code)}: not the right shape -- a code is 12 characters.")
                return@primaryButton
            }
            // The second the hash takes, plus the request: both off the main
            // thread, and the sentence below says what is happening while
            // they run.
            say("Redeeming ${Unlock.pretty(code)}...")
            thread {
                val said = Supporter.redeem(this, code)
                main.post {
                    if (said == Supporter.Redeemed.BOUND) render()
                    else say("${Unlock.pretty(code)}: ${said.sentence}.")
                }
            }
        }.apply { setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)) })
        sup.addView(field)
        sup.addView(answer, marginTop(10))
        // What redeeming sends is in the README under "What leaves the phone".
        // The code is a shop item on Ko-fi since 2026-10-02, pay what you want
        // from 3 euros; a donation still mails one, but the shop is what we name.
        val kofi = "Ko-fi shop"
        val hint = "Get a supporter code in the DigiAutotap $kofi -- pay what you want, " +
            "from 3 €. It comes by email and works on two devices."
        val linked = android.text.SpannableString(hint).apply {
            val at = hint.indexOf(kofi)
            setSpan(android.text.style.URLSpan(Unlock.KOFI_URL), at, at + kofi.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sup.addView(ui.hint(linked).apply {
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setLinkTextColor(ui.p.OK)
        }, marginTop(10))
    }

    private fun pageSettings() {
        val col = scroller()

        if (Supporter.have == Supporter.Have.BOUND) supporterThanks(col) else supporterRedeem(col)

        // The switch PLAN_ANDROID_5_SHELL.md 5 said would be a lie until
        // there was something behind it. There is now (design session,
        // decision 3: on from the factory, switchable off here).
        val over = card(col, "Overlay")
        over.addView(ui.switchRow("Show the dot over the game",
                                  "Tap it to stop and start, hold it to open DigiAutotap.",
                                  store.bool(Overlay.KEY_ON, true)) { v ->
            store.put(Overlay.KEY_ON, v)
            if (!v) Overlay.hide()
        })

        // Light or dark. It followed the phone and nothing else until
        // 2026-09-22; the choice writes `theme` and the Activity is built
        // again, which is what Android does by itself when the phone's own
        // setting changes.
        val design = card(col, "Design")
        val modes = Theme.Mode.entries
        design.addView(ui.segmented(modes.map { it.label }, modes.indexOf(Theme.mode)) { i ->
            Theme.choose(store, modes[i])
            HelperLog.line("design: ${modes[i].label.lowercase()}")
            recreate()
        })
        design.addView(ui.hint(when (Theme.mode) {
            Theme.Mode.SYSTEM -> "Follows the phone's own light or dark setting."
            Theme.Mode.LIGHT -> "Stays light whatever the phone is set to."
            Theme.Mode.DARK -> "Stays dark whatever the phone is set to."
        }), marginTop(8))

        val list = ui.listBox()
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(10)
        })
        // The log is a row of the list since 1.3's fourth candidate, the
        // first one (PLAN_REPORT_RAUS.md 2.2): it stood as a tile beside
        // "Report a problem" at the foot of the page from 2026-09-23, and
        // with the report gone a tile of its own said more than a row.
        list.addView(moreRow("Log", "What DigiAutotap did, line by line.",
                             R.drawable.ic_chevron) { page = "log"; render() })
        list.addView(ui.hairline())
        list.addView(moreRow("About", "Legal notice, requirements, privacy",
                             R.drawable.ic_chevron) { page = "about"; render() })
        list.addView(ui.hairline())
        val found = newer
        list.addView(moreRow("Check for updates",
                             if (found != null) "DigiAutotap ${found.version} is out. Tap to see it."
                             else "Looks on GitHub for a newer version. It also looks once " +
                                 "every time the app is opened.",
                             R.drawable.ic_chevron) {
            if (found != null) updateSheet(found) else checkForUpdate(asked = true)
        })
        list.addView(ui.hairline())
        val version = ui.row().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
        version.addView(ui.grow(ui.name("Version")))
        version.addView(ui.mono(version(), 11f, ui.p.TEXT_2))
        list.addView(version)

        // Which app is the game (notes/director.md, of that name): a thin line in the
        // Version row's shape, in a box of its own because the Version row
        // is not tappable and this one is. The app's name on the right, the
        // package only in the sheet; "not found" in WARN, because then no
        // task runs and this is where the player can see why.
        val gameBox = ui.listBox()
        col.addView(gameBox, marginTop(10))
        val game = DigiAutotapService.instance?.gamePackage ?: DigiAutotapService.findGame(this)
        val gameRow = ui.row().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
        gameRow.addView(ui.grow(ui.name("Game")))
        gameRow.addView(if (game != null) ui.mono(appLabel(game), 11f, ui.p.TEXT_2)
                        else ui.mono("not found", 11f, ui.p.WARN))
        gameRow.addView(ui.chevron().apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = ui.dp(8)
        })
        gameBox.addView(ui.tappable(gameRow) { gameSheet() })
    }

    /** An installed app's name as the launcher shows it, or its package when it has none. */
    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * Which app is Digimon UP, picked by the player. A player wrote on
     * 2026-09-24 that the Vital Bracelet and TCG apps were taken for the
     * game; the name comes first now ([Game.pick]), and this is the way
     * round anything the name and the hints still get wrong. Every launcher
     * app a hint matches is a row, the choice among them even when no hint
     * does; the last row clears the choice. The chosen row carries the chip
     * that says so -- Ui.kt has no radio button and the design none either.
     */
    private fun gameSheet(): Unit = bottomSheet { sheet, d ->
        val chosen = DigiAutotapService.chosenGame(this)
        val names = DigiAutotapService.launcherNames(this)
        sheet.addView(ui.title("Which app is Digimon UP?"))
        sheet.addView(ui.hint("Every installed app whose name looks like Bandai's. DigiAutotap " +
            "reads and taps the one you pick, and nothing else."), marginTop(4))

        fun choose(pkg: String?) {
            store.put(SkillSettings.GAME_KEY, pkg)
            HelperLog.line("game app: " + (if (pkg != null) "chosen $pkg" else "automatic"))
            DigiAutotapService.instance?.regame(
                if (pkg != null) "game app chosen in Settings" else "game app left to DigiAutotap")
            d.dismiss()
        }
        fun line(icon: View, left: View, on: Boolean, onClick: () -> Unit) {
            sheet.addView(rule(8))
            val r = ui.row().apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) }
            r.addView(icon, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply {
                rightMargin = ui.dp(10)
            })
            r.addView(ui.grow(left))
            if (on) r.addView(ui.chip("chosen", ui.p.PILL_OK_FG, ui.p.PILL_OK_BG, ui.p.PILL_OK_EDGE))
            sheet.addView(ui.tappable(r, onClick))
        }

        for (pkg in Game.candidates(names, chosen)) {
            val left = ui.column()
            left.addView(ui.name(appLabel(pkg)))
            left.addView(ui.mono(pkg, 10f, ui.p.TEXT_2))
            val icon = ImageView(this).apply {
                runCatching { setImageDrawable(packageManager.getApplicationIcon(pkg)) }
            }
            line(icon, left, pkg == chosen) { choose(pkg) }
        }
        val auto = ui.column()
        auto.addView(ui.name("Let DigiAutotap find it"))
        auto.addView(ui.hint("Picks the one it knows by name, then guesses."))
        // A choice that is no longer installed is not in the list, and is not
        // what the service uses either (the log says "falling back").
        line(View(this), auto, chosen == null || chosen !in names) { choose(null) }

        sheet.addView(ui.hint("Digimon UP not in the list? Then it isn't installed on this phone."),
                      marginTop(12))
    }

    private fun moreRow(title: String, note: String, glyph: Int, onClick: () -> Unit): View {
        val box = ui.row().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
        val middle = ui.column()
        middle.addView(ui.name(title))
        middle.addView(ui.hint(note))
        box.addView(ui.grow(middle))
        box.addView(ui.chevron(glyph).apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = ui.dp(8)
        })
        return ui.tappable(box, onClick)
    }

    // --- About ---------------------------------------------------------------------

    private fun pageAbout() {
        val col = scroller()
        val c = card(col)
        c.addView(ui.title("DigiAutotap " + version()))
        c.addView(ui.hint("An unofficial fan project, not made, endorsed, sponsored or supported " +
            "by Bandai Namco, Bandai, Toei Animation, or anyone else involved in Digimon UP."),
                  marginTop(6))
        about(col, "Legal notice",
              "The publisher's terms of service explicitly prohibit bots, emulators and " +
                  "similar tools in section 11 g. Anyone using this software risks having their " +
                  "game account suspended. It is provided without warranty; the decision to use " +
                  "it and the consequences are the user's.\n\nDigiAutotap is an independent " +
                  "project. It is not affiliated with, endorsed by or connected to the publisher " +
                  "of Digimon UP in any way. Product names and trademarks belong to their " +
                  "respective owners.")
        about(col, "What it needs",
              "Android 11 or later, with the game in portrait. Tablets and foldables are not " +
                  "supported.")
        about(col, "Phone makers",
              "Samsung, Xiaomi, Huawei and others close background apps by their own rules. " +
                  "Turn off battery optimisation for DigiAutotap (the set-up line at the bottom " +
                  "of the main page). If it still stops, look for your phone's own \"app launch\" " +
                  "or \"background activity\" setting and allow DigiAutotap there. When the " +
                  "notification disappears, DigiAutotap has been stopped.")
        about(col, "Privacy",
              "What DigiAutotap sees on the screen stays on the phone: it keeps no pictures " +
                  "of the game and sends none. It goes online for three things: when it is " +
                  "opened, it asks GitHub whether a newer version is out, and that request " +
                  "carries nothing about you or the phone; once a day it tells our server the " +
                  "app version, and once a month what kind of phone it is (make and model, " +
                  "Android version, screen size, camera cutout and the screenshot colour " +
                  "format), with nothing that identifies you or your phone in particular; and " +
                  "once, to redeem a supporter code. Share on the Log page makes a ZIP of the " +
                  "log, the settings and the phone model and hands it to the app you pick -- " +
                  "nothing is sent by itself. The source code is public on GitHub, so you can " +
                  "check for yourself exactly what the app sends and when.")
        about(col, "Type",
              "The names are set in Chakra Petch, copyright 2018 The Chakra Petch Project " +
                  "Authors, under the SIL Open Font License 1.1; the licence travels inside " +
                  "the app.")
    }

    private fun about(col: LinearLayout, title: String, text: String) {
        val c = card(col, title)
        c.addView(ui.hint(text))
    }

    // --- Onboarding ------------------------------------------------------------------

    private fun pageOnboarding() {
        val asks = Setup.asks(this)
        val ask = asks[onboarding.coerceIn(0, asks.size - 1)]
        val box = ui.column().apply {
            setBackgroundColor(ui.p.SURFACE)
            setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(22))
        }
        val steps = ui.row()
        asks.indices.forEach { i ->
            steps.addView(View(this).apply {
                background = ui.round(if (i <= onboarding) ui.p.PRIMARY else ui.p.LINE, radius = 2)
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(4), 1f).apply {
                    if (i > 0) leftMargin = ui.dp(6)
                }
            })
        }
        box.addView(steps)
        box.addView(TextView(this).apply {
            text = ask.glyph
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(ui.p.STATE_FG)
            background = ui.round(ui.p.STATE_BG, ui.p.STATE_EDGE, radius = Ui.RADIUS)
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56))
                .apply { topMargin = ui.dp(28) }
        })
        box.addView(TextView(this).apply {
            text = ask.headline
            textSize = 23f
            setTextColor(ui.p.TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.2f)
        }, marginTop(22))
        box.addView(ui.hint(ask.why).apply { textSize = 14f }, marginTop(10))
        box.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        box.addView(ui.bigButton(ask.button, null) { ask.fix(this); step() })
        // The second way out, where there is one, and it does not step on:
        // App info is not the ask's answer, it is what unbars the answer,
        // and the player comes back to this same card afterwards.
        ask.alt?.let { alt ->
            box.addView(ui.textButton(alt.label) { alt.go(this) }.apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                         ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ui.dp(4) }
            })
        }
        box.addView(ui.textButton("Skip for now") { step() }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                     ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(6) }
        })
        host.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                   ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * On to the next ask, or into the app. What was skipped is not
     * remembered anywhere: the set-up card asks the system again and shows a
     * cross for whatever is still missing (5_SHELL 3.2).
     */
    private fun step() {
        onboarding += 1
        if (onboarding >= Setup.asks(this).size) {
            onboarding = -1
            store.put(ONBOARDING_SEEN, true)
        }
        render()
    }

    // --- small helpers -----------------------------------------------------------------

    private fun version(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    /**
     * Hands a link to whatever browser the phone has. No permission is
     * needed for this and none is asked for: the browser does the loading,
     * not DigiAutotap. A phone with no browser at all says so rather than
     * throwing.
     */
    private fun openInBrowser(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "No browser on this phone", Toast.LENGTH_SHORT).show() }
    }

    // --- updates -----------------------------------------------------------------------

    /**
     * Asks GitHub for the newest release, off the main thread ([Updates]).
     *
     * On opening ([asked] false) it is quiet: a newer version is a sheet, at
     * every opening until it is installed -- "Later" is for this opening
     * only, the player's rule of 2026-09-24, which replaced "once per
     * version" -- unless the player ticked "Don't remind me" for exactly
     * this version ([UPDATE_MUTED]); anything else says nothing, because a
     * phone offline or a repository not public yet is not the player's
     * business at start.
     * Asked from Settings, every answer is said.
     */
    private fun checkForUpdate(asked: Boolean) {
        val installed = version()
        if (asked) Toast.makeText(this, "Looking on GitHub...", Toast.LENGTH_SHORT).show()
        thread(name = "update-check") {
            val result = Updates.check(installed)
            main.post {
                if (isFinishing || isDestroyed) return@post
                when (result) {
                    is Updates.Result.Newer -> {
                        if (newer?.version != result.version) {
                            HelperLog.line("update: ${result.version} is out, this is $installed")
                        }
                        newer = result
                        if (asked) updateSheet(result) else offerUpdate(result)
                    }
                    Updates.Result.Current ->
                        if (asked) Toast.makeText(this, "$installed is the newest version",
                                                  Toast.LENGTH_SHORT).show()
                    is Updates.Result.Unreachable -> {
                        HelperLog.line("update check: ${result.why}")
                        if (asked) Toast.makeText(this, "Could not reach GitHub (${result.why})",
                                                  Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * The popup unasked, at an opening. Not over the first-run pages (the
     * next opening shows it) and not over itself: coming back from the
     * release page the player just opened finds it still up.
     */
    private fun offerUpdate(found: Updates.Result.Newer) {
        val muted = store.str(UPDATE_MUTED, "") == found.version
        if (muted || onboarding >= 0 || updateDialog?.isShowing == true) render()
        else updateSheet(found)
    }

    /**
     * The popup for a newer version: what is out, what is here, and the way
     * to it. The tick box silences this one version and no other -- the
     * player's call of 2026-09-24, so that 1.2 is still said after 1.1 was
     * waved away -- and Settings keeps naming it either way, which is also
     * where the tick is taken off again.
     */
    private fun updateSheet(found: Updates.Result.Newer): Unit = bottomSheet { sheet, d ->
        updateDialog = d
        val muted = store.str(UPDATE_MUTED, "") == found.version
        sheet.addView(ui.eyebrow("Update"))
        sheet.addView(ui.title("DigiAutotap ${found.version} is out"), marginTop(4))
        sheet.addView(ui.hint("This phone has ${version()}. Download the new APK from the " +
            "release page and install it over this one: your settings and your supporter " +
            "unlock stay."), marginTop(6))
        // "Later" is a promise the tick takes back, so it says Close then.
        val later = ui.textButton(if (muted) "Close" else "Later") { d.dismiss() }
        sheet.addView(ui.checkRow("Don't remind me about ${found.version}", muted) { on ->
            store.put(UPDATE_MUTED, if (on) found.version else null)
            later.text = if (on) "Close" else "Later"
        }, marginTop(10))
        val buttons = ui.row().apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        buttons.addView(later)
        buttons.addView(ui.primaryButton("Open the release page") {
            d.dismiss()
            openInBrowser(found.page)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                     ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(12) })
        sheet.addView(buttons, marginTop(4))
    }

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = ui.dp(v) }

    private fun wrapTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = ui.dp(v) }

    companion object {
        const val ONBOARDING_SEEN = "onboarding_seen"
        /** A permanent invite: bugs, help, feedback, supporter devices. */
        const val DISCORD_INVITE = "https://discord.gg/mJFXtPuXng"

        /** The one version the player ticked "Don't remind me" for; a newer one is said again. */
        const val UPDATE_MUTED = "update_muted"

        /**
         * What the last check found, for as long as the process lives: the
         * Settings page names it, and a second opening does not have to wait
         * for the network to say so again.
         */
        @Volatile private var newer: Updates.Result.Newer? = null
    }
}


/**
 * What this phone has: supporter.txt in the app's files, as on the PC in the
 * data folder, and the install id that file's token is bound to.
 *
 * The file used to hold the redeemed code, checked once per process against
 * the codes.txt inside the APK. Since 2026-09-22 it holds a **token** the
 * activation server signed when the code was bound to this installation
 * (PLAN_SUPPORTER_SERVER.md): [load] checks it against a key in the build and
 * against [installId], **with no network at all**, and [redeem] is the one
 * moment this app goes online. Both run off the main thread and [unlocked]
 * stays null until the answer is in -- CoreService's comment at its
 * `Supporter.load` says why that matters more than it looks.
 *
 * Everything the page needs is read once, here, and kept: the page draws on
 * the main thread and has no business opening a file or verifying a
 * signature while it does.
 */
object Supporter {

    /** What the file said, as the page has to put it. */
    enum class Have {
        /** Not read yet. [unlocked] is null for exactly this. */
        CHECKING,
        /** No file: nobody has redeemed a code on this phone. */
        NONE,
        /**
         * Twelve alphabet characters and no dot: the code in the clear, from
         * a build before the binding. It cannot be turned into a token from
         * here -- only the server can bind it -- so the player enters it once
         * more and the page says why. There is one such phone (the Poco F3).
         */
        OLD_CODE,
        /** A file that is neither: a token that does not hold, or something else entirely. */
        BROKEN,
        /** A token this build's key signed, for this installation. */
        BOUND,
    }

    @Volatile var have: Have = Have.CHECKING
        private set

    /** The token behind [Have.BOUND] -- the page shows which of the two devices this is. */
    @Volatile var token: Unlock.Token? = null
        private set

    /**
     * The code behind [Have.BOUND], normalised, where this phone keeps it
     * (Unlock.CODE_FILE, since 2026-10-02) -- null on a phone that redeemed
     * before, which kept the token alone. For the Settings card and nothing
     * else: never logged, never in a shared log.
     */
    @Volatile var code: String? = null
        private set

    /** Unchanged in meaning for every caller that had it: null while it is not known yet. */
    val unlocked: Boolean?
        get() = if (have == Have.CHECKING) null else have == Have.BOUND

    /** What [redeem] learned, in the page's words rather than as a boolean. */
    enum class Redeemed(val sentence: String) {
        BOUND("bound to this phone"),
        UNKNOWN("not a supporter code, or it has been withdrawn"),
        IN_USE("already in use on two devices -- ask on Discord to free one up"),
        NO_SERVER("could not reach the activation server -- check your connection and try again"),
        BUSY("the activation server is busy right now -- please try again later, tomorrow at " +
             "the latest; everything else in DigiAutotap keeps working"),
        REFUSED("the server's answer could not be verified -- please try again"),
        NOT_KEPT("the code could not be saved on this phone -- nothing is unlocked"),
    }

    /**
     * This phone's install id: made from the Android id ([Unlock.installIdOf]),
     * so it survives an uninstall and a reinstall redeems the same code into
     * the slot it had, rather than counting as a third device (decided
     * 2026-09-23). A token redeemed under the random id of the builds before
     * reads as not this phone's, and the code is entered once more.
     *
     * Only a phone with no Android id at all falls back to the old way: a
     * random UUID in the app's files, written and read back, which dies with
     * the uninstall. Null only if that file can be neither read nor written.
     */
    fun installId(c: android.content.Context): String? {
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(
                c.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()
        Unlock.installIdOf(androidId)?.let { return it }
        val file = Paths.installFile(c)
        fun read() = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.length == 36 }
        read()?.let { return it }
        val made = java.util.UUID.randomUUID().toString()
        runCatching { file.writeText(made + "\n") }.getOrElse { return null }
        return read()
    }

    /** The file as it stands, trimmed; null if there is none, or it is empty. */
    fun savedText(c: android.content.Context): String? =
        runCatching { Paths.supporterFile(c).readText().trim() }.getOrNull()?.ifEmpty { null }

    /**
     * The file, read and believed or not -- off the main thread, once per
     * process. [done] is called whatever the answer is, because the shell
     * redraws either way and the service retries on the yes.
     */
    fun load(c: android.content.Context, done: () -> Unit) {
        if (have != Have.CHECKING) return
        thread {
            have = look(c)
            done()
        }
    }

    private fun look(c: android.content.Context): Have {
        val text = savedText(c) ?: return Have.NONE
        if ('.' !in text && Unlock.looksLikeCode(text)) return Have.OLD_CODE
        val read = Unlock.parseToken(text) ?: return Have.BROKEN
        // The digest is not asked about here: once the signature and the id
        // hold, the file is the word (PLAN_SUPPORTER_SERVER.md 2.2).
        if (!Unlock.holds(read, installId(c))) return Have.BROKEN
        token = read
        code = savedCode(c)
        return Have.BOUND
    }

    /**
     * The code file, read and not hashed again: its digest against the
     * token's would be a second of PBKDF2 on every start, and [unlocked]
     * waits for this function. It is written with the token it belongs to
     * and removed where it could not be ([keep]), so it is never another
     * code's.
     */
    private fun savedCode(c: android.content.Context): String? =
        runCatching { Paths.supporterCodeFile(c).readText() }.getOrNull()
            ?.let { Unlock.normalise(it) }?.takeIf { it.length == Unlock.LENGTH }

    /**
     * Hash, bind, believe, write, read back -- and only then say yes. On a
     * worker thread: the hash alone is about a second (PBKDF2, 600_000
     * rounds) and the request may take ten.
     *
     * What comes back is checked before it is kept: the signature, this
     * installation's id, and that the digest is the one just hashed -- a
     * token for some other code would otherwise unlock this one.
     *
     * The log gets the code covered but for its first group and one word, and
     * never the digest or the token: digiautotap.log goes into the ZIP that
     * Share on the Log page makes, and players share it in Discord.
     */
    fun redeem(c: android.content.Context, code: String): Redeemed {
        val plain = Unlock.normalise(code)
        val install = installId(c) ?: return say(plain, Redeemed.NOT_KEPT)
        val digest = Unlock.digest(plain)
        return when (val answer = Activation.activate(Unlock.ACTIVATION_URL, digest, install)) {
            is Activation.Result.Unknown -> say(plain, Redeemed.UNKNOWN)
            is Activation.Result.InUse -> say(plain, Redeemed.IN_USE)
            is Activation.Result.Busy -> say(plain, Redeemed.BUSY, answer.why)
            is Activation.Result.Unreachable -> say(plain, Redeemed.NO_SERVER, answer.why)
            is Activation.Result.Bound -> say(plain, keep(c, answer.token, digest, install, plain))
        }
    }

    private fun keep(c: android.content.Context, token: String,
                     digest: String, install: String, plain: String): Redeemed {
        val read = Unlock.parseToken(token) ?: return Redeemed.REFUSED
        if (!Unlock.holds(read, install) || read.digest != digest) return Redeemed.REFUSED
        runCatching { Paths.supporterFile(c).writeText(token + "\n") }.getOrElse { return Redeemed.NOT_KEPT }
        if (savedText(c) != token) return Redeemed.NOT_KEPT
        // The code itself, beside the token, for the Settings card (question
        // 14 of PLAN_ABSCHLUSS_1_3.md): the unlock does not hang on it, so a
        // file that cannot be written costs the card its line and no more --
        // and goes, so that an older code's file is not shown as this one.
        val file = Paths.supporterCodeFile(c)
        val kept = runCatching { file.writeText(plain + "\n") }.isSuccess && savedCode(c) == plain
        if (!kept) runCatching { file.delete() }
        Supporter.token = read
        code = if (kept) plain else null
        have = Have.BOUND
        return Redeemed.BOUND
    }

    /**
     * The log gets the code covered but for its first group ([Unlock.masked])
     * since 2026-10-02 -- it was the whole code until then, and the log goes
     * into every shared ZIP -- and one word; never the digest or the token.
     */
    private fun say(plain: String, said: Redeemed, why: String? = null): Redeemed {
        HelperLog.line("supporter code ${Unlock.masked(plain)}: ${said.sentence}" +
                       if (why == null) "" else " ($why)")
        return said
    }
}

/**
 * The scroll a bottom sheet stands in. It is never taller than its window
 * less [gap] of it, so a tap above the sheet always lands outside and closes
 * it; and a drag down that begins with the sheet scrolled to its top pulls
 * the whole sheet down, and lets it go ([onGone]) past a third of its height
 * or on a quick flick, or springs back. The drag is taken in
 * [dispatchTouchEvent], before a switch, a field or the scroll itself, so it
 * is the same pull wherever the finger came down; whoever held the gesture
 * until then is sent a cancel.
 */
private class SheetScroll(
    ctx: Context,
    private val gap: (Int) -> Int,
    private val onGone: () -> Unit,
) : ScrollView(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val flick = 600 * ctx.resources.displayMetrics.density  // px/s
    private var downX = 0f
    private var downY = 0f
    private var fromY = Float.NaN  // raw y where the pull began; NaN while there is none
    private var speed: VelocityTracker? = null
    /** A child asked for this gesture for itself (the order's grip, [OrderList]): no pull. */
    private var held = false

    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        if (disallow) held = true
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = MeasureSpec.getSize(heightSpec)
        super.onMeasure(widthSpec,
            if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) heightSpec
            else MeasureSpec.makeMeasureSpec(maxOf(0, h - gap(h)), MeasureSpec.AT_MOST))
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = e.rawX; downY = e.rawY; fromY = Float.NaN; held = false
            animate().cancel()
            speed?.recycle(); speed = VelocityTracker.obtain()
        }
        // In screen pixels: the sheet moves under the finger while it is pulled.
        MotionEvent.obtain(e).also { it.setLocation(e.rawX, e.rawY); speed?.addMovement(it); it.recycle() }
        val end = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        if (fromY.isNaN() && !held && e.actionMasked == MotionEvent.ACTION_MOVE && scrollY == 0) {
            val dy = e.rawY - downY
            if (dy > slop && dy > Math.abs(e.rawX - downX)) {
                fromY = e.rawY
                MotionEvent.obtain(e).also {
                    it.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
        }
        if (fromY.isNaN()) {
            if (end) { speed?.recycle(); speed = null }
            return super.dispatchTouchEvent(e)
        }
        if (e.actionMasked == MotionEvent.ACTION_MOVE) translationY = maxOf(0f, e.rawY - fromY)
        if (end) {
            val v = speed?.run { computeCurrentVelocity(1000); yVelocity } ?: 0f
            speed?.recycle(); speed = null
            val gone = e.actionMasked == MotionEvent.ACTION_UP &&
                (translationY > height / 3f || (v > flick && translationY > slop))
            if (gone) animate().translationY(height.toFloat()).setDuration(160).withEndAction { onGone() }
            else animate().translationY(0f).setDuration(160)
            fromY = Float.NaN
        }
        return true
    }
}
