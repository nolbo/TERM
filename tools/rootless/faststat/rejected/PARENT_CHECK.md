# Parent-directory type-check experiment (2026-09-20)

Not integrated. The original raw sequential and overall file-I/O 2x objective
remains unmet. This experiment only addresses per-file creation overhead.

`dispatch_open` pins the creation parent with O_PATH|O_DIRECTORY and then fstat
checks whether it is a regular file or directory. The candidate skips that type
check only when creating and `PROOT_SKIP_PARENT_CHECK=1`. It retains no-symlink
resolution, pinned-object filesystem checks, shared-rule validation, exclusive
creation, fallback behavior and the checks for ordinary read opens. It adds a
counter to establish that the candidate path is executed.

`parent-check.patch` reproduces the experimental source from repository root.
Compile with the same flags as faststat/build.sh, using the faststat directory
for protocol.h. The patch is archived only; no build flag activates it in the
product. Use the phase-one engine and guest variable PROOT_SKIP_PARENT_CHECK=0
or 1 in rename-local-run.sh, with rename-local disabled. Do not set the variable
only on the host: the runner resets the guest environment with env -i.

Validation on Android MCP, API35 ARM64, app UID, isolated Ubuntu scratch root:

- Clang -O2 -Wall -Wextra -Werror build and separate test APK install passed.
- fastcreate-test.py passed with flag off and on: creation, umask, existing-file
  truncation, symlink append, exclusive creation with eight competing threads,
  and a guest seccomp filter denying openat. The candidate counter was zero
  when off and positive when on.
- Original, unchanged workloads.py --case mutations --count 1000 --rounds 1
  ran in off/on, on/off, off/on order. Median off/on: 213.291/211.318 ms, a ratio
  of 1.00934. Ranges overlap (off 212.836–218.147; on 207.657–213.160 ms).
  No diagnostics ran inside the timing comparison. Raw results and DSO/engine
  hashes are in ../results/parent-check-2026-09-20.json.

Decision: do not integrate an additional experimental switch for this gain.
The result does not establish a meaningful whole-workload improvement, and no
sequential transfer speedup is expected from removing one check per creation.
Do not repeat this benchmark without a materially different implementation.
No distribution package or app runtime source changed. The test APK's install
used streamed installation after unsupported incremental installation. No
Gradle, UI, external API, Debian or physical-handset validation was needed to
reject this candidate; none is claimed. Correctness tests are bounded and do
not establish universal package compatibility.
