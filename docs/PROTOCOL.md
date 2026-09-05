# Protocol

Protocol v1 uses a length-delimited binary frame over an abstract transport.
Production transport is vsock; tests may use an in-memory stream. The protocol
does not assume Binder RPC, gRPC, or JSON.

| Offset | Size | Field |
| --- | ---: | --- |
| 0 | 4 | magic `DPTY` |
| 4 | 2 | protocol version (`1`) |
| 6 | 1 | service: session, lifecycle, bridge |
| 7 | 1 | frame kind: request, response, stream, cancel, error |
| 8 | 2 | flags |
| 10 | 2 | reserved, zero |
| 12 | 8 | request ID |
| 20 | 4 | payload size |
| 24 | n | payload, maximum 1 MiB |

Every request has a request ID. Errors are typed as `UNSUPPORTED`,
`PERMISSION_DENIED`, `CAPABILITY_DENIED`, `NOT_FOUND`, `INVALID_ARGUMENT`,
`TIMEOUT`, `DISCONNECTED`, `GUEST_ERROR`, or `HOST_ERROR`.

The Kotlin codec in `protocol/` and the Rust codec in `guest/droid-sessiond/`
implement the same header.

## Session payloads

Android Binder clients select `NATIVE`, `ROOTLESS`, or `AVF` before session
creation. The selection is host-local metadata and is not injected into the
guest wire protocol. Once created, all runtimes use the same attach, detach,
resize, input, output and terminate operations. Session JSON includes the
runtime so the renderer does not infer backend behavior.

The Android AIDL listener carries state, revisioned session-list, PTY-output,
session-exit, termination-failure, and operation-error events. Session-list
callbacks include a monotonic service revision so a delayed Binder delivery
cannot replace newer UI state. Listener registration immediately returns the
current revision and list. Activity recreation treats that list as authoritative;
an empty list remains empty and never creates an implicit default shell.

Session requests begin with a one-byte operation code. Strings are UTF-8 with a
two-byte big-endian length (maximum 4096 bytes). Terminal input is explicitly
binary and uses a four-byte big-endian length.

| Code | Request | Payload |
| ---: | --- | --- |
| 1 | Create | shell, cwd, rows, cols, sorted environment pairs |
| 2 | List | none |
| 3 | Attach | session ID |
| 4 | Detach | session ID |
| 5 | Resize | session ID, rows, cols |
| 6 | Write | session ID, binary input |
| 7 | Terminate | session ID, POSIX signal |

The maximum environment count is 64. Zero-sized PTY dimensions, duplicate or
malformed environment keys, trailing bytes, and truncated payloads are rejected
before they reach a guest shell.

## Transport

The guest `droid-sessiond` binds AF_VSOCK port `40500`, accepts only the host
CID, and uses a bounded listen backlog with close-on-exec descriptors. It reads
exactly one 24-byte frame header followed by its validated payload; therefore a
partial socket read can never be interpreted as a request. Request dispatch
preserves the request ID and returns a typed response or error frame.

After a successful `Attach`, the connection receives `STREAM` frames with
request ID `0`. Event `0x90` is raw PTY output (`session ID`, byte length,
bytes); event `0x91` is process exit (`session ID`, exit code). Detaching or
disconnecting removes only this output subscription—the guest PTY remains alive.
# Protocol

## Bridge service

Service `BRIDGE` uses typed request/response payloads in `BridgePayloadCodec`.
Initial requests are `clipboard.get`, `clipboard.set`, `intent.openUrl`, and
`notification.post`. The guest must handle typed protocol errors, especially
`CAPABILITY_DENIED`, rather than treating an Android integration failure as a
shell failure.

Bridge traffic runs on a dedicated guest-agent transport; it is not mixed into
the sessiond PTY stream. Each request retains the common protocol version and
request ID framing.

The initial guest deployment is `droid` CLI → private `droid-agent` Unix socket
→ host-CID-only vsock port 40501 → `BridgeHostServer` → capability broker.

## Port service

`droid-port-agent` reports deduplicated TCP listener add/remove events over
host-CID-only vsock port 40502. The host validates these events before adding
them to `PortForwardRegistry`; reporting a port does not itself expose it to a
network interface.

## Lifecycle service

Guest agents may emit `WORK_ACTIVE` and `WORK_IDLE` stream events over their
authenticated bridge connection. These are hints only: the host applies the
persisted wake policy and owns all wake-lock acquisition/release decisions.
