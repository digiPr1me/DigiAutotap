package io.github.digipr1me.digiautotap.core

import java.util.Locale

/**
 * Every number core writes goes out in [Locale.US].
 *
 * Kotlin's own `String.format` takes the machine's default locale, and this
 * machine is a phone in whatever country its owner lives in. On a German
 * one `"%.3f".format(0.437)` writes **0,437** and `"%,d"` writes 5.000 for
 * five thousand. That was found on the passive helper's first test run and
 * fixed there, in that one file, by naming [Locale.US] at each call
 * (NOTES.md, "Jede Zahl im Log geht durch Locale.US") -- and then a live
 * run of the director printed
 *
 *   something bubble-shaped at 0,437/0,377
 *
 * out of `Passive.kt`, which nobody had gone through. Fifty-odd calls in
 * this package take the default locale, and the next one written will too.
 *
 * So the fix is not another fifty call sites: it is this. A declaration in
 * the package beats a default import, so every `"...".format(...)` in
 * `io.github.digipr1me.digiautotap.core` resolves to this one instead of
 * `kotlin.text.format`, wherever it is written and whenever it is written
 * next. Nothing at a call site has to remember anything, which is the
 * only kind of rule that holds.
 *
 * Two things this does not reach, on purpose: `String.format(...)` written
 * the Java way, which names its locale already in every call this package
 * has, and anything outside this package.
 *
 * A decimal comma is not a cosmetic matter here. These lines are the
 * evidence a live run leaves behind -- a bug report, a frame's coordinates,
 * a counter -- and a number whose separator depends on the reader's phone
 * cannot be compared with the one measured in Python.
 */
internal fun String.format(vararg args: Any?): String =
    java.lang.String.format(Locale.US, this, *args)
