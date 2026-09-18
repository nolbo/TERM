#!/usr/bin/env python3
"""ARM64 fchmodat2 path translation and GNU tar directory restoration."""
import ctypes
import errno
import os
import signal
import stat
import subprocess
import tarfile
import tempfile

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)


def chmod(path, mode, flags=0, fd=-100):
    ctypes.set_errno(0)
    return libc.syscall(452, fd, os.fsencode(path), mode, flags)


with tempfile.TemporaryDirectory(prefix='terminal-fchmodat2-') as root:
    path = root + '/file'
    with open(path, 'wb') as stream:
        stream.write(b'unchanged')
    assert chmod(path, 0o640, 0x100) == 0, ctypes.get_errno()
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o640
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    try:
        assert chmod('file', 0o600, fd=fd) == 0
        assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
        assert chmod('', 0o750, 0x1000, fd) == 0
        assert stat.S_IMODE(os.stat(root).st_mode) == 0o750
    finally:
        os.close(fd)
    previous = os.getcwd()
    try:
        os.chdir(root)
        assert chmod('', 0o751, 0x1000) == 0
        assert stat.S_IMODE(os.stat(root).st_mode) == 0o751
    finally:
        os.chdir(previous)
    os.symlink('file', root + '/link')
    assert chmod(root + '/link', 0o644) == 0
    assert chmod(root + '/link', 0o777, 0x100) == -1
    assert ctypes.get_errno() in (errno.EOPNOTSUPP, errno.ENOTSUP)
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o644
    assert chmod(root + '/missing', 0o600) == -1
    assert ctypes.get_errno() == errno.ENOENT
    assert chmod(path, 0o777, 0x40000000) == -1
    assert ctypes.get_errno() == errno.EINVAL
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o644
    assert libc.syscall(452, -100, ctypes.c_void_p(1), 0o600, 0) == -1
    assert ctypes.get_errno() == errno.EFAULT
    assert open(path, 'rb').read() == b'unchanged'

    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]

    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]

    child = os.fork()
    if child == 0:
        try:
            filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, 452),
                Filter(0x06, 0, 0, 0x50000 | errno.EPERM), Filter(0x06, 0, 0, 0x7fff0000))
            program = Program(4, filters)
            assert libc.prctl(38, 1, 0, 0, 0) == 0
            assert libc.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
            assert chmod(path, 0o777) == -1
            assert ctypes.get_errno() == errno.EPERM
            os._exit(0)
        except BaseException:
            os._exit(1)
    assert os.waitpid(child, 0)[1] == 0
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o644

    # GNU tar delays directory chmod until after extracting its children.
    archive = root + '/fixture.tar'
    with tarfile.open(archive, 'w') as output:
        for name, mode in [('parent', 0o751), ('parent/child', 0o705)]:
            entry = tarfile.TarInfo(name)
            entry.type = tarfile.DIRTYPE
            entry.mode = mode
            output.addfile(entry)
    target = root + '/extracted'
    os.mkdir(target)
    subprocess.run(['tar', '-xf', archive, '-C', target], check=True)
    assert stat.S_IMODE(os.stat(target + '/parent').st_mode) == 0o751
    assert stat.S_IMODE(os.stat(target + '/parent/child').st_mode) == 0o705

print('FCHMODAT2_PASS')
