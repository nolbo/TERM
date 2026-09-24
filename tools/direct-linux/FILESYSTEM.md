# Direct Linux filesystem milestone

2026-09-24. The standalone experiment now runs ordinary GNU filesystem tools
against its disposable app-owned Linux root. It remains an opt-in test APK, with
no production backend, installer or change to existing Rootless installations.

## Implementation and semantics

`files.c` owns pathname translation and filesystem dispatch. Relative lookups
start from a pinned cwd/directory FD. They no longer reconstruct the lookup from
the printable pathname, which can be renamed or replaced. `..` opens the live
parent and clamps the root by device/inode identity. Absolute symlinks restart at
the guest root. There is no cross-call name/inode cache or fixed directory-depth
stack; the 4,096-byte pathname and 40-symlink expansion bounds remain.

Namespace changes resolve the prefix, retain the final component and trailing
slash, then issue one kernel operation using pinned parents. This adds mkdir,
unlink/rmdir, rename/renameat2 (including NOREPLACE/EXCHANGE) and symlink creation.
Renaming or unlinking a symlink affects the link itself. Kernel rename atomicity,
open-file survival after unlink, link counts and errno are retained. Symlink
targets are stored unchanged and interpreted by the guest resolver later.

Metadata operations add fchdir, chmod/chown, truncate, timestamps, statfs and
umask. Metadata targets are pinned before operating on them; ordinary chmod,
truncate and timestamp changes use the pinned `/proc/self/fd` alias. FD-only
operations preserve the real FD and kernel behavior, including sendfile and
copy_file_range outside the signal handler. Android UID/GID, DAC and SELinux
remain authoritative: chown to root fails; fake successful ownership changes
are not provided. This follows the Linux contracts for
[path lookup](https://man7.org/linux/man-pages/man7/path_resolution.7.html),
[rename](https://man7.org/linux/man-pages/man2/rename.2.html),
[unlink](https://man7.org/linux/man-pages/man2/unlink.2.html),
[chmod](https://man7.org/linux/man-pages/man2/fchmod.2.html) and
[timestamp changes](https://man7.org/linux/man-pages/man2/utimensat.2.html).

The compatibility walker is **not an openat2-equivalent security boundary**
against another same-UID process moving directories outside the root during a
lookup. Directory-FD membership checks and pinned prefixes improve correctness;
they do not isolate arbitrary guest code from the Android app UID. No new host
binding, broker capability or general `/proc` exposure is added.

Symlink timestamp updates use a pinned O_PATH descriptor and Linux 5.8+
AT_EMPTY_PATH. Older kernels return the unsupported error; normal file timestamps
have no such new requirement. Hardlinks/fake identity, ACL/xattr behavior,
O_TMPFILE, a full proc/device view and complete concurrent namespace semantics
remain unfinished. Native Android hardlink availability is not used as a shortcut
to claim package-manager compatibility. No fallback to PRoot occurs.

## Validation

The [raw Android report](results/filesystem-2026-09-24.json) records loader hash
`bed65ca5ed4ac2beafc276fb75f4b134aa1eff76dd259fa5250c037d00918337`,
fixture hashes/package versions, 77 top-level executions and 56 timing samples.
Instrumentation passed in 9.630 seconds on API35 ARM64 redroid. TracerPid and
CapEff were zero. Initial Seccomp was zero; Android MCP `getenforce` returned
`Disabled`. **Stock enforcing policy is unverified.**

Both static and dynamic glibc fixtures cover:

- Directory-FD/cwd lookup after rename, replacement and reparenting; root `..`
  clamping; removed cwd and still-open directory behavior.
- Prefix/final/absolute/dangling symlinks and trailing-slash failures without
  modifying the referenced file.
- NOREPLACE/EXCHANGE, unlink of an open file, surviving content and link count.
- Umask, real permission denial, chmod/chown, truncate, nanosecond timestamps,
  symlink timestamp isolation and statfs.
- Five hundred concurrent directory exchanges against five hundred lookups
  through a pinned FD, malformed pointers and low-numbered temporary FD cleanup.

Unmodified Ubuntu ARM64 coreutils 9.4, dash and their existing host DSOs are copied
into test assets only. A shell creates 128 files and a nested symlink, changes
mode/time, runs `cp -R --reflink=never --preserve=mode,timestamps`, moves the copy,
reads its symlink and removes the source. Android checks all copied contents,
mode 0640, exact mtime, symlink target and deletion. Recursive removal of the copy
is also checked. Benchmark rounds additionally check every file in the guest and
remove the entire work directory before the next engine runs. All immutable
fixture hashes are rechecked after execution.

The existing ELF/process/signal/exec regressions passed in the same run. Native
warnings-as-errors compilation and the complete runtime unresolved-import/TLS
audit passed. JDK17 canonical verification passed:

```sh
./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest \
  :image:test --console=plain \
  -ProotlessAcceleratedPayload=/tmp/terminal-unpack/prepared \
  -ProotlessStdioBuffer=true
```

Runtime tests: 180 passed, one optional external-archive fixture skipped. Image
tests: four passed. `:android-runtime:assembleDebugAndroidTest`, fixture generation,
test-only packaging, signing and signature verification passed. No UI/device
interaction changed. Android MCP supplied install/run/policy evidence; Context7
provided partial kernel pathname background, supplemented by primary Linux
man-pages and local NDK ABI headers. Replicant and codex_apps were not relevant.

Use the [ELF reproduction commands](ELF.md#reproduction) with current sources and
the hash printed by `build.sh`. Add `-e directLinuxBenchmark true` for all four
workloads. Trace/profile flags must be off for benchmark comparisons. The report
is written only after every assertion succeeds; `observation_ms` is Java process
observation latency and must not be used as a benchmark clock.

## Workload measurements

Seven alternating pairs use the same programs/root/storage and the pinned
`rootless-runtime-20260920-emulated.3` accelerated Rootless payload. Native
CLOCK_MONOTONIC measures guest work; Rootless preload use is independently
asserted by 5,000 hits in each matching stat/open workload.

| Workload | Direct Linux median | Accelerated Rootless median | Observation |
| --- | ---: | ---: | --- |
| 5,000 stat calls | 27.238 ms | 39.613 ms | 1.45x faster |
| 5,000 open/read/close | 45.202 ms | 60.104 ms | 1.33x faster |
| Shell plus 30 external commands | 53.458 ms | 48.438 ms | 10.4% slower |
| 128-file tree create/copy/move/verify/remove | 51.328 ms | 95.214 ms | 1.85x faster |

The tree workload ranges were 50.943–52.231 ms and 93.082–97.855 ms respectively.
These are warm, short local-storage workloads on a permissive container device.
They do not establish whole Linux speed, cold-start behavior, durability after
power loss, CPU/memory/thermal impact or physical-device policy compatibility.
Python/Git/packages, threads/network/PTY, lifecycle integration and enforcing
device validation remain required by the [overall goal](../../docs/DIRECT_LINUX_EXPERIMENT.md).
