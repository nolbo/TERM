# Architecture

## Product architecture contract

The product is one terminal application with three explicit execution
environments:

~~~text
                         Terminal application
                                  |
                         Unified session layer
                                  |
           +----------------------+----------------------+
           |                      |                      |
           v                      v                      v
    Direct Native         Rootless Debian          AVF Debian
  Android / Bionic       PRoot / glibc          AVF / crosvm
  no extra privilege     no extra privilege      privileged setup
           |                      |                      |
           +----------------------+----------------------+
                                  |
                     Android capability broker
~~~

The complete zero-special-permission product is:

~~~text
Direct Native + Rootless Debian
~~~

AVF is optional acceleration and full-kernel compatibility. It is not required
for the app to be useful.

Use this trade-off order:

~~~text
zero-setup usability
  > persistent sessions/runtime
  > correct lifecycle ownership
  > security boundary
  > Rootless Linux compatibility
  > Android integration
  > Native package usability
  > AVF acceleration
  > advanced features
~~~

### Lifecycle and sessions

These lifetimes are independent:

~~~text
Android UI lifecycle != runtime lifecycle != terminal session lifecycle
~~~

An Activity, Fragment, Compose screen, tab, WebView, or renderer MUST NOT own a
shell process or VM. UI destruction, recreation, unbinding, or task removal may
detach; it must not intentionally terminate live work.

Session termination requires an explicit user action whose effect is clear. If
a UI control called Close terminates a session, it must make that destructive
effect explicit rather than treating tab disappearance as termination.

All runtimes use a common session model for:

- create and list;
- attach and detach;
- input and output;
- resize;
- rename where supported;
- explicit terminate;
- observable state and errors.

The runtime service or guest session daemon is authoritative for live sessions.
Cached UI state is never authoritative. If the runtime or PTY owner actually
dies, report session loss honestly; do not fake restoration.

AVF creation immediately selects a loading tab identified by a request UUID.
Before Binder connects, the UI keeps only the unsent request and its immutable
profile snapshot; it submits once after connection. Once accepted, `:vmhost`
owns the preparation request and republishes it for returning screens. These
records are separate from live session descriptors and never own a PTY,
renderer, terminal input, running-session count, or VM. A successful session
carries the same request UUID so its loading tab retains its position and
selection; completion must not change a different tab the user selected.
Failures remain in the tab until dismissed. Dismissing a failed request removes
only that request record, without terminating a VM or another session.

Runtime-specific process ownership stays behind Native, Rootless, and AVF
backend interfaces. UI code must not depend directly on PRoot, AVF, crosvm,
vsock, or package-manager implementation details.

### Runtime semantics

Direct Native is a first-class Android/Bionic environment for immediate shell
startup, Android-centric tooling, scripting, SSH, Git, networking, storage
helpers, automation, and bootstrap duties.

Native packages target Android, Bionic, and an app-local prefix. They are not
Debian binary packages. Package support is recipe-driven, automation-first,
tested, and bounded to structurally valuable tools. Do not create a giant
popularity-based package distribution.

Modern Android executable restrictions are an architectural boundary. All
dynamically installed or packaged native executables go through the centralized
NativeExecutableLoader policy. Never pretend a package system is complete if
installed binaries cannot execute under the current target SDK and distribution
model.

Rootless Debian is the primary permissionless broad Linux environment. It uses
an independent PRoot/glibc rootfs and official Debian apt/dpkg. Represent its
ptrace, filesystem, fake-root, mount, namespace, cgroup, systemd, and kernel
limitations honestly.

AVF Debian is a separate, optional real-kernel environment. It requires device
support and privileged/product deployment. Normal APK permission declarations
cannot grant signature permissions. Keep AVF-specific APIs behind the AVF
backend and preserve Native/Rootless operation when AVF is unsupported or not
authorized.

AVF image production is automated on the product side (see `IMAGE_RELEASE.md`); end users do not construct images. After atomically saving an AVF profile with a fresh environment, its environment detail asks whether to install Debian. Deferring leaves the saved profile uninstalled; an explicit install action in environment settings can request installation later. The signed build embeds a generated catalog pinning an immutable HTTPS manifest. WorkManager installs the image in the main app process independently from VM ownership. Installation alone never starts a VM or creates a session. The producer's RO system image is the lower layer of a root overlay whose persistent writable state is on the home disk. Initial installation verifies every artifact, records the manifest hash, and subsequent starts verify immutable artifacts while retaining legitimate writable guest state. Existing images are never replaced by automatic installation.

