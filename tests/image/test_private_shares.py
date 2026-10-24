import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import threading
import unittest
from unittest.mock import patch, MagicMock
import uuid

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('private_shares', REPO / 'image/scripts/private-share-broker.py')
broker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(broker)


class Stream:
    def __init__(self, payload):
        self.data = io.BytesIO(payload)

    def recv(self, size):
        return self.data.read(min(size, 3))  # Exercise real partial reads.


class PrivateShareTests(unittest.TestCase):
    def setUp(self):
        self.diagnostics = tempfile.TemporaryDirectory()
        self.failure_file = Path(self.diagnostics.name) / 'failure.json'
        self.failure_patch = patch.object(broker, 'FAILURE_RECORD', self.failure_file)
        self.failure_patch.start()
        self.addCleanup(self.diagnostics.cleanup)
        self.addCleanup(self.failure_patch.stop)

    def test_failure_record_preserves_first_stage_without_private_exception_details(self):
        owner = broker.Broker()
        with patch.object(broker.sys, 'stderr', new_callable=io.StringIO) as stderr:
            owner.fail('share.control.eof', EOFError('/private/path/token=secret'))
            original = self.failure_file.read_bytes()
            owner.fail('share.attach.failed', ValueError('later cleanup'))
        record = json.loads(original)
        self.assertEqual('share.control.eof', record['event'])
        self.assertEqual('EOFError', record['error'])
        self.assertTrue(owner.failed.is_set())
        self.assertEqual(original, self.failure_file.read_bytes())
        self.assertNotIn('secret', original.decode())
        self.assertNotIn('secret', stderr.getvalue())
        self.assertEqual(0o600, self.failure_file.stat().st_mode & 0o777)

    def test_later_fatal_failure_replaces_a_transient_record_without_leaking_details(self):
        owner = broker.Broker()
        with patch.object(broker.sys, 'stderr', new_callable=io.StringIO) as stderr:
            owner.fail('share.control.recovering', EOFError('transient private detail'), terminate=False)
            self.assertFalse(owner.failed.is_set())
            self.assertFalse(json.loads(self.failure_file.read_text())['fatal'])
            owner.fail('share.isolation.failed', OSError(13, 'secret path and token'))
            fatal_record = self.failure_file.read_bytes()
            owner.fail('share.attach.failed', ValueError('later failure'))
        self.assertEqual(fatal_record, self.failure_file.read_bytes())
        record = json.loads(fatal_record)
        self.assertEqual('share.isolation.failed', record['event'])
        self.assertEqual('PermissionError', record['error'])
        self.assertEqual(13, record['errno'])
        self.assertTrue(record['fatal'])
        self.assertTrue(owner.failed.is_set())
        self.assertNotIn('secret', fatal_record.decode())
        self.assertNotIn('private detail', stderr.getvalue())

    def test_unavailable_diagnostics_never_prevent_fail_closed(self):
        owner = broker.Broker()
        with patch.object(broker, 'FAILURE_RECORD', self.failure_file / 'missing' / 'record.json'), \
                patch.object(broker.sys, 'stderr') as stderr:
            stderr.write.side_effect = OSError('journal unavailable')
            owner.fail('share.attach.failed', ValueError('invalid peer data'))
        self.assertTrue(owner.failed.is_set())
        self.assertEqual('share.attach.failed', owner.failure_reason)

    def manifest(self, entries, version=1):
        return struct.pack('!HH', version, len(entries)) + bytes(16) + b''.join(
            identity + bytes([len(name)]) + name for identity, name in entries)

    def test_manifest_is_complete_before_creating_mounts(self):
        identity = uuid.uuid4().bytes
        payload = self.manifest([(identity, b'Code')])
        token, folders = broker.manifest(Stream(payload))
        self.assertEqual(bytes(16), token)
        self.assertEqual({identity: 'Code'}, folders)
        for entries in [[], [(identity, b'../x')], [(identity, b'-option')],
                        [(identity, b'Name'), (identity, b'Other')],
                        [(identity, b'Name'), (uuid.uuid4().bytes, b'Name')]]:
            with self.subTest(entries=entries):
                with self.assertRaises(ValueError):
                    broker.manifest(Stream(self.manifest(entries)))
        for cut in range(len(payload)):
            with self.assertRaises((EOFError, ValueError)):
                broker.manifest(Stream(payload[:cut]))

    def test_client_uses_existing_stream_without_network_ssh_or_root_parsing(self):
        command = broker.mount_command(Path('/mnt/term-shares/Code'))
        self.assertEqual(['/usr/bin/setpriv', '--reuid=1000', '--regid=1000', '--clear-groups'], command[:4])
        self.assertEqual('/usr/bin/sshfs', command[4])
        self.assertIn('passive', command[-1].split(','))
        self.assertIn('sshfs_sync', command[-1].split(','))
        self.assertIn('direct_io', command[-1].split(','))
        self.assertNotIn('reconnect', command[-1].split(','))

    def test_data_connections_require_this_boots_manifest_and_unique_folder_identity(self):
        owner = broker.Broker()
        fake = MagicMock()
        fake.recv.side_effect = [b'TSF1']
        with self.assertRaises(ValueError):
            owner.attach(fake)
        owner.control = object()
        owner.token = bytes(16)
        identity = uuid.uuid4().bytes
        owner.folders = {identity: 'Code'}
        fake.recv.side_effect = [b'TSF1', bytes([1]) * 16, identity]
        with patch.object(broker.subprocess, 'Popen') as spawn:
            with self.assertRaises(ValueError):
                owner.attach(fake)
            spawn.assert_not_called()

    def test_readiness_requires_live_mounts_and_owner_loss_fails_the_vm_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ready, disconnected = threading.Event(), threading.Event()
            control = MagicMock()
            control.sendall.side_effect = lambda _: ready.set()
            control.recv.side_effect = lambda _: (disconnected.wait(2), b'')[1]
            owner = broker.Broker()
            owner.control = control
            identity = uuid.uuid4().bytes
            owner.folders = {identity: 'Code'}
            child = MagicMock(); child.poll.return_value = None
            owner.children = {identity: child}
            with patch.object(broker, 'ROOT', root), patch.object(broker, 'READY', root / 'ready'), \
                    patch.object(broker, 'mount_points', return_value={str(root / 'Code')}), \
                    patch.object(broker.os, 'stat') as probe:
                thread = threading.Thread(target=owner.watch)
                thread.start()
                try:
                    self.assertTrue(ready.wait(2))
                    control.sendall.assert_called_once_with(b'\0')
                    self.assertEqual('1\n', (root / 'ready').read_text())
                    probe.assert_called_once_with(str(root / 'Code'))
                finally:
                    disconnected.set()
                    thread.join(2)
                self.assertFalse(thread.is_alive())
                self.assertTrue(owner.failed.is_set())
                self.assertEqual('share.control.eof', json.loads(self.failure_file.read_text())['event'])

    def test_failed_mount_cannot_publish_readiness(self):
        owner = broker.Broker()
        identity = uuid.uuid4().bytes
        owner.folders = {identity: 'Code'}
        child = MagicMock(); child.poll.return_value = 1
        owner.children = {identity: child}
        owner.control = MagicMock()
        owner.watch()
        self.assertTrue(owner.failed.is_set())
        owner.control.sendall.assert_not_called()


if __name__ == '__main__':
    unittest.main()
