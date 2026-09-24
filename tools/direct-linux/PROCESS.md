# Direct Linux process and exec milestone

2026-09-24. This extends the [ELF experiment](ELF.md) to ordinary shell external
commands and scripts. It remains an independent standalone test APK. Existing
Rootless data, production payloads and session ownership are unchanged. This is
functional progress, **not completion of the Linux environment or speed goal**.

## Implementation and ownership

The APK runtime survives guest `execve`; the Android kernel never executes the
app-data ELF. Re-executing the APK loader would leave inherited seccomp rules
referring to old addresses, so that approach is not used. No ptracer, privilege,
OS-policy change, fallback runtime or alternate target SDK is introduced.

- `loader.c` performs initial Bionic launch/policy setup only. `exec.c` now owns
  initial ELF preparation and subsequent image replacement. `guest.c`, `exec.c`
  and `memory.c` are linked and audited together: no libc, TLS, allocation or
  compiler-runtime imports are allowed, except three raw assembly entry points.
- Exec copies arguments/environment and ELF bytes into bounded private staging
  mappings before changing the old image. Invalid ELF, inaccessible pointers,
  missing files and exceeded limits return errors and retain old code/FDs/TLS.
  There are at most 128 arguments, 256 environment strings, 128 KiB per string
  and at most 2 MiB string storage; these are explicit experimental bounds.
- After the commit point, caught signal dispositions are reset, ignored
  dispositions and the guest mask/pending signals survive, CLOEXEC FDs close,
  and stale robust-list/clear-child-TID pointers are cleared. Tracked guest
  mappings, including shared-object mappings, stack and heap, are removed.
  A new stack/auxv/heap/TLS startup replaces them, preserving PID and cwd.
  The saved signal frame enters the new ELF; the runtime's signal stack remains.
  A failure after commit exits 126 with a diagnostic instead of returning into
  discarded code. This is not Linux's complete late-exec failure behavior.
- `memory.c` tracks at most 2,048 guest mapping intervals; unmap and fixed
  replacement update ownership. Guest MAP_FIXED replacement is limited to its
  own mapped/reserved regions. ET_EXEC uses NOREPLACE, including repeated static
  exec. The tracker is for lifetime correctness, not a hostile-code sandbox.
- Shebang scripts support one optional argument, four interpreter expansions,
  and a bounded first line. A truncated 256-byte line is rejected. `AT_EXECFN`
  keeps the requested path; `/proc/self/exe` reports the resolved actual ELF.
- Fork uses kernel copy-on-write with independent runtime state. Pipe/dup, wait
  status, process-group calls and signal waits support shell jobs. Blocking I/O,
  wait and sleep retain their kernel interrupt behavior. Dispatch transitions
  block asynchronous signals; explicit signal waits allow nested handlers when
  no mutable transition is in progress.
- Vfork compatibility uses a copy-on-write child and a protected pipe barrier:
  the parent waits until image commit or child exit. It accepts glibc ARM64's
  current-stack argument. **Writes in the child are not shared with the parent**;
  arbitrary CLONE_VM, separate-stack spawn and pthread creation are unsupported.
  This is not a claim of complete raw Linux vfork/clone semantics.

