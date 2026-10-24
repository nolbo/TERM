#!/usr/bin/python3
"""Broker-v2 ownership and mount guards. Transport loss never creates a writable fallback."""
import importlib.util
import os
from pathlib import Path
import socket
import struct
import subprocess
import threading
import time

spec = importlib.util.spec_from_file_location('term_share_transport', Path(__file__).with_name('private-share-transport.py'))
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)

LEASE_SECONDS = 60


class RecoveryBroker:
    def init_recovery(self, root, ready, parse_manifest, mount_command, mount_points):
        self.version = 1
        self.recovery_root, self.ready_file = root, ready
        self.parse_manifest, self.mount_command, self.mount_points = parse_manifest, mount_command, mount_points
        self.guards, self.relays = {}, {}
        self.data_seen = {}
        self.offline = set()
        self.retired = False
        self.last_owner = time.monotonic()
        self.mounted = threading.Event()

    def pin_mount(self, path):
        self.guards[str(path)] = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)

    def guard_mounts(self):
        expected = {str(self.recovery_root / name) for name in self.folders.values()}
        if not expected or set(self.guards) != expected:
            raise RuntimeError('private mount guard set is incomplete')
        for descriptor in self.guards.values():
            os.fchown(descriptor, 0, 0)
            os.fchmod(descriptor, 0)
            stat = os.fstat(descriptor)
            if stat.st_uid != 0 or stat.st_mode & 0o777:
                raise RuntimeError('private mount guard failed')

    def attach_recoverable(self, stream, magic):
        if magic == b'TSB2':
            token, folders = self.parse_manifest(stream, 2)
            operation = transport.exact(stream, 1)
            if operation not in (b'\0', b'\1'):
                raise ValueError('invalid share generation operation')
            with self.lock:
                if self.token is None:
                    if operation != b'\1':
                        raise ValueError('share owner replay generation was lost')
                    self.version = 2
                    self.token, self.folders = token, folders
                    self.prepare()
                    threading.Thread(target=self.watch, daemon=True).start()
                elif self.version != 2 or self.token != token or self.folders != folders or self.retired:
                    raise ValueError('private share owner generation differs')
                previous, self.control = self.control, stream
                self.last_owner = time.monotonic()
            if previous:
                transport.shutdown(previous)
            stream.sendall(b'\0')
            threading.Thread(target=self.control_recoverable, args=(stream,), daemon=True).start()
            return
        if magic != b'TSF2':
            raise ValueError('unknown recovery stream')
        token, identity = transport.exact(stream, 16), transport.exact(stream, 16)
        operation = transport.exact(stream, 1)
        if operation not in (b'\0', b'\1'):
            raise ValueError('invalid share stream generation operation')
        with self.lock:
            if (self.version != 2 or self.retired or token != self.token
                    or identity not in self.folders or identity in self.offline
                    or time.monotonic() - self.last_owner > LEASE_SECONDS):
                raise ValueError('private share is not authorized')
            relay = self.relays.get(identity)
            if relay is None:
                if operation != b'\1':
                    raise ValueError('share stream replay generation was lost')
                application, child_socket = socket.socketpair()
                try:
                    child = subprocess.Popen(self.mount_command(self.recovery_root / self.folders[identity]),
                                             stdin=child_socket, stdout=child_socket, close_fds=True, start_new_session=True)
                except Exception:
                    application.close()
                    raise
                finally:
                    child_socket.close()
                relay = transport.ReplayStream()
                self.children[identity] = child
                self.relays[identity] = relay
                self.data_seen[identity] = time.monotonic()
                relay.bridge(application)
            if relay.closed:
                raise ValueError('share stream was permanently closed')
        stream.sendall(b'\0')
        relay.attach(stream)

    def control_recoverable(self, stream):
        try:
            if not self.mounted.wait(40):
                raise TimeoutError('private mounts did not become ready')
            with self.lock:
                if self.retired or self.control is not stream:
                    return
            stream.sendall(b'\0')
            stream.settimeout(10)
            while not self.failed.is_set():
                try:
                    operation = transport.exact(stream, 1)
                except socket.timeout:
                    continue
                identity = transport.exact(stream, 16) if operation == b'\3' else None
                with self.lock:
                    if self.control is not stream or self.retired:
                        return
                    self.last_owner = time.monotonic()
                if operation == b'\1':
                    with self.lock:
                        offline = sorted(self.offline)
                    stream.sendall(b'\0' + struct.pack('!H', len(offline)) + b''.join(offline))
                elif operation == b'\3':
                    if identity not in self.folders:
                        raise ValueError('unknown private share')
                    self.disable_folder(identity)
                    stream.sendall(b'\0')
                elif operation == b'\2':
                    self.retire_recovery('share.owner.revoked')
                    stream.sendall(b'\0')
                    return
                else:
                    raise ValueError('invalid share owner command')
        except Exception as error:
            if not self.failed.is_set():
                self.fail('share.control.recovering', error, terminate=False)
        finally:
            with self.lock:
                if self.control is stream:
                    self.control = None
            transport.shutdown(stream)

    def watch_recoverable(self):
        try:
            deadline = time.monotonic() + 40
            expected = {str(self.recovery_root / name) for name in self.folders.values()}
            while time.monotonic() < deadline and not self.failed.is_set():
                with self.lock:
                    if any(child.poll() is not None for child in self.children.values()):
                        raise RuntimeError('private share mount failed')
                    mounted = len(self.children) == len(self.folders) and expected <= self.mount_points()
                if mounted:
                    self.guard_mounts()
                    for path in expected:
                        os.stat(path)
                    self.ready_file.write_text('2\n')
                    self.mounted.set()
                    break
                time.sleep(0.05)
            else:
                raise TimeoutError('private share mounts timed out')
        except Exception as error:
            # No working mount/guard boundary has been established yet.
            self.fail('share.mount.failed', error)
            return
        try:
            while not self.failed.wait(0.5):
                with self.lock:
                    expired = time.monotonic() - self.last_owner > LEASE_SECONDS
                    dead = [identity for identity, child in self.children.items()
                            if identity not in self.offline and child.poll() is not None]
                if expired:
                    self.retire_or_fail('share.owner.expired')
                    return
                for identity in dead:
                    self.disable_folder(identity)
                self.expire_disconnected_data(time.monotonic())
        except Exception as error:
            # An operational monitor error is not a failed boot. Preserve the
            # VM only after proving guards and closing the affected authority.
            self.retire_or_fail('share.runtime.failed', error)

    def retire_or_fail(self, event, error=None):
        try:
            self.retire_recovery(event, error)
        except Exception as isolation_error:
            self.fail('share.isolation.failed', isolation_error)

    def expire_disconnected_data(self, now):
        with self.lock:
            expired = []
            for identity, relay in self.relays.items():
                if relay.connection is not None:
                    self.data_seen[identity] = now
                elif identity not in self.offline and now - self.data_seen[identity] > LEASE_SECONDS:
                    expired.append(identity)
        for identity in expired:
            self.disable_folder(identity)

    def disable_folder(self, identity):
        with self.lock:
            self.offline.add(identity)
            relay, child = self.relays.get(identity), self.children.get(identity)
        if relay:
            relay.close()
        if child and child.poll() is None:
            child.terminate()
        # Keep the failed FUSE mount in place; if explicitly unmounted, its pinned
        # underlying inode is root-owned mode000. No application can write a shadow tree.
        self.ready_file.unlink(missing_ok=True)

    def retire_recovery(self, event, error=None):
        self.guard_mounts()  # Never relax failure policy if guards cannot be proven.
        with self.lock:
            self.retired = True
        for identity in self.folders:
            self.disable_folder(identity)
        self.fail(event, error if error is not None else EOFError('private owner unavailable'), terminate=False)

    def close_recovery(self):
        for relay in self.relays.values():
            relay.close()
        for descriptor in self.guards.values():
            os.close(descriptor)
        self.guards.clear()
