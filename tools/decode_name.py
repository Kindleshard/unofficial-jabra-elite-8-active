"""Headset name (CONFIG 56): write a test name, read it back, restore the original."""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import jabra_gnp as g  # noqa: E402

NAME = 0x56
buds = g.Earbuds(g.DEFAULT_ADDR, g.DEFAULT_CHANNEL)
try:
    original = buds.request(g.READ, g.CMD_CONFIG, NAME).payload
    print("original:", original)
    try:
        buds._write_ack(g.CMD_CONFIG, NAME, b"Elite Test")
        print("after writing 'Elite Test':", buds.request(g.READ, g.CMD_CONFIG, NAME).payload)
    except g.GnpError as e:
        print("plain-text write refused:", e)
    finally:
        buds._write_ack(g.CMD_CONFIG, NAME, original)
        restored = buds.request(g.READ, g.CMD_CONFIG, NAME).payload
        print("restored:", restored, "OK" if restored == original else "!! DIFFERENT")
finally:
    buds.close()
