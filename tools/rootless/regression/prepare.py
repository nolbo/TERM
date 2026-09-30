#!/usr/bin/env python3
"""Build an isolated ARM64 Rootless regression root from existing host inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--preload", required=True, type=Path)
    args = parser.parse_args()
    if platform.machine() != "aarch64":
        parser.error("existing ARM64 glibc host toolchain required")
    if args.output.exists():
        parser.error("output must be a new disposable directory")
    root = args.output.resolve()
    root.mkdir(parents=True)
    source = Path(__file__).resolve().parent
    for name in ("bin", "dev", "proc", "tmp", "lib/aarch64-linux-gnu", "usr/local/lib"):
        (root / name).mkdir(parents=True, exist_ok=True)
    mapping = {"bin/env": "/usr/bin/env", "bin/true": "/usr/bin/true",
               "lib/ld-linux-aarch64.so.1": "/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
               "lib/aarch64-linux-gnu/libc.so.6": "/usr/lib/aarch64-linux-gnu/libc.so.6",
               "lib/aarch64-linux-gnu/libgcc_s.so.1": "/usr/lib/aarch64-linux-gnu/libgcc_s.so.1",
               "usr/local/lib/terminal-faststat.so": args.preload}
    for destination, original in mapping.items():
        shutil.copy2(original, root / destination)
    for program in ("concurrency", "fork", "pin-fork", "restart", "scope"):
        for kind in ("static", "dynamic") if program not in ("pin-fork", "scope") else ("dynamic",):
            subprocess.run(["clang", "-O2", "-Wall", "-Wextra", "-Werror", "-pthread",
                            "-Wl,-z,noexecstack,-z,max-page-size=16384",
                            *(["-static"] if kind == "static" else []),
                            str(source / f"{program}-test.c"), "-ldl", "-o",
                            str(root / "bin" / f"{program}-{kind}")], check=True)
    (root / "input").write_text("fixture\n")
    import os
    os.mkfifo(root / "tmp/thread-fifo")
    files = [{"path": str(path.relative_to(root)), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
             for path in sorted(root.rglob("*")) if path.is_file()]
    (root / "regression-manifest.json").write_text(json.dumps({"version": 1, "files": files}, indent=2) + "\n")
    print(root)


if __name__ == "__main__":
    main()
