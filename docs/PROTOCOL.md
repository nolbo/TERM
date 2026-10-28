# Protocol

## Session profile launch requests

Both launch JSON versions accept optional `tabTitle` (1–128 printable characters), independent of the profile `name`. Workspace configurations supply their own name here; absence preserves automatic tab naming for ordinary profile launches. The service publishes it as an explicit custom title from the first session snapshot and on AVF preparation/failure rows, with subsequent session renames taking precedence. Environment snapshot rebinding retains this field. It is host display metadata and changes no guest frames, capabilities, or launch arguments.

The app-private Binder `createSessionFromSpec(launchSpecJson, rows, cols)` accepts
version 1 JSON (legacy) and version 2 JSON with `profileId`, `name`, `runtime` (existing wire runtime name),
`shell`, `arguments` (string array), `cwd`, `environment` (string map), and
`startupCommand`. Settings are copied and validated before dispatch; payloads are
limited to 32 KiB for v1 without an Environment reference, or 34 KiB including Environment metadata/reference, and 64 environment entries and the existing 4096-byte/64-argument
argv envelope. `DROID_*`, `TERMINAL_USB_*` and `KITTY_WINDOW_ID` are reserved.
Rows/columns remain separate launch-time terminal state. The service snapshot
includes `profileId` and `launchSpec` for each new session, even after its profile
is edited or deleted. Missing metadata on legacy guest sessions does not imply
session loss. The old runtime-based Binder call adapts to a built-in profile.
Version 2 also carries `environmentId` and an immutable `executionEnvironment` object containing UUID/name, runtime kind, exact distribution/guest, storage identity, runtime-specific metadata and creation time. The top-level transitional runtime/storage fields must match that object. The service validates the Environment ID and canonical persistence handle against the registry and preserves the request snapshot instead of re-resolving through an edited profile. The small size allowance preserves otherwise-valid maximum-size v1 profiles during metadata-only migration and legacy storage adoption. Version 1 with only a transitional Environment reference remains readable by the updated host.

Environment management adds host-only `getEnvironmentsJson`, `getEnvironmentSoftwareJson`, `measureEnvironmentUsageJson`, `renameEnvironment`, `deleteEnvironment` and `operateEnvironment` Binder methods keyed by UUID. Operations include `START`, idle-only `STOP`, Rootless `INSTALL`/`REPAIR`, and `REMOVE_SOFTWARE` with Rootless-only `REMOVE_SOFTWARE_AND_HOME`; unsupported suspend/resume is rejected. Software status is scoped to the Environment and reports installation, preparation progress and guarded action availability independently of terminal creation. Removal keeps Environment/Profile references and requires idle/reachable session owners; AVF additionally requires a stopped-instance platform verification and no pending image work. Operations remain service-owned work across UI detach. Old Storage methods and Rootless preparation/removal calls remain compatibility/diagnostic APIs, without general-user instance actions in global engine settings. The Environment list includes references, runtime state and conservative deletion availability; unknown runtime state never authorizes deletion.

The appended host-only `createSessionFromSpecWithInstallationConsent(launchSpecJson, requestId, rows, cols)` requires the same UID and verified main UI process. It carries approval for that immutable Environment launch only, separate from profile JSON and guest data. Under the resolved storage lease, missing Rootless images require this explicit approval; ordinary session/START requests cannot bootstrap them. For AVF, this method may enqueue the approved pinned image after privileged access checks, then use the existing request UUID, service-owned preparation and loading-tab lifecycle. Ordinary AVF launches can only join already requested installation.

Userspace Linux appends host-only `getUserspaceLinuxSettingsJson()` and `setUserspaceLinuxDns(mode, servers)` Binder methods without changing existing transaction order or the guest protocol. Both require the same UID and verified main UI process. The getter reports `dnsMode` (`android` by default or `custom`), newline-separated `dnsServers`, and engine information (`arm64`, `prootAvailable`, `loaderAvailable`, `acceleration`, `faststat`, nullable `prootVersion`). The setter validates the mode and up to three numeric IPv4/IPv6 addresses, commits the single engine-wide policy in the runtime process and returns an empty string on success or an error on failure. Invalid input preserves the previous policy. No Environment/Profile IDs or engine implementation options are accepted.

An optional `palette` array carries exactly 19 `#RRGGBB` strings (foreground,
background, cursor, then ANSI 0–15); absent/null means global inheritance. Existing
version 1 snapshots remain valid. The array is validated and defensively copied.
The UI resolves saved profile palettes for live sessions; a deleted profile falls
back to this launch snapshot. It grants no runtime capability.
No guest protocol change is needed: launch arguments continue through the existing
`DROID_SESSION_ARGV` envelope, removed before constructing the child environment.

The appended app-private Binder method
`createSessionFromSpecWithRequestId(launchSpecJson, requestId, rows, cols)` uses
the same bounded launch JSON and a separate canonical UUID for AVF requests.
An accepted request appears in service session-list projections with ID
`avf-launch:<requestId>`, `pendingLaunch: true`, `launchRequestId`, profile
metadata, and state `PREPARING` or `LAUNCH_FAILED`. `launchError` is null while
preparing and contains the failure text otherwise. These rows cannot be attached,
resized, renamed, or written to, and are not live PTY descriptors. Successful
session rows carry the same `launchRequestId`; the pending row disappears in that
publication. `terminateSession` on a failed request ID dismisses its metadata
only; starting requests cannot be closed through the UI. Listener registration
replays outstanding service requests, and request-only publications increment
the service revision without querying a controller or starting a VM.
The original Binder method remains supported and allocates an AVF request UUID
in the service. No guest frame, profile version, or VM permission changes.

