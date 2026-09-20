#!/usr/bin/env python3
"""Raw unlinkat probes used by dpkg: errors, freshness, aliases and seccomp."""
import ctypes
import errno
import os
import signal
import tempfile

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)


def unlink(fd, path, flags=0, error=None):
    result = libc.syscall(35, fd, path.encode(), flags)
    actual = ctypes.get_errno()
    assert (result == 0 if error is None else result == -1 and actual == error), (path, result, actual, error)


with tempfile.TemporaryDirectory(prefix="unpack-unlink-") as root:
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    missing = root + "/file.dpkg-new"
    for _ in range(10):
        unlink(-100, missing, error=errno.ENOENT)
        unlink(directory, "file.dpkg-tmp", error=errno.ENOENT)
        unlink(directory, "file.dpkg-new", 0x200, errno.ENOENT)
    unlink(-1, missing, error=errno.ENOENT)  # Absolute path ignores dirfd.
    unlink(-1, "file.dpkg-new", error=errno.EBADF)
    unlink(directory, "", error=errno.ENOENT)
    unlink(directory, "file.dpkg-new", 0x40000000, errno.EINVAL)
    unlink(directory, "missing/child", error=errno.ENOENT)
    unlink(directory, "file.dpkg-new/", error=errno.ENOENT)
    # A fresh create after an absent probe must not reuse a negative result.
    with open(missing, "wb") as stream:
        stream.write(b"kept through open descriptor")
    file_fd = os.open(missing, os.O_RDONLY)
    unlink(file_fd, "child", error=errno.ENOTDIR)
    unlink(directory, "file.dpkg-new/", error=errno.ENOTDIR)
    unlink(directory, "file.dpkg-new", 0x200, errno.ENOTDIR)
    unlink(-100, root, error=errno.EISDIR)
    unlink(directory, "file.dpkg-new")
    assert not os.path.lexists(missing)
    assert os.read(file_fd, 64) == b"kept through open descriptor"
    os.close(file_fd)
    os.mkdir(root + "/empty")
    unlink(directory, "empty", 0x200)
    os.mkdir(root + "/nonempty")
    with open(root + "/nonempty/child", "wb") as stream:
        stream.write(b"preserved")
    unlink(directory, "nonempty", 0x200, errno.ENOTEMPTY)
    assert open(root + "/nonempty/child", "rb").read() == b"preserved"
    os.unlink(root + "/nonempty/child")
    unlink(directory, "nonempty", 0x200)
    os.symlink("absent", root + "/dangling")
    unlink(directory, "dangling", 0x200, errno.ENOTDIR)
    unlink(directory, "dangling")
    assert not os.path.lexists(root + "/dangling")
    os.symlink("loop", root + "/loop")
    unlink(directory, "loop/child", error=errno.ELOOP)
    unlink(directory, "loop")

    class Filter(ctypes.Structure):
        _fields_ = [("code", ctypes.c_ushort), ("jt", ctypes.c_ubyte),
                    ("jf", ctypes.c_ubyte), ("k", ctypes.c_uint)]

    class Program(ctypes.Structure):
        _fields_ = [("length", ctypes.c_ushort), ("filter", ctypes.POINTER(Filter))]

    child = os.fork()
    if child == 0:
        try:
            filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, 35),
                                   Filter(0x06, 0, 0, 0x50000 | errno.EPERM),
                                   Filter(0x06, 0, 0, 0x7fff0000))
            program = Program(4, filters)
            assert libc.prctl(38, 1, 0, 0, 0) == 0
            assert libc.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
            unlink(directory, "file.dpkg-new", error=errno.EPERM)
            unlink(directory, "file.dpkg-new", 0x200, errno.EPERM)
            os._exit(0)
        except BaseException:
            os._exit(1)
    assert os.waitpid(child, 0)[1] == 0
    os.close(directory)
print("PASS dpkg unlink probes: fresh absence/create, dirfd, flags, errors, symlinks, live FD, seccomp")