New factory images run interactive sessions as the non-root `droid` account and allow passwordless `sudo` inside that VM. The session daemon does not set `NoNewPrivileges`, which would prevent its child shells from using setuid sudo. The factory locks password login, installs a root-owned sudoers policy and rejects missing sudo/APT tools or ineffective elevation. Both offline QEMU boots exercise elevation, APT list updates and a local package install/removal on the writable overlay. Guest root remains untrusted at the Android capability boundary. This factory change does not migrate or replace previously installed images.

Session startup never enqueues image installation. When the image path is missing, it waits only for a previously requested storage-specific installation; without one, it reports that the user must install Debian in AVF environment settings. The AVF platform driver's `prepare` step performs full startup verification once before accepting the image for boot, including receipt adoption for fully verified older installations. Existing invalid paths are preserved and rejected at that boundary. Presence and catalog availability checks do not repeat artifact hashing; an install receipt only controls verification of the writable disk, while immutable artifacts are always hashed. Already-ready storage owners are reused when adding sessions. The AOSP driver logs image verification and VM/sessiond connection durations separately.

The AVF image worker persists its current phase, compressed transfer bytes, artifact index and timing in WorkManager progress. The owning AVF environment displays current progress inline in its settings; the terminal may also show a progress dialog. Closing or recreating a progress view never starts, cancels or owns installation. Download percentages describe the current compressed artifact, while verification and installation use indeterminate progress. Completion and failure snapshots are retained in output data after WorkManager clears running progress. Profile installation status comes from the complete regular-file image layout and bounded manifest metadata, not onboarding preferences, a finished worker, or VM readiness. This inexpensive UI check never authorizes boot. Global AVF settings link to environments and forwarded ports without owning installation status. Initial setup retains the legacy/default offline import path.

AVF profile selection always requests that profile's runtime owner, including legacy/default storage when Native is the primary owner. The common runtime selection rule sends explicit AVF requests to the service even before a VM is ready; capability checks remain in the owner. AVF availability reflects the actual primary or independent AVF backends, excluding Native/Rootless readiness. Custom Debian startup waits for a bounded connection to `droid-sessiond` rather than Microdroid's payload-ready callback. The driver retains the accepted descriptor for the session client, preserves platform failure reasons and reconnects existing running instances without replacing their disks. Before starting a stopped VM, it applies the verified configuration with the platform's compatibility check, including `systemd.hostname=localhost` for a short guest hostname. Persistent VM/storage identities and image files remain unchanged; a running guest receives new boot settings only after it stops and starts again. Startup failures remain visible in a dismissible UI dialog; the UI owns no VM lifecycle.

Image-related pushes to `dev` prepare an image candidate and a debug APK embedding its exact factory catalog. Optional publication waits for both jobs, uses public `nolbo/TERM` assets instead of private source release URLs, and verifies unauthenticated public downloads before producing a default pin for review. A committed `image/catalog-approved.properties` is the default for ordinary debug/release builds; an explicit build property overrides it only for that build. Without either, builds retain an empty catalog. Catalog promotion remains a reviewed source change and is never an unsigned runtime update.

Runtime selection is explicit. A failure in one selected runtime MUST NOT
silently redirect the command or session to another runtime.

Rootless and AVF do not share a complete rootfs or dpkg database. Share only
deliberate higher-level state such as workspace data, selected user files,
appropriate dotfiles, and a logical package/environment manifest.

### Android boundary, storage, and power

Linux guests are untrusted. Android integration uses a typed capability broker.
Every request must be authenticated where applicable, schema-validated,
capability-checked, Android-permission-checked, bounded, and optionally confirmed
by the user.

Never expose unrestricted Binder, Context, ContentResolver, private app paths,
raw host file descriptors, host UIDs, or arbitrary Android services to a guest.
Capability grants are separate from Android OS permissions and are scoped to
the host-selected environment or VM identity.

Storage exposure is explicit and minimal. Keep runtime roots and package
databases separate. Share a deliberate workspace rather than the entire
app-private directory.

A foreground service improves survivability but is not a permanent execution
guarantee. Wake locks must be policy-driven, time-bounded where appropriate, and
tied to meaningful work. Represent Android process death and power restrictions
honestly.

### UI contract

The UI presents Android Native, Userspace Linux (the Rootless/PRoot backend), and AVF Linux as explicit
choices with their setup, compatibility, and privilege differences.

