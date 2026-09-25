# Direct Linux syscall transport experiment

This develops a **separate**, unprivileged Linux runtime. The original syscall
transport probe is documented below; the subsequent [ELF runtime](ELF.md) now runs
real static/dynamic ARM64 glibc programs. The [process milestone](PROCESS.md)
adds shell external commands, scripts, fork/exec and measured process workloads.
[Exec cost reduction](EXEC_PERFORMANCE.md) records the first mapping improvement;
[bounded metadata loading](COMMAND_PERFORMANCE.md) reduces repeated-command
startup by another 9.8–10.7%, slightly ahead of Rootless in the measured workload.
[Argument snapshot reuse](ARGUMENT_PERFORMANCE.md) subsequently reduces the
96-argument command workload by 9.3–12.1%, bringing that measured gap to within
0.4% of Rootless; short commands and whole applications have no clear new gain.
[Short-command startup](SHORT_COMMAND_PERFORMANCE.md) removes redundant syscall
traps, signal-action scans and directory checks; 54 same-APK pairs show a median
paired time reduction of 2.3% for thirty short commands.
[Fork/exit startup costs](STARTUP_PERFORMANCE.md) separate process creation,
exec, libc startup and reaping. Omitting bootstrap-only mappings from fork
inheritance reduces the same short workload by a further 24.3% across 54 pairs.
These experiments are not a complete
distribution backend or user-selectable runtime. The
[design and remaining work](../../docs/DIRECT_LINUX_EXPERIMENT.md) retain the full
goal. Existing Rootless remains unchanged.

The [compatibility audit](COMPATIBILITY_AUDIT.md) inventories 45 remaining
implementation/validation areas, all 318 AArch64 syscall names in the pinned
NDK, and two newly reproduced mapping/FD cleanup defects. It distinguishes the
committed runtime, unfinished thread changes and stock-device evidence gaps.
The [implementation progress](COMPATIBILITY_PROGRESS.md) now records initial
pthread support, fork mapping and FD exhaustion fixes, and live-thread handling
after the leader exits. [Concurrent exec](CONCURRENT_EXEC.md) adds kernel-owned
thread teardown and nonleader conversion through a freestanding APK bootstrap.
[Concurrent fork and FD-table ownership](FORK_FD_TABLES.md) preserve copied runtime
state and clean private pins across fork, private-table clone and interrupted unshare.
[FD-based exec](EXEC_FD.md) adds execveat/fexecve, unlinked-inode execution,
script FD paths and precommit CLOEXEC errors, checked against native Linux.
It retains the full audit scope and remaining gates.

The [Python/Git milestone](APPLICATIONS.md) and subsequent
[subprocess FD cleanup](SUBPROCESS_PERFORMANCE.md) add real application checks
and isolate a major process-launch bottleneck. A general Linux environment and
enforcing-device validation remain incomplete.

[PTY and job-control checks](PTY.md) now compare interactive dash, GNU stty,
signals, resize and terminal hangup against the existing accelerated Rootless.
This does not establish overall compatibility parity or product session support.

[Networking and resolver checks](NETWORK.md) add IPv4/IPv6, anonymous socket IPC,
signal interruption, real glibc UDP/TCP DNS and Python HTTP/TLS comparisons against
Rootless. Named Unix sockets, DNS/NSS integration, external networking and stock
Android policy validation remain incomplete.

[Pathname Unix sockets](UNIX_SOCKETS.md) now support stream and datagram paths,
returned addresses and interrupted calls. The paired results explicitly record
Direct's long-name limitation and the reference engine's named-datagram failure.

[Signal mask compatibility](SIGNAL_MASKS.md) fixes full handler/wait masks killing
the private SIGSYS transport. Nested handlers, waits and nonlocal jumps are now
compared against Rootless. [Blocking/cancellation support](BLOCKING_SIGNALS.md)
now covers per-thread waits; guest alternate stacks remain unfinished.

`probe.c` handles raw ARM64 `openat` in the calling thread with seccomp TRAP and
SIGSYS. `syscall.S` supplies separate host/guest SVC sites. The host site is exempt
only from our own filter, including both halves of its 64-bit instruction pointer.
Kernel/Android restrictions remain in force. This is not a malicious-guest sandbox.

Only read-only `/probe/NAME` fixture paths without dots or slashes are supported.
O_NOFOLLOW is forced. These are experiment restrictions, not the intended Linux
filesystem semantics. The handler uses page-bounded self `process_vm_readv`, raw
syscalls, stack storage, and a thread-local diagnostic counter. This transport
probe does not load guest ELF or replace guest TLS. The separate ELF runtime uses
a libc/TLS-independent dispatcher; do not transplant the probe's Bionic TLS counter
into glibc.

## Build and run from an actual Android application

Use the repository-selected NDK r28 and host Clang; no additional toolchain:

