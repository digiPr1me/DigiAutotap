package io.github.digipr1me.digiautotap

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.digipr1me.digiautotap.core.AssetSource
import io.github.digipr1me.digiautotap.core.Census
import io.github.digipr1me.digiautotap.core.Chain
import io.github.digipr1me.digiautotap.core.HelperLog
import io.github.digipr1me.digiautotap.core.Settings
import io.github.digipr1me.digiautotap.core.Stored
import io.github.digipr1me.digiautotap.core.SkillStats
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * Per process, once: the log file the debug package carries, and where
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
        trim(file)
        // Every line also goes to the file, so that the debug package still
        // has the lines of a process the system has already killed.
        HelperLog.listen { line ->
            runCatching { file.appendText(line + "\n") }
        }
    }

    /** Keeps the file to its last lines once it grows past half a megabyte. */
    private fun trim(file: File) {
        if (!file.exists() || file.length() < 512 * 1024) return
        runCatching { file.writeText(file.readLines().takeLast(2000).joinToString("\n") + "\n") }
    }
}

/**
 * The day the TODAY card means: the phone's own date, in the phone's own
 * time zone. core takes it as a number ([SkillStats]) because core has no
 * business knowing where the player is, and because a test cannot wait for
 * midnight.
 */
fun today(): Long = LocalDate.now().toEpochDay()

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
        Census.count(store, version, known)?.let { HelperLog.line(it) }
    }
}

/** Where things are kept. Names follow the PC's so a phone file sits beside its siblings. */
object Paths {
    /** The PC's STATE_FILE is digiautotap.json; the same name and the same keys. */
    fun settingsFile(c: Context) = File(c.filesDir, "digiautotap.json")
    fun logFile(c: Context) = File(c.filesDir, "digiautotap.log")
    fun supporterFile(c: Context) = File(c.filesDir, io.github.digipr1me.digiautotap.core.Unlock.SAVED_FILE)

    /**
     * A random install id, for the one phone that has no Android id to make
     * one from (`Supporter.installId`, `Unlock.installIdOf`). Everywhere
     * else the id is derived and this file is never written.
     */
    fun installFile(c: Context) = File(c.filesDir, io.github.digipr1me.digiautotap.core.Unlock.INSTALL_FILE)

    /**
     * The debug dumps, under getExternalFilesDir, which ADB can pull from the
     * PC and the debug package zips: debug_dungeon and friends, the PC's own
     * folder names (PLAN_ANDROID_APP.md 3.5).
     */
    fun debugRoot(c: Context): File = c.getExternalFilesDir(null) ?: c.filesDir
    fun debugDir(c: Context, family: String) = File(debugRoot(c), "debug_$family").apply { mkdirs() }
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
