# Droid Terminal

Droid Terminal is an Android Linux runtime with a Termux-like terminal UI and
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
