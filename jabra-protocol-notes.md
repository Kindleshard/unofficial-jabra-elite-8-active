# Jabra Sound+ protocol notes

Source: Jabra Sound+ `com.jabra.moments` 5.21.0.0.11448 (versionCode 521011448), pulled from a phone, installed from Google Play.

| File | Size | SHA-256 |
|---|---|---|
| base.apk | 82,161,936 | `40b864ff5dcd3781c3e8f72cad327f45f6d8b55cc09278441483f9fe6348914b` |
| split_config.arm64_v8a.apk | 29,099,133 | `8d604433547980218a8b83e5324b7777264fe9012d8de349b8ba03fbb18d070b` |
| split_config.en.apk | 143,769 | `6e255114451867293cdbb384c736f7a9d2a81c60d70e9fb0addad89a0a6a8d3a` |
| split_config.xxhdpi.apk | 31,163,496 | `19a3f902804e5ced4691f569c1bfe601c9d22a8ede40c96523f7fa04f6dd8b43` |

Everything below is from static decompilation (REA + JADX). Nothing has been checked against live traffic yet.

## App architecture

- `com.jabra.moments.*`: the Sound+ UI and app logic (Kotlin, R8-obfuscated).
- `com.jabra.sdk.*`: Jabra SDK layer.
- `com.gnnetcom.jabraservice.*`: GN's **Jabra Service**, which owns the device connection.
  - It is an exported service (`com.gnnetcom.jabraservice.JabraService`) with a Messenger/Handler IPC interface.
  - It wakes up on `ACL_CONNECTED` / `ACL_DISCONNECTED` (`BootBroadcastReceiver`).
  - Readable data classes: `Headset`, `EqualizerParameters`, `FirmwareInfo`, `OtaProgress`, `LanguageDefinition`.
  - The real logic is spread across obfuscated packages `Id.*`, `Ib.*` and `Ta.c`, which have not been traced yet.
- `com.jabra.jabracollaboration.communication.gnp.*` → obfuscated `O7.b`: the GNP packet class (below).
- The app has no class names containing `Rfcomm` or `Gatt`, so the transport is either hidden behind obfuscation or lives in the native libraries in `split_config.arm64_v8a.apk`. Not yet checked.

## GNP (GN Protocol) packet format, from `O7.b`

The class is used by the "JabraCollaboration" module, and its address and command names point at Jabra's video-conferencing devices (PanaCast). **The live capture below confirms that the Elite 8 Active uses this same framing**, so GNP is shared across GN devices.

Packet size: **5 to 62 bytes** (the parser rejects `size < 5 || size >= 63`).