## Command history boundaries

The main terminal buffer consumes OSC 133 A (prompt), B (input), C (execution) and D (completion with optional exit code), terminated by BEL or ST. Boundaries in the alternate buffer are ignored. Logical cell anchors identify retained command input and output ranges, survive reflow and same-build terminal checkpoints, and are removed with trimmed history. Command text is derived from retained cells; no additional command-string history is serialized. Search is a case-insensitive literal query over the selected input/output range, with a 4,096-character display preview and a 256-character UI query limit.

The app-owned Android mksh prompt emits `B;droid=mksh` after PS1 and `B;droid=mksh-continuation` after PS2, inside nonprinting prompt delimiters. These explicit variants opt into echoed LF submission boundaries because mksh has no PS0 hook; continuation input extends the input range before output search. Ordinary B producers require C. Native D/A still report the exit status and next prompt. Bash adds A to the existing status-preserving D/PROMPT_COMMAND and C/PS0 hooks; user rc files may override integration. Without B, conventional A/C prompt delimiters provide a bounded display fallback. The toolbar refuses local history jumps while alternate screen, mouse tracking, application cursor mode or partial margins give the application its viewport. These display rules do not alter the independent notification parser or input transport.

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
The app-private `setSessionNotificationFocus(sessionId, listener)` reports the
selected session only while its terminal Activity is resumed and window-focused;
null clears focus. Only registered runtime listeners may report focus, and
unregister or Binder death removes their state. Completion messages for focused
sessions are suppressed without suppressing attention or
changing command/work tracking. This focus is separate from runtime attachment
and the last selected Kitty window.
Completion messages use the appended app-private listener callback
`onSessionTaskCompletionsChanged(completionsJson)`. Its array contains up to 16
unread `{id, sessionId, title, exitCode}` records; IDs are monotonic within the
runtime process, titles are sanitized and capped at 80 characters, and exit codes
may be null. Registration replays the current array. After the foreground UI has
displayed an item in the toolbar handle, `acknowledgeSessionTaskCompletion(id)`
removes only that item and republishes the array. Disabling clears the array.
Completion messages require no Android notification permission and never create
Android notifications. Attention requests retain the existing notification path
and rate limits; completions do not consume or share those limits.
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

## Terminal mouse wheel input

Horizontal wheel input reuses `TerminalEmulator.sendMouseEvent`: xterm button codes 66 (left) and 67 (right), with press reports only. With DECSET 1006 enabled, the emitted sequences are `ESC [ < 66 ; column ; row M` and `ESC [ < 67 ; column ; row M`; without 1006, the existing legacy mouse encoding applies. DECSET 1000, 1002 or 1003 must enable tracking; 1006 alone does not enable input. See the [xterm mouse encoding](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h2-Mouse-Tracking).

All four wheel directions (64 up, 65 down, 66 left, 67 right) share a modifier mask read from Hotkeys through `readShiftKey()`, `readAltKey()` and `readControlKey()` at each event dispatch. Shift adds 4, Alt adds 8 and Ctrl adds 16 to the base button code; combinations use the bitwise union (for example Shift+Up is 68, Shift+Down is 69, and Shift+Alt+Ctrl+Right is 95). The same button encoding applies to SGR 1006 and legacy reports, whether generated by a touch swipe/fling or a physical mouse/trackpad wheel. Wheel input never consumes latched modifiers or invokes keyboard consumption callbacks; changing a Hotkeys toggle affects the next wheel event, including within a drag. Existing keyboard input still consumes the selected modifiers as before.

The first single-finger drag callback after touch slop locks the dominant axis for that gesture. A leftward finger drag scrolls the application viewport right (67); a rightward drag scrolls it left (66). Horizontal distance accumulates one step per rendered line height, retains the fractional remainder, and clears that remainder on reversal or gesture end. Horizontal releases do not start vertical flings. Android mouse/trackpad `AXIS_HSCROLL` uses the same dispatch path, with positive values scrolling right and fractional values accumulated at three steps per unit, independently of vertical wheel input. Touch wheel reports retain the first reported cell during the gesture; physical wheel events use their current pointer cell.

Without tracking, horizontal gestures produce neither keys nor vertical scrollback, including on the alternate screen. Vertical drags retain scrollback, smooth scrolling and vertical mouse/alternate-scroll behavior. Selection, long press, pinch and physical mouse button dragging keep their existing input routes.

## Shared registered iPhone tools

Current service revision is 4. Successful tooling results include
`serviceRevision`, `requestAction` and a random non-authorizing `requestId`.
`tooling list` also
provides `workerSha256`, keyed by APK executable name (no host paths).
Pairing phases/errors are documented in [IPHONE_TOOLING.md](IPHONE_TOOLING.md).
The only registered tooling operation beyond `list` is the device-scoped probe
check; no signing, installation or credential-input operation is exposed.

Native's packaged client and Rootless's Python client use the existing same-UID,
session-token, version-1 length-prefixed USB protocol. A flat `op: "tooling"`
request accepts these exact additional fields: `list`: none; `check`: device.
Every request has version/token/op/action. Arbitrary argv, ports, paths, payloads
and credentials are not accepted.

