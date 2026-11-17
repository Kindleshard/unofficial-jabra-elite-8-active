"""Read the earbud settings we couldn't name, showing every frame that comes back.

Read-only: only READ requests are sent.

  py tools/probe_unknown.py
"""
import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import jabra_gnp as g  # noqa: E402

SUBS = [0x08, 0x10, 0x1D, 0x81, 0xAD, 0xE5]
PARAMS = [b"", b"\x00", b"\x01", b"\x02", b"\x03"]


def read(buds, cmd, sub, payload):
    try:
        f = buds.request(g.READ, cmd, sub, payload, timeout=1.0)
        return f"{f.payload.hex(' ')}   [from {f.src:02x} to {f.dst:02x}]"
    except g.NackError as e:
        return f"NACK {g.NACK_CODES.get(e.code, hex(e.code))}"
    except TimeoutError:
        return "TIMEOUT"


buds = g.Earbuds(g.DEFAULT_ADDR, g.DEFAULT_CHANNEL)
try:
    for sub in SUBS:
        print(f"CONFIG {sub:02x}")
        for p in PARAMS:
            results = [read(buds, g.CMD_CONFIG, sub, p) for _ in range(3)]
            label = p.hex() or "--"
            print(f"  param {label}: " + (results[0] if len(set(results)) == 1 else " | ".join(results)))
            time.sleep(0.05)
    print("Button menu 03")
    for p in (b"\x03",):
        print("  ", read(buds, g.CMD_CONFIG, 0x9A, p))
    print("Button menus 00-02 for comparison")
    for m in range(3):
        print("  ", m, read(buds, g.CMD_CONFIG, 0x9A, bytes([m])))
finally:
    buds.close()
