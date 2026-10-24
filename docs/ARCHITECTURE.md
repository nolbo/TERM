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
    Direct Native         Rootless Debian          AVF Linux
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
  > Userspace Linux compatibility
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

Stopping an idle Environment releases only that Environment's backend and session registration. AVF transport read, frame-validation and cleanup failures must remain local to that connection; they must not escape as uncaught coroutine failures that kill the shared `:vmhost` process and its Native/Rootless or other AVF owners. Disconnecting never fabricates session exits or clears sibling terminal checkpoints.

Every runtime backend belongs to a Profile-selected Environment. There is no primary/default runtime, app-wide preferred Environment, or implicit runtime startup when binding the UI. The service itself is ready to accept Profile launches without constructing a Native shell controller or VM. A Profile resolves its Environment and starts or reuses only that owner. Service/notification/wake lifetime aggregates live sessions, host operations, per-Environment guest work and explicitly started Environments, including those with no terminal sessions.

AVF session reconnection, Android capability bridges and port observers follow each Environment's owner and work demand. Legacy and UUID storage use the same recovery path; an unrelated owner's readiness never gates them. Bridges have independent connections/backoff and use the host-selected Environment VM identity for capability grants. A successful reconnect reattaches that Environment's running sessions while retaining terminal checkpoints and healthy sibling controllers; a failed connection remains eligible for bounded retry backoff.

Session reconnect holds that Environment's preparation lease through controller registration, a successful owner-specific LIST and all reattachments; a socket alone is not proof of recovery. Failed owner RPCs leave the previous session projection/checkpoints intact and remain eligible for retry. UI Environment projections use cached authoritative descriptors and backend state, rather than blocking on a guest RPC; destructive/idle checks still query and verify the actual owner.

A platform stop/error callback triggers Environment-local reconciliation. Only a successful platform proof of stopped/absent VM and completed sharing teardown permits discarding that owner's controller, session routing, checkpoints and explicit-start demand. A failed proof preserves the owner as unknown. Proven loss emits no fabricated guest exit code and never resets sibling controllers/checkpoints. Its UI error is replayed on a returning screen while `:vmhost` remains alive. A new explicit Profile/Environment start can boot that Environment again, but cannot restore dead guest processes. Error callbacks without stop proof retain the VM Binder reference.

UI checkpoint/output and runtime-observer callbacks use bounded serial delivery queues rather than invoking Binder under the terminal-state lock or runtime event collector. Checkpoints are captured into immutable, read-only, unlinked temporary files before delivery. An overloaded or failed observer is invalidated and its UI registration/mirror reconnects from authoritative state; it cannot pause guest output processing or terminate a PTY. Callback threads, queued bytes, descriptors and observer counts are bounded. See `PROTOCOL.md` for limits and ordering.

Connection diagnostics asynchronously retain metadata in app-private `connection-events.log` and logcat (`TermConnection`), up to 1,000 records and 256 KiB on disk. Recording defaults to enabled, preserving existing behavior. The AVF diagnostic logs Activity reads, enables/disables, searches, copies and clears these fixed files without binding or starting the runtime. A file-backed preference and process/file locks serialize UI, worker and `:vmhost` access. Toggle and deletion barriers reject older queued events, including events queued while disabled; disabling preserves saved history and stops this diagnostic file and tag. The 128-task writer queue is best effort during overload, storage failure or process death, and reports dropped tasks when possible. Events include VM preparation/start/stop/callbacks, channel and session connections/recovery, sharing startup/data loss, image operation phases, permission/API checks and process lifecycle. Wall/monotonic timestamps, PID, stable identities and symbolic codes exclude terminal bytes, commands, exception messages, paths and sharing tokens. Guest sharing failures remain in journald and `/var/log/term-share-failure.json`; this screen does not collect guest journals. Diagnostic write failures never weaken authority checks.

Verified images opt into recoverable sharing (`shareBrokerVersion=2`) and session transport (`sessionResumeVersion=1`). Bounded duplex byte replay retains the same session client/emulator and SSHFS/SFTP handles across raw vsock replacement. This preserves request ordering without re-executing filesystem mutations or terminal input. Full buffers backpressure producers. A 60-second sharing authority lease and root-owned mode-000 underlying mount directories permit failed shares to deny I/O while the VM and unrelated sessions continue. Unproven startup/guards remain VM-fatal; v1 keeps its original failure policy. Session transport recovers for at most 60 seconds. The total session RPC deadline includes sending and response waiting: 10 seconds for legacy streams, 70 seconds for resumable streams so the existing recovery window remains usable. Timeout, send failure or cancellation closes that logical stream and fails its pending requests; a transmitted mutation has an unknown result and is never automatically resent. Socket shutdown precedes descriptor close without flushing, waking blocked native IO. Platform vsock opens have a 10-second attempt limit; at most eight uncancellable platform calls run with no waiting queue, and late descriptors are closed.

After broker-v2 recovery expires, host sharing actors become unavailable and stop retrying, while preserving the VM, other sessions and durable deletion leases. Individual failed folders do not tear down healthy control/other folder actors. The error survives UI detach until a verified new boot; no failed generation is silently remounted or replayed. Legacy broker-v1 VM-fatal failure policy stays intact and requires an explicit verified recovery-component update for v2 behavior. Neither transport promises replay across process/daemon death. Capability receipts, migration and versioned wire details are in `PROTOCOL.md` and `AVF_UPDATES.md`.

### Runtime semantics

Direct Native is a first-class Android/Bionic environment for immediate shell
startup, Android-centric tooling, scripting, SSH, Git, networking, storage
helpers, automation, and bootstrap duties.

Native packages target Android, Bionic, and an app-local prefix. They are not
Debian binary packages. Package support is recipe-driven, automation-first,
tested, and bounded to structurally valuable tools. Do not create a giant
popularity-based package distribution.

The fixed APK Native toolchain includes jq, curl, the OpenSSH client, rg and Vim,
with no Git payload. Its managed PATH contains only links to approved installed
APK executables; CA/Vim resources are versioned data outside Environment home.
Managed shell defaults and user `~/.termrc` are separate, preserving existing
user files and session ownership. See [NATIVE_SHELL.md](NATIVE_SHELL.md).

Modern Android executable restrictions are an architectural boundary. All
dynamically installed or packaged native executables go through the centralized
NativeExecutableLoader policy. Never pretend a package system is complete if
installed binaries cannot execute under the current target SDK and distribution
model.

