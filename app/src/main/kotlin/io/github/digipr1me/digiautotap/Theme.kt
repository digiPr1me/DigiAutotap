package io.github.digipr1me.digiautotap

import android.content.Context
import android.content.res.Configuration
import io.github.digipr1me.digiautotap.core.Settings

/**
 * Every colour the app draws with, with a light and a dark value each
 * (PLAN_ANDROID_DESIGN.md 5.1). This is the **only** file in the app that
 * may write a colour value; `ThemeTest` in core scans the others for
 * `#rrggbb` and for `Color.rgb(`, because two places where a value is set
 * is how two copies of a threshold drift apart. The names were the PC
 * version's `theme.py` names, and the light values its values, until the
 * laboratory was retired on 2026-09-21; this file is the palette now.
 *
 * Dark follows the system unless the player says otherwise ([Mode], the
 * Design panel under Settings; it followed the system and nothing else until
 * 2026-09-22). Android recreates the Activity when the night mode changes,
 * so every page is built again against the other palette without anyone
 * having to listen for it.
 *
 * The values are the console design's since 2026-09-21 (the design session
 * of that evening, direction D): a cooler ground, a crisper blue, and
 * chips rather than pills. The **names** are the ones they always were, and
 * that is the point -- [Shell]'s state mapping, the notification and the
 * overlay all reach for a name, so a whole design changes here and nowhere
 * else.
 */
class Palette(
    val BG: Int,
    val SURFACE: Int,
    val SURFACE_HOVER: Int,
    val LINE: Int,
    val TEXT: Int,
    val TEXT_2: Int,
    val PRIMARY: Int,
    val PRIMARY_ACTIVE: Int,
    val ON_PRIMARY: Int,
    val OK: Int,
    val PAUSE: Int,
    val WARN: Int,
    val STATE_BG: Int,
    val STATE_FG: Int,
    val STATE_EDGE: Int,
    val PILL_NEUTRAL_BG: Int,
    val PILL_NEUTRAL_FG: Int,
    val PILL_NEUTRAL_EDGE: Int,
    val PILL_OK_BG: Int,
    val PILL_OK_FG: Int,
    val PILL_OK_EDGE: Int,
    val PILL_PAUSE_BG: Int,
    val PILL_PAUSE_FG: Int,
    val PILL_PAUSE_EDGE: Int,
    val PILL_WARN_BG: Int,
    val PILL_WARN_FG: Int,
    val PILL_WARN_EDGE: Int,
    val DISABLED_BG: Int,
    val DISABLED_FG: Int,
    val DOT_ON: Int,
    val DOT_WAIT: Int,
    val DOT_OFF: Int,
    val DOT_WARN: Int,
    val DOT_RIM: Int,
    val DOT_PLATE: Int,
    val DOT_PLATE_FG: Int,
    /**
     * The one colour in the app that is not the app's: Discord's blurple,
     * on the tile's logo field alone, since 2026-09-23. A brand's mark in
     * the app's own blue would be a second blue that means nothing, so the
     * field carries theirs; the tile around it is a panel like any other.
     */
    val DISCORD: Int,
    val ON_DISCORD: Int,
)

object Theme {

    /** What the Design panel offers, and what `theme` holds in digiautotap.json. */
    enum class Mode(val key: String, val label: String) {
        SYSTEM("system", "System"),
        LIGHT("light", "Light"),
        DARK("dark", "Dark");

        companion object {
            fun of(key: String): Mode = entries.firstOrNull { it.key == key } ?: SYSTEM
        }
    }

    const val KEY = "theme"

    /**
     * The player's choice, held here rather than read from the file at every
     * question: [dark] is asked once per page, once per overlay view and
     * once per notification, and a [SettingsStore] reads the whole file.
     * Filled at the start of the process ([load]) and whenever it changes.
     */
    @Volatile
    var mode: Mode = Mode.SYSTEM
        private set

    fun load(s: Settings) { mode = Mode.of(s.str(KEY, Mode.SYSTEM.key)) }

    fun choose(s: Settings, m: Mode) {
        s.put(KEY, m.key)
        mode = m
    }

