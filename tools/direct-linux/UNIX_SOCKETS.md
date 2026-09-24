# Direct Linux pathname Unix socket progress

2026-09-24. This extends the separate test runtime; full Unix socket compatibility
is **not complete**. Existing Rootless code, payloads and installed roots are unchanged.

`files.c` resolves bind parents without following the final component, and resolves
connect/send targets with guest-root symlink semantics. It snapshots the resulting
root-relative name and closes all temporary descriptors. `network.c` prefixes that
name with the persistent `/proc/self/fd/ROOT_FD` alias, keeping Android's longer
app-storage prefix out of the kernel's short Unix address. No temporary FD, lock or
allocated object survives an interruptible call or a guest siglongjmp. A signal
handler may change cwd without invalidating the absolute alias. Path lookup is not
atomic across concurrent rename/replacement; the kernel resolves the snapshotted
name when the actual socket call runs. This is not a malicious same-UID sandbox.

Getsockname/getpeername, accept, recvfrom and recvmsg translate returned names back
to guest paths, including caller buffer truncation and actual-length reporting.
Foreign pathname formats return EXDEV rather than exposing Android private paths.
Connected send/receive and accept with no address pointer retain direct kernel paths;
the BPF checks both halves of the pointer. Anonymous socketpair recvmmsg remains
available; batched named Unix receives and abstract-address input remain unsupported.
Input names that exceed the host sun_path after adding the alias fail explicitly
with ENAMETOOLONG. A shared long-name binding scheme is still required; truncating
names or reducing the overall compatibility requirement is not acceptable.

The [Linux Unix socket contract](https://man7.org/linux/man-pages/man7/unix.7.html)
and [kernel implementation](https://raw.githubusercontent.com/torvalds/linux/v5.10/net/unix/af_unix.c)
guided address and filesystem handling. Inspection of the existing local PRoot
source showed its long-name alias mechanism and socket translation coverage;
none of that GPL implementation was copied into the separate runtime.

The [raw comparison](results/unix-runtime-2026-09-24.json) contains 714 fixture
hashes, 56 non-PTY launches and the existing shared PTY/IP/DNS/HTTP/TLS checks.
Static and dynamic Unix stream checks passed on both engines: fork/exec clients,
bound/peer addresses, socket metadata and umask, dup, symlink resolution, rename,
and connection survival after unlink. Direct also passed named datagrams,
sendmsg/recvmsg addresses, strict truncated/zero-capacity output lengths, and six
repeated interrupt/nonlocal-exit cycles per executable without losing the next free
FD or the signal handler's cwd change.

The first combined run **failed** in Rootless's named datagram sendto with ENOENT
after stream checks had passed. The final harness keeps this failure visible in
`observed_limitations` and requires its exact diagnostic, rather than counting it
as successful communication. Both reference static/dynamic cases reproduce it.
Conversely, a 100-byte pathname bind succeeds in Rootless and fails in Direct
with ENAMETOOLONG; both failures are recorded as Direct limitations. The report
keeps `named_unix_sockets_verified: false`. These cases are unresolved comparison
findings, not ordinary passing compatibility assertions or a reason to waive parity.

Instrumentation completed in 9.766 seconds after recording these distinctions.
Native warning-as-error/import audit, fixtures, test APK packaging/signature checks
and canonical JDK17 build/tests passed. Runtime unit results were reused unchanged:
180 pass, one optional external archive skip; image four pass. Android MCP supplied
normal app-UID execution on ARM64 API35 redroid; SELinux Disabled and initial
Seccomp=0 still preclude a stock enforcing-device claim. Context7 supplied partial
pathname/security background; primary kernel sources supplied the socket details.
Replicant and connected-app tools were unnecessary because no product UI or external
application changed. No performance claim comes from this correctness run.

Use the [network reproduction](NETWORK.md#reproduction) with regenerated fixtures
and loader SHA-256 `5f6a714c8789cb3f13efb31af2e824ec0a8c3bf1779775f51fe6c4fbfd8b74aa`.
Required remaining work includes full-length/abstract names, named recvmmsg and
sendmmsg, concurrent namespace semantics, full signal/thread behavior, package and
session integration, and enforcing-device validation.
