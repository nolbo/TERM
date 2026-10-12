"""Protocol/device mocks plus real local sockets and PTYs; no physical USB implied."""
import array
import io
import json
import os
from pathlib import Path
import select
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "android-runtime/src/main/assets/usb"))
import usb_mtp as mtp
import usb_relays as relays
import usb_storage as storage
import usb_profiles as profiles


def container(kind, code, transaction, data=b""):
    return struct.pack("<IHHI", len(data) + 12, kind, code, transaction) + data


class FakeUsb:
    def __init__(self, incoming=b"", fragment=7):
        self.incoming = bytearray(incoming)
        self.fragment = fragment
        self.writes = []
        self.short = False

    def poll(self, endpoint, size, timeout):
        if not self.incoming:
            raise RuntimeError("Mock device detached")
        count = min(size, self.fragment, len(self.incoming))
        data = bytes(self.incoming[:count]); del self.incoming[:count]
        return data

    def write(self, endpoint, data, timeout=1000):
        self.writes.append(bytes(data))
        return max(0, len(data) - 1) if self.short else len(data)


class MtpTest(unittest.TestCase):
    def test_fragmented_data_and_response_preserve_transaction(self):
        payload = struct.pack("<3I", 2, 0x10001, 0x20001)
        raw = FakeUsb(container(2, 0x1004, 0, payload) + container(3, 0x2001, 0))
        client = mtp.MtpClient(raw, 129, 1, 64)
        data, response, total = client.operation(0x1004)
        self.assertEqual([0x10001, 0x20001], mtp.u32_array(data))
        self.assertEqual(len(payload), total)
        self.assertEqual(1, client.transaction)
        self.assertEqual([], response)
        self.assertEqual(container(1, 0x1004, 0), raw.writes[0])

    def test_invalid_transaction_and_oversize_poison_session(self):
        cases = [container(3, 0x2001, 9), struct.pack("<IHHI", 70000, 2, 0x1004, 0), container(3, 0x2019, 0)]
        for packet in cases:
            with self.subTest(packet=packet):
                client = mtp.MtpClient(FakeUsb(packet), 129, 1, 64)
                with self.assertRaises(RuntimeError): client.operation(0x1004)
                self.assertFalse(client.healthy)
                with self.assertRaisesRegex(RuntimeError, "session lost"): client.operation(0x1004)

    def test_upload_short_write_is_not_replayed(self):
        raw = FakeUsb(); raw.short = True
        client = mtp.MtpClient(raw, 129, 1, 64)
        with self.assertRaisesRegex(RuntimeError, "uncertain"):
            client.operation(0x100d, source=io.BytesIO(b"abc"), length=3)
        self.assertEqual(1, len(raw.writes))

    def test_outgoing_container_boundary_and_zlp(self):
        raw = FakeUsb(container(3, 0x2001, 0))
        client = mtp.MtpClient(raw, 129, 1, 64)
        client.operation(0x100d, source=io.BytesIO(b"a" * 52), length=52)
        self.assertEqual(container(2, 0x100d, 0, b"a" * 52), raw.writes[1])
        self.assertEqual(b"", raw.writes[2])

    def test_object_dataset_offsets_and_malformed_arrays(self):
        data = bytearray(53)
        struct.pack_into("<I", data, 0, 0x10001)
        struct.pack_into("<I", data, 8, 42)
        struct.pack_into("<I", data, 38, 17)
        data[52] = 3
        data += "ab\0".encode("utf-16-le")
        self.assertEqual(dict(name="ab", size=42, directory=False, storage=0x10001, parent=17), mtp.object_info(data))
        for data in (b"", struct.pack("<I", 4097), struct.pack("<I", 2) + b"\0" * 4):
            with self.assertRaises(RuntimeError): mtp.u32_array(data)

    def test_interrupted_download_cleans_temporary_and_preserves_existing(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "result"
            client = mtp.MtpClient(FakeUsb(), 129, 1, 64)
            client.info = lambda handle: dict(directory=False, size=4)
            with self.assertRaises(RuntimeError): client.get(1, str(target))
            self.assertEqual([], list(Path(directory).iterdir()))
            target.write_bytes(b"existing")
            with self.assertRaises(ValueError): client.get(1, str(target))
            self.assertEqual(b"existing", target.read_bytes())


class AdbTest(unittest.TestCase):
    def test_owned_child_dies_when_bridge_parent_is_killed(self):
        code = "from usb_relays import start_owned_process; import sys, time; p = start_owned_process([sys.executable, '-c', 'import time; time.sleep(30)']); print(p.pid, flush=True); time.sleep(30)"
        environment = dict(os.environ, PYTHONPATH=str(Path(relays.__file__).parent))
        owner = subprocess.Popen([sys.executable, "-c", code], env=environment, stdout=subprocess.PIPE, text=True)
        child = None
        try:
            self.assertTrue(select.select([owner.stdout], [], [], 3)[0])
            child = int(owner.stdout.readline())
            owner.kill(); owner.wait(timeout=3)
            def alive():
                try:
                    state = Path(f"/proc/{child}/stat").read_text().split(")", 1)[1].split()[0]
                    return state != "Z"
                except FileNotFoundError:
                    return False
            deadline = time.monotonic() + 3
            while alive() and time.monotonic() < deadline: time.sleep(0.01)
            self.assertFalse(alive(), "Owned child outlived its killed bridge")
        finally:
            relays.stop_process(owner)
            owner.stdout.close()
            if child is not None:
                try: os.kill(child, 9)
                except ProcessLookupError: pass

    @staticmethod
    def packet(data=b"", command=b"WRTE"):
        code = int.from_bytes(command, "little")
        return struct.pack("<6I", code, 1, 2, len(data), sum(data) & 0xffffffff, code ^ 0xffffffff) + data

    def test_header_payload_separation_and_zlp(self):
        raw = FakeUsb()
        packet = self.packet(b"a" * 64)
        relays.write_adb_packet(raw, 1, 64, packet)
        self.assertEqual([packet[:24], b"a" * 64, b""], raw.writes)
        raw.short = True
        with self.assertRaisesRegex(RuntimeError, "Incomplete"):
            relays.write_adb_packet(raw, 1, 64, packet)
        self.assertEqual(4, len(raw.writes))

    def test_oversize_and_bad_magic_are_rejected(self):
        packet = bytearray(self.packet())
        packet[23] ^= 1
        with self.assertRaises(RuntimeError): relays.adb_header(packet)
        packet = bytearray(self.packet())
        struct.pack_into("<I", packet, 12, relays.ADB_LIMIT + 1)
        with self.assertRaises(RuntimeError): relays.adb_header(packet)

    def test_real_unix_peer_handles_fragmented_bidirectional_packets_and_disconnect(self):
        local, remote = socket.socketpair()
        outbound, inbound = self.packet(b"from-client"), self.packet(b"from-device")
        class Device(FakeUsb):
            def poll(self, *args, **kwargs):
                if not self.incoming:
                    time.sleep(0.001); return None
                return super().poll(*args, **kwargs)
        raw = Device(inbound, fragment=3)
        failures = []
        def run():
            try: relays.relay_adb(raw, local, 129, 1, 64)
            except BaseException as error: failures.append(error)
        thread = threading.Thread(target=run)
        thread.start()
        try:
            remote.settimeout(3)
            for start in range(0, len(outbound), 5): remote.sendall(outbound[start:start + 5])
            received = bytearray()
            while len(received) < len(inbound): received.extend(remote.recv(128))
            self.assertEqual(inbound, received)
            deadline = time.monotonic() + 3
            while len(b"".join(raw.writes)) < len(outbound) and time.monotonic() < deadline: time.sleep(0.001)
            self.assertEqual(outbound, b"".join(raw.writes))
        finally:
            remote.close(); thread.join(timeout=3); local.close()
        self.assertFalse(thread.is_alive())
        self.assertEqual([], failures)


class PtyTest(unittest.TestCase):
    def test_real_pty_preserves_bytes_and_disconnect_closes_driver(self):
        done = threading.Event()
        written = bytearray()
        delivered = False
        port = MagicMock()
        port.__enter__.return_value = port
        port.write.side_effect = lambda data: written.extend(data)
        def read(size, timeout):
            nonlocal delivered
            if done.wait(0.002): raise RuntimeError("Mock device detached")
            if not delivered:
                delivered = True; return b"\0\xffdevice"
            return b""
        port.read.side_effect = read
        output = io.StringIO()
        failures = []
        def run():
            try: relays.serial_pty(lambda *unused: port, "device", 0, 9600, False, False)
            except RuntimeError as error: failures.append(str(error))
        with patch("sys.stdout", output), patch("sys.stderr", io.StringIO()):
            thread = threading.Thread(target=run); thread.start()
            fd = None
            try:
                deadline = time.monotonic() + 3
                while not output.getvalue() and time.monotonic() < deadline: time.sleep(0.002)
                fd = os.open(output.getvalue().strip(), os.O_RDWR | os.O_NONBLOCK)
                os.write(fd, b"\x00\xfehost")
                self.assertTrue(select.select([fd], [], [], 3)[0])
                self.assertEqual(b"\0\xffdevice", os.read(fd, 128))
                deadline = time.monotonic() + 3
                while not written and time.monotonic() < deadline: time.sleep(0.002)
                self.assertEqual(b"\x00\xfehost", written)
            finally:
                done.set(); thread.join(timeout=3)
                if fd is not None: os.close(fd)
        self.assertFalse(thread.is_alive())
        self.assertEqual(["Mock device detached"], failures)
        port.__exit__.assert_called_once()


class StorageTest(unittest.TestCase):
    def test_download_complete_empty_and_interrupted_without_overwrite(self):
        for payload in (b"", b"\0\xff"):
            with tempfile.TemporaryDirectory() as directory:
                broker = MagicMock()
                broker.call.return_value = dict(event="start", size=len(payload))
                broker.receive.side_effect = ([dict(event="data", offset=0, data=payload.hex())] if payload else []) + [dict(completed=True, bytes=len(payload))]
                path = str(Path(directory) / "new")
                storage.download(broker, "handle", path)
                self.assertEqual(payload, Path(path).read_bytes())
                with self.assertRaises(ValueError): storage.download(broker, "handle", path)
        with tempfile.TemporaryDirectory() as directory:
            broker.call.return_value = dict(event="start", size=1)
            broker.receive.side_effect = RuntimeError("revoked")
            with self.assertRaises(RuntimeError): storage.download(broker, "handle", str(Path(directory) / "new"))
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_upload_ack_validation_and_safe_name(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.bin"; path.write_bytes(b"ab")
            broker = MagicMock()
            broker.call.side_effect = [dict(event="ready", id="handle", name="new-input.bin", size=2), dict(event="accepted", offset=1)]
            with patch("sys.stderr", io.StringIO()), self.assertRaisesRegex(RuntimeError, "replay"):
                storage.upload(broker, "root", str(path))
            self.assertEqual(2, broker.call.call_count)
            with self.assertRaises(ValueError): storage.upload(broker, "root", str(path), "../other")

    def test_profile_roundtrip_and_no_authority_or_overwrite(self):
        value = dict(baud=9600, bits=8, stops=0, parity=0, dtr=False, rts=True, port=1)
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "profile.json")
            profiles.save(path, value)
            self.assertEqual(value, profiles.load(path))
            self.assertEqual(0o600, os.stat(path).st_mode & 0o777)
            with self.assertRaises(FileExistsError): profiles.save(path, value)
        with self.assertRaises(ValueError): profiles.validate(dict(value, token="secret"))
        with self.assertRaises(ValueError): profiles.validate(dict(value, baud=True))


@unittest.skipUnless(os.environ.get("TERMINAL_TEST_ADB") == "1", "Opt-in test starts an isolated real apt ADB server")
class InstalledAdbTest(unittest.TestCase):
    def test_private_server_accepts_localfilesystem_transport_and_closes(self):
        binary = __import__("shutil").which("adb")
        self.assertIsNotNone(binary)
        with tempfile.TemporaryDirectory(prefix="terminal-adb-test-") as directory:
            # Test keys/configuration remain in this test-owned temporary directory.
            with patch.dict(os.environ, {"ANDROID_USER_HOME": directory, "ADB_VENDOR_KEYS": directory}):
                with relays.private_adb_server(binary, directory) as (address, env):
                    path = directory + "/transport"
                    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
                        listener.bind(path); listener.listen(1); listener.settimeout(5)
                        connect = subprocess.Popen([binary, "connect", "localfilesystem:" + path], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                        try:
                            peer, _ = listener.accept()
                            with peer:
                                peer.settimeout(5)
                                header = bytearray()
                                while len(header) < 24: header.extend(peer.recv(24 - len(header)))
                                self.assertEqual(b"CNXN", header[:4])
                                self.assertGreater(relays.adb_header(header), 0)
                            self.assertEqual(0o600, os.stat(directory + "/server").st_mode & 0o777)
                        finally: relays.stop_process(connect)
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as probe:
                    self.assertNotEqual(0, probe.connect_ex(directory + "/server"))
