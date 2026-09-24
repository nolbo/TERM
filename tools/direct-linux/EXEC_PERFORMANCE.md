# Direct Linux exec cost reduction

2026-09-24. The [process milestone](PROCESS.md) ran shell commands correctly but
was 1.70x slower than current accelerated Rootless for a shell plus thirty external
commands. This step measures and reduces that cost. It does not replace Rootless,
add a user-selectable environment, or establish completion of the overall goal.

Historical milestone: [bounded metadata loading](COMMAND_PERFORMANCE.md) later
removes the full-image byte snapshot/comparison described here. It preserves
structural validation and updates the measured repeated-command result.

## Evidence and decision

[Diagnostic profiles](results/exec-profile-2026-09-24.json) time seven exec phases
and, in later variants, five mapping subphases. Every profile includes the outer
program, the shell, and thirty `/bin/true` images. The comparison below uses only
the thirty true images. Profiling is separate from throughput measurements.

| Total for thirty execs | Initial anonymous loader | Final file-mapped loader |
| --- | ---: | ---: |
| Read and validate | 5.905 ms | 5.339 ms |
| Reset signal/FD state | 1.202 ms | 1.262 ms |
| Remove old guest mappings | 6.834 ms | 5.468 ms |
| Map and populate new ELF images | 34.997 ms | 6.665 ms |

The byte-at-a-time copy was a candidate, but replacing it alone barely changed
throughput. A finer profile of that integer-copy variant found 14.089 ms in cache
maintenance and 14.611 ms in final protection changes, versus 1.915 ms copying.
The ordinary file mapping path avoids repeatedly creating and flushing fresh
anonymous code pages. Linux ARM64's [executable-page cache handling](https://github.com/torvalds/linux/blob/v6.6/arch/arm64/mm/flush.c)
tracks whether a file page needs instruction-cache synchronization. The kernel
remains responsible for that operation; no application policy is disabled.
Context7 supplied general cache/TLB background, and kernel/glibc sources provided
implementation context. The measured profiles establish this experiment's cost.

Changes:

- Keep full checked ELF metadata and a bounded private byte snapshot during
  preparation. Retain the same open source FD through mapping; exclude these
  temporary FDs from guest CLOEXEC cleanup, then close them after loading.
- Map LOAD file pages with ordinary MAP_PRIVATE and final permissions, compare
  their bytes against the preparation snapshot, and anonymously map full BSS
  pages. File-policy failures terminate the attempted image load; there is no
  anonymous-copy fallback when executable file mapping is denied.
- Zero the entire partial BSS page, including padding beyond p_memsz. Initial
  glibc startup uses this zeroed tail; the first candidate that zeroed only to
  p_memsz failed before libc startup and was corrected. Writable-executable ELF
  segments remain rejected. Any executable partial BSS page is modified with X
  removed, then cache-maintained and restored; no RWX transition is introduced.
- Use bounded integer-register ARM64 copy/equality routines. They do not touch
  SIMD state, TLS, libc, or bytes outside the specified range. The combined
  runtime import audit still permits only the original three syscall/signal
  assembly dependencies.
- Coalesce adjacent guest ownership intervals. Unmap holes remain unowned even
  if a later host allocation occupies them. A host regression verifies one
  unmap for a contiguous populated image and preservation of every possible
  interior/edge host hole. This reduces teardown calls; it is not presented as
  an independently established whole-workload speedup.

This deliberately changes initial main/interpreter memory from anonymous copies
to file-backed mappings, matching the already-used shared-library mapping model.
Validation metadata is private and mapped bytes are compared before entry, but
**the backing file is not frozen for the process lifetime**. Later concurrent
writes/truncation retain file-mapping behavior, including possible SIGBUS. Complete
Linux executable-write exclusion/ETXTBSY across processes is still missing; this
is neither an immutable executable cache nor an isolation boundary. Source FD
pinning prevents pathname replacement from redirecting an already-open image.

An executable memfd/cache or version-only content cache was not needed. Every
exec still opens and validates its selected program. In-place updates and atomic
inode replacement are explicitly tested. Existing runtime selection, central
APK executable policy, independent root and service/session ownership are intact.

