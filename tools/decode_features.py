"""Decode Sound+ settings automatically: flip each one on the phone, diff the earbuds from the PC, restore.

  py tools/decode_features.py GROUP [GROUP...]     groups: spatial audio call general

Every tap's phone timestamp is logged to snapshots/feature_taps.jsonl so writes that are not
readable can be matched in the HCI log afterwards. Results go to snapshots/feature_results.jsonl.
"""
import json
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import jabra_gnp as g  # noqa: E402
import jabra_scan as scan  # noqa: E402

# adb from PATH; set ANDROID_SERIAL to pick a phone when more than one is connected.
ADB = ["adb"]
TAPS = ROOT / "snapshots" / "feature_taps.jsonl"
RESULTS = ROOT / "snapshots" / "feature_results.jsonl"
TAPS.parent.mkdir(exist_ok=True)


# ---------- phone ----------

def adb(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    adb("shell", "rm", "/sdcard/ui.xml")
    out = []
    for n in ET.fromstring(xml[xml.index("<"):]).iter("node"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        out.append({
            "text": (n.get("text") or n.get("content-desc") or "").replace("\n", " "),
            "x": (x1 + x2) // 2, "y": (y1 + y2) // 2,
            "checkable": n.get("checkable") == "true", "checked": n.get("checked") == "true",
        })
    return out


def focus():
    m = re.search(r"mCurrentFocus=Window\{[^ ]+ [^ ]+ ([^}]+)\}", adb("shell", "dumpsys", "window"))
    return m.group(1) if m else ""


def tap(x, y, wait=2.0):
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(wait)


def back():
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.5)


def scroll(down=True):
    if down:
        adb("shell", "input", "swipe", "540", "1900", "540", "900", "400")
    else:
        adb("shell", "input", "swipe", "540", "700", "540", "1900", "400")
    time.sleep(1.2)


def find(text, exact=True, tries=10):
    """Return the node with this text, scrolling down to look for it."""
    for _ in range(tries):
        for n in nodes():
            if (n["text"] == text) if exact else (text in n["text"]):
                return n
        scroll()
    raise RuntimeError(f"'{text}' not found on {focus()}")


def tap_text(text, exact=True):
    n = find(text, exact)
    tap(n["x"], n["y"])


def control_for(label):
    """The checkable node for a label: the label itself (radio rows) or the switch on its row."""
    ns = nodes()
    lab = next((n for n in ns if n["text"] == label), None)
    if lab is None:
        lab = find(label)
        ns = nodes()
        lab = next(n for n in ns if n["text"] == label)
    if lab["checkable"]:
        return lab
    row = [n for n in ns if n["checkable"] and abs(n["y"] - lab["y"]) < 90]
    if not row:
        raise RuntimeError(f"no switch next to '{label}'")
    return min(row, key=lambda n: abs(n["y"] - lab["y"]))


def phone_time():
    return float(adb("shell", "date", "+%s.%N").strip())


def go_home():
    for _ in range(6):
        if "HomeActivity" in focus():
            break
        back()
    for _ in range(3):
        scroll(down=False)


def open_settings(*path):
    """Home -> settings cog -> each named item in turn."""
    go_home()
    tap(1008, 223)
    for item in path:
        tap_text(item)


# ---------- earbuds ----------

def snapshot(buds):
    out = {}
    for key, params in TARGETS.items():
        label, sub = key.split()
        cmd, sub = scan.COMMANDS[label], int(sub, 16)
        for p in params:
            out[f"{key}[{p}]"] = scan.read(buds, cmd, sub, bytes.fromhex(p))
    return out


def changes(a, b):
    ignore = ("config 9a", "config 9b", "status 02")  # live values that drift on their own
    return {k: (a[k], b[k]) for k in a if a[k] != b[k] and not k.startswith(ignore)}


def log(path, record):
    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(record) + "\n")


def experiment(buds, name, flip, restore):
    """flip()/restore() perform the phone taps. Returns the readable changes."""
    before = snapshot(buds)
    t = phone_time()
    flip()
    time.sleep(1.5)
    after = snapshot(buds)
    diff = changes(before, after)
    log(TAPS, {"feature": name, "step": "flip", "phone_time": t})
    t = phone_time()
    restore()
    time.sleep(1.5)
    final = snapshot(buds)
    log(TAPS, {"feature": name, "step": "restore", "phone_time": t})
    leftover = changes(before, final)
    log(RESULTS, {"feature": name, "changed": diff, "not_restored": leftover})
    shown = ", ".join(f"{k}: {a} -> {b}" for k, (a, b) in diff.items()) or "no readable change"
    print(f"{name:<44} {shown}" + (f"   !! NOT RESTORED: {leftover}" if leftover else ""), flush=True)
    return diff


def toggle_switch(label):
    def flip():
        c = control_for(label)
        tap(c["x"], c["y"])
    return flip


def choose(label):
    def pick():
        c = control_for(label)
        tap(c["x"], c["y"])
    return pick


# ---------- groups ----------

def wearing_detection(on):
    open_settings("Headset settings", "General")
    c = control_for("In-ear detection")
    if c["checked"] != on:
        tap(c["x"], c["y"])


def group_spatial(buds):
    go_home()  # the home-screen card has its own switch
    sw = toggle_switch("Spatial Sound for media")
    experiment(buds, "Spatial Sound for media", sw, sw)


def group_audio(buds):
    wearing_detection(True)
    back()
    tap_text("Audio experience")
    sw = toggle_switch("Auto-pause music")
    experiment(buds, "Auto-pause music", sw, sw)
    experiment(buds, "When HearThrough on: Mute audio", choose("Mute audio"), choose("Continue playing audio"))


def group_call(buds):
    open_settings("Headset settings", "Call experience")
    for label in ("Auto-answer calls", "Auto-mute microphones", "Mute reminder", "Automatic volume adjustment"):
        sw = toggle_switch(label)
        experiment(buds, label, sw, sw)
    experiment(buds, "In-call audio: Treble", choose("Treble"), choose("Neutral"))
    experiment(buds, "In-call audio: Bass", choose("Bass"), choose("Neutral"))


def group_general(buds):
    open_settings("Headset settings", "General")
    experiment(buds, "Sound-mode cycle: HearThrough/Off", choose("HearThrough/Off"), choose("HearThrough/ANC/Off"))
    experiment(buds, "Sound-mode cycle: HearThrough/ANC", choose("HearThrough/ANC"), choose("HearThrough/ANC/Off"))
    for option in ("15 minutes", "1 hour", "2 hours", "Off"):
        experiment(buds, f"Sleep mode: {option}", choose(option), choose("30 minutes"))
    for option in ("Tone", "None"):
        experiment(buds, f"Headset guidance: {option}", choose(option), choose("Voice"))
    # Leave wearing detection the way the user had it (off).
    c = control_for("In-ear detection")
    if c["checked"]:
        tap(c["x"], c["y"])


GROUPS = {"spatial": group_spatial, "audio": group_audio, "call": group_call, "general": group_general}
TARGETS = {k: [p for p, v in e.items() if not v.startswith(("NACK", "TIMEOUT"))]
           for k, e in scan.load("full2").items() if scan.readable(e)}
# CONFIG be answers inconsistently without its slot byte; always read slots 00 and 01.
TARGETS["config be"] = ["00", "01"]

if __name__ == "__main__":
    buds = g.Earbuds(g.DEFAULT_ADDR, g.DEFAULT_CHANNEL)
    try:
        for group in sys.argv[1:]:
            GROUPS[group](buds)
    finally:
        buds.close()
