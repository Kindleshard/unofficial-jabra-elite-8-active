"""Check that each newly decoded setting accepts writes: write a test value, read back, restore.

Each case: (name, sub, read_payload, test_write, expected_read, restore_write, expected_restored_read).
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import jabra_gnp as g  # noqa: E402

h = bytes.fromhex
CASES = [
    ("Spatial Sound",       0xE4, b"",  h("050500"), h("050501"), h("040400"), h("040401")),
    ("Auto-pause music",    0x8E, b"",  h("0001"),   h("0001"),   h("0000"),   h("0000")),
    ("HearThrough audio",   0x76, b"",  h("02"),     h("02"),     h("01"),     h("01")),
    ("Auto-answer calls",   0x91, b"",  h("0101"),   h("0101"),   h("0100"),   h("0100")),
    ("Auto-mute mics",      0x8F, b"",  h("0001"),   h("0001"),   h("0000"),   h("0000")),
    ("Mute reminder",       0x1E, b"",  h("14"),     h("14"),     h("00"),     h("00")),
    ("Auto volume",         0xF1, b"",  h("00"),     h("00"),     h("01"),     h("01")),
    ("In-call EQ",          0x15, b"",  h("01"),     h("01"),     h("00"),     h("00")),
    ("Sound-mode cycle",    0xBF, b"",  h("020001"), h("020001"), h("03000102"), h("03000102")),
    ("Sleep mode",          0x90, b"",  h("03"),     h("03"),     h("06"),     h("06")),
    ("Headset guidance",    0x3A, b"",  h("00"),     h("00"),     h("01"),     h("01")),
]


def read(buds, sub, payload):
    return buds.request(g.READ, g.CMD_CONFIG, sub, payload).payload


buds = g.Earbuds(g.DEFAULT_ADDR, g.DEFAULT_CHANNEL)
try:
    for name, sub, rp, test, want, restore, want_restored in CASES:
        before = read(buds, sub, rp)
        try:
            buds._write_ack(g.CMD_CONFIG, sub, test)
            got = read(buds, sub, rp)
            ok = "OK" if got == want else f"read {got.hex(' ')} (expected {want.hex(' ')})"
        except g.GnpError as e:
            ok = f"write refused: {e}"
        buds._write_ack(g.CMD_CONFIG, sub, restore)
        after = read(buds, sub, rp)
        back = "restored" if after == before == want_restored else f"!! now {after.hex(' ')}, was {before.hex(' ')}"
        print(f"{name:<20} {sub:02x}  write {test.hex(' '):<12} -> {ok:<40} {back}", flush=True)
finally:
    buds.close()
