package io.github.digipr1me.digiautotap

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.StatFs
import android.provider.OpenableColumns
import android.view.Display
import io.github.digipr1me.digiautotap.core.Census
import io.github.digipr1me.digiautotap.core.HelperLog
import io.github.digipr1me.digiautotap.core.Stored
import org.opencv.core.Core
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Share on the Log page: the log as a ZIP, handed to Android's share sheet,
 * and the player picks where it goes -- Discord, a chat, a mail. Nothing
 * leaves the phone by itself. Until 1.3's fourth candidate this was "Report
 * a problem", which sent the log with pictures of the game to a server by
 * itself, and "Share instead" on its pictures page (notes/reports.md,
 * "Nothing is sent and no picture of the game is kept: the player shares
 * the log").
 *
 * What is in it, all text and DEFLATED: about.txt ([about]), the two log
 * files, the settings file, crash.txt when there is one ([Crashes.text]),
 * and logcat.txt. What is never in it, because the ZIP lands in a public
 * channel: supporter.txt, the token, the activation's install id, the code
 * the Settings card shows (Unlock.CODE_FILE); the log names a code covered
 * but for its first group (Unlock.masked). NetworkTest holds this file to
 * that. No picture.
 */
object LogShare {

    /** A shared ZIP is gone from the cache at the first opening of the app an hour after it was built. */
    const val KEEP_MS = 3600_000L

    private fun dir(c: Context) = File(c.cacheDir, "share")

    /** The ZIP, in cache/share, the one before it deleted. Not on the main thread: logcat takes a moment. */
    fun build(c: Context): File {
        val dir = dir(c).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val zip = File(dir, "digiautotap-log-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for ((name, bytes) in texts(c)) {
                out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
            }
        }
        HelperLog.line("log share: ${zip.name}, ${zip.length() / 1024} KB")
        return zip
    }

    /** The share sheet, over [zip] as [ShareProvider] serves it. */
    fun share(c: Context, zip: File) {
        val uri = Uri.parse("content://${ShareProvider.authority(c)}/${zip.name}")
        val send = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "DigiAutotap log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(send, "Share log")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /**
     * At every opening of the app: a shared ZIP older than [KEEP_MS] goes.
     * Not at once -- the app it was shared with may still be reading it --
     * but not until the next share either, which may be never.
     */
    fun sweep(c: Context) {
        val now = System.currentTimeMillis()
        val old = dir(c).listFiles()?.filter { now - it.lastModified() > KEEP_MS } ?: return
        var bytes = 0L
        for (f in old) { val size = f.length(); if (f.delete()) bytes += size }
        if (old.isNotEmpty()) HelperLog.line("log share: ${old.size} shared ZIP(s) of ${bytes / 1024} KB deleted")
    }

    /** The entries of the ZIP, in the order they go into it. */
    fun texts(c: Context): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        out += "about.txt" to about(c).toByteArray()
        val log = Paths.logFile(c)
        LogFile.older(log).takeIf { it.isFile }?.let { out += "digiautotap.log.1" to it.readBytes() }
        log.takeIf { it.isFile }?.let { out += "digiautotap.log" to it.readBytes() }
        Paths.settingsFile(c).takeIf { it.isFile }?.let { out += "digiautotap.json" to it.readBytes() }
        Crashes.text(c)?.let { out += "crash.txt" to it.toByteArray() }
        out += "logcat.txt" to logcat()
        return out
    }

    private fun debuggable(c: Context) =
        c.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun game(c: Context): String? =
        DigiAutotapService.instance?.gamePackage ?: runCatching { DigiAutotapService.findGame(c) }.getOrNull()

    private fun gameVersion(c: Context): String? = game(c)?.let { pkg ->
        runCatching { c.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
    }

    /**
     * What the phone is. Everything a phone finding has asked for the first
     * time round and had to wait a day for: the game's own version, the
     * mode, the data saver, the cutout.
     */
    fun about(c: Context): String {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        val m = c.resources.displayMetrics
        val store = SettingsStore(c)
        val game = game(c)
        return buildString {
            append("DigiAutotap ${pi.versionName} (${pi.longVersionCode})" +
                (if (debuggable(c)) ", debug build" else "") + "\n")
            append("time ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())}, " +
                "zone ${TimeZone.getDefault().id}, language ${Locale.getDefault().toLanguageTag()}\n")
            append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("fingerprint ${Build.FINGERPRINT}, hardware ${Build.HARDWARE}\n")
            append("emulator suspected: ${emulator()?.let { "yes ($it)" } ?: "no"}\n")
            append("display ${m.widthPixels} x ${m.heightPixels} dpi ${m.densityDpi}, cutout ${cutout(c)}\n")
            // The screenshot's colour space (PLAN_FORMATE.md 12: a P3 phone
            // puts P3 values against sRGB thresholds), from the service's
            // first screenshot, and the service's last "display:" line, which
            // names the game's window in the picture (PLAN_FORMATE.md 10).
            append("screenshot colour space ${store.str(Census.COLOR_SPACE_KEY, "").ifEmpty { "unknown" }}\n")
            append("last ${DigiAutotapService.instance?.displaySaid ?: "display: not read since the service started"}\n")
            append("OpenCV ${runCatching { Core.VERSION }.getOrDefault("not loaded")}\n")
            append("game ${game ?: "not found"}" +
                (game?.let { gameVersion(c)?.let { " $it" } } ?: "") + "\n")
            append("mode ${Stored.mode(store)}\n")
            append("supporter ${Supporter.unlocked == true}\n")
            append("status ${Status.now}\n")
            append("free storage ${mb(freeAt(c.filesDir))} MB\n")
            append("data saver ${dataSaver(c)}\n")
        }
    }

    private fun mb(bytes: Long) = "%.1f".format(Locale.ROOT, bytes / 1048576.0)

    /**
     * "generic", "ldplayer", "qemu" and friends in what the build says of
     * itself: a hint in about.txt, never a lock. Logs from other players'
     * emulators are wanted.
     */
    private fun emulator(): String? {
        val said = listOf(Build.FINGERPRINT, Build.HARDWARE, Build.MODEL, Build.PRODUCT, Build.BRAND,
                          Build.MANUFACTURER, Build.DEVICE, Build.BOARD).joinToString(" ").lowercase()
        return listOf("generic", "ldplayer", "qemu", "emulator", "sdk_gphone", "ranchu", "goldfish", "vbox",
                      "nox", "bluestacks", "mumu").firstOrNull { it in said }
    }

    private fun cutout(c: Context): String = runCatching {
        val d = c.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        d?.cutout?.let { "top ${it.safeInsetTop}, bottom ${it.safeInsetBottom}, " +
            "left ${it.safeInsetLeft}, right ${it.safeInsetRight}" } ?: "none"
    }.getOrDefault("unknown")

    /** Whether the data saver holds this app back when it is not in front. No permission asked. */
    private fun dataSaver(c: Context): String = runCatching {
        when (c.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "off"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "on, this app exempt"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "on, this app restricted"
            else -> "unknown"
        }
    }.getOrDefault("unknown")

    fun freeAt(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(-1L)

    /**
     * The app's own last lines in the system log: what HelperLog never sees
     * -- the overlay's BadTokenException, "failed to attach", the system's
     * warnings about this app. An app reads its own lines with no permission.
     */
    private fun logcat(): ByteArray = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "500", "--pid=${Process.myPid()}")
            .redirectErrorStream(true).start()
        val bytes = p.inputStream.use { it.readBytes() }
        p.waitFor(5, TimeUnit.SECONDS)
        bytes
    }.getOrElse { "logcat could not be read: $it\n".toByteArray() }
}

