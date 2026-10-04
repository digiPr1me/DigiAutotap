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

- **World Search** -- plays the Digital World Search board
- **Summon** -- draws on the General tab of Special Summon, and only there
- **Meat Field** -- harvests, plants, and waters with the free watering cans
- **Dungeons** -- plays the dungeons you pick, the Daily Dungeon and the Lost
  Sector Tower by the minute
- **Bond token** -- collects the bond token from your partner on the main screen
- **Quest Loop** -- works through the quest card on the main screen
- **Gekkomon Run** -- plays the Gekkomon Run event, one run after another
- **EX Missions** -- claims the rewards on the EX Missions tab
- **Presets** -- switches your Digivice, Tactical Memory, Food Effects, Skill
  Cards, Support Digimon and Overdrive to a saved set of slots
- **Chef's Special** -- plays the Chef's Special minigame in the Events window until
  the combos of the daily mission are made

A small dot at the top of the screen shows what it is doing. Tap it to stop
the bot, tap it again to start it afresh; *Turn off* in the app ends the dot
as well. It is blue in semi-automatic mode and green in fully automatic mode.
Most of it is free. A few tasks need a supporter code (below).

**Ad Rewards.** With the game's Ad Skip Pass the free rewards behind an ad --
the two free summons a day on Skill Card and Support Digimon, a dungeon's two
ad tickets, the Meat Field's seeds and cans -- come with one tap, and
DigiAutotap takes them for you: switch on *Ad Skip Pass* in the **Ad Rewards**
card on the main page and pick the tasks there (the Quest Loop follows Summon
and Dungeons). Without the pass DigiAutotap taps no ad and watches none; the
free rewards stay for you to take yourself.


## What it needs

- **Android 11 or later** and an **arm64** processor (nearly every phone and
  tablet from the last years).
- Any phone, tablet or emulator. It has been tested on many screen shapes and
  formats, upright or on its side, with or without a camera cutout. If it does
  not work on yours, share the log (below) on Discord.

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
- **Report it:** in the app, open *Settings*, then *Log*, and tap *Share*. It
  makes a ZIP of the log, the settings and the phone model and hands it to the
  app you pick -- post it in `#bug-reports` on Discord (below) or attach it to
  a bug report here, with what you did and what happened, and a screenshot of
  the game if it is about a screen. Nothing is sent by itself.
- **A task stopped for the day** (out of tickets, the day's combos made): switch
  its row off and on, and it starts its day again.
- **Discord:** https://discord.gg/mJFXtPuXng -- questions, help, news.

## Supporter code

A supporter code from the **[Ko-fi shop](https://ko-fi.com/digipr1me/shop)** --
pay what you want, from 3 € -- unlocks Gekkomon Run, Chef's Special, EX
Missions and Presets. Everything else is free: World Search, Summon, Meat
Field, Dungeons with the Daily Dungeon and the Lost Sector Tower, Bond token
for all of your Digimon, the Quest Loop, and the free ads with the Ad Skip
Pass (above).

The code comes by e-mail, and you type it in once under *Settings*, which shows
it afterwards, covered but for its first four characters; it stays on the
phone, and the shared log shows it only covered. One code works on two
devices; reinstalling the app on the same phone is not a new device. To move
a code to a new phone, ask on Discord.

## Privacy

What DigiAutotap sees on the screen stays on the phone: it keeps no pictures
of the game and sends none. It goes online for three things. When the app is
opened it asks GitHub whether a newer version
is out, and that request carries nothing about you or the phone. Once a day it
tells our server the app version, and once a month what kind of phone it is:
make and model, Android version, screen size, camera cutout and the colour
format of screenshots. That says which phones the app has to work on; it
identifies nobody, and the server keeps no id and no IP address. And when you redeem a supporter code it sends one request, once, with
two values -- a hash of the code (never the code itself) and an anonymous id
for this phone, a salted hash of the per-app id Android gives it. The server
keeps those two and the time: no e-mail, no IP address. What comes back is a
signed token the app checks offline from then on, in flight mode too, and even
if the server is gone for good. *Share* on the Log page makes a ZIP of the
log, the settings and the phone model and hands it to the app you pick --
nothing is sent by itself. The ZIP never holds the token of your supporter
code or the id it is bound to, and the log names the code only covered.

You do not have to take our word for any of this: the source code is public,
right here in this repository, so you can check for yourself exactly what the
app sends and when.

## Building it yourself

The source is in this repository, so you can read what the app does with
your screen and build it. It needs a JDK 17 and the Android SDK with
platform 36, named as `sdk.dir` in a `local.properties` beside
`settings.gradle.kts`; then

```
gradlew :app:assemblePhoneDebug
```

writes an arm64 APK under `app/build/outputs/apk/phone/debug/`
(`assembleEmulatorDebug` an x86_64 one for an emulator). A build of your
own is signed with your own key, so it does not install over the release
and the release does not install over it.

Two folders are empty here on purpose: `templates/` and `digits/`, the
small crops of the game's own screen that the Digital World Search task
reads the board with. They are the game's pictures and are not
distributed, so a build from this source does everything **except Digital
World Search**; the APKs under Releases carry them. The same goes for the
corpus of screen frames the readers are measured against and the oracle
written from it: the tests that need them step aside here, and `gradlew
:core:fastTest` is the part that runs anywhere. It needs the desktop
OpenCV 5.0.0 named as `opencv.dir` in `local.properties` (see
`core/build.gradle.kts`).

The supporter code is a signed token from the activation server, checked
offline (`Unlock.kt`, `Activation.kt` in `core`). Taking that check out of
a build of your own is not hard, and it is not allowed: see
[LICENSE.txt](LICENSE.txt).

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
