package io.github.digipr1me.digiautotap.core

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * What a supporter code is, and what says it holds on this phone.
 *
 * The first half is unlock.py's, carried over and unmoved: the same
 * alphabet, the same normalising, PBKDF2-HMAC-SHA256 over the same salt at
 * the same 600_000 rounds. `normalise` and `digest` must never drift from
 * the Python side, because make_codes.py mints with Python's, and a code one
 * side accepts and the other refuses shows up at a supporter, after the
 * money. UnlockTest's Python digests are what says they have not moved.
 *
 * The second half is new (PLAN_SUPPORTER_SERVER.md). A code is no longer
 * looked up in a list inside the APK: it is redeemed once against the
 * activation server ([Activation]), which binds it to this installation and
 * hands back a signed token, and from then on the phone checks only the
 * token, offline, against [ACTIVATION_PUBLIC_KEYS]. So a code handed on to a
 * third stranger stops working, and a revoked code stops working without a
 * build -- neither of which a hash list in the APK could do.
 *
 * Nothing here has an expiry. A token that has been checked once stays good
 * for as long as the phone keeps the file, whatever becomes of the server:
 * this project expects one more release, and an app that dies thirty days
 * after its server is not something to ship (PLAN_SUPPORTER_SERVER.md 1).
 *
 * Keeping the token is the app's job (a file named [SAVED_FILE], with the
 * installation's id beside it in [INSTALL_FILE]); what a token is and
 * whether it holds is decided here.
 */
object Unlock {
    const val KOFI_URL = "https://ko-fi.com/digipr1me"
    const val SAVED_FILE = "supporter.txt"

    /**
     * Where a random install id is kept, beside [SAVED_FILE] -- only on a
     * phone that has no Android id to derive one from ([installIdOf]).
     */
    const val INSTALL_FILE = "install_id"

    /** What an Android id is hashed with before it becomes an install id. */
    const val INSTALL_SALT = "digiautotap-install-v1"

    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val GROUP = 4
    const val GROUPS = 3
    const val LENGTH = GROUP * GROUPS

    val SALT: ByteArray = "digiautotap-supporter-v1".toByteArray(Charsets.US_ASCII)
    const val ROUNDS = 600_000

    /**
     * Where a code is redeemed, and the only address this app ever opens
     * (NetworkTest): the Worker in server/, deployed on 2026-09-23 on the
     * workers.dev subdomain it was given. An empty address ends every redeem
     * in [Activation.Result.Unreachable], which is the honest answer for
     * "there is no server"; a moved Worker is a new release (NOTES.md,
     * "Publishing", step 1).
     */
    const val ACTIVATION_URL =
        "https://digiautotap-activation.digiautotap-activation-server.workers.dev/v1/activate"

    /**
     * The activation keys' public halves: X.509 SubjectPublicKeyInfo for
     * ECDSA P-256, base64 -- exactly what `openssl ec -pubout` writes
     * between its two BEGIN/END lines, and what WebCrypto exports as `spki`.
     * A token is believed when any one of them holds.
     *
     * A list, and only ever appended to. If the private half of the key the
     * Worker signs with is lost, a new pair is made, its private half becomes
     * the Worker's `ACTIVATION_KEY`, and its public half goes on the end of
     * this list in the next release (NOTES.md, "Publishing"). The old key
     * stays: every token a phone already holds was signed with it, and taking
     * it out would lock each of those supporters out at the update. What a
     * list cannot mend is a key that was *stolen* -- a token its thief signs
     * looks exactly like one the Worker signed, so dropping it costs every
     * honest holder too. That key is kept, not replaced.
     *
     * The first entry is the pair made on 2026-09-23
     * (`~/.digiautotap-signing/activation.pub.pem`). With the list empty
     * [parseToken] refuses every token, which is the right way round: a
     * build with no key unlocks nobody, rather than everybody.
     */
    val ACTIVATION_PUBLIC_KEYS: List<String> = listOf(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEoPmInPMVP/t//gOZ/EdAybq6ngAy5BA1zLQoUBmG2yWIL9XL38oEFPbGC0MQ9yzGYMYx5y9O4YNjp56ZkRHs0A==",
    )

