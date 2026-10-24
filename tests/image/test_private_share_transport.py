import importlib.util
from pathlib import Path
import socket
import struct
import threading
import unittest

spec = importlib.util.spec_from_file_location('share_transport', Path(__file__).resolve().parents[2] /
                                             'image/scripts/private-share-transport.py')
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)


class ReplayTransportTests(unittest.TestCase):
    def setUp(self):
        self.owners = []

    def tearDown(self):
        for owner in self.owners:
            owner.close()

    def owner(self, capacity=64):
        result = transport.ReplayStream(capacity)
        self.owners.append(result)
        return result

    def connect(self, left, right):
        a, b = socket.socketpair()
        errors = []
        def attach(owner, stream):
            try:
                owner.attach(stream)
            except Exception as error:
                errors.append(error)
        threads = [threading.Thread(target=attach, args=args) for args in ((left, a), (right, b))]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(2)
            self.assertFalse(thread.is_alive(), 'replay handshake stalled')
        return errors

    def read(self, owner, count):
        result = bytearray()
        while len(result) < count:
            data = owner.read(count - len(result))
            self.assertTrue(data)
            result.extend(data)
        return bytes(result)

    def test_reconnect_keeps_buffered_and_unacknowledged_bytes_without_reexecuting_prefix(self):
        left, right = self.owner(), self.owner()
        self.assertEqual([], self.connect(left, right))
        left.write(b'first-second')
        self.assertEqual(b'first', self.read(right, 5))
        left.detach(left.connection)
        left.write(b'-third')
        self.assertEqual([], self.connect(left, right))
        self.assertEqual(b'-second-third', self.read(right, 13))
        right.write(b'response')
        self.assertEqual(b'response', self.read(left, 8))

    def test_full_duplex_receive_windows_do_not_block_acknowledgements(self):
        left, right = self.owner(8), self.owner(8)
        self.assertEqual([], self.connect(left, right))
        left.write(b'abcdefgh'); right.write(b'12345678')
        completed = [threading.Event(), threading.Event()]
        def write(owner, data, event):
            owner.write(data); event.set()
        a = threading.Thread(target=write, args=(left, b'i', completed[0]), daemon=True)
        b = threading.Thread(target=write, args=(right, b'9', completed[1]), daemon=True)
        a.start(); b.start()
        self.assertEqual(b'abcd', self.read(right, 4))
        self.assertEqual(b'1234', self.read(left, 4))
        self.assertTrue(completed[0].wait(2)); self.assertTrue(completed[1].wait(2))
        self.assertEqual(b'efghi', self.read(right, 5))
        self.assertEqual(b'56789', self.read(left, 5))

    def test_lost_owner_generation_is_rejected_instead_of_replaying_mutations(self):
        left, right = self.owner(), self.owner()
        self.assertEqual([], self.connect(left, right))
        left.write(b'write')
        self.assertEqual(b'write', self.read(right, 5))
        with left.condition:
            self.assertTrue(left.condition.wait_for(lambda: left.acknowledged == 5, 2))
        left.detach(left.connection)
        self.assertTrue(self.connect(left, self.owner()))
        self.assertFalse(left.closed)

    def test_invalid_frame_is_local_to_its_socket_and_does_not_discard_stream_state(self):
        owner = self.owner()
        a, b = socket.socketpair()
        attached = threading.Thread(target=owner.attach, args=(a,))
        attached.start()
        transport.exact(b, transport.HANDSHAKE.size)
        b.sendall(transport.HANDSHAKE.pack(b'TRS2', 64, 0, 0))
        attached.join(2)
        self.assertFalse(attached.is_alive())
        link = owner.connection
        b.sendall(transport.FRAME.pack(1, 4, 1) + b'x')
        self.assertTrue(link['closed'].wait(2))
        self.assertFalse(owner.closed)
        b.close()
        peer = self.owner()
        self.assertEqual([], self.connect(owner, peer))
        peer.write(b'safe')
        self.assertEqual(b'safe', self.read(owner, 4))

    def test_explicit_owner_close_unblocks_application_reader(self):
        owner = self.owner()
        result = []
        thread = threading.Thread(target=lambda: result.append(owner.read(1)))
        thread.start(); owner.close(); thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertEqual([b''], result)


if __name__ == '__main__':
    unittest.main()
