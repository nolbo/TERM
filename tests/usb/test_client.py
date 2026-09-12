"""No Android/USB hardware required: exercise client framing against a socket peer."""
import importlib.util
from pathlib import Path
import socket
import struct
import threading
import unittest
import json
import io
import tempfile
import sys
from unittest.mock import patch, MagicMock

path = Path(__file__).resolve().parents[2] / "android-runtime/src/main/assets/usb/terminal-usb.py"
sys.path.insert(0, str(path.parent))
spec = importlib.util.spec_from_file_location("terminal_usb", path)
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


class ClientTest(unittest.TestCase):
    def test_tooling_lifecycle_and_choices_use_scoped_flat_requests(self):
        cases = [(["start", "isideload", "phone"], {"action": "start", "tool": "isideload", "device": "phone"}),
                 (["status", "operation"], {"action": "status", "operation": "operation"}),
                 (["cancel", "operation"], {"action": "cancel", "operation": "operation"}),
                 (["select", "operation", "A,B"], {"action": "select", "operation": "operation", "choices": "A,B"})]
        for args, fields in cases:
            broker = MagicMock(); broker.call.return_value = {"state": "RUNNING"}
            with patch.object(client, "Broker", return_value=broker), patch.object(sys, "argv", ["terminal-usb", "tooling"] + args), patch("sys.stdout", new=io.StringIO()):
                client.main()
            broker.call.assert_called_once_with("tooling", **fields)
    def test_watch_detaches_without_canceling_host_operation(self):
        broker = MagicMock(); broker.call.return_value = {"state": "RUNNING"}
        with patch.object(client, "Broker", return_value=broker), patch.object(sys, "argv", ["terminal-usb", "tooling", "watch", "operation"]), patch.object(client.time, "sleep", side_effect=KeyboardInterrupt), patch("sys.stdout", new=io.StringIO()):
            client.main()
        broker.call.assert_called_once_with("tooling", action="status", operation="operation")
    def test_tooling_cli_uses_only_registered_host_action_without_fds_or_secrets(self):
        broker = MagicMock()
        broker.__enter__.return_value = broker
        broker.call.return_value = {"completed": True, "publicMuxSocket": False}
        with patch.object(client, "Broker", return_value=broker), patch.object(sys, "argv", ["terminal-usb", "tooling", "check", "opaque-phone"]), patch("sys.stdout", new=io.StringIO()):
            client.main()
        broker.call.assert_called_once_with("tooling", action="check", device="opaque-phone")

    def test_hid_helpers_use_interface_scoped_requests(self):
        broker = MagicMock()
        broker.call.side_effect = [{"opened": True}, {"count": 2, "data": "0501"}, {"count": 2}, {"closed": True}]
        with patch.object(client, "Broker", return_value=broker):
            with client.RawInterface("dev", 3) as interface:
                self.assertEqual(b"\x05\x01", interface.hid_report_descriptor(64))
                self.assertEqual(2, interface.hid_set_report(3, 1, b"\x01\x02"))
        broker.call.assert_any_call("control", type=0x81, request=6, value=0x2200, index=3, timeout=1000, length=64)
        broker.call.assert_any_call("control", type=0x21, request=9, value=0x0301, index=3, timeout=1000, data="0102")

    def test_run_exposes_api_without_allocating_usb_connection(self):
        with patch.dict(client.sys.modules, {client.__name__: client}), \
                patch.object(client.sys, "argv", ["terminal-usb", "run", "protocol.py", "arg"]), \
                patch.object(client.runpy, "run_path") as run, \
                patch.object(client, "Broker") as broker:
            client.main()
            self.assertIs(client.sys.modules["terminal_usb"], client)
            self.assertEqual(["protocol.py", "arg"], client.sys.argv)
            run.assert_called_once_with("protocol.py", run_name="__main__")
            broker.assert_not_called()

    def test_serial_requires_port_for_multiport_and_closes_failed_client(self):
        broker = MagicMock()
        broker.call.return_value = {"devices": [{"id": "dev", "serialPorts": [
            {"driver": "ftdi", "port": 0}, {"driver": "ftdi", "port": 1}]}]}
        with patch.object(client, "Broker", return_value=broker):
            with self.assertRaisesRegex(ValueError, "Multiple"):
                client.SerialPort("dev")
        broker.close.assert_called_once()
        self.assertEqual(1, broker.call.call_count)

    def test_serial_preserves_binary_data_and_reports_uncertain_write_without_retry(self):
        broker = MagicMock()
        broker.call.side_effect = [
            {"devices": [{"id": "dev", "serialPorts": [{"driver": "cp21xx", "port": 0}]}]},
            {"opened": True}, {"count": 3, "data": "00ff80"},
            {"count": 1, "timedOut": True, "deliveryUncertain": True},
        ]
        with patch.object(client, "Broker", return_value=broker):
            with client.SerialPort("dev") as port:
                self.assertEqual(b"\x00\xff\x80", port.read(3))
                with self.assertRaises(client.SerialWriteError) as caught:
                    port.write(b"ab")
                self.assertEqual(1, caught.exception.count)
        self.assertEqual(4, broker.call.call_count)
        broker.call.assert_any_call("serial-open", device="dev", driver="cp21xx", port=0)
        broker.close.assert_called_once()

    def test_raw_interface_preserves_short_write_and_rejects_wrong_direction(self):
        broker = MagicMock()
        broker.call.side_effect = [{"opened": True}, {"count": 1}, {"closed": True}]
        with patch.object(client, "Broker", return_value=broker):
            with client.RawInterface("dev", 2) as interface:
                self.assertEqual(1, interface.write(2, b"ab"))
                with self.assertRaises(ValueError):
                    interface.write(0x81, b"ab")
        broker.close.assert_called_once()

    def test_serial_cli_selects_port_and_configures_without_opening_an_extra_broker(self):
        port = MagicMock()
        port.__enter__.return_value = port
        with patch.object(client, "SerialPort", return_value=port) as factory, \
                patch.object(client, "Broker") as broker, \
                patch.object(client.sys, "argv", ["terminal-usb", "serial", "dev", "--port", "1", "--no-dtr"]), \
                patch.object(client.select, "select", return_value=([client.sys.stdin], [], [])), \
                patch.object(client.os, "read", return_value=b""), \
                patch.object(client.sys, "stderr", new_callable=io.StringIO):
            client.main()
        factory.assert_called_once_with("dev", 1)
        port.configure.assert_called_once_with(baud=115200, bits=8, stops=0, parity=0, dtr=False, rts=True)
        broker.assert_not_called()

    def test_download_publishes_only_complete_binary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "result"
            broker = MagicMock()
            broker.receive.side_effect = [
                {"event": "start", "size": 2},
                {"event": "data", "offset": 0, "data": "00ff"},
                {"bytes": 2, "completed": True},
            ]
            client.download(broker, "device", "/file", str(target))
            self.assertEqual(b"\x00\xff", target.read_bytes())
            with self.assertRaises(ValueError):
                client.download(broker, "device", "/file", str(target))
            self.assertEqual(b"\x00\xff", target.read_bytes())

    def test_interrupted_download_leaves_no_file(self):
        with tempfile.TemporaryDirectory() as directory:
            broker = MagicMock()
            broker.receive.side_effect = [{"event": "start", "size": 2}, RuntimeError("disconnected")]
            with self.assertRaises(RuntimeError):
                client.download(broker, "device", "/file", str(Path(directory) / "result"))
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_upload_sends_binary_chunks_after_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.bin"
            source.write_bytes(b"\x00\xff")
            broker = MagicMock()
            broker.receive.side_effect = [
                {"event": "ready", "size": 2, "path": "/TerminalImports/new-input.bin"},
                {"event": "accepted", "offset": 2},
                {"bytes": 2, "completed": True},
            ]
            with patch.object(client.sys, "stderr", new_callable=io.StringIO):
                result = client.upload(broker, "device", str(source))
            self.assertTrue(result["completed"])
            broker.send.assert_any_call("iphone-data", offset=0, data="00ff")

    def test_iphone_cli_is_typed_and_uses_bounded_longer_deadline(self):
        broker = MagicMock()
        broker.call.return_value = {"operation": "pair", "completed": True}
        with patch.object(client, "Broker", return_value=broker), \
                patch.object(client.sys, "argv", ["terminal-usb", "iphone", "pair", "opaque-device"]), \
                patch.object(client.sys, "stdout", new_callable=io.StringIO) as output:
            client.main()
            self.assertTrue(json.loads(output.getvalue())["completed"])
        broker.socket.settimeout.assert_called_once_with(30)
        broker.call.assert_called_once_with("iphone", action="pair", device="opaque-device")
        broker.close.assert_called_once()

    def test_iphone_cli_rejects_raw_pair_record_operation(self):
        with patch.object(client, "Broker") as broker, \
                patch.object(client.sys, "argv", ["terminal-usb", "iphone", "ReadPairRecord", "device"]), \
                patch.object(client.sys, "stderr", new_callable=io.StringIO):
            with self.assertRaises(SystemExit):
                client.main()
            broker.assert_not_called()

    def test_partial_frames_and_host_identity(self):
        local, remote = socket.socketpair()
        broker = client.Broker.__new__(client.Broker)
        broker.socket, broker.token = local, "session-secret"
        seen = []

        def peer():
            with remote:
                stream = remote.makefile("rb")
                size = struct.unpack(">I", stream.read(4))[0]
                seen.append(json.loads(stream.read(size)))
                data = json.dumps({"ok": True, "result": {"count": 2}}).encode()
                frame = struct.pack(">I", len(data)) + data
                for byte in frame:
                    remote.sendall(bytes([byte]))
                stream.close()

        thread = threading.Thread(target=peer)
        thread.start()
        try:
            self.assertEqual({"count": 2}, broker.call("write", data="0102", token="forged", timeout=100))
        finally:
            broker.close()
            thread.join(timeout=3)
        self.assertEqual("session-secret", seen[0]["token"])
        self.assertEqual(1, seen[0]["version"])

    def test_eof_is_session_loss(self):
        local, remote = socket.socketpair()
        broker = client.Broker.__new__(client.Broker)
        broker.socket = local
        remote.close()
        try:
            with self.assertRaisesRegex(RuntimeError, "disconnected"):
                broker.read_exact(4)
        finally:
            broker.close()


if __name__ == "__main__":
    unittest.main()
