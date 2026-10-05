# Runtimes

The [Direct Linux experiment](DIRECT_LINUX_EXPERIMENT.md) was retired on 2026-09-30 in favor of improvements to the existing Rootless runtime. Its source, partial results and unresolved compatibility audit are preserved as historical records; its executables and instrumentation are no longer active. Android Native/Bionic, Rootless Linux and AVF remain the product runtimes.

## Runtime matrix

| Runtime | Availability | Userspace | Kernel | Package system |
| --- | --- | --- | --- | --- |
| Android Native | stock APK, immediate | Android/Bionic | Android | native recipe/cache (bounded set) |
| Rootless Linux | stock ARM64 APK, automatic on-demand bootstrap | Debian, Ubuntu or Arch Linux ARM through PRoot | Android | distribution `apt`/`dpkg` or `pacman` |
| AVF Debian | privileged product build only | Debian/glibc | guest Linux kernel | official `apt`/`dpkg` |

The zero-special-permission product is Native plus Rootless Debian. AVF is an
optional enhanced runtime and is never an automatic fallback.

Native and Rootless session RSS measurements capture the root PID and start ticks
before starting the process reaper. In non-debuggable builds, a forked child can
be hidden by `/proc` access policy until it executes its shell or PRoot binary;
identity capture retries for up to about one second during session creation.
The runtime does not enable debugging or change process dumpability. Permanent
access restrictions, exited sessions and AVF remain unavailable; RSS still
counts shared pages per process and can omit detached daemons.

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

Userspace Linux installation can be explicitly cancelled from its environment detail or installation dialog. The owning service stops only that backend's preparation, cleans temporary downloads/extraction, and retains any activated rootfs, home and workspace. A retry recovers interrupted package operations before verifying readiness. Closing a view does not cancel installation.

Each approved distribution uses its own `RootlessDebianBackend` instance (the
existing class name is retained). Debian retains its independent rootfs at
`files/rootless/debian/rootfs`, including `/bin/sh` and `apt`, before creating a
session. Selecting an uninstalled Environment first asks whether to install;
only approval permits `RootlessBootstrapper` to download the
pinned ARM64 archive over HTTPS, verify its SHA-256, safely extract it
into a staging directory while preserving executable modes, and atomically
activate it. Service-owned environment preparation then installs the official
distribution defaults, restores missing startup/locale settings, and verifies
packages and executable commands before readiness. Existing usable images are
completed in place, with package failures retried without rootfs replacement.
Stale downloads and staging trees are removed before a serialized
retry. Interrupted or corrupt
app-owned installs are replaced on the next explicitly approved installation; no file
picker, ADB, root, or app-data reset is required. `RootlessSessionController`
starts an APK-packaged, allow-listed PRoot and loader, binds only `/dev`,
`/proc`, `/sys`, the rootless home and `/workspace` by default, and owns the
resulting PTY across UI detach.

Channel CI builds now package the pinned accelerated engine (including local rename)
and combined faststat/stdio DSO from the pinned runtime input release. The emulated-link fix selects
`rootless-runtime-20260920-emulated.3`. They enable both
Rootless acceleration and regular-file stdio buffering and fail if either build
setting is missing. Release APK contents are checked against the same hashes.
The 2026-09-30 workload improvements are a separately verified local build input, selected with `rootlessWorkloadPayload=true` together with acceleration and stdio buffering. They fix worker exec ownership, temporary FD inheritance, stat identity, failed hardlink mutations and ARM64 syscall restarts after signals, and add a bounded FD-only timestamp path for static and dynamic binaries. Existing rootfs storage and package databases need no conversion. [ROOTLESS_AGENT_PERFORMANCE.md](ROOTLESS_AGENT_PERFORMANCE.md) records the source/preparation workflow and validation: correctness passed, but package wall-time gains were not established and large-repository work regressed. The candidate is not adopted as the default payload and has not been published as a new channel runtime.

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
by default. The new-session menu explicitly selects each distribution; Environment settings
show each instance's installation and runtime state; Userspace Linux settings contain only global DNS policy, engine status and runtime information, with limitations shown as a small note below the settings. Existing Debian storage
and `ROOTLESS` session identities are preserved. See
[ROOTLESS_DISTRIBUTIONS.md](ROOTLESS_DISTRIBUTIONS.md) for the pinned catalog and
validation policy.

Userspace Linux DNS defaults to **Use Android network settings**. The engine reads `ConnectivityManager` active-network DNS servers at each new session or installation/repair invocation and binds a generated resolver file over `/etc/resolv.conf`. Custom policy accepts one to three numeric IPv4/IPv6 addresses and applies to every Debian, Ubuntu and Arch Environment, including existing owners creating new sessions. Saving uses service-owned preferences through Binder; Environment rootfs and home are preserved. There is no hardcoded public-DNS fallback when Android supplies no servers. Running applications may cache resolver settings; start a new session to apply a policy reliably. Reading the server list does not route glibc requests through Android Private DNS encryption or reproduce all Android resolver behavior. Network changes are read on the next launch, not watched by a new background worker.

