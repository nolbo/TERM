#!/usr/bin/env python3
"""Offline, disposable whole-workload benchmarks; run inside the selected guest.

Preparation and cleanup are outside the timed regions. This measures warm-cache
work, not storage cold starts. --coverage adds diagnostic DSO exit reports and
must be run separately from speed comparisons. No user repository is modified.
"""
import argparse
import ctypes
import importlib.util
import json
import os
from pathlib import Path
import resource
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time


CASES = ("metadata", "mutations", "git-status", "python-import", "tar-extract")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=1000)
    parser.add_argument("--rounds", type=int, default=1)
    parser.add_argument("--case", action="append", choices=CASES)
    parser.add_argument("--coverage", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.count <= 100000 or not 1 <= args.rounds <= 30:
        parser.error("count must be 1..100000 and rounds 1..30")
    cases = args.case or list(CASES)
    if "git-status" in cases and not shutil.which("git"):
        parser.error("git is required for git-status")
    if "python-import" in cases and not importlib.util.find_spec("sqlite3"):
        parser.error("Python sqlite3 is required for the fixed import workload")
    library = ctypes.CDLL(None)
    counters = []
    for name in ("proot_faststat_hits", "proot_faststat_fallbacks"):
        function = getattr(library, name, None)
        if function is not None:
            function.restype = ctypes.c_ulong
        counters.append(function)

    def counts():
        return [function() if function else 0 for function in counters]

    # An inherited GIT_DIR/INDEX_FILE must never redirect fixture operations.
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env.update(PYTHONDONTWRITEBYTECODE="1", GIT_CONFIG_NOSYSTEM="1",
               GIT_CONFIG_GLOBAL="/dev/null", LC_ALL="C")
    env.pop("PROOT_FASTSTAT_REPORT", None)

    def command(argv, cwd, diagnostic=False):
        child_env = dict(env)
        if diagnostic:
            child_env["PROOT_FASTSTAT_REPORT"] = "1"
        result = subprocess.run(argv, cwd=cwd, env=child_env, check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        reports = []
        for line in result.stderr.decode(errors="replace").splitlines():
            try:
                parsed = json.loads(line)
            except ValueError:
                print(line, file=sys.stderr)
                continue
            if isinstance(parsed, dict) and "faststat" in parsed:
                reports.append(parsed["faststat"])
            else:
                print(line, file=sys.stderr)
        return result.stdout, reports

    with tempfile.TemporaryDirectory(prefix="terminal-workloads-") as temporary:
        root = Path(temporary)
        tree = root / "tree"
        tree.mkdir()
        payload = b"terminal rootless benchmark\n" * 16
        files = []
        for index in range(args.count):
            path = tree / ("d%03d" % (index % 32)) / ("f%06d" % index)
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(payload)
            files.append(path)
        archive = root / "input.tar"
        if "tar-extract" in cases:
            with tarfile.open(archive, "w") as output:
                output.add(tree, arcname="tree")
        git = ["git", "-c", "core.hooksPath=/dev/null"]
        if "git-status" in cases:
            command(git + ["init", "-q"], tree)
            command(git + ["add", "."], tree)
            command(git + ["-c", "user.name=Benchmark", "-c",
                           "user.email=benchmark@example.invalid", "commit", "-qm", "fixture"], tree)
            # Refresh the index before the timed status operations.
            command(git + ["status", "--porcelain", "--untracked-files=all"], tree)
        for round_index in range(args.rounds):
            for case in cases if round_index % 2 == 0 else list(reversed(cases)):
                scratch = root / "scratch"
                scratch.mkdir()
                before = counts()
                usage = [resource.getrusage(who) for who in (resource.RUSAGE_SELF, resource.RUSAGE_CHILDREN)]
                start = time.perf_counter_ns()
                reports = []
                if case == "metadata":
                    total = sum(os.stat(path).st_size for path in files)
                    assert total == len(payload) * args.count
                elif case == "mutations":
                    for index in range(args.count):
                        path = scratch / str(index)
                        path.write_bytes(payload)
                        renamed = scratch / (str(index) + ".renamed")
                        path.rename(renamed)
                        renamed.unlink()
                elif case == "git-status":
                    output, reports = command(git + ["status", "--porcelain", "--untracked-files=all"], tree, args.coverage)
                    assert output == b"", output
                elif case == "python-import":
                    _, reports = command([sys.executable, "-I", "-B", "-c",
                        "import asyncio,email.mime.text,http.client,json,sqlite3,ssl,tarfile,urllib.request,xml.etree.ElementTree,zipfile"], scratch, args.coverage)
                elif case == "tar-extract":
                    # Only this program's generated regular files/directories.
                    with tarfile.open(archive) as source:
                        if hasattr(tarfile, "fully_trusted_filter"):
                            source.extractall(scratch, filter="fully_trusted")
                        else:
                            source.extractall(scratch)
                elapsed = (time.perf_counter_ns() - start) / 1e6
                after = counts()
                finished = [resource.getrusage(who) for who in (resource.RUSAGE_SELF, resource.RUSAGE_CHILDREN)]
                cpu_ms = sum((end.ru_utime + end.ru_stime - begin.ru_utime - begin.ru_stime) * 1000
                             for begin, end in zip(usage, finished))
                if case == "tar-extract":
                    for path in files:
                        assert (scratch / "tree" / path.relative_to(tree)).read_bytes() == payload
                print(json.dumps({"case": case, "round": round_index + 1, "count": args.count,
                    "wall_ms": round(elapsed, 3), "guest_cpu_ms": round(cpu_ms, 3),
                    "parent_hits": after[0] - before[0], "parent_fallbacks": after[1] - before[1],
                    "child_reports": reports, "coverage": args.coverage}), flush=True)
                shutil.rmtree(scratch)


if __name__ == "__main__":
    main()
