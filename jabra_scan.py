"""Snapshot every readable GNP setting on the earbuds and compare snapshots.

Only READ requests are sent, so nothing on the earbuds changes.

Usage:
  py jabra_scan.py snap NAME       read CONFIG and STATUS subs 0x00-0xFF into snapshots/NAME.json
  py jabra_scan.py snap NAME BASE  re-read only the settings readable in snapshot BASE (fast)
  py jabra_scan.py diff OLD NEW    show what differs between two snapshots
  py jabra_scan.py show NAME       list everything readable in a snapshot
"""
import json
import pathlib
import sys
import time

import jabra_gnp as g

SNAP_DIR = pathlib.Path(__file__).with_name("snapshots")
COMMANDS = {"config": g.CMD_CONFIG, "status": g.CMD_STATUS}
# Some settings take an index/slot byte (e.g. sound mode is CONFIG BE 01).
PARAM_RETRIES = (b"\x00", b"\x01")


def read(buds, cmd, sub, payload):
    try:
        return buds.request(g.READ, cmd, sub, payload, timeout=0.4).payload.hex(" ")
    except g.NackError as e:
        return f"NACK {g.NACK_CODES.get(e.code, hex(e.code))}"
    except TimeoutError:
        return "TIMEOUT"


def readable(entry):
    return any(not v.startswith(("NACK", "TIMEOUT")) for v in entry.values())


def snap(name, base=None):
    if base:
        # Repeat exactly the reads (including slot params) that worked in the base snapshot.
        targets = {k: [p for p, v in e.items() if not v.startswith(("NACK", "TIMEOUT"))]
                   for k, e in load(base).items() if readable(e)}
    else:
        targets = {f"{label} {sub:02x}": None for label in COMMANDS for sub in range(256)}
    buds = g.Earbuds(g.DEFAULT_ADDR, g.DEFAULT_CHANNEL)
    result, started = {}, time.monotonic()
    try:
        for key, params in targets.items():
            label, sub = key.split()
            cmd, sub = COMMANDS[label], int(sub, 16)
            if params is not None:
                result[key] = {p: read(buds, cmd, sub, bytes.fromhex(p)) for p in params}
                continue
            value = read(buds, cmd, sub, b"")
            entry = {"": value}
            # Slot-based settings either NACK ILLEGAL_PARAM or stay silent (e.g. CONFIG EF).
            if value in ("NACK ILLEGAL_PARAM", "TIMEOUT"):
                for param in PARAM_RETRIES:
                    entry[param.hex()] = read(buds, cmd, sub, param)
            result[key] = entry
    finally:
        buds.close()
    SNAP_DIR.mkdir(exist_ok=True)
    path = SNAP_DIR / f"{name}.json"
    path.write_text(json.dumps(result, indent=1), encoding="utf-8")
    count = sum(1 for v in result.values() if readable(v))
    print(f"Saved {path.name}: {count} readable settings in {time.monotonic() - started:.0f}s")


def load(name):
    return json.loads((SNAP_DIR / f"{name}.json").read_text(encoding="utf-8"))


def show(name):
    for key, entry in load(name).items():
        for param, value in entry.items():
            if not value.startswith(("NACK", "TIMEOUT")):
                print(f"{key} {('[' + param + ']') if param else '':<5} {value}")


def diff(old, new):
    a, b = load(old), load(new)
    changes = 0
    # Compare only reads present in both snapshots (a fast snapshot covers a subset).
    for key in sorted(set(a) & set(b)):
        for param in sorted(set(a[key]) & set(b[key])):
            va, vb = a[key][param], b[key][param]
            if va != vb:
                changes += 1
                print(f"{key} {('[' + param + ']') if param else '':<5}\n   {old}: {va}\n   {new}: {vb}")
    print(f"{changes} difference(s)")


if __name__ == "__main__":
    actions = {"snap": (snap, (1, 2)), "show": (show, (1,)), "diff": (diff, (2,))}
    if len(sys.argv) < 2 or sys.argv[1] not in actions or len(sys.argv) - 2 not in actions[sys.argv[1]][1]:
        sys.exit(__doc__)
    fn, _ = actions[sys.argv[1]]
    fn(*sys.argv[2:])
