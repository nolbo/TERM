# AVF notes

## Deployment constraint

The custom Linux VM path needs `MANAGE_VIRTUAL_MACHINE` and
`USE_CUSTOM_VIRTUAL_MACHINE`. These are privileged/signature permissions on the
relevant Android releases. Declaring them in a normal APK does not grant them.

## Current spike

`PlatformProbeAvfDriver` reports one of these explicit outcomes:

1. AVF feature missing;
2. device supports AVF but this app lacks privileged deployment access; or
3. privileged access is present but the AOSP system-SDK driver has not yet been
   linked into this public-SDK variant.

This prevents a false claim that the application can boot a Debian VM on a
stock Play Store installation.

## Debian image contract

The initial runtime accepts only a verified Debian ARM64 image directory:

```text
system.img
home.img
config.img
vmlinuz
initrd.img
manifest.properties
```

`manifest.properties` is format version 1 and contains SHA-256 hashes for all
the boot artifacts and three disk artifacts. `DebianImageVerifier` validates the complete layout,
architecture, distribution, non-empty files, and hashes before the AVF platform
probe proceeds. This is not yet an image downloader or ISO importer.

The installer accepts only a complete staging directory, copies it into a
sibling temporary directory, verifies it again, and then promotes it to the VM
location. It never replaces an existing image; upgrade/rollback policy remains
a separate future design decision.

Image download is a one-time WorkManager task, not a VM owner. It accepts only
a release ID from a bundled/OEM catalog; the catalog pins an HTTPS manifest URL,
its SHA-256 hash, and an artifact-size limit. The public source build ships an
empty catalog until a product deployment provides an approved Debian release.

## Host vsock binding

The AOSP `VirtualMachine` system API exposes `connectVsock(port)` as a
`ParcelFileDescriptor`. The runtime wraps that descriptor in
`ParcelFileDescriptorFrameTransport`, which validates complete protocol frames
and owns descriptor closure. The UI never receives a file descriptor or direct
vsock access.

## Privileged AOSP deployment driver

`platform/aosp-avf-runtime` contains `AospAvfPlatformDriver`, deliberately
outside the normal Gradle build. It is compiled only by a platform/OEM product
variant with the matching AOSP system SDK. It creates/reuses a named VM with a
custom image configuration, waits for the AVF ready callback after `run()`, and
then retries port 40500 until `droid-sessiond` accepts a vsock connection.
Later AVF errors and stop callbacks are fed back into `AvfBackend`, so the UI
does not claim that a dead VM still owns persistent sessions.

The first disk mapping is fixed and part of the signed image ABI:

```text
vda=system.img (RO), vdb=home.img (RW), vdc=config.img (RO)
```

AVF `getOrCreate` retains an existing VM configuration. Image upgrade/reset is
therefore deliberately deferred to an explicit migration design; the runtime
does not replace an image under an existing VM instance.

Legacy storage adoption assigns a registry UUID without moving the image or replacing
`droid-default`. It waits until the service can reach that VM's PTY owner and prove no
active sessions. The persisted runtime-to-UUID association resolves the adopted UUID
back to `vm/default`, `shared-vms/default` and `droid-default`; the AOSP deletion driver
verifies the original instance is stopped before deleting it. A disconnected/unavailable
VM remains protected. This path still requires validation on the matching privileged
AOSP product; the public-SDK build does not exercise VirtualMachineManager.

## Next validation sequence

1. Build the driver against the target AOSP system SDK.
2. Provision one known-good ARM64 Debian image and immutable config image.
3. Create/start the VM from `VmRuntimeService`.
4. Establish a vsock connection to `droid-sessiond`.
5. Prove attach/detach/reconnect against one PTY before adding capabilities,
   GUI, ISO import, or multi-distro support.