Host-internal IPC is separate: same-UID + random stdin token, marker `B` with
USB+connector FDs to `iphone-worker mux-owner`, and marker `M` with one connector
FD to the trusted probe. Each private `M` request receives one fresh CLOEXEC
stream FD. No descriptor is returned on the guest socket. See
[IPHONE_TOOLING.md](IPHONE_TOOLING.md).

## Android storage size measurements

The app-local `IRuntimeService.measureStorageUsageJson(runtimeType, storageId)` returns
`{"bytes": <long>, "incomplete": <boolean>}`. `incomplete` marks a lower bound after
skipping access-denied descendants. Missing roots return zero; inaccessible roots and
other I/O failures raise a Binder-supported `IllegalStateException` rather than an
empty reply or default zero. The existing `measureStorage` returns the same
byte count for compatibility. Cached storage-list rows carry the same `incomplete`
flag alongside nullable `bytes`. Size reads never follow symbolic links or alter modes.

## Android Environment ownership

Host Binder clients launch a Profile's immutable `SessionLaunchSpec` or explicitly operate an Environment UUID. Binding/observing the service does not select or start a runtime. The app-local AIDL has no global runtime prepare/start/stop/state/image-status API. Environment state is read from `environmentsJson`; sessions remain authoritative through `sessionsJson` and `onSessionsChanged`. `onRuntimeChoicesChanged()` invalidates only the runtime-kind availability/installation projection and never represents a selected owner. UI and service AIDL are shipped together; this changes no guest wire frames.

Capability grant get/set requests require a host-validated AVF Environment UUID. The host resolves its saved VM identity; a guest cannot select its authority scope. Existing legacy `default` VM/grant identities remain storage compatibility identifiers, with no primary-runtime role. Grants are not copied to other Environments.

## Android runtime choice status

The app-local `IRuntimeService.runtimeStatusJson` is separate from the guest wire
protocol. Each runtime entry exposes `available` for session creation and may
expose `installable` for environments that can be prepared by installation.
Missing `installable` is false. Rootless backends report it only when the device
ABI and packaged PRoot executables permit installation. Session pickers display
entries with either flag; unavailable privileged AVF entries remain accessible
through settings but are omitted from session creation. Grouping distributions
under Userspace Linux is UI-only; requests retain their individual runtime IDs.

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

Host Binder keyboard/paste input pins the current controller and session lifetime before entering a controller/session-specific FIFO. Order is preserved within each session; sibling PTYs progress independently. A service-wide limit of 256 packets and per-session limit of 64 include active writes. Queue overload or an unavailable destination is reported through the existing input-operation error callback. Old queued input cannot select a later controller or a recreated session, and failed writes are never automatically retried. Raw replay socket replacement retains the logical controller and does not change this input identity or the guest wire format.

AVF emulator-generated replies use the existing WRITE RPC through separate session FIFO lanes, bounded to 256 batches per registration and 64 per session including active writes. Each output chunk's replies form one batch. The output collector must continue draining STREAM frames while the reply worker awaits RESPONSE frames on the same connection. Pending replies are pinned to their registration, session lifetime and terminal-state identity; replacement or exit invalidates them. Removing the registration cancels its reply worker. Unsent terminal replies wait in their bounded session lane during raw recovery, allowing buffered output to keep draining. A recovery admission rejection proves that no bytes were sent and may wait again; an uncertain WRITE is never repeated. They do not invoke user-input/activity observers. Saturation records `terminal.reply.overload` and closes only the affected AVF session client, allowing existing reconnection without stopping the VM or resending uncertain writes. Other controllers have independent reply capacity. No guest wire change is required.

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
Session creation errors use `세션 생성:<runtimeType>:<storageId>`; correlated AVF
errors may append `:<requestId>`. The UI matches
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
subscribes at an atomic checkpoint boundary under the authoritative emulator lock.
Resize RPC waits do not hold that lock. The host inserts an opaque, host-only
resize barrier at the successful matching response's position in the received
stream; Native/Rootless emit the same barrier under their PTY output mutex.
The registry applies dimensions/cell size before parsing subsequent output, then
finishes palette/subscription under the state lock. Earlier delivered output
retains the previous geometry. Barriers are not encoded on the guest wire.
Controller/session/state identities fence late responses after replacement,
exit or removal. Failed/cancelled requests are never automatically resent.
Terminal commands, resize and viewport updates retain arrival order per session,
with one shared limit of 512 active/pending operations. Different sessions can
progress independently. The short state lock still orders emulator mutation,
checkpoint capture and callback enqueue; network CREATE/ATTACH/RESIZE waits run
outside it. A per-owner creation gate preserves initial output until the new
terminal state exists, without holding existing sessions' output. No guest wire
field changes.

A checkpoint descriptor is a read-only, immutable app-private temporary file,
unlinked before delivery. Serialization runs under the state lock, with a 64 MiB
compressed size limit; no UI reader, pipe writer or Binder call participates in
capturing state. Descriptors close after delivery, cancellation or queue failure;
startup removes interrupted capture files. No path is exposed or retained after
capture. EOF is checked before installing the graph. Its header
contains an optional viewport (row, fractional offset and
transcript-shift counter), followed by `TSC1` and a gzip-compressed same-build
terminal object graph. Only listed terminal/value/collection classes are allowed;
serialized proxies are rejected. Output/client/process bindings are excluded.
This format is neither persistent cross-version storage nor a guest input API.
The UI installs the state without replaying queries or side effects. Its bounded
byte queue processes subsequent mirror output at the exact checkpoint boundary.
Runtime PTY readers drain final bytes before publishing process exit.

