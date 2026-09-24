# The activation test keys

**Throwaways.** `test.pkcs8.pem` and `test2.pkcs8.pem` are private keys
that are committed on purpose: they sign the fixture tokens in `UnlockTest`
and `ActivationTest`, and they unlock nothing anywhere. The second stands
for the key that replaces a lost one, for the test that says a token of
any key in `Unlock.ACTIVATION_PUBLIC_KEYS` is believed. Neither is the
activation key.

The real pair is made once and never lives here: the private half is a
Cloudflare Worker secret with a backup beside the signing key in
`~/.digiautotap-signing/`, and the public half is the first entry of
`Unlock.ACTIVATION_PUBLIC_KEYS`. What losing it costs, and the release
that mends it, is in NOTES.md, "Publishing".

`make_fixtures.py` makes both pairs if they are not there and prints the
constants the tests hold. Running it again over the existing pair prints
different signatures for the same payloads -- ECDSA signs with a random
nonce -- which is why the tokens are constants in the tests and are not
made while they run:

```
py core/src/test/resources/activation/make_fixtures.py
```

`test.pub.pem` and `test2.pub.pem` are read at test time; the tests get
the public keys from there and never from `Unlock`'s own list, so a test
says nothing about whether the shipped key is right. What says that is a redeem on a real phone.
