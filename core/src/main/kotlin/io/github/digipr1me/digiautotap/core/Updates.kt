package io.github.digipr1me.digiautotap.core

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Is there a newer DigiAutotap than this one.
 *
 * Asked once each time the app is opened, and again when the player taps
 * "Check for updates": one GET to GitHub's list of this project's releases,
 * which carries nothing about the phone -- no id, no settings, no code. What
 * comes back is read for its tags, `android-v<versionName>` as the release
 * recipe writes them (NOTES.md, "A release, by hand"), and the highest one
 * is compared with the version that is installed.
 *
 * The list and not `releases/latest`: the recipe publishes `--prerelease`
 * until a whole day has run, and `latest` does not see a prerelease, so the
 * first releases would never be found.
 *
 * Nothing here decides anything but a notice. A check that fails says
 * [Result.Unreachable] and the app carries on as it was; the supporter unlock
 * never waits on this and never asks the network at start ([Unlock]).
 *
 * Plain `HttpURLConnection` for the same reason as [Activation]: nothing to
 * add to the build, and the JVM tests can play a real server against it.
 */
object Updates {

    /** The project's releases, newest first; ten is more than one page of history needs. */
    const val RELEASES_API = "https://api.github.com/repos/digiPr1me/digiautotap/releases?per_page=10"

    /** The page a player opens when there is nothing more specific to open. */
    const val RELEASES_PAGE = "https://github.com/digiPr1me/digiautotap/releases"

    /** The release recipe's tag, step 6. A tag without it is not an app release. */
    const val TAG_PREFIX = "android-v"

    sealed class Result {
        /** [version] is higher than the one installed; [page] is its release. */
        class Newer(val version: String, val page: String) : Result()

        /** Nothing higher was found. */
        object Current : Result()

        /** Nothing was learned: no network, no repository yet, a timeout, or an answer with no release in it. */
        class Unreachable(val why: String) : Result()
    }

    /**
     * Ten releases with their notes and assets are a few tens of kilobytes;
     * what is past this is not read, and the tags near the top -- the newest
     * -- are what matter.
     */
    private const val MAX_BODY = 512 * 1024

    private val TAG = Regex(""""tag_name"\s*:\s*"${Regex.escape(TAG_PREFIX)}([0-9]+(?:\.[0-9]+)*)"""")

    /** The release page of one version. */
    fun page(version: String) = "$RELEASES_PAGE/tag/$TAG_PREFIX$version"

    /**
     * One GET, with [timeoutMs] as both the connect and the read timeout.
     * Runs on a worker thread; nothing is retried, the next opening asks again.
     */
    fun check(installed: String, url: String = RELEASES_API, timeoutMs: Int = 8_000): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                // GitHub's API refuses a request without a User-Agent.
                setRequestProperty("User-Agent", "DigiAutotap")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            when (val code = conn.responseCode) {
                200 -> {
                    val newest = newest(read(conn.inputStream))
                    when {
                        newest == null -> Result.Unreachable("no release carries a $TAG_PREFIX tag")
                        (compare(newest, installed) ?: 0) > 0 -> Result.Newer(newest, page(newest))
                        else -> Result.Current
                    }
                }
                // 404 is also what GitHub says while the repository is not public yet.
                else -> Result.Unreachable("GitHub answered $code")
            }
        } catch (e: Exception) {
            Result.Unreachable(e.javaClass.simpleName.ifEmpty { "no connection" })
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** The highest `android-v` version among the tags in a releases list, or null if there is none. */
    fun newest(json: String): String? =
        TAG.findAll(json).map { it.groupValues[1] }
            .fold(null as String?) { best, v -> if (best == null || compare(v, best)!! > 0) v else best }

    /**
     * Dotted numbers, part by part, a missing part counting as 0: 0.10 is
     * above 0.9, and 1.0 is 1. Null when either side is not dotted numbers --
     * a versionName like "0.3-debug" is never taken for older or newer.
     */
    fun compare(a: String, b: String): Int? {
        val x = parts(a) ?: return null
        val y = parts(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun parts(v: String): List<Int>? =
        v.trim().split('.').map { it.toIntOrNull() ?: return null }

    /** At most [MAX_BODY], by hand, as [Activation] reads: `readNBytes` is API 33. */
    private fun read(stream: InputStream): String = stream.use {
        val buffer = ByteArray(MAX_BODY)
        var filled = 0
        while (filled < buffer.size) {
            val n = it.read(buffer, filled, buffer.size - filled)
            if (n < 0) break
            filled += n
        }
        String(buffer, 0, filled, Charsets.UTF_8)
    }
}
