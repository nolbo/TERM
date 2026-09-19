#!/usr/bin/env python3
"""Rename path vectors at mapping boundaries and beyond the short-path window."""
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
try:
    with tempfile.TemporaryDirectory(prefix='rename-memory-', dir='/tmp') as root:
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            for number in (38, 276):
                with open(root + '/source', 'wb') as stream:
                    stream.write(b'boundary content')
                address = memory + page - len(b'source\0')
                assert libc.mprotect(memory, page, 3) == 0
                ctypes.memmove(address, b'source\0', len(b'source\0'))
                assert libc.mprotect(memory, page, 1) == 0
                # NUL at the final accessible byte, on a read-only mapping.
                assert libc.syscall(number, directory, ctypes.c_void_p(address),
                                    directory, b'target', 0) == 0
                assert open(root + '/target', 'rb').read() == b'boundary content'
                # Same boundary for the second vector; restore the source name.
                assert libc.syscall(number, directory, b'target', directory,
                                    ctypes.c_void_p(address), 0) == 0
                assert libc.syscall(number, directory, ctypes.c_void_p(address),
                                    directory, ctypes.c_void_p(memory + page), 0) == -1
                assert ctypes.get_errno() == errno.EFAULT
                assert open(root + '/source', 'rb').read() == b'boundary content'
                assert libc.mprotect(memory, page, 3) == 0
                ctypes.memmove(memory + page - 3, b'bad', 3)
                assert libc.syscall(number, directory, ctypes.c_void_p(memory + page - 3),
                                    directory, b'target', 0) == -1
                assert ctypes.get_errno() == errno.EFAULT
                os.unlink(root + '/source')
            deep = root
            for _ in range(16):
                deep += '/' + 'd' * 80
                os.mkdir(deep)
            source = deep + '/source'
            target = deep + '/target'
            assert len(os.fsencode(source)) > 1024
            with open(source, 'wb') as stream:
                stream.write(b'long path')
            os.rename(source, target)
            assert open(target, 'rb').read() == b'long path'
            assert libc.syscall(38, directory, b'x' * 4096, directory, b'target') == -1
            assert ctypes.get_errno() == errno.ENAMETOOLONG
        finally:
            os.close(directory)
finally:
    assert libc.munmap(memory, page) == 0
print('PASS rename memory: readonly page edges, invalid second vector, unterminated and long paths')
