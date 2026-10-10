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

After the capability checks, the factory allocates a custom-image builder to check constructor access before any image download or VM creation. A constructor/configuration `LinkageError` reports that either the ABI is incompatible or Android denied hidden-API access, preserving the original error. The pinned constructor is `Builder()`; ART's `NoSuchMethodError` can also mean policy denial. Compare the target framework's signatures and hidden-API logcat messages before changing the constructor or rebuilding against an OEM jar. See `docs/DEPLOYMENT.md` at the repository root for diagnosis.

Verify the built APK with `python3 tools/avf/verify-driver-apk.py android-app/build/outputs/apk/debug/android-app-debug.apk`. This inspects DEX class definitions, including that all compile-only platform classes are absent. `AvfDriverIntegrationTest` verifies the unsupported-device guard and a real Native shell through the runtime service; actual AVF boot still requires an authorized AOSP/ARM64 target and approved image.

## Fixed initial image mapping

The signed Debian image contract maps disks in this exact order:

```text
vda = system.img  (read-only)
vdb = home.img    (writable)
vdc = config.img  (read-only)
```

The guest image's kernel command line and `fstab` must agree with that mapping.

Image provisioning only installs a missing path; the driver verifies an installed image once in `prepare` using `DebianImageInstaller.verifyForStartup`. This checks every immutable artifact and adopts fully verified older installations without resetting writable state. Both default and independent storage paths use this boundary before boot. Logcat tag `TermAvfDriver` reports image verification and VM/sessiond connection durations separately; adding sessions to a prepared VM avoids this startup work.

Legacy and adopted-default profiles retain `droid-default`, using `SessionStorage.avfInstanceId` even though Native is now the default owner. `getOrCreate` reuses an existing AVF instance and ignores a replacement config. Before running a stopped instance, the driver applies the verified configuration through `setConfig`, which rejects incompatible VM identity changes. The `systemd.hostname=localhost` boot parameter gives both existing and new Debian images a short guest hostname without changing persistent VM names or image files. Already-running guests are reconnected without reconfiguration; the hostname applies on their next boot. Image upgrades still require an explicit reset/migration flow.

`VirtualMachine.run()` is asynchronous. The driver retries the `droid-sessiond` vsock port (40500) for up to 120 seconds without waiting for Microdroid's `onPayloadReady` callback, which this custom Debian guest does not send. A successful `RuntimeBackend.start()` means sessiond accepted a connection; `connectGuestAgent()` hands that same descriptor to the session client. Error/stop callbacks fail the wait with their original reason. Failed new boots are stopped; already-running instances are reconnected without rerunning Linux or replacing their disks.

The capability bridge uses a second, dedicated guest-agent port: `40501`.
`VmRuntimeService` connects it only after the VM is ready and dispatches its
versioned `BRIDGE` frames through `AndroidCapabilityBroker`; it is never
multiplexed with PTY bytes on port `40500`.

Forwarded guest services use raw vsock streams only inside the runtime owner:
the AOSP driver opens the requested non-privileged port and wraps its descriptor
as `GuestByteStream`; `LocalhostPortForwarder` binds the corresponding host
listener exclusively to `127.0.0.1`.

## Connected virtiofs directories

Verified images declaring `virtiofsVersion=1` receive the Environment's explicitly connected shared-folder snapshot as independent app-domain `VirtualMachineCustomImageConfig.SharedPath` exports. The driver maps the app owner to guest UID/GID 1000 and supplies unique boot-local Unix sockets. It exports none of `files/shared-vms/<vm-id>`, the shared-folder parent or app-private storage. Primary shared storage uses its own capability below. A read-only guest index maps bounded UUID-based wire tags to validated names; the image's `term-shares.service` mounts the connected directories at `/mnt/term-shares/<name>` before sessiond starts. Direct sharing has ordinary filesystem behavior, separate from TFT1 copies and their limits.

Only stopped VMs receive new configurations. Reconnecting a running VM preserves its current mounts and table; rename/disconnection requires VM stop/start, including revocation. Host-only persistent share leases protect data deletion after connection edits and owner death. Verified VM stop or storage deletion releases them; folder deletion queries persisted VM status even if no backend is attached. AOSP owns the virtiofs processes and vhost-user transport; matching `SharedPath`/`addSharedPath` APIs, custom-image permission/policy and platform crosvm execution must be available. API or SELinux denial is a platform error, never a silent successful mount. Protected VMs reject live exports. Older images skip the sharing ABI and retain explicit file transfers. Existing images and the approved catalog are not upgraded automatically.

## Primary shared internal storage

Images advertising `androidStorageVersion=1` use the common Android all-files grant for `/sdcard` on non-protected VM boots. The root comes exclusively from Android's primary-volume API and uses app-domain `SharedPath`; it is not accepted through RuntimeConfig or the strict UUID-folder validator. A fixed `term-storage` tag and `term.android-storage=1` parameter select the guest prerequisite, with `nodev,nosuid,noexec`. No per-Environment toggle is added. Storage-only boots create no empty folder index. Persistent storage-marked leases and a common boot gate allow AppOps revocation/startup recovery to stop users before releasing artifacts, without waiting on sessiond readiness. A failed stop retains its record. Older images and protected VMs receive no internal-storage export; installed images/catalog are not replaced automatically.
