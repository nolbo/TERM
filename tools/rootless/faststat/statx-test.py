#!/usr/bin/env python3
"""Extended metadata, link emulation, memory protection and syscall policy."""
import ctypes
import errno
import os
import signal
import stat
import struct
import sys
import tempfile

signal.alarm(30)
c = ctypes.CDLL(None, use_errno=True)
c.statx.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_int, ctypes.c_uint, ctypes.c_void_p]
c.proot_faststat_hits.restype = ctypes.c_ulong


def query(path, flags=0, mask=0x7ff, directory=-100):
    result = ctypes.create_string_buffer(256)
    assert c.statx(directory, os.fsencode(path), flags, mask, result) == 0, ctypes.get_errno()
    return dict(raw=result.raw, mask=struct.unpack_from('I', result, 0)[0],
                nlink=struct.unpack_from('I', result, 16)[0],
                uid=struct.unpack_from('I', result, 20)[0],
                mode=struct.unpack_from('H', result, 28)[0],
                ino=struct.unpack_from('Q', result, 32)[0],
                size=struct.unpack_from('Q', result, 40)[0])


with tempfile.TemporaryDirectory() as root:
    path = root + '/file'
    with open(path, 'wb') as output:
        output.write(b'statx')
    before = c.proot_faststat_hits()
    value = query(path)
    if '--expect-fast' in sys.argv:
        assert c.proot_faststat_hits() > before, 'statx was not accelerated'
    assert value['size'] == 5 and value['uid'] == 0
    assert value['ino'] == os.stat(path).st_ino and stat.S_ISREG(value['mode'])
    for mask in (0, 1, 0x200, 0x7ff, 0xfff, 0x1fff):
        for flags in (0, 0x100, 0x800, 0x2000, 0x4000):
            extended = query(path, flags, mask)
            original = ctypes.create_string_buffer(256)
            assert c.syscall(291, -100, os.fsencode(path), flags, mask, original) == 0
            # Preserve birth time, mount ID, attributes and every kernel field,
            # including masks that ask for only a subset of the basic fields.
            assert extended['raw'] == original.raw, (flags, mask)

    with open(path, 'ab') as output:
        output.write(b'-changed')
    assert query(path)['size'] == 13
    os.link(path, root + '/alias')
    assert query(root + '/alias')['nlink'] == query(path, 0x100)['nlink'] == 2
    # Binding an emulated alias must retain its virtual link count.
    binding = '/statx-bind-test'
    open(binding, 'wb').close()
    assert c.mount(os.fsencode(path), os.fsencode(binding), None, 4096, None) == 0
    try:
        assert query(binding)['nlink'] == 2, 'binding lost emulated link count'
    finally:
        assert c.umount2(os.fsencode(binding), 0) == 0
        os.unlink(binding)
    os.symlink('file', root + '/symlink')
    assert stat.S_ISLNK(query(root + '/symlink', 0x100)['mode'])
    assert query(root + '/symlink')['size'] == 13
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    try:
        assert query('file', directory=directory)['nlink'] == 2
        assert stat.S_ISDIR(query('', 0x1000, directory=directory)['mode'])
    finally:
        os.close(directory)
    ordinary = root + '/ordinary'
    with open(ordinary, 'wb') as output:
        output.write(b'protected')
    for pointer in (None, ctypes.c_void_p(1)):
        assert c.statx(-100, os.fsencode(ordinary), 0, 0x7ff, pointer) == -1
        assert ctypes.get_errno() == errno.EFAULT
    result = ctypes.create_string_buffer(256)
    assert c.statx(-100, ctypes.c_void_p(1), 0, 0x7ff, result) == -1
    assert ctypes.get_errno() == errno.EFAULT
    page = os.sysconf('SC_PAGESIZE')
    c.mmap.restype = ctypes.c_void_p
    c.mmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_long]
    c.munmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
    memory = c.mmap(None, page, 1, 0x22, -1, 0)
    assert memory != ctypes.c_void_p(-1).value
    try:
        assert c.statx(-100, os.fsencode(ordinary), 0, 0x7ff, memory) == -1
        assert ctypes.get_errno() == errno.EFAULT
    finally:
        assert c.munmap(memory, page) == 0
    child = os.fork()
    if child == 0:
        class F(ctypes.Structure):
            _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte), ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
        class P(ctypes.Structure):
            _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(F))]
        filters = (F * 4)(F(0x20, 0, 0, 0), F(0x15, 0, 1, 291), F(0x06, 0, 0, 0x50000 | errno.EPERM), F(0x06, 0, 0, 0x7fff0000))
        program = P(4, filters)
        assert c.prctl(38, 1, 0, 0, 0) == 0
        assert c.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
        assert c.statx(-100, os.fsencode(ordinary), 0, 0x7ff, result) == -1
        assert ctypes.get_errno() == errno.EPERM
        os._exit(0)
    assert os.waitpid(child, 0)[1] == 0
print('PASS statx: metadata, masks, sync flags, hardlinks, symlinks, dirfd, EFAULT, readonly output, seccomp')
