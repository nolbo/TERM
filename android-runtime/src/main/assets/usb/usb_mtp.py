"""Bounded standard PTP/MTP file operations over an explicitly approved raw interface.

No device-specific extensions, deletion, recursive copy or automatic protocol recovery.
"""
import io
import os
import secrets
import stat
import struct
import tempfile
import time
import uuid

LIMIT = 64 * 1024 * 1024
CHUNK = 16384


def u32_array(data):
    if len(data) < 4:
        raise RuntimeError("Truncated MTP array")
    count = struct.unpack_from("<I", data)[0]
    if count > 4096 or len(data) != 4 + count * 4:
        raise RuntimeError("Invalid or oversized MTP array")
    return list(struct.unpack_from("<" + "I" * count, data, 4))


def object_info(data):
    if len(data) < 53:
        raise RuntimeError("Truncated MTP object information")
    size = struct.unpack_from("<I", data, 8)[0]
    count = data[52]
    end = 53 + count * 2
    if end > len(data) or (count and data[end - 2:end] != b"\0\0"):
        raise RuntimeError("Invalid MTP filename")
    name = data[53:end - 2].decode("utf-16-le") if count else ""
    return {"name": name, "size": size, "directory": struct.unpack_from("<H", data, 4)[0] == 0x3001,
            "storage": struct.unpack_from("<I", data)[0], "parent": struct.unpack_from("<I", data, 38)[0]}


