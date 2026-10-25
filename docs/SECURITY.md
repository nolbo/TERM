# Security

## Application update channel

TERM reads public `nolbo/TERM` release metadata without credentials. The app
keeps main/stable (`dev.terminal.app`) and dev/prerelease
(`dev.terminal.app.prerelease`) as separate installed apps. Each release app is
bound to its build channel and validates both the manifest channel and the
channel's exact package ID; a stored channel preference cannot redirect it.
Higher version codes never authorize replacing the other app. Debug keeps
`dev.terminal.app.debug` and does not offer release updates.
The dev-latest manifest is a mutable pointer to an immutable dev commit APK;
it cannot provide an arbitrary download URL or bypass signing verification.
The app constructs fixed, commit-specific HTTPS APK URLs; bounds manifest/download
sizes, redirects and transfer time; and requires exact SHA-256/bytes plus the
installed app's package/version and current signing certificates before offering
installation. Both release packages retain the existing release signing key;
sharing that key does not bypass package/channel checks. The system package
installer independently validates the APK.
Signing and cross-repository publisher secrets stay in CI, never in APKs.
App channel release credentials are scoped to trusted main/dev push steps. Their build job
publishes directly from its verified signed output after all verification gates,
under the shared publication concurrency group. Actions artifact uploads are
optional archives and are not used as the publication input.
A non-exported FileProvider exposes only the dedicated update cache with a
temporary read grant; the user must grant unknown-app installation and confirm
the system installer. The app never uninstalls another app, migrates its private
data automatically, or terminates sessions itself. Historical development
installs under the stable package ID remain intact when the separate prerelease
app is installed. Each package has its own private data and update cache. APK
replacement may kill runtime processes, so the
installation confirmation explains that live work can be interrupted. See
[APP_UPDATES.md](APP_UPDATES.md) for limits and publisher setup.

Pinned idevice plist/usbmux/AFC readers reject invalid or oversized declared
lengths before subtraction/allocation. Private connector FD reception uses
nonblocking I/O and a deadline; cancellation poisons the channel to reject late
responses/replay. Cached RPPairing is live-verified without auto-repair fallback.
These controls do not certify all upstream parsers or physical iOS behavior.

The repeatable [device-safety gate and physical acceptance process](../tests/usb/DEVICE_SAFETY.md)
separates automated regression results from hardware evidence. Skips and missing
physical tests must never be presented as proof of device safety.

iPhone workers never change USB configurations, even for `check`; the existing
device-wide configuration capability remains separate. Pairing-file placement
stages/verifies then renames, never truncating or deleting the live file first.
Failure snapshots disclose only fixed residual-change categories, not pairing data.
No automatic uninstall or settings rollback is attempted, since these can destroy
pre-existing app data or user settings. See IPHONE_TOOLING.md for limits and recovery.

USB failure containment closes uncertain Python broker streams and releases raw
interfaces after host I/O exceptions; cleanup does not reset devices or replay
commands. Failed broker responses have nonzero CLI exit status. This bounds
additional effects of failure, not the effects of user-authorized raw/serial/ADB
commands or operations already accepted by a peripheral. See USB.md failure safety.

Trusted iPhone pairing diagnostics expose only stable stage/error codes and an
allowlist of transport/store categories, never raw upstream error text. Only
ENOENT permits new pairing; malformed or unreadable records fail closed without
deletion. APK worker hashes and tooling response IDs are non-secret build/request
metadata and grant no capability. Initial Trust has a 120-second deadline.

## Registered iPhone host tools

Native and Rootless have equivalent bounded USB tool capabilities, not raw
transport authority. The APK allowlist includes the Native `terminal-usb` client,
`iphone-worker` and the trusted `iphone-tool-probe`. Only the fixed probe check
can be requested; extra fields, arbitrary worker/service operations and connector
requests from guests are rejected. `usbStateJson` exposes only a tool ID/grant state.

USB FD ownership remains in the separate GPL worker. The host sends only an
anonymous connector to the trusted probe, authenticated by same UID and a random
stdin token. No path/token/FD for that connector enters guest environments or
binds. Device lease release follows reaping, including revocation and detach.
No credential-bearing host input Binder or signing store is part of the product.
See
[IPHONE_TOOLING.md](IPHONE_TOOLING.md).

The Linux guest is an untrusted execution environment. No guest process may
receive arbitrary Android Binder handles, host UIDs, private app paths, raw file
descriptors, or unrestricted `ContentResolver` access.

