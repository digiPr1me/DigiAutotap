# DigiAutotap

> **An unofficial fan project.** DigiAutotap is not made, endorsed, sponsored
> or supported by Bandai Namco, Bandai, Toei Animation, or anyone else involved
> in Digimon UP. *Digimon* and every name, character and image belonging to the
> game are the property of their owners.

An Android app that does the repetitive parts of Digimon UP for you, on the
phone (or emulator) itself. It looks at the screen the way you
do and taps where you would.

**Download: [Releases](https://github.com/digiPr1me/digiautotap/releases)**,
the file `digiautotap-<version>-arm64.apk`.

## What it does

Each thing it can do is a **task**, named after what the game calls it, and
each one has its own switch:

- **Bond token** -- collects the bond token from your partner on the main screen
- **World Search** -- plays the Digital World Search board
- **Summon** -- draws on the General tab of Special Summon, and only there
- **Meat Field** -- harvests what is ripe and plants free seeds
- **Dungeons** -- plays the dungeons you pick
- **Quest Loop** -- works through the quest card on the main screen
- **Gekkomon Run** -- plays the Gekkomon Run event, one run after another

A small dot at the top of the screen shows what it is doing; tap it to pause
and tap it again to go on. Most of it is free. A few tasks need a supporter
code (below).


## What it needs

- **Android 11 or later**, a phone with an **arm64** processor (nearly every
  phone from the last years). Tablets and foldables are not supported.
- The game in portrait.

## Installing

1. Download the APK from [Releases](https://github.com/digiPr1me/digiautotap/releases)
   on the phone and open it. Android asks once whether your browser or file
   manager may install apps; allow it.
2. Open DigiAutotap. It asks for three things, one after the other: its
   accessibility service (that is how it sees the screen and taps), the
   notification, and to be left out of battery optimisation.
3. Start the game.

**On Android 13 and later the accessibility switch is greyed out** for an app
that did not come from a store -- "For your security, this setting is
currently unavailable". That is Android's guard for restricted settings. The
way past it:

1. Tap the greyed-out switch once, in Settings > Accessibility > DigiAutotap.
2. Settings > Apps > DigiAutotap, the three-dot menu at the top right,
   **Allow restricted settings**.
3. Back to Accessibility, and the switch takes.

The set-up card in the app says the same and has a button that goes straight
to step 2.

**Updates** do not install themselves. When a new version is out, download it
and install it over the old one; your settings stay. "Check for updates" under
*Settings* (the cog at the top right of the app) opens this page.

### Checking the download

Every release lists the APK's SHA-256. The app is always signed with the same
key, and its certificate's SHA-256 is:

```
3fecad4a7682d1f1390aa16dd592fbbd39b3670ed0997d331ad74dc80ec6682e
```

Android itself refuses an update signed with any other key.

## If it stops or does something odd

- **Phone makers** (Samsung, Xiaomi, Huawei and others) close background apps
  by their own rules. Turn off battery optimisation for DigiAutotap, and if it
  still stops, look for your phone's own "app launch" or "background activity"
  setting and allow DigiAutotap there. When its notification disappears,
  DigiAutotap has been stopped.
- **Report it:** in the app, open *Settings* and tap *Debug package* (the log,
  the settings and the last pictures it looked at), then post it on Discord
  (below) or attach it to a bug report here.
- **Discord:** https://discord.gg/WBrnSpwrR -- questions, help, news.

## Supporter code

A few tasks are unlocked by a supporter code, which comes with a donation on
**[Ko-fi](https://ko-fi.com/digipr1me)**. You type it in once under *Settings*.
One code works on two devices; reinstalling the app on the same phone is not a
new device. To move a code to a new phone, ask on Discord.

## Privacy

Everything DigiAutotap sees on the screen stays on the phone. There is no
analytics and no crash reporting. It goes online in one moment only: when you
redeem a supporter code it sends one request, once, with two values -- a hash
of the code (never the code itself) and an anonymous id for this phone, a
salted hash of the per-app id Android gives it. The server keeps those two and
the time: no e-mail, no IP address. What comes back is a signed token the app
checks offline from then on, in flight mode too, and even if the server is
gone for good. The debug package goes only where you share it.

## Legal notice

The publisher's terms of service explicitly prohibit bots, emulators and
similar tools in section 11 g. Anyone using this software risks having their
game account suspended. It is provided without warranty; the decision to use
it and the consequences are the user's.

DigiAutotap is an independent project. It is not affiliated with, endorsed by
or connected to the publisher of Digimon UP in any way. Product names and
trademarks belong to their respective owners.

## Credits

The names in the app are set in Chakra Petch, © 2018 The Chakra Petch Project
Authors, under the SIL Open Font License 1.1. DigiAutotap itself is free to
use and not free to redistribute; see [LICENSE.txt](LICENSE.txt).
