# Architecture

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
        ├── RootlessSessionController ── packaged PRoot + Debian rootfs
        └── ProtocolSessionClient ── vsock ── droid-sessiond (AVF PTY owner)
```

`VmRuntimeService` uses `android:process=":vmhost"` and
`stopWithTask="false"`. The Activity unregisters its callback and unbinds in
`onStop`; it never sends a stop request as a lifecycle side effect.

`UnifiedSessionRegistry` records the owner runtime of every session and routes
write, resize, attach, detach, and terminate operations through the same API.
Runtime selection is explicit. A failed Rootless or AVF request is never moved
to another backend.

Rootless Debian uses a distinct rootfs and a host-owned shared workspace. The
first Rootless selection automatically installs a checksum-pinned Debian
archive into that rootfs. Its PRoot executable may only be resolved from `ApplicationInfo.nativeLibraryDir`;
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
