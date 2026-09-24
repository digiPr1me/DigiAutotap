package io.github.digipr1me.digiautotap.core

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one log: every line core and the app write, the last [CAPACITY] of
 * them, for the log page and the debug package. On the PC every skill takes
 * a `log` callable; here that callable is [line].
 */
object HelperLog {
    const val CAPACITY = 500
    private val FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Stamps and keeps one line, and hands it to everyone listening. */
    fun line(text: String, now: LocalTime = LocalTime.now()): String {
        val stamped = "${now.format(FMT)}  $text"
        synchronized(lines) {
            lines.addLast(stamped)
            while (lines.size > CAPACITY) lines.removeFirst()
        }
        listeners.forEach { it(stamped) }
        return stamped
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }

    fun listen(l: (String) -> Unit) { listeners += l }
    fun unlisten(l: (String) -> Unit) { listeners -= l }
}
