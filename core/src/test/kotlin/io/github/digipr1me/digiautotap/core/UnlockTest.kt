package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The supporter check against the minting tools'. The digests below were
 * computed by unlock.py itself (`py -c "import unlock; print(unlock.digest(...))"`),
 * which is what make_codes.py mints with: a code from the pool has to hold
 * here, or it is minted and worthless.
 *
 * The second half is the token (PLAN_SUPPORTER_SERVER.md 2.2), against the
 * fixtures a second language signed ([ActivationFixture]).
 */
class UnlockTest {

    private val pythonDigests = mapOf(
        "ABCD-EFGH-JKMN" to "943513c7863e02f4e658a19b3ef8c01407831b8c7bdcfc8f6b3d46ac98b7d876",
        "zz99-2345-6789" to "af374fdd2c2ba3c8853050a94825ac1bc497eb62712a6b3f06773de03a2c74d6",
    )

    private val key = listOf(ActivationFixture.PUBLIC_KEY)

    @Test
    fun `the same code however it was typed`() {
        for (written in listOf("ABCD-EFGH-JKMN", "abcd-efgh-jkmn", "ABCDEFGHJKMN",
                               "  abcd efgh jkmn\n", "AbCd-EfGh-JkMn")) {
            assertEquals("ABCDEFGHJKMN", Unlock.normalise(written), written)
        }
        assertEquals("ABCD-EFGH-JKMN", Unlock.pretty("abcdefghjkmn"))
    }

    /**
     * The alphabet, the salt and the rounds are what the pool in
     * codes_issued.csv was minted with (make_codes.py, unlock.py): a change
     * to any of them makes every code handed out so far worthless, and the
     * digests below are what says they have not moved. This outlived
     * codes.txt: the digests are the server's table now, and they are the
     * same digests.
     */
    @Test
    fun `the alphabet leaves out the look-alikes`() {
        for (c in "ILO01") assertFalse(c in Unlock.ALPHABET, "$c")
        assertEquals(31, Unlock.ALPHABET.length)
        assertEquals("ABCDEFGHJKMNPQRSTUVWXYZ23456789", Unlock.ALPHABET)
        assertEquals("digiautotap-supporter-v1", String(Unlock.SALT))
        assertEquals(600_000, Unlock.ROUNDS)
    }

    @Test
    fun `wrong shapes are refused`() {
        assertTrue(Unlock.looksLikeCode("ABCD-EFGH-JKMN"))
        assertFalse(Unlock.looksLikeCode("ABCD-EFGH"))
        assertFalse(Unlock.looksLikeCode("ABCD-EFGH-JKMN-PQRS"))
        assertFalse(Unlock.looksLikeCode(""))
        assertFalse(Unlock.looksLikeCode(null))
    }

    @Test
    fun `digest is Python's, byte for byte`() {
        for ((code, hex) in pythonDigests) assertEquals(hex, Unlock.digest(code), code)
    }

    /** And the digest in the fixture token is that same code's. */
    @Test
    fun `the fixture token is about a code this build can hash`() {
        assertEquals(ActivationFixture.DIGEST, Unlock.digest(ActivationFixture.CODE))
    }

    // --- The token ---------------------------------------------------------------

    @Test
    fun `a token the activation key signed reads back, field for field`() {
        val t = assertNotNull(Unlock.parseToken(ActivationFixture.GOOD, key))
        assertEquals(ActivationFixture.DIGEST, t.digest)
        assertEquals(ActivationFixture.INSTALL, t.install)
        assertEquals(1, t.slot)
        assertEquals(ActivationFixture.AT, t.at)

        val second = assertNotNull(Unlock.parseToken(ActivationFixture.SLOT_TWO, key))
        assertEquals(2, second.slot)
        assertEquals(ActivationFixture.OTHER_INSTALL, second.install)
    }

