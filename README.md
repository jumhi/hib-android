# H.I.B for Android

**Your Android phone and your computer, working as one.** Notifications, calls, clipboard, files,
music, phone mirroring and **Flick** — point your phone at a screen and throw a photo, file or link
onto it. Works with H.I.B for Windows (and the H.I.B Mac app). Made by Haitham Hamid.

## Get started

1. Download `H.I.B.apk` from the [latest release](https://github.com/jumhi/hib-android/releases/latest)
   on your phone and open it.
   - Allow your browser to install apps when Android asks.
   - Samsung: if it's blocked, turn off **Settings › Security and privacy › Auto Blocker** for a
     moment, install, then turn it back on.
2. Install H.I.B on your computer:
   [H.I.B for Windows](https://github.com/jumhi/hib-windows#get-started-5-minutes).
3. Open H.I.B on the phone, tap **Scan**, and scan the QR code shown on the computer.
4. Mirroring is optional: start it from the computer and H.I.B guides you through the one-time
   Wireless debugging setup — no terminal, nothing to install.

Requires Android 11 or newer.

## Features

- Notifications on the computer, with replies and actions; calls; clipboard both ways; media and volume
- **Flick:** point at a screen (any computer, any monitor) and throw photos, videos, files, links or text
- **Devices:** the phone and every computer in one list; files between all of them
- Phone screen mirroring, phone apps in their own windows, phone audio on the computer
- Find My phone, ring the phone, Phone Agent

## Credits and licence

H.I.B for Android is a modified build of [AirSync for Android](https://github.com/sameerasw/airsync-android)
by Sameera Sandakelum (sameerasw). It is **not** affiliated with or endorsed by AirSync, and doesn't use
its name, logo or branding. Licensed under the Mozilla Public License 2.0 with the additional terms in
[LICENSE](LICENSE), which are kept as required. Release builds are signed by Haitham Hamid, who takes
responsibility for the changes.

### Building

```sh
./gradlew :app:assembleDebug          # debug build
./gradlew :app:assembleRelease        # release; signed only if a keystore.properties is provided
```

Release signing reads `$HIB_SIGNING_PROPS` (or `~/Library/Application Support/H.I.B/signing/keystore.properties`):
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Never commit it.
