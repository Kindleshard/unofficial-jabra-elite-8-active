# Unofficial Jabra Elite 8 Active

Free, open-source apps for the **Jabra Elite 8 Active** earbuds: one for Android, and a tray app for Windows. They change the same settings as Jabra's own Sound+ app, plus a few things it doesn't do, with no account, no ads and no tracking.

> **Unofficial.** This project isn't made by, affiliated with or endorsed by Jabra or GN Audio. "Jabra" and "Elite" are their trademarks, used here only to say which earbuds the apps work with. The earbuds' protocol was worked out by observing Sound+ (documented in [`jabra-protocol-notes.md`](jabra-protocol-notes.md)), so use the apps at your own risk.

## Which earbuds

Only the **Jabra Elite 8 Active**, tested with firmware 4.6.0. Both apps read the model from the earbuds (product ID 11847) and refuse anything else, because every setting was decoded on this model and writing it to another could change the wrong thing. Other Jabra earbuds, including later generations, aren't supported.

## Download

From the [Releases](../../releases) page:

- **Android:** `elite8active-<version>-full.apk`. Android 12 or newer.
- **Windows:** `Elite8Active.exe`, one file with everything included. Windows 10 or 11, 64-bit. Windows may warn the first time, because the file isn't code-signed.

The Android app is also on its way to **F-Droid**, built from this source by F-Droid itself. The F-Droid build uses only free libraries, so its automatic modes switch by place but not by what you're doing (that part needs Google Play services).

## What they do

**Android** ([details](android/README.md))
- ANC, HearThrough and its level, the six EQ presets, a 5-band custom EQ and your own saved presets, Spatial Sound
- Every button gesture, wearing detection, call settings (sidetone and its volume, mute reminder…), sleep timer, voice guidance, the press-and-hold voice assistant, and renaming the earbuds
- Battery for each earbud and the case, and which earbud is in the case or out of range
- Find My earbuds (where they last disconnected), a left-behind alert, a locator sound
- Automatic modes by place (and, in the GitHub build, by what you're doing), a Quick Settings tile, app-icon shortcuts, low-battery and firmware-update notifications, a battery-health estimate

**Windows** ([details](windows/README.md))
- A tray icon with battery and sound mode at a glance
- A panel with the sound and earbud settings, and keyboard shortcuts for ANC / HearThrough / Off
- It only connects while the earbuds are connected to that PC, and works alongside the phone app

## Privacy

Nothing leaves your devices. There's no account, analytics or crash reporting. Find My locations, battery history and settings stay on the phone (or PC) that recorded them. The only network use is the optional firmware notice, off by default, which reads Jabra's public release-notes page once a week.

## Building and contributing

- Android: [`android/README.md`](android/README.md)
- Windows: [`windows/README.md`](windows/README.md)
- How the protocol works: [`jabra-protocol-notes.md`](jabra-protocol-notes.md), [`feature-map.md`](feature-map.md), and the scripts in [`tools/`](tools) and [`jabra_gnp.py`](jabra_gnp.py) used to decode it

Issues and pull requests are welcome, especially reports from other firmware versions.

## License

[GNU General Public License v3.0 or later](LICENSE). You may use, study, share and change the apps; modified versions you share must stay open under the same licence.
