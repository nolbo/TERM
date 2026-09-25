# Direct Linux signal mask compatibility

2026-09-24. A signal handler installed with a full `sa_mask` could terminate
Direct Linux when it made a translated syscall. Mask-bearing poll/select waits
could create the same conflict: their mask also blocked the private SIGSYS
transport. These ordinary Linux operations worked in the Rootless reference.
`signals.c` now separates the guest's logical SIGSYS mask from the kernel mask
needed for translation. This is a compatibility fix in the standalone test APK,
not full signal virtualization, thread support or completion of the Linux goal.

This page records the September 24 implementation and measurements. The current
per-thread implementation and signal-frame return path are described in
[blocking calls and signal frames](BLOCKING_SIGNALS.md) and the
[compatibility progress tracker](COMPATIBILITY_PROGRESS.md).

## Reproduction and implementation

[The recorded failure](results/signals-diagnosis-2026-09-24.json) used loader
`54e56976a5dd7989a58a435cecc9a2d41d28af4d20b45ccc3192c639955c95ef`.
Static and dynamic Rootless action/wait tests completed, then Direct's static
action test exited **159** instead of zero. The first corrected loader passed
the identical initial fixtures. The final test adds more mask-boundary checks;
their distinct hashes are retained rather than presented as identical inputs.

- Guest `rt_sigaction` installs a freestanding bridge for caught signals.
  The kernel enters it with asynchronous signals blocked. It exposes the guest
  action/mask on queries, constructs the requested handler mask, and unblocks
  everything the guest permits except the runtime's SIGSYS transport. Original
  action flags/restorer are retained, with SA_SIGINFO added only for the bridge.
  Default/ignored dispositions retain kernel behavior; SA_NODEFER recursion and
  SA_RESETHAND queries are covered. No Bionic/glibc/TLS helper is called by the bridge.
- `ppoll`, `pselect6`, `epoll_pwait`, `epoll_pwait2` and `rt_sigsuspend` safely copy
  their masks, remove only physical SIGSYS blocking and retain guest mask state.
  The original kernel wait still performs the temporary-mask transition. Poll,
  select and epoll calls with a null mask keep the direct kernel fast path; both
  halves of the pointer are checked in BPF. `sigtimedwait` distinguishes its
  selection set from a mask, and lets unrelated unblocked guest handlers interrupt.
- A translated wait records its guest context and temporary/restoration masks.
  The bridge presents the guest's saved mask in `ucontext`, carries mask edits
  back to the enclosing guest context, and restores the runtime's own blocked
  transition separately. The kernel still owns signal frames and register state.
- A guest handler may wait recursively or abandon calls with `siglongjmp`.
  Records use stable process storage, not abandoned stack objects. Dispatcher and
  bridge entry discard abandoned records using the saved interrupted stack
  pointer. They never inspect overwritten stack records. A handler temporarily
  suspends its enclosing wait's record, so another blocking call made by that
  handler does not borrow the outer wait's mask. The runtime holds no lock,
  allocated resource or temporary FD across the guest callback.

The final suspension rule fixes a regression found during this change: an outer
ppoll handler entered translated `accept`, then a second signal saw the outer
wait's saved mask instead of the handler's mask. The new nested-network check
failed in Direct and passed in Rootless before that correction. Its failure is
also recorded, and both variants now pass it.