    /** The file holds a line, and a line read back has a newline on it. */
    @Test
    fun `whitespace around the token is not part of it, and padding is allowed`() {
        assertNotNull(Unlock.parseToken("  ${ActivationFixture.GOOD}\n", key))
        // base64url without padding is what the contract says the server
        // writes; a decoder that refused the padded form would turn a
        // harmless helper on the other side into a lockout.
        val (payload, signature) = ActivationFixture.GOOD.split(".")
        val padded = payload.padEnd((payload.length + 3) / 4 * 4, '=') + "." +
            signature.padEnd((signature.length + 3) / 4 * 4, '=')
        assertNotNull(Unlock.parseToken(padded, key))
    }

    @Test
    fun `a token that is not this key's word is refused`() {
        // One character of the signature moved -- from the middle of it: the
        // last character of an 86-character signature carries four bits that
        // nothing reads, and moving that one leaves the bytes as they were.
        // This test said so the first time it ran.
        assertNull(Unlock.parseToken(ActivationFixture.BENT, key))
        // One character of the payload moved: the signature is over it.
        assertNull(Unlock.parseToken(ActivationFixture.BENT_PAYLOAD, key))
        // Signed properly, by somebody else.
        assertNull(Unlock.parseToken(ActivationFixture.STRANGER, key))
        // Signed by the right key, and about a contract this build has not read.
        assertNull(Unlock.parseToken(ActivationFixture.VERSION_TWO, key))
    }

    /**
     * The recovery from a lost activation key (NOTES.md, "Publishing"): a
     * new pair, its public half appended. A phone that redeemed under the
     * old key still holds, a phone that redeems under the new one holds too,
     * and a third key is nobody's word however long the list grows.
     */
    @Test
    fun `a token of any listed key is believed, and only of those`() {
        val both = listOf(ActivationFixture.PUBLIC_KEY, ActivationFixture.SECOND_PUBLIC_KEY)
        assertNotNull(Unlock.parseToken(ActivationFixture.GOOD, both), "the old key's token")
        val second = assertNotNull(Unlock.parseToken(ActivationFixture.SECOND_KEY, both),
                                   "the new key's token")
        assertEquals(ActivationFixture.INSTALL, second.install)
        assertNull(Unlock.parseToken(ActivationFixture.STRANGER, both), "a stranger's token")

        // Each key is its own word: the new key's token is not the old key's.
        assertNull(Unlock.parseToken(ActivationFixture.SECOND_KEY, key))
        // A broken entry is passed over, not a reason to refuse the others.
        assertNotNull(Unlock.parseToken(ActivationFixture.GOOD, listOf("", "not a key") + key))
        assertNull(Unlock.parseToken(ActivationFixture.GOOD, emptyList()))
    }

    /**
     * The install id from the Android id. The two values were worked out by
     * Python (hashlib, the same salt and bit-twiddling) and not by this
     * code: every id on the server was made this way, and an answer that
     * moved would make every supporter's next reinstall a third device.
     */
    @Test
    fun `the install id is the Android id's, and never moves`() {
        assertEquals("43ae1581-05fc-8838-9558-79e246886447", Unlock.installIdOf("9f3b2c4d5e6a7b8c"))
        assertEquals("c26ef1a3-2767-8b93-ae7a-6cc48f003d73", Unlock.installIdOf("0123456789abcdef"))
        // The same phone however the id comes back.
        assertEquals(Unlock.installIdOf("9f3b2c4d5e6a7b8c"), Unlock.installIdOf(" 9F3B2C4D5E6A7B8C\n"))
        // Nothing to make it from is null, and never an id everybody shares.
        for (none in listOf(null, "", "   ")) assertNull(Unlock.installIdOf(none), "took '$none'")

        // The shape the server takes (server/src/index.ts, UUID) and the
        // token pattern reads back, with the version 8 and variant bits set.
        val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        for (id in listOf("9f3b2c4d5e6a7b8c", "0", "a".repeat(64), "ümlaut")) {
            val made = assertNotNull(Unlock.installIdOf(id))
            assertTrue(uuid.matches(made), made)
        }
    }

