package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The small pieces of core the app shell stands on (PLAN_ANDROID_5_SHELL.md). */
class ShellCoreTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")

    @Test
    fun `the game package is found by its hints, in their order`() {
        assertEquals(listOf("digimon", "bandai", "bnei", "namco"), Game.PACKAGE_HINTS)
        val installed = listOf("com.android.settings", "com.bandainamcoent.dgup_ww",
                               "io.github.digipr1me.digiautotap")
        assertEquals("com.bandainamcoent.dgup_ww", Game.pick(installed))
        // Hint order, not list order: "digimon" outranks "namco".
        assertEquals("x.digimon.y", Game.pick(listOf("com.namco.other", "x.digimon.y")))
        assertNull(Game.pick(listOf("com.android.launcher3")))
    }

    // What used to stand here, `the app asks for no network`, went on
    // 2026-09-22: INTERNET is in the manifest for redeeming a supporter code
    // and for the update check, and NetworkTest holds the five permissions by
    // name and the two files that may open a connection.

    /**
     * The two things here that must never be in anything a player gets: the
     * ledger of supporter codes in the clear (`codes_issued.csv`, with the
     * tools that write it) and the release signing key. The APK takes its
     * assets by name out of the build script, so the ledger has to be absent
     * there; the key is refused by suffix in .gitignore and by the release
     * build itself (README.md). The laboratory's `release.py` had this rule
     * for the public copy of the PC version; the day this project gets a
     * public copy, the rule is needed again in whatever makes it.
     */
    @Test
    fun `the ledger and the key never ship`() {
        // What the APK takes out of the repository root is every
        // `repoRoot.resolve(...)` in the app's build script, and that is the
        // two folders the readers need.
        val build = File(repo, "app/build.gradle.kts").readText()
        val shipped = Regex("""repoRoot\.resolve\("([^"]+)"\)""").findAll(build).map { it.groupValues[1] }.toSet()
        // codes.txt left this list on 2026-09-22: the pool is the activation
        // server's table now, and the APK carries no list of codes at all
        // (PLAN_SUPPORTER_SERVER.md 3.5).
        assertEquals(setOf("templates", "digits"), shipped, "the app ships something else out of the root")
        val ignore = File(repo, ".gitignore").readLines().map { it.trim() }
        for (line in listOf("keystore.properties", "*.jks", "*.keystore", "*.p12", "*.pfx", "*.apk")) {
            assertTrue(line in ignore, ".gitignore does not refuse $line")
        }
        // And no key is lying around in the tree whatever .gitignore says.
        val keys = repo.walkTopDown()
            .onEnter { it.name !in setOf(".git", "build", ".gradle", ".kotlin", "corpus", "__pycache__") }
            .filter { it.isFile && (it.extension.lowercase() in setOf("jks", "keystore", "p12", "pfx") || "keystore" in it.name.lowercase() && it.name != "keystore.properties") }
            .map { it.relativeTo(repo).path }.toList()
        assertTrue(keys.isEmpty(), "a key in the tree: $keys")
    }

    @Test
    fun `the main switch is one state and says when it moves`() {
        val seen = ArrayList<Boolean>()
        val l: (Boolean) -> Unit = { seen += it }
        MainSwitch.set(true)
        MainSwitch.listen(l)
        MainSwitch.toggle()
        MainSwitch.set(false)          // no change, no call
        MainSwitch.set(true)
        MainSwitch.unlisten(l)
        MainSwitch.set(false)
        assertEquals(listOf(false, true), seen)
        MainSwitch.set(true)
    }

    @Test
    fun `the log keeps the last lines only`() {
        HelperLog.clear()
        val t = LocalTime.of(12, 34, 56)
        repeat(HelperLog.CAPACITY + 20) { HelperLog.line("line $it", t) }
        val lines = HelperLog.snapshot()
        assertEquals(HelperLog.CAPACITY, lines.size)
        assertEquals("12:34:56  line 20", lines.first())
        assertEquals("12:34:56  line ${HelperLog.CAPACITY + 19}", lines.last())
        HelperLog.clear()
    }

    /**
     * The six states, in the order [Shell.state] decides them. They are
     * core's since the merge session (PLAN_ANDROID_5_SHELL.md 5.6); what the
     * app still owns is the colour of each and the three functions wired in
     * here.
     */
    @Test
    fun `the six states are decided in one order`() {
        var alive = true
        var now = 1000L
        var screen: String? = null
        var note = "nothing on the screen for me"
        Shell.alive = { alive }
        Shell.seen = { screen to note }
        Shell.clock = { now }
        Shell.parked = null
        Shell.takeOverAt = 0L
        MainSwitch.set(true)
        try {
            assertEquals(HelperState.IDLE, Shell.state())
            assertEquals("nothing on the screen for me", Shell.sentence(Shell.state()))

            screen = "dungeon_list"
            note = "Dungeons has worked this dungeon_list"
            assertEquals(HelperState.RUNNING, Shell.state())
            assertTrue(Shell.sentence(Shell.state()).startsWith("Sees: dungeon_list -- "))

            // The three-second clock outranks a screen that is known.
            Shell.takeOverAt = now + 2200
            assertEquals(HelperState.WAITING, Shell.state())
            assertEquals("dungeon_list. Taking over in 3 s unless you touch the game.",
                         Shell.sentence(Shell.state()))

            // A park outranks the clock, the switch outranks the park, and a
            // service that is gone outranks all of it.
            Shell.parked = "A prompt is open that I did not raise."
            assertEquals(HelperState.PARKED, Shell.state())
            assertEquals("A prompt is open that I did not raise.", Shell.sentence(Shell.state()))
            MainSwitch.set(false)
            assertEquals(HelperState.PAUSED, Shell.state())
            alive = false
            assertEquals(HelperState.STOPPED, Shell.state())
        } finally {
            Shell.parked = null
            Shell.takeOverAt = 0L
            Shell.alive = { false }
            Shell.seen = { null to "" }
            MainSwitch.set(true)
        }
    }

    /**
     * A live run of the director printed "something bubble-shaped at
     * 0,437/0,377" on this German machine: `Passive.kt` had never been
     * through the Locale pass that `PassiveSkill.kt` had, and fifty-odd
     * other calls in core had not either. `Fmt.kt` answers it once for the
     * whole package; this is the proof that it does, run through a real
     * line of a real class rather than through the extension alone.
     */
    @Test
    fun `numbers come out with a decimal point on a German machine`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("pace 1.00, tick 1.50 s",
                         Actor(MiniNeverAsked, Vision(ClassPathAssets), MINI_UNUSED_CALIB).tempo())
            assertEquals("0.437", "%.3f".format(0.437))
            assertEquals("5,000", "%,d".format(5000))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `templates and digits load from the class path, as the app loads them from assets`() {
        // The public copy of the source carries both folders empty (release.py):
        // the crops are the game's pictures and are not distributed. There
        // this test has nothing to say; here, where they are, it is a test.
        val crops = File(repo, "templates").listFiles { f -> f.extension == "png" }.orEmpty()
        assumeTrue(crops.isNotEmpty(), "no game crops in this checkout")
        val icon = ClassPathAssets.bytes("templates/arrow.png")
        assertNotNull(icon)
        // A PNG, not a path or an empty stream.
        assertEquals(0x89.toByte(), icon[0])
        assertEquals('P'.code.toByte(), icon[1])
        assertNotNull(ClassPathAssets.bytes("digits/shared/0/00.png"))
        assertNull(ClassPathAssets.bytes("templates/not_there.png"))
    }
}
