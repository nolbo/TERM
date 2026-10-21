# Native Shell

Native is an immediate Android/Bionic environment, separate from Userspace Linux
and AVF. It keeps the existing service-owned PTY/session lifecycle. There is no
Native package manager or writable-data ELF loader. Git and Git-only dependencies
are deliberately absent from this bundle.

## Shell and files

Each Environment keeps its existing `home` and `tmp`. Empty cwd means that home;
an explicit cwd must already be an absolute directory. TERM supplies HOME,
TMPDIR, SHELL, ENV, UTF-8 LANG, TERM, COLORTERM, HISTFILE and HISTSIZE. TERM and
COLORTERM remain profile-overridable. HOME, TMPDIR, ENV and executable/library
paths are runtime-owned. The managed command directory precedes Android system
directories; profile PATH additions must be absolute existing directories.
Empty, relative and current-directory PATH entries are discarded.

Managed interactive defaults live at `shell/managed-shrc`, outside home, and
are replaced atomically only when their content changes. Put personal aliases,
exports and prompt overrides in `~/.termrc`, which is sourced last. Existing
`.profile`, `.vimrc`, `.sh_history` and `.droid-shrc` are preserved. A legacy
`.droid-shrc` is sourced before `.termrc`, including additions to a formerly
generated file. Replacing PS1
can disable OSC 133 prompt/history integration. The system mksh supplies shell
syntax, history, job control and scripting. Toybox applets and supported color
options vary by Android version and are checked rather than assumed.

