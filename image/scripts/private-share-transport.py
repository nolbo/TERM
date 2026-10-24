#!/usr/bin/python3
"""TRS2: bounded, consumption-credited byte replay for one authenticated boot owner."""
import socket
import struct
import threading
import time

WINDOW = 256 * 1024
CHUNK = 32 * 1024
MAX_OFFSET = (1 << 63) - 1
HANDSHAKE = struct.Struct('!4sIQQ')
FRAME = struct.Struct('!BQI')


def exact(stream, size):
    result = bytearray()
    while len(result) < size:
        part = stream.recv(size - len(result))
        if not part:
            raise EOFError('replay socket disconnected')
        result.extend(part)
    return bytes(result)


def shutdown(stream):
    try:
        stream.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass
    stream.close()


class ReplayStream:
    def __init__(self, capacity=WINDOW):
        if not 1 <= capacity <= WINDOW:
            raise ValueError('invalid replay window')
        self.capacity = capacity
        self.condition = threading.Condition()
        self.attach_lock = threading.Lock()
        self.incoming = bytearray()
        self.consumed = 0
        self.received = 0
        self.outgoing = bytearray()
        self.acknowledged = 0
        self.produced = 0
        self.connection = None
        self.connecting = None
        self.closed = False

    def read(self, size):
        with self.condition:
            while not self.incoming and not self.closed:
                self.condition.wait()
            if self.closed:
                return b''
            result = bytes(self.incoming[:size])
            del self.incoming[:len(result)]
            self.consumed += len(result)
            self.condition.notify_all()
            return result

    def write(self, data):
        position = 0
        with self.condition:
            while position < len(data):
                while len(self.outgoing) == self.capacity and not self.closed:
                    self.condition.wait()
                if self.closed:
                    raise EOFError('replay owner closed')
                count = min(len(data) - position, self.capacity - len(self.outgoing))
                if self.produced + count > MAX_OFFSET:
                    raise ValueError('replay position exhausted')
                self.outgoing.extend(data[position:position + count])
                self.produced += count
                position += count
                self.condition.notify_all()

    def attach(self, stream):
        with self.attach_lock:
            with self.condition:
                if self.closed:
                    shutdown(stream)
                    raise EOFError('replay owner closed')
                previous = self.connection
                self.connecting = stream
            if previous:
                self.detach(previous)
            try:
                stream.settimeout(20)
                with self.condition:
                    state = HANDSHAKE.pack(b'TRS2', self.capacity, self.received, self.consumed)
                stream.sendall(state)
                magic, window, received, consumed = HANDSHAKE.unpack(exact(stream, HANDSHAKE.size))
                with self.condition:
                    if (self.closed or magic != b'TRS2' or not 1 <= window <= WINDOW
                            or not self.acknowledged <= consumed <= received <= self.produced):
                        raise ValueError('invalid replay generation')
                    del self.outgoing[:consumed - self.acknowledged]
                    self.acknowledged = consumed
                    connection = {'socket': stream, 'cursor': received, 'consumed': consumed,
                                  'window': window, 'closed': threading.Event()}
                    self.connection = connection
                    self.connecting = None
                for method in (self.receive, self.send):
                    threading.Thread(target=method, args=(connection,), daemon=True).start()
                return connection['closed']
            except Exception:
                with self.condition:
                    self.connecting = None
                shutdown(stream)
                raise

    def receive(self, connection):
        try:
            while True:
                kind, position, length = FRAME.unpack(exact(connection['socket'], FRAME.size))
                if position > MAX_OFFSET or length > CHUNK or position + length > MAX_OFFSET:
                    raise ValueError('invalid replay frame bounds')
                data = exact(connection['socket'], length)
                with self.condition:
                    if self.closed or self.connection is not connection:
                        return
                    if kind == 2:
                        if length or not self.acknowledged <= position <= connection['cursor']:
                            raise ValueError('invalid replay acknowledgement')
                        del self.outgoing[:position - self.acknowledged]
                        self.acknowledged = connection['consumed'] = position
                    elif kind == 1:
                        if not length or position > self.received:
                            raise ValueError('replay output gap')
                        skip = min(length, self.received - position)
                        if len(self.incoming) + length - skip > self.capacity:
                            raise ValueError('replay credit exceeded')
                        self.incoming.extend(data[skip:])
                        self.received += length - skip
                    elif kind == 3:
                        if length or position:
                            raise ValueError('invalid replay heartbeat')
                    else:
                        raise ValueError('unknown replay frame')
                    self.condition.notify_all()
        except (OSError, EOFError, ValueError):
            pass
        finally:
            self.detach(connection)

    def send(self, connection):
        acknowledged = -1
        last_write = time.monotonic()
        try:
            while True:
                with self.condition:
                    while True:
                        if self.closed or self.connection is not connection:
                            return
                        if self.consumed > acknowledged:
                            acknowledged = self.consumed
                            kind, position, data = 2, acknowledged, b''
                            break
                        available = min(self.produced - connection['cursor'],
                                        connection['window'] - (connection['cursor'] - connection['consumed']))
                        if available > 0:
                            kind, position = 1, connection['cursor']
                            start = position - self.acknowledged
                            data = bytes(self.outgoing[start:start + min(CHUNK, available)])
                            connection['cursor'] += len(data)
                            break
                        remaining = 5 - (time.monotonic() - last_write)
                        if remaining <= 0:
                            kind, position, data = 3, 0, b''
                            break
                        self.condition.wait(remaining)
                connection['socket'].sendall(FRAME.pack(kind, position, len(data)) + data)
                last_write = time.monotonic()
        except (OSError, EOFError, ValueError):
            pass
        finally:
            self.detach(connection)

    def detach(self, connection):
        with self.condition:
            if connection['closed'].is_set():
                return
            connection['closed'].set()
            if self.connection is connection:
                self.connection = None
            self.condition.notify_all()
        shutdown(connection['socket'])

    def close(self):
        with self.condition:
            if self.closed:
                return
            self.closed = True
            active, connecting = self.connection, self.connecting
            self.condition.notify_all()
        if connecting:
            shutdown(connecting)
        if active:
            self.detach(active)

    def bridge(self, application):
        """Keep the SSHFS socket intact while the external vsock is replaced."""
        def pump_from_application():
            try:
                while True:
                    data = application.recv(CHUNK)
                    if not data:
                        break
                    self.write(data)
            except (OSError, EOFError):
                pass
            finally:
                self.close()

        def pump_to_application():
            try:
                while True:
                    data = self.read(CHUNK)
                    if not data:
                        break
                    application.sendall(data)
            except OSError:
                pass
            finally:
                shutdown(application)
                self.close()

        for method in (pump_from_application, pump_to_application):
            threading.Thread(target=method, daemon=True).start()
