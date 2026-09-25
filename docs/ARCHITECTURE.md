# Architecture

## Native / Rootless iPhone tool environment

The iPhone transport only claims the active USB configuration; changing it requires
the separate device-wide broker capability, including for trusted tools. The host
conversation retains bounded residual-effect metadata through failure/cancellation.
SideStore pairing placement uses a verified staging file and one AFC rename, with
no destructive replacement fallback or automatic uninstall/settings rollback.
These are failure-containment measures, not an iOS transaction or crash-recovery
journal; see IPHONE_TOOLING.md for protocol and physical-validation limitations.

The app provides a shared typed USB CLI capability, not a SideStore/IPA installer
screen. Native's packaged Bionic CLI and Rootless's Python CLI call the same
session-scoped broker. `:vmhost` leases the physical device and owns the USB/mux
worker and registered host-tool processes independently of Activities. Only the
trusted mux owner receives USB; a trusted tool receives a private anonymous
multi-stream connector. Guests receive neither. Registered APK recipes now have
session-scoped start/status/select/cancel operations. The pinned isideload recipe
uses generic host-only secure input; there is no application install button. See
[IPHONE_TOOLING.md](IPHONE_TOOLING.md) for the plan and extension rules.

## Phase 0 decision

**Problem:** Android UI destruction must not terminate a Linux VM, a shell, or
its child processes.

**Constraints:** custom AVF VMs require privileged Android permissions; guest
code is untrusted; the public Android SDK does not expose the system AVF APIs.

**Decision:** the app is split into architecture-owned modules and the runtime
service exposes a unified session registry:

```text
android-app (UI process / renderer)
        │ AIDL, attach/detach only
        ▼
android-runtime :vmhost (session owner)
        │ UnifiedSessionRegistry
        ├── NativeSessionController ── Bionic PTY
        ├── RootlessSessionController ── packaged PRoot + selected distribution rootfs
        └── ProtocolSessionClient ── vsock ── droid-sessiond (AVF PTY owner)
```

`VmRuntimeService` uses `android:process=":vmhost"` and
`stopWithTask="false"`. The Activity unregisters its callback and unbinds in
`onStop`; it never sends a stop request as a lifecycle side effect.

Task notifications are opt-in and owned by `:vmhost`, independently of the ongoing
foreground-service notification. A bounded streaming observer reads only fresh PTY
output (never replay) and recognizes OSC 133 command boundaries, BEL and explicit
OSC 9/777 attention requests. Native's managed prompt and Bash's initial PS0 /
PROMPT_COMMAND hooks supply completion boundaries without rewriting user rc files.
Native submission timing ignores bracketed-paste newlines until Enter is sent.
The observer uses a monotonic clock, a 10-second long-command threshold and per-session
plus global rate limits. It owns no process and starts no polling/background timer.
A separate Android notification channel and immutable, session-specific activity
PendingIntent provide click-through; missing sessions are never recreated. Disabling
the preference cancels only task notifications. Permission denial cannot stop PTYs.
User output cannot choose Android actions or notification body text.

Session creation resolves a persistent `SessionProfile` into a defensively copied,
immutable `SessionLaunchSpec` before the UI calls `createSessionFromSpec` (versioned,
bounded JSON over the existing Binder boundary). Native, Debian, Ubuntu, Arch and
AVF are initial defaults; app-private overrides retain their IDs and deletion markers
prevent deleted defaults from reappearing. User copies have independent IDs.
Profiles contain creation settings and a palette preference, never PTY, dimensions
or live renderer state. Palettes are either global inheritance or 19 explicit RGB
colors. Live UI mirrors resolve the current saved profile; deleted profiles fall
back to their launch snapshot. Each emulator owns its palette defaults so OSC
resets cannot pick up another session’s colors. Transition colors are render-only,
with one Activity-owned animation clock for terminal and chrome.
Profile icons use a stable `auto`, built-in key, or validated content-addressed image
identity in both the profile and launch snapshot. Missing keys retain automatic
runtime icons. The UI imports bounded PNG/WebP/JPEG streams into immutable normalized
app-private PNGs; no URI grants, arbitrary paths or image bytes enter Binder or the
runtime owner. Duplicates retain the icon identity and files are not removed when a
profile changes or is deleted. Tabs use their launch snapshot, not the mutable profile.
All icon surfaces share the same tint/fallback renderer; cached decoding is off the UI
thread. Missing imported files fall back to the snapshot runtime's default icon.
Profiles and immutable launch snapshots carry a validated storage UUID. New and
duplicated profiles allocate fresh storage by default; explicit sharing copies
the storage identity, not a reference to another profile. The service owns one
backend/controller per `(runtime, storageId)` and the registry routes sessions
to that owner. Deleting or editing a profile neither deletes storage nor changes
live session ownership. Kitty child sessions inherit their source storage.
Native home/tmp, Rootless rootfs (including dpkg), home/tmp/workspace, and AVF
VM instance/disk/image installation are scoped to storage. Android system and
APK-packaged Native executables remain shared read-only platform resources.
Sharing a Rootless profile also selects its distribution; different distributions
cannot share a rootfs. This is filesystem organization, not an Android UID or
security sandbox boundary.
Missing storage IDs decode as `legacy`, preserving existing roots and their
shared workspace without copying or deleting data. Storage changes affect future
launches only. There is no automatic storage garbage collection.
Independent AVF storage requires a privileged provider that creates independent
backends plus a configured image catalog or a verified provisioned image at its
storage root. Image workers are keyed by storage and never clone a running VM
disk. The built-in AVF profile is listed only when both custom VM permissions
are actually granted. Stock APK builds cannot grant themselves these permissions.
The registry's `create(spec)` is the common launch path; legacy runtime and Kitty
requests adapt to a built-in profile and resolve a spec too. Arguments use the
existing argv envelope understood by Native, PRoot and the AVF guest daemon.
Startup commands run once via the configured POSIX-compatible shell, then exec the
configured shell and literal arguments; attachment never reruns startup commands.