UI and settings may be reorganized when architecture or clarity requires it.
Active application screens use the shared Jetpack Compose Material 3 system in
docs/DESIGN_SYSTEM.md. Settings and general application chrome use the deliberate
dark color scheme. Terminal-screen chrome follows the active session palette with
readable contrast. An opt-in appearance setting also derives chrome from the
dominant alternate-screen cell background; this is renderer-only and does not
change session ownership or the palette exposed to terminal applications.

The UI renders state and sends typed commands. It never becomes the hidden
owner of a process, PTY, VM, package operation, or capability grant.

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
`Environment` is the persistent execution identity: where execution happens. It owns a stable UUID `EnvironmentId`, name, Native/Rootless/AVF runtime, exact distribution/guest, storage handle, runtime-specific metadata and creation time. Rootless distribution is an identity field; Debian, Ubuntu and Arch cannot become one environment. AVF metadata owns its persistent VM instance identity. Environment names do not identify paths, profiles or sessions.

`SessionProfile` describes how to enter an environment (shell/arguments, cwd, environment variables, startup command, icon and palette). Its `environmentId` is authoritative. The old runtime selector and `storageId` remain transitional API fields and are normalized from the environment on read/save. Several profiles and sessions can share one environment's rootfs, packages, home, configuration and runtime owner. New/duplicated profiles create independent environments by default; selecting an existing compatible environment explicitly shares it without copying data. Kitty child sessions inherit the source environment.

`SessionLaunchSpec` v2 defensively copies profile settings and includes the immutable Environment value. The service validates environment identity and its persistence handle without resolving through the current editable profile. Environment/Profile edits affect future launches; rename does not alter running launch snapshots. v1 snapshots remain readable, including old `legacy` storage references and pre-upgrade AVF cwd. Terminal checkpoints and renderer ownership are unchanged. UI process recreation reconnects to the service; Native/Rootless owner death still means session loss, while reachable AVF session owners can reconnect using persisted snapshots.

`VmRuntimeService` owns backend instances and preparation state by Environment UUID. An explicit Environment start retains the foreground service until that environment is stopped/deleted, even with no sessions or UI bindings; this does not add an idle wake lock or persistent restart supervisor. The common session registry exposes each session's Environment ID and retains its launch snapshot through reconnection. Existing `(SessionRuntime, storageId)` controller routing and preparation locks remain internal persistence/backend adapters, including the legacy adoption exclusion. No additional runtime supervisor or Activity-owned runtime is introduced.

`EnvironmentRegistry` owns domain metadata, names and profile references on the existing atomic `storage-registry.json` manifest and process-shared lock. On first access it associates existing profiles and orphan storage with UUID environments, grouping exact `(runtime, storageId)` identities, including the Rootless distribution encoded in the old selector. Migration IDs are deterministic, persisted creation times/names are retained, built-in shell defaults remain dynamic and profile deletion markers are preserved. Environment records and saved profile references are written atomically; a fresh environment and its profile share one commit. Existing `storageNames` remain migration input, not the new name source of truth.

`SessionStorage` and `StorageRegistry` remain the internal persistence abstraction: fixed UUID/legacy path resolution, discovery, approximate logical size, safe size/delete walks, legacy adoption, storage tombstones and reference checks. Legacy files, inodes, package databases and VM disks never move. Metadata can be attached while an old owner is live; physical storage identity adoption still waits for the existing idle/reachable-owner proof. Updating an adopted storage handle preserves the Environment ID and AVF `default` VM identity.

Settings manage actual Environment instances, displaying only name, runtime and logical storage size in the list. Details expose distribution, profile/session references, authoritative runtime state, Environment ID and AVF VM/disks/image installation, plus supported start/idle-stop, rename and delete operations. Userspace Linux settings contain only a global DNS policy, PRoot engine status, runtime information and limitations; instance operations belong to Environment details. Environment identifies where execution happens, Profile defines how to enter it, and Session is a running terminal instance. DNS policy has a single runtime-process writer and is read/saved through UI-authorized Binder methods. Every Rootless backend reads that same policy for new sessions and installation/repair commands. Android mode reads the active network DNS server list; custom mode accepts up to three numeric IPv4/IPv6 servers. An engine-managed resolver file is bound over guest `/etc/resolv.conf` without modifying Environment rootfs or home. No public DNS fallback or per-Environment override is introduced. Existing processes may retain resolver caches. PRoot acceleration, faststat, NSS caching, loaders, root emulation, mounts and link strategy remain automatic. Runtime information uses verified APK executable availability, the selected acceleration profile and an actual bounded PRoot version probe; unverifiable values remain unknown. AVF settings contain engine-wide support and policy. Forwarded ports are available only within AVF Linux settings. Global runtime diagnostics and crash history have a single settings-home destination, Diagnostics and reports. Current AVF suspend/resume only changes backend bookkeeping, so no suspend action is exposed as a real platform operation. AVF memory/CPU/network settings remain global defaults applied at the next backend start.

