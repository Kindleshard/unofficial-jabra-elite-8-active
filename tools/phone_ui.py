"""Look at and drive the phone screen over adb (used for mapping Sound+).

  py tools/phone_ui.py state              lock state and foreground activity
  py tools/phone_ui.py dump               visible texts with tap coordinates and switch states
  py tools/phone_ui.py tap X Y            tap a point
  py tools/phone_ui.py back               press Back
  py tools/phone_ui.py scroll             scroll down one screen
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

# adb from PATH; set ANDROID_SERIAL to pick a phone when more than one is connected.
ADB = ["adb"]


def adb(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def state():
    window = adb("shell", "dumpsys", "window")
    focus = re.search(r"mCurrentFocus=Window\{[^ ]+ [^ ]+ ([^}]+)\}", window)
    print("focus:", focus.group(1) if focus else "?")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    adb("shell", "rm", "/sdcard/ui.xml")
    out = []
    for node in ET.fromstring(xml[xml.index("<"):]).iter("node"):
        text = node.get("text") or node.get("content-desc") or ""
        checkable = node.get("checkable") == "true"
        if not text and not checkable:
            continue
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        flags = []
        if node.get("clickable") == "true":
            flags.append("click")
        if checkable:
            flags.append("ON" if node.get("checked") == "true" else "off")
        out.append((text.replace("\n", " | "), (x1 + x2) // 2, (y1 + y2) // 2, " ".join(flags)))
    return out


def dump():
    for text, x, y, flags in nodes():
        print(f"({x:4d},{y:4d}) {flags:<9} {text}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "state":
        state()
    elif cmd == "dump":
        dump()
    elif cmd == "tap":
        adb("shell", "input", "tap", sys.argv[2], sys.argv[3])
        time.sleep(2)
    elif cmd == "back":
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.5)
    elif cmd == "scroll":
        adb("shell", "input", "swipe", "540", "1900", "540", "800", "400")
        time.sleep(1.2)
