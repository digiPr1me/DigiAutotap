package io.github.digipr1me.digiautotap.core

/**
 * Which installed package is the game. Nothing ships a package name: it is
 * found at runtime, by ldplayer.py's rule, hint by hint in this order --
 * measured as the single hit among 103 packages on one machine (NOTES.md,
 * "Capture and ADB").
 *
 * **The name comes first, and the hints only after it.** The game is
 * `com.bandainamcoent.dgup_ww`, and "digimon" is not in it: a player wrote
 * on 2026-09-24 that the Vital Bracelet app and Bandai's TCG app were taken
 * for Digimon UP. Any package with "digimon" in its name beat the game, and
 * among the "bandai" packages whichever the system happened to list first
 * won. So [KNOWN] wins over every hint, the player's own choice under
 * Settings wins over [KNOWN], and the hints are the fallback for a regional
 * build nobody has seen yet ("dgup" first, the guess for one).
 */
object Game {
    /** The one package the game is known by; a regional build falls to the hints. */
    const val KNOWN = "com.bandainamcoent.dgup_ww"
    val PACKAGE_HINTS = listOf("dgup", "digimon", "bandai", "bnei", "namco")

    /** How [pick] came to its answer, for the log. */
    enum class By { CHOSEN, KNOWN, HINT }

    /** [pick]'s answer with its reason; [hint] is set when [by] is [By.HINT]. */
    data class Pick(val name: String, val by: By, val hint: String? = null)

    /** [chosen] first when it is installed; then [KNOWN]; then the hints in their order. */
    fun pick(names: List<String>, chosen: String? = null): String? = explain(names, chosen)?.name

    /** [pick], saying why. */
    fun explain(names: List<String>, chosen: String? = null): Pick? {
        if (!chosen.isNullOrEmpty() && chosen in names) return Pick(chosen, By.CHOSEN)
        if (KNOWN in names) return Pick(KNOWN, By.KNOWN)
        // ldplayer.py's loop: the first name holding the first hint that hits.
        for (hint in PACKAGE_HINTS) {
            for (name in names) {
                if (hint in name.lowercase()) return Pick(name, By.HINT, hint)
            }
        }
        return null
    }

    /** Does any hint match [name]? */
    fun hinted(name: String): Boolean = PACKAGE_HINTS.any { it in name.lowercase() }

    /**
     * Every installed name a hint matches, for the sheet -- [KNOWN] and
     * [chosen] first, [chosen] even when no hint matches it, the rest in the
     * system's order.
     */
    fun candidates(names: List<String>, chosen: String? = null): List<String> {
        val first = listOfNotNull(chosen?.takeIf { it.isNotEmpty() && it in names },
                                  KNOWN.takeIf { it in names })
        return (first + names.filter { hinted(it) }).distinct()
    }
}
