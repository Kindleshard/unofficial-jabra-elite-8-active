"""Read-only scan of IDENT (02), STATUS (12) and DEVICE (0d) sub-commands 00-FF with no payload.

  py tools/scan_cmds.py
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import jabra_gnp as g  # noqa: E402

buds = g.Earbuds(g.DEFAULT_ADDR, g.DEFAULT_CHANNEL)
try:
    for cmd in (0x02, 0x12, 0x0D):
        for sub in range(256):
            try:
                p = buds.request(g.READ, cmd, sub, b"", timeout=0.35).payload
                text = "".join(chr(b) if 32 <= b < 127 else "." for b in p)
                print(f"{cmd:02x} {sub:02x}: {p.hex(' ')}   |{text}|", flush=True)
            except g.NackError as e:
                if e.code not in (0xFF, 0xF6):
                    print(f"{cmd:02x} {sub:02x}: NACK {g.NACK_CODES.get(e.code, hex(e.code))}", flush=True)
            except TimeoutError:
                pass
finally:
    buds.close()
