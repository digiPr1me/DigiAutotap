package io.github.digipr1me.digiautotap.core

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Redeeming a supporter code, the one request that carries anything of the
 * player's.
 *
 * A supporter code is redeemed once, here: the digest of the code and a
 * random id for this installation go to the activation server, and a signed
 * token comes back that binds the two (PLAN_SUPPORTER_SERVER.md 2.1). After
 * that the phone never asks anything again -- [Unlock.parseToken] settles
 * every later start on its own, offline, and the app keeps working if this
 * server is gone for good.
 *
 * One of the two files that may open a connection; the other is [Updates],
 * which asks GitHub for the newest release and sends nothing of the phone's.
 * NetworkTest holds the list.
 *
 * It lives in core rather than in the shell for one reason: HttpURLConnection
 * is plain Java, so this can be tested on the JVM against a real server
 * (`com.sun.net.httpserver.HttpServer`, ActivationTest) rather than guessed
 * at behind Android.
 *
 * Redeeming fails hard. "The server did not answer" is an error the player
 * reads, never a shrug that unlocks anything: what is waved through here
 * would be waved through for everybody (PLAN_SUPPORTER_SERVER.md 1).
 */
object Activation {

    /** The server's four answers, the one Cloudflare gives in front of it, and the one the network gives instead. */
    sealed class Result {
        /** Bound to this installation. The token is not believed yet -- that is [Unlock.parseToken]. */
        class Bound(val token: String) : Result()

        /** No such code, or revoked. The server says one word for both, and so does the page. */
        object Unknown : Result()

        /** Both device slots are taken; only a release by hand frees one. */
        object InUse : Result()

        /**
         * The server was reached and turned the request away for now: 429 or
         * 503, neither of which the Worker ever says itself. The free plan
         * stops a Worker at 100,000 requests a day, until 00:00 UTC, and
         * answers every request after that with its own page, error 1027;
         * a script that hammers the address can use that up in minutes, and
         * no rule in front of a workers.dev address can stop it. Which of the
         * two codes 1027 comes with was not measured, so both count. The
         * player's connection is fine, and the page must not send them to
         * look for the fault there.
         */
        class Busy(val why: String) : Result()

        /** Nothing was learned: no network, no server, a timeout, or an answer that made no sense. */
        class Unreachable(val why: String) : Result()
    }

    /** What is read off an answer before giving up on it; a token is ~200 bytes. */
    private const val MAX_BODY = 8 * 1024

    private val TOKEN = Regex(""""token"\s*:\s*"([A-Za-z0-9_=-]+\.[A-Za-z0-9_=-]+)"""")

    /**
     * POST /v1/activate, once. [timeoutMs] is both the connect and the read
     * timeout, so the worst case is twice it and not a hang: this runs on a
     * worker thread while the player is watching the supporter page.
     *
     * Nothing here is retried. A retry after a 200 that could not be read
     * would spend a device slot a second time, and the player can press the
     * button again knowing what they are doing.
     */
    fun activate(url: String, digest: String, install: String, timeoutMs: Int = 10_000): Result {
        if (url.isBlank()) return Result.Unreachable("no activation server is set in this build")
        val body = """{"digest":"$digest","install":"$install"}""".toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body) }
            when (val code = conn.responseCode) {
                200 -> {
                    val token = TOKEN.find(read(conn.inputStream))?.groupValues?.get(1)
                    if (token == null) Result.Unreachable("the server's answer held no token")
                    else Result.Bound(token)
                }
                404 -> Result.Unknown
                409 -> Result.InUse
                429, 503 -> Result.Busy("the server answered $code")
                // 400 is the server saying this app sent nonsense, which is a
                // bug here and not something a player can act on; it lands
                // with the rest, with the number in the sentence so a log
                // says which it was.
                else -> Result.Unreachable("the server answered $code")
            }
        } catch (e: Exception) {
            // The class and not the message: a message is the library's to
            // word, and this sentence goes into a log players share.
            Result.Unreachable(e.javaClass.simpleName.ifEmpty { "no connection" })
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * At most [MAX_BODY], by hand: `InputStream.readNBytes` arrived on
     * Android in API 33 and minSdk here is 30, and a JVM test would never
     * have said so.
     */
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
