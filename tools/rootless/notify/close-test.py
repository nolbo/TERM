#!/usr/bin/env python3
"""Close notification regression; isolated guest process and temporary files."""
import ctypes
import errno
import os
import signal
import socket
import struct
import tempfile
from concurrent.futures import ThreadPoolExecutor

signal.alarm(30)
for _ in range(32):
    fd = os.open('/proc/self/auxv', os.O_RDONLY)
    assert os.read(fd, 4096)
    os.close(fd)
    with tempfile.TemporaryFile() as file:
        # A stale auxv_fd would rewrite this AT_EXECFN-looking ordinary data.
        assert file.fileno() == fd
        payload = struct.pack('QQQQ', 31, 1234, 0, 0)
        file.write(payload)
        file.seek(0)
        assert os.read(fd, len(payload)) == payload
    route = socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, socket.NETLINK_ROUTE)
    fd = route.fileno()
    route.close()
    first, second = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
    with first, second:
        assert first.fileno() == fd
        first.send(b'ordinary after netlink')
        assert second.recv(64) == b'ordinary after netlink'


def worker(number):
    for index in range(100):
        with tempfile.TemporaryFile() as file:
            payload = f'{number}:{index}'.encode()
            file.write(payload)
            file.seek(0)
            assert file.read() == payload


with ThreadPoolExecutor(max_workers=8) as pool:
    list(pool.map(worker, range(8)))

# A higher-precedence guest filter must keep close from reaching CONTINUE.
child = os.fork()
if child == 0:
    libc = ctypes.CDLL(None, use_errno=True)
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, 57),
                          Filter(0x06, 0, 0, 0x50000 | errno.EPERM),
                          Filter(0x06, 0, 0, 0x7fff0000))
    program = Program(4, filters)
    fd = os.open('/dev/null', os.O_RDONLY)
    assert libc.prctl(38, 1, 0, 0, 0) == 0
    assert libc.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
    assert libc.syscall(57, fd) == -1 and ctypes.get_errno() == errno.EPERM
    assert os.fstat(fd)
    os._exit(0)
_, status = os.waitpid(child, 0)
assert os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0
print('PASS close: auxv/netlink FD reuse, 8 threads/800 files, guest seccomp denial')
