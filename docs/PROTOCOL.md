# Protocol

## Session profile launch requests

The app-private Binder `createSessionFromSpec(launchSpecJson, rows, cols)` accepts
version 1 JSON with `profileId`, `name`, `runtime` (existing wire runtime name),
`shell`, `arguments` (string array), `cwd`, `environment` (string map), and
`startupCommand`. Settings are copied and validated before dispatch; payloads are
limited to 32 KiB, 64 environment entries and the existing 4096-byte/64-argument
argv envelope. `DROID_*`, `TERMINAL_USB_*` and `KITTY_WINDOW_ID` are reserved.
Rows/columns remain separate launch-time terminal state. The service snapshot
includes `profileId` and `launchSpec` for each new session, even after its profile
is edited or deleted. Missing metadata on legacy guest sessions does not imply
session loss. The old runtime-based Binder call adapts to a built-in profile.
An optional `palette` array carries exactly 19 `#RRGGBB` strings (foreground,
background, cursor, then ANSI 0–15); absent/null means global inheritance. Existing
version 1 snapshots remain valid. The array is validated and defensively copied.
The UI resolves saved profile palettes for live sessions; a deleted profile falls
back to this launch snapshot. It grants no runtime capability.
No guest protocol change is needed: launch arguments continue through the existing
`DROID_SESSION_ARGV` envelope, removed before constructing the child environment.

## Task notification signals