    /** Everything that is not an alphabet character is dropped, after upper(). */
    fun normalise(code: String?): String {
        if (code.isNullOrEmpty()) return ""
        return code.trim().uppercase().filter { it in ALPHABET }
    }

    fun pretty(code: String?): String = normalise(code).chunked(GROUP).joinToString("-")

    /** Shape only, and no hashing: a typo answers at once, not after a second. */
    fun looksLikeCode(code: String?): Boolean = normalise(code).length == LENGTH

    /**
     * The one way a code becomes a digest -- the thing the server knows a
     * code by, and the only part of a code that ever leaves the phone. It is
     * the same line that used to be looked up in codes.txt, so what the
     * server stores is what the APK used to carry in the clear.
     */
    fun digest(code: String): String {
        val plain = normalise(code)
        val spec = PBEKeySpec(plain.toCharArray(), SALT, ROUNDS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return key.joinToString("") { "%02x".format(it) }
    }

    // --- The token ---------------------------------------------------------------

    /** What a token says, once it has been believed. */
    data class Token(val digest: String, val install: String, val slot: Int, val at: Long)

    /**
     * The payload's bytes, spelled out.
     *
     * The contract fixes them (PLAN_SUPPORTER_SERVER.md 2.2): these keys,
     * this order, no whitespace. A signature is over bytes and not over a
     * meaning, so reading them with a pattern rather than with a JSON parser
     * is the honest way round -- anything that is not this shape is not the
     * message that was signed, whatever it may parse to. It also keeps core
     * free of a JSON library, which it has never needed.
     *
     * Both braces are escaped. The JVM takes a bare closing `}` as a
     * literal; Android's ICU engine calls it a syntax error, and this field
     * is built with the class, so on 2026-09-23 every start of the app died
     * in `Supporter.look` -- and every test here had passed.
     */
    private val PAYLOAD = Regex(
        """\{"v":(\d+),"digest":"([0-9a-f]{64})","install":"([0-9a-f-]{36})","slot":(\d+),"at":(\d+)\}""")

    /**
     * A token, or null: null means "do not believe this", and nothing else.
     * There is no telling the reasons apart on purpose -- a bent signature,
     * a token for another phone and a made-up one all end in the same place,
     * and a caller that could tell them apart would be tempted to forgive
     * one of them.
     *
     * [publicKeys] is only ever passed in by the tests, which hold two
     * throwaway pairs (core/src/test/resources/activation/); the app takes
     * the default. A key in the list that does not read as a key is passed
     * over, not a reason to refuse what another key holds.
     */
    fun parseToken(text: String?, publicKeys: List<String> = ACTIVATION_PUBLIC_KEYS): Token? {
        val whole = text?.trim() ?: return null
        val dot = whole.indexOf('.')
        if (dot <= 0 || dot != whole.lastIndexOf('.') || dot == whole.length - 1) return null
        val payload = base64url(whole.substring(0, dot)) ?: return null
        val signature = base64url(whole.substring(dot + 1)) ?: return null
        val der = p1363ToDer(signature) ?: return null

        val held = publicKeys.mapNotNull { publicKeyOf(it) }.any { key ->
            runCatching {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(payload)
                    verify(der)
                }
            }.getOrDefault(false)
        }
        if (!held) return null

        // Only now: these bytes are the server's word, and nothing before
        // this line was.
        val m = PAYLOAD.matchEntire(String(payload, Charsets.UTF_8)) ?: return null
        val (v, digest, install, slot, at) = m.destructured
        if (v != "1") return null
        return Token(digest, install,
                     slot.toIntOrNull() ?: return null,
                     at.toLongOrNull() ?: return null)
    }

