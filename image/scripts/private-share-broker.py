#!/usr/bin/python3
"""Root boot coordinator. SFTP/FUSE parsing runs as droid over app-initiated vsock only."""
import os
import json
import sys
from pathlib import Path
import re
import signal
import socket
import struct
import subprocess
import threading
import time
import uuid

PORT = 40506
ROOT = Path('/mnt/term-shares')
READY = Path('/run/term-share-broker.ready')
FAILURE_RECORD = Path('/var/log/term-share-failure.json')
NAME = re.compile(r'[A-Za-z][A-Za-z0-9_-]{0,63}', re.ASCII)


def exact(stream, size):
    data = bytearray()
    while len(data) < size:
        chunk = stream.recv(size - len(data))
        if not chunk:
            raise EOFError('private share owner disconnected')
        data.extend(chunk)
    return bytes(data)


def manifest(stream):
    version, count = struct.unpack('!HH', exact(stream, 4))
    if version != 1 or not 1 <= count <= 128:
        raise ValueError('invalid private share version/count')
    token = exact(stream, 16)
    folders, names = {}, set()
    for _ in range(count):
        identity = exact(stream, 16)
        size = exact(stream, 1)[0]
        if not 1 <= size <= 64:
            raise ValueError('invalid private share name size')
        name = exact(stream, size).decode('ascii')
        if not NAME.fullmatch(name) or identity in folders or name in names:
            raise ValueError('invalid or duplicate private share')
        uuid.UUID(bytes=identity)  # The wire identity never names a host directory.
        folders[identity] = name
        names.add(name)
    return token, folders


def mount_command(path):
    return ['/usr/bin/setpriv', '--reuid=1000', '--regid=1000', '--clear-groups',
            '/usr/bin/sshfs', ':', str(path), '-f', '-o',
            'passive,allow_other,default_permissions,nodev,nosuid,direct_io,sshfs_sync,'
            'dir_cache=no,attr_timeout=0,entry_timeout=0,negative_timeout=0']


def mount_points():
    # Names are restricted ASCII components, so mountinfo escaping is not needed here.
    return {line.split()[4] for line in Path('/proc/self/mountinfo').read_text().splitlines()
            if ' - fuse.sshfs ' in line}


class Broker:
    def __init__(self):
        self.control = None
        self.token = None
        self.folders = {}
        self.children = {}
        self.streams = []
        self.lock = threading.Lock()
        self.failed = threading.Event()
        self.failure_reason = None

    def fail(self, event, error):
        with self.lock:
            if self.failure_reason is not None:
                return
            self.failure_reason = event
            record = {'event': event, 'error': type(error).__name__,
                      'time_ns': time.time_ns(), 'monotonic_ns': time.monotonic_ns()}
            # No folder names, host paths, protocol bytes, tokens or exception messages.
            try:
                print('term-share ' + json.dumps(record, sort_keys=True), file=sys.stderr, flush=True)
            except OSError:
                pass
            try:
                temporary = FAILURE_RECORD.with_suffix('.tmp')
                with temporary.open('w', encoding='utf-8') as output:
                    os.chmod(temporary, 0o600)
                    json.dump(record, output)
                    output.flush()
                    os.fsync(output.fileno())
                temporary.replace(FAILURE_RECORD)
            except OSError:
                pass  # journald still has the event if the guest disk is unavailable.
            finally:
                self.failed.set()

    def attach(self, stream):
        stream.settimeout(15)
        magic = exact(stream, 4)
        with self.lock:
            if magic == b'TSB1':
                if self.control is not None:
                    raise ValueError('private sharing is already owned; restart VM')
                self.token, self.folders = manifest(stream)
                self.prepare()
                self.control = stream
                stream.sendall(b'\0')
                threading.Thread(target=self.watch, daemon=True).start()
                return
            if magic != b'TSF1' or self.control is None:
                raise ValueError('private share manifest required')
            token, identity = exact(stream, 16), exact(stream, 16)
            if token != self.token or identity not in self.folders or identity in self.children:
                raise ValueError('unbound or duplicate private share')
            stream.sendall(b'\0')
            stream.settimeout(None)
            child = subprocess.Popen(mount_command(ROOT / self.folders[identity]), stdin=stream, stdout=stream,
                                     close_fds=True, start_new_session=True)
            self.children[identity] = child
            self.streams.append(stream)

    def prepare(self):
        if ROOT.is_symlink() or ROOT.resolve() != ROOT:
            raise ValueError('unsafe mount parent')
        ROOT.mkdir(mode=0o755, parents=True, exist_ok=True)
        ROOT.chmod(0o755)
        for name in self.folders.values():
            path = ROOT / name
            if path.is_symlink():
                raise ValueError('unsafe mount directory')
            path.mkdir(mode=0o700, exist_ok=True)
            os.chown(path, 1000, 1000)
        READY.unlink(missing_ok=True)

    def watch(self):
        stage = 'share.mount.failed'
        try:
            deadline = time.monotonic() + 40
            expected = {str(ROOT / name) for name in self.folders.values()}
            while time.monotonic() < deadline and not self.failed.is_set():
                with self.lock:
                    if any(child.poll() is not None for child in self.children.values()):
                        raise RuntimeError('private share client failed')
                    mounted = len(self.children) == len(self.folders) and expected <= mount_points()
                if mounted:
                    # Trigger real filesystem requests before claiming readiness.
                    for path in expected:
                        os.stat(path)
                    READY.write_text('1\n')
                    self.control.sendall(b'\0')
                    self.control.settimeout(None)
                    stage = 'share.control.eof'
                    if self.control.recv(1) != b'':
                        stage = 'share.control.protocol'
                        raise ValueError('unexpected control data')
                    raise EOFError('private share owner disconnected')
                time.sleep(0.05)
            stage = 'share.mount.timeout'
            raise TimeoutError('private share mounts timed out')
        except Exception as error:
            self.fail(stage, error)

    def close(self):
        self.failed.set()
        READY.unlink(missing_ok=True)
        for stream in self.streams + ([self.control] if self.control else []):
            try:
                stream.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            stream.close()
        for child in self.children.values():
            if child.poll() is None:
                child.terminate()
        for name in self.folders.values():
            subprocess.run(['/usr/bin/fusermount3', '-uz', str(ROOT / name)], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for child in self.children.values():
            try:
                child.wait(timeout=2)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()


def serve():
    broker = Broker()
    listener = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
    listener.bind((socket.VMADDR_CID_ANY, PORT))
    listener.listen(128)
    listener.settimeout(0.5)
    signal.signal(signal.SIGTERM, lambda *_: broker.failed.set())
    signal.signal(signal.SIGINT, lambda *_: broker.failed.set())
    try:
        while not broker.failed.is_set():
            try:
                stream, address = listener.accept()
            except socket.timeout:
                continue
            if address[0] != socket.VMADDR_CID_HOST:
                stream.close()
                continue
            try:
                broker.attach(stream)
            except Exception as error:
                broker.fail('share.attach.failed', error)
                stream.close()
        # FailureAction=poweroff stops this VM, preventing ownerless private mounts.
        raise RuntimeError('private share connection stopped')
    finally:
        listener.close()
        broker.close()


if __name__ == '__main__':
    serve()
