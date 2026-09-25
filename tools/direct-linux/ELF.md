# Direct Linux ELF runtime experiment

2026-09-24. The next step after the fixed-file transport probe now runs **real
ARM64 Linux ELF** in an ordinary Android application process. It remains an
experimental executable in a standalone test APK, not a complete/selectable Linux
environment. Existing Rootless payloads, rootfs data and sessions are unchanged.
The subsequent [process milestone](PROCESS.md) adds fork/exec and scripts; its
results supersede the initial process limitations recorded below.

## What exists

- `exec.c` (initially in `loader.c`): checked ELF64/ARM64 ET_EXEC/ET_DYN parsing, PT_INTERP loading inside
  the independent root, mapped program headers, argument/environment/auxiliary
  vectors, zeroed BSS, guarded guest/signal stacks and a separately reserved brk
  heap. `loader.c` now uses a [freestanding APK bootstrap](CONCURRENT_EXEC.md) with
  raw Android policy checks and kernel auxv parsing. Rejects file/segment overflow, overlapping LOAD pages, invalid entry,
  invalid interpreter, executable stack and writable-executable LOAD segments.
  Limits: 128 program headers, 128 MiB file/image span, 128 arguments, 8 MiB stack,
  64 MiB brk reservation. Fixed-address mappings never replace existing memory.
- `enter.S`: transfers control with a 16-byte aligned guest stack and cleared TLS
  pointer. The Linux interpreter or static libc initializes its own TLS.
- `guest.c`: signal dispatch with raw syscalls, no Bionic/glibc/TLS/heap dependency.
  `build.sh` audits the combined runtime objects and their assembly entry points,
  then rejects unresolved imports, interpreter/TLS dependencies and relocations
  in the final loader. Read/write and other untranslated I/O go directly through the
  kernel. Other supported calls use the internal dispatcher; unsupported calls
  return explicit errors. There is no PRoot/Native fallback.
- `files.c` implements root-relative symlinks, dot/dot-dot and pinned cwd/dirfd
  lookup, with 4,096-byte paths and 40 symlink expansions. The original 255-level
  parent stack has been replaced by live parent lookup. open/create, stat/lstat,
  statx, access, readlink, chdir and the later [filesystem mutation operations](FILESYSTEM.md)
  are present. Internal root FD close/replacement is rejected. This is not yet the
  complete Linux filesystem/FD contract or a security namespace.
- [Signal actions and mask-bearing waits](SIGNAL_MASKS.md) use a freestanding
  bridge that separates guest SIGSYS masking from the private transport.
  sigprocmask changes the **saved guest
  signal frame**, not merely the handler's temporary mask; rt_sigreturn applies
  the requested mask. SIGSYS stays available to the runtime with its requested
  mask bit separately tracked. User SIGSYS handlers and guest altstacks are still
  unsupported. [Blocking signal handling](BLOCKING_SIGNALS.md) adds per-thread
  state and general-register context handling; full extended context is unfinished.
  Nested handlers, waits and siglongjmp now have explicit paired regressions.

The initial loader used Bionic only before guest entry. The current loader has
no libc/TLS dependency even during bootstrap. This permits APK runtime kernel
reentry under the same inherited seccomp filter for [concurrent/nonleader exec](CONCURRENT_EXEC.md).
The runtime image is fixed-address, with guest ASLR and runtime RELRO maintained;
stock enforcing policy remains unverified. Single-thread exec keeps its existing
in-process path. See [current compatibility status](COMPATIBILITY_PROGRESS.md).