The relevant contracts are the [signal action flags/masks](https://man7.org/linux/man-pages/man2/sigaction.2.html)
and [ppoll temporary mask and raw ABI](https://man7.org/linux/man-pages/man2/poll.2.html).
Linux's [pselect argument packing](https://github.com/torvalds/linux/blob/v6.6/fs/select.c)
also informed the implementation. Context7 returned alternate-stack background,
but not the narrow syscall contract; primary documentation supplied that detail.

## Validation and performance

`fixtures.py --applications` builds `signals-test.c` as static and dynamic glibc
programs. Add `-e directLinuxSignalMasks true` to the existing
[ELF instrumentation command](ELF.md#reproduction). The final loader digest is
`50b8267e428324736697a69e5406e0c637200caac9518749146c4d795ee5f9f8`.
The final run also enabled network, PTY and benchmark flags, with the previously
failing full Rootless application comparison explicitly skipped.

[Final raw results](results/signals-runtime-2026-09-24.json): `OK (1 test)`,
20.497 seconds, **716 fixture hashes, 113 ordinary launches, two PTY scenarios
and 77 timing samples**. Each engine has twelve signal-check markers, six for
each libc-linking variant. No signal test was skipped; epoll_pwait2 worked on this
target. Coverage includes:

- Full action masks with translated stat/mask calls inside handlers, action
  roundtrips, ignored/default dispositions, NODEFER recursion and RESETHAND.
- Temporary versus saved masks, SIGSYS blocked in either one independently,
  normal EINTR/timeout, and a handler editing its saved ucontext mask.
- Eighty ordinary longjmps, eighty nested waits and eighty jumps back into an
  enclosing handler per linking variant, exceeding the record-table capacity
  cumulatively to catch unreclaimed abandoned records.
- Nested blocking accept with a second signal, sigtimedwait selection/interruption,
  bad pointers, invalid mask sizes and null sigsuspend input.
- Existing malformed ELF, repeated exec, FD/signal reset, BSS, filesystem and
  Direct Python/Git tests, plus both engines' network/DNS/TLS/Unix/PTY checks.
  The previously recorded Unix pathname/datagram limitations remain explicit.

Seven alternating benchmark pairs had these medians. These measurements check
for a material regression; they do not establish a signal-fix speedup.

| Workload | Direct | Accelerated Rootless |
| --- | ---: | ---: |
| 5,000 stat calls | 27.472 ms | 39.496 ms |
| 5,000 open/read/close calls | 45.611 ms | 59.911 ms |
| Shell plus thirty external commands | 48.958 ms | 50.162 ms |
| 128-file tree | 50.398 ms | 95.891 ms |

Native warnings/import/TLS audits and APK signing passed. The canonical JDK 17
Gradle command plus `:android-runtime:assembleDebugAndroidTest` succeeded. The
instrumentation source was rebuilt; unchanged unit tasks reused UP-TO-DATE
results (runtime 180 passes and one optional external-fixture skip; image four
passes). No release or production payload was changed.

Android MCP installed the test APK and ran all guest commands under the normal
app UID. An installation failed with INSUFFICIENT_STORAGE; deleting 472 MB of
this goal's reproducible unsigned APK intermediates allowed installation to
succeed. The accidental run of the older installed APK was not counted for new
tests; final result fixture hashes were checked against the new manifest. Two
shell diagnostics used unsuitable paths; local filesystem inspection and the
instrumentation's packaged-loader digest supplied the evidence instead.
Replicant/codex_apps and the privileged AVF checklist were not relevant.

## Remaining requirements

The device remains redroid ARM64 API 35 with SELinux Disabled, initial Seccomp=0,
CapEff=0 and TracerPid=0. This is not evidence of stock enforcing-device support.
Guest SIGSYS dispositions/external delivery, guest alternate stacks, independent
setcontext/raw sigreturn virtualization and full register-context translation
when a signal interrupts a translated syscall remain unsupported/incomplete.
The wait-mask checks do not establish arbitrary context-switching semantics.

At the time of these measurements shared-VM/thread clone was rejected. The
September 25 implementation now supports basic pthreads with shared dispositions
and per-thread masks, wait records and 1 MiB runtime stacks. It also translates
GP context and return paths for blocking calls. Full shared-VM spawn, guest
alternate stacks, extended-state edits and all context-switching semantics
remain unfinished. Tables still bound nested waits/deliveries at 64.
There are no new privileges or changes to Android's signal/seccomp policy.
Packages, complete identity/filesystem behavior, product sessions and overall
Rootless compatibility remain required by the active goal.