The app-private Binder setting `setSessionTaskNotifications(enabled)` persists an
opt-in policy in the service process; the default is false. Fresh PTY output accepts
`OSC 133;C ST` (command start), `OSC 133;D[;exitCode] ST` (completion), and
`OSC 133;A ST` (prompt return). OSC terminators may be BEL or ST, across chunk
boundaries. These boundaries follow the [iTerm2 shell integration protocol](https://iterm2.com/documentation-escape-codes.html).
A bare BEL, `OSC 9;message ST`, or `OSC 777;notify;title;body ST` requests attention.
OSC 9;4 progress updates, unrelated strings, replayed output, and canceled or
oversized frames never request a notification. String storage is bounded to 1024
bytes; embedded bells in unrelated OSC/DCS/APC payloads do not trigger requests.
Payload titles/bodies are intentionally not displayed or executed. The service uses
its own notification templates and only permits session navigation on tap.
No guest RPC or Android capability is granted by these sequences.

## Terminal paste

Paste is application input, not a shell command queue. With DECSET 2004 enabled,
the terminal sends `ESC [ 200 ~`, the complete sanitized clipboard text, and
`ESC [ 201 ~` in one transport write. LF, CRLF, tabs, blank lines and the presence
or absence of a final newline are preserved. With 2004 disabled, the legacy LF/CRLF
to CR (Enter) conversion remains; no lines are trimmed, split into separate writes,
or delayed for prompt notifications. This applies equally to main and alternate
screens. Existing ESC/C1 filtering prevents pasted escape characters from breaking
the framing. The active application controls execution and paste interpretation.

Explicit paste actions share `TerminalView.pasteText` / `pasteFromClipboard`,
which delegate to `TerminalEmulator.paste`. Shell PS1, PS2 and PROMPT_COMMAND are
not modified for paste. OSC 133 output and replay never schedule clipboard input.

## Shared registered iPhone tools

Current service revision is 3. Snapshots may contain `changes` with only fixed
effect keys/statuses described in IPHONE_TOOLING.md. No paths, records or credentials
are included. Both CLIs preserve this metadata in JSON. `USB_CONFIGURATION_REQUIRED`
means the iPhone worker refused to change the active configuration; use the existing
separately approved `configuration` request, also supported by the Native CLI.

Service revision 2 adds `serviceRevision: 2`, `requestAction` and a random
non-authorizing `requestId` to successful tooling results. `tooling list` also
provides `workerSha256`, keyed by APK executable name (no host paths).
Operation failures may include only a fixed allowlisted `detail` category.
Pairing phases/errors are documented in [IPHONE_TOOLING.md](IPHONE_TOOLING.md).
Request schemas and grants are unchanged; clients reject isideload status
received as a `check` reply rather than interpreting it as probe output.

Native's packaged client and Rootless's Python client use the existing same-UID,
session-token, version-1 length-prefixed USB protocol. A flat `op: "tooling"`
request accepts these exact additional fields: `list`: none; `check`: device;
`start`: tool/device; `status` and `cancel`: operation; `select`: operation/choices
(comma-separated non-secret choice IDs). Every request has version/token/op/action.
Only registered isideload can start; it needs a separate whole-device grant and
OS USB permission. Arbitrary argv, ports, paths, payloads and credentials are
not accepted. `watch` is client-side status polling, not a separate broker RPC.
Operation status is validated metadata, stable errors/progress and non-secret
choices; ownership is authenticated by the session as well as operation ID.
No public response contains account/password/2FA or a secure prompt nonce.

App-only typed Binder APIs getHostToolOperationsJson/submitHostToolInput/
cancelHostToolOperation serve the generic host input screen. Input is a bounded
String array, not diagnostic JSON; secret prompts need a one-use nonce and the
verified main UI process. Terminal select is rejected for login and 2FA.

Host-internal IPC is separate: same-UID + random stdin token, marker `B` with
USB+connector FDs to `iphone-worker mux-owner`, and marker `M` with one connector
FD to the trusted probe or isideload worker. Each private `M` request receives one fresh CLOEXEC
stream FD. No descriptor is returned on the guest socket. Old USB operations
remain compatible. See [IPHONE_TOOLING.md](IPHONE_TOOLING.md).

## Android storage size measurements

The app-local `IRuntimeService.measureStorageUsageJson(runtimeType, storageId)` returns
`{"bytes": <long>, "incomplete": <boolean>}`. `incomplete` marks a lower bound after
skipping access-denied descendants. Missing roots return zero; inaccessible roots and
other I/O failures raise a Binder-supported `IllegalStateException` rather than an
empty reply or default zero. The existing `measureStorage` returns the same
byte count for compatibility. Cached storage-list rows carry the same `incomplete`
flag alongside nullable `bytes`. Size reads never follow symbolic links or alter modes.

## Android runtime choice status

The app-local `IRuntimeService.runtimeStatusJson` is separate from the guest wire
protocol. Each runtime entry exposes `available` for session creation and may
expose `installable` for environments that can be prepared by installation.
Missing `installable` is false. Rootless backends report it only when the device
ABI and packaged PRoot executables permit installation. Session pickers display
entries with either flag; unavailable privileged AVF entries remain accessible
through settings but are omitted from session creation. Grouping distributions
under Rootless Linux is UI-only; requests retain their individual runtime IDs.

Rootless entries also expose `installed` independently of readiness, so incomplete
or failed environment setup remains removable. Older responses infer installation
from `READY` or `ENVIRONMENT_REQUIRED`; other missing values mean false.
`ENVIRONMENT_REQUIRED` means the base image exists but its versioned default
environment needs completion; `SETUP_REQUIRED` means bootstrap is needed.
Host-local Rootless entries may include a `progress` object. It contains stable
`phase` names, a display `label`, bounded `detail`, optional `fraction` (0–1,
current phase only), optional `completedBytes`/`totalBytes`, and monotonic-clock
`startedAtElapsedMs`, `updatedAtElapsedMs`, `finishedAtElapsedMs` timestamps.
Absent totals/fractions mean indeterminate work, not zero progress. Old responses
without `progress` remain supported. The runtime retains the latest snapshot per
distribution while alive; reconnecting UI reads this object instead of replacing
the current operation with generic checking text. Finished/failed attempts stop
the elapsed timer; a new attempt resets it. Process death does not restore a live
installation job from cached UI state.

`onRootlessProgress(runtimeType, phase, detail)` remains compatible and notifies
clients to reread that distribution's snapshot. Updates are throttled to roughly
four per second; phase transitions and measured completion are delivered promptly.
Stages cover executable/storage checks, archive download/checksum/extraction/
activation, repository refresh/recovery/planning, package download/install/upgrade/
verification/hooks, locale/timezone/home setup, then package/command/environment
verification. `READY` follows successful final verification; package-manager 100%
never means the whole installation is ready. These are host-local additions,
not guest protocol changes.

## Guest wire protocol

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

Android Binder clients select `NATIVE`, `ROOTLESS` (existing Debian identity),
`ROOTLESS_UBUNTU`, `ROOTLESS_ARCH`, or `AVF` before session
creation. The selection is host-local metadata and is not injected into the
guest wire protocol. Once created, all runtimes use the same attach, detach,
resize, input, output and terminate operations. Session JSON includes the
runtime so the renderer does not infer backend behavior. Ubuntu and Arch use
`ubuntu-` and `archlinux-` session ID prefixes; Debian retains `rootless-`.
These are distribution selections within the Rootless backend family, not new
execution mechanisms. `prepareRootless(runtimeType)` prepares only the selected
approved distribution. `removeRootless(runtimeType, removeHome)` removes the selected image
only after an authoritative empty-session check; session creation is serialized
with removal. `removeHome` is false by default in the UI and retains the home;
when true, it deletes only the selected distribution's home. Shared workspace
and other distributions remain. Completion refreshes runtime
state; failure reports `ROOTLESS_REMOVE:<runtimeType>` through `onOperationError`.
`onRootlessProgress(runtimeType, phase, detail)` scopes
progress to that selection; setup errors use `ROOTLESS_SETUP:<runtimeType>`.
For independent profile storage, the progress callback's first argument is
`<runtimeType>:<storageId>`; legacy storage retains the runtime-only value.
Session creation errors use `세션 생성:<runtimeType>:<storageId>`. The UI matches
storage as well as runtime so concurrent preparation cannot overwrite another
profile's progress or complete its pending installation. Preparation/removal
locks are scoped to runtime and storage; shared-tree migration retains an
exclusive barrier across all storage preparation.
The APK updates both sides of this app-private AIDL contract together.

The Android AIDL listener carries state, revisioned session-list, PTY-output,
session-exit, termination-failure, and operation-error events. Session-list
callbacks include a monotonic service revision so a delayed Binder delivery
cannot replace newer UI state. Listener registration immediately returns the
current revision and list. Activity recreation treats that list as authoritative;
an empty list remains empty and never creates an implicit default shell.

Native and Rootless attach snapshots are marked `replay` in host-local
`SessionEvent.Output` metadata and in the Android AIDL output callback. The
marker is retained through the backend and main-thread emulator delivery.
Legacy replay applies retained screen, title, palette and mode controls, but must not send
terminal query responses back to the live shell or repeat clipboard/bell
effects. The authoritative runtime emulator answers live queries once; UI mirrors
suppress parsed replies while retaining live clipboard/bell effects. This metadata is
not a guest protocol-v1 field; the wire codec rejects host replay events.

Native/Rootless legacy replay snapshots remain bracketed by empty `replay=true`
frames under the PTY output mutex. Application restoration uses a separate
host-private `ITerminalStateListener`: a complete checkpoint, then ordered output,
resize and exit callbacks. `attachTerminal` configures dimensions/palette and
subscribes atomically under the authoritative emulator lock. Terminal commands
and viewport updates use an ordered service queue. No guest wire field changes.

A checkpoint descriptor is the read-only end of an anonymous reliable pipe.
Serialization runs on a writer while the caller holds the state lock; no file
path is exposed or retained. EOF is checked before installing the graph. Its header
contains an optional viewport (row, fractional offset and
transcript-shift counter), followed by `TSC1` and a gzip-compressed same-build
terminal object graph. Only listed terminal/value/collection classes are allowed;
serialized proxies are rejected. Output/client/process bindings are excluded.
This format is neither persistent cross-version storage nor a guest input API.
The UI installs the state without replaying queries or side effects. Its bounded
byte queue processes subsequent mirror output at the exact checkpoint boundary.
Runtime PTY readers drain final bytes before publishing process exit.

The AVF guest streams every running PTY while the host is connected, independent
of selected renderer attachment. Protocol v1 frame types and session IDs remain
unchanged; deploying an older single-selected-PTY guest cannot provide the same
multi-session restoration guarantee.

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
bytes, with kitty local media converted to direct transfers inside the guest);
event `0x91` is process exit (`session ID`, exit code). Detaching or
disconnecting removes only this output subscription—the guest PTY remains alive.

