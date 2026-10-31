import importlib.util
from pathlib import Path
import tempfile
import struct
import unittest
import threading
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('systemd_boot', REPO / 'image/scripts/smoke-avf-systemd.py')
boot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boot)


class Connection:
    def __init__(self, response):
        self.response = bytearray(response)
        self.sent = b''

    def sendall(self, data):
        self.sent += data

    def recv(self, size):
        count = min(size, 2, len(self.response))
        result = bytes(self.response[:count])
        del self.response[:count]
        return result


class NormalSystemdBootTests(unittest.TestCase):
    def test_offline_progress_requires_independent_completion_then_exit_on_first_list(self):
        for state, code in [(2, 7), (1, -1), (2, 127)]:
            with self.subTest(state=state, code=code):
                probe = boot.session_probe.SessionProbe.__new__(boot.session_probe.SessionProbe)
                events = []
                identity = 'flood-session'
                created = b'\x81' + boot.session_probe.text(identity)
                listed = b'\x82\0\1' + b''.join(boot.session_probe.text(value)
                    for value in [identity, 'Shell', '/bin/sh', '/']) + struct.pack('!qHHBi', 0, 24, 80, state, code)
                probe.application = mock.Mock()
                probe.cid = 123
                probe.run = mock.Mock()
                probe.application.close.side_effect = lambda: events.append('close')
                probe.connect = lambda: events.append('connect')
                def request(payload):
                    events.append(payload[0])
                    return {1: created, 2: listed, 7: b'\x87'}[payload[0]]
                probe.request = request
                with mock.patch.object(boot.session_probe, 'wait_completion',
                        side_effect=lambda *args: events.append('file-completion')), \
                        mock.patch.object(boot.session_probe.time, 'sleep',
                        side_effect=lambda seconds: events.append(('offline', seconds))):
                    if (state, code) == (2, 7):
                        probe.verify_offline_progress()
                        self.assertEqual([1, 'close', 'file-completion', ('offline', 1), 'connect', 2, 7], events)
                    else:
                        with self.assertRaisesRegex(AssertionError, f'state={state}, exitCode={code}'):
                            probe.verify_offline_progress()
                        self.assertEqual([1, 'close', 'file-completion', ('offline', 1), 'connect', 2], events)

    def test_completion_uses_only_file_port_and_verifies_exact_bytes_and_digest(self):
        marker = b'completion-token'
        magic = struct.pack('!I', 0x54465431)
        digest = boot.session_probe.hashlib.sha256(marker).digest()
        good = magic + b'\0' + struct.pack('!Q', len(marker)) + marker + digest
        for response in [good, b'BAD!', magic + b'\1', magic + b'\0' + struct.pack('!Q', 999),
                         good[:-1] + b'!', good[:-1]]:
            with self.subTest(response=response), mock.patch.object(boot.session_probe.socket, 'socket') as factory:
                wire = Connection(response)
                connection = factory.return_value.__enter__.return_value
                connection.recv.side_effect = wire.recv
                connection.sendall.side_effect = wire.sendall
                if response == good:
                    boot.session_probe.read_completion(123, 'fixture/done', marker)
                    connection.connect.assert_called_once_with((123, 40505))
                    self.assertEqual(magic + b'\2' + boot.session_probe.text('fixture/done') + b'\0' + digest, wire.sent)
                else:
                    with self.assertRaises((RuntimeError, EOFError)):
                        boot.session_probe.read_completion(123, 'fixture/done', marker)
                    self.assertFalse(wire.sent.endswith(b'\0' + digest))

    def test_completion_wait_retries_missing_file_but_is_bounded(self):
        with mock.patch.object(boot.session_probe, 'read_completion', side_effect=[EOFError(), None]) as read, \
                mock.patch.object(boot.session_probe.time, 'sleep'):
            boot.session_probe.wait_completion(123, 'fixture/done', b'token')
            self.assertEqual(2, read.call_count)
        with mock.patch.object(boot.session_probe, 'read_completion', side_effect=EOFError()), \
                mock.patch.object(boot.session_probe.time, 'monotonic', side_effect=[0, 0, 121]), \
                mock.patch.object(boot.session_probe.time, 'sleep'):
            with self.assertRaises(TimeoutError):
                boot.session_probe.wait_completion(123, 'fixture/done', b'token')

    def test_missing_offline_completion_never_reconnects_session(self):
        probe = boot.session_probe.SessionProbe.__new__(boot.session_probe.SessionProbe)
        probe.cid, probe.application, probe.run, probe.connect = 123, mock.Mock(), mock.Mock(), mock.Mock()
        probe.request = mock.Mock(return_value=b'\x81' + boot.session_probe.text('session'))
        with mock.patch.object(boot.session_probe, 'wait_completion', side_effect=TimeoutError()):
            with self.assertRaises(TimeoutError):
                probe.verify_offline_progress()
        probe.application.close.assert_called_once()
        probe.connect.assert_not_called()
        self.assertEqual(1, probe.request.call_count)

    def test_direct_probe_waits_for_listener_before_any_rpc(self):
        connect = mock.Mock(side_effect=[ConnectionResetError('starting'), EOFError('endpoint starting'), None])
        with mock.patch.object(boot.session_probe.time, 'sleep') as wait:
            boot.session_probe.await_connection(connect)
        self.assertEqual(3, connect.call_count)
        self.assertEqual(2, wait.call_count)

    def test_direct_readiness_is_bounded_and_retains_last_failure(self):
        connect = mock.Mock(side_effect=ConnectionResetError('starting'))
        with mock.patch.object(boot.session_probe.time, 'monotonic', side_effect=[0, 0, 61]), \
                mock.patch.object(boot.session_probe.time, 'sleep'):
            with self.assertRaises(TimeoutError) as failure:
                boot.session_probe.await_connection(connect)
        self.assertIsInstance(failure.exception.__cause__, ConnectionResetError)

    def test_session_probe_checks_a_fragmented_actual_list_response(self):
        response = boot.HEADER.pack(boot.MAGIC, 1, 1, 2, 0, 0, 1, 3) + b'\x82\0\0'
        connection = Connection(response)
        boot.probe_sessiond(connection)
        self.assertEqual(boot.HEADER.pack(boot.MAGIC, 1, 1, 1, 0, 0, 1, 1) + b'\x02', connection.sent)

    def test_unrelated_or_oversized_response_is_not_a_ready_session_daemon(self):
        for fields in [(boot.MAGIC, 1, 1, 2, 0, 0, 99, 3),
                       (boot.MAGIC, 1, 1, 2, 0, 0, 1, 1024 ** 3),
                       (boot.MAGIC, 1, 1, 5, 0, 0, 1, 3)]:
            with self.subTest(fields=fields), self.assertRaisesRegex(RuntimeError, 'header'):
                boot.probe_sessiond(Connection(boot.HEADER.pack(*fields)))

    def test_disconnected_or_nonempty_guest_list_fails_readiness(self):
        header = boot.HEADER.pack(boot.MAGIC, 1, 1, 2, 0, 0, 1, 3)
        for response in [b'', header[:10], header + b'\x82']:
            with self.subTest(response=response), self.assertRaises(EOFError):
                boot.probe_sessiond(Connection(response))
        with self.assertRaisesRegex(RuntimeError, 'not empty'):
            boot.probe_sessiond(Connection(header + b'\x82\0\1'))

    def test_stop_race_does_not_mask_a_completed_guest(self):
        with tempfile.TemporaryDirectory() as temporary:
            cid = Path(temporary) / 'container'
            cid.write_text('task-owned-container')
            process = mock.Mock()
            process.poll.side_effect = [None, 0]
            with mock.patch.object(boot.subprocess, 'run', return_value=mock.Mock(returncode=1)) as docker:
                boot.stop_boot(process, cid)
            docker.assert_called_once()
            self.assertEqual('task-owned-container', docker.call_args.args[0][-1])
            process.wait.assert_called_once_with(timeout=20)

    def test_failed_graceful_stop_kills_only_the_owned_disposable_vm(self):
        with tempfile.TemporaryDirectory() as temporary:
            cid = Path(temporary) / 'container'
            cid.write_text('task-owned-container')
            process = mock.Mock()
            process.poll.return_value = None
            with mock.patch.object(boot.subprocess, 'run', return_value=mock.Mock(returncode=1)) as docker:
                boot.stop_boot(process, cid)
            self.assertEqual(['docker', 'kill', 'task-owned-container'], docker.call_args_list[1].args[0])

    def test_boot_error_cannot_be_reported_as_verified(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'home.img').write_bytes(b'original-state')
            with mock.patch.object(boot, 'check_host'), mock.patch.object(boot.subprocess, 'run'), \
                    mock.patch.object(boot, 'boot', side_effect=RuntimeError('config device timed out')) as boot_once:
                with self.assertRaisesRegex(RuntimeError, 'config device timed out'):
                    boot.verify(directory, 'factory-image')
            self.assertEqual(1, boot_once.call_count)
            self.assertEqual(b'original-state', (directory / 'home.img').read_bytes())


if __name__ == '__main__':
    unittest.main()
