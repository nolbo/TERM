#!/usr/bin/env python3
"""Shared-path metadata stays fresh and respects original syscall denial."""
import ctypes
import errno
import os
import signal
import tempfile

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)
hits = libc.proot_faststat_hits
hits.restype = ctypes.c_ulong
with tempfile.TemporaryDirectory() as directory:
    path = directory + '/file'
    with open(path, 'wb') as output:
        output.write(b'one')
    before = hits()
    assert os.stat(path).st_size == 3
    with open(path, 'ab') as output:
        output.write(b'two')
    assert os.stat(path).st_size == 6
    assert os.stat(path).st_uid == 0
    previous = os.getcwd()
    os.chdir(directory)
    try:
        assert os.stat('././file').st_size == 6
    finally:
        os.chdir(previous)
    if os.environ.get('PROOT_FASTSTAT_SHARED') == '1':
        assert hits() > before, 'shared metadata fast path not used'
    os.symlink('file', directory + '/link')
    assert os.stat(directory + '/link').st_size == 6
    assert os.path.islink(directory + '/link')
    assert libc.fstatat(-100, ctypes.c_char_p(os.fsencode(path)), ctypes.c_void_p(1), 0) == -1
    assert ctypes.get_errno() == errno.EFAULT
    os.unlink(path)
    try:
        os.stat(path)
        raise AssertionError('deleted file still exists')
    except FileNotFoundError:
        pass
    # Exercise both sides of the bounded path-copy prefix, including its NUL.
    for total in (127, 128, 129, 200):
        boundary = directory + '/' + 'p' * (total - len(directory) - 1)
        with open(boundary, 'wb') as output:
            output.write(b'boundary')
        before = hits()
        assert os.stat(boundary).st_size == 8
        assert open(boundary, 'rb').read() == b'boundary'
        if os.environ.get('PROOT_FASTSTAT_SHARED') == '1':
            assert hits() > before
        page = os.sysconf('SC_PAGESIZE')
        libc.mmap.restype = ctypes.c_void_p
        libc.mmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int,
                             ctypes.c_int, ctypes.c_int, ctypes.c_long]
        libc.mprotect.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int]
        libc.munmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
        memory = libc.mmap(None, page * 2, 3, 0x22, -1, 0)
        assert memory != ctypes.c_void_p(-1).value
        assert libc.munmap(memory + page, page) == 0
        try:
            encoded = os.fsencode(boundary) + b'\0'
            address = memory + page - len(encoded)
            ctypes.memmove(address, encoded, len(encoded))
            assert libc.mprotect(memory, page, 1) == 0
            result = ctypes.create_string_buffer(128)
            assert libc.fstatat(-100, ctypes.c_void_p(address), result, 0) == 0
            assert libc.mprotect(memory, page, 3) == 0
            ctypes.memset(memory + page - 1, ord('x'), 1)
            assert libc.fstatat(-100, ctypes.c_void_p(address), result, 0) == -1
            assert ctypes.get_errno() == errno.EFAULT
        finally:
            assert libc.munmap(memory, page) == 0
        os.unlink(boundary)
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    child = os.fork()
    if child == 0:
        try:
            filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, 79),
                Filter(0x06, 0, 0, 0x50000 | errno.EPERM), Filter(0x06, 0, 0, 0x7fff0000))
            program = Program(4, filters)
            assert libc.prctl(38, 1, 0, 0, 0) == 0
            assert libc.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
            out = ctypes.create_string_buffer(128)
            assert libc.fstatat(-100, os.fsencode(directory), out, 0) == -1
            assert ctypes.get_errno() == errno.EPERM
            os._exit(0)
        except BaseException:
            os._exit(1)
    assert os.waitpid(child, 0)[1] == 0
print('PASS shared stat: hits, fresh size/identity, relative paths, symlinks, EFAULT, deletion, seccomp')
