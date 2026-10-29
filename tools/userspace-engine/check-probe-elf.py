#!/usr/bin/env python3
"""Check the freestanding probe artifact, without running it or using ldd."""
import pathlib
import struct
import subprocess
import sys

path = pathlib.Path(sys.argv[1])
data = path.read_bytes()
assert data[:7] == b"\x7fELF\x02\x01\x01", "not ELF64 little endian"
kind, machine = struct.unpack_from("<HH", data, 16)
assert machine == 183 and kind == 2, "expected ARM64 fixed-address probe"
offset = struct.unpack_from("<Q", data, 32)[0]
size, count = struct.unpack_from("<HH", data, 54)
assert size == 56
loads = 0
for index in range(count):
    tag, flags, _, _, _, _, _, align = struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
    assert tag not in (2, 3, 7), "dynamic/interpreter/TLS dependency in freestanding probe"
    assert flags & 3 != 3, "writable executable segment"
    if tag == 1:
        loads += 1
        assert align >= 16384, "PT_LOAD is not 16 KiB aligned"
assert loads > 0
symbols = subprocess.check_output(["readelf", "-Ws", str(path)], text=True)
undefined = [line for line in symbols.splitlines() if " UND " in line and len(line.split()) > 7]
assert not undefined, f"undefined runtime symbols: {undefined}"
assert "ue_host_gate_ip" in symbols and "ue_sigreturn" in symbols
print("PASS: ARM64 static ELF, no interpreter/dynamic/TLS/undefined symbols, W^X and 16 KiB segments")
