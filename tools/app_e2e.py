"""End-to-end check of the app: tap its controls on the phone, confirm on the earbuds from the PC.

Each check is undone afterwards. Run with the app open and connected.
"""
import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import decode_features as d  # noqa: E402
import walk_soundplus as w  # noqa: E402

g = d.g


def to_top():
    # Swipe between EQ bands (x=230), so the swipe never grabs a point on the Sound tab's graph.
    for _ in range(6):
        d.adb("shell", "input", "swipe", "230", "700", "230", "1900", "400")
        time.sleep(1.2)
    # Swiping down at the top starts pull-to-refresh; let it finish before tapping.
    time.sleep(3)


def nav(label):
    """Bottom navigation: the lowest node with this label."""
    target = max((n for n in d.nodes() if n["text"] == label), key=lambda n: n["y"])
    d.tap(target["x"], target["y"], wait=1.5)
    to_top()


def read(buds, sub, payload=b""):
    return buds.request(g.READ, g.CMD_CONFIG, sub, payload).payload


def check(label, got, want):
    show = lambda v: v.hex(" ") if isinstance(v, bytes) else v  # noqa: E731
    print(f"{'PASS' if got == want else 'FAIL'}  {label}: {show(got)}" + ("" if got == want else f" (expected {show(want)})"), flush=True)


def flip_switch(label):
    c = d.control_for(label)
    d.tap(c["x"], c["y"], wait=2.5)


def tap_clear(text):
    """Tap a label, first scrolling it clear of the bottom navigation bar."""
    n = d.find(text)
    for _ in range(3):
        if n["y"] < 1650:
            break
        d.adb("shell", "input", "swipe", "230", "1500", "230", "1000", "400")
        time.sleep(1.2)
        n = d.find(text)
    d.tap(n["x"], n["y"])


def pick(current, option):
    """Tap a setting row showing `current`, then choose `option` in its sheet."""
    tap_clear(current)
    d.tap_text(option)
    time.sleep(1.5)
    to_top()


