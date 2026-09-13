# TERM

**TERM** stands for **Terminal Environments with Runtime Multiplexing**.

TERM is an Android Linux runtime with a Termux-like terminal UI and
a real Debian ARM64 guest when deployed in an Android Virtualization Framework
(AVF) capable system image.

The project deliberately separates three lifetimes:

```text
Android UI  !=  :vmhost runtime owner  !=  guest PTY/session daemon
```

Closing the console or removing its task must only detach the UI; it must not
terminate the VM or an existing guest shell.

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

## Update versions

Every APK derives its Android `versionCode` from the complete commit count of
`origin/main`, and its `versionName` includes that mainline revision's short
SHA. A newly advanced `origin/main` is therefore a new app update; builds from
other branches retain the version of the `origin/main` revision they target.
The build deliberately fails when that ref is unavailable, rather than
producing an APK that Android could mistake for an update. CI fetches the full
mainline history before building.

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
