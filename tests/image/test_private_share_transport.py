import importlib.util
from pathlib import Path
import socket
import struct
import threading
import unittest
from unittest import mock

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

    def test_ring_wrap_growth_and_credit_do_not_shift_or_lose_bytes(self):
        ring = transport.Ring(73)
        self.assertEqual(0, len(ring.data))
        expected = bytearray()
        for index in range(200):
            data = bytes([index % 256]) * min(11, 73 - len(expected))
            ring.append(data)
            expected.extend(data)
            count = min(7, len(expected))
            self.assertEqual(expected[:count], ring.read(0, count))
            ring.discard(count)
            del expected[:count]
            self.assertEqual(expected, ring.read(0, len(expected)))
        with self.assertRaises(ValueError):
            ring.append(b'x' * 74)

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

    def test_ready_frames_share_one_write_and_partial_batch_replays_exactly_once(self):
        for interrupt in (False, True):
            left, right = self.owner(transport.WINDOW), self.owner(transport.WINDOW)
            payload = bytes(range(256)) * (transport.CHUNK * 4 // 256)
            left.write(payload)
            a, b = socket.socketpair()
            writes = []

            class ObservedSocket:
                def __init__(self, stream, receiver, interrupted, recorded):
                    self.stream, self.receiver = stream, receiver
                    self.interrupted, self.recorded = interrupted, recorded

                def __getattr__(self, name):
                    return getattr(self.stream, name)

                def sendall(self, data):
                    self.recorded.append(bytes(data))
                    if self.interrupted and len(data) > transport.CHUNK:
                        # First frame is complete; the second is cut midway through
                        # its payload. Reconnection must retain only complete frames.
                        first = transport.FRAME.size * 2 + transport.CHUNK
                        self.stream.sendall(data[:first])
                        with self.receiver.condition:
                            if not self.receiver.condition.wait_for(lambda: self.receiver.received == transport.CHUNK, 2):
                                raise AssertionError('first frame was not received')
                        self.stream.sendall(data[first:first + transport.FRAME.size + 7])
                        raise OSError('interrupted batch')
                    self.stream.sendall(data)

            worker = threading.Thread(target=left.attach, args=(ObservedSocket(a, right, interrupt, writes),))
            worker.start()
            right_link = right.attach(b)
            worker.join(2)
            self.assertFalse(worker.is_alive())
            if interrupt:
                self.assertTrue(right_link.wait(2))
                self.assertEqual(transport.CHUNK, right.received)
                self.assertEqual([], self.connect(left, right))
            self.assertEqual(payload, self.read(right, len(payload)))
            batch = next(data for data in writes if len(data) > transport.CHUNK)
            offset = 0
            frames = []
            while offset < len(batch):
                kind, position, count = transport.FRAME.unpack_from(batch, offset)
                self.assertLessEqual(count, transport.CHUNK)
                offset += transport.FRAME.size
                if kind == 1:
                    frames.append((position, batch[offset:offset + count]))
                offset += count
            self.assertEqual([i * transport.CHUNK for i in range(4)], [position for position, _ in frames])
            self.assertEqual(payload, b''.join(data for _, data in frames))
            left.write(b'x')
            self.assertEqual(b'x', self.read(right, 1))
            left.close(); right.close()

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

    def test_owner_close_interrupts_blocked_native_application_write_and_read(self):
        host, owner = self.owner(transport.WINDOW), self.owner(transport.WINDOW)
        self.assertEqual([], self.connect(host, owner))
        application, peer = socket.socketpair()
        application.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
        sending, sent = threading.Event(), threading.Event()
        pumps = []
        thread_type = threading.Thread

        class ObservedSocket:
            def recv(self, size):
                return application.recv(size)

            def sendall(self, data):
                sending.set()
                try:
                    application.sendall(data)
                finally:
                    sent.set()

            def shutdown(self, how):
                application.shutdown(how)

            def close(self):
                application.close()

        def thread(*args, **kwargs):
            result = thread_type(*args, **kwargs)
            pumps.append(result)
            return result

        try:
            with mock.patch.object(transport.threading, 'Thread', side_effect=thread):
                owner.bridge(ObservedSocket())
            host.write(b'x' * transport.CHUNK)
            self.assertTrue(sending.wait(2))
            self.assertFalse(sent.wait(0.05), 'unread native socket must actually block the writer')
            owner.close()
            owner.close()
            for pump in pumps:
                pump.join(2)
                self.assertFalse(pump.is_alive(), 'closing the owner must wake both native I/O directions')
            self.assertEqual(-1, application.fileno())
            self.assertTrue(sent.is_set())
        finally:
            transport.shutdown(peer)
            owner.close()
            for pump in pumps:
                pump.join(2)

    def test_closed_owner_rejects_and_closes_late_application_socket(self):
        owner = self.owner()
        application, peer = socket.socketpair()
        try:
            owner.close()
            with self.assertRaises(EOFError):
                owner.bridge(application)
            self.assertEqual(-1, application.fileno())
            peer.settimeout(2)
            self.assertEqual(b'', peer.recv(1))
        finally:
            application.close()
            peer.close()


if __name__ == '__main__':
    unittest.main()