Rootless Debian is a permissionless broad Linux environment. It uses
an independent PRoot/glibc rootfs and official Debian apt/dpkg. Represent its
ptrace, filesystem, fake-root, mount, namespace, cgroup, systemd, and kernel
limitations honestly.

AVF Linux is a separate, optional real-kernel environment. It requires device
support and privileged/product deployment. Normal APK permission declarations
cannot grant signature permissions. Keep AVF-specific APIs behind the AVF
backend and preserve Native/Rootless operation when AVF is unsupported or not
authorized.

AVF supports Debian, Ubuntu 24.04 LTS and Arch Linux ARM as immutable Environment distribution identities on the same AVF backend. The selected distribution determines the signed catalog entry and is checked against the verified manifest before installation and VM preparation. The root-disk/session/Android capability layers are shared. New Debian, Ubuntu and Arch image production requires a matched container-capable kernel/module bundle under [AVF_CONTAINER_KERNEL.md](AVF_CONTAINER_KERNEL.md); package refresh uses APT for Debian/Ubuntu and pacman for Arch. The existing Debian default catalog, WorkManager class/work IDs, wire opcode and pending/completed initialization receipts remain compatible. Ubuntu and Arch require separately produced, smoke-verified and published catalog pins; an absent pin is shown as unavailable, with no fallback to Debian. See [AVF_DISTRIBUTIONS.md](AVF_DISTRIBUTIONS.md).

AVF image production is automated on the product side (see `IMAGE_RELEASE.md`); end users do not construct images. After atomically saving an AVF profile with a fresh environment, its environment detail asks whether to install the selected Linux distribution. Deferring leaves the saved profile uninstalled; an explicit install action in environment settings can request installation later. The signed build embeds a generated catalog pinning an immutable HTTPS manifest. WorkManager installs the image in the main app process independently from VM ownership. Explicit online installation includes a service-owned initial VM boot and a distribution-selected package-index refresh after image verification; it creates no terminal session. WorkManager owns the persisted installation attempt, while `:vmhost` owns the VM and the environment storage lease. New producer images use format v2: `home.img` is a complete writable EXT4 root on vdb, while the RO `system.img` remains the immutable baseline on vda. Installed v1 OverlayFS guests retain their original boot path until an explicit full-image transition; an APK update alone never converts their disks. Initial installation verifies every artifact, records the manifest hash, and subsequent starts verify immutable artifacts while retaining legitimate writable guest state. Existing images are never replaced by automatic installation.

New factory images run interactive sessions as the non-root `droid` account and allow passwordless `sudo` inside that VM. The session daemon does not set `NoNewPrivileges`, which would prevent its child shells from using setuid sudo. The factory locks password login, installs a root-owned sudoers policy and rejects missing sudo/package tools or ineffective elevation. Both offline QEMU boots exercise elevation, package-index updates and a local package install/removal on the writable EXT4 root. New factory manifests declare `packageInitializationVersion=1` (legacy Debian `aptInitializationVersion=1` remains supported); online installation requests initialization only for the verified package/legacy APT capability, while older manifests keep their existing file-only installation behavior. The factory removes cached Packages/Sources/translation indexes, partial downloads and binary/package caches while preserving Release/InRelease signatures, sources, keys and dpkg state. Both boots also exercise the production initializer against a temporary local repository, rejecting repository failures and checking successful/idempotent completion; test state is removed before release hashes. The dedicated host-CID-only APT endpoint executes one fixed guest maintenance script with PDiffs disabled for the initial refresh, strict repository-error handling and bounded runtime. Indexes and the guest success receipt are flushed before host completion. Guest root remains untrusted at the Android capability boundary. This factory change does not migrate or replace previously installed images.

A launch joining an approved installation waits only for atomic image activation while holding its environment lease, then performs or joins pending package initialization itself. It never waits for a worker blocked on that same lease. Initialization-only boots are stopped after refresh; a VM reused from an earlier owner or handed to a terminal/explicit start is retained. Cancellation closes only the initialization connection and terminates its guest updater process group. Ordinary session requests never enqueue image installation. Profile selection checks the selected Environment and asks for consent if its image is missing. The host UI may then submit a launch through the explicit installation-consent Binder method; the service validates the caller, enqueues that storage-specific installation and waits before starting the session. Requests without consent only wait for previously approved work; otherwise they report that installation is required. The AVF platform driver's `prepare` step performs full startup verification once before accepting the image for boot, including receipt adoption for fully verified older installations. Existing invalid paths are preserved and rejected at that boundary. Presence and catalog availability checks do not repeat artifact hashing; an install receipt only controls verification of the writable disk, while immutable artifacts are always hashed. Already-ready storage owners are reused when adding sessions. The AOSP driver logs image verification and VM/sessiond connection durations separately.

The AVF image worker retains bounded compressed/raw staging per work ID across retry, resumes compressed transfers only with matching strong HTTP validators and ranges, and rehashes raw files before reuse. Stream checksums do not replace the installer/service ownership-boundary verification or the driver's startup verification. Update copies skip filesystem-reported holes when supported and always retain independent writable state; unsupported filesystems use the bounded zero-aware fallback. See `AVF_UPDATES.md` for cache cleanup and compatibility.

The AVF image worker persists its current phase, compressed transfer bytes, artifact index and timing in WorkManager progress. Progress includes initial VM boot and package refresh after file installation. A separate manifest-bound initialization receipt records successful refresh time; failed/cancelled refresh preserves verified disks and remains retryable without downloading them again. The owning AVF environment displays current progress inline in its settings; the terminal may also show a progress dialog. Closing or recreating a progress view never starts, cancels or owns installation. Download percentages describe the current compressed artifact, while verification and installation use indeterminate progress. Completion and failure snapshots are retained in output data after WorkManager clears running progress. Profile installation status comes from the complete regular-file image layout and bounded manifest metadata, not onboarding preferences, a finished worker, or VM readiness. This inexpensive UI check never authorizes boot. Global AVF settings link to environments and forwarded ports without owning installation status. Initial setup offers only terminal startup; AVF image installation is managed through execution-environment settings.

