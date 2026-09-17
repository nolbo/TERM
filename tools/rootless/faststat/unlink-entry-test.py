#!/usr/bin/env python3
"""Validate regular unlink, directory fallback and syscall policy."""
import ctypes
import errno
import os
import shutil
import signal
import tempfile

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)
hits = getattr(libc, 'proot_fastunlink_hits', None)
if hits:
    hits.restype = ctypes.c_ulong
before = hits() if hits else 0
root = tempfile.mkdtemp(prefix='unlink-entry-', dir='/tmp')

def rejects(path, flags, expected):
    assert libc.unlinkat(-100, path.encode(), flags) == -1
    assert ctypes.get_errno() == expected, (path, flags, ctypes.get_errno())

try:
    path = root + '/file'
    with open(path, 'wb') as output:
        output.write(b'alive')
    os.link(path, root + '/hard')
    fd = os.open(path, os.O_RDONLY)
    os.unlink(path)
    assert os.fstat(fd).st_nlink == 1
    assert os.read(fd, 5) == b'alive'
    os.unlink(root + '/hard')
    assert os.fstat(fd).st_nlink == 0
    os.close(fd)
    rejects(path, 0, errno.ENOENT)
    assert libc.syscall(35, -100, ctypes.c_void_p(1), 0) == -1
    assert ctypes.get_errno() == errno.EFAULT
    with open(path, 'wb') as output:
        output.write(b'kept')
    rejects(path, 0x40000000, errno.EINVAL)
    rejects(path, 0x200, errno.ENOTDIR)
    os.symlink('file', root + '/link')
    os.unlink(root + '/link')
    assert open(path, 'rb').read() == b'kept'
    os.mkdir(root + '/directory')
    rejects(root + '/directory', 0, errno.EISDIR)
    assert libc.syscall(35, -100, (root + '/directory').encode(), 0x200) == 0
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    assert libc.syscall(35, directory, b'file', 0) == 0
    os.close(directory)
    with open(path, 'wb') as output:
        output.write(b'protected')
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    child = os.fork()
    if child == 0:
        try:
            filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, 35),
                Filter(0x06, 0, 0, 0x50000 | errno.EPERM), Filter(0x06, 0, 0, 0x7fff0000))
            program = Program(4, filters)
            assert libc.prctl(38, 1, 0, 0, 0) == 0
            assert libc.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
            rejects(path, 0, errno.EPERM)
            os._exit(0)
        except BaseException:
            os._exit(1)
    _, status = os.waitpid(child, 0)
    assert status == 0
    assert open(path, 'rb').read() == b'protected'
    if os.environ.get('PROOT_FASTUNLINK') == '1':
        assert hits and hits() >= before + 3, 'unlink acceleration was not used'
    print('PASS unlink: live inode, hardlinks, dirfd, symlinks, directory fallback, errno, seccomp')
finally:
    shutil.rmtree(root)
