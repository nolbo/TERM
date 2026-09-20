#!/usr/bin/env python3
"""Time real coreutils metadata consumers over fresh ordinary files."""
import ctypes
import json
import os
import subprocess
import tempfile
import time

with tempfile.TemporaryDirectory() as root:
    for index in range(1000):
        with open(root + '/file-' + str(index), 'wb') as output:
            output.write(b'payload')
    for name, command in [('ls-long', ['ls', '-ln', '--color=never', root]),
                          ('du', ['du', '-s', root]),
                          ('find-ls', ['find', root, '-ls'])]:
        start = time.perf_counter_ns()
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        elapsed = (time.perf_counter_ns() - start) / 1e6
        assert result.stdout and not result.stderr
        if name == 'ls-long':
            assert len(result.stdout.splitlines()) == 1001
        print(json.dumps(dict(case=name,wall_ms=elapsed,count=1000)), flush=True)
