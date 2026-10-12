# Rootless PRoot payload provenance

The stock ARM64 APK embeds `proot` and its matching loader as immutable JNI
payloads. They are never downloaded or replaced at runtime.

`build-proot-arm64.sh` rebuilds both files on the repository's Ubuntu ARM64
host with native Clang and the NDK r28 sysroot. Every downloaded input is pinned
by version and SHA-256:

- Termux PRoot `v5.1.107.92`, commit `7266fb3e8516535682f5a9c8f3a7e70f6506eddb`
- Termux `libtalloc` 2.4.3 runtime/static packages
- Termux `libandroid-shmem` 0.7 runtime/static packages

The two libraries are statically linked into PRoot, leaving only Android system
libraries as dynamic dependencies. The small patch supplies two current-Clang
compatibility fixes, makes the upstream awk generator work with Ubuntu `mawk`,
and applies 16 KiB ELF alignment to the freestanding loader. The main executable
uses the same alignment flags.

Expected outputs:

```text
1374f8c067c3b2674144bf85f33bdce2fb1860c103e814b11ed21bf406d484d9  proot
2567ab8ad3b418a8804696342056052eef37835e50d200e9f4ff48aae564c237  proot-loader
```

PRoot is GPL-2.0-or-later, talloc is GPL-3.0-or-later, and
libandroid-shmem is BSD-3-Clause. The combined PRoot executable is distributed
under GPL-3.0-or-later. License texts and this build/provenance path are shipped
in the application assets.
