# Unofficial Jabra Elite 8 Active for Android

An open Android companion app for the **Jabra Elite 8 Active** (tested with firmware 4.6.0 on Android 17). It speaks Jabra's GN protocol (GNP) over Bluetooth RFCOMM channel 29, as reverse-engineered in `../jabra-protocol-notes.md`. It works with LE Audio on: audio goes over LE, and the app's control channel uses the Classic link next to it. Not made by or affiliated with Jabra; see the [main README](../README.md).

It only connects to the Jabra Elite 8 Active (product ID 11847, read from the earbuds). Every setting was decoded on that model, so other Jabra devices are refused rather than risking a wrong write.

The same controls for a PC are in `../windows`: a tray app for when the earbuds are connected to the computer.

## Screens

Four tabs with bottom navigation, in a navy-and-amber theme (dark and light):
- **Home:**
  - The device card: name (tap the pencil to rename), connection, and half-circle battery gauges for left, right and case. An earbud that's in the case or out of range is drawn faded and its gauge says "Offline". Below the gauges is the battery-health verdict.
  - Noise canceling modes (ANC / HearThrough / Off), with the HearThrough level while it's on.
  - The six EQ presets as illustrated tiles, then your saved ones, Spatial Sound (Dolby), and Find My.
  - During a call, a card at the top with the call settings.
- **Sound:** the EQ graph is the editor (drag a point; only a touch that starts on a point edits, so the page still scrolls), with Reset back to the curve from before the edit and Save to keep it under a name. Then the presets, "My presets" (long-press one to delete it) and Spatial Sound.
- **Controls:** which modes the mode button cycles through, then every button assignment in four menus (Music, Ringing, In call, and Dialling, which Sound+ hides), with the left and right earbud side by side. Then the press-and-hold voice assistant and "Restore factory button layout".
- **Settings:** wearing detection and the settings that depend on it (dimmed while it's off), calls, general settings, automatic modes, notifications, shortcuts, experimental features, help links and about (with the firmware's update status).

## Connection

- The app opens straight to its tabs with the values it read last time (kept in `last_state.json` on the phone), then connects and fills them in. Only the very first launch shows the connect screen.
- The app connects when it opens. When it goes to the background it keeps the connection for 15 seconds, then releases it so Jabra Sound+ and the tile can use the control channel. A quick look at another app or a short screen-off doesn't reconnect.
- Once the app has read the earbuds, the tabs stay up through reconnects, showing the last values:
  - The device card's status line says "Connecting…" while it reconnects, and the card flashes green when it's back.
  - If the earbuds drop (both back in the case, or out of range), the card flashes red and stays tinted while the app retries for two minutes. If that fails, it shows why, with "Try again" and a link to Sound+'s app info.
  - On the other tabs, a small pill in the title bar shows the same thing; tap it to retry.
  - A change made while the app is reconnecting is applied once it's connected. One made while disconnected says so and isn't applied.
- Every change appears at once and is then confirmed by reading the value back. Changes made on the earbuds or by other apps come in through the earbuds' change notifications. Pull down (or the refresh button) to read everything again.
- If another app holds the control channel, the connect screen explains it and links to Sound+'s app info (Force stop).

## Earbud settings

- **Device:** name, firmware version, battery for left, right and case (live), and which earbud is offline.
- **Sound:** ANC / HearThrough / Off, HearThrough level (5 steps), Spatial Sound.
- **Equalizer:** the six Sound+ presets, plus a 5-band custom EQ (−4.5..+4.5 per band).
- **Wearing detection:** the master switch, plus auto-pause music, auto-answer calls, auto-mute mics and automatic HearThrough in mono mode.
- **Calls:** sidetone with its volume (6 steps), mute reminder, automatic call volume, in-call audio (Neutral / Bass / Treble).
- **Buttons:** the mode-button cycle, every assignment in the four menus, and restoring the factory layout (copies `CONFIG 9b` to `9a`).
- **Voice assistant** (press and hold): the phone's assistant or Google Assistant built in (`DEVICE 0d 4d 03`). Sound+ only offers this with LE Audio off; this app sets it either way (tested both ways with LE Audio on).
- **General:** what happens to music when HearThrough turns on, sleep timer, headset guidance (voice / tone / none), reduce wind.