    /**
     * The install id a token is bound to, made from the phone's Android id
     * (`Settings.Secure.ANDROID_ID`), or null when there is none to make it
     * from.
     *
     * Decided 2026-09-23, replacing a random UUID in the app's files. That
     * one died with the uninstall, so a supporter who reinstalled was a third
     * device: measured on LDPlayer the same day, uninstall, install, the same
     * code -- "already in use on two devices". The Android id has been
     * scoped to the app's signing key since Android 8 and survives an
     * uninstall; it changes with a factory reset, which is what a new device
     * is. So a reinstall redeems into the slot it had (the server's "same
     * installation again", PLAN_SUPPORTER_SERVER.md 2.1), and another app
     * reading its own Android id learns nothing about this one.
     *
     * It never leaves the phone as it is: SHA-256 over [INSTALL_SALT] and
     * the id, the first 16 bytes written as a UUID with the version 8 and
     * variant bits set (RFC 9562's "custom" UUID), so the contract's install
     * field keeps its shape and the server needed no change. Case and
     * surrounding whitespace do not count.
     *
     * This must never move -- not the salt, not the bytes, not the format.
     * Every id already on the server was made this way, and a different
     * answer makes every supporter's next reinstall a third device.
     * UnlockTest pins one value, worked out by Python.
     */
    fun installIdOf(androidId: String?): String? {
        val id = androidId?.trim()?.lowercase()
        if (id.isNullOrEmpty()) return null
        val h = MessageDigest.getInstance("SHA-256")
            .digest("$INSTALL_SALT:$id".toByteArray(Charsets.UTF_8))
        h[6] = ((h[6].toInt() and 0x0f) or 0x80).toByte()
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = h.copyOfRange(0, 16).joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
               "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    /**
     * Is this token this installation's? The id is a UUID the app made
     * ([installIdOf]), so it is lower case on both sides; the comparison forgives
     * case anyway rather than locking a supporter out over it.
     */
    fun holds(token: Token, install: String?): Boolean =
        !install.isNullOrEmpty() && token.install.equals(install, ignoreCase = true)

    /** base64url, padding or none; anything else is null and not an exception. */
    private fun base64url(text: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

    private fun publicKeyOf(spki: String): PublicKey? {
        if (spki.isBlank()) return null
        return runCatching {
            val der = Base64.getDecoder().decode(spki.filterNot { it.isWhitespace() })
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        }.getOrNull()
    }

    /**
     * r-then-s to DER, the twenty lines the contract said would be needed.
     *
     * WebCrypto signs to IEEE P1363 -- the two 32-byte halves, plain -- and
     * Java's SHA256withECDSA reads DER and nothing else. Doing it on this
     * side rather than in the Worker was the choice of 2026-09-22: this is
     * the side with a JVM test behind it.
     *
     * Each half becomes a DER INTEGER, which is signed and minimal: leading
     * zero bytes go, and a leading bit that is set gets a 0x00 in front so
     * the number stays positive. Both halves together are at most 70 bytes,
     * so the SEQUENCE's length is always the short form.
     */
    fun p1363ToDer(signature: ByteArray): ByteArray? {
        if (signature.size != 64) return null
        val r = derInteger(signature.copyOfRange(0, 32))
        val s = derInteger(signature.copyOfRange(32, 64))
        val out = ByteArray(2 + r.size + s.size)
        out[0] = 0x30
        out[1] = (r.size + s.size).toByte()
        r.copyInto(out, 2)
        s.copyInto(out, 2 + r.size)
        return out
    }

    private fun derInteger(raw: ByteArray): ByteArray {
        var first = 0
        while (first < raw.size - 1 && raw[first] == 0.toByte()) first += 1
        val body = raw.copyOfRange(first, raw.size)
        val pad = if (body[0].toInt() and 0x80 != 0) 1 else 0
        val out = ByteArray(2 + pad + body.size)
        out[0] = 0x02
        out[1] = (pad + body.size).toByte()
        body.copyInto(out, 2 + pad)
        return out
    }
}
