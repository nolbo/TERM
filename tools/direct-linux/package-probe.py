#!/usr/bin/env python3
"""Add a fixed probe to the standalone instrumentation APK, never to the app.

Signing is deliberately a separate step; the input APK is never modified.
"""
import argparse
from pathlib import Path
import struct
import zipfile


def package(source, probe, output):
    if output.resolve() in (source.resolve(), probe.resolve()):
        raise ValueError("Output must differ from both inputs")
    data = probe.read_bytes()
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
    with zipfile.ZipFile(source) as original:
        # Do not accidentally repackage the production application.
        if b"dev.terminal.runtime.test" not in original.read("AndroidManifest.xml") and \
                "dev.terminal.runtime.test".encode("utf-16le") not in original.read("AndroidManifest.xml"):
            raise ValueError("Expected the standalone runtime instrumentation APK")
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as target:
            name = "lib/arm64-v8a/libdirect-linux-probe.so"
            for info in original.infolist():
                if info.filename == name or info.filename.startswith("META-INF/"):
                    continue
                target.writestr(info, original.read(info.filename))
            target.writestr(name, data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("probe", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    package(args.source, args.probe, args.output)
