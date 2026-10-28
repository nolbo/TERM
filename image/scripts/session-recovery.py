#!/usr/bin/python3
"""Host-only resumable session tunnel; PTYs remain owned by sessiond."""
import importlib.util
from pathlib import Path
import socket
import struct
import threading
import time

spec = importlib.util.spec_from_file_location('term_session_transport', Path(__file__).with_name('private-share-transport.py'))
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)
PORT = 40507
LEASE = 60
ENDPOINT = '/run/droid-sessiond/relay.sock'


class SessionRelay:
    def __init__(self, endpoint=ENDPOINT):
        self.endpoint = endpoint
        self.lock = threading.Lock()
        self.token = None
        self.stream = None
        self.last_seen = time.monotonic()

    def attach(self, connection):
        connection.settimeout(10)
        if transport.exact(connection, 4) != b'TSR1':
            raise ValueError('invalid session relay')
        token = transport.exact(connection, 16)
        operation = transport.exact(connection, 1)
        if operation not in (b'\0', b'\1', b'\2'):
            raise ValueError('invalid session generation operation')
        with self.lock:
            # Optional TSR1 release: only the exact current token can relinquish
            # its generation. Never replay input into a replacement owner.
            if operation == b'\2':
                if self.token is not None and token != self.token:
                    connection.sendall(b'\1')
                    return
                if self.stream is not None:
                    self.stream.close()
                self.stream, self.token = None, None
                connection.sendall(b'\0')
                return
            if self.stream is not None and (self.stream.closed or
                    (self.stream.connection is None and time.monotonic() - self.last_seen > LEASE)):
                self.stream.close()
                self.stream, self.token = None, None
            if self.token is None:
                if operation != b'\1':
                    connection.sendall(b'\2')
                    raise ValueError('session replay generation was lost')
                application = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                try:
                    application.connect(self.endpoint)
                except Exception:
                    application.close()
                    raise
                self.token, self.stream = token, transport.ReplayStream()
                self.stream.bridge(application)
            elif token != self.token:
                connection.sendall(b'\1')
                raise ValueError('session relay belongs to another owner')
            relay = self.stream
            self.last_seen = time.monotonic()
        connection.sendall(b'\0')
        relay.attach(connection)

    def expire(self):
        with self.lock:
            if self.stream is None:
                return
            if self.stream.connection is not None:
                self.last_seen = time.monotonic()
            elif time.monotonic() - self.last_seen > LEASE:
                self.stream.close()
                self.stream, self.token = None, None


def serve():
    relay = SessionRelay()
    with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as listener:
        listener.bind((socket.VMADDR_CID_ANY, PORT))
        listener.listen(8)
        listener.settimeout(1)
        # Handshakes are bounded and serial; application I/O has independent pumps.
        while True:
            relay.expire()
            try:
                connection, address = listener.accept()
            except socket.timeout:
                continue
            try:
                if address[0] != socket.VMADDR_CID_HOST:
                    raise ValueError('session relay requires Android host')
                relay.attach(connection)
            except Exception:
                transport.shutdown(connection)


if __name__ == '__main__':
    serve()