The Phase 0 runtime has no guest-to-Android capability calls. Future Android
integration follows this route:

```text
guest CLI → droid-agent → vsock → Android capability broker
```

Each broker request must be authenticated, protocol-validated, capability-
checked, Android-permission-checked, and optionally confirmed by the user.
Capability grants are persisted per VM and are independent of Android runtime
permissions.

New AVF factory images treat each VM as a single-user development environment. Interactive shells start as non-root `droid`, with passwordless sudo authorizing administration of that guest, including its APT/dpkg database. The factory locks the account's password and installs `/etc/sudoers.d/90-term-droid` as root with mode 0440, validated by `visudo` and an actual non-interactive elevation check. `droid-sessiond` uses `NoNewPrivileges=no` so child shells can use sudo; it retains `PrivateTmp=yes`. This allows guest programs running as `droid` to obtain guest root and is not an isolation boundary between users or programs within one VM. All guest processes, including root, remain untrusted by Android and receive only the VM's separately checked capabilities. Existing installed images are not modified by this factory policy.

The APK packages the direct AOSP AVF driver, but its compile-only platform API declarations must never appear as runtime classes in the APK. The optional device-provided shared library supplies the implementation. Driver creation is gated by Android 15+, AVF support, and both actual custom VM permission grants; the device must separately authorize the hidden custom-image API under its platform policy. Packaging does not grant permissions, install an implementation, or use reflection or hidden-API exemptions. Native remains the default owner, so absent platform classes cannot break permissionless service startup. `PlatformProbeAvfDriver` remains a public-SDK-safe diagnostic implementation for integrations without a provider.

The guest APT initializer is a root service restricted to host-CID vsock port 40503 and one fixed operation. It accepts no arbitrary executable, shell text, path, source or environment. It invokes the guest APT tool with signature verification intact, disables PDiffs only for the initial full refresh, fails on repository errors and bounds lock/network/overall waits. Cancellation kills only its dedicated process group. This is guest administration and grants no Android capability. The host verifies all original image artifacts and records the installation receipt before allowing writable initialization; it never rewrites manifest hashes to accommodate guest state.

## AVF update boundary

The Ubuntu container-kernel recipe enables guest-local namespaces, IPC and
networking while retaining guest hardening and existing AVF authorization.
Guest root and containers remain untrusted at the Android capability boundary.
Kernel/module replacement is a new verified image plus an explicit full image
transition, never an in-place guest write to host boot artifacts. The build and
runtime acceptance gates are described in [AVF_CONTAINER_KERNEL.md](AVF_CONTAINER_KERNEL.md).

Explicit AVF updates authenticate local TERM packages through the signed APK's
pinned manifest/system image. Dependency repository signatures remain enabled.
A fixed guest maintenance opcode runs only in the host-selected temporary boot;
it accepts no paths/commands. Old system is read-only and only copied state is
writable. Temporary VMs export no private folders or Android storage. Activation,
recovery and history deletion require service-owned leases and platform stop
proof; a journal blocks boot until recovery. See [AVF_UPDATES.md](AVF_UPDATES.md).

Resumable AVF image caches are private to one WorkManager ID and never authorize
boot. HTTP validators only bind range transfers; approved manifest/raw SHA-256
checks still authenticate each artifact, and the installer/service independently
checks stored files before use. Cache entries reject symlinks and enforce transfer
and expansion limits. Retry retains owned temporary files only; cancellation,
terminal failure and completion remove them. Extent-aware copying preserves
independent writable inodes under the existing stopped-VM/storage lease.

## Executable policy

Environment backup is an explicit SAF operation; Android automatic backup remains disabled. Backup files may contain credentials and private keys. Password protection uses framed AES-256-GCM with PBKDF2-HMAC-SHA256; passwords are never journaled. Plain exports detect corruption but do not authenticate their source. Imports require a source-trust acknowledgement and default to excluding Profiles, whose launch settings may execute commands later. Extraction accepts only bounded fixed components, never follows archive-controlled parents, validates all link relationships and rejects special files, traversal and excess expansion. New-only staging, hidden registry reservations and atomic activation protect existing storage on failure/cancellation/process death. See [BACKUP_RESTORE.md](BACKUP_RESTORE.md) for cryptographic framing, limits and recovery guarantees.

