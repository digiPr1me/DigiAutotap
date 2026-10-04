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
 * of the three are new, as four booleans -- and, with the month's, a
 * description of the phone ([Device]). The Worker adds one to each counter
 * and stores nothing else: no install id, no Android id, no hash of
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

    /**
     * The name of the colour space of the last screenshot, written by the
     * service when it grabs one and read here: the census is asked from the
     * app and cannot take a screenshot itself. Empty until the first grab,
     * and sent as "unknown" then.
     */
    const val COLOR_SPACE_KEY = "census_colorspace"

    /**
     * What kind of phone this is, as a description and not as a person: the
     * model, the Android version, the display and the name of the colour space
     * the screenshots come in. It says which formats and which Android
     * versions the players have (PLAN_FORMATE.md 11), and it is sent once per
     * month with the month's counter, so a row is a number of phones. Nothing
     * here is an id: two phones of one model and one setting send the same.
     */
    data class Device(val manufacturer: String, val model: String, val sdk: Int,
                      val width: Int, val height: Int, val dpi: Int,
                      val insetTop: Int, val insetBottom: Int, val insetLeft: Int, val insetRight: Int,
                      val colorSpace: String) {
        fun json() =
            """{"manufacturer":"${text(manufacturer)}","model":"${text(model)}","sdk":$sdk,""" +
                """"width":$width,"height":$height,"dpi":$dpi,""" +
                """"insetTop":$insetTop,"insetBottom":$insetBottom,"insetLeft":$insetLeft,"insetRight":$insetRight,""" +
                """"colorSpace":"${text(colorSpace)}"}"""

        private fun text(s: String) =
            s.filter { it.isLetterOrDigit() || it in " .-_+" }.trim().take(40).ifEmpty { "unknown" }
    }

    /** Which counters this phone has not been added to yet. */
    data class Visit(val day: Boolean, val week: Boolean, val month: Boolean, val first: Boolean) {
        val any get() = day || week || month || first

        /** [device] goes along only with the month's counter: once a month, once per phone. */
        fun json(version: String, device: Device? = null) =
            """{"version":"${version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.take(20)}",""" +
                """"day":$day,"week":$week,"month":$month,"first":$first""" +
                (if (month && device != null) ""","device":${device.json()}""" else "") + "}"
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
              url: String = URL, timeoutMs: Int = 8_000, device: Device? = null): String? {
        val visit = due(s, now, known)
        if (!visit.any) return null
        val code = send(visit, version, url, timeoutMs, device)
        if (code !in 200..299) return "census: not counted (${code?.let { "server answered $it" } ?: "no connection"})"
        val p = Periods.of(now)
        s.put(DAY_KEY, p.day)
        s.put(WEEK_KEY, p.week)
        s.put(MONTH_KEY, p.month)
        return "census: counted for " + listOfNotNull("the day".takeIf { visit.day }, "the week".takeIf { visit.week },
            "the month".takeIf { visit.month }, "a new install".takeIf { visit.first }).joinToString(", ")
    }

    /** The POST itself; the HTTP status, or null when there was no answer. */
    fun send(visit: Visit, version: String, url: String = URL, timeoutMs: Int = 8_000, device: Device? = null): Int? {
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
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(visit.json(version, device)) }
            conn.responseCode
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
