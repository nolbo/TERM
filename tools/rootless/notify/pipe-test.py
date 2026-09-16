#!/usr/bin/env python3
"""Pipe/FIFO notification semantics; externally bounded, no timing assertions."""
import errno
import fcntl
import os
import pathlib
import signal
import tempfile


def expect_unsupported(path):
    try:
        fd = os.open(path, os.O_RDONLY)
    except OSError as error:
        assert error.errno == errno.EOPNOTSUPP
    else:
        os.close(fd)
        raise AssertionError("blocking named FIFO was accepted")


def main():
    signal.alarm(20)
    reader, writer = os.pipe()
    try:
        # Reopen even the write end as a genuinely new read description.
        reopened = os.open(f"/proc/self/fd/{writer}", os.O_RDONLY | os.O_CLOEXEC)
        try:
            assert not os.get_inheritable(reopened)
            assert fcntl.fcntl(reopened, fcntl.F_GETFL) & os.O_ACCMODE == os.O_RDONLY
            flags = fcntl.fcntl(reopened, fcntl.F_GETFL)
            fcntl.fcntl(reopened, fcntl.F_SETFL, flags | os.O_NONBLOCK)
            assert not fcntl.fcntl(reader, fcntl.F_GETFL) & os.O_NONBLOCK
            assert not fcntl.fcntl(writer, fcntl.F_GETFL) & os.O_NONBLOCK
            assert os.write(writer, b"pipe") == 4
            assert os.read(reopened, 4) == b"pipe"
        finally:
            os.close(reopened)
        os.close(writer)
        writer = -1
        # With no writer, anonymous-pipe open must still complete and read EOF.
        reopened = os.open(f"/proc/self/fd/{reader}", os.O_RDONLY)
        try:
            assert os.read(reopened, 1) == b""
        finally:
            os.close(reopened)
    finally:
        os.close(reader)
        if writer >= 0:
            os.close(writer)

    with tempfile.TemporaryDirectory(prefix="terminal-notify-pipe-") as root:
        fifo = pathlib.Path(root) / "fifo"
        alias = pathlib.Path(root) / "alias"
        os.mkfifo(fifo)
        alias.symlink_to("fifo")
        expect_unsupported(fifo)
        expect_unsupported(alias)
        reader = os.open(alias, os.O_RDONLY | os.O_NONBLOCK | os.O_CLOEXEC)
        try:
            assert os.read(reader, 1) == b""
            assert not os.get_inheritable(reader)
            # /proc/fd spelling must not turn a named FIFO into an anonymous one.
            expect_unsupported(f"/proc/self/fd/{reader}")
            writer = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK)
            try:
                assert os.write(writer, b"fifo") == 4
                assert os.read(reader, 4) == b"fifo"
                assert fcntl.fcntl(reader, fcntl.F_GETFL) & os.O_NONBLOCK
            finally:
                os.close(writer)
            assert os.read(reader, 1) == b""
        finally:
            os.close(reader)
    print("PASS: anonymous pipe reopen/EOF/independent flags, nonblocking FIFO, blocking FIFO rejection")


if __name__ == "__main__":
    main()
