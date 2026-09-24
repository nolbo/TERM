# Runtimes

The separate [Direct Linux experiment](DIRECT_LINUX_EXPERIMENT.md) explores
in-process Linux syscall translation without a PRoot tracer. Its current APK
probe is not a usable distribution backend or a selectable runtime, and does
not replace existing Rootless installations.

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

Channel CI builds now package the pinned accelerated engine (including local rename)
and combined faststat/stdio DSO from the pinned runtime input release. The emulated-link fix selects
`rootless-runtime-20260920-emulated.1`; its prepared local archives must be published
before channel CI can download this revision. They enable both
Rootless acceleration and regular-file stdio buffering and fail if either build
setting is missing. Release APK contents are checked against the same hashes.
The installed app never downloads this runtime. Manual builds still need explicit
Gradle properties; see `tools/rootless/prepare-release-runtime.py` and the channel
workflow. Acceleration also supports emulated hardlinks; storage conversion is not required. The older general dev APK from `951bc7b1bf71` did not enable this profile.

New installations probe hardlinks in unpublished staging and record either
`native-hardlinks-1` or `emulated-hardlinks-1`. Ordinary Android SELinux policy
forbids untrusted apps from creating real hardlinks, so emulation is the expected
stock-device format. Both formats, including unmarked legacy installations,
receive the packaged acceleration profile. Emulated installations retain
`--link2symlink`; ordinary metadata/open/create/close operations use validated
fast paths, while virtual link-count mutations keep PRoot bookkeeping.
No data conversion or marker rewriting is necessary to accelerate an existing
installation. Unknown, corrupt or symlink markers remain rejected.

The old migration IPC is retained only to recover interrupted conversions.
Settings exposes it only when a journal exists for the selected distribution;
recovery still excludes all sessions and workspace writers. It does not start
another native-link conversion. See [storage recovery](ROOTLESS_MIGRATION.md)
and [emulated acceleration and validation](ROOTLESS_EMULATED_ACCELERATION.md).

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
redesign includes an optional accelerated APK profile. Build with
`-ProotlessAcceleratedPayload=/absolute/path/to/payload` to package the pinned
candidate `proot`, `proot-loader`, and `libproot-faststat.so`. Gradle verifies all
three hashes and ARM64/16 KiB ELF constraints. This APK automatically applies the
validated shared-path/stat/open/create/unlink and ID/TCGETS settings to ordinary
Rootless PTY sessions. The candidate also accelerates regular-file close while
the tracee's descriptor table is isolated after exec. Fork/thread creation and
tracked auxv/netlink descriptors revoke that eligibility until another exec.
Pipes and other descriptors retain the original close path.
The candidate translates ARM64 `fchmodat2`, including no-follow paths and
empty-path operations against the guest working directory. This is required
for current glibc/GNU tar directory permission restoration during extraction.
The glibc preload is bound into the guest only; host PRoot
execution still uses the centralized APK executable loader. Existing guest
preloads follow the acceleration DSO. Guest-supplied PROOT options cannot replace
the APK-selected profile.

An additional experimental build option, `-ProotlessStdioBuffer=true`, requires
that accelerated payload directory to contain the separately pinned combined
faststat/stdio DSO. It enables bounded 64 KiB buffering for eligible glibc file
streams in both native-link and emulated-link sessions and maintenance commands. It is off by default;
raw read/write and fsync are unchanged. Fresh Ubuntu and Debian installation,
maintenance and PTY buffering checks passed on API35 ARM64 redroid. This option
has not established overall 2x I/O performance or physical-device compatibility. Build instructions,
tests and visibility/memory limitations are in
[the stdio experiment](../tools/rootless/io/STDIO.md).

The candidate's package installation, environment configuration and verification
stages use the same profile automatically. Maintenance retains its finite
`--kill-on-exit` process ownership; interactive session ownership is unchanged.

The accelerated Debian installation also installs the official `nscd` package.
The session controller owns one foreground passwd/group cache, starts it before
the first session, and retries after a daemon exit when another session is
created. Cache data and its socket use dedicated app-cache directories; account
files retain the distribution's normal directory layout and inotify watches.
The cache is shared but nonpersistent, with positive/negative TTLs of 2/1 seconds.
Only the daemon uses the owner-pipe and EXITKILL options. Closing its controller
closes that pipe and waits for termination; UI detach leaves the cache running.
Missing packages or failed startup retain direct NSS lookups. Existing verified
installations without nscd do not gain this optimization until environment setup
installs it. Other distributions and the default APK profile do not enable it.
Explicit experimental DSO/notification setup paths remain separate from the
packaged profile to avoid duplicate library bindings or conflicting modes.

The emulated-link acceleration revision removes the native-marker gate and
replaces internal `openat2` probes/walks with Android-allowed `openat` operations.
Published host bindings are opened afresh; every guest suffix component is
resolved through pinned directory FDs without following symlinks. There is no
cross-call path/inode cache. Unsupported cases retain the normal resolver.
The old phase-one numbers are not stock-device measurements. Current comparison
results and remaining device restrictions are in
[emulated acceleration](ROOTLESS_EMULATED_ACCELERATION.md).

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