def set_slider(label, step, steps):
    """Tap a step on the slider that follows `label` (Compose sliders jump to a tapped spot)."""
    bounds = None
    for _ in range(6):
        for n in w.screen_nodes():
            if n["after"] == label and n["kind"] == "SeekBar":
                bounds = n["bounds"]
                break
        if bounds:
            break
        d.scroll(down=True)
    if not bounds:
        raise RuntimeError(f"no slider after '{label}'")
    x1, y1, x2, y2 = bounds
    # Compose leaves a thumb-radius margin at each end of the track.
    margin = 30
    x = x1 + margin + (x2 - x1 - 2 * margin) * step // (steps - 1)
    d.tap(x, (y1 + y2) // 2, wait=2.5)


def eq_gains(buds):
    p = read(buds, 0x7D, b"\x00")
    return [int.from_bytes(p[4 + 7 * i + 2:4 + 7 * i + 4], "big", signed=True) for i in range(p[0])]


buds = g.Earbuds(g.DEFAULT_ADDR, g.DEFAULT_CHANNEL)
try:
    nav("Home")
    mode_before = read(buds, 0xBE, b"\x01")
    d.tap_text("HearThrough")
    time.sleep(1.5)
    check("Mode tile HearThrough", read(buds, 0xBE, b"\x01"), bytes.fromhex("02"))

    # The HearThrough level slider appears under the tiles while HearThrough is on.
    level_before = read(buds, 0xBE, b"\x02")
    set_slider("How much you hear", 0, 5)
    check("HearThrough level least", read(buds, 0xBE, b"\x02"), bytes.fromhex("04"))
    set_slider("How much you hear", 4 - level_before[0], 5)
    check("HearThrough level restored", read(buds, 0xBE, b"\x02"), level_before)

    d.tap_text({b"\x04": "ANC", b"\x02": "HearThrough", b"\x01": "Off"}[mode_before])
    time.sleep(1.5)
    check("Mode tile restored", read(buds, 0xBE, b"\x01"), mode_before)

    flip_switch("Spatial Sound")
    check("Spatial Sound on", read(buds, 0xE4)[:2], bytes.fromhex("0505"))
    flip_switch("Spatial Sound")
    check("Spatial Sound off again", read(buds, 0xE4)[:2], bytes.fromhex("0404"))

    nav("Settings")
    # Start from whatever the user has set, change it, then put it back.
    sleep_names = {b"\x03": "15 min", b"\x06": "30 min", b"\x0c": "1 hour", b"\x18": "2 hours", b"\x00": "Never"}
    sleep_before = read(buds, 0x90)
    sleep_other = b"\x18" if sleep_before == b"\x0c" else b"\x0c"
    pick(sleep_names[sleep_before], sleep_names[sleep_other])
    check(f"Sleep after {sleep_names[sleep_other]}", read(buds, 0x90), sleep_other)
    pick(sleep_names[sleep_other], sleep_names[sleep_before])
    check(f"Sleep back to {sleep_names[sleep_before]}", read(buds, 0x90), sleep_before)

    # Sidetone volume shows under the Sidetone switch while sidetone is on.
    sidetone_before = read(buds, 0x7C)
    if sidetone_before[0] == 1:
        flip_switch("Sidetone")
    set_slider("Sidetone volume", 0, 6)
    check("Sidetone volume quietest", read(buds, 0x7C), bytes.fromhex("00f7"))
    set_slider("Sidetone volume", 5, 6)
    check("Sidetone volume loudest", read(buds, 0x7C), bytes.fromhex("0006"))
    set_slider("Sidetone volume", [-9, -6, -3, 0, 3, 6].index(int.from_bytes(sidetone_before[1:2], "big", signed=True)), 6)
    if sidetone_before[0] == 1:
        flip_switch("Sidetone")
    check("Sidetone restored", read(buds, 0x7C), sidetone_before)
    to_top()

    call_names = {b"\x00": "Neutral", b"\x01": "Bass", b"\x02": "Treble"}
    call_before = read(buds, 0x15)
    call_other = b"\x02" if call_before != b"\x02" else b"\x00"
    pick(call_names[call_before], call_names[call_other])
    check(f"In-call audio {call_names[call_other]}", read(buds, 0x15), call_other)
    pick(call_names[call_other], call_names[call_before])
    check(f"In-call audio back to {call_names[call_before]}", read(buds, 0x15), call_before)

    nav("Sound")
    eq_before = eq_gains(buds)
    # Drag the graph's bass point (the first band) to the top.
    graph = next(n for n in w.screen_nodes() if n["text"] == "Equalizer graph")
    x1, y1, x2, y2 = graph["bounds"]
    # The bass point sits 14 dp in from the left edge, at its current gain.
    density = int(d.adb("shell", "wm", "density").split()[-1]) / 160
    inset = round(14 * density)
    bass_y = (y1 + y2) // 2 - round(eq_before[0] / 450 * ((y2 - y1) / 2 - inset))
    d.adb("shell", "input", "swipe", str(x1 + inset), str(bass_y), str(x1 + inset), str(y1 - 60), "700")
    time.sleep(3)
    check("Custom EQ bass at maximum (graph drag)", eq_gains(buds)[0], 450)
    d.tap_text("Reset")
    time.sleep(3)
    check("Reset puts the curve back", eq_gains(buds), eq_before)
    to_top()
    preset = {tuple(v): k for k, v in {
        "Neutral": [0, 0, 0, 0, 0], "Bass boost": [270, -118, 0, 0, 0], "Smooth": [0, 118, 0, -118, 0],
        "Speech": [-270, 0, 0, 61, -120], "Treble boost": [-90, -118, 0, 118, 240], "Energize": [180, 0, -118, -61, 180],
    }.items()}.get(tuple(eq_before))
    if preset:
        d.tap_text(preset)
        time.sleep(2)
        check(f"EQ back to {preset}", eq_gains(buds), eq_before)
    else:
        # A custom curve isn't a tile to tap: write it back from the PC and refresh the app.
        buds.set_eq(eq_before)
        refresh = next(n for n in d.nodes() if n["text"] == "Refresh")
        d.tap(refresh["x"], refresh["y"], wait=4)
        check("EQ back to your custom curve", eq_gains(buds), eq_before)
    nav("Home")
finally:
    buds.close()
