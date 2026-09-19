#!/usr/bin/env python3
"""Bounded whole-program screen; run inside the combined-profile guest.

Times subprocess start/exec, processing and exit. Fixture creation and content
checks are excluded. Reads are warm; output is buffered, with no fsync claim.
"""
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import tempfile
import time


def main():
    with tempfile.TemporaryDirectory(prefix="terminal-stdio-applications-") as directory:
        work = Path(directory)
        source = work / "input"
        line = b"0123456789abcdef" * 4 + b"\n"
        count = 1024 * 1024
        chunk = line * 1024
        with source.open("wb") as stream:
            for _ in range(1024):
                stream.write(chunk)
        digest = hashlib.sha256()
        for _ in range(1024):
            digest.update(chunk)
        expected_hash = digest.digest()
        cases = [
            ("sed-count", ["/usr/bin/sed", "-n", "$=", str(source)]),
            ("wc-count", ["/usr/bin/wc", "-l", str(source)]),
            ("gzip", ["/usr/bin/gzip", "-1", "-c", str(source)]),
        ]
        rows = []
        for name, command in cases:
            for index, enabled in enumerate((0, 1, 1, 0, 0, 1)):
                output = work / "output"
                environment = dict(os.environ, PROOT_STDIO_BUFFER=str(enabled), LC_ALL="C")
                with output.open("wb") as stream:
                    start = time.monotonic_ns()
                    subprocess.run(command, env=environment, stdout=stream, check=True)
                    elapsed = time.monotonic_ns() - start
                if name == "gzip":
                    # Check through the installed decompressor, outside timing.
                    result = subprocess.run(["/usr/bin/gzip", "-dc", str(output)],
                                            stdout=subprocess.PIPE, check=True)
                    assert len(result.stdout) == len(line) * count
                    assert hashlib.sha256(result.stdout).digest() == expected_hash
                else:
                    assert int(output.read_bytes().split()[0]) == count
                row = dict(case=name, index=index, enabled=enabled,
                           elapsed_ms=elapsed / 1_000_000, verified=True)
                rows.append(row)
                print(json.dumps(row), flush=True)
        for name, _ in cases:
            medians = [statistics.median(row["elapsed_ms"] for row in rows
                                        if row["case"] == name and row["enabled"] == mode)
                       for mode in (0, 1)]
            print(json.dumps(dict(case=name, disabled_ms=medians[0], enabled_ms=medians[1],
                                  speedup=medians[0] / medians[1])), flush=True)


if __name__ == "__main__":
    main()