Each host UI listener has an ordered, asynchronous callback queue limited to
1 MiB of output bytes (including in-flight output) and 256 pending callbacks.
The service admits at most 64 terminal/runtime subscriptions and uses four
callback workers with a bounded scheduling queue. Adjacent output from the same
session is batched into calls of at most 64 KiB without crossing checkpoint,
resize or exit boundaries. Overloaded mirrors wait two seconds before requesting
a replacement, preventing repeated captures during sustained output. No synchronous Binder callback executes
under the authoritative terminal-state lock or on the runtime event collector.
Overflow invalidates only that observer and sends the host-private
`TERMINAL_RESYNC_REQUIRED` error; the UI discards the old mirror/registration and
reattaches for an authoritative checkpoint/list. Old subscription generations
cannot append to the replacement mirror. This is not silent loss of authoritative
PTY bytes. Error notifications are oneway, so an unresponsive client cannot block
invalidation. Runtime-listener registration still waits at most five seconds for
its initial ordered replay, outside runtime/terminal locks, preserving UI selection
restoration ordering. Guest protocol-v1 framing remains unchanged.

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

AVF image initialization uses a dedicated host-CID-only vsock listener on port 40503, separate from sessiond (40500), Android capability RPC (40501) and port reports (40502). The existing v1 frame envelope carries only a `LIFECYCLE` request with payload `0x40`; no command, path, repository or environment argument is accepted. Progress is a STREAM frame with `0x40`, success is a RESPONSE containing an eight-byte big-endian positive epoch-seconds timestamp, and failure uses the standard ERROR payload. Replies preserve the request ID. Closing the connection cancels the fixed updater process group; execution is bounded, and only a successful distribution-selected package-index refresh writes the guest receipt (strict APT refresh for Debian/Ubuntu; pacman database refresh for Arch).

Host-only appended `initializeAvfImage(storageId, requestId, callback)` and `cancelAvfImageInitialization(requestId)` Binder calls require the application UID and verified main process. The callback reports boot/package-refresh phases (the persisted `APT_REFRESH` phase name remains compatible) and completion errors to the installation worker. Each request owns only its environment lease; only a VM newly booted for initialization and without live sessions/explicit-start ownership may be stopped.

The host session client contains transport read and frame-validation exceptions within its receive job. EOF, a failed read or explicit close publishes `DISCONNECTED`, fails pending requests and closes only that transport; new requests on a disconnected client fail without a send or request timeout. Cleanup failures cannot escape to the shared runtime owner. Coroutine cancellation still propagates, and disconnection emits no synthetic PTY exit or sibling-session removal. Guest wire frames and protocol version remain unchanged.

After PTY EOF has been drained and the child exit status collected, sessiond closes that session's PTY master and removes it from the active I/O poll set. LIST and attachment still expose the retained exited descriptor until explicit termination removes its history; an exited ID cannot restart a shell. Only running sessions enter the hot poll loop, independently of how many exited descriptors remain.

A session request deadline covers both sending and response waiting, using 10 seconds on either transport. The 60-second raw-link recovery lease is independent. Transport state distinguishes `CONNECTED`, `RECOVERING` and `DISCONNECTED`; new requests during known recovery fail before sending. A complete ordinary request whose response times out or whose caller cancels is abandoned by request ID, without closing sibling requests. Late responses are ignored and the original mutation is never reissued. Incomplete sends, send failures, unconfirmed creation and unresolved ordered-resize boundaries still close the logical client and fail pending requests: reusing a partial frame or unknown initial terminal identity/output geometry is unsafe. `session.request.timeout` and `session.request.cancelled` distinguish these stages from read/send failures. An independent deadline closes the socket before waiting for a blocked sender to finish. Descriptor shutdown wakes both native directions; close does not flush buffered partial frames. Successful error responses remain ordinary RPC errors rather than transport failure.

Platform connection attempts are separately bounded to 10 seconds with eight workers and no queued opens. A platform call returning after timeout/cancellation loses ownership and its descriptor is closed. Resumable attachment/retry sleeps share its 60-second recovery deadline. No protocol version or guest capability changes are required by these host changes.

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

## AVF image maintenance

AVF maintenance extends the existing host-CID-only 40503 listener with exact,
argument-free LIFECYCLE opcodes `0x41` (update/migrate) and `0x42` (health/sync).
The former requires host-set boot parameter `term.maintenance=components|rebase`;
guest data never selects host paths or a command. Both retain `0x40` framing,
progress and positive epoch-second completion semantics. Maintenance is bounded
to 1800 seconds and cancellation terminates its process group. The appended
host-only `updateAvfImage(storageId, requestId, generation, operation, callback)`
consumes only that work UUID's verified staging directory. Environment operations
`UPDATE_COMPONENTS`, `UPDATE_IMAGE`, `ROLLBACK_IMAGE`, `DISCARD_IMAGE_HISTORY`
require the same UI caller checks. See [AVF_UPDATES.md](AVF_UPDATES.md).

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

