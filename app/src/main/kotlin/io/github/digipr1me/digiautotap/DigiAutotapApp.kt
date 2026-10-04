package io.github.digipr1me.digiautotap

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import io.github.digipr1me.digiautotap.core.AssetSource
import io.github.digipr1me.digiautotap.core.Census
import io.github.digipr1me.digiautotap.core.Chain
import io.github.digipr1me.digiautotap.core.HelperLog
import io.github.digipr1me.digiautotap.core.QuestSkill
import io.github.digipr1me.digiautotap.core.Settings
import io.github.digipr1me.digiautotap.core.Stored
import io.github.digipr1me.digiautotap.core.SkillStats
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Per process, once: the log file a shared ZIP carries, and where
 * things live. The core (the loop, later the director) lives in
 * [CoreService], never here and never in an Activity (PLAN_ANDROID_5_SHELL.md 4).
 */
class DigiAutotapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The three things core's Shell cannot know by itself, once per
        // process, before anything can ask it what state DigiAutotap is in.
        wireShell()
        // Light or dark, before the first view of any of them is built: the
        // overlay and the notification come from this process too, and a
        // choice only the Activity knew about would be the phone's setting
        // to them.
        Theme.load(SettingsStore(this))
        val file = Paths.logFile(this)
        LogFile.rotate(file)
        // Every line also goes to the file, so that a shared log still has
        // the lines of a process the system has already killed.
        HelperLog.listen { line -> LogFile.append(file, line) }
        // After the log, so that the crash's own line reaches the file.
        Crashes.install(this)
        // After the log too, so that what it took is said there.
        thread(name = "kept-frames") { KeptFrames.clearOld(this) }
    }
}

/**
 * The day the TODAY card means: the game's, from one reset to the next
 * ([QuestSkill.statsDay], 08:00 Vienna), the clock every daily limit already
 * kept. Until 2026-10-04 it was the phone's own date, and the card fell at
 * midnight in the phone's zone while the day's tickets and ads ran on to
 * eight. core takes it as a number ([SkillStats]) because a test cannot wait
 * for the reset.
 */
fun today(): Long = QuestSkill.statsDay(System.currentTimeMillis() / 1000.0)

/**
 * The phone counted once per day, week and month ([Census]), off the
 * calling thread. Asked at every opening of the app and once an hour by the
 * core, because a player who only ever runs the tasks never opens the app.
 * A debuggable build is not counted: those are this project's own.
 */
fun census(c: Context) {
    val app = c.applicationContext
    if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return
    thread(name = "census") {
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }
            .getOrNull() ?: return@thread
        val store = SettingsStore(app)
        // A phone that has been through the first-run pages was here before
        // the census, and is not a new install for having no stamp yet.
        val known = store.bool(MainActivity.ONBOARDING_SEEN, false)
        Census.count(store, version, known, device = device(app, store))?.let { HelperLog.line(it) }
    }
}

/** What the census says of the phone ([Census.Device]); a description, never an id. */
private fun device(c: Context, store: SettingsStore): Census.Device? = runCatching {
    val bounds = c.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    val cut = c.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.cutout
    Census.Device(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
                  bounds.width(), bounds.height(), c.resources.displayMetrics.densityDpi,
                  cut?.safeInsetTop ?: 0, cut?.safeInsetBottom ?: 0,
                  cut?.safeInsetLeft ?: 0, cut?.safeInsetRight ?: 0,
                  store.str(Census.COLOR_SPACE_KEY, ""))
}.getOrNull()

/** Where things are kept. Names follow the PC's so a phone file sits beside its siblings. */
object Paths {
    /** The PC's STATE_FILE is digiautotap.json; the same name and the same keys. */
    fun settingsFile(c: Context) = File(c.filesDir, "digiautotap.json")
    fun logFile(c: Context) = File(c.filesDir, "digiautotap.log")
    fun supporterFile(c: Context) = File(c.filesDir, io.github.digipr1me.digiautotap.core.Unlock.SAVED_FILE)

    /** The redeemed code in the clear, for the Settings card alone (Unlock.CODE_FILE). */
    fun supporterCodeFile(c: Context) = File(c.filesDir, io.github.digipr1me.digiautotap.core.Unlock.CODE_FILE)

    /**
     * A random install id, for the one phone that has no Android id to make
     * one from (`Supporter.installId`, `Unlock.installIdOf`). Everywhere
     * else the id is derived and this file is never written.
     */
    fun installFile(c: Context) = File(c.filesDir, io.github.digipr1me.digiautotap.core.Unlock.INSTALL_FILE)

    /**
     * The debug dumps, under getExternalFilesDir, which ADB can pull from the
     * PC: debug_director and friends, the PC's own folder names
     * (PLAN_ANDROID_APP.md 3.5). Written only with the developer's switch
     * since 1.3's fourth candidate ([KeptFrames]).
     */
    fun debugRoot(c: Context): File = c.getExternalFilesDir(null) ?: c.filesDir
    fun debugDir(c: Context, family: String) = File(debugRoot(c), "debug_$family").apply { mkdirs() }
}

/**
 * No picture of the game is kept on a player's phone since 1.3's fourth
 * candidate (notes/reports.md, "Nothing is sent and no picture of the game
 * is kept: the player shares the log"). Until then the director kept a frame
 * at every park and while a task ran, for "Report a problem" to send, and
 * 1.2 kept them without a limit.
 *
 * The one way round it is the developer's switch: a file named [SWITCH] in
 * the app's external folder ([Paths.debugRoot]), which only adb writes
 * (`adb shell touch /sdcard/Android/data/<package>/files/keep_frames`). With
 * it the director's frames go to `debug_director/` as before, without a
 * limit -- they are what the corpus grows from on LDPlayer -- and the old
 * folders stay.
 */
