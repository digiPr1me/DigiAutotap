package io.github.digipr1me.digiautotap.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The census against a real server, as UpdatesTest plays the update check:
 * once per day, week and month, and nothing in the request that tells one
 * phone from another.
 */
class CensusTest {

    private class Memory : Settings {
        val data = HashMap<String, Any?>()
        override fun bool(key: String, default: Boolean) = data[key] as? Boolean ?: default
        override fun num(key: String, default: Double) = data[key] as? Double ?: default
        override fun str(key: String, default: String) = data[key] as? String ?: default
        override fun strings(key: String, default: List<String>) = default
        override fun ints(key: String, default: Map<String, Int>) = default
        override fun steps(key: String): List<Chain.Step>? = null
        override fun put(key: String, value: Any?) { if (value == null) data.remove(key) else data[key] = value }
        override fun putInts(key: String, value: Map<String, Int>) {}
        override fun putSteps(key: String, value: List<Chain.Step>) {}
    }

    private fun serving(code: Int, use: (String, MutableList<String>) -> Unit) {
        val bodies = mutableListOf<String>()
        val pool = Executors.newCachedThreadPool { r -> Thread(r, "census-test").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/census") { exchange ->
            bodies += exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }
            val raw = """{"ok":true}""".toByteArray()
            exchange.sendResponseHeaders(code, raw.size.toLong())
            exchange.responseBody.use { it.write(raw) }
        }
        server.executor = pool
        server.start()
        try {
            use("http://127.0.0.1:${server.address.port}/v1/census", bodies)
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private val thursday = Instant.parse("2026-09-24T10:00:00Z")

    @Test
    fun `the periods are the Worker's, ISO weeks included`() {
        // The same days census.test.ts asks the Worker about.
        assertEquals(Census.Periods("2026-09-24", "2026-W39", "2026-09"), Census.Periods.of(thursday))
        assertEquals("2025-W01", Census.Periods.of(Instant.parse("2024-12-30T00:00:00Z")).week)
        assertEquals("2020-W53", Census.Periods.of(Instant.parse("2021-01-03T23:59:59Z")).week)
        assertEquals("2026-W01", Census.Periods.of(Instant.parse("2026-01-01T00:00:00Z")).week)
        assertEquals("2026-W53", Census.Periods.of(Instant.parse("2027-01-01T00:00:00Z")).week)
    }

    @Test
    fun `once a day, once a week, once a month, and new only the first time`() {
        serving(200) { url, bodies ->
            val s = Memory()
            assertEquals("census: counted for the day, the week, the month, a new install",
                         Census.count(s, "1.2", known = false, now = thursday, url = url))
            assertNull(Census.count(s, "1.2", known = false, now = thursday.plusSeconds(3600), url = url))
            // Friday: a new day, the same week and month.
            Census.count(s, "1.2", known = false, now = thursday.plusSeconds(86_400), url = url)
            // The Monday after: a new week too.
            Census.count(s, "1.2", known = false, now = thursday.plusSeconds(4 * 86_400), url = url)
            // October.
            Census.count(s, "1.2", known = false, now = Instant.parse("2026-10-01T00:00:00Z"), url = url)
            assertEquals(listOf(
                """{"version":"1.2","day":true,"week":true,"month":true,"first":true}""",
                """{"version":"1.2","day":true,"week":false,"month":false,"first":false}""",
                """{"version":"1.2","day":true,"week":true,"month":false,"first":false}""",
                """{"version":"1.2","day":true,"week":false,"month":true,"first":false}"""), bodies)
        }
    }

    @Test
    fun `a phone that was here before the census is not a new install`() {
        val visit = Census.due(Memory(), thursday, known = true)
        assertTrue(visit.day && visit.week && visit.month)
        assertFalse(visit.first)
    }

    @Test
    fun `nothing is stamped until the Worker has said yes`() {
        serving(500) { url, bodies ->
            val s = Memory()
            assertEquals("census: not counted (server answered 500)",
                         Census.count(s, "1.2", known = false, now = thursday, url = url))
            assertTrue(s.data.isEmpty())
            assertEquals(1, bodies.size)
        }
        val s = Memory()
        assertEquals("census: not counted (no connection)",
                     Census.count(s, "1.2", known = false, now = thursday, url = "http://127.0.0.1:1/v1/census",
                                  timeoutMs = 2_000))
        assertTrue(s.data.isEmpty())
    }

    @Test
    fun `the request carries the version and four booleans, and nothing else`() {
        val json = Census.Visit(day = true, week = false, month = false, first = false).json("1.1\"<x>")
        assertEquals("""{"version":"1.1x","day":true,"week":false,"month":false,"first":false}""", json)
    }
}
