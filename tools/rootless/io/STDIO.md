# Bounded stdio buffering experiment

This is an opt-in prototype, **not a packaged/default runtime optimization**.
It targets ordinary glibc `fopen`/`fopen64` read-only or write-only regular files.
Set `PROOT_STDIO_BUFFER=1` and preload `stdio-buffer.so` inside the guest only.
No native-hardlink capability is required. Raw read/write, fdopen, standard
streams, devices, pipes, update/append modes and mmap-requesting fopen modes
are not tuned. O_DIRECT/O_SYNC/O_DSYNC/O_NONBLOCK descriptors are skipped.

## Lifetime and semantics

Up to 32 live eligible streams receive independent 64 KiB buffers from a fixed
2 MiB BSS pool. Normal fclose returns release the slot for a later stream. The
wrapper detaches associations before calling libc, then frees only those slots
after libc returns (including an EOF/error return). It never looks up the FILE
address after close, because another fopen may already have reused that address.
Atomic slot reservation and owner publication avoid a process-global mutex.
No private FILE fields or malloc ownership flags are modified.

Cancellation or nonlocal exit during fclose deliberately strands its reserved
slots: a still-live stream may retain the buffer. The limit stays 2 MiB; after
sufficient interrupted closes new streams fall back to libc buffering. Failed
freopen or libc-internal close paths that bypass the wrapper may also retain
slots. These conservative cases affect optimization coverage, not permission to
reuse potentially live memory. Existing caller setvbuf and freopen retain valid
storage for the entire FILE lifetime; normal fork inherits copy-on-write pool
state. Pool exhaustion leaves additional streams unchanged and skips eligibility
syscalls when saturation is observed.
[GNU libc's buffering contract](https://www.gnu.org/software/libc/manual/2.34/html_node/Controlling-Buffering.html)
requires supplied storage to remain valid while the stream uses it.

`fflush` and `fsync` remain libc/kernel operations; the fclose wrapper delegates
the actual flush and close to libc before releasing storage. Writes are not
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
account for the extra fstat/fcntl at fopen and the concurrent 32-stream limit,
and test composition with the packaged faststat DSO and distribution setup.
Do not drop raw sequential I/O, synced writes or the original workload acceptance
criteria merely because this narrower experiment exceeds 2x at 4 KiB.

## Pool-exhaustion regression before recycling (2026-09-19)

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


## Reusable pool validation (2026-09-19)

The current pool replaces the first-32-stream lifetime limit with up to 32 live
slots. The revised counter test holds 32 tuned streams open, verifies 1,000
additional streams add zero eligibility checks, closes the original streams,
then verifies 1,000 further streams each receive a 64 KiB buffer. This passed on
the ARM64 host and Android API35 Ubuntu/original-PRoot/link2symlink.

`stdio-close-gate.c` is a test-only second preload that deterministically stops
inside the delegated fclose or opens a replacement FILE immediately after libc
frees the old one. `stdio-lifetime-test.c` verified on both environments:

- cancelling a close retains its buffer and only the other 31 slots can be reused;
- the original stream still flushes its original content after cancellation;
- libc reused the exact FILE address before the wrapper returned, and the new
  stream's live buffer remained reserved and its content intact;
- an actual EFBIG flush/close failure preserves errno and releases its slot after
  libc returns.

The existing lifecycle and basic-program checks also passed enabled/disabled.
Updated DSO SHA-256:
`de970f2757d1d51031899004dbebb156f5bfd2c676260344db68c8b312a70454`.
No performance comparisons were repeated; the earlier table describes the older
prototype, not a measurement of this revision. No production APK was changed.

Recycling restores eligibility checks on later opens whenever slots are free.
Consequently the previous lifetime-saturation optimization is now a full-live-
pool optimization only. Short-lived small-file streams may pay fcntl/fstat and
close bookkeeping costs repeatedly. Measure this regression risk against the
disabled baseline before default integration. Raw sequential I/O and fsync still
have no demonstrated 2x improvement, and the whole goal remains unmet.

## Small-file regression screen (2026-09-19)

`stdio-small-probe.c` creates 128 disposable 448-byte files using raw I/O, warms
them once, then times eight passes of fopen, fread through EOF, byte comparison
and fclose (1,024 complete operations). Setup, warmup, cleanup and process startup
are excluded. It checks EOF, errors, exact bytes and successful closes. The build
script compiles it; the standalone test APK optionally includes it.

On API35 ARM64 redroid in the same Ubuntu/original-PRoot/link2symlink environment,
three pairs in order disabled/enabled, enabled/disabled, disabled/enabled passed
all content checks. Disabled median was 96.527397 ms; enabled median was
95.244833 ms (1.0135x). This small difference does not establish a speedup. No
material regression appeared in this bounded warm-read case, despite repeated
eligibility calls. Raw observations are in `stdio-small-android-2026-09-19.json`.
The DSO is the reusable-pool revision identified above.

This does not cover small writes, cold reads, many concurrent streams, application
startup, physical phones, or composition with faststat. The experiment remains
opt-in and outside the production APK. All host lifecycle/pool/cancellation tests
also passed with this build. Android MCP installed and ran the test APK; streamed
installation succeeded after the device rejected incremental installation.
No Android module changed, so the Gradle suite was not rerun. No UI or external
API changed, so replicant, context7 and connected-app tools were unnecessary.
