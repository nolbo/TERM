#!/usr/bin/env python3
"""Run with --link2symlink and accelerated preload, in a disposable guest root."""
import ctypes
import os
import signal
import stat
import tempfile

signal.alarm(30)
libc = ctypes.CDLL(None)
for name in ('proot_faststat_hits', 'proot_fastopen_hits', 'proot_fastcreate_hits', 'proot_fastfd_hits'):
    getattr(libc, name).restype = ctypes.c_ulong
before = [getattr(libc, name)() for name in ('proot_faststat_hits', 'proot_fastopen_hits', 'proot_fastcreate_hits', 'proot_fastfd_hits')]
with tempfile.TemporaryDirectory() as directory:
    a, b, c = [directory + '/' + name for name in ('a', 'b', 'c')]
    fd = os.open(a, os.O_CREAT | os.O_RDWR, 0o600)
    os.write(fd, b'original')
    assert os.fstat(fd).st_size == 8
    assert os.stat(a).st_nlink == 1
    os.close(os.open(a, os.O_RDONLY))
    os.link(a, b)
    assert os.stat(a).st_ino == os.stat(b).st_ino
    assert os.stat(a).st_nlink == os.lstat(b).st_nlink == 2
    assert stat.S_ISREG(os.lstat(b).st_mode)
    # Open both before and after conversion into an emulated hardlink.
    alias_fd = os.open(b, os.O_RDONLY)
    assert os.fstat(alias_fd).st_nlink == os.fstat(fd).st_nlink == 2
    assert os.read(alias_fd, 32) == b'original'
    os.link(b, c)
    assert os.stat(c).st_nlink == 3
    os.unlink(b)
    assert os.stat(a).st_nlink == os.stat(c).st_nlink == 2
    os.rename(c, b)
    assert os.stat(b).st_ino == os.stat(a).st_ino
    with open(c, 'wb') as output:
        output.write(b'replacement')
    os.replace(c, b)
    assert open(b, 'rb').read() == b'replacement'
    assert open(a, 'rb').read() == b'original'
    assert os.stat(a).st_nlink == 1
    os.unlink(a)
    assert os.fstat(fd).st_nlink == 0
    os.lseek(fd, 0, 0)
    assert os.read(fd, 32) == b'original'
    os.close(alias_fd)
    os.close(fd)
    os.symlink('b', a)
    assert stat.S_ISLNK(os.lstat(a).st_mode)
    assert open(a, 'rb').read() == b'replacement'
    os.unlink(a)
    assert open(b, 'rb').read() == b'replacement'
after = [getattr(libc, name)() for name in ('proot_faststat_hits', 'proot_fastopen_hits', 'proot_fastcreate_hits', 'proot_fastfd_hits')]
assert all(x > y for x, y in zip(after, before)), (before, after)
print('PASS emulated hardlinks: stat/lstat/fstat, aliases, rename overwrite, unlink, open FD lifetime, symlinks; ordinary acceleration hits', after)
