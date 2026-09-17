#!/usr/bin/env python3
"""Fresh real/effective IDs, per-thread changes, fork and seccomp."""
import ctypes
import errno
import os
import signal
import threading

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)
def ids():
    return (os.getuid(), os.geteuid(), os.getgid(), os.getegid())
assert ids() == (0, 0, 0, 0)
child = os.fork()
if child == 0:
    try:
        os.setresgid(101, 102, 0)
        os.setresuid(201, 202, 0)
        assert ids() == (201, 202, 101, 102)
        assert os.getresuid() == (201, 202, 0)
        assert os.getresgid() == (101, 102, 0)
        os.seteuid(0)
        assert ids() == (201, 0, 101, 102)
        os._exit(0)
    except BaseException:
        os._exit(1)
_, status = os.waitpid(child, 0)
assert status == 0 and ids() == (0, 0, 0, 0)
errors = []
def worker(index):
    try:
        assert libc.syscall(149, 500 + index, 600 + index, 0) == 0
        assert libc.syscall(147, 300 + index, 400 + index, 0) == 0
        for _ in range(100):
            assert ids() == (300 + index, 400 + index, 500 + index, 600 + index)
    except BaseException as error:
        errors.append(error)
threads = [threading.Thread(target=worker, args=(i,)) for i in range(8)]
for thread in threads:
    thread.start()
for thread in threads:
    thread.join()
assert not errors and ids() == (0, 0, 0, 0), errors
class Filter(ctypes.Structure):
    _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
class Program(ctypes.Structure):
    _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
for number in (174, 175, 176, 177):
    child = os.fork()
    if child == 0:
        try:
            filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, number),
                Filter(0x06, 0, 0, 0x50000 | errno.EPERM), Filter(0x06, 0, 0, 0x7fff0000))
            program = Program(4, filters)
            assert libc.prctl(38, 1, 0, 0, 0) == 0
            assert libc.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
            assert libc.syscall(number) == -1 and ctypes.get_errno() == errno.EPERM
            os._exit(0)
        except BaseException:
            os._exit(1)
    _, status = os.waitpid(child, 0)
    assert status == 0, number
print('PASS ID entry: real/effective/saved IDs, 8 threads, fork isolation, seccomp')
