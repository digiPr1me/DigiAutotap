package io.github.digipr1me.digiautotap.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The update check against a real server, as ActivationTest plays the
 * redemption: what GitHub's list looks like, and the ways it can say nothing.
 */
class UpdatesTest {

    private class Seen {
        var method: String? = null
        var agent: String? = null
        var body: String? = null
    }

    private fun serving(code: Int, body: String, use: (String, Seen) -> Unit) {
        val seen = Seen()
        val pool = Executors.newCachedThreadPool { r -> Thread(r, "updates-test").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/releases") { exchange ->
            seen.method = exchange.requestMethod
            seen.agent = exchange.requestHeaders.getFirst("User-Agent")
            seen.body = exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }
            val raw = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, raw.size.toLong())
            exchange.responseBody.use { it.write(raw) }
        }
        server.executor = pool
        server.start()
        try {
            use("http://127.0.0.1:${server.address.port}/releases", seen)
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    /** A releases list in GitHub's shape, trimmed to what is read and a little of what is not. */
    private fun list(vararg tags: String, prerelease: Boolean = false) = tags.joinToString(",", "[", "]") {
        """{"html_url":"https://github.com/digiPr1me/digiautotap/releases/tag/$it",""" +
            """"tag_name": "$it","name":"$it","prerelease":$prerelease,""" +
            """"author":{"html_url":"https://github.com/digiPr1me"},"body":"notes for $it"}"""
    }

    @Test
    fun `a higher tag is a newer version, and the request says nothing about the phone`() {
        serving(200, list("android-v0.3", "android-v0.2")) { url, seen ->
            val found = assertIs<Updates.Result.Newer>(Updates.check("0.2", url, 4_000))
            assertEquals("0.3", found.version)
            assertEquals("https://github.com/digiPr1me/digiautotap/releases/tag/android-v0.3", found.page)
            assertEquals("GET", seen.method)
            assertEquals("DigiAutotap", seen.agent)
            assertEquals("", seen.body)
        }
    }

    @Test
    fun `the same or a lower tag is current`() {
        serving(200, list("android-v0.2", "android-v0.1")) { url, _ ->
            assertIs<Updates.Result.Current>(Updates.check("0.2", url, 4_000))
        }
        serving(200, list("android-v0.2")) { url, _ ->
            assertIs<Updates.Result.Current>(Updates.check("0.3", url, 4_000))
        }
    }

    @Test
    fun `a prerelease counts, because the first releases are all prereleases`() {
        serving(200, list("android-v0.3", prerelease = true)) { url, _ ->
            assertEquals("0.3", assertIs<Updates.Result.Newer>(Updates.check("0.2", url, 4_000)).version)
        }
    }

    @Test
    fun `the newest is the highest tag, not the first one`() {
        // GitHub sorts by date; a fix to an old line published later comes first.
        assertEquals("0.10", Updates.newest(list("android-v0.9.1", "android-v0.10", "android-v0.9")))
    }

    @Test
    fun `tags that are not app releases are left alone`() {
        assertNull(Updates.newest(list("v5.0", "pc-v1.2", "android-vnext")))
        serving(200, list("v9.9")) { url, _ ->
            assertIs<Updates.Result.Unreachable>(Updates.check("0.2", url, 4_000))
        }
    }

    @Test
    fun `no repository, an empty list and no server are all unreachable`() {
        serving(404, """{"message":"Not Found"}""") { url, _ ->
            val r = assertIs<Updates.Result.Unreachable>(Updates.check("0.2", url, 4_000))
            assertTrue("404" in r.why, r.why)
        }
        serving(200, "[]") { url, _ ->
            assertIs<Updates.Result.Unreachable>(Updates.check("0.2", url, 4_000))
        }
        // A port nothing listens on: bound and closed again.
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertIs<Updates.Result.Unreachable>(Updates.check("0.2", "http://127.0.0.1:$port/releases", 2_000))
    }

    @Test
    fun `versions compare as dotted numbers`() {
        assertEquals(1, Updates.compare("0.10", "0.9"))
        assertEquals(0, Updates.compare("1.0", "1"))
        assertEquals(-1, Updates.compare("0.2", "0.2.1"))
        assertNull(Updates.compare("0.3-debug", "0.2"))
    }

    @Test
    fun `an installed version that is not dotted numbers is never told to update`() {
        serving(200, list("android-v9.0")) { url, _ ->
            assertIs<Updates.Result.Current>(Updates.check("0.3-debug", url, 4_000))
        }
    }
}
