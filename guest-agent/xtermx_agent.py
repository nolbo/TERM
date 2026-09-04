#!/usr/bin/env python3
"""Persistent AVF guest PTY and Android API bridge.

The Android app connects to this daemon over AF_VSOCK. A connection is only an attachment to a
session: closing it never kills the pty child. The protocol is intentionally small and versioned so
the Debian image can update independently from the host APK.
"""

import errno
import fcntl
import json
import os
import pty
import pwd
import signal
import socket
import struct
import termios
import threading
import time
import uuid


VERSION = 2
VSOCK_PORT = 5580
API_SOCKET = "/run/xtermx-agent/api.sock"
MAX_CONTROL_BYTES = 64 * 1024
MAX_API_BYTES = 1024 * 1024
MAX_FRAME_BYTES = 1024 * 1024
SCROLLBACK_BYTES = 1024 * 1024

FRAME_INPUT = 1
FRAME_OUTPUT = 2
FRAME_RESIZE = 3
FRAME_EXIT = 4
FRAME_DETACH = 5
FRAME_TERMINATE = 6
FRAME_PING = 7
FRAME_PONG = 8
FRAME_API_REQUEST = 20
FRAME_API_RESPONSE = 21


def recv_exact(stream, size):
    chunks = bytearray()
    while len(chunks) < size:
        chunk = stream.recv(size - len(chunks))
        if not chunk:
            raise EOFError("connection closed")
        chunks.extend(chunk)
    return bytes(chunks)


def recv_control(stream, max_bytes=MAX_CONTROL_BYTES):
    length = struct.unpack("!I", recv_exact(stream, 4))[0]
    if length > max_bytes:
        raise ValueError("control message is too large")
    return json.loads(recv_exact(stream, length).decode("utf-8"))


def send_control(stream, value, max_bytes=MAX_CONTROL_BYTES):
    payload = json.dumps(value, separators=(",", ":")).encode("utf-8")
    if len(payload) > max_bytes:
        raise ValueError("control message is too large")
    stream.sendall(struct.pack("!I", len(payload)) + payload)


def recv_frame(stream):
    frame_type, length = struct.unpack("!BI", recv_exact(stream, 5))
    if length > MAX_FRAME_BYTES:
        raise ValueError("frame is too large")
    return frame_type, recv_exact(stream, length)


def send_frame(stream, frame_type, payload=b""):
    if len(payload) > MAX_FRAME_BYTES:
        raise ValueError("frame is too large")
    stream.sendall(struct.pack("!BI", frame_type, len(payload)) + payload)


class Client:
    def __init__(self, stream):
        self.stream = stream
        self.session = None
        self.write_lock = threading.Lock()
        self.closed = False

    def send(self, frame_type, payload=b""):
        with self.write_lock:
            if self.closed:
                raise EOFError("client detached")
            send_frame(self.stream, frame_type, payload)

    def close(self):
        with self.write_lock:
            if self.closed:
                return
            self.closed = True
            try:
                self.stream.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self.stream.close()


