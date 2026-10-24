#!/usr/bin/env python3
"""Boot the real guest init and verify its host-only vsock services twice.

The host probes vsock because Docker's default seccomp policy denies AF_VSOCK.
QEMU keeps that policy and runs with only its vhost-vsock device and image mounts.
The factory's writable image is never changed: both boots use a scratch copy.
"""
import argparse
import os
import json
from pathlib import Path
import secrets
import socket
import stat
import struct
import subprocess
import tempfile
import threading
import time
import uuid
import importlib.util

spec = importlib.util.spec_from_file_location('smoke_recovery', Path(__file__).with_name('smoke-avf-recovery.py'))
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)

HEADER = struct.Struct('>IHBBHHQI')
MAGIC = 0x44505459
PORTS = (40500, 40503)
BOOT_PARAMS = 'root=/dev/vdb rootfstype=ext4 rw console=hvc0 systemd.hostname=localhost'


def check_host():
    device = Path('/dev/vhost-vsock')
    if not device.exists() or not stat.S_ISCHR(device.stat().st_mode):
        raise RuntimeError('Normal AVF boot verification requires /dev/vhost-vsock; load the host vhost_vsock module')
    try:
        with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM):
            pass
    except OSError as error:
        raise RuntimeError('Normal AVF boot verification requires host AF_VSOCK; load vhost_vsock') from error


def receive_exact(connection, size):
    result = bytearray()
    while len(result) < size:
        part = connection.recv(size - len(result))
        if not part:
            raise EOFError('Guest closed the session RPC before a complete response')
        result.extend(part)
    return bytes(result)


def probe_sessiond(connection):
    # A new boot has no sessions. Verify the actual protocol, not only listen().
    connection.sendall(HEADER.pack(MAGIC, 1, 1, 1, 0, 0, 1, 1) + b'\x02')
    header = receive_exact(connection, HEADER.size)
    if header != HEADER.pack(MAGIC, 1, 1, 2, 0, 0, 1, 3):
        raise RuntimeError('Guest returned an invalid session-list response header')
    if receive_exact(connection, 3) != b'\x82\x00\x00':
        raise RuntimeError('Fresh guest session list was not empty')


