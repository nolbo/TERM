# Sequential I/O headroom probe

This diagnostic separates transfer, final file fsync, and open/close overhead.
It is not a replacement for the existing eight application workloads and is not
an acceleration implementation. Compile the same static ARM64 binary for every
mode, and use only a disposable app-private test directory:

```sh
cc -O2 -Wall -Wextra -Werror -static sequential-probe.c -o /tmp/sequential-probe
/tmp/sequential-probe /tmp 64 64
```

Arguments are directory, total MiB (1..256), request KiB (1..1024). The probe
handles short reads/writes and EINTR, verifies every file byte after timing,
then deletes its unique file. A killed process can leave `terminal-io-*` behind.
`fsync` occurs while the file is still linked. It does not sync the parent
folder, drop caches or use O_DIRECT. The final verified record and successful
exit are required before accepting any timings. CPU time excludes the tracer.

`PROOT_TEST_IO_BINARY` adds this executable to `build-perf-test-apk.sh` as
`libsequential-io-test.so`, keeping the user's terminal app untouched. Resolve the
installed library directory, then compare the same binary and same UID directly
and through PRoot. Android's `env` mistakes an APK path containing `=` for an
environment assignment: use `env NAME=value /system/bin/sh -c 'exec "$@"' runner
<proot-path> ...`, not `env NAME=value <proot-path> ...`.

## Initial evidence (2026-09-19)

API35 ARM64/ext4 test device; 64 MiB, three alternating rounds. Median ms:

| Request | Operation | Direct | Original PRoot | Candidate engine |
| --- | --- | ---: | ---: | ---: |
| 4 KiB | read | 19.008 | 19.425 | 19.548 |
| 4 KiB | write | 38.886 | 39.479 | 40.054 |
| 64 KiB | read | 7.590 | 7.787 | 7.733 |
| 64 KiB | write | 12.565 | 12.637 | 12.481 |
| 1 MiB | read | 6.991 | 7.142 | 7.138 |
| 1 MiB | write | 15.490 | 15.589 | 15.502 |

Final fsync was roughly 1.2 seconds in all modes. All 27 content checks passed.
Raw results and exact limitations are in `sequential-headroom-2026-09-19.json`.
PRoot ran with host root `/` solely to isolate engine overhead; this is not a
complete distribution or accelerated DSO measurement. These results cannot
establish cold-storage, physical-phone or overall workload speedups.

The engine's ordinary read/write/fsync path is already close to direct execution
in this test. Removing tracing alone does not show 2x headroom for 64 KiB I/O.
Changing the application's requests from 4 to 64 KiB reduced syscall count and
improved transfer time over 2x here, but **no application/runtime change has yet
been implemented**. Do not call this a delivered speedup or revise the original
2x objective to fit it. Next investigate bounded regular-file stdio buffering
and file-copy APIs without changing read/write visibility, fsync durability or
returning success before completion. Existing 64 KiB workloads remain acceptance
criteria; a smaller-block-only improvement cannot satisfy the full objective.

Follow-up: [the bounded stdio experiment](STDIO.md) now implements and tests this
approach for a subset of glibc streams. It improves 4 KiB stdio transfers in the
Android guest, but is not enabled in the runtime and does not close the remaining
raw sequential I/O or fsync gap.
