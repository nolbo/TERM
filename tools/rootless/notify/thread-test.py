#!/usr/bin/env python3
"""Concurrent notification regression; no timing or benchmark assertions."""
import concurrent.futures
import os
import pathlib
import tempfile
import threading


def main():
    payload = bytes(range(256)) * 8
    barrier = threading.Barrier(8, timeout=15)
    with tempfile.TemporaryDirectory(prefix="terminal-notify-thread-") as root:
        path = pathlib.Path(root)
        (path / "data").write_bytes(payload)
        (path / "alias").symlink_to("data")
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            def read_files(worker):
                barrier.wait()
                for iteration in range(100):
                    # Mix kernel-fast paths, symlink translation and dirfd lookup.
                    name = "data" if iteration % 2 else "alias"
                    if worker % 2:
                        fd = os.open(name, os.O_RDONLY | os.O_CLOEXEC,
                                     dir_fd=directory)
                    else:
                        fd = os.open(path / name, os.O_RDONLY | os.O_CLOEXEC)
                    try:
                        assert not os.get_inheritable(fd)
                        assert os.fstat(fd).st_size == len(payload)
                        output = bytearray()
                        while True:
                            block = os.read(fd, 4096)
                            if not block:
                                break
                            output.extend(block)
                        assert output == payload
                    finally:
                        os.close(fd)
                return 100

            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                assert sum(pool.map(read_files, range(8))) == 800
        finally:
            os.close(directory)
    print("PASS: 8 threads, 800 opens, symlink/dirfd/content/CLOEXEC/fstat")


if __name__ == "__main__":
    main()
