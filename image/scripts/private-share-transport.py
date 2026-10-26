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


def exact(stream, size, mutable=False):
    result = bytearray(size)
    position = 0
    while position < size:
        count = stream.recv_into(memoryview(result)[position:])
        if not count:
            raise EOFError('replay socket disconnected')
        position += count
    return result if mutable else bytes(result)


class Ring:
    """Lazily grown, bounded ring: prefix consumption never shifts remaining data."""
    def __init__(self, capacity):
        self.capacity = capacity
        self.data = bytearray()
        self.start = 0
        self.size = 0

    def __len__(self):
        return self.size

    def read(self, offset, count):
        if not 0 <= offset <= self.size or not 0 <= count <= self.size - offset:
            raise ValueError('invalid ring range')
        if not count:
            return b''
        start = (self.start + offset) % len(self.data)
        first = min(count, len(self.data) - start)
        result = bytearray(count)
        result[:first] = memoryview(self.data)[start:start + first]
        if first < count:
            result[first:] = memoryview(self.data)[:count - first]
        return result

    def append(self, source):
        count = len(source)
        if count > self.capacity - self.size:
            raise ValueError('replay credit exceeded')
        if not count:
            return
        if self.size + count > len(self.data):
            old = self.read(0, self.size)
            self.data = bytearray(min(self.capacity, max(4096, len(self.data) * 2, self.size + count)))
            self.data[:self.size] = old
            self.start = 0
        start = (self.start + self.size) % len(self.data)
        first = min(count, len(self.data) - start)
        view = memoryview(source)
        self.data[start:start + first] = view[:first]
        if first < count:
            self.data[:count - first] = view[first:]
        self.size += count

    def discard(self, count):
        if not 0 <= count <= self.size:
            raise ValueError('invalid replay acknowledgement')
        if count:
            self.start = (self.start + count) % len(self.data)
            self.size -= count


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
        self.incoming = Ring(capacity)
        self.consumed = 0
        self.received = 0
        self.outgoing = Ring(capacity)
        self.acknowledged = 0
        self.produced = 0
        self.connection = None
        self.connecting = None
        self.application = None
        self.closed = False

    def read(self, size):
        with self.condition:
            while not self.incoming and not self.closed:
                self.condition.wait()
            if self.closed:
                return b''
            result = self.incoming.read(0, min(size, len(self.incoming)))
            self.incoming.discard(len(result))
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
                self.outgoing.append(memoryview(data)[position:position + count])
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
                    self.outgoing.discard(consumed - self.acknowledged)
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
                data = exact(connection['socket'], length, mutable=True)
                with self.condition:
                    if self.closed or self.connection is not connection:
                        return
                    if kind == 2:
                        if length or not self.acknowledged <= position <= connection['cursor']:
                            raise ValueError('invalid replay acknowledgement')
                        self.outgoing.discard(position - self.acknowledged)
                        self.acknowledged = connection['consumed'] = position
                    elif kind == 1:
                        if not length or position > self.received:
                            raise ValueError('replay output gap')
                        skip = min(length, self.received - position)
                        if len(self.incoming) + length - skip > self.capacity:
                            raise ValueError('replay credit exceeded')
                        self.incoming.append(memoryview(data)[skip:])
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
                            data = self.outgoing.read(start, min(CHUNK, available))
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
            application, self.application = self.application, None
            self.condition.notify_all()
        # Either pump may be blocked in native I/O. Closing only the replay link
        # leaves the sessiond/SSHFS socket and its old owner alive indefinitely.
        if application is not None:
            shutdown(application)
        if connecting:
            shutdown(connecting)
        if active:
            self.detach(active)

    def bridge(self, application):
        """Keep the SSHFS socket intact while the external vsock is replaced."""
        with self.condition:
            if self.closed:
                shutdown(application)
                raise EOFError('replay owner closed')
            if self.application is not None:
                shutdown(application)
                raise ValueError('replay application already connected')
            self.application = application

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
                self.close()

        try:
            for method in (pump_from_application, pump_to_application):
                threading.Thread(target=method, daemon=True).start()
        except Exception:
            self.close()
            raise