AVF profile selection always requests that profile's runtime owner, including legacy/adopted storage. The common runtime selection rule sends explicit AVF requests to the service even before a VM is ready; capability checks remain in the owner. AVF availability reflects actual AVF Environment owners, independently from Native/Rootless readiness. Custom Linux startup waits for a bounded connection to `droid-sessiond` rather than Microdroid's payload-ready callback. The driver retains the accepted descriptor for the session client, preserves platform failure reasons and reconnects existing running instances without replacing their disks. Before starting a stopped VM, it applies the verified configuration with the platform's compatibility check, including `systemd.hostname=localhost` for a short guest hostname. Persistent VM/storage identities and image files remain unchanged; a running guest receives new boot settings only after it stops and starts again. Startup failures are presented only in one dismissible central UI dialog, without duplicate snackbar, empty-screen or pane error text. Operation callbacks and failed-launch snapshots share request-ID deduplication; recreation preserves the open dialog and acknowledged requests. Closing the dialog does not terminate or remove the failed launch. The UI owns no VM lifecycle.

Image-related pushes to `dev` prepare an image candidate and a debug APK embedding its exact factory catalog. Optional publication waits for both jobs, uses public `nolbo/TERM` assets instead of private source release URLs, and verifies unauthenticated public downloads before producing a default pin for review. A committed `image/catalog-approved.properties` is the default for ordinary debug/release builds; an explicit build property overrides it only for that build. Without either, builds retain an empty catalog. Catalog promotion remains a reviewed source change and is never an unsigned runtime update.

Runtime selection is explicit. A failure in one selected runtime MUST NOT
silently redirect the command or session to another runtime.

Explicit AVF component/full-image updates use an isolated service-owned VM with
copied state, then a separate normal candidate boot and health/sync check.
The Environment first checks installed base/component metadata against its
APK-pinned target. A differing supported target exposes one action: components
when kernels and root formats match, otherwise a full image transition. Checks fetch
at most the pinned manifest, never disks or an unsigned replacement catalog.
Generation-fenced activation retains matching old base/state for rollback.
Ordinary installation still refuses replacement; session startup never requests
updates. Migration scope, package authentication and recovery are specified in
[AVF_UPDATES.md](AVF_UPDATES.md).

Explicit installation cancellation targets the observed AVF WorkManager attempt or the selected Rootless backend's preparation job. Rootless preparation owns a separate child job, retains its lease through process and staging cleanup, and never cancels a live session or another environment. Downloads, hashing, extraction and AVF image copying check cancellation before promotion. Cancelled Rootless package preparation retains the activated rootfs, home and any pre-existing workspace data; retry uses the existing package recovery path. Closing or recreating UI still only detaches observation.

Rootless and AVF do not share a complete rootfs or dpkg database. Share only
deliberate higher-level state such as shared directory data, selected user files,
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
databases separate. Expose only explicitly connected shared directories rather than the entire
app-private directory. Logical Workspaces never expose filesystem paths.

Stable TERM and TERM Preview use distinct Android application IDs and independent
app-private settings, profiles, environments and runtime services. They do not
share an Android UID or automatically migrate data. User-selected shared folders
can still refer to the same external files in both apps.

AVF raw disks retain their published logical lengths and SHA-256 hashes while
installation omits writes of zero-filled 4 KiB blocks. Online downloads and
offline imports use owned sibling staging directories; verification and receipt
creation precede the fenced rename into place, avoiding a second complete disk
copy. WorkManager staging names include their work ID so retries can remove
their own partial files and finished/pruned attempts without deleting active
downloads, offline imports or installed environments. Cancellation and failure
clean up owned staging; package initialization failures retain activated disks.
AVF environment usage reports allocated filesystem blocks rather than raw disk
capacity. This changes new installation allocation, not existing guest data.

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
readable contrast. An opt-in appearance setting prioritizes an explicit OSC background.
Otherwise, it retains the whole-display erase background while that color remains
visible in bounded screen samples. Once absent, a consistent cell-painted theme,
including a return to the default background, can replace it on the next output
frame without another whole-display erase. Mixed screens or a default-colored gutter
fall back to the erase background, or the session palette when no erase hint exists.
OSC declarations and whole-display erase styles survive terminal checkpoints;
renderer inference never rewrites them. A uniform full-screen highlight cannot be
distinguished from a theme repaint using cell colors alone. This is renderer-only
and does not change session ownership or the palette exposed to terminal applications.
While this option is enabled in the alternate screen, terminal-toolbar chrome uses
Material Color Utilities' Expressive dynamic roles seeded by the inferred background.
The toolbar scopes its generated Material color scheme to its descendants, retaining
the exact canvas background and readable role contrast; settings keep the app scheme.
Disabling the option or leaving the alternate screen restores the existing chrome.

The UI renders state and sends typed commands. It never becomes the hidden
owner of a process, PTY, VM, package operation, or capability grant.

The terminal screen supports at most two pane slots with independent `TerminalView`
renderers over the existing `TerminalSessionStore` mirrors. `TerminalPaneState`
stores only session IDs, focused slot, split direction and ratio per logical
Workspace (including unassigned sessions). Runtime/Environment differences are
allowed within a Workspace; duplicate IDs and mixed Workspace membership are not.
The focused pane determines the existing selected tab, input/IME, hotkeys, command
history and toolbar palette. The UI retains one split pair per Workspace while an
unrelated tab is viewed alone: selecting a normal tab opens a single renderer, and
selecting either paired tab restores the pair with its direction and ratio. The
optional persisted solo selection is separate from the paired IDs/focus; only the
projected visible IDs determine renderers and PTY geometry. Explicit split actions
replace the saved pair. Pane replacement, unsplitting and Workspace changes
only detach presentation; explicit tab termination retains its existing PTY command.
Authoritative session/membership updates validate saved layouts and collapse an
invalid pair to a surviving session. Activity restoration never creates sessions.
`TerminalPaneLayout` remains the stable native owner of the renderer slots. Its
internal `ComposeView` uses Material 3 Adaptive 1.3.0 `SupportingPaneScaffold`,
with rectangular split-pane surfaces and a central Material drag handle; the
single terminal retains its full rectangular bounds without added outer margins.
The handle uses theme spatial motion to expand while dragging and return on
release or cancellation, without changing pane geometry or the touch region.
The official scaffold override preserves both split axes, the existing 20–80%
ratio and minimum extents, since default vertical reflow requires the first pane
to occupy at least half the area. Axis and focus changes retain the same native
slot containers. Renderers bind only after actual, positive layout; lookahead
geometry never drives binding or native hit testing. The native host alone
handles touch resizing, while the Compose handle exposes the range and progress
accessibility action. Neither presentation layer acquires runtime ownership.
Beyond the normal ratio/minimum bounds, the visible divider follows the raw drag
with a slightly underdamped spring. Start/end-anchored axis scaling fills both
panes while their native measured extents stay bounded; animation frames never
resize PTYs. Cancellation springs presentation back, and a completed collapse
immediately removes the transform while keeping the opposite session focused.
Each renderer submits its own settled, positive geometry; identical pending
geometry is coalesced per remote backend. PTY, Environment and emulator ownership
remains in `:vmhost`/`UnifiedSessionRegistry`.

