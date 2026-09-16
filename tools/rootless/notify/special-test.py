#!/usr/bin/env python3
"""Notification-only device and auxv regression, without performance timing."""
import ctypes
import errno
import fcntl
import os
import pathlib
import signal
import stat
import struct
import tempfile


def check_device(path, expected_minor, expected_bytes):
    fd = os.open(path, os.O_RDONLY | os.O_CLOEXEC | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        assert stat.S_ISCHR(info.st_mode)
        assert (os.major(info.st_rdev), os.minor(info.st_rdev)) == (1, expected_minor)
        assert not os.get_inheritable(fd)
        assert fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_NONBLOCK
        data = os.read(fd, 32)
        assert data == expected_bytes if expected_bytes is not None else len(data) == 32
        try:
            os.write(fd, b"x")
        except OSError as error:
            assert error.errno == errno.EBADF
        else:
            raise AssertionError("read-only device descriptor accepted a write")
    finally:
        os.close(fd)


def main():
    signal.alarm(20)
    for name, minor, expected in (("null", 3, b""), ("zero", 5, bytes(32)),
                                  ("full", 7, bytes(32)), ("random", 8, None),
                                  ("urandom", 9, None)):
        check_device("/dev/" + name, minor, expected)
    with tempfile.TemporaryDirectory(prefix="terminal-notify-special-") as root:
        alias = pathlib.Path(root) / "alias"
        alias.symlink_to("/dev/null")
        check_device(alias, 3, b"")
        fifo = pathlib.Path(root) / "fifo"
        os.mkfifo(fifo)
        try:
            fd = os.open(fifo, os.O_RDONLY)
        except OSError as error:
            assert error.errno == errno.EOPNOTSUPP
        else:
            os.close(fd)
            raise AssertionError("Blocking named FIFO support must not be silently claimed")

    libc = ctypes.CDLL(None)
    libc.getauxval.argtypes = [ctypes.c_ulong]
    libc.getauxval.restype = ctypes.c_ulong
    fd = os.open("/proc/self/auxv", os.O_RDONLY | os.O_CLOEXEC)
    try:
        data = bytearray()
        while True:
            chunk = os.read(fd, 3)  # Split both types and pointers across reads.
            if not chunk:
                break
            data.extend(chunk)
            assert len(data) <= 4096
        assert data and len(data) % 16 == 0
        entries = dict(struct.iter_unpack("=QQ", data))
        assert entries[31] == libc.getauxval(31)  # AT_EXECFN
        assert entries[31] != 0
        assert os.pread(fd, len(data), 0) == data
        seals = fcntl.fcntl(fd, fcntl.F_GET_SEALS)
        assert seals & 15 == 15
        os.lseek(fd, 0, os.SEEK_SET)
        duplicate = os.dup(fd)
        try:
            assert os.read(duplicate, 3) == data[:3]
            assert os.read(fd, 3) == data[3:6]
        finally:
            os.close(duplicate)
    finally:
        os.close(fd)
    print("PASS: five memory devices, alias/flags/read-only, blocking FIFO rejection, sealed auxv partial/pread/dup")


if __name__ == "__main__":
    main()
