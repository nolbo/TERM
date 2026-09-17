#!/usr/bin/env python3
"""TCGETS pass-through preserves tty state, errno and guest seccomp."""
import ctypes
import errno
import os
import signal
import tempfile
import termios

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)
master, slave = os.openpty()
try:
    assert os.isatty(master) and os.isatty(slave)
    with tempfile.TemporaryFile() as file:
        assert not os.isatty(file.fileno())
    read, write = os.pipe()
    assert not os.isatty(read) and not os.isatty(write)
    os.close(read)
    os.close(write)
    assert libc.isatty(-1) == 0 and ctypes.get_errno() == errno.EBADF
    assert libc.ioctl(slave, termios.TCGETS, ctypes.c_void_p(1)) == -1
    assert ctypes.get_errno() == errno.EFAULT
    original = termios.tcgetattr(slave)
    modified = list(original)
    modified[3] ^= termios.ECHO
    termios.tcsetattr(slave, termios.TCSANOW, modified)
    assert termios.tcgetattr(slave)[3] == modified[3]
    termios.tcsetattr(slave, termios.TCSANOW, original)
    assert termios.tcgetattr(slave) == original
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    child = os.fork()
    if child == 0:
        try:
            filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, 29),
                Filter(0x06, 0, 0, 0x50000 | errno.EPERM), Filter(0x06, 0, 0, 0x7fff0000))
            program = Program(4, filters)
            assert libc.prctl(38, 1, 0, 0, 0) == 0
            assert libc.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
            assert libc.isatty(slave) == 0 and ctypes.get_errno() == errno.EPERM
            os._exit(0)
        except BaseException:
            os._exit(1)
    _, status = os.waitpid(child, 0)
    assert status == 0
    print('PASS TCGETS: PTY, files, pipes, EBADF, EFAULT, termios roundtrip, seccomp')
finally:
    os.close(slave)
    os.close(master)