Runtime information reports only the installed engine: actual `proot --version` output when available, allow-listed/executable loader readiness, APK-selected acceleration and faststat, supported ARM64 ABI, and automatic per-Environment link selection. Missing information is shown as unknown, not an inferred version or enabled state. None of these engine internals is user-configurable.

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

The image progress card can cancel its current WorkManager attempt, including queued work or retries. Cancellation interrupts download, verification and copying before atomic activation and removes that attempt's temporary files. An already activated image is preserved; no VM or terminal session is terminated. Installation can be requested again in the same environment.

The APK includes the AOSP AVF execution driver. AVF still needs a supported Android 15+ device, both system permissions, and a compatible device-provided custom-image API authorized by the platform. The API declarations are compile-only, and Native remains the default owner. AVF uses a separate image/root filesystem and guest session daemon. See `AVF_NOTES.md`.

Saving an AVF profile with a new Environment opens that environment's image installation choice. Installation is an explicitly approved background job and does not start a VM. Environment details display image status and progress shared by all its profiles. A session may wait for an approved job, but a missing image without one requires installation through environment settings; opening a session never implicitly downloads an image. AVF environments retain a VM identity plus persistent system/home/config images; global AVF settings control engine policy only.

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
**내부저장소 접근 설정** in the common terminal settings and enable Android's
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
unsupported. Native/Rootless legacy replay retains up to 8 MiB at safe sequence
boundaries. UI restoration uses complete runtime-owned terminal checkpoints,
including retained history and graphics, independently of this raw replay limit.
The updated AVF daemon drains every running PTY while its host is connected.
See [TERMINAL_GRAPHICS.md](TERMINAL_GRAPHICS.md) for paths and limits.

## Battery and background work

`ALWAYS_AWAKE` is the default: a live session or host/guest operation retains a renewable, time-bounded partial wake lock even with no UI, output or shell activity signal. No inactivity deadline ends this protection. With no remaining sessions or work, the service releases the lock and its foreground/started lifetime. The old implicit `BATTERY_OPTIMIZED` default migrates once; policies explicitly selected in settings are preserved.

All runtimes expose power policy under terminal settings, including stock Native/Rootless builds without AVF privileges. The adjacent battery-optimization action asks Android to exempt this app from Doze, displays the actual `PowerManager` grant on return, and falls back to the system exemption list when the direct request is unavailable. The exemption is optional and user-controlled; a foreground service or partial wake lock alone does not remove Doze network restrictions. Manufacturer restrictions, force-stop and actual process death can still terminate work. Keeping sessions awake consumes more battery.

`KEEP_ACTIVE_WORK_RUNNING` follows live OSC 133 command activity, host operations and AVF `WORK_ACTIVE`/`WORK_IDLE`, rather than the mere existence of a running shell. Known idle prompts release the partial wake lock; silent known commands keep it until completion even after UI detaches. Task notification settings are independent. If hooks are unavailable or overwritten, a conservative ten-minute lease from creation or live I/O preserves temporary protection without an indefinite idle wake lock. Silent work in an uninstrumented shell beyond that lease cannot be identified reliably; use working command hooks, AVF work reports or the explicit `ALWAYS_AWAKE` policy for that case. Background shell jobs without a command/guest work signal are subject to the same signal limitation.

UI terminal mirrors remain subscribed for five seconds after the terminal Activity becomes invisible, then detach without stopping PTYs, sessions, the runtime or runtime-side terminal parsing. Return restores both screens, scrollback, parser state, graphics and saved viewport from a fresh authoritative checkpoint. UI redraw notifications are combined once per frame; output bytes are neither dropped nor throttled. Hidden/background terminal views cancel graphics redraw callbacks.

Failed guest/session, bridge and port-agent links back off through 2, 4, 8, 15, 30, 60 and 120 seconds, capped at 120 seconds. Success, explicit work and new session creation reset backoff; pending delays respond to explicit work. No session or pending/guest work means no reconnect loop. Connected event-driven port/task observers remain event-driven.

USB and host-tool UI polling stops with Activity visibility/service unbinding and uses ten-second intervals when no approvals or active operations need updates. Their current AIDL contract has no state-change callback, so bounded polling is retained. Memory polling runs only while its tool and Activity are visible and slows to at least fifteen seconds for unchanged snapshots. These UI polling policies never own or suspend the underlying operations.

## Persistent environments

Environment UUIDs identify persistent execution contexts independently of profiles and sessions. The service reuses one backend per Environment; profiles select how to enter it and sessions retain immutable v2 Environment snapshots. Storage paths and the Rootless legacy shared workspace stay unchanged. See `ARCHITECTURE.md` for identity migration and conservative lifecycle/deletion rules. Native/Rootless PTYs survive UI detach but cannot survive runtime-owner death; reconnecting UI or registry instances must not fabricate lost processes.

Stopping an idle AVF environment affects only that VM and its connection. A vsock reset or invalid frame disconnects that client without terminating the shared runtime process, other AVF environments, or Native/Userspace Linux sessions, including Ubuntu. Pending requests fail promptly, and unrelated sessions retain their terminal state and input routing.