## Local services and port ownership

`LocalServicesActivity` projects the `:vmhost`-owned `LocalServiceManager`/`PortForwardRegistry`; it never starts, prepares, stops or owns an Environment, session, listener or VM. The service exposes appended, authenticated host-UI Binder methods and an invalidation callback. Visible Activities reread authoritative snapshots after invalidation/reconnection and unregister on stop; the dashboard adds no persistent polling service. The dashboard is opened from AVF Linux settings and exposes only AVF services. Explicit AVF mappings use a bounded app-private preference record and survive Activity/runtime recreation. Retired Native/Rootless manual registrations are ignored when loading older preferences. Live AVF observations are not restored as active after owner death.

Services have a stable ID, an owning AVF Environment and optional session association, numeric source endpoint, TCP transport, optional user-specified HTTP/HTTPS, provenance, observation, forwarding state and errors. AVF IDs include the host-selected Environment, address and port; identical ports in different VMs never merge. Native and Userspace Linux service registration is not provided. No arbitrary Android socket enumeration, process killing, port scanning, health check or CLI registration is implemented. A CLI path needs a separately scoped authenticated host-broker operation; no stub command is shipped.

One event-driven port observer and forwarding driver belong to each active AVF owner. The existing reconnect backoff is reused only while that Environment has sessions, explicit-start ownership or pending host work. Disconnect/stop closes only that Environment's TERM listeners and connections and marks surviving observations unknown; a completed snapshot or explicit close confirms listener removal. A bound Android proxy is recorded separately from discovery and never claims guest application health. Port conflicts stay visible without displacing another owner. Explicit host-port changes bind a candidate and persist its mapping before replacing the old listener; either failure keeps the old mapping. All Android binds are IPv4 loopback only, with bounded service and connection counts.

Inspection found that the previous implementation connected a reported TCP port directly to the same-numbered vsock port without a guest TCP bridge. Address-aware agent extensions and a host-CID-only proxy on 40504 complete that missing path. Older agents retain their legacy report format but are honestly shown as discovery-only/forwarding unavailable. Existing images are not replaced or migrated automatically; new agent/proxy support requires an image built with the new guest source. This preserves runtime/session and wire-envelope compatibility instead of treating a successful host bind as proof that the old TCP path worked.

## Native / Rootless iPhone tool environment

The iPhone transport only claims the active USB configuration; changing it requires
the separate device-wide broker capability, including for trusted tools. The host
performs pairing placement through a verified staging file and one AFC rename,
with no destructive replacement fallback. These are failure-containment measures,
not an iOS transaction or crash-recovery journal; see IPHONE_TOOLING.md for
protocol and physical-validation limitations.

The app provides a shared typed USB CLI capability, not an IPA installer screen.
Native's packaged Bionic CLI and Rootless's Python CLI call the same
session-scoped broker. `:vmhost` leases the physical device and owns the USB/mux
worker and registered host-tool processes independently of Activities. Only the
trusted mux owner receives USB; the probe receives a private anonymous
multi-stream connector. Guests receive neither. The only registered APK host
tool is the device-scoped iPhone probe. See [IPHONE_TOOLING.md](IPHONE_TOOLING.md)
for the plan and extension rules.

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
Completion alerts are suppressed before rate limiting for the selected session of
a resumed, window-focused terminal Activity. The UI reports this transient focus
per registered Binder listener; pause, window focus loss, unregister and listener
death clear it. Other sessions and explicit attention requests still notify, and
completion continues to update work state even when its alert is suppressed.
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
Profile display order is an app-private UI preference keyed by stable profile IDs,
separate from Environment/Profile domain metadata. Management and both new-session
pickers read the same order. Deleted IDs are ignored; after a custom order is
saved, new profiles append. Reordering visible profiles retains the slots of
privilege-hidden defaults.
Profile icons use a stable `auto`, built-in key, or validated content-addressed image
identity in both the profile and launch snapshot. Missing keys retain automatic
runtime icons. The UI retains previously imported images as immutable normalized app-private PNGs and
no longer offers new image imports in profile icon settings; no URI grants, arbitrary
paths or image bytes enter Binder or the runtime owner. Duplicates retain the icon identity and files are not removed when a
profile changes or is deleted. Tabs use their launch snapshot, not the mutable profile.
All icon surfaces share the same tint/fallback renderer; cached decoding is off the UI
thread. Missing imported files fall back to the snapshot runtime's default icon.
`Environment` is the persistent execution identity: where execution happens. It owns a stable UUID `EnvironmentId`, name, Native/Rootless/AVF runtime, exact distribution/guest, storage handle, runtime-specific metadata, creation time and a host-owned internal-storage export choice. Rootless distribution is an identity field; Debian, Ubuntu and Arch cannot become one environment. AVF metadata owns its persistent VM instance identity. Environment names do not identify paths, profiles or sessions.

`SessionProfile` describes how to enter an environment (shell/arguments, cwd, environment variables, startup command, icon and palette). Its `environmentId` is authoritative. The old runtime selector and `storageId` remain transitional API fields and are normalized from the environment on read/save. Several profiles and sessions can share one environment's rootfs, packages, home, configuration and runtime owner. New profiles create independent environments by default; the profile duplication screen defaults to the source environment. Users can select a new independent environment or share an existing compatible one without copying data. Kitty child sessions inherit the source environment.

