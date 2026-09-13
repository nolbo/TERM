#!/usr/bin/env python3
"""Generate public release metadata from the built APK, not from a mutable ref."""
import argparse
import hashlib
import json
from pathlib import Path
import re

MAX_APK_BYTES = 128 * 1024 * 1024


def create_manifest(apk: Path, metadata: dict, commit: str) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid main commit")
    if metadata.get("applicationId") != "dev.terminal.app" or len(metadata.get("elements", [])) != 1:
        raise ValueError("Expected one TERM APK")
    element = metadata["elements"][0]
    code = element["versionCode"]
    if type(code) is not int or not 1 <= code <= 2147483647:
        raise ValueError("Invalid version code")
    name = element["versionName"]
    if not isinstance(name, str) or len(name) > 96 or not re.fullmatch(
        rf"[0-9]+\.[0-9]+\.[0-9]+\+main\.{code}\.{commit[:12]}", name
    ):
        raise ValueError("APK version does not match the main commit")
    if element.get("outputFile") != apk.name:
        raise ValueError("APK does not match build metadata")
    min_sdk = metadata["minSdkVersionForDexing"]
    if type(min_sdk) is not int or not 1 <= min_sdk <= 1000:
        raise ValueError("Invalid minimum SDK")
    size = apk.stat().st_size
    if not 1 <= size <= MAX_APK_BYTES:
        raise ValueError("APK size exceeds the download policy")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return dict(schemaVersion=1, applicationId="dev.terminal.app", commit=commit,
                versionCode=code, versionName=name, minSdk=min_sdk, size=size,
                sha256=digest.hexdigest())


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    manifest = create_manifest(args.apk, json.loads(args.metadata.read_text()), args.commit)
    args.output.write_text(json.dumps(manifest, indent=2) + "\n")