The implementation follows the relevant [exec contract](https://man7.org/linux/man-pages/man2/execve.2.html)
and [clone ABI](https://man7.org/linux/man-pages/man2/clone.2.html) where described
above. Context7 supplied robust-futex ABI background; local NDK headers and the
actual host glibc vfork assembly established the ARM64 calling convention.
Complete robust-mutex owner-death cleanup, extended SVE/SME state, guest altstack,
user SIGSYS handling, execveat, threads/spawn and full signal restart/cancellation
remain unimplemented. The current standalone test root is not a package system.

## Verification

Use the commands in [ELF.md](ELF.md#reproduction); fixture preparation now also
builds `process-test.c` as static and dynamic glibc executables. The same opt-in
`DirectLinuxLoaderTest` runs all checks and optional benchmarks.

[Raw results and hashes](results/process-runtime-2026-09-24.json) record **56
launcher runs**, with additional forks/execs inside them, and **42 timing samples**.
The final instrumentation run returned `OK (1 test)` in 6.807 seconds. Tests include:

- Existing ELF/path/TLS/signal/EOF-SIGBUS checks and 13 malformed-file rejections.
- Unmodified dash external commands, pipeline data, command substitution,
  background wait, executable scripts, arguments and environment propagation.
- Static/dynamic glibc fork isolation, pipe EOF, child exit status, stopped child,
  WNOHANG and SIGKILL reaping; vfork parent suspension and failed-exec exit.
- Exec PID, custom argv[0], spaces, environment replacement, relative path/cwd,
  AT_EXECFN, resolved `/proc/self/exe`, CLOEXEC and inherited open descriptors.
- Reset caught handler, retained ignored handler, blocked/pending signal across
  exec; signal wait interrupted by a guest handler which itself performs stat.
- ENOENT/ENOEXEC/EFAULT/E2BIG/EACCES/ELOOP without destroying the calling image.
- Thirty self-execs for each of static and dynamic ELF, checking fresh TLS and
  release of a fixed guest mapping on every iteration.

Native warnings-as-errors, complete freestanding import/TLS audit, shell/Python
syntax, APK signing and diff checks passed. The canonical JDK 17 command passed:

```sh
./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test \
  :android-runtime:assembleDebugAndroidTest \
  -ProotlessAcceleratedPayload=ABSOLUTE_VERIFIED_PAYLOAD_DIRECTORY \
  -ProotlessStdioBuffer=true --console=plain
```

Runtime: 180 passes, one external-archive fixture skipped. Image: four passes.
Later instrumentation-only edits were rebuilt and exercised on Android. Production
APK inspection found no Direct Linux payload. No privileged AVF integration or UI
check was applicable to this standalone runtime change.

Android MCP installed and ran the test app. It still reports ARM64 API 35 redroid,
SELinux Disabled, initial Seccomp=0, CapEff=0, TracerPid=0. This provides **no proof
of stock enforcing-phone support**. Replicant/codex_apps were not needed because
there was no UI or connected-app action. MCP calls succeeded.

During development, the shell exposed the missing vfork stack convention and
signal-wait path. Both were fixed and covered by the final run. A first wall-time
measurement was quantized by Android `Process.waitFor(timeout)` observation and
was discarded as a performance measurement. `observation_ms` remains diagnostic
only; every final benchmark uses guest CLOCK_MONOTONIC around actual work.

## Performance and next work

Seven alternating pairs use the same fixture files/programs and the pinned
`rootless-runtime-20260920-emulated.3` engine plus production faststat/stdio profile.
Each file workload verifies 5,000 actual preload hits in the Rootless process.
Shell timing includes fork, shell startup, thirty `/bin/true` invocations and
reaping, but excludes the outer APK/PRoot launcher. Trace logging is disabled.

| Workload | Accelerated Rootless median | Direct Linux median | Observation |
| --- | ---: | ---: | --- |
| 5,000 stat calls | 39.380 ms | 26.761 ms | Direct Linux 1.47x faster |
| 5,000 open/read/close calls | 59.066 ms | 44.694 ms | Direct Linux 1.32x faster |
| Shell + thirty external commands | 49.595 ms | 84.300 ms | Direct Linux 1.70x slower |

The process result is a **remaining performance regression against the reference**,
not a successful overall speed improvement. Its cause is not yet profiled; image
staging/copy, fork and state reset are candidates, not proven explanations. The
next performance work is to measure these costs and improve the real command
workload without weakening ELF validation, cleanup, policy or signal behavior.
Broader Linux semantics, independent installation/packages, common session/PTY
integration and enforcing-device verification are still required by the goal.
