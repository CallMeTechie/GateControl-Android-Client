#!/usr/bin/env python3
"""Tiny UI driver for the Play review recordings (adb + uiautomator dump).

  ui.py tap TEXT [--timeout S] [--contains] [--optional]
  ui.py wait TEXT [--timeout S] [--contains]
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
            if t:
                print(repr(t), node.get("bounds"))
        return 0
    text = argv[1]
    timeout = 20.0
    contains = "--contains" in argv
    optional = "--optional" in argv
    if "--timeout" in argv:
        timeout = float(argv[argv.index("--timeout") + 1])
    pos = wait_for(text, timeout, contains)
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
