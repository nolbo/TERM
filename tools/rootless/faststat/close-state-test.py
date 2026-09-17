#!/usr/bin/env python3
"""Descriptor reuse must not retain auxv/netlink state or alter pipe EOF."""
import ctypes
import errno
import os
import signal
import socket
import struct
import tempfile

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)
payload = struct.pack('QQQQ', 31, 0x12345678, 0, 0)
libc.getauxval.argtypes = [ctypes.c_ulong]
libc.getauxval.restype = ctypes.c_ulong

def ordinary_reuse(number, path):
    fd = os.open(path, os.O_RDONLY)
    if fd != number:
        os.dup2(fd, number)
        os.close(fd)
    try:
        assert os.read(number, 100) == payload
        address = ctypes.create_string_buffer(128)
        length = ctypes.c_uint(128)
        assert libc.getsockname(number, address, ctypes.byref(length)) == -1
        assert ctypes.get_errno() == errno.ENOTSOCK
    finally:
        os.close(number)

with tempfile.TemporaryDirectory() as directory:
    path = directory + '/file'
    with open(path, 'wb') as output:
        output.write(payload)
    for _ in range(16):
        fd = os.open('/proc/self/auxv', os.O_RDONLY)
        raw = os.read(fd, 16384)
        values = dict(struct.iter_unpack('QQ', raw))
        # PRoot rewrites AT_EXECFN; the rest can describe its loader ELF.
        assert values[31] == libc.getauxval(31)
        assert values[6] == libc.getauxval(6)  # guest AT_PAGESZ
        os.close(fd)
        ordinary_reuse(fd, path)
        sock = socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, 0)
        sock.bind((0, 0))
        sock.getsockname()
        number = sock.fileno()
        duplicate = os.dup(number)
        sock.close()
        ordinary_reuse(number, path)
        os.close(duplicate)
        ordinary_reuse(duplicate, path)
    fd = os.open(path, os.O_RDONLY)
    os.close(fd)
    assert libc.close(fd) == -1 and ctypes.get_errno() == errno.EBADF
    # A write end close must expose EOF after all written data.
    read_fd, write_fd = os.pipe()
    os.write(write_fd, b'pipe data')
    os.close(write_fd)
    assert os.read(read_fd, 100) == b'pipe data'
    assert os.read(read_fd, 100) == b''
    os.close(read_fd)
print('PASS close state: auxv, netlink and duplicate FD reuse, EBADF, pipe EOF')