Android 10 and newer disallow `execve` of files in a writable app home for apps
targeting API 29 or newer. This app targets API 35. `NativeExecutableLoader`
therefore resolves only a small allow-list from the APK-installed native
library directory. Rootfs downloads and backup imports are treated as untrusted data; downloaded PRoot binaries and arbitrary SAF files never receive Native execution authority. Explicitly trusted TERM Full imports restore guest rootfs data for later execution through APK-owned PRoot; import validation never executes their contents.
The fixed `rootless-notify-probe` name supports an opt-in standalone test APK;
the production APK does not bundle this probe. It remains subject to the same
canonical library-directory and executable checks and grants no downloaded-file
execution path. Its fixture-only FD transport is not a guest capability API.
The Direct Linux/glibc experiment was retired on 2026-09-30. Its probe and loader names are no longer accepted by `NativeExecutableLoader`; test sources and evidence remain in the [archive](../tools/direct-linux/README.md). Android Native/Bionic remains a supported product runtime.

The local Rootless workload profile keeps APK-owned executable verification and inherited Android filters. Its optional ARM64 `futimens` acceleration allows only the exact `utimensat(fd, NULL, times, flags)` form through PRoot's filter; both 32-bit halves of the path pointer must be zero. The kernel still validates the FD, time pointer, flags, permissions and inherited guest/Android filters. Non-null paths and unknown PRoot extensions retain translation. This is a tracing optimization, not a capability grant or a security namespace. Shared ABI v9 permits temporary tracee pins only between exec and the first clone/fork/vfork entry in the same conservative FD lineage, with signals blocked throughout the operation; user-owned descriptors are never guessed and closed. Fork descendants retain that lineage, including different-PID CLONE_FILES sharers, while an independently executed task has a kernel-unshared table. Unknown lineages remain conservative. Disabled dispatch remembers only explicit denial or revoked eligibility, never a cached positive grant or transient syscall error. See the [workload design and validation](ROOTLESS_AGENT_PERFORMANCE.md) and [FD lineage checks](../tools/rootless/faststat/FALLBACK_COST.md).

Bootstrap downloads are catalog-pinned Debian, Ubuntu and Arch Linux ARM rootfs
archives. The installer limits their size, verifies SHA-256 before extraction, rejects path
escapes, symlink-parent traversal, unsupported special files and unsafe links,
and activates only a complete staging tree. Catalog `/dev` placeholders are
not materialized; PRoot binds the host-selected `/dev` separately. Download size
limits and checksums are distribution-specific; caches, staging trees, homes and
package databases are separate. These directory boundaries prevent accidental
state sharing, not malicious access by processes sharing the Android app UID.
The approved Arch image disables pacman's kernel sandbox for PRoot compatibility;
package signature checking remains enabled. Environment completion additionally
downloads packages using the guest distribution package manager and its existing
repository/keyring policy. It does not disable signatures or add repositories.
Preparation uses the same APK-owned PRoot/loader without an external-storage bind;
its output and execution time are bounded. Locale configuration is parsed as data,
never evaluated by the Android host.

