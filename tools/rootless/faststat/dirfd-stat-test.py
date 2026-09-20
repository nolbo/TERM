#!/usr/bin/env python3
"""Compare accelerated dirfd metadata with the canonical PRoot syscalls."""
import ctypes
import errno
import os
import shutil
import signal
import tempfile

signal.alarm(40)
c = ctypes.CDLL(None, use_errno=True)
c.proot_faststat_hits.restype = ctypes.c_ulong
c.fstatat.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_int]
c.statx.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_uint, ctypes.c_void_p]


def check(fd, path, flags=0, fast=False):
    encoded = os.fsencode(path)
    for number, size in ((79, 128), (291, 256)):
        expected = ctypes.create_string_buffer(size)
        actual = ctypes.create_string_buffer(size)
        args = (fd, encoded, expected, flags) if number == 79 else (fd, encoded, flags, 0x7ff, expected)
        result = c.syscall(number, *args)
        error = ctypes.get_errno()
        before = c.proot_faststat_hits()
        observed = c.fstatat(fd, encoded, actual, flags) if number == 79 else c.statx(fd, encoded, flags, 0x7ff, actual)
        assert observed == result, (number, path, result, observed)
        if result == 0:
            assert actual.raw == expected.raw, (number, path)
            if fast:
                assert c.proot_faststat_hits() > before, (number, path, 'not accelerated')
        else:
            assert ctypes.get_errno() == error, (number, path, error, ctypes.get_errno())


root = tempfile.mkdtemp(prefix='dirfd-stat-', dir='/tmp')
source = tempfile.mkdtemp(prefix='dirfd-source-', dir='/tmp')
alias = tempfile.mkdtemp(prefix='dirfd-alias-', dir='/')
fd = -1
mounted = []
try:
    with open(root + '/file', 'wb') as output:
        output.write(b'original')
    with open(source + '/file', 'wb') as output:
        output.write(b'different binding')
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    check(fd, 'file', fast=True)
    check(fd, './file', fast=True)
    check(fd, 'file', 0x100, fast=True)
    check(fd, '')
    check(fd, '', 0x1000)
    check(fd, 'missing')
    check(-1, 'file')
    regular = os.open(root + '/file', os.O_RDONLY)
    try:
        check(regular, 'file')
        check(regular, root + '/file', fast=True)
    finally:
        os.close(regular)
    os.mkdir(root + '/nested')
    check(fd, 'nested/../file')
    os.symlink('file', root + '/relative-link')
    os.symlink(root + '/file', root + '/absolute-link')
    for name in ('relative-link', 'absolute-link'):
        check(fd, name)
        check(fd, name, 0x100)
    nested = root + '/nested'
    assert c.mount(os.fsencode(source), os.fsencode(nested), None, 4096, None) == 0
    mounted.append(nested)
    check(fd, 'nested/file')
    assert c.umount2(os.fsencode(mounted.pop()), 0) == 0
    assert c.mount(os.fsencode(root), os.fsencode(alias), None, 4096, None) == 0
    mounted.append(alias)
    check(fd, 'file')
    assert c.umount2(os.fsencode(mounted.pop()), 0) == 0
    check(fd, 'file', fast=True)
    moved = root + '-moved'
    os.rename(root, moved)
    os.mkdir(root)
    with open(root + '/file', 'wb') as output:
        output.write(b'replacement has a different inode and size')
    check(fd, 'file', fast=True)
    os.unlink(root + '/file')
    os.rmdir(root)
    root = moved
    os.link(root + '/file', root + '/hard')
    check(fd, 'file')
    check(fd, 'hard', 0x100)
    os.unlink(root + '/hard')
    check(fd, 'file')
    # Remove the remaining alias through PRoot before generic rmtree encounters
    # its visible implementation backing file (an existing l2s limitation).
    os.unlink(root + '/file')
    # Both the original metadata syscall and auxiliary pathname inspection can
    # be denied. Compare with the canonical result instead of bypassing policy.
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte), ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    with open(root + '/ordinary', 'wb') as output:
        output.write(b'seccomp')
    for number in (78, 79, 291):
        child = os.fork()
        if child == 0:
            try:
                filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, number),
                    Filter(0x06, 0, 0, 0x50000 | errno.EPERM), Filter(0x06, 0, 0, 0x7fff0000))
                program = Program(4, filters)
                assert c.prctl(38, 1, 0, 0, 0) == 0
                assert c.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
                check(fd, 'ordinary')
                os._exit(0)
            except BaseException:
                os._exit(1)
        assert os.waitpid(child, 0)[1] == 0, number
    print('PASS dirfd stat/statx: fast hits, raw equality, bad FDs, empty path, symlinks, bindings, rename/replacement, hardlinks, seccomp')
finally:
    for target in reversed(mounted):
        assert c.umount2(os.fsencode(target), 0) == 0
    if fd >= 0:
        os.close(fd)
    for directory in (root, source, alias):
        shutil.rmtree(directory)
