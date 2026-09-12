# Rootless distributions

The permissionless ARM64 application supports Debian, Ubuntu 24.04 LTS and
Arch Linux ARM as explicit selections within the existing Rootless execution
family. All use the APK-packaged PRoot and loader, the Android kernel, and the
same service-owned PTY/session interfaces. Arch Linux ARM is the ARM project,
not an emulated x86_64 Arch installation.

## Approved image catalog

`RootlessDistribution.kt` is the allow-list and authoritative checksum catalog.
Image URLs and hashes come from the `termux/proot-distro` v4.29.0 plugins:

| Selection | Host-local wire name | Directory under `files/rootless` | Pinned archive | Package manager |
| --- | --- | --- | --- | --- |
| Debian Trixie | `ROOTLESS` | `debian` | `debian-trixie-aarch64-pd-v4.29.0.tar.xz` | apt/dpkg |
| Ubuntu 24.04 LTS | `ROOTLESS_UBUNTU` | `ubuntu` | `ubuntu-noble-aarch64-pd-v4.18.0.tar.xz` | apt/dpkg |
| Arch Linux ARM | `ROOTLESS_ARCH` | `archlinux` | `archlinux-aarch64-pd-v4.22.1.tar.xz` | pacman |

Sources:

- [Debian plugin](https://github.com/termux/proot-distro/blob/v4.29.0/distro-plugins/debian.sh)
- [Ubuntu plugin](https://github.com/termux/proot-distro/blob/v4.29.0/distro-plugins/ubuntu.sh)
- [Arch plugin](https://github.com/termux/proot-distro/blob/v4.29.0/distro-plugins/archlinux.sh)
- [Arch image preparation](https://github.com/termux/proot-distro/blob/v4.29.0/distro-build/archlinux.sh)

These are pinned bootstrap snapshots, not claims of being the latest package
versions. Update packages using the distribution's package manager. Arch uses a
rolling repository and requires a full `pacman -Syu` rather than partial
upgrades. The Arch image already initializes/populates the Arch Linux ARM
keyring and enables `DisableSandbox` for PRoot compatibility. Package signature
verification remains enabled. Bundled Arch repository sync indexes are discarded
during staging so their extraction timestamps cannot cause stale HTTP 304
responses on the first update; the installed-package database and keyring are
preserved. Ubuntu uses its distribution repositories; this
app does not add the upstream plugin's optional browser PPAs.

## Installation and ownership

Each distribution owns a separate `rootfs`, `home`, `tmp`, staging directory and
cache subdirectory. Installation failure/retry affects only that distribution. Cleanup restores owner
access on discarded read-only directories and deletes links without following
them into sibling data.
The minimum free space before extraction is 2 GiB for Debian/Arch and 4 GiB for Ubuntu.
Official package installation also needs network access and room for package caches. Compressed
archive limits are 128 MiB for Debian and 256 MiB for Ubuntu/Arch; extracted data
is bounded to 2 GiB and 100,000 entries. Downloads use HTTPS and must match the
pinned SHA-256 before the existing safe extractor handles them.

The service retains one parameterized `RootlessDebianBackend` per distribution;
the historical class/layout names are retained to avoid unnecessary source
churn. Registry identities distinguish distributions without introducing a
second session transport or moving process ownership into UI code. A session
request never switches to another distribution on failure.

Existing Debian paths and `rootless-` session IDs are unchanged, so there is no
rootfs migration or reinstall. Ubuntu and Arch use `ubuntu-` and `archlinux-`
IDs. They share only the explicit `/workspace` binding and the existing optional
Android shared-storage grant. Separate homes/databases prevent accidental
mixing; PRoot and the common app UID do not provide hostile-guest isolation.

## User flow and scope

The new-session menu offers Debian, Ubuntu 24.04 LTS and Arch Linux ARM. Selecting
an uninstalled distribution asks to install that named environment, then opens
its shell. Rootless Linux settings show all three distributions together, each with its
own status and install, retry or remove action. Removal asks for confirmation,
rejects live sessions, and deletes only that distribution's rootfs, staging and
download cache. The confirmation keeps home by default and offers an explicit
unchecked option to delete that distribution's home too. Shared workspace and
other distributions are always retained; the image can be installed again.
Installation progress includes the distribution identity so events
for one environment do not overwrite another environment's status.

This supports shells, package managers and compatible user-space programs. It
does not promise systemd boot, a full desktop, privileged mounts, namespaces,
cgroups, or an independent kernel. UI detachment does not terminate sessions;
actual Android runtime-process death still loses live sessions.

Ubuntu preparation creates `/root/.hushlogin` if absent, for both new and existing
installations. Ubuntu's Bash startup honors this flag to skip its sudo hint,
whose `groups` lookup otherwise prints warnings for Android supplementary GIDs
inherited through PRoot. The flag also requests quiet login from tools that honor
it. Existing markers and shell configuration are preserved; a removed marker is
recreated on the next preparation. This does not change Android permissions or
guest group databases: explicitly running `groups` may still report unknown GIDs.
This Ubuntu compatibility marker is separate from the distribution startup files described below.

## Distribution default environment

An extracted bootstrap archive alone is not ready. Preparation installs each
project's default package selection, including recommended dependencies:

| Distribution | Official selection | Reference |
| --- | --- | --- |
| Debian | required, important and standard priority packages | [tasksel standard task](https://sources.debian.org/src/tasksel/3.81/tasks/standard/) |
| Ubuntu | `ubuntu-minimal`, `ubuntu-standard`, `ubuntu-server` | [Ubuntu Server metapackage](https://packages.ubuntu.com/noble/ubuntu-server) |
| Arch Linux ARM | `base`, with a full `pacman -Syu` | [Arch ARM image preparation](https://github.com/termux/proot-distro/blob/v4.29.0/distro-build/archlinux.sh) |

There is no common Git/Python/compiler bundle. Tools appear according to the
selected distribution's metadata; extra development tools remain user choices.
Debian/Ubuntu refresh indexes, recover interrupted dpkg configuration, install
the selection and upgrade within the configured release without package removal.
Arch performs a full upgrade, never a partial upgrade. Existing package-manager
configuration and signatures are retained. PRoot cannot boot the services these
packages provide; Debian-family service startup is suppressed during preparation,
and the working resolver configuration is restored after package scripts run.

Missing `.bashrc` and login startup files are copied from that image's root home
or `/etc/skel`. Existing files and links are preserved, and a new `.bash_profile`
must not shadow an existing `.profile`. Credentials are never copied. Preparation
restores the image installation hook's `en_US.UTF-8` locale, retains other generated
locales and user locale selections, and reads the distribution's locale config
as data when starting sessions. Existing timezone configuration is preserved;
an absent timezone receives UTC. Android language/timezone does not overwrite
these distribution settings. Ubuntu's `.hushlogin` remains the narrow PRoot
compatibility adjustment above.

Settings distinguish `ENVIRONMENT_REQUIRED` (base image installed, environment
completion needed) from `SETUP_REQUIRED` (no usable base image). Package,
configuration and command/locale verification stages must all succeed before
writing the versioned environment marker and reporting `READY`. Ready preparation
does not contact repositories; ordinary later updates belong to the user.
Older installations are completed in place, retaining home, workspace, packages
and configuration. Arch’s read-only root-directory mode is restored after the
app briefly enables owner writes to update its completion marker. Failure keeps
the usable rootfs for retry and leaves removal
available. A live session blocks maintenance/removal for its distribution; other
distributions remain usable. UI detachment does not cancel the service-owned job.

## Validation

Unit tests cover package-manager validation, unchanged Debian identity,
distribution path/cache separation, explicit routing without fallback, sibling
session survival, and UI readiness selection.

The opt-in Android instrumentation class
`dev.terminal.app.RootlessDistributionsTest` installs Ubuntu/Arch if needed and
checks `/etc/os-release`, package-manager execution, PTY dimensions, shell state
across detach/attach, and termination isolation. It leaves installed rootfs
intact and terminates only its own sessions. Run on an ARM64 Android target with
internet access and sufficient free space:

```sh
./gradlew :android-app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.terminal.app.RootlessDistributionsTest \
  --console=plain
```

To additionally update package indexes/packages and install/run `hello` (Ubuntu) or `jq` (Arch), add
`-Pandroid.testInstrumentationRunnerArguments.rootlessPackages=true`. This changes
packages in the selected installed environments and can take several minutes.

Canonical verification remains the AGENTS.md command, with
`:android-app:testDebugUnitTest` added for UI-model tests. Device results must be
reported separately from compilation and do not establish compatibility with
every OEM kernel or Android release.

On 2026-09-08, the ARM64 Android 15 target passed first-install and session
checks for Ubuntu and Arch, plus Ubuntu apt/hello and Arch full pacman upgrade
and jq execution. The first Arch package check exposed stale bundled sync
indexes; staging now removes those indexes and the fresh-install package check
passed. This is one target's evidence, not a claim about all Android devices.

`dev.terminal.app.RootlessImageRemovalTest` exercises real Debian image
installation, removal, retained home/workspace/sibling data, and reinstallation
in isolated test storage. It requires network access and never removes user
images. On 2026-09-09 this passed on the ARM64 Android 15 target. Unit tests also
cover symlink-safe cleanup and rejection of live or unreadable session lists.

Image removal also works when settings bind the runtime service before any shell
has started. It must not initialize or connect the primary Native/AVF session
runtime. The service uses an existing registry when present and the Rootless
backend checks its own PTY owner before deletion in either case.
`RootlessColdRemovalTest` verifies all three removal buttons and confirmations
with disposable rootfs fixtures, unchanged stopped runtime state, and retained
homes. Run only in an isolated APK with application ID
`dev.terminal.removaltest`; it deliberately skips the normal user application.
This UI/service regression test passed on the ARM64 Android 15 target.

`RootlessDefaultEnvironmentTest` exercises official package installation for all
three distributions in isolated test storage. It checks retained rootfs/home,
interrupted setup and repair, readiness only after verification, offline ready
preparation, live-session maintenance rejection, and preservation of a custom
locale/timezone. It requires network access and several GiB of free space, and
leaves its test trees on failure for diagnosis. Run with instrumentation argument
`class=dev.terminal.app.RootlessDefaultEnvironmentTest`; optional
`distribution=debian|ubuntu|archlinux` limits the run. If the host Gradle device
bridge cannot run, build app/androidTest APKs, install both using Android tools,
and invoke `am instrument` on the target, reporting this fallback separately.

On 2026-09-09, ARM64 Android 15 validation completed for all three default
profiles, including custom locale/timezone preservation and interrupted repair.
Arch initially exposed read-only `/` metadata writes and PRoot absolute hardlink
representations; both were corrected and its full scenario then passed. The
isolated cold-settings test also passed with `ENVIRONMENT_REQUIRED` images and
retained homes. The host Gradle device bridge failed, so APK installation and
instrumentation used Android/replicant MCP instead. These results do not establish
systemd boot support or compatibility with every device kernel.

## Installation progress

All three distributions retain their latest preparation stage in the runtime
backend. Settings and the installation dialog restore it when they reconnect.
A progress bar represents the current stage, never an invented whole-install
percentage. Archive download uses received bytes and the HTTP content length
when known; SHA-256 verification uses bytes read. Extraction reports processed
entries and extracted bytes without assuming a total. Package-manager output is
framed and bounded before recognized status messages reach the UI.

Debian/Ubuntu report repository refresh, interrupted-package recovery, dependency
selection, package download/install, existing-package upgrade and consistency
checks. Measured package progress comes from the documented
[APT Status-Fd protocol](https://github.com/Debian/apt/blob/main/doc/progress-reporting.md).
Arch keeps a single full `pacman -Syu` transaction; plain output distinguishes
repository refresh, upgrade planning, downloads, integrity checks, installation
and post-transaction hooks. Arch percentages are not inferred from text or time. Debian/Ubuntu retain apt’s
default dpkg PTY behavior; progress reporting must not disable it, since doing so
produced package-script stdout I/O errors in device validation.
Both paths then report locale generation, timezone checks, home setup and final
package/command/environment verification. The package selections and security
policy are unchanged by progress reporting.

Finite maintenance commands use PRoot `--kill-on-exit`, so detached helpers
created by that invocation (such as Arch keyring hooks' `gpg-agent`) cannot hold
the completed package stage open. This applies only to setup/verification
commands, not interactive terminal sessions.

Stages without measurable totals use an indeterminate bar and elapsed time;
a long interval without a recognized progress message shows the age of the last
report. This distinguishes missing progress information from proof of a hung
process. Package/configuration/verification subprocesses retain their 20-minute
stage timeout, with the last recognized stage included in timeout errors.

`RootlessInstallProgressUiTest` runs fresh installs of all three distributions
through the actual service, recreates settings during each install, and checks
snapshot restoration, visible elapsed progress, measured values and final readiness.
It only runs in an APK with application ID `dev.terminal.progresstest`, refuses
existing rootfs trees, and removes its own test images after successful validation.

On 2026-09-10, ARM64 Android 15 fresh-install validation passed for Debian and
Ubuntu, including all final checks and settings recreation. Arch exposed a
detached `gpg-agent` holding PRoot open; after the maintenance-only
`--kill-on-exit` fix, a separate fresh Arch run passed without intervention
(192 seconds). Manual Debian dialog dismissal/reentry also preserved the running
stage and elapsed time and displayed measured package progress. Installation and
shell startup then completed normally with the final maintenance behavior.
The isolated progress test application was removed after validation. Installation and
instrumentation used Android/replicant MCP. An additional attempt to rerun
`RootlessDefaultEnvironmentTest` against older cache fixtures could not start
Debian because its cached ELF loader was missing; that repair rerun is not a pass.
