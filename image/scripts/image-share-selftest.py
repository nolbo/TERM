#!/usr/bin/python3
"""Factory normal-boot fixture; activated only by term.share-selftest=1."""
import os
from pathlib import Path
import time

root = Path('/mnt/term-shares/Smoke')
assert os.geteuid() == 1000
assert (root / 'host-created').read_bytes() == b'host-to-guest\n'
with (root / 'guest-created').open('wb') as output:
    output.write(bytes(range(256)) * 1024)
    output.flush()
    os.fsync(output.fileno())
(root / 'guest-created').rename(root / 'guest-renamed')
(root / 'guest-renamed').chmod(0o640)
(root / 'guest-link').symlink_to('guest-renamed')
assert (root / 'guest-link').read_bytes() == bytes(range(256)) * 1024
assert os.statvfs(root).f_bsize > 0
(root / 'guest-ready').write_text('ready\n')
deadline = time.monotonic() + 20
while time.monotonic() < deadline:
    if (root / 'host-created').read_bytes() == b'host-update\n':
        break
    time.sleep(0.1)
else:
    raise TimeoutError('Host writes were not visible through FUSE')
(root / 'guest-link').unlink()
# Preserve an actual open FUSE/SFTP handle while the producer replaces both sockets.
with (root / 'guest-held').open('w+b', buffering=0) as held:
    held.write(b'before')
    (root / 'guest-recovery-ready').write_text('ready\n')
    deadline = time.monotonic() + 20
    while not (root / 'host-reconnected').exists():
        if time.monotonic() > deadline:
            raise TimeoutError('Host did not reconnect private share')
        time.sleep(0.1)
    held.write(b'-after')
    held.seek(0)
    assert held.read() == b'before-after'
(root / 'guest-finished').write_text('passed\n')
