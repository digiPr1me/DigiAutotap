package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app goes on the network in three places, and this is what says so.
 *
 * `Activation.kt` redeems a supporter code (PLAN_SUPPORTER_SERVER.md),
 * `Updates.kt` asks GitHub's list of releases whether a newer version is out,
 * once each time the app is opened, `Census.kt` tells the Worker once a
 * day that a phone ran, with the version and nothing that names the phone.
 * `Report.kt`, which sent a bug report with the log and the kept frames,
 * was the fourth until 1.3's fourth candidate; the log goes out now only as
 * a ZIP the player hands to the share sheet (`LogShare.kt`, no connection
 * of its own). Nothing else in the sources may open a connection: an
 * accessibility service sees every pixel of the phone, and where it can
 * reach the network is worth knowing by file name rather than by reading
 * all of it. A fourth place is a decision, and it goes into the list below.
 *
 * Built like CorpusTest, and for the same reason: the rule is about what
 * the sources may contain, so the sources are what is read. A call site
 * outside the list is red even if it is harmless.
 *
 * What this deliberately does not catch: `app/`'s dependencies, for a
 * library that phones home -- there are two, OpenCV and core itself -- and
 * the links the app hands to the browser (Feedback, the releases page),
 * which the browser loads and not this app.
 */
class NetworkTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")

    /** The files that are allowed to, and the only ones. */
    private val allowed = setOf("Activation.kt", "Updates.kt", "Census.kt")

    /**
     * Anything that opens a connection, or that carries a library which
     * would. Names, not intentions: `WebView` is in here because a page
     * inside the app is a way onto the network that no `HttpURLConnection`
     * would show.
     */
    private val network = Regex(
        """\b(?:HttpURLConnection|HttpsURLConnection|URLConnection|HttpClient|Retrofit|""" +
        """OkHttp\w*|Ktor|DatagramSocket|ServerSocket|SSLSocket\w*|WebSocket|WebView|""" +
        """InetAddress|InetSocketAddress|MulticastSocket)\b""" +
        """|\bSocket\(|\bURL\(|\.openConnection\(|\bopenStream\(""")

    private fun sources(): List<File> =
        listOf("app/src", "core/src/main").map { File(repo, it) }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .sortedBy { it.path }

    @Test
    fun `three files may open a connection`() {
        val problems = ArrayList<String>()
        var sites = 0
        for (file in sources()) {
            file.readLines().forEachIndexed { i, line ->
                val hit = network.find(line) ?: return@forEachIndexed
                sites += 1
                if (file.name !in allowed) {
                    problems += "${file.relativeTo(repo).path.replace('\\', '/')}:${i + 1} " +
                        "reaches the network (${hit.value}): ${line.trim()}"
                }
            }
        }
        for (p in problems) println("  $p")
        assertTrue(problems.isEmpty(),
                   "${problems.size} place(s) outside $allowed can open a connection")
        // And the scan can see one at all: a regex that matches nothing
        // would pass every source tree there is.
        assertTrue(sites >= 3, "the scan sees $sites network sites, which is too few to trust it")
        println("network sites: $sites, all of them in $allowed")
    }

    /**
     * Five permissions, by name. A sixth is a decision, not a detail, and
     * the README's paragraph about what leaves the phone is written against
     * this list.
     */
    @Test
    fun `the manifest asks for exactly five permissions`() {
        val manifest = File(repo, "app/src/main/AndroidManifest.xml").readText()
        val asked = Regex("""<uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(asked.size, asked.toSet().size, "a permission is asked for twice: $asked")
        assertEquals(
            setOf("android.permission.FOREGROUND_SERVICE",
                  "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                  "android.permission.POST_NOTIFICATIONS",
                  "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                  "android.permission.INTERNET"),
            asked.toSet())
        // Of those five only INTERNET is new, and it is a normal
        // permission: no runtime dialog, so the onboarding stays at three
        // asks (notes/overlay.md, "The overlay").
        assertTrue("android.permission.ACCESS_NETWORK_STATE" !in manifest)
        assertTrue("android.permission.ACCESS_WIFI_STATE" !in manifest)
        assertTrue("android.permission.QUERY_ALL_PACKAGES" !in manifest)
    }

    /**
     * No HTTP library anywhere in the build. [Activation] and [Updates] are
     * `HttpURLConnection` out of the JDK on purpose: they need nothing added,
     * and a library added for one request is a library that is there for the
     * next one too.
     */
    @Test
    fun `no http library is on the build path`() {
        val builds = listOf("gradle/libs.versions.toml", "build.gradle.kts",
                            "core/build.gradle.kts", "app/build.gradle.kts")
            .map { File(repo, it) }.filter { it.exists() }
        assertTrue(builds.size == 4, "a build file is missing: ${builds.map { it.name }}")
        for (file in builds) {
            val text = file.readText().lowercase()
            for (library in listOf("okhttp", "retrofit", "ktor", "volley", "apache.httpcomponents", "fuel")) {
                assertTrue(library !in text, "${file.name} names $library")
            }
        }
    }

    /**
     * The ZIP that Share on the Log page makes lands in a public channel, so
     * it never carries the supporter file, the token, the code in the clear
     * or the activation's install id, which the server keeps beside every
     * supporter code -- a log that carried it could be tied to a code
     * (PLAN_BUG_REPORT.md 9, written for the report; the same rule since
     * 1.3's fourth candidate for the ZIP, PLAN_REPORT_RAUS.md 2.5).
     * `LogShare.kt` is held to not naming any of them in code.
     */
    @Test
    fun `the shared log never names the supporter file, the code or the install id`() {
        fun code(text: List<String>) = text.filter { l -> listOf("*", "/*", "//").none { l.trim().startsWith(it) } }
        val app = File(repo, "app/src/main/kotlin/io/github/digipr1me/digiautotap")
        val share = File(app, "LogShare.kt")
        assertTrue(share.isFile, "LogShare.kt is not where it was")
        val shareCode = code(share.readLines())
        for (word in listOf("installFile", "installId", "INSTALL_FILE", "install_id", "supporterFile",
                            "Supporter.savedText", "Supporter.token", "supporter.txt", "SAVED_FILE",
                            "supporterCodeFile", "supporter_code", "CODE_FILE", "Supporter.code", "Unlock.")) {
            assertTrue(shareCode.none { word in it }, "LogShare.kt uses $word")
        }
        // And the census, which promises no id, knows none either.
        val census = File(repo, "core/src/main/kotlin/io/github/digipr1me/digiautotap/core/Census.kt").readText()
        for (word in listOf("INSTALL_FILE", "install_id", "installId")) {
            assertTrue(word !in census, "Census.kt names $word")
        }
    }

    /**
     * The redeemed code in the clear (Unlock.CODE_FILE, since 2026-10-02) is
     * the Settings card's alone: no shared log, no settings file and no log line
     * names it. Its readers are the Supporter object and the card; every
     * other app file is held to not reading it, and the redeem's log line to
     * the covered code.
     */
    @Test
    fun `the redeemed code is read by the Settings card alone and never logged whole`() {
        val app = File(repo, "app/src/main/kotlin/io/github/digipr1me/digiautotap")
        fun code(text: List<String>) = text.filter { l -> listOf("*", "/*", "//").none { l.trim().startsWith(it) } }
        for (file in app.listFiles { f -> f.name.endsWith(".kt") }!!) {
            val lines = code(file.readLines())
            val allowed = file.name in setOf("MainActivity.kt", "DigiAutotapApp.kt")
            if (!allowed) {
                for (word in listOf("supporterCodeFile", "CODE_FILE", "Supporter.code")) {
                    assertTrue(lines.none { word in it }, "${file.name} reads the redeemed code ($word)")
                }
            }
            // A log line never carries a code whole: pretty() is the screen's.
            for (l in lines.filter { "HelperLog.line" in it }) {
                assertTrue("Unlock.pretty" !in l, "${file.name} logs a code whole: ${l.trim()}")
            }
        }
        assertEquals("ABCD-····-····", Unlock.masked("abcd efgh jkmn"))
        assertTrue(Unlock.CODE_FILE != Unlock.SAVED_FILE)
    }

}