`droid-port-agent` uses the existing protocol-v1 `PORT`/`STREAM` frame envelope over host-CID-only vsock 40502. Legacy `1` listening (`u16 port`, `u16 UTF-8 label length`, label) and `2` closed (`u16 port`) payloads remain byte-for-byte compatible. New agents also emit `5,1` snapshot-begin/proxy-version, address-aware `3` listening and `4` closed events, then `6` snapshot-end. Address-aware payloads contain `u16 port`, `u8 numeric-address byte length`, ASCII IPv4/IPv6 address, and for listening only `u16 UTF-8 label length` plus label. Integers are big-endian. Addresses are limited to 45 bytes, labels to 128 bytes, ports to 1–65535, and initial/delta reports to 128 endpoints. Missing process names remain null. Failure to read the guest listener tables disconnects the report stream instead of inventing close events.

A new host consumes legacy events until a valid snapshot-begin, then ignores duplicate legacy events on that connection and uses address-aware events. Missing endpoints are closed only at a completed snapshot-end. Unknown, oversized and malformed event payloads are ignored; malformed frames/EOF disconnect only that Environment. Reconnect resets negotiated capability and rebuilds the full snapshot. Old hosts ignore unknown extension payloads and still consume legacy events. New hosts show old-agent ports as discovered, with unknown source address and unavailable TCP forwarding; they do not guess an HTTP protocol or expose a misleading active mapping.

The new guest proxy binds host-CID-only vsock 40504. Each raw stream starts with `u8 version=1`, `u16 TCP port`, `u8 address length` and the numeric address, then carries TCP bytes without a frame envelope. The guest accepts only endpoints currently in its local TCP listener tables, maps wildcard addresses to same-family loopback, and rejects DNS names, URI syntax, arbitrary remote destinations and non-host CIDs. Header reads and TCP connects are time-bounded; idle streams time out after five minutes and simultaneous connections are limited to 64. This endpoint carries no Android capabilities, Binder objects, commands or process IDs.

App-only AIDL exposes `getLocalServicesJson`, `updateLocalServiceForwarding`, and registration/unregistration of the separate one-way `ILocalServicesListener` invalidation callback. All methods require the application UID and verified main UI process. Snapshots contain only AVF services and AVF Environment metadata without creating or connecting runtime controllers. Native/Rootless registration APIs are removed; both sides of this app-private interface update together in the APK. Older manual preference entries are ignored while saved AVF mappings remain readable. Forwarding mutation accepts only an existing observed address-aware AVF service, a nonprivileged loopback host port and an explicit application-protocol enum. The retained `getForwardedPortsJson` adapter returns successfully bound proxies only, with separate host and guest ports.

## Host diagnostic recording

AVF diagnostic logging is host-only and changes no guest wire frames or image requirements. The UI, workers and `:vmhost` share fixed app-private `connection-events.log` and `connection-events.settings` files, serialized by a JVM monitor and POSIX file lock. Version-1 settings contain `enabled` and a boot-monotonic cutoff (`clearedAt`). Changing recording or clearing history advances the cutoff so older queued events cannot reappear. A subsequent boot resets an obsolete future cutoff while preserving the enabled preference. Corrupt settings fail closed instead of enabling recording.

Records contain wall/monotonic time, PID, symbolic event/error-class/code and stable owner identity; no terminal payload, command, path, exception message or token. Retention is at most 1,000 records and 256 KiB with atomic file replacement; the asynchronous writer queues at most 128 tasks and can drop records under overload or process death. Recording defaults on; disabling gates both this file and `TermConnection` logcat, retains history, and does not affect other Android logging. Viewing, copying and confirmed deletion need no Binder connection, AVF permission, VM startup or guest command. Guest journal collection is outside this feature.

The viewer and clipboard render stored records as one chronological text block,
with `[yyyy-MM-dd HH:mm:ss.SSS ±HH:mm]` local timestamps (UTC uses `Z`) followed
by metadata. Legacy `time_ms` records remain readable; missing/invalid timestamps
use `[unknown]`. Search matches displayed timestamps and original metadata.

Bounded structured fields correlate Environment/storage/session/request IDs,
operation and phase, byte counts, elapsed/deadline/backoff milliseconds and replay
receive/send/credit positions. Up to four cause types, one source class/method/line
and an available OS errno are recorded without exception messages or stack dumps.
Core fields cannot be overwritten and oversized detail sets explicitly truncate
within the 768-character line bound. Session RPC start/completion/failure events
cover control operations; ordinary successful writes stay quiet, while failed,
partial or one-second-plus writes include counts and phase. Invalid output event
frames are also diagnosed. Connection/recovery stages include re-list/reattach
results, bridge/port connection failures and retry waits. Runtime binding, task
removal and memory pressure include work counts and Android power/idle/exemption
state; wake-lock transitions and guest work boundaries are recorded. Repeated
input failures are limited to once per second per event/session/reason with a
suppressed count, using at most 128 retained limiter keys. No periodic resource
polling, input/output content capture, additional VM work or guest update is
introduced, and wire/retry/ownership semantics remain unchanged.

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

## Workspace and Shared Directory host APIs

The app-private `reorderWorkspaces(workspaceIdsJson)` operation is appended to the existing host API and enforces the same application UID and verified main UI process. Its JSON array is limited to 16 KiB and 128 unique canonical Workspace UUIDs. The registry updates only the saved Workspace array order under its single-writer lock, ignores IDs deleted since the UI snapshot and retains newly added groups after requested destinations. Invalid input leaves the last readable state intact; memberships, selection, Workspace launch configuration and backend lifecycles are unchanged.

