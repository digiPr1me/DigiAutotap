package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app goes on the network in two places, and this is what says so.
 *
 * `Activation.kt` redeems a supporter code (PLAN_SUPPORTER_SERVER.md), and
 * `Updates.kt` asks GitHub's list of releases whether a newer version is out,
 * once each time the app is opened. Nothing else in the sources may open a
 * connection: an accessibility service sees every pixel of the phone, and
 * where it can reach the network is worth knowing by file name rather than
 * by reading all of it. A third place is a decision, and it goes into the
 * list below and the README's "What leaves the phone" together.
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
    private val allowed = setOf("Activation.kt", "Updates.kt")

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
    fun `two files may open a connection`() {
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
        assertTrue(sites >= 4, "the scan sees $sites network sites, which is too few to trust it")
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
        // asks (NOTES.md, "The overlay").
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
}