AOSP device mksh builds disable persistent history (`HAVE_PERSISTENT_HISTORY=0`),
including the tested Android 15 device. In-memory `fc`/arrow history and TERM's
OSC 133 command history work, but merely setting HISTFILE cannot make those
builds persist shell history. The `.sh_history` default is available to OEM shells
that support it; no existing history file is overwritten or executed as a script.
Use TERM's command-history tool for session history, or Userspace Linux/Bash when
cross-session shell-history persistence is required. See the
[AOSP Android 15 mksh build](https://android.googlesource.com/platform/external/mksh/+/refs/tags/android-15.0.0_r14/Android.bp).

Invoke app-private scripts as `sh script.sh`; Android's executable-file policy
still applies to direct `./script.sh` launches. Unsupported non-ARM64 devices
retain the system Native shell without the ARM64-only CLI bundle.

## Bundled commands

| Command | Source / license | Runtime dependencies |
| --- | --- | --- |
| jq 1.8.2 | jqlang, MIT; bundled Oniguruma BSD | Bionic, libm |
| curl 8.23.0 | curl license | Bionic, system zlib, shared OpenSSL |
| ssh, ssh-keygen 10.6p1 | OpenSSH BSD/ISC notices | Bionic, shared libcrypto |
| rg 15.2.0 | MIT or Unlicense; Cargo dependency notices | Bionic |
| vim 9.2.1217 | Vim license | Bionic, static ncurses, packaged runtime |
| OpenSSL 3.5.9 | Apache-2.0 | Bionic |
| ncurses 6.5 | MIT | Bionic |

`tools/native-cli/sources.lock` pins official source URLs and SHA-256. CA roots
are the checked-in Mozilla bundle extracted by curl on 2026-10-20; updates must
review the bundle and regenerate the manifest, never fetch trust at app startup.
The build uses the repository NDK r28/API 31, the existing ARM64 host compiler
wrapper, and Rust 1.89.0 with upstream Cargo.lock. It does not install a toolchain.
Run `python3 tools/native-cli/build.py BUILD NDK` or the normal Gradle build.
Build-only files, dependency archives and debug symbols do not enter the APK.
Release CI verifies the packaged ELF/resource hashes and publishes
`native-cli-source.tar.gz` with pinned upstream archives, local build/patch recipes
and locked Cargo dependency sources beside each APK.
Rust standard-library notices and the selected NDK's compiler-runtime notices
accompany the component and vendored-dependency licenses in the offline catalog.

APK `.so` payload names are packaging containers for PIE executables.
`NativeExecutableLoader` resolves the fixed command allowlist exclusively under
the canonical APK native library directory. Standard command names are symlinks
to those immutable paths, so shell scripts and subprocesses use the same files
as interactive shells. No alias, executable copy, linker invocation, SELinux
change or downloaded ELF bypass is used. Shared OpenSSL libraries are isolated
as `libterm-crypto.so` and `libterm-ssl.so`; only Native receives their library
search path. Rootless/AVF package paths are unchanged.

The selected xterm/screen/tmux terminfo entries come from pinned ncurses source
and are supplied through TERMINFO, including alternate-screen and resize controls.
The build needs host `tic`, make, Perl, Python, LLVM utilities and the existing
Rust target in addition to the repository NDK. OpenSSH's Bionic compatibility
changes are bounded to HOME/tilde expansion, sentinel/bzero handling and public
resolver APIs; key and host verification remain upstream behavior.

Vim receives VIM/VIMRUNTIME plus syntax, indent, filetype, autoload, color and
default configuration files. Its user `.vimrc` remains authoritative after the
system defaults. GUI, embedded language interpreters, Vim terminal jobs,
translations, spell dictionaries and help documentation are omitted. This does
not remove terminal control, PTY resize or UTF-8 editing. curl retains HTTP(S),
certificate and hostname checks, file download and system zlib; unnecessary
protocols and optional protocol libraries are omitted. SSH is client-only; no
sshd, privilege helper, hardware-key provider or system daemon is bundled.

## Android access

`terminal-usb` resolves through PATH in both scripts and interactive shells.
Existing USB operations retain their broker grants. `terminal-usb storage roots`
and `terminal-usb storage ls ROOT [PARENT_HANDLE]` reuse the existing Android
folder selection, opaque handles and per-session read approval. Direct access
to shared storage still depends on the actual Android storage grant; no new
permission or mount is introduced.

`terminal-usb storage get ROOT HANDLE LOCAL_FILE` and
`terminal-usb storage put ROOT PARENT_HANDLE LOCAL_FILE [--name ASCII_NAME]`
reuse the same broker's separate read/write approvals and bounded, sequenced
64 MiB file-transfer protocol. Downloads publish a new local file atomically;
uploads create a new remote file and may leave that file partial if interrupted.

`printf 'text' | terminal-usb clipboard set` sends UTF-8 text (up to 32 KiB) to
the existing OSC 52 renderer callback. It requires an attached terminal and is
subject to the existing capability broker. Enable **Terminal settings → Android
permissions → Allow Native clipboard writes** first; it defaults off. The grant
has a fixed host Native scope, distinct from every AVF instance. Only the focused
Native terminal in the foreground can copy; background and other-runtime output
cannot. The callback is bound to UI observation and checkpoint restoration does
not replay clipboard requests. It cannot read the Android
clipboard, and emission alone is not confirmation of Android clipboard delivery.

## Verification

Native Diagnostics runs bounded commands in a disposable cache HOME/TMPDIR,
never a user rc, history, session or runtime owner. It reports actual system
command availability, all five CLI executions, loader errors, ABI/page size,
managed paths and storage access. Network authentication needs the separate
fixture; a version probe does not establish TLS or SSH authentication success.

`tools/native-cli/verify.py JNI ASSETS` checks ELF64/ARM64, Bionic interpreter,
16 KiB LOAD alignment, non-WX segments, packaged dynamic dependencies and hashes.
`python3 tools/native-cli/verify.py --apk APK` verifies actual packaged bytes,
resource hashes, Git exclusion and compressed/raw per-tool size. The generated
manifest and license/source metadata ship with the APK. ELF alignment is not a
substitute for execution on a 16 KiB Android device.

`NativeCliDeviceTest` covers real CLI probes, interactive Vim UTF-8/input/save/
syntax/resize, shell pipelines/redirection/exit status, detach/attach and close.
The optional network test uses `fixture.py` (host OpenSSH and OpenSSL), ephemeral
keys and loopback HTTP/TLS/SSH. Reverse device ports 18440–18442 to the same host
ports, then pass `nativeFixturePort=18440` and the fixture's `nativeFixtureUser`
to instrumentation. It checks trusted/untrusted/hostname TLS, key authentication,
strict host verification and local forwarding. Fixtures never ship in the APK.
Workspace, Split Terminal and background restoration use existing device
regression tests. API 31+ and ARM64 4/16 KiB runtime coverage must identify the
devices actually tested; unavailable targets remain explicit validation gaps.

The completed build/device checks, reproducibility comparison, APK size and
remaining target limitations are recorded in [NATIVE_VALIDATION.md](NATIVE_VALIDATION.md).