The initial milestone copied main ELF/interpreter bytes into anonymous RW pages
and then applied final permissions. The subsequent [exec cost reduction](EXEC_PERFORMANCE.md)
uses validated MAP_PRIVATE file mappings for those images, with anonymous full
BSS pages and private partial-page zeroing. The later [command startup change](COMMAND_PERFORMANCE.md)
reads and rechecks bounded ELF metadata; ordinary code/data bytes are demand-paged,
without a full-image snapshot comparison. Guest shared-library `mmap` also retains
the kernel's file-backed semantics, including paging, sharing and EOF/SIGBUS.
The original copy-every-executable-mapping candidate failed on glibc's larger
reservation and was removed. A successful `mmap` is distinct from `execve` of app
data. Android's mapping restrictions are authoritative; failures are returned,
not bypassed or rewritten as success. Stock enforcing-device support is still
unproven. See [policy basis](../../docs/DIRECT_LINUX_EXPERIMENT.md#android-정책-기준).

## Reproduction

The existing ARM64 host supplies Ubuntu's dash/coreutils/libc. No package or
toolchain is downloaded. `fixtures.py` records their versions and SHA-256 values,
builds static/dynamic glibc regression programs, and creates malformed ELF cases.
Generated binaries are test assets only and are not checked into the repository.

```sh
bash tools/direct-linux/build.sh /tmp/terminal-direct-linux
python3 tools/direct-linux/fixtures.py /tmp/terminal-direct-linux/fixtures
./gradlew :android-runtime:assembleDebugAndroidTest \
  -ProotlessAcceleratedPayload=ABSOLUTE_VERIFIED_PAYLOAD_DIRECTORY \
  -ProotlessStdioBuffer=true --console=plain
python3 tools/direct-linux/package-probe.py \
  android-runtime/build/outputs/apk/androidTest/debug/android-runtime-debug-androidTest.apk \
  /tmp/terminal-direct-linux/libdirect-linux-probe.so \
  /tmp/terminal-direct-linux/elf-unsigned.apk \
  --loader /tmp/terminal-direct-linux/libdirect-linux-loader.so \
  --fixtures /tmp/terminal-direct-linux/fixtures
.android-sdk/build-tools/36.0.0/apksigner sign --v4-signing-enabled false \
  --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
  --out /tmp/terminal-direct-linux/elf.apk /tmp/terminal-direct-linux/elf-unsigned.apk
.android-sdk/build-tools/36.0.0/apksigner verify /tmp/terminal-direct-linux/elf.apk
```

Install this standalone test APK with Android MCP. Use its device shell tool:

```sh
am instrument -w \
  -e class dev.terminal.runtime.directlinux.DirectLinuxLoaderTest \
  -e directLinuxLoader true -e directLinuxBenchmark true \
  -e directLinuxLoaderSha256 SHA256_FROM_NATIVE_BUILD \
  dev.terminal.runtime.test/androidx.test.runner.AndroidJUnitRunner
```

Omit `directLinuxBenchmark` for correctness-only execution. `directLinuxTrace=true`
is a separate diagnostic option and is forbidden with benchmarks. The report is
`files/direct-linux-loader-result.json` inside the test app; retrieve it with
run-as after execution. The process launch itself uses the actual application
and central APK executable resolver. It rejects root, effective capabilities and
an attached tracer. The test creates and deletes its own root; symlinks are never
followed during cleanup. It does not change an installed user distribution.

## Verified result and limits

[Raw results, hashes and all 28 timing samples](results/elf-runtime-2026-09-24.json).
API 35 ARM64 redroid; SELinux Disabled, initial Seccomp=0, CapEff=0, TracerPid=0.
This does **not** establish stock-device compatibility.

Passed 34 process runs: GNU true/printf, dash builtins with redirection/chdir,
static/dynamic glibc checks and separate SIGBUS checks (7 positive runs), 13 ELF
rejections, and 14 benchmark processes. Tests cover auxv, arguments, BSS, guest
TLS, malloc/brk, absolute/relative symlinks, loops/escape attempts, cwd/dirfd,
ENOENT/EBADF/EFAULT/ELOOP/EEXIST, CLOEXEC, create/write/fsync, signal-mask query,
pending SIGUSR1 and delivery after unblock, and real SIGBUS beyond mapped EOF.
Review caught and corrected an earlier mask implementation that changed only
the handler's temporary kernel mask. The pending-signal test verifies the final
signal-frame implementation, not just its return code.
Android's inherited mask may already block SIGUSR1; the test explicitly establishes
its unblock precondition rather than assuming exec clears signal masks.

Both benchmark modes use the **same dynamic glibc program and file**. Latest
`rootless-runtime-20260920-emulated.3` uses the production engine flags and combined
faststat/stdio preload. For each PRoot sample, the matching acceleration counter
must increase by exactly 5,000; timing excludes counter reads and process startup.
Seven pairs alternate execution order, with warm data and no trace logging.

| 5,000 operations | Current accelerated Rootless | Direct Linux | Ratio |
| --- | ---: | ---: | ---: |
| stat of one ordinary file | 39.415 ms | 27.081 ms | 1.46x |
| open/read/verify/close | 59.004 ms | 45.343 ms | 1.30x |

These results are narrower than full filesystem/application performance. Direct
Linux does not yet implement fake-root identity and emulated hardlinks, and the
workload does not exercise those features. Python/Git/package operations, process
startup, cold storage, long-running sessions, power and concurrent changes still
need comparison. The initial transport probe's 9.9x figure is not used to describe
this glibc comparison.

Native warnings-as-errors/import checks and signing passed. Canonical JDK 17
`:android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test` passed
with the pinned accelerated payload: 180 runtime passes, one external-archive
fixture skipped, four image passes. The final instrumentation APK and Android
test passed. The product APK was checked to contain no Direct Linux payload.

Android MCP supplied application/device validation. Context7 returned only partial
auxv background; local NDK ABI headers, actual Linux ELF headers and the
[Arm ABI](https://github.com/ARM-software/abi-aa/blob/main/sysvabi64/sysvabi64.rst)
provided additional context. Raw kernel-source web URLs failed; no runtime claim
depends on those failed fetches. Replicant/codex_apps were unnecessary because
this step changes neither UI interaction nor connected-app state.

Remaining implementation after the process milestone: broader thread semantics,
multi-thread fork/shared-VM spawn,
execveat and complete signal virtualization/interruption; FD sharing/lifecycle and complete filesystem
semantics (basic mutations now work); fake identity/hardlinks; installer/package environment; backend/PTY/UI
integration and lifecycle tests. Unsupported process variants return EOPNOTSUPP or ENOSYS; ordinary fork/exec/wait
and bounded vfork compatibility now work. This is a concrete ELF milestone, **not completion of the user goal**.