`SessionLaunchSpec` v2 defensively copies profile settings and includes the immutable Environment value. The service validates environment identity and its persistence handle without resolving through the current editable profile. Environment/Profile edits affect future launches; rename does not alter running launch snapshots. v1 snapshots remain readable, including old `legacy` storage references and pre-upgrade AVF cwd. Terminal checkpoints and renderer ownership are unchanged. UI process recreation reconnects to the service; Native/Rootless owner death still means session loss, while reachable AVF session owners can reconnect using persisted snapshots.

`VmRuntimeService` owns backend instances and preparation state by Environment UUID. An explicit Environment start retains the foreground service until that environment is stopped/deleted, even with no sessions or UI bindings; this does not add an idle wake lock or persistent restart supervisor. The common session registry exposes each session's Environment ID and retains its launch snapshot through reconnection. Existing `(SessionRuntime, storageId)` controller routing and preparation locks remain internal persistence/backend adapters, including the legacy adoption exclusion. Launch identity resolution, preparation and session creation share only that storage's lease; installation or image readiness waits never hold a global migration mutex. Environment information reads do not await physical legacy adoption. No additional runtime supervisor or Activity-owned runtime is introduced.

`EnvironmentRegistry` owns domain metadata, names and profile references on the existing atomic `storage-registry.json` manifest and process-shared lock. On first access it associates existing profiles and orphan storage with UUID environments, grouping exact `(runtime, storageId)` identities, including the Rootless distribution encoded in the old selector. Migration IDs are deterministic, persisted creation times/names are retained, built-in shell defaults remain dynamic and profile deletion markers are preserved. Environment records and saved profile references are written atomically; a fresh environment and its profile share one commit. Existing `storageNames` remain migration input, not the new name source of truth.

`SessionStorage` and `StorageRegistry` remain the internal persistence abstraction: fixed UUID/legacy path resolution, discovery, approximate logical size, safe size/delete walks, legacy adoption, storage tombstones and reference checks. Legacy files, inodes, package databases and VM disks never move. Metadata can be attached while an old owner is live; physical storage identity adoption still waits for the existing idle/reachable-owner proof. Updating an adopted storage handle preserves the Environment ID and AVF `default` VM identity.

Settings manage actual Environment instances, displaying only name, runtime and logical storage size in the list. Details expose distribution, profile/session references, authoritative runtime state, Environment ID and AVF VM/disks/image installation, plus supported start/idle-stop, rename and delete operations. Userspace Linux settings contain only a global DNS policy; engine information and limitations belong to the independent Userspace Linux diagnostic Activity, while instance operations belong to Environment details. Environment identifies where execution happens, Profile defines how to enter it, and Session is a running terminal instance. DNS policy has a single runtime-process writer and is read/saved through UI-authorized Binder methods. Every Rootless backend reads that same policy for new sessions and installation/repair commands. Android mode reads the active network DNS server list; custom mode accepts up to three numeric IPv4/IPv6 servers. An engine-managed resolver file is bound over guest `/etc/resolv.conf` without modifying Environment rootfs or home. No public DNS fallback or per-Environment override is introduced. Existing processes may retain resolver caches. PRoot acceleration, faststat, NSS caching, loaders, root emulation, mounts and link strategy remain automatic. Runtime information uses verified APK executable availability, the selected acceleration profile and an actual bounded PRoot version probe; unverifiable values remain unknown. AVF settings contain engine-wide support and policy. Local services and forwarded ports are available only within AVF Linux settings. Diagnostics and reports is a settings-home entry point for independent Native, Userspace Linux, AVF Linux, Memory and Crash Reports Activities. Compatibility diagnostics do not observe global runtime/session/image state or start runtime owners. AVF inspection gates optional APIs behind actual prerequisites and never creates a driver instance or VM. Memory inspection observes only an existing :vmhost service, sums UI/runtime PSS without substituting missing values, and measures only while resumed. Session RSS is requested for one session through Session Information in the session tool. Saved crash report format and retention are unchanged. Current AVF suspend/resume only changes backend bookkeeping, so no suspend action is exposed as a real platform operation. AVF memory/CPU/network settings remain global defaults applied at the next backend start.

The application uncaught-exception handler archives reports from the UI and runtime processes as individually committed app-private JSON files before delegating to Android. Reports retain timestamps and exception summaries, are limited to the latest 20 records and 65,536 body characters, and are read off the UI thread. The former single pending report is imported idempotently, preserving its body and timestamp when parseable. Viewing a report acknowledges the matching startup notification without deleting history or acknowledging a newer crash. Runtime diagnostic and forwarded-port screens bind only while visible and never stop a runtime when detached.

Environment details also manage installed software independently of Environment deletion. Rootless installation/retry/completion prepares that instance without creating a session; progress remains service-owned across UI detach, and UUID instances have independent temporary download caches. Removing a Rootless distribution preserves its Environment/Profile identity and home/work directory by default, with explicit optional home removal. Removing AVF Debian preserves Environment/Profile identity and the host shared directory but deletes the guest disks and persistent VM instance after the platform verifies it stopped. Active/disconnected sessions and approved image work block removal; installation enqueue and the final removal check share the manifest lock. AVF copying, hashing and progress publication run outside that lock; only final generation validation and promotion of the verified sibling directory hold it, keeping Environment/Profile metadata available during installation. A persisted image generation invalidates cancelled workers before disk removal, so a stale activation cannot restore removed data. Reinstallation keeps the same Environment and persistence identities. Adopted legacy AVF environments continue observing and waiting for the old installation work name, and that work also blocks removal or a duplicate download.

Deletion removes an environment, its idle runtime owner and storage together, using the same distribution/Debian removal procedure as software removal before deleting identity metadata. Rootless removal also clears the selected environment's installation cache and handles read-only distribution directories; full deletion additionally removes its home, while an existing UUID workspace remains at its original path and is excluded from storage deletion. Workspace links that could depend on rootfs data and interrupted storage migrations block deletion before any software removal. AVF removal deletes the Debian disks and persistent VM instance, invalidates stale image activation while host shared directories and legacy host VM folders remain independently stored. Linked profiles and active sessions must be removed first; active installation/management and pending legacy adoption block deletion. Unknown/unreachable runtime state blocks deletion, except that an unattached AVF environment can use the privileged platform's persisted-VM query to prove it stopped or absent before deleting any files. The service excludes preparation/creation and holds the manifest lock through the final reference and installation checks, platform deletion and filesystem removal. Environment and storage identities are tombstoned together to reject stale drafts/launches and prevent image workers recreating deleted data. Walks never follow symlinks, and another environment's roots, installation caches or legacy shared workspace are never removed. There is no automatic storage/environment garbage collection or general-user storage-only deletion.

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
arbitrary paths. Existing app-level and UUID workspace data remains intact, but new sessions no longer bind it to `/workspace`. New installations do not create that host directory. Logical Workspace membership never changes these storage handles or roots.

