package io.github.digipr1me.digiautotap.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The oracle's number format, from the Kotlin side (PLAN_ANDROID_2_ORACLE.md
 * 3.2). oracle.py writes `round(x, 4)`; this is the same rounding, done on
 * the double's exact value, half to even as Python's `round` does.
 */
object Oracle {
    const val DECIMALS = 4

    fun round(x: Double): BigDecimal =
        BigDecimal(x).setScale(DECIMALS, RoundingMode.HALF_EVEN).stripTrailingZeros()

    /**
     * An answer as JSON: maps with sorted keys, lists, strings, ints,
     * booleans, null, and every double at four places. What a Kotlin reader
     * prints this way can be laid beside the oracle's line for the same
     * frame, and what the app writes to the log is in that form too.
     */
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Double -> {
                val r = round(value)
                // Python writes 1.0, not 1, for a float that rounds whole.
                out.append(if (r.scale() <= 0) r.setScale(1).toPlainString() else r.toPlainString())
            }
            is String -> {
                out.append('"')
                for (c in value) when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    else -> out.append(c)
                }
                out.append('"')
            }
            is Map<*, *> -> {
                out.append('{')
                value.entries.sortedBy { it.key as String }.forEachIndexed { i, e ->
                    if (i > 0) out.append(", ")
                    write(out, e.key as String)
                    out.append(": ")
                    write(out, e.value)
                }
                out.append('}')
            }
            is Pair<*, *> -> write(out, listOf(value.first, value.second))
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(", ")
                    write(out, v)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("no oracle form for ${value::class}")
        }
    }
}
