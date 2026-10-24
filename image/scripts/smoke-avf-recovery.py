"""Normal-boot probes of the production resumable session wire (no mock PTY)."""
import importlib.util
from pathlib import Path
import secrets
import socket
import struct
import threading
import time

spec = importlib.util.spec_from_file_location('smoke_replay', Path(__file__).with_name('private-share-transport.py'))
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)
HEADER = struct.Struct('>IHBBHHQI')
MAGIC = 0x44505459


def await_connection(connect, timeout=60):
    """A systemd simple unit is active before its Python/vsock listener is ready."""
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            connect()
            return
        except (OSError, EOFError) as error:
            last_error = error
            time.sleep(0.2)
    raise TimeoutError('Session recovery listener did not become ready') from last_error


def text(value):
    value = value.encode()
    return struct.pack('!H', len(value)) + value


class SessionProbe:
    def __init__(self, cid):
        self.cid, self.token = cid, secrets.token_bytes(16)
        self.replay = transport.ReplayStream()
        self.sequence, self.output, self.identity = 0, bytearray(), None
        self.established = False
        try:
            await_connection(self.connect)
            self.application, bridge = socket.socketpair()
            self.application.settimeout(20)
            self.replay.bridge(bridge)
            payload = b'\1' + text('/bin/sh') + text('/') + struct.pack('!HHH', 24, 80, 1) + text('TERM') + text('dumb')
            response = self.request(payload)
            assert response[0] == 0x81
            size = struct.unpack('!H', response[1:3])[0]
            self.identity = response[3:3 + size].decode()
            self.run('stty -echo')
        except Exception:
            self.close()
            raise

    def connect(self):
        connection = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
        connection.settimeout(10)
        try:
            connection.connect((self.cid, 40507))
            connection.sendall(b'TSR1' + self.token + bytes([0 if self.established else 1]))
            assert transport.exact(connection, 1) == b'\0'
            self.replay.attach(connection)
            self.established = True
        except Exception:
            transport.shutdown(connection)
            raise

    def frame(self):
        header = HEADER.unpack(transport.exact(self.application, HEADER.size))
        assert header[:3] == (MAGIC, 1, 1) and header[-1] <= 1024 * 1024
        payload = transport.exact(self.application, header[-1])
        if header[3] == 3 and payload[0] == 0x90:
            size = struct.unpack('!H', payload[1:3])[0]
            count = struct.unpack('!I', payload[3 + size:7 + size])[0]
            assert len(payload) == 7 + size + count
            self.output.extend(payload[7 + size:])
        return header, payload

    def request(self, payload):
        self.sequence += 1
        self.application.sendall(HEADER.pack(MAGIC, 1, 1, 1, 0, 0, self.sequence, len(payload)) + payload)
        while True:
            header, response = self.frame()
            if header[6] == self.sequence:
                assert header[3] == 2, response
                return response

    def write(self, command):
        data = command.encode() + b'\n'
        self.request(b'\6' + text(self.identity) + struct.pack('!I', len(data)) + data)

    def wait(self, marker, start=0):
        while marker not in self.output[start:]:
            self.frame()

    def run(self, command):
        start = len(self.output)
        marker = b'\x1eTERM_DONE\x1f'
        self.write(command + "; printf '\\036TERM_DONE\\037'")
        self.wait(marker, start)
        return bytes(self.output[start:])

    def verify_reconnect(self):
        start = len(self.output)
        self.write("printf '\\036BEFORE\\037'; sleep 2; printf '\\036AFTER\\037'")
        self.wait(b'\x1eBEFORE\x1f', start)
        self.replay.detach(self.replay.connection)
        time.sleep(3)  # Output is generated while the raw socket does not exist.
        self.connect()
        self.wait(b'\x1eAFTER\x1f', start)
        assert bytes(self.output[start:]).count(b'\x1eBEFORE\x1f') == 1
        assert bytes(self.output[start:]).count(b'\x1eAFTER\x1f') == 1
        result = self.run("printf X >> /tmp/term-resume-once; printf '\\036COUNT'; wc -c < /tmp/term-resume-once")
        assert b'\x1eCOUNT1\r\n' in result, result

    def close(self):
        if self.identity:
            try:
                self.request(b'\7' + text(self.identity) + struct.pack('!i', 9))
            except (OSError, EOFError):
                pass
        self.replay.close()
        if hasattr(self, 'application'):
            self.application.close()
