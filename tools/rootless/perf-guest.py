#!/usr/bin/env python3
"""Run inside PRoot; temporary test files are removed on completion."""
import argparse
import ctypes
import errno
import json
import os
import signal
import stat
import subprocess
import tempfile
import threading
import time


def check():
    with tempfile.TemporaryDirectory(prefix="terminal-proot-perf-") as directory:
        original = os.path.join(directory, "original")
        second = os.path.join(directory, "second")
        symbolic = os.path.join(directory, "symbolic")
        with open(original, "wb") as output:
            output.write(b"rootless\n")
        os.link(original, second)
        os.symlink("second", symbolic)
        assert os.stat(original).st_nlink == os.stat(second).st_nlink == 2
        assert os.stat(original).st_ino == os.stat(second).st_ino
        assert stat.S_ISREG(os.lstat(second).st_mode)
        assert stat.S_ISLNK(os.lstat(symbolic).st_mode)
        assert os.stat(symbolic).st_ino == os.stat(second).st_ino
        descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        try:
            assert os.stat("second", dir_fd=descriptor).st_nlink == 2
            assert stat.S_ISLNK(os.stat("symbolic", dir_fd=descriptor,
                                      follow_symlinks=False).st_mode)
            with open(second, "rb") as source:
                assert os.fstat(source.fileno()).st_nlink == 2
                duplicated = os.dup(source.fileno())
                try:
                    assert os.fstat(duplicated).st_ino == os.stat(second).st_ino
                finally:
                    os.close(duplicated)
            libc = ctypes.CDLL(None, use_errno=True)
            buffer = ctypes.create_string_buffer(256)
            # Linux AT_EMPTY_PATH: this call intentionally has no translated path.
            assert libc.fstatat(descriptor, b"", buffer, 0x1000) == 0
            assert libc.fstatat(-1, b"second", buffer, 0) == -1
            assert ctypes.get_errno() == errno.EBADF
        finally:
            os.close(descriptor)
        renamed = os.path.join(directory, "renamed")
        os.rename(second, renamed)
        assert os.stat(renamed).st_nlink == 2
        os.unlink(original)
        assert os.stat(renamed).st_nlink == 1
        dangling = os.path.join(directory, "dangling")
        os.symlink("missing", dangling)
        assert stat.S_ISLNK(os.lstat(dangling).st_mode)
        try:
            os.stat(dangling)
        except FileNotFoundError:
            pass
        else:
            raise AssertionError("dangling symlink followed successfully")
        long_directory = directory
        for _ in range(6):
            long_directory = os.path.join(long_directory, "d" * 80)
            os.mkdir(long_directory)
        assert stat.S_ISDIR(os.stat(long_directory).st_mode)
        with open(os.path.join(directory, "unlinked"), "wb+") as source:
            os.unlink(source.name)
            assert os.fstat(source.fileno()).st_nlink == 0
        # Nested bind/symlink resolution and errors must not leak a prior entry.
        for path in ("/proc/self/fd", "/dev/null", "/etc/passwd", directory):
            os.stat(path)
        old_handler = signal.signal(signal.SIGUSR1, lambda *_: os.stat(directory))
        try:
            os.kill(os.getpid(), signal.SIGUSR1)
            assert os.stat(renamed).st_nlink == 1
        finally:
            signal.signal(signal.SIGUSR1, old_handler)
        if hasattr(os, "fork"):
            child = os.fork()
            if child == 0:
                try:
                    assert os.stat(renamed).st_nlink == 1
                except BaseException:
                    os._exit(1)
                os._exit(0)
            assert os.waitpid(child, 0)[1] == 0
        # Another thread replaces a file atomically while paths are translated.
        changing = os.path.join(directory, "changing")
        with open(changing, "wb") as output:
            output.write(b"a")
        failures = []

        def writer():
            try:
                for index in range(100):
                    temporary = changing + ".next"
                    with open(temporary, "wb") as output:
                        output.write(b"a" if index % 2 else b"bb")
                    os.replace(temporary, changing)
            except BaseException as error:
                failures.append(error)

        thread = threading.Thread(target=writer)
        thread.start()
        try:
            for _ in range(200):
                observed = os.stat(changing)
                assert stat.S_ISREG(observed.st_mode) and observed.st_size in (1, 2)
        finally:
            thread.join()
        assert not failures, failures
    print(json.dumps({"check": "passed"}), flush=True)


def benchmark(iterations, label):
    results = {"label": label}
    for name, operation in (
        ("stat", lambda: os.stat("/etc/passwd")),
        ("lstat", lambda: os.lstat("/etc/passwd")),
        ("open", lambda: open("/etc/passwd", "rb").close()),
    ):
        for _ in range(100):
            operation()
        start = time.perf_counter()
        for _ in range(iterations):
            operation()
        results[name + "_ms"] = round((time.perf_counter() - start) * 1000, 3)
    start = time.perf_counter()
    for _ in range(100):
        subprocess.run(["/bin/true"], check=True)
    results["exec100_ms"] = round((time.perf_counter() - start) * 1000, 3)
    results["iterations"] = iterations
    print(json.dumps(results), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--bench", action="store_true")
    parser.add_argument("--iterations", type=int, default=3000)
    parser.add_argument("--label", default="")
    arguments = parser.parse_args()
    if arguments.bench:
        benchmark(arguments.iterations, arguments.label)
    else:
        check()
