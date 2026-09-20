#!/usr/bin/env python3
"""Shared path-rule publication test; no direct-open dispatch yet."""
import ctypes
import errno
import os
import shutil
import signal
import tempfile
import threading

signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)
lookup = libc.proot_fastpath_translate
lookup.argtypes = [ctypes.c_char_p, ctypes.c_void_p, ctypes.c_size_t]
lookup.restype = ctypes.c_int


class Context(ctypes.Structure):
    _fields_ = [('dirfd', ctypes.c_int32), ('flags', ctypes.c_int32),
                ('host_uid', ctypes.c_uint32), ('host_gid', ctypes.c_uint32),
                ('guest_uid', ctypes.c_uint32), ('guest_gid', ctypes.c_uint32),
                ('fake_root', ctypes.c_uint32), ('emulated_nlink', ctypes.c_uint32),
                ('result_errno', ctypes.c_int32), ('path', ctypes.c_char * 4096)]


def translated(path):
    output = ctypes.create_string_buffer(4096)
    return output.value if lookup(path.encode(), output, len(output)) else None


def authoritative(path):
    context = Context(dirfd=-100, result_errno=-1, path=path.encode())
    assert libc.syscall(167, 0x50525347, ctypes.byref(context), ctypes.sizeof(context), 4, 0) == 0
    assert context.result_errno == 0
    return context.path


def check(path):
    # An invalidated sibling may fall back once before its next traced resume.
    expected = authoritative(path)
    actual = translated(path)
    assert actual == expected, (path, actual, expected)
    return actual


source = tempfile.mkdtemp(prefix='fastpath-source-', dir='/')
target = tempfile.mkdtemp(prefix='fastpath-target-', dir='/')
mounted = False
try:
    for directory, data in ((source, b'source'), (target, b'target')):
        with open(directory + '/file', 'wb') as file:
            file.write(data)
    before = check(target + '/file')
    assert translated('relative') is None
    assert translated('/tmp/../etc') is None
    assert translated('/tmp//file') is None
    assert translated(os.environ.get('PROOT_TEST_LIBRARY', '/tmp/terminal-faststat-apk/libproot-faststat.so')) is None
    assert lookup((target + '/file').encode(), ctypes.c_void_p(1), 4096) == 0
    assert lookup((target + '/file').encode(), ctypes.create_string_buffer(1), 1) == 0
    ready, changed = threading.Event(), threading.Event()
    observations = []

    def sibling():
        observations.append(check(target + '/file'))
        ready.set()
        assert changed.wait(10)
        observations.append(check(target + '/file'))

    thread = threading.Thread(target=sibling)
    thread.start()
    assert ready.wait(10)
    assert libc.mount(source.encode(), target.encode(), None, 4096, None) == 0, ctypes.get_errno()
    mounted = True
    after = check(target + '/file')
    assert after != before
    changed.set()
    thread.join(10)
    assert not thread.is_alive() and observations == [before, after]
    assert libc.umount2(target.encode(), 0) == 0, ctypes.get_errno()
    mounted = False
    assert check(target + '/file') == before
    child = os.fork()
    if child == 0:
        try:
            try:
                os.chroot(source + "/missing")
            except OSError as error:
                assert error.errno == errno.ENOENT
            else:
                raise AssertionError("missing chroot target accepted")
            os.chroot(source)
            assert check('/file') != before
            os._exit(0)
        except BaseException:
            os._exit(1)
    _, status = os.waitpid(child, 0)
    assert os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0
    assert check(target + '/file') == before
    print('PASS shared rules: bindings, sibling invalidation, unmount, fork/chroot, path and buffer rejection')
finally:
    if mounted:
        assert libc.umount2(target.encode(), 0) == 0
    shutil.rmtree(source)
    shutil.rmtree(target)