The application uncaught-exception handler archives reports from the UI and runtime processes as individually committed app-private JSON files before delegating to Android. Reports retain timestamps and exception summaries, are limited to the latest 20 records and 65,536 body characters, and are read off the UI thread. The former single pending report is imported idempotently, preserving its body and timestamp when parseable. Viewing a report acknowledges the matching startup notification without deleting history or acknowledging a newer crash. Runtime diagnostic and forwarded-port screens bind only while visible and never stop a runtime when detached.

Environment details also manage installed software independently of Environment deletion. Rootless installation/retry/completion prepares that instance without creating a session; progress remains service-owned across UI detach, and UUID instances have independent temporary download caches. Removing a Rootless distribution preserves its Environment/Profile identity and home/work directory by default, with explicit optional home removal. Removing AVF Debian preserves Environment/Profile identity and the host shared folder but deletes the guest disks and persistent VM instance after the platform verifies it stopped. Active/disconnected sessions and approved image work block removal; installation enqueue and the final removal check share the manifest lock. A persisted image generation invalidates cancelled workers before disk removal, so a stale activation cannot restore removed data. Reinstallation keeps the same Environment and persistence identities. Adopted legacy AVF environments continue observing and waiting for the old installation work name, and that work also blocks removal or a duplicate download.

Deletion removes an environment, its idle runtime owner and storage together. Linked profiles and active sessions must be removed first. Unknown/unreachable runtime state blocks deletion, including an unattached persistent AVF VM after host restart. The service excludes preparation/creation and holds the manifest lock through the final reference check, platform deletion and filesystem removal. Environment and storage identities are tombstoned together to reject stale drafts/launches and prevent image workers recreating deleted data. Walks never follow symlinks, and another environment's roots or legacy shared workspace are never removed. There is no automatic storage/environment garbage collection or general-user storage-only deletion.

Size is lazily measured with a one-minute cache; zero-byte values are not cached. Access-denied descendants produce an explicit partial lower bound without changing guest permissions, while root access or other I/O failures clear the UI measurement.

AVF profiles default to the `droid` account's `/home/droid`. A one-time manifest
upgrade changes saved AVF `/home/user` defaults to that home while preserving
custom directories, storage identities, tombstones, guest files and immutable
live launch snapshots. Later explicit `/home/user` choices remain valid.
AVF deletion also releases the stopped platform instance through its privileged
driver; unsupported providers fail closed. Image workers check the same manifest
under lock before installation so a pending download cannot recreate deleted storage.

**Legacy adoption decision:** keep existing files at fixed Native/Rootless/AVF paths,
and persist only a runtime-to-UUID association in the same registry manifest. Moving
rootfs trees or VM disks would change host paths, the existing shared `/workspace`,
and AVF's persisted configuration. Adoption therefore preserves data, package databases,
inode identity and `droid-default` rather than copying or renaming them. `SessionStorage`
remains a path resolver with fixed allowlisted legacy locations; metadata cannot supply
arbitrary paths. Existing Rootless environments retain the app-level shared workspace,
which deletion of one distribution never removes. Fresh UUID environments retain their
independent workspace and roots.

The service excludes preparation and session creation while committing adoption.
It checks the actual controller rather than a cached session list; active or unverifiable
owners defer adoption and keep immutable running launch snapshots. AVF adoption requires
the primary VM's reachable PTY owner, so an unattached persistent VM remains protected.
After the last session exits or the next bind, the service retries. A failed/interrupted
commit leaves old references and files intact; a committed runtime-to-UUID association
is reused after restart. Built-in shell/preferences remain dynamic and tombstones stay
deleted. The association survives storage deletion so old drafts, download jobs and
legacy requests resolve to the tombstone instead of recreating deleted data. Native
service readiness uses a separate `native-runtime` root and never recreates a deleted
profile environment. The AOSP deletion driver resolves an adopted AVF UUID to its
original `droid-default` instance; public-SDK builds still cannot validate that platform path.

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
Background transitions retain the live UI mirror for a five-second grace period.
After that, invisible UI subscriptions detach and cached mirrors are discarded;
PTYs, runtime terminal state, and background operations continue. Foreground
return installs a complete checkpoint into a fresh mirror before live output. On
resume, appearance is applied at the session's effective font size without an
intermediate default-size resize. Unchanged text size and font settings reuse
the renderer and loaded fonts; changing the custom font list, order or style
reloads fonts. Bundled Google Sans Code is the default and final built-in
fallback; legacy built-in font selections are ignored. Each view owns its cache
lifetime, so a new view loads fonts again (including changes to the contents of
an existing font URI).
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

