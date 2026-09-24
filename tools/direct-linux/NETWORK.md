# Direct Linux networking and resolver milestone

2026-09-24. The separate experimental runtime now runs IPv4/IPv6 networking,
anonymous socket IPC, real glibc DNS queries and Python HTTP/TLS. The same
positive scenarios pass against the pinned accelerated Rootless engine. This
does not establish complete socket compatibility, external Internet readiness,
stock Android policy support or overall Rootless parity.

## Runtime and signal ownership

`network.c` handles socket creation and calls carrying an input address. It
copies the address and sendmsg header with the existing checked memory reader
before forwarding them. IPv4, IPv6 and AF_UNSPEC disconnect addresses are
accepted. Socket creation supports IPv4/IPv6; socketpair supports anonymous
AF_UNIX IPC. Named/abstract Unix addresses and other socket families remain
unsupported rather than being interpreted as Android host paths.

Translated connect/send operations can block. They temporarily restore the
guest's saved signal mask, keeping the runtime's SIGSYS unblocked. At this point
there are no held runtime locks, allocated objects or temporary descriptors that
need cleanup. Address/header snapshots live on the runtime stack. A guest
signal handler can reenter path dispatch, return normally, or leave using
siglongjmp. Normal return restores the dispatcher mask. Named Unix path
translation must solve the lifetime of any pinned directory descriptors across
these nonlocal exits before using this path.

Calls needing no translation go directly to the host kernel: listen/accept,
socket-name and option queries, shutdown, receive operations, epoll and eventfd.
This preserves their actual guest signal mask and kernel blocking behavior.
The runtime remains free of libc, compiler TLS, allocators and new unresolved
imports. Android's inherited seccomp, SELinux and app permissions still apply;
this is not a sandbox against malicious code sharing the app UID.

