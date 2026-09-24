"""
The test key pair and the fixture tokens UnlockTest checks `parseToken`
against. Run it once; it is here so the fixtures can be made again, not
because anything reads it at test time.

    py core/src/test/resources/activation/make_fixtures.py

It writes test.pkcs8.pem and test.pub.pem beside itself if they are not
there yet (never overwriting a pair the committed fixtures were signed
with), and prints the Kotlin constants for UnlockTest. ECDSA signs with a
random nonce, so a second run prints different signatures over the same
payloads -- which is exactly why the tokens are constants in the test and
not made while it runs.

These keys are throwaways. The real one is a Worker secret, backed up
in ~/.digiautotap-signing/ (NOTES.md, "Publishing").
"""
import base64, json, pathlib

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

HERE = pathlib.Path(__file__).parent
PRIV, PUB = HERE / "test.pkcs8.pem", HERE / "test.pub.pem"
# A second throwaway pair: the key that replaces a lost one, for the test that
# says a token of either key in Unlock.ACTIVATION_PUBLIC_KEYS is believed.
PRIV2, PUB2 = HERE / "test2.pkcs8.pem", HERE / "test2.pub.pem"


def b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def key(priv=PRIV, pub=PUB):
    if priv.exists():
        return serialization.load_pem_private_key(priv.read_bytes(), password=None)
    k = ec.generate_private_key(ec.SECP256R1())
    priv.write_bytes(k.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption()))
    pub.write_bytes(k.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo))
    return k


def payload(v, digest, install, slot, at) -> bytes:
    """The contract's bytes, in the contract's order, no whitespace
    (PLAN_SUPPORTER_SERVER.md 2.2). json.dumps with these separators is
    what the Worker's JSON.stringify writes."""
    return json.dumps({"v": v, "digest": digest, "install": install,
                       "slot": slot, "at": at},
                      separators=(",", ":")).encode()


def sign(k, raw: bytes) -> str:
    """P1363 (r||s, 64 bytes), which is what WebCrypto hands back; the app
    turns it into DER before Java's SHA256withECDSA sees it."""
    der = k.sign(raw, ec.ECDSA(hashes.SHA256()))
    r, s = utils.decode_dss_signature(der)
    return b64u(r.to_bytes(32, "big") + s.to_bytes(32, "big"))


def token(k, **kw) -> str:
    raw = payload(**kw)
    return b64u(raw) + "." + sign(k, raw)


DIGEST = "943513c7863e02f4e658a19b3ef8c01407831b8c7bdcfc8f6b3d46ac98b7d876"  # ABCD-EFGH-JKMN
INSTALL = "7f3c1a92-5b4e-4d0a-9c21-0e8f6b2d4a77"
OTHER = "0b1d2e3f-4a5b-4c6d-8e9f-a0b1c2d3e4f5"
AT = 1695400000

k = key()
k2 = key(PRIV2, PUB2)
stranger = ec.generate_private_key(ec.SECP256R1())

good = token(k, v=1, digest=DIGEST, install=INSTALL, slot=1, at=AT)
payload_b64, sig_b64 = good.split(".")


def flip(text: str, at: int) -> str:
    """One character somewhere in the middle.

    Not the last one: 64 bytes are 512 bits and 86 base64 characters are
    516, so the final character carries four bits nothing reads. The first
    version of this file moved that character, Java's decoder dropped the
    difference, and the 'bent' signature verified -- which the test caught
    on its first run.
    """
    other = "B" if text[at] != "B" else "C"
    return text[:at] + other + text[at + 1:]


bent = payload_b64 + "." + flip(sig_b64, len(sig_b64) // 2)
bent_payload = flip(payload_b64, len(payload_b64) // 2) + "." + sig_b64

out = {
    "SPKI": base64.b64encode(k.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo)).decode(),
    "GOOD": good,
    "SLOT_TWO": token(k, v=1, digest=DIGEST, install=OTHER, slot=2, at=AT + 60),
    "BENT": bent,
    "BENT_PAYLOAD": bent_payload,
    "VERSION_TWO": token(k, v=2, digest=DIGEST, install=INSTALL, slot=1, at=AT),
    "SECOND_KEY": token(k2, v=1, digest=DIGEST, install=INSTALL, slot=1, at=AT),
    "STRANGER": token(stranger, v=1, digest=DIGEST, install=INSTALL, slot=1, at=AT),
}
for name, value in out.items():
    print(f'    const val {name} = "{value}"')