def stop_boot(process, cidfile):
    if process.poll() is None:
        if cidfile.is_file():
            cid = cidfile.read_text().strip()
            stopped = subprocess.run(['docker', 'stop', '--time', '2', cid],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if stopped.returncode and process.poll() is None:
                subprocess.run(['docker', 'kill', cid], check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            process.terminate()
    process.wait(timeout=20)


class PrivateShareProbe:
    """Real guest SSHFS against an isolated, unprivileged reference SFTP server.

    Android's scoped server is tested separately on Android. This fixture gives
    the guest only a disposable bind mount, no host tree, network or credentials.
    """
    def __init__(self, cid, docker_image, scratch, process, deadline):
        self.cid = cid
        self.token, self.identity = secrets.token_bytes(16), uuid.uuid4().bytes
        self.replay = recovery.transport.ReplayStream()
        self.control_lock = threading.Lock()
        self.stopped = threading.Event()
        self.heartbeat = None
        self.data_established = False
        self.control = None
        self.data = None
        self.server = None
        self.workers = []
        self.log = (scratch / 'sftp-server.log').open('wb')
        self.root = scratch / 'share-fixture'
        self.root.mkdir(mode=0o777)
        self.root.chmod(0o777)
        (self.root / 'host-created').write_bytes(b'host-to-guest\n')
        try:
            while process.poll() is None and time.monotonic() < deadline:
                connection = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
                connection.settimeout(3)
                try:
                    connection.connect((cid, 40506))
                    self.control = connection
                    break
                except OSError:
                    connection.close()
                    time.sleep(0.2)
            if self.control is None:
                raise TimeoutError('Guest private share coordinator did not listen')
            self.control.settimeout(50)
            self.control.sendall(b'TSB2' + struct.pack('!HH', 2, 1) + self.token + self.identity + b'\x05Smoke\1')
            if receive_exact(self.control, 1) != b'\0':
                raise RuntimeError('Guest rejected private share fixture')
            self.connect_data()
            command = ['docker', 'run', '--rm', '-i', '--network', 'none', '--read-only',
                       '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                       '--user', f'1000:{os.getgid()}', '-v', f'{self.root}:/export:rw',
                       '--entrypoint', '/usr/lib/openssh/sftp-server', docker_image, '-e', '-d', '/export', '-u', '000']
            self.server = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log)
            self.workers = [threading.Thread(target=self.to_server, daemon=True),
                            threading.Thread(target=self.to_guest, daemon=True)]
            for worker in self.workers:
                worker.start()
            if receive_exact(self.control, 1) != b'\0':
                raise RuntimeError('Guest private FUSE mount was not ready')
            self.heartbeat = threading.Thread(target=self.keep_owner, daemon=True)
            self.heartbeat.start()
        except Exception:
            self.close()
            raise

    def to_server(self):
        try:
            while data := self.replay.read(32 * 1024):
                self.server.stdin.write(data)
                self.server.stdin.flush()
        except (OSError, EOFError, ValueError):
            pass
        finally:
            try:
                self.server.stdin.close()
            except OSError:
                pass

    def to_guest(self):
        try:
            while data := self.server.stdout.read1(128 * 1024):
                self.replay.write(data)
        except (OSError, EOFError, ValueError):
            pass

    def connect_data(self):
        self.data = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
        self.data.settimeout(10)
        self.data.connect((self.cid, 40506))
        self.data.sendall(b'TSF2' + self.token + self.identity + bytes([0 if self.data_established else 1]))
        assert receive_exact(self.data, 1) == b'\0'
        self.replay.attach(self.data)
        self.data_established = True

    def keep_owner(self):
        while not self.stopped.wait(5):
            with self.control_lock:
                if self.control is None:
                    return
                try:
                    self.control.sendall(b'\1')
                    assert receive_exact(self.control, 3) == b'\0\0\0'
                except (OSError, EOFError):
                    return

    def reconnect(self):
        with self.control_lock:
            recovery.transport.shutdown(self.control)
            self.control = None
            self.replay.detach(self.replay.connection)
            time.sleep(2)
            self.control = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
            self.control.settimeout(10)
            self.control.connect((self.cid, 40506))
            self.control.sendall(b'TSB2' + struct.pack('!HH', 2, 1) + self.token + self.identity + b'\x05Smoke\0')
            assert receive_exact(self.control, 2) == b'\0\0'
            self.connect_data()
            (self.root / 'host-reconnected').write_text('ready\n')

    def verify_files(self, process):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline and process.poll() is None:
            if (self.root / 'guest-ready').exists():
                assert (self.root / 'guest-renamed').read_bytes() == bytes(range(256)) * 1024
                (self.root / 'host-created').write_bytes(b'host-update\n')
            if (self.root / 'guest-recovery-ready').exists() and not (self.root / 'host-reconnected').exists():
                self.reconnect()
            # SFTP creates the file before its write arrives. Require the full
            # completion record within the existing deadline, not just its inode.
            finished = self.root / 'guest-finished'
            if finished.exists() and finished.read_text() == 'passed\n':
                assert not (self.root / 'guest-link').exists()
                assert (self.root / 'guest-held').read_bytes() == b'before-after'
                return
            time.sleep(0.1)
        raise RuntimeError('Guest FUSE read/write/rename/symlink/host-update probe failed')

    def verify_owner_loss(self, process, session):
        self.stopped.set()
        with self.control_lock:
            recovery.transport.shutdown(self.control)
            self.control = None
        for _ in range(7):
            time.sleep(10)
            assert process.poll() is None, 'Share owner loss stopped the VM'
            assert b'VM_ALIVE' in session.run('printf VM_ALIVE')
        # Failed FUSE must deny I/O; even an explicit unmount must not expose a writable shadow tree.
        import shlex
        check = ("import os,subprocess; p='/mnt/term-shares/Smoke'; "
                 "subprocess.run(['fusermount3','-u',p],check=False); "
                 "s=os.stat(p); assert s.st_uid==0 and s.st_mode & 0o777==0; "
                 "exec(\"try:\\n open(p+'/shadow','w')\\nexcept PermissionError:\\n print('GUARD_OK')\\nelse:\\n raise AssertionError('writable shadow')\")")
        assert b'GUARD_OK' in session.run('python3 -c ' + shlex.quote(check) + '; sync')
        assert not (self.root / 'shadow').exists()

    def close(self):
        self.stopped.set()
        self.replay.close()
        for connection in [self.control, self.data]:
            if connection is not None:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                connection.close()
        if self.server is not None:
            try:
                self.server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.server.terminate()
                self.server.wait(timeout=10)
        for worker in self.workers:
            worker.join(timeout=2)
        self.log.close()


def boot(directory, state, docker_image, scratch, number, timeout=600):
    cid = secrets.randbelow(2 ** 31 - 1024) + 1024
    cidfile = scratch / f'boot-{number}.container'
    console = scratch / f'boot-{number}.log'
    parameters = BOOT_PARAMS + ' term.share-broker=1 term.share-selftest=1'
    command = ['docker', 'run', '--rm', '--network', 'none', '--device', '/dev/vhost-vsock',
               '--entrypoint', 'qemu-system-aarch64',
               '--cidfile', str(cidfile), '-v', f'{directory}:/images:ro', '-v', f'{state.parent}:/state',
               docker_image, '-machine', 'virt', '-cpu', 'max', '-accel', 'tcg',
               '-m', '2048', '-display', 'none', '-monitor', 'none', '-no-reboot', '-nic', 'none',
               '-kernel', '/images/vmlinuz', '-initrd', '/images/initrd.img', '-append', parameters,
               '-device', 'virtio-serial-pci', '-chardev', 'stdio,id=console,signal=off',
               '-device', 'virtconsole,chardev=console', '-device', f'vhost-vsock-pci,guest-cid={cid}']
    for index, (path, readonly) in enumerate([('/images/system.img', True), ('/state/home.img', False),
                                            ('/images/config.img', True)]):
        command += ['-drive', f'if=none,id=disk{index},file={path},format=raw,readonly={"on" if readonly else "off"}',
                    '-device', f'virtio-blk-pci,drive=disk{index}']
    started = time.monotonic()
    pending = set(PORTS)
    last_error = None
    with console.open('wb') as output:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
        sharing = None
        try:
            fixture = scratch / f'probe-{number}'
            fixture.mkdir()
            sharing = PrivateShareProbe(cid, docker_image, fixture, process, started + timeout)
            sharing.verify_files(process)
            while pending and process.poll() is None and time.monotonic() - started < timeout:
                for port in sorted(pending):
                    try:
                        with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as connection:
                            connection.settimeout(3)
                            connection.connect((cid, port))
                            if port == 40500:
                                probe_sessiond(connection)
                        pending.remove(port)
                    except OSError as error:
                        last_error = error
                if pending:
                    time.sleep(1)
            if pending or process.poll() is not None:
                raise RuntimeError(f'Normal systemd boot {number} did not serve vsock ports {sorted(pending)}; last error: {last_error}')
            result = {'boot': number, 'seconds': round(time.monotonic() - started, 2),
                      'sessionListRpc': 'passed', 'packageInitializationPort': 'passed'}
            session = recovery.SessionProbe(cid)
            try:
                session.verify_reconnect()
                result.update(sessionResume='passed')
                if sharing is not None:
                    sharing.verify_owner_loss(process, session)
                    result.update(privateShareReadWrite='passed', privateShareOwnerLoss='passed',
                                  privateShareReconnect='passed', privateShareGuard='passed')
            finally:
                session.close()
            return result
        except Exception:
            print(console.read_bytes()[-12000:].decode(errors='replace'), flush=True)
            raise
        finally:
            if sharing is not None:
                sharing.close()
            stop_boot(process, cidfile)


def verify(directory, docker_image):
    check_host()
    directory = directory.resolve()
    with tempfile.TemporaryDirectory(prefix='.avf-systemd-', dir=directory.parent) as temporary:
        scratch = Path(temporary)
        state = scratch / 'state'
        state.mkdir()
        home = state / 'home.img'
        subprocess.run(['cp', '--sparse=always', '--reflink=auto', str(directory / 'home.img'), str(home)], check=True)
        results = []
        for number in (1, 2):
            result = boot(directory, home, docker_image, scratch, number)
            results.append(result)
            print(f'Normal systemd boot {number}: session-list RPC 40500 and package endpoint 40503 passed ({result["seconds"]}s)', flush=True)
        return {'mode': 'normal-systemd', 'transport': 'host-vsock', 'network': 'disabled', 'boots': results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-host', action='store_true')
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--docker-image')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    if args.check_host:
        check_host()
        return
    if not args.directory or not args.docker_image or not args.report:
        parser.error('--directory, --docker-image and --report are required')
    result = verify(args.directory, args.docker_image)
    args.report.write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
