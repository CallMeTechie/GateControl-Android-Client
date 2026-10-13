#!/usr/bin/env python3
"""Tiny UI driver for the Play review recordings (adb + uiautomator dump).

  ui.py tap TEXT [--timeout S] [--contains] [--optional] [--scroll]
  ui.py wait TEXT [--timeout S] [--contains]
  ui.py field INDEX [--timeout S]   (taps the INDEX-th text field, 0-based)
  ui.py fill INDEX TEXT [--secret]  (types TEXT into that field; checks it unless secret)
  ui.py dump

Finds a node whose text or content-desc equals (or contains) TEXT and taps
the centre of its bounds. Compose exposes its texts to uiautomator, so this
works for the app and the system dialogs alike.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*args, check=True):
    return subprocess.run(["adb", *args], capture_output=True, text=True, check=check).stdout


def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        raw = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        try:
            return ET.fromstring(raw[raw.index("<?xml"):] if "<?xml" in raw else raw)
        except (ET.ParseError, ValueError):
            time.sleep(0.5)
    return None


def find(root, text, contains):
    want = text.lower()
    exact = text.strip()
    best = None
    for node in root.iter("node"):
        for attr in ("text", "content-desc"):
            raw = (node.get(attr) or "").strip()
            val = raw.lower()
            if not val:
                continue
            if val == want or (contains and want in val):
                m = BOUNDS.match(node.get("bounds") or "")
                if not m:
                    continue
                x1, y1, x2, y2 = map(int, m.groups())
                if x2 <= x1 or y2 <= y1:
                    continue
                # Prefer the same spelling (a section label is upper case),
                # then an exact match, then the smallest node.
                rank = 0 if raw == exact else (1 if val == want else 2)
                score = (rank, (x2 - x1) * (y2 - y1))
                if best is None or score < best[0]:
                    best = (score, ((x1 + x2) // 2, (y1 + y2) // 2))
    return best[1] if best else None


def wait_for(text, timeout, contains):
    end = time.time() + timeout
    while time.time() < end:
        root = dump()
        if root is not None:
            pos = find(root, text, contains)
            if pos:
                return pos
        time.sleep(0.7)
    return None


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    cmd = argv[0]
    if cmd == "dump":
        root = dump()
        for node in (root.iter("node") if root is not None else []):
            t = node.get("text") or node.get("content-desc")
            edit = node.get("class") == "android.widget.EditText"
            if t or edit:
                print(("[field] " if edit else "") + repr(t or ""), node.get("bounds"))
        return 0
    if cmd == "fill":
        index, value = int(argv[1]), argv[2]
        secret = "--secret" in argv
        for _ in range(4):
            if main(["field", str(index), "--timeout", "10"]) != 0:
                return 1
            adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            for _ in range(3):
                adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * 20))
            time.sleep(0.4)
            adb("shell", "input", "text", value)
            time.sleep(0.8)
            if secret:
                return 0
            root = dump()
            fields = [n for n in (root.iter("node") if root is not None else []) if n.get("class") == "android.widget.EditText"]
            if len(fields) > index and (fields[index].get("text") or "").strip() == value:
                return 0
        print(f"ui.py: could not fill field {index}", file=sys.stderr)
        return 1
    if cmd == "field":
        index = int(argv[1])
        timeout = float(argv[argv.index("--timeout") + 1]) if "--timeout" in argv else 20.0
        end = time.time() + timeout
        while time.time() < end:
            root = dump()
            fields = [n for n in (root.iter("node") if root is not None else []) if n.get("class") == "android.widget.EditText"]
            if len(fields) > index:
                m = BOUNDS.match(fields[index].get("bounds") or "")
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    time.sleep(0.8)
                    return 0
            time.sleep(0.7)
        print(f"ui.py: text field {index} not found", file=sys.stderr)
        return 1
    text = argv[1]
    timeout = 20.0
    contains = "--contains" in argv
    optional = "--optional" in argv
    if "--timeout" in argv:
        timeout = float(argv[argv.index("--timeout") + 1])
    pos = wait_for(text, timeout if "--scroll" not in argv else min(timeout, 3), contains)
    if pos is None and "--scroll" in argv:
        # Scroll the page up step by step until the text shows.
        for _ in range(6):
            adb("shell", "input", "swipe", "540", "1700", "540", "900", "300")
            time.sleep(0.8)
            pos = wait_for(text, 2, contains)
            if pos:
                break
    if pos is None:
        print(f"ui.py: '{text}' not found", file=sys.stderr)
        if not optional:
            main(["dump"])
        return 0 if optional else 1
    if cmd == "tap":
        adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
        time.sleep(0.8)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
