# Direct Linux subprocess FD cleanup

2026-09-24. This changes the separate experimental runtime, not the existing
Rootless engine or product backend.

## Diagnosis

The [application milestone](APPLICATIONS.md) completed Python/Git work, but its
Git section took 1,381.658 ms. A profile around each `subprocess.run` separated
total command duration from ELF exec stages. Even `git config` and `rev-parse`
took about 77–78 ms, with only about 7.5–7.7 ms spent replacing the ELF image.

The runtime returned ENOSYS for `close_range`, and the guest filesystem did not
expose `/proc/self/fd`. [CPython 3.12.3's subprocess implementation](https://raw.githubusercontent.com/python/cpython/v3.12.3/Modules/_posixsubprocess.c)
tries close_range around preserved descriptors, then falls back to directory
enumeration or individually closing FD numbers. The tested Python ELF imports
`close_range@GLIBC_2.34`. FD cleanup therefore warranted a compatibility fix
without relaxing image validation or changing subprocess's close_fds behavior.

The [before/after diagnostic](results/subprocess-profile-2026-09-24.json) uses
the same application script, programs and libraries; the later manifest adds
only two FD regression binaries. After the change, the first `git config` call
took 9.910 ms instead of 78.293 ms. The sum of 14 command durations fell from
1,323.372 to 384.686 ms. These are single diagnostic runs with profile output,
not statistical benchmark samples.

## Implementation and limits

`fds.c` shares a host-only FD sweep between guest close_range and exec's CLOEXEC
cleanup. It enumerates open descriptors via `/proc/self/fd` rather than iterating
to the FD limit. It preserves the root descriptor, active vfork barrier writer,
scanner, and (during exec) retained ELF source descriptors. The scanner closes
on completion/error. The host proc directory is never exposed to the guest.

The implementation follows the inclusive unsigned ranges and flags in the
[close_range contract](https://man7.org/linux/man-pages/man2/close_range.2.html).
Invalid flags or reversed ranges return EINVAL. CLOEXEC marks descriptors for
the next successful image replacement without closing them immediately.
Individual close errors are ignored. Enumeration and flag-setting errors are
reported rather than falsely claiming complete cleanup.

UNSHARE is a no-op under the current invariant: the APK launcher has a private
FD table, supported fork/vfork paths create private tables, and CLONE_FILES and
threads are rejected. Revisit this before adding shared tables. Signals are
blocked during dispatcher operations, so handlers cannot mutate the table
halfway through a sweep.

The sweep needs a readable host `/proc/self/fd` and one free descriptor. At
EMFILE/ENFILE it returns the open error; full-table compatibility with kernel
close_range remains a limitation. It does not call host close_range or require
a newer host implementation. The guest instruction still faces inherited
Android seccomp, which this emulation cannot override. No enforcing-device
support claim follows from this test.

## Validation and measurements

Static and dynamic glibc fixtures verify inclusive endpoints, holes, UINT_MAX,
invalid flags/ranges, multiple directory buffers, CLOEXEC marking, failed-exec
preservation, successful-exec closure, separate parent/child tables, closing
stdio while retaining root access, and the vfork parent barrier after range
closure. Earlier ELF/process/signal/filesystem/device/xattr/Python/Git checks
also pass. All 699 immutable fixture hashes remain unchanged after execution.

Diagnostic instrumentation passed in 6.835 seconds. The final run with tracing
and profiling disabled passed in 16.325 seconds: 87 top-level launches and 77
timing samples in the [raw report](results/close-range-2026-09-24.json). The
reference application's known cleanup failure remains explicitly excluded by
`directLinuxSkipApplicationReference=true`, recorded in the report. The four
existing paired comparisons remain enabled.

| Seven-run median | Direct Linux | Accelerated Rootless |
| --- | ---: | ---: |
| 5,000 stat calls | 26.959 ms | 39.624 ms |
| 5,000 open/read/close | 44.986 ms | 59.879 ms |
| Shell plus 30 external commands | 55.355 ms | 49.865 ms |
| 128-file create/copy/move/verify/remove | 53.064 ms | 96.996 ms |
| Python import block | 44.550 ms | No completed paired comparison |
| Git workflow plus Python file verification | 454.317 ms | No completed paired comparison |
| Python startup + full workflow + cleanup | 586.068 ms | Prior cleanup failure |

Against the previous Direct-only seven-run medians, Git duration decreased by
about 67% (1,381.658 to 454.317 ms) and the whole Python workflow by about 61%
(1,518.684 to 586.068 ms). These sequential milestone measurements are not an
interleaved before/after comparison. The command profile supports the diagnosis,
but neither establishes a whole-system speedup. The file-tree workload remains
about 1.83x faster than Rootless; repeated shell commands remain about 11% slower
in the current paired run.

Native warnings-as-errors compilation, import/TLS audit, fixture build, test
APK packaging/signature verification, and repository verification passed:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew \
  :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test \
  :android-runtime:assembleDebugAndroidTest \
  -ProotlessAcceleratedPayload=/tmp/terminal-unpack/prepared \
  -ProotlessStdioBuffer=true --console=plain
```

Runtime unit results: 180 passed, one optional external-archive test skipped.
Image: four passed. Unchanged unit tasks reused Gradle's up-to-date results.
Android MCP supplied live ARM64 API 35 redroid validation with CapEff=0,
TracerPid=0, SELinux Disabled and initial Seccomp=0. A stock enforcing phone was
unavailable. Context7 supplied FD-table background but not the narrow syscall
contract; CPython source, Linux man-pages and installed NDK headers supplied
that detail. AOSP allowlist fetches failed, so no new allowlist claim is made.
Replicant and connected-app tools were unnecessary for this test-only change.

## Reproduction

Use [ELF packaging](ELF.md#reproduction) with `fixtures.py --applications` and
loader SHA-256 `c6ed7790f901887aaa34e18be4e1964d8e37d9a36873ec311eb588e6c2f38cf8`.
`directLinuxLoader=true` enables correctness tests. Add
`directLinuxApplicationProfile=true` for the 14 command profiles. For separate
performance runs use `directLinuxBenchmark=true` and explicitly
`directLinuxSkipApplicationReference=true`; profiling cannot be combined with
benchmarking. Reference cleanup, enforcing-device validation, general threads,
package installation, networking, PTY and product session integration remain
outstanding.
