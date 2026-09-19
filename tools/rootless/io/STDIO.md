# Bounded stdio buffering experiment

This is an opt-in prototype, **not a packaged/default runtime optimization**.
It targets ordinary glibc `fopen`/`fopen64` read-only or write-only regular files.
Set `PROOT_STDIO_BUFFER=1` and preload `stdio-buffer.so` inside the guest only.
No native-hardlink capability is required. Raw read/write, fdopen, standard
streams, devices, pipes, update/append modes and mmap-requesting fopen modes
are not tuned. O_DIRECT/O_SYNC/O_DSYNC/O_NONBLOCK descriptors are skipped.

## Lifetime and semantics

The first 32 eligible streams receive independent 64 KiB fully buffered storage.
Buffers live in a fixed 2 MiB BSS pool until process exit and are never recycled.
This intentionally trades a bounded amount of retained memory for avoiding
fclose/freopen/cancellation hooks, dangling buffers and private FILE ABI changes.
Pool exhaustion leaves subsequent streams unchanged. No malloc/free ownership
is transferred to libc. The pool's slot index saturates and is atomic; fork
inherits normal copy-on-write process storage. Caller `setvbuf` still takes
precedence after fopen. [GNU libc's buffering contract](https://www.gnu.org/software/libc/manual/2.34/html_node/Controlling-Buffering.html)
requires supplied storage to remain valid while the stream uses it.

`fflush`, `fclose` and `fsync` remain libc/kernel operations. Writes are not
reported durable before fsync. As with ordinary buffered stdio, applications
must fflush before fsync on fileno; interposing larger buffers does not make
fsync flush libc memory. Unflushed output can become visible later than with a
smaller default buffer. Programs requiring each raw write's visibility must use
raw I/O or explicitly select their buffering policy. Do not enable globally
without mixed-application validation. No file contents or metadata are cached
outside the stream buffer and no kernel durability calls are suppressed.

## Build and validation

On the ARM64 glibc host with the existing compiler (no `-DNDEBUG` for tests):

```sh
bash tools/rootless/io/build-stdio.sh /tmp/terminal-stdio
PROOT_TEST_STDIO_DIRECTORY=/tmp/terminal-stdio \
  bash tools/rootless/build-perf-test-apk.sh PAYLOAD_DIRECTORY TEST_APK ORIGINAL_PROOT
```

Use the standalone test APK's extracted binaries, binding the library and test
executables into a disposable guest path. Do not preload a glibc DSO into the
Android/Bionic PRoot process. Both modes use the same DSO with flag 0 or 1.

`stdio-test.c` passed on the ARM64 host and on API35 Android in an existing
Ubuntu glibc guest under original PRoot with `--link2symlink`. It checks concurrent
streams, full content, fflush/fsync, another FD observing flushed output,
freopen, caller-owned buffers, update mode, device streams, fork, pool exhaustion
and failed-open errno. It does not establish all cancellation/signal, alternate
libc, application, cold-cache, handset or Android profile behavior.

## Results, 2026-09-19

The same 64 MiB file and executable, three alternating pairs per request size.
Write timing includes fflush; file fsync is separate and performed before unlink.
Reads use the warmed page cache. Open/close, process startup, allocation setup and
complete byte verification are outside transfer timing. All 18 checks passed.
Median speedup over disabled buffering under the same original PRoot:

| Request size | Read | Write + fflush | fsync |
| --- | ---: | ---: | ---: |
| 4 KiB | 2.06x | 2.89x | 0.99x |
| 64 KiB | 1.30x | 1.47x | 1.00x |
| 1 MiB | 1.03x | 1.03x | 1.00x |

`stdio-android-2026-09-19.json` contains the conditions and raw timings.
`stdio-host-screening-2026-09-19.json` records the preliminary ARM64 host screen
with the same probe and flags. These are **stdio** results, not improvements to
the original 64 KiB os.read/os.write suite or fsync-inclusive whole workloads.
The overall 2x target remains unmet. No APK release is changed by this experiment.

Tested Android payload SHA-256:

- DSO: `ed1ddb2dd2feed11edb276166621f72179f96f8e47e5ce3025aebf6edf358d5c`
- probe: `5534159c9f623eb0922e97204bfffed74a15f0e216122cd35d366e1252487b53`
- test: `5e0aabb6cf83e28accad78b9f4bfdf30a9a6ce057ecbfdb8370b7e9fe386461c`

Before runtime integration: measure whole programs and small-file regressions,
account for the extra fstat/fcntl at fopen, evaluate the first-32-stream limit,
and test composition with the packaged faststat DSO and distribution setup.
Do not drop raw sequential I/O, synced writes or the original workload acceptance
criteria merely because this narrower experiment exceeds 2x at 4 KiB.

## Pool-exhaustion regression (2026-09-19)

The initial prototype still issued fcntl(F_GETFL) and fstat on every eligible
fopen after its lifetime pool was full. This could add two traced calls per
small-file open while providing no buffering benefit. An atomic saturation check
now returns before either call. Existing reservations remain saturating and
buffers remain process-lifetime; no memory recycling or stream-close hooks were
introduced.

`stdio-check-counter.c` is linked only into a test DSO with GNU linker wrappers.
It counts the candidate's two checks, not libc's own file operations.
`stdio-pool-test.c` fills the 32 slots, then performs 1,000 successful open/read/
close operations and requires the counter to stay at 64. The test fails with the
previous DSO and passes with the fix. It also passed on Android API35 ARM64 in
Ubuntu under original PRoot/link2symlink, as did the existing stream lifecycle
test. Pool concurrency may permit already-started checks to finish after another
thread takes the last slot; the zero-extra-check guarantee is for subsequent
opens observing saturation, not a stop-the-world claim.

`stdio-programs.sh` passed in that guest with the candidate both disabled and
enabled: awk/sort/sed/grep output, gzip and tar round trips, cmp and a read-only
dpkg-query against bash. These are correctness checks, not whole-program speed
measurements or a full package installation. Performance comparisons were not
repeated because the transfer loop did not change. Android module code was not
changed, so Gradle tests were not rerun. The test APK needed streamed installation
after incremental installation was rejected by the device.

Remaining gates still include opening many short-lived streams before pool
exhaustion, memory/latency measurements, full faststat composition and complete
distribution setup. The earlier 4 KiB speedups remain specific to that measured
prototype; neither this regression fix nor the tool smoke tests proves overall
2x I/O performance.
