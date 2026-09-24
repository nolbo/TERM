# Direct Linux Python and Git milestone

The later [subprocess optimization](SUBPROCESS_PERFORMANCE.md) fixes FD cleanup
cost and records updated measurements. The results below preserve this initial
application milestone and its unresolved reference cleanup failure.

2026-09-24. Unmodified Ubuntu ARM64 Python 3.12 and Git now complete a local
development workflow in the standalone test APK. This adds executable evidence
beyond shell/coreutils. It is still an experimental runtime, not an installed
distribution backend, and does not replace existing Rootless environments.

## Implemented compatibility

Python `shutil.copytree` initially failed with ENOSYS from listxattr. `files.c`
now translates get/set/list/remove extended attributes and their no-follow forms;
FD variants use the real descriptor. Ordinary targets are pinned before using
their proc-FD alias. Symlink operations use a pinned parent and a no-follow leaf
operation, retaining pathname-race semantics without following the leaf into a
host absolute target. Real permission, unsupported-filesystem, missing-attribute
and buffer errors are preserved; attributes are not silently discarded.
The implementation follows [getxattr](https://man7.org/linux/man-pages/man2/getxattr.2.html)
and [listxattr](https://man7.org/linux/man-pages/man2/listxattr.2.html) semantics.

Git initially failed because `/dev/null` was absent. `devices.c` now supplies a
fixed table of `/dev/null`, `/dev/zero`, `/dev/random` and `/dev/urandom`, checked
as the expected character devices at launcher initialization. The app creates
only ordinary enumeration placeholders in its independent root; it requires no
mknod, mount or elevated permission. Rootfs symlinks at those names are rejected,
existing regular files are never truncated, and device setup errors are explicit.

Lookup identifies the guest device directory by device/inode, honors its search
permission and opens only the four fixed host paths with O_NOFOLLOW. Existing
Android policy still applies. The namespace cannot add, remove or rename device
entries, or rename/remove the device directory through guest filesystem calls.
stat/fstat return the real character-device metadata, while getdents64 adjusts
the four entries' type and inode to agree. Ordinary directory entries retain
their original data. Reads/writes stay in the kernel and retain real null, zero
and random-device behavior. See the Linux [null/zero contract](https://man7.org/linux/man-pages/man4/null.4.html)
and [getdents64 layout](https://man7.org/linux/man-pages/man2/getdents.2.html).

This is a minimal byte-device view. It is not a host `/dev` bind, devpts, a PTY
implementation or an Android capability-broker API. Binder/input/other Android
device paths are not mapped. The same-UID security limitations of the existing
compatibility runtime remain; neither this path walker nor its fixed table is
an isolation boundary for arbitrary guest machine code.

## Functional evidence

The opt-in `fixtures.py --applications` profile copies the existing Python
stdlib, Git templates and recursive ELF dependencies into disposable test assets.
No interpreter/program binary is rebuilt or downloaded. Only ARM64 ELF is
accepted; data files retain a non-executable manifest flag. Source package
versions and all 697 fixture hashes appear in the
[final report](results/applications-2026-09-24.json).

`application-test.py` verifies:

- Standard-library/native-module imports, JSON with Korean text, SHA-256,
  gzip/bzip2/lzma round trips, decimal and a ctypes libc call.
- A committed/reopened 64-row SQLite database, ZIP extraction, recursive copying
  and exact preservation of a binary user xattr.
- Git init/config, staging 128 files, two commits, clean status, branch creation
  and switches with every file checked after each switch, and `git fsck --full`.
- Python's normal FD-based TemporaryDirectory cleanup of the entire repository.

No Git thread/hardlink setting is changed to make the test pass. Commit identity
and dates are fixed test data. This does not establish networking, remote clone,
repack/GC, general pthread use, package installation or Python's whole test suite.

Both static/dynamic C fixtures additionally verify binary xattr values, size
queries, ERANGE/EEXIST/ENODATA, no-follow symlink isolation, xattrs on an unlinked
open FD, null/zero/random-device reads, stat/readdir consistency, namespace
mutation denial, missing Binder access and device-directory search permissions.
The earlier ELF/process/filesystem regressions still pass.

## Measurements and the failed reference run

The full Python/Git reference workload **failed twice in the unchanged pinned
accelerated Rootless runtime**. Git operations completed, but Python cleanup
returned ENOTEMPTY in `.git/objects`, with residual `.l2s.*` hardlink-emulation
metadata visible from Android. The
[failure record](results/applications-reference-failure-2026-09-24.json) preserves
the engine/DSO hashes, failed step and diagnostic excerpt. The exact cause has
not yet been isolated. This is not a passing reference measurement, and the
reference engine has not been modified or its link emulation disabled here.

After recording that failure, the final diagnostic run explicitly excluded only
the application reference with `directLinuxSkipApplicationReference=true`. The
report marks `application_reference_skipped: true`. It contains 85 successful
top-level launches and 77 samples: seven pairs for each of the four existing
workloads, plus seven Direct-only application runs producing three nested times.
Instrumentation passed in 23.635 seconds. Without that explicit flag, the full
application comparison remains a failing check, not an automatic skip.

| Seven-run median | Direct Linux | Accelerated Rootless |
| --- | ---: | ---: |
| 5,000 stat calls | 26.901 ms | 39.452 ms |
| 5,000 open/read/close | 44.986 ms | 59.384 ms |
| Shell plus 30 external commands | 53.216 ms | 47.525 ms |
| 128-file create/copy/move/verify/remove | 51.241 ms | 94.281 ms |
| Python import block | 42.946 ms | No completed paired comparison |
| Git workflow, including Python file verification | 1,381.658 ms | No completed paired comparison |
| Python startup + full workflow + cleanup | 1,518.684 ms | Failed during cleanup |

The failed reference attempts completed their Git section in about 988–1,002 ms.
That limited observation suggests Direct's Git path may be slower and warrants
profiling; it is not discarded because cleanup failed. No whole Python/Git speedup
is claimed. The existing file-tree comparison is about 1.84x faster, while repeated
command execution is about 12% slower.

Timers are native/Python monotonic clocks. The import block excludes interpreter
startup; the outer C timer includes child Python startup through successful
cleanup but excludes the driver's initial APK/ELF setup. Bytecode caches are warm
after the functional run. `observation_ms` is not a timing source. These small,
warm workloads do not measure full CPU/memory/thermal or long-running behavior.

## Reproduction and remaining gates

Use [ELF reproduction](ELF.md#reproduction) with a separate fixture directory:

```sh
python3 tools/direct-linux/fixtures.py /tmp/terminal-direct-linux/app-fixtures --applications
```

Pass that directory to `package-probe.py --fixtures`. The validated loader hash is
`6d703a46618f6371ada983180e969901d44819c99e8f1ee32746def2016863d5`.
`directLinuxLoader=true` runs correctness. Add `directLinuxBenchmark=true` to
reproduce the complete comparison failure. Also add
`directLinuxSkipApplicationReference=true` to reproduce the explicitly partial
final measurement report; it leaves all Direct correctness checks enabled.

Native warnings-as-errors compilation, the combined runtime import/TLS audit,
fixture generation, test-only APK packaging/signing/verification and JDK17
canonical verification passed:

```sh
./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest \
  :image:test :android-runtime:assembleDebugAndroidTest --console=plain \
  -ProotlessAcceleratedPayload=/tmp/terminal-unpack/prepared \
  -ProotlessStdioBuffer=true
```

Runtime: 180 passed, one optional external-archive test skipped. Image: four
passed. The production APK contains no Direct Linux payload. The two earlier
Direct failures (xattr ENOSYS, absent null device) were fixed and rechecked;
the Rootless application-reference cleanup failure remains unresolved.

Android MCP installed/ran the actual app and inspected policy. The only target
was API35 ARM64 redroid: TracerPid=0, CapEff=0, initial Seccomp=0, SELinux Disabled.
**Stock enforcing compatibility remains unverified.** Context7 supplied partial
kernel xattr background; primary Linux man-pages and local NDK ABI headers
supplied the precise contracts. Replicant/codex_apps were not relevant to these
runtime-only/local changes. No UI or privileged AVF integration check applies.

Next evidence needed includes isolating the reference cleanup bug and profiling
Direct Git costs, then broader process/thread, network/PTY and package workloads.
The independent installer/backend/session integration and enforcing-device gate
in [the overall objective](../../docs/DIRECT_LINUX_EXPERIMENT.md) remain open.