/**
 * Serves the one ZIP in cache/share to whatever the player shares it with.
 * Its own few lines instead of AndroidX's FileProvider, because the app
 * carries no AndroidX at all. Not exported: a reader gets in only through
 * the grant on the share intent.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val dir = File(context!!.cacheDir, "share")
        val f = File(dir, uri.lastPathSegment ?: "")
        require(f.parentFile == dir && f.exists()) { "no such file" }
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri) = "application/zip"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map<String, Any?> {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> f.name
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?) = 0

    companion object {
        fun authority(c: Context) = "${c.packageName}.share"
    }
}

/**
 * The log file, and the one before it (PLAN_BUG_REPORT.md 4). It was cut to
 * its last 2000 lines at every start once it passed half a megabyte -- a few
 * hours of a running bot -- and a log shared in the evening for the
 * morning's fault found nothing of it. Now a file past [ROTATE_BYTES]
 * becomes `digiautotap.log.1` and a new one begins; a shared ZIP takes both.
 * Asked at the start of the process and every [CHECK_EVERY] lines, because
 * the process of a bot that runs for days starts once.
 */
object LogFile {
    const val ROTATE_BYTES = 2L * 1024 * 1024
    private const val CHECK_EVERY = 1000

    fun older(log: File) = File(log.path + ".1")

