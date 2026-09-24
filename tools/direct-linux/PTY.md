# Direct Linux PTY and job-control milestone

2026-09-24. The standalone test APK now launches interactive Linux shells through
the repository's existing `PtyProcess` host. The same scenario runs against the
pinned accelerated Rootless engine. This is PTY compatibility evidence, not
product session integration or overall Rootless compatibility parity.

## Runtime changes

The fixed device view adds `/dev/tty`, validated as character device 5:0. It
resolves through the kernel to the calling process's controlling terminal; it
does not expose arbitrary Android terminals. The existing device-directory
identity, search-permission, no-follow, enumeration and mutation rules still
apply. A process without a controlling terminal receives ENXIO on an ordinary
open. Metadata lookup does not manufacture a terminal.

`ioctl` now goes directly to the kernel through the runtime's seccomp allow path.
It requires no ABI/path translation for the supported ARM64 calls. Running it
inside the SIGSYS handler would use that handler's blocked signal mask, changing
background terminal-operation behavior. Direct dispatch retains the caller's
mask, SIGTTOU job control and blocking/interruption behavior. Inherited Android
policy continues to apply. The launcher supplies `TERM=xterm-256color` when its
stdin is a terminal; it still does not import the Android application's complete
environment into the guest.

The host uses the existing JNI PTY allocator, session setup and resize functions.
The test owns the PTY master and waiter; guest exec preserves the process/session
and inherited slave descriptors. No Activity or renderer owns these test
processes. This does not establish real UI detach/recreation behavior: the report
explicitly says `ui_lifecycle_verified: false`.

The device and controlling-terminal contracts are documented in
[tty(4)](https://man7.org/linux/man-pages/man4/tty.4.html) and
[TIOCSCTTY](https://man7.org/linux/man-pages/man2/TIOCSCTTY.2const.html).

## Shared scenario and corrected test assumptions

Both engines passed the same checks using the same Linux program/library assets:

- Static/dynamic glibc: tty identity, foreground process group, termios, initial
  window size, and a forked child's loss of its controlling terminal after setsid.
- A background child's tcsetpgrp stops with SIGTTOU; the parent reaps it and
  retains the foreground. This guards the ioctl signal-mask behavior.
- Unmodified GNU stty reads `/dev/tty` size and changes echo settings. Interactive
  dash reads and prints a Korean UTF-8 line, with input echo suppression checked.
- Host resize from 24x80 to 37x111 reaches the foreground guest as SIGWINCH;
  both the C fixture and GNU stty observe the new size.
- Ctrl-C ends a foreground child with shell status 130 while the same shell
  survives. Ctrl-Z stops a job; jobs/bg observe and resume it; explicit kill/wait
  reports status 143. Explicit shell exit returns 23.
- Closing the last PTY master causes the kernel's terminal hangup behavior.

Initial harness attempts failed because input raced ahead of the shell prompt,
because output slices can start directly at a marker, and because interactive
dash cancels the remaining command list on Ctrl-C. The harness now waits for
each prompt, checks line boundaries including the start of each slice, and reads
the interrupt status in a separate command.

The first hangup assertion incorrectly expected every launched guest to die from
SIGHUP. Direct's guest is the session leader. PRoot instead has a separate tracer
as leader, with the guest as its child. The
[Linux terminal hangup implementation](https://raw.githubusercontent.com/torvalds/linux/v5.10/drivers/tty/tty_jobctrl.c)
signals the session leader and clears the controlling terminal from session
members. The final fixture reports its observed leadership role before closure.
A leader must terminate with SIGHUP; a nonleader must observe POLLHUP, read EOF,
and ENXIO when reopening `/dev/tty`, then returns the fixture's distinct success
code 78. The expected result is selected from the observed role, not an engine
name or an arbitrary accepted error code. Direct returned -SIGHUP; Rootless
returned 78. The earlier status 1 after the old five-second delay was a failed
test assumption, not evidence of a runtime regression or a repaired PRoot bug.

The host reader uses bounded poll/nonblocking reads and stops before explicit
master closure. A blocked read retaining the master must not invalidate the
hangup experiment. All processes have bounded waits and are reaped. No test
transcript containing transient process IDs is committed.

## Evidence and reproduction

The [raw report](results/pty-runtime-2026-09-24.json) contains 702 immutable asset
hashes, 40 non-PTY launches, two shared PTY scenarios (an interactive shell and a
hangup process per engine), observed hangup roles/results, and the exact PRoot,
loader and acceleration-library hashes. Instrumentation passed in 6.710 seconds.
Earlier Direct ELF/process/filesystem/FD/Python/Git checks remained enabled.
The broader Python/Git reference benchmark and its previously recorded cleanup
failure were not rerun here; this run contains no performance samples.

Prepare `fixtures.py --applications`, build/package/sign using the
[ELF instructions](ELF.md#reproduction), then run through Android MCP:

```sh
am instrument -w \
  -e class dev.terminal.runtime.directlinux.DirectLinuxLoaderTest \
  -e directLinuxLoader true -e directLinuxPty true \
  -e directLinuxLoaderSha256 ca2fa891746cae5f3a86a31d1aaf8dffcf73732c39f015109afe54b297f734c7 \
  dev.terminal.runtime.test/androidx.test.runner.AndroidJUnitRunner
```

Warnings-as-errors native/fixture builds, import/TLS audit, APK packaging and
signature verification passed. Canonical JDK17 verification also passed:

```sh
./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest \
  :image:test :android-runtime:assembleDebugAndroidTest --console=plain \
  -ProotlessAcceleratedPayload=/tmp/terminal-unpack/prepared \
  -ProotlessStdioBuffer=true
```

Runtime unit tests: 180 passed, one optional external-archive fixture skipped.
Image tests: four passed. Unchanged unit tasks reused up-to-date Gradle results.
Android MCP supplied live ARM64 API 35 redroid evidence, with SELinux Disabled,
initial Seccomp=0, CapEff=0 and TracerPid=0 for Direct's launch. No enforcing phone
was available. Context7 did not return the requested PTY contract; Linux
man-pages/kernel source and the existing JNI implementation supplied it.
The test collector uses the documented [Android Os API](https://developer.android.com/reference/android/system/Os).
Replicant/UI tools and connected-app tools were unnecessary for this runtime test.

## Remaining compatibility gates

Direct Linux still falls short of the user's requirement for equal or greater
compatibility than existing Rootless. This milestone does not expose devpts or
ptmx to the guest, implement ttyname/proc-FD terminal names, create nested guest
PTYs, or validate terminal editors/full-screen applications. General threads,
signals, virtual identity/hardlinks, network/DNS, Debian installation/apt/dpkg,
independent recovery/storage, common session/backend ownership and enforcing
device policy remain necessary. UI detach/recreation, reattach/replay and explicit
session termination must be tested after product integration. Existing Rootless
and the production APK payload remain unchanged.