The service stores each created session's profile ID and launch snapshot separately
from the profile store and publishes them with authoritative session snapshots.
Editing/deleting a profile cannot mutate a live session. Native/Rootless metadata
is discarded on owner process restart; it never fabricates restoration of a dead
PTY. AVF metadata remains available when reconnecting to a surviving guest daemon.
Legacy surviving guests with no launch metadata remain attachable without inventing
creation settings. The empty Native working directory retains its existing meaning:
resolve to the backend's app-private home. Broker-issued environment values remain
runtime-owned and are excluded from user profile settings. No wire protocol or
PTY ownership changes are required.

`UnifiedSessionRegistry` records the owner runtime of every session and routes
write, resize, attach, detach, and terminate operations through the same API.
Runtime selection is explicit. A failed Rootless or AVF request is never moved
to another backend.

The UI tracks runtime attachment separately from its cached terminal renderer.
Background/foreground transitions retain the live UI mirror and transport. On
resume, appearance is applied at the session's effective font size without an
intermediate default-size resize. Unchanged text size and font settings reuse
the renderer and loaded fonts; changing the font list, order, style or built-in
fallback reloads fonts. Each view owns its cache lifetime, so a new view loads
fonts again (including changes to the contents of an existing font URI).
Selecting a cached tab still attaches that exact session to the runtime
transport, while closing a background tab leaves the active attachment
unchanged. UI unbinding detaches only the session actually attached to that
client; it never iterates sessions as though every cached tab were attached.
Closing one PTY may interrupt that PTY's blocking output read. Controllers
contain this expected session-local I/O completion so it cannot escape a
coroutine and terminate the shared `:vmhost` process or sibling sessions.

Rootless Debian, Ubuntu and Arch Linux ARM each use a distinct rootfs and home,
and share a host-owned workspace. The service owns one backend/controller per
distribution and the registry routes by the explicit host-local selection. The
first selection automatically installs that distribution's checksum-pinned
archive into its rootfs. Debian's existing paths and wire identity are unchanged.
See `ROOTLESS_DISTRIBUTIONS.md` for catalog details. Its PRoot executable may only be resolved from `ApplicationInfo.nativeLibraryDir`;
downloaded executable code under writable app data is rejected by design.
The checked payload is staged into the APK as `libproot.so` and
`libproot-loader.so`; a clean build does not depend on ignored local JNI files.

## Alternatives considered

Using `Activity`, `WebView`, or a terminal tab as the process owner was
rejected because Android may destroy any of them. Direct AVF use in UI code was
rejected because it couples all higher layers to privileged APIs. A stock-APK
reflection implementation was rejected because it cannot make signature
permissions available and would hide the deployment requirement.

## Trade-offs and migration impact

The public build presents an AVF availability failure on normal devices. This
is intentional and truthful. A product build selects the separately compiled
system-SDK driver by declaring `AospRuntimeApplication`, which implements the
runtime module's `RuntimeBackendProvider` in both the UI and `:vmhost`
processes. The public runtime module never links against that class or AVF
System APIs. No UI, session, or protocol redesign is required for that change.

`droid-sessiond` now owns guest PTY master descriptors through its
`SessionRegistry` and exposes them through its host-CID-only vsock listener.

