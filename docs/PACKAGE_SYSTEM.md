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

Both Debian environments use official `apt` and `dpkg`, but their databases are
independent. A future `packages.lock` contains requested package names such as
`git`, `neovim`, `python3`, and `clang`; reproducing an environment means normal
apt installation in the target runtime, never copying `/var/lib/dpkg`.

## Rootless bootstrap

The ARM64 payload includes the allow-listed PRoot executable and loader. It is
rebuilt from pinned Termux PRoot source and checksum-pinned talloc and
libandroid-shmem artifacts; provenance, license notices, output hashes and the
16 KiB alignment check live under `tools/rootless`. The first Rootless session
downloads a pinned, SHA-256-verified Debian rootfs archive
and then uses Debian's own `apt`/`dpkg`; this is not a second native package
database. No user-provided PRoot binary or rootfs file is accepted. CI still
needs to run `cat /etc/os-release`, `apt update`, and a small package install on
every supported ABI.