    private var lines = 0

    @Synchronized
    fun append(log: File, line: String) {
        if (++lines >= CHECK_EVERY) { lines = 0; rotate(log) }
        runCatching { log.appendText(line + "\n") }
    }

    @Synchronized
    fun rotate(log: File) {
        if (!log.isFile || log.length() < ROTATE_BYTES) return
        val old = older(log)
        old.delete()
        if (!log.renameTo(old)) runCatching { log.copyTo(old, overwrite = true); log.writeText("") }
    }
}

/**
 * A crash (PLAN_BUG_REPORT.md 7), in the log and in a shared ZIP's
 * crash.txt. Nothing is asked after one since 1.3's fourth candidate: the
 * sheet that offered to send the log went with the report.
 *
 * Two sources. A Java exception nobody caught is written by the handler
 * ([install]) into the log, its stack trace with it, before the old handler
 * ends the process. What no Java handler sees -- a native crash in OpenCV,
 * an ANR, the system killing the app for "Too many Binders" -- is asked of
 * the system when a ZIP is built ([ActivityManager.getHistoricalProcessExitReasons],
 * API 30, which is the app's floor): crashes, native crashes and ANRs count,
 * a force-stop by the player does not.
 */
object Crashes {
    /** How far back crash.txt names what the system remembers. */
    const val SINCE_MS = 30L * 24 * 3600 * 1000

    /** The lines of a stack trace the log takes: the cause is at the top, the rest is Android's. */
    const val TRACE_LINES = 60

    private val COUNTED = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                                ApplicationExitInfo.REASON_ANR)

    fun install(c: Context) {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // Nothing in here may throw, and nothing may stop the old
            // handler from running: without it the process hangs instead of
            // ending.
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                HelperLog.line("CRASH in thread ${t.name}: " +
                    trace.lineSequence().take(TRACE_LINES).joinToString("\n"))
            }
            if (old != null) old.uncaughtException(t, e)
            else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    private fun exits(c: Context): List<ApplicationExitInfo> = runCatching {
        c.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(c.packageName, 0, 16)
            .filter { it.reason in COUNTED }
    }.getOrDefault(emptyList())

    /**
     * crash.txt: the system's account of the last crashes within [SINCE_MS],
     * with the first lines of the trace it kept for a native crash or an
     * ANR. Null when there is none.
     */
    fun text(c: Context): String? {
        val parts = ArrayList<String>()
        val since = System.currentTimeMillis() - SINCE_MS
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
        for (e in exits(c).filter { it.timestamp >= since }.take(5)) {
            val what = when (e.reason) {
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                ApplicationExitInfo.REASON_ANR -> "ANR"
                else -> "reason ${e.reason}"
            }
            val trace = runCatching {
                e.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(150).joinToString("\n") }
            }.getOrNull()
            parts += "system: $what at ${fmt.format(Date(e.timestamp))}, process ${e.processName}, " +
                "importance ${e.importance}, ${e.description ?: "no description"}" +
                (trace?.let { "\n$it" } ?: "")
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("\n\n") + "\n"
    }
}
