#!/usr/bin/env python3
"""Check disabled-dispatch overhead and guest results with a probe-only DSO."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proot", type=Path, required=True)
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output must be a new disposable directory")
    output = args.output.resolve()
    output.mkdir(parents=True)
    (output / "tmp").mkdir()
    rows = []
    for mode in ("native", "emulated"):
        for profile in ("shared", "context"):
            environment = {k: v for k, v in os.environ.items()
                           if not k.startswith("PROOT_") and k != "LD_PRELOAD"}
            environment.update(PROOT_LOADER=str(args.loader.resolve()), PROOT_TMP_DIR=str(output / "tmp"),
                               PROOT_FASTSTAT="2", PROOT_FASTFD="1", PROOT_FASTPATH="1",
                               PROOT_FASTCLOSE="1", PROOT_STAT_ENTRY="1", PROOT_STAT_KERNEL="1",
                               PROOT_PATH_KERNEL="1", PROOT_FSTAT_ENTRY="1", PROOT_ID_ENTRY="1")
            if mode == "emulated":
                environment["PROOT_EMULATED_HARDLINK"] = "1"
            command = [str(args.proot.resolve()), "--link2symlink", "-0", "-r", str(args.root.resolve()),
                       "-b", "/dev", "-b", "/proc", "-w", "/", "/bin/env", "-i", "PATH=/bin:/usr/bin",
                       "LD_PRELOAD=/usr/local/lib/terminal-faststat.so", "PROOT_FASTFD=1",
                       "PROOT_FASTCLOSE=1", "PROOT_FASTOPEN=1", "PROOT_FASTCREATE=1", "PROOT_FASTUNLINK=1",
                       f"PROOT_FASTSTAT_SHARED={int(profile == 'shared')}",
                       f"PROOT_FASTSTAT_PATH={int(profile == 'context')}", "/bin/fallback-cost-test"]
            process = subprocess.Popen(command, env=environment, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, start_new_session=True)
            try:
                text, _ = process.communicate(timeout=30)
                exit_code = process.returncode
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                text, _ = process.communicate()
                text += "FAIL timeout\n"
                exit_code = None
            passed = (exit_code == 0 and "PASS disabled path dispatch" in text and "FAIL" not in text)
            log = f"{mode}-{profile}.log"
            (output / log).write_text(text)
            row = {"mode": mode, "profile": profile, "exit": exit_code, "passed": passed, "log": log}
            rows.append(row)
            print(json.dumps(row), flush=True)
    (output / "results.json").write_text(json.dumps(rows, indent=2) + "\n")
    raise SystemExit(not all(row["passed"] for row in rows))


if __name__ == "__main__":
    main()
