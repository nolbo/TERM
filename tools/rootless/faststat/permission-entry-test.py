#!/usr/bin/env python3
"""Check real mutations, fallback errors and guest seccomp denial on ARM64."""
import ctypes
import errno
import os
import signal
import stat
import tempfile

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)

def call(number, path, a, b=0, flags=0):
    return libc.syscall(number, -100, os.fsencode(path), a, b, flags)

with tempfile.TemporaryDirectory() as directory:
    path = directory + '/file'
    with open(path, 'wb') as output:
        output.write(b'permission test')
    for mode in (0o600, 0o755, 0o640, 0o4755):
        assert call(53, path, mode) == 0
        assert stat.S_IMODE(os.stat(path).st_mode) == mode
    assert call(54, path, 0, 0) == 0
    assert call(54, path, -1, -1) == 0
    assert call(54, path, 1234, 5678) == 0  # fake-root permission handling
    assert open(path, 'rb').read() == b'permission test'
    link = directory + '/link'
    os.symlink('file', link)
    assert call(53, link, 0o644) == 0
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o644
    assert call(54, link, 0, 0, 0x100) == 0
    assert call(53, directory, 0o700) == 0
    assert stat.S_IMODE(os.stat(directory).st_mode) == 0o700
    for number in (53, 54):
        assert call(number, directory + '/missing', 0) == -1
        assert ctypes.get_errno() == errno.ENOENT
        assert libc.syscall(number, -100, ctypes.c_void_p(1), 0, 0, 0) == -1
        assert ctypes.get_errno() == errno.EFAULT
    assert call(54, path, 0, 0, 0x40000000) == -1
    assert ctypes.get_errno() == errno.EINVAL
    directory_fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        assert libc.syscall(53, directory_fd, b'file', 0o600) == 0
        assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
        assert libc.syscall(54, directory_fd, b'file', 0, 0, 0) == 0
        assert libc.syscall(54, directory_fd, b'', 0, 0, 0x1000) == 0
        assert libc.syscall(53, directory_fd, b'', 0o600) == -1
        assert ctypes.get_errno() == errno.ENOENT
    finally:
        os.close(directory_fd)
    assert call(53, path, 0o644) == 0

    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    for number in (53, 54):
        child = os.fork()
        if child == 0:
            try:
                filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, number),
                    Filter(0x06, 0, 0, 0x50000 | errno.EPERM), Filter(0x06, 0, 0, 0x7fff0000))
                program = Program(4, filters)
                assert libc.prctl(38, 1, 0, 0, 0) == 0
                assert libc.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
                assert call(number, path, 0) == -1 and ctypes.get_errno() == errno.EPERM
                os._exit(0)
            except BaseException:
                os._exit(1)
        assert os.waitpid(child, 0)[1] == 0
        assert stat.S_IMODE(os.stat(path).st_mode) == 0o644
print('PASS permission entry: modes, ownership, symlink/directory fallback, errors, seccomp')
