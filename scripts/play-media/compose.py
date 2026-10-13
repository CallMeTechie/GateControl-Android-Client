#!/usr/bin/env python3
"""Burns the captions into the recorded segments and joins them per video
(.github/workflows/play-videos.yml). A video longer than its Play limit is
sped up evenly to fit.

  compose.py OUT_DIR
"""
import os
import re
import subprocess
import sys
import textwrap

FONT = os.environ.get("CAPTION_FONT", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")
FFMPEG = os.environ.get("FFMPEG", "ffmpeg")
STILL_SECONDS = 3.5
LIMITS = {"vpn": 88.0, "location": 29.5, "fgs": 29.5}
NAMES = {
    "vpn": "gatecontrol-vpn-service.mp4",
    "location": "gatecontrol-background-location.mp4",
    "fgs": "gatecontrol-foreground-service.mp4",
}


def run(*cmd):
    if cmd and cmd[0] == "ffmpeg":
        cmd = (FFMPEG,) + cmd[1:]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        tail = " | ".join(r.stderr.strip().splitlines()[-6:])
        print(f"::error title=ffmpeg failed::{tail}")
        raise SystemExit(1)


def probe(path):
    """(has video, seconds) of [path] — ffmpeg only, no ffprobe."""
    r = subprocess.run([FFMPEG, "-hide_banner", "-i", path], capture_output=True, text=True)
    m = re.search(r"Duration: (\d+):(\d+):(\d+(?:\.\d+)?)", r.stderr)
    secs = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3)) if m else 0.0
    return "Video:" in r.stderr, secs


def duration(path):
    return probe(path)[1]


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
            if not os.path.exists(src):
                print(f"::warning title=compose::skip missing segment {name}")
                continue
            has_video, secs = probe(src)
            if not has_video:
                print(f"::warning title=compose::skip empty segment {name}")
                continue
            if secs < 0.3:
                # screenrecord writes a single frame when nothing on screen moves.
                frame = os.path.join(work, name + ".png")
                still = os.path.join(work, name + "-still.mp4")
                run("ffmpeg", "-y", "-i", src, "-frames:v", "1", "-update", "1", frame)
                run("ffmpeg", "-y", "-loop", "1", "-t", str(STILL_SECONDS), "-i", frame,
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-r", "30", still)
                src = still
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
            if duration(dst) < 0.3:
                print(f"::warning title=compose::segment {name} has no frames after encoding, skipped")
                continue
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
