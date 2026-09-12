"""Local compatibility relays. No TCP listener, raw FD export or automatic write replay."""
import errno
import os
import pty
import select
import shlex
import shutil
import socket
import struct
import subprocess
import tempfile
import time
import tty
import ctypes
import signal
from contextlib import contextmanager


ADB_LIMIT = 1024 * 1024
ADB_COMMANDS = {int.from_bytes(word, "little") for word in (b"CNXN", b"AUTH", b"OPEN", b"OKAY", b"CLSE", b"WRTE", b"STLS")}


def stop_process(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=3)


def start_owned_process(arguments, **options):
    """Linux CLI-only spawn: do not leave a server after the bridge is SIGKILLed.

    This pre-exec hook is used only by the single-threaded bridge CLI. Load libc
    before fork; fail closed if the host/PRoot environment rejects PDEATHSIG.
    """
    parent = os.getpid()
    prctl = ctypes.CDLL(None, use_errno=True).prctl
    prctl.argtypes = [ctypes.c_int] + [ctypes.c_ulong] * 4
    prctl.restype = ctypes.c_int
    def bind_lifetime():
        if prctl(1, signal.SIGKILL, 0, 0, 0) != 0 or os.getppid() != parent:
            os._exit(70)
    return subprocess.Popen(arguments, preexec_fn=bind_lifetime, **options)