    @Test
    fun `nonsense is null and never an exception`() {
        val payload = ActivationFixture.GOOD.substringBefore('.')
        val signature = ActivationFixture.GOOD.substringAfter('.')
        for (text in listOf(null, "", "   ", ".", "$payload.", ".$signature",
                            payload, ActivationFixture.GOOD.replace('.', '-'),
                            "$payload.$signature.$signature",
                            "$payload.!!!!", "%%%%.$signature",
                            // The payload's own bytes, unsigned and in the clear.
                            """{"v":1,"digest":"${ActivationFixture.DIGEST}"}""")) {
            assertNull(Unlock.parseToken(text, key), "took $text")
        }
    }

    @Test
    fun `a token belongs to one installation`() {
        val t = assertNotNull(Unlock.parseToken(ActivationFixture.GOOD, key))
        assertTrue(Unlock.holds(t, ActivationFixture.INSTALL))
        assertTrue(Unlock.holds(t, ActivationFixture.INSTALL.uppercase()))
        assertFalse(Unlock.holds(t, ActivationFixture.OTHER_INSTALL))
        assertFalse(Unlock.holds(t, ""))
        assertFalse(Unlock.holds(t, null))
    }

    /**
     * The two constants that are filled in when the Worker is deployed
     * (step 1 of the release recipe). Whichever state they are in, they have
     * to be in it properly: empty means nothing is unlocked, and set means
     * set right. What a test cannot say is whether the key that is in there
     * is the Worker's -- that is Session D's live proof.
     */
    @Test
    fun `the shipped activation keys are P-256 keys and none of them a test key`() {
        // Whatever is listed, the throwaway pairs' tokens are nobody's word.
        assertNull(Unlock.parseToken(ActivationFixture.GOOD),
                   "a shipped key is the throwaway test key")
        assertNull(Unlock.parseToken(ActivationFixture.SECOND_KEY),
                   "a shipped key is the second throwaway test key")
        // And every entry reads as a P-256 key: a mistyped one would be
        // passed over in silence, and the first anyone heard of it would be
        // a supporter whose token that key signed.
        for (spki in Unlock.ACTIVATION_PUBLIC_KEYS) {
            val der = java.util.Base64.getDecoder().decode(spki)
            val pub = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(der))
            val curve = (pub as java.security.interfaces.ECPublicKey).params.curve.field.fieldSize
            assertEquals(256, curve, spki)
        }
        assertEquals(Unlock.ACTIVATION_PUBLIC_KEYS.size, Unlock.ACTIVATION_PUBLIC_KEYS.toSet().size,
                     "a key is listed twice")
        val url = Unlock.ACTIVATION_URL
        assertTrue(url.isEmpty() || url.startsWith("https://"),
                   "the activation address is not https: $url")
    }

    /**
     * The one piece of arithmetic in here, at its edges: r is one byte long
     * after its leading zeros go, and s has its top bit set and so needs a
     * 0x00 in front to stay a positive DER INTEGER. Everything in between is
     * covered by the fixtures above, which would not verify if this were
     * wrong.
     */
    @Test
    fun `r and s become a DER sequence, minimal and positive`() {
        assertNull(Unlock.p1363ToDer(ByteArray(63)))
        assertNull(Unlock.p1363ToDer(ByteArray(65)))

        val sig = ByteArray(64)
        sig[31] = 1                 // r = 1
        sig[32] = 0x80.toByte()     // s = 0x8000...00
        val der = assertNotNull(Unlock.p1363ToDer(sig))
        val expected = "3026" + "020101" + "022100" + "80" + "00".repeat(31)
        assertEquals(expected, der.joinToString("") { "%02x".format(it) })

        // A zero r is still one byte, and 0x00 is not stripped down to nothing.
        val zero = assertNotNull(Unlock.p1363ToDer(ByteArray(64)))
        assertEquals("3006" + "020100" + "020100", zero.joinToString("") { "%02x".format(it) })
    }
}