App-only AIDL appends Workspace snapshot/save/delete/filter/membership operations and `createSessionInWorkspace`, preserving previous transaction order. All enforce application UID and verified main UI process. Workspace IDs are canonical UUIDs, membership has at most one value per session, and the default filter is `unassigned`, and the legacy `all` token is accepted as an alias for `unassigned`. Launch uses the existing bounded `SessionLaunchSpec` plus a request UUID, optional Workspace UUID and explicit installation-consent bit. Workspace metadata never changes an Environment/Profile identity or invokes terminate/stop. Shared directory APIs accept validated Native/Rootless Environment references and bounded legacy-operation JSON. AVF connections and folder-copy modes are rejected. Snapshots include host-only paths and persisted operation state; no host path enters guest messages.

The app-private `deleteEmptyTemporaryWorkspace(workspaceId)` method is appended without changing existing transaction IDs. It accepts a canonical Workspace UUID, enforces the same host UI caller boundary and returns an empty string on success or an error on refusal. A complete live-owner query is required; current temporary status and memberships are rechecked under the Workspace registry lock, and accepted pending launches prevent deletion. Empty selected temporary groups use the normal metadata-only deletion and fall back to `unassigned`; occupied, promoted or unverifiable groups are preserved. It never terminates sessions or deletes Environment, shared directory or file data.

## Explicit guest file copies (TFT1)

AVF shared-directory connections and folder-based copy jobs are retired. The TSB/TSF and virtiofs specifications below describe legacy images only: the current host does not activate them or open port 40506. TSR1 session recovery and standalone Android share-to-Linux TFT1 copies remain supported. Host Shared Directory APIs reject new AVF connections and TO_VM/FROM_VM folder jobs; old leases are retained only until verified stop.

Live app-private mounts are independent of TFT1. `shareBrokerVersion=1` and `term.share-broker=1` enable host-initiated streams to guest vsock 40506 (host CID 2 only). The control stream begins big-endian u32 `0x54534231` (TSB1), u16 version 1, u16 folder count 1..128, 16 random token bytes, then each raw 16-byte UUID, u8 name byte count 1..64 and ASCII name. Existing name validation applies; identities/names must be unique and the complete manifest is validated before creating mount directories. The guest replies u8 zero to accept. The host opens one stream per folder with u32 `0x54534631` (TSF1), the same 16-byte token and that UUID. After guest u8 zero, this stream becomes SFTP v3 over the existing duplex transport; no SSH handshake or TCP listener exists. The guest runs passive SSHFS and sends a second zero on the control stream only when every expected FUSE mount is present and its root responds. Startup is bounded by host 50-second and guest 40-second readiness deadlines. In v1, control EOF or failed data clients power off this VM; reconnect requires a new boot.

### Recoverable shared folders and sessions

`shareBrokerVersion=2` opts into TSB2/version 2 and TSF2, with the same manifest,
token and UUID fields. A replacement control connection must match this boot's
entire manifest and owner token. TSB2 appends u8 create (1) or resume (0) after
the complete manifest; TSF2 appends it after the folder UUID. Resume never creates
a missing owner/folder generation. After acceptance/readiness, opcode 1 refreshes
the 60-second owner lease; its response is u8 zero, u16 revoked-folder count,
then those UUIDs. The host refreshes every five seconds. Opcode 3 plus a UUID
revokes that folder; opcode 2 revokes the owner. Both return u8 zero. Data status
1 rejects a revoked or mismatched generation; zero starts TRS2. Reconnection
retains the same SSHFS process and scoped SFTP handles, without remounting.

TRS2 is a bounded duplex byte stream. All integers are big endian. Its handshake
is four bytes `TRS2`, u32 receive capacity (1..262144), u64 received offset and
u64 consumed offset. Offsets are limited to signed 64-bit range. Received means
buffered; consumed releases sender credit. A peer must report
`retained-base <= consumed <= received <= produced`; a lost generation is an
error, never a new application request. Frames are u8 kind, u64 offset, u32 size
and payload. Kind 1 carries 1..32768 data bytes; duplicates below the received
offset are discarded, gaps and credit violations reject the link. Kind 2 has
size zero and acknowledges consumption. Kind 3 has offset/size zero and is an
idle five-second heartbeat; an unresponsive link is replaced after 20 seconds.
Each direction retains at most 256 KiB per stream. Full windows backpressure
their producer; bytes and file operations are never silently dropped/reissued.

Host and guest writers combine up to four already-ready DATA frames and a
pending consumption ACK into one raw write. Each frame retains the same 32KiB
payload bound and offsets; an ACK or short interactive write is flushed without
waiting for another frame or a batching timer. Per-link scratch buffers are
reused: at most 128KiB plus five headers for sending, and 32KiB plus the receive
header for reading (in addition to replay rings and buffered stream wrappers).
The send scratch owns its bytes until native I/O completes, so consumption or
socket replacement cannot mutate an in-flight batch. A partial batch disconnect
recovers from the last complete received frame through the existing handshake.

