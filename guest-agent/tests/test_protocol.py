import importlib.util
import json
import os
from pathlib import Path
import socket
import struct
import threading
import unittest
from unittest import mock


AGENT_PATH = Path(__file__).resolve().parents[1] / "xtermx_agent.py"
SPEC = importlib.util.spec_from_file_location("xtermx_agent", AGENT_PATH)
agent_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(agent_module)


class ProtocolTest(unittest.TestCase):
    def test_control_and_frame_round_trip(self):
        left, right = socket.socketpair()
        try:
            control = {"version": 1, "operation": "create_or_attach"}
            agent_module.send_control(left, control)
            self.assertEqual(control, agent_module.recv_control(right))

            payload = os.urandom(8192)
            agent_module.send_frame(right, agent_module.FRAME_OUTPUT, payload)
            self.assertEqual(
                (agent_module.FRAME_OUTPUT, payload), agent_module.recv_frame(left)
            )
        finally:
            left.close()
            right.close()

    def test_oversized_messages_are_rejected_before_allocation(self):
        left, right = socket.socketpair()
        try:
            left.sendall(struct.pack("!I", agent_module.MAX_CONTROL_BYTES + 1))
            with self.assertRaises(ValueError):
                agent_module.recv_control(right)

            left.sendall(
                struct.pack("!BI", agent_module.FRAME_OUTPUT, agent_module.MAX_FRAME_BYTES + 1)
            )
            with self.assertRaises(ValueError):
                agent_module.recv_frame(right)
        finally:
            left.close()
            right.close()

    def test_reconnect_attaches_to_the_same_guest_session(self):
        created_sessions = []

        class FakeSession:
            def __init__(self, session_id, cwd, name, columns, rows, on_exit):
                self.session_id = session_id
                self.cwd = cwd or "/home/droid"
                self.pid = 123
                self.clients = set()
                created_sessions.append(self)

            def attach(self, client, requested_offset):
                client.session = self
                self.clients.add(client)
                agent_module.send_control(client.stream, {
                    "ok": True,
                    "pid": self.pid,
                    "cwd": self.cwd,
                    "output_offset": requested_offset,
                })

            def detach(self, client):
                self.clients.discard(client)
                client.session = None

            def write(self, payload):
                pass

            def resize(self, columns, rows):
                pass

            def terminate(self):
                pass

        with mock.patch.object(agent_module, "Session", FakeSession), \
             mock.patch.dict(os.environ, {}, clear=True):
            runtime = agent_module.Agent()
            for _ in range(2):
                host, guest = socket.socketpair()
                worker = threading.Thread(target=runtime._handle_host, args=(guest,))
                worker.start()
                agent_module.send_control(host, {
                    "version": agent_module.VERSION,
                    "operation": "create_or_attach",
                    "session_id": "stable-session",
                    "cwd": "/home/droid",
                    "columns": 80,
                    "rows": 24,
                })
                response = agent_module.recv_control(host)
                self.assertTrue(response["ok"])
                host.close()
                worker.join(timeout=2)
                self.assertFalse(worker.is_alive())

        self.assertEqual(1, len(created_sessions))
        self.assertIs(created_sessions[0], runtime.sessions["stable-session"])

    def test_attach_replays_only_output_after_requested_offset(self):
        session = object.__new__(agent_module.Session)
        session.pid = 123
        session.cwd = "/home/droid"
        session.clients = set()
        session.clients_lock = threading.Lock()
        session.scrollback = bytearray(b"cdef")
        session.output_offset = 6
        session.exited = False
        session.exit_code = 0

        host, guest = socket.socketpair()
        client = agent_module.Client(guest)
        try:
            session.attach(client, 4)
            response = agent_module.recv_control(host)
            self.assertEqual(4, response["output_offset"])
            self.assertEqual(
                (agent_module.FRAME_OUTPUT, b"ef"), agent_module.recv_frame(host)
            )
        finally:
            host.close()
            client.close()


if __name__ == "__main__":
    unittest.main()
