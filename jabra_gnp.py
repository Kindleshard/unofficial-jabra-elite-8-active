"""Control a Jabra Elite 8 Active over GN's GNP protocol on Bluetooth RFCOMM.

The earbuds must be paired with this PC first (Windows Settings > Bluetooth).
Only one program per PC can hold the control channel: quit the Windows tray app
(the tray app in windows/) before running this.

Usage:
  py jabra_gnp.py info                       product name and firmware version
  py jabra_gnp.py mode                       show the current sound mode
  py jabra_gnp.py mode anc|hearthrough|off   set the sound mode
  py jabra_gnp.py cycle                      ANC -> HearThrough -> Off, then restore
  py jabra_gnp.py battery                    show earbud and case battery levels
  py jabra_gnp.py buttons                    show every button assignment (MyControls)
  py jabra_gnp.py setting                    show all known on/off settings
  py jabra_gnp.py setting NAME on|off        change one (reducewind, ineardetection, autohearthrough, sidetone)
  py jabra_gnp.py eq                         show the current 5-band EQ
  py jabra_gnp.py eq PRESET                  apply a preset curve captured from Sound+

Options:
  --addr XX:XX:XX:XX:XX:XX   earbuds' Bluetooth address (default: the paired Jabra device, or JABRA_ADDR)
  --channel N                RFCOMM channel (29 = "SPP Dev" on firmware 4.6.0)
  -v                         print every frame sent and received

Protocol notes: jabra-protocol-notes.md
"""
import argparse
import ctypes
import os
import socket
import sys
import time


def find_earbuds():
    """The earbuds' address: JABRA_ADDR if set, otherwise the first device paired with this PC
    whose name contains "Jabra" (Windows only; elsewhere pass --addr)."""
    if os.environ.get("JABRA_ADDR"):
        return os.environ["JABRA_ADDR"]
    if sys.platform != "win32":
        return None
    from ctypes import wintypes

    class SearchParams(ctypes.Structure):  # BLUETOOTH_DEVICE_SEARCH_PARAMS
        _fields_ = [("size", wintypes.DWORD), ("authenticated", wintypes.BOOL), ("remembered", wintypes.BOOL),
                    ("unknown", wintypes.BOOL), ("connected", wintypes.BOOL), ("inquiry", wintypes.BOOL),
                    ("timeout", ctypes.c_ubyte), ("radio", wintypes.HANDLE)]

    class DeviceInfo(ctypes.Structure):  # BLUETOOTH_DEVICE_INFO
        _fields_ = [("size", wintypes.DWORD), ("address", ctypes.c_ulonglong), ("cod", wintypes.ULONG),
                    ("connected", wintypes.BOOL), ("remembered", wintypes.BOOL), ("authenticated", wintypes.BOOL),
                    ("last_seen", wintypes.WORD * 8), ("last_used", wintypes.WORD * 8), ("name", wintypes.WCHAR * 248)]

    try:
        bt = ctypes.WinDLL("bthprops.cpl")
    except OSError:
        return None
    bt.BluetoothFindFirstDevice.restype = wintypes.HANDLE
    search = SearchParams(ctypes.sizeof(SearchParams), True, True, False, True, False, 0, None)
    info = DeviceInfo()
    info.size = ctypes.sizeof(DeviceInfo)
    find = bt.BluetoothFindFirstDevice(ctypes.byref(search), ctypes.byref(info))
    if not find:
        return None
    try:
        while True:
            if "jabra" in info.name.lower():
                a = info.address
                return ":".join(f"{(a >> (40 - 8 * i)) & 0xFF:02X}" for i in range(6))
            if not bt.BluetoothFindNextDevice(wintypes.HANDLE(find), ctypes.byref(info)):
                return None
    finally:
        bt.BluetoothFindDeviceClose(wintypes.HANDLE(find))


DEFAULT_ADDR = find_earbuds()
DEFAULT_CHANNEL = 29

APP, BUDS = 0x09, 0x04
EVENT, READ, WRITE, REPLY = 0, 1, 2, 3
TYPE_NAMES = {EVENT: "EVENT", READ: "READ", WRITE: "WRITE", REPLY: "REPLY"}

