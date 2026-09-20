# Rootless PRoot payload provenance

Channel CI now uses `prepare-release-runtime.py` to fetch and hash-check the pinned
accelerated engine, loader, combined stdio DSO and corresponding source archive.
It embeds these files in the APK; the installed app never downloads runtimes.
The app and runtime packaging preserve the three pinned ELF files with
[AGP keepDebugSymbols](https://developer.android.com/reference/tools/gradle-api/9.3/com/android/build/api/dsl/JniLibsPackaging#keepDebugSymbols),
so CI stripping cannot change the verified DSO bytes. Debug and signed release
APKs both undergo the same payload hash check.
The baseline payload and manual source build documented below remain available
for explicit non-accelerated local builds. Channel release builds require acceleration.

The [faststat implementation](faststat/README.md) is used by accelerated channel
builds, separately from the baseline payload documented below. The current
[emulated-link revision](../../docs/ROOTLESS_EMULATED_ACCELERATION.md) also supports
legacy storage without a native-hardlink migration.

## File I/O measurements

`workloads.py` also measures `small-read`, `sequential-read`, `sequential-write`
and `synced-write`. For example, inside the selected guest:

```sh
python3 /tmp/terminal-path-kernel-workloads.py --count 1000 --io-mib 64 \
  --case small-read --case sequential-read --case sequential-write --case synced-write
```

Use the same source, rootfs, storage and interpreter for both payloads and
alternate their order. The first case opens, reads, verifies and closes 1000
448-byte files. Sequential cases transfer 64 MiB using 64 KiB blocks; they
include open/close. Read content checks are timed, while write content is
verified after timing. Short reads and writes are handled. `synced-write`
includes one final file fsync; it does not include a parent-directory fsync or
claim crash-safe namespace durability. Plain writes measure buffered completion.

Preparation and cleanup are excluded. Read data is warm in the page cache;
neither reported MiB/s nor fsync results represent cold device throughput.
`--io-mib` defaults to 16 and is bounded to 1..256. Each run uses its own
temporary directory, with at most an input and output transfer file alive at
once, plus the count-limited small-file tree. No cache dropping or global sync
is performed. JSON records transferred bytes and effective MiB/s.

[The first device comparison](faststat/results/file-io-2026-09-16.json) shows
1.09x small-file reads and no sequential I/O improvement. The fsync median was
worse with substantial shared-storage variation; it is not a speedup claim.

## Production payload

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

`proot-perf.patch` adds opt-in aggregate counters and avoids a redundant failed
hard-link `readlink` for ordinary stat-family paths. It reuses the final component
type only within one path translation, checks the exact host path, and retains
fresh post-syscall metadata checks. Symlinks, recursive resolution and mismatches
use the existing path. See [ROOTLESS_PERFORMANCE.md](../../docs/ROOTLESS_PERFORMANCE.md)
for the measurement limits and regression procedure.

`PROOT_DOWNLOAD_CACHE` optionally names a directory containing the five upstream
input files under their URL basenames. Cached inputs must pass the same pinned
SHA-256 checks; mismatches fail rather than silently fetching replacements.

Expected outputs:

```text
9edb5ff81998a990b014797c9acc05f31b9eb5d84cb16105d4e8c1179d7d382b  proot
2567ab8ad3b418a8804696342056052eef37835e50d200e9f4ff48aae564c237  proot-loader
```

PRoot is GPL-2.0-or-later, talloc is GPL-3.0-or-later, and
libandroid-shmem is BSD-3-Clause. The combined PRoot executable is distributed
under GPL-3.0-or-later. License texts and this build/provenance path are shipped
in the application assets.
