#!/usr/bin/env python3
"""Rootless client for TERM's session-scoped Android USB broker (Python stdlib only)."""
import argparse
import json
import os
import select
import socket
import struct
import sys
import stat
import tempfile
import re
import runpy
from contextlib import contextmanager

MAX_FRAME = 48 * 1024
FILE_LIMIT = 64 * 1024 * 1024
FILE_CHUNK = 16 * 1024


class Broker:
    def __init__(self):
        self.token = os.environ.get("TERMINAL_USB_TOKEN")
        address = os.environ.get("TERMINAL_USB_SOCKET")
        if not self.token or not address:
            raise RuntimeError("Start a new Rootless session in TERM to use USB")
        self.socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.socket.settimeout(10)
        self.socket.connect("\0" + address)

    def read_exact(self, count):
        result = bytearray()
        while len(result) < count:
            part = self.socket.recv(count - len(result))
            if not part:
                raise RuntimeError("USB broker disconnected; the transfer was not restored")
            result.extend(part)
        return bytes(result)

    def send(self, op, **fields):
        fields.update(version=1, token=self.token, op=op)
        data = json.dumps(fields, separators=(",", ":")).encode()
        if len(data) > MAX_FRAME:
            raise ValueError("Request is too large")
        self.socket.sendall(struct.pack(">I", len(data)) + data)

    def receive(self):
        length = struct.unpack(">I", self.read_exact(4))[0]
        if not 0 < length <= MAX_FRAME:
            raise RuntimeError("Invalid broker frame")
        reply = json.loads(self.read_exact(length))
        if not reply.get("ok"):
            raise RuntimeError(reply.get("error", "USB request failed"))
        return reply["result"]

    def call(self, op, **fields):
        self.send(op, **fields)
        return self.receive()

    def close(self):
        self.socket.close()

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()


class SerialWriteError(RuntimeError):
    """Output may already have reached the peripheral; never retry automatically."""
    def __init__(self, count):
        self.count = count
        super().__init__(f"Serial write timed out after at least {count} bytes; delivery is uncertain, do not blindly retry")


class SerialPort:
    """Binary serial transport. Use as a context manager; no automatic reconnect/replay."""
    def __init__(self, device, port=None):
        self.broker = Broker()
        try:
            devices = self.broker.call("list")["devices"]
            target = next((d for d in devices if d["id"] == device), None)
            choices = target.get("serialPorts", []) if target else []
            if not choices:
                raise ValueError("No supported USB serial driver; inspect terminal-usb list")
            if port is None:
                if len(choices) != 1:
                    raise ValueError("Multiple serial ports: select --port explicitly")
                port = choices[0]["port"]
            selected = next((p for p in choices if p["port"] == port), None)
            if selected is None:
                raise ValueError("Serial port is unavailable")
            self.broker.socket.settimeout(20)
            self.broker.call("serial-open", device=device, driver=selected["driver"], port=port)
        except BaseException:
            self.broker.close()
            raise

    def configure(self, baud=115200, bits=8, stops=0, parity=0, dtr=True, rts=True):
        return self.broker.call("configure", baud=baud, bits=bits, stops=stops, parity=parity, dtr=dtr, rts=rts)

    def read(self, size=4096, timeout=500):
        result = self.broker.call("read", length=size, timeout=timeout)
        data = bytes.fromhex(result["data"])
        if len(data) != result["count"] or len(data) > size:
            raise RuntimeError("Invalid serial read response")
        return data

    def write(self, data, timeout=1000):
        data = bytes(data)
        if len(data) > FILE_CHUNK:
            raise ValueError("One write is limited to 16 KiB; explicitly segment larger messages")
        result = self.broker.call("write", data=data.hex(), timeout=timeout)
        count = result["count"]
        if type(count) is not int or not 0 <= count <= len(data):
            raise RuntimeError("Invalid serial write response")
        if result.get("timedOut") or result.get("deliveryUncertain"):
            raise SerialWriteError(count)
        if count != len(data):
            raise RuntimeError("Incomplete serial write; do not blindly retry")
        return count

    def status(self):
        return self.broker.call("status")

    def ping(self):
        """Call before 30 seconds of inactivity; does not silently restore a lost connection."""
        return self.broker.call("ping")

    def close(self):
        self.broker.close()

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()


