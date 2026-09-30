#!/usr/bin/env python3
"""Run actual guest assertions; PRoot's launcher status alone cannot prove PASS."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import time

TRACER = {"PROOT_STAT_ENTRY": "1", "PROOT_STAT_KERNEL": "1", "PROOT_PATH_KERNEL": "1",
          "PROOT_MUTATION_ENTRY": "1", "PROOT_RENAME_LOCAL": "1", "PROOT_FSTAT_ENTRY": "1",
          "PROOT_FASTSTAT": "2", "PROOT_FASTFD": "1", "PROOT_FASTCLOSE": "1",
          "PROOT_FASTPATH": "1", "PROOT_TCGETS_DIRECT": "1", "PROOT_ID_ENTRY": "1"}
PRELOAD = ["LD_PRELOAD=/usr/local/lib/terminal-faststat.so", "PROOT_FASTFD=1", "PROOT_FASTCLOSE=1",
           "PROOT_FASTOPEN=1", "PROOT_FASTCREATE=1", "PROOT_FASTUNLINK=1",
           "PROOT_FASTSTAT_PATH=0", "PROOT_FASTSTAT_SHARED=1"]
ASSERTIONS = {"exec": "PASS concurrent exec PID/TID, sibling destruction, precommit failure, FD/CLOEXEC, cwd, masks, dispositions, TLS and mappings",
              "fork": "PASS 30 concurrent forks: child runtime locks/FD cleanup/thread creation/exec, surviving parent workers",
              "pin": "PASS 100 libc/raw forks: no temporary FD inheritance, user O_PATH retained, thread fallback and exec acceleration",
              "private": "PASS 160 private-FD-table clone lifecycles with another thread blocked on a runtime pin",
              "unshare": "PASS unshare during restarted FIFO wait: both tables release pins and preserve guest FD independence",
              "restart": "PASS syscall restart: guest signal context, EINTR, ignored/trap signals, relative/openat2 paths, altstack, nested handlers and unrelated read",
              "scope": "PASS exec-isolated FD scope: independent reader keeps acceleration while peer forks"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proot", type=Path, required=True)
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--mode", choices=("native", "emulated", "both"), default="both")
    args = parser.parse_args()
    if args.repeat < 1 or args.repeat > 100:
        parser.error("repeat must be between 1 and 100")
    root = args.root.resolve()
    if not (root / "regression-manifest.json").is_file():
        parser.error("root must have been created by regression/prepare.py")
    if args.output.exists():
        parser.error("output must be a new results directory")
    output = args.output.resolve()
    output.mkdir(parents=True)
    (output / "proot-tmp").mkdir()
    cases = [(profile, kind, scenario) for profile in ("plain", "tracer", "full")
             for kind in ("static", "dynamic")
             for scenario in ("exec-leader", "exec-worker", "exec-exited-leader", "fork-worker",
                              "fork-exited-leader", "private-clone", "unshare-wait", "restart")]
    cases += [("full", "dynamic", "pin"), ("context", "dynamic", "pin")]
    cases += [("full", "dynamic", "scope")]
    modes = ("native", "emulated") if args.mode == "both" else (args.mode,)
    cases = [(mode, *case) for mode in modes for case in cases]
    results = []
    for repeat in range(args.repeat):
        for mode, profile, kind, scenario in cases:
            environment = {key: value for key, value in os.environ.items()
                           if not key.startswith("PROOT_") and key != "LD_PRELOAD"}
            environment.update(PROOT_LOADER=str(args.loader.resolve()), PROOT_TMP_DIR=str(output / "proot-tmp"))
            if profile != "plain":
                environment.update(TRACER)
            if mode == "emulated":
                environment["PROOT_EMULATED_HARDLINK"] = "1"
            command = [str(args.proot.resolve()), "--link2symlink", "-0", "-r", str(root),
                       "-b", "/dev", "-b", "/proc", "-w", "/", "/bin/env", "-i", "PATH=/bin:/usr/bin"]
            if profile in ("full", "context"):
                command.extend(PRELOAD)
            if profile == "context":
                command.extend(["PROOT_FASTSTAT_PATH=1", "PROOT_FASTSTAT_SHARED=0"])
            family = scenario.split("-", 1)[0]
            program = {"exec": "concurrency", "fork": "fork", "private": "fork", "unshare": "fork",
                       "pin": "pin-fork", "restart": "restart", "scope": "scope"}[family]
            command.append(f"/bin/{program}-{kind}")
            if scenario not in ("pin", "restart", "scope"):
                command.append(scenario)
            log = output / f"{repeat}-{mode}-{profile}-{kind}-{scenario}.log"
            started = time.monotonic()
            timed_out = False
            with log.open("w") as stream:
                process = subprocess.Popen(command, env=environment, stdout=stream, stderr=subprocess.STDOUT,
                                           start_new_session=True)
                try:
                    process.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    timed_out = True
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
            text = log.read_text(errors="replace")
            passed = (not timed_out and process.returncode == 0 and ASSERTIONS[family] in text
                      and "FAIL" not in text and "terminated with signal" not in text)
            row = {"mode": mode, "profile": profile, "kind": kind, "scenario": scenario, "repeat": repeat,
                   "exit": process.returncode, "passed": passed, "timeout": timed_out,
                   "elapsed_seconds": round(time.monotonic() - started, 3), "log": log.name}
            results.append(row)
            print(json.dumps(row), flush=True)
            (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    raise SystemExit(not all(row["passed"] for row in results))


if __name__ == "__main__":
    main()