class MtpClient:
    def __init__(self, raw, input_ep, output_ep, packet_size):
        self.raw, self.input_ep, self.output_ep, self.packet_size = raw, input_ep, output_ep, packet_size
        self.transaction = 0
        self.buffer = bytearray()
        self.deadline = 0
        self.healthy = True

    def _read(self, size):
        while len(self.buffer) < size:
            if time.monotonic() > self.deadline:
                raise RuntimeError("MTP transaction timed out; reconnect explicitly")
            data = self.raw.poll(self.input_ep, CHUNK, timeout=500)
            if data:
                self.buffer.extend(data)
        result = bytes(self.buffer[:size])
        del self.buffer[:size]
        return result

    def _send(self, data):
        if self.raw.write(self.output_ep, data, timeout=1000) != len(data):
            raise RuntimeError("Incomplete MTP write; delivery uncertain")

    def _outgoing(self, code, source, length):
        header = struct.pack("<IHHI", length + 12, 2, code, self.transaction)
        first = source.read(min(length, CHUNK - 12))
        self._send(header + first)
        offset = len(first)
        while offset < length:
            if time.monotonic() > self.deadline:
                raise RuntimeError("MTP upload timed out")
            chunk = source.read(min(CHUNK, length - offset))
            if not chunk:
                raise RuntimeError("MTP upload source shrank")
            self._send(chunk)
            offset += len(chunk)
        if (length + 12) % self.packet_size == 0:
            self._send(b"")

    def operation(self, code, params=(), source=None, length=0, sink=None, limit=65536, timeout=120):
        if not self.healthy:
            raise RuntimeError("MTP session lost")
        self.deadline = time.monotonic() + timeout
        data = bytearray()
        total = 0
        try:
            self._send(struct.pack("<IHHI", 12 + 4 * len(params), 1, code, self.transaction) + struct.pack("<" + "I" * len(params), *params))
            if source is not None:
                self._outgoing(code, source, length)
            received_data = False
            while True:
                size, kind, reply_code, tx = struct.unpack("<IHHI", self._read(12))
                if size < 12 or tx != self.transaction:
                    raise RuntimeError("Invalid MTP transaction header")
                payload = size - 12
                if kind == 3:
                    if payload > 20 or payload % 4:
                        raise RuntimeError("Invalid MTP response")
                    response = self._read(payload)
                    if reply_code != 0x2001:
                        raise RuntimeError(f"MTP response 0x{reply_code:04x}")
                    self.transaction += 1
                    return bytes(data), list(struct.unpack("<" + "I" * (payload // 4), response)), total
                if kind != 2 or source is not None or received_data or reply_code != code or payload > limit:
                    raise RuntimeError("Unexpected or oversized MTP data container")
                received_data = True
                while total < payload:
                    chunk = self._read(min(CHUNK, payload - total))
                    total += len(chunk)
                    if sink is None:
                        data.extend(chunk)
                    else:
                        sink.write(chunk)
        except BaseException:
            self.healthy = False
            raise

    def open(self):
        self.operation(0x1002, (secrets.randbelow(0x7ffffffe) + 1,))

    def close(self):
        if self.healthy:
            try:
                self.operation(0x1003, timeout=3)
            except RuntimeError:
                pass

    def info(self, handle):
        return object_info(self.operation(0x1008, (handle,))[0])

    def list(self, storage, parent=0):
        handles = u32_array(self.operation(0x1007, (storage, 0, parent or 0xffffffff))[0])
        return {"entries": [dict(self.info(h), handle=h) for h in handles[:256]], "truncated": len(handles) > 256}

    def get(self, handle, destination):
        info = self.info(handle)
        if info["directory"] or info["size"] > LIMIT or os.path.lexists(destination):
            raise ValueError("Choose a regular file <=64 MiB and a new local destination")
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=os.path.dirname(os.path.abspath(destination)), prefix=".terminal-mtp-", delete=False) as output:
                temporary = output.name
                count = self.operation(0x1009, (handle,), sink=output, limit=LIMIT)[2]
                if count != info["size"]:
                    raise RuntimeError("MTP download size mismatch")
                output.flush(); os.fsync(output.fileno())
            os.link(temporary, destination)
            return {"bytes": count, "completed": True}
        finally:
            if temporary:
                os.unlink(temporary)

    def put(self, storage, parent, path):
        name = uuid.uuid4().hex + "-" + os.path.basename(path)
        encoded = name.encode("utf-16-le")
        if len(encoded) > 400 or any(ord(c) < 32 or c in "/\\" for c in name):
            raise ValueError("Invalid MTP upload filename")
        with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), "rb") as source:
            before = os.fstat(source.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_size > LIMIT:
                raise ValueError("MTP upload requires a regular file <=64 MiB")
            info = bytearray(52)
            struct.pack_into("<I", info, 0, storage)
            struct.pack_into("<H", info, 4, 0x3000)
            struct.pack_into("<I", info, 8, before.st_size)
            struct.pack_into("<I", info, 38, parent)
            info.extend(bytes([len(encoded) // 2 + 1]) + encoded + b"\0\0" + b"\0\0\0")
            response = self.operation(0x100c, (storage, parent), source=io.BytesIO(info), length=len(info))[1]
            if len(response) != 3:
                raise RuntimeError("MTP did not return an upload handle")
            print(f"New MTP object {response[2]}: {name!r}; interruption may leave a partial object", file=__import__("sys").stderr)
            self.operation(0x100d, source=source, length=before.st_size)
            after = os.fstat(source.fileno())
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise RuntimeError("Upload source changed; remote object may be inconsistent")
            return {"handle": response[2], "name": name, "bytes": before.st_size, "completed": True}


def run_mtp(Broker, RawInterface, args):
    # Reject incomplete commands before opening a device session.
    if args.action in ("ls", "put") and args.storage is None:
        raise ValueError("--storage is required")
    if args.action in ("get", "put") and args.file is None:
        raise ValueError("--file is required")
    if args.action == "get" and args.handle is None:
        raise ValueError("--handle is required")
    for value in (args.storage, args.parent, args.handle):
        if value is not None and (type(value) is not int or not 0 <= value <= 0xffffffff):
            raise ValueError("MTP IDs must be unsigned 32-bit integers")
    with Broker() as broker:
        device = next((d for d in broker.call("list")["devices"] if d["id"] == args.device), None)
    matches = [(c, i) for c in (device or {}).get("configurations", []) for i in c["interfaces"]
               if (i["class"], i["subclass"], i["protocol"], i["alternate"]) == (6, 1, 1, 0)]
    if len(matches) != 1:
        raise RuntimeError("Select MTP/PTP mode on the device; one standard still-image interface is required")
    c, i = matches[0]
    ins = [e for e in i["endpoints"] if e["type"] == 2 and e["address"] & 128]
    outs = [e for e in i["endpoints"] if e["type"] == 2 and not e["address"] & 128]
    if len(ins) != 1 or len(outs) != 1 or outs[0]["packetSize"] <= 0:
        raise RuntimeError("Invalid MTP endpoints")
    with RawInterface(args.device, i["id"], c["value"]) as raw:
        client = MtpClient(raw, ins[0]["address"], outs[0]["address"], outs[0]["packetSize"])
        client.open()
        try:
            if args.action == "stores":
                return {"storages": u32_array(client.operation(0x1004)[0])}
            if args.action == "ls":
                if args.storage is None: raise ValueError("--storage is required")
                return client.list(args.storage, args.parent)
            if args.action == "get":
                if args.handle is None or args.file is None: raise ValueError("--handle and --file are required")
                return client.get(args.handle, args.file)
            if args.storage is None or args.file is None: raise ValueError("--storage and --file are required")
            return client.put(args.storage, args.parent, args.file)
        finally:
            client.close()
