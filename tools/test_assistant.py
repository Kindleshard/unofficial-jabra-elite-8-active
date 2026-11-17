"""Switch the voice assistant in the app (Controls tab) and check it on the earbuds from the PC.

Ends on the value it started with. Run with the app open and connected.
"""
import pathlib
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import decode_features as d  # noqa: E402
import jabra_gnp as g  # noqa: E402

NAMES = {0x00: "Phone's assistant", 0x11: "Google Assistant built in"}


def read(buds):
    return buds.request(g.READ, 0x0D, 0x4D, b"\x03", timeout=2).payload[0]


def pick(current, option):
    d.tap_text(current)
    d.tap_text(option)
    time.sleep(2.5)


buds = g.Earbuds(g.DEFAULT_ADDR, g.DEFAULT_CHANNEL)
try:
    nav = max((n for n in d.nodes() if n["text"] == "Controls"), key=lambda n: n["y"])
    d.tap(nav["x"], nav["y"], wait=2)
    before = read(buds)
    other = 0x00 if before == 0x11 else 0x11
    pick(NAMES[before], NAMES[other])
    got = read(buds)
    print(f"{'PASS' if got == other else 'FAIL'}  switched to {NAMES[other]}: {got:02x}")
    if len(sys.argv) > 1:
        out = pathlib.Path(sys.argv[1])
        out.write_bytes(subprocess.run(d.ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)
    pick(NAMES[other], NAMES[before])
    got = read(buds)
    print(f"{'PASS' if got == before else 'FAIL'}  back to {NAMES[before]}: {got:02x}")
finally:
    buds.close()
