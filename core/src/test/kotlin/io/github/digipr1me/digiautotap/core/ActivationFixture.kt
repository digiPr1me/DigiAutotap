package io.github.digipr1me.digiautotap.core

/**
 * The throwaway activation key and the tokens signed with it.
 *
 * Every one of these was made by Python, not by Kotlin -- by
 * `core/src/test/resources/activation/make_fixtures.py`, which uses
 * `cryptography` the way the Worker uses WebCrypto: sign to DER, take r and
 * s out of it, put the two 32-byte halves back together, base64url without
 * padding. So what [Unlock.parseToken] is held to here is the other side of
 * the contract as a second language writes it, and not this side's own
 * arithmetic played back to itself (PLAN_SUPPORTER_SERVER.md 2.2).
 *
 * To make them again, key and all:
 *
 *     py core/src/test/resources/activation/make_fixtures.py
 *
 * It prints these constants. A second run over the same key prints
 * different signatures for the same payloads -- ECDSA picks a random nonce
 * -- so they are written down rather than made while the test runs.
 */
object ActivationFixture {

    /** ABCD-EFGH-JKMN, as unlock.py digests it: the anchor UnlockTest already had. */
    const val DIGEST = "943513c7863e02f4e658a19b3ef8c01407831b8c7bdcfc8f6b3d46ac98b7d876"
    const val CODE = "ABCD-EFGH-JKMN"

    const val INSTALL = "7f3c1a92-5b4e-4d0a-9c21-0e8f6b2d4a77"
    const val OTHER_INSTALL = "0b1d2e3f-4a5b-4c6d-8e9f-a0b1c2d3e4f5"
    const val AT = 1695400000L

    /** v 1, [DIGEST], [INSTALL], slot 1, [AT]. */
    const val GOOD = "eyJ2IjoxLCJkaWdlc3QiOiI5NDM1MTNjNzg2M2UwMmY0ZTY1OGExOWIzZWY4YzAxNDA3ODMxYjhjN2JkY2ZjOGY2YjNkNDZhYzk4YjdkODc2IiwiaW5zdGFsbCI6IjdmM2MxYTkyLTViNGUtNGQwYS05YzIxLTBlOGY2YjJkNGE3NyIsInNsb3QiOjEsImF0IjoxNjk1NDAwMDAwfQ.g-Jafd9a4D8XdbcqYXDj5YCK6I2-LCFlMQaQghafctHiQApuOPADdcz59BtgINk3hEVM1ye8yap-njjW_Tkq6g"

    /** The same code on a second phone: [OTHER_INSTALL], slot 2. */
    const val SLOT_TWO = "eyJ2IjoxLCJkaWdlc3QiOiI5NDM1MTNjNzg2M2UwMmY0ZTY1OGExOWIzZWY4YzAxNDA3ODMxYjhjN2JkY2ZjOGY2YjNkNDZhYzk4YjdkODc2IiwiaW5zdGFsbCI6IjBiMWQyZTNmLTRhNWItNGM2ZC04ZTlmLWEwYjFjMmQzZTRmNSIsInNsb3QiOjIsImF0IjoxNjk1NDAwMDYwfQ.jhcoxjCHp7_u_Dh0PQIQ66QIOhG0JrN-DCheXzgTAbMg1PiSZBtMvewv4Pc0tkFRDvGyDXTm0vIBdP3AGn7_dw"

    /**
     * [GOOD] with one character of the signature moved -- from the middle of
     * it, not the end. 64 bytes are 512 bits and 86 base64 characters are
     * 516, so the final character carries four bits no decoder reads: the
     * first version of this fixture moved that one, the bytes came out
     * identical, and the "bent" signature verified. The test found it on its
     * first run.
     */
    const val BENT = "eyJ2IjoxLCJkaWdlc3QiOiI5NDM1MTNjNzg2M2UwMmY0ZTY1OGExOWIzZWY4YzAxNDA3ODMxYjhjN2JkY2ZjOGY2YjNkNDZhYzk4YjdkODc2IiwiaW5zdGFsbCI6IjdmM2MxYTkyLTViNGUtNGQwYS05YzIxLTBlOGY2YjJkNGE3NyIsInNsb3QiOjEsImF0IjoxNjk1NDAwMDAwfQ.g-Jafd9a4D8XdbcqYXDj5YCK6I2-LCFlMQaQghafctHBQApuOPADdcz59BtgINk3hEVM1ye8yap-njjW_Tkq6g"

