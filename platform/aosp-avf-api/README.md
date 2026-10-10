# Compile-only AVF API declarations

The subset includes `VirtualMachineCustomImageConfig.SharedPath`'s ten-argument constructor and `Builder.addSharedPath`, pinned to the same revision as the image/VM APIs. The final arguments select app-domain virtiofs and its per-boot socket path. These declarations are used only for verified images advertising live sharing; older guests need no new sharing API. They remain compile-only and grant no device access.

This module contains only the Java declarations needed to compile the packaged AOSP AVF driver. It is consumed through `compileOnly` by the app and driver modules; its classes must never be packaged in the APK. Every method body throws to make accidental execution explicit. The device, not this module, supplies the actual AVF framework implementation.

The signatures and constants come from the [AOSP Virtualization sources at 175a51b30123fa6b02b541f1969665708f7ec2c3](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/libs/framework-virtualization/src/android/system/virtualmachine/). This is a minimal ABI subset, not an SDK replacement, permission grant, or hidden-API bypass. Custom-image configuration is a hidden platform API, so the target device must expose the matching methods and permit their use. OEM builds may replace these declarations with the target's full `framework-virtualization.jar` using `-PavfFrameworkJar=...`.

Run `python3 tools/avf/verify-driver-apk.py <apk>` after packaging. It checks DEX class definitions, requiring the driver while rejecting every `android.system.virtualmachine` class definition in the APK. See `../aosp-avf-runtime/README.md` for device requirements and integration.