## Alerts, automation and shortcuts

These work while the app is closed. Each is off until you turn it on in Settings; the alerts ask for notification access the first time.
- **Battery when connecting** (Settings → Notifications): when the earbuds connect, a short pop-up shows left, right and case battery (or "offline"), then goes away. Not shown while the app is open.
- **Low battery** (Settings → Notifications): a notification when an earbud or the case drops to 10 %, once until it has charged past 20 %. Readings come from the app, the tile, the pop-up, and a check every 15 minutes while the earbuds are connected.
- **Left behind** (Settings → Notifications; needs Find My): when the earbuds disconnect and, within about 10 minutes, the phone is more than ~120 m from where that happened (allowing for location accuracy) while they're still disconnected, you get "Did you leave your earbuds behind?" with a map link.
- **Firmware updates** (Settings → Notifications): once a week the app reads Jabra's public release-notes page and tells you if it lists a newer version than the earbuds have. Updating is still done in Sound+. Settings → About shows "the latest" or which version is available.
- **Automatic modes** (Settings → Automatic modes): switch the sound mode when you start sitting still, walking, running or travelling (Android's activity recognition), or arrive at a place you've saved (a 150 m geofence; a place wins over what you're doing). Defaults follow Sony's adaptive sound control: HearThrough on foot, ANC in a vehicle, sitting still left alone. It only acts while the earbuds are connected, at most every 30 seconds, with a short silent notification; a mode you pick yourself stays until the next change. Needs physical-activity access, and "Allow all the time" location for places.
- **Shortcuts:** long-press the app icon for ANC, HearThrough and Off. They switch without opening the app (with a short confirmation), can be pinned to the home screen, and can be used as app actions in automation apps such as One UI's Modes and Routines.
- **Saved EQs** (Sound tab → Save): name and keep your own curves; they appear as tiles on Sound and Home. Kept on the phone only, since the earbuds hold just the current curve.

## Phone-side features

These copy Sound+'s phone-only features and don't need the control channel.
- **Find my earbuds** (Home, and the connect screen): when any Jabra Bluetooth link drops, `BluetoothLinkReceiver` wakes up, even with the app closed, and saves the phone's location. Only the latest place per earbud link is kept, on the phone only; links that dropped within 100 m of each other count as one place. It shows "Close to …", the time, accuracy and a map link. Needs location "Allow all the time".
- **Play sound:** a loud repeating chirp sent only to the earbuds (never the phone speaker), at full media volume for up to a minute, then the volume is restored.
- **Battery health** (Home, under the gauges): the earbuds report only a percentage, so the app measures how fast each earbud drains while it's out of the case and compares that with Jabra's rating (up to 8 h with ANC on, 14 h off), weighted by how much of the time ANC was on. It flags one earbud draining clearly faster than the other. Readings come from the app, the tile, and, with "Track battery health" on, a check every 15 minutes while the earbuds are connected as audio. They're kept in `battery_log.csv` on the phone.
- **Quick Settings tile** (Settings → Shortcuts → Add): an "ANC" tile. Tapping it turns ANC on; tapping again goes back to Off or HearThrough. Its subtitle shows the battery ("L 85% · R 80%"; on One UI only the large tile shows it). It borrows the app's connection when there is one, otherwise connects for about two seconds and lets go.
- **Quick access during calls** (Settings → Calls): while a call is going on with the earbuds connected, an "On a call" notification opens the call settings on Home. Needs the phone-state and notification permissions.
- **Hearing test** (Settings → Experimental): pulsed tones rise in each ear until you tap. The thresholds are compared with typical hearing and turned into an EQ by the half-gain rule, capped at +4.5. App-side and uncalibrated: Sound+'s MySound isn't offered for this model.
- **Help:** user manual, FAQ, Jabra Support and product registration on jabra.com (links from Sound+'s own resources).

## Code

| Package | What's in it |
|---|---|
| `gnp/` | The protocol, with no Android code: framing (`Gnp.kt`) and every decoded setting (`Jabra.kt`) as data |
| `bt/` | The RFCOMM client, the shared connection (`EarbudsLink`), and reads that take several requests (`EarbudReads.kt`) |
| `EarbudsViewModel.kt` | The screen's state, the connection's lifecycle, and the `EarbudsActions` the screens call |
| `ui/` | One file per tab (`HomeTab`, `SoundTab`, `ControlsTab`, `SettingsTab`), the device card, shared widgets (`Components`), the EQ graph, artwork and preset tiles |
| `battery/`, `findmy/`, `calls/`, `tile/`, `hearing/` | The phone-side features: battery health and alerts, Find My and left-behind, call access, the tile, the hearing test |
| `automodes/`, `firmware/`, `shortcuts/`, `notify/`, `store/` | Automatic modes, the firmware check, the app-icon shortcuts, notification channels, and what's kept on the phone (last state, saved EQs) |

The artwork is hand-made vector drawables in `res/drawable`; the earbud outline was traced over a product photo.

## Build

Needs JDK 17+ and the Android SDK (platform 34). There are two builds of the same app:

| Build | For | Difference |
|---|---|---|
| `full` | GitHub releases | Automatic modes also follow what you're doing (walking, running, in a vehicle), through Google Play services |
| `fdroid` | F-Droid | Only free libraries: automatic modes work by place, through Android's own location service |

```
./gradlew testFullDebugUnitTest testFdroidDebugUnitTest
./gradlew assembleFullDebug
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

**Release builds** (`assembleFullRelease`, `assembleFdroidRelease`) are shrunk with R8. They're signed when `android/keystore.properties` exists; copy `keystore.properties.example` and follow the steps in it. The key never goes into git, and every update must be signed with the same key. F-Droid builds from source and signs with its own key.

The unit tests in `app/src/test` check the packet builder and parsers against real frames captured from Sound+, plus battery health, Find My, the hearing profile, the automatic-mode rules and the tile text.

## Known limits

- **Only one app can hold the control channel.** If Jabra Sound+ is running, its background service keeps RFCOMM channel 29 open and this app can't connect until Sound+ is force-stopped. Sound+ restarts its service on its own, so it may take the channel back later.
- The earbuds can be bonded under several addresses (an LE Audio bond goes to the left earbud). The app tries every paired Jabra device and keeps the one that answers GNP; on the test phone that was the right earbud's classic address.
- **Left behind** can't tell "put in the case" from "left on a table": the earbuds look the same to the phone either way. It only counts moving away within about 10 minutes of the disconnect, so it can still go off when you pack them away and leave at once.
- **Automatic modes** use Google Play services' activity recognition and geofencing; how quickly they react is up to Android (usually within a minute or two).
- The **firmware check** reads a web page, so it stops finding versions if Jabra changes the page's layout. It then simply stays quiet.
- "Offline" means the earbuds aren't linked to each other: the earbud is in the case or out of range. The earbuds don't say which, or whether it's charging.
- The selected EQ preset is recognised by matching the curve; a curve that matches no preset is shown as custom.
- The custom EQ's dB scale is assumed to be 0.01 dB per step; this hasn't been measured.
- Not yet tested on a device: saving a location when the earbuds disconnect, the locator sound by ear, the call notification during a real call, and whether press and hold starts an assistant over LE Audio.
- Product registration opens Jabra's web form. Sound+ registers through Jabra's own API instead.
- Not done: Spotify Tap (needs the Spotify app), voice guidance languages, firmware updates (use Sound+).

## History

- **0.13:** opens instantly with the last values; battery pop-up when connecting; low-battery and left-behind alerts; firmware-update notice; automatic modes by activity and place; ANC / HearThrough / Off shortcuts on the app icon; saved custom EQs.
- **0.12:** shows when one earbud is in the case or out of range; fixes left and right swapping after the earbuds change which one holds the phone link; half-circle battery gauges; the connection is kept through short app switches, and drops show on the device card instead of the full-screen connect page; code split into one file per tab and feature.
- **0.11:** artwork traced from the real earbud (launcher icon, earbud outlines, battery icons).
- **0.10:** Quick Settings tile, HearThrough level, sidetone volume, voice assistant, hidden Dialling menu, battery health, hearing test, redesign.
- **0.5:** Find My, call quick access, help links.
- **0.4:** tabs, brand theme and EQ graph.
- **0.3:** connects only while visible, and reconnects after drops.
