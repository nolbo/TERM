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


def boot(directory, state, docker_image, scratch, number, timeout=600):
    cid = secrets.randbelow(2 ** 31 - 1024) + 1024
    cidfile = scratch / f'boot-{number}.container'
    console = scratch / f'boot-{number}.log'
    parameters = BOOT_PARAMS
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
        try:
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
            finally:
                session.close()
            return result
        except Exception:
            print(console.read_bytes()[-12000:].decode(errors='replace'), flush=True)
            raise
        finally:
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
