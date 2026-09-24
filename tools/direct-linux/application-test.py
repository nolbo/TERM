"""Real CPython/Git work inside a disposable guest root, without network access."""
import sys
import time

imports_started = time.monotonic_ns()
import bz2
import ctypes
import decimal
import gzip
import hashlib
import json
import lzma
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import zipfile

imports_elapsed = (time.monotonic_ns() - imports_started) / 1e6


def measurement(case, elapsed, count):
    if sys.argv[1:] == ["benchmark"]:
        print("BENCH " + json.dumps({"case": case, "count": count, "elapsed_ms": elapsed}), flush=True)


def main():
    measurement("python-imports", imports_elapsed, 14)
    payload = json.dumps({"files": list(range(256)), "text": "Linux 환경"}, ensure_ascii=False).encode()
    assert json.loads(payload)["text"] == "Linux 환경"
    assert ctypes.CDLL(None).getpid() > 0
    assert decimal.Decimal("1.25") * 4 == 5
    for module in (gzip, bz2, lzma):
        assert module.decompress(module.compress(payload)) == payload
    print("PASS Python imports/DSOs/JSON/hash/compression/ctypes", flush=True)
    with tempfile.TemporaryDirectory(prefix="applications-") as temporary:
        work = Path(temporary)
        database = sqlite3.connect(work / "data.db")
        database.execute("create table data (id integer primary key, value blob)")
        database.executemany("insert into data values (?, ?)", [(i, payload) for i in range(64)])
        database.commit()
        database.close()
        with sqlite3.connect(work / "data.db") as database:
            assert database.execute("select count(*), value from data").fetchone() == (64, payload)
        with zipfile.ZipFile(work / "data.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("nested/data.json", payload)
        with zipfile.ZipFile(work / "data.zip") as archive:
            archive.extractall(work / "extracted")
        assert hashlib.sha256((work / "extracted/nested/data.json").read_bytes()).digest() == hashlib.sha256(payload).digest()
        os.setxattr(work / "extracted/nested/data.json", "user.fixture", b"metadata\0value")
        shutil.copytree(work / "extracted", work / "copied")
        assert (work / "copied/nested/data.json").read_bytes() == payload
        assert os.getxattr(work / "copied/nested/data.json", "user.fixture") == b"metadata\0value"
        print("PASS Python SQLite/archive/filesystem", flush=True)

        repo = work / "repo"
        repo.mkdir()
        git_environment = dict(os.environ, GIT_AUTHOR_DATE="2000-01-01T00:00:00Z", GIT_COMMITTER_DATE="2000-01-01T00:00:00Z")

        def git(*args):
            started = time.monotonic_ns()
            result = subprocess.run(["/usr/bin/git", "-C", str(repo), *args], env=git_environment,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            if sys.argv[1:] == ["profile"]:
                lines = result.stdout.splitlines(keepends=True)
                exec_profiles = [json.loads(line.removeprefix("EXEC_PROFILE ")) for line in lines if line.startswith("EXEC_PROFILE ")]
                result.stdout = "".join(line for line in lines if not line.startswith("EXEC_PROFILE "))
                print("APPLICATION_PROFILE " + json.dumps({"command": args[0],
                    "elapsed_ms": (time.monotonic_ns() - started) / 1e6, "exec": exec_profiles}), flush=True)
            if result.returncode:
                print(result.stdout, flush=True)
            result.check_returncode()
            return result.stdout

        git_started = time.monotonic_ns()
        git("init", "--initial-branch=fixture")
        git("config", "user.name", "Runtime Fixture")
        git("config", "user.email", "fixture@example.invalid")
        for i in range(128):
            (repo / f"file-{i}.txt").write_text(f"original:{i}\n")
        git("add", ".")
        git("commit", "-m", "initial fixture")
        original = git("rev-parse", "HEAD").strip()
        assert git("status", "--porcelain") == ""
        git("switch", "-c", "changed")
        for i in range(128):
            (repo / f"file-{i}.txt").write_text(f"changed:{i}\n")
        git("commit", "-am", "changed fixture")
        changed = git("rev-parse", "HEAD").strip()
        assert original != changed
        git("switch", "fixture")
        assert all((repo / f"file-{i}.txt").read_text() == f"original:{i}\n" for i in range(128))
        git("switch", "changed")
        assert all((repo / f"file-{i}.txt").read_text() == f"changed:{i}\n" for i in range(128))
        assert git("status", "--porcelain") == ""
        git("fsck", "--full")
        measurement("git-workflow-128", (time.monotonic_ns() - git_started) / 1e6, 128)
        print("PASS Git init/add/commit/status/branch/checkout/fsck", flush=True)
    print("PASS Linux applications", flush=True)


if __name__ == "__main__":
    main()
