"""Read CONFIG be (sound mode) with each slot byte, twice. Read-only."""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import jabra_gnp as g  # noqa: E402

buds = g.Earbuds(g.DEFAULT_ADDR, g.DEFAULT_CHANNEL)
try:
    for slot in range(6):
        out = []
        for _ in range(2):
            try:
                out.append(buds.request(g.READ, g.CMD_CONFIG, 0xBE, bytes([slot]), timeout=1.5).payload.hex(" "))
            except g.NackError as e:
                out.append(f"NACK {g.NACK_CODES.get(e.code, hex(e.code))}")
            except TimeoutError:
                out.append("TIMEOUT")
        print(f"be {slot:02x}: {' | '.join(out)}")
finally:
    buds.close()