class RawInterface:
    """Generic bulk/interrupt/class-vendor transport within one approved interface."""
    def __init__(self, device, interface, configuration=None, alternate=0):
        self.interface = interface
        self.broker = Broker()
        try:
            if configuration is None and alternate == 0:
                self.metadata = self.broker.call("open", device=device, interface=interface, serial=False)
            else:
                if configuration is None:
                    raise ValueError("An explicit configuration is required for alternate settings")
                self.metadata = self.broker.call("raw-open", device=device, interface=interface, configuration=configuration, alternate=alternate)
        except BaseException:
            self.broker.close()
            raise

    def read(self, endpoint, size, timeout=1000):
        if not endpoint & 0x80:
            raise ValueError("An IN endpoint is required")
        result = self.broker.call("transfer", endpoint=endpoint, length=size, timeout=timeout)
        data = bytes.fromhex(result["data"])
        if len(data) != result["count"] or len(data) > size:
            raise RuntimeError("Invalid USB read response")
        return data

    def poll(self, endpoint, size, timeout=500):
        """None means a queued read is still pending; repeat with the same endpoint/size."""
        result = self.broker.call("poll", endpoint=endpoint, length=size, timeout=timeout)
        data = bytes.fromhex(result["data"])
        if type(result["pending"]) is not bool or type(result["count"]) is not int or len(data) != result["count"] or len(data) > size:
            raise RuntimeError("Invalid USB poll response")
        if result["pending"] and data:
            raise RuntimeError("Pending USB poll included data")
        return None if result["pending"] else data

    def ping(self):
        return self.broker.call("ping")

    def write(self, endpoint, data, timeout=1000):
        if endpoint & 0x80:
            raise ValueError("An OUT endpoint is required")
        data = bytes(data)
        result = self.broker.call("transfer", endpoint=endpoint, data=data.hex(), timeout=timeout)
        count = result["count"]
        if type(count) is not int or not 0 <= count <= len(data):
            raise RuntimeError("Invalid USB write response")
        return count  # A short write is visible to the device-specific protocol, never replayed.

    def control(self, request_type, request, value, index, data_or_length, timeout=1000):
        fields = dict(type=request_type, request=request, value=value, index=index, timeout=timeout)
        if request_type & 0x80:
            fields["length"] = data_or_length
        else:
            fields["data"] = bytes(data_or_length).hex()
        result = self.broker.call("control", **fields)
        return bytes.fromhex(result["data"]) if request_type & 0x80 else result["count"]

    def hid_report_descriptor(self, size=4096, timeout=1000):
        """Read a HID report descriptor; Android rejects this for non-HID interfaces."""
        return self.control(0x81, 6, 0x2200, self.interface, size, timeout)

    def hid_get_report(self, report_type, report_id, size, timeout=1000):
        if report_type not in (1, 2, 3) or not 0 <= report_id <= 255:
            raise ValueError("HID report type must be 1/2/3 and report ID 0..255")
        return self.control(0xa1, 1, report_type << 8 | report_id, self.interface, size, timeout)

    def hid_set_report(self, report_type, report_id, data, timeout=1000):
        if report_type not in (2, 3) or not 0 <= report_id <= 255:
            raise ValueError("HID output/feature report type must be 2/3 and report ID 0..255")
        return self.control(0x21, 9, report_type << 8 | report_id, self.interface, data, timeout)

    def close(self):
        try:
            self.broker.call("close")
        except (OSError, RuntimeError):
            pass  # Cleanup must also work after detach/revocation.
        finally:
            self.broker.close()

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()