Kitty media conversion does not change the session wire schema. File paths,
shared-memory handles and guest descriptors never become host file operations;
`droid-sessiond` reads bounded regular-file data in the guest and emits direct
APCs in output frames of at most 32 KiB. Native/Rootless perform the equivalent
conversion before their 8 MiB transcript/replay buffer. Runtime emulator replies
remain ordinary PTY input. UI mirrors suppress duplicate
query replies, and checkpoint installation never re-executes historical effects.
See [TERMINAL_GRAPHICS.md](TERMINAL_GRAPHICS.md) for protocol coverage and limits.

Session descriptor exit codes are valid only when state is `EXITED`. The
binary descriptor uses `-1` as the default for a session that has not exited;
an exited local session can also have `-1` for a real SIGHUP. Consumers must
check state rather than infer termination from this number. The Android AIDL
session-list JSON omits `exitCode` for `CREATED` and `RUNNING` sessions, and the
UI ignores that field for non-exited sessions even if an older producer sends
it. Explicit process-exit events still carry the actual exit code.
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

## Rootless USB service

Generic serial drivers use a separate flat `serial-open` request with `device`,
`driver`, zero-based `port`, `version:1` and `token`. Driver/port must match host
probing and a separate driver/port grant (device-wide for non-isolated layouts). The same authenticated socket then
accepts configure/read/write/status/ping/close only; no raw/vendor commands or
driver overrides. Configuration is required before I/O. All fields are strict,
and each operation is bounded by a watchdog. Write timeouts return uncertain
delivery metadata, never an implicit retry. See [USB.md](USB.md) for the wire
fields, response formats, timing and backward compatibility.

