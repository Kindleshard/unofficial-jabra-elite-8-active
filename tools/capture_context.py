"""Show when Sound+ reads given CONFIG subs in a capture, with the replies and nearby traffic.

  py tools/capture_context.py capture/ch29.tsv 08 10 1d e5 ad
Each capture line is: frame|time|direction|hex.
"""
import sys

path, wanted = sys.argv[1], {int(s, 16) for s in sys.argv[2:]}
TYPES = {0: "EVENT", 1: "READ", 2: "WRITE", 3: "REPLY"}

frames = []
for line in open(path, encoding="utf-8", errors="replace"):
    parts = line.strip().split("|")
    if len(parts) < 4 or len(parts[3]) < 10:
        continue
    raw = bytes.fromhex(parts[3])
    frames.append((float(parts[1]), raw))


def show(t, raw, mark=""):
    kind = TYPES[raw[3] >> 6]
    print(f"{mark}{t:9.3f}  {raw[1]:02x}->{raw[0]:02x} seq={raw[2]:02x} {kind:<5} {raw[4:].hex(' ')}")


for i, (t, raw) in enumerate(frames):
    if raw[3] >> 6 == 1 and raw[4] == 0x13 and len(raw) > 5 and raw[5] in wanted:
        print("-" * 70)
        for tt, r in frames[max(0, i - 4):i]:
            show(tt, r, "   ")
        show(t, raw, ">> ")
        reply = next(((tt, r) for tt, r in frames[i + 1:i + 40] if r[2] == raw[2] and r[3] >> 6 == 3), None)
        if reply:
            show(*reply, "<< ")
