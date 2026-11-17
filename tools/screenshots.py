"""Screenshot every tab of the app (top and scrolled) into the given folder.

  py tools/screenshots.py OUT_DIR [PREFIX]
"""
import pathlib
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import decode_features as d  # noqa: E402

out = pathlib.Path(sys.argv[1])
prefix = sys.argv[2] if len(sys.argv) > 2 else "ui"
out.mkdir(parents=True, exist_ok=True)


def shot(name):
    png = subprocess.run(d.ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout
    (out / f"{prefix}_{name}.png").write_bytes(png)
    print("saved", name, flush=True)


def nav(label):
    # Bottom navigation labels sit near the bottom of the screen.
    target = max((n for n in d.nodes() if n["text"] == label), key=lambda n: n["y"])
    d.tap(target["x"], target["y"], wait=1.5)


# Swipes run at x=230, between EQ bands, so they never grab a point on the Sound tab's graph.
for tab, pages in (("Home", 2), ("Sound", 3), ("Controls", 2), ("Settings", 3)):
    nav(tab)
    for _ in range(4):
        d.adb("shell", "input", "swipe", "230", "700", "230", "1900", "400")
        time.sleep(1.2)
    time.sleep(2)  # let a pull-to-refresh at the top finish
    for page in range(pages):
        shot(f"{tab.lower()}{page + 1}")
        if page + 1 < pages:
            d.adb("shell", "input", "swipe", "230", "1800", "230", "700", "500")
            time.sleep(1.2)
nav("Home")
