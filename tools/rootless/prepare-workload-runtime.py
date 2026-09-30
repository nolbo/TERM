#!/usr/bin/env python3
"""Prepare pinned local workload-profile inputs without downloads or publishing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile

PROFILE = "workload-local-20260930"
PAYLOAD = {
    "proot": "85dc5a9a527dba926ab496e5e7929ba8ded4e6dbd7da5f77a072ee0af255af42",
    "proot-loader": "2567ab8ad3b418a8804696342056052eef37835e50d200e9f4ff48aae564c237",
    "libproot-faststat.so": "619bc9a2c3270fb89f787b5e20003adbe484a1f33843cd8e382584eb6fa2a406",
}
BUILD_INPUTS = {
    "v5.1.107.92.zip": "29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135",
    "libtalloc_2.4.3_aarch64.deb": "ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da",
    "libtalloc-static_2.4.3_aarch64.deb": "a2f3bb400395520cc1380626907e61ec59bd072ec2209fbff9bf21e167c5be2e",
    "libandroid-shmem_0.7_aarch64.deb": "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6",
    "libandroid-shmem-static_0.7_aarch64.deb": "333b092c98b2da88837e1ae41aadb05096761f4a4b4530c85d99f22dc8bdb317",
}


def verify_digest(name, data, expected):
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise ValueError(f"Unexpected SHA-256 for {name}: {actual}")


def verify_payload(name, data):
    verify_digest(name, data, PAYLOAD[name])
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01" or data[18:20] != b"\xb7\x00":
        raise ValueError(f"{name} is not a little-endian ARM64 ELF")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or count == 0 or offset + entry_size * count > len(data):
        raise ValueError(f"Invalid ELF program headers for {name}")
    loads = 0
    for index in range(count):
        entry = offset + index * entry_size
        if struct.unpack_from("<I", data, entry)[0] == 1:
            loads += 1
            if struct.unpack_from("<Q", data, entry + 48)[0] < 16384:
                raise ValueError(f"{name} has a LOAD segment below 16 KiB alignment")
    if not loads:
        raise ValueError(f"Missing ELF LOAD segments for {name}")


def prepare(output, engine_directory, preload=None):
    if output.exists():
        raise FileExistsError("Output must be a new directory; existing build inputs are preserved")
    sources = {name: engine_directory / name for name in PAYLOAD}
    if preload is not None:
        sources["libproot-faststat.so"] = preload
    verified = {}
    for name, source in sources.items():
        data = source.read_bytes()
        verify_payload(name, data)
        verified[name] = data
    output.mkdir(parents=True)
    for name, data in verified.items():
        target = output / name
        target.write_bytes(data)
        target.chmod(0o755)
    (output / "rootless-runtime.json").write_text(json.dumps({
        "profile": PROFILE, "acceleration": True, "stdioBuffering": True,
        "workloadAcceleration": True, "fdUtimensDirect": True, "sha256": PAYLOAD,
    }, indent=2) + "\n")
    print(f"Verified local Rootless workload payload: {output.resolve()}")


def build_and_prepare(output, input_cache):
    if output.exists():
        raise FileExistsError("Output must be a new directory; existing build inputs are preserved")
    repository = Path(__file__).resolve().parents[2]
    with tempfile.TemporaryDirectory(prefix="rootless-workload-") as temporary:
        temporary = Path(temporary)
        cache = temporary / "inputs"
        cache.mkdir()
        # Freeze every verified upstream input locally before invoking the
        # existing builder, so its download fallback is never needed.
        for name, expected in BUILD_INPUTS.items():
            data = (input_cache / name).read_bytes()
            verify_digest(name, data, expected)
            (cache / name).write_bytes(data)
        environment = {key: value for key, value in os.environ.items() if not key.startswith("PROOT_")}
        environment.update(PROOT_DOWNLOAD_CACHE=str(cache), PROOT_BUILD_FASTSTAT="1",
                           PROOT_BUILD_RENAME_LOCAL="1", PROOT_BUILD_STDIO="1")
        built = temporary / "payload"
        subprocess.run(["bash", str(repository / "tools/rootless/build-proot-arm64.sh"), str(built)],
                       env=environment, check=True)
        subprocess.run(["bash", str(repository / "tools/rootless/faststat/build.sh"), str(built)],
                       env=environment, check=True)
        prepare(output, built)


def verify_apk(apk):
    with zipfile.ZipFile(apk) as archive:
        for name in PAYLOAD:
            entry = "lib/arm64-v8a/" + {"proot": "libproot.so", "proot-loader": "libproot-loader.so"}.get(name, name)
            if not 0 < archive.getinfo(entry).file_size < 8 * 1024 * 1024:
                raise ValueError(f"Invalid APK payload size: {name}")
            verify_payload(name, archive.read(entry))
    print("Verified APK: pinned local workload engine, loader and combined faststat/stdio DSO")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--engine-directory", type=Path)
    mode.add_argument("--build", action="store_true")
    mode.add_argument("--verify-apk", type=Path)
    parser.add_argument("--preload", type=Path, help="Local combined DSO; defaults to engine-directory/libproot-faststat.so")
    parser.add_argument("--input-cache", type=Path, help="Complete existing pinned upstream inputs; no downloads")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.verify_apk:
        if args.output or args.preload or args.input_cache:
            parser.error("--verify-apk cannot be combined with preparation arguments")
        verify_apk(args.verify_apk)
    elif not args.output:
        parser.error("--output is required for preparation")
    elif args.build:
        if args.preload or not args.input_cache:
            parser.error("--build requires --input-cache and builds its own combined DSO")
        build_and_prepare(args.output, args.input_cache)
    else:
        if args.input_cache:
            parser.error("--input-cache requires --build")
        prepare(args.output, args.engine_directory, args.preload)


if __name__ == "__main__":
    main()
