#!/usr/bin/env python3
"""Rename contents, dirfd resolution, errors, flags and seccomp."""
import ctypes
import errno
import os
import signal
import tempfile

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)
with tempfile.TemporaryDirectory() as directory:
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        for number in (38, 276):
            with open(directory + '/source', 'wb') as output:
                output.write(b'rename source')
            with open(directory + '/target', 'wb') as output:
                output.write(b'old target')
            assert libc.syscall(number, fd, b'source', fd, b'target', 0) == 0
            assert not os.path.exists(directory + '/source')
            assert open(directory + '/target', 'rb').read() == b'rename source'
            assert libc.syscall(number, fd, b'missing', fd, b'target', 0) == -1
            assert ctypes.get_errno() == errno.ENOENT
            assert libc.syscall(number, fd, ctypes.c_void_p(1), fd, b'target', 0) == -1
            assert ctypes.get_errno() == errno.EFAULT
            assert libc.syscall(number, fd, b'', fd, b'target', 0) == -1
            assert ctypes.get_errno() == errno.ENOENT
            os.symlink('target', directory + '/link')
            assert libc.syscall(number, fd, b'link', fd, b'moved-link', 0) == 0
            assert os.readlink(directory + '/moved-link') == 'target'
            os.unlink(directory + '/moved-link')
            assert libc.syscall(number, fd, b'target', fd, ctypes.c_void_p(1), 0) == -1
            assert ctypes.get_errno() == errno.EFAULT
            assert open(directory + '/target', 'rb').read() == b'rename source'
        with open(directory + '/source', 'wb') as output:
            output.write(b'kept')
        assert libc.syscall(276, fd, b'source', fd, b'target', 1) == -1
        assert ctypes.get_errno() == errno.EEXIST
        assert open(directory + '/source', 'rb').read() == b'kept'
        # Nonzero flags must use the kernel fallback, including successful exchange.
        assert libc.syscall(276, fd, b'source', fd, b'target', 2) == 0
        assert open(directory + '/target', 'rb').read() == b'kept'
        assert libc.syscall(276, fd, b'source', fd, b'target', 2) == 0
        # Directory moves retain PRoot's ordinary renameat exit/cwd handling.
        previous = os.getcwd()
        os.makedirs(directory + '/old/child')
        try:
            os.chdir(directory + '/old/child')
            assert libc.syscall(38, fd, b'old', fd, b'new') == 0
            assert os.getcwd() == directory + '/new/child'
            with open('relative', 'wb') as output:
                output.write(b'cwd')
            assert open(directory + '/new/child/relative', 'rb').read() == b'cwd'
        finally:
            os.chdir(previous)
        class Filter(ctypes.Structure):
            _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                        ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
        class Program(ctypes.Structure):
            _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
        for number in (38, 276):
            child = os.fork()
            if child == 0:
                try:
                    filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, number),
                        Filter(0x06, 0, 0, 0x50000 | errno.EPERM), Filter(0x06, 0, 0, 0x7fff0000))
                    program = Program(4, filters)
                    assert libc.prctl(38, 1, 0, 0, 0) == 0
                    assert libc.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
                    assert libc.syscall(number, fd, b'source', fd, b'target', 0) == -1
                    assert ctypes.get_errno() == errno.EPERM
                    os._exit(0)
                except BaseException:
                    os._exit(1)
            assert os.waitpid(child, 0)[1] == 0
            assert open(directory + '/source', 'rb').read() == b'kept'
    finally:
        os.close(fd)
print('PASS rename: contents, dirfd, ENOENT/EFAULT, symlink, exchange, directory cwd, seccomp')
