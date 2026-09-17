#!/usr/bin/env python3
"""Exclusive fast creation plus libc fallback for existing objects."""
import ctypes
import errno
import os
import shutil
import signal
import tempfile
import threading

signal.alarm(40)
libc = ctypes.CDLL(None, use_errno=True)
hits = libc.proot_fastcreate_hits
hits.restype = ctypes.c_ulong
root = tempfile.mkdtemp(prefix='fastcreate-', dir='/tmp')
old_umask = os.umask(0o027)
try:
    path = root + '/file'
    before = hits()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o666)
    assert hits() > before
    os.write(fd, b'first')
    os.close(fd)
    assert os.stat(path).st_mode & 0o777 == 0o640
    before = hits()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.write(fd, b'second')
    os.close(fd)
    assert hits() == before
    assert open(path, 'rb').read() == b'second'
    os.symlink('file', root + '/link')
    fd = os.open(root + '/link', os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    os.write(fd, b'+')
    os.close(fd)
    assert open(path, 'rb').read() == b'second+'
    try:
        os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except OSError as error:
        assert error.errno == errno.EEXIST
    else:
        raise AssertionError('exclusive creation overwrote existing file')
    barrier = threading.Barrier(8)
    winners, errors = [], []
    def create():
        try:
            barrier.wait()
            fd = os.open(root + '/race', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            os.write(fd, b'winner')
            os.close(fd)
            winners.append(1)
        except OSError as error:
            if error.errno != errno.EEXIST:
                errors.append(error)
    threads = [threading.Thread(target=create) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert len(winners) == 1 and not errors
    assert open(root + '/race', 'rb').read() == b'winner'
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    child = os.fork()
    if child == 0:
        try:
            filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, 56),
                Filter(0x06, 0, 0, 0x50000 | errno.EPERM), Filter(0x06, 0, 0, 0x7fff0000))
            program = Program(4, filters)
            assert libc.prctl(38, 1, 0, 0, 0) == 0
            assert libc.prctl(22, 2, ctypes.byref(program), 0, 0) == 0
            try:
                os.open(root + '/denied', os.O_CREAT | os.O_WRONLY, 0o600)
            except OSError as error:
                assert error.errno == errno.EPERM
            else:
                raise AssertionError('seccomp bypass')
            os._exit(0)
        except BaseException:
            os._exit(1)
    _, status = os.waitpid(child, 0)
    assert status == 0 and not os.path.exists(root + '/denied')
    print('PASS fastcreate: creation, umask, existing truncate, symlink append, exclusive race, seccomp; hits=' + str(hits()))
finally:
    os.umask(old_umask)
    shutil.rmtree(root)
