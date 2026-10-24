import importlib.util
import json
from pathlib import Path
import socket
import struct
import tempfile
import threading
import time
import unittest
from unittest.mock import MagicMock, patch
import uuid

spec = importlib.util.spec_from_file_location('broker_recovery_test', Path(__file__).resolve().parents[2] /
                                             'image/scripts/private-share-broker.py')
broker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(broker)


class RecoveryTests(unittest.TestCase):
    def test_missing_guard_set_cannot_be_treated_as_proof_of_safe_retirement(self):
        owner = broker.Broker()
        owner.folders = {uuid.uuid4().bytes: 'Code'}
        with self.assertRaisesRegex(RuntimeError, 'guard set is incomplete'):
            owner.retire_recovery('share.owner.expired')
        self.assertFalse(owner.retired)

    def watch_ready_owner(self, directory, failure=None, isolation_failure=None, expired=False):
        owner = broker.Broker()
        owner.version, owner.token = 2, bytes(16)
        identity = uuid.uuid4().bytes
        owner.folders = {identity: 'Code'}
        child = MagicMock(); child.poll.return_value = None
        owner.children[identity] = child
        relay = MagicMock(); owner.relays[identity] = relay
        if expired:
            owner.last_owner = time.monotonic() - 61
        expected = {str(owner.recovery_root / 'Code')}
        guard_effect = [None, isolation_failure] if isolation_failure is not None else None
        with patch.object(owner, 'ready_file', Path(directory) / 'ready'), \
                patch.object(broker, 'FAILURE_RECORD', Path(directory) / 'failure.json'), \
                patch.object(owner, 'mount_points', return_value=expected), \
                patch.object(owner, 'guard_mounts', side_effect=guard_effect) as guard, \
                patch.object(broker.os, 'stat'), \
                patch.object(owner, 'expire_disconnected_data', side_effect=failure):
            watcher = threading.Thread(target=owner.watch_recoverable, daemon=True)
            watcher.start(); watcher.join(3)
            if watcher.is_alive():
                owner.failed.set(); watcher.join(1)
                self.fail('share monitor did not finish')
            self.assertEqual(2, guard.call_count)  # Failed guard proof is never retried as an ordinary monitor error.
        self.assertTrue(owner.mounted.is_set())
        return owner, child, relay, json.loads((Path(directory) / 'failure.json').read_text())

    def test_runtime_monitor_error_retires_guarded_shares_without_powering_off_vm(self):
        with tempfile.TemporaryDirectory() as directory:
            owner, child, relay, record = self.watch_ready_owner(directory, failure=OSError('monitor failure'))
        self.assertTrue(owner.retired)
        self.assertFalse(owner.failed.is_set())
        relay.close.assert_called_once(); child.terminate.assert_called_once()
        self.assertEqual('share.runtime.failed', record['event'])
        self.assertFalse(record['fatal'])

    def test_runtime_isolation_failure_still_powers_off_vm(self):
        with tempfile.TemporaryDirectory() as directory:
            owner, _, _, record = self.watch_ready_owner(directory, failure=OSError('monitor failure'),
                                                        isolation_failure=OSError('guard proof failed'))
        self.assertTrue(owner.failed.is_set())
        self.assertFalse(owner.retired)
        self.assertEqual('share.isolation.failed', record['event'])
        self.assertTrue(record['fatal'])

    def test_full_watchdog_lease_expiry_keeps_vm_running(self):
        with tempfile.TemporaryDirectory() as directory:
            owner, child, relay, record = self.watch_ready_owner(directory, expired=True)
        self.assertTrue(owner.retired)
        self.assertFalse(owner.failed.is_set())
        relay.close.assert_called_once(); child.terminate.assert_called_once()
        self.assertEqual('share.owner.expired', record['event'])
        self.assertFalse(record['fatal'])

    def test_lease_expiry_with_failed_guard_proof_is_fatal_without_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            owner, _, _, record = self.watch_ready_owner(directory, expired=True,
                                                        isolation_failure=OSError('guard proof failed'))
        self.assertTrue(owner.failed.is_set())
        self.assertFalse(owner.retired)
        self.assertEqual('share.isolation.failed', record['event'])
        self.assertTrue(record['fatal'])

    def test_new_boot_reopens_previously_sealed_underlying_directory_for_mount_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'mounts'
            path = root / 'Code'
            path.mkdir(parents=True)
            path.chmod(0)
            with patch.object(broker, 'ROOT', root), patch.object(broker, 'READY', Path(directory) / 'ready'), \
                    patch.object(broker.os, 'chown') as chown:
                owner = broker.Broker()
                owner.version = 2
                owner.folders = {uuid.uuid4().bytes: 'Code'}
                try:
                    owner.prepare()
                    self.assertEqual(0o700, path.stat().st_mode & 0o777)
                    chown.assert_called_once_with(path, 1000, 1000)
                    self.assertIn(str(path), owner.guards)
                finally:
                    owner.close_recovery()

    def test_resume_never_creates_a_missing_owner_or_mount_generation(self):
        owner = broker.Broker()
        identity = uuid.uuid4().bytes
        client, server = socket.socketpair()
        try:
            client.sendall(b'TSB2' + struct.pack('!HH', 2, 1) + bytes(16) + identity + b'\4Code\0')
            with patch.object(owner, 'prepare') as prepare:
                with self.assertRaisesRegex(ValueError, 'generation was lost'):
                    owner.attach(server)
                prepare.assert_not_called()
            self.assertIsNone(owner.token)
            self.assertEqual({}, owner.children)
        finally:
            client.close(); server.close()

    def test_control_disconnect_and_reconnect_keep_existing_folder_actors(self):
        with tempfile.TemporaryDirectory() as directory:
            owner = broker.Broker()
            owner.version, owner.token = 2, bytes(16)
            identity = uuid.uuid4().bytes
            owner.folders = {identity: 'Code'}
            actor = MagicMock()
            owner.children[identity] = actor
            owner.mounted.set()
            self.addCleanup(owner.failed.set)
            with patch.object(broker, 'FAILURE_RECORD', Path(directory) / 'failure.json'):
                for _ in range(2):
                    client, server = socket.socketpair()
                    client.settimeout(2)
                    client.sendall(b'TSB2' + struct.pack('!HH', 2, 1) + owner.token + identity + b'\4Code\0')
                    owner.attach(server)
                    self.assertEqual(b'\0\0', broker.exact(client, 2))
                    client.sendall(b'\1')
                    self.assertEqual(b'\0\0\0', broker.exact(client, 3))
                    client.shutdown(socket.SHUT_RDWR)
                    client.close()
                    deadline = time.monotonic() + 2
                    while owner.control is not None and time.monotonic() < deadline:
                        time.sleep(0.01)
                    self.assertIsNone(owner.control)
                    self.assertFalse(owner.failed.is_set())
                    self.assertIs(actor, owner.children[identity])
                    actor.terminate.assert_not_called()

    def test_retirement_keeps_vm_running_and_leaves_guarded_mount_in_place(self):
        owner = broker.Broker()
        identity = uuid.uuid4().bytes
        owner.folders = {identity: 'Code'}
        child = MagicMock()
        child.poll.return_value = None
        owner.children[identity] = child
        relay = MagicMock()
        owner.relays[identity] = relay
        with patch.object(owner, 'guard_mounts') as guard, patch.object(owner, 'fail') as fail, \
                patch.object(owner, 'ready_file') as ready:
            owner.retire_recovery('share.owner.expired')
        guard.assert_called_once()
        relay.close.assert_called_once()
        child.terminate.assert_called_once()
        ready.unlink.assert_called_once()
        self.assertTrue(owner.retired)
        self.assertIn(identity, owner.offline)
        self.assertFalse(owner.failed.is_set())
        self.assertFalse(fail.call_args.kwargs['terminate'])

    def test_guard_failure_remains_fatal_instead_of_accepting_shadow_directory(self):
        owner = broker.Broker()
        with patch.object(owner, 'guard_mounts', side_effect=OSError('guard denied')):
            with self.assertRaises(OSError):
                owner.retire_recovery('share.owner.expired')
        self.assertFalse(owner.retired)

    def test_data_only_outage_expires_that_folder_while_control_and_vm_remain_alive(self):
        owner = broker.Broker()
        identity, healthy = uuid.uuid4().bytes, uuid.uuid4().bytes
        owner.folders = {identity: 'Code', healthy: 'Other'}
        lost, live = MagicMock(), MagicMock()
        lost.connection, live.connection = None, object()
        owner.relays = {identity: lost, healthy: live}
        owner.data_seen = {identity: 1, healthy: 1}
        control = owner.control = object()
        with patch.object(owner, 'ready_file'):
            owner.expire_disconnected_data(62)
        lost.close.assert_called_once()
        live.close.assert_not_called()
        self.assertEqual({identity}, owner.offline)
        self.assertIs(control, owner.control)
        self.assertFalse(owner.failed.is_set())
