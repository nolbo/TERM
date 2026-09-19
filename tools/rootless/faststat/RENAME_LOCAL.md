# Rename path-copy experiment

The existing mutation-entry optimization translates both rename paths, writes
them to guest memory, runs extension callbacks, then completes eligible regular
file renames inside the tracer. Those two guest writes are unnecessary when the
tracer completes the call. This experiment defers them until a fallback needs
the ordinary kernel/exit path. It does not add untraced rename syscalls, cache
filesystem metadata, or change directory/cwd bookkeeping.

Build separately from the pinned application payload:

```sh
PROOT_DOWNLOAD_CACHE=/path/to/verified-inputs \
PROOT_BUILD_FASTSTAT=1 PROOT_BUILD_RENAME_LOCAL=1 \
  bash tools/rootless/build-proot-arm64.sh /tmp/rename-local-payload
```

The extra patch is applied only when explicitly selected. The runtime option is
`PROOT_RENAME_LOCAL=1`, in addition to the existing mutation-entry profile. This
is not selected by the normal Android build. The experimental engine SHA-256 is
`603ac7d6c40af0b76b9691641738e3955040920c75ea13a812bd1144427b61a2`;
loader remains `2567ab8ad3b418a8804696342056052eef37835e50d200e9f4ff48aae564c237`.
The test used the ordinary faststat DSO
`32440930dc740987aec8288b0d84c569980703a20479a10c2f58218bc22a745d`.

Deferral is permitted only in non-USERLAND builds with the known fake_id0 and
mountinfo callbacks: their syscall-enter-end handling does not read/change rename
paths. All callbacks still run. Other extensions, including link2symlink, keep
the existing eager translation/write behavior. After callbacks, argument address
and syscall identity checks must still match. An unexpected rewrite fails closed
instead of dispatching untranslated paths. Directory/symlink objects and nonzero
renameat2 flags restore the translated arguments before the ordinary path runs.
Completed mutation errors remain final; mutations are never retried after execution.

## Validation, 2026-09-19

The pinned-input ARM64 build and 16 KiB ELF checks passed. Android MCP installed
the standalone performance APK (streamed fallback after incremental rejection)
and ran `rename-entry-test.py` with the option off and on. Both passed replacement
contents, dirfd resolution, missing/invalid/empty pointers, symlink moves,
RENAME_NOREPLACE and RENAME_EXCHANGE, directory renameat followed by getcwd and a
relative file write, and additional seccomp filters rejecting rename syscalls.
The same checks passed with link2symlink enabled and the local option requested.
Directory renameat2/cwd handling and concurrent directory replacement are not
newly validated by this change.

The runner is `rename-local-run.sh`, executed as the app UID with the test APK
nativeLibraryDir and the Python test/probe on stdin. Its second argument selects
the local option (0/1), third selects PRoot counters (0/1). It creates a disposable
root, binds the existing Ubuntu userspace, and removes its own fixtures. The
installed user rootfs is not converted to native-link storage.

`rename-local-probe.py` compares the same engine with the flag toggled. Three
alternating pairs on API35 ARM64 redroid/ext4, warm cache, counters off:

| Case | Off median ms | On median ms | Ratio |
| --- | ---: | ---: | ---: |
| 2,000 regular-file renames | 94.788 | 88.313 | 1.073x |
| 1,000 create/write/rename/unlink cycles, 4 KiB each | 149.853 | 140.904 | 1.064x |

Content and final-state checks passed. This is not the original complete workload
suite and does not include fsync. A separate diagnostic run showed vm_write
6,210 → 210 for 3,000 total renames, with enter_stop=3,220 and exit_stop=107 in
both modes. Diagnostic timings are excluded from the table. The first diagnostic
attempt's stderr was hidden by the tool; the runner now merges stderr for capture.
Raw observations are in `results/rename-local-2026-09-19.json`.

Python compilation, shell syntax and diff checks passed. No Android runtime
source changed, so Gradle was not rerun. Other MCP servers were unnecessary for
this native syscall change. No default APK or release payload was replaced.

This removes measured overhead but is not a 2x file-I/O improvement. The original
sequential and synced-write criteria remain unachieved. Full workload comparison
and installation validation are required before replacing the pinned engine.

## Original workload and rejected read batching (2026-09-19)

The runner now forwards arguments after its first three parameters to the Python
program on stdin, allowing the unchanged `tools/rootless/workloads.py` to run.
Three alternating pairs used count=1000 and io-mib=64, with the same local-path
engine and profile and only PROOT_RENAME_LOCAL toggled:

| Original case | Off median ms | On median ms | Ratio |
| --- | ---: | ---: | ---: |
| mutations | 213.621 | 210.341 | 1.016x |
| sequential-read | 13.151 | 13.109 | 1.003x |
| sequential-write | 14.094 | 13.982 | 1.008x |
| synced-write | 754.375 | 762.357 | 0.990x |

All driver checks passed. The mutation ranges overlap; the earlier 6–7% limited
probe result must not be presented as the improvement to this original workload.
Sequential/synced results show no demonstrated improvement. These comparisons
are incremental to the existing accelerated profile, not original PRoot speedups.

An additional candidate read both short rename strings with one process_vm_readv
call, retaining the original readers on incomplete transfers, missing terminators
or long paths. It reduced diagnostic vm_read from 4,499 to 3,507, with unchanged
enter_stop=3,465, exit_stop=162 and vm_write=2,427. But original mutations had
median 208.267 ms disabled versus 210.689 ms enabled (three alternating pairs).
This provides no demonstrated speedup, so the candidate was removed from the
build path. Its patch is archived in `rejected/rename-readv.patch` for audit, not
selected by a build option. Its tested engine hash was
`5e2782532138bb0afff9336f5457b26d7d7df88b3a3520e35901579aa71b8bf0`.
For reproduction, that patch applies after proot-rename-local.patch in the
temporary PRoot source; the tracer-side switch was PROOT_RENAME_READV=1.

`rename-memory-test.py` passed with the archived candidate off/on and with the
retained 603ac7d6... engine. It checks source and destination NULs at the last byte
of a read-only mapped page, an unmapped second vector, an unterminated string
before an unmapped page, a valid path longer than 1,024 bytes and ENAMETOOLONG.
The existing contents/dirfd/symlink/flags/cwd/seccomp suite also passed with the
read-batching candidate off/on. Python compilation, shell syntax and diff checks
passed. Android MCP supplied live runtime evidence; streamed APK installation
followed the device's incremental-install rejection. No Android app code or
default payload changed, so Gradle was not repeated.

Raw timings and diagnostic reports are in
`results/rename-whole-workload-2026-09-19.json`. The overall 2x objective is still
unmet; neither reduced syscall counts nor a successful compatibility check is
substituted for that requirement.
