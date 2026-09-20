#!/usr/bin/env python3
"""Read-only open dispatch: real file state, fallback and syscall policy."""
import ctypes
import errno
import fcntl
import os
import shutil
import signal
import tempfile
import threading

signal.alarm(40)
libc = ctypes.CDLL(None, use_errno=True)
hits = libc.proot_fastopen_hits
hits.restype = ctypes.c_ulong
root = tempfile.mkdtemp(prefix='fastopen-', dir='/tmp')
path = root + '/file'
target = tempfile.mkdtemp(prefix='fastopen-bind-', dir='/')
mounted = False

def read_file(name, expected=b'sample', flags=0):
    fd = os.open(name, os.O_RDONLY | os.O_CLOEXEC | flags)
    try:
        assert os.read(fd, 32) == expected
        assert fcntl.fcntl(fd, fcntl.F_GETFD) == fcntl.FD_CLOEXEC
    finally:
        os.close(fd)

def rejects(name, flags, error):
    try:
        fd = os.open(name, flags)
    except OSError as actual:
        assert actual.errno == error, actual
    else:
        os.close(fd)
        raise AssertionError('unexpected success')

try:
    with open(path, 'wb') as output:
        output.write(b'sample')
    before = hits()
    read_file(path)
    assert hits() > before, 'open was not accelerated'
    os.symlink('file', root + '/link')
    before = hits()
    read_file(root + '/link')
    assert hits() == before
    rejects(root + '/link', os.O_RDONLY | os.O_NOFOLLOW, errno.ELOOP)
    # Full-path O_PATH probes must reject intermediate links whose kernel
    # resolution differs from the guest namespace, then let PRoot resolve them.
    os.mkdir(root + '/directory')
    with open(root + '/directory/nested', 'wb') as output:
        output.write(b'nested')
    for name, destination in [('relative-directory', 'directory'),
                              ('absolute-directory', root + '/directory')]:
        os.symlink(destination, root + '/' + name)
        before = hits()
        read_file(root + '/' + name + '/nested', b'nested')
        assert hits() == before, 'intermediate symlink bypassed PRoot'
    rejects(path, os.O_RDONLY | os.O_DIRECTORY, errno.ENOTDIR)
    rejects(root + '/missing', os.O_RDONLY, errno.ENOENT)
    assert libc.open(ctypes.c_void_p(1), 0) == -1 and ctypes.get_errno() == errno.EFAULT
    os.mkfifo(root + '/fifo')
    fd = os.open(root + '/fifo', os.O_RDONLY | os.O_NONBLOCK)
    assert os.read(fd, 1) == b''
    os.close(fd)
    # Truncation and creation must keep their side effects through libc fallback.
    fd = os.open(path, os.O_WRONLY | os.O_TRUNC)
    os.write(fd, b'changed')
    os.close(fd)
    read_file(path, b'changed')
    os.rename(path, root + '/old')
    with open(path, 'wb') as output:
        output.write(b'new')
    read_file(path, b'new')
    read_file(root + '/old', b'changed')
    assert libc.mount(root.encode(), target.encode(), None, 4096, None) == 0
    mounted = True
    before = hits()
    read_file(target + '/file', b'new')
    assert hits() > before, 'top-level bind was not accelerated'
    assert libc.umount2(target.encode(), 0) == 0
    mounted = False
    rejects(target + '/file', os.O_RDONLY, errno.ENOENT)
    # A regular-looking procfs file under an arbitrary bind name must retain
    # PRoot's auxv emulation and must not enter the ordinary-storage fast path.
    assert libc.mount(b'/proc', target.encode(), None, 4096, None) == 0
    mounted = True
    auxv = (target + '/' + str(os.getpid()) + '/auxv').encode()
    raw = libc.syscall(56, -100, auxv, os.O_RDONLY, 0)
    assert raw >= 0
    expected = os.read(raw, 4096)
    os.close(raw)
    before = hits()
    fd = os.open(auxv, os.O_RDONLY)
    assert os.read(fd, 4096) == expected and hits() == before
    os.close(fd)
    assert libc.umount2(target.encode(), 0) == 0
    mounted = False
    errors = []
    def worker():
        try:
            for _ in range(50):
                read_file(path, b'new')
        except BaseException as error:
            errors.append(error)
    threads = [threading.Thread(target=worker) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert not errors, errors
    # A filter denying original openat must also deny the accelerated reopen.
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    for number in (56, 437, 44, 78):  # openat denied; openat2/fstatfs must fall back.
        child = os.fork()
        if child == 0:
            try:
                instructions = (Filter * 4)(Filter(0x20, 0, 0, 0),
                    Filter(0x15, 0, 1, number), Filter(0x06, 0, 0, 0x50000 | errno.EPERM),
                    Filter(0x06, 0, 0, 0x7fff0000))
                program = Program(4, instructions)
                assert libc.prctl(38, 1, 0, 0, 0) == 0
                assert libc.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
                if number == 56:
                    rejects(path, os.O_RDONLY, errno.EPERM)
                else:
                    read_file(path, b'new')
                os._exit(0)
            except BaseException:
                os._exit(1)
        _, status = os.waitpid(child, 0)
        assert status == 0, (number, status)
    print('PASS fastopen: fresh data, flags, links, FIFO, EFAULT, bind/unmount, procfs, 8 threads, fork, seccomp; hits=' + str(hits()))
finally:
    if mounted:
        assert libc.umount2(target.encode(), 0) == 0
    shutil.rmtree(target)
    shutil.rmtree(root)
