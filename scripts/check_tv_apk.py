#!/usr/bin/env python3
"""Check the GateControl TV APK: 32-bit ARM and size.

The Fire TV Stick 4K Max (Fire OS 7) has a 32-bit system: an APK without
armeabi-v7a libraries is refused (INSTALL_FAILED_NO_MATCHING_ABIS). Every
native library must therefore ship for armeabi-v7a, and the APK may only
carry armeabi-v7a and arm64-v8a. Prints the size; --max-mb fails above it.

Usage: check_tv_apk.py <tv.apk> [--max-mb N]
"""
import argparse
import os
import sys
import zipfile

ALLOWED = {"armeabi-v7a", "arm64-v8a"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--max-mb", type=float, default=None)
    args = parser.parse_args()

    libs = {}
    with zipfile.ZipFile(args.apk) as z:
        for name in z.namelist():
            parts = name.split("/")
            if len(parts) == 3 and parts[0] == "lib" and parts[2].endswith(".so"):
                libs.setdefault(parts[1], set()).add(parts[2])

    errors = []
    size_mb = os.path.getsize(args.apk) / (1024 * 1024)
    print(f"{args.apk}: {size_mb:.1f} MB")
    for abi in sorted(libs):
        print(f"  lib/{abi}: {', '.join(sorted(libs[abi]))}")
    other = set(libs) - ALLOWED
    if other:
        errors.append(f"unexpected ABIs: {', '.join(sorted(other))}")
    if libs and "armeabi-v7a" not in libs:
        errors.append("no armeabi-v7a libraries: the Fire TV Stick 4K Max (32-bit) would refuse the APK")
    missing = set().union(*libs.values()) - libs.get("armeabi-v7a", set()) if libs else set()
    if missing:
        errors.append(f"only 64-bit, missing for armeabi-v7a: {', '.join(sorted(missing))}")
    if args.max_mb is not None and size_mb > args.max_mb:
        errors.append(f"{size_mb:.1f} MB is more than {args.max_mb:g} MB")

    for e in errors:
        print(f"::error::GateControl TV APK: {e}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
