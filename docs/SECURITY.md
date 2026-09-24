# Security

## Application update channel

TERM reads public `nolbo/TERM` release metadata without credentials. The app
keeps main/stable and dev/prerelease discovery separate, validates the manifest's
channel against the user's selection, and never downgrades across channels.
The dev-latest manifest is a mutable pointer to an immutable dev commit APK;
it cannot provide an arbitrary download URL or bypass signing verification.
The app constructs fixed, commit-specific HTTPS APK URLs; bounds manifest/download
sizes, redirects and transfer time; and requires exact SHA-256/bytes plus the
installed app's package/version and current signing certificates before offering
installation. The system package installer independently validates the APK.
Signing and cross-repository publisher secrets stay in CI, never in APKs.
A non-exported FileProvider exposes only the dedicated update cache with a
temporary read grant; the user must grant unknown-app installation and confirm
the system installer. The app never uninstalls a conflicting debug build or
terminates sessions itself. APK replacement may kill runtime processes, so the
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
commands. Tooling FAILED/CANCELED states have nonzero CLI exit status. This bounds
additional effects of failure, not the effects of user-authorized raw/serial/ADB
commands or operations already accepted by a peripheral. See USB.md failure safety.

Trusted iPhone pairing diagnostics expose only stable stage/error codes and an
allowlist of transport/store categories, never raw upstream error text. Only
ENOENT permits new pairing; malformed or unreadable records fail closed without
deletion. APK worker hashes and tooling response IDs are non-secret build/request
metadata and grant no capability. Initial Trust has a 120-second deadline.

## Registered iPhone host tools

Native and Rootless have equivalent bounded USB tool capabilities, not raw
transport authority. The APK allowlist includes the Native `terminal-usb` client
and trusted `iphone-tool-probe`/`isideload`. Only the registered fixed-artifact
recipe can start; status/select/cancel are authenticated and session scoped.
Extra fields, arbitrary worker/service operations and connector requests
from guests are rejected. `usbStateJson` exposes only a tool ID/grant state.

USB FD ownership remains in the separate GPL worker. The host sends only an
anonymous connector to the trusted probe, authenticated by same UID and a random
stdin token. No path/token/FD for that connector enters guest environments or
binds. Device lease release follows reaping, including cancellation, revocation
and detach. Signing tools use host-only secure input, never Rootless
account/password/2FA input or a general stdin/argv relay. Secret Binder calls
require the app main process (same UID plus ActivityManager PID/process identity)
and a one-use prompt nonce. Guest snapshots omit that nonce and all secret
values. The generic input screen has FLAG_SECURE, disables autofill and clears
unsaved drafts on stop. Immutable JVM/Binder/upstream copies cannot be guaranteed
zeroized; mutable reply arrays/pipe buffers are cleared and credentials are not
persisted or logged. The signing store uses
0700/0600 no-backup pairing-level protection, not Keystore encryption; it is not
exposed to Rootless. New per-operation artifacts use owner-only host cache
directories and are deleted after worker reaping on all terminal paths. See
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

The current `PlatformProbeAvfDriver` only checks whether the device advertises
AVF and whether signature permissions were actually granted. It does not try to
bypass the platform requirement with hidden API reflection.

## Executable policy

Android 10 and newer disallow `execve` of files in a writable app home for apps
targeting API 29 or newer. This app targets API 35. `NativeExecutableLoader`
therefore resolves only a small allow-list from the APK-installed native
library directory. Rootfs downloads are treated as untrusted data; neither a
downloaded PRoot binary nor an arbitrary file selected through SAF is executed.
The fixed `rootless-notify-probe` name supports an opt-in standalone test APK;
the production APK does not bundle this probe. It remains subject to the same
canonical library-directory and executable checks and grants no downloaded-file
execution path. Its fixture-only FD transport is not a guest capability API.
The separate `direct-linux-probe` and `direct-linux-loader` entries exist only for an
opt-in standalone instrumentation APK. These test unprivileged in-process syscall
translation, anonymous W-to-X mappings and actual Linux ELF using temporary
fixture data. Guest exec replaces tracked guest mappings inside the surviving APK
runtime; it never kernel-executes a data ELF or clears inherited filters. Initial
ELF/interpreter pages now use validated private file mappings; mapping denial
is not bypassed with an anonymous-copy fallback. Source-file changes after entry
retain ordinary file-mapping behavior, not a frozen-image guarantee. The
[process milestone](../tools/direct-linux/PROCESS.md) documents its bounded semantics.
They are not bundled in the product APK and do not loosen inherited
Android policy. Their results do not establish support on stock enforcing devices. See the
[Direct Linux experiment](DIRECT_LINUX_EXPERIMENT.md) for the remaining gates.
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
`Environment.isExternalStorageManager()` for every new Rootless session before
adding `/sdcard`; guest environment variables cannot request a bind. Native
and PRoot run under the app UID, so this is deliberately a common OS permission,
not independent per-session capability isolation. PRoot is not a security
sandbox. Turning access off opens Android settings to revoke the actual grant;
a local preference or removing a bind would not revoke Native filesystem access.
Revocation removes broad access, while ordinary scoped-storage access to files
owned by this app remains subject to Android policy.
Android can terminate app processes on permission revocation. This setting does
not grant AVF storage capabilities, expose another app's private data, or change
the executable policy. All-files access can also cover removable shared volumes
for Native, subject to Android's restrictions; Rootless binds only the primary
volume. See [Android's all-files access contract](https://developer.android.com/training/data-storage/manage-all-files).
Google Play distribution requires review of this special permission; declaring
it does not grant it.
Guest-provided bind paths, arbitrary host file descriptors and the full app
data directory are not exposed.
# Security boundary

## Capability broker

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
