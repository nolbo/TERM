#!/usr/bin/env python3
"""Guest CLI for the allow-listed Android API bridge."""

import json
import os
import socket
import struct
import sys
import uuid


API_SOCKET = os.environ.get("XTERMX_ANDROID_API_SOCKET", "/run/xtermx-agent/api.sock")
MAX_MESSAGE = 1024 * 1024


def recv_exact(stream, size):
    value = bytearray()
    while len(value) < size:
        chunk = stream.recv(size - len(value))
        if not chunk:
            raise RuntimeError("Android API bridge closed the connection")
        value.extend(chunk)
    return bytes(value)


def call(request):
    request["id"] = str(uuid.uuid4())
    payload = json.dumps(request).encode("utf-8")
    if len(payload) > MAX_MESSAGE:
        raise RuntimeError("Android API request is too large")
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as stream:
        stream.connect(API_SOCKET)
        stream.sendall(struct.pack("!I", len(payload)) + payload)
        length = struct.unpack("!I", recv_exact(stream, 4))[0]
        if length > MAX_MESSAGE:
            raise RuntimeError("Android API response is too large")
        response = json.loads(recv_exact(stream, length).decode("utf-8"))
    if not response.get("ok"):
        raise RuntimeError(response.get("error", "Android API request failed"))
    return response


def main():
    program = os.path.basename(sys.argv[0])
    if program == "termux-clipboard-get":
        print(call({"operation": "clipboard_get"}).get("value", ""), end="")
    elif program == "termux-clipboard-set":
        call({"operation": "clipboard_set", "value": sys.stdin.read()})
    elif program == "termux-open-url":
        if len(sys.argv) != 2:
            raise RuntimeError("usage: termux-open-url URL")
        call({"operation": "open_url", "url": sys.argv[1]})
    elif program == "termux-share":
        value = " ".join(sys.argv[1:]) if len(sys.argv) > 1 else sys.stdin.read()
        call({"operation": "share_text", "value": value})
    else:
        if len(sys.argv) < 2:
            raise RuntimeError("usage: xtermx-api OPERATION [JSON_OBJECT]")
        request = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
        request["operation"] = sys.argv[1]
        print(json.dumps(call(request), ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"{os.path.basename(sys.argv[0])}: {error}", file=sys.stderr)
        sys.exit(1)