Rootless bind paths are constructed by the host from fixed app-owned paths,
plus the primary shared-storage directory when the user grants optional
`MANAGE_EXTERNAL_STORAGE` access. The runtime checks
the saved Environment choice and `Environment.isExternalStorageManager()` for every new Rootless session before
adding `/sdcard`; guest environment variables cannot request a bind. Native
and PRoot run under the app UID, so this is deliberately a common OS permission,
not independent per-session capability isolation. PRoot is not a security
sandbox. Turning access off opens Android settings to revoke the actual grant;
a local preference or removing a bind would not revoke Native filesystem access.
Revocation removes broad access, while ordinary scoped-storage access to files
owned by this app remains subject to Android policy.
Android can terminate app processes on permission revocation. Supported non-protected AVF images use this common grant for the host-selected primary shared-storage export at `/sdcard`; Rootless/AVF additionally require the saved per-Environment export choice. This choice controls new mounts, not independent Android permissions or PRoot filesystem isolation. It never exposes another app's private data or changes the Android executable policy. All-files access can also cover removable shared volumes
for Native, subject to Android's restrictions; Rootless binds only the primary
volume. See [Android's all-files access contract](https://developer.android.com/training/data-storage/manage-all-files).
Google Play distribution requires review of this special permission; declaring
it does not grant it.
Guest-provided bind paths, arbitrary host file descriptors and the full app
data directory are not exposed.
# Security boundary

## Capability broker

AVF image URLs and manifest hashes originate only from the catalog embedded in the signed product APK. The image producer emits that catalog; runtime/UI input and unsigned remote catalogs cannot change it. HTTPS redirects have a fixed hop limit and cannot downgrade or introduce URL credentials. Downloads and gzip expansion enforce per-artifact size limits; the manifest has a separate 64 KiB limit. Initial installation checks all five artifact hashes before recording the verified manifest hash in app-private storage. Subsequent startup checks the four immutable artifacts and requires the writable state disk to remain a non-empty regular file; guest writes are not interpreted as immutable image tampering. For image v2 this writable disk is the complete EXT4 root, while the sealed system disk remains an independently verified baseline. V1 OverlayFS and v2 direct-root images select their boot path from the verified manifest, never writable guest files. The install receipt is host metadata, never copied from the imported source directory. Local imports still require full initial verification.

Boot authorization comes from full checksum verification in the AVF platform driver's `prepare` step. Startup provisioning and catalog guards only check path existence so an installed image is hashed once per runtime preparation. The driver verifies/adopts older installations through `DebianImageInstaller.verifyForStartup`; a receipt never substitutes for immutable artifact hashes. Existing invalid paths, including broken symlinks, are preserved for verification to reject rather than triggering replacement downloads. This optimization introduces no verification cache across preparations or process restarts.

AVF image downloads require explicit installation consent after saving a fresh-environment profile or through the saved Environment's install action. Starting a session never enqueues a download; it may only wait for an existing approved job. Profile installation status checks complete non-empty regular files without following leaf symlinks and reads at most the bounded manifest metadata. This UI-only presence check, historical worker success and onboarding preferences cannot authorize VM boot or bypass checksum verification. Installation remains storage-scoped and subject to the registry's deletion guard.

CI binds transferred factory metadata to the producer's source commit and manifest digest, verifies compressed transport checksums and decompressed artifact hashes, and checks that the candidate APK embeds the exact catalog. Public TERM publication requires both image and APK verification; the private source repository cannot serve unauthenticated runtime downloads. Default-pin approval streams the published manifest and all five images without credentials, with HTTPS redirect and size limits, before atomically replacing the pin. Failed publication verification leaves the prior pin intact. Ordinary builds consume only the reviewed source-controlled default pin or an explicit build-time override; CI does not silently push pins, and apps never fetch unsigned remote catalogs.

AVF bridge Android APIs are reached through `android-bridge`'s
`AndroidCapabilityBroker`. An AVF guest request is evaluated against a grant keyed
by the host-selected VM instance ID before any Android service is called.
`ASK` is deny-by-default until a host permission UI is implemented; it never
silently becomes an allow decision.

The initial broker supports only clipboard text, HTTP(S) URL opening, and
notifications. It validates size limits and URL scheme/host, returns typed
results, and never returns Android Binder objects, `Context`,
`ContentResolver`, or host file descriptors to the guest.

Capability grants remain independent from Android OS permissions and service
availability. For example, a guest notification grant does not bypass the
user's Android notification setting.

`VmRuntimeService` exposes only typed capability/grant names through local
AIDL for the future host permission UI. Invalid values are ignored, grants are
stored per host-selected VM instance, and guest RPC cannot alter its own grant.

Rootless USB uses the service-owned typed `UsbBroker`, with separate host UI
approval and Android device permission. Grants are scoped to session, attachment,
interface and raw/CDC mode (or a specific iPhone action, with exact AFC read path or
dedicated upload-directory scope), and are revoked on session exit, detach or explicit
revocation. USB FDs stay on Android. This is not an independent OS sandbox between
same-UID PRoot processes. Serial-driver grants additionally identify a recognized
driver and selected port. Only unambiguous single-configuration FTDI/CP21xx
interface/port mappings can coexist; other layouts reserve the whole device since
initialization may use device-wide vendor requests. The guest cannot supply arbitrary requests
through that grant. Pinned upstream forced claims are disabled. Blocking serial
and raw I/O run outside the broker lock with cancellation and per-operation deadlines.
Raw HID report descriptor reads are narrowly allowlisted; other standard requests
remain denied on the raw control route. Explicit configuration changes have a
separate device-wide approval and rotate attachment identity after any attempt;
alternate settings are bound to the raw-interface grant.

The PyUSB backend and ADB bridge do not expose a USB FD. ADB uses an owned private
Unix-socket server rather than a shared TCP server; PTYs provide byte streams,
not hardware ioctl access. Standard MTP/PTP commands run under an explicit raw
interface grant and do not imply a narrower host-enforced file capability.
Removable storage instead uses a host-picked Android ExternalStorageProvider
tree with separate read/write session grants and opaque document handles. Picker
URIs, ContentResolver and host descriptors never enter the guest. Emulated/internal
storage and other providers are rejected. Writes create new UUID-prefixed files;
no overwrite/delete/mount/format capability is offered. Unmount events revoke
grants; removing a tree drops its persisted permission, not its files. Provider
cancellation runs on a bounded pool, but cannot guarantee that a stuck Binder
provider returns promptly. See [USB.md](USB.md) for limits and the threat model.

The iPhone worker receives a USB FD only within the Android host implementation.
Its usbmuxd server has no public listener; host libimobiledevice connects through
anonymous socketpairs. Pair records are no-backup, bounded and atomically stored;
neither pairing-record APIs nor generic service connections are guest operations.
This prevents API-level credential disclosure, not malicious same-UID access.

## AVF diagnostic log boundary

The non-exported log Activity only reads fixed app-private files; no intent-supplied path, runtime binding, privileged logcat access or guest command is accepted. Logging is enabled by default and can be disabled immediately across app processes without deleting history. Only metadata is recorded: timestamps, PID, stable identities, symbolic stages/status/exit codes and exception class names. Terminal input/output, commands, environment values, paths, URLs, exception messages and share tokens are excluded. Fields reject non-symbolic or oversized values, file reads and retention are bounded, and diagnostic I/O failures never interrupt runtime work. Disabled-period and pre-clear queued events are discarded. Copy places the filtered metadata on the Android clipboard only on explicit user action; deletion requires confirmation and does not stop VMs or sessions. Guest journals and console output are not collected.

## Native CLI resources and diagnostics

The fixed Native CLI allowlist adds jq, curl, ssh, ssh-keygen, rg and vim; Git is
excluded. Command links resolve to the canonical APK native library directory.
Only versioned Vim/CA data is extracted into a managed no-backup directory, with
SHA-256 checks and atomic activation; user home and existing sessions are not
rewritten. Native library search paths are kept separate from Rootless and AVF.
curl retains trust-chain and hostname verification, and OpenSSH retains known
hosts and key permissions. Native Diagnostics uses disposable cache files and
bounded child processes without session/backend creation. Clipboard copy reuses
OSC 52; storage listing reuses the existing authenticated USB/file broker and
per-session approvals. No new capability, daemon or downloaded code authority is
introduced. Native clipboard writes require the default-off switch in the existing
terminal Android-permissions section and the existing capability broker's fixed
`host-native-clipboard` write grant, distinct from VM grants. Only live output
from the focused Native session in the resumed, focused Activity is eligible.
Background, Rootless, AVF, oversized text and clipboard reads remain denied.
Checkpoint restoration is data restoration, not a replay of clipboard requests.
See [NATIVE_SHELL.md](NATIVE_SHELL.md).

## Local service dashboard boundary

AVF local service reads, forwarding changes and callback subscriptions use non-exported Activities and same-UID/verified-main-process Binder checks. Guests cannot choose an Environment identity, register Native/Rootless services, request host interface addresses or invoke these APIs. The host associates each report stream with its owning backend. Native/Rootless manual registration APIs are removed. Older manual records are ignored during preference loading, and only AVF Environment metadata is included in dashboard snapshots.

Host forwarding binds only `127.0.0.1`, limits host ports to 1024–65535, retains at most 512 service records (128 per AVF Environment), and shares budgets of 128 bound proxies and 64 active connections. Each guest reports at most 128 numeric TCP endpoints and accepts at most 64 proxy connections. Guest proxy requests may target only currently observed local listener endpoints; wildcard listeners map to loopback. There is no LAN exposure, remote-target relay, port scan or process termination. Invalid event payloads are isolated, report/frame failures affect only their source Environment, and close/stop releases its listeners and accepted streams. Safe replacement binds and persists the candidate before closing the old listener; conflicts never close another owner's socket.

The browser destination is constructed from an explicit HTTP/HTTPS enum, fixed loopback host and validated port; arbitrary service-provided URIs are never executed. Android uses a standard browsable `ACTION_VIEW`, with missing handlers reported. Observed addresses and bound proxies are not health checks. Legacy agents without the TCP proxy remain discovery-only until an explicit image upgrade; dashboard refresh cannot download, start or install anything.

## Terminal image input

Terminal image payloads are untrusted. The common parser bounds image dimensions,
decoded pixels across animation frames, transfer sizes, decompression and SIXEL
expansion. PNG dimensions are checked before allocation. Runtime PTY owners
resolve kitty file/temp/shared-memory requests within host-selected runtime roots;
AVF resolves them inside the guest. Rootless symlinks use the guest namespace.
Readers pin directories, reject symlink races and non-regular files, bound reads,
and restrict unlink to approved temporary/shared-memory entries. The UI receives
direct image bytes and never opens guest-supplied paths. Native excludes other
app-private roots and reports unsupported POSIX shared memory explicitly.
Oversized strings are drained through
their terminator; historical protocol replies are suppressed during replay.
See [TERMINAL_GRAPHICS.md](TERMINAL_GRAPHICS.md) for exact bounds and limitations.

## Kitty remote control

Remote PTY commands are denied by default. A host setting grants newly UI-created
sessions authority over sessions in the same host-selected runtime only. The
source identity comes from the PTY owner, not the JSON request or environment.
Disabling the setting revokes existing grants. Remote launches cannot delegate
authority or override internal identity/argv/loader variables. No network listener,
password transport, arbitrary Android service or capability broker bypass is
exposed. JSON size/depth, queued work, input, titles and argv are bounded; replay
never executes commands. The grant covers all programs in the permitted PTY and
can explicitly terminate matching sessions. See [KITTY_PROTOCOL.md](KITTY_PROTOCOL.md).

## Host terminal checkpoints

OSC 8 hyperlinks are untrusted terminal metadata, stored per cell and carried with text through scrollback, copy, reflow and same-build checkpoints. Parsing never launches an Android activity. Only an explicit tap with the existing terminal-link setting enabled can open a destination, through the shared `ACTION_VIEW` + `CATEGORY_BROWSABLE` path. Both OSC 8 and regex-detected destinations require an http/https scheme and non-empty host; controls and other schemes (including file, content and intent) are rejected. A disallowed explicit destination never falls back to a different URL in its label. OSC arguments remain bounded and malformed or oversized strings are discarded through their terminator; incomplete strings do not commit link state. OSC 8 parameters such as `id` are accepted without adding UI or an unbounded identity registry.

Terminal checkpoints are created and read only between components of the same
app build. The runtime service is not exported; no guest capability, wire frame
or file path accepts a serialized checkpoint. Transfers expose only the read end
of an anonymous reliable pipe; no data file
or guest-accessible checkpoint path is created. The reader
uses an explicit class allowlist and refuses proxies. Output callbacks, Android
clients, processes and filesystem handles are transient and never serialized.
Existing text/history and graphics quotas still bound retained state; raw PTY
output is never retained without limit to achieve restoration. The debug-only
process-death probe requires the platform DUMP permission and is absent from
release APKs.

## Environment persistence and deletion

Installation consent for a profile launch uses a separate host-only Binder method guarded by the app UID and verified main UI process. Approval is scoped to its validated immutable Environment snapshot and is never a profile setting, environment variable or guest capability. Normal Rootless session requests recheck the usable base image under the storage lease and fail if missing; AVF image enqueue requires explicit consent and privileged custom VM access. Reading installation state and displaying or restoring a confirmation dialog have no installation side effects.

Environment UUIDs and their runtime/distribution identity are host-selected metadata, not paths or a new Android UID isolation boundary. Profiles reference Environment IDs; launch snapshots include immutable runtime metadata. The service validates snapshots against Environment identity, including the AVF VM identity, without consulting a mutable profile. Fresh AVF environments cannot select the legacy/default VM identity.

Environment metadata shares the existing process-shared storage lock and atomic manifest. Delete operations recheck profile references, actual session owners and runtime reachability under the preparation/controller locks before removing the runtime and its persistence. Unknown/disconnected AVF owners fail closed. Software removal also verifies idle session ownership; an unattached AVF image can be removed only when the platform independently confirms the persisted VM is stopped or absent. Pending image jobs block removal, and enqueue/final-removal checks share the manifest lock. A persisted generation is advanced before disk removal and checked at worker activation, including for cancelled workers, to prevent stale image recreation. Software removal retains Environment/Profile identities and never tombstones their storage. Storage and Environment tombstones are committed together; legacy adoption preserves data locations and inodes. Size/delete walks retain their no-symlink-follow policy, and image workers still check storage tombstones under the same lock.

## Shared Directory and explicit-copy boundary

AVF exports no Android primary storage or shared directories, regardless of legacy image capability markers, OS grants or stored Environment choices. Its runtime configuration accepts no host-directory paths and the driver never invokes SharedPath or the folder broker. Native access remains governed by the common OS grant; Rootless additionally checks its saved Environment choice for each new session. These same-UID runtimes do not gain independent filesystem isolation.

Pre-upgrade AVF sharing leases are retained solely to protect existing data until platform-proven VM stop and artifact cleanup. A running leased VM must be explicitly stopped before reconnecting under the new driver. Legacy storage-permission revocation remains active and shares a fence with unshared VM startup. Unknown state or failed stop preserves leases. No new broker workers or sharing leases are created; settings/Binder reject new AVF folder connections and folder-copy requests.

Existing public-backed folders retain their current data until an explicit Save confirms restoration to app-private storage. This one-time copy requires the actual all-files grant and proven idle sessions, persisted VM owners, file jobs and same-UID writers under the preparation reservation. It copies and rehashes the latest public tree, stages `.private-<UUID>`, retains the old private tree as `.original-<UUID>`, then publishes private metadata last. A synced `.shared-folder-private-restores/<UUID>` journal permits retrying only unpublished private destinations. The public source remains intact; missing sources, links and special files fail rather than selecting the stale private backup. Limits are 2 GiB, 100,000 entries and 64 levels. Once restored, ordinary sharing and deletion need no storage grant; folder deletion removes its private data/backup and leaves the retained public source.

Legacy deletion leases persist until platform-proven stop. Connection edits cannot release a live VM’s protection. Unknown VM state and inaccessible cleanup retain the record; cleanup only removes fixed socket/index artifacts and never traverses user data. Native/Rootless operation with no legacy AVF leases requires no AVF API access.

AVF terminal recovery remains independent of removed folder-sharing recovery. A stop/error callback alone is insufficient to discard sessions or storage ownership: platform stop proof remains required under the Environment preparation reservation. Unknown state preserves the live VM reference. RPC timeout may follow a committed operation, so reconnect never automatically reexecutes terminal input or mutations.

Workspace membership is logical metadata, not filesystem ownership or a capability. Shared directories live outside Environment storage and use UUID paths; only validated ASCII names form `/mnt/term-shares/<name>`. Native and PRoot share an Android UID, so Environment connection settings do not provide UID or mount-namespace isolation. Native receives paths only for its connections, while Rootless launches bind explicitly selected directories and retain their host capabilities for session lifetime. Host transfer/import file APIs reject traversal, symbolic-link parents/leaves, hardlinked regular files and special files. JNI walks each component with `openat`/`O_NOFOLLOW` against pinned directory descriptors; atomic no-replace publication prevents replacing checked destinations.

Only verified main-UI callers can change Native/Rootless folder connections or submit legacy file jobs. New AVF folder connections and folder-copy jobs are rejected. Independent Android share-to-Linux file copies route through the host-selected running VM; the guest endpoint remains non-root, host-CID-only, limited to a fixed home subtree and bounded by SHA-256 verification, no-follow access and no-replace publication.

Legacy Rootless workspace imports and ZIP exports keep originals, reject unsafe entries, limit expansion and require idle owners plus a same-UID writer probe under existing preparation leases. Import re-hashes the source tree before publication. Metadata validation and publication are serialized with connection edits; crash recovery cleans only unpublished job-owned temporary names. Host transfer publication checks the current VM connection under that same registry lock. Existing emulated-hardlink migration journals retain their original recovery path and block copy/deletion until recovered. Deleting a UUID Environment leaves regular-file workspace data at its original path, excludes that directory from storage removal and preserves discovery after the Environment is tombstoned. No automatic relocation is performed. Any workspace symlink can depend on rootfs/emulated-link data, so it blocks deletion before rootfs removal; Full environment backup remains the preservation path for those semantics. Legacy common and legacy AVF host folders are retained. Only an explicit shared directory data-delete operation removes shared directory files, after verifiable session idleness and completed file jobs.
