"""Bounded files in a host-selected removable-volume tree; no raw paths or mounts."""
import json
import os
import re
import stat
import sys
import tempfile

LIMIT = 64 * 1024 * 1024
CHUNK = 16384


def download(broker, handle, destination):
    if os.path.lexists(destination):
        raise ValueError("Destination exists; choose a new filename")
    start = broker.call("get", handle=handle)
    size = start.get("size")
    if start.get("event") != "start" or type(size) is not int or not 0 <= size <= LIMIT:
        raise RuntimeError("Invalid download header")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=os.path.dirname(os.path.abspath(destination)), prefix=".terminal-storage-", delete=False) as output:
            temporary = output.name
            offset = 0
            while offset < size:
                event = broker.receive()
                text = event.get("data", "")
                if event.get("event") != "data" or event.get("offset") != offset or not isinstance(text, str) or not re.fullmatch(r"(?:[0-9a-f]{2}){1,16384}", text):
                    raise RuntimeError("Invalid download chunk")
                data = bytes.fromhex(text)
                if offset + len(data) > size:
                    raise RuntimeError("Download exceeds declared size")
                output.write(data); offset += len(data)
            final = broker.receive()
            if final.get("completed") is not True or final.get("bytes") != size:
                raise RuntimeError("Incomplete download")
            output.flush(); os.fsync(output.fileno())
        os.link(temporary, destination)  # Atomic publication without overwrite, including symlinks.
        return {"completed": True, "bytes": size, "local": destination}
    finally:
        if temporary is not None:
            os.unlink(temporary)


def upload(broker, parent, source, name=None):
    name = name or os.path.basename(source)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", name):
        raise ValueError("Use an ASCII --name: letters, digits, dot, underscore, hyphen; <=100 characters")
    fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as data:
        before = os.fstat(data.fileno())
        size = before.st_size
        if not stat.S_ISREG(before.st_mode) or not 0 <= size <= LIMIT:
            raise ValueError("Upload requires a regular file <=64 MiB")
        ready = broker.call("put", parent=parent, name=name, size=size)
        if ready.get("event") != "ready" or ready.get("size") != size or not isinstance(ready.get("id"), str):
            raise RuntimeError("Invalid upload header")
        print("New remote file: " + json.dumps(ready, ensure_ascii=True) + "; interruption may leave a partial file.", file=sys.stderr)
        offset = 0
        while offset < size:
            chunk = data.read(min(CHUNK, size - offset))
            if not chunk:
                raise RuntimeError("Local file shrank during upload")
            accepted = broker.call("file-data", offset=offset, data=chunk.hex())
            offset += len(chunk)
            if accepted.get("event") != "accepted" or accepted.get("offset") != offset:
                raise RuntimeError("Upload stream rejected; do not replay")
        final = broker.receive()
        after = os.fstat(data.fileno())
        if after.st_size != before.st_size or after.st_mtime_ns != before.st_mtime_ns:
            raise RuntimeError("Local file changed; remote file may be inconsistent")
        if final.get("completed") is not True or final.get("bytes") != size or final.get("id") != ready["id"]:
            raise RuntimeError("Incomplete upload")
        return dict(final, name=ready.get("name"))


def run_storage(Broker, args):
    if args.action != "roots" and not args.root:
        raise ValueError("Specify a root ID from terminal-usb storage roots")
    if args.action in ("get", "put") and not args.file:
        raise ValueError("Specify --file")
    if args.action == "get" and not args.handle:
        raise ValueError("Specify --handle from a directory listing")
    with Broker() as broker:
        broker.socket.settimeout(130)
        if args.action == "roots":
            return broker.call("storage-roots")
        broker.call("files-open", root=args.root, mode="write" if args.action == "put" else "read")
        if args.action == "ls":
            return broker.call("ls", parent=args.parent)
        if args.action == "get":
            return download(broker, args.handle, args.file)
        return upload(broker, args.parent, args.file, args.name)
