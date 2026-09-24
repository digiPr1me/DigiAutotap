package io.github.digipr1me.digiautotap.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The one request, against a real server.
 *
 * `com.sun.net.httpserver.HttpServer` is in the JDK and needs nothing
 * added; [Activation] is plain Java for exactly this reason, so the four
 * answers of the contract and the two ways a network fails are all played
 * here rather than guessed at behind Android (PLAN_SUPPORTER_SERVER.md 3.2).
 *
 * What this cannot say is whether the Worker answers like this. That is the
 * contract's job (section 2) and Session D's.
 */
class ActivationTest {

    /** What the last request carried, so the contract can be read off it. */
    private class Seen {
        var method: String? = null
        var contentType: String? = null
        var body: String? = null
    }

    private fun serving(handler: (HttpExchange, Seen) -> Unit, use: (String, Seen) -> Unit) {
        val seen = Seen()
        val pool = Executors.newCachedThreadPool { r -> Thread(r, "activation-test").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/activate") { exchange ->
            seen.method = exchange.requestMethod
            seen.contentType = exchange.requestHeaders.getFirst("Content-Type")
            seen.body = exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }
            handler(exchange, seen)
        }
        server.executor = pool
        server.start()
        try {
            use("http://127.0.0.1:${server.address.port}/v1/activate", seen)
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private fun reply(exchange: HttpExchange, code: Int, body: String) {
        val raw = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, raw.size.toLong())
        exchange.responseBody.use { it.write(raw) }
    }

    private fun activate(url: String, timeoutMs: Int = 4_000) =
        Activation.activate(url, ActivationFixture.DIGEST, ActivationFixture.INSTALL, timeoutMs)

    @Test
    fun `a bound code comes back as a token, and the request is the contract's`() {
        serving({ x, _ -> reply(x, 200, """{"token":"${ActivationFixture.GOOD}"}""") }) { url, seen ->
            val result = assertIs<Activation.Result.Bound>(activate(url))
            assertEquals(ActivationFixture.GOOD, result.token)
            // And the token means something once it is home.
            val token = assertNotNull(Unlock.parseToken(result.token, listOf(ActivationFixture.PUBLIC_KEY)))
            assertEquals(ActivationFixture.INSTALL, token.install)

            assertEquals("POST", seen.method)
            assertEquals("application/json", seen.contentType)
            // Two values and no third: the digest of the code, never the
            // code, and a random id (PLAN_SUPPORTER_SERVER.md 2.1).
            assertEquals("""{"digest":"${ActivationFixture.DIGEST}","install":"${ActivationFixture.INSTALL}"}""",
                         seen.body)
        }
    }

    @Test
    fun `404 is unknown and 409 is in use`() {
        serving({ x, _ -> reply(x, 404, """{"error":"unknown"}""") }) { url, _ ->
            assertIs<Activation.Result.Unknown>(activate(url))
        }
        serving({ x, _ -> reply(x, 409, """{"error":"in_use","slots":2}""") }) { url, _ ->
            assertIs<Activation.Result.InUse>(activate(url))
        }
    }

    /**
     * What Cloudflare says when the day's free requests are gone (error
     * 1027), with the body it sends -- an HTML page, not the Worker's JSON.
     * The connection worked, so this must not read as [Activation.Result.Unreachable].
     */
    @Test
    fun `429 and 503 are a busy server, not a missing connection`() {
        for (code in listOf(429, 503)) {
            serving({ x, _ -> reply(x, code, "<html><title>Error 1027</title></html>") }) { url, _ ->
                val result = assertIs<Activation.Result.Busy>(activate(url))
                assertTrue(code.toString() in result.why, "$code is not in: ${result.why}")
            }
        }
    }

    /**
     * Everything that is not one of the four, or busy, is
     * [Activation.Result.Unreachable], which the page words as "nothing was
     * learned, and nothing was written". 400 is in here on purpose: it means
     * this app sent something malformed, which is a bug and not a thing a
     * player can act on.
     */
    @Test
    fun `an answer that is not one of the four teaches nothing`() {
        for (code in listOf(400, 401, 500, 502)) {
            serving({ x, _ -> reply(x, code, """{"error":"bad_request"}""") }) { url, _ ->
                val result = assertIs<Activation.Result.Unreachable>(activate(url))
                assertTrue(code.toString() in result.why, "$code is not in: ${result.why}")
            }
        }
    }

    @Test
    fun `a 200 with no token in it is not a yes`() {
        for (body in listOf("", "not json at all", """{"ok":true}""", """{"token":""}""",
                            """{"token":"no-dot-in-here"}""")) {
            serving({ x, _ -> reply(x, 200, body) }) { url, _ ->
                assertIs<Activation.Result.Unreachable>(activate(url), "took <$body>")
            }
        }
    }

    /** A server that takes the request and then says nothing. */
    @Test
    fun `a server that does not answer runs out of time`() {
        serving({ _, _ -> Thread.sleep(30_000) }) { url, _ ->
            val started = System.currentTimeMillis()
            val result = assertIs<Activation.Result.Unreachable>(activate(url, timeoutMs = 700))
            val took = System.currentTimeMillis() - started
            assertTrue(took < 10_000, "waited ${took} ms for a 700 ms timeout")
            assertTrue("Timeout" in result.why, result.why)
        }
    }

    @Test
    fun `nothing listening is not a yes either`() {
        // A port that was free a moment ago: nothing is listening on it now.
        val port = ServerSocket(0).use { it.localPort }
        assertIs<Activation.Result.Unreachable>(
            activate("http://127.0.0.1:$port/v1/activate", timeoutMs = 2_000))
    }

    /**
     * The state this build is actually in until the Worker is deployed
     * ([Unlock.ACTIVATION_URL] is empty): redeeming says so, and says it
     * without opening a socket.
     */
    @Test
    fun `no address means no request`() {
        val result = assertIs<Activation.Result.Unreachable>(
            Activation.activate("", ActivationFixture.DIGEST, ActivationFixture.INSTALL))
        assertTrue("no activation server" in result.why, result.why)
    }
}