Environment backup/restore is owned by `EnvironmentBackupJobs` in `:vmhost`, independently of UI lifetime. Native/Rootless Data backups contain home configuration plus explicitly selected Profiles/Workspace; Rootless Full backups additionally contain the matching rootfs and package database. Exports hold the existing storage preparation leases, require no live sessions or writers, and never stop processes. Including legacy shared Workspace checks and leases all its Rootless users. Independent Environment storage remains usable.

The versioned `.termbackup` stream preserves file/link and Rootless storage semantics, verifies bounded extraction and optionally uses password-authenticated encryption. Restore always stages and validates before allocating fresh Environment/Storage/Profile identities. Pending reservations are hidden in the existing process-shared manifest; atomic tree promotion and one registry commit activate the new Environment. Recovery removes only unpublished reservations, and completion receipts protect already committed restores. AVF, overwriting and scheduled/incremental backups are excluded. See [BACKUP_RESTORE.md](BACKUP_RESTORE.md) for the format, compatibility, crash recovery and storage boundaries.

The service excludes preparation and session creation while committing adoption.
Adoption uses a non-waiting exclusive attempt and defers while storage work is active,
so maintenance never queues behind an installation and blocks unrelated launches.
It checks the actual controller rather than a cached session list; active or unverifiable
owners defer adoption and keep immutable running launch snapshots. AVF adoption requires
that Environment VM's reachable PTY owner, so an unattached persistent VM remains protected.
The service retries on session-launch and session-exit maintenance paths. A failed/interrupted
commit leaves old references and files intact; a committed runtime-to-UUID association
is reused after restart. Built-in shell/preferences remain dynamic and tombstones stay
deleted. The association survives storage deletion so old drafts, download jobs and
legacy requests resolve to the tombstone instead of recreating deleted data. Binding the service allocates no runtime backend or `native-runtime` readiness root,
and never recreates a deleted profile environment. The AOSP deletion driver resolves an adopted AVF UUID to its
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
lifetime, so a new view loads fonts again. Custom font documents are validated
and copied atomically to app-private persistent storage before new selections
are saved. Legacy URI-only selections are copied on their first successful load
without changing their order or style. These copies survive app updates and
source deletion or loss of provider access; selecting a source again refreshes
its copy and invalidates loaded font caches. Failed loads are not cached as
complete, so a later appearance application retries unavailable entries. A
stored copy is removed only after its URI is absent from both style lists.
Selecting a cached tab still attaches that exact session to the runtime
transport, while closing a background tab leaves the active attachment
unchanged. UI unbinding detaches only the session actually attached to that
client; it never iterates sessions as though every cached tab were attached.
Closing one PTY may interrupt that PTY's blocking output read. Controllers
contain this expected session-local I/O completion so it cannot escape a
coroutine and terminate the shared `:vmhost` process or sibling sessions.

Rootless Debian, Ubuntu and Arch Linux ARM each use a distinct rootfs and home,
and can explicitly connect independent host-owned shared directories. The service owns one backend/controller per
distribution and the registry routes by the explicit host-local selection. The
first selection of an uninstalled Environment asks for consent before installing
that distribution's checksum-pinned archive into its rootfs. Ordinary session
requests recheck the usable base image under the storage lease and cannot
bootstrap a missing or incomplete rootfs without explicit consent. Debian's existing paths and wire identity are unchanged.
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

Ordinary APK builds package `:aosp-avf-runtime` and its AOSP driver. The platform ABI declarations in `:aosp-avf-api` are compile-only and must never be packaged. `TerminalApplication` implements `RuntimeBackendProvider` in both the UI and `:vmhost` processes, creating AVF owners only for explicitly requested Environments; Native and Rootless owners are also allocated only for their selected Environments. The runtime-neutral module has no direct platform API dependency. `android.system.virtualmachine` is an optional device-provided shared library; Android 15+, AVF feature support, both actual permission grants, and a compatible authorized custom-image API remain required. The custom-image ABI is hidden in AOSP and is not made accessible by these declarations or the manifest. OEMs may compile against their exact full framework jar. No reflection or API-policy bypass is used; unsupported devices retain Native/Rootless operation. This replaces the former product-only `AospRuntimeApplication` integration without changing session or protocol ownership. Legacy and adopted AVF profiles use the same Environment-owned AVF backend factory; `SessionStorage.avfInstanceId` retains `droid-default` and the fixed `vm/default` image path. Legacy adoption retains that Environment owner and its VM identity.

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

Shell command history metadata belongs to the main terminal buffer, alongside retained cells. OSC 133 prompt/input/execution/completion boundaries attach logical cell anchors, start time and completion status; command strings are derived from retained cells rather than persisted in a second history. Trim invalidates anchors before ring-row reuse; resize/reflow remaps them. The anchor graph and pending shell boundaries travel in the same host-private checkpoint, so detached sessions continue recording independently of the optional command-history toolbar tool. The tool reads the UI mirror and performs local viewport jumps; it never owns runtime subscriptions or PTYs.

For Bash shells reached through `su` or SSH without OSC hooks, command history also recognizes Readline's bracketed-paste enable/disable boundaries paired with a visible shell prompt and echoed submission newline. Plain output, hidden authentication input and application-owned screens do not create fallback markers. A following primary prompt ends the previous output range; default `> ` continuation prompts extend its input range. Explicit OSC boundaries take precedence. These fallback records have an unknown exit code unless an explicit completion frame supplies one; they do not change task notifications or wake-state detection. Shells with bracketed paste disabled or unrecognized custom prompts still require OSC integration.

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

## Workspace Manager and Shared Directory

Workspace order is the persisted `State.workspaces` array order, owned by the runtime registry and shared by settings, the selector and move destinations. The host-only `reorderWorkspaces` Binder operation accepts a bounded UUID list; duplicate or malformed IDs are rejected, deleted IDs are ignored and newly created groups remain appended. Reordering preserves memberships, selection, launch snapshots and every Workspace field. Updating or promoting an existing group retains its position, while new groups append. Settings use the same measured drag/accessibility component as Profiles, save on drop, discard cancelled previews and restore edit mode across recreation. No session or filesystem lifecycle operation runs.

