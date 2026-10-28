#!/usr/bin/env python3
"""Compare a committed guest with the worktree, using temporary release builds.

Usage: python3 tests/benchmarks/avf_sessiond_overhead.py [--baseline 545d204] [--runs 5]
Requires cached Cargo dependencies and a Linux PTY/pidfd-capable host. No AVF claim.
"""
import argparse
import base64
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default="545d204")
    parser.add_argument("--runs", type=int, default=5)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    cargo = shutil.which("cargo") or str(Path.home() / ".cargo/bin/cargo")
    template = Path(__file__).with_suffix(".rs").read_text()
    with tempfile.TemporaryDirectory(prefix="term-sessiond-overhead-") as temporary:
        root = Path(temporary)
        archive = subprocess.check_output(["git", "archive", args.baseline, "guest/droid-sessiond"], cwd=repo)
        baseline = root / "before"
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            source.extractall(baseline, filter="data")
        before = baseline / "guest/droid-sessiond"
        after = root / "after"
        shutil.copytree(repo / "guest/droid-sessiond", after, ignore=shutil.ignore_patterns("target"))
        builds = [("before", before), ("after", after)]
        for name, directory in builds:
            event_source = (directory / "src/session_event.rs").read_text()
            encode = "event.encode_frame().unwrap()" if "fn encode_frame(" in event_source else (
                "Frame { service: Service::Session, kind: FrameKind::Stream, flags: 0, request_id: 0, "
                "payload: event.encode().unwrap() }.encode().unwrap()")
            media = str(directory / "media.bin")
            command = "\x1b_Gi=1,t=f;" + base64.b64encode(media.encode()).decode() + "\x1b\\"
            # json escapes ESC as \u001b, whereas Rust byte strings need \x1b.
            rust_command = "b" + json.dumps(command).replace("\\u001b", "\\x1b")
            fixture = template.replace("__ENCODE_EVENT__", encode).replace("__MEDIA_PATH__", json.dumps(media)).replace("__MEDIA_COMMAND__", rust_command)
            (directory / "src/overhead.rs").write_text(fixture)
            with (directory / "src/server.rs").open("a") as output:
                output.write('\n#[cfg(test)] mod overhead { include!("overhead.rs"); }\n')
            command_args = [cargo, "test", "--offline", "--release", "--manifest-path", str(directory / "Cargo.toml"), "--no-run"]
            subprocess.run(command_args, check=True, cwd=repo, stdout=subprocess.DEVNULL)
        for run in range(args.runs):
            for name, directory in builds[::1 if run % 2 == 0 else -1]:
                result = subprocess.run([cargo, "test", "--offline", "--release", "--manifest-path", str(directory / "Cargo.toml"),
                    "server::overhead::overhead", "--", "--ignored", "--exact", "--nocapture", "--test-threads=1"],
                    check=True, cwd=repo, capture_output=True, text=True)
                for line in result.stdout.splitlines():
                    if "BENCH " in line:
                        print(f"run={run + 1} revision={name} {line[line.index('BENCH '):]}", flush=True)


if __name__ == "__main__":
    main()