@contextmanager
def private_adb_server(binary, directory):
    address = "localfilesystem:" + directory + "/server"
    environment = dict(os.environ, ADB_SERVER_SOCKET=address, ADB_MDNS_AUTO_CONNECT="", ADB_LOCAL_TRANSPORT_MAX_PORT="0")
    # Own a foreground child, not a daemon whose cleanup depends on another RPC.
    process = start_owned_process([binary, "-L", address, "--one-device", "terminal-broker-no-direct-usb", "server", "nodaemon"],
                               env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.monotonic() + 10
        while not os.path.exists(directory + "/server"):
            if process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError("Private ADB server failed; adb must support -L localfilesystem and --one-device")
            time.sleep(0.05)
        os.chmod(directory + "/server", 0o600)
        yield address, environment
    finally:
        stop_process(process)


def adb_header(header):
    if len(header) != 24:
        raise RuntimeError("Incomplete ADB header")
    command, arg0, arg1, length, checksum, magic = struct.unpack("<6I", header)
    if command not in ADB_COMMANDS or magic != command ^ 0xffffffff or length > ADB_LIMIT:
        raise RuntimeError("Invalid or oversized ADB packet")
    return length


def write_adb_packet(raw, endpoint, packet_size, packet):
    length = adb_header(packet[:24])
    if len(packet) != 24 + length:
        raise RuntimeError("ADB packet length mismatch")
    if raw.write(endpoint, packet[:24]) != 24:
        raise RuntimeError("Incomplete ADB header write; connection cannot be resumed")
    data = packet[24:]
    for offset in range(0, length, 16384):
        chunk = data[offset:offset + 16384]
        if raw.write(endpoint, chunk) != len(chunk):
            raise RuntimeError("Incomplete ADB payload write; delivery uncertain")
    if length and length % packet_size == 0:
        if raw.write(endpoint, b"") != 0:
            raise RuntimeError("ADB terminating zero-length packet failed")


def relay_adb(raw, peer, input_ep, output_ep, packet_size):
    incoming = bytearray()
    receiving = bytearray()
    expected = 24
    partial_since = None
    peer.settimeout(5)
    while True:
        if select.select([peer], [], [], 0)[0]:
            chunk = peer.recv(min(16384, ADB_LIMIT + 24 - len(incoming)))
            if not chunk:
                return
            incoming.extend(chunk)
            partial_since = partial_since or time.monotonic()
            while len(incoming) >= 24:
                length = adb_header(incoming[:24])
                if len(incoming) < 24 + length:
                    break
                packet = bytes(incoming[:24 + length])
                del incoming[:24 + length]
                write_adb_packet(raw, output_ep, packet_size, packet)
                partial_since = time.monotonic() if incoming else None
        if partial_since and time.monotonic() - partial_since > 30:
            raise RuntimeError("ADB client left a partial packet")
        count = min(16384, expected - len(receiving))
        data = raw.poll(input_ep, count, timeout=100)
        if data is None or not data:  # pending request or a USB ZLP
            continue
        if len(data) > count:
            raise RuntimeError("ADB device exceeded the requested packet size")
        receiving.extend(data)
        if len(receiving) == 24 and expected == 24:
            expected = 24 + adb_header(receiving)
        if len(receiving) == expected:
            peer.sendall(receiving)
            receiving.clear()
            expected = 24


def adb_bridge(Broker, RawInterface, device):
    binary = shutil.which("adb")
    if binary is None:
        raise RuntimeError("Install adb using your Rootless distribution package manager")
    with Broker() as broker:
        target = next((d for d in broker.call("list")["devices"] if d["id"] == device), None)
    if not target:
        raise RuntimeError("USB device disconnected")
    interfaces = [(c, i) for c in target["configurations"] for i in c["interfaces"]
                  if (i["class"], i["subclass"], i["protocol"], i["alternate"]) == (255, 66, 1, 0)]
    if len(interfaces) != 1:
        raise RuntimeError("Expected one ADB interface; enable USB debugging on the target")
    config, interface = interfaces[0]
    ins = [e for e in interface["endpoints"] if e["type"] == 2 and e["address"] & 128]
    outs = [e for e in interface["endpoints"] if e["type"] == 2 and not e["address"] & 128]
    if len(ins) != 1 or len(outs) != 1 or outs[0]["packetSize"] <= 0:
        raise RuntimeError("Invalid ADB bulk endpoints")
    with RawInterface(device, interface["id"], config["value"]) as raw, tempfile.TemporaryDirectory(prefix="terminal-adb-") as directory:
        os.chmod(directory, 0o700)
        transport_path = directory + "/transport"
        connect = None
        with private_adb_server(binary, directory) as (server_address, environment), socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            listener.bind(transport_path)
            os.chmod(transport_path, 0o600)
            listener.listen(1)
            listener.settimeout(15)
            try:
                print("Use in another terminal in the same Rootless environment:", flush=True)
                print("ADB_SERVER_SOCKET=" + shlex.quote(server_address) + " adb devices", flush=True)
                print("Unlock the target and approve its USB debugging key. Ctrl-C closes only this private server.", flush=True)
                connect = start_owned_process([binary, "connect", "localfilesystem:" + transport_path], env=environment)
                peer, _ = listener.accept()
                listener.close()  # exactly one transport; never silently reconnect after detach
                with peer:
                    relay_adb(raw, peer, ins[0]["address"], outs[0]["address"], outs[0]["packetSize"])
            finally:
                stop_process(connect)


def serial_pty(SerialPort, device, port, baud, dtr, rts, bits=8, stops=0, parity=0):
    """PTY provides data compatibility only; hardware line settings are fixed at launch."""
    with SerialPort(device, port) as port:
        port.configure(baud=baud, dtr=dtr, rts=rts, bits=bits, stops=stops, parity=parity)
        master, slave = pty.openpty()
        try:
            tty.setraw(slave)
            os.chmod(os.ttyname(slave), 0o600)
            os.set_blocking(master, False)
            print(os.ttyname(slave), flush=True)
            print("PTY byte stream; configure hardware with launch options, not PTY ioctls. Ctrl-C disconnects.", file=__import__("sys").stderr)
            pending = bytearray()
            while True:
                readable, writable, _ = select.select([master], [master] if pending else [], [], 0)
                if readable:
                    try:
                        data = os.read(master, 4096)
                    except OSError as error:
                        if error.errno == errno.EIO:
                            return
                        raise
                    if not data:
                        return
                    port.write(data)
                if writable:
                    try:
                        del pending[:os.write(master, pending)]
                    except BlockingIOError:
                        pass
                if len(pending) < 65536:
                    pending.extend(port.read(min(4096, 65536 - len(pending)), timeout=100))
                else:
                    # Bound backpressure without losing input silently; retain grant liveness.
                    select.select([], [master], [], 0.5)
                    port.ping()
        finally:
            os.close(master); os.close(slave)
