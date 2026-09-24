package io.github.digipr1me.digiautotap.core

/**
 * Which installed package is the game. Nothing ships a package name: it is
 * found at runtime, by ldplayer.py's rule, hint by hint in this order --
 * measured as the single hit among 103 packages on one machine (NOTES.md,
 * "Capture and ADB").
 */
object Game {
    val PACKAGE_HINTS = listOf("digimon", "bandai", "bnei", "namco")

    /** ldplayer.py's loop: the first name holding the first hint that hits. */
    fun pick(names: List<String>): String? {
        for (hint in PACKAGE_HINTS) {
            for (name in names) {
                if (hint in name.lowercase()) return name
            }
        }
        return null
    }
}
