# Package systems

The product does not present a fake shared package database.

## Native packages

Native packages are for Android-integrated or latency-sensitive tools. Recipes
should identify an upstream release, checksum, generic build class
(`autotools`, `cmake`, `meson`, `cargo`, `go`, `python`, `perl`, or plain make),
compatibility patches, tests and output metadata. CI publishes only a passing
artifact and retains the previous known-good version on failure.

`NativeExecutableLoader` is the runtime enforcement point. At target SDK 35 it
accepts only named, allow-listed executables installed with the APK under the
read-only native library directory. A package download into app data is data,
not executable code. This deliberately limits dynamic Native package breadth.

## Rootless and AVF packages

Rootless Debian, Rootless Ubuntu and AVF Debian use official `apt` and `dpkg`;
Rootless Arch Linux ARM uses `pacman`. Every environment has an independent
package database and home. A future `packages.lock` contains requested package names such as
`git`, `neovim`, `python3`, and `clang`; reproducing an environment means normal
apt installation in the target runtime, never transplanting `/var/lib/dpkg` into an unrelated rootfs. A Full Environment backup instead copies a matching rootfs and its package database together into a new independent Environment with compatible ABI and preserved storage semantics; see [BACKUP_RESTORE.md](BACKUP_RESTORE.md). Data backups do not include a package database or installed distribution.

## Optional USB client

An APK-bundled deterministic Python zipapp is bound as one file into new Rootless sessions at
`/usr/local/bin/terminal-usb`. It uses Python's standard library; users install
Python through the selected distribution if missing. The explicit PyUSB backend
additionally uses distribution `python3-usb`; ADB integration reuses distribution
`adb` with localfilesystem/--one-device support. Neither is automatically installed.
MTP/PTP, storage, PTY and profile helpers use the Python standard library. Raw/CDC need no ELF. iPhone
operations use the APK-built `libiphone-worker.so`, selected exclusively by
`NativeExecutableLoader`; no host executable is downloaded at runtime. Pinned
source dependencies are built by `tools/iphone/build.sh`. USB drivers run on
Android rather than distribution `udev`/`systemd`. See [USB.md](USB.md).

## Rootless bootstrap

The ARM64 payload includes the allow-listed PRoot executable and loader. It is
rebuilt from pinned Termux PRoot source and checksum-pinned talloc and
libandroid-shmem artifacts; provenance, license notices, output hashes and the
16 KiB alignment check live under `tools/rootless`. The first Rootless session
downloads the selected pinned, SHA-256-verified rootfs archive
and installs that distribution's official default selection before reporting ready:
Debian standard priorities, Ubuntu minimal/standard/server metapackages, or Arch
`base` with a full upgrade. There is no shared app-curated development bundle.
Existing installations complete this versioned preparation in place; ready shell
startup does not run network updates. This is not a second native package database. Bootstrap accepts only catalog-pinned rootfs images and APK-owned PRoot. The separate, explicit TERM Full backup importer validates and restores a complete compatible Environment; it does not extend bootstrap to arbitrary rootfs archives or PRoot binaries. Restored Data home link semantics are pinned for the later explicit base installation. The device check `RootlessDistributionsTest` exercises Ubuntu and Arch install,
identity, PTY, detach/attach and session isolation. Its optional
`rootlessPackages=true` mode additionally updates packages and installs `hello` (Ubuntu) or `jq` (Arch).
See `ROOTLESS_DISTRIBUTIONS.md` for the catalog and commands.