Ordinary APK builds package `:aosp-avf-runtime` and its AOSP driver. The platform ABI declarations in `:aosp-avf-api` are compile-only and must never be packaged. `TerminalApplication` implements `RuntimeBackendProvider` in both the UI and `:vmhost` processes, preserving Native as the default owner and creating independent AVF owners only for explicit AVF storage selections. The runtime-neutral module has no direct platform API dependency. `android.system.virtualmachine` is an optional device-provided shared library; Android 15+, AVF feature support, both actual permission grants, and a compatible authorized custom-image API remain required. The custom-image ABI is hidden in AOSP and is not made accessible by these declarations or the manifest. OEMs may compile against their exact full framework jar. No reflection or API-policy bypass is used; unsupported devices retain Native/Rootless operation. This replaces the former product-only `AospRuntimeApplication` integration without changing session or protocol ownership. Under the Native default owner, legacy and adopted-default AVF profiles also create a separate AVF backend; `SessionStorage.avfInstanceId` retains `droid-default` and the fixed `vm/default` image path. Legacy adoption recognizes that backend and never replaces it with the Native primary owner.

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
time-bounded, renewable partial wake lock according to the persisted policy and
service-owned sessions/work. Neither component treats a foreground service as an execution
guarantee.

The default wake policy is `ALWAYS_AWAKE` while live sessions or host/guest work retain the service. Quiet SSH connections, idle prompts and detached shell jobs therefore remain protected without shell activity signals or an inactivity lease. Each acquisition remains bounded and renewable; the last session/work exit, runtime failure or stop releases it. Upgrade migrates the previously implicit battery-saving default while preserving explicit user choices. The common terminal settings expose both wake policy and an optional Android battery-optimization exemption. Only the OS-approved exemption permits network and partial wake locks in Doze; the app cannot silently grant it or promise survival against force-stop, memory pressure or manufacturer restrictions.

Wake policy is persisted host-side. `KEEP_ACTIVE_WORK_RUNNING` follows runtime
work rather than UI attachment: live OSC 133 command boundaries (including
Native prompt submission), host-owned setup/start operations, and authenticated
guest `WORK_ACTIVE` reports are active. A shell at a known prompt is idle even
while its session remains RUNNING. Notification preferences do not reset command
work state. Unsupported or overridden shell hooks retain a ten-minute protection
lease from creation or the latest live input/output; replay and session-list
refresh never renew it. This conservative fallback cannot identify a silent
command beyond that lease without boundaries or a guest work report.
Activity detach does not change known work. `WORK_IDLE`, command completion,
owner exit and runtime stop/failure release their corresponding work signals.
Wake acquisitions remain time-bounded and renew on the original nine-minute
deadline while work remains active, including silent long commands.

Guest session, bridge and port-agent connection failures use independent bounded
backoff (2, 4, 8, 15, 30, 60, then 120 seconds). A successful connection, explicit
input/attachment, setup/start or new session resets the relevant retry state;
explicit work interrupts a pending delay without replacing healthy connections.
Retries stop when no running session, pending host operation or guest work
remains. AVF reconnect replaces only the failed transport registration and
reattaches its sessions, preserving sibling controllers and terminal checkpoints.
Port and task notifications remain event-driven.

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

The runtime process maintains authoritative terminal emulators in
`UnifiedSessionRegistry`/`RuntimeTerminalState` from session creation onwards,
including both buffers, retained history, parser/input modes and graphics. It
answers terminal queries independently of UI lifetime. The application-owned
`TerminalSessionStore` subscribes through an ordered checkpoint/output stream.
Brief Activity transitions retain their mirror. Invisible UI releases its
checkpoint/output subscriptions after the grace period; foreground return and
UI-process recreation create a new mirror from complete runtime state and its
saved viewport. No UI detach terminates a session or stops a runtime. Checkpoints use host-private,
read-only file descriptors rather than Binder-sized byte arrays or a bounded raw
transcript. PTY and VM ownership remains behind the runtime controllers. See
[TERMINAL_TUI.md](TERMINAL_TUI.md) for state transfer, lifecycle guarantees and tests.

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
