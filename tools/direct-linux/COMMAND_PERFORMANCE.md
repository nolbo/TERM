# Direct Linux repeated-command startup

2026-09-24. The remaining shell startup gap came partly from reading every byte
of each executable and interpreter into temporary memory, then touching all
mapped file bytes again to compare them. The new loader reads bounded ELF
metadata and demand-pages the payload. Two before/after comparisons reduced
shell plus thirty external commands by **9.8–10.7%**; the final paired Rootless
comparisons were **2.2–4.2% shorter**. This is a narrow warm-workload result on
redroid, not general performance or compatibility parity.

## Diagnosis

[Raw diagnostics and timing samples](results/command-performance-2026-09-24.json)
record all eight runs in execution order. Profiles are separate from throughput
measurements. Each profile contains the outer program, dash, and thirty true
images; the table uses the mean of only those thirty true images.

| Per-exec phase | Before | After |
| --- | ---: | ---: |
| Read and validate | 171.271 µs | 82.383 µs |
| Map images, including comparison and BSS | 238.697 µs | 42.864 µs |
| File-byte comparison within mapping | 189.731 µs | 4.475 µs |
| Dispose temporary input state | 62.403 µs | 10.295 µs |
| Bytes read by preparation, main plus interpreter | 271,760 | 1,435 |

The comparison caused file pages to be faulted in even when a short command
would not use their contents. Full-file temporary copies also required allocation,
copying and teardown. This overhead was specific to Direct's in-process exec
implementation; removing the PRoot tracer alone did not remove it.

An intermediate MAP_POPULATE experiment moved the cost from byte comparison to
file mapping: mean mapping syscall cost rose from 24.165 to 192.535 µs while
comparison fell to 15.745 µs. Shell median was 53.416 ms, versus 52.351 ms before;
Rootless also varied between those runs. It did not establish a throughput gain
and was removed. The final implementation does not use MAP_POPULATE.

## Implementation and compatibility

`exec.c` reads at most a 256-byte initial prefix, the bounded program-header
table, and the PT_INTERP pathname into private storage. Every exec still opens
and validates its program and interpreter. No content or pathname cache is used.
The retained source FD pins the selected inode through file mapping.

Structural checks remain: ELF architecture/type, file and image bounds, program
header count, segment alignment and overlap, executable entry point, interpreter
validity, executable permissions, set-ID rejection, executable-stack rejection and
no writable-executable LOAD mappings. BSS zeroing and mapping permissions are
unchanged. The mapper compares the portions of the ELF header, complete program
header table and interpreter string present in each LOAD mapping with their
validated private copies. Metadata mismatches abort the load before guest entry.

**Payload bytes are now ordinary demand-paged file contents, not a full-image
snapshot checked before entry.** An in-place payload edit during preparation can
be observed; the new host test explicitly verifies this contract. The earlier
loader already allowed subsequent backing-file writes/truncation after its
comparison. Neither design freezes the inode or implements complete Linux
executable-write exclusion/ETXTBSY. Truncation can still produce SIGBUS. Metadata
comparison is also not an atomic file snapshot or executable authentication.
This experiment remains unsuitable as a malicious-guest isolation boundary.