object KeptFrames {
    const val SWITCH = "keep_frames"

    /** What a report left in `files/` until 1.3's fourth candidate, gone at every start. */
    val LEFT_FILES = listOf("report_open", "report_last", "report_tag", "report_url",
                            "crash_report.txt", "crash_pending.txt")

    /** Asked at every frame the director would keep: a stat, never cached, so a touch takes effect at once. */
    fun on(c: Context): Boolean = File(Paths.debugRoot(c), SWITCH).isFile

    /**
     * At every start of the process: every `debug_*` folder, and what a
     * report left, deleted -- said once in the log when it took something.
     * A phone that ran 1.2 or a candidate of 1.3 gets its space back here.
     * With the switch the folders stay, and the log says the switch is on.
     */
    fun clearOld(c: Context) {
        val root = Paths.debugRoot(c)
        if (on(c)) {
            HelperLog.line("frames: kept in ${root.path}/debug_director (the $SWITCH file is there)")
        } else {
            var pictures = 0
            var bytes = 0L
            val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith("debug_") }.orEmpty()
            for (d in dirs) {
                for (f in d.walkBottomUp()) {
                    if (f.isFile) {
                        val size = f.length()
                        val png = f.name.endsWith(".png")
                        if (f.delete()) { bytes += size; if (png) pictures += 1 }
                    } else f.delete()
                }
            }
            if (dirs.isNotEmpty()) HelperLog.line("frames: ${dirs.size} debug folder(s) deleted, $pictures " +
                "picture(s), %.1f MB -- DigiAutotap keeps no pictures of the game".format(
                    java.util.Locale.ROOT, bytes / 1048576.0))
        }
        var files = 0
        for (name in LEFT_FILES) if (File(c.filesDir, name).delete()) files += 1
        val queue = File(c.cacheDir, "report")
        if (queue.exists() && queue.deleteRecursively()) files += 1
        val store = SettingsStore(c)
        if (store.num("crash_seen_at", -1.0) >= 0) { store.put("crash_seen_at", null); files += 1 }
        if (files > 0) HelperLog.line("frames: $files thing(s) the old report left deleted")
    }
}

/** templates/ and digits/ out of the APK, as bytes (core's [AssetSource]). */
class ApkAssets(private val c: Context) : AssetSource {
    override fun bytes(path: String): ByteArray? =
        runCatching { c.assets.open(path).use { it.readBytes() } }.getOrNull()
}

/**
 * digiautotap.json: the PC's settings file, read and written whole, every
 * change saved at once. What the keys mean and what they default to is
 * core's [Stored]; this is the six shapes of [Settings] over `org.json`,
 * and nothing else. A store is built where it is asked, so that it reads
 * the file as it stands (CoreService, `store`).
 */
class SettingsStore(private val c: Context) : Settings {
    private val file = Paths.settingsFile(c)
    private var data: JSONObject = read()

    private fun read(): JSONObject =
        runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    override fun bool(key: String, default: Boolean) = data.optBoolean(key, default)
    override fun num(key: String, default: Double) = data.optDouble(key, default)
    override fun str(key: String, default: String) = data.optString(key, default)

    override fun strings(key: String, default: List<String>): List<String> {
        val arr = data.optJSONArray(key) ?: return default
        return (0 until arr.length()).map { arr.optString(it) }
    }

    override fun ints(key: String, default: Map<String, Int>): Map<String, Int> {
        val obj = data.optJSONObject(key) ?: return default
        return obj.keys().asSequence().associateWith { obj.optInt(it) }
    }

    override fun steps(key: String): List<Chain.Step>? {
        val arr = data.optJSONArray(key) ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Chain.Step(it.optString("key"), it.optInt("minutes", 0)) }
        }
    }

    /**
     * **Read, change, write** -- the file is read again here and not only
     * when the store was built.
     *
     * A store holds the whole file and [save] writes the whole file back, so
     * two stores are two copies and the second save undoes the first. Two of
     * them exist all the time: the Activity keeps one while a page is open
     * and the overlay's view keeps one of its own. (The director builds a
     * fresh one per question, which is the same rule from the other side --
     * and the reason it never hit this.)
     *
     * Measured live on 2026-09-21: one tap on the dot set the Tower's point
     * through the Activity's store and slid the dot back to its measured
     * place through the view's, in that order, and the second save came from
     * a copy that had never seen `tower_fx`. The log said the point was set;
     * the file did not have it.
     *
     * It costs a file read per change, which is what a settings file is for.
     */
    @Synchronized
    override fun put(key: String, value: Any?) {
        data = read()
        if (value == null) data.remove(key) else data.put(key, value)
        save()
    }

    /**
     * Several keys in one read, change and write: the minigames' two
     * switches (Stored.include) and a day's count with its day stamp, which
     * a second store must never see one without the other.
     */
    @Synchronized
    override fun putAll(values: Map<String, Any?>) {
        data = read()
        for ((key, value) in values) if (value == null) data.remove(key) else data.put(key, value)
        save()
    }

    override fun putInts(key: String, value: Map<String, Int>) =
        put(key, JSONObject().apply { value.forEach { (k, v) -> put(k, v) } })

    override fun putSteps(key: String, value: List<Chain.Step>) = put(key, JSONArray().apply {
        value.forEach { put(JSONObject().put("key", it.key).put("minutes", it.minutes)) }
    })

    private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(data.toString(2))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