An isolated data link that remains disconnected for 60 seconds also revokes only
that folder, even while the control lease is healthy. After mount readiness,
root pins and seals each underlying mount directory as
root-owned mode 000. A v2 lease expiry or failed folder actor closes its relay
and SSHFS process, preserving a failed FUSE mount where possible. Even after an
explicit unmount, unprivileged writes cannot fall through to a local shadow
directory. Proven guards permit the VM and its sessions to continue. Startup or
guard-proof failure retains the VM-fatal policy. Durable host deletion leases
still require verified VM stop and stopped host I/O.

The complete pinned guard set must match every configured mount. Guest startup
failures remain VM-fatal. After readiness, a monitor error attempts guarded
owner retirement; proven isolation preserves the VM, while failed isolation
remains fatal and is not retried as an ordinary monitor error. Guest failure
records include a fatal flag and numeric OS errno when available. A later fatal
cause supersedes an earlier transient record; subsequent errors cannot replace
the first fatal cause.

Host `share.control.reconnecting` diagnostics identify connection, manifest,
owner acceptance, folder startup, mount readiness, folder revocation, heartbeat
and revoked-folder decoding phases. Metadata includes the received status byte
(-1 for EOF, `none` when no byte was read), retry attempt and observed control
outage milliseconds. `share.control.stalled` records the local 15-second progress
watchdog and its observed idle milliseconds. Paths, tokens, protocol payloads
and exception messages are excluded. These diagnostics do not change the
60-second authority/recovery budgets or authorize expired-generation replay.

The host applies the same 60-second recovery budget to failed v2 control/data links. Expiry permanently closes the corresponding host relay/SFTP handles and stops retrying that generation; owner-wide failure closes all its host actors, while an isolated folder failure leaves the control link and other folders alive. `ready` cannot remain true after permanent failure. Host metadata events `share.owner.unavailable`, `share.data.unavailable` and `share.data.revoked` surface an Environment-scoped warning without claiming VM/session death. Repair requires a stopped/new boot through the existing Environment controls. This does not release durable deletion leases, renew an expired guest authority, change the wire handshake, or replay uncertain filesystem operations.

`sessionResumeVersion=1` enables the host-CID-only session relay on 40507.
`TSR1` plus a random 16-byte process-owner token and u8 create (1) or resume (0)
receives status zero, followed by TRS2; a different live owner receives status 1. Optional operation 2 releases the exact matching token and returns status 0 without TRS2. A mismatched release returns status 1 and cannot affect the current owner; release with no retained owner succeeds idempotently. Older guests reject operation 2, so the host treats it as best effort and preserves legacy lease semantics.
Resume without a retained generation receives status 2 and must close the host
stream. This prevents re-executing unacknowledged input after proxy death even
when the sender has not received its first consumption ACK. The relay retains one
root-authenticated Unix connection to sessiond (`SO_PEERCRED` UID 0), using the
unchanged session-v1 frames. The host retains its protocol client and emulator
while replacing only the raw socket, with a 60-second recovery budget. Guest
relay state expires after 60 seconds without a link. Legacy 40500 remains
available. Buffer saturation intentionally backpressures PTYs. No state is
persisted across host/proxy/sessiond death, and no historical input is retried
as a new RPC. An operation timing out after transmission has an unknown result;
reconnection does not authorize retrying that operation.

Permanent replay-owner closure (including lease expiry) shuts down its internal sessiond/SSHFS socket in both directions before releasing it, waking either application pump even if a native send or receive is blocked. Raw-link detachment alone retains that socket and its pumps for recovery. A closed owner rejects and closes late application sockets. These guest cleanup changes retain the existing wire version, lease and replay rules.

Host `session.link.reconnecting` records the first raw-link failure with a fixed
code for header/payload reads, frame bounds, ACK/data/credit/heartbeat validation,
frame writes or heartbeat expiry. Explicit link/owner closure has no failure
code. `session.attach.failed` identifies platform `connect`, `owner_write`,
`owner_status` or `replay` failure, with the received status byte (`-1` for EOF,
`none` before a reply), attempt number (0 for initial attachment, 1 onward for
recovery) and elapsed milliseconds for that attempt. Replay rejection codes
distinguish invalid handshake magic, receive window and stream offsets; timeouts
have a separate code. Error classes remain separate from these stable codes.
No exception messages, tokens or protocol/terminal bytes are recorded. These
host-only diagnostics retain the existing retry classification, recovery and
heartbeat budgets, replay checks and wire format; no guest update is required.

Each host stream is bound to one pinned directory. SFTP absolute paths mean that export root; parent traversal, invalid UTF-8/NUL and oversized paths are rejected. Packets are at most 256 KiB and file data requests 128 KiB, with 128 handles per stream, 256 global handles, four concurrent host operations and 256 global folder streams. SFTP v3 ordinary file/directory operations plus `posix-rename@openssh.com=1`, `fsync@openssh.com=1` and `statvfs@openssh.com=2` are supported. New-link requests follow OpenSSH's target-first order. Symlink targets are literal guest paths; the host never follows them. File UID/GID maps to 1000 and host paths/errors are not returned. Special files, hardlinked content and ownership changes to other IDs are refused. Cross-runtime advisory locks and hardlink creation are unsupported. SFTP v3 timestamps have seconds precision. Unlike TFT1, live I/O has no whole-file 64 MiB/two-minute limit.

Legacy images may still contain the virtiofs mounters. Current app boots send neither `term.virtiofs=1` nor `term.android-storage=1`; new images omit both services.

