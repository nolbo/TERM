import importlib.util
from pathlib import Path
import tempfile
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
    def test_recovery_probe_waits_for_listener_and_unix_endpoint_before_any_rpc(self):
        connect = mock.Mock(side_effect=[ConnectionResetError('starting'), EOFError('endpoint starting'), None])
        with mock.patch.object(boot.recovery.time, 'sleep') as wait:
            boot.recovery.await_connection(connect)
        self.assertEqual(3, connect.call_count)
        self.assertEqual(2, wait.call_count)

    def test_recovery_readiness_is_bounded_and_retains_last_failure(self):
        connect = mock.Mock(side_effect=ConnectionResetError('starting'))
        with mock.patch.object(boot.recovery.time, 'monotonic', side_effect=[0, 0, 61]), \
                mock.patch.object(boot.recovery.time, 'sleep'):
            with self.assertRaises(TimeoutError) as failure:
                boot.recovery.await_connection(connect)
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
