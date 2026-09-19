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
