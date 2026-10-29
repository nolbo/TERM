#!/usr/bin/env python3
"""Actual ARM64/glibc loader tests. No distribution/VFS/performance claims."""
import hashlib
import json
import os
import pathlib
import struct
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOADER = ROOT / "native/userspace-engine/target/aarch64-unknown-linux-gnu/release/ue-loader-probe"
SOURCE = ROOT / "native/userspace-engine/tests/loader-fixture.c"
ENV = {"LC_ALL": "C", "UE_FIXTURE": "preserved=value"}
ARGS = ["two words", "", "한글"]


def run(argv, env=ENV):
    return subprocess.run([str(a) for a in argv], env=env, capture_output=True, timeout=15)


def phdrs(data):
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    assert size == 56
    return [offset + index * size for index in range(count)]


def main():
    results = []
    with tempfile.TemporaryDirectory(prefix="ue-loader-") as directory:
        work = pathlib.Path(directory)
        fixture = None
        for name, flags in [
            ("dynamic-pie", ["-fPIE", "-pie", "-DDYNAMIC_FIXTURE", "-ldl"]),
            ("dynamic-exec", ["-fno-pie", "-no-pie", "-DDYNAMIC_FIXTURE", "-ldl"]),
            ("static-exec", ["-static", "-fno-pie", "-no-pie"]),
            ("static-pie", ["-static-pie", "-fPIE"]),
        ]:
            binary = work / name
            subprocess.run([os.environ.get("CC", "gcc"), "-O2", "-Wall", "-Wextra", "-Werror",
                str(SOURCE), "-o", str(binary), *flags], check=True)
            baseline = run([binary, *ARGS])
            assert baseline.returncode == 37 and baseline.stdout.startswith(b"PASS "), (name, baseline)
            for mode in ["native-syscalls", "probe-trap"]:
                prefix = [LOADER] + (["--probe-trap"] if mode == "probe-trap" else [])
                env = {**ENV, **({"UE_TRAP_EXPECTED": "1"} if mode == "probe-trap" else {})}
                actual = run([*prefix, binary, *ARGS], env)
                assert (actual.returncode, actual.stdout, actual.stderr) == (
                    baseline.returncode, baseline.stdout, baseline.stderr), (name, mode, actual)
                results.append({"case": name, "mode": mode, "status": "pass",
                    "sha256": hashlib.sha256(binary.read_bytes()).hexdigest()})
            if name == "dynamic-pie":
                fixture = binary.read_bytes()
        assert fixture is not None
        echo = run([LOADER, "/bin/echo", "loader", "glibc"])
        assert (echo.returncode, echo.stdout, echo.stderr) == (0, b"loader glibc\n", b""), echo
        results.append({"case": "system-echo", "status": "pass"})
        identity = run([LOADER, "--probe-trap", "/usr/bin/id", "-u"])
        assert (identity.returncode, identity.stdout, identity.stderr) == (0, b"12345\n", b""), identity
        results.append({"case": "system-id-trap", "status": "pass"})

        def reject(name, data, message, mode=0o700):
            path = work / name
            path.write_bytes(data)
            path.chmod(mode)
            result = run([LOADER, path])
            assert result.returncode == 126 and message in result.stderr, (name, result)
            assert not result.stdout, (name, result)
            results.append({"case": name, "status": "pass"})

        reject("truncated", fixture[:32], b"Elf(Truncated)")
        reject("non-executable", fixture, b"Host(-13)", 0o600)
        reject("setuid", fixture, b"Host(-13)", 0o4700)
        fifo = work / "fifo"
        os.mkfifo(fifo, 0o700)
        for name, path in [("fifo-no-block", fifo), ("directory", work)]:
            result = run([LOADER, path])
            assert result.returncode == 126 and b"Host(-13)" in result.stderr, (name, result)
            results.append({"case": name, "status": "pass"})
        bad = bytearray(fixture)
        struct.pack_into("<H", bad, 18, 62)
        reject("wrong-architecture", bad, b"Elf(Architecture)")
        headers = phdrs(fixture)
        loads = [h for h in headers if struct.unpack_from("<I", fixture, h)[0] == 1]
        bad = bytearray(fixture)
        struct.pack_into("<I", bad, loads[0] + 4, 7)
        reject("wx-segment", bad, b"Elf(UnsupportedProtection)")
        bad = bytearray(fixture)
        stack = next(h for h in headers if struct.unpack_from("<I", fixture, h)[0] == 0x6474E551)
        struct.pack_into("<I", bad, stack + 4, 7)
        reject("executable-stack", bad, b"Elf(UnsupportedProtection)")
        bad = bytearray(fixture)
        interp = next(h for h in headers if struct.unpack_from("<I", fixture, h)[0] == 3)
        offset = struct.unpack_from("<Q", fixture, interp + 8)[0]
        bad[offset:offset+9] = b"/missing\0"
        reject("missing-interpreter", bad, b"Host(-2)")
        # Create an otherwise valid ET_EXEC whose first LOAD collides with our
        # running loader. NOREPLACE must report EEXIST, not clobber the loader.
        bootstrap = LOADER.read_bytes()
        boot_load = next(h for h in phdrs(bootstrap) if struct.unpack_from("<I", bootstrap, h)[0] == 1)
        address = struct.unpack_from("<Q", bootstrap, boot_load + 16)[0]
        assert address % 65536 == 0
        bad = bytearray(fixture)
        struct.pack_into("<H", bad, 16, 2)
        struct.pack_into("<Q", bad, 24, struct.unpack_from("<Q", bad, 24)[0] + address)
        for h in loads:
            struct.pack_into("<Q", bad, h+16, struct.unpack_from("<Q", bad, h+16)[0] + address)
        reject("address-collision", bad, b"Host(-17)")
    report = {"scope": "ARM64 host loader, not Android policy or engine superiority",
        "page_size": os.sysconf("SC_PAGE_SIZE"), "loader_sha256": hashlib.sha256(LOADER.read_bytes()).hexdigest(),
        "cases": results}
    if len(sys.argv) == 2:
        pathlib.Path(sys.argv[1]).write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: {len(results)} loader cases; dynamic/static ET_EXEC and PIE, trap, rejection")


if __name__ == "__main__":
    main()