Workspace launch names are optional and remain bounded to 128 printable characters. Lists and launch pickers show the referenced Profile name for unnamed configurations; missing-profile rows use a generic configuration label and remain disabled for launch. Resolving a named Workspace launch snapshots its configuration name as the optional `SessionLaunchSpec.tabTitle`, independently of the referenced profile name. The runtime snapshot pipeline applies that label as an explicit custom title before the first session publication; subsequent user renames take precedence. Shell OSC titles remain automatic subtitles. AVF loading/failure tabs carry the same custom title, and serialization plus Environment resolution preserve it through recreation and preparation. Unnamed Workspace launches and ordinary profile launches preserve normal Profile resolution without introducing a custom title. Moving an existing session into a Workspace does not rename it.

Each launch configuration optionally persists `startWithWorkspace` (absent means false). Opening an empty Workspace queues all selected configurations in their saved order as immutable launch snapshots. The empty permanent Workspace header also exposes an explicit Run default sessions action that refreshes runtime membership and reuses the same queue. The action is disabled when session state is unknown or no startup configurations are selected; temporary Workspaces and the unassigned filter do not expose it. Existing or pending memberships suppress the entire startup batch, so returning to an occupied Workspace never replaces or duplicates sessions. Native/Rootless and AVF accepted creation requests occupy their Workspace in the runtime projection while preparing. The UI serializes pending startup snapshots across recreation, preserves each target Workspace even after filter changes, and submits through the normal installation-consent and Binder creation paths. Installing environments still require explicit consent; cancelling one confirmation skips that configuration and leaves later choices queued. Reconnection, rendering an empty filter, session exit and Close all do not independently enqueue startup work. Manually selecting a new session cancels any startup snapshots still waiting in the UI.

See [SHARED_FOLDERS.md](SHARED_FOLDERS.md) for retirement, preserved data paths, transfer limits and compatibility.

A Workspace is a logical, optional session group. A session has at most one membership; the selector exposes unassigned sessions and registered Workspaces, with no all-session item. Ordinary app launches, launcher re-entry, UI recreation and service reconnection restore the runtime registry's persisted selected filter and the last active tab in that filter. Background expiry releases UI mirrors without resetting selection or memberships. A fresh registry starts with `unassigned`. Explicit Workspace selection/launch and session-notification targets still change the filter to their requested destination. Restoring a saved empty Workspace does not independently enqueue its startup configurations. The persisted legacy `all` selection also resolves to `unassigned` without changing groups or memberships; the old token remains accepted for compatibility. Session notifications select the target session's own Workspace or the unassigned filter. `WorkspaceRegistry` has a single runtime-process writer and persists names, built-in icon identities, permanent/temporary status, memberships, launch configurations and the selected filter through host-UI Binder calls. Missing icon metadata defaults to `team_dashboard` without changing existing UUIDs or references; accepted icons use the same stable, path-free `BuiltInIcon` catalog as Profiles. The shared built-in catalog includes existing runtime/app vectors and additional Material Symbols; Profile-only content-addressed image imports remain outside Workspace metadata. Workspace `auto` renders the default dashboard, while Profile `auto` continues to resolve the launch runtime. Activities retain only projection/selection state and unsaved editor drafts. Workspace list, editor and launch editor use separate non-exported Activities; the icon setting opens a modal inside the Workspace editor. Both Profile and Workspace icon modals label their apply action Confirm and modify only the parent draft; cancellation and dismissal preserve it, and modal state and selection survive recreation. Child Activity results also modify only the parent draft; the Workspace editor's whole-page Save writes through the existing runtime Binder path. Returning from a child or reconnecting must not overwrite that draft with saved metadata. Moving sessions, promotion and Workspace deletion never signal PTYs or call a backend lifecycle operation. Selecting a Workspace can submit its explicitly opted-in startup configurations through the existing session creation path. Deletion removes memberships and preserves sessions, Profiles, Environments and files. Empty filters keep the sessions toolbar accessible; automatic creation requires saved startup configurations on explicit Workspace entry. The expanded toolbar's explicit Close all action follows the session-close confirmation preference and uses the existing per-session termination path. It captures eligible IDs in the selected Workspace or unassigned filter, rechecks membership against runtime-owned metadata when confirmed, and excludes sessions created afterward or moved outside that scope. It never deletes Workspace, Environment or filesystem data.

Temporary Workspaces survive automatic collection while selected or occupied. The expanded toolbar replaces Close all with an explicit delete action for a selected empty temporary group after receiving a session projection. Runtime-guarded deletion requires a complete live-owner query, rechecks current temporary status and memberships under the registry lock, and refuses pending launches. It reuses metadata-only deletion and selects unassigned; unknown owner state, a new membership or promotion preserves the saved group. No backend/PTY lifecycle operation is invoked. Collection requires a complete authoritative live-session query; disconnected controllers or an unrecovered AVF owner produce unknown and prevent collection. Explicit exits/termination remove the corresponding membership. Pending launches reserve their Workspace until creation finishes; deleting it while a launch is pending makes the resulting session unassigned. Permanent launch configurations reference an existing Profile and optionally override cwd on a copied Profile before the existing immutable `SessionLaunchSpec` resolution/consent path. They never edit a Profile or allocate another Environment.

Shared directories are independent of logical Workspaces. `SharedFolderRegistry` defaults to data under app-private `shared-folders/<UUID>` and stores explicit Environment UUID connections in runtime-owned metadata. Native gets host paths in `TERM_SHARED_FOLDERS`; Rootless binds only selected folders at `/mnt/term-shares/<name>` when each session starts. Running sessions retain their bindings through rename/disconnection. Rootless sessions retain pinned host directory capabilities for binding lifetime. Connection settings are convenience, not Android UID isolation: Native and PRoot run as the same app UID. Deleting an Environment/Workspace cannot delete this independent storage. Explicit shared directory data deletion requires all session owners to be verifiably idle and file jobs finished.

