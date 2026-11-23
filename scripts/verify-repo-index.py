#!/usr/bin/env python3

"""Verify the generated Mihon repository files describe one release consistently."""

from __future__ import annotations

import argparse
import gzip
import json
from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR.parent / ".github" / "scripts"))
import index_pb2  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--index-dir", type=Path, required=True)
    parser.add_argument("--source-info", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--repository", required=True, help="OWNER/REPOSITORY")
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--signing-key", required=True)
    return parser.parse_args()


def read_index(directory: Path) -> index_pb2.Index:
    return index_pb2.Index.FromString(
        gzip.decompress((directory / "index.pb").read_bytes()),
    )


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def main() -> int:
    args = parse_args()
    info = json.loads(args.source_info.read_text(encoding="utf-8"))
    require(args.release_tag == f"v{info['versionName']}", "release tag does not match source-info")
    base_name = f"tachiyomi-{info['module']}-{args.release_tag}"
    for artifact, suffix in ((args.apk, ".apk"), (args.jar, ".jar")):
        require(artifact.is_file(), f"missing release artifact: {artifact}")
        require(artifact.name == base_name + suffix, f"invalid release artifact name: {artifact}")
    sources = info.get("sources") or []
    require(len(sources) == 1, "source-info must contain exactly one source")
    source = sources[0]
    index = read_index(args.index_dir)

    extensions = list(index.extensionList.extensions)
    require(len(extensions) == 1, "the single-extension index must contain exactly one extension")
    extension = extensions[0]
    require(extension.name == info["name"], "index extension name does not match source-info")
    require(extension.packageName == info["packageName"], "index package does not match source-info")
    require(extension.extensionLib == info["extensionLib"], "index library does not match source-info")
    require(extension.versionCode == int(info["versionCode"]), "index version code does not match")
    require(extension.versionName == info["versionName"], "index version name does not match")
    require(extension.contentWarning == int(info["contentWarning"]), "index content warning does not match")

    indexed_sources = list(extension.sources)
    require(len(indexed_sources) == 1, "index extension must contain exactly one source")
    indexed_source = indexed_sources[0]
    require(indexed_source.id == int(source["id"]), "index source ID does not match")
    require(indexed_source.name == source["name"], "index source name does not match")
    require(indexed_source.language == source["lang"], "index source language does not match")
    require(indexed_source.homeUrl == source["baseUrl"], "index source URL does not match")

    release_base = f"https://github.com/{args.repository}/releases/download/{args.release_tag}"
    require(
        extension.resources.iconUrl
        == f"https://raw.githubusercontent.com/{args.repository}/{args.release_tag}/"
        "src/zh/copymanga/res/mipmap-xhdpi/ic_launcher.png",
        "index icon URL does not point to the tagged source icon",
    )
    require(
        extension.resources.apkUrl == f"{release_base}/{args.apk.name}",
        "index APK URL does not match the release asset",
    )
    require(
        extension.resources.jarUrl == f"{release_base}/{args.jar.name}",
        "index JAR URL does not match the release asset",
    )
    require(index.signingKey == args.signing_key, "index signing fingerprint does not match")

    repo = json.loads((args.index_dir / "repo.json").read_text(encoding="utf-8"))
    require(
        repo["index_v2"] == f"https://raw.githubusercontent.com/{args.repository}/main/index.pb",
        "repo.json index_v2 does not point to the public main branch",
    )
    require(repo["meta"]["signingKeyFingerprint"] == args.signing_key, "repo.json signing key does not match")

    print(f"repository index check: PASS ({args.index_dir})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"repository index check: FAIL: {error}", file=sys.stderr)
        raise SystemExit(1) from error
