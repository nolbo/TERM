import importlib.util
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest

spec = importlib.util.spec_from_file_location('session_recovery', Path(__file__).resolve().parents[2] /
                                             'image/scripts/session-recovery.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
transport = module.transport


class SessionRecoveryTests(unittest.TestCase):
    def test_release_is_scoped_to_the_token_and_does_not_wait_for_expiry(self):
        relay = module.SessionRelay('must-not-open')
        owner = transport.ReplayStream()
        application, peer = socket.socketpair()
        owner.bridge(application)
        relay.stream, relay.token = owner, bytes(16)
        try:
            for token, expected in [(bytes([1]) * 16, 1), (bytes(16), 0), (bytes(16), 0)]:
                client, server = socket.socketpair()
                try:
                    client.sendall(b'TSR1' + token + b'\2')
                    relay.attach(server)
                    self.assertEqual(bytes([expected]), transport.exact(client, 1))
                    if expected == 1:
                        self.assertFalse(owner.closed)
                finally:
                    client.close(); server.close()
            self.assertIsNone(relay.stream)
            self.assertIsNone(relay.token)
            peer.settimeout(1)
            self.assertEqual(b'', peer.recv(1))
        finally:
            owner.close(); peer.close()

    def test_output_and_input_keep_one_application_connection_across_socket_loss(self):
        with tempfile.TemporaryDirectory() as directory:
            endpoint = str(Path(directory) / 'session.sock')
            with socket.socket(socket.AF_UNIX) as listener:
                listener.bind(endpoint)
                listener.listen()
                relay = module.SessionRelay(endpoint)
                host = transport.ReplayStream()
                self.addCleanup(host.close)
                self.addCleanup(lambda: relay.stream.close() if relay.stream else None)
                token = bytes(range(16))
                established = False

                def connect():
                    nonlocal established
                    client, server = socket.socketpair()
                    errors = []
                    def accept():
                        try:
                            relay.attach(server)
                        except Exception as error:
                            errors.append(error)
                    worker = threading.Thread(target=accept, daemon=True)
                    worker.start()
                    client.sendall(b'TSR1' + token + bytes([0 if established else 1]))
                    self.assertEqual(b'\0', transport.exact(client, 1))
                    host.attach(client)
                    worker.join(2)
                    self.assertFalse(worker.is_alive())
                    self.assertEqual([], errors)
                    established = True

                connect()
                application, _ = listener.accept()
                self.addCleanup(application.close)
                application.settimeout(2)
                host.write(b'input-once')
                self.assertEqual(b'input-once', transport.exact(application, 10))
                application.sendall(b'first-output')
                self.assertEqual(b'first', host.read(5))
                host.detach(host.connection)
                application.sendall(b'-offline-output')
                host.write(b'-next-input')
                connect()
                received = bytearray()
                while len(received) < 22:
                    received.extend(host.read(22 - len(received)))
                self.assertEqual(b'-output-offline-output', received)
                self.assertEqual(b'-next-input', transport.exact(application, 11))
                application.settimeout(0.05)
                with self.assertRaises(socket.timeout):
                    application.recv(1)  # Previously consumed input was not repeated.

    def test_expired_owner_closes_its_internal_session_socket(self):
        relay = module.SessionRelay('unused')
        relay.stream, relay.token = transport.ReplayStream(), bytes(16)
        previous = relay.stream
        application, peer = socket.socketpair()
        try:
            previous.bridge(application)
            relay.last_seen = time.monotonic() - module.LEASE - 1
            relay.expire()
            self.assertTrue(previous.closed)
            self.assertIsNone(relay.stream)
            self.assertIsNone(relay.token)
            peer.settimeout(2)
            self.assertEqual(b'', peer.recv(1))
            self.assertEqual(-1, application.fileno())
        finally:
            previous.close()
            peer.close()

    def test_resume_cannot_recreate_a_lost_proxy_and_reexecute_unacknowledged_input(self):
        relay = module.SessionRelay('must-not-open')
        client, server = socket.socketpair()
        try:
            client.sendall(b'TSR1' + bytes(16) + b'\0')
            with self.assertRaisesRegex(ValueError, 'generation was lost'):
                relay.attach(server)
            self.assertEqual(b'\2', transport.exact(client, 1))
            self.assertIsNone(relay.stream)
        finally:
            client.close(); server.close()