Raw control additionally permits the read-only HID report descriptor request
only for a HID-class interface and its exact granted index. No other standard
control requests are newly exposed.

`raw-open` adds exact `device`, `interface`, `configuration`, `alternate` fields.
Raw `open` retains its first-configuration/alternate-zero behavior. `configuration`
takes `device`, `configuration` and requires a separate idle-device approval; it
invalidates old attachment IDs/grants after the attempt. Raw `poll` takes IN
`endpoint`, `length`, `timeout` and returns `pending`, `count`, hex `data`.
Pending means the same request remains queued; subsequent polls must preserve
endpoint/length. `metadata`, `ping`, `close` take no additional fields.
`diagnostics` returns bounded connection/approval counts and symbolic errors, not
tokens, USB paths or transferred data.

`storage-roots` lists opaque removable-folder IDs. `files-open` takes `root` and
`mode:read|write`; a host-selected tree and matching session approval are mandatory.
That socket then accepts `ls(parent)`, `get(handle)`, `put(parent,name,size)`,
`file-data(offset,data)`, `ping`, `close`, always with the original token/version.
Parent `root` denotes the selected tree root. Other handles are host-issued and
tree-bound. Get emits `start(size)`, `data(offset,data)` and `completed/bytes`;
put emits `ready(id,name,size)`, `accepted(offset)` per chunk, then
`completed/bytes/id`. Completion follows close and size checks, not a snapshot
guarantee. Interrupted uploads may leave a partial new file. No guest URI, path,
provider selection, overwrite, delete or raw FD is accepted. See USB.md for limits.

The flat USB request `op:"iphone", action:"pair"|"info"|"unpair", device:ID`
requires the usual version/token plus a separately approved per-action grant.
For these three actions no other fields, service ports or pair-record requests are accepted. It returns
bounded operation status or five allowlisted information strings, not a mux tunnel.

