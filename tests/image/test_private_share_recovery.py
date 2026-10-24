import importlib.util
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
