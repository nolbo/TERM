"""Repeatable, host-only safety regression gate. Never contacts a USB device.

Exit 0 means the selected automated scope passed, NOT physical-device safety.
Only fixed test commands are executed. Reports deliberately omit raw test logs.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def classify(tests, failures, skipped):
    if failures:
        return "FAILED"
    return "PASSED" if tests > 0 and skipped == 0 else "INCOMPLETE"


def execute(command, timeout=900):
    # TemporaryFile is private, unlinked on close; no raw upstream output in JSON.
    with tempfile.TemporaryFile() as log:
        try:
            process = subprocess.Popen(command, cwd=ROOT, stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        except OSError:
            return "INCOMPLETE", ""
        try:
            returncode = process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
            # Only this runner's process group; never kill unrelated sessions/daemons.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
            if isinstance(error, KeyboardInterrupt):
                raise SystemExit(130)
            return "INCOMPLETE", ""
        log.seek(0, os.SEEK_END)
        log.seek(max(0, log.tell() - 256 * 1024))
        output = log.read().decode("utf-8", errors="replace")
    return ("PASSED" if returncode == 0 else "FAILED"), output


def text_result(kind, status, output):
    pattern = (r"Ran (\d+) tests?" if kind == "python" else
               r"test result: ok\. (\d+) passed; (\d+) failed; (\d+) ignored")
    matches = re.findall(pattern, output)
    if not matches:
        return {"status": status if status != "PASSED" else "INCOMPLETE"}
    if kind == "python":
        tests = int(matches[-1])
        skipped = sum(map(int, re.findall(r"skipped=(\d+)", output)))
        failures = 0
    else:
        tests, failures, skipped = map(sum, zip(*(map(int, match) for match in matches)))
    return {"status": classify(tests, failures, skipped) if status == "PASSED" else status,
            "tests": tests, "skipped": skipped}


def junit_result(directory, pattern="TEST-*.xml"):
    tests = failures = skipped = 0
    try:
        for path in directory.glob(pattern):
            suite = ET.parse(path).getroot()
            tests += int(suite.attrib["tests"])
            failures += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))
    except (OSError, ValueError, KeyError, ET.ParseError):
        return {"status": "INCOMPLETE"}
    return {"status": classify(tests, failures, skipped), "tests": tests, "skipped": skipped}


def report(layers, revision):
    statuses = [layer["status"] for layer in layers.values()]
    automated = ("FAILED" if "FAILED" in statuses else
                 "PASSED" if statuses and all(s == "PASSED" for s in statuses) else "INCOMPLETE")
    return {"schema": 1, "revision": revision, "automatedScope": automated,
            "layers": layers, "physicalDeviceSafety": "NOT_VALIDATED",
            "androidInstrumentation": "NOT_RUN",
            "limitations": ["No physical USB, iOS services or electrical safety tested",
                            "See tests/usb/DEVICE_SAFETY.md for hardware acceptance"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=["python", "host"], default="host")
    parser.add_argument("--ndk", default=os.environ.get("TERMINAL_ANDROID_NDK"))
    parser.add_argument("--rust-build", default=str(ROOT / "android-runtime/build/device-safety-rust"))
    args = parser.parse_args()
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    # Keep this descriptor alive for the whole run: shared Gradle result files
    # must not be collected by two safety runners at once.
    lock_dir = ROOT / ".gradle"
    lock_dir.mkdir(exist_ok=True)
    lock = (lock_dir / "usb-safety.lock").open("a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        lock.close()
        print(json.dumps(report({"runner": {"status": "INCOMPLETE", "reason": "RUNNER_BUSY"}}, revision)))
        return 2
    layers = {}
    for name, pattern in [("client", "test_client.py"), ("gate", "test_safety_gate.py")]:
        print("Safety gate: " + name, file=sys.stderr, flush=True)
        status, output = execute([sys.executable, "-m", "unittest", "discover", "-s", "tests/usb", "-p", pattern], 60)
        layers[name] = text_result("python", status, output)
    if args.scope == "host":
        print("Safety gate: USB runtime tests", file=sys.stderr, flush=True)
        # Force current executions, rather than interpreting stale/up-to-date reports.
        status, _ = execute(["./gradlew", ":android-runtime:testDebugUnitTest", "--tests", "dev.terminal.runtime.usb.*", "--rerun", "--console=plain"], 1800)
        layers["runtime"] = (junit_result(ROOT / "android-runtime/build/test-results/testDebugUnitTest", "TEST-dev.terminal.runtime.usb.*.xml")
                             if status == "PASSED" else {"status": status})
        if not args.ndk or not Path(args.ndk).is_dir():
            layers["rust"] = {"status": "INCOMPLETE", "reason": "NDK_REQUIRED"}
        else:
            print("Safety gate: Rust connector tests", file=sys.stderr, flush=True)
            connector_build = str(Path(args.rust_build).parent / "device-safety-connector")
            status, output = execute(["bash", "tools/iphone/connector/build.sh", connector_build, args.ndk, "--test"], 1800)
            layers["rust"] = text_result("rust", status, output)
    result = report(layers, revision)
    result["scope"] = args.scope
    result["worktreeDirty"] = bool(subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=ROOT))
    print(json.dumps(result, indent=2))
    lock.close()
    return {"PASSED": 0, "FAILED": 1, "INCOMPLETE": 2}[result["automatedScope"]]


if __name__ == "__main__":
    sys.exit(main())