A separate raw stream on vsock 40505 belongs to the existing droid-agent binary, invoked by `droid-file-transfer.service` as non-root `droid`. Only host CID 2 is accepted. Standalone Android share routing selects the actual running VM backend without a shared-directory connection. This stream does not share a PTY, bridge, port-forward or session envelope and accepts no guest-to-host pathname or request.

The server greets with big-endian u32 `0x54465431` (TFT1). The host sends the same magic, u8 operation (`1` copy to VM, `2` copy from VM), u16 UTF-8 path length, and path bytes. The path is relative to `/home/droid/term-transfers`, starts with the selected folder name, is at most 4096 bytes/65 components, and rejects empty, dot, parent, control and oversized components. Every parent and file is opened relative to pinned directory FDs with no-follow flags. Only single-link regular files are transferred; special files, symlinks and existing destination files are rejected.

For operation 1 the host sends u64 size, exactly that many file bytes, then 32-byte SHA-256. The guest verifies, fsyncs and atomically renames with `RENAME_NOREPLACE`, then replies u8 success (`0`) and the digest. For operation 2 the guest replies u8 success, u64 size, bytes and SHA-256; the host verifies and commits with the same no-replace rule, then acknowledges u8 zero and digest. Nonzero status/EOF/invalid framing fails the operation. Both enforce 64 MiB; the host closes the operation connection on cancellation or its 120-second deadline and the guest has 30-second socket I/O timeouts with at most two workers. Directory creation is scoped to the fixed destination root. Originals and prior destination files are never deleted or replaced. Failure after an atomic commit but before acknowledgement can leave a verified new copy, which a retry will not overwrite. Older guests without TFT1 remain explicitly unsupported; no image or legacy envelope is silently rewritten.

## AVF primary shared-storage mount

This capability is retired. Older images recognize `androidStorageVersion=1`, `virtiofsVersion=1` and `term.android-storage=1`, but the current driver never sends the flag or creates an export. `setEnvironmentInternalStorageAccess` rejects enabling AVF storage sharing. Retained preferences cannot authorize a mount; Native/Rootless permission behavior is unchanged.

## Aggregate guest resource observation

Optional SESSION request opcode `0x08` has no fields. Response `0x88` contains four big-endian signed 64-bit byte counts (MemTotal, MemAvailable, SwapTotal, SwapFree), then three signed 32-bit values (available CPU count, load average times 1000, memory PSI `some avg10` percent times 1000). PSI `-1` means unavailable. Counts must be consistent, CPUs 1–4096, load nonnegative and PSI -1–100000. The response is exactly 45 payload bytes. No PIDs, paths or terminal content are exported. Old guests return their normal unsupported/invalid-request error; this does not disconnect a healthy stream. Host observations are asynchronous and registration-scoped, at most once per 15 seconds on demand from memory diagnostics. Metrics do not enter the app PSS sum.

Guest session transport remains byte-compatible. Nonblocking framing handles partial reads/writes without delaying PTY draining. Outbound frames retain FIFO order and partial-write offsets. Output has a lazy 16MiB bound and stops reading new PTY output when response headroom is exhausted. Accepted pending PTY input has a 4MiB/256-packet bound, retains per-session order, acknowledges only complete delivery, and is not retried across logical connection death. Socket replacement inside the replay tunnel preserves the same logical connection. Empty or missing sessions and input overload receive explicit errors. Host recovery credits, heartbeat and authority timeouts are unchanged.


## Native session recovery implementation

New guest packages execute `droid-sessiond --session-recovery` for port 40507.
The on-wire TSR1/TRS2 versions, offsets, credit, frame bounds and host-CID check
are unchanged. No Python interpreter is involved in normal session forwarding.
The relay uses lazy bounded rings and owned write batches; native socket I/O
never runs under the ring mutex. Dropping a raw link retains application state;
explicit release, application loss and lease expiry close both directions.
The existing serial session daemon retains exclusive PTY ownership.

Host list collection runs owner RPCs concurrently outside metadata/publication
locks. Registration and mutation generations fence delayed responses. A
successful local terminate removes only that session and immediately publishes
the cached authoritative projection; it does not wait for another VM's LIST.
PTY EOF without a child wait status leaves that process running/unknown until
an actual exit is reaped or termination is requested. No EOF invents a guest
exit or closes sibling sessions.


Host session projections may contain `INTERRUPTED` for retained history after
proven execution-owner loss. It is not a guest wire state: the session-v1 codec
still accepts/emits only CREATED, RUNNING and EXITED. Interrupted JSON records
omit `exitCode`; actual EXITED records retain real codes, including negative
signal values. Runtime terminal snapshots can still be attached/configured for
retained records without a guest connection. Input is rejected; explicit Close
removes only the history/checkpoint. Service startup completes history restoration
before its initial observer replay or session JSON read, avoiding a transient
empty list that would discard tabs and Workspace layouts.


AVF session IDs exposed to the host include an Environment boot namespace, the
guest creation timestamp and the guest-local ID. Only the original ID travels
on the guest wire. The namespace is persisted before first use, stable across
transport reconnects and Android runtime-service recreation, and retired after
verified Environment stop; reused counters such as `session-1`
therefore cannot target an archived session or another VM. Descriptors establish
identity before subsequent events. At most 256 events / 256 KiB of early output
can wait for CREATE/LIST metadata without blocking receipt of that response.
Identity mapping and response decoding happen in the existing receive worker,
without an extra forwarding queue for normal traffic.
