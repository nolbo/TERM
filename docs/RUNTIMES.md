# Runtimes

## Runtime matrix

| Runtime | Availability | Userspace | Kernel | Package system |
| --- | --- | --- | --- | --- |
| Android Native | stock APK, immediate | Android/Bionic | Android | native recipe/cache (bounded set) |
| Rootless Debian | stock ARM64 APK, automatic on-demand bootstrap | Debian/glibc through PRoot | Android | official `apt`/`dpkg` |
| AVF Debian | privileged product build only | Debian/glibc | guest Linux kernel | official `apt`/`dpkg` |

The zero-special-permission product is Native plus Rootless Debian. AVF is an
optional enhanced runtime and is never an automatic fallback.

## Native

Native PTYs are owned by `VmRuntimeService` in `:vmhost`. The current bootstrap
is `/system/bin/sh` with app-private home and temporary directories. Dynamic
executables from writable app data are not supported at target SDK 35.

`libtermux.so` is rebuilt from the checked-in JNI source for every supported
ABI and uses 16 KiB ELF segment alignment. On ARM64 Linux build hosts, where
Google's NDK host executables are unavailable, the build uses the native Clang
driver with the official NDK r28 sysroot and compiler runtime archives.

## Rootless Debian

`RootlessDebianBackend` validates an independent rootfs at
`files/rootless/debian/rootfs`, including `/bin/sh` and `apt`, before creating a
session. On the first Rootless selection, `RootlessBootstrapper` downloads the
pinned Debian ARM64 archive over HTTPS, verifies its SHA-256, safely extracts it
into a staging directory while preserving executable modes, and atomically
activates it. Stale downloads and staging trees are removed before a serialized
retry. Interrupted or corrupt
app-owned installs are replaced automatically on the next attempt; no file
picker, ADB, root, or app-data reset is required. `RootlessSessionController`
starts an APK-packaged, allow-listed PRoot and loader, binds only `/dev`,
`/proc`, `/sys`, the rootless home and `/workspace`, and owns the resulting PTY
across UI detach.

The current PRoot payload and automatic Debian catalog target ARM64. Other
ABIs fail clearly instead of silently redirecting a command to AVF.
The payload is built from pinned Termux PRoot 5.1.107.92 source with pinned
talloc and libandroid-shmem artifacts, and both executable ELFs use 16 KiB LOAD
segment alignment. See `tools/rootless/README.md` for provenance and rebuilding.

PRoot does not provide real root, namespaces, cgroups, kernel mounts, or a full
systemd boot. Syscall and filesystem translation also add overhead.

## AVF Debian

AVF needs device support, privileged permissions and the product-only AOSP
system-SDK driver. It uses a separate image/root filesystem and guest session
daemon. See `AVF_NOTES.md`.

## Shared state

Only the workspace is shared between suitable runtimes. Native, Rootless and
AVF root filesystems and package databases stay separate.
