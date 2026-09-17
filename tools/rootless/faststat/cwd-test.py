#!/usr/bin/env python3
"""Virtual cwd publication across chdir, shared threads, fork and rename."""
import ctypes
import errno
import os
import shutil
import signal
import tempfile
import threading

signal.alarm(40)
libc = ctypes.CDLL(None)
for name in ('open', 'create', 'unlink'):
    getattr(libc, 'proot_fast' + name + '_hits').restype = ctypes.c_ulong
root = tempfile.mkdtemp(prefix='fastcwd-', dir='/tmp')
original = os.getcwd()
a, b = root + '/a', root + '/b'
os.mkdir(a)
os.mkdir(b)
for directory, data in ((a, b'a'), (b, b'b')):
    with open(directory + '/file', 'wb') as output:
        output.write(data)

def read_relative(expected, accelerate=True):
    before = libc.proot_fastopen_hits()
    fd = os.open('file', os.O_RDONLY)
    assert os.read(fd, 1) == expected
    os.close(fd)
    if accelerate:
        assert libc.proot_fastopen_hits() > before

try:
    os.chdir(a)
    read_relative(b'a')
    for spelling in ('./file', '././file', a + '//./file'):
        before = libc.proot_fastopen_hits()
        fd = os.open(spelling, os.O_RDONLY)
        assert os.read(fd, 1) == b'a'
        os.close(fd)
        assert libc.proot_fastopen_hits() > before
    for spelling in ('file/.', './file//.', 'file/'):
        try:
            fd = os.open(spelling, os.O_RDONLY)
        except OSError as error:
            assert error.errno == errno.ENOTDIR
        else:
            os.close(fd)
            raise AssertionError('trailing directory requirement lost')
    before = libc.proot_fastcreate_hits()
    fd = os.open('././new', os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.write(fd, b'new')
    os.close(fd)
    assert libc.proot_fastcreate_hits() > before
    before = libc.proot_fastunlink_hits()
    os.unlink('./new')
    assert libc.proot_fastunlink_hits() > before and not os.path.exists(a + '/new')
    ready, changed = threading.Event(), threading.Event()
    errors = []
    def sibling():
        try:
            read_relative(b'a')
            ready.set()
            assert changed.wait(10)
            # One fallback may refresh an invalidated sibling's snapshot.
            read_relative(b'b', False)
            read_relative(b'b')
        except BaseException as error:
            errors.append(error)
    thread = threading.Thread(target=sibling)
    thread.start()
    assert ready.wait(10)
    os.chdir(b)
    changed.set()
    thread.join(10)
    assert not thread.is_alive() and not errors, errors
    try:
        os.chdir(root + '/missing')
    except FileNotFoundError:
        pass
    else:
        raise AssertionError('missing cwd accepted')
    read_relative(b'b')
    fd = os.open(a, os.O_RDONLY | os.O_DIRECTORY)
    other = os.open('file', os.O_RDONLY, dir_fd=fd)
    assert os.read(other, 1) == b'a'
    os.close(other)
    os.fchdir(fd)
    os.close(fd)
    read_relative(b'a')
    moved = root + '/moved'
    os.rename(a, moved)
    read_relative(b'a')
    child = os.fork()
    if child == 0:
        try:
            os.chdir(b)
            read_relative(b'b')
            os._exit(0)
        except BaseException:
            os._exit(1)
    _, status = os.waitpid(child, 0)
    assert status == 0
    read_relative(b'a')
    print('PASS relative open/create/unlink: chdir, fchdir, sibling, failed chdir, real dirfd, rename cwd, fork isolation')
finally:
    os.chdir(original)
    shutil.rmtree(root)
