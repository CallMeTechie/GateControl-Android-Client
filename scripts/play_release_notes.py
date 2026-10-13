#!/usr/bin/env python3
"""Release notes for Google Play from CHANGELOG.md.

Writes the bullet points of the newest CHANGELOG section ("## [x.y.z] ...")
as fastlane `changelogs/default.txt` for de-DE and en-US. Play allows 500
characters per language; longer notes are cut at a line break.

Usage: play_release_notes.py <CHANGELOG.md> <metadata dir> [version]
(version: take that section instead of the newest one)
"""
import pathlib
import re
import sys

LIMIT = 500


def latest_notes(changelog: str, version: str = "") -> str:
    sections = re.split(r"^## \[", changelog, flags=re.M)[1:]
    if version:
        sections = [s for s in sections if s.startswith(version + "]")]
    if not sections:
        return ""
    body = sections[0].split("\n", 1)[1] if "\n" in sections[0] else ""
    body = body.split("\n---", 1)[0]
    lines = [l.strip() for l in body.splitlines() if l.strip().startswith("- ")]
    return "\n".join("• " + l[2:].strip() for l in lines)


def cap(text: str, limit: int = LIMIT) -> str:
    if len(text) <= limit:
        return text
    out = ""
    for line in text.splitlines():
        if len(out) + len(line) + 1 > limit - 2:
            return (out + "\n…").strip()
        out += line + "\n"
    return out.strip()


def main() -> int:
    changelog, metadata = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    version = sys.argv[3].lstrip("v") if len(sys.argv) > 3 else ""
    notes = cap(latest_notes(changelog.read_text(encoding="utf-8"), version)) or "•"
    for lang in ("de-DE", "en-US"):
        target = metadata / lang / "changelogs" / "default.txt"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(notes + "\n", encoding="utf-8")
    print(notes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