@contextmanager
def terminal_input(cbreak=False):
    """Restore terminal settings even when a device disappears or Ctrl-C is pressed."""
    settings = None
    if cbreak:
        import termios
        import tty
        if not sys.stdin.isatty():
            raise ValueError("--cbreak requires an interactive terminal")
        settings = termios.tcgetattr(sys.stdin.fileno())
    try:
        if settings is not None:
            tty.setcbreak(sys.stdin.fileno())
        yield
    finally:
        if settings is not None:
            termios.tcsetattr(sys.stdin.fileno(), termios.TCSADRAIN, settings)


def pyusb_backend():
    """Explicit PyUSB backend; never monkey-patch libusb or pass Android FDs."""
    from usb_pyusb import create_backend
    return create_backend(Broker, RawInterface)


def download(broker, device, remote, destination):
    """Publish only a complete download, with an atomic no-replace local link."""
    if os.path.lexists(destination):
        raise ValueError("Destination already exists; choose a new local filename")
    broker.send("iphone", action="afc-get", device=device, path=remote)
    start = broker.receive()
    size = start.get("size")
    if start.get("event") != "start" or type(size) is not int or not 0 <= size <= FILE_LIMIT:
        raise RuntimeError("Invalid download header")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".terminal-download-", dir=os.path.dirname(os.path.abspath(destination)),
                                         delete=False) as output:
            temporary = output.name
            offset = 0
            while True:
                result = broker.receive()
                if result.get("event") != "data":
                    if result.get("completed") is not True or result.get("bytes") != size or offset != size:
                        raise RuntimeError("Incomplete download")
                    break
                text = result.get("data", "")
                if result.get("offset") != offset or not isinstance(text, str) or not re.fullmatch(r"[0-9a-f]{2,32768}", text) or len(text) % 2:
                    raise RuntimeError("Invalid download chunk")
                data = bytes.fromhex(text)
                if offset + len(data) > size:
                    raise RuntimeError("Download exceeds declared size")
                output.write(data)
                offset += len(data)
            output.flush()
            os.fsync(output.fileno())
        # Unlike rename/replace, link fails if a file or symlink appeared at destination.
        os.link(temporary, destination)
        return {"completed": True, "bytes": size, "local": destination}
    finally:
        if temporary is not None:
            os.unlink(temporary)


