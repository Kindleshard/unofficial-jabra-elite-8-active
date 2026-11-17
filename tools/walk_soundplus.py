"""Record every label and control on the current screen, scrolling to the bottom. Read-only (no taps).

Controls without text (sliders, switches, radio buttons) are listed by type, with the label
nearest above them, so an unlabeled slider can't be missed again. Works for Sound+ and for
the app.

  py tools/walk_soundplus.py NAME      saves snapshots/screens/NAME.txt and NAME.png
"""
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import decode_features as d  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parents[1] / "snapshots" / "screens"
CONTROLS = ("SeekBar", "Slider", "Switch", "CheckBox", "RadioButton", "ToggleButton", "ProgressBar")


def screen_nodes():
    """Labels and controls on screen, in order. Controls carry the label nearest above them."""
    d.adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = d.adb("shell", "cat", "/sdcard/ui.xml")
    d.adb("shell", "rm", "/sdcard/ui.xml")
    out, last_label = [], ""
    for n in ET.fromstring(xml[xml.index("<"):]).iter("node"):
        text = (n.get("text") or n.get("content-desc") or "").replace("\n", " ")
        kind = (n.get("class") or "").rsplit(".", 1)[-1]
        bounds = tuple(map(int, re.findall(r"\d+", n.get("bounds"))))
        if text:
            last_label = text
            out.append({"text": text, "kind": "label", "bounds": bounds, "after": None})
        if any(c in kind for c in CONTROLS):
            control = next(c for c in CONTROLS if c in kind)
            out.append({"text": None, "kind": control, "bounds": bounds, "after": last_label,
                        "checked": n.get("checked") == "true"})
    return out


def screen_items():
    items = []
    for n in screen_nodes():
        if n["kind"] == "label":
            items.append(n["text"])
        else:
            state = " on" if n["checked"] else ""
            items.append(f"[{n['kind']}{state} after \"{n['after']}\"]")
    return items


def walk():
    seen = []
    for _ in range(3):
        d.scroll(down=False)
    stale = 0
    while stale < 2:
        new = [t for t in screen_items() if t not in seen]
        seen += new
        stale = stale + 1 if not new else 0
        d.scroll(down=True)
    for _ in range(8):
        d.scroll(down=False)
    return seen


def switch_after(label, tries=8):
    """Centre of the first switch/radio/slider after a label, scrolling down to find it."""
    for _ in range(tries):
        for n in screen_nodes():
            if n["kind"] != "label" and n["after"] == label:
                x1, y1, x2, y2 = n["bounds"]
                return (x1 + x2) // 2, (y1 + y2) // 2
        d.scroll(down=True)
    raise RuntimeError(f"no control after '{label}'")


if __name__ == "__main__":
    name = sys.argv[1]
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.png").write_bytes(subprocess.run(d.ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)
    found = walk()
    (OUT / f"{name}.txt").write_text("\n".join(found), encoding="utf-8")
    print(f"{d.focus()}\n" + "\n".join(f"  {t}" for t in found))
