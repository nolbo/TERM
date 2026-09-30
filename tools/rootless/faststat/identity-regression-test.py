#!/usr/bin/env python3
"""ARM64 host regression for raw/preloaded stat identity in a disposable rootfs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import tempfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, environment):
    process = subprocess.Popen(command, env=environment, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, start_new_session=True)
    try:
        output, _ = process.communicate(timeout=45)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        output, _ = process.communicate()
        raise RuntimeError("identity regression timed out:\n" + output[-4000:])
    if process.returncode or "PASS identity across" not in output:
        raise RuntimeError(f"identity regression exited {process.returncode}:\n{output[-4000:]}")
    return {"exit": process.returncode, "checks": [line for line in output.splitlines() if line.startswith("PASS ")]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", type=Path, required=True)
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--preload", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if platform.machine() != "aarch64":
        parser.error("requires an existing ARM64 Linux compiler and userspace")
    foreign = Path("/etc/passwd")
    foreign_owner = foreign.stat()
    if foreign_owner.st_uid == os.getuid() or foreign_owner.st_gid == os.getgid():
        parser.error("the host must expose a foreign-owned /etc/passwd for the ownership-preservation assertion")
    expected_foreign = f"{foreign_owner.st_uid}:{foreign_owner.st_gid}"
    source = Path(__file__).with_suffix(".c")
    report = {"schema": 1, "architecture": platform.machine(), "kernel": platform.release(),
              "engine_sha256": digest(args.engine), "loader_sha256": digest(args.loader),
              "fixture_source_sha256": digest(source), "cases": []}
    if args.preload:
        report["preload_sha256"] = digest(args.preload)
    with tempfile.TemporaryDirectory(prefix="rootless-identity-") as temporary:
        work = Path(temporary)
        root = work / "root"
        for relative in ("bin", "work", "dev", "proc", "lib/aarch64-linux-gnu", "usr/local/lib"):
            (root / relative).mkdir(parents=True, exist_ok=True)
        (work / "tmp").mkdir()
        for variant in ("static", "dynamic"):
            subprocess.run(["clang", "-O2", "-Wall", "-Wextra", "-Werror",
                            *(["-static"] if variant == "static" else []), str(source),
                            "-o", str(root / "bin" / f"identity-{variant}")], check=True)
        shutil.copy2("/usr/bin/env", root / "bin/env")
        shutil.copy2("/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", root / "lib/ld-linux-aarch64.so.1")
        shutil.copy2("/usr/lib/aarch64-linux-gnu/libc.so.6", root / "lib/aarch64-linux-gnu/libc.so.6")
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith("PROOT_") and key != "LD_PRELOAD"}
        for variant in ("static", "dynamic"):
            result = run([str(root / "bin" / f"identity-{variant}"), str(os.getuid()), str(os.getgid()),
                          str(root / "work"), str(foreign), expected_foreign], environment)
            report["cases"].append({"case": f"native/{variant}", **result})
        configs = [("slow", {"PROOT_NO_SECCOMP": "1"}),
                   ("entry", {"PROOT_STAT_ENTRY": "1", "PROOT_FSTAT_ENTRY": "1", "PROOT_STAT_KERNEL": "1"})]
        if args.preload:
            configs.append(("preload", {"PROOT_STAT_ENTRY": "1", "PROOT_FSTAT_ENTRY": "1", "PROOT_STAT_KERNEL": "1",
                                       "PROOT_FASTSTAT": "2", "PROOT_FASTFD": "1", "PROOT_FASTCLOSE": "1", "PROOT_ID_ENTRY": "1"}))
        for mode, extra in configs:
            for order in ("native", "links-first", "identity-first"):
                for identity in ("0:0", "1234:2345", None):
                    identity_option = ["-i", identity] if identity else []
                    if order == "native":
                        options = identity_option
                    elif order == "links-first":
                        options = ["--link2symlink", *identity_option]
                    else:
                        options = [*identity_option, "--link2symlink"]
                    uid, gid = identity.split(":") if identity else (str(os.getuid()), str(os.getgid()))
                    for variant in ("static", "dynamic"):
                        current = {**environment, **extra, "PROOT_LOADER": str(args.loader.resolve()),
                                   "PROOT_TMP_DIR": str(work / "tmp")}
                        command = [str(args.engine.resolve()), *options, "-r", str(root), "-b", "/dev", "-b", "/proc",
                                   "-b", f"{foreign}:/foreign", "-w", "/work"]
                        if mode == "preload":
                            command += ["-b", f"{args.preload.resolve()}:/usr/local/lib/terminal-faststat.so"]
                        command += ["/bin/env", "-i", "PATH=/bin:/usr/bin"]
                        if mode == "preload":
                            command += ["LD_PRELOAD=/usr/local/lib/terminal-faststat.so", "PROOT_FASTFD=1",
                                        "PROOT_FASTCLOSE=1", "PROOT_FASTOPEN=1", "PROOT_FASTCREATE=1",
                                        "PROOT_FASTUNLINK=1", "PROOT_FASTSTAT_PATH=0", "PROOT_FASTSTAT_SHARED=1"]
                        command += [f"/bin/identity-{variant}", uid, gid, "/work", "/foreign", expected_foreign]
                        if identity == "0:0":
                            command += ["change-ids"]
                        case = f"{mode}/{order}/{identity or 'real-ids'}/{variant}"
                        result = run(command, current)
                        report["cases"].append({"case": case, **result})
                        print("PASS", case, flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS {len(report['cases'])} native/PRoot identity cases; {args.output}")


if __name__ == "__main__":
    main()
