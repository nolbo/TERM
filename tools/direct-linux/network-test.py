"""Loopback HTTP/TLS and selector work using unmodified Python standard libraries."""
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import os
import selectors
import socket
import ssl
import struct
import subprocess
import urllib.error
import urllib.request

PAYLOAD = ("network Linux 한글\n".encode() + bytes(range(256))) * 1024
CERTIFICATE = "/network-fixture/cert.pem"
KEY = "/network-fixture/key.pem"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        assert self.path == "/payload"
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(PAYLOAD)))
        self.end_headers()
        self.wfile.write(PAYLOAD)


class Server(HTTPServer):
    def __init__(self, tls):
        self.context = None
        if tls:
            self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            self.context.load_cert_chain(CERTIFICATE, KEY)
        super().__init__(("127.0.0.1", 0), Handler)
        self.timeout = 4

    def get_request(self):
        stream, peer = super().get_request()
        stream.settimeout(4)
        try:
            if self.context:
                stream = self.context.wrap_socket(stream, server_side=True)
            return stream, peer
        except BaseException:
            stream.close()
            raise


def request(tls=False, hostname="fixture.local", trusted=True, rejection=False):
    server = Server(tls)
    port = server.server_address[1]
    child = os.fork()
    if child == 0:
        try:
            server.handle_request()
            server.server_close()
        except BaseException:
            os._exit(91)
        os._exit(0)
    server.server_close()
    try:
        context = (ssl.create_default_context(cafile=CERTIFICATE) if trusted
                   else ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)) if tls else None
        try:
            with urllib.request.urlopen(f"{'https' if tls else 'http'}://{hostname}:{port}/payload",
                                        context=context, timeout=4) as response:
                assert not rejection
                data = response.read()
                assert len(data) == len(PAYLOAD)
                assert hashlib.sha256(data).digest() == hashlib.sha256(PAYLOAD).digest()
        except urllib.error.URLError as error:
            assert rejection and isinstance(error.reason, ssl.SSLCertVerificationError), error
    finally:
        waited, status = os.waitpid(child, 0)
        assert waited == child and os.waitstatus_to_exitcode(status) == 0


def dns_reply(packet, tcp):
    """Minimal fixture responder; the client under test uses unmodified glibc."""
    identifier, flags, questions = struct.unpack_from("!HHH", packet)
    assert not flags & 0x8000 and questions == 1
    cursor, labels = 12, []
    while packet[cursor]:
        size = packet[cursor]
        assert 0 < size <= 63 and cursor + size < len(packet)
        labels.append(packet[cursor + 1:cursor + size + 1].decode("ascii"))
        cursor += size + 1
    kind, query_class = struct.unpack_from("!HH", packet, cursor + 1)
    question = packet[12:cursor + 5]
    name = ".".join(labels)
    assert query_class == 1 and kind in (1, 28)
    assert name in ("address.fixture.test", "missing.fixture.test", "truncated.fixture.test", "search.fixture.test")
    truncated = name == "truncated.fixture.test" and not tcp
    missing = name == "missing.fixture.test"
    response_flags = 0x8180 | (0x0200 if truncated else 0) | (3 if missing else 0)
    answer = b""
    if not truncated and not missing:
        data = socket.inet_pton(socket.AF_INET if kind == 1 else socket.AF_INET6,
                                "192.0.2.123" if kind == 1 else "2001:db8::123")
        answer = b"\xc0\x0c" + struct.pack("!HHIH", kind, 1, 42, len(data)) + data
    return (struct.pack("!HHHHHH", identifier, response_flags, 1, int(bool(answer)), 0, 0)
            + question + answer), (name, kind)


def exact_read(stream, count):
    output = b""
    while len(output) < count:
        chunk = stream.recv(count - len(output))
        assert chunk
        output += chunk
    return output


def dns_workload(kind):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp, socket.socket() as tcp:
        udp.bind(("127.0.0.1", 0))
        port = udp.getsockname()[1]
        assert port > 1023
        tcp.bind(("127.0.0.1", port))
        tcp.listen(1)
        child = os.fork()
        if child == 0:
            try:
                observed = []
                with selectors.DefaultSelector() as selector:
                    selector.register(udp, selectors.EVENT_READ)
                    selector.register(tcp, selectors.EVENT_READ)
                    for _ in range(6):
                        events = selector.select(4)
                        assert len(events) == 1
                        if events[0][0].fileobj is udp:
                            packet, peer = udp.recvfrom(4096)
                            reply, query = dns_reply(packet, False)
                            # An incorrect transaction ID must be discarded.
                            wrong = struct.pack("!H", struct.unpack_from("!H", reply)[0] ^ 0xffff) + reply[2:]
                            if query == ("address.fixture.test", 1):
                                wrong = wrong[:-4] + socket.inet_pton(socket.AF_INET, "192.0.2.99")
                            assert udp.sendto(wrong, peer) == len(wrong)
                            assert udp.sendto(reply, peer) == len(reply)
                            observed.append(("udp", *query))
                        else:
                            stream, _ = tcp.accept()
                            with stream:
                                stream.settimeout(3)
                                size = struct.unpack("!H", exact_read(stream, 2))[0]
                                assert 12 <= size <= 4096
                                reply, query = dns_reply(exact_read(stream, size), True)
                                stream.sendall(struct.pack("!H", len(reply)) + reply)
                                observed.append(("tcp", *query))
                assert observed == [
                    ("udp", "address.fixture.test", 1), ("udp", "address.fixture.test", 28),
                    ("udp", "missing.fixture.test", 1), ("udp", "truncated.fixture.test", 1),
                    ("tcp", "truncated.fixture.test", 1), ("udp", "search.fixture.test", 1),
                ], observed
            except BaseException:
                os._exit(92)
            os._exit(0)
        udp.close()
        tcp.close()
        try:
            result = subprocess.run([f"/bin/dns-{kind}", str(port)], capture_output=True, timeout=8)
            assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
            assert b"PASS glibc DNS A/AAAA/NXDOMAIN/search/TCP retry\n" in result.stdout
        finally:
            waited, status = os.waitpid(child, 0)
            assert waited == child and os.waitstatus_to_exitcode(status) == 0, status
    print(f"PASS {kind} glibc DNS UDP/TCP traffic, A/AAAA/NXDOMAIN/search and transaction IDs", flush=True)


def main():
    dns_workload("static")
    dns_workload("dynamic")
    addresses = socket.getaddrinfo("fixture.local", 443, type=socket.SOCK_STREAM)
    assert addresses and {a[4][0] for a in addresses} == {"127.0.0.1"}
    assert socket.getnameinfo(("127.0.0.1", 443), socket.NI_NUMERICHOST | socket.NI_NUMERICSERV) == ("127.0.0.1", "443")
    print("PASS Python hosts-file name resolution", flush=True)
    request()
    request(tls=True)
    request(tls=True, hostname="wrong.invalid", rejection=True)
    request(tls=True, trusted=False, rejection=True)
    print("PASS Python HTTP/TLS payload and certificate/hostname verification", flush=True)
    first, second = socket.socketpair()
    with first, second, selectors.DefaultSelector() as selector:
        first.setblocking(False)
        selector.register(first, selectors.EVENT_READ)
        assert selector.select(0) == []
        second.sendall(b"selector")
        assert selector.select(1)[0][0].fileobj is first
        assert first.recv(8) == b"selector"
        selector.unregister(first)
    print("PASS Python socketpair/default selector", flush=True)
    print("PASS Linux network applications", flush=True)


if __name__ == "__main__":
    main()