The [Unix socket contract](https://man7.org/linux/man-pages/man7/unix.7.html)
distinguishes unnamed, pathname and abstract sockets. The
[connect contract](https://man7.org/linux/man-pages/man2/connect.2.html) documents
AF_UNSPEC disconnection. Neither is replaced with a blanket host-path bypass.

## Shared tests

`network-test.c`, built as static and dynamic glibc executables, checks:

- IPv4 TCP with a forked peer, accept4/CLOEXEC, peer identity, payload, half-close
  and EOF; IPv4/IPv6 UDP send/receive, MSG_PEEK, MSG_TRUNC and disconnect.
- Anonymous SOCK_SEQPACKET IPC transferring an actual open file using SCM_RIGHTS;
  the received descriptor retains the file after sender close and has CLOEXEC.
- A filled stream socket's blocking sendmsg interrupted by SIGUSR1. Its handler
  performs a translated stat. Both EINTR return and siglongjmp escape must leave
  subsequent dispatch usable. Two-second socket timeouts bound failure; this is
  not a test of SA_RESTART, thread cancellation or all signal semantics.

Direct also checks explicit rejection of unsupported named IPC and an invalid
address pointer. These negative policy checks do not count as Rootless parity.

`network-test.py` uses unmodified Python standard libraries to check:

- HTTP and HTTPS transfer with exact length and SHA-256 validation, trusted
  certificate success, hostname mismatch rejection and untrusted certificate
  rejection. Only SSLCertVerificationError satisfies either negative TLS case.
- Hosts-file getaddrinfo and numeric getnameinfo, plus socketpair readiness using
  DefaultSelector. NSS is deliberately configured as `hosts: files` here.
- Static and dynamic `dns-test.c` clients using real glibc 2.39 res_nquery and
  res_nsearch, rather than an application-side replacement resolver. A forked
  Python fixture binds UDP/TCP to the same ephemeral loopback port above 1023.
  Each client has its own resolver state pointing to that port. The server checks
  all six exchanges: A, AAAA, NXDOMAIN, truncated UDP reply, retry over TCP and
  search-domain expansion. It injects a wrong transaction ID with an incorrect A
  answer before the correct reply; the client must return the expected address.

The DNS test follows the
[resolver API](https://man7.org/linux/man-pages/man3/resolver.3.html), existing
glibc 2.39 headers and upstream
[resolver transport](https://raw.githubusercontent.com/bminor/glibc/release/2.39/master/resolv/res_send.c).
It uses reserved example names/addresses and no privileged port, public resolver,
host DNS configuration change or extra app permission. It proves local resolver
transport, not automatic Android DNS discovery or getaddrinfo's DNS/NSS path.

The fixture generator creates a fresh, disposable EC test key/certificate with
the existing OpenSSL installation. They are valid for two days, included only
in the standalone test APK and never committed. Both engines use identical
assets; only hashes are recorded. Regenerate fixtures before repeating an expired
TLS test. This trust fixture does not establish a working distribution CA store.

## Evidence, failures and remaining work

The [raw report](results/network-runtime-2026-09-24.json) records 712 immutable
fixture hashes, 46 top-level non-PTY launches, two shared PTY scenarios, network
checks for both engines and all reference engine/library hashes. DNS server and
client children run inside those launches. Final Android instrumentation passed
in 8.118 seconds. Earlier ELF/process/filesystem/FD/Direct Python-Git checks stayed
enabled. There are no performance samples in this run and no network speed claim.
The broader Rootless Python/Git cleanup failure was not rerun or waived. The
report's historical `application_reference_skipped: false` records that the skip
flag was not supplied; it does not mean that reference benchmark was requested.

Initial network checks failed when getpeername ran after connection teardown,
and when a sendmsg-interruption test assumed inherited SIGUSR1 was unblocked.
Peer inspection now occurs while the connection is active. The signal test
explicitly unblocks/restores SIGUSR1 and reports its inherited state; both engines
reported it initially blocked. Progress output and socket timeouts exposed this
precondition instead of hiding it behind an outer timeout. These were corrected
test assumptions, not repaired Rootless bugs. The added DNS fixture initially
failed the warning-as-error enum comparison check; using the declared ns_type
resolved it before execution.

Native warning-as-error build/import audit, static/dynamic fixture build, APK
packaging/signature verification and canonical JDK17 verification passed:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew \
  :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test \
  :android-runtime:assembleDebugAndroidTest \
  -ProotlessAcceleratedPayload=/tmp/terminal-unpack/prepared \
  -ProotlessStdioBuffer=true --console=plain
```

Runtime unit results: 180 passed, one optional external-archive fixture skipped.
Image: four passed. Unchanged unit tasks reused Gradle's up-to-date results.
Android MCP supplied install/instrumentation and package/device evidence:
ARM64 API 35 redroid, targetSdk 35, existing normal INTERNET permission granted,
CapEff=0, TracerPid=0, **SELinux Disabled and initial Seccomp=0**. No stock enforcing
phone was available. Context7 returned no matching narrow socket/resolver docs;
Linux man-pages, installed headers and upstream glibc source supplied the fallback.
The first two upstream tag URL fetches failed; the release branch was accessible.
Replicant and connected-app tools were unnecessary for this test-only work.

Required remaining compatibility work includes named/abstract Unix sockets,
additional families and message operations, complete errno/signal/concurrency
semantics, DNS/NSS integration, real distribution CA/network configuration,
external DNS/Internet, apt/dpkg, threads, and product session/UI lifecycle support.
The successful local DNS test does not excuse these gaps. See the full
[compatibility completion criteria](../../docs/DIRECT_LINUX_EXPERIMENT.md#호환성-동등성-기준).

## Reproduction

Build and package with the [ELF instructions](ELF.md#reproduction), selecting
`fixtures.py /tmp/terminal-direct-linux/app-fixtures --applications` and passing
that fixture directory to the packager. Install the standalone test APK using
Android MCP, then run:

```sh
am instrument -w \
  -e class dev.terminal.runtime.directlinux.DirectLinuxLoaderTest \
  -e directLinuxLoader true -e directLinuxNetwork true -e directLinuxPty true \
  -e directLinuxLoaderSha256 4e1895b5322d204783654f3b3f1bab1e35f7a0df06bc0c3046d753ff547733d4 \
  dev.terminal.runtime.test/androidx.test.runner.AndroidJUnitRunner
```

The application starts the runtimes at its normal UID through the central APK
executable loader. Retrieve `files/direct-linux-loader-result.json` with run-as
only after instrumentation completes. `local_glibc_dns_verified` is true;
`external_dns_verified`, `named_unix_sockets_verified`, `stock_policy_verified`
and the PTY scenarios' `ui_lifecycle_verified` remain false.
