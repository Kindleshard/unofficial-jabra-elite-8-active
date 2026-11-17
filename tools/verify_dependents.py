"""Restore Spatial Sound's original value, then test the wearing-detection dependents with detection on."""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import jabra_gnp as g  # noqa: E402

h = bytes.fromhex
WEARING = 0x92
DEPENDENTS = [("Auto-pause music", 0x8E, h("0001"), h("0000")),
              ("Auto-answer calls", 0x91, h("0101"), h("0100")),
              ("Auto-mute mics", 0x8F, h("0001"), h("0000"))]


def read(buds, sub):
    return buds.request(g.READ, g.CMD_CONFIG, sub).payload


buds = g.Earbuds(g.DEFAULT_ADDR, g.DEFAULT_CHANNEL)
try:
    buds._write_ack(g.CMD_CONFIG, 0xE4, h("040401"))
    print("Spatial Sound restored to:", read(buds, 0xE4).hex(" "))

    wearing_before = read(buds, WEARING)
    buds._write_ack(g.CMD_CONFIG, WEARING, h("01"))
    print("wearing detection on:", read(buds, WEARING).hex(" "))
    for name, sub, on, off in DEPENDENTS:
        buds._write_ack(g.CMD_CONFIG, sub, on)
        got = read(buds, sub)
        buds._write_ack(g.CMD_CONFIG, sub, off)
        print(f"{name:<18} write {on.hex(' ')} -> read {got.hex(' ')} {'OK' if got == on else '!!'};"
              f" restored {read(buds, sub).hex(' ')}")
    buds._write_ack(g.CMD_CONFIG, WEARING, wearing_before)
    print("wearing detection restored to:", read(buds, WEARING).hex(" "))
finally:
    buds.close()
