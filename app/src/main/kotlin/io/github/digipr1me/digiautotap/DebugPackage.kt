package io.github.digipr1me.digiautotap

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.digipr1me.digiautotap.core.HelperLog
import org.opencv.core.Core
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Share debug package" (PLAN_ANDROID_APP.md 3.5): the log, the settings and
 * every debug_* folder in one ZIP, handed to the share menu. It is the only
 * way a phone bug reaches the corpus, and so the start of every phone
 * finding, not its attachment.
 */
object DebugPackage {

    fun build(c: Context): File {
        val dir = File(c.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val zip = File(dir, "digiautotap-debug-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun add(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
            }
            add("about.txt", about(c).toByteArray())
            Paths.logFile(c).takeIf { it.exists() }?.let { add("digiautotap.log", it.readBytes()) }
            Paths.settingsFile(c).takeIf { it.exists() }?.let { add("digiautotap.json", it.readBytes()) }
            val root = Paths.debugRoot(c)
            root.listFiles { f -> f.isDirectory && f.name.startsWith("debug_") }?.forEach { d ->
                d.walkTopDown().filter { it.isFile }.forEach { f ->
                    add(f.relativeTo(root).invariantSeparatorsPath, f.readBytes())
                }
            }
        }
        HelperLog.line("debug package: ${zip.name}, ${zip.length() / 1024} KB")
        return zip
    }

    private fun about(c: Context): String {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        val m = c.resources.displayMetrics
        return "DigiAutotap ${pi.versionName}\n" +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "display ${m.widthPixels} x ${m.heightPixels} dpi ${m.densityDpi}\n" +
            "OpenCV ${runCatching { Core.VERSION }.getOrDefault("not loaded")}\n" +
            "status ${Status.now}\n"
    }

    fun share(c: Context) {
        val zip = build(c)
        val uri = Uri.parse("content://${ShareProvider.authority(c)}/${zip.name}")
        val send = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "DigiAutotap debug package")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(send, "Share debug package")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
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
