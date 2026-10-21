#!/usr/bin/env python3
"""Private control port for a disposable QEMU test, never a guest payload."""
import base64
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def find_port(ports):
    for entry in ports.glob('*'):
        name = entry / 'name'
        if name.is_file() and name.read_text().strip() == 'term.redroid.test':
            return Path('/dev') / entry.name
    return None


def send(channel, response):
    packet = memoryview(json.dumps(response).encode() + b'\n')
    while packet:
        written = channel.write(packet)
        if not written:
            raise RuntimeError('Diagnostic channel disconnected while writing')
        packet = packet[written:]


def execute(argv, timeout):
    process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               start_new_session=True)
    try:
        output, _ = process.communicate(timeout=timeout)
        return process.returncode, output
    except subprocess.TimeoutExpired:
        # A shell child can retain the output pipe after the shell is killed.
        # Kill this command's process group so the diagnostic channel recovers.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.communicate()
        raise


def main():
    if 'term.redroid-test=1' not in Path('/proc/cmdline').read_text().split():
        raise SystemExit('Requires the disposable ReDroid test boot')
    ports = Path('/sys/class/virtio-ports')
    port = None
    for _ in range(200):
        # Console ports do not necessarily have a name attribute.
        port = find_port(ports)
        if port:
            break
        time.sleep(.1)
    if not port:
        raise SystemExit('Missing private QEMU diagnostic port')
    with port.open('r+b', buffering=0) as channel:
        send(channel, {'id': 0, 'ready': True})
        while True:
            line = channel.readline(16384)
            if not line:
                time.sleep(.1)
                continue
            request = json.loads(line)
            try:
                argv = request['argv']
                if not isinstance(argv, list) or not argv or not all(isinstance(x, str) for x in argv):
                    raise ValueError('Expected argv')
                code, output = execute(argv, min(180, max(1, request.get('timeout', 60))))
                response = {'id': request['id'], 'code': code,
                            'data': base64.b64encode(output[:524288]).decode()}
            except Exception as exc:
                response = {'id': request['id'], 'error': str(exc)}
            send(channel, response)


if __name__ == '__main__':
    main()