Existing public-backed folders retain their current data until an explicit Save confirms restoration to app-private storage. This one-time copy requires the actual all-files grant and proven idle sessions, persisted VM owners, file jobs and same-UID writers under the preparation reservation. It copies and rehashes the latest public tree, stages `.private-<UUID>`, retains the old private tree as `.original-<UUID>`, then publishes private metadata last. A synced `.shared-folder-private-restores/<UUID>` journal permits retrying only unpublished private destinations. The public source remains intact; missing sources, links and special files fail rather than selecting the stale private backup. Limits are 2 GiB, 100,000 entries and 64 levels. Once restored, ordinary sharing and deletion need no storage grant; folder deletion removes its private data/backup and leaves the retained public source.

The Rootless layout retains its old workspace path only for data discovery, backups and interrupted native/emulated-link conversion recovery. Historic two-line migration journals retain their three fixed root indices; migrations of layouts without workspace data add a `NO_WORKSPACE` marker and never create that directory. Automatic directory creation and the special `/workspace` bind are removed; users may still create an ordinary guest `/workspace`. Shared Directory settings and search do not offer legacy `/workspace` source discovery, import or ZIP-export controls. Existing source data, compatibility workers and interrupted-job recovery remain retained. Runtime-owned imports copy into unpublished storage, stream bounded regular files/directories, verify the complete source tree again, then atomically activate a new shared directory. ZIP exports use the Android document picker. Both retain originals on success/failure/cancellation, reject links/special files and cap work at 2 GiB/100,000 entries. Long copies lease only the selected folder's actual Environment users; unrelated Environment sessions and installations remain admitted. Interrupted unpublished imports and host file-transfer temporaries are cleaned on owner restart without touching sources or committed copies. Existing Full environment backups remain the path for Rootless link/package semantics. Deleting a UUID Environment keeps its old regular-file workspace at the original UUID path, tombstones the Environment/Storage identity and lists the remaining directory as retained data; links that might depend on the deleted rootfs and pending migration journals block deletion instead. Legacy common and legacy AVF host directories are never removed by Environment deletion.

Successful Environment deletion disconnects that UUID from shared directories and cancels only transfers targeting that Environment, after releasing preparation and Environment registry locks. Shared directory identities, data and connections to other Environments are retained. The editor also excludes deleted references after an owner restart interrupted this metadata cleanup.

AVF connected folders require a verified `shareBrokerVersion=1` image and a non-protected VM. The app opens a boot-owned control stream and one SFTP v3 stream per selected UUID through host-initiated vsock 40506. The guest accepts host CID 2 only and runs passive SSHFS as UID/GID 1000 at `/mnt/term-shares/<name>`. Each host server pins one app-private UUID directory; guest messages never choose a host root. Parent walks use no-follow directory capabilities, content/metadata operations reject special files and hardlinked leaves, and symlinks are returned literally for guest-side resolution. No connected-folder `SharedPath` or index export is created. Optional whole-`/sdcard` virtiofs remains a separate capability.

Android 17's platform `crosvm_vu_fs` cannot open app-private data, including the previous folder index. Moving the index alone or increasing socket timeouts does not resolve that boundary. The app broker keeps file access in the owning Android UID and carries requests over the existing AVF vsock API. SSHFS provides the existing guest FUSE implementation; no guest-to-host connection, SSH daemon, TCP listener or guest-selected host command is added. All private mounts must be ready before sessiond becomes available. A control/data disconnect fails this VM closed. Older images without the broker marker reject connected-folder startup explicitly; no public-storage fallback or installed-image replacement occurs.

Connection and name changes apply on the next VM boot. Reconnecting never changes live mounts. Host-only `avf-share-leases` persist folder UUIDs and VM identity before run, independently of optional Android storage export. Verified VM stop and completed broker I/O teardown are required before releasing leases. A process-wide owner registry joins the original I/O even when a separate verifier/revocation driver proves the VM stopped. Unknown VM state or an unfinished worker retains protection across owner death and connection edits. Private sharing disables SSHFS data/directory/attribute caches and uses synchronous writes; it does not provide cross-runtime advisory locks, hardlinks or Linux device files. Existing installed images and the approved catalog stay unchanged until an authorized image release.

AVF also exposes explicit copies through the running, host-selected VM backend and a dedicated endpoint in the existing guest-agent binary. Copying does not start a VM or claim live synchronization. Each operation validates the selected folder/Environment connection; host paths never cross vsock. Files are relative to `/home/droid/term-transfers/<directory-name>`, limited to 64 MiB and 120 seconds, streamed and SHA-256 verified before no-replace atomic publication. Cancellation closes only that operation connection. Activities observe persisted job results; UI recreation/unbinding does not cancel work. Owner death marks unfinished jobs interrupted and preserves originals. Guest images need the non-root host-CID-only service for copies; older images without it report unavailable.

AVF images additionally declaring `androidStorageVersion=1` expose the Android-authorized primary shared-storage volume through the fixed `term-storage` export at guest `/sdcard` when the common all-files permission is granted and the VM is non-protected. A per-Environment internal-storage choice and the actual common OS grant must both allow the export. New Environments default off; records predating the choice retain their former grant-dependent behavior. The Permissions section exposes this choice for Rootless/AVF and the real common OS permission for Native, whose UID-wide access cannot be isolated by an Environment preference. Creation stores the choice atomically with the new Environment/Profile and keeps it in the draft until save. No permission is automatically granted. The driver obtains the root from Android, never from RuntimeConfig or guest input, and retains the strict UUID-folder validation. Storage-only boots have no empty folder index. The guest prerequisite mounts with `nodev,nosuid,noexec`; absent capability/permission leaves storage unmounted. AppOps monitoring in :vmhost stops recorded storage-sharing VMs on revocation and repeats recovery on owner startup. A process-wide boot gate serializes lease registration/VM run with revocation, including persistent reconnects; permission is rechecked before run. Session readiness waits outside this gate. Stop failures retain the lease and surface an error. Runtime ownership is reconciled only after platform-proven stop under the preparation reservation. OS permission grants and Environment choice changes take effect at the next boot, never by hot mounting a running VM. Changing the Environment choice does not stop an existing VM; its current export remains until stop. Rootless queries the current host-owned choice and OS grant for each new session. Native and PRoot remain outside independent filesystem security isolation. Launch snapshots cannot authorize the mutable choice: registry resolution refreshes it from the saved Environment, and backend configuration reads the current host record after image readiness waits. Backups may record the source choice, but restored new Environments default off.
