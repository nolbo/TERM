# Privileged AOSP deployment

Stable TERM uses `dev.terminal.app`; TERM Preview uses
`dev.terminal.app.prerelease` (debug remains `dev.terminal.app.debug`). Provision
any package-specific platform allowlists and AVF permission grants for each
installed package separately. Installing Preview does not copy the stable app's
permissions, VM state or private files.

Run the automated image producer or its ARM64 workflow described in `IMAGE_RELEASE.md`. It builds the Rust guest, installs and enables `droid-sessiond`, `droid-agent`, and `droid-port-agent`, prepares the persistent overlay image, and generates the manifest and approved catalog. End users do not prepare a rootfs or checksum manifest.

The APK now includes the AVF execution driver and keeps `TerminalApplication` in the manifest; a separate `AospRuntimeApplication` is no longer used. Product deployment must supply the optional `android.system.virtualmachine` library, authorize its hidden custom-image APIs, and grant both AVF permissions described in `platform/aosp-avf-runtime`. Platform/OEM signing or an explicitly authorized development platform remains a deployment concern, not something APK packaging grants. Compile against the target product's full ABI with `-PavfFrameworkJar=...` when it differs from the bundled compile-only declarations. Once public downloads have been verified and `image/catalog-approved.properties` committed, both ordinary debug/release builds include the default image catalog automatically. Candidate builds can override it with `-PavfImageCatalog=...`; without an approved default or override, the catalog remains empty. The application implements WorkManager's on-demand main-process configuration; retain the manifest removal of `WorkManagerInitializer` and include `work-multiprocess` when compiling the product driver.

The image workflow builds a catalog-bearing candidate APK before optional publication to public `nolbo/TERM`, then verifies all unauthenticated public downloads before producing a default pin for review. The private source repository's release URLs cannot be used for end-user installation. See `IMAGE_RELEASE.md` for publication and catalog promotion; generating an unpublished candidate does not activate automatic installation in ordinary APKs.

Keep the WorkManager initializer removal in the application manifest as well as the runtime library manifest. Release Lint checks the application's on-demand configuration and must see its explicit removal; other AndroidX Startup initializers remain enabled.
Run `tests/integration/acceptance.sh` on the target device for the end-to-end
acceptance sequence.

## Custom-image API access failures

`No direct method <init>()V` for `VirtualMachineCustomImageConfig$Builder` can indicate either an incompatible platform ABI or Android non-SDK policy denial. The pinned AOSP API has the no-argument constructor. The [Android non-SDK interface documentation](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces) specifies that blocked direct method calls throw `NoSuchMethodError`; therefore granting the two VM permissions through ADB is not sufficient evidence of custom-image API access.

The runtime checks constructor access after capability checks and before image installation or VM creation. Startup reports the platform-access problem together with the original linkage error; existing images are retained.

A successful basic VM boot does not establish storage-sharing API support. Internal-storage and connected-folder exports additionally call `VirtualMachineCustomImageConfig.SharedPath` and `Builder.addSharedPath`. In **진단 및 리포트 → AVF Linux**, the separate storage-sharing section checks those direct calls using inert configuration objects; it neither creates files nor starts a VM or a virtiofs server. It runs only after the seven basic checks pass. Sharing failures retain their original error, and failed installation cards expose **오류 복사** with up to 2048 characters of persisted detail. This provides on-device evidence without ADB; a hidden-API denial and an OEM ABI difference remain distinct possibilities until the original error and target framework are examined.

For `SharedPath`, Android 17 has a concrete ABI change: [AOSP 22f1c9ee92b146e10c8f5e71338618f77ab1631e](https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineCustomImageConfig.java) removes the `appDomain` boolean. The driver supports the nine- and ten-argument constructors through a direct-call compatibility library, preferring the SDK-appropriate signature and trying the other only on `NoSuchMethodError`. Legacy exports keep `appDomain=true`. This corrects the reported old-signature failure without changing Android policy or guest images. Target-device permissions and actual mounts still require validation. See the runtime module's SharedPath ABI compatibility section for the compile-only declarations and OEM jar build boundary.

On the target device, reproduce the failure and inspect only its AVF hidden-API log entries:

```sh
adb logcat -d -v brief | rg 'virtualmachine/VirtualMachineCustomImageConfig|hiddenapi|Accessing hidden'
adb shell settings get global hidden_api_policy
adb pull /apex/com.android.virt/javalib/framework-virtualization.jar
```

An entry identifying the constructor with `linking, denied` establishes policy denial. Inspect the pulled framework DEX to confirm the actual constructor signature if no denial is observed; reflection also filters blocked methods and is not proof of their absence. For a real signature difference, compile against the compatible OEM implementation using `-PavfFrameworkJar=...` and adapt the direct driver calls. For policy denial, provision a product that authorizes its private platform APIs or an explicitly authorized development device under the Android-documented policy. The APK does not change device-wide enforcement, grant itself an exemption, or replace the framework implementation.

For an operator-authorized development device, Android documents `adb shell settings put global hidden_api_policy 1` to allow non-SDK access while retaining warning logs. Record the previous setting first, save live session work before restarting the app processes, and restore that previous setting after testing (`adb shell settings delete global hidden_api_policy` restores the default only when the original value was unset). This is a device-wide development policy, not an APK installation step or a production deployment fix. Repeating the constructor call after restarting the app tests policy denial; a persistent linkage error still requires comparison with the target framework ABI.
