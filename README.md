# TERM

**TERM** stands for **Terminal Environments with Runtime Multiplexing**.

TERM is an Android Linux runtime based on
[Termux](https://github.com/termux/termux-app). It uses and extends Termux's
`terminal-emulator` and `terminal-view` libraries, and provides a real Debian
ARM64 guest when deployed in an Android Virtualization Framework (AVF) capable
system image.

The project deliberately separates three lifetimes:

```text
Android UI  !=  :vmhost runtime owner  !=  guest PTY/session daemon
```

Closing the console or removing its task must only detach the UI; it must not
terminate the VM or an existing guest shell.

## License

TERM is released under the GNU General Public License, version 3 only
(`GPL-3.0-only`), the same repository license as Termux. See
[LICENSE.md](LICENSE.md) for the license notice, full license text, and
third-party exceptions. Third-party components retain their respective licenses
and copyright notices.

## What is implemented

- Korean Material dark UI with initial setup, console, settings, and diagnostics
- immediate stock-Android Native shell fallback with app-managed home and temp data
- automatic Debian, Ubuntu 24.04 LTS and Arch Linux ARM bootstrap with an APK-packaged PRoot runtime
- persistent `:vmhost` foreground runtime service behind `RuntimeBackend`
- AVF product-driver seam, guest vsock session protocol, PTY session daemon
- capability-controlled clipboard, URL, notification, storage, and port APIs
- verified Debian ARM64 image contract and safe local-image import
- guest port reporting with loopback-only host forwarding
- wake-policy persistence and guest-work wake-lock policy
- guest Rust services and Termux:API compatibility command shims

## Building

```sh
./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test
```

The debug APK is written to:

```text
android-app/build/outputs/apk/debug/android-app-debug.apk
```

Debug builds install as **TERM Debug** (`dev.terminal.app.debug`) alongside stable
and prerelease builds (`dev.terminal.app`). Their settings, runtime data, and
sessions are separate. Update debug builds by installing a new APK signed with
the same debug key; release-channel updates are disabled in the debug app.
See [debug build details](docs/DEBUG_BUILDS.md).

## Update versions

Base versions follow the [version promotion policy](docs/VERSIONING.md): fixes
increment patch, compatible features increment minor, and incompatible changes
after 1.0 increment major. The current development baseline is `0.2.0`.

`origin/main` advances provide stable updates; `origin/dev` advances provide
development prereleases. Settings → App updates selects either channel, with
the installed APK's channel as the initial default. Both use the same app ID
and release signing key. CI uses `1000000 + GITHUB_RUN_NUMBER` as a shared Android
`versionCode`, avoiding branch-count collisions; the name is
`<base>+<main|dev>.<code>.<12-character source SHA>`. An older channel cannot
downgrade the installed app. Local builds default to the checked-out dev branch
or main, identify that remote ref, and use its commit count unless
`TERMINAL_UPDATE_VERSION_CODE` is supplied. Set `TERMINAL_UPDATE_CHANNEL=main|dev`
to override the local channel. Local count-based APKs do not replace newer CI
APKs. Missing refs or shallow Git history fail the build.

### Signed APKs outside a store

Generate a private PKCS12 keystore once with `keytool`, using alias `terminal`,
and keep it at `~/.local/share/terminal-signing/terminal-release.p12` (or set
`TERMINAL_SIGNING_STORE_FILE` to another location). Back up the keystore and
password outside Git; every update must use the same signing identity.

```sh
bash tools/build-release.sh
```

The script prompts for the password without echoing it. The resulting signed
APK is `android-app/build/outputs/apk/release/android-app-release.apk`.
For non-interactive builds, supply `TERMINAL_SIGNING_STORE_PASSWORD` through
the environment. Optional `TERMINAL_SIGNING_KEY_ALIAS` and
`TERMINAL_SIGNING_KEY_PASSWORD` support other existing keystores.
Missing credentials fail release packaging instead of generating an unsigned
update. Debug builds continue to use their separate debug signing identity.

For CI, add these repository secrets under **Settings → Secrets and variables
→ Actions** using GitHub's web UI or interactive `gh secret set`:

- `TERMINAL_SIGNING_KEYSTORE_BASE64`: Base64 encoding of the same `.p12` file.
- `TERMINAL_SIGNING_STORE_PASSWORD`: The keystore password.

With an authenticated GitHub CLI, register the file without printing its
contents, then enter the password at the second command's hidden prompt:

```sh
base64 -w 0 "$HOME/.local/share/terminal-signing/terminal-release.p12" \
  | gh secret set TERMINAL_SIGNING_KEYSTORE_BASE64 --repo nolbo/terminal
gh secret set TERMINAL_SIGNING_STORE_PASSWORD --repo nolbo/terminal
```

After verification, main/dev push builds publish the signed APK, integrity
metadata and native corresponding-source bundles directly from the verified
build directory to the public [TERM distribution repository](https://github.com/nolbo/TERM).
Channel build jobs are serialized through publication. Actions copies of the
APKs and safety report are optional; artifact storage failures do not bypass
verification or prevent publication.
Cross-repository publishing requires a separate `TERMINAL_UPDATES_TOKEN` secret,
restricted to that repository with `Contents: write`. Fork PRs and other
branches other than main/dev do not receive signing or publication secrets.
**Settings → App updates** checks the public release, downloads the APK, verifies
its size, SHA-256, package/version and installed signing identity, and opens
Android's installer only after explicit confirmation. See [App updates](docs/APP_UPDATES.md)
for publisher setup, version history rules and lifecycle limitations.
An existing debug-signed installation cannot be replaced by this release key
without a supported signing-key migration or a reinstall with data handling.

The ARM64 PRoot executable and loader are checked, 16 KiB-aligned build
artifacts with a pinned-source rebuild path in `tools/rootless`. Runtime setup
downloads only checksum-pinned distribution rootfs archives; the host PRoot and
loader remain immutable APK payloads. See [Rootless distributions](docs/ROOTLESS_DISTRIBUTIONS.md).

## Deployment boundaries

A normal Play Store APK cannot acquire `MANAGE_VIRTUAL_MACHINE` or
`USE_CUSTOM_VIRTUAL_MACHINE` by declaring them. A real AVF guest requires an
OEM/platform-signed build and the system-SDK AVF driver module. The public
build reports this clearly in its runtime state and diagnostics instead of
claiming that a VM started.

Automatic image setup accepts only a catalog-pinned, HTTPS-delivered AVF image
bundle with a compiled-in manifest SHA-256. This source build intentionally
ships with no release URL; see [docs/IMAGE_RELEASE.md](docs/IMAGE_RELEASE.md).
The directory picker remains available for a locally verified image.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Security boundary](docs/SECURITY.md)
- [Protocol](docs/PROTOCOL.md)
- [AVF deployment notes](docs/AVF_NOTES.md)
- [Privileged deployment](docs/DEPLOYMENT.md)
- [Image release procedure](docs/IMAGE_RELEASE.md)
- [사용 가이드 (한국어)](docs/USER_GUIDE.ko.md)

On an AVF-enabled test device, use `tests/integration/acceptance.sh` for the
session persistence, bridge, shared-storage, forwarding, and Recents tests.