CMD_IDENT, CMD_DEVICE, CMD_STATUS, CMD_CONFIG, CMD_NACK, CMD_ACK = 0x02, 0x0D, 0x12, 0x13, 0xFE, 0xFF
# DEVICE 4c: the event subscription (a 32-bit mask), and the events themselves.
DEVICE_EVENTS, EVENT_LINK = 0x4C, 0x07
STATUS_BATTERY = 0x02
IDENT_NAME, IDENT_FIRMWARE = 0x00, 0x03
CONFIG_SOUND_MODE, SOUND_MODE_SLOT = 0xBE, 0x01
CONFIG_EQ, CONFIG_EQ_ENABLED = 0x7D, 0x7E

# On/off settings: name -> (CONFIG sub, read payload, write prefix, off value, on value).
# Values are exactly what Sound+ wrote; each confirmed by toggling it in Sound+ and reading back.
TOGGLES = {
    "reducewind":      (0xEF, b"\x00", b"\x00", b"\x00", b"\x01"),
    "ineardetection":  (0x92, b"", b"", b"\x00", b"\x01"),
    "autohearthrough": (0xF0, b"", b"", b"\x00", b"\x01"),
    # Only the first byte is on/off; the second is the sidetone volume, which set_toggle keeps.
    "sidetone":        (0x7C, b"", b"", b"\x01", b"\x00"),
}

# Button map (CONFIG 9A): records of [menu][earbud][gesture][action]; names as shown in Sound+.
CONFIG_BUTTONS = 0x9A
BUTTON_MENUS = {0: "Music and media", 1: "Incoming calls", 2: "Active calls", 3: "(hidden menu 03)"}
EARBUDS = {0: "left", 1: "right"}
GESTURES = {0: "single press", 1: "double press", 2: "triple press"}
BUTTON_ACTIONS = {
    0x00: "None", 0x01: "Play/pause", 0x02: "Next track", 0x03: "Restart track",
    0x04: "Spotify Tap? (unconfirmed)", 0x05: "HearThrough/ANC/Off", 0x06: "Reject call",
    0x07: "Answer call", 0x08: "Answer call and hold active call", 0x09: "End call",
    0x0A: "Hold active call", 0x0B: "Mute/unmute microphone", 0x0C: "Sidetone on/off",
}

# Fixed per-band (freq field, Q field) on firmware 4.6.0; only the gains vary.
EQ_BANDS = [(180, 1730), (750, 1654), (3000, 1577), (12000, 1597), (22800, 2088)]
# Gains exactly as Sound+ wrote them. Names confirmed by tapping each preset in
# Sound+ and reading the curve back (neutral and bassboost by elimination).
EQ_PRESETS = {
    "neutral":   (0, 0, 0, 0, 0),
    "bassboost": (270, -118, 0, 0, 0),
    "smooth":    (0, 118, 0, -118, 0),
    "speech":    (-270, 0, 0, 61, -120),
    "trebleboost": (-90, -118, 0, 118, 240),
    "energize":  (180, 0, -118, -61, 180),
}

# Confirmed on firmware 4.6.0 by setting each value and checking Sound+ and by ear.
MODES = {"anc": 0x04, "hearthrough": 0x02, "off": 0x01}
MODE_NAMES = {v: k for k, v in MODES.items()}

NACK_CODES = {
    0x00: "NO_ERROR_CODE", 0xF2: "NO_TRANSPORT", 0xF3: "BAD_STATE",
    0xF4: "TRANSMIT_FAILED", 0xF5: "ILLEGAL_ADDR", 0xF6: "UNKNOWN_CMD",
    0xF7: "FILE_NOT_FOUND", 0xF8: "INTERNAL_ERROR", 0xF9: "REPLY_ERROR",
    0xFA: "ILLEGAL_PARAM", 0xFB: "OUT_OF_SEQUENCE", 0xFC: "NO_SPACE",
    0xFD: "ACCESS_DENIED", 0xFE: "NO_MORE_DATA", 0xFF: "UNKNOWN_SUB_CMD",
}


class GnpError(Exception):
    pass


