# AOSP AVF runtime driver

`AospAvfPlatformDriver.kt` implements the runtime-neutral `AvfPlatformDriver` interface. The `:aosp-avf-runtime` Android library is now included in ordinary debug and release APKs by `:android-app`. The owning `TerminalApplication` implements `RuntimeBackendProvider`, keeps Native as the default owner, and creates a new AVF owner only for an explicitly selected AVF storage. It preserves app update/session stores, crash reporting, and WorkManager configuration in both the main and `:vmhost` processes. The former standalone `AospRuntimeApplication` is removed; product manifests must use `dev.terminal.app.TerminalApplication`.

## Compilation and device access

`:aosp-avf-api` supplies only the required AOSP ABI declarations via `compileOnly`; no `android.system.virtualmachine` implementation or stub may enter the APK. They are based on the [AOSP AVF sources at commit 175a51b30123fa6b02b541f1969665708f7ec2c3](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/libs/framework-virtualization/src/android/system/virtualmachine/). Optional `-PavfFrameworkJar=/path/to/framework-virtualization.jar` replaces these declarations with the exact full ABI from the target AOSP product; ordinary System SDK stubs omit the hidden custom-image APIs and cannot serve as that input.

The manifest requests the device-provided `android.system.virtualmachine` shared library with `required=false`. This preserves installation and Native/Rootless operation on devices without AVF. Before creating the platform driver, the factory checks Android 15+, the AVF feature, and both actual grants:

```text
android.permission.MANAGE_VIRTUAL_MACHINE
android.permission.USE_CUSTOM_VIRTUAL_MACHINE
```

Packaging the driver grants neither permission. The device must also expose compatible custom-image methods and authorize their use under its platform API policy: custom-image configuration is `@hide` in AOSP, not an ordinary public or System SDK API. Granting the two permissions alone is therefore not proof that every stock APK can execute it. No reflection, hidden-API exemptions, permission grants, or bundled framework implementation are used. Missing API linkage reports an explicit AVF failure instead of affecting the Native default owner.

Verify the built APK with `python3 tools/avf/verify-driver-apk.py android-app/build/outputs/apk/debug/android-app-debug.apk`. This inspects DEX class definitions, including that all compile-only platform classes are absent. `AvfDriverIntegrationTest` verifies the unsupported-device guard and a real Native shell through the runtime service; actual AVF boot still requires an authorized AOSP/ARM64 target and approved image.

## Fixed initial image mapping

The signed Debian image contract maps disks in this exact order:

```text
vda = system.img  (read-only)
vdb = home.img    (writable)
vdc = config.img  (read-only)
```

The guest image's kernel command line and `fstab` must agree with that mapping.
Legacy and adopted-default profiles retain `droid-default`, using `SessionStorage.avfInstanceId` even though Native is now the default owner. `getOrCreate` reuses an existing AVF instance and ignores a replacement config;
therefore image upgrades must use an explicit reset/migration flow rather than
silently replacing boot or disk files.

`VirtualMachine.run()` is asynchronous. The driver waits for the AVF ready
callback, then retries the `droid-sessiond` vsock port (40500) for a bounded
period. A successful `RuntimeBackend.start()` consequently means the VM has
reported ready, while a successful `connectGuestAgent()` means sessiond has
accepted a vsock connection.

The capability bridge uses a second, dedicated guest-agent port: `40501`.
`VmRuntimeService` connects it only after the VM is ready and dispatches its
versioned `BRIDGE` frames through `AndroidCapabilityBroker`; it is never
multiplexed with PTY bytes on port `40500`.

Forwarded guest services use raw vsock streams only inside the runtime owner:
the AOSP driver opens the requested non-privileged port and wraps its descriptor
as `GuestByteStream`; `LocalhostPortForwarder` binds the corresponding host
listener exclusively to `127.0.0.1`.

The public runtime provisions one host-owned directory under
`files/shared-vms/<vm-id>` for a future virtiofs export. It is deliberately not
mounted by this first driver until the product supplies the matching AOSP
virtiofs service/socket configuration and guest fstab entry. This prevents an
incorrect claim that a regular directory is already visible in the guest.
