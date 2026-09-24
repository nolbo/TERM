#!/usr/bin/env python3
"""Add a fixed probe to the standalone instrumentation APK, never to the app.

Signing is deliberately a separate step; the input APK is never modified.
"""
import argparse
from pathlib import Path
import struct
import zipfile


def validate_elf(data):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("Expected a little-endian ARM64 ELF")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size < 56 or not count or offset + size * count > len(data):
        raise ValueError("Invalid ELF program headers")
    for i in range(count):
        header = offset + size * i
        if struct.unpack_from("<I", data, header)[0] == 1:
            if struct.unpack_from("<Q", data, header + 48)[0] < 16384:
                raise ValueError("LOAD segment alignment is below 16 KiB")


def package(source, probe, output, loader=None, fixtures=None):
    inputs = [source, probe] + ([loader] if loader else [])
    if output.resolve() in [p.resolve() for p in inputs]:
        raise ValueError("Output must differ from all inputs")
    data = probe.read_bytes()
    validate_elf(data)
    loader_data = loader.read_bytes() if loader else None
    if loader_data is not None:
        validate_elf(loader_data)
    with zipfile.ZipFile(source) as original:
        # Do not accidentally repackage the production application.
        if b"dev.terminal.runtime.test" not in original.read("AndroidManifest.xml") and \
                "dev.terminal.runtime.test".encode("utf-16le") not in original.read("AndroidManifest.xml"):
            raise ValueError("Expected the standalone runtime instrumentation APK")
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as target:
            name = "lib/arm64-v8a/libdirect-linux-probe.so"
            for info in original.infolist():
                if info.filename in (name, "lib/arm64-v8a/libdirect-linux-loader.so") or \
                        info.filename.startswith(("META-INF/", "assets/direct-linux-fixtures/")):
                    continue
                target.writestr(info, original.read(info.filename))
            target.writestr(name, data)
            if loader_data is not None:
                target.writestr("lib/arm64-v8a/libdirect-linux-loader.so", loader_data)
            if fixtures:
                for path in sorted(fixtures.rglob("*")):
                    if path.is_symlink():
                        raise ValueError("Fixture links must be created by the test, not copied from the host")
                    if path.is_file():
                        target.write(path, "assets/direct-linux-fixtures/" + str(path.relative_to(fixtures)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("probe", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--loader", type=Path)
    parser.add_argument("--fixtures", type=Path)
    args = parser.parse_args()
    package(args.source, args.probe, args.output, args.loader, args.fixtures)
