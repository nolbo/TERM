#!/usr/bin/env python3
"""Pinned superblock identity, policy fallback and tracer descriptor lifetime."""
import ctypes
import os
import signal
import sys
import tempfile
import threading
import time

signal.alarm(40)
c = ctypes.CDLL(None, use_errno=True)
c.proot_faststorage_hits.restype = ctypes.c_ulong
with tempfile.TemporaryDirectory() as root:
    path = root + '/file'
    with open(path, 'wb') as output:
        output.write(b'ordinary')
    before = c.proot_faststorage_hits()
    assert os.stat(path).st_size == 8
    assert open(path, 'rb').read() == b'ordinary'
    if '--expect-disabled' in sys.argv:
        assert c.proot_faststorage_hits() == before, 'disabled superblock fast path used'
    else:
        assert c.proot_faststorage_hits() > before, 'superblock fast path not used'
    # Procfs must retain tracer processing even when exposed by a normal bind.
    target = '/storage-proc-test'
    os.mkdir(target)
    assert c.mount(b'/proc', target.encode(), None, 4096, None) == 0
    try:
        name = (target + '/' + str(os.getpid()) + '/auxv').encode()
        raw = c.syscall(56, -100, name, os.O_RDONLY, 0)
        assert raw >= 0
        expected = os.read(raw, 4096)
        os.close(raw)
        before = c.proot_faststorage_hits()
        fd = os.open(name, os.O_RDONLY)
        assert os.read(fd, 4096) == expected
        os.close(fd)
        assert c.proot_faststorage_hits() == before, 'procfs misclassified'
    finally:
        assert c.umount2(target.encode(), 0) == 0
        os.rmdir(target)
    # Each thread owns a tracer-side reference. It must be released at thread
    # teardown instead of accumulating host descriptors across short workers.
    status = open('/proc/self/status').read()
    tracer = next(line.split()[1] for line in status.splitlines() if line.startswith('TracerPid:'))
    def tracer_fds():
        return len(os.listdir('/proc/' + tracer + '/fd'))
    original = tracer_fds()
    errors = []
    def work():
        try:
            for _ in range(5):
                assert os.stat(path).st_size == 8
                assert open(path, 'rb').read() == b'ordinary'
        except BaseException as error:
            errors.append(error)
    for _ in range(3):
        threads = [threading.Thread(target=work) for _ in range(16)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        assert not errors, errors
        # Deliver a traced operation after exits so the event loop reaps them.
        for _ in range(20):
            after = tracer_fds()
            if after <= original + 2:
                break
            time.sleep(0.01)
        assert after <= original + 2, (original, after, 'tracer FD leak')
    child = os.fork()
    if child == 0:
        try:
            assert os.stat(path).st_size == 8
            assert open(path, 'rb').read() == b'ordinary'
            os._exit(0)
        except BaseException:
            os._exit(1)
    assert os.waitpid(child, 0)[1] == 0
print('PASS pinned storage: hits, procfs bind, fresh metadata, 48 thread lifetimes, fork')
