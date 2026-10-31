"""Normal-boot probes of the production direct session wire (no mock PTY)."""
import socket
import struct
import time


def exact(connection, length):
    result = bytearray()
    while len(result) < length:
        chunk = connection.recv(length - len(result))
        if not chunk:
            raise EOFError('Session transport closed')
        result.extend(chunk)
    return bytes(result)


HEADER = struct.Struct('>IHBBHHQI')
MAGIC = 0x44505459


def await_connection(connect, timeout=60):
    """A systemd simple unit is active before its vsock listener is ready."""
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            connect()
            return
        except (OSError, EOFError) as error:
            last_error = error
            time.sleep(0.2)
    raise TimeoutError('Session listener did not become ready') from last_error


def text(value):
    value = value.encode()
    return struct.pack('!H', len(value)) + value


class SessionProbe:
    def __init__(self, cid):
        self.cid = cid
        self.sequence, self.output, self.identity = 0, bytearray(), None
        self.gaps = []
        try:
            await_connection(self.connect)
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
            connection.connect((self.cid, 40500))
            self.application = connection
        except Exception:
            connection.close()
            raise

    def frame(self):
        header = HEADER.unpack(exact(self.application, HEADER.size))
        assert header[:3] == (MAGIC, 1, 1) and header[-1] <= 1024 * 1024
        payload = exact(self.application, header[-1])
        if header[3] == 3 and payload[0] == 0x90:
            size = struct.unpack('!H', payload[1:3])[0]
            count = struct.unpack('!I', payload[3 + size:7 + size])[0]
            assert len(payload) == 7 + size + count
            self.output.extend(payload[7 + size:])
        if header[3] == 3 and payload[0] == 0x92:
            self.gaps.append(struct.unpack("!q", payload[-8:])[0])
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
        self.application.close()
        time.sleep(3)  # Output is generated while the raw socket does not exist.
        await_connection(self.connect)
        assert self.request(b"\2")[0] == 0x82  # Identify sessions before output.
        self.wait(b'\x1eAFTER\x1f', start)
        assert bytes(self.output[start:]).count(b'\x1eBEFORE\x1f') == 1
        assert bytes(self.output[start:]).count(b'\x1eAFTER\x1f') == 1
        assert -1 in self.gaps
        result = self.run("rm -f /tmp/term-resume-once; printf X >> /tmp/term-resume-once; printf '\\036COUNT'; wc -c < /tmp/term-resume-once")
        assert b'\x1eCOUNT1\r\n' in result, result

    def verify_offline_progress(self):
        # Observe EXITED in the first LIST response after an offline flood. Testing
        # a marker in the same shell after reconnect could accidentally let a
        # previously blocked producer finish, hiding the original regression.
        arguments = ['/bin/sh', '-c', 'sleep 1; head -c 33554432 /dev/zero; exit 7']
        encoded = '3:' + ''.join(f'{len(arg.encode())}:{arg}' for arg in arguments)
        created = self.request(b'\1' + text('/bin/sh') + text('/') + struct.pack('!HHH', 24, 80, 1)
                               + text('DROID_SESSION_ARGV') + text(encoded))
        assert created[0] == 0x81
        length = struct.unpack('!H', created[1:3])[0]
        identity = created[3:3 + length].decode()
        self.application.close()
        # The factory uses TCG while systemd is still starting other services.
        # This is a progress check, not a native-host throughput benchmark. Keep
        # the socket absent for the entire bounded grace period; never poll LIST
        # while waiting, since reconnecting could unblock a broken output loop.
        time.sleep(30)
        await_connection(self.connect)
        payload = self.request(b'\2')
        assert payload[0] == 0x82
        count = struct.unpack('!H', payload[1:3])[0]
        offset, found = 3, False
        for _ in range(count):
            fields = []
            for _ in range(4):
                size = struct.unpack('!H', payload[offset:offset + 2])[0]
                fields.append(payload[offset + 2:offset + 2 + size].decode())
                offset += 2 + size
            _, _, _, state, code = struct.unpack('!qHHBi', payload[offset:offset + 17])
            offset += 17
            if fields[0] == identity:
                assert (state, code) == (2, 7), (
                    f'Disconnected viewer progress failed: state={state}, exitCode={code} after 30s offline')
                found = True
        assert found and offset == len(payload)
        self.request(b'\7' + text(identity) + struct.pack('!i', 9))

    def close(self):
        if self.identity:
            try:
                self.request(b'\7' + text(self.identity) + struct.pack('!i', 9))
            except (OSError, EOFError):
                pass
        if hasattr(self, 'application'):
            self.application.close()
