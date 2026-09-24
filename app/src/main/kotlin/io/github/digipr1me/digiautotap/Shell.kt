package io.github.digipr1me.digiautotap

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import io.github.digipr1me.digiautotap.core.HelperState
import io.github.digipr1me.digiautotap.core.Shell

/**
 * The six states are core's now ([HelperState], [Shell]): they hold no
 * Android class, and Waiting, Running and Parked come from the director
 * (PLAN_ANDROID_5_SHELL.md 5.6). What is the app's is here.
 *
 * First, which colour each state is drawn in -- [Theme] is the only file
 * that writes a value, so the mapping lives beside it rather than in core.
 * Second, the three things core's [Shell] cannot know by itself, wired in
 * [wireShell] once per process: whether the service is alive, what the last
 * round saw, and a clock that does not jump.
 */
fun HelperState.pillBg(p: Palette) = when (this) {
    HelperState.RUNNING -> p.PILL_OK_BG
    HelperState.PAUSED -> p.PILL_PAUSE_BG
    HelperState.PARKED -> p.PILL_WARN_BG
    else -> p.PILL_NEUTRAL_BG
}

fun HelperState.pillFg(p: Palette) = when (this) {
    HelperState.RUNNING -> p.PILL_OK_FG
    HelperState.PAUSED -> p.PILL_PAUSE_FG
    HelperState.PARKED -> p.PILL_WARN_FG
    else -> p.PILL_NEUTRAL_FG
}

fun HelperState.pillEdge(p: Palette) = when (this) {
    HelperState.RUNNING -> p.PILL_OK_EDGE
    HelperState.PAUSED -> p.PILL_PAUSE_EDGE
    HelperState.PARKED -> p.PILL_WARN_EDGE
    else -> p.PILL_NEUTRAL_EDGE
}

/**
 * The main switch's icon, which is core's [HelperState.glyph] drawn rather
 * than typed. The glyph itself stays core's: the notification is a platform
 * widget with a text label, and a character is all it can carry there.
 */
fun HelperState.icon(): Int = when (this) {
    HelperState.PAUSED, HelperState.STOPPED -> R.drawable.ic_play
    HelperState.PARKED -> R.drawable.ic_retry
    else -> R.drawable.ic_pause
}

/**
 * The overlay dot's colour: the same four names in either design, and one
 * question -- is DigiAutotap doing something. On while it is on, whether it
 * is working or only watching (the plate says which), and since 2026-09-24
 * "on" says the mode as well: green when [full]y automatic, blue when
 * semi-automatic (Theme.kt, DOT_SEMI). Amber while the three-second clock
 * runs; red when it is parked and needs the player; grey when it is off,
 * which is Paused and, should a dot ever be drawn then, Stopped -- those
 * three are the same in either mode.
 */
fun HelperState.dot(p: Palette, full: Boolean) = when (this) {
    HelperState.RUNNING, HelperState.IDLE -> if (full) p.DOT_ON else p.DOT_SEMI
    HelperState.WAITING -> p.DOT_WAIT
    HelperState.PARKED -> p.DOT_WARN
    HelperState.PAUSED, HelperState.STOPPED -> p.DOT_OFF
}

/**
 * The three seams of core's [Shell], filled in by whoever starts something:
 * the Activity as well as the service, because the app can be opened while
 * no core is running and the pill has to be right then too. Idempotent --
 * each is a function, not a copied value, so wiring it twice sets the same
 * three functions again.
 */
fun wireShell() {
    Shell.alive = { CoreService.alive }
    Shell.seen = { Status.now.let { it.screen to it.note } }
    Shell.clock = { SystemClock.elapsedRealtime() }
}

/**
 * One of the three asks (PLAN_ANDROID_5_SHELL.md 3.2). [ok] is measured
 * every time this list is built and never remembered: the player switches a
 * service off between two sittings the way they switch ADB debugging off,
 * and a remembered answer is not a measurement.
 */
class Ask(
    val key: String,
    val label: String,
    val headline: String,
    val why: String,
    val glyph: String,
    val button: String,
    val ok: Boolean,
    /** What the status card says in amber while this one is missing. */
    val missing: String,
    /**
     * A second, quieter way out, under the button. Only the accessibility
     * ask has one, and it is there because the first way can be barred:
     * Android 13 greys the switch of a sideloaded app out until "Allow
     * restricted settings" has been ticked, and that tick is in App info,
     * where [Settings.ACTION_ACCESSIBILITY_SETTINGS] does not lead.
     */
    val alt: Alt? = null,
    val fix: (Activity) -> Unit,
)

/** A named second action of an [Ask]; see [Ask.alt]. */
class Alt(val label: String, val go: (Activity) -> Unit)

object Setup {
    const val REQ_NOTIFY = 1

    fun asks(a: Activity): List<Ask> {
        val a11yOn = DigiAutotapService.enabledInSettings(a)
        val a11yRunning = DigiAutotapService.instance != null
        val notifyOk = a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            a.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val batteryOk = a.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(a.packageName)
        return listOf(
            Ask("acc", "Accessibility service",
                "Let DigiAutotap see the game",
                "The accessibility service is the only way an app can see the screen and " +
                    "tap for you. What it sees stays on the phone.\n\n" +
                    "If the switch is greyed out and Android says the setting is " +
                    "unavailable for your security, open App info, tap the three dots at " +
                    "the top right, choose \"Allow restricted settings\", then come back.",
                "◎", "Open settings",
                a11yOn && a11yRunning,
                "The accessibility service is off, so DigiAutotap cannot see the game",
                Alt("Open app info") {
                    it.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${it.packageName}")))
                }) {
                it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Ask("notif", "Notifications",
                "Keep the switch in reach",
                "A notification stays while DigiAutotap runs, with Pause, Stop and Open. " +
                    "Without it there is no switch outside the app.",
                "◈", "Allow notifications",
                notifyOk,
                "Without notifications there is no pause switch outside the app") {
                it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            },
            Ask("batt", "Battery optimisation",
                "Stay awake in the background",
                "Android stops apps it thinks are idle. Turning this off keeps DigiAutotap " +
                    "running while you play.",
                "◐", "Open settings",
                batteryOk,
                "Android may stop DigiAutotap in the background") {
                it.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${it.packageName}")))
            },
        )
    }

    /**
     * The accessibility ask, with the one state that reads as neither on nor
     * off: switched on in the settings and not running. It is worth its own
     * sentence because switching it off and on again is the fix, and "off"
     * would send the player looking for something that is already ticked.
     */
    fun accessibilityState(a: Activity): String = when {
        DigiAutotapService.instance != null -> "on and running"
        DigiAutotapService.enabledInSettings(a) ->
            "switched on but not running -- switch it off and on again"
        else -> "off"
    }
}
