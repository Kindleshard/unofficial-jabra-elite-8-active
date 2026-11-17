"""Custom EQ range: drag band 1 of Sound+'s equalizer widget to its top and bottom, read the gains."""
import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import decode_features as d  # noqa: E402

# Screen coordinates of band 1 (bass) on the widget, from the screenshot (1080x2340 frame).
X, CENTRE, TOP, BOTTOM = 185, 528, 200, 860


def gains(buds):
    p = buds.request(d.g.READ, d.g.CMD_CONFIG, d.g.CONFIG_EQ, b"\x00").payload
    return [int.from_bytes(p[4 + 7 * i + 2:4 + 7 * i + 4], "big", signed=True) for i in range(p[0])]


def drag(y_from, y_to):
    d.adb("shell", "input", "swipe", str(X), str(y_from), str(X), str(y_to), "800")
    time.sleep(2.5)


buds = d.g.Earbuds(d.g.DEFAULT_ADDR, d.g.DEFAULT_CHANNEL)
try:
    print("start (Smooth)     :", gains(buds))
    drag(CENTRE, TOP - 150)
    print("bass dragged to top:", gains(buds))
    drag(TOP, BOTTOM + 150)
    print("bass dragged down  :", gains(buds))
    d.tap_text("Smooth")
    time.sleep(1.5)
    print("after tapping Smooth:", gains(buds))
finally:
    buds.close()