```sh
bash tools/direct-linux/build.sh /tmp/terminal-direct-linux
./gradlew :android-runtime:assembleDebugAndroidTest --console=plain
python3 tools/direct-linux/package-probe.py \
  android-runtime/build/outputs/apk/androidTest/debug/android-runtime-debug-androidTest.apk \
  /tmp/terminal-direct-linux/libdirect-linux-probe.so \
  /tmp/terminal-direct-linux/unsigned.apk
.android-sdk/build-tools/36.0.0/apksigner sign --v4-signing-enabled false \
  --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
  --out /tmp/terminal-direct-linux/probe.apk /tmp/terminal-direct-linux/unsigned.apk
.android-sdk/build-tools/36.0.0/apksigner verify /tmp/terminal-direct-linux/probe.apk
```

For the current accelerated-engine comparison, pass
`-ProotlessAcceleratedPayload=ABSOLUTE_VERIFIED_PAYLOAD_DIRECTORY -ProotlessStdioBuffer=true`
to Gradle. The existing hash gate checks the pinned payload. The instrumentation
uses the production `RootlessAccelerationProfile` engine settings when built this
way. The same Bionic probe runs in every condition. Its raw SVC calls do not use
the glibc preload DSO, so the result does not compare accelerated glibc workloads.

Install the **standalone test APK** with Android MCP. Run this device shell command
with Android MCP, substituting the digest printed by the native build:

```sh
am instrument -w \
  -e class dev.terminal.runtime.directlinux.DirectLinuxProbeTest \
  -e directLinuxProbe true \
  -e directLinuxProbeSha256 SHA256_FROM_BUILD \
  dev.terminal.runtime.test/androidx.test.runner.AndroidJUnitRunner
```

The opt-in test checks its package, APK-owned executable and digest, creates its
own small directory and symlink, starts the probe via `NativeExecutableLoader`,
runs correctness checks, and alternates direct/TRAP/PRoot 7 times. Each timed loop
performs 5,000 open/read/fcntl/close operations and checks contents/CLOEXEC. Setup,
process startup and teardown are excluded. No user rootfs, shell or service is
restarted. The probe refuses root, effective capabilities and an attached tracer.

Read `files/direct-linux-probe-result.json` under the test app afterward (run-as is
appropriate **only to retrieve this artifact**, not to establish app-policy
evidence). The file contains hashes, initial process policy and all 21 samples;
no device identifier or app-private path. Production APKs do not contain the probe.

## Results, 2026-09-24

ARM64 API 35 redroid, 4 KiB pages, same warm app-private file. The initial process
had `CapEff=0`, `TracerPid=0`, **Seccomp=0**, and device **SELinux Disabled**.
These are not stock enforcing Android results.

| Mode | Median, 5,000 operations | Voluntary switches |
| --- | ---: | ---: |
| Direct kernel calls | 8.797 ms | 0 |
| In-process TRAP | 26.436 ms | 0 |
| Pinned accelerated PRoot engine | 261.583 ms | 10,000 |

[Raw current-engine result](results/accelerated-engine-2026-09-24.json).
The approximately 9.9x difference is limited to this fixed-path raw-syscall
experiment, with one TRAP per open and an already-open root directory. General
path/symlink/fake-ID/FD/exec logic will add work. It is not a speedup claim for
Python, Git, apt, sequential I/O, shell startup, or the whole Rootless environment.
An earlier [baseline-engine result](results/baseline-2026-09-24.json) is preserved
for provenance and is not the current performance reference.

Passed: anonymous RW→RX execution and rewriting via RW→RX, bounded path copy,
EFAULT including inaccessible next-page handling, page-end NUL, ENOENT/ELOOP,
unsupported write/path rejection, CLOEXEC/content/close, four threads with guarded
signal stacks, fork, and a stacked EACCES filter also affecting the host trampoline.
That filter test does not simulate the complete Android seccomp/SELinux policy.

Native build uses `-Wall -Wextra -Werror` and 16 KiB ELF segment alignment. The
packager validates ELF architecture/alignment and refuses a production app APK.
Central resolver tests cover absent, valid, and escaping-symlink probe paths.
Canonical app build, 181 runtime tests (one external archive fixture skipped),
four image tests, instrumentation APK build, and the opt-in Android test passed.
At this initial transport milestone, actual-ELF execution was still open; the
subsequent [ELF runtime results](ELF.md) supersede that limitation. Stock-device
support, complete signal virtualization and application benchmarks remain open.

The canonical validation used JDK 17 and the existing wrapper/cache:

```sh
./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test \
  :android-runtime:assembleDebugAndroidTest \
  -ProotlessAcceleratedPayload=ABSOLUTE_VERIFIED_PAYLOAD_DIRECTORY \
  -ProotlessStdioBuffer=true --console=plain
```

The first sandboxed Gradle invocation could not write its existing cache lock;
the authorized retry reused that cache and passed. Android MCP's initial install
attempt rejected incremental installation and automatically succeeded with streamed
installation. Subsequent packaging disabled v4 signing for this test APK, and its
streamed install passed directly. These were tooling/environment issues, not
successful checks silently substituted for failed runtime assertions.

Android MCP supplied install/instrumentation/device evidence; context7 supplied
the Linux seccomp contract. Some AOSP URLs failed through the web fetcher; current
policy pages and the Bionic public header were available through search. Neither
these sources nor the injected filter prove stock-device support. Replicant and
codex_apps were not used: no UI interaction or connected-app action was required.
