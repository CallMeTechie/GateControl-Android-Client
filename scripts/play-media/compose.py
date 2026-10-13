#!/usr/bin/env python3
"""Burns the captions into the recorded segments and joins them per video
(.github/workflows/play-videos.yml). A video longer than its Play limit is
sped up evenly to fit.

  compose.py OUT_DIR
"""
import json
import os
import subprocess
import sys
import textwrap

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
LIMITS = {"vpn": 88.0, "location": 29.5, "fgs": 29.5}
NAMES = {
    "vpn": "gatecontrol-vpn-service.mp4",
    "location": "gatecontrol-background-location.mp4",
    "fgs": "gatecontrol-foreground-service.mp4",
}


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        tail = " | ".join(r.stderr.strip().splitlines()[-6:])
        print(f"::error title=ffmpeg failed::{tail}")
        raise SystemExit(1)


def duration(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", path],
                         check=True, capture_output=True, text=True).stdout
    return float(json.loads(out)["format"]["duration"])


def main(out):
    seg_dir = os.path.join(out, "segments")
    work = os.path.join(out, "work")
    os.makedirs(work, exist_ok=True)
    videos = {}
    with open(os.path.join(out, "segments.tsv"), encoding="utf-8") as f:
        for line in f:
            video, name, caption = line.rstrip("\n").split("\t", 2)
            videos.setdefault(video, []).append((name, caption))

    for video, segs in videos.items():
        parts = []
        for name, caption in segs:
            src = os.path.join(seg_dir, name + ".mp4")
            if not os.path.exists(src) or os.path.getsize(src) < 1000:
                print(f"::warning title=compose::skip missing segment {name}")
                continue
            cap = os.path.join(work, name + ".txt")
            with open(cap, "w", encoding="utf-8") as c:
                c.write("\n".join(textwrap.wrap(caption, 34)))
            dst = os.path.join(work, name + ".mp4")
            vf = (
                "scale=720:-2,fps=30,format=yuv420p,"
                f"drawtext=fontfile={FONT}:textfile={cap}:fontsize=30:fontcolor=white:"
                "line_spacing=8:box=1:boxcolor=black@0.72:boxborderw=22:"
                "x=(w-text_w)/2:y=h-text_h-190"
            )
            run("ffmpeg", "-y", "-i", src, "-vf", vf, "-an", "-c:v", "libx264", "-preset", "veryfast",
                "-crf", "20", "-r", "30", dst)
            parts.append(dst)
        if not parts:
            continue
        listing = os.path.join(work, video + ".txt")
        with open(listing, "w") as l:
            l.writelines(f"file '{p}'\n" for p in parts)
        joined = os.path.join(work, video + "-joined.mp4")
        run("ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", listing, "-c", "copy", joined)
        total = duration(joined)
        final = os.path.join(out, NAMES.get(video, video + ".mp4"))
        limit = LIMITS.get(video, 88.0)
        if total > limit:
            factor = total / limit
            run("ffmpeg", "-y", "-i", joined, "-vf", f"setpts=PTS/{factor:.4f}", "-an", "-c:v", "libx264",
                "-preset", "veryfast", "-crf", "20", "-r", "30", final)
        else:
            os.replace(joined, final)
        print(f"::notice title={os.path.basename(final)}::{duration(final):.1f} s (raw {total:.1f} s, {len(parts)} segments)")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
