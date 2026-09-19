#!/usr/bin/env python3
"""Bounded rename and create/write/rename/unlink probe; no fsync claim."""
import json
import os
import tempfile
import time

count = 1000
payload = bytes(range(256)) * 16
with tempfile.TemporaryDirectory(prefix='rename-local-', dir='/tmp') as directory:
    source = directory + '/source'
    target = directory + '/target'
    with open(source, 'wb') as stream:
        stream.write(payload)
    start = time.monotonic_ns()
    for _ in range(count):
        os.rename(source, target)
        os.rename(target, source)
    rename_ns = time.monotonic_ns() - start
    assert open(source, 'rb').read() == payload and not os.path.exists(target)
    os.unlink(source)
    start = time.monotonic_ns()
    for _ in range(count):
        with open(source, 'wb') as stream:
            assert stream.write(payload) == len(payload)
        os.rename(source, target)
        os.unlink(target)
    mutation_ns = time.monotonic_ns() - start
    assert not os.listdir(directory)
    print(json.dumps(dict(rename_calls=count * 2, rename_ms=rename_ns / 1e6,
                          mutation_files=count, mutation_ms=mutation_ns / 1e6,
                          bytes_per_file=len(payload), verified=True)))