## Reproduction and validation

Follow [ELF build/package/run instructions](ELF.md#reproduction). Native regression
commands use the existing ARM64 host compiler, with no downloads:

```sh
clang -O2 -Wall -Wextra -Werror tools/direct-linux/copy-test.c \
  tools/direct-linux/copy.S -o /tmp/direct-linux-copy-test
/tmp/direct-linux-copy-test
clang -O2 -Wall -Wextra -Werror tools/direct-linux/memory-test.c \
  tools/direct-linux/memory.c -o /tmp/direct-linux-memory-test
/tmp/direct-linux-memory-test
```

For a diagnostic run add `-e directLinuxProfile true` to instrumentation instead
of `directLinuxBenchmark`. Only its dedicated shell sample receives
DIRECT_LINUX_EXEC_PROFILE=1. The test rejects profiler/trace plus benchmark flags,
removes inherited diagnostic environment variables, deletes a stale result before
starting and writes a result only on success. The executable digest must match.

Passed: warnings-as-errors and freestanding symbol/TLS audits; copy/equality over
all short alignments, long tails and guarded page boundaries; mapping ownership
regression; shell/Python syntax; APK signing; diff checks; standard JDK 17 build:

```sh
./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test \
  :android-runtime:assembleDebugAndroidTest \
  -ProotlessAcceleratedPayload=ABSOLUTE_VERIFIED_PAYLOAD_DIRECTORY \
  -ProotlessStdioBuffer=true --console=plain
```

Runtime: 180 passes and one external-archive fixture skipped. Image: four passes.
Later instrumentation-only changes were rebuilt. Final Android benchmark run:
`OK (1 test)`, 7.030 seconds, **59 top-level process runs and 42 timing samples**.
The diagnostic run for the identical final loader passed separately. Existing
ELF corruption, fork/exec, signals, pipe, FD, static/dynamic TLS and repeated-exec
checks still pass. New checks cover zeroed BSS page padding, temporary-FD closure
after every exec, untouched fixture file hashes and updated program selection.
The production APK contains no Direct Linux payload.

Android MCP provided install, actual app launch and result retrieval. The device
remains redroid ARM64 API 35, SELinux Disabled, initial Seccomp=0, CapEff=0 and
TracerPid=0. **Stock enforcing-device compatibility remains unverified.** A crash
log query provided only historical unrelated logs; a temporary raw fault trace
located the BSS-padding error and was removed before final validation. There were
no MCP transport failures. No UI/connected-app change required replicant or
codex_apps, and the privileged AVF checklist was not applicable.

## Current measured result and remaining work

[Final raw results](results/exec-performance-2026-09-24.json) use seven alternating
pairs, identical fixture programs/data, and the pinned accelerated Rootless
engine/production preload. Profile and trace are disabled. File workloads verify
5,000 preload hits per Rootless sample. Guest CLOCK_MONOTONIC measures actual work;
Android `observation_ms` is diagnostic only. Shell time includes shell creation,
thirty commands and reaping, excluding the outer launcher.

| Workload | Accelerated Rootless | Direct Linux | Comparison |
| --- | ---: | ---: | --- |
| 5,000 stat calls | 39.619 ms | 26.759 ms | 1.48x faster |
| 5,000 open/read/close calls | 59.662 ms | 44.688 ms | 1.34x faster |
| Shell + thirty external commands | 47.911 ms | 51.995 ms | 1.09x slower |

Compared with the previous experiment's 84.300 ms, repeated-command time fell
about 38%. These historical before/after runs are not a simultaneous A/B trial;
the current Rootless comparison is paired within the final run. Direct Linux is
still about 9% slower for this shell case, and broad application performance is
unproven. Remaining startup costs need profiling beyond image replacement.

Threads/spawn, complete filesystem/identity/hardlink semantics, robust signal
handling, independent installation/packages, actual Python/Git/apt workloads,
common session/PTY integration and enforcing-phone validation are still needed.
The overall goal remains active and incomplete.