Reading the headers and PT_INTERP separately follows the preparation pattern in
[Linux's ELF loader](https://github.com/torvalds/linux/blob/v6.6/fs/binfmt_elf.c),
but does not establish equivalence with kernel exec semantics. Mapping policy is
still enforced by Android/the kernel; no anonymous executable fallback, elevated
permission or executable-cache bypass was added. Existing Rootless and the
production runtime selection are unchanged.

## Measurements

Each benchmark run has seven alternating Direct/accelerated Rootless pairs using
identical programs, libraries and inputs. Native CLOCK_MONOTONIC measures shell
creation, thirty `/bin/true` commands and reaping, excluding the outer launcher.
Profiling and tracing are disabled. The APK was switched back to the original
loader and then to the new loader for the repeat; the reference payload was not
modified. These are sequential APK comparisons, not simultaneous measurements.

| Shell + 30 commands | Direct median | Rootless median | Direct vs Rootless |
| --- | ---: | ---: | ---: |
| Before | 52.351 ms | 47.386 ms | 10.5% longer |
| After | 46.753 ms | 48.819 ms | 4.2% shorter |
| Before, repeat | 52.326 ms | 47.855 ms | 9.3% longer |
| After, repeat | 47.195 ms | 48.243 ms | 2.2% shorter |

The final run's nearest-rank p95 is 48.831 ms for Direct and 50.077 ms for
Rootless. With only seven samples, that statistic is the maximum, not a reliable
tail-latency estimate. The initial before/after Direct reduction was 10.7%; the
repeat reduction was 9.8%. The small advantage over Rootless needs broader/device
measurements and should not be generalized.

File-call medians remained around 27 ms for 5,000 stat calls and 45 ms for 5,000
open/read/close calls. All Rootless samples in those cases record 5,000 preload
hits. The 128-file tree case went from 51.142 to 49.133 ms initially and 51.330 to
49.607 ms in the repeat. The complete Direct Python/Git work was 551.531→545.525 ms
initially but 560.501→561.157 ms in the repeat: **no consistent additional whole
application speedup was established**. The previously recorded Rootless cleanup
failure is explicitly skipped in these timing runs, so no paired Git claim is
made. CPU, memory, cold-cache and physical-device thermal effects were not measured.

## Reproduction and validation

Use the existing [ELF build/package/run steps](ELF.md#reproduction) with application
fixtures, then add `-e directLinuxProfile true` for diagnostics or
`-e directLinuxBenchmark true -e directLinuxSkipApplicationReference true` for the
timing run. Do not combine profile/trace with benchmark flags. The expected loader
digest is `54e56976a5dd7989a58a435cecc9a2d41d28af4d20b45ccc3192c639955c95ef`.
The before digest is `5f6a714c8789cb3f13efb31af2e824ec0a8c3bf1779775f51fe6c4fbfd8b74aa`.
All runs used the same 714 fixture hashes, recorded in the
[final runtime report](results/command-runtime-2026-09-24.json).

The new ARM64 host regression uses the actual ELF reader/mapper, with host file
adapters; it does not establish guest-path or Android-policy correctness:

```sh
clang -O2 -Wall -Wextra -Werror tools/direct-linux/exec-metadata-test.c \
  tools/direct-linux/scratch.c tools/direct-linux/memory.c tools/direct-linux/copy.S \
  -o /tmp/direct-linux-exec-metadata-test
/tmp/direct-linux-exec-metadata-test
```

Passed: metadata mutation rejection for the ELF header, page-crossing program
headers and interpreter; bounded input reads; demand-paged payload behavior;
pathname replacement retaining the original inode. Native build warnings, import
and TLS audits and APK signature validation passed. Canonical JDK 17 Gradle build
plus `:android-runtime:assembleDebugAndroidTest` succeeded; unchanged runtime/image
unit tasks were UP-TO-DATE, reusing 180 passes plus one optional fixture skip and
four image passes rather than rerunning them.

The final Android run with `directLinuxNetwork` and `directLinuxPty` passed in
9.870 s: 56 ordinary launches and two PTY scenarios. It includes existing malformed
ELF, static/dynamic loading, scripts, repeated fork/exec, signals/FDs, BSS, file
operations, Direct Python/Git, and paired network/DNS/TLS/Unix/PTY checks. Existing
Unix limitations remain explicit: Direct's 100-byte pathname fails ENAMETOOLONG,
while the Rootless reference's named datagram send fails ENOENT. These expected
observations do not count as successful communication.

Android MCP installed each APK, ran instrumentation under the normal app UID and
retrieved results. The target remains ARM64 API 35 redroid, SELinux Disabled,
initial Seccomp=0, CapEff=0 and TracerPid=0. Stock enforcing-device policy remains
unverified. Context7 did not provide the narrow mmap detail; primary Linux/man-page
documentation supplied the fallback. A raw kernel-source web fetch timed out;
the same official repository's GitHub view worked. No Android MCP call failed.
Replicant/codex_apps and the privileged AVF checklist were irrelevant to this
loader-only change. Threads, full signal/identity/filesystem semantics, packages,
product sessions and overall Rootless compatibility remain unfinished.