class Session:
    def __init__(self, session_id, cwd, name, columns, rows, on_exit):
        self.session_id = session_id
        self.name = name or ""
        self.cwd = cwd if cwd and os.path.isdir(cwd) else "/home/droid"
        if not os.path.isdir(self.cwd):
            self.cwd = "/"
        self.columns = max(1, columns)
        self.rows = max(1, rows)
        self.clients = set()
        self.clients_lock = threading.Lock()
        self.scrollback = bytearray()
        self.output_offset = 0
        self.exited = False
        self.exit_code = 0
        self.on_exit = on_exit
        self.pid, self.master_fd = self._spawn_shell()
        self.resize(self.columns, self.rows)
        threading.Thread(target=self._read_pty, name=f"pty-{session_id}", daemon=True).start()

    def _spawn_shell(self):
        pid, master_fd = pty.fork()
        if pid == 0:
            shell = os.environ.get("SHELL", "/bin/bash")
            if not os.path.isfile(shell):
                shell = "/bin/sh"
            home = "/home/droid" if os.path.isdir("/home/droid") else self.cwd
            user = "droid"
            try:
                account = pwd.getpwnam("droid")
                home = account.pw_dir if os.path.isdir(account.pw_dir) else home
                os.initgroups(account.pw_name, account.pw_gid)
                os.setgid(account.pw_gid)
                os.setuid(account.pw_uid)
            except KeyError:
                # Custom development images may not have the AOSP Terminal image's droid user.
                user = "root"
            try:
                os.chdir(self.cwd)
            except OSError:
                os.chdir(home if os.path.isdir(home) else "/")
            environment = {
                "HOME": home,
                "USER": user,
                "LOGNAME": user,
                "SHELL": shell,
                "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM": "xterm-256color",
                "COLORTERM": "truecolor",
                "XTERMX_ANDROID_API_SOCKET": API_SOCKET,
            }
            for key in ("LANG", "LANGUAGE", "LC_ALL", "LC_CTYPE"):
                if key in os.environ:
                    environment[key] = os.environ[key]
            os.execvpe(shell, [shell, "--login"] if shell.endswith("bash") else [shell], environment)
        return pid, master_fd

    def _read_pty(self):
        try:
            while True:
                try:
                    data = os.read(self.master_fd, 64 * 1024)
                except OSError as error:
                    if error.errno == errno.EIO:
                        break
                    raise
                if not data:
                    break
                with self.clients_lock:
                    self.scrollback.extend(data)
                    self.output_offset += len(data)
                    if len(self.scrollback) > SCROLLBACK_BYTES:
                        del self.scrollback[: len(self.scrollback) - SCROLLBACK_BYTES]
                    clients = list(self.clients)
                self._send_to_clients(clients, FRAME_OUTPUT, data)
        finally:
            try:
                _, status = os.waitpid(self.pid, 0)
                self.exit_code = os.waitstatus_to_exitcode(status)
            except ChildProcessError:
                self.exit_code = -1
            self.exited = True
            self.broadcast(FRAME_EXIT, struct.pack("!i", self.exit_code))
            try:
                os.close(self.master_fd)
            except OSError:
                pass
            self.on_exit(self)

    def attach(self, client, requested_offset):
        client.session = self
        # Serialize the replay against live writes to this client. Output appended before the
        # client becomes visible is in the snapshot; later output waits behind the replay.
        with client.write_lock:
            with self.clients_lock:
                old_clients = list(self.clients)
                scrollback_offset = self.output_offset - len(self.scrollback)
                replay_offset = min(
                    max(int(requested_offset), scrollback_offset), self.output_offset
                )
                replay = bytes(self.scrollback[replay_offset - scrollback_offset:])
                self.clients.add(client)
                exited = self.exited
                exit_code = self.exit_code
            send_control(client.stream, {
                "ok": True,
                "pid": self.pid,
                "cwd": self.cwd,
                "output_offset": replay_offset,
            })
            if replay:
                send_frame(client.stream, FRAME_OUTPUT, replay)
            if exited:
                send_frame(client.stream, FRAME_EXIT, struct.pack("!i", exit_code))
        for old_client in old_clients:
            self.detach(old_client)
            old_client.close()

    def detach(self, client):
        with self.clients_lock:
            self.clients.discard(client)
        client.session = None

    def broadcast(self, frame_type, payload):
        with self.clients_lock:
            clients = list(self.clients)
        self._send_to_clients(clients, frame_type, payload)

    def _send_to_clients(self, clients, frame_type, payload):
        for client in clients:
            try:
                client.send(frame_type, payload)
            except (OSError, EOFError):
                self.detach(client)
                client.close()

    def write(self, payload):
        if not self.exited:
            os.write(self.master_fd, payload)

    def resize(self, columns, rows):
        self.columns = max(1, columns)
        self.rows = max(1, rows)
        packed = struct.pack("HHHH", self.rows, self.columns, 0, 0)
        fcntl.ioctl(self.master_fd, termios.TIOCSWINSZ, packed)

    def terminate(self):
        if self.exited:
            return
        try:
            os.killpg(self.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        threading.Thread(target=self._force_kill_later, daemon=True).start()

    def _force_kill_later(self):
        time.sleep(2)
        if not self.exited:
            try:
                os.killpg(self.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass


class ApiRequest:
    def __init__(self):
        self.event = threading.Event()
        self.response = None


class Agent:
    def __init__(self):
        self.sessions = {}
        self.sessions_lock = threading.Lock()
        self.clients = set()
        self.clients_lock = threading.Lock()
        self.api_requests = {}
        self.api_lock = threading.Lock()
        self.required_token = os.environ.get("XTERMX_AGENT_TOKEN", "")

    def serve(self):
        threading.Thread(target=self._serve_api_socket, name="android-api", daemon=True).start()
        listener = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((socket.VMADDR_CID_ANY, VSOCK_PORT))
        listener.listen(16)
        while True:
            stream, _ = listener.accept()
            threading.Thread(target=self._handle_host, args=(stream,), daemon=True).start()

    def _handle_host(self, stream):
        client = Client(stream)
        try:
            request = recv_control(stream)
            if request.get("version") != VERSION:
                send_control(stream, {"ok": False, "error": "unsupported protocol version"})
                return
            if self.required_token and request.get("token") != self.required_token:
                send_control(stream, {"ok": False, "error": "authentication failed"})
                return
            if request.get("operation") != "create_or_attach":
                send_control(stream, {"ok": False, "error": "unsupported operation"})
                return

            session_id = str(request["session_id"])
            with self.sessions_lock:
                session = self.sessions.get(session_id)
                if session is None:
                    session = Session(
                        session_id,
                        request.get("cwd"),
                        request.get("name"),
                        int(request.get("columns", 80)),
                        int(request.get("rows", 24)),
                        self._session_exited,
                    )
                    self.sessions[session_id] = session
            with self.clients_lock:
                self.clients.add(client)
            session.attach(client, request.get("output_offset", 0))

            while True:
                frame_type, payload = recv_frame(stream)
                if frame_type == FRAME_INPUT:
                    session.write(payload)
                elif frame_type == FRAME_RESIZE and len(payload) == 8:
                    columns, rows = struct.unpack("!II", payload)
                    session.resize(columns, rows)
                elif frame_type == FRAME_TERMINATE:
                    session.terminate()
                elif frame_type == FRAME_DETACH:
                    break
                elif frame_type == FRAME_PING:
                    client.send(FRAME_PONG)
                elif frame_type == FRAME_API_RESPONSE:
                    self._complete_api_request(payload)
        except (EOFError, OSError, ValueError, KeyError, json.JSONDecodeError):
            pass
        finally:
            if client.session is not None:
                client.session.detach(client)
            with self.clients_lock:
                self.clients.discard(client)
            client.close()

    def _session_exited(self, session):
        # Keep the exit record briefly so a client racing a reconnect receives the real status.
        def remove_later():
            time.sleep(30)
            with self.sessions_lock:
                if self.sessions.get(session.session_id) is session:
                    self.sessions.pop(session.session_id, None)
        threading.Thread(target=remove_later, daemon=True).start()

    def _serve_api_socket(self):
        os.makedirs(os.path.dirname(API_SOCKET), mode=0o755, exist_ok=True)
        try:
            os.unlink(API_SOCKET)
        except FileNotFoundError:
            pass
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(API_SOCKET)
        try:
            os.chown(API_SOCKET, -1, pwd.getpwnam("droid").pw_gid)
            os.chmod(API_SOCKET, 0o660)
        except KeyError:
            os.chmod(API_SOCKET, 0o600)
        listener.listen(8)
        while True:
            stream, _ = listener.accept()
            threading.Thread(target=self._handle_api_client, args=(stream,), daemon=True).start()

    def _handle_api_client(self, stream):
        try:
            request = recv_control(stream, MAX_API_BYTES)
            request_id = str(request.get("id") or uuid.uuid4())
            request["id"] = request_id
            pending = ApiRequest()
            with self.api_lock:
                self.api_requests[request_id] = pending
            client = self._any_host_client()
            if client is None:
                raise RuntimeError("no Android host is attached")
            client.send(FRAME_API_REQUEST, json.dumps(request).encode("utf-8"))
            if not pending.event.wait(30):
                raise TimeoutError("Android API request timed out")
            send_control(stream, pending.response, MAX_API_BYTES)
        except Exception as error:
            send_control(stream, {"ok": False, "error": str(error)}, MAX_API_BYTES)
        finally:
            try:
                request_id
            except UnboundLocalError:
                pass
            else:
                with self.api_lock:
                    self.api_requests.pop(request_id, None)
            stream.close()

    def _any_host_client(self):
        with self.clients_lock:
            return next((client for client in self.clients if not client.closed), None)

    def _complete_api_request(self, payload):
        try:
            response = json.loads(payload.decode("utf-8"))
            request_id = str(response["id"])
        except (ValueError, KeyError, UnicodeDecodeError):
            return
        with self.api_lock:
            pending = self.api_requests.get(request_id)
        if pending is not None:
            pending.response = response
            pending.event.set()


if __name__ == "__main__":
    Agent().serve()