AFC actions extend this same protocol: `afc-list`/`afc-get` require `path` and an
exact-path grant; `afc-put` requires ASCII `name` and integer `size` (0–64 MiB),
with a separate `/TerminalImports` grant and host-generated UUID destination.
Each server frame retains `{ok:true,result:...}`. Listing returns `entries` and
`truncated`. Downloads send `event:"start",size`, then `event:"data",offset,data`
(lowercase hex, at most 16 KiB decoded), then `bytes,completed:true`.
Uploads send `event:"ready",path,size`; the client sends authenticated flat
`op:"iphone-data",offset,data` frames on that same socket, with only version/token
and these fields. Each chunk gets `event:"accepted",offset` before the next chunk.
The final result is `bytes,completed:true`; accepted is not remote durability.
Offsets must be contiguous. Empty files have no chunks. The streaming opcode is
not accepted outside an active upload. Failures or EOF do not imply completion.
See USB.md for deadlines, path constraints and partial remote-file semantics.

USB uses a separate versioned, length-prefixed local protocol, not AVF BRIDGE
vsock or PTY escape sequences. See [USB.md](USB.md) for operations, bounds,
host-selected session credentials, approvals and disconnect semantics.

## Port service

`droid-port-agent` reports deduplicated TCP listener add/remove events over
host-CID-only vsock port 40502. The host validates these events before adding
them to `PortForwardRegistry`; reporting a port does not itself expose it to a
network interface.

## Lifecycle service

Guest agents may emit `WORK_ACTIVE` and `WORK_IDLE` stream events over their
authenticated bridge connection. These are hints only: the host applies the
persisted wake policy and owns all wake-lock acquisition/release decisions.

## Kitty terminal control compatibility

Live PTY output can carry bounded DCS `@kitty-cmd` JSON requests. The unified
registry frames them and the runtime service checks a host-owned source grant
and same-runtime targets before dispatch. Replies return through the source PTY
using the same DCS wrapper. Replayed output is never dispatched. The renderer
separately negotiates kitty keyboard flags and emits CSI-u input events.

Local AIDL adds `setKittyGraphicsCompatibility`, `setKittyRemoteControl` and
`onSessionFocusRequested`; focus does not own or terminate a PTY. A remote launch
uses the existing Session Create envelope with internal `DROID_SESSION_ARGV`:
`count:byteLength:UTF8Argument...` (1–64 arguments, at most 4096 bytes). Native,
Rootless and the updated AVF guest validate and consume this value before exec;
it is not placed in the process environment. Deploy the updated guest for AVF
argv launches. No shell interpolation is involved. Full scope and limits are in
[KITTY_PROTOCOL.md](KITTY_PROTOCOL.md).

Terminal presentation additionally supports DEC synchronized output mode 2026
(`CSI ? 2026 h/l`, DECRQM query). The model continues parsing while the renderer
holds its previous completed frame, with a non-extending one-second deadline.
Replay ignores presentation barriers; reset and resize release them. See
[terminal graphics](TERMINAL_GRAPHICS.md#synchronized-presentation-and-codex-pet-flicker).

## Toolbar memory snapshot (host UI only)

`IRuntimeService.getSessionMemoryJson()` is an app-private, read-only measurement RPC.
It verifies the calling UID and host UI process, as the host-tool input APIs do.
The JSON has `totalBytes`, `availableBytes` (Android ActivityManager memory info),
`sessions` (session ID to estimated RSS bytes, or null when unavailable), and
`runtimeAppMemory` (runtime service process memory, or null). The latter contains
`total` (PSS bytes), `java-heap`, `native-heap`, `code`, `stack`, `graphics`,
`private-other`, and `system` (Android Debug.MemoryInfo summary values converted
from KiB to bytes; unsupported areas are null). The UI measures its own process
and sums corresponding values into the local `appMemory` model. Both process
snapshots are required for an app total; session/guest RSS is not added to PSS.
No PID or host path is exposed by this extension.
Missing IDs, including AVF sessions, are unavailable rather than zero. The UI
calls off its main thread and only polls while the memory tool is visible, active,
and at least one device/app/session display section is enabled.

Native and Rootless PTY owners capture PID/start-time identities at creation. The
registry samples at most 8,192 readable proc entries and aggregates descendants
and processes still in that Linux session, checking the root start time against
PID reuse. RSS can double-count shared pages and omit detached/reparented daemons.
Process IDs, proc paths, and arbitrary query parameters are not exposed by this RPC.
This is independent of the guest wire protocol and grants no guest capability.
