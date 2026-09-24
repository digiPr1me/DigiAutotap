package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Theme.kt is the only file in the app that writes a colour, and no page
 * types a value.
 *
 * Two places where a colour is set is exactly how two copies of a threshold
 * drift apart, so every colour has one name and one value per design, in
 * Theme.kt, and a page reaches for the name. This scans the app's sources
 * the way the laboratory's `test_android_theme.py` did until 2026-09-21;
 * what that test also did -- hold the light values to the PC's `theme.py`
 * -- is gone with the PC (PLAN_STANDALONE.md 3): Theme.kt is the palette.
 *
 *   * LIGHT and DARK fill every field of the Palette, so a name cannot have
 *     a value in one design and nothing in the other;
 *   * only the overlay's DOT_* may be the same in both designs -- the dot
 *     lies over the game, which does not go dark with the phone
 *     (PLAN_ANDROID_DESIGN.md 4.2) -- so that a colour of the *app* can
 *     never slip in by being equal in both;
 *   * no other file writes a colour at all.
 */
class ThemeTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val app = File(repo, "app/src/main/kotlin/io/github/digipr1me/digiautotap")
    private val themeKt = File(app, "Theme.kt")

    private val fields: List<String> by lazy {
        Regex("""^    val ([A-Z][A-Z0-9_]*): Int,$""", RegexOption.MULTILINE).findAll(themeKt.readText())
            .map { it.groupValues[1] }.toList()
    }

    private fun palette(which: String): Map<String, String> {
        val kt = themeKt.readText()
        val block = Regex("""val $which = Palette\((.*?)\n    \)""", RegexOption.DOT_MATCHES_ALL).find(kt)
            ?: error("Theme.kt has no $which palette")
        val pairs = Regex("""(\w+) = rgb\("(#[0-9a-fA-F]{6})"\)""").findAll(block.groupValues[1])
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        val found = pairs.toMap()
        assertEquals(pairs.size, found.size, "$which names a colour twice")
        return found
    }

    @Test
    fun `both designs fill every name, and only the dot is the same in both`() {
        assertTrue(app.isDirectory, "the app's Kotlin folder is not where it was: $app")
        assertTrue(fields.isNotEmpty(), "Theme.kt has no Palette fields -- has its shape changed?")
        assertEquals(fields.distinct(), fields, "Theme.kt names a Palette field twice")
        val light = palette("LIGHT")
        val dark = palette("DARK")
        for ((which, values) in listOf("LIGHT" to light, "DARK" to dark)) {
            assertEquals(emptyList(), fields.filter { it !in values }, "$which leaves names without a value")
            assertEquals(emptyList(), values.keys.filter { it !in fields }, "$which sets what is not a Palette field")
        }
        val same = fields.filter { light[it] == dark[it] }
        assertTrue(same.all { it.startsWith("DOT_") },
                   "only the overlay's DOT_* may be the same in both designs, and these are: $same")
        assertTrue(same.size >= 4, "the four state colours of the dot are missing from one design")
        // And Theme.kt is where they are, rather than somewhere none of this looks.
        assertEquals(2 * fields.size, Regex("""rgb\("#""").findAll(themeKt.readText()).count(),
                     "Theme.kt does not write exactly one value per name per design")
        println("Theme.kt: ${fields.size} names, ${2 * fields.size} values, ${same.size} DOT_* the same in both designs")
    }

    // Color.TRANSPARENT is allowed: it is the absence of a colour, not a
    // shade. Everything else that names or spells one out is not.
    private val banned = listOf(
        Regex(""""#[0-9a-fA-F]{6,8}"""") to "a colour written out",
        Regex("""Color\.parseColor\(""") to "Color.parseColor",
        Regex("""Color\.rgb\(""") to "Color.rgb",
        Regex("""Color\.(WHITE|BLACK|RED|GREEN|BLUE|YELLOW|CYAN|MAGENTA|GRAY|DKGRAY|LTGRAY)\b""") to "a named colour constant",
        Regex("""0[xX][fF][fF][0-9a-fA-F]{6}""") to "an ARGB value")

    @Test
    fun `no page writes a colour of its own`() {
        val sources = app.listFiles { f -> f.name.endsWith(".kt") }!!.sortedBy { it.name }
        assertTrue(sources.size > 3, "the app has suspiciously few Kotlin files: ${sources.map { it.name }}")
        val offences = ArrayList<String>()
        for (file in sources) {
            if (file.name == "Theme.kt") continue
            val src = file.readText()
            for ((pattern, what) in banned) {
                for (hit in pattern.findAll(src)) offences += "${file.name}: ${hit.value} ($what)"
            }
        }
        assertTrue(offences.isEmpty(),
                   "a page writes a colour of its own instead of reaching for a name in Theme.kt:\n  " + offences.joinToString("\n  "))
        println("no colour value outside Theme.kt: ${sources.size - 1} Kotlin files scanned")
    }
}