    /** [GOOD] with one character of the payload moved: the signature covers it. */
    const val BENT_PAYLOAD = "eyJ2IjoxLCJkaWdlc3QiOiI5NDM1MTNjNzg2M2UwMmY0ZTY1OGExOWIzZWY4YzAxNDA3ODMxYjhjN2JkY2ZjOGY2YjNkNDZhYzk4YjdkOBc2IiwiaW5zdGFsbCI6IjdmM2MxYTkyLTViNGUtNGQwYS05YzIxLTBlOGY2YjJkNGE3NyIsInNsb3QiOjEsImF0IjoxNjk1NDAwMDAwfQ.g-Jafd9a4D8XdbcqYXDj5YCK6I2-LCFlMQaQghafctHiQApuOPADdcz59BtgINk3hEVM1ye8yap-njjW_Tkq6g"

    /** Properly signed, and says `v` 2: a token from a contract this build does not know. */
    const val VERSION_TWO = "eyJ2IjoyLCJkaWdlc3QiOiI5NDM1MTNjNzg2M2UwMmY0ZTY1OGExOWIzZWY4YzAxNDA3ODMxYjhjN2JkY2ZjOGY2YjNkNDZhYzk4YjdkODc2IiwiaW5zdGFsbCI6IjdmM2MxYTkyLTViNGUtNGQwYS05YzIxLTBlOGY2YjJkNGE3NyIsInNsb3QiOjEsImF0IjoxNjk1NDAwMDAwfQ.vjOsECCmcAELuRE0n8FPdm-_6REPldvLJDNXmTGZqUgPDFAHdPSTQQOFmtBMnC9sucCM_HaA0rSY2U8fLMGwiA"

    /**
     * [GOOD]'s payload, signed with the second throwaway pair (`test2.*`):
     * the key that replaces a lost one, appended after [PUBLIC_KEY] in
     * [Unlock.ACTIVATION_PUBLIC_KEYS] (NOTES.md, "Publishing").
     */
    const val SECOND_KEY = "eyJ2IjoxLCJkaWdlc3QiOiI5NDM1MTNjNzg2M2UwMmY0ZTY1OGExOWIzZWY4YzAxNDA3ODMxYjhjN2JkY2ZjOGY2YjNkNDZhYzk4YjdkODc2IiwiaW5zdGFsbCI6IjdmM2MxYTkyLTViNGUtNGQwYS05YzIxLTBlOGY2YjJkNGE3NyIsInNsb3QiOjEsImF0IjoxNjk1NDAwMDAwfQ.PdSyqHYw2Z1Z9aAjc6MFdpS7ypp8tZYLhGfaZyz5U6vsljtR5uShNGObZtW5PSVeJJcwuJ-n-dYwwiAJJoMkkg"

    /** The right payload, signed with a P-256 key that is nobody's. */
    const val STRANGER = "eyJ2IjoxLCJkaWdlc3QiOiI5NDM1MTNjNzg2M2UwMmY0ZTY1OGExOWIzZWY4YzAxNDA3ODMxYjhjN2JkY2ZjOGY2YjNkNDZhYzk4YjdkODc2IiwiaW5zdGFsbCI6IjdmM2MxYTkyLTViNGUtNGQwYS05YzIxLTBlOGY2YjJkNGE3NyIsInNsb3QiOjEsImF0IjoxNjk1NDAwMDAwfQ.FRsU5ZWI-Gd4aO0muAFlA12yYwLY7y4R8mfKUHEyF12UamsKnfKoamwo7QaJb9vzjlqpUmUpLGUWxJbkj8Izgg"

    /**
     * The test key's public half, read off `test.pub.pem` on the classpath
     * rather than written down here -- so the pem in the repository is the
     * one thing the fixtures hang from, and a pair replaced without
     * replacing the tokens fails loudly instead of quietly passing.
     */
    val PUBLIC_KEY: String by lazy { pem("test.pub.pem") }

    /** The second pair's public half, which [SECOND_KEY] hangs from. */
    val SECOND_PUBLIC_KEY: String by lazy { pem("test2.pub.pem") }

    private fun pem(name: String): String {
        val pem = ActivationFixture::class.java.getResourceAsStream("/activation/$name")
            ?.use { String(it.readBytes(), Charsets.US_ASCII) }
            ?: error("activation/$name is not on the test classpath")
        return pem.lineSequence()
            .filterNot { it.startsWith("-----") || it.isBlank() }
            .joinToString("")
    }
}
