#!/usr/bin/env python3
"""Fail if a 64-bit native library is not 16 KB page aligned.

Google Play requires apps targeting Android 15+ to support 16 KB memory
pages: every PT_LOAD segment of each 64-bit .so must be aligned to at least
16384 bytes. Accepts APK/AAB/AAR/ZIP archives, directories and .so files.
32-bit libraries are reported but not enforced (16 KB devices are 64-bit).
For APKs, uncompressed (stored) 64-bit libraries must also start at a 16 KB
offset inside the ZIP so they can be mapped directly from the APK.

Usage: check_elf_alignment.py <path> [<path> ...]
"""
import io
import os
import struct
import sys
import zipfile

MIN_ALIGN = 16384
PT_LOAD = 1


def load_alignments(data):
    """Return (is_64bit, [p_align of each PT_LOAD]) or None if not ELF."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if is64:
        phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
    else:
        phoff = struct.unpack_from(endian + "I", data, 0x1C)[0]
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x2A)
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type = struct.unpack_from(endian + "I", data, off)[0]
        if p_type != PT_LOAD:
            continue
        if is64:
            p_align = struct.unpack_from(endian + "Q", data, off + 0x30)[0]
        else:
            p_align = struct.unpack_from(endian + "I", data, off + 0x1C)[0]
        aligns.append(p_align)
    return is64, aligns


def zip_data_offset(z, info):
    """Offset of an entry's data inside the archive (after its local header)."""
    fp = z.fp
    fp.seek(info.header_offset)
    header = fp.read(30)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def check_apk_zip_alignment(path):
    """Return names of stored 64-bit libs not 16 KB aligned inside an APK."""
    bad = []
    if not path.endswith(".apk"):
        return bad
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if not info.filename.endswith(".so") or info.compress_type != zipfile.ZIP_STORED:
                continue
            if not info.filename.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
                continue
            off = zip_data_offset(z, info)
            if off % MIN_ALIGN:
                bad.append(f"{path}!{info.filename} (zip offset {off:#x})")
    return bad


def iter_apks(path):
    if os.path.isdir(path):
        for root, _, files in os.walk(path):
            for name in files:
                if name.endswith(".apk"):
                    yield os.path.join(root, name)
    elif path.endswith(".apk"):
        yield path


def iter_libs(path):
    if os.path.isdir(path):
        for root, _, files in os.walk(path):
            for name in files:
                full = os.path.join(root, name)
                if name.endswith(".so"):
                    with open(full, "rb") as f:
                        yield full, f.read()
                elif name.endswith((".apk", ".aab", ".aar", ".zip")):
                    yield from iter_libs(full)
    elif path.endswith(".so"):
        with open(path, "rb") as f:
            yield path, f.read()
    else:
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                if info.filename.endswith(".so"):
                    yield f"{path}!{info.filename}", z.read(info)
                elif info.filename.endswith((".aar", ".apk")):
                    inner = io.BytesIO(z.read(info))
                    with zipfile.ZipFile(inner) as iz:
                        for ii in iz.infolist():
                            if ii.filename.endswith(".so"):
                                yield f"{path}!{info.filename}!{ii.filename}", iz.read(ii)


def main(paths):
    if not paths:
        print(__doc__)
        return 2
    failures = 0
    checked = 0
    for p in paths:
        for name, data in iter_libs(p):
            res = load_alignments(data)
            if res is None:
                continue
            is64, aligns = res
            checked += 1
            worst = min(aligns) if aligns else 0
            ok = worst >= MIN_ALIGN
            tag = "OK  " if ok else ("FAIL" if is64 else "32bit")
            print(f"{tag} align={worst:#x} {name}")
            if is64 and not ok:
                failures += 1
    for p in paths:
        for apk in iter_apks(p):
            for bad in check_apk_zip_alignment(apk):
                print(f"FAIL zip-align {bad}")
                failures += 1
    if checked == 0:
        print("::error::no native libraries found")
        return 1
    if failures:
        print(f"::error::{failures} 64-bit native librar{'y is' if failures == 1 else 'ies are'} not 16 KB aligned")
        return 1
    print(f"All {checked} native libraries checked; 64-bit libraries are 16 KB aligned.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