The host-facing `ProtocolSessionClient` is transport-agnostic. It matches
request IDs, receives typed errors, and emits PTY output events. An AVF driver
must supply only an authenticated `GuestConnection`/`FrameTransport`; it cannot
leak a raw vsock descriptor into UI code.

`ParcelFileDescriptorFrameTransport` is the concrete host framing layer for an
AOSP AVF driver: `VirtualMachine.connectVsock(40500)` yields a descriptor, then
this transport owns framing and closure. VM creation remains platform-only.

`VmRuntimeService` caches only serializable session metadata for AIDL clients.
The guest remains the source of truth: UI reconnect calls the service for a
fresh list, while terminal output and process exit are delivered as bounded
callbacks. Output enters the terminal emulator from a Binder worker rather
than the UI thread so a full 4 KiB emulator queue cannot deadlock rendering.
Operation failures are returned through the same listener instead of being
lost in a service coroutine.

Session mutations and list publication pass through one serialized snapshot
pipeline. Each published list has a monotonic revision, and the UI ignores an
older revision. The unified registry retains each controller's last successful
list across a transient list/transport failure; only a successful authoritative
list or successful single-session termination removes its entries. Closing a
tab therefore disposes only that session's UI projection after the service
snapshot confirms removal. An empty authoritative list is a normal UI state,
not a request to create a shell or stop the runtime.

`SharedDirectoryManager` owns a per-VM host path only; it does not expose the
rest of app-private storage. `PortForwardRegistry` accepts only validated port
reports from a future guest port-agent, and `RuntimeWakeController` uses a
time-bounded partial wake lock only when a configured policy reports meaningful
guest work. Neither component treats a foreground service as an execution
guarantee.

Wake policy is persisted host-side. `KEEP_ACTIVE_WORK_RUNNING` follows runtime
work rather than UI attachment: a running session, host-owned setup/start
operation, or authenticated guest `WORK_ACTIVE` report is active, while
Activity detach alone does not make it idle. `WORK_IDLE`, the authoritative
session list, and runtime stop/failure return the policy to idle or stopped
state. Each partial-wake-lock acquisition remains time-bounded and is renewed
only while that authoritative work state remains active.

Settings live in separate UI and `:vmhost` processes. The settings Activity
sends validated AVF, Native-session, and wake-policy values through typed AIDL
setters, so the runtime process does not depend on cross-process
`SharedPreferences` cache invalidation. AVF resource changes remain next-start
configuration; Native TERM/login-shell changes apply to new sessions and wake
policy changes apply immediately.

Termux:API compatibility is a collection of guest command wrappers over the
same `droid` CLI. It intentionally does not introduce a second host bridge or
allow legacy commands to bypass the capability model.

Android share intents are imported by a short-lived UI entry activity into the
VM-specific shared inbox. The activity then requests the foreground runtime but
does not bind to, stop, or otherwise own its VM/session lifecycle.

## Rootless USB

`VmRuntimeService` owns `UsbBroker`. New Rootless PTYs receive host-selected
local socket credentials and the bundled Python CLI. The settings Activity only
approves/revokes requests through AIDL; it never owns connections. See
[USB.md](USB.md) for raw/HID transport, pinned USB serial drivers, explicit port
selection, lifecycle and same-UID isolation limits. Serial driver I/O runs outside
the shared broker lock; device-level driver grants never become a raw FD tunnel.
iPhone pair/info/unpair operations use a service-owned APK native worker with
private in-process usbmuxd sockets; Rootless never receives USB FDs or pair records.

## Terminal image rendering

The shared emulator and view implement bounded SIXEL and kitty graphics,
including animation, Unicode and relative placements. Runtime PTY owners resolve
local media into direct image bytes before UI transport; rendering never opens
guest paths. Native/Rootless retain the resolved bytes in bounded replay, while
AVF resolves media inside the guest session daemon. See
[TERMINAL_GRAPHICS.md](TERMINAL_GRAPHICS.md) for ownership, bounds and compatibility limitations.

## Kitty keyboard and remote window control

The emulator/view own negotiated keyboard encoding. `UnifiedSessionRegistry`
extracts live PTY remote-control frames and dispatches them through a bounded
worker; transcript replay never executes them. `VmRuntimeService` authorizes
the source PTY, restricts targets to the same runtime and uses existing session
operations. Android focus requests only select tabs. UI-created sessions receive
a grant only when the host setting is enabled; remote-created sessions do not.
See [KITTY_PROTOCOL.md](KITTY_PROTOCOL.md) for commands, bounds and desktop limits.
