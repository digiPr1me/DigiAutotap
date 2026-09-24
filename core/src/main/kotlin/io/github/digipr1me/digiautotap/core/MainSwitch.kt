package io.github.digipr1me.digiautotap.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The main switch: the one stop the director asks between two actions, and
 * nothing beside it (PLAN_ANDROID_5_SHELL.md 3.3). The notification, the app
 * page and later the overlay read it and write it here; none of them keeps a
 * copy. A second state in the interface that can drift from this one is
 * what `wait_while_paused` already cost once (NOTES.md: the Summons skill's
 * Stop button set a flag that nobody looked at).
 *
 * On in a fresh process, and the app sets it on again when it is opened:
 * the player's decision of 2026-09-19.
 */
object MainSwitch {
    @Volatile
    var on: Boolean = true
        private set

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun set(value: Boolean) {
        if (on == value) return
        on = value
        listeners.forEach { it(value) }
    }

    fun toggle() = set(!on)

    fun listen(l: (Boolean) -> Unit) { listeners += l }
    fun unlisten(l: (Boolean) -> Unit) { listeners -= l }
}
