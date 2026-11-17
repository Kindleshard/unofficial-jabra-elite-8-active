# Sound+ feature map: Jabra Elite 8 Active

All Sound+ 5.21 screens for this device were walked over adb on 2026-10-27 (read-only; nothing was changed). Each feature is marked as decoded, still to decode, or phone-only.

Path legend: **Home** = Sound+ home screen; **⚙** = settings cog (top right) → headset page.

## Already decoded (in the app since v0.1)

| Feature | Where in Sound+ | GNP |
|---|---|---|
| Battery L / R / case | Home | `STATUS 02` (ordered by role: see the link event below) |
| Firmware version, product name | ⚙ | `IDENT 03`, `IDENT 00` |
| Sound mode ANC / HearThrough / Off | Home → Sound Modes | `CONFIG be [01]` |
| Music presets (6) | Home → Music presets | `CONFIG 7d` + `7e` |
| Wind noise reduction | ⚙ → Personalize | `CONFIG ef [00]` |
| Wearing (in-ear) detection | ⚙ → Headset settings → General | `CONFIG 92` |
| Automatic HearThrough for mono mode | ⚙ → Headset settings → General | `CONFIG f0` |
| Sidetone | ⚙ → Headset settings → Call experience | `CONFIG 7c` |
| MyControls (3 menus × 2 earbuds × 3 gestures) | ⚙ → Personalize → MyControls | `CONFIG 9a` |

## Decoded on 2026-10-27 (all in the app now)

Each was flipped in Sound+ over adb, diffed from the PC and restored. Details are in `jabra-protocol-notes.md`.

| Feature | Where | GNP | Values |
|---|---|---|---|
| Spatial Sound for media (Dolby) | Home | `CONFIG e4` | `04 04 01` off / `05 05 01` on |
| Custom EQ (5 draggable bands) | Home → Edit widgets → Music equalizer | `CONFIG 7d` | gain −450..+450 per band |
| Sound-mode cycle for the button | ⚙ → General | `CONFIG bf` | `03 00 01 02` all / `02 00 01` HT+Off / `02 01 02` HT+ANC |
| Auto-pause music | ⚙ → Audio experience | `CONFIG 8e` | `00 00` / `00 01` |
| When HearThrough is turned on | ⚙ → Audio experience | `CONFIG 76` | `01` keep playing / `02` mute |
| Auto-answer calls | ⚙ → Call experience | `CONFIG 91` | `01 00` / `01 01` |
| Auto-mute microphones | ⚙ → Call experience | `CONFIG 8f` | `00 00` / `00 01` |
| Mute reminder | ⚙ → Call experience | `CONFIG 1e` | `00` / `14` |
| Automatic volume adjustment | ⚙ → Call experience | `CONFIG f1` | `00` / `01` |
| In-call audio EQ | ⚙ → Call experience | `CONFIG 15` | `00` Neutral / `01` Bass / `02` Treble |
| Sleep mode | ⚙ → General | `CONFIG 90` | 5-min units; `00` = off |
| Headset guidance | ⚙ → General | `CONFIG 3a` | `01` voice / `00` tone / `02` none |
| Headset name | ⚙ → Personalize | `CONFIG 56` | plain ASCII, read and write |
| HearThrough level (5-step slider, shown only in HearThrough) | Home → Sound Modes | `CONFIG be 02` | `00` most … `04` least |
| Sidetone volume (6-step slider, shown only while sidetone is on) | ⚙ → Call experience | `CONFIG 7c` byte 1 | signed −9..+6 in steps of 3 |

## Second sweep (2026-10-27): controls that only appear in some states

The first walk saw each screen once, and it only recorded text, so it missed two unlabeled sliders that only show up in certain states. `tools/walk_soundplus.py` now records sliders, switches and radio buttons too.

Screens were re-walked:
- Home with the sound mode on ANC, HearThrough and Off
- Home with Spatial Sound on
- Call experience with sidetone on and with mute reminder on
- Audio experience, Call experience and General with wearing detection on
- Personalize, Edit widgets, Voice Assistant, and the Discover and About tabs

