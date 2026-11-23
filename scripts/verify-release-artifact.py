"""Verify the release JAR and, when supplied, the APK against Gradle source metadata."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree
import zipfile


ANDROID = "{http://schemas.android.com/apk/res/android}"
FORBIDDEN_ENTRY_MARKERS = (
    "android/webkit",
    "app/cash/quickjs",
    "quickjs",
    "webview",
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--source-info", type=Path)
    parser.add_argument("--aapt", type=Path)
    args = parser.parse_args()

    if any((args.apk, args.source_info, args.aapt)) and not all((args.apk, args.source_info, args.aapt)):
        parser.error("--apk, --source-info and --aapt must be provided together")

    with zipfile.ZipFile(args.jar) as archive:
        entries = archive.namelist()
        manifest = ElementTree.fromstring(archive.read("AndroidManifest.xml"))

    forbidden = [
        entry
        for entry in entries
        if any(marker in entry.casefold() for marker in FORBIDDEN_ENTRY_MARKERS)
    ]
    if forbidden:
        print("unsupported runtime entries found in release JAR:", file=sys.stderr)
        print("\n".join(forbidden), file=sys.stderr)
        return 1

    if args.apk:
        info = json.loads(args.source_info.read_text(encoding="utf-8"))
        expected = (info["packageName"], str(info["versionCode"]), info["versionName"])
        stem = f"tachiyomi-{info['module']}-v{expected[2]}"
        if args.apk.name != f"{stem}.apk" or args.jar.name != f"{stem}.jar":
            raise ValueError("APK and JAR filenames must match the Gradle source version")
        jar_info = (manifest.get("package"), manifest.get(ANDROID + "versionCode"), manifest.get(ANDROID + "versionName"))
        badging = subprocess.check_output([args.aapt, "dump", "badging", args.apk], encoding="utf-8")
        apk_info = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
        if jar_info != expected or not apk_info or apk_info.groups() != expected:
            raise ValueError("APK or JAR embedded package/version does not match Gradle source metadata")
        print(f"APK and JAR package/version check: PASS ({expected[0]} {expected[2]}, code {expected[1]})")

    print(f"release JAR dependency check: PASS ({args.jar})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError, ElementTree.ParseError, zipfile.BadZipFile) as error:
        print(f"release artifact check: FAIL: {error}", file=sys.stderr)
        raise SystemExit(1) from error
