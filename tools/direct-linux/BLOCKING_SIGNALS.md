# Direct Linux blocking calls and signal frames

2026-09-25. This implements the interruptible open/FD portion of audit C14 and
part of C17. [All 45 compatibility targets](COMPATIBILITY_PROGRESS.md) remain
tracked; this is a standalone runtime change, not a product release.

## Problem and decision

The SIGSYS dispatcher blocks asynchronous signals while translating paths and
updating shared runtime state. A final FIFO open or blocking fcntl could therefore
wait indefinitely while the guest's interrupt signal remained pending. Simply
unmasking signals was insufficient: the former C callback exposed runtime PC/SP
and left opaque runtime frames on the cancellation unwind path.

`dl_blocking` now records the guest context and mask for final guest opens,
`F_SETLKW`/`F_OFD_SETLKW`, and translated network calls. Existing mask-bearing
waits share the delivery machinery. Final opens retain a pinned inode, or a
pinned parent for creation, and release the filesystem lock before unmasking.
This includes regular-file lease waits. Preparatory path traversal and internal
loader opens remain separate; executable inspection already uses O_NONBLOCK.

The signal bridge records the kernel continuation and presents the original
guest PC/SP/GP registers. A restarted host syscall exposes the guest SVC;
an interrupted call exposes its result. The small unmask/remask windows are also
associated with the correct guest operation. A handler enters through a copied
kernel signal frame and private `rt_sigreturn`, atomically changing PC and mask.
Its original kernel/guest restorer remains the return address. Thus glibc's
unwinder sees a signal frame leading to guest code, without runtime C frames.

Guest `rt_sigreturn` is now translated. An unchanged context resumes the saved
host continuation, retaining kernel restart behavior. Explicit GP edits abandon
that continuation and resume the original guest frame with the edits. Saved mask
edits and FPSIMD edits are propagated separately. The filter exempts the two
private return sites by full 64-bit PC, just as it exempts the host syscall site.
This continues to be a compatibility mechanism, not a security sandbox.

The frame layout/alignment and extra-context relocation follow the
[ARM64 kernel signal implementation](https://raw.githubusercontent.com/torvalds/linux/v5.10/arch/arm64/kernel/signal.c).
This does not establish support for arbitrary guest frame construction or all
SVE/SME state edits; those remain C17 work.

Temporary pins are held in a per-thread ownership table with stack markers.
All live pins are protected from close/dup/close_range operations, including
nested waits. Normal return closes the pin. A nonlocal return reclaims abandoned
pins at the next translated syscall or bridge entry; thread exit and successful
exec also reclaim them. No filesystem lock survives a guest handler.

The vfork parent wait was deliberately not unmasked as though it were an
ordinary I/O wait. Linux defers parent signals until the child releases its
memory, as documented by [vfork(2)](https://man7.org/linux/man-pages/man2/vfork.2.html).
Direct's COW/pipe implementation still lacks full shared-memory vfork semantics;
its complete transition and signal tests belong to unfinished C04/C05.

## Validation

[Final result and 724 fixture hashes](results/blocking-runtime-2026-09-25.json):
loader `0196b2c726728e72cbec063eadb3e787df60502aa01c10b284262c9983de57c3`,
Android API 35 ARM64 redroid, 4 KiB, SELinux Disabled, ordinary app UID,
initial Seccomp=0, CapEff=0 and TracerPid=0. **Stock enforcing support is unverified.**

The static/dynamic `blocking-test.c` checks:

- FIFO EINTR and SA_RESTART while a handler performs pathname translation;
- guest PC/SP/syscall/result visibility and an edited x0 return value;
- 16 repeated longjmps with FD reclamation, an inner wait/jump back into an
  outer handler, and close_range while both internal pins must survive;
- POSIX and OFD lock interruption/restart, regular-file lease interruption;
- caught SIGINT/SIGTERM and default termination during a FIFO open;
- eight translated open cancellations and eight address-bearing accept
  cancellations, pthread cleanup callbacks and FD reclamation.

All above checks passed in Direct on Android and in an ARM64 host loader linked
to the same freestanding runtime objects. The host sandbox initially denied a
required preparation syscall (EPERM); the ordinary-user run outside that sandbox
passed. No elevated guest UID/capability was used.

The full Android run was **25.747 seconds, OK (1 test)**: 142 top-level launches,
including 54 comparison samples, plus existing thread/network/PTY/signal tests.
Rootless passed the added checks except FIFO SA_RESTART after a pathname call in
the handler, which returned ENOENT in both linking variants. That result is a
`LIMIT`, not a passing restart check. The test waits for its helper child before
reporting the limitation; an early parent failure could otherwise be obscured by
the reference tracer returning a later child's exit status.

The initial Android FIFO test timed out because the fixture inherited a blocked
signal mask from its launching process. Explicitly clearing the test mask fixed
the fixture; the runtime correctly preserves inherited masks. Two APK installs
failed for insufficient storage; deleting only superseded generated test APKs
and a duplicate generated fixture directory restored installation. A run of the
older APK after the first failed install was not counted. A concurrent Android
shell diagnostic was serialized until after cleanup and therefore found no
fixture path; packaged hash checks and instrumentation supplied the evidence.

Native `build.sh` warnings/import/TLS audits and APK signature verification
passed. `signals-exec-test` passed. The canonical JDK 17 Gradle command with the
verified accelerated payload passed (runtime 180 passed/1 optional fixture
skipped; image 4 passed), followed by rebuilding the instrumentation APK for its
new checks. No privileged AVF acceptance or UI test was applicable.

Android MCP supplied install/runtime/report evidence. Primary Linux sources
supplied the signal-frame/vfork contracts; Context7's earlier lookup did not
cover this custom runtime. Replicant and codex_apps had no relevant UI/external
application work. A GCC source URL lookup failed and was not treated as evidence.

## Cost and remaining scope

The previous committed loader was rebuilt with the identical toolchain; its
digest matched `98536095dedde3670177e227df42aea41b11023e010c955f5703976000e0f6c7`.
Both versions and the current accelerated Rootless ran from the same APK/rootfs.
Eighteen rounds rotate all six engine orders, without trace/profile logging.

| Shell plus 30 external commands | Median |
| --- | ---: |
| Previous Direct | 35.603 ms |
| Current Direct | 36.829 ms |
| Accelerated Rootless | 47.698 ms |

Signal-safe final opens and explicit return translation add **3.4%** in this
workload; current Direct takes 22.8% less time than Rootless. This change is a
correctness improvement, not a speedup claim. Broader performance distributions
and devices are not established by these samples.

C14 still needs the C04/C05 vfork/spawn contract and interruption coverage for
slow preparatory filesystem operations. C17 still needs stop/continue restart,
restart_syscall, full setcontext/manual frame behavior, and SVE/SME edit coverage.
Guest alternate stacks and SIGSYS delivery remain C15/C16. Wait/delivery/owned-I/O
tables are bounded at 64, and the runtime stack is 1 MiB per thread (C13).
No ABI, package format, product runtime selection, or release version changed.