| Offset | Content |
|---|---|
| 0 | header byte A (constructor arg 3, `b12`) |
| 1 | header byte B (constructor arg 1, `b10`); `toString` prints it as `PYTHON_DEVICE` |
| 2 | header byte C (constructor arg 2, `b11`); `toString` prints it as `REMOTE_CONTROL_APP` |
| 3 | `(type << 6) \| length`: top 2 bits = message type, low 6 bits = total packet length in bytes |
| 4 | command (0 if none) |
| 5 | sub-command (optional; when it's present the header is 6 bytes, otherwise 5) |
| 6… | payload |

`toString` prints byte 2 as a constant `42` placeholder. The exact roles of bytes 0 to 2 (destination, source or sequence) still need confirming from a capture.

### Message types (bits 7..6 of byte 3)

| Value | Name |
|---|---|
| 0 | EVENT |
| 1 | READ |
| 2 | WRITE |
| 3 | REPLY |

### Address enum (`O7.b$a`)

| Name | Value |
|---|---|
| REMOTE_CONTROL_APP | 0x09 |
| PYTHON_DEVICE | 0x01 |
| PYTHON_VIDEO_DEVICE | 0x02 |
| BLE0 | 0x0D |
| MOBILE | 0x09 |

### Commands (byte 4) and known sub-commands (byte 5)

| Cmd | Name | Known sub-commands |
|---|---|---|
| 2 (0x02) | GNP_IDENT_CMD | 1 IDENT_ID, 3 IDENT_VERSION, 17 IDENT_PID, 29 CONFIG_SKU_ID |
| 4 (0x04) | GNP_HWTEST_CMD | 34 HWTEST_HARDWARE_VERSION |
| 13 (0x0D) | GNP_DEVICE_CMD | 1 DEVICE_ATTACH_EVENT, 2 DEVICE_DETACH_EVENT |
| 18 (0x12) | GNP_STATUS_CMD | 23 CAMERA_STATUS |
| 19 (0x13) | GNP_CONFIG_CMD | **135 CONFIG_ANC**, 169 CONFIG_SECOND_STREAM |
| 26 (0x1A) | GNP_MMI_CMD | (buttons / user interface; none named) |
| 32 (0x20) | GNP_BODY_MONITOR_CMD | (none named) |
| 38 (0x26) | GNP_VIDEO_CMD | whiteboard zoom, video mode, pan/tilt, camera status… |
| 254 (0xFE) | GNP_NACK_CMD | error codes below |
| 255 (0xFF) | GNP_ACK_CMD | |

NACK error codes (sub-command of 0xFE): 0 NO_ERROR_CODE, 242 NO_TRANSPORT, 243 BAD_STATE, 244 TRANSMIT_FAILED, 245 ILLEGAL_ADDR, 246 UNKNOWN_CMD, 247 FILE_NOT_FOUND, 248 INTERNAL_ERROR, 249 REPLY_ERROR, 250 ILLEGAL_PARAM, 251 OUT_OF_SEQUENCE, 252 NO_SPACE, 253 ACCESS_DENIED, 254 NO_MORE_DATA, 255 UNKNOWN_SUB_CMD.

## Confirmed from live traffic (HCI snoop log, 2026-10-27)

Capture: Samsung bug report `FS/data/log/bt/btsnoop_hci.log`, 13,661 HCI packets. The Sound+ session is about 286 to 640 s in.

### Transport: classic Bluetooth SPP, not BLE

The earbuds' SDP records (`Jabra Elite 8 Active`, `AA:BB:CC:00:00:01`):

| RFCOMM ch | Service name | UUID | Notes |
|---|---|---|---|
| 1 | Hands-Free | 0x111E | calls |
| 19 | GSOUND_BT_CONTROL | `f8d1fbe4-7966-4334-8024-ff96c9330e15` | Google Assistant |
| 20 | GSOUND_BT_AUDIO | `81c2e72a-0591-443e-a1ff-05f988593351` | Google Assistant |
| 21 | Airoha_APP | Serial Port + `00000000-0000-0000-0099-aabbccddeeff` | the chip vendor's own channel (Airoha / MediaTek) |
| 23 | BTFASTPAIR | `df21fe2c-2515-4fdb-8886-f12c4d67927c` | Google Fast Pair |
| **29** | **SPP Dev** | **`00001101-0000-1000-8000-00805f9b34fb`** (standard Serial Port) | **Sound+ ↔ earbuds, GNP** |

Sound+ sent all 1,127 control frames over **RFCOMM channel 29**. Any app can open it with `createRfcommSocketToServiceRecord(UUID 0x1101)`, or by connecting to channel 29 directly.

### The earbuds speak GNP, and the framing is confirmed

All 1,127 frames on channel 29 parse as GNP, and every one's length byte matches its actual length. The header roles are now known:

| Offset | Role | Values seen |
|---|---|---|
| 0 | **destination** | `04` = earbuds, `09` = app |
| 1 | **source** | `09` = app (`MOBILE` / `REMOTE_CONTROL_APP` in the enum), `04` = earbuds |
| 2 | **sequence number** | The app increments it per request and the reply echoes it; events use `00` |
| 3 | type << 6 \| total length | e.g. `46` = READ, 6 bytes; `88` = WRITE, 8; `c9` = REPLY, 9; `09` = EVENT, 9 |
| 4 | command | |
| 5 | sub-command | |
| 6… | payload | |

An **ACK** to a WRITE is a REPLY with cmd `FF` whose payload echoes the request's first 5 bytes. Example: write `04 09 6c 88 13 be 01 02` → ack `09 04 6c ca ff 04 09 6c 88 13`.

A **NACK** is cmd `FE` with sub-command = error code. Only `FA` (ILLEGAL_PARAM) was seen, in reply to some parameterised CONFIG reads.

### Identity reads (cmd 0x02 IDENT, READ with no payload)

| Sub | Reply payload | Meaning |
|---|---|---|
| 00 | `14` + "Jabra Elite 8 Active" | product name, length-prefixed |
| 01 | `0c` + "AABBCC000001" | this earbud's Bluetooth address, length-prefixed |
| 03 | `05` + "4.6.0" | **firmware version** |
| 11 | `47 2e` | product ID (byte order not confirmed) |
| 28 + index | `0c` + address string | per-earbud info: index `04` → `AABBCC000001`, `05` → `AABBCC000002` (the other earbud), `15` → zeros |

### Sound mode: CONFIG (0x13) sub 0xBE ✅

| Operation | Bytes (app → earbuds) | Result |
|---|---|---|
| Read capabilities | `04 09 SS 47 13 be 00` | `07 04 00 21 01 00`: `07` looks like a bitmask of 3 supported modes |
| Read current mode | `04 09 SS 47 13 be 01` | 1 byte: the current mode |
| **Set mode** | `04 09 SS 88 13 be 01 MM` | ACK, then EVENT `0d 4c 09 01 xx`; reading it back returns `MM` |

Mode values, **confirmed** by setting each one from `jabra_gnp.py` on the PC and checking the result:

| `MM` | Mode | How it was confirmed |
|---|---|---|
| `01` | **Off** | Sound+ showed Off |
| `02` | **HearThrough** | Sound+ showed HearThrough, and outside sound was louder |
| `04` | **ANC** | faint ANC hiss and muffled surroundings; the only value left |

(An earlier guess based on the order of taps in the capture had these wrong. Don't trust tap order.)

Sound+ doesn't always update its screen when another device changes the mode. Closing and reopening it forces a fresh read.

`0x07`, returned by the capabilities read, = `01 | 02 | 04`: all three modes are supported.

**Slot `02` = HearThrough level** ✅ (decoded 2026-10-27). Sound+ shows a 5-step slider under HearThrough, only while HearThrough is selected. It's stored reversed:

| Slider step | 0 (hear the least) | 1 | 2 | 3 | 4 (hear the most) |
|---|---|---|---|---|---|
| `BE 02` | `04` | `03` | `02` | `01` | `00` |

Read `13 be 02` → level. Write `13 be 02 LL` → ACK (tested from the PC: `03`, then `00`). Writing it doesn't change the mode (slot `01`).

Other slots: `00` = capabilities (`07 04 00 21 01 00`), `03` = `00` and `04` = `01` (unchanged by the slider; unknown), `05` = ILLEGAL_PARAM.

### Equalizer: CONFIG (0x13) sub 0x7D, enable flag sub 0x7E ✅ (structure)

Second capture, 2026-10-27, while the user cycled through every EQ preset in Sound+ (sliders not touched).

The app does this for each preset:
1. WRITE `13 7e 01`: probably "EQ on" (ACK).
2. READ `13 7d 00`: current EQ.
3. **WRITE `13 7d <34 bytes>`: the whole 5-band EQ at once** (ACK, and reading it back returns the new gains).
4. After choosing the all-zero (Neutral) preset, the app also wrote `13 7e 00`, so `7E` = EQ enabled looks right.
5. `7E` is **write-only**: READ `13 7e` gets NACK `FD` (ACCESS_DENIED), tested from the PC.

**Write payload** (34 bytes, so the whole frame is 40 bytes):
```
05 00 00 00 | [freq u16][gain s16][Q u16] × 5        (big-endian)
```
**Read-back payload**: header `05 00 00 1e`, and each band has one extra type byte after Q (`18` for bands 1–3, `28` for band 4, none for band 5). These may be filter types (peaking or shelf). Not confirmed.

Fixed per-band values (the same in every preset):

| Band | Freq field | Q field | Type (read-back) |
|---|---|---|---|
| 1 | 180 | 1730 | 0x18 |
| 2 | 750 | 1654 | 0x18 |
| 3 | 3000 | 1577 | 0x18 |
| 4 | 12000 | 1597 | 0x28 |
| 5 | 22800 | 2088 | – |

The freq field is probably Hz for bands 1–4. 22800 is above human hearing, so band 5's field may mean something else. Q looks like ×1000 (1.73 and so on).

Gain per band (signed 16-bit; the unit is probably 0.01 dB, so 270 = +2.7 dB, but a slider test is needed to confirm it):

| Preset | b1 (180) | b2 (750) | b3 (3000) | b4 (12000) | b5 (22800) | How it was confirmed |
|---|---|---|---|---|---|---|
| **Neutral** | 0 | 0 | 0 | 0 | 0 | by elimination; the app also writes `7e 00` after it |
| **Bass boost** | +270 | −118 | 0 | 0 | 0 | by elimination |
| **Smooth** | 0 | +118 | 0 | −118 | 0 | ✅ tapped in Sound+, read from the PC (the earbuds were reset to Neutral first) |
| **Speech** | −270 | 0 | 0 | +61 | −120 | ✅ same method |
| **Treble boost** | −90 | −118 | 0 | +118 | +240 | ✅ same method |
| **Energize** | +180 | 0 | −118 | −61 | +180 | ✅ Sound+ showed it selected while the PC read this curve |

Sound+ has six presets, and the capture has six distinct curves. Four were confirmed directly; the remaining two (the all-zero curve and the bass-heavy curve) can only be Neutral and Bass boost.

Method: reset the earbuds to Neutral from the PC (`jabra_gnp.py eq neutral`), tap one preset in Sound+, then read the curve back from the PC (`jabra_gnp.py eq`). Tapping a preset that Sound+ already has selected may send nothing, so tap a different one first.

**The earbuds store only the curve, not a preset ID.** Sound+ keeps the selected preset name on the phone. When the PC wrote `curve4`, the earbuds held it (confirmed by reading it back, even after Sound+ was closed and reopened), but Sound+ still showed "Energize". So the name shown in Sound+ can't confirm a curve written by another device. Instead, tap a preset in Sound+ and read the curve from the PC.

To confirm the gain unit and range: move a single slider to each end, as in the original plan.

### Battery: STATUS (0x12) sub 0x02 ✅

READ `04 09 SS 46 12 02` (no payload). The earbuds also send the same message as an **EVENT** whenever an earbud goes in or out of the case.

```
80 PP 00 00 | 02 | 15 00 CC | 05 00 OO
```

`80 PP 00 00`, then a count (`02`) and that many `[id][flags][%]` entries.

| Bytes | Meaning |
|---|---|
| PP (byte 1) | **the earbud that holds the phone link** ✅ (usually the right one, `…0001`) |
| `15 00 CC` | **case %** ✅ (`15` = the case's ID, as in IDENT `28`) |
| `05 00 OO` | **the other earbud %** ✅. `00` = not reported (e.g. it's been in the case since the link opened) |

Confirmed against Sound+ twice with the right earbud holding the link: (1) left 85, right 85, case 90; (2) left 85, right 80 → PP = 80, OO = 85.

So the earbud with the connection address `AA:BB:CC:00:00:01` (IDENT `28 04`) is the **right** one, and `AA:BB:CC:00:00:02` (IDENT `28 05`) is the **left** one.

**The order is by role, not by side (2026-10-28).** In `capture4`, the right earbud came out of the case at 100 % while the left (75 %) held the link: the report read `80 4b … 05 00 64`. When the right took the link over, it read `80 64 … 05 00 4b`. So the first level belongs to whichever earbud holds the link, which the link event below names. The app reads that event first and maps the levels to left and right; before 0.12 it assumed the right earbud and could show them swapped.

The message carries **no charging or in-case flag**. An earbud in the case keeps reporting its last level, so the link event is the way to tell it's offline. When both are in the case, the earbuds disconnect entirely (RFCOMM connect times out).

### Earbud link: EVENT DEVICE (0x0D) sub 0x4C, `07 OO PP` ✅

| Byte | Meaning |
|---|---|
| `07` | event type: the link between the two earbuds |
| OO | `01` while the earbuds are linked to each other, `00` while one is offline (in the case or out of range) |
| PP | the earbud holding the phone link: `01` = right, `00` = left |

Evidence (2026-10-28):
- Jabra's native library (`libjabralib.so` in Sound+) logs it as `DeviceEvent DEVICE_LINK_CONNECTION LINK_OPEN=%s, PRIMARY=%s` with `EARBUD_LS` / `EARBUD_RS`. Sound+'s Java side (`JabraDeviceEarbudConnectionProxy`) turns it into "both earbuds connected", "only left" or "only right".
- From the PC: `07 01 01` when the left earbud was taken out, `07 00 01` when it was put back, with the right one holding the link (`listen.log`).
- `capture4`: `07 00 00` while the right earbud was in the case (left holding the link), `07 01 00` when it came out, `07 01 01` once the right took the link over. The battery report swapped order at the same moments.

**Getting the current state:** there's no read for it, but the earbuds send it every time the event subscription is written (below). Sound+ writes the subscription several times at start-up, and each write is followed by a `07` event within ~50 ms.

### Event subscription: DEVICE (0x0D) sub 0x4C, read/write ✅

| Operation | Bytes | Reply |
|---|---|---|
| Read | `0d 4c` | `00 00 02 96` (a 32-bit mask) |
| Write | `0d 4c MM MM MM MM` | ACK, then EVENT `0d 4c 07 OO PP` |

Sound+ reads the mask, then writes `00 00 00 02`, `…82`, `…86`, `…96`, `00 00 02 96` and later `…02 86`, `…02 82`, `…02 92`. Bit n seems to subscribe to event type n: the offered mask `0x296` = types 1, 2, 4 (setting changed), 7 (link) and 9 (sound mode). Types 1 and 2 are probably the head-status and jack-status events the native library names; unconfirmed. The app writes back the current mask with bits 4, 7 and 9 set, which Sound+ always includes.

### Reduce wind: CONFIG (0x13) sub 0xEF, slot 00 ✅

| Operation | Bytes (app → earbuds) | Reply |
|---|---|---|
| Read | `04 09 SS 47 13 ef 00` | `00 VV` (the slot is echoed back) |
| Write | `04 09 SS 88 13 ef 00 VV` | ACK, then EVENT `0d 4c 04 ef` |

`VV`: `00` = off, `01` = on. Confirmed 2026-10-27: Sound+ "Reduce wind" was switched on, the log showed write `00 01`, and the PC read back `00 01`.

A read **without** the slot byte gets **no reply at all** (no NACK). The settings scanner (`jabra_scan.py`) now also retries silent settings with slot `00` and `01`.

### Toggles mapped from the 4th capture (2026-10-27)

The user changed four Sound+ settings in a known order. Each produced exactly one WRITE, in the same order, and each was read back from the PC with the written value.

| Sound+ setting | Write | Off | On | Notes |
|---|---|---|---|---|
| **In-ear detection** (auto-pause when you take an earbud out) ✅ | `13 92 VV` | `00` | `01` | read without a slot |
| **Automatic HearThrough for mono mode** | `13 f0 VV` | `00` | `01` | read without a slot |
| **Sidetone** (hearing your own voice on calls) | `13 7c AA BB` | `01 LL` | `00 LL` | byte 0: `01` = off, `00` = on. Byte 1 = **sidetone volume** ✅, signed: Sound+'s 6-step slider (shown only while sidetone is on) gave `f7` −9, `fa` −6, `fd` −3, `00` 0 (default), `03` +3, `06` +6, probably dB. Turning sidetone on or off must keep byte 1. The slot byte is ignored on read |
| **Reduce wind** | `13 ef 00 VV` | `00 00` | `00 01` | the slot `00` is required (see above) |

In-ear detection (`92`) and auto HearThrough (`f0`) both went `00 → 01`. **Confirmed separately:** the user then turned only in-ear detection off in Sound+, and only `92` changed (`01 → 00`); `f0` stayed `01`.

### Button controls (MyControls): CONFIG (0x13) sub 0x9A ✅ (structure)

From the 5th capture (2026-10-27). That log rotated mid-connection, so Wireshark couldn't label RFCOMM; it had to be forced with `-d btl2cap.cid==0x0042,btrfcomm -d btl2cap.cid==0x006a,btrfcomm`. It was then read from the PC.

**Read** one menu: `04 09 SS 47 13 9a MM` → 6 records of 4 bytes, `[menu][earbud][gesture][action]`:

| Field | Values |
|---|---|
| menu | `00` = Music and media ✅, `01` = Incoming calls ✅, `02` = Active calls ✅, `03` = a 4th context not shown in Sound+ (only right double = `09` End call; maybe an outgoing or held call) |
| earbud | `00` = left, `01` = right |
| gesture | `00` = single press, `01` = double press, `02` = **triple press** ✅. Press and hold is **not** in this map; Sound+ shows it as "Use Google Assistant", and the voice assistant is handled separately |

**Write** one gesture: `04 09 SS 8a 13 9a MM EE GG AA` (a single record) → ACK. Sound+ wrote `00 00 01 01` (left double → `01`), then `00 01 01 05`, then `00 01 01 01` (right double → `05`, then `01`).

The `9a` value without a menu byte (`09 04 xx xx`) is meaningless. That's why it looked as if it "changed on its own" in the scans.

Action codes:

Music-menu codes were confirmed **automatically**: each option was tapped in Sound+ over adb, and the code was then read from the PC (right triple press, right double press and left double press were tested, and each was restored).

| Code | Action (Sound+ label) |
|---|---|
| `00` | **None** ✅ |
| `01` | **Play/pause** ✅ |
| `02` | **Next track** ✅ |
| `03` | **Restart track** ✅ |
| `05` | **HearThrough/ANC/Off** ✅ (left single's default) |
| `04` | not offered on this phone. It was left double's value before the test; probably **Spotify Tap**, since the app mentions "Double press the left earbud to get Spotify Tap". Unconfirmed |
| `06` | **Reject call** ✅ (incoming) |
| `07` | **Answer call** ✅ (incoming) |
| `08` | **Answer call and put an active call on hold** ✅ (incoming) |
| `09` | **End call** ✅ (active) |
| `0a` | **Hold active call** ✅ (active) |
| `0b` | **Mute/unmute microphone** ✅ (shown as "Mute/unmute active call" in the active menu) |
| `0c` | **Sidetone on/off** ✅ (incoming and active) |

Options Sound+ offers in each menu (the same on both earbuds, for single, double and triple press):
- **Music:** None, Play/pause, Next track, Restart track, HearThrough/ANC/Off
- **Incoming:** None, Reject, Answer, Answer + hold, Mute/unmute, Sidetone on/off
- **Active:** None, End call, Hold, Mute/unmute, Sidetone on/off

How these were confirmed (2026-10-27, automated over adb): each option was tapped in Sound+ and the code read from the PC. Music used right triple, right double and left double; the call menus used left single. Each was then restored, and afterwards all four menus' maps matched the pre-test baseline byte for byte.

**Sound+ caches its button labels.** Writing a code from the PC changes the earbuds (confirmed by reading it back), but Sound+ keeps showing the old label even after the screen is reopened. Name codes by tapping in Sound+ and reading from the PC, not the other way round.

Where to find it in Sound+: home → settings cog (top right) → **Personalize** → **MyControls** → Music and media / Incoming calls / Active calls.

Current map read from the PC (after the test):

| Gesture | Music (00) | Incoming (01) | Active (02) | Menu 03 |
|---|---|---|---|---|
| L single | 05 | 07 | 0b | 00 |
| R single | 01 | 07 | 0b | 00 |
| L double | 01 | 06 | 09 | 00 |
| R double | 01 | 06 | 09 | 09 |
| L hold | 00 | 00 | 00 | 00 |
| R hold | 03 | 00 | 00 | 00 |

Also seen in this capture: READ `DEVICE 0d 4d 03` → `11`, polled 15 times. Not decoded.

### More settings, decoded automatically (2026-10-27)

Method (`tools/decode_features.py`): Sound+ was driven over adb, flipping one setting at a time; every readable setting was snapshotted from the PC before and after; then the setting was restored and a third snapshot confirmed the restore. Every one of these showed a readable change, so no HCI log was needed. Values are what Sound+ wrote; reads need no slot byte unless shown.

| Feature (Sound+ label) | CONFIG sub | Values |
|---|---|---|
| Spatial Sound for media (Dolby) | `e4` | `04 04 01` off, `05 05 01` on. Sound+ writes `e4 04 04 00` when screens open; the 3rd byte's meaning is unknown |
| Auto-pause music (needs wearing detection) | `8e` | `00 00` off, `00 01` on |
| When HearThrough is turned on | `76` | `01` continue playing audio, `02` mute audio |
| Auto-answer calls (needs wearing detection) | `91` | `01 00` off, `01 01` on |
| Auto-mute microphones (needs wearing detection) | `8f` | `00 00` off, `00 01` on |
| Mute reminder | `1e` | `00` off, `14` on (0x14 = 20, probably the interval in seconds) |
| Automatic volume adjustment (calls) | `f1` | `01` on, `00` off |
| In-call audio EQ | `15` | `00` Neutral, `01` Bass, `02` Treble |
| Sound-mode cycle for the button | `bf` | list `[count][mode…]` with modes `00` Off, `01` HearThrough, `02` ANC: `03 00 01 02` = HearThrough/ANC/Off, `02 00 01` = HearThrough/Off, `02 01 02` = HearThrough/ANC. **Note: these mode codes differ from `be`** |
| Sleep mode | `90` | in 5-minute units: `03` 15 min, `06` 30 min, `0c` 1 h, `18` 2 h, `00` off |
| Headset guidance | `3a` | `01` voice, `00` tone, `02` none |
| Headset name | `56` | plain ASCII, no length byte. Read `13 56` → name; write `13 56 <ascii>` → ACK (tested with "Elite Test", then restored) |

**Custom EQ range:** dragging band 1 of Sound+'s (hidden) "Music equalizer" widget to its limits wrote gains of **+450** and **−450**. The widget also re-quantises the other bands (118 → 115). So the per-band gain range is −450..+450, probably ±4.5 dB in 0.01 dB steps (the unit isn't verified acoustically).

**Voice assistant choice** (Google Assistant / Android default): Sound+ says "This setting is disabled when the headset is using Bluetooth LE Audio". It was decoded with LE Audio off (see "Voice assistant" below), and writing it works with LE Audio on too.

**Spotify Tap:** Sound+ shows it as "Not installed". Its description ("Double press the left earbud to get Spotify playing right away") and the factory button layout (`9b`: left double = `04`, see below) make action `04` = Spotify Tap very likely.

### The values that aren't settings (investigated 2026-10-27)

Each was read 15 times from the PC with 5 different slot bytes (`tools/probe_unknown.py`): every read answered, with the same value, whatever the slot. The earlier "unreliable" result came from the scanner's short 0.4 s timeout. `tools/capture_context.py` shows when Sound+ reads them in the captures.

| Sub | Value | What it is | Evidence |
|---|---|---|---|
| `08` | `09 04` | the two GNP addresses on this link (app `09`, earbuds `04`) | read in Sound+'s start-up sequence, next to IDENT `08` (`01 09 04`) |
| `10` | `09 04 95 de 13 10 05 00 00` | link plumbing: the same addresses, this command's own code `13 10`, and `05` (the left earbud's ID). Probably about relaying messages to the left earbud; details unknown | read once at start-up, right after IDENT `28 05`/`28 15` |
| `1d` | "100-99160901-99" | the earbuds' hardware part number. Sound+ lists the retail product as 100-99160701-98 | read twice at start-up, right after IDENT |
| `ad` | `00 01 02` | the button menus an app should show | Sound+ reads `ad`, then menus `00`, `01`, `02` (never `03`) when MyControls opens |
| `e5` | `00` | written `00` by Sound+ just before it changes the voice assistant (see below). Possibly Spotify Tap on/off, since Spotify Tap replaces the voice assistant; unconfirmed | Sound+ reads it after `e4`, and writes it when switching the assistant |
| `81` | `01` | unknown internal flag | Sound+ never reads it in any capture |

### Voice assistant (decoded 2026-10-28)

The press-and-hold assistant isn't a CONFIG setting. It's **DEVICE `0d 4d` with slot `03`**, which is why no CONFIG scan found it:

| Value | Assistant (Sound+ label) |
|---|---|
| `00` | Android default: the phone's own assistant |
| `11` | Google Assistant built in ("bisto"). Factory/original value on this unit; Sound+ shows it as "Not activated" until Google Assistant is set up on the phone |

- **How it was found:** LE Audio was switched off on the phone, which unlocks the setting in Sound+. Sound+ was then switched from Google Assistant to Android default while the HCI snoop log recorded (`capture6/`). It sent `WRITE 13 e5 00` → ACK, `READ 0d 4d 03` → `11`, `WRITE 0d 4d 03 00` → ACK, `READ 0d 4d 06` → `00`.
- **Switching back in Sound+:** Sound+ won't select Google Assistant again until it's activated on the phone, and it sends nothing. Writing `0d 4d 03 11` from the PC restored it (ACK, reads back `11`).
- **`0d 4d 06`** reads `00`. It's probably the "Hey Google" wake word state (`bistoConfiguration.wakewordSupported` is true for this model); unconfirmed.
- **Polling:** Sound+ polls `0d 4d 03` and `0d 4d 06` whenever MyControls or the assistant page is open.
- **`CONFIG 81`** (`01`) turned out not to be the assistant. Writes to it are ACKed but ignored.

**Device IDs** (IDENT `28`): `28` → `04 05 15`. `28 04` = "AABBCC000001" (right earbud, which is also GNP address `04`), `28 05` = "AABBCC000002" (left earbud), `28 15` = "000000000000" (the case, which has no Bluetooth address). Battery groups use the same IDs.

**Button tables:** `9a MM` is the live layout and `9b MM` is the **factory default**. They are identical except where the user changed left double (`04` → `01`) and right double (`02` → `01`) in Music. Defaults for Music: left single = mode, right single = play/pause, left double = `04` (Spotify Tap), right double = next, right triple = restart. Menu `03` is the same in both tables: only right double = `09` End call. It isn't in the `ad` list, which is why Sound+ hides it. It is probably the outgoing-call (dialling) context, where hanging up is the only useful action (unconfirmed).

### Readable settings found by a full read-only scan (2026-10-27, no slot byte)

`CONFIG` subs that answered (meaning unknown unless stated): `08` (`09 04`), `10` (`09 04 95 de 13 10 05 00 00`), `15` (`00`), `1d` ("100-99160901-99", probably a part number), `1e` (`00`), `3a` (`01`), `56` (Bluetooth name, "Jabra Elite 8 Active"), `76` (`01`), `7c` (`00 00`), `7d` (EQ), `81` (`01`), `8e`/`8f` (`00 00`), `90` (`06`), `91` (`01 00`), `92` (`00`), `9a`/`9b` (button tables, which need a menu byte; see above), `ad` (`00 01 02`), `be` (sound mode), `bf` (`03 00 01 02`), `e4` (`04 04 01`), `e5` (`00`), `f0` (`00`), `f1` (`01`). `STATUS 02` = battery, and `STATUS e4` mirrors `CONFIG e4`.

A second full scan, retrying every silent sub with slot `00` and `01`, found only one more setting: `CONFIG ef [00]` (Reduce wind). Other Sound+ toggles (auto-pause, sidetone, auto HearThrough) are therefore not readable this way. They need a log capture of the write.

Write-only or refused: `CONFIG 7e` (ACCESS_DENIED). UNKNOWN_SUB_CMD: `CONFIG 83, 88, 93, 9c, 9d, db`, `STATUS 00`. UNKNOWN_CMD: `CONFIG 12, 7f`.

### Other observations (not yet decoded)

- **EVENT `0d 4c 04 <sub>`**: "setting `<sub>` changed". Seen as `04 be` after mode writes and `04 e4` after `13 e4` writes.
- **CONFIG `13 e4`**: the app writes `04 04 00` each time the screen opens (`7` writes). Purpose unknown.
- **DEVICE `0d 4c` writes** `00 00 0x yy`: the event subscription; see "Event subscription" above (decoded 2026-10-28).
- **CONFIG `13 9a`** is polled about 90 times: the button layout, decoded since (see MyControls above).
- Cmds `0x0f`, `0x22` and `0xfc` show up as reads; `fc 00` looks like a keep-alive.

## Firmware updates (from the Sound+ APK and Jabra's release notes)

### How Sound+ checks and downloads firmware

Code: `com.jabra.moments.jabralib.headset.http.FirmwareClientImpl` → `J7.b` → Retrofit interface `L7.c`.

| Purpose | Request |
|---|---|
| Latest version for a product | `GET https://firmware-api.jabra.com/v4/Firmware/{pId}` with Jabra's vendor ID (`0B0E`); the client method is `getLatestFirmwareInfoForPid(vendorId, pid)` |
| Download a version | `GET https://firmware-api.jabra.com/v4/Download/{pId}/{version}` |
| Assets | `…/v4/assets/{downloadUrl}` |

- The response fields include `version`, `releaseDate`, `releaseLevel`, `downloadUrl`, `releaseNotesUrl`, `productName` and `headsetLanguages`.
- Strings in the same code include `esn` (serial number), `currentFirmware` and an `Ocp-Apim-Subscription-Key` header (Azure API Management). Which calls need them is not established.
- `chooseServer(testing)` switches between a production and a test server. The APK also contains Jabra QA/dev hosts (`*-qa-*`, `*-dev*`), which should be ignored.
- **No API call was made.** The public release notes answered the version question.
- The product ID for this device is probably the IDENT `0x11` reply (`47 2e`); the byte order isn't confirmed.

### Latest firmware: 4.6.0 is current

Jabra's release notes (jabra.com, Elite 8 Active, read 2026-10-27):

| Version | Date | Main change |
|---|---|---|
| **4.6.0** | 2026-01-23 | security fix (CVE-2025-36911) ← **these earbuds run this** |
| 4.5.0 | 2025-09-17 | security fixes (CVE-2025-20700, -20701, -20702) |
| 4.4.4 | 2025-05-27 | **LE Audio (LC3) and Auracast**: needs Android 15+; Galaxy S25 is listed as supported; you have to **delete the pairing and re-pair** after enabling LE Audio on the phone |
| 3.1.6 | 2024-03-26 | ANC on calls, wind-noise and call-mic improvements |
| 2.1.8 | 2024-01-09 | Bluetooth name, Auto HearThrough, wind and volume toggles, an EQ bug fix |

**There's no newer firmware.** Bugs fixed only in firmware can't be fixed by an app.

**The app's firmware notice (0.13)** reads this release-notes page (`https://www.jabra.com/support/release-notes/release-note-jabra-elite-8-active`) once a week instead of the firmware API, which needs Sound+'s `Ocp-Apim-Subscription-Key`. The page redirects to a regional copy and sets cookies on the way; each version is its own element (`>4.6.0<`). On 2026-10-28 it listed 4.6.0 down to 1.2.4.

## Still open

1. Confirm the ANC / HearThrough / Off byte values by sending them from our own code.
2. **EQ**: the structure and all six presets are decoded and confirmed (above). Still to do: confirm the gain unit and range (slider test) and band 5's frequency field.
3. ~~Battery~~: done (left, right and case confirmed).
4. ~~Button mapping~~: done (CONFIG 9A). Still open: action `04` (Spotify Tap?), menu `03`, and press-and-hold / voice assistant settings.
