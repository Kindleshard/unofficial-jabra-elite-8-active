# Unofficial Jabra Elite 8 Active for Windows

A tray app that controls the **Jabra Elite 8 Active** from a PC, so the phone can stay in your pocket while the earbuds are connected to the computer. It speaks the same protocol as the Android app in `../android` (GNP over Bluetooth RFCOMM channel 29, documented in `../jabra-protocol-notes.md`).

Tested on Windows 11 with firmware 4.6.0. Not made by or affiliated with Jabra; see the [main README](../README.md).

It only connects to the Jabra Elite 8 Active (product ID 11847). Every setting was decoded on that model, so other Jabra devices are refused rather than risking a wrong write.

## What it does

- **Tray icon:** an earbud next to the clock, amber while the earbuds are connected to this PC and grey otherwise, with a red dot when a battery is low. Hovering shows the battery and sound mode. Right-click for ANC / HearThrough / Off.
- **Panel** (click the icon): opens in the corner of the screen and closes when you click elsewhere or press Esc.
  - On top, always: the earbuds' name, connection, and half-circle battery gauges for left, right and case. An earbud in the case or out of range is faded and says "Offline".
  - **Sound:** the three sound modes, the HearThrough level, the six Sound+ EQ presets, your own saved presets (right-click one to delete it), a 5-band custom EQ, and Spatial Sound.
  - **Earbuds:** wearing detection and what depends on it, call settings (sidetone and its volume, mute reminder, automatic volume, in-call audio), general settings (HearThrough behaviour, sleep timer, guidance, reduce wind), the mode button's cycle and the press-and-hold assistant.
  - **This PC:** the app's own options, below.
- **Keyboard shortcuts:** Ctrl+Alt+Shift+F9 / F10 / F11 for ANC / HearThrough / Off, from anywhere, with a short confirmation on screen. Each can be changed (click it, press the new keys) or all switched off. If another program already has a combination, the panel says so.
- **Low-battery notification:** when an earbud or the case drops to 10 %, once until it has charged past 20 %.
- **Start with Windows** (off by default): adds an entry for the current user only.
- **From the command line:** `Elite8Active.exe --mode anc|hearthrough|off` switches the mode through the copy that's already running (or starts one), for scripts, desktop shortcuts and macro keys. `--quit` closes the running copy.

Not in the Windows app: remapping what each button press does, renaming the earbuds, Find My, battery health and the hearing test. Those are in the phone app.

## How it connects

- The app **follows Windows** instead of reaching for the earbuds itself. It opens the control channel while the earbuds are one of this PC's audio outputs, and lets go when they stop being one (back in the case, or "Disconnect" in Bluetooth settings). Earbuds that are only with the phone are left alone.
- It checks every two seconds, so it connects a moment after the earbuds do. While they're away, the panel shows the last values, faded.
- **Phone and PC at once:** the earbuds give each connected device its own control channel, so this app and the phone app work side by side. A change made in one shows up in the other.
- **One program per PC:** only one program on this PC can hold the channel. Quit the tray app before running `../jabra_gnp.py` or the scripts in `../tools`, and the other way round.
- Every change shows at once and is then confirmed by reading the value back. If the earbuds refuse it, the panel says why and shows the real value again.

## Build and run

Needs the .NET 9 SDK.

```
dotnet build
dotnet test
dotnet publish src/Elite8Active -c Release -r win-x64 -o publish
```

`publish\Elite8Active.exe` is the whole app in one file, with .NET included, so it runs on any 64-bit Windows 10 or 11 without installing anything. Starting it a second time opens the running copy's panel. Windows SmartScreen may warn about it the first time, because the file isn't code-signed.

It keeps `settings.json` (shortcuts, saved EQ presets, options, and the earbuds' address once they've connected) and `last_state.json` (the last values read) in `%AppData%\Elite8Active`, and a small connection log in `%LocalAppData%\Elite8Active\log.txt`. Nothing is sent anywhere.

## Code

| Project | What's in it |
|---|---|
| `src/Elite8Active.Core` | Everything without a UI. `Protocol/` is the frame format and every decoded setting as data (the same table as the Android app's `gnp/`). `Bluetooth/` is the Winsock RFCOMM socket, Windows' paired-device list and audio outputs. `EarbudSession` matches replies to requests; `EarbudsController` keeps the connection and applies changes; `Store/` is what's kept on disk. |
| `src/Elite8Active` | The WPF tray app: `PanelView` (the panel), `PanelViewModel`, `PanelWindow` (the flyout), `TrayIcon`, `Hotkeys`, `Osd`. The artwork in `Art.cs` is copied from the Android app's vector drawables. |
| `tests/Elite8Active.Tests` | Unit tests. The protocol tests use real frames from the Sound+ captures; the controller tests run against `FakeEarbuds`, which answers the way the real ones were seen to. |

**Live tests.** `LiveTests` run the real stack against earbuds connected to this PC. They read everything, then change the sound mode and EQ and put them back. They're skipped unless asked for:

```
set ELITE_LIVE=1
dotnet test --filter FullyQualifiedName~LiveTests
```

**Looking at the UI without clicking through it.** The app can draw its panel and icons to image files, with made-up values:

```
Elite8Active.exe --render panel.png --state connected|offline|away|failed|unpaired --tab 0|1|2 --height 720
Elite8Active.exe --render-icons icons.png
Elite8Active.exe --make-icon src\Elite8Active\Assets\app.ico
Elite8Active.exe --render-app-icon icon.png --size 512
```

## Known limits

- The earbuds are found by name the first time (a paired device whose name contains "Jabra"), and by their address after that, so renaming them later is fine.
- "Offline" means the two earbuds aren't linked to each other: one is in the case or out of range. The earbuds don't say which, or whether it's charging.
- The saved EQ presets are per device: the ones saved here don't appear in the phone app, and the other way round. The earbuds only hold the current curve.
- The custom EQ's dB scale is assumed to be 0.01 dB per step; this hasn't been measured.
- Dark theme only.