def upload(broker, device, source, name=None):
    name = name or os.path.basename(source)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", name):
        raise ValueError("Use --name with an ASCII upload name (letters, digits, dot, underscore, hyphen; up to 100 characters)")
    fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as data:
        before = os.fstat(data.fileno())
        size = before.st_size
        if not stat.S_ISREG(before.st_mode) or not 0 <= size <= FILE_LIMIT:
            raise ValueError("Upload requires a regular file of at most 64 MiB")
        broker.send("iphone", action="afc-put", device=device, name=name, size=size)
        ready = broker.receive()
        path = ready.get("path", "")
        if ready.get("event") != "ready" or ready.get("size") != size or not path.startswith("/TerminalImports/"):
            raise RuntimeError("Invalid upload header")
        print("Uploading to " + json.dumps(path) + "; an interrupted upload may leave a partial file there.", file=sys.stderr)
        offset = 0
        while offset < size:
            chunk = data.read(min(FILE_CHUNK, size - offset))
            if not chunk:
                raise RuntimeError("Local file shrank during upload")
            broker.send("iphone-data", offset=offset, data=chunk.hex())
            offset += len(chunk)
            accepted = broker.receive()
            if accepted.get("event") != "accepted" or accepted.get("offset") != offset:
                raise RuntimeError("Upload stream rejected")
        result = broker.receive()
        after = os.fstat(data.fileno())
        if result.get("completed") is not True or result.get("bytes") != size:
            raise RuntimeError("Incomplete upload")
        if before.st_size != after.st_size or before.st_mtime_ns != after.st_mtime_ns:
            raise RuntimeError("Source changed during upload; remote copy may be inconsistent: " + json.dumps(path))
        return dict(result, path=path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("list", help="List device descriptors and this terminal session ID")
    sub.add_parser("diagnostics", help="Bounded broker status without credentials or payloads")
    configure = sub.add_parser("configuration", help="Separately approve a device-wide USB configuration change")
    configure.add_argument("device")
    configure.add_argument("value", type=int)
    storage = sub.add_parser("storage", help="Files in an Android-picked removable volume folder")
    storage.add_argument("action", choices=("roots", "ls", "get", "put"))
    storage.add_argument("root", nargs="?")
    storage.add_argument("--parent", default="root")
    storage.add_argument("--handle")
    storage.add_argument("--file")
    storage.add_argument("--name")
    adb = sub.add_parser("adb", help="Bridge USB to an isolated apt-installed ADB server")
    adb.add_argument("device")
    mtp = sub.add_parser("mtp", help="Standard MTP/PTP stores, listing and bounded file transfers")
    mtp.add_argument("action", choices=("stores", "ls", "get", "put"))
    mtp.add_argument("device")
    mtp.add_argument("--storage", type=lambda v: int(v, 0))
    mtp.add_argument("--parent", type=lambda v: int(v, 0), default=0)
    mtp.add_argument("--handle", type=lambda v: int(v, 0))
    mtp.add_argument("--file")
    pty_command = sub.add_parser("serial-pty", help="Expose a serial byte stream through a local PTY")
    pty_command.add_argument("device")
    pty_command.add_argument("--port", type=int)
    pty_command.add_argument("--baud", type=int, default=115200)
    pty_command.add_argument("--no-dtr", action="store_true")
    pty_command.add_argument("--no-rts", action="store_true")
    pty_command.add_argument("--bits", type=int, choices=range(5, 9), default=8)
    pty_command.add_argument("--stops", type=int, choices=(0, 1, 2), default=0)
    pty_command.add_argument("--parity", type=int, choices=range(5), default=0)
    pty_command.add_argument("--profile", help="Load saved line/port settings (overrides launch line options)")
    pty_command.add_argument("--save-profile", help="Save settings to a new local file; never grants access")
    run = sub.add_parser("run", help="Run a Python USB protocol script; import SerialPort/RawInterface from terminal_usb")
    run.add_argument("script")
    run.add_argument("arguments", nargs=argparse.REMAINDER)
    iphone = sub.add_parser("iphone", help="Host-owned iPhone pairing/info; each action requires separate approval")
    iphone.add_argument("action", choices=("pair", "info", "unpair", "ls", "get", "put"))
    iphone.add_argument("device", help="Opaque attachment ID from terminal-usb list")
    iphone.add_argument("paths", nargs="*", help="ls [AFC_PATH]; get AFC_PATH LOCAL_FILE; put LOCAL_FILE")
    iphone.add_argument("--name", help="Safe ASCII name for a new upload in TerminalImports")
    script = sub.add_parser("raw", help="JSON-line control/transfer operations on one approved interface")
    script.add_argument("device")
    script.add_argument("interface", type=int)
    serial = sub.add_parser("serial", help="Auto-detected USB serial driver; Ctrl-C disconnects without terminating the shell")
    serial.add_argument("device")
    serial.add_argument("--port", type=int, help="Required when multiple serial ports are available")
    serial.add_argument("--cbreak", action="store_true", help="Send keystrokes immediately; restore terminal mode on exit")
    serial.add_argument("--baud", type=int, default=115200)
    serial.add_argument("--bits", type=int, choices=range(5, 9), default=8)
    serial.add_argument("--stops", type=int, choices=(0, 1, 2), default=0,
                        help="CDC encoding: 0=1 stop bit, 1=1.5, 2=2")
    serial.add_argument("--parity", type=int, choices=range(5), default=0,
                        help="0=none, 1=odd, 2=even, 3=mark, 4=space")
    serial.add_argument("--no-dtr", action="store_true")
    serial.add_argument("--no-rts", action="store_true")
    serial.add_argument("--profile", help="Load saved line/port settings (overrides launch line options)")
    serial.add_argument("--save-profile", help="Save settings to a new local file; never grants access")
    args = parser.parse_args()
    if args.command == "storage":
        from usb_storage import run_storage
        print(json.dumps(run_storage(Broker, args), ensure_ascii=True, indent=2))
        return
    if args.command == "mtp":
        from usb_mtp import run_mtp
        print(json.dumps(run_mtp(Broker, RawInterface, args), ensure_ascii=True, indent=2))
        return
    if args.command in ("adb", "serial-pty"):
        from usb_relays import adb_bridge, serial_pty
        if args.command == "adb":
            adb_bridge(Broker, RawInterface, args.device)
        else:
            from usb_profiles import settings
            serial_pty(SerialPort, args.device, **settings(args))
        return
    if args.command == "run":
        sys.modules["terminal_usb"] = sys.modules[__name__]
        sys.argv = [args.script] + args.arguments
        runpy.run_path(args.script, run_name="__main__")
        return
    if args.command == "serial":
        from usb_profiles import settings
        values = settings(args)
        with SerialPort(args.device, values.pop("port")) as port, terminal_input(args.cbreak):
            port.configure(**values)
            print("USB serial connected. Ctrl-C disconnects. No automatic reconnect or write replay.", file=sys.stderr)
            while True:
                if select.select([sys.stdin], [], [], 0)[0]:
                    data = os.read(sys.stdin.fileno(), 4096)
                    if not data:
                        break
                    port.write(data)
                sys.stdout.buffer.write(port.read())
                sys.stdout.buffer.flush()
        return
    broker = Broker()
    try:
        if args.command == "diagnostics":
            print(json.dumps(broker.call("diagnostics"), indent=2)); return
        if args.command == "configuration":
            print(json.dumps(broker.call("configuration", device=args.device, configuration=args.value), indent=2)); return
        if args.command == "list":
            print(json.dumps(broker.call("list"), ensure_ascii=True, indent=2))
            return
        if args.command == "iphone":
            broker.socket.settimeout(130 if args.action in ("ls", "get", "put") else 30)
            if args.name is not None and args.action != "put":
                parser.error("--name is only supported for put")
            if args.action == "ls" and len(args.paths) <= 1:
                result = broker.call("iphone", action="afc-list", device=args.device, path=args.paths[0] if args.paths else "/")
            elif args.action == "get" and len(args.paths) == 2:
                result = download(broker, args.device, *args.paths)
            elif args.action == "put" and len(args.paths) == 1:
                result = upload(broker, args.device, args.paths[0], args.name)
            elif args.action in ("pair", "info", "unpair") and not args.paths:
                result = broker.call("iphone", action=args.action, device=args.device)
            else:
                parser.error("Expected: ls [AFC_PATH], get AFC_PATH LOCAL_FILE, put LOCAL_FILE, or pair/info/unpair")
            print(json.dumps(result, ensure_ascii=True, indent=2))
            return
        if args.command == "raw":
            broker.call("open", device=args.device, interface=args.interface, serial=False)
            # One socket keeps ownership across operations; EOF releases the interface.
            while True:
                line = sys.stdin.buffer.readline(MAX_FRAME + 1)
                if not line:
                    break
                if len(line) > MAX_FRAME:
                    raise ValueError("Input line is too large")
                request = json.loads(line)
                op = request.pop("op")
                if op not in ("control", "transfer"):
                    raise ValueError("raw accepts only control and transfer operations")
                request.pop("token", None)
                request.pop("version", None)
                print(json.dumps(broker.call(op, **request)), flush=True)
            return
    finally:
        broker.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
