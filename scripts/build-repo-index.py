#!/usr/bin/env python3

"""Create the modern Mihon store files for this single-extension repository."""

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
    parser.add_argument("--source-info", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--repository", required=True, help="OWNER/REPOSITORY")
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--signing-key", required=True)
    parser.add_argument("--repo-name", default="CopyManga")
    parser.add_argument("--badge-label", default="COPY")
    parser.add_argument("--website", default="https://www.mangacopy.com")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    info = json.loads(args.source_info.read_text(encoding="utf-8"))
    source = info["sources"][0]
    release_base = f"https://github.com/{args.repository}/releases/download/{args.release_tag}"
    apk_url = f"{release_base}/{args.apk.name}"
    icon_url = (
        f"https://raw.githubusercontent.com/{args.repository}/{args.release_tag}/"
        f"src/zh/copymanga/res/mipmap-xhdpi/ic_launcher.png"
    )

    extension = index_pb2.Extension(
        name=info["name"],
        packageName=info["packageName"],
        resources=index_pb2.Resources(
            apkUrl=apk_url,
            iconUrl=icon_url,
            jarUrl=f"{release_base}/{args.jar.name}",
        ),
        extensionLib=info["extensionLib"],
        versionCode=int(info["versionCode"]),
        versionName=info["versionName"],
        contentWarning=int(info["contentWarning"]),
        sources=[
            index_pb2.Source(
                id=int(source["id"]),
                name=source["name"],
                language=source["lang"],
                homeUrl=source["baseUrl"],
                mirrorUrls=source.get("mirrorUrls", []),
            ),
        ],
    )
    index = index_pb2.Index(
        name=args.repo_name,
        badgeLabel=args.badge_label,
        signingKey=args.signing_key,
        contact=index_pb2.Contact(website=args.website),
        extensionList=index_pb2.ExtensionList(extensions=[extension]),
    )

    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "index.pb").write_bytes(
        gzip.compress(index.SerializeToString(deterministic=True), mtime=0),
    )
    (args.output_dir / "repo.json").write_text(
        json.dumps(
            {
                "index_v2": f"https://raw.githubusercontent.com/{args.repository}/main/index.pb",
                "meta": {
                    "name": args.repo_name,
                    "website": args.website,
                    "signingKeyFingerprint": args.signing_key,
                },
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