    /**
     * The Activity's context with the chosen night mode written into it, so
     * that res/values-night decides the window's own background the same way
     * the palette below decides the app's colours. Android reads the theme
     * out of the manifest before `onCreate`, which is why this belongs in
     * `attachBaseContext` and not in a page.
     */
    fun wrap(c: Context): Context {
        if (mode == Mode.SYSTEM) return c
        val cfg = Configuration(c.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (mode == Mode.DARK) Configuration.UI_MODE_NIGHT_YES
            else Configuration.UI_MODE_NIGHT_NO
        return c.createConfigurationContext(cfg)
    }

    /** "#rrggbb" as an opaque colour, so a value here reads as it does in a design sketch. */
    private fun rgb(hex: String): Int = 0xFF000000.toInt() or hex.substring(1).toInt(16)

    val LIGHT = Palette(
        BG = rgb("#e9ecf1"),
        SURFACE = rgb("#ffffff"),
        SURFACE_HOVER = rgb("#eef1f5"),
        LINE = rgb("#d7dce4"),
        TEXT = rgb("#11151b"),
        TEXT_2 = rgb("#6b7686"),
        PRIMARY = rgb("#1f6feb"),
        PRIMARY_ACTIVE = rgb("#1957bd"),
        ON_PRIMARY = rgb("#ffffff"),
        OK = rgb("#12693a"),
        PAUSE = rgb("#8a5f00"),
        WARN = rgb("#b4222c"),
        STATE_BG = rgb("#eef4ff"),
        STATE_FG = rgb("#19456f"),
        STATE_EDGE = rgb("#c7dcf7"),
        PILL_NEUTRAL_BG = rgb("#eef1f5"),
        PILL_NEUTRAL_FG = rgb("#6b7686"),
        PILL_NEUTRAL_EDGE = rgb("#d7dce4"),
        PILL_OK_BG = rgb("#e4f3e9"),
        PILL_OK_FG = rgb("#12693a"),
        PILL_OK_EDGE = rgb("#b7dcc4"),
        PILL_PAUSE_BG = rgb("#fbf0d9"),
        PILL_PAUSE_FG = rgb("#8a5f00"),
        PILL_PAUSE_EDGE = rgb("#e8ce8e"),
        PILL_WARN_BG = rgb("#fbe9ea"),
        PILL_WARN_FG = rgb("#b4222c"),
        PILL_WARN_EDGE = rgb("#f0b6ba"),
        DISABLED_BG = rgb("#cfd5de"),
        DISABLED_FG = rgb("#98a2b1"),
        DOT_ON = rgb("#3ddc84"),
        DOT_WAIT = rgb("#ffb547"),
        DOT_OFF = rgb("#9aa0a8"),
        DOT_WARN = rgb("#ff5a5f"),
        DOT_RIM = rgb("#ffffff"),
        DOT_PLATE = rgb("#101114"),
        DOT_PLATE_FG = rgb("#ffffff"),
        DISCORD = rgb("#5865f2"),
        ON_DISCORD = rgb("#ffffff"),
    )

    /**
     * The DOT_* values are the light ones again, on purpose: the dot lies
     * over the game, not over the app, and the game does not go dark with
     * the phone (PLAN_ANDROID_DESIGN.md 4.2).
     *
     * And they are not the app's OK / PAUSE / WARN either, since 2026-09-21:
     * the four are drawn on a dark halo over a bright, coloured game, where
     * the app's muted light values sank in, and they answer one question
     * -- is DigiAutotap doing something -- in the colours a glance already
     * knows: green is on, grey is off, amber is about to, red needs you.
     * Paused is grey and not the app's amber for that reason: over a game
     * an amber dot said "careful" where it meant "nothing happens".
     */
    val DARK = Palette(
        BG = rgb("#0f1216"),
        SURFACE = rgb("#171b21"),
        SURFACE_HOVER = rgb("#1f242b"),
        LINE = rgb("#2a313a"),
        TEXT = rgb("#e6eaf0"),
        TEXT_2 = rgb("#8b95a3"),
        PRIMARY = rgb("#58a6ff"),
        PRIMARY_ACTIVE = rgb("#79b8ff"),
        ON_PRIMARY = rgb("#06213f"),
        OK = rgb("#4fc47c"),
        PAUSE = rgb("#e0a13a"),
        WARN = rgb("#ff7b72"),
        STATE_BG = rgb("#14202e"),
        STATE_FG = rgb("#a8cbf5"),
        STATE_EDGE = rgb("#26405e"),
        PILL_NEUTRAL_BG = rgb("#1f242b"),
        PILL_NEUTRAL_FG = rgb("#8b95a3"),
        PILL_NEUTRAL_EDGE = rgb("#2a313a"),
        PILL_OK_BG = rgb("#11261a"),
        PILL_OK_FG = rgb("#4fc47c"),
        PILL_OK_EDGE = rgb("#24512f"),
        PILL_PAUSE_BG = rgb("#2c2110"),
        PILL_PAUSE_FG = rgb("#e0a13a"),
        PILL_PAUSE_EDGE = rgb("#5c4517"),
        PILL_WARN_BG = rgb("#331715"),
        PILL_WARN_FG = rgb("#ff7b72"),
        PILL_WARN_EDGE = rgb("#632723"),
        DISABLED_BG = rgb("#262c34"),
        DISABLED_FG = rgb("#626b78"),
        DOT_ON = rgb("#3ddc84"),
        DOT_WAIT = rgb("#ffb547"),
        DOT_OFF = rgb("#9aa0a8"),
        DOT_WARN = rgb("#ff5a5f"),
        DOT_RIM = rgb("#ffffff"),
        DOT_PLATE = rgb("#101114"),
        DOT_PLATE_FG = rgb("#ffffff"),
        // A step lighter than the brand's own, as PRIMARY is: the brand's
        // blurple sinks into a dark surface. And the mark on it is TEXT's
        // off-white, as nothing in the dark design is pure white.
        DISCORD = rgb("#7289f5"),
        ON_DISCORD = rgb("#e6eaf0"),
    )

    /**
     * The player's choice where they made one, otherwise the system's, asked
     * every time a page is built. The platform half of the same question is
     * res/values-night/theme.xml, which Android answers before the first view
     * exists -- see [wrap], which is how a choice reaches that half too.
     */
    fun dark(c: Context): Boolean = when (mode) {
        Mode.LIGHT -> false
        Mode.DARK -> true
        Mode.SYSTEM -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun of(c: Context): Palette = if (dark(c)) DARK else LIGHT
}
