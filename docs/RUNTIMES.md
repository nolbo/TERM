# Runtimes

## Runtime matrix

| Runtime | Availability | Userspace | Kernel | Package system |
| --- | --- | --- | --- | --- |
| Android Native | stock APK, immediate | Android/Bionic | Android | native recipe/cache (bounded set) |
| Rootless Linux | stock ARM64 APK, automatic on-demand bootstrap | Debian, Ubuntu or Arch Linux ARM through PRoot | Android | distribution `apt`/`dpkg` or `pacman` |
| AVF Debian | privileged product build only | Debian/glibc | guest Linux kernel | official `apt`/`dpkg` |

The zero-special-permission product is Native plus Rootless Debian. AVF is an
optional enhanced runtime and is never an automatic fallback.

## Native

Native PTYs are owned by `VmRuntimeService` in `:vmhost`. The current bootstrap
is `/system/bin/sh` with app-private home and temporary directories. Dynamic
executables from writable app data are not supported at target SDK 35.

Live sessions retain the started foreground service and a notification showing
their count. With zero live sessions and no pending setup/create operation, the
notification and foreground status are removed and the service releases its
started lifetime and wake lock. A bound UI can still create another session;
once it unbinds, Android can destroy the idle service. Setup/create operations
are protected only until completion or failure. The service is not sticky and
does not claim to restore PTYs after process death. Android 13+ notification
permission is requested by the UI; denial does not prevent session execution.
The notification offers an explicit terminate-all action routed directly to the
service through an immutable PendingIntent. It terminates the current sessions
across registered runtimes, refreshes the authoritative list, and retains any
session whose termination failed. The batch also works without a bound UI.

`libtermux.so` is rebuilt from the checked-in JNI source for every supported
ABI and uses 16 KiB ELF segment alignment. On ARM64 Linux build hosts, where
Google's NDK host executables are unavailable, the build uses the native Clang
driver with the official NDK r28 sysroot and compiler runtime archives.

## Rootless Linux

Each approved distribution uses its own `RootlessDebianBackend` instance (the
existing class name is retained). Debian retains its independent rootfs at
`files/rootless/debian/rootfs`, including `/bin/sh` and `apt`, before creating a
session. On the first selection of each distribution, `RootlessBootstrapper` downloads the
pinned ARM64 archive over HTTPS, verifies its SHA-256, safely extracts it
into a staging directory while preserving executable modes, and atomically
activates it. Service-owned environment preparation then installs the official
distribution defaults, restores missing startup/locale settings, and verifies
packages and executable commands before readiness. Existing usable images are
completed in place, with package failures retried without rootfs replacement.
Stale downloads and staging trees are removed before a serialized
retry. Interrupted or corrupt
app-owned installs are replaced automatically on the next attempt; no file
picker, ADB, root, or app-data reset is required. `RootlessSessionController`
starts an APK-packaged, allow-listed PRoot and loader, binds only `/dev`,
`/proc`, `/sys`, the rootless home and `/workspace` by default, and owns the
resulting PTY across UI detach.

Ubuntu 24.04 LTS and Arch Linux ARM have separate `files/rootless/ubuntu` and
`files/rootless/archlinux` roots, homes, temporary files and download caches.
Debian/Ubuntu validate apt; Arch validates pacman. Only `/workspace` is shared
by default. The new-session menu explicitly selects each distribution; settings
show installation states and independent actions for all three distributions together. Existing Debian storage
and `ROOTLESS` session identities are preserved. See
[ROOTLESS_DISTRIBUTIONS.md](ROOTLESS_DISTRIBUTIONS.md) for the pinned catalog and
validation policy.

The current PRoot payload and automatic distribution catalog target ARM64. Other
ABIs fail clearly instead of silently redirecting a command to AVF.
The payload is built from pinned Termux PRoot 5.1.107.92 source with pinned
talloc and libandroid-shmem artifacts, and both executable ELFs use 16 KiB LOAD
segment alignment. See `tools/rootless/README.md` for provenance and rebuilding.

PRoot does not provide real root, namespaces, cgroups, kernel mounts, or a full
systemd boot. Syscall and filesystem translation also add overhead.
The packaged PRoot avoids a redundant failed hard-link lookup for ordinary
stat-family paths while retaining fresh metadata checks and link emulation.
See [ROOTLESS_PERFORMANCE.md](ROOTLESS_PERFORMANCE.md) for measurements,
diagnostic controls and validation limits.

[ROOTLESS_FILESYSTEM_REDESIGN.md](ROOTLESS_FILESYSTEM_REDESIGN.md) proposes
in-process filesystem translation with PRoot compatibility handling. This
redesign has a separate experimental dirfd-stat prototype; it is not enabled in
the application. Stock-device probes and whole-workload targets remain to be validated.

## AVF Debian

AVF needs device support, privileged permissions and the product-only AOSP
system-SDK driver. It uses a separate image/root filesystem and guest session
daemon. See `AVF_NOTES.md`.

## Rootless USB

New Rootless sessions expose optional `terminal-usb` (Python 3 required).
Android brokers approved raw interfaces, serial drivers and explicit alternate
settings. PyUSB uses an explicit backend; serial PTYs provide byte compatibility.
An apt-installed ADB server can use the private USB relay; standard MTP/PTP and
host-picked Android-mounted removable folders provide bounded file access. PRoot
does not gain kernel USB drivers or FUSE mounts. ARM64 Android also hosts a
private usbmuxd/libimobiledevice worker for separately approved iPhone pairing,
limited information queries and unpairing. It does not expose unchanged Linux
`idevice*` tools. None of these paths imply all-device compatibility. See [USB.md](USB.md) for limits.

## Shared state

The workspace is shared between suitable runtimes. Optionally, users can open
**내부저장소 접근 설정** in either Native or Rootless settings and enable Android's
all-files access. The OS grant is off by default and shared by both local
runtimes; AVF gets no new bind. Native uses `/sdcard` (normally
`/storage/emulated/0`). New Rootless sessions bind the host-selected primary
shared storage to `/sdcard` only when the OS grant is present. Existing Rootless
sessions need a new session to acquire that bind. Disabling access uses the same
system settings page, so Android enforces revocation rather than merely hiding
a path. Android may kill app processes and sessions on revocation; save work
first. Neither settings navigation nor Activity destruction terminates sessions.
Other apps' private data and Android-protected storage paths remain inaccessible.
Native, Rootless and AVF root filesystems and package databases stay separate.

## Inline terminal images

The shared SIXEL/kitty renderer supports animation, Unicode placeholders,
relative placements and cropping without additional permissions. Native and
Rootless PTY owners resolve local files before recording output; Rootless also
binds a distribution-private `/dev/shm` for shared-memory transfers. AVF resolves
files/shared memory inside the guest daemon. Native POSIX shared memory remains
unsupported. Native/Rootless replay retains up to 8 MiB at safe sequence
boundaries. See [TERMINAL_GRAPHICS.md](TERMINAL_GRAPHICS.md) for paths and limits.
