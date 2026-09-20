#!/usr/bin/env python3
"""Unlink entry: read-only boundary vectors, reused addresses and fallbacks."""
import ctypes
import errno
import os
import signal
import tempfile

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)
libc.mmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int,
                      ctypes.c_int, ctypes.c_int, ctypes.c_long]
libc.mmap.restype = ctypes.c_void_p
libc.mprotect.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int]
libc.munmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
page = os.sysconf('SC_PAGESIZE')
memory = libc.mmap(None, page * 2, 3, 0x22, -1, 0)
assert memory and memory != ctypes.c_void_p(-1).value
assert libc.munmap(memory + page, page) == 0


def unlink(fd, address, expected=0, flags=0):
    result = libc.syscall(35, fd, address, flags)
    if expected:
        assert result == -1 and ctypes.get_errno() == expected, (result, ctypes.get_errno())
    else:
        assert result == 0, ctypes.get_errno()


try:
    with tempfile.TemporaryDirectory(prefix='unlink-memory-', dir='/tmp') as root:
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            # Same address, distinct names, with NUL at the last readable byte.
            address = memory + page - len(b'file0\0')
            for index in range(4):
                name = ('file%d' % index).encode()
                with open(root + '/' + name.decode(), 'wb') as stream:
                    stream.write(b'content')
                assert libc.mprotect(memory, page, 3) == 0
                ctypes.memmove(address, name + b'\0', len(name) + 1)
                assert libc.mprotect(memory, page, 1) == 0
                unlink(directory, ctypes.c_void_p(address))
                assert ctypes.string_at(address) == name
                assert not os.path.lexists(root + '/' + name.decode())
                unlink(directory, ctypes.c_void_p(address), errno.ENOENT)
            unlink(directory, ctypes.c_void_p(memory + page), errno.EFAULT)
            assert libc.mprotect(memory, page, 3) == 0
            ctypes.memmove(memory + page - 3, b'bad', 3)
            unlink(directory, ctypes.c_void_p(memory + page - 3), errno.EFAULT)
            unlink(directory, b'x' * 4096, errno.ENAMETOOLONG)
            unlink(directory, b'', errno.ENOENT)
            # These require the original kernel path after local callbacks.
            os.mkdir(root + '/directory')
            unlink(directory, b'directory', errno.EISDIR)
            unlink(directory, b'directory', flags=0x200)
            os.mkfifo(root + '/fifo')
            unlink(directory, b'fifo')
            with open(root + '/target', 'wb') as stream:
                stream.write(b'preserved')
            os.symlink('target', root + '/symlink')
            unlink(directory, b'symlink')
            assert open(root + '/target', 'rb').read() == b'preserved'
            deep = root
            for _ in range(16):
                deep += '/' + 'd' * 80
                os.mkdir(deep)
            long_path = (deep + '/file').encode()
            assert len(long_path) > 1024
            with open(long_path, 'wb') as stream:
                stream.write(b'long')
            unlink(-100, long_path)
            assert not os.path.exists(long_path)
        finally:
            os.close(directory)
finally:
    assert libc.munmap(memory, page) == 0
print('PASS unlink memory: readonly page edge, address reuse, EFAULT/ENAMETOOLONG, FIFO/symlink/directory fallback, long path')
