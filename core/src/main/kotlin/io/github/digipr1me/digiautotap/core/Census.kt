package io.github.digipr1me.digiautotap.core

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.IsoFields

/**
 * How many phones use DigiAutotap: a day, a week, a month, and how many are
 * new.
 *
 * GitHub counts downloads, and a download of 1.1 does not say whether it
 * was a new player or 1.0 going over itself. So the phone says it instead --
 * and says nothing that would tell one phone from another. It remembers
 * which day, ISO week and month it has already been counted in, and the
 * first time it runs in a new one it sends the Worker the version and which
 * of the three are new, as four booleans. The Worker adds one to each
 * counter and stores nothing else: no install id, no Android id, no hash of
 * either, no address. Counting once per period is done here, on the phone,
 * which is why the server needs nothing to recognise a phone by.
 *
 * All periods in UTC, on both sides, so that the phone's "new week" and the
 * Worker's week are the same week (server/src/census.ts).
 *
 * A phone that is offline is counted the next time it is not; one that
 * fails is asked again at the next occasion ([count]), since nothing is
 * remembered until the Worker has said yes.
 */
object Census {

    /** The activation Worker ([Unlock.ACTIVATION_URL]), its census route. */
    const val URL = "https://digiautotap-activation.digiautotap-activation-server.workers.dev/v1/census"

    const val DAY_KEY = "census_day"
    const val WEEK_KEY = "census_week"
    const val MONTH_KEY = "census_month"

    /** Which counters this phone has not been added to yet. */
    data class Visit(val day: Boolean, val week: Boolean, val month: Boolean, val first: Boolean) {
        val any get() = day || week || month || first

        fun json(version: String) =
            """{"version":"${version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.take(20)}",""" +
                """"day":$day,"week":$week,"month":$month,"first":$first}"""
    }

    /** "2026-09-24", "2026-W39", "2026-09" -- the Worker's spelling ([Visit] is about these). */
    data class Periods(val day: String, val week: String, val month: String) {
        companion object {
            fun of(now: Instant): Periods {
                val d = now.atOffset(ZoneOffset.UTC).toLocalDate()
                val week = "%04d-W%02d".format(d.get(IsoFields.WEEK_BASED_YEAR),
                                               d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
                return Periods(d.toString(), week, d.toString().substring(0, 7))
            }
        }
    }

    /**
     * What is due at [now], or a [Visit] with nothing in it.
     *
     * [known] says this phone was in use before the census existed -- a
     * player who opened 1.1 and updates -- so that an empty stamp is not
     * taken for a new install. It is asked only while nothing is stamped.
     */
    fun due(s: Settings, now: Instant, known: Boolean): Visit {
        val p = Periods.of(now)
        val day = s.str(DAY_KEY, "")
        return Visit(day = day != p.day,
                     week = s.str(WEEK_KEY, "") != p.week,
                     month = s.str(MONTH_KEY, "") != p.month,
                     first = day.isEmpty() && !known)
    }

    /**
     * One census, if one is due: [due], one POST, and the stamps written
     * when the Worker has taken it. Returns what happened, for the log, or
     * null when nothing was due.
     *
     * Synchronized because the Activity and the core both call it, in one
     * process, and two sends for one day would count one phone twice. Runs
     * on a worker thread: it can wait [timeoutMs] on the network.
     */
    @Synchronized
    fun count(s: Settings, version: String, known: Boolean, now: Instant = Instant.now(),
              url: String = URL, timeoutMs: Int = 8_000): String? {
        val visit = due(s, now, known)
        if (!visit.any) return null
        val code = send(visit, version, url, timeoutMs)
        if (code !in 200..299) return "census: not counted (${code?.let { "server answered $it" } ?: "no connection"})"
        val p = Periods.of(now)
        s.put(DAY_KEY, p.day)
        s.put(WEEK_KEY, p.week)
        s.put(MONTH_KEY, p.month)
        return "census: counted for " + listOfNotNull("the day".takeIf { visit.day }, "the week".takeIf { visit.week },
            "the month".takeIf { visit.month }, "a new install".takeIf { visit.first }).joinToString(", ")
    }

    /** The POST itself; the HTTP status, or null when there was no answer. */
    fun send(visit: Visit, version: String, url: String = URL, timeoutMs: Int = 8_000): Int? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                // Cloudflare turns a request without a User-Agent of its own away at the edge.
                setRequestProperty("User-Agent", "DigiAutotap")
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(visit.json(version)) }
            conn.responseCode
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