Only the two sliders above were new. Personalized ANC, MyFit and MySound exist in Sound+'s resources but aren't offered for this model.

## What Sound+ enables for this model

Sound+ ships one definition file per product (`assets/Device/Definition/JSON/11847_pelican.json` in the APK). For the Elite 8 Active (product ID 11847) it says:

| Feature flag | Value |
|---|---|
| `isAdjustableAmbienceModeSupported` (HearThrough level) | true |
| `isAdjustableValueANCSupported` (ANC strength) | false |
| `isFFANCSupported` (Personalized ANC) | false |
| `isSealingTestSupported` (MyFit) | false |
| `mySound.isSupported` | false |
| `isSoundscapeSupported`, `isWindModeSupported`, `isAutomaticVolumeAdjustmentSupported`, `isHearThroughForMonoSupported`, `isMonoEarbudSupported`, `isInCallSettingsSupported` | true |
| `voiceAssistant.supportedVoiceAssistants` | "siri" (the phone's own assistant) and "bisto" (Google Assistant built in, with wake word "Hey Google" supported) |
| `spatialSound` | media only; no calls, no HRTF toggle |
| `forcedSoundModes.call` | "anc" (ANC is always on during calls) |
| `leAudio.firstLEAudioFirmware` | 4.2.0 |

It also maps part numbers to colours. `100-99160901-99` (as read from `CONFIG 1d`) is **navy**, as is `100-99160701-98`. The black variants are `…0700-98`/`…0900-99`, caramel `…0702-98`/`…0902-99`, and dark grey `…0703-98`/`…0903-99`.

**Voice assistant: decoded 2026-10-28.** It's `DEVICE 0d 4d 03`: `00` = phone's assistant ("Android default"), `11` = Google Assistant built in. It was found by switching LE Audio off (which unlocks it in Sound+), flipping it there and reading the Bluetooth log. Details are in `jabra-protocol-notes.md`. The app has it as "Press and hold" on the Controls tab. `CONFIG 81` (an earlier suspect) accepts writes but ignores them.

## Decoded on 2026-10-28

| Feature | Where in Sound+ | GNP |
|---|---|---|
| Voice assistant for press and hold | ⚙ → Voice Assistant (only with LE Audio off) | `DEVICE 0d 4d 03`: `00` phone's assistant / `11` Google Assistant |
| Which earbud is offline (in the case or out of range) | not shown as such; Sound+ tracks it as "only left/right earbud connected" | event `DEVICE 4c 07 OO PP`, sent whenever the event subscription (`DEVICE 4c`) is written |

## Not decoded

| Feature | Why |
|---|---|
| Spotify Tap | needs the Spotify app; button action `04` is probably it |
| Voice guidance language | a downloadable firmware language pack; out of scope |

The other readable values aren't user settings: `08`/`10` are link plumbing, `1d` is the part number, `ad` lists the button menus to show, and `e5` is probably Spatial Sound related. Only `81` (`01`) is still unknown. `9b` is the factory button layout. See "The values that aren't settings" in `jabra-protocol-notes.md`.

## Phone-only features (nothing on the earbuds)

| Feature | Notes | This app |
|---|---|---|
| Find My Jabra | saves the phone's location when a link disconnects (one per earbud), shows "Close to:" with a map, plus "Locate by sound" | v0.5: Find my earbuds + Play sound |
| Soundscape | the phone plays background sounds (e.g. "Waterfall"), a hidden widget | no |
| Quick access to call settings | needs READ_PHONE_STATE; an "Ongoing call" notification opens a "During a call" panel | v0.5 |
| Register / manuals / FAQ / support | registration uses Jabra's API; the others are links (`device_faq_link_pelican` etc.) | v0.5: links (registration through jabra.com) |
| Firmware update | out of scope (see notes: 4.6.0 is current) | no |

## How to decode the rest

The quickest method, fully automatic over adb:
1. Snapshot the readable settings from the PC (`jabra_scan.py`).
2. Flip one Sound+ setting by tapping it on the phone.
3. Snapshot again and compare. If nothing readable changes, pull the Bluetooth log to see the write (`CONFIG ef` was found this way).
4. Set the setting back.
