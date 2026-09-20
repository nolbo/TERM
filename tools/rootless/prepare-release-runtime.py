#!/usr/bin/env python3
"""Fetch immutable, reviewed APK build inputs; the installed app never downloads them."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile

RELEASE = "rootless-runtime-20260920-emulated.1"
BASE = f"https://github.com/nolbo/TERM/releases/download/{RELEASE}"
ARCHIVES = {
    "rootless-runtime-arm64.tar.gz": "980a15f05085dc67e48a6fcbd08ddd6babad4428dbf161f71a9c072d71af926d",
    "rootless-runtime-source.tar.gz": "0ebb4d3ed7c1b81a27c83f894da1821f79bddf875128d07135f379d6f9c99ea4",
}
PAYLOAD = {
    "proot": "873c56f105b80c3490840f2e628920a6898f389ba82732195cc17c5df6c3b5d2",
    "proot-loader": "2567ab8ad3b418a8804696342056052eef37835e50d200e9f4ff48aae564c237",
    "libproot-faststat.so": "21e4cb846db1f6db4eccdc31b7b22b66722e4fa9ad1b9889c9749c39d4e9ab38",
}


def verify(data, expected):
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError("Rootless runtime SHA-256 mismatch")


def prepare(output, assets=None):
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="rootless-release-") as temporary:
        temporary = Path(temporary)
        for name, expected in ARCHIVES.items():
            target = temporary / name
            if assets:
                shutil.copyfile(assets / name, target)
            else:
                with urllib.request.urlopen(f"{BASE}/{name}", timeout=120) as response, target.open("wb") as stream:
                    shutil.copyfileobj(response, stream)
            verify(target.read_bytes(), expected)
        with tarfile.open(temporary / "rootless-runtime-arm64.tar.gz") as archive:
            members = archive.getmembers()
            if len(members) != len(PAYLOAD) or {m.name for m in members} != set(PAYLOAD):
                raise ValueError("Unexpected runtime archive members")
            for member in members:
                if not member.isfile() or not 0 < member.size < 8 * 1024 * 1024:
                    raise ValueError("Invalid runtime archive member")
                data = archive.extractfile(member).read()
                verify(data, PAYLOAD[member.name])
                target = output / member.name
                target.write_bytes(data)
                target.chmod(0o755)
        shutil.copyfile(temporary / "rootless-runtime-source.tar.gz", output / "rootless-runtime-source.tar.gz")
    (output / "rootless-runtime.json").write_text(json.dumps({
        "release": RELEASE, "acceleration": True, "stdioBuffering": True,
        "renameLocal": True, "emulatedLinks": True, "requiresOpenat2": False, "sha256": PAYLOAD,
    }, indent=2) + "\n")


def verify_apk(apk):
    with zipfile.ZipFile(apk) as archive:
        for name, expected in PAYLOAD.items():
            entry = "lib/arm64-v8a/" + ({"proot": "libproot.so", "proot-loader": "libproot-loader.so"}.get(name, name))
            verify(archive.read(entry), expected)
    print("Verified APK: pinned Rootless engine, loader and combined faststat/stdio DSO")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--assets-directory", type=Path)
    parser.add_argument("--verify-apk", type=Path)
    args = parser.parse_args()
    if args.verify_apk:
        verify_apk(args.verify_apk)
    elif args.output:
        prepare(args.output, args.assets_directory)
    else:
        parser.error("--output or --verify-apk is required")