class NackError(GnpError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


class Frame:
    def __init__(self, raw):
        self.raw = raw
        self.dst, self.src, self.seq = raw[0], raw[1], raw[2]
        self.type = raw[3] >> 6
        self.cmd = raw[4]
        self.sub = raw[5] if len(raw) > 5 else None
        self.payload = raw[6:]

    def __str__(self):
        sub = "--" if self.sub is None else f"{self.sub:02x}"
        return (f"{TYPE_NAMES[self.type]:<5} {self.src:02x}->{self.dst:02x} seq={self.seq:02x} "
                f"cmd={self.cmd:02x} sub={sub} {self.payload.hex(' ')}")


def build(dst, src, seq, ptype, cmd, sub=None, payload=b""):
    """Header: dst, src, seq, (type << 6 | total length), cmd, [sub], payload."""
    body = bytes([cmd]) + (bytes([sub]) if sub is not None else b"") + payload
    length = 4 + len(body)
    if not 5 <= length <= 62:
        raise ValueError(f"GNP frame length {length} outside 5..62")
    return bytes([dst, src, seq, (ptype << 6) | length]) + body


class Earbuds:
    def __init__(self, addr, channel, verbose=False):
        if not addr:
            raise GnpError("No paired Jabra earbuds found. Pair them with this PC, or pass --addr / set JABRA_ADDR.")
        self.verbose = verbose
        self.seq = 0
        self.buf = b""
        self.events = []  # unsolicited frames seen while waiting for replies
        for attempt in range(2):
            self.sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            self.sock.settimeout(10)
            try:
                self.sock.connect((addr, channel))
                return
            except OSError as e:
                self.sock.close()
                # Windows reports WSAENETDOWN (10050) when the earbuds' link to
                # this PC is not up yet; the failed attempt usually brings it up.
                if attempt or getattr(e, "winerror", None) != 10050:
                    raise
                time.sleep(2)

    def close(self):
        self.sock.close()

    def _next_seq(self):
        # Events from the earbuds use seq 0, so requests cycle through 1..255.
        self.seq = self.seq % 255 + 1
        return self.seq

    def _read_frame(self, deadline):
        while True:
            if len(self.buf) >= 4:
                length = self.buf[3] & 0x3F
                if length < 5:
                    raise GnpError(f"bad frame header {self.buf[:4].hex()}")
                if len(self.buf) >= length:
                    raw, self.buf = self.buf[:length], self.buf[length:]
                    frame = Frame(raw)
                    if self.verbose:
                        print(f"  <- {frame}")
                    return frame
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("no reply from earbuds")
            self.sock.settimeout(remaining)
            chunk = self.sock.recv(1024)
            if not chunk:
                raise GnpError("connection closed by earbuds")
            self.buf += chunk

    def request(self, ptype, cmd, sub=None, payload=b"", timeout=3.0):
        seq = self._next_seq()
        raw = build(BUDS, APP, seq, ptype, cmd, sub, payload)
        if self.verbose:
            print(f"  -> {Frame(raw)}")
        self.sock.sendall(raw)
        deadline = time.monotonic() + timeout
        while True:
            frame = self._read_frame(deadline)
            if frame.type != REPLY or frame.seq != seq:
                if frame.type == EVENT:
                    self.events.append(frame)
                continue  # unsolicited event or a stale reply
            if frame.cmd == CMD_NACK:
                code = frame.sub if frame.sub is not None else 0
                raise NackError(code, f"NACK {NACK_CODES.get(code, hex(code))} for cmd {cmd:02x}/{sub:02x}")
            return frame

    def read_string(self, cmd, sub):
        payload = self.request(READ, cmd, sub).payload
        # Strings are length-prefixed.
        return payload[1:1 + payload[0]].decode("ascii", errors="replace")

    def get_mode(self):
        return self.request(READ, CMD_CONFIG, CONFIG_SOUND_MODE, bytes([SOUND_MODE_SLOT])).payload[0]

    def set_mode(self, value):
        reply = self.request(WRITE, CMD_CONFIG, CONFIG_SOUND_MODE, bytes([SOUND_MODE_SLOT, value]))
        if reply.cmd != CMD_ACK:
            raise GnpError(f"expected ACK, got {reply}")
        actual = self.get_mode()
        if actual != value:
            raise GnpError(f"mode reads back as {actual:02x}, expected {value:02x}")

    def get_eq(self):
        """Return [(freq, gain, q, type)] per band; band 5 carries no type byte."""
        p = self.request(READ, CMD_CONFIG, CONFIG_EQ, b"\x00").payload
        bands = []
        for i in range(p[0]):
            band = p[4 + 7 * i:11 + 7 * i]
            bands.append((int.from_bytes(band[0:2], "big"),
                          int.from_bytes(band[2:4], "big", signed=True),
                          int.from_bytes(band[4:6], "big"),
                          band[6] if len(band) > 6 else None))
        return bands

    def get_link(self):
        """Return (linked, primary) or None: whether the earbuds are linked to each other, and
        which one ("left"/"right") holds the link to this PC.

        The earbuds only send this as an event (0d 4c 07 OO PP), but they send it whenever the
        event subscription is written, so write the current mask back and wait for it.
        """
        self.events.clear()
        mask = self.request(READ, CMD_DEVICE, DEVICE_EVENTS).payload
        self.request(WRITE, CMD_DEVICE, DEVICE_EVENTS, mask)
        deadline = time.monotonic() + 1.5
        while True:
            for f in self.events:
                if f.cmd == CMD_DEVICE and f.sub == DEVICE_EVENTS and len(f.payload) >= 3 and f.payload[0] == EVENT_LINK:
                    return f.payload[1] == 1, "right" if f.payload[2] == 1 else "left"
            self.events.clear()
            try:
                frame = self._read_frame(deadline)
            except TimeoutError:
                return None
            if frame.type == EVENT:
                self.events.append(frame)

    def get_battery(self):
        """Return (left %, right %, case %); None for a part that's offline or didn't report.

        The report is ordered by role, not by side: `80 PP 00 00 NN`, then NN entries of
        `[id][flags][%]`, where PP is the earbud holding the link to this PC and the entries
        are the case (id 15) and the other earbud. An earbud in the case keeps reporting its
        last level, so it's dropped while the earbuds aren't linked.
        """
        link = self.get_link()
        linked, primary_side = link if link else (True, "right")
        p = self.request(READ, CMD_STATUS, STATUS_BATTERY).payload
        case = other = None
        for i in range(p[4]):
            at = 5 + 3 * i
            if at + 2 >= len(p):
                break
            if p[at] == 0x15:
                case = p[at + 2] or None
            else:
                other = p[at + 2] or None
        primary = p[1] or None
        if not linked:
            other = None
        left, right = (other, primary) if primary_side == "right" else (primary, other)
        return left, right, case

    def get_buttons(self, menu):
        """Return [(earbud, gesture, action)] for one MyControls menu."""
        p = self.request(READ, CMD_CONFIG, CONFIG_BUTTONS, bytes([menu])).payload
        return [(p[i + 1], p[i + 2], p[i + 3]) for i in range(0, len(p) - 3, 4)]

    def get_toggle(self, name):
        sub, read_payload, prefix, off, on = TOGGLES[name]
        value = self.request(READ, CMD_CONFIG, sub, read_payload).payload[len(prefix):]
        return {off: "off", on: "on"}.get(value[:len(on)], f"unknown ({value.hex(' ')})")

    def set_toggle(self, name, state):
        sub, read_payload, prefix, off, on = TOGGLES[name]
        # Keep any bytes after the on/off value as they are (sidetone's volume).
        rest = self.request(READ, CMD_CONFIG, sub, read_payload).payload[len(prefix) + len(on):]
        self._write_ack(CMD_CONFIG, sub, prefix + (on if state == "on" else off) + rest)
        actual = self.get_toggle(name)
        if actual != state:
            raise GnpError(f"{name} reads back as {actual}, expected {state}")

    def _write_ack(self, cmd, sub, payload):
        reply = self.request(WRITE, cmd, sub, payload)
        if reply.cmd != CMD_ACK:
            raise GnpError(f"expected ACK, got {reply}")

    def set_eq(self, gains):
        # Same sequence as Sound+: enable, write the curve, and disable for an all-zero curve.
        self._write_ack(CMD_CONFIG, CONFIG_EQ_ENABLED, b"\x01")
        self._write_ack(CMD_CONFIG, CONFIG_EQ, eq_payload(gains))
        if not any(gains):
            self._write_ack(CMD_CONFIG, CONFIG_EQ_ENABLED, b"\x00")
        actual = tuple(gain for _, gain, _, _ in self.get_eq())
        if actual != tuple(gains):
            raise GnpError(f"EQ reads back as {actual}, expected {tuple(gains)}")


def eq_payload(gains):
    """Band count, three zero bytes, then (freq u16, gain s16, Q u16) per band, big-endian."""
    out = bytes([len(EQ_BANDS), 0, 0, 0])
    for (freq, q), gain in zip(EQ_BANDS, gains):
        out += freq.to_bytes(2, "big") + gain.to_bytes(2, "big", signed=True) + q.to_bytes(2, "big")
    return out


def mode_name(value):
    return MODE_NAMES.get(value, f"unknown (0x{value:02x})")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["info", "mode", "cycle", "battery", "eq", "setting", "buttons"])
    parser.add_argument("value", nargs="?", help=f"mode: {', '.join(MODES)}; eq preset: {', '.join(EQ_PRESETS)}; "
                                                 f"setting: {', '.join(TOGGLES)}")
    parser.add_argument("state", nargs="?", choices=["on", "off"], help="for setting: on or off")
    parser.add_argument("--addr", default=DEFAULT_ADDR)
    parser.add_argument("--channel", type=int, default=DEFAULT_CHANNEL)
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()
    allowed = {"mode": MODES, "eq": EQ_PRESETS, "setting": TOGGLES}.get(args.command, {})
    if args.value is not None and args.value not in allowed:
        parser.error(f"'{args.value}' is not valid for {args.command}; choose from: {', '.join(allowed) or 'nothing'}")
    if args.state and (args.command != "setting" or not args.value):
        parser.error("on/off only applies to: setting NAME on|off")

    try:
        buds = Earbuds(args.addr, args.channel, args.verbose)
    except OSError as e:
        sys.exit(f"Could not connect to {args.addr} on RFCOMM channel {args.channel}: {e}\n"
                 "Check that the earbuds are paired with this PC, out of the case, and in range.")

    try:
        if args.command == "info":
            print(f"Product:  {buds.read_string(CMD_IDENT, IDENT_NAME)}")
            print(f"Firmware: {buds.read_string(CMD_IDENT, IDENT_FIRMWARE)}")
            print(f"Mode:     {mode_name(buds.get_mode())}")
        elif args.command == "mode":
            if args.value:
                buds.set_mode(MODES[args.value])
            print(f"Mode: {mode_name(buds.get_mode())}")
        elif args.command == "cycle":
            original = buds.get_mode()
            print(f"Starting mode: {mode_name(original)}")
            for name in ("anc", "hearthrough", "off"):
                buds.set_mode(MODES[name])
                print(f"Set {name:<12} -> earbuds report {mode_name(buds.get_mode())}")
                time.sleep(5)
            buds.set_mode(original)
            print(f"Restored: {mode_name(original)}")
        elif args.command == "buttons":
            for menu in (0, 1, 2):
                print(BUTTON_MENUS[menu])
                for earbud, gesture, action in buds.get_buttons(menu):
                    name = BUTTON_ACTIONS.get(action, f"unknown (0x{action:02x})")
                    print(f"  {EARBUDS.get(earbud, earbud):<5} {GESTURES.get(gesture, gesture):<13} {name}")
        elif args.command == "setting":
            if args.value and args.state:
                buds.set_toggle(args.value, args.state)
            for name in ([args.value] if args.value else TOGGLES):
                print(f"{name:<16} {buds.get_toggle(name)}")
        elif args.command == "battery":
            left, right, case = buds.get_battery()
            for part, level in (("Left: ", left), ("Right:", right), ("Case: ", case)):
                print(f"{part} {level}%" if level else f"{part} offline or not reported")
        elif args.command == "eq":
            if args.value:
                buds.set_eq(EQ_PRESETS[args.value])
                print(f"Applied EQ preset: {args.value}")
            # The 0x7E enable flag is write-only (reads are NACKed ACCESS_DENIED). Gain unit is unconfirmed; 0.01 dB is the working assumption.
            for i, (freq, gain, q, ftype) in enumerate(buds.get_eq(), 1):
                kind = "--" if ftype is None else f"{ftype:02x}"
                print(f"Band {i}: freq={freq:>5}  gain={gain:+5d} (~{gain / 100:+.2f} dB?)  q={q}  type={kind}")
    except (GnpError, TimeoutError, OSError) as e:
        sys.exit(f"Error: {e}")
    finally:
        buds.close()


if __name__ == "__main__":
    main()
